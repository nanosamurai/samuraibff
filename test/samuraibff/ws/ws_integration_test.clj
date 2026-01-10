(ns samuraibff.ws.ws-integration-test
  "Integration test for HTTP server + /ws/audio + /ws/events + gRPC (rtservice).

  Assumes rtservice is reachable at localhost:50052.

  It starts an Integrant system with:
  - http-kit server
  - reitit router
  - ws-registry
  - gRPC client

  Then it:
  - opens /ws/events and collects messages
  - opens /ws/audio and sends a few binary frames
  - asserts at least one `status` event arrives on /ws/events." 
  (:require
    [clojure.test :refer :all]
    [integrant.core :as ig]
    [jsonista.core :as json]
    [org.httpkit.client :as http]
    [samuraibff.grpc.client]
    [samuraibff.http.router]
    [samuraibff.http.server]
    [samuraibff.ws.registry])
  (:import
    (java.util UUID)
    (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private mapper
  (json/object-mapper {:decode-key-fn keyword}))

(defn- ws-url
  "Build a ws:// URL." 
  [port path query]
  (str "ws://localhost:" port path "?" query))

(defn- drain-queue
  "Drain up to `max-wait-ms` from a LinkedBlockingQueue." 
  [^LinkedBlockingQueue q max-wait-ms]
  (loop [acc []
         deadline (+ (System/currentTimeMillis) max-wait-ms)]
    (if (>= (System/currentTimeMillis) deadline)
      acc
      (if-let [m (.poll q 250 TimeUnit/MILLISECONDS)]
        (recur (conj acc m) deadline)
        (recur acc deadline)))))

(deftest ws-audio-to-events-roundtrip-test
  (let [port 8090
        session-id (str (UUID/randomUUID))
        cfg {:samuraibff/config {:env :test
                                 :http {:port port}
                                 :grpc {:rtservice-addr "localhost:50052"}}
             :samuraibff/grpc-client {:config (ig/ref :samuraibff/config)}
             :samuraibff/ws-registry {:config (ig/ref :samuraibff/config)}
             :samuraibff/router {:config (ig/ref :samuraibff/config)
                                 :ws-registry (ig/ref :samuraibff/ws-registry)
                                 :grpc (ig/ref :samuraibff/grpc-client)}
             :samuraibff/http-server {:config (ig/ref :samuraibff/config)
                                      :handler (ig/ref :samuraibff/router)}}
        system (ig/init cfg)
        q (LinkedBlockingQueue.)
        events* (atom nil)
        audio* (atom nil)]
    (try
      (reset! events*
              @(http/websocket
                 (ws-url port "/ws/events" (str "session_id=" session-id))
                 {:on-receive (fn [_ msg] (.offer q msg))
                  :on-close (fn [_ status] (.offer q (str "CLOSED:" status)))}))

      (reset! audio*
              @(http/websocket
                 (ws-url port "/ws/audio" (str "session_id=" session-id "&lang=en&sample_rate=16000"))
                 {:on-receive (fn [_ _] nil)}))

      ;; send a few dummy frames (rtservice should handle silence)
      (dotimes [_ 3]
        (http/send! @audio* (byte-array 320)))

      (let [msgs (drain-queue q 4000)
            decoded (->> msgs
                         (keep (fn [m]
                                 (when (and (string? m)
                                            (not (.startsWith ^String m "CLOSED:")))
                                   (try
                                     (json/read-value m mapper)
                                     (catch Exception _ nil))))))]
        (is (some #(= "status" (:type %)) decoded)
            (str "Expected at least one status event, got: " msgs)))

      (finally
        (when-let [a @audio*] (http/close a))
        (when-let [e @events*] (http/close e))
        (ig/halt! system)))))
