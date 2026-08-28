# WebSocket contracts

This document defines the contracts for:

* `/ws/audio` – binary audio ingestion (browser/Electron → BFF)
* `/ws/events` – JSON event egress (BFF → browser/Electron)

The WebSocket surface is intentionally **not** part of OpenAPI.

## `/ws/audio`

Audio ingestion WebSocket.

### Payload

* Binary frames: **PCM16LE**, mono.

### Query parameters

Required:

* `session_id` – session UUID

Optional:

* `lang` – ISO-639-1 language code (e.g. `en`, `cs`, `de`, ...). Empty string means auto-detect.
* `sample_rate` – integer; defaults to `16000`

Output selection (all default to `true` when omitted):

* `realtime=true|false` – whether to run configured realtime ASR tracks (gRPC)
* `realtime_tracks=<track-id>,...` – optional non-empty subset of the one to
  four operator-configured track IDs; omission selects every configured track
* `refined=true|false` – whether to publish audio to the refined pipeline (Kafka)
* `final=true|false` – whether to produce final transcript artifacts (pipeline)

Recording retention:

* `store_recording=true|false` – whether the recording should be retained for playback.
  * Only applies when `final=true`.
  * When `final=false`, the backend forces retention off.

Refinement tuning:

* `refinement_window_sec=<double>` – optional refinement window size.
  * Backend clamps it to **[10, 600]** seconds.

Realtime tuning (forwarded service overrides):

* `rt_partial_enable=true|false` – whether compatible services should emit partial hypotheses.
* `rt_window_sec=<double>` (alias: `window_sec`)
* `rt_overlap_sec=<double>` (alias: `overlap_sec`)
* `rt_emit_every_sec=<double>` (alias: `emit_every_sec`)
  * Backend enforces a minimum of **1s**.

Semantics:

* If `realtime=false`, the BFF does not start the gRPC realtime stream.
* An explicit `realtime_tracks` value containing an empty, duplicate, or
  unconfigured ID is rejected before WebSocket upgrade. Clients cannot provide
  service addresses or arbitrary model identifiers.
* If `refined=false` and `final=false`, the BFF does not publish audio to Kafka.
* Normal `/ws/audio` closure finishes that session's audio input. The BFF drains
  accepted frames and half-closes every active realtime gRPC request while
  keeping `/ws/events` active so terminal events can be delivered. Clients
  should create a new session before starting another audio stream.

Example (tune realtime only):

`/ws/audio?session_id=<uuid>&lang=en&sample_rate=16000&rt_window_sec=5.0&rt_overlap_sec=0.5&rt_emit_every_sec=1.0`

Example (run only the configured Qwen track):

`/ws/audio?session_id=<uuid>&lang=en&sample_rate=16000&realtime_tracks=qwen`

## `/ws/events`

Event egress WebSocket.

### Typical event types

* `{"type":"asr", ...}` – realtime ASR events. Additive fields include
  `track`, `provider_profile_id`, and `primary_track`.
* `{"type":"error","track":"...",...}` – failure or overload isolated to
  one realtime track; other tracks may continue.
* `{"type":"refined", ...}` – refined transcript segments
* `{"type":"workflow_result", ...}` – workflow result updates
* `{"type":"status", ...}` – status/health style events

See:

* `docs/features-transcripts.md`
* `docs/features-webhooks-workflows.md`

## Auth / tenant isolation (WS)

When auth is required (`:auth {:required? true}`):

* WebSockets enforce auth **before upgrade**.
* A `session_id` is accepted only if it belongs to the authenticated tenant.
* WS endpoints must not create sessions implicitly; unknown session ids are rejected.

For full auth details, see `docs/security-auth.md`.
