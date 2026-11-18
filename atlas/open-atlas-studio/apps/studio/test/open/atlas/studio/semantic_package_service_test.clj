(ns open.atlas.studio.semantic-package-service-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [open.atlas.common.contract :as version]
            [open.atlas.common.semantic-contract :as contract]
            [open.atlas.common.semantic-graph-package :as package]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.semantic-package-service :as service]
            [open.atlas.studio.semantic-service :as semantic]))

(def caps #{:semantic/core-v1 :graph/single-owner-v1
            :deployment/source-slot-v1 :artifact/embedded-closure-v1})

(defn document [id label dependencies]
  {:contract version/semantic-v2 :workspace {:workspace/id "portable"}
   :graph {:graph/id id :graph/label label :graph/exposure :restricted
           :graph/entities [] :graph/resolvers [] :graph/operations []
           :graph/terms [] :graph/dependencies dependencies}
   :vocabulary {:entities {} :attributes {} :terms {} :aliases [] :references [] :equivalences []}
   :execution {:resolvers {} :operations {} :sources {} :bindings {} :selection {}}
   :ownership {:entities {} :resolvers {} :operations {} :terms {}}
   :publication {:release/version "1" :source/revision 1
                 :required-capabilities (cond-> caps (seq dependencies) (conj :graph/dependencies-v1))}})

(defn ref-of [d]
  {:graph/id (get-in d [:graph :graph/id])
   :release/version (get-in d [:publication :release/version])
   :content/hash (contract/content-hash (:text (contract/write-document d semantic/checkers)))})

(deftest package-import-is-reviewed-and-does-not-publish-or-bind-sources
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "owner"}
        caller {:workspace "w" :principal principal}
        child (document "portable-child" "shared" [])
        root (document "portable-root" "orders" [(ref-of child)])
        built (package/build root [root child] semantic/checkers)
        request {:text (:text built) :digest (:package-digest built) :root-graph "w/orders"}
        provenance {:entrypoint :ui :actor principal}]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (graph/register-graph! conn {:workspace "w" :name "orders" :members {}
                                   :created-at "2026-09-09T00:00:00Z"})
      (let [planned (service/plan conn caller request semantic/checkers)
            imported (service/apply! conn caller (assoc request :targets (:targets planned)
                                                        :plan-id (:plan-id planned))
                                     semantic/checkers provenance)
            db (pool/db conn)
            local-root (graph/graph db "w/orders")
            local-child (graph/graph db "w/shared")
            root-draft (pool/semantic-draft db "w" (:graph/id local-root))
            child-draft (pool/semantic-draft db "w" (:graph/id local-child))]
        (is (empty? (:findings planned)) (pr-str planned))
        (is (= #{:update :create} (set (map :mode (:graphs planned)))))
        (is (false? (:publishes? planned)))
        (is (false? (:activates? planned)))
        (is (true? (:imported? imported)) (pr-str imported))
        (is (= 1 (:revision root-draft)))
        (is (= 1 (:revision child-draft)))
        (is (= (:graph/id local-root) (get-in root-draft [:document :graph :graph/id])))
        (is (= (:graph/id local-child)
               (get-in root-draft [:document :graph :graph/dependencies 0 :graph/id])))
        (is (= "w" (get-in child-draft [:document :workspace :workspace/id])))
        (is (= :semantic.package/plan-changed
               (get-in (service/apply! conn caller (assoc request :targets (:targets planned)
                                                          :plan-id "stale")
                                       semantic/checkers provenance)
                       [:findings 0 :code]))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest package-import-can-remap-owned-namespaces-without-changing-physical-values
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "owner"}
        caller {:workspace "w" :principal principal}
        original-text (slurp "../../docs/guides/examples/orders-semantic-graph-2.0.edn")
        portable-id "REPLACE-WITH-GRAPH-ID"
        request {:text original-text :root-graph "w/orders-import"
                 :namespace-remaps {portable-id "sandbox.orders"}}
        provenance {:entrypoint :ui :actor principal}]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (let [planned (service/plan conn caller request semantic/checkers)
            imported (service/apply! conn caller
                                     (assoc request :targets (:targets planned)
                                                    :plan-id (:plan-id planned)
                                                    :digest (:package-digest planned))
                                     semantic/checkers provenance)
            target (graph/graph (pool/db conn) "w/orders-import")
            document (:document (pool/semantic-draft (pool/db conn) "w" (:graph/id target)))
            example (first (get-in document [:graph :graph/query-examples]))]
        (is (empty? (:findings planned)) (pr-str planned))
        (is (not= (:package-digest planned) (:candidate-digest planned)))
        (is (= [{:package-graph portable-id :namespace "sandbox.orders" :assets 20}]
               (:namespace-preview planned)))
        (is (true? (:imported? imported)) (pr-str imported))
        (is (every? #(str/starts-with? % "sandbox.orders.")
                    (keys (get-in document [:vocabulary :entities]))))
        (is (contains? (get-in document [:vocabulary :attributes]) :sandbox.orders.order/id))
        (is (= "SELECT description FROM part WHERE sku_No = :sku_no"
               (get-in document [:execution :bindings "part-by-sku-sql" :binding/implementation :query]))
            "SQL and physical column spelling are opaque deployment values")
        (is (= 1001 (get-in example [:example/entity :sandbox.orders.order/id])))
        (is (= [:sandbox.orders.order/customer_id :sandbox.orders.order/from_acct_no
                :sandbox.orders.order/status :sandbox.orders.order/total]
               (edn/read-string (:example/eql example))))
        (is (= original-text (slurp "../../docs/guides/examples/orders-semantic-graph-2.0.edn"))
            "the transported package is immutable")
        (let [collision (service/plan conn caller (assoc request :root-graph "w/second-import")
                                      semantic/checkers)]
          (is (= :semantic.package/namespace-conflict
                 (get-in collision [:findings 0 :code])))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest remapping-a-root-keeps-an-external-common-term-owned-by-a-child
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "owner"}
        caller {:workspace "w" :principal principal}
        child (-> (document "portable-terms" "Common terms" [])
                  (assoc-in [:vocabulary :terms "common/sku_no"]
                            {:term/label "SKU" :term/description "Shared product code"})
                  (assoc-in [:ownership :terms "common/sku_no"] "portable-terms")
                  (assoc-in [:graph :graph/terms] ["common/sku_no"]))
        root (-> (document "portable-root" "Orders" [(ref-of child)])
                 (assoc-in [:vocabulary :entities "sales.part"]
                           {:entity/label "Part" :entity/identity [:sales.part/sku]
                            :entity/attributes [:sales.part/sku]})
                 (assoc-in [:vocabulary :attributes :sales.part/sku]
                           {:attribute/label "SKU" :attribute/type :string
                            :attribute/nullable? false :attribute/term "common/sku_no"})
                 (assoc-in [:ownership :entities "sales.part"] "portable-root")
                 (assoc-in [:graph :graph/entities] ["sales.part"]))
        built (package/build root [root child] semantic/checkers)
        request {:text (:text built) :digest (:package-digest built) :root-graph "w/orders"
                 :namespace-remaps {"portable-root" "sandbox.sales"}}
        provenance {:entrypoint :ui :actor principal}]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (let [planned (service/plan conn caller request semantic/checkers)
            imported (service/apply! conn caller (assoc request :targets (:targets planned)
                                                        :plan-id (:plan-id planned))
                                     semantic/checkers provenance)
            target (graph/graph (pool/db conn) "w/orders")
            saved (:document (pool/semantic-draft (pool/db conn) "w" (:graph/id target)))]
        (is (empty? (:findings planned)) (pr-str planned))
        (is (true? (:imported? imported)) (pr-str imported))
        (is (= "common/sku_no"
               (get-in saved [:vocabulary :attributes :sandbox.sales.part/sku :attribute/term]))
            "a term supplied by an unchanged dependency remains external"))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))
