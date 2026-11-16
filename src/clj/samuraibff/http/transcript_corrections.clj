(ns samuraibff.http.transcript-corrections
  "HTTP API for human corrections of final transcripts.

  Endpoint:
  - POST /api/recordings/:session_id/transcripts/:transcript_id/corrections

  Every edit is stored at once as one appended row; there is no draft or
  save step. Undo is another POST (kind `undo`). Readers get the corrections
  with the recording detail (`corrections-by-transcript`).

  Access: the caller must manage the session (`session-guard/require-managed-session!`).
  A reader gets 403 read-only-access; an unreadable session 404.

  Public API:
  - `create-correction-handler`
  - `corrections-by-transcript`, `correction->json`, `correct-final-rows`"
  (:require
   [org.corfield.logging4j2 :as log]
   [samuraibff.db.transcript-corrections :as db.corrections]
   [samuraibff.http.session-guard :as session-guard]
   [samuraibff.transcripts.corrections :as corrections])
  (:import
   (java.sql Timestamp)
   (java.time OffsetDateTime)
   (java.util UUID)))

(defn- iso-time
  "Return a timestamp as an ISO-8601 UTC string."
  [t]
  (cond
    (instance? Timestamp t) (str (.toInstant ^Timestamp t))
    (instance? OffsetDateTime t) (str (.toInstant ^OffsetDateTime t))
    :else (some-> t str)))

(defn correction->json
  "Convert a stored correction row into the client shape
  (`samuraibff.transcripts.correction-schemas/TranscriptCorrection`).

  Inputs: row from `samuraibff.db.transcript-corrections`, caller principal ID.
  Returns a map with string IDs and an :author {:display_name? :is_me}."
  [row principal-id]
  (let [author-name (db.corrections/display-name row)]
    (cond-> {:id (str (:id row))
             :seq (long (:seq row))
             :kind (:kind row)
             :old_value (or (:old_value row) {})
             :new_value (or (:new_value row) {})
             :created_at (iso-time (:created_at row))
             :author (cond-> {:is_me (and (some? principal-id)
                                          (= (str principal-id) (str (:author_user_id row))))}
                       author-name (assoc :display_name author-name))}
      (some? (:segment_index row)) (assoc :segment_index (long (:segment_index row)))
      (some? (:speaker_label row)) (assoc :speaker_label (:speaker_label row))
      (some? (:undoes_id row)) (assoc :undoes_id (str (:undoes_id row)))
      (number? (:audio_start_s row)) (assoc :audio_start_s (double (:audio_start_s row)))
      (number? (:audio_end_s row)) (assoc :audio_end_s (double (:audio_end_s row))))))

(defn corrections-by-transcript
  "Group a session's correction rows for the recording detail response.

  Inputs: rows from `db.corrections/list-for-session`, caller principal ID.
  Returns {transcript-id-string {:head_seq int :items [json ...]}}."
  [rows principal-id]
  (->> rows
       (group-by (comp str :transcript_id))
       (into {} (map (fn [[transcript-id items]]
                       [transcript-id {:head_seq (corrections/head-seq items)
                                       :items (mapv #(correction->json % principal-id) items)}])))))

(defn correct-final-rows
  "Apply stored corrections to the final transcript that readers see.

  Inputs: DataSource, tenant UUID, final rows of one track ordered by
  created_at with decoded :segments vectors. The latest row is the one the
  recording page, export and workflows use.
  Returns {:rows rows :corrected? boolean}; with active corrections the
  latest row's :segments are the corrected segments."
  [ds tenant-id rows]
  (let [latest (peek (vec rows))
        transcript-id (some-> (:id latest) str parse-uuid)
        stored (when transcript-id (db.corrections/list-for-transcript ds tenant-id transcript-id))]
    (if (seq (corrections/active-corrections stored))
      {:rows (conj (pop (vec rows)) (update latest :segments corrections/corrected-segments stored))
       :corrected? true}
      {:rows (vec rows) :corrected? false})))

(def ^:private bad-request-errors
  #{:invalid-base-seq :invalid-kind :invalid-segment :invalid-text :invalid-speaker
    :unknown-speaker :invalid-speaker-label :unknown-correction :correction-unchanged})

(def ^:private conflict-errors
  #{:correction-conflict :already-undone :too-many-corrections})

(defn- response
  [status body]
  {:status status :body body})

(defn- path-uuid
  "Parse a path parameter as UUID, or throw {:type ::invalid-path}."
  [req k]
  (try
    (UUID/fromString (str (get-in req [:path-params k])))
    (catch Exception _
      (throw (ex-info "invalid-path" {:type ::invalid-path :param k})))))

(defn- request-body
  [req]
  (let [body (or (get-in req [:parameters :body]) (:body-params req) {})]
    (select-keys body [:kind :base_seq :segment_index :speaker_label :text :speaker :speaker_id :correction_id])))

(defn create-correction-handler
  "Handler for `POST /api/recordings/:session_id/transcripts/:transcript_id/corrections`.

  Body: `samuraibff.transcripts.correction-schemas/CreateCorrectionRequest`.
  Responses:
  - 201 {:ok true :correction {...} :head_seq n}
  - 400 invalid IDs or request (message names the problem, for example
    invalid-segment or correction-unchanged)
  - 403 read-only-access for a reader; 404 for an unreadable session or a
    transcript that is not a final transcript of the session
  - 409 correction-conflict (a newer edit changed the same target),
    already-undone or too-many-corrections
  - 503 without a database

  Dependencies: {:db {:ds DataSource}}."
  [{:keys [db]}]
  (fn [req]
    (let [ds (:ds db)]
      (try
        (if-not ds
          (response 503 {:ok false :message "db-unavailable"})
          (let [session-id (path-uuid req :session_id)
                transcript-id (path-uuid req :transcript_id)
                session (session-guard/require-managed-session! ds req session-id)
                principal (session-guard/require-principal! req)
                request (request-body req)
                log-data {:session-id (str session-id) :tenant-id (str (:tenant_id session))
                          :user-id (str (:id principal)) :transcript-id (str transcript-id)
                          :kind (:kind request)}
                _ (log/info "Appending transcript correction" log-data)
                {:keys [correction head_seq error]}
                (db.corrections/append! ds {:tenant-id (:tenant_id session)
                                            :session-id session-id
                                            :transcript-id transcript-id
                                            :author-id (:id principal)
                                            :request request})]
            (cond
              (nil? error)
              (do (log/info "Appended transcript correction" (assoc log-data :seq head_seq))
                  (response 201 {:ok true
                                 :correction (correction->json (assoc correction
                                                                      :author_name (:name principal)
                                                                      :author_email (:email principal))
                                                               (:id principal))
                                 :head_seq head_seq}))

              (= :transcript-not-found error)
              (response 404 {:ok false :message "transcript-not-found"})

              (contains? conflict-errors error)
              (do (log/info "Rejected transcript correction" (assoc log-data :reason (name error)))
                  (response 409 {:ok false :message (name error)}))

              (contains? bad-request-errors error)
              (response 400 {:ok false :message (name error)})

              :else
              (response 400 {:ok false :message "invalid-request"}))))
        (catch clojure.lang.ExceptionInfo e
          (let [type (:type (ex-data e))]
            (cond
              (= type ::invalid-path) (response 400 {:ok false :message "invalid-id"})
              (session-guard/error-response type) (session-guard/error-response type)
              :else (do (log/error e "Failed to append transcript correction")
                        (response 500 {:ok false :message "internal-error"})))))
        (catch Exception e
          (log/error e "DB error appending transcript correction")
          (response 500 {:ok false :message "db-error"}))))))
