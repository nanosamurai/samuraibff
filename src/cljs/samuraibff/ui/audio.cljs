(ns samuraibff.ui.audio
  "Microphone capture + audio websocket streaming.

  Captures microphone audio using Web Audio, downsamples to 16kHz, converts
  float32 samples to PCM16LE, and streams ArrayBuffer frames to /ws/audio.

  Public API:
  - start-audio!
  - stop-audio!"
  (:require
   [samuraibff.ui.store :as store]
   [samuraibff.ui.util :as util]))

(def ^:private target-sample-rate
  "We always send 16kHz PCM16LE to the backend."
  16000)

(defonce ^:private audio-ws* (atom nil))
(defonce ^:private audio-ctx* (atom nil))
(defonce ^:private processor* (atom nil))
(defonce ^:private media-stream* (atom nil))

(defn- float32->pcm16le
  "Convert Float32Array samples in range [-1,1] to an Int16Array.

  Returns: Int16Array."
  [^js/Float32Array samples]
  (let [n (.-length samples)
        out (js/Int16Array. n)]
    (dotimes [i n]
      (let [s (aget samples i)
            s (max -1 (min 1 s))
            v (if (neg? s)
                (* s 0x8000)
                (* s 0x7FFF))]
        (aset out i (int v))))
    out))

(defn- downsample
  "Downsample Float32Array to `target-sample-rate`.

  Supports arbitrary input sample rates via linear interpolation.

  Returns: Float32Array."
  [^js/Float32Array input in-rate]
  (if (= in-rate target-sample-rate)
    input
    (let [ratio (/ target-sample-rate in-rate)
          out-len (js/Math.floor (* (.-length input) ratio))
          out (js/Float32Array. out-len)]
      (dotimes [i out-len]
        (let [t (/ i ratio)
              i0 (js/Math.floor t)
              frac (- t i0)
              s0 (or (aget input i0) 0)
              s1 (or (aget input (inc i0)) 0)]
          (aset out i (+ s0 (* (- s1 s0) frac)))))
      out)))

(defn stop-audio!
  "Stop microphone capture and close the audio websocket.

  Returns: nil."
  []
  (store/set-ws-status! :audio :disconnected nil)

  (when-let [p @processor*]
    (try (.disconnect p) (catch :default _ nil)))
  (reset! processor* nil)

  (when-let [ctx @audio-ctx*]
    (try (.close ctx) (catch :default _ nil)))
  (reset! audio-ctx* nil)

  (when-let [ms @media-stream*]
    (try
      (doseq [t (array-seq (.getTracks ms))]
        (.stop t))
      (catch :default _ nil)))
  (reset! media-stream* nil)

  (when-let [ws @audio-ws*]
    (try (.close ws) (catch :default _ nil)))
  (reset! audio-ws* nil)

  nil)

(defn- start-capture!
  "Start mic capture and wire audio callback that sends binary frames to WS.

  Returns: Promise resolving truthy when started."
  [^js ws]
  (-> (.getUserMedia (.-mediaDevices js/navigator)
                     #js {:audio #js {:channelCount 1
                                      :noiseSuppression false
                                      :echoCancellation false}
                          :video false})
      (.then
       (fn [stream]
         (reset! media-stream* stream)
         (let [ctx (js/AudioContext.)
               src (.createMediaStreamSource ctx stream)
               proc (.createScriptProcessor ctx 2048 1 1)]
           (reset! audio-ctx* ctx)
           (reset! processor* proc)

           (.connect src proc)
           (.connect proc (.-destination ctx))

           (set! (.-onaudioprocess proc)
                 (fn [e]
                   (when (and ws (= 1 (.-readyState ws)))
                     (let [buf (.-inputBuffer e)
                           ch0 (.getChannelData buf 0)
                           ds (downsample ch0 (.-sampleRate ctx))
                           i16 (float32->pcm16le ds)]
                       (.send ws (.-buffer i16))))))

           (store/append-log! "[audio] capture started")
           true)))
      (.catch (fn [e]
                (store/set-ws-status! :audio :error (str e))
                (store/append-log! (str "[audio] failed to start: " e))
                (stop-audio!)
                (throw e)))))

(defn start-audio!
  "Open audio websocket and start microphone capture.

  Inputs:
  - session-id: string
  - lang: string (optional; empty = auto)

  Returns:
  - Promise resolving truthy when capture started."
  [session-id lang]
  (stop-audio!)
  (let [controls (get-in @store/session* [:controls])
        qp (cond-> {:session_id session-id
                    :lang (or lang "")
                    :sample_rate target-sample-rate

                    ;; Output selection + retention
                    :realtime (if (false? (:realtime controls)) "false" "true")
                    :refined (if (false? (:refined controls)) "false" "true")
                    :final (if (false? (:final controls)) "false" "true")
                    :store_recording (if (false? (:store_recording controls)) "false" "true")

                    ;; Realtime knob
                    :rt_partial_enable (if (false? (:rt_partial_enable controls)) "false" "true")}

             ;; Optional numeric knobs (omit when nil)
             (some? (:rt_window_sec controls)) (assoc :rt_window_sec (:rt_window_sec controls))
             (some? (:rt_overlap_sec controls)) (assoc :rt_overlap_sec (:rt_overlap_sec controls))
             (some? (:rt_emit_every_sec controls)) (assoc :rt_emit_every_sec (:rt_emit_every_sec controls))
             (some? (:refinement_window_sec controls)) (assoc :refinement_window_sec (:refinement_window_sec controls)))
        url (util/ws-url "/ws/audio" qp)
        ws (js/WebSocket. url)]
    (reset! audio-ws* ws)
    (store/set-ws-status! :audio :connecting url)

    (set! (.-binaryType ws) "arraybuffer")

    (js/Promise.
     (fn [resolve reject]
       (set! (.-onopen ws)
             (fn [_]
               (store/set-ws-status! :audio :connected nil)
               (store/append-log! (str "[audio] ws connected " url))
               (-> (start-capture! ws)
                   (.then (fn [_] (resolve true)))
                   (.catch (fn [e] (reject e))))))

       (set! (.-onclose ws)
             (fn [e]
               (store/set-ws-status! :audio :disconnected (str "code=" (.-code e)))
               (store/append-log! (str "[audio] ws closed code=" (.-code e)
                                       " reason=" (.-reason e)))
               (reset! audio-ws* nil)
               (stop-audio!)))

       (set! (.-onerror ws)
             (fn [_]
               (store/set-ws-status! :audio :error "onerror")
               (store/append-log! "[audio] websocket error")
               (reject (js/Error. "audio websocket error"))))))))
