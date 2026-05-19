# Documentation policy (authoring rules)

This repository uses a **split documentation model**.

## Goals

* Keep `README.MD` short and stable.
* Avoid multiple sources of truth.
* Make it obvious **where** new information belongs.

## Canonical sources

### `README.MD`

`README.MD` is an **index + 5-minute quickstart**:

* what this repo is
* high-level architecture
* local dev quickstart (how to start backend + UI)
* links to canonical docs under `docs/`

`README.MD` is **not** where feature semantics, API contracts, or ops details live.

### `docs/` (canonical documentation)

All detailed documentation must live under `docs/`.

When adding a new feature, you must:

1) put the doc in an existing canonical document (preferred), or
2) create a new dedicated doc (if the topic would make an existing doc too large).

## Where to put what

* **Developer workflows** (run, tests, proto generation, nREPL): `docs/dev-runbook.md`
* **Public API + OpenAPI/Swagger**: `docs/api.md`
* **WebSocket contracts + event shapes**: `docs/ws-contract.md`
* **Security/auth and tenant isolation**: `docs/security-auth.md`
* **Ops/observability** (health/ready/metrics/tracing/log correlation): `docs/ops-observability.md`
* **Storage** (Postgres responsibilities, S3 layout/config, LocalStack tests): `docs/storage.md`

Feature docs (deep semantics):

* transcripts: `docs/features-transcripts.md`
* recordings/playback/karaoke: `docs/features-recordings-playback-karaoke.md`
* webhooks/workflows: `docs/features-webhooks-workflows.md`

## Archive

`docs/readme-archive.md` is intentionally kept as a historical long-form reference.

Rules:

* It may contain duplicate information.
* New behavior must be documented in canonical docs under `docs/`, not only in the archive.
* When migrating content out of the archive, prefer copying the relevant parts and then adding links.
