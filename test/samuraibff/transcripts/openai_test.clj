(ns samuraibff.transcripts.openai-test
  "Unit tests for the OpenAI transcription mapping."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [samuraibff.transcripts.openai :as openai]))

(def ^:private config
  {:final-tracks ["whisperx" "qwen"]
   :default-tracks {:final "qwen"}})

(def transcript
  "A stored final transcript with speakers, word timings and markup-like text."
  {:segments [{:start_s 0.0 :end_s 1.5 :text "Hello there." :speaker "Ana"
               :words [{:start_s 0.0 :end_s 0.5 :text "Hello"} {:start_s 0.6 :end_s 1.5 :text "there."}]}
              {:start_s 1.5 :end_s 3.25 :text "General <Kenobi>."}]
   :full-text "Hello there. General <Kenobi>."
   :lang "en"
   :duration-s 3.25})

(deftest resolve-model-test
  (testing "whisper-1 maps to the default final track"
    (is (= "qwen" (openai/resolve-model config "whisper-1")))
    (is (= "whisperx" (openai/resolve-model (dissoc config :default-tracks) "whisper-1"))))
  (testing "configured final track IDs are models"
    (is (= "whisperx" (openai/resolve-model config "whisperx"))))
  (testing "configured aliases replace the default alias"
    (let [config (assoc config :openai {:model-aliases {"fast" "whisperx" "broken" "large-v3"}})]
      (is (= "whisperx" (openai/resolve-model config "fast")))
      (is (nil? (openai/resolve-model config "whisper-1")))
      (is (nil? (openai/resolve-model config "broken")) "an alias to an unconfigured track is unknown")
      (is (= ["fast" "qwen" "whisperx"] (openai/available-models config)))))
  (testing "unknown models and weights never resolve"
    (doseq [model [nil "" "gpt-4o-transcribe" "/models/large-v3" "WHISPERX"]]
      (is (nil? (openai/resolve-model config model)) (pr-str model)))))

(deftest parse-request-test
  (testing "defaults"
    (is (= {:model "whisper-1" :track-id "qwen" :language nil :response-format "json"
            :granularities #{"segment"}}
           (:ok (openai/parse-request config {"model" "whisper-1"})))))
  (testing "repeated granularities, language, ignored prompt and temperature"
    (is (= {:model "whisperx" :track-id "whisperx" :language "pt-BR" :response-format "verbose_json"
            :granularities #{"segment" "word"}}
           (:ok (openai/parse-request config {"model" " whisperx " "language" "pt-BR" "prompt" "Names: Ana"
                                              "temperature" "0.2" "response_format" "verbose_json"
                                              "timestamp_granularities[]" ["word" "segment"]})))))
  (testing "errors name the parameter"
    (doseq [[params code param] [[{} "missing_model" "model"]
                                 [{"model" ["a" "b"]} "missing_model" "model"]
                                 [{"model" "tiny"} "model_not_found" "model"]
                                 [{"model" "whisper-1" "response_format" "docx"} "invalid_response_format" "response_format"]
                                 [{"model" "whisper-1" "timestamp_granularities[]" "char"}
                                  "invalid_timestamp_granularities" "timestamp_granularities[]"]
                                 [{"model" "whisper-1" "stream" "true"} "unsupported_parameter" "stream"]]]
      (let [{:keys [error]} (openai/parse-request config params)]
        (is (= [400 code param] [(:status error) (:code error) (:param error)]) (pr-str params)))))
  (testing "a long unknown model is truncated in the message"
    (is (< (count (get-in (openai/parse-request config {"model" (apply str (repeat 500 "x"))}) [:error :message])) 200))))

(defn- render
  "Render `transcript` in `fmt` with optional granularities."
  ([fmt] (render fmt #{"segment"}))
  ([fmt granularities]
   (openai/render {:response-format fmt :granularities granularities} transcript)))

(deftest render-test
  (testing "json"
    (let [resp (render "json")]
      (is (= 200 (:status resp)))
      (is (str/starts-with? (get-in resp [:headers "Content-Type"]) "application/json"))
      (is (= {:text "Hello there. General <Kenobi>."} (cheshire/parse-string (:body resp) true)))))
  (testing "text"
    (is (= "Hello there. General <Kenobi>.\n" (:body (render "text")))))
  (testing "srt"
    (is (= (str "1\n00:00:00,000 --> 00:00:01,500\nAna: Hello there.\n\n"
                "2\n00:00:01,500 --> 00:00:03,250\nGeneral ‹Kenobi›.\n\n")
           (:body (render "srt")))))
  (testing "vtt"
    (let [body (:body (render "vtt"))]
      (is (str/starts-with? body "WEBVTT\n\n00:00:00.000 --> 00:00:01.500\n"))
      (is (str/includes? body "General &lt;Kenobi&gt;."))))
  (testing "verbose_json with segments"
    (let [doc (cheshire/parse-string (:body (render "verbose_json")) true)]
      (is (= {:task "transcribe" :language "en" :duration 3.25 :text "Hello there. General <Kenobi>."}
             (dissoc doc :segments)))
      (is (= [{:id 0 :seek 0 :start 0.0 :end 1.5 :text "Hello there."}
              {:id 1 :seek 0 :start 1.5 :end 3.25 :text "General <Kenobi>."}]
             (:segments doc)))))
  (testing "verbose_json with word timestamps only"
    (let [doc (cheshire/parse-string (:body (render "verbose_json" #{"word"})) true)]
      (is (nil? (:segments doc)))
      (is (= [{:word "Hello" :start 0.0 :end 0.5} {:word "there." :start 0.6 :end 1.5}] (:words doc)))))
  (testing "a text-only transcript"
    (let [resp (openai/render {:response-format "verbose_json" :granularities #{"segment"}}
                              {:segments [{:text "Only text"}] :full-text "" :lang nil})]
      (is (= {:task "transcribe" :language "" :duration 0.0 :text "Only text"
              :segments [{:id 0 :seek 0 :start 0.0 :end 0.0 :text "Only text"}]}
             (cheshire/parse-string (:body resp) true))))))

(deftest error-response-test
  (let [resp (openai/error-response 504 "transcription_timeout" "Not ready" nil {:session_id "s"})]
    (is (= 504 (:status resp)))
    (is (= {:error {:message "Not ready" :type "timeout_error" :param nil
                    :code "transcription_timeout" :session_id "s"}}
           (cheshire/parse-string (:body resp) true)))))
