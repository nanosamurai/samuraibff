(ns samuraibff.ui.access
  "Pure access-control helpers for the browser UI.

  The BFF returns an `access` block from `GET /api/me` and an `access` summary
  on each recording. Capabilities are presentation hints only: every route
  authorizes again on the server. This namespace turns those maps into labels,
  messages and request bodies so the logic runs under the CLJ test runner.

  Public API:
  - `access-state`, `active?`, `capability?`, `role-label`
  - `inactive-message`, `error-code`, `error-message`
  - `audience-label`, `owner-label`, `row-access-label`, `detail-access-label`
  - `share-request-body`, `toggle-recipient`, `clear-warning?`
  - `url-host`, `definition-badge`, `owner-name`
  - `role-descriptions`, `person-status-label`, `setup-label`,
    `sync-pending-label` (People page)")

(def ^:private legacy-capabilities
  "Capabilities assumed when the BFF does not send an `access` block.

  An older BFF has no roles, so the UI keeps its previous behavior. People
  management stays hidden because that page needs the new endpoints."
  {:record true
   :share_sessions false
   :manage_people false
   :manage_credentials true
   :manage_own_credentials true
   :author_integrations true
   :publish_integrations false
   :manage_defaults true
   :manage_speakers true})

(def inactive-states
  "Access states that replace the application with a message page."
  #{:not-provisioned :pending :suspended :unavailable})

(defn access-state
  "Return the access state keyword for an `/api/me` access block.

  Inputs: access map {:state string ...} or nil.
  Returns: one of :active :not-provisioned :pending :suspended :unavailable.
  A missing block (older BFF) is :active."
  [access]
  (case (some-> (:state access) str)
    nil :active
    "active" :active
    "not_provisioned" :not-provisioned
    "pending" :pending
    "suspended" :suspended
    :unavailable))

(defn active?
  "Return true when the access block allows the application to render."
  [access]
  (= :active (access-state access)))

(defn capability?
  "Return true when the `/api/me` access block grants a capability hint.

  Inputs: access map or nil; capability keyword such as :manage_people.
  Returns: boolean. Without an access block the legacy defaults apply."
  [access capability]
  (if (contains? access :capabilities)
    (true? (get-in access [:capabilities capability]))
    (if (some? (:state access))
      false
      (true? (get legacy-capabilities capability)))))

(defn role-label
  "Return the display label for a role string, or nil."
  [role]
  (case (some-> role str)
    "member" "Member"
    "builder" "Builder"
    "admin" "Admin"
    nil))

(defn inactive-message
  "Return {:title string :text string} for an inactive access state keyword."
  [state]
  (case state
    :not-provisioned {:title "Account not set up"
                      :text "Your account is not set up for this organization yet. Contact an Admin."}
    :pending {:title "Finish account setup"
              :text "Finish setting up your account from the email we sent, then sign in again."}
    :suspended {:title "Account suspended"
                :text "Your account is suspended. Contact an Admin."}
    {:title "Access unavailable"
     :text "Access can't be checked right now. Try again shortly."}))

(def ^:private code->state
  {"access-not-provisioned" :not-provisioned
   "account-setup-pending" :pending
   "account-suspended" :suspended
   "authorization-unavailable" :unavailable})

(defn error-access-state
  "Return the inactive access state for an API error, or nil.

  Inputs: HTTP status integer, error code string or nil."
  [status code]
  (let [state (get code->state (some-> code str))]
    (when (or (and (= 403 status) (contains? #{:not-provisioned :pending :suspended} state))
              (and (= 503 status) (= :unavailable state)))
      state)))

(defn error-code
  "Return a safe error code from a decoded JSON error body, or nil.

  Only short lowercase codes such as `read-only-access` are accepted, so
  free-form server text never reaches the UI."
  [body]
  (let [code (or (:message body) (:error body))]
    (when (and (string? code) (re-matches #"[a-z0-9][a-z0-9_-]{0,63}" code))
      code)))

(def ^:private error-messages
  {"read-only-access" "You have read-only access to this session."
   "not-found" "This item is no longer available to you."
   "access-changed" "Someone changed who can access this session. Review the current audience and try again."
   "recipients-required" "Choose at least one person, or choose another option."
   "invalid-recipients" "Some selected people can no longer receive shared sessions. Remove them and try again."
   "too-many-recipients" "Choose at most 100 people."
   "forbidden" "You don't have permission to do this."
   "invalid-default" "Only published items can be defaults. Publish it first."
   "last-admin" "Keep at least one active Admin. Make someone else an Admin first."
   "provisioning-disabled" "Adding people from the app is not enabled. Ask your operator to add accounts."
   "person-unavailable" "This email can't be added. It may belong to another account."
   "provisioning-failed" "The account could not be created. Try again later."
   "rate-limited" "Wait a minute before you send another setup email."
   "not-pending" "This person has already finished setup."
   "credential-revoked" "This credential is revoked. Create a new credential instead."
   "credential-provisioning-failed" "The credential could not be created. Try again later."
   "access-not-provisioned" "Your account is not set up for this organization yet. Contact an Admin."
   "account-setup-pending" "Finish setting up your account from the email we sent, then sign in again."
   "account-suspended" "Your account is suspended. Contact an Admin."
   "authorization-unavailable" "Access can't be checked right now. Try again shortly."})

(defn error-message
  "Return a user-facing sentence for an API error code.

  Inputs: code string or nil; fallback string used for unknown codes."
  [code fallback]
  (get error-messages (some-> code str) fallback))

(defn audience-label
  "Return the long audience label for a visibility string.

  Inputs: visibility (\"private\" | \"selected\" | \"tenant\"), tenant name or nil."
  [visibility tenant-name]
  (case (some-> visibility str)
    "selected" "Selected people"
    "tenant" (if (seq (str tenant-name))
               (str "Everyone in " tenant-name)
               "Everyone here")
    "Private"))

(defn owner-label
  "Return \"you\" or the owner's display name from a recording access summary."
  [access]
  (if (true? (get-in access [:owner :is_me]))
    "you"
    (or (not-empty (str (get-in access [:owner :display_name] ""))) "a colleague")))

(defn row-access-label
  "Return the compact owner/audience label for a Sessions row.

  Inputs: recording access summary map or nil, tenant name or nil.
  Returns: string such as \"By you · Private\" or \"By Alice · Shared\", or nil
  when the BFF sends no access summary."
  [access tenant-name]
  (when (map? access)
    (let [visibility (str (:visibility access))
          audience (case visibility
                     "selected" "Shared"
                     "tenant" (audience-label "tenant" tenant-name)
                     "Private")]
      (str "By " (owner-label access) " · " audience))))

(defn detail-access-label
  "Return the owner/audience label for the recording detail header.

  Owners see the audience. Recipients see that the session is read-only."
  [access tenant-name]
  (when (map? access)
    (if (true? (:is_owner access))
      (str "By you · " (audience-label (:visibility access) tenant-name))
      (str "By " (owner-label access)
           (if (= "tenant" (str (:visibility access)))
             (str " · " (audience-label "tenant" tenant-name) " · read-only")
             " · Shared with you · read-only")))))

(defn share-request-body
  "Build the PUT /api/sessions/:id/access body.

  Inputs: {:visibility string :recipient-ids [uuid-string] :revision int}.
  Returns: {:visibility .. :revision ..} plus :recipient_ids only for
  \"selected\", so other choices clear the recipient list on the server."
  [{:keys [visibility recipient-ids revision]}]
  (cond-> {:visibility (str visibility)
           :revision (or revision 0)}
    (= "selected" (str visibility)) (assoc :recipient_ids (vec (distinct (map str recipient-ids))))))

(defn toggle-recipient
  "Add or remove a person from a recipient vector.

  Inputs: vector of {:id ..} maps, person map, selected? boolean.
  Returns: vector without duplicates, original order kept."
  [recipients person selected?]
  (let [id (str (:id person))
        without (filterv #(not= id (str (:id %))) (or recipients []))]
    (if selected? (conj without person) without)))

(defn clear-warning?
  "Return true when saving will clear a non-empty selected-people list."
  [visibility recipients]
  (and (not= "selected" (str visibility)) (seq recipients) true))

(defn url-host
  "Return the host of an absolute URL string, or the input when it has none."
  [url]
  (let [url (str (or url ""))]
    (or (second (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^@/?#]*@)?([^/?#:]+)" url))
        url)))

(defn definition-badge
  "Return the availability label for a workflow or webhook, or nil."
  [availability]
  (case (some-> availability str)
    "personal" "Personal"
    "published" "Published"
    nil))

(defn owner-name
  "Return the owner label for a workflow/webhook/credential owner map, or nil."
  [owner]
  (when (map? owner)
    (if (true? (:is_me owner))
      "You"
      (not-empty (str (or (:display_name owner) ""))))))

(def role-descriptions
  "Ordered role choices with their People page descriptions."
  [{:value "member" :label "Member"
    :description "Records and shares sessions. Uses published workflows and webhooks."}
   {:value "builder" :label "Builder"
    :description "Also creates personal workflows, webhooks and automation credentials."}
   {:value "admin" :label "Admin"
    :description "Manages people, publishing and defaults."}])

(defn person-status-label
  "Return {:label string :kind keyword} for a People row status string."
  [status]
  (case (some-> status str)
    "active" {:label "Active" :kind :ok}
    "pending" {:label "Invited" :kind :muted}
    "suspended" {:label "Suspended" :kind :bad}
    {:label "Unknown" :kind :muted}))

(defn setup-label
  "Return the setup status sentence for a People row, or nil when complete."
  [setup-state]
  (case (some-> setup-state str)
    "email_pending" "Setup email not sent yet"
    "email_sent" "Setup email sent"
    "email_failed" "Setup email failed"
    nil))

(defn sync-pending-label
  "Return the retry label for a pending sign-in sync, or nil when synced."
  [identity-sync-state]
  (case (some-> identity-sync-state str)
    "disable_pending" "Sign-in disable pending — retry"
    "enable_pending" "Sign-in enable pending — retry"
    nil))
