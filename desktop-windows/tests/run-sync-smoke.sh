#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "${test_dir}/.." && pwd)"
build_dir="${project_dir}/build-sync-smoke"
compiler="${MINGW_CXX:-x86_64-w64-mingw32-g++}"
port="${HEARTNOTE_SMOKE_PORT:-18765}"

for command in "${compiler}" python3 wine xvfb-run; do
    if ! command -v "${command}" >/dev/null 2>&1; then
        echo "error: ${command} was not found" >&2
        exit 1
    fi
done

mkdir -p "${build_dir}"
"${compiler}" \
    -std=c++17 -O2 -static -static-libgcc -static-libstdc++ -municode \
    "${test_dir}/sync_smoke.cpp" "${project_dir}/src/sync.cpp" \
    -o "${build_dir}/sync-smoke.exe" -lwinhttp -lws2_32

wine_prefix="$(mktemp -d)"
server_pid=""
cleanup() {
    if [[ -n "${server_pid}" ]] && kill -0 "${server_pid}" 2>/dev/null; then
        kill "${server_pid}" 2>/dev/null || true
        wait "${server_pid}" 2>/dev/null || true
    fi
    env WINEPREFIX="${wine_prefix}" wineserver -k >/dev/null 2>&1 || true
    env WINEPREFIX="${wine_prefix}" wineserver -w >/dev/null 2>&1 || true
    rm -rf -- "${wine_prefix}"
}
trap cleanup EXIT

python3 "${test_dir}/mock_server.py" "${port}" &
server_pid="$!"

xvfb-run -a env WINEDEBUG=-all WINEPREFIX="${wine_prefix}" \
    wine "${build_dir}/sync-smoke.exe" "${port}"
wait "${server_pid}"
server_pid=""

echo "sync smoke: passed"
