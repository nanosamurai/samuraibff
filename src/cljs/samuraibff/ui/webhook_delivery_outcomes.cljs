(ns samuraibff.ui.webhook-delivery-outcomes
  "UI helpers for rendering webhook delivery outcomes." 
  (:require
   [clojure.string :as str]))

(defn status->badge-class
  "Return CSS badge class for an outcome status string.

  Inputs:
  - status: string? (e.g. "delivered", "failed")

  Returns:
  - string CSS class name." 
  [status]
  (let [s (some-> status str str/lower-case)]
    (cond
      (or (= s "delivered") (= s "success") (= s "ok")) "badge ok"
      (or (= s "failed") (= s "error")) "badge bad"
      :else "badge muted")))

(defn render-outcome-panel
  "Render a single webhook outcome (latest per dispatch).

  Inputs:
  - o: map with keys from the API (keywordized):
      :status :http_status :created_at :event_type :attempts_count :attempt_no

  Returns: hiccup." 
  [o]
  (let [{:keys [status http_status created_at event_type attempts_count attempt_no webhook_id error_code error_detail latency_ms]} o
        attempts-count (or attempts_count attempt_no 0)
        show-error? (and (seq (str error_code)) (not (str/blank? (str error_code))))
        show-detail? (and (seq (str error_detail)) (not (str/blank? (str error_detail))))
        http-part (when (some? http_status)
                    (str "HTTP " http_status))
        ts (some-> created_at str)]
    [:div {:style {:border "1px solid rgba(34,48,70,.55)"
                   :borderRadius "12px"
                   :padding "10px"
                   :background "rgba(12,18,27,.55)"
                   :display "flex"
                   :flexDirection "column"
                   :gap "6px"}}
     [:div {:class "row" :style {:alignItems "center" :gap "8px"}}
      [:span {:class (status->badge-class status)}
       (str (or status "unknown"))]
      (when (seq http-part)
        [:span {:class "badge muted"} http-part])
      [:div {:class "spacer"}]
      (when (some? latency_ms)
        [:span {:class "muted" :style {:fontSize "12px"}}
         (str latency_ms "ms")])]

     [:div {:class "muted" :style {:fontSize "12px"}}
      (str "Event: " (or event_type "(unknown)")
           " • Attempts: " attempts-count)]

     (when (seq (str webhook_id))
       [:div {:class "muted" :style {:fontSize "12px"}}
        [:span {:class "mono"} (str "Webhook: " webhook_id)]])

     (when (seq ts)
       [:div {:class "muted" :style {:fontSize "12px"}}
        (str "At: " ts)])

     (when (or show-error? show-detail?)
       [:div {:style {:display "flex" :flexDirection "column" :gap "4px"}}
        (when show-error?
          [:div {:class "badge bad"} (str "Error: " error_code)])
        (when show-detail?
          [:div {:class "muted" :style {:fontSize "12px" :whiteSpace "pre-wrap"}}
           ;; Keep it bounded; error_detail can be long.
           (let [s (str error_detail)
                 s' (if (> (count s) 400) (str (subs s 0 400) "…") s)]
             s')])])]))

(defn webhook-dispatches-card
  "Render a card containing webhook dispatch outcomes.

  Inputs:
  - {:keys [items loading? error title]}
    - items: vector of outcome maps
    - loading?: boolean
    - error: string?
    - title: string (optional)

  Returns: hiccup." 
  [{:keys [items loading? error title]}]
  (let [items (vec (or items []))
        title (or title "Webhook dispatches")]
    [:div {:class "card"}
     [:div {:class "card-title"} title]
     (cond
       (true? loading?)
       [:div {:class "muted"} "Loading…"]

       (seq (str error))
       [:div {:class "badge bad"} (str error)]

       (empty? items)
       [:div {:class "muted"} "No webhook dispatches recorded for this session."]

       :else
       [:div {:style {:display "flex" :flexDirection "column" :gap "10px"}}
        (for [o items]
          ^{:key (str "wh-out-" (:dispatch_id o) "-" (:id o))}
          [render-outcome-panel o])])]))
