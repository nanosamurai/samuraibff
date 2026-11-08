(ns samuraibff.ui.retranscribe-form-test
  "Unit tests for the Re-transcribe panel rules."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [samuraibff.ui.retranscribe-form :as form]))

(def ^:private detail
  "An `/api/me` detail with two final tracks; WhisperX is the default."
  {:async_tracks [{:stage "final" :track_id "whisperx" :display_name "WhisperX" :default_selected true}
                  {:stage "final" :track_id "parakeet" :display_name "Parakeet" :default_selected false}]})

(deftest default-title-test
  (is (= "Interview (re-transcribed)" (form/default-title " Interview ")))
  (is (= "Session (re-transcribed)" (form/default-title nil)))
  (let [title (form/default-title (apply str (repeat 300 "x")))]
    (is (= 200 (count title)))
    (is (str/ends-with? title " (re-transcribed)"))))

(deftest initial-form-test
  (testing "the source session's tracks and recording language are preselected"
    (is (= {:title "Interview (re-transcribed)" :lang "pt-BR"
            :controls {:realtime false :refined false :final true :store_recording true :final_tracks ["parakeet"]}}
           (form/initial-form {:stream_controls {:final_tracks ["parakeet"]} :recording_lang "pt-BR"}
                              "Interview" detail))))
  (testing "tracks that are no longer offered fall back to the defaults"
    (is (nil? (get-in (form/initial-form {:stream_controls {:final_tracks ["removed"]}} "x" detail)
                      [:controls :final_tracks])))
    (is (= "" (:lang (form/initial-form {} "x" detail))) "no stored language means detect")))

(deftest request-body-test
  (testing "the body carries the chosen values"
    (is (= {:title "Again" :lang "" :final_tracks ["whisperx" "parakeet"] :store_recording true}
           (form/request-body {:title " Again " :lang ""
                               :controls {:final true :store_recording false :final_tracks ["whisperx" "parakeet"]}}
                              detail))
        "more than one final transcript always keeps audio"))
  (testing "a blank title lets the server choose"
    (is (not (contains? (form/request-body {:title "  " :lang "en" :controls {}} detail) :title))))
  (testing "without configured tracks the server default is used"
    (is (not (contains? (form/request-body {:title "x" :lang "" :controls {}} {}) :final_tracks)))))

(deftest availability-test
  (is (= :available (form/availability {:can_retranscribe true})))
  (is (= :unavailable (form/availability {:can_retranscribe false :retranscribe_unavailable "recording-not-available"
                                          :access {:can_manage true}})))
  (is (nil? (form/availability {:can_retranscribe false :retranscribe_unavailable "recording-not-available"
                                :access {:can_manage false}}))
      "readers see nothing")
  (is (nil? (form/availability {:can_retranscribe false :access {:can_manage true}}))
      "other reasons hide the control"))

(deftest error-message-test
  (is (re-find #"still recording" (form/error-message 409 "session-not-finished")))
  (is (= form/unavailable-text (form/error-message 409 "recording-not-available")))
  (is (re-find #"too long" (form/error-message 400 "invalid-title")))
  (is (re-find #"turned off" (form/error-message 503 "uploads-disabled")))
  (is (re-find #"reach the server" (form/error-message nil nil)))
  (is (string? (form/error-message 500 "retranscribe-failed"))))
