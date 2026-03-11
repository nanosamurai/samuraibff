(ns samuraibff.http.recordings-integration-test
  "Integration tests for recordings endpoints.

  Covered endpoints:
  - GET /api/recordings
  - GET /api/recordings/:session_id

  The tests use a Postgres Testcontainer and call handlers directly (Ring request maps),
  asserting:
  - tenant scoping (no cross-tenant data leaks)
  - transcript type filtering (refined/final)
  - 404 on not-found within tenant."
  (:require
    [cheshire.core :as cheshire]
    [clojure.test :refer :all]
    [next.jdbc :as jdbc]
    [samuraibff.http.recordings :as http.recordings]
    [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
    (java.util UUID)))

(defn- parse-json-body [resp]
  (cheshire/parse-string (:body resp) true))

(deftest recordings-list-and-detail-tenant-scoped-integration-test
  (testing "Recordings list/detail are tenant-scoped and return transcripts"
    (tc.pg/with-postgres [pg]
      (let [jdbc-url (tc.pg/jdbc-url pg)
            ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
            _ (tc.pg/apply-schema! ds)

            tenant-a (UUID/fromString "00000000-0000-0000-0000-000000000000")
            tenant-b (UUID/fromString "00000000-0000-0000-0000-000000000001")

            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-a "Tenant A"])
            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-b "Tenant B"])

            session-a (UUID/fromString "00000000-0000-0000-0000-000000000010")
            session-b (UUID/fromString "00000000-0000-0000-0000-000000000011")

            _ (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, session_key, status, created_at) VALUES (?, ?, ?, ?, now())"
                                session-a tenant-a session-a "active"])
            _ (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, session_key, status, created_at) VALUES (?, ?, ?, ?, now())"
                                session-b tenant-b session-b "active"])

            ;; attach final transcript to session-a
            _ (jdbc/execute! ds ["INSERT INTO session_transcripts (
                                 id, tenant_id, session_id, type, source, model, full_text, segments, created_at
                               ) VALUES (?, ?, ?, 'final', 'worker', 'whisperx', 'hello final', '[]'::jsonb, now())"
                                (UUID/fromString "00000000-0000-0000-0000-000000000100") tenant-a session-a])
            ;; attach refined transcript to session-a
            _ (jdbc/execute! ds ["INSERT INTO session_transcripts (
                                 id, tenant_id, session_id, type, source, model, full_text, segments, created_at
                               ) VALUES (?, ?, ?, 'refined', 'worker', 'whisperx', 'hello refined', '[]'::jsonb, now())"
                                (UUID/fromString "00000000-0000-0000-0000-000000000101") tenant-a session-a])

            deps {:db {:ds ds}
                  :config {:env :test}}
            list-handler (http.recordings/list-recordings-handler deps)
            detail-handler (http.recordings/get-recording-handler deps)

            list-resp-a (list-handler {:auth/tenant-id (str tenant-a)
                                      :params {:limit "200" :offset "0"}})
            list-body-a (parse-json-body list-resp-a)
            items-a (:items list-body-a)

            list-resp-b (list-handler {:auth/tenant-id (str tenant-b)
                                      :params {}})
            list-body-b (parse-json-body list-resp-b)
            items-b (:items list-body-b)

            detail-resp-a (detail-handler {:auth/tenant-id (str tenant-a)
                                           :path-params {:session_id (str session-a)}})
            detail-body-a (parse-json-body detail-resp-a)

            detail-resp-cross (detail-handler {:auth/tenant-id (str tenant-b)
                                               :path-params {:session_id (str session-a)}})
            detail-body-cross (parse-json-body detail-resp-cross)]

        (is (= 200 (:status list-resp-a)))
        (is (= 1 (count items-a)))
        (is (= (str session-a) (get-in items-a [0 :session_id])))
        (is (true? (get-in items-a [0 :has_final_transcript])))

        (is (= 200 (:status list-resp-b)))
        (is (= 1 (count items-b)))
        (is (= (str session-b) (get-in items-b [0 :session_id])))
        (is (false? (get-in items-b [0 :has_final_transcript])))

        (is (= 200 (:status detail-resp-a)))
        (is (= (str session-a) (get-in detail-body-a [:session :id])))
        (is (= 1 (count (get-in detail-body-a [:transcripts :refined]))))
        (is (= 1 (count (get-in detail-body-a [:transcripts :final]))))

        (is (= 404 (:status detail-resp-cross)))
        (is (= false (:ok detail-body-cross)))
        (is (= "not-found" (:message detail-body-cross)))))))
