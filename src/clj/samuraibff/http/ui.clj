(ns samuraibff.http.ui
  "HTTP handlers for serving the SPA assets and small UI helper endpoints.

  Endpoints:
  - GET /              -> resources/public/index.html
  - GET /js/*          -> compiled shadow-cljs output (resources/public/js)
  - POST /api/sessions -> create a new session id (uuid)

  Note: for MVP, `session_id` is an opaque client identifier used by the
  websocket endpoints. Persistence is not implemented yet."
  (:require
    [clojure.java.io :as io]
    [jsonista.core :as json]
    [org.corfield.logging4j2 :as log]
    [ring.util.response :as resp]))

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name}))

(defn index-handler
  "Serve the SPA index.html from classpath (resources/public/index.html).

  Inputs:
  - req: Ring request map (ignored)

  Returns:
  - Ring response (200 text/html) or (500 text/plain if resource missing)."
  [_req]
  (if-let [_res (io/resource "public/index.html")]
    (-> (resp/resource-response "public/index.html")
        (resp/content-type "text/html; charset=utf-8"))
    (do
      (log/error "Missing public/index.html on classpath")
      {:status 500
       :headers {"content-type" "text/plain"}
       :body "Missing UI index.html"})))

(defn create-session-handler
  "Create a new session id.

  Inputs:
  - req: Ring request map (ignored)

  Returns:
  - Ring response (200 application/json) with body:
    `{ :session_id \"<uuid>\" }`."
  [_req]
  (let [session-id (str (java.util.UUID/randomUUID))]
    {:status 200
     :headers {"content-type" "application/json"}
     :body (json/write-value-as-string {:session_id session-id} json-mapper)}))
