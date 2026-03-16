(ns samuraibff.auth.keycloak-auth-integration-test
  "Heavy auth integration tests using a real Keycloak (Testcontainers).

  Covered:
  - unauthenticated requests cannot access /api/*
  - authenticated end-users are tenant-isolated
  - M2M credentials minted via Keycloak Admin API are tenant-isolated

  Notes:
  - Uses Keycloak Direct Access Grants (password grant) for end-user tokens.
  - Uses Postgres Testcontainers for real DB tenant scoping.
  - Starts a real http-kit server via Integrant.
  "
  (:require
    [cheshire.core :as cheshire]
    [clojure.test :refer :all]
    [integrant.core :as ig]
    [next.jdbc :as jdbc]
    [org.httpkit.client :as http]
    [samuraibff.config]
    [samuraibff.db.core]
    [samuraibff.grpc.client]
    [samuraibff.http.router]
    [samuraibff.http.server]
    [samuraibff.keycloak.admin]
    [samuraibff.testcontainers.keycloak :as tc.kc]
    [samuraibff.testcontainers.postgres :as tc.pg]
    [samuraibff.ws.registry])
  (:import
    (java.util UUID)))

(defn- parse-json-body
  [resp]
  (when-let [body (:body resp)]
    (cheshire/parse-string body true)))

(defn- authz
  [token]
  {"Authorization" (str "Bearer " token)})

(defn- free-port
  "Find a free local TCP port by binding a temporary ServerSocket." 
  []
  (with-open [sock (java.net.ServerSocket. 0)]
    (.getLocalPort sock)))

(defn- start-system!
  "Start a minimal Integrant system for HTTP API testing.

  Returns {:system <ig-system> :base-url <string>}" 
  [{:keys [port issuer audience jdbc-url db-user db-pass admin-client-secret]}]
  (let [cfg {:samuraibff/config {:env :test
                                :http {:host "127.0.0.1" :port port}
                                :auth {:required? true
                                       :issuer issuer
                                       :audience audience
                                       :client-id audience
                                       :tenant-claim "tenant_id"}
                                :db {:jdbc-url jdbc-url
                                     :username (or db-user "drsynth")
                                     :password (or db-pass "drsynth")
                                     :maximum-pool-size 3}
                                :keycloak {:admin {:issuer issuer
                                                   :realm "nanosamurai-test"
                                                   :client-id "bff-admin"
                                                   :client-secret admin-client-secret}}
                                ;; grpc exists but isn't used by these HTTP tests
                                :grpc {:rtservice-addr "localhost:59999"}}
             :samuraibff/db {:config (ig/ref :samuraibff/config)}
             :samuraibff/grpc-client {:config (ig/ref :samuraibff/config)}
             :samuraibff/ws-registry {:config (ig/ref :samuraibff/config)
                                      :kafka-producer nil}
             :samuraibff/keycloak-admin {:config (ig/ref :samuraibff/config)}
             :samuraibff/router {:config (ig/ref :samuraibff/config)
                                 :db (ig/ref :samuraibff/db)
                                 :ws-registry (ig/ref :samuraibff/ws-registry)
                                 :grpc (ig/ref :samuraibff/grpc-client)
                                 :keycloak-admin (ig/ref :samuraibff/keycloak-admin)}
             :samuraibff/http-server {:config (ig/ref :samuraibff/config)
                                      :handler (ig/ref :samuraibff/router)}}
        system (ig/init cfg)]
    {:system system
     :base-url (str "http://127.0.0.1:" port)}))

(defn- stop-system!
  [{:keys [system]}]
  (when system
    (ig/halt! system)))

(defn- seed-db!
  "Create tenants + sessions for tenant A and tenant B." 
  [ds {:keys [tenant-a tenant-b session-a session-b]}]
  (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-a "Tenant A"])
  (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-b "Tenant B"])

  (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, session_key, status, created_at) VALUES (?, ?, ?, ?, now())"
                     session-a tenant-a (str session-a) "active"])
  (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, session_key, status, created_at) VALUES (?, ?, ?, ?, now())"
                     session-b tenant-b (str session-b) "active"]))

(deftest keycloak-backed-auth-and-tenant-isolation
  (testing "end-user + m2m auth works and is tenant isolated"
    (tc.kc/with-keycloak [kc]
      (tc.pg/with-postgres [pg]
        (let [jdbc-url (tc.pg/jdbc-url pg)
              ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
              _ (tc.pg/apply-schema! ds)

              provision (tc.kc/provision-test-realm! kc {})
              issuer (:issuer provision)
              token-endpoint (:token-endpoint provision)

              tenant-a (UUID/fromString (get-in provision [:users :alice :tenant-id]))
              tenant-b (UUID/fromString (get-in provision [:users :bob :tenant-id]))
              session-a (UUID/fromString "00000000-0000-0000-0000-000000000010")
              session-b (UUID/fromString "00000000-0000-0000-0000-000000000011")

              alice-token (tc.kc/password-token!
                            token-endpoint
                            {:client-id (:web-client-id provision)
                             :username (get-in provision [:users :alice :username])
                             :password (get-in provision [:users :alice :password])})
              bob-token (tc.kc/password-token!
                          token-endpoint
                          {:client-id (:web-client-id provision)
                           :username (get-in provision [:users :bob :username])
                           :password (get-in provision [:users :bob :password])})
              port (free-port)
              {:keys [system base-url] :as running} (start-system!
                                                      {:port port
                                                       :issuer issuer
                                                       :audience (:web-client-id provision)
                                                       :jdbc-url jdbc-url
                                                       :db-user "drsynth"
                                                       :db-pass "drsynth"
                                                       :admin-client-secret (:admin-client-secret provision)})]
          (try
            ;; Seed into the DB (use raw datasource; same DB the system pool points at).
            (seed-db! ds {:tenant-a tenant-a
                          :tenant-b tenant-b
                          :session-a session-a
                          :session-b session-b})

            ;; --- Unauthenticated cannot access /api ---
            (let [resp @(http/get (str base-url "/api/recordings") {:timeout 3000})]
              (is (= 403 (:status resp)))
              (is (= "missing-token" (:message (parse-json-body resp)))))

            ;; --- End-user tenant isolation ---
            (let [resp-a @(http/get (str base-url "/api/recordings")
                                    {:timeout 5000
                                     :headers (authz alice-token)})
                  body-a (parse-json-body resp-a)
                  items-a (:items body-a)]
              (is (= 200 (:status resp-a)))
              (is (= 1 (count items-a)))
              (is (= (str session-a) (get-in items-a [0 :session_id]))))

            (let [resp-cross @(http/get (str base-url "/api/recordings/" session-a)
                                        {:timeout 5000
                                         :headers (authz bob-token)})
                  body-cross (parse-json-body resp-cross)]
              (is (= 404 (:status resp-cross)))
              (is (= "not-found" (:message body-cross))))

            ;; --- M2M credential creation (using human token) ---
            (let [create-resp @(http/post (str base-url "/api/api-credentials")
                                          {:timeout 10000
                                           :headers (merge (authz alice-token)
                                                           {"Content-Type" "application/json"})
                                           :body (cheshire/generate-string {:name "sdk"})})
                  create-body (parse-json-body create-resp)
                  client-id (:client_id create-body)
                  client-secret (:client_secret create-body)]
              (is (= 200 (:status create-resp)))
              (is (string? client-id))
              (is (string? client-secret))

              (let [m2m-token (tc.kc/client-credentials-token!
                                token-endpoint
                                {:client-id client-id
                                 :client-secret client-secret})
                    resp-m2m @(http/get (str base-url "/api/recordings")
                                        {:timeout 5000
                                         :headers (authz m2m-token)})
                    body-m2m (parse-json-body resp-m2m)
                    items-m2m (:items body-m2m)]
                (is (= 200 (:status resp-m2m)))
                (is (= 1 (count items-m2m)))
                (is (= (str session-a) (get-in items-m2m [0 :session_id])))))

            (finally
              (stop-system! running))))))))
