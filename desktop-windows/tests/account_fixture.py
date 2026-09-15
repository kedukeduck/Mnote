"""Isolated real Capture Server for Windows tests; never uses production data."""
import pathlib
import sys
from heartnote_capture.http_api import create_server, Tokens
from heartnote_capture.store import CaptureStore, minimal_png

root = pathlib.Path(sys.argv[1])
root.mkdir(parents=True, exist_ok=True)
server = create_server("127.0.0.1", int(sys.argv[2]), CaptureStore(str(root / "server")), Tokens("test-write", "test-read", "test-ai"), "https://images.example.test/capture")
invitation = server.RequestHandlerClass.accounts.invite()
(root / "invitation.txt").write_text(invitation, encoding="ascii")
(root / "fixture.png").write_bytes(minimal_png())
(root / "port.txt").write_text(str(server.server_port), encoding="ascii")
server.serve_forever()
