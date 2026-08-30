# Transcripts (realtime + refined + final)

This document explains how transcript data flows through the system and how the
UI renders it.

## Realtime (peer gRPC services)

* Operators configure one to four allowlisted `RealtimeASR` tracks.
* A session selects a non-empty subset, or all configured tracks when omitted.
* Audio is streamed from SamuraiBFF to each selected peer over gRPC.
* Each peer emits track-labelled realtime ASR events back to the BFF.
* The BFF forwards realtime events to the UI over `/ws/events` as JSON.

rtservice semantics:

* **PARTIAL** results are replaceable hypotheses.
* **FINAL** results commit a completed window.

The UI tracks partials per track and window (derived from `start_s`) so that a
provider can replace only its own relevant window. Selected tracks are rendered
in labelled side-by-side live panels; one provider's partial/final replacement
or coalescing cannot overwrite another provider's result.

The live page's right-side diagnostic log can be hidden or restored with the
chevron beside the transcript tabs. Hiding it gives the selected transcript
tracks the full available width without interrupting capture or event logging.

The compact **Highlight updates** checkbox beside that chevron is a local,
display-only comparison aid. Once enabled, newly arriving or revised partial
hypotheses briefly flash blue/purple, while committed final revisions briefly
flash the warm yellow used by karaoke highlighting. Enabling it does not alter
ASR controls, provider requests, transcript data, or the diagnostic log, and
existing messages do not flash merely because the checkbox was selected.

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
