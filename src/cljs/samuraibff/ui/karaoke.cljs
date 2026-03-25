(ns samuraibff.ui.karaoke
  "Karaoke helpers for word-level timing playback.

  This namespace is a thin CLJS wrapper over the shared CLJC logic in
  `samuraibff.ui.karaoke` (shared) to keep UI code tidy.

  Note: we intentionally keep these helpers pure and framework-free
  (no React), so they are easy to unit test.")

;; We keep the shared ns in CLJC, so UI can require it directly.
;; This file exists mainly to give the UI a stable require path.
