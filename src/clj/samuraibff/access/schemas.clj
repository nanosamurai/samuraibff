(ns samuraibff.access.schemas
  "Malli schemas for access-control API contracts.

  Kept separate from `samuraibff.schemas` so the access contract stays
  cohesive; `samuraibff.schemas` reuses `AccessSummary` and `MeAccess` in the
  recording and `/api/me` responses.

  Public vars:
  - `AccessSummary`, `MeAccess`
  - `UpdateSessionAccessRequest`, `SessionAccessResponse`, `DirectoryResponse`
  - `PersonItem`, `PeopleListResponse`, `AddPersonRequest`, `UpdatePersonRequest`,
    `PersonResponse`")

(def ^:private Uuid
  [:re #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"])

(def ^:private Visibility
  [:enum "private" "selected" "tenant"])

(def ^:private Role
  [:enum "member" "builder" "admin"])

(def AccessSummary
  "Caller-relative session access: audience, ownership and mutation right."
  [:map
   [:visibility Visibility]
   [:is_owner :boolean]
   [:can_manage :boolean]
   [:owner [:map
            [:display_name [:maybe :string]]
            [:is_me :boolean]
            [:kind {:optional true} [:enum "api_key"]]]]
   [:via_api_key {:optional true} :string]])

(def MeAccess
  "Access state and capability hints returned by GET /api/me."
  [:map
   [:state [:enum "active" "not_provisioned" "pending" "suspended" "unavailable"]]
   [:principal {:optional true}
    [:map
     [:id Uuid]
     [:type [:enum "human" "service"]]
     [:role {:optional true} [:maybe Role]]
     [:display_name {:optional true} [:maybe :string]]
     [:guest :boolean]
     [:api_key {:optional true}
      [:map
       [:id Uuid]
       [:name [:maybe :string]]
       [:kind [:enum "personal" "organization"]]
       [:read_scope {:optional true} [:enum "credential" "owner"]]]]]]
   [:capabilities {:optional true} [:map-of :keyword :boolean]]])

(def UpdateSessionAccessRequest
  "Body of PUT /api/sessions/{session_id}/access."
  [:map
   [:visibility Visibility]
   [:recipient_ids {:optional true} [:vector {:max 100} Uuid]]
   [:revision [:int {:min 0}]]])

(def ^:private Recipient
  [:map
   [:id Uuid]
   [:display_name [:maybe :string]]
   [:email [:maybe :string]]
   [:suspended {:optional true} :boolean]])

(def SessionAccessResponse
  "Response of GET/PUT /api/sessions/{session_id}/access."
  [:map
   [:ok :boolean]
   [:session_id Uuid]
   [:visibility Visibility]
   [:revision :int]
   [:is_owner :boolean]
   [:can_manage :boolean]
   [:owner {:optional true} [:map [:display_name [:maybe :string]] [:is_me :boolean]]]
   [:recipients {:optional true} [:vector Recipient]]])

(def DirectoryResponse
  "Response of GET /api/directory."
  [:map
   [:ok :boolean]
   [:items [:vector [:map
                     [:id Uuid]
                     [:display_name [:maybe :string]]
                     [:email [:maybe :string]]]]]])

(def PersonItem
  "One human principal on the People page."
  [:map
   [:id Uuid]
   [:display_name [:maybe :string]]
   [:name [:maybe :string]]
   [:email [:maybe :string]]
   [:role Role]
   [:status [:enum "pending" "active" "suspended"]]
   [:setup_state [:maybe :string]]
   [:identity_sync_state :string]
   [:last_seen_at [:maybe :string]]
   [:is_me :boolean]])

(def PeopleListResponse
  "Response of GET /api/people."
  [:map
   [:ok :boolean]
   [:total :int]
   [:active_admins :int]
   [:provisioning_enabled :boolean]
   [:items [:vector PersonItem]]])

(def AddPersonRequest
  "Body of POST /api/people. Tenant is always the caller's tenant."
  [:map
   [:email [:re {:max 254} #"^[^\s@]+@[^\s@]+\.[^\s@]+$"]]
   [:name {:optional true} [:string {:min 1 :max 200}]]
   [:role {:optional true} Role]])

(def UpdatePersonRequest
  "Body of PATCH /api/people/{id}."
  [:map
   [:role {:optional true} Role]
   [:status {:optional true} [:enum "active" "suspended"]]])

(def PersonResponse
  "Response of People mutations."
  [:map
   [:ok :boolean]
   [:person PersonItem]
   [:upstream {:optional true} [:enum "synced" "pending" "not_configured"]]
   [:setup_email {:optional true} [:enum "sent" "failed" "not_requested"]]])
