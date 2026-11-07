(ns samuraibff.ui.components.pages.upload
  "Upload page: transcribe an existing audio or video file.

  Sends the file with `POST /api/sessions/upload` and opens the new session.
  Language and final transcript choices reuse the Record page controls. Form
  rules live in `samuraibff.ui.upload-form`."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.components.stream-controls :as stream-controls]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.icons :as icons]
   [samuraibff.ui.langs :as langs]
   [samuraibff.ui.permissions :as permissions]
   [samuraibff.ui.router :as router]
   [samuraibff.ui.store :as store]
   [samuraibff.ui.upload-api :as upload-api]
   [samuraibff.ui.upload-form :as upload-form]
   ["react" :as react]))

(def ^:private accepted-types
  "File picker filter; the BFF checks the file content."
  "audio/*,video/*")

(defn- file-rejection
  "Return why a chosen file cannot be uploaded, or nil.

  Inputs: js/File and the upload limit in bytes (0 when unknown)."
  [file limit]
  (cond
    (not (upload-form/media-type-accepted? (.-type file)))
    (str "\"" (.-name file) "\" is not an audio or video file. Choose an audio or video file.")

    (upload-form/too-large? (.-size file) limit)
    (str "\"" (.-name file) "\" is " (upload-form/format-bytes (.-size file))
         ". The upload limit is " (upload-form/format-bytes limit) ". Choose a smaller file.")))

(defn- drop-zone
  "Render the file drop area with a Choose file button.

  Inputs (map):
  - :file       selected js/File or nil
  - :limit      upload limit in bytes (0 when unknown)
  - :disabled?  true while uploading
  - :error      file rejection message or nil
  - :on-file    (fn [js/File]) for a chosen or dropped file
  - :on-clear   (fn []) to remove the selected file

  Returns: hiccup. The button is the keyboard control; the area also accepts
  a dropped file."
  [{:keys [file limit disabled? error on-file on-clear]}]
  (let [[dragging? set-dragging!] (react/useState false)
        input-ref (react/useRef nil)
        choose! #(some-> (.-current input-ref) .click)
        take-first! (fn [files]
                      (when (and files (pos? (.-length files)))
                        (on-file (aget files 0))))]
    [:div {:class (str "drop-zone" (when dragging? " dragging") (when file " has-file"))
           :on-drag-over (fn [e]
                           (.preventDefault e)
                           (when-not disabled? (set-dragging! true)))
           :on-drag-leave (fn [e]
                            (when-not (.contains (.-currentTarget e) (.-relatedTarget e))
                              (set-dragging! false)))
           :on-drop (fn [e]
                      (.preventDefault e)
                      (set-dragging! false)
                      (when-not disabled?
                        (take-first! (.. e -dataTransfer -files))))}
     [:input {:type "file" :ref input-ref :accept accepted-types :style {:display "none"}
              :on-change (fn [e]
                           (take-first! (.. e -target -files))
                           (set! (.. e -target -value) ""))}]
     (if file
       [:div {:class "drop-zone-file"}
        [:span {:class "drop-zone-icon"} (icons/icon :upload {:size 20})]
        [:div {:class "drop-zone-file-copy"}
         [:strong {:title (.-name file)} (.-name file)]
         [:span {:class "muted"} (upload-form/format-bytes (.-size file))]]
        [:div {:class "spacer"}]
        [:button {:class "btn" :type "button" :on-click choose!} "Choose another file"]
        [:button {:class "btn ghost icon-only" :type "button" :on-click on-clear
                  :aria-label (str "Remove " (.-name file)) :title "Remove file"}
         (icons/icon :x)]]
       [:div {:class "drop-zone-empty"}
        [:span {:class "drop-zone-icon"} (icons/icon :upload {:size 28})]
        [:strong "Drop an audio or video file here"]
        [:button {:class "btn" :type "button" :on-click choose!} "Choose file"]
        [:span {:class "hint"}
         (str (when (pos? limit) (str "Up to " (upload-form/format-bytes limit) ". "))
              "MP3, M4A, WAV, MP4, WebM and other common formats.")]])
     (when error
       [:p {:class "error" :role "alert"} error])]))

(defn- upload-progress
  "Render upload progress with an announced percentage.

  Inputs: {:loaded bytes :total bytes}. Returns: hiccup."
  [{:keys [loaded total]}]
  (let [percent (upload-form/progress-percent loaded total)
        sent? (>= percent 100)]
    [:div {:class "upload-progress"}
     [:div {:class "progress-track" :role "progressbar" :aria-label "Upload progress"
            :aria-valuemin 0 :aria-valuemax 100 :aria-valuenow percent
            :aria-valuetext (str percent "% uploaded")}
      [:div {:class "progress-fill" :style {:width (str percent "%")}}]]
     [:span {:class "muted"}
      (if sent?
        "Preparing your session…"
        (str percent "% · " (upload-form/format-bytes loaded) " of " (upload-form/format-bytes total)))]
     [:span {:class "sr-only" :aria-live "polite"}
      (if sent? "Upload complete. Preparing your session." (str (* 10 (quot percent 10)) "% uploaded"))]]))

(defn- use-leave-warning
  "React hook: ask the browser to confirm closing the tab while `active?`."
  [active?]
  (react/useEffect
   (fn []
     (if active?
       (let [handler (fn [e] (.preventDefault e) (set! (.-returnValue e) ""))]
         (.addEventListener js/window "beforeunload" handler)
         (fn [] (.removeEventListener js/window "beforeunload" handler)))
       js/undefined))
   #js [active?]))

(defn- upload-card
  "Render the upload form, send the file and open the new session.

  Leaving the page during an upload cancels it. Returns: hiccup."
  []
  (let [detail (:detail (hooks/use-atom store/auth*))
        limit (store/uploads-max-bytes)
        [file set-file!] (react/useState nil)
        [file-error set-file-error!] (react/useState nil)
        [title set-title!] (react/useState "")
        [auto-title set-auto-title!] (react/useState "")
        [lang set-lang!] (react/useState "")
        [controls set-controls!] (react/useState upload-form/initial-controls)
        [progress set-progress!] (react/useState nil)
        [error set-error!] (react/useState nil)
        [notice set-notice!] (react/useState nil)
        abort-ref (react/useRef nil)
        error-ref (react/useRef nil)
        uploading? (some? progress)
        sent? (and uploading? (>= (upload-form/progress-percent (:loaded progress) (:total progress)) 100))
        tracks-chosen? (upload-form/tracks-chosen? controls detail)
        can-submit? (and file tracks-chosen? (not uploading?))
        choose-file! (fn [chosen]
                       (set-error! nil)
                       (set-notice! nil)
                       (if-let [reason (file-rejection chosen limit)]
                         (do (set-file! nil) (set-file-error! reason))
                         (let [derived (upload-form/title-from-file-name (.-name chosen))]
                           (set-file-error! nil)
                           (set-file! chosen)
                           (when (or (str/blank? title) (= title auto-title))
                             (set-title! derived))
                           (set-auto-title! derived))))
        clear-file! (fn []
                      (set-file! nil)
                      (set-file-error! nil)
                      (when (= title auto-title) (set-title! ""))
                      (set-auto-title! ""))
        submit! (fn []
                  (when can-submit?
                    (set-error! nil)
                    (set-notice! nil)
                    (set-progress! {:loaded 0 :total (.-size file)})
                    (store/append-log! "[ui] uploading file")
                    (let [{:keys [promise abort!]}
                          (upload-api/upload-session!
                           {:file file
                            :fields (upload-form/form-fields {:title title :lang lang :controls controls} detail)
                            :on-progress (fn [loaded total] (set-progress! {:loaded loaded :total total}))})]
                      (set! (.-current abort-ref) abort!)
                      (-> promise
                          (.then (fn [{:keys [session_id]}]
                                   (set! (.-current abort-ref) nil)
                                   (store/note-uploaded-session! session_id)
                                   (store/append-log! (str "[ui] uploaded file as session " session_id))
                                   (router/navigate! {:page :recording :params {:session_id session_id}})))
                          (.catch (fn [e]
                                    (set! (.-current abort-ref) nil)
                                    (set-progress! nil)
                                    (let [{:keys [aborted? status code]} (ex-data e)]
                                      (if aborted?
                                        (set-notice! "Upload cancelled.")
                                        (do (store/append-log! (str "[ui] upload failed: " (shared/safe-http-error e)))
                                            (set-error! (upload-form/error-message status code limit)))))))))))
        cancel! #(some-> (.-current abort-ref) (apply []))]
    (react/useEffect
     (fn [] (fn [] (some-> (.-current abort-ref) (apply []))))
     #js [])
    (react/useEffect
     (fn []
       (when error (some-> (.-current error-ref) .focus))
       js/undefined)
     #js [error])
    (use-leave-warning uploading?)
    [:section {:class "card upload-card" :aria-label "Upload a file"}
     [:fieldset {:class "stage-fieldset upload-fieldset" :disabled uploading?}
      [drop-zone {:file file :limit limit :disabled? uploading? :error file-error
                  :on-file choose-file! :on-clear clear-file!}]
      [:div {:class "controls-row"}
       [:div {:class "field"}
        [:label {:class "label" :for "upload-title"} "Session name"]
        [:input {:id "upload-title" :value title :max-length upload-form/max-title-length
                 :placeholder "Untitled session"
                 :on-change #(set-title! (.. % -target -value))}]]
       [:div {:class "field"}
        [:span {:class "label"} "Language"]
        [shared/searchable-dropdown {:value lang :options (langs/language-options) :placeholder "Auto"
                                     :aria-label "Language" :disabled uploading?
                                     :on-change set-lang!}]]]
      [:div {:class "stage-settings upload-settings"}
       [:div {:class "stage-heading"}
        [:h3 "Transcripts"]
        [:p {:class "muted"} "A complete transcript is prepared from the whole file. Auto language detects the spoken language."]]
       [stream-controls/track-picker {:stage :final :controls controls :detail detail
                                      :on-update (fn [f & args] (set-controls! #(apply f % args)))
                                      :hint "Each choice creates a separate transcript."}]
       (when-not tracks-chosen?
         [:p {:class "error" :role "alert"} "Choose at least one transcript."])
       [stream-controls/retention-field {:controls controls :detail detail
                                         :on-change (fn [keep?] (set-controls! #(assoc % :store_recording keep?)))}]]]
     (when error
       [:p {:class "badge bad upload-error" :role "alert" :tab-index -1 :ref error-ref} error])
     (when notice
       [:p {:class "muted" :role "status"} notice])
     [:div {:class "row upload-actions"}
      [:button {:class "btn primary" :type "button" :disabled (not can-submit?) :on-click submit!}
       (icons/icon :upload)
       (if uploading? "Uploading…" "Upload and transcribe")]
      (when (and uploading? (not sent?))
        [:button {:class "btn" :type "button" :on-click cancel!} "Cancel"])
      (when uploading?
        [upload-progress progress])]]))

(defn upload-page
  "Upload page. Shows the form to principals who may record when the BFF
  reports file uploads as turned on.

  Returns: hiccup."
  []
  (let [{:keys [status]} (hooks/use-atom store/auth*)
        can-record? (permissions/use-capability? :record)
        loading? (contains? #{:unknown :loading} status)]
    [:div {:class "page"}
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "Upload"]
       [:div {:class "muted"} "Transcribe an audio or video file."]]
      [:div {:class "row"}
       [router/link {:route {:page :recordings :params {}} :class "btn"} "Sessions"]]]
     (cond
       loading? [:p {:class "muted" :role "status"} "Loading…"]
       (not can-record?) [:div {:class "card" :role "status"}
                          [:p {:class "muted"} "You do not have permission to upload files."]]
       (not (store/uploads-enabled?)) [:div {:class "card" :role "status"}
                                       [:p {:class "muted"} "File uploads are turned off for this installation."]]
       :else [upload-card])]))
