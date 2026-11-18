(ns open.atlas.common.dialect.key-docs-test
  "The key face is DERIVED from the closed schemas (owner ruling 2026-09-07,
   doc 84 §8.4.25), and this suite is what keeps the derivation honest: a new
   dialect key with no doc line, or a doc line for a key the schema no longer
   declares, fails the build. That is the whole point of deriving it — a
   hand-written list in the Studio browser would drift in silence (D1.8)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.dialect.impl.key-docs :as key-docs]
            [open.atlas.common.dialect.impl.schema :as schema]))

(def ^:private types (sort dialect/object-types))

(deftest every-schema-key-has-a-doc-line
  (doseq [t types
          {:keys [key]} (schema/key-face t)]
    (is (contains? key-docs/docs [t key])
        (str "no doc for " t " " key " — a new dialect key needs a meaning and "
             "a literal example in impl/key_docs.clj"))))

(deftest every-doc-line-names-a-key-the-schema-declares
  (let [declared (set (for [t types, {:keys [key]} (schema/key-face t)] [t key]))]
    (doseq [k (keys key-docs/docs)]
      (is (contains? declared k)
          (str "doc for " (pr-str k) " names a key the schema does not declare")))))

(deftest every-type-has-a-summary-line
  (doseq [t types]
    (is (seq (get key-docs/summaries t)) t)))

(deftest the-face-carries-meaning-and-a-literal-example-per-key
  (doseq [t types
          {:keys [key meaning example]} (:keys (dialect/key-face t))]
    (testing (str t " " key)
      (is (seq meaning))
      ;; A literal example is the exact bytes an author may type, so it starts
      ;; with the key itself — "a map" would be the type name that
      ;; OQ-MCP-WRITE-ERGONOMICS item 1 measured as useless.
      (is (str/starts-with? example (pr-str key))))))

(deftest a-required-key-is-marked-and-a-vocabulary-is-listed
  (testing "D2.14: a leaf attribute's :type is required; D2.29: a join's :target"
    (let [by-key (into {} (map (juxt :key identity)) (:keys (dialect/key-face "attribute")))]
      (is (:required? (get by-key :type)))
      (is (:required? (get by-key :target)))
      (is (false? (:required? (get by-key :label))))
      (is (= ["scalar"] (:variants (get by-key :type))))
      (is (= ["join"] (:variants (get by-key :target))))))
  (testing "a closed value list comes from the vocabulary, not from prose"
    (is (= #{:master :transaction :reference :event :bridge}
           (set (:enum (first (filter #(= :kind (:key %))
                                      (:keys (dialect/key-face "namespace"))))))))))

(deftest an-unknown-type-answers-nil-so-the-caller-refuses
  (is (nil? (dialect/key-face "navigation")))
  (is (nil? (dialect/key-face "graph"))))
