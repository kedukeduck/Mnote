import hashlib
import json
import re
import unittest
import test_markdown_export as fixtures


class CardShareTest(unittest.TestCase):
    setUp = fixtures.MarkdownExportTest.setUp
    tearDown = fixtures.MarkdownExportTest.tearDown
    call = fixtures.MarkdownExportTest.call
    export = fixtures.MarkdownExportTest.export
    def card(self, token="a" * 64, fields=None, **extra):
        return self.call("POST", "/v1/exports/card", {"id": self.record["id"], "revision": 1,
            "token": token, "publish": True, "fields": ["original", "source"] if fields is None else fields, **extra}, self.a["access_token"])

    def test_qr_snapshot_exact_fields_immutable_and_revocable(self):
        status, result, _ = self.card(fields=["original"])
        self.assertEqual(200, status)
        self.assertEqual(hashlib.sha256(("a" * 64).encode()).hexdigest()[:32], result["id"])
        path = "/c/" + "a" * 64
        status, page, headers = self.call("GET", path)
        self.assertEqual(200, status)
        self.assertIn("完整保留的文字".encode(), page)
        for private in ("我的想法", "外部摘录", "https://example.com/article", "灵感", self.a["access_token"]):
            self.assertNotIn(private.encode(), page)
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertEqual("no-referrer", headers["Referrer-Policy"])
        self.assertIn("noindex", headers["X-Robots-Tag"])
        self.assertEqual(b"", self.call("HEAD", path)[1])
        self.vault.soft_delete(self.record["id"], 1)
        self.vault.purge(self.record["id"])
        self.assertEqual(200, self.call("GET", path)[0])
        self.assertEqual(200, self.card(fields=["original"])[0])  # Retry does not change a snapshot.
        self.assertEqual(400, self.card(fields=["source"])[0])
        self.assertEqual(404, self.call("DELETE", "/v1/exports/" + result["id"], token=self.b["access_token"])[0])
        self.assertEqual(200, self.call("DELETE", "/v1/exports/" + result["id"], token=self.a["access_token"])[0])
        self.assertEqual(404, self.call("GET", path)[0])
        self.assertEqual(400, self.card(fields=["original"])[0])

    def test_source_only_and_owner_management(self):
        _, result, _ = self.card(fields=["source"])
        page = self.call("GET", "/c/" + "a" * 64)[1]
        self.assertIn(b'https://example.com/article', page)
        self.assertNotIn("完整保留".encode(), page)
        listing = self.call("GET", "/v1/exports", token=self.a["access_token"])[1]["exports"]
        self.assertEqual(1, len(listing)); self.assertEqual(0, listing[0]["image_count"])
        self.assertIn("二维码内容", listing[0]["text_preview"])
        self.assertEqual(404, self.call("GET", "/s/" + "a" * 64 + "/card.json")[0])
        _, markdown, _ = self.export()
        # Markdown image tokens must never expose their private text through the new page route.
        import re
        token = re.search(r"/s/([a-f0-9]{64})/", markdown["markdown"]).group(1)
        self.assertEqual(404, self.call("GET", "/c/" + token)[0])

    def test_auth_consent_revision_and_input_validation(self):
        for token in (None, "write", "read", "ai", self.b["access_token"]):
            status = self.call("POST", "/v1/exports/card", {"id": self.record["id"], "revision": 1,
                "token": "a" * 64, "publish": True, "fields": ["original"]}, token)[0]
            self.assertIn(status, (401,403,404))
        self.assertEqual(400, self.card(publish=False)[0])
        self.assertEqual(409, self.card(revision=2)[0])
        for fields in ([], ["comment"], ["original", "original"], [{"bad": 1}], "source"):
            self.assertEqual(400, self.card(fields=fields)[0])
        self.assertEqual(400, self.card(token="../bad")[0])
        self.assertEqual(404, self.call("GET", "/c/../../etc/passwd")[0])

    def test_xss_is_inert_and_unsafe_links_rejected(self):
        self.record = self.vault.put("xss-test", {"comment": "private", "source": {"url": "javascript:alert(1)"},
            "evidence": {"context": {"text": {"full_text": '<script>alert(1)</script><img src=x onerror=alert(2)>'}}}})
        self.assertEqual(400, self.card(fields=["source"])[0])
        self.assertEqual(200, self.card(fields=["original"])[0])
        page = self.call("GET", "/c/" + "a" * 64)[1]
        self.assertNotIn(b"<script>", page); self.assertIn(b"&lt;script&gt;", page)

    def test_storage_failure_leaves_no_share_and_no_path_disclosure(self):
        from unittest.mock import patch
        with patch("heartnote_capture.markdown_export.Path.write_text", side_effect=OSError("private/disk/path")):
            status, body, _ = self.card()
            self.assertEqual(503, status)
            self.assertNotIn("private/disk/path", str(body))
        self.assertEqual(404, self.call("GET", "/c/" + "a" * 64)[0])
        self.assertEqual([], self.server.RequestHandlerClass.exports.list(self.a["account_id"]))
        self.assertEqual(200, self.card()[0])

    def test_selected_thought_excerpt_and_images_are_exact_immutable_snapshots(self):
        fields = ["thought", "excerpt", "crop", "context", "original", "source"]
        status, result, _ = self.card(fields=fields, crop_role="annotated")
        self.assertEqual(200, status)
        self.assertEqual(2, result["format"])
        page = self.call("GET", "/c/" + "a" * 64)[1].decode()
        for value in (self.record["comment"], "外部摘录", "完整保留的文字", "https://example.com/article"):
            self.assertIn(value, page)
        self.assertLess(page.index("我的想法"), page.index("<h2>摘录"))
        self.assertLess(page.index("<h2>摘录"), page.index("<h2>圈选截图"))
        self.assertNotIn("灵感", page)
        self.assertNotIn(self.a["access_token"], page)
        paths = re.findall(r'src="https://images.example.com/capture([^\"]+)"', page)
        self.assertEqual(["/s/" + "a" * 64 + "/1-annotated.png", "/s/" + "a" * 64 + "/1-context.png"], paths)
        before = [self.call("GET", path)[1] for path in paths]
        self.assertEqual(404, self.call("GET", "/s/" + "a" * 64 + "/1-original.png")[0])
        detail = self.call("GET", "/v1/exports/" + result["id"], token=self.a["access_token"])[1]
        self.assertEqual(2, detail["image_count"])
        self.assertEqual({"annotated", "context"}, {i["role"] for i in detail["images"]})
        for image in detail["images"]:
            path = "/v1/exports/" + result["id"] + "/assets/" + image["name"]
            self.assertEqual(200, self.call("GET", path, token=self.a["access_token"])[0])
            self.assertEqual(404, self.call("GET", path, token=self.b["access_token"])[0])
        self.vault.soft_delete(self.record["id"], 1)
        self.vault.purge(self.record["id"])
        self.assertEqual(page, self.call("GET", "/c/" + "a" * 64)[1].decode())
        self.assertEqual(before, [self.call("GET", path)[1] for path in paths])
        self.assertEqual(200, self.card(fields=fields, crop_role="annotated")[0])
        self.assertEqual(400, self.card(fields=fields, crop_role="original")[0])
        self.call("DELETE", "/v1/exports/" + result["id"], token=self.a["access_token"])
        for path in paths + ["/c/" + "a" * 64]:
            self.assertEqual(404, self.call("GET", path)[0])

    def test_thought_only_and_image_only_do_not_leak_unselected_fields(self):
        for i, (fields, extra) in enumerate([(["thought"], {}), (["excerpt"], {}),
                (["crop"], {"crop_role": "original"}), (["context"], {})]):
            token = str(i + 1) * 64
            status, result, _ = self.card(token=token, fields=fields, **extra)
            self.assertEqual(200, status)
            folder = self.server.RequestHandlerClass.exports.root / result["id"]
            data = json.loads((folder / "card.json").read_text())["data"]
            self.assertEqual(set(fields), set(data))
            page = self.call("GET", "/c/" + token)[1].decode()
            for value in ("完整保留的文字", "https://example.com/article", "灵感", self.record["created_at"]):
                self.assertNotIn(value, page)
            self.assertEqual("thought" in fields, self.record["comment"] in page)
            self.assertEqual("excerpt" in fields, "外部摘录" in page)

    def test_missing_images_invalid_role_and_image_write_failure_do_not_publish(self):
        from unittest.mock import patch
        for extra in ({}, {"crop_role": "context"}, {"crop_role": "../original"}):
            self.assertEqual(400, self.card(fields=["crop"], **extra)[0])
        self.assertEqual(400, self.card(fields=["thought"], crop_role="annotated")[0])
        with patch("heartnote_capture.markdown_export.MAX_IMAGES", 1):
            self.assertEqual(400, self.card(fields=["context"])[0])
        from pathlib import Path
        original_open = Path.open
        def fail_image_write(path, mode="r", *args, **kwargs):
            if path.name == "1-context.png" and mode == "xb":
                raise OSError("private/disk/path")
            return original_open(path, mode, *args, **kwargs)
        with patch.object(Path, "open", fail_image_write):
            status, body, _ = self.card(fields=["context"])
            self.assertEqual(503, status)
            self.assertNotIn("private/disk/path", str(body))
        self.assertEqual([], self.server.RequestHandlerClass.exports.list(self.a["account_id"]))
        self.record = self.vault.put("no-images", {"comment": "只有想法"})
        self.assertEqual(400, self.card(fields=["crop"], crop_role="original")[0])
        self.assertEqual(400, self.card(fields=["context"])[0])
        self.assertEqual(400, self.card(fields=["excerpt"])[0])
        self.assertEqual(404, self.call("GET", "/c/" + "a" * 64)[0])

    def test_shared_thought_and_excerpt_html_are_inert(self):
        payload = '<script>alert(1)</script><img src=x onerror="alert(2)">'
        self.record = self.vault.put("xss-text", {"comment": payload, "source": {"text": payload}})
        self.assertEqual(200, self.card(fields=["thought", "excerpt"])[0])
        page = self.call("GET", "/c/" + "a" * 64)[1]
        self.assertNotIn(b"<script>", page)
        self.assertNotIn(b"<img src=x", page)
        self.assertEqual(2, page.count(b"&lt;script&gt;"))
