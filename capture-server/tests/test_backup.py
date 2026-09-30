import base64
import hashlib
import importlib.machinery
import importlib.util
import json
import os
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from heartnote_capture.accounts import Accounts
from heartnote_capture.markdown_export import MarkdownExports
from heartnote_capture.store import CaptureNotFound, CaptureStore, minimal_png
from test_chat import conversation


SCRIPT = Path(__file__).resolve().parents[1] / "deploy" / "heartnote-capture-backup"
loader = importlib.machinery.SourceFileLoader("mnote_backup_test_module", str(SCRIPT))
spec = importlib.util.spec_from_loader(loader.name, loader)
backup_tool = importlib.util.module_from_spec(spec)
loader.exec_module(backup_tool)


class CompleteBackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.data = self.root / "data"
        self.output = self.root / "backups"
        self.store = CaptureStore(self.data)
        self.record_id = "synthetic-legacy-record"
        self.store.put(self.record_id, {"comment": "synthetic legacy thought", "ai_access": "deny", "assets": {
            "original": {"content_type": "image/png", "data_base64": base64.b64encode(minimal_png()).decode()}}})

    def tearDown(self):
        self.temp.cleanup()

    def unpack(self, archive):
        destination = self.root / ("restored-" + archive.stem)
        with tarfile.open(archive) as packed:
            self.assertFalse(any(item.issym() or item.islnk() for item in packed.getmembers()))
            packed.extractall(destination, filter="data")
        return destination

    def test_standalone_backup_restores_accounts_all_vaults_chats_assets_and_shares(self):
        accounts = Accounts(self.store)
        accounts.ITERATIONS = 1000
        owner = accounts.activate("synthetic-owner", "test-only-password-123", accounts.invite(legacy_owner=True))
        other = accounts.activate("synthetic-other", "test-only-password-123", accounts.invite())
        vault = accounts.store(accounts.resolve(other["access_token"]))
        record = "synthetic-other-record"
        vault.put(record, {"comment": "separate vault", "assets": {"context": {
            "content_type": "image/png", "data_base64": base64.b64encode(minimal_png()).decode()}}})
        first_chat, second_chat = conversation(self.record_id), conversation(record)
        self.store.chats.put(first_chat["id"], first_chat, 0)
        vault.chats.put(second_chat["id"], second_chat, 0)
        deleted_id = "synthetic-deleted-record"
        vault.put(deleted_id, {"comment": "deleted chat cannot revive"})
        deleted_chat = conversation(deleted_id)
        vault.chats.put(deleted_chat["id"], deleted_chat, 0)
        vault.soft_delete(deleted_id, 1)
        vault.restore(deleted_id, 2)
        shares = MarkdownExports(self.data, "https://example.invalid/capture")
        card = shares.create_card(owner["account_id"], self.store, {"id": self.record_id, "revision": 1,
            "token": "a" * 64, "publish": True, "fields": ["thought", "crop"], "crop_role": "original"})
        revoked = shares.create_card(owner["account_id"], self.store, {"id": self.record_id, "revision": 1,
            "token": "b" * 64, "publish": True, "fields": ["thought"]})
        shares.revoke(owner["account_id"], revoked["id"])
        installers = self.data / "updates"
        installers.mkdir()
        (installers / "synthetic-installer.apk").write_bytes(b"not user data")
        source_changes = self.store.changes()
        env = dict(os.environ, HEARTNOTE_CAPTURE_DATA=str(self.data), HEARTNOTE_CAPTURE_BACKUP_DIR=str(self.output))
        result = subprocess.run([sys.executable, str(SCRIPT)], env=env, capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)
        archives = list(self.output.glob("*.tar.gz"))
        self.assertEqual(1, len(archives))
        archive = archives[0]
        self.assertEqual(0o600, archive.stat().st_mode & 0o777)
        checksum = (self.output / (archive.name + ".sha256")).read_text().split()
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), checksum[0])
        self.assertEqual(archive.name, checksum[1])
        restored = self.unpack(archive)
        manifest = json.loads((restored / "BACKUP.json").read_text())
        self.assertEqual(2, manifest["format"])
        self.assertEqual(6, len(manifest["databases"]))
        self.assertFalse(list((restored / "data").rglob("*-wal")))
        self.assertFalse(list((restored / "data").rglob("*-shm")))
        self.assertFalse((restored / "data" / "updates").exists())
        for path in (restored / "data").rglob("*.sqlite3"):
            with sqlite3.connect(path) as db:
                self.assertEqual("ok", db.execute("PRAGMA integrity_check").fetchone()[0])
        restored_store = CaptureStore(restored / "data")
        restored_accounts = Accounts(restored_store)
        self.assertEqual(owner["account_id"], restored_accounts.resolve(owner["access_token"])["id"])
        other_vault = restored_accounts.store(restored_accounts.resolve(other["access_token"]))
        self.assertEqual("separate vault", other_vault.get(record)["comment"])
        self.assertEqual(second_chat["snapshot"], other_vault.chats.get(second_chat["id"])["snapshot"])
        self.assertEqual(first_chat["messages"], restored_store.chats.get(first_chat["id"])["messages"])
        self.assertEqual(minimal_png(), other_vault.asset(record, "context")[0].read_bytes())
        with self.assertRaises(CaptureNotFound):
            other_vault.chats.get(deleted_chat["id"])
        restored_shares = MarkdownExports(restored / "data", "https://example.invalid/capture")
        self.assertIn(b"synthetic legacy thought", restored_shares.card_page("a" * 64))
        self.assertEqual(card["id"], restored_shares.list(owner["account_id"])[0]["id"])
        with self.assertRaises(CaptureNotFound):
            restored_shares.card_page("b" * 64)
        self.assertEqual(source_changes, self.store.changes())
        self.assertEqual([], list(self.output.glob(".staging-*")))

    def test_backup_failure_never_publishes_partial_archive_and_releases_locks(self):
        with patch.object(backup_tool, "copy_tree", side_effect=OSError("synthetic disk failure")):
            with self.assertRaises(OSError):
                backup_tool.backup(str(self.data), str(self.output))
        self.assertEqual([], list(self.output.iterdir()))
        self.assertEqual(2, self.store.put(self.record_id, {"comment": "still writable"}, 1)["revision"])

    def test_source_symlinks_and_nested_roots_rejected_without_copying_outside(self):
        outside = self.root / "outside.txt"
        outside.write_text("synthetic private outside file")
        (self.data / "blobs" / "unexpected-link").symlink_to(outside)
        with self.assertRaises(ValueError):
            backup_tool.backup(str(self.data), str(self.output))
        self.assertEqual([], list(self.output.iterdir()))
        self.assertEqual("synthetic private outside file", outside.read_text())
        with self.assertRaises(ValueError):
            backup_tool.backup(str(self.data), str(self.data / "nested-backup"))
        self.assertFalse((self.data / "nested-backup").exists())
        with self.assertRaises(ValueError):
            backup_tool.backup(str(self.data), "/")

    def test_readers_continue_but_writers_wait_for_consistent_asset_snapshot(self):
        waiting, release, writer_started, writer_done = (threading.Event() for _ in range(4))
        original = backup_tool.snapshot_database
        errors, results = [], []
        second_store = CaptureStore(self.data)

        def snapshot(source, destination):
            waiting.set()
            if not release.wait(5):
                raise TimeoutError("synthetic test deadline")
            return original(source, destination)

        def run_backup():
            try:
                results.append(backup_tool.backup(str(self.data), str(self.output)))
            except Exception as error:
                errors.append(error)

        def run_writer():
            writer_started.set()
            try:
                second_store.put(self.record_id, {"comment": "after backup snapshot"}, 1)
            except Exception as error:
                errors.append(error)
            finally:
                writer_done.set()

        with patch.object(backup_tool, "snapshot_database", side_effect=snapshot):
            worker = threading.Thread(target=run_backup)
            writer = threading.Thread(target=run_writer)
            worker.start()
            self.assertTrue(waiting.wait(5))
            try:
                writer.start()
                self.assertTrue(writer_started.wait(1))
                self.assertFalse(writer_done.wait(0.1))
                self.assertEqual("synthetic legacy thought", self.store.get(self.record_id)["comment"])
            finally:
                release.set()
                worker.join(10)
                writer.join(10)
        self.assertFalse(worker.is_alive())
        self.assertFalse(writer.is_alive())
        self.assertEqual([], errors)
        self.assertEqual("after backup snapshot", self.store.get(self.record_id)["comment"])
        snapshot_store = CaptureStore(self.unpack(results[0]) / "data")
        self.assertEqual("synthetic legacy thought", snapshot_store.get(self.record_id)["comment"])
        self.assertEqual(minimal_png(), snapshot_store.asset(self.record_id, "original")[0].read_bytes())

    def test_legacy_no_chat_compatibility_unique_archives_and_scoped_retention(self):
        # Only synthetic fixture files are removed to emulate pre-chat versions.
        (self.data / "chat.sqlite3").unlink()
        self.output.mkdir()
        old = self.output / "heartnote-capture-20000101T000000Z.tar.gz"
        old.write_bytes(b"synthetic expired archive")
        old_sum = self.output / (old.name + ".sha256")
        old_sum.write_text("synthetic expired checksum")
        unrelated = self.output / "do-not-delete.txt"
        unrelated.write_text("unrelated")
        for path in (old, old_sum, unrelated): os.utime(path, (1, 1))
        first = backup_tool.backup(str(self.data), str(self.output))
        second = backup_tool.backup(str(self.data), str(self.output))
        self.assertNotEqual(first, second)
        self.assertTrue(first.is_file())
        self.assertTrue(second.is_file())
        self.assertFalse(old.exists())
        self.assertFalse(old_sum.exists())
        self.assertTrue(unrelated.exists())
        restored = self.unpack(first)
        self.assertFalse((restored / "data" / "chat.sqlite3").exists())
        self.assertEqual("synthetic legacy thought", CaptureStore(restored / "data").get(self.record_id)["comment"])


if __name__ == "__main__":
    unittest.main()
