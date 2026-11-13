# Feature flags

Status: implemented in samuraibff

Last updated: 2026-11-13

## Decision

Workflow and webhook contracts stay visible in the public repo. Each optional
capability has its own flag, so a deployment turns on what it runs and the
flags describe the stack instead of an edition.

| Environment variable | Default | Enables |
| --- | --- | --- |
| `SAMURAIBFF_WORKFLOWS_ENABLED` | `false` | Workflow definitions, on-demand runs on finished sessions, saved workflow results. |
| `SAMURAIBFF_WORKFLOW_TRIGGERS_ENABLED` | `false` | Automatic event-triggered workflows: session workflow selection, workflow targets in `sessions.meta`, `/internal/workflow-result`, the `workflow.result` consumer and live results on `/ws/events`. Also opens workflow definitions. |
| `SAMURAIBFF_WEBHOOKS_ENABLED` | `false` | Webhook definitions and defaults, session webhook selection, webhook routing in `sessions.meta`, webhook delivery outcomes. |
| `SAMURAIBFF_CE_MODE` | `true` | Deprecated. When `false`, every capability flag above that is not set defaults to `true`. |

Values `true`/`1`/`yes`/`y` turn a flag on and `false`/`0`/`no`/`n` turn it
off. Any other value counts as not set.

An explicit capability flag always wins over `SAMURAIBFF_CE_MODE`. For
example, `SAMURAIBFF_CE_MODE=false` with `SAMURAIBFF_WEBHOOKS_ENABLED=false`
turns on workflows and workflow triggers but not webhooks.

Turn on only what the stack runs:

* `SAMURAIBFF_WORKFLOWS_ENABLED` needs a workflow runner that consumes
  `workflow.dispatch`, and a persistor that stores workflow results
  (`SP_WORKFLOW_RESULT_ENABLED=true`).
* `SAMURAIBFF_WORKFLOW_TRIGGERS_ENABLED` needs automatic event-triggered
  workflow routing that reads `sessions.meta`, and the same runner.
* `SAMURAIBFF_WEBHOOKS_ENABLED` needs a webhook delivery service that reads
  `sessions.meta`.

See [Webhooks + workflows](features-webhooks-workflows.md#capability-flags).

## Upgrading from `SAMURAIBFF_CE_MODE`

* Nothing set: every capability stays off, as before.
* `SAMURAIBFF_CE_MODE=false`: every capability stays on, as before. Replace it
  with the three capability flags set to `true` when convenient; the switch
  will be removed in a later release.
* `SAMURAIBFF_WORKFLOWS_ENABLED=true` in Community Edition: unchanged.
* `SAMURAIBFF_CE_MODE=false` with `SAMURAIBFF_WORKFLOWS_ENABLED=false`: before,
  the BFF ignored the explicit `false`; now it turns off on-demand runs. The
  Workflows page stays because triggers still need definitions.

## What each flag gates

- UI (convenience, not enforcement):
  - Webhooks page, navigation item, Session settings tab, live Webhooks tab
    and webhook dispatches on the recording page: `webhooks_enabled`.
  - Session settings Workflows tab and live Workflows tab:
    `workflow_triggers_enabled`.
  - Workflows page, navigation item and saved results on the recording page:
    `workflows_enabled` or `workflow_triggers_enabled`. The run form:
    `workflows_enabled`.
  - "Results are sent to" lists webhooks and workflows for their own flag.
- API:
  - `/api/webhooks`, its defaults and
    `/api/sessions/{session_id}/webhook-delivery-outcomes`:
    webhooks.
  - `/api/workflows` and its defaults: workflows or workflow triggers.
  - `POST /api/recordings/{session_id}/workflows/{workflow_id}/runs`:
    workflows.
  - `POST /api/sessions` rejects `webhook_overrides` without webhooks and
    `workflow_overrides` without workflow triggers, instead of silently
    accepting settings the stack will not use.
  - A disabled route returns `403` with `{"message": "feature-not-enabled",
    "feature": "<webhooks|workflows|workflow-definitions|workflow-triggers>"}`.
- `sessions.meta`: core session and refined transcript settings are always
  published. `routing` and `webhook_routing` need webhooks;
  `workflows.targets` needs workflow triggers. Refined transcript
  consolidation is forced on only for workflow targets that are published.
- Runtime: the `workflow.result` consumer and `/internal/workflow-result`
  need workflow triggers. On-demand results reach the recording page through
  the persistor, not through this path.

## `/api/me` features

`/api/me` returns a `features` map for UI gating:

| Key | Meaning |
| --- | --- |
| `workflows_enabled` | `SAMURAIBFF_WORKFLOWS_ENABLED` resolved |
| `workflow_triggers_enabled` | `SAMURAIBFF_WORKFLOW_TRIGGERS_ENABLED` resolved |
| `webhooks_enabled` | `SAMURAIBFF_WEBHOOKS_ENABLED` resolved |
| `uploads_enabled`, `uploads_max_bytes` | File uploads, independent of these flags (see `docs/features-file-uploads.md`) |
| `ce_mode` | Deprecated: the `SAMURAIBFF_CE_MODE` value |
| `workflow_webhook_runtime_enabled` | Deprecated: `workflow_triggers_enabled` and `webhooks_enabled` |

## Implementation notes

- Flags are parsed into `[:features :workflows-enabled?]`,
  `[:features :workflow-triggers-enabled?]`, `[:features :webhooks-enabled?]`
  and the deprecated `[:features :ce-mode?]`. `resources/system.edn` leaves
  the capability keys out so that the deprecated switch still sets their
  default.
- `samuraibff.features` is the only place that resolves the flags; routes use
  `features/wrap-enabled` with `:workflows`, `:workflow-definitions`,
  `:workflow-triggers` or `:webhooks`.

## Security note

The flags set the product boundary and keep unused dependencies off. They do
not hide a secret protocol. Server-side gates enforce them; the UI only hides
what is off. Every capability is off unless set, so a missing or misspelled
value fails closed. Turning on a capability does not change access rules:
definitions and session use still follow the roles in
[Webhooks + workflows](features-webhooks-workflows.md#access-to-definitions).
