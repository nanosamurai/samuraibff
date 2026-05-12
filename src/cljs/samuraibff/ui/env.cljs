(ns samuraibff.ui.env
  "Runtime environment helpers.

  This namespace centralizes checks for Electron vs browser, and provides a
  single source of truth for the backend base URL.

  The key requirement for Electron is that the UI may be loaded from `file://`,
  so relative URLs like `/api/...` are not usable. We therefore compute a
  backend base (default http://127.0.0.1:8000) and use it in all HTTP/WS
  clients.")

(def ^:private electron-backend-ls-key
  "localStorage key used to persist backend base url in Electron builds."
  "samuraibff.backend_base_url")

(defn electron?
  "Return true when running inside Electron.

  Detection:
  - preload exposes window.samuraibffElectron.isElectron

  Returns: boolean."
  []
  (boolean (some-> (.-samuraibffElectron js/window) (.-isElectron))))

(defn backend-base-url
  "Return HTTP backend base URL for API/WS calls.

  Behavior:
  - Browser build (served by backend): return empty string => same-origin.
  - Electron build: return value from localStorage (if set), otherwise
    default to "http://127.0.0.1:8000".

  Returns: string ("" or e.g. "http://127.0.0.1:8000")."
  []
  (if-not (electron?)
    ""
    (let [v (.getItem (.-localStorage js/window) electron-backend-ls-key)
          v (when (string? v) (.trim v))]
      (if (seq v)
        v
        "http://127.0.0.1:8000"))))

(defn set-electron-backend-base-url!
  "Persist backend base URL for Electron.

  Inputs:
  - base-url: string; should be absolute http(s) URL.

  Returns: nil."
  [base-url]
  (when (electron?)
    (.setItem (.-localStorage js/window)
              electron-backend-ls-key
              (str (or base-url ""))))
  nil)
