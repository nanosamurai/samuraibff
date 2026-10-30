(ns samuraibff.ui.icons
  "Inline SVG icons for the UI.

  Icons are 24x24 outlines drawn in `currentColor`, so they follow the text
  color of their parent (muted, hover, active). Most shapes come from Lucide
  (ISC license); the webhook mark is the one used on the nanosamur.ai site.
  Icons are decorative: the visible label next to them, or the aria-label of
  an icon-only button, carries the meaning.")

(def ^:private stroke-attrs
  {:fill "none"
   :stroke "currentColor"
   :stroke-width 2
   :stroke-linecap "round"
   :stroke-linejoin "round"})

(def ^:private webhook-path
  "nanosamur.ai webhook mark. It is a filled outline, so it is filled instead
  of stroked; the thin stroke brings its line weight close to the others."
  (str "M17.974 7A4.967 4.967 0 0 0 18 6.5a5.5 5.5 0 1 0-8.672 4.491L7.18 15.114"
       "A2.428 2.428 0 0 0 6.496 15 2.5 2.5 0 1 0 9 17.496a2.36 2.36 0 0 0-.93-1.925"
       "l2.576-4.943-.41-.241A4.5 4.5 0 1 1 17 6.5a4.8 4.8 0 0 1-.022.452z"
       "M6.503 18.999a1.5 1.5 0 1 1 1.496-1.503A1.518 1.518 0 0 1 6.503 19z"
       "M18.5 12a5.735 5.735 0 0 0-1.453.157l-2.744-3.941A2.414 2.414 0 0 0 15 6.5"
       "a2.544 2.544 0 1 0-1.518 2.284l3.17 4.557.36-.13A4.267 4.267 0 0 1 18.5 13"
       "a4.5 4.5 0 1 1-.008 9h-.006a4.684 4.684 0 0 1-3.12-1.355l-.703.71"
       "A5.653 5.653 0 0 0 18.49 23h.011a5.5 5.5 0 0 0 0-11z"
       "M11 6.5A1.5 1.5 0 1 1 12.5 8 1.509 1.509 0 0 1 11 6.5z"
       "M18.5 20a2.5 2.5 0 1 0-2.447-3h-5.05l-.003.497A4.546 4.546 0 0 1 6.5 22"
       " 4.526 4.526 0 0 1 2 17.5a4.596 4.596 0 0 1 3.148-4.37l-.296-.954"
       "A5.606 5.606 0 0 0 1 17.5 5.532 5.532 0 0 0 6.5 23a5.573 5.573 0 0 0 5.478-5"
       "h4.08a2.487 2.487 0 0 0 2.442 2zm0-4a1.5 1.5 0 1 1-1.5 1.5 1.509 1.509 0 0 1 1.5-1.5z"))

(defn- stroked
  "Return a Lucide-style stroked shape from hiccup children."
  [& children]
  {:attrs stroke-attrs :children (vec children)})

(def ^:private control-shapes
  "Icons for controls, menus, pagination and status (Lucide)."
  {:arrow-up-right (stroked [:path {:d "M7 7h10v10"}] [:path {:d "M7 17 17 7"}])
   :chevron-down (stroked [:path {:d "m6 9 6 6 6-6"}])
   :chevron-left (stroked [:path {:d "m15 18-6-6 6-6"}])
   :chevron-right (stroked [:path {:d "m9 18 6-6-6-6"}])
   :chevrons-left (stroked [:path {:d "m11 17-5-5 5-5"}] [:path {:d "m18 17-5-5 5-5"}])
   :chevrons-right (stroked [:path {:d "m6 17 5-5-5-5"}] [:path {:d "m13 17 5-5-5-5"}])
   :circle-check (stroked [:circle {:cx 12 :cy 12 :r 10}] [:path {:d "m16 9-5.5 5.5L8 12"}])
   :circle-dashed (stroked [:path {:d "M10.1 2.182a10 10 0 0 1 3.8 0"}]
                           [:path {:d "M13.9 21.818a10 10 0 0 1-3.8 0"}]
                           [:path {:d "M17.609 3.721a10 10 0 0 1 2.69 2.7"}]
                           [:path {:d "M2.182 13.9a10 10 0 0 1 0-3.8"}]
                           [:path {:d "M20.279 17.609a10 10 0 0 1-2.7 2.69"}]
                           [:path {:d "M21.818 10.1a10 10 0 0 1 0 3.8"}]
                           [:path {:d "M3.721 6.391a10 10 0 0 1 2.7-2.69"}]
                           [:path {:d "M6.391 20.279a10 10 0 0 1-2.69-2.7"}])
   :circle-stop (stroked [:circle {:cx 12 :cy 12 :r 10}] [:rect {:x 9 :y 9 :width 6 :height 6 :rx 1}])
   :circle-x (stroked [:circle {:cx 12 :cy 12 :r 10}] [:path {:d "m15 9-6 6"}] [:path {:d "m9 9 6 6"}])
   :loader-circle (stroked [:path {:d "M21 12a9 9 0 1 1-6.219-8.56"}])
   :menu (stroked [:path {:d "M4 5h16"}] [:path {:d "M4 12h16"}] [:path {:d "M4 19h16"}])
   :panel-right-close (stroked [:rect {:width 18 :height 18 :x 3 :y 3 :rx 2}]
                               [:path {:d "M15 3v18"}] [:path {:d "m8 9 3 3-3 3"}])
   :panel-right-open (stroked [:rect {:width 18 :height 18 :x 3 :y 3 :rx 2}]
                              [:path {:d "M15 3v18"}] [:path {:d "m10 15-3-3 3-3"}])
   :plus (stroked [:path {:d "M5 12h14"}] [:path {:d "M12 5v14"}])
   :share (stroked [:circle {:cx 18 :cy 5 :r 3}] [:circle {:cx 6 :cy 12 :r 3}]
                   [:circle {:cx 18 :cy 19 :r 3}]
                   [:line {:x1 8.59 :x2 15.42 :y1 13.51 :y2 17.49}]
                   [:line {:x1 15.41 :x2 8.59 :y1 6.51 :y2 10.49}])
   :settings (stroked [:path {:d (str "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915"
                                      " 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1"
                                      "-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0"
                                      " 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0"
                                      " 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915")}]
                      [:circle {:cx 12 :cy 12 :r 3}])
   :square (stroked [:rect {:width 18 :height 18 :x 3 :y 3 :rx 2}])
   :trash (stroked [:path {:d "M10 11v6"}] [:path {:d "M14 11v6"}]
                   [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"}]
                   [:path {:d "M3 6h18"}] [:path {:d "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}])
   :x (stroked [:path {:d "M18 6 6 18"}] [:path {:d "m6 6 12 12"}])})

(def ^:private nav-shapes
  "Navigation icons."
  {:sessions {:attrs stroke-attrs
              :children [[:path {:d "M2 10v3"}] [:path {:d "M6 6v11"}] [:path {:d "M10 3v18"}]
                         [:path {:d "M14 8v7"}] [:path {:d "M18 5v13"}] [:path {:d "M22 10v3"}]]}
   :record {:attrs stroke-attrs
            :children [[:path {:d "M12 19v3"}]
                       [:path {:d "M19 10v2a7 7 0 0 1-14 0v-2"}]
                       [:rect {:x 9 :y 2 :width 6 :height 13 :rx 3}]]}
   :webhooks {:attrs {:fill "currentColor"
                      :stroke "currentColor"
                      :stroke-width 0.5
                      :stroke-linejoin "round"}
              :children [[:path {:d webhook-path}]]}
   :workflows {:attrs stroke-attrs
               :children [[:rect {:width 8 :height 8 :x 3 :y 3 :rx 2}]
                          [:path {:d "M7 11v4a2 2 0 0 0 2 2h4"}]
                          [:rect {:width 8 :height 8 :x 13 :y 13 :rx 2}]]}
   :speakers {:attrs stroke-attrs
              :children [[:path {:d (str "M2 13a2 2 0 0 0 2-2V7a2 2 0 0 1 4 0v13a2 2 0 0 0 4 0V4"
                                         "a2 2 0 0 1 4 0v13a2 2 0 0 0 4 0v-4a2 2 0 0 1 2-2")}]]}
   :api-credentials {:attrs stroke-attrs
                     :children [[:path {:d (str "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3"
                                                "a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1"
                                                "a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814"
                                                "a6.5 6.5 0 1 0-4-4z")}]
                                [:circle {:cx 16.5 :cy 7.5 :r 0.5 :fill "currentColor"}]]}
   :people {:attrs stroke-attrs
            :children [[:path {:d "M18 21a8 8 0 0 0-16 0"}]
                       [:circle {:cx 10 :cy 8 :r 5}]
                       [:path {:d "M22 20c0-3.37-2-6.5-4-8a5 5 0 0 0-.45-8.3"}]]}})

(def ^:private shapes
  "Icon name -> {:attrs svg attributes, :children hiccup children}."
  (merge nav-shapes control-shapes))

(def names
  "Known icon names."
  (set (keys shapes)))

(defn icon
  "Render the icon `icon-name` as an inline SVG, or nil for an unknown name.

  Inputs:
  - icon-name: keyword from `names`
  - opts: optional map {:class string :size number (px, default 16)}

  Returns: hiccup or nil."
  ([icon-name] (icon icon-name nil))
  ([icon-name {:keys [class size]}]
   (when-let [{:keys [attrs children]} (get shapes icon-name)]
     (let [size (or size 16)]
       (into [:svg (merge {:class (str "icon-svg" (when class (str " " class)))
                           :width size
                           :height size
                           :viewBox "0 0 24 24"
                           :aria-hidden true
                           :focusable "false"}
                          attrs)]
             children)))))
