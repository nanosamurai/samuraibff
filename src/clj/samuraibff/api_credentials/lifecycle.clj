(ns samuraibff.api-credentials.lifecycle
  "Create, rotate and revoke machine credentials with their service principals.

  A credential is a confidential Keycloak client plus two Postgres rows: the
  `api_credentials` inventory row and a `service` principal in `app_users`
  bound to the client's service-account subject and clientId. Principal
  resolution reads the principal from Postgres on every request, so:
  - a token of a client without a registered principal is `not-provisioned`;
  - suspending the principal stops every token of the client on the next
    request, before Keycloak is updated.

  Rules:
  - Create registers the principal before the secret is returned. When
    registration fails, the Keycloak client is deleted (or at least disabled)
    and an error is returned, so no usable secret exists without a principal.
  - Revoke first sets `revoked_at` and suspends the service principal in one
    transaction, then disables the Keycloak client. When the upstream call
    fails, `identity_sync_state` becomes `disable_pending` and the result says
    `pending`. Repeating the revoke retries the upstream call.
  - Rotation keeps the principal active. Revoked credentials cannot rotate.
  - Nothing reactivates a suspended service principal.

  Audit events never contain secrets.

  Public API:
  - `register-credential!`, `create-credential!`, `rotate-credential!`,
    `revoke-credential!`, `revoke-managed-credentials!`"
  (:require
   [clojure.string :as str]
   [next.jdbc :as jdbc]
   [org.corfield.logging4j2 :as log]
   [samuraibff.access.policy :as policy]
   [samuraibff.db.api-credentials :as db.api-creds]
   [samuraibff.db.audit :as db.audit]
   [samuraibff.db.principals :as db.principals]
   [samuraibff.keycloak.admin :as kc.admin]
   [samuraibff.observability.diagnostics :as diagnostics]
   [samuraibff.util.uuid :as util.uuid])
  (:import
   (java.util UUID)))

(defn- audit!
  "Append a credential audit event. actor is a principal map or nil (system)."
  [connectable tenant-id actor action credential-id outcome details]
  (db.audit/record! connectable {:tenant-id tenant-id
                                 :actor-id (:id actor)
                                 :actor-kind (if actor "user" "system")
                                 :action action
                                 :target-type "api_credential"
                                 :target-id credential-id
                                 :outcome outcome
                                 :details details}))

(defn register-credential!
  "Insert the service principal, the credential row and the create audit event
  in one transaction.

  Inputs: DataSource and
  {:tenant-id uuid :credential-id uuid :name string :client-id string
   :service-account-id string :issuer string :manager principal-map
   :created-by-sub string?}
  Returns: {:credential-id uuid :service-user-id uuid}. Throws on DB failure."
  [ds {:keys [tenant-id credential-id name client-id service-account-id issuer manager created-by-sub]}]
  (jdbc/with-transaction [tx ds]
    (let [service-user-id (util.uuid/uuid7)]
      (db.principals/insert-principal! tx {:id service-user-id
                                           :tenant-id tenant-id
                                           :external-id service-account-id
                                           :issuer issuer
                                           :principal-type "service"
                                           :role nil
                                           :status "active"
                                           :client-id client-id
                                           :name name})
      (db.api-creds/insert-credential! tx {:id credential-id
                                           :tenant-id tenant-id
                                           :name name
                                           :keycloak-client-id client-id
                                           :created-by-sub created-by-sub
                                           :manager-user-id (:id manager)
                                           :service-user-id service-user-id})
      (audit! tx tenant-id manager "credential.create" credential-id "succeeded"
              {:name name :service_user_id (str service-user-id)})
      {:credential-id credential-id :service-user-id service-user-id})))

(defn- compensate-client!
  "Remove a Keycloak client whose principal could not be registered.

  Deletes the client; when that fails, disables it. Never throws."
  [keycloak-admin client-id tenant-id]
  (try
    (kc.admin/delete-client! keycloak-admin {:client-id client-id})
    (log/info "Deleted unregistered API credential client" {:tenant_id (str tenant-id)})
    (catch Exception delete-failure
      (log/warn "Failed deleting unregistered API credential client"
                (diagnostics/failure-details delete-failure))
      (try
        (kc.admin/disable-client! keycloak-admin {:client-id client-id})
        (log/info "Disabled unregistered API credential client" {:tenant_id (str tenant-id)})
        (catch Exception disable-failure
          (log/error "Unregistered API credential client needs manual removal"
                     (assoc (diagnostics/failure-details disable-failure)
                            :tenant_id (str tenant-id)
                            :keycloak_client_id client-id)))))))

(defn create-credential!
  "Create a Keycloak client, register its service principal and return the secret.

  Inputs: DataSource, config map (uses [:auth :issuer] and [:auth :audience]),
  KeycloakAdmin, manager principal map (Builder/Admin), {:name string
  :created-by-sub string?}.
  Returns: {:credential-id uuid :client-id string :client-secret string}.
  Throws:
  - ex-info {:type :samuraibff.api-credentials/issuer-missing} before any
    upstream call when the token issuer is not configured;
  - Keycloak admin ex-info from client creation (nothing to compensate);
  - ex-info {:type :samuraibff.api-credentials/provisioning-failed} after the
    client was created but the principal could not be registered (the client
    is deleted or disabled first)."
  [ds config keycloak-admin manager {:keys [name created-by-sub]}]
  (let [tenant-id (:tenant-id manager)
        issuer (some-> (get-in config [:auth :issuer]) str str/trim not-empty)
        _ (when-not issuer
            (throw (ex-info "Token issuer not configured" {:type :samuraibff.api-credentials/issuer-missing})))
        _ (log/info "Creating API credential" {:tenant_id (str tenant-id)
                                               :principal_id (str (:id manager))})
        {:keys [client-id client-secret]} (kc.admin/create-m2m-client!
                                           keycloak-admin
                                           {:tenant-id (str tenant-id)
                                            :name name
                                            :audience-client-id (get-in config [:auth :audience])})
        credential-id (util.uuid/uuid7)]
    (try
      (let [service-account-id (kc.admin/service-account-user-id keycloak-admin {:client-id client-id})]
        (when-not (and (string? service-account-id) (not (str/blank? service-account-id)))
          (throw (ex-info "Missing service account id" {:type :samuraibff.api-credentials/missing-service-account})))
        (register-credential! ds {:tenant-id tenant-id
                                  :credential-id credential-id
                                  :name name
                                  :client-id client-id
                                  :service-account-id service-account-id
                                  :issuer issuer
                                  :manager manager
                                  :created-by-sub created-by-sub}))
      (catch Exception failure
        (log/error "Failed registering API credential principal" (diagnostics/failure-details failure))
        (compensate-client! keycloak-admin client-id tenant-id)
        (throw (ex-info "Credential provisioning failed"
                        {:type :samuraibff.api-credentials/provisioning-failed}))))
    (log/info "Created API credential" {:tenant_id (str tenant-id)
                                        :credential_id (str credential-id)})
    {:credential-id credential-id :client-id client-id :client-secret client-secret}))

(defn- manageable-row!
  "Return the credential row when the principal may manage it, else throw not-found."
  [principal row]
  (if (and row (policy/can-manage-credential? principal row))
    row
    (throw (ex-info "Not found" {:type :samuraibff.http/not-found}))))

(defn rotate-credential!
  "Rotate the client secret of a manageable, non-revoked credential.

  Inputs: DataSource, KeycloakAdmin, principal map, credential UUID.
  Returns: {:client-id string :client-secret string}.
  Throws :samuraibff.http/not-found (absent or not manageable),
  :samuraibff.api-credentials/revoked, or Keycloak admin ex-info."
  [ds keycloak-admin principal ^UUID credential-id]
  (let [tenant-id (:tenant-id principal)
        row (manageable-row! principal (db.api-creds/find-credential ds tenant-id credential-id))
        _ (when (:revoked_at row)
            (throw (ex-info "Credential revoked" {:type :samuraibff.api-credentials/revoked})))
        {:keys [client-secret]} (kc.admin/rotate-client-secret! keycloak-admin {:client-id (:keycloak_client_id row)})]
    (try
      (audit! ds tenant-id principal "credential.rotate" credential-id "succeeded" {:name (:name row)})
      (catch Exception failure
        (log/error "Failed recording credential rotation audit" (diagnostics/failure-details failure))))
    (log/info "Rotated API credential" {:tenant_id (str tenant-id) :credential_id (str credential-id)})
    {:client-id (:keycloak_client_id row) :client-secret client-secret}))

(defn- revoke-locally!
  "Within tx: set revoked_at (when not set) and suspend the service principal.

  Returns true when this call revoked the credential."
  [tx actor row reason]
  (let [tenant-id (:tenant_id row)
        {:keys [updated?]} (db.api-creds/revoke-credential! tx tenant-id (:id row))]
    (when-let [service-user-id (:service_user_id row)]
      (db.principals/update-principal! tx tenant-id service-user-id {:status "suspended"}))
    (when updated?
      (audit! tx tenant-id actor "credential.revoke" (:id row) "succeeded"
              (cond-> {:name (:name row)} reason (assoc :reason reason))))
    updated?))

(defn- disable-upstream!
  "Disable the Keycloak client of a locally revoked credential.

  Returns \"disabled\" or \"pending\". Never throws: on failure (or without a
  Keycloak admin client) it sets identity_sync_state = disable_pending."
  [ds keycloak-admin actor row]
  (let [tenant-id (:tenant_id row)
        disabled? (when keycloak-admin
                    (try
                      (kc.admin/disable-client! keycloak-admin {:client-id (:keycloak_client_id row)})
                      true
                      (catch Exception failure
                        (log/warn "Failed disabling API credential client" (diagnostics/failure-details failure))
                        false)))]
    (try
      (if disabled?
        (db.api-creds/set-identity-sync-state! ds tenant-id (:id row) "synced")
        (do (db.api-creds/set-identity-sync-state! ds tenant-id (:id row) "disable_pending")
            (audit! ds tenant-id actor "credential.disable" (:id row) "pending" {:name (:name row)})))
      (catch Exception failure
        (log/error "Failed recording API credential sync state" (diagnostics/failure-details failure))))
    (log/info "API credential upstream disable" {:tenant_id (str tenant-id)
                                                 :credential_id (str (:id row))
                                                 :upstream (if disabled? "disabled" "pending")})
    (if disabled? "disabled" "pending")))

(defn revoke-credential!
  "Revoke one manageable credential: local revoke and suspension, then upstream.

  Inputs: DataSource, KeycloakAdmin or nil, principal map, credential UUID.
  Returns: {:upstream \"disabled\"|\"pending\"}. Repeating the call on a
  revoked credential re-checks the principal suspension and retries a pending
  upstream disable.
  Throws :samuraibff.http/not-found when absent or not manageable."
  [ds keycloak-admin principal ^UUID credential-id]
  (let [tenant-id (:tenant-id principal)
        row (jdbc/with-transaction [tx ds]
              (let [row (manageable-row! principal (db.api-creds/lock-credential tx tenant-id credential-id))]
                (revoke-locally! tx principal row nil)
                row))]
    (log/info "Revoked API credential locally" {:tenant_id (str tenant-id)
                                                :credential_id (str credential-id)})
    {:upstream (if (and (:revoked_at row) (= "synced" (:identity_sync_state row)))
                 "disabled"
                 (disable-upstream! ds keycloak-admin principal row))}))

(defn revoke-managed-credentials!
  "Revoke every non-revoked credential managed by one user.

  Call after a Builder loses credential management (demotion to Member or
  suspension), after that change is committed. Local revocation and
  service-principal suspension for all credentials happen in one transaction;
  upstream disables follow, best effort, with `disable_pending` on failure.
  A later reactivation of the user does not revive these credentials.

  Inputs: DataSource, KeycloakAdmin or nil, tenant UUID, manager UUID, actor
  principal map or nil (audited as system).
  Returns: {:revoked long :pending long}. Throws only on local DB failure."
  [ds keycloak-admin ^UUID tenant-id ^UUID manager-user-id actor]
  (let [rows (jdbc/with-transaction [tx ds]
               (let [rows (db.api-creds/lock-active-managed-credentials tx tenant-id manager-user-id)]
                 (doseq [row rows]
                   (revoke-locally! tx actor row "manager-access-removed"))
                 rows))
        pending (count (filter #(= "pending" (disable-upstream! ds keycloak-admin actor %)) rows))]
    (log/info "Revoked managed API credentials" {:tenant_id (str tenant-id)
                                                 :manager_user_id (str manager-user-id)
                                                 :revoked (count rows)
                                                 :pending pending})
    {:revoked (long (count rows)) :pending (long pending)}))
