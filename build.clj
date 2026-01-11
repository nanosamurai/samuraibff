(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.shell :as sh]))

(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))

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