(ns samuraibff.http.retranscription-test
  "Unit tests (no Docker) for the re-transcription rules: defaults, request
  fields and the access and precondition checks."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [samuraibff.http.retranscription :as retranscription]
   [samuraibff.sessions.file-source :as file-source])
  (:import
   (java.util UUID)))

(def ^:private tenant #uuid "11111111-1111-1111-1111-111111111111")

(def ^:private config
  {:uploads {:max-bytes 4096}
   :final-tracks ["whisperx" "qwen"]
   :s3 {:buckets {:recordings {:bucket "recordings-bucket" :prefix "recordings"}}}})

(def ^:private owner
  {:id (UUID/randomUUID) :tenant-id tenant :type :human :role :member :status :active})

(def ^:private reader
  (assoc owner :id (UUID/randomUUID)))

(def ^:private session
  {:id (UUID/randomUUID) :tenant_id tenant :user_id (:id owner) :status "finished"
   :title "Interview" :visibility "tenant"})

(def ^:private recording
  {:recording_url (str "s3://recordings-bucket/recordings/" tenant "/2026/11/08/x/audio.wav")
   :lang "pt-BR"})

(defn- rejection
  "Return [status message] of the rejection that f throws, or :no-rejection."
  [f]
  (try
    (f)
    :no-rejection
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [status body]} (file-source/rejection-response e)]
        [status (:message body)]))))

(deftest default-title-test
  (is (= "Interview (re-transcribed)" (retranscription/default-title " Interview ")))
  (is (= "Session (re-transcribed)" (retranscription/default-title nil)))
  (is (= "Session (re-transcribed)" (retranscription/default-title "  ")))
  (testing "a long title is shortened so the suffix stays and the limit holds"
    (let [title (retranscription/default-title (apply str (repeat 300 "x")))]
      (is (= 200 (count title)))
      (is (str/ends-with? title " (re-transcribed)")))))

(deftest default-final-tracks-test
  (testing "the source session's tracks are reused while they are configured"
    (is (= ["qwen"] (retranscription/default-final-tracks config {:final_tracks ["qwen"]})))
    (is (= ["whisperx" "qwen"]
           (retranscription/default-final-tracks config "{\"final_tracks\":[\"whisperx\",\"qwen\"]}"))))
  (testing "otherwise the configured defaults apply"
    (is (nil? (retranscription/default-final-tracks config {:final_tracks ["removed"]})))
    (is (nil? (retranscription/default-final-tracks config {:final_tracks []})))
    (is (nil? (retranscription/default-final-tracks config {:final_tracks ["qwen" "qwen"]})))
    (is (nil? (retranscription/default-final-tracks config nil)))
    (is (nil? (retranscription/default-final-tracks config {:final_tracks "qwen"})))))

(deftest request-fields-test
  (let [source (assoc session :stream_controls {:final_tracks ["qwen"]})]
    (testing "an empty body uses every default"
      (is (= {:title "Interview (re-transcribed)" :lang "pt-BR" :final-tracks "qwen" :store-recording "true"}
             (retranscription/request-fields nil source recording config))))
    (testing "explicit values win; an empty language means detect"
      (is (= {:title "Again" :lang "" :final-tracks "whisperx,qwen" :store-recording "false"}
             (retranscription/request-fields {:title "Again" :lang "" :final_tracks ["whisperx" "qwen"]
                                              :store_recording false}
                                             source recording config))))
    (testing "an invalid stored language falls back to detection"
      (is (= "" (:lang (retranscription/request-fields {} source {:lang "English"} config)))))
    (testing "wrong types and an empty track list are rejected"
      (doseq [[body expected] [[{:title 5} [400 "invalid-request"]]
                               [{:lang :en} [400 "invalid-request"]]
                               [{:final_tracks "qwen"} [400 "invalid-request"]]
                               [{:final_tracks [1]} [400 "invalid-request"]]
                               [{:store_recording "yes"} [400 "invalid-request"]]
                               [{:final_tracks []} [400 "invalid-stream-controls"]]]]
        (is (= expected (rejection #(retranscription/request-fields body source recording config)))
            (pr-str body))))
    (testing "the shared field rules apply to the result"
      (doseq [[body expected] [[{:title (apply str (repeat 201 "x"))} [400 "invalid-title"]]
                               [{:lang "en; rm -rf /"} [400 "invalid-lang"]]
                               [{:final_tracks ["unknown"]} [400 "invalid-stream-controls"]]
                               [{:final_tracks ["whisperx" "qwen"] :store_recording false}
                                [400 "invalid-stream-controls"]]]]
        (is (= expected (rejection #(file-source/validate-fields
                                     config (retranscription/request-fields body source recording config))))
            (pr-str body))))
    (testing "a client cannot set the session source through the body"
      (is (= #{:title :lang :final-tracks :store-recording}
             (set (keys (retranscription/request-fields {:source "upload" :parent_session_id "x"}
                                                        source recording config))))))))

(deftest unavailable-reason-test
  (testing "the owner of a finished session with stored audio may re-transcribe"
    (is (nil? (retranscription/unavailable-reason config owner session recording)))
    (is (nil? (retranscription/unavailable-reason config owner (assoc session :status "failed") recording))
        "a failed session with audio"))
  (testing "a reader of a shared session may not copy its audio"
    (is (= [403 "forbidden"] (retranscription/unavailable-reason config reader session recording))))
  (testing "a principal that cannot record may not re-transcribe"
    (is (= [403 "forbidden"]
           (retranscription/unavailable-reason config (assoc owner :type :device) session recording))))
  (testing "file uploads must be enabled"
    (is (= [503 "uploads-disabled"]
           (retranscription/unavailable-reason (assoc-in config [:uploads :max-bytes] 0) owner session recording))))
  (testing "the session input must be complete"
    (doseq [status ["created" "active" nil]]
      (is (= [409 "session-not-finished"]
             (retranscription/unavailable-reason config owner (assoc session :status status) recording))
          (pr-str status))))
  (testing "only stored audio in the tenant's part of the recordings bucket can be copied"
    (doseq [rec [nil
                 {:recording_url "file:///data/recordings/a.wav"}
                 {:recording_url (str "s3://other-bucket/recordings/" tenant "/a.wav")}
                 {:recording_url "s3://recordings-bucket/recordings/22222222-2222-2222-2222-222222222222/a.wav"}]]
      (is (= [409 "recording-not-available"] (retranscription/unavailable-reason config owner session rec))
          (pr-str rec)))))

(deftest handler-without-database-test
  (is (= [503 "db-unavailable"]
         (let [resp ((retranscription/retranscribe-handler {:config config :db {}})
                     {:path-params {:session_id (str (UUID/randomUUID))}})]
           [(:status resp) (get-in resp [:body :message])]))))
