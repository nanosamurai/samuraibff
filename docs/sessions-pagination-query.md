# Sessions pagination query

`GET /api/recordings` reads its page, filtered total, and tenant-wide draft count
with one parameterized HoneySQL statement. `next.jdbc` executes the formatted
statement. The response contract and default limit of 200 remain unchanged.

## Query design

1. Aggregate both counts over the authenticated tenant's sessions.
2. Left join the requested session page, filtered before `LIMIT` / `OFFSET` and
   ordered by `created_at DESC, id DESC`.
3. Use lateral lookups for the latest recording and existence of a tenant-scoped
   final transcript, each limited to one row per selected session.
4. Preserve the aggregate row on empty pages, then remove its null session
   placeholder when constructing `items`.

This removes the separate count round trip and gives counts and items one
statement snapshot. Metadata cannot multiply session rows. Recording ties use
`created_at DESC, id DESC`; internal recording URLs are not selected.

`COUNT(*) OVER()` is a valid alternative, but still processes every matching
session before returning the total. An empty result, including an offset beyond
the last row, carries no window count. Also, a window evaluated after excluding
drafts cannot provide the tenant-wide draft count. The benchmark compares both
ways of preserving that count: windows before filtering, and a filtered count
window with a separate draft aggregate inside the same statement.

The aggregate-and-page statement was chosen for its measured performance and
its handling of empty pages without a fallback query. It still reads sessions
for both the aggregate and the page: one statement does not mean one table scan.

## Measurements

Measured on 2026-09-28 with disposable PostgreSQL 18.1 Alpine on local Docker,
the repository's test schema and existing indexes. Synthetic data contained four
equally sized tenants, 10% drafts, three recordings and two transcripts per
session. The large case contained 80,000 sessions, 240,000 recordings, and
160,000 transcripts. Each page contained 20 sessions.

Times below are median PostgreSQL **execution** milliseconds from seven
interleaved `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` runs after one warm-up per
variant. Original timings sum the separate count and page statements. They
exclude planning, network latency, and application/serialization time. Window
timings exclude the fallback required for an empty page. These are local
synthetic measurements, not production latency guarantees.

| Tenant sessions | Drafts shown | Offset | Original two statements | Windows before filtering | Filtered window + draft aggregate | Aggregate + page |
| ---: | :---: | ---: | ---: | ---: | ---: | ---: |
| 310 | No | 0 | 1.943 | 0.466 | 0.398 | 0.424 |
| 310 | No | 259 | 2.006 | 0.477 | 0.416 | 0.425 |
| 310 | Yes | 0 | 1.906 | 0.443 | 0.381 | 0.379 |
| 310 | Yes | 290 | 2.003 | 0.496 | 0.415 | 0.402 |
| 20,000 | No | 0 | 176.340 | 12.418 | 12.354 | 9.095 |
| 20,000 | No | 17,980 | 202.728 | 18.380 | 17.469 | 10.565 |
| 20,000 | Yes | 0 | 167.191 | 11.402 | 12.304 | 8.900 |
| 20,000 | Yes | 19,980 | 202.605 | 17.503 | 19.194 | 10.960 |

The original large first-page plan scanned all 240,000 recording rows to find
each session's latest recording, then joined and sorted before limiting. It also
scanned the transcript table to build the final-transcript existence lookup.
The revised plan used 20 indexed recording lookups (three rows each) and 20
indexed final-transcript lookups. Both window alternatives use those same
page-local lookups, so their comparison with the revised aggregate isolates
the counting strategy. Small-dataset differences are too small to justify a
general claim that aggregates always outperform windows.

To reproduce from the repository root in PowerShell, with Docker running:

```powershell
New-Item -ItemType Directory -Force target | Out-Null
git show 416bc54:src/clj/samuraibff/db/recordings.clj |
  Set-Content -Encoding utf8 target/pagination-original-recordings.clj
$env:TESTCONTAINERS_RYUK_DISABLED = 'true'
clojure -M:test utilities/pagination_benchmark.clj
```

The benchmark loads the pre-optimization implementation from commit `416bc54`
to capture its original statements, then reloads the current implementation.
Run it as a standalone process, not inside a running application's REPL.
It creates and stops its own container, binds its published port to `127.0.0.1`,
and never connects to the Compose database. Generated sample plans and summary
JSON are written under `target/pagination-plan-*` and
`target/pagination-benchmark-results.json`.

## Further tuning and limits

Exact counts still require inspecting the tenant's matching rows or index
entries; deep offsets still process skipped rows. The existing tenant index
does not provide the requested creation-time ordering.

An additional index was tested **only in the disposable benchmark database**:

```sql
CREATE INDEX benchmark_sessions_page
ON sessions (tenant_id, created_at DESC, id DESC) INCLUDE (status);
```

With drafts hidden and 20,000 tenant sessions, this reduced the revised query's
first-page median from 9.095 ms to 2.022 ms, and its last-page median from
10.565 ms to 9.488 ms. The first page benefits from ordered access and an
index-only count after vacuum. Benefits depend on data distribution, table
visibility, and write activity. The index adds storage and write cost and does
not eliminate counting or deep-offset work.

No migration is included here. Deployment schema changes are owned by
Nanosamurai/Nanodeploy and need a migration there with a suitable rollout plan.
The index is a measured follow-up, not a prerequisite for this BFF change.
Keyset pagination is another option for very large datasets, but it changes the
navigation contract and does not directly support this UI's arbitrary last-page
jump with an exact page count.

## Correctness and security checks

The PostgreSQL integration test covers all UI page sizes, tied timestamps,
draft filtering, tenant-scoped counts, empty tenants, draft-only tenants,
zero-limit and out-of-range pages, deletion of the final row, multiple recordings
and transcripts, deterministic latest-recording selection, and rejection of a
different tenant's transcript flag. It asserts one SQL execution for every page,
including empty pages, and verifies that private recording URLs are absent.

Runtime values remain bound parameters; tenant IDs come from authenticated
request context. This change adds no configuration, dependencies, or schema
requirements. HTTP response fields remain compatible. The internal DB function
now returns `{:items ... :total ... :drafts_count ...}` instead of a row vector;
its sole production caller was updated with it.

PostgreSQL references: [aggregate count cost](https://www.postgresql.org/docs/18/functions-aggregate.html)
and [window evaluation and filtering](https://www.postgresql.org/docs/18/tutorial-window.html).
