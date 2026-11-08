(ns samuraibff.test-support.fake-s3
  "In-memory S3Client for unit tests of object uploads.

  Public API:
  - `fake-s3` returns {:client S3Client :state atom}. The state map holds
    :objects {key bytes}, :uploads {upload-id {:key key :parts {n bytes}}},
    :aborted #{upload-id} and :deleted [key ...]."
  (:import
   (java.util UUID)
   (software.amazon.awssdk.core.sync RequestBody)
   (software.amazon.awssdk.services.s3 S3Client)
   (software.amazon.awssdk.services.s3.model AbortMultipartUploadRequest AbortMultipartUploadResponse
                                             CompleteMultipartUploadRequest CompleteMultipartUploadResponse
                                             CreateMultipartUploadRequest CreateMultipartUploadResponse
                                             DeleteObjectRequest DeleteObjectResponse
                                             PutObjectRequest PutObjectResponse
                                             UploadPartRequest UploadPartResponse)))

(defn- body-bytes
  "Read all bytes of a RequestBody."
  ^bytes [^RequestBody body]
  (with-open [in (.newStream (.contentStreamProvider body))]
    (.readAllBytes in)))

(defn fake-s3
  "Return {:client S3Client :state atom} backed by memory.

  Option :fail-part n makes uploadPart throw for part number n."
  ([] (fake-s3 {}))
  ([{:keys [fail-part]}]
   (let [state (atom {:objects {} :uploads {} :aborted #{} :deleted []})
         client
         (reify S3Client
           (serviceName [_] "s3")
           (close [_] nil)
           (^PutObjectResponse putObject [_ ^PutObjectRequest req ^RequestBody body]
             (swap! state assoc-in [:objects (.key req)] (body-bytes body))
             (.build (PutObjectResponse/builder)))
           (^CreateMultipartUploadResponse createMultipartUpload [_ ^CreateMultipartUploadRequest req]
             (let [id (str (UUID/randomUUID))]
               (swap! state assoc-in [:uploads id] {:key (.key req) :parts (sorted-map)})
               (.build (.uploadId (CreateMultipartUploadResponse/builder) id))))
           (^UploadPartResponse uploadPart [_ ^UploadPartRequest req ^RequestBody body]
             (when (= fail-part (.partNumber req))
               (throw (RuntimeException. "storage unavailable")))
             (swap! state assoc-in [:uploads (.uploadId req) :parts (.partNumber req)] (body-bytes body))
             (.build (.eTag (UploadPartResponse/builder) (str "etag-" (.partNumber req)))))
           (^CompleteMultipartUploadResponse completeMultipartUpload [_ ^CompleteMultipartUploadRequest req]
             (let [{:keys [key parts]} (get-in @state [:uploads (.uploadId req)])
                   out (java.io.ByteArrayOutputStream.)]
               (doseq [^bytes part (vals parts)]
                 (.write out part))
               (swap! state #(-> %
                                 (assoc-in [:objects key] (.toByteArray out))
                                 (update :uploads dissoc (.uploadId req))))
               (.build (CompleteMultipartUploadResponse/builder))))
           (^AbortMultipartUploadResponse abortMultipartUpload [_ ^AbortMultipartUploadRequest req]
             (swap! state #(-> %
                               (update :aborted conj (.uploadId req))
                               (update :uploads dissoc (.uploadId req))))
             (.build (AbortMultipartUploadResponse/builder)))
           (^DeleteObjectResponse deleteObject [_ ^DeleteObjectRequest req]
             (swap! state #(-> %
                               (update :deleted conj (.key req))
                               (update :objects dissoc (.key req))))
             (.build (DeleteObjectResponse/builder))))]
     {:client client :state state})))
