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
until_drive() { local last_status=0; for _ in $(seq 1 100); do if drive "$@" >/dev/null 2>&1; then return; else last_status=$?; fi; sleep 0.15; done; drive screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/failed-timeout.png" || true; echo "GUI timeout: $* (driver exit ${last_status})" >&2; exit 1; }
wineboot -u >/dev/null 2>&1
if [[ -f /usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf ]]; then
    cp /usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf "${WINEPREFIX}/drive_c/windows/Fonts/"
    wine reg add 'HKLM\Software\Microsoft\Windows NT\CurrentVersion\FontSubstitutes' /v 'Georgia' /d 'Liberation Serif' /f >/dev/null 2>&1
fi
if [[ -f /root/.cache/mnote-build-tools/root/usr/share/fonts/truetype/wqy/wqy-microhei.ttc ]]; then
    cp /root/.cache/mnote-build-tools/root/usr/share/fonts/truetype/wqy/wqy-microhei.ttc "${WINEPREFIX}/drive_c/windows/Fonts/"
    wine reg add 'HKLM\Software\Microsoft\Windows NT\CurrentVersion\FontSubstitutes' /v 'Segoe UI' /d 'WenQuanYi Micro Hei' /f >/dev/null 2>&1
fi
(cd "${test_dir}" && wine "${application}") >"${test_dir}/app.log" 2>&1 &
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
drive open
until_drive editor
drive ai-save-chat
until_drive ai-ready
drive ai-small
until_drive ai-ready
drive window-screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/ai-chat-small-preview.png" 'Mnote · 与 AI 聊聊'
drive ai-large
drive ai-draft
drive window-screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/ai-chat-preview.png" 'Mnote · 与 AI 聊聊'
drive ai-close
drive open
until_drive editor
drive ai-save-chat
until_drive ai-draft-ready
drive settings
until_drive settings-ready
drive ai-models
until_drive ai-models-ready
drive ai-models-fill
sleep 0.4
drive window-screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/ai-models-preview.png" 'Mnote · AI 模型配置'
drive ai-models-close
drive ai-all-history
until_drive ai-history-empty
drive settings-close
drive ai-send
until_drive ai-consent-cancel
drive ai-close
until_drive count 1
python3 - "${application_data}" <<'PY'
import pathlib,sys
root=pathlib.Path(sys.argv[1])/'ai-chat'/'guest'
assert (root/'models.dpapi').is_file()
assert b'sk_SYNTHETIC_GUI_ONLY' not in (root/'models.dpapi').read_bytes()
assert not list(root.glob('*.json')), 'cancelled consent must not create a conversation'
assert next(root.glob('draft-*')).read_text()=='Synthetic draft, never sent to a model.'
print('GUI: save-before-chat, independent draft, module controls, DPAPI model form, empty history, explicit consent cancellation passed; no model calls')
PY
# A local persisted conversation exercises the real history-to-chat UI. No provider is invoked.
python3 - "${application_data}" <<'PY'
import json,pathlib,sys
root=pathlib.Path(sys.argv[1])
record=json.loads(next((root/'Library/guest/records').glob('*.json')).read_text())['record']
record_id=record['id']; timestamp='2026-10-01T10:00:00Z'
def message(identifier,role,content,status='complete'):
    return dict(id=identifier,role=role,content=content,status=status,created_at=timestamp,
                request_id='gui-only',model='Mnote Demo',error='')
conversation=dict(schema_version=1,id='gui-bubble-fixture',record_id=record_id,
    title='让灵感慢慢变成行动',created_at=timestamp,updated_at=timestamp,
    snapshot=dict(record_id=record_id,consent='record_chat_only',modules=['thought'],
                  thought=record['comment'],images=[]),
    model=dict(label='Synthetic model · not invoked',base_url='https://models.invalid/v1',
               model='mock-vision',vision=True),
    messages=[
        message('gui-user-one','user','我想把收藏变成行动，而不只是越存越多。你会建议我从哪里开始？'),
        message('gui-ai-one','assistant','**给想法一个小小的出口**\n\n不用整理整个知识库，可以先给这条记录一个具体动作。\n- 写下它触动你的原因\n- 选一件十分钟内能完成的小事\n\n> 回顾不是清空列表，而是重新遇见当时的自己。'),
        message('gui-user-two','user','先从这条记录开始，帮我把第一步缩小一点。'),
        message('gui-ai-two','assistant','**今天只做一件事**\n\n写下这句话：「这条记录让我想起了……」\n不用写完整，先保留最真实的那个念头。','stopped')])
envelope=dict(conversation=conversation,revision=0,dirty=False,deleted=False,draft='',
              consent='',conflict={},parent_generation='')
(root/'ai-chat/guest/gui-bubble-fixture.json').write_text(json.dumps(envelope,ensure_ascii=False))
PY
drive settings
until_drive settings-ready
drive ai-all-history
until_drive ai-history-continue
until_drive ai-bubbles-ready
drive ai-history-close
drive settings-close
drive ai-large
until_drive ai-bubbles-ready
drive ai-bubbles-select
drive window-screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/ai-chat-bubbles-preview.png" 'Mnote · 与 AI 聊聊'
drive ai-small
until_drive ai-bubbles-ready
drive ai-bubbles-select
drive window-screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/ai-chat-bubbles-small-preview.png" 'Mnote · 与 AI 聊聊'
drive ai-close
until_drive ai-closed
until_drive library-uncovered
python3 - "${application_data}" <<'PY'
import json,pathlib,sys
path=pathlib.Path(sys.argv[1])/'ai-chat/guest/gui-bubble-fixture.json'
envelope=json.loads(path.read_text())
assert envelope['conversation']['id']=='gui-bubble-fixture'
assert len(envelope['conversation']['messages'])==4, 'GUI fixture must not send a model request'
path.unlink()
print('GUI: persisted multi-turn IM bubbles, role alignment, selectable text, hidden inline actions, normal/narrow composer separation passed; no model calls')
PY
drive show
drive tag 工作
until_drive count 1
drive open
until_drive editor
until_drive tags-existing
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
assert edited['record']['tags']==['已编辑','灵感'] and not edited['deleted']
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
# Account was opened from Settings; close its still-visible parent before pixel checks.
# GetDC on a covered list can return the settings window's pixels or CLR_INVALID.
until_drive settings-close
drive show
until_drive library-uncovered
sleep 0.3
until_drive cards-ready
drive kind 想法
until_drive count 1
until_drive mixed-preview-ready
drive mixed-dpi-ready
drive home-size 1240 900
until_drive mixed-preview-ready
drive window-screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/mixed-record-library.png" "Mnote · 我的知识库"
drive kind 摘录
until_drive count 3
drive kind 全部类型
until_drive count 3
drive screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/library-preview.png"
drive home-size 780 650
sleep 0.3
until_drive cards-ready
drive kind 想法
until_drive count 1
until_drive mixed-preview-ready
drive window-screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/mixed-record-library-compact.png" "Mnote · 我的知识库"
drive kind 全部类型
until_drive count 3
drive window-screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/journal-library-compact.png" "Mnote · 我的知识库"
drive home-size 1240 900
sleep 0.3
until_drive cards-ready
drive read-record 2
sleep 0.3
drive window-screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/journal-library.png" "Mnote · 我的知识库"
drive multi-begin
until_drive multi-ready
drive multi-cancel
drive markdown
until_drive inline-ready
drive multi-all
drive screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/multiselect-preview.png"
drive window-screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/journal-multiselect.png" "Mnote · 我的知识库"
drive markdown
until_drive markdown-confirm-cancel
[[ ! -f "${test_dir}/Mnote-export.md" ]]
until_drive inline-ready
drive markdown
until_drive markdown-confirm
until_drive markdown-file
for _ in $(seq 1 100); do [[ -s "${test_dir}/Mnote-export.md" ]] && break; sleep 0.15; done
if [[ ! -s "${test_dir}/Mnote-export.md" ]]; then drive screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/failed-export.png"; fi
python3 - "${test_dir}" <<'PY'
import pathlib,re,sqlite3,sys,urllib.request
root=pathlib.Path(sys.argv[1]);text=(root/'Mnote-export.md').read_text()
assert text.startswith('# Mnote 记录导出') and '3 条' in text and 'original END' in text
assert 'mns_' not in text
paths=list(dict.fromkeys(re.findall(r'https://images.example.test/capture(/s/[^)]+)',text)))
assert len(paths)==4,paths
port=(root/'fixture/port.txt').read_text()
for path in paths:
    with urllib.request.urlopen('http://127.0.0.1:'+port+path) as response:
        assert response.status==200 and response.read().startswith(b'\x89PNG')
print('GUI: three selected records exported with full original and four accessible snapshot images')
PY
drive settings
until_drive settings-ready
drive settings-shares
until_drive shares-covers-ready
drive screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/share-gallery-preview.png"
drive share-text
until_drive share-text-ready
drive screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/share-text-preview.png"
drive share-text-close
until_drive shares-preview
until_drive history-ready
drive screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/history-preview.png"
drive history-next
until_drive history-ready
drive history-close
until_drive shares-revoke
until_drive confirm
until_drive shares-empty
drive shares-close
drive settings-close
drive multi-cancel
python3 - "${test_dir}" <<'PY'
import pathlib,re,sys,urllib.request,urllib.error
root=pathlib.Path(sys.argv[1]);text=(root/'Mnote-export.md').read_text();port=(root/'fixture/port.txt').read_text()
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
drive settings
until_drive settings-ready
drive screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/settings-preview.png"
drive updates
until_drive updates-ready
drive screenshot "Z:${repo_dir}/desktop-windows/build-gui-smoke/updates-preview.png"
drive close-updates
drive show
drive multi-begin
until_drive multi-ready
drive multi-all
drive multi-delete
until_drive multi-delete-cancel
until_drive count 3
drive multi-delete
until_drive multi-delete-confirm
until_drive count 0
drive trash
until_drive count 3
echo 'GUI: inline multi-select, preselected export, real image viewer, batch deletion confirmation and trash preservation passed'
drive exit
echo 'workspace GUI: passed (real account server, explicit import, upload, cancellation)'
