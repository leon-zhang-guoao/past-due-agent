(ns open.atlas.server.mcp.time-test
  "Opt-in time inference (doc 108 §108.3 second paragraph, §108.5 item 4, ruled
   §108.6).

   One rule underlies all of it: **no value is ever assumed to be UTC or the
   host's zone.** A wall-clock reading with no offset does not name an instant,
   and picking a zone for it invents one — every `date_trunc('day', …)`
   downstream then answers for a day the business may never have meant."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.server.mcp.impl.duck :as duck]
            [open.atlas.server.mcp.impl.results :as results]
            [open.atlas.server.mcp.impl.tools :as tools]))

(defn- call [ctx tool args]
  ((:handler (first (filter #(= tool (:name %)) tools/declarations))) ctx args))

(defn- ctx-of
  ([data] (ctx-of data {:a/moment {:entity "a" :type :instant}
                        :a/day {:entity "a" :type :date}}))
  ([data attrs]
   {:credential "owner-a"
    :runtime {:pinned (fn [_ _] {:result-owner "owner-a" :graph-id "g" :version 1
                                 :catalog {:attributes attrs}})
              :answer (fn [_ _ _] {:engine/data data :env/version 1})}}))

(defn- refusal-code [r] (:code (:refusal r)))

(defn- spec-for [rows column declared & [opts]]
  (first (filter #(= column (:name %)) (duck/column-specs rows declared (or opts {})))))

(defmacro with-store [[sym] & body]
  `(let [~sym (results/create-store)]
     (binding [results/*store* ~sym] (try ~@body (finally (results/close! ~sym))))))

(def ^:private on {:infer-time? true})

;; ---------------------------------------------------------------------------
;; The switch
;; ---------------------------------------------------------------------------

(deftest nothing-is-inferred-unless-the-switch-is-on
  (let [rows [{:a/moment "2024-03-01T10:00:00Z"} {:a/moment "2024-03-02T10:00:00Z"}]
        declared {:a/moment :instant}]
    (testing "a declared instant is text by default, and says which kind of text"
      (let [sp (spec-for rows "moment" declared)]
        (is (= "VARCHAR" (:sql sp)))
        (is (= :declared-temporal (:type-source sp)))
        (is (= :off (:time-status sp)))))
    (testing "and so is a column that would obviously parse"
      (is (= "VARCHAR" (:sql (spec-for [{:a/x "2024-03-01T10:00:00Z"}] "x" nil)))))
    (testing "through the tool, the default is off"
      (let [r (call (ctx-of {:a/all rows}) "analyze_result" {:query "[{:a/all [:a/moment]}]"})
            col (first (:columns r))]
        (is (= "VARCHAR" (:type col)))
        (is (= :declared-temporal (:type-source col)))))))

;; ---------------------------------------------------------------------------
;; Declared temporal attributes, switch on
;; ---------------------------------------------------------------------------

(deftest a-declared-instant-whose-values-all-carry-a-zone-becomes-a-real-moment
  (let [rows [{:a/moment "2024-03-01T10:00:00Z"} {:a/moment "2024-03-15T10:00:00+08:00"}]
        sp (spec-for rows "moment" {:a/moment :instant} on)]
    (is (= "TIMESTAMP WITH TIME ZONE" (:sql sp)))
    (is (= :declared (:time-source sp)))
    (is (= :ok (:time-status sp)))
    (testing "and date_trunc works on it, which is the whole point"
      (let [r (duck/query-rows rows "SELECT date_trunc('month', moment) AS m, count(*) AS n FROM data GROUP BY 1 ORDER BY 1"
                               nil {:declared {:a/moment :instant} :infer-time? true})]
        (is (= 1 (count (:rows r))) "both values are in the same UTC month")
        (is (= "2" (second (first (:rows r)))))))))

(deftest one-value-without-a-zone-refuses-the-column-and-names-no-value
  (let [rows [{:a/moment "2024-03-01T10:00:00Z"} {:a/moment "2024-03-02T10:00:00"}]
        e (try (duck/column-specs rows {:a/moment :instant} on) nil
               (catch clojure.lang.ExceptionInfo e e))
        ref* (:analysis/refusal (ex-data e))]
    (is (= :analysis.type/time-zone-missing (:code (:refusal ref*))))
    (is (= "moment" (:column (:refusal ref*))))
    (is (not (str/includes? (:error ref*) "2024-03-02")) "the column, never the value")
    (testing "and it reaches the agent as a structured refusal"
      (let [r (call (ctx-of {:a/all rows}) "analyze_result"
                    {:query "[{:a/all [:a/moment]}]" :infer_time true
                     :sql "SELECT count(*) FROM data"})]
        (is (= :analysis.type/time-zone-missing (refusal-code r)))
        (is (nil? (:rows r)))))))

(deftest a-declared-date-loads-as-date-or-is-refused-by-format
  (is (= "DATE" (:sql (spec-for [{:a/day "2024-03-01"} {:a/day "2024-12-31"}] "day" {:a/day :date} on))))
  (let [e (try (duck/column-specs [{:a/day "2024-03-01"} {:a/day "01/03/2024"}] {:a/day :date} on) nil
               (catch clojure.lang.ExceptionInfo e e))
        ref* (:analysis/refusal (ex-data e))]
    (is (= :analysis.type/time-format (:code (:refusal ref*))))
    (is (= "day" (:column (:refusal ref*))))
    (is (= "YYYY-MM-DD" (:expected (:refusal ref*))))
    (is (not (str/includes? (:error ref*) "01/03/2024")))))

;; ---------------------------------------------------------------------------
;; Undeclared columns: one fixed rule set, checked against every value
;; ---------------------------------------------------------------------------

(deftest an-undeclared-column-is-promoted-only-by-a-rule-that-holds-for-every-value
  (testing "full ISO with Z or an offset"
    (let [sp (spec-for [{:a/x "2024-03-01T10:00:00Z"} {:a/x "2024-03-02T10:00:00+02:00"}] "x" nil on)]
      (is (= "TIMESTAMP WITH TIME ZONE" (:sql sp)))
      (is (= :inferred (:time-source sp)))))

  (testing "YYYY-MM-DD"
    (is (= "DATE" (:sql (spec-for [{:a/x "2024-03-01"}] "x" nil on)))))

  (testing "an ISO datetime with NO zone is left alone, and the status says why"
    (let [sp (spec-for [{:a/x "2024-03-01T10:00:00"} {:a/x "2024-03-02T11:00:00"}] "x" nil on)]
      (is (= "VARCHAR" (:sql sp)))
      (is (= :none (:time-source sp)))
      (is (= :zone-missing (:time-status sp)))))

  (testing "bare digits are ids far more often than epochs"
    (doseq [vs [["20240101" "20240102"] ["1709287200000" "1709373600000"]]]
      (let [sp (spec-for (mapv #(hash-map :a/x %) vs) "x" nil on)]
        (is (= "VARCHAR" (:sql sp)) (pr-str vs))
        (is (= :not-temporal (:time-status sp))))))

  (testing "01/02/2024 is two different days depending on who typed it"
    (let [sp (spec-for [{:a/x "01/02/2024"} {:a/x "02/01/2024"}] "x" nil on)]
      (is (= "VARCHAR" (:sql sp)))
      (is (= :ambiguous (:time-status sp)))))

  (testing "one non-matching value is enough to stop the promotion —
            the rule is checked against EVERY value, not the first row"
    (is (= "VARCHAR" (:sql (spec-for [{:a/x "2024-03-01T10:00:00Z"} {:a/x "n/a"}] "x" nil on))))))

(deftest offsets-are-preserved-as-instants-across-a-dst-boundary
  "Europe/Berlin moved from +01:00 to +02:00 on 2024-03-31. Both readings below
   are 00:30 UTC; a naive «drop the offset» or «assume one zone» would place
   them an hour apart."
  (let [rows [{:a/moment "2024-03-31T01:30:00+01:00"} {:a/moment "2024-03-31T02:30:00+02:00"}]
        r (duck/query-rows rows "SELECT count(DISTINCT epoch_ms(moment)) AS distinct_instants FROM data"
                           nil {:declared {:a/moment :instant} :infer-time? true})]
    (is (= [["1"]] (:rows r)) "the same instant, written in two zones")))

;; ---------------------------------------------------------------------------
;; The wire
;; ---------------------------------------------------------------------------

(deftest a-moment-travels-as-an-iso-string-that-keeps-its-offset
  (let [rows {:a/all [{:a/moment "2024-03-01T10:00:00Z" :a/day "2024-03-01"}]}
        ctx (ctx-of rows)]
    (testing "version 1 is text, as it always was"
      (let [r (call ctx "analyze_result"
                    {:query "[{:a/all [:a/moment]}]" :infer_time true
                     :sql "SELECT moment, day FROM data"})]
        (is (every? string? (first (:rows r))))
        (is (str/starts-with? (ffirst (:rows r)) "2024-03-01T10:00"))
        (is (= "2024-03-01" (second (first (:rows r)))))))

    (testing "version 2 keeps the offset and STATES the column's SQL type —
              JSON has no datetime, so the cell alone cannot say it is a moment"
      (let [r (call ctx "analyze_result"
                    {:query "[{:a/all [:a/moment]}]" :infer_time true :analysis_version 2
                     :sql "SELECT moment, day FROM data"})
            types (into {} (map (juxt :name :type)) (:column-types r))]
        (is (= 2 (:analysis/version r)))
        (is (str/includes? (ffirst (:rows r)) "Z") "the offset survives")
        (is (str/includes? (get types "moment") "TIMESTAMP"))
        (is (= "DATE" (get types "day")))))))

(deftest discovery-and-execution-agree-with-the-switch-on
  (let [rows [{:a/moment "2024-03-01T10:00:00Z" :a/day "2024-03-01" :a/note "x"}]
        declared {:a/moment :instant :a/day :date}
        specs (duck/column-specs rows declared on)
        described (:columns (duck/describe-rows rows declared on))]
    (is (= (mapv :sql specs) (mapv :type described)))
    (is (= (mapv :time-source specs) (mapv :time-source described)))
    (is (= [:declared :declared nil] (mapv :time-source specs)))
    (testing "and the loaded table really has those types"
      (is (= [["TIMESTAMP WITH TIME ZONE" "DATE"]]
             (:rows (duck/query-rows rows "SELECT typeof(moment) AS a, typeof(day) AS d FROM data"
                                     nil {:declared declared :infer-time? true})))))))

;; ---------------------------------------------------------------------------
;; The switch reaches the caches and the multi-handle path
;; ---------------------------------------------------------------------------

(deftest the-switch-is-part-of-the-materialization-key
  "A table materialized with the column as VARCHAR cannot answer the
   `date_trunc` the switched-on call asks of it."
  (with-store [_s]
    (let [ctx (ctx-of {:a/all [{:a/moment "2024-03-01T10:00:00Z"}]})
          h (:result_handle (call ctx "execute_eql" {:query "[{:a/all [:a/moment]}]" :retain_result true}))
          builds (atom 0) real duck/materialize!]
      (with-redefs [duck/materialize! (fn [& a] (swap! builds inc) (apply real a))]
        (call ctx "analyze_result" {:result_handle h :sql "SELECT count(*) FROM data"})
        (call ctx "analyze_result" {:result_handle h :infer_time true
                                    :sql "SELECT count(*) FROM data"})
        (is (= 2 @builds) "two switches, two tables")
        (testing "and each answers in its own type"
          (is (= [["VARCHAR"]] (:rows (call ctx "analyze_result"
                                            {:result_handle h :sql "SELECT typeof(moment) AS t FROM data"}))))
          (is (= [["TIMESTAMP WITH TIME ZONE"]]
                 (:rows (call ctx "analyze_result"
                              {:result_handle h :infer_time true
                               :sql "SELECT typeof(moment) AS t FROM data"})))))))))

(deftest a-moment-in-one-handle-and-zone-less-text-in-another-is-refused
  (with-store [_s]
    (let [data (atom nil)
          ctx {:credential "owner-a"
               :runtime {:pinned (fn [_ _] {:result-owner "owner-a" :graph-id "g" :version 1})
                         :answer (fn [_ _ _] {:engine/data @data :env/version 1})}}
          mint (fn [d] (reset! data d)
                 (:result_handle (call ctx "execute_eql" {:query "[{:a/all [:a/x]}]" :retain_result true})))
          h1 (mint {:a/all [{:a/x "2024-03-01T10:00:00Z"}]})
          h2 (mint {:a/all [{:a/x "2024-03-01T10:00:00"}]})
          r (call ctx "analyze_result"
                  {:result_handles [h1 h2] :infer_time true :sql "SELECT count(*) FROM data"})]
      (is (= :analysis.type/mixed (refusal-code r)))
      (is (= "x" (:column (:refusal r))))
      (is (= [1 2] (:positions (:refusal r))))
      (is (nil? (:rows r))))))

(deftest compute-metric-can-group-a-declared-instant-by-month
  (let [model {:metrics {"sales_by_month"
                         {:label "月度销售额" :description "开单口径。"
                          :entity "a"
                          :dimensions [:a/moment]
                          :expression {:contract :duckdb-spill/v1 :text "sum(amount)"}}}}
        catalog {:joins {:a/all "a"}
                 :attributes {:a/amount {:entity "a" :type :decimal}
                              :a/moment {:entity "a" :type :instant}}}
        data {:a/all [{:a/moment "2024-03-01T10:00:00Z" :a/amount 10.00M}
                      {:a/moment "2024-03-20T10:00:00Z" :a/amount 5.50M}]}
        ctx {:credential "owner-a"
             :runtime {:pinned (fn [_ _] {:graph-id "g" :version 1 :catalog catalog :model model})
                       :answer (fn [_ _ _] {:engine/data data :env/version 1})}}]
    (testing "with the switch off the grain column is text, and the metric still computes"
      (let [r (call ctx "compute_metric" {:metric "sales_by_month"})]
        (is (= [["15.50"]] (:rows r)) (pr-str r))))
    (testing "with the switch on it is a real moment, so a month grain is date arithmetic"
      (let [r (call ctx "compute_metric" {:metric "sales_by_month" :infer_time true
                                          :group_by ["a/moment"]})]
        (is (= 2 (count (:rows r))) "grouped by the instant itself, both rows distinct")
        (is (= "sales_by_month" (:metric r)))))))
