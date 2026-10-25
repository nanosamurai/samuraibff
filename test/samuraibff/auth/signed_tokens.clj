(ns samuraibff.auth.signed-tokens
  "Ephemeral signed-token fixtures; no external issuer or stored credentials."
  (:require [jsonista.core :as json])
  (:import (com.nimbusds.jose JWSAlgorithm JWSHeader$Builder JWSObject Payload)
           (com.nimbusds.jose.crypto RSASSASigner)
           (com.nimbusds.jose.jwk JWKSet)
           (com.nimbusds.jose.jwk.gen RSAKeyGenerator)
           (java.util ArrayList UUID)))

(def issuer "https://issuer.example.test/realms/test")
(def config {:auth {:required? true :issuer issuer :audience "bff-web"}})
(def tenant-id (str (UUID/randomUUID)))
(defonce key-a (delay (.generate (.keyID (RSAKeyGenerator. 2048) "key-a"))))
(defonce key-b (delay (.generate (.keyID (RSAKeyGenerator. 2048) "key-b"))))

(defn claims
  "Return ordinary access-token claims relative to an epoch-millisecond clock."
  [now-ms]
  {:iss issuer :sub "test-user" :aud ["bff-web"] :tenant_id tenant-id
   :iat (quot now-ms 1000) :exp (+ 300 (quot now-ms 1000))})

(defn token
  "Sign arbitrary JSON claims, including malformed values, with an ephemeral key."
  ([claims] (token claims {}))
  ([claims {:keys [key kid algorithm] :or {algorithm JWSAlgorithm/RS256}}]
   (let [key (or key @key-a)
         header (.build (.keyID (JWSHeader$Builder. algorithm) (or kid (.getKeyID key))))
         jwt (JWSObject. header (Payload. (json/write-value-as-string claims)))]
     (.sign jwt (RSASSASigner. key))
     (.serialize jwt))))

(defn key-json
  "Return the public half of fixture signing keys as decoded JWKS JSON."
  [keys]
  (json/read-value (.toString (JWKSet. (ArrayList. (mapv #(.toPublicJWK %) keys))))
                   (json/object-mapper {:decode-key-fn keyword})))

(defn metadata-fetch
  "Return a fake discovery/JWKS transport backed by a mutable public key list."
  [keys* calls*]
  (fn [url]
    (swap! calls* conj url)
    (if (.endsWith ^String url "/.well-known/openid-configuration")
      {:issuer issuer :jwks_uri (str issuer "/keys")}
      (key-json @keys*))))
