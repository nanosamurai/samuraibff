(ns samuraibff.async-tracks-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [samuraibff.async-tracks :as tracks]
            [samuraibff.final-tracks :as plans]
            [samuraibff.stream-controls :as controls]))

(def tenant "00000000-0000-0000-0000-000000000001")
(def other-tenant "00000000-0000-0000-0000-000000000002")
(def primary {:track_id "whisperx" :profile_id "whisperx-medium-final-r1" :primary true})
(def secondary {:track_id "shadow" :profile_id "test-final-r1" :primary false
                :display_name "Comparison" :default_selected false :tenant_ids [tenant]})
(def config {:final-tracks {:enabled? true :test-profile-enabled? true
                            :selections-json (json/generate-string [primary secondary])}
             :refinement-tracks {:enabled? true}})

(deftest catalog-is-permitted-sanitized-and-distinct-from-defaults
  (let [visible (filter #(= "final" (:stage %)) (tracks/public-catalog config tenant))]
    (is (= ["whisperx" "shadow"] (mapv :track_id visible)))
    (is (= [true false] (mapv :default_selected visible)))
    (is (every? #(not (contains? % :tenant_ids)) visible)))
  (is (= ["whisperx"] (mapv :track_id (tracks/permitted config :final-tracks other-tenant))))
  (is (= [primary] (tracks/resolve-selection config :final-tracks tenant nil)))
  (is (= [primary (select-keys secondary [:track_id :profile_id :primary])]
         (tracks/resolve-selection config :final-tracks tenant ["shadow" "whisperx"])))
  (is (= [] (tracks/public-catalog {} tenant))))

(deftest reject-forbidden-unknown-duplicate-and-empty
  (doseq [[owner ids] [[tenant []] [tenant ["whisperx" "unknown"]]
                       [tenant ["whisperx" "whisperx"]] [other-tenant ["whisperx" "shadow"]]
                       [tenant ["whisperx" "a" "b" "c" "d"]]]]
    (is (thrown? clojure.lang.ExceptionInfo (tracks/resolve-selection config :final-tracks owner ids))))
  (doseq [entry [(assoc primary :endpoint "http://untrusted")
                 (assoc primary :default_selected false)
                 (assoc primary :tenant_ids [tenant])
                 (assoc primary :display_name "")]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (tracks/catalog {:final-tracks {:selections-json (json/generate-string [entry])}} :final-tracks)))))

(deftest selected-provider-becomes-compatibility-output
  (is (= [{:track_id "shadow" :profile_id "test-final-r1" :primary true}]
         (tracks/resolve-selection config :final-tracks tenant ["shadow"])))
  (is (thrown? clojure.lang.ExceptionInfo
               (tracks/resolve-selection config :final-tracks other-tenant ["shadow"])))
  (let [plan (plans/new-plan config tenant other-tenant {:final true :final_tracks ["shadow"]})]
    (is (= [{:track_id "shadow" :profile_id "test-final-r1" :primary true}] (:final_tracks plan)))))

(deftest independent-stage-selection-preserves-worker-contract
  (let [params {"final_tracks" "whisperx,shadow" "refinement_tracks" "whisperx"}
        selected (tracks/requested-controls config params {:refined true :final true :store_recording true})
        plan (plans/new-plan config tenant other-tenant selected)]
    (is (= ["whisperx" "shadow"] (mapv :track_id (:final_tracks plan))))
    (is (= ["whisperx"] (mapv :track_id (:refinement_tracks plan))))
    (is (every? #(= #{:track_id :profile_id :primary} (set (keys %))) (:final_tracks plan))))
  (let [parsed (controls/parse-and-validate {"final" "false" "realtime" "false"} [] true)
        plan (plans/new-plan config tenant other-tenant parsed)]
    (is (:store_recording parsed))
    (is (= [] (:final_tracks plan)))
    (is (= 1 (count (:refinement_tracks plan)))))
  (doseq [params [{"final_tracks" ""} {"final_tracks" "whisperx,"}
                  {"final_tracks" "whisperx,whisperx"} {"final_tracks" "../whisperx"}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (tracks/requested-controls config params {:final true}))))
  (is (thrown? clojure.lang.ExceptionInfo
               (tracks/requested-controls config {"final_tracks" "whisperx"} {:final false})))
  (is (thrown? clojure.lang.ExceptionInfo
               (tracks/requested-controls {} {"final_tracks" "whisperx"} {:final true}))))
