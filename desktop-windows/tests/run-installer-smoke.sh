#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
installer="${repo_dir}/deliverables/mnote-windows-1.10.1-test/Mnote-Windows-1.10.1-test-Setup.exe"
[[ -f "${installer}" ]]
mkdir -p "${repo_dir}/desktop-windows/build-installer-tests"
x86_64-w64-mingw32-g++ -std=c++17 -static -municode "${repo_dir}/desktop-windows/tests/installer_handoff.cpp" \
    -o "${repo_dir}/desktop-windows/build-installer-tests/handoff.exe" -luser32
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
    # Simulate graceful updater handoff: the installer must wait, never terminate the old app.
    wine "$2/desktop-windows/build-installer-tests/handoff.exe" host &
    host_pid=$!
    ready=false
    for _ in $(seq 1 100); do
        if wine "$2/desktop-windows/build-installer-tests/handoff.exe" ready; then ready=true; break; fi
        sleep 0.1
    done
    [[ "$ready" == true ]]
    old_pid="$(wine "$2/desktop-windows/build-installer-tests/handoff.exe" pid | tr -d "\r")"
    [[ "$old_pid" =~ ^[0-9]+$ ]]
    wine "$1" /UPDATE "/UPDATEPID=$old_pid" /S "/D=C:\Mnote-Installer-Test" &
    update_pid=$!
    sleep 2
    kill -0 "$update_pid"
    wine "$2/desktop-windows/build-installer-tests/handoff.exe" ready
    wine "$2/desktop-windows/build-installer-tests/handoff.exe" close
    sleep 0.5
    kill -0 "$update_pid"
    wait "$host_pid"
    wait "$update_pid"
    cmp "$2/desktop-windows/build-mingw/mnote.exe" "${WINEPREFIX}/drive_c/Mnote-Installer-Test/mnote.exe"
    wine "${WINEPREFIX}/drive_c/Mnote-Installer-Test/Uninstall.exe" /S
    for _ in $(seq 1 100); do [[ ! -f "${WINEPREFIX}/drive_c/Mnote-Installer-Test/mnote.exe" ]] && break; sleep 0.1; done
    [[ ! -f "${WINEPREFIX}/drive_c/Mnote-Installer-Test/mnote.exe" ]]
    cmp "$2/desktop-windows/README.md" "${local_dir}/PersonalCapture/Inbox/preserve-this-record.txt"
    echo "installer smoke: install, graceful update handoff, uninstall, exact payload and existing vault preservation passed"
' _ "${installer}" "${repo_dir}"
