;; Copyright (c) samuraibff contributors.
(ns samuraibff.http.middleware.observability
  "HTTP observability middleware (metrics + correlated logging).

  This middleware is responsible for:
  - counting HTTP responses by route+status
  - observing per-route request latency
  - emitting structured logs for error responses and exceptions
  - binding log4j2 MDC keys so Tempo ⇄ Loki correlation works

  MDC keys
  --------
  - trace_id: 32-hex Tempo trace id
  - span_id:  16-hex span id (best-effort)
  - session_id: UUID string when present
  - tenant_id: UUID string when present
  - user_id: Keycloak subject (sub) when present
  - http_route: low-cardinality route template
  "
  (:require
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [samuraibff.observability.diagnostics :as diagnostics]
   [samuraibff.observability.mdc :as mdc]
   [samuraibff.observability.metrics :as metrics]
   [samuraibff.observability.trace :as trace]))

(defn- now-ns [] (System/nanoTime))

(defn- duration-seconds
  [start-ns end-ns]
  (/ (double (- (long end-ns) (long start-ns))) 1.0e9))

(defn- request-method
  "Return a recognized HTTP method, without recording arbitrary request text."
  [req]
  (let [value (:request-method req)
        method (when (or (keyword? value) (string? value))
                 (-> value name str/upper-case))]
    (if (contains? #{"GET" "HEAD" "POST" "PUT" "PATCH" "DELETE" "OPTIONS" "TRACE" "CONNECT"}
                   method)
      method
      "OTHER")))

(defn- route-template
  "Return a low-cardinality route template for metrics/logging.

  Prefer the configured Reitit template; never fall back to a raw request URI.

  Returns: string"
  [req]
  (or (some-> req :reitit.core/match :template)
      "<unmatched>"))

(defn- request-session-id
  "Best-effort extraction of session_id from request.

  Looks at path params and query params.

  Returns: string or nil"
  [req]
  (let [v (or (get-in req [:path-params :session_id])
              (get-in req [:path-params "session_id"])
              (get-in req [:params :session_id])
              (get-in req [:params "session_id"]))
        s (diagnostics/session-id v)]
    s))

(defn log-failure!
  "Log bounded failure diagnostics and route/session correlation without payloads.

  Accepts a Ring request and Throwable. Never passes the Throwable, its message,
  request URL, headers, parameters, response body or exception data to logging."
  ([req failure]
   (log-failure! req failure 500))
  ([req failure status]
   (let [session-id (request-session-id req)
         status (or (diagnostics/http-status status) 500)
         fields (cond-> (assoc (diagnostics/failure-details failure)
                               :http {:method (request-method req)
                                      :route (route-template req)
                                      :status status})
                  session-id (assoc :session_id session-id
                                    :trace_id (trace/trace-id-for-session session-id)))]
     (if (< status 500)
       (log/warn "HTTP request validation failed" fields)
       (log/error "HTTP handler failed" fields)))
   nil))

(defn- log-response!
  "Log an HTTP response when it indicates an error.

  We intentionally avoid logging request params/headers to prevent accidental
  leakage of auth tokens.

  Returns: nil"
  [req resp duration-seconds]
  (let [status (long (or (:status resp) 200))
        method (request-method req)
        route (route-template req)
        tenant-id (or (:auth/tenant-id req) (get-in req [:auth :tenant-id]))
        user-sub (get-in req [:auth/user :sub])
        session-id (request-session-id req)
        fields (cond-> {:http {:method method
                               :route route
                               :status status
                               :duration_s duration-seconds}}
                 (some? tenant-id) (assoc :tenant_id (str tenant-id))
                 (some? user-sub) (assoc :user_id (str user-sub))
                 (some? session-id) (assoc :session_id (str session-id)))]
    (cond
      (>= status 500)
      (log/error "HTTP request failed" fields)

      (>= status 400)
      ;; Keep 4xx at warn so we can debug misbehaving clients.
      (log/warn "HTTP request rejected" fields)

      :else nil))
  nil)

(defn wrap-auth-mdc
  "DEPRECATED: kept only for backward compatibility.

  New code should not use this. Auth MDC binding is performed inside
  `wrap-observability` to ensure keys are always removed on request exit.

  Returns wrapped handler."
  [handler]
  handler)

(defn wrap-observability
  "Top-level HTTP observability middleware.

  Side effects:
  - records Prometheus metrics
  - binds MDC keys (trace_id/span_id/session_id/http_route)
  - logs error responses/exceptions

  Returns: wrapped handler."
  [handler]
  (fn [req]
    (let [start-ns (now-ns)
          method (or (request-method req) "<unknown>")
          route (route-template req)
          session-id (request-session-id req)
          session-trace-id (some-> session-id trace/trace-id-for-session)
          ctx (trace/trace-context)
          trace-id (or session-trace-id (:trace-id ctx))
          span-id (:span-id ctx)
          tenant-id (or (:auth/tenant-id req) (get-in req [:auth :tenant-id]))
          user-sub (get-in req [:auth/user :sub])]
      (mdc/with-mdc {:trace_id trace-id
                     :span_id span-id
                     :session_id session-id
                     :tenant_id (some-> tenant-id str)
                     :user_id (some-> user-sub str)
                     :http_route route}
        (try
          (let [resp (handler req)
                end-ns (now-ns)
                dur-s (duration-seconds start-ns end-ns)
                status (if (nil? (:status resp))
                         200
                         (or (diagnostics/http-status (:status resp))
                             (throw (ex-info "Invalid HTTP response status" {}))))]
            (metrics/observe-http! method route status dur-s)
            (log-response! req resp dur-s)
            resp)
          (catch Throwable t
            (let [end-ns (now-ns)
                  dur-s (duration-seconds start-ns end-ns)]
              (metrics/inc-http-exception! method route t)
              (metrics/observe-http! method route 500 dur-s)
              (log-failure! req t)
              (throw (ex-info "HTTP request failed" {:type :samuraibff.http/internal-error})))))))))
