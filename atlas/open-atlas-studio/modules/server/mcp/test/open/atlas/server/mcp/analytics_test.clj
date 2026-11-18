(ns open.atlas.server.mcp.analytics-test
  "Tier ③ and the metric executor: the pure pieces, tested without a runtime.

   `rows` and `metrics` are pure; `duck` needs only an in-memory DuckDB, which
   is per-call and dropped with the connection — so all of this runs offline.
   What is NOT tested here is the token/graph path: that has exactly one
   implementation and is exercised where a real graph is assembled."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.search :as search]
            [open.atlas.server.mcp.impl.catalog :as catalog]
            [open.atlas.server.mcp.impl.duck :as duck]
            [open.atlas.server.mcp.impl.metrics :as metrics]
            [open.atlas.server.mcp.impl.rows :as rows]))

;; ---------------------------------------------------------------------------
;; Denormalization
;; ---------------------------------------------------------------------------

(deftest a-nested-collection-denormalizes-with-left-join-semantics
  (testing "one row per child, parent columns repeated"
    (is (= [{:o/no 1 :l/sku "A"} {:o/no 1 :l/sku "B"}]
           (rows/flatten-result {:o/all [{:o/no 1 :l/lines [{:l/sku "A"} {:l/sku "B"}]}]}))))
  (testing "an EMPTY child collection keeps the parent row — LEFT join, not inner"
    (is (= [{:o/no 1}]
           (rows/flatten-result {:o/all [{:o/no 1 :l/lines []}]}))))
  (testing "an empty collection wrapper is ZERO rows, not one empty row

            This is the bug the migrated docstring records: classified as a
            record, it reached DuckDB as one row with no columns and got
            'Table must have at least one column!' — a legitimately empty
            window read as a crash."
    (is (= [] (rows/flatten-result {:o/all []})))))

;; ---------------------------------------------------------------------------
;; The SQL engine
;; ---------------------------------------------------------------------------

(deftest columns-are-attribute-local-names
  "metric-expression-duckdb-spill-v1 §2: the column name IS the attribute's
   local name. An agent writes `sum(total_order)`, not `sum(cis_sales_mso_…)`."
  (is (= [[:cis.sales.mso/total_order "total_order"]
          [:cis.sales.mso/to_acct_no "to_acct_no"]]
         (duck/column-names [{:cis.sales.mso/total_order 1 :cis.sales.mso/to_acct_no "x"}])))
  (testing "a genuine collision falls back to a namespace-qualified name rather
            than silently dropping one of the two columns"
    (is (= ["no" "vpo_no"]
           (mapv second (duck/column-names [{:cis.sales.mso/no 1 :cis.purchase.vpo/no 2}]))))))

(deftest one-sql-over-the-rows
  (is (= {:columns ["g" "total"] :rows [["p" "6"] ["q" "2"]] :truncated? false
          :truncation {:rows? false :cells? false :rows-shown 2 :rows-total 2}
          :analysis/version 1}
         (duck/query-rows [{:a/x 1 :a/g "p"} {:a/x 5 :a/g "p"} {:a/x 2 :a/g "q"}]
                          "SELECT g, sum(x) AS total FROM data GROUP BY g ORDER BY g"))))

(deftest the-sandbox-has-no-way-out
  "`enable_external_access=false` is the containment that matters: the ONLY
   data is the loaded table. Pinned because a tool that runs model-written SQL
   is exactly where a file read would be most useful to an attacker."
  (is (thrown? java.sql.SQLException
               (duck/query-rows [{:a/x 1}] "SELECT * FROM read_csv('/etc/passwd')")))
  (testing "and the up-front pattern refuses the same family with a clearer message"
    (doseq [s ["ATTACH 'x.db'" "INSTALL httpfs" "COPY data TO '/tmp/x.csv'"]]
      (is (re-find duck/forbidden s) (str "should be refused up front: " s)))))

;; ---------------------------------------------------------------------------
;; The metric executor
;; ---------------------------------------------------------------------------

(def ^:private model
  {:metrics {"sales_by_customer_month"
             {:label "客户月度销售额"
              :description "开单口径的销售额。"
              :entity "cis.sales.mso"
              :dimensions [:cis.sales.mso/to_acct_no :cis.sales.mso/issue_date]
              :grain :month
              :synonyms ["月度销售额"]
              :expression {:contract :duckdb-spill/v1 :text "sum(total_order)"}}}})

(def ^:private cat
  {:joins {:cis.sales.mso/all_msos "cis.sales.mso"}
   :attributes {:cis.sales.mso/total_order {:entity "cis.sales.mso" :type :decimal}
                :cis.sales.mso/to_acct_no {:entity "cis.sales.mso" :type :integer}
                :cis.sales.mso/issue_date {:entity "cis.sales.mso" :type :date}
                :cis.sales.mso/ship_to_city {:entity "cis.sales.mso" :type :string}}})

(deftest a-metric-is-found-by-id-or-by-the-word-a-human-used
  "If a synonym misses, the agent falls through to analyze_result and invents
   the formula — which is the one thing a declared 口径 exists to prevent."
  (is (= "sales_by_customer_month" (first (metrics/find-metric model "sales_by_customer_month"))))
  (is (= "sales_by_customer_month" (first (metrics/find-metric model "月度销售额"))))
  (is (nil? (metrics/find-metric model "something else"))))

(deftest the-query-fetches-exactly-the-columns-the-expression-names
  "Contract §2 restricts a reference to the entity's registered attributes and
   the declared dimensions. So the fetch is dimensions + whatever the text
   mentions — not the whole entity, which for a wide table is most of a table
   scan to compute one sum."
  (let [{:keys [query columns]} (metrics/query-for cat (get-in model [:metrics "sales_by_customer_month"]))
        asked (set (first (vals (first query))))]
    (is (= [:cis.sales.mso/all_msos] (keys (first query))) "rooted at the global collection")
    (is (contains? asked :cis.sales.mso/total_order) "the expression names it")
    (is (contains? asked :cis.sales.mso/to_acct_no) "a declared dimension")
    (is (not (contains? asked :cis.sales.mso/ship_to_city))
        "unmentioned attributes are not fetched")
    (is (= [:cis.sales.mso/to_acct_no :cis.sales.mso/issue_date] columns))))

(deftest a-metric-with-no-global-collection-is-refused-not-guessed
  (is (str/includes? (:error (metrics/query-for {:joins {} :attributes {}}
                                                (get-in model [:metrics "sales_by_customer_month"])))
                     "no global collection")))

(deftest the-declared-expression-is-embedded-verbatim
  "Contract §1: `SELECT <text> FROM data [GROUP BY <dims>]`. The executor does
   not rewrite the 口径 — §4 is explicit that `:grain` describes semantics and
   does NOT rewrite the expression, so what runs is what the modeller signed."
  (let [m (get-in model [:metrics "sales_by_customer_month"])
        dims [:cis.sales.mso/to_acct_no :cis.sales.mso/issue_date]]
    (testing "no grouping asked for"
      (is (= "SELECT sum(total_order) AS value FROM data" (metrics/sql-for m dims []))))
    (testing "grouped by a declared dimension"
      (is (= (str "SELECT \"to_acct_no\", sum(total_order) AS value FROM data"
                  " GROUP BY \"to_acct_no\" ORDER BY \"to_acct_no\"")
             (metrics/sql-for m dims [:cis.sales.mso/to_acct_no]))))
    (testing "a column that is NOT a declared dimension cannot become a grouping
              — contract §4: grouping columns come only from :dimensions"
      (is (= "SELECT sum(total_order) AS value FROM data"
             (metrics/sql-for m dims [:cis.sales.mso/ship_to_city]))))))

(deftest a-metric-computes-end-to-end-over-its-rows
  "The whole ladder in one assertion: rows → table `data` → declared expression."
  (let [m (get-in model [:metrics "sales_by_customer_month"])
        data {:cis.sales.mso/all_msos [{:cis.sales.mso/to_acct_no 1 :cis.sales.mso/total_order 100}
                                       {:cis.sales.mso/to_acct_no 1 :cis.sales.mso/total_order 50}
                                       {:cis.sales.mso/to_acct_no 2 :cis.sales.mso/total_order 7}]}
        flat (rows/flatten-result data)
        out (duck/query-rows flat (metrics/sql-for m [:cis.sales.mso/to_acct_no]
                                                   [:cis.sales.mso/to_acct_no]))]
    (is (= ["to_acct_no" "value"] (:columns out)))
    (is (= [["1" "150"] ["2" "7"]] (:rows out)))))

;; ---------------------------------------------------------------------------
;; The discovery rendering (D2.29)
;; ---------------------------------------------------------------------------

(def ^:private discovery-cat
  "A catalog shaped the way the engine renders one since D2.29: `:attributes`
   is SCALAR-ONLY, joins answer from `:joins`."
  {:attributes {:cis.master.part/sku_no {:entity "cis.master.part" :type :string
                                         :description "SKU"}}
   :joins {:cis.master.part/all_parts "cis.master.part"
           :cis.master.part/msos "cis.sales.mso"}})

(deftest a-declared-collection-is-listed-once-as-a-join
  "**The double-listing is gone** (D2.29). `all_parts` used to reach the agent
   twice — once as a `:type \"json\"` attribute row (it was a registered
   `:collection?` attribute) and once as a navigation row — and the agent had to
   work out that the two were the same thing. One registry, one row.

   The row SHAPE is unchanged: an agent that has been reading `:type \"join\"`
   with a `→ target` description must not be able to tell that anything moved."
  (let [rows (catalog/attributes-for-entities
              discovery-cat
              {:cis.master.part/all_parts {:limit {:default 50 :max 500}}}
              ["cis.master.part"])
        by-attr (group-by :attribute rows)]
    (is (= 1 (count (get by-attr "cis.master.part/all_parts")))
        "listed exactly once, not once per registry")
    (is (= {:entity "cis.master.part"
            :attribute "cis.master.part/all_parts"
            :type "join"
            :description "→ cis.master.part"
            :limit {:default 50 :max 500}}
           (first (get by-attr "cis.master.part/all_parts"))))
    (is (= "join" (:type (first (get by-attr "cis.master.part/msos")))))
    (is (= "string" (:type (first (get by-attr "cis.master.part/sku_no"))))
        "a scalar attribute still renders as its own type")
    (is (not-any? #(= "json" (:type %)) rows)
        "no join reaches the agent disguised as a JSON-valued attribute")))

;; ---------------------------------------------------------------------------
;; search_attributes rides the real index (D7.10)
;; ---------------------------------------------------------------------------

(def ^:private demo-model
  "The demo model — `fixtures/demo/model.edn` is its home. A toy model cannot
   prove what this tool is for: term hits with many bindings, CJK synonyms and
   typo tolerance only exist once the model is real (D7.11)."
  (delay (edn/read-string (slurp (io/resource "model.edn")))))

(defn- search* [q & [limit]]
  (catalog/search-attributes [::test-graph 1] @demo-model 1 q limit))

(deftest search-attributes-is-the-real-index-not-a-substring-scan
  "D7.10 wires the server to `open.atlas.common.search`. The scan this replaced
   could only match a lowercase substring of name/label/description/synonyms —
   every assertion below is something it could NOT do."
  (testing "a CJK term the query never spells the same way as the model"
    (is (some #(= "cis.party/customer_no" (:term %)) (:hits (search* "客户编号")))))

  (testing "a partial CJK word — n-grams, which a whole-word scan has no notion of"
    (is (some #(= "cis.party/customer_no" (:term %)) (:hits (search* "客户编")))))

  (testing "a latin typo: the thing the tool description promises"
    (let [typo (into #{} (map :attribute) (:hits (search* "custmer")))
          ok   (into #{} (map :attribute) (:hits (search* "customer")))]
      (is (seq (clojure.set/intersection typo ok)))))

  (testing "identifier folding: three spellings, one answer (common/search 裁定 ③)"
    (is (apply = (for [q ["to_acct_no" "toAcctNo" "to acct no"]]
                   (into #{} (map :attribute) (:hits (search* q)))))))

  (testing "a term hit is ONE row carrying its bindings, not one row per binding
            — the flooding D7.11 solves structurally"
    (let [h (first (filter #(= "cis.party/customer_no" (:term %)) (:hits (search* "客户编号"))))]
      (is (= "term" (:kind h)))
      (is (= 7 (:binding_count h)))
      (is (= 7 (count (:bindings h))))
      (is (every? :entity (:bindings h)))
      (is (= 1 (count (filter #(= "cis.party/customer_no" (:term %))
                              (filter #(= "term" (:kind %)) (:hits (search* "客户编号")))))))))

  (testing "no score reaches the agent — position is order, not confidence"
    (is (not-any? #(some (fn [k] (re-find #"(?i)score|rank|bm25|confidence" (name k)))
                         (keys %))
                  (:hits (search* "客户编号"))))))

(deftest search-attributes-refuses-over-limit-instead-of-truncating
  "The scan did `(take 50)` silently. A cut list is indistinguishable from a
   complete one, and the agent is reading it precisely to choose among them."
  (let [r (search* "customer" 1)]
    (is (nil? (:hits r)))
    (is (= [:search.query/over-limit] (map :code (:findings r))))
    (is (every? search/finding? (:findings r))))
  (testing "and a blank query is refused too, rather than answered with nothing"
    (is (= [:search.query/blank] (map :code (:findings (search* "   ，。  ")))))))

(deftest search-attributes-agrees-with-the-catalog-about-what-exists
  "D2.28: `:listed? false` attributes are not in the catalog for anyone, and
   naming one is refused as `:unknown-attribute` — byte-identical to naming an
   attribute that does not exist. The consumer cannot tell those apart, so a
   search hit the catalog then denies is a name the agent can only misuse."
  (let [ids (into #{} (comp (map :attribute) (remove nil?))
                  (:hits (search* "entry_id")))]
    (is (seq ids) "premise: this word does hit something")
    (is (not (contains? ids "cis.master.part/entry_id"))
        "`:listed? false` in the demo model, so the catalog denies it")
    (is (contains? ids "cis.sales.mso_order_detail/entry_id")
        "its listed sibling is still found — what is filtered is `:listed?`,
         not the word")))

(deftest the-search-index-is-cached-per-graph-version
  "D7.10: the index is rebuilt from the loaded artifact and keyed by graph
   version. Same key → the same handle, so a call is not paying to rebuild the
   whole corpus; a different version misses, which is the only invalidation
   there is."
  (let [a (catalog/search-index [::cache-test 1] @demo-model 1)
        b (catalog/search-index [::cache-test 1] @demo-model 1)
        c (catalog/search-index [::cache-test 2] @demo-model 2)]
    (is (identical? a b))
    (is (not (identical? a c)))
    (is (= 2 (search/index-revision c)))))
