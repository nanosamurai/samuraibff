(ns samuraibff.http.definition-access-integration-test
  "Role rules for workflow and webhook definitions, defaults and session snapshots.

  Uses one Postgres testcontainer per test with two Builders, a Member, an
  Admin, a foreign-tenant user and a service principal."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [samuraibff.db.principals :as db.principals]
   [samuraibff.db.sessions :as db.sessions]
   [samuraibff.db.workflows :as db.workflows]
   [samuraibff.http.webhooks :as http.webhooks]
   [samuraibff.http.workflows :as http.workflows]
   [samuraibff.schemas :as schemas]
   [samuraibff.secrets.core :as secrets.core]
   [samuraibff.sessions.meta :as sessions.meta]
   [samuraibff.test-support.principals :as p]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.util UUID)))

(defn- setup!
  "Create the schema, two tenants and the cast of principals."
  [ds]
  (tc.pg/apply-schema! ds)
  (let [tenant (p/insert-tenant! ds (UUID/randomUUID) "acme")
        foreign-tenant (p/insert-tenant! ds (UUID/randomUUID) "other")
        principal (fn [opts] (p/insert-principal! ds (assoc opts :tenant-id tenant)))]
    {:tenant tenant
     :builder-a (principal {:role :builder :name "Builder A"})
     :builder-b (principal {:role :builder :name "Builder B"})
     :member (principal {:role :member :name "Member"})
     :admin (principal {:role :admin :name "Admin"})
     :service (principal {:type :service})
     :foreign (p/insert-principal! ds {:tenant-id foreign-tenant :role :admin})}))

(defn- call
  "Invoke a handler factory as principal with extra request keys."
  [handler deps principal extra]
  ((handler deps) (p/request-for principal extra)))

(defn- unique-name
  "Make a definition body unique; names are unique per tenant."
  [body]
  (update body :name str " " (subs (str (UUID/randomUUID)) 0 8)))

(defn- item-ids
  [resp]
  (set (map :id (get-in resp [:body :items]))))

(defn- item
  [resp id]
  (first (filter #(= id (:id %)) (get-in resp [:body :items]))))

(def ^:private workflow-payload
  {:name "Summary"
   :trigger {:type "transcript.refined.segment" :track_id "parakeet"}
   :provider {:type "bedrock" :model_id "model" :params {:temperature 0.1}}
   :prompt {:text "Summarize the visit"}})

(defn- audit-events
  [ds tenant prefix]
  (jdbc/execute! ds ["SELECT action, details::text AS details FROM access_audit_events
                       WHERE tenant_id = ? AND action LIKE ? ORDER BY created_at"
                     tenant (str prefix "%")]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(deftest workflow-definition-access-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          {:keys [tenant builder-a builder-b member admin service foreign]} (setup! ds)
          deps {:db {:ds ds}}
          create (fn [principal body] (call http.workflows/create-workflow-handler deps principal {:body-params (unique-name body)}))
          update! (fn [principal id body] (call http.workflows/update-workflow-handler deps principal
                                                {:path-params {:id id} :body-params body}))
          delete! (fn [principal id] (call http.workflows/delete-workflow-handler deps principal {:path-params {:id id}}))
          list-as (fn [principal] (call http.workflows/list-workflows-handler deps principal {}))
          defaults-as (fn [principal] (call http.workflows/get-defaults-handler deps principal {}))
          set-defaults! (fn [principal ids] (call http.workflows/set-defaults-handler deps principal
                                                  {:body-params {:workflow_ids ids}}))]
      (testing "Members and services cannot create; Builders cannot create published"
        (is (= {:status 403 :body {:ok false :message "forbidden"}} (create member workflow-payload)))
        (is (= 403 (:status (create service workflow-payload))))
        (is (= 403 (:status (create builder-a (assoc workflow-payload :availability "published"))))))
      (let [a-id (get-in (create builder-a workflow-payload) [:body :workflow_id])
            b-id (get-in (create builder-b workflow-payload) [:body :workflow_id])
            pub-id (get-in (create admin workflow-payload) [:body :workflow_id])
            admin-personal-id (get-in (create admin (assoc workflow-payload :availability "personal")) [:body :workflow_id])]
        (is (every? string? [a-id b-id pub-id admin-personal-id]))
        (is (= "personal" (:availability (db.workflows/find-workflow ds tenant (UUID/fromString a-id)))))
        (is (= (:id builder-a) (:owner_user_id (db.workflows/find-workflow ds tenant (UUID/fromString a-id)))))

        (testing "Member lists published definitions only, without prompt or parameters"
          (let [resp (list-as member)
                listed (item resp pub-id)]
            (is (= #{pub-id} (item-ids resp)))
            (is (= (:body resp) (schemas/validate! schemas/WorkflowsListResponse (:body resp))))
            (is (not (contains? listed :prompt)))
            (is (not (contains? (:provider listed) :params)))
            (is (not (contains? listed :incremental)))
            (is (= {:type "bedrock" :model_id "model"} (:provider listed)))
            (is (= false (:can_manage listed)))
            (is (= {:id (str (:id admin)) :display_name "Admin" :is_me false} (:owner listed)))
            (is (not (str/includes? (pr-str (:body resp)) "Summarize the visit")))))

        (testing "Builder lists published plus own personal, full detail only for own"
          (let [resp (list-as builder-a)]
            (is (= #{pub-id a-id} (item-ids resp)))
            (is (= "Summarize the visit" (get-in (item resp a-id) [:prompt :text])))
            (is (= true (:can_manage (item resp a-id))))
            (is (= true (get-in (item resp a-id) [:owner :is_me])))
            (is (not (contains? (item resp pub-id) :prompt)))))

        (testing "Admin sees every tenant definition; foreign tenant sees none"
          (is (= #{a-id b-id pub-id admin-personal-id} (item-ids (list-as admin))))
          (is (empty? (item-ids (list-as foreign))))
          (is (= 403 (:status (list-as service)))))

        (testing "Peer personal definitions are invisible; published ones are read-only for Builders"
          (is (= 404 (:status (update! builder-b a-id {:name "Hijack"}))))
          (is (= 404 (:status (delete! builder-b a-id))))
          (is (= 404 (:status (update! member a-id {:name "Hijack"}))))
          (is (= 404 (:status (update! foreign a-id {:name "Hijack"}))))
          (is (= 403 (:status (update! builder-a pub-id {:name "Hijack"}))))
          (is (= 403 (:status (delete! builder-a pub-id))))
          (is (= 403 (:status (update! member pub-id {:name "Hijack"}))))
          (is (str/starts-with? (:name (db.workflows/find-workflow ds tenant (UUID/fromString a-id))) "Summary")))

        (testing "Builder edits own personal definition but cannot publish it"
          (is (= 200 (:status (update! builder-a a-id {:name "Renamed"})))))
        (is (= 403 (:status (update! builder-a a-id {:availability "published"}))))
        (is (= "personal" (:availability (db.workflows/find-workflow ds tenant (UUID/fromString a-id)))))

        (testing "Only Admin sets defaults, and only to published definitions"
          (is (= 403 (:status (set-defaults! builder-a [pub-id]))))
          (is (= 403 (:status (set-defaults! member [pub-id]))))
          (is (= {:status 400 :body {:ok false :message "invalid-default"}} (set-defaults! admin [a-id])))
          (is (= 400 (:status (set-defaults! admin [(str (UUID/randomUUID))]))))
          (is (= 200 (:status (set-defaults! admin [pub-id]))))
          (is (= [pub-id] (get-in (defaults-as member) [:body :workflow_ids])))
          (is (= 403 (:status (defaults-as service)))))

        (testing "Unpublishing removes the definition from defaults"
          (is (= 200 (:status (update! admin pub-id {:availability "personal"}))))
          (is (= [] (get-in (defaults-as admin) [:body :workflow_ids])))
          (is (= #{} (item-ids (list-as member)))))

        (testing "Admin publishes a Builder definition, which then becomes Admin-managed"
          (is (= 200 (:status (update! admin a-id {:availability "published"}))))
          (is (= 403 (:status (update! builder-a a-id {:name "Mine again"}))))
          (is (contains? (item-ids (list-as member)) a-id)))

        (testing "Deleting removes the definition from defaults"
          (is (= 200 (:status (set-defaults! admin [a-id]))))
          (is (= 200 (:status (delete! admin a-id))))
          (is (= [] (get-in (defaults-as admin) [:body :workflow_ids]))))

        (testing "Changes are audited without prompts"
          (let [events (audit-events ds tenant "workflow.")]
            (is (= #{"workflow.create" "workflow.update" "workflow.publish" "workflow.unpublish"
                     "workflow.delete" "workflow.defaults.set"}
                   (set (map :action events))))
            (is (not (str/includes? (pr-str events) "Summarize")))))))))

(defn- recording-store
  "Return [SecretStore calls-atom] for a fake store that records secret names."
  []
  (let [calls (atom [])]
    [(reify secrets.core/SecretStore
       (put-secret! [_ {:keys [name]}]
         (swap! calls conj name)
         {:secret-ref (str "test-ref:" (UUID/randomUUID))})
       (delete-secret! [_ _] nil))
     calls]))

(def ^:private webhook-payload
  {:name "Hook"
   :url "https://hooks.example.test/receive"
   :enabled true
   :auth {:type "oauth" :token_url "https://idp.example.test/token" :client_id "client" :scopes "a b"}
   :subscriptions ["recording.finished"]
   :static_headers {"X-Team" "alpha"}
   :oauth_client_secret "synthetic-secret"})

(deftest webhook-definition-access-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          {:keys [builder-a builder-b member admin service]} (setup! ds)
          [store calls] (recording-store)
          deps {:db {:ds ds} :secrets {:store store}}
          create (fn [principal body] (call http.webhooks/create-webhook-handler deps principal {:body-params (unique-name body)}))
          update! (fn [principal id body] (call http.webhooks/update-webhook-handler deps principal
                                                {:path-params {:id id} :body-params body}))
          delete! (fn [principal id] (call http.webhooks/delete-webhook-handler deps principal {:path-params {:id id}}))
          list-as (fn [principal] (call http.webhooks/list-webhooks-handler deps principal {}))
          set-defaults! (fn [principal ids] (call http.webhooks/set-defaults-handler deps principal
                                                  {:body-params {:webhook_ids ids}}))]
      (testing "Members and services cannot create, and no secret is stored for them"
        (is (= 403 (:status (create member webhook-payload))))
        (is (= 403 (:status (create service webhook-payload))))
        (is (= 403 (:status (create builder-a (assoc webhook-payload :availability "published")))))
        (is (empty? @calls)))
      (let [pub-id (get-in (create admin webhook-payload) [:body :webhook_id])
            a-id (get-in (create builder-a webhook-payload) [:body :webhook_id])
            secrets-before (count @calls)]
        (is (every? string? [pub-id a-id]))

        (testing "Member gets a safe projection without secret refs or auth settings"
          (let [resp (list-as member)
                listed (item resp pub-id)]
            (is (= #{pub-id} (item-ids resp)))
            (is (= (:body resp) (schemas/validate! schemas/WebhooksListResponse (:body resp))))
            (is (= #{:id :tenant_id :name :url :enabled :subscriptions :availability :owner :can_manage :created_at}
                   (set (keys listed))))
            (is (= "https://hooks.example.test" (:url listed)) "Members see only the destination origin")
            (is (= ["recording.finished"] (:subscriptions listed)))
            (is (not (str/includes? (pr-str (:body resp)) "test-ref:")))))

        (testing "Admin gets full detail including secret refs, never secret values"
          (let [listed (item (list-as admin) pub-id)]
            (is (str/starts-with? (:oauth_client_secret_ref listed) "test-ref:"))
            (is (= "client" (:oauth_client_id listed)))
            (is (= {"X-Team" "alpha"} (:static_headers listed)))
            (is (not (str/includes? (pr-str (list-as admin)) "synthetic-secret")))))

        (testing "Peer Builder cannot see or change a personal webhook, and stores no secret"
          (is (not (contains? (item-ids (list-as builder-b)) a-id)))
          (is (= 404 (:status (update! builder-b a-id {:url "https://evil.example.test" :hmac_secret "x"}))))
          (is (= 404 (:status (delete! builder-b a-id))))
          (is (= 403 (:status (update! builder-a pub-id {:url "https://evil.example.test" :hmac_secret "x"}))))
          (is (= secrets-before (count @calls))))

        (testing "Owner edits own personal webhook; only Admin publishes"
          (is (= 200 (:status (update! builder-a a-id {:name "Hook 2" :subscriptions ["transcript.final.ready"]}))))
          (is (= ["transcript.final.ready"] (:subscriptions (item (list-as builder-a) a-id))))
          (is (= 403 (:status (update! builder-a a-id {:availability "published"})))))

        (testing "Defaults accept published webhooks only and only from Admin"
          (is (= 403 (:status (set-defaults! builder-a [pub-id]))))
          (is (= 400 (:status (set-defaults! admin [a-id]))))
          (is (= 200 (:status (set-defaults! admin [pub-id]))))
          (is (= 200 (:status (delete! admin pub-id))))
          (is (= [] (get-in (call http.webhooks/get-defaults-handler deps member {}) [:body :webhook_ids]))))))))

(defn- insert-session!
  [ds tenant owner workflow-ids webhook-ids]
  (let [id (UUID/randomUUID)]
    (db.sessions/insert-session! ds {:id id :tenant-id tenant :user-id (:id owner) :session-key (str id)
                                     :status "created"
                                     :workflow-overrides {:workflow_ids workflow-ids}
                                     :webhook-overrides {:webhook_ids webhook-ids}})
    id))

(defn- workflow-ids-of
  [meta]
  (set (map :workflow_id (get-in meta [:workflows :targets]))))

(defn- webhook-ids-of
  [meta]
  (set (map :webhook_id (mapcat val (get-in meta [:webhook_routing :targets_by_event_type])))))

(defn- stored-admission
  [ds session-id]
  (:snapshot (jdbc/execute-one! ds ["SELECT admission_snapshot::text AS snapshot FROM sessions WHERE id = ?" session-id]
                                {:builder-fn rs/as-unqualified-lower-maps})))

(deftest session-snapshot-uses-owner-access-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          {:keys [tenant builder-a builder-b admin member]} (setup! ds)
          [store] (recording-store)
          deps {:db {:ds ds} :secrets {:store store}}
          config {:features {:ce-mode? false}}
          workflow (fn [principal body] (get-in (call http.workflows/create-workflow-handler deps principal {:body-params (unique-name body)})
                                                [:body :workflow_id]))
          webhook (fn [principal body] (get-in (call http.webhooks/create-webhook-handler deps principal {:body-params (unique-name body)})
                                               [:body :webhook_id]))
          personal-wf (workflow builder-a workflow-payload)
          published-wf (workflow admin workflow-payload)
          personal-hook (webhook builder-a webhook-payload)
          published-hook (webhook admin webhook-payload)
          _ (call http.workflows/set-defaults-handler deps admin {:body-params {:workflow_ids [published-wf]}})
          _ (call http.webhooks/set-defaults-handler deps admin {:body-params {:webhook_ids [published-hook]}})
          own-session (insert-session! ds tenant builder-a [personal-wf] [personal-hook])
          peer-session (insert-session! ds tenant builder-b [personal-wf] [personal-hook])
          member-session (insert-session! ds tenant member [personal-wf] [personal-hook])]
      (testing "Owner uses own personal definitions plus published defaults"
        (let [meta (sessions.meta/resolve-sessions-meta config ds tenant own-session)]
          (is (= #{personal-wf published-wf} (workflow-ids-of meta)))
          (is (= #{personal-hook published-hook} (webhook-ids-of meta)))
          (is (not (str/includes? (cheshire/generate-string meta) "synthetic-secret")))))
      (testing "Peer and Member sessions never use another user's personal definitions"
        (doseq [session [peer-session member-session]]
          (let [meta (sessions.meta/resolve-sessions-meta config ds tenant session)]
            (is (= #{published-wf} (workflow-ids-of meta)))
            (is (= #{published-hook} (webhook-ids-of meta))))))
      (testing "A demoted owner loses personal definitions but keeps published ones"
        (db.principals/update-principal! ds tenant (:id builder-a) {:role "member"})
        (let [meta (sessions.meta/resolve-sessions-meta config ds tenant own-session)]
          (is (= #{published-wf} (workflow-ids-of meta)))
          (is (= #{published-hook} (webhook-ids-of meta))))
        (db.principals/update-principal! ds tenant (:id builder-a) {:role "builder"}))
      (testing "Defaults contribute only published definitions"
        (jdbc/execute! ds ["UPDATE workflow_defaults SET workflow_ids = ?::uuid[] WHERE tenant_id = ?"
                           (into-array UUID [(UUID/fromString personal-wf) (UUID/fromString published-wf)]) tenant])
        (let [session (insert-session! ds tenant builder-a [] [])]
          (is (= #{published-wf} (workflow-ids-of (sessions.meta/resolve-sessions-meta config ds tenant session))))))
      (testing "Admission snapshot is frozen at first resolution"
        (let [session (insert-session! ds tenant builder-a [personal-wf] [personal-hook])
              first-meta (sessions.meta/resolve-admission-meta! config ds tenant session)
              stored (stored-admission ds session)]
          (is (= #{personal-wf published-wf} (workflow-ids-of first-meta)))
          (is (some? stored))
          (is (not (str/includes? stored "synthetic-secret")))
          (call http.workflows/update-workflow-handler deps builder-a
                {:path-params {:id personal-wf} :body-params {:prompt {:text "Changed prompt"}}})
          (call http.webhooks/delete-webhook-handler deps builder-a {:path-params {:id personal-hook}})
          (db.principals/update-principal! ds tenant (:id builder-a) {:role "member"})
          (let [again (sessions.meta/resolve-admission-meta! config ds tenant session)]
            (is (= stored (stored-admission ds session)))
            (is (= (:workflows first-meta) (:workflows again)))
            (is (= (:webhook_routing first-meta) (:webhook_routing again)))
            (is (= "Summarize the visit"
                   (get-in (first (filter #(= personal-wf (:workflow_id %)) (get-in again [:workflows :targets])))
                           [:prompt :text]))))
          (is (thrown? clojure.lang.ExceptionInfo
                       (sessions.meta/resolve-admission-meta! config ds tenant (UUID/randomUUID)))))))))
