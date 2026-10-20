from __future__ import annotations

import copy
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
import uuid

from heartnote_capture.http_api import Tokens, create_server
from heartnote_capture.store import CaptureStore
from heartnote_capture.transcripts import (
    Admissions, CoreClient, MAX_RESPONSE, TranscriptError, Transcripts, public_payload,
)


TOKEN = "test-transcription-service-token-no-real-secret"
BODY = {"share_text": "https://www.bilibili.com/video/BVfixture",
        "rights_basis": "authorized", "rights_note": "作者已授权处理该测试内容", "consent": True,
        "agreement_version": "2026-10-12-v1"}


def job(ident=None, status="queued"):
    return {"id": ident or str(uuid.uuid4()), "status": status, "stage": status, "progress": 100 if status == "completed" else 0,
            "source": {"platform": "bilibili", "host": "www.bilibili.com", "title": "视频标题"},
            "text": "完整文字稿" if status == "completed" else "", "segments": [], "error": None,
            "rights": {"basis": "authorized", "note": BODY["rights_note"], "agreement_version": BODY["agreement_version"], "verified": False}}


class FakeCore:
    def __init__(self):
        self.calls, self.jobs, self.failure, self.rejection = [], {}, None, None

    def request(self, method, path, owner, body=None):
        self.calls.append((method, path, owner, copy.deepcopy(body)))
        if self.failure:
            raise self.failure
        if path == "/v1/config":
            return 200, {"ready": True, "agreement_version": BODY["agreement_version"], "retention_hours": 24,
                         "max_duration_seconds": 1800, "message": "已就绪", "private_secret": "never expose"}
        if method == "POST":
            if self.rejection:
                return self.rejection
            value = job()
            self.jobs[value["id"]] = owner, value
            return 202, {"job": copy.deepcopy(value)}
        ident = path.rsplit("/", 1)[-1]
        item = self.jobs.get(ident)
        if item is None or item[0] != owner:
            return 404, {"error": {"code": "not_found", "message": "不存在"}}
        if method == "DELETE":
            del self.jobs[ident]
            return 200, {"deleted": True, "id": ident}
        return 200, {"job": copy.deepcopy(item[1])}


class TranscriptServiceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.service = Transcripts(self.root, TOKEN)
        self.core = self.service.client = FakeCore()

    def tearDown(self):
        self.temp.cleanup()

    def test_config_optional_and_allowlisted(self):
        missing = Transcripts(self.root, "")
        self.assertFalse(missing.config("account")["configured"])
        self.assertFalse(missing.config("account")["ready"])
        self.assertFalse((self.root / "transcripts").exists())
        with self.assertRaises(TranscriptError) as raised:
            missing.submit("account", BODY)
        self.assertEqual("not_configured", raised.exception.code)
        for invalid in ("x" * 20, "x" * 40 + "\n", "中" * 40):
            self.assertFalse(Transcripts(self.root, invalid).configured)
        configured = self.service.config("account")
        self.assertTrue(configured["ready"])
        self.assertTrue(configured["configured"])
        self.assertNotIn("private_secret", configured)

    def test_submit_read_delete_and_cross_account_isolation(self):
        status, result = self.service.submit("first", BODY | {"owner": "second", "token": "bad", "upstream": "https://attacker.invalid"})
        ident = result["job"]["id"]
        self.assertEqual(202, status)
        call = self.core.calls[0]
        self.assertEqual(BODY, call[3])
        self.assertEqual(64, len(call[2]))
        self.assertNotEqual(call[2], self.service.owner("second"))
        self.assertEqual(200, self.service.job("GET", "first", ident)[0])
        previous_calls = len(self.core.calls)
        for method in ("GET", "DELETE"):
            with self.assertRaises(TranscriptError) as raised:
                self.service.job(method, "second", ident)
            self.assertEqual("not_found", raised.exception.code)
        self.assertEqual(previous_calls, len(self.core.calls))
        self.assertEqual((200, {"deleted": True, "id": ident}), self.service.job("DELETE", "first", ident))
        self.assertEqual(404, self.service.job("GET", "first", ident)[0])

    def test_active_job_and_successful_completion_unlock(self):
        _, result = self.service.submit("account", BODY)
        ident = result["job"]["id"]
        with self.assertRaises(TranscriptError) as raised:
            self.service.submit("account", BODY)
        self.assertEqual("account_busy", raised.exception.code)
        self.core.jobs[ident][1]["status"] = "completed"
        self.assertEqual(202, self.service.submit("account", BODY)[0])

    def test_poll_terminal_and_missing_jobs_unlock(self):
        for terminal in ("completed", "failed", "missing"):
            account = "account-" + terminal
            _, result = self.service.submit(account, BODY)
            ident = result["job"]["id"]
            if terminal == "missing":
                del self.core.jobs[ident]
            else:
                self.core.jobs[ident][1]["status"] = terminal
            self.service.job("GET", account, ident)
            self.assertEqual(202, self.service.submit(account, BODY)[0])

    def test_accepted_deleted_and_failed_jobs_still_count(self):
        for _ in range(3):
            _, result = self.service.submit("account", BODY)
            self.service.job("DELETE", "account", result["job"]["id"])
        with self.assertRaises(TranscriptError) as raised:
            self.service.submit("account", BODY)
        self.assertEqual("account_limit", raised.exception.code)
        self.assertGreater(raised.exception.retry_after, 0)

    def test_rejected_request_preserves_error_and_refunds_reservation(self):
        self.core.rejection = (400, {"error": {"code": "consent_required", "message": "请确认授权", "debug": "hidden"}})
        for _ in range(5):
            status, result = self.service.submit("account", BODY)
            self.assertEqual(400, status)
            self.assertEqual({"error": {"code": "consent_required", "message": "请确认授权"}}, result)
        self.core.rejection = None
        self.assertEqual(202, self.service.submit("account", BODY)[0])

    def test_unknown_post_outcome_stays_blocked_across_restart(self):
        self.core.failure = TranscriptError("upstream_unavailable", 503)
        with self.assertRaises(TranscriptError):
            self.service.submit("account", BODY)
        restarted = Transcripts(self.root, TOKEN)
        restarted.client = FakeCore()
        with self.assertRaises(TranscriptError) as raised:
            restarted.submit("account", BODY)
        self.assertEqual("submission_uncertain", raised.exception.code)
        self.assertGreater(raised.exception.retry_after, 0)
        self.assertEqual([], restarted.client.calls)

    def test_reserved_crash_is_uncertain_and_expiry_prunes(self):
        owner = self.service.owner("account")
        with patch("heartnote_capture.transcripts.time.time", return_value=100000):
            self.service.admissions.reserve(owner)
            restarted = Admissions(self.root / "transcripts")
            with self.assertRaises(TranscriptError) as raised:
                restarted.reserve(owner)
            self.assertEqual("submission_uncertain", raised.exception.code)
        with patch("heartnote_capture.transcripts.time.time", return_value=200000):
            self.assertIsNotNone(restarted.reserve(owner))

    def test_global_daily_limit_and_atomic_single_account_admission(self):
        quotas = self.service.admissions
        with patch("heartnote_capture.transcripts.time.time", return_value=100000):
            for i in range(40):
                quotas.reserve(str(i))
            with self.assertRaises(TranscriptError) as raised:
                quotas.reserve("other")
            self.assertEqual("daily_limit", raised.exception.code)
        with patch("heartnote_capture.transcripts.time.time", return_value=200000):
            def attempt(_):
                try:
                    quotas.reserve("same-owner")
                    return "accepted"
                except TranscriptError as error:
                    return error.code
            with ThreadPoolExecutor(max_workers=8) as pool:
                outcomes = list(pool.map(attempt, range(8)))
            self.assertEqual(1, outcomes.count("accepted"))
            self.assertEqual(7, outcomes.count("account_busy"))

    def test_private_core_fields_never_leave_adapter(self):
        value = job(status="completed")
        value.update(private_url="https://signed.invalid/secret", token="bad", owner="bad")
        value["source"]["url"] = "https://signed.invalid/private"
        value["rights"]["token"] = "bad"
        value["segments"] = [{"start": 0, "end": 1, "text": "你好", "url": "private"}]
        clean = public_payload(200, {"job": value, "token": "bad"})
        serialized = json.dumps(clean)
        self.assertNotIn("signed.invalid", serialized)
        self.assertNotIn("token", serialized)
        self.assertNotIn("owner", serialized)
        self.assertEqual("完整文字稿", clean["job"]["text"])

    def test_malformed_success_is_uncertain_not_refunded(self):
        self.core.request = lambda *args: (202, {"job": {"id": "not-a-uuid"}})
        with self.assertRaises(TranscriptError):
            self.service.submit("account", BODY)
        with self.assertRaises(TranscriptError) as raised:
            self.service.submit("account", BODY)
        self.assertEqual("submission_uncertain", raised.exception.code)

    def test_client_request_retry_returns_same_job_without_new_admission(self):
        body = BODY | {"client_request_id": str(uuid.uuid4())}
        first = self.service.submit("account", body)
        replay = self.service.submit("account", body)
        self.assertEqual(first, replay)
        self.assertEqual(1, sum(call[0] == "POST" for call in self.core.calls))
        self.assertNotIn("client_request_id", self.core.calls[0][3])
        restarted = Transcripts(self.root, TOKEN)
        restarted.client = self.core
        self.assertEqual(first, restarted.submit("account", body))
        with restarted.admissions.db() as db:
            self.assertEqual(1, db.execute("SELECT count(*) FROM attempts").fetchone()[0])

    def test_client_request_conflict_account_isolation_and_validation(self):
        body = BODY | {"client_request_id": str(uuid.uuid4())}
        self.service.submit("first", body)
        with self.assertRaises(TranscriptError) as raised:
            self.service.submit("first", body | {"share_text": "https://www.douyin.com/video/1234567890"})
        self.assertEqual("request_conflict", raised.exception.code)
        self.assertEqual(202, self.service.submit("second", body)[0])
        for invalid in ("", "x", {}, 123, "A" * 36):
            with self.assertRaises(TranscriptError) as raised:
                self.service.submit("first", BODY | {"client_request_id": invalid})
            self.assertEqual("invalid_request", raised.exception.code)
        self.assertEqual(2, sum(call[0] == "POST" for call in self.core.calls))

    def test_deleted_or_expired_request_id_never_creates_new_job(self):
        body = BODY | {"client_request_id": str(uuid.uuid4())}
        _, result = self.service.submit("account", body)
        self.service.job("DELETE", "account", result["job"]["id"])
        for future in (time.time(), time.time() + 2 * 86400):
            with patch("heartnote_capture.transcripts.time.time", return_value=future):
                with self.assertRaises(TranscriptError) as raised:
                    self.service.submit("account", body)
                self.assertEqual("not_found", raised.exception.code)
        self.assertEqual(1, sum(call[0] == "POST" for call in self.core.calls))

    def test_uncertain_request_never_retries_and_expires_without_resubmission(self):
        body = BODY | {"client_request_id": str(uuid.uuid4())}
        self.core.failure = TranscriptError("upstream_unavailable", 503)
        with self.assertRaises(TranscriptError):
            self.service.submit("account", body)
        self.core.failure = None
        with self.assertRaises(TranscriptError) as raised:
            self.service.submit("account", body)
        self.assertEqual("submission_uncertain", raised.exception.code)
        with patch("heartnote_capture.transcripts.time.time", return_value=time.time() + 2 * 86400):
            with self.assertRaises(TranscriptError) as raised:
                self.service.submit("account", body)
            self.assertEqual("not_found", raised.exception.code)
            self.assertEqual(202, self.service.submit("account", BODY | {"client_request_id": str(uuid.uuid4())})[0])
        self.assertEqual(2, sum(call[0] == "POST" for call in self.core.calls))

    def test_simultaneous_duplicate_request_ids_submit_only_once(self):
        body = BODY | {"client_request_id": str(uuid.uuid4())}
        def attempt(_):
            try:
                return self.service.submit("account", body)[0]
            except TranscriptError as error:
                return error.code
        with ThreadPoolExecutor(max_workers=8) as pool:
            outcomes = list(pool.map(attempt, range(8)))
        self.assertTrue(all(result in (202, "submission_uncertain") for result in outcomes))
        self.assertEqual(1, sum(call[0] == "POST" for call in self.core.calls))
        self.assertEqual(202, self.service.submit("account", body)[0])


class TranscriptHTTPTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        with patch.dict(os.environ, {"HEARTNOTE_CAPTURE_TRANSCRIPT_TOKEN": TOKEN}):
            self.server = create_server("127.0.0.1", 0, CaptureStore(self.temp.name), Tokens("write-token", "read-token", "ai-token"))
        self.adapter = self.server.RequestHandlerClass.transcripts
        self.adapter.client = self.core = FakeCore()
        accounts = self.server.RequestHandlerClass.accounts
        self.token = accounts.activate("test-user", "a-long-test-password", accounts.invite())["access_token"]
        self.other = accounts.activate("second-user", "a-long-test-password", accounts.invite())["access_token"]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.temp.cleanup()

    def request(self, method, path, token=None, body=None, headers=None, raw=None):
        data = json.dumps(body).encode() if body is not None else raw
        supplied = dict(headers or {})
        if data is not None:
            supplied.setdefault("Content-Type", "application/json")
        if token:
            supplied["Authorization"] = "Bearer " + token
        request = urllib.request.Request(self.base + path, method=method, headers=supplied, data=data)
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, response.headers, json.loads(response.read())
        except urllib.error.HTTPError as error:
            return error.code, error.headers, json.loads(error.read())

    def test_account_only_including_legacy_owner(self):
        for path in ("/v1/transcripts/config", "/v1/transcripts/jobs/" + str(uuid.uuid4())):
            self.assertEqual(401, self.request("GET", path)[0])
            for token in ("read-token", "write-token", "ai-token"):
                status, _, result = self.request("GET", path, token)
                self.assertEqual(403, status)
                self.assertEqual("forbidden", result["error"]["code"])
        self.assertEqual([], self.core.calls)

    def test_config_job_progress_result_delete_no_sync_side_effect(self):
        status, headers, config = self.request("GET", "/v1/transcripts/config", self.token)
        self.assertEqual(200, status)
        self.assertTrue(config["ready"])
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertNotIn("Access-Control-Allow-Origin", headers)
        status, _, result = self.request("POST", "/v1/transcripts/jobs", self.token, BODY, {"X-Owner-Id": "attacker"})
        self.assertEqual(202, status)
        ident = result["job"]["id"]
        self.assertNotEqual("attacker", self.core.calls[-1][2])
        self.assertEqual(404, self.request("GET", "/v1/transcripts/jobs/" + ident, self.other)[0])
        self.core.jobs[ident][1].update(status="completed", stage="completed", text="完整文字稿")
        status, _, result = self.request("GET", "/v1/transcripts/jobs/" + ident, self.token)
        self.assertEqual("完整文字稿", result["job"]["text"])
        self.assertEqual(200, self.request("DELETE", "/v1/transcripts/jobs/" + ident, self.token)[0])
        self.assertEqual(404, self.request("GET", "/v1/transcripts/jobs/" + ident, self.token)[0])
        self.assertEqual([], self.request("GET", "/v1/captures", self.token)[2]["records"])

    def test_disabled_feature_leaves_sync_healthy(self):
        self.server.RequestHandlerClass.transcripts = Transcripts(Path(self.temp.name), "")
        self.assertFalse(self.request("GET", "/v1/transcripts/config", self.token)[2]["ready"])
        status, _, result = self.request("POST", "/v1/transcripts/jobs", self.token, BODY)
        self.assertEqual(503, status)
        self.assertEqual("not_configured", result["error"]["code"])
        self.assertEqual(200, self.request("GET", "/health")[0])
        self.assertEqual(200, self.request("GET", "/v1/captures", self.token)[0])

    def test_invalid_paths_body_and_unsupported_methods_not_proxied(self):
        for path in ("/v1/transcripts/config?url=https://attacker.invalid", "/v1/transcripts/config/", "/v1/transcripts/jobs/../config", "/v1/transcripts/jobs/%31", "/v1/transcripts/jobs/" + str(uuid.uuid4()) + "/download?format=txt"):
            self.assertEqual(404, self.request("GET", path, self.token)[0])
        for method in ("PUT", "DELETE", "POST"):
            self.assertEqual(404, self.request(method, "/v1/transcripts/config", self.token, {})[0])
        for raw in (b"[]", b"null", b"not-json", b"\xff", b"a" * 17000):
            self.assertEqual(400, self.request("POST", "/v1/transcripts/jobs", self.token, raw=raw)[0])
        self.assertEqual([], self.core.calls)

    def test_quota_errors_have_retry_after_and_no_secret(self):
        self.request("POST", "/v1/transcripts/jobs", self.token, BODY)
        status, headers, result = self.request("POST", "/v1/transcripts/jobs", self.token, BODY)
        self.assertEqual(429, status)
        self.assertEqual("account_busy", result["error"]["code"])
        self.assertEqual("30", headers["Retry-After"])
        self.assertNotIn(TOKEN, json.dumps(result))


class CoreClientTest(unittest.TestCase):
    def setUp(self):
        self.received = []
        self.response = (200, {"ready": True}, {})
        owner = self
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                owner.received.append((self.path, dict(self.headers)))
                status, value, headers = owner.response
                if callable(value):
                    value()
                    value = {}
                raw = value if isinstance(value, bytes) else json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                if "Content-Length" not in headers:
                    self.send_header("Content-Length", str(len(raw)))
                for key, item in headers.items():
                    self.send_header(key, item)
                self.end_headers()
                try:
                    self.wfile.write(raw)
                except OSError:
                    pass
            def log_message(self, *args):
                pass
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.patched = patch("heartnote_capture.transcripts.UPSTREAM_PORT", self.server.server_port)
        self.patched.start()
        self.client = CoreClient(TOKEN)

    def tearDown(self):
        self.patched.stop()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def test_fixed_auth_owner_and_json(self):
        self.assertEqual((200, {"ready": True}), self.client.request("GET", "/v1/config", "trusted-owner"))
        self.assertEqual("Bearer " + TOKEN, self.received[0][1]["Authorization"])
        self.assertEqual("trusted-owner", self.received[0][1]["X-Owner-Id"])
        self.assertEqual("identity", self.received[0][1]["Accept-Encoding"])

    def test_redirect_never_followed_and_auth_failure_hidden(self):
        for code in (302, 307, 401, 403, 500):
            self.response = code, {"error": "secret upstream detail"}, {"Location": "https://attacker.invalid"}
            with self.assertRaises(TranscriptError) as raised:
                self.client.request("GET", "/v1/config", "trusted-owner")
            self.assertEqual("upstream_unavailable", raised.exception.code)
            self.assertNotIn("secret", str(raised.exception))
        self.assertEqual(5, len(self.received))

    def test_invalid_json_oversize_truncated_and_timeout_fail_closed(self):
        for value, headers in ((b"[]", {}), (b"not-json", {}), (b"{}", {"Content-Length": str(MAX_RESPONSE + 1)}), (b"{}", {"Content-Length": "5"})):
            self.response = 200, value, headers
            with self.assertRaises(TranscriptError):
                self.client.request("GET", "/v1/config", "trusted-owner")
        self.response = 200, lambda: time.sleep(0.2), {}
        with patch("heartnote_capture.transcripts.UPSTREAM_TIMEOUT", 0.03):
            start = time.monotonic()
            with self.assertRaises(TranscriptError):
                self.client.request("GET", "/v1/config", "trusted-owner")
            self.assertLess(time.monotonic() - start, 0.15)


if __name__ == "__main__":
    unittest.main()
