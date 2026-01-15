(ns samuraibff.ui.util
  "Small UI helpers (pure).

  This namespace contains helpers that are easier to test or reason about
  when kept pure.")

(defn now-ms
  "Return current epoch time in milliseconds." 
  []
  (.now js/Date))

(defn ws-url
  "Build a ws/wss URL from current location.

  Inputs:
  - path: string, e.g. \"/ws/events\"
  - query-params: map string->string

  Behavior:
  - If sessionStorage.access_token is present, it is appended as `token=`.
    This supports non-cookie clients / dev usage.

  Returns: string (absolute ws://... URL)." 
  [path query-params]
  (let [loc (.-location js/window)
        proto (if (= "https:" (.-protocol loc)) "wss:" "ws:")
        host (.-host loc)
        qs (js/URLSearchParams.)]
    (doseq [[k v] query-params]
      (.set qs (name k) (str v)))

    (when-let [t (.getItem (.-sessionStorage js/window) "access_token")]
      (when (and (string? t) (not (empty? t)))
        (.set qs "token" t)))

    (str proto "//" host path "?" (.toString qs))))

(defn fmt-sec
  "Format seconds (double) into mm:ss.xx.

  Inputs:
  - sec: number

  Returns: string." 
  [sec]
  (if-not (and (number? sec) (js/isFinite sec))
    "—"
    (let [s (max 0 sec)
          mm (js/Math.floor (/ s 60))
          ss (js/Math.floor (mod s 60))
          xx (js/Math.floor (* 100 (- s (js/Math.floor s))))]
      (str (when (< mm 10) "0") mm ":"
           (when (< ss 10) "0") ss "."
           (when (< xx 10) "0") xx))))
