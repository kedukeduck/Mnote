#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "${test_dir}/.." && pwd)"
build_dir="${project_dir}/build-gui-smoke"
compiler="${MINGW_CXX:-x86_64-w64-mingw32-g++}"
application="${project_dir}/build-mingw/mnote.exe"
port="${HEARTNOTE_GUI_SMOKE_PORT:-18766}"

for command in "${compiler}" python3 wine wineserver xvfb-run xdotool; do
    if ! command -v "${command}" >/dev/null 2>&1; then
        echo "error: ${command} was not found" >&2
        exit 1
    fi
done
if [[ ! -f "${application}" ]]; then
    echo "error: build the Windows application before running the GUI smoke test" >&2
    exit 1
fi

mkdir -p "${build_dir}"
"${compiler}" \
    -std=c++17 -O2 -static -municode \
    "${test_dir}/gui_trigger.cpp" \
    -o "${build_dir}/gui-trigger.exe" -luser32

wine_prefix="$(mktemp -d /tmp/mnote-gui-smoke.XXXXXX)"
cleanup() {
    env WINEPREFIX="${wine_prefix}" wineserver -k >/dev/null 2>&1 || true
    env WINEPREFIX="${wine_prefix}" wineserver -w >/dev/null 2>&1 || true
    rm -rf -- "${wine_prefix}"
}
trap cleanup EXIT

xvfb-run -a -s '-screen 0 1280x800x24' bash -c '
    set -euo pipefail
    export WINEDEBUG=-all
    export WINEPREFIX="$1"
    application="$2"
    trigger="$3"
    mock_server="$4"
    port="$5"

    wineboot -u >/dev/null 2>&1
    app_data="$(find "${WINEPREFIX}/drive_c/users" -type d \
        -path "*/AppData" -print -quit)"
    local_app_data="${app_data}/Local"
    application_data="${local_app_data}/PersonalCapture"
    mkdir -p "${application_data}"
    printf "%s\n" \
        "[sync]" \
        "server_url=http://127.0.0.1:${port}" \
        "write_token=gui-smoke-write-token" \
        "ai_access=local_only" \
        >"${application_data}/settings.ini"

    wine "${application}" >"${WINEPREFIX}/mnote-app.log" 2>&1 &
    application_pid=$!

    triggered=0
    for _attempt in $(seq 1 100); do
        if wine "${trigger}" capture >/dev/null 2>&1; then
            triggered=1
            break
        fi
        sleep 0.2
    done
    if [[ "${triggered}" != 1 ]]; then
        echo "error: the hidden Mnote message window did not become ready" >&2
        exit 10
    fi

    overlay=""
    for _attempt in $(seq 1 100); do
        overlay="$(xdotool search --onlyvisible --name "Mnote" 2>/dev/null | tail -n 1 || true)"
        if [[ -n "${overlay}" ]]; then
            break
        fi
        sleep 0.2
    done
    if [[ -z "${overlay}" ]]; then
        echo "error: the frozen-screen editor did not become visible" >&2
        exit 11
    fi

    # Drive the actual overlay: select, choose the pen, draw, type, and save.
    xdotool mousemove --window "${overlay}" 180 150 \
        mousedown 1 mousemove --sync --window "${overlay}" 900 620 mouseup 1
    xdotool mousemove --window "${overlay}" 120 24 click 1
    xdotool mousemove --window "${overlay}" 300 300 \
        mousedown 1 mousemove --sync --window "${overlay}" 700 500 mouseup 1
    xdotool mousemove --window "${overlay}" 300 64 click 1 \
        type --delay 20 "wine-smoke-note"
    xdotool key ctrl+Return

    inbox=""
    for _attempt in $(seq 1 100); do
        inbox="$(find "${WINEPREFIX}/drive_c/users" -type d \
            -path "*/AppData/Local/PersonalCapture/Inbox" -print -quit 2>/dev/null || true)"
        if [[ -n "${inbox}" ]] && compgen -G "${inbox}/*.json" >/dev/null; then
            break
        fi
        sleep 0.2
    done
    if [[ -z "${inbox}" ]]; then
        echo "error: the GUI did not create an Inbox record" >&2
        exit 12
    fi

    record="$(find "${inbox}" -maxdepth 1 -name "*.json" -print -quit)"
    if ! grep -Fq "\"sync_state\": \"error\"" "${record}"; then
        echo "error: the first offline upload was not retained as an error" >&2
        exit 13
    fi

    server_result="${WINEPREFIX}/gui-server-result.txt"
    python3 "${mock_server}" "${port}" "${server_result}" &
    server_pid=$!
    wine "${trigger}" sync >/dev/null 2>&1
    retry_succeeded=0
    for _attempt in $(seq 1 100); do
        if [[ -f "${server_result}" ]] && \
            grep -Fq "\"sync_state\": \"synced\"" "${record}"; then
            retry_succeeded=1
            break
        fi
        sleep 0.2
    done
    if [[ "${retry_succeeded}" != 1 ]]; then
        echo "error: the tray retry did not synchronize the retained record" >&2
        kill "${server_pid}" >/dev/null 2>&1 || true
        exit 14
    fi
    wait "${server_pid}"

    # Dismiss the retry summary, open one more overlay, and prove Esc is non-mutating.
    xdotool key Return
    before_cancel_count="$(find "${inbox}" -maxdepth 1 -type f | wc -l)"
    wine "${trigger}" capture >/dev/null 2>&1
    cancel_overlay=""
    for _attempt in $(seq 1 100); do
        cancel_overlay="$(xdotool search --onlyvisible \
            --name "Mnote -" 2>/dev/null | tail -n 1 || true)"
        if [[ -n "${cancel_overlay}" ]]; then
            break
        fi
        sleep 0.2
    done
    if [[ -z "${cancel_overlay}" ]]; then
        echo "error: the cancellation overlay did not become visible" >&2
        exit 15
    fi
    xdotool key Escape
    sleep 0.5
    after_cancel_count="$(find "${inbox}" -maxdepth 1 -type f | wc -l)"
    if [[ "${after_cancel_count}" != "${before_cancel_count}" ]]; then
        echo "error: Esc cancellation created a formal record" >&2
        exit 16
    fi

    python3 - "${record}" <<"PY"
import json
import pathlib
import struct
import sys

record_path = pathlib.Path(sys.argv[1])
record = json.loads(record_path.read_text(encoding="utf-8-sig"))
assert record["schema_version"] == 1
assert record["comment"] == "wine-smoke-note"
assert record["kind"] == "thought"
assert record["ai_access"] == "local_only"
assert record["sync_state"] == "synced"
assert record["sync_error"] == ""
assert record["capture"]["selection_screen"]["width"] == 720
assert record["capture"]["selection_screen"]["height"] == 470
assert len(record["annotations"]) == 1
assert record["annotations"][0]["tool"] == "pen"
assert len(record["annotations"][0]["points"]) >= 2

for asset_name in ("original", "annotated"):
    asset_path = record_path.parent / record["local_files"][asset_name]
    data = asset_path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    assert struct.unpack(">II", data[16:24]) == (720, 470)
    assert len(data) > 100

assert not list(record_path.parent.glob("*.tmp"))
capture_id = record["id"]
print(f"gui smoke: saved {capture_id} with two PNGs, one stroke, and a comment")
PY

    kill "${application_pid}" >/dev/null 2>&1 || true
' _ \
    "${wine_prefix}" \
    "${application}" \
    "${build_dir}/gui-trigger.exe" \
    "${test_dir}/gui_mock_server.py" \
    "${port}"

echo "gui smoke: passed"
