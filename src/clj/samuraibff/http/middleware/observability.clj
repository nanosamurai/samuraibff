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
    [samuraibff.observability.mdc :as mdc]
    [samuraibff.observability.metrics :as metrics]
    [samuraibff.observability.trace :as trace]))

(defn- now-ns [] (System/nanoTime))

(defn- duration-seconds
  [start-ns end-ns]
  (/ (double (- (long end-ns) (long start-ns))) 1.0e9))

(defn- request-method
  [req]
  (some-> req :request-method name str/upper-case))

(defn- route-template
  "Return a low-cardinality route template for metrics/logging.

  Prefer Reitit route template when available, otherwise fall back to URI.

  Returns: string" 
  [req]
  (or (some-> req :reitit.core/match :template)
      (some-> req :uri)
      "<unknown>"))

(defn- request-session-id
  "Best-effort extraction of session_id from request.

  Looks at path params and query params.

  Returns: string or nil" 
  [req]
  (let [v (or (get-in req [:path-params :session_id])
              (get-in req [:path-params "session_id"])
              (get-in req [:params :session_id])
              (get-in req [:params "session_id"]))
        s (some-> v str str/trim)]
    (when-not (str/blank? (str s))
      s)))

(defn- response-message
  "Extract a stable, low-sensitivity message from a JSON response body.

  Returns: string or nil" 
  [resp]
  (let [body (:body resp)]
    (when (map? body)
      (some-> (or (:message body) (:error body)) str not-empty))))

(defn- log-response!
  "Log an HTTP response when it indicates an error.

  We intentionally avoid logging request params/headers to prevent accidental
  leakage of auth tokens.

  Returns: nil" 
  [req resp duration-seconds]
  (let [status (long (or (:status resp) 200))
        uri (:uri req)
        method (request-method req)
        route (route-template req)
        msg (response-message resp)
        tenant-id (or (:auth/tenant-id req) (get-in req [:auth :tenant-id]))
        user-sub (get-in req [:auth/user :sub])
        session-id (request-session-id req)
        fields (cond-> {:http {:method method
                               :uri uri
                               :route route
                               :status status
                               :duration_s duration-seconds}}
                 (some? msg) (assoc :message msg)
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
                status (long (or (:status resp) 200))]
            (metrics/observe-http! method route status dur-s)
            (log-response! req resp dur-s)
            resp)
          (catch Throwable t
            (let [end-ns (now-ns)
                  dur-s (duration-seconds start-ns end-ns)]
              (metrics/inc-http-exception! method route t)
              (metrics/observe-http! method route 500 dur-s)
              (log/error t "HTTP handler threw" {:http {:method method
                                                        :uri (:uri req)
                                                        :route route
                                                        :duration_s dur-s}
                                      :session_id session-id
                                      :tenant_id (some-> (or (:auth/tenant-id req) (get-in req [:auth :tenant-id])) str)
                                      :user_id (some-> (get-in req [:auth/user :sub]) str)
                                      :ex_data (when (instance? clojure.lang.ExceptionInfo t)
                                                 (ex-data t))})
              (throw t))))))))
