(ns open.atlas.server.mcp.materialize-test
  "Per-handle materialization (doc 108 §108.2 first paragraph, §108.5 item 1,
   ruled §108.6) — and item 7, ruled NO: nothing survives a process restart.

   The cache is an internal, bounded, read-only compute cache. Every assertion
   here is either «the same question is not paid for twice» or «the cache never
   outlives, out-permissions, or half-answers the handle it came from»."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.server.mcp.impl.duck :as duck]
            [open.atlas.server.mcp.impl.results :as results]
            [open.atlas.server.mcp.impl.tools :as tools])
  (:import [java.nio.file Files Path]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- call [ctx tool args]
  ((:handler (first (filter #(= tool (:name %)) tools/declarations))) ctx args))

(def ^:private rows
  (mapv (fn [i] {:a/id i :a/g (str "g" (mod i 5)) :a/amount (bigdec (str i ".25"))})
        (range 40)))

(defn- ctx-of [calls]
  {:credential "owner-a"
   :runtime {:pinned (fn [_ _] {:result-owner "owner-a" :graph-id "g" :version 1
                                :catalog {:attributes {:a/amount {:entity "a" :type :decimal}}}})
             :answer (fn [_ _ _] (swap! calls inc)
                       {:engine/data {:a/all rows} :env/version 1})}})

(defn- handle! [ctx]
  (:result_handle (call ctx "execute_eql" {:query "[{:a/all [:a/id]}]" :retain_result true})))

(defn- db-files [store]
  (with-open [s (Files/list ^Path (:directory store))]
    (->> (.toList s) (map #(str (.getFileName ^Path %)))
         (filter #(str/includes? % "mat-")) vec)))

(defmacro with-store [[sym] & body]
  `(let [~sym (results/create-store)]
     (binding [results/*store* ~sym]
       (try ~@body (finally (results/close! ~sym))))))

;; ---------------------------------------------------------------------------
;; Build once, read many
;; ---------------------------------------------------------------------------

(deftest three-questions-over-one-handle-build-the-table-once
  (with-store [store]
    (let [calls (atom 0) builds (atom 0)
          ctx (ctx-of calls)
          real duck/materialize!]
      (with-redefs [duck/materialize! (fn [& a] (swap! builds inc) (apply real a))]
        (let [h (handle! ctx)
              ask (fn [sql] (call ctx "analyze_result" {:result_handle h :sql sql}))
              first* (ask "SELECT count(*) AS n FROM data")
              second* (ask "SELECT g, count(*) AS n FROM data GROUP BY g ORDER BY g")
              third* (ask "SELECT sum(amount) AS total FROM data")]
          (is (= 1 @calls) "the source is read once, for the handle itself")
          (is (= 1 @builds) "and the table is built once, not per SQL")

          (testing "the first call pays for flatten and load, and says it did"
            (is (false? (:materialized (:analysis first*))))
            (is (contains? (:stages (:analysis first*)) :flatten)))

          (testing "the later calls take the fast path and the evidence shows it"
            (doseq [r [second* third*]]
              (is (true? (:materialized (:analysis r))) (pr-str r))
              (is (not (contains? (:stages (:analysis r)) :flatten))
                  "flatten is not merely fast — it does not happen")
              (is (contains? (:stages (:analysis r)) :sql))))

          (testing "and the answers are the ones the in-memory path gives"
            (is (= [["40"]] (:rows first*)))
            (is (= 5 (count (:rows second*))))
            (is (= [["790.00"]] (:rows third*))
                "the declared DECIMAL survives into the materialized table")))))))

(deftest discovery-over-a-materialized-handle-answers-from-the-sidecar
  "§108.3's «发现接口与 SQL 使用同一 schema» has to hold on BOTH paths, or an
   agent that discovered before the build and planned after it is planning
   against a schema nobody serves."
  (with-store [_store]
    (let [ctx (ctx-of (atom 0))
          h (handle! ctx)
          before (call ctx "analyze_result" {:result_handle h})
          _ (call ctx "analyze_result" {:result_handle h :sql "SELECT 1 AS x FROM data LIMIT 1"})
          after (call ctx "analyze_result" {:result_handle h})]
      (is (false? (:materialized (:analysis before))))
      (is (true? (:materialized (:analysis after))))
      (is (= (:columns before) (:columns after)) "same columns, types and type-sources")
      (is (= (:row-count before) (:row-count after)))
      (is (= :declared (:type-source (first (filter #(= "amount" (:name %)) (:columns after)))))))))

(deftest a-different-row-budget-is-a-different-table
  "Reusing a 40-row table to answer a 10-row question would answer a narrower
   question with a wider table's rows."
  (with-store [_store]
    (let [builds (atom 0) real duck/materialize!
          ctx (ctx-of (atom 0))]
      (with-redefs [duck/materialize! (fn [& a] (swap! builds inc) (apply real a))]
        (let [h (handle! ctx)]
          (call ctx "analyze_result" {:result_handle h :sql "SELECT count(*) FROM data"})
          ;; Both budgets admit all 40 rows; what differs is the KEY, and a
          ;; table built for one budget must not answer for the other.
          (call ctx "analyze_result" {:result_handle h :sql "SELECT count(*) FROM data"
                                      :input_row_limit 100})
          (is (= 2 @builds))
          (testing "and each key answers its own question"
            (is (= [["40"]] (:rows (call ctx "analyze_result"
                                         {:result_handle h :sql "SELECT count(*) AS n FROM data"}))))
            (let [narrow (call ctx "analyze_result"
                               {:result_handle h :sql "SELECT count(*) AS n FROM data"
                                :input_row_limit 100})]
              (is (= [["40"]] (:rows narrow)))
              (is (true? (:materialized (:analysis narrow)))
                  "its own table, hit on the second call")
              (is (= 100 (:input-row-limit (:analysis narrow)))))))))))

(deftest the-analysis-version-is-part-of-the-key
  (with-store [_store]
    (let [builds (atom 0) real duck/materialize!
          ctx (ctx-of (atom 0))]
      (with-redefs [duck/materialize! (fn [& a] (swap! builds inc) (apply real a))]
        (let [h (handle! ctx)]
          (call ctx "analyze_result" {:result_handle h :sql "SELECT sum(amount) AS t FROM data"})
          (let [v2 (call ctx "analyze_result" {:result_handle h :analysis_version 2
                                               :sql "SELECT sum(amount) AS t FROM data"})]
            (is (= 2 @builds))
            (is (= [["790.00"]] (:rows v2)) "an exact decimal string in version 2")))))))

;; ---------------------------------------------------------------------------
;; The cache never outlives the handle
;; ---------------------------------------------------------------------------

(deftest an-expired-handle-takes-its-materialization-with-it
  (with-store [store]
    (let [ctx (ctx-of (atom 0))
          h (handle! ctx)]
      (call ctx "analyze_result" {:result_handle h :sql "SELECT count(*) FROM data"})
      (is (seq (db-files store)) "premise: a file exists")
      ;; Expire it the way time would.
      (swap! (:entries store) update h assoc :expires-at (- (results/now-ms) 1))
      (let [r (call ctx "analyze_result" {:result_handle h :sql "SELECT count(*) FROM data"})]
        (is (str/includes? (:error r) "handle"))
        (is (nil? (:rows r))))
      (is (empty? (db-files store)) "the file is swept with the handle, not left behind"))))

(deftest a-cache-hit-does-not-extend-the-handles-life
  "§108.2 「缓存命中不续期」: a sliding TTL would let a busy handle live for ever,
   which is exactly the long-term snapshot a materialization must not become."
  (with-store [store]
    (let [ctx (ctx-of (atom 0))
          h (handle! ctx)
          _ (call ctx "analyze_result" {:result_handle h :sql "SELECT count(*) FROM data"})
          expiry (:expires-at (get @(:entries store) h))]
      (call ctx "analyze_result" {:result_handle h :sql "SELECT count(*) FROM data"})
      (call ctx "analyze_result" {:result_handle h :sql "SELECT count(*) FROM data"})
      (is (= expiry (:expires-at (get @(:entries store) h)))))))

(deftest closing-the-store-removes-every-file
  (let [store (results/create-store)
        dir (:directory store)]
    (binding [results/*store* store]
      (let [ctx (ctx-of (atom 0))
            h (handle! ctx)]
        (call ctx "analyze_result" {:result_handle h :sql "SELECT count(*) FROM data"})
        (is (seq (db-files store)))))
    (results/close! store)
    (is (not (Files/exists ^Path dir (make-array java.nio.file.LinkOption 0)))
        "directory and everything under it")))

(deftest nothing-survives-a-restart
  "Doc 108 §108.5 item 7, ruled NO in §108.6. A fresh store is a fresh temp
   directory: there is no path a later process could reopen, by accident or
   otherwise, so a materialization cannot outlive the process that made it."
  (let [first-store (results/create-store)
        first-dir (:directory first-store)]
    (binding [results/*store* first-store]
      (let [ctx (ctx-of (atom 0))
            h (handle! ctx)]
        (call ctx "analyze_result" {:result_handle h :sql "SELECT count(*) FROM data"})
        (results/close! first-store)
        ;; The "restart".
        (with-store [second-store]
          (is (not= (str first-dir) (str (:directory second-store))) "no path reuse")
          (is (empty? (db-files second-store)))
          (is (empty? @(:entries second-store)))
          (is (nil? (results/materialized "owner-a" h "any-key"))
              "the old handle resolves to nothing at all")
          (let [r (call (ctx-of (atom 0)) "analyze_result"
                        {:result_handle h :sql "SELECT count(*) FROM data"})]
            (is (str/includes? (:error r) "handle"))))))))

;; ---------------------------------------------------------------------------
;; Failure leaves nothing readable, and says so
;; ---------------------------------------------------------------------------

(deftest a-failed-build-leaves-no-half-file-and-falls-back-explicitly
  (with-store [store]
    (let [ctx (ctx-of (atom 0))
          h (handle! ctx)
          r (with-redefs [duck/materialize!
                          (fn [& _] (throw (RuntimeException. "disk went away")))]
              (call ctx "analyze_result" {:result_handle h :sql "SELECT count(*) AS n FROM data"}))]
      (is (= [["40"]] (:rows r)) "the ANALYSIS still answers — the cache is an optimisation")
      (is (false? (:materialized (:analysis r))))
      (is (= :analysis.materialize/failed (:materialize-refused (:analysis r)))
          "and the fallback is stated, not left as a mysterious slow call")
      (is (empty? (db-files store)) "no readable half-file for the next caller to trust")
      (testing "the next call is free to try again"
        (let [again (call ctx "analyze_result" {:result_handle h :sql "SELECT count(*) FROM data"})]
          (is (nil? (:materialize-refused (:analysis again))))
          (is (seq (db-files store))))))))

(deftest a-materialization-that-would-blow-the-store-budget-is-refused-not-squeezed-in
  (with-store [store]
    (let [ctx (ctx-of (atom 0))
          h (handle! ctx)
          r (with-redefs [results/total-bytes 1]
              (call ctx "analyze_result" {:result_handle h :sql "SELECT count(*) AS n FROM data"}))]
      (is (= [["40"]] (:rows r)))
      (is (false? (:materialized (:analysis r))))
      (is (= :analysis.limit/total-bytes (:materialize-refused (:analysis r))))
      (is (empty? (db-files store))))))

(deftest concurrent-first-builds-coalesce-into-one
  (with-store [_store]
    (let [ctx (ctx-of (atom 0))
          h (handle! ctx)
          builds (atom 0)
          entered (CountDownLatch. 1)
          release (CountDownLatch. 1)
          real duck/materialize!]
      (with-redefs [duck/materialize!
                    (fn [& a]
                      (swap! builds inc)
                      (.countDown entered)
                      (.await release 5 TimeUnit/SECONDS)
                      (apply real a))]
        (let [a (future (call ctx "analyze_result"
                              {:result_handle h :sql "SELECT count(*) AS n FROM data"}))]
          (is (.await entered 5 TimeUnit/SECONDS) "the first build is in flight")
          (let [b (future (call ctx "analyze_result"
                                {:result_handle h :sql "SELECT count(*) AS n FROM data"}))]
            (.countDown release)
            (is (= [["40"]] (:rows @a)))
            (is (= [["40"]] (:rows @b)))
            (is (= 1 @builds) "the second caller waited for the first, it did not build again")))))))

;; ---------------------------------------------------------------------------
;; Read-only
;; ---------------------------------------------------------------------------

(deftest the-materialized-table-cannot-be-written-to
  "A cache the model can write into is a place to put things, and the next
   reader of that handle would be reading the model's edits as data."
  (with-store [_store]
    (let [ctx (ctx-of (atom 0))
          h (handle! ctx)]
      (call ctx "analyze_result" {:result_handle h :sql "SELECT count(*) FROM data"})
      (doseq [sql ["INSERT INTO data VALUES (999, 'x', 1.0)"
                   "CREATE TABLE sneaky (x INTEGER)"
                   "UPDATE data SET g = 'x'"
                   "DELETE FROM data"]]
        (let [r (call ctx "analyze_result" {:result_handle h :sql sql})]
          (is (some? (:error r)) (str "should be refused: " sql))
          (is (nil? (:rows r)))))
      (testing "and the table is intact afterwards"
        (is (= [["40"]] (:rows (call ctx "analyze_result"
                                     {:result_handle h :sql "SELECT count(*) AS n FROM data"}))))))))
