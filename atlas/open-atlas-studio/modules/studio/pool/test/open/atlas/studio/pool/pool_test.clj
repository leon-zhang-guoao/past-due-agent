(ns open.atlas.studio.pool.pool-test
  "B4.1 的三条退出条件各有一个守门测试：

   1. `demo-roundtrip-test`      —— 删掉 store、导入最后一次导出，精确重建出同一模型；
   2. `action-rejection-test`  —— 每个 action 的拒绝都是结构化的且不含配置值；
   3. `revision-conflict-test` —— `expected_revision` 冲突被正确拒绝。

   **装料是 demo 模型**（`fixtures/demo/`，207 属性 / 23 词条 / 12 张码值表）。
   玩具数据证不出「池装得下」——三个属性的模型里没有跨 entity 的词条绑定，
   也没有 35 个 resolver 指着同一个 source，而这两样正是 delete 的引用检查与
   投机校验要判的东西。

   `fixtures/demo/model.edn` 是这个模型的**家**：一份 committed 的方言文件,
   测试期 `dialect->objects` 装进 datahike（`load-pool!`），与池自己的
   `import-pool!` 走同一条路。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.pool :as pool]
            [taoensso.timbre :as timbre]))

;; Datahike 每次 transact/connect 都打 DEBUG。测试输出要能一眼看出红绿，
;; 而 86 条 schema 与 312 个对象的事务日志会把它淹掉。
(timbre/set-min-level! :warn)

;; ---------------------------------------------------------------------------
;; 装料
;; ---------------------------------------------------------------------------

(defn- tmp-dir
  "file 后端的 store 目录（`export-import-rebuilds-the-pool-test` 要一个真路径）。"
  [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix (into-array java.nio.file.attribute.FileAttribute []))))

(defonce ^{:doc "demo 模型。`fixtures/demo/model.edn` 是它的家（见那里的 README）。"}
  demo-model
  (delay (edn/read-string (slurp (io/resource "model.edn")))))

(def ^:dynamic *conn* nil)
(def ^:dynamic *cfg* nil)

(defn- load-pool!
  "把方言模型装进空池。装料走 `import-pool!` 而不是 304 次 `execute-action`：
   裁定 ② 让每次 action 校验整池，装料时逐个跑等于把同一份校验做 304 遍，
   而**装料本身不是被测行为**——被测的是 action 的拒绝与导出的往返。"
  [conn model]
  (let [objs (pool/dialect->objects model)]
    (pool/import-pool! conn {:pool/version 1
                             :pool/objects (mapv #(assoc % :pool/revision 1 :pool/workspace "demo") objs)})))

(defn with-loaded-pool [f]
  (let [cfg (pool/config {:backend :mem :id (str (gensym "pool"))})]
    (pool/delete-pool! cfg)
    (let [conn (pool/open-pool! cfg)]
      (let [r (load-pool! conn @demo-model)]
        (assert (empty? (:findings r)) (pr-str (:findings r))))
      (binding [*conn* conn *cfg* cfg]
        (try (f)
             (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))))

(defn stage-unsigned-named-query!
  "把一个**无锚且未签**的 list resolver 摆进池里，制造一条 `:curated` 候选。

   demo 模型自己不再提供这样的对象：D2.13 的执法在切版本，而一个切不出版本的
   demo 是错的装料，所以那里的无锚 list 全都签过字了。借装料里「恰好有人没签」
   来喂签字通道的测试，本来也是把被测行为压在一个与它无关的事实上。"
  []
  (let [r (pool/execute-action
           *conn* {:workspace "demo" :action :create :type :resolver :id "unsigned_all_parts"
                   :entry {:id "unsigned_all_parts"
                           :input [] :output [:cis.master.part/sku_no]
                           :result_type "list" :limit {:default 50 :max 500}
                           :source {:type "sql" :query "SELECT sku_no FROM parts"}}
                   :expected_revision 0})]
    (assert (empty? (:findings r)) (pr-str (:findings r)))))

(use-fixtures :each with-loaded-pool)

;; ---------------------------------------------------------------------------
;; 共用断言
;; ---------------------------------------------------------------------------

(def ^:private config-values
  "demo 模型里真实存在的配置值。拒绝里出现任何一个都是泄漏（B4 退出条件 2 /
   finding-v1 §5）。`:jdbc_url` 是 source 条目上的连接串，与凭证同类。"
  ["jdbc:sqlite" "cis-demo.db" "data/cis-demo"])

(defn- rejected!
  "断言：这次 action 一个字节都没写，且拒绝是结构化的、不含配置值。
   返回 findings 供调用方再看 code。"
  [res]
  (is (nil? (:revision res)) "拒绝的 action 不得返回 revision")
  (let [fs (:findings res)]
    (is (seq fs) "拒绝必须有 finding，不许静默失败")
    (is (every? pool/finding? fs) "每条 finding 过 finding-v1 §2 封闭 schema")
    (is (some #(= :error (:severity %)) fs))
    (let [s (binding [*print-namespace-maps* false] (pr-str fs))]
      (doseq [v config-values]
        (is (not (str/includes? s v))
            (str "finding 泄漏了配置值 " (pr-str v) "：" s))))
    fs))

(defn- codes-of [fs] (set (map :code fs)))

;; ---------------------------------------------------------------------------
;; 退出条件 1：删掉 store、导入最后一次导出，精确重建出同一模型
;; ---------------------------------------------------------------------------

(deftest demo-load-test
  (testing "demo 模型整份装得进池，逐个概念数得出来"
    (let [objs (group-by :pool/type (pool/objects (pool/db *conn*)))]
      ;; 175 → 188：D2.25 组移除后 13 个组实例各变成一个 derived `*_info` 属性。
      ;; 188 → 195：7 个 `all_*` collection 声明（tiers.md 2026-08-26 裁定）。
      ;; 195 → 207：D2.29 把 19 条 navigation 并进属性注册表，其中 7 条与既有的
      ;; `all_*` collection 属性合成同一条 join 属性（195 - 7 + 19 = 207）。
      (is (= 207 (count (:attribute objs))) "207 属性（188 标量 + 19 join）")
      ;; 21 → 23：`audit_info` 与 `ship_to_info` 两条派生属性的词条。
      (is (= 23  (count (:term objs)))      "23 词条")
      (is (= 12  (count (filter #(:values (:pool/entry %)) (:attribute objs))))
          "12 张码值表")))
  (testing "池拼回的方言与装进去的那一份是同一个模型"
    (let [back (pool/as-dialect (pool/db *conn*))
          norm #(update-vals (select-keys % [:links :resolvers :mutations])
                             (fn [v] (set v)))]
      ;; 向量型顶层键在池里按身份定序（model/id-order 的注释），
      ;; 故先按集合比内容，再逐个 map 型顶层键比。
      (is (= (norm @demo-model) (norm back)))
      (is (= (dissoc @demo-model :links :resolvers :mutations)
             (dissoc back :links :resolvers :mutations)))))
  (testing "拼回的方言过 B0"
    (is (some? (:model (dialect/read-dialect
                        (binding [*print-namespace-maps* false]
                          (pr-str (pool/as-dialect (pool/db *conn*))))))))))

(deftest demo-roundtrip-test
  (testing "退出条件 1：导出 → 删掉 store → 导入 → 再导出，两次导出逐字节相同"
    (let [export-1  (pool/export-pool (pool/db *conn*))
          dialect-1 (dialect/write-dialect (pool/as-dialect (pool/db *conn*)))
          file-cfg  (pool/config {:backend :file :path (tmp-dir "b4-store-")})]
      ;; 真的落到文件后端再删——内存库删了等于什么都没发生，证不出「删掉 store」。
      (pool/delete-pool! file-cfg)
      (let [c1 (pool/open-pool! file-cfg)]
        (is (empty? (:findings (pool/import-pool! c1 (edn/read-string export-1)))))
        (is (= export-1 (pool/export-pool (pool/db c1))))
        (pool/close-pool! c1))
      (pool/delete-pool! file-cfg)
      (let [c2 (pool/open-pool! file-cfg)]
        (is (zero? (count (pool/objects (pool/db c2)))) "store 确实被删干净了")
        (let [r (pool/import-pool! c2 (edn/read-string export-1))]
          (is (empty? (:findings r)))
          (is (= 312 (:count r))))
        (is (= export-1 (pool/export-pool (pool/db c2)))
            "两次导出逐字节相同")
        (is (= dialect-1 (dialect/write-dialect (pool/as-dialect (pool/db c2))))
            "重建出的是同一个模型，不只是同一份字节")
        (pool/close-pool! c2)
        (pool/delete-pool! file-cfg))))
  (testing "导出是规范形：同一池导两次逐字节相同，且键序无关"
    (let [e (pool/export-pool (pool/db *conn*))]
      (is (= e (pool/export-pool (pool/db *conn*))))
      (is (str/ends-with? e "\n")))))

(deftest import-envelope-test
  (testing "导入的信封自己也要判：版本、键面、对象形状、重复对象"
    (let [fresh (fn [] (let [cfg (pool/config {:backend :mem :id (str (gensym "env"))})]
                         (pool/delete-pool! cfg)
                         (pool/open-pool! cfg)))
          ;; D2.29 之后池里没有子对象了，「悬空子对象」这条信封检查随之退休；
          ;; 一个池不认识的类型仍然要在信封层就拦下。
          alien {:pool/type :navigation :pool/id ["x.y" "z"] :pool/revision 1
                 :pool/entry {:target "x.y"}}]
      (is (= #{:pool.export/version-unsupported}
             (codes-of (rejected! (pool/import-pool! (fresh) {:pool/version 2 :pool/objects []})))))
      (is (= #{:pool.export/malformed}
             (codes-of (rejected! (pool/import-pool! (fresh) {:pool/version 1 :pool/objects []
                                                              :pool/extra 1})))))
      (is (contains? (codes-of (rejected! (pool/import-pool! (fresh)
                                                             {:pool/version 1 :pool/objects [alien]})))
                     :pool.export/malformed)
          "退休的 :navigation 类型（D2.29）不再是对象类型 → 拦下，绝不静默丢")
      (is (contains? (codes-of (rejected!
                                (pool/import-pool!
                                 (fresh)
                                 {:pool/version 1
                                  :pool/objects [{:pool/type :unit :pool/id "kg" :pool/revision 1
                                                  :pool/entry {}}
                                                 {:pool/type :unit :pool/id "kg" :pool/revision 1
                                                  :pool/entry {:label "x"}}]})))
                     :pool.export/duplicate-object)))))

;; ---------------------------------------------------------------------------
;; 退出条件 2：每个 action 的拒绝都是结构化的且不含配置值
;; ---------------------------------------------------------------------------

(def ^:private an-attr
  "取一个**没有绑词条**的属性：绑了词条的属性禁写 `:label`/`:description` 等
   词条携带字段（D2.26），拿它做 patch 测试会被 B0 拦下——那是另一条规则的对，
   不是本测试要证的事。"
  :cis.cpo.cpo_detail/cpo_line_qty)
(def ^:private the-source "cis-demo-db")

(deftest action-shape-rejection-test
  (testing "action 自身的形状：键面封闭、expected_revision 必带、类型与 id 认得出"
    (is (= #{:pool.action/malformed}
           (codes-of (rejected! (pool/execute-action
                                 *conn* {:workspace "demo" :action :create :type :unit :id "kg"
                                         :entry {} :expected_revision 0 :who "me"})))))
    (is (= #{:pool.action/malformed}
           (codes-of (rejected! (pool/execute-action
                                 *conn* {:workspace "demo" :action :create :type :unit :id "kg" :entry {}})))))
    (is (= #{:pool.action/unknown-action}
           (codes-of (rejected! (pool/execute-action
                                 *conn* {:workspace "demo" :action :upsert :type :unit :id "kg"
                                         :entry {} :expected_revision 0})))))
    (is (= #{:pool.action/unknown-type}
           (codes-of (rejected! (pool/execute-action
                                 *conn* {:workspace "demo" :action :create :type :widget :id "kg"
                                         :entry {} :expected_revision 0})))))
    (is (= #{:pool.action/invalid-id}
           (codes-of (rejected! (pool/execute-action
                                 *conn* {:workspace "demo" :action :create :type :attribute :id "not-a-kw"
                                         :entry {:type :string} :expected_revision 0})))))
    (is (= #{:pool.action/malformed}
           (codes-of (rejected! (pool/execute-action *conn* [:create :unit "kg"])))))))

(deftest action-rejection-test
  (testing "update：entry 带契约外的键 → 拒绝，而不是静默丢掉
            ——object->tx 曾直接丢弃 :open 键并报成功，typo 的键无声消失"
    (let [rev (:pool/revision (pool/object (pool/db *conn*) :attribute an-attr))
          fs  (rejected! (pool/execute-action
                          *conn* {:workspace "demo" :action :update :type :attribute :id an-attr
                                  :entry {:label "X" :bogus_key 1}
                                  :expected_revision rev}))]
      (is (= #{:pool.action/unknown-entry-key} (codes-of fs)))
      (is (str/includes? (pr-str fs) ":bogus_key") "拒绝点名的是那个键")
      (is (= rev (:pool/revision (pool/object (pool/db *conn*) :attribute an-attr)))
          "拒绝之后池一个字节都没变")))

  (testing "entry 自带的 :id 与 action 的 :id 不一致 → 拒绝（身份歧义）；
            一致则是方言注册表条目的正常形状，照常接受"
    (let [fs (rejected! (pool/execute-action
                         *conn* {:workspace "demo" :action :create :type :resolver :id "r_one"
                                 :entry {:id "r_other"
                                         :input [] :output [an-attr]
                                         :result_type "single"
                                         :source {:type "sql" :query "SELECT 1"}}
                                 :expected_revision 0}))]
      (is (contains? (codes-of fs) :pool.action/malformed))))

  (testing "link 的 :from/:to 必须就是它被寻址的那对端点（D2.3）
            ——`objects->dialect` 只读 entry、不看 id，所以两者不一致不会存成
            矛盾，而是**悄悄把这条 link 搬到调用方没要过的端点上**"
    (let [fs (rejected! (pool/execute-action
                         *conn* {:workspace "demo" :action :create :type :link
                                 :id [:cis.a/x :cis.b/y]
                                 :entry {:from :cis.a/other :to :cis.b/y}
                                 :expected_revision 0}))]
      (is (contains? (codes-of fs) :pool.action/malformed))
      (is (str/includes? (pr-str fs) "endpoint pair"))))

  (testing "create：撞已有 id → :id-taken（没有公开 upsert）"
    (let [fs (rejected! (pool/execute-action
                         *conn* {:workspace "demo" :action :create :type :attribute :id an-attr
                                 :entry {:type :string} :expected_revision 0}))]
      (is (= #{:pool.action/id-taken} (codes-of fs)))
      (is (= [:pool :attribute an-attr] (:path (first fs))) "拒绝点名的是这个对象")))

  (testing "create：expected_revision 必须是 0（这个对象还不该存在）"
    (is (= #{:pool.conflict/revision-mismatch}
           (codes-of (rejected! (pool/execute-action
                                 *conn* {:workspace "demo" :action :create :type :unit :id "kg"
                                         :entry {:label "kg"} :expected_revision 3}))))))

  (testing "update：指向不存在的对象 → :unknown-entity（没有公开 upsert）"
    (is (= #{:pool.action/unknown-entity}
           (codes-of (rejected! (pool/execute-action
                                 *conn* {:workspace "demo" :action :update :type :term :id "no.such/term"
                                         :entry {:label "x"} :expected_revision 1}))))))

  (testing "delete：指向不存在的对象 → :unknown-entity"
    (is (= #{:pool.action/unknown-entity}
           (codes-of (rejected! (pool/execute-action
                                 *conn* {:workspace "demo" :action :delete :type :unit :id "no-such-unit"
                                         :expected_revision 1}))))))

  (testing "delete：仍被引用即点名拒绝——source 被 35 个 resolver 指着"
    (let [fs (rejected! (pool/execute-action
                         *conn* {:workspace "demo" :action :delete :type :source :id the-source
                                 :expected_revision 1}))]
      (is (= #{:pool.reference/inbound} (codes-of fs)))
      (is (every? (fn [[t _]] (contains? #{:resolver :mutation} t)) (:got (first fs)))
          ":got 逐个点名引用者，且只给 [类型 id]，不给内容")
      (is (pos? (count (:got (first fs)))))))

  (testing "delete：namespace 底下还住着属性（或被某条 join 属性指着）→ 点名拒绝"
    (let [fs (rejected! (pool/execute-action
                         *conn* {:workspace "demo" :action :delete :type :namespace :id "cis.master.customer"
                                 :expected_revision 1}))]
      (is (= #{:pool.reference/inbound} (codes-of fs)))
      (is (some (fn [[t _]] (= :attribute t)) (:got (first fs))))))

  (testing "create/update 的结构错一律回落到 B0（裁定 ②：池不写第二个 schema）"
    (let [fs (rejected! (pool/execute-action
                         *conn* {:workspace "demo" :action :create :type :attribute
                                 :id :cis.master.vendor/brand_new
                                 :entry {:type :not-a-real-type}
                                 :expected_revision 0}))]
      (is (contains? (codes-of fs) :pool.dialect/rejected) "池给出摘要行")
      (is (some #(= "dialect.schema" (namespace (:code %))) fs)
          "明细是 dialect.* —— 词表判定归 B0，池不重写一遍")))

  (testing "整体替换的 source 写坏了，拒绝里也不许带出池里那份连接串"
    (let [fs (rejected! (pool/execute-action
                         *conn* {:workspace "demo" :action :update :type :source :id the-source
                                 :entry {:type "sql" :bogus_key "x"}
                                 :expected_revision 1}))]
      (is (contains? (codes-of fs) :pool.dialect/rejected))))

  (testing "拒绝之后池一个字节都没变"
    (is (= 312 (count (pool/objects (pool/db *conn*)))))
    (is (= 1 (:pool/revision (pool/object (pool/db *conn*) :source the-source))))))

;; ---------------------------------------------------------------------------
;; 退出条件 3：expected_revision 冲突被正确拒绝
;; ---------------------------------------------------------------------------

(deftest revision-conflict-test
  (testing "同一个 revision 上并发改两次：第一次进去，第二次拒绝"
    (let [rev (:pool/revision (pool/object (pool/db *conn*) :attribute an-attr))
          ;; 两个建模者各自读到 rev，各自算好了自己的 patch。
          a   {:workspace "demo" :action :update :type :attribute :id an-attr
               :entry {:label "CPO line qty (A)"} :expected_revision rev}
          b   {:workspace "demo" :action :update :type :attribute :id an-attr
               :entry {:label "CPO line qty (B)"} :expected_revision rev}
          ra  (pool/execute-action *conn* a)]
      (is (= [] (:findings ra)))
      (is (= (inc rev) (:revision ra)))
      (let [fs (rejected! (pool/execute-action *conn* b))
            f  (first fs)]
        (is (= #{:pool.conflict/revision-mismatch} (codes-of fs)))
        (is (= rev (:expected f)))
        (is (= (inc rev) (:got f)) "拒绝要说出「它已经走到哪了」，否则调用方只能盲目重试"))
      (is (= "CPO line qty (A)"
             (:label (:pool/entry (pool/object (pool/db *conn*) :attribute an-attr))))
          "后写的没有覆盖先写的")))

  (testing "revision 是对象级的（裁定 ④）：改了 A 不影响 B 的 expected_revision"
    (let [other :cis.cpo.cpo_detail/cpo_line_seq
          r     (pool/execute-action *conn* {:workspace "demo" :action :update :type :attribute :id other
                                             :entry {:label "CPO line seq"}
                                             :expected_revision 1})]
      (is (= [] (:findings r)))
      (is (= 2 (:revision r)))))

  (testing "delete 也要对 revision（拿旧 revision 删 = 删掉一个你没看过的东西）"
    (let [r (pool/execute-action *conn* {:workspace "demo" :action :create :type :unit :id "each"
                                         :entry {:label "each"} :expected_revision 0})]
      (is (= [] (:findings r)))
      (is (= 1 (:revision r)))
      (is (= [] (:findings (pool/execute-action *conn* {:workspace "demo" :action :update :type :unit :id "each"
                                                        :entry {:description "one item"}
                                                        :expected_revision 1}))))
      (is (= #{:pool.conflict/revision-mismatch}
             (codes-of (rejected! (pool/execute-action *conn* {:workspace "demo" :action :delete :type :unit
                                                               :id "each"
                                                               :expected_revision 1})))))
      (is (= [] (:findings (pool/execute-action *conn* {:workspace "demo" :action :delete :type :unit :id "each"
                                                        :expected_revision 2})))))))

;; ---------------------------------------------------------------------------
;; §7.3 的两种改法
;; ---------------------------------------------------------------------------

(deftest update-semantics-test
  (testing "attribute 元数据是 merge patch：没提到的键留着"
    (let [before (:pool/entry (pool/object (pool/db *conn*) :attribute an-attr))]
      (is (some? (:type before)))
      (pool/execute-action *conn* {:workspace "demo" :action :update :type :attribute :id an-attr
                                   :entry {:description "patched"} :expected_revision 1})
      (let [after (:pool/entry (pool/object (pool/db *conn*) :attribute an-attr))]
        (is (= (:type before) (:type after)))
        (is (= "patched" (:description after)))
        (is (= (:label before) (:label after))))))

  (testing "merge patch 里显式 nil = 删除该键"
    ;; 上一条 testing 已经把它推到 revision 2 —— 对象级 revision 就是这么用的。
    (is (= [] (:findings (pool/execute-action
                          *conn* {:workspace "demo" :action :update :type :attribute :id an-attr
                                  :entry {:description nil} :expected_revision 2}))))
    (is (nil? (:description (:pool/entry (pool/object (pool/db *conn*) :attribute an-attr))))))

  (testing "resolver 是整体替换：新条目里没有的键就是没有了"
    (let [rid  (:pool/id (first (filter #(= :resolver (:pool/type %))
                                        (pool/objects (pool/db *conn*)))))
          old  (:pool/entry (pool/object (pool/db *conn*) :resolver rid))
          slim (dissoc old :description)]
      (is (some? (:description old)))
      (let [r (pool/execute-action *conn* {:workspace "demo" :action :update :type :resolver :id rid
                                           :entry slim :expected_revision 1})]
        (is (= [] (:findings r))))
      (is (nil? (:description (:pool/entry (pool/object (pool/db *conn*) :resolver rid))))))))

(deftest finding-registry-test
  (testing "finding-v1 §6：产出未注册 code 是实现 bug —— 注册表非空且全是 pool.*"
    (is (seq pool/finding-codes))
    (is (every? #(str/starts-with? (namespace %) "pool.") pool/finding-codes)))
  (testing "九个对象类型，schema 的 :db/ident 与条目键一一对应"
    ;; 10 → 9：D2.29 把 navigation 并进 attribute（每个顶层键正好一个对象类型）。
    (is (= 9 (count pool/object-types)))
    (is (not (contains? (set pool/object-types) :navigation)))
    (is (contains? (set (map :db/ident pool/datahike-schema)) :pool.attribute/target))
    ;; 抽查一键一 ident 的对应关系。`:sensitivity` 曾是这里的样本，随访问档位
    ;; 退役（owner 裁定 2026-08-27）从键面表里消失——schema 是从那张表机械推出
    ;; 的，所以它**不在**才是对的。
    (is (not (contains? (set (map :db/ident pool/datahike-schema)) :pool.attribute/sensitivity)))
    (is (contains? (set (map :db/ident pool/datahike-schema)) :pool.attribute/listed?))
    (is (contains? (set (map :db/ident pool/datahike-schema)) :pool.resolver/result_type))))

(deftest a-written-default-is-an-equivalent-spelling-not-an-error
  "owner 裁定 2026-08-27：`:listed? true` 与不写这个键是**同一句话**。
   收得宽——两种拼法都进得来；存得严——落库永远是规范形（键缺席）。
   merge 语义下这条还捋顺了「拨回缺省」：patch 里写出缺省 = 删键。"
  (let [cfg  (pool/config {:backend :mem :id (str (gensym "spell"))})
        _    (pool/delete-pool! cfg)
        conn (pool/open-pool! cfg)]
    (try
      (doseq [a [{:workspace "demo" :action :create :type :namespace :id "s.a" :entry {} :expected_revision 0}
                 {:workspace "demo" :action :create :type :attribute :id :s.a/x
                  :entry {:type :date :listed? true} :expected_revision 0}]]
        (is (empty? (filter #(= :error (:severity %))
                            (:findings (pool/execute-action conn a))))))
      (is (= {:type :date} (:pool/entry (pool/object (pool/db conn) :attribute :s.a/x)))
          "写出的缺省不落库——存储只有一种拼法")
      (pool/execute-action conn {:workspace "demo" :action :update :type :attribute :id :s.a/x
                                 :entry {:listed? false} :expected_revision 1})
      (pool/execute-action conn {:workspace "demo" :action :update :type :attribute :id :s.a/x
                                 :entry {:listed? true} :expected_revision 2})
      (is (= {:type :date} (:pool/entry (pool/object (pool/db conn) :attribute :s.a/x)))
          "merge patch 里写出缺省 = 删键，读回来就是缺省")
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))
