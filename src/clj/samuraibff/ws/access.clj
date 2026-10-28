(ns samuraibff.ws.access
  "Authorize WebSocket sessions before upgrade and watch them while open.

  Before upgrade: reject a foreign Origin, resolve the current principal from
  the database and require session read access (`/ws/events`) or ownership
  (`/ws/audio`). The access token is needed only for this step.

  While open: the socket is registered with the process-wide access watcher
  (`samuraibff.access.watch`), which re-checks all open sockets in one batched
  background cycle. A socket closes with an application close code when its
  access is revoked, cannot be verified within the grace period, or reaches
  the maximum duration. All BFF replicas read the same database, so a change
  on one replica applies to sockets on every replica.

  http-kit sends close codes but drops close reasons, so clients must match on
  the code (see `close-codes`).

  Public API:
  - `close-codes`, `authorize-upgrade`, `watch-socket`"
  (:require
   [jsonista.core :as json]
   [samuraibff.access.policy :as policy]
   [samuraibff.access.principal :as access.principal]
   [samuraibff.access.watch :as watch]
   [samuraibff.auth.oidc :as oidc]
   [samuraibff.db.session-access :as session-access]
   [samuraibff.http.origin :as http.origin])
  (:import
   (java.util UUID)
   (org.httpkit.server AsyncChannel)))

(def close-codes
  "WebSocket close codes per watcher close reason (application range).

  - 4403 access revoked: suspended, demoted, unshared or key revoked
  - 4408 maximum duration reached
  - 4503 access could not be verified within the grace period"
  {:revoked {:code 4403 :reason "access-revoked"}
   :max-duration {:code 4408 :reason "max-duration"}
   :unverified {:code 4503 :reason "access-unverified"}})

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name}))

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

(defn- close-channel!
  [channel reason]
  (let [{:keys [code] close-reason :reason} (get close-codes reason (:revoked close-codes))]
    (.serverClose ^AsyncChannel channel (int code) ^String close-reason)))

(defn watch-socket
  "Watch an authorized socket while it is open.

  Inputs: watcher, config, principal (from `authorize-upgrade`), session id
  string, mode :read or :manage. With authentication required, the socket also
  closes at the watcher's maximum duration.

  Returns {:allowed? (fn [] boolean) :wrap (fn [callbacks] callbacks)}.
  `allowed?` reads cached state only and is safe on hot paths. `wrap`
  registers the socket on open, refuses frames once access ends, closes the
  channel with the matching close code and unregisters it on close. http-kit
  may run `on-close` before `on-open` finishes for a client that disconnects
  at once; the socket is then unregistered right after registration."
  [watcher config principal session-id mode]
  (let [entry* (atom nil)
        channel* (atom nil)
        closed?* (atom false)
        allowed? (fn [] (if-let [entry @entry*] (watch/allowed? watcher entry) true))]
    {:allowed? allowed?
     :wrap
     (fn [{:keys [on-open on-close on-receive] :as callbacks}]
       (cond-> (assoc callbacks
                      :on-open
                      (fn [channel]
                        (reset! channel* channel)
                        (when on-open (on-open channel))
                        (let [entry (watch/register!
                                     watcher
                                     {:principal principal
                                      :session-id (->session-uuid session-id)
                                      :mode mode
                                      :max-duration? (oidc/auth-required? config)
                                      :on-close (fn [reason]
                                                  (some-> @channel* (close-channel! reason)))})]
                          (reset! entry* entry)
                          (when @closed?* (watch/unregister! watcher entry))))
                      :on-close
                      (fn [channel status]
                        (reset! closed?* true)
                        (watch/unregister! watcher @entry*)
                        (when on-close (on-close channel status))))
         on-receive
         (assoc :on-receive
                (fn [channel payload]
                  (when (allowed?)
                    (on-receive channel payload))))))}))
