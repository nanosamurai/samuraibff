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
is `created` before applying pagination. The response includes `items`, `total`
(matching sessions), and `drafts_count` (all drafts for the authenticated tenant).
Counts remain available on empty pages. Existing callers retain their defaults.
Offset pages reflect current data; creating or deleting sessions can shift rows
between requests.

Items and both counts share one database snapshot. Latest-recording ties use
`id DESC` after `created_at DESC`.

### Sort

`sort` and `order` set the row order in the BFF, before pagination, so the
order holds across all pages:

* `sort`: `title`, `created_at` (default), `started_at`, `duration` or `status`.
* `order`: `asc` or `desc` (default).
* Other values return HTTP 400 `{"ok":false,"message":"invalid-sort"}`.
* Empty values (no title, not started, no duration) come last in both
  directions. The session `id` breaks ties in the same direction, so pages are
  stable.
* `title` ignores case and spaces at the ends.
* `status` sorts by the label that the UI shows, in this order: Created,
  Recording, Processing, Finished, Finalized, Failed (`asc`).
  `samuraibff.ui.session-status/labels-in-sort-order` holds this order for the
  UI and the SQL.
* Sorting does not change which sessions are listed or the counts.

The response echoes `sort` and `order`.

### Duration

Each item has `duration_s`: the session duration in seconds, `ended_at -
started_at`. If the session times are missing or out of order, the latest
recording's `duration_s` is used. An uploaded file (`source: "upload"`) always
uses the latest recording's `duration_s`, because its session starts and ends
at the upload; it is `null` until the recorder has converted the file.
`duration_s` is `null` for drafts (`created`) and running sessions (`active`). `recording.duration_s` is unchanged and still
holds the latest recording's own duration.

### Source

List items and the detail `session` have `source: "upload"` when the session
was created from an uploaded file (`POST /api/sessions/upload`, see
`docs/features-file-uploads.md`), and `source: "retranscription"` when it was
created from the saved audio of another session
(`POST /api/recordings/{session_id}/retranscribe`, see
`docs/features-retranscription.md`). Live sessions have no `source`. The BFF
stores the marker in `sessions.session_settings`; `POST /api/sessions` ignores
`source` and `parent_session_id` in its `session_settings`.

### Re-transcription fields

The detail `session` also has `can_retranscribe`, `retranscribe_unavailable`,
`recording_lang`, `retranscribed_from` and `retranscriptions`. Linked sessions
appear only when the caller may read them. See
`docs/features-retranscription.md`.

The session page shows the **Re-transcribe** icon button next to **Export
transcript**, a **Re-transcribed from** link in the header, and a
**Re-transcriptions** list with status labels.

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
When workflows and webhooks are enabled, an icon button at the right end of the
tab row opens and closes the Workflows / Webhooks side panel. It is closed when
the page opens. The panel is as tall as the Playback card and the transcript
together, and its lists scroll inside it.
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
