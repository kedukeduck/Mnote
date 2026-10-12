# Anonymous, self-hosted demo

Public URL: https://chenyu.online/mnote-transcript/

The earlier Sites demo remains owner-private as a separate deployment. This
standalone React/Vite frontend is served by Nginx on the existing server and does
not require a ChatGPT account, OAuth, an API key or a manually entered token.
The original inference service still runs independently on loopback port 8791;
the new anonymous gateway on loopback port 8792 is its only public adapter.
Mnote Android, Windows and the capture/sync service are not changed.

## Browser-session isolation

`GET /mnote-transcript/api/transcripts/config` establishes a random 24-hour
visitor cookie. Its ID/expiry are HMAC-signed using an independent environment
secret; its derived SHA-256 owner ID is never chosen by the browser. The cookie
is `__Host-mnote-transcript-visitor`, with Secure, HttpOnly, SameSite=Strict,
Path=/ and no Domain. The host prefix avoids subdomain Domain-cookie fixation
in supporting browsers; see [MDN Set-Cookie](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie#cookie_prefixes).
Different visitors cannot read, export or delete each other's jobs, even if
they know the other task's UUID. The gateway also checks its own admission
records before forwarding job access to the core service.

This is not an authenticated account or guaranteed anonymity. People sharing
the same browser profile share a visitor session. Clearing cookies, switching
browser/profile, or expiry may make tasks inaccessible. The frontend stores
only its most recent task UUID in sessionStorage; no transcript/share text is
persisted in browser storage. Download and remove work before using a shared
browser. The core keeps tasks/text for at most 24 hours and cleans temporary
media. Rate-limit admission metadata is pruned after 24 hours on subsequent
admission/job access (not by an idle background sweeper). Logical
database deletion is not a guarantee of physical erasure on SSDs or backups.

## Public resource bounds

- One active task per visitor; at most three admitted submissions per rolling
  hour, and forty across the entire public demo per UTC day.
- SQLite reservations persist across gateway restarts. A submission with an
  uncertain upstream result is not automatically retried or refunded; it is
  conservatively held until the core's 24-hour job TTL. Deleting or failing an
  admitted job does not refund its submission quota.
- The core's queue is still globally limited to four active jobs, 30 minutes /
  80 MiB per source, a bounded inference child and local-only media decoding.
- Nginx limits request rate, body size and concurrent requests. Because sslh
  forwards TLS to loopback on this host, peer-IP buckets may aggregate all
  visitors. They are an extra shared-ingress bound, not verified user-IP limits.
- POST/DELETE require the exact configured same origin. No CORS is enabled.
  The gateway overwrites upstream credentials and never follows redirects;
  the API token and cookie signing secret never appear in frontend files.

## Install and rollback

Build `../public-web/` using its pinned lockfile (`npm ci`, `npm run build`).
Publish its static `dist/` to a release directory beneath
`/var/www/mnote-transcript-releases/` and point
`/var/www/mnote-transcript-current` to that release. Assets use the fixed
`/mnote-transcript/` base, so no Node process is needed in production.

Install `../public_gateway.py` under `/opt/mnote-transcription-demo/`, and
`mnote-transcription-public.service` under `/etc/systemd/system/`. Its
DynamicUser and StateDirectory are separate from the core service. The gateway
uses system Python and stdlib only; do not install another model or inference
runtime for it.

The protected `/etc/mnote-transcription-demo/service.env` needs:

```text
MNOTE_TRANSCRIPT_TOKEN=<existing dedicated core API token>
MNOTE_TRANSCRIPT_SESSION_SECRET=<independent random secret, at least 32 bytes>
MNOTE_TRANSCRIPT_GATEWAY_DATA=/var/lib/mnote-transcription-public
MNOTE_TRANSCRIPT_PUBLIC_ORIGIN=https://chenyu.online
```

Install the `nginx-mnote-transcript-public-limits.conf` contents in Nginx's
HTTP context (`conf.d`), and the public/proxy snippets under `snippets` with
the names used by their includes. Add the public snippet to the existing HTTPS
server block only after backing up the exact active configuration. Run
`nginx -t` before reload; keep the capture and private demo routes unchanged.
Only start/restart the new gateway when its source changes. Enable its unit on
boot after verifying the full public flow.

For rollback, remove the public snippet's server include, test/reload Nginx,
then stop only `mnote-transcription-public.service`. Existing static releases,
source history and private/core services remain available. A UI-only rollback
can repoint the current static symlink to a retained known-good release.
Do not rotate the session signing secret unless intentionally invalidating
existing visitors' access to their still-retained jobs.

## Verification

Run backend and gateway mock regressions:

```sh
python3 -m unittest discover -s transcription-service/tests -v
```

The opt-in production checks use only the operator's original CC0 synthetic
speech and delete their jobs afterwards:

```sh
python3 scripts/verify-transcript-public-live.py
node scripts/verify-transcript-demo-browser.mjs \
  https://chenyu.online/mnote-transcript/ /absolute/path/to/screenshots
```

The HTTP check uses two fresh cookie jars, not an API secret. Browser QA creates
an isolated context in the existing Chromium service; it has no logged-in
session and does not read any user's tabs/cookies. It checks no-login access,
cookie security, desktop/mobile layout, original sample submission, actual text,
timeline, exports, refresh recovery and deletion.

Source resolution remains ordinary public access only. No-login access to this
demo does not remove Bilibili/Douyin's restrictions or guarantee every share link
will resolve. The rights declaration remains explicitly unverified.
