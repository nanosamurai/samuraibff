(ns samuraibff.final-tracks-integration-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [samuraibff.db.sessions :as sessions]
            [samuraibff.final-tracks :as tracks]
            [samuraibff.kafka.producer :as producer]
            [samuraibff.sessions.meta :as meta]
            [samuraibff.testcontainers.postgres :as tc])
  (:import (java.util UUID)))

(deftest freeze-mirror-retry-and-preserve-inception-settings
  (tc/with-postgres [pg]
    (let [ds (tc/datasource (tc/jdbc-url pg) "drsynth" "drsynth")
          tenant (UUID/randomUUID) session (UUID/randomUUID)
          config {:final-tracks {:enabled? true} :features {:ce-mode? false}}
          controls {:final true :store_recording true}
          initial (meta/build-sessions-meta config tenant session
                                            {:targets_by_event_type {"transcript.final.ready" [{:webhook_id "fixture"}]}}
                                            {:refined_transcript {:consolidation {:enabled true}}}
                                            [{:workflow_id "fixture"}])
          publications (atom [])]
      (tc/apply-schema! ds)
      (jdbc/execute! ds [(slurp (io/resource "migrations/0010_sessions_asr_meta_snapshot.up.sql"))])
      (jdbc/execute! ds ["INSERT INTO tenants(id,name) VALUES (?,?)" tenant "fixture"])
      (jdbc/execute! ds ["INSERT INTO sessions(id,tenant_id,session_key,started_at,status) VALUES (?,?,?,NULL,'created')"
                         session tenant (str session)])
      (tracks/save-meta-snapshot! ds config tenant session initial)
      (with-redefs [producer/send-sessions-meta! (fn [_ _ snapshot _] (swap! publications conj snapshot))]
        (let [plan (tracks/freeze! ds :fixture config (str tenant) (str session) controls 16000)
              mirror (first @publications)]
          (is (= plan (:asr_plan mirror)))
          (is (= (json/parse-string (json/generate-string (dissoc initial :event_id)))
                 (json/parse-string (json/generate-string (dissoc mirror :event_id :asr_plan)))))
          (is (= plan (tracks/freeze! ds :fixture config (str tenant) (str session) controls 16000)))
          (is (= {:updated? false} (sessions/update-session-stream-controls! ds tenant session {:final false})))
          (is (= {:updated? false} (sessions/activate-session-on-audio-start-with-controls!
                                    ds tenant session {:final false})))
          (is (= plan (tracks/freeze! ds :fixture config (str tenant) (str session) controls 16000)))
          (is (thrown? clojure.lang.ExceptionInfo
                       (tracks/freeze! ds :fixture config (str tenant) (str session)
                                       (assoc controls :store_recording false) 16000)))
          (is (thrown? clojure.lang.ExceptionInfo
                       (tracks/freeze! ds :fixture config (str tenant) (str session)
                                       {:final false :store_recording false} 16000)))
          (is (thrown? clojure.lang.ExceptionInfo
                       (tracks/freeze! ds :fixture {} (str tenant) (str session) controls 16000)))
          (is (thrown? clojure.lang.ExceptionInfo
                       (tracks/freeze! ds :fixture config (str (UUID/randomUUID)) (str session) controls 16000)))
          (is (= plan (json/parse-string (String. ^bytes (get (tracks/kafka-headers plan) "x-asr-plan") "UTF-8") true))))))))
