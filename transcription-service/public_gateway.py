#!/usr/bin/env python3
"""Anonymous same-origin adapter; no browser bearer token or caller-selected owner.

Visitor cookies identify browser sessions, not real people. The persistent global
daily ceiling is the resource bound even if someone clears their cookie. A POST
whose upstream outcome is unknown is never retried/refunded automatically.
"""
from __future__ import annotations

import contextlib
import dataclasses
import hashlib
import hmac
import http.client
from http.cookies import CookieError, SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
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
import urllib.parse

PREFIX = "/mnote-transcript/api/transcripts/"
COOKIE_NAME = "__Host-mnote-transcript-visitor"
COOKIE_PATH = "/"
SESSION_SECONDS = 24 * 3600
UPSTREAM_HOST, UPSTREAM_PORT = "127.0.0.1", 8791
MAX_BODY, MAX_RESPONSE = 16 * 1024, 8 * 1024 * 1024
COOKIE_RE = re.compile(r"([0-9a-f]{48})\.([0-9]{10})\.([0-9a-f]{64})\Z")
JOB_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
MESSAGES = {
    "not_found": "任务不存在、已过期或不属于当前访客。",
    "session_required": "请先打开配置页面建立访客会话；会话有效期为 24 小时。",
    "origin_denied": "此操作必须从本站页面发起。",
    "invalid_request": "请求格式不正确。",
    "payload_too_large": "请求内容超过 16 KiB 限制。",
    "visitor_busy": "当前访客已有进行中的任务，请等待完成或删除后再提交。",
    "submission_uncertain": "上次提交结果尚未确认；为避免重复任务，请等待访客限制解除后重试。",
    "visitor_limit": "当前访客每小时最多提交 3 次，请等待后再试。",
    "daily_limit": "公开演示每日最多接收 40 次提交（UTC 日界），请等待下一日再试。",
    "upstream_unavailable": "转写服务暂时不可用，请稍后重试。",
    "upstream_redirect": "转写服务返回了不允许的重定向。",
}


class GatewayError(Exception):
    def __init__(self, code, status=400, retry_after=None):
        self.code, self.status, self.retry_after = code, status, retry_after
        super().__init__(MESSAGES[code])


@dataclasses.dataclass(frozen=True)
class Config:
    token: str
    session_secret: str
    public_origin: str = "https://chenyu.online"
    data_dir: Path = Path("/var/lib/mnote-transcription-public")

    @classmethod
    def from_env(cls):
        value = cls(os.environ.get("MNOTE_TRANSCRIPT_TOKEN", ""),
                    os.environ.get("MNOTE_TRANSCRIPT_SESSION_SECRET", ""),
                    os.environ.get("MNOTE_TRANSCRIPT_PUBLIC_ORIGIN", "https://chenyu.online"),
                    Path(os.environ.get("MNOTE_TRANSCRIPT_GATEWAY_DATA", "/var/lib/mnote-transcription-public")).resolve())
        if len(value.token.encode()) < 32 or len(value.session_secret.encode()) < 32:
            raise ValueError("gateway credentials must each contain at least 32 bytes")
        if re.search(r"[\r\n]", value.token) or not re.fullmatch(r"https://[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?(?::443)?", value.public_origin):
            raise ValueError("invalid gateway configuration")
        return value


class Sessions:
    def __init__(self, secret: str):
        self.secret = secret.encode("utf-8")

    def issue(self, now=None):
        now = time.time() if now is None else now
        random_id = secrets.token_hex(24)
        unsigned = random_id + "." + str(int(now) + SESSION_SECONDS)
        signature = hmac.new(self.secret, unsigned.encode("ascii"), hashlib.sha256).hexdigest()
        value = unsigned + "." + signature
        return value, self.owner(random_id)

    @staticmethod
    def owner(random_id):
        return hashlib.sha256(("public-demo-v1:" + random_id).encode("ascii")).hexdigest()

    def verify(self, value, now=None):
        now = time.time() if now is None else now
        if not isinstance(value, str):
            return None
        match = COOKIE_RE.fullmatch(value)
        if not match:
            return None
        random_id, expiry, signature = match.groups()
        expected = hmac.new(self.secret, (random_id + "." + expiry).encode("ascii"), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(signature, expected) or not now < int(expiry) <= now + SESSION_SECONDS + 1:
            return None
        return self.owner(random_id)

    def cookie(self, value):
        return f"{COOKIE_NAME}={value}; Max-Age={SESSION_SECONDS}; Path={COOKIE_PATH}; HttpOnly; Secure; SameSite=Strict"

    def read(self, headers):
        values = headers.get_all("Cookie", [])
        if len(values) != 1 or len(values[0]) > 4096:
            return None, None
        # A duplicate name is ambiguous across proxies/parsers: reject it, not last-wins.
        names = [part.strip().split("=", 1)[0] for part in values[0].split(";")]
        if names.count(COOKIE_NAME) != 1:
            return None, None
        try:
            cookie = SimpleCookie()
            cookie.load(values[0])
            value = cookie[COOKIE_NAME].value
        except (CookieError, KeyError, ValueError):
            return None, None
        return self.verify(value), value


class Quotas:
    """Reservations are durable potential admissions, counted until definitely rejected.

    This closes simultaneous submissions and crash-after-acceptance races. An unknown
    reservation expires only at 24h (the backend's own job TTL); it cannot be used to
    reset quotas. Successful admission still counts after ASR failure or DELETE.
    """
    def __init__(self, directory):
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = directory / "gateway.sqlite3"
        self.lock = threading.RLock()
        with self.db() as db:
            db.execute("CREATE TABLE IF NOT EXISTS attempts (id TEXT PRIMARY KEY, owner TEXT NOT NULL, created_at REAL NOT NULL, job_id TEXT, status TEXT NOT NULL)")
            db.execute("CREATE INDEX IF NOT EXISTS attempts_owner_time ON attempts(owner,created_at)")
            db.execute("CREATE INDEX IF NOT EXISTS attempts_job ON attempts(job_id,owner)")
            db.execute("CREATE TABLE IF NOT EXISTS visitors (owner TEXT PRIMARY KEY, active_attempt TEXT)")
            # Previous process may have died after core accepted but before local commit.
            db.execute("UPDATE attempts SET status='uncertain' WHERE status='reserved'")
        os.chmod(self.path, 0o600)

    @contextlib.contextmanager
    def db(self):
        with self.lock:
            db = sqlite3.connect(self.path, timeout=10)
            db.execute("PRAGMA secure_delete=ON")
            try:
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
        db.execute("DELETE FROM attempts WHERE created_at<=?", (now - SESSION_SECONDS,))
        db.execute("UPDATE visitors SET active_attempt=NULL WHERE active_attempt NOT IN (SELECT id FROM attempts)")
        db.execute("DELETE FROM visitors WHERE active_attempt IS NULL AND owner NOT IN (SELECT owner FROM attempts)")

    def active(self, owner):
        now = time.time()
        with self.db() as db:
            self.prune(db, now)
            return db.execute("SELECT a.id,a.job_id,a.status,a.created_at FROM visitors v JOIN attempts a ON a.id=v.active_attempt WHERE v.owner=?", (owner,)).fetchone()

    def reserve(self, owner):
        now = time.time()
        with self.db() as db:
            self.prune(db, now)
            active = db.execute("SELECT a.status,a.created_at FROM visitors v JOIN attempts a ON a.id=v.active_attempt WHERE v.owner=?", (owner,)).fetchone()
            if active:
                uncertain = active[0] == "uncertain"
                raise GatewayError("submission_uncertain" if uncertain else "visitor_busy", 429,
                                   max(1, math.ceil(active[1] + SESSION_SECONDS - now)) if uncertain else 30)
            day_start = math.floor(now / SESSION_SECONDS) * SESSION_SECONDS
            daily = db.execute("SELECT COUNT(*) FROM attempts WHERE created_at>=?", (day_start,)).fetchone()[0]
            if daily >= 40:
                raise GatewayError("daily_limit", 429, max(1, math.ceil(day_start + SESSION_SECONDS - now)))
            hourly = db.execute("SELECT created_at FROM attempts WHERE owner=? AND created_at>? ORDER BY created_at", (owner, now - 3600)).fetchall()
            if len(hourly) >= 3:
                raise GatewayError("visitor_limit", 429, max(1, math.ceil(hourly[0][0] + 3600 - now)))
            ident = secrets.token_hex(16)
            db.execute("INSERT INTO attempts VALUES (?,?,?,NULL,'reserved')", (ident, owner, now))
            db.execute("INSERT INTO visitors VALUES (?,?) ON CONFLICT(owner) DO UPDATE SET active_attempt=excluded.active_attempt", (owner, ident))
        return ident

    def accepted(self, reservation, job_id):
        with self.db() as db:
            db.execute("UPDATE attempts SET job_id=?,status='accepted' WHERE id=?", (job_id, reservation))

    def uncertain(self, reservation):
        with self.db() as db:
            db.execute("UPDATE attempts SET status='uncertain' WHERE id=?", (reservation,))

    def rejected(self, reservation):
        with self.db() as db:
            db.execute("UPDATE visitors SET active_attempt=NULL WHERE active_attempt=?", (reservation,))
            db.execute("DELETE FROM attempts WHERE id=?", (reservation,))

    def owned(self, owner, job_id):
        with self.db() as db:
            self.prune(db, time.time())
            return db.execute("SELECT 1 FROM attempts WHERE owner=? AND job_id=? AND status='accepted'", (owner, job_id)).fetchone() is not None

    def clear_active(self, owner, job_id):
        with self.db() as db:
            db.execute("UPDATE visitors SET active_attempt=NULL WHERE owner=? AND active_attempt IN (SELECT id FROM attempts WHERE owner=? AND job_id=?)", (owner, owner, job_id))


@dataclasses.dataclass
class UpstreamResponse:
    status: int
    body: bytes

    def json(self):
        try:
            value = json.loads(self.body)
            if not isinstance(value, dict):
                raise ValueError()
            return value
        except (ValueError, UnicodeError):
            raise GatewayError("upstream_unavailable", 502) from None


class Upstream:
    def __init__(self, token):
        self.token = token

    def request(self, method, path, owner, body=None):
        # Neither hostname nor port comes from input, headers, env URLs, or redirects.
        connection = http.client.HTTPConnection(UPSTREAM_HOST, UPSTREAM_PORT, timeout=25)
        transport_socket = [None]
        timed_out = threading.Event()
        def abort_upstream():
            timed_out.set()
            # getresponse() clears connection.sock for HTTP/1.0/will_close while
            # HTTPResponse.fp still owns its descriptor. Preserve that original socket.
            sock = transport_socket[0] or connection.sock
            if sock:
                with contextlib.suppress(OSError):
                    sock.shutdown(socket.SHUT_RDWR)
            connection.close()
        timer = threading.Timer(25, abort_upstream)
        timer.daemon = True
        timer.start()
        try:
            connection.request(method, path, body=body, headers={"Authorization": "Bearer " + self.token,
                "X-Owner-Id": owner, "Content-Type": "application/json", "Accept-Encoding": "identity"})
            transport_socket[0] = connection.sock
            response = connection.getresponse()
            if 300 <= response.status < 400:
                raise GatewayError("upstream_redirect", 502)
            declared_length = response.getheader("Content-Length")
            if declared_length is not None:
                try:
                    declared_length = int(declared_length)
                    if not 0 <= declared_length <= MAX_RESPONSE:
                        raise ValueError()
                except ValueError:
                    raise GatewayError("upstream_unavailable", 502) from None
            raw = response.read(MAX_RESPONSE + 1)
            if timed_out.is_set():
                raise GatewayError("upstream_unavailable", 503)
            if declared_length is not None and len(raw) != declared_length:
                raise GatewayError("upstream_unavailable", 502)
            if len(raw) > MAX_RESPONSE:
                raise GatewayError("upstream_unavailable", 502)
            if response.status in (401, 403) or response.status >= 500:
                raise GatewayError("upstream_unavailable", 503)
            return UpstreamResponse(response.status, raw)
        except (OSError, http.client.HTTPException):
            raise GatewayError("upstream_unavailable", 503) from None
        finally:
            timer.cancel()
            connection.close()


class GatewayService:
    def __init__(self, config):
        self.config, self.sessions = config, Sessions(config.session_secret)
        self.quotas, self.upstream = Quotas(config.data_dir), Upstream(config.token)

    def submit(self, owner, body):
        active = self.quotas.active(owner)
        if active and active[1]:
            response = self.upstream.request("GET", "/v1/jobs/" + active[1], owner)
            if response.status == 404 or (response.status == 200 and response.json().get("job", {}).get("status") in ("completed", "failed")):
                self.quotas.clear_active(owner, active[1])
        reservation = self.quotas.reserve(owner)
        try:
            response = self.upstream.request("POST", "/v1/jobs", owner, body)
            if 400 <= response.status < 500:
                self.quotas.rejected(reservation)
                return response
            if response.status != 202:
                raise GatewayError("upstream_unavailable", 502)
            job_id = response.json().get("job", {}).get("id")
            if not isinstance(job_id, str) or not JOB_RE.fullmatch(job_id):
                raise GatewayError("upstream_unavailable", 502)
            self.quotas.accepted(reservation, job_id)
            return response
        except Exception:
            # Includes failure after core accepted but before commit: no automatic refund.
            self.quotas.uncertain(reservation)
            raise

    def job(self, method, owner, ident, format_name=None):
        if not self.quotas.owned(owner, ident):
            raise GatewayError("not_found", 404)
        path = "/v1/jobs/" + ident
        if format_name:
            path += "/download?format=" + format_name
        response = self.upstream.request(method, path, owner)
        if method == "DELETE" and response.status in (200, 404):
            self.quotas.clear_active(owner, ident)
        elif method == "GET" and not format_name:
            if response.status == 404 or (response.status == 200 and response.json().get("job", {}).get("status") in ("completed", "failed")):
                self.quotas.clear_active(owner, ident)
        return response


class Handler(BaseHTTPRequestHandler):
    server_version = "MnotePublicGateway/1.0"

    def setup(self):
        super().setup()
        self.connection.settimeout(20)
        def abort_headers():
            with contextlib.suppress(OSError):
                self.connection.shutdown(socket.SHUT_RDWR)
        self.header_timer = threading.Timer(20, abort_headers)
        self.header_timer.daemon = True
        self.header_timer.start()

    def parse_request(self):
        try:
            return super().parse_request()
        finally:
            self.header_timer.cancel()

    def finish(self):
        self.header_timer.cancel()
        super().finish()

    def log_message(self, *args):
        pass

    def respond(self, status, body, mime="application/json; charset=utf-8", cookie=None, filename=None, retry_after=None):
        if isinstance(body, dict):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        if cookie:
            self.send_header("Set-Cookie", cookie)
        if filename:
            self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
        if retry_after:
            self.send_header("Retry-After", str(max(1, int(retry_after))))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self):
        lengths = self.headers.get_all("Content-Length", [])
        if self.headers.get("Transfer-Encoding") or len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,10}", lengths[0]):
            raise GatewayError("invalid_request")
        length = int(lengths[0])
        if length > MAX_BODY:
            raise GatewayError("payload_too_large", 413)
        if length < 1:
            raise GatewayError("invalid_request")
        if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
            raise GatewayError("invalid_request", 415)
        def abort_read():
            with contextlib.suppress(OSError):
                self.connection.shutdown(socket.SHUT_RDWR)
        timer = threading.Timer(20, abort_read)
        timer.daemon = True
        timer.start()
        try:
            raw = self.rfile.read(length)
            value = json.loads(raw)
            if len(raw) != length or not isinstance(value, dict):
                raise ValueError()
        except (ValueError, UnicodeError):
            raise GatewayError("invalid_request") from None
        finally:
            timer.cancel()
        return raw

    def require_origin(self):
        origins = self.headers.get_all("Origin", [])
        if origins != [self.server.service.config.public_origin]:
            raise GatewayError("origin_denied", 403)

    def route(self):
        service = self.server.service
        if self.command == "GET" and self.path == "/health":
            return self.respond(200, {"ok": True})
        if len(self.path) > 200:
            raise GatewayError("not_found", 404)
        parsed = urllib.parse.urlsplit(self.path)
        # No percent decoding, slash normalization, extra query parameters or arbitrary proxy paths.
        if not parsed.path.startswith(PREFIX) or parsed.scheme or parsed.netloc or parsed.fragment:
            raise GatewayError("not_found", 404)
        suffix = parsed.path[len(PREFIX):]
        owner, cookie_value = service.sessions.read(self.headers)
        if self.command == "GET" and suffix == "config" and not parsed.query:
            new_cookie = None
            if not owner:
                cookie_value, owner = service.sessions.issue()
                new_cookie = service.sessions.cookie(cookie_value)
            response = service.upstream.request("GET", "/v1/config", owner)
            return self.respond(response.status, response.json(), cookie=new_cookie)
        if suffix == "jobs" and self.command == "POST" and not parsed.query:
            if not owner:
                raise GatewayError("session_required", 401)
            self.require_origin()
            response = service.submit(owner, self.read_json())
            return self.respond(response.status, response.json(), retry_after=30 if response.status == 429 else None)
        match = re.fullmatch(r"jobs/([0-9a-f-]{36})(/download)?", suffix)
        if not match or not JOB_RE.fullmatch(match[1]):
            raise GatewayError("not_found", 404)
        ident, download = match.groups()
        if not owner:
            raise GatewayError("session_required", 401)
        format_name = None
        if download:
            if self.command != "GET" or parsed.query not in ("format=txt", "format=md", "format=srt"):
                raise GatewayError("not_found", 404)
            format_name = parsed.query[7:]
        elif parsed.query or self.command not in ("GET", "DELETE"):
            raise GatewayError("not_found", 404)
        if self.command == "DELETE":
            self.require_origin()
        response = service.job(self.command, owner, ident, format_name)
        if format_name and response.status == 200:
            mime = {"txt": "text/plain; charset=utf-8", "md": "text/markdown; charset=utf-8", "srt": "application/x-subrip; charset=utf-8"}[format_name]
            return self.respond(200, response.body, mime, filename=f"transcript-{ident}.{format_name}")
        return self.respond(response.status, response.json())

    def dispatch(self):
        try:
            self.route()
        except GatewayError as error:
            self.respond(error.status, {"error": {"code": error.code, "message": str(error)}}, retry_after=error.retry_after)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        except Exception:
            self.respond(503, {"error": {"code": "upstream_unavailable", "message": MESSAGES["upstream_unavailable"]}})

    do_GET = do_POST = do_DELETE = do_PUT = do_PATCH = do_OPTIONS = do_HEAD = dispatch


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 16

    def __init__(self, address, service):
        self.service = service
        super().__init__(address, Handler)

    def handle_error(self, request, client_address):
        # Avoid stdlib tracebacks/access details for abandoned or malformed requests.
        pass


def main():
    # Listener and upstream are intentionally not configurable through client input or env.
    service = GatewayService(Config.from_env())
    server = Server(("127.0.0.1", 8792), service)
    print("public transcription gateway listening on 127.0.0.1:8792", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
