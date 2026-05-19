(ns samuraibff.ui.util
  "Small UI helpers (pure).

  This namespace contains helpers that are easier to test or reason about
  when kept pure.")

(defn- trim-trailing-slash
  "Trim trailing slashes from a URL-like string.

  Inputs:
  - s: string

  Returns: string."
  [s]
  (let [s (str (or s ""))]
    (loop [x s]
      (if (and (seq x) (= "/" (subs x (dec (count x)))))
        (recur (subs x 0 (dec (count x))))
        x))))

(defn now-ms
  "Return current epoch time in milliseconds." 
  []
  (.now js/Date))

(defn- pad2
  "Left-pad a non-negative integer to 2 digits.

  Inputs:
  - n: number

  Returns: string."
  [n]
  (let [n (js/Math.floor (js/Math.abs (double (or n 0))))
        s (str n)]
    (if (< (count s) 2)
      (str "0" s)
      s)))

(defn iso->ms
  "Parse an ISO timestamp into epoch milliseconds.

  Inputs:
  - iso: string?

  Returns:
  - number? (ms) when parseable
  - nil otherwise."
  [iso]
  (when (seq (str iso))
    (let [t (.getTime (js/Date. (str iso)))]
      (when (and (number? t) (js/isFinite t))
        t))))

(defn fmt-local-ymd-hm
  "Format epoch milliseconds into local YYYY-MM-DD HH:mm (24h) string.

  Inputs:
  - ms: number

  Returns: string?"
  [ms]
  (when (and (number? ms) (js/isFinite ms))
    (let [d (js/Date. ms)
          y (.getFullYear d)
          m (inc (.getMonth d))
          dd (.getDate d)
          hh (.getHours d)
          mm (.getMinutes d)]
      (str y "-" (pad2 m) "-" (pad2 dd) " " (pad2 hh) ":" (pad2 mm)))))

(defn default-session-title
  "Generate a stable display title for an untitled session.

  This is only a UI presentation helper. It does not imply the session title is
  persisted.

  Inputs:
  - created-at-ms: number?

  Returns: string (best-effort)."
  [created-at-ms]
  (str "Session "
       (or (fmt-local-ymd-hm created-at-ms)
           "(untitled)")))

(defn ws-url
  "Build a ws/wss URL from current location.

  Inputs:
  - path: string, e.g. \"/ws/events\"
  - query-params: map string->string

  Behavior:
  - If sessionStorage.access_token is present, it is appended as `token=`.
    This supports non-cookie clients / dev usage.

  Returns: string (absolute ws://... URL)."
  ([path query-params]
   (ws-url path query-params nil))
  ([path query-params {:keys [backend-base-url]}]
   (let [qs (js/URLSearchParams.)
         backend-base-url (some-> backend-base-url trim-trailing-slash)
         loc (.-location js/window)
         ;; When backend-base-url is provided (Electron), derive ws/wss from it.
         ;; Otherwise fall back to current location.
         proto (cond
                 (and (string? backend-base-url)
                      (not (empty? backend-base-url))
                      (.startsWith backend-base-url "https://"))
                 "wss:"

                 (and (string? backend-base-url)
                      (not (empty? backend-base-url))
                      (.startsWith backend-base-url "http://"))
                 "ws:"

                 (= "https:" (.-protocol loc))
                 "wss:"

                 :else
                 "ws:")
         host (if (and (string? backend-base-url)
                       (not (empty? backend-base-url)))
                (.-host (js/URL. backend-base-url))
                (.-host loc))]
    (doseq [[k v] query-params]
      (.set qs (name k) (str v)))

    (when-let [t (.getItem (.-sessionStorage js/window) "access_token")]
      (when (and (string? t) (not (empty? t)))
        (.set qs "token" t)))

     (str proto "//" host path "?" (.toString qs)))))

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
