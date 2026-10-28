(ns samuraibff.access.watch-test
  "Unit tests of the background access watcher with a fake clock and check."
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.access.watch :as watch]))

(def ^:private settings
  {:interval-ms 30000 :grace-ms 120000 :timeout-s 5 :max-duration-ms 600000 :jitter 0})

(defn- fixture
  "Return a stopped watcher whose check returns @result* (or throws it)."
  []
  (let [now* (atom 1000000)
        result* (atom true)
        calls* (atom 0)
        w (watch/watcher :ds settings
                         {:now #(deref now*)
                          :check-batch (fn [_ _ entries]
                                         (swap! calls* inc)
                                         (let [r @result*]
                                           (if (instance? Exception r)
                                             (throw r)
                                             (zipmap (map :id entries) (repeat r)))))})]
    {:w w :now* now* :result* result* :calls* calls*}))

(defn- entry!
  [w closed* & {:keys [max-duration?]}]
  (watch/register! w {:principal {:id (random-uuid) :tenant-id (random-uuid)}
                      :session-id (random-uuid)
                      :mode :read
                      :max-duration? (boolean max-duration?)
                      :on-close #(swap! closed* conj %)}))

(deftest settings-test
  (testing "defaults"
    (is (= {:interval-ms 30000 :grace-ms 120000 :timeout-s 5 :max-duration-ms 14400000 :jitter 0.1}
           (watch/settings {}))))
  (testing "configured values"
    (is (= {:interval-ms 60000 :grace-ms 300000 :timeout-s 2 :max-duration-ms 7200000 :jitter 0.1}
           (watch/settings {:access {:recheck-interval-s 60 :recheck-grace-s 300 :recheck-timeout-s 2}
                            :ws {:max-duration-s 7200}}))))
  (testing "the grace period covers at least two intervals"
    (is (= 120000 (:grace-ms (watch/settings {:access {:recheck-interval-s 60 :recheck-grace-s 10}})))))
  (testing "invalid values fall back to defaults"
    (is (= 30000 (:interval-ms (watch/settings {:access {:recheck-interval-s -5}}))))))

(deftest hot-path-reads-cached-state-only
  (let [{:keys [w calls*]} (fixture)
        closed* (atom [])
        e (entry! w closed*)]
    (dotimes [_ 1000] (is (watch/allowed? w e)))
    (is (zero? @calls*) "allowed? never runs a database check")))

(deftest definite-denial-closes-once
  (let [{:keys [w result* calls*]} (fixture)
        closed* (atom [])
        e (entry! w closed*)]
    (is (= :ok (watch/check-now! w)))
    (is (watch/allowed? w e))
    (reset! result* false)
    (is (= :ok (watch/check-now! w)))
    (is (= [:revoked] @closed*))
    (is (= :revoked (watch/close-reason e)))
    (is (false? (watch/allowed? w e)))
    (reset! result* true)
    (is (= :idle (watch/check-now! w)) "a closed entry is no longer checked")
    (is (= [:revoked] @closed*) "on-close runs once")
    (is (= 2 @calls*))))

(deftest one-batched-check-covers-all-entries
  (let [{:keys [w calls*]} (fixture)
        closed* (atom [])]
    (dotimes [_ 50] (entry! w closed*))
    (is (= :ok (watch/check-now! w)))
    (is (= 1 @calls*))))

(deftest failures-are-retried-within-the-grace-period
  (let [{:keys [w now* result*]} (fixture)
        closed* (atom [])
        e (entry! w closed*)]
    (reset! result* (ex-info "statement timeout" {}))
    (doseq [t [30000 30000 30000]]
      (swap! now* + t)
      (is (= :failed (watch/check-now! w)))
      (is (watch/allowed? w e) "a slow or failing database does not close deliveries"))
    (testing "a later success restores the full grace period"
      (reset! result* true)
      (is (= :ok (watch/check-now! w)))
      (swap! now* + 100000)
      (is (watch/allowed? w e)))
    (testing "deliveries close once checks fail for longer than the grace period"
      (reset! result* (ex-info "down" {}))
      (swap! now* + 30000)
      (is (= :failed (watch/check-now! w)))
      (is (false? (watch/allowed? w e)))
      (is (= [:unverified] @closed*)))))

(deftest maximum-duration
  (let [{:keys [w now*]} (fixture)
        closed* (atom [])
        socket (entry! w closed* :max-duration? true)
        playback (entry! w closed*)]
    (swap! now* + 599999)
    (is (= :ok (watch/check-now! w)))
    (is (watch/allowed? w socket))
    (swap! now* + 1)
    (is (false? (watch/allowed? w socket)))
    (is (= :max-duration (watch/close-reason socket)))
    (is (watch/allowed? w playback) "entries without a maximum duration continue")))

(deftest tick-defers-while-a-check-is-running
  (let [now* (atom 0)
        release (promise)
        calls* (atom 0)
        w (watch/start! (watch/watcher :ds (assoc settings :interval-ms 3600000)
                                       {:now #(deref now*)
                                        :check-batch (fn [_ _ entries]
                                                       (swap! calls* inc)
                                                       @release
                                                       (zipmap (map :id entries) (repeat true)))}))
        closed* (atom [])]
    (try
      (entry! w closed*)
      (is (= :started (watch/tick! w)))
      (is (= :deferred (watch/tick! w)) "no second check while the first still runs")
      (deliver release true)
      (is (= :ok @@(:in-flight* w)))
      (is (= :started (watch/tick! w)))
      (is (= :ok @@(:in-flight* w)))
      (is (= 2 @calls*))
      (is (empty? @closed*))
      (finally (watch/stop! w)))))

(deftest tick-closes-lapsed-entries-without-a-check
  (let [{:keys [w now*]} (fixture)
        closed* (atom [])]
    (entry! w closed* :max-duration? true)
    (swap! now* + 600000)
    (is (= :idle (watch/tick! w)) "lapsed entries close before any database work")
    (is (= [:max-duration] @closed*))))
