(ns samuraibff.http.ui-test
  "Unit + integration tests for UI-related HTTP handlers." 
  (:require
    [cheshire.core :as cheshire]
    [clojure.test :refer :all]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [samuraibff.http.ui :as http.ui]
    [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
    (java.util UUID)))

(def ^:private uuid-regex
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(deftest create-session-handler-unit-test
  (testing "POST /api/sessions returns a uuid (no DB)"
    (let [ws-registry {:config {:env :test}
                       :sessions (atom {})}
          handler (http.ui/create-session-handler {:config {:auth {:required? false}}
                                                   :ws-registry ws-registry
                                                   ;; DB intentionally missing
                                                   :db {:ds nil}})
          resp (handler {})
          body (cheshire/parse-string (:body resp) true)
          sid (:session_id body)]
      (is (= 200 (:status resp)))
      (is (string? sid))
      (is (re-matches uuid-regex sid)))))

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
            handler (http.ui/create-session-handler {:config {:auth {:required? false}}
                                                     :ws-registry ws-registry
                                                     :db {:ds ds}})
            resp (handler {})
            body (cheshire/parse-string (:body resp) true)
            sid (:session_id body)
            row (jdbc/execute-one!
                  ds
                  ["SELECT id, tenant_id, session_key, status FROM sessions WHERE id = ?" (UUID/fromString sid)]
                  {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= 200 (:status resp)))
        (is (string? sid))
        (is (re-matches uuid-regex sid))
        (is (= sid (str (:id row))))
        (is (= tenant-id (str (:tenant_id row))))
        (is (= sid (str (:session_key row))))
        (is (= "created" (:status row)))))))
