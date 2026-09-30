"""Private account chat snapshots. Never used by legacy or AI/MCP read surfaces.

The attached database has its own change sequence. All chat mutations acquire
the capture database write transaction as well, so parent validation and chat
uploads cannot race even between separate server processes. Parent deletions
commit to the capture change feed BEFORE chat cleanup in a separate transaction:
ATTACH plus WAL does not provide cross-database crash atomicity. The durable
parent change sequence is an outbox; every chat access reconciles it, including
after a restore or crash, so deferred cleanup can never disclose deleted chats.
"""
from __future__ import annotations

import base64
import binascii
import ipaddress
import json
import sqlite3
import struct
import time
import uuid
import zlib
from datetime import datetime
from typing import Any
from urllib.parse import urlsplit

from .store import CAPTURE_ID, CaptureConflict, CaptureNotFound, CaptureValidationError

MAX_CHAT_BYTES = 8 * 1024 * 1024
MAX_MESSAGES = 500
LEASE_SECONDS = 300
ROOT_FIELDS = {"schema_version", "id", "record_id", "title", "created_at", "updated_at", "snapshot", "model", "messages"}
SNAPSHOT_FIELDS = {"record_id", "fingerprint", "record_revision", "record_created_at", "kind", "tags", "thought", "excerpt", "original", "source_url", "source_app", "source_type", "context_note", "images", "modules", "consent"}
MESSAGE_FIELDS = {"id", "role", "content", "created_at", "status", "request_id", "model", "error"}
SAFE_ERROR_CODES = {
    "", "cancelled", "account_changed", "consent_required", "record_unavailable", "conversation_unavailable",
    "conversation_busy", "revision_conflict", "model_required", "profile_changed", "vision_required",
    "message_too_long", "conversation_too_long", "conversation_too_large", "context_too_large",
    "response_too_large", "response_limit", "response_incomplete", "empty_response", "provider_auth",
    "provider_rate_limit", "provider_redirect", "provider_model_or_endpoint", "provider_input_rejected",
    "provider_unavailable", "provider_error", "provider_filtered", "unsupported_response", "login_required",
    "network_timeout", "chat_failed", "interrupted", "sync_before_send", "chat_stopped", "chat_network",
    "chat_timeout", "chat_auth", "chat_model", "chat_context", "chat_vision", "chat_limit", "chat_empty",
    "chat_consent", "chat_conflict", "chat_request", "chat_interrupted",
}


class ChatConflict(CaptureConflict):
    def __init__(self, current_revision: int, code: str = "revision_conflict"):
        super().__init__(current_revision)
        self.code = code
        self.args = (code,)


def _object(value: Any, allowed: set[str], name: str) -> dict:
    if not isinstance(value, dict) or not set(value).issubset(allowed):
        raise CaptureValidationError(f"{name} has unsupported fields or type")
    return value


def _text(value: Any, name: str, maximum: int = 200_000, *, empty: bool = True) -> str:
    if not isinstance(value, str) or len(value) > maximum or "\x00" in value or (not empty and not value):
        raise CaptureValidationError(f"invalid {name}")
    return value


def _uuid(value: Any, name: str) -> str:
    value = _text(value, name, 36, empty=False)
    try:
        uuid.UUID(value)
    except ValueError as error:
        raise CaptureValidationError(f"invalid {name}") from error
    return value


def _date(value: Any, name: str) -> None:
    value = _text(value, name, 40, empty=False)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.utcoffset() is None or parsed.utcoffset().total_seconds() != 0:
            raise ValueError()
    except ValueError as error:
        raise CaptureValidationError(f"{name} must be an ISO UTC timestamp") from error


def _image_bounds(width: int, height: int) -> None:
    if not 1 <= width <= 4096 or not 1 <= height <= 4096 or width * height > 4_194_304:
        raise CaptureValidationError("snapshot image dimensions exceed the limit")


def _image_header(data: bytes, kind: str) -> None:
    """Bound decode allocations without a native image library on the server.

    This checks container structure, signatures and declared dimensions, not
    pixel equivalence or image safety beyond those bounds. Client bitmap
    decoders still need to handle malformed images without crashing.
    """
    if not data or len(data) > 3 * 1024 * 1024:
        raise CaptureValidationError("snapshot image exceeds the size limit")
    if kind == "image/png" and data.startswith(b"\x89PNG\r\n\x1a\n"):
        offset, header, pixels = 8, False, False
        while offset + 12 <= len(data):
            size = int.from_bytes(data[offset:offset + 4], "big")
            name = data[offset + 4:offset + 8]
            end = offset + 12 + size
            if end > len(data):
                break
            payload = data[offset + 8:end - 4]
            checksum = int.from_bytes(data[end - 4:end], "big")
            if zlib.crc32(name + payload) != checksum:
                break
            if not header:
                if name != b"IHDR" or size != 13:
                    break
                _image_bounds(*struct.unpack(">II", payload[:8]))
                header = True
            elif name == b"IHDR":
                break
            if name == b"IDAT":
                pixels = pixels or bool(size)
            if name == b"IEND":
                if not size and pixels and end == len(data):
                    return
                break
            offset = end
    elif kind == "image/jpeg" and data.startswith(b"\xff\xd8") and data.endswith(b"\xff\xd9"):
        offset, frame = 2, False
        while offset + 4 <= len(data):
            if data[offset] != 255:
                break
            while offset < len(data) and data[offset] == 255:
                offset += 1
            if offset + 2 >= len(data):
                break
            marker = data[offset]
            offset += 1
            if marker in (0, 216, 217) or 208 <= marker <= 215:
                break
            size = int.from_bytes(data[offset:offset + 2], "big")
            if size < 2 or offset + size > len(data) - 2:
                break
            if marker in (192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207):
                if size < 8:
                    break
                height, width = struct.unpack(">HH", data[offset + 3:offset + 7])
                _image_bounds(width, height)
                frame = True
            if marker == 218:  # Start of entropy-coded scan, bounded by frame.
                if frame and size >= 6 and offset + size < len(data) - 2:
                    return
                break
            offset += size
    elif kind == "image/webp" and len(data) >= 20 and data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        if int.from_bytes(data[4:8], "little") != len(data) - 8:
            raise CaptureValidationError("invalid snapshot image container")
        offset, pixels = 12, False
        while offset + 8 <= len(data):
            name = data[offset:offset + 4]
            size = int.from_bytes(data[offset + 4:offset + 8], "little")
            end = offset + 8 + size
            if end > len(data):
                break
            payload = data[offset + 8:end]
            if name == b"VP8X":
                if size != 10 or payload[0] & 2:  # No animated page snapshots.
                    break
                _image_bounds(1 + int.from_bytes(payload[4:7], "little"), 1 + int.from_bytes(payload[7:10], "little"))
            elif name == b"VP8 ":
                if size < 10 or payload[3:6] != b"\x9d\x01\x2a":
                    break
                _image_bounds(int.from_bytes(payload[6:8], "little") & 16383, int.from_bytes(payload[8:10], "little") & 16383)
                pixels = True
            elif name == b"VP8L":
                if size < 5 or payload[0] != 47:
                    break
                bits = int.from_bytes(payload[1:5], "little")
                _image_bounds(1 + (bits & 16383), 1 + ((bits >> 14) & 16383))
                pixels = True
            offset = end + (size & 1)
        if offset == len(data) and pixels:
            return
    raise CaptureValidationError("invalid snapshot image container")


def canonicalize_chat(body: Any, conversation_id: str) -> tuple[dict, str]:
    body = _object(body, ROOT_FIELDS, "conversation")
    if body.get("schema_version") != 1 or isinstance(body.get("schema_version"), bool):
        raise CaptureValidationError("unsupported chat schema_version")
    _uuid(conversation_id, "conversation id")
    if body.get("id") != conversation_id:
        raise CaptureValidationError("body id does not match the URL")
    record_id = _text(body.get("record_id"), "record_id", 96, empty=False)
    if not CAPTURE_ID.fullmatch(record_id):
        raise CaptureValidationError("invalid record_id")
    _text(body.get("title"), "title", 500)
    _date(body.get("created_at"), "created_at")
    _date(body.get("updated_at"), "updated_at")
    snapshot = _object(body.get("snapshot"), SNAPSHOT_FIELDS, "snapshot")
    if snapshot.get("record_id") != record_id or snapshot.get("consent") != "record_chat_only":
        raise CaptureValidationError("snapshot record or consent mismatch")
    for name in ("fingerprint", "kind", "source_app", "source_type"):
        _text(snapshot.get(name, ""), name, 500)
    _text(snapshot.get("source_url", ""), "source_url", 8192)
    for name in ("thought", "excerpt", "original", "context_note"):
        _text(snapshot.get(name, ""), name)
    if snapshot.get("record_created_at"):
        _date(snapshot["record_created_at"], "record_created_at")
    elif "record_created_at" in snapshot:
        _text(snapshot["record_created_at"], "record_created_at", 40)
    revision = snapshot.get("record_revision", 0)
    if isinstance(revision, bool) or not isinstance(revision, int) or not 0 <= revision <= 2**63 - 1:
        raise CaptureValidationError("invalid record_revision")
    tags = snapshot.get("tags", [])
    if not isinstance(tags, list) or len(tags) > 100:
        raise CaptureValidationError("invalid tags")
    for tag in tags:
        _text(tag, "tag", 100)
    modules = snapshot.get("modules", [])
    if not isinstance(modules, list) or len(modules) > 5 or any(
        not isinstance(module, str) or module not in {"thought", "excerpt", "original", "images", "metadata"}
        for module in modules
    ) or len(set(modules)) != len(modules):
        raise CaptureValidationError("invalid snapshot modules")
    images = snapshot.get("images", [])
    if not isinstance(images, list) or len(images) > 4:
        raise CaptureValidationError("invalid snapshot images")
    for module in ("thought", "excerpt", "original", "images"):
        if module not in modules and snapshot.get(module):
            raise CaptureValidationError("snapshot contains an unselected module")
    if "metadata" not in modules and any(snapshot.get(key) for key in (
        "record_created_at", "kind", "tags", "source_url", "source_app", "source_type"
    )):
        raise CaptureValidationError("snapshot contains unselected metadata")
    for image in images:
        image = _object(image, {"label", "content_type", "data_base64"}, "image")
        _text(image.get("label", ""), "image label", 100)
        kind = image.get("content_type")
        if kind not in ("image/png", "image/jpeg", "image/webp"):
            raise CaptureValidationError("unsupported image content_type")
        encoded = _text(image.get("data_base64"), "image data", MAX_CHAT_BYTES, empty=False)
        try:
            data = base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error) as error:
            raise CaptureValidationError("invalid image base64") from error
        _image_header(data, kind)
    model = _object(body.get("model"), {"label", "model", "base_url"}, "model")
    _text(model.get("label", ""), "model label", 200)
    _text(model.get("model"), "model name", 200, empty=False)
    endpoint = _text(model.get("base_url"), "model endpoint", 2048, empty=False)
    try:
        url = urlsplit(endpoint)
        local = url.hostname == "localhost"
        if url.hostname and not local:
            try:
                local = ipaddress.ip_address(url.hostname).is_loopback
            except ValueError:
                pass
        if not url.hostname or url.username or url.password or url.query or url.fragment or (
            url.scheme != "https" and not (url.scheme == "http" and local)
        ) or any(c.isspace() or ord(c) < 32 for c in endpoint):
            raise ValueError()
        url.port  # Validate malformed ports without contacting any endpoint.
    except ValueError as error:
        raise CaptureValidationError("model endpoint must not contain credentials, query, or fragment") from error
    messages = body.get("messages")
    if not isinstance(messages, list) or not 1 <= len(messages) <= MAX_MESSAGES:
        raise CaptureValidationError("messages must contain 1 to 500 items")
    ids = set()
    assistant_requests = set()
    saw_user = False
    generating = []
    for index, message in enumerate(messages):
        message = _object(message, MESSAGE_FIELDS, "message")
        message_id = _uuid(message.get("id"), "message id")
        if message_id in ids:
            raise CaptureValidationError("duplicate message id")
        ids.add(message_id)
        if message.get("role") not in ("user", "assistant"):
            raise CaptureValidationError("invalid message role")
        if message["role"] == "user":
            saw_user = True
        elif not saw_user:
            raise CaptureValidationError("assistant message requires a preceding user question")
        _text(message.get("content"), "message content", 100_000)
        _date(message.get("created_at"), "message created_at")
        if message.get("status") not in ("complete", "generating", "stopped", "failed"):
            raise CaptureValidationError("invalid message status")
        if message["role"] == "user" and message["status"] != "complete":
            raise CaptureValidationError("user message must be complete")
        if "request_id" in message and message["request_id"]:
            _uuid(message["request_id"], "request_id")
            if message["role"] == "assistant":
                if message["request_id"] in assistant_requests:
                    raise CaptureValidationError("duplicate assistant request_id")
                assistant_requests.add(message["request_id"])
        elif "request_id" in message:
            _text(message["request_id"], "request_id", 36)
        _text(message.get("model", ""), "message model", 200)
        error = _text(message.get("error", ""), "message error", 80)
        if error not in SAFE_ERROR_CODES:
            raise CaptureValidationError("message error must be a safe error code")
        if message["status"] == "generating":
            if message["role"] != "assistant" or index != len(messages) - 1 or not message.get("request_id"):
                raise CaptureValidationError("only the final assistant can be generating")
            generating.append(message)
    if len(generating) > 1:
        raise CaptureValidationError("multiple generating requests")
    try:
        encoded = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
        if len(encoded.encode("utf-8")) > MAX_CHAT_BYTES:
            raise CaptureValidationError("conversation exceeds 8 MiB")
    except (ValueError, UnicodeError, TypeError) as error:
        raise CaptureValidationError("invalid conversation JSON or size") from error
    return body, encoded


class ChatStore:
    def __init__(self, store):
        self.store = store
        self.clock = time.time
        self._initialized = False

    def initialize(self) -> None:
        with self.store._lock:
            if self._initialized:
                return
            self._initialize()
            self._initialized = True

    def _initialize(self) -> None:
        with self.store.connection(include_chat=True) as db:
            db.executescript("""
                PRAGMA chat.journal_mode=WAL;
                PRAGMA chat.synchronous=FULL;
                PRAGMA chat.secure_delete=ON;
                CREATE TABLE IF NOT EXISTS chat.conversations (
                    id TEXT PRIMARY KEY, record_id TEXT NOT NULL,
                    record_sequence INTEGER NOT NULL, revision INTEGER NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0, payload_json TEXT,
                    lease_request TEXT, lease_until REAL NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS chat.conversations_record ON conversations(record_id);
                CREATE TABLE IF NOT EXISTS chat.changes (
                    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                    id TEXT NOT NULL, record_id TEXT NOT NULL,
                    deleted INTEGER NOT NULL, revision INTEGER NOT NULL
                );
            """)
            db.execute("BEGIN IMMEDIATE")
            try:
                self._reconcile(db)
                db.commit()
            except Exception:
                db.rollback()
                raise

    @staticmethod
    def _precondition(revision: Any) -> None:
        if isinstance(revision, bool) or not isinstance(revision, int) or not 0 <= revision <= 2**63 - 1:
            raise CaptureValidationError("If-Match revision is required")

    @staticmethod
    def _change(db: sqlite3.Connection, row: dict, deleted: bool, revision: int) -> None:
        db.execute("INSERT INTO chat.changes(id,record_id,deleted,revision) VALUES (?,?,?,?)",
                   (row["id"], row["record_id"], int(deleted), revision))

    def _tombstone(self, db: sqlite3.Connection, row: sqlite3.Row) -> dict:
        revision = int(row["revision"]) + 1
        db.execute("UPDATE chat.conversations SET deleted=1,revision=?,payload_json=NULL,lease_request=NULL,lease_until=0 WHERE id=?",
                   (revision, row["id"]))
        self._change(db, row, True, revision)
        return {"id": row["id"], "deleted": True, "revision": revision}

    def reconcile(self) -> None:
        self.initialize()
        with self.store._lock, self.store.connection(include_chat=True) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                self._reconcile(db)
                db.commit()
            except Exception:
                db.rollback()
                raise

    def _reconcile(self, db: sqlite3.Connection) -> None:
        # Restoring a parent never restores its old conversations. Main changes
        # are already durable before this separate chat-only write transaction.
        rows = db.execute("""
            SELECT c.* FROM chat.conversations c
            WHERE c.deleted=0 AND (
                NOT EXISTS (SELECT 1 FROM main.captures r WHERE r.id=c.record_id AND r.deleted=0)
                OR EXISTS (SELECT 1 FROM main.changes e WHERE e.capture_id=c.record_id
                    AND e.sequence>c.record_sequence AND e.operation IN ('delete','purge')))
        """).fetchall()
        for row in rows:
            self._tombstone(db, row)

    @staticmethod
    def _decode(row: sqlite3.Row) -> dict:
        result = json.loads(row["payload_json"])
        result["revision"] = int(row["revision"])
        return result

    def get(self, conversation_id: str) -> dict:
        self.initialize()
        with self.store._lock, self.store.connection(include_chat=True) as db:
            db.execute("BEGIN IMMEDIATE")
            self._reconcile(db)
            row = db.execute("SELECT * FROM chat.conversations WHERE id=? AND deleted=0", (conversation_id,)).fetchone()
            db.commit()
            if row is None:
                raise CaptureNotFound(conversation_id)
            return self._decode(row)

    def put(self, conversation_id: str, body: Any, base_revision: int) -> dict:
        self._precondition(base_revision)
        body, encoded = canonicalize_chat(body, conversation_id)
        self.initialize()
        with self.store._lock, self.store.connection(include_chat=True) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                self._reconcile(db)
                # Persist any recovered tombstones even when the incoming write
                # below conflicts; all checks remain in a second write lock.
                db.commit()
                db.execute("BEGIN IMMEDIATE")
                parent = db.execute("SELECT deleted FROM captures WHERE id=?", (body["record_id"],)).fetchone()
                if parent is None or parent["deleted"]:
                    raise CaptureNotFound(body["record_id"])
                row = db.execute("SELECT * FROM chat.conversations WHERE id=?", (conversation_id,)).fetchone()
                if row is not None and row["deleted"]:
                    raise ChatConflict(int(row["revision"]), "conversation_deleted")
                if row is not None and row["payload_json"] == encoded:
                    # Lost-response retry is idempotent. An exact active request
                    # replay also renews its lease without flooding change feeds.
                    if row["lease_request"]:
                        db.execute("UPDATE chat.conversations SET lease_until=? WHERE id=?",
                                   (self.clock() + LEASE_SECONDS, conversation_id))
                    db.commit()
                    return self._decode(row)
                current = int(row["revision"]) if row else 0
                if base_revision != current:
                    raise ChatConflict(current)
                if row:
                    old = json.loads(row["payload_json"])
                    if any(old[key] != body[key] for key in ("record_id", "created_at", "snapshot")):
                        raise ChatConflict(current, "snapshot_immutable")
                    self._validate_transition(old, body, row)
                messages = body["messages"]
                active = messages[-1] if messages[-1]["status"] == "generating" else None
                parent_sequence = int(db.execute("SELECT COALESCE(MAX(sequence),0) FROM main.changes").fetchone()[0])
                revision = current + 1
                db.execute("""INSERT INTO chat.conversations
                    (id,record_id,record_sequence,revision,deleted,payload_json,lease_request,lease_until)
                    VALUES (?,?,?,?,0,?,?,?) ON CONFLICT(id) DO UPDATE SET
                    revision=excluded.revision,payload_json=excluded.payload_json,
                    lease_request=excluded.lease_request,lease_until=excluded.lease_until""",
                    (conversation_id, body["record_id"], parent_sequence, revision, encoded,
                     active["request_id"] if active else None, self.clock() + LEASE_SECONDS if active else 0))
                self._change(db, body, False, revision)
                db.commit()
                return {**body, "revision": revision}
            except Exception:
                db.rollback()
                raise

    def _validate_transition(self, old: dict, new: dict, row: sqlite3.Row) -> None:
        current = int(row["revision"])
        previous = old["messages"]
        messages = new["messages"]
        if len(messages) < len(previous):
            raise ChatConflict(current, "message_history_immutable")
        for index, message in enumerate(previous):
            replacement = messages[index]
            if message == replacement:
                continue
            if message["status"] != "generating" or any(
                message.get(key) != replacement.get(key)
                for key in ("id", "role", "created_at", "request_id", "model")
            ):
                raise ChatConflict(current, "message_history_immutable")
        if row["lease_request"] and row["lease_until"] > self.clock():
            # Another request cannot append to or terminate the current lease in
            # the same write; only the active placeholder may gain output/status.
            if len(messages) != len(previous) or new["model"] != old["model"]:
                raise ChatConflict(current, "generation_in_progress")
        if len(messages) > len(previous) and previous[-1]["status"] == "generating":
            if messages[len(previous) - 1]["status"] == "generating":
                raise ChatConflict(current, "generation_in_progress")

    def delete(self, conversation_id: str, base_revision: int) -> dict:
        self._precondition(base_revision)
        self.initialize()
        with self.store._lock, self.store.connection(include_chat=True) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                self._reconcile(db)
                db.commit()
                db.execute("BEGIN IMMEDIATE")
                row = db.execute("SELECT * FROM chat.conversations WHERE id=?", (conversation_id,)).fetchone()
                if row is None:
                    raise CaptureNotFound(conversation_id)
                current = int(row["revision"])
                if row["deleted"] and base_revision in (current, current - 1):
                    db.commit()
                    return {"id": conversation_id, "deleted": True, "revision": current}
                if base_revision != current:
                    raise ChatConflict(current)
                result = self._tombstone(db, row)
                db.commit()
                return result
            except Exception:
                db.rollback()
                raise

    def changes(self, after: int = 0, limit: int = 100) -> dict:
        if isinstance(after, bool) or not isinstance(after, int) or not 0 <= after <= 2**63 - 1:
            raise CaptureValidationError("after must be a non-negative integer")
        if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 500:
            raise CaptureValidationError("limit must be between 1 and 500")
        self.initialize()
        with self.store._lock, self.store.connection(include_chat=True) as db:
            db.execute("BEGIN IMMEDIATE")
            self._reconcile(db)
            rows = db.execute("SELECT * FROM chat.changes WHERE sequence>? ORDER BY sequence LIMIT ?",
                              (after, limit + 1)).fetchall()
            db.commit()
        page = rows[:limit]
        changes = [{**dict(row), "deleted": bool(row["deleted"])} for row in page]
        return {"changes": changes, "next_cursor": int(page[-1]["sequence"]) if page else after, "has_more": len(rows) > limit}
