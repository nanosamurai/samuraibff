(ns samuraibff.ws.ws-integration-test
  "Integration test for HTTP server + /ws/audio + /ws/events + gRPC (rtservice).

  It starts an Integrant system with:
  - http-kit server
  - reitit router
  - ws-registry
  - a fake gRPC stream at the client boundary

  Then it:
  - opens /ws/events and collects messages
  - opens /ws/audio and sends a few binary frames
  - closes /ws/audio while leaving /ws/events connected
  - asserts the gRPC request is completed and its terminal ASR response arrives

  Note on WebSocket client:
  - We intentionally use `com.neovisionaries/nv-websocket-client` here because
    the JDK `java.net.http.WebSocket` client has shown ProtocolExceptions against
    http-kit in this repo's tests." 
  (:require
    [clojure.test :refer [deftest is]]
    [integrant.core :as ig]
    [jsonista.core :as json]
    [samuraibff.config]
    [samuraibff.grpc.client :as grpc]
    [samuraibff.http.router]
    [samuraibff.http.server]
    [samuraibff.ws.registry :as reg])
  (:import
    (com.neovisionaries.ws.client WebSocket WebSocketAdapter WebSocketFactory)
    (samuraibff.proto AsrEvent AsrType)
    (java.util UUID)
    (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private mapper
  (json/object-mapper {:decode-key-fn keyword}))

(defn- ws-url
  "Build a ws:// URL.

  Inputs:
  - port: integer
  - path: string
  - query: string (already url-encoded)

  Returns: string" 
  [port path query]
  (str "ws://127.0.0.1:" port path "?" query))

(defn- connect-ws!
  "Connect to a WebSocket URL using nv-websocket-client.

  Inputs:
  - url: string
  - {:keys [on-text on-binary on-close]}: callback fns

  Callbacks:
  - on-text: (fn [text-string])
  - on-binary: (fn [^bytes byte-array])
  - on-close: (fn [status reason])

  Returns: com.neovisionaries.ws.client.WebSocket" 
  [^String url {:keys [on-text on-binary on-close]}]
  (let [ws (-> (WebSocketFactory.)
               (.createSocket url))]
    (.addListener
      ws
      (proxy [WebSocketAdapter] []
        ;; Implement only the String overload. WebSocketAdapter already provides
        ;; a default implementation for the byte[] overload, and Clojure cannot
        ;; define two same-arity overloads in a proxy.
        (onTextMessage [_ws ^String message]
          (when on-text
            (on-text message)))

        (onBinaryMessage [_ws payload]
          (when on-binary
            (on-binary payload)))

        (onDisconnected [_websocket server-close-frame _client-close-frame _closed-by-server]
          (when on-close
            (on-close (when server-close-frame (.getCloseCode server-close-frame))
                      "disconnected")))

        (onError [error]
          (when on-close
            (on-close 1011 (str error))))))
    ;; Logging in tests: if connect fails, it tends to fail silently otherwise.
    (try
      (.connect ws)
      (catch Exception e
        (when on-close
          (on-close 1011 (str "connect-failed: " (.getMessage e))))
        (throw e)))
    ws))

(defn- drain-queue
  "Drain up to `max-wait-ms` from a LinkedBlockingQueue.

  Inputs:
  - q: java.util.concurrent.LinkedBlockingQueue
  - max-wait-ms: integer

  Returns: vector of collected elements" 
  [^LinkedBlockingQueue q max-wait-ms]
  (loop [acc []
         deadline (+ (System/currentTimeMillis) max-wait-ms)]
    (if (>= (System/currentTimeMillis) deadline)
      acc
      (if-let [m (.poll q 250 TimeUnit/MILLISECONDS)]
        (recur (conj acc m) deadline)
        (recur acc deadline)))))

(defn- free-port
  "Allocate a loopback TCP port for the integration test.

  Returns: integer port number."
  []
  (with-open [socket (java.net.ServerSocket. 0 0 (java.net.InetAddress/getLoopbackAddress))]
    (.getLocalPort socket)))

(deftest ws-audio-close-flushes-terminal-asr-test
  (let [port (free-port)
        session-id (str (UUID/randomUUID))
        completed* (atom #{})
        sent-counts* (atom {})
        fake-start-stream!
        (fn [client {:keys [on-next on-error on-complete]}]
          {:send! (fn [_audio-chunk]
                    (swap! sent-counts* update (:id client) (fnil inc 0)))
           :complete! (fn []
                        (when-not (contains? @completed* (:id client))
                          (swap! completed* conj (:id client))
                          (on-next (-> (AsrEvent/newBuilder)
                                       (.setSessionId session-id)
                                       (.setStartS 0.0)
                                       (.setEndS 0.02)
                                       (.setText (str "synthetic terminal " (:id client)))
                                       (.setType AsrType/FINAL)
                                       (.setLang "en")
                                       (.setProviderProfileId (str (:id client) "-profile"))
                                       (.build)))
                          (on-complete)))
           :error! (fn [error]
                     (when on-error
                       (on-error error)))})
        cfg {:samuraibff/config {:env :test
                                 :http {:host "127.0.0.1" :port port}}
             :samuraibff/ws-registry {:config (ig/ref :samuraibff/config)}
             :samuraibff/router {:config (ig/ref :samuraibff/config)
                                 :ws-registry (ig/ref :samuraibff/ws-registry)
                                 :grpc {:tracks [{:id "faster"}
                                                 {:id "qwen"}]}}
             :samuraibff/http-server {:config (ig/ref :samuraibff/config)
                                      :handler (ig/ref :samuraibff/router)}}]
    (with-redefs [grpc/get-capabilities (fn [client _]
                                         {:provider-profile-id (str (:id client) "-profile")})
                  grpc/start-stream! fake-start-stream!]
      (let [system (ig/init cfg)
            q (LinkedBlockingQueue.)
            events* (atom nil)
            audio* (atom nil)]
        (try
          (reset! events*
                  (connect-ws!
                   (ws-url port "/ws/events" (str "session_id=" session-id))
                   {:on-text (fn [msg] (.offer q msg))
                    :on-close (fn [status reason]
                                (.offer q (str "CLOSED:" status ":" reason)))}))

          (reset! audio*
                  (connect-ws!
                   (ws-url port "/ws/audio" (str "session_id=" session-id "&lang=en&sample_rate=16000"))
                   {:on-close (fn [_ _] nil)}))

          (let [ws-registry (get system :samuraibff/ws-registry)
                session (reg/get-session ws-registry "00000000-0000-0000-0000-000000000000" session-id)]
            (is (= "en" (:lang session))
                (str "Expected :lang to be updated, got "
                     (pr-str (select-keys session [:lang :sample-rate])))))

          (dotimes [_ 3]
            (.sendBinary ^WebSocket @audio* (byte-array 320)))
          (.disconnect ^WebSocket @audio*)

          (let [msgs (drain-queue q 4000)
                decoded (->> msgs
                             (keep (fn [message]
                                     (when (and (string? message)
                                                (not (.startsWith ^String message "CLOSED:")))
                                       (try
                                         (json/read-value message mapper)
                                         (catch Exception _
                                           nil))))))]
            (is (= {"faster" 3 "qwen" 3} @sent-counts*)
                "Accepted audio should reach both tracks before completion")
            (is (= #{"faster" "qwen"} @completed*)
                "Closing /ws/audio should half-close both requests")
            (is (= #{["faster" "faster-profile" true]
                     ["qwen" "qwen-profile" false]}
                   (->> decoded
                        (filter #(and (= "asr" (:type %)) (true? (:final %))))
                        (map (juxt :track :provider_profile_id :primary_track))
                        set))
                (str "Expected labelled terminal events from both tracks, got " msgs))
            (is (not-any? #(.startsWith ^String % "CLOSED:") msgs)
                (str "Events WebSocket should remain open through terminal delivery, got " msgs)))

          (finally
            (when-let [^WebSocket audio @audio*] (.disconnect audio))
            (when-let [^WebSocket events @events*] (.disconnect events))
            (ig/halt! system)))))))
