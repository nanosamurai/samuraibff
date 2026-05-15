# Architecture (SamuraiBFF in nanosamur.ai)

This document expands on the high-level architecture diagram in the repository
README.

SamuraiBFF is the **canonical API + orchestration layer** for nanosamur.ai.
It provides:

* a browser/Electron UI (ClojureScript)
* HTTP REST endpoints (tenant-scoped)
* WebSockets for audio ingress + event egress
* gRPC streaming to rtservice
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

* gRPC client: realtime audio streaming to **rtservice**
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
