(ns open.atlas.common.search.impl.match
  "**匹配层**——`resolve_target`（写闸门）与 `search_attrs`（读解析）共用的那一半
   （README 裁定 ③）。

   两者哲学相同、词汇不同：写侧的裁决是 `:exact-unique` / `:exact-ambiguous` /
   `:fuzzy` / `:none`，读侧的是直中 / 经词条 / `:ambiguous` / `:none`（D7.12）。
   **裁决层各自保留**：把它们合成一套，等于让读侧那个「错了只是白看一眼」的失败
   模式，给写侧那个「错了是静默的模型损坏」的定契约（principles「MCP 与工具面」）。

   **匹配层则必须只有一份**。它做三件事，三件都是「同一个字符串的不同书写要归到
   一处」：标识符折叠、缩写表展开、词条 label/synonym 查找。各写一份的下场本仓库
   已经栽过（`redact` 的两份逐字节相同的实现，CLAUDE.md）——两份实现总有一天分叉,
   而分叉的那天没有人会知道。第二份的形态未必是复制粘贴：server 的 MCP 面曾自带
   一份子串扫描充当 `search_attributes`，与本模块的匹配层各说各话，直到它被换掉
   （D7.10）。

   本 ns **不打分、不排序**。候选一律给 sorted-set：一个稳定但明确无意义的顺序,
   读的人不会误当排名（principles：歧义永不靠排名打破）。"
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; 标识符折叠
;; ---------------------------------------------------------------------------

(def ^:private camel-boundary
  ;; camelCase 归一（principles「存储与同步」：`-` `_` `.` `/` 与 camelCase 一并折叠,
  ;; 让 `sso/order-no`、`sso/order_no`、`sso/orderNo` 产生**逐字节相同**的词元流
  ;; ——不是碰巧排对，是同一个字符串）。
  ;; 两处边界：小写/数字后跟大写（toAcct → to Acct）、连续大写后跟大写+小写
  ;; （HTTPServer → HTTP Server）。
  #"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

(defn tokens
  "字符串 → 归一化词元向量。

   分隔符 `. / _ - 空白` 一律等价，camelCase 先拆再小写：属性引用是
   `cis.master.part/sku_no`、词条 ID 是 `common.audit/created_by`、人说的是
   「sku no」、代码里是 `skuNo`，四者本就是同一串词的不同书写。

   CJK 不在这里切——n-gram 是**索引层**的事（impl.gram）。匹配层要的是
   「同不同」，切成 gram 反而会让 `客户号` 与 `客户编号` 在这一层就糊成一片,
   而裁决层正需要它们不同。"
  [s]
  (->> (str/split (str s) camel-boundary)
       (mapcat #(str/split (str/lower-case %) #"[\s./_\-]+"))
       (remove str/blank?)
       vec))

(defn expand
  "按缩写表展开词元。表里没有的词原样留下——不认识就不认识，不猜。

   principles 要求**同时展开比较的两侧**：只展开查询侧，`\"po number\"` 对上
   `po_no` 就得靠子串巧合；两侧都展开之后，它们在同一个词面上相等或不等。"
  [table ts]
  (vec (mapcat (fn [t] (if-let [v (get table t)] (tokens v) [t])) ts)))

(defn table-for
  "生效的缩写表：全局表 + 该 namespace 的覆盖（principles「缩写是领域配置」——
   `no` 在一个领域是 `number`，隔两个 namespace 就是 `purchase order`）。

   **默认空**。空表不是缺陷：不认识的缩写让解析落到 `:fuzzy` / `:none`，那是
   诚实的回答；替 owner 猜一张表才是缺陷，猜错的形态是把目标解到另一个属性上。"
  [abbrev ns-str]
  (merge (:global abbrev) (get (:by-namespace abbrev) ns-str)))

(defn phrase [ts] (str/join " " ts))

(defn fold
  "一个说法 → 折叠后的规范短语。**两侧都走这一个函数**，这就是「匹配层只有一份」
   在代码上的具体含义：写闸门与读解析拿同一个输入得到逐字节相同的短语。"
  [abbrev ns-str s]
  (phrase (expand (table-for abbrev ns-str) (tokens s))))

;; ---------------------------------------------------------------------------
;; 词条 label / synonym 查找
;; ---------------------------------------------------------------------------

(defn semantic-entry
  "条目的**语义来源**：绑了词条就是词条条目，否则是自己（D2.26 绑定即封走语义
   ——绑定属性不再自带 label/synonyms，它们住在词条上）。"
  [terms entry]
  (if-let [t (:term entry)] (get terms t) entry))

(defn semantic-names
  "条目上所有**可被人称呼**的语义名字：label、synonyms，绑定时再加 `:term-context`。

   `:term-context`（D2.27：「购买方客户」/「收货方客户」）正是为消歧而存在的槽,
   匹配时不看它，等于把消歧机制关掉一半。"
  [terms entry]
  (let [sem (semantic-entry terms entry)]
    (->> (concat [(:label sem)] (:synonyms sem)
                 (when (:term entry) [(:term-context entry)]))
         (remove nil?)
         (remove str/blank?))))

(defn term-names
  "词条条目上所有可被人称呼的名字：ID 本身 + label + synonyms。
   global 搜索与 scoped 解析都靠它把「客户编号」认回 `cis.party/customer_no`。"
  [term-id entry]
  (->> (concat [term-id (:label entry)] (:synonyms entry))
       (remove nil?)
       (remove str/blank?)))

;; ---------------------------------------------------------------------------
;; 短语索引与命中
;; ---------------------------------------------------------------------------

(def id-order
  "候选的**稳定但无意义**的顺序：按 `pr-str` 字典序。
   刻意不按任何质量维度排——排了就会有人当成排名读。"
  #(compare (pr-str %1) (pr-str %2)))

(defn empty-hits [] (sorted-set-by id-order))

(defn build-phrase-index
  "`[{:id … :ns … :names [说法…]}…]` × 缩写表 → `{折叠短语 #{id…}}`。

   `:ns` 决定用哪张缩写覆盖表；`:id` 是调用方自己的身份形状（写侧是
   `[类型 id]`，读侧是属性 kw / 词条 ID），本 ns 不解释它。"
  [entries abbrev]
  (reduce
   (fn [acc {:keys [id ns names]}]
     (let [tbl (table-for abbrev ns)]
       (reduce (fn [acc nm]
                 (update acc (phrase (expand tbl (tokens nm)))
                         (fnil conj (empty-hits)) id))
               acc names)))
   {} entries))

(defn exact-hits
  "折叠短语完全相等的候选。**这是共用匹配层的产出**——两侧对同一个输入词拿到的
   这个集合必须逐字节相同（B4.3 验收断言），裁决则各自不同。"
  [by-phrase folded-query]
  (into (empty-hits) (get by-phrase folded-query)))

(defn subset-hits
  "词元子集匹配：查询的每个词都在候选名字里出现。

   **刻意不做编辑距离、不做 BM25**——它的用处只有一个：告诉调用方「大概是这些」。
   让它更聪明不会改变这个结论，只会让人开始相信它的排序。"
  [by-phrase folded-query]
  (let [qs (set (tokens folded-query))]
    (into (empty-hits)
          (mapcat val (filter (fn [[p _]] (every? (set (tokens p)) qs)) by-phrase)))))
