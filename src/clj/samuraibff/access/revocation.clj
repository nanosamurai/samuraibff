(ns samuraibff.access.revocation
  "Bounded re-authorization for long-lived deliveries.

  Long playback responses and WebSockets were authorized when they started.
  This namespace re-checks them against the current database state so that
  suspension, demotion, credential revocation and unsharing stop delivery on
  every replica within `revocation-bound-ms`.

  A check runs at most every `check-interval-ms`. When the policy store cannot
  be consulted, delivery continues only until the last successful check is
  older than the bound, then it fails closed.

  Public API:
  - `check-interval-ms`, `revocation-bound-ms`
  - `bounded-checker`"
  (:require
   [org.corfield.logging4j2 :as log]
   [samuraibff.observability.diagnostics :as diagnostics]))

(def check-interval-ms
  "How often an open delivery re-checks its authorization."
  15000)

(def revocation-bound-ms
  "Maximum time an unauthorized or unverifiable delivery may continue."
  30000)

(defn bounded-checker
  "Wrap an authorization check into a rate-limited, fail-closed predicate.

  Inputs:
  - check!: zero-arity fn returning truthy while access is still allowed; it
    may throw when the policy store is unavailable.
  - opts: {:now (fn [] epoch-ms)} for tests.

  Returns a zero-arity fn returning boolean. It calls check! only when the
  previous successful check is older than `check-interval-ms`. A false result
  is final. An exception keeps access only while the last success is within
  `revocation-bound-ms`."
  ([check!] (bounded-checker check! {}))
  ([check! {:keys [now] :or {now #(System/currentTimeMillis)}}]
   (let [state* (atom {:last-ok (now) :denied? false})]
     (fn []
       (let [{:keys [last-ok denied?]} @state*
             t (now)]
         (cond
           denied? false
           (< (- t last-ok) check-interval-ms) true
           :else
           (let [allowed (try
                           (if (check!) :allowed :denied)
                           (catch Exception failure
                             (log/warn "Access re-check failed" (diagnostics/failure-details failure))
                             :unknown))]
             (case allowed
               :allowed (do (swap! state* assoc :last-ok t) true)
               :denied (do (swap! state* assoc :denied? true) false)
               (if (< (- t last-ok) revocation-bound-ms)
                 true
                 (do (swap! state* assoc :denied? true) false))))))))))
