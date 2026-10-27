(ns samuraibff.http.origin
  "Origin validation for browser mutations and WebSocket upgrades (CSRF).

  The browser UI authenticates with an HttpOnly cookie, so a foreign site could
  try to submit requests or open WebSockets that carry it. Rules:
  - Unsafe methods (POST, PUT, PATCH, DELETE) and WebSocket upgrades that send
    an `Origin` header must come from an allowed origin.
  - Cookie-authenticated unsafe requests without `Origin` must carry a
    same-origin `Referer`; otherwise they are rejected.
  - Requests with an explicit `Authorization` header and no `Origin` (SDK, CLI,
    service clients) are not browser requests and keep their bearer path.
  - `/internal/*` service callbacks are not browser endpoints and are skipped.

  Allowed origins are the browser-facing BFF origin (see
  `samuraibff.http.auth/request-origin`), the request Host origin and the
  optional `[:auth :allowed-origins]` list.

  Public API:
  - `allowed-origin?`, `wrap-origin-check`, `websocket-origin-allowed?`"
  (:require
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [samuraibff.http.auth :as http.auth])
  (:import
   (java.net URI)))

(def ^:private unsafe-methods #{:post :put :patch :delete})

(defn- header
  [req k]
  (some-> (get-in req [:headers k]) str str/trim not-empty))

(defn- normalize-origin
  "Return scheme://host[:port] in lower case, or nil for malformed values."
  [value]
  (try
    (let [uri (URI. (str value))
          scheme (some-> (.getScheme uri) str/lower-case)
          host (some-> (.getHost uri) str/lower-case)
          port (.getPort uri)
          default-port? (or (= -1 port)
                            (and (= "http" scheme) (= 80 port))
                            (and (= "https" scheme) (= 443 port)))]
      (when (and (#{"http" "https"} scheme) host)
        (str scheme "://" host (when-not default-port? (str ":" port)))))
    (catch Exception _ nil)))

(defn- allowed-origins
  [config req]
  (let [scheme (or (some-> (header req "x-forwarded-proto") str/lower-case)
                   (some-> (:scheme req) name)
                   "http")]
    (into #{}
          (keep normalize-origin)
          (concat [(http.auth/request-origin config req)
                   (some->> (header req "host") (str scheme "://"))]
                  (get-in config [:auth :allowed-origins])))))

(defn allowed-origin?
  "True when value (an Origin or Referer) belongs to an allowed origin."
  [config req value]
  (contains? (allowed-origins config req) (normalize-origin value)))

(defn- cookie-authenticated?
  [config req]
  (and (nil? (header req "authorization"))
       (some? (get-in req [:cookies (or (get-in config [:auth :cookie-name]) "access_token") :value]))))

(defn- forbidden
  []
  {:status 403
   :headers {"content-type" "application/json; charset=utf-8"}
   :body "{\"ok\":false,\"message\":\"origin-not-allowed\"}"})

(defn websocket-origin-allowed?
  "True unless a WebSocket upgrade carries a foreign Origin."
  [config req]
  (let [origin (header req "origin")]
    (or (nil? origin) (allowed-origin? config req origin))))

(defn wrap-origin-check
  "Ring middleware enforcing the origin rules above. Requires cookie parsing."
  [handler config]
  (fn [req]
    (let [uri (or (:uri req) "")
          origin (header req "origin")
          referer (header req "referer")
          unsafe? (contains? unsafe-methods (:request-method req))]
      (cond
        (or (not unsafe?) (str/starts-with? uri "/internal/"))
        (handler req)

        origin
        (if (allowed-origin? config req origin)
          (handler req)
          (do (log/warn "Rejected cross-origin request" {:uri uri :method (name (:request-method req))})
              (forbidden)))

        (cookie-authenticated? config req)
        (if (and referer (allowed-origin? config req referer))
          (handler req)
          (do (log/warn "Rejected cookie request without origin" {:uri uri})
              (forbidden)))

        :else
        (handler req)))))
