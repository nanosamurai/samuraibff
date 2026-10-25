(ns samuraibff.ui.ws-security-test
  "Exercise renderer WebSocket construction and diagnostics without real devices."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [samuraibff.ui.audio :as audio]
            [samuraibff.ui.store :as store]
            [samuraibff.ui.util :as util]
            [samuraibff.ui.ws :as ws]))

(def ^:private storage-marker "synthetic-storage-access-token")
(def ^:private query-marker "synthetic-session-query-canary")
(def ^:private fixture-session-id "11111111-1111-4111-8111-111111111111")
(defonce ^:private storage-reads* (atom 0))
(defonce ^:private sockets* (atom []))
(defonce ^:private saved* (atom nil))

(defn- install-global!
  "Set a configurable browser API mock on the Node global object."
  [key value]
  (js/Object.defineProperty js/globalThis key
                            #js {:value value :writable true :configurable true}))

(defn- fake-audio-context
  "Return the small Web Audio surface needed to start and stop a fake stream."
  []
  #js {:createScriptProcessor (fn [] #js {:connect (fn []) :disconnect (fn [])})
       :createGain (fn [] #js {:connect (fn [])})
       :createMediaStreamSource (fn [_] #js {:connect (fn [])})
       :destination #js {}
       :sampleRate 48000
       :close (fn [] (js/Promise.resolve nil))})

(defn- setup-browser!
  "Install isolated browser/socket/audio mocks and reset diagnostic state."
  []
  (reset! saved* {:globals (into {} (map (fn [key]
                                           [key (js/Object.getOwnPropertyDescriptor js/globalThis key)])
                                         ["window" "WebSocket" "navigator" "AudioContext"]))
                  :log @store/log* :session-logs @store/log-by-session*
                  :session @store/session*
                  :status @store/ws-status*})
  (reset! storage-reads* 0)
  (reset! sockets* [])
  (reset! store/log* [])
  (reset! store/log-by-session* {})
  (swap! store/session* assoc :id fixture-session-id)
  (install-global! "window"
                   #js {:location (js/URL. "https://app.example.test/recordings")
                        :sessionStorage #js {:getItem (fn [_]
                                                        (swap! storage-reads* inc)
                                                        storage-marker)}})
  (install-global! "WebSocket"
                   (fn [url]
                     (let [socket #js {:url url :readyState 1 :close (fn [])}]
                       (swap! sockets* conj socket)
                       socket)))
  (install-global! "navigator"
                   #js {:mediaDevices
                        #js {:getUserMedia (fn [_]
                                             (js/Promise.resolve
                                              #js {:getTracks (fn [] #js [])}))}})
  (install-global! "AudioContext" fake-audio-context))

(defn- restore-browser!
  "Close mock connections and restore globals and diagnostic state."
  []
  (audio/stop-audio!)
  (ws/close-events!)
  (doseq [[key descriptor] (:globals @saved*)]
    (if descriptor
      (js/Object.defineProperty js/globalThis key descriptor)
      (js-delete js/globalThis key)))
  (reset! store/log* (:log @saved*))
  (reset! store/log-by-session* (:session-logs @saved*))
  (reset! store/session* (:session @saved*))
  (reset! store/ws-status* (:status @saved*)))

(use-fixtures :each {:before setup-browser! :after restore-browser!})

(defn- assert-private-diagnostics!
  "Check both persistent log stores and the connection status details."
  []
  (let [diagnostics (pr-str [@store/log* @store/log-by-session* @store/ws-status*])]
    (doseq [excluded [storage-marker query-marker "wss://" "ws://"]]
      (is (not (str/includes? diagnostics excluded))))))

(deftest websocket-urls-ignore-browser-storage-and-credential-parameters
  (doseq [path ["/ws/events" "/ws/audio"]]
    (let [url (js/URL. (util/ws-url path {:session_id "session-one"
                                          :lang "en" :token storage-marker
                                          "ACCESS_TOKEN" storage-marker
                                          :refresh_token storage-marker
                                          :id_token storage-marker
                                          :client_secret storage-marker
                                          :authorization storage-marker
                                          :cookie storage-marker}))]
      (is (= "wss:" (.-protocol url)))
      (is (= "app.example.test" (.-host url)))
      (is (= path (.-pathname url)))
      (is (= "session-one" (.get (.-searchParams url) "session_id")))
      (is (= "en" (.get (.-searchParams url) "lang")))
      (is (= 2 (.-size (.-searchParams url))))
      (is (not (str/includes? (str url) storage-marker)))))
  (is (zero? @storage-reads*)))

(deftest websocket-origin-selection-is-preserved
  (testing "loopback browser development uses ws"
    (set! (.-location js/window) (js/URL. "http://127.0.0.1:8000/"))
    (is (= "ws://127.0.0.1:8000/ws/events?session_id=one"
           (util/ws-url "/ws/events" {:session_id "one"}))))
  (testing "an explicit backend origin still selects wss"
    (is (= "wss://backend.example.test/ws/audio?session_id=one"
           (util/ws-url "/ws/audio" {:session_id "one"}
                        {:backend-base-url "https://backend.example.test/"}))))
  (is (zero? @storage-reads*)))

(defn- exercise-connections!
  "Run the real renderer connection functions and return their capture promise."
  [electron?]
  (set! (.-samuraibffElectron js/window) #js {:isElectron electron?})
  (ws/connect-events! query-marker)
  (let [events-socket (first @sockets*)]
    (assert-private-diagnostics!)
    ((.-onopen events-socket) nil))
  (let [started (audio/start-audio! query-marker "en")
        audio-socket (last @sockets*)]
    (is (= 2 (count @sockets*)))
    (doseq [socket @sockets*]
      (let [url (js/URL. (.-url socket))]
        (is (not (str/includes? (.-url socket) storage-marker)))
        (is (= query-marker (.get (.-searchParams url) "session_id")))
        (is (nil? (.get (.-searchParams url) "token")))
        (is (nil? (.get (.-searchParams url) "access_token")))))
    (assert-private-diagnostics!)
    ((.-onopen audio-socket) nil)
    (.then started (fn [result]
                     (is (true? result))
                     (is (zero? @storage-reads*))
                     (is (seq (get @store/log-by-session* fixture-session-id)))
                     (assert-private-diagnostics!)))))

(defn- check-connections!
  "Complete an asynchronous renderer check, failing if capture does not settle."
  [electron? done]
  (let [timeout* (atom nil)
        timeout (js/Promise.
                 (fn [_ reject]
                   (reset! timeout* (js/setTimeout
                                     #(reject (js/Error. "Mock capture timed out")) 3000))))]
    (-> (js/Promise.race #js [(exercise-connections! electron?) timeout])
        (.then (fn [_] (js/clearTimeout @timeout*) (done)))
        (.catch (fn [error]
                  (js/clearTimeout @timeout*)
                  (is false (str error))
                  (done))))))

(deftest browser-connections-do-not-log-or-send-storage-tokens
  (async done (check-connections! false done)))

(deftest electron-renderer-connections-do-not-log-or-send-storage-tokens
  (async done (check-connections! true done)))
