(ns samuraibff.ui.components.transcript
  "Transcript-related rendering components.

  This namespace focuses purely on rendering transcript message feeds and
  final-transcript playback/karaoke UI."
  (:require
   [samuraibff.ui.karaoke :as karaoke]
   [samuraibff.ui.transcript :as transcript]
   [samuraibff.ui.util :as util]
   ["react" :as react]))

(defn- message-key
  [idx msg]
  (str "msg-" idx "-" (:seq msg) "-" (:ts_ms msg)))

(defn- badge
  [{:keys [kind final]}]
  (cond
    (= kind "refined")
    [:span {:class "badge refined"} "★ refined"]

    (and (= kind "asr") (false? final))
    [:span {:class "badge muted typing"
            :title "partial"}
     [:span {:class "typing-dots"}
      [:span]]]

    :else
    nil))

(defn transcript-view
  "Transcript display (Slack-like feed).

  Renders transcript as a message thread:
  - avatar
  - speaker name
  - timestamp
  - message bubble

  Refined messages are visually marked (★ refined).

  Inputs:
  - {:keys [messages empty-title empty-hint auto-scroll? initial-scroll]}

  Returns: hiccup." 
  [{:keys [messages empty-title empty-hint auto-scroll? initial-scroll]}]
  (let [msgs (->> (or messages [])
                  transcript/coalesce-asr-finals
                  vec)
        container-ref (react/useRef nil)
        ;; Auto-scroll unless the user scrolled up.
        ;; NOTE: for some views (e.g. final transcript playback) we disable this.
        auto-scroll? (if (some? auto-scroll?) (boolean auto-scroll?) true)
        initial-scroll (or initial-scroll :bottom)
        auto-scroll?* (react/useRef true)
        initial-scrolled?* (react/useRef false)]

    (react/useEffect
     (fn []
       (when-let [el (.-current container-ref)]
         (when (and auto-scroll? (true? (.-current auto-scroll?*)))
           (set! (.-scrollTop el) (.-scrollHeight el)))

         ;; One-time initial positioning for non-auto-scrolling views.
         (when (and (not auto-scroll?) (false? (.-current initial-scrolled?*)))
           (case initial-scroll
             :top (set! (.-scrollTop el) 0)
             :bottom (set! (.-scrollTop el) (.-scrollHeight el))
             nil)
           (set! (.-current initial-scrolled?*) true)))
       js/undefined)
     #js [(count msgs) auto-scroll? initial-scroll])

    [:div {:class "transcript"}
     (if (empty? msgs)
       [:div {:class "empty"}
        [:div {:class "empty-title"} (or empty-title "Transcript")]
        [:div {:class "muted"} (or empty-hint "No events yet…")]]
       [:div (cond-> {:class "transcript-feed"
                      :ref container-ref}
              auto-scroll?
              (assoc :on-scroll
                     (fn [e]
                       (let [el (.-target e)
                             dist (- (.-scrollHeight el)
                                     (.-scrollTop el)
                                     (.-clientHeight el))]
                         (set! (.-current auto-scroll?*) (<= dist 48))))))
        (for [[idx msg] (map-indexed vector msgs)]
          (let [k (message-key idx msg)
                speaker (:speaker msg)
                who (transcript/speaker->display-name speaker)
                avatar (transcript/speaker->avatar-text speaker)
                start-ts (util/fmt-sec (:start_s msg))
                end-ts (util/fmt-sec (:end_s msg))
                bubble-class (str "bubble" (when (and (= "asr" (:kind msg)) (false? (:final msg))) " draft"))]
            [:div {:class "msg" :key k}
             [:div {:class "avatar"} avatar]
             [:div {:class "msgBody"}
              [:div {:class "msgHeader"}
               [:span {:class "who"} who]
               [:span {:class "ts"} (str start-ts " → " end-ts)]
               (badge msg)]
              [:div {:class bubble-class} (:text msg)]]]))])]))

(defn final-transcript-karaoke
  "Render final transcript with word-level karaoke highlighting.

  Inputs:
  - messages: vector of final transcript messages (each may contain :words)
  - audio-ref: React ref to the <audio> element
  - current-time-s: double
  - follow?: boolean

  Returns: hiccup." 
  [{:keys [messages audio-ref current-time-s follow?]}]
  (let [msgs (vec (or messages []))
        word-index (react/useMemo (fn [] (karaoke/build-word-index msgs)) #js [msgs])
        active-flat-idx (karaoke/active-word-idx-normalized word-index current-time-s)
        active-word (when (some? active-flat-idx) (nth word-index active-flat-idx))
        active-msg-idx (:msg-idx active-word)
        active-word-idx (:word-idx active-word)
        active-el-ref (react/useRef nil)]

    ;; When there is no active word (timing gaps), clear the active element ref.
    ;; Otherwise Follow may keep scrolling to a stale word and cause flicker.
    (react/useEffect
     (fn []
       (when (nil? active-flat-idx)
         (set! (.-current active-el-ref) nil))
       js/undefined)
     #js [active-flat-idx])

    (react/useEffect
     (fn []
       (when (and (true? follow?)
                  (some? active-flat-idx)
                  (some? (.-current active-el-ref)))
         (try
           (.scrollIntoView (.-current active-el-ref)
                            #js {:block "nearest" :inline "nearest"})
           (catch :default _
             nil)))
       js/undefined)
     #js [follow? active-flat-idx])

    ;; Inline word rendering inside transcript-view bubbles.
    (let [rendered-msgs
          (mapv
           (fn [msg-idx m]
             (let [words (vec (or (:words m) []))]
               (if (empty? words)
                 m
                 (let [word-spans
                       (map-indexed
                        (fn [widx w]
                          (let [txt (karaoke/word-text w)
                                active? (and (= msg-idx active-msg-idx)
                                             (= widx active-word-idx))]
                            [:span
                             {:key (str "w-" msg-idx "-" widx "-" (double (or (:start_s w) 0.0)))
                              :class (str "word" (when active? " active"))
                              :ref (when active?
                                     (fn [el]
                                       (set! (.-current active-el-ref) el)))
                              :on-click (fn [_]
                                          (when-let [audio (.-current audio-ref)]
                                            (try
                                              (set! (.-currentTime audio) (double (or (:start_s w) 0.0)))
                                              ;; Autoplay requested.
                                              (-> (.play audio)
                                                  (.catch (fn [_] nil)))
                                              (catch :default _
                                                nil))))}
                             (if (seq txt) txt "")
                             " "]))
                        words)]
                   (assoc m :text
                          (into [:span {:class "karaoke"}]
                                word-spans))))))
           (range (count msgs))
           msgs)]
      [transcript-view
       {:messages rendered-msgs
        ;; Final transcript playback should not behave like a live chat.
        ;; Default to top and let karaoke "Follow" be the only source of scrolling.
        :auto-scroll? false
        :initial-scroll :top
        :empty-title "Final transcript"
        :empty-hint "No final transcript stored"}])))
