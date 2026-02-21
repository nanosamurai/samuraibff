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
     :speaker <string?>, :lang <string?>, :final <boolean>}

  refined:
    {:type refined, :session_id <string>, :seq <int>, :ts_ms <int>,
     :start_s <double>, :end_s <double>, :text <string>,
     :speaker <string?>, :lang <string?>, :supersedes_seq <[int]?>}

  Speaker list items (from backend):
    {:id <string>, :tenant_id <string>, :user_id <string?>,
     :label <string>, :audio_url <string>, :created_at <string>}"
  )
