(ns samuraibff.ui.upload-form
  "Pure rules of the Upload page form for `POST /api/sessions/upload`.

  The page keeps its state in React; this namespace turns that state into
  display text and multipart text fields, so the rules are unit-tested.

  Form state shape (map):
  - :title     string typed by the user
  - :lang      language code string; empty means detect the language
  - :controls  stream controls map for the final stage only, such as
               {:final true :store_recording true :final_tracks [\"whisperx\"]};
               no :final_tracks key means the deployment default tracks

  Public API:
  - `initial-controls`, `format-bytes`, `title-from-file-name`, `too-large?`
  - `media-type-accepted?`, `selected-final-tracks`, `tracks-chosen?`, `form-fields`
  - `progress-percent`, `error-message`"
  (:require
   [clojure.string :as str]
   [samuraibff.ui.output-settings :as settings]))

(def max-title-length
  "Longest title the BFF accepts for an uploaded session."
  200)

(def initial-controls
  "Upload controls before the user changes anything: final output only,
  default final tracks, audio kept for playback."
  {:realtime false :refined false :final true :store_recording true})

(defn- round-to
  "Round a number to `digits` decimal places."
  [x digits]
  (let [scale (double (reduce * 1 (repeat digits 10)))]
    (/ (#?(:clj Math/round :cljs js/Math.round) (* x scale)) scale)))

(defn format-bytes
  "Format a byte count for people, with 1024-based units.

  Inputs: non-negative number of bytes.
  Returns: string such as \"512 bytes\", \"1.5 KB\", \"12 MB\" or \"1.2 GB\".
  Values under 10 units keep one decimal; a trailing `.0` is dropped."
  [bytes]
  (let [bytes (max 0 (or bytes 0))
        units ["KB" "MB" "GB" "TB"]]
    (if (< bytes 1024)
      (str (long bytes) (if (= 1 (long bytes)) " byte" " bytes"))
      (loop [value (/ bytes 1024) [unit & more] units]
        (if (and (>= value 1024) (seq more))
          (recur (/ value 1024) more)
          (let [shown (round-to value (if (< value 10) 1 0))
                text (str shown)]
            (str (str/replace text #"\.0$" "") " " unit)))))))

(defn title-from-file-name
  "Return a session title derived from a file name.

  Inputs: file name string (a path is reduced to its last part).
  Returns: the name without its last extension, trimmed and limited to
  `max-title-length` characters; a name that is only an extension, such as
  `.m4a`, is kept. Returns \"\" for a blank name."
  [file-name]
  (let [base (last (str/split (str (or file-name "")) #"[/\\]"))
        dot (str/last-index-of (str base) ".")
        stem (if (and dot (pos? dot)) (subs base 0 dot) (str base))
        title (str/trim stem)]
    (subs title 0 (min max-title-length (count title)))))

(defn too-large?
  "Return true when a file of `size` bytes is over the upload limit.

  Inputs: size in bytes, limit in bytes (0 or nil means no known limit)."
  [size limit]
  (and (number? limit) (pos? limit) (number? size) (> size limit)))

(def ^:private other-media-types
  "Declared types that browsers use for some audio or video containers."
  #{"application/ogg" "application/mp4" "application/octet-stream"})

(defn media-type-accepted?
  "Return true when a file's declared type may be audio or video.

  Inputs: the browser's `File.type` string; blank when the browser does not
  know it. Returns: boolean. This only catches obvious mistakes, such as a
  dropped PDF; the BFF checks the file content."
  [media-type]
  (let [media-type (str/lower-case (str/trim (str (or media-type ""))))]
    (or (str/blank? media-type)
        (str/starts-with? media-type "audio/")
        (str/starts-with? media-type "video/")
        (contains? other-media-types media-type))))

(defn selected-final-tracks
  "Return the final track IDs the form will produce, in catalog order.

  Inputs: controls map, `/api/me` detail map. Unset selections resolve to the
  tracks the deployment marks as default."
  [controls detail]
  (settings/selected-ids controls :final (settings/entries detail :final)))

(defn tracks-chosen?
  "Return true when the form selects at least one final transcript.

  Without configured final tracks the deployment default is used, so the
  form is always complete."
  [controls detail]
  (or (empty? (settings/entries detail :final))
      (boolean (seq (selected-final-tracks controls detail)))))

(defn form-fields
  "Return the multipart text fields for an upload, without the file.

  Inputs:
  - form: {:title string :lang string :controls map} (see namespace doc)
  - detail: `/api/me` detail map

  Returns: vector of [field-name value] string pairs in a stable order:
  - title when not blank (trimmed)
  - lang when not blank (blank means detect the language)
  - final_tracks as comma-separated IDs, only after the user changed the
    selection; otherwise the server applies its default tracks
  - store_recording \"true\" or \"false\"; more than one final track always
    sends \"true\", as the server requires"
  [{:keys [title lang controls]} detail]
  (let [controls (merge initial-controls controls)
        title (str/trim (str (or title "")))
        lang (str/trim (str (or lang "")))
        catalog (settings/entries detail :final)
        explicit? (and (seq catalog) (some? (:final_tracks controls)))
        store? (true? (:store_recording (settings/effective-controls controls detail)))]
    (cond-> []
      (seq title) (conj ["title" title])
      (seq lang) (conj ["lang" lang])
      explicit? (conj ["final_tracks" (str/join "," (selected-final-tracks controls detail))])
      true (conj ["store_recording" (if store? "true" "false")]))))

(defn progress-percent
  "Return whole percent uploaded, from 0 to 100.

  Inputs: bytes sent and total bytes (numbers; a missing total gives 0)."
  [loaded total]
  (if (and (number? loaded) (number? total) (pos? total))
    (-> (* 100 (/ loaded total)) #?(:clj Math/floor :cljs js/Math.floor) long (max 0) (min 100))
    0))

(defn error-message
  "Return a plain-language message for a failed upload.

  Inputs:
  - status: HTTP status integer, or nil for a network error
  - code: server error code string such as `file-too-large`, or nil
  - limit: upload limit in bytes (0 or nil when unknown)

  Returns: string."
  [status code limit]
  (let [limit-text (when (and (number? limit) (pos? limit))
                     (str " (" (format-bytes limit) ")"))]
    (case status
      nil "Could not reach the server. Check your connection and try again."
      401 "Your sign-in has expired. Sign in again, then upload the file again."
      403 "You do not have permission to upload files."
      413 (str "This file is larger than the upload limit" limit-text ". Choose a smaller file.")
      415 "This file type is not supported. Choose an audio or video file, such as MP3, M4A, WAV or MP4."
      400 (case code
            "empty-file" "This file is empty. Choose another file."
            "invalid-title" (str "The title is too long. Use at most " max-title-length " characters.")
            "invalid-lang" "This language is not available. Choose another language or Auto."
            "invalid-stream-controls" "These transcript choices are not available. Change them and try again."
            "The file could not be read. Choose an audio or video file and try again.")
      503 (if (= code "uploads-disabled")
            "File uploads are turned off for this installation."
            "The service is temporarily unavailable. Try again in a few minutes.")
      "The upload failed. Try again.")))
