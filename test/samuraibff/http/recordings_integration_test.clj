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
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [next.jdbc :as jdbc]
    [samuraibff.http.recordings :as http.recordings]
    [samuraibff.testcontainers.localstack :as tc.localstack]
    [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
    (java.util UUID)))

(defn- parse-json-body [resp]
  (let [body (:body resp)]
    (cond
      (nil? body) nil
      (map? body) body
      (string? body) (cheshire/parse-string body true)
      (instance? java.io.InputStream body) (cheshire/parse-stream (io/reader body) true)
      :else (cheshire/parse-string (str body) true))))

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

            ;; attach webhook delivery outcomes to session-a
            dispatch-id (UUID/fromString "00000000-0000-0000-0000-000000000901")
            _ (jdbc/execute! ds ["INSERT INTO webhook_delivery_outcomes (
                                  id, created_at, tenant_id, session_id, webhook_id, dispatch_id,
                                  event_id, event_type, attempt_no, status, http_status,
                                  error_code, error_detail, latency_ms,
                                  kafka_topic, kafka_partition, kafka_offset
                                ) VALUES (?, now(), ?, ?, 'wh-1', ?, NULL, 'recording.finished', 1, 'delivered', 200,
                                         NULL, NULL, 12,
                                         't', 0, 0)"
                                 (UUID/fromString "00000000-0000-0000-0000-000000000900") tenant-a session-a dispatch-id])

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

        ;; Security: recordings list must not leak internal recording URLs.
        (is (nil? (get-in items-a [0 :recording :url])))

        (is (= 200 (:status list-resp-b)))
        (is (= 1 (count items-b)))
        (is (= (str session-b) (get-in items-b [0 :session_id])))
        (is (false? (get-in items-b [0 :has_final_transcript])))

        (is (= 200 (:status detail-resp-a)))
        (is (= (str session-a) (get-in detail-body-a [:session :id])))
        (is (false? (get-in detail-body-a [:session :has_recording])))
        (is (true? (get-in detail-body-a [:session :has_final_transcript])))
        (is (= 1 (count (get-in detail-body-a [:transcripts :refined]))))
        (is (= 1 (count (get-in detail-body-a [:transcripts :final]))))

        (is (vector? (get-in detail-body-a [:webhook_delivery_outcomes])))
        (is (= 1 (count (get-in detail-body-a [:webhook_delivery_outcomes]))))
        (is (= "recording.finished" (get-in detail-body-a [:webhook_delivery_outcomes 0 :event_type])))
        (is (= 1 (get-in detail-body-a [:webhook_delivery_outcomes 0 :attempts_count])))

        ;; Transcript records must expose segments as a decoded vector (JSON array).
        (is (vector? (get-in detail-body-a [:transcripts :refined 0 :segments])))
        (is (vector? (get-in detail-body-a [:transcripts :final 0 :segments])))

        (is (= 404 (:status detail-resp-cross)))
        (is (= false (:ok detail-body-cross)))
        (is (= "not-found" (:message detail-body-cross)))))))

(deftest recordings-delete-tenant-scoped-integration-test
  (testing "DELETE /api/recordings/:session_id deletes within tenant only"
    (tc.pg/with-postgres [pg]
      (let [jdbc-url (tc.pg/jdbc-url pg)
            ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
            _ (tc.pg/apply-schema! ds)

            tenant-a (UUID/fromString "00000000-0000-0000-0000-000000000000")
            tenant-b (UUID/fromString "00000000-0000-0000-0000-000000000001")

            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-a "Tenant A"])
            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-b "Tenant B"])

            session-a (UUID/fromString "00000000-0000-0000-0000-000000000010")

            _ (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, session_key, status, created_at) VALUES (?, ?, ?, ?, now())"
                                session-a tenant-a session-a "created"])
            ;; Attach a transcript row to ensure cascade delete.
            _ (jdbc/execute! ds ["INSERT INTO session_transcripts (
                                 id, tenant_id, session_id, type, source, model, full_text, segments, created_at
                               ) VALUES (?, ?, ?, 'final', 'worker', 'whisperx', 'hello', '[]'::jsonb, now())"
                                (UUID/fromString "00000000-0000-0000-0000-000000000100") tenant-a session-a])

            deps {:db {:ds ds}
                  :config {:env :test}}
            delete-handler (http.recordings/delete-recording-handler deps)

            delete-cross (delete-handler {:auth/tenant-id (str tenant-b)
                                          :path-params {:session_id (str session-a)}})
            delete-cross-body (parse-json-body delete-cross)

            delete-ok (delete-handler {:auth/tenant-id (str tenant-a)
                                       :path-params {:session_id (str session-a)}})

            row-session (jdbc/execute-one! ds ["SELECT id FROM sessions WHERE id=?" session-a])
            row-tr (jdbc/execute-one! ds ["SELECT id FROM session_transcripts WHERE session_id=?" session-a])]

        (is (= 404 (:status delete-cross)))
        (is (= "not-found" (:message delete-cross-body)))

        (is (= 200 (:status delete-ok)))
        (is (nil? row-session))
        (is (nil? row-tr))))))

(deftest recordings-delete-active-session-rejected-integration-test
  (testing "DELETE /api/recordings/:session_id rejects deleting active sessions"
    (tc.pg/with-postgres [pg]
      (let [jdbc-url (tc.pg/jdbc-url pg)
            ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
            _ (tc.pg/apply-schema! ds)

            tenant-a (UUID/fromString "00000000-0000-0000-0000-000000000000")
            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-a "Tenant A"])

            session-a (UUID/fromString "00000000-0000-0000-0000-000000000010")
            _ (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, session_key, status, created_at) VALUES (?, ?, ?, ?, now())"
                                session-a tenant-a session-a "active"])

            deps {:db {:ds ds}
                  :config {:env :test}}
            delete-handler (http.recordings/delete-recording-handler deps)
            resp (delete-handler {:auth/tenant-id (str tenant-a)
                                 :path-params {:session_id (str session-a)}})
            body (parse-json-body resp)]
        (is (= 409 (:status resp)))
        (is (= false (:ok body)))
        (is (= "session-active" (:message body)))))))

(deftest recording-audio-handler-file-url-range-integration-test
  (testing "GET /api/recordings/:session_id/audio streams local file with Range"
    (tc.pg/with-postgres [pg]
      (let [jdbc-url (tc.pg/jdbc-url pg)
            ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
            _ (tc.pg/apply-schema! ds)

            tenant-a (UUID/fromString "00000000-0000-0000-0000-000000000000")
            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-a "Tenant A"])

            session-a (UUID/fromString "00000000-0000-0000-0000-000000000010")
            _ (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, session_key, status, created_at) VALUES (?, ?, ?, ?, now())"
                                session-a tenant-a session-a "active"])

            root-path (java.nio.file.Files/createTempDirectory
                        "samuraibff-audio-test"
                        (make-array java.nio.file.attribute.FileAttribute 0))
            root-file (.toFile root-path)
            audio-file (java.io.File. root-file "a.wav")
            bytes (byte-array (range 0 128))
            _ (java.nio.file.Files/write (.toPath audio-file) bytes (into-array java.nio.file.OpenOption []))
            url (str (.toURI audio-file))

            _ (jdbc/execute! ds ["INSERT INTO recordings (id, session_id, recording_url, duration_s, sample_rate, lang, created_at)
                                VALUES (?, ?, ?, 1.0, 16000, 'en', now())"
                                (UUID/fromString "00000000-0000-0000-0000-000000000200") session-a url])

            deps {:db {:ds ds}
                  :config {:env :test
                           :recordings {:local-root (.getPath root-file)}
                           ;; allowlist irrelevant for file://
                           :s3 {:buckets {:recordings {:bucket "x"}}}}}

            handler (http.recordings/get-recording-audio-handler deps)
            ;; Request bytes 10-19.
            resp (handler {:auth/tenant-id (str tenant-a)
                           :uri (str "/api/recordings/" session-a "/audio")
                           :path-params {:session_id (str session-a)}
                           :headers {"range" "bytes=10-19"}})
            out (with-open [in ^java.io.InputStream (:body resp)]
                  (let [buf (byte-array 64)
                        n (.read ^java.io.InputStream in buf)]
                    (vec (take n buf))))]
        (is (= 206 (:status resp)) (pr-str resp))
        (is (= "bytes" (get-in resp [:headers "Accept-Ranges"])) (pr-str (:headers resp)))
        (is (= 10 (first out)))
        (is (= 10 (count out)))))))

(deftest recording-audio-handler-s3-url-range-integration-test
  (testing "GET /api/recordings/:session_id/audio streams S3 object with Range (LocalStack)"
    (tc.localstack/with-localstack [localstack]
      (tc.pg/with-postgres [pg]
        (let [jdbc-url (tc.pg/jdbc-url pg)
              ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
              _ (tc.pg/apply-schema! ds)

              tenant-a (UUID/fromString "00000000-0000-0000-0000-000000000000")
              _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-a "Tenant A"])

              session-a (UUID/fromString "00000000-0000-0000-0000-000000000010")
              _ (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, session_key, status, created_at) VALUES (?, ?, ?, ?, now())"
                                  session-a tenant-a session-a "active"])

              s3 (tc.localstack/s3-client localstack)
              endpoint (tc.localstack/s3-endpoint localstack)
              {:keys [access-key secret-key region]} (tc.localstack/s3-credentials localstack)

              ;; Use two buckets to ensure playback is allowlisted by the recordings bucket
              ;; and does not accidentally depend on the enrollments bucket.
              enrollments-bucket "xamurai-enrollment"
              recordings-bucket "xamurai-recordings"
              _ (tc.localstack/create-bucket! s3 enrollments-bucket)
              _ (tc.localstack/create-bucket! s3 recordings-bucket)

              key "recordings/test/a.wav"
              bytes (byte-array (range 0 128))
              _ (tc.localstack/put-object! s3 recordings-bucket key bytes {:content-type "audio/wav"})
              url (str "s3://" recordings-bucket "/" key)

              _ (jdbc/execute! ds ["INSERT INTO recordings (id, session_id, recording_url, duration_s, sample_rate, lang, created_at)
                                  VALUES (?, ?, ?, 1.0, 16000, 'en', now())"
                                  (UUID/fromString "00000000-0000-0000-0000-000000000200") session-a url])

              config {:env :test
                      :s3 {:region region
                           :endpoint endpoint
                           :access-key access-key
                           :secret-key secret-key
                           :force-path-style? true
                           :buckets {:enrollments {:bucket enrollments-bucket
                                                   :prefix "enrollment"}
                                     :recordings {:bucket recordings-bucket
                                                  :prefix "recordings"}}}
                      :recordings {:local-root ""}}
              deps {:db {:ds ds}
                    :config config}

              handler (http.recordings/get-recording-audio-handler deps)
              resp (handler {:auth/tenant-id (str tenant-a)
                             :uri (str "/api/recordings/" session-a "/audio")
                             :path-params {:session_id (str session-a)}
                             :headers {"range" "bytes=10-19"}})

              out (with-open [in ^java.io.InputStream (:body resp)]
                    (let [buf (byte-array 64)
                          n (.read ^java.io.InputStream in buf)]
                      (vec (take n buf))))]
          (try
            (is (= 206 (:status resp)) (pr-str resp))
            (is (= "bytes" (get-in resp [:headers "Accept-Ranges"])) (pr-str (:headers resp)))
            (is (= 10 (first out)))
            (is (= 10 (count out)))
            (finally
              (.close s3))))))))
