(ns samuraibff.transcripts.export-test
  "Unit and round-trip tests for the transcript export formatters."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.xml :as xml]
   [samuraibff.transcripts.export :as export])
  (:import
   (java.io ByteArrayInputStream)
   (java.nio.charset StandardCharsets)
   (java.util.zip ZipInputStream)))

(def segments
  [{:start_s 0.0 :end_s 1.25 :speaker "Alice" :text "Hello <world> & you"
    :words [{:start_s 0.0 :end_s 0.5 :text "Hello"}]}
   {:start_s 3723.5 :end_s 3725.004 :speaker "Bob" :text "Žluťoučký kůň\n\nšel --> dál"}
   {:start_s 4.0 :end_s 5.0 :text "   "}
   {:start_s 6.0 :end_s 7.0 :text "No speaker"}])

(defn- parse-timestamp
  "Parse `HH:MM:SS,mmm` or `HH:MM:SS.mmm` back to seconds."
  [s]
  (let [[_ h m sec ms] (re-matches #"(\d+):(\d{2}):(\d{2})[,.](\d{3})" s)]
    (/ (+ (* 3600000 (parse-long h)) (* 60000 (parse-long m)) (* 1000 (parse-long sec)) (parse-long ms))
       1000.0)))

(defn- cue-times
  "Return [[start end] ...] parsed from SRT or VTT text."
  [text]
  (mapv (fn [[_ start end]] [(parse-timestamp start) (parse-timestamp end)])
        (re-seq #"(\d+:\d{2}:\d{2}[,.]\d{3}) --> (\d+:\d{2}:\d{2}[,.]\d{3})" text)))

(defn- unzip
  "Return {entry-name utf8-string} for a zip byte array."
  [^bytes data]
  (with-open [zip (ZipInputStream. (ByteArrayInputStream. data))]
    (loop [parts {}]
      (if-let [entry (.getNextEntry zip)]
        (recur (assoc parts (.getName entry) (String. (.readAllBytes zip) StandardCharsets/UTF_8)))
        parts))))

(deftest format-timestamp-test
  (is (= "00:00:00,000" (export/format-timestamp 0 ",")))
  (is (= "01:02:03,500" (export/format-timestamp 3723.5 ",")))
  (is (= "01:02:03.500" (export/format-timestamp 3723.5 ".")))
  (is (= "00:00:01.000" (export/format-timestamp 0.9996 ".")) "rounds to the nearest millisecond")
  (is (= "00:00:00,000" (export/format-timestamp nil ",")))
  (is (= "00:00:00,000" (export/format-timestamp -2 ",")))
  (is (= "00:00:00,000" (export/format-timestamp Double/NaN ","))))

(deftest srt-test
  (let [srt (export/->srt segments)]
    (is (= (str "1\n00:00:00,000 --> 00:00:01,250\nAlice: Hello <world> & you\n\n"
                "2\n01:02:03,500 --> 01:02:05,004\nBob: Žluťoučký kůň šel --> dál\n\n"
                "3\n00:00:06,000 --> 00:00:07,000\nNo speaker\n\n")
           srt))
    (testing "round trip keeps timings of exported cues"
      (is (= [[0.0 1.25] [3723.5 3725.004] [6.0 7.0]] (cue-times srt)))))
  (is (= "" (export/->srt [])))
  (is (= "1\n00:00:00,000 --> 00:00:00,000\nText only\n\n" (export/->srt [{:text "Text only"}])))
  (is (= "1\n00:00:02,000 --> 00:00:02,000\nx\n\n" (export/->srt [{:start_s 2 :end_s 1 :text "x"}]))
      "end before start is clamped to start"))

(deftest vtt-test
  (let [vtt (export/->vtt segments)]
    (is (str/starts-with? vtt "WEBVTT\n\n"))
    (is (str/includes? vtt "00:00:00.000 --> 00:00:01.250\n<v Alice>Hello &lt;world&gt; &amp; you\n\n"))
    (is (str/includes? vtt "<v Bob>Žluťoučký kůň šel --&gt; dál\n\n") "cue text cannot contain -->")
    (is (str/includes? vtt "00:00:06.000 --> 00:00:07.000\nNo speaker\n\n"))
    (is (= [[0.0 1.25] [3723.5 3725.004] [6.0 7.0]] (cue-times vtt))))
  (is (= "WEBVTT\n\n" (export/->vtt [])))
  (is (str/includes? (export/->vtt [{:start_s 0 :end_s 1 :speaker "A<b>" :text "x"}]) "<v A&lt;b&gt;>x")))

(deftest txt-test
  (is (= (str "[00:00:00] Alice: Hello <world> & you\n"
              "[01:02:03] Bob: Žluťoučký kůň šel --> dál\n"
              "[00:00:06] No speaker\n")
         (export/->txt segments)))
  (is (= "Text only\n" (export/->txt [{:text "Text only"}])) "no invented timestamp")
  (is (= "" (export/->txt []))))

(deftest json-test
  (let [doc (cheshire/parse-string (export/->json {:session_id "s1" :title "T" :stage "final"
                                                   :track_id "whisperx" :lang "cs" :ignored 1}
                                                  segments)
                                   true)]
    (is (= {:session_id "s1" :title "T" :stage "final" :track_id "whisperx" :lang "cs"}
           (dissoc doc :text :segments)))
    (is (= 3 (count (:segments doc))))
    (is (= {:start_s 0.0 :end_s 1.25 :speaker "Alice" :text "Hello <world> & you"
            :words [{:start_s 0.0 :end_s 0.5 :text "Hello"}]}
           (first (:segments doc))))
    (is (= "Žluťoučký kůň\n\nšel --> dál" (:text (second (:segments doc)))) "stored text is kept")
    (is (not (contains? (second (:segments doc)) :words)))
    (is (str/starts-with? (:text doc) "Hello <world> & you Žluťoučký")))
  (is (= {:segments [] :text ""} (export/json-document {} []))))

(deftest docx-test
  (let [parts (unzip (export/->docx "Q3 <review> & plan" segments))
        document (get parts "word/document.xml")
        texts (->> (xml-seq (xml/parse (ByteArrayInputStream. (.getBytes ^String document StandardCharsets/UTF_8))))
                   (filter #(= :w:t (:tag %)))
                   (mapcat :content))]
    (is (= #{"[Content_Types].xml" "_rels/.rels" "word/document.xml"} (set (keys parts))))
    (is (str/includes? (get parts "[Content_Types].xml") "/word/document.xml"))
    (is (str/includes? (get parts "_rels/.rels") "Target=\"word/document.xml\""))
    (is (= ["Q3 <review> & plan"
            "[00:00:00] Alice: Hello <world> & you"
            "[01:02:03] Bob: Žluťoučký kůň šel --> dál"
            "[00:00:06] No speaker"]
           texts)
        "document.xml is well-formed and holds escaped, unicode text"))
  (testing "control characters are dropped so the XML stays valid"
    (let [document (get (unzip (export/->docx nil [{:text "a\u0001b"}])) "word/document.xml")]
      (is (str/includes? document ">ab<"))
      (is (str/includes? document ">Transcript<")))))
