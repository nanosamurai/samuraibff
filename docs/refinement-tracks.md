# Refinement tracks

Set `SAMURAIBFF_REFINEMENT_TRACKS_ENABLED=true` after deployment migrations 014
and 015 and the refinement workers are installed. It defaults off. Optional
`SAMURAIBFF_REFINEMENT_TRACKS_JSON` uses the same operator-only catalog shape as
final tracks, with `whisperx-medium-refined-r1` as the default primary profile.
One to four tracks are allowed, exactly one primary. Synthetic
`test-refined-r1` requires `SAMURAIBFF_REFINEMENT_TRACK_TEST_PROFILE_ENABLED=true`.

On audio admission, the BFF freezes selected refinement tracks and the existing
`refinement_window_sec` control (10–600 seconds, default 60) into the session's
`asr_plan`. Refinement-only and combined final/refinement sessions are supported.
The same plan is persisted in SQL, published in the complete `sessions.meta`
snapshot, and carried with audio in canonical headers. Existing workflow and
webhook metadata stays in that snapshot. No topic key changes occur here.

The spike accepts retained mono PCM16/16 kHz audio only. Worker assembly is
bounded to ten-minute sessions; longer sessions and reconnect after terminal
closure require later lifecycle work. After the accepted audio queue drains,
the BFF emits an empty `AudioChunk` with the next sequence and `x-audio-eof=true`.
This terminal marker is specific to planned refinement and is not sent to ASR.

Only successful primary windows appear on the existing `transcripts.refined`,
WebSocket and recordings API paths. All tracks publish canonical outcomes on
`transcripts.refined-tracks`; there is no new UI selector or track query API.
The existing BFF refinement merger uses absolute session times. Persistor
deduplicates primary rows by stable result identity; WebSocket delivery remains
at least once. Use consented local fixtures until shared source and derived
artifact deletion/retention are qualified.
