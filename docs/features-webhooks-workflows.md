# Webhooks + workflows

This document summarizes the webhook/workflow surface area and links to deeper
implementation notes.

## Webhooks (session overrides)

The UI can provide **session-scoped webhook routing overrides** when creating a
session. These are persisted in Postgres and used to resolve an immutable
routing snapshot published to Kafka (`sessions.meta`).

Read more:

* `docs/readme-archive.md` (historical detail until this doc is further expanded)

## Workflows

Workflows are tenant-scoped post-processing definitions executed by
**workflow-runner**.

### Results streaming

Data flow (high level):

* workflow-runner produces results to Kafka topic `workflow.result` (JSON)
* every BFF instance consumes from `workflow.result`
* non-origin instances forward to origin instance via:
  * `POST /internal/workflow-result` (`application/json`)
* origin instance pushes a `/ws/events` JSON event:
  * `{"type":"workflow_result", ...}`
