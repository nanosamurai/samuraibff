(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.shell :as sh]))

(def class-dir
  "Directory where compiled class files and copied resources are assembled before packaging." 
  "target/classes")

(def uber-file
  "Path of the produced uberjar." 
  "target/samuraibff.jar")

(def basis
  "tools.build basis created from deps.edn." 
  (b/create-basis {:project "deps.edn"}))

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
  nil)