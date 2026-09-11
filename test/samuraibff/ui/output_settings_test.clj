(ns samuraibff.ui.output-settings-test
  (:require [clojure.test :refer [deftest is]]
            [samuraibff.ui.output-settings :as settings]))

(def catalog
  "Two providers, one selected by default."
  [{:track_id "whisperx" :default_selected true}
   {:track_id "other" :default_selected false}])

(deftest clearing-last-track-disables-and-toggle-restores
  (doseq [stage [:realtime :refined :final]]
    (let [controls {stage true}
          both (settings/select-track controls stage catalog "other" true)
          only-other (settings/select-track both stage catalog "whisperx" false)
          empty (settings/select-track only-other stage catalog "other" false)
          restored (settings/toggle-stage empty stage catalog true)]
      (is (= ["other"] (settings/selected-ids only-other stage catalog)))
      (is (settings/enabled? only-other stage catalog))
      (is (false? (settings/enabled? empty stage catalog)))
      (is (false? (get empty stage)))
      (is (= ["other"] (settings/selected-ids restored stage catalog))))))

(deftest explicit-empty-never-restores-defaults-at-admission
  (let [detail {:async_tracks (mapv #(assoc % :stage "refined") catalog)}
        controls {:refined true :refinement_tracks [] :final false :store_recording false}
        effective (settings/effective-controls controls detail)]
    (is (false? (:refined effective)))
    (is (false? (:store_recording effective)))))

(deftest direct-toggle-preserves-multiple-choices-and-audio-requirements
  (let [controls {:refined true :refinement_tracks ["whisperx" "other"] :store_recording false}
        off (settings/toggle-stage controls :refined catalog false)
        on (settings/toggle-stage off :refined catalog true)
        effective (settings/effective-controls on {:async_tracks (mapv #(assoc % :stage "refined") catalog)})]
    (is (= [] (:refinement_tracks off)))
    (is (= ["whisperx" "other"] (:refinement_tracks on)))
    (is (:store_recording effective))
    (is (not (contains? effective :remembered_tracks)))))

(deftest selecting-a-track-while-off-enables-only-that-track
  (let [off (settings/toggle-stage {:final true} :final catalog false)
        selected (settings/select-track off :final catalog "other" true)]
    (is (:final selected))
    (is (= ["other"] (:final_tracks selected)))))

(deftest changed-catalog-cannot-restore-unavailable-tracks
  (let [off {:final false :final_tracks [] :remembered_tracks {:final ["removed"]}}
        on (settings/toggle-stage off :final catalog true)]
    (is (= ["whisperx"] (:final_tracks on))))
  (is (false? (settings/enabled? {:final true :final_tracks ["removed"]} :final catalog))))

(deftest legacy-deployments-retain-output-switches
  (is (:final (settings/toggle-stage {:final false} :final [] true)))
  (is (false? (:refined (settings/effective-controls {:refined false} {})))))
