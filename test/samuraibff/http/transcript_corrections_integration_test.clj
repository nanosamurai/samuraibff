(ns samuraibff.http.transcript-corrections-integration-test
  "Postgres integration tests of transcript corrections.

  Alice owns the session, Bob reads it through a direct share and Carol has
  no access. Only Alice may correct; everyone who reads sees the corrections,
  and export uses the corrected transcript unless asked for the original."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [samuraibff.db.session-access :as session-access]
   [samuraibff.http.recordings :as http.recordings]
   [samuraibff.http.transcript-corrections :as http.corrections]
   [samuraibff.http.transcript-export :as transcript-export]
   [samuraibff.test-support.principals :as tp]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.nio.charset StandardCharsets)
   (java.util UUID)))

(defn- insert-transcript!
  [ds session tenant stage segments]
  (let [id (UUID/randomUUID)]
    (jdbc/execute! ds ["INSERT INTO session_transcripts
                         (id,session_id,tenant_id,source,type,model,track_id,full_text,segments,lang,created_at)
                       VALUES (?, ?, ?, 'test-worker', ?, 'test-model', 'whisperx', ?, ?::jsonb, 'en', now())"
                       id session tenant stage (str/join " " (map :text segments))
                       (cheshire/generate-string segments)])
    id))

(defn- insert-speaker!
  [ds tenant label]
  (let [id (UUID/randomUUID)]
    (jdbc/execute! ds ["INSERT INTO speakers (id, tenant_id, label, audio_url) VALUES (?, ?, ?, 's3://enrollments/x.wav')"
                       id tenant label])
    id))

(def segments
  [{:start_s 0.0 :end_s 2.0 :speaker "SPEAKER_00" :text "Let's go through the Q-four numbers"
    :words [{:start_s 0.0 :end_s 0.3 :text "Let's"} {:start_s 0.3 :end_s 0.5 :text "go"}
            {:start_s 0.5 :end_s 0.9 :text "through"} {:start_s 0.9 :end_s 1.0 :text "the"}
            {:start_s 1.0 :end_s 1.5 :text "Q-four"} {:start_s 1.5 :end_s 2.0 :text "numbers"}]}
   {:start_s 2.0 :end_s 4.0 :speaker "Anna" :text "Churn in the enterprise tear worries me"}
   {:start_s 4.0 :end_s 5.0 :speaker "SPEAKER_00" :text "Agreed"}])

(defn- share-with!
  [ds owner sid reader]
  (let [revision (:access_revision (jdbc/execute-one! ds ["SELECT access_revision FROM sessions WHERE id=?" sid]
                                                      {:builder-fn rs/as-unqualified-lower-maps}))]
    (session-access/set-audience! ds owner sid {:visibility "selected" :recipient-ids [(:id reader)]
                                                :revision revision})))

(defn- body-string
  [resp]
  (String. ^bytes (:body resp) StandardCharsets/UTF_8))

(deftest corrections-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          tenant (UUID/randomUUID)
          _ (tp/insert-tenant! ds tenant "Acme")
          alice (tp/insert-principal! ds {:tenant-id tenant :name "Alice"})
          bob (tp/insert-principal! ds {:tenant-id tenant :name "Bob"})
          carol (tp/insert-principal! ds {:tenant-id tenant :name "Carol" :role :admin})
          sid (UUID/randomUUID)
          other-sid (UUID/randomUUID)
          _ (doseq [[id title] [[sid "Weekly sync"] [other-sid "Other"]]]
              (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, user_id, session_key, status, title)
                                  VALUES (?, ?, ?, ?, 'finished', ?)" id tenant (:id alice) (str id) title]))
          transcript (insert-transcript! ds sid tenant "final" segments)
          refined (insert-transcript! ds sid tenant "refined" segments)
          other-transcript (insert-transcript! ds other-sid tenant "final" segments)
          anna-id (insert-speaker! ds tenant "Anna")
          petr-id (insert-speaker! ds tenant "Petr")
          handler (http.corrections/create-correction-handler {:db {:ds ds}})
          post! (fn [principal body & {:keys [transcript-id session-id]
                                       :or {transcript-id transcript session-id sid}}]
                  (handler (tp/request-for principal {:path-params {:session_id (str session-id)
                                                                    :transcript_id (str transcript-id)}
                                                      :parameters {:body body}})))
          detail (fn [principal]
                   ((http.recordings/get-recording-handler {:db {:ds ds} :config {:env :test}})
                    (tp/request-for principal {:path-params {:session_id (str sid)}})))
          export (fn [principal query]
                   ((transcript-export/export-transcript-handler {:db {:ds ds}})
                    (tp/request-for principal {:path-params {:session_id (str sid)} :query-params query})))
          text-edit (atom nil)]

      (testing "the owner corrects segment text; the edit is stored at once"
        (let [resp (post! alice {:kind "segment_text" :base_seq 0 :segment_index 0
                                 :text "Let's go through the Q4 numbers"})]
          (is (= 201 (:status resp)))
          (is (= 1 (get-in resp [:body :head_seq])))
          (is (= {:display_name "Alice" :is_me true} (get-in resp [:body :correction :author])))
          (is (= {:text "Let's go through the Q-four numbers"} (get-in resp [:body :correction :old_value])))
          (is (= [0.0 2.0] ((juxt :audio_start_s :audio_end_s) (get-in resp [:body :correction]))))
          (reset! text-edit (get-in resp [:body :correction :id]))))

      (testing "readers and strangers cannot correct"
        (share-with! ds alice sid bob)
        (is (= {:status 403 :body {:ok false :message "read-only-access"}}
               (post! bob {:kind "segment_text" :base_seq 1 :segment_index 1 :text "x"})))
        (is (= 404 (:status (post! carol {:kind "segment_text" :base_seq 1 :segment_index 1 :text "x"})))))

      (testing "optimistic concurrency is per target"
        (is (= {:status 409 :body {:ok false :message "correction-conflict"}}
               (post! alice {:kind "segment_text" :base_seq 0 :segment_index 0 :text "Stale edit"})))
        (is (= 201 (:status (post! alice {:kind "segment_text" :base_seq 0 :segment_index 1
                                          :text "Churn in the enterprise tier worries me"})))))

      (testing "speakers: name a label and replace a wrongly identified enrolled speaker"
        (is (= 201 (:status (post! alice {:kind "speaker_label" :base_seq 2 :speaker_label "SPEAKER_00"
                                          :speaker "Miro"}))))
        (let [resp (post! alice {:kind "segment_speaker" :base_seq 3 :segment_index 1 :speaker_id (str petr-id)})]
          (is (= 201 (:status resp)))
          (is (= {:speaker "Anna" :machine_speaker "Anna" :machine_speaker_id (str anna-id)}
                 (get-in resp [:body :correction :old_value])))
          (is (= {:speaker "Petr" :speaker_id (str petr-id)} (get-in resp [:body :correction :new_value]))))
        (is (= {:status 400 :body {:ok false :message "unknown-speaker"}}
               (post! alice {:kind "segment_speaker" :base_seq 4 :segment_index 1
                             :speaker_id (str (UUID/randomUUID))})))
        (is (= {:status 400 :body {:ok false :message "invalid-speaker-label"}}
               (post! alice {:kind "speaker_label" :base_seq 4 :speaker_label "SPEAKER_07" :speaker "X"}))))

      (testing "undo appends a row and can happen once"
        (is (= 201 (:status (post! alice {:kind "undo" :base_seq 4 :correction_id @text-edit}))))
        (is (= {:status 409 :body {:ok false :message "already-undone"}}
               (post! alice {:kind "undo" :base_seq 5 :correction_id @text-edit}))))

      (testing "only final transcripts of this session can be corrected"
        (is (= 404 (:status (post! alice {:kind "segment_text" :base_seq 0 :segment_index 0 :text "x"}
                                   :transcript-id refined))))
        (is (= 404 (:status (post! alice {:kind "segment_text" :base_seq 0 :segment_index 0 :text "x"}
                                   :transcript-id other-transcript))))
        (is (= 400 (:status (post! alice {:kind "segment_text" :base_seq 0 :segment_index 0 :text "x"}
                                   :transcript-id "nope")))))

      (testing "concurrent edits of different segments get consecutive seq numbers"
        (let [results (mapv deref (mapv (fn [[idx text]]
                                          (future (post! alice {:kind "segment_text" :base_seq 5
                                                                :segment_index idx :text text})))
                                        [[0 "Zero"] [2 "Two"]]))]
          (is (= [201 201] (mapv :status results)))
          (is (= #{6 7} (set (map #(get-in % [:body :head_seq]) results))))))

      (testing "rows are only appended"
        (is (= (range 1 8)
               (map :seq (jdbc/execute! ds ["SELECT seq FROM transcript_corrections WHERE transcript_id=? ORDER BY seq"
                                            transcript]
                                        {:builder-fn rs/as-unqualified-lower-maps})))))

      (testing "a reader sees the corrections with their authors"
        (let [resp (detail bob)
              final (first (get-in resp [:body :transcripts :final]))]
          (is (= 200 (:status resp)))
          (is (= 7 (get-in final [:corrections :head_seq])))
          (is (= {:display_name "Alice" :is_me false} (get-in final [:corrections :items 0 :author])))
          (is (= "Let's go through the Q-four numbers" (get-in final [:segments 0 :text]))
              "segments stay the machine output")
          (is (nil? (:corrections (first (get-in resp [:body :transcripts :refined])))))))

      (testing "export uses the corrected transcript; version=original the machine output"
        (is (= (str "[00:00:00] Miro: Zero\n"
                    "[00:00:02] Petr: Churn in the enterprise tier worries me\n"
                    "[00:00:04] Miro: Two\n")
               (body-string (export bob {"format" "txt"}))))
        (is (= (str "[00:00:00] SPEAKER_00: Let's go through the Q-four numbers\n"
                    "[00:00:02] Anna: Churn in the enterprise tear worries me\n"
                    "[00:00:04] SPEAKER_00: Agreed\n")
               (body-string (export bob {"format" "txt" "version" "original"}))))
        (let [doc (cheshire/parse-string (body-string (export alice {"format" "json"})) true)]
          (is (= "corrected" (:version doc)))
          (is (= (str petr-id) (get-in doc [:segments 1 :speaker_id])))
          (is (= ["Zero"] (mapv :text (get-in doc [:segments 0 :words])))))
        (is (= 400 (:status (export alice {"format" "txt" "version" "latest"})))))

      (testing "deleting the session deletes its corrections"
        (jdbc/execute! ds ["DELETE FROM sessions WHERE id=?" sid])
        (is (zero? (:n (jdbc/execute-one! ds ["SELECT count(*) AS n FROM transcript_corrections"]
                                          {:builder-fn rs/as-unqualified-lower-maps}))))))))
