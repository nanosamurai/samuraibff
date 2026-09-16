(ns samuraibff.ui.output-settings-test
  (:require [clojure.test :refer [deftest is]]
            [samuraibff.ui.output-settings :as settings]))

(deftest service-defaults-and-selected-overrides-test
  (let [detail {:realtime_tracks ["faster" "nemotron"]
                :realtime_track_capabilities [{:id "faster" :session_settings {:window_sec {:default 10}
                                                                               :overlap_sec {:default 1}
                                                                               :partial_enable {:default true}}}
                                              {:id "nemotron" :session_settings {:endpointing_silence_ms {:default 2000}}}]}
        controls {:realtime_tracks ["faster"]
                  :realtime_settings {:faster {:overlap_sec 0 :partial_enable false :unknown 1}
                                      :nemotron {:endpointing_silence_ms 800}}}]
    (is (= {:faster {:window_sec 10 :overlap_sec 0 :partial_enable false}}
           (:realtime_settings (settings/effective-controls controls detail))))
    (is (= {} (:realtime_settings (settings/effective-controls (assoc controls :realtime false) detail))))))

(deftest independent-selection-and-retention-test
  (let [entries [{:track_id "whisperx" :default_selected true}
                 {:track_id "alternative" :default_selected false}]
        detail {:async_tracks (vec (for [stage ["refined" "final"] entry entries]
                                     (assoc entry :stage stage)))}
        initial {:refined true :final true :store_recording false}
        cleared (settings/select-track initial :final entries "whisperx" false)
        restored (settings/toggle-stage cleared :final entries true)
        alternative (settings/select-track cleared :final entries "alternative" true)
        multiple (settings/select-track alternative :final entries "whisperx" true)]
    (is (false? (:final cleared)))
    (is (true? (:refined cleared)))
    (is (= ["whisperx"] (:final_tracks restored)))
    (is (= ["alternative"] (:final_tracks alternative)))
    (is (false? (:store_recording (settings/effective-controls alternative detail))))
    (is (true? (:store_recording (settings/effective-controls multiple detail))))
    (is (false? (:store_recording (settings/effective-controls
                                   (assoc initial :final false) detail))))))
