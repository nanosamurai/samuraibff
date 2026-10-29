(ns samuraibff.ws.access
  "Authorize WebSocket sessions before upgrade and keep re-checking them.

  Before upgrade: reject a foreign Origin, resolve the current principal from
  the database and require session read access (`/ws/events`) or ownership
  (`/ws/audio`). After upgrade: every open socket, including idle ones, is
  re-authorized every `samuraibff.access.revocation/check-interval-ms` and is
  closed with code 1008 `access-revoked` when access is gone or cannot be
  verified within the revocation bound. All BFF replicas read the same
  database, so a change on one replica applies to sockets on every replica.

  Public API:
  - `authorize-upgrade`, `session-check`, `revocation-callbacks`"
  (:require
   [jsonista.core :as json]
   [org.corfield.logging4j2 :as log]
   [samuraibff.access.policy :as policy]
   [samuraibff.access.principal :as access.principal]
   [samuraibff.access.revocation :as revocation]
   [samuraibff.db.session-access :as session-access]
   [samuraibff.http.origin :as http.origin])
  (:import
   (java.util UUID)
   (java.util.concurrent Future ScheduledThreadPoolExecutor ThreadFactory TimeUnit)
   (org.httpkit.server AsyncChannel)))

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name}))

(defonce ^:private recheck-executor
  (doto (ScheduledThreadPoolExecutor.
         1 (reify ThreadFactory
             (newThread [_ runnable]
               (doto (Thread. runnable "websocket-access-recheck")
                 (.setDaemon true)))))
    (.setRemoveOnCancelPolicy true)))

(defn- rejection
  [status message]
  {:ok? false
   :response {:status status
              :headers {"content-type" "application/json"}
              :body (json/write-value-as-string {:ok false :message message} json-mapper)}})

(defn- ->session-uuid
  [value]
  (try (UUID/fromString (str value)) (catch Exception _ nil)))

(defn- allowed?
  [mode principal session]
  (and session
       (case mode
         :read true
         :manage (policy/can-manage-session? principal session))))

(defn authorize-upgrade
  "Authorize a WebSocket upgrade for a session.

  Inputs: config, DataSource, Ring request, verified user map or nil,
  session id string, mode :read (events) or :manage (audio).
  Returns {:ok? true :principal map :tenant-id string} or
  {:ok? false :response ring-response}. Inaccessible and unknown sessions both
  yield 403 `unknown-session`, so the response does not reveal existence."
  [config ds req user session-id mode]
  (if-not (http.origin/websocket-origin-allowed? config req)
    (rejection 403 "origin-not-allowed")
    (let [{:keys [state principal]} (access.principal/resolve-principal config ds user)
          session-uuid (->session-uuid session-id)]
      (cond
        (= :unavailable state) (rejection 503 "authorization-unavailable")
        (not (policy/active? principal)) (rejection 403 (case state
                                                          :suspended "account-suspended"
                                                          :pending "account-setup-pending"
                                                          "access-not-provisioned"))
        (nil? session-uuid) (rejection 403 "unknown-session")
        :else
        (let [session (session-access/find-accessible-session ds principal session-uuid)]
          (if (allowed? mode principal session)
            {:ok? true :principal principal :tenant-id (str (:tenant-id principal))}
            (rejection 403 (if session "read-only-access" "unknown-session"))))))))

(defn session-check
  "Return a bounded predicate that stays true while the socket remains authorized.

  Inputs: config, DataSource, verified user map or nil, session id string, mode."
  [config ds user session-id mode]
  (let [session-uuid (->session-uuid session-id)]
    (revocation/bounded-checker
     (fn []
       (let [{:keys [state principal]} (access.principal/resolve-principal config ds user)]
         (when (= :unavailable state)
           (throw (ex-info "Authorization store unavailable" {:type ::unavailable})))
         (and (policy/active? principal)
              (allowed? mode principal
                        (session-access/find-accessible-session ds principal session-uuid))))))))

(defn- close-revoked!
  [channel]
  (.serverClose ^AsyncChannel channel 1008 "access-revoked"))

(defn revocation-callbacks
  "Wrap http-kit callbacks so the socket closes once still-allowed? is false.

  A periodic task checks idle sockets; :on-receive also checks before handing
  frames to the inner callback. Timers are cancelled on close."
  [still-allowed? {:keys [on-open on-close on-receive] :as callbacks}]
  (let [timer* (atom nil)
        check! (fn [channel]
                 (when-not (still-allowed?)
                   (log/info "Closing WebSocket after access change")
                   (close-revoked! channel)))]
    (cond-> (assoc callbacks
                   :on-open
                   (fn [channel]
                     (when on-open (on-open channel))
                     (reset! timer* (.scheduleWithFixedDelay
                                     recheck-executor
                                     ^Runnable (fn [] (try (check! channel)
                                                           (catch Exception e
                                                             (log/warn e "WebSocket access re-check failed"))))
                                     (long revocation/check-interval-ms)
                                     (long revocation/check-interval-ms)
                                     TimeUnit/MILLISECONDS)))
                   :on-close
                   (fn [channel status]
                     (some-> ^Future @timer* (.cancel false))
                     (when on-close (on-close channel status))))
      on-receive
      (assoc :on-receive
             (fn [channel payload]
               (if (still-allowed?)
                 (on-receive channel payload)
                 (close-revoked! channel)))))))
