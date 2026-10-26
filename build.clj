(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.shell :as sh]
            [clojure.java.io :as io])
  (:import (java.nio.file Files FileSystems)
           (java.util Properties)))

(def class-dir
  "Directory where compiled class files and copied resources are assembled before packaging."
  "target/classes")

(def uber-file
  "Path of the produced uberjar."
  "target/samuraibff.jar")

(def basis
  "tools.build basis created from deps.edn."
  (b/create-basis {:project "deps.edn"}))

(def grpc-runtime-classes
  "Known packaged classes for gRPC artifacts whose jars omit Maven metadata."
  {'io.grpc/grpc-api "io/grpc/ManagedChannel.class"
   'io.grpc/grpc-core "io/grpc/internal/ManagedChannelImpl.class"
   'io.grpc/grpc-netty "io/grpc/netty/NettyChannelBuilder.class"
   'io.grpc/grpc-protobuf "io/grpc/protobuf/ProtoUtils.class"
   'io.grpc/grpc-protobuf-lite "io/grpc/protobuf/lite/ProtoLiteUtils.class"
   'io.grpc/grpc-stub "io/grpc/stub/AbstractStub.class"
   'io.grpc/grpc-util "io/grpc/util/RoundRobinLoadBalancer.class"})

(defn complete-grpc-metadata!
  "A helper fn sued to improve vulnerability detection. 
  Our packaged application was missing some library names and versions. 
  => so this function adds those labels so vulnerability scanners can identify the included gRPC libraries. It improves detection.
  Adds missing gRPC Maven metadata to a built jar using its exact build basis.
  Takes a jar path and tools.build basis. Requires each known runtime class,
  preserves existing matching metadata, and throws on absent or conflicting
  evidence. Returns nil; does not alter any classes."
  [jar-path build-basis]
  (with-open [fs (FileSystems/newFileSystem (.toPath (io/file jar-path))
                                            ^java.util.Map {})]
    (doseq [[lib class-name] grpc-runtime-classes]
      (let [version (get-in build-basis [:libs lib :mvn/version])
            class-path (.getPath fs class-name (make-array String 0))
            metadata (.getPath fs (str "META-INF/maven/" (namespace lib) "/" (name lib) "/pom.properties")
                               (make-array String 0))
            expected {"groupId" (namespace lib) "artifactId" (name lib) "version" version}]
        (when-not (and (seq version) (Files/exists class-path (make-array java.nio.file.LinkOption 0)))
          (throw (ex-info "Missing packaged gRPC artifact evidence" {:library lib})))
        (if (Files/exists metadata (make-array java.nio.file.LinkOption 0))
          (let [actual (Properties.)]
            (with-open [input (Files/newInputStream metadata (make-array java.nio.file.OpenOption 0))]
              (.load actual input))
            (when-not (every? (fn [[key value]] (= value (.getProperty actual key))) expected)
              (throw (ex-info "Conflicting packaged gRPC metadata" {:library lib}))))
          (do
            (Files/createDirectories (.getParent metadata) (make-array java.nio.file.attribute.FileAttribute 0))
            (with-open [output (Files/newOutputStream metadata (make-array java.nio.file.OpenOption 0))]
              (.store (doto (Properties.) (.putAll expected)) output nil)))))))
  nil)

(defn clean
  "Delete build output under `target/`.

  Input: ignored.
  Returns: nil."
  [_]
  (b/delete {:path "target"})
  nil)

(defn buf-generate [_]
  (let [{:keys [exit out err]} (sh/sh "buf" "generate")]
    (when-not (zero? exit)
      (throw (ex-info "buf generate failed" {:out out :err err}))))
  nil)

(defn compile-java [_]
  (b/javac {:src-dirs  ["src/java"]
            :class-dir class-dir
            :basis     basis})
  nil)

(defn proto+compile [_]
  (buf-generate nil)
  (compile-java nil))

(defn- copy-resources!
  "Copy non-code resources into `class-dir` so they end up in the uberjar.

  Returns: nil."
  []
  (b/copy-dir {:src-dirs   ["resources"]
               :target-dir class-dir})
  nil)

(defn- compile-clj!
  "AOT compile Clojure sources into `class-dir`.

  Notes:
  - We compile both `src/clj` and `src/shared`.
  - This is required so `java -jar` works without the Clojure CLI.

  Returns: nil."
  []
  (b/compile-clj {:basis     basis
                  :src-dirs  ["src/clj" "src/shared"]
                  :class-dir class-dir})
  nil)

(defn uber
  "Build an executable uberjar.

  This expects:
  - UI assets are already present under `resources/public/js` (built via shadow-cljs)
  - protobuf Java sources are already present under `src/java` (generated via buf)

  Typical local workflow:
  - `clojure -M:cljs release app`
  - `clojure -T:build proto+compile`
  - `clojure -T:build uber`

  Returns: nil."
  [_]
  (clean nil)
  (copy-resources!)
  (compile-java nil)
  (compile-clj!)
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     basis
           :main      'samuraibff.core
           ;; Some libraries (notably gRPC) include conflicting license paths
           ;; that can collide at the directory/file level during uberjar
           ;; assembly. Excluding META-INF/license avoids a hard build failure
           ;; while keeping other standard LICENSE/NOTICE entries.
           :exclude   ["(?i)^META-INF/license(/.*)?$"]})
  (complete-grpc-metadata! uber-file basis)
  nil)
