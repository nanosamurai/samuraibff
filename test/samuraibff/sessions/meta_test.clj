(ns samuraibff.sessions.meta-test
  "Unit tests for sessions.meta contract composition."
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.sessions.meta :as sessions.meta])
  (:import
   (java.util UUID)))

(deftest build-sessions-meta-includes-routing-and-consolidation
  (testing "sessions.meta includes both routing and webhook_routing + refined_transcript.consolidation"
    (let [tenant-id (UUID/fromString "00000000-0000-0000-0000-000000000000")
          session-id (UUID/fromString "11111111-1111-1111-1111-111111111111")
          routing {:targets_by_event_type {"transcript.refined.segment" []}}
          session-settings {:refined_transcript {:consolidation {:enabled true}}}
          config {:features {:ce-mode? false}}
          meta (sessions.meta/build-sessions-meta config tenant-id session-id routing session-settings [])]
      (is (= (str tenant-id) (:tenant_id meta)))
      (is (= (str session-id) (:session_id meta)))
      (is (string? (:event_id meta)))

      (is (= routing (:routing meta)))
      (is (= routing (:webhook_routing meta)))

      (is (= true (get-in meta [:refined_transcript :consolidation :enabled])))
      (is (= 262144 (get-in meta [:refined_transcript :consolidation :max_bytes])))
      (is (= "transcripts.refined.consolidated" (get-in meta [:refined_transcript :consolidation :topic])))
       (is (= true (get-in meta [:refined_transcript :consolidation :include_full_text]))))))

(deftest build-sessions-meta-omits-workflow-webhook-routing-in-ce
  (testing "sessions.meta omits workflow/webhook routing fields in default CE mode"
    (let [tenant-id (UUID/fromString "00000000-0000-0000-0000-000000000000")
          session-id (UUID/fromString "11111111-1111-1111-1111-111111111111")
          routing {:targets_by_event_type {"transcript.refined.segment" [{:id "x"}]}}
          meta (sessions.meta/build-sessions-meta {} tenant-id session-id routing nil [{:workflow_id "x"}])]
      (is (= (str tenant-id) (:tenant_id meta)))
      (is (= (str session-id) (:session_id meta)))
      (is (not (contains? meta :routing)))
      (is (not (contains? meta :webhook_routing)))
      (is (not (contains? meta :workflows)))
      (is (= false (get-in meta [:refined_transcript :consolidation :enabled]))))))

(deftest build-sessions-meta-follows-each-capability-flag
  (let [tenant-id (UUID/fromString "00000000-0000-0000-0000-000000000000")
        session-id (UUID/fromString "11111111-1111-1111-1111-111111111111")
        routing {:targets_by_event_type {"transcript.refined.segment" [{:id "x"}]}}
        targets [{:workflow_id "x"}]
        build #(sessions.meta/build-sessions-meta {:features %} tenant-id session-id routing nil targets)]
    (testing "webhooks alone publish webhook routing but no workflow targets"
      (let [meta (build {:webhooks-enabled? true})]
        (is (= routing (:routing meta)))
        (is (= routing (:webhook_routing meta)))
        (is (not (contains? meta :workflows)))))
    (testing "workflow triggers alone publish workflow targets but no webhook routing"
      (let [meta (build {:workflows-triggered-enabled? true})]
        (is (= {:targets targets} (:workflows meta)))
        (is (not (contains? meta :routing)))
        (is (not (contains? meta :webhook_routing)))))
    (testing "on-demand workflows publish neither"
      (let [meta (build {:workflows-ondemand-enabled? true})]
        (is (not-any? #(contains? meta %) [:routing :webhook_routing :workflows]))))))
