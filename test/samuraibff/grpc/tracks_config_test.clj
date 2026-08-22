(ns samuraibff.grpc.tracks-config-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.system :as system]))

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
