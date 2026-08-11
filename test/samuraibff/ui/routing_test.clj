(ns samuraibff.ui.routing-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.ui.routing :as routing]))

(deftest navigation-href-test
  (testing "HTTP navigation keeps normal application paths"
    (is (= "/live" (routing/navigation-href "http:" "/live")))
    (is (= "/recordings" (routing/navigation-href "https:" "/recordings"))))
  (testing "packaged Electron navigation uses hash routes"
    (is (= "#/live" (routing/navigation-href "file:" "/live")))
    (is (= "#/recordings" (routing/navigation-href "file:" "/recordings")))))

(deftest location-route-path-test
  (testing "HTTP navigation reads the location pathname"
    (is (= "/live"
           (routing/location-route-path "http:" "/live" ""))))
  (testing "packaged Electron navigation reads a route hash"
    (is (= "/live"
           (routing/location-route-path "file:"
                                        "/C:/app/resources/public/index.html"
                                        "#/live"))))
  (testing "packaged Electron defaults to recordings without a route hash"
    (is (= "/recordings"
           (routing/location-route-path "file:"
                                        "/C:/app/resources/public/index.html"
                                        "")))
    (is (= "/recordings"
           (routing/location-route-path "file:"
                                        "/C:/app/resources/public/index.html"
                                        "#invalid")))))
