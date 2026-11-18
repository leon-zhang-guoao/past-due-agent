(ns open.atlas.runtime.obs.ratelimit-test
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.runtime.obs.impl.ratelimit :as rl]))

(defn- fixed-clock [t-atom] (fn [] @t-atom))

(deftest disabled-always-allows
  (rl/configure! {:limit-per-minute nil})
  (dotimes [_ 100]
    (is (:allowed? (rl/allow? "u1")))))

(deftest burst-up-to-capacity-then-blocked
  (let [now (atom 0)]
    (rl/configure! {:limit-per-minute 3 :clock (fixed-clock now)})
    (is (:allowed? (rl/allow? "u1")))
    (is (:allowed? (rl/allow? "u1")))
    (is (:allowed? (rl/allow? "u1")))
    (let [r (rl/allow? "u1")]
      (is (not (:allowed? r)))
      (is (pos? (:retry-after-secs r))))))

(deftest refill-over-time
  (let [now (atom 0)]
    (rl/configure! {:limit-per-minute 60 :clock (fixed-clock now)}) ; 1/sec
    (dotimes [_ 60] (rl/allow? "u1"))
    (is (not (:allowed? (rl/allow? "u1"))))
    (swap! now + 2000)                       ; +2s → ~2 tokens back
    (is (:allowed? (rl/allow? "u1")))
    (is (:allowed? (rl/allow? "u1")))
    (is (not (:allowed? (rl/allow? "u1"))))))

(deftest buckets-are-independent
  (let [now (atom 0)]
    (rl/configure! {:limit-per-minute 1 :clock (fixed-clock now)})
    (is (:allowed? (rl/allow? "u1")))
    (is (not (:allowed? (rl/allow? "u1"))))
    (is (:allowed? (rl/allow? "u2")) "different user has own bucket")))

(deftest limit-info-exposed
  (rl/configure! {:limit-per-minute 42})
  (is (= 42 (rl/limit)))
  (rl/configure! {:limit-per-minute nil})
  (is (nil? (rl/limit))))
