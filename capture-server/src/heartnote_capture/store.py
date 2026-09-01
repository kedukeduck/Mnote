from __future__ import annotations

import base64
import binascii
import hashlib
import json
import os
import re
import sqlite3
import tempfile
import threading
import zipfile
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator


CAPTURE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{5,95}$")
VALID_KINDS = {"capture", "comment", "thought", "later", "todo", "journal"}
VALID_AI_ACCESS = {"deny", "local_only", "remote_no_memory", "remote_memory"}
VALID_ASSET_ROLES = {"original", "annotated", "context", "thumbnail"}
CONTENT_EXTENSIONS = {
    "image/png": ".png",
    "image/jpeg": ".jpg",
    "image/webp": ".webp",
}
MAX_TEXT = 200_000
MAX_JSON_BYTES = 32 * 1024 * 1024
MAX_ASSET_BYTES = 16 * 1024 * 1024


class CaptureValidationError(ValueError):
    pass


class CaptureConflict(RuntimeError):
    def __init__(self, current_revision: int):
        super().__init__(f"capture revision conflict; current revision is {current_revision}")
        self.current_revision = current_revision


class CaptureNotFound(KeyError):
    pass


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _clean_text(value: Any, *, maximum: int = MAX_TEXT) -> str:
    if value is None:
        return ""
    if not isinstance(value, str):
        raise CaptureValidationError("text fields must be strings")
    value = value.replace("\x00", " ").strip()
    if len(value) > maximum:
        raise CaptureValidationError(f"text field exceeds {maximum} characters")
    return value


def _created_at(record: dict[str, Any]) -> str:
    value = record.get("created_at", record.get("createdAt"))
    if value is None:
        return utc_now()
    if isinstance(value, (int, float)):
        seconds = float(value) / 1000.0 if value > 10_000_000_000 else float(value)
        try:
            return datetime.fromtimestamp(seconds, tz=timezone.utc).isoformat(
                timespec="milliseconds"
            ).replace("+00:00", "Z")
        except (OverflowError, OSError, ValueError) as error:
            raise CaptureValidationError("created_at is outside the supported range") from error
    if isinstance(value, str):
        candidate = value.strip()
        if not candidate:
            raise CaptureValidationError("created_at cannot be empty")
        try:
            datetime.fromisoformat(candidate.replace("Z", "+00:00"))
        except ValueError as error:
            raise CaptureValidationError("created_at must be ISO-8601 or epoch milliseconds") from error
        return candidate
    raise CaptureValidationError("created_at must be a string or number")


def _flatten_ocr(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return _clean_text(value)
    if not isinstance(value, list):
        raise CaptureValidationError("ocr must be a string or list")
    parts: list[str] = []
    for item in value[:10_000]:
        if isinstance(item, str):
            text = item
        elif isinstance(item, dict):
            text = item.get("text", "")
        else:
            continue
        if isinstance(text, str) and text.strip():
            parts.append(text.strip())
    return _clean_text("\n".join(parts))


def _source_from_record(record: dict[str, Any]) -> dict[str, Any]:
    source = record.get("source")
    if source is None:
        source = {
            "type": record.get("sourceType", "screen"),
            "text": record.get("sourceText", ""),
            "app_id": record.get("sourcePackage", ""),
        }
    if not isinstance(source, dict):
        raise CaptureValidationError("source must be an object")
    encoded = json.dumps(source, ensure_ascii=False)
    if len(encoded) > 1_000_000:
        raise CaptureValidationError("source metadata is too large")
    return source


def _decode_assets(record: dict[str, Any]) -> dict[str, tuple[str, bytes]]:
    raw_assets = record.get("assets", {})
    if raw_assets is None:
        raw_assets = {}
    if not isinstance(raw_assets, dict):
        raise CaptureValidationError("assets must be an object")
    legacy = {
        "original": record.get("original_png_base64"),
        "annotated": record.get("annotated_png_base64"),
    }
    decoded: dict[str, tuple[str, bytes]] = {}
    for role in VALID_ASSET_ROLES:
        entry = raw_assets.get(role)
        if entry is None and legacy.get(role):
            entry = {"content_type": "image/png", "data_base64": legacy[role]}
        if entry is None:
            continue
        if not isinstance(entry, dict):
            raise CaptureValidationError(f"asset {role} must be an object")
        content_type = entry.get("content_type", "image/png")
        encoded = entry.get("data_base64")
        if content_type not in CONTENT_EXTENSIONS:
            raise CaptureValidationError(f"asset {role} has unsupported content type")
        if not isinstance(encoded, str) or not encoded:
            raise CaptureValidationError(f"asset {role} is missing data_base64")
        try:
            data = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError) as error:
            raise CaptureValidationError(f"asset {role} is not valid base64") from error
        if not data or len(data) > MAX_ASSET_BYTES:
            raise CaptureValidationError(f"asset {role} exceeds the size limit")
        if content_type == "image/png" and not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise CaptureValidationError(f"asset {role} is not a PNG")
        if content_type == "image/jpeg" and not data.startswith(b"\xff\xd8"):
            raise CaptureValidationError(f"asset {role} is not a JPEG")
        decoded[role] = (content_type, data)
    return decoded


def canonicalize(record: dict[str, Any], capture_id: str) -> tuple[dict[str, Any], dict[str, tuple[str, bytes]]]:
    if not isinstance(record, dict):
        raise CaptureValidationError("request body must be an object")
    if not CAPTURE_ID.fullmatch(capture_id):
        raise CaptureValidationError("capture id contains unsupported characters")
    body_id = record.get("id")
    if body_id is not None and body_id != capture_id:
        raise CaptureValidationError("body id does not match the URL")
    schema_version = record.get("schema_version", record.get("schemaVersion", 1))
    if schema_version != 1:
        raise CaptureValidationError("unsupported schema_version")
    kind = record.get("kind", "capture")
    if kind not in VALID_KINDS:
        raise CaptureValidationError("unsupported capture kind")
    comment = _clean_text(record.get("comment", ""), maximum=20_000)
    source = _source_from_record(record)
    source_text = _clean_text(source.get("text", record.get("sourceText", "")))
    ocr_value = record.get("ocr", record.get("extracted_text", []))
    ocr_text = _flatten_ocr(ocr_value)
    # Absence of an explicit consent choice must never make a record remotely
    # AI-readable. Clients can opt individual records in after user consent.
    ai_access = record.get("ai_access", record.get("aiAccess", "local_only"))
    if ai_access not in VALID_AI_ACCESS:
        raise CaptureValidationError("unsupported ai_access policy")
    annotations = record.get("annotations", [])
    if not isinstance(annotations, list) or len(annotations) > 20_000:
        raise CaptureValidationError("annotations must be a bounded list")
    try:
        encoded_annotations = json.dumps(annotations, ensure_ascii=False)
    except (TypeError, ValueError) as error:
        raise CaptureValidationError("annotations must contain JSON values") from error
    if len(encoded_annotations.encode("utf-8")) > 2 * 1024 * 1024:
        raise CaptureValidationError("annotations are too large")
    source_json = json.dumps(source, ensure_ascii=False)
    source_app = _clean_text(
        source.get("app_name", source.get("app_id", source.get("package", ""))),
        maximum=500,
    )
    source_url = _clean_text(source.get("url", ""), maximum=8_192)
    assets = _decode_assets(record)

    payload = dict(record)
    payload.pop("assets", None)
    payload.pop("original_png_base64", None)
    payload.pop("annotated_png_base64", None)
    payload.update(
        {
            "schema_version": 1,
            "id": capture_id,
            "created_at": _created_at(record),
            "kind": kind,
            "comment": comment,
            "source": source,
            "ocr": ocr_value if isinstance(ocr_value, list) else [{"text": ocr_text}],
            "ai_access": ai_access,
        }
    )
    payload.pop("schemaVersion", None)
    payload.pop("createdAt", None)
    payload.pop("sourceType", None)
    payload.pop("sourceText", None)
    payload.pop("sourcePackage", None)
    if len(json.dumps(payload, ensure_ascii=False).encode("utf-8")) > MAX_JSON_BYTES:
        raise CaptureValidationError("capture metadata is too large")
    payload["_search"] = {
        "source_text": source_text,
        "ocr_text": ocr_text,
        "source_app": source_app,
        "source_url": source_url,
        "source_json": source_json,
    }
    return payload, assets


class CaptureStore:
    """SQLite metadata and content-addressed image storage.

    A store instance is safe to use from multiple request threads. Each method
    opens its own SQLite connection and serialises schema/file maintenance.
    """

    def __init__(self, root: str | os.PathLike[str]):
        self.root = Path(root).expanduser().resolve()
        self.db_path = self.root / "captures.sqlite3"
        self.blob_root = self.root / "blobs"
        self.root.mkdir(parents=True, exist_ok=True)
        self.blob_root.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._initialize()

    @contextmanager
    def connection(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.db_path, timeout=30, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA busy_timeout=30000")
        try:
            yield connection
        finally:
            connection.close()

    def _initialize(self) -> None:
        with self._lock, self.connection() as db:
            db.executescript(
                """
                PRAGMA journal_mode=WAL;
                PRAGMA synchronous=FULL;
                CREATE TABLE IF NOT EXISTS captures (
                    id TEXT PRIMARY KEY,
                    revision INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0,
                    kind TEXT NOT NULL,
                    comment TEXT NOT NULL,
                    source_text TEXT NOT NULL,
                    ocr_text TEXT NOT NULL,
                    source_app TEXT NOT NULL,
                    source_url TEXT NOT NULL,
                    ai_access TEXT NOT NULL,
                    payload_json TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS capture_assets (
                    capture_id TEXT NOT NULL REFERENCES captures(id) ON DELETE CASCADE,
                    role TEXT NOT NULL,
                    digest TEXT NOT NULL,
                    content_type TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    PRIMARY KEY (capture_id, role)
                );
                CREATE INDEX IF NOT EXISTS capture_assets_digest
                    ON capture_assets(digest);
                CREATE TABLE IF NOT EXISTS changes (
                    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                    capture_id TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    operation TEXT NOT NULL,
                    changed_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    at TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    action TEXT NOT NULL,
                    query TEXT NOT NULL,
                    record_ids TEXT NOT NULL
                );
                CREATE VIRTUAL TABLE IF NOT EXISTS capture_search USING fts5(
                    capture_id UNINDEXED,
                    comment,
                    source_text,
                    ocr_text,
                    source_app,
                    source_url,
                    tokenize='unicode61'
                );
                """
            )

    def _blob_path(self, digest: str, content_type: str) -> Path:
        return self.blob_root / digest[:2] / f"{digest}{CONTENT_EXTENSIONS[content_type]}"

    def _write_blob(self, content_type: str, data: bytes) -> tuple[str, Path]:
        digest = hashlib.sha256(data).hexdigest()
        destination = self._blob_path(digest, content_type)
        if destination.is_file():
            return digest, destination
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as temporary:
            temporary.write(data)
            temporary.flush()
            os.fsync(temporary.fileno())
            temporary_path = Path(temporary.name)
        try:
            os.replace(temporary_path, destination)
        finally:
            temporary_path.unlink(missing_ok=True)
        return digest, destination

    def put(self, capture_id: str, body: dict[str, Any], base_revision: int | None = None) -> dict[str, Any]:
        if base_revision is not None and (
            isinstance(base_revision, bool) or not isinstance(base_revision, int) or base_revision < 0
        ):
            raise CaptureValidationError("base_revision must be a non-negative integer")
        payload, assets = canonicalize(body, capture_id)
        search = payload.pop("_search")
        encoded_payload = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        written: dict[str, tuple[str, str, int]] = {}
        with self._lock:
            for role, (content_type, data) in assets.items():
                digest, _ = self._write_blob(content_type, data)
                written[role] = (digest, content_type, len(data))
            try:
                with self.connection() as db:
                    db.execute("BEGIN IMMEDIATE")
                    try:
                        existing = db.execute(
                            "SELECT revision, payload_json FROM captures WHERE id=?", (capture_id,)
                        ).fetchone()
                        if existing is None:
                            if base_revision not in (None, 0):
                                raise CaptureConflict(0)
                            revision = 1
                        else:
                            current = int(existing["revision"])
                            stored_assets = {
                                row["role"]: (
                                    row["digest"],
                                    row["content_type"],
                                    int(row["size"]),
                                )
                                for row in db.execute(
                                    "SELECT role, digest, content_type, size "
                                    "FROM capture_assets WHERE capture_id=?",
                                    (capture_id,),
                                )
                            }
                            same_assets = not written or stored_assets == written
                            # A retry after a lost response must not create a new revision.
                            if existing["payload_json"] == encoded_payload and same_assets:
                                db.rollback()
                                return self.get(capture_id, include_deleted=True)
                            if base_revision != current:
                                raise CaptureConflict(current)
                            revision = current + 1
                        now = utc_now()
                        db.execute(
                            """
                            INSERT INTO captures(
                                id, revision, created_at, updated_at, deleted, kind,
                                comment, source_text, ocr_text, source_app, source_url,
                                ai_access, payload_json
                            ) VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT(id) DO UPDATE SET
                                revision=excluded.revision,
                                created_at=excluded.created_at,
                                updated_at=excluded.updated_at,
                                deleted=0,
                                kind=excluded.kind,
                                comment=excluded.comment,
                                source_text=excluded.source_text,
                                ocr_text=excluded.ocr_text,
                                source_app=excluded.source_app,
                                source_url=excluded.source_url,
                                ai_access=excluded.ai_access,
                                payload_json=excluded.payload_json
                            """,
                            (
                                capture_id,
                                revision,
                                payload["created_at"],
                                now,
                                payload["kind"],
                                payload["comment"],
                                search["source_text"],
                                search["ocr_text"],
                                search["source_app"],
                                search["source_url"],
                                payload["ai_access"],
                                encoded_payload,
                            ),
                        )
                        if written:
                            db.execute("DELETE FROM capture_assets WHERE capture_id=?", (capture_id,))
                            db.executemany(
                                "INSERT INTO capture_assets(capture_id, role, digest, content_type, size) "
                                "VALUES (?, ?, ?, ?, ?)",
                                [
                                    (capture_id, role, digest, content_type, size)
                                    for role, (digest, content_type, size) in written.items()
                                ],
                            )
                        db.execute("DELETE FROM capture_search WHERE capture_id=?", (capture_id,))
                        db.execute(
                            "INSERT INTO capture_search(capture_id, comment, source_text, ocr_text, "
                            "source_app, source_url) VALUES (?, ?, ?, ?, ?, ?)",
                            (
                                capture_id,
                                payload["comment"],
                                search["source_text"],
                                search["ocr_text"],
                                search["source_app"],
                                search["source_url"],
                            ),
                        )
                        db.execute(
                            "INSERT INTO changes(capture_id, revision, operation, changed_at) "
                            "VALUES (?, ?, 'upsert', ?)",
                            (capture_id, revision, now),
                        )
                        db.commit()
                    except Exception:
                        db.rollback()
                        raise
            finally:
                # A failed optimistic update can leave an unreferenced content blob.
                self._remove_unreferenced_blobs()
        return self.get(capture_id, include_deleted=True)

    def _row_to_record(self, db: sqlite3.Connection, row: sqlite3.Row) -> dict[str, Any]:
        payload = json.loads(row["payload_json"])
        assets = db.execute(
            "SELECT role, digest, content_type, size FROM capture_assets WHERE capture_id=? ORDER BY role",
            (row["id"],),
        ).fetchall()
        payload.update(
            {
                "revision": int(row["revision"]),
                "updated_at": row["updated_at"],
                "deleted": bool(row["deleted"]),
                "assets": {
                    asset["role"]: {
                        "sha256": asset["digest"],
                        "content_type": asset["content_type"],
                        "size": int(asset["size"]),
                        "href": f"/v1/captures/{row['id']}/assets/{asset['role']}",
                    }
                    for asset in assets
                },
            }
        )
        return payload

    def get(self, capture_id: str, *, include_deleted: bool = False, ai_only: bool = False) -> dict[str, Any]:
        if not CAPTURE_ID.fullmatch(capture_id):
            raise CaptureNotFound(capture_id)
        clauses = ["id=?"]
        parameters: list[Any] = [capture_id]
        if not include_deleted:
            clauses.append("deleted=0")
        if ai_only:
            clauses.append("ai_access IN ('remote_no_memory','remote_memory')")
        with self.connection() as db:
            row = db.execute(
                f"SELECT * FROM captures WHERE {' AND '.join(clauses)}", parameters
            ).fetchone()
            if row is None:
                raise CaptureNotFound(capture_id)
            return self._row_to_record(db, row)

    def list(self, *, limit: int = 50, before: str | None = None, include_deleted: bool = False, ai_only: bool = False) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), 200))
        clauses: list[str] = []
        parameters: list[Any] = []
        if not include_deleted:
            clauses.append("deleted=0")
        if ai_only:
            clauses.append("ai_access IN ('remote_no_memory','remote_memory')")
        if before:
            clauses.append("created_at < ?")
            parameters.append(before)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        parameters.append(limit)
        with self.connection() as db:
            rows = db.execute(
                f"SELECT * FROM captures {where} ORDER BY created_at DESC, id DESC LIMIT ?",
                parameters,
            ).fetchall()
            return [self._row_to_record(db, row) for row in rows]

    @staticmethod
    def _fts_query(query: str) -> str:
        tokens = [token for token in re.split(r"\s+", query.strip()) if token]
        return " AND ".join('"' + token.replace('"', '""') + '"' for token in tokens[:20])

    def search(
        self,
        query: str,
        *,
        limit: int = 30,
        include_deleted: bool = False,
        ai_only: bool = False,
    ) -> list[dict[str, Any]]:
        query = _clean_text(query, maximum=1_000)
        if not query:
            return []
        limit = max(1, min(int(limit), 100))
        ai_clause = "AND c.ai_access IN ('remote_no_memory','remote_memory')" if ai_only else ""
        with self.connection() as db:
            rows: list[sqlite3.Row] = []
            # Deleted rows are intentionally absent from FTS. A first-party
            # trash search therefore uses the canonical columns below.
            if not include_deleted:
                rows = db.execute(
                    f"""
                    SELECT c.* FROM capture_search s
                    JOIN captures c ON c.id=s.capture_id
                    WHERE capture_search MATCH ? AND c.deleted=0 {ai_clause}
                    ORDER BY bm25(capture_search), c.created_at DESC LIMIT ?
                    """,
                    (self._fts_query(query), limit),
                ).fetchall()
            if not rows:
                like = f"%{query}%"
                deleted_clause = "" if include_deleted and not ai_only else "AND c.deleted=0"
                rows = db.execute(
                    f"""
                    SELECT * FROM captures c
                    WHERE 1=1 {deleted_clause} {ai_clause}
                      AND (comment LIKE ? OR source_text LIKE ? OR ocr_text LIKE ?
                           OR source_app LIKE ? OR source_url LIKE ?)
                    ORDER BY created_at DESC LIMIT ?
                    """,
                    (like, like, like, like, like, limit),
                ).fetchall()
            return [self._row_to_record(db, row) for row in rows]

    def soft_delete(self, capture_id: str, base_revision: int | None) -> dict[str, Any]:
        with self._lock, self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                row = db.execute(
                    "SELECT revision, deleted FROM captures WHERE id=?", (capture_id,)
                ).fetchone()
                if row is None:
                    raise CaptureNotFound(capture_id)
                current = int(row["revision"])
                if base_revision != current:
                    raise CaptureConflict(current)
                if bool(row["deleted"]):
                    raise CaptureValidationError("capture is already in the trash")
                revision = current + 1
                now = utc_now()
                db.execute(
                    "UPDATE captures SET deleted=1, revision=?, updated_at=? WHERE id=?",
                    (revision, now, capture_id),
                )
                db.execute("DELETE FROM capture_search WHERE capture_id=?", (capture_id,))
                db.execute(
                    "INSERT INTO changes(capture_id, revision, operation, changed_at) VALUES (?, ?, 'delete', ?)",
                    (capture_id, revision, now),
                )
                db.commit()
            except Exception:
                db.rollback()
                raise
        return self.get(capture_id, include_deleted=True)

    def restore(self, capture_id: str, base_revision: int | None) -> dict[str, Any]:
        with self._lock, self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                row = db.execute("SELECT * FROM captures WHERE id=?", (capture_id,)).fetchone()
                if row is None:
                    raise CaptureNotFound(capture_id)
                current = int(row["revision"])
                if base_revision != current:
                    raise CaptureConflict(current)
                if not bool(row["deleted"]):
                    raise CaptureValidationError("capture is not in the trash")
                revision = current + 1
                now = utc_now()
                db.execute(
                    "UPDATE captures SET deleted=0, revision=?, updated_at=? WHERE id=?",
                    (revision, now, capture_id),
                )
                db.execute(
                    "INSERT INTO capture_search(capture_id, comment, source_text, ocr_text, source_app, source_url) VALUES (?, ?, ?, ?, ?, ?)",
                    (
                        capture_id,
                        row["comment"],
                        row["source_text"],
                        row["ocr_text"],
                        row["source_app"],
                        row["source_url"],
                    ),
                )
                db.execute(
                    "INSERT INTO changes(capture_id, revision, operation, changed_at) VALUES (?, ?, 'restore', ?)",
                    (capture_id, revision, now),
                )
                db.commit()
            except Exception:
                db.rollback()
                raise
        return self.get(capture_id, include_deleted=True)

    def purge(self, capture_id: str) -> None:
        with self._lock, self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                row = db.execute(
                    "SELECT revision, deleted FROM captures WHERE id=?", (capture_id,)
                ).fetchone()
                if row is None:
                    raise CaptureNotFound(capture_id)
                if not bool(row["deleted"]):
                    raise CaptureValidationError("capture must be in the trash before purge")
                db.execute("DELETE FROM capture_search WHERE capture_id=?", (capture_id,))
                db.execute("DELETE FROM captures WHERE id=?", (capture_id,))
                db.execute(
                    "INSERT INTO changes(capture_id, revision, operation, changed_at) VALUES (?, ?, 'purge', ?)",
                    (capture_id, int(row["revision"]) + 1, utc_now()),
                )
                db.commit()
            except Exception:
                db.rollback()
                raise
            self._remove_unreferenced_blobs()

    def changes(self, after: int = 0, limit: int = 200, *, ai_only: bool = False) -> dict[str, Any]:
        if ai_only:
            # Tombstones and purges cannot be policy-filtered after their source
            # row is gone. Refuse this data-layer mode as a defense-in-depth
            # invariant; AI callers use list/search/timeline instead.
            raise CaptureValidationError("AI access to the synchronization change feed is disabled")
        limit = max(1, min(int(limit), 500))
        with self.connection() as db:
            rows = db.execute(
                "SELECT * FROM changes WHERE sequence>? ORDER BY sequence LIMIT ?", (after, limit)
            ).fetchall()
            result: list[dict[str, Any]] = []
            for row in rows:
                item = dict(row)
                if row["operation"] in {"upsert", "restore"}:
                    try:
                        item["record"] = self.get(
                            row["capture_id"], include_deleted=True, ai_only=False
                        )
                    except CaptureNotFound:
                        pass
                result.append(item)
            next_sequence = int(rows[-1]["sequence"]) if rows else after
            return {"changes": result, "next_sequence": next_sequence, "has_more": len(rows) == limit}

    def asset(
        self,
        capture_id: str,
        role: str,
        *,
        include_deleted: bool = False,
        ai_only: bool = False,
    ) -> tuple[Path, str, str]:
        if role not in VALID_ASSET_ROLES:
            raise CaptureNotFound(role)
        self.get(capture_id, include_deleted=include_deleted, ai_only=ai_only)
        with self.connection() as db:
            row = db.execute(
                "SELECT digest, content_type FROM capture_assets WHERE capture_id=? AND role=?",
                (capture_id, role),
            ).fetchone()
            if row is None:
                raise CaptureNotFound(role)
            path = self._blob_path(row["digest"], row["content_type"])
            if not path.is_file():
                raise CaptureNotFound(role)
            return path, row["content_type"], row["digest"]

    def audit(self, actor: str, action: str, query: str, record_ids: list[str]) -> None:
        with self.connection() as db:
            db.execute(
                "INSERT INTO audit(at, actor, action, query, record_ids) VALUES (?, ?, ?, ?, ?)",
                (
                    utc_now(),
                    _clean_text(actor, maximum=200),
                    _clean_text(action, maximum=100),
                    _clean_text(query, maximum=2_000),
                    json.dumps(record_ids[:500], separators=(",", ":")),
                ),
            )

    def export_zip(self, destination: str | os.PathLike[str], *, include_deleted: bool = False) -> Path:
        destination = Path(destination)
        # Export all pages, not just the first list page.
        with self.connection() as db:
            clauses = "" if include_deleted else "WHERE deleted=0"
            rows = db.execute(f"SELECT * FROM captures {clauses} ORDER BY created_at, id").fetchall()
            with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                manifest: list[dict[str, Any]] = []
                for row in rows:
                    record = self._row_to_record(db, row)
                    manifest.append(record)
                    capture_id = record["id"]
                    archive.writestr(
                        f"records/{capture_id}.json",
                        json.dumps(record, ensure_ascii=False, indent=2),
                    )
                    for role, asset in record["assets"].items():
                        path = self._blob_path(asset["sha256"], asset["content_type"])
                        if not path.is_file():
                            raise CaptureNotFound(f"missing asset {capture_id}:{role}")
                        archive.write(path, f"assets/{capture_id}/{role}{path.suffix}")
                archive.writestr(
                    "manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2)
                )
        return destination

    def _remove_unreferenced_blobs(self) -> None:
        with self.connection() as db:
            referenced = {
                row["digest"] for row in db.execute("SELECT DISTINCT digest FROM capture_assets")
            }
        for path in self.blob_root.glob("*/*"):
            if path.is_file() and path.stem not in referenced:
                path.unlink(missing_ok=True)
        for directory in self.blob_root.iterdir():
            if directory.is_dir():
                try:
                    directory.rmdir()
                except OSError:
                    pass

    def close_for_test_cleanup(self) -> None:
        """No persistent connections are held; this documents cleanup intent."""


def minimal_png() -> bytes:
    """One-pixel PNG used by tests and protocol smoke checks."""
    return base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )
