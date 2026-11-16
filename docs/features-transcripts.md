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

The log panel groups two compact diagnostics: **Log ASR events** logs every
final and samples at most one partial per track/audio second, while **Highlight
updates** is a local, display-only comparison aid. A partial line is marked
`replace=current-utterance`: it is the provider's complete current hypothesis,
not a text delta. Sampling affects only the bounded diagnostic log; the
transcript reducer still consumes every provider event. Once highlighting is
enabled, only newly added or replaced words flash: partial hypotheses use
blue/cyan and committed finals use the warm yellow used by karaoke
highlighting. The UI derives these spans from successive full-text revisions,
including additions to coalesced final bubbles. Enabling it does not alter ASR
controls, provider requests, or transcript data, and existing messages do not
flash merely because it was selected.

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

## Export

`GET /api/recordings/{session_id}/export` downloads one saved transcript track
as a file. On the recording detail page, the download icon above the selected
Final or Refined tab opens a small panel with the format picker and a Download
button. Escape or a click outside closes it.

Query parameters:

* `format` (required): `srt`, `vtt`, `txt`, `json` or `docx`.
* `stage` (optional): `final` (default) or `refined`.
* `track_id` (optional): the track to export. Without it, the export uses the
  track of the earliest stored result for that stage.
* `version` (optional, final only): `corrected` (default) applies the human
  corrections (see [Corrections](#corrections)); `original` returns the
  machine output. The Original view on the detail page downloads `original`.

The rows are the same as on the detail page: the latest final result of the
track, or all refined windows of the track ordered by audio start.

| Format | Content |
| --- | --- |
| `srt` | Numbered cues, `HH:MM:SS,mmm` times, `Speaker: text`; `<` `>` become `‹` `›` because SRT has no escaping |
| `vtt` | `WEBVTT` header, `HH:MM:SS.mmm` times, `<v Speaker>Speaker: text` (voice tag plus visible name), escaped text |
| `txt` | One `[HH:MM:SS] Speaker: text` line per segment |
| `docx` | Word document: title, then one `[HH:MM:SS] Speaker: text` paragraph per segment |
| `json` | `{session_id, title, stage, track_id, lang, version, text, segments}`; `version` (final only) is `corrected` when corrections apply, else `original`; each segment has `start_s`, `end_s`, `text`, optional `speaker`, optional `speaker_id` (enrolled speaker set by a correction) and optional `words` (`start_s`, `end_s`, `text`) |

Cue text is kept on one line. Segments with blank text are left out. Text-only
results have no timing: SRT and VTT place them at `00:00:00`, TXT and DOCX
omit the time. The response is an attachment with an ASCII filename built from
the session title (or ID) and track.

Access uses the recording detail read check: a reader can export each session
they can open, and an unreadable session returns 404. A track with no stored
result also returns 404 `transcript-not-found`.

The formatters are plain functions in `samuraibff.transcripts.export`.

## Corrections

People who manage a session can correct its final transcripts. The machine
output never changes: each correction is one appended row in
`transcript_corrections` (migration `025-add-transcript-corrections`), and
the corrected transcript is the machine segments plus every correction that
is not undone. Each track is corrected on its own.

Kinds:

| Kind | Changes | Request fields |
| --- | --- | --- |
| `segment_text` | Text of one segment. An empty text clears the segment. | `segment_index`, `text` |
| `segment_speaker` | Speaker of one segment | `segment_index`, `speaker` or `speaker_id` |
| `speaker_label` | Speaker of every segment with a machine label (`SPEAKER_00` or an enrolled name). Two labels mapped to one name merge them. | `speaker_label`, `speaker` or `speaker_id` |
| `undo` | Cancels one earlier correction, once | `correction_id` |

Rules:

* A segment's text is its latest active text correction. Its speaker is its
  latest active segment speaker correction, else the latest active label
  correction for its machine label, else the machine label.
* Corrected text keeps the timing of unchanged words; other words share the
  time between their neighbours. Playback highlighting uses this timing.
* `speaker_id` links an enrolled speaker of the tenant. A speaker correction
  stores what the machine said in `old_value.machine_speaker` and, when
  exactly one enrolled speaker has that label, `machine_speaker_id`.
  Corrections never change enrollments.
* Each correction stores the author, the time, the previous and new value and
  the audio span of the segment.

API:

* `POST /api/recordings/{session_id}/transcripts/{transcript_id}/corrections`
  appends one correction (`kind`, `base_seq` and the fields above).
  `transcript_id` is the final record ID from the recording detail. The
  caller must manage the session: a reader gets 403 `read-only-access`, an
  unreadable session 404.
* `base_seq` is the highest correction `seq` the client has seen. The request
  is rejected with 409 `correction-conflict` only when a newer correction
  changed the same segment text, segment speaker or label. 409
  `already-undone` and 400 `correction-unchanged` also exist.
* `GET /api/recordings/{session_id}` returns each final record's machine
  `segments` and `corrections: {head_seq, items}`; every item has the author's
  display name. Readers see corrections read-only.
* Export and on-demand workflows use the corrected transcript. The
  OpenAI-compatible endpoint returns new uploads, which have no corrections.

UI (Final tab):

* Hover a segment: the pencil corrects the text in place (Ctrl+Enter saves,
  Esc cancels), the person icon changes the speaker of this segment or of
  every segment with its machine label, and **+** still adds the speaker.
  Every save is stored at once.
* When a transcript has corrections, a switch shows **Corrected** (default;
  edited segments have a margin marker and an `edited` tag), **Changes**
  (removed words struck through, added words underlined, a `was …` speaker
  tag, who changed what and when, **Undo** and the segment history) or
  **Original** (the machine output).
* The page refreshes every 4 seconds, so other people's corrections appear
  without a reload. A stale edit shows a notice and the latest version.
