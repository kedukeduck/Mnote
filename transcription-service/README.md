# Mnote-independent transcription demo

An intentionally small, owner-private HTTP service that can later be reused by Mnote.
It stores jobs in SQLite and uses one bounded worker, local FFmpeg decoding and
`faster-whisper` small on CPU/int8/two threads. No paid API is called. All model files
must already exist locally; `local_files_only=True` prevents runtime model downloads.

This is **not a copyright verification service**. The agreement, authorization basis,
note and server consent timestamp are recorded separately from `verified: false`.
Only submit recordings you own, have permission to transcribe, or whose license
permits it. A checked box is not proof of rights. The project does not submit the
previously supplied third-party examples. Tests use synthetic/mocked media; the
optional public sample must be an operator-created CC0 recording.

## Run

Requires Python 3.10+, FFmpeg (`ffmpeg`, `ffprobe`) and an operator-provisioned
faster-whisper-compatible small model directory containing `model.bin`.
Install the pinned runtime pair in `requirements.txt`: `faster-whisper==1.2.1`
and `av==18.1.0`. PyAV 19 removed the `metadata_errors` argument still passed by
faster-whisper 1.2.1's audio decoder; allowing pip/uv to choose PyAV 19 can make
imports succeed but real transcription fail. Do not independently upgrade PyAV
without checking decoder compatibility and rerunning a real owned/CC0 recording
through the complete API workflow. The deployment runtime has verified this pinned
pair on Python 3.12; unit tests alone do not establish audio-decoder compatibility.

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env
# Edit .env first. Keep it private and outside version control.
set -a
. ./.env
set +a
.venv/bin/python service.py --host 127.0.0.1 --port 8791
python3 -m unittest discover -s tests -v
```

Do not expose the loopback service directly to the public Internet. Terminate TLS
at a trusted reverse proxy. In the owner-private Sites demo, the authenticated
proxy adds the secret Bearer token and **overwrites** `X-Owner-Id` from its own
native identity. Never put the service secret in browser JavaScript. A shared
Bearer token and caller-selected owner header alone do not constitute account
authentication. Future Mnote deployments need trusted account/owner binding.
No CORS headers are provided; browser access is through that same-origin proxy.

`MNOTE_TRANSCRIPT_AUDIO_HOSTS` is a comma-separated list of **exact** HTTPS hostnames,
including any direct-audio redirect destination. The empty default disables direct
audio. Never set an untrusted user-controlled host as a blanket workaround.
Platform page/API hosts and CDN suffixes are separately restricted in code.
The optional `requirements-resolver.txt` pins yt-dlp for operator diagnostics only;
the service does not execute it, because arbitrary extractor networking would
defeat this service's SSRF controls. No cookies, account login, JavaScript-signature
generation, DRM circumvention or access-control bypass is supported.

## API

Every `/v1/` route requires:

```text
Authorization: Bearer <MNOTE_TRANSCRIPT_TOKEN>
X-Owner-Id: <trusted-proxy-owner, ASCII 1..64 characters>
```

`GET /health` is public liveness only. `GET /v1/config` returns
`{ready,agreement_version,max_duration_seconds:1800,retention_hours:24,message}`;
readiness checks the exact pinned dependency versions, module presence and files/tools,
never imports the inference runtime or loads a model on a request thread. Missing
dependencies and incompatible versions produce `ready:false` with an actionable
Chinese `message`. It is a
prerequisite check, not proof every model dependency is healthy.

`POST /v1/jobs` requires JSON:

```json
{
  "share_text": "https://media.example.org/my-recording.wav",
  "rights_basis": "owned",
  "rights_note": "I recorded this audio myself.",
  "agreement_version": "2026-10-12-v1",
  "consent": true
}
```

`rights_basis` is `owned`, `authorized`, or `open_license`; a nonempty note up to
1000 characters and literal boolean `true` are mandatory. `authorized` and
`open_license` notes must be at least eight characters. Normal license links are
preserved, but declaration URL credentials and query parameters are scrubbed.
Share text may contain
Chinese surrounding text and exactly one HTTPS URL. B23/Bilibili and Douyin are
recognized. Bilibili uses ordinary anonymous metadata and lowest-bandwidth DASH
audio only. For that same audio representation, it validates the API's primary
and backup URLs, deduplicates them, and tries at most three approved candidates.
Besides the bilivideo domains, only the exact observed
`upos-hz-mirrorakam.akamaized.net` hostname is approved, not `*.akamaized.net`.
An ordinary network failure, early EOF or HTTP 403/412 can switch to an explicit
backup; HTTP 429, unsafe redirects, oversized media and cancellation stop the
attempt. All candidates share a 120-second acquisition deadline (including DNS
and TLS), and each retry replaces the partial file. A Bilibili media request does
not follow cross-host redirects: another host must be an approved explicit
candidate. Source credentials, cookies and forged Referer headers are never sent.
Douyin reads public embedded metadata if offered; unavailable or
login/signature-gated links fail clearly instead of asserting successful access.
Platform changes may make public resolution unavailable. Direct audio supports
common audio/video container suffixes on configured hosts, not playlists.

Response is `202 {job:{id,status,stage,progress,error,source,segments,text,
created_at,updated_at,expires_at,rights}}`. Timestamps are Unix seconds.
`status` is `queued`, `processing`, `completed`, or `failed`; `stage` is `queued`,
`resolving`, `downloading`, `transcribing`, `completed`, or `failed`.
Progress is monotonic but not a time estimate; ASR advances from 50 to at most 95
using the latest recognized segment position (silence may not report an update).
An empty completed transcript means no speech was recognized, not a fabricated result.

`GET /v1/jobs/{uuid}` polls the same wrapper. `GET /v1/jobs/{uuid}/download?format=txt|md|srt`
returns an attachment only after completion. `DELETE /v1/jobs/{uuid}` deletes its
stored text, acquisition URL and temporary files. Foreign/expired IDs return the
same 404. Clients must use authenticated fetch for export, not uncredentialed links.
Errors are `{error:{code,message}}` with fixed Chinese messages; source URLs,
signed query parameters, cookies, stack traces and underlying exception messages
are never included in responses or access logs. Source summaries only contain
platform, host, bounded title and optionally duration.

`source_forbidden` identifies HTTP 403/412; `source_rate_limited` identifies
HTTP 429. `network_failed` covers connection/read failures or other non-200
responses. A valid share link is not necessarily an accessible audio stream.

If `MNOTE_TRANSCRIPT_DEMO_AUDIO` names the operator-created sample, public
`GET /demo/sample.wav` serves **only that exact file** and `/demo/license` gives
the CC0-1.0 attribution declaration. With `MNOTE_TRANSCRIPT_PUBLIC_URL` configured,
`/v1/config` also has `demo_sample:{url,license,license_url}`. Do not configure this
endpoint to point at personal media or third-party material.

## Safety and operations

- HTTPS only, no credentials/fragments/non-443 ports. Every redirect is validated
  and bounded. DNS results containing any nonpublic, multicast, mapped or transition
  address are rejected. TLS connects to one approved literal IP while validating
  the original hostname, blocking DNS rebinding between approval and connection.
- No proxy environment forwarding or app auth headers/cookies sent to sources.
  Downloads have 80 MiB streaming cap, per-socket timeout and total timeout checks.
  DNS resolution runs in an isolated subprocess with a 15-second deadline; a hard
  socket watchdog covers slow trickling and cancellation, not only checks between reads.
- FFmpeg receives only a local file; protocol and container format whitelists exclude
  HTTP, TCP, HLS and concat playlists. Decode is capped to 1801 seconds and PCM size;
  actual decoded audio over 1800 seconds is rejected, including misleading metadata.
  ASR is a cancellable subprocess with a one-hour bound, not a request-thread task.
- Queue admits at most four active jobs total. Restart marks pending/in-progress jobs
  failed with an explicit interrupted message, never silently retries acquisition.
  Shutdown/restart/delete cannot recreate a deleted row. Orphan temporary directories
  are cleaned on startup. A worker checks expiry at least once a second while idle;
  every authenticated read/delete/submission also enforces the 24-hour TTL.
- Data directories use 0700 and SQLite 0600. SQLite secure_delete and DELETE journal
  reduce residual live-database content. This is logical deletion, **not guaranteed
  physical erasure on SSDs or backups**. Do not back up temporary media; document any
  database backup retention separately. Keep the data directory on a private filesystem.
- This stdlib server is for a small private demo behind a rate/connection-limited
  proxy, not a general multi-tenant production upload service. Restrict inbound
  requests and outbound networking at the deployment boundary as an extra defense.

An example systemd unit is included. Use a dedicated unprivileged account with
access only to its model/data directories. Test a model and a recording you created
before marking deployment operational; passing mock tests is not ASR proof.

For operator-selected Bilibili links, a read-only diagnostic checks public
metadata and media response headers, without reading media bodies or creating
transcription jobs:

```sh
python3 scripts/verify-transcript-bilibili-access.py HTTPS_SHARE_LINK
```

A 200 response here establishes current source access, not successful complete
download, decoding, speech recognition or rights verification. It does not print
signed media queries. Automated regressions use only synthetic/mock media; the
anonymous end-to-end check uses the operator's original CC0 sample.
