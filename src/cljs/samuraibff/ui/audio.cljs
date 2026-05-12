(ns samuraibff.ui.audio
  "Microphone capture + audio websocket streaming.

  Captures microphone audio using Web Audio, downsamples to 16kHz, converts
  float32 samples to PCM16LE, and streams ArrayBuffer frames to /ws/audio.

  Public API:
  - start-audio!
  - stop-audio!"
  (:require
   [samuraibff.ui.env :as env]
   [samuraibff.ui.store :as store]
   [samuraibff.ui.util :as util]))

(def ^:private target-sample-rate
  "We always send 16kHz PCM16LE to the backend."
  16000)

(defonce ^:private audio-ws* (atom nil))
(defonce ^:private audio-ctx* (atom nil))
(defonce ^:private processor* (atom nil))
(defonce ^:private media-stream* (atom nil))
(defonce ^:private system-stream* (atom nil))

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

  (when-let [ms @system-stream*]
    (try
      (doseq [t (array-seq (.getTracks ms))]
        (.stop t))
      (catch :default _ nil)))
  (reset! system-stream* nil)

  (when-let [ws @audio-ws*]
    (try (.close ws) (catch :default _ nil)))
  (reset! audio-ws* nil)

  nil)

(defn- list-desktop-sources!
  "List available desktop capture sources (Electron-only).

  Returns:
  - Promise resolving to vector of {:id :name :thumbnailDataUrl :appIconDataUrl}
  - rejects if not in Electron."
  []
  (if-not (env/electron?)
    (js/Promise.reject (js/Error. "desktop capture not available (not Electron)"))
    (-> (.listDesktopSources (.-samuraibffElectron js/window)
                             #js {:types #js ["screen" "window"]
                                  :thumbnailSize #js {:width 320 :height 200}})
        (.then (fn [xs]
                 (->> (js->clj xs :keywordize-keys true)
                      vec))))))

(defn pick-system-source!
  "Pick a system capture source.

  Current implementation: best-effort auto-select.

  Behavior:
  - If an explicit :system_source_id is already set in store, keep it.
  - Otherwise, pick the first available screen.

  Returns: Promise resolving to {:id :name}."
  []
  (let [controls (get-in @store/session* [:controls])
        existing-id (:system_source_id controls)
        existing-name (:system_source_name controls)]
    (if (seq (str existing-id))
      (js/Promise.resolve {:id existing-id :name existing-name})
      (-> (list-desktop-sources!)
          (.then (fn [sources]
                   (let [screen (or (first (filter (fn [s] (.startsWith (str (:id s)) "screen:")) sources))
                                    (first sources))]
                     (when-not screen
                       (throw (js/Error. "No desktop capture sources available")))
                     (store/set-session-control! :system_source_id (:id screen))
                     (store/set-session-control! :system_source_name (:name screen))
                     {:id (:id screen)
                      :name (:name screen)})))))))

(defn- get-mic-stream!
  "Capture microphone stream.

  Returns: Promise resolving to MediaStream."
  []
  (.getUserMedia (.-mediaDevices js/navigator)
                #js {:audio #js {:channelCount 1
                                  :noiseSuppression false
                                  :echoCancellation false}
                      :video false}))

(defn- get-system-stream!
  "Capture system/desktop stream (Electron-only) using a chosen desktop source id.

  Returns: Promise resolving to MediaStream."
  [source-id]
  (if-not (env/electron?)
    (js/Promise.reject (js/Error. "system capture not available (not Electron)"))
    (-> (pick-system-source!)
        (.then (fn [{:keys [id]}]
                 (let [sid (or source-id id)]
                   (when-not (seq (str sid))
                     (throw (js/Error. "Missing desktop source id")))
                   ;; Chromium desktop constraints.
                   (.getUserMedia (.-mediaDevices js/navigator)
                                 #js {:audio #js {:mandatory #js {:chromeMediaSource "desktop"
                                                                 :chromeMediaSourceId sid}}
                                       :video #js {:mandatory #js {:chromeMediaSource "desktop"
                                                                  :chromeMediaSourceId sid}}})))))))

(defn- start-streaming!
  "Start capture from one or more MediaStreams and send frames to WS.

  Inputs:
  - ws: WebSocket
  - {:keys [mic-stream system-stream mic-gain system-gain]}

  Returns: truthy when started."
  [^js ws {:keys [mic-stream system-stream mic-gain system-gain]}]
  (let [ctx (js/AudioContext.)
        proc (.createScriptProcessor ctx 2048 1 1)
        dst (.createGain ctx)]
    (reset! audio-ctx* ctx)
    (reset! processor* proc)

    ;; Sum inputs into `dst`, then connect to processor.
    (when mic-stream
      (reset! media-stream* mic-stream)
      (let [src (.createMediaStreamSource ctx mic-stream)
            g (.createGain ctx)]
        (set! (.-gain g) (or mic-gain 1.0))
        (.connect src g)
        (.connect g dst)))

    (when system-stream
      (reset! system-stream* system-stream)
      (let [src (.createMediaStreamSource ctx system-stream)
            g (.createGain ctx)]
        (set! (.-gain g) (or system-gain 1.0))
        (.connect src g)
        (.connect g dst)))

    (.connect dst proc)
    (.connect proc (.-destination ctx))

    (set! (.-onaudioprocess proc)
          (fn [e]
            (when (and ws (= 1 (.-readyState ws)))
              (let [buf (.-inputBuffer e)
                    ch0 (.getChannelData buf 0)
                    ds (downsample ch0 (.-sampleRate ctx))
                    i16 (float32->pcm16le ds)]
                (.send ws (.-buffer i16))))))

    (store/append-log!
      (str "[audio] capture started"
           " mic=" (boolean mic-stream)
           " system=" (boolean system-stream)))
    true))

(defn- start-capture!
  "Start capture based on current UI controls.

  Returns: Promise resolving truthy when started."
  [^js ws]
  (let [controls (get-in @store/session* [:controls])
        mode (:audio_source controls)
        mode (or mode :mic)
        system-id (:system_source_id controls)
        mic-gain (:mic_gain controls)
        system-gain (:system_gain controls)]
    (-> (case mode
          :system
          (-> (get-system-stream! system-id)
              (.then (fn [sys]
                       (when (empty? (array-seq (.getAudioTracks sys)))
                         (throw (js/Error. "Selected system source has no audio track")))
                       (start-streaming! ws {:mic-stream nil
                                             :system-stream sys
                                             :mic-gain mic-gain
                                             :system-gain system-gain}))))

          :mix
          (-> (get-mic-stream!)
              (.then (fn [mic]
                       (-> (get-system-stream! system-id)
                           (.then (fn [sys]
                                    (when (empty? (array-seq (.getAudioTracks sys)))
                                      (throw (js/Error. "Selected system source has no audio track")))
                                    (start-streaming! ws {:mic-stream mic
                                                          :system-stream sys
                                                          :mic-gain mic-gain
                                                          :system-gain system-gain})))))))

          ;; default mic
          (-> (get-mic-stream!)
              (.then (fn [mic]
                       (start-streaming! ws {:mic-stream mic
                                             :system-stream nil
                                             :mic-gain mic-gain
                                             :system-gain system-gain}))))
          )
        (.catch (fn [e]
                  (store/set-ws-status! :audio :error (str e))
                  (store/append-log! (str "[audio] failed to start: " e))
                  (stop-audio!)
                  (throw e))))))

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
         url (util/ws-url "/ws/audio" qp {:backend-base-url (env/backend-base-url)})
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
