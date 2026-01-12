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
