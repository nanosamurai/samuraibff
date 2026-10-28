(ns samuraibff.access.policy
  "Pure application authorization rules.

  This namespace is the single source of the fixed role policy. It has no I/O.
  HTTP, WebSocket and DB code call these predicates; UI capabilities in
  `/api/me` are derived from the same functions and are only hints.

  Principal map (`:auth/principal`, see `samuraibff.access.principal`):

  {:id uuid :tenant-id uuid :type :human|:service
   :role :member|:builder|:admin|nil :status :active|:pending|:suspended
   :name string? :email string? :client-id string? :guest? boolean}

  Roles gate authoring and administration only. Every active human can record
  with all capture settings. No role reads another user's private session.

  The guest principal (authentication disabled) is the only person in a
  single-user install. It keeps Admin authoring rights but has nobody to share
  with and no identity provider to add people to, so sharing, the directory
  and People are off for it.

  Public API:
  - `active?`, `human?`, `service?`, `admin?`, `builder?`, `guest?`
  - `owns-session?`, `can-manage-session?`, `session-visible-to?`
  - `can-manage-people?`, `can-share-session?`, `can-use-directory?`
  - `credential-scope`, `can-manage-credential?`
  - `can-author-integrations?`, `can-view-definition?`, `can-manage-definition?`,
    `can-use-definition?`, `can-publish-definitions?`, `can-manage-defaults?`
  - `capabilities`")

(def roles
  "Fixed human application roles, lowest to highest."
  #{:member :builder :admin})

(def visibilities
  "Session audiences: owner only, explicit read grants, all active humans."
  #{"private" "selected" "tenant"})

(defn active?
  "True when the principal exists and its membership is active."
  [principal]
  (= :active (:status principal)))

(defn human?
  "True for an active human principal."
  [principal]
  (and (active? principal) (= :human (:type principal))))

(defn service?
  "True for an active machine (service-account) principal."
  [principal]
  (and (active? principal) (= :service (:type principal))))

(defn admin?
  "True for an active human Admin."
  [principal]
  (and (human? principal) (= :admin (:role principal))))

(defn builder?
  "True for an active human Builder or Admin (integration authoring)."
  [principal]
  (and (human? principal) (contains? #{:builder :admin} (:role principal))))

(defn guest?
  "True for the guest principal used while authentication is disabled."
  [principal]
  (true? (:guest? principal)))

(defn- same-tenant?
  [principal tenant-id]
  (and (some? tenant-id) (= (str (:tenant-id principal)) (str tenant-id))))

(defn owns-session?
  "True when an active principal created the session row.

  Inputs: principal map, session row with :tenant_id and :user_id."
  [principal session]
  (and (active? principal)
       (some? (:user_id session))
       (same-tenant? principal (:tenant_id session))
       (= (str (:id principal)) (str (:user_id session)))))

(defn can-manage-session?
  "Only the active owner may send audio, finish, rename, delete or reshare.

  Admin has no override: administration is not content access."
  [principal session]
  (owns-session? principal session))

(defn session-visible-to?
  "Reference read predicate, mirrored by the SQL in `samuraibff.db.session-access`.

  Inputs: principal, session row (:tenant_id :user_id :visibility) and
  granted? (true when a read grant exists for this principal).
  Services read only their own sessions. Ownerless sessions are readable by
  nobody through ordinary routes."
  [principal session granted?]
  (cond
    (not (active? principal)) false
    (not (same-tenant? principal (:tenant_id session))) false
    (nil? (:user_id session)) false
    (owns-session? principal session) true
    (not (human? principal)) false
    (= "tenant" (:visibility session)) true
    (= "selected" (:visibility session)) (boolean granted?)
    :else false))

(defn can-share-session?
  "Sharing is an owner mutation; services keep their sessions private in v1."
  [principal session]
  (and (human? principal) (not (guest? principal)) (can-manage-session? principal session)))

(defn can-use-directory?
  "Any active human except the guest can look up colleagues to share with."
  [principal]
  (and (human? principal) (not (guest? principal))))

(defn can-manage-people?
  "Only an active human Admin manages membership and roles. The guest of a
  single-user install has no membership to manage."
  [principal]
  (and (admin? principal) (not (guest? principal))))

(defn credential-scope
  "Return which machine credentials the principal may manage.

  :all for Admin, :own for Builder, nil for everyone else (Members, services)."
  [principal]
  (cond
    (admin? principal) :all
    (builder? principal) :own
    :else nil))

(defn can-manage-credential?
  "True when the principal may read, rotate or revoke one credential row.

  Builders manage only credentials they manage themselves; legacy credentials
  without a manager are Admin-managed."
  [principal credential]
  (case (credential-scope principal)
    :all true
    :own (= (str (:id principal)) (str (:manager_user_id credential)))
    false))

(defn can-author-integrations?
  "Builder and Admin may create workflow/webhook definitions."
  [principal]
  (builder? principal))

(defn can-publish-definitions?
  "Only Admin publishes definitions for everyone."
  [principal]
  (admin? principal))

(defn can-manage-defaults?
  "Only Admin sets the organization's default workflows/webhooks."
  [principal]
  (admin? principal))

(defn- personal-owner?
  [principal definition]
  (and (= "personal" (:availability definition))
       (= (str (:id principal)) (str (:owner_user_id definition)))))

(defn can-view-definition?
  "Members see published definitions; Builders also their own personal ones;
  Admin sees every definition in the tenant."
  [principal definition]
  (cond
    (not (human? principal)) false
    (admin? principal) true
    (= "published" (:availability definition)) true
    (builder? principal) (personal-owner? principal definition)
    :else false))

(defn can-manage-definition?
  "Admin manages any definition. A Builder edits/deletes only their own
  personal definitions; published definitions are Admin-managed."
  [principal definition]
  (cond
    (admin? principal) true
    (builder? principal) (personal-owner? principal definition)
    :else false))

(defn can-use-definition?
  "Whether a session owned by `principal` may run the definition.

  Published definitions are usable by every active human owner. Personal
  definitions are usable only by their own active Builder/Admin owner, never by
  another Admin automatically. Services use none in v1."
  [principal definition]
  (cond
    (not (human? principal)) false
    (= "published" (:availability definition)) true
    :else (and (builder? principal) (personal-owner? principal definition))))

(defn capabilities
  "Return presentation hints for the UI. Server routes still authorize.

  Returns a map of snake_case keyword -> boolean."
  [principal]
  {:record (or (human? principal) (service? principal))
   :share_sessions (can-use-directory? principal)
   :manage_people (can-manage-people? principal)
   :manage_credentials (= :all (credential-scope principal))
   :manage_own_credentials (some? (credential-scope principal))
   :author_integrations (can-author-integrations? principal)
   :publish_integrations (can-publish-definitions? principal)
   :manage_defaults (can-manage-defaults? principal)
   :manage_speakers (human? principal)})
