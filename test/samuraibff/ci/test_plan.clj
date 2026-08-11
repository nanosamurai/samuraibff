;; Copyright (c) samuraibff contributors.
(ns samuraibff.ci.test-plan
  "CI test selection helpers.

  This namespace exists so GitHub Actions PR CI can run a deterministic,
  lightweight subset of the test suite without requiring Docker.

  Public API:
  - `unit-nses` (var)
  - `run`      (exec-fn for `clojure -X:ci`)"
  (:require
    [cognitect.test-runner.api :as tr]))

(def unit-nses
  "List of test namespaces to run in PR-gate CI.

  Constraints:
  - Must NOT use Testcontainers / Docker.
  - Must NOT require external services (Keycloak, rtservice, Kafka, Postgres).

  Note:
  Some namespaces are named *integration-test* but are still lightweight
  (bind local ephemeral ports, use mocks, and skip when dependencies are not
  reachable). Those are acceptable here as long as they are Docker-free." 
  '[samuraibff.auth.oidc-audience-test
    samuraibff.auth.oidc-jwks-fetch-test
    samuraibff.auth.oidc-test
    samuraibff.http.auth-test
    samuraibff.http.readiness-integration-test
    samuraibff.http.server-integration-test
    samuraibff.http.server-test
    samuraibff.ui.routing-test
    samuraibff.ui.transcript-test
    samuraibff.ws.registry-test
    samuraibff.ws.ws-auth-required-integration-test
    samuraibff.ws.ws-tenant-isolation-integration-test])

(defn run
  "Run the lightweight CI test plan.

  Inputs:
  - opts: map (ignored; exec-fn signature)

  Behavior:
  - Executes `unit-nses` via cognitect.test-runner.
  - Returns the test runner result map (and sets process exit code
    appropriately when used via `clojure -X`)." 
  [_opts]
  (tr/test {:nses unit-nses}))
