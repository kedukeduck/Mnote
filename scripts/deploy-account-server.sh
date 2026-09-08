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
wheel="$wheel_dir/heartnote_capture_server-0.2.0-py3-none-any.whl"
[[ -f "$wheel" ]]
backup_dir="$(mktemp -d /var/backups/mnote-account-upgrade.XXXXXX)"
chmod 700 "$backup_dir"
cp -a /opt/heartnote-capture/.venv "$backup_dir/venv"
cp -a /etc/heartnote-capture/server.env "$backup_dir/server.env"
stopped=0
rollback() {
    status=$?
    if [[ "$stopped" == 1 ]]; then
        systemctl stop heartnote-capture-api.service || true
        mv /opt/heartnote-capture/.venv "$backup_dir/failed-venv"
        cp -a "$backup_dir/venv" /opt/heartnote-capture/.venv
        systemctl start heartnote-capture-api.service
        echo "Rolled back service binary; data retained. Backup: $backup_dir" >&2
    fi
    exit "$status"
}
trap rollback EXIT
systemctl stop heartnote-capture-api.service
stopped=1
cp -a /var/lib/heartnote-capture "$backup_dir/data"
/opt/heartnote-capture/.venv/bin/python -m pip install --no-deps --force-reinstall "$wheel"
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
