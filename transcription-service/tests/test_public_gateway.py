"""Gateway regression tests use a real local HTTP stub; no media acquisition/ASR."""
import contextlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import public_gateway as gateway


class CoreHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, value, headers=None):
        raw = value if isinstance(value, bytes) else json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Length", str(len(raw)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        with contextlib.suppress(OSError):
            self.wfile.write(raw)

    def trickle(self, raw):
        self.send_response(200)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        for value in raw:
            try:
                self.wfile.write(bytes([value]))
                self.wfile.flush()
            except OSError:
                return
            time.sleep(0.04)

    def route(self):
        owner = self.headers.get("X-Owner-Id")
        with self.server.lock:
            self.server.requests.append({"method": self.command, "path": self.path,
                "owner": owner, "auth": self.headers.get("Authorization"), "cookie": self.headers.get("Cookie")})
        if self.server.redirect:
            return self.reply(302, b"SECRET https://private.invalid/?token=PRIVATE", {"Location": "https://evil.invalid/path"})
        if self.path == "/v1/config":
            if self.server.oversize:
                return self.reply(200, b"x" * (gateway.MAX_RESPONSE + 1))
            if self.server.slow_body:
                return self.trickle(b'{"ready":true}')
            return self.reply(200, {"ready": True, "agreement_version": "synthetic-v1", "max_duration_seconds": 1800})
        if self.command == "POST" and self.path == "/v1/jobs":
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            if self.server.post_status != 202:
                return self.reply(self.server.post_status, {"error": {"code": "synthetic_rejection", "message": "Rejected fixture"}})
            ident = str(uuid.uuid4())
            job = {"id": ident, "status": self.server.job_status, "source": {"title": "Synthetic"}, "text": "Synthetic result", "segments": []}
            with self.server.lock:
                self.server.jobs[ident] = (owner, job)
            self.server.entered.set()
            if self.server.block_post:
                self.server.release.wait(3)
            if self.server.drop_after_accept:
                self.connection.shutdown(socket.SHUT_RDWR)
                return
            return self.reply(202, {"job": job})
        suffix = self.path.removeprefix("/v1/jobs/")
        ident = suffix.split("/")[0]
        with self.server.lock:
            stored = self.server.jobs.get(ident)
            if not stored or stored[0] != owner:
                return self.reply(404, {"error": {"code": "not_found", "message": "Not found"}})
            if self.command == "DELETE":
                del self.server.jobs[ident]
                return self.reply(200, {"deleted": True, "id": ident})
            job = stored[1].copy()
        if "/download?format=" in self.path:
            if self.server.slow_body:
                return self.trickle(b"Synthetic export\n")
            if self.server.truncate_body:
                self.send_response(200)
                self.send_header("Content-Length", "100")
                self.end_headers()
                self.wfile.write(b"short")
                return
            return self.reply(200, b"Synthetic export\n", {"Set-Cookie": "forbidden=SECRET", "Access-Control-Allow-Origin": "*"})
        return self.reply(200, {"job": job})

    do_GET = do_POST = do_DELETE = route


class CoreServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self):
        self.jobs, self.requests, self.lock = {}, [], threading.RLock()
        self.redirect = self.oversize = self.block_post = self.drop_after_accept = self.slow_body = False
        self.truncate_body = False
        self.post_status, self.job_status = 202, "queued"
        self.entered, self.release = threading.Event(), threading.Event()
        super().__init__(("127.0.0.1", 0), CoreHandler)

    def handle_error(self, *args):
        pass


class GatewayTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.config = gateway.Config("private-upstream-token-" + "a" * 32, "separate-session-secret-" + "b" * 32,
                                        data_dir=Path(self.temp.name))
        self.core = CoreServer()
        self.core_thread = threading.Thread(target=self.core.serve_forever, daemon=True)
        self.core_thread.start()
        self.port_patch = patch.object(gateway, "UPSTREAM_PORT", self.core.server_port)
        self.port_patch.start()
        self.service = gateway.GatewayService(self.config)
        self.server = gateway.Server(("127.0.0.1", 0), self.service)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.core.release.set()
        self.server.shutdown()
        self.core.shutdown()
        self.server.server_close()
        self.core.server_close()
        self.thread.join(2)
        self.core_thread.join(2)
        self.port_patch.stop()
        self.temp.cleanup()

    def request(self, method, suffix, cookie=None, value=None, origin="https://chenyu.online", extra=None, raw=None, absolute=False):
        headers = {"Content-Type": "application/json"}
        if cookie:
            headers["Cookie"] = cookie
        if origin is not None:
            headers["Origin"] = origin
        headers.update(extra or {})
        payload = raw if raw is not None else json.dumps(value or {"synthetic": True}).encode() if method == "POST" else None
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        connection.request(method, suffix if absolute else gateway.PREFIX + suffix, body=payload, headers=headers)
        response = connection.getresponse()
        result = response.read()
        response_headers = dict(response.getheaders())
        status = response.status
        connection.close()
        if response_headers.get("Content-Type", "").startswith("application/json"):
            result = json.loads(result)
        return status, result, response_headers

    def session(self):
        status, value, headers = self.request("GET", "config", origin=None)
        self.assertEqual(status, 200)
        return headers["Set-Cookie"].split(";", 1)[0]

    def create(self, cookie):
        status, response, headers = self.request("POST", "jobs", cookie)
        self.assertEqual(status, 202)
        return response["job"]["id"]

    def test_config_issues_secure_cookie_without_raw_session_or_bearer_in_json(self):
        status, value, headers = self.request("GET", "config", origin=None)
        self.assertEqual(status, 200)
        cookie = headers["Set-Cookie"]
        for attribute in ["HttpOnly", "Secure", "SameSite=Strict", "Path=/", "Max-Age=86400"]:
            self.assertIn(attribute, cookie)
        self.assertTrue(cookie.startswith("__Host-mnote-transcript-visitor="))
        self.assertNotIn("Domain=", cookie)
        random_id, expiry, signature = cookie.split("=", 1)[1].split(";", 1)[0].split(".")
        output = json.dumps(value)
        self.assertNotIn(random_id, output)
        self.assertNotIn(signature, output)
        self.assertNotIn(self.config.token, output)
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertEqual(headers["X-Content-Type-Options"], "nosniff")
        self.assertNotIn("Access-Control-Allow-Origin", headers)
        status, _, second_headers = self.request("GET", "config", cookie.split(";", 1)[0])
        self.assertEqual(status, 200)
        self.assertNotIn("Set-Cookie", second_headers)

    def test_signed_cookie_expiry_tampering_and_duplicates(self):
        cookie = self.session()
        value = cookie.split("=", 1)[1]
        expired, _ = self.service.sessions.issue(now=time.time() - gateway.SESSION_SECONDS - 10)
        for bad in [None, gateway.COOKIE_NAME + "=unsigned", cookie[:-1] + ("0" if cookie[-1] != "0" else "1"),
                    gateway.COOKIE_NAME + "=" + expired, cookie + "; " + cookie,
                    "mnote_transcript_visitor=" + value,
                    cookie.replace(value.split(".")[1], str(int(time.time()) + 999999))]:
            with self.subTest(bad=bad):
                self.assertEqual(self.request("POST", "jobs", bad)[0], 401)

    def test_client_owner_and_auth_are_overwritten_and_no_cookie_forwarded(self):
        cookie = self.session()
        random_id = cookie.split("=", 1)[1].split(".")[0]
        expected_owner = self.service.sessions.owner(random_id)
        status, _, _ = self.request("POST", "jobs", cookie, extra={"Authorization": "Bearer attacker", "X-Owner-Id": "attacker-owner"})
        self.assertEqual(status, 202)
        request = self.core.requests[-1]
        self.assertEqual(request["auth"], "Bearer " + self.config.token)
        self.assertEqual(request["owner"], expected_owner)
        self.assertIsNone(request["cookie"])

    def test_cross_visitor_get_download_delete_hidden(self):
        first, second = self.session(), self.session()
        ident = self.create(first)
        base = "jobs/" + ident
        for method, suffix in [("GET", base), ("GET", base + "/download?format=txt"), ("DELETE", base)]:
            with self.subTest(method=method, suffix=suffix):
                self.assertEqual(self.request(method, suffix, second)[0], 404)
        self.assertEqual(self.request("GET", base, first)[0], 200)
        self.assertEqual(self.request("GET", base)[0], 401)

    def test_csrf_external_missing_null_origin_rejected(self):
        cookie = self.session()
        ident = self.create(cookie)
        for origin in [None, "null", "https://evil.invalid", "https://chenyu.online.evil.invalid", "http://chenyu.online", "https://chenyu.online/"]:
            for method, suffix in [("POST", "jobs"), ("DELETE", "jobs/" + ident)]:
                with self.subTest(origin=origin, method=method):
                    self.assertEqual(self.request(method, suffix, cookie, origin=origin, extra={"X-Forwarded-Host": "chenyu.online", "Host": "chenyu.online"})[0], 403)

    def test_request_json_body_limit_and_type(self):
        cookie = self.session()
        self.assertEqual(self.request("POST", "jobs", cookie, raw=b"x" * (gateway.MAX_BODY + 1))[0], 413)
        self.assertEqual(self.request("POST", "jobs", cookie, raw=b"{")[0], 400)
        self.assertEqual(self.request("POST", "jobs", cookie, raw=b"[]")[0], 400)
        self.assertEqual(self.request("POST", "jobs", cookie, raw=b"{}", extra={"Content-Type": "text/plain"})[0], 415)
        self.assertEqual(self.request("POST", "jobs", cookie, raw=b"{}", extra={"Transfer-Encoding": "chunked"})[0], 400)
        self.assertEqual(len([r for r in self.core.requests if r["method"] == "POST"]), 0)

    def test_fixed_path_allowlist_rejects_encoding_query_absolute_form(self):
        cookie = self.session()
        ident = self.create(cookie)
        for method, path in [("GET", "config?evil=1"), ("GET", "jobs"), ("POST", "jobs?evil=1"),
                             ("GET", "../v1/config"), ("GET", "%63onfig"), ("GET", "jobs/" + ident + "/download?format=txt&x=1"),
                             ("GET", "jobs/" + ident + "/download?format=%74xt"), ("GET", "jobs/" + ident + "?x=1"),
                             ("OPTIONS", "config"), ("PUT", "jobs/" + ident)]:
            with self.subTest(path=path):
                self.assertEqual(self.request(method, path, cookie)[0], 404)
        self.assertEqual(self.request("GET", "https://evil.invalid/" + gateway.PREFIX, cookie, absolute=True)[0], 404)
        self.assertEqual(self.request("GET", "/health", absolute=True, origin=None)[1], {"ok": True})

    def test_upstream_redirect_not_followed_and_secret_body_not_forwarded(self):
        self.core.redirect = True
        status, result, headers = self.request("GET", "config")
        self.assertEqual(status, 502)
        self.assertNotIn("SECRET", json.dumps(result))
        self.assertNotIn("Location", headers)
        self.assertEqual(len(self.core.requests), 1)

    def test_upstream_response_cap(self):
        self.core.oversize = True
        self.assertEqual(self.request("GET", "config")[0], 502)

    def test_exports_and_delete_dont_refund_history_or_forward_headers(self):
        cookie = self.session()
        ident = self.create(cookie)
        for format_name in ["txt", "md", "srt"]:
            status, value, headers = self.request("GET", "jobs/" + ident + "/download?format=" + format_name, cookie)
            self.assertEqual(status, 200)
            self.assertEqual(value, b"Synthetic export\n")
            self.assertIn("attachment", headers["Content-Disposition"])
            self.assertNotIn("Set-Cookie", headers)
            self.assertNotIn("Access-Control-Allow-Origin", headers)
        self.assertEqual(self.request("DELETE", "jobs/" + ident, cookie)[0], 200)
        self.assertEqual(self.request("GET", "jobs/" + ident, cookie)[0], 404)
        with self.service.quotas.db() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM attempts").fetchone()[0], 1)
        self.create(cookie)

    def test_one_active_job_blocks_second_submission(self):
        cookie = self.session()
        ident = self.create(cookie)
        status, value, headers = self.request("POST", "jobs", cookie)
        self.assertEqual(status, 429)
        self.assertEqual(value["error"]["code"], "visitor_busy")
        self.assertIn("Retry-After", headers)
        self.core.jobs[ident][1]["status"] = "failed"
        self.create(cookie)

    def test_simultaneous_same_visitor_posts_cannot_bypass_reservation(self):
        cookie = self.session()
        self.core.block_post = True
        results = []
        first = threading.Thread(target=lambda: results.append(self.request("POST", "jobs", cookie)[0]))
        first.start()
        self.assertTrue(self.core.entered.wait(2))
        self.assertEqual(self.request("POST", "jobs", cookie)[0], 429)
        self.core.release.set()
        first.join(3)
        self.assertEqual(results, [202])
        self.assertEqual(len([r for r in self.core.requests if r["method"] == "POST"]), 1)

    def test_hourly_limit_survives_restart_and_delete(self):
        cookie = self.session()
        for _ in range(3):
            ident = self.create(cookie)
            self.assertEqual(self.request("DELETE", "jobs/" + ident, cookie)[0], 200)
        self.server.service = gateway.GatewayService(self.config)
        status, result, headers = self.request("POST", "jobs", cookie)
        self.assertEqual(status, 429)
        self.assertEqual(result["error"]["code"], "visitor_limit")
        self.assertGreater(int(headers["Retry-After"]), 0)

    def test_global_daily40_and_parallel_visitors_at39(self):
        for number in range(39):
            reservation = self.service.quotas.reserve("synthetic-owner-" + str(number))
            self.service.quotas.accepted(reservation, str(uuid.uuid4()))
            self.service.quotas.clear_active("synthetic-owner-" + str(number), self.service.quotas.active("synthetic-owner-" + str(number))[1])
        cookies = [self.session(), self.session()]
        results = []
        threads = [threading.Thread(target=lambda cookie=cookie: results.append(self.request("POST", "jobs", cookie)[0])) for cookie in cookies]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(3)
        self.assertEqual(sorted(results), [202, 429])
        self.server.service = gateway.GatewayService(self.config)
        third = self.session()
        status, result, headers = self.request("POST", "jobs", third)
        self.assertEqual(status, 429)
        self.assertEqual(result["error"]["code"], "daily_limit")
        self.assertGreater(int(headers["Retry-After"]), 0)

    def test_definite_rejection_refunds_but_lost_acceptance_stays_reserved(self):
        cookie = self.session()
        self.core.post_status = 400
        self.assertEqual(self.request("POST", "jobs", cookie)[0], 400)
        with self.service.quotas.db() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM attempts").fetchone()[0], 0)
        self.core.post_status, self.core.drop_after_accept = 202, True
        self.assertEqual(self.request("POST", "jobs", cookie)[0], 503)
        self.assertEqual(len(self.core.jobs), 1)
        self.server.service = gateway.GatewayService(self.config)
        status, result, _ = self.request("POST", "jobs", cookie)
        self.assertEqual(status, 429)
        self.assertEqual(result["error"]["code"], "submission_uncertain")
        self.assertEqual(len(self.core.jobs), 1)

    def test_crashed_reserved_state_is_uncertain_after_restart(self):
        cookie = self.session()
        value = cookie.split("=", 1)[1]
        owner = self.service.sessions.verify(value)
        self.service.quotas.reserve(owner)
        self.server.service = gateway.GatewayService(self.config)
        status, result, headers = self.request("POST", "jobs", cookie)
        self.assertEqual(status, 429)
        self.assertEqual(result["error"]["code"], "submission_uncertain")
        self.assertGreater(int(headers["Retry-After"]), 86000)

    def test_duplicate_origin_content_length_and_cookie_headers_rejected(self):
        cookie = self.session()
        fixtures = [([("Origin", "https://chenyu.online"), ("Origin", "https://chenyu.online"),
                      ("Cookie", cookie), ("Content-Length", "2")], 403),
                    ([("Origin", "https://chenyu.online"), ("Cookie", cookie),
                      ("Content-Length", "2"), ("Content-Length", "2")], 400),
                    ([("Origin", "https://chenyu.online"), ("Cookie", cookie),
                      ("Cookie", cookie), ("Content-Length", "2")], 401)]
        for pairs, expected in fixtures:
            connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
            connection.putrequest("POST", gateway.PREFIX + "jobs")
            connection.putheader("Content-Type", "application/json")
            for name, value in pairs:
                connection.putheader(name, value)
            connection.endheaders(b"{}")
            response = connection.getresponse()
            self.assertEqual(response.status, expected)
            response.read()
            connection.close()
        self.assertEqual(len([r for r in self.core.requests if r["method"] == "POST"]), 0)

    def test_hard_upstream_deadline_retains_uncertain_admission(self):
        cookie = self.session()
        self.core.block_post = True
        timer_type = threading.Timer
        def quick_timer(interval, callback):
            return timer_type(0.08 if interval == 25 else interval, callback)
        with patch.object(gateway.threading, "Timer", side_effect=quick_timer):
            started = time.monotonic()
            self.assertEqual(self.request("POST", "jobs", cookie)[0], 503)
            self.assertLess(time.monotonic() - started, 1)
        self.core.release.set()
        status, result, _ = self.request("POST", "jobs", cookie)
        self.assertEqual(status, 429)
        self.assertEqual(result["error"]["code"], "submission_uncertain")

    def test_upstream_deadline_interrupts_http10_trickling_response_body(self):
        self.core.slow_body = True
        timer_type = threading.Timer
        def quick_timer(interval, callback):
            return timer_type(0.08 if interval == 25 else interval, callback)
        with patch.object(gateway.threading, "Timer", side_effect=quick_timer):
            started = time.monotonic()
            status, _, _ = self.request("GET", "config")
            self.assertEqual(status, 503)
            self.assertLess(time.monotonic() - started, 0.4)

    def test_download_deadline_never_returns_partial_success(self):
        cookie = self.session()
        ident = self.create(cookie)
        self.core.slow_body = True
        timer_type = threading.Timer
        def quick_timer(interval, callback):
            return timer_type(0.08 if interval == 25 else interval, callback)
        with patch.object(gateway.threading, "Timer", side_effect=quick_timer):
            status, result, _ = self.request("GET", "jobs/" + ident + "/download?format=txt", cookie)
            self.assertEqual(status, 503)
            self.assertEqual(result["error"]["code"], "upstream_unavailable")

    def test_premature_download_eof_never_returns_partial_success(self):
        cookie = self.session()
        ident = self.create(cookie)
        self.core.truncate_body = True
        status, result, _ = self.request("GET", "jobs/" + ident + "/download?format=txt", cookie)
        self.assertEqual(status, 502)
        self.assertEqual(result["error"]["code"], "upstream_unavailable")

    def test_incoming_header_and_body_hard_read_deadline(self):
        cookie = self.session()
        timer_type = threading.Timer
        def quick_timer(interval, callback):
            return timer_type(0.08 if interval == 20 else interval, callback)
        requests = [b"POST " + gateway.PREFIX.encode() + b"jobs HTTP/1.1\r\nHost: test\r\n",
                    ("POST " + gateway.PREFIX + "jobs HTTP/1.1\r\nHost: test\r\nOrigin: https://chenyu.online\r\nCookie: " + cookie +
                     "\r\nContent-Type: application/json\r\nContent-Length: 10\r\n\r\n{}").encode()]
        with patch.object(gateway.threading, "Timer", side_effect=quick_timer):
            for raw in requests:
                client = socket.create_connection(("127.0.0.1", self.server.server_port), timeout=1)
                started = time.monotonic()
                client.sendall(raw)
                self.assertEqual(client.recv(4096), b"")
                self.assertLess(time.monotonic() - started, 1)
                client.close()
        self.assertEqual(len([r for r in self.core.requests if r["method"] == "POST"]), 0)

    def test_expired_reservation_and_old_hourly_history(self):
        now = time.time()
        with patch.object(gateway.time, "time", return_value=now - gateway.SESSION_SECONDS - 1):
            self.service.quotas.reserve("old-visitor")
        self.assertIsNone(self.service.quotas.active("old-visitor"))
        for _ in range(3):
            with patch.object(gateway.time, "time", return_value=now - 3601):
                reservation = self.service.quotas.reserve("hourly-visitor")
                self.service.quotas.accepted(reservation, str(uuid.uuid4()))
            self.service.quotas.clear_active("hourly-visitor", self.service.quotas.active("hourly-visitor")[1])
        self.assertIsInstance(self.service.quotas.reserve("hourly-visitor"), str)


if __name__ == "__main__":
    unittest.main()
