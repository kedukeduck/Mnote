#!/usr/bin/env python3
"""One-request validator for the full Windows GUI/offline-retry smoke test."""

from __future__ import annotations

import base64
import json
import pathlib
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    result_path: pathlib.Path

    def log_message(self, _format: str, *args: object) -> None:
        return

    def do_PUT(self) -> None:  # noqa: N802
        status = 400
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 32 * 1024 * 1024:
                raise ValueError("invalid body size")
            body = json.loads(self.rfile.read(length))
            record_id = body["id"]
            if self.path != f"/v1/captures/{record_id}":
                raise ValueError("unexpected path")
            if self.headers.get("Authorization") != "Bearer gui-smoke-write-token":
                raise ValueError("unexpected authorization")
            if self.headers.get("Content-Type") != "application/json":
                raise ValueError("unexpected content type")
            if body["schema_version"] != 1 or body["comment"] != "wine-smoke-note":
                raise ValueError("unexpected record")
            if body["ai_access"] != "local_only":
                raise ValueError("unsafe privacy default")
            if len(body["annotations"]) != 1:
                raise ValueError("missing annotation")
            if {"local_files", "sync_state", "sync_error"} & body.keys():
                raise ValueError("local-only fields were uploaded")
            for asset_name in ("original", "annotated"):
                asset = body["assets"][asset_name]
                if asset["content_type"] != "image/png":
                    raise ValueError("unexpected asset type")
                png = base64.b64decode(asset["data_base64"], validate=True)
                if not png.startswith(b"\x89PNG\r\n\x1a\n"):
                    raise ValueError("invalid PNG")
            self.result_path.write_text(record_id, encoding="utf-8")
            status = 200
        except (KeyError, TypeError, ValueError, json.JSONDecodeError):
            status = 400
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()


def main() -> int:
    if len(sys.argv) != 3:
        return 2
    Handler.result_path = pathlib.Path(sys.argv[2])
    server = HTTPServer(("127.0.0.1", int(sys.argv[1])), Handler)
    server.handle_request()
    server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
