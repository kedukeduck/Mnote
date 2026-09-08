import json
import tempfile
import threading
import unittest
import base64
import io
import zipfile
from urllib.request import Request, urlopen
from urllib.error import HTTPError

from heartnote_capture.accounts import Accounts, AuthError
from heartnote_capture.http_api import create_server, Tokens
from heartnote_capture.store import CaptureStore, minimal_png


class AccountHTTPTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = CaptureStore(self.temp.name)
        self.server = create_server("127.0.0.1", 0, self.store, Tokens("write-test-token", "read-test-token", "ai-test-token"))
        self.accounts = self.server.RequestHandlerClass.accounts
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, method, path, body=None, token=None, headers=None):
        values = dict(headers or {})
        if token:
            values["Authorization"] = "Bearer " + token
        values["Content-Type"] = "application/json"
        request = Request(f"http://127.0.0.1:{self.server.server_port}" + path,
                          data=json.dumps(body).encode() if body is not None else None,
                          headers=values, method=method)
        try:
            response = urlopen(request, timeout=5)
        except HTTPError as error:
            response = error
        with response:
            data = response.read()
            return response.status, (json.loads(data) if response.headers.get_content_type() == "application/json" else data)

    def activate(self, username, legacy=False):
        code = self.accounts.invite(legacy_owner=legacy)
        status, data = self.request("POST", "/v1/auth/activate", {
            "username": username, "password": "test-password-only-123", "invitation": code})
        self.assertEqual(200, status)
        return data

    def test_accounts_isolate_read_write_changes_assets_and_deletes(self):
        first = self.activate("owner-a", legacy=True)
        second = self.activate("owner-b")
        a, b = first["access_token"], second["access_token"]
        note = {"id": "same-id-123", "comment": "first user's note", "ai_access": "deny"}
        note["assets"] = {"original": {"content_type": "image/png", "data_base64": base64.b64encode(minimal_png()).decode()}}
        self.assertEqual(200, self.request("PUT", "/v1/captures/same-id-123", note, a)[0])
        self.assertEqual(404, self.request("GET", "/v1/captures/same-id-123", token=b)[0])
        self.assertEqual([], self.request("GET", "/v1/changes", token=b)[1]["changes"])
        self.assertEqual([], self.request("GET", "/v1/search?q=first", token=b)[1]["records"])
        self.assertEqual(404, self.request("GET", "/v1/captures/same-id-123/assets/original", token=b)[0])
        self.assertEqual(minimal_png(), self.request("GET", "/v1/captures/same-id-123/assets/original", token=a)[1])
        status, exported = self.request("GET", "/v1/export", token=b)
        self.assertEqual(200, status)
        with zipfile.ZipFile(io.BytesIO(exported)) as archive:
            self.assertFalse(any("same-id-123" in name for name in archive.namelist()))
        self.assertEqual(404, self.request("DELETE", "/v1/captures/same-id-123", token=b, headers={"If-Match": "1"})[0])
        note["comment"] = "second user's note"
        note.pop("assets")
        self.assertEqual(200, self.request("PUT", "/v1/captures/same-id-123", note, b)[0])
        self.assertEqual(404, self.request("GET", "/v1/captures/same-id-123/assets/original", token=b)[0])
        self.assertEqual("first user's note", self.request("GET", "/v1/captures/same-id-123", token=a)[1]["comment"])
        self.assertEqual(200, self.request("DELETE", "/v1/captures/same-id-123", token=a, headers={"If-Match": "1"})[0])
        self.assertEqual(200, self.request("GET", "/v1/captures/same-id-123", token=b)[0])
        self.assertEqual("delete", self.request("GET", "/v1/changes", token=a)[1]["changes"][-1]["operation"])

    def test_existing_vault_requires_single_use_owner_invitation(self):
        self.store.put("legacy-note-123", {"comment": "existing note"})
        code = self.accounts.invite(legacy_owner=True)
        body = {"username": "my-account", "password": "test-password-only-123", "invitation": code}
        status, session = self.request("POST", "/v1/auth/activate", body)
        self.assertEqual(200, status)
        self.assertEqual(200, self.request("GET", "/v1/captures/legacy-note-123", token=session["access_token"])[0])
        body["username"] = "another-account"
        self.assertEqual(403, self.request("POST", "/v1/auth/activate", body)[0])
        with self.assertRaises(AuthError):
            self.accounts.invite(legacy_owner=True)
        self.assertEqual(403, self.request("GET", "/v1/changes", token="write-test-token")[0])

    def test_login_logout_expiry_and_passwords_are_hashed(self):
        first = self.activate("my-account")
        status, second = self.request("POST", "/v1/auth/login", {"username": "MY-ACCOUNT", "password": "test-password-only-123"})
        self.assertEqual(200, status)
        self.assertEqual(first["account_id"], second["account_id"])
        self.assertNotEqual(first["access_token"], second["access_token"])
        self.assertEqual(401, self.request("POST", "/v1/auth/login", {"username": "my-account", "password": "wrong"})[0])
        self.assertEqual(200, self.request("POST", "/v1/auth/logout", {}, first["access_token"])[0])
        self.assertEqual(401, self.request("GET", "/v1/changes", token=first["access_token"])[0])
        self.assertEqual(200, self.request("GET", "/v1/changes", token=second["access_token"])[0])
        with self.accounts.db() as db:
            row = db.execute("SELECT * FROM accounts").fetchone()
            self.assertNotEqual("test-password-only-123", row["password_hash"])
            db.execute("UPDATE sessions SET expires_at=0")
        self.assertEqual(401, self.request("GET", "/v1/changes", token=second["access_token"])[0])

    def test_throttle_and_invalid_activation(self):
        self.assertEqual(403, self.request("POST", "/v1/auth/activate", {
            "username": "new-user", "password": "test-password-only-123", "invitation": "not-a-real-invitation-code"})[0])
        for _ in range(12):
            self.accounts.throttle("test-ip", "limited-user")
        with self.assertRaises(AuthError) as result:
            self.accounts.throttle("test-ip", "limited-user")
        self.assertEqual(429, result.exception.status)
