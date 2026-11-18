(ns open.atlas.common.search
  "**B4.3 搜索索引**（D7.11 / D7.12）。跨模块只许 require 本 ns；
   `open.atlas.common.search.impl.*` 视为私有。

   六条裁定写在模块 README，这里只记它们在接口上的落点：

   - ① 三类平级文档（词条/entity/属性；组随 D2.25 退役），
     **绑定属性的语义文本只进词条文档**
     ——所以 `search` 搜「客户编号」得到**一条**词条命中带 7 个绑定，
     不是 7 条等分的属性命中；
   - ② BM25 只在类型内排序、类型间固定顺序、**响应里没有分数字段**；
   - ③ 与 `resolve_target` **共用匹配层、不共用裁决层**——匹配层就是本 ns 导出的
     `fold` / `build-phrase-index` / `exact-hits` / `subset-hits` / `semantic-names`,
     `studio/tools` 的写闸门调用它们，自己只留裁决；
   - ④ 陈旧即弃：每次调用都要给 `:revision`，对不上**拒绝**而不是带警告地用；
   - ⑤ 中文靠自己切 2/3-gram，不装自定义 tokenizer；
   - ⑥ 吃 `(方言模型, revision)`，不吃 Datahike 连接。

   **两道过滤，来源不同**（README 边界一节）：

   - `:listed? false` 由本模块自己执行（D2.28）：它是模型说「这一列不在对外语义
     里」，engine 的 catalog 对所有调用方都不列它，索引照收就会指着一个 catalog
     否认存在的名字——**同一个模型的两个视图必须对「有什么」给同一个答案**;
   - `:visible?` 是**调用方给的文档抑制器**（`build-index` 的 opt，缺省全可见）:
     它不表达模型语义，只表达「这次调用把语料缩到哪一块」，例如按 over-limit
     finding 自己给的补救办法缩小语料。访问档位已整套退役（D5.11），这个钩子
     与档位无关，返回形状也不因它而变。

   契约：docs/contracts/finding-v1.md（所有拒绝的形状）。"
  (:require [open.atlas.common.search.impl.docs :as docs]
            [open.atlas.common.search.impl.finding :as finding]
            [open.atlas.common.search.impl.gram :as gram]
            [open.atlas.common.search.impl.index :as index]
            [open.atlas.common.search.impl.match :as match]
            [open.atlas.common.search.impl.scoped :as scoped]))

;; ---------------------------------------------------------------------------
;; 索引（裁定 ④⑥）
;; ---------------------------------------------------------------------------

(defn build-index
  "`(方言模型, revision)` → 索引句柄。opts：`:abbreviations` `:path` `:visible?`。"
  ([model revision] (build-index model revision {}))
  ([model revision opts] (index/build model revision opts)))

(defn close-index! [idx] (index/close! idx))

(defn index-revision [idx] (:search/revision idx))

(defn stale?
  "索引对不上这个 revision 吗（裁定 ④）。"
  [idx revision] (index/stale? idx revision))

(defn refresh-index!
  "陈旧即**整体作废重建**——不是增量补，也不是「属性文档新、词条文档旧」的
   撕裂态（裁定 ④）。revision 相同则原样返回，不白建一次。"
  ([idx model revision] (refresh-index! idx model revision {}))
  ([idx model revision opts]
   (if (stale? idx revision)
     (do (close-index! idx) (build-index model revision opts))
     idx)))

;; ---------------------------------------------------------------------------
;; 两个问题，一个索引（§7.3）
;; ---------------------------------------------------------------------------

(defn search
  "global：「哪里有这个概念」。类型化命中，`bucket-order` 固定顺序，**无分数**。"
  ([idx q revision] (search idx q revision {}))
  ([idx q revision opts] (index/search idx q (assoc opts :revision revision))))

(defn search-attrs
  "scoped：「这个 entity 里是哪个字段」。四个离散裁决：
   `:direct` / `:via-term` / `:ambiguous` / `:none`（D7.12）。**不猜。**"
  ([idx q entity revision] (search-attrs idx q entity revision {}))
  ([idx q entity revision opts]
   (if (stale? idx revision)
     {:findings [(index/stale-finding idx revision [:search_attrs])]}
     (scoped/search-attrs (:search/model idx) q entity
                          (assoc opts :abbreviations (:search/abbrev idx))))))

(def bucket-order
  "类型间的固定顺序（裁定 ②）：`[:exact-name :term :entity :attribute]`。"
  index/bucket-order)

(def doc-types docs/doc-types)

;; ---------------------------------------------------------------------------
;; 匹配层（裁定 ③）—— `resolve_target` 从这里取，不再自带一份
;; ---------------------------------------------------------------------------

(def tokens             "标识符折叠：`. / _ - 空白` 与 camelCase 一并归一。" match/tokens)
(def expand             "按缩写表展开词元（两侧都展开）。" match/expand)
(def abbrev-table       "全局表 + 按域覆盖。" match/table-for)
(def phrase             "词元向量 → 短语。" match/phrase)
(def fold               "说法 → 折叠后的规范短语。**两侧走同一个函数。**" match/fold)
(def semantic-names     "条目的可称呼语义名（绑定则走词条，带 `:term-context`）。" match/semantic-names)
(def semantic-entry     "条目的语义来源（词条条目或它自己）。" match/semantic-entry)
(def term-names         "词条的可称呼名：ID + label + synonyms。" match/term-names)
(def build-phrase-index "`[{:id :ns :names}…]` × 缩写表 → `{短语 #{id}}`。" match/build-phrase-index)
(def exact-hits         "折叠短语完全相等的候选（共用匹配层的产出）。" match/exact-hits)
(def subset-hits        "词元子集匹配。不做编辑距离、不做 BM25。" match/subset-hits)
(def candidate-order    "候选的稳定但无意义的顺序（不是排名）。" match/id-order)

;; ---------------------------------------------------------------------------
;; n-gram（裁定 ⑤）—— 索引期与查询期共用，测试要能把它关掉做反面证明
;; ---------------------------------------------------------------------------

(def gram-terms "一段文本 → 喂 FTS5 的词序列（CJK gram + Latin 整词与 gram）。" gram/terms)
(def gram-text  "多段文本 → FTS5 那一列的值。" gram/text)

(defn finding?
  "x 是否为合法 `search.*` finding（finding-v1 §2 封闭 schema）。"
  [x]
  (finding/valid? x))

(def finding-codes
  "本模块已发布的 `:code` 注册表（finding-v1 §6）。"
  finding/codes)
