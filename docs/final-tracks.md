# Opt-in final-track plans

The finalizer-first spike freezes an operator-selected plan when `/ws/audio`
starts a retained, mono 16 kHz recording. Set `SAMURAIBFF_FINAL_TRACKS_ENABLED=true`
only after applying deployment migration `014-final-track-results.up.sql`.
The BFF-owned `0010_sessions_asr_meta_snapshot` migration adds the same snapshot
column for independently migrated BFF installations; it does not replace the
deployment migration needed by Persistor.

`SAMURAIBFF_FINAL_TRACKS_JSON` optionally supplies an array of selections:

```json
[{"track_id":"whisperx","profile_id":"whisperx-medium-final-r1","primary":true}]
```

That is also the default. The catalog allows at most four unique logical track
IDs, exactly one primary, and fixed allowlisted profiles. The synthetic
`test-final-r1` profile requires `SAMURAIBFF_FINAL_TRACK_TEST_PROFILE_ENABLED=true`.
There is no public track picker, model URL, or runtime-argument API.

The transaction locks the tenant-owned session and stores the plan in
`sessions.stream_controls.asr_plan`. The complete session inception metadata is
saved in `sessions.asr_meta_snapshot`; admission adds `asr_plan` while preserving
the existing routing, workflow, and refinement sections. Its Kafka publication
is acknowledged before audio is accepted. Reconnects reuse the frozen plan and
controls. A session already started without a plan cannot be upgraded in place.
A frozen session cannot switch back to legacy admission or change retention.

Every audio event carries canonical `x-asr-plan` JSON, `x-asr-plan-id`, and
`x-final-track-ids`. Recorder copies that plan into `RecordingFinished` and emits
one source event. Track groups consume that shared topic and filter locally;
they do not depend on a timely `sessions.meta` lookup. The compacted metadata
topic remains the latest-session view, not the authority for an emitted job.

Each selected track publishes to `transcripts.final-tracks`. Only the successful
primary also projects the existing `SessionTranscript` body onto
`transcripts.final`, preserving the current transcript and recording API. No
secondary-track UI or query API is added by this spike.

The feature defaults off. The opt-in mode requires `store_recording=true`, S3
source storage, and completed recordings bounded to 600 seconds in the recorder.
Shared artifact retention/deletion and live refinement execution remain later
work. Use the isolated `docker-compose.final-tracks.yml` in `nanosamurai` for
qualification; do not enable this on general user recordings yet.

Focused tests cover catalog validation, canonical cross-language headers,
transactional freezing/replay, retention rejection, tenant ownership, and
preservation of full session metadata. The full BFF suite also exercises the
existing feature-off WebSocket, refined forwarding, transcript, recording and
authentication paths. Test HTTP and Testcontainers services bind to loopback;
set `TESTCONTAINERS_RYUK_DISABLED=true` when running the suite locally so the
automatic cleanup sidecar does not publish a wildcard port. Fixtures still stop
their own containers in `finally`.
