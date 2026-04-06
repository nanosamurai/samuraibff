(ns samuraibff.stream-controls
  "Stream-level controls (outputs + retention + realtime knobs).

  This namespace is the single source of truth for parsing and validating the
  per-stream controls that originate in the UI/SDK and are transported to the
  BFF via `/ws/audio` query params.

  It is used to:
  - decide whether BFF starts rtservice gRPC (realtime)
  - decide whether BFF publishes `audio.raw` to Kafka (refined/final)
  - attach stream snapshot headers to Kafka (`x-outputs`, `x-store-recording`)
  - attach gRPC metadata (`x-rt-*` headers)
  - persist the controls into Postgres (sessions.stream_controls jsonb)

  Security / cost:
  - inputs are treated as untrusted and validated/clamped
  - invalid combinations are rejected before WS upgrade"
  (:require
   [clojure.string :as str]
   [samuraibff.grpc.metadata :as grpc.metadata]))

(def ^:private default-controls
  "Default stream controls (backwards compatible)."
  {:realtime true
   :refined true
   :final true
   :store_recording true
   :rt_partial_enable true})

(def ^:private rt-window-min-sec 1.0)
(def ^:private rt-window-max-sec 30.0)

(def ^:private rt-overlap-min-sec 0.0)

;; User requirement: emit_every must have minimum 1s due to perf concerns.
(def ^:private rt-emit-every-min-sec 1.0)

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

(defn- parse-double
  "Parse a finite double from a string-like input.

  Returns double or nil."
  [v]
  (when (some? v)
    (try
      (let [x (Double/parseDouble (str v))]
        (when (grpc.metadata/finite-double? x)
          (double x)))
      (catch Exception _
        nil))))

(defn- clamp
  "Clamp a number x into [minv, maxv]."
  [x minv maxv]
  (-> x (max minv) (min maxv)))

(defn parse-and-validate
  "Parse stream controls from `/ws/audio` query parameters and return a
  validated + clamped control map.

  Supported query params (all optional; defaults are backwards compatible):
  - outputs:
    - realtime=true|false
    - refined=true|false
    - final=true|false
  - retention:
    - store_recording=true|false
  - rtservice knobs:
    - rt_window_sec (double)
    - rt_overlap_sec (double)
    - rt_emit_every_sec (double)
    - rt_partial_enable=true|false

  Optional refined/WhisperX knob:
  - refinement_window_sec (double)

  Validation rules:
  - at least one output must be enabled
  - if final=false then store_recording is forced false (no recording needed)
  - realtime knobs are clamped only when realtime=true
    - window in [1,30]
    - overlap in [0, window]
    - emit_every in [1, window] when partial_enable=true

  Returns: map
  {:realtime boolean :refined boolean :final boolean
   :store_recording boolean
   :refinement_window_sec double?
   :rt_partial_enable boolean
   :rt_window_sec double? :rt_overlap_sec double? :rt_emit_every_sec double?}

  Throws:
  - ex-info {:type :samuraibff.stream-controls/invalid-controls ...} on invalid."
  [params]
  (let [realtime? (parse-bool (or (get params :realtime) (get params "realtime"))
                              (:realtime default-controls))
        refined? (parse-bool (or (get params :refined) (get params "refined"))
                             (:refined default-controls))
        final? (parse-bool (or (get params :final) (get params "final"))
                           (:final default-controls))
        store-recording? (parse-bool (or (get params :store_recording) (get params "store_recording")
                                         (get params :store-recording) (get params "store-recording"))
                                     (:store_recording default-controls))
        rt-partial-enable? (parse-bool (or (get params :rt_partial_enable) (get params "rt_partial_enable")
                                           (get params :rt-partial-enable) (get params "rt-partial-enable"))
                                       (:rt_partial_enable default-controls))
        rt-window (parse-double (or (get params :rt_window_sec) (get params "rt_window_sec")
                                    (get params :window_sec) (get params "window_sec")))
        rt-overlap (parse-double (or (get params :rt_overlap_sec) (get params "rt_overlap_sec")
                                     (get params :overlap_sec) (get params "overlap_sec")))
        rt-emit-every (parse-double (or (get params :rt_emit_every_sec) (get params "rt_emit_every_sec")
                                        (get params :emit_every_sec) (get params "emit_every_sec")))

        refinement-window (parse-double (or (get params :refinement_window_sec) (get params "refinement_window_sec")
                                            (get params :refinement_window) (get params "refinement_window")
                                            (get params :refined_window_sec) (get params "refined_window_sec")))

        want-any? (or realtime? refined? final?)
        _ (when-not want-any?
            (throw (ex-info "At least one output must be enabled"
                            {:type :samuraibff.stream-controls/invalid-controls
                             :reason :no-outputs})))

        ;; Applied semantics.
        store-recording? (if final? store-recording? false)

        ;; Clamp realtime knobs only when realtime is enabled.
        rt-window (when (and realtime? (some? rt-window))
                    (clamp rt-window rt-window-min-sec rt-window-max-sec))
        rt-overlap (when (and realtime? (some? rt-overlap))
                     (let [w (or rt-window rt-window-max-sec)]
                       (clamp rt-overlap rt-overlap-min-sec w)))

        refinement-window (when (and refined? (some? refinement-window))
                            (clamp refinement-window refinement-window-min-sec refinement-window-max-sec))
        rt-emit-every (when (and realtime? (some? rt-emit-every) rt-partial-enable?)
                        (let [w (or rt-window rt-window-max-sec)]
                          (clamp rt-emit-every rt-emit-every-min-sec w)))]
    (cond-> {:realtime realtime?
             :refined refined?
             :final final?
             :store_recording store-recording?
             :rt_partial_enable rt-partial-enable?}
      (some? rt-window) (assoc :rt_window_sec rt-window)
      (some? rt-overlap) (assoc :rt_overlap_sec rt-overlap)
      (some? rt-emit-every) (assoc :rt_emit_every_sec rt-emit-every)

      (some? refinement-window) (assoc :refinement_window_sec refinement-window))))

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

  Returns:
  - {" x-outputs " <bytes> " x-store-recording " <bytes> ...}"
  [controls]
  (cond-> {"x-outputs" (.getBytes ^String (outputs-header-value controls) "UTF-8")
           "x-store-recording" (.getBytes ^String (if (:store_recording controls) "true" "false") "UTF-8")}
    (and (true? (:refined controls)) (some? (:refinement_window_sec controls)))
    (assoc "x-refinement-window-sec" (.getBytes ^String (str (double (:refinement_window_sec controls))) "UTF-8"))))

(defn grpc-metadata
  "Return gRPC metadata map for rtservice based on controls.

  Returns: map string->string."
  [controls]
  (let [{:keys [rt_window_sec rt_overlap_sec rt_emit_every_sec rt_partial_enable]} controls]
    (cond-> {}
      (some? rt_window_sec)
      (assoc "x-rt-window-sec" (grpc.metadata/header-double rt_window_sec))

      (some? rt_overlap_sec)
      (assoc "x-rt-overlap-sec" (grpc.metadata/header-double rt_overlap_sec))

      (some? rt_emit_every_sec)
      (assoc "x-rt-emit-every-sec" (grpc.metadata/header-double rt_emit_every_sec))

      (some? rt_partial_enable)
      (assoc "x-rt-partial-enable" (if rt_partial_enable "true" "false")))))
