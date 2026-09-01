#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
release_id="${1:-v1.0.0-core}"
if [[ ! "${release_id}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$ ]]; then
  echo "release id must contain only letters, digits, dot, underscore, and dash" >&2
  exit 2
fi

if [[ -n "$(git -C "${repo_dir}" status --porcelain --untracked-files=normal)" ]]; then
  echo "release packaging requires a clean source worktree" >&2
  exit 2
fi

source_commit="$(git -C "${repo_dir}" rev-parse HEAD)"
source_branch="$(git -C "${repo_dir}" branch --show-current)"
created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
delivery_root="${repo_dir}/deliverables"
final_dir="${delivery_root}/mnote-${release_id}"
final_zip="${final_dir}.zip"
if [[ -e "${final_dir}" || -e "${final_zip}" || -e "${final_zip}.sha256" ]]; then
  echo "delivery already exists: ${final_dir}" >&2
  exit 2
fi
mkdir -p "${delivery_root}"
staging_dir="$(mktemp -d "${delivery_root}/.mnote-staging.XXXXXX")"
cleanup() {
  if [[ -d "${staging_dir}" ]]; then
    rm -rf -- "${staging_dir}"
  fi
}
trap cleanup EXIT

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

if [[ ! -f "${repo_dir}/docs/verification-report-v1.0.0-core.md" ]]; then
  echo "verification report is missing" >&2
  exit 1
fi

echo "Running the complete source verification suite"
bash "${repo_dir}/scripts/verify-mnote-v1.sh"

echo "Building Windows portable executable"
(
  cd "${repo_dir}"
  bash desktop-windows/build-mingw.sh
)

mkdir -p \
  "${staging_dir}/android" \
  "${staging_dir}/windows" \
  "${staging_dir}/extension" \
  "${staging_dir}/server" \
  "${staging_dir}/docs"

android_name="Mnote-Android-1.0.0-test.apk"
android_apk="${repo_dir}/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "${android_apk}" ]]; then
  echo "installable Android test APK was not produced" >&2
  exit 1
fi
cp "${android_apk}" "${staging_dir}/android/${android_name}"

apksigner_bin="$(find_android_tool apksigner)" || {
  echo "apksigner was not found" >&2
  exit 1
}
"${apksigner_bin}" verify --verbose --print-certs \
  "${staging_dir}/android/${android_name}" \
  >"${staging_dir}/android/signing-certificate.txt"

windows_source="${repo_dir}/desktop-windows/build-mingw/mnote.exe"
windows_name="Mnote-Windows-x64.exe"
if [[ ! -f "${windows_source}" ]]; then
  echo "Windows executable was not produced" >&2
  exit 1
fi
file "${windows_source}" | grep -q "PE32+ executable (GUI) x86-64"
if x86_64-w64-mingw32-objdump -p "${windows_source}" \
    | grep -Eiq 'libgcc|libstdc\+\+|libwinpthread'; then
  echo "Windows executable unexpectedly depends on a MinGW runtime DLL" >&2
  exit 1
fi
cp "${windows_source}" "${staging_dir}/windows/${windows_name}"
cp "${repo_dir}/desktop-windows/README.md" "${staging_dir}/windows/README-Windows.md"
cp "${repo_dir}/desktop-windows/settings.example.ini" "${staging_dir}/windows/settings.example.ini"

extension_name="Mnote-Chrome-Edge.zip"
python3 "${repo_dir}/scripts/create-zip.py" \
  --source "${repo_dir}/browser-extension" \
  --output "${staging_dir}/extension/${extension_name}" \
  --prefix "" \
  --exclude "tests/*" \
  --exclude "package.json" \
  --exclude "*.log"

server_name="Mnote-Server.zip"
python3 "${repo_dir}/scripts/create-zip.py" \
  --source "${repo_dir}/capture-server" \
  --output "${staging_dir}/server/${server_name}" \
  --prefix "capture-server" \
  --exclude "*/__pycache__/*" \
  --exclude "__pycache__/*" \
  --exclude "*.pyc" \
  --exclude "*/.venv/*" \
  --exclude ".venv/*" \
  --exclude "*/data/*" \
  --exclude "data/*" \
  --exclude "*.egg-info/*" \
  --exclude "*/*.egg-info/*"

cp -R "${repo_dir}/docs/." "${staging_dir}/docs/"

android_sha="$(sha256sum "${staging_dir}/android/${android_name}" | awk '{print $1}')"
windows_sha="$(sha256sum "${staging_dir}/windows/${windows_name}" | awk '{print $1}')"
extension_sha="$(sha256sum "${staging_dir}/extension/${extension_name}" | awk '{print $1}')"
server_sha="$(sha256sum "${staging_dir}/server/${server_name}" | awk '{print $1}')"
certificate_sha="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "${staging_dir}/android/signing-certificate.txt" | head -n 1 | tr '[:upper:]' '[:lower:]')"
if [[ ! "${certificate_sha}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Android signing certificate SHA-256 could not be determined" >&2
  exit 1
fi

printf '%s\n' \
  '{' \
  "  \"release_id\": \"${release_id}\"," \
  "  \"created_at\": \"${created_at}\"," \
  "  \"git_commit\": \"${source_commit}\"," \
  "  \"git_branch\": \"${source_branch}\"," \
  '  "status": "test-candidate",' \
  '  "schema_version": 1,' \
  '  "artifacts": [' \
  "    {\"platform\":\"android\",\"path\":\"android/${android_name}\",\"version\":\"1.0.0-test\",\"application_id\":\"com.codex.mnote\",\"sha256\":\"${android_sha}\",\"signature\":{\"type\":\"apk-certificate\",\"sha256\":\"${certificate_sha}\"}}," \
  "    {\"platform\":\"windows-x64\",\"path\":\"windows/${windows_name}\",\"version\":\"0.1.0\",\"sha256\":\"${windows_sha}\",\"signature\":{\"type\":\"authenticode\",\"status\":\"unsigned-test-build\"}}," \
  "    {\"platform\":\"chrome-edge\",\"path\":\"extension/${extension_name}\",\"version\":\"0.1.0\",\"sha256\":\"${extension_sha}\"}," \
  "    {\"platform\":\"capture-server\",\"path\":\"server/${server_name}\",\"version\":\"0.1.0\",\"sha256\":\"${server_sha}\"}" \
  '  ],' \
  '  "verification": {' \
  '    "automated": "passed_at_packaging",' \
  '    "android_hardware": "pending_real_device",' \
  '    "windows_hardware": "pending_real_windows_11",' \
  '    "complete_v1_proven": false' \
  '  }' \
  '}' \
  >"${staging_dir}/release-manifest.json"
python3 -m json.tool "${staging_dir}/release-manifest.json" >/dev/null

printf '%s\n' \
  "# Mnote ${release_id}" \
  '' \
  "- 源码：\`${source_commit}\`（\`${source_branch}\`）" \
  "- 构建时间：${created_at}" \
  '- 状态：V1 Core 测试候选' \
  '' \
  '包含 Android 11+ APK、Windows 11 x64 便携 EXE、Chrome/Edge 扩展、Capture Server/Web Inbox/只读 MCP 和完整文档。三端均本地优先，可选同步；Android 与 Windows 的普通应用以系统允许的单次截图为统一保底。' \
  '' \
  '自动构建、lint、数据/HTTP/MCP/浏览器契约和 Wine 保存/同步烟测已执行。Android 权限流程仍待真实 Android 11+ 设备验收；多显示器、SmartScreen、UAC/DRM 边界仍待真实 Windows 11 验收。详见 `docs/verification-report-v1.0.0-core.md`。' \
  '' \
  '先校验 `SHA256SUMS`，再按 `docs/installation-delivery.md` 安装。Windows EXE 是未做 Authenticode 商业签名的测试构建；Android APK 使用独立的 Mnote 测试签名，证书信息在 `android/signing-certificate.txt`。' \
  >"${staging_dir}/RELEASE.md"

(
  cd "${staging_dir}"
  find . -type f ! -name SHA256SUMS -print0 \
    | sort -z \
    | xargs -0 sha256sum \
    >SHA256SUMS
  sha256sum -c SHA256SUMS
)

mv "${staging_dir}" "${final_dir}"
staging_dir=""
python3 "${repo_dir}/scripts/create-zip.py" \
  --source "${final_dir}" \
  --output "${final_zip}" \
  --prefix "$(basename "${final_dir}")"
(
  cd "${delivery_root}"
  sha256sum "$(basename "${final_zip}")" \
    >"$(basename "${final_zip}").sha256"
)

trap - EXIT
echo "Packaged: ${final_dir}"
echo "Bundle: ${final_zip}"
echo "Source commit: ${source_commit}"
