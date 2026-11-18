(ns open.atlas.studio.ui.contract-blueprint-test
  (:require [cljs.test :refer [deftest is]]
            [open.atlas.studio.ui.contract-blueprint :as blueprint]))

(deftest contract-is-visible-without-legacy-pool-members
  (let [doc {:vocabulary {:entities {"order" {:entity/identity [:order/id]}}
                          :attributes {:order/id {} :order/items {} :item/id {} :item/name {} :order/unused {}}}
             :execution {:resolvers {"lookup" {:input [:order/id] :output [{:order/items [:item/id :item/name]}]}}
                         :selection {[:resolver "lookup"] "sql"}
                         :bindings {"sql" {:binding/source "readonly-db"}}}}
        p (blueprint/document->blueprint doc)
        edges (set (map (juxt :from :to) (get-in p [:graph :edges])))]
    (is (= 5 (count (:attributes p))))
    (is (= 5 (count (get-in p [:graph :nodes]))))
    (is (= #{["order/id" "order/items"] ["order/items" "item/id"] ["order/items" "item/name"]} edges))
    (is (= "readonly-db" (get-in p [:resolvers 0 :binding :binding/source])))))

(deftest compound-input-and-nested-output-preserve-shape
  (let [p (blueprint/document->blueprint
           {:execution {:resolvers {"multi" {:input [:a/id :b/id]
                                             :output [:a/id {:c/all [{:c/children [:d/id]}]}]}}}})
        edges (set (map (juxt :from :to) (get-in p [:graph :edges])))]
    (is (contains? edges ["a/id" "#{a/id b/id}"]))
    (is (contains? edges ["b/id" "#{a/id b/id}"]))
    (is (contains? edges ["c/all" "c/children"]))
    (is (contains? edges ["c/children" "d/id"]))
    (is (not (contains? edges ["#{a/id b/id}" "d/id"])))
    (is (not (contains? edges ["#{a/id b/id}" "a/id"])))
    (is (some :combination? (get-in p [:graph :nodes])))))

(deftest saved-operations-expose-selected-write-binding
  (let [p (blueprint/document->blueprint
           {:execution {:operations {"case/create" {:effect :write :input [:command/id] :output [:receipt/id]}}
                        :selection {[:operation "case/create"] "insert"}
                        :bindings {"insert" {:binding/source "trdc-db" :binding/effect :write}}}})]
    (is (= "case/create" (get-in p [:mutations 0 :id])))
    (is (= :write (get-in p [:mutations 0 :definition :effect])))
    (is (= "trdc-db" (get-in p [:mutations 0 :binding :binding/source])))))

(deftest input-v2-descriptors-use-attribute-nodes-without-losing-metadata
  (let [inputs [:case/id {:attribute :case/options :optional? true}]
        p (blueprint/document->blueprint
           {:vocabulary {:attributes {:case/id {} :case/options {} :case/name {}}}
            :execution {:resolvers {"lookup" {:input inputs :output [:case/options :case/name]}
                                    "optional-only" {:input [{:attribute :case/options :optional? true}]
                                                     :output [:case/name]}}}})
        nodes (get-in p [:graph :nodes])
        edges (set (map (juxt :from :to) (get-in p [:graph :edges])))]
    (is (= inputs (:input (first (filter #(= "lookup" (:id %)) (:resolvers p))))))
    (is (= #{"case/id" "case/options" "case/name" "#{case/id case/options}"}
           (set (map :attribute nodes))))
    (is (not-any? :external? (remove :combination? nodes)))
    (is (contains? edges ["case/options" "#{case/id case/options}"]))
    (is (contains? edges ["case/options" "case/name"]))
    (is (not (contains? edges ["#{case/id case/options}" "case/options"])))))

(deftest parameters-do-not-become-topology-inputs
  (let [p (blueprint/document->blueprint
           {:execution {:resolvers {"lookup" {:input [:case/id]
                                             :params {:search {:type :string}}
                                             :output [:case/title]}}}})]
    (is (= {:search {:type :string}} (get-in p [:resolvers 0 :parameters])))
    (is (= #{["case/id" "case/title"]}
           (set (map (juxt :from :to) (get-in p [:graph :edges])))))))
