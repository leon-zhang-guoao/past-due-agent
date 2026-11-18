(ns open.atlas.studio.tools.impl.query-model
  "`query_model` —— 具名查询清单，答「模型缺什么」（§7.3 建模面那一行；
   与词法搜索相反的问题）。

   ## 裁定 ⑥ 是本 ns 的形状

   README：「不是『你这儿有问题』，是『你这儿有问题，这个 action 修它』。
   一份修不动的体检报告读一次就没人再读了。」于是每条缺口**必带** `:action`，
   形状就是 `execute_action` 的入参（pool 的 `execute-action` docstring）。

   有些 action **直接可跑**（漂移属性的 delete、零绑定词条的 delete：修法唯一且
   机器算得出全部参数）；有些必须由人补内容（描述、同义词、码值——那些是
   D2.14 明说「机器一填就是填充文本」的东西）。后者在 action 里把待补的位置摆成
   `:tools/fill-in`，并在 `:fill` 里逐个点名路径。

   **为什么用一个显式标记而不是留空**：attribute 一类的 update 是
   merge patch，patch 里显式 `nil` = 删除该键（pool/impl/model 的 `:update` 一行）。
   留空或写 nil 的「骨架」拿去跑，恰好把该补的键删掉——一个看起来完全合理的
   action 干了相反的事。标记是个非法值，照原样跑会被 B0 结构化拒绝，
   这是**故意的**：拒绝比静默的反向修改便宜得多。

   ## 具名查询是清单，不是查询语言

   七条具名查询覆盖 README「覆盖的账单项」那张表里**属于缺口**的那几行（描述与
   同义词一行拆两条查询）。第八行「未列出属性数」（D2.28）不在其中：它是账单项
   而不是缺陷，报告里单独成节 `:metrics`，**不带 `:action`**——理由见下面
   `m-unlisted` 抬头。

   **不开放自由查询**——那是 D2.8「查询纪律」的同一条线：一个能生成任意
   查询的入口，其失败模式无人负责。指纹-`:values` 偏差（D7.6）不在本片：指纹是 B7。

   ## 判定读的是**方言形状**，不是 Datahike

   `pool/as-dialect` 已经把整池拼回方言，词条继承、组成员这些语义在那个形状上
   就是它们本来的样子。绕开它直接写 datalog，等于把 dialect-v1 的语义再表达一遍
   ——CLAUDE.md「需要跨模块共用的东西，摆到接口上，不要各写一份」。"
  (:require [clojure.string :as str]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.tools.impl.repair :as repair]))

(def fill-in
  "待人补内容的位置标记（见 ns 注释：非法值是有意的）。"
  :tools/fill-in)

(def queries
  "具名查询的固定清单与顺序。顺序进报告，两次调用逐字节相同。"
  [:attribute/missing-description
   :attribute/missing-synonyms
   :namespace/no-identity
   :attribute/declaration-drift
   :attribute/code-format-without-values
   :term/zero-binding
   :attribute/multi-binding-without-context
   ;; 8.4.21 裁定十五 ③. Last because it is the newest, and the order is the
   ;; report's order: two calls on one model print the same bytes.
   :namespace/unreachable])

(def query-set (set queries))

;; ---------------------------------------------------------------------------
;; 模型上的派生事实
;; ---------------------------------------------------------------------------

(defn- attr-ns [akw] (namespace akw))

(defn- leaf-keywords
  "EQL 语法树里的属性 keyword。**逐位置点名而不是深度遍历**是 pool/impl/model
   的 `attr-refs` 定的调子，但这里方向相反：那边漏报只会放过一次 delete，
   这边漏报会**多报**一条漂移缺口。所以这里取全量 keyword——
   `:output` 的 join 键本身就是属性（D2.16；D2.29 之后是同一张注册表里的
   join 属性），把它算进闭包是对的。"
  [x]
  (filter keyword? (tree-seq coll? seq x)))

(defn- existence-closure
  "D2.2 的存在性闭包：resolver `:output` ∪ mutation `:params` ∪ mutation `:returns`。"
  [model]
  (into #{}
        (concat (mapcat #(leaf-keywords (:output %)) (:resolvers model))
                (mapcat #(keys (:params %)) (:mutations model))
                (mapcat #(leaf-keywords (:returns %)) (:mutations model)))))

(defn- effective
  "词条绑定封走本地语义键（D2.26）：`:label` `:description` `:synonyms`
   `:format` `:values` 有 `:term` 时来自词条。判「缺不缺」必须看**生效值**，
   否则 178 个绑了词条的属性会被整批报成「无描述」——那种报告读一次就废了。"
  [terms attr k]
  (if-let [t (:term attr)]
    (get (get terms t) k)
    (get attr k)))

;; ---------------------------------------------------------------------------
;; 缺口构造
;; ---------------------------------------------------------------------------

(defn- fill-paths
  "action 里所有摆着 `fill-in` 的位置，路径形式，确定性顺序。
   空向量 = 这条 action 直接可跑。"
  [action]
  (letfn [(walk [prefix v]
            (cond
              (= fill-in v) [prefix]
              (map? v)      (mapcat (fn [[k x]] (walk (conj prefix k) x))
                                    (sort-by pr-str v))
              :else         nil))]
    (vec (walk [] action))))

(defn- gap [query target message action]
  {:query   query
   :target  target
   :message message
   :action  action
   :fill    (fill-paths action)})

(defn- rev [revisions t id] (get revisions [t id] fill-in))

;; ---------------------------------------------------------------------------
;; 七条具名查询
;; ---------------------------------------------------------------------------

(defn- q-missing-description [model revisions]
  (let [terms (:terms model)]
    (for [[akw a] (sort-by pr-str (:attributes model))
          :when   (str/blank? (str (effective terms a :description)))]
      (gap :attribute/missing-description [:attribute akw]
           "attribute has no effective :description (not covered by a term)"
           {:action :update :type :attribute :id akw
            :entry {:description fill-in}
            :expected_revision (rev revisions :attribute akw)}))))

(defn- q-missing-synonyms [model revisions]
  (let [terms (:terms model)]
    (for [[akw a] (sort-by pr-str (:attributes model))
          :when   (empty? (effective terms a :synonyms))]
      (gap :attribute/missing-synonyms [:attribute akw]
           "attribute has no effective :synonyms (search and resolve_target have only its name to go on)"
           {:action :update :type :attribute :id akw
            :entry {:synonyms fill-in}
            :expected_revision (rev revisions :attribute akw)}))))

(defn- q-no-identity [model _revisions]
  ;; **Zero versus non-zero**, which is why a compound key needs nothing here
  ;; (owner 2026-09-07, doc 106 §106.14 item 14): several `:identity? true`
  ;; attributes in one namespace ARE its key, and a namespace that has any of
  ;; them is not missing one.
  (let [with-id  (into #{} (for [[akw a] (:attributes model) :when (:identity? a)] (attr-ns akw)))
        ;; **只数标量属性**（D2.29）：`:identity?` 是标量属性的键——join 属性
        ;; 连写都写不出它（封闭 map）。一个只住着 join 属性的 namespace 因此
        ;; 与「一个属性都没有」是同一种情况，理由见下。
        with-any (into #{} (map attr-ns) (keys (dialect/scalar-attributes model)))]
    (for [[n _] (sort-by pr-str (:namespaces model))
          ;; **没有标量属性的 namespace 不报这一条。** 它不是「忘了标身份」,
          ;; 而是根本没有可标的东西——`cis.sales.mso_fulfilment` 就是这样一个
          ;; **纯 join 锚**：它自己没有列，只挂着几条通往别的 entity 的 join
          ;; 属性（D2.29 之前那几条是 navigation）。
          ;; 对它报「没有 identity」会把人引去标一个不存在的属性——**指错修法的
          ;; 缺口比不报更贵**。「entity 一个属性都没有」若哪天成为一类真缺陷，
          ;; 那是它自己的一条查询，不是借这一条的消息去说另一件事。
          :when (and (contains? with-any n) (not (contains? with-id n)))]
      (gap :namespace/no-identity [:namespace n]
           "namespace has no attribute marked :identity? true (principles: entity 由 namespace 推导)"
           ;; 修它的 action 打在**属性**上——identity 是属性的键。哪一个属性是
           ;; 身份，机器不该猜：猜错的形态是把一个普通列钉成 entity 入口。
           {:action :update :type :attribute :id fill-in
            :entry {:identity? true}
            :expected_revision fill-in}))))

(defn- referenced-attributes
  "被 link 端点或 metric 维度指着的属性。**漂移缺口据此选修法**，见下。"
  [model]
  (into (into #{} (mapcat (juxt :from :to)) (:links model))
        (mapcat :dimensions) (vals (:metrics model))))

(defn- q-declaration-drift [model revisions]
  (let [closure (existence-closure model)
        refd    (referenced-attributes model)]
    (for [[akw _] (sort-by pr-str (:attributes model))
          :when   (not (contains? closure akw))]
      (gap :attribute/declaration-drift [:attribute akw]
           ;; D2.2：日常写入只警告，切版本体检拒绝。工具面报的是那份体检的预演。
           (if (contains? refd akw)
             (str "attribute is registered but appears in no resolver :output nor "
                  "mutation :params/:returns (D2.2 existence closure), and it is a link "
                  "endpoint or metric dimension — so deleting it is not available; "
                  "name a resolver whose :output should produce it")
             (str "attribute is registered but appears in no resolver :output "
                  "nor mutation :params/:returns (D2.2 existence closure); "
                  "the alternative fix is to add it to a producing resolver"))
           ;; 裁定 ⑥ 要的是「**能修它**的 action」。被 link/metric 指着的属性
           ;; delete 一定被 `:pool.reference/inbound` 拒绝——递一条**必然被拒**的
           ;; 命令，正是「读一次就没人再读」的那种报告。于是这一支给待补型的
           ;; 「加进哪条 resolver 的 `:output`」，让人补的是真正要做的决定。
           (if (contains? refd akw)
             {:action :update :type :resolver :id fill-in
              :entry {:output fill-in}
              :expected_revision fill-in}
             {:action :delete :type :attribute :id akw
              :expected_revision (rev revisions :attribute akw)})))))

(defn- q-code-without-values [model revisions]
  (let [terms (:terms model)]
    (for [[akw a] (sort-by pr-str (:attributes model))
          :when   (and (= :code (effective terms a :format))
                       (empty? (effective terms a :values)))]
      (gap :attribute/code-format-without-values [:attribute akw]
           ":format :code but no effective :values (the code table has no meanings)"
           {:action :update :type :attribute :id akw
            :entry {:values fill-in}
            :expected_revision (rev revisions :attribute akw)}))))

(defn- q-term-zero-binding [model revisions]
  (let [bound (into #{} (keep (comp :term val) (:attributes model)))]
    (for [[tid _] (sort-by pr-str (:terms model))
          :when   (not (contains? bound tid))]
      (gap :term/zero-binding [:term tid]
           ;; 零绑定词条是「字典里有个词，没有任何一列用它」——catalog 带不出它
           ;; （D2.26 只登记真有属性绑着的词条），于是它对消费端等于不存在。
           "term is registered but no attribute binds it"
           {:action :delete :type :term :id tid
            :expected_revision (rev revisions :term tid)}))))

;; ---------------------------------------------------------------------------
;; 指标（不是缺口）
;; ---------------------------------------------------------------------------
;;
;; **「未列出属性」是账单项，不是缺陷。** 不列出（`:listed? false`）是一次建模
;; 判断（D2.28：这一列不是这张图对外语义的一部分），不是要修的缺口——把它混进
;; gap 清单会诱使人把它追到 0，而那等于取消这个建模手段。但它必须被看见：
;; 不列出的东西最容易烂掉，而烂掉没人看得见（§7.3 把它列在**账单项**一行）。
;;
;; 于是它单独成节，**不带 `:action`**：报告给数字与按 entity 的分布。

(defn- m-unlisted
  "未列出（`:listed? false`）属性的数量与按 entity 的分布（D2.28）。"
  [model]
  (let [unlisted (for [[akw a] (:attributes model)
                       :when (false? (:listed? a))]
                   akw)]
    {:count (count unlisted)
     :by-entity (into (sorted-map) (frequencies (map attr-ns unlisted)))}))

(defn- root-collection-namespaces
  "Entities a query can start AT: some resolver with an empty `:input` produces
   an attribute of theirs (the unanchored collection, D2.13)."
  [model]
  (into #{} (comp (filter #(empty? (:input %)))
                  (mapcat #(leaf-keywords (:output %)))
                  (keep attr-ns))
        (:resolvers model)))

(defn- navigated-namespaces
  "Entities something POINTS AT: a join attribute on another entity targets
   them (D2.29 — a navigation IS a join attribute now)."
  [model]
  (into #{} (keep (fn [[akw a]]
                    (when-let [t (:target a)]
                      (when (not= (str t) (str (attr-ns akw))) (str t)))))
        (:attributes model)))

(defn- q-unreachable
  "**Entities no query can reach** (8.4.21 裁定十五 ③).

   No root collection to start at, and nothing pointing at them — so every
   query naming their attributes is refused with `:engine.request/unreachable`,
   and until this query existed nobody found out before a person asked. Each
   one carries the SAME `:repair` the refusal would carry, from the one
   function that infers it.

   `:action` is nil here and that is not an omission: 裁定六's `:action` is a
   POOL action, and what fixes an unreachable entity is a tool call (a child
   collection, or a link a person names). The fix rides in `:repair`, which is
   the shape every other face carries it in."
  [model revisions]
  (let [roots  (root-collection-namespaces model)
        toward (navigated-namespaces model)
        ;; Only entities that HAVE attributes: a namespace with none is not an
        ;; unreachable entity, it is an empty one (same restraint as
        ;; `q-no-identity`'s pure join anchors).
        with-attrs (into #{} (map attr-ns) (keys (:attributes model)))]
    (for [[n _] (sort-by pr-str (:namespaces model))
          :let  [n (str n)]
          :when (and (contains? with-attrs n)
                     (not (contains? roots n))
                     (not (contains? toward n)))]
      (assoc (gap :namespace/unreachable [:namespace n]
                  (str "no query can reach " n ": nothing starts at it and nothing points at it, "
                       "so every attribute of it answers «not reachable». Hang it under the "
                       "entity it belongs to (see :repair)")
                  nil)
             :repair
             (:repair (repair/repair-for
                       {:code :engine.request/unreachable
                        :severity :error
                        :path [:namespace n]
                        :message ""
                        :attributes [(keyword n "*")]}
                       {:model model}))
             ;; The revision table is read for the same reason every other
             ;; query reads it: a caller that wants to write must know which
             ;; revision it saw. Nothing here is a pool write, so it is the
             ;; namespace's own.
             :revision (rev revisions :namespace n)))))

(defn- q-multi-binding-without-context [model revisions]
  (let [attrs (:attributes model)]
    (apply concat
           (for [[[n tid] bound] (sort-by pr-str
                                          (group-by (fn [akw] [(attr-ns akw) (:term (attrs akw))])
                                                    (sort (for [[akw a] attrs :when (:term a)] akw))))
                 :when (and (some? tid)
                            (> (count bound) 1)
                            (not= (count (set (map #(:term-context (attrs %)) bound)))
                                  (count bound)))]
             (for [akw (sort bound)]
               (gap :attribute/multi-binding-without-context [:attribute akw]
                    (str "namespace " (pr-str n) " binds term " (pr-str tid)
                         " more than once; each binding needs a distinct :term-context (D2.27)")
                    {:action :update :type :attribute :id akw
                     :entry {:term-context fill-in}
                     :expected_revision (rev revisions :attribute akw)}))))))

(def ^:private impls
  {:attribute/missing-description           q-missing-description
   :attribute/missing-synonyms              q-missing-synonyms
   :namespace/no-identity                   q-no-identity
   :attribute/declaration-drift             q-declaration-drift
   :attribute/code-format-without-values    q-code-without-values
   :term/zero-binding                       q-term-zero-binding
   :attribute/multi-binding-without-context q-multi-binding-without-context
   :namespace/unreachable                   q-unreachable})

(defn run
  "模型 × 对象 revision 表 → 缺口报告。

   `query` 为 nil 时跑全部八条；`ns-prefix` 非 nil 时只留 target 落在该 namespace
   前缀下的缺口（**前缀**而不是精确相等：D3.11 的 scope 菜谱就是按前缀圈选的，
   两处用同一种圈法，读的人不必记两套）。

   `:metrics` 是**指标**不是缺口：它没有 `:action`，因为它不该被追到 0
   （见 `m-unlisted` 抬头）。指定了单条 `query` 时它照样在——一份只报数字的
   小节不会把人引去做任何事，而抽掉它会让「同一个模型两次调用给出不同的账」。"
  [model revisions {:keys [query ns-prefix]}]
  (let [qs   (if query [query] queries)
        gaps (vec (mapcat #((impls %) model revisions) qs))
        pfx? (fn [n] (or (str/blank? (str ns-prefix)) (str/starts-with? (str n) ns-prefix)))
        gaps (if (str/blank? (str ns-prefix))
               gaps
               (filterv (fn [{[t id] :target}]
                          (pfx? (case t
                                  :attribute (str (namespace id))
                                  :namespace (str id)
                                  (str id))))
                        gaps))
        ung  (m-unlisted model)
        ung  (if (str/blank? (str ns-prefix))
               ung
               (let [by (into (sorted-map) (filter (comp pfx? key)) (:by-entity ung))]
                 {:count (reduce + 0 (vals by)) :by-entity by}))]
    {:queries (vec qs)
     :counts  (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b))))
                    (for [q qs] [q (count (filter #(= q (:query %)) gaps))]))
     :count   (count gaps)
     :gaps    gaps
     :metrics {:attribute/unlisted ung}}))
