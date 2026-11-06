(ns samuraibff.transcripts.export
  "Pure transcript export formatters: SRT, WebVTT, plain text, JSON and DOCX.

  Every formatter takes a sequence of `samuraibff.schemas/TranscriptSegment`
  maps ({:start_s :end_s :text :speaker? :words?}). Segments without timing
  (text-only results) are exported at 00:00:00. Text formatters return a
  string; `->docx` returns a byte array. The functions do no I/O and do not
  check access; callers pass only segments the reader may read."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str])
  (:import
   (java.io ByteArrayOutputStream)
   (java.nio.charset StandardCharsets)
   (java.util.zip ZipEntry ZipOutputStream)))

(def formats
  "Supported export formats, mapped to file extension and content type."
  {"srt" {:extension "srt" :content-type "application/x-subrip; charset=utf-8"}
   "vtt" {:extension "vtt" :content-type "text/vtt; charset=utf-8"}
   "txt" {:extension "txt" :content-type "text/plain; charset=utf-8"}
   "json" {:extension "json" :content-type "application/json; charset=utf-8"}
   "docx" {:extension "docx"
           :content-type "application/vnd.openxmlformats-officedocument.wordprocessingml.document"}})

(defn- seconds->ms
  "Convert seconds (number or nil) to non-negative whole milliseconds."
  [seconds]
  (if (and (number? seconds) (Double/isFinite (double seconds)) (pos? (double seconds)))
    (Math/round (* 1000.0 (double seconds)))
    0))

(defn format-timestamp
  "Format seconds as `HH:MM:SS<sep>mmm`.

  Inputs: seconds (number or nil; nil/negative/NaN become 0) and the
  millisecond separator: \",\" for SRT, \".\" for WebVTT.
  Returns: string, e.g. (format-timestamp 3723.5 \",\") => \"01:02:03,500\"."
  [seconds sep]
  (let [ms (seconds->ms seconds)]
    (format "%02d:%02d:%02d%s%03d"
            (quot ms 3600000) (rem (quot ms 60000) 60) (rem (quot ms 1000) 60) sep (rem ms 1000))))

(defn- clock
  "Format seconds as `HH:MM:SS` for text and DOCX exports."
  [seconds]
  (subs (format-timestamp seconds ".") 0 8))

(defn- one-line
  "Collapse all whitespace runs (including newlines) to single spaces.

  A blank line would end an SRT/VTT cue early, so cue text stays on one line."
  [s]
  (str/trim (str/replace (str (or s "")) #"\s+" " ")))

(defn- speaker
  "Return the segment's trimmed speaker label, or nil when absent/blank."
  [segment]
  (not-empty (one-line (:speaker segment))))

(defn- cue-times
  "Return [start-s end-s] for a segment; end is never before start."
  [{:keys [start_s end_s]}]
  [start_s (if (and (number? end_s) (number? start_s) (>= end_s start_s)) end_s start_s)])

(defn- speaker-line
  "Return `Speaker: text`, or just the text when the speaker is unknown."
  [segment]
  (let [text (one-line (:text segment))]
    (if-let [who (speaker segment)] (str who ": " text) text)))

(defn- exportable
  "Drop segments whose text is blank; they would produce empty cues."
  [segments]
  (remove #(str/blank? (:text %)) segments))

(defn ->srt
  "Render segments as SubRip (SRT): numbered cues, `HH:MM:SS,mmm` times and
  `Speaker: ` prefixes. Returns a string (empty for no segments)."
  [segments]
  (apply str
         (map-indexed (fn [idx segment]
                        (let [[start end] (cue-times segment)]
                          (str (inc idx) "\n"
                               (format-timestamp start ",") " --> " (format-timestamp end ",") "\n"
                               (speaker-line segment) "\n\n")))
                      (exportable segments))))

(defn- vtt-escape
  "Escape `&`, `<` and `>` as WebVTT cue text requires."
  [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn ->vtt
  "Render segments as WebVTT: `HH:MM:SS.mmm` times and `<v Speaker>` voice
  tags. Cue text and speaker names are escaped. Returns a string that always
  starts with the `WEBVTT` header."
  [segments]
  (apply str "WEBVTT\n\n"
         (map (fn [segment]
                (let [[start end] (cue-times segment)
                      text (vtt-escape (one-line (:text segment)))]
                  (str (format-timestamp start ".") " --> " (format-timestamp end ".") "\n"
                       (if-let [who (speaker segment)] (str "<v " (vtt-escape who) ">" text) text)
                       "\n\n")))
              (exportable segments))))

(defn- timed?
  "True when the segment carries a numeric start time."
  [segment]
  (number? (:start_s segment)))

(defn- text-lines
  "Return one `[HH:MM:SS] Speaker: text` line per segment.

  The time prefix is omitted for text-only segments."
  [segments]
  (map (fn [segment]
         (str (when (timed? segment) (str "[" (clock (:start_s segment)) "] "))
              (speaker-line segment)))
       (exportable segments)))

(defn ->txt
  "Render segments as plain text, one `[HH:MM:SS] Speaker: text` line each.
  Returns a string (empty for no segments)."
  [segments]
  (apply str (map #(str % "\n") (text-lines segments))))

(defn json-document
  "Build the stable JSON export document.

  Inputs: metadata map {:session_id :title :stage :track_id :lang} (values
  may be nil) and segments. Returns a map with those keys, `:text` (all
  segment text joined by spaces) and `:segments`, each limited to
  {:start_s :end_s :speaker :text :words}."
  [metadata segments]
  (let [segments (vec (exportable segments))]
    (assoc (select-keys metadata [:session_id :title :stage :track_id :lang])
           :text (str/join " " (map #(one-line (:text %)) segments))
           :segments (mapv (fn [segment]
                             (cond-> (select-keys segment [:start_s :end_s :speaker :text])
                               (seq (:words segment))
                               (assoc :words (mapv #(select-keys % [:start_s :end_s :text]) (:words segment)))))
                           segments))))

(defn ->json
  "Render `json-document` as a pretty-printed JSON string."
  [metadata segments]
  (cheshire/generate-string (json-document metadata segments) {:pretty true}))

(defn- xml-escape
  "Escape XML special characters and drop characters XML 1.0 forbids."
  [s]
  (-> (str s)
      (str/replace #"[\x00-\x08\x0B\x0C\x0E-\x1F￾￿]" "")
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- docx-paragraph
  "Return one WordprocessingML paragraph; `bold?` marks a heading line."
  [text bold?]
  (str "<w:p><w:r>" (when bold? "<w:rPr><w:b/></w:rPr>")
       "<w:t xml:space=\"preserve\">" (xml-escape text) "</w:t></w:r></w:p>"))

(def ^:private docx-static-parts
  "Package parts that do not depend on the transcript."
  {"[Content_Types].xml"
   (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        "<Override PartName=\"/word/document.xml\" "
        "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
        "</Types>")
   "_rels/.rels"
   (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        "<Relationship Id=\"rId1\" "
        "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" "
        "Target=\"word/document.xml\"/>"
        "</Relationships>")})

(defn- docx-document-xml
  "Return `word/document.xml`: a bold title line, then one paragraph per segment."
  [title segments]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
       (docx-paragraph (or (not-empty (one-line title)) "Transcript") true)
       (apply str (map #(docx-paragraph % false) (text-lines segments)))
       "</w:body></w:document>"))

(defn ->docx
  "Render segments as a minimal Word (DOCX) package built with java.util.zip.

  Inputs: document title (string or nil) and segments. Each paragraph reads
  `[HH:MM:SS] Speaker: text`. Returns a byte array."
  [title segments]
  (let [out (ByteArrayOutputStream.)
        parts (assoc docx-static-parts "word/document.xml" (docx-document-xml title segments))]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [part-name ["[Content_Types].xml" "_rels/.rels" "word/document.xml"]]
        (.putNextEntry zip (ZipEntry. ^String part-name))
        (.write zip (.getBytes ^String (get parts part-name) StandardCharsets/UTF_8))
        (.closeEntry zip)))
    (.toByteArray out)))
