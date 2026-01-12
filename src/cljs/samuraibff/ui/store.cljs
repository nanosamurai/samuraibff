(ns samuraibff.ui.store
  "UI application state store.

  We keep state in plain ClojureScript atoms (no Reagent ratoms).

  Atoms:
  - `session*`   : current session form state
  - `ws-status*` : statuses of events/audio websockets
  - `segments*`  : transcript segments (realtime ASR)
  - `log*`       : debug log lines
  - `running?*`  : whether capture/streaming is running

  All public functions are side-effecting and named with verbs."
  (:require
    [samuraibff.ui.util :as util]))

(defonce route*
  (atom {:page :recordings
         :params {}}))

(defonce session*
  (atom {:id ""
         :lang "cs"}))

(defonce ws-status*
  (atom {:events {:status :disconnected :detail nil}
         :audio  {:status :disconnected :detail nil}}))

(defonce segments*
  (atom []))

(defonce log*
  (atom []))

(defonce running?*
  (atom false))

(defonce recordings*
  (atom []))

(def ^:private max-log-lines 200)
(def ^:private max-segments 400)

(defn set-route!
  "Set the current route.

  Input:
  - route: {:page keyword :params map}

  Returns: nil."
  [route]
  (reset! route* route)
  nil)

(defn set-session-id!
  "Set the current session id (string)."
  [session-id]
  (swap! session* assoc :id (or session-id "")))

(defn set-lang!
  "Set current language code (string; empty allowed for auto)."
  [lang]
  (swap! session* assoc :lang (or lang "")))

(defn set-running!
  "Set whether the UI is currently streaming audio." 
  [running?]
  (reset! running?* (boolean running?)))

(defn add-recording!
  "Add an in-memory recording session.

  Inputs:
  - rec: map with keys:
    - :session_id string
    - :created_at_ms int
    - :status keyword (:ready|:recording|:stopped)

  Returns: nil."
  [rec]
  (swap! recordings*
         (fn [xs]
           (let [xs (vec xs)
                 sid (:session_id rec)
                 xs (remove #(= sid (:session_id %)) xs)]
             (conj (vec xs) rec))))
  nil)

(defn set-recording-status!
  "Update a recording status in the in-memory list.

  Inputs:
  - session-id: string
  - status: keyword (:ready|:recording|:stopped)

  Returns: nil."
  [session-id status]
  (swap! recordings*
         (fn [xs]
           (mapv (fn [r]
                   (if (= session-id (:session_id r))
                     (assoc r :status status)
                     r))
                 xs)))
  nil)

(defn set-ws-status!
  "Update websocket status.

  Inputs:
  - which: :events or :audio
  - status: keyword (e.g. :disconnected/:connecting/:connected/:error)
  - detail: optional string

  Returns: nil."
  [which status detail]
  (swap! ws-status* assoc which {:status status :detail detail})
  nil)

(defn append-log!
  "Append a line to the UI debug log (keeps only last `max-log-lines`)."
  [s]
  (let [line (str "[" (.toISOString (js/Date. (util/now-ms))) "] " s)]
    (swap! log*
           (fn [lines]
             (let [lines (conj (vec lines) line)]
               (if (> (count lines) max-log-lines)
                 (subvec lines (- (count lines) max-log-lines))
                 lines))))
    nil))

(defn clear-log!
  "Clear debug log."
  []
  (reset! log* [])
  nil)

(defn clear-segments!
  "Clear transcript segments."
  []
  (reset! segments* [])
  nil)

(defn upsert-asr!
  "Insert or update a realtime ASR segment.

  For MVP we keep this simple:
  - if the newest segment is also non-final, replace it (partial updates)
  - otherwise append

  Input:
  - ev: map decoded from ws event, expects keys:
    :start_s, :end_s, :text, :speaker (optional), :lang (optional), :final

  Returns: nil."
  [ev]
  (let [seg {:type "asr"
             :start_s (double (or (:start_s ev) 0))
             :end_s (double (or (:end_s ev) 0))
             :text (str (or (:text ev) ""))
             :speaker (some-> (:speaker ev) str)
             :lang (some-> (:lang ev) str)
             :final (boolean (:final ev))
             :received_at_ms (util/now-ms)}]
    (swap! segments*
           (fn [xs]
             (let [xs (vec xs)
                   n (count xs)
                   last-seg (when (pos? n) (nth xs (dec n)))
                   xs (cond
                        (and last-seg
                             (= "asr" (:type last-seg))
                             (false? (:final last-seg)))
                        (assoc xs (dec n) seg)

                        :else
                        (conj xs seg))]
               (if (> (count xs) max-segments)
                 (subvec xs (- (count xs) max-segments))
                 xs))))
    nil))
