-- Migration: store session-scoped webhook override request (immutable snapshot for audit)

ALTER TABLE sessions
  ADD COLUMN IF NOT EXISTS webhook_overrides jsonb;
