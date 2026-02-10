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
   [next.jdbc :as jdbc]
   [reitit.ring :as ring]
   [reitit.core]
   [samuraibff.ws.audio :as ws.audio]
   [samuraibff.ws.events :as ws.events]
   [samuraibff.http.auth :as http.auth]
   [samuraibff.http.internal :as http.internal]
   [samuraibff.http.ui :as http.ui]
   [reitit.ring.coercion :as rrc]
   [reitit.coercion.malli]
   [reitit.swagger :as swagger]
   [reitit.swagger-ui :as swagger-ui]
   [muuntaja.core :as mc]
   [malli.util :as mu]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.not-modified :refer [wrap-not-modified]]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.middleware.cookies :refer [wrap-cookies]]
   [ring.util.response :as resp]))

;; --- Schemas ---

(def HealthCheckResponse
  "Schema for health check response"
  [:map
   [:status [:enum "ok"]]
   [:timestamp inst?]
   [:version string?]])

(def ReadinessResponse
  "Schema for readiness response.

  Readiness is intended for load balancers / Kubernetes readiness probes.
  Unlike liveness (/health), readiness may return a non-200 when a required
  dependency (e.g. Postgres) is unavailable." 
  [:map
   [:status [:enum "ok" "degraded"]]
   [:timestamp inst?]
   [:version string?]
   [:db [:map
         [:up? boolean?]]]])

;; --- Routes ---

(defn- healthcheck-route []
  "Create the health check route definition."
  ["/health"
   {:get {:summary "Health check"
          :description "Returns health status of the application"
          :responses {200 {:body HealthCheckResponse}}
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

(defn- readiness-route
  "Create readiness route definition.

  Readiness returns 200 only when critical dependencies are available.
  Currently we check Postgres only.

  Returns a Reitit route vector." 
  [deps]
  ["/ready"
   {:get {:summary "Readiness check"
          :description "Returns readiness status (dependency checks)."
          :responses {200 {:body ReadinessResponse}
                      503 {:body ReadinessResponse}}
          :handler (fn [_]
                     (let [db-ok? (db-up? deps)
                           status (if db-ok? 200 503)
                           body {:status (if db-ok? "ok" "degraded")
                                 :timestamp (java.util.Date.)
                                 :version "0.1.0"
                                 :db {:up? (boolean db-ok?)}}]
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
        wrap-authenticate (fn [handler]
                            (http.auth/wrap-authenticate handler config))
        wrap-require-auth (fn [handler]
                            (http.auth/wrap-require-auth handler config))
        router
        (ring/router
          [["/" {:get {:handler http.ui/index-handler}}]
           ["/recordings" {:get {:handler http.ui/index-handler}}]
           ["/recordings/:session_id" {:get {:handler http.ui/index-handler}}]
           ["/live" {:get {:handler http.ui/index-handler}}]

           ;; Auth endpoints (browser login flow)
           ["/auth" {:tags ["auth"]}
            ["/login" {:get {:summary "Start OIDC login (redirect to Keycloak)"
                             :handler (http.auth/login-handler config)}}]
            ["/callback" {:get {:summary "OIDC callback endpoint (code -> token)"
                                :handler (http.auth/callback-handler config)}}]
            ["/logout" {:post {:summary "Logout (clear auth cookie)"
                               :handler (http.auth/logout-handler config)}}]]

           ;; Small UI helpers
           ["/api" {:tags ["api"]
                    :middleware [wrap-require-auth]}
            ["/me" {:get {:summary "Current authenticated user"
                          :handler (http.auth/me-handler config)}}]
            ["/sessions" {:post {:summary "Create a new session id"
                                 :handler (http.ui/create-session-handler deps)}}]]

           ;; Health check endpoint
           (healthcheck-route)

           ;; Readiness (dependency status)
           (readiness-route deps)

           ;; Internal callbacks (between BFF instances)
           ["/internal" {:tags ["internal"]}
            ["/refined" {:post {:summary "BFF-to-BFF refined callback (protobuf)"
                                :handler (http.internal/refined-callback-handler deps)}}]]

           ;; WebSockets
           ["/ws" {:tags ["ws"]}
            ["/audio" {:get {:handler (ws.audio/handler deps)}}]
            ["/events" {:get {:handler (ws.events/handler deps)}}]]]

          {:data {:muuntaja mc/instance
                  :coercion reitit.coercion.malli/coercion
                  :malli/options {:error-keys #(mu/keys HealthCheckResponse)}
                  :swagger {:id ::api}
                  :middleware [parameters/parameters-middleware ; decoding query & form params
                               wrap-cookies
                               wrap-authenticate
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
