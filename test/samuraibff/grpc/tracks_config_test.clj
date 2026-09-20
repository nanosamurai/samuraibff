(ns samuraibff.grpc.tracks-config-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [integrant.core :as ig]
   [samuraibff.config]
   [samuraibff.system :as system]))

(deftest default-tracks-env-test
  (let [env {"SAMURAIBFF_GRPC_REALTIME_TRACKS" "faster=rtservice:50052,qwen=qwen:50052"
             "SAMURAIBFF_REFINEMENT_TRACKS" "whisperx,qwen"
             "SAMURAIBFF_FINAL_TRACKS" "whisperx,qwen"
             "SAMURAIBFF_DEFAULT_REALTIME_TRACK" " qwen "
             "SAMURAIBFF_DEFAULT_REFINEMENT_TRACK" "qwen"
             "SAMURAIBFF_DEFAULT_FINAL_TRACK" "whisperx"}
        config (:samuraibff/config (#'system/apply-env-overrides {} env))]
    (is (= {:realtime "qwen" :refined "qwen" :final "whisperx"} (:default-tracks config)))
    (is (= config (ig/init-key :samuraibff/config config)))
    (doseq [stage [:realtime :refined :final]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Default track must be configured"
                            (ig/init-key :samuraibff/config
                                         (assoc-in config [:default-tracks stage] "unconfigured")))))
    (is (nil? (get-in (#'system/apply-env-overrides {} {"SAMURAIBFF_DEFAULT_REALTIME_TRACK" " "})
                     [:samuraibff/config :default-tracks])))))

(deftest realtime-tracks-env-test
  (testing "multiple fixed tracks replace the default realtime track list"
    (let [cfg {:samuraibff/config {:grpc {:realtime-tracks [{:id "default"
                                                              :address "localhost:50052"}]}}}
          env {"SAMURAIBFF_GRPC_REALTIME_TRACKS" "faster=rtservice:50052,qwen=qwen-rtservice:50052"}
          configured (#'system/apply-env-overrides cfg #(get env %))]
      (is (= [{:id "faster" :address "rtservice:50052"}
              {:id "qwen" :address "qwen-rtservice:50052"}]
             (get-in configured [:samuraibff/config :grpc :realtime-tracks])))))

  (testing "malformed and duplicate operator entries are rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"expected track-id=host:port"
         (#'system/parse-realtime-tracks "qwen=http://untrusted.example")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"must be unique"
         (#'system/parse-realtime-tracks "qwen=one:50052,qwen=two:50052")))))
