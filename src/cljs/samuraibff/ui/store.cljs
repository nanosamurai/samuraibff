(ns samuraibff.ui.store
  "UI application state store.

  We keep state in plain ClojureScript atoms (no Reagent ratoms).

  Atoms:
  - `session*`   : current session form state
  - `ws-status*` : statuses of events/audio websockets
  - `segments*`  : transcript messages (realtime ASR + refined)
  - `log*`       : debug log lines
  - `running?*`  : whether capture/streaming is running

  All public functions are side-effecting and named with verbs."
  (:require
    [samuraibff.ui.transcript :as transcript]
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

(defonce transcript-zero-s*
  (atom nil))

(defonce log*
  (atom []))

(defonce running?*
  (atom false))

(defonce recordings*
  (atom []))

(defonce auth*
  (atom {:status :unknown
         :detail nil}))

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
  "Set the current session id (string).

  Note: we clear transcript state because session_id is an isolation boundary
  for the transcript." 
  [session-id]
  (swap! session* assoc :id (or session-id ""))
  ;; clear-segments! is defined later in this namespace.
  (reset! segments* [])
  (reset! transcript-zero-s* nil)
  nil)

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
  "Clear transcript segments/messages and reset transcript time base." 
  []
  (reset! segments* [])
  (reset! transcript-zero-s* nil)
  nil)

(defn set-auth-status!
  "Set authentication status.

  Inputs:
  - status: keyword (:unknown | :loading | :authenticated | :anonymous)
  - detail: optional map (e.g. {:user {...} :tenant_id \"...\"})

  Returns: nil." 
  [status detail]
  (reset! auth* {:status status :detail detail})
  nil)

(defn- rebase-event-times
  "Rebase an ASR/refined event to start at 0.0 in the UI.

  Problem this solves:
  - backend `start_s/end_s` can be absolute (e.g. derived from monotonic clock)
    and may not start at 0 when the user hits Start.

  Approach:
  - capture the first observed `start_s` as UI zero
  - subtract it from all subsequent events

  Inputs:
  - ev: map with :start_s/:end_s

  Returns: updated event map." 
  [ev]
  (let [start (double (or (:start_s ev) 0))
        end (double (or (:end_s ev) 0))
        zero (or @transcript-zero-s*
                 (do (reset! transcript-zero-s* start)
                     start))
        start' (max 0 (- start zero))
        end' (max start' (- end zero))]
    (assoc ev :start_s start' :end_s end')))

(defn upsert-asr!
  "Insert or update a realtime ASR message.

  Input:
  - ev: map decoded from ws event, expects keys:
    :seq, :ts_ms, :start_s, :end_s, :text, :speaker (optional), :lang (optional), :final

  Returns: nil." 
  [ev]
  (let [ev' (rebase-event-times ev)]
    (swap! segments*
           (fn [xs]
             (let [xs (transcript/upsert-asr xs ev')
                   xs (if (> (count xs) max-segments)
                        (subvec (vec xs) (- (count xs) max-segments))
                        (vec xs))]
               xs))))
  nil)

(defn apply-refined!
  "Apply a refined transcript message.

  Important:
  - For now we do NOT rebase refined events in the UI.

  Reason:
  - in practice, refined (WhisperX) timestamps may already be relative to the
    recording start, while realtime ASR timestamps can be offset (e.g. session-
    relative). Rebasing refined using the ASR-derived baseline can collapse
    times to 0..0, which breaks replacement.

  We log missing/degenerate timing for debugging.

  Input:
  - ev: map decoded from ws event, expects keys:
    :seq, :ts_ms, :start_s, :end_s, :text, :speaker (optional), :lang (optional)

  Returns: nil."
  [ev]
  (let [start (:start_s ev)
        end (:end_s ev)]
    (when (or (nil? start) (nil? end) (= (double (or start 0)) (double (or end 0))))
      (append-log! (str "[refined] suspicious times start=" (pr-str start)
                        " end=" (pr-str end)
                        " (no-rebase)")))
    (swap! segments*
           (fn [xs]
             (let [xs (transcript/apply-refined xs ev)
                   xs (if (> (count xs) max-segments)
                        (subvec (vec xs) (- (count xs) max-segments))
                        (vec xs))]
               xs))))
  nil)
