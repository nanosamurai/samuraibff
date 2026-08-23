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

`GET /api/me` exposes only the ordered stable track IDs to the UI. Each audio
session may select a non-empty subset with the `/ws/audio` `realtime_tracks`
control; omission selects every configured track for compatibility. The BFF
validates selections against the operator allowlist and never accepts endpoints
from a client. Selected clients retain operator order, and the first selected
track is marked `primary_track=true` for compatibility consumers.

The BFF publishes each accepted audio chunk to `audio.raw` once before offering
that same protobuf value to selected active realtime tracks. Adding a realtime model
therefore does not duplicate the Kafka refinement, recording, or finalization
pipeline. Peer services expose the same public `RealtimeASR` contract; the BFF
does not orchestrate model-specific inference calls.
