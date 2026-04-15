-- Migration rollback: drop session webhook override column

ALTER TABLE sessions
  DROP COLUMN IF EXISTS webhook_overrides;
