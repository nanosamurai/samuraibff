(ns samuraibff.http.api-credentials-access-integration-test
  "Role rules and service-principal lifecycle for API credentials.

  Keycloak is replaced with an in-memory KeycloakAdmin; Postgres is real."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [samuraibff.access.principal :as access.principal]
   [samuraibff.db.api-credentials :as db.api-creds]
   [samuraibff.db.principals :as db.principals]
   [samuraibff.http.api-credentials :as http.api-creds]
   [samuraibff.keycloak.admin :as kc.admin]
   [samuraibff.schemas :as schemas]
   [samuraibff.test-support.principals :as p]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.util UUID)))

(def ^:private config
  {:auth {:required? true
          :issuer p/test-issuer
          :audience "bff-web"
          :tenant-claim "tenant_id"}})

(defn- fake-keycloak
  "Return [KeycloakAdmin state-atom]. Set :fail-disable? or :fail-service-account?
  in the atom to simulate upstream failures; :calls records operations."
  []
  (let [state (atom {:calls [] :n 0})]
    [(reify kc.admin/KeycloakAdmin
       (create-m2m-client! [_ _]
         (let [n (:n (swap! state update :n inc))]
           (swap! state update :calls conj [:create n])
           {:client-id (str "m2m-test-" n) :client-secret (str "synthetic-secret-" n)}))
       (rotate-client-secret! [_ {:keys [client-id]}]
         (swap! state update :calls conj [:rotate client-id])
         {:client-secret "synthetic-rotated-secret"})
       (disable-client! [_ {:keys [client-id]}]
         (when (:fail-disable? @state)
           (throw (ex-info "Keycloak request failed" {:type :samuraibff.keycloak-admin/request-failed})))
         (swap! state update :calls conj [:disable client-id])
         nil)
       (service-account-user-id [_ {:keys [client-id]}]
         (when (:fail-service-account? @state)
           (throw (ex-info "Keycloak request failed" {:type :samuraibff.keycloak-admin/request-failed})))
         (str "sa-" client-id))
       (delete-client! [_ {:keys [client-id]}]
         (swap! state update :calls conj [:delete client-id])
         nil))
     state]))

(defn- service-row
  [ds tenant credential-id]
  (let [cred (db.api-creds/find-credential ds tenant credential-id)]
    (when-let [service-id (:service_user_id cred)]
      (db.principals/find-by-id ds tenant service-id))))

(defn- token-state
  "Resolve the access state of a client_credentials token for a service row."
  [ds tenant row]
  (:state (access.principal/resolve-principal
           config ds {:sub (:external_id row)
                      :raw {:tenant_id (str tenant) :azp (:client_id row)}})))

(deftest api-credential-access-and-lifecycle-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          tenant (p/insert-tenant! ds (UUID/randomUUID) "acme")
          principal (fn [opts] (p/insert-principal! ds (assoc opts :tenant-id tenant)))
          builder-a (principal {:role :builder :name "Builder A"})
          builder-b (principal {:role :builder :name "Builder B"})
          member (principal {:role :member})
          admin (principal {:role :admin :name "Admin"})
          service (principal {:type :service})
          [kc state] (fake-keycloak)
          deps {:config config :db {:ds ds} :keycloak-admin kc}
          call (fn [handler who extra] ((handler deps) (p/request-for who extra)))
          create (fn [who name] (call http.api-creds/create-api-credential-handler who {:body-params {:name name}}))
          list-as (fn [who] (call http.api-creds/list-api-credentials-handler who {}))
          rotate (fn [who id] (call http.api-creds/rotate-api-credential-handler who {:path-params {:id id}}))
          revoke (fn [who id] (call http.api-creds/revoke-api-credential-handler who {:path-params {:id id}}))]
      (testing "Members and services cannot list or create"
        (doseq [who [member service]]
          (is (= {:status 403 :body {:ok false :message "forbidden"}} (list-as who)))
          (is (= 403 (:status (create who "nope")))))
        (is (empty? (:calls @state))))
      (let [a-resp (create builder-a "builder-a-cred")
            a-id (get-in a-resp [:body :credential_id])
            a-uuid (UUID/fromString a-id)
            b-id (get-in (create builder-b "builder-b-cred") [:body :credential_id])
            admin-id (get-in (create admin "admin-cred") [:body :credential_id])]
        (testing "Create registers an active service principal before returning the secret"
          (is (= 200 (:status a-resp)))
          (is (= (:body a-resp) (schemas/validate! schemas/CreateApiCredentialResponse (:body a-resp))))
          (is (str/starts-with? (get-in a-resp [:body :client_secret]) "synthetic-secret-"))
          (let [cred (db.api-creds/find-credential ds tenant a-uuid)
                row (service-row ds tenant a-uuid)]
            (is (= (:id builder-a) (:manager_user_id cred)))
            (is (= {:principal_type "service" :role nil :status "active" :issuer p/test-issuer
                    :client_id (:keycloak_client_id cred) :external_id (str "sa-" (:keycloak_client_id cred))}
                   (select-keys row [:principal_type :role :status :issuer :client_id :external_id])))
            (is (= :active (token-state ds tenant row)))))

        (testing "Builder lists only own credentials; Admin lists all with manager"
          (let [resp (list-as builder-a)]
            (is (= [a-id] (mapv :id (get-in resp [:body :items]))))
            (is (= (:body resp) (schemas/validate! schemas/ApiCredentialsListResponse (:body resp))))
            (is (= {:id (str (:id builder-a)) :display_name "Builder A" :is_me true}
                   (get-in resp [:body :items 0 :manager])))
            (is (true? (get-in resp [:body :items 0 :service_principal_registered]))))
          (let [items (get-in (list-as admin) [:body :items])]
            (is (= #{a-id b-id admin-id} (set (map :id items))))
            (is (every? :can_manage items))))

        (testing "Peer Builder and Member cannot rotate or revoke"
          (is (= 404 (:status (rotate builder-b a-id))))
          (is (= 404 (:status (revoke builder-b a-id))))
          (is (= 403 (:status (revoke member a-id))))
          (is (= "active" (:status (service-row ds tenant a-uuid)))))

        (testing "Rotation keeps the service principal active"
          (let [resp (rotate builder-a a-id)]
            (is (= 200 (:status resp)))
            (is (= "synthetic-rotated-secret" (get-in resp [:body :client_secret])))
            (is (= "active" (:status (service-row ds tenant a-uuid))))))

        (testing "Revoke suspends the service principal, so its tokens stop at once"
          (let [resp (revoke builder-a a-id)
                row (service-row ds tenant a-uuid)]
            (is (= {:ok true :credential_id a-id :upstream "disabled"} (:body resp)))
            (is (= "suspended" (:status row)))
            (is (= :suspended (token-state ds tenant row)))
            (is (some? (:revoked_at (db.api-creds/find-credential ds tenant a-uuid))))
            (is (= {:status 400 :body {:ok false :message "credential-revoked"}} (rotate builder-a a-id)))))

        (testing "Upstream failure is reported as pending and retried on repeat"
          (swap! state assoc :fail-disable? true)
          (let [b-uuid (UUID/fromString b-id)
                resp (revoke builder-b b-id)]
            (is (= "pending" (get-in resp [:body :upstream])))
            (is (= "disable_pending" (:identity_sync_state (db.api-creds/find-credential ds tenant b-uuid))))
            (is (= "suspended" (:status (service-row ds tenant b-uuid))))
            (swap! state assoc :fail-disable? false)
            (is (= "disabled" (get-in (revoke builder-b b-id) [:body :upstream])))
            (is (= "synced" (:identity_sync_state (db.api-creds/find-credential ds tenant b-uuid))))))

        (testing "Failed principal registration deletes the client and returns no secret"
          (swap! state assoc :fail-service-account? true)
          (let [resp (create builder-a "broken")
                client-id (str "m2m-test-" (:n @state))]
            (is (= {:status 502 :body {:ok false :message "credential-provisioning-failed"}} resp))
            (is (some #{[:delete client-id]} (:calls @state)))
            (is (not-any? #(= client-id (:keycloak_client_id %))
                          (db.api-creds/list-credentials ds tenant {}))))
          (swap! state assoc :fail-service-account? false))

        (testing "Removing a manager revokes all their credentials; reactivation does not revive them"
          (let [ids (mapv #(UUID/fromString (get-in (create builder-a %) [:body :credential_id])) ["one" "two"])]
            (swap! state assoc :fail-disable? true)
            (is (nil? (http.api-creds/revoke-managed-credentials-for-manager! deps tenant (:id builder-a))))
            (swap! state assoc :fail-disable? false)
            (db.principals/update-principal! ds tenant (:id builder-a) {:status "suspended"})
            (db.principals/update-principal! ds tenant (:id builder-a) {:status "active"})
            (doseq [id ids]
              (let [cred (db.api-creds/find-credential ds tenant id)]
                (is (some? (:revoked_at cred)))
                (is (= "disable_pending" (:identity_sync_state cred)))
                (is (= "suspended" (:status (service-row ds tenant id))))))))

        (testing "Legacy credentials without a manager are Admin-managed"
          (let [legacy (UUID/randomUUID)]
            (jdbc/execute! ds ["INSERT INTO api_credentials (id, tenant_id, name, keycloak_client_id) VALUES (?, ?, 'legacy', 'legacy-client')"
                               legacy tenant])
            (is (not-any? #(= (str legacy) (:id %)) (get-in (list-as builder-b) [:body :items])))
            (is (= 404 (:status (revoke builder-b (str legacy)))))
            (is (false? (get-in (first (filter #(= (str legacy) (:id %)) (get-in (list-as admin) [:body :items])))
                                [:service_principal_registered])))
            (is (= "disabled" (get-in (revoke admin (str legacy)) [:body :upstream])))))

        (testing "Credential changes are audited without secrets"
          (let [events (jdbc/execute! ds ["SELECT action, outcome, actor_kind, details::text AS details
                                             FROM access_audit_events WHERE tenant_id = ? AND target_type = 'api_credential'"
                                          tenant]
                                      {:builder-fn rs/as-unqualified-lower-maps})]
            (is (= #{"credential.create" "credential.rotate" "credential.revoke" "credential.disable"}
                   (set (map :action events))))
            (is (some #(= ["credential.revoke" "system"] [(:action %) (:actor_kind %)]) events))
            (is (not (str/includes? (pr-str events) "synthetic")))))))))
