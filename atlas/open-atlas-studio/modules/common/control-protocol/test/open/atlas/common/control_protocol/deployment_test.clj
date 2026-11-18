(ns open.atlas.common.control-protocol.deployment-test
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.common.control-protocol :as cp]))

(deftest every-refusal-stops-subsequent-deployment-commands
  (doseq [refusal [:owner :plan :offer :stage :load :activate nil]]
    (let [calls (atom [])
          step (fn [op] (fn [_] (swap! calls conj op)
                          (if (= op refusal) {:step op :findings [{:code :test/refused}]}
                              {:step op :result {:op op}})))
          result (cp/run-deployment! [(step :owner) (step :plan)]
                                    (into {} (map (fn [op] [op (step op)]) [:offer :stage :load :activate])))
          order [:owner :plan :offer :stage :load :activate]]
      (is (= (if refusal (conj (vec (take-while #(not= refusal %) order)) refusal) order) @calls))
      (is (= (boolean refusal) (boolean (seq (:findings result))))))))

(deftest stage-receives-only-the-missing-artifacts-from-offer
  (let [seen (atom nil)
        r (cp/run-deployment!
           [] {:offer (fn [_] {:result {:missing ["digest"]}})
               :stage (fn [acc] (reset! seen (get-in acc [:by-step :offer :missing])) {:result {}})
               :load (fn [_] {:result {}}) :activate (fn [_] {:result {:serving 2}})})]
    (is (= ["digest"] @seen))
    (is (= [:offer :stage :load :activate] (mapv :step (:steps r))))
    (is (= 2 (get-in r [:by-step :activate :serving])))))

(deftest importing-a-production-version-does-not-activate-it
  (let [calls (atom [])
        result (cp/run-deployment! []
                 (into {} (for [op [:offer :stage :load :activate]]
                            [op (fn [_] (swap! calls conj op) {:result {}})]))
                 {:activate? false})]
    (is (= [:offer :stage :load] @calls))
    (is (= @calls (mapv :step (:steps result))))))
