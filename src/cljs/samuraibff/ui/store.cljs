(ns samuraibff.ui.store
  "UI application state store.

  We keep state in plain ClojureScript atoms (no Reagent ratoms).

  Atoms:
  - `session*`   : current session form state
  - `ws-status*` : statuses of events/audio websockets
  - `asr-segments*`     : realtime ASR transcript messages
  - `refined-segments*` : refined realtime transcript messages
  - `log*`       : debug log lines
  - `running?*`  : whether capture/streaming is running

  All public functions are side-effecting and named with verbs."
  (:require
   [samuraibff.ui.api-credentials-store :as api-creds.store]
   [samuraibff.ui.transcript :as transcript]
   [samuraibff.ui.util :as util]))

(defonce route*
  (atom {:page :recordings
         :params {}}))

(defonce session*
  (atom {:id ""
         :title ""
         :lang "cs"}))

(defonce ws-status*
  (atom {:events {:status :disconnected :detail nil}
         :audio {:status :disconnected :detail nil}}))

(defonce segments*
  (atom []))

;; NOTE: `segments*` used to store ASR+refined in one merged feed.
;; We keep it for a short transitional period to avoid churn elsewhere,
;; but it is no longer used by UI rendering.

(defonce asr-segments*
  (atom []))

(defonce refined-segments*
  (atom []))

(defonce segments-by-session*
  (atom {}))

;; NOTE: split caches for ASR vs refined.
(defonce asr-by-session*
  (atom {}))

(defonce refined-by-session*
  (atom {}))

(defonce transcript-zero-s*
  (atom nil))

(defonce log*
  (atom []))

(defonce log-by-session*
  (atom {}))

(defonce running?*
  (atom false))

(defonce debug-asr-log?*
  (atom false))

(defn set-debug-asr-log!
  "Enable/disable compact per-ASR-event logging in the UI debug log.

  Inputs:
  - enabled?: boolean

  Returns: nil."
  [enabled?]
  (reset! debug-asr-log?* (boolean enabled?))
  nil)

(defn debug-asr-log-enabled?
  "Return true if compact ASR event logging is enabled."
  []
  (true? @debug-asr-log?*))

(defonce recordings*
  (atom []))

(defonce recordings-db*
  (atom []))

(defn set-recordings-db!
  "Replace the DB-backed recordings list.

  Inputs:
  - items: vector of recording/session maps (from /api/recordings)

  Returns: nil."
  [items]
  (reset! recordings-db* (vec (or items [])))
  nil)

(defn remove-recording-db!
  "Remove a recording/session from the DB-backed list.

  Inputs:
  - session-id: string

  Returns: nil."
  [session-id]
  (swap! recordings-db*
         (fn [xs]
           (vec (remove #(= (or session-id "") (:session_id %)) xs))))
  nil)

(defn cached-segments
  "Get cached transcript messages for a session.

  Inputs:
  - session-id string

  Returns:
  - vector of transcript message maps (possibly empty)."
  [session-id]
  (vec (get @segments-by-session* (or session-id "") [])))

(defn cached-asr-segments
  "Get cached realtime ASR transcript messages for a session.

  Inputs:
  - session-id string

  Returns:
  - vector of transcript message maps (possibly empty)."
  [session-id]
  (vec (get @asr-by-session* (or session-id "") [])))

(defn cached-refined-segments
  "Get cached refined realtime transcript messages for a session.

  Inputs:
  - session-id string

  Returns:
  - vector of transcript message maps (possibly empty)."
  [session-id]
  (vec (get @refined-by-session* (or session-id "") [])))

(defn cached-log
  "Get cached log lines for a session.

  Inputs:
  - session-id string

  Returns:
  - vector of strings (possibly empty)."
  [session-id]
  (vec (get @log-by-session* (or session-id "") [])))

(defonce speakers*
  (atom []))

(defonce api-credentials*
  (atom (api-creds.store/init-state)))

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
  (let [old-id (get @session* :id)
        new-id (or session-id "")]
    ;; Persist current transcript/log into per-session caches before switching.
    (when (and (string? old-id) (seq old-id))
      ;; legacy merged cache
      (swap! segments-by-session* assoc old-id (vec @segments*))
      ;; split caches
      (swap! asr-by-session* assoc old-id (vec @asr-segments*))
      (swap! refined-by-session* assoc old-id (vec @refined-segments*))
      (swap! log-by-session* assoc old-id (vec @log*)))

    (swap! session* assoc :id new-id)
    ;; clear-segments! is defined later in this namespace.
    (reset! segments* [])
    (reset! asr-segments* [])
    (reset! refined-segments* [])
    (reset! transcript-zero-s* nil)
    (reset! log* [])
    nil))

(defn set-session-title!
  "Set the current session title (string).

  Inputs:
  - title: string

  Returns: nil." 
  [title]
  (swap! session* assoc :title (or title ""))
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
  (let [line (str "[" (.toISOString (js/Date. (util/now-ms))) "] " s)
        sid (or (get @session* :id) "")]
    (swap! log*
           (fn [lines]
             (let [lines (conj (vec lines) line)]
               (if (> (count lines) max-log-lines)
                 (subvec lines (- (count lines) max-log-lines))
                 lines))))
    (when (seq sid)
      (swap! log-by-session*
             (fn [m]
               (let [xs (conj (vec (get m sid [])) line)
                     xs (if (> (count xs) max-log-lines)
                          (subvec xs (- (count xs) max-log-lines))
                          xs)]
                 (assoc m sid xs)))))
    nil))

(defn clear-log!
  "Clear debug log."
  []
  (reset! log* [])
  (let [sid (or (get @session* :id) "")]
    (when (seq sid)
      (swap! log-by-session* assoc sid [])))
  nil)

(defn clear-segments!
  "Clear transcript segments/messages and reset transcript time base."
  []
  (reset! segments* [])
  (reset! asr-segments* [])
  (reset! refined-segments* [])
  (reset! transcript-zero-s* nil)
  (let [sid (or (get @session* :id) "")]
    (when (seq sid)
      (swap! segments-by-session* assoc sid [])
      (swap! asr-by-session* assoc sid [])
      (swap! refined-by-session* assoc sid [])))
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

(defn set-speakers!
  "Replace the speakers list in state.

  Inputs:
  - items: vector of speaker maps

  Returns: nil."
  [items]
  (reset! speakers* (vec (or items [])))
  nil)

;; --- API Credentials state ---

(defn api-credentials-set-loading!
  "Set API credentials loading flag."
  [loading?]
  (swap! api-credentials* api-creds.store/set-loading loading?)
  nil)

(defn api-credentials-set-error!
  "Set a safe user-facing error message for API credentials.

  Inputs:
  - message: string? (nil clears)

  Returns nil."
  [message]
  (swap! api-credentials* api-creds.store/set-error message)
  nil)

(defn api-credentials-set-items!
  "Replace API credentials list.

  Inputs:
  - items: vector of credential maps

  Returns nil."
  [items]
  (swap! api-credentials* api-creds.store/set-items items)
  nil)

(defn api-credentials-toggle-show-revoked!
  "Toggle whether revoked credentials are shown."
  []
  (swap! api-credentials* api-creds.store/toggle-show-revoked)
  nil)

(defn api-credentials-open-secret!
  "Open the show-once secret modal.

  Inputs:
  - {:keys [credential-id client-id client-secret]} strings

  Returns nil."
  [{:keys [credential-id client-id client-secret]}]
  (swap! api-credentials* api-creds.store/open-secret-modal
         {:credential-id credential-id
          :client-id client-id
          :client-secret client-secret})
  nil)

(defn api-credentials-close-secret!
  "Close the show-once secret modal and clear any in-memory secret."
  []
  (swap! api-credentials* api-creds.store/close-secret-modal)
  nil)

(defn api-credentials-mark-secret-copied!
  "Set secret modal copied indicator."
  [copied?]
  (swap! api-credentials* api-creds.store/mark-secret-copied copied?)
  nil)

(defn api-credentials-mark-revoked!
  "Mark a credential revoked in local state."
  [credential-id]
  (swap! api-credentials* api-creds.store/mark-revoked credential-id)
  nil)

(defn remove-speaker!
  "Remove a speaker by id from state.

  Inputs:
  - speaker-id: string

  Returns: nil."
  [speaker-id]
  (swap! speakers* (fn [xs]
                     (vec (remove #(= speaker-id (:id %)) xs))))
  nil)

(defn prepend-speaker!
  "Add a speaker to the front of the list.

  Inputs:
  - item: speaker map

  Returns: nil."
  [item]
  (swap! speakers* (fn [xs]
                     (vec (cons item (or xs [])))))
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

  Side effects:
  - updates realtime transcript atoms
  - when `debug-asr-log?*` is enabled, appends a compact debug line to `log*`

  Returns: nil."
  [ev]
  (let [ev' (rebase-event-times ev)
        sid (or (:session_id ev) (get @session* :id) "")
        speaker (or (:speaker ev') "")
        start (double (or (:start_s ev') 0.0))
        end (double (or (:end_s ev') 0.0))
        final? (true? (:final ev'))
        text (str (or (:text ev') ""))]
    (when (debug-asr-log-enabled?)
      (append-log!
       (str "[asr] "
            (if final? "FINAL" "PARTIAL")
            " sp=" (pr-str speaker)
            " t=" (util/fmt-sec start) "→" (util/fmt-sec end)
            " len=" (count text)
            (when (seq text) (str " text=" (pr-str (subs text 0 (min 32 (count text)))))))))

    ;; legacy merged
    (swap! segments*
           (fn [xs]
             (let [xs (transcript/upsert-asr xs ev')]
               (if (> (count xs) max-segments)
                 (subvec (vec xs) (- (count xs) max-segments))
                 (vec xs)))))
    ;; split ASR
    (swap! asr-segments*
           (fn [xs]
             (let [xs (transcript/upsert-asr xs ev')]
               (if (> (count xs) max-segments)
                 (subvec (vec xs) (- (count xs) max-segments))
                 (vec xs)))))
    (when (seq sid)
      (swap! segments-by-session* assoc sid (vec @segments*))
      (swap! asr-by-session* assoc sid (vec @asr-segments*)))
    nil))

(defn append-refined!
  "Append a refined transcript message.

  Important:
  - Refined messages are not merged into ASR any more.
  - For now we do NOT rebase refined events in the UI.

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
    (let [sid (or (:session_id ev) (get @session* :id) "")]
      (when (seq sid)
        ;; split refined
        (swap! refined-segments*
               (fn [xs]
                 (let [msg (transcript/normalize-refined ev)
                       xs (->> (conj (vec (or xs [])) msg)
                               ;; de-dupe by seq for idempotency
                               (reduce (fn [acc m]
                                         (let [k (:seq m)]
                                           (if (contains? acc k) acc (assoc acc k m))))
                                       {})
                               vals
                               transcript/sort-messages
                               vec)
                       xs (if (> (count xs) max-segments)
                            (subvec xs (- (count xs) max-segments))
                            xs)]
                   xs)))
        (swap! refined-by-session* assoc sid (vec @refined-segments*))))
    nil))
