# Playback + karaoke highlighting (word-level timing) — implementation plan

This document tracks the implementation of:

1. Word-level timing propagation into the UI (karaoke-style highlight).
2. Audio playback for finalized transcripts.

Upstream context:

- xamurai PR #66 (word timing): `docs/word-level-timing.md` in xamurai
- samuraipersistor PR #17 (persist words into DB)

## Status / checklist

### Phase 0 — prerequisites / alignment

- [x] Protobuf schema aligned with xamurai (`WordAlignment`, `SessionTranscriptSegment.words`).
- [x] Java stubs generated via Buf are present (repo already contains the generated `WordAlignment.java`).
- [x] Malli schema supports `segments[].words[]` (`samuraibff.schemas/TranscriptSegment`).

### Phase 1 — Audio playback API (backend)

- [x] Add tenant-scoped audio playback endpoint: `GET /api/recordings/:session_id/audio`.
- [x] Implement Range requests (`Range: bytes=start-end`) for seeking.
- [x] Secure `file:` playback via `:recordings :local-root` canonical path allowlisting.
- [x] Secure `s3:` playback via allowlist bucket config.
- [x] Integration test: file URL + Range.

### Phase 2 — UI playback (Final transcript tab)

- [x] Add UI helper to build audio URL (`/api/recordings/:session_id/audio`).
- [x] Render `<audio controls>` in the “Final transcript” tab.
- [x] Ensure UI keeps and passes word timing data (`segment.words`).

### Phase 3 — Karaoke highlighting

- [x] Add shared pure helpers (CLJC) for karaoke indexing + binary search.
- [x] Render final transcript as word spans when `words[]` present.
- [x] Highlight active word based on audio `currentTime`.
- [x] Click word to seek.
- [x] (Optional) Auto-scroll active word into view (“follow”).

### Phase 4 — Tests + documentation

- [x] Unit tests for karaoke pure functions.
- [ ] Update README.MD (new endpoint + config + karaoke feature).
- [ ] Run full test suite (`clojure -X:test`).

## Implementation notes / constraints

### Timing model

- `segment.start_s/end_s` and `word.start_s/end_s` are **absolute** within the recording/session.
- The browser `<audio>` time is also from 0 at recording start.
- Therefore karaoke highlighting can compare `audio.currentTime` (seconds) directly against `word` timestamps.

### Security

- Never expose `recording_url` (file://, s3://) directly to the browser via JSON.
- Playback is only via BFF streaming endpoint, still protected by auth + tenant isolation.

### Performance

- Prefer `timeupdate` events (or a throttled loop) rather than rAF.
- Precompute a flattened word index per recording, then use binary search to find active word.
