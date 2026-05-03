(ns samuraibff.ui.workflow-results
  "UI helpers for rendering workflow results (latest per workflow).

  These are produced by workflow-runner and persisted by samuraipersistor.

  In the UI we use two sources:
  - recording detail: DB-backed `workflow_results_latest` returned by GET /api/recordings/:id
  - live page: streamed WS events of type `workflow_result` (best-effort)" 
  (:require
   [clojure.string :as str]))

(defn- status->badge-class
  "Return a CSS badge class for workflow result status.

  Inputs:
  - status: string?

  Returns: string." 
  [status]
  (let [s (some-> status str str/lower-case)]
    (cond
      (or (= s "ok") (= s "success") (= s "delivered")) "badge ok"
      (or (= s "failed") (= s "error")) "badge bad"
      :else "badge muted")))

(defn render-workflow-result-panel
  "Render one workflow result panel.

  Inputs:
  - r: map with keys (keywordized):
      :workflow_id :workflow_name :created_at :status :trigger_type
      :render_markdown :error_code :error_detail

  Returns: hiccup." 
  [r]
  (let [{:keys [workflow_id workflow_name created_at status trigger_type render_markdown error_code error_detail]} r
        title (or (some-> workflow_name str str/trim not-empty)
                  (some-> workflow_id str)
                  "(workflow)")
        ts (some-> created_at str)
        show-error? (seq (str/trim (str (or error_code ""))))
        show-detail? (seq (str/trim (str (or error_detail ""))))
        md (some-> render_markdown str)
        md' (when (seq (str md))
              ;; keep it bounded on screen; backend already truncates WS.
              (let [s md]
                (if (> (count s) 6000)
                  (str (subs s 0 6000) "\n\n…")
                  s)))]
    [:div {:style {:border "1px solid rgba(34,48,70,.55)"
                   :borderRadius "12px"
                   :padding "10px"
                   :background "rgba(12,18,27,.55)"
                   :display "flex"
                   :flexDirection "column"
                   :gap "6px"}}
     [:div {:class "row" :style {:alignItems "center" :gap "8px"}}
      [:span {:class (status->badge-class status)} (str (or status "unknown"))]
      [:div {:style {:fontWeight 600}} title]
      [:div {:class "spacer"}]
      (when (seq (str trigger_type))
        [:span {:class "badge muted"} (str trigger_type)])]

     (when (seq ts)
       [:div {:class "muted" :style {:fontSize "12px"}}
        (str "At: " ts)])

     (when (or show-error? show-detail?)
       [:div {:style {:display "flex" :flexDirection "column" :gap "4px"}}
        (when show-error?
          [:div {:class "badge bad"} (str "Error: " error_code)])
        (when show-detail?
          [:div {:class "muted" :style {:fontSize "12px" :whiteSpace "pre-wrap"}}
           (let [s (str error_detail)
                 s' (if (> (count s) 500) (str (subs s 0 500) "…") s)]
             s')])])

     (cond
       (seq md')
       [:pre {:class "mono"
              :style {:margin 0
                      :padding "10px"
                      :borderRadius "10px"
                      :background "rgba(0,0,0,.25)"
                      :whiteSpace "pre-wrap"
                      :maxHeight "360px"
                      :overflow "auto"}}
        md']

       :else
       [:div {:class "muted" :style {:fontSize "12px"}}
        "(no markdown output)"])]))

(defn workflow-results-card
  "Render a card containing workflow results.

  Inputs:
  - {:keys [items loading? error title fill? empty-hint]}
    - items: vector of workflow result maps

  Returns: hiccup." 
  [{:keys [items loading? error title fill? empty-hint]}]
  (let [items (vec (or items []))
        title (or title "Workflow results")
        empty-hint (or empty-hint "No workflow results recorded for this session.")]
    [:div (cond-> {:class "card"}
            (true? fill?)
            (assoc :style {:display "flex"
                           :flexDirection "column"
                           :height "100%"
                           :minHeight 0}))
     [:div {:class "card-title"} title]
     (cond
       (true? loading?)
       [:div {:class "muted"} "Loading…"]

       (seq (str error))
       [:div {:class "badge bad"} (str error)]

       (empty? items)
       [:div {:class "muted"} empty-hint]

       :else
       [:div (cond-> {:style {:display "flex" :flexDirection "column" :gap "10px"}}
               (true? fill?)
               (assoc :style {:display "flex"
                              :flexDirection "column"
                              :gap "10px"
                              :flex 1
                              :minHeight 0
                              :overflow "auto"}))
        (for [r items]
          ^{:key (str "wf-res-" (:workflow_id r) "-" (:workflow_run_id r))}
          [render-workflow-result-panel r])])]))
