(ns samuraibff.transcripts.openai
  "Pure mapping between the OpenAI audio transcription API shape and
  nanosamurai final tracks.

  - `resolve-model`: OpenAI `model` -> configured final track ID.
  - `parse-request`: multipart params -> validated request options.
  - `render`: stored final transcript -> OpenAI response (json, text, srt,
    vtt, verbose_json).
  - `error-response`: OpenAI error body {:error {:message :type :param :code}}.

  The functions do no I/O and check no access."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [samuraibff.transcripts.export :as export]))

(def response-formats
  "Supported `response_format` values."
  #{"json" "text" "srt" "vtt" "verbose_json"})

(def ^:private granularities #{"segment" "word"})

(defn- final-track-ids
  "Return the configured final track IDs (default [\"whisperx\"])."
  [config]
  (or (seq (:final-tracks config)) ["whisperx"]))

(defn model-aliases
  "Return the model alias map {alias-string track-id-string} for `config`.

  Uses [:openai :model-aliases] when set; otherwise maps `whisper-1` to the
  default final track ([:default-tracks :final], or the first configured
  final track)."
  [config]
  (or (not-empty (get-in config [:openai :model-aliases]))
      {"whisper-1" (or (get-in config [:default-tracks :final])
                       (first (final-track-ids config)))}))

(defn available-models
  "Return the sorted model names a client may send: aliases whose target is a
  configured final track, plus every configured final track ID."
  [config]
  (let [tracks (set (final-track-ids config))]
    (vec (sort (distinct (concat (keep (fn [[alias track]] (when (tracks track) (name alias)))
                                       (model-aliases config))
                                 tracks))))))

(defn resolve-model
  "Map an OpenAI `model` value to a configured final track ID.

  Inputs: config map and model string (may be nil). An alias wins over a
  track ID of the same name. Returns the track ID string, or nil when the
  model is unknown or the alias points to a track that is not configured.
  Model names never select weights directly."
  [config model]
  (let [tracks (set (final-track-ids config))
        aliases (into {} (map (fn [[k v]] [(name k) v])) (model-aliases config))
        track (if (contains? aliases model) (get aliases model) model)]
    (when (and (string? track) (tracks track))
      track)))

(defn error-response
  "Return a Ring response with the OpenAI error shape.

  Inputs: HTTP status (int), code (string), human-readable message, optional
  param name (string or nil) and optional extra error fields (map).
  Returns {:status :headers :body} with a JSON string body
  {\"error\": {\"message\", \"type\", \"param\", \"code\", ...extra}}."
  ([status code message]
   (error-response status code message nil nil))
  ([status code message param]
   (error-response status code message param nil))
  ([status code message param extra]
   {:status status
    :headers {"Content-Type" "application/json; charset=utf-8"
              "Cache-Control" "no-store"
              "X-Content-Type-Options" "nosniff"}
    :body (cheshire/generate-string
           {:error (merge {:message message
                           :type (cond
                                   (= 401 status) "authentication_error"
                                   (= 403 status) "permission_error"
                                   (= 429 status) "rate_limit_error"
                                   (= 504 status) "timeout_error"
                                   (>= status 500) "server_error"
                                   :else "invalid_request_error")
                           :param param
                           :code code}
                          extra)})}))

(defn- text-param
  "Return a trimmed, non-blank single text value of multipart param `k`, or nil."
  [params k]
  (let [v (get params k)]
    (when (string? v)
      (not-empty (str/trim v)))))

(defn- multi-param
  "Return all text values of a repeated multipart param under any of `ks`."
  [params ks]
  (->> ks
       (mapcat #(let [v (get params %)] (if (sequential? v) v [v])))
       (filter string?)
       (map str/trim)
       (remove str/blank?)
       distinct
       vec))

(defn parse-request
  "Validate the OpenAI fields of a parsed multipart request.

  Inputs: config map and multipart params (string keys; repeated fields are
  vectors). `file` is checked by the upload code, not here.
  Returns {:ok {:model :track-id :language :response-format :granularities}}
  or {:error {:status :code :message :param}}.
  `prompt` and `temperature` are accepted and ignored."
  [config params]
  (let [model (text-param params "model")
        track-id (resolve-model config model)
        response-format (or (text-param params "response_format") "json")
        requested (multi-param params ["timestamp_granularities[]" "timestamp_granularities"])
        stream? (= "true" (some-> (text-param params "stream") str/lower-case))]
    (cond
      (nil? model)
      {:error {:status 400 :code "missing_model" :param "model"
               :message "you must provide a model parameter"}}

      (nil? track-id)
      {:error {:status 400 :code "model_not_found" :param "model"
               :message (str "The model `" (subs model 0 (min 100 (count model)))
                             "` does not exist. Available models: "
                             (str/join ", " (available-models config)))}}

      (not (contains? response-formats response-format))
      {:error {:status 400 :code "invalid_response_format" :param "response_format"
               :message (str "response_format must be one of: "
                             (str/join ", " (sort response-formats)))}}

      (not-every? granularities requested)
      {:error {:status 400 :code "invalid_timestamp_granularities" :param "timestamp_granularities[]"
               :message "timestamp_granularities[] values must be segment or word"}}

      stream?
      {:error {:status 400 :code "unsupported_parameter" :param "stream"
               :message "Streaming transcription is not supported"}}

      :else
      {:ok {:model model
            :track-id track-id
            :language (text-param params "language")
            :response-format response-format
            :granularities (set (or (not-empty requested) ["segment"]))}})))

(defn- one-line
  "Collapse whitespace runs to single spaces and trim."
  [s]
  (str/trim (str/replace (str (or s "")) #"\s+" " ")))

(defn transcript-text
  "Return the plain transcript text: the stored full text, or the segment
  texts joined by spaces when the full text is blank."
  [{:keys [full-text segments]}]
  (or (not-empty (one-line full-text))
      (str/join " " (keep #(not-empty (one-line (:text %))) segments))))

(defn- duration
  "Return the duration in seconds: the stored value or the last segment end."
  [{:keys [duration-s segments]}]
  (double (or (when (number? duration-s) duration-s)
              (some->> segments (keep :end_s) (filter number?) seq (apply max))
              0)))

(defn verbose-json
  "Build the OpenAI `verbose_json` document.

  Inputs: transcript {:segments [TranscriptSegment] :full-text :lang
  :duration-s} and the set of granularities. `language` is the stored
  language code (for example `en`), not an English language name. Segments
  carry id, seek (always 0), start, end and text; top-level `words` is added
  when `word` is requested and the track stored word timings."
  [transcript granularities]
  (let [segments (vec (remove #(str/blank? (:text %)) (:segments transcript)))
        words (when (contains? granularities "word")
                (vec (for [segment segments
                           word (:words segment)
                           :when (not (str/blank? (:text word)))]
                       {:word (str/trim (:text word))
                        :start (double (or (:start_s word) 0))
                        :end (double (or (:end_s word) (:start_s word) 0))})))]
    (cond-> {:task "transcribe"
             :language (or (:lang transcript) "")
             :duration (duration transcript)
             :text (transcript-text transcript)}
      (contains? granularities "segment")
      (assoc :segments (vec (map-indexed (fn [idx segment]
                                           {:id idx
                                            :seek 0
                                            :start (double (or (:start_s segment) 0))
                                            :end (double (or (:end_s segment) (:start_s segment) 0))
                                            :text (one-line (:text segment))})
                                         segments)))
      (seq words) (assoc :words words))))

(defn render
  "Render a stored final transcript as an OpenAI transcription response.

  Inputs: options from `parse-request` and transcript {:segments :full-text
  :lang :duration-s}. Returns a Ring response with a string body:
  json {\"text\"}, text, srt, vtt or verbose_json."
  [{:keys [response-format granularities]} transcript]
  (let [[content-type body]
        (case response-format
          "json" ["application/json; charset=utf-8"
                  (cheshire/generate-string {:text (transcript-text transcript)})]
          "verbose_json" ["application/json; charset=utf-8"
                          (cheshire/generate-string (verbose-json transcript granularities))]
          "text" ["text/plain; charset=utf-8" (str (transcript-text transcript) "\n")]
          "srt" ["text/plain; charset=utf-8" (export/->srt (:segments transcript))]
          "vtt" ["text/vtt; charset=utf-8" (export/->vtt (:segments transcript))])]
    {:status 200
     :headers {"Content-Type" content-type
               "Cache-Control" "no-store"
               "X-Content-Type-Options" "nosniff"}
     :body body}))
