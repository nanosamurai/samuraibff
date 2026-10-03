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

The `@electron/get` override pins Electron's downloader to 5.1.0, including
the copy used by `electron-builder`. Its native-fetch downloader removes the
`got` / `cacheable-request` / `http-cache-semantics` chain affected by
[GHSA-ch52-4w7c-c8xp](https://github.com/advisories/GHSA-ch52-4w7c-c8xp).
It uses the project's existing Node >=22.12 requirement. Keep the override
until the builder's dependency range also selects a safe downloader.
For proxy downloads, set `ELECTRON_GET_USE_PROXY=true` with `HTTP_PROXY`,
`HTTPS_PROXY`, and `NO_PROXY` as needed; legacy `GLOBAL_AGENT_*` variables and
Got-specific download options are no longer supported by the downloader.

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
