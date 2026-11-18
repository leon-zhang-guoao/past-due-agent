(ns open.atlas.runtime.obs.impl.metrics
  "In-process observability state: per-source counters, latency buffers,
   and a recent-queries ring buffer. All operations fail-open — recording
   must never break the query path. State resets on restart by design."
  (:require [clojure.tools.logging :as log]
            [open.atlas.runtime.obs.impl.otlp :as otlp]))

(def ^:private recent-queries-max 100)
(def ^:private latency-buffer-max 500)

(defonce ^:private started-at* (atom (System/currentTimeMillis)))
(defonce ^:private counters*   (atom {}))  ; {source {:queries n :mutations n :errors n}}
(defonce ^:private latencies*  (atom {}))  ; {source [ms ...]} bounded vector
(defonce ^:private recent*     (atom []))  ; bounded vector of records, newest LAST
(defonce ^:private rejections* (atom {}))  ; {:rate-limited n :unauthorized n}
(defonce ^:private agent*      (atom {:runs 0 :rounds 0 :statuses {}
                                      :tokens {:input 0 :output 0 :cache-read 0}}))

(defn reset-all!
  "Reset all metrics state. For tests and REPL."
  []
  (reset! counters* {})
  (reset! latencies* {})
  (reset! recent* [])
  (reset! rejections* {})
  (reset! agent* {:runs 0 :rounds 0 :statuses {}
                  :tokens {:input 0 :output 0 :cache-read 0}})
  (reset! started-at* (System/currentTimeMillis)))

(defn uptime-ms
  "Milliseconds since process start (or last reset-all!). In-memory; no I/O."
  []
  (- (System/currentTimeMillis) @started-at*))

(defn- conj-bounded [v x max-n]
  (let [v (conj (or v []) x)]
    (if (> (count v) max-n)
      (subvec v (- (count v) max-n))
      v)))

(defn record-query!
  "Record a completed query or mutation. Never throws.
   Expected keys: :request-id :source (:api :mcp :admin) :op (:query :mutation)
                  :status (:ok :error) :latency-ms :eql-shape :resolver-trace :error"
  [{:keys [source status latency-ms op] :as m}]
  (try
    (let [rec (assoc m :ts (java.util.Date.))]
      (swap! counters* update source
             (fn [c]
               (-> (or c {:queries 0 :mutations 0 :errors 0})
                   (update (if (= op :mutation) :mutations :queries) inc)
                   (cond-> (= status :error) (update :errors inc)))))
      (when latency-ms
        (swap! latencies* update source conj-bounded latency-ms latency-buffer-max))
      (swap! recent* conj-bounded rec recent-queries-max)
      (otlp/offer-query! m)     ; no-op unless :otlp is configured
      nil)
    (catch Exception e
      (log/warn "metrics/record-query! partial write (ignored):" (.getMessage e))
      nil)))

(declare ^:private percentile)

(defn record-rejection!
  "Count one rejected request: :rate-limited (429) or :unauthorized (401).
   Called from the audit choke points so every surface is covered. Never throws."
  [kind]
  (try (swap! rejections* update kind (fnil inc 0)) nil
       (catch Exception _ nil)))

(defn rejections
  "Current rejection counters: {:rate-limited n :unauthorized n}."
  []
  (merge {:rate-limited 0 :unauthorized 0} @rejections*))

(defn record-agent-run!
  "Record one completed agent task from its result map ({:status :usage :timing}).
   Steps = LLM round-trips (:timing :rounds). Never throws."
  [{:keys [status usage timing]}]
  (try
    (swap! agent*
           (fn [a]
             (-> a
                 (update :runs inc)
                 (update :rounds + (long (or (:rounds timing) 0)))
                 (update-in [:statuses (or status :unknown)] (fnil inc 0))
                 (update-in [:tokens :input] + (long (or (:input usage) 0)))
                 (update-in [:tokens :output] + (long (or (:output usage) 0)))
                 (update-in [:tokens :cache-read] + (long (or (:cache-read usage) 0))))))
    (otlp/offer-agent! {:status status :usage usage :timing timing})
    nil
    (catch Exception e
      (log/warn "metrics/record-agent-run! ignored:" (.getMessage e))
      nil)))

(defn agent-stats
  "Cumulative agent counters: {:runs :rounds :statuses {status n} :tokens {...}}."
  []
  @agent*)

(defn latency-stats
  "Per-source latency snapshot for exporters: {source {:count n :sum ms
   :p50 :p95 :p99}}. Percentiles come from the bounded buffer (last 500)."
  []
  (into {}
        (map (fn [[src v]]
               (let [sorted (vec (sort v))]
                 [src {:count (count v)
                       :sum   (reduce + 0 v)
                       :p50   (percentile sorted 0.50)
                       :p95   (percentile sorted 0.95)
                       :p99   (percentile sorted 0.99)}])))
        @latencies*))

(defn counter-snapshot
  "Raw per-source counters {source {:queries :mutations :errors}} for exporters."
  []
  @counters*)

;; Floor-rank method: accurate at n>=100; at small n, p95 can return lower
;; than expected. Acceptable given latency-buffer-max of 500.
(defn- percentile [sorted-v p]
  (when (seq sorted-v)
    (nth sorted-v (int (Math/floor (* p (dec (count sorted-v))))))))

(defn snapshot
  "Point-in-time metrics for the admin console."
  []
  (let [counters @counters*
        lats     @latencies*
        sources  (into {}
                       (map (fn [[src c]]
                              (let [sorted (vec (sort (get lats src [])))]
                                [src (assoc c
                                            :p50 (percentile sorted 0.50)
                                            :p95 (percentile sorted 0.95)
                                            :max (last sorted))]))
                            counters))]
    {:uptime-ms (- (System/currentTimeMillis) @started-at*)
     :sources   sources
     :totals    {:queries   (reduce + 0 (map :queries (vals counters)))
                 :mutations (reduce + 0 (map :mutations (vals counters)))
                 :errors    (reduce + 0 (map :errors (vals counters)))}}))

(defn recent-queries
  "Recent query records, newest first. Optional {:source :admin} filter."
  ([] (recent-queries nil))
  ([{:keys [source]}]
   (cond->> (reverse @recent*)
     source (filter #(= source (:source %)))
     true   vec)))

(defn- series-from
  "Pure bucketing. Given a seq of query records (order-independent) and a bucket
   count, return a vector (oldest→newest) of {:count n :p95 ms-or-nil}, one entry
   per bucket (length == buckets). Records are bucketed over their [min-ts,max-ts]
   span via (.getTime :ts); the last bucket is inclusive of max-ts. Fewer than 2
   records or a zero ts-span → []."
  [records buckets]
  (let [recs    (vec records)
        n       (count recs)
        ts-of   #(.getTime ^java.util.Date (:ts %))]
    (if (< n 2)
      []
      (let [tss     (map ts-of recs)
            min-ts  (apply min tss)
            max-ts  (apply max tss)
            span    (- max-ts min-ts)]
        (if (zero? span)
          []
          (let [width  (/ (double span) buckets)
                bucket (fn [t]
                         (min (dec buckets)                       ; max-ts → last bucket
                              (int (Math/floor (/ (- t min-ts) width)))))
                groups (group-by #(bucket (ts-of %)) recs)]
            (mapv (fn [i]
                    (let [in-bucket (get groups i [])
                          lats      (sort (keep :latency-ms in-bucket))]
                      {:count (count in-bucket)
                       :p95   (percentile (vec lats) 0.95)}))
                  (range buckets))))))))

(defn series
  "Derive a sparkline series from the recent-query ring. Buckets recent records
   over their [min-ts,max-ts] span into `buckets` equal slots (default 20),
   returning a vector (oldest→newest) of {:count n :p95 ms-or-nil}, length ==
   buckets. Optional :source filter. <2 records or zero ts-span → []."
  ([] (series {}))
  ([{:keys [buckets source] :or {buckets 20}}]
   (series-from (recent-queries {:source source}) buckets)))

(defn query-by-request-id [request-id]
  (when request-id
    (first (filter #(= request-id (:request-id %)) (recent-queries)))))
