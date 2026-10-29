(ns samuraibff.http.webhooks
  "HTTP handlers for tenant-scoped webhook configuration.

  Endpoints (under /api, auth required):
  - GET    /api/webhooks
  - POST   /api/webhooks
  - PUT    /api/webhooks/:id
  - DELETE /api/webhooks/:id
  - GET    /api/webhooks/defaults
  - PUT    /api/webhooks/defaults

  Access (see `samuraibff.http.definition-access`):
  - Every active human lists the webhooks they may view. Secret refs, OAuth
    settings, API-key header settings, static headers and the auth type are
    returned only for webhooks the caller may manage.
  - Builders and Admins create. Builders create personal webhooks only.
  - Admins publish, unpublish and set defaults. Defaults hold published
    webhooks only. Unpublishing or deleting a webhook removes it from the
    defaults in the same transaction.

  Secret handling:
  - secret values are accepted only as inputs, after authorization
  - secrets are stored via `:samuraibff/secrets` component
  - API responses never echo secret values (only refs, to managers)
  "
  (:require
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [samuraibff.access.policy :as policy]
   [samuraibff.db.webhooks :as db.webhooks]
   [samuraibff.http.definition-access :as access]
   [samuraibff.observability.diagnostics :as diagnostics]
   [samuraibff.schemas :as schemas]
   [samuraibff.secrets.core :as secrets.core]
   [samuraibff.util.uuid :as util.uuid])
  (:import
   (java.util UUID)))

(defn- json-response
  [status body]
  {:status status
   :body body})

(defn- require-ds!
  [db]
  (or (:ds db)
      (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource}))))

(defn- parse-uuid-or-nil
  [s]
  (try
    (UUID/fromString (str s))
    (catch Exception _
      nil)))

(defn- require-path-id!
  [req]
  (or (parse-uuid-or-nil (or (get-in req [:path-params :id]) (get-in req [:path-params "id"])))
      (throw (ex-info "Invalid id" {:type :samuraibff.http/invalid-id}))))

(defn- failure-response
  "Map a handler failure to a fixed JSON response.

  Known ex-info types map to fixed statuses; other ex-info failures use
  fallback-status; other exceptions return 500. Logs never include payloads."
  [^Throwable e log-message fallback-status]
  (let [type (:type (ex-data e))]
    (or (access/error-response type)
        (case type
          :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
          :samuraibff.http/invalid-id (json-response 400 {:ok false :message "invalid-id"})
          (if (instance? clojure.lang.ExceptionInfo e)
            (do (log/warn log-message (diagnostics/failure-details e))
                (json-response fallback-status {:ok false :message (if (= 400 fallback-status)
                                                                     "invalid-request"
                                                                     "internal-error")}))
            (do (log/error log-message (diagnostics/failure-details e))
                (json-response 500 {:ok false :message "internal-error"})))))))

(defn- normalize-static-headers
  "Ensure static headers is a small string->string map.

  We intentionally do not allow overriding system-controlled headers.
  "
  [m]
  (let [blocked #{"x-nanosamurai-signature"
                  "x-nanosamurai-event-id"
                  "x-nanosamurai-event-type"
                  "x-nanosamurai-tenant-id"
                  "authorization"}]
    (into {}
          (keep (fn [[k v]]
                  (let [k (some-> k str str/trim)
                        v (some-> v str str/trim)
                        k0 (some-> k str/lower-case)]
                    (when (and (seq k) (seq v)
                               (<= (count k) 128)
                               (<= (count v) 2000)
                               (not (contains? blocked k0)))
                      [k v]))))
          (or m {}))))

(def ^:private managed-only-keys
  "Webhook columns returned only to principals who may manage the webhook."
  [:auth_type
   :hmac_secret_ref :oauth_client_secret_ref :api_key_ref
   :oauth_token_url :oauth_client_id :oauth_scopes
   :api_key_header_name :api_key_prefix
   :static_headers])

(defn- destination-origin
  "Return scheme://host[:port] of a webhook URL, dropping credentials, path,
  query and fragment, or nil for a malformed URL."
  [url]
  (try
    (let [uri (java.net.URI. (str url))]
      (when (and (.getScheme uri) (.getHost uri))
        (str (.getScheme uri) "://" (.getHost uri)
             (when (pos? (.getPort uri)) (str ":" (.getPort uri))))))
    (catch Exception _ nil)))

(defn- webhook-row->api-item
  "Convert a webhook row and its subscriptions into an API item for the principal.

  Every viewer gets id, name, destination, enabled, subscriptions,
  availability, owner and `can_manage`. Viewers who cannot manage the webhook
  see only the destination origin (scheme, host, port): URL credentials, paths
  and query tokens are withheld. Auth settings, secret refs and static headers
  are added only when the principal may manage the webhook."
  [principal owners row subscriptions]
  (let [manage? (policy/can-manage-definition? principal row)
        base {:id (str (:id row))
              :tenant_id (str (:tenant_id row))
              :name (:name row)
              :url (if manage? (:url row) (or (destination-origin (:url row)) ""))
              :enabled (boolean (:enabled row))
              :subscriptions (vec subscriptions)
              :availability (:availability row)
              :owner (access/owner-view principal owners (:owner_user_id row))
              :can_manage manage?
              :created_at (str (:created_at row))}]
    (if manage?
      (merge base (select-keys row managed-only-keys))
      base)))

(defn list-webhooks-handler
  "Handler for GET /api/webhooks.

  Returns 200 {:ok true :tenant_id :items [WebhookItem]} with only the
  webhooks the principal may view, 403 for non-human principals."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [principal (access/require-human! req)
            tenant-uuid (:tenant-id principal)
            ds (require-ds! db)
            _ (log/info "Listing webhooks" {:tenant_id (str tenant-uuid)
                                            :principal_id (str (:id principal))})
            rows (filterv #(policy/can-view-definition? principal %)
                          (db.webhooks/list-webhooks ds tenant-uuid))
            owners (access/owner-views ds tenant-uuid rows)
            items (mapv (fn [row]
                          (webhook-row->api-item principal owners row
                                                 (db.webhooks/list-subscriptions ds tenant-uuid (:id row))))
                        rows)]
        (json-response 200 {:ok true
                            :tenant_id (str tenant-uuid)
                            :items items}))
      (catch Exception e
        (failure-response e "Failed listing webhooks" 500)))))

(defn- store-secret-if-present!
  "Store a secret string in SecretStore and return secret-ref (or nil).

  Inputs:
  - secret-store: SecretStore
  - tenant-id-str: string
  - name: string (logical name)
  - secret-value: string|nil
  "
  [secret-store tenant-id-str name secret-value]
  (let [v (secrets.core/blank->nil secret-value)]
    (when (and secret-store v)
      (:secret-ref (secrets.core/put-secret! secret-store
                                             {:tenant-id tenant-id-str
                                              :name name
                                              :value v})))))

(defn create-webhook-handler
  "Handler for POST /api/webhooks.

  Builders create personal webhooks owned by themselves; a Builder request
  for `published` returns 403. Admins may pass `availability` (default
  `published`). Members and services get 403 before any secret is stored."
  [{:keys [db secrets]}]
  (fn [req]
    (let [body (or (:body-params req) (:body req) {})]
      (try
        (let [principal (access/require-human! req)
              _ (when-not (policy/can-author-integrations? principal) (access/forbidden!))
              tenant-uuid (:tenant-id principal)
              tenant-id-str (str tenant-uuid)
              ds (require-ds! db)
              {:keys [name url enabled auth subscriptions static_headers
                      hmac_secret api_key oauth_client_secret availability]}
              (schemas/decode-and-validate! schemas/CreateWebhookRequest body)
              availability (access/creation-availability principal availability)
              name (str/trim (str name))
              url (str/trim (str url))
              auth-type (get auth :type)
              webhook-id (util.uuid/uuid7)
              store (:store secrets)
              row {:id webhook-id
                   :tenant-id tenant-uuid
                   :name name
                   :url url
                   :enabled (boolean enabled)
                   :auth-type (clojure.core/name auth-type)
                   :hmac_secret_ref (store-secret-if-present! store tenant-id-str (str "webhook-hmac/" name) hmac_secret)
                   :api_key_ref (store-secret-if-present! store tenant-id-str (str "webhook-api-key/" name) api_key)
                   :oauth_client_secret_ref (store-secret-if-present! store tenant-id-str (str "webhook-oauth/" name) oauth_client_secret)
                   :oauth_token_url (get auth :token_url)
                   :oauth_client_id (get auth :client_id)
                   :oauth_scopes (get auth :scopes)
                   :api_key_header_name (get auth :header_name)
                   :api_key_prefix (get auth :prefix)
                   :static_headers (normalize-static-headers static_headers)
                   :owner_user_id (:id principal)
                   :availability availability}]
          (log/info "Creating webhook" {:tenant_id tenant-id-str
                                        :principal_id (str (:id principal))
                                        :webhook_id (str webhook-id)
                                        :availability availability
                                        :auth_type (clojure.core/name auth-type)
                                        :enabled (boolean enabled)
                                        :subscriptions_count (count subscriptions)
                                        :static_headers_count (count (or static_headers {}))})
          (access/in-transaction
           ds
           (fn [tx]
             (db.webhooks/insert-webhook! tx row)
             (db.webhooks/replace-subscriptions! tx tenant-uuid webhook-id subscriptions)
             (access/record-audit! tx principal "webhook.create" "webhook" webhook-id
                                   {:name name :availability availability})))
          (log/info "Created webhook" {:tenant_id tenant-id-str :webhook_id (str webhook-id)})
          (json-response 200 {:ok true
                              :webhook_id (str webhook-id)}))
        (catch Exception e
          (failure-response e "Failed creating webhook" 400))))))

(defn- webhook-patch
  "Build the DB patch from a validated UpdateWebhookRequest and stored secret refs."
  [{:keys [name url enabled auth static_headers]} {:keys [hmac-ref api-key-ref oauth-client-secret-ref]}]
  (cond-> {}
    (some? name) (assoc :name (str/trim (str name)))
    (some? url) (assoc :url (str/trim (str url)))
    (some? enabled) (assoc :enabled (boolean enabled))
    (some? auth) (assoc :auth_type (clojure.core/name (get auth :type))
                        :oauth_token_url (get auth :token_url)
                        :oauth_client_id (get auth :client_id)
                        :oauth_scopes (get auth :scopes)
                        :api_key_header_name (get auth :header_name)
                        :api_key_prefix (get auth :prefix))
    (some? static_headers) (assoc :static_headers (normalize-static-headers static_headers))
    (some? hmac-ref) (assoc :hmac_secret_ref hmac-ref)
    (some? api-key-ref) (assoc :api_key_ref api-key-ref)
    (some? oauth-client-secret-ref) (assoc :oauth_client_secret_ref oauth-client-secret-ref)))

(defn- store-update-secrets!
  "Store secrets sent with an update and return their refs."
  [store tenant-id-str webhook-id {:keys [name hmac_secret api_key oauth_client_secret]}]
  (let [logical (or name webhook-id)]
    {:hmac-ref (store-secret-if-present! store tenant-id-str (str "webhook-hmac/" logical) hmac_secret)
     :api-key-ref (store-secret-if-present! store tenant-id-str (str "webhook-api-key/" logical) api_key)
     :oauth-client-secret-ref (store-secret-if-present! store tenant-id-str (str "webhook-oauth/" logical) oauth_client_secret)}))

(defn- availability-action
  [availability-patch]
  (case (:availability availability-patch)
    "published" "webhook.publish"
    "personal" "webhook.unpublish"
    "webhook.update"))

(defn update-webhook-handler
  "Handler for PUT /api/webhooks/:id.

  Requires `can-manage-definition?`: 404 when the webhook is not visible, 403
  when it is visible but not manageable. Secrets are stored only after this
  check. Changing `availability` requires Admin. Unpublishing removes the
  webhook from the tenant defaults."
  [{:keys [db secrets]}]
  (fn [req]
    (let [body (or (:body-params req) (:body req) {})]
      (try
        (let [principal (access/require-human! req)
              tenant-uuid (:tenant-id principal)
              ds (require-ds! db)
              webhook-id (require-path-id! req)
              parsed (schemas/decode-and-validate! schemas/UpdateWebhookRequest body)]
          (access/in-transaction
           ds
           (fn [tx]
             (let [existing (access/authorize-manage!
                             principal (db.webhooks/lock-webhook tx tenant-uuid webhook-id))
                   availability-patch (access/availability-change principal existing (:availability parsed))
                   refs (store-update-secrets! (:store secrets) (str tenant-uuid) webhook-id parsed)
                   field-patch (webhook-patch parsed refs)
                   patch (merge field-patch availability-patch)
                   subscriptions (:subscriptions parsed)]
               (when (seq patch)
                 (db.webhooks/update-webhook! tx tenant-uuid webhook-id patch))
               (when (some? subscriptions)
                 (db.webhooks/replace-subscriptions! tx tenant-uuid webhook-id subscriptions))
               (when (= "personal" (:availability availability-patch))
                 (db.webhooks/remove-default! tx tenant-uuid webhook-id))
               (when (or (seq patch) (some? subscriptions))
                 (access/record-audit! tx principal (availability-action availability-patch) "webhook" webhook-id
                                       (cond-> {:changed_fields (vec (sort (map name (cond-> (keys field-patch)
                                                                                         (some? subscriptions) (conj :subscriptions)))))}
                                         availability-patch (assoc :availability (:availability availability-patch))))))))
          (log/info "Updated webhook" {:tenant_id (str tenant-uuid)
                                       :principal_id (str (:id principal))
                                       :webhook_id (str webhook-id)})
          (json-response 200 {:ok true}))
        (catch Exception e
          (failure-response e "Failed updating webhook" 400))))))

(defn delete-webhook-handler
  "Handler for DELETE /api/webhooks/:id.

  Same access rules as update. Deleting removes the webhook from the tenant
  defaults in the same transaction."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [principal (access/require-human! req)
            tenant-uuid (:tenant-id principal)
            ds (require-ds! db)
            webhook-id (require-path-id! req)]
        (log/info "Deleting webhook" {:tenant_id (str tenant-uuid)
                                      :principal_id (str (:id principal))
                                      :webhook_id (str webhook-id)})
        (access/in-transaction
         ds
         (fn [tx]
           (let [existing (access/authorize-manage!
                           principal (db.webhooks/lock-webhook tx tenant-uuid webhook-id))]
             (db.webhooks/delete-webhook! tx tenant-uuid webhook-id)
             (db.webhooks/remove-default! tx tenant-uuid webhook-id)
             (access/record-audit! tx principal "webhook.delete" "webhook" webhook-id
                                   {:name (:name existing) :availability (:availability existing)}))))
        (json-response 200 {:ok true}))
      (catch Exception e
        (failure-response e "Failed deleting webhook" 500)))))

(defn get-defaults-handler
  "Handler for GET /api/webhooks/defaults. Any active human may read."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [principal (access/require-human! req)
            tenant-uuid (:tenant-id principal)
            ds (require-ds! db)
            {:keys [webhook_ids]} (db.webhooks/get-defaults ds tenant-uuid)]
        (log/info "Reading webhook defaults" {:tenant_id (str tenant-uuid)
                                              :webhook_ids_count (count webhook_ids)})
        (json-response 200 {:ok true
                            :tenant_id (str tenant-uuid)
                            :webhook_ids (mapv str webhook_ids)}))
      (catch Exception e
        (failure-response e "Failed reading webhook defaults" 500)))))

(defn- assert-published-defaults!
  "Lock each requested webhook and require it to be a published tenant webhook.

  Throws :samuraibff.http/invalid-default otherwise."
  [tx tenant-uuid ids]
  (doseq [id ids]
    (let [row (db.webhooks/lock-webhook tx tenant-uuid id)]
      (when-not (= "published" (:availability row))
        (throw (ex-info "Invalid default" {:type :samuraibff.http/invalid-default}))))))

(defn set-defaults-handler
  "Handler for PUT /api/webhooks/defaults.

  Admin only (403 otherwise). Every id must be a published webhook of the
  tenant, else 400 `invalid-default`."
  [{:keys [db]}]
  (fn [req]
    (let [body (or (:body-params req) (:body req) {})]
      (try
        (let [principal (access/require-human! req)
              _ (when-not (policy/can-manage-defaults? principal) (access/forbidden!))
              tenant-uuid (:tenant-id principal)
              ds (require-ds! db)
              {:keys [webhook_ids]} (schemas/decode-and-validate! schemas/WebhookDefaultsRequest body)
              ids (->> webhook_ids (keep parse-uuid-or-nil) distinct vec)]
          (log/info "Setting webhook defaults" {:tenant_id (str tenant-uuid)
                                                :principal_id (str (:id principal))
                                                :webhook_ids_count (count ids)})
          (access/in-transaction
           ds
           (fn [tx]
             (assert-published-defaults! tx tenant-uuid ids)
             (db.webhooks/set-defaults! tx tenant-uuid ids)
             (access/record-audit! tx principal "webhook.defaults.set" "webhook_defaults" tenant-uuid
                                   {:webhook_ids (mapv str ids)})))
          (json-response 200 {:ok true}))
        (catch Exception e
          (failure-response e "Failed setting webhook defaults" 400))))))
