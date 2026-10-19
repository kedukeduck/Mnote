# Anonymous desktop-browser resolution

This optional adapter keeps the existing public demo and account/visitor binding.
Only public video IDs are sent to the browser; it never receives an owner ID,
the API token, user cookies, transcript text, model directory or database.
It is best-effort, not a guarantee of access to every Douyin work.

## Flow and boundaries

1. The core validates HTTPS and the exact short-link/redirect hosts, then stops at
   a canonical numeric video ID. Image posts fail with `image_post_unsupported`;
   their music is not treated as the text of the post.
2. The private Unix HTTP adapter starts a new Chromium process with a fresh
   temporary profile for this task. No existing browser or CDP port is reused.
   Normal first-party anonymous browser state stays only in this profile.
3. Normal desktop-page loading supplies target-matching public metadata. There
   is no account login, imported profile/cookie, stealth patch, manually generated
   website signature, CAPTCHA handling or private API replay. Observed access
   denial, verification requirements and rate limiting are terminal.
4. The browser only returns a bounded item record for the requested ID; no raw
   page, browser storage, request headers, cookies or recommendations are returned.
5. The core prefers low bandwidth, keeping the normal player representation as
   a fallback when a regional low-rate CDN rejects the file. At most three explicit
   target-media URLs are validated. Downloads use TLS-verified, pinned-public-IP
   `SafeClient`, without browser cookies or referrer spoofing, with the existing
   80 MiB / 30 minute bounds and shared 120-second download budget.
6. FFmpeg and the locally provisioned speech model process the resulting local
   file. A readable media response does not by itself prove complete download,
   decoding, speech recognition or rights ownership.

The installed extra media hostname is exactly `v3-dy-o.zjcdn.com`, observed in
the target site's metadata and verified by a no-cookie GET response. The whole
shared CDN suffix is not opened. An API/website change can require a reviewed
update of exact hostnames; the helper never accepts new hostnames from a client.

## Network isolation

The browser service uses `PrivateNetwork=true`: neither the Internet, host
loopback API ports nor the existing QA browser on port 9222 can be reached
directly. A private-namespace TCP bridge on 127.0.0.1:8794 forwards to the pathname
Unix socket `/run/mnote-transcription-egress/proxy.sock` outside the namespace.
This is not a public proxy and exposes no new TCP listener on the host.

The independent egress process authenticates CONNECT requests, accepts only HTTPS
port 443 for a short exact-host allowlist, checks **all** DNS answers are public,
connects to an approved literal IP and validates visible ClientHello SNI matches
the requested host. Opaque TLS extensions, including Chromium's GREASE ECH noise,
are preserved; the proxy does not claim to authenticate an encrypted inner name.
The browser separately enforces exact URL hosts, disables ECH configuration and
has no direct DNS/DoH route. TLS remains end-to-end and certificate verification
is never disabled. There is no direct-connect fallback. The browser separately
blocks images, media, fonts, unsupported protocols and non-allowlisted requests.
The proxy caps connections, input, tunnel bytes and wall-clock duration.

This design uses systemd's [network namespaces](https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html#PrivateNetwork=)
instead of changing global firewall rules or SSH/network settings.

## Runtime and sandbox

The Chromium binary/resources and Node runtime are provisioned outside `/root`:

```text
/opt/mnote-transcription-browser/node
/opt/mnote-transcription-browser/chromium/chrome
/opt/mnote-transcription-browser/browser_worker.mjs
/opt/mnote-transcription-browser/browser_egress.py
/etc/mnote-transcription-browser/worker.env       # 0700 directory, 0600 file
/etc/mnote-transcription-browser/egress.env       # independent proxy credential
/run/mnote-transcription-browser/api.sock
/run/mnote-transcription-egress/proxy.sock
```

Use a maintained, locally verified Chromium build and retain its license files.
No binary, model, production token or profile belongs in Git. `--no-sandbox`,
`--disable-setuid-sandbox`, `--ignore-certificate-errors` and root-run Chromium
are not used. Chromium children receive a minimal environment with no service
credentials. Process groups are killed on finish/cancel/timeout, the temporary
profile is removed, and systemd uses control-group cleanup when the unit stops.

Ubuntu may restrict unprivileged user namespaces. Install the narrowly scoped
`mnote-transcription-browser.apparmor` profile, which grants the namespace
permission required for Chromium's own sandbox. It intentionally does not claim
to be a full filesystem AppArmor policy: filesystem/network restrictions come
from the independent DynamicUser, ProtectHome/ProtectSystem/InaccessiblePaths
and private-network systemd unit. Do not disable global AppArmor/sysctl controls.

## Configure and install

`worker.env` requires two independent random secrets (at least 32 characters):

```text
MNOTE_TRANSCRIPT_BROWSER_TOKEN=<independent local adapter secret>
MNOTE_BROWSER_PROXY_PASSWORD=<independent proxy secret, urlsafe 32..128 characters>
```

`egress.env` contains only the same `MNOTE_BROWSER_PROXY_PASSWORD`; it must not
contain the API token or browser API token. Optional exact static hosts use
`MNOTE_BROWSER_EXTRA_HOSTS` in **both** files after operator review. Do not put
credentials in JavaScript bundles or send source session cookies to the core.

Install/verify the AppArmor profile and the two dedicated systemd units, start
egress first, then browser, and wait for the private socket's `/health` response.
Before enabling the core adapter, verify an actual anonymous metadata resolution,
no-cookie media response headers, cancellation/profile cleanup, and blocked
private/direct network access. Then add these two entries to the protected core
environment file and restart only an idle transcription API:

```text
MNOTE_TRANSCRIPT_BROWSER_SOCKET=/run/mnote-transcription-browser/api.sock
MNOTE_TRANSCRIPT_BROWSER_TOKEN=<same independent local adapter secret>
```

The two new units should be enabled on boot only after validation. The capture/
sync, Android, Windows and old Sites deployment are not part of this release.

## Checks and rollback

```sh
python3 -m unittest discover -s transcription-service/tests
node --test transcription-service/tests/browser_worker.test.mjs
python3 scripts/verify-transcript-public-live.py
```

Mock tests cover target identity, forged owners, Unix authentication, bounded
responses, cancellation, early EOF, proxy SNI/DNS/address safety, strict hosts,
single-browser admission and secret redaction. The public end-to-end test uses
the operator-created CC0 recording, not third-party transcripts.

`MNOTE_BROWSER_DIAGNOSTICS=1` is an operator-only temporary option: private helper
errors may include bounded host/status counters, never URLs, cookies or headers.
The core does not forward these details. Disable it after diagnosing a deployment.

To disable the adapter, remove/empty the core socket configuration and restart
only the idle transcription API. Stop/disable the two new units; the old API and
public/private UI paths remain available. Unload only the dedicated AppArmor
profile after the browser has stopped if fully uninstalling. Keep source backups
and Git rollback points; do not alter global firewall or existing service state.
