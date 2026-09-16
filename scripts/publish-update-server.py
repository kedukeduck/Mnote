#!/usr/bin/env python3
"""Atomically publish local, versioned packages; never fetch or proxy GitHub."""
import argparse
import fcntl
import hashlib
import json
import os
import re
import shutil
import tempfile
from pathlib import Path

BASE = "https://chenyu.online/heartnote-capture/updates/"


def publish(root, platform, version, artifacts, notes):
    if platform not in ("android", "windows"):
        raise ValueError("Invalid platform")
    if not re.fullmatch(r"(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})(-test)?", version):
        raise ValueError("Invalid version")
    tag = f"mnote-{platform}-v{version}"
    expected = {f"Mnote-Android-{version}.apk", "SHA256SUMS"} if platform == "android" else {
        f"Mnote-Windows-{version}-Setup.exe", f"Mnote-Windows-{version}-Portable.zip", "SHA256SUMS"}
    if {p.name for p in artifacts} != expected or len(artifacts) != len(expected):
        raise ValueError("Unexpected package set")
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    with (root / ".publish.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        manifest = root / "releases.json"
        releases = json.loads(manifest.read_text()) if manifest.exists() else []
        if not isinstance(releases, list) or any(not isinstance(r, dict) or "tag_name" not in r for r in releases):
            raise ValueError("Invalid existing manifest")
        if len([r for r in releases if r["tag_name"] != tag]) >= 100:
            raise ValueError("Manifest limit reached; archive old entries deliberately")
        stage = Path(tempfile.mkdtemp(prefix=".staging-", dir=root))
        try:
            assets = []
            for file in artifacts:
                if not file.is_file() or not 0 < file.stat().st_size <= 128 * 1024 * 1024:
                    raise ValueError("Invalid package size")
                target = stage / file.name
                shutil.copyfile(file, target)
                with target.open("rb") as stream:
                    digest = hashlib.file_digest(stream, "sha256").hexdigest()
                    stream.seek(0)
                    magic = stream.read(2)
                if file.suffix in (".apk", ".zip") and magic != b"PK":
                    raise ValueError("APK is not a ZIP package")
                if file.suffix == ".exe" and magic != b"MZ":
                    raise ValueError("Installer is not PE")
                target.chmod(0o644)
                assets.append({"name": file.name, "size": target.stat().st_size, "digest": "sha256:" + digest,
                               "browser_download_url": BASE + "files/" + tag + "/" + file.name})
            sums = {}
            for line in (stage / "SHA256SUMS").read_text().splitlines():
                digest, name = line.split(maxsplit=1)
                sums[name.strip()] = digest
            for asset in assets:
                if asset["name"] != "SHA256SUMS" and sums.get(asset["name"]) != asset["digest"][7:]:
                    raise ValueError("SHA256SUMS mismatch")
            destination = root / "files" / tag
            destination.parent.mkdir(exist_ok=True)
            if destination.exists():
                for asset in assets:
                    with (destination / asset["name"]).open("rb") as stream:
                        if hashlib.file_digest(stream, "sha256").hexdigest() != asset["digest"][7:]:
                            raise ValueError("Published version is immutable; bump version")
            else:
                stage.chmod(0o755)
                stage.rename(destination)
            item = {"tag_name": tag, "draft": False, "prerelease": version.endswith("-test"),
                    "body": notes, "assets": assets}
            releases = [item] + [r for r in releases if r["tag_name"] != tag]
            if len(releases) > 100:
                raise ValueError("Manifest limit reached; archive old entries deliberately")
            fd, temporary = tempfile.mkstemp(prefix=".manifest-", dir=root)
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as out:
                    json.dump(releases, out, ensure_ascii=False, indent=2); out.flush(); os.fsync(out.fileno())
                os.chmod(temporary, 0o644)
                os.replace(temporary, manifest)
            finally:
                Path(temporary).unlink(missing_ok=True)
        finally:
            if stage.exists():
                shutil.rmtree(stage)  # Only the exact temporary directory allocated above.


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--platform", choices=["android", "windows"], required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("artifacts", type=Path, nargs="+")
    args = parser.parse_args()
    publish(args.root, args.platform, args.version, args.artifacts, args.notes.read_text(encoding="utf-8"))
    print(f"Published {args.platform} {args.version}; packages immutable, manifest replaced atomically.")
