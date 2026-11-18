(ns open.atlas.server.console.example-package-test
  "The bundled example seals the SAME `:package` block Studio's cut seals
   (doc 104 §8.17.7 A).

   Two seal paths write a manifest — Studio's cut and this one — and §8.17.6's
   lesson is that a fact written twice is a fact that eventually disagrees with
   itself. So this asserts the block is there, is computed by the connector
   rather than hand-written, and names the package the console offers."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.server.console.example :as example])
  (:import [java.io File]))

(defn- temp-instance []
  {:root (io/file (str (java.nio.file.Files/createTempDirectory
                        "example-package-" (into-array java.nio.file.attribute.FileAttribute []))))})

(defn- rm-r [^File f]
  (doseq [x (reverse (file-seq f))] (.delete ^File x)))

(deftest the-bundled-example-seals-a-package-block
  (if-not (example/available?)
    ;; A checkout that never ran `clojure -T:build demo-db` and has no fixture
    ;; database has nothing to seal. Saying so beats a green test that ran no
    ;; assertion.
    (is true "no example is packaged with this build; nothing to seal")
    (let [instance (temp-instance)]
      (try
        (let [r (example/artifact-files instance "2026-09-06T00:00:00Z")
              manifest (edn/read-string (get (:files r) "manifest.edn"))
              pkg (:package manifest)]
          (is (empty? (:findings r)) (pr-str (:findings r)))
          (is (= "open-atlas/dvd" (:name pkg)))
          (testing "the requirement is the database this instance materialised"
            (is (= 1 (count (:files (:requires pkg)))))
            (is (.endsWith ^String (first (:files (:requires pkg)))
                           "open-atlas-demo.db"))
            (is (= [] (:vars (:requires pkg)))))
          (testing "and the contract versions it was sealed against"
            (is (= {"sql" "v1"} (:connectors (:compat pkg))))
            (is (some? (:dialect (:compat pkg))))))
        (finally (rm-r (:root instance)))))))
