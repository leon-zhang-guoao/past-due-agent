(ns open.atlas.server.mcp.budget-test
  "The analysis budget: one end-to-end deadline, a concurrency permit, and byte
   guardrails measured separately (doc 108 §108.5 items 5 and 6, ruled §108.6;
   the byte guardrails themselves are §108.4).

   Every refusal here is asserted TWICE: once for the structured code and the
   stage it names, and once for the permit count afterwards. A refusal that
   leaks a permit costs the server a quarter of its analysis capacity and says
   nothing while doing it, which is the failure mode that outlives the bug."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.server.mcp.impl.duck :as duck]
            [open.atlas.server.mcp.impl.results :as results]
            [open.atlas.server.mcp.impl.rows :as rows]
            [open.atlas.server.mcp.impl.tools :as tools])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- call [ctx tool args]
  ((:handler (first (filter #(= tool (:name %)) tools/declarations))) ctx args))

(defn- ctx-of
  ([data] (ctx-of data (fn [] nil)))
  ([data before]
   {:credential "owner-a"
    :runtime {:pinned (fn [_ _] {:graph-id "g" :version 1})
              :answer (fn [_ _ _] (before) {:engine/data data :env/version 1})}}))

(defn- rows-of [n]
  {:a/all (mapv (fn [i] {:a/id i :a/g (str "g" (mod i 7))}) (range n))})

(def ^:private real-start-deadline
  "Captured before any `with-redefs` below: a stub written as
   `#(duck/start-deadline 60)` would call the STUB."
  duck/start-deadline)

(defn- budget-of [ms] (fn [& _] (real-start-deadline ms)))

(def ^:private expired
  (fn [& _] (let [now (System/currentTimeMillis)]
              {:limit 30000 :started (- now 40000) :at (- now 10000)})))

(defn- refusal-code [r] (:code (:refusal r)))

(defn- permits-restored? []
  ;; The permit is released in a `finally`, but the releasing thread may be
  ;; a hair behind the assertion; give it a bounded moment rather than a sleep.
  (loop [tries 0]
    (cond (= duck/max-concurrent (duck/available-permits)) true
          (> tries 100) false
          :else (do (Thread/sleep 5) (recur (inc tries))))))

;; ---------------------------------------------------------------------------
;; The end-to-end deadline, stage by stage
;; ---------------------------------------------------------------------------

(deftest the-deadline-names-the-stage-that-ran-out
  (testing "authorization — the clock covers pinning, not just the SQL"
    (with-redefs [duck/start-deadline expired]
      (let [r (call (ctx-of (rows-of 3)) "analyze_result"
                    {:query "[{:a/all [:a/id]}]" :sql "SELECT count(*) FROM data"})]
        (is (= :analysis.limit/deadline (refusal-code r)))
        (is (= :authorization (:stage (:refusal r))))
        (is (= 30000 (:max (:refusal r))))
        (is (nil? (:rows r)) "no partial aggregate")
        (is (permits-restored?)))))

  (testing "fetch — a slow source spends the analysis budget like any stage"
    (with-redefs [duck/start-deadline (budget-of 60)]
      (let [r (call (ctx-of (rows-of 3) #(Thread/sleep 600)) "analyze_result"
                    {:query "[{:a/all [:a/id]}]" :sql "SELECT count(*) FROM data"})]
        (is (= :analysis.limit/deadline (refusal-code r)))
        (is (= :fetch (:stage (:refusal r))))
        (is (permits-restored?)))))

  (testing "handle-read — reading a retained result is inside the deadline too"
    (with-redefs [duck/start-deadline (budget-of 60)
                  results/fetch! (fn [_ _] (Thread/sleep 600) {:result (rows-of 3)})]
      (let [r (call (ctx-of (rows-of 3)) "analyze_result"
                    {:result_handle "result_x" :sql "SELECT count(*) FROM data"})]
        (is (= :analysis.limit/deadline (refusal-code r)))
        (is (= :handle-read (:stage (:refusal r))))
        (is (permits-restored?)))))

  (testing "flatten — a wide Cartesian expansion is where the time actually goes,
            so the deadline has to reach inside it and not merely after it"
    (let [wide (into {} (map (fn [i] [(keyword "a" (str "k" i)) (str "v" i)])) (range 40))
          data {:a/all (mapv (fn [i] (assoc wide :a/id i)) (range 200000))}]
      (with-redefs [duck/start-deadline (budget-of 15)]
        (let [r (call (ctx-of data) "analyze_result"
                      {:query "[{:a/all [:a/id]}]" :sql "SELECT count(*) FROM data"})]
          (is (= :analysis.limit/deadline (refusal-code r)))
          (is (= :flatten (:stage (:refusal r))))
          (is (permits-restored?))))))

  (testing "sql — the watchdog fires at min(ATLAS_ANALYSIS_TIMEOUT_MS, remaining),
            and a cancel caused by the deadline is reported as the deadline, not
            as a DuckDB complaint the agent would try to rewrite its way out of"
    (with-redefs [duck/start-deadline (budget-of 200)]
      (let [r (call (ctx-of (rows-of 5)) "analyze_result"
                    {:query "[{:a/all [:a/id]}]"
                     :sql (str "WITH RECURSIVE t(i) AS ("
                               "SELECT 1 UNION ALL SELECT i+1 FROM t WHERE i < 400000000)"
                               " SELECT count(*) AS n FROM t")})]
        (is (= :analysis.limit/deadline (refusal-code r)) (pr-str r))
        (is (= :sql (:stage (:refusal r))))
        (is (nil? (:rows r)))
        (is (permits-restored?))))))

(deftest a-healthy-call-leaves-the-permits-where-it-found-them
  (let [r (call (ctx-of (rows-of 10)) "analyze_result"
                {:query "[{:a/all [:a/id]}]" :sql "SELECT count(*) AS n FROM data"})]
    (is (= [["10"]] (:rows r)))
    (is (permits-restored?))))

;; ---------------------------------------------------------------------------
;; Concurrency and the total resource budget
;; ---------------------------------------------------------------------------

(deftest the-n-plus-first-analysis-is-refused-not-queued-forever
  (let [started (CountDownLatch. duck/max-concurrent)
        hold (CountDownLatch. 1)
        ctx (ctx-of (rows-of 3) (fn [] (.countDown started) (.await hold 5 TimeUnit/SECONDS)))
        busy (doall (for [_ (range duck/max-concurrent)]
                      (future (call ctx "analyze_result"
                                    {:query "[{:a/all [:a/id]}]"
                                     :sql "SELECT count(*) FROM data"}))))]
    (try
      (is (.await started 5 TimeUnit/SECONDS) "all permits are held")
      (with-redefs [duck/max-wait-ms 50]
        (let [r (call ctx "analyze_result"
                      {:query "[{:a/all [:a/id]}]" :sql "SELECT count(*) FROM data"})]
          (is (= :analysis.limit/concurrency (refusal-code r)))
          (is (= duck/max-concurrent (:max (:refusal r))))
          (is (nil? (:rows r)) "nothing was computed")))
      (finally
        (.countDown hold)
        (doseq [f busy] @f)))
    (is (permits-restored?))))

(deftest the-total-in-flight-input-budget-is-separate-from-the-per-call-one
  "`memory_limit` is set per CONNECTION, so it cannot see the other three
   analyses running beside this one. Four calls each half the per-call cap are
   each legal and together are not."
  (with-redefs [duck/total-input-bytes 8]
    (let [r (call (ctx-of (rows-of 50)) "analyze_result"
                  {:query "[{:a/all [:a/id]}]" :sql "SELECT count(*) FROM data"})]
      (is (= :analysis.limit/total-bytes (refusal-code r)))
      (is (= 8 (:max (:refusal r))))
      (is (nil? (:rows r)))))
  (is (zero? (duck/in-flight-input-bytes)) "the reservation is given back")
  (is (permits-restored?)))

;; ---------------------------------------------------------------------------
;; Byte guardrails, measured separately (§108.4)
;; ---------------------------------------------------------------------------

(deftest an-over-large-response-is-refused-rather-than-cut-to-fit
  "A response trimmed to fit is indistinguishable from a complete one, and the
   caller would quote it."
  (with-redefs [duck/max-output-bytes 200]
    (let [r (call (ctx-of (rows-of 100)) "analyze_result"
                  {:query "[{:a/all [:a/id]}]"
                   :sql "SELECT repeat('x', 400) AS wide, id FROM data"})]
      (is (= :analysis.limit/output-bytes (refusal-code r)))
      (is (= 200 (:max (:refusal r))))
      (is (> (:actual (:refusal r)) 200))
      (is (nil? (:rows r)) "not one partial row")
      (is (not (str/includes? (:error r) "x")) "and no business value in the message")))
  (is (permits-restored?)))

(deftest display-truncation-is-labelled-evidence-not-a-silent-cut
  "Distinct from the INPUT limits above, which refuse. The row cap still applies
   to what the model reads, but the answer says so and says how many rows the
   SQL really produced."
  (let [r (call (ctx-of (rows-of 300)) "analyze_result"
                {:query "[{:a/all [:a/id]}]" :sql "SELECT id FROM data ORDER BY id"})]
    (is (= {:rows? true :cells? false :rows-shown duck/max-rows :rows-total 300}
           (:truncation r)))
    (is (= duck/max-rows (count (:rows r))))
    (is (= 300 (:row-count r)) "the input row count is its own number")
    (is (true? (:truncated? r))))
  (testing "and discovery reports the untouched row count"
    (is (= 300 (:row-count (duck/describe-rows (rows/flatten-result (rows-of 300))))))
    (is (= 300 (:row-count (call (ctx-of (rows-of 300)) "analyze_result"
                                 {:query "[{:a/all [:a/id]}]"}))))))

;; ---------------------------------------------------------------------------
;; Ruled defaults and the statement filter
;; ---------------------------------------------------------------------------

(deftest the-default-input-rows-is-the-maximum
  "Doc 108 §108.5 item 5, ruled §108.6. A lower default only bought a second full fetch of the same
   data with `input_row_limit` set."
  (is (= duck/input-max-rows duck/input-default-rows))
  (is (= duck/input-max-rows (duck/input-budget nil)))
  (is (nil? (duck/input-budget (inc duck/input-max-rows))) "above the maximum is refused"))

(deftest the-statement-filter-matches-leading-keywords-only
  "`enable_external_access=false` is the real guard. The word-boundary pattern
   also refused `SELECT load, copy FROM data` — ordinary column names in a model
   that has them — with a message about ATTACH, teaching the agent to write
   around a rule that does not exist."
  (testing "columns named load and copy are ordinary columns"
    (is (not (re-find duck/forbidden "SELECT load, copy FROM data")))
    (is (= [["1" "2"]]
           (:rows (duck/query-rows [{:a/load 1 :a/copy 2}] "SELECT load, copy FROM data"))))
    (let [r (call (ctx-of {:a/all [{:a/load 1 :a/copy 2}]}) "analyze_result"
                  {:query "[{:a/all [:a/load :a/copy]}]" :sql "SELECT load, copy FROM data"})]
      (is (= [["1" "2"]] (:rows r)) (pr-str r))))
  (testing "and a statement that really does lead with one is still refused"
    (doseq [s ["ATTACH 'x.db'" "  attach 'x.db'" "INSTALL httpfs" "COPY data TO '/tmp/x.csv'"
               "EXPORT DATABASE '/tmp/d'" "IMPORT_DATABASE '/tmp/d'"]]
      (is (re-find duck/forbidden s) (str "should be refused up front: " s)))
    (let [r (call (ctx-of (rows-of 3)) "analyze_result"
                  {:query "[{:a/all [:a/id]}]" :sql "ATTACH 'x.db'"})]
      (is (str/includes? (:error r) "refused"))
      (is (nil? (:rows r))))))
