(ns samuraibff.ui.hooks
  "React hooks bridging plain ClojureScript atoms to React.

  We avoid Reagent and instead use `react/useSyncExternalStore`.

  Public API:
  - `use-atom` - subscribe to a CLJS atom and re-render on changes
  - `use-atom-selector` - subscribe and return a derived value

  This keeps state in normal atoms, but still allows idiomatic React rendering."
  (:require
    ["react" :as react]))

(defn use-atom
  "Subscribe to a ClojureScript atom and return its current value.

  Inputs:
  - a*: clojure.lang.IAtom

  Returns:
  - any (current @a*)"
  [a*]
  (react/useSyncExternalStore
    (fn [on-store-change]
      (let [watch-key (random-uuid)]
        (add-watch a* watch-key (fn [_ _ _ _] (on-store-change)))
        (fn []
          (remove-watch a* watch-key))))
    (fn [] @a*)
    (fn [] @a*)))

(defn use-atom-selector
  "Subscribe to a ClojureScript atom and return a derived value.

  Inputs:
  - a*: clojure.lang.IAtom
  - f:  (fn [value] any)

  Returns:
  - any (f @a*)"
  [a* f]
  (f (use-atom a*)))

(defn use-media-query
  "Subscribe to a CSS media query.

  This is used for responsive rendering in components (e.g. switching a table
  into a stacked card list on mobile).

  Inputs:
  - query: string, e.g. "(max-width: 768px)"

  Returns:
  - boolean, true when the query currently matches.

  Notes:
  - Uses `window.matchMedia` and listens to query changes.
  - Falls back to false when `window` is not available (defensive)."
  [query]
  (let [query (str (or query ""))
        mm (when (and (exists? js/window)
                      (exists? (.-matchMedia js/window))
                      (seq query))
             (.matchMedia js/window query))
        matches?* (react/useState (boolean (some-> mm .-matches)))
        matches? (aget matches?* 0)
        set-matches! (aget matches?* 1)]
    (react/useEffect
     (fn []
       (when mm
         (let [handler (fn [e]
                         (set-matches! (boolean (.-matches e))))]
           ;; Safari < 14 uses addListener/removeListener.
           (if (exists? (.-addEventListener mm))
             (do
               (.addEventListener mm "change" handler)
               (fn [] (.removeEventListener mm "change" handler)))
             (do
               (.addListener mm handler)
               (fn [] (.removeListener mm handler))))))
       js/undefined)
     #js [query])
    (boolean matches?)))
