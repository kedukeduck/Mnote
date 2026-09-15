#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
installer="${repo_dir}/deliverables/mnote-windows-1.6.0-test/Mnote-Windows-1.6.0-test-Setup.exe"
[[ -f "${installer}" ]]
test_dir="$(mktemp -d /tmp/mnote-installer-smoke.XXXXXX)"
cleanup() {
    env WINEPREFIX="${test_dir}" wineserver -k >/dev/null 2>&1 || true
    env WINEPREFIX="${test_dir}" wineserver -w >/dev/null 2>&1 || true
    case "${test_dir}" in /tmp/mnote-installer-smoke.*) rm -rf -- "${test_dir}" ;; esac
}
trap cleanup EXIT
export WINEDEBUG=-all WINEPREFIX="${test_dir}"
xvfb-run -a bash -c '
    set -euo pipefail
    wineboot -u >/dev/null 2>&1
    local_dir="$(find "${WINEPREFIX}/drive_c/users" -type d -path "*/AppData/Local" -print -quit)"
    mkdir -p "${local_dir}/PersonalCapture/Inbox"
    cp "$2/desktop-windows/README.md" "${local_dir}/PersonalCapture/Inbox/preserve-this-record.txt"
    wine "$1" /S "/D=C:\Mnote-Installer-Test"
    cmp "$2/desktop-windows/build-mingw/mnote.exe" "${WINEPREFIX}/drive_c/Mnote-Installer-Test/mnote.exe"
    # Installing again exercises the upgrade path without deleting the local vault.
    wine "$1" /S "/D=C:\Mnote-Installer-Test"
    cmp "$2/desktop-windows/build-mingw/mnote.exe" "${WINEPREFIX}/drive_c/Mnote-Installer-Test/mnote.exe"
    wine "${WINEPREFIX}/drive_c/Mnote-Installer-Test/Uninstall.exe" /S
    for _ in $(seq 1 100); do [[ ! -f "${WINEPREFIX}/drive_c/Mnote-Installer-Test/mnote.exe" ]] && break; sleep 0.1; done
    [[ ! -f "${WINEPREFIX}/drive_c/Mnote-Installer-Test/mnote.exe" ]]
    cmp "$2/desktop-windows/README.md" "${local_dir}/PersonalCapture/Inbox/preserve-this-record.txt"
    echo "installer smoke: install, upgrade, uninstall, exact payload and existing vault preservation passed"
' _ "${installer}" "${repo_dir}"
