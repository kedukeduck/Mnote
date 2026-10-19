"""Operator-only metadata/media-header check; never downloads or transcribes a body.

Uses the installed core/helper configuration. No credential, signed media query,
Cookie or transcript is printed. A 200 header is not full-ASR success.
"""
import http.client
import json
import os
from pathlib import Path
import sys
import time
import urllib.parse

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "transcription-service"))
import service as api


def check(share, config):
    source = api.parse_share_text(share, config.direct_hosts)
    if source["platform"] != "douyin":
        raise api.ServiceError("unsupported_source")
    started = time.monotonic()
    urls, public = api.MediaResolver(api.SafeClient(), config).resolve_candidates(source)
    probes, readable = [], False
    for url in urls[:3]:
        parts = api.validate_url(url, lambda host: api.cdn_allowed(host, "douyin", ()))
        addresses = api.bounded_addresses(parts.hostname)
        connection = api.PinnedHTTPSConnection(parts.hostname, addresses[0], 8)
        response = None
        try:
            connection.request("GET", urllib.parse.urlunsplit(("", "", parts.path, parts.query, "")),
                headers={"User-Agent": "MnoteAuthorizedTranscript/1.0", "Accept-Encoding": "identity"})
            response = connection.getresponse()
            content_type = response.getheader("Content-Type", "").split(";", 1)[0]
            probes.append({"host": parts.hostname, "status": response.status, "content_type": content_type,
                           "content_length": response.getheader("Content-Length")})
            readable = response.status == 200 and (content_type.startswith(("audio/", "video/")) or content_type == "application/octet-stream")
            if readable or response.status == 429:
                break
        except (OSError, http.client.HTTPException):
            probes.append({"host": parts.hostname, "error": "network_failed"})
        finally:
            if response is not None:
                response.close()
            connection.close()
    return {"currently_readable": readable, "duration_seconds": public["duration_seconds"],
            "seconds": round(time.monotonic() - started, 1), "no_download_cookies": True,
            "media_body_not_read": True, "probes": probes}


if __name__ == "__main__":
    if not 1 <= len(sys.argv[1:]) <= 3:
        raise SystemExit("Usage: python3 scripts/verify-transcript-douyin-access.py HTTPS_SHARE_LINK [LINK2 [LINK3]]")
    env_file = Path("/etc/mnote-transcription-demo/service.env")
    values = dict(line.split("=", 1) for line in env_file.read_text().splitlines() if line and not line.startswith("#") and "=" in line)
    os.environ.update(values)
    config = api.Config.from_env()
    okay = True
    for index, share in enumerate(sys.argv[1:], 1):
        try:
            result = check(share, config)
            okay = okay and result["currently_readable"]
        except api.ServiceError as error:
            result = {"currently_readable": False, "error": error.code}
            okay = False
        print(json.dumps({"index": index, **result}, ensure_ascii=False), flush=True)
    raise SystemExit(0 if okay else 1)
