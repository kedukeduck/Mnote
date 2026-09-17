import base64
import json
import re
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError

from heartnote_capture.http_api import create_server, Tokens
from heartnote_capture.store import CaptureStore, minimal_png
from heartnote_capture.markdown_export import literal


class MarkdownExportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = CaptureStore(self.temp.name)
        self.server = create_server("127.0.0.1", 0, self.store, Tokens("write", "read", "ai"), "https://images.example.com/capture")
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
        accounts = self.server.RequestHandlerClass.accounts
        self.a = accounts.activate("export-a", "test-password-123", accounts.invite())
        self.b = accounts.activate("export-b", "test-password-123", accounts.invite())
        self.vault = accounts.store(accounts.resolve(self.a["access_token"]))
        self.record = self.vault.put("record-test-001", {
            "kind": "comment", "comment": "我的想法\n不能冒充原文", "tags": ["灵感"], "ai_access": "local_only",
            "source": {"text": "外部摘录", "url": "https://example.com/article", "app_name": "Chrome"},
            "evidence": {"context": {"text": {"origin": "accessibility", "full_text": "完整保留的文字\n" * 300}, "image": {"selection": {"left": 1, "top": 2, "right": 3, "bottom": 4}}}},
            "assets": {role: {"content_type": "image/png", "data_base64": base64.b64encode(minimal_png()).decode()} for role in ("original", "annotated", "context")}})

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(); self.temp.cleanup()

    def call(self, method, path, body=None, token=None):
        headers = {"Content-Type": "application/json"}
        if token: headers["Authorization"] = "Bearer " + token
        request = Request(f"http://127.0.0.1:{self.server.server_port}" + path,
            data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try: response = urlopen(request, timeout=10)
        except HTTPError as error: response = error
        with response:
            data = response.read()
            return response.status, json.loads(data) if data and response.headers.get_content_type() == "application/json" else data, response.headers

    def export(self, records=None, **extra):
        return self.call("POST", "/v1/exports/markdown", {"records": records if records is not None else [{"id": self.record["id"], "revision": 1}], "publish_images": True, **extra}, self.a["access_token"])

    def test_snapshot_contains_text_images_and_no_session_credentials(self):
        status, result, _ = self.export()
        self.assertEqual(200, status)
        text = result["markdown"]
        for part in ("文档说明", "记录索引", "我的想法", "外部摘录", "完整保留的文字", "Chrome", "圈选位置"):
            self.assertIn(part, text)
        self.assertEqual(300, text.count("完整保留的文字"))
        self.assertNotIn(self.a["access_token"], text); self.assertNotIn("/v1/captures/", text)
        paths = list(dict.fromkeys(re.findall(r"https://images.example.com/capture(/s/[^)]+)", text)))
        self.assertEqual(3, len(re.findall(r"!\[记录 1 · [^\]]+\]\(https://", text)))
        self.assertIn("[打开完整页面截图原始图片]", text)
        self.assertEqual(3, len(paths))
        for path in paths:
            code, image, headers = self.call("GET", path)
            self.assertEqual(200, code); self.assertEqual(minimal_png(), image)
            self.assertEqual("image/png", headers.get_content_type())
            self.assertEqual("cross-origin", headers["Cross-Origin-Resource-Policy"])
            self.assertEqual("no-store", headers["Cache-Control"])
            self.assertEqual(b"", self.call("HEAD", path)[1])
        self.vault.soft_delete(self.record["id"], 1); self.vault.purge(self.record["id"])
        self.assertEqual(200, self.call("GET", paths[0])[0])
        self.assertEqual(200, self.call("DELETE", "/v1/exports/"+result["id"], token=self.a["access_token"])[0])
        self.assertEqual(404, self.call("GET", paths[0])[0])

    def test_encoded_response_and_storage_errors_leave_no_public_snapshot(self):
        with patch("heartnote_capture.markdown_export.render", return_value="\\" * (5 * 1024 * 1024)):
            self.assertEqual(400, self.export()[0])
        with patch("heartnote_capture.markdown_export.MAX_IMAGES", 1):
            self.assertEqual(400, self.export()[0])
        with patch("heartnote_capture.markdown_export.tempfile.mkdtemp", side_effect=OSError("private filesystem detail")):
            status, body, _ = self.export()
            self.assertEqual(503, status)
            self.assertNotIn("private filesystem detail", json.dumps(body))
        self.assertEqual([], self.server.RequestHandlerClass.exports.list(self.a["account_id"]))

    def test_owner_can_preview_old_snapshot_without_original_record_or_public_token(self):
        _, created, _ = self.export()
        path = "/v1/exports/" + created["id"]
        # Existing export schema has no new fields: the owner preview must work with old exports.
        self.vault.soft_delete(self.record["id"], 1); self.vault.purge(self.record["id"])
        status, detail, _ = self.call("GET", path, token=self.a["access_token"])
        self.assertEqual(200, status); self.assertEqual(3, len(detail["images"]))
        self.assertNotIn("token", json.dumps(detail)); self.assertNotIn("owner", detail)
        for image in detail["images"]:
            image_path = path + "/assets/" + image["name"]
            self.assertEqual(1, image["record_index"])
            status, data, headers = self.call("GET", image_path, token=self.a["access_token"])
            self.assertEqual(200, status); self.assertEqual(minimal_png(), data)
            self.assertEqual("no-store", headers["Cache-Control"])
            self.assertEqual(b"", self.call("HEAD", image_path, token=self.a["access_token"])[1])
            for inaccessible in (None, "write", "read", "ai", self.b["access_token"]):
                self.assertIn(self.call("GET", image_path, token=inaccessible)[0], (401, 403, 404))
                self.assertIn(self.call("GET", path, token=inaccessible)[0], (401, 403, 404))
        for name in ("../exports.sqlite3", "%2e%2e%2fexports.sqlite3", "1-missing.png"):
            self.assertEqual(404, self.call("GET", path + "/assets/" + name, token=self.a["access_token"])[0])
        self.call("DELETE", path, token=self.a["access_token"])
        self.assertEqual(404, self.call("GET", path, token=self.a["access_token"])[0])
        self.assertEqual(404, self.call("GET", image_path, token=self.a["access_token"])[0])

    def test_account_isolation_and_public_route_has_no_metadata(self):
        _, result, _ = self.export()
        self.assertEqual([], self.call("GET", "/v1/exports", token=self.b["access_token"])[1]["exports"])
        self.assertEqual(404, self.call("DELETE", "/v1/exports/"+result["id"], token=self.b["access_token"])[0])
        body={"records": [{"id": self.record["id"], "revision": 1}], "publish_images": True}
        self.assertEqual(404, self.call("POST", "/v1/exports/markdown", body, self.b["access_token"])[0])
        for token in (None, "ai", "read", "write"):
            self.assertIn(self.call("POST", "/v1/exports/markdown", body, token)[0], (401,403))
            self.assertIn(self.call("GET", "/v1/exports", token=token)[0], (401,403))
        self.assertEqual(401, self.call("GET", "/v1/captures/"+self.record["id"]+"/assets/original")[0])
        self.assertEqual(404, self.call("GET", "/s/"+"a"*64+"/../exports.sqlite3")[0])

    def test_confirmation_revision_deny_and_limits_are_atomic(self):
        self.assertEqual(400, self.export(publish_images=False)[0])
        self.assertEqual(409, self.export([{"id": self.record["id"], "revision": 0}])[0])
        self.assertEqual(400, self.export([])[0])
        self.assertEqual(400, self.export([{"id": self.record["id"], "revision": 1}]*101)[0])
        self.assertEqual(400, self.export([{"id": self.record["id"], "revision": 1}]*2)[0])
        self.vault.put("denied-record", {"ai_access": "deny", "comment": "never publish"})
        self.assertEqual(400, self.export([{"id": self.record["id"], "revision": 1}, {"id":"denied-record","revision":1}])[0])
        with patch("heartnote_capture.markdown_export.MAX_MARKDOWN", 100):
            self.assertEqual(400, self.export()[0])
        self.assertEqual([], self.server.RequestHandlerClass.exports.list(self.a["account_id"]))
        self.assertFalse(list(self.server.RequestHandlerClass.exports.root.glob("staging-*")))

    def test_quote_markup_cannot_inject_additional_images(self):
        raw="![steal](https://evil.example/image)\n<script>bad()</script>\n# forged section"
        safe=literal(raw)
        self.assertNotIn("![steal]", safe); self.assertNotIn("<script>", safe)
        self.assertIn("\\# forged section", safe)

    def test_only_selected_records_and_stable_order(self):
        self.vault.put("record-test-002", {"comment":"not selected"})
        _, result, _ = self.export()
        self.assertEqual(1, result["count"]); self.assertNotIn("not selected",result["markdown"])
        listing=self.call("GET","/v1/exports",token=self.a["access_token"])[1]["exports"]
        self.assertEqual(1,len(listing)); self.assertNotIn("token_hash",listing[0]); self.assertNotIn("assets",listing[0])
