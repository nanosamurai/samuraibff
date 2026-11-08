(ns samuraibff.http.retranscription
  "Create a new session from the stored recording of an existing session.

  Endpoint:
  - POST /api/recordings/:session_id/retranscribe (JSON body, all optional:
    title, lang, final_tracks, store_recording)

  A session keeps at most one final transcript per track, so a re-run makes
  a NEW session. The flow rides the file upload pipeline
  (`samuraibff.sessions.file-source`):
  1. Authorize: the caller must read the source session (else 404), may
     record and manages the source session (else 403).
  2. Check that file uploads are enabled, the source session is finished or
     failed, and its latest recording is in the caller's tenant part of the
     recordings bucket.
  3. Copy the recording inside S3 (CopyObject) to
     `<prefix>/<tenant_id>/uploads/<new_session_id>/source`.
  4. Create the new session (caller owns it, default audience, final output
     only, `session_settings` {source: retranscription, parent_session_id}),
     publish `sessions.meta`, then `recordings.uploaded` after the broker ack.
     The recorder converts the copy like an uploaded file.

  The source session and its transcripts do not change.

  Security:
  - Readers of a shared session cannot copy its audio: the caller must manage
    the source session.
  - Only `s3://` recordings in the configured bucket under the caller's
    tenant prefix are copied (`samuraibff.s3.uploads/tenant-recording-object`).
  - On a failure after the copy, the copy is deleted best-effort and a created
    session is set to `failed`.

  Public API:
  - `default-title`, `default-final-tracks`, `request-fields`
  - `unavailable-reason`, `detail-fields`
  - `retranscribe-handler`"
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [samuraibff.access.policy :as policy]
   [samuraibff.db.audit :as db.audit]
   [samuraibff.db.recordings :as db.recordings]
   [samuraibff.db.retranscriptions :as db.retranscriptions]
   [samuraibff.db.sessions :as db.sessions]
   [samuraibff.features :as features]
   [samuraibff.http.session-guard :as session-guard]
   [samuraibff.s3.client :as s3.client]
   [samuraibff.s3.uploads :as s3.uploads]
   [samuraibff.sessions.file-source :as file-source]
   [samuraibff.util.uuid :as uuid])
  (:import
   (java.util UUID)
   (software.amazon.awssdk.services.s3 S3Client)
   (software.amazon.awssdk.services.s3.model NoSuchKeyException)))

(def ^:private title-suffix " (re-transcribed)")

(def ^:private retranscribable-statuses
  "Session statuses whose audio input is complete."
  #{"finished" "failed"})

(def ^:private lang-pattern
  "Same language rule as `file-source/validate-fields`."
  #"|[a-z]{2,3}(-[A-Za-z0-9]{2,8})?")

(defn default-title
  "Return the default title of a re-transcription.

  Inputs: source session title (string or nil).
  Returns: `<source title> (re-transcribed)`, at most
  `file-source/max-title-length` characters; the source title is shortened so
  the suffix stays. A blank source title gives `Session (re-transcribed)`."
  [source-title]
  (let [base (or (some-> source-title str str/trim not-empty) "Session")
        room (- file-source/max-title-length (count title-suffix))]
    (str (str/trimr (subs base 0 (min room (count base)))) title-suffix)))

(defn- jsonb->map
  "Decode a jsonb column value (PGobject, string or map) into a keyword map, or nil."
  [v]
  (let [x (cond
            (map? v) v
            (string? v) (cheshire/parse-string v true)
            (some? v) (some-> (.getValue ^org.postgresql.util.PGobject v) (cheshire/parse-string true)))]
    (when (map? x) x)))

(defn default-final-tracks
  "Return the final track IDs that a re-transcription uses by default.

  Inputs: config map (:final-tracks), source session stream controls (jsonb
  value or map).
  Returns: the source session's frozen `final_tracks` when every one is
  still configured (1 to 4 distinct IDs), else nil, which means the
  configured default tracks."
  [config stream-controls]
  (let [tracks (:final_tracks (jsonb->map stream-controls))
        configured (set (or (:final-tracks config) ["whisperx"]))]
    (when (and (sequential? tracks)
               (<= 1 (count tracks) 4)
               (apply distinct? tracks)
               (every? #(and (string? %) (contains? configured %)) tracks))
      (vec tracks))))

(defn- usable-recording
  "Return {:bucket :key} of the session's latest recording when it can be
  copied for the principal's tenant, else nil."
  [config principal recording]
  (let [{:keys [bucket prefix]} (get-in config [:s3 :buckets :recordings])]
    (s3.uploads/tenant-recording-object (:recording_url recording) bucket prefix (:tenant-id principal))))

(defn unavailable-reason
  "Return why the principal may not re-transcribe a session, or nil.

  Inputs: config, principal, source session row (with :status and the access
  columns that `policy/can-manage-session?` needs), latest recording row or nil.
  Returns: nil when allowed, else [status message] with
  - [403 \"forbidden\"]                 no `:record` capability or not a manager
  - [503 \"uploads-disabled\"]          file uploads are disabled
  - [409 \"session-not-finished\"]      the session is still created or active
  - [409 \"recording-not-available\"]   no stored recording that can be copied"
  [config principal session recording]
  (cond
    (not (and (:record (policy/capabilities principal))
              (policy/can-manage-session? principal session)))
    [403 "forbidden"]

    (not (pos? (features/uploads-max-bytes config)))
    [503 "uploads-disabled"]

    (not (contains? retranscribable-statuses (some-> (:status session) str)))
    [409 "session-not-finished"]

    (nil? (usable-recording config principal recording))
    [409 "recording-not-available"]

    :else nil))

(defn- invalid-request!
  []
  (file-source/reject! 400 "invalid-request"))

(defn request-fields
  "Turn a re-transcribe request body into `file-source/validate-fields` input.

  Inputs:
  - body: map with optional :title string, :lang string, :final_tracks
    [string], :store_recording boolean (nil means an empty body)
  - source: source session row (:title, :stream_controls)
  - recording: latest recording row (:lang)
  - config: BFF config map

  Defaults: title from `default-title`; lang = the recording's language when
  it is valid, else \"\" (detect); final_tracks from `default-final-tracks`;
  store_recording true.

  Returns: {:title :lang :final-tracks (CSV or nil) :store-recording string}.
  Throws a rejection (400 `invalid-request`) for wrong value types and
  (400 `invalid-stream-controls`) for an empty final_tracks list."
  [body source recording config]
  (let [body (or body {})
        {:keys [title lang final_tracks store_recording]} body
        recording-lang (some-> (:lang recording) str str/trim)]
    (when-not (and (map? body)
                   (or (nil? title) (string? title))
                   (or (nil? lang) (string? lang))
                   (or (nil? final_tracks) (and (sequential? final_tracks) (every? string? final_tracks)))
                   (or (nil? store_recording) (boolean? store_recording)))
      (invalid-request!))
    (when (and (some? final_tracks) (empty? final_tracks))
      (file-source/reject! 400 "invalid-stream-controls"))
    {:title (if (str/blank? title) (default-title (:title source)) title)
     :lang (cond
             (some? lang) lang
             (and recording-lang (re-matches lang-pattern recording-lang)) recording-lang
             :else "")
     :final-tracks (some->> (or final_tracks (default-final-tracks config (:stream_controls source)))
                            (str/join ","))
     :store-recording (str (if (some? store_recording) store_recording true))}))

(defn detail-fields
  "Return the re-transcription fields of a session detail response.

  Inputs: config, DataSource, principal, session row from
  `session-access/find-accessible-session` (the caller may read it), latest
  recording row or nil.

  Returns map:
  - :can_retranscribe boolean, same rules as the endpoint
  - :retranscribe_unavailable \"recording-not-available\" only when the caller
    could re-transcribe but no usable audio was kept
  - :recording_lang language code of the latest recording, or nil; the
    default language of a re-transcription
  - :retranscribed_from {:id :title} when the session is a re-transcription
    and the caller may read its source session; else absent
  - :retranscriptions [{:id :title :status :created_at :source
    :has_final_transcript}] readable re-transcriptions, newest first"
  [config ds principal session recording]
  (let [reason (unavailable-reason config principal session recording)
        parent (when (= db.sessions/retranscription-source (:source session))
                 (db.retranscriptions/find-readable-parent ds principal (:parent_session_id session)))
        children (db.retranscriptions/list-readable-children ds principal (:id session))]
    (cond-> {:can_retranscribe (nil? reason)
             :recording_lang (some-> (:lang recording) str str/trim not-empty)
             :retranscriptions (mapv (fn [c]
                                       {:id (str (:id c))
                                        :title (:title c)
                                        :status (:status c)
                                        :created_at (some-> (:created_at c) str)
                                        :source (:source c)
                                        :has_final_transcript (boolean (:has_final_transcript c))})
                                     children)}
      (= [409 "recording-not-available"] reason) (assoc :retranscribe_unavailable "recording-not-available")
      parent (assoc :retranscribed_from {:id (str (:id parent)) :title (:title parent)}))))

(defn- parse-session-uuid
  "Parse the :session_id path parameter or throw a 400 rejection."
  [req]
  (let [sid (or (get-in req [:path-params :session_id]) (get-in req [:path-params "session_id"]))]
    (try
      (UUID/fromString (str sid))
      (catch IllegalArgumentException _
        (file-source/reject! 400 "invalid-session-id")))))

(defn- audit!
  "Append the payload-free audit event of a re-transcription (ids only)."
  [ds principal new-session-uuid source-session-uuid final-tracks]
  (db.audit/record! ds {:tenant-id (:tenant-id principal)
                        :actor-id (:id principal)
                        :actor-kind (if (policy/service? principal) "service" "user")
                        :action "session.retranscribed"
                        :target-type "session"
                        :target-id new-session-uuid
                        :outcome "succeeded"
                        :details {:parent_session_id (str source-session-uuid)
                                  :final_tracks (vec final-tracks)}}))

(defn- copy-recording!
  "Copy the source recording to the new source key; a missing object is 409."
  [^S3Client s3 bucket source-key dest-key]
  (try
    (s3.uploads/copy-source! s3 bucket source-key dest-key)
    (catch NoSuchKeyException _
      (file-source/reject! 409 "recording-not-available"))))

(defn- create-retranscription!
  "Copy the recording, create the new session and publish it.

  Returns the 202 response. On any failure after the copy, deletes the copy
  best-effort, sets a created session to `failed` and rethrows."
  [{:keys [config ds kafka-producer principal source-uuid object fields]}]
  (let [{:keys [title lang controls]} (file-source/validate-fields config fields)
        tenant-uuid (:tenant-id principal)
        {:keys [bucket prefix]} (get-in config [:s3 :buckets :recordings])
        session-uuid (uuid/uuid7)
        session-id (str session-uuid)
        dest-key (s3.uploads/source-key prefix tenant-uuid session-id)
        created? (atom false)
        log-ctx {:tenant_id (str tenant-uuid)
                 :principal_id (str (:id principal))
                 :session_id session-id
                 :parent_session_id (str source-uuid)
                 :final_tracks (:final_tracks controls)}]
    (log/info "Starting re-transcription" log-ctx)
    (with-open [^S3Client s3 (s3.client/build-s3-client config)]
      (copy-recording! s3 bucket (:key object) dest-key)
      (try
        (reset! created? true)
        (file-source/start-session! config ds kafka-producer principal session-uuid title controls
                                    {:source db.sessions/retranscription-source
                                     :parent_session_id (str source-uuid)})
        (file-source/publish-source! kafka-producer ds tenant-uuid session-uuid bucket dest-key lang
                                     controls log-ctx)
        (try
          (audit! ds principal session-uuid source-uuid (:final_tracks controls))
          (catch Exception e
            (log/warn e "Failed to record re-transcription audit event" log-ctx)))
        (log/info "Re-transcription accepted" log-ctx)
        {:status 202 :body {:session_id session-id
                            :title title
                            :retranscribed_from (str source-uuid)}}
        (catch Throwable t
          (s3.uploads/delete-source! s3 bucket dest-key)
          (when @created?
            (try
              (db.sessions/update-session-status! ds tenant-uuid session-uuid "failed")
              (catch Exception e
                (log/warn e "Failed to mark re-transcription failed" log-ctx))))
          (throw t))))))

(defn retranscribe-handler
  "Return a Ring handler for `POST /api/recordings/:session_id/retranscribe`.

  Dependencies (deps map): :config, :db {:ds DataSource}, :kafka-producer.

  Request body (JSON, all optional): see `request-fields`.

  Returns:
  - 202 {:session_id <uuid> :title <string> :retranscribed_from <uuid>}
  - 400 invalid-session-id / invalid-request / invalid-title / invalid-lang /
    invalid-stream-controls
  - 403 forbidden, 404 not-found
  - 409 session-not-finished / recording-not-available
  - 503 uploads-disabled / db-unavailable / kafka-unavailable
  - 500 retranscribe-failed"
  [{:keys [config db kafka-producer]}]
  (fn [req]
    (let [ds (:ds db)]
      (try
        (when-not ds
          (file-source/reject! 503 "db-unavailable"))
        (let [principal (session-guard/require-principal! req)
              source-uuid (parse-session-uuid req)
              source (session-guard/require-readable-session! ds req source-uuid)
              recording (db.recordings/find-latest-recording ds (:tenant-id principal) source-uuid)]
          (when-let [[status message] (unavailable-reason config principal source recording)]
            (file-source/reject! status message))
          (let [fields (request-fields (or (get-in req [:parameters :body]) (:body-params req))
                                       source recording config)]
            (when-not (:producer kafka-producer)
              (file-source/reject! 503 "kafka-unavailable"))
            (create-retranscription!
             {:config config :ds ds :kafka-producer kafka-producer :principal principal
              :source-uuid source-uuid
              :object (usable-recording config principal recording)
              :fields fields})))
        (catch clojure.lang.ExceptionInfo e
          (or (file-source/rejection-response e)
              (session-guard/error-response (:type (ex-data e)))
              (do (log/error e "Failed to re-transcribe session" {:uri (:uri req)})
                  {:status 500 :body {:ok false :message "retranscribe-failed"}})))
        (catch Exception e
          (log/error e "Failed to re-transcribe session" {:uri (:uri req)})
          {:status 500 :body {:ok false :message "retranscribe-failed"}})))))
