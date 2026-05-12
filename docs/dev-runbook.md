# Dev runbook (local)

This repo intentionally documents **local developer workflows only**.

For Docker/Kubernetes/CI/CD orchestration, see the **nanodeploy** repository.

## Prerequisites

* Java + Clojure CLI tools (to run the backend)
* Node.js (recommended: 20.x) + npm

## Backend (HTTP + WS) — dev

The backend reads configuration from `resources/system.edn` with optional env
overrides.

```bash
clojure -M:run
```

Default local bind is `127.0.0.1:8000`.

## UI (ClojureScript) — dev

Install npm deps once:

```bash
npm install
```

Then run the UI watcher:

```bash
clojure -M:cljs watch app
```

Open:

* http://127.0.0.1:8000/recordings
* http://127.0.0.1:8000/live

## Electron (Windows-first) — dev

Electron connects to an **already-running** backend. It does not bundle or start
the backend.

```bash
npm run electron:dev
```

Notes:

* This runs `shadow-cljs watch` and then starts Electron.
* Electron loads the UI from the shadow dev server and calls the backend at
  `http://127.0.0.1:8000` by default.

## Electron (Windows) — packaging

```bash
npm run electron:dist
```

Outputs:

* `dist/electron/` (NSIS installer + portable exe)
