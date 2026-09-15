#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="1.6.0-test"
output="${repo_dir}/deliverables/mnote-windows-${version}"
payload="${output}/Mnote-Windows-${version}"
makensis="${MAKENSIS:-makensis}"
if ! command -v "${makensis}" >/dev/null 2>&1; then echo 'NSIS makensis is required' >&2; exit 1; fi
[[ -f "${repo_dir}/desktop-windows/build-mingw/mnote.exe" ]]
mkdir -p "${payload}"
cp "${repo_dir}/desktop-windows/build-mingw/mnote.exe" "${payload}/mnote.exe"
cp "${repo_dir}/desktop-windows/README.md" "${payload}/README.md"
cp "${repo_dir}/desktop-windows/THIRD-PARTY-NOTICES.txt" "${payload}/THIRD-PARTY-NOTICES.txt"
"${makensis}" -V2 "-DPAYLOAD=${payload}" "-DOUTPUT=${output}/Mnote-Windows-${version}-Setup.exe" "${repo_dir}/desktop-windows/installer.nsi"
python3 - "${payload}" "${output}/Mnote-Windows-${version}-Portable.zip" <<'PY'
import pathlib,sys,zipfile
payload=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(sys.argv[2],'w',zipfile.ZIP_DEFLATED) as archive:
    for name in ('mnote.exe','README.md','THIRD-PARTY-NOTICES.txt'):
        archive.write(payload/name,arcname=payload.name+'/'+name)
PY
(cd "${output}" && sha256sum "Mnote-Windows-${version}-Setup.exe" "Mnote-Windows-${version}-Portable.zip")
