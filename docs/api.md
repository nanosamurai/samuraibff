# API documentation (OpenAPI + REST surface)

This document is the **entry point** for SamuraiBFF’s customer-facing HTTP API.

## OpenAPI / Swagger

The service publishes a customer-facing OpenAPI spec and Swagger UI:

* OpenAPI JSON: `GET /openapi.json`
* Swagger UI: `GET /docs`

The OpenAPI spec intentionally includes only:

* `/auth/*` (browser login flow)
* `/api/*` (tenant-scoped REST API)
* `/v1/audio/transcriptions` (OpenAI-compatible transcription)

It intentionally excludes:

* SPA routes (e.g. `/`, `/recordings`, `/live`, `/upload`, `/speakers`)
* internal callbacks (e.g. `/internal/*`)
* WebSockets (`/ws/*`)
* operational probes (`/health`, `/ready`)

## High-level REST surface

This is a navigational overview. For detailed semantics, see the linked docs.

### Sessions

* `POST /api/sessions` – create a session (optionally with title and overrides)
* `POST /api/sessions/upload` – create a session from an audio/video file
  (multipart); the final transcript follows asynchronously
* `POST /api/sessions/{session_id}/finish` – finish a session (persist `ended_at`)
* `PATCH /api/sessions/{session_id}` – update session title (when enabled)

See also:

* `docs/ws-contract.md` (how sessions relate to WS connection semantics)
* `docs/features-file-uploads.md` (file upload fields, limits, status)
* `docs/features-webhooks-workflows.md` (session override concepts)

### Recordings + transcripts

* `GET /api/recordings` – list sessions (tenant-scoped). Query: `limit`,
  `offset`, `show_drafts`, `scope` (`all` | `mine` | `shared`), `sort`
  (`title` | `created_at` | `started_at` | `duration` | `status`) and `order`
  (`asc` | `desc`); default sort `created_at` `desc`. Items include the session
  `duration_s`. See `docs/features-recordings-playback-karaoke.md`.
* `GET /api/recordings/{session_id}` – recording detail (includes stored transcripts)
* `GET /api/recordings/{session_id}/audio` – audio stream (supports HTTP Range)
* `GET /api/recordings/{session_id}/export?format=srt|vtt|txt|json|docx` – download one
  saved transcript track (see `docs/features-transcripts.md`)
* `POST /api/recordings/{session_id}/retranscribe` – create a new session from the
  saved audio of this session (needs file uploads enabled; see
  `docs/features-retranscription.md`)
* `DELETE /api/recordings/{session_id}` – delete a session (and cascaded recordings/transcripts)

  Notes:
  - **Active sessions cannot be deleted** (returns HTTP 409 with `{"ok":false,"message":"session-active"}`).

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

* `GET /api/api-credentials` (items add `kind`, `read_scope`, `audience`,
  `can_rotate`, `can_configure`)
* `POST /api/api-credentials` body `{name, kind?, read_scope?, audience?,
  manager_id?}`: `kind` is `personal` (default) or `organization` (Admin
  only); `read_scope` (`credential` | `owner`) applies to personal keys;
  `audience` (`private` | `tenant`) and `manager_id` to organization keys
* `PATCH /api/api-credentials/{id}` body `{manager_id?, audience?}`
  (organization keys, Admin only)
* `POST /api/api-credentials/{id}/rotate` (personal keys: owner only,
  `403 owner-only-rotation`)
* `DELETE /api/api-credentials/{id}` (response `upstream`: `disabled` | `pending`)

Admins manage all credentials, Builders their own. Session access summaries
add `via_api_key` (the key that recorded it) and `owner.kind = "api_key"`
for sessions owned by an organization key; `/api/me` adds
`access.principal.api_key` for requests made with a key. See:

* `docs/security-auth.md`

### Webhooks + workflows

Definitions are `personal` or `published`; roles decide who can list, edit,
publish and set defaults.

* `POST /api/recordings/{session_id}/workflows/{workflow_id}/runs` – run a
  workflow on a stored transcript of a finished session (`202` with
  `workflow_run_id`)

See:

* `docs/features-webhooks-workflows.md`

### OpenAI-compatible transcription

`POST /v1/audio/transcriptions` accepts the OpenAI audio transcription request
shape, so OpenAI clients can use nanosamurai as their base URL. Each request is
a file upload (`POST /api/sessions/upload`) that waits for its final transcript:
the same permission, ownership, size limit (`[:uploads :max-bytes]`) and media
checks apply, and the session stays visible in Sessions and through
`GET /api/recordings/{session_id}`.

Authentication: send a nanosamurai access token or API key token as
`Authorization: Bearer <token>`. While sign-in is not required (guest mode),
any value works and the request runs as the guest.

```python
from openai import OpenAI

client = OpenAI(base_url="https://<nanosamurai-host>/v1", api_key="<token>")
with open("meeting.mp3", "rb") as audio:
    srt = client.audio.transcriptions.create(model="whisper-1", file=audio, response_format="srt")
```

The same request with curl:

```bash
curl https://<nanosamurai-host>/v1/audio/transcriptions   -H "Authorization: Bearer <token>"   -F file=@meeting.mp3 -F model=whisper-1 -F response_format=srt
```

Fields (multipart/form-data):

| Field | Behavior |
| --- | --- |
| `file` | Required. One audio or video file. |
| `model` | Required. A configured final track ID (as listed in `/api/me`) or an alias. Default alias: `whisper-1` = the default final track. Other values return 400 `model_not_found`. |
| `language` | Optional language code such as `en` or `pt-BR`. Empty = detect. |
| `response_format` | `json` (default, `{"text": ...}`), `text`, `srt`, `vtt` or `verbose_json`. |
| `timestamp_granularities[]` | `segment` (default) and/or `word`. Applies to `verbose_json`: `segment` adds `segments` (`id`, `seek` = 0, `start`, `end`, `text`); `word` adds `words` (`word`, `start`, `end`) when the track stores word timings. |
| `prompt`, `temperature` | Accepted and ignored. No final track takes a prompt. |

Differences from the OpenAI API:

* `verbose_json.language` is the stored language code (for example `en`), not
  a language name. Segments have no token or probability fields.
* `srt` and `vtt` cues start with `Speaker: ` when the track labels speakers.
* Not supported: `/v1/audio/translations`, `stream=true` (400
  `unsupported_parameter`) and the realtime API.

Waiting and limits:

* The BFF polls the stored final transcript of the selected track. Waiting
  does not hold a request thread.
* After `[:openai :wait-timeout-s]` the response is 504
  `transcription_timeout`. `error.session_id` names the session; the
  transcription continues and the result appears in
  `GET /api/recordings/{session_id}`.
* When the recorder cannot convert the file, the session becomes `failed`
  and the response is 400 `unreadable_file` (`param: file`, with
  `error.session_id`) as soon as the next poll sees it.
* When `[:openai :max-concurrent-waits]` requests already wait, a new request
  gets 429 `too_many_pending_transcriptions` before the file is stored.
* A load balancer or proxy in front of the BFF must allow idle requests at
  least as long as the wait timeout.

Errors use the OpenAI shape
`{"error": {"message", "type", "param", "code"}}` with the matching HTTP
status. A missing or invalid token is 401 `invalid_api_key`. Upload errors
keep their upload code, for example 413 `file-too-large` or 415
`unsupported-media-type`.

Configuration:

| Key | Env var | Default |
| --- | --- | --- |
| `[:openai :wait-timeout-s]` | `SAMURAIBFF_OPENAI_WAIT_TIMEOUT_S` | `600` |
| `[:openai :max-concurrent-waits]` | `SAMURAIBFF_OPENAI_MAX_CONCURRENT_WAITS` | `16` (per BFF replica) |
| `[:openai :poll-interval-ms]` | none | `2000` |
| `[:openai :model-aliases]` | `SAMURAIBFF_OPENAI_MODEL_ALIASES` (JSON object, for example `{"whisper-1":"whisperx","fast":"parakeet"}`) | `{"whisper-1": <default final track>}` |

Setting `model-aliases` replaces the default alias. An alias to a track that
is not in `[:final-tracks]` is ignored.
