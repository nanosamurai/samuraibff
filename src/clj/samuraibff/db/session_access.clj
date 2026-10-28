(ns samuraibff.db.session-access
  "Session read policy in SQL and atomic recording-audience changes.

  The read condition mirrors `samuraibff.access.policy/session-visible-to?`:
  an active principal reads a session of its own tenant when it owns it, when
  it manages the organization API key that owns it, or, for humans only, when
  the audience is `tenant` (Everyone) or `selected` with a read grant for that
  principal. A personal API key with read scope `credential` reads only the
  sessions created with that key. Ownerless legacy sessions are never
  readable. The caller's own status is checked before any query runs.

  Policy is applied in the WHERE clause, before pagination, counts and column
  projection, so lists and totals never reveal inaccessible sessions. Grants
  are matched with EXISTS, so they cannot duplicate list rows.

  Public API:
  - `readable-condition`, `scope-condition`, `key-manager-expr`
  - `find-accessible-session`, `list-recipients`
  - `set-audience!`"
  (:require
   [honey.sql :as sql]
   [honey.sql.helpers :as h]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [samuraibff.access.policy :as policy]
   [samuraibff.db.audit :as db.audit]
   [samuraibff.db.principals :as db.principals])
  (:import
   (java.util UUID)))

(def max-recipients
  "Upper bound of Selected people recipients per session."
  100)

(def ^:private row-opts
  {:builder-fn rs/as-unqualified-lower-maps})

(defn key-manager-expr
  "Return a HoneySQL boolean expression: the principal manages the
  organization API key whose service principal owns session `s`.

  True when the key's manager is the principal, or when the key has no
  manager and the principal is an Admin. Always false for services and for
  requests made with an API key (keys never get delegated rights).
  Mirrored by `samuraibff.access.policy/manages-key-session?` through the
  `:key_manager` column."
  [principal]
  (if (and (policy/human? principal) (not (policy/api-key? principal)))
    (let [pid (:id principal)
          tid (:tenant-id principal)]
      [:exists {:select [1]
                :from [[:api_credentials :kc]]
                :where [:and [:= :kc.tenant_id tid]
                        [:= :kc.service_user_id :s.user_id]
                        [:= :kc.kind "organization"]
                        (if (policy/admin? principal)
                          [:or [:= :kc.manager_user_id pid] [:= :kc.manager_user_id nil]]
                          [:= :kc.manager_user_id pid])]}])
    [:= 1 0]))

(defn readable-condition
  "Return a HoneySQL condition on sessions alias `s` for the principal's reads.

  Inputs: principal map (must be active; inactive principals match nothing).
  Returns: HoneySQL condition vector."
  [principal]
  (if-not (policy/active? principal)
    [:= 1 0]
    (let [pid (:id principal)
          tid (:tenant-id principal)
          key-id (get-in principal [:credential :id])
          restricted? (and (policy/personal-key? principal)
                           (not= :owner (get-in principal [:credential :read-scope])))]
      (conj [:and [:= :s.tenant_id tid] [:is-not :s.user_id nil]]
            (cond
              restricted?
              [:and [:= :s.user_id pid] [:= :s.created_via_credential_id key-id]]

              (policy/human? principal)
              [:or
               [:= :s.user_id pid]
               (key-manager-expr principal)
               [:= :s.visibility "tenant"]
               [:and [:= :s.visibility "selected"]
                [:exists {:select [1]
                          :from [[:session_access_grants :g]]
                          :where [:and [:= :g.session_id :s.id]
                                  [:= :g.tenant_id tid]
                                  [:= :g.user_id pid]]}]]]

              :else
              [:= :s.user_id pid])))))

(defn scope-condition
  "Return the condition for a Sessions view scope.

  Inputs: principal map, scope keyword :all | :mine | :shared. `:mine`
  includes sessions of organization keys the principal manages.
  Returns: HoneySQL condition vector on sessions alias `s`."
  [principal scope]
  (let [readable (readable-condition principal)
        mine [:or [:= :s.user_id (:id principal)] (key-manager-expr principal)]]
    (case scope
      :mine [:and readable mine]
      :shared [:and readable [:not mine]]
      readable)))

(defn find-accessible-session
  "Find one session the principal may read, or nil.

  Callers return a non-enumerating 404 for nil: the response must not reveal
  whether the session exists for somebody else.

  Inputs: connectable, principal map, session UUID.
  Returns: session row with :visibility :access_revision :owner_name
  :owner_email :owner_type :created_via_credential_id :created_via_name
  :key_manager, or nil."
  [connectable principal ^UUID session-id]
  (jdbc/execute-one!
   connectable
   (sql/format {:select [:s.id :s.session_key :s.tenant_id :s.user_id :s.title :s.status
                         :s.started_at :s.ended_at :s.stream_controls :s.created_at
                         :s.visibility :s.access_revision :s.created_via_credential_id
                         [:o.name :owner_name] [:o.email :owner_email]
                         [:o.principal_type :owner_type] [:cv.name :created_via_name]
                         [(key-manager-expr principal) :key_manager]]
                :from [[:sessions :s]]
                :left-join [[:app_users :o] [:= :o.id :s.user_id]
                            [:api_credentials :cv] [:and [:= :cv.id :s.created_via_credential_id]
                                                    [:= :cv.tenant_id :s.tenant_id]]]
                :where [:and [:= :s.id session-id] (readable-condition principal)]})
   row-opts))

(defn list-recipients
  "List the read-grant recipients of a session (owner-facing view).

  Inputs: connectable, tenant UUID, session UUID.
  Returns: vector of {:id :name :email :status} ordered by name."
  [connectable ^UUID tenant-id ^UUID session-id]
  (vec (jdbc/execute!
        connectable
        (sql/format {:select [:u.id :u.name :u.email :u.status]
                     :from [[:session_access_grants :g]]
                     :join [[:app_users :u] [:and [:= :u.id :g.user_id] [:= :u.tenant_id :g.tenant_id]]]
                     :where [:and [:= :g.tenant_id tenant-id] [:= :g.session_id session-id]]
                     :order-by [[[:lower [:coalesce :u.name :u.email]] :asc] [:u.id :asc]]})
        row-opts)))

(defn- lock-session!
  "Lock the session row with the columns the sharing policy needs."
  [tx principal ^UUID session-id]
  (jdbc/execute-one!
   tx
   (sql/format {:select [:s.id :s.tenant_id :s.user_id :s.visibility :s.access_revision
                         :s.created_via_credential_id
                         [(key-manager-expr principal) :key_manager]]
                :from [[:sessions :s]]
                :where [:and [:= :s.id session-id] [:= :s.tenant_id (:tenant-id principal)]]
                :for [:update :s]})
   row-opts))

(defn- granted?
  [tx ^UUID tenant-id ^UUID session-id ^UUID principal-id]
  (some? (jdbc/execute-one!
          tx
          (sql/format {:select [1]
                       :from [:session_access_grants]
                       :where [:and [:= :tenant_id tenant-id]
                               [:= :session_id session-id]
                               [:= :user_id principal-id]]}))))

(defn- validate-request
  "Return an error keyword for an invalid audience request, or nil."
  [principal {:keys [visibility recipient-ids]}]
  (cond
    (not (contains? policy/visibilities visibility)) :invalid-visibility
    (and (= "selected" visibility) (empty? recipient-ids)) :recipients-required
    (and (not= "selected" visibility) (seq recipient-ids)) :recipients-not-allowed
    (> (count recipient-ids) max-recipients) :too-many-recipients
    (contains? (set recipient-ids) (:id principal)) :invalid-recipients
    :else nil))

(defn- replace-grants!
  [tx principal ^UUID session-id recipient-ids]
  (jdbc/execute-one! tx (sql/format (-> (h/delete-from :session_access_grants)
                                        (h/where [:= :tenant_id (:tenant-id principal)]
                                                 [:= :session_id session-id]))))
  (when (seq recipient-ids)
    (jdbc/execute-one! tx (sql/format (-> (h/insert-into :session_access_grants)
                                          (h/values (mapv (fn [rid]
                                                            {:session_id session-id
                                                             :tenant_id (:tenant-id principal)
                                                             :user_id rid
                                                             :granted_by (:id principal)})
                                                          recipient-ids)))))))

(defn set-audience!
  "Atomically replace a session's audience and read grants.

  Only the active human owner may change the audience. The change applies only
  when `revision` equals the stored access revision, so stale overlapping Share
  saves are rejected instead of silently restoring access. Private and Everyone
  clear every grant; Selected people replaces the recipient set, which must be
  active humans of the same tenant.

  Inputs: DataSource, principal map, session UUID,
  {:visibility string :recipient-ids [uuid] :revision long}.
  Returns {:ok? true :visibility :revision :recipients :owner?} or
  {:ok? false :error kw :revision long?} with error one of :not-found
  :forbidden :conflict :invalid-visibility :recipients-required
  :recipients-not-allowed :too-many-recipients :invalid-recipients."
  [ds principal ^UUID session-id {:keys [visibility recipient-ids revision] :as request}]
  (let [recipient-ids (vec (distinct recipient-ids))
        request (assoc request :recipient-ids recipient-ids)]
    (jdbc/with-transaction [tx ds]
      (let [row (lock-session! tx principal session-id)
            tenant-id (:tenant-id principal)]
        (cond
          (nil? row)
          {:ok? false :error :not-found}

          (not (policy/can-share-session? principal row))
          (if (policy/session-visible-to? principal row
                                          (granted? tx tenant-id session-id (:id principal)))
            {:ok? false :error :forbidden}
            {:ok? false :error :not-found})

          (not= (long (or revision -1)) (long (:access_revision row)))
          {:ok? false :error :conflict :revision (:access_revision row)}

          (validate-request principal request)
          {:ok? false :error (validate-request principal request)}

          (not= (set recipient-ids)
                (db.principals/active-human-ids tx tenant-id recipient-ids))
          {:ok? false :error :invalid-recipients}

          :else
          (let [before (set (map :id (list-recipients tx tenant-id session-id)))
                new-revision (inc (long (:access_revision row)))]
            (replace-grants! tx principal session-id recipient-ids)
            (jdbc/execute-one! tx (sql/format (-> (h/update :sessions)
                                                  (h/set {:visibility visibility
                                                          :access_revision new-revision})
                                                  (h/where [:= :id session-id] [:= :tenant_id tenant-id]))))
            (db.audit/record! tx {:tenant-id tenant-id
                                  :actor-id (:id principal)
                                  :actor-kind "user"
                                  :action "session.audience.changed"
                                  :target-type "session"
                                  :target-id session-id
                                  :outcome "succeeded"
                                  :details {:from (:visibility row)
                                            :to visibility
                                            :added (mapv str (remove before recipient-ids))
                                            :removed (mapv str (remove (set recipient-ids) before))}})
            {:ok? true
             :visibility visibility
             :revision new-revision
             :owner? (policy/owns-session? principal row)
             :recipients (list-recipients tx tenant-id session-id)}))))))
