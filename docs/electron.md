# Electron (Windows-first) app

The Electron app is a Windows-first wrapper around the existing SamuraiBFF UI.

* It can capture **microphone** and (best-effort) **system output** audio.
* It mixes mic + system into a **single mono** stream and sends it to the backend
  over the existing `/ws/audio` WebSocket.

For the detailed design/implementation notes, see:

* `docs/electron-w1-system-audio-plan.md`

## Prerequisites

* Node.js (recommended: 20.x) + npm
* A running SamuraiBFF backend (default `http://localhost:8000`)
* Windows Developer Mode (or equivalent symlink privileges) when packaging, so
  electron-builder can extract the helpers used to embed executable icons

## Dev run

1) Start backend:

```bash
clojure -M:run
```

2) Start Electron (runs shadow watch + Electron):

```bash
npm install
npm run electron:dev
```

Notes:

* In dev, Electron loads the UI from the running backend (default `http://localhost:8000`).
* `shadow-cljs watch` runs in parallel and continuously recompiles the UI bundle into `resources/public/js/main.js`.

## Build Windows artifacts

```bash
npm run electron:dist
```

Outputs:

* `dist/electron/` (NSIS installer + portable exe)

The Electron window, taskbar entry, executables, shortcuts, installer, and
uninstaller use the nanosamur.ai icon. The committed assets are generated from
`resources/public/img/nanosamurai_logo_finished_shoulders.svg`:

* `build/icon.png` - transparent 1024x1024 runtime icon
* `build/icon.ico` - multi-resolution Windows packaging icon

The artifacts remain unsigned unless code-signing credentials are configured,
so Windows SmartScreen warnings may still appear.

## Known limitations

Chromium desktop capture is best-effort: depending on Windows configuration and
the selected source, the captured desktop stream may contain **no audio track**.
