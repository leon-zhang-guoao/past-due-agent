(ns open.atlas.studio.pool.impl.model
  "对象类型表 + 方言 ↔ 池对象的双向拆装 + 引用索引。

   **裁定 ① 的落点**：池按概念建模，不整块存方言 EDN。本 ns 把 dialect-v1 §2/§3
   的键表翻译成一张类型表，每个概念一个可寻址的对象，字段名与条目键**一一对应**。

   Nine object types, one per dialect-v1 top-level key: `:attributes` `:links`
   `:terms` `:units` `:metrics` `:resolvers` `:mutations` `:sources`
   `:namespaces`.

   **Every top-level key is exactly one object type** (D2.29, owner ruling
   2026-09-02). It was not always so: a navigation used to live inside its
   namespace entry (`:namespaces {ns {:navigations {name …}}}`) and had to be
   split out into an object of its own, hanging off `:pool.child/parent`.
   D2.29 retired that registry — a branch of an `:output` tree is now an
   ordinary `:attributes` entry carrying `:target` — so the two-level shape,
   the child edge and the parent-exists checks that guarded it are all gone.
   (The pre-D2.25 groups were the other two-level shape; they went with the
   groups.)

   **字段的值形态只有两类**：
   - `:scalar` 家族（`:string` `:keyword` `:boolean`）落成原生 Datahike 值，
     §7.3 `query_model` 的具名查询（无描述的属性、`:format :code` 缺 `:values`、
     无 identity 的 namespace）就是对它们的 datalog 查询；
   - `:edn` 落成规范 EDN 字符串。**为什么不用 `:db.cardinality/many`**：
     `:synonyms` `:input` `:output` 这些向量**保持书写顺序**（dialect-v1 §4）而
     多值属性是无序集合，进 content-hash 就不稳（同则见 §3 carried 汇总要求
     `:synonyms` 字典序的那一行）。顺序丢了没有任何机制会发现。

   **一个类型的键面是开的**：source。dialect-v1 §3「其余连接键由该 type 的连接器
   契约声明（D2.15）」——池枚举不了它，也不该枚举：那等于池替连接器契约再声明
   一遍。核心三键落成可查字段，其余整块进 `:pool.source/connector-keys`。"
  (:require [clojure.edn :as edn]
            [open.atlas.common.dialect :as dialect]))

;; ---------------------------------------------------------------------------
;; 类型表
;; ---------------------------------------------------------------------------

(def types
  "对象类型 → {:id-kind :fields :update}。

   `:id-kind` 是身份的形状，与 dialect-v1 的寻址方式一致：
   `:keyword`（属性引用是全限定 kw，D2.17）、`:string`（注册表/容器的 map 键）、
   `:attr-pair`（link 的两个端点）。

   `:update` 是 §7.3 建模面那一行钉的两种改法：
   - `:replace` —— **整体替换**。resolver / mutation / source 是§7.3 点名的三个；
     metric 与 link 归此类的理由相同：它们是**行为/结构**而非描述——
     `:expression` 与一对端点是「这个东西是什么」，patch 掉半个等于留下一个
     语义不明的中间态。
   - `:merge` —— **merge patch**。§7.3 点名 entity / attribute 元数据；
     registry 两张（term/unit）同归此类，它们承载的
     同样是 label / description / synonyms 一类**可逐项修的描述**。
     patch 里显式写 `nil` = 删除该键（nil 不是任何方言键的合法值，故无歧义）；
     整体换语义要 delete + create。
   **这一行是裁定之外的判断**：§7.3 只点了五个类型的名，其余七个按「描述性 →
   merge，行为性 → replace」推，写在这里等 owner 裁。"
  {:attribute      {:id-kind :keyword :update :merge
                    :fields {:type          :keyword
                             :format        :keyword
                             :identity?     :boolean
                             :deprecated?   :boolean
                             ;; D2.29: one registry, two kinds of entry. A join
                             ;; attribute carries :target (the namespace the
                             ;; branch reaches) and :cardinality and no :type;
                             ;; a scalar one is the other way round. The pool
                             ;; stores both kinds side by side and leaves the
                             ;; mutual exclusion to the dialect reader — this ns
                             ;; makes no structural judgements (ruling ②).
                             :target        :string
                             :cardinality   :keyword
                             ;; D2.28：缺省 true，只有 false 写得出来（同 :curated?）。
                             :listed?       :boolean
                             :description   :string
                             :label         :string
                             :term          :string
                             :term-context  :string
                             :unit          :string
                             :replaced-by   :keyword
                             :synonyms      :edn
                             :values        :edn}}
   :term           {:id-kind :string :update :merge
                    :fields {:label        :string
                             :description  :string
                             :format       :keyword
                             :deprecated?  :boolean
                             :replaced-by  :string
                             :synonyms     :edn
                             :values       :edn}}
   :unit           {:id-kind :string :update :merge
                    :fields {:label       :string
                             :description :string}}
   :metric         {:id-kind :string :update :replace
                    :fields {:entity      :string
                             :grain       :keyword
                             :label       :string
                             :description :string
                             :expression  :edn
                             :dimensions  :edn
                             :synonyms    :edn}}
   :namespace      {:id-kind :string :update :merge
                    :fields {:kind        :keyword
                             :label       :string
                             :description :string
                             :synonyms    :edn
                             :notes       :edn}}
   :link           {:id-kind :attr-pair :update :replace
                    :fields {:from        :keyword
                             :to          :keyword
                             :direction   :keyword
                             :cardinality :keyword
                             :condition   :string
                             :description :string}}
   :resolver       {:id-kind :string :update :replace
                    :fields {:result_type  :string
                             :source-ref   :string
                             :label        :string
                             :description  :string
                             :curated?     :boolean
                             :input        :edn
                             :output       :edn
                             :source       :edn
                             :eql-params   :edn
                             :limit        :edn
                             :order        :edn
                             :cache        :edn
                             :row-security :edn}}
   :mutation       {:id-kind :string :update :replace
                    :fields {:result_type  :string
                             :source-ref   :string
                             :label        :string
                             :description  :string
                             :source       :edn
                             :params       :edn
                             :returns      :edn
                             :idempotency  :edn
                             :row-security :edn}}
   :source         {:id-kind :string :update :replace
                    :fields {:type        :string
                             :label       :string
                             :description :string}
                    ;; D2.15：连接键由连接器契约声明，池枚举不了（见 ns 注释）。
                    :open-keys :connector-keys}})

(def type-order
  "类型的固定顺序——导出与报告里凡是「按类型」的地方都用它，
   免得两处各排一遍还排得不一样。"
  [:attribute :link :metric :mutation
   :namespace :resolver :source :term :unit])

(defn known-type? [t] (contains? types t))

(defn valid-id?
  "id 的形状是否符合该类型的身份约定。"
  [t id]
  (case (:id-kind (types t))
    :keyword   (and (keyword? id) (some? (namespace id)))
    :string    (and (string? id) (seq id))
    :attr-pair (and (vector? id) (= 2 (count id)) (every? keyword? id)
                    (every? #(some? (namespace %)) id))
    false))

(defn object-key
  "对象在池里的唯一键。一个字符串同时编码类型与 id：
   Datahike 的 `:db.unique/identity` 要标量，而九个类型各设一个 unique 属性
   等于把「这是同一件事」拆成十份判断。"
  [t id]
  (binding [*print-namespace-maps* false] (pr-str [t id])))

;; ---------------------------------------------------------------------------
;; 方言 → 池对象
;; ---------------------------------------------------------------------------

(defn- entry->object [t id entry]
  {:pool/type t :pool/id id :pool/entry entry})

(defn dialect->objects
  "方言模型 → [对象…]（无 revision；revision 归池，不归方言）。"
  [model]
  (vec
   (concat
    (for [[k v] (:attributes model)] (entry->object :attribute k v))
    (for [[k v] (:terms model)]      (entry->object :term k v))
    (for [[k v] (:units model)]      (entry->object :unit k v))
    (for [[k v] (:metrics model)]    (entry->object :metric k v))
    (for [[k v] (:sources model)]    (entry->object :source k v))
    (for [[k v] (:namespaces model)] (entry->object :namespace k v))
    (for [l (:links model)]  (entry->object :link [(:from l) (:to l)] l))
    (for [r (:resolvers model)] (entry->object :resolver (:id r) (dissoc r :id)))
    (for [m (:mutations model)] (entry->object :mutation (:id m) (dissoc m :id))))))

;; ---------------------------------------------------------------------------
;; 池对象 → 方言
;; ---------------------------------------------------------------------------

(defn- by-type [objects] (group-by :pool/type objects))

(defn- as-map [objs] (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b))))
                           (map (juxt :pool/id :pool/entry) objs)))

(defn- id-order
  "向量型顶层键（`:links` `:resolvers` `:mutations`）的确定性顺序。

   dialect-v1 §4 说向量**保持书写顺序**——但池里没有「书写顺序」这回事：
   对象是逐个 action 写进来的，先后取决于谁先按下回车。于是池按身份定序，
   这是唯一与写入历史无关、因而**导出两次逐字节相同**的选择（裁定 ③）。"
  [objs]
  (sort-by #(pr-str (:pool/id %)) objs))

(defn objects->dialect
  "[对象…] → 方言模型。缺省不写：空的顶层键一律缺席
   （dialect-v1 §4「缺省值不书写」的同一条纪律）。"
  [objects]
  (let [g      (by-type objects)
        ns-map (as-map (:namespace g))]
    (cond-> {}
      (seq (:attribute g))  (assoc :attributes (as-map (:attribute g)))
      (seq (:term g))       (assoc :terms      (as-map (:term g)))
      (seq (:unit g))       (assoc :units      (as-map (:unit g)))
      (seq (:metric g))     (assoc :metrics    (as-map (:metric g)))
      (seq (:source g))     (assoc :sources    (as-map (:source g)))
      (seq ns-map)          (assoc :namespaces ns-map)
      (seq (:link g))       (assoc :links (mapv :pool/entry (id-order (:link g))))
      (seq (:resolver g))   (assoc :resolvers
                                   (mapv #(assoc (:pool/entry %) :id (:pool/id %))
                                         (id-order (:resolver g))))
      (seq (:mutation g))   (assoc :mutations
                                   (mapv #(assoc (:pool/entry %) :id (:pool/id %))
                                         (id-order (:mutation g)))))))

;; ---------------------------------------------------------------------------
;; 引用索引（delete 的「点名拒绝」）
;; ---------------------------------------------------------------------------

(defn attr-refs
  "一个对象引用到的**属性 kw**。位置逐个点名而不是深度遍历找 kw：
   深遍历会把 `:output` 语法树里的 join 键与描述文字里偶然相等的东西一起算进来，
   把一次合法的 delete 拒掉。宁可漏报一个位置（B0 的悬空引用检查兜底），
   不可凭巧合拒绝。"
  [{:pool/keys [type entry id]}]
  (case type
    :attribute      (concat (keep identity [(:replaced-by entry)])
                            (when (keyword? id) nil))
    :link           [(:from entry) (:to entry)]
    :metric         (:dimensions entry)
    :resolver       (concat (:input entry)
                            (filter keyword? (tree-seq coll? seq (:output entry)))
                            (keep :attribute (vals (:eql-params entry)))
                            (get-in entry [:order :allowed])
                            (when-let [d (get-in entry [:order :default])] [(first d)]))
    :mutation       (concat (keys (:params entry))
                            (filter keyword? (tree-seq coll? seq (:returns entry))))
    nil))

(defn string-refs
  "一个对象引用到的**其他对象的字符串 id**，带上被引类型。"
  [{:pool/keys [type entry id]}]
  (case type
    :attribute      (cond-> []
                      (:term entry) (conj [:term (:term entry)])
                      (:unit entry) (conj [:unit (:unit entry)])
                      true          (conj [:namespace (namespace id)])
                      ;; D2.29: a join attribute reaches a second namespace —
                      ;; the one its branch lands in. Asked of the public
                      ;; interface rather than re-derived here, so "what counts
                      ;; as a join" has one definition (CLAUDE.md).
                      (dialect/join-attribute? entry)
                      (conj [:namespace (:target entry)]))
    :term           (cond-> [] (:replaced-by entry) (conj [:term (:replaced-by entry)]))
    :metric         [[:namespace (:entity entry)]]
    :resolver       (cond-> [] (:source-ref entry) (conj [:source (:source-ref entry)]))
    :mutation       (cond-> [] (:source-ref entry) (conj [:source (:source-ref entry)]))
    nil))

(defn referrers
  "谁引用了 `[t id]`。返回 [[类型 id]…]，确定性顺序，供 finding 逐个点名。
   **只回 [类型 id]，不回内容**——finding-v1 §5 的不泄漏约束在这里就要成立。"
  [objects t id]
  (->> objects
       (remove #(and (= t (:pool/type %)) (= id (:pool/id %))))
       (filter (fn [o]
                 (if (= :attribute t)
                   (some #(= id %) (attr-refs o))
                   (some #(= [t id] %) (string-refs o)))))
       (map (juxt :pool/type :pool/id))
       (sort-by pr-str)
       vec))

;; ---------------------------------------------------------------------------
;; EDN 字段的编解码（值形态 :edn）
;; ---------------------------------------------------------------------------

(defn encode-edn [v]
  (binding [*print-namespace-maps* false] (pr-str v)))

(defn decode-edn [s] (edn/read-string s))

(defn field-kind [t k] (get-in types [t :fields k]))

(defn split-entry
  "条目 map → {:known {字段 值} :open {未列入类型表的键 值}}。
   `:open` 非空而该类型没有 `:open-keys` 时，是**方言长出了新键而池没跟上**——
   调用方一律当错误处理，绝不静默丢（CLAUDE.md：拒绝而非截断/猜测）。"
  [t entry]
  (let [fs (:fields (types t))]
    {:known (select-keys entry (keys fs))
     :open  (apply dissoc entry (keys fs))}))

(defn open-key-attr
  "该类型放「契约声明的其余键」的那一个字段名（只有 source 有，见 ns 注释）。"
  [t]
  (:open-keys (types t)))
