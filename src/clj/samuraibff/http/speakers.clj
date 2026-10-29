;; Copyright (c) samuraibff contributors.
(ns samuraibff.http.speakers
  "HTTP handlers for enrolled speaker management.

  Endpoints:
  - GET    /api/speakers
  - POST   /api/speakers (multipart; label + sample)
  - DELETE /api/speakers/:speaker_id

  All endpoints are tenant-aware and require auth (via router middleware).
  Speaker samples are stored in S3 and metadata in Postgres.

  This namespace expects the following dependencies:
  - :config (global config)
  - :db {:ds DataSource}

  Multipart parsing is provided by `ring.middleware.multipart-params`.
  The `:multipart-params` key is expected in the Ring request." 
  (:require
    [clojure.string :as str]
    [org.corfield.logging4j2 :as log]
    [samuraibff.access.policy :as policy]
    [samuraibff.db.speakers :as db.speakers]
    [samuraibff.s3.enrollment :as s3.enrollment]
    [samuraibff.util.uuid :as uuid])
  (:import
    (java.io InputStream)
    (java.util UUID)
    (software.amazon.awssdk.services.s3.model NoSuchBucketException S3Exception)))

(defn- json-response
  "Return a JSON response map.

  Inputs:
  - status int
  - body map

  Returns: Ring response map." 
  [status body]
  ;; NOTE: Return a *data* body (map). Muuntaja JSON-encodes it.
  ;; This keeps Reitit response coercion compatible with Malli schemas.
  {:status status
   :body body})

(defn- ensure-tenant-id
  "Return the tenant UUID of an active human principal or throw.

  The shared speaker gallery is available to every active human member of the
  tenant. Machine principals cannot list or change it.

  Inputs:
  - req: Ring request (expects :auth/principal and :auth/tenant-id)

  Returns:
  - java.util.UUID

  Throws:
  - ex-info when the principal is not an active human or tenant-id is missing."
  [req]
  (let [tenant-id (:auth/tenant-id req)]
    (when-not (and (policy/human? (:auth/principal req)) (seq (str tenant-id)))
      (throw (ex-info "missing-tenant-id" {:type :samuraibff.http/missing-tenant-id})))
    (UUID/fromString (str tenant-id))))

(defn- read-bytes
  "Read all bytes from an InputStream.

  Inputs:
  - in: InputStream

  Returns:
  - byte-array

  Notes:
  - This helper closes the stream automatically." 
  [^InputStream in]
  (with-open [stream in]
    (let [buffer (byte-array 8192)
          out (java.io.ByteArrayOutputStream.)]
      (loop []
        (let [n (.read stream buffer)]
          (when (pos? n)
            (.write out buffer 0 n)
            (recur))))
      (.toByteArray out))))

(defn- resolve-user-id
  "Return the request principal id. Speaker gallery changes require an active
  human; machine principals and missing principals are rejected.

  Inputs: request map. Returns: UUID. Throws :samuraibff.http/missing-tenant-id."
  [req]
  (let [principal (:auth/principal req)]
    (when-not (policy/human? principal)
      (throw (ex-info "missing-tenant-id" {:type :samuraibff.http/missing-tenant-id})))
    (:id principal)))

(defn list-speakers-handler
  "List speakers for the current tenant.

  Dependencies:
  - deps map containing :db {:ds ...}

  Returns:
  - 200 JSON {:items [...]}
  - 500 JSON on errors." 
  [{:keys [db]}]
  (fn [req]
    (try
      (let [tenant-id (ensure-tenant-id req)
            ds (:ds db)
            rows (if ds
                   (db.speakers/list-speakers ds tenant-id)
                   [])
            items (mapv (fn [row]
                          (-> row
                              (update :id str)
                              (update :tenant_id str)
                              (update :user_id #(when % (str %)))
                              (update :created_at #(when % (str %)))))
                        rows)]
        (json-response 200 {:ok true :items items}))
      (catch clojure.lang.ExceptionInfo e
        (if (= :samuraibff.http/missing-tenant-id (:type (ex-data e)))
          (json-response 403 {:ok false :message "missing-tenant-id"})
          (do
            (log/error e "Failed to list speakers" {})
            (json-response 500 {:ok false :message "list-speakers-failed"}))))
      (catch Exception e
        (log/error e "Failed to list speakers" {})
        (json-response 500 {:ok false :message "list-speakers-error"})))))

(defn create-speaker-handler
  "Create a new speaker (metadata + single sample upload).

  Expected multipart fields:
  - label (string)
  - sample (file input; wav)

  Dependencies:
  - deps map containing :config and :db

  Returns:
  - 200 JSON with speaker metadata
  - 400 JSON if missing fields
  - 500 JSON on errors." 
  [{:keys [config db]}]
  (fn [req]
    (try
      (let [tenant-id (ensure-tenant-id req)
            label (some-> (get-in req [:multipart-params "label"]) str str/trim)
            sample (get-in req [:multipart-params "sample"])
            sample-tempfile (:tempfile sample)
            sample-stream (:stream sample)
            ds (:ds db)
            user-uuid (when ds
                        (resolve-user-id req))]
        (cond
          (str/blank? label)
          (json-response 400 {:ok false :message "missing-label"})

          (nil? sample)
          (json-response 400 {:ok false :message "missing-sample"})

          :else
          (let [speaker-id (uuid/uuid7)
                sample-id (uuid/uuid7)
                sample-bytes (cond
                               sample-tempfile (java.nio.file.Files/readAllBytes (.toPath sample-tempfile))
                               sample-stream (read-bytes sample-stream)
                               :else nil)]
            (if-not (seq sample-bytes)
              (json-response 400 {:ok false :message "empty-sample"})
              (let [s3 (s3.enrollment/build-s3-client config)]
                (try
                  (log/info "Creating speaker" {:tenant_id (str tenant-id)
                                                :user_id (some-> req :auth/user :sub str)
                                                :speaker_id (str speaker-id)
                                                :sample_id (str sample-id)
                                                :label label
                                                :sample_bytes (alength ^bytes sample-bytes)})
                  (let [{:keys [sample-url manifest-url]} (s3.enrollment/put-speaker!
                                                            s3 config tenant-id speaker-id label sample-id sample-bytes)
                        _ (when ds
                            (db.speakers/insert-speaker!
                              ds
                              {:id speaker-id
                               :tenant-id tenant-id
                               :user-id user-uuid
                               :label label
                               :audio-url manifest-url}))]
                    (log/info "Speaker created" {:tenant_id (str tenant-id)
                                                 :user_id (some-> req :auth/user :sub str)
                                                 :speaker_id (str speaker-id)
                                                 :sample_id (str sample-id)
                                                 :label label
                                                 :sample_url sample-url
                                                 :manifest_url manifest-url})
                    (json-response 200 {:ok true
                                        :speaker_id (str speaker-id)
                                        :tenant_id (str tenant-id)
                                        :label label
                                        :sample_url sample-url
                                        :manifest_url manifest-url}))
                  (finally
                    (.close s3))))))))
      (catch clojure.lang.ExceptionInfo e
        (if (= :samuraibff.http/missing-tenant-id (:type (ex-data e)))
          (json-response 403 {:ok false :message "missing-tenant-id"})
          (do
            (log/error e "Failed to create speaker" {})
            (json-response 500 {:ok false :message "create-speaker-failed"}))))
      (catch Exception e
        (log/error e "Failed to create speaker" {})
        (json-response 500 {:ok false :message "create-speaker-error"})))))

(defn delete-speaker-handler
  "Delete a speaker for the current tenant.

  Dependencies:
  - deps map containing :config and :db

  Returns:
  - 200 JSON {ok true}
  - 404 if not found
  - 500 on errors." 
  [{:keys [config db]}]
  (fn [req]
    (try
      (let [tenant-id (ensure-tenant-id req)
            speaker-id (some-> (get-in req [:path-params :speaker_id]) str)
            speaker-uuid (try
                           (UUID/fromString speaker-id)
                           (catch Exception _
                             nil))]
        (if-not speaker-uuid
          (json-response 400 {:ok false :message "invalid-speaker-id"})
          (let [ds (:ds db)
                deleted (when ds (db.speakers/delete-speaker! ds tenant-id speaker-uuid))]
            (if (and ds (zero? (or deleted 0)))
              (json-response 404 {:ok false :message "speaker-not-found"})
              (let [s3 (s3.enrollment/build-s3-client config)]
                (try
                  (log/info "Deleting speaker" {:tenant_id (str tenant-id)
                                                :user_id (some-> req :auth/user :sub str)
                                                :speaker_id (str speaker-uuid)})
                  (let [s3-result
                        (try
                          {:s3_deleted_objects (long (or (s3.enrollment/delete-speaker-prefix! s3 config tenant-id speaker-uuid) 0))}
                          (catch NoSuchBucketException e
                            (log/info e "Speaker S3 bucket missing; treating as already-deleted" {:tenant_id (str tenant-id)
                                                                                                 :speaker_id (str speaker-uuid)})
                            {:s3_deleted_objects 0
                             :s3_delete_failed true})
                          (catch S3Exception e
                            ;; LocalStack may lose data or restart; do not fail speaker deletion if DB record is gone.
                            (let [status (some-> e .statusCode)]
                              (if (or (= 404 status) (= 400 status))
                                (do
                                  (log/info e "Speaker S3 prefix missing; treating as already-deleted" {:tenant_id (str tenant-id)
                                                                                                        :speaker_id (str speaker-uuid)
                                                                                                        :status status})
                                  {:s3_deleted_objects 0})
                                (do
                                  (log/warn e "Speaker S3 delete failed; continuing" {:tenant_id (str tenant-id)
                                                                                     :speaker_id (str speaker-uuid)
                                                                                     :status status})
                                  {:s3_deleted_objects 0
                                   :s3_delete_failed true})))))]
                    (log/info "Speaker deleted" (merge {:tenant_id (str tenant-id)
                                                        :user_id (some-> req :auth/user :sub str)
                                                        :speaker_id speaker-id}
                                                       s3-result))
                    (json-response 200 (merge {:ok true :speaker_id speaker-id}
                                             s3-result)))
                  (finally
                    (.close s3))))))))
      (catch clojure.lang.ExceptionInfo e
        (if (= :samuraibff.http/missing-tenant-id (:type (ex-data e)))
          (json-response 403 {:ok false :message "missing-tenant-id"})
          (do
            (log/error e "Failed to delete speaker" {})
            (json-response 500 {:ok false :message "delete-speaker-failed"}))))
      (catch Exception e
        (log/error e "Failed to delete speaker" {})
        (json-response 500 {:ok false :message "delete-speaker-error"})))))