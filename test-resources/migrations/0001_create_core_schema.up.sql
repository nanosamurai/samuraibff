-- Test-only schema bootstrap for Postgres Testcontainers.
-- This is copied from drsynth/migrations/versions/0001_create_core_schema.up.sql

-- Tenants (companies, clinics, etc.)
CREATE TABLE tenants (
    id             uuid PRIMARY KEY,
    name           text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

-- Users within a tenant (doctors, coaches, etc.)
CREATE TABLE app_users (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    external_id    text UNIQUE,          -- e.g. auth provider ID or email
    email          text NOT NULL,
    name           text,
    roles          text,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_app_users_tenant ON app_users(tenant_id);

CREATE TABLE sessions (
    id             uuid PRIMARY KEY,      -- app-generated UUIDv7/ULID
    tenant_id      uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id        uuid REFERENCES app_users(id) ON DELETE SET NULL,

    -- business key used across Kafka messages (can also be the same as session_id if you want)
    session_key    text NOT NULL UNIQUE,

    title          text,                  -- optional: "Cardio followup with John"
    status         text NOT NULL DEFAULT 'active',   -- active | finished | failed
    -- When the session is first created, this is NULL.
    -- Set to the timestamp when audio recording begins (first successful /ws/audio connect).
    started_at     timestamptz,
    ended_at       timestamptz,           -- set when finalized
    -- Stream controls snapshot (outputs + retention + realtime knobs)
    stream_controls jsonb,

    -- Session-scoped webhook override request body snapshot (used to compute sessions.meta)
    webhook_overrides jsonb,

    -- Session-scoped settings snapshot (webhook-agnostic; workflows/LLM/etc.)
    session_settings jsonb,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_sessions_tenant_user ON sessions(tenant_id, user_id);
CREATE INDEX idx_sessions_status ON sessions(status);

CREATE TABLE recordings (
    id             uuid PRIMARY KEY,
    session_id     uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,

    recording_url  text NOT NULL,        -- file://..., s3://...
    duration_s     double precision NOT NULL,
    sample_rate    integer NOT NULL,
    lang           text,                 -- "en", "cs", ...
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_recordings_session ON recordings(session_id);

CREATE TABLE session_transcripts (
    id             uuid PRIMARY KEY,
    session_id     uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    recording_id   uuid REFERENCES recordings(id) ON DELETE SET NULL,

    tenant_id      uuid NOT NULL,        -- duplicated for simpler querying
    user_id        uuid,                 -- duplicated as well

    full_text      text NOT NULL,        -- concatenated transcript
    lang           text,
    duration_s     double precision,
    segments       jsonb NOT NULL,       -- [{start_s, end_s, text, speaker}, ...]
    created_at     timestamptz NOT NULL DEFAULT now(),

    -- Metadata for refined/final pipelines (samuraipersistor)
    source              text NOT NULL,   -- rtservice | whisperx | ...
    type                text NOT NULL,   -- refined | final
    model               text,
    window_length       integer,
    segment_start_s     double precision,
    segment_end_s       double precision,
    supersedes_seq      bigint[],
    event_created_at_ns bigint
);

CREATE INDEX idx_session_transcripts_tenant ON session_transcripts(tenant_id);
CREATE INDEX idx_session_transcripts_user ON session_transcripts(user_id);
CREATE INDEX idx_session_transcripts_session_created_at ON session_transcripts(session_id, created_at DESC);
CREATE INDEX idx_session_transcripts_event_created_at_ns ON session_transcripts(event_created_at_ns);
CREATE INDEX idx_session_transcripts_session_type_source_window
  ON session_transcripts(session_id, type, source, window_length, segment_start_s);

CREATE TABLE speakers (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id        uuid REFERENCES app_users(id) ON DELETE SET NULL,
    audio_url      text NOT NULL,

    -- e.g. "Dr. Novak", "Miroslav", "Patient A"
    label          text NOT NULL,

    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_speakers_tenant_user ON speakers(tenant_id, user_id);

-- M2M API credentials inventory (test schema mirror).
CREATE TABLE api_credentials (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name               text NOT NULL,
    keycloak_client_id text NOT NULL UNIQUE,
    created_by_sub     text,
    revoked_at         timestamptz,
    last_used_at       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_credentials_tenant ON api_credentials(tenant_id);
CREATE INDEX idx_api_credentials_tenant_revoked ON api_credentials(tenant_id, revoked_at);

-- Webhooks (tenant-scoped webhook endpoint configs)
CREATE TABLE webhooks (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    name          text NOT NULL,
    url           text NOT NULL,
    enabled       boolean NOT NULL DEFAULT true,

    auth_type     text NOT NULL,

    hmac_secret_ref         text,
    oauth_client_secret_ref text,
    api_key_ref             text,

    oauth_token_url   text,
    oauth_client_id   text,
    oauth_scopes      text,
    api_key_header_name text,
    api_key_prefix      text,

    static_headers jsonb,

    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhooks_tenant ON webhooks(tenant_id);
CREATE UNIQUE INDEX idx_webhooks_tenant_name ON webhooks(tenant_id, name);

CREATE TABLE webhook_subscriptions (
    tenant_id  uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    webhook_id uuid NOT NULL REFERENCES webhooks(id) ON DELETE CASCADE,
    event_type text NOT NULL,

    created_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, webhook_id, event_type)
);

CREATE INDEX idx_webhook_subscriptions_tenant_event_type
  ON webhook_subscriptions(tenant_id, event_type);

CREATE TABLE tenant_webhook_defaults (
    tenant_id   uuid PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    webhook_ids uuid[] NOT NULL DEFAULT '{}'::uuid[],
    updated_at  timestamptz NOT NULL DEFAULT now()
);
