(ns samuraibff.ui.components
  "Compatibility façade for UI components.

  The UI used to live in this single namespace. It has been split into smaller
  namespaces under:

  - `samuraibff.ui.components.*`
  - `samuraibff.ui.components.pages.*`

  Public API:
  - `app` (root React component)
  - `memo-clear!` (dev hot-reload helper)"
  (:require
   [samuraibff.ui.ui-app :as ui-app]
   [samuraibff.ui.components.shared :as shared]))

(def app
  "Root app component (re-export).

  Returns: hiccup." 
  ui-app/app)

(defn memo-clear!
  "Clear HSX memoization cache (used by core reload hook)."
  []
  (shared/memo-clear!))
