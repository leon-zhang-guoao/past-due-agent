(ns open.atlas.integration.semantic-collection-test
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.common.semantic-compiler :as compiler]
            [open.atlas.common.engine :as engine]))

(def vocabulary
  {:entities {"item" {:entity/label "Item" :entity/identity [:item/id]
                      :entity/attributes [:item/id :item/all]}}
   :attributes {:item/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :item/all {:attribute/label "Items" :attribute/type :ref :attribute/target "item"
                           :attribute/cardinality :many :attribute/nullable? false}}})
(def collection
  {:ordering :source-defined :paging :none :completeness :not-guaranteed
   :bounds {:max-items 10 :max-response-bytes 65536 :timeout-ms 5000}})
(defn compile-with [collection]
  (compiler/compile-candidate
   vocabulary
   {:resolvers {"items" {:label "Items" :effect :read :input [] :output [{:item/all [:item/id]}]
                         :result/cardinality :many :collection collection}}
    :operations {} :sources {"fixture" {:source/kind "static" :source/effects #{:read}}}
    :bindings {"items" {:binding/for [:resolver "items"] :binding/source "fixture" :binding/effect :read
                        :binding/implementation {} :binding/input-map {} :binding/output-map {:item/all "all" :item/id "id"}}}
    :selection {[:resolver "items"] "items"}}
   {"static" {:source-collection? true :collection-limit? true :validate (constantly true) :lower (fn [_] {:type "static" :data [{:item/id 3} {:item/id 1} {:item/id 2}]})}}
   {"fixture" {:label "Fixture"}}))

(deftest preserves-source-order-and-reports-uncertain-completeness
  (let [candidate (compile-with collection)
        answer (engine/query (:compiled candidate) {:entity {} :query [{:item/all [:item/id]}]})]
    (is (:compiled candidate) (pr-str (:findings candidate)))
    (is (= [3 1 2] (mapv :item/id (get-in answer [:engine/data :item/all]))))
    (is (= {:ordering :source-defined :paging :none :completeness :not-guaranteed :returned-count 3}
           (get-in answer [:engine/collections [:item/all]])))))

(deftest unsupported-parameters-never-pretend-to-page-or-sort
  (let [candidate (compile-with collection)]
    (doseq [params [{:offset 1} {:order_by :item/id} {:sort_dir :desc} {:limit 1}]]
      (let [answer (engine/query (:compiled candidate) {:entity {} :query [{(list :item/all params) [:item/id]}]})]
        (is (= :rejected (:engine/status answer)))
        (is (nil? (:engine/data answer)))))))

(deftest item-budget-refuses-instead-of-truncating
  (let [candidate (compile-with (assoc-in collection [:bounds :max-items] 2))
        answer (engine/query (:compiled candidate) {:entity {} :query [{:item/all [:item/id]}]})]
    (is (= :engine.limit/exceeded (:code answer)))
    (is (nil? (:engine/data answer)))
    (is (nil? (:engine/collections answer)))))

(deftest explicit-static-limit-keeps-the-old-prefix-order
  (let [candidate (compile-with (assoc collection :limit {:default 2 :max 3}))
        answer (engine/query (:compiled candidate) {:entity {} :query [{:item/all [:item/id]}]})]
    (is (= [3 1] (mapv :item/id (get-in answer [:engine/data :item/all]))))))
