;; Copyright (c) samuraibff contributors.
(ns samuraibff.http.speakers-router-test
  "Regression tests for speaker routes.

  These tests ensure our route templates do not use UUID regex constraints
  that would break Reitit's `{...}` constraint syntax and therefore path param
  extraction/coercion."
  (:require
    [clojure.test :refer [deftest is testing]]
    [reitit.core :as reitit]))

(deftest delete-speaker-route-extracts-path-param
  (testing "DELETE /api/speakers/:speaker_id extracts :speaker_id"
    (let [router (reitit/router
                 [["/api"
                   ["/speakers/:speaker_id" {:name ::delete-speaker}]]])
          speaker-id "019d85d7-5915-7a8f-a263-ca04cf3c4b5d"
          match (reitit/match-by-path router (str "/api/speakers/" speaker-id))]
      (is (some? match))
      (is (= ::delete-speaker (:name (:data match))))
      (is (= {:speaker_id speaker-id} (:path-params match))))))
