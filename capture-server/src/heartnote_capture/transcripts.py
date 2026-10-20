"""Account-only adapter to the isolated local media-transcription service.

The application never receives the core bearer token and may never choose an
upstream, owner, or proxy path. Admission reservations survive server restarts;
an uncertain POST is not retried or refunded (the core may already have accepted).
"""
from __future__ import annotations

import contextlib
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import re
import secrets
import socket
import sqlite3
import threading
import time

MAX_BODY = 16 * 1024
MAX_RESPONSE = 8 * 1024 * 1024
UPSTREAM_HOST, UPSTREAM_PORT = "127.0.0.1", 8791
UPSTREAM_TIMEOUT = 25
RETENTION_SECONDS = 24 * 3600
JOB_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
MESSAGES = {
    "not_configured": "当前服务器尚未启用视频文字稿解析。",
    "not_found": "文字稿任务不存在、已过期或不属于当前账号。",
    "invalid_request": "请求格式不正确。",
    "request_conflict": "该提交标识已用于另一份内容，请重新发起解析。",
    "account_busy": "当前账号已有进行中的任务，请等待完成或取消后再提交。",
    "account_limit": "每个账号每小时最多提交 3 次，请稍后再试。",
    "daily_limit": "文字稿服务今日已达到 40 次提交上限，请次日再试。",
    "submission_uncertain": "上次提交结果尚未确认，请勿重复提交；限制将在 24 小时内解除。",
    "upstream_unavailable": "文字稿解析暂时不可用，请稍后重试。",
    "storage_unavailable": "文字稿任务状态暂时不可用，请稍后重试。",
}


class TranscriptError(Exception):
    def __init__(self, code, status=400, retry_after=None):
        self.code, self.status, self.retry_after = code, status, retry_after
        super().__init__(MESSAGES[code])


class ExistingRequest(Exception):
    def __init__(self, job_id):
        self.job_id = job_id


class Admissions:
    """A small private quota/ownership ledger, never a copy of media or text."""
    def __init__(self, root: Path):
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path, self.lock = root / "admissions.sqlite3", threading.RLock()
        with self.db() as db:
            db.execute("CREATE TABLE IF NOT EXISTS attempts (id TEXT PRIMARY KEY, owner TEXT NOT NULL, created REAL NOT NULL, job_id TEXT, status TEXT NOT NULL)")
            db.execute("CREATE INDEX IF NOT EXISTS transcript_owner_time ON attempts(owner,created)")
            db.execute("CREATE INDEX IF NOT EXISTS transcript_job ON attempts(owner,job_id)")
            db.execute("CREATE TABLE IF NOT EXISTS active (owner TEXT PRIMARY KEY, attempt TEXT NOT NULL)")
            # Minimal permanent idempotency tombstones: no URL, declaration or text.
            # The global 40/day admission ceiling bounds growth to ~15k rows/year.
            db.execute("CREATE TABLE IF NOT EXISTS requests (owner TEXT NOT NULL, request_id TEXT NOT NULL, fingerprint TEXT NOT NULL, attempt TEXT NOT NULL, job_id TEXT, status TEXT NOT NULL, PRIMARY KEY(owner,request_id))")
            db.execute("UPDATE attempts SET status='uncertain' WHERE status='reserved'")
            db.execute("UPDATE requests SET status='uncertain' WHERE status='reserved'")
        os.chmod(self.path, 0o600)

    @contextlib.contextmanager
    def db(self):
        with self.lock:
            db = sqlite3.connect(self.path, timeout=10)
            try:
                db.execute("PRAGMA secure_delete=ON")
                db.execute("BEGIN IMMEDIATE")
                yield db
                db.commit()
            except Exception:
                db.rollback()
                raise
            finally:
                db.close()

    @staticmethod
    def prune(db, now):
        db.execute("UPDATE requests SET status='expired' WHERE status IN ('reserved','uncertain') AND attempt IN (SELECT id FROM attempts WHERE created<=?)", (now - RETENTION_SECONDS,))
        db.execute("DELETE FROM attempts WHERE created<=?", (now - RETENTION_SECONDS,))
        db.execute("DELETE FROM active WHERE attempt NOT IN (SELECT id FROM attempts)")

    def current(self, owner):
        with self.db() as db:
            self.prune(db, time.time())
            return db.execute("SELECT a.job_id FROM active v JOIN attempts a ON v.attempt=a.id WHERE v.owner=?", (owner,)).fetchone()

    @staticmethod
    def _check_request(db, owner, request_id, fingerprint):
        if request_id is None:
            return
        row = db.execute("SELECT fingerprint,job_id,status FROM requests WHERE owner=? AND request_id=?", (owner, request_id)).fetchone()
        if row:
            if row[0] != fingerprint:
                raise TranscriptError("request_conflict", 409)
            if row[2] == "expired":
                raise TranscriptError("not_found", 404)
            if row[2] == "accepted" and row[1]:
                raise ExistingRequest(row[1])
            raise TranscriptError("submission_uncertain", 429, 30)

    def check_request(self, owner, request_id, fingerprint):
        with self.db() as db:
            self.prune(db, time.time())
            self._check_request(db, owner, request_id, fingerprint)

    def reserve(self, owner, request_id=None, fingerprint=None):
        now = time.time()
        with self.db() as db:
            self.prune(db, now)
            self._check_request(db, owner, request_id, fingerprint)
            row = db.execute("SELECT a.status,a.created FROM active v JOIN attempts a ON v.attempt=a.id WHERE v.owner=?", (owner,)).fetchone()
            if row:
                uncertain = row[0] == "uncertain"
                raise TranscriptError("submission_uncertain" if uncertain else "account_busy", 429,
                                      max(1, math.ceil(row[1] + RETENTION_SECONDS - now)) if uncertain else 30)
            day = math.floor(now / RETENTION_SECONDS) * RETENTION_SECONDS
            if db.execute("SELECT COUNT(*) FROM attempts WHERE created>=?", (day,)).fetchone()[0] >= 40:
                raise TranscriptError("daily_limit", 429, max(1, math.ceil(day + RETENTION_SECONDS - now)))
            rows = db.execute("SELECT created FROM attempts WHERE owner=? AND created>? ORDER BY created", (owner, now - 3600)).fetchall()
            if len(rows) >= 3:
                raise TranscriptError("account_limit", 429, max(1, math.ceil(rows[0][0] + 3600 - now)))
            ident = secrets.token_hex(16)
            db.execute("INSERT INTO attempts VALUES (?,?,?,NULL,'reserved')", (ident, owner, now))
            db.execute("INSERT INTO active VALUES (?,?)", (owner, ident))
            if request_id is not None:
                db.execute("INSERT INTO requests VALUES (?,?,?,?,NULL,'reserved')", (owner, request_id, fingerprint, ident))
            return ident

    def accepted(self, reservation, ident):
        with self.db() as db:
            db.execute("UPDATE attempts SET job_id=?,status='accepted' WHERE id=?", (ident, reservation))
            db.execute("UPDATE requests SET job_id=?,status='accepted' WHERE attempt=?", (ident, reservation))

    def uncertain(self, reservation):
        with self.db() as db:
            db.execute("UPDATE attempts SET status='uncertain' WHERE id=?", (reservation,))
            db.execute("UPDATE requests SET status='uncertain' WHERE attempt=?", (reservation,))

    def rejected(self, reservation):
        with self.db() as db:
            db.execute("DELETE FROM active WHERE attempt=?", (reservation,))
            db.execute("DELETE FROM attempts WHERE id=?", (reservation,))
            db.execute("DELETE FROM requests WHERE attempt=?", (reservation,))

    def owns(self, owner, ident):
        with self.db() as db:
            self.prune(db, time.time())
            return db.execute("SELECT 1 FROM attempts WHERE owner=? AND job_id=? AND status='accepted'", (owner, ident)).fetchone() is not None

    def finished(self, owner, ident):
        with self.db() as db:
            db.execute("DELETE FROM active WHERE owner=? AND attempt IN (SELECT id FROM attempts WHERE owner=? AND job_id=?)", (owner, owner, ident))


class CoreClient:
    def __init__(self, token):
        self.token = token

    def request(self, method, path, owner, body=None):
        # HTTPConnection never follows Location. No client headers are forwarded.
        connection = http.client.HTTPConnection(UPSTREAM_HOST, UPSTREAM_PORT, timeout=UPSTREAM_TIMEOUT)
        original_socket = [None]
        expired = threading.Event()
        def abort():
            expired.set()
            sock = original_socket[0] or connection.sock
            if sock:
                with contextlib.suppress(OSError):
                    sock.shutdown(socket.SHUT_RDWR)
            connection.close()
        timer = threading.Timer(UPSTREAM_TIMEOUT, abort)
        timer.daemon = True
        timer.start()
        try:
            encoded = None if body is None else json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            connection.request(method, path, body=encoded, headers={
                "Authorization": "Bearer " + self.token, "X-Owner-Id": owner,
                "Content-Type": "application/json", "Accept-Encoding": "identity",
            })
            original_socket[0] = connection.sock
            response = connection.getresponse()
            if 300 <= response.status < 400 or response.status in (401, 403) or response.status >= 500:
                raise TranscriptError("upstream_unavailable", 503)
            if response.getheader("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json":
                raise TranscriptError("upstream_unavailable", 502)
            length = response.getheader("Content-Length")
            if length is not None and (not re.fullmatch(r"[0-9]{1,10}", length) or int(length) > MAX_RESPONSE):
                raise TranscriptError("upstream_unavailable", 502)
            raw = response.read(MAX_RESPONSE + 1)
            if expired.is_set() or len(raw) > MAX_RESPONSE or (length is not None and len(raw) != int(length)):
                raise TranscriptError("upstream_unavailable", 502)
            payload = json.loads(raw)
            if not isinstance(payload, dict):
                raise ValueError()
            return response.status, payload
        except (OSError, http.client.HTTPException, ValueError, UnicodeError, RecursionError):
            raise TranscriptError("upstream_unavailable", 503) from None
        finally:
            timer.cancel()
            connection.close()


def public_payload(status, payload):
    """Only contract fields leave the core boundary; private URLs/keys cannot."""
    if status >= 400:
        error = payload.get("error")
        if not isinstance(error, dict) or not isinstance(error.get("code"), str) or not isinstance(error.get("message"), str):
            raise TranscriptError("upstream_unavailable", 502)
        return {"error": {"code": error["code"][:100], "message": error["message"][:2000]}}
    job = payload.get("job")
    if not isinstance(job, dict) or not isinstance(job.get("id"), str) or not JOB_RE.fullmatch(job["id"]):
        raise TranscriptError("upstream_unavailable", 502)
    result = {key: job[key] for key in ("id", "status", "stage", "progress", "text", "created_at", "updated_at", "expires_at") if key in job}
    if result.get("status") not in ("queued", "processing", "completed", "failed"):
        raise TranscriptError("upstream_unavailable", 502)
    source = job.get("source", {})
    result["source"] = {key: source[key] for key in ("platform", "host", "title", "duration_seconds") if isinstance(source, dict) and key in source}
    rights = job.get("rights", {})
    result["rights"] = {key: rights[key] for key in ("basis", "note", "agreement_version", "consented_at", "verified") if isinstance(rights, dict) and key in rights}
    segments = job.get("segments", [])
    if not isinstance(segments, list) or len(segments) > 20000:
        raise TranscriptError("upstream_unavailable", 502)
    result["segments"] = [{key: item[key] for key in ("start", "end", "text") if key in item} for item in segments if isinstance(item, dict)]
    result["error"] = None
    if job.get("error") is not None:
        result["error"] = public_payload(400, {"error": job["error"]})["error"]
    return {"job": result}


class Transcripts:
    def __init__(self, root: Path, token=None):
        supplied = os.environ.get("HEARTNOTE_CAPTURE_TRANSCRIPT_TOKEN", "") if token is None else token
        # Missing/invalid optional configuration must not stop ordinary sync.
        self.configured = isinstance(supplied, str) and bool(re.fullmatch(r"[\x21-\x7e]{32,512}", supplied))
        self.client = CoreClient(supplied) if self.configured else None
        self.root, self._admissions, self._lock = Path(root) / "transcripts", None, threading.Lock()

    @property
    def admissions(self):
        with self._lock:
            if self._admissions is None:
                self._admissions = Admissions(self.root)
            return self._admissions

    @staticmethod
    def owner(account_id):
        # Namespace separates app accounts from public-demo visitors and other consumers.
        return hashlib.sha256(("mnote-account-transcripts-v1:" + account_id).encode("utf-8")).hexdigest()

    def config(self, account_id):
        if not self.configured:
            return {"ready": False, "configured": False, "agreement_version": "2026-10-12-v1",
                    "max_duration_seconds": 1800, "retention_hours": 24, "message": MESSAGES["not_configured"]}
        status, value = self.client.request("GET", "/v1/config", self.owner(account_id))
        if status != 200 or not isinstance(value.get("ready"), bool) or not isinstance(value.get("agreement_version"), str):
            raise TranscriptError("upstream_unavailable", 502)
        return {key: value[key] for key in ("ready", "agreement_version", "max_duration_seconds", "retention_hours", "message") if key in value} | {"configured": True}

    def submit(self, account_id, body):
        if not self.configured:
            raise TranscriptError("not_configured", 503)
        if not isinstance(body, dict):
            raise TranscriptError("invalid_request")
        owner, quotas = self.owner(account_id), self.admissions
        request_id = body.get("client_request_id")
        if request_id is not None and (not isinstance(request_id, str) or not JOB_RE.fullmatch(request_id)):
            raise TranscriptError("invalid_request")
        # Drop all unknown keys (including owner/token/upstream) rather than proxy them.
        request = {key: body[key] for key in ("share_text", "rights_basis", "rights_note", "agreement_version", "consent") if key in body}
        fingerprint = hashlib.sha256(json.dumps(request, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()
        try:
            quotas.check_request(owner, request_id, fingerprint)
            active = quotas.current(owner)
            if active and active[0]:
                status, result = self.client.request("GET", "/v1/jobs/" + active[0], owner)
                if status == 404 or (status == 200 and public_payload(status, result)["job"]["status"] in ("completed", "failed")):
                    quotas.finished(owner, active[0])
            reservation = quotas.reserve(owner, request_id, fingerprint)
        except ExistingRequest as replay:
            # Core ownership is still checked, including after local 24h admission expiry.
            status, result = self.client.request("GET", "/v1/jobs/" + replay.job_id, owner)
            clean = public_payload(status, result)
            if status == 404:
                raise TranscriptError("not_found", 404)
            if status != 200 or clean["job"]["id"] != replay.job_id:
                raise TranscriptError("upstream_unavailable", 502)
            return 202, clean
        try:
            status, result = self.client.request("POST", "/v1/jobs", owner, request)
            clean = public_payload(status, result)
            if 400 <= status < 500:
                quotas.rejected(reservation)
                return status, clean
            if status != 202:
                raise TranscriptError("upstream_unavailable", 502)
            quotas.accepted(reservation, clean["job"]["id"])
            return status, clean
        except Exception:
            quotas.uncertain(reservation)
            raise

    def job(self, method, account_id, ident):
        if not self.configured:
            raise TranscriptError("not_configured", 503)
        if method not in ("GET", "DELETE") or not JOB_RE.fullmatch(ident):
            raise TranscriptError("not_found", 404)
        owner, quotas = self.owner(account_id), self.admissions
        if not quotas.owns(owner, ident):
            raise TranscriptError("not_found", 404)
        status, payload = self.client.request(method, "/v1/jobs/" + ident, owner)
        if method == "DELETE" and status == 200:
            if payload.get("deleted") is not True or payload.get("id") != ident:
                raise TranscriptError("upstream_unavailable", 502)
            quotas.finished(owner, ident)
            return 200, {"deleted": True, "id": ident}
        result = public_payload(status, payload)
        if status == 200 and result["job"]["id"] != ident:
            raise TranscriptError("upstream_unavailable", 502)
        if status == 404 or (method == "GET" and status == 200 and result["job"]["status"] in ("completed", "failed")):
            quotas.finished(owner, ident)
        return status, result
