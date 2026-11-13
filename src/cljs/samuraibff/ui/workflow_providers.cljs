(ns samuraibff.ui.workflow-providers
  "Readable names for workflow provider types in the workflow editor.

  The API and Kafka keep the provider type IDs (`bedrock`,
  `openai_compatible`); only the labels shown to people change here.

  Public API:
  - label
  - select-options")

(def ^:private labels
  {"bedrock" "AWS Bedrock"
   "openai_compatible" "Self-hosted LLM (OpenAI-compatible API)"})

(defn label
  "Return the readable name of a provider type ID.

  Inputs: type-id string (e.g. \"openai_compatible\").
  Returns: string; an unknown ID is returned unchanged."
  [type-id]
  (get labels type-id type-id))

(defn select-options
  "Return the options of the provider drop-down.

  Inputs:
  - allowed: provider type IDs this deployment allows, in order
  - stored: the workflow's saved provider type ID, or nil for a new workflow

  Returns: vector of {:value id :label string}. A saved type that is not
  allowed comes first, marked as not enabled, so that opening and saving the
  workflow keeps it."
  [allowed stored]
  (cond-> []
    (and (seq stored) (not (some #{stored} allowed)))
    (conj {:value stored :label (str (label stored) " (not enabled on this server)")})
    true
    (into (map (fn [t] {:value t :label (label t)})) allowed)))
