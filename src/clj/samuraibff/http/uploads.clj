(ns samuraibff.http.uploads
  "Create a session from an uploaded audio or video file.

  Endpoint:
  - POST /api/sessions/upload (multipart/form-data)

  Flow:
  1. Authorize like `POST /api/sessions` (the principal must be able to record).
  2. Copy the `file` part unchanged to
     `s3://<recordings-bucket>/<prefix>/<tenant_id>/uploads/<session_id>/source`
     with a byte limit. The BFF never decodes the file; it only checks the
     leading bytes against known container signatures.
  3. Create the session with the same ownership and audience as a live session,
     save its stream controls (final output only) and publish `sessions.meta`
     (`samuraibff.sessions.file-source/start-session!`).
  4. Publish `recordings.uploaded` and wait for the broker ack. The recorder
     worker decodes the file and publishes the normal `recordings.finished`.

  Status and results use the existing recording endpoints and the
  `transcript.final.ready` webhook. When the recorder cannot convert the file,
  `samuraibff.kafka.upload-failed-consumer` sets the session to `failed`.

  Security:
  - Client file names and part content types are ignored and never stored.
  - On any failure after the file was stored, the stored object is deleted
    best-effort and no session event is published."
  (:require
   [org.corfield.logging4j2 :as log]
   [ring.middleware.multipart-params :as multipart]
   [samuraibff.access.policy :as policy]
   [samuraibff.db.sessions :as db.sessions]
   [samuraibff.features :as features]
   [samuraibff.s3.client :as s3.client]
   [samuraibff.s3.uploads :as s3.uploads]
   [samuraibff.sessions.file-source :as file-source]
   [samuraibff.util.uuid :as uuid])
  (:import
   (org.apache.commons.fileupload2.core FileUploadException FileUploadSizeException)))

(def ^:private max-form-items
  "File part plus the optional text fields, with room for clients that send extras."
  8)

(defn- error
  "Return a JSON error response map."
  [status message]
  {:status status :body {:ok false :message message}})

(defn- fail!
  "Throw an upload failure that maps to an HTTP status and message."
  [status message]
  (file-source/reject! status message))

(defn- parse-fields
  "Validate the optional text fields of an upload.

  Inputs: config map, multipart params map (string keys).
  Returns: see `file-source/validate-fields`. Throws an upload failure (400)
  for invalid values."
  [config params]
  (let [field #(let [v (get params %)] (when (string? v) v))]
    (file-source/validate-fields config {:title (field "title")
                                         :lang (field "lang")
                                         :final-tracks (field "final_tracks")
                                         :store-recording (field "store_recording")})))

(defn- size-limit-failure?
  "True when `t` or one of its causes is a multipart size-limit failure."
  [^Throwable t]
  (some #(instance? FileUploadSizeException %) (take-while some? (iterate #(.getCause ^Throwable %) t))))

(defn- store-upload!
  "Parse the multipart request and copy its single file part to S3.

  Returns {:params map :stored {:bytes long :container keyword}}.
  Throws an upload failure for malformed requests, unsupported media or too
  large files. Deletes the stored object when parsing fails after the copy."
  [req s3 bucket key max-bytes]
  (let [stored (atom nil)
        store (fn [{:keys [stream]}]
                (when @stored
                  (fail! 400 "multiple-files"))
                (reset! stored (s3.uploads/put-source! s3 bucket key stream max-bytes)))]
    (try
      (let [params (multipart/parse-multipart-params
                    req {:store store
                         :max-file-size max-bytes
                         :max-file-count max-form-items
                         :encoding "UTF-8"})]
        (when-not (and (map? params) @stored (identical? @stored (get params "file")))
          (fail! 400 "missing-file"))
        {:params params :stored @stored})
      (catch Throwable t
        (when @stored
          (s3.uploads/delete-source! s3 bucket key))
        (let [type (:type (ex-data t))]
          (cond
            (= type ::file-source/rejected) (throw t)
            (= type ::s3.uploads/too-large) (fail! 413 "file-too-large")
            (= type ::s3.uploads/unsupported-media) (fail! 415 "unsupported-media-type")
            (= type ::s3.uploads/empty) (fail! 400 "empty-file")
            (size-limit-failure? t) (fail! 413 "file-too-large")
            (or (instance? FileUploadException t)
                (some? (:max-file-count (ex-data t)))) (fail! 400 "invalid-multipart")
            :else (throw t)))))))

(defn upload-session-handler
  "Return a Ring handler for `POST /api/sessions/upload`.

  Dependencies (deps map): :config, :db {:ds DataSource}, :kafka-producer.
  Config: [:uploads :max-bytes] (0 disables uploads) and
  [:s3 :buckets :recordings :bucket|:prefix].

  Request: multipart/form-data with
  - file (required): audio or video file
  - title (optional, max 200 characters)
  - lang (optional language code such as `en` or `pt-BR`; empty = detect)
  - final_tracks (optional CSV of configured final track IDs)
  - store_recording (optional true|false; multiple tracks need true)

  Returns:
  - 200 {:session_id <uuid> :title <string>}
  - 400 invalid request or field, 403 forbidden, 413 file-too-large,
    415 unsupported-media-type, 503 uploads-disabled / db-unavailable /
    kafka-unavailable, 500 upload-failed"
  [{:keys [config db kafka-producer]}]
  (fn [req]
    (let [principal (:auth/principal req)
          max-bytes (features/uploads-max-bytes config)
          {:keys [bucket prefix]} (get-in config [:s3 :buckets :recordings])
          ds (:ds db)]
      (try
        (when-not (:record (policy/capabilities principal))
          (fail! 403 "forbidden"))
        (when-not (pos? max-bytes)
          (fail! 503 "uploads-disabled"))
        (when-not ds
          (fail! 503 "db-unavailable"))
        (when-not (:producer kafka-producer)
          (fail! 503 "kafka-unavailable"))
        (let [tenant-uuid (:tenant-id principal)
              session-uuid (uuid/uuid7)
              session-id (str session-uuid)
              key (s3.uploads/source-key prefix tenant-uuid session-id)
              s3 (s3.client/build-s3-client config)
              log-ctx {:tenant_id (str tenant-uuid)
                       :principal_id (str (:id principal))
                       :session_id session-id}
              _ (log/info "Receiving uploaded file" log-ctx)
              {:keys [params stored]} (store-upload! req s3 bucket key max-bytes)]
          (try
            (let [{:keys [title lang controls]} (parse-fields config params)
                  title (or title (format "Upload %1$tF %1$tR" (java.time.ZonedDateTime/now)))]
              (file-source/start-session! config ds kafka-producer principal session-uuid title controls
                                          {:source db.sessions/upload-source})
              (file-source/publish-source! kafka-producer ds tenant-uuid session-uuid bucket key lang
                                           controls log-ctx)
              (log/info "Uploaded file accepted" (assoc log-ctx
                                                        :bytes (:bytes stored)
                                                        :container (name (:container stored))
                                                        :final_tracks (:final_tracks controls)))
              {:status 200 :body {:session_id session-id :title title}})
            (catch Throwable t
              (s3.uploads/delete-source! s3 bucket key)
              (throw t))))
        (catch clojure.lang.ExceptionInfo e
          (or (file-source/rejection-response e)
              (do (log/error e "Failed to create upload session" {:uri (:uri req)})
                  (error 500 "upload-failed"))))
        (catch Exception e
          (log/error e "Failed to create upload session" {:uri (:uri req)})
          (error 500 "upload-failed"))))))
