#!/usr/bin/env bash
# Package a verified build without changing its existing application identity or signer.
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
apk="${repo_dir}/app/build/outputs/apk/debug/app-debug.apk"
build_tools="${MNOTE_BUILD_TOOLS:-/root/.cache/android-sdk-couple/build-tools/35.0.0}"
[[ -f "$apk" && -x "$build_tools/aapt" && -x "$build_tools/apksigner" ]]
badging="$("$build_tools/aapt" dump badging "$apk")"
[[ "$badging" == *"package: name='com.codex.mnote'"* ]]
version="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+-test$ ]]
output="${repo_dir}/deliverables/mnote-android-${version}"
mkdir -p "$output"
"$build_tools/apksigner" verify --verbose --print-certs "$apk" > "$output/signing-certificate.txt"
target="$output/Mnote-Android-${version}.apk"
if [[ -f "$target" ]]; then
    cmp "$apk" "$target" || { echo 'Existing version differs; bump version before packaging.' >&2; exit 1; }
else
    cp "$apk" "$target"
fi
(cd "$output" && sha256sum "Mnote-Android-${version}.apk" | tee SHA256SUMS)
echo "Test-signed Android package: $target"
