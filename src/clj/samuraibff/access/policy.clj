(ns samuraibff.access.policy
  "Pure application authorization rules.

  This namespace is the single source of the fixed role policy. It has no I/O.
  HTTP, WebSocket and DB code call these predicates; UI capabilities in
  `/api/me` are derived from the same functions and are only hints.

  Principal map (`:auth/principal`, see `samuraibff.access.principal`):

  {:id uuid :tenant-id uuid :type :human|:service
   :role :member|:builder|:admin|nil :status :active|:pending|:suspended
   :name string? :email string? :client-id string? :guest? boolean
   :credential {:id uuid :name string :kind :personal|:organization
                :read-scope :credential|:owner
                :default-visibility \"private\"|\"tenant\"}?}

  `:credential` is present when the request used an API key. A personal key
  resolves to its owner (a human) and acts as that person with limits: it
  never administers (people, keys, definitions, defaults), and with read scope
  `:credential` it reads and manages only sessions created with that key. An
  organization key resolves to its service principal, which owns its
  sessions; the key's manager (or any Admin when it has none) manages them.

  Roles gate authoring and administration only. Every active human can record
  with all capture settings. No role reads another user's private session.

  Sessions recorded before access control have no known owner (`user_id` is
  NULL; the BFF stores an owner for every new session). Upgrades make them
  visible to the whole tenant, as before. Admins manage them: rename, finish,
  delete, reshare and assign an owner.

  The guest principal (authentication disabled) is the only person in a
  single-user install. It keeps Admin authoring rights but has nobody to share
  with and no identity provider to add people to, so sharing, the directory
  and People are off for it.

  Public API:
  - `active?`, `human?`, `service?`, `admin?`, `builder?`, `guest?`
  - `api-key?`, `personal-key?`, `can-create-credential?`
  - `owns-session?`, `can-manage-session?`, `session-visible-to?`
  - `legacy-session?`, `manages-legacy-session?`, `can-assign-owner?`
  - `can-manage-people?`, `can-share-session?`, `can-use-directory?`
  - `credential-scope`, `can-manage-credential?`, `can-rotate-credential?`,
    `can-configure-credential?`
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

(defn api-key?
  "True when the request was made with an API key (personal or organization)."
  [principal]
  (some? (:credential principal)))

(defn personal-key?
  "True when the request was made with a personal API key."
  [principal]
  (= :personal (get-in principal [:credential :kind])))

(defn- key-restricted-reads?
  "True for a personal key that may only touch sessions it created."
  [principal]
  (and (personal-key? principal)
       (not= :owner (get-in principal [:credential :read-scope]))))

(defn- same-tenant?
  [principal tenant-id]
  (and (some? tenant-id) (= (str (:tenant-id principal)) (str tenant-id))))

(defn- key-allows?
  [principal session]
  (or (not (key-restricted-reads? principal))
      (= (str (get-in principal [:credential :id])) (str (:created_via_credential_id session)))))

(defn owns-session?
  "True when an active principal created the session row.

  Inputs: principal map, session row with :tenant_id, :user_id and, for
  personal keys, :created_via_credential_id."
  [principal session]
  (and (active? principal)
       (some? (:user_id session))
       (same-tenant? principal (:tenant_id session))
       (= (str (:id principal)) (str (:user_id session)))
       (key-allows? principal session)))

(defn manages-key-session?
  "True when a human (not via an API key) manages the organization key that
  owns the session.

  The session row carries `:key_manager`, computed in SQL by
  `samuraibff.db.session-access/key-manager-expr`: the key's manager is the
  principal, or the key has no manager and the principal is an Admin."
  [principal session]
  (and (active? principal)
       (= :human (:type principal))
       (not (api-key? principal))
       (same-tenant? principal (:tenant_id session))
       (true? (:key_manager session))))

(defn legacy-session?
  "True for a session without a recorded owner: one recorded before access
  control. A row without the `:user_id` column is never treated as ownerless."
  [session]
  (and (contains? session :user_id) (nil? (:user_id session))))

(defn manages-legacy-session?
  "True when a human Admin (not via an API key) manages an ownerless legacy
  session of the same tenant.

  Mirrored in SQL by `samuraibff.db.session-access/readable-condition`."
  [principal session]
  (and (admin? principal)
       (not (api-key? principal))
       (same-tenant? principal (:tenant_id session))
       (legacy-session? session)))

(defn can-manage-session?
  "The active owner, or the manager of the organization key that owns it, may
  send audio, finish, rename, delete or reshare.

  Admin has no general override: administration is not content access. Only
  sessions of organization keys without a manager and ownerless legacy
  sessions are Admin-managed."
  [principal session]
  (or (owns-session? principal session)
      (manages-key-session? principal session)
      (manages-legacy-session? principal session)))

(defn can-assign-owner?
  "An Admin who manages an ownerless legacy session may give it an owner. The
  guest has nobody else to assign."
  [principal session]
  (and (manages-legacy-session? principal session) (not (guest? principal))))

(defn session-visible-to?
  "Reference read predicate, mirrored by the SQL in `samuraibff.db.session-access`.

  Inputs: principal, session row (:tenant_id :user_id :visibility) and
  granted? (true when a read grant exists for this principal).
  Services read only their own sessions. Ownerless legacy sessions follow
  their audience like any other session, and their Admins always read them."
  [principal session granted?]
  (cond
    (not (active? principal)) false
    (not (same-tenant? principal (:tenant_id session))) false
    (owns-session? principal session) true
    (manages-key-session? principal session) true
    (manages-legacy-session? principal session) true
    (not (human? principal)) false
    (key-restricted-reads? principal) false
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
  single-user install has no membership to manage. API keys never do."
  [principal]
  (and (admin? principal) (not (guest? principal)) (not (api-key? principal))))

(defn credential-scope
  "Return which machine credentials the principal may manage.

  :all for Admin, :own for Builder, nil for everyone else (Members, services,
  API keys, and the guest: without sign-in no request needs a key)."
  [principal]
  (cond
    (api-key? principal) nil
    (guest? principal) nil
    (admin? principal) :all
    (builder? principal) :own
    :else nil))

(defn can-create-credential?
  "Builders and Admins create personal keys; only Admins create organization
  keys. kind is :personal or :organization."
  [principal kind]
  (case kind
    :personal (some? (credential-scope principal))
    :organization (= :all (credential-scope principal))
    false))

(defn can-manage-credential?
  "True when the principal may read or revoke one credential row.

  Builders manage only credentials they manage themselves; legacy credentials
  without a manager are Admin-managed. Admins may revoke anyone's key."
  [principal credential]
  (case (credential-scope principal)
    :all true
    :own (= (str (:id principal)) (str (:manager_user_id credential)))
    false))

(defn can-rotate-credential?
  "True when the principal may obtain a new secret for one credential.

  A personal key acts as its owner, so only that owner may rotate it: a new
  secret in an Admin's hands would read the owner's private sessions.
  Organization keys follow `can-manage-credential?`."
  [principal credential]
  (if (= "personal" (:kind credential))
    (and (some? (credential-scope principal))
         (= (str (:id principal)) (str (:manager_user_id credential))))
    (can-manage-credential? principal credential)))

(defn can-configure-credential?
  "Only Admins change an organization key's manager or default audience."
  [principal credential]
  (and (= :all (credential-scope principal))
       (= "organization" (:kind credential))))

(defn can-author-integrations?
  "Builder and Admin may create workflow/webhook definitions (not via API keys)."
  [principal]
  (and (builder? principal) (not (api-key? principal))))

(defn can-publish-definitions?
  "Only Admin publishes definitions for everyone (not via API keys)."
  [principal]
  (and (admin? principal) (not (api-key? principal))))

(defn can-manage-defaults?
  "Only Admin sets the organization's default workflows/webhooks (not via API keys)."
  [principal]
  (and (admin? principal) (not (api-key? principal))))

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
  personal definitions; published definitions are Admin-managed. API keys
  never manage definitions."
  [principal definition]
  (cond
    (api-key? principal) false
    (admin? principal) true
    (builder? principal) (personal-owner? principal definition)
    :else false))

(defn can-use-definition?
  "Whether a session owned by `principal` may run the definition.

  Published definitions are usable by every active owner, including the
  service principals of organization keys. Personal definitions are usable
  only by their own active Builder/Admin owner, never by another Admin
  automatically."
  [principal definition]
  (cond
    (service? principal) (= "published" (:availability definition))
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
