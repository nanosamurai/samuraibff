-- Test-only migration: workflow_results_latest read model.
--
-- In production this table is maintained by samuraipersistor.
-- For BFF tests (recordings detail response) we need the table to exist.

CREATE TABLE workflow_results_latest (
    tenant_id               uuid NOT NULL,
    session_id              uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    workflow_id             uuid NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,

    created_at              timestamptz NOT NULL,
    workflow_run_id         uuid,

    trigger_type            text,
    trigger_source_event_id text,

    status                  text NOT NULL,
    render_markdown         text,
    render_json             jsonb,

    provider_type           text,
    provider_model_id       text,

    usage_input_tokens      integer,
    usage_output_tokens     integer,

    stream_source_uri       text,
    stream_source_node_id   text,

    error_code              text,
    error_detail            text,

    PRIMARY KEY (tenant_id, session_id, workflow_id)
);

CREATE INDEX idx_workflow_results_latest_tenant_session_created_at
  ON workflow_results_latest(tenant_id, session_id, created_at DESC);
