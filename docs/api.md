# API documentation (OpenAPI + REST surface)

This document is the **entry point** for SamuraiBFF’s customer-facing HTTP API.

## OpenAPI / Swagger

The service publishes a customer-facing OpenAPI spec and Swagger UI:

* OpenAPI JSON: `GET /openapi.json`
* Swagger UI: `GET /docs`

The OpenAPI spec intentionally includes only:

* `/auth/*` (browser login flow)
* `/api/*` (tenant-scoped REST API)

It intentionally excludes:

* SPA routes (e.g. `/`, `/recordings`, `/live`, `/speakers`)
* internal callbacks (e.g. `/internal/*`)
* WebSockets (`/ws/*`)
* operational probes (`/health`, `/ready`)

## High-level REST surface

This is a navigational overview. For detailed semantics, see the linked docs.

### Sessions

* `POST /api/sessions` – create a session (optionally with title and overrides)
* `POST /api/sessions/{session_id}/finish` – finish a session (persist `ended_at`)
* `PATCH /api/sessions/{session_id}` – update session title (when enabled)

See also:

* `docs/ws-contract.md` (how sessions relate to WS connection semantics)
* `docs/features-webhooks-workflows.md` (session override concepts)

### Recordings + transcripts

* `GET /api/recordings` – list sessions (tenant-scoped)
* `GET /api/recordings/{session_id}` – recording detail (includes stored transcripts)
* `GET /api/recordings/{session_id}/audio` – audio stream (supports HTTP Range)

See:

* `docs/features-recordings-playback-karaoke.md`

### Transcripts (streamed)

Transcript events are streamed over WebSocket, not REST.

See:

* `docs/ws-contract.md`
* `docs/features-transcripts.md`

### Speakers (enrollment)

* `GET /api/speakers`
* `POST /api/speakers` (multipart)
* `DELETE /api/speakers/{speaker_id}`
* `POST /api/speaker-enrollment/from-recording`

See:

* `docs/storage.md` (S3 layout/config)

### API credentials (M2M)

* `GET /api/api-credentials`
* `POST /api/api-credentials`
* `POST /api/api-credentials/{id}/rotate`
* `DELETE /api/api-credentials/{id}`

See:

* `docs/security-auth.md`

### Webhooks + workflows

See:

* `docs/features-webhooks-workflows.md`
