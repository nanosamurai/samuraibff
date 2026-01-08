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
   [reitit.ring :as ring]
   [reitit.core]
   [reitit.ring.coercion :as rrc]
   [reitit.coercion.malli]
   [reitit.swagger :as swagger]
   [reitit.swagger-ui :as swagger-ui]
   [malli.core :as m]
   [malli.util :as mu]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

;; --- Schemas ---

(def HealthCheckResponse
  "Schema for health check response"
  [:map
   [:status [:enum "ok"]]
   [:timestamp inst?]
   [:version string?]])

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

;; --- Router ---

(defn create-router []
  "Create and return a Reitit router with all routes.

  Returns a Ring handler function."
  (ring/ring-handler
   (ring/router
    [;; Health check endpoint
     (healthcheck-route)

     ;; API routes would go here
     ["/api" {:tags ["api"]}
      ;; API endpoints would be defined here
      ]]

    {:data {:coercion reitit.coercion.malli/coercion
            :malli/options {:error-keys #(mu/keys HealthCheckResponse)}
            :swagger {:id ::api}
            :middleware [parameters/parameters-middleware ; decoding query & form params
                         muuntaja/format-middleware       ; content negotiation
                         exception/exception-middleware   ; converting exceptions to HTTP responses
                         rrc/coerce-request-middleware
                         rrc/coerce-response-middleware]}})))

;; --- Integrant Component ---

(defmethod ig/init-key :samuraibff/router [_ _]
  "Integrant init method for the router component.

  Creates and returns the router handler.

  Returns a Ring handler function."
  (create-router))

(defmethod ig/halt-key! :samuraibff/router [_ _]
  "Integrant halt method for the router component.

  No cleanup needed for the router."
  nil)
