(ns open.atlas.server.mcp.handles-test
  "Several handles in one analysis (doc 108 §108.2 second paragraph, §108.5
   item 2, ruled §108.6 — initial version: one identity, one environment, one
   graph version; cross-graph is the named next step).

   Two themes run through these tests. One: an input the caller may not read,
   or cannot be read any more, refuses the WHOLE call by position — half an
   answer over half the inputs is indistinguishable from a whole one. Two: the
   combination never invents completeness it was not given."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.server.mcp.impl.duck :as duck]
            [open.atlas.server.mcp.impl.results :as results]
            [open.atlas.server.mcp.impl.tools :as tools]))

(defn- call [ctx tool args]
  ((:handler (first (filter #(= tool (:name %)) tools/declarations))) ctx args))

(defn- ctx-of
  "A runtime whose `answer` returns whatever `data` currently holds, so two
   handles can be minted from two different result trees."
  [data & [version]]
  {:credential "owner-a"
   :runtime {:pinned (fn [_ _] {:result-owner "owner-a" :graph-id "g" :version (or @(or version (atom 1)) 1)
                                :catalog {:attributes {:a/amount {:entity "a" :type :decimal}}}})
             :answer (fn [_ _ _] {:engine/data @data :env/version 1})}})

(defn- handle! [ctx]
  (:result_handle (call ctx "execute_eql" {:query "[{:a/all [:a/id]}]" :retain_result true})))

(defmacro with-store [[sym] & body]
  `(let [~sym (results/create-store)]
     (binding [results/*store* ~sym]
       (try ~@body (finally (results/close! ~sym))))))

(defn- two-handles
  "Two handles minted by the same caller, graph and version."
  [ctx data a b]
  (reset! data a)
  (let [h1 (handle! ctx)]
    (reset! data b)
    [h1 (handle! ctx)]))

(def ^:private orders
  {:a/all [{:a/id 1 :a/region "north" :a/amount 10.50M}
           {:a/id 2 :a/region "south" :a/amount 5.25M}]})

(def ^:private invoices
  {:a/all [{:a/id 1 :a/paid true} {:a/id 2 :a/paid false} {:a/id 3 :a/paid true}]})

;; ---------------------------------------------------------------------------
;; Union and join
;; ---------------------------------------------------------------------------

(deftest two-handles-union-into-data-with-null-fill-and-an-ordinal
  (with-store [_s]
    (let [data (atom nil) ctx (ctx-of data)
          [h1 h2] (two-handles ctx data orders invoices)
          r (call ctx "analyze_result"
                  {:result_handles [h1 h2]
                   :sql "SELECT _atlas_handle, id, region, paid FROM data ORDER BY _atlas_handle, id"})]
      (is (= 5 (count (:rows r))) "row counts ADD — the union keeps every row")
      (is (= ["1" "1" "north" nil] (first (:rows r)))
          "a column the other handle does not have is NULL, not an error")
      (is (= ["2" "3" nil "true"] (last (:rows r))))
      (is (= #{"1" "2"} (set (map first (:rows r)))) "1-based ordinals, one per handle")
      (is (= 5 (:row-count r)))
      (testing "duplicates are KEPT — deduplication needs a business key nobody guessed"
        (let [d (call ctx "analyze_result"
                      {:result_handles [h1 h2] :sql "SELECT count(*) AS n, count(DISTINCT id) AS ids FROM data"})]
          (is (= [["5" "3"]] (:rows d))))))))

(deftest a-keyed-join-across-two-handles
  (with-store [_s]
    (let [data (atom nil) ctx (ctx-of data)
          [h1 h2] (two-handles ctx data orders invoices)
          r (call ctx "analyze_result"
                  {:result_handles [h1 h2]
                   :sql (str "SELECT h1.region, h2.paid FROM h1 JOIN h2 ON h1.id = h2.id "
                             "ORDER BY h1.region")})]
      (is (= [["north" "true"] ["south" "false"]] (:rows r)) (pr-str r))
      (testing "and the handles are also addressable one by one"
        (is (= [["2"]] (:rows (call ctx "analyze_result"
                                    {:result_handles [h1 h2] :sql "SELECT count(*) AS n FROM h1"}))))))))

(deftest numeric-types-widen-across-handles-but-text-beside-a-number-does-not
  (with-store [_s]
    (let [data (atom nil) ctx (ctx-of data)]
      (testing "BIGINT in one handle and DECIMAL in the other widen to DECIMAL"
        (let [[h1 h2] (two-handles ctx data
                                   {:a/all [{:a/id 1 :a/amount 10}]}
                                   {:a/all [{:a/id 2 :a/amount 2.50M}]})
              r (call ctx "analyze_result"
                      {:result_handles [h1 h2] :sql "SELECT sum(amount) AS total FROM data"})]
          (is (= [["12.50"]] (:rows r)) (pr-str r))))

      (testing "text where the graph declared a number is refused at that handle's
                position — the declaration cannot make \"n/a\" into an amount, and
                both ways of papering over it (coerce, drop) return a number that
                is not the one in the source"
        (let [[h1 h2] (two-handles ctx data
                                   {:a/all [{:a/id 1 :a/amount 10}]}
                                   {:a/all [{:a/id 2 :a/amount "n/a"}]})
              r (call ctx "analyze_result"
                     {:result_handles [h1 h2] :sql "SELECT sum(amount) FROM data"})]
          (is (= :analysis.type/mixed (:code (:refusal r))))
          (is (= "amount" (:column (:refusal r))))
          (is (= [2] (:positions (:refusal r))) "the handle that contradicts the declaration")
          (is (nil? (:rows r)))
          (is (not (str/includes? (:error r) "n/a")) "no value in the message")))

      (testing "and for an UNDECLARED column the conflict is between the handles
                themselves, so both positions are named"
        (let [[h1 h2] (two-handles ctx data
                                   {:a/all [{:a/id 1 :a/note 10}]}
                                   {:a/all [{:a/id 2 :a/note "n/a"}]})
              r (call ctx "analyze_result"
                      {:result_handles [h1 h2] :sql "SELECT count(*) FROM data"})]
          (is (= :analysis.type/mixed (:code (:refusal r))))
          (is (= "note" (:column (:refusal r))))
          (is (= [1 2] (:positions (:refusal r))))
          (is (nil? (:rows r)))))

      (testing "a column that is all-NULL in one handle takes the other's type"
        (let [[h1 h2] (two-handles ctx data
                                   {:a/all [{:a/id 1 :a/amount nil}]}
                                   {:a/all [{:a/id 2 :a/amount 7}]})
              r (call ctx "analyze_result"
                      {:result_handles [h1 h2] :sql "SELECT sum(amount) AS total FROM data"})]
          (is (= [["7"]] (:rows r)) (pr-str r)))))))

;; ---------------------------------------------------------------------------
;; Every input is authorized on its own
;; ---------------------------------------------------------------------------

(deftest one-expired-handle-refuses-the-whole-call-by-position
  (with-store [store]
    (let [data (atom nil) ctx (ctx-of data)
          [h1 h2] (two-handles ctx data orders invoices)]
      (swap! (:entries store) update h2 assoc :expires-at (- (results/now-ms) 1))
      (let [r (call ctx "analyze_result"
                    {:result_handles [h1 h2] :sql "SELECT count(*) FROM data"})]
        (is (= :analysis.handles/unavailable (:code (:refusal r))))
        (is (= 2 (:position (:refusal r))))
        (is (= 2 (:count (:refusal r))))
        (is (nil? (:rows r)) "no aggregate over the surviving handle alone")
        (is (not (str/includes? (:error r) h2)) "the position, not the id")))))

(deftest a-handle-from-another-scope-is-refused-as-a-scope-mismatch
  "A handle minted under a different graph VERSION is a different environment.
   The initial version combines one version only (§108.5 item 2); cross-graph
   is the named next step, not something to improvise here."
  (with-store [_s]
    (let [data (atom orders) version (atom 1)
          ctx {:credential "owner-a"
               :runtime {:pinned (fn [_ _] {:result-owner "owner-a" :graph-id "g" :version @version})
                         :answer (fn [_ _ _] {:engine/data @data :env/version @version})}}
          h1 (handle! ctx)]
      (reset! version 2)
      (let [h2 (handle! ctx)
            r (call ctx "analyze_result"
                    {:result_handles [h1 h2] :sql "SELECT count(*) FROM data"})]
        ;; h1 was minted under version 1 and the caller is now pinned to 2.
        (is (= :analysis.handles/scope-mismatch (:code (:refusal r))))
        (is (= 1 (:position (:refusal r))))
        (is (nil? (:rows r)))
        (is (not (str/includes? (:error r) h1)))))))

(deftest result-handles-is-exclusive-with-the-single-input-forms
  (with-store [_s]
    (let [data (atom orders) ctx (ctx-of data)
          [h1 h2] (two-handles ctx data orders invoices)]
      (doseq [extra [{:query "[{:a/all [:a/id]}]"} {:result_handle h1}]]
        (let [r (call ctx "analyze_result"
                      (merge {:result_handles [h1 h2] :sql "SELECT count(*) FROM data"} extra))]
          (is (str/includes? (:error r) "exactly one") (pr-str extra))
          (is (nil? (:rows r)))))
      (testing "and one handle is not a multi-handle call"
        (is (str/includes? (:error (call ctx "analyze_result"
                                         {:result_handles [h1] :sql "SELECT 1"}))
                           "at least two"))))))

;; ---------------------------------------------------------------------------
;; Budgets are summed, and the join guard is honest about being a guard
;; ---------------------------------------------------------------------------

(deftest the-row-budget-is-the-sum-across-handles-not-per-handle
  (with-store [_s]
    (let [data (atom nil) ctx (ctx-of data)
          [h1 h2] (two-handles ctx data orders invoices)
          r (call ctx "analyze_result"
                  {:result_handles [h1 h2] :input_row_limit 4
                   :sql "SELECT count(*) FROM data"})]
      ;; 2 + 3 = 5 rows against a budget of 4.
      (is (= :analysis.limit/rows (:code (:refusal r))))
      (is (= 4 (:max (:refusal r))))
      (is (= 5 (:actual (:refusal r))))
      (is (nil? (:rows r)))
      (testing "and a budget that fits answers"
        (is (= [["5"]] (:rows (call ctx "analyze_result"
                                    {:result_handles [h1 h2] :input_row_limit 5
                                     :sql "SELECT count(*) AS n FROM data"}))))))))

(deftest a-predicate-less-join-over-large-inputs-is-refused-up-front
  "The guard reads the SQL text, so it is a guard and not a guarantee — but the
   case it catches is the one that bites: a cartesian intermediate behind a
   final SELECT that looks small."
  (with-store [_s]
    (let [data (atom nil) ctx (ctx-of data)
          big (fn [n] {:a/all (mapv (fn [i] {:a/id i}) (range n))})
          [h1 h2] (two-handles ctx data (big 600) (big 600))]
      (with-redefs [duck/max-join-rows 1000]
        (let [r (call ctx "analyze_result"
                      {:result_handles [h1 h2] :sql "SELECT count(*) AS n FROM h1 JOIN h2"})]
          (is (= :analysis.limit/join-rows (:code (:refusal r))))
          (is (= 1000 (:max (:refusal r))))
          (is (nil? (:rows r))))
        (testing "a join WITH a key is let through — the guard is about pairing, not about joins"
          (let [r (call ctx "analyze_result"
                        {:result_handles [h1 h2] :sql "SELECT count(*) AS n FROM h1 JOIN h2 ON h1.id = h2.id"})]
            (is (= [["600"]] (:rows r)) (pr-str r))))))))

;; ---------------------------------------------------------------------------
;; Evidence
;; ---------------------------------------------------------------------------

(deftest every-input-carries-its-own-provenance
  (with-store [_s]
    (let [data (atom nil) ctx (ctx-of data)
          [h1 h2] (two-handles ctx data orders invoices)
          r (call ctx "analyze_result"
                  {:result_handles [h1 h2] :sql "SELECT count(*) AS n FROM data"})]
      (is (= 2 (count (:sources r))))
      (doseq [src (:sources r)]
        (is (number? (:captured-at src)))
        (is (number? (:expires-at src)))
        (is (contains? src :source-completeness))
        (is (contains? src :partial))
        (is (= 0 (:failed-inputs src))))
      (testing "and the note says the inputs were captured separately"
        (is (str/includes? (:note (:analysis r)) "NO joint snapshot"))
        (is (= 2 (:handles (:analysis r))))))))

(deftest discovery-lists-every-table-and-the-union
  (with-store [_s]
    (let [data (atom nil) ctx (ctx-of data)
          [h1 h2] (two-handles ctx data orders invoices)
          r (call ctx "analyze_result" {:result_handles [h1 h2]})
          by-table (into {} (map (juxt :table identity)) (:tables r))]
      (is (= #{"h1" "h2" "data"} (set (keys by-table))))
      (is (= 2 (:row-count (get by-table "h1"))))
      (is (= 3 (:row-count (get by-table "h2"))))
      (is (= 5 (:row-count (get by-table "data"))))
      (is (= "_atlas_handle" (:name (first (:columns (get by-table "data")))))
          "the ordinal is part of the union's declared schema, not a surprise")
      (is (= #{"_atlas_handle" "id" "region" "amount" "paid"}
             (set (map :name (:columns (get by-table "data"))))))
      (is (= :declared (:type-source (first (filter #(= "amount" (:name %))
                                                    (:columns (get by-table "h1"))))))
          "PR H's declared types survive into a multi-handle description"))))

(deftest a-materialized-handle-is-reused-inside-a-multi-handle-call
  "PR I built the table; a multi-handle call has no reason to build it again."
  (with-store [_s]
    (let [data (atom nil) ctx (ctx-of data)
          [h1 h2] (two-handles ctx data orders invoices)]
      ;; Materialize h1 on its own first.
      (call ctx "analyze_result" {:result_handle h1 :sql "SELECT count(*) FROM data"})
      (let [builds (atom 0) real duck/materialize!]
        (with-redefs [duck/materialize! (fn [& a] (swap! builds inc) (apply real a))]
          (let [r (call ctx "analyze_result"
                        {:result_handles [h1 h2] :sql "SELECT count(*) AS n FROM data"})]
            (is (= [["5"]] (:rows r)) (pr-str r))
            (is (= 0 @builds) "nothing was rebuilt")
            (is (= [true false] (:materialized (:analysis r)))
                "and the response says which input came from a materialization")))))))
