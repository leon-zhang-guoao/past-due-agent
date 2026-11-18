(ns open.atlas.integration.business-chain-test
  "demo **业务链**的端到端：committed 模型 → 真编译 → 真 SQL 连接器 →
   真 SQLite → 真 EQL。

   ── 这条链是什么 ──────────────────────────────────────────────────────────

     CPO ─→ MSO ─┬─ 正常履约 ───────────────────→ SSO   (sso.int_ref → MSO,  type 3)
                 └─ dropship → VPO ─(供应商直发)─→ SSO   (sso.int_ref → VPO,  type 2)

     CPO  客户的采购订单            MSO  我们的销售合同（全量订货行）
     VPO  向供应商下的采购单        SSO  每次发货一张（履约进度）

   ── 模型从哪来（2026-08-24 改）────────────────────────────────────────────

   本 ns 曾经在测试里**现场跑一遍 B2 转换管线**（语料 → scope → additions →
   signoff），理由是「快照会悄悄过期」。那条理由随管线一起失效了：转换器与语料
   已经退休（模型迁进 `fixtures/demo/model.edn`，见那里的 README），**没有上游
   可过期**——模型自己就是事实源，不是谁的缓存。

   于是这里改读那份 committed 模型，并且**照样让它过一遍 B0**：committed 的
   东西更该验，因为不再有任何一步会替它验。

   随管线一起删掉的还有一条「补充确实进了产物、且与语料分得开」的用例：模型
   现在是**一个文件**，「这一段来自语料、那一段来自补充」这个区分本身没有了
   ——一条判断已经不存在的区分的测试，留着只会让人以为那个区分还在。

   ── 库 ─────────────────────────────────────────────────────────────────────

   `fixtures/demo/demo-db/`：合成数据，刻意小而有结构。三种履约状态各有足够样本、
   两条履约路径都有样本、还有 5 张**混合模式**的 MSO。见那边的 README。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]
            ;; 装配：require 即注册 "sql" / "derived" 的 SPI 方法。
            [open.atlas.common.connector.sql :as sql]
            [open.atlas.common.connector.derived :as derived]))

;; --- 真库 -------------------------------------------------------------------
;; 同 vertical-slice：共享缓存的内存库，整个套件握住一条 keep-alive 连接。

(def ^:private jdbc-url
  "jdbc:sqlite:file:atlas_b2_chain?mode=memory&cache=shared")

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
      (doseq [r ["demo-db/schema.sql" "demo-db/seed.sql"]
              s (statements (slurp (io/resource r)))]
        (jdbc/execute! keep-alive [s]))
      (f)
      (finally
        (sql/close-all-pools!)
        (.close keep-alive)))))

(use-fixtures :once with-db)

;; --- 装配：模型 + 库 --------------------------------------------------------

(def ^:private product
  "demo 模型（`fixtures/demo/model.edn`），source 指到本次的临时库。

   **模型自己去过一遍 B0**：它是 committed 的文件，不是刚算出来的产物——
   committed 的东西更该验，因为没有任何一步会替它验（dialect-v1 §7）。"
  (delay
   (let [{m :model fs :findings} (dialect/read-dialect (slurp (io/resource "model.edn")))]
     (assert (some? m) (str "fixtures/demo/model.edn must pass B0, got: " (pr-str fs)))
     (assoc-in m [:sources "cis-demo-db" :jdbc_url] jdbc-url))))

(def ^:private compiled (delay (engine/compile-env @product)))

(defn- token [tier] {:tier tier :graph "demo/b2" :version :latest})
(defn- q [tier eql] (engine/query @compiled (token tier) eql))

(defn- ok-data [res]
  (is (= :ok (:engine/status res)) (str "expected :ok, got " (pr-str res)))
  (:engine/data res))

;; 数据里的锚点。取自 fixtures/demo/demo-db/seed.sql，由 generate.py 确定性产出。
(def ^:private cpo-with-two-msos 210000001)
(def ^:private mso-fully-shipped 143100002)   ; 100%，仓库发货，挂 3 张 SSO
(def ^:private mso-partial-mixed 143100003)   ; 部分发货 56/135，**混合模式**
(def ^:private mso-unshipped     143100001)   ; 0%
(def ^:private sso-dropship      160500001)   ; int_ref_type 2 → VPO 32700101
(def ^:private sso-warehouse     160500009)   ; int_ref_type 3 → MSO 143100002
(def ^:private vpo-dropship       32700101)

;; --- ① CPO → MSO 导航 -------------------------------------------------------

(deftest cpo-navigates-to-its-msos
  (testing "一张客户采购订单能看到它开出的全部销售合同（一对多）"
    (let [data (ok-data (q :restricted
                           {:entity {:cis.cpo.cpo_header/order_no cpo-with-two-msos}
                            :query  [:cis.cpo.cpo_header/cpo_cust_no
                                     :cis.cpo.cpo_header/cpo_date
                                     {:cis.cpo.cpo_header/msos
                                      [:cis.sales.mso/order_no]}]}))]
      (is (= 120001 (:cis.cpo.cpo_header/cpo_cust_no data)))
      (is (= "2025-01-11" (str (:cis.cpo.cpo_header/cpo_date data))))
      (is (= [143100001 143100016]
             (sort (mapv :cis.sales.mso/order_no (:cis.cpo.cpo_header/msos data))))
          "这张 CPO 开出两张 MSO —— CPO→MSO 是一对多"))))

(deftest cpo-line-items
  (testing "CPO 的行项：客户订了什么"
    (let [lines (:cis.cpo.cpo_header/cpo_details
                 (ok-data (q :restricted
                             {:entity {:cis.cpo.cpo_header/order_no cpo-with-two-msos}
                              :query  [{:cis.cpo.cpo_header/cpo_details
                                        [:cis.cpo.cpo_detail/cpo_line_seq
                                         :cis.cpo.cpo_detail/cpo_sku_no
                                         :cis.cpo.cpo_detail/cpo_line_qty]}]})))]
      (is (= 5 (count lines)))
      (is (= [1 2 3 4 5] (mapv :cis.cpo.cpo_detail/cpo_line_seq lines)))
      (is (= [33 59 21 13 40] (mapv :cis.cpo.cpo_detail/cpo_line_qty lines))))))

;; --- ② MSO → 全量订货行 -----------------------------------------------------

(deftest mso-navigates-to-its-order-details
  (testing "MSO 的行项就是合同的全量订货行"
    (let [lines (:cis.sales.mso_fulfilment/order_details
                 (ok-data (q :restricted
                             {:entity {:cis.sales.mso/order_no mso-fully-shipped}
                              :query  [{:cis.sales.mso_fulfilment/order_details
                                        [:cis.sales.mso_order_detail/line_no
                                         :cis.sales.mso_order_detail/sku_no
                                         :cis.sales.mso_order_detail/order_qty
                                         :cis.sales.mso_order_detail/ship_qty]}]})))]
      (is (= 7 (count lines)))
      (is (= [1 2 3 4 5 6 7] (mapv :cis.sales.mso_order_detail/line_no lines)))
      (is (= (mapv :cis.sales.mso_order_detail/order_qty lines)
             (mapv :cis.sales.mso_order_detail/ship_qty lines))
          "这一张是 100% 发完的"))))

;; --- ③ MSO → 多张 SSO（一对多）---------------------------------------------

(deftest mso-navigates-to-many-ssos
  (testing "一张 MSO 挂多张 SSO —— 每次发货一张"
    (let [ssos (:cis.sales.mso_fulfilment/ssos
                (ok-data (q :restricted
                            {:entity {:cis.sales.mso/order_no mso-fully-shipped}
                             :query  [{:cis.sales.mso_fulfilment/ssos
                                       [:cis.sales.sso/order_no :cis.sales.sso/ship_date]}]})))]
      (is (= 3 (count ssos)) "一对多：这张合同分三次发完")
      (is (= [160500009 160500010 160500011]
             (sort (mapv :cis.sales.sso/order_no ssos)))))))

;; --- ④ 履约进度：订了多少、发了多少 -----------------------------------------

(defn- fulfilment
  "从一张 MSO 的订货行算出 [订货量 已发量]。这就是「履约进度」——现有 demo 数据
   里 `order_qty` 恒等于 `ship_qty`，这个数算出来永远是 100%，于是演示不出东西。"
  [order-no]
  (let [lines (:cis.sales.mso_fulfilment/order_details
               (ok-data (q :restricted
                           {:entity {:cis.sales.mso/order_no order-no}
                            :query  [{:cis.sales.mso_fulfilment/order_details
                                      [:cis.sales.mso_order_detail/order_qty
                                       :cis.sales.mso_order_detail/ship_qty]}]})))]
    [(reduce + (map :cis.sales.mso_order_detail/order_qty lines))
     (reduce + (map :cis.sales.mso_order_detail/ship_qty lines))]))

(deftest three-fulfilment-states-are-all-reachable
  (testing "未发货 / 部分发货 / 全部发货，三种都算得出来"
    (is (= [126 0] (fulfilment mso-unshipped))     "未发货：0%")
    (is (= [135 56] (fulfilment mso-partial-mixed)) "部分发货：56/135，既不是 0 也不是 100")
    (let [[o s] (fulfilment mso-fully-shipped)]
      (is (= o s) "全部发货：100%")
      (is (pos? o)))))

;; --- ⑤ dropship：SSO → VPO，而且**不会**错接指向 MSO 的那些 SSO -------------
;;
;; `sso.int_ref_no` 是多态引用，指向什么由 `int_ref_type` 决定。把它建成 link 的话，
;; `:condition` 是纯文字槽、不进入执行语义（D2.3），条件根本不会被执行——指向 MSO
;; 的 SSO 会被一并接到 VPO 上，得到**静默的错误关联**。
;;
;; ⚠️ **下面的行为断言单独拿出来是没有牙的，这一点要说清楚。**
;;
;; demo 库的号段是**互不重叠**的（MSO 143.1M / VPO 32.7M / SSO 160.5M / CPO 210M）。
;; 于是「仓库发货的 SSO 查不到 VPO」这件事有**两个各自独立的机制**在保证：
;;   (a) SQL 里 `AND int_ref_type = 2` 的类型过滤；
;;   (b) 号段不重叠——`int_ref_no` 是个 143.1M 的数，`vpos.order_no` 里根本没有它。
;; 实测过：把 (a) 从 SQL 里删掉，下面的行为断言**仍然全绿**，因为 (b) 顶上了。
;;
;; 两道防线是好事（号段分开本来就是数据自带的一道自检），但它意味着**行为断言
;; 证明不了那条硬约束**。真正守住「不许用 link」的是后面两条**结构断言**：
;; 模型里不许有以 `sso/int_ref_no` 为端点的 link，且那两条 join resolver 的 SQL
;; 必须带着类型过滤。想「顺手用 link 简化一下」的人会被那两条顶回来。

(deftest dropship-sso-navigates-to-its-vpo
  (testing "供应商直发的 SSO 能找到它那张 VPO，并顺着 VPO 找回 MSO"
    (let [data (ok-data (q :restricted
                           {:entity {:cis.sales.sso/order_no sso-dropship}
                            :query  [:cis.purchase.vpo/order_no
                                     :cis.purchase.vpo/from_acct_no
                                     :cis.purchase.vpo/int_ref_no]}))]
      (is (= vpo-dropship (:cis.purchase.vpo/order_no data)))
      (is (= 700001 (:cis.purchase.vpo/from_acct_no data)) "货是这家供应商直接发的")
      (is (= mso-partial-mixed (:cis.purchase.vpo/int_ref_no data))
          "顺着 VPO 找回它履约的那张 MSO —— dropship 这条链是完整的"))))

(deftest warehouse-sso-is-not-reachable-through-the-dropship-path
  (testing "指向 MSO 的 SSO 走 dropship 路径什么都查不到（行为；两道防线共同保证，
            见本节抬头——单独这一条证明不了类型过滤在执行）"
    (let [res (q :restricted
                 {:entity {:cis.sales.sso/order_no sso-warehouse}
                  :query  [:cis.purchase.vpo/order_no]})]
      (is (nil? (get-in res [:engine/data :cis.purchase.vpo/order_no]))
          (str "仓库发货的 SSO 不该接到任何 VPO 上，得到: " (pr-str res)))))

  (testing "反过来同理：dropship 的 SSO 走「正常履约」路径也什么都查不到"
    (let [res (q :restricted
                 {:entity {:cis.sales.sso/order_no sso-dropship}
                  :query  [:cis.sales.mso/issue_date]})]
      (is (nil? (get-in res [:engine/data :cis.sales.mso/issue_date]))
          (str "dropship 的 SSO 的 int_ref 指的是 VPO 不是 MSO，得到: " (pr-str res))))))

(deftest polymorphic-int-ref-is-never-modelled-as-a-link
  (testing "**这一条才是那条硬约束的守卫。** 模型里不许有任何以 `sso/int_ref_no`
            为端点的 link：link 的 `:condition` 是纯文字槽、不进入执行语义（D2.3），
            机械翻译出来的 alias resolver 会无条件成立，把指向 MSO 的 SSO 也接到
            VPO 上——一个**查得出东西**的错误关联，没有人会来报障。"
    (let [ends (into #{} (mapcat (juxt :from :to)) (:links @product))]
      (is (not (contains? ends :cis.sales.sso/int_ref_no))
          (str "`sso.int_ref_no` 是多态引用，只能建成显式 join resolver。"
               " 想用 link 简化的话，先读 additions 文件抬头那一节与 D2.3。"))
      (is (contains? ends :cis.sales.mso/int_ref_no)
          "对照：MSO→CPO 是**非多态**的普通外键，它用 link 是对的"))))

(deftest polymorphic-join-resolvers-carry-the-type-filter-in-sql
  (testing "两条 join resolver 的 SQL 必须带着 `int_ref_type` 过滤。
            类型过滤是写死在 SQL 里的字面量，它**只能活在 resolver 里**——
            link 没有 SQL 可写。把过滤删掉，这一条会红。"
    (let [by-id (into {} (map (juxt :id identity)) (:resolvers @product))
          sql-of #(get-in by-id [% :source :query])]
      (doseq [[id needle] [["demo_sso_vpo" "int_ref_type = 2"]
                           ["demo_sso_mso" "int_ref_type = 3"]
                           ["demo_mso_ssos" "int_ref_type = 3"]
                           ["demo_mso_dropship_vpos" "int_ref_type = 3"]
                           ["demo_cpo_msos" "int_ref_type = 4"]]]
        (is (str/includes? (str (sql-of id)) needle)
            (str "resolver " id " 的 SQL 必须带 `" needle "`"))))))

(deftest warehouse-sso-navigates-to-its-mso
  (testing "正常履约的 SSO 找得到它履约的那张 MSO（同一条路径的阳性用例）"
    (let [data (ok-data (q :restricted
                           {:entity {:cis.sales.sso/order_no sso-warehouse}
                            :query  [:cis.sales.mso/order_no :cis.sales.mso/issue_date]}))]
      (is (= mso-fully-shipped (:cis.sales.mso/order_no data)))
      (is (= "2025-02-15" (str (:cis.sales.mso/issue_date data)))))))

;; --- ⑥ 混合模式：一张 MSO 同时挂着两条履约路径 -------------------------------

(deftest mixed-mode-mso-shows-both-fulfilment-paths
  (testing "同一张 MSO 既有仓库发货的 SSO、又有 dropship 的 VPO —— 真实业务里
            最容易出错的形态"
    (let [data (ok-data (q :restricted
                           {:entity {:cis.sales.mso/order_no mso-partial-mixed}
                            :query  [{:cis.sales.mso_fulfilment/ssos
                                      [:cis.sales.sso/order_no]}
                                     {:cis.sales.mso_fulfilment/dropship_vpos
                                      [:cis.purchase.vpo/order_no
                                       :cis.purchase.vpo/from_acct_no]}]}))
          ssos (:cis.sales.mso_fulfilment/ssos data)
          vpos (:cis.sales.mso_fulfilment/dropship_vpos data)]
      (is (= [160500012] (mapv :cis.sales.sso/order_no ssos))
          "仓库发货的那一张")
      (is (= [vpo-dropship] (mapv :cis.purchase.vpo/order_no vpos))
          "供应商直发的那一张")
      (is (= [700001] (mapv :cis.purchase.vpo/from_acct_no vpos))))))

;; --- ⑦ 档位：:restricted 的成本字段对不够档 token 完全不存在 -----------------


(deftest null-milestones-are-the-answer
  (testing "未发货的单据仓库作业五个时点全空——**NULL 本身是演示素材**,
            不是缺数据。三种履约状态在这五个时点上长得不一样。"
    (let [ops (fn [no] (ok-data (q :restricted {:entity {:cis.sales.mso/order_no no}
                                                :query  [:cis.sales.mso/pick_date
                                                         :cis.sales.mso/qc_date
                                                         :cis.sales.mso/label_date
                                                         :cis.sales.mso/ship_date
                                                         :cis.sales.mso/bol_date]})))]
      (is (every? nil? (vals (select-keys (ops mso-unshipped)
                                          [:cis.sales.mso/pick_date :cis.sales.mso/ship_date
                                           :cis.sales.mso/bol_date])))
          "0% 的那张：还没拣货，也就没有后面的一切")
      (let [full (ops mso-fully-shipped)]
        (is (every? some? (vals (select-keys full [:cis.sales.mso/pick_date
                                                   :cis.sales.mso/qc_date
                                                   :cis.sales.mso/ship_date
                                                   :cis.sales.mso/bol_date])))
            "100% 的那张：五个时点都有")
        (let [ds (mapv #(str (get full %)) [:cis.sales.mso/pick_date :cis.sales.mso/qc_date
                                            :cis.sales.mso/label_date :cis.sales.mso/ship_date
                                            :cis.sales.mso/bol_date])]
          (is (= ds (sort ds))
              "顺序恒为 pick < qc < label < ship < bol —— 时间线的顺序是这一族的语义")))
      (let [partial* (ops mso-partial-mixed)]
        (is (some? (:cis.sales.mso/ship_date partial*)))
        (is (nil? (:cis.sales.mso/bol_date partial*))
            "部分发货：发了货，提单还没开完")))))

;; --- ⑧ 词条（D2.26）：描述来自注册表，本地一份都没有 -----------------------

(deftest bound-attributes-take-their-description-from-the-term-registry
  (testing "绑定即封走语义：属性上只剩 `:term`,
            `:description` / `:label` / `:format` / `:synonyms` 一个都不留（D2.26）"
    (let [m     @product
          bound (into (sorted-map)
                      (for [[a ad] (:attributes m) :when (:term ad)] [a ad]))]
      ;; 78 → 86 / 21 → 23：D2.25 组移除的收尾——组注册表「定义写一次」那半
      ;; 价值改由词条接住，`audit_info`（6 个 entity）与 `ship_to_info`（2 个）
      ;; 各建一条词条。另外五个派生属性**各只有一个绑定，故不建词条**：
      ;; 一份只有一个用户的定义，去重收益是零。
      (is (= 86 (count bound)) "86 条绑定：26 条审计字段 + 8 条派生 + 52 条业务")
      (is (= 23 (count (:terms m))) "23 个词条：5 条审计 + 2 条派生 + 16 条业务")
      (is (= (set (keys (:terms m))) (into #{} (map :term) (vals bound)))
          "注册表与绑定**两边对齐**：没有一条注册了没人用（那是一句没人在听的
           定义），也没有一条绑了却没注册（那是悬空引用）")
      (doseq [[a ad] bound]
        (is (nil? (:description ad)) (str a " 绑了词条就不该再有本地描述"))
        (is (nil? (:label ad)) (str a))
        (is (nil? (:format ad)) (str a))
        (is (nil? (:synonyms ad)) (str a))
        (is (contains? (:terms m) (:term ad))
            (str a " 的词条必须先注册后使用")))))

  (testing "业务词条把**三个历史名**并成一个概念（D2.26 的另一半）"
    (let [m  @product
          of (fn [tid] (into (sorted-set)
                             (for [[a ad] (:attributes m) :when (= tid (:term ad))] a)))]
      (is (= #{:cis.cpo.cpo_header/cpo_cust_no
               :cis.master.customer/cust_no
               :cis.master.customer_bank/cust_no
               :cis.master.customer_credit/cust_no
               :cis.purchase.vpo/to_acct_no
               :cis.sales.mso/to_acct_no
               :cis.sales.sso/to_acct_no}
             (of "cis.party/customer_no"))
          "`cust_no` / `to_acct_no` / `cpo_cust_no` 说的是同一件事：我们卖给谁")
      (is (= #{:cis.master.part/vend_no
               :cis.master.vendor/vend_no
               :cis.purchase.vpo/from_acct_no}
             (of "cis.party/vendor_no"))
          "`from_acct_no` 在 demo 模型里只出现一次、永远不成候选——它是靠作者通道
           绑到候选通道定义的那个词条上的，那正是这条跨通道能力存在的理由")
      (is (= 6 (count (of "cis.catalog/sku_no")))
          "六个 sku_no。它们曾经类型不齐，D2.27 于是一条候选都不出")
      (is (= #{:integer} (into #{} (map #(get-in m [:attributes % :type]) (of "cis.catalog/sku_no"))))
          "类型一致是词条能成立的前提（D2.27）；当年由 scope.edn 的 :correct-attributes 修齐,
           今天是 demo 模型里的既成事实")
      (is (not-any? #(contains? (get (:terms m) %) :type)
                    ["cis.party/customer_no" "cis.party/vendor_no" "cis.catalog/sku_no"])
          "词条本身**不携带 :type**：类型是每一列的物理事实，不是概念的断言")))

  (testing "同名不一定同义，异名不一定异义——两个方向都由人判"
    (let [m @product]
      (is (= "cis.party/vendor_no"   (get-in m [:attributes :cis.purchase.vpo/from_acct_no :term])))
      (is (= "cis.party/customer_no" (get-in m [:attributes :cis.purchase.vpo/to_acct_no :term]))
          "同一张 VPO 上，`from_acct_no` 与 `to_acct_no` 名字只差一个词，是两个概念")
      (is (nil? (get-in m [:attributes :cis.sales.sso/cust_no]))
          "`sso/cust_no` 与同表的 `to_acct_no` 是同一个值，已由 scope.edn 排除——
           给冗余数据编一个 :term-context 去绕开 D2.27，就是在造第二套语义")))

  (testing "词条的定义**送得到消费端**：catalog 带注册表，不是只带一个 ID"
    (let [m   @product
          cat (engine/catalog @compiled :public)
          a   (get-in cat [:attributes :cis.master.part/sku_no])]
      (is (= "cis.catalog/sku_no" (:term a)))
      (is (nil? (:description a)) "本地描述被摘掉了——注册表不在场就等于没有描述")
      (is (= "物料号" (get-in cat [:terms "cis.catalog/sku_no" :label])))
      ;; **「注册表跟着 catalog 走、不跟着模型走」**（engine 的 build-catalog：
      ;; 只登记本档位 catalog 里真有属性绑着的词条）。2026-08-24 起这条规则
      ;; 在真模型上**有了命中**：`:listed? false`（D2.28）把审计成员移出 catalog,
      ;; 于是**全部绑定都未列出**的那几个词条也随之不在 catalog 里——
      ;; 一个指向「对这个 token 而言不存在的属性」的定义，送出去只会让调用方
      ;; 对着一条查不到东西的引用推理。模型里它们仍在（下面那条断言钉住）。
      (let [gone (into (sorted-set) (remove (set (keys (:terms cat)))) (keys (:terms m)))]
        (is (= #{"common.audit/detail_row_version"
                 "common.audit/updated_at"
                 "common.audit/updated_by"}
               gone)
            "恰是这三个：它们的绑定只落在 mso/vpo 的 update_* 与 u_version 上，
             而那几个属性全部 `:listed? false`")
        (doseq [t gone]
          (is (contains? (:terms m) t) (str t " 该留在模型里——未列出的是属性，不是定义"))))
      (is (contains? (:terms cat) "common.audit/created_by")
          "对照：`created_by` 还在——它另有列出的绑定（cpo 与 mso 行项上的录入人）")
      (is (every? (into #{} (keep (comp :term val)) (:attributes cat))
                  (keys (:terms cat)))
          "注册表里不该有一条是本档位没有属性在用的——那是在告诉低档 token
           某个概念存在")))

  (testing "码值表也只写一份：`:values` 是词条携带字段"
    (let [m @product
          ot (get-in m [:terms "cis.doc/order_type"])]
      (is (= #{1 2 8} (set (keys (:values ot))))
          "1 销售单 / 2 采购单 / 8 欠单 —— 码值表记的是**这个域里存在哪些码**，
           不是这批样本里出现过哪些：demo 数据里一条 8 都没有，写漏它模型就在说
           「不存在这种单据」")
      (is (= 4 (count (for [[_ ad] (:attributes m) :when (= "cis.doc/order_type" (:term ad))] 1))))
      (is (not-any? #(contains? (val %) :values)
                    (filter #(:term (val %)) (:attributes m)))
          "绑了词条的属性本地一个 `:values` 都不许留（D2.26）——四张表共用一份码表")))

  (testing "直发单在数据里真的走得通（`inv_type` 的码值表不是一纸空文）"
    (let [rows (ok-data (q :restricted
                           [{[:cis.sales.mso/order_no 143100003]
                             [{:cis.sales.mso_fulfilment/order_details
                               [:cis.sales.mso_order_detail/line_no
                                :cis.sales.mso_order_detail/inv_type]}]}]))
          lines (get-in rows [[:cis.sales.mso/order_no 143100003] :cis.sales.mso_fulfilment/order_details])]
      (is (seq lines))
      (is (every? #(= 1 (:cis.sales.mso_order_detail/inv_type %)) lines)
          "143100003 是 MIXED：同一行既有仓库发的一半也有直发的一半，`inv_type`
           表达不了「半直发」，于是照实写 1——不替这个码值发明一层它没有的语义"))
    (let [rows (ok-data (q :restricted
                           [{[:cis.sales.mso/order_no 143100005]
                             [{:cis.sales.mso_fulfilment/order_details
                               [:cis.sales.mso_order_detail/inv_type]}]}]))
          lines (get-in rows [[:cis.sales.mso/order_no 143100005] :cis.sales.mso_fulfilment/order_details])]
      (is (seq lines))
      (is (every? #(= 98 (:cis.sales.mso_order_detail/inv_type %)) lines)
          "143100005 属于 DROPSHIP_ONLY：整单由供应商直发，每一行都是 98")))

  (testing "描述**只写一次**：`entry_id` 出现在 8 个 entity 上，定义只有一份"
    (let [m       @product
          created (for [[a ad] (:attributes m)
                        :when (= "common.audit/created_by" (:term ad))]
                    a)]
      (is (= 8 (count created)))
      (is (= 8 (count (into #{} (map namespace created))))
          "8 个不同的 entity")
      (is (contains? (set created) :cis.cpo.cpo_header/cpo_entry_id)
          "历史命名被词条屏蔽：这一列在库里叫 cpo_entry_id，概念仍是「录入人」")
      (is (string? (get-in m [:terms "common.audit/created_by" :description])))))

  (testing "同一个词条、两个当事人：定义共用一份，**语境各是各的**（D2.27）"
    (let [m @product
          vpo (get-in m [:attributes :cis.purchase.vpo/ship_to_info])
          mso (get-in m [:attributes :cis.sales.mso/ship_to_info])]
      (is (= "cis.order/ship_to_info" (:term vpo) (:term mso))
          "共用一条词条 ID——「定义写一次」，这是组注册表移除后由词条接住的那半")
      (is (re-find #"采购单" (:term-context vpo)))
      (is (re-find #"销售单" (:term-context mso)))
      (is (not= (:term-context vpo) (:term-context mso))
          "语境不同才有意义：同一个概念、不同的当事人")
      (is (every? #(nil? (% vpo)) [:label :description :format :synonyms])
          "`:term-context` **不在** term-carried-keys 里——语境是本地的，绑定不摘它;
           语义那几个键则全被摘掉，只有词条一个事实源")
      (is (= "收货地址" (get-in m [:terms "cis.order/ship_to_info" :label])))))

  (testing "词条**不携带 `:type`** —— 类型是这一列的物理事实，不是概念的语义"
    (let [m @product]
      (doseq [[_ t] (:terms m)]
        (is (nil? (:type t)))
        (is (nil? (:unit t)))))))

(deftest terms-do-not-change-what-a-query-returns
  (testing "词条是字典，不进线上 EQL：绑定之后照样按**具体属性** keyword 取值。
            取的是**列出的**绑定属性——审计族已标 `:listed? false`（D2.28），
            它们的可查性由下面 unlisted-attributes-are-absent-yet-still-derivable 单管。"
    (let [data (ok-data (q :restricted
                           {:entity {:cis.sales.mso/order_no mso-fully-shipped}
                            :query  [:cis.sales.mso/ship_to_name :cis.sales.mso/ship_to_city]}))]
      (is (string? (:cis.sales.mso/ship_to_name data)))
      (is (string? (:cis.sales.mso/ship_to_city data))))))

(deftest unlisted-attributes-are-absent-yet-still-derivable
  (testing "D2.28：`:listed? false` 的属性对**所有档位**都不在 catalog 里，
            点名它与点名一个不存在的属性**逐字相同**"
    (doseq [t [:public :sensitive :restricted]]
      (let [cat (engine/catalog @compiled t)]
        (is (not (contains? (:attributes cat) :cis.sales.mso/entry_id))
            (str t " 档不该看得见 entry_id"))
        (is (contains? (:attributes cat) :cis.sales.mso/order_no)
            (str t " 档的对照：同 entity 的列出属性仍在")))
      (let [unlisted (q t {:entity {:cis.sales.mso/order_no mso-fully-shipped}
                           :query  [:cis.sales.mso/entry_id]})
            ghost    (q t {:entity {:cis.sales.mso/order_no mso-fully-shipped}
                           :query  [:cis.sales.mso/no_such_attribute_at_all]})]
        (is (= :engine.catalog/unknown-attribute (:code unlisted)))
        (is (= (pr-str ghost) (pr-str (assoc unlisted :attribute
                                             :cis.sales.mso/no_such_attribute_at_all)))
            "两条应答除属性名外逐字相同——未列出与不存在不可区分"))))

  (testing "**它仍然算得出来**：未列出不是「不可用」，是「不在目录里」。
            derived resolver 拿它当 `:input`，派生属性照常返回（D2.28 明写
            规划器仍可把它当中间量，D5.3 的 :planner-derived 那一支）"
    (let [data (ok-data (q :restricted
                           {:entity {:cis.sales.mso/order_no mso-fully-shipped}
                            :query  [:cis.sales.mso/audit_info]}))]
      (is (string? (:cis.sales.mso/audit_info data)))
      (is (re-find #"entry " (:cis.sales.mso/audit_info data))
          "派生值确实由那几个未列出的成员算出来的"))))


;; --- ⑨ metric（D2.21）：收口径定义，不收执行引擎 ---------------------------

(deftest metrics-are-defined-and-their-dimensions-are-registered
  (testing "两个 metric 进了产物，`:entity` 的 namespace 与 `:dimensions` 的属性
            都**已登记**（D2.21 的两条体检）"
    (let [m (:metrics @product)]
      (is (= ["sales_by_customer_month" "spend_by_vendor_month"] (sort (keys m))))
      (doseq [[id mt] m]
        (is (contains? (:namespaces @product) (:entity mt))
            (str id " 的 :entity namespace 必须存在"))
        (is (seq (:dimensions mt)))
        (doseq [d (:dimensions mt)]
          (is (contains? (:attributes @product) d)
              (str id " 的维度 " d " 必须是已登记属性")))
        (is (= :month (:grain mt)) "D2.7 词表")
        (is (= :duckdb-spill/v1 (get-in mt [:expression :contract]))
            "指向版本化执行契约，不是一句模糊的「这是 SQL」")
        (let [t (get-in mt [:expression :text])]
          (is (string? t))
          (is (nil? (re-find #"(?i)\bfrom\b|;" t))
              "duckdb-spill/v1：`:text` 是可嵌入的聚合表达式，不含 FROM 与多语句")))))

  (testing "同名 ≠ 同义：收入侧与支出侧是**两个** metric，不是一个加一个方向维度"
    (let [m (:metrics @product)]
      (is (= "cis.sales.mso" (:entity (m "sales_by_customer_month"))))
      (is (= "cis.purchase.vpo" (:entity (m "spend_by_vendor_month"))))
      (is (not= (get-in m ["sales_by_customer_month" :expression :text])
                (get-in m ["spend_by_vendor_month" :expression :text]))))))
