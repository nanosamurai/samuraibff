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
- [x] Update README.MD (new endpoint + config + karaoke feature).
- [x] Run full test suite (`clojure -X:test`).

## PR summary — karaoke highlighting in UI

This PR completes Phase 3 (“karaoke highlighting”) by rendering word-level timing in the UI and syncing it with audio playback.

### Data model / timing

- Final transcript segments from DB may include `words[]`:
  - each word: `{start_s,end_s,text}`
- All timestamps are **absolute within the session recording**.
- `<audio>` playback time (`audio.currentTime`) is also relative to recording start, so we can compare directly.

### How highlighting is implemented

Files:
- UI renderer: `src/cljs/samuraibff/ui/components.cljs`
- Pure helpers: `src/shared/samuraibff/ui/karaoke.cljc`

Algorithm:
1) Build a flattened, sorted “word index” from transcript messages using `karaoke/build-word-index`.
   - Each entry includes `:msg-idx` and `:word-idx` so we can map back to a specific word span.
2) On every audio time update, find the active word via `karaoke/active-word-idx-normalized`.
   - This uses a binary search over the flattened index (fast enough for long recordings).
3) Render the final transcript bubble text as a sequence of `<span class="word">` elements.
   - The currently active word span receives the additional class `active`.

### Sync with playback (`<audio>`) and UI state

No new global UI atoms were introduced; everything is local to the Recording detail page.

Local React state added in `recording-detail-page`:
- `audio-ref` (`react/useRef`): reference to the `<audio>` element.
- `current-time-s` (`react/useState`): updated from `<audio>` events.
- `follow?` (`react/useState`): whether to auto-scroll the active word into view.

How `current-time-s` is updated:
- `final-audio-player` accepts `:on-time` and wires it to `on-time-update` + `on-seeked`.
- `on-time->current-time-s` reads `e.target.currentTime` and normalizes to `double`.

### Interaction

- Click a word: seeks the `<audio>` element to that word’s `start_s` and attempts autoplay.
- Follow mode: when enabled, the active word span is scrolled into view via `scrollIntoView`.

### Styling

CSS for `.karaoke .word` + `.karaoke .word.active` lives in `resources/public/index.html`.

## Implementation notes / constraints

### Timing model

- `segment.start_s/end_s` and `word.start_s/end_s` are **absolute** within the recording/session.
- The browser `<audio>` time is also from 0 at recording start.
- Therefore karaoke highlighting can compare `audio.currentTime` (seconds) directly against `word` timestamps.

### Security

- Never expose `recording_url` (file://, s3://) directly to the browser via JSON.
- Playback is only via BFF streaming endpoint, still protected by auth + tenant isolation.

### Performance

- Do **not** rely solely on the `<audio>` `timeupdate` event for karaoke playback.
  It is often emitted at a low frequency (~4–10 Hz), which can skip short words.
- Prefer a `requestAnimationFrame` sampling loop while the audio is playing, optionally
  throttled (e.g. update state only when the time changed by >= 10ms).
- Precompute a flattened word index per recording, then use binary search to find active word.
