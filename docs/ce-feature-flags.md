# Community Edition feature flags

Status: implemented in samuraibff

Last updated: 2026-07-10

## Decision

Community Edition should keep workflow/webhook contracts visible in the public
repo, but should not present workflow/webhook runtime features as part of the
default OSS experience.

Use a single edition flag first:

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `SAMURAIBFF_CE_MODE` | `true` | When true, hide/disable workflow and webhook runtime features. |

This keeps OSS easy to run: the `nanosamurai` Compose stack can omit the flag
and get CE behavior. Commercial/full deployments managed by `nanodeploy` should
set `SAMURAIBFF_CE_MODE=false`.

If we later need finer control, add positive enablement flags such as
`SAMURAIBFF_WORKFLOWS_ENABLED` and `SAMURAIBFF_WEBHOOKS_ENABLED`, defaulting to
false when CE mode is true.

## What CE mode should affect

- UI:
  - hide or disable Webhooks and Workflows navigation/pages
  - hide session creation controls for `webhook_overrides`,
    `workflow_overrides`, and workflow-driven rolling-tail options
  - avoid showing empty workflow/webhook panels on recording detail unless they
    are clearly disabled
- API:
  - disable `/api/webhooks` and `/api/workflows` routes, including defaults
  - return a clear "not enabled" response for disabled commercial routes
  - reject workflow/webhook override fields in `POST /api/sessions` instead of
    silently accepting settings that CE will not use
- Kafka/session metadata:
  - do not resolve webhook routing snapshots in CE
  - do not resolve workflow target snapshots in CE
  - do not publish workflow/webhook routing data into `sessions.meta`
  - do not force refined transcript consolidation only because workflow targets
    would have required it; keep any pure-STT consolidation behavior separate
- Runtime consumers:
  - do not start the workflow result consumer in CE
  - do not forward `/internal/workflow-result` payloads in CE
- Documentation:
  - keep contract docs public, but mark workflow/webhook runtime behavior as
    disabled in CE

## Implementation plan

1. Add config parsing for `SAMURAIBFF_CE_MODE`, defaulting to true.
2. Add one small helper for commercial feature availability, so UI/API/session
   code does not duplicate flag logic.
3. Pass the feature state to the UI bootstrap payload and hide disabled pages
   and controls client-side.
4. Gate BFF routes server-side; UI hiding is convenience, not enforcement.
5. Gate session creation so CE does not accept workflow/webhook overrides or
   publish non-empty workflow/webhook metadata to `sessions.meta`.
6. Make the workflow result consumer a no-op, or omit its Integrant key, when CE
   mode is true.
7. Add focused tests for default CE behavior and `SAMURAIBFF_CE_MODE=false`
   compatibility.
8. After code lands, update `nanosamurai` docs to rely on the default CE mode
   and update `nanodeploy` k8s/Compose values to set
   `SAMURAIBFF_CE_MODE=false`.

## samuraibff implementation notes

- `SAMURAIBFF_CE_MODE` is parsed into `[:features :ce-mode?]` and defaults to
  true.
- `/api/me` returns a JSON-friendly `features` map for UI gating.
- In CE mode, `/api/webhooks`, `/api/workflows`, their defaults routes,
  workflow result callbacks/consumer behavior, and session
  workflow/webhook overrides return a clear `feature-not-enabled` response.
- In CE mode, `sessions.meta` still publishes core session/refined transcript
  settings but omits workflow/webhook routing fields.

## Security note

This flag is primarily for product boundary, UX clarity, and dependency
simplification. It is not meant to hide a secret protocol. Contracts can remain
visible; the important behavior is that CE does not run workflow/webhook flows
by default.
