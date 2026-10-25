# SamuraiBFF CI and image publication

## Pull request validation

Pull requests targeting `master`, `implement-lean-final-tracks` or
`implement-lean-refinement-tracks` run the same checks, including while the
lean track spikes are stacked for review:

- `Clojure tests (PR gate)` via the lightweight `clojure -X:ci` plan
- `UI build (shadow-cljs release)`
- `Full backend test suite (Testcontainers)` via `clojure -X:test`
- `scan`, which runs the pinned open-source Gitleaks CLI against the complete
  repository history without injecting a repository secret

The Docker build uses `npm ci` with the committed lockfile, matching the UI
dependency graph checked by CI.

The UI job runs `npm audit --audit-level=high` against the locked dependencies
before compilation. When addressing advisories, update the affected direct
dependency pins and compatible transitive versions in `package-lock.json`,
including nested copies. Validate with `npm ci`, `npm audit`, `npm test`, and
`npm run ui:release`; keep the audit gate enabled.

The repository ruleset for `master` must require all four checks before merge,
require the branch to be up to date, and prevent routine bypass. Workflow
triggers make checks run; the repository ruleset makes them merge
prerequisites.

Repository secrets are not available to workflows triggered from forks. Never
use `pull_request_target` to execute untrusted pull request code with secrets.

## Image publication

On pushes to `master`, `publish-image.yml` reuses the application, integration,
and Gitleaks workflows to validate the exact merged commit. The Docker build,
GHCR login, and push run only after every gate succeeds. A failed, cancelled,
or misconfigured gate therefore publishes no image.

The successful image is published as:

```text
ghcr.io/nanosamurai/samuraibff:sha-<git-sha>
```

Validation jobs have read-only permissions. The publication job receives
`packages: write` and the GitHub attestation permissions; the Gitleaks gate does
not require `GITLEAKS_LICENSE`.

### Image SBOM

The published image includes an SPDX JSON software bill of materials as a
GHCR-attached OCI attestation. The workflow validates the SBOM after the push
and also uploads it as a workflow artifact named
`sbom-samuraibff-<git-sha>`. Public repositories additionally publish a signed
GitHub artifact attestation; that signing step is skipped while the repository
is private.

Retrieve the canonical SBOM attached to an image with:

```bash
docker buildx imagetools inspect \
  ghcr.io/nanosamurai/samuraibff:sha-<git-sha> \
  --format "{{ json .SBOM.SPDX }}" > samuraibff.spdx.json
```

Deployment orchestration and environment-specific configuration remain owned
by the deployment repository.

### Runtime dependency maintenance

The runtime uses a digest-pinned Temurin Java 21 image on Ubuntu 24.04 LTS.
While the corresponding container release is pending, the Dockerfile replaces
its JRE with the official Temurin 21.0.13+9 archive and verifies the architecture's
pinned SHA-256 before extraction. Preserve the base's CA certificates, native
libraries, timezones and UID 10001 when updating it. Verify `java --version` in
the final image; the base tag and an OS package scan do not establish the JRE
patch level. The Ubuntu LTS variant does not include Pebble.

The bundled default Java truststore follows Oracle's
[eMudhra root-removal guidance](https://ops.java/emudhra). The build verifies
the remaining emSign Root CA G1 fingerprint before deleting that exact alias,
then checks that all four roots named by the guidance are absent. TLS peers
chaining only to these removed roots will be rejected. Application-provided
truststores, including `javax.net.ssl.trustStore` overrides, need separate review.

Jackson 2.x modules in `deps.edn` follow one BOM version; annotations use the
BOM's minor-only version. Keep the gRPC runtime and Buf gRPC generator aligned.
The gRPC client uses `grpc-netty` with its three Netty dependency roots pinned
to one patch release; verify every resolved Netty module has that same version.
This allows Netty security patches to be applied independently of gRPC's shaded
bundle. The current client uses plaintext connections within its configured
service network. If TLS is introduced, the unshaded transport uses Java 21's
built-in TLS/ALPN provider unless an explicitly managed native provider is added.
The application uses http-kit, Nimbus and JDK HttpClient for authentication,
and synchronous AWS clients. Jetty, Keycloak's Java client and the AWS async
Netty transport are not runtime dependencies; Cheshire is an explicit dependency.
AWS SDK modules also stay on one version because their shaded Jackson copy is
independent of the application Jackson pins. Kafka uses the maintained
`at.yawk.lz4` library; exclude the old `org.lz4` coordinate when updating Kafka.
Keep the Log4j API, implementation and bridges on the same patch version.

After dependency changes, run the full backend suite, UI build, UI and Electron
tests, and npm audit. Scan the final image with a current database and inspect
Netty Maven metadata and AWS's shaded Jackson version in the packaged jar:
flattened uberjars can hide shaded libraries from package scanners. Preserve
gRPC service-loader metadata and verify streaming, deadlines and round-robin
routing against the assembled jar as well as the dependency classpath.
The build adds missing gRPC Maven metadata from the same resolved basis used
to assemble the jar, and requires each known runtime class to be present.
Existing matching metadata is preserved; conflicting versions fail the build.
`clojure -M:build-test` verifies these evidence checks and runs in PR CI.
