(ns samuraibff.http.track-results-integration-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [samuraibff.http.track-results :as http]
            [samuraibff.http.track-results-test :as fixture]
            [samuraibff.testcontainers.localstack :as s3]
            [samuraibff.testcontainers.postgres :as pg])
  (:import (java.util UUID HexFormat)
           (java.security MessageDigest)))

(deftest persisted-index-and-verified-artifact-round-trip
  (pg/with-postgres [postgres]
    (s3/with-localstack [localstack]
      (let [ds (pg/datasource (pg/jdbc-url postgres) "drsynth" "drsynth")
            tenant (UUID/fromString fixture/tenant)
            session-id (UUID/fromString fixture/session-id)
            result-id (UUID/fromString fixture/result-id)
            config {:s3 (merge (s3/s3-credentials localstack)
                               {:endpoint (s3/s3-endpoint localstack) :force-path-style? true
                                :buckets {:recordings {:bucket "test-recordings"}}})}
            deps {:db {:ds ds} :config config}
            req {:auth/tenant-id fixture/tenant
                 :path-params {:session_id fixture/session-id :result_id fixture/result-id}}
            key (str "refined-tracks/" fixture/tenant "/" fixture/session-id "/" fixture/result-id
                     "/whisperx/" fixture/result-id "/" fixture/result-id ".transcript.json")
            uri (str "s3://test-recordings/" key)
            artifact {:schema_version 1 :full_text "Fixture" :lang "en"
                      :segments [{:text "Fixture" :start_s 10.0 :end_s 10.5
                                  :words [{:text "Fixture" :start_s 10.0 :end_s 10.5}]}]
                      :provenance {:runtime "private"}}
            bytes (.getBytes (json/generate-string artifact) "UTF-8")
            digest (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes))]
        (pg/apply-schema! ds)
        (jdbc/execute! ds [(slurp (io/resource "migrations/track-results-schema.sql"))])
        (jdbc/execute! ds ["INSERT INTO tenants(id,name) VALUES (?, 'fixture')" tenant])
        (jdbc/execute! ds ["INSERT INTO sessions(id,tenant_id,session_key,status,stream_controls,asr_meta_snapshot) VALUES (?,?,?,'active',?::jsonb,?::jsonb)"
                           session-id tenant (str session-id)
                           (json/generate-string (:stream_controls fixture/session))
                           (json/generate-string (:asr_meta_snapshot fixture/session))])
        (is (= 2 (count (get-in ((http/index-handler deps) req) [:body :tracks]))))
        (jdbc/execute! ds ["INSERT INTO transcript_track_results(result_id,tenant_id,session_id,plan_id,track_id,profile_id,run_id,attempt_id,stage,unit_id,revision,status,is_primary,result_uri,result_sha256,capabilities,degradations,provenance,event_sha256,event_created_at_ns,source) VALUES (?,?,?,?,'whisperx','whisperx-medium-refined-r1',?,?,'refined','fixed-160000:160000:320000',1,'succeeded',true,?,?,?::jsonb,'[]','{}',?,1,?::jsonb)"
                           result-id tenant session-id result-id result-id result-id uri digest
                           (json/generate-string (:capabilities fixture/row)) digest
                           (json/generate-string (:source fixture/row))])
        (with-open [client (s3/s3-client localstack)]
          (s3/create-bucket! client "test-recordings")
          (s3/put-object! client "test-recordings" key bytes)
          (let [response ((http/result-handler deps) req)]
            (is (= 200 (:status response)))
            (is (= 10.0 (get-in response [:body :transcript :segments 0 :words 0 :start_s])))
            (is (not (contains? (get-in response [:body :transcript]) :provenance)))
            (is (= "private, no-store" (get-in response [:headers "Cache-Control"]))))
          (is (= 404 (:status ((http/index-handler deps) (assoc req :auth/tenant-id fixture/session-id)))))
          (is (= 404 (:status ((http/result-handler deps) (assoc req :auth/tenant-id fixture/session-id)))))
          (jdbc/execute! ds ["UPDATE transcript_track_results SET capabilities='{\"segment_timestamps\":false,\"word_timestamps\":false,\"speaker_labels\":false}' WHERE result_id=?" result-id])
          (let [segment (get-in ((http/result-handler deps) req) [:body :transcript :segments 0])]
            (is (= {:text "Fixture"} segment)))
          (s3/put-object! client "test-recordings" key (.getBytes "changed" "UTF-8"))
          (is (= 503 (:status ((http/result-handler deps) req))))
          (s3/put-object! client "test-recordings" key (byte-array 1000001))
          (is (= 503 (:status ((http/result-handler deps) req))))
          (jdbc/execute! ds ["UPDATE transcript_track_results SET result_uri='http://127.0.0.1/private' WHERE result_id=?" result-id])
          (is (= 503 (:status ((http/result-handler deps) req)))))))))
