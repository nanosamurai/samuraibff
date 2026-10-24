(ns samuraibff.auth.jwks
  "Bounded, single-flight OIDC discovery and signing-key refresh."
  (:require [clojure.string :as str]
            [jsonista.core :as json])
  (:import (com.nimbusds.jose.jwk JWKSet)
           (java.io IOException)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpResponse$BodyHandler HttpResponse$BodySubscriber
                          HttpResponse$BodySubscribers)
           (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util.concurrent TimeUnit)))

(def cache-ttl-ms
  "Maximum age of an accepted key set, measured with a monotonic clock."
  300000)

(def refresh-interval-ms
  "Minimum interval between refresh attempts, including unknown-key failures."
  30000)

(def ^:private response-limit-bytes 262144)
(def ^:private mapper (json/object-mapper {:decode-key-fn keyword}))
(defonce ^:private cache* (atom nil))
(def ^:private client
  (-> (HttpClient/newBuilder)
      (.followRedirects HttpClient$Redirect/NEVER)
      (.connectTimeout (Duration/ofSeconds 5))
      (.build)))

(defn- monotonic-ms
  "Return monotonic milliseconds for cache lifetimes and retry backoff."
  []
  (quot (System/nanoTime) 1000000))

(defn- bounded-body-handler
  "Return a response handler that cancels bodies larger than the JSON limit."
  []
  (reify HttpResponse$BodyHandler
    (apply [_ _response-info]
      (let [subscriber (HttpResponse$BodySubscribers/ofByteArray)
            subscription* (atom nil)
            received* (atom 0)]
        (reify HttpResponse$BodySubscriber
          (getBody [_] (.getBody subscriber))
          (onSubscribe [_ subscription]
            (reset! subscription* subscription)
            (.onSubscribe subscriber subscription))
          (onNext [_ buffers]
            (let [size (reduce + (map #(.remaining ^ByteBuffer %) buffers))]
              (if (> (swap! received* + size) response-limit-bytes)
                (do
                  (.cancel ^java.util.concurrent.Flow$Subscription @subscription*)
                  (.onError subscriber (IOException. "OIDC response exceeds size limit")))
                (.onNext subscriber buffers))))
          (onError [_ error] (.onError subscriber error))
          (onComplete [_] (.onComplete subscriber)))))))

(defn- endpoint-uri
  "Validate an operator/discovery endpoint without changing private HTTP setups."
  [url]
  (let [uri (URI/create (str url))
        scheme (.getScheme uri)
        host (.getHost uri)]
    (when-not (and (seq host)
                   (nil? (.getUserInfo uri))
                   (nil? (.getFragment uri))
                   (contains? #{"https" "http"} scheme))
      (throw (ex-info "Invalid OIDC endpoint" {})))
    uri))

(defn fetch-json!
  "Read bounded OIDC JSON without redirects, with a five-second total deadline.

  Throws a constant error without response contents or remote exception data."
  [url]
  (try
    (let [request (-> (HttpRequest/newBuilder (endpoint-uri url))
                      (.timeout (Duration/ofSeconds 5))
                      (.GET)
                      (.build))
          pending (.sendAsync client request (bounded-body-handler))]
      (try
        (let [response (.get pending 5 TimeUnit/SECONDS)]
          (when-not (= 200 (.statusCode response))
            (throw (ex-info "OIDC request failed" {})))
          (json/read-value (String. ^bytes (.body response) StandardCharsets/UTF_8) mapper))
        (finally
          (when-not (.isDone pending)
            (.cancel pending true)))))
    (catch Exception _
      (throw (ex-info "OIDC metadata unavailable" {})))))

(defn- fetch-key-set!
  "Retrieve and validate discovery plus a bounded, public signing-key set."
  [issuer fetch-json]
  (let [discovery (fetch-json (str (str/replace issuer #"/+$" "")
                                   "/.well-known/openid-configuration"))
        key-url (:jwks_uri discovery)]
    (when-not (and (= issuer (:issuer discovery)) (string? key-url))
      (throw (ex-info "Invalid OIDC discovery" {})))
    (when (and (= "https" (.getScheme (endpoint-uri issuer)))
               (not= "https" (.getScheme (endpoint-uri key-url))))
      (throw (ex-info "Invalid OIDC endpoint downgrade" {})))
    (let [key-json (fetch-json key-url)
          jwks (JWKSet/parse (json/write-value-as-string key-json))]
      (when-not (<= 1 (count (.getKeys jwks)) 64)
        (throw (ex-info "Invalid OIDC key set" {})))
      (.toPublicJWKSet jwks))))

(defn- fresh-known-keys
  "Read a known key without waiting for an unrelated refresh; never extend TTL."
  [cached issuer kid now]
  (let [keys (:jwks cached)]
    (when (and (= issuer (:issuer cached)) keys
               (< (- now (:loaded-at-ms cached)) cache-ttl-ms)
               (.getKeyByKeyId ^JWKSet keys kid))
      keys)))

(defn resolve-key-set!
  "Return fresh keys for an issuer and key ID, or fail closed.

  Refreshes after five minutes or for an unknown kid, at most once per thirty
  seconds. Refresh failures never extend the original cache lifetime. Concurrent
  requests share one fetch; arbitrary key IDs never create cache entries.
  Options inject fetch-json, a single-entry cache atom and monotonic now-ms
  for isolated tests."
  [issuer kid {:keys [fetch-json cache now-ms]}]
  (let [cache (or cache cache*)
        now-ms (or now-ms monotonic-ms)
        fetch-json (or fetch-json fetch-json!)]
    (or (fresh-known-keys @cache issuer kid (now-ms))
        (locking cache
          (let [now (now-ms)
                cached @cache
                same-issuer? (= issuer (:issuer cached))
                keys (:jwks cached)
                fresh? (and same-issuer? keys
                            (< (- now (:loaded-at-ms cached)) cache-ttl-ms))
                known? (and fresh? (.getKeyByKeyId ^JWKSet keys kid))
                throttled? (and same-issuer?
                                (< (- now (:attempt-at-ms cached)) refresh-interval-ms))]
            (cond
              known? keys
              throttled? (throw (ex-info "OIDC keys unavailable" {}))
              :else
              (do
                (reset! cache (assoc (if fresh? cached {})
                                     :issuer issuer :attempt-at-ms now))
                (let [keys (fetch-key-set! issuer fetch-json)]
                  (reset! cache {:issuer issuer :attempt-at-ms (now-ms)
                                 :loaded-at-ms (now-ms) :jwks keys})
                  (when-not (.getKeyByKeyId ^JWKSet keys kid)
                    (throw (ex-info "Unknown OIDC signing key" {})))
                  keys))))))))
