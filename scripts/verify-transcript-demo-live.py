"""Opt-in end-to-end smoke check using only the demo's original CC0 speech.

Usage: python3 scripts/verify-transcript-demo-live.py
The token is read from the protected service environment, never printed.
"""
import hashlib
import json
from pathlib import Path
import time
import urllib.error
import urllib.request

BASE = "https://chenyu.online/mnote-transcript-api"
settings = {}
for line in Path("/etc/mnote-transcription-demo/service.env").read_text().splitlines():
    if line and not line.startswith("#") and "=" in line:
        key, value = line.split("=", 1)
        settings[key] = value.strip('"')
owner = hashlib.sha256(b"mnote-transcript-live-qa").hexdigest()


def request(path, method="GET", body=None, other_owner=False):
    headers = {"Authorization": "Bearer " + settings["MNOTE_TRANSCRIPT_TOKEN"],
               "X-Owner-Id": "other-qa-owner" if other_owner else owner}
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return response.status, response.read(), response.headers
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.headers


code, raw, _ = request("/v1/config")
config = json.loads(raw)
assert code == 200 and config["ready"] is True, (code, config)
payload = {"share_text": config["demo_sample"]["url"], "rights_basis": "open_license",
           "rights_note": "Original demo recording dedicated to CC0: " + config["demo_sample"]["license_url"],
           "agreement_version": config["agreement_version"], "consent": True}
denied, _, _ = request("/v1/jobs", "POST", {**payload, "consent": False})
assert denied == 400, denied
code, raw, _ = request("/v1/jobs", "POST", payload)
assert code == 202, (code, raw.decode())
job = json.loads(raw)["job"]
ident = job["id"]
try:
    denied, _, _ = request("/v1/jobs/" + ident, other_owner=True)
    assert denied == 404, denied
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        code, raw, _ = request("/v1/jobs/" + ident)
        job = json.loads(raw)["job"]
        print(json.dumps({"status": job["status"], "stage": job["stage"], "progress": job["progress"]}), flush=True)
        if job["status"] in ("completed", "failed"):
            break
        time.sleep(2)
    assert job["status"] == "completed" and job["text"].strip(), job
    assert "recording" in job["text"].lower(), job["text"]
    assert job["rights"]["verified"] is False
    exports = {}
    for kind in ("txt", "md", "srt"):
        code, content, headers = request("/v1/jobs/" + ident + "/download?format=" + kind)
        assert code == 200 and content.strip(), (kind, code)
        assert "attachment" in headers.get("Content-Disposition", ""), kind
        exports[kind] = len(content)
    print(json.dumps({"completed": True, "text": job["text"], "segments": len(job["segments"]),
                      "exports_bytes": exports, "consent_enforced": True, "owner_isolation": True}, ensure_ascii=False), flush=True)
finally:
    code, _, _ = request("/v1/jobs/" + ident, "DELETE")
    assert code == 200, code
    code, _, _ = request("/v1/jobs/" + ident)
    assert code == 404, code
    print("QA job deleted; subsequent read returns 404.", flush=True)
