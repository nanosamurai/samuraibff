(ns samuraibff.ui.upload-form-test
  "Unit tests for the Upload page form rules."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [samuraibff.ui.output-settings :as settings]
            [samuraibff.ui.upload-form :as upload-form]))

(def ^:private detail
  "An `/api/me` detail with two final tracks; WhisperX is the default."
  {:async_tracks [{:stage "final" :track_id "whisperx" :display_name "WhisperX" :default_selected true}
                  {:stage "final" :track_id "parakeet" :display_name "Parakeet" :default_selected false}
                  {:stage "refined" :track_id "whisperx" :display_name "WhisperX" :default_selected true}]})

(deftest format-bytes-test
  (testing "small sizes count bytes"
    (is (= "0 bytes" (upload-form/format-bytes 0)))
    (is (= "1 byte" (upload-form/format-bytes 1)))
    (is (= "1023 bytes" (upload-form/format-bytes 1023))))
  (testing "larger sizes use 1024-based units with at most one decimal"
    (is (= "1 KB" (upload-form/format-bytes 1024)))
    (is (= "1.5 KB" (upload-form/format-bytes 1536)))
    (is (= "12 MB" (upload-form/format-bytes (* 12.3 1024 1024))))
    (is (= "128 MB" (upload-form/format-bytes 134217728)))
    (is (= "1.2 GB" (upload-form/format-bytes (* 1.2 1024 1024 1024))))))

(deftest title-from-file-name-test
  (is (= "interview" (upload-form/title-from-file-name "interview.m4a")))
  (is (= "team call.final" (upload-form/title-from-file-name "team call.final.mp4")))
  (is (= "notes" (upload-form/title-from-file-name "C:\\fakepath\\notes.wav")))
  (is (= "README" (upload-form/title-from-file-name "README")))
  (is (= ".m4a" (upload-form/title-from-file-name ".m4a")))
  (is (= "" (upload-form/title-from-file-name nil)))
  (is (= upload-form/max-title-length
         (count (upload-form/title-from-file-name (str (str/join (repeat 300 "a")) ".mp3"))))))

(deftest too-large-test
  (is (true? (upload-form/too-large? 2049 2048)))
  (is (false? (upload-form/too-large? 2048 2048)))
  (is (false? (upload-form/too-large? 10 0)))
  (is (false? (upload-form/too-large? 10 nil))))

(deftest media-type-accepted-test
  (doseq [media-type ["" nil "audio/mpeg" "video/mp4" "Video/WebM" "application/ogg" "application/octet-stream"]]
    (is (true? (upload-form/media-type-accepted? media-type)) (pr-str media-type)))
  (doseq [media-type ["application/pdf" "image/png" "text/plain"]]
    (is (false? (upload-form/media-type-accepted? media-type)) media-type)))

(deftest form-fields-test
  (testing "untouched choices leave the default tracks to the server"
    (is (= [["title" "Interview"] ["store_recording" "true"]]
           (upload-form/form-fields {:title "  Interview " :lang "" :controls upload-form/initial-controls}
                                    detail))))
  (testing "a language and an explicit track choice are sent"
    (let [controls (settings/select-track upload-form/initial-controls :final
                                          (settings/entries detail :final) "parakeet" true)]
      (is (= [["lang" "cs"] ["final_tracks" "whisperx,parakeet"] ["store_recording" "true"]]
             (upload-form/form-fields {:title "" :lang "cs" :controls (assoc controls :store_recording false)}
                                      detail)))))
  (testing "one track may drop the audio"
    (is (= [["store_recording" "false"]]
           (upload-form/form-fields {:controls (assoc upload-form/initial-controls :store_recording false)}
                                    detail))))
  (testing "without configured final tracks no track list is sent"
    (is (= [["store_recording" "true"]]
           (upload-form/form-fields {:controls (assoc upload-form/initial-controls :final_tracks ["x"])} {})))))

(deftest tracks-chosen-test
  (is (true? (upload-form/tracks-chosen? upload-form/initial-controls detail)))
  (is (= ["whisperx"] (upload-form/selected-final-tracks upload-form/initial-controls detail)))
  (let [cleared (settings/select-track upload-form/initial-controls :final
                                       (settings/entries detail :final) "whisperx" false)]
    (is (false? (upload-form/tracks-chosen? cleared detail))))
  (is (true? (upload-form/tracks-chosen? {:final_tracks []} {}))))

(deftest progress-percent-test
  (is (= 0 (upload-form/progress-percent 0 0)))
  (is (= 42 (upload-form/progress-percent 42.9 100)))
  (is (= 100 (upload-form/progress-percent 120 100)))
  (is (= 0 (upload-form/progress-percent nil 100))))

(deftest error-message-test
  (is (str/includes? (upload-form/error-message 413 "file-too-large" 134217728) "(128 MB)"))
  (is (not (str/includes? (upload-form/error-message 413 nil 0) "(")))
  (is (str/includes? (upload-form/error-message nil nil 0) "connection"))
  (is (str/includes? (upload-form/error-message 400 "empty-file" 0) "empty"))
  (is (str/includes? (upload-form/error-message 400 "invalid-multipart" 0) "could not be read"))
  (is (str/includes? (upload-form/error-message 403 "forbidden" 0) "permission"))
  (is (str/includes? (upload-form/error-message 503 "uploads-disabled" 0) "turned off"))
  (is (str/includes? (upload-form/error-message 500 "upload-failed" 0) "failed")))
