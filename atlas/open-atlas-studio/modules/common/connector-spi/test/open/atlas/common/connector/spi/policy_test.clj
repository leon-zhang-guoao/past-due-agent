(ns open.atlas.common.connector.spi.policy-test
  "The four-level merge (D6.2) and the deployment's tighten-only clamp
   (doc 107 §107.3, owner 2026-09-18). One implementation serves api and sql,
   so this is where its arithmetic is pinned."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.spi :as spi]))

(deftest merge-runs-outermost-default-first
  (let [p (spi/effective-policy [nil
                                 {:timeout_ms 9000}                          ; connector type default
                                 {:max_retries 2 :retry_on #{:rate_limited}} ; slot policy
                                 {:max_retries 3}]                           ; binding override
                                nil)]
    (is (= 3 (:max_retries p)))
    (is (= #{:rate_limited} (:retry_on p)))
    (is (= 9000 (:timeout_ms p)))
    (testing "every key is present, so no caller re-derives a default"
      (is (= (set (keys spi/platform-default-policy)) (set (keys p)))))))

(deftest the-deployment-may-only-tighten
  (let [model [{:max_retries 3 :retry_on #{:rate_limited :unavailable}}]]
    (testing "lowering the retry budget and dropping a category is honoured"
      (let [p (spi/effective-policy model {:max_retries 1 :retry_on #{:rate_limited}})]
        (is (= 1 (:max_retries p)))
        (is (= #{:rate_limited} (:retry_on p)))))
    (testing "raising the budget or adding a category cannot widen it"
      (let [p (spi/effective-policy model {:max_retries 9 :retry_on #{:connection}})]
        (is (= 3 (:max_retries p)))
        (is (= #{} (:retry_on p))
            "the deployment's set is intersected with what the graph declared")))
    (testing "the capacity half is the deployment's to set outright"
      (is (= 250 (:timeout_ms (spi/effective-policy model {:timeout_ms 250})))))))

(deftest loosenings-name-the-key-and-nothing-else
  (is (empty? (spi/policy-loosenings {:max_retries 3 :retry_on #{:rate_limited}}
                                     {:max_retries 1 :retry_on #{:rate_limited}})))
  (let [found (spi/policy-loosenings {:max_retries 1 :retry_on #{:rate_limited}
                                      :batch {:concurrency 4}}
                                     {:max_retries 5 :retry_on #{:rate_limited :connection}
                                      :bulkhead {:concurrency 2}})]
    (is (= #{:max_retries :retry_on [:batch :concurrency]} (set (map :key found))))
    (is (= #{:connection} (:deployment (first (filter #(= :retry_on (:key %)) found)))))))

(deftest chunk-concurrency-is-clamped-by-the-permits-operations-granted
  ;; §107.3: `:batch :concurrency` is a semantic declaration, but it cannot
  ;; exceed the bulkhead the deployment set (PR E drives it; the clamp is here
  ;; so the two halves never disagree once it does).
  (is (= 2 (get-in (spi/effective-policy [{:batch {:concurrency 8}}]
                                         {:bulkhead {:concurrency 2}})
                   [:batch :concurrency]))))

(deftest retry-is-allowed-only-where-d64-says
  (let [p (spi/effective-policy [{:max_retries 2 :retry_on #{:rate_limited :unavailable}}] nil)]
    (is (spi/retry-allowed? p :rate_limited 1))
    (is (spi/retry-allowed? p :unavailable 2))
    (is (not (spi/retry-allowed? p :rate_limited 3)) "the budget is spent")
    (is (not (spi/retry-allowed? p :source 1)) "deterministic, not transient")
    (doseq [never [:circuit_open :bulkhead_rejected :unauthorized :not-found
                   :input-rejected :cancelled :mapping :timeout]]
      (is (not (spi/retry-allowed? p never 1))
          (str never " is never retried: retrying it is either our own refusal "
               "repeated or the same deterministic answer bought twice"))))
  (testing "a non-transient category written into :retry_on never becomes effective"
    (is (not (spi/retry-allowed? (spi/effective-policy [{:max_retries 2 :retry_on #{:source}}] nil)
                                 :source 1)))))

(deftest backoff-grows-and-an-upstream-retry-after-wins
  (let [p (spi/effective-policy [{:backoff_ms [100 800 2.0] :jitter 0}] nil)]
    (is (= 100 (spi/retry-backoff-ms p 1)))
    (is (= 200 (spi/retry-backoff-ms p 2)))
    (is (= 800 (spi/retry-backoff-ms p 5)) "capped at the declared maximum")
    (is (= 2000 (spi/retry-backoff-ms p 1 2000))
        "the source telling us when it will answer beats our guess"))
  (testing "jitter stays inside [1-j, 1+j] of the base"
    (let [p (spi/effective-policy [{:backoff_ms [1000 100000 1.0] :jitter 0.2}] nil)]
      (dotimes [_ 50]
        (let [ms (spi/retry-backoff-ms p 1)]
          (is (<= 800 ms 1200)))))))
