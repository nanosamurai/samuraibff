(ns samuraibff.http.ui-test
  "Unit tests for UI-related HTTP handlers." 
  (:require
    [clojure.test :refer :all]
    [cheshire.core :as cheshire]
    [samuraibff.http.ui :as http.ui]))

(deftest create-session-handler-test
  (testing "POST /api/sessions returns a uuid"
    (let [ws-registry {:config {:env :test}
                       :sessions (atom {})}
          handler (http.ui/create-session-handler {:config {:auth {:required? false}}
                                                   :ws-registry ws-registry})
          resp (handler {})
          body (cheshire/parse-string (:body resp) true)
          sid (:session_id body)]
      (is (= 200 (:status resp)))
      (is (string? sid))
      (is (re-matches #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" sid)))))
