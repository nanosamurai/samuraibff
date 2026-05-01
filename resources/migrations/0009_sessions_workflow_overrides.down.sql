-- Migration rollback: drop session workflow override column

ALTER TABLE sessions
  DROP COLUMN IF EXISTS workflow_overrides;
