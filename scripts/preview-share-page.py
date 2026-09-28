#!/usr/bin/env python3
"""Serve synthetic share snapshots on loopback with the real HTML/CSP/asset handler."""
import argparse
import base64
import json
from pathlib import Path
import signal
import sys
import tempfile
import threading

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "capture-server" / "src"))
from heartnote_capture.http_api import Tokens, create_server
from heartnote_capture.store import CaptureStore


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", type=Path, required=True, help="Non-private PNG fixture")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--seconds", type=int, default=1800)
    args = parser.parse_args()
    image = args.image.read_bytes()
    assert image.startswith(b"\x89PNG\r\n\x1a\n") and len(image) < 8 * 1024 * 1024
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    with tempfile.TemporaryDirectory(prefix="mnote-share-preview-") as folder:
        server = create_server("127.0.0.1", args.port, CaptureStore(folder),
                               Tokens("fixture-write", "fixture-read", "fixture-ai"),
                               "https://fixture.invalid")
        origin = f"http://127.0.0.1:{server.server_port}"
        # Local fixture override only; production continues to require HTTPS.
        exports = server.RequestHandlerClass.exports
        exports.public_base = origin
        accounts = server.RequestHandlerClass.accounts
        account = accounts.activate("design-fixture", "fixture-password-123", accounts.invite())
        vault = accounts.store(accounts.resolve(account["access_token"]))
        record = vault.put("design-preview", {
            "kind": "thought", "comment": "想把每周回顾变成习惯。\n\n不只看收藏了什么，也看看自己为什么会被触动。真正有价值的不是记下多少，而是从这些片刻里，慢慢看见自己。",
            "source": {"text": "记录，不只是保存信息。\n也保留那些被触动的时刻。", "url": "https://example.com/reading/meaning-of-notes"},
            "tags": ["不应出现在分享页的标签"],
            "evidence": {"context": {"text": {"origin": "user_supplied", "full_text": "记录的意义\n\n在快节奏的生活里，我们总会遇到一些让内心微微一动的瞬间。它可能是一句话、一张图，或是一次不经意的对话。\n\n" + "当我们回头再看，那些曾经的细节，会成为连接过去与现在的线索。\n\n" * 12}}},
            "assets": {role: {"content_type": "image/png", "data_base64": base64.b64encode(image).decode()} for role in ("original", "context")}})
        variants = {
            "all": ["thought", "excerpt", "crop", "context", "original", "source"],
            "thought": ["thought"], "excerpt": ["excerpt"], "image": ["crop"],
            "legacy": ["original", "source"], "source": ["source"],
        }
        urls = {}
        for i, (name, fields) in enumerate(variants.items(), 1):
            body = {"id": record["id"], "revision": 1, "token": format(i, "064x"), "publish": True, "fields": fields}
            if "crop" in fields:
                body["crop_role"] = "original"
            urls[name] = exports.create_card(account["account_id"], vault, body)["url"]
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        print(json.dumps({"origin": origin, "urls": urls, "expires_seconds": args.seconds}, ensure_ascii=False), flush=True)
        try:
            stop.wait(args.seconds)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    main()
