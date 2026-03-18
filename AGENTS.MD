Aim of this clojure project (samuraibff) is to rewrite and further evolve
 xamurai's (aka drsynth's) bff that was originally written in python 
 for xamurai project ( C:\Users\miros\PycharmProjects\xamurai\ ) .
Please read its readme.md (C:\Users\miros\PycharmProjects\xamurai\README.MD) to get idea what it does.

I decided to isolate the bff and rewrite it in clojure.
For the data model, please look at proto at resources/proto/stream.proto and also at our Malli schema in src/clj/samuraibff/schemas.clj .

## Main Rules / Best Practices
- You are usually being run from CLINE IDE plugin, which limits you to the given working repository. If you want to execute commands (i.e. run executables, make git commands) in other folders / repositories, the only way you can do so is via powershell `Set-Location`
    - e.g. `powershell -NoProfile -Command "Set-Location 'C:\Users\miros\IdeaProjects\samuraipersistor'; clojure -M -e '(println :ok)'"`
- All changes should be documented accordingly in the relevant .MD docs; before claiming task is coplete, always check docs for gaps as well as for a stale or no longer correct information and fix that
- For any task, create a dedicated branch (named imperatively) from master (unless told otherwise) and as you progress make small(er) atomic commits named imperatively
- For a task to be completed, there should be no security risks or concerns, always assess task's completion also from the security standpoint and highlight any risks.
- NEVER EXPOSE PORTS on local LAN for testing, always use localhost or 127.0.0.1
- Xamurai used to be called "drsynth", so do not be confused if you do see this name somewhere. That name however is legacy and you should always use xamurai when naming things related to xamurai stack- alternatively, you can also use nanosamurai if the name refers to the whole solution and not just the subset of voice-to-text services
- before you claim any task is complete, run full set of test/integration tests
- DO NOT EVER attempt to read or fetch the compiled js code (usually at resources/public/js/main.js ) - it is too big and your cotext will rot into oblivion

# About Nanosamur.ai
Nanosamurai solution currently consists of
- **SamuraiBFF** (this repo),
- **xamurai** (https://github.com/mikub/xamurai ; locally usually at `%USERPROFILE%\PycharmProjects\xamurai`) services connected via Kafka/gRPC
    - xamurai is a monorepo that contains multiple Speach-to-text and speaker diarization services for Nanosamur.ai solution - they provide both real-time transcription as well as near-real-time refinement.
- **samuraipersistor** (https://github.com/mikub/samuraipersistor ; locally usually at `%USERPROFILE%\IdeaProjects\samuraipersistor` ) for persistence.
    - Database persistence is handled by a separate service, the samuraipersistor; consumes from all relevant Kafka topics and stores the data in Postgres
- **nanodeploy** - Helm charts for local k8s  and aws EKS deployments and also local docker compose setup
- **nanplatform** - Pulumi infra setup in AWS

# AGENTS.MD — samuraibff (Clojure) Architecture + House Rules

## Status (current implementation)

### Implemented (as of now)

* **Postgres DB pool** via HikariCP (Integrant key `:samuraibff/db`)
    * configured via `:db` section in `resources/system.edn`
    * provides `{:ds <javax.sql.DataSource>}` to handlers/components

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
        * session id is generated as **UUIDv7**
        * a `sessions` row is inserted into Postgres (tenant + optional user)
        * when auth is disabled, a dev fallback tenant id is used (`00000000-0000-0000-0000-000000000000`)
      * static assets from `resources/public/*` (incl. shadow-cljs output under `/js/*`)
* **WebSockets** (http-kit)
    * `GET /ws/audio` – accepts **binary** PCM16LE frames and enqueues them (non-blocking) into a per-session bounded `core.async` channel.
    * `GET /ws/events` – streams **JSON** event maps back to the frontend.
* **In-memory WS session registry** (Integrant key `:samuraibff/ws-registry`)
    * per-session channels (`:audio-ch`, `:events-ch`) and `mult` for event fanout
    * per-session monotonic `:seq` and `:chunk-seq`
    * backpressure: drops audio frames when buffer is full (with throttled warning logging)
    * can publish refined events into existing local sessions (`publish-refined-proto!`)
* **Kafka producer** (Integrant key `:samuraibff/kafka-producer`)
    * publishes `AudioChunk` protobuf messages to Kafka topic `audio.raw`
    * includes `lang` and `bff_origin_uri` (reply-to) in produced messages
* **Kafka refined consumer** (Integrant key `:samuraibff/refined-consumer`)
    * consumes `RefinedEvent` protobuf messages from Kafka topic `transcripts.refined`
    * if session exists locally: delivers to WS
    * otherwise forwards to origin BFF via `POST /internal/refined` (protobuf body)
* **Internal HTTP callback endpoint**
    * `POST /internal/refined` – accepts protobuf `RefinedEvent` and delivers it only if session exists locally
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
    * **refined transcript rendering** via `/ws/events` (`{"type":"refined" ...}`)
      * UI is Slack-like (avatar/name/time + bubble)
      * refined segments are marked and replace ASR segments based on time window containment
      * UI shows `start → end` for debugging time alignment
* **Tests**
    * unit tests for ws registry
    * integration tests for http server + grpc client
    * Postgres integration test for session persistence using **Testcontainers**
      (`test/samuraibff/http/ui_test.clj`)
    * WS integration test that opens `/ws/events` and `/ws/audio` and asserts a `status` event is received
      (requires rtservice on `localhost:50052`; otherwise skipped)

### Pending / not yet implemented (planned PRs)

* Refined transcript merge logic (windowing / overlap resolution) + UI rendering polish
  (Note: UI-side refined replacement is now implemented; backend merge/windowing may still evolve.)
* OpenAPI/Swagger polishing for non-WS endpoints (WS not covered by OpenAPI)

### Recently implemented

* **OIDC / Keycloak authentication** (mirrors drsynth Python BFF)
    * browser login flow: `GET /auth/login` → Keycloak → `GET /auth/callback` (PKCE)
    * stores access token in HttpOnly cookie (`:auth :cookie-name`, default `access_token`)
    * `GET /api/me` to fetch current user (used by UI)
    * `POST /auth/logout` clears cookie
    * WS `/ws/audio` and `/ws/events` enforce auth **before upgrade** when `:auth {:required? true}`

* Kafka integration (further work)
    * end-to-end worker compatibility (see notes: worker must copy `bff_origin_uri`)
* Postgres persistence (sessions/transcripts)
* Refined transcript merge logic (windowing / overlap resolution) + UI rendering polish
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


### Use of replace_in_file

* For replace_in_file, ensure you use correct indentation. If search and replace fails after 2 attempts, fall back to write_to_file or ask the user for help.

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
