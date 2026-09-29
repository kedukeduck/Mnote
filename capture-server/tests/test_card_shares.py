import hashlib
from html.parser import HTMLParser
import json
import re
import unittest
import test_markdown_export as fixtures


class SharePageParser(HTMLParser):
    """Inspect rendered semantics without coupling tests to HTML whitespace."""
    VOID_TAGS = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}

    def __init__(self, html):
        super().__init__(convert_charrefs=True)
        self.elements = []
        self.stack = []
        self.feed(html.decode() if isinstance(html, bytes) else html)
        self.close()

    def handle_starttag(self, tag, attrs):
        element = {"tag": tag, "attrs": dict(attrs), "ancestors": tuple(self.stack),
                   "text": "", "index": len(self.elements)}
        self.elements.append(element)
        if tag not in self.VOID_TAGS:
            self.stack.append(element["index"])

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in self.VOID_TAGS:
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        for index in range(len(self.stack) - 1, -1, -1):
            if self.elements[self.stack[index]]["tag"] == tag:
                del self.stack[index:]
                break

    def handle_data(self, data):
        for index in self.stack:
            self.elements[index]["text"] += data

    def select(self, tag=None, element_id=None, class_name=None, within=None):
        return [element for element in self.elements
                if (tag is None or element["tag"] == tag)
                and (element_id is None or element["attrs"].get("id") == element_id)
                and (class_name is None or class_name in element["attrs"].get("class", "").split())
                and (within is None or within["index"] in element["ancestors"])]


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
        structure = SharePageParser(page)
        thought, = structure.select(element_id="thought")
        excerpt, = structure.select(element_id="excerpt")
        images, = structure.select(element_id="images")
        self.assertLess(thought["index"], excerpt["index"])
        self.assertLess(excerpt["index"], images["index"])
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

    def test_editorial_modules_keep_distinct_semantics_and_original_image_links(self):
        fields = ["thought", "excerpt", "crop", "context", "original", "source"]
        self.assertEqual(200, self.card(fields=fields, crop_role="annotated")[0])
        page = SharePageParser(self.call("GET", "/c/" + "a" * 64)[1])
        thought, = page.select("section", element_id="thought", class_name="thought-module")
        excerpt, = page.select("section", element_id="excerpt", class_name="quote-module")
        thought_body, = page.select(class_name="thought-body", within=thought)
        quote_body, = page.select("blockquote", class_name="quote-body", within=excerpt)
        self.assertEqual(self.record["comment"], thought_body["text"])
        self.assertEqual("外部摘录", quote_body["text"])
        images, = page.select("div", element_id="images")
        self.assertLess(thought["index"], excerpt["index"])
        self.assertLess(excerpt["index"], images["index"])
        self.assertEqual(["圈选截图", "页面截图"], [heading["text"] for heading in page.select("h2", within=images)])
        image_links = page.select("a", class_name="image-link", within=images)
        self.assertEqual(2, len(image_links))
        for link, role in zip(image_links, ("annotated", "context")):
            image, = page.select("img", within=link)
            expected = "https://images.example.com/capture/s/" + "a" * 64 + "/1-" + role + ".png"
            self.assertEqual(expected, link["attrs"]["href"])
            self.assertEqual(expected, image["attrs"]["src"])
            self.assertTrue(image["attrs"].get("alt"))
            self.assertTrue(link["attrs"].get("aria-label"))
        source, = page.select("section", element_id="source")
        source_link, = page.select("a", class_name="source-link", within=source)
        self.assertEqual("https://example.com/article", source_link["attrs"]["href"])
        self.assertIn("example.com", source_link["text"])
        self.assertTrue({"noreferrer", "noopener"}.issubset(source_link["attrs"].get("rel", "").split()))

    def test_all_module_combinations_navigation_and_original_disclosure_match_selection(self):
        fields = ["thought", "excerpt", "crop", "context", "original", "source"]
        group_order = ["thought", "excerpt", "images", "original", "source"]
        for mask in range(1, 1 << len(fields)):
            selected = [field for bit, field in enumerate(fields) if mask & (1 << bit)]
            with self.subTest(fields=selected):
                token = format(mask, "064x")
                extra = {"crop_role": "original"} if "crop" in selected else {}
                status, share, _ = self.card(token=token, fields=selected, **extra)
                self.assertEqual(200, status)
                page = SharePageParser(self.call("GET", "/c/" + token)[1])
                expected = {"images" if field in ("crop", "context") else field for field in selected}
                visible_groups = {element["attrs"].get("id") for element in page.elements
                                  if element["attrs"].get("id") in group_order}
                self.assertEqual(expected, visible_groups)
                navigation = page.select("nav")
                self.assertEqual(int(len(expected) >= 3), len(navigation))
                if navigation:
                    nav, = navigation
                    self.assertEqual("本次分享内容", nav["attrs"].get("aria-label"))
                    self.assertEqual(["#" + group for group in group_order if group in expected],
                                     [link["attrs"].get("href") for link in page.select("a", within=nav)])
                for link in page.select("a"):
                    target = link["attrs"].get("href", "")
                    if target.startswith("#"):
                        self.assertEqual(1, len(page.select(element_id=target[1:])))
                original = page.select("section", element_id="original")
                if "original" in selected:
                    original_section, = original
                    details, = page.select("details", class_name="original-details", within=original_section)
                    self.assertEqual(not bool(set(selected) & {"thought", "excerpt", "crop", "context"}),
                                     "open" in details["attrs"])
                    summary, = page.select("summary", within=details)
                    self.assertTrue(summary["text"].strip())
                    self.assertEqual(300, details["text"].count("完整保留的文字"))
                else:
                    self.assertEqual([], page.select("details", class_name="original-details"))
                self.assertNotIn("灵感", "".join(node["text"] for node in page.select("body")))
                # Keep the production limit on active shares intact while checking every combination.
                self.assertEqual(200, self.call("DELETE", "/v1/exports/" + share["id"],
                                               token=self.a["access_token"])[0])

    def test_editorial_page_keeps_text_inert_and_has_no_inline_code(self):
        payload = '<script>alert(1)</script><img src=x onerror="alert(2)"><a href="https://evil.example">伪造链接</a>\n& 原样文字'
        source_url = "https://example.com/article?topic=%3Cscript%3E&next=%22quoted%22"
        self.record = self.vault.put("editorial-xss", {"comment": payload,
            "source": {"text": payload, "url": source_url},
            "evidence": {"context": {"text": {"full_text": payload}}}})
        self.assertEqual(200, self.card(fields=["thought", "excerpt", "original", "source"])[0])
        status, raw, headers = self.call("GET", "/c/" + "a" * 64)
        self.assertEqual(200, status)
        page = SharePageParser(raw)
        self.assertEqual([], page.select("script"))
        self.assertEqual([], page.select("style"))
        self.assertEqual([], page.select("img"))
        for element in page.elements:
            self.assertNotIn("style", element["attrs"])
            self.assertFalse(any(name.startswith("on") for name in element["attrs"]))
        for class_name in ("thought-body", "quote-body"):
            body, = page.select(class_name=class_name)
            self.assertEqual(payload, body["text"])
        original, = page.select(element_id="original")
        self.assertIn(payload, original["text"])
        external_links = [link for link in page.select("a") if not link["attrs"].get("href", "").startswith("#")]
        self.assertEqual([source_url], [link["attrs"]["href"] for link in external_links])
        self.assertIn(b"&amp;next=", raw)
        stylesheet, = [link for link in page.select("link") if link["attrs"].get("rel") == "stylesheet"]
        self.assertEqual("https://images.example.com/capture/assets/share-card.css", stylesheet["attrs"]["href"])
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertEqual("no-referrer", headers["Referrer-Policy"])
        self.assertTrue({"noindex", "nofollow", "noarchive"}.issubset(
            {value.strip() for value in headers["X-Robots-Tag"].split(",")}))
        csp = {parts[0]: set(parts[1:]) for directive in headers["Content-Security-Policy"].split(";")
               if (parts := directive.split())}
        for directive in ("script-src", "style-src", "font-src"):
            self.assertEqual({"'self'"}, csp[directive])
        for directive in ("default-src", "base-uri", "form-action", "frame-ancestors", "object-src"):
            self.assertEqual({"'none'"}, csp[directive])
        css_status, css, css_headers = self.call("GET", "/assets/share-card.css")
        self.assertEqual(200, css_status)
        self.assertTrue(css)
        self.assertEqual("text/css", css_headers.get_content_type())
        self.assertEqual("no-store", css_headers["Cache-Control"])
