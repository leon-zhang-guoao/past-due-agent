(ns open.atlas.common.search.search-test
  "B4.3 的守门测试。每条钉住一条裁定，红了说明那条裁定被人绕过去了。

   **装料是 demo 模型**（`fixtures/demo/model.edn`，裁定 ⑥ 与 pool README
   裁定 ⑥ 的同一条理由）。玩具数据证不出
   「不洪泛」——三个属性的玩具里没有一个绑着 7 个属性的词条，而**洪泛正是绑定
   数一多才出现的失败**（D7.11：真实 CIS 里 `created_by` 绑着 381 个）。"
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.search :as search])
  (:import [java.sql DriverManager]))

;; ---------------------------------------------------------------------------
;; 装料
;; ---------------------------------------------------------------------------

(defn- repo-root []
  (loop [d (io/file (System/getProperty "user.dir"))]
    (cond (nil? d) (throw (ex-info "repo root not found" {}))
          (.isDirectory (io/file d "fixtures/demo")) d
          :else (recur (.getParentFile d)))))

(defn- tmp-dir [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix (into-array java.nio.file.attribute.FileAttribute []))))

(defn- rm-r [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-r (.listFiles f)))
    (.delete f)))

(defonce ^{:doc "demo 模型。`fixtures/demo/model.edn` 是它的家（见那里的 README）。"}
  demo-model
  (delay (edn/read-string (slurp (io/resource "model.edn")))))

(def rev "本轮的 revision。B4 的键是**池 revision**，B5a 要换成 graph 闭包 revision。" 7)

(defonce ^:private idx* (delay (search/build-index @demo-model rev)))
(defn- idx [] @idx*)

;; ---------------------------------------------------------------------------
;; 裁定 ① —— 不洪泛（核心）
;; ---------------------------------------------------------------------------

(deftest no-flooding-test
  (testing "搜「客户编号」得到**一条词条命中**，不是 7 条属性命中（D7.11）"
    (let [hits  (:hits (search/search (idx) "客户编号" rev))
          terms (filter #(= :term (:type %)) hits)
          t     (first (filter #(= "cis.party/customer_no" (:id %)) terms))]
      (is (some? t) "词条本身必须命中")
      (is (= 1 (count (filter #(= "cis.party/customer_no" (:id %)) hits)))
          "同一个词条只出现一次")
      (is (= 7 (:binding-count t))
          "绑定计数随行——第二跳是目录读，在建索引时就算好了（§7.3 默认走法 ①）")
      (is (= 7 (count (:bindings t))))
      (is (= (set (map :attribute (:bindings t)))
             (set (keys (filter (fn [[_ e]] (= "cis.party/customer_no" (:term e)))
                                (:attributes @demo-model)))))
          "绑定清单就是模型里绑它的那些属性，一个不多一个不少")
      (testing "**7 个绑定属性没有一个因为词条的语义文本而命中**
                ——语义文本只索引一次，不向属性文档复制（裁定 ①）"
        (is (empty? (filter #(and (= :attribute (:type %))
                                  (= "cis.party/customer_no" (:term %)))
                            hits))))))

  (testing "「录入人」同理：一条 common.audit/created_by，不是每个 entity 一条"
    (let [hits (:hits (search/search (idx) "录入人" rev))
          t    (first (filter #(= "common.audit/created_by" (:id %)) hits))]
      (is (some? t))
      (is (< 1 (:binding-count t)) "它是个多绑定词条，否则这条测试证不出什么")
      (is (empty? (filter #(and (= :attribute (:type %))
                                (= "common.audit/created_by" (:term %)))
                          hits))))))

(deftest attribute-hits-carry-coordinates-test
  (testing "属性命中带坐标 `→ 词条ID`（D7.11）"
    (let [hits (:hits (search/search (idx) "entry_id" rev))
          as   (filter #(= :attribute (:type %)) hits)]
      (is (seq as))
      (is (every? #(some? (:entity %)) as) "属性命中必须说自己属于哪个 entity")
      (is (some #(some? (:term %)) as) "绑定属性回带它的词条 ID"))))

;; ---------------------------------------------------------------------------
;; Join attributes are first-class (owner rulings 2026-09-02: collection
;; reversal, then D2.29「分支即属性」)
;; ---------------------------------------------------------------------------

(deftest join-attributes-are-searchable-test
  (testing "join 属性按名字搜得到，是带坐标的 `:attribute` 命中——owner 裁定
            2026-09-02 推翻了 ba7004d 的排除，D2.29 又把 navigation 并进属性,
            于是「要不要索引它」这个问题连同它的特判一起消失了。
            search 必须与 engine 一致：「查不到」与「不存在」在消费端逐字相同"
    (doseq [q ["all_parts" "all parts" "allParts"]]
      (let [hits (:hits (search/search (idx) q rev))
            h    (first (filter #(= :cis.master.part/all_parts (:id %)) hits))]
        (is (some? h) (str q " 搜不到 :cis.master.part/all_parts"))
        (is (= :attribute (:type h)) "它是属性文档，不是别的什么类型")
        (is (= "cis.master.part" (:entity h)) "属性命中必须说自己属于哪个 entity")
        (is (= :exact-name (:bucket h))
            "标识符折叠后与查询同形 → 精确名先行（裁定 ②/③ 的共用匹配层）"))))

  (testing "demo 模型里 19 条 join 属性一个都不缺（其中 7 条是全局集合：
            `:target` 就是自己的 namespace）"
    (let [joins   (dialect/join-attributes @demo-model)
          globals (set (keep (fn [[k t]] (when (= t (namespace k)) k)) joins))]
      (is (= 19 (count joins)) "前提：fixtures/demo 里就是 19 条 join")
      (is (= 7 (count globals)) "其中 7 条是 D2.29 之前的 `:collection?`")
      ;; `all_customers` 例外见下一条：它的名字太宽，被信封挡下而不是没进索引。
      (doseq [k (disj (set (keys joins)) :cis.master.customer/all_customers)]
        (is (some #(= k (:id %))
                  (:hits (search/search (idx) (name k) rev)))
            (str k " 进不了索引")))))

  ;; `all_customers` 在索引里，但**它的名字太宽**：`all` 与 `customer` 两个词元
  ;; 各自的 gram 在 demo 模型上加起来 212 条，于是被信封挡下
  ;; （见 `broad-query-trips-the-envelope-test`）。所以这里按 finding 自己给的补救
  ;; 办法办——**缩小语料**（`:visible?` 钩子），不是把信封抬高。
  (testing "`all_customers` 也在索引里：语料缩到它那个 entity 就直接命中"
    (let [i (search/build-index @demo-model rev
                                {:visible? (fn [x] (str/starts-with? (str x) ":cis.master.customer/"))})
          h (first (filter #(= :cis.master.customer/all_customers (:id %))
                           (:hits (search/search i "all_customers" rev))))]
      (is (some? h))
      (is (= :attribute (:type h)))
      (is (= "cis.master.customer" (:entity h)))
      (search/close-index! i)))

  (testing "回归：普通 entity / 属性查询照旧"
    (is (some #(and (= :attribute (:type %))
                    (= :cis.master.customer/cust_no (:id %)))
              (:hits (search/search (idx) "cust_no" rev))))
    (is (some #(= :entity (:type %)) (:hits (search/search (idx) "part" rev)))
        "entity 文档照旧命中")))

(deftest broad-query-trips-the-envelope-test
  (testing "A genuinely broad whole-word query still refuses rather than truncates"
    (let [r (search/search (idx) "customer" rev {:max-hits 1})]
      (is (nil? (:hits r)))
      (is (= [:search.query/over-limit] (map :code (:findings r))))
      (is (every? search/finding? (:findings r)))))

  (testing "同一个词缩到 scope 之内照样有答案——信封挡的是宽度，不是这个词"
    (let [r (search/search-attrs (idx) "cust_no" "cis.master.customer" rev)]
      (is (= :direct (:verdict r)))
      (is (= :cis.master.customer/cust_no (:attribute r))))))

;; ---------------------------------------------------------------------------
;; 裁定 ⑤ —— 中文搜得到，以及反面
;; ---------------------------------------------------------------------------

(deftest cjk-is-searchable-test
  (testing "只有中文同义词的说法搜得到——n-gram 让它们真的进了索引"
    (doseq [[q expect] {"收货城市"   "cis.ship/ship_to_city"
                        "订货数量"   "cis.line/order_qty"
                        "单据类型"   "cis.doc/order_type"
                        "供应商编号" "cis.party/vendor_no"}]
      (is (some #(= expect (:id %)) (:hits (search/search (idx) q rev)))
          (str q " 搜不到 " expect))))

  (testing "**子串也搜得到**：这正是 n-gram 买来的东西"
    (is (some #(= "cis.party/customer_no" (:id %))
              (:hits (search/search (idx) "客户编" rev))))))

(deftest cjk-negative-proof-test
  (testing "反面：同一段文本**不切 n-gram** 直接喂 FTS5（内置 unicode61 不切 CJK）,
            子串查询一条都搜不到——所以裁定 ⑤ 不是可有可无的优化"
    (Class/forName "org.sqlite.JDBC")
    (with-open [c (DriverManager/getConnection "jdbc:sqlite::memory:")]
      (with-open [s (.createStatement c)]
        (.execute s "CREATE VIRTUAL TABLE raw USING fts5(body)")
        (.execute s "INSERT INTO raw VALUES ('客户编号 客户账号')"))
      (let [hit? (fn [q] (with-open [ps (.prepareStatement c "SELECT count(*) FROM raw WHERE raw MATCH ?")]
                           (.setString ps 1 (str \" q \"))
                           (with-open [rs (.executeQuery ps)]
                             (.next rs) (pos? (.getInt rs 1)))))]
        (is (hit? "客户编号") "整词当然搜得到——不切也能中的那一半")
        (is (not (hit? "客户编")) "子串搜不到：unicode61 把一整串 CJK 当一个词")
        (is (not (hit? "编号"))  "同上"))))

  (testing "而我们切出来的词流里，这两个子串都在"
    (let [ts (set (search/gram-terms nil nil "客户编号"))]
      (is (contains? ts "客户编"))
      (is (contains? ts "编号")))))

(deftest identifier-folding-test
  (testing "`to_acct_no` / `toAcctNo` / `to acct no` 同形（principles「存储与同步」）"
    (is (apply = (map #(search/fold nil nil %)
                      ["to_acct_no" "toAcctNo" "to acct no" "to-acct-no" "TO.ACCT.NO"])))
    (is (= ["to" "acct" "no"] (search/tokens "toAcctNo")))
    (is (= ["http" "server" "id"] (search/tokens "HTTPServerId"))
        "连续大写后跟大写+小写也是一处边界")))

(deftest latin-typos-still-find-the-thing-test
  (testing "英文错一个字母照样找得到 —— principles「存储与同步」把这条拼写容错
            明确算作字符 gram 买来的能力：**搜索框被要求的是容错不是语义**"
    ;; `custmer` / `customer` is back as the first pair. It was pulled once
    ;; collection attributes entered the index and `customer` grew past the
    ;; 200-hit envelope; with the 23 `:listed? false` attributes now out of the
    ;; index (D2.28) it matches 194 docs and fits again. The envelope itself is
    ;; asserted directly by `broad-query-trips-the-envelope-test` — this test
    ;; only proves tolerance, so it needs pairs that fit, not a particular word.
    (doseq [[typo correct] [["custmer" "customer"]
                            ["vendro" "vendor"]
                            ["invoce"  "invoice"]  ["shippment" "shipment"]]]
      (let [got (into #{} (map :id) (:hits (search/search (idx) typo rev)))
            ok  (into #{} (map :id) (:hits (search/search (idx) correct rev)))]
        (is (seq ok) (str "前提：拼对的 " correct " 本来就有命中"))
        (is (seq (set/intersection got ok))
            (str typo " 拼错了，但它与 " correct " 的命中集合应当有交集
                 ——只按整词索引时这里是空集，那正是这条断言存在的理由")))))

  (testing "折叠排在 gram **之前**：三种写法产生的词流逐字节相同"
    (is (apply = (map #(vec (search/gram-terms nil nil %))
                      ["order_no" "orderNo" "order-no"]))
        "不是碰巧排对，是同一个字符串（principles 原话）")))

;; ---------------------------------------------------------------------------
;; 裁定 ② —— 类型内排序、类型间固定顺序、无分数
;; ---------------------------------------------------------------------------

(def ^:private hit-keys
  "命中的封闭键面。**这里没有任何分数字段**，而且不许有人偷偷加一个。"
  #{:bucket :type :id :label :entity :term :term-context
    :binding-count :bindings})

(deftest no-score-in-response-test
  (doseq [q ["客户编号" "entry_id" "ship to"]]
    (let [hits (:hits (search/search (idx) q rev))]
      (is (seq hits))
      (testing (str q "：响应里没有分数字段（裁定 ②：没有哪个阈值两次意味着同一件事）")
        (is (empty? (remove hit-keys (mapcat keys hits))))
        (is (not-any? #(some (fn [k] (re-find #"(?i)score|rank|bm25|confidence" (name k)))
                             (keys %))
                      hits))))))

(deftest bucket-order-is-fixed-test
  (testing "类型间固定顺序：scope 内精确名 → 词条 → entity → 散属性"
    (is (= [:exact-name :term :entity :attribute] search/bucket-order))
    (doseq [q ["客户编号" "entry_id" "ship to" "订单"]]
      (let [ord (map #(.indexOf ^java.util.List search/bucket-order (:bucket %))
                     (:hits (search/search (idx) q rev)))]
        (is (= ord (sort ord)) (str q "：桶的顺序被打乱了"))))))

(deftest exact-name-comes-first-test
  (testing "精确名先行，且不与任何 BM25 分数比较"
    (let [hits (:hits (search/search (idx) "客户编号" rev))]
      (is (= :exact-name (:bucket (first hits))))
      (is (= "cis.party/customer_no" (:id (first hits)))))))

(def ^:private bm25-model
  "为「类型内确实排序」造的最小模型：`zzz` 的说法里有查询的两个词，`aaa` 只有一个。
   字典序会把 `aaa` 排前面，BM25 会把 `zzz` 排前面——**两者相反**，所以这条断言
   区分得出「排了」与「按下标原样吐出来」。demo 模型上做不到这一点：命中恰好与
   字典序一致时，测试会绿得毫无意义。"
  {:terms {}
   :namespaces {"demo.x" {:label "Demo"}}
   :attributes {:demo.x/aaa {:label "收货城市" :type :string}
                :demo.x/zzz {:label "收货城市 收货地址" :synonyms ["收货城市"] :type :string}}})

(deftest conjunctive-query-excludes-partial-word-matches-test
  (testing "精确阶段排除只匹配部分查询词的文档"
    (let [i   (search/build-index bm25-model 1)
          ids (->> (:hits (search/search i "收货地址" 1))
                   (filter #(= :attribute (:type %)))
                   (map :id))]
      (is (= [:demo.x/zzz] (vec ids))
          "仅匹配收货不应进入收货地址的结果")
      (search/close-index! i))))

;; ---------------------------------------------------------------------------
;; 裁定 ④ —— 陈旧即弃
;; ---------------------------------------------------------------------------

(deftest stale-index-is-discarded-test
  (let [i (search/build-index @demo-model rev)]
    (testing "对得上就正常答"
      (is (seq (:hits (search/search i "客户编号" rev)))))
    (testing "对不上**拒绝**，不带警告地用（裁定 ④）"
      (let [r (search/search i "客户编号" (inc rev))]
        (is (nil? (:hits r)))
        (is (= [:search.index/stale] (map :code (:findings r))))
        (is (every? search/finding? (:findings r))))
      (let [r (search/search-attrs i "客户编号" "cis.master.customer" (inc rev))]
        (is (nil? (:verdict r)))
        (is (= [:search.index/stale] (map :code (:findings r))))))
    (testing "`refresh-index!` 整体作废重建，不是增量补"
      (is (search/stale? i (inc rev)))
      (let [i2 (search/refresh-index! i @demo-model (inc rev))]
        (is (not (identical? i i2)))
        (is (= (inc rev) (search/index-revision i2)))
        (is (seq (:hits (search/search i2 "客户编号" (inc rev)))))
        (testing "revision 没变则不白建一次"
          (is (identical? i2 (search/refresh-index! i2 @demo-model (inc rev)))))
        (search/close-index! i2)))))

;; ---------------------------------------------------------------------------
;; D7.12 —— scoped 的四个离散裁决
;; ---------------------------------------------------------------------------

(deftest scoped-direct-test
  (testing "直中：用户的词 = 本 entity 属性的 local name，**词条不参与**"
    (let [r (search/search-attrs (idx) "to_acct_no" "cis.purchase.vpo" rev)]
      (is (= :direct (:verdict r)))
      (is (= :cis.purchase.vpo/to_acct_no (:attribute r)))
      (is (nil? (:term r)) "直中不经词条，就不该标注词条"))
    (testing "折叠后同形的写法也直中（共用匹配层）"
      (is (= :direct (:verdict (search/search-attrs (idx) "toAcctNo" "cis.purchase.vpo" rev)))))))

(deftest scoped-via-term-test
  (testing "经词条命中：标注**经由哪个词条**（D7.12 `:asked-as` 要读给用户听）"
    (let [r (search/search-attrs (idx) "客户编号" "cis.master.customer" rev)]
      (is (= :via-term (:verdict r)))
      (is (= :cis.master.customer/cust_no (:attribute r)))
      (is (= "cis.party/customer_no" (:term r)))
      (is (= "客户号" (:term-label r))))))

(deftest scoped-none-test
  (testing "零绑定 → `:none`，如实说没有并回退搜索。**不猜**"
    (let [r (search/search-attrs (idx) "紫色的鸭子" "cis.purchase.vpo" rev)]
      (is (= :none (:verdict r)))
      (is (nil? (:attribute r)) "`:none` 一个候选都不带——带了就等于在猜")))
  (testing "「这个 entity 里没有」与「没有这个 entity」是两个不同的答案"
    (let [r (search/search-attrs (idx) "客户编号" "cis.does.not_exist" rev)]
      (is (nil? (:verdict r)))
      (is (= [:search.scope/unknown-entity] (map :code (:findings r)))))))

;; ── `:ambiguous` 用合成模型 ────────────────────────────────────────────────
;; 真模型上现在是 0 条（`sso/cust_no` 已删）。多绑定不在体检禁止——订单确实有
;; 两个客户编号（D7.12）——所以这条裁决必须有测试守着，而 demo 模型里恰好没有实例时,
;; 造一份**最小**合成模型是唯一诚实的办法：不改 fixtures（CLAUDE.md），
;; 也不假装那份真模型里有。
(def ambiguous-model
  {:terms      {"demo/customer_no" {:label "客户号" :synonyms ["客户编号"]}}
   :namespaces {"demo.order" {:label "Order"}}
   :attributes {:demo.order/bill_to {:term "demo/customer_no" :term-context "购买方" :type :integer}
                :demo.order/ship_to {:term "demo/customer_no" :term-context "收货方" :type :integer}}})

(deftest scoped-ambiguous-test
  (let [i (search/build-index ambiguous-model 1)
        r (search/search-attrs i "客户编号" "demo.order" 1)]
    (testing "多个绑定 → `:ambiguous`，列候选与各自 `:term-context`，**不猜**"
      (is (= :ambiguous (:verdict r)))
      (is (nil? (:attribute r)) "歧义时不带出任何单一答案")
      (is (= #{:demo.order/bill_to :demo.order/ship_to}
             (set (map :attribute (:candidates r)))))
      (is (= #{"购买方" "收货方"} (set (map :term-context (:candidates r))))
          "D2.27 的多绑定硬门保证语境存在且互异——歧义靠它打破，不靠排名"))
    (testing "同一份合成模型上，global 仍然只给一条词条命中（裁定 ① 不因歧义而失效）"
      (let [hits (:hits (search/search i "客户编号" 1))]
        (is (= 1 (count (filter #(= :term (:type %)) hits))))
        (is (= 2 (:binding-count (first (filter #(= :term (:type %)) hits)))))))
    (search/close-index! i)))

;; ---------------------------------------------------------------------------
;; 拒绝的形状
;; ---------------------------------------------------------------------------

(deftest rejections-are-structured-test
  (testing "空查询：拒绝而不是回一个空结果——「没切出词」与「没搜到」是两件事"
    (let [r (search/search (idx) "   ，。！  " rev)]
      (is (nil? (:hits r)))
      (is (= [:search.query/blank] (map :code (:findings r))))))
  (testing "超限拒绝而非截断（CLAUDE.md）"
    (let [r (search/search (idx) "customer" rev {:max-hits 1})]
      (is (nil? (:hits r)))
      (is (= [:search.query/over-limit] (map :code (:findings r))))))
  (testing "每个产出的 code 都在注册表里，且 finding 键面封闭"
    (doseq [r [(search/search (idx) "  " rev)
               (search/search (idx) "customer" rev {:max-hits 1})
               (search/search (idx) "客户编号" (inc rev))
               (search/search-attrs (idx) "x" "nope" rev)]]
      (is (seq (:findings r)))
      (is (every? search/finding? (:findings r)))
      (is (every? #(contains? search/finding-codes (:code %)) (:findings r))))))

;; ---------------------------------------------------------------------------
;; 两道过滤（README 边界）
;; ---------------------------------------------------------------------------

(deftest visible-hook-narrows-the-corpus-test
  (testing "`:visible?` 是**调用方给的文档抑制器**：传一个谓词进来，被排除的条目
            连文档都不生成。它只缩语料，不表达模型语义——访问档位整套退役
            （D5.11）之后，本模块唯一的**模型级**过滤是 `:listed?`（见下）"
    (let [i (search/build-index @demo-model rev
                                {:visible? (fn [x] (not (str/starts-with? (str x) ":cis.master.customer/")))})
          hits (:hits (search/search i "客户名称" rev))]
      (is (not-any? #(= "cis.master.customer" (:entity %)) hits))
      (search/close-index! i))))

;; ---------------------------------------------------------------------------
;; D2.28 `:listed?` —— search 与 catalog 对「有什么」必须给同一个答案
;; ---------------------------------------------------------------------------

(def ^:private unlisted-attr
  "demo 模型里的一条 `:listed? false` 属性。engine 的 catalog 对所有调用方都不列它,
   点名它得 `:unknown-attribute`，与点名一个从未建模的属性逐字相同（D2.28）。"
  :cis.master.part/entry_id)

(def ^:private listed-sibling
  "同一族名字里**登记了的**那一条：证明这条测试挡下的是 `:listed?`,
   不是「凡叫 entry_id 的都没了」。"
  :cis.sales.mso_order_detail/entry_id)

(deftest unlisted-attributes-are-not-searchable-test
  (testing "前提：demo 模型里这两条确实一个未登记、一个登记了"
    (is (false? (get-in @demo-model [:attributes unlisted-attr :listed?])))
    (is (not (false? (get-in @demo-model [:attributes listed-sibling :listed?])))))

  (testing "`:listed? false` 的属性不出现在全局命中里——search 指着一个 catalog
            否认存在的名字，等于教调用方去点一个必被拒绝的属性:
            「查不到」与「不存在」在消费端逐字相同，它分辨不出"
    (doseq [q ["entry_id" "entry_datetime" "u_version" "update_id"]]
      (let [ids (into #{} (map :id) (:hits (search/search (idx) q rev)))]
        (is (not-any? #(false? (get-in @demo-model [:attributes % :listed?])) ids)
            (str q " 的命中里混进了 :listed? false 的属性")))))

  (testing "登记了的同族属性照旧搜得到——挡下的是 `:listed?`，不是这个名字"
    (is (contains? (into #{} (map :id) (:hits (search/search (idx) "entry_id" rev)))
                   listed-sibling)))

  (testing "词条命中的 `:bindings` 也不带未登记属性：绑定是让人接着点的坐标,
            指向一个 catalog 拒绝存在的名字，是同一个分歧退后一步"
    (doseq [h (:hits (search/search (idx) "录入人" rev))
            :when (= :term (:type h))]
      (is (not-any? #(false? (get-in @demo-model [:attributes (:attribute %) :listed?]))
                    (:bindings h)))
      (is (= (:binding-count h) (count (:bindings h)))
          "计数与清单同源——两者不同步正是 D7.11 明令避免的撕裂态")))

  (testing "scoped 对未登记属性不给裁决：假的 `:direct` 比 `:none` 更坏
            ——`:none` 让人回退搜索，一个假的直中让人确信自己拿对了"
    (let [r (search/search-attrs (idx) "entry_id" (namespace unlisted-attr) rev)]
      (is (= :none (:verdict r)))
      (is (nil? (:attribute r))))
    (testing "而登记了的那条同名属性照旧直中"
      (let [r (search/search-attrs (idx) "entry_id" (namespace listed-sibling) rev)]
        (is (= :direct (:verdict r)))
        (is (= listed-sibling (:attribute r)))))))

(deftest coverage-precision-and-fallback-test
  (let [model {:terms {} :namespaces {"demo.x" {:label "Demo"}}
               :attributes (into {:demo.x/coverage_rate {:type :string :label "Coverage rate"}}
                             (for [n (range 500)] [(keyword "demo.x" (str "status_" n))
                              {:type :string :label (str "Status " n) :description "created date state category"}]))}
        i (search/build-index model 1)]
    (try
      (is (= [:demo.x/coverage_rate] (mapv :id (:hits (search/search i "coverage rate" 1)))))
      (is (= [:demo.x/coverage_rate] (mapv :id (:hits (search/search i "coverage rate" 1 {:max-hits 20})))))
      (is (some #(= :demo.x/coverage_rate (:id %)) (:hits (search/search i "coverge rate" 1 {:max-hits 1000}))))
      (is (= [:search.query/over-limit] (mapv :code (:findings (search/search i "status" 1 {:max-hits 20})))))
      (finally (search/close-index! i)))))

(deftest per-word-fuzzy-recall-test
  (let [model {:terms {} :namespaces {}
               :attributes {:demo/coverage_rate {:label "Coverage rate"}
                            :demo/coverage_total {:label "Coverage total"}
                            :demo/date {:label "Created date"}
                            :demo/ratio {:label "Coverage ratio"}}}
        i (search/build-index model 1)]
    (try
      ;; Tiny corpus: hard df pruning must not erase common but necessary words.
      (doseq [q ["coverage rate" "coverge rate" "covrage rate"
                 "coverage raet" "coverage rtae" "cover rate"]]
        (is (= #{:demo/coverage_rate}
               (set (map :id (:hits (search/search i q 1 {:max-hits 20})))) ) q))
      (doseq [q ["coverage nonexistentxyz" "at" "覆盖率"]]
        (is (empty? (:hits (search/search i q 1))) q))
      (finally (search/close-index! i)))))
