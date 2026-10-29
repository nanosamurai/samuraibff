(ns samuraibff.http.final-tracks-integration-test
  "Final/refined track selection, frozen stream controls and transcript history.

  Recording detail is read as the provisioned session owner. A foreign-tenant
  principal and a same-tenant non-owner both get 404 for the private session."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [samuraibff.db.sessions :as sessions]
            [samuraibff.http.recordings :as recordings]
            [samuraibff.sessions.meta :as meta]
            [samuraibff.stream-controls :as controls]
            [samuraibff.test-support.principals :as tp]
            [samuraibff.testcontainers.postgres :as pg])
  (:import (java.util UUID)))

(deftest final-selection-and-history-test
  (pg/with-postgres [container]
    (let [ds (pg/datasource (pg/jdbc-url container) "drsynth" "drsynth")
          tenant (UUID/randomUUID)
          foreign (UUID/randomUUID)
          session (UUID/randomUUID)
          _ (pg/apply-schema! ds)
          _ (tp/insert-tenant! ds tenant "tracks")
          _ (tp/insert-tenant! ds foreign "foreign")
          owner (tp/insert-principal! ds {:tenant-id tenant})
          config {:features {:ce-mode? true}}
          selected (controls/with-track-labels (controls/parse-and-validate
                                                {:final_tracks "test-shadow,whisperx" :refinement_tracks "test-shadow,whisperx"
                                                 :realtime "true" :refined "true"
                                                 :realtime_settings "{\"nemotron\":{\"endpointing_silence_ms\":800},\"unselected\":{\"ignored\":1}}"}
                                                ["nemotron"] ["whisperx" "test-shadow"] ["whisperx" "test-shadow"])
                     {:final-tracks ["whisperx" "test-shadow"]
                      :refinement-tracks ["whisperx" "test-shadow"]
                      :track-labels {:final {:test-shadow "Original label"}}})]
      (is (= {:nemotron {:endpointing_silence_ms 800}} (:realtime_settings selected)))
      (let [id (UUID/randomUUID)
            defaults (controls/parse-and-validate {} ["faster" "nemotron"]
                                                  ["whisperx" "test-shadow"] ["whisperx" "test-shadow"]
                                                  {:realtime "nemotron" :refined "test-shadow" :final "test-shadow"})]
        (sessions/insert-session! ds {:id id :tenant-id tenant :user-id (:id owner) :session-key (str id) :status "created"})
        (is (= [["nemotron"] ["test-shadow"] ["test-shadow"]]
               (mapv defaults [:realtime_tracks :refinement_tracks :final_tracks])))
        (is (= defaults (sessions/activate-session-on-audio-start-with-controls! ds tenant id defaults)))
        (is (= defaults (sessions/activate-session-on-audio-start-with-controls!
                         ds tenant id (controls/parse-and-validate {} ["faster"])))))
      (sessions/insert-session! ds {:id session :tenant-id tenant :user-id (:id owner)
                                    :session-key (str session) :status "created"})
      (is (= selected (sessions/activate-session-on-audio-start-with-controls! ds tenant session selected)))
      (is (= selected (sessions/activate-session-on-audio-start-with-controls!
                       ds tenant session (controls/with-track-labels
                                           (controls/parse-and-validate {})
                                           {:track-labels {:final {:whisperx "Changed label"}}}))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (sessions/activate-session-on-audio-start-with-controls! ds foreign session selected)))
      (is (= (str tenant) (:tenant_id (meta/resolve-sessions-meta config ds tenant session))))
      (doseq [stage ["final" "refined"]
              [track text] [[nil "historical"] ["whisperx" "speech"] ["test-shadow" "synthetic"]]]
        (jdbc/execute! ds
                       ["INSERT INTO session_transcripts
                          (id,session_id,tenant_id,source,type,model,track_id,full_text,segments)
                        VALUES (?, ?, ?, 'test-worker', ?, 'test-model', ?, ?, '[]'::jsonb)"
                        (UUID/randomUUID) session tenant stage track text]))
      (let [foreign-admin (tp/insert-principal! ds {:tenant-id foreign :role :admin})
            colleague (tp/insert-principal! ds {:tenant-id tenant :role :admin})
            handler (recordings/get-recording-handler {:db {:ds ds} :config config})
            params {:path-params {:session_id (str session)}}
            request (tp/request-for owner params)
            all (:body (handler request))
            filtered (:body (handler (assoc request :query-params {"track_id" "test-shadow"})))]
        (is (= 3 (count (get-in all [:transcripts :final]))))
        (is (= 3 (count (get-in all [:transcripts :refined]))))
        (is (= ["test-shadow"] (mapv :track_id (get-in filtered [:transcripts :refined]))))
        (is (= ["synthetic"] (mapv :full_text (get-in filtered [:transcripts :final]))))
        (is (= ["test-shadow"] (mapv :track_id (get-in filtered [:transcripts :final]))))
        (is (= selected (get-in all [:session :stream_controls])))
        (is (= 404 (:status (handler (tp/request-for foreign-admin params)))))
        (testing "a same-tenant Admin who is not the owner cannot open a private session"
          (is (= 404 (:status (handler (tp/request-for colleague params))))))))))
