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

The UI job audits the locked dependencies before compilation. Its lockfile
uses `fast-uri` 3.1.7 and `@xmldom/xmldom` 0.8.15 to clear the dependency
advisories encountered while validating the realtime routing PR; the audit
remains enabled.

The 2026-09-09 lockfile refresh also resolves `joi` to 18.2.8 and `js-yaml`
to 4.3.2 for the newly reported
[Joi custom-message](https://github.com/advisories/GHSA-6w3j-5fw6-r9vr),
[Joi rename](https://github.com/advisories/GHSA-gg4h-3hg2-grpc), and
[YAML merge CPU-use](https://github.com/advisories/GHSA-2883-xcg3-v3hh)
advisories. These are compatible transitive build-tool updates; direct
dependency versions and the audit threshold are unchanged.

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
