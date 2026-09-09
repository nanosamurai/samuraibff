(ns samuraibff.grpc.routing-probe
  "Executable two-replica distribution and rejection-recovery proof."
  (:require
   [integrant.core :as ig]
   [samuraibff.grpc.client :as grpc]))

(defn- open-session!
  "Open and admit one idle session on the shared round-robin channel."
  [track session-id completed]
  (grpc/start-stream!
   track
   {:metadata {"x-session-id" session-id}
    :admission-timeout-ms 5000
    :admission-max-attempts 4
    :on-complete #(deliver completed true)}))

(defn- require!
  "Throw with proof data when an expected routing invariant is false."
  [condition message data]
  (when-not condition
    (throw (ex-info message data))))

(defn -main
  "Prove two-way distribution, then recover from one full replica."
  [& _args]
  (let [target (or (System/getenv "ROUTING_TARGET") "rtservice:50052")
        component (ig/init-key
                   :samuraibff/grpc-client
                   {:config {:grpc {:rtservice-addr target}}})]
    (try
      (let [track (first (grpc/tracks component))
            first-completed (promise)
            second-completed (promise)
            third-completed (promise)
            first-stream (open-session! track "routing-first" first-completed)
            second-stream (open-session! track "routing-second" second-completed)
            first-instance (:serving-instance-id first-stream)
            second-instance (:serving-instance-id second-stream)]
        (require! (not= first-instance second-instance)
                  "First two sessions were not distributed"
                  {:first first-stream :second second-stream})

        (grpc/close! second-stream)
        (require! (= true (deref second-completed 5000 ::timeout))
                  "Second replica did not release its slot"
                  {:second second-stream})

        (let [third-stream (open-session! track "routing-third" third-completed)]
          (require! (= second-instance (:serving-instance-id third-stream))
                    "Admission retry did not reach the free replica"
                    {:first first-stream :second second-stream :third third-stream})
          (require! (> (:admission-attempts third-stream) 1)
                    "Proof did not exercise REPLICA_FULL recovery"
                    {:third third-stream})
          (println "ROUTING_PROOF_OK"
                   {:distributed-instances [first-instance second-instance]
                    :recovery-instance (:serving-instance-id third-stream)
                    :recovery-attempts (:admission-attempts third-stream)})
          (grpc/close! third-stream)
          (deref third-completed 5000 nil)
          (grpc/close! first-stream)
          (deref first-completed 5000 nil)))
      (finally
        (ig/halt-key! :samuraibff/grpc-client component)))))
