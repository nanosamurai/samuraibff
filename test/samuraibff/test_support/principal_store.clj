(ns samuraibff.test-support.principal-store
  "In-memory principal and session-access rows for Docker-free tests.

  Router and WebSocket tests in the PR-gate plan (`samuraibff.ci.test-plan`)
  must not start Postgres. This helper replaces only the database reads used
  by principal resolution and session access. The production resolution and
  policy code still runs: the issuer + subject lookup, the tenant-claim
  binding, the service `azp` binding and
  `samuraibff.access.policy/session-visible-to?` (the reference predicate that
  the SQL read condition mirrors).

  Store shape (an atom):
  {:principals  [app_users-row ...]
   :credentials [api_credentials-row ...]  (optional; API key binding)
   :sessions    [{:id uuid :tenant_id uuid :user_id uuid? :visibility string} ...]
   :grants      #{[session-uuid principal-uuid] ...}}

  Public API:
  - `principal-row`, `guest-row`, `row->principal`, `add-session!`,
    `with-store`"
  (:require
   [clojure.string :as str]
   [samuraibff.access.policy :as policy]
   [samuraibff.access.principal :as access.principal]
   [samuraibff.db.api-credentials :as db.api-creds]
   [samuraibff.db.principals :as db.principals]
   [samuraibff.db.session-access :as session-access]
   [samuraibff.test-support.principals :as tp])
  (:import
   (java.util UUID)))

(defn principal-row
  "Return an `app_users` row map.

  Options: :tenant-id (required), :external-id (required), :issuer (default
  `tp/test-issuer`), :type :human|:service, :role, :status, :client-id, :id."
  [{:keys [id tenant-id external-id issuer type role status client-id]
    :or {type :human status :active}}]
  (let [service? (= :service type)]
    {:id (or id (UUID/randomUUID))
     :tenant_id tenant-id
     :external_id external-id
     :issuer (or issuer tp/test-issuer)
     :principal_type (name type)
     :role (when-not service? (name (or role :member)))
     :status (name status)
     :client_id client-id
     :name (when-not service? (str "User " external-id))
     :email nil}))

(defn guest-row
  "Return the guest principal row for a tenant (default: the guest tenant)."
  ([] (guest-row tp/guest-tenant-id))
  ([tenant-id]
   {:id tp/guest-principal-id
    :tenant_id tenant-id
    :external_id "guest"
    :issuer db.principals/guest-issuer
    :principal_type "human"
    :role "admin"
    :status "active"
    :client_id nil
    :name "Guest"
    :email nil}))

(def row->principal
  "Convert a row to the principal map used by the policy."
  access.principal/row->principal)

(defn add-session!
  "Add a session row owned by owner-row (or ownerless when nil)."
  ([store* session-id owner-row] (add-session! store* session-id owner-row "private"))
  ([store* session-id owner-row visibility]
   (swap! store* update :sessions (fnil conj [])
          {:id (UUID/fromString (str session-id))
           :tenant_id (:tenant_id owner-row)
           :user_id (:id owner-row)
           :visibility visibility})
   nil))

(defn- find-by-subject
  [store* issuer subject]
  (when (and (string? issuer) (seq (str/trim issuer))
             (string? subject) (seq (str/trim subject)))
    (first (filter #(and (= subject (:external_id %))
                         (or (nil? (:issuer %)) (= issuer (:issuer %))))
                   (:principals @store*)))))

(defn- find-guest
  [store* tenant-id]
  (first (filter #(and (= (str tenant-id) (str (:tenant_id %)))
                       (= "guest" (:external_id %))
                       (= db.principals/guest-issuer (:issuer %)))
                 (:principals @store*))))

(defn- find-by-id
  [store* tenant-id id]
  (first (filter #(and (= (str tenant-id) (str (:tenant_id %))) (= (str id) (str (:id %))))
                 (:principals @store*))))

(defn- find-by-service-user
  [store* tenant-id service-user-id]
  (first (filter #(and (= (str tenant-id) (str (:tenant_id %)))
                       (= (str service-user-id) (str (:service_user_id %))))
                 (:credentials @store*))))

(defn- find-accessible-session
  [store* principal session-id]
  (let [{:keys [sessions grants]} @store*]
    (some (fn [session]
            (when (and (= (str session-id) (str (:id session)))
                       (policy/session-visible-to?
                        principal session
                        (contains? (set grants) [(:id session) (:id principal)])))
              session))
          sessions)))

(defn with-store
  "Call f while principal and session-access reads use the rows in store*.

  The redefinitions are global (`with-redefs`), so server threads see them.
  Returns the result of f."
  [store* f]
  (with-redefs [db.principals/find-by-subject (fn [_ issuer subject] (find-by-subject store* issuer subject))
                db.principals/find-guest (fn [_ tenant-id] (find-guest store* tenant-id))
                db.principals/find-by-id (fn [_ tenant-id id] (find-by-id store* tenant-id id))
                db.api-creds/find-by-service-user (fn [_ tenant-id id] (find-by-service-user store* tenant-id id))
                db.principals/touch-last-seen! (constantly nil)
                session-access/find-accessible-session (fn [_ principal session-id]
                                                         (find-accessible-session store* principal session-id))]
    (f)))
