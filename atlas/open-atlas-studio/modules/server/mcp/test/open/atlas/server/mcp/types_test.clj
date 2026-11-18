(ns open.atlas.server.mcp.types-test
  "Type fidelity: what a column is loaded as, and what a cell comes back as
   (doc 108 §108.3 and §108.5 item 3, ruled §108.6).

   The rule underneath every assertion here is the same one D2.9 states:
   a number that quietly changes on its way to the reader is worse than no
   number, because nothing in the answer says it changed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.server.mcp.impl.duck :as duck]
            [open.atlas.server.mcp.impl.tools :as tools]))

(defn- call [ctx tool args]
  ((:handler (first (filter #(= tool (:name %)) tools/declarations))) ctx args))

(defn- ctx-of
  ([data] (ctx-of data nil))
  ([data catalog]
   {:credential "owner-a"
    :runtime {:pinned (fn [_ _] (cond-> {:graph-id "g" :version 1}
                                  catalog (assoc :catalog catalog)))
              :answer (fn [_ _ _] {:engine/data data :env/version 1})}}))

(defn- refusal-code [r] (:code (:refusal r)))

(defn- spec-for [flat-rows column & [declared]]
  (first (filter #(= column (:name %)) (duck/column-specs flat-rows declared))))

;; ---------------------------------------------------------------------------
;; Load-side fidelity
;; ---------------------------------------------------------------------------

(deftest a-high-precision-decimal-survives-sum-exactly
  "The whole point of DECIMAL over DOUBLE: 123456789012345.678901 has 21
   significant digits and a double carries about 15. Loaded as DOUBLE this sum
   comes back wrong in the sixth decimal and nothing says so."
  (let [rows [{:a/amount 123456789012345.678901M} {:a/amount 0.000001M}]]
    (is (= "DECIMAL(21,6)" (:sql (spec-for rows "amount"))))
    (testing "version 2 hands back the exact digits as a string"
      (let [r (duck/query-rows rows "SELECT sum(amount) AS total FROM data" nil {:version 2})]
        (is (= [["123456789012345.678902"]] (:rows r)))
        (is (= 2 (:analysis/version r)))))
    (testing "and version 1 prints the same exact digits, as it always has"
      (is (= [["123456789012345.678902"]]
             (:rows (duck/query-rows rows "SELECT sum(amount) AS total FROM data")))))))

(deftest integers-load-as-bigint-up-to-the-boundary-and-are-refused-beyond-it
  (let [ok [{:a/n Long/MAX_VALUE} {:a/n Long/MIN_VALUE}]]
    (is (= "BIGINT" (:sql (spec-for ok "n"))))
    (is (= [[(str Long/MAX_VALUE)]]
           (:rows (duck/query-rows ok "SELECT max(n) AS m FROM data")))))
  (testing "one digit past what a BIGINT carries is refused, not wrapped"
    (let [over [{:a/n (inc (bigint Long/MAX_VALUE))}]
          e (try (duck/column-specs over) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (let [ref* (:analysis/refusal (ex-data e))]
        (is (= :analysis.type/out-of-range (:code (:refusal ref*))))
        (is (= "n" (:column (:refusal ref*))))
        (is (not (str/includes? (:error ref*) (str Long/MAX_VALUE)))
            "the refusal names the column, never the value")))))

(deftest an-all-null-column-stays-varchar
  "Nothing in the data says what it would have been, and a guess is a type the
   next page contradicts."
  (let [rows [{:a/id 1 :a/note nil} {:a/id 2 :a/note nil}]]
    (is (= "VARCHAR" (:sql (spec-for rows "note"))))
    (is (= [[nil]] (:rows (duck/query-rows rows "SELECT note FROM data LIMIT 1" nil {:version 2}))))))

(deftest a-mixed-column-is-refused-by-name-with-no-value-in-the-message
  "Stringifying it silently turns `sum(amount)` into either a DuckDB error the
   agent cannot explain or a lexicographic MAX that looks like an answer."
  (let [rows [{:a/amount 10} {:a/amount "n/a"}]
        e (try (duck/column-specs rows) nil (catch clojure.lang.ExceptionInfo e e))
        ref* (:analysis/refusal (ex-data e))]
    (is (= :analysis.type/mixed (:code (:refusal ref*))))
    (is (= "amount" (:column (:refusal ref*))))
    (is (not (str/includes? (:error ref*) "n/a")))
    (is (not (str/includes? (:error ref*) "10"))))
  (testing "it reaches the agent as a structured refusal, not a stack trace"
    (let [r (call (ctx-of {:a/all [{:a/amount 10} {:a/amount "n/a"}]}) "analyze_result"
                  {:query "[{:a/all [:a/amount]}]" :sql "SELECT sum(amount) FROM data"})]
      (is (= :analysis.type/mixed (refusal-code r)))
      (is (nil? (:rows r))))))

(deftest a-precision-wider-than-duckdb-carries-is-refused-never-rounded
  (let [huge (bigdec (str (apply str (repeat 34 "9")) ".99999"))
        e (try (duck/column-specs [{:a/amount huge}]) nil
               (catch clojure.lang.ExceptionInfo e e))
        ref* (:analysis/refusal (ex-data e))]
    (is (= :analysis.type/precision (:code (:refusal ref*))))
    (is (= 38 (:max (:refusal ref*))))
    (is (= "amount" (:column (:refusal ref*))))))

(deftest nested-values-are-flagged-as-printed-structures
  "They have always been loaded as printed EDN. What changes is that the agent
   is TOLD, instead of discovering it by writing sum() over a string."
  (let [rows [{:a/id 1 :a/tags ["x" "y"]}]
        cols (:columns (duck/describe-rows rows))
        tags (first (filter #(= "tags" (:name %)) cols))]
    (is (= "VARCHAR" (:type tags)))
    (is (true? (:nested tags)))
    (is (nil? (:nested (first (filter #(= "id" (:name %)) cols)))))))

(deftest a-collision-still-resolves-to-a-namespace-qualified-name
  "The naming rule is untouched by typing."
  (is (= ["no" "vpo_no"]
         (mapv :name (duck/column-specs [{:cis.sales.mso/no 1 :cis.purchase.vpo/no 2}])))))

;; ---------------------------------------------------------------------------
;; Declared types outrank inference
;; ---------------------------------------------------------------------------

(deftest the-graphs-declaration-beats-what-the-sample-happens-to-look-like
  "A page whose every `amount` happens to be a whole number is not evidence
   that the column is an integer — the model already said what it is."
  (let [rows [{:a/amount 10 :a/flag true}]
        declared {:a/amount :decimal}]
    (is (str/starts-with? (:sql (spec-for rows "amount" declared)) "DECIMAL"))
    (is (= :declared (:type-source (spec-for rows "amount" declared))))
    (is (= :inferred (:type-source (spec-for rows "flag" declared)))))

  (testing "temporal declarations are carried as text with a source PR K can hook"
    (let [spec (spec-for [{:a/when "2026-09-18"}] "when" {:a/when :date})]
      (is (= "VARCHAR" (:sql spec)))
      (is (= :declared-temporal (:type-source spec)))))

  (testing "and the declaration travels from the pinned catalog through the tool"
    (let [ctx (ctx-of {:a/all [{:a/amount 10}]}
                      {:attributes {:a/amount {:entity "a" :type :decimal}}})
          r (call ctx "analyze_result" {:query "[{:a/all [:a/amount]}]"})
          col (first (:columns r))]
      (is (str/starts-with? (:type col) "DECIMAL") (pr-str r))
      (is (= :declared (:type-source col))))))

;; ---------------------------------------------------------------------------
;; One schema for discovery and execution
;; ---------------------------------------------------------------------------

(deftest discovery-and-execution-derive-types-from-the-same-function
  "§108.3: 发现接口与 SQL 使用同一 schema. An agent that plans `sum(amount)`
   against the DECIMAL discovery showed it must not meet a VARCHAR at load."
  (let [rows [{:a/amount 1.50M :a/n 3 :a/ok true :a/name "x" :a/tags [1 2] :a/nothing nil}]
        declared {:a/n :decimal}
        specs (duck/column-specs rows declared)
        described (:columns (duck/describe-rows rows declared))]
    (is (= (mapv :sql specs) (mapv :type described)))
    (is (= (mapv :name specs) (mapv :name described)))
    (is (= (mapv :type-source specs) (mapv :type-source described)))
    (testing "and the loaded table really has those types"
      (let [r (duck/query-rows rows "SELECT typeof(amount) AS a, typeof(n) AS n FROM data"
                               nil {:declared declared})]
        (is (= [["DECIMAL(3,2)" "DECIMAL(1,0)"]] (:rows r)))))))

;; ---------------------------------------------------------------------------
;; The protocol version
;; ---------------------------------------------------------------------------

(def ^:private fixture
  {:a/all [{:a/n 7 :a/ratio 0.5 :a/ok true :a/name "x" :a/missing nil}]})

(deftest version-one-is-byte-identical-to-what-clients-parse-today
  "The default stays 1 until a later ruling flips it: every deployed agent
   parses all-strings cells, and changing the shape underneath them is a
   change nobody asked for."
  (is (= 1 duck/default-analysis-version))
  (let [r (call (ctx-of fixture) "analyze_result"
                {:query "[{:a/all [:a/n]}]"
                 :sql "SELECT n, ratio, ok, name, missing FROM data"})]
    (is (= [["7" "0.5" "true" "x" nil]] (:rows r)))
    (is (= 1 (:analysis/version r))))
  (testing "asking for version 1 explicitly is the same answer"
    (is (= [["7" "0.5" "true" "x" nil]]
           (:rows (call (ctx-of fixture) "analyze_result"
                        {:query "[{:a/all [:a/n]}]" :analysis_version 1
                         :sql "SELECT n, ratio, ok, name, missing FROM data"}))))))

(deftest version-two-carries-jdbc-types
  (let [r (call (ctx-of fixture) "analyze_result"
                {:query "[{:a/all [:a/n]}]" :analysis_version 2
                 :sql "SELECT n, ratio, ok, name, missing FROM data"})]
    (is (= [[7 0.5 true "x" nil]] (:rows r)))
    (is (= 2 (:analysis/version r)))
    (let [[n ratio ok _ missing] (first (:rows r))]
      (is (integer? n))
      (is (double? ratio))
      (is (boolean? ok))
      (is (nil? missing))))
  (testing "a long string is still clipped, and the truncation evidence says so"
    (let [r (call (ctx-of {:a/all [{:a/n 1}]}) "analyze_result"
                  {:query "[{:a/all [:a/n]}]" :analysis_version 2
                   :sql (str "SELECT repeat('x', " (+ 10 duck/max-cell) ") AS wide FROM data")})]
      (is (= (inc duck/max-cell) (count (ffirst (:rows r)))) "max-cell chars plus the ellipsis")
      (is (true? (:cells? (:truncation r)))))))

(deftest an-unknown-analysis-version-is-refused-not-rounded-down
  (doseq [v [0 3 99]]
    (let [r (call (ctx-of fixture) "analyze_result"
                  {:query "[{:a/all [:a/n]}]" :analysis_version v
                   :sql "SELECT n FROM data"})]
      (is (str/includes? (:error r) "analysis_version"))
      (is (nil? (:rows r))))))

(deftest compute-metric-honours-the-version-too
  "The 口径's number is exactly where an approximated decimal does the most
   damage: it is the figure that gets quoted."
  (let [model {:metrics {"total_sales"
                         {:label "销售额" :description "开单口径。"
                          :entity "a"
                          :dimensions [:a/g]
                          :expression {:contract :duckdb-spill/v1 :text "sum(amount)"}}}}
        catalog {:joins {:a/all "a"}
                 :attributes {:a/amount {:entity "a" :type :decimal}
                              :a/g {:entity "a" :type :string}}}
        data {:a/all [{:a/g "p" :a/amount 123456789012345.678901M}
                      {:a/g "p" :a/amount 0.000001M}]}
        ctx {:credential "owner-a"
             :runtime {:pinned (fn [_ _] {:graph-id "g" :version 1
                                          :catalog catalog :model model})
                       :answer (fn [_ _ _] {:engine/data data :env/version 1})}}]
    (testing "version 2 returns the exact decimal as a string"
      (let [r (call ctx "compute_metric" {:metric "total_sales" :analysis_version 2})]
        (is (= [["123456789012345.678902"]] (:rows r)) (pr-str r))
        (is (= 2 (:analysis/version r)))
        (is (= "total_sales" (:metric r)))))
    (testing "version 1 is unchanged"
      (let [r (call ctx "compute_metric" {:metric "total_sales"})]
        (is (= [["123456789012345.678902"]] (:rows r)))
        (is (= 1 (:analysis/version r)))))))
