"""Static, administrator-published updates. No GitHub dependency or upload API."""
import html
import json
import re
from pathlib import Path

VERSION = r"(?:0|[1-9][0-9]{0,5})\.(?:0|[1-9][0-9]{0,5})\.(?:0|[1-9][0-9]{0,5})(?:-test)?"


class Releases:
    def __init__(self, root):
        self.root = Path(root) / "releases"

    def resolve(self, path):
        match = re.fullmatch(r"/updates/files/(mnote-(android|windows)-v(" + VERSION + r"))/([^/]+)", path)
        if not match:
            raise FileNotFoundError()
        tag, platform, version, name = match.groups()
        allowed = {"SHA256SUMS", f"Mnote-Android-{version}.apk"} if platform == "android" else {
            "SHA256SUMS", f"Mnote-Windows-{version}-Setup.exe", f"Mnote-Windows-{version}-Portable.zip"}
        if name not in allowed:
            raise FileNotFoundError()
        candidate = self.root / "files" / tag / name
        if not candidate.resolve().is_relative_to(self.root.resolve()) or not candidate.is_file():
            raise FileNotFoundError()
        return candidate

    def manifest(self):
        path = self.root / "releases.json"
        if path.stat().st_size > 8 * 1024 * 1024:
            raise ValueError("release_manifest_too_large")
        value = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, list) or len(value) > 100 or any(not isinstance(r, dict) for r in value):
            raise ValueError("invalid_release_manifest")
        return value

    def page(self):
        # No user data or markup supplied in notes is interpreted as HTML.
        rows = []
        for release in self.manifest():
            if release.get("draft"):
                continue
            tag = release["tag_name"]
            links = []
            for asset in release["assets"]:
                name = asset["name"]
                path = f"/updates/files/{tag}/{name}"
                self.resolve(path)
                links.append(f'<a href="{html.escape("files/" + tag + "/" + name, quote=True)}">{html.escape(name)}</a>')
            rows.append("<section><h2>" + html.escape(tag) + "</h2><p>" + "<br>".join(links)
                + "</p><pre>" + html.escape(release.get("body", "")) + "</pre></section>")
        return ('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
            '<link rel="stylesheet" href="style.css"><title>Mnote 下载与更新</title><main><p>MNOTE · DOWNLOADS</p>'
            '<h1>让想法，继续生长。</h1><p>直接从 Mnote 服务器下载。无需 GitHub 账号，也无需笔记 Token。</p>'
            '<p>请直接覆盖安装，保留原有账号与记录。Android 为测试签名，Windows 测试包未签名。</p>'
            + "".join(rows) + "</main></html>").encode("utf-8")

    def serve(self, handler, path):
        if not path.startswith("/updates/"):
            return False
        sent = False
        try:
            if path == "/updates/releases.json":
                handler._json(200, self.manifest())
                return True
            if path == "/updates/":
                payload, content_type = self.page(), "text/html; charset=utf-8"
            elif path == "/updates/style.css":
                payload = b"body{margin:0;background:#f7f8fa;color:#202430;font:16px/1.7 system-ui,sans-serif}main{max-width:800px;margin:auto;padding:64px 24px}h1{font-size:40px;letter-spacing:-1px}section{background:white;border:1px solid #e4e8f0;border-radius:20px;padding:24px;margin:24px 0}a{color:#5064e8;overflow-wrap:anywhere}pre{white-space:pre-wrap;font:inherit;font-size:14px;color:#687184}h2{font-size:20px}"
                content_type = "text/css; charset=utf-8"
            else:
                file = self.resolve(path)
                with file.open("rb") as stream:
                    handler.send_response(200)
                    handler.send_header("Content-Type", "application/octet-stream")
                    handler.send_header("Content-Length", str(file.stat().st_size))
                    handler.send_header("Content-Disposition", f'attachment; filename="{file.name}"')
                    handler.send_header("Cache-Control", "public, max-age=31536000, immutable")
                    handler.end_headers()
                    sent = True
                    if handler.command != "HEAD":
                        while chunk := stream.read(65536):
                            handler.wfile.write(chunk)
                return True
            handler.send_response(200)
            handler.send_header("Content-Type", content_type)
            handler.send_header("Content-Length", str(len(payload)))
            handler.send_header("Cache-Control", "no-cache")
            handler.end_headers()
            sent = True
            if handler.command != "HEAD":
                handler.wfile.write(payload)
        except FileNotFoundError:
            handler._problem(404, "not_found", "Release unavailable")
        except (ValueError, OSError, KeyError, TypeError):
            if not sent:
                handler._problem(503, "release_unavailable", "Release temporarily unavailable")
        return True
