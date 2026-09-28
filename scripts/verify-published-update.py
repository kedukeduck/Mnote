#!/usr/bin/env python3
"""Read-only public update verification against the locally verified packages."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.request import HTTPRedirectHandler, Request, build_opener

BASE = "https://chenyu.online/heartnote-capture/updates/"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--platform", choices=("android", "windows", "both"), default="both")
    args = parser.parse_args()
    version = args.version
    assert re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-test)?", version)
    opener = build_opener(NoRedirect())

    def get(url):
        assert url.startswith(BASE)
        response = opener.open(Request(url, headers={"User-Agent": "Mnote-Updater",
            "Cache-Control": "no-cache"}), timeout=30)
        assert response.status == 200 and response.url == url
        return response

    with get(BASE + "releases.json") as response:
        releases = json.load(response)
    repo = Path(__file__).resolve().parents[1]
    download_names = []
    for platform, names in (
        ("android", [f"Mnote-Android-{version}.apk", "SHA256SUMS"]),
        ("windows", [f"Mnote-Windows-{version}-Setup.exe",
                     f"Mnote-Windows-{version}-Portable.zip", "SHA256SUMS"]),
    ):
        if args.platform not in ("both", platform):
            continue
        download_names.extend(name for name in names if name != "SHA256SUMS")
        tag = f"mnote-{platform}-v{version}"
        matches = [r for r in releases if r["tag_name"] == tag]
        assert len(matches) == 1
        release = matches[0]
        assert not release["draft"] and release["prerelease"] == version.endswith("-test")
        assets = {asset["name"]: asset for asset in release["assets"]}
        assert set(assets) == set(names)
        for name in names:
            asset = assets[name]
            local = repo / "deliverables" / f"mnote-{platform}-{version}" / name
            with local.open("rb") as stream:
                local_hash = hashlib.file_digest(stream, "sha256").hexdigest()
            assert asset["size"] == local.stat().st_size
            assert asset["digest"] == "sha256:" + local_hash
            url = BASE + "files/" + tag + "/" + name
            assert asset["browser_download_url"] == url
            digest, size = hashlib.sha256(), 0
            with get(url) as response:
                assert int(response.headers["Content-Length"]) == asset["size"]
                while chunk := response.read(256 * 1024):
                    size += len(chunk)
                    assert size <= asset["size"]
                    digest.update(chunk)
            assert size == asset["size"] and digest.hexdigest() == local_hash
            print(f"Verified {platform} {name}: {size} bytes; SHA-256 {local_hash}; no redirect")
    with get(BASE) as response:
        page = response.read().decode("utf-8")
    for name in download_names:
        assert name in page
    print("Public manifest, download page and every package match verified local artifacts.")


if __name__ == "__main__":
    main()
