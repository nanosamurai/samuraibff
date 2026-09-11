# Refinement and final track selection

The opt-in track UI builds on the final/refinement worker contracts and deployment
migrations 014/015. It adds no migration, worker capability RPC, or worker health
dependency. Use the matching `implement-track-selection-ui` BFF and `nanosamurai`
branches, based on `implement-refinement-tracks`; existing refinement worker and
Persistor images remain compatible.

## Operator catalog

Enable each stage with `SAMURAIBFF_REFINEMENT_TRACKS_ENABLED` or
`SAMURAIBFF_FINAL_TRACKS_ENABLED`. Both default off. Their existing `*_TRACKS_JSON`
settings now accept presentation/default fields:

```json
[
  {"track_id":"whisperx","profile_id":"whisperx-medium-refined-r1","primary":true,"display_name":"WhisperX","default_selected":true},
  {"track_id":"shadow","profile_id":"test-refined-r1","primary":false,"display_name":"Test text only","default_selected":false}
]
```

For final output use `whisperx-medium-final-r1` and `test-final-r1`. Synthetic
profiles require the stage's explicit `SAMURAIBFF_*_TRACK_TEST_PROFILE_ENABLED`
gate and the corresponding worker gate. They are local test fixtures, not speech
models. Catalog IDs/profiles must match worker configuration. The public Compose
repo supplies `smoke-tests/check_track_catalog.py` to check that agreement before
startup; it does not probe live worker health.

Each enabled stage has one to four unique track IDs and exactly one primary.
The primary must be selected by default. An optional `tenant_ids` array of
canonical tenant UUIDs restricts a secondary entry; the primary is shared.
Startup rejects malformed catalogs, unsupported profiles, unknown fields, invalid
names, and invalid primary/default combinations. Display names are nonblank,
at most 80 characters, and rendered as text. Missing presentation fields retain
legacy behavior: the ID is the label and the entry is selected by default.

`GET /api/me` advertises `async_tracks`: permitted entries with `stage`,
`track_id`, `profile_id`, `display_name`, `default_selected`, and `primary`.
ACLs, addresses, model locations and runtime settings remain server-side.
Catalog membership means configured support, not a healthy or running worker.

## Session controls and freeze

Before recording, open **Session settings** and independently enable **Refined**
and **Final**, then select optional tracks under each stage. The required primary
checkbox is fixed. Controls lock when recording starts; **New session** keeps
preferences but creates a new identity. Disabling a stage disables its picker.

`GET /ws/audio` accepts optional comma-separated ID parameters:

```text
refinement_tracks=whisperx,shadow&final_tracks=whisperx
```

Omission uses permitted catalog defaults. Explicit lists must contain one to four
distinct configured IDs, including the primary, and may be supplied only for an
enabled/requested stage. Clients cannot supply profiles, endpoints or runtime
arguments. Invalid or forbidden selections receive HTTP 400 before audio is
accepted. Normal session/tenant authorization still applies.

At first audio admission the BFF resolves IDs under the authenticated tenant and
freezes the existing `sessions.stream_controls.asr_plan`. The worker plan still
contains only track ID, profile ID and primary for each selection. Safe catalog
presentation is stored separately in `sessions.asr_meta_snapshot.asr_track_catalog`
and included in the complete metadata snapshot. Reconnects must use the original
controls and retain their original plan, even if current catalog defaults or
profiles change. A finished session cannot be reopened for audio.

Retained mono PCM16/16 kHz audio and the existing ten-minute worker limit apply.
The refinement window is 10–600 seconds. **Store** is required for either planned
stage. Refinement-only retains its window artifacts; enabling Final also retains
the full recording needed for playback. With track stages disabled, legacy
retention behavior remains unchanged.

## Read API and tabs

| Read | Response |
| --- | --- |
| `GET /api/sessions/:session_id/tracks` | Frozen selected tracks, labels, primary, session status, recording availability and indexed result metadata; includes pending tracks |
| `GET /api/sessions/:session_id/track-results/:result_id` | One selected outcome and, on success, its verified transcript |

Both routes use the normal tenant authorization boundary and `private, no-store`
caching. Missing or foreign sessions/results return 404. Invalid UUIDs return
400. Unavailable or inconsistent artifacts return a sanitized 503. Reads check
the persisted plan, stage, track, profile and primary before accessing storage.
The BFF derives the exact permitted key in its configured recordings bucket,
checks the indexed URI and SHA-256, limits the read to 1 MB, and applies request
timeouts. Arbitrary client storage URLs are never accepted. Responses omit S3
locations and runtime provenance; capability flags determine which timing and
speaker fields are exposed.

The index is bounded to the spike's 60 windows × four refinement tracks plus
four final tracks. It is not a paginated, multi-run history API. The UI polls
metadata every two seconds while mounted and reads artifacts only for the viewed
track. Each stage uses full-panel track tabs in both Record and session history.
Waiting, success, per-window failure and degraded output are shown independently.
Tab selection does not start processing or change the configured primary used by
compatibility events, workflows and webhooks.

Playback reuses the existing tenant-authorized recording audio endpoint. Each
track's own real words drive karaoke highlighting and click-to-seek. Refinement
timings are already absolute session seconds; the UI orders windows and does not
apply a second offset. Text-only output remains readable with ordinary playback
when audio exists; absent timings are never synthesized. Historical labels and
identities come from the frozen snapshot, with ID fallback for older sessions.
Direct `/live` and `/recordings/:id` navigation/reload is supported.

## Verification and scope

The full BFF suite covers catalog/default/ACL validation, frozen plans, tenant
denial, exact artifact scope, digest/size rejection, actual Postgres/S3 reads,
HTTP JSON encoding/coercion and timestamp assembly. Build the normal Dockerfile
and follow the `nanosamurai` [Chromium smoke guide](https://github.com/nanosamurai/nanosamurai/blob/implement-track-selection-ui/docs/track-selection.md)
for microphone capture, multi-track live/final results, text-only and failed
tracks, real playback, reload and stage-only sessions.

Use consented local fixtures. Shared source/derived-artifact deletion, deletion
during processing, sessions over ten minutes, a second real model, SDK expansion,
workflow/webhook migration and production Helm enablement remain separate work.
Existing worker timing validation may reject an output as
`invalid_provider_timing`; this is displayed as a failed outcome rather than
inventing timestamps or suppressing other tracks.
