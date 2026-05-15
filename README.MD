# samuraibff

Clojure BFF (backend-for-frontend) for the **nanosamur.ai** solution.

This repo contains:

* **Backend** (Clojure): HTTP/WS API + orchestration
* **UI** (ClojureScript): browser UI
* **Electron app (Windows-first)**: wraps the UI and can capture mic + (best-effort)
  system output audio

## Architecture (high level)

```mermaid
flowchart LR
    subgraph Client
        Browser["Browser UI\n(ClojureScript)"]
        Electron["Electron app\n(Windows-first)"]
    end

    subgraph SamuraiBFF["SamuraiBFF (this repo)"]
        HTTP[HTTP /api + /auth]
        WSAudio[ws/audio]
        WSEvents[ws/events]
    end

    subgraph Xamurai["xamurai (Python services)"]
        RTService["rtservice\n(realtime ASR)"]
        WhisperXWorker["whisperx_worker\n(slice refinement)"]
        RecorderWorker["recorder_worker\n(session WAV)"]
        FinalizerWorker["finalizer_worker\n(final transcript)"]
    end

    Browser -->|HTTP /api + /auth| HTTP
    Browser -->|"WS audio\nWebSocket /ws/audio\nPCM16LE mono 16kHz"| WSAudio
    Browser ---|"WS events\nWebSocket /ws/events\nJSON events"| WSEvents

    Electron -->|HTTP /api + /auth| HTTP
    Electron -->|"WS audio\nWebSocket /ws/audio\nPCM16LE mono 16kHz"| WSAudio
    Electron ---|"WS events\nWebSocket /ws/events\nJSON events"| WSEvents

    SamuraiBFF -->|gRPC bidirectional stream| RTService

    subgraph Kafka["Kafka"]
        KafkaBroker[(Kafka broker)]
    end

    SamuraiBFF -->|"produce protobuf AudioChunk\ntopic: audio.raw"| KafkaBroker
    SamuraiBFF -->|"produce compacted JSON\ntopic: sessions.meta"| KafkaBroker

    KafkaBroker -->|"consume protobuf RefinedEvent\ntopic: transcripts.refined"| SamuraiBFF
    KafkaBroker -->|"consume JSON workflow.result\ntopic: workflow.result"| SamuraiBFF

    KafkaBroker -->|"consume\ntopic: audio.raw"| WhisperXWorker
    WhisperXWorker -->|"produce protobuf RefinedEvent\ntopic: transcripts.refined"| KafkaBroker

    KafkaBroker -->|"consume\ntopic: audio.raw"| RecorderWorker
    RecorderWorker -->|"produce protobuf RecordingFinished\ntopic: recordings.finished"| KafkaBroker

    KafkaBroker -->|"consume\ntopic: recordings.finished"| FinalizerWorker
    FinalizerWorker -->|"produce protobuf SessionTranscript\ntopic: transcripts.final"| KafkaBroker

    KafkaBroker -->|"consume + persist\ntopic: transcripts.refined"| Persistor["samuraipersistor\n(Postgres writer)"]
    KafkaBroker -->|"consume + persist\ntopic: transcripts.final"| Persistor
    KafkaBroker -->|"consume + persist\ntopic: workflow.result"| Persistor
    KafkaBroker -->|"consume + persist\ntopic: webhook.delivery_outcome"| Persistor

    Persistor -->|persist| Postgres[(Postgres)]
    SamuraiBFF -->|query| Postgres

    KafkaBroker -->|"consume\ntopic: sessions.meta"| WebhookRouter[webhook-router]
    KafkaBroker -->|"consume\ntopics: transcripts.refined, transcripts.final, recordings.finished"| WebhookRouter

    WebhookRouter -->|"produce\ntopic: webhook.dispatch"| KafkaBroker
    WebhookRouter -->|"produce\ntopic: workflow.dispatch"| KafkaBroker

    KafkaBroker -->|"consume\ntopic: webhook.dispatch"| WebhookDispatcher[webhook-dispatcher]
    WebhookDispatcher -->|"produce\ntopic: webhook.delivery_outcome"| KafkaBroker

    KafkaBroker -->|"consume\ntopic: workflow.dispatch"| WorkflowRunner[workflow-runner]
    WorkflowRunner -->|"produce\ntopic: workflow.result"| KafkaBroker
```

Read more:

* `docs/architecture.md`

## Related repositories / projects (nanosamur.ai)

Nanosamurai solution currently consists of:

* **SamuraiBFF** (this repo)
* **xamurai** https://github.com/mikub/xamurai (locally usually at `%USERPROFILE%\PycharmProjects\xamurai`)
  * xamurai is a monorepo with speech-to-text and speaker diarization services.
* **samuraipersistor** https://github.com/mikub/samuraipersistor (locally usually at `%USERPROFILE%\IdeaProjects\samuraipersistor`)
  * persistence service consuming Kafka topics and storing to Postgres.
* **nanodeploy** https://github.com/mikub/nanodeploy (locally usually at `%USERPROFILE%\PycharmProjects\nanodeploy`)
  * Helm charts / local k8s / docker compose setups.
* **nanplatform** https://github.com/mikub/nanplatform (locally usually at `%USERPROFILE%\PycharmProjects\nanplatform`)
  * Pulumi infra in AWS.
* **nanosamurai-sdk** https://github.com/mikub/nanosamurai-sdk (locally usually at `%USERPROFILE%\PycharmProjects\nanosamurai-sdk`)
* **webhook-router** (local: `%USERPROFILE%\IdeaProjects\webhook-router`)
* **webhook-dispatcher** (local: `%USERPROFILE%\IdeaProjects\webhook-dispatcher`)
* **workflow-runner** https://github.com/mikub/workflow-runner (local: `%USERPROFILE%\IdeaProjects\workflow-runner`)

## How to run (local dev)

This README documents **local developer workflows only**.
Docker/Kubernetes/CI/CD is owned by **nanodeploy**.

### Backend

```bash
clojure -M:run
```

Default local bind: `127.0.0.1:8000`.

### UI (browser)

Install npm deps once:

```bash
npm install
```

Run UI watcher:

```bash
clojure -M:cljs watch app
```

Open:

* http://127.0.0.1:8000/recordings
* http://127.0.0.1:8000/live

### Electron (Windows-first)

Electron connects to an **already-running** backend.

```bash
npm run electron:dev
```

Build Windows artifacts:

```bash
npm run electron:dist
```

Outputs: `dist/electron/`.

See:

* `docs/electron.md`
* `docs/electron-w1-system-audio-plan.md`

## Docs index

* Electron: `docs/electron.md`
* Architecture: `docs/architecture.md`
* Dev runbook (local): `docs/dev-runbook.md`
* Transcripts (realtime/refined/final): `docs/features-transcripts.md`
* Recordings + playback + karaoke: `docs/features-recordings-playback-karaoke.md`
* Webhooks + workflows: `docs/features-webhooks-workflows.md`
* Security + auth: `docs/security-auth.md`
* Ops + observability: `docs/ops-observability.md`
* Historical long-form README (archive): `docs/readme-archive.md`
