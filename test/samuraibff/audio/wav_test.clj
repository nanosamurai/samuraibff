;; Copyright (c) samuraibff contributors.
(ns samuraibff.audio.wav-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [samuraibff.audio.wav :as wav])
  (:import
    (java.nio ByteBuffer ByteOrder)
    (java.nio.file Files)
    (java.util Arrays)))

(defn- pcm-wav-bytes
  "Build a minimal canonical PCM WAV byte array.

  Inputs:
  - {:keys [channels sample-rate bits-per-sample data-bytes data]}
    - data: byte-array length == data-bytes

  Returns: byte-array." 
  [{:keys [channels sample-rate bits-per-sample data-bytes data]}]
  (let [channels (int channels)
        sample-rate (int sample-rate)
        bits-per-sample (int bits-per-sample)
        block-align (int (* channels (quot bits-per-sample 8)))
        byte-rate (int (* sample-rate block-align))
        data-bytes (int data-bytes)
        riff-size (+ 36 data-bytes)
        ^ByteBuffer bb (doto (ByteBuffer/allocate (+ 44 data-bytes))
                         (.order ByteOrder/LITTLE_ENDIAN))]
    (.put bb (.getBytes "RIFF" "US-ASCII"))
    (.putInt bb riff-size)
    (.put bb (.getBytes "WAVE" "US-ASCII"))
    (.put bb (.getBytes "fmt " "US-ASCII"))
    (.putInt bb 16)
    (.putShort bb (short 1))
    (.putShort bb (short channels))
    (.putInt bb sample-rate)
    (.putInt bb byte-rate)
    (.putShort bb (short block-align))
    (.putShort bb (short bits-per-sample))
    (.put bb (.getBytes "data" "US-ASCII"))
    (.putInt bb data-bytes)
    (.put bb ^bytes data)
    (.array bb)))

(defn- write-temp!
  "Write bytes to a temporary file.

  Returns java.io.File." 
  [^bytes bytes]
  (let [p (Files/createTempFile "samuraibff-wav" ".wav"
                               (make-array java.nio.file.attribute.FileAttribute 0))]
    (Files/write p bytes (make-array java.nio.file.OpenOption 0))
    (.toFile p)))

(deftest parse-wav-header-basic-test
  (testing "parse-wav-header finds fmt + data and returns offsets"
    (let [data (byte-array (range 0 100))
          bytes (pcm-wav-bytes {:channels 1
                                :sample-rate 10
                                :bits-per-sample 16
                                :data-bytes 100
                                :data data})
          hdr (wav/parse-wav-header (Arrays/copyOfRange bytes 0 44))]
      (is (= 1 (:audio-format hdr)))
      (is (= 1 (:channels hdr)))
      (is (= 10 (:sample-rate hdr)))
      (is (= 20 (:byte-rate hdr)))
      (is (= 2 (:block-align hdr)))
      (is (= 16 (:bits-per-sample hdr)))
      (is (= 44 (:data-offset hdr)))
      (is (= 100 (:data-size hdr))))))

(deftest clip-wav-file-extracts-correct-range-test
  (testing "clip-wav-file extracts exact byte window (aligned)"
    (let [data (byte-array (range 0 100))
          bytes (pcm-wav-bytes {:channels 1
                                :sample-rate 10
                                :bits-per-sample 16
                                :data-bytes 100
                                :data data})
          f (write-temp! bytes)
          {:keys [wav-bytes clip]} (wav/clip-wav-file f {:start_s 1.0 :end_s 3.0 :max_duration_s 10.0})
          out-hdr (wav/parse-wav-header (Arrays/copyOfRange ^bytes wav-bytes 0 44))
          out-data (Arrays/copyOfRange ^bytes wav-bytes 44 (alength ^bytes wav-bytes))
          expected (Arrays/copyOfRange ^bytes bytes (+ 44 20) (+ 44 60))]
      (try
        (is (= {:start_s 1.0 :end_s 3.0 :truncated? false} clip))
        (is (= 40 (:data-size out-hdr)))
        (is (= 40 (alength ^bytes out-data)))
        (is (Arrays/equals ^bytes expected ^bytes out-data))
        (finally
          (try
            (.delete f)
            (catch Exception _ nil)))))))

(deftest clip-wav-file-truncates-long-window-test
  (testing "max_duration_s clamps end_s"
    (let [data (byte-array (range 0 200))
          bytes (pcm-wav-bytes {:channels 1
                                :sample-rate 10
                                :bits-per-sample 16
                                :data-bytes 200
                                :data data})
          f (write-temp! bytes)
          {:keys [clip]} (wav/clip-wav-file f {:start_s 0.0 :end_s 20.0 :max_duration_s 10.0})]
      (try
        (is (= 0.0 (:start_s clip)))
        (is (= 10.0 (:end_s clip)))
        (is (true? (:truncated? clip)))
        (finally
          (try
            (.delete f)
            (catch Exception _ nil)))))))
