(ns samuraibff.http.transcript-export-integration-test
  "Postgres integration tests of `GET /api/recordings/:session_id/export`.

  Alice owns the session, Bob reads it through a direct share and Carol has
  no access. Export must follow the recording detail read check."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set]
   [samuraibff.db.session-access :as session-access]
   [samuraibff.http.transcript-export :as transcript-export]
   [samuraibff.test-support.principals :as tp]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.nio.charset StandardCharsets)
   (java.util UUID)))

(defn- insert-transcript!
  [ds session tenant stage track segments created-at]
  (jdbc/execute! ds ["INSERT INTO session_transcripts
                       (id,session_id,tenant_id,source,type,model,track_id,full_text,segments,lang,created_at,segment_start_s)
                     VALUES (?, ?, ?, 'test-worker', ?, 'test-model', ?, ?, ?::jsonb, 'en', ?::timestamptz, ?)"
                     (UUID/randomUUID) session tenant stage track
                     (str/join " " (map :text segments)) (cheshire/generate-string segments) created-at
                     (:start_s (first segments))]))

(defn- body-string
  [resp]
  (String. ^bytes (:body resp) StandardCharsets/UTF_8))

(deftest export-access-and-selection-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          tenant (UUID/randomUUID)
          _ (tp/insert-tenant! ds tenant "Acme")
          alice (tp/insert-principal! ds {:tenant-id tenant :name "Alice"})
          bob (tp/insert-principal! ds {:tenant-id tenant :name "Bob"})
          carol (tp/insert-principal! ds {:tenant-id tenant :name "Carol" :role :admin})
          sid (UUID/randomUUID)
          _ (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, user_id, session_key, status, title)
                                VALUES (?, ?, ?, ?, 'finished', 'Weekly sync: Q3')"
                               sid tenant (:id alice) (str sid)])
          _ (insert-transcript! ds sid tenant "final" nil
                                [{:start_s 0.0 :end_s 1.5 :speaker "SPEAKER_00" :text "old draft"}]
                                "2026-01-01T00:00:00Z")
          _ (insert-transcript! ds sid tenant "final" nil
                                [{:start_s 0.0 :end_s 1.5 :speaker "SPEAKER_00" :text "Hello there"}
                                 {:start_s 61.25 :end_s 62.0 :text "Bye"}]
                                "2026-01-01T00:01:00Z")
          _ (insert-transcript! ds sid tenant "final" "test-shadow"
                                [{:start_s 2.0 :end_s 3.0 :text "Shadow text"}]
                                "2026-01-01T00:02:00Z")
          _ (insert-transcript! ds sid tenant "refined" "whisperx"
                                [{:start_s 5.0 :end_s 6.0 :text "second window"}]
                                "2026-01-01T00:00:10Z")
          _ (insert-transcript! ds sid tenant "refined" "whisperx"
                                [{:start_s 1.0 :end_s 2.0 :text "first window"}]
                                "2026-01-01T00:00:20Z")
          handler (transcript-export/export-transcript-handler {:db {:ds ds}})
          export (fn [principal query]
                   (handler (tp/request-for principal {:path-params {:session_id (str sid)}
                                                       :query-params query})))]

      (testing "the owner exports the latest final row of the first track"
        (let [resp (export alice {"format" "srt"})]
          (is (= 200 (:status resp)))
          (is (= "application/x-subrip; charset=utf-8" (get-in resp [:headers "Content-Type"])))
          (is (= "attachment; filename=\"Weekly_sync_Q3-whisperx.srt\""
                 (get-in resp [:headers "Content-Disposition"])))
          (is (= (str "1\n00:00:00,000 --> 00:00:01,500\nSPEAKER_00: Hello there\n\n"
                      "2\n00:01:01,250 --> 00:01:02,000\nBye\n\n")
                 (body-string resp)))))

      (testing "track and stage selection"
        (is (str/includes? (body-string (export alice {"format" "txt" "track_id" "test-shadow"}))
                           "[00:00:02] Shadow text"))
        (is (= "[00:00:01] first window\n[00:00:05] second window\n"
               (body-string (export alice {"format" "txt" "stage" "refined"})))
            "refined windows are ordered by audio start")
        (is (= 404 (:status (export alice {"format" "txt" "track_id" "missing"}))))
        (let [doc (cheshire/parse-string (body-string (export alice {"format" "json"})) true)]
          (is (= {:session_id (str sid) :title "Weekly sync: Q3" :stage "final" :track_id "whisperx" :lang "en"}
                 (select-keys doc [:session_id :title :stage :track_id :lang])))
          (is (= [0.0 61.25] (mapv :start_s (:segments doc))))))

      (testing "bad input"
        (is (= 400 (:status (export alice {"format" "pdf"}))))
        (is (= 400 (:status (handler (tp/request-for alice {:path-params {:session_id "nope"}
                                                            :query-params {"format" "srt"}}))))))

      (testing "no access is 404, like recording detail"
        (is (= 404 (:status (export bob {"format" "vtt"}))))
        (is (= 404 (:status (export carol {"format" "vtt"})))))

      (testing "a reader exports what they can read; others still cannot"
        (let [revision (:access_revision (jdbc/execute-one! ds ["SELECT access_revision FROM sessions WHERE id=?" sid]
                                                            {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))]
          (is (:ok? (session-access/set-audience! ds alice sid {:visibility "selected"
                                                                :recipient-ids [(:id bob)]
                                                                :revision revision}))))
        (let [resp (export bob {"format" "docx"})]
          (is (= 200 (:status resp)))
          (is (= "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                 (get-in resp [:headers "Content-Type"]))))
        (is (= 404 (:status (export carol {"format" "vtt"}))))))))

(deftest export-filename-test
  (is (= "Weekly_sync_Q3-whisperx.srt" (transcript-export/export-filename "Weekly sync: Q3" "sid" "whisperx" "srt")))
  (is (= "lu_x-whisperx.vtt" (transcript-export/export-filename "Žluť\"; x=" "sid" "whisperx" "vtt"))
      "quotes, separators and non-ASCII characters never reach the header")
  (is (= "sid-whisperx.vtt" (transcript-export/export-filename "Žťň" "sid" "whisperx" "vtt")))
  (is (= "sid-transcript.txt" (transcript-export/export-filename nil "sid" nil "txt")))
  (is (= "a_b-x.json" (transcript-export/export-filename "a/b" "sid" "../x" "json"))))
