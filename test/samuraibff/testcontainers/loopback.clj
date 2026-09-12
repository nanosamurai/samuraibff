(ns samuraibff.testcontainers.loopback
  "Keep local integration-test services off the LAN.")

(defn only!
  "Bind all currently configured container ports to ephemeral loopback ports."
  [container]
  (.setPortBindings container (mapv #(str "127.0.0.1:0:" %) (.getExposedPorts container)))
  container)
