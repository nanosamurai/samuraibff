# Ops + observability

This repo provides basic observability primitives (logs/metrics/tracing).

This document is a short index; details may still live in
`docs/readme-archive.md` until further migrated.

## Health probes

* `GET /health` — liveness
* `GET /ready` — readiness (dependency reachability)

## Metrics

* `GET /internal/metrics` — Prometheus scrape endpoint

Important:

* Intended for **in-cluster scraping only**.
* Should not be exposed publicly.

## Tracing

OpenTelemetry integration is supported. For cluster-level setup see nanodeploy.
