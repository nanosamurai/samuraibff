# Recordings + playback + karaoke highlighting

This document describes the recordings API contract and the UI playback/karaoke
behavior.

For the original implementation plan, see:

* `docs/playback-karaoke-plan.md`

## Recordings REST API (contract)

* `GET /api/recordings` — tenant-scoped list of sessions
* `GET /api/recordings/{session_id}` — details including stored transcript records
* `GET /api/recordings/{session_id}/audio` — streams recorded audio

The list accepts `limit` (default 200), `offset` (default 0), and `show_drafts`
(boolean, default true). Set `show_drafts=false` to exclude sessions whose status
is `created` before applying pagination. Results sort by `created_at DESC, id DESC`
so tied timestamps have a stable order. The response includes `items`, `total`
(matching sessions), and `drafts_count` (all drafts for the authenticated tenant).
Counts remain available on empty pages. Existing callers retain their defaults.
Offset pages reflect current data; creating or deleting sessions can shift rows
between requests.

Items and both counts are read in one parameterized HoneySQL statement and share
one PostgreSQL statement snapshot. The counts aggregate is left-joined to the
limited session page, so empty pages still return counts. Latest-recording and
final-transcript lookups run only for the page; recording ties use `id DESC`.
See [query measurements and indexing considerations](sessions-pagination-query.md).

Important:

* The backend **must not** expose internal `recording_url` values (`file://`,
  `s3://`) to the browser via JSON.
* Playback is only via `GET /api/recordings/{session_id}/audio` and remains
  protected by auth + tenant isolation.

## Playback endpoint

The audio endpoint supports HTTP **Range** requests so the browser can seek.

Saved transcripts use flat per-track tabs such as `Final Transcript (WhisperX)`.
Labels are read from the session's frozen `stream_controls.track_labels`; old
NULL track IDs mean WhisperX. Selected tracks without rows remain visible as
unavailable, without inferring failure. The shared recording player appears only
on Final Transcript tabs and uses the selected final track's existing timing.
Follow sits in the Playback header when word timing is available, using the same
checkbox size and accent as the Record panel. Switching to a non-final tab removes
the player and stops playback.
Text-only rows use `full_text` without invented timestamps or speakers. Detail
feeds keep the latest final row within each track for legacy history and sort
refined windows by their audio bounds, independent of arrival order. Detail
pages reload their tenant-scoped Postgres results, including on direct links.
See [the lean UI spike](track-selection-ui.md).

## Karaoke highlighting (word-level timing)

When a final transcript segment includes `words[]` timing data
`{start_s,end_s,text}`:

* the UI renders the bubble as word spans
* the currently spoken word is highlighted based on `<audio>.currentTime`
* clicking a word seeks playback to `word.start_s` (best effort autoplay)
