# SamuraiBFF CI and image publication

## Pull request validation

Pull requests targeting `master` run:

- `Clojure tests (PR gate)` via the lightweight `clojure -X:ci` plan
- `UI build (shadow-cljs release)`
- `Full backend test suite (Testcontainers)` via `clojure -X:test`
- `scan`, which runs the pinned open-source Gitleaks CLI against the complete
  repository history without injecting a repository secret

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

Validation jobs have read-only permissions. Only the publication job receives
`packages: write`; the Gitleaks gate does not require
`GITLEAKS_LICENSE`.

Deployment orchestration and environment-specific configuration remain owned
by the deployment repository.
