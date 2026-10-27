(ns samuraibff.test-support.principals
  "Test helpers for provisioned principals and authorized Ring requests.

  Public API:
  - `insert-tenant!`, `insert-principal!`, `request-for`"
  (:require
   [next.jdbc :as jdbc]
   [samuraibff.access.principal :as access.principal]
   [samuraibff.db.principals :as db.principals])
  (:import
   (java.util UUID)))

(def test-issuer
  "Issuer used for principals created by tests."
  "https://issuer.test/realms/nanosamurai")

(defn insert-tenant!
  "Insert a tenant row. Returns the tenant UUID."
  [ds ^UUID tenant-id tenant-name]
  (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT DO NOTHING" tenant-id tenant-name])
  tenant-id)

(defn insert-principal!
  "Insert a principal and return its principal map.

  Options: :tenant-id (required), :type :human|:service (default :human),
  :role :member|:builder|:admin (default :member for humans), :status
  (default :active), :name, :email, :external-id, :client-id."
  [ds {:keys [tenant-id type role status name email external-id client-id]
       :or {type :human status :active}}]
  (let [id (UUID/randomUUID)
        service? (= :service type)
        row (db.principals/insert-principal!
             ds
             {:id id
              :tenant-id tenant-id
              :external-id (or external-id (str "sub-" id))
              :issuer test-issuer
              :principal-type (clojure.core/name type)
              :role (when-not service? (clojure.core/name (or role :member)))
              :status (clojure.core/name status)
              :client-id (when service? (or client-id (str "client-" id)))
              :name (or name (when-not service? (str "User " (subs (str id) 0 8))))
              :email email})]
    (access.principal/row->principal row)))

(defn request-for
  "Return a Ring request map authorized as principal, merged with extra keys."
  ([principal] (request-for principal {}))
  ([principal extra]
   (merge {:auth/principal principal
           :auth/access-state :active
           :auth/tenant-id (str (:tenant-id principal))
           :auth/user {:sub (str "sub-" (:id principal))}}
          extra)))
