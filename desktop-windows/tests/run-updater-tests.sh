#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
build_dir="${repo_dir}/desktop-windows/build-updater-tests"
mkdir -p "${build_dir}"
"${MINGW_CXX:-x86_64-w64-mingw32-g++}" -std=c++17 -O1 -static -municode -DUNICODE -D_UNICODE -DNOMINMAX \
  -I"${repo_dir}/desktop-windows/build-mingw/deps" "${repo_dir}/desktop-windows/tests/updater_test.cpp" \
  "${repo_dir}/desktop-windows/src/updater.cpp" "${repo_dir}/desktop-windows/src/library.cpp" \
  "${repo_dir}/desktop-windows/src/sync.cpp" -o "${build_dir}/updater-tests.exe" \
  -lole32 -lcrypt32 -lbcrypt -lwinhttp -lws2_32 -lshell32
test_dir="$(mktemp -d /tmp/mnote-updater-tests.XXXXXX)"
cleanup() {
  env WINEPREFIX="${test_dir}" wineserver -k >/dev/null 2>&1 || true
  env WINEPREFIX="${test_dir}" wineserver -w >/dev/null 2>&1 || true
  case "${test_dir}" in /tmp/mnote-updater-tests.*) rm -rf -- "${test_dir}" ;; esac
}
trap cleanup EXIT
args=('C:\mnote-updater-tests')
if [[ "${1:-}" == --live ]]; then
  curl --fail --silent --show-error --max-time 30 \
    'https://chenyu.online/heartnote-capture/updates/releases.json' -o "${test_dir}/public-releases.json"
  args+=("Z:${test_dir}/public-releases.json")
fi
xvfb-run -a env WINEDEBUG=-all WINEPREFIX="${test_dir}" \
  wine "${build_dir}/updater-tests.exe" "${args[@]}"
