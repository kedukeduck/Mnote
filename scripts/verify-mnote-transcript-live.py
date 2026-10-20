#!/usr/bin/env python3
"""Operator-only account transcript smoke test, using original CC0 speech only.

Run with the deployed capture-server Python as root. Creates two isolated test
accounts, checks HTTPS owner isolation, imports an ASR result into a synthetic
record, then revokes test credentials and quarantines only their own vaults.
Never prints credentials or reads existing account records/transcripts.
"""
import argparse
import json
import secrets
import sqlite3
import tempfile
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener

from heartnote_capture.accounts import Accounts
from heartnote_capture.store import CaptureStore


BASE = "https://chenyu.online/heartnote-capture"
SAMPLE = "https://chenyu.online/mnote-transcript-api/demo/sample.wav"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=Path("/var/lib/heartnote-capture"))
    args = parser.parse_args()
    assert args.data.resolve() == Path("/var/lib/heartnote-capture")
    quarantine = Path(tempfile.mkdtemp(prefix="mnote-transcript-verify.", dir="/var/backups"))
    quarantine.chmod(0o700)
    accounts = Accounts(CaptureStore(args.data))
    opener = build_opener(NoRedirect())
    sessions, users, jobs = [], [], []

    def request(method, path, session=None, body=None, extra=None):
        headers = {"Accept": "application/json", "Cache-Control": "no-store"}
        if session:
            headers["Authorization"] = "Bearer " + session["access_token"]
        headers.update(extra or {})
        if body is not None:
            headers["Content-Type"] = "application/json"
        req = Request(BASE + path, method=method, headers=headers,
                      data=json.dumps(body).encode() if body is not None else None)
        try:
            response = opener.open(req, timeout=25)
        except HTTPError as error:
            response = error
        with response:
            assert response.headers.get("Cache-Control") == "no-store"
            return response.status, json.loads(response.read(8 * 1024 * 1024))

    try:
        for _ in range(2):
            username = "transcript-qa-" + uuid.uuid4().hex[:12]
            users.append(username)
            sessions.append(accounts.activate(username, secrets.token_urlsafe(32), accounts.invite()))
        first, second = sessions
        assert request("GET", "/v1/transcripts/config")[0] == 401
        status, config = request("GET", "/v1/transcripts/config", first)
        assert status == 200 and config["ready"]
        payload = {"share_text": SAMPLE, "rights_basis": "open_license",
                   "rights_note": "Original Mnote synthetic speech, CC0-1.0; "
                                  "https://chenyu.online/mnote-transcript-api/demo/license",
                   "agreement_version": config["agreement_version"], "consent": False,
                   "client_request_id": str(uuid.uuid4())}
        assert request("POST", "/v1/transcripts/jobs", first, payload)[0] == 400
        payload["consent"] = True
        status, data = request("POST", "/v1/transcripts/jobs", first, payload,
                               {"X-Owner-Id": "untrusted-client-owner"})
        assert status == 202, (status, data.get("error"))
        ident = data["job"]["id"]
        uuid.UUID(ident)
        path = "/v1/transcripts/jobs/" + ident
        jobs.append((first, path))
        status, retried = request("POST", "/v1/transcripts/jobs", first, payload)
        assert status == 202 and retried["job"]["id"] == ident, "Duplicate POST created another task"
        assert request("GET", path, second)[0] == 404
        assert request("DELETE", path, second)[0] == 404
        deadline, job = time.monotonic() + 180, data["job"]
        while time.monotonic() < deadline:
            status, data = request("GET", path, first)
            assert status == 200
            job = data["job"]
            if job["status"] in ("completed", "failed"):
                break
            time.sleep(2)
        assert job["status"] == "completed" and "recording" in job["text"].lower(), job.get("error")
        assert 0 < len(job["text"]) <= 100000
        capture_id = "transcript-qa-" + uuid.uuid4().hex
        record_path = "/v1/captures/" + capture_id
        record = {"id": capture_id, "comment": "Synthetic import verification; not a real user note",
                  "source": {"type": "video_transcript", "text_origin": "video_transcript",
                             "text": job["text"], "url": SAMPLE}, "ai_access": "deny"}
        assert request("PUT", record_path, first, record)[0] == 200
        status, saved = request("GET", record_path, first)
        assert status == 200 and saved["source"]["text"] == job["text"]
        assert saved["source"]["type"] == "video_transcript"
        assert request("GET", record_path, second)[0] == 404
        assert request("DELETE", record_path, first, extra={"If-Match": '"revision:1"'})[0] == 200
        assert request("DELETE", path, first)[0] == 200
        assert request("GET", path, first)[0] == 404
        print("PASS: HTTPS account auth, explicit consent, idempotent POST, owner-header override, cross-account GET/DELETE isolation,")
        print("CC0 full ASR, transcript record save/read/delete and job cleanup; no existing user records accessed.")
    finally:
        failures = []
        for session, path in jobs:
            try:
                assert request("DELETE", path, session)[0] in (200, 404)
            except Exception as error:
                failures.append(type(error).__name__)
        for username in users:
            with accounts.db() as db:
                row = db.execute("SELECT id,vault FROM accounts WHERE username=?", (username,)).fetchone()
                if not row:
                    continue
                assert row["vault"] != "legacy" and len(row["vault"]) == 32
                db.execute("BEGIN IMMEDIATE")
                db.execute("DELETE FROM sessions WHERE account_id=?", (row["id"],))
                db.execute("DELETE FROM accounts WHERE id=? AND username=?", (row["id"], username))
                db.commit()
                vault = args.data / "account-vaults" / row["vault"]
                if vault.is_dir():
                    vault.rename(quarantine / ("synthetic-vault-" + row["vault"]))
        print("Synthetic accounts revoked; recoverable QA vaults: " + str(quarantine))
        if failures:
            raise RuntimeError("Review QA job cleanup: " + ", ".join(failures))


if __name__ == "__main__":
    main()
