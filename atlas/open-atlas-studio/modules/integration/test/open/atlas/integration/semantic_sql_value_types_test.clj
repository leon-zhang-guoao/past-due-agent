(ns open.atlas.integration.semantic-sql-value-types-test
  "A legacy graph rebuilt as Semantic Contract 2.0 keeps its SQL, and the SQL
   keeps returning what the database gives: Double for a float, text for a cast
   timestamp or date. The 2.0 result check accepts only BigDecimal, LocalDate
   and Instant for :decimal / :date / :datetime, so the sql connector converts
   by the declared type on the 2.0 path (owner 2026-09-29, pastdue / flowx
   upgrade). Legacy dialect resolvers never carry the declared types and keep
   their exact JDBC values. A value that needs a guessed zone is not converted
   and is still refused by the result check."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql :as sql]
            [open.atlas.common.contract :as version]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.semantic-compiler :as compiler]
            [open.atlas.common.semantic-result :as result]))

(def ^:private vocabulary
  {:entities {"monitor" {:entity/label "Monitor" :entity/identity [:monitor/country]
                         :entity/attributes [:monitor/country :monitor/ratio :monitor/snapshot
                                             :monitor/checked-at :monitor/local-at :monitor/all]}}
   :attributes {:monitor/country {:attribute/label "Country" :attribute/type :string :attribute/nullable? false}
                :monitor/ratio {:attribute/label "Ratio" :attribute/type :decimal :attribute/nullable? true}
                :monitor/snapshot {:attribute/label "Snapshot" :attribute/type :date :attribute/nullable? true}
                :monitor/checked-at {:attribute/label "Checked at" :attribute/type :datetime :attribute/nullable? true}
                :monitor/local-at {:attribute/label "Local time" :attribute/type :datetime :attribute/nullable? true}
                :monitor/all {:attribute/label "All" :attribute/type :ref :attribute/nullable? false
                              :attribute/target "monitor" :attribute/cardinality :many}}})

(def ^:private select
  "SELECT country, ratio, snapshot, checked_at, local_at FROM monitor")

(def ^:private output-map
  {:monitor/country "country" :monitor/ratio "ratio" :monitor/snapshot "snapshot"
   :monitor/checked-at "checked_at" :monitor/local-at "local_at"})

(def ^:private definitions
  {:resolvers {"monitor_all" {:label "All" :effect :read :result/cardinality :many :input []
                              :output [{:monitor/all [:monitor/country :monitor/ratio :monitor/snapshot
                                                      :monitor/checked-at]}]
                              :pagination {:default 100 :max 100}
                              :sort {:allowed [:monitor/country :monitor/ratio] :default [:monitor/country :asc]}}
               "monitor_by_country" {:label "One" :effect :read :result/cardinality :one
                                     :input [:monitor/country]
                                     :output [:monitor/ratio :monitor/snapshot :monitor/checked-at :monitor/local-at]}}
   :operations {}
   :sources {"db" {:source/kind "sql" :source/effects #{:read}}}
   :bindings {"all-sql" {:binding/for [:resolver "monitor_all"] :binding/source "db" :binding/effect :read
                         :binding/implementation {:query select}
                         :binding/input-map {}
                         :binding/output-map (-> output-map (dissoc :monitor/local-at) (assoc :monitor/all "all"))}
              "one-sql" {:binding/for [:resolver "monitor_by_country"] :binding/source "db" :binding/effect :read
                         :binding/implementation {:query (str select " WHERE country = :country")}
                         :binding/input-map {:monitor/country "country"}
                         :binding/output-map (dissoc output-map :monitor/country)}}
   :selection {[:resolver "monitor_all"] "all-sql" [:resolver "monitor_by_country"] "one-sql"}})

(defn- with-db [f]
  (let [url (str "jdbc:sqlite:file:" (java.util.UUID/randomUUID) "?mode=memory&cache=shared")]
    (with-open [connection (java.sql.DriverManager/getConnection url)]
      (try
        ;; REAL answers Double (ClickHouse Float64 does the same); the temporal
        ;; columns are text, as a legacy query that casts with ::text returns them.
        (jdbc/execute! connection ["CREATE TABLE monitor (country TEXT, ratio REAL, snapshot TEXT, checked_at TEXT, local_at TEXT)"])
        (jdbc/execute! connection ["INSERT INTO monitor VALUES ('CA', 0.1, '2026-09-28', '2026-09-29 08:00:00+00', '2026-09-29 08:00:00'), ('US', NULL, NULL, '2026-09-29T08:00:00Z', NULL)"])
        (f url)
        (finally (sql/close-all-pools!))))))

(deftest a-2-0-sql-resolver-returns-the-declared-value-classes
  (with-db
    (fn [url]
      (let [candidate (compiler/compile-candidate vocabulary definitions {"sql" (spi/semantic-adapter "sql")}
                                                  {"db" {:jdbc_url url}} {:contract version/semantic-v2})
            q (fn [entity query]
                (engine/query (:compiled candidate) {:graph "monitor" :version 1} {:entity entity :query query}))]
        (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
        (when (:compiled candidate)
          (testing "a collection: Double becomes BigDecimal, date text LocalDate, offset text Instant"
            (let [answer (q {} [{:monitor/all [:monitor/country :monitor/ratio :monitor/snapshot :monitor/checked-at]}])
                  [ca us] (get-in answer [:engine/data :monitor/all])]
              (is (= :ok (:engine/status answer)) (pr-str answer))
              (is (= (bigdec "0.1") (:monitor/ratio ca)) "valueOf keeps 0.1, not its binary expansion")
              (is (= (java.time.LocalDate/parse "2026-09-28") (:monitor/snapshot ca)))
              (is (= (java.time.Instant/parse "2026-09-29T08:00:00Z") (:monitor/checked-at ca)) "PostgreSQL text form +00")
              (is (= (java.time.Instant/parse "2026-09-29T08:00:00Z") (:monitor/checked-at us)) "ISO-8601 Z")
              (is (nil? (:monitor/ratio us)) "null stays null")
              (is (empty? (:findings (result/validate-query-result vocabulary answer)))
                  "the 2.0 result check accepts the converted values")))
          (testing "a zoneless timestamp is not given a zone: the result check still refuses it"
            (let [answer (q {:monitor/country "CA"} [:monitor/ratio :monitor/local-at])]
              (is (= "2026-09-29 08:00:00" (get-in answer [:engine/data :monitor/local-at])))
              (is (= [[:monitor/local-at]]
                     (map :path (:findings (result/validate-query-result vocabulary answer))))))))))))

(deftest a-legacy-sql-resolver-keeps-its-jdbc-values
  (with-db
    (fn [url]
      ;; No `:semantic/output-types` on the resolver config: the dialect path.
      (let [read! (spi/compile-source {:type "sql" :query select
                                       :field_mapping (dissoc output-map :monitor/local-at)}
                                      (jdbc/get-datasource url)
                                      {:id "legacy" :result_type "list"
                                       :output [:monitor/country :monitor/ratio :monitor/snapshot :monitor/checked-at]})
            [ca] (read! {} {})]
        (is (instance? Double (:monitor/ratio ca)))
        (is (= "2026-09-28" (:monitor/snapshot ca)))
        (is (= "2026-09-29 08:00:00+00" (:monitor/checked-at ca)))))))

(deftest order-by-accepts-the-legacy-column-spelling
  ;; Legacy chart graphs documented `order_by` as a "mapped result column";
  ;; agents written against them still send one (owner 2026-09-29).
  (with-db
    (fn [url]
      (let [candidate (compiler/compile-candidate vocabulary definitions {"sql" (spi/semantic-adapter "sql")}
                                                  {"db" {:jdbc_url url}} {:contract version/semantic-v2})
            countries (fn [params]
                        (let [answer (engine/query (:compiled candidate) {:graph "monitor" :version 1}
                                                   {:entity {} :query [{(list :monitor/all params) [:monitor/country]}]})]
                          (if (= :ok (:engine/status answer))
                            (mapv :monitor/country (get-in answer [:engine/data :monitor/all]))
                            (:engine/status answer))))]
        (is (= ["CA" "US"] (countries {})) "default sort")
        (is (= ["US" "CA"] (countries {:order_by :monitor/country :sort_dir :desc})) "the attribute itself")
        (is (= ["US" "CA"] (countries {:order_by "country" :sort_dir "DESC"})) "the mapped column")
        (is (= ["US" "CA"] (countries {:order_by "monitor/country" :sort_dir "desc"})) "the attribute as text")
        (is (= ["CA" "US"] (countries {:order_by "ratio" :sort_dir "desc"})) "another allowed column; NULL sorts last in sqlite DESC")
        (is (= :rejected (countries {:order_by "snapshot"})) "a column that is not an allowed sort key is still refused")
        (is (= :rejected (countries {:order_by "nope"})) "an unknown spelling is still refused")))))
