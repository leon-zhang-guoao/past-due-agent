(ns open.atlas.integration.vertical-slice-test
  "B1 竖切的端到端：**真方言 → 真编译 → 真 SQL 连接器 → 真 SQLite → 真 EQL**。

   这是 B1 退出条件第一条「一条真实 EQL 端到端返回正确结果」的唯一证据。
   engine 的测试用假连接器、connector-sql 的测试用真库但不接 engine——两边
   各自全绿也证明不了这一条。理由与 D1.5 一字不差：不这么做，这条分界线就是
   全平台唯一没有被任何测试穿过的接缝。

   require `open.atlas.common.connector.sql` 就是装配：六个 SPI 方法在那一刻
   挂上去，engine 经 SPI 找到它们，没有别的接线。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]
            ;; 装配：require 即注册 "sql" 的六个 SPI 方法。
            [open.atlas.common.connector.sql :as sql]))

;; --- 真库 -------------------------------------------------------------------
;; `file:…?mode=memory&cache=shared`：`:memory:` 下连接池每取一条新连接就是
;; 一个空库。共享缓存下只要有一条连接活着库就在，故整个套件握住一条 keep-alive。

(def ^:private jdbc-url
  "jdbc:sqlite:file:atlas_b1_slice?mode=memory&cache=shared")

(defn- statements [sql]
  (->> (str/split-lines sql)
       (remove #(str/starts-with? (str/triml %) "--"))
       (str/join "\n")
       (#(str/split % #";"))
       (map str/trim)
       (remove str/blank?)))

(defn- with-db [f]
  (let [keep-alive (java.sql.DriverManager/getConnection jdbc-url)]
    (try
      (doseq [s (statements (slurp (io/resource "schema.sql")))]
        (jdbc/execute! keep-alive [s]))
      (f)
      (finally
        (sql/close-all-pools!)
        (.close keep-alive)))))

(use-fixtures :once with-db)

;; --- 装配 -------------------------------------------------------------------

(defn- model
  "读 fixtures/b1/graph.edn，把 source 的 `${B1_JDBC_URL}` 换成本次的库。

   直接写字面 URL 是**合法**的：sql-v1 说 `:jdbc_url`「只收 `${VAR}` 或
   **无凭证 URL**」，内存 SQLite 正是无凭证 URL——这条路径顺带把它测了。
   凭证形（user:pass@）的字面值仍然在写入端被拒，那有 connector-sql 自己的用例。"
  []
  (let [{:keys [model findings]} (dialect/read-dialect (slurp (io/resource "graph.edn")))]
    (assert (some? model) (str "fixture must read clean, got: " (pr-str findings)))
    (assoc-in model [:sources "cis-demo-db" :jdbc_url] jdbc-url)))

(defn- compiled [] (engine/compile-env (model)))

(defn- token [tier] {:tier tier :graph "demo/b1" :version :latest})

(defn- q [tier eql] (engine/query (compiled) (token tier) eql))

(defn- ok-data [res]
  (is (= :ok (:engine/status res)) (str "expected :ok, got " (pr-str res)))
  (:engine/data res))

;; --- ① 真实 EQL 端到端 ------------------------------------------------------

(deftest single-resolver-returns-real-rows
  (testing "ident + 点名属性，值来自真 SQLite"
    (let [data (ok-data (q :restricted
                           {:entity {:cis.master.vendor/vend_no 1001}
                            :query  [:cis.master.vendor/vend_name
                                     :cis.master.vendor/discontinued]}))]
      (is (= "Acme Industrial" (:cis.master.vendor/vend_name data)))
      (is (= "N" (:cis.master.vendor/discontinued data))))))

(deftest a-by-key-miss-on-the-real-database-is-an-answer-not-a-failure
  ;; 0.2 release note defect 11: `vend_no 999999` used to answer
  ;; `:engine.execution/failed :error/category :source` — «the source broke»
  ;; for a database that is perfectly healthy. sql `"single"` returns nil for
  ;; an empty result set (connector-sql conformance), and the engine reports
  ;; that as absent attributes plus `:engine/not-found`.
  (let [res (q :restricted {:entity {:cis.master.vendor/vend_no 999999}
                            :query  [:cis.master.vendor/vend_name]})]
    (is (= :ok (:engine/status res)) (pr-str res))
    (is (not (contains? (:engine/data res) :cis.master.vendor/vend_name))
        "absent, not nil-valued")
    (is (= {:anchor     [:cis.master.vendor/vend_no]
            :attributes [:cis.master.vendor/vend_name]}
           (:engine/not-found res)))))

(deftest list-resolver-returns-real-rows
  (testing "具名查询走 navigation 锚，行数与 schema.sql 一致"
    (let [data (ok-data (q :restricted
                           [{:cis.master.part/all_parts
                             [:cis.master.part/sku_no :cis.master.part/part_no]}]))
          parts (:cis.master.part/all_parts data)]
      (is (= 5 (count parts)))
      (is (= [5001 5002 5003 5004 5005] (mapv :cis.master.part/sku_no parts))))))

(deftest eql-param-narrows-on-the-anchor
  (testing "声明过的 eql-param 收窄，值走 prepared statement 绑定"
    (let [data (ok-data (q :restricted
                           [{(list :cis.master.part/all_parts
                                   {:prod_code "A"})
                             [:cis.master.part/sku_no]}]))]
      (is (= [5003 5004]
             (mapv :cis.master.part/sku_no (:cis.master.part/all_parts data)))))))

(deftest navigation-crosses-entities
  (testing "vendor → parts 跨 entity，link 与 navigation 都真的编译进了 index"
    (let [data (ok-data (q :restricted
                           {:entity {:cis.master.vendor/vend_no 1002}
                            :query  [:cis.master.vendor/vend_name
                                     {:cis.master.vendor/parts
                                      [:cis.master.part/part_no]}]}))]
      (is (= "Beta Components" (:cis.master.vendor/vend_name data)))
      (is (= ["BET-001" "BET-002"]
             (mapv :cis.master.part/part_no (:cis.master.vendor/parts data)))))))

(deftest batch-pagination-is-per-parent-end-to-end
  (testing "batch list 的 :limit 是 per-parent 窗口——1001 有 3 个 part，窗口 2 → 拿 2 个；
            全局 LIMIT 会让某个父项的行被别人吃掉，那正是这条要排除的"
    (let [data (ok-data (q :restricted
                           {:entity {:cis.master.vendor/vend_no 1001}
                            :query  [{:cis.master.vendor/parts
                                      [:cis.master.part/sku_no]}]}))]
      (is (= [5001 5002] (mapv :cis.master.part/sku_no (:cis.master.vendor/parts data)))
          "声明的 :order（sku_no asc）决定桶内顺序，窗口只是切片"))))

(deftest batch-alignment-never-drops-a-slot
  (testing "无 part 的 vendor 拿到空位而不是被丢掉——丢一位会把后面每个结果移到错误的父项"
    (let [data (ok-data (q :restricted
                           {:entity {:cis.master.vendor/vend_no 1003}
                            :query  [:cis.master.vendor/vend_name
                                     {:cis.master.vendor/parts
                                      [:cis.master.part/part_no]}]}))]
      (is (= "Gamma Supply (old)" (:cis.master.vendor/vend_name data)))
      (is (empty? (:cis.master.vendor/parts data))))))

;; --- ② 三种档位在同一张图上得到 D5.4 的三种处理 -----------------------------





;; --- ④ 连接器故障不携带业务数据 ---------------------------------------------

(deftest connector-failure-carries-no-business-data
  (testing "指向不存在的库：ex-data 无语句/URL/行值/凭证"
    (let [broken (assoc-in (model) [:sources "cis-demo-db" :jdbc_url]
                           "jdbc:sqlite:file:/nonexistent/dir/nope.db")
          res    (engine/query (engine/compile-env broken) (token :restricted)
                               {:entity {:cis.master.vendor/vend_no 1001}
                                :query  [:cis.master.vendor/vend_name]})
          dump   (pr-str res)]
      (is (not= :ok (:engine/status res)))
      (is (nil? (re-find #"SELECT|nonexistent|jdbc:" dump))
          (str "故障泄漏了语句或 URL: " dump)))))

;; --- ④ E2 源系统打标 --------------------------------------------------------

(deftest e2-statements-carry-the-request-id-down-to-the-real-database
  (testing "engine 的 `:trace` 一路走到真库：语句带注释、值仍是绑定值、
            占位符没被动过。这条**必须**在这里测——engine 单测里连接器是假的,
            connector-sql 单测里没有 engine，只有这一条穿过那道接缝。"
    (let [seen (atom [])
          res  (binding [spi/*statement-trace* #(swap! seen conj %)]
                 (engine/query (compiled) (token :restricted)
                               {:entity {:cis.master.vendor/vend_no 1001}
                                :query  [:cis.master.vendor/vend_name]}
                               {:trace {:request-id "req-e2-slice"
                                        :graph-id "6f1c9e40-0000-4000-8000-abcdef012345"}}))
          sqls (mapv :sql @seen)]
      (is (= "Acme Industrial" (:cis.master.vendor/vend_name (ok-data res)))
          "打了标的语句照样在真库上取回正确的行")
      (is (seq sqls))
      (is (every? #(str/starts-with?
                    % "/* open-atlas req=req-e2-slice graph=6f1c9e40-0000-4000-8000-abcdef012345 */ ")
                  sqls)
          (pr-str sqls))
      (is (every? #(= 1 (count (re-seq #"\?" %))) sqls)
          "占位符个数不变——注释只在 sql 串前面加字")
      (is (every? #(= [1001] (:values %)) @seen)
          "值仍走绑定，从来没有进过语句串"))))

(deftest e2-without-a-trace-the-statement-is-untouched
  (let [seen (atom [])]
    (binding [spi/*statement-trace* #(swap! seen conj %)]
      (q :restricted {:entity {:cis.master.vendor/vend_no 1001}
                      :query  [:cis.master.vendor/vend_name]}))
    (is (seq @seen))
    (is (every? #(not (str/includes? (:sql %) "open-atlas")) @seen)
        "没有 `:trace` 的调用（单测、CLI）与以前逐字节相同")))

;; --- 行级安全的运行期拒绝：源库上一条语句都不产生（E1 / D5.14 ③）------------

(defn- rowsec-compiled
  "同一份 b1 fixture，只在内存里给 `demo_vendor_by_id` 加一条 `:row-security`
   声明。**不改 fixture**（CLAUDE.md：fixture 是既成事实）——加在这里是因为
   b1 语料里没有任何 `:row-security`，而这条测试要证的正是「带声明时会怎样」。

   接线本来就是对的：`:vend_no` 既是 `:input`，语句也按它绑——所以这张图切得出
   版本（E1 的体检核对），运行期只剩「值到底在不在」这一件事。"
  []
  (engine/compile-env
   (update (model) :resolvers
           (fn [rs] (mapv #(cond-> %
                             (= "demo_vendor_by_id" (:id %))
                             (assoc :row-security {:attribute :cis.master.vendor/vend_no}))
                          rs)))))

(deftest a-null-row-security-input-reaches-no-statement-on-the-real-database
  (testing "`{:vend_no nil}`：pathom 判输入就绪按 `contains?`，会让 resolver 跑
            起来、SQLite 绑成 `= NULL` 返回 0 行——SQL 上碰巧安全。D5.14 ③ 说
            不靠这个巧合，所以门在这里就拒，**语句摘要为空**"
    (let [seen (atom [])
          r    (binding [spi/*statement-trace* #(swap! seen conj %)]
                 (engine/query (rowsec-compiled) (token :restricted)
                               {:entity {:cis.master.vendor/vend_no nil}
                                :query  [:cis.master.vendor/vend_name]}))]
      (is (= :rejected (:engine/status r)))
      (is (= :rowsec.input/null (:code r)))
      (is (= [] @seen) "真库上一条语句都没发")
      (is (nil? (spi/statement-audit-summary @seen))
          "所以审计的 `:statements` 摘要是 nil——不是一个空壳（那会被读成
           「发了但没记下」）"))))

(deftest the-same-resolver-still-answers-with-a-real-value
  (testing "对照：值在，门放行，真库真的被查了——否则上一条测的是一扇焊死的门"
    (let [seen (atom [])
          r    (binding [spi/*statement-trace* #(swap! seen conj %)]
                 (engine/query (rowsec-compiled) (token :restricted)
                               {:entity {:cis.master.vendor/vend_no 1001}
                                :query  [:cis.master.vendor/vend_name]}))]
      (is (= :ok (:engine/status r)))
      (is (= 1 (count @seen)))
      (is (= 1 (:count (spi/statement-audit-summary @seen)))))))
