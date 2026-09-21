# Lean track selection UI

Spike 3 is on `implement-lean-track-selection-ui`, based on the unmerged
`implement-lean-refinement-tracks`. Only the BFF application changes. Xamurai
and Persistor keep their spike 2 workers, events, storage and consumer groups.

## Contract and interface

`GET /api/me` exposes the deployment's configured async IDs and labels.
`SAMURAIBFF_TRACK_LABELS` is a small JSON object with `final` and `refined` maps;
labels default to the ID, with `WhisperX` for `whisperx`. Async selections use
the stage's configured default ID, falling back to its first configured ID.
Realtime preselection uses `default_tracks.realtime`, or all tracks when unset.
See the README for the three optional `SAMURAIBFF_DEFAULT_*_TRACK` variables.
Labels are resolved by BFF at audio admission, stored in
`sessions.stream_controls.track_labels`, and copied into the existing
`sessions.meta.stream_controls`. Reconnects preserve that same snapshot.
The recordings response preserves the nested JSON object during coercion.

Settings reuse the earlier draft's stage-tab styling. Real-time, Refined and
Final each have an adjacent enable switch and independent track choices.
Clearing a last choice disables that stage; enabling it restores choices.
New session retains preferences. Execution choices lock when recording starts.
Recording settings require retention only for multiple final tracks. The
realtime maximum inference input remains distinct from its processing window.

Live refinement panels separate tracks. Saved-result tabs use the selected ID
order and include selected tracks with no row yet. They show available saved
results/windows, never infer a failure or all-track success from session status.
Saved text comes from Postgres. One audio player serves the selected track's
timings; plain full_text works without segments, timestamps or speakers.
Direct links, reloads, empty results, and independently arriving results remain
usable. Realtime history remains a browser cache because it is not persisted.
Realtime messages retain the `?` avatar and `Unknown` label until a speaker is
available. Hiding absent speakers applies to saved/refined text-only results;
it must not shift realtime partial text out of alignment with diarized turns.

## Validation

The owning Compose recipe and repeatable browser/DB/Kafka tests are in
Nanosamurai's `docs/track-selection-ui.md` and `smoke-tests/track-ui/`, mirrored
in Nanodeploy. They use real browser microphone capture from the repository's
audio fixture, real WhisperX, the existing BFF/Kafka/Persistor/Postgres path,
and explicitly test-only synthetic second tracks. Synthetic output establishes
plumbing, not another model's quality.

The browser checks default, multiple and alternative-only selections; each
stage alone; disabled stages; live refinement before stop; locked settings;
retained preferences; saved reloads; per-track availability; text-only and
word-aligned playback. The separate audit verifies one recording per session,
tenant denial, nested controls, and the Kafka label snapshot. A BFF restart with
renamed deployment labels checks historical label preservation.

Local qualification on 2026-09-14 used the original `nanosamurai` project,
Postgres 18 database and retained volumes. No migrations were applied. The BFF
release build, lint, full backend test suite and Electron tests are part of the
qualification; detailed output lives in ignored `.tmp/lean-track-ui/`.
The final suite passed 129 tests / 982 assertions, plus all eight Electron
tests. Ordinary realtime/refined/final playback passed after restoring the
normal Compose configuration and stopping the synthetic workers.

The 2026-09-15 realtime placeholder follow-up reproduced the missing avatar/name
on the previous image, then passed `smoke-tests/track-ui/realtime.cjs` after
rebuilding and replacing only BFF. Real Nemotron partials show `Unknown`/`?`
and align with a diarized final turn. Saved text-only results still omit
speakers/timing, and aligned WhisperX results retain avatars. Lint, release
build, 129 backend tests / 982 assertions and eight Electron tests passed again.
Nemotron endpoint and diarization settings were unchanged.

## Boundaries

No new tables, topics, protobufs, result envelopes, stored transcript files or
worker-discovery service. Database migrations remain owned by Nanosamurai and
Nanodeploy; Persistor's historical migration directory is not used.

Durable per-track failures and completion require a later contract. An absent
result is reported as unavailable, including when session status is Finished.
Older audio missing from the existing LocalStack bucket cannot be reconstructed
by this UI. Resume after an idle-flushed session remains outside this spike;
start a new session. Admission failures now appear beside recording controls,
but the earlier backend status transition can still leave such a saved session
Active despite accepting no audio.

Tenant checks remain in place. Labels render as text. Smoke services publish
no host ports; existing stack ports stay on loopback. Test workers and their
allowlist are removed from the running configuration after qualification.
Helm/cloud rollout and webhook/workflow track migration remain out of scope.
