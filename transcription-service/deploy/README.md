# Private demo deployment — 2026-10-12

Live private UI: https://mnote-transcript-demo.stopunk.chatgpt.site

Independent API base: https://chenyu.online/mnote-transcript-api

This deployment does not modify Mnote Android, Windows or the capture/sync service.
The private Sites gateway authenticates the owner, and its server-only API proxy
derives a SHA-256 owner identifier from the authenticated identity. The service
Bearer secret is a native Worker runtime secret, not frontend configuration.
The original CC0 synthetic sample is public; private jobs and text are not.

## Source and release identity

- Sites project: `appgprj_6acc97cb7880819198120a2e8d675503`
- Pushed Sites source: `2d6fd6c96d70887ec753fbc0cbd42def0dd654fe`
- Private deployment: `appgdep_6acc9ec064888191999a02eb2750c186`
- Version: `appgprj_6acc97cb7880819198120a2e8d675503~appgver_67f68cb5d2fc81918ab26b64f96bb5b6`
- `../web/` is a credential-free Git archive of that exact source revision.
  It includes the maintained Sites build/worker sources. Do not rebind its opaque
  project ID to a different account or silently create a replacement site.

Use `npm ci` and `npx tsc --noEmit` in the web snapshot for source checks. The actual
Sites checkout uses the plugin's install/build/source-publishing helpers. Preview
mock identity is local-only; production uses the native authenticated gateway.
An ignored `.dev.vars` supplies local preview bindings; `.env.example` contains
only placeholders. Neither real environment files nor models are in Git.

## Service layout

The installed dedicated service uses these paths:

```text
/opt/mnote-transcription-demo/service.py
/opt/mnote-transcription-demo/runtime/bin/python
/opt/mnote-transcription-demo/models/
/opt/mnote-transcription-demo/sample.wav
/etc/mnote-transcription-demo/service.env       # root-only, 0600
/var/lib/mnote-transcription-demo/              # systemd private StateDirectory
/etc/nginx/snippets/mnote-transcript.conf
```

`mnote-transcription-demo.service` runs a DynamicUser on loopback port 8791,
with ProtectHome/ProtectSystem and a private writable state directory. Its Python
interpreter must resolve outside `/root` (the deployed runtime uses system Python
3.12); a venv pointing into a root-owned managed Python cache will not work with
ProtectHome. Install the exact inference pair in `../requirements.txt`.

Provision the small model's config/model/tokenizer/vocabulary files locally, and
set the environment keys from `../.env.example`. Use an independent high-entropy
Bearer secret; do not reuse a Mnote write token. The public sample environment
must point only at an operator-created recording you intentionally dedicate to
CC0, never at user or third-party media.

Install the unit and Nginx snippet under their names above. Add the snippet include
inside the existing HTTPS server block, back up the exact active configuration,
run `nginx -t`, then reload Nginx. Only restart the new transcription service;
never restart the independent capture/sync service as part of this release.
The deployed unit is enabled on boot. No source URL or private token is logged
by the dedicated API route.

Native Sites runtime bindings:

```text
MNOTE_TRANSCRIPT_API_URL=https://chenyu.online/mnote-transcript-api
MNOTE_TRANSCRIPT_TOKEN=<secret binding, same dedicated API token>
```

## Verified behavior

39 backend regression tests cover URL/DNS/redirect safety, owner isolation,
consent, bounded queue, timeouts, cleanup, exports and compatible dependency
readiness. A real live sample test downloads the public original recording,
decodes it, performs local CPU inference and exports TXT/Markdown/SRT. It also
checks foreign-owner reads return 404, omitted consent is rejected, and deleted
records cannot be read again. Test jobs are deleted after checks.

Run the opt-in live check from the Mnote repository on the deployment host:

```sh
python3 scripts/verify-transcript-demo-live.py
node scripts/verify-transcript-demo-browser.mjs \
  http://127.0.0.1:5173/ /absolute/path/to/qa-screenshots
```

The live checker reads the protected environment file without printing the token.
Browser QA uses a fresh isolated context in the existing local Chromium service;
it does not read the user's tabs or cookies. It tests the sample form's unchecked
consent, actual submission, timeline, exports, refresh recovery and delete modal.
It is local-preview evidence, not a substitute for the user's private production
login session. Native Sites reported a successful private production deployment.

Limits: 30 minutes / 80 MiB per media item, four active tasks, single CPU worker,
24-hour task/result retention, temporary media cleanup. Timing and recognition
quality vary; the six-second synthetic recording misrecognizes the proper name
Mnote as MNOD, illustrating why the UI asks users to review the text.

Bilibili/Douyin resolution is best-effort ordinary public access. This release
does not promise every share link will work and does not bypass login/signature/
DRM restrictions. Rights declarations are recorded but explicitly unverified.
Mnote can later reuse `/v1/jobs`, polling, exports and deletion, but its own account
authentication adapter has not yet been integrated.
