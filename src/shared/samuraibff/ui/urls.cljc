(ns samuraibff.ui.urls
  "Pure same-origin URL builders shared by the CLJS UI and JVM tests."
  #?(:clj
     (:require
      [clojure.string :as str]))
  #?(:clj
     (:import
      (java.net URLEncoder))))

(defn api-url
  "Join a backend base and root-relative API path.

  Inputs:
  - backend-base: empty string for same-origin, or an absolute origin string.
  - path: root-relative application path.

  Returns: URL string."
  [backend-base path]
  (str (or backend-base "") (or path "")))

(defn- encode-component
  "Percent-encode a value for use as one URL path segment.

  Inputs:
  - value: any value coercible to string.

  Returns: percent-encoded string."
  [value]
  #?(:clj (-> (URLEncoder/encode (str (or value "")) "UTF-8")
              (str/replace "+" "%20"))
     :cljs (js/encodeURIComponent (or value ""))))

(defn recording-audio-url
  "Build the BFF recording playback URL.

  Inputs:
  - backend-base: empty string for same-origin, or an absolute origin string.
  - session-id: recording session identifier.

  Returns: URL string targeting the tenant-scoped BFF streaming endpoint."
  [backend-base session-id]
  (api-url backend-base
           (str "/api/recordings/"
                (encode-component session-id)
                "/audio")))
