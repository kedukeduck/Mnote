"""Invite-only accounts; opaque revocable sessions; one physically separate vault per account."""
from __future__ import annotations

import argparse
import hashlib
import hmac
import re
import secrets
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from pathlib import Path

from .store import CaptureStore


class AuthError(ValueError):
    def __init__(self, code: str, status: int = 400):
        super().__init__(code)
        self.status = status


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


class Accounts:
    SESSION_SECONDS = 30 * 86400
    ITERATIONS = 600_000

    def __init__(self, legacy: CaptureStore):
        self.legacy = legacy
        self.path = legacy.root / "accounts.sqlite3"
        self.lock = threading.RLock()
        self.stores = {}
        with self.db() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS accounts (
                    id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL,
                    salt TEXT NOT NULL, password_hash TEXT NOT NULL,
                    vault TEXT UNIQUE NOT NULL, created_at INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS sessions (
                    digest TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id),
                    expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS invitations (
                    digest TEXT PRIMARY KEY, vault TEXT NOT NULL, expires_at INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS attempts (
                    bucket TEXT PRIMARY KEY, started INTEGER NOT NULL, count INTEGER NOT NULL);
            """)

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=15, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            yield db
        finally:
            db.close()

    @staticmethod
    def username(value):
        if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{2,31}", value):
            raise AuthError("username_format")
        return value.lower()

    @staticmethod
    def password(value):
        if not isinstance(value, str) or not 12 <= len(value) <= 128:
            raise AuthError("password_length")
        return value

    def password_hash(self, password, salt):
        return hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), self.ITERATIONS).hex()

    def throttle(self, address, username):
        now = int(time.time())
        with self.lock, self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("DELETE FROM attempts WHERE started < ?", (now - 600,))
            for key, maximum in [("ip:" + address, 60), ("user:" + str(username)[:128].lower(), 12)]:
                bucket = digest(key)
                row = db.execute("SELECT count FROM attempts WHERE bucket=?", (bucket,)).fetchone()
                if row and row[0] >= maximum:
                    db.rollback()
                    raise AuthError("too_many_attempts", 429)
                db.execute("INSERT INTO attempts VALUES (?, ?, 1) ON CONFLICT(bucket) DO UPDATE SET count=count+1", (bucket, now))
            db.commit()

    def invite(self, *, legacy_owner=False):
        code = secrets.token_urlsafe(32)
        vault = "legacy" if legacy_owner else uuid.uuid4().hex
        with self.lock, self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            if legacy_owner and db.execute("SELECT 1 FROM accounts WHERE vault='legacy'").fetchone():
                raise AuthError("legacy_owner_already_exists")
            db.execute("DELETE FROM invitations WHERE vault=? OR expires_at < ?", (vault, int(time.time())))
            db.execute("INSERT INTO invitations VALUES (?, ?, ?)", (digest(code), vault, int(time.time()) + 48 * 3600))
            db.commit()
        return code

    def activate(self, username, password, invitation):
        username = self.username(username)
        password = self.password(password)
        if not isinstance(invitation, str) or not 20 <= len(invitation) <= 128:
            raise AuthError("invalid_invitation", 403)
        salt = secrets.token_hex(16)
        hashed = self.password_hash(password, salt)
        with self.lock, self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM invitations WHERE digest=? AND expires_at>?", (digest(invitation), int(time.time()))).fetchone()
            if row is None:
                raise AuthError("invalid_invitation", 403)
            account_id = uuid.uuid4().hex
            try:
                db.execute("INSERT INTO accounts VALUES (?, ?, ?, ?, ?, ?)",
                           (account_id, username, salt, hashed, row["vault"], int(time.time())))
            except sqlite3.IntegrityError:
                raise AuthError("account_unavailable", 409) from None
            db.execute("DELETE FROM invitations WHERE digest=?", (digest(invitation),))
            result = self._session(db, account_id, username)
            db.commit()
            return result

    def login(self, username, password):
        username = self.username(username)
        # Always perform the password hash, including unknown usernames.
        if not isinstance(password, str) or len(password) > 128:
            raise AuthError("invalid_credentials", 401)
        with self.db() as db:
            account = db.execute("SELECT * FROM accounts WHERE username=?", (username,)).fetchone()
        hashed = self.password_hash(password, account["salt"] if account else "00" * 16)
        if not account or not hmac.compare_digest(hashed, account["password_hash"]):
            raise AuthError("invalid_credentials", 401)
        with self.lock, self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            result = self._session(db, account["id"], username)
            db.commit()
            return result

    def _session(self, db, account_id, username):
        token = "mns_" + secrets.token_urlsafe(32)
        now = int(time.time())
        expires = now + self.SESSION_SECONDS
        db.execute("DELETE FROM sessions WHERE expires_at<=?", (now,))
        db.execute("DELETE FROM sessions WHERE account_id=? AND digest NOT IN (SELECT digest FROM sessions WHERE account_id=? ORDER BY created_at DESC LIMIT 15)", (account_id, account_id))
        db.execute("INSERT INTO sessions VALUES (?, ?, ?, ?)", (digest(token), account_id, expires, now))
        return {"account_id": account_id, "username": username, "access_token": token, "expires_at": expires}

    def resolve(self, token):
        if not isinstance(token, str) or not token.startswith("mns_") or len(token) > 128:
            return None
        with self.db() as db:
            row = db.execute("SELECT a.* FROM sessions s JOIN accounts a ON a.id=s.account_id WHERE s.digest=? AND s.expires_at>?", (digest(token), int(time.time()))).fetchone()
            return dict(row) if row else None

    def logout(self, token):
        with self.db() as db:
            db.execute("DELETE FROM sessions WHERE digest=?", (digest(token),))

    def store(self, account):
        vault = account["vault"]
        if vault == "legacy":
            return self.legacy
        if not re.fullmatch("[a-f0-9]{32}", vault):
            raise AuthError("invalid_account", 401)
        with self.lock:
            if vault not in self.stores:
                self.stores[vault] = CaptureStore(self.legacy.root / "account-vaults" / vault)
            return self.stores[vault]


def main():
    parser = argparse.ArgumentParser(description="Create a one-use, 48-hour Mnote account activation code (keep secret)")
    parser.add_argument("--data", required=True)
    parser.add_argument("--legacy-owner", action="store_true")
    args = parser.parse_args()
    print(Accounts(CaptureStore(Path(args.data))).invite(legacy_owner=args.legacy_owner))


if __name__ == "__main__":
    main()
