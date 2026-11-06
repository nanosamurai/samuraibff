(ns samuraibff.http.openai
  "OpenAI-compatible transcription endpoint: `POST /v1/audio/transcriptions`.

  The endpoint is a thin adapter over file upload
  (`samuraibff.http.uploads/create-upload-session!`):
  1. Reject at once when [:openai :max-concurrent-waits] requests already wait.
  2. Create an upload session for the final track that `model` maps to
     (`samuraibff.transcripts.openai/resolve-model`). Authorization,
     ownership, size limits and media checks are the upload ones.
  3. Poll the stored final transcript of that track every
     [:openai :poll-interval-ms] until it exists or [:openai :wait-timeout-s]
     passes. Waiting uses core.async timers, not a request thread: behind
     http-kit the response is sent on an async channel.
  4. Render the transcript in the requested `response_format`, or return 504
     with the `session_id` so the client can read the result later through
     `GET /api/recordings/{session_id}`.

  Every error uses the OpenAI error shape (see `wrap-openai-errors`)."
  (:require
   [cheshire.core :as cheshire]
   [clojure.core.async :as a]
   [org.corfield.logging4j2 :as log]
   [org.httpkit.server :as http]
   [samuraibff.db.recordings :as db.recordings]
   [samuraibff.http.uploads :as uploads]
   [samuraibff.transcripts.openai :as openai]
   [samuraibff.ui.recording-detail :as recording-detail])
  (:import
   (java.util UUID)
   (java.util.concurrent Semaphore)
   (java.util.concurrent.atomic AtomicBoolean)))

(def default-settings
  "Defaults for the [:openai ...] config keys."
  {:wait-timeout-s 600
   :max-concurrent-waits 16
   :poll-interval-ms 2000})

(def ^:private max-form-items
  "OpenAI clients send more text fields than the upload form
  (model, language, prompt, response_format, temperature, granularities)."
  16)

(defn settings
  "Return the effective wait settings: [:openai] config over `default-settings`.

  Non-positive or missing values fall back to the defaults."
  [config]
  (merge-with (fn [default v] (if (and (number? v) (pos? v)) v default))
              default-settings
              (select-keys (:openai config) (keys default-settings))))

(defn- decode-segments
  "Decode a row's `segments` jsonb (PGobject, string or data) into a vector."
  [row]
  (let [v (:segments row)]
    (assoc row :segments (vec (cond
                                (nil? v) []
                                (sequential? v) v
                                :else (cheshire/parse-string (str v) true))))))

(defn fetch-final-transcript
  "Return the stored final transcript of one track, or nil when none exists.

  Inputs: DataSource, tenant UUID, session UUID and track ID string.
  Returns {:segments [TranscriptSegment] :full-text :lang :duration-s} built
  from the latest final row of the track."
  [ds ^UUID tenant-id ^UUID session-id track-id]
  (let [rows (db.recordings/list-transcript-records
              ds tenant-id session-id {:type "final" :limit 20 :track-id track-id})]
    (when-let [row (some-> (last rows) decode-segments)]
      {:segments (recording-detail/record-messages [row] :final)
       :full-text (:full_text row)
       :lang (:lang row)
       :duration-s (:duration_s row)})))

(defn- upload-error
  "Map an upload rejection {:status :message :param?} to an OpenAI error response."
  [{:keys [status message param]}]
  (openai/error-response status message
                         (case message
                           "missing-file" "No audio file was provided"
                           "empty-file" "The audio file is empty"
                           "multiple-files" "Send exactly one file"
                           "file-too-large" "The audio file is larger than the configured limit"
                           "unsupported-media-type" "The file is not a supported audio or video format"
                           "invalid-lang" "language must be a language code such as en or pt-BR"
                           "forbidden" "This credential may not transcribe audio"
                           message)
                         (or param
                             (case message
                               ("missing-file" "empty-file" "multiple-files" "file-too-large"
                                               "unsupported-media-type") "file"
                               "invalid-lang" "language"
                               nil))))

(defn- prepare-params
  "Return the upload params for a validated OpenAI request, or reject it."
  [config params]
  (let [{:keys [ok error]} (openai/parse-request config params)]
    (when error
      (uploads/reject! (:status error) (:code error) {:param (:param error)
                                                      :openai-message (:message error)}))
    (cond-> {"final_tracks" (:track-id ok)}
      (:language ok) (assoc "lang" (:language ok)))))

(defn wait-for-transcript!
  "Poll for a transcript without blocking a thread, then call `respond!` once.

  Inputs:
  - fetch!: (fn [] transcript-or-nil); runs on a core.async thread and may
    throw (a failure is logged and polling continues).
  - opts: {:timeout-ms :poll-interval-ms :cancelled? (fn [] bool)}
  - respond!: (fn [result]) with {:transcript map} or {:timeout true}; not
    called when `cancelled?` turns true.
  Returns the go-loop channel (closes when done)."
  [fetch! {:keys [timeout-ms poll-interval-ms cancelled?] :or {cancelled? (constantly false)}} respond!]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (a/go-loop []
      (a/<! (a/timeout (min (long poll-interval-ms)
                            (max 0 (- deadline (System/currentTimeMillis))))))
      (when-not (cancelled?)
        (let [result (a/<! (a/thread
                             (try
                               {:transcript (fetch!)}
                               (catch Throwable t
                                 (log/warn t "Polling the final transcript failed")
                                 {}))))]
          (cond
            (cancelled?) nil
            (:transcript result) (respond! result)
            (>= (System/currentTimeMillis) deadline) (respond! {:timeout true})
            :else (recur)))))))

(defn- timeout-response
  "Return the 504 response for a transcript that is not ready yet."
  [session-id timeout-s]
  (openai/error-response 504 "transcription_timeout"
                         (str "The transcript was not ready within " timeout-s " seconds. "
                              "It is still processing: read it later with GET /api/recordings/" session-id)
                         nil
                         {:session_id session-id}))

(defn- respond-async
  "Send the eventual response on an http-kit async channel, or block on a
  promise when the request has no async channel (direct handler calls).

  `start!` receives (fn [response]) and (fn [] cancelled?) and must call the
  first exactly once unless cancelled. `on-done` runs once when the exchange ends."
  [req start! on-done]
  (if (:async-channel req)
    (let [closed? (AtomicBoolean. false)]
      (http/as-channel req
                       {:on-open (fn [ch]
                                   (start! (fn [response] (http/send! ch response true))
                                           #(.get closed?)))
                        :on-close (fn [_ch _status]
                                    (.set closed? true)
                                    (on-done))}))
    (let [result (promise)]
      (start! #(deliver result %) (constantly false))
      (try @result (finally (on-done))))))

(defn transcriptions-handler
  "Return a Ring handler for `POST /v1/audio/transcriptions`.

  Dependencies: same as `samuraibff.http.uploads/upload-session-handler`
  ({:config :db {:ds} :kafka-producer}). Config: [:openai :wait-timeout-s
  :max-concurrent-waits :poll-interval-ms] (see `default-settings`),
  [:openai :model-aliases], [:final-tracks], [:default-tracks :final] and the
  upload keys.

  Request: multipart/form-data with `file` and `model` (required),
  `language`, `response_format` (json|text|srt|vtt|verbose_json),
  `timestamp_granularities[]` (segment|word); `prompt` and `temperature`
  are accepted and ignored.

  Returns 200 in the requested format, or an OpenAI error: 400 invalid
  request, 403 forbidden, 413, 415, 429 too many waiting requests, 500, 503,
  504 transcript not ready (error.session_id)."
  [{:keys [config] :as deps}]
  (let [{:keys [wait-timeout-s max-concurrent-waits poll-interval-ms]} (settings config)
        waits (Semaphore. (int max-concurrent-waits))]
    (fn [req]
      (if-not (.tryAcquire waits)
        (openai/error-response 429 "too_many_pending_transcriptions"
                               "Too many transcriptions are waiting. Retry later.")
        (let [released (AtomicBoolean. false)
              release! #(when (.compareAndSet released false true) (.release waits))]
          (try
            (let [{:keys [session-id tenant-id params]}
                  (uploads/create-upload-session! deps req {:prepare-params #(prepare-params config %)
                                                            :max-form-items max-form-items})
                  {options :ok} (openai/parse-request config params)
                  track-id (:track-id options)
                  session-uuid (UUID/fromString session-id)
                  log-ctx {:session_id session-id :tenant_id (str tenant-id) :track_id track-id}]
              (log/info "OpenAI transcription waiting for final transcript" log-ctx)
              (respond-async
               req
               (fn [respond! cancelled?]
                 (wait-for-transcript!
                  #(fetch-final-transcript (get-in deps [:db :ds]) tenant-id session-uuid track-id)
                  {:timeout-ms (* 1000 (long wait-timeout-s))
                   :poll-interval-ms poll-interval-ms
                   :cancelled? cancelled?}
                  (fn [{:keys [transcript]}]
                    (if transcript
                      (log/info "OpenAI transcription completed" log-ctx)
                      (log/info "OpenAI transcription wait timed out" log-ctx))
                    (respond! (try
                                (if transcript
                                  (openai/render options transcript)
                                  (timeout-response session-id wait-timeout-s))
                                (catch Throwable t
                                  (log/error t "Rendering the OpenAI transcription failed" log-ctx)
                                  (openai/error-response 500 "internal_error" "The transcription request failed"
                                                         nil {:session_id session-id})))))))
               release!))
            (catch clojure.lang.ExceptionInfo e
              (release!)
              (if-let [rejection (uploads/rejection e)]
                (if-let [message (:openai-message rejection)]
                  (openai/error-response (:status rejection) (:message rejection) message (:param rejection))
                  (upload-error rejection))
                (do (log/error e "OpenAI transcription failed" {:uri (:uri req)})
                    (openai/error-response 500 "internal_error" "The transcription request failed"))))
            (catch Throwable t
              (release!)
              (log/error t "OpenAI transcription failed" {:uri (:uri req)})
              (openai/error-response 500 "internal_error" "The transcription request failed"))))))))

(defn- legacy-error
  "Return {:status :message} when `resp` carries the BFF error body
  {:ok false :message string} (as data or as a JSON string), else nil."
  [resp]
  (let [body (:body resp)
        data (cond
               (map? body) body
               (and (string? body) (< (count body) 4096))
               (try (cheshire/parse-string body true) (catch Exception _ nil)))]
    (when (and (map? data) (false? (:ok data)) (string? (:message data)))
      {:status (:status resp) :message (:message data)})))

(defn wrap-openai-errors
  "Ring middleware that rewrites BFF error bodies ({:ok false :message})
  from authentication and access checks into the OpenAI error shape.

  A missing or invalid token becomes 401 `invalid_api_key`, like the
  OpenAI API. Other responses pass through unchanged."
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (if-let [{:keys [status message]} (legacy-error resp)]
        (if (contains? #{"missing-token" "invalid-token"} message)
          (openai/error-response 401 "invalid_api_key" "Missing or invalid bearer token")
          (openai/error-response status message message))
        resp))))
