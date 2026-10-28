(ns samuraibff.access.principal
  "Resolve the current application principal for HTTP and WebSocket requests.

  Every authenticated request loads the current principal from Postgres. There
  is no positive-permission cache: a role change or suspension applies to the
  next request after commit on every BFF replica.

  Resolution rules:
  - A verified token maps to `app_users` by issuer + subject only. Email is
    never an identity key.
  - The token's tenant claim must equal the principal's immutable tenant.
  - Machine principals must present a token whose authorized party (`azp`)
    is the bound client id. Type is read from the database, never inferred
    from usernames or missing email.
  - An API key's service principal is then bound to its credential row. A
    revoked credential is suspended. A personal key resolves to its owner,
    who must be an active Builder or Admin, with `:credential` attached; an
    organization key stays its service principal with `:credential`
    attached. Keycloak is not involved in this step.
  - Unknown identities are not provisioned automatically.
  - A pending human becomes active only after a login with a verified email.
  - Authentication-disabled mode resolves the explicit guest principal.
  - Database failures fail closed with `:unavailable`.

  Request keys added by `wrap-principal`:
  - `:auth/principal`    principal map (see `samuraibff.access.policy`) or nil
  - `:auth/access-state` :active | :unauthenticated | :not-provisioned |
                         :pending | :suspended | :unavailable
  - `:auth/tenant-id`    the principal's tenant id string (replaces the claim)

  Public API:
  - `row->principal`, `resolve-principal`, `wrap-principal`,
    `denied-response`, `principal-log-context`"
  (:require
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [samuraibff.access.policy :as policy]
   [samuraibff.auth.oidc :as oidc]
   [samuraibff.db.api-credentials :as db.api-creds]
   [samuraibff.db.principals :as db.principals]
   [samuraibff.observability.diagnostics :as diagnostics])
  (:import
   (java.util UUID)))

(def default-guest-tenant-id
  "Guest tenant used when authentication is disabled and none is configured."
  "00000000-0000-0000-0000-000000000000")

(defn row->principal
  "Convert an `app_users` row into the principal map used by the policy.

  Returns nil for a nil row."
  [row]
  (when row
    {:id (:id row)
     :tenant-id (:tenant_id row)
     :type (keyword (:principal_type row))
     :role (some-> (:role row) keyword)
     :status (keyword (:status row))
     :name (:name row)
     :email (:email row)
     :client-id (:client_id row)
     :guest? (= db.principals/guest-issuer (:issuer row))}))

(defn principal-log-context
  "Return safe structured-log fields for a principal (no email or name)."
  [principal]
  (cond-> {}
    principal (assoc :tenant_id (str (:tenant-id principal))
                     :principal_id (str (:id principal))
                     :principal_type (some-> (:type principal) name))
    (:credential principal) (assoc :credential_id (str (get-in principal [:credential :id])))))

(defn- credential-map
  [row]
  {:id (:id row)
   :name (:name row)
   :kind (keyword (:kind row))
   :read-scope (keyword (:read_scope row))
   :default-visibility (:default_visibility row)})

(defn- bind-credential
  "Attach the API key of an active service principal, or resolve a personal
  key to its owner. Returns {:state keyword :principal map?}."
  [ds principal]
  (let [credential (when (= :service (:type principal))
                     (db.api-creds/find-by-service-user ds (:tenant-id principal) (:id principal)))]
    (cond
      (nil? credential)
      {:state :active :principal principal}

      (some? (:revoked_at credential))
      {:state :suspended}

      (= "personal" (:kind credential))
      (let [owner (some->> (:manager_user_id credential)
                           (db.principals/find-by-id ds (:tenant-id principal))
                           row->principal)]
        (if (and (policy/human? owner)
                 (not (policy/guest? owner))
                 (policy/can-create-credential? owner :personal))
          {:state :active :principal (assoc owner :credential (credential-map credential))}
          (do (log/info "Personal API key owner cannot use keys" {:credential_id (str (:id credential))})
              {:state :suspended})))

      :else
      {:state :active :principal (assoc principal :credential (credential-map credential))})))

(defn- guest-tenant-uuid
  [config]
  (UUID/fromString (or (some-> (get-in config [:auth :guest-tenant-id]) str str/trim not-empty)
                       default-guest-tenant-id)))

(defn- guest-principal
  [config ds]
  (if-let [row (db.principals/find-guest ds (guest-tenant-uuid config))]
    (if (= "active" (:status row))
      {:state :active :principal (row->principal row)}
      {:state :suspended})
    {:state :not-provisioned}))

(defn- email-verified?
  [user]
  (true? (get-in user [:raw :email_verified])))

(defn- authorized-party
  [user]
  (some-> (get-in user [:raw :azp]) str))

(defn- tenant-matches?
  [config user row]
  (= (some-> (oidc/extract-tenant-from-claims* config user) str/lower-case)
     (str/lower-case (str (:tenant_id row)))))

(defn- resolve-verified
  "Resolve a verified token user against the principal store."
  [config ds user]
  (let [issuer (get-in config [:auth :issuer])
        row (db.principals/find-by-subject ds issuer (:sub user))]
    (cond
      (nil? row)
      {:state :not-provisioned}

      (not (tenant-matches? config user row))
      (do
        (log/warn "Principal tenant binding mismatch" {:principal_id (str (:id row))})
        {:state :not-provisioned})

      (and (= "service" (:principal_type row))
           (not= (:client_id row) (authorized-party user)))
      (do
        (log/warn "Service principal client binding mismatch" {:principal_id (str (:id row))})
        {:state :not-provisioned})

      :else
      (let [_ (when (nil? (:issuer row))
                (db.principals/bind-issuer! ds (:id row) issuer))
            row (if (and (= "pending" (:status row))
                         (= "human" (:principal_type row))
                         (email-verified? user))
                  (or (db.principals/activate-pending! ds (:id row)) row)
                  row)]
        (case (:status row)
          "active" (do (db.principals/touch-last-seen! ds (:id row))
                       (bind-credential ds (row->principal row)))
          "pending" {:state :pending}
          {:state :suspended})))))

(defn resolve-principal
  "Resolve the principal for a verified token user (or nil).

  Inputs: full config map, DataSource (may be nil), verified user map or nil.
  Returns {:state keyword :principal map?}. Never throws: database errors
  return {:state :unavailable}."
  [config ds user]
  (let [required? (oidc/auth-required? config)]
    (try
      (cond
        (nil? ds) {:state :unavailable}
        (and (nil? user) required?) {:state :unauthenticated}
        (nil? user) (guest-principal config ds)
        :else (let [result (resolve-verified config ds user)]
                (if (and (not required?) (not= :active (:state result)))
                  (guest-principal config ds)
                  result)))
      (catch Exception failure
        (log/warn "Principal resolution failed" (diagnostics/failure-details failure))
        {:state :unavailable}))))

(def ^:private denial-messages
  {:unauthenticated [401 "not-authenticated"]
   :not-provisioned [403 "access-not-provisioned"]
   :pending [403 "account-setup-pending"]
   :suspended [403 "account-suspended"]
   :unavailable [503 "authorization-unavailable"]})

(defn denied-response
  "Return the fixed JSON response for a non-active access state.

  The body never names other users, tenants or identifiers."
  [state]
  (let [[status message] (get denial-messages state [403 "forbidden"])]
    {:status status :body {:ok false :message message}}))

(defn wrap-principal
  "Ring middleware that attaches the current principal to /api requests.

  With allow-inactive? true (for example `/api/me`) the handler receives
  inactive states instead of a denial response, so the UI can explain them.
  Otherwise the request is denied unless the principal is active.

  Inputs: handler, deps map with :config and :db {:ds DataSource},
  allow-inactive? boolean.
  Returns: wrapped handler."
  [handler {:keys [config db]} allow-inactive?]
  (fn [req]
    (let [{:keys [state principal]} (resolve-principal config (:ds db) (:auth/user req))
          req (assoc req
                     :auth/principal principal
                     :auth/access-state state
                     :auth/tenant-id (some-> principal :tenant-id str))]
      (if (or (policy/active? principal) allow-inactive?)
        (handler req)
        (denied-response state)))))
