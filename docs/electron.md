# Electron (Windows-first) app

The Electron app is a Windows-first shell around the UI served by SamuraiBFF.

* It can capture **microphone** and best-effort **system output** audio.
* It mixes mic + system into one mono stream and sends it to the BFF through
  `/ws/audio`.
* Development and packaged builds both load the UI from the configured BFF.
* Recording playback and speaker enrollment use BFF endpoints. Electron never
  connects directly to S3, LocalStack, or another object-storage endpoint.

For detailed capture implementation notes, see
`docs/electron-w1-system-audio-plan.md`.

## Backend configuration

Electron reads its BFF origin at startup using this precedence:

1. `NANOSAMURAI_API_URL`
2. `SAMURAIBFF_ELECTRON_DEV_URL` (deprecated compatibility alias)
3. `http://localhost:8000`

The value must be an origin without a path, query, fragment, or embedded
credentials. Cloud and other non-loopback origins must use HTTPS. HTTP is
accepted only for `localhost`, `127.0.0.1`, and `[::1]`.

PowerShell examples:

```powershell
$env:NANOSAMURAI_API_URL = "http://localhost:8000"
npm run electron:dev
```

```powershell
$env:NANOSAMURAI_API_URL = "https://your-nanosamurai.example"
& ".\dist\electron\samuraibff 0.1.0.exe"
```

An invalid value stops startup with an error. If a valid BFF is unavailable,
Electron offers Retry or Quit instead of switching to another origin.

## Development

Prerequisites:

* Node.js 22.12 or newer and npm; Node.js 24 is used in CI
* a running SamuraiBFF backend

Start a source-backed BFF, then run:

```bash
npm install
npm run electron:dev
```

The command runs the Shadow CLJS watcher and Electron. The BFF serves the
resulting `resources/public/js/main.js`, so renderer assets, API requests,
recording audio, auth cookies, and WebSockets share one origin.

## Authentication and security

The main Electron window is pinned to the configured BFF origin. Unexpected
navigation and new windows are blocked, and IPC calls are accepted only from
that origin and window.

When authentication is required, the renderer asks the main process to open
`/auth/login` in an isolated child window. The child has sandboxing and context
isolation enabled, Node integration disabled, and no preload bridge. It shares
the default Electron session so the HttpOnly cookie set by `/auth/callback` is
available to the main BFF window. The child closes after returning to the
validated application route.

Before desktop sources are listed, Electron displays a native consent prompt.
Consent lasts only for the current main-window session. The bridge returns
source identifiers and names; it does not return screen thumbnails or icons.

## Build Windows artifacts

```bash
npm test
npm run electron:dist
```

Outputs are written to `dist/electron/` and include NSIS and portable builds.
The Electron package contains the shell and icons; the browser UI is served by
the configured BFF. This also avoids bundling a renderer version that can drift
from the backend API.

Windows Developer Mode, or equivalent symlink privileges, may be required for
electron-builder to embed executable icons. Artifacts remain unsigned unless
code-signing credentials are configured, so Windows SmartScreen may warn.

## Runtime maintenance

The packaged application pins Electron 43.4.0, which embeds Chromium 150 and
is supported by Electron through January 5, 2027. Electron supports only its
latest three stable major releases, so an Electron artifact must not be
released from an end-of-life major. Weekly Dependabot checks are restricted to
`electron` and `electron-builder`; major upgrades require the Electron breaking
changes review and the full packaging and smoke-test workflow described above.

## Known limitations

Chromium desktop capture is best-effort. Depending on Windows configuration and
the selected source, the captured desktop stream may contain no audio track.

The configured cloud BFF must serve an Electron-compatible UI. Preload features
remain capability-detected by the renderer so an older shell fails safely when
a newer optional bridge method is unavailable.
