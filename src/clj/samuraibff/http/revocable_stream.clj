(ns samuraibff.http.revocable-stream
  "Stop recording playback when the reader loses access.

  Audio responses can stream for minutes. The wrapped body re-checks the
  reader's principal and session access through
  `samuraibff.access.revocation/bounded-checker` and aborts the response with
  an IOException once access is revoked or cannot be verified within the bound.
  Future Range requests are authorized again from scratch.

  Public API:
  - `session-read-check`, `wrap`"
  (:require
   [samuraibff.access.policy :as policy]
   [samuraibff.access.principal :as access.principal]
   [samuraibff.access.revocation :as revocation]
   [samuraibff.db.session-access :as session-access])
  (:import
   (java.io FilterInputStream IOException InputStream)
   (java.util UUID)
   (javax.sql DataSource)))

(defn session-read-check
  "Return a bounded predicate that is true while the request may read the session.

  Inputs: DataSource, Ring request (with :auth/user and :samuraibff/deps),
  session UUID. The predicate re-resolves the principal from the database."
  [^DataSource ds req ^UUID session-id]
  (let [config (get-in req [:samuraibff/deps :config])
        user (:auth/user req)]
    (revocation/bounded-checker
     (fn []
       (let [{:keys [state principal]} (access.principal/resolve-principal config ds user)]
         (when (= :unavailable state)
           (throw (ex-info "Authorization store unavailable" {:type ::unavailable})))
         (and (policy/active? principal)
              (some? (session-access/find-accessible-session ds principal session-id))))))))

(defn wrap
  "Wrap an InputStream so reads fail once `allowed?` returns false.

  Returns the original value when it is not an InputStream."
  [body allowed?]
  (if-not (instance? InputStream body)
    body
    (proxy [FilterInputStream] [^InputStream body]
      (read
        ([]
         (when-not (allowed?) (throw (IOException. "access-revoked")))
         (.read ^InputStream body))
        ([^bytes buf]
         (when-not (allowed?) (throw (IOException. "access-revoked")))
         (.read ^InputStream body buf))
        ([^bytes buf off len]
         (when-not (allowed?) (throw (IOException. "access-revoked")))
         (.read ^InputStream body buf (int off) (int len)))))))
