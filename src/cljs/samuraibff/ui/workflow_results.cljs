(ns samuraibff.ui.workflow-results
  "UI helpers for rendering workflow results (latest per workflow).

  These are produced by workflow-runner and persisted by samuraipersistor.

  In the UI we use two sources:
  - recording detail: DB-backed `workflow_results_latest` returned by GET /api/recordings/:id
  - live page: streamed WS events of type `workflow_result` (best-effort)

  Security notes:
  - markdown is rendered as a safe subset (no raw HTML)
  - we avoid `dangerouslySetInnerHTML`
  - we deliberately emit only a small set of host tags (div/span) to avoid HSX
    edge cases that could produce invalid DOM element names."
  (:require
   [clojure.string :as str]
   [nextjournal.markdown :as md]))

(def ^:private max-markdown-chars
  "Maximum number of characters of markdown to render in the UI (keeps the panel fast)."
  6000)

(defn- strip-reasoning-blocks
  "Remove `<reasoning>...</reasoning>` blocks if present.

  Inputs:
  - s: string?

  Returns: string."
  [s]
  (str/replace (str (or s ""))
               #"<reasoning>[\s\S]*?</reasoning>"
               ""))

(defn- bound-markdown
  "Bound markdown length to `max-markdown-chars`.

  Inputs:
  - s: string?

  Returns: string?"
  [s]
  (let [s (some-> s str)]
    (when (seq (str s))
      (if (> (count s) max-markdown-chars)
        (str (subs s 0 max-markdown-chars) "\n\n…")
        s))))

(defn- try-extract-openai-chat-content
  "Attempt to parse a JSON string that looks like an OpenAI Chat Completions response
  and extract the markdown content from `choices[0].message.content`.

  Inputs:
  - s: string

  Returns:
  - string? markdown content, or nil if not found / not parseable."
  [s]
  (try
    (let [js-obj (.parse js/JSON s)
          content (aget js-obj "choices" 0 "message" "content")]
      (when (string? content) content))
    (catch :default _
      nil)))

(defn- extract-markdown
  "Extract displayable markdown from workflow result `:render_markdown`.

  Extraction rules:
  1) If the string parses as JSON and contains `choices[0].message.content`, use that.
  2) Otherwise, treat the input as markdown.
  3) Strip `<reasoning>...</reasoning>` blocks.
  4) Bound length.

  Inputs:
  - render-markdown: string?

  Returns:
  - string? (nil when empty)."
  [render-markdown]
  (let [s (some-> render-markdown str str/trim)]
    (when (seq (str s))
      (-> (or (try-extract-openai-chat-content s) s)
          strip-reasoning-blocks
          str/trim
          bound-markdown))))

(defn- render-markdown
  "Render markdown string as hiccup using nextjournal/markdown.

  Security:
  - we do NOT render arbitrary HTML via `dangerouslySetInnerHTML`
  - output is hiccup data and React will treat strings as text nodes

  Inputs:
  - md-string: string?

  Returns: hiccup."
  [md-string]
  (try
    (md/->hiccup (md/parse (str (or md-string ""))))
    (catch :default _
      [:div {:class "muted" :style {:fontSize "12px" :whiteSpace "pre-wrap"}}
       "(markdown render failed)"])))

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
        md' (extract-markdown render_markdown)]
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
       [:div {:style {:maxHeight "360px" :overflow "auto"}}
        (render-markdown md')]

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
