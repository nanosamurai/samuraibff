# Ops + observability

This repo provides basic observability primitives (logs/metrics/tracing).

## Health probes

* `GET /health` — liveness
* `GET /ready` — readiness (dependency reachability)

### Readiness semantics

Readiness returns **200** only when critical dependencies are reachable.

Current checks:

* Postgres (`select 1`)
* Kafka (TCP reachability to at least one bootstrap host:port)
* rtservice (TCP reachability to `:grpc :rtservice-addr`)

If any dependency is down, `/ready` returns **503** and includes per-dependency flags:

```json
{
  "status": "degraded",
  "db": {"up?": false},
  "kafka": {"up?": false},
  "grpc": {"up?": false}
}
```

Startup is graceful under dependency outages:

* If Postgres is down at boot, the process still starts and serves HTTP/WS.
* If Kafka is down at boot, the process still starts; producer is disabled and consumer retries in the background.

## Metrics

* `GET /internal/metrics` — Prometheus scrape endpoint

Important:

* Intended for **in-cluster scraping only**.
* Should not be exposed publicly.

## Tracing

OpenTelemetry integration is supported. For cluster-level setup see nanodeploy.

## Logs ↔ traces correlation

SamuraiBFF writes structured logs using log4j2 and includes correlation fields in MDC.

Key fields typically include:

* `trace_id`
* `span_id`
* `session_id`
* `tenant_id`
* `user_id`
* `http_route`

