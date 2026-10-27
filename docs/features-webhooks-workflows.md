# Webhooks + workflows

This document summarizes the webhook/workflow surface area and links to deeper
implementation notes.

## Access to definitions

Webhooks and workflows are definitions. Each definition has an `availability`:

* `published`: every active person in the organization can see and use it.
  Only an Admin edits, deletes or unpublishes it.
* `personal`: only its owner (a Builder or Admin) can see and use it. The owner
  and Admins can edit or delete it.

Roles:

* Member: lists published definitions only. The list omits prompt text,
  provider parameters, incremental settings, secret references, auth settings
  and static headers. Cannot create.
* Builder: creates personal definitions only (a request for `published` gets
  `403 forbidden`). Sees full details only for own personal definitions.
* Admin: sees and manages every definition. Creates published definitions by
  default (`availability` may be `personal`). Publishes and unpublishes with
  `availability` on update. Sets defaults.
* Service principals (API credentials) get `403 forbidden` on these routes.

Each list item has `availability`, `owner` (`{id, display_name, is_me}` or
null) and `can_manage`. A definition that the caller cannot see returns
`404 not-found`; one the caller can see but not manage returns
`403 forbidden`. Names are unique per organization.

Defaults (`/api/webhooks/defaults`, `/api/workflows/defaults`): every person
can read them; only an Admin sets them. Every default must be a published
definition of the organization, else `400 invalid-default`. Unpublishing or
deleting a definition removes it from the defaults in the same transaction.

Changes to definitions and defaults write an access audit event without
prompts, URLs or secrets.

## Session use

A session uses only definitions that its owner may use at resolution time:
the owner's own personal definitions and published definitions. Defaults add
published definitions only. When the owner is demoted or suspended, or a
definition is unpublished, new resolutions stop using it. A session without an
active human owner uses no definition. Using a definition does not give its
author access to the session.

At the first audio start the resolved selection is stored in
`sessions.admission_snapshot`. Later audio starts of that session reuse it, so
later edits to definitions do not change a running session. The stored value
holds secret references only, never secret values.

## Webhooks (session overrides)

The UI can provide **session-scoped webhook routing overrides** when creating a
session. These are persisted in Postgres and used to resolve an immutable
routing snapshot published to Kafka (`sessions.meta`).

## Workflows

Workflows are tenant-scoped post-processing definitions executed by an
external integration that is not part of Community Edition.

### Source track

Each workflow definition selects one transcript source:

```json
{"trigger":{"type":"transcript.refined.segment","track_id":"parakeet"}}
```

Final workflows use `transcript.final.ready` with a final track ID. The editor
lists configured tracks for that stage from `/api/me.async_tracks`. The API
requires a nonblank ID for both transcript trigger types and rejects track IDs
on `recording.finished`. Realtime ASR is delivered over gRPC/WebSocket and is not
a workflow trigger in this integration.

The binding is persisted in `workflows.trigger_track_id` and copied to
`sessions.meta.workflows.targets[].trigger.track_id`. The router matches both
event type and track. Selecting a workflow does not enable its transcription
track: that track must also be selected for the session. The session settings
list shows the workflow's event type and source ID.

Use another workflow definition to apply the same prompt to another track. This
keeps one result per `(session_id, workflow_id)` and needs no change to result
persistence keys or session override multiplicity. Webhook event subscriptions
receive all matching tracks separately, identified by `event.data.track_id`.

### Deployment and validation

Deploy together with the matching webhook-router and workflow-runner. Apply
Nanodeploy migration `020-bind-workflows-to-tracks.up.sql` first; it adds the
workflow column and binds existing transcript definitions to `whisperx`. Review
those definitions if a different track should supply their input. The migration
does not change recording triggers. There are no new environment variables.

Validation covers API/schema rejection of missing or invalid bindings, CRUD
through Postgres, tenant isolation, and propagation into the routing snapshot.
The full backend suite and UI release build pass. Kafka test containers bind
only to localhost. Track selection adds no egress destinations or permissions;
workflow prompts and transcript bodies are not added to logs.

### Results streaming

Data flow (high level):

* an external workflow integration produces results to Kafka topic
  `workflow.result` (JSON)
* every BFF instance consumes from `workflow.result`
* non-origin instances forward to origin instance via:
  * `POST /internal/workflow-result` (`application/json`)
* origin instance pushes a `/ws/events` JSON event:
  * `{"type":"workflow_result", ...}`
