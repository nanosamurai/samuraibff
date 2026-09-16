(ns samuraibff.stream-controls
  "Stream-level controls (outputs + retention + realtime knobs).

  This namespace is the single source of truth for parsing and validating the
  per-stream controls that originate in the UI/SDK and are transported to the
  BFF via `/ws/audio` query params.

  It is used to:
  - decide whether BFF starts rtservice gRPC (realtime)
  - decide whether BFF publishes `audio.raw` to Kafka (refined/final)
  - attach stream snapshot headers to Kafka (`x-outputs`, `x-store-recording`)
  - carry per-track realtime settings to the gRPC fan-out
  - persist the controls into Postgres (sessions.stream_controls jsonb)

  Security / cost:
  - output selections and refinement windows are validated/clamped
  - service-owned settings pass through; UI boundaries apply in this spike
  - invalid combinations are rejected before WS upgrade"
  (:require
   [clojure.string :as str]
   [jsonista.core :as json]))

(def ^:private default-controls
  "Default stream controls (backwards compatible)."
  {:realtime true
   :refined true
   :final true
   :store_recording true})

;; How often WhisperX refinement should run (slice window). This is currently
;; implemented in xamurai as a default env var (WHISPERX_SLICE_SECONDS=60).
;; We transport it per-stream via Kafka header for future/worker support.
(def ^:private refinement-window-min-sec 10.0)
(def ^:private refinement-window-max-sec 600.0)

(defn- parse-bool
  "Parse a boolean from a string-like input.

  Inputs:
  - v: any (typically string)
  - default: boolean

  Returns: boolean."
  [v default]
  (let [raw (some-> v str str/trim str/lower-case)]
    (cond
      (or (nil? raw) (str/blank? raw)) (boolean default)
      (#{"1" "true" "yes" "y" "on"} raw) true
      (#{"0" "false" "no" "n" "off"} raw) false
      :else (boolean default))))

(defn- parse-finite-double
  "Parse a finite double from a string-like input.

  Returns:
  - double, or nil when unparseable / NaN / +/-Infinity."
  [v]
  (when (some? v)
    (try
      (let [x (Double/parseDouble (str v))]
        (when (Double/isFinite x)
          (double x)))
      (catch Exception _
        nil))))

(defn- clamp
  "Clamp a number x into [minv, maxv]."
  [x minv maxv]
  (-> x (max minv) (min maxv)))

(defn configured-async-tracks
  "Return ordered IDs, display labels and defaults from deployment config.
  Accepts BFF config; returns UI metadata only, without worker discovery."
  [config]
  (vec (for [[stage config-key] [[:refined :refinement-tracks] [:final :final-tracks]]
             :let [ids (or (seq (get config config-key)) ["whisperx"])]
             id ids]
         {:stage (name stage)
          :track_id id
          :display_name (or (get-in config [:track-labels stage (keyword id)])
                            (when (= id "whisperx") "WhisperX") id)
          :default_selected (= id (first ids))})))

(defn with-track-labels
  "Snapshot selected deployment labels beside validated controls.
  Accepts controls and config maps; returns controls with a small track_labels map.
  Client-provided labels are never used."
  [controls config]
  (assoc controls :track_labels
         (reduce (fn [labels {:keys [stage track_id display_name]}]
                   (let [stage (keyword stage)
                         selection (get controls (if (= stage :final) :final_tracks :refinement_tracks))]
                     (if (and (get controls stage) (some #{track_id} selection))
                       (assoc-in labels [stage (keyword track_id)] display_name)
                       labels)))
                 {} (configured-async-tracks config))))

(defn parse-and-validate
  "Parse stream controls from `/ws/audio` query parameters and return a
  validated + clamped control map.

  Supported query params (all optional; defaults are backwards compatible):
  - outputs:
    - realtime=true|false
    - realtime_tracks=track-a,track-b
    - refined=true|false
    - final=true|false
  - retention:
    - store_recording=true|false
  - realtime_settings: JSON object keyed by track ID; service-owned values

  Optional refined/WhisperX knob:
  - refinement_window_sec (double)

  Validation rules:
  - at least one output must be enabled
  - explicit realtime track IDs must be a non-empty subset of the
    operator-configured allowlist
  - if final=false then store_recording is forced false (no recording needed)
  - realtime settings are passed through; the UI uses service-owned boundaries

  Returns: map
  {:realtime boolean :refined boolean :final boolean
   :realtime_tracks [string ...]?
   :store_recording boolean
   :refinement_window_sec double?
   :realtime_settings {track-id {setting value}}}

  Throws:
  - ex-info {:type :samuraibff.stream-controls/invalid-controls ...} on invalid.

  The one-argument arity preserves legacy parsing without resolving a track
  selection. The two-argument arity accepts the ordered vector of configured
  track IDs and resolves an omitted selection to all of them."
  ([params]
   (parse-and-validate params nil))
  ([params available-realtime-tracks]
   (parse-and-validate params available-realtime-tracks ["whisperx"]))
  ([params available-realtime-tracks available-final-tracks]
   (parse-and-validate params available-realtime-tracks available-final-tracks ["whisperx"]))
  ([params available-realtime-tracks available-final-tracks available-refinement-tracks]
   (let [realtime? (parse-bool (or (get params :realtime) (get params "realtime"))
                               (:realtime default-controls))
         refined? (parse-bool (or (get params :refined) (get params "refined"))
                              (:refined default-controls))
         final? (parse-bool (or (get params :final) (get params "final"))
                            (:final default-controls))
         final-tracks-raw (or (get params :final_tracks) (get params "final_tracks"))
         final-tracks (if (some? final-tracks-raw)
                        (mapv str/trim (str/split (str final-tracks-raw) #"," -1))
                        [(first available-final-tracks)])
         refinement-tracks-raw (or (get params :refinement_tracks) (get params "refinement_tracks"))
         refinement-tracks (if (some? refinement-tracks-raw)
                             (mapv str/trim (str/split (str refinement-tracks-raw) #"," -1))
                             [(first available-refinement-tracks)])
         _ (when (or (> (count refinement-tracks) 4)
                     (not= (count refinement-tracks) (count (distinct refinement-tracks)))
                     (some #(not (contains? (set available-refinement-tracks) %)) refinement-tracks)
                     (some str/blank? refinement-tracks))
             (throw (ex-info "Refinement tracks must be a non-empty subset of configured tracks"
                             {:type :samuraibff.stream-controls/invalid-controls
                              :reason :invalid-refinement-tracks})))
         _ (when (or (> (count final-tracks) 4)
                     (not= (count final-tracks) (count (distinct final-tracks)))
                     (some #(not (contains? (set available-final-tracks) %)) final-tracks)
                     (some str/blank? final-tracks))
             (throw (ex-info "Final tracks must be a non-empty subset of configured tracks"
                             {:type :samuraibff.stream-controls/invalid-controls
                              :reason :invalid-final-tracks})))
         store-recording? (parse-bool (or (get params :store_recording) (get params "store_recording")
                                          (get params :store-recording) (get params "store-recording"))
                                      (:store_recording default-controls))
         realtime-tracks-raw (or (get params :realtime_tracks) (get params "realtime_tracks")
                                 (get params :realtime-tracks) (get params "realtime-tracks"))
         explicit-realtime-tracks? (some? realtime-tracks-raw)
         requested-realtime-tracks (when explicit-realtime-tracks?
                                     (mapv str/trim (str/split (str realtime-tracks-raw) #"," -1)))
         available-realtime-tracks (when (some? available-realtime-tracks)
                                     (mapv str available-realtime-tracks))
         available-realtime-track-set (set available-realtime-tracks)
         invalid-realtime-tracks? (or (and explicit-realtime-tracks?
                                           (or (empty? requested-realtime-tracks)
                                               (> (count requested-realtime-tracks) 4)
                                               (some str/blank? requested-realtime-tracks)
                                               (not= (count requested-realtime-tracks)
                                                     (count (distinct requested-realtime-tracks)))))
                                      (and explicit-realtime-tracks?
                                           (or (nil? available-realtime-tracks)
                                               (some #(not (contains? available-realtime-track-set %))
                                                     requested-realtime-tracks))))
         _ (when invalid-realtime-tracks?
             (throw (ex-info "Realtime tracks must be a non-empty subset of configured tracks"
                             {:type :samuraibff.stream-controls/invalid-controls
                              :reason :invalid-realtime-tracks})))
         realtime-tracks (when (seq available-realtime-tracks)
                           (if explicit-realtime-tracks?
                             (let [requested-set (set requested-realtime-tracks)]
                               (filterv requested-set available-realtime-tracks))
                             available-realtime-tracks))
         realtime-settings (json/read-value (or (get params :realtime_settings) (get params "realtime_settings") "{}")
                                            (json/object-mapper {:decode-key-fn keyword}))

         refinement-window (parse-finite-double (or (get params :refinement_window_sec) (get params "refinement_window_sec")
                                                    (get params :refinement_window) (get params "refinement_window")
                                                    (get params :refined_window_sec) (get params "refined_window_sec")))

         want-any? (or realtime? refined? final?)
         _ (when-not want-any?
             (throw (ex-info "At least one output must be enabled"
                             {:type :samuraibff.stream-controls/invalid-controls
                              :reason :no-outputs})))

        ;; Applied semantics.
         store-recording? (if final? store-recording? false)
         _ (when (and final? (> (count final-tracks) 1) (not store-recording?))
             (throw (ex-info "Multiple final tracks require store_recording=true"
                             {:type :samuraibff.stream-controls/invalid-controls
                              :reason :multiple-final-tracks-require-recording})))

         refinement-window (when (and refined? (some? refinement-window))
                             (clamp refinement-window refinement-window-min-sec refinement-window-max-sec))]
     (cond-> {:realtime realtime?
              :refined refined?
              :final final?
              :final_tracks final-tracks
              :refinement_tracks refinement-tracks
              :store_recording store-recording?
              :realtime_settings (if realtime? (select-keys realtime-settings (map keyword realtime-tracks)) {})}
       (seq realtime-tracks) (assoc :realtime_tracks realtime-tracks)

       (some? refinement-window) (assoc :refinement_window_sec refinement-window)))))

(defn outputs-header-value
  "Return the value for Kafka header `x-outputs` based on controls.

  Inputs:
  - controls: map as returned by `parse-and-validate`

  Returns: string (CSV, tokens ordered realtime,refined,final)."
  [{:keys [realtime refined final]}]
  (->> [(when realtime "realtime")
        (when refined "refined")
        (when final "final")]
       (remove nil?)
       (str/join ",")))

(defn kafka-headers
  "Return Kafka header map for audio.raw.

  Inputs:
  - controls: map as returned by `parse-and-validate`

  Returns:
  - map of {header-name string -> header-value byte[]}.

  Always included:
  - `x-outputs` (CSV: realtime,refined,final)
  - `x-store-recording` (true|false)

  Optionally included:
  - `x-refinement-window-sec` when refined=true and `:refinement_window_sec` is present."
  [controls]
  (cond-> {"x-outputs" (.getBytes ^String (outputs-header-value controls) "UTF-8")
           "x-final-tracks" (.getBytes ^String (str/join "," (or (:final_tracks controls) ["whisperx"])) "UTF-8")
           "x-refinement-tracks" (.getBytes ^String (str/join "," (or (:refinement_tracks controls) ["whisperx"])) "UTF-8")
           "x-store-recording" (.getBytes ^String (if (:store_recording controls) "true" "false") "UTF-8")}
    (and (true? (:refined controls)) (some? (:refinement_window_sec controls)))
    (assoc "x-refinement-window-sec"
           (.getBytes ^String (str (double (:refinement_window_sec controls))) "UTF-8"))))
