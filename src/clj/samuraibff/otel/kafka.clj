(ns samuraibff.otel.kafka
  "Small helpers for Kafka tracing in samuraibff.

  Goal
  ----
  Keep business logic readable while still emitting a minimal set of spans that
  anchor end-to-end session traces.

  We intentionally *rate-limit* span creation for high-volume topics like
  `audio.raw` (many messages per second), but we still propagate `traceparent`
  on every message.

  Env vars
  --------
  - SAMURAIBFF_AUDIO_RAW_PRODUCE_SPAN_EVERY_S (float seconds)
      0  -> disable produce spans completely
      >0 -> emit at most 1 `kafka.produce audio.raw` span per interval
  "
  (:require
    [clojure.string :as str]
    [samuraibff.session-trace :as session-trace])
  (:import
    (io.opentelemetry.api GlobalOpenTelemetry)
    (io.opentelemetry.api.trace Span SpanContext SpanKind StatusCode)))

(def ^:private default-audio-raw-produce-span-every-s 2.0)

(defn- parse-double
  [s]
  (let [s0 (some-> s str str/trim)]
    (when (and s0 (not (str/blank? s0)))
      (try
        (Double/parseDouble s0)
        (catch Exception _ nil)))))

(def ^:private audio-raw-produce-span-every-ms
  (long
    (* 1000.0
       (double
         (or (parse-double (System/getenv "SAMURAIBFF_AUDIO_RAW_PRODUCE_SPAN_EVERY_S"))
             default-audio-raw-produce-span-every-s)))))

(defonce ^:private last-audio-raw-produce-span-ms* (atom 0))

(defn- now-ms [] (System/currentTimeMillis))

(defn traceparent-for-span
  "Return a W3C traceparent string for a started Span (or nil)." 
  [^Span span]
  (when span
    (let [^SpanContext sc (.getSpanContext span)
          flags (if (.isSampled sc) "01" "00")]
      (str "00-" (.getTraceId sc) "-" (.getSpanId sc) "-" flags))))

(defn start-audio-raw-produce-span!
  "Start a rate-limited `kafka.produce audio.raw` span.

  Returns a started Span or nil." 
  []
  (when (pos? audio-raw-produce-span-every-ms)
    (let [t (now-ms)]
      (when (>= (- t @last-audio-raw-produce-span-ms*) audio-raw-produce-span-every-ms)
        (reset! last-audio-raw-produce-span-ms* t)
        (let [tracer (.getTracer GlobalOpenTelemetry "samuraibff.otel.kafka")
              sb (.spanBuilder tracer "kafka.produce audio.raw")]
          (.setSpanKind sb SpanKind/PRODUCER)
          (.startSpan sb))))))

(defn end-produce-span!
  "End a produce span with Kafka callback info (best-effort).

  Inputs:
  - span: Span or nil
  - metadata: Kafka RecordMetadata or nil
  - exception: Throwable or nil
  "
  [^Span span metadata exception]
  (when span
    (try
      (when metadata
        (try
          ;; We avoid importing RecordMetadata to keep deps light.
          (.setAttribute span "messaging.kafka.partition" (long (.partition metadata)))
          (.setAttribute span "messaging.kafka.offset" (long (.offset metadata)))
          (catch Exception _ nil)))
      (when exception
        (.recordException span exception)
        (.setStatus span StatusCode/ERROR))
      (finally
        (try (.end span) (catch Exception _ nil))))))

(defn traceparent-for-audio-raw
  "Return traceparent header value for an audio.raw message.

  If a real span was created, prefer its context so downstream spans attach to a
  real exported parent. Otherwise fall back to deterministic session traceparent.
  "
  [session-id ^Span span]
  (or (traceparent-for-span span)
      (session-trace/traceparent-for-session session-id)))
