(ns samuraibff.ui.corrections-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.ui.corrections :as ui.corrections]))

(def rows
  [{:id "old" :segments [{:text "draft"}]}
   {:id "t1" :lang "en"
    :segments [{:start_s 0.0 :end_s 1.0 :speaker "SPEAKER_00" :text "Hello tear"}
               {:start_s 1.0 :end_s 2.0 :speaker "Anna" :text "Hi"}
               {:start_s 2.0 :end_s 3.0 :speaker "SPEAKER_00" :text "noise"}]
    :corrections {:head_seq 3
                  :items [{:id "a" :seq 1 :kind "segment_text" :segment_index 0
                           :old_value {:text "Hello tear"} :new_value {:text "Hello tier"}}
                          {:id "b" :seq 2 :kind "segment_speaker" :segment_index 1
                           :old_value {:speaker "Anna"} :new_value {:speaker "Petr" :speaker_id "p"}}
                          {:id "c" :seq 3 :kind "segment_text" :segment_index 2
                           :old_value {:text "noise"} :new_value {:text ""}}]}}])

(deftest final-source-test
  (let [source (ui.corrections/final-source rows)]
    (is (= {:transcript-id "t1" :head-seq 3 :lang "en"} (select-keys source [:transcript-id :head-seq :lang])))
    (is (ui.corrections/correctable? source))
    (is (= 3 (ui.corrections/changed-count source)))
    (is (nil? (ui.corrections/final-source [])))
    (is (= 0 (:head-seq (ui.corrections/final-source [{:id "x" :segments []}]))))))

(deftest view-messages-test
  (let [source (ui.corrections/final-source rows)]
    (testing "corrected view"
      (let [msgs (ui.corrections/view-messages source false)]
        (is (= [0 1] (mapv :seq msgs)) "cleared segments are left out")
        (is (= ["Hello tier" "Hi"] (mapv :text msgs)))
        (is (= ["SPEAKER_00" "Petr"] (mapv :speaker msgs)))
        (is (every? :edited msgs))))
    (testing "show changes"
      (let [msgs (ui.corrections/view-messages source true)]
        (is (= [0 1 2] (mapv :seq msgs)))
        (is (= [[:same "Hello"] [:del "tear"] [:ins "tier"]] (:diff (first msgs))))
        (is (= "Anna" (:speaker_was (second msgs))))
        (is (= [[:del "noise"]] (:diff (nth msgs 2))))))
    (testing "an edit back to the machine text is history, not a change"
      (let [restored (-> source
                         (update :items conj {:id "d" :seq 4 :kind "segment_text" :segment_index 0
                                              :old_value {:text "Hello tier"} :new_value {:text "Hello tear"}}))
            change (:change (first (ui.corrections/view-messages restored true)))]
        (is (= 2 (ui.corrections/changed-count restored)))
        (is (not (ui.corrections/text-changed? change)))
        (is (= ["d" "a"] (mapv :id (:history change))))
        (is (nil? (:diff (first (ui.corrections/view-messages restored true)))))
        (is (not (:edited (first (ui.corrections/view-messages restored false)))))
        (is (ui.corrections/restores-machine? change (:text-edit change)))
        (is (not (ui.corrections/restores-machine? change (last (:history change)))))))
    (testing "without corrections"
      (let [msgs (ui.corrections/view-messages (assoc source :items []) true)]
        (is (= ["Hello tear" "Hi" "noise"] (mapv :text msgs)))
        (is (not-any? :edited msgs))
        (is (not-any? :diff msgs))))))

(deftest speakers-test
  (let [source (ui.corrections/final-source rows)
        speakers [{:id "p" :label "Petr"} {:id "e" :label "Eva"}]]
    (is (= ["Petr" "Eva" "SPEAKER_00"] (ui.corrections/speaker-names speakers source)))
    (is (= {:speaker_id "p"} (ui.corrections/speaker-request speakers " Petr ")))
    (is (= {:speaker "Miro"} (ui.corrections/speaker-request speakers "Miro")))
    (is (nil? (ui.corrections/speaker-request speakers "  ")))))

(defn- plain
  "Render describe runs as text, with replaced values in [brackets] and new
  values in {braces}."
  [runs]
  (apply str (map (fn [[op text]] (case op :del (str "[" text "]") :ins (str "{" text "}") text)) runs)))

(deftest describe-runs-test
  (let [describe #(plain (ui.corrections/describe-runs %))]
    (is (= [[:text "Changed "] [:del "tear"] [:text " to "] [:ins "tier"]]
           (ui.corrections/describe-runs {:kind "segment_text" :old_value {:text "Hello tear"}
                                          :new_value {:text "Hello tier"}})))
    (is (= "Added {there}" (describe {:kind "segment_text" :old_value {:text "Hello"} :new_value {:text "Hello there"}})))
    (is (= "Removed [uh]" (describe {:kind "segment_text" :old_value {:text "uh Hello"} :new_value {:text "Hello"}})))
    (is (= "Cleared the text" (describe {:kind "segment_text" :old_value {:text "noise"} :new_value {:text ""}})))
    (is (= "Changed [Each] to {Every}; added {test}"
           (describe {:kind "segment_text" :old_value {:text "Each word. This sample"}
                      :new_value {:text "Every word. This test sample"}})))
    (is (= "Removed [a]; removed [b]; removed [c]; 1 more"
           (describe {:kind "segment_text" :old_value {:text "a x b x c x d x"} :new_value {:text "x x x x"}})))
    (is (= "Changed the spacing" (describe {:kind "segment_text" :old_value {:text "a  b"} :new_value {:text "a b"}})))
    (is (= "Set the speaker to {Petr}" (describe {:kind "segment_speaker" :new_value {:speaker "Petr"}})))
    (is (= "Changed the speaker from [Anna] to {Petr}"
           (describe {:kind "segment_speaker" :old_value {:speaker "Anna"} :new_value {:speaker "Petr"}})))
    (is (= "Renamed [SPEAKER_00] to {Miro} in the whole transcript"
           (describe {:kind "speaker_label" :speaker_label "SPEAKER_00" :new_value {:speaker "Miro"}})))
    (is (= "Undid a change" (describe {:kind "undo"})))))

(deftest restores-machine-test
  (let [change {:original {:text "Hello" :speaker "SPEAKER_00"}}]
    (is (ui.corrections/restores-machine? change {:kind "segment_text" :new_value {:text "Hello"}}))
    (is (not (ui.corrections/restores-machine? change {:kind "segment_text" :new_value {:text "Hi"}})))
    (is (ui.corrections/restores-machine? change {:kind "segment_speaker" :new_value {:speaker "SPEAKER_00"}}))
    (is (not (ui.corrections/restores-machine? change {:kind "segment_speaker"
                                                       :new_value {:speaker "SPEAKER_00" :speaker_id "p"}})))
    (is (ui.corrections/restores-machine? change {:kind "speaker_label" :speaker_label "SPEAKER_00"
                                                  :new_value {:speaker "SPEAKER_00"}}))
    (is (not (ui.corrections/restores-machine? change {:kind "undo"})))))

(deftest author-label-test
  (is (= "you" (ui.corrections/author-label {:author {:is_me true :display_name "Alice"}})))
  (is (= "Alice" (ui.corrections/author-label {:author {:is_me false :display_name "Alice"}})))
  (is (= "someone" (ui.corrections/author-label {:author {:is_me false}}))))

(deftest apply-saved-test
  (let [detail {:transcripts {:final [{:id "t0"} {:id "t1" :corrections {:head_seq 3 :items [{:id "a"}]}}]}}
        saved (ui.corrections/apply-saved detail "t1" {:id "d" :seq 4} 4)]
    (is (= {:head_seq 4 :items [{:id "a"} {:id "d" :seq 4}]} (get-in saved [:transcripts :final 1 :corrections])))
    (is (= {:id "t0"} (get-in saved [:transcripts :final 0])))
    (is (= saved (ui.corrections/apply-saved saved "t1" {:id "d" :seq 4} 4)) "a known correction is not added twice")))

(deftest error-message-test
  (is (re-find #"latest version" (ui.corrections/error-message "correction-conflict")))
  (is (= "Could not save the correction. Try again." (ui.corrections/error-message nil))))
