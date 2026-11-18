(ns open.atlas.common.engine.impl.gate
  "D5.4 的那一道门：数据离开平台的唯一出口。

   **判定顺序不可换**（D5.4）：

     ① 按 token 档位裁出 catalog 与 index（`:restricted` 不够档的在此消失，D5.3）
            ↓   —— 在 impl.compile，编译期一次性物化，本 ns 只是取用第 ① 步的产物
     ② 用**裁剪后的** catalog 校验查询里点名的每个属性
            ↓   —— `check-query`
     ③ 规划与执行，wildcard 展开按档位过滤
                —— 执行归 impl.engine，wildcard 过滤是 `scrub`

   三种处理由 security/decide 得到，engine 只负责**应用**——判定不许在这里有第二份
   实现（security ns 的 docstring 点名了这一条）。"
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [open.atlas.common.engine.impl.model :as m]))

;; --- 拒绝的形状 -------------------------------------------------------------
;;
;; 沿用 finding-v1 的形状精神：结构化、点名、不泄漏值（S5.3「永不记录值」）。
;; engine 自己的 code 用 `:engine.*` 域，与方言的 finding code 不共用注册表。

(defn reject [code path message & {:as extra}]
  (merge {:engine/status :rejected
          :code code :severity :error :path path :message message}
         extra))

(defn unknown-attribute
  "D5.4 第 3 种：与点名一个**真不存在**的属性完全相同。

   这个 map 里**没有任何一个键来自档位**——没有 `:required-tier`，没有
   `:token-tier`，措辞里也没有「禁止」。这是刻意的：若显式点名会回一个
   「它存在，你不够格」，攻击者逐个键名试探就能把整张图的高敏字段列出来，
   差分泄漏只是从值层面挪到元数据层面（D5.4）。构造函数只有这一个，
   两条来路（档位不够的 `:restricted` / 键名根本不存在）走同一行代码，
   「逐字相同」因此不是测试断言出来的，是结构上没有第二种可能。"
  [attr]
  (reject :engine.catalog/unknown-attribute [:query]
          "unknown attribute"
          :attribute attr
          :refs ["D5.4"]))

(defn- join-required [attr target]
  (reject :engine.query/join-required [:query]
          "this key is a join root and must be asked for with a sub-query"
          :attribute attr
          :target target
          :expected (str "{" attr " [\u2026]}")
          :refs ["D2.16"]))

(defn limit-exceeded [{:keys [limit max actual]}]
  (reject :engine.limit/exceeded [:request]
          "EQL boundary exceeded; the request is rejected, not truncated"
          :limit limit :max max :actual actual
          :refs ["principles: 执行与连接" "D1.6"]))

;; --- 查询的行走 --------------------------------------------------------------

(defn- params-of
  "EQL 里 `(:key {:p v})` 形式的参数。"
  [node]
  (when (and (seq? node) (= 2 (count node))) (second node)))

(defn- key-of
  "EQL 节点 → 它点名的那个 key。四种写法：`:k`、`{:k [...]}`、`(:k {params})`、
   `({:k [...]} {params})`——后两种是带参形，key 藏在里面一层。"
  [node]
  (cond
    (seq? node)     (key-of (first node))
    (map? node)     (when (seq node) (key (first node)))
    (keyword? node) node
    :else           node))

(defn expand-query
  "查询整形：把 **`'*` 查询宏**在规划前展开成属性清单。它不是点名，因此展开
   出来的成员一律走 D5.4 的 **wildcard 分支**（够档就返回、不够档就过滤，
   不整体拒绝）。（D2.25 的组曾是第二种查询宏，随组移除而删；组的收纳需求
   由 derived 属性接手，derived 是普通具名属性，不展开。）

   **`'*`**：展开成「当前实体在该档位 index 下可达的属性集」。

   为什么必须展开而不能原样交给 pathom：Pathom3 的 `*` 语义是「把实体里**已有**
   的数据给我」，不会去解析未点名的属性。平台的 wildcard 按 D5.4 原文是
   「把我能看的都给我」——D5.4 论证第 2 种处理时说「对它拒绝等于任何非最高档
   token 都用不了 wildcard，实际效果是所有人改用点名枚举」，这句话预设了
   wildcard 真的会返回一个宽集合；若 `*` 什么都不返回，那段论证与第 2 种处理
   （过滤 + `:filtered-attributes`）在实践中一起落空。**档位退役后
   （D5.11）那段论证连同 `:filtered-attributes` 一并没了**，但展开本身留着:
   理由变成了「平台的 wildcard 是『这张图里这个实体能到的都给我』」,
   而 Pathom3 的 `*` 给不出这个。

   当前实体按 **entity 由 namespace 推导**（principles）定：根部取
   `:entity` 各键的 namespace，ident join 取 ident 属性的 namespace，
   嵌套 join 取该 join 属性的 `:target`（D2.16 的硬门就是为此，D2.29 之后它长在属性上）。
   `:restricted` 不够档的属性在编译期就已不在该档 catalog 里，因此天然展不进来
   ——「对这个 token 而言它整个不存在」（D5.3），此处不加第二层特判。

   `'*` 本身**保留**在展开后的查询里：它让 ident 自身的键照常回显，且此时实体
   里绝不可能有不该出现的东西——每条 resolver 的行在离开时已按该档位裁剪过的
   output 过滤（impl.compile/scrub-row）。

   返回 `[展开后的查询 被展开出来的属性集]`。**调用方点名过的属性从该集合里减掉**
   ——`[:a/b '*]` 是并集语义，`:a/b` 仍是点名（不够档要拒绝），只有纯展开出来的
   才走 wildcard 分支。"
  [query {:keys [attrs-by-entity joins entity]}]
  (let [expanded (atom #{})
        named    (atom #{})]
    (letfn [(ident? [k] (and (vector? k) (= 2 (count k)) (keyword? (first k))))
            (entity-ns
              ;; join 的 key → 子查询所处的 entity（namespace 字符串集合）
              [k]
              (cond
                (ident? k)   #{(namespace (first k))}
                (keyword? k) #{(or (get joins k) (namespace k))}
                :else        #{}))
            (level [q nss]
              (if-not (vector? q)
                q
                (let [here (into #{} (keep #(let [k (key-of %)]
                                              (when (keyword? k) k)))
                                 q)]
                  (swap! named into here)
                  (vec
                   (mapcat
                    (fn [node]
                      (if (= '* node)
                        (let [reach (into #{} (mapcat #(get attrs-by-entity % #{})) nss)
                              adds  (vec (sort (remove here reach)))]
                          (swap! expanded into adds)
                          (cons '* adds))
                        [(node* node)]))
                    q)))))
            (node* [node]
              (cond
                (map? node) (into {} (map (fn [[k v]] [k (level v (entity-ns k))])) node)
                (seq? node) (let [inner (first node)]
                              (if (map? inner)
                                (cons (node* inner) (rest node))
                                node))
                :else       node))]
      (let [root-nss (into #{} (keep #(when (keyword? %) (namespace %))) (keys entity))
            q        (level query root-nss)]
        ;; 点名过的减掉：并集语义下点名优先，点名的那些仍走 :named 分支。
        [q (set/difference @expanded @named)]))))

(defn- collect
  "查询树 → `[{:attr a :mention :named|:wildcard} …]` 与 `[{:key k :params p} …]`。
   `:wildcard` = 由 `'*` 展开出来的（过滤不拒绝，D5.4 第 2 种处理）；
   ident `[:attr v]` 与 `{:entity …}` 的键属于 caller-supplied，单独收。"
  [query star-expanded]
  (let [mentions (transient []) params (transient []) joined (transient #{})]
    (letfn [(mention [k]
              (conj! mentions {:attr k
                               :mention (if (contains? star-expanded k) :wildcard :named)}))
            (walk [q]
              (cond
                (vector? q)
                (doseq [node q] (walk-node node))
                :else nil))
            (walk-node [node]
              (cond
                (= '* node) nil
                (keyword? node) (mention node)
                ;; A symbol-headed node is a MUTATION call, not a
                ;; parameterised read. Its arguments are declared on the
                ;; mutation's own `:params` (D2.18) and are checked by
                ;; `check-mutation` below — running them past the
                ;; `:eql-params` rule would reject every one of them as an
                ;; undeclared narrowing parameter.
                (and (seq? node) (symbol? (first node))) nil

                (seq? node) (do (when-let [p (params-of node)]
                                  (conj! params {:key (key-of node) :params p}))
                                (walk-node (first node)))

                (map? node) (doseq [[k v] node]
                              (cond
                                (vector? k) nil                 ; ident join，caller-supplied 另行处理
                                (seq? k)    (do (when-let [p (params-of k)]
                                                  (conj! params {:key (key-of k) :params p}))
                                                (conj! joined (key-of k))
                                                (mention (key-of k)))
                                :else       (do (conj! joined k) (mention k)))
                              (walk v))
                :else nil))]
      (walk query)
      [(persistent! mentions) (persistent! params) (persistent! joined)])))

(defn- caller-supplied-attrs
  "调用方能提供值的属性：`{:entity map}` 的键、EQL ident 的键、
   声明过 `:attribute` 的 eql-param（D5.3、S5.6）。"
  [entity query param-uses param-decls]
  (concat (keys entity)
          ;; ident 只可能出现在 **map 的键位**（`{[:attr v] [...]}`）——查询根部的
          ;; 二元向量是普通的两个属性，不是 ident。按形状而不按位置认 ident 会
          ;; 把 `[:a/x :a/y]` 当成 caller-supplied 绑定，凭空多出一次拒绝。
          (let [found (transient [])]
            (letfn [(ident? [k] (and (vector? k) (= 2 (count k)) (keyword? (first k))))
                    (walk [q]
                      (cond
                        (vector? q) (doseq [n q] (walk n))
                        (map? q)    (doseq [[k v] q]
                                      (when (ident? k) (conj! found (first k)))
                                      (walk v))
                        (seq? q)    (walk (first q))
                        :else       nil))]
              (walk query) (persistent! found)))
          (for [{:keys [key params]} param-uses
                [pname _] params
                :let [decl (get-in param-decls [key :eql-params pname])]
                :when (:attribute decl)]
            (:attribute decl))))

;; --- 步骤 ② -----------------------------------------------------------------

(defn- keyword-form
  "The keyword a string or symbol key would have been, or nil."
  [k]
  (cond (string? k) (keyword k)
        (symbol? k) (keyword (namespace k) (name k))))

(defn- undeclared-mutation-params
  "One refusal naming EVERY undeclared key and the declared set (D2.18).
   Key names are model vocabulary, not data, so echoing them leaks nothing
   (S5.3 covers values; no argument value is read here). A string or symbol
   key whose keyword form is declared gets its own hint: an agent that sent
   `\"a/b\"` for `:a/b` otherwise reads \"undeclared\" as a different fault
   (2026-10-03: one burned ~25 calls guessing a size limit). Refused, never
   coerced -- refuse rather than guess (principles)."
  [id declared undeclared]
  (let [miskeyed (filter #(contains? declared (keyword-form %)) undeclared)
        named    (str/join ", " (map pr-str undeclared))]
    (reject :engine.request/unknown-mutation-param [:query (symbol id) (first undeclared)]
            (str "mutation " id " does not declare parameter(s) " named
                 (when (seq miskeyed)
                   (str "; parameter names are keywords: write "
                        (str/join ", " (map #(str (keyword-form %) ", not " (pr-str %)) miskeyed)))))
            :param (first undeclared)
            :expected (vec (sort-by str (keys declared)))
            :refs ["D2.18"])))

(defn check-mutation
  "D5.4 步骤 ② 的写那一半：一次 mutation 调用的实参对不对。

   与读那一半刻意分开，因为它们校验的是**两份不同的声明**：读的参数是
   resolver 的 `:eql-params`（在锚上收窄），写的参数是 mutation 自己的
   `:params`（D2.18 明说参数上不写 `:type`——类型的唯一事实源是属性登记）。
   拿一份去校验另一份，读的那条规则会把每一个写参数都判成「未声明的收窄
   参数」，而那句话对一次写没有意义。

   两条：**未声明的键拒绝**（拼错的参数静默丢掉，等于把一次写变成一次
   不完整的写），**缺必填拒绝**（在到达数据库之前，而不是靠约束报错）。"
  [mutations query]
  (let [node (first query)]
    (when (and (seq? node) (symbol? (first node)))
      (let [id    (str (first node))
            args  (or (second node) {})
            decl  (get mutations id)]
        (cond
          (nil? decl)
          (reject :engine.request/unknown-mutation [:query (symbol id)]
                  "no such mutation in this graph"
                  :refs ["D2.18"])

          :else
          (let [declared (:params decl)]
            (or
             (when-let [undeclared (seq (remove #(contains? declared %) (keys args)))]
               (undeclared-mutation-params id declared undeclared))
             (some (fn [[k d]]
                     (when (and (:required? d) (not (contains? args k)))
                       (reject :engine.request/missing-mutation-param [:query (symbol id) k]
                               "required mutation parameter is missing"
                               :param k :refs ["D2.18"])))
                   declared))))))))

(defn check-query
  "D5.4 步骤 ②：用**裁剪后的** catalog 校验查询里点名的每个属性。
   返回 nil（放行）或一张结构化拒绝。

   Steps (b)/(c) — the tier-rank decisions this docstring once walked
   through — retired with the tier axis (D5.11): the one compiled catalog
   already contains everything the graph declares, so there is nothing left
   for them to decide. What remains here is correctness, not tiering:
   unknown key, bare join, param discipline — and those run
   unconditionally, as they always did."
  [{:keys [catalog param-decls joins]} entity query star-expanded]
  (let [attrs   (:attributes catalog)
        known?  (fn [k] (or (contains? attrs k)
                            (contains? joins k)))
        [mentions param-uses joined] (collect query star-expanded)]
    (or
     ;; a) 点名的每个 key 必须在裁剪后的 catalog 里；不在就是 unknown——
     ;;    档位不够的 :restricted 在步骤 ① 已经不在了，因此走的正是这一支。
     (some (fn [{:keys [attr]}] (when-not (known? attr) (unknown-attribute attr))) mentions)

     ;; a2) join 属性点名了却没带子查询。**拒绝而不是给空列表**：这条路
     ;;     的值就是子实体的行，没有子查询就没有东西可返回，于是 Pathom 连
     ;;     resolver 都不会调——调用方拿到的 `[]` 与「这个父项真的没有子行」
     ;;     逐字相同（principles「失败绝不能长得像空结果」）。
     ;;
     ;;     只拦**点名**：`'*` 展开出来的 join 是「这个 entity 有这条路」
     ;;     的陈述，不是取值请求，把它变成拒绝会让通配查询在建了导航之后突然
     ;;     不能用了。
     (some (fn [{:keys [attr mention]}]
             (when (and (= :named mention)
                        (contains? joins attr)
                        (not (contains? joined attr)))
               (join-required attr (get joins attr))))
           mentions)

     ;; b) 参数纪律（D2.8）：只做锚上收窄，未声明的参数拒绝而不静默丢弃。
     (some (fn [{:keys [key params]}]
             (let [{:keys [eql-params limit order source-type offset-unsupported? source-collection]}
                   (get param-decls key)]
               (or
                (some (fn [[pname v]]
                       (cond
                         (and source-collection
                              (or (#{:offset :order_by :sort_dir :cursor} pname)
                                  (and (= :limit pname) (nil? (:limit source-collection)))))
                         (reject :engine.request/order-not-allowed [:query key pname]
                                 "This source collection does not support the requested paging, ordering or limit parameter")

                         (= :limit pname)
                         (when (and (:max limit) (> v (:max limit)))
                           (reject :engine.request/limit-exceeds-max [:query key :limit]
                                   "requested :limit exceeds the resolver's declared :max"
                                   :max (:max limit) :actual v :refs ["D2.8"]))

                         (= :order_by pname)
                         (when-not (contains? (set (:allowed order)) (m/resolve-order-by order v))
                           (reject :engine.request/order-not-allowed [:query key :order_by]
                                   "attribute is not in this resolver's declared :order :allowed"
                                   :attribute v :refs ["D2.20"]))

                         ;; `:offset` is refused when the resolver's source type
                         ;; has no offset capability (8.17). The engine used to
                         ;; pass it down for every type, and a connector that
                         ;; does not implement it answered the unskipped rows —
                         ;; the caller then reasoned about a page that was never
                         ;; skipped (principles: 静默的降级). The capability is
                         ;; decided in `impl.compile` and carried on
                         ;; `:param-decls`; this gate does not read type names.
                         (= :offset pname)
                         (when offset-unsupported?
                           (reject :engine.request/offset-unsupported [:query key :offset]
                                   (str "rows here cannot be skipped: this part of the query "
                                        "is answered by source type " (pr-str source-type)
                                        ", which returns the whole set at once, so :offset "
                                        "would have been ignored. That source type's connector "
                                        "contract grants no offset capability")
                                   :source-type source-type :refs ["D2.8" "8.17"]))

                         (= :sort_dir pname) nil

                         :else
                         (if-let [decl (get eql-params pname)]
                           (when-let [allowed (some-> (:allowed decl) set)]
                             (when-not (contains? allowed v)
                               (reject :engine.request/param-value-not-allowed
                                       [:query key pname]
                                       "value is outside the parameter's allowed code set"
                                       :param pname :refs ["D2.8"])))
                           (reject :engine.request/unknown-param [:query key pname]
                                   "undeclared EQL parameter; :eql-params only narrows on the anchor"
                                   :param pname :refs ["D2.8" "D2.20"]))))
                     params)
                (some (fn [[pname decl]]
                        (when (and (:required? decl) (not (contains? params pname)))
                          (reject :engine.request/missing-param [:query key pname]
                                  "required EQL parameter is missing"
                                  :param pname :refs ["D2.8"])))
                      eql-params))))
           param-uses))))

;; --- 步骤 ② 的行级安全那半条 -------------------------------------------------

(defn check-row-security
  "**E1 / [D5.14](../../../../../../../../docs/design/50-security.md#d514-行级安全门禁改为声明与接线核对)
   ③**: a row-security input that is present but `nil`.

   Cut time proved the wiring (the attribute is an `:input`, the statement
   binds it). Runtime has one thing left to prove, and it is the one pathom
   cannot: pathom decides \"input ready\" with `contains?`, so `{:order/tenant_id
   nil}` **runs the resolver**, and SQL binds `= NULL` — zero rows. That is safe
   by accident on SQL and not safe at all on a connector whose \"no value\"
   renders as an absent query parameter, i.e. as *unfiltered*. D5.14 says not to
   rely on the accident.

   A **missing** key is not this check's business: the planner already declares
   the output unreachable and `engine` turns that into a refusal before any
   connector runs. Two doors, both shut, for two different states.

   No re-planning: the answer comes from `:param-decls`, the same map step ②
   already consults for parameter discipline."
  [{:keys [param-decls]} entity query star-expanded]
  (let [[mentions _ _] (collect query star-expanded)]
    (some (fn [{:keys [attr]}]
            (when-let [a (:attribute (:row-security (get param-decls attr)))]
              (when (and (contains? entity a) (nil? (get entity a)))
                (reject :rowsec.input/null [:entity a]
                        (str "this attribute carries the row-security input for the resolver "
                             "answering the request, and it was supplied as nil; a null "
                             "partition key is not a partition")
                        :attribute a
                        :resolver-id (:resolver-id (get param-decls attr))
                        :refs ["D5.14" "D5.6"]))))
          mentions)))

;; --- D1.9b：平台保留的调用者命名空间 -------------------------------------------

(def reserved-caller-ns
  "Values in this namespace come only from the data plane's own verification
   (D1.9b): today the user a Workbench token vouches for."
  "open-atlas.caller")

(defn- reserved-caller? [a] (and (keyword? a) (= reserved-caller-ns (namespace a))))

(defn check-reserved-caller
  "No request may write `:open-atlas.caller/*` — not through `{:entity}`, an
   ident, or an attribute-bound parameter. Every caller is refused alike, an
   agent's token included: a value it could write would be a user it could
   claim to be."
  [{:keys [param-decls]} entity query star-expanded]
  (let [[_ params _] (collect query star-expanded)]
    (when-let [a (first (filter reserved-caller? (caller-supplied-attrs entity query params param-decls)))]
      (reject :engine.gate/reserved-caller-attribute [:entity a]
              (str "this attribute is set only by the data plane from a verified credential; "
                   "a request cannot supply it")
              :attribute a
              :refs ["D1.9b"]))))

(defn check-caller-values
  "The host hands caller values in; still refuse a malformed map rather than
   let a nil or a foreign key reach a resolver as if it had been vouched for."
  [caller]
  (when (some? caller)
    (cond
      (not (map? caller))
      (reject :engine.gate/reserved-caller-attribute [:caller]
              "caller values must be a map of :open-atlas.caller/* attributes to strings"
              :refs ["D1.9b"])
      :else
      (when-let [[k _] (first (remove (fn [[k v]] (and (reserved-caller? k) (string? v) (seq v))) caller))]
        (reject :engine.gate/reserved-caller-attribute [:caller k]
                "caller values must be non-blank strings under :open-atlas.caller/*"
                :attribute k
                :refs ["D1.9b"])))))

;; --- 步骤 ② 的递归那半条（Semantic contract 2.0 §6.4） ----------------------

(defn clamp-recursion
  "Contract 2.0 §6.4: a caller may recurse into a join the graph declared
   recursive, either `{k ...}` or `{k n}`, and the declared depth is the
   ceiling. A request above it -- or an unbounded `...` -- is CLAMPED to the
   declaration and the clamp is reported: this is the one permitted
   «truncation», because the ceiling is the author's contract, not a runtime
   silently giving less (D2.9). Measured on pathom3 2025.01.16-alpha: `...`
   over cyclic data never returns, so nothing unbounded may reach the planner.

   A recursion form on a join the graph did NOT declare recursive is refused
   (`:engine.request/recursion-not-declared`): Pathom would happily recurse,
   and that would be the engine granting a capability the contract withheld.

   Returns `{:query q' :applied [{:path :attribute :requested :applied} …]}`
   or `{:reject …}`. A query without recursion forms comes back byte-for-byte."
  [query recursion-decls]
  (let [applied (atom []) rejection (atom nil)]
    (letfn [(depth-form? [v] (or (integer? v) (= '... v)))
            (clamp [k v path]
              (if-let [declared (get recursion-decls k)]
                (let [n (if (integer? v) (min v declared) declared)]
                  (when (not= v n)
                    (swap! applied conj {:path path :attribute k :requested v :applied n}))
                  n)
                (do (reset! rejection
                            (reject :engine.request/recursion-not-declared (conj path k)
                                    (str "this join is not declared recursive in the graph; a depth or "
                                         "`...` can only be asked of a join whose resolver declares a "
                                         "recursion node")
                                    :attribute k
                                    :refs ["ATLAS-SEMANTIC-CONTRACT-V2 §6.4"]))
                    v)))
            (node* [node path]
              (cond
                (map? node) (into {} (map (fn [[k v]]
                                            (cond
                                              (depth-form? v) [k (clamp (key-of k) v path)]
                                              (vector? v) [k (level v (conj path (key-of k)))]
                                              :else [k v])))
                                  node)
                (seq? node) (let [inner (first node)]
                              (if (map? inner) (cons (node* inner path) (rest node)) node))
                :else node))
            (level [q path]
              (if (vector? q) (mapv #(node* % path) q) q))]
      (let [q (level query [])]
        (or (some-> @rejection (assoc :path (into [:query] (:path @rejection))))
            {:query q :applied @applied})))))
