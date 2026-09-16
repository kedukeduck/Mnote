import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from heartnote_capture.http_api import Tokens, create_server
from heartnote_capture.store import CaptureStore

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).resolve().parents[2] / "scripts/publish-update-server.py")
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.store = CaptureStore(self.root / "vault")
        self.releases = self.store.root / "releases"
        self.artifacts = self.root / "artifacts"
        self.artifacts.mkdir()
        self.server = create_server("127.0.0.1", 0, self.store, Tokens("write", "read", "ai"))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def package(self, platform="android", version="1.9.0-test", data=b"PK-test-apk"):
        names = {f"Mnote-Android-{version}.apk": data} if platform == "android" else {
            f"Mnote-Windows-{version}-Setup.exe": b"MZ-test-installer",
            f"Mnote-Windows-{version}-Portable.zip": b"PK-test-portable"}
        for name, payload in names.items():
            (self.artifacts / name).write_bytes(payload)
        (self.artifacts / "SHA256SUMS").write_text("\n".join(hashlib.sha256(payload).hexdigest() + "  " + name for name, payload in names.items()))
        return [self.artifacts / name for name in [*names, "SHA256SUMS"]]

    def publish(self, platform="android", version="1.9.0-test", data=b"PK-test-apk"):
        publisher.publish(self.releases, platform, version, self.package(platform, version, data), "更新 <script>alert(1)</script>")

    def get(self, path, method="GET"):
        try:
            response = urlopen(Request(f"http://127.0.0.1:{self.server.server_port}" + path, method=method), timeout=5)
        except HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, response.read()

    def test_public_downloads_manifest_and_head_do_not_need_note_credentials(self):
        self.publish()
        self.publish("windows")
        code, headers, data = self.get("/updates/releases.json")
        self.assertEqual(200, code)
        self.assertEqual("no-store", headers["Cache-Control"])
        manifest = json.loads(data)
        self.assertEqual(2, len(manifest))
        self.assertNotIn(b"github.com", data)
        for release in manifest:
            for asset in release["assets"]:
                url = asset["browser_download_url"]
                self.assertTrue(url.startswith(publisher.BASE + "files/"))
                path = url.removeprefix("https://chenyu.online/heartnote-capture")
                status, package_headers, payload = self.get(path)
                self.assertEqual(200, status)
                self.assertEqual(asset["size"], len(payload))
                self.assertEqual(asset["digest"], "sha256:" + hashlib.sha256(payload).hexdigest())
                self.assertIn("immutable", package_headers["Cache-Control"])
                self.assertEqual(b"", self.get(path, "HEAD")[2])
        self.assertEqual(401, self.get("/v1/captures")[0])
        self.assertNotEqual(200, self.get("/updates/releases.json", "POST")[0])

    def test_page_escapes_notes_and_only_links_to_public_packages(self):
        self.publish()
        status, headers, page = self.get("/updates/")
        self.assertEqual(200, status)
        self.assertIn(b"&lt;script&gt;", page)
        self.assertNotIn(b"<script>", page)
        self.assertIn(b'href="files/mnote-android-v1.9.0-test/', page)
        self.assertIn("style-src 'self'", headers["Content-Security-Policy"])
        self.assertEqual(200, self.get("/updates/style.css")[0])

    def test_traversal_symlinks_unknown_paths_are_not_public(self):
        self.publish()
        for path in ("/updates/../accounts.sqlite3", "/updates/%2e%2e/accounts.sqlite3", "/updates/.publish.lock", "/updates/files/mnote-android-v1.9.0-test/../SHA256SUMS", "/updates/files/mnote-android-v1.9.0-test/extra.apk"):
            self.assertEqual(404, self.get(path)[0])
        package = self.releases / "files/mnote-android-v1.9.0-test/Mnote-Android-1.9.0-test.apk"
        package.unlink()
        package.symlink_to(self.artifacts / package.name)
        self.assertEqual(404, self.get("/updates/files/mnote-android-v1.9.0-test/" + package.name)[0])

    def test_failed_publish_preserves_manifest_and_immutable_packages(self):
        self.publish()
        manifest = (self.releases / "releases.json").read_bytes()
        with self.assertRaisesRegex(ValueError, "immutable"):
            self.publish(data=b"PK-changed")
        self.assertEqual(manifest, (self.releases / "releases.json").read_bytes())
        paths = self.package(version="1.9.1-test")
        (self.artifacts / "SHA256SUMS").write_text("bad  Mnote-Android-1.9.1-test.apk")
        with self.assertRaisesRegex(ValueError, "mismatch"):
            publisher.publish(self.releases, "android", "1.9.1-test", paths, "")
        self.assertEqual(manifest, (self.releases / "releases.json").read_bytes())
        self.assertFalse((self.releases / "files/mnote-android-v1.9.1-test").exists())
        self.assertFalse(list(self.releases.glob(".staging-*")))

    def test_missing_or_invalid_manifest_fails_closed(self):
        self.assertEqual(404, self.get("/updates/releases.json")[0])
        self.publish()
        (self.releases / "releases.json").write_text('{"bad": "private detail"}')
        status, _, body = self.get("/updates/releases.json")
        self.assertEqual(503, status)
        self.assertNotIn(b"private detail", body)
