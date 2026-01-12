(ns samuraibff.ui.types
  "Shared data shape documentation (lightweight) for the UI.

  We keep it minimal for now (no Malli in CLJS yet), but having a central
  place for shape comments helps maintainability.

  WS event shapes (from backend):

  status:
    {:type status, :session_id <string>, :seq <int>, :ts_ms <int>,
     :status connected|started|paused|resumed|stopped, :detail <string?>}

  asr:
    {:type asr, :session_id <string>, :seq <int>, :ts_ms <int>,
     :start_s <double>, :end_s <double>, :text <string>,
     :speaker <string?>, :lang <string?>, :final <boolean>}")
