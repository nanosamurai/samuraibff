-- Migration: store session-scoped settings snapshot (webhook-agnostic)

ALTER TABLE sessions
  ADD COLUMN IF NOT EXISTS session_settings jsonb;
