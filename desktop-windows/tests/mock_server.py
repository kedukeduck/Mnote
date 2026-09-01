#!/usr/bin/env python3
"""One-request server for the Windows WinHTTP smoke test.

It intentionally emits no request log, header, token, or body.
"""

from __future__ import annotations

import sys
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def log_message(self, _format: str, *args: object) -> None:
        return

    def do_PUT(self) -> None:  # noqa: N802
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        body = self.rfile.read(length)
        valid = (
            self.path == "/v1/captures/smoke-capture-0001"
            and self.headers.get("Authorization") == "Bearer smoke-write-token"
            and self.headers.get("Content-Type") == "application/json"
            and body == b'{"schema_version":1,"id":"smoke-capture-0001"}'
        )
        self.send_response(200 if valid else 400)
        self.send_header("Content-Length", "0")
        self.end_headers()


def main() -> int:
    if len(sys.argv) != 2:
        return 2
    server = HTTPServer(("127.0.0.1", int(sys.argv[1])), Handler)
    server.handle_request()
    server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
