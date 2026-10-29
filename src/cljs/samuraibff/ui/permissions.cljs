(ns samuraibff.ui.permissions
  "Access state and capability hints bound to the UI auth store.

  `GET /api/me` stores its `access` block under `[:detail :access]` of
  `samuraibff.ui.store/auth*`. Capabilities only decide what the UI shows; the
  BFF authorizes every request again.

  Public API:
  - `current-access`, `capability?`, `tenant-name`
  - `use-access`, `use-capability?`, `use-guest?` (React hooks)
  - `note-access-error!`, `note-access-revoked!`, `use-revoked-session-id`"
  (:require
   [samuraibff.ui.access :as access]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.store :as store]))

(defonce ^:private revoked-session-id*
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

(defn note-access-revoked!
  "Record that the server closed a session WebSocket because access ended.

  Inputs: session id string. Returns: nil."
  [session-id]
  (reset! revoked-session-id* (str (or session-id "")))
  nil)

(defn use-revoked-session-id
  "React hook: return the session id whose live access was revoked, or nil."
  []
  (not-empty (hooks/use-atom revoked-session-id*)))

(defn clear-access-revoked!
  "Forget a previous access-revoked notice. Returns: nil."
  []
  (reset! revoked-session-id* nil)
  nil)
