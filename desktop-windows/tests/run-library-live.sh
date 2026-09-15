#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
build_dir="${repo_dir}/desktop-windows/build-library-tests"
mkdir -p "${build_dir}"
x86_64-w64-mingw32-g++ -std=c++17 -O1 -static -municode -DUNICODE -D_UNICODE -DNOMINMAX \
    -I"${repo_dir}/desktop-windows/src" -I"${repo_dir}/desktop-windows/build-mingw/deps" \
    "${repo_dir}/desktop-windows/tests/library_live.cpp" "${repo_dir}/desktop-windows/src/library.cpp" \
    "${repo_dir}/desktop-windows/src/sync.cpp" -lole32 -lcrypt32 -lbcrypt -lwinhttp -lws2_32 -o "${build_dir}/library-live.exe"
test_dir="$(mktemp -d /tmp/mnote-library-live.XXXXXX)"
server_pid=""
cleanup() {
    if [[ -n "${server_pid}" ]]; then kill "${server_pid}" 2>/dev/null || true; wait "${server_pid}" 2>/dev/null || true; fi
    env WINEPREFIX="${test_dir}/wine" wineserver -k >/dev/null 2>&1 || true
    env WINEPREFIX="${test_dir}/wine" wineserver -w >/dev/null 2>&1 || true
    case "${test_dir}" in /tmp/mnote-library-live.*) rm -rf -- "${test_dir}" ;; esac
}
trap cleanup EXIT
PYTHONPATH="${repo_dir}/capture-server/src" python3 "${repo_dir}/desktop-windows/tests/account_fixture.py" "${test_dir}/data" 0 >"${test_dir}/server.log" 2>&1 &
server_pid=$!
for _ in $(seq 1 60); do [[ -f "${test_dir}/data/port.txt" ]] && break; sleep 0.1; done
port="$(<"${test_dir}/data/port.txt")"
invitation="$(<"${test_dir}/data/invitation.txt")"
env WINEDEBUG=-all WINEPREFIX="${test_dir}/wine" xvfb-run -a wine "${build_dir}/library-live.exe" \
    "Z:${test_dir}/data" "http://127.0.0.1:${port}" "${invitation}"
