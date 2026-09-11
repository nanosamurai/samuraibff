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

Before recording, open **Session settings**. **Real-time**, **Refined**, and
**Final** each have a tab with an adjacent enable checkbox; toggling it does not
open the tab. **Recording** contains the storage choice, while Audio, Webhooks
and Workflows keep their own tabs when available.

All tracks are equal choices in the UI, including the operator's preferred
provider. Clearing the last selection turns the stage off. The tab checkbox
clears the active selections and restores the last nonempty set when re-enabled
(or catalog defaults, then the first available track). Selecting a track while
off enables it immediately. Explicit empty selections never revert to defaults
at audio admission. Track preferences are kept in memory, not browser storage.
Controls lock when recording starts; **New session** keeps preferences but creates
a new identity.

`GET /ws/audio` accepts optional comma-separated ID parameters:

```text
refinement_tracks=whisperx,shadow&final_tracks=whisperx
```

Omission uses permitted catalog defaults. Explicit lists must contain one to four
distinct configured IDs and may be supplied only for an
enabled/requested stage. Clients cannot supply profiles, endpoints or runtime
arguments. Invalid or forbidden selections receive HTTP 400 before audio is
accepted. Normal session/tenant authorization still applies.

The BFF automatically assigns the worker contract's compatibility `primary` to
the operator's preferred provider if selected, otherwise the first selected
provider in catalog order. There is no additional required track or primary
choice in the UI. Existing single-output APIs, workflows and webhooks therefore
receive that selected provider's result. This choice is frozen with the plan;
changing display tabs cannot change it. Catalog ACLs and profile validation
continue to apply before choosing this compatibility output.

At first audio admission the BFF resolves IDs under the authenticated tenant and
freezes the existing `sessions.stream_controls.asr_plan`. The worker plan still
contains only track ID, profile ID and primary for each selection. Safe catalog
presentation is stored separately in `sessions.asr_meta_snapshot.asr_track_catalog`
and included in the complete metadata snapshot. Reconnects must use the original
controls and retain their original plan, even if current catalog defaults or
profiles change. A finished session cannot be reopened for audio.

Retained mono PCM16/16 kHz audio and the existing ten-minute worker limit apply.
The refinement window is 10–600 seconds. **Store** is required for either planned
stage and is enforced automatically when admitting audio. Refinement-only retains its window artifacts; enabling Final also retains
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
track. Record uses full-panel track views within each transcript stage. Session
history uses a single row of tabs such as **Refined Transcript (WhisperX)** and
**Final Transcript (WhisperX)**, without a second level of engine tabs. Pending
tracks remain visible and older sessions without a plan retain their legacy tabs.
Waiting, success, per-window failure and degraded output are shown independently.
Tab selection does not start processing or change the frozen compatibility output used by
compatibility events, workflows and webhooks.

Playback reuses the existing tenant-authorized recording audio endpoint. Each
track's own real words drive karaoke highlighting and click-to-seek. Refinement
timings are already absolute session seconds; the UI orders windows and does not
apply a second offset. Text-only output remains readable with ordinary playback
when audio exists; absent timings are never synthesized. Historical labels and
identities come from the frozen snapshot, with ID fallback for older sessions.
Direct `/live` and `/recordings/:id` navigation/reload is supported.

## Verification and scope

The `streamline-session-settings` follow-up preserves the unmerged track UI and
adds tests for empty selections, restoring preferences, automatic compatibility
selection, and flat history tabs. Verification on 2026-09-11 passed the full BFF
suite (150 tests, 1,100 assertions), eight Electron tests, clj-kondo, the production
UI compile and Docker build. Browser checks against the loopback stack covered
direct header toggles, clearing/reselecting the last refined track, the Recording
tab, and loading a saved WhisperX transcript with its audio and word timings in
one tab. No browser errors were reported.

The local preview uses `samuraibff:streamlined-ui` at `http://127.0.0.1:8000`.
When recreating it with the existing Compose overlays, set
`TRACK_UI_BFF_IMAGE=samuraibff:streamlined-ui`. This is a local image; this UI
follow-up does not change the recommissioned EKS image pins.

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
