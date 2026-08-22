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

* `realtime=true|false` – whether to run rtservice realtime ASR (gRPC)
* `refined=true|false` – whether to publish audio to the refined pipeline (Kafka)
* `final=true|false` – whether to produce final transcript artifacts (pipeline)

Recording retention:

* `store_recording=true|false` – whether the recording should be retained for playback.
  * Only applies when `final=true`.
  * When `final=false`, the backend forces retention off.

Refinement tuning:

* `refinement_window_sec=<double>` – optional refinement window size.
  * Backend clamps it to **[10, 600]** seconds.

Realtime tuning (rtservice overrides):

* `rt_partial_enable=true|false` – whether rtservice should emit partial hypotheses.
* `rt_window_sec=<double>` (alias: `window_sec`)
* `rt_overlap_sec=<double>` (alias: `overlap_sec`)
* `rt_emit_every_sec=<double>` (alias: `emit_every_sec`)
  * Backend enforces a minimum of **1s**.

Semantics:

* If `realtime=false`, the BFF does not start the gRPC realtime stream.
* If `refined=false` and `final=false`, the BFF does not publish audio to Kafka.
* Normal `/ws/audio` closure finishes that session's audio input. The BFF drains
  accepted frames and half-closes the rtservice gRPC request while keeping
  `/ws/events` active so the terminal realtime event can be delivered. Clients
  should create a new session before starting another audio stream.

Example (tune realtime only):

`/ws/audio?session_id=<uuid>&lang=en&sample_rate=16000&rt_window_sec=5.0&rt_overlap_sec=0.5&rt_emit_every_sec=1.0`

## `/ws/events`

Event egress WebSocket.

### Typical event types

* `{"type":"asr", ...}` – realtime ASR events
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
