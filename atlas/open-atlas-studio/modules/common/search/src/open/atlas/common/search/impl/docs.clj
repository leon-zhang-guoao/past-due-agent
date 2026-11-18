(ns open.atlas.common.search.impl.docs
  "方言模型 → **三类平级文档**（README 裁定 ① / D7.11）。

   ## 唯一的承重规则：语义文本只索引一次

   绑定属性的语义文本（词条的 label / synonyms / description）**只在词条文档里
   索引一次，不向属性文档展开复制**。

   这是「洪泛在结构上解决」的全部内容：demo 模型里 `cis.party/customer_no` 绑着
   7 个属性，真实 CIS 里 `created_by` 绑着 381 个。复制展开的话，搜「客户编号」
   得到 7 条一模一样的命中，搜「录入人」得到 381 条——**把所有别的东西埋掉**。
   不复制，就得到一条词条命中，带绑定计数与 scope 内的绑定清单。

   于是**绑定属性的文档里只有它自己的标识符**（`to_acct_no`、全限定 kw）。
   这不是信息丢失：那个属性的语义在词条文档里，而词条文档带着指回来的绑定清单
   （§7.3「词条绑定反查并进 search 的词条命中响应」）。

   ## 三类是平级的

   词条 / entity / 属性（组文档随 D2.25 组移除删去）。类型间**固定顺序**、
   不比分数（裁定 ②），所以这里只负责把三类都造出来，排序是 impl.index 的事。

   ## 两道过滤，来源不同

   - **`:listed? false` 由本模块自己执行**（D2.28）。它是**建模判断**——
     「这一列不是这张图对外语义的一部分」——而 engine 的 catalog 对所有调用方
     一律不列它，点名它得 `:unknown-attribute`，与点名一个不存在的属性逐字相同。
     索引若照收，search 就会指着一个 catalog 拒绝存在的名字：**同一个模型的两个
     视图必须对「有什么」给同一个答案**，因为「查不到」与「不存在」在消费端逐字
     相同——调用方分辨不出，只会照着搜索结果去查，然后拿到一个否认它存在的拒绝。
   - **`:visible?` 是调用方给的文档抑制器**（`entity-docs` / `attribute-docs` /
     `build` 都吃它，缺省全可见）。它不表达模型语义，只表达「这次调用把语料缩到
     哪一块」——调用方缩小语料是 over-limit finding 自己给的补救办法。
     `:restricted` 那套访问档位已整套退役（D5.11），这个钩子与它无关。"
  (:require [open.atlas.common.search.impl.gram :as gram]
            [open.atlas.common.search.impl.match :as match]))

(def doc-types
  "三类平级文档。顺序即类型间的固定呈现顺序（裁定 ②），
   前面还有一个不属于任何类型的 `:exact-name` 桶，见 impl.index。"
  [:term :entity :attribute])

(defn- attr-ns [k] (namespace k))
(defn- attr-local [k] (name k))

(defn listed?
  "Is this attribute part of the graph's outward semantics (D2.28)?

   `:listed? false` means it is not: the engine catalog leaves it out for
   every caller, and naming it is refused as `:unknown-attribute` — byte-for-byte
   the same refusal as naming an attribute that was never modelled. Search
   therefore must not surface it either. 「查不到」与「不存在」在消费端逐字相同,
   so the two views of one model have to agree on what exists; a search hit the
   catalog then denies teaches the caller a name that cannot be used."
  [entry]
  (not (false? (:listed? entry))))

(defn bindings-of
  "词条 ID → 绑它的属性清单（按 kw 字典序，稳定可 diff）。
   这就是「第二跳是**目录读**（精确键值反查），不是搜索」（D7.11）——
   它在建索引时算一次，命中时直接随行，省掉一次往返（§7.3 默认走法 ①）。

   Unlisted attributes are left out of the list, not only out of the attribute
   documents (D2.28): a binding is a coordinate the caller is meant to follow,
   and pointing at a name the catalog denies is the same disagreement one step
   removed. The binding COUNT drops with it — a count that includes attributes
   the caller can never name is not a count of anything they can use."
  [model]
  (reduce-kv (fn [acc k {:keys [term term-context] :as entry}]
               (cond-> acc
                 (and term (listed? entry))
                 (update term (fnil conj [])
                         (cond-> {:attribute k}
                           term-context (assoc :term-context term-context)))))
             {}
             (into (sorted-map) (:attributes model))))

(defn term-docs
  "词条文档。`:doc/names` 是可被人称呼的名字（精确名桶用），
   `:doc/text` 是喂 FTS5 的词流——**绑定属性的语义全部汇集在这里**。"
  [model abbrev]
  (for [[id entry] (into (sorted-map) (:terms model))
        :let [b (get (bindings-of model) id [])]]
    {:doc/type          :term
     :doc/id            id
     :doc/label         (:label entry)
     :doc/names         (match/term-names id entry)
     :doc/bindings      b
     :doc/binding-count (count b)
     :doc/raw-description (:description entry)
     :doc/text          (gram/text abbrev (attr-ns (keyword id))
                                   (concat (match/term-names id entry)
                                           [(:description entry)]))}))

(defn entity-docs
  "entity（= namespace）文档。`visible?` 是 B7 的档位钩子（缺省全可见）。"
  ([model abbrev] (entity-docs model abbrev (constantly true)))
  ([model abbrev visible?]
   (for [[id entry] (into (sorted-map) (:namespaces model))
         :when (visible? id)]
     {:doc/type  :entity
      :doc/id    id
      :doc/label (:label entry)
      :doc/names (remove nil? [id (:label entry)])
      :doc/raw-description (:description entry)
      :doc/text  (gram/text abbrev id [id (:label entry) (:description entry)])})))

(defn attribute-docs
  "属性文档。

   **绑定属性只索引自己的标识符**（裁定 ①）——`:doc/text` 里没有词条的一个字。
   未绑定属性才把自己的 label / synonyms / description 索引进来：它们的语义
   没有别处可去，不索引就等于搜不到。

   `:listed? false` 的属性整条不出文档（D2.28，见 `listed?`）。"
  ([model abbrev] (attribute-docs model abbrev (constantly true)))
  ([model abbrev visible?]
   ;; **Join attributes are indexed like any other attribute** (D2.29). This
   ;; body needs no branch for them, and that is the point — a join carries
   ;; `:label`, `:synonyms` and `:description` exactly as a scalar does, and
   ;; none of the indexed material is `:type`-dependent.
   (for [[k entry] (into (sorted-map) (:attributes model))
         :when (and (listed? entry) (visible? k))
         :let [ns-str (attr-ns k)
              bound? (some? (:term entry))
              idents [(attr-local k) (str (symbol k))]
              own    (when-not bound?
                       (concat [(:label entry)] (:synonyms entry) [(:description entry)]))]]
     (cond-> {:doc/type  :attribute
              :doc/id    k
              :doc/label (:label (match/semantic-entry (:terms model) entry))
              :doc/names (cond-> (vec idents)
                           ;; 精确名桶只收**标识符**：绑定属性的 label 属于词条,
                           ;; 收进来就等于把裁定 ① 从后门放回去。
                           (not bound?) (into (remove nil? (:synonyms entry)))
                           (and (not bound?) (:label entry)) (conj (:label entry)))
              :doc/raw-description (when-not bound? (:description entry))
              :doc/text  (gram/text abbrev ns-str (concat idents own))}
       bound?                  (assoc :doc/term (:term entry))
       (:term-context entry)   (assoc :doc/term-context (:term-context entry))))))

(defn all-docs
  "三类文档，按 `doc-types` 的顺序拼起来。"
  ([model abbrev] (all-docs model abbrev (constantly true)))
  ([model abbrev visible?]
   (concat (term-docs model abbrev)
           (entity-docs model abbrev visible?)
           (attribute-docs model abbrev visible?))))
