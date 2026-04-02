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
  - Remember however that “Task resumption” is a Cline/session thing, not a new job, and should be treated as “continue on the existing branch”
- For a task to be completed, there should be no security risks or concerns, always assess task's completion also from the security standpoint and highlight any risks.
- NEVER EXPOSE PORTS on local LAN for testing, always use localhost or 127.0.0.1
- Xamurai used to be called "drsynth", so do not be confused if you do see this name somewhere. That name however is legacy and you should always use xamurai when naming things related to xamurai stack- alternatively, you can also use nanosamurai if the name refers to the whole solution and not just the subset of voice-to-text services
- before you claim any task is complete, run full set of test/integration tests
- DO NOT EVER attempt to read or fetch the compiled js code (usually at resources/public/js/main.js ) - it is too big and your cotext will rot into oblivion
- DO NOT EVER run `clojure -Spath` or similar command to list entire classpath, you would burn too many tokens and rot your context without any significant benefit
- **IMPORTANT:** If you encounter unbalanced parentheses, brackets, or braces in any `.clj`, `.cljs`, `.cljc`, `.cljd`, or EDN-like file: Do NOT try to manually repair them by repeated editing. Run `clj-paren-repair <path>` on the affected file. After repair, re-read the file and continue.
- DO NOT create huge namespaces. If a namespace has 1000 LoC, you are doing something wrong and are likely to run into issues. Break dow the namespace into smaller logical namespaces instead.

# Clojure Parenthesis Repair
The command `clj-paren-repair` is currently installed on your path.

**IMPORTANT:** You SHOULD NOT try to manually repair parenthesis errors. If you encounter a file with unbalanced parentheses or delimiters, run the `clj-paren-repair` tool on that file instead of attempting to fix the delimiters yourself. IF the tool doesn't work report to the user that they need to fix the delimiter error.

### `clj-paren-repair` Tool Behavior

The `clj-paren-repair` tool exhibits the following behavior:

*   **Delimiter Repair:** It successfully identifies and fixes common delimiter errors, such as unbalanced parentheses.
*   **Code Formatting (`cljfmt`):** The tool automatically formats files using `cljfmt` whenever it processes them, regardless of whether a delimiter error was fixed or not.

# About Nanosamur.ai
Nanosamurai solution currently consists of
- **SamuraiBFF** (this repo),
- **xamurai** (https://github.com/mikub/xamurai ; locally usually at `%USERPROFILE%\PycharmProjects\xamurai`) services connected via Kafka/gRPC
    - xamurai is a monorepo that contains multiple Speach-to-text and speaker diarization services for Nanosamur.ai solution - they provide both real-time transcription as well as near-real-time refinement.
- **samuraipersistor** (https://github.com/mikub/samuraipersistor ; locally usually at `%USERPROFILE%\IdeaProjects\samuraipersistor` ) for persistence.
    - Database persistence is handled by a separate service, the samuraipersistor; consumes from all relevant Kafka topics and stores the data in Postgres
- **nanodeploy** - Helm charts for local k8s  and aws EKS deployments and also local docker compose setup (https://github.com/mikub/nanodeploy ; locally usually at `%USERPROFILE%\PycharmProjects\nanodeploy`)
- **nanplatform** - Pulumi infra setup in AWS (https://github.com/mikub/nanplatform ; locally usually at `%USERPROFILE%\PycharmProjects\nanplatform`)
- **nanosamurai-sdk** - A python sdk client for nanosamur.ai (uses client credentials for m2m access) (https://github.com/mikub/nanosamurai-sdk ; locally usually at `%USERPROFILE%\PycharmProjects\nanosamurai-sdk`)



# AGENTS.MD — samuraibff (Clojure) Architecture + House Rules

## Documentation ownership (avoid two sources of truth)

This file is **not** a changelog.

* **README.MD** is the canonical source of truth for:
  * current product scope / features (endpoints, auth, OpenAPI, refined transcripts, etc.)
  * operational behavior and configuration notes
  * “how to run” instructions

* **AGENTS.MD** is the canonical source of truth for:
  * agent/dev workflow rules (safety, branching/commits, testing discipline)
  * stable architectural constraints and conventions that shouldn’t drift every PR

If you add/modify user-facing behavior, update **README.MD**.

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
* Receives refined transcript data via **Kafka consumer** and/or **BFF-to-BFF internal callback** (`POST /internal/refined`).
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
4. Kafka consumer: refined transcript events (`transcripts.refined`) → BFF (RefinedEvent protobuf)
5. HTTP internal callback: **BFF → BFF** (`POST /internal/refined`, protobuf)

### Outbound interfaces

1. Kafka producer: BFF produces `audio.raw` (AudioChunk protobuf)
2. gRPC: BFF streams audio to rtservice (RealtimeASR.Stream)
3. Postgres: tenant-scoped persistence (sessions, recordings metadata, api_credentials, speakers)
4. S3: enrollment artifacts (speaker manifests + uploaded WAV samples)

---

## 4) Proposed repo / namespace structure

This is a **high-level guide** to keep boundaries clear. For the authoritative,
current layout, consult the actual `src/` tree (namespaces may evolve).

```
src/
  clj/samuraibff/
    core.clj
    system.clj
    config.clj
    http/router.clj
    http/ui.clj
    http/auth.clj
    http/recordings.clj
    http/api_credentials.clj
    http/speakers.clj
    http/internal.clj
    http/server.clj
    ws/audio.clj
    ws/events.clj
    ws/registry.clj
    ws/auth.clj
    ws/tenant.clj
    grpc/client.clj
    kafka/producer.clj
    kafka/refined_consumer.clj
    db/core.clj
    db/sessions.clj
    db/recordings.clj
    db/api_credentials.clj
    db/speakers.clj
    s3/enrollment.clj
    keycloak/admin.clj
    otel/kafka.clj
  cljs/samuraibff/ui/
    core.cljs
    components.cljs
    store.cljs
    ws.cljs
    audio.cljs
    ...
```
