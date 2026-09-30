# Record-scoped AI conversations · implementation contract

Approved scope: Android and Windows record/save entry points, local BYOK configuration,
record-specific and global history, account sync, list badges/filter. No automatic AI
calls, cross-record retrieval, tools, public image links, or automatic note modification.

## Wire format (v1)

Conversation JSON uses snake_case:

```
{
  "schema_version": 1,
  "id": "UUID", "record_id": "capture-id", "title": "first question",
  "created_at": "ISO UTC", "updated_at": "ISO UTC",
  "snapshot": {
    "record_id": "capture-id", "fingerprint": "hash", "record_revision": 0,
    "record_created_at": "ISO UTC", "kind": "thought", "tags": [],
    "thought": "", "excerpt": "", "original": "", "source_url": "",
    "source_app": "", "source_type": "", "context_note": "",
    "images": [{"label": "圈选截图", "content_type": "image/jpeg", "data_base64": "..."}],
    "modules": ["thought", "excerpt", "original", "images", "metadata"],
    "consent": "record_chat_only"
  },
  "model": {"label": "my model", "model": "model-id", "base_url": "https://host/v1"},
  "messages": [{"id": "UUID", "role": "user|assistant", "content": "",
    "created_at": "ISO UTC", "status": "complete|generating|stopped|failed",
    "request_id": "UUID", "model": "model-id", "error": "fixed-safe-code"}]
}
```

`model` stores only a sanitized endpoint/model label for routing disclosure, never keys,
headers, secrets, provider error bodies, or provider response metadata. Profile credentials,
local draft, dirty/sync-error state and server revision are local wrapper fields, never put
in a conversation snapshot. Snapshot is immutable after the first message. It contains the
actual permitted text and bounded, downsampled image bytes supplied to the model, not public
URLs. No unrequested record content is added. Total conversation JSON <= 8 MiB; individual
message <= 100,000 characters, total messages <= 500. Exceeding limits is visible, not silent
truncation. Rendering text does not execute HTML or remote Markdown images.

## Account-only API

- `GET /v1/chat/changes?after=0&limit=100` -> `{changes:[{sequence,id,record_id,deleted,revision}],next_cursor,has_more}`.
- `GET /v1/chat/conversations/{id}` -> conversation fields plus `revision`.
- `PUT /v1/chat/conversations/{id}` -> conversation fields plus `revision`.
  `If-Match: revision:N` required (0 for create). Body is the conversation, without revision.
- `DELETE /v1/chat/conversations/{id}` -> `{id,deleted:true,revision}`; same revision precondition.
- Errors use existing 400/401/403/404/409 patterns. Chat routes require account credentials;
  legacy read/write/AI tokens and public shares never expose chats.

Separate chat database/change sequence per account vault. Parent record must exist and be
live at upload, and record deletion cascades to conversation tombstones without resurrecting
on stale upload or record restore. Deleted chat bodies/snapshot blobs are removed. Endpoint
GET also refuses deleted parents. Equivalent replays may return current revision; conflicting
edits must return 409, never last-write-wins.

Before generation in a synced conversation the client saves the user turn plus a final
assistant `generating` placeholder through CAS. Server rejects another request while that
placeholder is active (5-minute renewable lease based on server update time). Same request
may persist partial/final output; after expiry a client can mark interrupted and start a new
turn using latest revision. Clients must synchronize before continuing shared history, keep
drafts on conflicts and never silently overwrite another device's reply. Generation does
not hold the global record/account mutex over model network calls.

## Ownership and privacy

Client scopes all I/O/jobs to captured account scope; switching accounts cancels or hides old
jobs. Every first session send presents selected modules, endpoint, cost/sync disclosure and
explicit one-session remote grant. Existing deny/local_only is not silently rewritten and
MCP access never broadened. A new remote chat on a restricted record requires explicit grant;
later privacy restriction blocks continuation until a fresh confirmation. Deleted records
cannot continue even if the conversation was open. Provider requests set store:false, never
follow redirects or forward Mnote credentials. HTTPS only except explicit loopback test seam.
No real provider invocation or credential discovery during development.

## Failure recovery and release boundaries

Capture deletion commits first as a durable cleanup signal. Chat operations reconcile parent
deletion before exposing payloads and fail closed if cleanup fails; a crash between SQLite
files cannot expose a deleted snapshot. Restoration does not resurrect tombstoned chats.
An equivalent replay after a lost upload acknowledgement is not a conflict. Actual conflicts
keep the local version as a separate conversation and retain the unsent draft; the original
refreshes to the server revision. Model invocation must not begin after failed CAS reservation.

Windows and Android fingerprints are local implementation details. The UI compares selected
snapshot text and stable metadata with the current record rather than comparing those hashes.
Snapshots remain immutable irrespective of this advisory change indicator.

Local drafts and model keys do not sync. Android uses the independent Keystore alias
`mnote.ai.byok.v1`; Windows uses DPAPI. Server chat payloads are account-authenticated but not
end-to-end encrypted. Account-aware backups must include captures, chat databases, blobs and
share snapshots. No automatic model summarization is implemented: exceeding the explicit
message/context limits asks the user to narrow the scope or create a new conversation.

Release 1.20.0-test targets Android versionCode 36, Windows 1.20.0-test and server 0.9.0.
The user explicitly approved joint publication after verification. Production checks use
isolated synthetic records/accounts; never real provider keys or billable provider requests.

## Protocol references reviewed

- [Official streaming guide](https://developers.openai.com/api/docs/guides/streaming-responses):
  incremental Chat Completions SSE deltas; interrupted streams are not complete answers.
- [Official image inputs](https://developers.openai.com/api/docs/guides/images-vision):
  use image data URLs for actual visual input, never publish a record to make it AI-readable.
- These references specify the adapter format, not universal provider compatibility or a
  claim of zero retention. Each user-configured endpoint remains independently responsible
  for its own capabilities, pricing and data retention.
