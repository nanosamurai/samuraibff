(ns samuraibff.workflows.runs
  "Workflow execution settings and on-demand workflow jobs.

  Settings (under `:workflows` in the BFF config):
  - `:provider-types`: provider types the editor offers and the API accepts
    for new or changed definitions (SAMURAIBFF_WORKFLOW_PROVIDER_TYPES,
    default [\"bedrock\"]). Stored definitions with other types keep working.

  Public API:
  - `provider-types`, `provider-type-allowed?`")

(def ^:private default-provider-types
  ["bedrock"])

(defn provider-types
  "Return the configured workflow provider types.

  Inputs: BFF config map.
  Returns: non-empty vector of type strings."
  [config]
  (vec (or (seq (get-in config [:workflows :provider-types])) default-provider-types)))

(defn provider-type-allowed?
  "True when a definition may be saved with provider type t.

  Inputs: BFF config map, requested type string, stored type string or nil.
  A type outside the allowlist is accepted only when it equals the stored
  type, so existing definitions stay editable."
  [config t stored-type]
  (boolean (or (some #{t} (provider-types config))
               (and (some? stored-type) (= t stored-type)))))
