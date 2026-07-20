(ns samuraibff.http.ui-test
  "Unit + integration tests for UI-related HTTP handlers."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.java.io :as io]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [samuraibff.http.ui :as http.ui]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.util UUID)))

(def ^:private uuid-regex
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn- parse-json-body
  [resp]
  (let [body (:body resp)]
    (cond
      (nil? body) nil
      (map? body) body
      (string? body) (cheshire/parse-string body true)
      (instance? java.io.InputStream body) (cheshire/parse-stream (io/reader body) true)
      :else (cheshire/parse-string (str body) true))))

(deftest create-session-handler-unit-test
  (testing "POST /api/sessions returns a uuid (no DB)"
    (let [ws-registry {:config {:env :test}
                       :sessions (atom {})}
          handler (http.ui/create-session-handler {:config {:auth {:required? false}}
                                                   :ws-registry ws-registry
                                                   ;; DB intentionally missing
                                                   :db {:ds nil}})
          resp (handler {})
          body (parse-json-body resp)
          sid (:session_id body)]
      (is (= 200 (:status resp)))
      (is (string? sid))
      (is (re-matches uuid-regex sid))
      (is (string? (:title body)))
      (is (not (str/blank? (:title body)))))))

(deftest create-session-handler-persisted-integration-test
  (testing "POST /api/sessions persists a session row (postgres testcontainer)"
    (tc.pg/with-postgres [pg]
      (let [jdbc-url (tc.pg/jdbc-url pg)
            ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
            _ (tc.pg/apply-schema! ds)

            tenant-id "00000000-0000-0000-0000-000000000000"
            tenant-uuid (UUID/fromString tenant-id)
            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-uuid "Guest"])

            ;; create a guest user row so user_id can resolve (by external_id = "guest")
            _ (jdbc/execute! ds ["INSERT INTO app_users (id, tenant_id, external_id, email, name) VALUES (?, ?, ?, ?, ?)"
                                 tenant-uuid tenant-uuid "guest" "guest@example.com" "Guest"])

            ws-registry {:config {:env :test}
                         :sessions (atom {})}
            handler (http.ui/create-session-handler {:config {:auth {:required? false}
                                                              :features {:ce-mode? false}}
                                                     :ws-registry ws-registry
                                                     :db {:ds ds}})
            resp (handler {})
            body (parse-json-body resp)
            sid (:session_id body)
            row (jdbc/execute-one!
                 ds
                 ["SELECT id, tenant_id, session_key, status, title FROM sessions WHERE id = ?" (UUID/fromString sid)]
                 {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= 200 (:status resp)))
        (is (string? sid))
        (is (re-matches uuid-regex sid))
        (is (string? (:title body)))
        (is (not (str/blank? (:title body))))
        (is (= sid (str (:id row))))
        (is (= tenant-id (str (:tenant_id row))))
        (is (= sid (str (:session_key row))))
        (is (= (:title body) (:title row)))
        (is (= "created" (:status row)))))))

(deftest create-session-handler-persists-webhook-overrides-integration-test
  (testing "POST /api/sessions persists webhook_overrides JSONB when provided"
    (tc.pg/with-postgres [pg]
      (let [jdbc-url (tc.pg/jdbc-url pg)
            ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
            _ (tc.pg/apply-schema! ds)

            tenant-id "00000000-0000-0000-0000-000000000000"
            tenant-uuid (UUID/fromString tenant-id)
            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-uuid "Guest"])

            _ (jdbc/execute! ds ["INSERT INTO app_users (id, tenant_id, external_id, email, name) VALUES (?, ?, ?, ?, ?)"
                                 tenant-uuid tenant-uuid "guest" "guest@example.com" "Guest"])

            ws-registry {:config {:env :test}
                         :sessions (atom {})}
            handler (http.ui/create-session-handler {:config {:auth {:required? false}
                                                              :features {:ce-mode? false}}
                                                     :ws-registry ws-registry
                                                     :db {:ds ds}})
            resp (handler {:body-params {:title "t"
                                         :webhook_overrides {:use_defaults true
                                                             :webhook_ids ["11111111-1111-1111-1111-111111111111"]
                                                             :disable_event_types ["recording.finished"]}}})
            body (parse-json-body resp)
            sid (:session_id body)
            row (jdbc/execute-one!
                 ds
                 ["SELECT webhook_overrides FROM sessions WHERE id = ?" (UUID/fromString sid)]
                 {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= 200 (:status resp)))
        (is (re-matches uuid-regex sid))
        ;; next.jdbc returns PGobject for jsonb; stringify/parse to compare
        (let [overrides-json (some-> (:webhook_overrides row) str)
              overrides (when (seq overrides-json)
                          (cheshire/parse-string overrides-json true))]
          (is (= true (get overrides :use_defaults)))
          (is (= ["11111111-1111-1111-1111-111111111111"] (get overrides :webhook_ids)))
          (is (= ["recording.finished"] (get overrides :disable_event_types))))))))

(deftest create-session-handler-rejects-workflow-webhook-overrides-in-ce-test
  (testing "POST /api/sessions rejects workflow/webhook overrides in default CE mode"
    (let [ws-registry {:config {:env :test}
                       :sessions (atom {})}
          handler (http.ui/create-session-handler {:config {:auth {:required? false}}
                                                   :ws-registry ws-registry
                                                   :db {:ds nil}})
          webhook-resp (handler {:body-params {:webhook_overrides {:use_defaults true}}})
          webhook-body (parse-json-body webhook-resp)
          workflow-resp (handler {:body-params {:workflow_overrides {:use_defaults true}}})
          workflow-body (parse-json-body workflow-resp)]
      (is (= 403 (:status webhook-resp)))
      (is (= false (:ok webhook-body)))
      (is (= "feature-not-enabled" (:message webhook-body)))
      (is (= "workflow-webhook-runtime" (:feature webhook-body)))

      (is (= 403 (:status workflow-resp)))
      (is (= false (:ok workflow-body)))
      (is (= "feature-not-enabled" (:message workflow-body)))
      (is (= "workflow-webhook-runtime" (:feature workflow-body))))))

(deftest create-session-handler-allows-workflow-webhook-overrides-when-enabled-test
  (testing "POST /api/sessions accepts workflow/webhook overrides when CE mode is false"
    (let [ws-registry {:config {:env :test}
                       :sessions (atom {})}
          handler (http.ui/create-session-handler {:config {:auth {:required? false}
                                                            :features {:ce-mode? false}}
                                                   :ws-registry ws-registry
                                                   :db {:ds nil}})
          resp (handler {:body-params {:title "commercial"
                                       :webhook_overrides {:use_defaults false
                                                           :webhook_ids []
                                                           :disable_event_types []}
                                       :workflow_overrides {:use_defaults false
                                                            :workflow_ids []}}})
          body (parse-json-body resp)
          sid (:session_id body)]
      (is (= 200 (:status resp)))
      (is (re-matches uuid-regex sid))
      (is (some? (get-in @(:sessions ws-registry)
                         ["00000000-0000-0000-0000-000000000000" sid]))))))

(deftest create-session-handler-persists-session-settings-integration-test
  (testing "POST /api/sessions persists session_settings JSONB when provided"
    (tc.pg/with-postgres [pg]
      (let [jdbc-url (tc.pg/jdbc-url pg)
            ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
            _ (tc.pg/apply-schema! ds)

            tenant-id "00000000-0000-0000-0000-000000000000"
            tenant-uuid (UUID/fromString tenant-id)
            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-uuid "Guest"])

            _ (jdbc/execute! ds ["INSERT INTO app_users (id, tenant_id, external_id, email, name) VALUES (?, ?, ?, ?, ?)"
                                 tenant-uuid tenant-uuid "guest" "guest@example.com" "Guest"])

            ws-registry {:config {:env :test}
                         :sessions (atom {})}
            handler (http.ui/create-session-handler {:config {:auth {:required? false}}
                                                     :ws-registry ws-registry
                                                     :db {:ds ds}})
            resp (handler {:body-params {:title "t"
                                         :session_settings {:refined_transcript {:consolidation {:enabled true}}}}})
            body (parse-json-body resp)
            sid (:session_id body)
            row (jdbc/execute-one!
                 ds
                 ["SELECT session_settings FROM sessions WHERE id = ?" (UUID/fromString sid)]
                 {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= 200 (:status resp)))
        (is (re-matches uuid-regex sid))
        ;; next.jdbc returns PGobject for jsonb; stringify/parse to compare
        (let [settings-json (some-> (:session_settings row) str)
              settings (when (seq settings-json)
                         (cheshire/parse-string settings-json true))]
          (is (= true (get-in settings [:refined_transcript :consolidation :enabled]))))))))

(deftest rename-session-handler-integration-test
  (testing "PATCH /api/sessions/:session_id updates title (tenant-scoped)"
    (tc.pg/with-postgres [pg]
      (let [jdbc-url (tc.pg/jdbc-url pg)
            ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
            _ (tc.pg/apply-schema! ds)

            tenant-a (UUID/randomUUID)
            tenant-b (UUID/randomUUID)
            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?), (?, ?)"
                                 tenant-a "Tenant A" tenant-b "Tenant B"])

            session-id (UUID/randomUUID)
            _ (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, session_key, title, status) VALUES (?, ?, ?, ?, ?)"
                                 session-id tenant-a (str session-id) "Old" "created"])

            handler (http.ui/rename-session-handler {:config {:auth {:required? true}} :db {:ds ds}})

            ok-resp (handler {:auth/tenant-id (str tenant-a)
                              :path-params {:session_id (str session-id)}
                              :body-params {:title "New title"}})
            ok-body (parse-json-body ok-resp)

            cross-resp (handler {:auth/tenant-id (str tenant-b)
                                 :path-params {:session_id (str session-id)}
                                 :body-params {:title "Hacked"}})
            cross-body (parse-json-body cross-resp)

            row (jdbc/execute-one!
                 ds
                 ["SELECT title FROM sessions WHERE id = ?" session-id]
                 {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= 200 (:status ok-resp)))
        (is (= true (:ok ok-body)))
        (is (= "New title" (:title ok-body)))
        (is (= "New title" (:title row)))

        (is (= 404 (:status cross-resp)))
        (is (= false (:ok cross-body)))
        (is (= "not-found" (:message cross-body)))))))

(deftest finish-session-handler-integration-test
  (testing "POST /api/sessions/:session_id/finish marks session finished (tenant-scoped)"
    (tc.pg/with-postgres [pg]
      (let [jdbc-url (tc.pg/jdbc-url pg)
            ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
            _ (tc.pg/apply-schema! ds)

            tenant-a (UUID/randomUUID)
            tenant-b (UUID/randomUUID)
            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?), (?, ?)"
                                 tenant-a "Tenant A" tenant-b "Tenant B"])

            session-id (UUID/randomUUID)
            _ (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, session_key, title, status) VALUES (?, ?, ?, ?, ?)"
                                 session-id tenant-a (str session-id) "T" "active"])

            handler (http.ui/finish-session-handler {:config {:auth {:required? true}} :db {:ds ds}})

            ok-resp (handler {:auth/tenant-id (str tenant-a)
                              :path-params {:session_id (str session-id)}})
            ok-body (parse-json-body ok-resp)

            cross-resp (handler {:auth/tenant-id (str tenant-b)
                                 :path-params {:session_id (str session-id)}})
            cross-body (parse-json-body cross-resp)

            row (jdbc/execute-one!
                 ds
                 ["SELECT status, ended_at FROM sessions WHERE id = ?" session-id]
                 {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= 200 (:status ok-resp)))
        (is (= true (:ok ok-body)))
        (is (= (str session-id) (:session_id ok-body)))
        (is (= "finished" (:status ok-body)))

        (is (= "finished" (:status row)))
        (is (some? (:ended_at row)))

        (is (= 404 (:status cross-resp)))
        (is (= false (:ok cross-body)))
        (is (= "not-found" (:message cross-body)))))))
