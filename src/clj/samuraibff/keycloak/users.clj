(ns samuraibff.keycloak.users
  "Restricted Keycloak adapter for administrator-led onboarding.

  This adapter is separate from the machine-credential adapter
  (`samuraibff.keycloak.admin`) and uses its own, narrowly scoped service
  client. It exposes only fixed operations on one realm:
  - create a user with an Admin-controlled `tenant_id` attribute,
  - send the time-limited setup email (verify email + set password),
  - read a user to verify its tenant binding,
  - disable or enable a user and end its Keycloak sessions.

  It never proxies arbitrary Admin REST paths, never assigns roles and never
  accepts or emails passwords. Errors carry fixed types without upstream
  payloads.

  Config under [:keycloak :user-admin]:
  {:enabled? boolean :issuer string :realm string :client-id string
   :client-secret string :setup-client-id string :setup-redirect-uri string
   :setup-lifespan-s int}

  Public API:
  - `configured?`, `create-user!`, `find-user`, `send-setup-email!`, `set-enabled!`"
  (:require
   [clojure.string :as str]
   [jsonista.core :as json]
   [org.corfield.logging4j2 :as log]
   [samuraibff.observability.diagnostics :as diagnostics]
   [ring.util.codec :as codec])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)
   (java.nio.charset StandardCharsets)
   (java.time Duration)))

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn keyword :encode-key-fn name}))

(def ^:private http-client
  (-> (HttpClient/newBuilder)
      (.followRedirects HttpClient$Redirect/NEVER)
      (.connectTimeout (Duration/ofSeconds 7))
      (.build)))

(def setup-actions
  "Required actions sent in the setup email."
  ["VERIFY_EMAIL" "UPDATE_PASSWORD"])

(defn configured?
  "True when self-service provisioning is enabled and fully configured."
  [config]
  (let [{:keys [enabled? issuer realm client-id client-secret]} (get-in config [:keycloak :user-admin])]
    (boolean (and enabled? (every? #(and (string? %) (not (str/blank? %)))
                                   [issuer realm client-id client-secret])))))

(defn- failure
  [type & [status]]
  (ex-info "Keycloak user administration failed"
           (cond-> {:type type} status (assoc :status status))))

(defn- send!
  "Send one request; returns {:status int :body string :location string?}."
  [{:keys [method url token body form]}]
  (try
    (let [builder (-> (HttpRequest/newBuilder)
                      (.uri (URI/create url))
                      (.timeout (Duration/ofSeconds 10)))
          builder (cond-> builder
                    token (.header "Authorization" (str "Bearer " token))
                    body (.header "Content-Type" "application/json")
                    form (.header "Content-Type" "application/x-www-form-urlencoded"))
          payload (cond
                    form (HttpRequest$BodyPublishers/ofString (codec/form-encode form) StandardCharsets/UTF_8)
                    body (HttpRequest$BodyPublishers/ofString (json/write-value-as-string body json-mapper)
                                                              StandardCharsets/UTF_8)
                    :else (HttpRequest$BodyPublishers/noBody))
          builder (case method
                    :get (.GET builder)
                    :post (.POST builder payload)
                    :put (.PUT builder payload))
          ^HttpResponse resp (.send http-client (.build builder) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp)
       :body (.body resp)
       :location (.orElse (.firstValue (.headers resp) "Location") nil)})
    (catch InterruptedException e
      (.interrupt (Thread/currentThread))
      (log/warn "Keycloak user request interrupted" (diagnostics/failure-details e))
      (throw (failure ::request-failed)))
    (catch Exception e
      (log/warn "Keycloak user request failed" (diagnostics/failure-details e))
      (throw (failure ::request-failed)))))

(defn- admin-base
  [{:keys [issuer realm]}]
  (str (str/replace (str/replace issuer #"/+$" "") #"/realms/[^/]+$" "")
       "/admin/realms/" (codec/url-encode realm)))

(defn- admin-token!
  [{:keys [issuer client-id client-secret]}]
  (let [{:keys [status body]} (send! {:method :post
                                      :url (str (str/replace issuer #"/+$" "") "/protocol/openid-connect/token")
                                      :form {:grant_type "client_credentials"
                                             :client_id client-id
                                             :client_secret client-secret}})]
    (when-not (<= 200 status 299)
      (throw (failure ::token-failed status)))
    (or (try (:access_token (json/read-value body json-mapper)) (catch Exception _ nil))
        (throw (failure ::token-failed status)))))

(defn- settings
  [config]
  (when-not (configured? config)
    (throw (failure ::not-configured)))
  (get-in config [:keycloak :user-admin]))

(defn- user-id-from-location
  [location]
  (some->> location (re-find #"/users/([0-9a-fA-F-]{36})$") second))

(defn create-user!
  "Create a disabled-for-login-until-setup Keycloak user bound to tenant-id.

  Inputs: config, {:email string :name string? :tenant-id string}.
  Returns {:user-id string}. Throws {:type ::conflict} when an account with
  that username/email already exists (never adopted by email), or other
  fixed failure types."
  [config {:keys [email name tenant-id]}]
  (let [cfg (settings config)
        token (admin-token! cfg)
        [first-name & more] (some-> name str/trim not-empty (str/split #"\s+" 2))
        {:keys [status location]} (send! {:method :post
                                          :url (str (admin-base cfg) "/users")
                                          :token token
                                          :body (cond-> {:username (str/lower-case email)
                                                         :email (str/lower-case email)
                                                         :enabled true
                                                         :emailVerified false
                                                         :requiredActions setup-actions
                                                         :attributes {:tenant_id [(str tenant-id)]}}
                                                  first-name (assoc :firstName first-name)
                                                  (seq more) (assoc :lastName (first more)))})]
    (cond
      (= 409 status) (throw (failure ::conflict status))
      (not= 201 status) (throw (failure ::create-failed status))
      :else (or (some->> (user-id-from-location location) (hash-map :user-id))
                (throw (failure ::create-failed status))))))

(defn find-user
  "Read a Keycloak user by exact id.

  Returns {:user-id :tenant-id :enabled? :email-verified?} or nil when absent."
  [config user-id]
  (let [cfg (settings config)
        token (admin-token! cfg)
        {:keys [status body]} (send! {:method :get
                                      :url (str (admin-base cfg) "/users/" (codec/url-encode user-id))
                                      :token token})]
    (cond
      (= 404 status) nil
      (not (<= 200 status 299)) (throw (failure ::read-failed status))
      :else (let [user (json/read-value body json-mapper)]
              {:user-id (:id user)
               :tenant-id (first (get-in user [:attributes :tenant_id]))
               :enabled? (true? (:enabled user))
               :email-verified? (true? (:emailVerified user))}))))

(defn send-setup-email!
  "Ask Keycloak to email the time-limited setup link for a user.

  Requires realm SMTP. Returns nil; throws {:type ::email-failed} otherwise."
  [config user-id]
  (let [{:keys [setup-client-id setup-redirect-uri setup-lifespan-s] :as cfg} (settings config)
        token (admin-token! cfg)
        query (codec/form-encode (cond-> {:lifespan (long (or setup-lifespan-s 86400))}
                                   setup-client-id (assoc :client_id setup-client-id)
                                   setup-redirect-uri (assoc :redirect_uri setup-redirect-uri)))
        {:keys [status]} (send! {:method :put
                                 :url (str (admin-base cfg) "/users/" (codec/url-encode user-id)
                                           "/execute-actions-email?" query)
                                 :token token
                                 :body setup-actions})]
    (when-not (<= 200 status 299)
      (throw (failure ::email-failed status)))
    nil))

(defn set-enabled!
  "Enable or disable a Keycloak user. Disabling also ends its Keycloak sessions.

  Returns nil; throws a fixed failure type when Keycloak rejects the change."
  [config user-id enabled?]
  (let [cfg (settings config)
        token (admin-token! cfg)
        base (str (admin-base cfg) "/users/" (codec/url-encode user-id))
        current (send! {:method :get :url base :token token})
        _ (when-not (<= 200 (:status current) 299)
            (throw (failure ::read-failed (:status current))))
        representation (-> (json/read-value (:body current) json-mapper)
                           (assoc :enabled (boolean enabled?)))
        {:keys [status]} (send! {:method :put :url base :token token :body representation})]
    (when-not (<= 200 status 299)
      (throw (failure ::update-failed status)))
    (when-not enabled?
      (let [{:keys [status]} (send! {:method :post :url (str base "/logout") :token token})]
        (when-not (<= 200 status 299)
          (throw (failure ::logout-failed status)))))
    nil))
