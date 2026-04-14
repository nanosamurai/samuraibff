(ns samuraibff.http.webhooks
  "HTTP handlers for tenant-scoped webhook configuration.

  Endpoints (under /api, auth required):
  - GET    /api/webhooks
  - POST   /api/webhooks
  - PUT    /api/webhooks/:id
  - DELETE /api/webhooks/:id
  - GET    /api/webhooks/defaults
  - PUT    /api/webhooks/defaults

  Secret handling:
  - secret values are accepted only as inputs
  - secrets are stored via `:samuraibff/secrets` component
  - API responses never echo secret values (only refs)
  "
  (:require
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [samuraibff.db.webhooks :as db.webhooks]
   [samuraibff.schemas :as schemas]
   [samuraibff.secrets.core :as secrets.core]
   [samuraibff.util.uuid :as util.uuid])
  (:import
   (java.util UUID)))

(defn- json-response
  [status body]
  {:status status
   :body body})

(defn- require-tenant-uuid!
  [req]
  (let [tid (some-> (:auth/tenant-id req) str str/trim)]
    (cond
      (str/blank? tid)
      (throw (ex-info "Missing tenant id" {:type :samuraibff.http/missing-tenant-id}))

      :else
      (try
        (UUID/fromString tid)
        (catch Exception _
          (throw (ex-info "Invalid tenant id" {:type :samuraibff.http/invalid-tenant-id
                                               :tenant-id tid})))))))

(defn- parse-uuid-or-nil
  [s]
  (try
    (UUID/fromString (str s))
    (catch Exception _
      nil)))

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

(defn list-webhooks-handler
  "Handler for GET /api/webhooks."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [tenant-uuid (require-tenant-uuid! req)
            ds (:ds db)]
        (log/info "Listing webhooks" {:tenant_id (str tenant-uuid)})
        (when-not ds
          (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
        (let [items (db.webhooks/list-webhooks ds tenant-uuid)
              items' (mapv (fn [w]
                             (let [subs (db.webhooks/list-subscriptions ds tenant-uuid (:id w))]
                               ;; never expose secret values (we store refs only anyway)
                               (-> w
                                   (assoc :subscriptions (vec subs))
                                   (update :id str)
                                   (update :tenant_id str)
                                   (update :created_at str))))
                           items)]
          (json-response 200 {:ok true
                              :tenant_id (str tenant-uuid)
                              :items items'})))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type]} (ex-data e)]
          (case type
            :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
            :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
            :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
            (do
              (log/error e "Failed listing webhooks")
              (json-response 500 {:ok false :message "internal-error"})))))
      (catch Exception e
        (log/error e "Unexpected error listing webhooks")
        (json-response 500 {:ok false :message "internal-error"})))))

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
  "Handler for POST /api/webhooks."
  [{:keys [db secrets]}]
  (fn [req]
    (let [body (or (:body-params req) (:body req) {})]
      (try
        (let [tenant-uuid (require-tenant-uuid! req)
              tenant-id-str (str tenant-uuid)
              ds (:ds db)
              _ (when-not ds
                  (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
              {:keys [name url enabled auth subscriptions static_headers
                      hmac_secret api_key oauth_client_secret]} (schemas/decode-and-validate! schemas/CreateWebhookRequest body)

              name (str/trim (str name))
              url (str/trim (str url))
              auth-type (get auth :type)
              webhook-id (util.uuid/uuid7)
              store (:store secrets)

              hmac-ref (store-secret-if-present! store tenant-id-str (str "webhook-hmac/" name) hmac_secret)
              api-key-ref (store-secret-if-present! store tenant-id-str (str "webhook-api-key/" name) api_key)
              oauth-client-secret-ref (store-secret-if-present! store tenant-id-str (str "webhook-oauth/" name) oauth_client_secret)

              row {:id webhook-id
                   :tenant-id tenant-uuid
                   :name name
                   :url url
                   :enabled (boolean enabled)
                   :auth-type (clojure.core/name auth-type)

                   :hmac_secret_ref hmac-ref
                   :api_key_ref api-key-ref
                   :oauth_client_secret_ref oauth-client-secret-ref

                   :oauth_token_url (get auth :token_url)
                   :oauth_client_id (get auth :client_id)
                   :oauth_scopes (get auth :scopes)
                   :api_key_header_name (get auth :header_name)
                   :api_key_prefix (get auth :prefix)
                   :static_headers (normalize-static-headers static_headers)}]
          (log/info "Creating webhook" {:tenant_id (str tenant-uuid)
                                        :webhook_id (str webhook-id)
                                        :name name
                                        :url url
                                        :auth_type (clojure.core/name auth-type)
                                        :enabled (boolean enabled)
                                        :subscriptions_count (count subscriptions)
                                        :static_headers_count (count (or static_headers {}))})
          (db.webhooks/insert-webhook! ds row)
          (db.webhooks/replace-subscriptions! ds tenant-uuid webhook-id subscriptions)
          (json-response 200 {:ok true
                              :webhook_id (str webhook-id)}))

        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type errors]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              (do
                (log/error e "Failed creating webhook" {:errors errors})
                (json-response 400 {:ok false :message "invalid-request"})))))
        (catch Exception e
          (log/error e "Unexpected error creating webhook")
          (json-response 500 {:ok false :message "internal-error"}))))))

(defn update-webhook-handler
  "Handler for PUT /api/webhooks/:id."
  [{:keys [db secrets]}]
  (fn [req]
    (let [body (or (:body-params req) (:body req) {})
          id-str (or (get-in req [:path-params :id]) (get-in req [:path-params "id"]))]
      (try
        (let [tenant-uuid (require-tenant-uuid! req)
              tenant-id-str (str tenant-uuid)
              ds (:ds db)
              _ (when-not ds
                  (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
              webhook-id (or (parse-uuid-or-nil id-str)
                             (throw (ex-info "Invalid id" {:type :samuraibff.http/invalid-id})))
              {:keys [name url enabled auth subscriptions static_headers
                      hmac_secret api_key oauth_client_secret]} (schemas/decode-and-validate! schemas/UpdateWebhookRequest body)

              store (:store secrets)
              hmac-ref (store-secret-if-present! store tenant-id-str (str "webhook-hmac/" (or name webhook-id)) hmac_secret)
              api-key-ref (store-secret-if-present! store tenant-id-str (str "webhook-api-key/" (or name webhook-id)) api_key)
              oauth-client-secret-ref (store-secret-if-present! store tenant-id-str (str "webhook-oauth/" (or name webhook-id)) oauth_client_secret)

              patch (cond-> {}
                      (some? name) (assoc :name (str (str/trim (str name))))
                      (some? url) (assoc :url (str (str/trim (str url))))
                      (some? enabled) (assoc :enabled (boolean enabled))
                      (some? auth) (assoc :auth_type (name (get auth :type))
                                          :oauth_token_url (get auth :token_url)
                                          :oauth_client_id (get auth :client_id)
                                          :oauth_scopes (get auth :scopes)
                                          :api_key_header_name (get auth :header_name)
                                          :api_key_prefix (get auth :prefix))
                      (some? static_headers) (assoc :static_headers (normalize-static-headers static_headers))
                      (some? hmac-ref) (assoc :hmac_secret_ref hmac-ref)
                      (some? api-key-ref) (assoc :api_key_ref api-key-ref)
                      (some? oauth-client-secret-ref) (assoc :oauth_client_secret_ref oauth-client-secret-ref))
              existing (db.webhooks/find-webhook ds tenant-uuid webhook-id)]
          (if-not existing
            (json-response 404 {:ok false :message "not-found"})
            (do
              (when (seq patch)
                (db.webhooks/update-webhook! ds tenant-uuid webhook-id patch))
              (when (some? subscriptions)
                (db.webhooks/replace-subscriptions! ds tenant-uuid webhook-id subscriptions))
              (json-response 200 {:ok true}))))

        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              :samuraibff.http/invalid-id (json-response 400 {:ok false :message "invalid-id"})
              (do
                (log/error e "Failed updating webhook")
                (json-response 400 {:ok false :message "invalid-request"})))))
        (catch Exception e
          (log/error e "Unexpected error updating webhook")
          (json-response 500 {:ok false :message "internal-error"}))))))

(defn delete-webhook-handler
  "Handler for DELETE /api/webhooks/:id."
  [{:keys [db]}]
  (fn [req]
    (let [id-str (or (get-in req [:path-params :id]) (get-in req [:path-params "id"]))]
      (try
        (let [tenant-uuid (require-tenant-uuid! req)
              ds (:ds db)
              _ (when-not ds
                  (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
              webhook-id (or (parse-uuid-or-nil id-str)
                             (throw (ex-info "Invalid id" {:type :samuraibff.http/invalid-id})))
              _ (log/info "Deleting webhook" {:tenant_id (str tenant-uuid)
                                              :webhook_id (str webhook-id)})
              {:keys [deleted?]} (db.webhooks/delete-webhook! ds tenant-uuid webhook-id)]
          (if deleted?
            (json-response 200 {:ok true})
            (json-response 404 {:ok false :message "not-found"})))

        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              :samuraibff.http/invalid-id (json-response 400 {:ok false :message "invalid-id"})
              (do
                (log/error e "Failed deleting webhook")
                (json-response 500 {:ok false :message "internal-error"})))))
        (catch Exception e
          (log/error e "Unexpected error deleting webhook")
          (json-response 500 {:ok false :message "internal-error"}))))))

(defn get-defaults-handler
  "Handler for GET /api/webhooks/defaults."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [tenant-uuid (require-tenant-uuid! req)
            ds (:ds db)
            _ (when-not ds
                (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
            {:keys [webhook_ids]} (db.webhooks/get-defaults ds tenant-uuid)]
        (log/info "Reading webhook defaults" {:tenant_id (str tenant-uuid)
                                              :webhook_ids_count (count webhook_ids)})
        (json-response 200 {:ok true
                            :tenant_id (str tenant-uuid)
                            :webhook_ids (mapv str webhook_ids)}))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type]} (ex-data e)]
          (case type
            :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
            :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
            :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
            (do
              (log/error e "Failed reading webhook defaults")
              (json-response 500 {:ok false :message "internal-error"})))))
      (catch Exception e
        (log/error e "Unexpected error reading webhook defaults")
        (json-response 500 {:ok false :message "internal-error"})))))

(defn set-defaults-handler
  "Handler for PUT /api/webhooks/defaults."
  [{:keys [db]}]
  (fn [req]
    (let [body (or (:body-params req) (:body req) {})]
      (try
        (let [tenant-uuid (require-tenant-uuid! req)
              ds (:ds db)
              _ (when-not ds
                  (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
              {:keys [webhook_ids]} (schemas/decode-and-validate! schemas/WebhookDefaultsRequest body)
              ids (->> webhook_ids
                       (keep parse-uuid-or-nil)
                       vec)]
          (log/info "Setting webhook defaults" {:tenant_id (str tenant-uuid)
                                                :webhook_ids_count (count ids)})
          (db.webhooks/set-defaults! ds tenant-uuid ids)
          (json-response 200 {:ok true}))

        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              (do
                (log/error e "Failed setting webhook defaults")
                (json-response 400 {:ok false :message "invalid-request"})))))
        (catch Exception e
          (log/error e "Unexpected error setting webhook defaults")
          (json-response 500 {:ok false :message "internal-error"}))))))
