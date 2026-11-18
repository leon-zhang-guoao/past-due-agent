(ns open.atlas.common.dialect.impl.invariants
  "跨条目不变量层（实现顺序第 5 步）。malli 管形状，这里管关系：
   - D2.19 :result_type ⇔ :returns 不变量
   - D2.20 eql-param 锚定：:attribute 可解析、:allowed ⊆ :values、
           :attribute/:type 互斥且必居其一、保留调用名不得声明
   - D2.22 :replaced-by 三条体检（目标存在、:type 相同、无替换环）
   - D2.25 组：先注册后使用、成员同 namespace、identity 禁入、
     （**不检查「本地名与注册 ID 尾段是否一致」**：两者活在不同的命名空间——
      本地名与属性 local name 共用名字空间、跟着源头的列名走 snake_case，
      注册 ID 是平台层的概念名、按 dialect-v1 §2 用 kebab-case。跨命名空间
      比较字符串对任何多词组名都必然不等；而折叠分隔符之后它唯一还能报的，
      恰恰是 D2.25 明文允许的「起别名的自由」。一条只会对合法行为报警的检查
      没有存在的理由，2026-08-23 删除。）
           一属性至多一组、组/属性/navigation 两两同名拒绝、
           保留前缀 `g_`（组名必须带、属性 local name 与 navigation 名必须不带）
   - D2.16/D2.29 output 语法树：分支键是已登记的 **join** 属性、`:target` 与子叶
     属性的 namespace 一致、join 属性不得写在叶位置
   - D2.29 join `:target` 指向已登记 namespace（owner 2026-09-02 补回的门）
   - D2.26 词条：:term 已注册、携带字段互斥、不进线上 EQL
   - D2.27 :term-context 规则 + 类型兼容矩阵（:values 承载、
           :format×:type、码值键 EDN 类型严格一致）
   - 注册表（units/groups/terms）先注册后使用
   - carried 键逐项 :info 点名（:dialect.carried/key-present）
   每条规则一个私有函数，check 汇总——规则可单测、清单可对账 conformance 矩阵 §7。"
  (:require [clojure.string :as str]
            [open.atlas.common.dialect.impl.finding :as finding]
            [open.atlas.common.dialect.impl.vocab :as vocab]))

;; ---------------------------------------------------------------------------
;; 公共小工具
;; ---------------------------------------------------------------------------

(defn- attr-ns
  "属性 keyword → 所属 namespace 字符串（D2.17：属性引用是全限定 kw）。"
  [kw]
  (namespace kw))

(defn- attr-name [kw] (name kw))

(defn- term-of [terms attr-def]
  (when-let [t (:term attr-def)] (get terms t)))

(defn- effective-values
  "属性生效的码值表：本地 :values，或绑定词条携带的 :values（D2.26 绑定即继承）。"
  [terms attr-def]
  (or (:values attr-def) (:values (term-of terms attr-def))))

(defn- effective-format
  "属性生效的 :format：本地或词条携带（D2.26/D2.27）。"
  [terms attr-def]
  (or (:format attr-def) (:format (term-of terms attr-def))))

(defn- value-key-type
  "码值键的 EDN 类型 → 属性 :type 词表值（D2.27 严格一致，禁止隐式 coercion）。"
  [k]
  (cond
    (boolean? k) :boolean
    (integer? k) :integer
    (string? k)  :string
    :else        :other))

(defn- join?
  "条目是 join 属性（有 `:target`，或 Semantic 2.0 §6.1 的多目标 `:targets`）而不是
   标量属性（有 `:type`）——D2.29 的判别式。两者互斥由结构层保证，这里只读判别键。"
  [attr-def]
  (or (contains? attr-def :target) (contains? attr-def :targets)))

(defn- walk-output
  "output 语法树遍历（D2.16/D2.29：叶是标量属性，分支是 join 属性）。
   → {:leaves [{:path :attr}…] :branches [{:path :key :children}…]}"
  [tree base]
  (reduce
   (fn [acc [i item]]
     (let [p (conj base i)]
       (cond
         (keyword? item) (update acc :leaves conj {:path p :attr item})
         (map? item)
         (reduce-kv
          (fn [acc k v]
            (let [sub (walk-output (if (vector? v) v []) (conj p k))]
              (-> acc
                  (update :branches conj {:path p :key k :children v})
                  (update :leaves into (:leaves sub))
                  (update :branches into (:branches sub)))))
          acc item)
         :else acc)))
   {:leaves [] :branches []}
   (map-indexed vector (or tree []))))

(defn- resolver-path [i] [:resolvers i])
(defn- mutation-path [i] [:mutations i])

;; ---------------------------------------------------------------------------
;; D2.19 —— :result_type ⇔ :returns / :output
;; ---------------------------------------------------------------------------

(defn- check-result-type
  "D2.19：条目级 :result_type 必在场；mutation \"none\" ⇔ 无 :returns，
   \"single\"|\"list\" ⇒ 非空 :returns（dialect-v1 §3 mutation 条目 / §7）。"
  [data]
  (concat
   (for [[i r] (map-indexed vector (:resolvers data))
         :when (nil? (:result_type r))]
     (finding/make :dialect.invariant/result-type-returns-mismatch :error
                   (conj (resolver-path i) :result_type)
                   "resolver is missing the entry-level :result_type"
                   :expected vocab/result-types :refs ["D2.19" "dialect-v1 §7"]))
   (mapcat
    (fn [[i m]]
      (let [p   (mutation-path i)
            rt  (:result_type m)
            ret (:returns m)]
        (cond
          (nil? rt)
          [(finding/make :dialect.invariant/result-type-returns-mismatch :error
                         (conj p :result_type)
                         "mutation is missing the entry-level :result_type"
                         :expected vocab/mutation-result-types
                         :refs ["D2.19" "dialect-v1 §7"])]

          (and (= "none" rt) (some? ret))
          [(finding/make :dialect.invariant/result-type-returns-mismatch :error
                         (conj p :returns)
                         "mutation with :result_type \"none\" must not declare :returns"
                         :expected "absent" :got (finding/redact ret)
                         :refs ["D2.19" "D2.18" "dialect-v1 §7"])]

          (and (contains? vocab/result-types rt) (empty? ret))
          [(finding/make :dialect.invariant/result-type-returns-mismatch :error
                         (conj p :returns)
                         (str "mutation with :result_type " (pr-str rt)
                              " requires a non-empty :returns")
                         :expected "non-empty output tree"
                         :refs ["D2.19" "D2.18" "dialect-v1 §7"])]
          :else [])))
    (map-indexed vector (:mutations data)))))

;; ---------------------------------------------------------------------------
;; 先注册后使用 —— 属性引用、:unit、metric、mutation :params（D2.2/D2.7/D2.18）
;; ---------------------------------------------------------------------------

(defn- attr-refs
  "全模型的属性引用点位：[path 属性kw]（D2.17 列出的全部引用处）。"
  [data]
  (concat
   (for [[i r] (map-indexed vector (:resolvers data))
         [j a] (map-indexed vector (:input r))]
     [(into (resolver-path i) [:input j]) a])
   (for [[i r] (map-indexed vector (:resolvers data))
         leaf  (:leaves (walk-output (:output r) (conj (resolver-path i) :output)))]
     [(:path leaf) (:attr leaf)])
   ;; D2.29：**分支键也是属性引用**。在两个注册表的年代它不是——它在
   ;; `:navigations` 里另有出处，故必须从这里排除，否则每一条 navigation 都
   ;; 会被报成「未登记属性」。合并之后排除就成了漏检：分支键与叶属性走同一张
   ;; 注册表，「先注册后使用」对二者是同一条规则。
   (for [[i r] (map-indexed vector (:resolvers data))
         br    (:branches (walk-output (:output r) (conj (resolver-path i) :output)))]
     [(:path br) (:key br)])
   (for [[i m] (map-indexed vector (:mutations data))
         br    (:branches (walk-output (:returns m) (conj (mutation-path i) :returns)))]
     [(:path br) (:key br)])
   (for [[i r] (map-indexed vector (:resolvers data))
         [j a] (map-indexed vector (get-in r [:order :allowed]))]
     [(into (resolver-path i) [:order :allowed j]) a])
   (for [[i r] (map-indexed vector (:resolvers data))
         :let  [a (first (get-in r [:order :default]))]
         :when (keyword? a)]
     [(into (resolver-path i) [:order :default 0]) a])
   ;; An eql-param's `:attribute` is an attribute reference like any other, and
   ;; it was missing here. Every downstream eql-param check (D2.20 resolvability,
   ;; `:allowed ⊆ :values`, the `:op` × type matrix) reads the attribute's entry
   ;; and does nothing when there is none — so a typo'd `:attribute` used to
   ;; produce **zero** findings anywhere in the model. "Register before use"
   ;; (D2.2/D2.17) is the check that has to catch it.
   (for [[i r]      (map-indexed vector (:resolvers data))
         [pname sp] (:eql-params r)
         :when      (keyword? (:attribute sp))]
     [(into (resolver-path i) [:eql-params pname :attribute]) (:attribute sp)])
   (for [[i m] (map-indexed vector (:mutations data))
         [a _] (:params m)]
     [(into (mutation-path i) [:params a]) a])
   (for [[i m] (map-indexed vector (:mutations data))
         leaf  (:leaves (walk-output (:returns m) (conj (mutation-path i) :returns)))]
     [(:path leaf) (:attr leaf)])
   (for [[i l] (map-indexed vector (:links data))
         k     [:from :to]
         :when (keyword? (get l k))]
     [[:links i k] (get l k)])
   (for [[mid m] (:metrics data)
         [j a]   (map-indexed vector (:dimensions m))]
     [[:metrics mid :dimensions j] a])
   (for [[akw a] (:attributes data)
         :when   (keyword? (:replaced-by a))]
     [[:attributes akw :replaced-by] (:replaced-by a)])))

(defn- check-attribute-registration
  "D2.2/D2.18：所有属性引用必须已在 :attributes 登记。"
  [data]
  (let [attrs (:attributes data)]
    (for [[p a] (attr-refs data)
          :when (not (contains? attrs a))]
      (finding/make :dialect.invariant/unregistered-reference :error p
                    (str "attribute " a " is not declared in :attributes")
                    :got a :refs ["D2.2" "D2.17" "dialect-v1 §2"]))))

(defn- check-unit-registration
  "D2.7：:unit 必须在方言根 :units 先注册后使用。"
  [data]
  (let [units (:units data)]
    (for [[akw a] (:attributes data)
          :let    [u (:unit a)]
          :when   (and (some? u) (not (contains? units u)))]
      (finding/make :dialect.invariant/unregistered-reference :error
                    [:attributes akw :unit]
                    (str "unit " (pr-str u) " is not registered in :units")
                    :got u :refs ["D2.7" "dialect-v1 §2"]))))

(defn- check-metric-entity
  "D2.21：metric :entity 与 :dimensions 指向已登记的 namespace / 属性。"
  [data]
  (let [nss (:namespaces data)]
    (for [[mid m] (:metrics data)
          :let    [e (:entity m)]
          :when   (and (some? e) (not (contains? nss e)))]
      (finding/make :dialect.invariant/unregistered-reference :error
                    [:metrics mid :entity]
                    (str "metric entity " (pr-str e) " is not a declared namespace")
                    :got e :refs ["D2.21" "dialect-v1 §3"]))))

(defn- check-join-target
  "D2.29: a join attribute's `:target` must name a registered namespace.

   Before the object-type merge, the pool's parent-namespace check caught an
   unregistered target; that check vanished with the merge, leaving the gate
   only on joins actually used as an `:output` branch (`check-output-branches`).
   A join that nobody navigates yet would then ship pointing at nothing.
   Owner restored it as a dialect invariant (2026-09-02)."
  [data]
  (let [nss (:namespaces data)]
    (for [[akw a] (:attributes data)
          :let    [t (:target a)]
          :when   (and (join? a) (some? t) (not (contains? nss t)))]
      (finding/make :dialect.invariant/join-target-unregistered :error
                    [:attributes akw :target]
                    (str "join target " (pr-str t) " is not a declared namespace")
                    :got t :refs ["D2.29" "dialect-v1 §7"]))))

;; ---------------------------------------------------------------------------
;; D2.16 / D2.29 —— output 语法树：分支是已登记 join 属性，:target 与子属性一致
;; ---------------------------------------------------------------------------

(defn- check-output-branches
  "D2.16 的两条检查，D2.29 之后改瞄准 join 属性（语义不变，出处变了）：
   ① 分支键必须是已登记的 **join** 属性——登记本身由
      `check-attribute-registration` 管（分支键现在也是属性引用），这里管
      「登记的那一条确实是 join 而不是标量」；
   ② join 属性的 `:target` 必须等于其子叶属性的 namespace。

   语法位置与条目形状必须一致，这是判别式的另一半：写成叶的 join 属性没有值可
   给（`:type` 不存在），写成分支的标量属性没有 `:target` 可跟。两种错都在这里
   点名，而不是留给消费端在运行时发现。"
  [data]
  (mapcat
   (fn [{:keys [path key children]}]
     (let [attr-def (get (:attributes data) key)]
       (cond
         ;; 未登记 → check-attribute-registration 已在同一 path 点过名，
         ;; 这里不重复报（finding 去重靠的是不重复产生，不是事后去重）。
         (nil? attr-def) []

         (not (join? attr-def))
         [(finding/make :dialect.invariant/join-attribute-invalid :error path
                        (str "attribute " key " is used as an :output branch but is a scalar"
                             " attribute; only a join attribute (one with :target) can carry"
                             " a sub-tree")
                        :expected ":target" :got (:type attr-def)
                        :refs ["D2.16" "D2.29" "dialect-v1 §7"])]

         :else
         (let [target (:target attr-def)
               ;; **直接**叶，不含嵌套分支下面的叶：每个分支各自被这条检查瞄准
               ;; 一次（`walk-output` 把嵌套分支单独收进 `:branches`），拿孙子叶
               ;; 去比这一层的 `:target` 会把合法的嵌套判成不匹配——106.14 第 5
               ;; 条的 `{B/as [A/… {A/b [B/k]}]}` 正是这一形。
               subs   (keep-indexed (fn [i item]
                                      (when (keyword? item)
                                        {:path (conj path i) :attr item}))
                                    (if (vector? children) children []))
               bad    (remove #(= target (attr-ns (:attr %))) subs)]
           (for [b bad]
             (finding/make :dialect.invariant/join-target-mismatch :error (:path b)
                           (str "join attribute " key " declares :target " (pr-str target)
                                " but the child attribute lives in "
                                (pr-str (attr-ns (:attr b))))
                           :expected target :got (attr-ns (:attr b))
                           :refs ["D2.16" "D2.29" "dialect-v1 §7"]))))))
   (concat
    (mapcat (fn [[i r]] (:branches (walk-output (:output r) (conj (resolver-path i) :output))))
            (map-indexed vector (:resolvers data)))
    (mapcat (fn [[i m]] (:branches (walk-output (:returns m) (conj (mutation-path i) :returns))))
            (map-indexed vector (:mutations data))))))

;; ---------------------------------------------------------------------------
;; D2.20 —— eql-param 锚定
;; ---------------------------------------------------------------------------

(defn- resolver-attr-closure
  "resolver 可解析到源侧的属性集：:field_mapping 的键（显式映射）
   ∪ :input ∪ output 叶（恒等缺省，D2.1/sql-v1「含恒等缺省」）。"
  [r]
  (into (set (keys (get-in r [:source :field_mapping])))
        (concat (:input r)
                (map :attr (:leaves (walk-output (:output r) []))))))

(defn- check-eql-params
  "D2.20：保留调用名不得声明；:attribute 与 :type 互斥且必居其一；
   :attribute 必须能经映射（含恒等缺省）解析；:allowed ⊆ 该属性 :values 键集（D2.8 第 4 条）。"
  [data]
  (mapcat
   (fn [[i r]]
     (let [closure (resolver-attr-closure r)]
       (mapcat
        (fn [[pname spec]]
          (let [p    (into (resolver-path i) [:eql-params pname])
                a    (:attribute spec)
                t    (:type spec)
                adef (get (:attributes data) a)]
            (concat
             (when (contains? vocab/reserved-call-names pname)
               [(finding/make :dialect.invariant/reserved-param-name :error p
                              (str "reserved call name " pname
                                   " must not be declared as an eql-param")
                              :expected (str "a name outside " (pr-str vocab/reserved-call-names))
                              :got pname :refs ["D2.20" "dialect-v1 §7"])])
             (cond
               (and (some? a) (some? t))
               [(finding/make :dialect.invariant/eql-param-anchor-invalid :error p
                              "eql-param declares both :attribute and :type; :type is inherited from the attribute and must be omitted"
                              :expected ":attribute only" :refs ["D2.20" "dialect-v1 §7"])]
               (and (nil? a) (nil? t))
               [(finding/make :dialect.invariant/eql-param-anchor-invalid :error p
                              "eql-param declares neither :attribute nor :type; the parameter type has no source of truth"
                              :expected "exactly one of :attribute / :type"
                              :refs ["D2.20" "dialect-v1 §7"])]
               :else [])
             (when (and (some? a) (some? adef) (not (contains? closure a)))
               [(finding/make :dialect.invariant/eql-param-unresolvable :error
                              (conj p :attribute)
                              (str "attribute " a " cannot be resolved through the resolver's mapping"
                                   " (:field_mapping, :input or :output)")
                              :got a :refs ["D2.20" "sql-v1" "dialect-v1 §7"])])
             (when-let [allowed (:allowed spec)]
               (if (some? a)
                 (let [vals (effective-values (:terms data) adef)
                       ks   (set (keys vals))
                       bad  (remove ks allowed)]
                   (when (seq bad)
                     [(finding/make :dialect.invariant/eql-param-unresolvable :error
                                    (conj p :allowed)
                                    ":allowed must be a subset of the attribute's :values key set"
                                    :expected ks :got (vec bad)
                                    :refs ["D2.20" "D2.8" "dialect-v1 §7"])]))
                 ;; A `:type`-anchored parameter (Semantic contract 2.0 §3.1
                 ;; `:params`, lowered without an attribute) has no `:values`
                 ;; to be a subset of; the only set it can be checked against
                 ;; is its own type, under D2.27's strict EDN-type reading. A
                 ;; date or datetime is an ISO-8601 string, the one spelling
                 ;; EDN has for it. Before this branch every typed `:allowed`
                 ;; was refused as «not in an empty value set».
                 (let [ok? (fn [v] (or (= t (value-key-type v))
                                       (and (#{:date :datetime} t) (string? v))))
                       bad (remove ok? allowed)]
                   (when (seq bad)
                     [(finding/make :dialect.invariant/eql-param-unresolvable :error
                                    (conj p :allowed)
                                    ":allowed elements of a :type-anchored parameter must be of that type"
                                    :expected t :got (vec bad)
                                    :refs ["D2.20" "D2.27" "dialect-v1 §7"])]))))
             ;; :op × 类型（dialect-v1 §3 类型兼容矩阵）
             (let [typ (or t (:type adef))
                   op  (:op spec)]
               (concat
                (when (= :json typ)
                  [(finding/make :dialect.invariant/type-compat-violation :error p
                                 ":json attributes must not be used as filter parameters"
                                 :got typ :refs ["D2.27" "dialect-v1 §3"])])
                (when (and (= :like op) (not= :string typ))
                  [(finding/make :dialect.invariant/type-compat-violation :error
                                 (conj p :op)
                                 ":op :like is only legal on :string"
                                 :expected :string :got typ
                                 :refs ["D2.27" "dialect-v1 §3"])])
                (when (and (contains? #{:gt :gte :lt :lte} op) (= :boolean typ))
                  [(finding/make :dialect.invariant/type-compat-violation :error
                                 (conj p :op)
                                 (str ":op " op " is not legal on :boolean")
                                 :got typ :refs ["D2.27" "dialect-v1 §3"])]))))))
        (:eql-params r))))
   (map-indexed vector (:resolvers data))))

;; ---------------------------------------------------------------------------
;; D2.22 —— :replaced-by 三条体检（属性），词条同则（D2.26）
;; ---------------------------------------------------------------------------

(defn- cycle-from?
  "从 start 沿 next-fn 走是否回到 start（替换环，D2.22/D2.26）。"
  [start next-fn]
  (loop [cur (next-fn start) seen #{start}]
    (cond
      (nil? cur)          false
      (contains? seen cur) true
      :else               (recur (next-fn cur) (conj seen cur)))))

(defn- check-replaced-by
  "D2.22：目标存在、:type 相同、无替换环；:terms 的 replaced-by 环同查（D2.26）。"
  [data]
  (let [attrs (:attributes data)
        terms (:terms data)]
    (concat
     (mapcat
      (fn [[akw a]]
        (let [t (:replaced-by a)
              p [:attributes akw :replaced-by]]
          (cond
            (nil? t) []
            (not (contains? attrs t))
            [(finding/make :dialect.invariant/replaced-by-invalid :error p
                           (str ":replaced-by target " t " is not declared in :attributes")
                           :got t :refs ["D2.22" "dialect-v1 §7"])]
            (not= (:type a) (:type (get attrs t)))
            [(finding/make :dialect.invariant/replaced-by-invalid :error p
                           (str ":replaced-by target " t " has a different :type")
                           :expected (:type a) :got (:type (get attrs t))
                           :refs ["D2.22" "dialect-v1 §7"])]
            (cycle-from? akw #(:replaced-by (get attrs %)))
            [(finding/make :dialect.invariant/replacement-cycle :error p
                           (str "attribute " akw " takes part in a :replaced-by cycle")
                           :got t :refs ["D2.22" "dialect-v1 §7"])]
            :else [])))
      attrs)
     (mapcat
      (fn [[tid t]]
        (let [tgt (:replaced-by t)
              p   [:terms tid :replaced-by]]
          (cond
            (nil? tgt) []
            (not (contains? terms tgt))
            [(finding/make :dialect.invariant/replaced-by-invalid :error p
                           (str ":replaced-by target " (pr-str tgt) " is not registered in :terms")
                           :got tgt :refs ["D2.26" "D2.22" "dialect-v1 §2"])]
            (cycle-from? tid #(:replaced-by (get terms %)))
            [(finding/make :dialect.invariant/replacement-cycle :error p
                           (str "term " (pr-str tid) " takes part in a :replaced-by cycle")
                           :got tgt :refs ["D2.26" "D2.22" "dialect-v1 §2"])]
            :else [])))
      terms))))

;; ---------------------------------------------------------------------------
;; D2.29 —— 判别式的另一半：join 属性不得写在叶位置
;; ---------------------------------------------------------------------------

(defn- check-output-leaves
  "D2.29：叶位置上的键必须是标量属性。join 属性写成叶等于向调用方许诺一个它没有
   的值——`:type` 根本不存在，连接器映射不出列，消费端拿到的会是一个静默缺席的键。

   （**D2.16 的「同 namespace 内 navigation 与属性 local name 撞名即拒绝」连同
   它的 collection 豁免一起消失了**：撞名规则的前提是两张注册表各有一个名字空间,
   合并成一张之后，同一个名字在同一个 namespace 里只能是同一个条目——没有第二
   个东西可撞。规则不是被放宽，是失去了指称对象。）"
  [data]
  (mapcat
   (fn [{:keys [path attr]}]
     (let [attr-def (get (:attributes data) attr)]
       (when (and (some? attr-def) (join? attr-def))
         [(finding/make :dialect.invariant/join-attribute-invalid :error path
                        (str "join attribute " attr " is used as an :output leaf; a join carries"
                             " rows of " (pr-str (:target attr-def))
                             " and must be written as a branch with a sub-tree")
                        :expected (str "{" attr " […]}")
                        :refs ["D2.16" "D2.29" "dialect-v1 §7"])])))
   (concat
    (mapcat (fn [[i r]] (:leaves (walk-output (:output r) (conj (resolver-path i) :output))))
            (map-indexed vector (:resolvers data)))
    (mapcat (fn [[i m]] (:leaves (walk-output (:returns m) (conj (mutation-path i) :returns))))
            (map-indexed vector (:mutations data))))))

;; ---------------------------------------------------------------------------
;; D2.26 / D2.27 —— 词条绑定与本地语境
;; ---------------------------------------------------------------------------

(def ^:private term-carried-keys
  "词条携带字段：绑定后本地再写任何一个都是拒绝（D2.26）。"
  [:description :format :label :synonyms :values])

(defn- check-terms
  "D2.26：:term 指向已注册词条；有 :term 时禁写词条携带字段。"
  [data]
  (let [terms (:terms data)]
    (mapcat
     (fn [[akw a]]
       (let [t (:term a)]
         (cond
           (nil? t) []
           (not (contains? terms t))
           [(finding/make :dialect.invariant/unregistered-reference :error
                          [:attributes akw :term]
                          (str "term " (pr-str t) " is not registered in :terms")
                          :got t :refs ["D2.26" "dialect-v1 §2"])]
           :else
           (for [k term-carried-keys
                 :when (contains? a k)]
             (finding/make :dialect.invariant/term-violation :error [:attributes akw k]
                           (str "attribute binds a term and must not write the term-carried key " k)
                           :expected "absent" :refs ["D2.26" "dialect-v1 §3"])))))
     (:attributes data))))

(defn- check-term-context
  "D2.27：:term-context 仅在有 :term 时合法；同 namespace 对同一词条多绑定
   必须带互不相同的 :term-context。"
  [data]
  (let [attrs (:attributes data)]
    (concat
     (for [[akw a] attrs
           :when   (and (contains? a :term-context) (nil? (:term a)))]
       (finding/make :dialect.invariant/term-violation :error [:attributes akw :term-context]
                     ":term-context is only legal on an attribute that binds a :term"
                     :expected ":term present" :refs ["D2.27" "dialect-v1 §7"]))
     ;; 同 namespace 同词条的多绑定
     (mapcat
      (fn [[[ns-str tid] bound]]
        (let [ctxs (map #(:term-context (get attrs %)) bound)]
          (when (and (> (count bound) 1)
                     (not= (count (set ctxs)) (count bound)))
            (for [akw (sort bound)]
              (finding/make :dialect.invariant/term-violation :error
                            [:attributes akw :term-context]
                            (str "namespace " (pr-str ns-str) " binds term " (pr-str tid)
                                 " more than once; each binding needs a distinct :term-context")
                            :got (:term-context (get attrs akw))
                            :refs ["D2.27" "dialect-v1 §7"])))))
      (group-by (fn [akw] [(attr-ns akw) (:term (get attrs akw))])
                (sort (for [[akw a] attrs :when (:term a)] akw)))))))

(defn- check-type-compat
  "D2.27 兼容矩阵：:values 承载类型限制、码值键 EDN 类型严格一致、:format×:type。"
  [data]
  (let [terms (:terms data)]
    (mapcat
     (fn [[akw a]]
       (let [typ  (:type a)
             vals (effective-values terms a)
             fmt  (effective-format terms a)
             ;; 码值/格式来自本地还是词条，决定点名位置
             vpath (if (:values a) [:attributes akw :values] [:attributes akw :term])
             fpath (if (:format a) [:attributes akw :format] [:attributes akw :term])]
         (concat
          (when (and (seq vals) (not (contains? vocab/values-capable-types typ)))
            [(finding/make :dialect.invariant/type-compat-violation :error vpath
                           (str ":values cannot be carried by an attribute of :type " typ)
                           :expected vocab/values-capable-types :got typ
                           :refs ["D2.27" "dialect-v1 §3"])])
          (when (and (seq vals) (contains? vocab/values-capable-types typ))
            (for [k (sort-by pr-str (keys vals))
                  :when (not= typ (value-key-type k))]
              (finding/make :dialect.invariant/type-compat-violation :error vpath
                            (str "value key " (pr-str k) " does not match the attribute :type " typ)
                            :expected typ :got (value-key-type k)
                            :refs ["D2.27" "dialect-v1 §3"])))
          (when (and (some? fmt) (some? typ)
                     (not (contains? (get vocab/format->types fmt #{}) typ)))
            [(finding/make :dialect.invariant/type-compat-violation :error fpath
                           (str ":format " fmt " is not legal on :type " typ)
                           :expected (get vocab/format->types fmt #{}) :got typ
                           :refs ["D2.27" "dialect-v1 §3"])]))))
     (:attributes data))))

;; ---------------------------------------------------------------------------
;; carried 键逐项点名（D2.9 / dialect-v1 §3「carried 键汇总」）
;; ---------------------------------------------------------------------------

(defn- carried-finding [path key*]
  (finding/make :dialect.carried/key-present :info path
                (str "carried key " key* " is legal and round-trips but has no execution semantics yet")
                :refs ["D2.9" "dialect-v1 §3"]))

(defn- check-carried
  "carried 键逐项 :info 点名：resolver/mutation 的 :row-security、resolver 的 :cache、
   metric :expression、无执行者阶段的 :mutations 整块。
   （`:security` 已退役，D5.12——attribute 因此不再有任何 carried 键。）"
  [data]
  (concat
   (for [[i r] (map-indexed vector (:resolvers data))
         k     [:cache :row-security]
         :when (contains? r k)]
     (carried-finding (conj (resolver-path i) k) k))
   (for [[i m] (map-indexed vector (:mutations data))
         k     [:row-security]
         :when (contains? m k)]
     (carried-finding (conj (mutation-path i) k) k))
   (for [[mid m] (:metrics data) :when (contains? m :expression)]
     (carried-finding [:metrics mid :expression] :expression))
   (when (contains? data :mutations)
     [(carried-finding [:mutations] :mutations)])))

;; ---------------------------------------------------------------------------
;; 汇总
;; ---------------------------------------------------------------------------

(defn- in-record-collection?
  "这条 list resolver 的行是不是**就在宿主记录里**（doc 106 §106.14 第 15 条，
   owner 2026-09-07）：`:output` 的 join 根在 api 操作块的 `:field_mapping` 里
   由一个带 `:_from` 的分支产出。

   判据只看这一条：**分支自带数组来源**。这类集合的基数由那一条记录定死，
   一次请求取回，不存在「再取一页」——`:limit` 对它无处可施，写上一个也约束不了
   任何东西（api-v1「记录内集合」）。"
  [r]
  (let [jr (when-let [o (:output r)]
             (when (and (vector? o) (= 1 (count o)) (map? (first o))
                        (= 1 (count (first o))))
               (key (ffirst o))))]
    (boolean (when jr
               (let [v (get-in r [:source :field_mapping jr])]
                 (and (map? v) (contains? v :_from)))))))

(defn- check-list-limit
  "D2.8：`\"list\"` resolver 必须声明 `:limit {:default :max}`
   （dialect-v1 §3 resolver 条目「list 必」）。

   **记录内集合豁免**（§106.14 第 15 条）：行就在宿主记录里，基数由那条记录约束，
   分页无从谈起——见 `in-record-collection?`。"
  [data]
  (for [[i r] (map-indexed vector (:resolvers data))
        :when (and (= "list" (:result_type r)) (nil? (:limit r))
                   (not (in-record-collection? r)))]
    (finding/make :dialect.invariant/list-limit-missing :error
                  (conj (resolver-path i) :limit)
                  "list resolver must declare :limit {:default :max}"
                  :expected {:default 'pos-int :max 'pos-int}
                  :refs ["D2.8" "dialect-v1 §3"])))

(def ^:private rules
  [#'check-result-type
   #'check-list-limit
   #'check-attribute-registration
   #'check-unit-registration
   #'check-metric-entity
   #'check-join-target
   #'check-output-branches
   #'check-output-leaves
   #'check-eql-params
   #'check-replaced-by
   #'check-terms
   #'check-term-context
   #'check-type-compat
   #'check-carried])

(defn check
  "data（已过结构层）→ [finding…]。顺序确定（finding-v1 §6）。"
  [data]
  (->> rules
       (mapcat (fn [r] (r data)))
       (remove nil?)
       finding/sort-findings
       vec))
