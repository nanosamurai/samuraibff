(ns samuraibff.http.retranscription-integration-test
  "Integration tests for POST /api/recordings/{session_id}/retranscribe and
  the re-transcription fields of the session detail.

  Uses a Postgres testcontainer (bound to 127.0.0.1), an in-memory S3 client
  and stubbed Kafka producer functions."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [samuraibff.db.sessions :as db.sessions]
   [samuraibff.http.recordings :as http.recordings]
   [samuraibff.http.retranscription :as http.retranscription]
   [samuraibff.kafka.producer :as kafka.producer]
   [samuraibff.s3.client :as s3.client]
   [samuraibff.test-support.fake-s3 :as fake-s3]
   [samuraibff.test-support.principals :as tp]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.util UUID)))

(def ^:private base-config
  {:env :test
   :auth {:required? false}
   :uploads {:max-bytes 4096}
   :final-tracks ["whisperx" "qwen"]
   :s3 {:buckets {:recordings {:bucket "recordings-bucket" :prefix "recordings"}}}})

(def ^:private row-opts {:builder-fn rs/as-unqualified-lower-maps})

(defn- insert-session!
  "Insert a session row. Options: :owner principal, :status, :title,
  :visibility, :controls map, :settings map. Returns the session UUID."
  [ds tenant {:keys [owner status title visibility controls settings]
              :or {status "finished" visibility "private"}}]
  (let [id (UUID/randomUUID)]
    (jdbc/execute! ds (sql/format {:insert-into :sessions
                                   :values [(cond-> {:id id :tenant_id tenant :user_id (:id owner)
                                                     :session_key (str id) :status status :title title
                                                     :visibility visibility}
                                              controls (assoc :stream_controls
                                                              [:cast (cheshire/generate-string controls) :jsonb])
                                              settings (assoc :session_settings
                                                              [:cast (cheshire/generate-string settings) :jsonb]))]}))
    id))

(defn- insert-recording!
  [ds session-id url lang]
  (jdbc/execute! ds (sql/format {:insert-into :recordings
                                 :values [{:id (UUID/randomUUID) :session_id session-id :recording_url url
                                           :duration_s 12.0 :sample_rate 16000 :lang lang}]})))

(defn- session-row
  [ds id]
  (jdbc/execute-one! ds ["SELECT user_id, status, title, visibility, stream_controls::text AS controls,
                                 session_settings::text AS settings
                            FROM sessions WHERE id = ?" (if (string? id) (UUID/fromString id) id)]
                     row-opts))

(defn- message
  [resp]
  [(:status resp) (get-in resp [:body :message])])

(deftest retranscribe-integration-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          tenant #uuid "11111111-1111-1111-1111-111111111111"
          other-tenant #uuid "22222222-2222-2222-2222-222222222222"
          _ (tp/insert-tenant! ds tenant "Acme")
          _ (tp/insert-tenant! ds other-tenant "Other")
          alice (tp/insert-principal! ds {:tenant-id tenant :name "Alice"})
          bob (tp/insert-principal! ds {:tenant-id tenant :name "Bob"})
          dave (tp/insert-principal! ds {:tenant-id other-tenant :name "Dave"})
          rec-key (str "recordings/" tenant "/2026/11/08/source/audio.wav")
          rec-url (str "s3://recordings-bucket/" rec-key)
          source (insert-session! ds tenant {:owner alice :title "Interview" :visibility "tenant"
                                             :controls {:final true :final_tracks ["qwen"] :store_recording true}})
          _ (insert-recording! ds source rec-url "pt-BR")
          private-source (insert-session! ds tenant {:owner alice :title "Private board meeting"})
          _ (insert-recording! ds private-source rec-url "en")
          s3 (fake-s3/fake-s3 {:objects {rec-key (.getBytes "RIFF....WAVE" "US-ASCII")}})
          published (atom [])
          meta-sent (atom [])
          deps {:config base-config :db {:ds ds} :kafka-producer {:producer (Object.)}}
          call (fn [deps principal session-id body]
                 (with-redefs [s3.client/build-s3-client (constantly (:client s3))
                               kafka.producer/send-sessions-meta!
                               (fn [_ sid meta _] (swap! meta-sent conj [sid meta]))
                               kafka.producer/send-recording-uploaded!
                               (fn [_ event headers]
                                 (swap! published conj [event (update-vals headers #(String. ^bytes % "UTF-8"))]))]
                   ((http.retranscription/retranscribe-handler deps)
                    (tp/request-for principal {:path-params {:session_id (str session-id)}
                                               :parameters {:body body}}))))
          detail (fn [principal session-id]
                   ((http.recordings/get-recording-handler {:db {:ds ds} :config base-config})
                    (tp/request-for principal {:path-params {:session_id (str session-id)}})))
          copies #(count (:copies @(:state s3)))]

      (testing "the owner gets a new session from a copy of the stored audio"
        (let [resp (call deps alice source nil)
              sid (get-in resp [:body :session_id])
              row (session-row ds sid)
              dest-key (str "recordings/" tenant "/uploads/" sid "/source")
              [event headers] (last @published)]
          (is (= 202 (:status resp)))
          (is (= {:session_id sid :title "Interview (re-transcribed)" :retranscribed_from (str source)}
                 (:body resp)))
          (is (= {:source-bucket "recordings-bucket" :source-key rec-key :bucket "recordings-bucket" :key dest-key}
                 (last (:copies @(:state s3)))) "copied inside S3 to the upload source key")
          (is (contains? (:objects @(:state s3)) rec-key) "the source audio stays")
          (is (= (:id alice) (:user_id row)))
          (is (= "private" (:visibility row)) "the caller's default audience, not the source shares")
          (is (= "finished" (:status row)))
          (is (= {:source "retranscription" :parent_session_id (str source)}
                 (cheshire/parse-string (:settings row) true)))
          (is (= {:final true :realtime false :refined false :final_tracks ["qwen"] :store_recording true}
                 (select-keys (cheshire/parse-string (:controls row) true)
                              [:final :realtime :refined :final_tracks :store_recording]))
              "the source session's tracks are the default")
          (is (= {:session-id sid :tenant-id (str tenant) :lang "pt-BR"
                  :recording-url (str "s3://recordings-bucket/" dest-key)}
                 event))
          (is (= {"x-outputs" "final" "x-final-tracks" "qwen" "x-store-recording" "true"}
                 (select-keys headers ["x-outputs" "x-final-tracks" "x-store-recording"])))
          (is (= sid (first (last @meta-sent))) "sessions.meta is published for webhooks and workflows")
          (is (= {:status "finished" :title "Interview"}
                 (select-keys (session-row ds source) [:status :title])) "the source session does not change")
          (let [audit (jdbc/execute-one! ds ["SELECT actor_user_id, actor_kind, target_id, details::text AS details
                                                FROM access_audit_events WHERE action = 'session.retranscribed'"]
                                         row-opts)]
            (is (= [(:id alice) "user" sid] ((juxt :actor_user_id :actor_kind :target_id) audit)))
            (is (= {:parent_session_id (str source) :final_tracks ["qwen"]}
                   (cheshire/parse-string (:details audit) true)))
            (is (not (str/includes? (:details audit) "Interview")) "no titles in the audit event"))
          (testing "and the new session can fail like an upload"
            (let [extra (get-in (call deps alice source nil) [:body :session_id])]
              (is (= {:updated? true} (db.sessions/fail-upload-session! ds tenant (UUID/fromString extra))))
              (is (= "failed" (:status (session-row ds extra))))))))

      (testing "explicit fields are used"
        (let [resp (call deps alice source {:title " Again " :lang "" :final_tracks ["whisperx" "qwen"]
                                            :store_recording true})
              [event headers] (last @published)]
          (is (= 202 (:status resp)))
          (is (= "Again" (get-in resp [:body :title])))
          (is (= "" (:lang event)))
          (is (= "whisperx,qwen" (get headers "x-final-tracks")))))

      (testing "invalid fields are rejected before anything is copied"
        (let [before (copies)]
          (doseq [[body expected] [[{:title (apply str (repeat 201 "x"))} [400 "invalid-title"]]
                                   [{:lang "english"} [400 "invalid-lang"]]
                                   [{:final_tracks []} [400 "invalid-stream-controls"]]
                                   [{:final_tracks ["unknown"]} [400 "invalid-stream-controls"]]]]
            (is (= expected (message (call deps alice source body))) (pr-str body)))
          (is (= before (copies)))))

      (testing "a caller who cannot read the session gets not found"
        (is (= [404 "not-found"] (message (call deps dave source nil))) "another tenant")
        (is (= [404 "not-found"] (message (call deps bob private-source nil))) "a private session")
        (is (= [404 "not-found"] (message (call deps alice (UUID/randomUUID) nil))) "no such session")
        (is (= [400 "invalid-session-id"] (message (call deps alice "nope" nil)))))

      (testing "a reader of a shared session cannot copy its audio"
        (let [before (copies)]
          (is (= [403 "forbidden"] (message (call deps bob source nil))))
          (is (= before (copies)))))

      (testing "a principal that cannot record is refused"
        (let [own (insert-session! ds tenant {:owner bob :title "Bob"})]
          (insert-recording! ds own rec-url "en")
          (is (= [403 "forbidden"] (message (call deps (assoc bob :type :device) own nil))))))

      (testing "preconditions"
        (let [active (insert-session! ds tenant {:owner alice :status "active"})
              created (insert-session! ds tenant {:owner alice :status "created"})
              no-audio (insert-session! ds tenant {:owner alice})
              local-file (insert-session! ds tenant {:owner alice})
              foreign-bucket (insert-session! ds tenant {:owner alice})
              foreign-tenant (insert-session! ds tenant {:owner alice})
              gone (insert-session! ds tenant {:owner alice})
              before (copies)]
          (insert-recording! ds active rec-url "en")
          (insert-recording! ds local-file "file:///data/recordings/a.wav" "en")
          (insert-recording! ds foreign-bucket (str "s3://other-bucket/" rec-key) "en")
          (insert-recording! ds foreign-tenant (str "s3://recordings-bucket/recordings/" other-tenant "/a.wav") "en")
          (insert-recording! ds gone (str "s3://recordings-bucket/recordings/" tenant "/deleted.wav") "en")
          (is (= [409 "session-not-finished"] (message (call deps alice active nil))))
          (is (= [409 "session-not-finished"] (message (call deps alice created nil))))
          (doseq [id [no-audio local-file foreign-bucket foreign-tenant]]
            (is (= [409 "recording-not-available"] (message (call deps alice id nil)))))
          (is (= before (copies)) "nothing is copied")
          (let [sessions-before (:count (jdbc/execute-one! ds ["SELECT count(*) AS count FROM sessions"] row-opts))]
            (is (= [409 "recording-not-available"] (message (call deps alice gone nil)))
                "the stored object no longer exists")
            (is (= sessions-before (:count (jdbc/execute-one! ds ["SELECT count(*) AS count FROM sessions"] row-opts)))
                "no session is created"))
          (is (= [503 "uploads-disabled"]
                 (message (call (assoc-in deps [:config :uploads :max-bytes] 0) alice source nil))))
          (is (= [503 "kafka-unavailable"] (message (call (assoc deps :kafka-producer {}) alice source nil))))
          (is (= before (copies)))))

      (testing "when Kafka does not acknowledge, the copy is deleted and the session fails"
        (with-redefs [kafka.producer/send-sessions-meta! (fn [& _] nil)
                      kafka.producer/send-recording-uploaded! (fn [& _] (throw (RuntimeException. "broker down")))
                      s3.client/build-s3-client (constantly (:client s3))]
          (let [resp ((http.retranscription/retranscribe-handler deps)
                      (tp/request-for alice {:path-params {:session_id (str source)}}))
                {:keys [key]} (last (:copies @(:state s3)))
                failed (jdbc/execute-one! ds ["SELECT status FROM sessions
                                                WHERE session_settings->>'parent_session_id' = ?
                                                ORDER BY created_at DESC LIMIT 1" (str source)] row-opts)]
            (is (= [503 "kafka-unavailable"] (message resp)))
            (is (not (contains? (:objects @(:state s3)) key)) "the copy is deleted")
            (is (some #{key} (:deleted @(:state s3))))
            (is (= "failed" (:status failed))))))

      (testing "when sessions.meta cannot be published, the copy is deleted and the session fails"
        (with-redefs [kafka.producer/send-sessions-meta! (fn [& _] (throw (RuntimeException. "broker down")))
                      s3.client/build-s3-client (constantly (:client s3))]
          (let [resp ((http.retranscription/retranscribe-handler deps)
                      (tp/request-for alice {:path-params {:session_id (str source)}}))
                {:keys [key]} (last (:copies @(:state s3)))]
            (is (= [500 "retranscribe-failed"] (message resp)))
            (is (not (contains? (:objects @(:state s3)) key))))))

      (testing "session detail fields"
        (let [child (get-in (call deps alice source {:title "Child"}) [:body :session_id])
              shared-child (insert-session! ds tenant {:owner alice :title "Shared child" :visibility "tenant"
                                                       :settings {:source "retranscription"
                                                                  :parent_session_id (str source)}})
              orphan (insert-session! ds tenant {:owner alice :title "Of a private session" :visibility "tenant"
                                                 :settings {:source "retranscription"
                                                            :parent_session_id (str private-source)}})
              child-detail (get-in (detail alice child) [:body :session])
              source-detail (get-in (detail alice source) [:body :session])
              bob-source (get-in (detail bob source) [:body :session])
              bob-orphan (get-in (detail bob orphan) [:body :session])
              no-audio (insert-session! ds tenant {:owner alice})]
          (is (= "retranscription" (:source child-detail)))
          (is (= {:id (str source) :title "Interview"} (:retranscribed_from child-detail)))
          (is (false? (:can_retranscribe child-detail)) "the child has no recording yet")
          (is (true? (:can_retranscribe source-detail)))
          (is (nil? (:retranscribe_unavailable source-detail)))
          (is (= (str shared-child) (:id (first (:retranscriptions source-detail)))) "newest first")
          (is (some #(= child (:id %)) (:retranscriptions source-detail)))
          (is (every? #(= "retranscription" (:source %)) (:retranscriptions source-detail)))
          (is (<= (count (:retranscriptions source-detail)) 20))
          (testing "a reader sees only re-transcriptions it may read and cannot re-transcribe"
            (is (= [(str shared-child)] (mapv :id (:retranscriptions bob-source))))
            (is (false? (:can_retranscribe bob-source)))
            (is (nil? (:retranscribe_unavailable bob-source))))
          (testing "the source of a re-transcription stays hidden when it is not readable"
            (is (= "retranscription" (:source bob-orphan)))
            (is (not (contains? bob-orphan :retranscribed_from)))
            (is (not (str/includes? (pr-str (detail bob orphan)) "Private board meeting"))))
          (testing "a manager learns when no usable audio was kept"
            (let [d (get-in (detail alice no-audio) [:body :session])]
              (is (false? (:can_retranscribe d)))
              (is (= "recording-not-available" (:retranscribe_unavailable d)))))
          (testing "the detail response matches its schema"
            (is (= 200 (:status (detail alice source))))))))))
