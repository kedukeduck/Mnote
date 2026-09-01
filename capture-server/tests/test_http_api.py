from __future__ import annotations

import base64
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

from heartnote_capture.http_api import Tokens, create_server
from heartnote_capture.store import CaptureStore, minimal_png


class CaptureHTTPTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.store = CaptureStore(self.temporary.name)
        self.tokens = Tokens("write-token", "read-token", "ai-token")
        self.server = create_server("127.0.0.1", 0, self.store, self.tokens)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.temporary.cleanup()

    def request(
        self,
        method: str,
        path: str,
        token: str | None = None,
        body: dict | None = None,
        headers: dict[str, str] | None = None,
    ):
        data = None if body is None else json.dumps(body).encode("utf-8")
        request_headers = dict(headers or {})
        if token:
            request_headers["Authorization"] = f"Bearer {token}"
        if body is not None:
            request_headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            self.base + path, data=data, headers=request_headers, method=method
        )
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.headers, error.read()

    def body(self, capture_id: str, ai_access: str = "remote_no_memory") -> dict:
        png = base64.b64encode(minimal_png()).decode("ascii")
        return {
            "id": capture_id,
            "kind": "thought",
            "comment": "跨端同步测试",
            "source": {"app_name": "Test App", "text": "原文"},
            "ai_access": ai_access,
            "assets": {"original": {"content_type": "image/png", "data_base64": png}},
        }

    def test_health_auth_crud_search_and_ai_filter(self) -> None:
        status, _, _ = self.request("GET", "/health")
        self.assertEqual(200, status)
        status, _, _ = self.request("GET", "/v1/captures")
        self.assertEqual(401, status)
        status, headers, raw = self.request(
            "PUT", "/v1/captures/capture-http-001", "write-token", self.body("capture-http-001")
        )
        self.assertEqual(200, status)
        created = json.loads(raw)
        self.assertEqual(1, created["revision"])
        self.assertEqual('"revision:1"', headers["ETag"])
        status, _, _ = self.request("GET", "/v1/captures", "write-token")
        self.assertEqual(403, status)
        status, _, raw = self.request("GET", "/v1/search?q=%E8%B7%A8%E7%AB%AF", "ai-token")
        self.assertEqual(200, status)
        self.assertEqual("capture-http-001", json.loads(raw)["records"][0]["id"])
        self.request(
            "PUT", "/v1/captures/capture-http-002", "write-token", self.body("capture-http-002", "deny")
        )
        status, _, raw = self.request("GET", "/v1/captures", "ai-token")
        self.assertEqual(["capture-http-001"], [item["id"] for item in json.loads(raw)["records"]])

    def test_revision_conflict_and_delete(self) -> None:
        self.request(
            "PUT", "/v1/captures/capture-http-001", "write-token", self.body("capture-http-001")
        )
        changed = self.body("capture-http-001")
        changed.pop("assets")
        changed["comment"] = "changed"
        status, _, raw = self.request(
            "PUT", "/v1/captures/capture-http-001", "write-token", changed
        )
        self.assertEqual(409, status)
        request = urllib.request.Request(
            self.base + "/v1/captures/capture-http-001",
            method="DELETE",
            headers={"Authorization": "Bearer write-token", "If-Match": '"revision:1"'},
        )
        with urllib.request.urlopen(request, timeout=5) as response:
            deleted = json.loads(response.read())
        self.assertTrue(deleted["deleted"])

    def test_web_inbox_assets_and_security_headers(self) -> None:
        status, headers, body = self.request("GET", "/")
        self.assertEqual(200, status)
        self.assertIn(b"Mnote Inbox", body)
        self.assertIn(b'id="read-token-input"', body)
        self.assertIn(b'id="write-token-input"', body)
        self.assertIn(b'href="assets/app.css"', body)
        self.assertIn(b'src="assets/app.js"', body)
        self.assertIn(b'href="./"', body)
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertEqual("nosniff", headers["X-Content-Type-Options"])
        self.assertEqual("DENY", headers["X-Frame-Options"])
        self.assertEqual("same-origin", headers["Cross-Origin-Resource-Policy"])
        self.assertIn("default-src 'none'", headers["Content-Security-Policy"])
        self.assertNotIn("Access-Control-Allow-Origin", headers)

        status, _, script = self.request("GET", "/assets/app.js")
        self.assertEqual(200, status)
        self.assertIn(b"sessionStorage", script)
        self.assertNotIn(b"localStorage", script)
        self.assertIn(b"session-read-token", script)
        self.assertIn(b"session-write-token", script)
        self.assertIn(b"APP_BASE_PATH", script)
        self.assertIn(b"appPath(path)", script)
        self.assertIn(b"/v1/export?include_deleted=true", script)
        self.assertIn(b'If-Match', script)

        status, headers, body = self.request("HEAD", "/assets/app.css")
        self.assertEqual(200, status)
        self.assertEqual(b"", body)
        self.assertEqual("nosniff", headers["X-Content-Type-Options"])

    def test_auth_scope_ai_changes_and_ai_writes_are_non_mutating(self) -> None:
        for capture_id, policy in (
            ("capture-http-allowed", "remote_no_memory"),
            ("capture-http-local", "local_only"),
            ("capture-http-denied", "deny"),
        ):
            status, _, _ = self.request(
                "PUT", f"/v1/captures/{capture_id}", "write-token", self.body(capture_id, policy)
            )
            self.assertEqual(200, status)

        status, headers, _ = self.request("GET", "/v1/captures", "not-a-token")
        self.assertEqual(401, status)
        self.assertIn("Bearer", headers["WWW-Authenticate"])
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertEqual("nosniff", headers["X-Content-Type-Options"])
        self.assertIn("frame-ancestors 'none'", headers["Content-Security-Policy"])

        status, _, _ = self.request("GET", "/v1/captures", "write-token")
        self.assertEqual(403, status)

        status, _, _ = self.request(
            "PUT",
            "/v1/captures/capture-http-read-write",
            "read-token",
            self.body("capture-http-read-write"),
        )
        self.assertEqual(403, status)
        with self.assertRaises(KeyError):
            self.store.get("capture-http-read-write")

        status, _, raw = self.request("GET", "/v1/changes?after=0", "ai-token")
        self.assertEqual(403, status)
        self.assertNotIn(b"capture-http-local", raw)
        self.assertNotIn(b"capture-http-denied", raw)
        self.assertNotIn(b"capture-http-allowed", raw)

        status, _, raw = self.request("GET", "/v1/changes?after=0", "read-token")
        self.assertEqual(200, status)
        self.assertEqual(3, len(json.loads(raw)["changes"]))

        before = self.store.get("capture-http-allowed", include_deleted=True)
        status, _, _ = self.request(
            "PUT",
            "/v1/captures/capture-http-ai-new",
            "ai-token",
            self.body("capture-http-ai-new"),
        )
        self.assertEqual(403, status)
        status, _, _ = self.request(
            "POST",
            "/v1/captures",
            "ai-token",
            self.body("capture-http-ai-post"),
        )
        self.assertEqual(403, status)
        status, _, _ = self.request(
            "DELETE",
            "/v1/captures/capture-http-allowed",
            "ai-token",
            headers={"If-Match": '"revision:1"'},
        )
        self.assertEqual(403, status)
        after = self.store.get("capture-http-allowed", include_deleted=True)
        self.assertEqual(before["revision"], after["revision"])
        self.assertFalse(after["deleted"])
        self.assertEqual(
            {"capture-http-allowed", "capture-http-local", "capture-http-denied"},
            {record["id"] for record in self.store.list(include_deleted=True)},
        )

    def test_restore_endpoint_supports_web_inbox_flow(self) -> None:
        self.request(
            "PUT", "/v1/captures/capture-http-restore", "write-token", self.body("capture-http-restore")
        )
        status, _, raw = self.request(
            "DELETE",
            "/v1/captures/capture-http-restore",
            "write-token",
            headers={"If-Match": '"revision:1"'},
        )
        self.assertEqual(200, status)
        deleted = json.loads(raw)
        status, _, raw = self.request(
            "POST",
            "/v1/captures/capture-http-restore/restore",
            "write-token",
            {"base_revision": deleted["revision"]},
        )
        self.assertEqual(200, status)
        self.assertFalse(json.loads(raw)["deleted"])

    def test_every_protected_route_uses_consistent_scope_statuses(self) -> None:
        self.request(
            "PUT", "/v1/captures/capture-http-matrix", "write-token", self.body("capture-http-matrix")
        )
        read_paths = (
            "/v1/captures",
            "/v1/search?q=matrix",
            "/v1/changes?after=0",
            "/v1/export",
            "/v1/captures/capture-http-matrix",
            "/v1/captures/capture-http-matrix/assets/original",
        )
        for path in read_paths:
            with self.subTest(path=path, case="missing"):
                self.assertEqual(401, self.request("GET", path)[0])
            with self.subTest(path=path, case="unknown"):
                self.assertEqual(401, self.request("GET", path, "unknown-token")[0])
            with self.subTest(path=path, case="known-wrong-scope"):
                self.assertEqual(403, self.request("GET", path, "write-token")[0])

        mutation_requests = (
            ("PUT", "/v1/captures/capture-http-matrix-new", self.body("capture-http-matrix-new"), None),
            ("POST", "/v1/captures", self.body("capture-http-matrix-post"), None),
            (
                "POST",
                "/v1/captures/capture-http-matrix/restore",
                {"base_revision": 1},
                None,
            ),
            ("POST", "/v1/captures/capture-http-matrix/purge", {}, None),
            (
                "DELETE",
                "/v1/captures/capture-http-matrix",
                None,
                {"If-Match": '"revision:1"'},
            ),
        )
        for method, path, body, headers in mutation_requests:
            with self.subTest(method=method, path=path, case="missing"):
                self.assertEqual(401, self.request(method, path, body=body, headers=headers)[0])
            with self.subTest(method=method, path=path, case="unknown"):
                self.assertEqual(
                    401,
                    self.request(method, path, "unknown-token", body, headers)[0],
                )
            with self.subTest(method=method, path=path, case="known-wrong-scope"):
                self.assertEqual(403, self.request(method, path, "read-token", body, headers)[0])

        record = self.store.get("capture-http-matrix", include_deleted=True)
        self.assertEqual(1, record["revision"])
        self.assertFalse(record["deleted"])
        self.assertEqual(
            ["capture-http-matrix"],
            [item["id"] for item in self.store.list(include_deleted=True)],
        )


if __name__ == "__main__":
    unittest.main()
