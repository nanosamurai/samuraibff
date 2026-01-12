Aim of this clojure project (samuraibff) is to rewrite and further evolve
 drsynth's bff written in python (see C:\Users\miros\PycharmProjects\drsynth\bff\src\bff)
 for drsynth project ( C:\Users\miros\PycharmProjects\drsynth\ ) .
Please read its readme.md (C:\Users\miros\PycharmProjects\drsynth\README.MD) to get idea what it does.

I decided to isolate the bff and rewrite it in clojure.
For the data model, please look at C:\Users\miros\PycharmProjects\drsynth\proto\stream.proto .

# AGENTS.MD — samuraibff (Clojure) Architecture + House Rules

## Status (current implementation)

### Implemented (as of now)

* **HTTP server** via Ring + http-kit (Integrant key `:samuraibff/http-server`)
* **Reitit router** (Integrant key `:samuraibff/router`)
    * `GET /health`
    * WS endpoints under `/ws/*`
    * UI endpoints:
      * `GET /` – serves `resources/public/index.html`
      * `GET /recordings` – SPA route (serves index.html; deep-link safe)
      * `GET /recordings/:session_id` – SPA route (serves index.html; deep-link safe)
      * `GET /live` – SPA route (serves index.html; deep-link safe)
      * `POST /api/sessions` – returns `{ "session_id": "<uuid>" }`
      * static assets from `resources/public/*` (incl. shadow-cljs output under `/js/*`)
* **WebSockets** (http-kit)
    * `GET /ws/audio` – accepts **binary** PCM16LE frames and enqueues them (non-blocking) into a per-session bounded `core.async` channel.
    * `GET /ws/events` – streams **JSON** event maps back to the frontend.
* **In-memory WS session registry** (Integrant key `:samuraibff/ws-registry`)
    * per-session channels (`:audio-ch`, `:events-ch`) and `mult` for event fanout
    * per-session monotonic `:seq` and `:chunk-seq`
    * backpressure: drops audio frames when buffer is full (with throttled warning logging)
* **gRPC client** to rtservice (Integrant key `:samuraibff/grpc-client`)
    * opens bidirectional stream and forwards `AudioChunk` messages
    * converts incoming `AsrEvent` to WS event maps
    * stream close is idempotent to avoid noisy half-close exceptions
* **Config component** (Integrant key `:samuraibff/config`)
    * minimal identity component so `resources/system.edn` works with `ig/load-namespaces`
* **CLI entrypoint**
    * `samuraibff.core/-main` so `clojure -M:run` starts the Integrant system
    * `samuraibff.system/read-system-config` uses `integrant.core/read-string` so `#ig/ref` tags work
    * `samuraibff` namespace acts as an Integrant loader/aggregator for component namespaces
* **ClojureScript UI scaffold**
    * shadow-cljs build (`shadow-cljs.edn`) output to `resources/public/js`
    * React + HSX (Hiccup-ish) rendering (no Reagent)
    * App shell UI (top bar + sidebar) branded as **nanosamur.ai**
    * Pages (MVP):
      * **Recordings** table (in-memory list in browser for now)
      * **Live Recording** page with transcript feed + right-side Log panel
      * Recording detail placeholder view
    * microphone capture + PCM16LE streaming to `/ws/audio`
    * realtime transcript display via `/ws/events`
* **Tests**
    * unit tests for ws registry
    * integration tests for http server + grpc client
    * WS integration test that opens `/ws/events` and `/ws/audio` and asserts a `status` event is received
      (requires rtservice on `localhost:50052`; otherwise skipped)

### Pending / not yet implemented (planned PRs)

* OIDC / Keycloak authentication and tenant/user derivation
* Kafka integration (publish audio chunks, etc.)
* Postgres persistence (sessions/transcripts)
* HTTP callback for refined transcripts (whisperx worker) + merge logic
* OpenAPI/Swagger polishing for non-WS endpoints (WS not covered by OpenAPI)
* Operational endpoints `/ready`, `/metrics`

---

## 0) House rules (engineering discipline)

### Documentation & intent

* Every public (and even every larger private where just the name might not be self-explanatory) function **must have a docstring** describing:

    * purpose
    * expected inputs (shape + types)
    * expected outputs (shape + types)
    * thrown exceptions / error behavior (if relevant)
* Prefer Malli schemas (see below) to document shapes explicitly; docstrings should reference schema names where helpful.

### REPL-driven workflow

* All new code should be validated via REPL early:

    * load namespace
    * run focused unit tests
    * exercise functions against realistic sample inputs
* Use the “REPL MCP” workflow (or equivalent) for quick feedback:

    * evaluate functions at the REPL
    * validate schema coercion and error cases interactively
    * confirm Integrant component lifecycle start/stop is clean

### Testing is not optional

* Every new function should ship with tests:

    * unit tests for pure logic (schemas, merges, ordering rules)
    * integration tests where the boundary matters (DB, Kafka, HTTP, WS)
* If a change affects an API contract or message semantics, update tests accordingly.

### Style & readability

* Follow: https://guide.clojure.style/
* Crucial additions we want to enforce:

    * Keep namespaces cohesive; avoid “god” namespaces.
    * Prefer pure functions + explicit dependencies (pass deps in) over implicit globals.
    * Avoid long functions; break into named helpers with good docstrings.
    * Use `let` bindings with meaningful names; avoid “x/y/z” except in tight math code.
    * Never hide side effects: name side-effecting fns with verbs (`start!`, `stop!`, `persist!`, `publish!`, `send!`).
    * Keep logs structured and consistent (include `session-id`, `tenant-id`, `user-id` when applicable).

---

## 1) Core goals for the new BFF

The Clojure BFF is the **canonical API + orchestration layer**:

* Accepts browser audio via WS and produces **Kafka audio chunks**.
* Streams audio to rtservice over **gRPC** (realtime ASR).
* Pushes realtime + refined transcript events to UI.
* Receives refined transcript data via **HTTP callback** from whisperx worker (not Kafka).
* Performs OIDC authentication (Keycloak), derives tenant/user, and persists session/transcript metadata in Postgres.

We explicitly want:

* Clean boundaries (Kafka, gRPC, HTTP/WS, DB).
* A codified data model via **Malli schemas**.
* Robust lifecycle management via **Integrant**.
* Concurrency managed primarily by **core.async**.
* Thorough tests: unit + integration (testcontainers-clj) + end-to-end UI (etaoin).

---

## 2) Technology stack (locked in)

### Server and routing

* HTTP: Ring + http-kit
* Routing: **Reitit**
* Schemas/coercion: **Malli**
* WebSocket: **http-kit**

### gRPC

* gRPC client: Java stubs generated from `resources/proto/stream.proto`

### Logging

Use org.corfield.logging4j2 for logging. To new namespaces, import it as follows: `[org.corfield.logging4j2 :as log]`. Do not use `print` and `println` for logging.

---

## 3) High-level architecture (target)

### Inbound interfaces

1. UI HTTP (static + REST)
2. WS `/ws/audio`: browser → BFF (binary PCM16 LE)
3. WS `/ws/events` (or SSE): BFF → browser (JSON events: asr/refined/status)
4. HTTP callback: whisperx worker → BFF (refined transcript segments)

### Outbound interfaces

1. Kafka: BFF produces `audio.raw.*` (not yet)
2. gRPC: BFF streams to rtservice (RealtimeASR.Stream)
3. Postgres: persist sessions, recordings metadata, transcripts, speakers (not yet)

---

## 4) Proposed repo / namespace structure

(Kept as a long-term target; current repo may still be evolving towards this.)

```
src/
  clj/samuraibff/
    core.clj
    system.clj
    config.clj
    http/server.clj
    http/router.clj
    http/ui.clj
    ws/audio.clj
    ws/events.clj
    ws/registry.clj
    grpc/client.clj
  cljs/samuraibff/ui/
    core.cljs
    components.cljs
    store.cljs
    ws.cljs
    audio.cljs
    ...
```
