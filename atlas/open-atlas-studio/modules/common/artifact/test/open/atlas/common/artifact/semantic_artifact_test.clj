(ns open.atlas.common.artifact.semantic-artifact-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [open.atlas.common.artifact :as legacy]
            [open.atlas.common.contract :as version]
            [open.atlas.common.semantic-artifact :as artifact]
            [open.atlas.common.artifact.semantic-graph-package-test :as graph-fixture]))

(def document (edn/read-string (slurp (io/resource "semantic-v2.edn"))))
(def checkers {"sql" #(string? (get-in % [:binding/implementation :query]))})
(def supported #{:semantic/core-v1 :connector/sql :graph/single-owner-v1
                 :deployment/source-slot-v1 :artifact/embedded-closure-v1})

(deftest immutable-candidate-roundtrip
  (let [encoded (artifact/encode-candidate document {} checkers)
        read-back (artifact/read-candidate (:files encoded) (:artifact-digest encoded) {} checkers supported)]
    (is (= document (:document read-back)))
    (is (= :contract-validated (:validation read-back)))
    (is (= encoded (artifact/encode-candidate document {} checkers)))))

(deftest corruption-and-capability-mismatch-do-not-return-a-document
  (let [encoded (artifact/encode-candidate document {} checkers)]
    (doseq [[files digest capabilities]
            [[(update (:files encoded) "content.edn" str " ") (:artifact-digest encoded) supported]
             [(dissoc (:files encoded) "report.edn") (:artifact-digest encoded) supported]
             [(:files encoded) nil supported]
             [(:files encoded) (:artifact-digest encoded) #{}]]]
      (let [result (artifact/read-candidate files digest {} checkers capabilities)]
        (is (seq (:findings result)))
        (is (nil? (:document result)))))))

(deftest self-consistent-digest-does-not-hide-a-manifest-identity-change
  (let [encoded (artifact/encode-candidate document {} checkers)
        files (update (:files encoded) "manifest.edn"
                      #(legacy/canonical-str (assoc (edn/read-string %) :graph/id "other")))
        result (artifact/read-candidate files (legacy/artifact-digest files) {} checkers supported)]
    (is (= :semantic.artifact/integrity (-> result :findings first :code)))))

(deftest deployment-coordinates-are-validated-before-sealing
  (let [candidate (assoc-in document [:publication :release/version] "8")]
    (doseq [coords [{:workspace "other" :graph "support" :version 8}
                    {:workspace "support-dev" :graph "support" :version 7}
                    {:workspace "support-dev" :graph "support" :version 0}]]
      (let [r (artifact/encode-candidate candidate {} checkers coords)]
        (is (seq (:findings r)))
        (is (nil? (:files r)))))))

(deftest shared-reader-loads-new-contract-and-requires-deployment
  (require 'open.atlas.common.connector.sql)
  (let [candidate (assoc-in document [:publication :release/version] "8")
        coords {:workspace "support-dev" :graph "support" :version 8}
        encoded (artifact/encode-candidate candidate {} checkers coords)
        loaded (legacy/read-artifact (:files encoded) {:expected-digest (:artifact-digest encoded)})
        unattached (artifact/encode-candidate candidate {} checkers)]
    (is (empty? (:findings encoded)))
    (is (= candidate (:document loaded)))
    (is (= coords (:deployment loaded)))
    (is (= :semantic.artifact/deployment
           (-> (legacy/read-artifact (:files unattached) {:expected-digest (:artifact-digest unattached)}) :findings first :code)))
    (let [files (update (:files encoded) "manifest.edn"
                        #(legacy/canonical-str (assoc-in (edn/read-string %) [:deployment :version] 9)))
          loaded (legacy/read-artifact files {:expected-digest (legacy/artifact-digest files)})]
      (is (= :semantic.artifact/deployment (-> loaded :findings first :code)))
      (is (nil? (:document loaded))))))

(deftest an-artifact-sealed-under-a-retired-contract-is-refused-by-the-shared-reader
  ;; Task book «contract-upgrade-and-retirement» PR1 (owner 2026-09-19): the
  ;; 1.x readers are gone, so a 1.2 artifact on the shelf is refused with the
  ;; retirement finding, never read as 2.0 or silently skipped. The refusal
  ;; is by descriptor, before any connector checker is consulted.
  (let [old (-> document
                (assoc-in [:contract :contract/version] "1.2.0")
                (update :graph dissoc :graph/terms)
                (update :ownership dissoc :terms)
                (assoc-in [:publication :release/version] "8"))
        coords {:workspace "support-dev" :graph "support" :version 8}
        content (legacy/canonical-str {:artifact/format-version 2 :document old})
        hash (legacy/content-hash-of-text content)
        files {"content.edn" content
               "manifest.edn" (legacy/canonical-str {:artifact/format-version 2 :content-hash hash
                                                     :graph/id "support" :release/version "8" :deployment coords})
               "report.edn" (legacy/canonical-str {:artifact/format-version 2 :content-hash hash
                                                   :validation :contract-validated})}
        loaded (legacy/read-artifact files {:expected-digest (legacy/artifact-digest files)})]
    (is (= [version/version-retired] (:findings loaded)))
    (is (nil? (:document loaded)))
    (is (= [version/version-retired] (:findings (artifact/encode-candidate old {} checkers coords)))
        "and it cannot be sealed again either")))

(def ^:private v20-params-document
  "Semantic contract 2.0 §3: a graph with one free parameter and the
   capability it therefore needs."
  (-> (graph-fixture/pure-graph "params" {} [])
      (assoc-in [:vocabulary :entities "demo.part"]
                {:entity/label "Part" :entity/identity [:demo.part/sku_no]
                 :entity/attributes [:demo.part/sku_no :demo.part/part_no]})
      (assoc-in [:vocabulary :attributes]
                {:demo.part/sku_no {:attribute/label "SKU" :attribute/type :integer :attribute/nullable? false}
                 :demo.part/part_no {:attribute/label "Part no" :attribute/type :string :attribute/nullable? false}})
      (assoc :execution {:resolvers {"part_by_sku" {:label "Part by SKU" :effect :read :result/cardinality :one
                                                    :input [:demo.part/sku_no] :output [:demo.part/part_no]
                                                    :params {:scope {:type :string :allowed ["a" "b"] :default "a"}}}}
                         :operations {}
                         :sources {"db" {:source/kind "sql" :source/effects #{:read}}}
                         :bindings {"sku-sql" {:binding/for [:resolver "part_by_sku"] :binding/source "db" :binding/effect :read
                                               :binding/implementation {:query "SELECT part_no FROM parts WHERE sku_no = :sku_no AND scope = :scope"}
                                               :binding/input-map {:demo.part/sku_no "sku_no"}
                                               :binding/parameter-map {:scope "scope"}
                                               :binding/output-map {:demo.part/part_no "part_no"}}}
                         :selection {[:resolver "part_by_sku"] "sku-sql"}})
      (assoc-in [:graph :graph/entities] ["demo.part"])
      (assoc-in [:graph :graph/resolvers] ["part_by_sku"])
      (assoc-in [:ownership :entities] {"demo.part" "params"})
      (assoc-in [:ownership :resolvers] {"part_by_sku" "params"})
      (update-in [:publication :required-capabilities] into [:connector/sql :execution/params-v2])))

(deftest version-20-params-and-batch-are-runtime-capabilities-of-this-build
  (is (every? artifact/runtime-capabilities [:execution/params-v2 :execution/batch-v2]))
  (let [encoded (artifact/encode-candidate v20-params-document {} checkers)
        read (fn [capabilities]
               (artifact/read-candidate (:files encoded) (:artifact-digest encoded) {} checkers capabilities))]
    (is (empty? (:findings encoded)) (pr-str (:findings encoded)))
    (is (= v20-params-document (:document (read artifact/runtime-capabilities)))
        (pr-str (:findings (read artifact/runtime-capabilities))))
    (let [older (read (disj artifact/runtime-capabilities :execution/params-v2))]
      (is (nil? (:document older)) "a runtime without the capability refuses the graph rather than dropping :params")
      (is (seq (:findings older))))))

(deftest version-20-priority-is-a-runtime-capability-of-this-build
  ;; Semantic contract 2.0 §4: a graph that ranks two producers of one
  ;; attribute is refused by a runtime that would plan them unranked.
  (is (contains? artifact/runtime-capabilities :execution/priority-v2))
  (let [ranked (-> v20-params-document
                   (update-in [:execution :resolvers "part_by_sku"] dissoc :params)
                   (update-in [:execution :bindings "sku-sql"] dissoc :binding/parameter-map)
                   (assoc-in [:execution :bindings "sku-sql" :binding/implementation :query]
                             "SELECT part_no FROM parts WHERE sku_no = :sku_no")
                   (assoc-in [:execution :resolvers "part_by_sku_cache"]
                             {:label "Part by SKU (cache)" :effect :read :result/cardinality :one
                              :input [:demo.part/sku_no] :output [:demo.part/part_no] :priority 10})
                   (assoc-in [:execution :bindings "sku-cache-sql"]
                             {:binding/for [:resolver "part_by_sku_cache"] :binding/source "db" :binding/effect :read
                              :binding/implementation {:query "SELECT part_no FROM parts_cache WHERE sku_no = :sku_no"}
                              :binding/input-map {:demo.part/sku_no "sku_no"}
                              :binding/output-map {:demo.part/part_no "part_no"}})
                   (assoc-in [:execution :selection [:resolver "part_by_sku_cache"]] "sku-cache-sql")
                   (update-in [:graph :graph/resolvers] conj "part_by_sku_cache")
                   (assoc-in [:ownership :resolvers "part_by_sku_cache"] "params")
                   (update-in [:publication :required-capabilities] disj :execution/params-v2)
                   (update-in [:publication :required-capabilities] conj :execution/priority-v2))
        encoded (artifact/encode-candidate ranked {} checkers)
        read (fn [capabilities]
               (artifact/read-candidate (:files encoded) (:artifact-digest encoded) {} checkers capabilities))]
    (is (empty? (:findings encoded)) (pr-str (:findings encoded)))
    (is (= ranked (:document (read artifact/runtime-capabilities)))
        (pr-str (:findings (read artifact/runtime-capabilities))))
    (let [older (read (disj artifact/runtime-capabilities :execution/priority-v2))]
      (is (nil? (:document older)) "a runtime without the capability refuses the graph rather than planning unranked")
      (is (seq (:findings older))))))

(deftest candidate-creation-allows-optional-guide
  (let [document (graph-fixture/pure-graph "candidate" {} [])
        no-guide (update document :graph dissoc :graph/usage-guide)
        encoded (artifact/encode-candidate document {} checkers)]
    (is (empty? (:findings (artifact/encode-candidate no-guide {} checkers))))
    (is (empty? (:findings encoded)))
    (is (= (get-in document [:graph :graph/usage-guide])
           (get-in (artifact/read-candidate (:files encoded) (:artifact-digest encoded)
                                           {} checkers artifact/runtime-capabilities)
                   [:document :graph :graph/usage-guide])))))

(deftest version-20-union-and-recursion-are-runtime-capabilities-of-this-build
  ;; Semantic contract 2.0 §6: this runtime discriminates union records and
  ;; clamps recursion to the declared ceiling; an older runtime must refuse
  ;; such a graph rather than plan one branch against the other's records.
  (is (contains? artifact/runtime-capabilities :execution/output-union-v2))
  (is (contains? artifact/runtime-capabilities :execution/output-recursion-v2)))
