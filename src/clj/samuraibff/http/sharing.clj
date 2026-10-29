(ns samuraibff.http.sharing
  "HTTP handlers for recording audiences and the colleague directory.

  Endpoints (under /api, active principal required):
  - GET /api/sessions/:session_id/access  current audience; recipients for the owner
  - PUT /api/sessions/:session_id/access  replace audience (owner only, revisioned)
  - PUT /api/sessions/:session_id/owner   assign an owner to a legacy session (Admin, revisioned)
  - GET /api/directory?q=                 active human colleagues for sharing

  Sharing is read-only access to the whole session: audio, every transcript
  track and results. A link never grants access by itself.

  Public API:
  - `get-access-handler`, `put-access-handler`, `put-owner-handler`,
    `directory-handler`"
  (:require
   [org.corfield.logging4j2 :as log]
   [samuraibff.access.policy :as policy]
   [samuraibff.db.principals :as db.principals]
   [samuraibff.db.session-access :as session-access]
   [samuraibff.access.schemas :as access.schemas]
   [samuraibff.http.session-guard :as session-guard]
   [malli.core :as m])
  (:import
   (java.util UUID)))

(defn- json-response
  [status body]
  {:status status :body body})

(defn- parse-uuid!
  [value]
  (try
    (UUID/fromString (str value))
    (catch Exception _
      (throw (ex-info "invalid-session-id" {:type :samuraibff.http/invalid-session-id})))))

(defn- path-session-id
  [req]
  (parse-uuid! (or (get-in req [:path-params :session_id])
                   (get-in req [:path-params "session_id"]))))

(defn- recipient->api
  [{:keys [id name email status]}]
  (cond-> {:id (str id) :display_name (or name email) :email email}
    (not= "active" status) (assoc :suspended true)))

(defn- access-body
  [principal session recipients]
  (let [manager? (policy/can-manage-session? principal session)]
    (cond-> (merge {:ok true
                    :session_id (str (:id session))
                    :revision (long (:access_revision session))}
                   (session-guard/access-summary principal session))
      manager? (assoc :recipients (mapv recipient->api recipients)))))

(defn- handle-errors
  [e]
  (let [type (:type (ex-data e))]
    (or (session-guard/error-response type)
        (case type
          :samuraibff.http/invalid-session-id (json-response 400 {:ok false :message "invalid-session-id"})
          :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
          (do (log/error e "Sharing request failed")
              (json-response 500 {:ok false :message "internal-error"}))))))

(defn get-access-handler
  "Handler for GET /api/sessions/:session_id/access.

  Readers get the audience and their own read-only state; only whoever may
  manage the session (owner or organization key manager) sees the recipient
  list. Inaccessible sessions return 404."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [ds (or (:ds db) (throw (ex-info "db" {:type :samuraibff.http/missing-datasource})))
            principal (session-guard/require-principal! req)
            session (session-guard/require-readable-session! ds req (path-session-id req))
            recipients (when (policy/can-manage-session? principal session)
                         (session-access/list-recipients ds (:tenant-id principal) (:id session)))]
        (json-response 200 (access-body principal session recipients)))
      (catch clojure.lang.ExceptionInfo e (handle-errors e)))))

(def ^:private error-statuses
  {:not-found [404 "not-found"]
   :forbidden [403 "read-only-access"]
   :conflict [409 "access-changed"]
   :invalid-visibility [400 "invalid-visibility"]
   :recipients-required [400 "recipients-required"]
   :recipients-not-allowed [400 "recipients-not-allowed"]
   :too-many-recipients [400 "too-many-recipients"]
   :invalid-recipients [400 "invalid-recipients"]})

(defn put-access-handler
  "Handler for PUT /api/sessions/:session_id/access.

  Body: access.schemas/UpdateSessionAccessRequest (router-coerced)
  {:visibility \"private\"|\"selected\"|\"tenant\" :recipient_ids [uuid] :revision int}.
  Saves the complete audience atomically. A stale revision returns 409 with the
  current revision so the client can reload instead of overwriting."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [ds (or (:ds db) (throw (ex-info "db" {:type :samuraibff.http/missing-datasource})))
            principal (session-guard/require-principal! req)
            session-id (path-session-id req)
            body (or (get-in req [:parameters :body]) (:body-params req) {})
            _ (when-not (m/validate access.schemas/UpdateSessionAccessRequest body)
                (throw (ex-info "invalid-request" {:type :samuraibff.http/invalid-request})))
            {:keys [visibility recipient_ids revision]} body
            result (session-access/set-audience!
                    ds principal session-id
                    {:visibility visibility
                     :recipient-ids (mapv #(UUID/fromString (str %)) (or recipient_ids []))
                     :revision revision})]
        (if (:ok? result)
          (do
            (log/info "Session audience changed" {:session_id (str session-id)
                                                  :tenant_id (str (:tenant-id principal))
                                                  :visibility visibility
                                                  :recipients_count (count (:recipients result))})
            (json-response 200 {:ok true
                                :session_id (str session-id)
                                :visibility (:visibility result)
                                :revision (:revision result)
                                :is_owner (boolean (:owner? result))
                                :can_manage true
                                :recipients (mapv recipient->api (:recipients result))}))
          (let [[status message] (get error-statuses (:error result) [400 "invalid-request"])]
            (json-response status (cond-> {:ok false :message message}
                                    (:revision result) (assoc :revision (:revision result)))))))
      (catch clojure.lang.ExceptionInfo e
        (if (= :samuraibff.http/invalid-request (:type (ex-data e)))
          (json-response 400 {:ok false :message "invalid-request"})
          (handle-errors e))))))

(def ^:private owner-error-statuses
  {:not-found [404 "not-found"]
   :forbidden [403 "forbidden"]
   :conflict [409 "access-changed"]
   :invalid-owner [400 "invalid-owner"]})

(defn put-owner-handler
  "Handler for PUT /api/sessions/:session_id/owner.

  Body: access.schemas/AssignSessionOwnerRequest {:owner_id uuid :revision int}.
  An Admin gives a session recorded before access control (owner unknown) an
  owner. The audience stays; the response carries the new revision and the
  caller's access. A stale revision returns 409 with the current revision."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [ds (or (:ds db) (throw (ex-info "db" {:type :samuraibff.http/missing-datasource})))
            principal (session-guard/require-principal! req)
            session-id (path-session-id req)
            body (or (get-in req [:parameters :body]) (:body-params req) {})
            _ (when-not (m/validate access.schemas/AssignSessionOwnerRequest body)
                (throw (ex-info "invalid-request" {:type :samuraibff.http/invalid-request})))
            result (session-access/assign-owner!
                    ds principal session-id
                    {:owner-id (UUID/fromString (str (:owner_id body)))
                     :revision (:revision body)})]
        (if (:ok? result)
          (do
            (log/info "Session owner assigned" {:session_id (str session-id)
                                                :tenant_id (str (:tenant-id principal))})
            (json-response 200 {:ok true
                                :session_id (str session-id)
                                :visibility (:visibility result)
                                :revision (:revision result)
                                :is_owner (boolean (:owner? result))
                                :can_manage (boolean (:owner? result))
                                :can_assign_owner false}))
          (let [[status message] (get owner-error-statuses (:error result) [400 "invalid-request"])]
            (json-response status (cond-> {:ok false :message message}
                                    (:revision result) (assoc :revision (:revision result)))))))
      (catch clojure.lang.ExceptionInfo e
        (if (= :samuraibff.http/invalid-request (:type (ex-data e)))
          (json-response 400 {:ok false :message "invalid-request"})
          (handle-errors e))))))

(defn directory-handler
  "Handler for GET /api/directory?q=&limit=.

  Returns at most 20 active human members of the caller's organization,
  excluding the caller. Services and suspended people are never listed."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [ds (or (:ds db) (throw (ex-info "db" {:type :samuraibff.http/missing-datasource})))
            principal (session-guard/require-principal! req)
            _ (when-not (policy/can-use-directory? principal)
                (throw (ex-info "forbidden" {:type :samuraibff.http/forbidden})))
            query (merge (:params req) (get-in req [:parameters :query]))
            q (let [raw (str (or (:q query) (get query "q") ""))]
                (subs raw 0 (min 100 (count raw))))
            people (db.principals/search-directory ds (:tenant-id principal)
                                                   {:q q :limit 20 :exclude-id (:id principal)})]
        (json-response 200 {:ok true
                            :items (mapv (fn [{:keys [id name email]}]
                                           {:id (str id) :display_name (or name email) :email email})
                                         people)}))
      (catch clojure.lang.ExceptionInfo e (handle-errors e)))))
