"""Explicit live smoke test: isolated temporary account; never claims the legacy vault.

Run with the deployed venv Python. Secrets stay in memory. Synthetic account data
is quarantined after the test instead of recursively deleted.
"""
import argparse
import base64
import json
import secrets
import sqlite3
import uuid
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from heartnote_capture.accounts import Accounts
from heartnote_capture.store import CaptureStore, minimal_png

parser = argparse.ArgumentParser()
parser.add_argument('--backup', required=True)
args = parser.parse_args()
backup = Path(args.backup).resolve()
assert backup.parent == Path('/var/backups') and backup.name.startswith('mnote-account-upgrade.')
assert (backup / 'data/captures.sqlite3').is_file()
data = Path('/var/lib/heartnote-capture')
accounts = Accounts(CaptureStore(data))

def snapshot(path):
    with sqlite3.connect(f'file:{path}?mode=ro', uri=True) as db:
        return db.execute('SELECT * FROM captures ORDER BY id').fetchall(), db.execute('SELECT * FROM capture_assets ORDER BY capture_id,role').fetchall()

before = snapshot(backup / 'data/captures.sqlite3')
assert snapshot(data / 'captures.sqlite3') == before, 'Existing records changed since backup; review before testing'

def request(method, path, body=None, token=None, revision=None):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    if revision: headers['If-Match'] = '"revision:' + str(revision) + '"'
    req = Request('https://chenyu.online/heartnote-capture' + path, method=method,
                  headers=headers, data=json.dumps(body).encode() if body is not None else None)
    try: response = urlopen(req, timeout=20)
    except HTTPError as error: response = error
    with response:
        raw = response.read()
        return response.status, json.loads(raw) if response.headers.get_content_type() == 'application/json' else raw

username = 'verify-' + uuid.uuid4().hex[:16]
password = secrets.token_urlsafe(32)
invitation = accounts.invite()
session = None
try:
    status, session = request('POST', '/v1/auth/activate', {'username': username, 'password': password, 'invitation': invitation})
    assert status == 200, ('activate', status)
    token = session['access_token']
    account = accounts.resolve(token)
    assert account and account['vault'] != 'legacy'
    status, feed = request('GET', '/v1/changes', token=token)
    assert status == 200 and feed['changes'] == [], 'Test account must start empty'
    capture_id = 'verify-' + uuid.uuid4().hex
    body = {'id': capture_id, 'comment': 'Temporary account smoke test', 'ai_access': 'deny',
            'assets': {'original': {'content_type': 'image/png', 'data_base64': base64.b64encode(minimal_png()).decode()}}}
    assert request('PUT', '/v1/captures/' + capture_id, body, token)[0] == 200
    assert request('GET', '/v1/captures/' + capture_id)[0] == 401
    assert request('GET', '/v1/captures/' + capture_id + '/assets/original', token=token) == (200, minimal_png())
    assert request('GET', '/v1/changes', token=token)[1]['changes'][0]['capture_id'] == capture_id
    assert request('DELETE', '/v1/captures/' + capture_id, token=token, revision=1)[0] == 200
    assert request('GET', '/v1/changes', token=token)[1]['changes'][-1]['operation'] == 'delete'
    status, second = request('POST', '/v1/auth/login', {'username': username, 'password': password})
    assert status == 200 and second['account_id'] == session['account_id']
    assert request('POST', '/v1/auth/logout', {}, token)[0] == 200
    assert request('GET', '/v1/changes', token=token)[0] == 401
    assert request('GET', '/v1/changes', token=second['access_token'])[0] == 200
    assert snapshot(data / 'captures.sqlite3') == before, 'Legacy records changed'
    print('HTTPS activation/login/upload/PNG download/changefeed/delete/logout passed; legacy record and asset rows unchanged.')
finally:
    # Resolve the exact synthetic username even if the HTTP response was interrupted.
    with accounts.db() as db:
        row = db.execute('SELECT id,vault FROM accounts WHERE username=?', (username,)).fetchone()
        if row:
            assert row['vault'] != 'legacy' and len(row['vault']) == 32
            db.execute('BEGIN IMMEDIATE')
            db.execute('DELETE FROM sessions WHERE account_id=?', (row['id'],))
            db.execute('DELETE FROM accounts WHERE id=? AND username=?', (row['id'], username))
            db.commit()
            vault = data / 'account-vaults' / row['vault']
            if vault.is_dir(): vault.rename(backup / ('synthetic-vault-' + row['vault']))
    print('Synthetic credentials revoked; test vault quarantined in the private deployment backup.')
