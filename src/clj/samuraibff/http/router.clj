(ns samuraibff.http.router
  "HTTP router component for the samuraibff application.

  This namespace provides an Integrant component that creates a Reitit router
  with all HTTP routes. It handles routing, middleware, and endpoint definitions.

  Component keys:
  - `:samuraibff/router` - The router component

  Dependencies:
  - None directly, but routes may depend on other components

  Example usage in system.edn:
  {:samuraibff/router
   {:config #ig/ref :samuraibff/config}}"
  (:require
   [integrant.core :as ig]
   [clojure.string :as str]
   [next.jdbc :as jdbc]
   [reitit.ring :as ring]
   [reitit.core]
   [samuraibff.ws.audio :as ws.audio]
   [samuraibff.ws.events :as ws.events]
   [samuraibff.http.auth :as http.auth]
   [samuraibff.http.recordings :as http.recordings]
   [samuraibff.http.api-credentials :as http.api-creds]
   [samuraibff.http.internal :as http.internal]
   [samuraibff.http.speakers :as http.speakers]
   [samuraibff.http.ui :as http.ui]
   [samuraibff.http.middleware.observability :as http.obs]
   [samuraibff.observability.metrics :as metrics]
   [samuraibff.schemas :as schemas]
   [reitit.ring.coercion :as rrc]
   [reitit.coercion.malli]
   [reitit.openapi :as openapi]
   [reitit.swagger-ui :as swagger-ui]
   [muuntaja.core :as mc]
   [malli.util :as mu]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.not-modified :refer [wrap-not-modified]]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.middleware.cookies :refer [wrap-cookies]]
   [ring.util.response :as resp]))

;; --- Schemas ---
;;
;; NOTE: customer-facing OpenAPI schemas live in `samuraibff.schemas`.

;; --- Routes ---

(defn- healthcheck-route []
  "Create the health check route definition."
  ["/health"
   {:get {:summary "Health check"
          :description "Returns health status of the application"
          :responses {200 {:body schemas/HealthCheckResponse}}
          :handler (fn [_]
                     {:status 200
                      :body {:status "ok"
                             :timestamp (java.util.Date.)
                             :version "0.1.0"}})}}])

(defn- db-up?
  "Best-effort DB connectivity check.

  Inputs:
  - deps: router deps map, expects optional :db {:ds DataSource}

  Returns:
  - boolean (true if DB is reachable)

  Notes:
  - We use a small timeout via next.jdbc options rather than blocking for a
    potentially long driver connect timeout.
  - This is meant for readiness checks, not for normal query execution." 
  [deps]
  (let [ds (get-in deps [:db :ds])]
    (if-not ds
      false
      (try
        (jdbc/execute-one! ds ["select 1 as ok"] {:timeout 2})
        true
        (catch Exception _
          false)))))

(defn- parse-host-port
  "Parse a host:port pair.

  Inputs:
  - s: string, expected in the form 'host:port'

  Returns:
  - {:host string :port int} or nil." 
  [s]
  (let [s0 (some-> s str str/trim not-empty)
        ;; Accept schemes commonly seen in config values:
        ;; - PLAINTEXT://host:port (Kafka)
        ;; - dns:///host:port (gRPC)
        s (when s0
            (-> s0
                (str/replace #"^[a-zA-Z][a-zA-Z0-9+.-]*:///" "")
                (str/replace #"^[a-zA-Z][a-zA-Z0-9+.-]*://" "")
                (str/replace #"/+$" "")))]
    (when (and s (str/includes? s ":"))
      (let [[host port-str] (str/split s #":" 2)
            host (str/trim host)
            port-str (str/trim port-str)]
        (when (and (not (str/blank? host)) (not (str/blank? port-str)))
          (try
            {:host host :port (Integer/parseInt port-str)}
            (catch Exception _
              nil)))))))

(defn- tcp-up?
  "Best-effort TCP reachability check.

  Inputs:
  - host string
  - port int
  - timeout-ms int

  Returns:
  - boolean" 
  [host port timeout-ms]
  (try
    (with-open [sock (java.net.Socket.)]
      (.connect sock (java.net.InetSocketAddress. ^String host (int port)) (int timeout-ms))
      true)
    (catch Exception _
      false)))

(defn- kafka-up?
  "Best-effort Kafka reachability check.

  We treat Kafka as reachable when we can establish a TCP connection to at
  least one bootstrap server.

  Inputs:
  - deps: router deps map, expects :config

  Returns:
  - boolean" 
  [deps]
  (let [bootstrap (some-> (get-in deps [:config :kafka :bootstrap-servers]) str)]
    (if (str/blank? bootstrap)
      true
      (let [servers (->> (str/split bootstrap #",")
                         (map str/trim)
                         (remove str/blank?)
                         (keep parse-host-port))]
        (boolean
          (some (fn [{:keys [host port]}]
                  (tcp-up? host port 500))
                servers))))))

(defn- grpc-up?
  "Best-effort rtservice (gRPC) reachability check.

  We treat rtservice as reachable when we can establish a TCP connection to
  the configured `[:grpc :rtservice-addr]` host:port.

  Inputs:
  - deps: router deps map, expects :config

  Returns:
  - boolean" 
  [deps]
  (let [addr (some-> (get-in deps [:config :grpc :rtservice-addr]) str str/trim)]
    (if (str/blank? addr)
      true
      (if-let [{:keys [host port]} (parse-host-port addr)]
        (tcp-up? host port 500)
        false))))

(defn- readiness-route
  "Create readiness route definition.

  Readiness returns 200 only when critical dependencies are available.
  Currently we check:
  - Postgres
  - Kafka (TCP reachability to bootstrap)
  - rtservice (gRPC) (TCP reachability)

  Returns a Reitit route vector." 
  [deps]
  ["/ready"
   {:get {:summary "Readiness check"
          :description "Returns readiness status (dependency checks)."
          :responses {200 {:body schemas/ReadinessResponse}
                      503 {:body schemas/ReadinessResponse}}
          :handler (fn [_]
                     (let [db-ok? (db-up? deps)
                           kafka-ok? (kafka-up? deps)
                           grpc-ok? (grpc-up? deps)
                           ok? (and db-ok? kafka-ok? grpc-ok?)
                           status (if ok? 200 503)
                           body {:status (if ok? "ok" "degraded")
                                 :timestamp (java.util.Date.)
                                 :version "0.1.0"
                                 :db {:up? (boolean db-ok?)}
                                 :kafka {:up? (boolean kafka-ok?)}
                                 :grpc {:up? (boolean grpc-ok?)}}]
                       {:status status
                        :body body}))}}])

;; --- Router ---

(defn create-router
  "Create and return a Reitit router with all routes.

  deps - map with keys:
  - :config      global config
  - :db          db component (HikariCP pool)
  - :grpc        gRPC client component
  - :ws-registry ws registry component

  Returns a Ring handler function." 
  [deps]
  (let [config (:config deps)
        deps* deps
        wrap-authenticate (fn [handler]
                            (http.auth/wrap-authenticate handler config))
        wrap-require-auth (fn [handler]
                            (http.auth/wrap-require-auth handler config))
        customer-openapi-id ::customer
        docs-handler
        (swagger-ui/create-swagger-ui-handler
          {:path "/docs"
           :url "/openapi.json"
           :config {:validatorUrl nil}})
        router
        (ring/router
          [["/" {:get {:handler http.ui/index-handler}}]
           ["/recordings" {:get {:handler http.ui/index-handler}}]
           ["/recordings/:session_id" {:get {:handler http.ui/index-handler}}]
           ["/live" {:get {:handler http.ui/index-handler}}]
           ["/api-credentials" {:get {:handler http.ui/index-handler}}]

           ;; --- OpenAPI + Swagger UI ---
           ["/openapi" {:tags ["openapi"]}
            [".json"
             {:get {:summary "OpenAPI specification"
                    :no-doc  true
                    :openapi {:id         customer-openapi-id
                              :info       {:title       "nanosamur.ai API"
                                           :version     "0.1.0"
                                           :description (str
                                                         "Customer-facing REST API for nanosamur.ai. "
                                                         "The nanosamur.ai API is secured using OAuth 2.0 / OpenID Connect (OIDC).\n\n
                                                         Authentication modes\n\n
                                                         1) Browser-based applications (interactive sign-in)\n
                                                         - Use the OIDC login flow via the `/auth/*` endpoints.\n
                                                         - After a successful sign-in, nanosamur.ai stores the access token in a secure, HttpOnly cookie.\n
                                                         - Subsequent API calls from the browser are authenticated automatically via this cookie.\n\n
                                                         2) Server-to-server / CLI clients (programmatic access)\n
                                                         - Obtain an access token from your identity provider.\n
                                                         - Send the token with each request using the `Authorization` header:\n
                                                         `Authorization: Bearer <access_token>`\n\n
                                                         All endpoints under `/api/*` require authentication.")}
                              :components {:securitySchemes
                                           {:bearerAuth {:type         "http"
                                                         :scheme       "bearer"
                                                         :bearerFormat "JWT"}}}}
                    :handler (openapi/create-openapi-handler)}}]]

           ;; Swagger UI serves the index HTML at /docs(/) and static assets under
           ;; /docs/* (swagger-ui.css, swagger-ui-bundle.js, ...).
           ["/docs"
            {:get {:summary "Swagger UI"
                   :no-doc true
                   :handler docs-handler}}]
           ["/docs/"
            {:get {:summary "Swagger UI (trailing slash)"
                   :no-doc true
                   :handler docs-handler}}]
           ["/docs/*path"
            {:get {:summary "Swagger UI assets"
                   :no-doc true
                   :handler docs-handler}}]

           ;; Auth endpoints (browser login flow)
           ["/auth" {:tags ["auth"]
                     :openapi {:id customer-openapi-id}}
            ["/login" {:get {:summary "Start OIDC login"
                             :description "Redirects the user agent to the identity provider for authentication."
                             :parameters {:query [:map
                                                  [:next {:optional true} :string]]}
                             :responses {302 {:description "Redirect to identity provider"}
                                         400 {:body schemas/ApiErrorResponse}}
                             :handler (http.auth/login-handler config)}}]
            ["/callback" {:get {:summary "OIDC callback"
                                :description "Completes the OIDC authorization code flow and sets the access token cookie."
                                :parameters {:query [:map
                                                     [:code {:optional true} :string]
                                                     [:state {:optional true} :string]
                                                     [:error {:optional true} :string]
                                                     [:error_description {:optional true} :string]]}
                                :responses {302 {:description "Redirect to the post-login URL"}
                                            400 {:body schemas/ApiErrorResponse}}
                                :handler (http.auth/callback-handler config)}}]
            ["/logout" {:post {:summary "Logout"
                               :description "Clears the access token cookie."
                               :responses {204 {:description "No Content"}}
                               :handler (http.auth/logout-handler config)}}]]

           ;; API endpoints (all tenant-scoped; auth enforced by wrap-require-auth)
           ["/api" {:tags ["api"]
                    :openapi {:id customer-openapi-id}
                    :security [{:bearerAuth []}]
                    :middleware [wrap-require-auth]}
            ["/me" {:get {:summary "Current user"
                          :description "Returns details about the current authenticated principal."
                          :responses {200 {:body schemas/ApiMeResponse}
                                      401 {:body schemas/ApiErrorResponse}
                                      403 {:body schemas/ApiErrorResponse}}
                          :handler (http.auth/me-handler config)}}]

            ["/recordings" {:get {:summary "List recordings"
                                  :description "Returns the tenant-scoped list of recording sessions."
                                  :parameters {:query [:map
                                                       [:limit {:optional true} :int]
                                                       [:offset {:optional true} :int]]}
                                  :responses {200 {:body schemas/RecordingsListResponse}
                                              400 {:body schemas/ApiErrorResponse}
                                              403 {:body schemas/ApiErrorResponse}
                                              503 {:body schemas/ApiErrorResponse}}
                                  :handler (http.recordings/list-recordings-handler deps)}}]
            ["/recordings/:session_id"
             {:parameters {:path [:map
                                  [:session_id :string]]}}

             ;; NOTE: Reitit route nodes that have children should not define
             ;; method handlers directly at the same node. Put them under an
             ;; empty-path child instead.
             [""
              {:get {:summary "Get recording detail"
                     :description "Returns recording metadata and transcript records for the given session id."
                     :responses {200 {:body schemas/RecordingDetailResponse}
                                 400 {:body schemas/ApiErrorResponse}
                                 403 {:body schemas/ApiErrorResponse}
                                 404 {:body schemas/ApiErrorResponse}
                                 503 {:body schemas/ApiErrorResponse}}
                     :handler (http.recordings/get-recording-handler deps)}
               :delete {:summary "Delete recording"
                        :description "Deletes the recording session and related data for the current tenant."
                        :responses {200 {:body schemas/DeleteRecordingResponse}
                                    400 {:body schemas/ApiErrorResponse}
                                    403 {:body schemas/ApiErrorResponse}
                                    404 {:body schemas/ApiErrorResponse}
                                    503 {:body schemas/ApiErrorResponse}}
                        :handler (http.recordings/delete-recording-handler deps)}}]

             ;; Audio playback (binary stream). OpenAPI support for binary is
             ;; limited; we document responses but do not provide a Malli body.
             ["/audio"
              {:get {:summary "Get recording audio"
                     :description "Streams the session recording audio (supports Range requests)."
                     :responses {200 {:description "Audio stream"}
                                 206 {:description "Partial audio stream (Range)"}
                                 400 {:body schemas/ApiErrorResponse}
                                 403 {:body schemas/ApiErrorResponse}
                                 404 {:body schemas/ApiErrorResponse}
                                 416 {:body schemas/ApiErrorResponse}
                                 503 {:body schemas/ApiErrorResponse}}
                     :handler (http.recordings/get-recording-audio-handler deps)}}]]
            ["/sessions" {:post {:summary "Create session"
                                 :description "Creates a new session identifier for WebSocket streaming."
                                 :parameters {:body schemas/CreateSessionRequest}
                                 :responses {200 {:body schemas/CreateSessionResponse}
                                             403 {:body schemas/ApiErrorResponse}
                                             500 {:body schemas/ApiErrorResponse}}
                                 :handler (http.ui/create-session-handler deps)}}]

            ["/sessions/:session_id"
             {:patch {:summary "Rename session"
                      :description "Updates the session title."
                      :parameters {:path [:map [:session_id :string]]
                                   :body schemas/UpdateSessionTitleRequest}
                      :responses {200 {:body schemas/UpdateSessionTitleResponse}
                                  400 {:body schemas/ApiErrorResponse}
                                  403 {:body schemas/ApiErrorResponse}
                                  404 {:body schemas/ApiErrorResponse}
                                  503 {:body schemas/ApiErrorResponse}
                                  500 {:body schemas/ApiErrorResponse}}
                      :handler (http.ui/rename-session-handler deps)}}]

            ["/speakers"
             {:get {:summary "List enrolled speakers"
                    :description "Lists the current tenant's enrolled speakers."
                    :responses {200 {:body schemas/SpeakersListResponse}
                                403 {:body schemas/ApiErrorResponse}}
                    :handler (http.speakers/list-speakers-handler deps)}
              :post {:summary "Create enrolled speaker"
                     :description (str
                                    "Creates a new enrolled speaker by uploading a single WAV sample. "
                                    "Request must be multipart/form-data with fields: label (string), sample (file).")
                     ;; OpenAPI multipart file schemas vary by generator; we document
                     ;; it textually and still keep response schemas formal.
                     :responses {200 {:body schemas/CreateSpeakerResponse}
                                 400 {:body schemas/ApiErrorResponse}
                                 403 {:body schemas/ApiErrorResponse}
                                 500 {:body schemas/ApiErrorResponse}}
                     :middleware [wrap-multipart-params]
                     :handler (http.speakers/create-speaker-handler deps)}}]
            ["/speakers/:speaker_id"
             {:delete {:summary "Delete enrolled speaker"
                       :description "Deletes an enrolled speaker and all associated stored data for the current tenant."
                       :parameters {:path [:map [:speaker_id :string]]}
                       :responses {200 {:body schemas/DeleteSpeakerResponse}
                                   400 {:body schemas/ApiErrorResponse}
                                   403 {:body schemas/ApiErrorResponse}
                                   404 {:body schemas/ApiErrorResponse}
                                   500 {:body schemas/ApiErrorResponse}}
                       :handler (http.speakers/delete-speaker-handler deps)}}]

            ;; M2M credential management (human UX; secrets returned once)
            ["/api-credentials"
             {:get {:summary "List API credentials"
                    :description "Lists the current tenant's machine-to-machine API credentials."
                    :responses {200 {:body schemas/ApiCredentialsListResponse}
                                403 {:body schemas/ApiErrorResponse}
                                503 {:body schemas/ApiErrorResponse}}
                    :handler (http.api-creds/list-api-credentials-handler deps)}
              :post {:summary "Create API credential"
                     :description "Creates a new API credential and returns the client secret once."
                     :parameters {:body schemas/CreateApiCredentialRequest}
                     :responses {200 {:body schemas/CreateApiCredentialResponse}
                                 400 {:body schemas/ApiErrorResponse}
                                 403 {:body schemas/ApiErrorResponse}
                                 503 {:body schemas/ApiErrorResponse}
                                 502 {:body schemas/ApiErrorResponse}}
                     :handler (http.api-creds/create-api-credential-handler deps)}}]

            ["/api-credentials/:id/rotate"
             {:post {:summary "Rotate API credential secret"
                    :description "Rotates the client secret and returns the new secret once."
                    :parameters {:path [:map [:id :string]]}
                    :responses {200 {:body schemas/RotateApiCredentialResponse}
                                400 {:body schemas/ApiErrorResponse}
                                403 {:body schemas/ApiErrorResponse}
                                404 {:body schemas/ApiErrorResponse}
                                503 {:body schemas/ApiErrorResponse}}
                    :handler (http.api-creds/rotate-api-credential-handler deps)}}]
            ["/api-credentials/:id"
             {:delete {:summary "Revoke API credential"
                       :description "Revokes the API credential for the current tenant."
                       :parameters {:path [:map [:id :string]]}
                       :responses {200 {:body schemas/ApiOkResponse}
                                   400 {:body schemas/ApiErrorResponse}
                                   403 {:body schemas/ApiErrorResponse}
                                   404 {:body schemas/ApiErrorResponse}}
                       :handler (http.api-creds/revoke-api-credential-handler deps)}}]]

           ;; Health check endpoint
           (healthcheck-route)

           ;; Readiness (dependency status)
           (readiness-route deps)

           ;; Internal callbacks (between BFF instances)
           ["/internal" {:tags ["internal"]
                         :no-doc true}
            ["/metrics" {:get {:summary "Prometheus metrics"
                               :description "Prometheus scrape endpoint (in-cluster only)."
                               :handler metrics/metrics-handler}}]
            ["/refined" {:post {:summary "BFF-to-BFF refined callback (protobuf)"
                                :handler (http.internal/refined-callback-handler deps)}}]]

           ;; WebSockets
           ["/ws" {:tags ["ws"]
                   ;; OpenAPI doesn't model WS. Keep these out of generated docs.
                   :no-doc true}
            ["/audio" {:get {:handler (ws.audio/handler deps)}}]
            ["/events" {:get {:handler (ws.events/handler deps)}}]]]

          {:data {:muuntaja mc/instance
                  :coercion reitit.coercion.malli/coercion
                  :malli/options {:error-keys #(mu/keys schemas/HealthCheckResponse)}
                  :swagger {:id ::api}
                  :middleware [parameters/parameters-middleware ; decoding query & form params
                               wrap-cookies
                               (fn [handler]
                                 (fn [req]
                                   (handler (assoc req :samuraibff/deps deps*))))
                               wrap-authenticate
                               http.obs/wrap-observability
                               openapi/openapi-feature
                               muuntaja/format-middleware       ; content negotiation
                               exception/exception-middleware   ; converting exceptions to HTTP responses
                               rrc/coerce-request-middleware
                               rrc/coerce-response-middleware]}})

        ;; Static classpath assets under resources/public.
        ;;
        ;; NOTE: We intentionally do NOT use `reitit.ring/create-resource-handler`
        ;; here because it redirects `/js/main.js` -> `/js/main.js/` (302) and the
        ;; redirected URL ends up serving index.html. That breaks JS loading in the
        ;; browser ("expected expression, got '<'").
        wrap-no-cache
        (fn [handler]
          (fn [req]
            (let [resp (handler req)
                  uri (:uri req)]
              (if (and resp (string? uri) (re-find #"\\.(?:html|js)$" uri))
                (-> resp
                    (resp/header "Cache-Control" "no-store, max-age=0")
                    (resp/header "Pragma" "no-cache"))
                resp))))

        ;; NOTE: We do NOT use wrap-not-modified here.
        ;; We have seen cases where the browser ends up with a stale cached
        ;; `/js/main.js` while `index.html` changed (or vice versa), which makes
        ;; CSS selectors not match the rendered DOM ("unstyled" transcript).
        static-handler (-> (fn [_] nil)
                           (wrap-resource "public")
                           (wrap-content-type)
                           (wrap-no-cache))]
    (ring/ring-handler
      router
      (ring/routes
        static-handler
        (ring/create-default-handler)))))

;; --- Integrant Component ---

(defmethod ig/init-key :samuraibff/router
  [_ deps]
  "Integrant init method for the router component.

  Creates and returns the router handler.

  Returns a Ring handler function." 
  (create-router deps))

(defmethod ig/halt-key! :samuraibff/router [_ _]
  "Integrant halt method for the router component.

  No cleanup needed for the router."
  nil)

;; (create-router deps) returns a Ring handler function; no additional
;; lifecycle cleanup required.
