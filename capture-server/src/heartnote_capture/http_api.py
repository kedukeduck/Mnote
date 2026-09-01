from __future__ import annotations

import hmac
import json
import mimetypes
import os
import sys
import tempfile
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urlparse

from .store import (
    MAX_JSON_BYTES,
    CaptureConflict,
    CaptureNotFound,
    CaptureStore,
    CaptureValidationError,
)


WEB_ROOT = Path(__file__).with_name("web")
WEB_ASSETS = {
    "/": "index.html",
    "/index.html": "index.html",
    "/assets/app.css": "app.css",
    "/assets/app.js": "app.js",
}


@dataclass(frozen=True)
class Tokens:
    write: str
    read: str
    ai: str

    @classmethod
    def from_environment(cls) -> "Tokens":
        tokens = cls(
            write=os.environ.get("HEARTNOTE_CAPTURE_WRITE_TOKEN", ""),
            read=os.environ.get("HEARTNOTE_CAPTURE_READ_TOKEN", ""),
            ai=os.environ.get("HEARTNOTE_CAPTURE_AI_TOKEN", ""),
        )
        if not all((tokens.write, tokens.read, tokens.ai)):
            raise RuntimeError(
                "HEARTNOTE_CAPTURE_WRITE_TOKEN, HEARTNOTE_CAPTURE_READ_TOKEN, "
                "and HEARTNOTE_CAPTURE_AI_TOKEN are required"
            )
        if len({tokens.write, tokens.read, tokens.ai}) != 3:
            raise RuntimeError("write, read, and AI tokens must be different")
        return tokens


class CaptureRequestHandler(BaseHTTPRequestHandler):
    server_version = "MnoteCapture/0.1"
    store: CaptureStore
    tokens: Tokens

    def end_headers(self) -> None:
        # Applied to UI, API, assets and errors. No Access-Control-Allow-Origin
        # header is emitted: this personal vault is same-origin by default.
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        self.send_header("Cross-Origin-Opener-Policy", "same-origin")
        self.send_header("X-Permitted-Cross-Domain-Policies", "none")
        self.send_header(
            "Permissions-Policy",
            "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()",
        )
        self.send_header(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'self'; style-src 'self'; "
            "img-src 'self' blob: data:; connect-src 'self'; font-src 'self'; "
            "base-uri 'none'; form-action 'none'; frame-ancestors 'none'; "
            "object-src 'none'",
        )
        super().end_headers()

    def log_message(self, format: str, *args: Any) -> None:
        # BaseHTTPRequestHandler logs the full request target, including search
        # query text and capture IDs. Keep only method and status in operational
        # logs so personal knowledge and bearer tokens never reach stderr.
        status = str(args[1]) if format == '"%s" %s %s' and len(args) > 1 else "-"
        sys.stderr.write(
            f'{self.address_string()} - - [{self.log_date_time_string()}] '
            f'"{self.command}" {status}\n'
        )

    def _json(self, status: int, payload: Any, headers: dict[str, str] | None = None) -> None:
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(encoded)

    def _problem(self, status: int, code: str, detail: str, **extra: Any) -> None:
        self._json(status, {"error": code, "detail": detail, **extra})

    def _body(self) -> dict[str, Any]:
        raw_length = self.headers.get("Content-Length", "")
        try:
            length = int(raw_length)
        except ValueError as error:
            raise CaptureValidationError("Content-Length is required") from error
        if length < 0 or length > MAX_JSON_BYTES:
            raise CaptureValidationError("request body exceeds the size limit")
        content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        if content_type != "application/json":
            raise CaptureValidationError("Content-Type must be application/json")
        try:
            value = json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise CaptureValidationError("request body is not valid UTF-8 JSON") from error
        if not isinstance(value, dict):
            raise CaptureValidationError("request body must be an object")
        return value

    def _scope(self) -> str | None:
        authorization = self.headers.get("Authorization", "")
        if not authorization.startswith("Bearer "):
            return None
        supplied = authorization[7:]
        for scope, expected in (
            ("write", self.tokens.write),
            ("read", self.tokens.read),
            ("ai", self.tokens.ai),
        ):
            if hmac.compare_digest(supplied, expected):
                return scope
        return None

    def _require(self, *allowed: str) -> str | None:
        scope = self._scope()
        if scope is None:
            self._json(
                HTTPStatus.UNAUTHORIZED,
                {"error": "unauthorized", "detail": "A valid bearer token is required"},
                {"WWW-Authenticate": 'Bearer realm="Mnote"'},
            )
            return None
        if scope not in allowed:
            self._problem(
                HTTPStatus.FORBIDDEN,
                "forbidden",
                "The bearer token does not grant this operation",
            )
            return None
        return scope

    @staticmethod
    def _parts(path: str) -> list[str]:
        return [unquote(part) for part in path.split("/") if part]

    @staticmethod
    def _integer(values: dict[str, list[str]], key: str, default: int) -> int:
        try:
            return int(values.get(key, [str(default)])[0])
        except ValueError as error:
            raise CaptureValidationError(f"{key} must be an integer") from error

    def _web_asset(self, path: str) -> bool:
        filename = WEB_ASSETS.get(path)
        if filename is None:
            return False
        asset = WEB_ROOT / filename
        try:
            data = asset.read_bytes()
        except OSError:
            self._problem(HTTPStatus.INTERNAL_SERVER_ERROR, "ui_unavailable", "Web Inbox files are unavailable")
            return True
        content_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        if filename.endswith(".js"):
            content_type = "text/javascript"
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", f"{content_type}; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)
        return True

    def do_HEAD(self) -> None:  # noqa: N802
        self.do_GET()

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if self._web_asset(parsed.path):
            return
        if parsed.path == "/health":
            self._json(HTTPStatus.OK, {"status": "ok"})
            return
        # Reading is intentionally separated from upload authority. A leaked
        # device write token can submit captures, but cannot enumerate or
        # download the vault.
        scope = self._require("read", "ai")
        if scope is None:
            return
        query = parse_qs(parsed.query, keep_blank_values=False)
        parts = self._parts(parsed.path)
        ai_only = scope == "ai"
        try:
            if parts == ["v1", "captures"]:
                records = self.store.list(
                    limit=self._integer(query, "limit", 50),
                    before=query.get("before", [None])[0],
                    include_deleted=(
                        not ai_only and query.get("include_deleted", ["false"])[0] == "true"
                    ),
                    ai_only=ai_only,
                )
                if ai_only:
                    self.store.audit("ai", "list", parsed.query, [item["id"] for item in records])
                self._json(HTTPStatus.OK, {"records": records})
                return
            if parts == ["v1", "search"]:
                text = query.get("q", [""])[0]
                records = self.store.search(
                    text,
                    limit=self._integer(query, "limit", 30),
                    include_deleted=(
                        not ai_only and query.get("include_deleted", ["false"])[0] == "true"
                    ),
                    ai_only=ai_only,
                )
                if ai_only:
                    self.store.audit("ai", "search", text, [item["id"] for item in records])
                self._json(HTTPStatus.OK, {"records": records, "query": text})
                return
            if parts == ["v1", "changes"]:
                # A change feed includes tombstones. Once a private capture is
                # purged there is no policy row left with which to filter its
                # identifier, so exposing this endpoint to AI credentials can
                # leak deny/local_only IDs. AI clients use the filtered list,
                # search and timeline surfaces instead.
                if ai_only:
                    self._problem(
                        HTTPStatus.FORBIDDEN,
                        "forbidden",
                        "AI credentials cannot read the synchronization change feed",
                    )
                    return
                result = self.store.changes(
                    self._integer(query, "after", 0),
                    self._integer(query, "limit", 200),
                    ai_only=False,
                )
                self._json(HTTPStatus.OK, result)
                return
            if parts == ["v1", "export"]:
                if ai_only:
                    self._problem(HTTPStatus.FORBIDDEN, "forbidden", "AI credentials cannot export the vault")
                    return
                with tempfile.NamedTemporaryFile(suffix=".zip", delete=False) as temporary:
                    temporary_path = Path(temporary.name)
                try:
                    self.store.export_zip(
                        temporary_path,
                        include_deleted=query.get("include_deleted", ["false"])[0] == "true",
                    )
                    data = temporary_path.read_bytes()
                finally:
                    temporary_path.unlink(missing_ok=True)
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Content-Disposition", 'attachment; filename="heartnote-export.zip"')
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(data)
                return
            if len(parts) == 3 and parts[:2] == ["v1", "captures"]:
                record = self.store.get(parts[2], ai_only=ai_only)
                if ai_only:
                    self.store.audit("ai", "read", parts[2], [parts[2]])
                self._json(HTTPStatus.OK, record)
                return
            if len(parts) == 5 and parts[:2] == ["v1", "captures"] and parts[3] == "assets":
                path, content_type, digest = self.store.asset(
                    parts[2],
                    parts[4],
                    include_deleted=(
                        not ai_only and query.get("include_deleted", ["false"])[0] == "true"
                    ),
                    ai_only=ai_only,
                )
                if ai_only:
                    self.store.audit("ai", "read_asset", f"{parts[2]}:{parts[4]}", [parts[2]])
                data = path.read_bytes()
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(data)))
                self.send_header("ETag", f'"sha256:{digest}"')
                self.send_header("Cache-Control", "private, no-store")
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(data)
                return
            self._problem(HTTPStatus.NOT_FOUND, "not_found", "Unknown endpoint")
        except CaptureNotFound:
            self._problem(HTTPStatus.NOT_FOUND, "not_found", "Capture was not found")
        except CaptureValidationError as error:
            self._problem(HTTPStatus.BAD_REQUEST, "invalid_request", str(error))

    def do_PUT(self) -> None:  # noqa: N802
        if self._require("write") is None:
            return
        parts = self._parts(urlparse(self.path).path)
        if len(parts) != 3 or parts[:2] != ["v1", "captures"]:
            self._problem(HTTPStatus.NOT_FOUND, "not_found", "Unknown endpoint")
            return
        try:
            body = self._body()
            base_revision = body.pop("base_revision", None)
            record = self.store.put(parts[2], body, base_revision=base_revision)
            self._json(HTTPStatus.OK, record, {"ETag": f'"revision:{record["revision"]}"'})
        except CaptureConflict as error:
            self._problem(
                HTTPStatus.CONFLICT,
                "revision_conflict",
                str(error),
                current_revision=error.current_revision,
            )
        except CaptureValidationError as error:
            self._problem(HTTPStatus.BAD_REQUEST, "invalid_request", str(error))

    def do_POST(self) -> None:  # noqa: N802
        if self._require("write") is None:
            return
        parts = self._parts(urlparse(self.path).path)
        try:
            if parts == ["v1", "captures"]:
                body = self._body()
                capture_id = body.get("id")
                if not isinstance(capture_id, str):
                    raise CaptureValidationError("id is required")
                base_revision = body.pop("base_revision", None)
                record = self.store.put(capture_id, body, base_revision=base_revision)
                self._json(HTTPStatus.CREATED, record)
                return
            if len(parts) == 4 and parts[:2] == ["v1", "captures"] and parts[3] == "restore":
                body = self._body()
                record = self.store.restore(parts[2], body.get("base_revision"))
                self._json(HTTPStatus.OK, record)
                return
            if len(parts) == 4 and parts[:2] == ["v1", "captures"] and parts[3] == "purge":
                self._body()
                self.store.purge(parts[2])
                self._json(HTTPStatus.OK, {"purged": parts[2]})
                return
            self._problem(HTTPStatus.NOT_FOUND, "not_found", "Unknown endpoint")
        except CaptureNotFound:
            self._problem(HTTPStatus.NOT_FOUND, "not_found", "Capture was not found")
        except CaptureConflict as error:
            self._problem(
                HTTPStatus.CONFLICT,
                "revision_conflict",
                str(error),
                current_revision=error.current_revision,
            )
        except CaptureValidationError as error:
            self._problem(HTTPStatus.BAD_REQUEST, "invalid_request", str(error))

    def do_DELETE(self) -> None:  # noqa: N802
        if self._require("write") is None:
            return
        parts = self._parts(urlparse(self.path).path)
        if len(parts) != 3 or parts[:2] != ["v1", "captures"]:
            self._problem(HTTPStatus.NOT_FOUND, "not_found", "Unknown endpoint")
            return
        try:
            revision = self.headers.get("If-Match", "").strip('"')
            if revision.startswith("revision:"):
                revision = revision[9:]
            try:
                base_revision = int(revision)
            except ValueError as error:
                raise CaptureValidationError("If-Match revision is required") from error
            record = self.store.soft_delete(parts[2], base_revision)
            self._json(HTTPStatus.OK, record)
        except CaptureNotFound:
            self._problem(HTTPStatus.NOT_FOUND, "not_found", "Capture was not found")
        except CaptureConflict as error:
            self._problem(
                HTTPStatus.CONFLICT,
                "revision_conflict",
                str(error),
                current_revision=error.current_revision,
            )
        except CaptureValidationError as error:
            self._problem(HTTPStatus.BAD_REQUEST, "invalid_request", str(error))

    def do_OPTIONS(self) -> None:  # noqa: N802
        # Deliberately do not enable wildcard CORS. Browser extensions with an
        # explicit localhost host permission and the bundled same-origin UI do
        # not require it.
        self.send_response(HTTPStatus.METHOD_NOT_ALLOWED)
        self.send_header("Allow", "GET, HEAD, PUT, POST, DELETE")
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()


def create_server(host: str, port: int, store: CaptureStore, tokens: Tokens) -> ThreadingHTTPServer:
    handler = type(
        "ConfiguredCaptureRequestHandler",
        (CaptureRequestHandler,),
        {"store": store, "tokens": tokens},
    )
    return ThreadingHTTPServer((host, port), handler)


def main() -> None:
    host = os.environ.get("HEARTNOTE_CAPTURE_HOST", "127.0.0.1")
    port = int(os.environ.get("HEARTNOTE_CAPTURE_PORT", "8787"))
    data = os.environ.get("HEARTNOTE_CAPTURE_DATA", "./data")
    server = create_server(host, port, CaptureStore(data), Tokens.from_environment())
    print(f"Mnote capture API listening on http://{host}:{port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
