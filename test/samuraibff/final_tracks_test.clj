(ns samuraibff.final-tracks-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [samuraibff.final-tracks :as tracks]))

(deftest operator-catalog-is-closed
  (is (= [{:track_id "whisperx" :profile_id "whisperx-medium-final-r1" :primary true}]
         (tracks/selections {})))
  (let [primary {:track_id "whisperx" :profile_id "whisperx-medium-final-r1" :primary true}
        secondary {:track_id "shadow" :profile_id "test-final-r1" :primary false}]
    (is (= [primary] (tracks/selections {:final-tracks {:selections-json (json/generate-string [primary])}})))
    (is (= [primary secondary]
           (tracks/selections {:final-tracks {:selections-json (json/generate-string [primary secondary])
                                              :test-profile-enabled? true}}))))
  (doseq [selection [[{:track_id "evil" :profile_id "arbitrary" :primary true}]
                     [{:track_id "test" :profile_id "test-final-r1" :primary true}]
                     [{:track_id "whisperx" :profile_id "whisperx-medium-final-r1" :primary false}]
                     [{:track_id "whisperx" :profile_id "whisperx-medium-final-r1" :primary true :endpoint "bad"}]]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (tracks/selections {:final-tracks {:selections-json (json/generate-string selection)}})))))

(deftest plan-headers-match-python-canonical-order
  (let [tenant "00000000-0000-0000-0000-000000000001"
        session "00000000-0000-0000-0000-000000000002"
        plan-id "00000000-0000-0000-0000-000000000003"
        plan (assoc (tracks/new-plan {} tenant session) :plan_id plan-id)
        headers (tracks/kafka-headers plan)]
    (is (= (str "{\"final_tracks\":[{\"primary\":true,\"profile_id\":\"whisperx-medium-final-r1\",\"track_id\":\"whisperx\"}],"
                "\"plan_id\":\"" plan-id "\",\"schema_version\":1,\"session_id\":\"" session
                "\",\"tenant_id\":\"" tenant "\"}")
           (String. ^bytes (headers "x-asr-plan") "UTF-8")))
    (is (= "whisperx" (String. ^bytes (headers "x-final-track-ids") "UTF-8")))
    (is (= plan-id (String. ^bytes (headers "x-asr-plan-id") "UTF-8")))))

(deftest disabled-path-and-retention
  (testing "Feature-off and omitted final output do not create a plan"
    (is (nil? (tracks/freeze! nil nil {} nil nil {:final true} 16000)))
    (is (nil? (tracks/freeze! nil nil {:final-tracks {:enabled? true}} nil nil {:final false} 16000))))
  (testing "Unsupported retention is rejected before any database or Kafka access"
    (is (thrown? clojure.lang.ExceptionInfo
                 (tracks/freeze! nil nil {:final-tracks {:enabled? true}} nil nil
                                 {:final true :store_recording false} 16000)))))

(deftest refinement-selection-is-frozen-with-its-window-policy
  (let [tenant "00000000-0000-0000-0000-000000000001"
        session "00000000-0000-0000-0000-000000000002"
        config {:refinement-tracks {:enabled? true}}
        plan (tracks/new-plan config tenant session {:refined true :refinement_window_sec 10.0})]
    (is (tracks/enabled? config))
    (is (tracks/selected? config {:refined true}))
    (is (not (tracks/selected? config {:final true})))
    (is (= [] (:final_tracks plan)))
    (is (= 160000 (:refinement_window_samples plan)))
    (is (= "whisperx-medium-refined-r1" (get-in plan [:refinement_tracks 0 :profile_id])))
    (is (= "whisperx" (String. ^bytes (get (tracks/kafka-headers plan) "x-refinement-track-ids") "UTF-8")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (tracks/selections {:refinement-tracks {:selections-json "[{\"track_id\":\"x\",\"profile_id\":\"test-refined-r1\",\"primary\":true}]"}}
                                    :refinement-tracks)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (tracks/freeze! nil nil config nil nil {:refined true :store_recording false} 16000)))))
