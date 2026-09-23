import hashlib
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
