from __future__ import annotations

import base64
import json
import tempfile
import unittest
from pathlib import Path

from heartnote_capture.store import (
    CaptureConflict,
    CaptureNotFound,
    CaptureStore,
    CaptureValidationError,
    minimal_png,
)


class CaptureStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.store = CaptureStore(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def record(self, capture_id: str = "capture-test-001") -> dict:
        encoded = base64.b64encode(minimal_png()).decode("ascii")
        return {
            "schema_version": 1,
            "id": capture_id,
            "created_at": "2026-08-31T01:00:00.000Z",
            "kind": "thought",
            "comment": "这是我的想法",
            "source": {
                "type": "screen",
                "app_name": "Browser",
                "text": "来源应用直接提供的文字",
                "url": "https://example.test/article",
            },
            "ocr": [{"text": "机器识别文字", "confidence": 0.91}],
            "annotations": [{"type": "highlighter", "points": [[1, 2], [3, 4]]}],
            "ai_access": "remote_no_memory",
            "assets": {
                "original": {"content_type": "image/png", "data_base64": encoded},
                "annotated": {"content_type": "image/png", "data_base64": encoded},
            },
        }

    def test_put_get_search_and_asset(self) -> None:
        created = self.store.put("capture-test-001", self.record())
        self.assertEqual(1, created["revision"])
        self.assertEqual("这是我的想法", created["comment"])
        self.assertEqual({"annotated", "original"}, set(created["assets"]))
        self.assertEqual("capture-test-001", self.store.search("机器识别")[0]["id"])
        path, content_type, digest = self.store.asset("capture-test-001", "original")
        self.assertEqual("image/png", content_type)
        self.assertEqual(minimal_png(), path.read_bytes())
        self.assertEqual(64, len(digest))

    def test_revision_conflict_and_idempotent_retry(self) -> None:
        first = self.store.put("capture-test-001", self.record())
        same_with_assets = self.store.put("capture-test-001", self.record())
        self.assertEqual(first["revision"], same_with_assets["revision"])
        retry = dict(self.record())
        retry.pop("assets")
        same = self.store.put("capture-test-001", retry)
        self.assertEqual(first["revision"], same["revision"])
        changed = dict(retry)
        changed["comment"] = "新的想法"
        with self.assertRaises(CaptureConflict):
            self.store.put("capture-test-001", changed)
        updated = self.store.put("capture-test-001", changed, base_revision=1)
        self.assertEqual(2, updated["revision"])

    def test_ai_scope_delete_restore_purge_and_changes(self) -> None:
        allowed = self.store.put("capture-test-001", self.record())
        denied_body = self.record("capture-test-002")
        denied_body["ai_access"] = "deny"
        self.store.put("capture-test-002", denied_body)
        self.assertEqual(["capture-test-001"], [item["id"] for item in self.store.list(ai_only=True)])
        deleted = self.store.soft_delete("capture-test-001", allowed["revision"])
        self.assertTrue(deleted["deleted"])
        with self.assertRaises(CaptureNotFound):
            self.store.get("capture-test-001")
        restored = self.store.restore("capture-test-001", deleted["revision"])
        self.assertFalse(restored["deleted"])
        deleted_again = self.store.soft_delete("capture-test-001", restored["revision"])
        self.store.purge("capture-test-001")
        with self.assertRaises(CaptureNotFound):
            self.store.get("capture-test-001", include_deleted=True)
        operations = [item["operation"] for item in self.store.changes()["changes"]]
        self.assertEqual(["upsert", "upsert", "delete", "restore", "delete", "purge"], operations)
        with self.assertRaises(CaptureValidationError):
            self.store.changes(ai_only=True)

    def test_missing_ai_consent_defaults_to_local_only(self) -> None:
        record = self.record()
        record.pop("ai_access")
        created = self.store.put("capture-test-001", record)
        self.assertEqual("local_only", created["ai_access"])
        self.assertEqual([], self.store.list(ai_only=True))

    def test_export_and_validation(self) -> None:
        created = self.store.put("capture-test-001", self.record())
        destination = Path(self.temporary.name) / "export.zip"
        self.store.export_zip(destination)
        self.assertTrue(destination.is_file())
        self.store.soft_delete("capture-test-001", created["revision"])
        deleted_destination = Path(self.temporary.name) / "export-with-trash.zip"
        self.store.export_zip(deleted_destination, include_deleted=True)
        self.assertTrue(deleted_destination.is_file())
        second = self.store.put("capture-test-002", self.record("capture-test-002"))
        with self.assertRaises(CaptureValidationError):
            self.store.restore("capture-test-002", second["revision"])
        invalid = self.record("capture-test-003")
        invalid["assets"]["original"]["data_base64"] = "not base64"
        with self.assertRaises(CaptureValidationError):
            self.store.put("capture-test-003", invalid)


if __name__ == "__main__":
    unittest.main()
