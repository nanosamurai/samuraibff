(ns samuraibff.ui.env
  "Runtime environment helpers.

  Electron and browser renderers are both served by SamuraiBFF. Consequently,
  all HTTP and WebSocket clients use the current origin; the Electron main
  process owns backend selection through NANOSAMURAI_API_URL.")

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
  - Browser and Electron renderers are served by the BFF.
  - Return an empty string so clients use the current origin.

  Returns: empty string."
  []
  "")
