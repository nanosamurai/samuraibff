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

## nREPL

Start an nREPL server on port **7888**:

```bash
clojure -M:nrepl
```

### nREPL MCP Server

To run the clojure repl MCP server:

```bash
clojure -X:mcp :not-cwd true :port 7888
```

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

## gRPC proto generation

To trigger generation and compilation of proto files:

```bash
clj -T:build proto+compile
```

## Running tests

Run all unit + integration tests:

```bash
clojure -X:test
```

Tests for workflow and webhook behavior that is unavailable in Community
Edition explicitly use `:features {:ce-mode? false}`; CE feature-gate tests
keep CE mode enabled. Static-asset routing tests use a small fixture
from `test-resources/` and do not require a compiled UI bundle.

Run the same lightweight test plan used as the fast PR check:

```bash
clojure -X:ci
```

### WS integration test (requires rtservice)

The test namespace is:

* `test/samuraibff/ws/ws_integration_test.clj` (`samuraibff.ws.ws-integration-test`)

Prerequisite:

* rtservice must be reachable at `localhost:50052`.
  * If rtservice is not reachable, the test is skipped.

If you run rtservice inside Kubernetes, port-forward it before running the full test suite:

```bash
kubectl port-forward svc/nanosamurai-stack-rtservice 50052:50052
```

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

