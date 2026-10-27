(ns samuraibff.access.session-access-integration-test
  "Postgres integration tests of the recording access model.

  Actors: Alice (Member, owner), Bob (Member), Carol (Builder), Admin, a
  foreign-tenant Admin and a service principal of Alice's tenant."
  (:require
   [clojure.test :refer [deftest is testing]]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set]
   [samuraibff.access.membership :as membership]
   [samuraibff.access.principal :as access.principal]
   [samuraibff.db.principals :as db.principals]
   [samuraibff.db.session-access :as session-access]
   [samuraibff.http.recordings :as http.recordings]
   [samuraibff.http.sharing :as http.sharing]
   [samuraibff.http.ui :as http.ui]
   [samuraibff.test-support.principals :as tp]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.util UUID)))

(def tenant #uuid "11111111-1111-1111-1111-111111111111")
(def foreign-tenant #uuid "22222222-2222-2222-2222-222222222222")

(defn- setup!
  [ds]
  (tp/insert-tenant! ds tenant "Acme")
  (tp/insert-tenant! ds foreign-tenant "Globex")
  {:alice (tp/insert-principal! ds {:tenant-id tenant :name "Alice"})
   :bob (tp/insert-principal! ds {:tenant-id tenant :name "Bob"})
   :carol (tp/insert-principal! ds {:tenant-id tenant :name "Carol" :role :builder})
   :admin (tp/insert-principal! ds {:tenant-id tenant :name "Ada" :role :admin})
   :foreign (tp/insert-principal! ds {:tenant-id foreign-tenant :name "Eve" :role :admin})
   :service (tp/insert-principal! ds {:tenant-id tenant :type :service})})

(defn- insert-session!
  [ds owner]
  (let [id (UUID/randomUUID)]
    (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, user_id, session_key, status) VALUES (?, ?, ?, ?, 'finished')"
                       id (:tenant-id owner) (:id owner) (str id)])
    id))

(defn- list-ids
  [deps principal scope]
  (let [resp ((http.recordings/list-recordings-handler deps)
              (tp/request-for principal {:params {:scope (name scope)}}))]
    {:status (:status resp)
     :ids (set (map :session_id (get-in resp [:body :items])))
     :total (get-in resp [:body :total])}))

(defn- detail-status
  [deps principal session-id]
  (:status ((http.recordings/get-recording-handler deps)
            (tp/request-for principal {:path-params {:session_id (str session-id)}}))))

(defn- share!
  [ds owner session-id visibility recipients]
  (let [revision (:access_revision (jdbc/execute-one! ds ["SELECT access_revision FROM sessions WHERE id=?" session-id]
                                                      {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))]
    (session-access/set-audience! ds owner session-id {:visibility visibility
                                                       :recipient-ids (mapv :id recipients)
                                                       :revision revision})))

(deftest first-demo-sharing-flow-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          {:keys [alice bob carol admin foreign service]} (setup! ds)
          deps {:db {:ds ds} :config {:env :test}}
          sid (insert-session! ds alice)
          sid-str (str sid)]

      (testing "private: nobody but Alice can list or open it"
        (is (contains? (:ids (list-ids deps alice :all)) sid-str))
        (doseq [p [bob carol admin foreign service]]
          (is (not (contains? (:ids (list-ids deps p :all)) sid-str)))
          (is (= 404 (detail-status deps p sid))))
        (is (= 0 (:total (list-ids deps bob :all))) "counts do not leak"))

      (testing "selected people: Bob reads, Carol does not; Bob cannot mutate"
        (is (:ok? (share! ds alice sid "selected" [bob])))
        (is (contains? (:ids (list-ids deps bob :shared)) sid-str))
        (is (not (contains? (:ids (list-ids deps bob :mine)) sid-str)))
        (is (= 200 (detail-status deps bob sid)))
        (is (= 404 (detail-status deps carol sid)))
        (is (= 403 (:status ((http.recordings/delete-recording-handler deps)
                             (tp/request-for bob {:path-params {:session_id sid-str}})))))
        (is (= 403 (:status ((http.ui/rename-session-handler deps)
                             (tp/request-for bob {:path-params {:session_id sid-str}
                                                  :body-params {:title "x"}})))))
        (is (= :forbidden (:error (share! ds bob sid "tenant" []))) "recipients cannot reshare"))

      (testing "Alice's own shared recording stays in My sessions"
        (is (contains? (:ids (list-ids deps alice :mine)) sid-str))
        (is (not (contains? (:ids (list-ids deps alice :shared)) sid-str))))

      (testing "everyone: Carol, Admin and a later member read; services and foreign tenant never"
        (is (:ok? (share! ds alice sid "tenant" [])))
        (let [dave (tp/insert-principal! ds {:tenant-id tenant :name "Dave"})]
          (doseq [p [bob carol admin dave]]
            (is (= 200 (detail-status deps p sid)))))
        (is (= 404 (detail-status deps service sid)))
        (is (= 404 (detail-status deps foreign sid)))
        (is (zero? (:count (jdbc/execute-one! ds ["SELECT count(*) AS count FROM session_access_grants WHERE session_id=?" sid] {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps})))
            "Everyone clears direct grants"))

      (testing "private again: all non-owner access stops"
        (is (:ok? (share! ds alice sid "private" [])))
        (doseq [p [bob carol admin]]
          (is (= 404 (detail-status deps p sid)))))

      (testing "stale revision and invalid recipients are rejected"
        (is (= :conflict (:error (session-access/set-audience! ds alice sid {:visibility "tenant" :revision 0}))))
        (doseq [bad [[foreign] [service] [alice]]]
          (is (= :invalid-recipients (:error (share! ds alice sid "selected" bad)))))
        (is (= :recipients-required (:error (share! ds alice sid "selected" []))))
        (let [suspended (tp/insert-principal! ds {:tenant-id tenant :status :suspended})]
          (is (= :invalid-recipients (:error (share! ds alice sid "selected" [suspended]))))))

      (testing "suspension blocks a grant holder; reactivation restores it"
        (is (:ok? (share! ds alice sid "selected" [bob])))
        (db.principals/update-principal! ds tenant (:id bob) {:status "suspended"})
        (let [bob-row (db.principals/find-by-id ds tenant (:id bob))
              config {:auth {:required? true :issuer tp/test-issuer :tenant-claim "tenant_id"}}
              bob-user {:sub (:external_id bob-row) :raw {:tenant_id (str tenant)}}]
          (is (= :suspended (:state (access.principal/resolve-principal config ds bob-user))))
          (is (nil? (session-access/find-accessible-session
                     ds (access.principal/row->principal bob-row) sid))
              "a suspended principal reads nothing even with a grant"))
        (db.principals/update-principal! ds tenant (:id bob) {:status "active"})
        (is (= 200 (detail-status deps bob sid))))

      (testing "share HTTP contract: owner sees recipients, reader does not"
        (let [owner-view ((http.sharing/get-access-handler deps)
                          (tp/request-for alice {:path-params {:session_id sid-str}}))
              reader-view ((http.sharing/get-access-handler deps)
                           (tp/request-for bob {:path-params {:session_id sid-str}}))]
          (is (= 1 (count (get-in owner-view [:body :recipients]))))
          (is (nil? (get-in reader-view [:body :recipients])))
          (is (false? (get-in reader-view [:body :can_manage]))))))))

(deftest principal-resolution-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          {:keys [alice service]} (setup! ds)
          config {:auth {:required? true :issuer tp/test-issuer :tenant-claim "tenant_id"}}
          user (fn [sub claims] {:sub sub :raw (merge {:tenant_id (str tenant)} claims)})
          alice-sub (:external_id (db.principals/find-by-id ds tenant (:id alice)))
          service-row (db.principals/find-by-id ds tenant (:id service))]
      (testing "provisioned human with matching tenant is active"
        (is (= :active (:state (access.principal/resolve-principal config ds (user alice-sub {}))))))
      (testing "unknown subject is not provisioned, never auto-created"
        (is (= :not-provisioned (:state (access.principal/resolve-principal config ds (user "unknown" {})))))
        (is (nil? (db.principals/find-by-subject ds tp/test-issuer "unknown"))))
      (testing "forged tenant claim is rejected"
        (is (= :not-provisioned (:state (access.principal/resolve-principal
                                         config ds (user alice-sub {:tenant_id (str foreign-tenant)}))))))
      (testing "service needs its bound client as azp"
        (is (= :active (:state (access.principal/resolve-principal
                                config ds (user (:external_id service-row) {:azp (:client_id service-row)})))))
        (is (= :not-provisioned (:state (access.principal/resolve-principal
                                         config ds (user (:external_id service-row) {:azp "bff-web"}))))))
      (testing "pending activates only with a verified email; suspended never"
        (let [pending (tp/insert-principal! ds {:tenant-id tenant :status :pending})
              sub (:external_id (db.principals/find-by-id ds tenant (:id pending)))]
          (is (= :pending (:state (access.principal/resolve-principal config ds (user sub {:email_verified false})))))
          (is (= :active (:state (access.principal/resolve-principal config ds (user sub {:email_verified true})))))
          (db.principals/update-principal! ds tenant (:id pending) {:status "suspended"})
          (is (= :suspended (:state (access.principal/resolve-principal config ds (user sub {:email_verified true})))))))
      (testing "database failure fails closed"
        (is (= :unavailable (:state (access.principal/resolve-principal config nil (user alice-sub {}))))))
      (testing "auth disabled resolves the guest only when seeded"
        (is (= :not-provisioned (:state (access.principal/resolve-principal {:auth {:required? false}} ds nil))))))))

(deftest last-admin-protection-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          {:keys [admin]} (setup! ds)
          admin-2 (tp/insert-principal! ds {:tenant-id tenant :role :admin})
          noop (fn [_ _] nil)
          demote (fn [actor target]
                   (future (membership/update-person! ds {} actor (:id target) {:role "member"} noop)))
          results [(demote admin admin-2) (demote admin-2 admin)]
          outcomes (mapv deref results)]
      (testing "concurrent demotions leave exactly one active Admin"
        (is (= 1 (db.principals/count-active-admins ds tenant)))
        (is (= #{true false} (set (map :ok? outcomes))))
        (is (some #(= :last-admin (:error %)) outcomes)))
      (testing "the remaining Admin cannot suspend themselves"
        (let [remaining (first (filter #(= "admin" (:role (db.principals/find-by-id ds tenant (:id %))))
                                       [admin admin-2]))]
          (is (= :last-admin (:error (membership/update-person! ds {} remaining (:id remaining)
                                                                {:status "suspended"} noop)))))))))
