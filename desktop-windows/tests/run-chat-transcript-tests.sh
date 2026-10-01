#!/usr/bin/env bash
set -euo pipefail
test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "${test_dir}/.." && pwd)"
build_dir="${project_dir}/build-chat-transcript-tests"
mkdir -p "${build_dir}"
"${MINGW_CXX:-x86_64-w64-mingw32-g++}" -std=c++17 -O1 -static -municode -DUNICODE -D_UNICODE -DNOMINMAX \
  -Wall -Wextra -Wconversion -Wshadow "${test_dir}/chat_transcript_test.cpp" \
  "${project_dir}/src/chat_transcript.cpp" -o "${build_dir}/chat-transcript-tests.exe" \
  -lcomctl32 -luser32 -lgdi32
test_prefix="$(mktemp -d /tmp/mnote-chat-transcript-tests.XXXXXX)"
cleanup() {
  env WINEPREFIX="${test_prefix}" wineserver -k >/dev/null 2>&1 || true
  env WINEPREFIX="${test_prefix}" wineserver -w >/dev/null 2>&1 || true
  case "${test_prefix}" in /tmp/mnote-chat-transcript-tests.*) rm -rf -- "${test_prefix}" ;; esac
}
trap cleanup EXIT
xvfb-run -a -s '-screen 0 1280x1000x24' env WINEDEBUG=-all WINEPREFIX="${test_prefix}" \
  timeout 100s wine "${build_dir}/chat-transcript-tests.exe"
