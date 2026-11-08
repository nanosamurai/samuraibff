(ns samuraibff.s3.uploads-test
  "Unit tests for storing uploaded media unchanged in S3 (no Docker)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.s3.uploads :as s3.uploads]
   [samuraibff.test-support.fake-s3 :as fake-s3])
  (:import
   (java.io ByteArrayInputStream)
   (java.util Arrays)))

(defn- ascii ^bytes [s] (.getBytes ^String s "US-ASCII"))

(defn- with-head
  "Return n bytes that start with `head`."
  ^bytes [^bytes head n]
  (let [out (byte-array n (byte 7))]
    (System/arraycopy head 0 out 0 (alength head))
    out))

(deftest sniff-container-test
  (testing "known audio and video containers are recognized from leading bytes"
    (doseq [[head container] [["RIFF\u0000\u0000\u0000\u0000WAVE" :wav]
                              ["RIFF\u0000\u0000\u0000\u0000AVI " :avi]
                              ["FORM\u0000\u0000\u0000\u0000AIFF" :aiff]
                              ["ID3\u0004" :mp3]
                              ["fLaC" :flac]
                              ["OggS" :ogg]
                              ["\u0000\u0000\u0000\u001cftypM4A " :mp4]
                              ["#!AMR\n" :amr]
                              ["caff" :caf]]]
      (is (= container (s3.uploads/sniff-container (with-head (ascii head) 16))) head)))
  (testing "binary signatures"
    (is (= :matroska (s3.uploads/sniff-container (byte-array [0x1a 0x45 0xdf 0xa3 0 0 0 0 0 0 0 0]))))
    (is (= :asf (s3.uploads/sniff-container (byte-array [0x30 0x26 0xb2 0x75 0 0 0 0 0 0 0 0]))))
    (is (= :aac (s3.uploads/sniff-container (byte-array (map unchecked-byte [0xff 0xf1 0x50 0x80])))))
    (is (= :mp3 (s3.uploads/sniff-container (byte-array (map unchecked-byte [0xff 0xfb 0x90 0x64]))))))
  (testing "other files are rejected regardless of name or declared type"
    (doseq [head ["%PDF-1.7" "PK\u0003\u0004" "<html>" "#EXTM3U\n" "MZ" "\u007fELF" "RIFF\u0000\u0000\u0000\u0000WEBP"]]
      (is (nil? (s3.uploads/sniff-container (with-head (ascii head) 16))) head))
    (is (nil? (s3.uploads/sniff-container (byte-array 0))))))

(deftest source-key-test
  (is (= "recordings/t1/uploads/s1/source" (s3.uploads/source-key "recordings" "t1" "s1")))
  (is (= "a/b/t1/uploads/s1/source" (s3.uploads/source-key "/a/b/" "t1" "s1")))
  (is (= "t1/uploads/s1/source" (s3.uploads/source-key nil "t1" "s1"))))

(defn- wav-like ^bytes [n]
  (with-head (ascii "RIFF\u0000\u0000\u0000\u0000WAVEfmt ") n))

(defn- failure-type
  "Call f and return the :type of the ExceptionInfo it throws, or nil."
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:type (ex-data e)))))

(deftest put-source-test
  (testing "a small file is stored with one request"
    (let [{:keys [client state]} (fake-s3/fake-s3)
          data (wav-like 1000)]
      (is (= {:bytes 1000 :container :wav}
             (s3.uploads/put-source! client "b" "k" (ByteArrayInputStream. data) 5000)))
      (is (Arrays/equals data ^bytes (get-in @state [:objects "k"])))))

  (testing "a large file is stored unchanged in parts"
    (let [{:keys [client state]} (fake-s3/fake-s3)
          n (+ (* 2 s3.uploads/part-size) 123)
          data (wav-like n)]
      (is (= n (:bytes (s3.uploads/put-source! client "b" "k" (ByteArrayInputStream. data)
                                               (* 4 s3.uploads/part-size)))))
      (is (Arrays/equals data ^bytes (get-in @state [:objects "k"])))
      (is (empty? (:uploads @state)))))

  (testing "a file over the limit is rejected and nothing is stored"
    (let [{:keys [client state]} (fake-s3/fake-s3)
          limit (+ s3.uploads/part-size 10)]
      (is (= ::s3.uploads/too-large
             (failure-type #(s3.uploads/put-source! client "b" "k" (ByteArrayInputStream. (wav-like 1001)) 1000))))
      (is (= ::s3.uploads/too-large
             (failure-type #(s3.uploads/put-source! client "b" "k" (ByteArrayInputStream. (wav-like (inc limit))) limit))))
      (is (empty? (:objects @state)))
      (is (empty? (:uploads @state)))
      (is (= 1 (count (:aborted @state))) "the started multipart upload is aborted")))

  (testing "empty, unsupported and failing uploads store nothing"
    (let [{:keys [client state]} (fake-s3/fake-s3 {:fail-part 2})]
      (is (= ::s3.uploads/empty
             (failure-type #(s3.uploads/put-source! client "b" "k" (ByteArrayInputStream. (byte-array 0)) 1000))))
      (is (= ::s3.uploads/unsupported-media
             (failure-type #(s3.uploads/put-source! client "b" "k" (ByteArrayInputStream. (ascii "%PDF-1.7 hello")) 1000))))
      (is (thrown-with-msg? RuntimeException #"storage unavailable"
                            (s3.uploads/put-source! client "b" "k"
                                                    (ByteArrayInputStream. (wav-like (* 3 s3.uploads/part-size)))
                                                    (* 4 s3.uploads/part-size))))
      (is (empty? (:objects @state)))
      (is (= 1 (count (:aborted @state)))))))

(deftest tenant-recording-object-test
  (let [tenant "11111111-1111-1111-1111-111111111111"
        ok-key (str "recordings/" tenant "/2026/11/08/session/audio.wav")]
    (testing "a recording in the tenant's part of the recordings bucket is accepted"
      (is (= {:bucket "rec" :key ok-key}
             (s3.uploads/tenant-recording-object (str "s3://rec/" ok-key) "rec" "/recordings/" tenant)))
      (is (= {:bucket "rec" :key (str tenant "/x.wav")}
             (s3.uploads/tenant-recording-object (str "s3://rec/" tenant "/x.wav") "rec" nil tenant))
          "no prefix"))
    (testing "other locations are refused"
      (doseq [[url why] [[(str "file:///data/" tenant "/a.wav") "local file"]
                         [(str "s3://other/" ok-key) "another bucket"]
                         ["s3://rec/recordings/22222222-2222-2222-2222-222222222222/a.wav" "another tenant"]
                         [(str "s3://rec/other/" tenant "/a.wav") "another prefix"]
                         [(str "s3://rec/recordings/" tenant "/") "the tenant folder itself"]
                         [(str "s3://rec/recordings/" tenant "x/a.wav") "a tenant id with a suffix"]
                         [(str "s3://rec/recordings/" tenant "/../2222/a.wav") "a dot-dot segment"]
                         [(str "s3://rec/recordings/" tenant "//a.wav") "an empty segment"]
                         ["s3://rec" "no key"]
                         [nil "no URL"]]]
        (is (nil? (s3.uploads/tenant-recording-object url "rec" "recordings" tenant)) why))
      (is (nil? (s3.uploads/tenant-recording-object (str "s3://rec/" ok-key) "" "recordings" tenant))
          "no configured bucket"))))

(deftest copy-source-test
  (testing "the stored object is copied inside S3 to the new key"
    (let [{:keys [client state]} (fake-s3/fake-s3 {:objects {"a/old.wav" (ascii "RIFF")}})]
      (is (nil? (s3.uploads/copy-source! client "rec" "a/old.wav" "a/uploads/new/source")))
      (is (= [{:source-bucket "rec" :source-key "a/old.wav" :bucket "rec" :key "a/uploads/new/source"}]
             (:copies @state)))
      (is (Arrays/equals (ascii "RIFF") ^bytes (get-in @state [:objects "a/uploads/new/source"])))
      (is (contains? (:objects @state) "a/old.wav") "the original stays")))
  (testing "a missing source object fails and stores nothing"
    (let [{:keys [client state]} (fake-s3/fake-s3)]
      (is (thrown? Exception (s3.uploads/copy-source! client "rec" "missing" "dest")))
      (is (empty? (:objects @state))))))
