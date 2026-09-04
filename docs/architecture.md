# Architecture (SamuraiBFF in nanosamur.ai)

This document expands on the high-level architecture diagram in the repository
README.

SamuraiBFF is the **canonical API + orchestration layer** for nanosamur.ai.
It provides:

* a browser/Electron UI (ClojureScript)
* HTTP REST endpoints (tenant-scoped)
* WebSockets for audio ingress + event egress
* bounded gRPC fan-out to one or more peer realtime ASR services
* Kafka integration for near-real-time refinement and workflow results

## Interfaces (high level)

### Inbound

* HTTP (Reitit/Ring): `/api/*`, `/auth/*`
* WebSocket audio ingest: `/ws/audio` (binary **PCM16LE mono**, 16kHz)
* WebSocket events: `/ws/events` (JSON)
* Kafka consumers:
  * `transcripts.refined` (protobuf `RefinedEvent`)
  * `workflow.result` (JSON)
* Internal callbacks (BFF→BFF cross-instance routing):
  * `POST /internal/refined` (`application/x-protobuf`)
  * `POST /internal/workflow-result` (`application/json`)

### Outbound

* gRPC clients: one allowlisted track per peer `RealtimeASR` service
* Kafka producer:
  * `audio.raw` (protobuf `AudioChunk`)
  * `sessions.meta` (compacted, JSON) – routing/config snapshot for downstream services
* Postgres:
  * session metadata + selected read models needed directly by the BFF/UI
* S3/object storage:
  * speaker enrollment artifacts
  * recordings (playback)

## Related repos/services

The rest of the nanosamur.ai system is spread across multiple repositories.
See README for the authoritative list.

## Realtime track orchestration

Operators register at most four tracks with
`SAMURAIBFF_GRPC_REALTIME_TRACKS=track-id=host:port,...`. The BFF discovers
each peer's fixed capabilities, then gives every track an independent bounded
queue and bidirectional stream. One slow, failed, or overloaded track is
canceled without stopping its peers.

Track addresses are normalized to `dns:///host:port`. Each track owns one
long-lived gRPC channel configured with the built-in `round_robin` policy, not
a channel or endpoint registry per pod. A Kubernetes headless Service or
Docker Compose DNS returns the ready replica addresses; gRPC maintains the
subchannels and selects one when each new stream RPC starts.

Opening a stream is an explicit pre-audio operation. The BFF sends
`x-session-id`, waits for `SESSION_ACCEPTED`, and only then returns the stream
to the audio forwarder. If a pod responds with the precise admission signal
`RESOURCE_EXHAUSTED: REPLICA_FULL`, the BFF starts a fresh RPC on the same
round-robin channel. This is safe because the rejected pod has not consumed
audio. The admitted RPC stays pinned to its selected pod until completion;
active streams are never moved. Other failures and any failure after admission
are not retried.

`SAMURAIBFF_GRPC_ADMISSION_TIMEOUT_MS` bounds one handshake (default `3000`)
and `SAMURAIBFF_GRPC_ADMISSION_MAX_ATTEMPTS` bounds full-replica retries
(default `8`). Set the latter at least as high as the largest expected replica
count. These bounds are per BFF process; no capacity or tenant state is shared
between BFF replicas.

`GET /api/me` exposes the ordered stable track IDs and a sanitized capability
view to the UI. The capability view includes mode, timestamp, speaker-label and
language support, aligned-and-diarized language codes, sample rate, duration
policy, and concurrency; it deliberately omits
peer endpoints, runtime versions, model revisions, and digests. Discovery is
best-effort, so an unavailable provider is reported as unavailable without
failing the entire endpoint. The live track picker renders these limits before
a session starts.

Each audio session may select a non-empty subset with the `/ws/audio`
`realtime_tracks` control; omission selects every configured track for
compatibility. The BFF validates selections against the operator allowlist and
never accepts endpoints from a client. Selected clients retain operator order,
and the first selected track is marked `primary_track=true` for compatibility
consumers.

The BFF publishes each accepted audio chunk to `audio.raw` once before offering
that same protobuf value to selected active realtime tracks. Adding a realtime model
therefore does not duplicate the Kafka refinement, recording, or finalization
pipeline. Peer services expose the same public `RealtimeASR` contract; the BFF
does not orchestrate model-specific inference calls.
