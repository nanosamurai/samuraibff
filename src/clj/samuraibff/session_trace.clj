(ns samuraibff.session-trace
  "Helpers to bind a stable OpenTelemetry trace context derived from session_id.

  Goal
  ----
  In local observability we want *one trace per session* across Kafka + gRPC hops.

  The OTEL Java agent injects/extracts W3C trace context automatically for
  instrumented clients, but it needs an active parent context.

  We derive that parent context deterministically from `session-id` (UUID string):
  - trace_id = session_id without dashes (32 lowercase hex)
  - span_id  = last 16 hex chars of trace_id (fallback to first 16 if all zeros)

  This keeps the implementation stateless and makes it trivial to search a trace
  by session id (trace_id == normalized session id).
  
  Notes
  -----
  - Intended primarily for local dev; in prod we may not want trace ids to be guessable.
  - We create a *remote parent* span context and make it current for the duration
    of a block; the agent then creates real spans underneath it.
  "
  (:require
    [clojure.string :as str])
  (:import
    (io.opentelemetry.api.trace Span SpanContext TraceFlags TraceState)
    (io.opentelemetry.context Context Scope)))

(defn session-id->trace-id
  "Convert a UUID session id string to a 32-hex OTEL trace id.

  Returns nil if input does not look like a UUID." 
  [session-id]
  (let [s (some-> session-id str str/trim)]
    (when (and s
               (= 36 (count s))
               (re-matches #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" s))
      (-> s
          (str/replace "-" "")
          (str/lower-case)))))

(defn- all-zeros?
  [^String hex]
  (boolean (re-matches #"0+" hex)))

(defn session-id->span-id
  "Derive a deterministic 16-hex parent span id from a session id.

  This span id is used only as the *remote parent*; the OTEL agent will create
  real child spans with proper unique span ids.

  Returns nil if trace_id cannot be derived." 
  [session-id]
  (when-let [trace-id (session-id->trace-id session-id)]
    (let [tail (.substring trace-id 16 32)]
      (if (all-zeros? tail)
        (.substring trace-id 0 16)
        tail))))

(defn context-for-session
  "Build an OpenTelemetry Context that contains a remote parent span context for session-id.

  Returns nil if session-id is not a UUID." 
  [session-id]
  (when-let [trace-id (session-id->trace-id session-id)]
    (let [span-id (or (session-id->span-id session-id) "0000000000000001")
          sc (SpanContext/createFromRemoteParent
               trace-id
               span-id
               (TraceFlags/getSampled)
               (TraceState/getDefault))
          span (Span/wrap sc)]
      ;; Clojure interop: `Context/root` is a static method, call it with `Context/root`
      ;; and then call `.with` on the returned instance.
      (.with (Context/root) span))))

(defmacro with-session-trace
  "Make a session-derived OTEL Context current for the duration of body.

  If session-id is invalid/unavailable, runs body without modifying context." 
  [session-id & body]
  `(if-let [ctx# (context-for-session ~session-id)]
     (let [^Scope scope# (.makeCurrent ^Context ctx#)]
       (try
         ~@body
         (finally
           (.close scope#))))
     (do
       ~@body)))
