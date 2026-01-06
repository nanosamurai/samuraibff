(ns samuraibff.http.server
  "HTTP server component for the samuraibff application.

  This namespace provides an Integrant component that starts an HTTP server
  with a provided Ring handler. The server is responsible for starting and stopping
  the HTTP server instance using http-kit.

  Component keys:
  - `:samuraibff/http-server` - The HTTP server component

  Dependencies:
  - Requires a Ring handler to be provided (typically from a router component)

  Example usage in system.edn:
  {:samuraibff/http-server
   {:config {:port 3000}
    :handler #ig/ref :samuraibff/router}}"
  (:require
   [integrant.core :as ig]
   [org.httpkit.server]
   [org.corfield.logging4j2 :as log]))

;; --- HTTP Server Component ---

(defn start-server [config handler]
  "Start the HTTP server with the given configuration and handler.

  config - Map containing server configuration (e.g., :port)
  handler - Ring handler to process requests

  Returns the server instance."
  (let [{:keys [port]} config
        server (org.httpkit.server/run-server handler {:port port})]
    (log/info (format "HTTP server started on port %d" port))
    server))

(defn stop-server [server]
  "Stop the HTTP server gracefully.

  server - The server instance to stop"
  (when server
    (server :timeout 100)
    (log/info "HTTP server stopped")))

(defmethod ig/init-key :samuraibff/http-server [_ {:keys [config handler]}]
  "Integrant init method for the HTTP server component.

  Starts the HTTP server with the provided configuration and handler.

  Parameters:
  - config - Map containing server configuration (e.g., :port)
  - handler - Ring handler to process requests

  Returns a map with :server and :handler keys."
  (let [server (start-server config handler)]
    {:server server
     :handler handler}))

(defmethod ig/halt-key! :samuraibff/http-server [_ {:keys [server]}]
  "Integrant halt method for the HTTP server component.

  Stops the HTTP server gracefully.

  Parameters:
  - server - The server instance to stop"
  (stop-server server))
