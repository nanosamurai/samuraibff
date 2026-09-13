# Final track spike validation

Validated on 2026-09-13 on `implement-lean-final-tracks`.

- `TESTCONTAINERS_RYUK_DISABLED=true clojure -X:test`: full suite, 127 tests,
  955 assertions, zero failures/errors. Real Postgres tests cover persisted
  selection, reconnect, tenant denial, legacy NULL tracks and filtered history.
- Changed feature namespaces passed lint and compilation. The Kafka forwarding
  test passed separately (1 test / 9 assertions) after restricting its container
  port to localhost; its existing `:refer :all` lint warning remains.
- Rebuilt `samuraibff:lean-tracks`; the full Nanosamurai Compose smoke passed
  real HTTP/WebSocket audio input, pre-audio selection/retention rejection,
  saved selection and `sessions.meta`, independent final rows, track filtering,
  shared WAV/range playback and tenant denial. It also verified real WhisperX,
  silence, worker failure/restart, Kafka replay and database retry across services.

All local test ports bind to localhost. Docker contexts exclude credentials and
scratch files. Transcript response coercion now preserves nested stream controls;
the schema fix was required for the real HTTP reconnect assertion.

The repeatable overlay and assertions live in Nanosamurai's
`docker-compose.final-tracks-smoke.yml` and `smoke-tests/final-tracks/`; see its
`docs/final-tracks-spike.md` (mirrored in Nanodeploy). The rollout and full smoke
now target the original Compose project `nanosamurai` and its Postgres 18 database,
where migrations 017/018 were applied. All 117 original transcripts and 51
recording records were preserved. BFF uses its local image at
`http://127.0.0.1:8000`; the experimental Compose project is stopped. The original
LocalStack recordings bucket was empty at startup, so historical audio remains
unavailable there; the smoke verifies new recording and playback.

The browser still chooses one final result. Selection UI and separate result
tabs belong to spike 3; session `finished` does not prove all tracks succeeded.
