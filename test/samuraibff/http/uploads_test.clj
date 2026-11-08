(ns samuraibff.http.uploads-test
  "Tests for POST /api/sessions/upload.

  Unit tests (no Docker) use an in-memory S3 client and stop before the
  database. The integration test uses a Postgres testcontainer and stubs the
  Kafka producer functions."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [samuraibff.access.principal :as access.principal]
   [samuraibff.db.api-credentials :as db.api-creds]
   [samuraibff.db.principals :as db.principals]
   [samuraibff.http.recordings :as http.recordings]
   [samuraibff.http.uploads :as http.uploads]
   [samuraibff.kafka.producer :as kafka.producer]
   [samuraibff.s3.client :as s3.client]
   [samuraibff.system]
   [samuraibff.test-support.fake-s3 :as fake-s3]
   [samuraibff.test-support.principal-store :as store]
   [samuraibff.test-support.principals :as tp]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.io ByteArrayInputStream ByteArrayOutputStream)
   (java.util UUID)))

(def ^:private boundary "nanosamuraiTestBoundary")

(def ^:private wav-head
  "Leading bytes of a WAV file."
  (.getBytes "RIFF\u0000\u0000\u0000\u0000WAVEfmt " "ISO-8859-1"))

(defn- media
  "Return n bytes that start like a WAV file."
  ^bytes [n]
  (let [out (byte-array n (byte 1))]
    (System/arraycopy wav-head 0 out 0 (alength ^bytes wav-head))
    out))

(defn- multipart-body
  "Build a multipart/form-data body.

  parts: sequence of [name value] where value is a string (text field) or
  bytes (file part named upload.bin). Option :truncate drops the closing
  boundary and the last n bytes, like a cancelled upload."
  ^bytes [parts & {:keys [truncate]}]
  (let [out (ByteArrayOutputStream.)
        w (fn [^String s] (.write out (.getBytes s "UTF-8")))]
    (doseq [[name value] parts]
      (w (str "--" boundary "\r\n"))
      (if (bytes? value)
        (do (w (str "Content-Disposition: form-data; name=\"" name "\"; filename=\"../../etc/passwd\"\r\n"
                    "Content-Type: application/pdf\r\n\r\n"))
            (.write out ^bytes value))
        (do (w (str "Content-Disposition: form-data; name=\"" name "\"\r\n\r\n"))
            (w value)))
      (w "\r\n"))
    (w (str "--" boundary "--\r\n"))
    (let [body (.toByteArray out)]
      (if truncate
        (java.util.Arrays/copyOf body (- (alength body) (+ truncate (count boundary) 8)))
        body))))

(defn- upload-request
  "Return a Ring request for principal with a multipart body."
  [principal ^bytes body]
  (tp/request-for principal
                  {:request-method :post
                   :uri "/api/sessions/upload"
                   :headers {"content-type" (str "multipart/form-data; boundary=" boundary)
                             "content-length" (str (alength body))}
                   :body (ByteArrayInputStream. body)}))

(def ^:private base-config
  {:auth {:required? false}
   :uploads {:max-bytes 4096}
   :final-tracks ["whisperx" "qwen"]
   :s3 {:buckets {:recordings {:bucket "recordings-bucket" :prefix "recordings"}}}})

(def ^:private guest
  (store/row->principal (store/guest-row)))

(defn- call
  "Run the handler with a fake S3 client. Returns [response fake-s3-state]."
  [deps req]
  (let [{:keys [client state]} (fake-s3/fake-s3)]
    (with-redefs [s3.client/build-s3-client (constantly client)]
      [((http.uploads/upload-session-handler deps) req) @state])))

(deftest upload-rejections-unit-test
  (let [deps {:config base-config
              :db {:ds (Object.)}
              :kafka-producer {:producer (Object.)}}
        message (fn [[resp]] [(:status resp) (get-in resp [:body :message])])]
    (testing "an anonymous caller may not upload"
      (is (= [403 "forbidden"] (message (call deps (upload-request nil (multipart-body [["file" (media 100)]])))))))

    (testing "uploads are off without a size limit, a bucket, a database or Kafka"
      (doseq [deps' [(assoc-in deps [:config :uploads :max-bytes] 0)
                     (assoc-in deps [:config :s3 :buckets :recordings :bucket] "")]]
        (is (= [503 "uploads-disabled"] (message (call deps' (upload-request guest (multipart-body [["file" (media 100)]])))))))
      (is (= [503 "db-unavailable"] (message (call (assoc deps :db {}) (upload-request guest (multipart-body [["file" (media 100)]]))))))
      (is (= [503 "kafka-unavailable"] (message (call (assoc deps :kafka-producer {}) (upload-request guest (multipart-body [["file" (media 100)]])))))))

    (testing "malformed requests store nothing"
      (doseq [[body expected] [[(multipart-body [["title" "no file"]]) [400 "missing-file"]]
                               [(multipart-body [["file" (byte-array 0)]]) [400 "empty-file"]]
                               [(multipart-body [["file" (.getBytes "%PDF-1.7 not audio" "UTF-8")]]) [415 "unsupported-media-type"]]
                               [(multipart-body [["file" (media 5000)]]) [413 "file-too-large"]]
                               [(multipart-body [["file" (media 100)] ["other" (media 100)]]) [400 "multiple-files"]]]]
        (let [[resp state] (call deps (upload-request guest body))]
          (is (= expected (message [resp])))
          (is (empty? (:objects state)) (str expected)))))

    (testing "a request that is not multipart is rejected"
      (let [[resp] (call deps (tp/request-for guest {:headers {"content-type" "application/json"}
                                                     :body (ByteArrayInputStream. (.getBytes "{}" "UTF-8"))}))]
        (is (= [400 "missing-file"] (message [resp])))))

    (testing "a cancelled upload (body ends mid-file) stores nothing"
      (let [[resp state] (call deps (upload-request guest (multipart-body [["file" (media 2000)]] :truncate 500)))]
        (is (= 400 (:status resp)))
        (is (empty? (:objects state)))))

    (testing "invalid fields delete the stored file"
      (doseq [[fields expected] [[[["lang" "en; rm -rf /"]] "invalid-lang"]
                                 [[["title" (apply str (repeat 201 "x"))]] "invalid-title"]
                                 [[["final_tracks" "unknown"]] "invalid-stream-controls"]
                                 [[["final_tracks" "whisperx,qwen"] ["store_recording" "false"]] "invalid-stream-controls"]]]
        (let [[resp state] (call deps (upload-request guest (multipart-body (concat [["file" (media 100)]] fields))))]
          (is (= [400 expected] (message [resp])))
          (is (empty? (:objects state)) expected)
          (is (= 1 (count (:deleted state))) expected))))))

(defn- insert-org-key!
  "Insert an organization API key with the given default audience. Returns its resolved principal."
  [ds tenant manager audience]
  (let [service (tp/insert-principal! ds {:tenant-id tenant :type :service})
        id (UUID/randomUUID)]
    (db.api-creds/insert-credential! ds {:id id
                                         :tenant-id tenant
                                         :name "org-key"
                                         :keycloak-client-id (:client-id service)
                                         :manager-user-id (:id manager)
                                         :service-user-id (:id service)
                                         :kind "organization"
                                         :read-scope "credential"
                                         :default-visibility audience})
    (let [row (db.principals/find-by-id ds tenant (:id service))]
      (:principal (access.principal/resolve-principal
                   {:auth {:required? true :issuer tp/test-issuer :tenant-claim "tenant_id"}}
                   ds {:sub (:external_id row) :raw {:tenant_id (str tenant) :azp (:client_id row)}})))))

(defn- session-row
  [ds session-id]
  (jdbc/execute-one! ds ["SELECT tenant_id, user_id, status, title, visibility, created_via_credential_id,
                                 stream_controls::text AS controls, session_settings::text AS settings
                            FROM sessions WHERE id = ?" (UUID/fromString session-id)]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(deftest upload-session-integration-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          tenant #uuid "11111111-1111-1111-1111-111111111111"
          _ (tp/insert-tenant! ds tenant "Acme")
          carol (tp/insert-principal! ds {:tenant-id tenant :name "Carol" :role :builder})
          org-key (insert-org-key! ds tenant carol "tenant")
          published (atom [])
          meta-sent (atom [])
          deps {:config (assoc base-config :features {:ce-mode? true})
                :db {:ds ds}
                :kafka-producer {:producer (Object.)}}
          upload! (fn [principal fields]
                    (with-redefs [kafka.producer/send-sessions-meta!
                                  (fn [_ sid meta _] (swap! meta-sent conj [sid meta]))
                                  kafka.producer/send-recording-uploaded!
                                  (fn [_ event headers]
                                    (swap! published conj [event (update-vals headers #(String. ^bytes % "UTF-8"))]))]
                      (call deps (upload-request principal (multipart-body (concat [["file" (media 300)]] fields))))))]

      (testing "a person's upload is owned by that person and starts private"
        (let [[resp state] (upload! carol [["title" " Interview "] ["lang" "en"]])
              sid (get-in resp [:body :session_id])
              row (session-row ds sid)
              [event headers] (last @published)]
          (is (= 200 (:status resp)))
          (is (= "Interview" (get-in resp [:body :title])))
          (is (= (:id carol) (:user_id row)))
          (is (= "private" (:visibility row)))
          (is (nil? (:created_via_credential_id row)))
          (is (= "finished" (:status row)) "the input is complete; the transcript follows, and the owner can delete it")
          (is (= {:source "upload"} (cheshire/parse-string (:settings row) true)) "the session is marked as an upload")
          (let [detail ((http.recordings/get-recording-handler {:db {:ds ds} :config {:env :test}})
                        (tp/request-for carol {:path-params {:session_id sid}}))]
            (is (= 200 (:status detail)))
            (is (= "upload" (get-in detail [:body :session :source])) "the detail page can tell an upload"))
          (is (= {:final true :realtime false :refined false :final_tracks ["whisperx"] :store_recording true}
                 (select-keys (cheshire/parse-string (:controls row) true)
                              [:final :realtime :refined :final_tracks :store_recording])))
          (let [key (str "recordings/" tenant "/uploads/" sid "/source")]
            (is (= #{key} (set (keys (:objects state)))) "the file is stored unchanged under the session")
            (is (= {:session-id sid :tenant-id (str tenant) :lang "en"
                    :recording-url (str "s3://recordings-bucket/" key)}
                   event)))
          (is (= {"x-outputs" "final" "x-final-tracks" "whisperx" "x-store-recording" "true"}
                 (select-keys headers ["x-outputs" "x-final-tracks" "x-store-recording"])))
          (is (= sid (first (last @meta-sent))) "sessions.meta is published for webhook routing")
          (is (not (str/includes? (str row) "passwd")) "the client file name is not stored")
          (is (= 200 (:status ((http.recordings/delete-recording-handler {:db {:ds ds}})
                               (tp/request-for carol {:path-params {:session_id sid}}))))
              "the owner can cancel by deleting the session while it is processed")))

      (testing "an organization key's upload is owned by the key with its default audience"
        (let [[resp] (upload! org-key [["final_tracks" "whisperx,qwen"]])
              row (session-row ds (get-in resp [:body :session_id]))
              [_ headers] (last @published)]
          (is (= 200 (:status resp)))
          (is (= (:id org-key) (:user_id row)))
          (is (= (get-in org-key [:credential :id]) (:created_via_credential_id row)))
          (is (= "tenant" (:visibility row)))
          (is (= "whisperx,qwen" (get headers "x-final-tracks")))))

      (testing "when Kafka does not acknowledge, the session fails and the file is deleted"
        (with-redefs [kafka.producer/send-sessions-meta! (fn [& _] nil)
                      kafka.producer/send-recording-uploaded! (fn [& _] (throw (RuntimeException. "broker down")))]
          (let [before (count (jdbc/execute! ds ["SELECT id FROM sessions WHERE status='failed'"]))
                [resp state] (call deps (upload-request carol (multipart-body [["file" (media 300)]])))]
            (is (= [503 "kafka-unavailable"] [(:status resp) (get-in resp [:body :message])]))
            (is (empty? (:objects state)))
            (is (= (inc before) (count (jdbc/execute! ds ["SELECT id FROM sessions WHERE status='failed'"]))))))))))

(deftest upload-env-overrides-test
  (testing "operators set the upload limit and topic through environment variables"
    (let [env {"SAMURAIBFF_UPLOAD_MAX_BYTES" "0"
               "SAMURAIBFF_KAFKA_TOPIC_RECORDINGS_UPLOADED" "custom.uploaded"}
          cfg (:samuraibff/config (#'samuraibff.system/apply-env-overrides
                                   {:samuraibff/config {:uploads {:max-bytes 134217728}}}
                                   env))]
      (is (= 0 (get-in cfg [:uploads :max-bytes])) "0 disables uploads")
      (is (= "custom.uploaded" (get-in cfg [:kafka :topics :recordings-uploaded]))))))
