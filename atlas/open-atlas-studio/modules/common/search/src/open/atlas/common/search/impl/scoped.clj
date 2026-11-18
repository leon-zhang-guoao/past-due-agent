(ns open.atlas.common.search.impl.scoped
  "`search_attrs(q, entity)` —— **读侧解析**的四个离散裁决（D7.12 / §7.3）。

   直中（local name）/ 经词条命中（标注经由哪个词条）/ `:ambiguous`（候选各带
   `:term-context`）/ `:none`（如实说没有）。**不猜。**

   ## 与 `resolve_target` 的分工（README 裁定 ③）

   **匹配层共用**（impl.match：标识符折叠、缩写展开、词条 label/synonym 查找）,
   **裁决层各自保留**。两套裁决词汇不同不是疏忽：写侧错了是静默的模型损坏,
   读侧错了只是白看一眼——合成一套等于让便宜的失败模式给昂贵的那个定契约
   （principles「MCP 与工具面」）。

   ## 为什么这里没有 `:fuzzy`

   D7.12 的裁决恰好四个，`:none` 的语义是「如实说没有并**回退搜索**」——
   模糊召回是 `search` 的事，塞进 scoped 会让「解析到了」与「大概是这些」
   在同一个键面上混起来，而调用方正要靠这个区别决定要不要反问用户。"
  (:require [open.atlas.common.search.impl.docs :as docs]
            [open.atlas.common.search.impl.finding :as f]
            [open.atlas.common.search.impl.match :as match]))

(defn- attrs-of
  "本 entity 的属性，**不含 `:listed? false`**（D2.28，见 impl.docs/listed?）。

   scoped 的裁决直接被读成「就是这个字段」，调用方拿着它去 EQL 里点名——
   而 catalog 对未登记属性回 `:unknown-attribute`。解析成一个 catalog 拒绝存在
   的名字，比 `:none` 更坏：`:none` 让人回退搜索，一个假的 `:direct` 让人确信
   自己拿对了。"
  [model entity]
  (into (sorted-map)
        (filter (fn [[k e]] (and (= entity (namespace k)) (docs/listed? e))))
        (:attributes model)))

(defn- candidate
  "候选随行 `:term-context`（D2.27 的多绑定硬门保证它存在且互异）——
   歧义要靠语境打破，不靠排名（裁定 ②）。"
  [model k]
  (let [e (get-in model [:attributes k])]
    (cond-> {:attribute k}
      (:term-context e) (assoc :term-context (:term-context e))
      (:term e)         (assoc :term (:term e)))))

(defn search-attrs
  "`(q, entity)` → 四个离散裁决之一，或结构化拒绝。

   `opts`：`:revision`（陈旧即弃，由接口 ns 先判）、`:abbreviations`。"
  [model q entity {:keys [abbreviations]}]
  (let [ns-str (str entity)]
    (if-not (contains? (:namespaces model) ns-str)
      ;; 「这个 entity 里没有」与「没有这个 entity」是两个不同的答案，
      ;; 回 `:none` 会让调用方拿一个拼错的 entity 名反复搜下去。
      {:findings [(f/make :search.scope/unknown-entity :error [:search_attrs :entity]
                          "entity is not a namespace in this model"
                          :expected "a declared namespace"
                          :got (f/redact ns-str)
                          :refs ["D7.12"])]}
      (let [attrs  (attrs-of model ns-str)
            folded (match/fold abbreviations ns-str q)
            fold*  #(match/fold abbreviations ns-str %)
            ;; ── 直中：用户的词 = 本 entity 属性的 local name，**词条不参与** ──
            direct (for [[k _] attrs
                         :when (or (= folded (fold* (name k)))
                                   (= folded (fold* (str (symbol k)))))]
                     k)
            ;; ── 经词条：= 词条名/label/synonym，且本 entity 有属性绑它 ──
            terms  (for [[id e] (into (sorted-map) (:terms model))
                         :when (some #(= folded (fold* %)) (match/term-names id e))]
                     id)
            via    (for [[k e] attrs :when (contains? (set terms) (:term e))] k)]
        (cond
          (= 1 (count direct))
          {:verdict :direct :query q :entity ns-str :attribute (first direct)}

          ;; 折叠后两个 local name 撞在一起也是歧义。**不挑一个**——
          ;; 挑一个正是 D7.12 说的那种「答错且不自知」。
          (seq direct)
          {:verdict :ambiguous :query q :entity ns-str
           :candidates (mapv #(candidate model %) direct)}

          (= 1 (count via))
          (let [k (first via)]
            {:verdict :via-term :query q :entity ns-str :attribute k
             ;; 标注**经由哪个词条**：调用方要能把「你问的『客户编号』我按
             ;; cis.party/customer_no 解成了 cust_no」读给用户听（D7.12 `:asked-as`）。
             :term (get-in model [:attributes k :term])
             :term-label (get-in model [:terms (get-in model [:attributes k :term]) :label])})

          (seq via)
          {:verdict :ambiguous :query q :entity ns-str
           :candidates (mapv #(candidate model %) via)}

          ;; 零绑定 → 如实说没有并回退搜索（D7.12）。
          :else {:verdict :none :query q :entity ns-str})))))
