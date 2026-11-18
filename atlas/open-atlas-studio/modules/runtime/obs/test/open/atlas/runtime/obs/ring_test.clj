(ns open.atlas.runtime.obs.ring-test
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.runtime.obs.impl.ring :as ring]))

(deftest push-and-recent
  (testing "recent returns newest-first"
    (let [r (ring/make 10)]
      (ring/push! r {:n 1}) (ring/push! r {:n 2}) (ring/push! r {:n 3})
      (is (= [{:n 3} {:n 2} {:n 1}] (ring/recent r)))
      (is (= [{:n 3} {:n 2}] (ring/recent r 2))))))

(deftest cap-drops-oldest
  (testing "ring keeps only the last :cap items"
    (let [r (ring/make 3)]
      (doseq [i (range 5)] (ring/push! r {:n i}))
      (is (= [{:n 4} {:n 3} {:n 2}] (ring/recent r)))
      (is (= 3 (count (ring/recent r 100)))))))

(deftest clear-empties
  (let [r (ring/make 3)]
    (ring/push! r {:n 1})
    (ring/clear! r)
    (is (= [] (ring/recent r)))))
