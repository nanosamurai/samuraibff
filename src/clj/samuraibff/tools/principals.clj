(ns samuraibff.tools.principals
  "Operator command for explicit principal provisioning and legacy ownership.

  Run inside the BFF image (database settings come from SAMURAIBFF_DB_*):

    java -cp /app/samuraibff.jar clojure.main -m samuraibff.tools.principals <command> [--option value ...]

  Commands:
  - provision      --tenant UUID --subject SUB --issuer URL [--type human|service]
                   [--role member|builder|admin] [--name N] [--email E]
                   [--client-id ID] [--tenant-name NAME]
                   Creates an active principal. --tenant-name also creates a
                   missing tenant. Use it to nominate the first Admin and to
                   register existing machine clients.
  - set-role       --tenant UUID --id UUID --role ROLE
  - set-status     --tenant UUID --id UUID --status active|suspended
  - list           --tenant UUID
  - legacy-report  Aggregate counts of sessions without an owner, per tenant.
  - assign-owner   --tenant UUID --session UUID --owner UUID
                   Assigns a verified owner to one ownerless legacy session.

  Every change writes an `operator` audit event. The command never prints
  emails, tokens or transcript content. The last active Admin is protected
  here too: provision a replacement Admin first."
  (:gen-class)
  (:require
   [clojure.string :as str]
   [honey.sql :as sql]
   [honey.sql.helpers :as h]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [samuraibff.db.audit :as db.audit]
   [samuraibff.db.principals :as db.principals])
  (:import
   (java.util UUID)))

(defn- fail!
  [message]
  (throw (ex-info message {:type ::usage})))

(defn- parse-options
  "Parse `--key value` pairs into a keyword map."
  [args]
  (loop [[k v & more] args
         options {}]
    (cond
      (nil? k) options
      (and (str/starts-with? k "--") (some? v)) (recur more (assoc options (keyword (subs k 2)) v))
      :else (fail! (str "Invalid argument: " k)))))

(defn- uuid!
  [options k]
  (try (UUID/fromString (str (get options k)))
       (catch Exception _ (fail! (str "--" (name k) " must be a UUID")))))

(defn- datasource
  []
  (let [env #(some-> (System/getenv %) str/trim not-empty)
        url (or (env "SAMURAIBFF_DB_JDBC_URL") (fail! "SAMURAIBFF_DB_JDBC_URL is required"))]
    (jdbc/get-datasource {:jdbcUrl url
                          :user (env "SAMURAIBFF_DB_USERNAME")
                          :password (env "SAMURAIBFF_DB_PASSWORD")})))

(defn- audit!
  [tx tenant-id action target-id details]
  (db.audit/record! tx {:tenant-id tenant-id :actor-kind "operator" :action action
                        :target-type "principal" :target-id target-id
                        :outcome "succeeded" :details details}))

(defn- print-principal
  [row]
  (println (str/join "\t" [(:id row) (:principal_type row) (or (:role row) "-") (:status row)
                           (or (:client_id row) "-") (or (:name row) "-")])))

(defn provision!
  "Create an active principal. Returns the inserted row."
  [ds options]
  (let [tenant-id (uuid! options :tenant)
        type (get options :type "human")
        role (when (= "human" type) (get options :role "member"))]
    (when-not (#{"human" "service"} type) (fail! "--type must be human or service"))
    (when (and role (not (#{"member" "builder" "admin"} role))) (fail! "--role must be member, builder or admin"))
    (when (and (= "service" type) (str/blank? (:client-id options))) (fail! "--client-id is required for services"))
    (when (str/blank? (:subject options)) (fail! "--subject is required"))
    (when (str/blank? (:issuer options)) (fail! "--issuer is required"))
    (jdbc/with-transaction [tx ds]
      (when-let [tenant-name (:tenant-name options)]
        (jdbc/execute-one! tx (sql/format (-> (h/insert-into :tenants)
                                              (h/values [{:id tenant-id :name tenant-name}])
                                              (h/on-conflict :id) (h/do-nothing)))))
      (let [row (db.principals/insert-principal!
                 tx {:id (UUID/randomUUID)
                     :tenant-id tenant-id
                     :external-id (:subject options)
                     :issuer (:issuer options)
                     :principal-type type
                     :role role
                     :status "active"
                     :client-id (:client-id options)
                     :name (:name options)
                     :email (:email options)})]
        (audit! tx tenant-id "principal.provision" (:id row) {:type type :role role})
        row))))

(defn- update-membership!
  [ds options changes]
  (let [tenant-id (uuid! options :tenant)
        id (uuid! options :id)]
    (jdbc/with-transaction [tx ds]
      (db.principals/lock-tenant-membership! tx tenant-id)
      (let [row (or (db.principals/find-by-id tx tenant-id id) (fail! "Principal not found"))
            removes-admin? (and (= "admin" (:role row)) (= "active" (:status row))
                                (or (and (:role changes) (not= "admin" (:role changes)))
                                    (= "suspended" (:status changes))))]
        (when (and removes-admin? (<= (db.principals/count-active-admins tx tenant-id) 1))
          (fail! "Refusing to remove the last active Admin"))
        (let [updated (db.principals/update-principal! tx tenant-id id changes)]
          (audit! tx tenant-id "principal.update" id
                  {:role_from (:role row) :role_to (:role updated)
                   :status_from (:status row) :status_to (:status updated)})
          updated)))))

(defn legacy-report
  "Return [{:tenant_id :ownerless_sessions}] aggregate counts."
  [ds]
  (jdbc/execute! ds (sql/format {:select [:tenant_id [[:count :*] :ownerless_sessions]]
                                 :from [:sessions]
                                 :where [:= :user_id nil]
                                 :group-by [:tenant_id]})
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn assign-owner!
  "Assign an owner to one ownerless session of the tenant. Returns update count."
  [ds options]
  (let [tenant-id (uuid! options :tenant)
        session-id (uuid! options :session)
        owner-id (uuid! options :owner)]
    (jdbc/with-transaction [tx ds]
      (when-not (db.principals/find-by-id tx tenant-id owner-id)
        (fail! "Owner is not a principal of this tenant"))
      (let [n (:next.jdbc/update-count
               (jdbc/execute-one! tx (sql/format (-> (h/update :sessions)
                                                     (h/set {:user_id owner-id})
                                                     (h/where [:= :tenant_id tenant-id]
                                                              [:= :id session-id]
                                                              [:= :user_id nil])))))]
        (when (zero? n) (fail! "Session not found or already owned"))
        (db.audit/record! tx {:tenant-id tenant-id :actor-kind "operator" :action "session.owner.assigned"
                              :target-type "session" :target-id session-id :outcome "succeeded"
                              :details {:owner_id (str owner-id)}})
        n))))

(defn -main
  "Entry point. Exits 0 on success, 1 on failure, 2 on usage errors."
  [& [command & args]]
  (try
    (let [options (parse-options args)
          ds (datasource)]
      (case command
        "provision" (print-principal (provision! ds options))
        "set-role" (print-principal (update-membership! ds options {:role (:role options)}))
        "set-status" (print-principal (update-membership! ds options {:status (:status options)}))
        "list" (run! print-principal (:items (db.principals/list-people ds (uuid! options :tenant) {:limit 200})))
        "legacy-report" (doseq [{:keys [tenant_id ownerless_sessions]} (legacy-report ds)]
                          (println (str tenant_id "\t" ownerless_sessions)))
        "assign-owner" (println "assigned" (assign-owner! ds options))
        (fail! "Unknown command; see the namespace docstring")))
    (System/exit 0)
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (if (= ::usage (:type (ex-data e))) (ex-message e) "Operation failed")))
      (System/exit (if (= ::usage (:type (ex-data e))) 2 1)))
    (catch Exception e
      (binding [*out* *err*] (println "Operation failed:" (.getName (class e))))
      (System/exit 1))))
