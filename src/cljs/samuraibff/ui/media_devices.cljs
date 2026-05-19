(ns samuraibff.ui.media-devices
  "Media devices helper functions.

  This namespace provides a minimal wrapper around `navigator.mediaDevices` so
  UI components can list audio input devices without duplicating permission/
  compatibility handling.

  Security/UX notes:
  - Browsers often return empty device labels until the user grants microphone
    permissions at least once.
  - We never persist device IDs outside of UI state; they are only used as
    getUserMedia constraints.

  Public API:
  - list-microphones!"
  (:require
   [clojure.string :as str]
   [samuraibff.ui.store :as store]))

(defn list-microphones!
  "List available microphone (audioinput) devices.

  Returns:
  - Promise resolving to vector of maps:
      [{:id <deviceId string> :label <string?>} ...]
  - Promise rejects when the API is not available.

  Side effects:
  - On error, appends a safe log line via `store/append-log!`.
  
  Important:
  - Device labels may be empty until the user grants mic permissions." 
  []
  (if-not (and (exists? js/navigator)
               (exists? (.-mediaDevices js/navigator))
               (fn? (.-enumerateDevices (.-mediaDevices js/navigator))))
    (js/Promise.reject (js/Error. "mediaDevices.enumerateDevices not available"))
    (-> (.enumerateDevices (.-mediaDevices js/navigator))
        (.then (fn [devices]
                 (->> (array-seq devices)
                      (filter (fn [d]
                                (= "audioinput" (.-kind d))))
                      (map-indexed (fn [idx d]
                                     (let [id (str (or (.-deviceId d) ""))
                                           label (str (or (.-label d) ""))
                                           label (when (seq (str/trim label)) label)]
                                       {:id id
                                        :label (or label (str "Microphone " (inc idx)))})))
                      (remove (fn [{:keys [id]}]
                                (str/blank? (str id))))
                      vec)))
        (.catch (fn [e]
                  (store/append-log! (str "[ui] failed enumerating microphones: " e))
                  (throw e))))))
