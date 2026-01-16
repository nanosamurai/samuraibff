(ns samuraibff.ws.tenant
  "Tenant isolation helpers for WS/session registry.

  These helpers provide a single place to enforce the rule:

  *A session_id belongs to exactly one tenant, and other tenants must never be
  able to subscribe to events or send audio for that session.*

  We currently store `:tenant-id` in the in-memory ws registry session map.

  NOTE: This must be enforced **before WebSocket upgrade** (http-kit/as-channel)
  to avoid leaking any events.

  Public API:
  - `assert-tenant-match!`
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

(defn assert-tenant-match!
  "Ensure that the session's tenant matches the caller tenant.

  Rules:
  - If auth is required, tenant-id must be present.
  - If session does not exist:
      - when auth is required: deny (sessions must be created server-side)
      - when auth is not required: allow creation (tenant-id may be nil)
  - If session exists and has tenant-id:
      - caller tenant-id must match (string equality)
  - If session exists but tenant-id is nil:
      - if caller tenant-id is present, bind it (first authenticated use wins)
      - otherwise allow (dev mode)

  Inputs:
  - config: full config map
  - ws-registry: registry component
  - session-id: string
  - tenant-id: string or nil
  - opts: map of optional session creation options (e.g. :lang/:sample-rate)

  Returns:
  - session map (from registry)

  Throws:
  - ex-info with {:type :samuraibff.ws/tenant-mismatch} or {:type :samuraibff.ws/missing-tenant-id}"
  ([config ws-registry session-id tenant-id]
   (assert-tenant-match! config ws-registry session-id tenant-id {}))
  ([config ws-registry session-id tenant-id opts]
   (let [required? (oidc/auth-required? config)
         existing (ws.registry/get-session ws-registry session-id)]
     (when (and required? (nil? tenant-id))
       (throw (ex-info "Missing tenant-id" {:type :samuraibff.ws/missing-tenant-id
                                             :session-id session-id})))

     (cond
       (nil? existing)
       (if required?
         (throw (ex-info "Unknown session" {:type :samuraibff.ws/unknown-session
                                             :session-id session-id
                                             :tenant-id tenant-id}))
         ;; auth not required, allow creation via WS
         (ws.registry/ensure-session! ws-registry session-id (assoc opts :tenant-id tenant-id)))

       (and (:tenant-id existing) (not= (:tenant-id existing) tenant-id))
       (throw (ex-info "Tenant mismatch" {:type :samuraibff.ws/tenant-mismatch
                                           :session-id session-id
                                           :session-tenant-id (:tenant-id existing)
                                           :tenant-id tenant-id}))

       (and (nil? (:tenant-id existing)) tenant-id)
       (do
         ;; Bind the existing session to this tenant.
         ;; We do a best-effort swap to avoid races.
         (swap! (:sessions ws-registry)
                (fn [m]
                  (if-let [s (get m session-id)]
                    (assoc m session-id (assoc s :tenant-id tenant-id))
                    m)))
         (log/info "Bound existing ws session to tenant" {:session-id session-id :tenant-id tenant-id})
         (ws.registry/get-session ws-registry session-id))

       :else
       existing))))
