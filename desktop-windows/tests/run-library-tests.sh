#!/usr/bin/env bash
set -euo pipefail
test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "${test_dir}/.." && pwd)"
build_dir="${project_dir}/build-library-tests"
mkdir -p "${build_dir}"
"${MINGW_CXX:-x86_64-w64-mingw32-g++}" -std=c++17 -O1 -static -municode -DUNICODE -D_UNICODE -DNOMINMAX \
    -I"${project_dir}/build-mingw/deps" "${test_dir}/library_test.cpp" \
    "${project_dir}/src/library.cpp" "${project_dir}/src/sync.cpp" \
    -o "${build_dir}/library-tests.exe" -lole32 -lcrypt32 -lbcrypt -lwinhttp -lws2_32
test_prefix="$(mktemp -d /tmp/mnote-library-tests.XXXXXX)"
cleanup() {
    env WINEPREFIX="${test_prefix}" wineserver -k >/dev/null 2>&1 || true
    env WINEPREFIX="${test_prefix}" wineserver -w >/dev/null 2>&1 || true
    # The path is the explicit, unique test prefix returned by mktemp above.
    if [[ "${test_prefix}" == /tmp/mnote-library-tests.* ]]; then rm -rf -- "${test_prefix}"; fi
}
trap cleanup EXIT
xvfb-run -a env WINEDEBUG=-all WINEPREFIX="${test_prefix}" \
    wine "${build_dir}/library-tests.exe" 'C:\mnote-test-data'
