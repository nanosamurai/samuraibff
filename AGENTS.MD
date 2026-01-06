Aim of this clojure project (samuraibff) is to rewrite and further evolve 
drsynth's bff written in python (see C:\Users\miros\PycharmProjects\drsynth\bff\src\bff) 
for drsynth project ( C:\Users\miros\PycharmProjects\drsynth\ ) . 
Please read its readme.md (C:\Users\miros\PycharmProjects\drsynth\README.MD) to get idea what it does. 

I decided to isolate the bff and rewrite it in clojure. 
For the data model, please look at C:\Users\miros\PycharmProjects\drsynth\proto\stream.proto . 

# AGENTS.MD — samuraibff (Clojure) Architecture + House Rules

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

* Follow: [https://guide.clojure.style/](https://guide.clojure.style/)
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
* WebSocket: **http-kit** (preferred for WS reliability & simplicity)

### Auth (Keycloak)

* Prefer: [https://github.com/jgrodziski/keycloak-clojure](https://github.com/jgrodziski/keycloak-clojure)

    * Use it for JWKS discovery + token verification if it fits our needs.
* If keycloak-clojure isn’t sufficient for our specific constraints, use Java interop:

    * Either Keycloak adapters where appropriate, or generic JWT verification with JWKS caching using Java libs.

### Messaging & persistence

* Kafka producer in BFF (keep it).
* DB: Postgres (next.jdbc + hikariCP)
* gRPC client: Java gRPC stubs generated from `proto/stream.proto`

### Concurrency primitives

* `core.async` for:

    * per-session pipelines (audio ingestion, gRPC streaming, event fanout)
    * bounded queues/backpressure
    * controlled worker pools where a full-blown executor framework feels heavy

### Testing tooling

* Unit/integration tests: `clojure.test`
* Docker-based integration: [https://github.com/testcontainers/testcontainers-clj](https://github.com/testcontainers/testcontainers-clj)
* E2E UI tests: [https://github.com/clj-commons/etaoin](https://github.com/clj-commons/etaoin)

---

## 3) High-level architecture

### Inbound interfaces

1. **UI HTTP** (static + REST)
2. **WS /ws/audio**: browser → BFF (binary PCM16 LE)
3. **WS /ws/events** (or SSE): BFF → browser (JSON events: asr/refined/status)
4. **HTTP callback**: whisperx worker → BFF (refined transcript segments)

### Outbound interfaces

1. Kafka: BFF produces `audio.raw.*`
2. gRPC: BFF streams to rtservice (RealtimeASR.Stream)
3. Postgres: persist sessions, recordings metadata, transcripts, speakers

### Core ordering rule

BFF is the ordering authority for UI events:

* Assign monotonic per-session `seq` to outbound events.
* UI renders by `seq` (or uses it to reconcile if needed).
* Refined segments update/replace live windows deterministically.

---

## 4) Proposed repo / namespace structure

```
src/samuraibff/
  core.clj                 ; entrypoint, integrant init/halt, -main
  config.clj               ; env parsing, defaults, config schema

  ig/keys.clj              ; integrant key registration helpers (optional)
  log.clj                  ; logging helpers (structured fields, context)

  http/server.clj          ; ring+http-kit server component - the single http-kit runtime + routing wiring (start/stop, reitit handler, mount the WS endpoint)
  http/routes.clj          ; reitit router, middlewares, openapi
  http/handlers.clj        ; REST endpoints: sessions, health, callback

  ws/server.clj            ; http-kit server component: ws endpoints - pure WebSocket logic (on-open/on-close/on-message, session registry, broadcasting refined events, etc.) — no server start/stop in here
  ws/audio.clj             ; ws audio handler, binary ingest → core.async channel
  ws/events.clj            ; ws events handler, fanout to clients

  auth/keycloak.clj        ; token verification + principal extraction
  auth/middleware.clj      ; reitit/ring middleware for authz/authn
  auth/tenant.clj          ; tenant/user derivation rules

  session/registry.clj     ; in-memory session registry + lifecycle
  session/service.clj      ; start/stop session, persistence hooks

  rt/grpc.clj              ; gRPC client, stream lifecycle, mapping to events
  rt/pipeline.clj          ; core.async pipelines for per-session realtime streaming

  kafka/producer.clj       ; kafka producer component + publish helpers

  refined/callback.clj     ; HTTP callback validation, merge logic, publish events
  transcript/model.clj     ; pure merge/reconcile logic (live vs refined)
  transcript/store.clj     ; DB persistence for transcript snapshots/segments

  db/core.clj              ; datasource component + transaction helpers
  db/queries.clj           ; SQL strings/queries for sessions/transcripts/etc.

test/samuraibff/
  ... unit tests
  ... integration tests with testcontainers
  ... etaoin e2e tests
```

Guideline:

* `model.clj` contains **pure functions** (easy to unit test).
* `service.clj` orchestrates side effects and integrates components.

---

## 5) Integrant components (system map)

We manage long-lived resources via Integrant. Core keys:

* `:samuraibff/config`
* `:samuraibff/logging` (optional)
* `:samuraibff/db` (next.jdbc datasource + hikariCP + honeysql)
* `:samuraibff/kafka-producer`
* `:samuraibff/grpc-channel` (or grpc client factory)
* `:samuraibff/session-registry` (atom + housekeeping loop)
* `:samuraibff/http-server` (Jetty)
* `:samuraibff/ws-server` (http-kit)
* `:samuraibff/refined-handler` (depends on session-registry + db + ws/events)
* `:samuraibff/metrics` (optional)

Component lifecycle rules:

* `halt-key!` must close resources cleanly (DB pool, kafka flush/close, grpc channel shutdown, WS server stop).
* All components must be restartable in REPL without leftover threads.
* Any background loops must have explicit stop signals (core.async channel close or atom flag).

---

## 6) Data model + API codification (Malli)

### “Single source of truth”

* All request/response bodies, and WS events are described by Malli schemas.
* Reitit coercion enabled:

    * incoming requests validated/coerced
    * outgoing responses validated (at least in dev/test profiles)

### Schemas to define early

* `SessionStartRequest`, `SessionStartResponse`
* `WsAsrEvent`, `WsRefinedEvent`, `WsStatusEvent`, `WsErrorEvent`
* `RefinedCallbackRequest` (worker→BFF)
* Domain model schemas:

    * `Session`
    * `TranscriptSegment`
    * `TranscriptState` (optional: windowed state)

### Event shape guidance

Every event emitted to UI includes:

* `:type` (keyword / string)
* `:session_id`
* `:seq` (monotonic per session)
* `:ts_ms` (server timestamp)
  Plus payload fields (start/end/text/speaker/etc.)

---

## 7) Auth: Keycloak integration & tenant/user rules

### Verification

* Prefer keycloak-clojure for discovery + verification.
* JWT must be validated against:

    * issuer
    * audience (client id)
    * signature (JWKS)
    * expiry (`exp`)
* Cache JWKS with TTL and refresh on cache miss / unknown `kid`.

### Principal extraction

Create a normalized `principal` map:

* `:sub`
* `:preferred-username`
* `:email`
* `:roles` (set)
* `:tenant-id` (required for BFF endpoints that create sessions)
* `:raw-claims` (optional, for debugging / auditing)

### Tenant injection (Keycloak)

We want tenant to exist as a claim (e.g. `tenant_id`). Preferred:

* configure Keycloak Protocol Mapper to include tenant id in token
* if multi-tenant membership exists, store allowed tenant ids as claim list, e.g. `tenant_ids: [...]`

### WS auth requirement

* Authentication must happen **before starting pipelines**.
* If unauthorized: close connection immediately with appropriate code.
* For internal callbacks: use client credentials token (preferred), or HMAC signature as stepping stone.

---

## 8) Realtime pipeline design (core.async)

Per session we maintain a small set of channels:

* `audio-in` (binary frames from WS)
* `audio-chunks` (processed frames for Kafka + gRPC)
* `events-out` (maps for UI)
* `control` (stop signal, disconnect signals)

Suggested flow:

1. WS audio handler receives binary, puts into `audio-in`
2. A transducer pipeline:

    * downsample/convert if needed
    * chunk/sequence annotate (`seq`, timestamps)
3. Fanout:

    * publish to Kafka
    * write to gRPC stream
4. gRPC responses become `WsAsrEvent` into `events-out`
5. refined callback merges transcript -> emits `WsRefinedEvent` into `events-out`
6. WS events handler drains `events-out` -> sends JSON to all subscribers

Backpressure:

* channels should be bounded; if overloaded:

    * drop policy for audio (or apply sampling)
    * never block the WS thread indefinitely
* log overload events with session context

---

## 9) Refined delivery (HTTP callback) — no Kafka dependency

WhisperX worker calls:

* `POST /api/internal/refined`
  Payload includes:
* `session_id`
* `segments` or a single segment
* timing fields (`start_s`, `end_s`)
* `text`, `speaker`, `lang`

BFF behavior:

* validate request (Malli)
* apply merge logic (pure function in `transcript/model.clj`)
* persist refined state (DB)
* emit UI events with correct `seq` ordering

Security:

* Preferred: worker uses Keycloak client credentials token
* Alternative: signed HMAC header (short-term)

---

## 10) Persistence responsibilities

* BFF creates session row on session start.
* WhisperX/finalizer update transcript/recording info; BFF stores canonical metadata.
* Real-time partials do **not** have to be persisted initially (MVP); refined/final should be.

(We can later add an option to store “live window” snapshots if needed.)

---

## 11) Testing strategy (must-have)

### Unit tests (fast)

* Pure transcript merge ordering (live vs refined)
* Schema validations
* tenant/user principal extraction logic
* event sequencing and session state transitions

### Integration tests (Docker via testcontainers-clj)

Run against real containers:

* Postgres with migrations applied
* Kafka broker
* (Optional) rtservice container if feasible; otherwise mock gRPC with an in-process server

Coverage targets:

* Session start persists correct DB rows
* WS audio produces Kafka messages and triggers gRPC stream calls
* Refined callback updates transcript and pushes events to WS events subscribers

### End-to-end tests (etaoin)

* Bring up stack (compose or testcontainers)
* Open UI, login flow (or test mode bypass)
* Validate websocket connects and transcript updates appear
* Validate refined callback causes UI replacement/updates

Notes:

* We should support a **test auth mode** (e.g. `AUTH_REQUIRED=false`) for local/E2E unless we explicitly want to test Keycloak in CI.
* But we should still have at least one CI job that validates OIDC against a Keycloak container or a mocked JWKS endpoint.

---

## 12) Operational practices

* All endpoints and events must log with:

    * `session-id`
    * `tenant-id`
    * `user-id` (if present)
    * `event-seq`
* Provide `/health` and `/ready` endpoints
* Provide a `/metrics` endpoint if/when we add metrics

---

## 13) Logging

Use org.corfield.logging4j2 for logging. To new namespaces, import it as follows: [org.corfield.logging4j2 :as log]

## 14) “Golden decisions” recap

* Keep Kafka publishing in BFF.
* Use Reitit + Malli for typed, explicit API.
* Use Integrant for lifecycle.
* Use core.async for pipelines.
* Use http-kit for WebSockets.
* Receive refined transcripts via HTTP callback (not Kafka).
* Keycloak integration: prefer keycloak-clojure; otherwise Java interop for JWT+JWKS.
* Tests: unit + testcontainers integration + etaoin E2E.
* Use org.corfield.logging4j2 for logging

---


