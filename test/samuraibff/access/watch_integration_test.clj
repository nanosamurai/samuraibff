(ns samuraibff.access.watch-integration-test
  "Postgres integration tests of the batched access re-check.

  The batched check must agree with the request-time rules: owners manage,
  shared readers only read, suspension, unsharing, demotion and key
  revocation end access, and a blocked database times out instead of
  holding the watcher."
  (:require
   [clojure.test :refer [deftest is testing]]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set]
   [samuraibff.access.watch :as watch]
   [samuraibff.db.api-credentials :as db.api-creds]
   [samuraibff.db.session-access :as session-access]
   [samuraibff.test-support.principals :as tp]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.util UUID)))

(def tenant #uuid "11111111-1111-1111-1111-111111111111")

(def ^:private settings
  {:interval-ms 30000 :grace-ms 120000 :timeout-s 1 :max-duration-ms 600000 :jitter 0})

(defn- insert-session!
  [ds owner & {:keys [via visibility]}]
  (let [id (UUID/randomUUID)]
    (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, user_id, session_key, status, visibility, created_via_credential_id)
                        VALUES (?, ?, ?, ?, 'active', ?, ?)"
                       id (:tenant-id owner) (:id owner) (str id) (or visibility "private") via])
    id))

(defn- insert-key!
  [ds kind manager]
  (let [service (tp/insert-principal! ds {:tenant-id tenant :type :service})
        id (UUID/randomUUID)]
    (db.api-creds/insert-credential! ds {:id id :tenant-id tenant :name (str kind "-key")
                                         :keycloak-client-id (:client-id service)
                                         :manager-user-id (:id manager)
                                         :service-user-id (:id service)
                                         :kind kind :read-scope "credential"
                                         :default-visibility "private"})
    {:id id :service service}))

(defn- credential-map
  [id kind]
  {:id id :name (str kind "-key") :kind (keyword kind) :read-scope :credential
   :default-visibility "private"})

(defn- share!
  [ds owner session-id visibility recipients]
  (let [revision (:access_revision (jdbc/execute-one! ds ["SELECT access_revision FROM sessions WHERE id=?" session-id]
                                                      {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))]
    (session-access/set-audience! ds owner session-id {:visibility visibility
                                                       :recipient-ids (mapv :id recipients)
                                                       :revision revision})))

(defn- check
  [ds entries]
  (watch/check-batch! ds settings entries))

(defn- entry
  [principal session-id mode]
  {:id (UUID/randomUUID) :principal principal :session-id session-id :mode mode})

(deftest batched-check-applies-the-access-rules
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          _ (tp/insert-tenant! ds tenant "Acme")
          alice (tp/insert-principal! ds {:tenant-id tenant :name "Alice"})
          bob (tp/insert-principal! ds {:tenant-id tenant :name "Bob"})
          carol (tp/insert-principal! ds {:tenant-id tenant :name "Carol" :role :builder})
          own (insert-session! ds alice)
          personal (insert-key! ds "personal" carol)
          via-key (insert-session! ds carol :via (:id personal))
          org (insert-key! ds "organization" carol)
          org-session (insert-session! ds (:service org))
          carol-key (assoc carol :credential (credential-map (:id personal) "personal"))
          org-principal (assoc (:service org) :credential (credential-map (:id org) "organization"))
          owner-manage (entry alice own :manage)
          bob-read (entry bob own :read)
          bob-manage (entry bob own :manage)
          key-read (entry carol-key via-key :read)
          key-other (entry carol-key own :read)
          org-manage (entry org-principal org-session :manage)
          manager-manage (entry carol org-session :manage)]
      (is (:ok? (share! ds alice own "selected" [bob])))
      (testing "current access, in one batch"
        (is (= {(:id owner-manage) true (:id bob-read) true (:id bob-manage) false
                (:id key-read) true (:id key-other) false (:id org-manage) true
                (:id manager-manage) true}
               (check ds [owner-manage bob-read bob-manage key-read key-other org-manage manager-manage]))))
      (testing "unsharing ends the reader's access"
        (is (:ok? (share! ds alice own "private" [])))
        (is (false? (get (check ds [bob-read]) (:id bob-read)))))
      (testing "demotion below Builder ends personal key access"
        (jdbc/execute! ds ["UPDATE app_users SET role='member' WHERE id=?" (:id carol)])
        (is (false? (get (check ds [key-read]) (:id key-read))))
        (jdbc/execute! ds ["UPDATE app_users SET role='builder' WHERE id=?" (:id carol)])
        (is (true? (get (check ds [key-read]) (:id key-read)))))
      (testing "key revocation ends organization key access"
        (jdbc/execute! ds ["UPDATE api_credentials SET revoked_at=now() WHERE id=?" (:id org)])
        (is (false? (get (check ds [org-manage]) (:id org-manage)))))
      (testing "suspension ends the owner's access"
        (jdbc/execute! ds ["UPDATE app_users SET status='suspended' WHERE id=?" (:id alice)])
        (is (false? (get (check ds [owner-manage]) (:id owner-manage)))))
      (testing "a blocked database times out instead of holding the watcher"
        (with-open [conn (jdbc/get-connection ds)]
          (.setAutoCommit conn false)
          (jdbc/execute! conn ["LOCK TABLE app_users IN ACCESS EXCLUSIVE MODE"])
          (let [started (System/currentTimeMillis)
                result (try (check ds [bob-read]) :answered
                            (catch Exception _ :timed-out))]
            (is (= :timed-out result))
            (is (< (- (System/currentTimeMillis) started) 5000) "bounded by the 1 s statement timeout"))
          (.rollback conn))))))
