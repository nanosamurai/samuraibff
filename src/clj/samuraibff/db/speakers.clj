;; Copyright (c) samuraibff contributors.
(ns samuraibff.db.speakers
  "DB access for enrolled speakers.

  This namespace stores speaker metadata in Postgres (table `speakers`).

  Tables (see drsynth migrations):
  - speakers (id, tenant_id, user_id, audio_url, label, created_at)

  Public API:
  - `list-speakers`
  - `insert-speaker!`
  - `delete-speaker!`

  All functions accept a next.jdbc datasource (usually (:ds db))."
  (:require
    [honey.sql :as sql]
    [honey.sql.helpers :as h]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (java.time Instant)
    (java.util UUID)
    (javax.sql DataSource)))

(defn list-speakers
  "Return all speakers for a tenant.

  Inputs:
  - ds: javax.sql.DataSource
  - tenant-id: UUID

  Returns:
  - vector of maps {:id :tenant_id :user_id :label :audio_url :created_at}"
  [^DataSource ds ^UUID tenant-id]
  (when-not (and ds (instance? UUID tenant-id))
    (throw (ex-info "list-speakers missing tenant-id" {:tenant-id tenant-id})))
  (let [q (-> (h/select :id :tenant_id :user_id :label :audio_url :created_at)
              (h/from :speakers)
              (h/where [:= :tenant_id tenant-id])
              (h/order-by [:created_at :desc]))
        sqlvec (sql/format q)]
    (jdbc/execute! ds sqlvec {:builder-fn rs/as-unqualified-lower-maps})))

(defn insert-speaker!
  "Insert a new speaker row.

  Inputs:
  - ds: javax.sql.DataSource
  - {:keys [id tenant-id user-id label audio-url]}

  Returns:
  - map with inserted identifiers and timestamps." 
  [^DataSource ds {:keys [id tenant-id user-id label audio-url]}]
  (when-not (and ds (instance? UUID id) (instance? UUID tenant-id)
                 (seq (str label)) (seq (str audio-url)))
    (throw (ex-info "insert-speaker! missing required params"
                    {:id id :tenant-id tenant-id :label label :audio-url audio-url})))
  (let [values (cond-> {:id id
                        :tenant_id tenant-id
                        :label (str label)
                        :audio_url (str audio-url)}
                 (some? user-id) (assoc :user_id user-id))
        q (-> (h/insert-into :speakers)
              (h/values [values]))
        sqlvec (sql/format q)
        _ (jdbc/execute-one! ds sqlvec)
        created-at (Instant/now)]
    (assoc values :created_at created-at)))

(defn delete-speaker!
  "Delete a speaker row for a tenant.

  Inputs:
  - ds: javax.sql.DataSource
  - tenant-id: UUID
  - speaker-id: UUID

  Returns:
  - number of rows deleted." 
  [^DataSource ds ^UUID tenant-id ^UUID speaker-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID speaker-id))
    (throw (ex-info "delete-speaker! missing required params"
                    {:tenant-id tenant-id :speaker-id speaker-id})))
  (let [q (-> (h/delete-from :speakers)
              (h/where [:= :tenant_id tenant-id]
                       [:= :id speaker-id]))
        sqlvec (sql/format q)
        result (jdbc/execute-one! ds sqlvec)]
    (:next.jdbc/update-count result)))