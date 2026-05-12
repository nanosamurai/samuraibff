# Transcripts (realtime + refined + final)

This document explains how transcript data flows through the system and how the
UI renders it.

## Realtime (gRPC rtservice)

* Audio is streamed from SamuraiBFF to **rtservice** over gRPC.
* rtservice emits realtime ASR events back to the BFF.
* The BFF forwards realtime events to the UI over `/ws/events` as JSON.

rtservice semantics:

* **PARTIAL** results are replaceable hypotheses.
* **FINAL** results commit a completed window.

The UI tracks partials per window (derived from `start_s`) so that PARTIAL
updates replace only the relevant window.

## Refined (Kafka)

* BFF publishes `AudioChunk` protobuf messages to Kafka topic `audio.raw`.
* Downstream refinement workers publish `RefinedEvent` protobuf messages to Kafka
  topic `transcripts.refined`.

### Cross-instance routing

Because Kafka consumer groups may deliver a refined event to a non-origin BFF
instance, non-origin instances forward the protobuf payload to the origin BFF
via:

* `POST /internal/refined` (`application/x-protobuf`)

The origin instance then pushes refined transcript events to the UI via
`/ws/events`.

### Fan-out semantics

A single protobuf `RefinedEvent` may contain multiple speaker turns in
`RefinedEvent.segments`. The BFF fans out a single `RefinedEvent` into **N** WS
events (one per segment) so the UI can keep its “one message == one segment”
model.

## Final transcripts

Final transcripts are stored/persisted and served via the Recordings REST API.
The UI renders them under the “Final transcript” tab.
