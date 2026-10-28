(ns samuraibff.http.revocable-stream
  "Stop recording playback when the reader loses access.

  Audio responses can stream for minutes. The wrapped body is registered with
  the process-wide access watcher (`samuraibff.access.watch`) and fails reads
  with an IOException once access is revoked or cannot be verified within the
  grace period. Reads check cached state only; they never query the database.
  Future Range requests are authorized again from scratch.

  Public API:
  - `watched-body`"
  (:require
   [samuraibff.access.watch :as watch])
  (:import
   (java.io FilterInputStream IOException InputStream)
   (java.util UUID)))

(defn watched-body
  "Wrap a playback body so reads fail once the reader loses access.

  Inputs: watcher, Ring request (with :auth/principal), session UUID, body.
  Returns the original value when it is not an InputStream. Closing the
  stream stops watching it."
  [w req ^UUID session-id body]
  (if-not (instance? InputStream body)
    body
    (let [entry (watch/register! w {:principal (:auth/principal req)
                                    :session-id session-id
                                    :mode :read
                                    :max-duration? false})
          check! (fn [] (when-not (watch/allowed? w entry) (throw (IOException. "access-revoked"))))]
      (proxy [FilterInputStream] [^InputStream body]
        (read
          ([]
           (check!)
           (.read ^InputStream body))
          ([^bytes buf]
           (check!)
           (.read ^InputStream body buf))
          ([^bytes buf off len]
           (check!)
           (.read ^InputStream body buf (int off) (int len))))
        (close []
          (watch/unregister! w entry)
          (.close ^InputStream body))))))
