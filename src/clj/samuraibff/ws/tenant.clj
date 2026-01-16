(ns samuraibff.ws.tenant
  "Tenant isolation helpers for WS/session registry.

  These helpers provide a single place to enforce the rule:

  *A session_id belongs to exactly one tenant, and other tenants must never be
  able to subscribe to events or send audio for that session.*

  The ws registry stores sessions per tenant, so cross-tenant access is avoided
  by construction.

  NOTE: This must be enforced **before WebSocket upgrade** (http-kit/as-channel)
  to avoid leaking any events.

  Public API:
  - `assert-session-access!`
  - `forbidden-response`"
  (:require
    [jsonista.core :as json]
    [org.corfield.logging4j2 :as log]
    [samuraibff.auth.oidc :as oidc]
    [samuraibff.ws.registry :as ws.registry]))

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name}))

(defn forbidden-response
  "Create a standard 403 JSON response.

  Inputs:
  - message: string keyword-ish

  Returns: Ring response map." 
  [message]
  {:status 403
   :headers {"content-type" "application/json"}
   :body (json/write-value-as-string {:ok false :message (str message)} json-mapper)})

(defn assert-session-access!
  "Ensure the caller can access a session within their tenant.

  Rules (Option A):
  - If auth is required, tenant-id must be present.
  - If session does not exist within the tenant bucket:
      - when auth is required: deny (sessions must be created server-side)
      - when auth is not required: allow creation (tenant-id may be nil)

  Inputs:
  - config: full config map
  - ws-registry: registry component
  - tenant-id: string or nil
  - session-id: string
  - opts: map of optional session creation options (e.g. :lang/:sample-rate)

  Returns:
  - session map (from registry)

  Throws:
  - ex-info with {:type :samuraibff.ws/missing-tenant-id} or {:type :samuraibff.ws/unknown-session}" 
  ([config ws-registry tenant-id session-id]
   (assert-session-access! config ws-registry tenant-id session-id {}))
  ([config ws-registry tenant-id session-id opts]
   (let [required? (oidc/auth-required? config)
         existing (ws.registry/get-session ws-registry tenant-id session-id)]
     (when (and required? (nil? tenant-id))
       (throw (ex-info "Missing tenant-id" {:type :samuraibff.ws/missing-tenant-id
                                             :session-id session-id})))

     (cond
       existing
       (do
         ;; Sessions are typically created first via POST /api/sessions without
         ;; lang/sample-rate, and then the browser connects /ws/audio with these
         ;; controls as query params.
         (when (seq opts)
           (ws.registry/update-session-controls! ws-registry tenant-id session-id opts))
         (ws.registry/get-session ws-registry tenant-id session-id))

       required?
       (throw (ex-info "Unknown session" {:type :samuraibff.ws/unknown-session
                                           :session-id session-id
                                           :tenant-id tenant-id}))

       :else
       (ws.registry/ensure-session! ws-registry tenant-id session-id opts)))))
