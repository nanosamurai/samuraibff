(ns samuraibff.ui.components.corrections
  "Transcript correction controls for the recording page: the version
  toggle, the inline text editor, the speaker dialog, the word diff and the
  change history of a segment."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.corrections :as ui.corrections]
   [samuraibff.ui.icons :as icons]
   [samuraibff.ui.transcript :as transcript]
   [samuraibff.ui.util :as util]
   ["react" :as react]))

(defn view-toggle
  "Render the Corrected / Changes / Original switch.

  Inputs: {:keys [mode on-change active-count]}; mode is a keyword from
  `ui.corrections/modes`, on-change receives the new mode."
  [{:keys [mode on-change active-count]}]
  [:div {:class "correction-toggle" :role "group" :aria-label "Transcript version"}
   (for [[id label] ui.corrections/modes]
     [:button {:key (name id) :type "button" :class (str "btn ghost" (when (= id mode) " active"))
               :aria-pressed (= id mode) :on-click #(on-change id)}
      label])
   [:span {:class "muted"}
    (str active-count (if (= 1 active-count) " correction" " corrections"))]])

(defn diff-text
  "Render word runs from `corrections/word-diff`: removed words as <del>,
  added words as <ins>."
  [diff]
  (into [:span {:class "correction-diff"}]
        (interpose " "
                   (map (fn [[op text]]
                          (case op
                            :del [:del {:class "diff-del"} text]
                            :ins [:ins {:class "diff-ins"} text]
                            [:span text]))
                        diff))))

(defn text-editor
  "Inline editor for one segment's text.

  Inputs: {:keys [initial saving? on-save on-cancel]}; on-save receives the
  edited text. Ctrl/Cmd+Enter saves and Escape cancels."
  [{:keys [initial saving? on-save on-cancel]}]
  (let [[text set-text!] (react/useState (str initial))
        input-ref (react/useRef nil)]
    (react/useEffect
     (fn []
       (when-let [el (.-current input-ref)]
         (.focus el))
       js/undefined)
     #js [])
    [:div {:class "correction-editor" :on-click #(.stopPropagation %)}
     [:textarea {:ref input-ref :value text :disabled saving? :aria-label "Segment text"
                 :rows (max 2 (min 8 (inc (quot (count text) 70))))
                 :on-change #(set-text! (.. % -target -value))
                 :on-key-down (fn [e]
                                (cond
                                  (= "Escape" (.-key e)) (do (.preventDefault e) (on-cancel))
                                  (and (= "Enter" (.-key e)) (or (.-ctrlKey e) (.-metaKey e)))
                                  (do (.preventDefault e) (on-save text))))}]
     [:div {:class "row"}
      [:button {:class "btn primary" :type "button" :disabled saving? :on-click #(on-save text)}
       (if saving? "Saving…" "Save")]
      [:button {:class "btn ghost" :type "button" :disabled saving? :on-click #(on-cancel)} "Cancel"]
      [:span {:class "muted"} "Ctrl+Enter saves · Esc cancels"]]]))

(defn speaker-dialog
  "Modal that changes the speaker of a segment, or of every segment with the
  same machine label.

  Inputs: {:keys [msg source saving? error on-save on-close]}
  - msg: the display message of the segment (nil closes the dialog)
  - source: `ui.corrections/final-source`
  - on-save: receives {:scope :segment|:label :speakers [...] :name string}"
  [{:keys [msg source saving? error on-save on-close]}]
  (let [[speakers set-speakers!] (react/useState [])
        [speaker-name set-name!] (react/useState "")
        [scope set-scope!] (react/useState :label)
        machine-label (not-empty (get-in msg [:change :original :speaker]))
        open? (some? msg)]
    (react/useEffect
     (fn []
       (when open?
         (set-name! "")
         (set-scope! (if machine-label :label :segment))
         (-> (api/list-speakers!)
             (.then #(set-speakers! (vec (js->clj % :keywordize-keys true))))
             (.catch (fn [_] (set-speakers! [])))))
       js/undefined)
     #js [open? (:seq msg)])
    (when open?
      (let [save! #(on-save {:scope scope :speakers speakers :name speaker-name})
            blank? (str/blank? speaker-name)]
        [:div {:class "modal-overlay" :on-click #(on-close)}
         [:div {:class "modal" :role "dialog" :aria-modal true :aria-labelledby "correction-speaker-title"
                :on-click #(.stopPropagation %)}
          [:div {:class "modal-title" :id "correction-speaker-title"} "Change speaker"]
          [:p {:class "muted"} (str "Now: " (transcript/speaker->display-name (:speaker msg)))]
          [:fieldset {:class "correction-scope"}
           [:legend "Apply to"]
           (when machine-label
             [:label [:input {:type "radio" :name "correction-scope" :checked (= scope :label)
                              :on-change #(set-scope! :label)}]
              (str " Every segment of " (transcript/speaker->display-name machine-label))])
           [:label [:input {:type "radio" :name "correction-scope" :checked (= scope :segment)
                            :on-change #(set-scope! :segment)}]
            " Only this segment"]]
          [:div {:class "row"}
           [:input {:list "correction-speaker-names" :placeholder "Speaker name" :aria-label "Speaker name"
                    :value speaker-name :disabled saving? :auto-focus true
                    :on-change #(set-name! (.. % -target -value))
                    :on-key-down #(when (and (= "Enter" (.-key %)) (not blank?)) (save!))}]
           [:datalist {:id "correction-speaker-names"}
            (for [option (ui.corrections/speaker-names speakers source)]
              [:option {:key option :value option}])]
           [:button {:class "btn primary" :type "button" :disabled (or saving? blank?) :on-click save!}
            (if saving? "Saving…" "Save")]
           [:button {:class "btn" :type "button" :disabled saving? :on-click #(on-close)} "Cancel"]]
          [:p {:class "muted"} "Choose a saved speaker to link their voice profile, or type any name."]
          (when (seq error) [:p {:class "badge bad" :role "alert"} error])]]))))

(defn- when-label
  [correction]
  (some-> (:created_at correction) util/iso->ms util/fmt-local-ymd-hm))

(defn- edit-line
  [correction]
  (str (ui.corrections/describe correction) " · " (ui.corrections/author-label correction)
       (when-let [t (when-label correction)] (str " · " t))))

(defn change-footer
  "Render who changed a segment, an Undo for each change in effect and the
  segment's full history.

  Inputs: {:keys [change can-manage? busy? on-undo]}; change comes from
  `corrections/segment-changes`, on-undo receives the correction to undo.
  Returns nil when the segment has no corrections."
  [{:keys [change can-manage? busy? on-undo]}]
  (let [{:keys [text-edit speaker-edit history]} change
        active (remove nil? [text-edit speaker-edit])]
    (when (seq history)
      [:div {:class "correction-meta"}
       (for [edit active]
         [:div {:key (:id edit) :class "correction-line"}
          [:span (edit-line edit)]
          (when can-manage?
            [:button {:class "btn ghost correction-undo" :type "button" :disabled busy?
                      :title "Undo this change" :on-click #(on-undo edit)}
             (icons/icon :undo {:size 14}) "Undo"])])
       [:details
        [:summary (str "History (" (count history) ")")]
        [:ul {:class "correction-history"}
         (for [edit history]
           [:li {:key (:id edit) :class (when (:undone edit) "undone")}
            (edit-line edit)
            (when (:undone edit)
              (str " · undone by " (ui.corrections/author-label (:undone_by edit))))])]]])))

(defn segment-actions
  "Render the hover actions of a final transcript segment.

  Inputs: {:keys [on-edit on-speaker on-enroll]}; each is a function or nil
  when that action is not available."
  [{:keys [on-edit on-speaker on-enroll]}]
  (when (or on-edit on-speaker on-enroll)
    [:div {:class "bubble-actions"}
     (when on-edit
       [:button {:class "bubble-action-btn" :type "button" :title "Correct the text" :aria-label "Correct the text"
                 :on-click #(do (.stopPropagation %) (on-edit))}
        (icons/icon :pencil)])
     (when on-speaker
       [:button {:class "bubble-action-btn" :type "button" :title "Change the speaker" :aria-label "Change the speaker"
                 :on-click #(do (.stopPropagation %) (on-speaker))}
        (icons/icon :user-pen)])
     (when on-enroll
       [:button {:class "bubble-action-btn" :type "button" :title "Recognize this speaker in future transcripts"
                 :aria-label "Add speaker" :on-click #(do (.stopPropagation %) (on-enroll))}
        (icons/icon :plus)])]))
