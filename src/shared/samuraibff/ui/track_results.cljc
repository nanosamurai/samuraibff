(ns samuraibff.ui.track-results
  "Pure selection and transcript assembly for asynchronous track tabs."
  (:require [clojure.string :as str]))

(defn selected-ids
  "Resolve the form's optional ID list against visible catalog defaults."
  [entries requested]
  (let [ids (if (nil? requested) (set (map :track_id (filter :default_selected entries))) (set requested))]
    (mapv :track_id (filter #(or (:primary %) (contains? ids (:track_id %))) entries))))

(defn latest-results
  "Keep only the newest revision of a unit within its run, then order windows."
  [results]
  (->> results
       (reduce (fn [acc result]
                 (let [key [(:run_id result) (:unit_id result)]
                       previous (get acc key)]
                   (if (> (or (:revision result) 0) (or (:revision previous) -1))
                     (assoc acc key result) acc))) {})
       vals
       (sort-by (fn [result]
                  (let [start (second (re-matches #"fixed-[0-9]+:([0-9]+):[0-9]+" (:unit_id result)))]
                    (if start #?(:clj (Long/parseLong start) :cljs (js/parseInt start 10)) 0))))
       vec))

(defn messages
  "Assemble one track's ordered units using existing absolute session timings.
  Text-only artifacts remain readable without invented words or timestamps."
  [results artifacts]
  (->> (latest-results results)
       (filter #(= "succeeded" (:status %)))
       (mapcat (fn [result]
                 (let [transcript (get-in artifacts [(:result_id result) :transcript])
                       segments (:segments transcript)]
                   (if (seq segments) segments
                       (when-not (str/blank? (:full_text transcript))
                         [{:text (:full_text transcript)}])))))
       (map-indexed (fn [idx segment] (assoc segment :kind "final" :seq idx :ts_ms 0)))
       vec))

(defn status-label
  "Describe observed outcomes; pending never claims that a worker is healthy."
  [track]
  (let [results (latest-results (:results track))
        failed (count (filter #(= "failed" (:status %)) results))]
    (cond
      (empty? results) "Waiting for results"
      (pos? failed) (str failed " failed" (when (> (count results) failed) (str " · " (- (count results) failed) " succeeded")))
      (= "final" (:stage track)) "Succeeded"
      :else (str (count results) " windows received"))))
