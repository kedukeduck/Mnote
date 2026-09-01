from __future__ import annotations

import base64
import tempfile
import unittest

from heartnote_capture.store import CaptureStore, minimal_png


class ClientContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.store = CaptureStore(self.temporary.name)
        self.png = base64.b64encode(minimal_png()).decode("ascii")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_android_legacy_shape_is_losslessly_canonicalized(self) -> None:
        record = self.store.put(
            "20260831T120000000-a1b2c3d4",
            {
                "schemaVersion": 1,
                "id": "20260831T120000000-a1b2c3d4",
                "createdAt": 1_788_171_200_000,
                "kind": "todo",
                "comment": "我要验证这个想法",
                "sourceType": "process_text",
                "sourceText": "Android 来源直接交付的文字",
                "sourcePackage": "com.example.reader",
                "annotationLayer": {"coordinateSpace": "source_bitmap_pixels"},
                "aiAccess": "remote_no_memory",
                "original_png_base64": self.png,
                "annotated_png_base64": self.png,
            },
        )
        self.assertEqual("todo", record["kind"])
        self.assertEqual("Android 来源直接交付的文字", record["source"]["text"])
        self.assertEqual("com.example.reader", record["source"]["app_id"])
        self.assertEqual({"annotated", "original"}, set(record["assets"]))
        self.assertEqual(
            record["id"], self.store.search("来源直接交付")[0]["id"]
        )

    def test_windows_and_browser_records_share_one_search_index(self) -> None:
        windows = {
            "schema_version": 1,
            "id": "windows-contract-001",
            "created_at": "2026-08-31T04:00:00.000Z",
            "kind": "thought",
            "comment": "多屏截图给我的启发",
            "source": {
                "type": "screen",
                "app_name": "reader.exe",
                "title": "Windows Reader",
                "text": "",
                "selection_screen": {"x": -1200, "y": 80, "width": 900, "height": 500},
            },
            "annotations": [{"type": "highlighter", "points": [[0.1, 0.2], [0.7, 0.2]]}],
            "ai_access": "remote_no_memory",
            "assets": {
                "original": {"content_type": "image/png", "data_base64": self.png},
                "annotated": {"content_type": "image/png", "data_base64": self.png},
            },
        }
        browser = {
            "schema_version": 1,
            "id": "browser-contract-001",
            "created_at": "2026-08-31T04:01:00.000Z",
            "kind": "comment",
            "comment": "网页锚点测试",
            "source": {
                "type": "web_selection",
                "app_name": "Chrome/Edge extension",
                "title": "Example",
                "url": "https://example.test/article",
                "text": "浏览器精确原文",
                "fidelity_level": "L4",
                "selectors": {
                    "text_quote": {"exact": "浏览器精确原文", "prefix": "前", "suffix": "后"},
                    "validation_status": "validated_at_capture",
                },
            },
            "ocr": [],
            "annotations": [],
            "ai_access": "deny",
        }
        self.store.put(windows["id"], windows)
        self.store.put(browser["id"], browser)

        self.assertEqual("windows-contract-001", self.store.search("多屏截图")[0]["id"])
        self.assertEqual("browser-contract-001", self.store.search("浏览器精确")[0]["id"])
        self.assertEqual([], self.store.search("浏览器精确", ai_only=True))
        self.assertEqual(
            ["windows-contract-001"],
            [record["id"] for record in self.store.list(ai_only=True)],
        )


if __name__ == "__main__":
    unittest.main()
