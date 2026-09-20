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

Realtime tuning:

* `realtime_settings=<URL-encoded JSON>` – values keyed by configured track ID,
  e.g. `{"faster-whisper":{"window_sec":5,"overlap_sec":0.5,"partial_enable":false},"nemotron":{"endpointing_silence_ms":800}}`.
* Definitions come from each service's `GetCapabilities.session_settings_json` and
  appear in `/api/me.realtime_track_capabilities[].session_settings`. Each key has
  `display_name`, `type` (`boolean`, `integer`, `decimal`), `default`, and numeric
  `min`/`max`. The UI uses steps of 1 or 0.01 and submits resolved defaults.
* Admission freezes the map in `sessions.stream_controls.realtime_settings` and
  `sessions.meta.stream_controls`. Reconnects reuse that snapshot. Fanout sends
  only the selected service's submap in the `x-rt-settings` JSON gRPC header.
* Unknown settings are ignored by services. Omitted keys use deployment defaults.
  This spike relies on UI limits and existing engine limits; comprehensive SDK
  input validation is deferred. The former flat `rt_*` query/header path and its
  numeric-header formatting helpers are removed.

Semantics:

* If `realtime=false`, the BFF does not start the gRPC realtime stream.
* An explicit `realtime_tracks` value containing an empty, duplicate, or
  unconfigured ID is rejected before WebSocket upgrade. Clients cannot provide
  service addresses or arbitrary model identifiers.
* If `refined=false` and `final=false`, the BFF does not publish audio to Kafka.
* Normal `/ws/audio` closure finishes that session's audio input. The BFF drains
  accepted frames and half-closes every active realtime gRPC request while
  keeping `/ws/events` active so terminal events can be delivered. Clients
  should keep `/ws/events` connected until each selected track emits its
  `status=stopped` event, and should create a new session before starting
  another audio stream.
* A normal close (code 1000) sends `x-audio-end=true` on an empty Kafka
  `AudioChunk` after queued audio, with the same session key and stream-control
  headers. It is never sent to realtime gRPC. Abnormal closes use the recorder's
  idle fallback; the finish REST endpoint is not an audio-completion signal.

Example (tune realtime only):

`/ws/audio?session_id=<uuid>&lang=en&realtime_settings=%7B%22nemotron%22%3A%7B%22endpointing_silence_ms%22%3A800%7D%7D`

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
