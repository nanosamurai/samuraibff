-- Migration: store session-scoped workflow override request (immutable snapshot for audit)

ALTER TABLE sessions
  ADD COLUMN IF NOT EXISTS workflow_overrides jsonb;
