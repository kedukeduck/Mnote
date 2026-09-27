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
parser.add_argument('--card-shares', action='store_true', help='Verify 0.7 selected-module QR snapshots with synthetic content only')
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
shares = []
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
            'source': {'text': 'selected text', 'url': 'https://example.com/mnote-release-fixture'},
            'evidence': {'context': {'text': {'full_text': 'before selected text after', 'origin': 'user_supplied'},
                'image': {'retained': True, 'width': 1, 'height': 1, 'selection': {'left': 0,'top': 0,'right': 1,'bottom': 1}}}},
            'assets': {role: {'content_type': 'image/png', 'data_base64': base64.b64encode(minimal_png()).decode()}
                for role in ('original', 'annotated', 'context')}}
    assert request('PUT', '/v1/captures/' + capture_id, body, token)[0] == 200
    assert request('GET', '/v1/captures/' + capture_id)[0] == 401
    assert request('GET', '/v1/captures/' + capture_id + '/assets/original', token=token) == (200, minimal_png())
    assert request('GET', '/v1/captures/' + capture_id + '/assets/context', token=token) == (200, minimal_png())
    assert request('GET', '/v1/captures/' + capture_id, token=token)[1]['evidence'] == body['evidence']
    assert request('GET', '/v1/changes', token=token)[1]['changes'][0]['capture_id'] == capture_id
    if args.card_shares:
        # Exercise the new schema and the old original/source-only client request.
        for fields in (['thought', 'excerpt', 'crop', 'context', 'original', 'source'],
                       ['thought'], ['original', 'source']):
            public_token = secrets.token_hex(32)
            payload = {'id': capture_id, 'revision': 1, 'token': public_token,
                       'publish': True, 'fields': fields}
            if 'crop' in fields: payload['crop_role'] = 'annotated'
            status, shared = request('POST', '/v1/exports/card', payload, token)
            assert status == 200 and shared['format'] == 2, ('card', status)
            public_path = '/c/' + public_token
            shares.append((shared['id'], public_path, public_token))
            status, page = request('GET', public_path)
            assert status == 200
            for field, value in [('thought', body['comment']), ('excerpt', body['source']['text']),
                                 ('original', body['evidence']['context']['text']['full_text']),
                                 ('source', body['source']['url'])]:
                # The excerpt is contained in the original, so check its dedicated heading.
                present = b'<h2>\xe6\x91\x98\xe5\xbd\x95</h2>' in page if field == 'excerpt' else value.encode() in page
                assert present == (field in fields), ('selected field', field)
            assert token.encode() not in page and capture_id.encode() not in page
            for role in ('annotated', 'context'):
                status, image = request('GET', '/s/' + public_token + '/1-' + role + '.png')
                expected = 'crop' in fields if role == 'annotated' else 'context' in fields
                assert status == (200 if expected else 404), ('public image', role, status)
                if expected: assert image == minimal_png()
            assert request('GET', '/s/' + public_token + '/1-original.png')[0] == 404
            # Retrying never changes or duplicates the snapshot.
            assert request('POST', '/v1/exports/card', payload, token)[1]['id'] == shared['id']
        print('HTTPS all-module, thought-only and legacy-field QR snapshots and PNG assets passed; only synthetic content was shared.')
    assert request('DELETE', '/v1/captures/' + capture_id, token=token, revision=1)[0] == 200
    assert request('GET', '/v1/changes', token=token)[1]['changes'][-1]['operation'] == 'delete'
    for export_id, public_path, public_token in shares:
        assert request('GET', public_path)[0] == 200, 'Deleting a record must not rewrite its shared snapshot'
        assert request('DELETE', '/v1/exports/' + export_id, token=token)[0] == 200
        assert request('GET', public_path)[0] == 404
        for role in ('annotated', 'context'):
            assert request('GET', '/s/' + public_token + '/1-' + role + '.png')[0] == 404
    status, second = request('POST', '/v1/auth/login', {'username': username, 'password': password})
    assert status == 200 and second['account_id'] == session['account_id']
    assert request('POST', '/v1/auth/logout', {}, token)[0] == 200
    assert request('GET', '/v1/changes', token=token)[0] == 401
    assert request('GET', '/v1/changes', token=second['access_token'])[0] == 200
    assert snapshot(data / 'captures.sqlite3') == before, 'Legacy records changed'
    print('HTTPS activation/login/upload/PNG download/changefeed/delete/logout passed; legacy record and asset rows unchanged.')
finally:
    if session and session.get('access_token'):
        # Revoke any temporary public links even when an assertion fails.
        for export_id, _, _ in shares:
            status, _ = request('DELETE', '/v1/exports/' + export_id, token=session['access_token'])
            assert status in (200, 401, 404), ('cleanup temporary share', status)
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
