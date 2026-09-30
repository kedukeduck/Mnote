import base64
import copy
import io
import json
import sqlite3
import struct
import tempfile
import threading
import unittest
import uuid
import zlib
import zipfile
from concurrent.futures import ThreadPoolExecutor
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

from heartnote_capture.chat_store import ChatConflict, MAX_CHAT_BYTES, canonicalize_chat
from heartnote_capture.http_api import Tokens, create_server
from heartnote_capture.store import CaptureNotFound, CaptureStore, CaptureValidationError, minimal_png


STAMP = "2026-09-29T00:00:00.000Z"
JPEG = base64.b64decode(
    "/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD5/ooooA//2Q=="
)


def message(role="user", content="A synthetic question", status="complete", request_id=None):
    return {"id": str(uuid.uuid4()), "role": role, "content": content, "created_at": STAMP,
            "status": status, "request_id": request_id or str(uuid.uuid4()), "model": "synthetic-model", "error": ""}


def conversation(record_id="synthetic-record-123", generating=False):
    request_id = str(uuid.uuid4())
    result = {"schema_version": 1, "id": str(uuid.uuid4()), "record_id": record_id,
              "title": "A synthetic discussion", "created_at": STAMP, "updated_at": STAMP,
              "snapshot": {"record_id": record_id, "fingerprint": "synthetic-hash", "record_revision": 1,
                           "record_created_at": STAMP, "kind": "thought", "tags": ["test"],
                           "thought": "MY SYNTHETIC THOUGHT", "excerpt": "QUOTED SYNTHETIC TEXT", "original": "BACKGROUND ONLY",
                           "source_url": "https://example.invalid/page", "source_app": "synthetic", "source_type": "text",
                           "context_note": "unconfirmed clipboard relationship", "images": [
                               {"label": "圈选截图", "content_type": "image/png", "data_base64": base64.b64encode(minimal_png()).decode()}],
                           "modules": ["thought", "excerpt", "original", "images", "metadata"], "consent": "record_chat_only"},
              "model": {"label": "Synthetic test", "model": "synthetic-model", "base_url": "https://example.invalid/v1"},
              "messages": [message(request_id=request_id)]}
    if generating:
        result["messages"].append(message("assistant", "", "generating", request_id))
    return result


class ChatStoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = CaptureStore(self.temp.name)
        self.record_id = "synthetic-record-123"
        self.store.put(self.record_id, {"comment": "synthetic", "ai_access": "deny"})

    def tearDown(self):
        self.temp.cleanup()

    def test_snapshot_and_completed_history_immutable_and_cas_retry(self):
        body = conversation()
        created = self.store.chats.put(body["id"], body, 0)
        self.assertEqual(1, created["revision"])
        self.assertEqual(created, self.store.chats.put(body["id"], body, 0))
        renamed = copy.deepcopy(body)
        renamed["title"] = "Renamed by device A"
        updated = self.store.chats.put(body["id"], renamed, 1)
        self.assertEqual(2, updated["revision"])
        with self.assertRaises(ChatConflict):
            self.store.chats.put(body["id"], body, 1)
        for key in ("snapshot", "created_at", "record_id", "messages"):
            changed = copy.deepcopy(renamed)
            if key == "snapshot": changed[key]["thought"] = "silent replacement"
            elif key == "messages": changed[key][0]["content"] = "rewritten question"
            elif key == "record_id":
                changed[key] = "synthetic-record-456"
                changed["snapshot"][key] = changed[key]
                self.store.put(changed[key], {"comment": "other synthetic"})
            else: changed[key] = "2026-09-30T00:00:00Z"
            with self.subTest(key=key), self.assertRaises(ChatConflict):
                self.store.chats.put(body["id"], changed, 2)
        self.assertEqual(2, len(self.store.chats.changes()["changes"]))

    def test_generation_lease_renew_partial_final_expiry_and_conflict(self):
        now = [1000.0]
        self.store.chats.clock = lambda: now[0]
        body = conversation(generating=True)
        self.store.chats.put(body["id"], body, 0)
        now[0] += 200
        self.store.chats.put(body["id"], body, 0)  # Idempotent heartbeat renews.
        next_turn = copy.deepcopy(body)
        next_turn["messages"][-1]["status"] = "stopped"
        next_turn["messages"].append(message(content="new question"))
        with self.assertRaises(ChatConflict) as result:
            self.store.chats.put(body["id"], next_turn, 1)
        self.assertEqual("generation_in_progress", result.exception.code)
        now[0] = 1401  # More than 300 from original start; renewed lease remains.
        with self.assertRaises(ChatConflict):
            self.store.chats.put(body["id"], next_turn, 1)
        partial = copy.deepcopy(body)
        partial["messages"][-1]["content"] = "partial synthetic answer"
        self.store.chats.put(body["id"], partial, 1)
        now[0] = 1702
        next_turn = copy.deepcopy(partial)
        next_turn["messages"][-1]["status"] = "stopped"
        next_turn["messages"].append(message(content="new question"))
        self.store.chats.put(body["id"], next_turn, 2)
        final = copy.deepcopy(partial)
        final["messages"][-1]["status"] = "complete"
        with self.assertRaises(ChatConflict):
            self.store.chats.put(body["id"], final, 2)  # Stale stream cannot overwrite.
        fresh = conversation(generating=True)
        self.store.chats.put(fresh["id"], fresh, 0)
        fresh["messages"][-1]["status"] = "complete"
        fresh["messages"][-1]["content"] = "finished"
        self.assertEqual(2, self.store.chats.put(fresh["id"], fresh, 1)["revision"])

    def test_direct_record_delete_purge_restore_never_resurrect_chat(self):
        body = conversation()
        self.store.chats.put(body["id"], body, 0)
        deleted = self.store.soft_delete(self.record_id, 1)
        with self.assertRaises(CaptureNotFound):
            self.store.chats.get(body["id"])
        self.assertTrue(self.store.chats.changes()["changes"][-1]["deleted"])
        with self.store.connection(include_chat=True) as db:
            row = db.execute("SELECT * FROM chat.conversations WHERE id=?", (body["id"],)).fetchone()
            self.assertIsNone(row["payload_json"])
            self.assertIsNone(row["lease_request"])
        restored = self.store.restore(self.record_id, deleted["revision"])
        with self.assertRaises(ChatConflict):
            self.store.chats.put(body["id"], body, 2)
        fresh = conversation()
        self.store.chats.put(fresh["id"], fresh, 0)
        self.store.soft_delete(self.record_id, restored["revision"])
        self.store.purge(self.record_id)
        with self.assertRaises(CaptureNotFound):
            self.store.chats.get(fresh["id"])
        self.store.put(self.record_id, {"comment": "same id recreated"}, 0)
        with self.assertRaises(ChatConflict):
            self.store.chats.put(fresh["id"], fresh, 2)

    def test_recovery_reconciles_deleted_parent_even_if_restored(self):
        body = conversation()
        self.store.chats.put(body["id"], body, 0)
        # Simulate a process dying across attached WAL commit boundaries. The
        # durable parent delete event is retained even if another client restored.
        with self.store.connection() as db:
            db.execute("INSERT INTO main.changes(capture_id,revision,operation,changed_at) VALUES (?,2,'delete',?)", (self.record_id, STAMP))
        reopened = CaptureStore(self.temp.name)
        with self.assertRaises(CaptureNotFound):
            reopened.chats.get(body["id"])
        self.assertTrue(reopened.chats.changes()["changes"][-1]["deleted"])
        self.assertEqual("synthetic", reopened.get(self.record_id)["comment"])

    def test_parent_commit_precedes_cleanup_and_cleanup_failure_is_recoverable(self):
        body = conversation()
        self.store.chats.put(body["id"], body, 0)
        # A transient chat storage failure must not undo or misreport a committed
        # parent deletion. No raw records/SQL paths are included in the warning.
        with patch.object(self.store.chats, "reconcile", side_effect=sqlite3.OperationalError("synthetic unavailable")):
            with self.assertLogs("heartnote_capture.store", "WARNING") as logs:
                result = self.store.soft_delete(self.record_id, 1)
        self.assertTrue(result["deleted"])
        self.assertNotIn(self.record_id, " ".join(logs.output))
        with self.store.connection(include_chat=True) as db:
            self.assertIsNotNone(db.execute("SELECT payload_json FROM chat.conversations").fetchone()[0])
        # Even a subsequent restore cannot undo the durable cleanup outbox.
        self.store.restore(self.record_id, result["revision"])
        with self.assertRaises(CaptureNotFound):
            self.store.chats.get(body["id"])
        with self.store.connection(include_chat=True) as db:
            self.assertIsNone(db.execute("SELECT payload_json FROM chat.conversations").fetchone()[0])

    def test_failed_parent_transaction_does_not_delete_chat_and_reads_are_compatible(self):
        body = conversation()
        self.store.chats.put(body["id"], body, 0)
        with self.store.connection() as db:
            db.execute("""CREATE TRIGGER synthetic_fail_parent_delete BEFORE INSERT ON main.changes
                WHEN NEW.operation='delete' BEGIN SELECT RAISE(ABORT,'synthetic rollback'); END""")
        with self.assertRaises(sqlite3.IntegrityError):
            self.store.soft_delete(self.record_id, 1)
        self.assertFalse(self.store.get(self.record_id)["deleted"])
        self.assertEqual(body["snapshot"], self.store.chats.get(body["id"])["snapshot"])
        self.assertEqual(1, len(self.store.chats.changes()["changes"]))
        # A legacy reader continues to read the unchanged record schema, and the
        # separate database never appears in ordinary list/search/change data.
        with sqlite3.connect(self.store.db_path) as legacy:
            payload = legacy.execute("SELECT payload_json FROM captures WHERE id=?", (self.record_id,)).fetchone()[0]
            tables = {row[0] for row in legacy.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertNotIn(body["id"], payload)
        self.assertNotIn("conversations", tables)
        self.assertNotIn(body["id"], json.dumps(self.store.changes()))
        self.assertEqual("synthetic", self.store.list()[0]["comment"])

    def test_delete_idempotence_pagination_and_removed_payload(self):
        body = conversation()
        self.store.chats.put(body["id"], body, 0)
        body["title"] = "Second revision"
        self.store.chats.put(body["id"], body, 1)
        first = self.store.chats.changes(limit=1)
        self.assertTrue(first["has_more"])
        second = self.store.chats.changes(first["next_cursor"], limit=1)
        self.assertFalse(second["has_more"])
        deleted = self.store.chats.delete(body["id"], 2)
        self.assertEqual(deleted, self.store.chats.delete(body["id"], 2))
        self.assertEqual(deleted, self.store.chats.delete(body["id"], 3))
        self.assertEqual(3, len(self.store.chats.changes()["changes"]))
        with self.assertRaises(ChatConflict):
            self.store.chats.put(body["id"], body, 3)
        with self.store.connection(include_chat=True) as db:
            self.assertIsNone(db.execute("SELECT payload_json FROM chat.conversations").fetchone()[0])

    def test_separate_instances_serialize_cas_and_parent_deletion(self):
        second_store = CaptureStore(self.temp.name)
        body = conversation()
        self.store.chats.put(body["id"], body, 0)
        barrier = threading.Barrier(2)

        def change(store, title):
            changed = copy.deepcopy(body)
            changed["title"] = title
            barrier.wait()
            try:
                store.chats.put(body["id"], changed, 1)
                return "updated"
            except ChatConflict:
                return "conflict"

        with ThreadPoolExecutor(2) as pool:
            a = pool.submit(change, self.store, "A")
            b = pool.submit(change, second_store, "B")
            self.assertCountEqual(["updated", "conflict"], [a.result(), b.result()])
        second_store.soft_delete(self.record_id, 1)
        with self.assertRaises(CaptureNotFound):
            self.store.chats.get(body["id"])

    def test_validation_rejects_credentials_unknown_fields_limits_and_invalid_types(self):
        body = conversation()
        cases = []

        def invalid(mutator):
            candidate = copy.deepcopy(body)
            mutator(candidate)
            cases.append(candidate)

        invalid(lambda v: v.update(api_key="NEVER STORE KEYS"))
        invalid(lambda v: v["model"].update(headers={"Authorization": "secret"}))
        invalid(lambda v: v["model"].update(base_url="https://u:secret@example.invalid/v1"))
        invalid(lambda v: v["model"].update(base_url="https://example.invalid/v1?key=secret"))
        invalid(lambda v: v["model"].update(base_url="http://example.invalid/v1"))
        invalid(lambda v: v["snapshot"].update(api_key="secret"))
        invalid(lambda v: v["snapshot"].update(consent="all_records"))
        invalid(lambda v: v["snapshot"].update(modules=[{}]))
        invalid(lambda v: v["snapshot"].update(tags=[{}]))
        invalid(lambda v: v["snapshot"].update(images=[{"href": "https://example.invalid/private.png"}]))
        invalid(lambda v: v["snapshot"]["images"][0].update(data_base64="<not a PNG>"))
        invalid(lambda v: v["snapshot"].update(record_revision=True))
        invalid(lambda v: v["messages"][0].update(content="x" * 100_001))
        invalid(lambda v: v["messages"][0].update(error="Bearer SUPERSECRET detail"))
        invalid(lambda v: v["messages"][0].update(error="sk-lowercase-secret"))
        invalid(lambda v: v["messages"][0].update(request_id=[]))
        invalid(lambda v: v["messages"].append(copy.deepcopy(v["messages"][0])))
        invalid(lambda v: v.update(messages=[message() for _ in range(501)]))
        invalid(lambda v: v.update(messages=[message(content="x" * 100_000) for _ in range(85)]))
        invalid(lambda v: v.update(created_at="no-date"))
        invalid(lambda v: v.update(created_at="2026-09-29T01:00:00+01:00"))
        invalid(lambda v: v.update(schema_version=True))
        for index, candidate in enumerate(cases):
            with self.subTest(index=index), self.assertRaises(CaptureValidationError):
                canonicalize_chat(candidate, candidate["id"])

    def test_text_only_unselected_metadata_and_loopback_test_endpoint(self):
        body = conversation()
        body["snapshot"].update(kind="", record_created_at="", tags=[], source_url="", source_app="", source_type="", images=[], excerpt="", original="", modules=["thought"])
        body["model"]["base_url"] = "http://127.0.0.1:1234/v1"
        self.assertEqual(body, canonicalize_chat(body, body["id"])[0])
        self.assertEqual(1, self.store.chats.put(body["id"], body, 0)["revision"])


class ChatHTTPTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = CaptureStore(self.temp.name)
        self.server = create_server("127.0.0.1", 0, self.store, Tokens("legacy-write", "legacy-read", "legacy-ai"))
        self.accounts = self.server.RequestHandlerClass.accounts
        self.accounts.ITERATIONS = 1000  # Only this isolated test fixture.
        self.a = self.accounts.activate("test-owner-a", "synthetic-password-123", self.accounts.invite(legacy_owner=True))
        self.b = self.accounts.activate("test-owner-b", "synthetic-password-123", self.accounts.invite())
        self.store.put("synthetic-record-123", {"comment": "synthetic only", "ai_access": "deny"})
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, method, path, body=None, token=None, revision=None, extra=None):
        headers = {"Content-Type": "application/json"}
        if token is not None: headers["Authorization"] = "Bearer " + token
        if revision is not None: headers["If-Match"] = f'"revision:{revision}"'
        headers.update(extra or {})
        request = Request(f"http://127.0.0.1:{self.server.server_port}" + path,
                          data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
        try:
            response = urlopen(request, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            data = response.read()
            value = json.loads(data) if response.headers.get_content_type() == "application/json" and data else data
            return response.status, value, response.headers

    def test_account_only_all_methods_and_isolation_including_adopted_legacy(self):
        body = conversation()
        path = "/v1/chat/conversations/" + body["id"]
        a, b = self.a["access_token"], self.b["access_token"]
        self.assertEqual(200, self.request("PUT", path, body, a, 0)[0])
        for token in (None, "legacy-read", "legacy-write", "legacy-ai"):
            expected = 401 if token is None else 403
            for method, endpoint in (("GET", path), ("HEAD", path), ("PUT", path), ("DELETE", path), ("GET", "/v1/chat/changes")):
                with self.subTest(token=token, method=method, endpoint=endpoint):
                    self.assertEqual(expected, self.request(method, endpoint, body if method == "PUT" else None, token, 1)[0])
        self.assertEqual(404, self.request("GET", path, token=b)[0])
        self.assertEqual(404, self.request("DELETE", path, token=b, revision=1)[0])
        self.assertEqual(404, self.request("PUT", path, body, b, 0)[0])
        self.assertEqual([], self.request("GET", "/v1/chat/changes", token=b)[1]["changes"])
        self.assertEqual(200, self.request("PUT", "/v1/captures/synthetic-record-123", {"comment": "B parent"}, b)[0])
        b_body = copy.deepcopy(body)
        b_body["snapshot"]["thought"] = "B account thought"
        self.assertEqual(200, self.request("PUT", path, b_body, b, 0)[0])
        self.assertEqual("MY SYNTHETIC THOUGHT", self.request("GET", path, token=a)[1]["snapshot"]["thought"])
        self.assertEqual("B account thought", self.request("GET", path, token=b)[1]["snapshot"]["thought"])
        # Ordinary record export and legacy/MCP data surfaces contain no chat.
        status, data, _ = self.request("GET", "/v1/export", token=a)
        self.assertEqual(200, status)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            self.assertFalse(any("chat" in name for name in archive.namelist()))
            self.assertNotIn(body["id"], archive.read("manifest.json").decode())

    def test_http_preconditions_feed_and_delete_parent_cascade(self):
        a = self.a["access_token"]
        body = conversation(generating=True)
        path = "/v1/chat/conversations/" + body["id"]
        self.assertEqual(400, self.request("PUT", path, body, a)[0])
        self.assertEqual(400, self.request("PUT", path, body, a, extra={"If-Match": "*"})[0])
        self.assertEqual(400, self.request("PUT", path, body, a, -1)[0])
        status, created, headers = self.request("PUT", path, body, a, 0)
        self.assertEqual(200, status)
        self.assertEqual('"revision:1"', headers["ETag"])
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertEqual("no-referrer", headers["Referrer-Policy"])
        self.assertIsNone(headers.get("Access-Control-Allow-Origin"))
        changed = copy.deepcopy(body)
        changed["messages"][-1]["content"] = "partial"
        self.assertEqual(409, self.request("PUT", path, changed, a, 0)[0])
        self.assertEqual(200, self.request("PUT", path, changed, a, 1)[0])
        next_turn = copy.deepcopy(changed)
        next_turn["messages"][-1]["status"] = "stopped"
        next_turn["messages"].append(message())
        status, error, _ = self.request("PUT", path, next_turn, a, 2)
        self.assertEqual(409, status)
        self.assertEqual("generation_in_progress", error["error"])
        page = self.request("GET", "/v1/chat/changes?limit=1", token=a)[1]
        self.assertTrue(page["has_more"])
        self.assertEqual(body["id"], page["changes"][0]["id"])
        final = self.request("GET", f'/v1/chat/changes?after={page["next_cursor"]}&limit=1', token=a)[1]
        self.assertFalse(final["has_more"])
        self.assertEqual(400, self.request("GET", "/v1/chat/changes?after=-1", token=a)[0])
        self.assertEqual(400, self.request("GET", "/v1/chat/changes?limit=0", token=a)[0])
        self.assertEqual(200, self.request("DELETE", "/v1/captures/synthetic-record-123", token=a, revision=1)[0])
        self.assertEqual(404, self.request("GET", path, token=a)[0])
        self.assertEqual(404, self.request("PUT", path, changed, a, 2)[0])
        tombstone = self.request("GET", "/v1/chat/changes?after=2", token=a)[1]["changes"][0]
        self.assertTrue(tombstone["deleted"])
        self.assertEqual(3, tombstone["revision"])
        self.assertEqual(200, self.request("POST", "/v1/captures/synthetic-record-123/restore", {"base_revision": 2}, a)[0])
        self.assertEqual(409, self.request("PUT", path, body, a, 3)[0])

    def test_http_body_size_rejected_before_read_and_logout_blocks_access(self):
        body = conversation()
        path = "/v1/chat/conversations/" + body["id"]
        a = self.a["access_token"]
        self.assertEqual(400, self.request("PUT", path, body, a, 0,
                                         {"Content-Length": str(MAX_CHAT_BYTES + 1)})[0])
        body["model"]["api_key"] = "SYNTHETIC_SECRET_DO_NOT_STORE"
        self.assertEqual(400, self.request("PUT", path, body, a, 0)[0])
        self.assertEqual([], self.store.chats.changes()["changes"])
        self.accounts.logout(a)
        self.assertEqual(401, self.request("GET", "/v1/chat/changes", token=a)[0])

    def test_android_and_windows_wire_shapes_can_continue_retry_without_repeating_user(self):
        a = self.a["access_token"]
        for platform in ("android", "windows"):
            with self.subTest(platform=platform):
                body = conversation(generating=True)
                # Mirrored client-produced contract shapes: Android Instant and
                # Windows Timestamp differ in precision, not UTC semantics.
                stamp = STAMP if platform == "android" else "2026-09-29T00:00:00Z"
                body["created_at"] = body["updated_at"] = stamp
                body["snapshot"].update(fingerprint="a" * 64, record_created_at="", kind="", tags=[], source_url="", source_app="", source_type="")
                body["snapshot"]["modules"].remove("metadata")
                body["snapshot"]["images"] = [
                    {"label": label, "content_type": "image/jpeg", "data_base64": base64.b64encode(JPEG).decode()}
                    for label in ("圈选截图", "完整页面截图")]
                for item in body["messages"]: item["created_at"] = stamp
                path = "/v1/chat/conversations/" + body["id"]
                self.assertEqual(200, self.request("PUT", path, body, a, 0)[0])
                body["messages"][-1].update(status="failed", error="network_timeout" if platform == "android" else "chat_network", content="partial answer kept")
                self.assertEqual(200, self.request("PUT", path, body, a, 1)[0])
                original = copy.deepcopy(body["messages"])
                body["messages"].append(message("assistant", "", "generating"))
                self.assertEqual(200, self.request("PUT", path, body, a, 2)[0])
                self.assertEqual(1, sum(item["role"] == "user" for item in body["messages"]))
                body["messages"][-1].update(status="complete", content="successful retry")
                self.assertEqual(200, self.request("PUT", path, body, a, 3)[0])
                self.assertEqual(original, self.request("GET", path, token=a)[1]["messages"][:2])
                stale = copy.deepcopy(body)
                # A second device may append a normal next turn with unchanged
                # snapshot and history, even after a retry-only assistant turn.
                request_id = str(uuid.uuid4())
                body["messages"] += [message(content="A follow-up", request_id=request_id), message("assistant", "", "generating", request_id)]
                self.assertEqual(200, self.request("PUT", path, body, a, 4)[0])
                stale["title"] = "Stale device title"
                self.assertEqual(409, self.request("PUT", path, stale, a, 4)[0])
                body["messages"][-1].update(status="complete", content="follow-up answer")
                self.assertEqual(200, self.request("PUT", path, body, a, 5)[0])
                result = self.request("GET", path, token=a)[1]
                self.assertEqual(body["snapshot"], result["snapshot"])
                self.assertEqual(6, result["revision"])

    def test_http_rejects_unknown_fields_invalid_images_and_unselected_material(self):
        a = self.a["access_token"]
        original = conversation()
        mutations = [
            lambda b: b.update(revision=0),
            lambda b: b.update(api_key="synthetic-secret"),
            lambda b: b["snapshot"].update(local_path="private-path"),
            lambda b: b["model"].update(authorization="synthetic-secret"),
            lambda b: b["messages"][0].update(provider_response={"debug": "unsafe"}),
            lambda b: b["snapshot"]["images"][0].update(href="https://example.invalid/private.png"),
            lambda b: b["snapshot"]["images"][0].update(data_base64="not_base64"),
            lambda b: b["snapshot"]["images"][0].update(content_type="image/svg+xml"),
            lambda b: b["snapshot"]["images"][0].update(content_type="image/jpeg"),
            lambda b: b["snapshot"]["images"][0].update(data_base64=base64.b64encode(b"\x89PNG\r\n\x1a\n").decode()),
            lambda b: b["snapshot"].update(modules=["thought"]),
            lambda b: b["messages"][0].update(role="assistant"),
        ]
        oversized = bytearray(minimal_png())
        oversized[16:24] = struct.pack(">II", 6000, 6000)
        oversized[29:33] = struct.pack(">I", zlib.crc32(oversized[12:29]))
        mutations.append(lambda b: b["snapshot"]["images"][0].update(data_base64=base64.b64encode(oversized).decode()))
        bad_jpeg = bytearray(JPEG)
        frame = bad_jpeg.index(b"\xff\xc0")
        bad_jpeg[frame + 7:frame + 9] = (6000).to_bytes(2, "big")
        mutations.append(lambda b: b["snapshot"]["images"][0].update(content_type="image/jpeg", data_base64=base64.b64encode(bad_jpeg).decode()))
        for index, mutate in enumerate(mutations):
            candidate = copy.deepcopy(original)
            mutate(candidate)
            with self.subTest(index=index):
                status, result, _ = self.request("PUT", "/v1/chat/conversations/" + candidate["id"], candidate, a, 0)
                self.assertEqual(400, status)
                self.assertEqual("invalid_request", result["error"])
                self.assertNotIn("synthetic-secret", json.dumps(result))
        self.assertEqual([], self.request("GET", "/v1/chat/changes", token=a)[1]["changes"])

    def test_http_deferred_cleanup_fails_closed_but_record_delete_still_succeeds(self):
        a = self.a["access_token"]
        body = conversation()
        path = "/v1/chat/conversations/" + body["id"]
        self.assertEqual(200, self.request("PUT", path, body, a, 0)[0])
        with patch.object(self.store.chats, "_reconcile", side_effect=sqlite3.OperationalError("PRIVATE SQL DETAIL")):
            with self.assertLogs("heartnote_capture.store", "WARNING"):
                status, parent, _ = self.request("DELETE", "/v1/captures/synthetic-record-123", token=a, revision=1)
            self.assertEqual(200, status)
            self.assertTrue(parent["deleted"])
            for method, endpoint in (("GET", path), ("GET", "/v1/chat/changes"), ("PUT", path), ("DELETE", path)):
                status, error, _ = self.request(method, endpoint, body if method == "PUT" else None, a, 1)
                self.assertEqual(503, status)
                self.assertEqual("chat_storage_unavailable", error["error"])
                self.assertNotIn("PRIVATE", json.dumps(error))
        self.assertEqual(404, self.request("GET", path, token=a)[0])
        self.assertTrue(self.request("GET", "/v1/chat/changes", token=a)[1]["changes"][-1]["deleted"])

    def test_http_lease_heartbeat_expiry_and_stale_stream_never_overwrite(self):
        now = [1000.0]
        self.store.chats.clock = lambda: now[0]
        a = self.a["access_token"]
        body = conversation(generating=True)
        path = "/v1/chat/conversations/" + body["id"]
        self.assertEqual(200, self.request("PUT", path, body, a, 0)[0])
        now[0] = 1250.0
        self.assertEqual(200, self.request("PUT", path, body, a, 0)[0])
        self.assertEqual(1, len(self.request("GET", "/v1/chat/changes", token=a)[1]["changes"]))
        replacement = copy.deepcopy(body)
        replacement["messages"][-1].update(status="failed", error="response_incomplete")
        replacement["messages"].append(message("assistant", "", "generating"))
        now[0] = 1549.0
        self.assertEqual(409, self.request("PUT", path, replacement, a, 1)[0])
        now[0] = 1551.0
        self.assertEqual(200, self.request("PUT", path, replacement, a, 1)[0])
        body["messages"][-1].update(status="complete", content="late old stream")
        status, conflict, _ = self.request("PUT", path, body, a, 1)
        self.assertEqual(409, status)
        self.assertEqual(2, conflict["current_revision"])
        result = self.request("GET", path, token=a)[1]
        self.assertEqual(replacement["messages"], result["messages"])

    def test_unavailable_chat_database_does_not_break_legacy_record_reading(self):
        chat_path = self.store.root / "chat.sqlite3"
        healthy_bytes = chat_path.read_bytes()
        try:
            # Only this test's synthetic temporary database is corrupted.
            chat_path.write_bytes(b"synthetic invalid database")
            self.assertEqual(200, self.request("GET", "/v1/captures/synthetic-record-123", token="legacy-read")[0])
            self.assertEqual(200, self.request("GET", "/v1/changes", token=self.a["access_token"])[0])
            self.assertEqual(503, self.request("GET", "/v1/chat/changes", token=self.a["access_token"])[0])
            with self.assertLogs("heartnote_capture.store", "WARNING"):
                restarted = CaptureStore(self.temp.name)
            self.assertEqual("synthetic only", restarted.get("synthetic-record-123")["comment"])
            with self.assertRaises(sqlite3.DatabaseError):
                restarted.chats.changes()
        finally:
            chat_path.write_bytes(healthy_bytes)
        self.assertEqual(200, self.request("GET", "/v1/chat/changes", token=self.a["access_token"])[0])


if __name__ == "__main__":
    unittest.main()
