(ns open.atlas.studio.ui.semantic-test
  (:require [cljs.test :refer [deftest is]]
            [open.atlas.studio.ui.semantic :as semantic]))

(deftest saved-definition-projects-entities-and-relationships
  (let [document {:graph {:graph/entities ["sales.order" "sales.customer"]}
                  :vocabulary
                  {:entities
                   {"sales.order" {:entity/label "Order"
                                    :entity/description "A purchase order"
                                    :entity/identity [:sales.order/id]
                                    :entity/attributes [:sales.order/id :sales.order/customer]}
                    "sales.customer" {:entity/label "Customer"
                                       :entity/identity [:sales.customer/id]
                                       :entity/attributes [:sales.customer/id]}}
                   :attributes
                   {:sales.order/id {:attribute/label "Order ID" :attribute/type :integer}
                    :sales.order/customer {:attribute/label "Customer"
                                           :attribute/type :ref
                                           :attribute/target "sales.customer"
                                           :attribute/cardinality :one}
                    :sales.customer/id {:attribute/label "Customer ID" :attribute/type :integer}}
                   :terms {}
                   :references []}}
        need (semantic/document->need document)
        order (first (:things need))]
    (is (= ["sales.order" "sales.customer"] (mapv :name (:things need))))
    (is (= ["sales.order/id"] (:identity order)))
    (is (= "Order ID" (get-in order [:attribute-definitions "sales.order/id" :attribute/label])))
    (is (= [{:name "sales.order/customer" :kind :one :target "sales.customer"}]
           (:relations order)))))

(deftest source-search-and-unbound-filter-keep-current-state
  (let [rows [{:resolver-id "orders" :label "Orders" :status :ready}
              {:resolver-id "people" :label "People" :status :no-compatible-source}
              {:resolver-id "local" :label "Local" :status :not-required}]]
    (is (= ["people"] (mapv :resolver-id (semantic/filter-source-rows rows "" true))))
    (is (= ["orders"] (mapv :resolver-id (semantic/filter-source-rows rows "ORD" false))))
    (is (empty? (semantic/filter-source-rows rows "ORD" true)))))

(deftest source-loading-surfaces-success-response-findings
  (let [errors (atom []) loading (atom []) views (atom [])]
    (with-redefs [open.atlas.studio.ui.api/intent!
                  (fn [_ args handlers]
                    ((:on-success handlers)
                     (if (= :read (:action args)) {:document {} :revision 1}
                       {:findings [{:code :semantic.studio/unknown-action}]})))]
      (semantic/load-source-draft! "w/g" {:on-draft identity :on-view #(swap! views conj %)
                                          :on-loading #(swap! loading conj %) :on-error #(swap! errors conj %)}))
    (is (= [true false] @loading))
    (is (empty? @views))
    (is (= :semantic.studio/unknown-action (get-in @errors [0 :findings 0 :code])))
    (is (re-find #"resolver Sources" (:message (first @errors))))))

(deftest source-loading-distinguishes-empty-draft-from-failed-load
  (doseq [[response expected-errors] [[{:resolver-sources [] :operation-sources [] :revision 0} 0]
                                     [{} 1]]]
    (let [errors (atom []) views (atom [])]
      (with-redefs [open.atlas.studio.ui.api/intent!
                    (fn [_ args handlers] ((:on-success handlers) (if (= :read (:action args)) {:document {}} response)))]
        (semantic/load-source-draft! "w/g" {:on-draft identity :on-view #(swap! views conj %)
                                            :on-loading identity :on-error #(swap! errors conj %)}))
      (is (= expected-errors (count @errors)))
      (is (= (- 1 expected-errors) (count @views))))))
