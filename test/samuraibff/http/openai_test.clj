(ns samuraibff.http.openai-test
  "Tests for POST /v1/audio/transcriptions.

  Docker-free: S3 is in memory, session creation and the Kafka publish are
  stubbed, and transcript readiness is stubbed with
  `samuraibff.http.openai/fetch-final-transcript`. The server tests bind
  127.0.0.1 on an ephemeral port and use the guest principal. The OpenAI
  Python client test runs only when `python` (or the interpreter named by
  SAMURAIBFF_TEST_PYTHON) can import the `openai` package."
  (:require
   [cheshire.core :as cheshire]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [org.httpkit.server :as httpkit]
   [samuraibff.http.openai :as http.openai]
   [samuraibff.http.router :as router]
   [samuraibff.kafka.producer :as kafka.producer]
   [samuraibff.s3.client :as s3.client]
   [samuraibff.sessions.file-source :as file-source]
   [samuraibff.test-support.fake-s3 :as fake-s3]
   [samuraibff.test-support.principal-store :as store]
   [samuraibff.test-support.principals :as tp]
   [samuraibff.transcripts.openai-test :as openai-test])
  (:import
   (java.io ByteArrayInputStream ByteArrayOutputStream File)
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
   (java.util.concurrent TimeUnit)))

(def ^:private boundary "nanosamuraiOpenAiBoundary")

(def ^:private wav
  "Bytes that start like a WAV file."
  (let [head (.getBytes "RIFF\u0000\u0000\u0000\u0000WAVEfmt " "ISO-8859-1")
        out (byte-array 200 (byte 1))]
    (System/arraycopy head 0 out 0 (alength head))
    out))

(defn- multipart-body
  "Build a multipart/form-data body from [name value] pairs; byte values are
  file parts."
  ^bytes [parts]
  (let [out (ByteArrayOutputStream.)
        w (fn [^String s] (.write out (.getBytes s "UTF-8")))]
    (doseq [[part-name value] parts]
      (w (str "--" boundary "\r\n"))
      (if (bytes? value)
        (do (w (str "Content-Disposition: form-data; name=\"" part-name "\"; filename=\"audio.wav\"\r\n"
                    "Content-Type: audio/wav\r\n\r\n"))
            (.write out ^bytes value))
        (do (w (str "Content-Disposition: form-data; name=\"" part-name "\"\r\n\r\n"))
            (w value)))
      (w "\r\n"))
    (w (str "--" boundary "--\r\n"))
    (.toByteArray out)))

(def ^:private guest
  (store/row->principal (store/guest-row)))

(defn- request
  "Return a Ring request for principal with a multipart body."
  [principal parts]
  (let [body (multipart-body parts)]
    (tp/request-for principal
                    {:request-method :post
                     :uri "/v1/audio/transcriptions"
                     :headers {"content-type" (str "multipart/form-data; boundary=" boundary)
                               "content-length" (str (alength body))}
                     :body (ByteArrayInputStream. body)})))

(def ^:private config
  {:auth {:required? false}
   :uploads {:max-bytes 4096}
   :final-tracks ["whisperx" "qwen"]
   :default-tracks {:final "whisperx"}
   :s3 {:buckets {:recordings {:bucket "recordings-bucket" :prefix "recordings"}}}
   :openai {:wait-timeout-s 1 :max-concurrent-waits 4 :poll-interval-ms 10}})

(def ^:private deps
  {:config config
   :db {:ds :principal-store}
   :kafka-producer {:producer (Object.)}})

(defn- with-stubs
  "Call f with in-memory S3, stubbed session creation and Kafka, and
  `fetch` as the transcript lookup. Returns {:result :s3 :published}."
  [fetch f]
  (let [{:keys [client state]} (fake-s3/fake-s3)
        published (atom [])]
    (with-redefs [s3.client/build-s3-client (constantly client)
                  file-source/start-session! (constantly nil)
                  kafka.producer/send-recording-uploaded! (fn [_ event _] (swap! published conj event))
                  http.openai/fetch-final-transcript fetch
                  http.openai/upload-failed? (constantly false)]
      {:result (f) :s3 @state :published @published})))

(defn- error-of
  "Decode an OpenAI error response to [status code param]."
  [resp]
  (let [error (:error (cheshire/parse-string (:body resp) true))]
    [(:status resp) (:code error) (:param error)]))

(def ^:private ready (constantly openai-test/transcript))

(deftest response-formats-test
  (doseq [[fmt check] [["json" #(= {:text "Hello there. General <Kenobi>."} (cheshire/parse-string % true))]
                       ["text" #(= "Hello there. General <Kenobi>.\n" %)]
                       ["srt" #(str/starts-with? % "1\n00:00:00,000 --> 00:00:01,500\nAna: Hello there.")]
                       ["vtt" #(str/starts-with? % "WEBVTT\n\n")]
                       ["verbose_json" #(= 2 (count (:segments (cheshire/parse-string % true))))]]]
    (testing fmt
      (let [{:keys [result published]}
            (with-stubs ready #((http.openai/transcriptions-handler deps)
                                (request guest [["file" wav] ["model" "whisper-1"] ["language" "en"]
                                                ["prompt" "ignored"] ["response_format" fmt]])))]
        (is (= 200 (:status result)))
        (is (check (:body result)) (:body result))
        (is (= "en" (:lang (first published))))))))

(deftest model-selects-final-track-test
  (let [lookups (atom [])
        {:keys [result]} (with-stubs (fn [_ _ _ track-id] (swap! lookups conj track-id) openai-test/transcript)
                           #((http.openai/transcriptions-handler deps)
                             (request guest [["model" "qwen"] ["file" wav]])))]
    (is (= 200 (:status result)))
    (is (= ["qwen"] @lookups))))

(deftest request-errors-test
  (doseq [[label principal parts expected] [["unknown model" guest [["file" wav] ["model" "gpt-4o-transcribe"]] [400 "model_not_found" "model"]]
                                            ["missing model" guest [["file" wav]] [400 "missing_model" "model"]]
                                            ["missing file" guest [["model" "whisper-1"]] [400 "missing-file" "file"]]
                                            ["bad format" guest [["file" wav] ["model" "whisper-1"] ["response_format" "docx"]]
                                             [400 "invalid_response_format" "response_format"]]
                                            ["bad language" guest [["file" wav] ["model" "whisper-1"] ["language" "en; x"]]
                                             [400 "invalid-lang" "language"]]
                                            ["no record permission" nil [["file" wav] ["model" "whisper-1"]] [403 "forbidden" nil]]]]
    (testing label
      (let [{:keys [result s3 published]}
            (with-stubs ready #((http.openai/transcriptions-handler deps) (request principal parts)))]
        (is (= expected (error-of result)))
        (is (empty? (:objects s3)) "no file is kept")
        (is (empty? published) "no transcription is queued")))))

(deftest timeout-returns-session-id-test
  (let [{:keys [result published]}
        (with-stubs (constantly nil) #((http.openai/transcriptions-handler deps)
                                       (request guest [["file" wav] ["model" "whisper-1"]])))
        error (:error (cheshire/parse-string (:body result) true))]
    (is (= 504 (:status result)))
    (is (= "transcription_timeout" (:code error)))
    (is (= (:session-id (first published)) (:session_id error)))
    (is (str/includes? (:message error) (str "/api/recordings/" (:session_id error))))))

(deftest unconvertible-upload-stops-waiting-test
  (let [{:keys [result published]}
        (with-stubs (constantly nil)
          #(with-redefs [http.openai/upload-failed? (constantly true)]
             ((http.openai/transcriptions-handler deps)
              (request guest [["file" wav] ["model" "whisper-1"]]))))
        error (:error (cheshire/parse-string (:body result) true))]
    (is (= 400 (:status result)))
    (is (= "unreadable_file" (:code error)))
    (is (= "file" (:param error)))
    (is (= (:session-id (first published)) (:session_id error)))))

(deftest concurrent-waits-are-capped-test
  (let [release (promise)
        handler (http.openai/transcriptions-handler
                 (assoc-in deps [:config :openai] {:wait-timeout-s 30 :max-concurrent-waits 1 :poll-interval-ms 10}))
        call #(handler (request guest [["file" wav] ["model" "whisper-1"]]))]
    (with-stubs (fn [& _] (when (realized? release) openai-test/transcript))
      (fn []
        (let [first-call (future (call))]
          (Thread/sleep 200)
          (is (= [429 "too_many_pending_transcriptions" nil] (error-of (call))))
          (deliver release true)
          (is (= 200 (:status (deref first-call 5000 nil))))
          (is (= 200 (:status (call))) "the slot is released after the response"))))))

(deftest auth-errors-use-openai-shape-test
  (let [handler (router/create-router (assoc-in deps [:config :auth] {:required? true :issuer "https://issuer.test"}))
        resp (handler {:request-method :post :uri "/v1/audio/transcriptions"
                       :headers {"content-type" (str "multipart/form-data; boundary=" boundary)}
                       :body (ByteArrayInputStream. (multipart-body [["model" "whisper-1"]]))})
        body (cheshire/parse-string (let [b (:body resp)] (if (string? b) b (slurp b))) true)]
    (is (= 401 (:status resp)))
    (is (= {:message "Missing or invalid bearer token" :type "authentication_error"
            :param nil :code "invalid_api_key"}
           (:error body)))))

(defn- with-server
  "Run the real router on 127.0.0.1 with the guest principal; call (f base-url)."
  [f]
  (store/with-store
    (atom {:principals [(store/guest-row)]})
    (fn []
      (let [server (httpkit/run-server (router/create-router deps)
                                       {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
        (try
          (f (str "http://127.0.0.1:" (httpkit/server-port server)))
          (finally (httpkit/server-stop! server)))))))

(defn- post!
  "POST a multipart body with java.net.http. Returns {:status :body}."
  [url parts]
  (let [resp (.send (HttpClient/newHttpClient)
                    (-> (HttpRequest/newBuilder (URI. url))
                        (.header "Content-Type" (str "multipart/form-data; boundary=" boundary))
                        (.header "Authorization" "Bearer unused-in-guest-mode")
                        (.POST (HttpRequest$BodyPublishers/ofByteArray (multipart-body parts)))
                        (.build))
                    (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :body (.body resp)}))

(deftest server-multipart-request-test
  (let [polls (atom 0)
        {:keys [result]}
        (with-stubs (fn [& _] (when (> (swap! polls inc) 2) openai-test/transcript))
          #(with-server
             (fn [base]
               [(post! (str base "/v1/audio/transcriptions")
                       [["file" wav] ["model" "whisper-1"] ["response_format" "srt"]
                        ["timestamp_granularities[]" "segment"] ["timestamp_granularities[]" "word"]
                        ["temperature" "0"]])
                (post! (str base "/v1/audio/transcriptions") [["file" wav] ["model" "nope"]])])))
        [ok unknown] result]
    (is (= 200 (:status ok)))
    (is (str/starts-with? (:body ok) "1\n00:00:00,000 --> 00:00:01,500\n"))
    (is (< 2 @polls) "the response waits for the transcript")
    (is (= 400 (:status unknown)))
    (is (= "model_not_found" (get-in (cheshire/parse-string (:body unknown) true) [:error :code])))))

(def ^:private openai-client-script
  "Transcribe a file with the official OpenAI Python client and print the SRT."
  (str "import sys\n"
       "from openai import OpenAI\n"
       "client = OpenAI(base_url=sys.argv[1] + '/v1', api_key='unused-in-guest-mode', max_retries=0)\n"
       "with open(sys.argv[2], 'rb') as audio:\n"
       "    srt = client.audio.transcriptions.create(model='whisper-1', file=audio, response_format='srt')\n"
       "sys.stdout.buffer.write(srt.encode('utf-8'))\n"))

(def ^:private python
  "Python interpreter for the OpenAI client test."
  (or (not-empty (System/getenv "SAMURAIBFF_TEST_PYTHON")) "python"))

(defn- openai-python?
  "True when `python` can import the openai package."
  []
  (try
    (let [p (.start (ProcessBuilder. [python "-I" "-c" "import openai"]))]
      (and (.waitFor p 30 TimeUnit/SECONDS) (zero? (.exitValue p))))
    (catch Exception _ false)))

(deftest official-openai-python-client-test
  (if-not (openai-python?)
    (println "SKIP official-openai-python-client-test: python with the openai package is not available")
    (let [script (doto (File/createTempFile "openai-client" ".py") (.deleteOnExit))
          audio (doto (File/createTempFile "audio" ".wav") (.deleteOnExit))
          _ (spit script openai-client-script)
          _ (io/copy wav audio)
          {:keys [result]}
          (with-stubs ready
            #(with-server
               (fn [base]
                 (let [p (-> (ProcessBuilder. [python "-I" (str script) base (str audio)])
                             (.redirectErrorStream true)
                             (.start))
                       out (future (slurp (.getInputStream p) :encoding "UTF-8"))]
                   (when (.waitFor p 60 TimeUnit/SECONDS)
                     {:exit (.exitValue p) :out @out})))))]
      (is (= 0 (:exit result)) (:out result))
      (is (= (str "1\n00:00:00,000 --> 00:00:01,500\nAna: Hello there.\n\n"
                  "2\n00:00:01,500 --> 00:00:03,250\nGeneral ‹Kenobi›.\n\n")
             (:out result))))))
