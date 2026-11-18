(ns open.atlas.server.mcp.results-test
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]
            [open.atlas.server.mcp.impl.results :as results]
            [open.atlas.server.mcp.impl.tools :as tools])
  (:import [java.nio.file Files]))

(defn call [ctx tool args]
  ((:handler (first (filter #(= tool (:name %)) tools/declarations))) ctx args))
(def query [{:product/index [:product/id :product/brand]}])
(defn compiled [n]
  (engine/compile-env
    (:model (dialect/read-dialect
      (pr-str
        {:namespaces {"product" {:label "Products"}}
         :attributes {:product/id {:type :string :identity? true :label "Reference"}
                      :product/brand {:type :string :label "Brand"}
                      :product/index {:cardinality :one-to-many :target "product" :label "Catalog"}}
         :resolvers [{:id "catalog" :label "Catalog" :input [] :output query
                      :result_type "list" :limit {:default n :max n}
                      :source {:type "static"
                               :data (mapv (fn [i] {:product/id (str i)
                                                    :product/brand (apply str (repeat 60 "x"))})
                                           (range n))}}]})))))

(deftest complete-large-result-is-analyzed-without-refetch
  (let [c (compiled 6000) calls (atom 0) version (atom 1) allowed (atom true)
        ctx {:credential "owner-a"
             :runtime {:pinned (fn [_ _] (if @allowed {:graph-id "se" :version @version}
                                            {:findings [{:code :revoked}]}))
                       :answer (fn [_ _ q] (swap! calls inc)
                                 (assoc (engine/query c q) :env/version 1))}}
        store (results/create-store)]
    (binding [results/*store* store]
      (try
        (is (= :engine.limit/exceeded (:code (engine/query c query))))
        (let [r (call ctx "execute_eql" {:query (pr-str query)})
              handle (:result_handle r)
              args {:result_handle handle :input_row_limit 6000
                    :sql "SELECT count(*) AS n, count(DISTINCT id) AS unique_n FROM data"}]
          (is (string? handle) (pr-str r))
          (is (nil? (:result r)))
          (is (= true (:result-complete r)))
          (is (= 6000 (:row-count (call ctx "analyze_result" args))))
          (is (= [["6000" "6000"]] (:rows (call ctx "analyze_result" args))))
          (is (= 1 @calls) "both analyses reuse the saved result")
          (is (:error (call ctx "analyze_result" (assoc args :input_row_limit 5000))))
          (is (:error (call (assoc ctx :credential "owner-b") "analyze_result" args)))
          (swap! version inc)
          (is (:error (call ctx "analyze_result" args)))
          (reset! version 1)
          (reset! allowed false)
          (is (:error (call ctx "analyze_result" args)))
          (reset! allowed true)
          (is (:error (call ctx "analyze_result" (assoc args :query (pr-str query)))))
          (is (:error (call ctx "analyze_result" (assoc args :sql "SELECT * FROM read_csv_auto('/etc/passwd')")))))
        (is (= :engine.limit/exceeded (:code (engine/query c query))))
        (with-redefs [results/max-bytes 100]
          (is (:error (call ctx "execute_eql" {:query (pr-str query)}))))
        (let [bounded (assoc-in c [:engine/bundle :param-decls :product/index :source-collection]
                               {:bounds {:max-items 1 :max-response-bytes 1000000}})]
          (is (= :rejected
                 (:engine/status (binding [engine/*result-byte-budget* results/max-bytes]
                                   (engine/query bounded query))))))
        (finally (results/close! store))))))

(deftest small-result-compatibility-and-explicit-retention
  (let [store (results/create-store)
        ctx {:runtime {:pinned (fn [_ args] {:result-owner (:owner args) :graph-id (:graph args) :version 1})
                       :answer (fn [_ _ _] {:engine/status :ok :engine/data {:product/id "001"}})}}]
    (binding [results/*store* store]
      (try
        (is (= {:product/id "001"} (:result (call ctx "execute_eql" {:query "[:product/id]"}))))
        (let [r (call ctx "execute_eql" {:query "[:product/id]" :retain_result true :owner "alice" :graph "se"})
              sql {:result_handle (:result_handle r) :sql "SELECT id FROM data" :owner "alice" :graph "se"}]
          (is (= [["001"]] (:rows (call ctx "analyze_result" sql))))
          (is (:error (call ctx "analyze_result" (assoc sql :graph "another"))))
          (is (:error (call ctx "analyze_result" (assoc sql :owner "bob"))))
          (is (= [["001"]] (:rows (call ctx "analyze_result" {:query "[:product/id]" :sql "SELECT id FROM data"})))))
        (finally (results/close! store))))))

(deftest file-lifecycle-and-storage-bounds
  (let [store (results/create-store)]
    (binding [results/*store* store]
      (try
        (let [r (results/retain! "a" {:result {:rows [{:id "001"}]}})
              handle (:result_handle r)
              path (:path (get @(:entries store) handle))]
          (is (Files/exists path (make-array java.nio.file.LinkOption 0)))
          (is (:error (results/fetch! "a" "../../etc/passwd")))
          (is (:error (results/fetch! "b" handle)))
          (with-redefs [results/now-ms (constantly (inc (:expires-at r)))]
            (is (:error (results/fetch! "a" handle)))
            (is (not (Files/exists path (make-array java.nio.file.LinkOption 0))))))
        (with-redefs [results/max-bytes 10]
          (is (:error (results/retain! "a" {:result (repeat 100 "x")})))
          (is (empty? @(:entries store))))
        (with-redefs [results/total-bytes 1]
          (is (:error (results/retain! "a" {:result [{:id 1}]}))))
        (with-redefs [results/max-files 1]
          (is (:result_handle (results/retain! "a" {:result [{:id 1}]})))
          (is (:error (results/retain! "a" {:result [{:id 2}]}))))
        (finally (results/close! store))))))

(deftest ident-batches-remain-separate-rows-after-retention
  (let [store (results/create-store)
        ctx {:runtime {:pinned (fn [_ _] {:result-owner "a" :graph-id "se" :version 1})
                       :answer (fn [_ _ _]
                                 {:engine/status :ok
                                  :engine/data {[:product/id "01"] {:product/brand "APC"}
                                                [:product/id "02"] {:product/brand "Schneider"}}})}}]
    (binding [results/*store* store]
      (try
        (let [r (call ctx "execute_eql" {:query "[{[:product/id \"01\"] [:product/brand]} {[:product/id \"02\"] [:product/brand]}]"
                                        :retain_result true})
              a (call ctx "analyze_result" {:result_handle (:result_handle r)
                                            :sql "SELECT id, brand FROM data ORDER BY id"})]
          (is (= 2 (:row-count a)))
          (is (= [["01" "APC"] ["02" "Schneider"]] (:rows a))))
        (finally (results/close! store))))))


(deftest isolated-ident-errors-survive-direct-and-handle-analysis
  (let [store (results/create-store)
        data {[:p/id "good"] {:p/detail "good" :p/brand "Fixture"}
              [:p/id "bad"] {}}
        outcomes [{:path [[:p/id "bad"]] :status :source-error :http-status 400}]
        reply {:engine/status :ok :engine/data data :engine/partial? true
               :engine/row-outcomes outcomes :engine/issues [{:code :engine.execution/batch-input-failed}]}
        ctx {:runtime {:pinned (fn [_ _] {:result-owner "fixture" :graph-id "test" :version 1})
                       :answer (fn [& _] reply)}}
        sql "SELECT id, _atlas_fetch_status FROM data ORDER BY id"]
    (binding [results/*store* store]
      (try
        (let [retained (call ctx "execute_eql" {:query "[:p/id]" :retain_result true})
              handle-analysis (call ctx "analyze_result" {:result_handle (:result_handle retained) :sql sql})
              direct (call ctx "analyze_result" {:query "[:p/id]" :sql sql})]
          (is (= [["bad" "source-error"] ["good" "returned"]] (:rows handle-analysis)))
          (is (= (:rows direct) (:rows handle-analysis)))
          (is (true? (:partial retained)))
          (is (true? (:partial direct)))
          (is (true? (get-in handle-analysis [:source :partial])))
          (is (= :partial (get-in handle-analysis [:analysis :source-completeness])))
          (is (= 1 (get-in handle-analysis [:analysis :failed-inputs]))))
        (finally (results/close! store))))))

(deftest failures-cannot-be-disguised-as-business-status-columns
  (doseq [field [:p/_atlas_fetch_status :_atlas_fetch_status :p/-atlas-http-status :p/_ATLAS_FETCH_STATUS]]
    (let [ctx {:runtime {:answer (fn [& _]
                                  {:engine/status :ok :engine/partial? true
                                   :engine/data {:p/index [{:p/id "bad" field "returned"}]}
                                   :engine/row-outcomes [{:path [:p/index 0] :http-status 400}]})}}
          r (call ctx "analyze_result" {:query "[:p/id]" :sql "SELECT _atlas_fetch_status FROM data"})]
      (is (string? (:error r)))
      (is (nil? (:rows r))))))

(deftest governed-metrics-refuse-partial-input
  (let [ctx {:runtime {:pinned (fn [& _]
                                {:model {:metrics {"count" {:entity "p" :expression {:text "count(id)"}}}}
                                 :catalog {:joins {:p/index "p"} :attributes {:p/id {:entity "p"}}}})
                       :answer (fn [& _] {:engine/status :ok :engine/partial? true
                                         :engine/data {:p/index [{:p/id "bad"}]}
                                         :engine/issues [{:code :engine.execution/batch-input-failed}]})}}
        r (call ctx "compute_metric" {:metric "count"})]
    (is (true? (:partial r)))
    (is (string? (:error r)))
    (is (nil? (:rows r)))))


(deftest single-entity-failure-keeps-a-usable-analysis-row
  (let [ctx {:runtime {:answer (fn [& _]
                                {:engine/status :ok :engine/partial? true :engine/data {:p/id "bad"}
                                 :engine/row-outcomes [{:path [] :http-status 404}]})}}
        r (call ctx "analyze_result" {:query "[:p/id]"
                                      :sql "SELECT id, _atlas_fetch_status, _atlas_http_status FROM data"})]
    (is (= [["bad" "source-error" "404"]] (:rows r)))
    (is (= 1 (get-in r [:analysis :failed-inputs])))))

(deftest schema-discovery-reuses-the-handle-and-preserves-failures
  (let [store (results/create-store) calls (atom 0)
        ctx {:runtime {:pinned (fn [_ _] {:result-owner "schema-owner" :graph-id "test" :version 1})
                       :answer (fn [& _] (swap! calls inc)
                                 {:engine/status :ok :engine/partial? true
                                  :engine/data {:p/index [{:p/id "private-good" :p/price 10 :p/active true}
                                                         {:p/id "private-bad"}]}
                                  :engine/row-outcomes [{:path [:p/index 1] :http-status 400}]})}}]
    (binding [results/*store* store]
      (try
        (let [r (call ctx "execute_eql" {:query "[:p/id]" :retain_result true})
              args {:result_handle (:result_handle r)}
              discovered (call ctx "analyze_result" args)
              cols (into {} (map (juxt :name identity)) (:columns discovered))]
          (is (= "data" (:table discovered)))
          (is (= 2 (:row-count discovered)))
          (is (= {:name "price" :from "p/price" :type "BIGINT" :type-source :inferred} (cols "price")))
          (is (= "BOOLEAN" (:type (cols "active"))))
          (is (= "VARCHAR" (:type (cols "_atlas_fetch_status"))))
          (is (= 1 (get-in discovered [:analysis :failed-inputs])))
          (is (true? (get-in discovered [:source :partial])))
          (is (nil? (:rows discovered)))
          (is (not (.contains (pr-str discovered) "private-good")))
          (is (= (:columns discovered) (:columns (call ctx "analyze_result" (assoc args :sql nil)))))
          (is (:error (call ctx "analyze_result" (assoc args :sql "  "))))
          (is (:error (call ctx "analyze_result" (assoc args :input_row_limit 1))))
          (is (= 1 @calls)))
        (finally (results/close! store))))))

(deftest schema-discovery-keeps-authorization-and-byte-limits
  (let [ctx {:runtime {:answer (fn [& _] {:engine/status :ok :engine/data {:p/id "value"}})}}]
    (with-redefs [open.atlas.server.mcp.impl.duck/input-max-bytes 1]
      (is (:error (call ctx "analyze_result" {:query "[:p/id]"})))))
  (let [ctx {:runtime {:pinned (fn [& _] {:findings [{:code :revoked}]})}}]
    (is (:error (call ctx "analyze_result" {:result_handle "unavailable"})))))

(deftest empty-schema-discovery-does-not-invent-columns
  (let [ctx {:runtime {:answer (fn [& _] {:engine/status :ok :engine/data {:p/index []}})}}
        r (call ctx "analyze_result" {:query "[{:p/index [:p/id]}]"})]
    (is (= "data" (:table r)))
    (is (= 0 (:row-count r)))
    (is (= [] (:columns r)))))

(deftest retained-result-expiry-is-fixed-despite-reads
  (let [store (results/create-store)]
    (binding [results/*store* store]
      (try
        (let [r (results/retain! "ttl-owner" {:result {:p/id "1"}})
              expiry (:expires-at r)]
          (is (= results/ttl-ms (- expiry (:captured-at r))))
          (with-redefs [results/now-ms (constantly (dec expiry))]
            (is (= expiry (:expires-at (results/fetch! "ttl-owner" (:result_handle r))))))
          (with-redefs [results/now-ms (constantly expiry)]
            (is (:error (results/fetch! "ttl-owner" (:result_handle r))))))
        (finally (results/close! store))))))
