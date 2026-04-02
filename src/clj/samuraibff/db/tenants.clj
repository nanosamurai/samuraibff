(ns samuraibff.db.tenants
  "DB access for tenants.

  This namespace exists primarily for UI-friendly metadata lookups.

  Public API:
  - `find-tenant-name`

  Security:
  - Functions are read-only and operate on tenant ids derived from auth claims."
  (:require
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (java.util UUID)
    (javax.sql DataSource)))

(defn find-tenant-name
  "Find tenant name by tenant id.

  Inputs:
  - ds: javax.sql.DataSource
  - tenant-id: java.util.UUID

  Returns:
  - string (tenant name) or nil when not found." 
  [^DataSource ds ^UUID tenant-id]
  (when (and ds (instance? UUID tenant-id))
    (let [row (jdbc/execute-one!
                ds
                ["SELECT name FROM tenants WHERE id = ?" tenant-id]
                {:builder-fn rs/as-unqualified-lower-maps})]
      (:name row))))
