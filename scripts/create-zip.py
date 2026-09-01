#!/usr/bin/env python3
"""Create a sorted ZIP from one directory without following symlinks."""

from __future__ import annotations

import argparse
import fnmatch
import sys
import zipfile
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--prefix",
        help="archive path prefix; defaults to the source directory name",
    )
    parser.add_argument("--exclude", action="append", default=[])
    return parser.parse_args()


def is_excluded(relative: str, patterns: list[str]) -> bool:
    return any(fnmatch.fnmatchcase(relative, pattern) for pattern in patterns)


def main() -> int:
    args = parse_args()
    source = args.source.resolve(strict=True)
    output = args.output.resolve(strict=False)
    if not source.is_dir():
        raise ValueError("source must be a directory")
    if output.exists():
        raise FileExistsError(f"output already exists: {output}")
    if output == source or source in output.parents:
        raise ValueError("output cannot be inside the source directory")

    prefix = source.name if args.prefix is None else args.prefix.strip("/")
    if prefix in {".", ".."} or "/../" in f"/{prefix}/":
        raise ValueError("archive prefix is unsafe")

    files: list[tuple[Path, str]] = []
    for candidate in sorted(source.rglob("*")):
        relative = candidate.relative_to(source).as_posix()
        if is_excluded(relative, args.exclude):
            continue
        if candidate.is_symlink():
            raise ValueError(f"refusing to archive symlink: {candidate}")
        if not candidate.is_file():
            continue
        archive_name = f"{prefix}/{relative}" if prefix else relative
        files.append((candidate, archive_name))
    if not files:
        raise ValueError("source contains no files after exclusions")

    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        with zipfile.ZipFile(
            output,
            "x",
            compression=zipfile.ZIP_DEFLATED,
            compresslevel=9,
            strict_timestamps=False,
        ) as archive:
            for candidate, archive_name in files:
                archive.write(candidate, archive_name)
    except Exception:
        output.unlink(missing_ok=True)
        raise
    print(f"created {output} with {len(files)} files")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FileExistsError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1) from error
