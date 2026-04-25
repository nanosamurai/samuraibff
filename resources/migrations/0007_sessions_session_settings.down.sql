-- Migration rollback: drop session_settings column

ALTER TABLE sessions
  DROP COLUMN IF EXISTS session_settings;
