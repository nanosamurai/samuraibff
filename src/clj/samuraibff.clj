(ns samuraibff
  "Namespace loader for Integrant.

  Integrant's `ig/load-namespaces` loads namespaces based on the *namespace part*
  of Integrant keys.

  This project uses keys like `:samuraibff/http-server`, `:samuraibff/router`,
  etc. That means `ig/load-namespaces` will attempt to `require` the namespace
  `samuraibff`.

  Therefore this namespace acts as a small aggregation point that requires all
  namespaces that contain `ig/init-key` / `ig/halt-key!` methods.

  No public API is provided here; requiring the namespace is enough." 
  (:require
    ;; Integrant components
    [samuraibff.config]
    [samuraibff.db.core]
    [samuraibff.grpc.client]
    [samuraibff.http.router]
    [samuraibff.http.server]
    [samuraibff.http.ui]
    [samuraibff.http.auth]
    [samuraibff.http.api-credentials]
    [samuraibff.http.internal]
    [samuraibff.keycloak.admin]
    [samuraibff.kafka.producer]
    [samuraibff.kafka.refined-consumer]
     [samuraibff.kafka.workflow-results-consumer]
    [samuraibff.secrets.component]
    [samuraibff.ws.auth]
    [samuraibff.ws.audio]
    [samuraibff.ws.events]
    [samuraibff.ws.registry]))
