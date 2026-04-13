;; Copyright (c) samuraibff contributors.
(ns samuraibff.http.speaker-enrollment-integration-test
  "Integration tests for enrolling speakers from a stored recording.

  Covered endpoint:
  - POST /api/speaker-enrollment/from-recording

  The test uses LocalStack (S3) + Postgres testcontainers and calls the handler directly." 
  (:require
    [cheshire.core :as cheshire]
    [clojure.test :refer :all]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [ring.mock.request :as mock]
    [samuraibff.http.speaker-enrollment :as http.speaker-enrollment]
    [samuraibff.testcontainers.localstack :as tc.localstack]
    [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
    (java.nio ByteBuffer ByteOrder)
    (java.util UUID)))

(defn- parse-json-body
  [resp]
  (let [body (:body resp)]
    (cond
      (nil? body) nil
      (map? body) body
      (string? body) (cheshire/parse-string body true)
      (instance? java.io.InputStream body) (cheshire/parse-stream (clojure.java.io/reader body) true)
      :else (cheshire/parse-string (str body) true))))

(def ^:private tenant-id
  "00000000-0000-0000-0000-000000000000")

(defn- insert-tenant!
  [ds]
  (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" (UUID/fromString tenant-id) "Tenant"])
  nil)

(defn- build-config
  [localstack]
  (let [{:keys [access-key secret-key region]} (tc.localstack/s3-credentials localstack)
        endpoint (tc.localstack/s3-endpoint localstack)]
    {:env :test
     :auth {:required? true}
     :recordings {:local-root ""}
     :s3 {:region region
          :endpoint endpoint
          :access-key access-key
          :secret-key secret-key
          :force-path-style? true
          :buckets {:enrollments {:bucket "xamurai-enrollment"
                                  :prefix "enrollment"}
                    :recordings {:bucket "xamurai-recordings"
                                 :prefix "recordings"}}}}))

(defn- auth-req
  [req]
  (assoc req :auth/user {:sub "user-1"} :auth/tenant-id tenant-id))

(defn- pcm-wav-bytes
  "Build a canonical PCM WAV byte array.

  We use a very small sample rate so the file stays tiny.

  Inputs:
  - sample-rate int
  - data-bytes int

  Returns: byte-array." 
  [sample-rate data-bytes]
  (let [channels 1
        bits-per-sample 16
        block-align (* channels (quot bits-per-sample 8))
        byte-rate (* sample-rate block-align)
        riff-size (+ 36 data-bytes)
        data (byte-array (range 0 data-bytes))
        ^ByteBuffer bb (doto (ByteBuffer/allocate (+ 44 data-bytes))
                         (.order ByteOrder/LITTLE_ENDIAN))]
    (.put bb (.getBytes "RIFF" "US-ASCII"))
    (.putInt bb (int riff-size))
    (.put bb (.getBytes "WAVE" "US-ASCII"))
    (.put bb (.getBytes "fmt " "US-ASCII"))
    (.putInt bb 16)
    (.putShort bb (short 1))
    (.putShort bb (short channels))
    (.putInt bb (int sample-rate))
    (.putInt bb (int byte-rate))
    (.putShort bb (short block-align))
    (.putShort bb (short bits-per-sample))
    (.put bb (.getBytes "data" "US-ASCII"))
    (.putInt bb (int data-bytes))
    (.put bb ^bytes data)
    (.array bb)))

(deftest enroll-speaker-from-recording-s3-integration-test
  (testing "POST /api/speaker-enrollment/from-recording clips WAV from S3 and stores enrolled speaker"
    (tc.localstack/with-localstack [localstack]
      (tc.pg/with-postgres [pg]
        (let [jdbc-url (tc.pg/jdbc-url pg)
              ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
              _ (tc.pg/apply-schema! ds)
              _ (insert-tenant! ds)

              s3 (tc.localstack/s3-client localstack)
              _ (tc.localstack/create-bucket! s3 "xamurai-enrollment")
              _ (tc.localstack/create-bucket! s3 "xamurai-recordings")

              session-id (UUID/fromString "00000000-0000-0000-0000-000000000010")
              _ (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, session_key, status, created_at) VALUES (?, ?, ?, ?, now())"
                                  session-id (UUID/fromString tenant-id) session-id "active"])

              ;; Create a tiny WAV: sample-rate=10 Hz, 2 bytes per sample => 20 bytes/s.
              ;; With 200 data bytes, duration is 10 seconds.
              wav-bytes (pcm-wav-bytes 10 200)
              key "recordings/test/a.wav"
              _ (tc.localstack/put-object! s3 "xamurai-recordings" key wav-bytes {:content-type "audio/wav"})
              url (str "s3://" "xamurai-recordings" "/" key)
              _ (jdbc/execute! ds ["INSERT INTO recordings (id, session_id, recording_url, duration_s, sample_rate, lang, created_at) VALUES (?, ?, ?, 10.0, 10, 'en', now())"
                                  (UUID/fromString "00000000-0000-0000-0000-000000000200") session-id url])

              deps {:config (build-config localstack)
                    :db {:ds ds}}
              handler (http.speaker-enrollment/create-speaker-from-recording-handler deps)

              ;; Intentionally request a longer window; server should clamp to 10 seconds.
              req (-> (mock/request :post "/api/speaker-enrollment/from-recording")
                      (assoc :body-params {:session_id (str session-id)
                                           :start_s 0.0
                                           :end_s 30.0
                                           :label "Dr Novak"})
                      (auth-req))
              resp (handler req)
              body (parse-json-body resp)
              speaker-id (some-> body :speaker_id UUID/fromString)
              db-row (when speaker-id
                       (jdbc/execute-one! ds
                                         ["SELECT id, label FROM speakers WHERE id=?" speaker-id]
                                         {:builder-fn rs/as-unqualified-lower-maps}))
              s3-keys (tc.localstack/list-objects s3 "xamurai-enrollment" "enrollment/")]
          (try
            (is (= 200 (:status resp)) (pr-str resp))
            (is (= true (:ok body)))
            (is (= "Dr Novak" (:label body)))
            (is (= (str session-id) (get-in body [:clip :session_id])))
            (is (= 0.0 (double (get-in body [:clip :start_s]))))
            (is (= 10.0 (double (get-in body [:clip :end_s]))))
            (is (true? (get-in body [:clip :truncated?])))
            (is (= "Dr Novak" (:label db-row)))
            ;; Ensure we uploaded both manifest and sample.
            ;; NOTE: Regex literals are a bit fiddly with escaping; use character
            ;; classes for literal dots.
            (is (some #(re-find #"speaker[.]json$" %) s3-keys) (pr-str s3-keys))
            (is (some #(re-find #"samples/.+[.]wav$" %) s3-keys) (pr-str s3-keys))
            (finally
              (.close s3))))))))
