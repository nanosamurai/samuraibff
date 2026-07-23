# Webhooks + workflows

This document summarizes the webhook/workflow surface area and links to deeper
implementation notes.

## Webhooks (session overrides)

The UI can provide **session-scoped webhook routing overrides** when creating a
session. These are persisted in Postgres and used to resolve an immutable
routing snapshot published to Kafka (`sessions.meta`).

## Workflows

Workflows are tenant-scoped post-processing definitions executed by an
external integration that is not part of Community Edition.

### Results streaming

Data flow (high level):

* an external workflow integration produces results to Kafka topic
  `workflow.result` (JSON)
* every BFF instance consumes from `workflow.result`
* non-origin instances forward to origin instance via:
  * `POST /internal/workflow-result` (`application/json`)
* origin instance pushes a `/ws/events` JSON event:
  * `{"type":"workflow_result", ...}`
