(ns samuraibff.ui.recording-detail
  "Pure Postgres track selection and transcript display helpers."
  (:require
   [clojure.string :as str]))

(defn track-tabs
  "Build flat saved-result tabs from selected IDs and actual Postgres rows.
  Accepts a recording response; returns ordered {:id [stage track], :label, :rows}
  maps. Historical labels win, NULL track IDs mean WhisperX, and missing rows
  remain visible without inferring a worker outcome."
  [detail]
  (let [controls (get-in detail [:session :stream_controls])]
    (vec (for [[stage selection] [[:refined :refinement_tracks] [:final :final_tracks]]
               :let [rows (get-in detail [:transcripts stage])
                     selected (when (get controls stage) (get controls selection))
                     ids (distinct (concat selected (map #(or (:track_id %) "whisperx") rows)))]
               id ids
               :let [label (or (get-in controls [:track_labels stage (keyword id)])
                               (when (= id "whisperx") "WhisperX") id)]]
           {:id [stage id]
            :label (str (if (= stage :final) "Final Transcript" "Refined Transcript") " (" label ")")
            :rows (filterv #(= id (or (:track_id %) "whisperx")) rows)}))))

(defn record-messages
  "Convert one track's stored rows to display messages, preserving real timings.
  Accepts rows and stage keyword; falls back to full_text for text-only rows,
  without manufacturing speakers, timestamps or alignment. Use the latest
  final row for legacy history and order refined windows by their audio bounds."
  [rows stage]
  (vec (mapcat (fn [row]
                 (map-indexed (fn [idx segment]
                                (merge {:kind (name stage) :seq idx :lang (:lang row)} segment))
                              (if (seq (:segments row))
                                (:segments row)
                                (when-not (str/blank? (:full_text row))
                                  [{:text (:full_text row)}]))))
               (if (= stage :final)
                 (take-last 1 rows)
                 (sort-by #(or (:segment_start_s %) 0) rows)))))
