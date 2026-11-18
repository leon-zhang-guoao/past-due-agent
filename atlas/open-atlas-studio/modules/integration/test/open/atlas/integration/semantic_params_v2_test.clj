(ns open.atlas.integration.semantic-params-v2-test
  "Semantic contract 2.0 §3 (doc 109 §109.8 items 5 and 7, owner 2026-09-18) end
   to end: one 2.0 document over a stubbed api source -- a catalog search whose
   free `:params` (`:keyword` required, `:scope` with an allowed set and a
   default) reach the upstream as query parameters, and an item detail declared
   `:batch? true` whose binding carries the physical `:batch` block -- compiled
   through `compile-candidate` and queried through the real engine and api
   connector. The gate refuses an undeclared, a missing and a disallowed
   parameter before any request is issued; the same fragments under a 1.4
   descriptor are a schema error; a definition and binding that disagree about
   batching are refused at validation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [open.atlas.common.connector.api]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.contract :as version]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.semantic-compiler :as compiler]
            [open.atlas.common.semantic-contract :as contract]))

(def ^:private vocabulary
  {:entities {"catalog" {:entity/label "Catalog" :entity/identity [:catalog/id]
                         :entity/attributes [:catalog/id :catalog/items]}
              "item" {:entity/label "Item" :entity/identity [:item/id]
                      :entity/attributes [:item/id :item/name]}}
   :attributes {:catalog/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :catalog/items {:attribute/label "Items" :attribute/type :ref :attribute/nullable? false
                                :attribute/target "item" :attribute/cardinality :many}
                :item/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :item/name {:attribute/label "Name" :attribute/type :string :attribute/nullable? false}}})

(def ^:private batch {:parent-key :item/id :child-fk "id" :placeholder "ids" :chunk-size 25})

(def ^:private definitions
  {:resolvers {"catalog_search" {:label "Items of a catalog matching a search term"
                                 :effect :read :result/cardinality :many
                                 :input [:catalog/id] :output [{:catalog/items [:item/id]}]
                                 :pagination {:default 50 :max 100}
                                 :sort {:allowed [:item/id] :default [:item/id :asc]}
                                 ;; 2.0 §3.1: free parameters, next to no filters at all.
                                 :params {:keyword {:type :string :required? true :description "Full-text term"}
                                          :scope {:type :string :allowed ["standalone" "cluster"]
                                                  :default "standalone" :description "Node scope"}}}
               "item_detail" {:label "Item detail" :effect :read :result/cardinality :one
                              :input [:item/id] :output [:item/name]
                              ;; 2.0 §3.2: the definition says it; the binding keeps the block.
                              :batch? true}}
   :operations {}
   :sources {"shop" {:source/kind "api" :source/effects #{:read}}}
   :bindings {"search-api" {:binding/for [:resolver "catalog_search"] :binding/source "shop" :binding/effect :read
                            :binding/implementation {:method "GET" :endpoint "/catalogs/{catalog/id}/items"
                                                     ;; api-v1: a template still holding an unresolved
                                                     ;; placeholder is dropped, so an absent param is omitted.
                                                     :query_params {"q" "{keyword}" "scope" "{scope}"}
                                                     :data_path "$.items" :result-root :catalog/items
                                                     :field_mapping {:item/id "$.id"}}
                            :binding/input-map {:catalog/id "catalog/id"}
                            ;; Contract 2.0 §3.1 amendment (owner 2026-09-18): an api parameter-map
                            ;; value must equal the parameter name -- the template's "q" is the
                            ;; physical query key, the parameter-map is not where it is spelled.
                            :binding/parameter-map {:keyword "keyword" :scope "scope"}
                            :binding/output-map {:catalog/items "catalog/items" :item/id "item/id"}}
              "detail-api" {:binding/for [:resolver "item_detail"] :binding/source "shop" :binding/effect :read
                            :binding/implementation {:method "GET" :endpoint "/items/{ids}"
                                                     :data_path "$.items" :batch batch
                                                     :field_mapping {:item/name "$.name"}}
                            :binding/input-map {:item/id "item/id"}
                            :binding/output-map {:item/name "item/name"}}}
   :selection {[:resolver "catalog_search"] "search-api"
               [:resolver "item_detail"] "detail-api"}})

(def ^:private adapters {"api" (spi/semantic-adapter "api")})
(def ^:private connections {"shop" {:base_url "https://fixture.invalid"}})
(def ^:private v2 {:contract version/semantic-v2})

(defn- query-of [url]
  (into {} (for [kv (str/split (or (second (str/split url #"\?" 2)) "") #"&") :when (seq kv)
                 :let [[k v] (str/split kv #"=" 2)]]
             [k (java.net.URLDecoder/decode (or v "") "UTF-8")])))

(defn- stub
  "A GET stub answering both routes and recording what it was asked."
  [calls]
  (fn [url _]
    (let [path (first (str/split (subs url (count "https://fixture.invalid")) #"\?" 2))]
      (cond
        (str/starts-with? path "/catalogs/")
        (let [q (query-of url)]
          (swap! calls conj [:search q])
          {:status 200 :body (json/generate-string {:items (mapv #(hash-map :id %) (range 1 31))})})

        (str/starts-with? path "/items/")
        (let [ids (mapv parse-long (str/split (java.net.URLDecoder/decode (last (str/split path #"/")) "UTF-8") #","))]
          (swap! calls conj [:detail ids])
          {:status 200 :body (json/generate-string {:items (mapv (fn [id] {:id id :name (str "Item " id)}) ids)})})

        :else (throw (ex-info "unexpected route" {:path path}))))))

(defn- batch-flag [candidate id]
  (-> candidate :compiled :engine/bundle :env ::pci/index-resolvers
      (get (symbol "open.atlas.common.engine.generated" id))
      pco/operation-config ::pco/batch?))

(deftest a-2-0-document-runs-free-params-and-a-declared-batch-through-the-real-api-connector
  (let [candidate (compiler/compile-candidate vocabulary definitions adapters connections v2)
        calls (atom [])
        q (fn [params]
            (engine/query (:compiled candidate) {:graph "shop" :version 1}
                          {:entity {:catalog/id 7}
                           :query [(list {:catalog/items [:item/id :item/name]} params)]}))]
    (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
    (when (:compiled candidate)
      (testing "::pco/batch? still comes from the binding's block, not from the flag (2.0 §3.2 lowering)"
        (is (true? (batch-flag candidate "item_detail")))
        (is (not (batch-flag candidate "catalog_search"))))
      (with-redefs [http/get (stub calls)]
        (testing "a declared param reaches the upstream by its own name; the default fills the other"
          (let [answer (q {:keyword "widget"})
                [_ search] (first (filter #(= :search (first %)) @calls))]
            (is (= :ok (:engine/status answer)) (pr-str answer))
            (is (= "widget" (get search "q")))
            (is (= "standalone" (get search "scope")))
            (is (= 30 (count (get-in answer [:engine/data :catalog/items]))))
            (is (= "Item 3" (:item/name (nth (get-in answer [:engine/data :catalog/items]) 2))))
            (testing "and the detail rows came in batches of the binding's chunk size"
              (is (= [25 5] (mapv (comp count second) (filter #(= :detail (first %)) @calls)))))))
        (testing "an allowed value overrides the default"
          (reset! calls [])
          (q {:keyword "widget" :scope "cluster"})
          (is (= "cluster" (get (second (first @calls)) "scope"))))
        (testing "the gate refuses before any request is issued (2.0 §3.1, 1.x gate)"
          (reset! calls [])
          (is (= :engine.request/missing-param (:code (q {}))))
          (is (= :engine.request/unknown-param (:code (q {:keyword "w" :whatever 1}))))
          (is (= :engine.request/param-value-not-allowed (:code (q {:keyword "w" :scope "mesh"}))))
          (is (empty? @calls)))))))

(def ^:private retired-1-4 {:contract/family "atlas-semantic" :contract/version "1.4.0"})

(deftest the-same-fragments-are-read-without-a-descriptor-and-refused-under-a-retired-one
  ;; Task book «contract-upgrade-and-retirement» PR1 (owner 2026-09-19).
  (let [compile (fn [options] (compiler/compile-candidate vocabulary definitions adapters connections options))]
    (is (some? (:compiled (compile {}))) (pr-str (:findings (compile {}))))
    (let [result (compile {:contract retired-1-4})]
      (is (nil? (:compiled result)))
      (is (= [version/version-retired] (:findings result))))))

(deftest a-definition-and-its-binding-must-agree-about-batching
  (testing "block without flag: refused at the definition"
    (let [r (compiler/compile-candidate vocabulary (update-in definitions [:resolvers "item_detail"] dissoc :batch?)
                                        adapters connections v2)]
      (is (some #(= [:execution/batch-mismatch [[:resolver "item_detail"] :batch?]] [(:code %) (:path %)])
                (:findings r)) (pr-str (:findings r)))))
  (testing "flag without block: refused at the binding"
    (let [r (compiler/compile-candidate vocabulary (update-in definitions [:bindings "detail-api" :binding/implementation] dissoc :batch)
                                        adapters connections v2)]
      (is (some #(= [:execution/batch-mismatch [:bindings "detail-api" :binding/implementation :batch]] [(:code %) (:path %)])
                (:findings r)) (pr-str (:findings r))))))

(deftest the-2-0-document-carries-both-capabilities-through-the-contract
  (let [document {:contract version/semantic-v2
                  :workspace {:workspace/id "shop-dev"}
                  :graph {:graph/id "shop" :graph/label "Shop" :graph/exposure :internal
                          :graph/entities ["catalog" "item"]
                          :graph/resolvers ["catalog_search" "item_detail"]
                          :graph/operations [] :graph/dependencies [] :graph/terms []}
                  :vocabulary vocabulary
                  :execution definitions
                  :ownership {:entities {"catalog" "shop" "item" "shop"}
                              :resolvers {"catalog_search" "shop" "item_detail" "shop"}
                              :operations {} :terms {}}
                  :publication {:release/version "1.0.0" :source/revision 1
                                :required-capabilities #{:semantic/core-v1 :connector/api
                                                         :graph/single-owner-v1 :deployment/source-slot-v1
                                                         :artifact/embedded-closure-v1 :query/pagination-v1
                                                         :query/sort-v1 :execution/params-v2 :execution/batch-v2}}}
        checkers (update-vals adapters :validate)]
    (is (empty? (:findings (contract/validate-document document checkers)))
        (pr-str (:findings (contract/validate-document document checkers))))
    (doseq [cap [:execution/params-v2 :execution/batch-v2]]
      (is (some #(= :contract/capability-undeclared (:code %))
                (:findings (contract/validate-document
                            (update-in document [:publication :required-capabilities] disj cap)
                            checkers)))
          (str cap " must be declared")))
    (is (= [version/version-retired]
           (:findings (contract/validate-document (assoc document :contract retired-1-4) checkers))))))
