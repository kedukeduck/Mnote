#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
build_dir="${repo_dir}/desktop-windows/build-gui-smoke"
mkdir -p "${build_dir}"
x86_64-w64-mingw32-g++ -std=c++17 -O1 -static -municode "${repo_dir}/desktop-windows/tests/workspace_driver.cpp" -o "${build_dir}/workspace-driver.exe" -luser32 -lgdi32 -lgdiplus
test_dir="$(mktemp -d /tmp/mnote-workspace-gui.XXXXXX)"
if [[ -d /root/.cache/mnote-build-tools/root/usr/share/fonts/truetype/wqy ]]; then
    export WINEFONTPATH=/root/.cache/mnote-build-tools/root/usr/share/fonts/truetype/wqy
fi
cleanup() {
    env WINEPREFIX="${test_dir}/wine" wineserver -k >/dev/null 2>&1 || true
    env WINEPREFIX="${test_dir}/wine" wineserver -w >/dev/null 2>&1 || true
    case "${test_dir}" in /tmp/mnote-workspace-gui.*) rm -rf -- "${test_dir}" ;; esac
}
trap cleanup EXIT
xvfb-run -a -s '-screen 0 1280x1000x24' bash "${repo_dir}/desktop-windows/tests/workspace-gui-session.sh" "${repo_dir}" "${test_dir}"
