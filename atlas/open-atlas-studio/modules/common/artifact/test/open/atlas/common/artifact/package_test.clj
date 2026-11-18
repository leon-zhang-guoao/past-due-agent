(ns open.atlas.common.artifact.package-test
  "doc 104 §8.17.7 A: the OPTIONAL `:package` block in `manifest.edn`.

   Two obligations beyond «it round-trips»: sealing WITHOUT a package must
   still write the manifest it always wrote (the frozen vectors depend on it),
   and the block must be covered by `artifact-digest` — a package that can be
   swapped without changing the digest would let an installer be told it needs
   a different set of files than the one the cut declared."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.artifact :as artifact]))

(def ^:private content-edn
  (slurp (io/resource "golden/minimal/content.edn") :encoding "UTF-8"))

(def ^:private content-hash (artifact/content-hash-of-text content-edn))

(def ^:private provenance
  {:workspace "core-team" :graph "b1" :version 1
   :cut-at "2026-08-22T00:00:00Z" :cut-by "package-test"})

(def ^:private package
  {:name "open-atlas/dvd"
   ;; already in the normalized order the sealer writes (sorted, distinct)
   :requires {:vars ["NIFI_CLIENT_STORE_PASS"] :files ["data/open-atlas-demo.db"
                                                       "studio/nifi-client.p12"]}
   :compat {:dialect "v1" :connectors {"sql" "v1" "api" "v1"}}})

(defn- seal
  ([] (artifact/seal-graph content-edn content-hash provenance
                           {:findings []} {:previous-version nil} {:attribute-count 0}))
  ([pkg] (artifact/seal-graph content-edn content-hash provenance
                              {:findings []} {:previous-version nil} {:attribute-count 0}
                              pkg)))

(deftest six-arg-call-and-nil-package-write-the-same-bytes
  (testing "the key is ABSENT, not nil-valued — a manifest sealed today is
            byte-identical to one sealed before the key existed (D4.3)"
    (is (= (:manifest-edn (seal)) (:manifest-edn (seal nil))))
    (is (= (:artifact-digest (seal)) (:artifact-digest (seal nil))))
    (is (not (contains? (edn/read-string (:manifest-edn (seal))) :package)))))

(deftest package-is-written-and-covered-by-the-digest
  (let [with (seal package)]
    (is (= [] (:findings with)))
    (is (= package (:package (edn/read-string (:manifest-edn with)))))
    (testing "content.edn is untouched: the package is publication context"
      (is (= content-hash (artifact/content-hash-of-text (get (:files with) "content.edn")))))
    (testing "a different package ⇒ a different artifact-digest"
      (is (not= (:artifact-digest with) (:artifact-digest (seal))))
      (is (not= (:artifact-digest with)
                (:artifact-digest (seal (assoc package :name "open-atlas/other"))))))))

(deftest requires-lists-are-sorted-so-the-digest-is-a-function-of-the-content
  (let [a (seal package)
        b (seal (assoc-in package [:requires :files]
                          ["data/open-atlas-demo.db" "studio/nifi-client.p12"
                           "data/open-atlas-demo.db"]))]
    (is (= (:artifact-digest a) (:artifact-digest b)))
    (is (= ["data/open-atlas-demo.db" "studio/nifi-client.p12"]
           (get-in (edn/read-string (:manifest-edn a)) [:package :requires :files])))))

(deftest malformed-packages-are-refused-not-silently-dropped
  (doseq [[label bad path]
          [["not a map" "open-atlas/dvd" [:package]]
           ["blank name" {:name "  "} [:package :name]]
           ["missing name" {:requires {:vars []}} [:package]]
           ["unknown key" (assoc package :vars []) [:package]]
           ["requires not strings" (assoc-in package [:requires :vars] [42]) [:package :requires :vars]]
           ["compat not a map" (assoc package :compat "v1") [:package :compat]]]]
    (testing label
      (let [{:keys [findings manifest-edn]} (seal bad)]
        (is (nil? manifest-edn) "错误与产物互斥 (finding-v1 §2)")
        (is (some #(= path (:path %)) findings) (pr-str findings))))))

(deftest read-artifact-accepts-manifests-with-and-without-a-package
  (doseq [[label sealed expected]
          [["with" (seal package) package]
           ["without" (seal) nil]]]
    (testing label
      (let [r (artifact/read-artifact (:files sealed)
                                      {:expected-digest (:artifact-digest sealed)
                                       :closure :unverified})]
        (is (= [] (:findings r)))
        (is (= expected (:package r)))))))

(deftest with-package-golden-vector-reproduces
  (testing "a sealed triple carrying a package block (fixtures/golden/with-package)"
    (let [golden (edn/read-string (slurp (io/resource "golden.edn")))
          texts (into {} (for [f artifact/artifact-files]
                           [f (slurp (io/resource (str "golden/with-package/" f))
                                     :encoding "UTF-8")]))]
      (is (= (get-in golden [:with-package :content-hash])
             (artifact/content-hash-of-text (get texts "content.edn"))))
      (is (= (get-in golden [:with-package :artifact-digest])
             (artifact/artifact-digest texts)))
      (testing "and it still loads, package exposed"
        (let [r (artifact/read-artifact texts
                                        {:expected-digest (get-in golden [:with-package :artifact-digest])
                                         :closure :unverified})]
          (is (= [] (:findings r)))
          (is (= {:name "open-atlas/b1"
                  :requires {:vars ["B1_JDBC_URL"] :files []}
                  :compat {:dialect "v1" :connectors {"sql" "v1"}}}
                 (:package r))))))))
