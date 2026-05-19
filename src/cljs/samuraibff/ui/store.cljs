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
   [clojure.string :as str]
   [samuraibff.ui.api-credentials-store :as api-creds.store]
   [samuraibff.ui.transcript :as transcript]
   [samuraibff.ui.util :as util]))

(defonce route*
  (atom {:page :recordings
         :params {}}))

(defonce session*
  (atom {:id ""
         :title ""
         ;; DB-backed session status.
         ;;
         ;; Expected values (backend): "created" | "active" | "finished" | "failed".
         ;;
         ;; Notes:
         ;; - We keep this as a keyword in the UI for ergonomics.
         ;; - nil means "unknown / not loaded".
         :status nil
         ;; Used only for consistent UI display of untitled sessions.
         ;; - Set when selecting a session from the Sessions table.
         ;; - Set when creating a new session.
         ;; - Nil/0 means "unknown".
         :created_at_ms nil
         :lang ""
         ;; Session-scoped webhook routing overrides.
         ;;
         ;; Shape matches schemas/CreateSessionRequest:
         ;; {:use_defaults boolean
         ;;  :webhook_ids #{<uuid-string> ...}
         ;;  :disable_event_types #{<event-type> ...}}
         ;;
         ;; Notes:
         ;; - We keep :webhook_ids as a set for easy checkbox toggling.
         ;; - UI currently does not expose :disable_event_types, but we keep it
         ;;   here to avoid churn when we decide to surface it.
         :webhook_overrides {:use_defaults true
                             :webhook_ids #{}
                             :disable_event_types #{}}

          ;; Session-scoped workflow routing overrides.
          ;; Shape matches schemas/CreateSessionRequest :workflow_overrides.
          ;; {:use_defaults boolean
          ;;  :workflow_ids #{<uuid-string> ...}}
          :workflow_overrides {:use_defaults true
                               :workflow_ids #{}}

          ;; Session-level, webhook-agnostic settings.
          ;;
          ;; Shape matches schemas/CreateSessionRequest :session_settings.
         :session_settings {:refined_transcript {:consolidation {:enabled false}}}
         ;; Stream controls (sent at /ws/audio connect).
         :controls {:realtime true
                    :refined true
                    :final true
                    :store_recording true
                    :rt_partial_enable true

                     ;; Audio capture source selection (frontend-only).
                     ;; :audio_source is one of:
                     ;; - :mic    (default; getUserMedia)
                     ;; - :system (Electron desktop capture)
                     ;; - :mix    (mic + system mixed to mono)
                     :audio_source :mic
                     ;; Desktop capture source id (Electron).
                     ;; Example: "screen:0:0" / "window:123:0"
                     :system_source_id nil
                     :system_source_name nil
                     :mic_device_id nil
                     ;; Input gain knobs (frontend-only)
                     :mic_gain 1.0
                     :system_gain 1.0
                    ;; Optional knobs (nil => omit from query params)
                    :rt_window_sec nil
                    :rt_overlap_sec nil
                    :rt_emit_every_sec nil
                    :refinement_window_sec nil}}))

(defn set-session-workflow-overrides-use-defaults!
  "Set whether tenant workflow defaults should be used for newly created sessions.

  Inputs:
  - use-defaults?: boolean

  Returns: nil." 
  [use-defaults?]
  (swap! session* assoc-in [:workflow_overrides :use_defaults] (boolean use-defaults?))
  nil)

(defn set-session-workflow-id-selected!
  "Select/unselect a workflow id for the next session creation.

  Inputs:
  - workflow-id: string UUID
  - selected?: boolean

  Returns: nil." 
  [workflow-id selected?]
  (let [sid (str (or workflow-id ""))]
    (when (seq sid)
      (swap! session*
             (fn [st]
               (let [path [:workflow_overrides :workflow_ids]
                     ids (set (get-in st path #{}))
                     ids' (if (true? selected?)
                            (conj ids sid)
                            (disj ids sid))]
                 (assoc-in st path ids'))))))
  nil)

;; --- Workflows (tenant-scoped definitions) ---

(defonce workflows*
  (atom {:items []
         :loading? false
         :error nil}))

(defonce workflow-defaults*
  (atom {:workflow_ids []
         :loading? false
         :error nil}))

(defn set-workflows-loading!
  "Set loading flag for workflows list." 
  [loading?]
  (swap! workflows* assoc :loading? (boolean loading?))
  nil)

(defn set-workflows-error!
  "Set workflows list error string (nil clears)." 
  [err]
  (swap! workflows* assoc :error err)
  nil)

(defn set-workflows-items!
  "Replace workflows list items." 
  [items]
  (swap! workflows* assoc :items (vec (or items [])))
  nil)

(defn remove-workflow-item!
  "Remove a workflow item from current list." 
  [workflow-id]
  (swap! workflows*
         (fn [st]
           (update st :items
                   (fn [items]
                     (->> (vec (or items []))
                          (remove (fn [w]
                                    (= (str workflow-id) (str (:id w)))))
                          vec)))))
  nil)

(defn set-workflow-defaults-loading!
  "Set loading flag for workflow defaults." 
  [loading?]
  (swap! workflow-defaults* assoc :loading? (boolean loading?))
  nil)

(defn set-workflow-defaults-error!
  "Set workflow defaults error string (nil clears)." 
  [err]
  (swap! workflow-defaults* assoc :error err)
  nil)

(defn set-workflow-defaults-ids!
  "Replace default workflow ids." 
  [workflow-ids]
  (swap! workflow-defaults* assoc :workflow_ids (vec (or workflow-ids [])))
  nil)

(defn set-session-refined-consolidation-enabled!
  "Enable/disable refined transcript consolidation for newly created sessions.

  This mutates `session*` under:
  [:session_settings :refined_transcript :consolidation :enabled]

  Inputs:
  - enabled?: boolean

  Returns: nil."
  [enabled?]
  (swap! session* assoc-in [:session_settings :refined_transcript :consolidation :enabled]
         (boolean enabled?))
  nil)

(defn set-session-control!
  "Set a single stream control field under `session*`.

  Inputs:
  - k: keyword
  - v: any

  Returns: nil."
  [k v]
  (swap! session* assoc-in [:controls k] v)
  nil)

(defn set-session-webhook-overrides-use-defaults!
  "Set whether tenant defaults should be used for newly created sessions.

  This mutates `session*` under :webhook_overrides.

  Inputs:
  - use-defaults?: boolean

  Returns: nil."
  [use-defaults?]
  (swap! session* assoc-in [:webhook_overrides :use_defaults] (boolean use-defaults?))
  nil)

(defn set-session-webhook-id-selected!
  "Select/unselect a webhook id for the next session creation.

  Inputs:
  - webhook-id: string UUID
  - selected?: boolean

  Returns: nil."
  [webhook-id selected?]
  (let [sid (str (or webhook-id ""))]
    (when (seq sid)
      (swap! session*
             (fn [st]
               (let [path [:webhook_overrides :webhook_ids]
                     ids (set (get-in st path #{}))
                     ids' (if (true? selected?)
                            (conj ids sid)
                            (disj ids sid))]
                 (assoc-in st path ids'))))))
  nil)

(defn set-session-disable-event-type-selected!
  "Select/unselect a disabled webhook event type for the next session creation.

  This mutates `session*` under `[:webhook_overrides :disable_event_types]`.

  Inputs:
  - event-type: string
  - selected?: boolean (true => disable, false => enable)

  Returns: nil."
  [event-type selected?]
  (let [et (str (or event-type ""))]
    (when (seq et)
      (swap! session*
             (fn [st]
               (let [path [:webhook_overrides :disable_event_types]
                     xs (set (get-in st path #{}))
                     xs' (if (true? selected?)
                           (conj xs et)
                           (disj xs et))]
                 (assoc-in st path xs'))))))
  nil)

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

(defonce workflow-results*
  (atom []))

(defonce segments-by-session*
  (atom {}))

;; NOTE: split caches for ASR vs refined.
(defonce asr-by-session*
  (atom {}))

(defonce refined-by-session*
  (atom {}))

(defonce workflow-results-by-session*
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

(defn update-recording-db-title!
  "Update the session title in the DB-backed recordings list.

  Inputs:
  - session-id: string
  - title: string? (nil allowed)

  Returns: nil."
  [session-id title]
  (let [sid (or session-id "")]
    (swap! recordings-db*
           (fn [xs]
             (mapv (fn [r]
                     (if (= sid (or (:session_id r) ""))
                       (assoc r :title title)
                       r))
                   (vec (or xs []))))))
  nil)

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

(defn cached-workflow-results
  "Get cached workflow result items for a session.

  Inputs:
  - session-id string

  Returns:
  - vector of workflow result maps." 
  [session-id]
  (vec (get @workflow-results-by-session* (or session-id "") [])))

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

;; --- Webhooks (tenant-scoped outbound endpoints) ---

(defonce webhooks*
  (atom {:items []
         :loading? false
         :error nil}))

(defonce webhook-defaults*
  (atom {:webhook_ids []
         :loading? false
         :error nil}))

(defn set-webhooks-loading!
  "Set loading flag for the webhooks list."
  [loading?]
  (swap! webhooks* assoc :loading? (boolean loading?))
  nil)

(defn set-webhooks-error!
  "Set error string for the webhooks list (nil clears)."
  [err]
  (swap! webhooks* assoc :error err)
  nil)

(defn set-webhooks-items!
  "Replace the current webhooks list.

  Inputs:
  - items: vector of webhook item maps"
  [items]
  (swap! webhooks* assoc :items (vec (or items [])))
  nil)

(defn remove-webhook-item!
  "Remove a webhook by id from store list."
  [webhook-id]
  (swap! webhooks*
         (fn [st]
           (update st :items (fn [xs]
                               (vec (remove #(= (or webhook-id "") (or (:id %) "")) xs))))))
  nil)

(defn set-webhook-defaults-loading!
  "Set loading flag for webhook defaults."
  [loading?]
  (swap! webhook-defaults* assoc :loading? (boolean loading?))
  nil)

(defn set-webhook-defaults-error!
  "Set error string for webhook defaults (nil clears)."
  [err]
  (swap! webhook-defaults* assoc :error err)
  nil)

(defn set-webhook-defaults-ids!
  "Replace default webhook ids.

  Inputs:
  - webhook-ids: vector of uuid strings"
  [webhook-ids]
  (swap! webhook-defaults* assoc :webhook_ids (vec (or webhook-ids [])))
  nil)

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
      (swap! workflow-results-by-session* assoc old-id (vec @workflow-results*))
      (swap! log-by-session* assoc old-id (vec @log*)))

    (swap! session* assoc :id new-id)
    ;; clear-segments! is defined later in this namespace.
    (reset! segments* [])
    (reset! asr-segments* [])
    (reset! refined-segments* [])
    (reset! workflow-results* [])
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

(defn set-session-created-at-ms!
  "Set the current session created_at timestamp in ms.

  This is used for UI-only default title generation when session title is blank.

  Inputs:
  - created-at-ms: number? or nil

  Returns: nil."
  [created-at-ms]
  (swap! session* assoc :created_at_ms created-at-ms)
  nil)

(defn set-session-status!
  "Set the current session status.

  This is the DB-backed session state machine status.

  Inputs:
  - status: string? | keyword? | nil (e.g. :created | :active | :finished | :failed)

  Returns: nil."
  [status]
  (let [status (cond
                 (keyword? status) status
                 (string? status) (some-> status str str/trim not-empty keyword)
                 :else nil)]
    (swap! session* assoc :status status))
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
  (reset! workflow-results* [])
  (reset! transcript-zero-s* nil)
  (let [sid (or (get @session* :id) "")]
    (when (seq sid)
      (swap! segments-by-session* assoc sid [])
      (swap! asr-by-session* assoc sid [])
      (swap! refined-by-session* assoc sid [])
      (swap! workflow-results-by-session* assoc sid [])))
  nil)

(defn upsert-workflow-result!
  "Insert or update latest workflow result for a workflow id.

  Input:
  - ev: map decoded from ws event, expects:
      :session_id :workflow_id :status and optionally
      :workflow_name :created_at :trigger_type :render_markdown

  Returns: nil." 
  [ev]
  (let [sid (or (:session_id ev) (get @session* :id) "")
        wf-id (str (or (:workflow_id ev) ""))
        item (select-keys ev
                          [:session_id :workflow_id :workflow_name :workflow_run_id
                           :created_at :trigger_type :status :render_markdown
                           :error_code :error_detail])]
    (when (and (seq sid) (seq wf-id))
      (swap! workflow-results*
             (fn [xs]
               (let [xs (vec (or xs []))
                     xs' (->> (conj xs item)
                              (reduce (fn [acc x]
                                        (let [k (str (:workflow_id x))]
                                          ;; latest wins (we overwrite)
                                          (assoc acc k x)))
                                      {})
                              vals
                              (sort-by (fn [x] (or (:created_at x) "")) >)
                              vec)]
                 xs')))
      (swap! workflow-results-by-session* assoc sid (vec @workflow-results*)))
    nil))

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
