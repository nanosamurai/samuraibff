(ns samuraibff.testcontainers.postgres
  "Postgres Testcontainers helpers.

  This namespace provides a small wrapper around the Java Testcontainers
  Postgres container.

  Public API:
  - `with-postgres` (macro)
  - `jdbc-url`      (fn)
  - `apply-schema!` (fn)

  The goal is to keep DB integration tests readable and consistent." 
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [next.jdbc :as jdbc]
    [org.corfield.logging4j2 :as log])
  (:import
    (org.testcontainers.containers PostgreSQLContainer)
    (javax.sql DataSource)))

(defn start-postgres!
  "Start a Postgres testcontainer.

  Returns:
  - org.testcontainers.containers.PostgreSQLContainer" 
  ^PostgreSQLContainer
  []
  (doto (PostgreSQLContainer. "postgres:16-alpine")
    (.withDatabaseName "drsynth")
    (.withUsername "drsynth")
    (.withPassword "drsynth")
    (.start)))

(defn stop-postgres!
  "Stop a Postgres testcontainer." 
  [^PostgreSQLContainer c]
  (when c
    (try
      (.stop c)
      (catch Exception _
        nil))))

(defn jdbc-url
  "Return JDBC URL for a running Postgres container." 
  [^PostgreSQLContainer c]
  (.getJdbcUrl c))

(defn datasource
  "Create a next.jdbc datasource for the given JDBC URL + credentials." 
  ^DataSource
  [jdbc-url username password]
  (jdbc/get-datasource {:dbtype "postgresql"
                        :jdbcUrl jdbc-url
                        :user username
                        :password password}))

(defn apply-schema!
  "Apply the test schema migrations to the given datasource.

  Inputs:
  - ds: javax.sql.DataSource

  Side effects:
  - executes the SQL from `test-resources/migrations/0001_create_core_schema.up.sql`
  - plus any additional migrations needed by the BFF read model used in tests

  Returns: nil" 
  [^DataSource ds]
  (let [paths ["migrations/0001_create_core_schema.up.sql"
               ;; NOTE: 0001 already contains workflows/workflow_defaults.
               "migrations/0009_sessions_workflow_overrides.up.sql"
               "migrations/0010_workflow_results_latest.up.sql"]]
    (doseq [path paths]
      (let [sql (some-> (io/resource path) slurp)]
        (when-not (seq sql)
          (throw (ex-info "Missing test migration resource" {:path path})))
        (log/info "Applying test DB schema migration" {:path path})
        ;; naive split: our migration files contain only DDL and no semicolons inside strings
        (doseq [stmt (->> (str/split sql #";\s*\n")
                          (map str/trim)
                          (remove str/blank?))]
          (jdbc/execute! ds [stmt]))))
    nil))

(defmacro with-postgres
  "Run body with a running Postgres testcontainer.

  Binds:
  - container-sym => PostgreSQLContainer

  Ensures container stop in finally." 
  [[container-sym] & body]
  `(let [~container-sym (start-postgres!)]
     (try
       ~@body
       (finally
         (stop-postgres! ~container-sym)))))
