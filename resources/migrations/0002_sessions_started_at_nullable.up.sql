-- Migration: make sessions.started_at nullable and remove default.
--
-- Desired semantics:
-- - created_at: when the session row is created (POST /api/sessions)
-- - started_at: when audio recording actually begins (first /ws/audio connection)
--
-- Note:
-- Existing rows will retain their started_at value. If your DB previously defaulted
-- started_at=now() on insert, older “draft” sessions may still have started_at set.

ALTER TABLE sessions
  ALTER COLUMN started_at DROP NOT NULL;

ALTER TABLE sessions
  ALTER COLUMN started_at DROP DEFAULT;