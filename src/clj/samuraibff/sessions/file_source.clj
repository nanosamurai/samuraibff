(ns samuraibff.sessions.file-source
  "Shared rules for sessions whose audio is a stored file.

  Two endpoints create such sessions:
  - `POST /api/sessions/upload` (`samuraibff.http.uploads`): an uploaded file
  - `POST /api/recordings/{session_id}/retranscribe`
    (`samuraibff.http.retranscription`): a copy of another session's recording

  Both store the file at `samuraibff.s3.uploads/source-key`, create a
  `finished` session with final output only, publish `sessions.meta`, then
  publish `recordings.uploaded` after the broker ack. The recorder worker
  converts the file and publishes the normal `recordings.finished`.

  Public API:
  - `max-title-length`
  - `reject!`, `rejection`, `rejection-response`
  - `validate-fields`
  - `start-session!`
  - `publish-source!`"
  (:require
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [samuraibff.db.sessions :as db.sessions]
   [samuraibff.http.ui :as http.ui]
   [samuraibff.kafka.producer :as kafka.producer]
   [samuraibff.sessions.meta :as sessions.meta]
   [samuraibff.stream-controls :as stream-controls]))

(def max-title-length
  "Longest session title, in characters, that these endpoints accept."
  200)

(defn reject!
  "Throw a request rejection that maps to an HTTP status and error message.

  Inputs: status integer, stable error message string, optional map of extra
  data for the caller (for example {:param \"model\"}).
  Throws: ex-info {:type ::rejected :status int :message string} merged with
  the extra data."
  ([status message]
   (reject! status message nil))
  ([status message extra]
   (throw (ex-info message (merge extra {:type ::rejected :status status :message message})))))

(defn rejection
  "Return the data of a rejection thrown by `reject!` without :type, or nil.

  Inputs: Throwable. Returns: {:status int :message string ...extra} or nil
  for any other exception."
  [^Throwable t]
  (let [data (ex-data t)]
    (when (= ::rejected (:type data))
      (dissoc data :type))))

(defn rejection-response
  "Return the JSON error response for a rejection thrown by `reject!`, or nil.

  Inputs: Throwable. Returns: {:status int :body {:ok false :message string}}
  or nil for any other exception."
  [^Throwable t]
  (let [{:keys [type status message]} (ex-data t)]
    (when (= type ::rejected)
      {:status status :body {:ok false :message message}})))

(defn- blank->nil
  "Return a trimmed string, or nil for nil or a blank string."
  [v]
  (when (string? v)
    (not-empty (str/trim v))))

(defn validate-fields
  "Validate the optional title, language and final-stage controls of a new
  session from a stored file.

  Inputs:
  - config: BFF config map (:final-tracks, :refinement-tracks,
    :default-tracks, :track-labels)
  - fields: {:title string? :lang string? :final-tracks string?
             :store-recording string?} where :final-tracks is a CSV of track
    IDs and :store-recording is `true` or `false`. Blank values mean the
    default.

  Returns: {:title string? :lang string :controls map}. :lang is \"\" (detect)
  or a code such as `en` or `pt-BR`. :controls come from
  `stream-controls/parse-and-validate` with only the final output enabled,
  plus the deployment track labels.

  Throws a rejection (400) with message `invalid-title`, `invalid-lang` or
  `invalid-stream-controls`."
  [config {:keys [title lang final-tracks store-recording]}]
  (let [title (blank->nil title)
        lang (or (blank->nil lang) "")
        final-tracks (blank->nil final-tracks)
        store-recording (blank->nil store-recording)]
    (when (and title (> (count title) max-title-length))
      (reject! 400 "invalid-title"))
    (when-not (re-matches #"|[a-z]{2,3}(-[A-Za-z0-9]{2,8})?" lang)
      (reject! 400 "invalid-lang"))
    (let [controls (try
                     (stream-controls/parse-and-validate
                      (cond-> {"realtime" "false" "refined" "false" "final" "true"}
                        final-tracks (assoc "final_tracks" final-tracks)
                        store-recording (assoc "store_recording" store-recording))
                      nil
                      (or (:final-tracks config) ["whisperx"])
                      (or (:refinement-tracks config) ["whisperx"])
                      (dissoc (:default-tracks config) :realtime))
                     (catch clojure.lang.ExceptionInfo _
                       (reject! 400 "invalid-stream-controls")))]
      {:title title
       :lang lang
       :controls (stream-controls/with-track-labels controls config)})))

(defn start-session!
  "Create the session row, save its controls and publish sessions.meta.

  Uses the same ownership columns and default audience as
  `POST /api/sessions`. The audio input is complete, so the session is
  `finished` at once, like a live session after the user stops recording;
  the final transcript arrives later. `session-settings` must hold a
  `:source` from `db.sessions/file-sources`, so the UI can show Processing and
  a conversion failure can mark the session failed.

  Inputs: config, DataSource, Kafka producer component, principal, new
  session UUID, title string, validated controls, session settings map.
  Returns nil. Database and Kafka errors propagate."
  [config ds kafka-producer principal session-uuid title controls session-settings]
  (let [tenant-uuid (:tenant-id principal)]
    (db.sessions/insert-session!
     ds (merge (http.ui/session-ownership principal)
               {:id session-uuid
                :session-key (str session-uuid)
                :title title
                :status "created"
                :session-settings session-settings}))
    (db.sessions/activate-session-on-audio-start-with-controls! ds tenant-uuid session-uuid controls)
    (db.sessions/finish-session! ds tenant-uuid session-uuid)
    (kafka.producer/send-sessions-meta!
     kafka-producer (str session-uuid)
     (assoc (sessions.meta/resolve-admission-meta! config ds tenant-uuid session-uuid)
            :stream_controls controls)
     {:tenant-id (str tenant-uuid)})
    nil))

(defn publish-source!
  "Publish `recordings.uploaded` for a stored source and wait for the ack.

  Inputs: Kafka producer component, DataSource, tenant UUID, session UUID,
  bucket and key of the source object, language code string, validated
  controls, log context map.
  Returns nil. When the broker does not acknowledge, sets the session to
  `failed` and throws a rejection (503 `kafka-unavailable`)."
  [kafka-producer ds tenant-uuid session-uuid bucket key lang controls log-ctx]
  (try
    (kafka.producer/send-recording-uploaded!
     kafka-producer
     {:session-id (str session-uuid)
      :tenant-id (str tenant-uuid)
      :recording-url (str "s3://" bucket "/" key)
      :lang lang}
     (stream-controls/kafka-headers controls))
    (catch Exception e
      (log/error e "Failed publishing recordings.uploaded" log-ctx)
      (db.sessions/update-session-status! ds tenant-uuid session-uuid "failed")
      (reject! 503 "kafka-unavailable")))
  nil)
