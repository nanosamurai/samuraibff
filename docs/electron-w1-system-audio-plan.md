# Electron (Windows-first) — W1 system output capture + mic mixing plan

This document describes how we will reuse the existing SamuraiBFF UI (ClojureScript)
as a Windows-first Electron application capable of capturing **microphone + system
output**, mixing them into a **single mono track**, and streaming the audio to the
existing BFF over the already-supported `/ws/audio` websocket.

Scope decisions (explicit):

* **Priority OS:** Windows (first milestone).
* **Capture approach:** **W1** = Electron/Chromium `desktopCapturer` + renderer
  `getUserMedia` with desktop source constraints.
* **Audio output:** single track = **mix(mic + system)** → **16kHz PCM16LE mono**.
* **Backend:** Electron connects to an **already-running** SamuraiBFF instance
  (default `http://localhost:8000`). We do **not** bundle/launch the backend.
* macOS/Linux: not in first milestone; may require virtual audio devices.

---

## 1) Architecture

### 1.1 Components

```
┌──────────────────────────────────────────────────────────────┐
│ Electron main process                                        │
│  - creates BrowserWindow                                     │
│  - exposes limited IPC/preload API                           │
│  - provides desktopCapturer sources                          │
└───────────────┬──────────────────────────────────────────────┘
                │ contextIsolation (preload bridge)
┌───────────────▼──────────────────────────────────────────────┐
│ Electron renderer (existing CLJS UI)                          │
│  - selects backend base URL (default localhost:8000)          │
│  - selects desktop capture source (screen/window)             │
│  - getUserMedia(mic) + getUserMedia(desktop source)           │
│  - WebAudio mixes sources → downsample → PCM16LE              │
│  - streams binary frames to /ws/audio (same as today)         │
└───────────────┬──────────────────────────────────────────────┘
                │ ws://localhost:8000/ws/audio  (PCM16LE mono)
┌───────────────▼──────────────────────────────────────────────┐
│ samuraibff backend (unchanged contract)                       │
│  - /ws/audio ingests PCM16LE mono                              │
│  - forwards to rtservice + Kafka etc                           │
└──────────────────────────────────────────────────────────────┘
```

### 1.2 Why we keep the backend contract unchanged

Backend handler `samuraibff.ws.audio` already supports:

* binary websocket frames
* **PCM16LE mono**
* `sample_rate=16000`

Therefore, adding “system output” capture is primarily a *frontend capture*
problem. Once we have samples, the existing downsample+PCM pipeline can be reused
unchanged.

---

## 2) Capture strategy (W1)

### 2.1 W1 = desktopCapturer + getUserMedia

Electron provides `desktopCapturer.getSources({types:["screen","window"]})`.
The renderer then calls `navigator.mediaDevices.getUserMedia` with Chromium
desktop constraints ("desktop" source id).

Important constraint:

* W1 is **best-effort** for “system output”. Depending on Windows config and the
  chosen source, the returned stream may contain **no audio tracks**.
* The UI must detect this (`getAudioTracks().length == 0`) and show remediation.

### 2.2 Mixing mic + system into one mono track

We mix in WebAudio and keep a single processing callback:

* mic stream → `MediaStreamAudioSourceNode`
* system stream → `MediaStreamAudioSourceNode`
* connect both into a mono “sum” node (downmix)
* `ScriptProcessorNode` (existing) reads the mixed buffer
* downsample to 16kHz + convert float32 → PCM16LE
* send ArrayBuffer frames over `/ws/audio`

This produces **one track** (mono) and avoids needing multi-channel changes in
xamurai/rtservice.

---

## 3) UI changes (high-level)

### 3.1 Backend base URL configurability

Electron production builds typically load UI from `file://.../index.html`, where
relative `/api/...` and `/ws/...` calls are not valid.

We will add an app-level configuration function that returns a backend base URL:

* Browser (served by BFF): empty base (same-origin).
* Electron: default `http://localhost:8000`, configurable (stored in
  localStorage).

All `fetch` and `ws-url` helpers will use this base.

### 3.2 System source selection UX

We will introduce:

* “Audio input mode”: `mic` | `system` | `mix` (default `mic`)
* “System source” dropdown/picker (screen/window)

Both settings live on the Live Recording page (Stream settings panel).

---

## 4) Electron app scaffold

### 4.1 File layout

Proposed new files:

* `electron/main.cjs` — Electron main process entry.
* `electron/preload.cjs` — safe preload bridge exposing:
  * `listDesktopSources()`

### 4.2 Security posture

We keep safe defaults:

* `contextIsolation: true`
* `nodeIntegration: false`
* preload exposes a minimal, typed surface only

---

## 5) Build & packaging

### 5.1 NPM scripts

We will extend `package.json` with:

* `electron:dev` — run shadow watch + start Electron
* `electron:dist` — build UI release + package Electron

### 5.2 Distributions

Per request, we will build both:

* **NSIS installer**
* **portable .exe**

via electron-builder targets.

Notes:

* On some Windows machines, extracting electron-builder helper binaries may fail
  due to missing symlink privileges. We disable Windows signing/editing for local
  builds (`win.signAndEditExecutable: false`) to avoid this.

---

## 6) CI/CD impact

Current CI already runs:

* Clojure tests
* UI build (`npm run ui:release`) on Ubuntu

We will add a new workflow or job:

* Runner: `windows-latest`
* Steps:
  * `npm ci`
  * `npm run ui:release`
  * `npm run electron:dist`
  * upload artifacts (NSIS + portable exe)

This will be introduced as **non-gating** initially (manual `workflow_dispatch`)
to avoid slowing PR checks.

---

## 7) Phased delivery plan (implementation steps)

## Status matrix (what is done vs planned)

This table is the current source-of-truth for this document.

Legend: ✅ done, 🟡 partial, ⏳ planned, ❌ not planned.

| Area | Item | Status | Notes / links |
|------|------|--------|---------------|
| Electron scaffold | Main process (`electron/main.cjs`) | ✅ | Creates BrowserWindow, loads UI |
| Electron scaffold | Preload bridge (`electron/preload.cjs`) | ✅ | Exposes `window.samuraibffElectron.listDesktopSources()` |
| Electron scaffold | Security posture (contextIsolation, nodeIntegration) | ✅ | Kept safe defaults |
| UI networking | Configurable backend base URL (`samuraibff.ui.env/backend-base-url`) | ✅ | Needed for `file://` builds |
| UI networking | All UI fetch/WS calls use backend base URL | ✅ | `ui.api`, `ui.auth`, `ui.ws`, `ui.audio` |
| UI capture | Mic capture mode | ✅ | Default mode |
| UI capture | System capture mode (Electron desktop) | ✅ | Best-effort; may have no audio track |
| UI capture | Mix mode (mic + system summed to mono) | ✅ | Gain controls included |
| UI capture | Desktop source picker UX | 🟡 | Implemented as “Pick/Change system source” auto-select; no full dropdown yet |
| Packaging | electron-builder config (`electron-builder.yml`) | ✅ | Builds NSIS + portable |
| Packaging | Local packaging works (`npm run electron:dist`) | ✅ | Signing/edit disabled for local builds |
| CI | Windows artifact workflow (`.github/workflows/electron-windows.yml`) | ✅ | Manual `workflow_dispatch` |
| Docs | Known limitations (no audio track, best-effort) | ✅ | See Risks section |
| Browser-only alt | Pure web “tab audio” capture (Chrome/Firefox) | ⏳ | Separate path; not implemented in Electron W1 |

### Phase A — Electron shell + dev loop
1. Add Electron main+preload scaffold.
2. Add npm deps + scripts.
3. Ensure Electron can load the UI.

### Phase B — Backend base URL plumbing
1. Add backend-base URL helper.
2. Update all `fetch` and WS URL builders.

### Phase C — W1 system capture + mixing
1. Add desktop source listing (preload + UI).
2. Implement system stream capture.
3. Implement mic+system mixing and stream to `/ws/audio`.
4. UX: clear errors when system audio is not available.

### Phase D — Packaging + CI
1. Configure electron-builder for Windows.
2. Add GitHub Actions workflow to build artifacts.

---

## 8) How to build & run (what is implemented)

This repo now contains a working Electron (Windows-first) app.

Important:

* Electron connects to an **already-running** SamuraiBFF backend.
* We do **not** bundle or launch the backend from Electron.

### 8.1 Prerequisites

* Node.js (recommended: 20.x)
* npm

### 8.2 Run locally (dev loop)

1) Start the backend (terminal 1):

```bash
clojure -M:run
```

2) Install UI/Electron dependencies (once):

```bash
npm install
```

3) Start Electron dev (terminal 2):

```bash
npm run electron:dev
```

What this does:

* starts `shadow-cljs watch` (continuous UI recompilation to `resources/public/js/main.js`)
* waits for the backend (`http://localhost:8000`)
* launches Electron and loads the UI from the backend

### 8.3 Build Windows artifacts (NSIS + portable)

```bash
npm run electron:dist
```

Outputs:

* `dist/electron/`

### 8.4 CI artifacts

Windows packaging is also available as a manual GitHub Actions workflow:

* `.github/workflows/electron-windows.yml`

It runs `npm run electron:dist` and uploads `dist/electron/**`.

---

## 9) Risks / fallback plan

If W1 cannot reliably capture output audio for Teams/Zoom on target Windows
machines, the next step is **W2** (WASAPI loopback helper), while keeping the
rest of the architecture intact (same backend contract, same UI mixer pipeline).
