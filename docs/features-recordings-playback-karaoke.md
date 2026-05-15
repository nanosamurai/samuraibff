# Recordings + playback + karaoke highlighting

This document describes the recordings API contract and the UI playback/karaoke
behavior.

For the original implementation plan, see:

* `docs/playback-karaoke-plan.md`

## Recordings REST API (contract)

* `GET /api/recordings` — tenant-scoped list of sessions
* `GET /api/recordings/{session_id}` — details including stored transcript records
* `GET /api/recordings/{session_id}/audio` — streams recorded audio

Important:

* The backend **must not** expose internal `recording_url` values (`file://`,
  `s3://`) to the browser via JSON.
* Playback is only via `GET /api/recordings/{session_id}/audio` and remains
  protected by auth + tenant isolation.

## Playback endpoint

The audio endpoint supports HTTP **Range** requests so the browser can seek.

## Karaoke highlighting (word-level timing)

When a final transcript segment includes `words[]` timing data
`{start_s,end_s,text}`:

* the UI renders the bubble as word spans
* the currently spoken word is highlighted based on `<audio>.currentTime`
* clicking a word seeks playback to `word.start_s` (best effort autoplay)
