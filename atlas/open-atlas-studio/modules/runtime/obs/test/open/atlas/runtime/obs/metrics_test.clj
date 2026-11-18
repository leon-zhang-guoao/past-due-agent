(ns open.atlas.runtime.obs.metrics-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [open.atlas.runtime.obs.impl.metrics :as metrics]))

(use-fixtures :each (fn [f] (metrics/reset-all!) (f)))

(deftest counters-and-snapshot
  (metrics/record-query! {:request-id "r1" :source :api :op :query :status :ok :latency-ms 10})
  (metrics/record-query! {:request-id "r2" :source :api :op :query :status :error :latency-ms 20})
  (metrics/record-query! {:request-id "r3" :source :mcp :op :mutation :status :ok :latency-ms 30})
  (let [snap (metrics/snapshot)]
    (is (= 2 (get-in snap [:sources :api :queries])))
    (is (= 1 (get-in snap [:sources :api :errors])))
    (is (= 1 (get-in snap [:sources :mcp :mutations])))
    (is (= 2 (get-in snap [:totals :queries])))
    (is (= 1 (get-in snap [:totals :mutations])))
    (is (= 1 (get-in snap [:totals :errors])))
    (is (<= 0 (:uptime-ms snap)))))

(deftest latency-percentiles
  (doseq [ms (range 1 101)] ; 1..100
    (metrics/record-query! {:source :api :op :query :status :ok :latency-ms ms}))
  (let [stats (get-in (metrics/snapshot) [:sources :api])]
    (is (= 50 (:p50 stats)))
    (is (= 95 (:p95 stats)))
    (is (= 100 (:max stats)))))

(deftest recent-queries-ring-buffer
  (doseq [i (range 150)]
    (metrics/record-query! {:request-id (str "r" i) :source :api :op :query
                            :status :ok :latency-ms i}))
  (let [recent (metrics/recent-queries)]
    (is (= 100 (count recent)))
    (is (= "r149" (:request-id (first recent)))))   ; newest first
  (is (some? (metrics/query-by-request-id "r149")))
  (is (nil? (metrics/query-by-request-id "r0"))))   ; evicted

(deftest source-filter
  (metrics/record-query! {:request-id "a" :source :api :op :query :status :ok :latency-ms 1})
  (metrics/record-query! {:request-id "b" :source :admin :op :query :status :ok :latency-ms 1})
  (is (= ["b"] (mapv :request-id (metrics/recent-queries {:source :admin})))))

(deftest record-has-timestamp
  (metrics/record-query! {:request-id "t1" :source :api :op :query :status :ok :latency-ms 1})
  (is (inst? (:ts (metrics/query-by-request-id "t1")))))

(deftest fail-open
  (is (nil? (metrics/record-query! nil))))

(deftest nil-latency-in-snapshot
  (metrics/record-query! {:source :api :op :query :status :ok})
  (let [stats (get-in (metrics/snapshot) [:sources :api])]
    (is (nil? (:p50 stats)))
    (is (nil? (:max stats)))))

(deftest nil-request-id-lookup
  (metrics/record-query! {:source :api :op :query :status :ok :latency-ms 1}) ; no :request-id
  (is (nil? (metrics/query-by-request-id nil))))

;; --- series / series-from -------------------------------------------------

(defn- rec [epoch-ms latency-ms]
  {:ts (java.util.Date. (long epoch-ms)) :latency-ms latency-ms :source :api})

(deftest series-from-bucketing
  ;; ts span [0, 1000], width 250 over 4 buckets:
  ;;   b0 [0,250)   : 0, 100        -> count 2
  ;;   b1 [250,500) : 250, 300      -> count 2
  ;;   b2 [500,750) : (none)        -> count 0
  ;;   b3 [750,1000]: 800, 1000     -> count 2  (last bucket inclusive of max)
  (let [recs [(rec 0 10) (rec 100 nil) (rec 250 50) (rec 300 70)
              (rec 800 90) (rec 1000 110)]
        s    (#'metrics/series-from recs 4)]
    (is (= 4 (count s)))                                  ; length == buckets
    (is (= 6 (reduce + (map :count s))))                  ; counts sum to #records
    (is (= [2 2 0 2] (mapv :count s)))                    ; known per-bucket counts
    (is (number? (:p95 (nth s 0))))                       ; populated bucket has p95
    (is (nil? (:p95 (nth s 2))))                          ; empty bucket -> nil p95
    ;; b0 latencies [10] (100 is nil) -> p95 = 10
    (is (= 10 (:p95 (nth s 0))))))

(deftest series-from-insufficient
  (is (= [] (#'metrics/series-from [] 4)))
  (is (= [] (#'metrics/series-from [(rec 0 10)] 4)))            ; <2 records
  (is (= [] (#'metrics/series-from [(rec 5 1) (rec 5 2)] 4))))  ; zero ts-span

(deftest series-from-bucket-with-no-latencies
  ;; both records nil latency -> count present but p95 nil
  (let [s (#'metrics/series-from [(rec 0 nil) (rec 1000 nil)] 2)]
    (is (= 2 (count s)))
    (is (every? nil? (map :p95 s)))
    (is (= 2 (reduce + (map :count s))))))

(deftest series-public-reads-ring
  (metrics/record-query! {:source :api :op :query :status :ok :latency-ms 1})
  (metrics/record-query! {:source :api :op :query :status :ok :latency-ms 2})
  ;; record-query! stamps :ts = now; ts-span is ~0 within a test, so likely [].
  ;; Just assert it returns a vector and never throws.
  (is (vector? (metrics/series {:buckets 4}))))

(deftest series-source-filter
  ;; Seed ring with mixed sources; series with :source restricts records.
  (metrics/record-query! {:source :api :op :query :status :ok :latency-ms 1})
  (Thread/sleep 2)
  (metrics/record-query! {:source :admin :op :query :status :ok :latency-ms 1})
  (Thread/sleep 2)
  (metrics/record-query! {:source :api :op :query :status :ok :latency-ms 9})
  ;; api has 2 records spanning ~4ms -> non-empty; counts sum to 2
  (let [s (metrics/series {:buckets 4 :source :api})]
    (is (or (= [] s)                                  ; tolerate clock granularity
            (= 2 (reduce + (map :count s)))))))
