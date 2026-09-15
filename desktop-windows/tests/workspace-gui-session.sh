#!/usr/bin/env bash
set -euo pipefail
repo_dir="$1"
test_dir="$2"
export WINEDEBUG=-all WINEPREFIX="${test_dir}/wine"
driver="${repo_dir}/desktop-windows/build-gui-smoke/workspace-driver.exe"
application="${repo_dir}/desktop-windows/build-mingw/mnote.exe"
server_pid=""
cleanup() { if [[ -n "${server_pid}" ]]; then kill "${server_pid}" 2>/dev/null || true; wait "${server_pid}" 2>/dev/null || true; fi; }
trap cleanup EXIT
drive() { wine "${driver}" "$@"; }
until_drive() { for _ in $(seq 1 100); do if drive "$@" >/dev/null 2>&1; then return; fi; sleep 0.15; done; echo "GUI timeout: $*" >&2; exit 1; }
wineboot -u >/dev/null 2>&1
if [[ -f /root/.cache/mnote-build-tools/root/usr/share/fonts/truetype/wqy/wqy-microhei.ttc ]]; then
    cp /root/.cache/mnote-build-tools/root/usr/share/fonts/truetype/wqy/wqy-microhei.ttc "${WINEPREFIX}/drive_c/windows/Fonts/"
    wine reg add 'HKLM\Software\Microsoft\Windows NT\CurrentVersion\FontSubstitutes' /v 'Segoe UI' /d 'WenQuanYi Micro Hei' /f >/dev/null 2>&1
fi
wine "${application}" >"${test_dir}/app.log" 2>&1 &
until_drive ready
wine "${driver}" source >"${test_dir}/source.log" 2>&1 &
until_drive focus-source
sleep 0.3
drive capture
overlay=""
for _ in $(seq 1 100); do overlay="$(xdotool search --onlyvisible --name 'Mnote -' 2>/dev/null | tail -n 1 || true)"; [[ -n "${overlay}" ]] && break; sleep 0.15; done
[[ -n "${overlay}" ]]
xdotool mousemove --window "${overlay}" 180 150 mousedown 1 mousemove --sync --window "${overlay}" 900 620 mouseup 1
drive pen
sleep 0.2
xdotool mousemove --window "${overlay}" 300 300 mousedown 1 mousemove --sync --window "${overlay}" 700 500 mouseup 1
drive next
until_drive editor
drive fill
drive full
drive screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/editor-preview.png"
drive save
until_drive count 1
application_data="$(find "${WINEPREFIX}/drive_c/users" -type d -path '*/AppData/Local/PersonalCapture' -print -quit)"
[[ -n "${application_data}" ]]
python3 - "${application_data}" <<'PY'
import json, pathlib, struct, sys
root=pathlib.Path(sys.argv[1]); path=next((root/'Library/guest/records').glob('*.json')); envelope=json.loads(path.read_text()); r=envelope['record']
assert r['comment']=='wine-smoke-note' and r['tags']==['工作','灵感']
assert envelope['state']=='local' and r['ai_access']=='local_only'
assert r['source']['app_name']=='workspace-driver.exe',r['source']
assert len(r['annotations'])==1 and r['annotations'][0]['tool']=='pen'
assert len(r['annotations'][0]['points'])>=2
assert r['evidence']['context']['image']['retained'] is True
assert r['evidence']['context']['image']['selection']==dict(left=180,top=150,right=900,bottom=620)
for role, dimensions in [('original',(720,470)),('annotated',(720,470)),('context',(1280,1000))]:
    data=(root/'Library/guest/assets'/envelope['assets'][role]).read_bytes()
    assert data[:8]==b'\x89PNG\r\n\x1a\n' and struct.unpack('>II',data[16:24])==dimensions
assert not list(root.rglob('*.part'))
print('GUI: screenshot, annotation, thought, tags, full context and source passed')
PY
drive show
drive tag 工作
until_drive count 1
drive open
until_drive editor
drive edit
until_drive count 0
drive tag 全部标签
until_drive count 1
drive open
until_drive editor
drive delete
until_drive confirm
until_drive count 0
drive trash
until_drive count 1
drive open
until_drive count 0
drive trash
until_drive count 1
drive focus-source
drive quick
until_drive editor
drive clipboard
drive save
until_drive count 2
python3 - "${application_data}" <<'PY'
import json,pathlib,sys
rows=[json.loads(p.read_text()) for p in (pathlib.Path(sys.argv[1])/'Library/guest/records').glob('*.json')]
edited=next(x for x in rows if x['record']['comment']=='edited-thought')
assert edited['record']['tags']==['已编辑'] and not edited['deleted']
assert edited['record']['evidence']['context']['text']['full_text'].endswith('original END')
assert len(edited['assets'])==3
clipboard=next(x for x in rows if x['record']['source']['text']=='explicit clipboard excerpt')
assert not clipboard['assets'] and clipboard['record']['comment']==''
print('GUI: local refresh, editing long original, filtering, delete/restore and opt-in clipboard passed')
PY
drive focus-source
drive quick
until_drive editor
drive context-text
sleep 5
until_drive idle
drive context-screen
# Reading may have succeeded; replacing that context then requires confirmation.
sleep 0.3
drive confirm >/dev/null 2>&1 || true
until_drive idle
drive save
until_drive count 3
python3 - "${application_data}" <<'PY'
import json,pathlib,sys
rows=[json.loads(p.read_text()) for p in (pathlib.Path(sys.argv[1])/'Library/guest/records').glob('*.json')]
context=next(x for x in rows if set(x['assets'])=={'context'})
assert context['record']['source']['text']=='' and context['record']['comment']==''
assert context['record']['evidence']['context']['image']['relation_to_quote']=='unverified'
print('GUI: independent screenshot context without clipboard, bounded text read returns safely')
PY
drive tag 未分类
until_drive count 2
drive tag 全部标签
until_drive count 3
PYTHONPATH="${repo_dir}/capture-server/src" python3 "${repo_dir}/desktop-windows/tests/account_fixture.py" "${test_dir}/fixture" 0 >"${test_dir}/server.log" 2>&1 &
server_pid=$!
for _ in $(seq 1 100); do [[ -f "${test_dir}/fixture/port.txt" ]] && break; sleep 0.1; done
drive account
sleep 0.3
until_drive login "http://127.0.0.1:$(<"${test_dir}/fixture/port.txt")" "$(<"${test_dir}/fixture/invitation.txt")"
until_drive count 0
until_drive import
until_drive confirm
until_drive count 3
for _ in $(seq 1 100); do
    if python3 - "${application_data}" <<'PY'
import json,pathlib,sys
paths=[p for p in (pathlib.Path(sys.argv[1])/'Library').glob('*/records/*.json') if p.parent.parent.name!='guest']
assert len(paths)==3
assert all(json.loads(p.read_text())['state']=='synced' for p in paths)
PY
    then break; fi
    sleep 0.2
done
until_drive close-account
drive show
sleep 0.3
drive screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/library-preview.png"
drive markdown
until_drive markdown-select
drive screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/markdown-preview.png"
drive markdown-save
until_drive confirm
until_drive markdown-file "Z:${test_dir}/export.md"
for _ in $(seq 1 100); do [[ -s "${test_dir}/export.md" ]] && break; sleep 0.15; done
python3 - "${test_dir}" <<'PY'
import pathlib,re,sqlite3,sys,urllib.request
root=pathlib.Path(sys.argv[1]);text=(root/'export.md').read_text()
assert text.startswith('# Mnote 记录导出') and '3 条' in text and 'original END' in text
assert 'mns_' not in text
paths=re.findall(r'https://images.example.test/capture(/s/[^)]+)',text)
assert len(paths)==4,paths
port=(root/'fixture/port.txt').read_text()
for path in paths:
    with urllib.request.urlopen('http://127.0.0.1:'+port+path) as response:
        assert response.status==200 and response.read().startswith(b'\x89PNG')
print('GUI: three selected records exported with full original and four accessible snapshot images')
PY
drive markdown-shares
until_drive shares-revoke
until_drive confirm
until_drive shares-empty
drive shares-close
drive markdown-close
python3 - "${test_dir}" <<'PY'
import pathlib,re,sys,urllib.request,urllib.error
root=pathlib.Path(sys.argv[1]);text=(root/'export.md').read_text();port=(root/'fixture/port.txt').read_text()
for path in re.findall(r'https://images.example.test/capture(/s/[^)]+)',text):
    try: urllib.request.urlopen('http://127.0.0.1:'+port+path);raise AssertionError('revoked image accessible')
    except urllib.error.HTTPError as error: assert error.code==404
print('GUI: revocation disables all export images and keeps original library')
PY
until_drive count 3
before="$(find "${application_data}/Library" -type f -name '*.json' | wc -l)"
drive focus-source
drive capture
until_drive cancel-capture
sleep 0.3
after="$(find "${application_data}/Library" -type f -name '*.json' | wc -l)"
[[ "${before}" == "${after}" ]]
drive show
drive updates
until_drive updates-ready
drive screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/updates-preview.png"
drive close-updates
drive exit
echo 'workspace GUI: passed (real account server, explicit import, upload, cancellation)'
