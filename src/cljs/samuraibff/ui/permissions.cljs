(ns samuraibff.ui.permissions
  "Access state and capability hints bound to the UI auth store.

  `GET /api/me` stores its `access` block under `[:detail :access]` of
  `samuraibff.ui.store/auth*`. Capabilities only decide what the UI shows; the
  BFF authorizes every request again.

  Public API:
  - `current-access`, `capability?`, `tenant-name`
  - `use-access`, `use-capability?`, `use-guest?` (React hooks)
  - `note-access-error!`, `note-socket-stop!`, `use-socket-stop`,
    `clear-socket-stop!`"
  (:require
   [samuraibff.ui.access :as access]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.store :as store]))

(defonce ^:private socket-stop*
  (atom nil))

(defn current-access
  "Return the latest `/api/me` access block map, or nil."
  []
  (get-in @store/auth* [:detail :access]))

(defn capability?
  "Return true when the current principal has the capability hint.

  Inputs: capability keyword such as :manage_people. Not reactive; use
  `use-capability?` inside components."
  [capability]
  (access/capability? (current-access) capability))

(defn tenant-name
  "Return the organization display name from `/api/me`, or nil."
  []
  (not-empty (str (or (get-in @store/auth* [:detail :tenant_name]) ""))))

(defn use-access
  "React hook: return the current access block and re-render when it changes."
  []
  (get-in (hooks/use-atom store/auth*) [:detail :access]))

(defn use-guest?
  "React hook: true in local mode, where everything runs as the guest."
  []
  (true? (get-in (use-access) [:principal :guest])))

(defn use-capability?
  "React hook: return whether the current principal has a capability hint."
  [capability]
  (access/capability? (use-access) capability))

(def ^:private state->wire
  {:not-provisioned "not_provisioned"
   :pending "pending"
   :suspended "suspended"
   :unavailable "unavailable"})

(defn note-access-error!
  "Switch the UI to an access-state page when an API error reports one.

  Inputs: HTTP status integer and safe error code string or nil.
  Returns: nil. Other errors leave the store unchanged."
  [status code]
  (when-let [state (access/error-access-state status code)]
    (swap! store/auth* assoc-in [:detail :access] {:state (state->wire state)}))
  nil)

(defn note-socket-stop!
  "Record why the server closed a session WebSocket, when it did so on purpose.

  Inputs: session id string, WebSocket close code.
  Returns: the stop reason keyword, or nil for an ordinary close."
  [session-id code]
  (when-let [reason (access/socket-stop-reason code)]
    (reset! socket-stop* {:session-id (str (or session-id "")) :reason reason})
    reason))

(defn use-socket-stop
  "React hook: return {:session-id :reason} of the last server stop, or nil."
  []
  (hooks/use-atom socket-stop*))

(defn clear-socket-stop!
  "Forget a previous server stop notice. Returns: nil."
  []
  (reset! socket-stop* nil)
  nil)
