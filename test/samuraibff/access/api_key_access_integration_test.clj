(ns samuraibff.access.api-key-access-integration-test
  "Postgres integration tests of personal and organization API keys.

  Actors: Carol (Builder) with a personal key and as manager of an
  organization key, Bob (Member), an Admin and a foreign-tenant Admin. The
  SQL read condition must agree with `samuraibff.access.policy`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [next.jdbc :as jdbc]
   [samuraibff.access.principal :as access.principal]
   [samuraibff.api-credentials.lifecycle :as lifecycle]
   [samuraibff.db.api-credentials :as db.api-creds]
   [samuraibff.db.principals :as db.principals]
   [samuraibff.http.recordings :as http.recordings]
   [samuraibff.test-support.principals :as tp]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.util UUID)))

(def tenant #uuid "11111111-1111-1111-1111-111111111111")
(def foreign-tenant #uuid "22222222-2222-2222-2222-222222222222")

(def ^:private config
  {:auth {:required? true :issuer tp/test-issuer :tenant-claim "tenant_id"}})

(defn- insert-key!
  "Insert a service principal and its credential row. Returns {:id :service}."
  [ds {:keys [kind manager read-scope audience]}]
  (let [service (tp/insert-principal! ds {:tenant-id tenant :type :service})
        id (UUID/randomUUID)]
    (db.api-creds/insert-credential! ds {:id id
                                         :tenant-id tenant
                                         :name (str kind "-key")
                                         :keycloak-client-id (:client-id service)
                                         :manager-user-id (:id manager)
                                         :service-user-id (:id service)
                                         :kind kind
                                         :read-scope (or read-scope "credential")
                                         :default-visibility (or audience "private")})
    {:id id :service service}))

(defn- insert-session!
  [ds owner {:keys [via visibility]}]
  (let [id (UUID/randomUUID)]
    (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, user_id, session_key, status, visibility, created_via_credential_id)
                        VALUES (?, ?, ?, ?, 'finished', ?, ?)"
                       id (:tenant-id owner) (:id owner) (str id) (or visibility "private") via])
    (str id)))

(defn- resolve-key
  [ds {:keys [service]}]
  (let [row (db.principals/find-by-id ds tenant (:id service))]
    (access.principal/resolve-principal
     config ds {:sub (:external_id row) :raw {:tenant_id (str tenant) :azp (:client_id row)}})))

(defn- listing
  [ds principal scope]
  (let [resp ((http.recordings/list-recordings-handler {:db {:ds ds} :config {:env :test}})
              (tp/request-for principal {:params {:scope (name scope)}}))]
    (into {} (map (juxt :session_id :access)) (get-in resp [:body :items]))))

(defn- detail-status
  [ds principal session-id]
  (:status ((http.recordings/get-recording-handler {:db {:ds ds} :config {:env :test}})
            (tp/request-for principal {:path-params {:session_id session-id}}))))

(deftest personal-and-organization-key-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          _ (tp/insert-tenant! ds tenant "Acme")
          _ (tp/insert-tenant! ds foreign-tenant "Globex")
          carol (tp/insert-principal! ds {:tenant-id tenant :name "Carol" :role :builder})
          bob (tp/insert-principal! ds {:tenant-id tenant :name "Bob"})
          admin (tp/insert-principal! ds {:tenant-id tenant :name "Ada" :role :admin})
          foreign (tp/insert-principal! ds {:tenant-id foreign-tenant :name "Eve" :role :admin})
          personal (insert-key! ds {:kind "personal" :manager carol})
          broad (insert-key! ds {:kind "personal" :manager carol :read-scope "owner"})
          org (insert-key! ds {:kind "organization" :manager carol :audience "tenant"})
          via-key (insert-session! ds carol {:via (:id personal)})
          from-ui (insert-session! ds carol {})
          org-session (insert-session! ds (:service org) {:via (:id org) :visibility "tenant"})
          org-private (insert-session! ds (:service org) {:via (:id org)})]

      (testing "a personal key resolves to its owner with the key attached"
        (let [{:keys [state principal]} (resolve-key ds personal)]
          (is (= :active state))
          (is (= (:id carol) (:id principal)))
          (is (= :human (:type principal)))
          (is (= :personal (get-in principal [:credential :kind])))))

      (testing "a restricted personal key lists and opens only its own sessions"
        (let [k (:principal (resolve-key ds personal))
              items (listing ds k :all)]
          (is (= #{via-key} (set (keys items))))
          (is (true? (get-in items [via-key :can_manage])))
          (is (= "personal-key" (get-in items [via-key :via_api_key])))
          (is (= 200 (detail-status ds k via-key)))
          (is (= 404 (detail-status ds k from-ui)))
          (is (= 404 (detail-status ds k org-session)) "not even Everyone sessions")))

      (testing "an owner-scope key reads what Carol reads, without her manager rights"
        (let [k (:principal (resolve-key ds broad))]
          (is (= #{via-key from-ui org-session} (set (keys (listing ds k :all))))
              "the private organization session is Carol's only as key manager")
          (is (false? (get-in (listing ds k :all) [org-session :can_manage]))
              "manager rights never flow through a key")))

      (testing "Carol sees key sessions in the UI and manages organization ones"
        (let [items (listing ds carol :mine)]
          (is (= #{via-key from-ui org-session org-private} (set (keys items))))
          (is (true? (get-in items [org-session :can_manage])))
          (is (= "api_key" (get-in items [org-session :owner :kind])))))

      (testing "Bob reads the Everyone organization session read-only, not the private one"
        (let [items (listing ds bob :all)]
          (is (= #{org-session} (set (keys items))))
          (is (false? (get-in items [org-session :can_manage])))
          (is (= 404 (detail-status ds bob org-private)))))

      (testing "the Admin manages nothing while Carol manages the key"
        (is (false? (get-in (listing ds admin :all) [org-session :can_manage])))
        (is (= 404 (detail-status ds admin org-private))))

      (testing "the organization key owns its sessions"
        (let [k (:principal (resolve-key ds org))]
          (is (= :organization (get-in k [:credential :kind])))
          (is (= #{org-session org-private} (set (keys (listing ds k :all)))))))

      (testing "losing Builder rights revokes personal keys and hands organization keys to Admins"
        (lifecycle/revoke-managed-credentials! ds nil tenant (:id carol) nil)
        (is (= :suspended (:state (resolve-key ds personal))))
        (is (= :active (:state (resolve-key ds org))))
        (is (nil? (:manager_user_id (db.api-creds/find-credential ds tenant (:id org)))))
        (is (true? (get-in (listing ds admin :all) [org-private :can_manage]))
            "an Admin-managed key's sessions are managed by Admins")
        (is (not (contains? (listing ds foreign :all) org-session)) "never across tenants"))

      (testing "a personal key stops when its owner is demoted, even before revocation"
        (let [dana (tp/insert-principal! ds {:tenant-id tenant :name "Dana" :role :builder})
              dana-key (insert-key! ds {:kind "personal" :manager dana})]
          (is (= :active (:state (resolve-key ds dana-key))))
          (db.principals/update-principal! ds tenant (:id dana) {:role "member"})
          (is (= :suspended (:state (resolve-key ds dana-key))))))

      (testing "Admins configure organization keys; a manager must be a Builder or Admin"
        (is (= (:id admin) (:manager_user_id (lifecycle/configure-credential!
                                               ds admin (:id org) {:manager-id (:id admin)
                                                                   :default-visibility "private"}))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid manager"
                              (lifecycle/configure-credential! ds admin (:id org) {:manager-id (:id bob)})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not found"
                              (lifecycle/configure-credential! ds admin (:id personal) {:default-visibility "tenant"})))))))
