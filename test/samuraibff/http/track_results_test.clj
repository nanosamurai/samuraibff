(ns samuraibff.http.track-results-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [malli.core :as m]
            [samuraibff.auth.oidc :as oidc]
            [samuraibff.db.track-results :as db]
            [samuraibff.db.recordings :as recordings]
            [samuraibff.db.webhook-delivery-outcomes :as outcomes]
            [samuraibff.db.workflow-results :as workflows]
            [samuraibff.http.router :as router]
            [samuraibff.http.track-results :as http]
            [samuraibff.track-schemas :as schemas])
  (:import (java.util UUID)))

(def tenant "00000000-0000-0000-0000-000000000001")
(def session-id "00000000-0000-0000-0000-000000000002")
(def result-id "00000000-0000-0000-0000-000000000003")
(def plan {:tenant_id tenant :session_id session-id :plan_id result-id
           :refinement_tracks [{:track_id "whisperx" :profile_id "whisperx-medium-refined-r1" :primary true}]
           :final_tracks [{:track_id "whisperx" :profile_id "whisperx-medium-final-r1" :primary true}]})
(def session {:stream_controls {:asr_plan plan} :status "active" :has_recording false
              :asr_meta_snapshot {:asr_track_catalog [{:stage "refined" :track_id "whisperx"
                                                       :profile_id "whisperx-medium-refined-r1"
                                                       :display_name "Original label"}]}})
(def row {:tenant_id (UUID/fromString tenant) :session_id (UUID/fromString session-id)
          :plan_id (UUID/fromString result-id) :result_id (UUID/fromString result-id)
          :run_id (UUID/fromString result-id) :attempt_id (UUID/fromString result-id)
          :track_id "whisperx" :profile_id "whisperx-medium-refined-r1" :is_primary true
          :stage "refined" :unit_id "fixed-160000:160000:320000" :revision 1
          :source {:source-id result-id} :status "succeeded" :error_code nil
          :capabilities {:segment_timestamps true :word_timestamps true :speaker_labels true}
          :degradations [] :provenance {:private "not-public"}})

(deftest index-uses-frozen-labels-and-includes-pending-tabs
  (let [index (http/track-index session [row (assoc row :profile_id "other") (assoc row :track_id "unselected")])]
    (is (m/validate schemas/TrackIndexResponse index))
    (is (= 2 (count (:tracks index))))
    (is (= "Original label" (get-in index [:tracks 0 :display_name])))
    (is (= 1 (count (get-in index [:tracks 0 :results]))))
    (is (= [] (get-in index [:tracks 1 :results])))
    (is (not (contains? (get-in index [:tracks 0 :results 0]) :provenance)))))

(deftest exact-artifact-scope-rejects-urls-paths-and-other-tenants
  (let [config {:s3 {:buckets {:recordings {:bucket "test-recordings"}}}}
        key (str "refined-tracks/" tenant "/" session-id "/" result-id "/whisperx/" result-id "/" result-id ".transcript.json")
        valid (assoc row :result_uri (str "s3://test-recordings/" key))]
    (is (= {:bucket "test-recordings" :key key} (http/artifact-key config valid)))
    (doseq [invalid [(assoc valid :result_uri "http://127.0.0.1/private")
                     (assoc valid :result_uri "s3://other/private")
                     (assoc valid :track_id "../other")
                     (assoc valid :source {:source-id "../other"})
                     (assoc valid :tenant_id session-id)]]
      (is (thrown? clojure.lang.ExceptionInfo (http/artifact-key config invalid))))))

(deftest handler-checks-ownership-and-selection-before-storage
  (let [deps {:db {:ds :fixture}}
        req {:auth/tenant-id tenant :path-params {:session_id session-id :result_id result-id}}
        reads (atom 0)]
    (with-redefs [db/session (fn [_ owner _] (when (= (str owner) tenant) session))
                  db/result (fn [& _] row)
                  http/read-transcript! (fn [& _] (swap! reads inc) {:full_text "Fixture" :segments [] :lang "en"})]
      (is (= 404 (:status ((http/result-handler deps) (assoc req :auth/tenant-id session-id)))))
      (is (zero? @reads))
      (is (= 403 (:status ((http/result-handler deps) (dissoc req :auth/tenant-id)))))
      (is (= 200 (:status ((http/result-handler deps) req))))
      (is (= 1 @reads))
      (with-redefs [db/result (fn [& _] (assoc row :profile_id "not-selected"))]
        (is (= 404 (:status ((http/result-handler deps) req))))
        (is (= 1 @reads)))
      (with-redefs [db/result (fn [& _] (assoc row :status "failed" :error_code "provider_failed"))]
        (is (= "provider_failed" (get-in ((http/result-handler deps) req) [:body :result :error_code])))
        (is (= 1 @reads))))))

(deftest router-encodes-track-responses-and-enforces-tenant-identity
  (let [handler (router/create-router {:config {:auth {:required? true}} :db {:ds :fixture}})
        request {:request-method :get :headers {"accept" "application/json"}}
        owner (atom tenant)]
    (with-redefs [oidc/extract-token (fn [& _] "test-token")
                  oidc/verify-token (fn [& _] {:sub "fixture"})
                  oidc/extract-tenant-from-claims* (fn [& _] @owner)
                  db/session (fn [_ tenant-id _] (when (= tenant (str tenant-id)) session))
                  db/results (fn [& _] [row])
                  db/result (fn [& _] row)
                  http/read-transcript! (fn [& _] {:full_text "Fixture" :segments [] :lang "en"})]
      (doseq [path [(str "/api/sessions/" session-id "/tracks")
                    (str "/api/sessions/" session-id "/track-results/" result-id)]]
        (let [response (handler (assoc request :uri path))
              body (json/parse-stream (io/reader (:body response)) true)]
          (is (= 200 (:status response)))
          (is (:ok body))
          (is (re-find #"application/json" (get-in response [:headers "Content-Type"])))
          (is (= "private, no-store" (get-in response [:headers "Cache-Control"])))))
      (reset! owner session-id)
      (is (= 404 (:status (handler (assoc request :uri (str "/api/sessions/" session-id "/tracks")))))))))

(deftest recording-history-keeps-nested-frozen-controls-on-the-wire
  (let [handler (router/create-router {:config {:auth {:required? true}} :db {:ds :fixture}})]
    (with-redefs [oidc/extract-token (fn [& _] "test-token")
                  oidc/verify-token (fn [& _] {:sub "fixture"})
                  oidc/extract-tenant-from-claims* (fn [& _] tenant)
                  recordings/find-session-by-id (fn [& _] (assoc session :id session-id))
                  recordings/find-latest-recording (fn [& _] nil)
                  recordings/list-transcript-records (fn [& _] [])
                  outcomes/list-latest-outcomes-for-session (fn [& _] [])
                  workflows/list-latest-results-for-session (fn [& _] [])]
      (let [response (handler {:request-method :get :uri (str "/api/recordings/" session-id)
                               :headers {"accept" "application/json"}})
            body (json/parse-stream (io/reader (:body response)) true)]
        (is (= 200 (:status response)))
        (is (= plan (get-in body [:session :stream_controls :asr_plan])))))))
