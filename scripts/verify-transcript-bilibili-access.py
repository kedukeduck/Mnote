"""Opt-in metadata/header diagnostic; never downloads a media body or transcribes.

Pass operator-selected Bilibili share links as arguments. No service credential,
login, source Cookie, Referer spoofing or query-bearing media URL is printed.
A media GET returning 200 proves current access only, not ASR completion.
"""
import concurrent.futures
import http.client
import json
from pathlib import Path
import sys
import urllib.parse

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "transcription-service"))
import service as api


def check(item):
    index, share = item
    result = {"index": index, "media_body_downloaded": False}
    try:
        source = api.parse_share_text(share, ())
        if source["platform"] != "bilibili":
            raise api.ServiceError("unsupported_source")
        config = api.Config(Path("."), "", Path("."))
        urls, public = api.MediaResolver(api.SafeClient(deadline_seconds=20), config).resolve_candidates(source)
        result["source"] = public
        probes = []
        for url in urls:
            parts = api.validate_url(url, lambda host: api.cdn_allowed(host, "bilibili", ()))
            probe = {"host": parts.hostname}
            addresses = api.bounded_addresses(parts.hostname)
            connection = api.PinnedHTTPSConnection(parts.hostname, addresses[0], 8)
            response = None
            try:
                path = urllib.parse.urlunsplit(("", "", parts.path, parts.query, ""))
                connection.request("GET", path, headers={"User-Agent": "MnoteAuthorizedTranscript/1.0", "Accept-Encoding": "identity"})
                response = connection.getresponse()
                probe["get_status"] = response.status
                probe["content_length"] = response.getheader("Content-Length")
                # Intentionally do not call response.read or read1.
            except (OSError, http.client.HTTPException):
                probe["error"] = "network_failed"
            finally:
                if response is not None:
                    response.close()
                connection.close()
            probes.append(probe)
        result["probes"] = probes
        result["currently_readable"] = any(probe.get("get_status") == 200 for probe in probes)
    except api.ServiceError as error:
        result["error"] = error.code
        result["currently_readable"] = False
    return result


if __name__ == "__main__":
    if not 1 <= len(sys.argv[1:]) <= 3:
        raise SystemExit("Usage: python3 scripts/verify-transcript-bilibili-access.py HTTPS_SHARE_LINK [LINK2 [LINK3]]")
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        results = list(executor.map(check, enumerate(sys.argv[1:], 1)))
    for result in results:
        print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if all(result["currently_readable"] for result in results) else 1)
