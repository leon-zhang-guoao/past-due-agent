(ns open.atlas.studio.ui.graph-scope-test
  (:require [cljs.test :refer [deftest is]]
            [open.atlas.studio.ui.graph-scope :as scope]))
(def graph
  {:nodes [{:attribute "a/id"} {:attribute "a/name"} {:attribute "b/id"}
           {:attribute "#{a/id b/id}" :combination? true}
           {:attribute "c/id"} {:attribute "c/child"} {:attribute "unrelated/id"}]
   :edges [{:from "a/id" :to "a/name"}
           {:from "a/id" :to "#{a/id b/id}"}
           {:from "b/id" :to "#{a/id b/id}"}
           {:from "#{a/id b/id}" :to "c/id"}
           {:from "c/id" :to "c/child" :nested? true}]})
(deftest neighborhood-does-not-expand-transitively
  (let [shown (scope/neighborhood graph ["a/id"])]
    (is (= #{"a/id" "a/name" "b/id" "#{a/id b/id}"} (set (map :attribute (:nodes shown)))))
    (is (= 3 (count (:edges shown))))))
(deftest all-entity-attributes-are-kept-including-isolated-ones
  (is (= #{"a/name" "a/id" "unrelated/id"}
         (set (map :attribute (:nodes (scope/neighborhood graph ["a/name" "unrelated/id"]))))))
  (is (empty? (:nodes (scope/neighborhood graph ["missing"])))))
(deftest relationship-filters-preserve-the-node-selection
  (let [shown (scope/neighborhood graph ["c/id"])]
    (is (= (:nodes shown) (:nodes (scope/filter-edges shown :nested))))
    (is (= 1 (count (:edges (scope/filter-edges shown :nested)))))
    (is (not-any? :nested? (:edges (scope/filter-edges shown :direct))))))
