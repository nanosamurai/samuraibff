(ns samuraibff.ui.components.shared
  "Shared UI building blocks and low-level helpers.

  This namespace is intentionally dependency-light so page namespaces can
  compose it without introducing circular requires."
  (:require
   [clojure.string :as str]
   [io.factorhouse.hsx.core :as hsx]
   [samuraibff.ui.langs :as langs]
   ["react" :as react]))

;; --- Formatting helpers ---

(defn iso->local
  "Best-effort formatting of an ISO timestamp into a local date/time string.

  Inputs:
  - s: string (ISO timestamp)

  Returns: string?"
  [s]
  (when (seq (str s))
    (try
      (.toLocaleString (js/Date. s))
      (catch :default _
        (str s)))))

;; --- UI primitives ---

(defn icon
  "Render a lightweight icon glyph (no external deps).

  Inputs:
  - s: string
  - opts: optional map {:title string}

  Returns: hiccup."
  ([s] (icon s nil))
  ([s {:keys [title]}]
   [:span {:class "icon" :title title} (or s "")]))

(defn status-pill
  "Render a compact session status pill.

  Inputs:
  - {:keys [label kind blink? tooltip]} where:
      label   => string (required)
      kind    => keyword? one of :ok :bad :warn :muted (optional; default :muted)
      blink?  => boolean (optional; when true, the dot blinks)
      tooltip => string? (optional)

  Returns: hiccup."
  [{:keys [label kind blink? tooltip]}]
  (let [kind (or kind :muted)
        tooltip (when (seq (str tooltip)) (str tooltip))]
    [:span {:class (str "badge " (name kind))
            :title tooltip}
     [:span {:class (str "rec-dot " (name kind) (when blink? " blink"))
             :title tooltip}]
     [:span {:style {:marginLeft "8px"}} (str (or label ""))]]))

;; --- Fetch / clipboard safety helpers ---

(defn safe-http-error
  "Return a safe string to show/log for fetch errors.

  Important:
  - never include response bodies (may contain secrets)
  - never include stack traces

  Inputs:
  - e: JS error

  Returns: string."
  [e]
  (let [msg (some-> e .-message str)]
    (if (seq msg) msg "Request failed")))

(defn copy-to-clipboard!
  "Copy text to clipboard (best effort).

  Inputs:
  - s: string

  Returns:
  - Promise resolving to true/false."
  [s]
  (let [s (str (or s ""))]
    (cond
      (and (exists? js/navigator)
           (exists? (.-clipboard js/navigator))
           (exists? (.-writeText (.-clipboard js/navigator))))
      (-> (.writeText (.-clipboard js/navigator) s)
          (.then (fn [_] true))
          (.catch (fn [_] false)))

      :else
      (js/Promise.resolve false))))

;; --- Searchable dropdown (language selection) ---

(defn- lang-option->search-haystack
  "Build a lowercase search string for a language option.

  Inputs:
  - opt: {:value string :label string :flag string}

  Returns: string."
  [{:keys [value label]}]
  (-> (str (or value "") " " (or label ""))
      str/lower-case))

(defn searchable-dropdown
  "A lightweight searchable dropdown.

  Used for language selection on Record.

  Inputs:
  - value: currently selected option value (string)
  - options: vector of options {:value string :label string :flag string}
  - placeholder: string shown when no matching option is found
  - on-change: (fn [new-value] ...)

  Returns: hiccup."
  [{:keys [value options placeholder on-change]}]
  (let [open?* (react/useState false)
        open? (aget open?* 0)
        set-open! (aget open?* 1)

        query* (react/useState "")
        query (aget query* 0)
        set-query! (aget query* 1)

        root-ref (react/useRef nil)
        search-ref (react/useRef nil)

        value (str (or value ""))
        placeholder (or placeholder "Select...")

        opts (vec (or options []))
        selected (or (first (filter (fn [o] (= (str (:value o)) value)) opts))
                     (first opts)
                     {:value value :label value :flag "🏳"})

        q (-> (str (or query "")) str/trim str/lower-case)
        visible-opts (if (str/blank? q)
                       opts
                       (->> opts
                            (filter (fn [o]
                                      (str/includes? (lang-option->search-haystack o) q)))
                            vec))]

    ;; Close on outside click.
    (react/useEffect
     (fn []
       (let [handler (fn [e]
                       (when (and (true? open?)
                                  (some? (.-current root-ref)))
                         (let [root (.-current root-ref)
                               target (.-target e)]
                           (when (and root (not (.contains root target)))
                             (set-open! false)
                             (set-query! "")))))]
         (.addEventListener js/document "mousedown" handler)
         (fn []
           (.removeEventListener js/document "mousedown" handler))))
     #js [open?])

    ;; Focus search when opening.
    (react/useEffect
     (fn []
       (when (and (true? open?) (some? (.-current search-ref)))
         (try
           (.focus (.-current search-ref))
           (catch :default _ nil)))
       js/undefined)
     #js [open?])

    (let [render-flag
          (fn [{:keys [flag flagIcon]}]
            (cond
              (= :svg (:type flagIcon))
              [:img {:class "flag-icon"
                     :src (:src flagIcon)
                     :alt (or (:alt flagIcon) "")
                     :loading "lazy"}]

              :else
              (or flag "")))

          trigger
          [:button {:type "button"
                    :class "dropdown-trigger"
                    :on-click (fn [_]
                                (set-open! (not open?)))}
           [:span {:class "dropdown-flag"} (render-flag selected)]
           [:span {:class "dropdown-label"} (or (:label selected) placeholder)]
           [:span {:class "dropdown-caret"} "v"]]

          menu
          (when open?
            [:div {:class "dropdown-menu"
                   :on-key-down (fn [e]
                                  (when (= "Escape" (.-key e))
                                    (set-open! false)
                                    (set-query! "")))}
             [:div {:class "dropdown-search"}
              [:input {:ref search-ref
                       :value query
                       :placeholder "Search..."
                       :on-change (fn [e]
                                    (set-query! (.. e -target -value)))}]]
             [:div {:class "dropdown-items"}
              (if (empty? visible-opts)
                [:div {:class "dropdown-empty muted"} "No matches"]
                (for [{:keys [value label flag flagIcon]} visible-opts]
                  [:button {:type "button"
                            :key (str "opt-" value)
                            :class (str "dropdown-item"
                                        (when (= (str value) (str (:value selected))) " active"))
                            :on-click (fn [_]
                                        (when (fn? on-change)
                                          (on-change (str value)))
                                        (set-open! false)
                                        (set-query! ""))}
                   [:span {:class "dropdown-flag"} (render-flag {:value value
                                                                 :label label
                                                                 :flag flag
                                                                 :flagIcon flagIcon})]
                   [:span {:class "dropdown-item-label"} (or label (str value))]
                   [:span {:class "dropdown-item-code muted"} (or value "")]]))]])]

      [:div {:class (str "dropdown" (when open? " open"))
             :ref root-ref}
       trigger
       menu])))

(defn lang-flag
  "Render a language flag hint.

  Inputs:
  - lang: string (ISO-639-1 code or blank)

  Returns: hiccup."
  [lang]
  (let [code (str (or lang ""))
        flag (langs/lang->flag code)
        title (when-not (str/blank? code)
                (str (langs/lang->display-name code) " (" code ")"))]
    [:span {:class "lang-flag" :title title}
     (or flag "")]))

(defn memo-clear!
  "Clear HSX memoization cache (used by core reload hook)."
  []
  (hsx/memo-clear!))
