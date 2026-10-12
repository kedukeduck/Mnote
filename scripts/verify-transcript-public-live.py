"""Check the deployed anonymous demo using only its original CC0 test audio.

No service credential or logged-in cookie is used. Two fresh cookie jars simulate
different visitors. The created job is removed even if a check fails.
"""
import http.cookiejar
import json
import time
import urllib.error
import urllib.request

BASE = "https://chenyu.online/mnote-transcript/api/transcripts"
ORIGIN = "https://chenyu.online"


def visitor():
    jar = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar)), jar


def request(opener, path, method="GET", body=None, extra=None):
    headers = {"Origin": ORIGIN} if method in ("POST", "DELETE") else {}
    headers.update(extra or {})
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with opener.open(req, timeout=30) as response:
            return response.status, response.read(), response.headers
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.headers


first, jar = visitor()
second, other_jar = visitor()
status, raw, _ = request(first, "/config")
config = json.loads(raw)
assert status == 200 and config["ready"], (status, config)
cookies = list(jar)
assert len(cookies) == 1
cookie = cookies[0]
assert cookie.name == "__Host-mnote-transcript-visitor" and cookie.secure
assert cookie.path == "/" and not cookie.domain_specified and "HttpOnly" in cookie._rest
assert cookie._rest.get("SameSite") == "Strict"
status, _, _ = request(second, "/config")
assert status == 200 and list(other_jar)[0].value != cookie.value
payload = {"share_text": config["demo_sample"]["url"], "rights_basis": "open_license",
           "rights_note": "Original synthetic speech licensed CC0: " + config["demo_sample"]["license_url"],
           "agreement_version": config["agreement_version"], "consent": True}
status, _, _ = request(first, "/jobs", "POST", payload, {"Origin": "https://external.invalid"})
assert status == 403, status
status, raw, _ = request(first, "/jobs", "POST", payload,
                         {"Authorization": "Bearer untrusted-client-value", "X-Owner-Id": "untrusted-client-owner"})
assert status == 202, (status, raw.decode())
ident = json.loads(raw)["job"]["id"]
try:
    for path, method in (("/jobs/" + ident, "GET"),
                         ("/jobs/" + ident + "/download?format=txt", "GET"),
                         ("/jobs/" + ident, "DELETE")):
        status, _, _ = request(second, path, method)
        assert status == 404, (method, status)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        status, raw, _ = request(first, "/jobs/" + ident)
        assert status == 200, status
        job = json.loads(raw)["job"]
        print(json.dumps({"status": job["status"], "progress": job["progress"]}), flush=True)
        if job["status"] in ("completed", "failed"):
            break
        time.sleep(2)
    assert job["status"] == "completed" and "recording" in job["text"].lower(), job
    exports = {}
    for kind in ("txt", "md", "srt"):
        status, raw, headers = request(first, "/jobs/" + ident + "/download?format=" + kind)
        assert status == 200 and raw and "attachment" in headers.get("Content-Disposition", "")
        exports[kind] = len(raw)
    print(json.dumps({"anonymous_success": True, "text": job["text"], "exports": exports,
                      "visitor_isolation": True, "csrf_enforced": True,
                      "client_credentials_ignored": True, "secure_http_only_cookie": True}), flush=True)
finally:
    status, _, _ = request(first, "/jobs/" + ident, "DELETE")
    assert status == 200, status
    status, _, _ = request(first, "/jobs/" + ident)
    assert status == 404, status
    print("Anonymous QA job deleted; subsequent read returns 404.", flush=True)
