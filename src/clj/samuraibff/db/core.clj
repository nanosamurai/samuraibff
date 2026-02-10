(ns samuraibff.db.core
  "Database component.

  This namespace provides a Postgres connection pool backed by HikariCP.

  Integrant key:
  - `:samuraibff/db`

  Expected config (under `:samuraibff/config`):
  - [:db :jdbc-url]          string
  - [:db :username]          string
  - [:db :password]          string
  - [:db :maximum-pool-size] int (optional)

  Returns component map:
  - {:ds javax.sql.DataSource}"
  (:require
    [integrant.core :as ig]
    [org.corfield.logging4j2 :as log])
  (:import
    (com.zaxxer.hikari HikariConfig HikariDataSource)
    (javax.sql DataSource)))

(defn- hikari-datasource
  "Create a new HikariCP DataSource.

  Inputs:
  - db-config: map with keys:
      :jdbc-url string
      :username string
      :password string
      :maximum-pool-size int (optional)

  Returns:
  - javax.sql.DataSource (HikariDataSource)" 
  ^DataSource
  [{:keys [jdbc-url username password maximum-pool-size]
    :or {maximum-pool-size 10}}]
  (when-not (and (string? jdbc-url) (seq jdbc-url))
    (throw (ex-info "Missing :db :jdbc-url" {:jdbc-url jdbc-url})))
  (let [cfg (doto (HikariConfig.)
              (.setJdbcUrl jdbc-url)
              (.setUsername (or username ""))
              (.setPassword (or password ""))
              (.setMaximumPoolSize (int maximum-pool-size))

              ;; High-availability / graceful-start behavior:
              ;;
              ;; By default, Hikari validates connectivity during pool startup.
              ;; If Postgres is down, that throws and Integrant aborts the whole
              ;; process. For HA we want the *process* to stay up and report
              ;; "not ready" instead, so we disable fail-fast initialization.
              ;;
              ;; Semantics (HikariCP):
              ;; - initializationFailTimeout < 0 => do not fail startup
              ;; - the pool will keep trying to acquire connections in the
              ;;   background and will recover once DB becomes available.
              (.setInitializationFailTimeout -1)

              ;; Avoid attempting to eagerly keep idle connections on startup.
              ;; When DB is down this reduces noisy retries at boot.
              (.setMinimumIdle 0)

              ;; useful defaults
              (.setPoolName "samuraibff-hikari")
              ;; keep it reasonably fail-fast in dev
              (.setConnectionTimeout 5000))]
    (HikariDataSource. cfg)))

(defmethod ig/init-key :samuraibff/db
  [_ {:keys [config]}]
  (let [db-config (:db config)
        _ (log/info "Starting DB pool" {:jdbc-url (get db-config :jdbc-url)
                                         :maximum-pool-size (get db-config :maximum-pool-size)})
        ds (hikari-datasource db-config)]
    {:ds ds
     :config config}))

(defmethod ig/halt-key! :samuraibff/db
  [_ {:keys [^HikariDataSource ds]}]
  (when ds
    (try
      (.close ds)
      (catch Exception e
        (log/warn e "Failed to close DB pool" {}))))
  nil)
