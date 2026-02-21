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

### 3) Refined transcripts handling (UI semantics)
The UI receives both realtime and refined transcript events over `/ws/events`:

- realtime ASR: `{"type":"asr", "start_s": <double>, "end_s": <double>, "final": <bool>, ...}`
- refined (WhisperX): `{"type":"refined", "start_s": <double>, "end_s": <double>, ...}`

The **Live Recording** transcript is rendered as a Slack-like message thread (avatar/name/timestamp + bubble).

#### Replacement heuristic
When a refined segment arrives with time window `[r0, r1]`, the UI removes only **ASR** messages whose time window is fully contained:

- `r0 <= asr.start_s` and `asr.end_s <= r1` (inclusive)

Then it inserts the refined message (marked with `★ refined`) and re-sorts by time.

#### Timebases and debugging
In practice, ASR and refined timestamps can come from different sources.

- ASR events are **rebased in the UI** to make the timeline start at `00:00.xx` when you hit **Start**.
- Refined events are currently **NOT rebased** (they are displayed using the raw `start_s/end_s`).

The UI shows `start → end` in the message header to make it easier to debug window alignment.

If you see refined segments with `00:00.00 → 00:00.00`, check backend logs: protobuf3 defaults missing doubles to `0.0`, which usually means the worker didn’t populate `start_s/end_s`.

## Auth (OIDC / Keycloak)

OIDC login is implemented similarly to the original Python BFF (drsynth).

### Endpoints

Browser (Authorization Code + PKCE):
- `GET /auth/login?next=/live` – redirect to Keycloak login; stores PKCE cookies
- `GET /auth/callback?code=...&state=...` – exchanges `code` for tokens; stores access token in an **HttpOnly cookie**
- `POST /auth/logout` – clears the access token cookie

API helpers:
- `GET /api/me` – returns current user info (or unauthenticated)

### Tokens / transport

### Tenant isolation (current model)

At the moment, isolation is enforced **per-tenant** (not per-user):

- `POST /api/sessions` creates a new `session_id` server-side and binds it to the
  authenticated tenant (`tenant_id` claim).
- `/ws/events` and `/ws/audio` will only accept a `session_id` if it belongs to
  the same tenant as the currently authenticated user.
- When auth is required, **WS endpoints will not create sessions**; unknown
  session ids are rejected.

This prevents a tenant from subscribing to another tenant’s session, even if a
`session_id` leaks.

The backend accepts access tokens from:
1) `Authorization: Bearer <token>` header
2) `?token=<token>` query param (useful for WS/dev)
3) HttpOnly cookie (default name: `access_token`)

WebSockets `/ws/events` and `/ws/audio` enforce auth **before upgrade** when `:auth {:required? true}`.

### Configuration

See `resources/system.edn`:

- `:auth`
  - `:required?` (boolean)
  - `:issuer` (Keycloak realm issuer URL)
- `:audience` (JWT audience / usually the Keycloak client id)
    - Note: Some Keycloak setups emit access tokens with `aud=["account"]` and put the real client id into `azp`.
      We accept either `aud` containing the expected value OR `azp == expected`.
  - `:client-id` (OAuth client id for `/auth/login`; defaults to `:audience`)
  - `:cookie-name` (default: `access_token`)
- `:bff :origin-uri`
  - Used to build the OIDC `redirect_uri` (`<origin-uri>/auth/callback`).
  - Keycloak matches this **exactly** against client "Valid Redirect URIs".

### How to debug the redirect_uri (curl)

You can see the exact Keycloak redirect URL (and therefore the computed `redirect_uri`) without using the browser:

```bash
curl -s -D - -o NUL --max-redirs 0 "http://localhost:8000/auth/login?next=%2Flive"
```

Look at the `Location:` header. It should contain:

- `redirect_uri=http%3A%2F%2Flocalhost%3A8000%2Fauth%2Fcallback` (or `127.0.0.1` depending on your config)

If Keycloak shows `Invalid parameter: redirect_uri`, fix either:
- `resources/system.edn` `:bff :origin-uri`, or
- Keycloak client settings → **Valid Redirect URIs** (e.g. `http://localhost:8000/*`).

### Note on automated auth testing

Right now our automated test suite covers auth mechanics (token extraction and WS rejection when auth is required),
but it does **not** run a real Keycloak.

A good next step is to add a dockerized Keycloak to integration tests (e.g. via **testcontainers-clj**) so we can:
- create a realm + client dynamically
- fetch a real token from Keycloak
- validate the full `/auth/login` → `/auth/callback` flow end-to-end

(We haven't implemented that yet.)

## Enrolled speakers (multi-tenant)

The BFF now supports **enrolled speakers** for diarization, stored per tenant
in S3 and persisted in Postgres.

### API endpoints

- `GET /api/speakers` – list speakers for the authenticated tenant
- `POST /api/speakers` – multipart upload with fields:
  - `label` (string)
  - `sample` (WAV file)
- `DELETE /api/speakers/:speaker_id` – delete speaker (S3 + DB)

### S3 layout

```
s3://<bucket>/<enrollment-prefix>/<tenant_id>/speakers/<speaker_id>/
  speaker.json
  samples/<sample_id>.wav
```

The `speaker.json` is a per-speaker manifest containing the label and sample URL.

### Configuration

Add S3 config in `resources/system.edn` (or env overrides):

```edn
:s3 {:bucket "drsynth-enrollment"
     :enrollment-prefix "enrollment"
     :region ""
     :endpoint ""          ;; localstack/minio
     :access-key ""
     :secret-key ""
     :force-path-style? true}
```

Environment variables:

- `SAMURAIBFF_S3_BUCKET`
- `SAMURAIBFF_S3_ENROLLMENT_PREFIX`
- `SAMURAIBFF_S3_REGION`
- `SAMURAIBFF_S3_ENDPOINT`
- `SAMURAIBFF_S3_ACCESS_KEY`
- `SAMURAIBFF_S3_SECRET_KEY`
- `SAMURAIBFF_S3_FORCE_PATH_STYLE`

### Tests (LocalStack)

Integration tests use LocalStack S3 via Testcontainers:

```
clojure -X:test :nses '[samuraibff.http.speakers-integration-test]'
```

## Postgres persistence (sessions)

Session persistence is now implemented:

- Integrant component: `:samuraibff/db` (HikariCP pool)
- `POST /api/sessions` generates a **UUIDv7** session id and inserts a row into `sessions`.
- Keycloak user mapping: `app_users.external_id` should contain Keycloak `sub`.

### Unauthenticated / dev fallback

If `:auth {:required? false}` we still need to satisfy the DB schema constraint `sessions.tenant_id NOT NULL`.

The server will use a fallback tenant id:
- `00000000-0000-0000-0000-000000000000`

Optionally, if you want `sessions.user_id` to resolve in unauthenticated mode, add a guest user:
- `app_users.external_id = "guest"` within that tenant.

### Config

See `resources/system.edn`:

```edn
:db {:jdbc-url "jdbc:postgresql://localhost:5432/drsynth"
     :username "drsynth"
     :password "drsynth"
     :maximum-pool-size 10}
```

### Tests

We have an integration test that boots Postgres via Testcontainers and asserts the row is inserted:
- `test/samuraibff/http/ui_test.clj` (`create-session-handler-persisted-integration-test`)

> Note: transcripts/recordings persistence is still not implemented.

## Running the server

## Docker (Kubernetes-oriented)

This service is intended to run in Kubernetes.

The repo includes a multi-stage `Dockerfile` that produces a **self-contained**
image:
- builds the CLJS UI bundle (`shadow-cljs release`) into `resources/public/js`
- generates protobuf Java stubs (`buf generate`) and compiles them
- builds an **uberjar** (`target/samuraibff.jar`) and runs it via `java -jar`

### Build the image

```bash
docker build -t samuraibff:local .
```

### Run the container (example)

At minimum you must provide configuration for external dependencies, otherwise
the process will still start but readiness (`/ready`) will be degraded.

```bash
docker run --rm -p 8000:8000 \
  -e SAMURAIBFF_HTTP_HOST=0.0.0.0 \
  -e SAMURAIBFF_HTTP_PORT=8000 \
  -e SAMURAIBFF_DB_JDBC_URL=jdbc:postgresql://postgres:5432/drsynth \
  -e SAMURAIBFF_DB_USERNAME=drsynth \
  -e SAMURAIBFF_DB_PASSWORD=drsynth \
  -e SAMURAIBFF_KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e SAMURAIBFF_GRPC_RTSERVICE_ADDR=rtservice:50052 \
  -e SAMURAIBFF_ORIGIN_URI=https://bff.example.com \
  samuraibff:local
```

### Health probes

- `GET /health` – liveness (process is up)
- `GET /ready` – readiness (DB/Kafka/gRPC reachability)

### Configuration in Kubernetes

You can configure the service either:
1) via **environment variables** (`SAMURAIBFF_*`), or
2) by mounting an EDN config file and pointing the process to it:

```bash
SAMURAIBFF_CONFIG_PATH=/etc/samuraibff/system.edn
```

`SAMURAIBFF_CONFIG_PATH` is useful when you want to keep the Integrant config as
a single ConfigMap/Secret. Environment variables are then applied as an overlay
(only variables that are set override the EDN).

### Environment variables

The container reads configuration from `resources/system.edn` (or
`SAMURAIBFF_CONFIG_PATH`) and then applies overrides from these env vars:

#### Core
- `SAMURAIBFF_ENV` = `dev|prod|test` (keyword)

#### HTTP / WS
- `SAMURAIBFF_HTTP_HOST` (default: from EDN; recommended `0.0.0.0` in k8s)
- `SAMURAIBFF_HTTP_PORT`
- `SAMURAIBFF_WS_HOST`
- `SAMURAIBFF_WS_PORT`

#### Auth / OIDC (defaults to secure)
- `SAMURAIBFF_AUTH_REQUIRED` (`true|false`) – **defaults to true** via EDN
- `SAMURAIBFF_AUTH_ISSUER`
- `SAMURAIBFF_AUTH_AUDIENCE`
- `SAMURAIBFF_AUTH_CLIENT_ID`
- `SAMURAIBFF_AUTH_COOKIE_NAME`
- `SAMURAIBFF_AUTH_TENANT_CLAIM`

#### Database
- `SAMURAIBFF_DB_JDBC_URL`
- `SAMURAIBFF_DB_USERNAME`
- `SAMURAIBFF_DB_PASSWORD`
- `SAMURAIBFF_DB_MAX_POOL_SIZE`

#### Kafka
- `SAMURAIBFF_KAFKA_BOOTSTRAP_SERVERS`
- `SAMURAIBFF_KAFKA_CLIENT_ID`
- `SAMURAIBFF_KAFKA_ACKS`
- `SAMURAIBFF_KAFKA_COMPRESSION_TYPE`
- `SAMURAIBFF_KAFKA_CONSUMER_GROUP_ID`
- `SAMURAIBFF_KAFKA_TOPIC_AUDIO_RAW`
- `SAMURAIBFF_KAFKA_TOPIC_REFINED`

#### gRPC
- `SAMURAIBFF_GRPC_RTSERVICE_ADDR`

#### BFF instance identity (important for multi-replica refined routing)
- `SAMURAIBFF_ORIGIN_URI` – the externally reachable base URL of this BFF
  (also used to compute the OIDC `redirect_uri`).
- `SAMURAIBFF_CALLBACK_PATH` (default `/internal/refined`)

> Kubernetes note: for multiple replicas, `SAMURAIBFF_ORIGIN_URI` must be a
> routable address other instances can reach (typically a stable service DNS
> name, or an internal ingress).

## Health & readiness (HA behavior)

The app exposes two probe-style endpoints:

- `GET /health` – **liveness**. Returns **200** when the process is up.
- `GET /ready` – **readiness**. Returns **200** only when critical dependencies are reachable.

Current readiness checks:

- Postgres (simple `select 1`)
- Kafka (TCP reachability to at least one `:kafka :bootstrap-servers` host:port)
- rtservice (TCP reachability to `:grpc :rtservice-addr`)

If any dependency is down, `/ready` returns **503** and includes per-dependency flags:

```json
{
  "status": "degraded",
  "db": {"up?": false},
  "kafka": {"up?": false},
  "grpc": {"up?": false}
}
```

Startup is **graceful** under dependency outages:

- If Postgres is down at boot, the process still starts and serves HTTP/WS.
- If Kafka is down at boot, the process still starts; the Kafka producer is disabled and
  the refined consumer retries in the background.

This is intended for Kubernetes / load balancers so that dependency outages affect
**readiness** (traffic routing) rather than **liveness** (restart loops).

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
  - `GET /speakers`
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
