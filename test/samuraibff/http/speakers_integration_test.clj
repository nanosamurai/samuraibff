;; Copyright (c) samuraibff contributors.
(ns samuraibff.http.speakers-integration-test
  "Integration tests for enrolled speaker HTTP endpoints (S3 + Postgres)." 
  (:require
    [cheshire.core :as cheshire]
    [clojure.test :refer :all]
    [ring.mock.request :as mock]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [samuraibff.http.speakers :as http.speakers]
    [samuraibff.testcontainers.localstack :as tc.localstack]
    [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
    (java.io ByteArrayInputStream)
    (java.util UUID)))

(def ^:private tenant-id
  "00000000-0000-0000-0000-000000000000")

(defn- insert-tenant!
  [ds]
  (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" (UUID/fromString tenant-id) "Tenant"])
  nil)

(defn- build-config
  [localstack]
  (let [{:keys [access-key secret-key region]} (tc.localstack/s3-credentials localstack)
        endpoint (tc.localstack/s3-endpoint localstack)]
    {:auth {:required? true}
     :s3 {:bucket "drsynth-enrollment"
          :enrollment-prefix "enrollment"
          :region region
          :endpoint endpoint
          :access-key access-key
          :secret-key secret-key
          :force-path-style? true}}))

(defn- auth-req
  [req]
  (assoc req :auth/user {:sub "user-1"} :auth/tenant-id tenant-id))

(deftest speakers-create-list-delete-integration-test
  (testing "Create/list/delete speakers with LocalStack + Postgres"
    (tc.localstack/with-localstack [localstack]
      (tc.pg/with-postgres [pg]
        (let [jdbc-url (tc.pg/jdbc-url pg)
              ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
              _ (tc.pg/apply-schema! ds)
              _ (insert-tenant! ds)
              s3 (tc.localstack/s3-client localstack)
              _ (tc.localstack/create-bucket! s3 "drsynth-enrollment")
              config (build-config localstack)
              deps {:config config :db {:ds ds}}
              create-handler (http.speakers/create-speaker-handler deps)
              list-handler (http.speakers/list-speakers-handler deps)
              delete-handler (http.speakers/delete-speaker-handler deps)
              audio-bytes (.getBytes "RIFF....WAVE" "UTF-8")
              req (-> (mock/request :post "/api/speakers")
                      (mock/multipart-params
                        {:label "Dr Novak"
                         :sample {:filename "sample.wav"
                                  :content-type "audio/wav"
                                  :content (ByteArrayInputStream. audio-bytes)}})
                      (auth-req))
              resp (create-handler req)
              body (cheshire/parse-string (:body resp) true)
              speaker-id (:speaker_id body)
              list-resp (list-handler (auth-req (mock/request :get "/api/speakers")))
              list-body (cheshire/parse-string (:body list-resp) true)
              pre-delete-row (jdbc/execute-one!
                               ds
                               ["SELECT id, label FROM speakers WHERE id = ?" (UUID/fromString speaker-id)]
                               {:builder-fn rs/as-unqualified-lower-maps})
              delete-resp (delete-handler
                            (auth-req (mock/request :delete (str "/api/speakers/" speaker-id))))
              delete-body (cheshire/parse-string (:body delete-resp) true)
              post-delete-row (jdbc/execute-one!
                                ds
                                ["SELECT id FROM speakers WHERE id = ?" (UUID/fromString speaker-id)]
                                {:builder-fn rs/as-unqualified-lower-maps})
              s3-keys (tc.localstack/list-objects s3 "drsynth-enrollment" "enrollment/")]
          (is (= 200 (:status resp)))
          (is (string? speaker-id))
          (is (= "Dr Novak" (:label body)))
          (is (= 200 (:status list-resp)))
          (is (seq (:items list-body)))
          (is (= "Dr Novak" (:label pre-delete-row)))
          (is (= 200 (:status delete-resp)))
          (is (= speaker-id (:speaker_id delete-body)))
          (is (nil? post-delete-row))
          (is (empty? s3-keys))))
        (.close s3)))))