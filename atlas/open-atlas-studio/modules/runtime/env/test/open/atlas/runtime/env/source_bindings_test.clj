(ns open.atlas.runtime.env.source-bindings-test
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.runtime.source-bindings :as bindings]))
(deftest scopes-and-kind-are-required
  (let [entries {["a" "g" "n"] {:type "nifi" :base_url "https://example.invalid"}}
        source {:semantic/workspace "a" :semantic/graph "g" :source/kind "nifi"}]
    (is (= (get entries ["a" "g" "n"]) (bindings/resolve-entry entries "n" source)))
    (doseq [other [(assoc source :semantic/workspace "b") (assoc source :semantic/graph "h") (assoc source :source/kind "api")]]
      (is (thrown? clojure.lang.ExceptionInfo (bindings/resolve-entry entries "n" other))))
    (is (= {:type "api"} (bindings/resolve-entry entries "n" {:type "api"})))))
(deftest missing-config-never-falls-back-to-a-same-named-source
  (is (thrown? clojure.lang.ExceptionInfo
               ((bindings/file-resolver "/nonexistent/atlas-bindings.edn" (fn [_ x] x))
                "n" {:semantic/workspace "a" :semantic/graph "g" :source/kind "nifi"}))))

(deftest portable-slots-can-share-one-registered-source
  (let [source {:type "sql" :jdbc_url "jdbc:example" :password "outside-package"}
        registry {:sources {"warehouse" source}
                  :bindings {["orders" "primary"] "warehouse"
                             ["catalog" "primary"] "warehouse"}}
        requirements [{:graph-id "orders" :slot-id "primary"
                       :source {:source/kind "sql"}}
                      {:graph-id "catalog" :slot-id "primary"
                       :source {:source/kind "sql"}}]]
    (is (= {["orders" "primary"] source ["catalog" "primary"] source}
           (bindings/resolve-package-bindings registry requirements)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (bindings/resolve-package-bindings
                  (assoc-in registry [:sources "warehouse" :type] "api") requirements)))))

(deftest installation-is-idempotent-and-refuses-overwriting-operator-config
  (let [dir (java.nio.file.Files/createTempDirectory "atlas-bindings-test-" (make-array java.nio.file.attribute.FileAttribute 0))
        file (.resolve dir "source-bindings.edn")
        entries {["w" "g" "s"] {:type "nifi" :base_url "https://localhost:9443"}}
        resolve! (bindings/file-resolver (str file) (fn [_ x] x))]
    (try
      (bindings/install-missing! (str file) entries)
      (bindings/install-missing! (str file) entries)
      (is (= (get entries ["w" "g" "s"])
             (resolve! "s" {:semantic/workspace "w" :semantic/graph "g" :source/kind "nifi"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (bindings/install-missing! (str file) {["w" "g" "s"] {:type "nifi" :base_url "https://different.invalid"}})))
      (is (= entries (clojure.edn/read-string (slurp (str file)))))
      (finally (java.nio.file.Files/deleteIfExists file) (java.nio.file.Files/deleteIfExists dir)))))

(deftest releases-keep-independent-source-bindings-and-legacy-remains-readable
  (let [dir (java.nio.file.Files/createTempDirectory "atlas-versioned-bindings-test-" (make-array java.nio.file.attribute.FileAttribute 0))
        file (.resolve dir "source-bindings.edn")
        v1 {:type "nifi" :base_url "https://old.example.invalid"}
        v2 {:type "nifi" :base_url "https://new.example.invalid"}
        resolve! (bindings/file-resolver (str file) (fn [_ x] x))]
    (try
      (bindings/install-missing! (str file) {["w" "g" "1" "source"] v1})
      (bindings/install-missing! (str file) {["w" "g" "2" "source"] v2})
      (is (= v1 (resolve! "source" {:semantic/workspace "w" :semantic/graph "g"
                                     :semantic/release "1" :source/kind "nifi"})))
      (is (= v2 (resolve! "source" {:semantic/workspace "w" :semantic/graph "g"
                                     :semantic/release "2" :source/kind "nifi"})))
      (let [error (try
                    (bindings/install-missing! (str file)
                                               {["w" "g" "2" "source"] (assoc v2 :base_url "https://third.invalid")})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :semantic.runtime/source-binding-conflict (:code (ex-data error))))
        (is (re-find #"Source source in release 2" (ex-message error))))
      (finally (java.nio.file.Files/deleteIfExists file) (java.nio.file.Files/deleteIfExists dir)))))
