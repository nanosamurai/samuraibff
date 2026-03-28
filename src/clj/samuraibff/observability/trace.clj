;; Copyright (c) samuraibff contributors.
(ns samuraibff.observability.trace
  "Helpers for log ↔ trace correlation.

  Goals
  -----
  - Ensure we can always compute a `trace_id` for session-scoped flows
    (trace_id == normalized session_id).
  - For non-session flows, fall back to the current OpenTelemetry span context
    when present.

  Public API
  ----------
  - `trace-context` -> {:trace-id :span-id}
  - `trace-id-for-session` -> 32-hex trace id or nil
  "
  (:require
    [samuraibff.session-trace :as session-trace])
  (:import
    (io.opentelemetry.api.trace Span SpanContext)))

(defn trace-id-for-session
  "Return a deterministic OTEL trace id derived from a session id.

  Inputs:
  - session-id: UUID string

  Returns:
  - 32-char lowercase hex trace id
  - nil when session-id is not a UUID string" 
  [session-id]
  (session-trace/session-id->trace-id session-id))

(defn- span-context
  []
  (let [^Span span (Span/current)
        ^SpanContext sc (when span (.getSpanContext span))]
    (when (and sc (.isValid sc)) sc)))

(defn trace-context
  "Return the current trace context as a simple map.

  Returns:
  - {:trace-id string :span-id string} when there is a valid current span
  - nil otherwise" 
  []
  (when-let [^SpanContext sc (span-context)]
    {:trace-id (.getTraceId sc)
     :span-id (.getSpanId sc)}))
