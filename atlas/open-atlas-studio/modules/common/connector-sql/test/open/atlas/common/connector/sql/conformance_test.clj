(ns open.atlas.common.connector.sql.conformance-test
  "sql-v1「conformance 要点」逐条对账，跑在 fixtures/b1 的真 SQLite 上：

     「占位符与 `:params` 恰好互相覆盖，多、少均拒绝；`\"single\"` 无行返回
      `nil` 不是错误；`\"list\"` 空返回 `[]`；batch 对齐不丢位；超时强制且
      故障 `ex-data` 不含语句与行值。」"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql :as sql]
            [open.atlas.common.connector.sql.db :as db]))

(use-fixtures :once db/with-fixture-db)

;; fixtures/b1/graph.edn 的四条 resolver，拆成 SPI 的两个入参。
;; source-config = source 条目 + 操作块；:jdbc_url 在端到端用例里由注入的
;; DataSource 顶替（连接是装配处的事，不是本用例要测的东西）。

(def vendor-by-id
  {:source {:type "sql"
            :query (str "SELECT vend_no, vend_name, discontinued, contact_email, "
                        "entry_by, entry_date FROM vendors WHERE vend_no = :vend_no")}
   :resolver {:id "demo_vendor_by_id"
              :input [:cis.master.vendor/vend_no]
              :output [:cis.master.vendor/vend_name :cis.master.vendor/discontinued
                       :cis.master.vendor/contact_email :cis.master.vendor/entry_by
                       :cis.master.vendor/entry_date]
              :result_type "single"}})

(def all-parts
  {:source {:type "sql"
            :query "SELECT sku_no, part_no, short_desc, ave_cost, prod_code FROM parts"}
   :resolver {:id "demo_all_parts"
              :input []
              :eql-params {:prod_code {:attribute :cis.master.part/prod_code :op :eq}}
              :limit {:default 50 :max 500}
              :order {:allowed [:cis.master.part/sku_no :cis.master.part/part_no]
                      :default [:cis.master.part/sku_no :asc]}
              :output [{:cis.master.part/all_parts
                        [:cis.master.part/sku_no :cis.master.part/part_no
                         :cis.master.part/short_desc :cis.master.part/ave_cost
                         :cis.master.part/prod_code]}]
              :result_type "list"}})

(def part-by-id
  {:source {:type "sql"
            :batch {:child-fk "sku_no" :parent-key :cis.master.part/sku_no
                    :placeholder "sku_nos"}
            :query (str "SELECT sku_no, part_no, short_desc, ave_cost, prod_code, "
                        "vend_no FROM parts WHERE sku_no IN (:sku_nos)")}
   :resolver {:id "demo_part_by_id"
              :input [:cis.master.part/sku_no]
              :output [:cis.master.part/part_no :cis.master.part/short_desc
                       :cis.master.part/ave_cost :cis.master.part/prod_code
                       :cis.master.part/vend_no]
              :result_type "single"}})

(def parts-by-vendor
  {:source {:type "sql"
            :query "SELECT sku_no, part_no, ave_cost FROM parts WHERE vend_no = :vend_no"}
   :resolver {:id "demo_parts_by_vendor"
              :input [:cis.master.vendor/vend_no]
              :limit {:default 100 :max 500}
              :output [{:cis.master.vendor/parts
                        [:cis.master.part/sku_no :cis.master.part/part_no
                         :cis.master.part/ave_cost]}]
              :result_type "list"}})

;; vendor 的一对多做成 batch 形——与 fixtures/b1 的 `demo_parts_by_vendor` 同形
;; （那条 resolver 已是 batch list，`:limit {:default 2 :max 5}` 让 per-parent
;; 窗口可观察）。这里保留自己的 `:limit`，因为本文件测的是连接器行为而不是
;; 那一份 fixture 的具体数字；窗口用例在下面单独声明它要的窗口。
(def parts-by-vendor-batch
  {:source (assoc (:source parts-by-vendor)
                  :batch {:child-fk "vend_no" :parent-key :cis.master.vendor/vend_no
                          :placeholder "vend_nos"}
                  :query (str "SELECT sku_no, part_no, ave_cost, vend_no FROM parts "
                              "WHERE vend_no IN (:vend_nos)")
                  :field_mapping {:cis.master.part/sku_no "sku_no"
                                  :cis.master.part/part_no "part_no"
                                  :cis.master.part/ave_cost "ave_cost"})
   :resolver (:resolver parts-by-vendor)})

(defn- reader [{:keys [source resolver]}]
  (spi/compile-source source db/*ds* resolver))

(defn- batch-reader [{:keys [source resolver]}]
  (spi/compile-batch-source source db/*ds* resolver))

;; ---------------------------------------------------------------------------

(deftest single-with-rows-returns-one-mapped-map
  (let [r ((reader vendor-by-id) {:cis.master.vendor/vend_no 1001} nil)]
    (is (= "Acme Industrial" (:cis.master.vendor/vend_name r)))
    (is (= "N" (:cis.master.vendor/discontinued r)))
    (is (= "jgao" (:cis.master.vendor/entry_by r)))
    (testing "恒等映射缺省（D2.1）：没写 :field_mapping 也按 local name = 列名解析"
      (is (= (set (:output (:resolver vendor-by-id))) (set (keys r)))))))

(deftest single-with-no-rows-returns-nil-not-an-error-not-an-empty-map
  (testing "sql-v1 conformance：「`\"single\"` 无行返回 `nil` 不是错误」"
    (let [r ((reader vendor-by-id) {:cis.master.vendor/vend_no 999999} nil)]
      (is (nil? r))
      (is (not= {} r) "`{}` 是被契约点名禁止的顶替形状"))))

(deftest sql-null-column-is-nil-not-a-dropped-key
  (testing "vendor 1003 的 contact_email 是 NULL——键在，值为 nil；
            少一个键会让调用方分不清「没值」与「没供」"
    (let [r ((reader vendor-by-id) {:cis.master.vendor/vend_no 1003} nil)]
      (is (contains? r :cis.master.vendor/contact_email))
      (is (nil? (:cis.master.vendor/contact_email r))))))

(deftest list-returns-a-vector-and-empty-is-empty-vector
  (let [read (reader all-parts)]
    (testing "有行"
      (let [rows (read {} nil)]
        (is (vector? rows))
        (is (= 5 (count rows)))
        (testing ":order 的 :default 生效（sku_no asc）"
          (is (= [5001 5002 5003 5004 5005] (mapv :cis.master.part/sku_no rows))))))
    (testing "sql-v1 conformance：「`\"list\"` 空返回 `[]`」"
      (let [rows (read {} {:prod_code "ZZZ"})]
        (is (= [] rows))
        (is (vector? rows))
        (is (not (nil? rows)))))))

(deftest eql-param-narrows-on-the-anchor
  (testing "D2.8：`:eql-params` 只做锚上收窄"
    (let [rows ((reader all-parts) {} {:prod_code "A"})]
      (is (= [5003 5004] (mapv :cis.master.part/sku_no rows)))))
  (testing ":limit 被 :max 夹住，:order_by 只认 :allowed 里的属性"
    (let [rows ((reader all-parts) {} {:limit 2 :order_by :cis.master.part/part_no
                                       :sort_dir :desc})]
      (is (= 2 (count rows)))
      (is (= ["BET-002" "BET-001"] (mapv :cis.master.part/part_no rows))))))

;; ---------------------------------------------------------------------------
;; 值永不字符串插值
;; ---------------------------------------------------------------------------

(deftest values-are-bound-never-interpolated
  (testing "principles：「值永远不字符串插值进 SQL——`:query` 用具名占位按
            prepared statement 执行」。含引号/分号的参数值不改变语句语义：
            它只是一个匹配不到任何行的值，而不是第二条语句。"
    (let [read (reader vendor-by-id)]
      (doseq [evil ["1001; DROP TABLE vendors--"
                    "1001' OR '1'='1"
                    "'); DELETE FROM parts; --"]]
        (is (nil? (read {:cis.master.vendor/vend_no evil} nil))
            (str "注入形状的值必须只是「查无此行」：" evil)))
      (testing "语句与数据都还在——没有第二条语句被执行过"
        (is (= "Acme Industrial"
               (:cis.master.vendor/vend_name (read {:cis.master.vendor/vend_no 1001} nil))))
        (is (= 5 (count ((reader all-parts) {} nil))))))))

(deftest eql-param-values-are-bound-too
  (testing "收窄的值同样走绑定，不进语句串"
    (is (= [] ((reader all-parts) {} {:prod_code "A' OR '1'='1"})))))

;; ---------------------------------------------------------------------------
;; batch
;; ---------------------------------------------------------------------------

(deftest batch-single-never-loses-a-slot
  (testing "`IN (:sku_nos)` 展开为 N 个绑定；缺行的父项得 nil，位置不动"
    (let [run (batch-reader part-by-id)
          out (run [{:cis.master.part/sku_no 5001}
                    {:cis.master.part/sku_no 404404}
                    {:cis.master.part/sku_no 5003}]
                   nil)]
      (is (= 3 (count out)))
      (is (= "ACM-100" (:cis.master.part/part_no (nth out 0))))
      (is (nil? (nth out 1)))
      (is (= "ACM-900" (:cis.master.part/part_no (nth out 2))))))
  (testing "输入乱序时结果跟着输入走，而不是跟着 source 的行序"
    (let [out ((batch-reader part-by-id)
               [{:cis.master.part/sku_no 5005} {:cis.master.part/sku_no 5001}] nil)]
      (is (= ["BET-002" "ACM-100"] (mapv :cis.master.part/part_no out))))))

(deftest batch-list-empty-slot-is-empty-vector
  (testing "fixtures/b1 的现成空位用例：「vendor 1003 刻意没有 part」"
    (let [out ((batch-reader parts-by-vendor-batch)
               [{:cis.master.vendor/vend_no 1001}
                {:cis.master.vendor/vend_no 1003}
                {:cis.master.vendor/vend_no 1002}]
               nil)]
      (is (= 3 (count out)))
      (is (= 3 (count (nth out 0))))
      (is (= [] (nth out 1)) "没有行的父项得 []，绝不丢位")
      (is (= 2 (count (nth out 2))))
      (testing "行落在正确的父项上——丢一位会把后面每个结果静默移位"
        (is (= #{"ACM-100" "ACM-220" "ACM-900"}
               (set (map :cis.master.part/part_no (nth out 0)))))
        (is (= #{"BET-001" "BET-002"}
               (set (map :cis.master.part/part_no (nth out 2)))))))))

(deftest batch-with-no-matching-parents-at-all
  (let [out ((batch-reader part-by-id) [{:cis.master.part/sku_no 1}
                                        {:cis.master.part/sku_no 2}] nil)]
    (is (= [nil nil] out))))

(deftest batch-on-a-connector-without-batch-support-is-a-configuration-error
  (testing "principles：落到没有 compile-batch-source 的连接器上是配置错误。
            sql **有** batch 能力，所以这里验的是能力查询本身。"
    (is (true? (spi/supports-batch? "sql")))
    (is (true? (spi/supports-batch? "SQL")))))

;; --- per-parent 窗口跑在真 SQL 上 -------------------------------------------
;; sql-v1「分页在 batch 上是 per-parent 窗口，不下推」。种子里 vendor 1001 有
;; 3 个 part、1002 有 2 个、1003 没有——一次调用就能同时看到「切窗」「不切窗」
;; 「空位」三种情形。

(def ^:private parts-by-vendor-windowed
  (assoc-in parts-by-vendor-batch [:resolver :limit] {:default 2 :max 5}))

(def ^:private parts-by-vendor-ordered
  (assoc-in parts-by-vendor-windowed [:resolver :order]
            {:allowed [:cis.master.part/sku_no]
             :default [:cis.master.part/sku_no :asc]}))

(deftest batch-pagination-is-per-parent-on-a-real-database
  (testing "每个父项各自取前 N 条，全局 LIMIT 的截断不会发生"
    (let [out ((batch-reader parts-by-vendor-ordered)
               [{:cis.master.vendor/vend_no 1001}
                {:cis.master.vendor/vend_no 1002}
                {:cis.master.vendor/vend_no 1003}]
               {})]
      (is (= 3 (count out)) "永不丢位")
      (is (= [5001 5002] (mapv :cis.master.part/sku_no (nth out 0)))
          "1001 有 3 条，窗口 2 → 前两条")
      (is (= [5004 5005] (mapv :cis.master.part/sku_no (nth out 1)))
          "1002 的两条一条不少——它没有被 1001 吃掉配额")
      (is (= [] (nth out 2)) "1003 无 part，仍是 []"))))

(deftest batch-window-order-comes-from-the-declared-order
  (testing "桶内顺序 = 声明的 :order（全局 ORDER BY + 分组保序），无需 PARTITION BY"
    (let [desc (assoc-in parts-by-vendor-ordered [:resolver :order :default]
                         [:cis.master.part/sku_no :desc])
          out  ((batch-reader desc) [{:cis.master.vendor/vend_no 1001}] {})]
      (is (= [5003 5002] (mapv :cis.master.part/sku_no (first out)))
          "倒序下窗口取到的是最大的两个 sku——切窗切的是已排好序的桶"))))

(deftest batch-window-respects-the-authors-max
  (testing "调用方要多少都不越过作者签的 :max（与非 batch 路径同一个夹取）"
    (let [out ((batch-reader parts-by-vendor-ordered)
               [{:cis.master.vendor/vend_no 1001}]
               {:limit 99})]
      (is (= 3 (count (first out)))
          "要 99 被夹到 :max 5，而 1001 只有 3 条 → 全给"))))

(deftest batch-window-offset-is-inside-each-parent
  (testing "offset 是桶内偏移"
    (let [out ((batch-reader parts-by-vendor-ordered)
               [{:cis.master.vendor/vend_no 1001}
                {:cis.master.vendor/vend_no 1002}]
               {:limit 1 :offset 1})]
      (is (= [5002] (mapv :cis.master.part/sku_no (nth out 0))))
      (is (= [5005] (mapv :cis.master.part/sku_no (nth out 1)))
          "1002 跳过自己的第一条，而不是跳过合并行集的第一条"))))

;; ---------------------------------------------------------------------------
;; 语句回执（spi/*statement-trace*）
;; ---------------------------------------------------------------------------

(deftest a-bound-trace-receives-the-statement-that-actually-ran
  (let [seen (atom [])]
    (binding [spi/*statement-trace* #(swap! seen conj %)]
      ((reader vendor-by-id) {:cis.master.vendor/vend_no 1001} nil))
    (is (= 1 (count @seen)) "一次执行一条回执")
    (let [{:keys [sql values rows resolver-id source-type elapsed-ms]} (first @seen)]
      (is (re-find #"(?i)select" sql))
      (is (= [1001] values) "值与语句分开，从没插值进串")
      (is (= 1 rows))
      (is (= "sql" source-type))
      (is (string? resolver-id))
      (is (nat-int? elapsed-ms)))))

(deftest a-batch-statement-is-traced-with-its-in-list-values
  (let [seen (atom [])]
    (binding [spi/*statement-trace* #(swap! seen conj %)]
      ((batch-reader parts-by-vendor-batch)
       [{:cis.master.vendor/vend_no 1001} {:cis.master.vendor/vend_no 1003}] nil))
    (let [{:keys [sql values]} (first @seen)]
      (is (re-find #"(?i)in \(" sql) "一条语句取回所有父项")
      (is (= [1001 1003] values)))))

(deftest an-unbound-trace-is-a-no-op
  (is (= nil spi/*statement-trace*) "缺省不接任何东西")
  (is (some? ((reader vendor-by-id) {:cis.master.vendor/vend_no 1001} nil))
      "没绑的时候查询照常"))

(deftest the-trace-never-carries-the-source-config
  (let [seen (atom nil)]
    (binding [spi/*statement-trace* #(reset! seen %)]
      ((reader vendor-by-id) {:cis.master.vendor/vend_no 1001} nil))
    (let [text (pr-str @seen)]
      (is (not (re-find #"jdbc:" text)) "jdbc_url 与凭证住在 source-config 里，不许进来")
      (is (= :ok (:outcome @seen)))
      (is (boolean? (:slow-query @seen)))
      (is (= 1 (:attempt @seen)) "doc 107 §107.8：第几次尝试，同 api 连接器一份形状")
      (is (= #{:sql :values :elapsed-ms :rows :resolver-id :source-type :outcome
               :slow-query :attempt}
             (set (keys @seen)))
          "键是封闭的——多一个键就是多一条没人审过的外泄通道"))))

(deftest a-sink-that-throws-does-not-take-the-query-with-it
  (binding [spi/*statement-trace* (fn [_] (throw (ex-info "sink is broken" {})))]
    (is (= "Acme Industrial"
           (:cis.master.vendor/vend_name
            ((reader vendor-by-id) {:cis.master.vendor/vend_no 1001} nil)))
        "观测手段不许把被观测的查询搞挂")))

(deftest offset-capability-matches-the-contract
  ;; sql-v1「能力」：offset ✓。The engine's gate refuses `:offset` for any type
  ;; that does not declare it, so this declaration is what keeps paging working
  ;; for sql (8.17).
  (is (true? (spi/supports-offset? sql/source-type))))
