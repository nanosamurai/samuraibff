(ns build-test
  "Tests for fail-closed metadata completion in the assembled runtime jar."
  (:require [build :as build]
            [clojure.test :refer [deftest is run-tests]])
  (:import (java.nio.file Files)
           (java.util.jar JarEntry JarFile JarOutputStream)
           (java.util Properties)))

(defn- with-jar
  "Create a temporary jar containing named byte strings, call f, and delete it."
  [entries f]
  (let [path (Files/createTempFile "runtime-metadata-" ".jar"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (with-open [out (JarOutputStream. (Files/newOutputStream path (make-array java.nio.file.OpenOption 0)))]
        (doseq [[name text] entries]
          (.putNextEntry out (JarEntry. name))
          (.write out (.getBytes text java.nio.charset.StandardCharsets/UTF_8))
          (.closeEntry out)))
      (f (str path))
      (finally (Files/deleteIfExists path)))))

(defn- metadata-text
  "Read one metadata entry from a temporary jar without loading its classes."
  [path entry]
  (with-open [jar (JarFile. path)
              input (.getInputStream jar (.getJarEntry jar entry))]
    (slurp input)))

(def test-basis
  "A synthetic resolved basis covering the known gRPC runtime artifacts."
  {:libs (into {} (map (fn [lib] [lib {:mvn/version "1.85.1"}])
                       (keys build/grpc-runtime-classes)))})

(def test-classes
  "Synthetic class entries used only to test metadata evidence checks."
  (zipmap (vals build/grpc-runtime-classes) (repeat "synthetic class bytes")))

(deftest add-metadata-and-preserve-existing-test
  (let [entry "META-INF/maven/io.grpc/grpc-api/pom.properties"
        existing "# Preserve this exact metadata\ngroupId=io.grpc\nartifactId=grpc-api\nversion=1.85.1\n"]
    (with-jar (assoc test-classes entry existing)
      (fn [path]
        (build/complete-grpc-metadata! path test-basis)
        (is (= existing (metadata-text path entry)))
        (doseq [lib (keys build/grpc-runtime-classes)]
          (let [properties (Properties.)]
            (.load properties (java.io.StringReader.
                               (metadata-text path (str "META-INF/maven/io.grpc/" (name lib) "/pom.properties"))))
            (is (= "1.85.1" (.getProperty properties "version")))
            (is (= (name lib) (.getProperty properties "artifactId")))))))))

(deftest reject-missing-class-or-coordinate-test
  (with-jar (dissoc test-classes "io/grpc/ManagedChannel.class")
    #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing packaged"
                           (build/complete-grpc-metadata! % test-basis))))
  (with-jar test-classes
    #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing packaged"
                           (build/complete-grpc-metadata! % {:libs {}})))))

(deftest reject-conflicting-version-test
  (with-jar (assoc test-classes "META-INF/maven/io.grpc/grpc-api/pom.properties"
                   "groupId=io.grpc\nartifactId=grpc-api\nversion=1.0.0\n")
    #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"Conflicting packaged"
                           (build/complete-grpc-metadata! % test-basis)))))

(defn -main
  "Run packaging metadata regressions and exit nonzero on any failure."
  [& _]
  (let [result (run-tests 'build-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
