(ns samuraibff.access.membership
  "Administrator-led membership: add people, change roles, suspend, reactivate.

  Rules:
  - Only an active human Admin of the tenant calls these functions; the tenant
    is always the Admin's own tenant.
  - Membership changes of one tenant are serialized with a transaction-scoped
    lock, so concurrent requests on any replica cannot remove the last active
    Admin.
  - Suspension commits local denial first, then disables the Keycloak account
    and ends its sessions. A failed upstream call is stored as a pending sync
    state and can be retried; it is never reported as complete.
  - Adding a person creates the Keycloak user with an Admin-controlled tenant
    attribute, verifies that binding, stores a pending principal and asks
    Keycloak to send the setup email. The person gets access only after a
    verified login. Existing Keycloak accounts are never adopted by email.
  - Demoting or suspending a Builder revokes the machine credentials they
    manage. Reactivation never revives revoked credentials.

  Public API:
  - `person->api`, `list-people`, `add-person!`, `resend-setup-email!`,
    `update-person!`"
  (:require
   [next.jdbc :as jdbc]
   [org.corfield.logging4j2 :as log]
   [samuraibff.access.policy :as policy]
   [samuraibff.db.audit :as db.audit]
   [samuraibff.db.principals :as db.principals]
   [samuraibff.keycloak.users :as kc.users]
   [samuraibff.util.uuid :as uuid])
  (:import
   (java.time Duration Instant)
   (java.util UUID)))

(def resend-interval
  "Minimum time between two setup emails for one person."
  (Duration/ofSeconds 60))

(defn person->api
  "Project a principal row for the People page."
  [admin row]
  {:id (str (:id row))
   :display_name (or (:name row) (:email row))
   :name (:name row)
   :email (:email row)
   :role (:role row)
   :status (:status row)
   :setup_state (:setup_state row)
   :identity_sync_state (:identity_sync_state row)
   :last_seen_at (some-> (:last_seen_at row) str)
   :is_me (= (str (:id admin)) (str (:id row)))})

(defn- audit!
  [connectable admin action target-id outcome details]
  (db.audit/record! connectable {:tenant-id (:tenant-id admin)
                                 :actor-id (:id admin)
                                 :actor-kind "user"
                                 :action action
                                 :target-type "principal"
                                 :target-id target-id
                                 :outcome outcome
                                 :details details}))

(defn list-people
  "Return {:items [person] :total n :active_admins n} for the Admin's tenant."
  [ds admin {:keys [limit offset q]}]
  (let [{:keys [items total]} (db.principals/list-people ds (:tenant-id admin)
                                                         {:limit limit :offset offset :q q})]
    {:items (mapv #(person->api admin %) items)
     :total total
     :active_admins (db.principals/count-active-admins ds (:tenant-id admin))}))

(defn- keycloak-managed?
  "True when the principal's identity lives in the configured realm."
  [config row]
  (and (kc.users/configured? config)
       (= (:issuer row) (get-in config [:auth :issuer]))))

(defn- send-setup!
  "Send the setup email and record the outcome. Returns :sent or :failed."
  [ds config admin row]
  (let [outcome (try
                  (kc.users/send-setup-email! config (:external_id row))
                  :sent
                  (catch Exception e
                    (log/warn "Setup email failed" {:principal_id (str (:id row))
                                                   :failure (str (:type (ex-data e)))})
                    :failed))]
    (db.principals/update-principal! ds (:tenant-id admin) (:id row)
                                     {:setup_state (if (= :sent outcome) "email_sent" "email_failed")
                                      :setup_email_sent_at [:now]})
    (audit! ds admin "person.setup_email" (:id row) (if (= :sent outcome) "succeeded" "failed") {})
    outcome))

(defn add-person!
  "Provision a new person through the restricted Keycloak adapter.

  Inputs: DataSource, config, admin principal, {:email :name :role}.
  Returns {:ok? true :person row :setup-email :sent|:failed} or
  {:ok? false :error kw} with :provisioning-disabled, :identity-unavailable
  or :provisioning-failed."
  [ds config admin {:keys [email name role] :or {role "member"}}]
  (if-not (kc.users/configured? config)
    {:ok? false :error :provisioning-disabled}
    (let [operation-id (uuid/uuid7)
          tenant-id (:tenant-id admin)
          user-id (try
                    (:user-id (kc.users/create-user! config {:email email :name name :tenant-id tenant-id}))
                    (catch clojure.lang.ExceptionInfo e
                      (if (= ::kc.users/conflict (:type (ex-data e)))
                        ::conflict
                        (do (log/warn "Keycloak user creation failed" {:failure (str (:type (ex-data e)))})
                            ::failed))))]
      (case user-id
        ::conflict (do (audit! ds admin "person.add" nil "denied" {:reason "identity-unavailable"})
                       {:ok? false :error :identity-unavailable})
        ::failed {:ok? false :error :provisioning-failed}
        (let [binding-ok? (try
                            (= (str tenant-id) (:tenant-id (kc.users/find-user config user-id)))
                            (catch Exception _ false))
              row (when binding-ok?
                    (try
                      (jdbc/with-transaction [tx ds]
                        (let [row (db.principals/insert-principal!
                                   tx {:id (UUID/randomUUID)
                                       :tenant-id tenant-id
                                       :external-id user-id
                                       :issuer (get-in config [:auth :issuer])
                                       :principal-type "human"
                                       :role role
                                       :status "pending"
                                       :name name
                                       :email email
                                       :setup-state "email_pending"
                                       :provisioning-operation-id operation-id})]
                          (audit! tx admin "person.add" (:id row) "succeeded" {:role role})
                          row))
                      (catch Exception e
                        (log/warn "Principal provisioning failed" {:failure (str (class e))})
                        nil)))]
          (if-not row
            (do
              (try (kc.users/set-enabled! config user-id false)
                   (catch Exception _
                     (log/error "Could not disable a partially provisioned Keycloak user"
                                {:tenant_id (str tenant-id) :operation_id (str operation-id)})))
              {:ok? false :error :provisioning-failed})
            (let [outcome (send-setup! ds config admin row)]
              {:ok? true
               :person (db.principals/find-by-id ds tenant-id (:id row))
               :setup-email outcome})))))))

(defn resend-setup-email!
  "Resend the setup email for a pending person, at most once per minute.

  Returns {:ok? true :person row :setup-email kw} or {:ok? false :error kw}
  with :not-found, :not-pending, :rate-limited or :provisioning-disabled."
  [ds config admin person-id]
  (let [row (db.principals/find-by-id ds (:tenant-id admin) person-id)]
    (cond
      (or (nil? row) (not= "human" (:principal_type row))) {:ok? false :error :not-found}
      (not= "pending" (:status row)) {:ok? false :error :not-pending}
      (not (keycloak-managed? config row)) {:ok? false :error :provisioning-disabled}
      (and (:setup_email_sent_at row)
           (.isAfter (.plus (.toInstant ^java.util.Date (:setup_email_sent_at row)) resend-interval)
                     (Instant/now)))
      {:ok? false :error :rate-limited}
      :else
      (let [outcome (send-setup! ds config admin row)]
        {:ok? true :person (db.principals/find-by-id ds (:tenant-id admin) person-id) :setup-email outcome}))))

(defn- removes-admin?
  [row {:keys [role status]}]
  (and (= "admin" (:role row)) (= "active" (:status row))
       (or (and role (not= "admin" role))
           (= "suspended" status))))

(defn- next-status
  "Reactivating a person who never completed setup returns them to pending."
  [row status]
  (if (and (= "active" status) (contains? #{"email_pending" "email_sent" "email_failed"} (:setup_state row)))
    "pending"
    status))

(defn- sync-upstream!
  "Mirror the local status to Keycloak. Returns :synced, :pending or :not_configured."
  [ds config admin row]
  (if-not (keycloak-managed? config row)
    :not_configured
    (let [enable? (not= "suspended" (:status row))]
      (try
        (kc.users/set-enabled! config (:external_id row) enable?)
        (db.principals/update-principal! ds (:tenant-id admin) (:id row) {:identity_sync_state "synced"})
        :synced
        (catch Exception e
          (log/warn "Keycloak account sync failed" {:principal_id (str (:id row))
                                                    :failure (str (:type (ex-data e)))})
          (db.principals/update-principal! ds (:tenant-id admin) (:id row)
                                           {:identity_sync_state (if enable? "enable_pending" "disable_pending")})
          (audit! ds admin "person.identity_sync" (:id row) "pending" {:enabled enable?})
          :pending)))))

(defn update-person!
  "Change a person's role and/or status, or retry a pending Keycloak sync.

  Inputs: DataSource, config, admin principal, person UUID,
  {:role string? :status \"active\"|\"suspended\"?},
  revoke-credentials! (fn [tenant-id manager-id] ...) called after commit when
  the person loses Builder rights or is suspended.
  Returns {:ok? true :person row :upstream kw} or {:ok? false :error kw} with
  :not-found or :last-admin."
  [ds config admin person-id {:keys [role status] :as changes} revoke-credentials!]
  (let [tenant-id (:tenant-id admin)
        result (jdbc/with-transaction [tx ds]
                 (db.principals/lock-tenant-membership! tx tenant-id)
                 (let [row (db.principals/find-by-id tx tenant-id person-id)]
                   (cond
                     (or (nil? row) (not= "human" (:principal_type row)))
                     {:ok? false :error :not-found}

                     (and (removes-admin? row changes)
                          (<= (db.principals/count-active-admins tx tenant-id) 1))
                     (do (audit! tx admin "person.update" person-id "denied" {:reason "last-admin"})
                         {:ok? false :error :last-admin})

                     :else
                     (let [updates (cond-> {}
                                     role (assoc :role role)
                                     status (assoc :status (next-status row status)))
                           updated (if (seq updates)
                                     (db.principals/update-principal! tx tenant-id person-id updates)
                                     row)]
                       (when (seq updates)
                         (audit! tx admin "person.update" person-id "succeeded"
                                 {:role_from (:role row) :role_to (:role updated)
                                  :status_from (:status row) :status_to (:status updated)}))
                       {:ok? true :before row :person updated}))))]
    (if-not (:ok? result)
      result
      (let [{:keys [before person]} result
            lost-builder? (and (contains? #{"builder" "admin"} (:role before))
                               (or (= "member" (:role person)) (= "suspended" (:status person))))
            status-changed? (not= (:status before) (:status person))
            sync-pending? (not= "synced" (:identity_sync_state before))]
        (when lost-builder?
          (revoke-credentials! tenant-id person-id))
        (log/info "Membership updated" {:tenant_id (str tenant-id)
                                        :principal_id (str person-id)
                                        :role (:role person)
                                        :status (:status person)})
        {:ok? true
         :person (db.principals/find-by-id ds tenant-id person-id)
         :upstream (if (or status-changed? sync-pending?)
                     (sync-upstream! ds config admin person)
                     :synced)}))))

(defn admin-principal?
  "Convenience for handlers: true when principal may manage people."
  [principal]
  (policy/can-manage-people? principal))
