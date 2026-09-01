#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

find_android_tool() {
  local tool="$1"
  local candidate=""
  if command -v "${tool}" >/dev/null 2>&1; then
    command -v "${tool}"
    return 0
  fi
  for candidate in \
    "${ANDROID_SDK_ROOT:-}/build-tools/35.0.0/${tool}" \
    "${ANDROID_HOME:-}/build-tools/35.0.0/${tool}" \
    "/root/.cache/android-sdk-couple/build-tools/35.0.0/${tool}"; do
    if [[ -n "${candidate}" && -x "${candidate}" ]]; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done
  return 1
}

echo "[1/7] Android build, unit tests, and lint"
(
  cd "${repo_dir}"
  ./gradlew testDebugUnitTest assembleDebug lintDebug
)

echo "[2/7] Windows x64 cross-build"
(
  cd "${repo_dir}"
  bash desktop-windows/build-mingw.sh
  windows_exe=""
  for candidate in \
    desktop-windows/build-mingw/mnote.exe \
    desktop-windows/build-mingw/personal-capture.exe; do
    if [[ -f "${candidate}" ]]; then
      windows_exe="${candidate}"
      break
    fi
  done
  if [[ -z "${windows_exe}" ]]; then
    echo "Windows executable was not produced" >&2
    exit 1
  fi
  file "${windows_exe}" | grep -q "PE32+ executable (GUI) x86-64"
  if x86_64-w64-mingw32-objdump -p "${windows_exe}" \
      | grep -Eiq 'libgcc|libstdc\+\+|libwinpthread'; then
    echo "Windows executable unexpectedly depends on a MinGW runtime DLL" >&2
    exit 1
  fi
)

echo "[3/7] Windows frozen-overlay GUI smoke test"
(
  cd "${repo_dir}"
  bash desktop-windows/tests/run-gui-smoke.sh
)

echo "[4/7] Windows sync boundary and WinHTTP smoke test"
(
  cd "${repo_dir}"
  bash desktop-windows/tests/run-sync-smoke.sh
)

echo "[5/7] Browser extension manifest and module smoke tests"
(
  cd "${repo_dir}/browser-extension"
  python3 -m json.tool manifest.json >/dev/null
  for module in ./*.js; do
    node --check "${module}"
  done
  npm test
)

echo "[6/7] Capture Server data, HTTP, Web, and MCP tests"
(
  cd "${repo_dir}/capture-server"
  python3 -m py_compile src/heartnote_capture/*.py
  if [[ -d /tmp/heartnote-mcp-py ]]; then
    PYTHONPATH="/tmp/heartnote-mcp-py:src" python3 -m unittest discover -s tests -v
  else
    PYTHONPATH=src python3 -m unittest discover -s tests -v
  fi
)

echo "[7/7] Android manifest smoke check"
aapt_bin="$(find_android_tool aapt)" || {
  echo "aapt was not found" >&2
  exit 1
}
apk_path="${repo_dir}/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "${apk_path}" ]]; then
  echo "debug APK is missing" >&2
  exit 1
fi
manifest_dump="$(${aapt_bin} dump xmltree "${apk_path}" AndroidManifest.xml)"
badging_dump="$(${aapt_bin} dump badging "${apk_path}")"
grep -q "package: name='com.codex.mnote'" <<<"${badging_dump}"
grep -q "application-label:'Mnote'" <<<"${badging_dump}"
if grep -Eiq 'lovetools|com\.codex\.heartnote' <<<"${badging_dump}${manifest_dump}"; then
  echo "Android APK still contains the old LoveTools application identity" >&2
  exit 1
fi
for component in \
  CaptureAccessibilityService \
  CaptureQuickSettingsTileService \
  CaptureTriggerActivity \
  CaptureEditorActivity \
  CaptureInboxActivity; do
  if ! grep -q "${component}" <<<"${manifest_dump}"; then
    echo "Android manifest is missing ${component}" >&2
    exit 1
  fi
done

echo "Mnote V1 automated verification passed"
