;; Copyright (c) samuraibff contributors.
(ns samuraibff.http.speaker-enrollment
  "HTTP handler for creating enrolled speakers from an existing recording.

  Endpoint:
  - POST /api/speaker-enrollment/from-recording

  Behavior:
  - Loads the tenant-scoped latest recording for the session.
  - Clips a short WAV segment from the recording (file:// or s3://).
  - Uploads the clip into the enrollments S3 bucket and stores metadata in Postgres.

  Security:
  - Requires auth via router middleware.
  - Ensures the session belongs to the authenticated tenant (via recordings query).
  - Enforces configured allowlists:
    - file:// must be under [:recordings :local-root]
    - s3:// must be in allowlisted recordings bucket [:s3 :buckets :recordings :bucket] when set

  This namespace is intentionally separate from `samuraibff.http.speakers` to
  keep the multipart upload handler simpler and avoid turning it into a god namespace."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [samuraibff.audio.wav :as audio.wav]
   [samuraibff.db.recordings :as db.recordings]
   [samuraibff.db.sessions :as db.sessions]
   [samuraibff.db.speakers :as db.speakers]
   [samuraibff.s3.client :as s3.client]
   [samuraibff.s3.enrollment :as s3.enrollment]
   [samuraibff.schemas :as schemas]
   [samuraibff.util.uuid :as uuid])
  (:import
   (java.io File)
   (java.net URI)
   (java.util UUID)
   (javax.sql DataSource)
   (software.amazon.awssdk.services.s3 S3Client)))

(def ^:private default-max-duration-s
  "Maximum clip duration for speaker enrollment from recording.

  This prevents accidentally uploading very long transcript bubbles."
  10.0)

(defn- json-response
  "Return a JSON response map.

  Inputs:
  - status int
  - body map

  Returns: Ring response map."
  [status body]
  {:status status
   :body body})

(defn- ensure-tenant-uuid!
  "Extract tenant id from request and convert to UUID.

  Inputs:
  - req: Ring request

  Returns: UUID

  Throws:
  - ex-info when missing/invalid."
  [req]
  (let [tid (or (:auth/tenant-id req)
                (get-in req [:auth :tenant-id]))]
    (when (str/blank? (str tid))
      (throw (ex-info "missing-tenant-id" {:type :samuraibff.http/missing-tenant-id})))
    (try
      (UUID/fromString (str tid))
      (catch Exception e
        (throw (ex-info "invalid-tenant-id" {:type :samuraibff.http/invalid-tenant-id
                                             :tenant-id tid}
                        e))))))

(defn- recordings-s3-allow-bucket
  "Return the configured allowlist bucket for reading recordings.

  Returns:
  - string? (blank => nil)."
  [config]
  (let [b0 (some-> (get-in config [:s3 :buckets :recordings :bucket]) str str/trim)]
    (when (seq b0) b0)))

(defn- parse-s3-url
  "Parse an s3:// URL into {:bucket :key}.

  Inputs:
  - s: string (s3://bucket/key)

  Returns:
  - {:bucket string :key string} or nil."
  [s]
  (let [s (some-> s str str/trim)]
    (when (and (seq s) (str/starts-with? s "s3://"))
      (let [uri (URI/create s)
            bucket (.getHost uri)
            path (some-> (.getPath uri) (str/replace #"^/+" ""))]
        (when (and (seq bucket) (seq path))
          {:bucket bucket :key path})))))

(defn- safe-file-under-root
  "Resolve a file:// URL into a File under configured root.

  Inputs:
  - root-path string
  - file-url string

  Returns:
  - java.io.File

  Throws:
  - ex-info when root missing, URL invalid, or path traversal attempt."
  [root-path file-url]
  (when-not (seq (str root-path))
    (throw (ex-info "recordings-local-root-not-configured"
                    {:type :samuraibff.http/recordings-local-root-not-configured})))
  (let [root (-> (io/file root-path) .getCanonicalFile)
        uri (try
              (URI/create (str file-url))
              (catch Exception e
                (throw (ex-info "invalid-recording-url"
                                {:type :samuraibff.http/invalid-recording-url
                                 :recording-url file-url}
                                e))))
        _ (when-not (= "file" (.getScheme uri))
            (throw (ex-info "invalid-recording-url"
                            {:type :samuraibff.http/invalid-recording-url
                             :recording-url file-url})))
        f (-> (File. uri) .getCanonicalFile)
        root-path* (.getPath root)
        file-path (.getPath f)
        allowed? (or (= root-path* file-path)
                     (.startsWith file-path (str root-path* File/separator)))]
    (when-not allowed?
      (throw (ex-info "recording-path-outside-root"
                      {:type :samuraibff.http/recording-path-outside-root
                       :root root-path*
                       :path file-path})))
    f))

(defn- resolve-user-id
  "Resolve app_users.id for the current request.

  Inputs:
  - ds: datasource
  - tenant-id: UUID
  - req: Ring request

  Returns: UUID?"
  [ds tenant-id req]
  (let [external-id (some-> req :auth/user :sub)]
    (when (and ds (seq (str external-id)))
      (db.sessions/find-user-id-by-external-id ds tenant-id external-id))))

(defn create-speaker-from-recording-handler
  "Create an enrolled speaker from a segment of an existing recording.

  Dependencies:
  - deps: {:config config :db {:ds DataSource}}

  Request body (JSON):
  - {:session_id <uuid-string>
     :start_s <double>
     :end_s <double>
     :label <string>}

  Response:
  - 200 with speaker metadata + effective clip window
  - 400/403/404 on validation errors
  - 503 when DB unavailable
  - 500 on unexpected errors."
  [{:keys [config db]}]
  (fn [req]
    (let [^DataSource ds (:ds db)]
      (try
        (when-not ds
          (log/error "DB datasource missing; cannot enroll speaker" {:uri (:uri req)})
          (throw (ex-info "missing-datasource" {:type :samuraibff.http/missing-datasource})))

        (let [body (or (:body-params req) (:body req) {})
              coerced (schemas/decode-and-validate! schemas/CreateSpeakerFromRecordingRequest body)
              label (audio.wav/sanitize-label (:label coerced))
              tenant-id (ensure-tenant-uuid! req)
              session-uuid (UUID/fromString (str (:session_id coerced)))
              recording (db.recordings/find-latest-recording ds tenant-id session-uuid)
              recording-url (some-> (:recording_url recording) str)
              max-d default-max-duration-s
              clip-opts {:start_s (:start_s coerced)
                         :end_s (:end_s coerced)
                         :max_duration_s max-d}
              user-uuid (resolve-user-id ds tenant-id req)]

          (cond
            (nil? recording)
            (json-response 404 {:ok false :message "recording-not-found"})

            (str/blank? recording-url)
            (json-response 404 {:ok false :message "recording-url-missing"})

            :else
            (let [wav-result
                  (cond
                    (= "file" (some-> (URI/create recording-url) (.getScheme)))
                    (let [root (get-in config [:recordings :local-root])
                          f (safe-file-under-root root recording-url)]
                      (audio.wav/clip-wav-file f clip-opts))

                    (= "s3" (some-> (URI/create recording-url) (.getScheme)))
                    (let [{:keys [bucket key]} (parse-s3-url recording-url)
                          allow-bucket (recordings-s3-allow-bucket config)]
                      (when-not (and (seq bucket) (seq key))
                        (throw (ex-info "invalid-recording-url"
                                        {:type :samuraibff.http/invalid-recording-url
                                         :recording-url recording-url})))
                      (when (and allow-bucket (not= allow-bucket bucket))
                        (throw (ex-info "recording-bucket-not-allowed"
                                        {:type :samuraibff.http/recording-bucket-not-allowed
                                         :bucket bucket
                                         :allowed allow-bucket})))
                      (let [^S3Client s3 (s3.client/build-s3-client config)]
                        (try
                          (audio.wav/clip-wav-s3 s3 bucket key clip-opts)
                          (finally
                            (.close s3)))))

                    :else
                    (throw (ex-info "unsupported-recording-url"
                                    {:type :samuraibff.http/unsupported-recording-url
                                     :recording-url recording-url})))

                  speaker-id (uuid/uuid7)
                  sample-id (uuid/uuid7)
                  sample-bytes (:wav-bytes wav-result)
                  clip (:clip wav-result)
                  enroll-s3 (s3.enrollment/build-s3-client config)]

              (try
                (log/info "Creating speaker from recording"
                          {:tenant_id (str tenant-id)
                           :user_id (some-> req :auth/user :sub str)
                           :session_id (str session-uuid)
                           :speaker_id (str speaker-id)
                           :sample_id (str sample-id)
                           :label label
                           :start_s (:start_s clip)
                           :end_s (:end_s clip)
                           :sample_bytes (alength ^bytes sample-bytes)})
                (let [{:keys [sample-url manifest-url]} (s3.enrollment/put-speaker!
                                                         enroll-s3 config tenant-id speaker-id label sample-id sample-bytes)
                      _ (db.speakers/insert-speaker!
                         ds
                         {:id speaker-id
                          :tenant-id tenant-id
                          :user-id user-uuid
                          :label label
                          :audio-url manifest-url})
                      body {:ok true
                            :speaker_id (str speaker-id)
                            :tenant_id (str tenant-id)
                            :label label
                            :sample_url sample-url
                            :manifest_url manifest-url
                            :clip {:session_id (str session-uuid)
                                   :start_s (:start_s clip)
                                   :end_s (:end_s clip)
                                   :max_duration_s max-d
                                   :truncated? (boolean (:truncated? clip))}}]
                  (when (#{:dev :test} (:env config))
                    (schemas/validate! schemas/CreateSpeakerFromRecordingResponse body))
                  (json-response 200 body))
                (finally
                  (.close enroll-s3))))))

        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})

              :samuraibff.http/recordings-local-root-not-configured (json-response 503 {:ok false :message "recordings-local-root-not-configured"})
              :samuraibff.http/recording-path-outside-root (json-response 403 {:ok false :message "recording-path-outside-root"})
              :samuraibff.http/invalid-recording-url (json-response 400 {:ok false :message "invalid-recording-url"})
              :samuraibff.http/recording-bucket-not-allowed (json-response 403 {:ok false :message "recording-bucket-not-allowed"})
              :samuraibff.http/unsupported-recording-url (json-response 400 {:ok false :message "unsupported-recording-url"})

              :samuraibff.audio/missing-label (json-response 400 {:ok false :message "missing-label"})
              :samuraibff.audio/label-too-long (json-response 400 {:ok false :message "label-too-long"})
              :samuraibff.audio/invalid-time-window (json-response 400 {:ok false :message "invalid-time-window"})
              :samuraibff.audio/empty-clip (json-response 400 {:ok false :message "empty-clip"})
              :samuraibff.audio/not-a-riff-wav (json-response 400 {:ok false :message "unsupported-wav"})
              :samuraibff.audio/not-a-wave (json-response 400 {:ok false :message "unsupported-wav"})
              :samuraibff.audio/wav-unsupported-audio-format (json-response 400 {:ok false :message "unsupported-wav"})

              (do
                (log/error e "Failed creating speaker from recording")
                (json-response 500 {:ok false :message "internal-error"})))))
        (catch Exception e
          (log/error e "Unexpected error enrolling speaker from recording")
          (json-response 500 {:ok false :message "internal-error"}))))))
