(ns samuraibff.db.sessions
  "DB access for sessions.

  This namespace contains the minimal DB operations we need for session creation.

  Tables (see drsynth migration 0001):
  - tenants
  - app_users
  - sessions

  Public API:
  - `find-user-id-by-external-id`
  - `insert-session!`
  - `update-session-status!`

  All functions accept a next.jdbc datasource, typically provided by the
  Integrant `:samuraibff/db` component as `(:ds db)`.

  Note: we use HoneySQL to build SQL and next.jdbc to execute it." 
  (:require
    [honey.sql :as sql]
    [honey.sql.helpers :as h]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (java.util UUID)
    (javax.sql DataSource)))

(defn find-user-id-by-external-id
  "Find app_users.id for a given tenant + external id.

  Inputs:
  - ds: javax.sql.DataSource
  - tenant-id: java.util.UUID
  - external-id: string (Keycloak user sub)

  Returns:
  - java.util.UUID or nil" 
  ^UUID
  [^DataSource ds ^UUID tenant-id ^String external-id]
  (when (and ds tenant-id (seq (str external-id)))
    (let [q (-> (h/select :id)
                (h/from :app_users)
                (h/where [:= :tenant_id tenant-id]
                         [:= :external_id external-id])
                (h/limit 1))
          sqlvec (sql/format q)
          rows (jdbc/execute! ds sqlvec
                              {:builder-fn rs/as-unqualified-lower-maps})]
      (:id (first rows)))))

(defn insert-session!
  "Insert a new session row.

  Inputs:
  - ds: javax.sql.DataSource
  - {:keys [id tenant-id user-id session-key status]}
      id          => java.util.UUID
      tenant-id   => java.util.UUID
      user-id     => java.util.UUID or nil
      session-key => string
      status      => string (defaults to active)

  Side effects:
  - INSERT into sessions

  Returns:
  - map with inserted identifiers:
      {:id <uuid> :session-key <string>}" 
  [^DataSource ds {:keys [id tenant-id user-id session-key status]
                   :or {status "active"}}]
  (when-not (and ds (instance? UUID id) (instance? UUID tenant-id) (seq (str session-key)))
    (throw (ex-info "insert-session! missing required params"
                    {:id id :tenant-id tenant-id :session-key session-key})))
  (let [values (cond-> {:id id
                        :tenant_id tenant-id
                        :session_key (str session-key)
                        :status (str status)}
                 (some? user-id) (assoc :user_id user-id))
        q (-> (h/insert-into :sessions)
              (h/values [values]))
        sqlvec (sql/format q)]
    (jdbc/execute-one! ds sqlvec)
    {:id id :session-key (str session-key)}))

(defn update-session-status!
  "Update a session status for a tenant-scoped session.

  Inputs:
  - ds: DataSource
  - tenant-id: UUID
  - session-id: UUID
  - status: string (e.g. \"created\" | \"active\" | \"finished\" | \"failed\")

  Returns:
  - {:updated? boolean}

  Notes:
  - This does not change timestamps (started_at/ended_at) yet.
  - If the row does not exist for the given tenant, returns {:updated? false}." 
  [^DataSource ds ^UUID tenant-id ^UUID session-id ^String status]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id) (seq (str status)))
    (throw (ex-info "update-session-status! missing required params"
                    {:tenant-id tenant-id :session-id session-id :status status})))
  (let [q (-> (h/update :sessions)
              (h/set {:status (str status)})
              (h/where [:= :tenant_id tenant-id]
                       [:= :id session-id]))
        sqlvec (sql/format q)
        res (jdbc/execute-one! ds sqlvec)]
    {:updated? (pos? (long (or (:next.jdbc/update-count res) 0)))}))

(defn mark-session-started!
  "Set `sessions.started_at` to now() for a tenant-scoped session.

  Semantics:
  - This is invoked when audio recording actually begins (first successful
    /ws/audio connection).
  - It is idempotent-ish: it will NOT overwrite an existing started_at.

  Inputs:
  - ds: DataSource
  - tenant-id: UUID
  - session-id: UUID

  Returns:
  - {:updated? boolean}

  Notes:
  - Uses SQL `COALESCE(started_at, now())` so re-connects don't shift the start.
  - This function does not modify status."
  [^DataSource ds ^UUID tenant-id ^UUID session-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id))
    (throw (ex-info "mark-session-started! missing required params"
                    {:tenant-id tenant-id :session-id session-id})))
  (let [sqlvec ["UPDATE sessions\n              SET started_at = COALESCE(started_at, now())\n              WHERE tenant_id = ? AND id = ?"
               tenant-id session-id]
        res (jdbc/execute-one! ds sqlvec)]
    {:updated? (pos? (long (or (:next.jdbc/update-count res) 0)))}))
