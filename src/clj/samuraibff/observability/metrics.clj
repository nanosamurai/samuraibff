;; Copyright (c) samuraibff contributors.
(ns samuraibff.observability.metrics
  "Prometheus metrics registry for samuraibff.

  This namespace defines a small set of low-cardinality metrics used for
  debugging and monitoring production behavior.

  Metrics are exposed via `/internal/metrics`.

  Public API
  ----------
  - `observe-http!`
  - `inc-http-exception!`
  - `metrics-handler`
  "
  (:require
    [clojure.string :as str]
    [org.corfield.logging4j2 :as log])
  (:import
    (io.prometheus.client CollectorRegistry Counter Histogram)
    (io.prometheus.client.exporter.common TextFormat)
    (io.prometheus.client.hotspot DefaultExports)
    (java.io StringWriter)
    (java.util.concurrent.atomic AtomicBoolean)))

(defonce ^:private default-exports-initialized?*
  (AtomicBoolean. false))

(defn- init-default-exports!
  "Initialize standard JVM metrics (memory/GC/threads/etc.) exactly once.

  Returns: nil" 
  []
  (when (.compareAndSet default-exports-initialized?* false true)
    (try
      (DefaultExports/initialize)
      (catch Exception e
        ;; Best-effort only; never crash the app if default exports fail.
        (log/warn e "Failed to initialize Prometheus DefaultExports" {}))))
  nil)

(defonce ^Counter http-requests-total
  (doto
    (Counter/build)
    (.name "samuraibff_http_requests_total")
    (.help "Total HTTP requests.")
    (.labelNames (into-array String ["method" "route" "status"]))
    (.register)))

(defonce ^Histogram http-request-duration-seconds
  (doto
    (Histogram/build)
    (.name "samuraibff_http_request_duration_seconds")
    (.help "HTTP request duration in seconds.")
    (.labelNames (into-array String ["method" "route" "status"]))
    ;; A reasonable default set of buckets for typical BFF latencies.
    (.buckets (double-array [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1.0 2.5 5.0 10.0]))
    (.register)))

(defonce ^Counter http-exceptions-total
  (doto
    (Counter/build)
    (.name "samuraibff_http_exceptions_total")
    (.help "Total exceptions thrown while handling HTTP requests.")
    (.labelNames (into-array String ["method" "route" "exception"]))
    (.register)))

(defn observe-http!
  "Observe an HTTP request.

  Inputs:
  - method: string
  - route: string (low-cardinality route template; e.g. /api/recordings/:session_id)
  - status: integer HTTP status
  - duration-seconds: double

  Returns: nil" 
  [method route status duration-seconds]
  (let [method (some-> method str str/upper-case)
        route (or (some-> route str not-empty) "<unknown>")
        status (str (long status))]
    (.inc (.labels http-requests-total (into-array String [method route status])))
    (.observe (.labels http-request-duration-seconds (into-array String [method route status]))
              (double duration-seconds)))
  nil)

(defn inc-http-exception!
  "Increment the HTTP exception counter.

  Inputs:
  - method: string
  - route: string
  - throwable: Throwable

  Returns: nil" 
  [method route ^Throwable throwable]
  (let [method (some-> method str str/upper-case)
        route (or (some-> route str not-empty) "<unknown>")
        ex-name (or (some-> throwable class .getName)
                    "<unknown>")]
    (.inc (.labels http-exceptions-total (into-array String [method route ex-name]))))
  nil)

(defn metrics-handler
  "Ring handler exposing Prometheus metrics.

  Endpoint: GET /internal/metrics

  Returns:
  - 200 text/plain; version=0.0.4" 
  [_req]
  (init-default-exports!)
  (let [writer (StringWriter.)]
    (TextFormat/write004 writer (.metricFamilySamples ^CollectorRegistry CollectorRegistry/defaultRegistry))
    {:status 200
     :headers {"content-type" TextFormat/CONTENT_TYPE_004}
     :body (.toString writer)}))
