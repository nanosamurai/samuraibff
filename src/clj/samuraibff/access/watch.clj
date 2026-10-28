(ns samuraibff.access.watch
  "One background process re-checks every open WebSocket and playback stream.

  Long-lived deliveries are authorized when they start. After that, a single
  watcher per BFF process keeps them honest against the current database
  state: suspension, demotion, key revocation and unsharing close them on every
  replica. The access token is not needed after the start.

  Hot paths (audio frames, event sends, playback reads) only read cached state
  through `allowed?`. They never query the database, so a slow database cannot
  stall audio or events.

  Each cycle (`:interval-ms`, with jitter) one scheduler tick:
  - closes deliveries past their maximum duration,
  - closes deliveries whose last successful check is older than `:grace-ms`,
  - starts one batched check, unless the previous one is still running.

  A batched check reads all principals, API keys and session access rows of
  all open deliveries in a few queries, each with a statement timeout. A check
  that fails or times out changes nothing; the next cycle retries. A definite
  denial closes the delivery at once.

  Settings (config):
  - [:access :recheck-interval-s] default 30
  - [:access :recheck-grace-s]    default 120, at least two intervals
  - [:access :recheck-timeout-s]  default 5
  - [:ws :max-duration-s]         default 14400 (4 h); WebSockets with
    authentication only

  Close reasons: `:revoked`, `:unverified`, `:max-duration`.

  Public API:
  - `settings`, `watcher`, `start!`, `stop!`, `for-deps`
  - `register!`, `unregister!`, `allowed?`, `close-reason`
  - `tick!`, `check-now!`"
  (:require
   [integrant.core :as ig]
   [org.corfield.logging4j2 :as log]
   [samuraibff.access.policy :as policy]
   [samuraibff.access.principal :as access.principal]
   [samuraibff.db.access-recheck :as db.recheck]
   [samuraibff.observability.diagnostics :as diagnostics])
  (:import
   (java.util UUID)
   (java.util.concurrent Callable ExecutorService Executors Future ScheduledExecutorService ThreadFactory TimeUnit)))

(def defaults
  "Default re-check settings."
  {:interval-ms 30000
   :grace-ms 120000
   :timeout-s 5
   :max-duration-ms (* 4 60 60 1000)
   :jitter 0.1})

(defn- positive
  [v]
  (when (and (integer? v) (pos? v)) v))

(defn settings
  "Return re-check settings from the config map, with defaults.

  The grace period is raised to at least two intervals, so one slow cycle
  never closes a delivery."
  [config]
  (let [interval-ms (* 1000 (or (positive (get-in config [:access :recheck-interval-s]))
                                (quot (:interval-ms defaults) 1000)))
        grace-ms (* 1000 (or (positive (get-in config [:access :recheck-grace-s]))
                             (quot (:grace-ms defaults) 1000)))]
    {:interval-ms interval-ms
     :grace-ms (max grace-ms (* 2 interval-ms))
     :timeout-s (or (positive (get-in config [:access :recheck-timeout-s])) (:timeout-s defaults))
     :max-duration-ms (* 1000 (or (positive (get-in config [:ws :max-duration-s]))
                                  (quot (:max-duration-ms defaults) 1000)))
     :jitter (:jitter defaults)}))

(defn- principal-key
  [entry]
  [(str (get-in entry [:principal :id])) (some-> (get-in entry [:principal :credential :id]) str)])

(defn- index-by-id
  [rows]
  (into {} (map (juxt (comp str :id) identity)) rows))

(defn- still-allowed?
  [mode principal row]
  (boolean (and principal row
                (case mode
                  :read true
                  :manage (policy/can-manage-session? principal row)
                  false))))

(defn check-batch!
  "Re-check all entries against the database in a few queries.

  Inputs: DataSource, settings, entries. Returns {entry-id boolean}. Throws when
  the database cannot answer within the statement timeout."
  [ds {:keys [timeout-s]} entries]
  (when-not ds
    (throw (ex-info "Authorization store unavailable" {:type ::unavailable})))
  (let [principals (index-by-id (db.recheck/find-principals
                                 ds (map (comp :id :principal) entries) timeout-s))
        credentials (index-by-id (db.recheck/find-credentials
                                  ds (keep (comp :id :credential :principal) entries) timeout-s))
        by-key (group-by principal-key entries)
        keys* (vec (keys by-key))
        current (into {}
                      (map (fn [k]
                             (let [{:keys [principal]} (first (by-key k))]
                               [k (access.principal/recheck-principal
                                   principal
                                   (get principals (str (:id principal)))
                                   (some->> (get-in principal [:credential :id]) str (get credentials)))])))
                      keys*)
        groups (mapv (fn [k]
                       {:principal (current k)
                        :session-ids (when (current k)
                                       (vec (distinct (map :session-id (by-key k)))))})
                     keys*)
        readable (db.recheck/accessible-sessions ds groups timeout-s)
        readable-by-key (zipmap keys* readable)]
    (into {}
          (map (fn [entry]
                 (let [k (principal-key entry)]
                   [(:id entry)
                    (still-allowed? (:mode entry)
                                    (current k)
                                    (get-in readable-by-key [k (str (:session-id entry))]))])))
          entries)))

(defn watcher
  "Create a stopped watcher.

  Inputs: DataSource, settings (see `settings`), optional
  {:now (fn [] epoch-ms) :check-batch (fn [ds settings entries])} for tests."
  ([ds settings] (watcher ds settings {}))
  ([ds settings {:keys [now check-batch]}]
   {:ds ds
    :settings settings
    :now (or now #(System/currentTimeMillis))
    :check-batch (or check-batch check-batch!)
    :entries* (atom {})
    :in-flight* (atom nil)
    :executors* (atom nil)}))

(defn close-reason
  "Return the reason an entry was closed (:revoked, :unverified,
  :max-duration), or nil while it is open."
  [entry]
  (:reason @(:state* entry)))

(defn- close!
  "Close an entry once with a reason and notify its owner."
  [{:keys [entries*]} entry reason]
  (let [[old _] (swap-vals! (:state* entry) (fn [st] (if (:reason st) st (assoc st :reason reason))))]
    (when-not (:reason old)
      (swap! entries* dissoc (:id entry))
      (log/info "Closing delivery after access check" {:reason (name reason) :mode (name (:mode entry))})
      (when-let [f (:on-close entry)]
        (try (f reason)
             (catch Exception failure
               (log/warn "Delivery close callback failed" (diagnostics/failure-details failure))))))))

(defn- lapse-reason
  "Return why an entry may no longer continue at time t, or nil."
  [{:keys [settings]} entry t]
  (let [{:keys [last-ok reason]} @(:state* entry)
        deadline (:deadline-ms entry)]
    (cond
      reason reason
      (and deadline (>= t deadline)) :max-duration
      (> (- t last-ok) (:grace-ms settings)) :unverified
      :else nil)))

(defn allowed?
  "Return true while the entry may continue. Reads cached state only.

  When the entry has lapsed (maximum duration or grace period), closes it."
  [w entry]
  (if-let [reason (lapse-reason w entry ((:now w)))]
    (do (close! w entry reason) false)
    true))

(defn register!
  "Watch a delivery and return its entry.

  Inputs: watcher, {:principal map :session-id UUID :mode :read|:manage
  :max-duration? boolean :on-close (fn [reason])}. `:on-close` is called once,
  from the watcher or from `allowed?`, when access ends."
  [{:keys [entries* settings now]} {:keys [principal session-id mode max-duration? on-close]}]
  (let [t (now)
        entry {:id (UUID/randomUUID)
               :principal principal
               :session-id session-id
               :mode mode
               :deadline-ms (when max-duration? (+ t (:max-duration-ms settings)))
               :on-close on-close
               :state* (atom {:last-ok t :reason nil})}]
    (swap! entries* assoc (:id entry) entry)
    entry))

(defn unregister!
  "Stop watching an entry (the delivery ended normally)."
  [{:keys [entries*]} entry]
  (when entry
    (swap! entries* dissoc (:id entry))))

(defn check-now!
  "Run one batched check in the calling thread.

  Returns :ok, :failed or :idle. A failure changes no entry."
  [{:keys [ds settings now check-batch entries*] :as w}]
  (let [entries (vec (vals @entries*))
        started (now)]
    (if (empty? entries)
      :idle
      (try
        (let [results (check-batch ds settings entries)]
          (doseq [entry entries]
            (case (get results (:id entry))
              true (swap! (:state* entry) update :last-ok max started)
              false (close! w entry :revoked)
              nil))
          :ok)
        (catch Throwable failure
          (log/warn "Access re-check failed; open deliveries continue within the grace period"
                    (assoc (diagnostics/failure-details failure) :open (count entries)))
          :failed)))))

(defn tick!
  "Run one scheduler cycle: close lapsed entries, then start a batched check
  on the worker unless the previous check is still running.

  Returns :started, :deferred or :idle."
  [{:keys [entries* in-flight* executors* now] :as w}]
  (let [t (now)]
    (doseq [entry (vals @entries*)]
      (when-let [reason (lapse-reason w entry t)]
        (close! w entry reason)))
    (cond
      (empty? @entries*) :idle

      (some-> ^Future @in-flight* (.isDone) not)
      (do (log/warn "Access re-check still running; the next cycle retries" {:open (count @entries*)})
          :deferred)

      :else
      (let [^ExecutorService worker (:worker @executors*)]
        (reset! in-flight* (.submit worker ^Callable (fn [] (check-now! w))))
        :started))))

(defn- daemon-factory
  [thread-name]
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable ^String thread-name) (.setDaemon true)))))

(defn- next-delay-ms
  [{:keys [interval-ms jitter]}]
  (let [spread (* interval-ms jitter)]
    (long (+ interval-ms (- (rand (* 2 spread)) spread)))))

(defn- schedule-next!
  [{:keys [executors* settings] :as w}]
  (when-let [^ScheduledExecutorService scheduler (:scheduler @executors*)]
    (when-not (.isShutdown scheduler)
      (.schedule scheduler
                 ^Runnable (fn []
                             (try (tick! w)
                                  (catch Throwable failure
                                    (log/warn "Access re-check cycle failed" (diagnostics/failure-details failure))))
                             (schedule-next! w))
                 (next-delay-ms settings)
                 TimeUnit/MILLISECONDS))))

(defn start!
  "Start the scheduler and worker threads. Returns the watcher."
  [{:keys [executors*] :as w}]
  (when-not @executors*
    (reset! executors* {:scheduler (Executors/newSingleThreadScheduledExecutor
                                    (daemon-factory "access-recheck-scheduler"))
                        :worker (Executors/newSingleThreadExecutor
                                 (daemon-factory "access-recheck-worker"))})
    (schedule-next! w))
  w)

(defn stop!
  "Stop the watcher threads. Open deliveries are not closed."
  [{:keys [executors*]}]
  (when-let [{:keys [^ExecutorService scheduler ^ExecutorService worker]} @executors*]
    (.shutdownNow scheduler)
    (.shutdownNow worker)
    (reset! executors* nil))
  nil)

(defonce ^:private fallback-watchers* (atom {}))

(defn for-deps
  "Return the system watcher from router deps.

  Deps built without the `:samuraibff/access-watch` component (some tests and
  tools) get one started watcher per DataSource."
  [{:keys [access-watch config db]}]
  (or access-watch
      (let [ds (:ds db)]
        (or (get @fallback-watchers* ds)
            (locking fallback-watchers*
              (or (get @fallback-watchers* ds)
                  (let [w (start! (watcher ds (settings config)))]
                    (swap! fallback-watchers* assoc ds w)
                    w)))))))

(defmethod ig/init-key :samuraibff/access-watch
  [_ {:keys [config db]}]
  (start! (watcher (:ds db) (settings config))))

(defmethod ig/halt-key! :samuraibff/access-watch
  [_ w]
  (stop! w))
