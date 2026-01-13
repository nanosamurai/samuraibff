# samuraibff

Clojure rewrite of the **drsynth** BFF (backend-for-frontend).

This repo currently focuses on:

### 1) MVP realtime path
- accept browser audio via WebSocket (`/ws/audio`) as **binary PCM16LE**
- forward audio frames to **rtservice** via **gRPC** (bidirectional stream)
- stream realtime events back to the UI via WebSocket (`/ws/events`) as **JSON**

### 2) Near-realtime refinement path (Kafka + cross-instance routing)
- publish `AudioChunk` protobuf messages to Kafka topic **`audio.raw`**
- consume `RefinedEvent` protobuf messages from Kafka topic **`transcripts.refined`**
- forward refined events to the **originating BFF instance** using an internal callback:
  - `POST /internal/refined` with `application/x-protobuf`
- push refined transcript segments to the UI over `/ws/events` as `{"type":"refined", ...}` events

> OIDC/Keycloak and Postgres persistence are **not implemented yet**.

## Running the server

### Start the HTTP server (dev)

The system configuration lives in `resources/system.edn`.

In a terminal:

```bash
clojure -M:run
```

### UI (ClojureScript) dev workflow

The repo includes a minimal CLJS UI (React + HSX) meant as a **test harness** for the WS pipeline.

Prerequisites:

```bash
npm install
```

In terminal 1 (backend):

```bash
clojure -M:run
```

In terminal 2 (UI watcher):

```bash
clojure -M:cljs watch app
```

Then open one of:
- http://127.0.0.1:8000/recordings (default landing page)
- http://127.0.0.1:8000/live

The UI will:
- show an **app shell** (top bar + sidebar) branded as **nanosamur.ai**
- manage an **in-memory** list of recordings (MVP only; no persistence yet)
- create a new session via `POST /api/sessions`
- open `/ws/events?session_id=<uuid>` and display realtime transcript events
- capture microphone audio (PCM16LE) and stream it to `/ws/audio?session_id=<uuid>&lang=en&sample_rate=16000`

Notes:
- The compiled JS bundle is large (shadow-cljs dev output). Avoid printing it to the terminal.
- If you change CLJS code, shadow-cljs will hot-reload via `^:dev/after-load`.

#### UI structure & conventions

The UI lives under `src/cljs/samuraibff/ui/` and is intentionally small, with a few simple rules:

- **Rendering**: React + **HSX** (Hiccup-ish) via `io.factorhouse/hsx`.
  - Components are plain functions that return hiccup-like vectors.
  - We do **not** use Reagent.
- **Entry point**: `samuraibff.ui.core`
  - owns the React root (`react-dom/client`)
  - calls `init` and provides the hot-reload hook (`^:dev/after-load`)
- **Components**: `samuraibff.ui.components`
  - add new UI pieces here as small, pure render functions
  - keep side-effects (WS, audio, fetch) out of component render bodies
- **State**: `samuraibff.ui.store`
  - use the store atom as the single source of truth for session id, ws status, transcript segments and log
  - mutate state via explicit functions (`set-…!`, `append-…!`, etc.)
- **Effects / I/O**:
  - HTTP calls in `samuraibff.ui.api` (e.g. create session)
  - events WS client in `samuraibff.ui.ws`
  - microphone capture + audio WS streaming in `samuraibff.ui.audio`

Adding a new feature typically means:
1. extend the store data + add a small `…!` mutator in `store.cljs`
2. add the I/O code in `api.cljs` / `ws.cljs` / `audio.cljs`
3. add a small rendering function in `components.cljs` and mount it from `components/app`

Defaults:
- HTTP server: `0.0.0.0:8000`
- Health endpoint: `GET /health`
- WebSockets:
  - `GET /ws/events?session_id=<uuid>`
  - `GET /ws/audio?session_id=<uuid>&lang=<code>&sample_rate=16000`
- Internal callback (BFF-to-BFF):
  - `POST /internal/refined` (`application/x-protobuf` body of `RefinedEvent`)
- UI (SPA routes served as index.html for deep-link refresh):
  - `GET /` (SPA index)
  - `GET /recordings`
  - `GET /recordings/:session_id`
  - `GET /live`
- UI helpers:
  - `POST /api/sessions`
- rtservice: `localhost:50052` (see `resources/system.edn`)

Kafka topics (defaults in `resources/system.edn`):
- `audio.raw`
- `transcripts.refined`

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

### WS integration test (requires rtservice)

The test namespace is:
- `test/samuraibff/ws/ws_integration_test.clj` (`samuraibff.ws.ws-integration-test`)

Prerequisite:
- **rtservice must be reachable at `localhost:50052`**.
  - If rtservice is not reachable, the test is **skipped**.

Run it via the normal test runner:

```bash
clojure -X:test
```

Notes:
- The test opens `/ws/events` and `/ws/audio`, sends a few dummy audio frames,
  and asserts a `{"type":"status" ...}` event arrives.
- The test uses `com.neovisionaries/nv-websocket-client` (test-only dependency)
  because the JDK WebSocket client was unreliable against http-kit for us.
