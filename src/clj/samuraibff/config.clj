(ns samuraibff.config
  "Integrant config component.

  This project uses a `:samuraibff/config` key in `resources/system.edn` as a
  central place to keep environment/runtime configuration.

  The config component is intentionally simple: it returns the provided config
  map unchanged.

  The main purpose is:
  - enable `integrant.core/load-namespaces` to resolve the key
  - provide a stable reference (`#ig/ref :samuraibff/config`) for other
    components

  Input:
  - a Clojure map (typically shaped like the value of `:samuraibff/config` in
    `resources/system.edn`)

  Output:
  - the same config map (no side effects)." 
  (:require
    [integrant.core :as ig]))

(defmethod ig/init-key :samuraibff/config
  [_ config]
  "Initialize the config component.

  Inputs:
  - config: map

  Returns: map (the same config map)." 
  config)

(defmethod ig/halt-key! :samuraibff/config
  [_ _]
  "Halt the config component.

  No-op because config carries no resources.

  Returns: nil." 
  nil)
