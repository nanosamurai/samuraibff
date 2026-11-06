(ns samuraibff.ui.urls-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.ui.urls :as urls]))

(deftest api-url-test
  (testing "browser and Electron renderers use a same-origin API path"
    (is (= "/api/recordings"
           (urls/api-url "" "/api/recordings"))))
  (testing "the helper still supports an explicit base for non-renderer clients"
    (is (= "https://app.example/api/recordings"
           (urls/api-url "https://app.example" "/api/recordings")))))

(deftest recording-audio-url-test
  (testing "recording playback targets the BFF instead of a file URL"
    (is (= "/api/recordings/session%20one/audio"
           (urls/recording-audio-url "" "session one")))
    (is (= "https://app.example/api/recordings/session%20one/audio"
           (urls/recording-audio-url "https://app.example" "session one")))))

(deftest recording-export-url-test
  (testing "export URLs encode the session, format, stage and track"
    (is (= "/api/recordings/session%20one/export?format=srt&stage=final&track_id=test%26shadow"
           (urls/recording-export-url "" "session one" {:format "srt" :stage "final" :track-id "test&shadow"})))))
