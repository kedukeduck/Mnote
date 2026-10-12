# Mnote public transcript frontend

Standalone static React frontend for the public, self-hosted transcript demo. No Sites, Next.js, login screen, browser API key, or pre-generated transcript is included.

## Build

```sh
npm ci
npm run typecheck
npm run build
```

The Vite base path is `/mnote-transcript/`. Serve the generated `dist/` directory from this path, for example using an Nginx alias. Frontend assets do not require a Node process in production.

The existing server gateway must expose `/mnote-transcript/api/transcripts/` on the same origin, isolate jobs using its anonymous browser-session cookie, and protect mutations. The frontend sends same-origin cookies and never supplies upstream credentials or an owner identifier.

Supported calls: config, create job, poll/resume job, TXT/MD/SRT download, and delete. The form requires a new explicit rights consent for each submission. The optional original CC0 audio button fills the form but never checks consent or submits automatically.

Only the last task identifier is placed in sessionStorage under `mnote-transcript-job-id`; share text, rights notes, and transcript bodies are not persisted by the frontend. Jobs default to 24-hour retention (as reported by server config). This is browser-session isolation, not an authenticated private workspace; people sharing the same browser may access the same task. Clearing cookies can make a previous task inaccessible.

## Local preview

```sh
npm run dev
```

The dev server serves `/mnote-transcript/`. It does not proxy or simulate backend API responses. A working same-origin gateway is required for real transcription.
