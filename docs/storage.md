# Storage (Postgres + S3)

This document describes storage responsibilities that live in (or are directly
used by) SamuraiBFF.

## Postgres persistence (sessions)

Not all persistence is handled by **samuraipersistor**. SamuraiBFF still handles
selected persistence directly, including session metadata.

Key behaviors:

* `POST /api/sessions` generates a session id server-side and inserts a row into `sessions`.
* `sessions` are tenant-scoped.
* Keycloak user mapping: `app_users.external_id` should contain Keycloak `sub`.

### Unauthenticated / dev fallback

If `:auth {:required? false}` the DB schema still requires `sessions.tenant_id NOT NULL`.

The server uses a fallback tenant id:

* `00000000-0000-0000-0000-000000000000`

Optionally, if you want `sessions.user_id` to resolve in unauthenticated mode,
add a guest user:

* `app_users.external_id = "guest"` within that tenant.

### DB config

See `resources/system.edn`.

Example snippet:

```edn
:db {:jdbc-url "jdbc:postgresql://localhost:5432/nanosamurai"
     :username "drsynth"
     :password "drsynth"
     :maximum-pool-size 10}
```

## S3 / object storage

SamuraiBFF stores and/or serves some artifacts from S3-compatible object storage.

### Enrolled speakers (layout)

```
s3://<enrollments-bucket>/<enrollment-prefix>/<tenant_id>/speakers/<speaker_id>/
  speaker.json
  samples/<sample_id>.wav
```

The `speaker.json` is a per-speaker manifest containing the label and sample URL.

### Configuration

Configure S3 in `resources/system.edn` (or via env overrides).

```edn
:s3 {:region ""
     :endpoint ""          ;; localstack/minio/ceph
     :access-key ""        ;; optional; when blank, AWS default credentials chain is used (IAM/IRSA)
     :secret-key ""        ;; optional
     :force-path-style? true
     :buckets {:enrollments {:bucket "xamurai-enrollment"
                             :prefix "enrollment"}
               :recordings  {:bucket "xamurai-recordings"
                             :prefix "recordings"}}}
```

Environment variables:

* `SAMURAIBFF_S3_REGION`
* `SAMURAIBFF_S3_ENDPOINT`
* `SAMURAIBFF_S3_ACCESS_KEY`
* `SAMURAIBFF_S3_SECRET_KEY`
* `SAMURAIBFF_S3_FORCE_PATH_STYLE`
* `SAMURAIBFF_S3_ENROLLMENTS_BUCKET`
* `SAMURAIBFF_S3_ENROLLMENTS_PREFIX`
* `SAMURAIBFF_S3_RECORDINGS_BUCKET`
* `SAMURAIBFF_S3_RECORDINGS_PREFIX`

### Tests (LocalStack)

Integration tests use LocalStack S3 via Testcontainers. Run:

```bash
clojure -X:test :nses '[samuraibff.http.speakers-integration-test]'
```
