(ns samuraibff.transcripts.correction-requests-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.transcripts.correction-requests :as requests]))

(def segments
  [{:start_s 0.0 :end_s 2.0 :speaker "SPEAKER_00" :text "Hello there"}
   {:start_s 2.0 :end_s 4.0 :speaker "Anna" :text "Hi"}
   {:start_s 4.0 :end_s 5.0 :text "Bye"}])

(defn- plan
  ([request] (plan [] request))
  ([corrections request & {:as more}]
   (requests/plan (merge {:segments segments :corrections corrections :request request} more))))

(deftest text-edit-test
  (is (= {:row {:kind "segment_text" :segment_index 0 :seq 1
                :old_value {:text "Hello there"} :new_value {:text "Hello, there"}
                :audio_start_s 0.0 :audio_end_s 2.0}}
         (plan {:kind "segment_text" :base_seq 0 :segment_index 0 :text "  Hello, there "})))
  (is (= {:error :invalid-segment} (plan {:kind "segment_text" :base_seq 0 :segment_index 3 :text "x"})))
  (is (= {:error :invalid-text} (plan {:kind "segment_text" :base_seq 0 :segment_index 0})))
  (is (= {:error :invalid-text}
         (plan {:kind "segment_text" :base_seq 0 :segment_index 0
                :text (apply str (repeat (inc requests/max-text-length) "a"))})))
  (is (= {:error :correction-unchanged} (plan {:kind "segment_text" :base_seq 0 :segment_index 0 :text "Hello there"})))
  (testing "a cleared segment is a valid correction"
    (is (= {:text ""} (get-in (plan {:kind "segment_text" :base_seq 0 :segment_index 2 :text " "})
                              [:row :new_value])))))

(deftest concurrency-test
  (let [stored [{:id "a" :seq 1 :kind "segment_text" :segment_index 0 :new_value {:text "Hello you"}}
                {:id "b" :seq 2 :kind "segment_text" :segment_index 1 :new_value {:text "Hi all"}}]]
    (testing "a newer edit of the same segment conflicts"
      (is (= {:error :correction-conflict}
             (plan stored {:kind "segment_text" :base_seq 0 :segment_index 0 :text "Hey"}))))
    (testing "a newer edit of another segment does not conflict"
      (is (= 3 (get-in (plan stored {:kind "segment_text" :base_seq 1 :segment_index 0 :text "Hey"})
                       [:row :seq]))))
    (testing "the old value is the corrected text"
      (is (= {:text "Hello you"}
             (get-in (plan stored {:kind "segment_text" :base_seq 2 :segment_index 0 :text "Hey"})
                     [:row :old_value]))))
    (is (= {:error :invalid-base-seq} (plan stored {:kind "segment_text" :base_seq 3 :segment_index 0 :text "Hey"})))
    (is (= {:error :invalid-base-seq} (plan stored {:kind "segment_text" :segment_index 0 :text "Hey"})))
    (is (= {:error :invalid-kind} (plan stored {:kind "delete" :base_seq 2})))))

(deftest speaker-test
  (testing "naming an anonymous label"
    (is (= {:row {:kind "speaker_label" :speaker_label "SPEAKER_00" :seq 1
                  :old_value {:speaker "SPEAKER_00" :machine_speaker "SPEAKER_00"}
                  :new_value {:speaker "Petr"}}}
           (plan {:kind "speaker_label" :base_seq 0 :speaker_label "SPEAKER_00" :speaker " Petr "}))))
  (testing "replacing a wrongly identified enrolled speaker records the machine's choice"
    (let [anna-id #uuid "00000000-0000-0000-0000-0000000000aa"
          petr-id #uuid "00000000-0000-0000-0000-0000000000bb"]
      (is (= {:row {:kind "segment_speaker" :segment_index 1 :seq 1
                    :old_value {:speaker "Anna" :machine_speaker "Anna" :machine_speaker_id (str anna-id)}
                    :new_value {:speaker "Petr" :speaker_id (str petr-id)}
                    :audio_start_s 2.0 :audio_end_s 4.0}}
             (plan [] {:kind "segment_speaker" :base_seq 0 :segment_index 1 :speaker_id (str petr-id)}
                   :speaker {:id petr-id :label "Petr"}
                   :enrolled-ids {"Anna" anna-id})))))
  (is (= {:error :unknown-speaker}
         (plan {:kind "segment_speaker" :base_seq 0 :segment_index 1 :speaker_id "00000000-0000-0000-0000-000000000001"})))
  (is (= {:error :invalid-speaker} (plan {:kind "segment_speaker" :base_seq 0 :segment_index 1 :speaker "  "})))
  (is (= {:error :invalid-speaker-label} (plan {:kind "speaker_label" :base_seq 0 :speaker_label "SPEAKER_09" :speaker "X"})))
  (is (= {:error :correction-unchanged} (plan {:kind "segment_speaker" :base_seq 0 :segment_index 1 :speaker "Anna"})))
  (let [stored [{:id "a" :seq 1 :kind "speaker_label" :speaker_label "SPEAKER_00" :new_value {:speaker "Petr"}}]]
    (is (= {:error :correction-unchanged}
           (plan stored {:kind "speaker_label" :base_seq 1 :speaker_label "SPEAKER_00" :speaker "Petr"})))
    (is (= {:error :correction-conflict}
           (plan stored {:kind "speaker_label" :base_seq 0 :speaker_label "SPEAKER_00" :speaker "Eva"})))
    (testing "merging another label into the same person"
      (is (= {:speaker "Petr"}
             (get-in (plan stored {:kind "speaker_label" :base_seq 1 :speaker_label "Anna" :speaker "Petr"})
                     [:row :new_value]))))))

(deftest undo-test
  (let [stored [{:id "a" :seq 1 :kind "segment_text" :segment_index 0 :new_value {:text "Hello you"}
                 :audio_start_s 0.0 :audio_end_s 2.0}
                {:id "b" :seq 2 :kind "segment_text" :segment_index 0 :new_value {:text "Hey you"}}
                {:id "c" :seq 3 :kind "undo" :undoes_id "b"}]]
    (is (= {:row {:kind "undo" :undoes_id "a" :seq 4 :audio_start_s 0.0 :audio_end_s 2.0}}
           (plan stored {:kind "undo" :base_seq 3 :correction_id "a"})))
    (is (= {:error :already-undone} (plan stored {:kind "undo" :base_seq 3 :correction_id "b"})))
    (is (= {:error :unknown-correction} (plan stored {:kind "undo" :base_seq 3 :correction_id "c"})))
    (is (= {:error :unknown-correction} (plan stored {:kind "undo" :base_seq 3 :correction_id "zzz"})))
    (testing "undoing an edit someone already replaced conflicts"
      (is (= {:error :correction-conflict}
             (plan (pop stored) {:kind "undo" :base_seq 1 :correction_id "a"}))))
    (testing "undoing a newer edit you know about does not conflict with itself"
      (is (= "b" (get-in (plan (pop stored) {:kind "undo" :base_seq 2 :correction_id "b"})
                         [:row :undoes_id]))))))
