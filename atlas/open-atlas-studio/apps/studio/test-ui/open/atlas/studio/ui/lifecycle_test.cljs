(ns open.atlas.studio.ui.lifecycle-test
  (:require [cljs.test :refer [deftest is]]
            [open.atlas.studio.ui.lifecycle :as lifecycle]))

(deftest split-and-merge-keep-direction-and-do-not-invent-missing-nodes
  (let [nodes (mapv #(hash-map :nifi.lineage.node/id % :nifi.lineage.node/type "EVENT") ["p" "a" "b" "m"])
        links (mapv (fn [i [from to]] {:nifi.lineage.link/id (str i) :nifi.lineage.link/source_id from :nifi.lineage.link/target_id to})
                    (range) [["p" "a"] ["p" "b"] ["a" "m"] ["b" "m"] ["m" "missing"]])
        elements (lifecycle/graph-elements nodes links)
        edges (drop 4 elements)]
    (is (= 8 (count elements)))
    (is (= [["node/p" "node/a"] ["node/p" "node/b"] ["node/a" "node/m"] ["node/b" "node/m"]]
           (mapv (juxt #(get-in % [:data :source]) #(get-in % [:data :target])) edges)))
    (is (not-any? #(= "node/missing" (get-in % [:data :id])) elements))))
