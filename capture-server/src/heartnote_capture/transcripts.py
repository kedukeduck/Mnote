"""Account-only adapter to the isolated local media-transcription service.

The application never receives the core bearer token and may never choose an
upstream, owner, or proxy path. Admission reservations survive server restarts;
an uncertain POST is not retried or refunded (the core may already have accepted).
"""
from __future__ import annotations

import contextlib
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import re
import secrets
import socket
import sqlite3
import threading
import time
from urllib.parse import urlsplit

MAX_BODY = 16 * 1024
MAX_RESPONSE = 8 * 1024 * 1024
UPSTREAM_HOST, UPSTREAM_PORT = "127.0.0.1", 8791
UPSTREAM_TIMEOUT = 25
RETENTION_SECONDS = 24 * 3600
POLL_SECONDS = 15
MAX_HISTORY_ROWS = 5000
MAX_HISTORY_BYTES = 100 * 1024 * 1024
MAX_GLOBAL_HISTORY_BYTES = 512 * 1024 * 1024
MAX_ARCHIVED_JOB_BYTES = 1024 * 1024
MAX_HISTORY_PAGE_BYTES = 1536 * 1024
MAX_TRANSCRIPT_CHARS = 100000
JOB_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
CAPTURE_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")
SHARE_URL_RE = re.compile(r"https://[^\s<>\"'，。！；、（）【】\[\]`]+", re.I)
MESSAGES = {
    "not_configured": "当前服务器尚未启用视频文字稿解析。",
    "not_found": "文字稿任务不存在、已过期或不属于当前账号。",
    "invalid_request": "请求格式不正确。",
    "request_conflict": "该提交标识已用于另一份内容，请重新发起解析。",
    "account_busy": "当前账号已有进行中的任务，请等待完成或取消后再提交。",
    "account_limit": "每个账号每小时最多提交 3 次，请稍后再试。",
    "daily_limit": "文字稿服务今日已达到 40 次提交上限，请次日再试。",
    "submission_uncertain": "上次提交结果尚未确认，请勿重复提交；限制将在 24 小时内解除。",
    "upstream_unavailable": "文字稿解析暂时不可用，请稍后重试。",
    "storage_unavailable": "文字稿任务状态暂时不可用，请稍后重试。",
    "history_full": "解析历史存储已满，请删除不再需要的任务后重试。",
    "task_expired": "解析任务已过期，未取得完整文字稿，请重新发起解析。",
    "result_too_large": "文字稿超过可保存的大小，已有记录没有变化。",
}


class TranscriptError(Exception):
    def __init__(self, code, status=400, retry_after=None):
        self.code, self.status, self.retry_after = code, status, retry_after
        super().__init__(MESSAGES[code])


class ExistingRequest(Exception):
    def __init__(self, job_id):
        self.job_id = job_id


class Admissions:
    """Private quotas, idempotency tombstones and durable transcript history."""
    def __init__(self, root: Path):
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path, self.lock = root / "admissions.sqlite3", threading.RLock()
        with self.db() as db:
            db.execute("CREATE TABLE IF NOT EXISTS attempts (id TEXT PRIMARY KEY, owner TEXT NOT NULL, created REAL NOT NULL, job_id TEXT, status TEXT NOT NULL)")
            db.execute("CREATE INDEX IF NOT EXISTS transcript_owner_time ON attempts(owner,created)")
            db.execute("CREATE INDEX IF NOT EXISTS transcript_job ON attempts(owner,job_id)")
            db.execute("CREATE TABLE IF NOT EXISTS active (owner TEXT PRIMARY KEY, attempt TEXT NOT NULL)")
            # Minimal permanent idempotency tombstones: no URL, declaration or text.
            # The global 40/day admission ceiling bounds growth to ~15k rows/year.
            db.execute("CREATE TABLE IF NOT EXISTS requests (owner TEXT NOT NULL, request_id TEXT NOT NULL, fingerprint TEXT NOT NULL, attempt TEXT NOT NULL, job_id TEXT, status TEXT NOT NULL, PRIMARY KEY(owner,request_id))")
            # Result/history storage shares the quota database's atomic backup and
            # privacy permissions. No core media URL, credential or audio is kept.
            db.execute("CREATE TABLE IF NOT EXISTS catalog (owner TEXT NOT NULL, job_id TEXT NOT NULL, metadata TEXT NOT NULL, payload TEXT NOT NULL, status TEXT NOT NULL, created REAL NOT NULL, updated REAL NOT NULL, next_poll REAL NOT NULL, poll_failures INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(owner,job_id))")
            db.execute("CREATE INDEX IF NOT EXISTS transcript_catalog_owner ON catalog(owner,created DESC,job_id)")
            db.execute("CREATE INDEX IF NOT EXISTS transcript_catalog_poll ON catalog(deleted,status,next_poll)")
            db.execute("UPDATE attempts SET status='uncertain' WHERE status='reserved'")
            db.execute("UPDATE requests SET status='uncertain' WHERE status='reserved'")
        os.chmod(self.path, 0o600)

    @contextlib.contextmanager
    def db(self):
        with self.lock:
            db = sqlite3.connect(self.path, timeout=10)
            try:
                db.execute("PRAGMA secure_delete=ON")
                db.execute("BEGIN IMMEDIATE")
                yield db
                db.commit()
            except Exception:
                db.rollback()
                raise
            finally:
                db.close()

    @staticmethod
    def prune(db, now):
        db.execute("UPDATE requests SET status='expired' WHERE status IN ('reserved','uncertain') AND attempt IN (SELECT id FROM attempts WHERE created<=?)", (now - RETENTION_SECONDS,))
        db.execute("DELETE FROM attempts WHERE created<=?", (now - RETENTION_SECONDS,))
        db.execute("DELETE FROM active WHERE attempt NOT IN (SELECT id FROM attempts)")

    def current(self, owner):
        with self.db() as db:
            self.prune(db, time.time())
            return db.execute("SELECT a.job_id FROM active v JOIN attempts a ON v.attempt=a.id WHERE v.owner=?", (owner,)).fetchone()

    @staticmethod
    def _check_request(db, owner, request_id, fingerprint):
        if request_id is None:
            return
        row = db.execute("SELECT fingerprint,job_id,status FROM requests WHERE owner=? AND request_id=?", (owner, request_id)).fetchone()
        if row:
            if row[0] != fingerprint:
                raise TranscriptError("request_conflict", 409)
            if row[2] in ("expired", "deleted"):
                raise TranscriptError("not_found", 404)
            if row[2] == "accepted" and row[1]:
                raise ExistingRequest(row[1])
            raise TranscriptError("submission_uncertain", 429, 30)

    def check_request(self, owner, request_id, fingerprint):
        with self.db() as db:
            self.prune(db, time.time())
            self._check_request(db, owner, request_id, fingerprint)

    def reserve(self, owner, request_id=None, fingerprint=None):
        now = time.time()
        with self.db() as db:
            self.prune(db, now)
            self._check_request(db, owner, request_id, fingerprint)
            # Never silently evict existing history. Reserve headroom for one
            # maximum-sized terminal result per in-flight job, including owners
            # other than this one, before submitting anything to the core.
            count, used = db.execute("SELECT COUNT(*),COALESCE(SUM(length(CAST(payload AS BLOB))+length(CAST(metadata AS BLOB))),0) FROM catalog WHERE owner=? AND deleted=0", (owner,)).fetchone()
            pending = db.execute("SELECT COUNT(*) FROM catalog WHERE owner=? AND deleted=0 AND status IN ('queued','processing')", (owner,)).fetchone()[0]
            total, all_pending = db.execute("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))+length(CAST(metadata AS BLOB))),0),COALESCE(SUM(CASE WHEN status IN ('queued','processing') AND deleted=0 THEN 1 ELSE 0 END),0) FROM catalog").fetchone()
            outstanding = db.execute("SELECT a.owner FROM attempts a LEFT JOIN catalog c ON c.owner=a.owner AND c.job_id=a.job_id WHERE a.status IN ('reserved','uncertain','accepted') AND c.job_id IS NULL").fetchall()
            pending += sum(row[0] == owner for row in outstanding)
            all_pending += len(outstanding)
            reserved_size = MAX_ARCHIVED_JOB_BYTES + MAX_BODY * 2
            if count >= MAX_HISTORY_ROWS or used + (pending + 1) * reserved_size > MAX_HISTORY_BYTES or total + (all_pending + 1) * reserved_size > MAX_GLOBAL_HISTORY_BYTES:
                raise TranscriptError("history_full", 409)
            row = db.execute("SELECT a.status,a.created FROM active v JOIN attempts a ON v.attempt=a.id WHERE v.owner=?", (owner,)).fetchone()
            if row:
                uncertain = row[0] == "uncertain"
                raise TranscriptError("submission_uncertain" if uncertain else "account_busy", 429,
                                      max(1, math.ceil(row[1] + RETENTION_SECONDS - now)) if uncertain else 30)
            day = math.floor(now / RETENTION_SECONDS) * RETENTION_SECONDS
            if db.execute("SELECT COUNT(*) FROM attempts WHERE created>=?", (day,)).fetchone()[0] >= 40:
                raise TranscriptError("daily_limit", 429, max(1, math.ceil(day + RETENTION_SECONDS - now)))
            rows = db.execute("SELECT created FROM attempts WHERE owner=? AND created>? ORDER BY created", (owner, now - 3600)).fetchall()
            if len(rows) >= 3:
                raise TranscriptError("account_limit", 429, max(1, math.ceil(rows[0][0] + 3600 - now)))
            ident = secrets.token_hex(16)
            db.execute("INSERT INTO attempts VALUES (?,?,?,NULL,'reserved')", (ident, owner, now))
            db.execute("INSERT INTO active VALUES (?,?)", (owner, ident))
            if request_id is not None:
                db.execute("INSERT INTO requests VALUES (?,?,?,?,NULL,'reserved')", (owner, request_id, fingerprint, ident))
            return ident

    def accepted(self, reservation, ident, value=None, metadata=None):
        with self.db() as db:
            db.execute("UPDATE attempts SET job_id=?,status='accepted' WHERE id=?", (ident, reservation))
            db.execute("UPDATE requests SET job_id=?,status='accepted' WHERE attempt=?", (ident, reservation))
            if value is not None:
                owner = db.execute("SELECT owner FROM attempts WHERE id=?", (reservation,)).fetchone()
                if owner is None:
                    raise TranscriptError("storage_unavailable", 503)
                self._remember(db, owner[0], value, metadata)

    def uncertain(self, reservation):
        with self.db() as db:
            db.execute("UPDATE attempts SET status='uncertain' WHERE id=? AND status='reserved'", (reservation,))
            db.execute("UPDATE requests SET status='uncertain' WHERE attempt=? AND status='reserved'", (reservation,))

    def rejected(self, reservation):
        with self.db() as db:
            db.execute("DELETE FROM active WHERE attempt=?", (reservation,))
            db.execute("DELETE FROM attempts WHERE id=?", (reservation,))
            db.execute("DELETE FROM requests WHERE attempt=?", (reservation,))

    def owns(self, owner, ident):
        with self.db() as db:
            self.prune(db, time.time())
            return db.execute("SELECT 1 FROM attempts WHERE owner=? AND job_id=? AND status='accepted'", (owner, ident)).fetchone() is not None

    def finished(self, owner, ident):
        with self.db() as db:
            db.execute("DELETE FROM active WHERE owner=? AND attempt IN (SELECT id FROM attempts WHERE owner=? AND job_id=?)", (owner, owner, ident))

    def remember(self, owner, value, metadata=None):
        with self.db() as db:
            self._remember(db, owner, value, metadata)

    def _remember(self, db, owner, value, metadata=None):
        now = time.time()
        encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        if len(encoded.encode("utf-8")) > MAX_ARCHIVED_JOB_BYTES or not isinstance(value.get("text", ""), str) or len(value.get("text", "")) > MAX_TRANSCRIPT_CHARS:
            value = {key: item for key, item in value.items() if key not in ("text", "segments")}
            value.update(status="failed", stage="failed", text="", segments=[], error={"code": "result_too_large", "message": MESSAGES["result_too_large"]})
            encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        ident = value["id"]
        row = db.execute("SELECT deleted,status FROM catalog WHERE owner=? AND job_id=?", (owner, ident)).fetchone()
        if row and row[0]:
            return
        if row and row[1] in ("completed", "failed"):
            # Concurrent GETs/collector responses may arrive out of order.
            # A terminal result is immutable, including its full text.
            return
        if row is None:
            db.execute("INSERT INTO catalog(owner,job_id,metadata,payload,status,created,updated,next_poll) VALUES (?,?,?,?,?,?,?,?)", (owner, ident, json.dumps(metadata or {}, ensure_ascii=False, separators=(",", ":")), encoded, value["status"], now, now, now + POLL_SECONDS))
        else:
            db.execute("UPDATE catalog SET payload=?,status=?,updated=?,next_poll=?,poll_failures=0 WHERE owner=? AND job_id=? AND deleted=0", (encoded, value["status"], now, now + POLL_SECONDS, owner, ident))
        if value["status"] in ("completed", "failed"):
            db.execute("DELETE FROM active WHERE owner=? AND attempt IN (SELECT id FROM attempts WHERE owner=? AND job_id=?)", (owner, owner, ident))

    @staticmethod
    def _catalog_job(row, summary=False):
        metadata, value, created, updated = json.loads(row[0]), json.loads(row[1]), row[2], row[3]
        result = dict(value)
        result.update(metadata)
        result.setdefault("created_at", created)
        result.setdefault("updated_at", updated)
        if summary:
            result.pop("text", None)
            result.pop("segments", None)
            result["has_text"] = bool(value.get("text"))
        return result

    def cached(self, owner, ident):
        with self.db() as db:
            row = db.execute("SELECT metadata,payload,created,updated,deleted FROM catalog WHERE owner=? AND job_id=?", (owner, ident)).fetchone()
        return (None if row is None else (False if row[4] else self._catalog_job(row)))

    def history(self, owner, limit, offset):
        jobs, size, consumed = [], 0, 0
        with self.db() as db:
            # Stream rows rather than materializing up to fifty full transcripts
            # merely to make summaries. The response also has a byte budget.
            rows = db.execute("SELECT metadata,payload,created,updated FROM catalog WHERE owner=? AND deleted=0 ORDER BY created DESC,job_id DESC LIMIT ? OFFSET ?", (owner, limit + 1, offset))
            for row in rows:
                if consumed >= limit:
                    break
                item = self._catalog_job(row, True)
                encoded_size = len(json.dumps(item, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
                if jobs and size + encoded_size > MAX_HISTORY_PAGE_BYTES:
                    break
                jobs.append(item)
                size += encoded_size
                consumed += 1
            else:
                return {"jobs": jobs, "has_more": False, "next_offset": None}
        return {"jobs": jobs, "has_more": True, "next_offset": offset + consumed}

    def associate(self, owner, ident, capture_id):
        with self.db() as db:
            row = db.execute("SELECT metadata FROM catalog WHERE owner=? AND job_id=? AND deleted=0", (owner, ident)).fetchone()
            if row is None:
                raise TranscriptError("not_found", 404)
            metadata = json.loads(row[0])
            metadata["capture_id"] = capture_id
            db.execute("UPDATE catalog SET metadata=? WHERE owner=? AND job_id=? AND deleted=0", (json.dumps(metadata, ensure_ascii=False, separators=(",", ":")), owner, ident))
            return self._catalog_job(db.execute("SELECT metadata,payload,created,updated FROM catalog WHERE owner=? AND job_id=?", (owner, ident)).fetchone())

    def remove(self, owner, ident):
        with self.db() as db:
            db.execute("UPDATE catalog SET metadata='{}',payload='{}',deleted=1,next_poll=?,updated=? WHERE owner=? AND job_id=?", (time.time(), time.time(), owner, ident))
            db.execute("UPDATE requests SET status='deleted' WHERE owner=? AND job_id=?", (owner, ident))
            db.execute("UPDATE attempts SET status='deleted' WHERE owner=? AND job_id=?", (owner, ident))
            db.execute("DELETE FROM active WHERE owner=? AND attempt IN (SELECT id FROM attempts WHERE owner=? AND job_id=?)", (owner, owner, ident))

    def due(self, limit=8):
        with self.db() as db:
            return db.execute("SELECT owner,job_id,deleted,created FROM catalog WHERE next_poll<=? AND (deleted=1 OR status IN ('queued','processing')) ORDER BY next_poll LIMIT ?", (time.time(), limit)).fetchall()

    def poll_error(self, owner, ident):
        with self.db() as db:
            row = db.execute("SELECT poll_failures FROM catalog WHERE owner=? AND job_id=?", (owner, ident)).fetchone()
            if row:
                failures = min(8, row[0] + 1)
                db.execute("UPDATE catalog SET poll_failures=?,next_poll=? WHERE owner=? AND job_id=?", (failures, time.time() + min(300, POLL_SECONDS * (2 ** (failures - 1))), owner, ident))

    def cleanup_done(self, owner, ident):
        with self.db() as db:
            db.execute("DELETE FROM catalog WHERE owner=? AND job_id=? AND deleted=1", (owner, ident))


class CoreClient:
    def __init__(self, token):
        self.token = token

    def request(self, method, path, owner, body=None):
        # HTTPConnection never follows Location. No client headers are forwarded.
        connection = http.client.HTTPConnection(UPSTREAM_HOST, UPSTREAM_PORT, timeout=UPSTREAM_TIMEOUT)
        original_socket = [None]
        expired = threading.Event()
        def abort():
            expired.set()
            sock = original_socket[0] or connection.sock
            if sock:
                with contextlib.suppress(OSError):
                    sock.shutdown(socket.SHUT_RDWR)
            connection.close()
        timer = threading.Timer(UPSTREAM_TIMEOUT, abort)
        timer.daemon = True
        timer.start()
        try:
            encoded = None if body is None else json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            connection.request(method, path, body=encoded, headers={
                "Authorization": "Bearer " + self.token, "X-Owner-Id": owner,
                "Content-Type": "application/json", "Accept-Encoding": "identity",
            })
            original_socket[0] = connection.sock
            response = connection.getresponse()
            if 300 <= response.status < 400 or response.status in (401, 403) or response.status >= 500:
                raise TranscriptError("upstream_unavailable", 503)
            if response.getheader("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json":
                raise TranscriptError("upstream_unavailable", 502)
            length = response.getheader("Content-Length")
            if length is not None and (not re.fullmatch(r"[0-9]{1,10}", length) or int(length) > MAX_RESPONSE):
                raise TranscriptError("upstream_unavailable", 502)
            raw = response.read(MAX_RESPONSE + 1)
            if expired.is_set() or len(raw) > MAX_RESPONSE or (length is not None and len(raw) != int(length)):
                raise TranscriptError("upstream_unavailable", 502)
            payload = json.loads(raw)
            if not isinstance(payload, dict):
                raise ValueError()
            return response.status, payload
        except (OSError, http.client.HTTPException, ValueError, UnicodeError, RecursionError):
            raise TranscriptError("upstream_unavailable", 503) from None
        finally:
            timer.cancel()
            connection.close()


def public_payload(status, payload):
    """Only contract fields leave the core boundary; private URLs/keys cannot."""
    if status >= 400:
        error = payload.get("error")
        if not isinstance(error, dict) or not isinstance(error.get("code"), str) or not isinstance(error.get("message"), str):
            raise TranscriptError("upstream_unavailable", 502)
        return {"error": {"code": error["code"][:100], "message": error["message"][:2000]}}
    job = payload.get("job")
    if not isinstance(job, dict) or not isinstance(job.get("id"), str) or not JOB_RE.fullmatch(job["id"]):
        raise TranscriptError("upstream_unavailable", 502)
    result = {"id": job["id"], "status": job.get("status")}
    if result.get("status") not in ("queued", "processing", "completed", "failed"):
        raise TranscriptError("upstream_unavailable", 502)
    for key in ("stage", "text"):
        if key in job:
            if not isinstance(job[key], str):
                raise TranscriptError("upstream_unavailable", 502)
            result[key] = job[key] if key == "text" else job[key][:100]
    for key in ("progress", "created_at", "updated_at", "expires_at"):
        if key in job:
            if isinstance(job[key], bool) or not isinstance(job[key], (int, float)) or not math.isfinite(job[key]) or job[key] < 0:
                raise TranscriptError("upstream_unavailable", 502)
            result[key] = min(100, job[key]) if key == "progress" else job[key]
    source = job.get("source", {})
    result["source"] = {}
    if not isinstance(source, dict):
        raise TranscriptError("upstream_unavailable", 502)
    for key, limit in (("platform", 64), ("host", 253), ("title", 1000)):
        if key in source:
            if not isinstance(source[key], str):
                raise TranscriptError("upstream_unavailable", 502)
            result["source"][key] = source[key][:limit]
    duration = source.get("duration_seconds")
    if duration is not None:
        if isinstance(duration, bool) or not isinstance(duration, (int, float)) or not math.isfinite(duration) or duration < 0:
            raise TranscriptError("upstream_unavailable", 502)
        result["source"]["duration_seconds"] = duration
    rights = job.get("rights", {})
    result["rights"] = {"verified": False}
    if not isinstance(rights, dict):
        raise TranscriptError("upstream_unavailable", 502)
    for key, limit in (("basis", 64), ("note", 1000), ("agreement_version", 100)):
        if key in rights:
            if not isinstance(rights[key], str):
                raise TranscriptError("upstream_unavailable", 502)
            result["rights"][key] = rights[key][:limit]
    if "consented_at" in rights:
        consented_at = rights["consented_at"]
        if isinstance(consented_at, bool) or not isinstance(consented_at, (int, float)) or not math.isfinite(consented_at):
            raise TranscriptError("upstream_unavailable", 502)
        result["rights"]["consented_at"] = consented_at
    segments = job.get("segments", [])
    if not isinstance(segments, list) or len(segments) > 20000:
        raise TranscriptError("upstream_unavailable", 502)
    result["segments"] = []
    for item in segments:
        if not isinstance(item, dict) or not isinstance(item.get("text"), str):
            raise TranscriptError("upstream_unavailable", 502)
        segment = {"text": item["text"]}
        for key in ("start", "end"):
            if key in item:
                if isinstance(item[key], bool) or not isinstance(item[key], (int, float)) or not math.isfinite(item[key]) or item[key] < 0:
                    raise TranscriptError("upstream_unavailable", 502)
                segment[key] = item[key]
        result["segments"].append(segment)
    result["error"] = None
    if job.get("error") is not None:
        result["error"] = public_payload(400, {"error": job["error"]})["error"]
    return {"job": result}


class Transcripts:
    def __init__(self, root: Path, token=None):
        supplied = os.environ.get("HEARTNOTE_CAPTURE_TRANSCRIPT_TOKEN", "") if token is None else token
        # Missing/invalid optional configuration must not stop ordinary sync.
        self.configured = isinstance(supplied, str) and bool(re.fullmatch(r"[\x21-\x7e]{32,512}", supplied))
        self.client = CoreClient(supplied) if self.configured else None
        self.root, self._admissions, self._lock = Path(root) / "transcripts", None, threading.Lock()
        self._stop, self._thread = threading.Event(), None

    def start(self):
        """A single bounded collector belongs to the HTTP server lifecycle."""
        if not self.configured:
            return
        with self._lock:
            if self._thread is not None:
                return
            self._thread = threading.Thread(target=self._collect, name="transcript-results", daemon=True)
            self._thread.start()

    def close(self):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=UPSTREAM_TIMEOUT + 2)

    def _collect(self):
        # Delay also keeps optional transcription failures off the main server's
        # startup path. Existing sync remains independent if storage is absent.
        while not self._stop.wait(POLL_SECONDS):
            try:
                self.collect_once()
            except (sqlite3.Error, OSError, TranscriptError, ValueError, TypeError):
                # No raw source URL, result text, key or account ID in logs.
                continue

    def collect_once(self):
        if not self.configured:
            return
        quotas = self.admissions
        # Recover accepted pre-catalog jobs too; never POST an uncertain attempt.
        with quotas.db() as db:
            legacy = db.execute("SELECT a.owner,a.job_id,a.created,(SELECT r.request_id FROM requests r WHERE r.owner=a.owner AND r.job_id=a.job_id LIMIT 1) FROM attempts a LEFT JOIN catalog c ON c.owner=a.owner AND c.job_id=a.job_id WHERE a.status='accepted' AND a.job_id IS NOT NULL AND c.job_id IS NULL AND a.created>? LIMIT 40", (time.time() - RETENTION_SECONDS,)).fetchall()
            for owner, ident, created, request_id in legacy:
                db.execute("INSERT OR IGNORE INTO catalog(owner,job_id,metadata,payload,status,created,updated,next_poll) VALUES (?,?,?,?,?,?,?,0)", (owner, ident, json.dumps({"client_request_id": request_id or "", "capture_id": "", "source_url": "", "share_text": ""}), json.dumps({"id": ident, "status": "queued", "stage": "recovering", "progress": 0, "text": "", "segments": [], "error": None, "source": {}}), "queued", created, time.time()))
        for owner, ident, deleted, created in quotas.due():
            if self._stop.is_set():
                return
            if deleted:
                try:
                    status, result = self.client.request("DELETE", "/v1/jobs/" + ident, owner)
                    if status == 404 or (status == 200 and result.get("deleted") is True and result.get("id") == ident):
                        quotas.cleanup_done(owner, ident)
                    else:
                        quotas.poll_error(owner, ident)
                except TranscriptError:
                    if created + RETENTION_SECONDS < time.time():
                        quotas.cleanup_done(owner, ident)
                    else:
                        quotas.poll_error(owner, ident)
                continue
            try:
                self._refresh(owner, ident)
            except (TranscriptError, ValueError, TypeError):
                quotas.poll_error(owner, ident)

    def _refresh(self, owner, ident):
        cached = self.admissions.cached(owner, ident)
        expiry = cached.get("expires_at", cached.get("created_at", time.time()) + RETENTION_SECONDS) if cached else None
        if cached and cached["status"] not in ("completed", "failed") and isinstance(expiry, (int, float)) and expiry <= time.time():
            return self._expired(owner, ident, cached)
        status, payload = self.client.request("GET", "/v1/jobs/" + ident, owner)
        if status == 404:
            cached = self.admissions.cached(owner, ident)
            if cached:
                return self._expired(owner, ident, cached)
            self.admissions.finished(owner, ident)
            return 404, public_payload(status, payload)
        clean = public_payload(status, payload)
        if status != 200 or clean["job"]["id"] != ident:
            raise TranscriptError("upstream_unavailable", 502)
        self.admissions.remember(owner, clean["job"])
        cached = self.admissions.cached(owner, ident)
        if cached is False:
            raise TranscriptError("not_found", 404)
        return 200, {"job": cached}

    def _expired(self, owner, ident, cached):
        cached = {key: value for key, value in cached.items() if key not in ("share_text", "source_url", "capture_id", "client_request_id")}
        cached.update(status="failed", stage="failed", progress=100, text="", segments=[], updated_at=time.time(),
                      error={"code": "task_expired", "message": MESSAGES["task_expired"]})
        self.admissions.remember(owner, cached)
        return 200, {"job": self.admissions.cached(owner, ident)}

    @staticmethod
    def _metadata(body):
        share = body.get("share_text", "")
        metadata = {"client_request_id": body.get("client_request_id") or "", "capture_id": body.get("capture_id") or "",
                    "share_text": share if isinstance(share, str) and len(share) <= 8192 else "", "source_url": ""}
        # Original user-supplied public link only. This is not a core resolved
        # media URL and is never used as a fetch/proxy target by this adapter.
        stripped = metadata["share_text"].strip()
        naked = bool(re.match(r"https://", stripped, re.I)) and not re.search(r"\s", stripped)
        candidates = [stripped] if naked and SHARE_URL_RE.fullmatch(stripped) else ([] if naked else SHARE_URL_RE.findall(stripped))
        urls = set()
        for candidate in candidates:
            if not naked:
                candidate = candidate.rstrip(".,;!?】）")
                unmatched = candidate.count(")") - candidate.count("(")
                while unmatched > 0 and candidate.endswith(")"):
                    candidate = candidate[:-1].rstrip(".,;!?】）")
                    unmatched -= 1
            urls.add(candidate)
        if len(urls) == 1:
            url = next(iter(urls))
            with contextlib.suppress(ValueError):
                parts = urlsplit(url)
                if parts.hostname and not parts.username and not parts.password:
                    metadata["source_url"] = url
        return metadata

    def history(self, account_id, limit=50, offset=0):
        if isinstance(limit, bool) or isinstance(offset, bool) or not isinstance(limit, int) or not isinstance(offset, int) or not 1 <= limit <= 50 or not 0 <= offset <= MAX_HISTORY_ROWS:
            raise TranscriptError("invalid_request")
        return self.admissions.history(self.owner(account_id), limit, offset)

    def associate(self, account_id, ident, body):
        if not JOB_RE.fullmatch(ident) or not isinstance(body, dict) or set(body) != {"capture_id"} or not isinstance(body["capture_id"], str) or not CAPTURE_RE.fullmatch(body["capture_id"]) or ".." in body["capture_id"]:
            raise TranscriptError("invalid_request")
        owner = self.owner(account_id)
        return {"job": self.admissions.associate(owner, ident, body["capture_id"])}

    @property
    def admissions(self):
        with self._lock:
            if self._admissions is None:
                self._admissions = Admissions(self.root)
            return self._admissions

    @staticmethod
    def owner(account_id):
        # Namespace separates app accounts from public-demo visitors and other consumers.
        return hashlib.sha256(("mnote-account-transcripts-v1:" + account_id).encode("utf-8")).hexdigest()

    def config(self, account_id):
        if not self.configured:
            return {"ready": False, "configured": False, "agreement_version": "2026-10-12-v1",
                    "max_duration_seconds": 1800, "retention_hours": 24, "message": MESSAGES["not_configured"]}
        status, value = self.client.request("GET", "/v1/config", self.owner(account_id))
        if status != 200 or not isinstance(value.get("ready"), bool) or not isinstance(value.get("agreement_version"), str):
            raise TranscriptError("upstream_unavailable", 502)
        return {key: value[key] for key in ("ready", "agreement_version", "max_duration_seconds", "retention_hours", "message") if key in value} | {"configured": True}

    def submit(self, account_id, body):
        if not self.configured:
            raise TranscriptError("not_configured", 503)
        if not isinstance(body, dict):
            raise TranscriptError("invalid_request")
        owner, quotas = self.owner(account_id), self.admissions
        request_id = body.get("client_request_id")
        if request_id is not None and (not isinstance(request_id, str) or not JOB_RE.fullmatch(request_id)):
            raise TranscriptError("invalid_request")
        capture_id = body.get("capture_id")
        if capture_id is not None and (not isinstance(capture_id, str) or not CAPTURE_RE.fullmatch(capture_id) or ".." in capture_id):
            raise TranscriptError("invalid_request")
        # Drop all unknown keys (including owner/token/upstream) rather than proxy them.
        request = {key: body[key] for key in ("share_text", "rights_basis", "rights_note", "agreement_version", "consent") if key in body}
        fingerprint = hashlib.sha256(json.dumps(request, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()
        try:
            quotas.check_request(owner, request_id, fingerprint)
            active = quotas.current(owner)
            if active and active[0]:
                status, result = self._refresh(owner, active[0])
                if status == 404 or (status == 200 and result["job"]["status"] in ("completed", "failed")):
                    quotas.finished(owner, active[0])
            reservation = quotas.reserve(owner, request_id, fingerprint)
        except ExistingRequest as replay:
            # Local catalog ownership is durable after core expiry. A replay
            # never submits again or changes a record association/fingerprint.
            cached = quotas.cached(owner, replay.job_id)
            if cached:
                return 202, {"job": cached}
            if cached is False:
                raise TranscriptError("not_found", 404)
            status, clean = self._refresh(owner, replay.job_id)
            if status == 404:
                raise TranscriptError("not_found", 404)
            if status != 200 or clean["job"]["id"] != replay.job_id:
                raise TranscriptError("upstream_unavailable", 502)
            return 202, clean
        try:
            status, result = self.client.request("POST", "/v1/jobs", owner, request)
            clean = public_payload(status, result)
            if 400 <= status < 500:
                quotas.rejected(reservation)
                return status, clean
            if status != 202:
                raise TranscriptError("upstream_unavailable", 502)
            quotas.accepted(reservation, clean["job"]["id"], clean["job"], self._metadata(body))
            return status, {"job": quotas.cached(owner, clean["job"]["id"])}
        except Exception:
            quotas.uncertain(reservation)
            raise

    def job(self, method, account_id, ident):
        if method not in ("GET", "DELETE") or not JOB_RE.fullmatch(ident):
            raise TranscriptError("not_found", 404)
        owner, quotas = self.owner(account_id), self.admissions
        cached = quotas.cached(owner, ident)
        if cached is False:
            return 404, {"error": {"code": "not_found", "message": MESSAGES["not_found"]}}
        if cached:
            if method == "GET":
                if cached["status"] in ("completed", "failed") or not self.configured:
                    return 200, {"job": cached}
                try:
                    return self._refresh(owner, ident)
                except TranscriptError:
                    # A temporary core outage must not hide durable progress.
                    return 200, {"job": cached}
            quotas.remove(owner, ident)
            if self.configured:
                try:
                    status, payload = self.client.request("DELETE", "/v1/jobs/" + ident, owner)
                    if status == 404 or (status == 200 and payload.get("deleted") is True and payload.get("id") == ident):
                        # Keep a minimal catalog tombstone until admission TTL;
                        # otherwise legacy recovery could recreate this task.
                        with quotas.db() as db:
                            db.execute("UPDATE catalog SET next_poll=? WHERE owner=? AND job_id=?", (time.time() + RETENTION_SECONDS, owner, ident))
                except TranscriptError:
                    quotas.poll_error(owner, ident)
            return 200, {"deleted": True, "id": ident}
        if not self.configured:
            raise TranscriptError("not_configured", 503)
        if not quotas.owns(owner, ident):
            raise TranscriptError("not_found", 404)
        # Lazily migrate a known pre-upgrade task on its first direct access.
        # This also makes deleting one before the collector runs durable.
        with quotas.db() as db:
            request = db.execute("SELECT request_id FROM requests WHERE owner=? AND job_id=? LIMIT 1", (owner, ident)).fetchone()
        quotas.remember(owner, {"id": ident, "status": "queued", "stage": "recovering", "progress": 0, "text": "", "segments": [], "source": {}, "error": None},
                        {"client_request_id": request[0] if request else "", "capture_id": "", "source_url": "", "share_text": ""})
        return self.job(method, account_id, ident)
