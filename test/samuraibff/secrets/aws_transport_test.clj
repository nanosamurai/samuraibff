(ns samuraibff.secrets.aws-transport-test
  "Exercise the synchronous AWS JSON protocol without real credentials or AWS calls."
  (:require [clojure.test :refer [deftest is]]
            [jsonista.core :as json]
            [org.httpkit.server :as http]
            [samuraibff.secrets.aws-secrets-manager :as aws])
  (:import (software.amazon.awssdk.services.secretsmanager.model
            CreateSecretRequest DeleteSecretRequest)))

(deftest synchronous-secrets-manager-roundtrip-test
  (let [requests (atom [])
        arn "synthetic-secret-reference"
        server (http/run-server
                (fn [request]
                  (swap! requests conj {:target (get-in request [:headers "x-amz-target"])
                                        :signed? (boolean (get-in request [:headers "authorization"]))
                                        :body (json/read-value (slurp (:body request)))})
                  {:status 200
                   :headers {"content-type" "application/x-amz-json-1.1"}
                   :body (json/write-value-as-string {"ARN" arn "Name" "transport-test"})})
                {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    (try
      (with-open [client (aws/build-client {:region "us-east-1"
                                            :endpoint (str "http://127.0.0.1:" (http/server-port server))
                                            :access-key "synthetic-access-key"
                                            :secret-key "synthetic-secret-key"})]
        (is (= arn (.arn (.createSecret client (-> (CreateSecretRequest/builder)
                                                   (.name "transport-test")
                                                   (.secretString "synthetic-value")
                                                   (.build))))))
        (is (= arn (.arn (.deleteSecret client (-> (DeleteSecretRequest/builder)
                                                   (.secretId arn)
                                                   (.forceDeleteWithoutRecovery true)
                                                   (.build)))))))
      (is (= ["secretsmanager.CreateSecret" "secretsmanager.DeleteSecret"]
             (mapv :target @requests)))
      (is (every? :signed? @requests))
      (is (= "synthetic-value" (get-in @requests [0 :body "SecretString"])))
      (is (= true (get-in @requests [1 :body "ForceDeleteWithoutRecovery"])))
      (finally (http/server-stop! server)))))
