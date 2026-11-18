(ns open.atlas.common.artifact.semantic-graph-package-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [open.atlas.common.contract :as version]
            [open.atlas.common.semantic-contract :as contract]
            [open.atlas.common.semantic-graph-package :as package]))

(def checkers {"sql" (constantly true)})
(def caps #{:semantic/core-v1 :graph/single-owner-v1
            :deployment/source-slot-v1 :artifact/embedded-closure-v1})

(defn pure-graph [id terms deps]
  {:contract version/semantic-v2
   :workspace {:workspace/id "portable"}
   :graph {:graph/usage-guide {:guide/purpose "Fixture behavior" :guide/asset-scope "Declared fixture assets"
 :guide/applicability "Test environment only" :guide/limitations ["Not production data"]
 :guide/composition "Pinned dependencies; review before reuse" :guide/examples []} :graph/id id :graph/label id :graph/exposure :restricted
           :graph/entities [] :graph/resolvers [] :graph/operations []
           :graph/terms (vec (keys terms)) :graph/dependencies deps}
   :vocabulary {:entities {} :attributes {} :terms terms :aliases []
                :references [] :equivalences []}
   :execution {:resolvers {} :operations {} :sources {} :bindings {} :selection {}}
   :ownership {:entities {} :resolvers {} :operations {}
               :terms (into {} (map (fn [term] [term id]) (keys terms)))}
   :publication {:release/version "1" :source/revision 1
                 :required-capabilities (cond-> caps (seq deps) (conj :graph/dependencies-v1))}})

(defn ref-of [document]
  (let [text (:text (contract/write-document document checkers))]
    {:graph/id (get-in document [:graph :graph/id])
     :release/version (get-in document [:publication :release/version])
     :content/hash (contract/content-hash text)}))

(deftest package-embeds-a-distinct-transitive-closure
  (let [common (pure-graph "common" {"common/sku_no" {:term/label "产品编码"
                                                       :term/description "共享产品业务编码"
                                                       :term/format :code}} [])
        child (-> (pure-graph "orders" {} [(ref-of common)])
                  (assoc-in [:vocabulary :entities "demo.part"]
                            {:entity/label "产品" :entity/identity [:demo.part/sku_no]
                             :entity/attributes [:demo.part/sku_no]})
                  (assoc-in [:vocabulary :attributes :demo.part/sku_no]
                            {:attribute/label "产品编码" :attribute/description "共享产品业务编码"
                             :attribute/type :string :attribute/nullable? false
                             :attribute/term "common/sku_no"})
                  (assoc-in [:graph :graph/entities] ["demo.part"])
                  (assoc-in [:ownership :entities] {"demo.part" "orders"}))
        root (pure-graph "commerce" {} [(ref-of child)])
        built (package/build root [root child common] checkers)
        read (package/read-package (:text built) (:package-digest built) checkers)]
    (is (empty? (:findings built)) (pr-str built))
    (is (= 3 (count (get-in built [:package :graphs]))))
    (is (= root (:root read)))
    (is (= #{"commerce" "orders" "common"}
           (set (map :graph/id (get-in read [:package :graphs])))))
    (is (= :semantic.package/digest
           (get-in (package/read-package (:text built) "sha256:wrong" checkers) [:findings 0 :code])))
    (is (= :semantic.package/term-missing
           (get-in (package/build root [root (assoc-in child [:graph :graph/dependencies] [])] checkers)
                   [:findings 0 :code])))))

(deftest package-carries-only-a-logical-source-slot
  (let [graph (-> (pure-graph "orders" {} [])
                  (assoc-in [:execution :sources "primary"]
                            {:source/kind "sql" :source/effects #{:read}})
                  (update-in [:publication :required-capabilities] conj :connector/sql))
        built (package/build graph [graph] checkers)]
    (is (empty? (:findings built)) (pr-str built))
    (is (str/includes? (:text built) ":source/kind \"sql\""))
    (is (not (str/includes? (:text built) "jdbc:")))
    (is (not (str/includes? (:text built) "password")))
    (is (not (str/includes? (:text built) "token")))))

(deftest versioned-usage-guide-and-delivery-profile
  (let [legacy (update (pure-graph "shared" {} []) :graph dissoc :graph/usage-guide)
        guide {:guide/purpose "Shared business vocabulary"
               :guide/asset-scope "Local terms; inventory is derived"
               :guide/applicability "Reuse as an exact dependency"
               :guide/limitations ["No query entry point"]
               :guide/composition "Bind attributes to published terms; review equivalence"
               :guide/examples []}
        doc (assoc-in legacy [:graph :graph/usage-guide] guide)
        built (package/build-deliverable doc [doc] checkers)
        reread (package/read-package (:text built) (:package-digest built) checkers)]
    (is (empty? (:findings (package/build legacy [legacy] checkers))))
    (is (empty? (:findings (package/build-deliverable legacy [legacy] checkers))))
    (is (empty? (:findings built)))
    (is (= guide (get-in reread [:root :graph :graph/usage-guide])))
    (is (not= (ref-of legacy) (ref-of doc)))
    (is (= [] (get-in (contract/usage-catalog doc) [:assets :terms])))
    (is (seq (:findings (contract/validate-deliverable
                         (assoc-in doc [:graph :graph/usage-guide :guide/examples] ["missing"])
                         checkers))))
    (is (seq (:findings (contract/validate-deliverable
                         (assoc-in doc [:graph :graph/usage-guide :endpoint] "not-config")
                         checkers))))
    (is (empty? (:findings (contract/validate-deliverable
                         (update-in doc [:graph :graph/usage-guide] dissoc :guide/purpose)
                         checkers))))))

(deftest a-package-sealed-under-a-retired-contract-is-refused-by-name
  ;; The fixture is a real 1.4 package with its transported digest. Task book
  ;; «contract-upgrade-and-retirement» PR1 (owner 2026-09-19): it is no longer
  ;; loaded; the digest still verifies, then the reader refuses by descriptor.
  (let [text (slurp (io/resource "legacy-guide-free-package.edn"))
        digest "sha256:67e719cbebd719e80640f2659c48a1aff13486c87890e711bb76af6e6a50eac7"
        read-back (package/read-package text digest checkers)]
    (is (= [(assoc version/version-retired :path [:graphs])] (:findings read-back)))
    (is (nil? (:root read-back)))
    (is (= :semantic.package/digest
           (get-in (package/read-package text "sha256:wrong" checkers) [:findings 0 :code]))
        "the digest is still checked first")))

(deftest a-guide-free-2-0-package-loads
  (let [legacy (update (pure-graph "guide-free" {} []) :graph dissoc :graph/usage-guide)
        built (package/build legacy [legacy] checkers)
        read-back (package/read-package (:text built) (:package-digest built) checkers)]
    (is (empty? (:findings read-back)))
    (is (= "guide-free" (get-in read-back [:root :graph :graph/id])))
    (is (nil? (get-in read-back [:root :graph :graph/usage-guide])))
    (is (= (:text built) (:text read-back)))))
