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
* Organization API keys get `403 forbidden` on these routes. A personal API
  key lists and views like its owner but never creates, edits, publishes or
  sets defaults. Sessions of organization keys run published definitions.

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

Workflows are tenant-scoped post-processing definitions. An external workflow
runner executes them. The BFF does not call a model.

Two paths start a workflow:

* Automatic (full stack only): an event router reads the session's workflow
  targets from `sessions.meta` and starts a run on each matching transcript
  event.
* On demand (both editions): a person starts a run on a finished session (see
  [Run on demand](#run-on-demand)).

### Editions

| Setting | Community Edition | Full stack (`SAMURAIBFF_CE_MODE=false`) |
| --- | --- | --- |
| Workflow definitions, on-demand runs, saved results | Off unless `SAMURAIBFF_WORKFLOWS_ENABLED=true` | On |
| Automatic runs, session workflow selection, defaults in `sessions.meta` | Off | On |
| Webhooks, live workflow results on `/ws/events` | Off | On |

`/api/me.features.workflows_enabled` tells the UI whether to show the
Workflows page and the recording page Workflows panel. In Community Edition,
defaults and triggers are stored but start nothing automatically. Enable
workflows only when a workflow runner consumes `workflow.dispatch`.

### Provider types

`SAMURAIBFF_WORKFLOW_PROVIDER_TYPES` is a comma-separated list of provider
types (default `bedrock`). For example, use
`bedrock,openai_compatible` when the runner also has an OpenAI-compatible
provider (Ollama, vLLM). Create and update accept only listed types, else
`400 provider-not-allowed`. A stored definition with another type stays
usable and editable while its type does not change.
`/api/me.workflow_provider_types` returns the list for the editor. Provider
endpoints and model allowlists are runner settings; a definition holds only
the type, model ID and parameters.

The workflow editor offers exactly that list, with readable names; the API
keeps the type IDs:

| Type ID | Shown as |
| --- | --- |
| `bedrock` | AWS Bedrock |
| `openai_compatible` | Self-hosted LLM (OpenAI-compatible API) |

An unknown type ID is shown as is. A saved type that is not in the list is
shown with "(not enabled on this server)" and stays selected, so saving the
workflow keeps it.

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

### Run on demand

```http
POST /api/recordings/{session_id}/workflows/{workflow_id}/runs
Content-Type: application/json

{"stage":"refined","track_id":"parakeet"}
```

The body is optional. It selects the stored transcript source:

1. `stage` and `track_id`: exactly that source. `track_id` without `stage` is
   `400 invalid-request`.
2. `stage` only: the workflow's own track for that stage, the default track of
   that stage, then the first stored track of that stage.
3. No body: the workflow's own source when it is stored, then the default final
   track, then the first stored final track.

Any workflow can use any stored source. A `recording.finished` workflow uses
rule 2 or 3. Stage `final` sends trigger `transcript.final.ready` with input
kind `final` (latest final record of the track). Stage `refined` sends trigger
`transcript.refined.segment` with input kind `refined.consolidated` (all
stored refined windows of the track, ordered by time).

Rules:

* The caller must be able to read the session (same rule as the recording
  detail), else `404 not-found`.
* The caller must be able to use the workflow: own personal definitions and
  published ones. Organization API keys use published definitions only. An
  unknown or unusable workflow is `404 not-found`.
* Only enabled workflows run (`409 workflow-disabled`). A disabled definition
  is not run by hand either, so turning a workflow off stops all new runs.
* The session must be `finished` (`409 session-not-finished`) and have a stored
  transcript for the chosen source (`409 transcript-not-available`).
* The encoded job must not be larger than `SAMURAIBFF_WORKFLOW_RUN_MAX_BYTES`
  (default `524288`), else `413 transcript-too-large`.

The BFF builds a `WorkflowDispatchJob` (schema version 1) from the definition
(prompt, provider type, model ID, parameters) and the stored segments. It
produces the job to `workflow.dispatch` (`SAMURAIBFF_KAFKA_TOPIC_WORKFLOW_DISPATCH`)
keyed by `session_id` and waits for the broker acknowledgement. Then it
answers:

```json
{"ok":true,"workflow_run_id":"…","workflow_id":"…","stage":"refined","track_id":"parakeet"}
```

A failed send returns `503 dispatch-unavailable`. Each accepted run writes the
access audit event `workflow.run.requested` (actor, session, workflow, run ID,
stage and track; no prompt or transcript text). Logs hold the same IDs only.

The job has no `stream` section. The runner result is stored as the latest
result of `(session_id, workflow_id)`, and the recording page shows it from
`workflow_results_latest`. A new run replaces the latest result of that
workflow for every reader of the session. The result of a personal workflow
is visible to every reader of the session; its prompt is not.

There is no rate limit. Every run calls the configured model.

The recording page has a Run workflow control in its Workflows panel. It lists
enabled workflows the person may use and the stored transcript sources. The
page refreshes until the result with the returned `workflow_run_id` arrives.
Results render as safe markdown: no raw HTML, images show as their alt text,
and only `http`, `https` and `mailto` links stay links.

### Results streaming

Data flow (high level):

* an external workflow integration produces results to Kafka topic
  `workflow.result` (JSON)
* every BFF instance consumes from `workflow.result`
* non-origin instances forward to origin instance via:
  * `POST /internal/workflow-result` (`application/json`)
* origin instance pushes a `/ws/events` JSON event:
  * `{"type":"workflow_result", ...}`

This path is full stack only. It streams results of refined-trigger runs to a
live session.
