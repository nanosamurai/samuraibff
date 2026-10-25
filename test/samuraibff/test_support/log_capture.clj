(ns samuraibff.test-support.log-capture
  "Capture application logging arguments and MDC without printing test secrets."
  (:require [org.corfield.logging4j2 :as log]
            [org.corfield.logging4j2.impl :as logging])
  (:import (org.apache.logging.log4j ThreadContext)))

(defn capture!
  "Call a zero-argument thunk and return {:value result :events vector}.

  Each event contains original arguments, formatted text and the current MDC."
  [f]
  (let [events (atom [])]
    (with-redefs [logging/log* (fn [_logger _level & args]
                                 (swap! events conj
                                        {:arguments args
                                         :text (.getFormattedMessage (apply log/as-message args))
                                         :mdc (into {} (ThreadContext/getImmutableContext))}))]
      {:value (f) :events @events})))
