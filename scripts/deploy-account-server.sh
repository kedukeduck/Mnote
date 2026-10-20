#!/usr/bin/env bash
# Deliberately scoped to this existing service. Never changes SSH, proxy, or network settings.
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$(id -u)" == 0 ]]
[[ -x /opt/heartnote-capture/.venv/bin/python ]]
[[ -d /var/lib/heartnote-capture && ! -L /var/lib/heartnote-capture ]]
systemctl is-active --quiet heartnote-capture-api.service
python3 - <<'PY'
from pathlib import Path
import shlex
env = {}
for line in Path('/etc/heartnote-capture/server.env').read_text().splitlines():
    if line and not line.startswith('#') and '=' in line:
        key, value = line.split('=',1)
        env[key] = shlex.split(value)[0]
assert env['HEARTNOTE_CAPTURE_DATA'] == '/var/lib/heartnote-capture', 'Unexpected data directory; stop for review'
PY
wheel_dir="$(mktemp -d /var/tmp/mnote-account-wheel.XXXXXX)"
/opt/heartnote-capture/.venv/bin/python -m pip wheel --no-deps --wheel-dir "$wheel_dir" "$repo_dir/capture-server"
version="$(python3 -c 'import sys,tomllib; print(tomllib.load(open(sys.argv[1],"rb"))["project"]["version"])' "$repo_dir/capture-server/pyproject.toml")"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
wheel="$wheel_dir/heartnote_capture_server-$version-py3-none-any.whl"
[[ -f "$wheel" ]]
backup_dir="$(mktemp -d /var/backups/mnote-account-upgrade.XXXXXX)"
chmod 700 "$backup_dir"
cp -a /opt/heartnote-capture/.venv "$backup_dir/venv"
cp -a /etc/heartnote-capture/server.env "$backup_dir/server.env"
if [[ -f /usr/local/sbin/heartnote-capture-backup && ! -L /usr/local/sbin/heartnote-capture-backup ]]; then
    cp -a /usr/local/sbin/heartnote-capture-backup "$backup_dir/backup-tool"
fi
stopped=0
rollback() {
    status=$?
    if [[ "$stopped" == 1 ]]; then
        systemctl stop heartnote-capture-api.service || true
        mv /opt/heartnote-capture/.venv "$backup_dir/failed-venv"
        cp -a "$backup_dir/venv" /opt/heartnote-capture/.venv
        cp -a "$backup_dir/server.env" /etc/heartnote-capture/server.env
        if [[ -f "$backup_dir/backup-tool" ]]; then
            cp -a "$backup_dir/backup-tool" /usr/local/sbin/heartnote-capture-backup
        fi
        systemctl start heartnote-capture-api.service
        echo "Rolled back service binary; data retained. Backup: $backup_dir" >&2
    fi
    exit "$status"
}
trap rollback EXIT
systemctl stop heartnote-capture-api.service
stopped=1
cp -a /var/lib/heartnote-capture "$backup_dir/data"
if [[ "${MNOTE_CONFIGURE_TRANSCRIPTS:-0}" == 1 ]]; then
    # Only the private core API token is shared with this trusted adapter, not
    # the browser worker/proxy secrets. Never echo credentials to the terminal.
    python3 - <<'PY'
import os, re, shlex, stat, tempfile
from pathlib import Path
source = Path('/etc/mnote-transcription-demo/service.env')
target = Path('/etc/heartnote-capture/server.env')
for path in (source, target):
    info = path.lstat()
    assert stat.S_ISREG(info.st_mode) and info.st_uid == 0 and stat.S_IMODE(info.st_mode) == 0o600
values = {}
for line in source.read_text().splitlines():
    if line and not line.startswith('#') and '=' in line:
        key, value = line.split('=', 1)
        parsed = shlex.split(value)
        if parsed:
            values[key] = parsed[0]
token = values['MNOTE_TRANSCRIPT_TOKEN']
assert re.fullmatch(r'[A-Za-z0-9_-]{32,256}', token), 'Unexpected core token format'
name = 'HEARTNOTE_CAPTURE_TRANSCRIPT_TOKEN'
lines = [line for line in target.read_text().splitlines() if not line.startswith(name + '=')]
lines.append(name + '=' + token)
fd, temporary = tempfile.mkstemp(prefix='.transcripts-', dir=target.parent)
try:
    with os.fdopen(fd, 'w') as stream:
        stream.write('\n'.join(lines) + '\n'); stream.flush(); os.fsync(stream.fileno())
    os.replace(temporary, target)
finally:
    Path(temporary).unlink(missing_ok=True)
print('Configured private transcription adapter; credentials not displayed.')
PY
fi
/opt/heartnote-capture/.venv/bin/python -m pip install --no-deps --force-reinstall "$wheel"
install -m 755 "$repo_dir/capture-server/deploy/heartnote-capture-backup" /usr/local/sbin/heartnote-capture-backup
systemctl start heartnote-capture-api.service
python3 - <<'PY'
import json, time
from urllib.request import urlopen, Request
from urllib.error import HTTPError
for attempt in range(10):
    try:
        with urlopen('http://127.0.0.1:8787/health', timeout=3) as response:
            assert response.status == 200
        break
    except Exception:
        if attempt == 9: raise
        time.sleep(0.5)
request=Request('http://127.0.0.1:8787/v1/auth/activate', method='POST',
    headers={'Content-Type':'application/json'},data=json.dumps({'username':'deployment-check',
    'password':'test-only-not-an-account','invitation':'invalid-deployment-check-code'}).encode())
try:
    urlopen(request,timeout=5)
    raise AssertionError('Activation unexpectedly accepted')
except HTTPError as error:
    assert error.code == 403, error.code
    assert json.load(error)['error'] == 'invalid_invitation'
print('Health and invitation enforcement passed; no account or record created.')
PY
systemctl is-active --quiet heartnote-capture-api.service
stopped=0
echo "Account server deployed; backup: $backup_dir"
