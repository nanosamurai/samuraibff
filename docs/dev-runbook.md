# Dev runbook (local)

This repo intentionally documents **local developer workflows only**.

For the supported Community Edition Docker Compose setup, see the
[`nanosamurai` quickstart](https://github.com/nanosamurai/nanosamurai).

## Prerequisites

* Java + Clojure CLI tools (to run the backend)
* Node.js 22.12 or newer (Node.js 24 recommended) + npm

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

### UI styling conventions

The stylesheet is the `<style>` block in `resources/public/index.html`. The UI
is dark only and sets `color-scheme: dark`, so native parts (select lists,
scrollbars, checkboxes) follow the theme. Body text is 14px; controls inherit
the page font.

* **Icons:** use `samuraibff.ui.icons/icon` (inline 24x24 SVG in
  `currentColor`, 16px by default). Do not use Unicode glyphs or emoji as
  icons. New shapes are copied from [Lucide](https://lucide.dev) (ISC) into
  that namespace; there is no icon npm package. Icons are `aria-hidden`, so
  an icon-only button or link needs an `aria-label` (`router/link` accepts
  `:aria-label`).
* **Unavailable actions:** a `button` with `:disabled true`, or a
  `span.btn.disabled` with `:aria-disabled "true"` and a `title` that says
  why. Both render faded with a not-allowed cursor.
* **States:** `btn danger` for destructive buttons, `error` for inline error
  text, `badge ok|bad|warn|muted` for short states.
* **Chevrons:** native `select` elements get a chevron from CSS
  (`--chevron-down`); `details > summary` gets a turning chevron. Do not add
  arrow characters.
* **Focus:** one `:focus-visible` ring in the accent color for all controls.
  Do not add per-component focus rules. Animations stop under
  `prefers-reduced-motion`.
* **Fewer boxes:** in-page panels (`card`, `controls`, `list-item`) have one
  `--line` border and no shadow; shadows are for dialogs, menus and the
  drawer. Do not nest a bordered box inside a panel. Fields use the `--soft`
  fill with a border only on hover and focus. Secondary actions inside a
  panel (Settings, Close, Change) use `btn quiet`.
* **Wide screens:** the Record fields grow to share the row (`.controls-row`)
  and stack at full width below 768px. Check new layouts at phone width and
  on a wide monitor.

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


### Local test isolation

Integration fixtures bind Postgres, Kafka, LocalStack, Keycloak and HTTP ports to
`127.0.0.1`. For local runs, set `TESTCONTAINERS_RYUK_DISABLED=true` so the
auxiliary reaper does not publish an unrestricted port; fixture cleanup stops
the containers. Docker build contexts exclude `.env` and scratch worktrees.
