(ns open.atlas.studio.version.impl.health
  "**切版本体检的「拒绝」那一半**（[D2.11](../../../../../../../../docs/design/20-dialect.md#d211-切版本体检强化)）。

   D2.11 把体检结果分成两栏，**两栏走两条路**：

   | 拒绝（本 ns） | 展示（B5b.2 的质量指标节） |
   |---|---|
   | 结构：dangling input、link 断链、多方产出、传递引用冲突 | description / synonyms 覆盖率 |
   | D2.2 属性声明漂移 | `:format :code`/`:id` 的 `:values` 覆盖率 |
   | D2.13 无锚 list 未签 `:curated? true` | |
   | D2.8 分页声明与 `:allowed` 子集 | carried-only 键使用清单 · 档位分布 |
   | E6.2 韧性 | |

   **质量指标只展示不拒绝是裁定过的**（D2.11 原文：社会压力够用；强制阈值会催生
   垃圾描述凑数）。所以本 ns 里一条覆盖率都没有——顺手复用拒绝通道「也拒一下」
   正是那条裁定要防的事。

   ## 跑在**投影产物 + 闭包**上，不是跑在池上（README 裁定 ②）

   体检要判的是「**这个版本**长什么样」，而池里还有一堆不属于这张图的东西。

   ## D2.8 与全部结构不变量**回落 `read-dialect`**，不写第二个 schema

   分页强制（`:dialect.invariant/list-limit-missing`）与 `:allowed ⊆ :values`
   （`:dialect.invariant/eql-param-unresolvable`）在 B0 就是不变量。这里**再跑一遍
   B0 的 reader**，把它的 error 原样带出来——重写一份判定等于给同一条规则造第二个
   事实源，两份迟早分叉，而分叉只在生产看得见。带出来的 code 保持 `dialect.*`：
   重贴一层 `cut.*` 的皮会让下游按 code 分流时漏掉一半（见 impl.finding）。"
  (:require [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.version.impl.closure :as closure]
            [open.atlas.studio.version.impl.finding :as f]))

;; ---------------------------------------------------------------------------
;; Keys in an `:output` / `:returns` tree
;; ---------------------------------------------------------------------------

(defn- tree-keywords [tree]
  (into #{} (filter qualified-keyword?) (tree-seq coll? seq tree)))

(defn- output-attributes
  "Every qualified keyword of a resolver's `:output` tree — **branches
   included** (D2.29, owner 2026-09-02).

   This used to subtract a set of `structural-keys`: a branch key named a
   navigation declared on the namespace entry, navigations had their own
   declaration site, and counting them into the D2.2 closure would have
   reported each of them as an unregistered attribute — a false refusal. That
   second declaration site is gone. A branch key IS a join attribute, a leaf
   key IS a scalar attribute, and both are ordinary entries of the one
   `:attributes` registry — so a branch now earns its existence the same way a
   leaf does, by appearing here. The `:collection?` exemption that rode along
   with the old exclusion (D2.16's collection relaxation) retired with it:
   there is no longer a second name to collide with."
  [x]
  (tree-keywords (:output x)))

(defn existence-closure
  "**D2.2 的存在性闭包**：resolver `:output` ∪ mutation `:params` ∪ mutation
   `:returns` 的并集（D2.2 逐字点名这三处，D2.18 是它的出处）。

   `:input` **不在闭包里**——D2.2 只点名了产出侧。一个属性若只作为 input 出现,
   它由别的 resolver 产出才谈得上被读到；本 ns 另有 dangling-input 判它。"
  [model]
  (into (into #{} (mapcat output-attributes) (:resolvers model))
        (mapcat (fn [m]
                  (into (set (keys (:params m)))
                        (tree-keywords (:returns m)))))
        (:mutations model)))

;; ---------------------------------------------------------------------------
;; 结构（D2.11 第一格）
;; ---------------------------------------------------------------------------

(defn- dangling-inputs [model]
  (let [attrs (set (keys (:attributes model)))]
    (vec (concat
          (for [r (:resolvers model), a (:input r) :when (not (contains? attrs a))]
            (f/make :cut.structure/dangling-input :error [:resolvers (:id r) :input]
                    "this resolver reads an attribute that is not in the version's attribute set"
                    :got a :refs ["D2.11"]))
          (for [m (:mutations model), a (keys (:params m)) :when (not (contains? attrs a))]
            (f/make :cut.structure/dangling-input :error [:mutations (:id m) :params]
                    "this mutation takes a parameter that is not in the version's attribute set"
                    :got a :refs ["D2.11" "D2.18"]))))))

(defn- broken-links [model]
  (let [attrs (set (keys (:attributes model)))]
    (vec (for [l (:links model)
               end [:from :to]
               :when (not (contains? attrs (get l end)))]
           (f/make :cut.structure/broken-link :error [:links [(:from l) (:to l)] end]
                   "one end of this link is not in the version's attribute set"
                   :got (get l end) :refs ["D2.11"])))))

(defn- multiple-producers
  "**多方产出**：同一属性被两个 `result_type \"single\"` 且 `:input` 键面相同的
   resolver 产出。

   **裁量点（报给 owner）**：D2.11 只写「多方产出」四个字。按最宽的读法（同一
   属性出现在两个 `:output` 里）会拒掉 demo 模型里 28 个属性——而那是 Pathom 的
   常态：同一个属性经不同锚点各有一条路。按「同一锚点」读还会拒掉
   `demo_mso_vpos` 与 `demo_mso_dropship_vpos` 这一对——两条从同一锚出发、
   WHERE 各自收敛的具名集合，正是 [D2.8](../../../../../../../../docs/design/20-dialect.md#d28-查询纪律有锚有限有主码值单一定义)
   第 1 条要的形态。

   所以判据收在 `\"single\"` 上：`\"single\"` 回答的是「给定这个锚，这个属性的
   值是什么」——同一锚上两个答案是真歧义，规划器替谁选都是错的。

   **锚是整个 `:input` 键集**，不是单个属性——复合键因此天然成立（owner
   2026-09-07，doc 106 §106.14 第 14 条）：按 `[k1 k2]` 读的与按 `[k1]` 读的
   是两个不同的锚，不算多方产出；同一个复合锚上的两条仍然算。

   **Exemption for ranked producers** (D2.11 amendment 2026-09-18, Semantic
   contract 2.0 §4.1 \"Studio 切版本体检\"): several `\"single\"` resolvers on
   the same anchor are *not* multiple producers when their `:priority` values
   are pairwise distinct — the planner then has a total order and falls back
   along it (§4.2), so there is nothing ambiguous to refuse. `:priority`
   absent means 0 (dialect schema). Any two equal — including two that both
   omit it — is still a tie and is refused as before."
  [model]
  (let [prod (reduce (fn [acc r]
                       (if (= "single" (:result_type r))
                         (reduce (fn [a k]
                                   (update a [k (vec (sort (:input r)))] (fnil conj [])
                                           {:id (:id r) :priority (or (:priority r) 0)}))
                                 acc (output-attributes r))
                         acc))
                     {} (:resolvers model))
        tied? (fn [rs] (not (apply distinct? (map :priority rs))))]
    (vec (for [[[attr input] rs] (sort-by pr-str prod)
               :when (and (> (count rs) 1) (tied? rs))]
           (f/make :cut.structure/multiple-producers :error [:attributes attr]
                   (str "two \"single\" resolvers produce this attribute from the same input anchor; "
                        "the planner cannot choose — give them distinct :priority (contract 2.0 §4)")
                   :got attr :expected (vec (sort (map :id rs)))
                   :refs ["D2.11" "D2.8"]
                   :files [(pr-str input)])))))

(defn- incomplete-root-collections
  "**A compound-key thing's root collection must hand back the WHOLE key**
   (owner 2026-09-07, doc 106 §106.14 item 14).

   A root collection — a resolver with no `:input` producing `{X/all […]}` —
   is the only way in to a thing; every deeper read starts from a row it
   answered. When the thing is recognised by several attributes together and
   the branch names only some of them, every row it answers is unusable: the
   by-key resolver's `:input` cannot be satisfied, so the planner can reach
   the list and nothing beyond it. Refusing at the cut is the point — a graph
   whose entry point cannot be walked from is exactly the «查询时断路的图» of
   8.4.19, and it is not visible from any single object.

   Single-key things are untouched: a one-attribute key either is in the
   branch or the branch is empty, and that case is already D2.2's."
  [model]
  (let [attrs   (:attributes model)
        ids     (reduce (fn [acc [akw d]]
                          (if (and (:identity? d) (not (:target d)) (namespace akw))
                            (update acc (namespace akw) (fnil conj (sorted-set)) akw)
                            acc))
                        {} attrs)]
    (vec
     (for [r (:resolvers model)
           :when (empty? (:input r))
           node (:output r)
           :when (map? node)
           [bkw leaves] node
           :let [target (:target (get attrs bkw))
                 key-set (get ids (str target))]
           :when (and target (> (count key-set) 1))
           :let [have (set (filter keyword? (tree-seq coll? seq leaves)))
                 missing (vec (remove have key-set))]
           :when (seq missing)]
       (f/make :cut.structure/incomplete-root-collection :error [:resolvers (:id r) :output bkw]
               (str "this root collection lists " target
                    ", which is recognised by " (count key-set)
                    " attributes together, but its branch does not produce all of them; "
                    "nothing can be read from the rows it answers")
               :got bkw
               :expected (mapv #(str (symbol %)) key-set)
               :files (mapv #(str (symbol %)) missing)
               :refs ["D2.11" "doc 106 §106.14 item 14"])))))

(defn- empty-recipe
  "**A graph whose recipe names nothing cannot be published** (doc 106 §106.12
   item 13.3, measured 2026-09-07 on `jie.gao@tdsynnex.com/cis-prod`).

   Every other check here judges what the version HAS, so a version with
   nothing in it passed all of them and reported «Every cut check passes» over
   an empty recipe — a published graph that answers no query and says nothing
   about why. That is the same failure as 8.4.19's «查询时断路的图», only total.

   Judged on `local`, like the other declaration-class checks: what is at stake
   is this graph's own recipe, and D3.17 retired cross-graph references, so
   there is nothing another version could have contributed.

   The message names the two doors that fill a recipe, because «empty» is not
   a defect anyone fixes by reading a code — the wizard writes objects into a
   graph, and the Things page files pool objects the wizard already wrote."
  [local]
  (when (every? empty? [(:namespaces local) (:attributes local) (:resolvers local)
                        (:mutations local) (:links local) (:terms local)
                        (:units local) (:metrics local)])
    [(f/make :cut.structure/empty-recipe :error [:members]
             (str "this graph's recipe names no objects at all, so the version would carry "
                  "nothing and answer no query; add objects to it — generate them from a "
                  "source with the wizard, or file pool objects into this graph from the "
                  "Things page")
             :refs ["D3.19" "doc 106 §106.12 13.3"])]))

(defn- unsigned-named-queries
  "**D2.13**：无锚的 list resolver 必须显式写 `:curated? true`。

   D2.13 逐字把这条的执法放在**切版本**，不在 B0：一个还在写的池对象允许暂时
   没写标记，密封成版本的那一刻不允许。

   「有锚」= `:input` 非空（父键 / identity anchor 至少一个）。**不去解析
   WHERE 猜收敛性**——D2.13 明说那是业务判断不是语法判断。无锚的两种 list
   （烤进 WHERE 的具名查询 vs 误写的全表入口）结构上完全相同，机器分不开，
   所以要的是作者签字，而不是更聪明的推断。"
  [model]
  (vec (for [r (:resolvers model)
             :when (and (= "list" (:result_type r))
                        (empty? (:input r))
                        (not (:curated? r)))]
         (f/make :cut.resolver/unsigned-named-query :error
                 [:resolvers (:id r) :curated?]
                 (str "this list resolver has no anchor in :input, so it is a named query; "
                      "declare :curated? true to sign for the convergence baked into its source")
                 :expected true :refs ["D2.13" "D2.8"]))))

(defn- join-root
  "`:result_type \"list\"` 的 resolver 把行装在**单一** join 分支下（D2.8 有锚 +
   D2.16）。判据与 `engine/impl/compile` 逐字相同——那里是编译前的门，这里是
   切版本前的门，问的是同一个形状。"
  [tree]
  (when (and (vector? tree) (= 1 (count tree)) (map? (first tree)))
    (let [m (first tree)]
      (when (= 1 (count m)) (key (first m))))))

(defn- uncompilable-resolvers
  "**编不出来的 resolver 拒绝切版本**（8.4.19 裁定十三 ②，owner 2026-09-04）。

   引擎对这三种只发 warning 然后把该条 resolver 排除出编译：草稿里它像存在,
   查询时它不存在，而拒绝里不提这条 resolver 一个字。裁定把它们升为 error。

   code 是**引擎的**（见 impl.finding 的 `engine-compile-codes`）：同一个缺陷在
   summary、体检与 plan 三处必须是同一个词。

   **第三种（`source-not-executable`）不在这里**（施工记录 2026-09-04）：裁定②
   点了它的名，但「本机装了哪些连接器」是**宿主配置**，把它升为切版本 error
   会让同一份模型在两台机器上一台切得出一台切不出——而 fixtures/demo 的 13 条
   `derived` resolver 在没加载 derived 连接器的 JVM 里当场把这份**既成事实**的
   模型判成不可发布。它仍然出现在 `inspect_graph` 的 `:compile` 一节（那一节
   本来就是「这台机器上的编译结果」），只是不拒绝切版本。裁的是原则，这一条
   与体检的纯函数纪律正面冲突，留给 owner。"
  [_opts model]
  (vec (for [r (:resolvers model)
               :let [src (merge (get-in model [:sources (:source-ref r)]) (:source r))
                     path [:resolvers (:id r)]
                     bad (cond
                           (nil? (:type src))
                           (f/make :engine.compile/source-unresolved :error
                                   (conj path :source-ref)
                                   (str "this resolver's :source-ref names no source, so nothing "
                                        "can execute it; it would be carried in the version and "
                                        "excluded from every query")
                                   :got (:source-ref r) :refs ["8.4.19"])

                           (and (= "list" (:result_type r)) (nil? (join-root (:output r))))
                           (f/make :engine.compile/list-without-anchor :error
                                   (conj path :output)
                                   (str "this list resolver's :output has no single join root to "
                                        "carry the rows, so the engine compiles nothing for it — "
                                        "the graph would serve a path that is not there")
                                   :refs ["D2.8" "D2.16" "8.4.19"]))]
             :when bad]
         bad)))

;; ---------------------------------------------------------------------------
;; D2.2 属性声明漂移
;; ---------------------------------------------------------------------------

(defn- attribute-drift
  "D2.2：**属性以存在性闭包中的出现为存在依据**；`:attributes` 注册表只承载
   元数据，不再是第二份存在性声明。两个方向的漂移都拒绝，日常写入只警告
   （D2.2「书写自由、密封严格」，与 D3.4 同一哲学）。"
  [model]
  (let [reg     (set (keys (:attributes model)))
        clos    (existence-closure model)]
    (vec (concat
          (for [a (sort-by pr-str (remove reg clos))]
            (f/make :cut.attribute/undeclared :error [:attributes a]
                    "this attribute is produced but not registered in :attributes — the only source of type facts"
                    :got a :refs ["D2.2" "D2.18"]))
          (for [a (sort-by pr-str (remove clos reg))]
            (f/make :cut.attribute/unregistered-use :error [:attributes a]
                    "this attribute is registered but appears in no :output, mutation :params or :returns"
                    :got a :refs ["D2.2"]))
          ;; D2.29: a join attribute carries rows of another entity, not a
          ;; value, so it has no `:type` by construction — `:target` is its
          ;; type fact. Only a scalar entry owes one.
          (for [[a d] (sort-by (comp pr-str key) (dialect/scalar-attributes model))
                :when (not (:type d))]
            (f/make :cut.attribute/metadata-missing :error [:attributes a :type]
                    ":attributes is the single source of type facts; a registry entry without :type is not one"
                    :got a :refs ["D2.2" "D2.29"]))))))

;; ---------------------------------------------------------------------------
;; E6.2 韧性（拒绝）
;; ---------------------------------------------------------------------------

(def default-timeout-enforcing-types
  "能**强制**超时的连接器契约。E6.2 原话：「enforce 不了超时的连接器不允许发布」。

   四份已注册契约都能：sql-v1 conformance 逐字写着「超时强制」，api 走 HTTP
   客户端的读写超时，derived-v1 有硬超时预算，static 在内存里不发起外部调用。
   **它仍然是一份可传入的清单**，因为将来的连接器不一定能——那时这条判定要
   拦得住它，而不是靠有人记得回来改代码。"
  #{"sql" "api" "static" "derived"})

(def default-platform-timeout-ms
  "平台缺省超时（D6.2 的生效优先级最后一级：resolver 覆盖 > source policy >
   连接器类型缺省 > 平台缺省）。部署配置可以**收紧**它，不得放宽（D6.2）。"
  30000)

(defn effective-resilience
  "**生效 policy**（D6.2 的四级合并，从最弱到最强逐层覆盖）。

   **裁量点（报给 owner）**：D6.2 与 E6.2 都说 resolver 可局部覆盖，而
   [dialect-v1](../../../../../../../../docs/contracts/dialect-v1.md) 的
   resolver 条目 schema **没有** `:resilience` 键——resolver 覆盖今天写不出来。
   这里照读 `(:resilience x)`，写得出来的那天判定不用改；写不出来的今天它恒为 nil。"
  [opts model x]
  (let [src (get (:sources model) (:source-ref x))]
    (merge {:timeout_ms (or (:platform-timeout-ms opts) default-platform-timeout-ms)}
           (get (:connector-defaults opts) (:type src))
           (:resilience src)
           (:resilience x))))

(defn- retry?
  "「**生效 policy 含 retry**」（E6.2）：重试次数为正，**或**声明了可重试故障
   类别。后者单独算数是 E6.2 逐字要求的——`retry_on` 定义可重试故障类别，
   它出现就意味着这条路径上打算重试。"
  [pol]
  (boolean (or (and (integer? (:max_retries pol)) (pos? (:max_retries pol)))
               (seq (:retry_on pol)))))

(defn- timeout-findings [opts model]
  (let [types (or (:timeout-enforcing-types opts) default-timeout-enforcing-types)]
    (vec (for [[sid src] (sort-by key (:sources model))
               :let [pol (merge {:timeout_ms (or (:platform-timeout-ms opts)
                                                 default-platform-timeout-ms)}
                                (get (:connector-defaults opts) (:type src))
                                (:resilience src))
                     t   (:timeout_ms pol)]
               :when (or (not (contains? types (:type src)))
                         (not (and (integer? t) (pos? t))))]
           (if (contains? types (:type src))
             (f/make :cut.resilience/timeout-not-enforceable :error
                     [:sources sid :resilience :timeout_ms]
                     "the effective timeout for this source is not a positive number of milliseconds"
                     :got (f/redact t) :refs ["E6.2" "D6.2" "D6.3"])
             (f/make :cut.resilience/timeout-not-enforceable :error [:sources sid :type]
                     "this connector cannot enforce a timeout, so a version using it must not be published"
                     :got (:type src) :expected (vec (sort types))
                     :refs ["E6.2"])))))) 

(defn- idempotency-findings
  "E6.2 的幂等性判定，**两句话分开**：

   - **read resolver 按 D6.4 默认幂等**——读同一份数据两次不产生额外业务副作用,
     这是 resolver 的定义决定的。所以带 retry 的 resolver **通过**，一条 finding
     都不出。
   - **mutation 默认非幂等**，只有两种情况允许 retry：作者显式声明它自然幂等
     （`:idempotency :natural`），或它携带被强制执行的 idempotency key
     （`:idempotency {:key …}`）。**未声明即拒绝**，失败方向朝关闭（D6.4）。

   `retry_on` **在 source 层不能替 mutation 声明幂等**（E6.2 逐字）：它定义的是
   可重试的**故障类别**，与「这个操作重放一次安不安全」是两件事。所以一个只在
   source 上写了 `:retry_on`、mutation 自己没声明幂等的模型，照样拒绝——消息里
   点名这一点，因为它正是最容易被读成「我已经配过了」的那一处。"
  [opts model]
  (vec (for [m (sort-by :id (:mutations model))
             :let [pol (effective-resilience opts model m)
                   idem (:idempotency m)]
             :when (and (retry? pol)
                        (not (or (= :natural idem)
                                 (and (map? idem) (some? (:key idem))))))]
         (f/make :cut.resilience/mutation-idempotency-unknown :error
                 [:mutations (:id m) :idempotency]
                 (if (and (seq (:retry_on pol))
                          (not (and (integer? (:max_retries pol)) (pos? (:max_retries pol)))))
                   (str "the effective policy is retryable because :retry_on is declared on the source; "
                        ":retry_on names retryable failure classes and never declares idempotency for a "
                        "mutation — declare :idempotency :natural or an enforced idempotency key")
                   (str "a mutation is not idempotent by default; declare :idempotency :natural or an "
                        "enforced idempotency key, otherwise it must not be retried"))
                 :got (:source-ref m)
                 :expected [":natural" "{:key <attribute>}"]
                 :refs ["E6.2" "D6.4"]))))

;; ---------------------------------------------------------------------------
;; 回落 B0 的 reader（D2.8 与全部结构不变量）
;; ---------------------------------------------------------------------------

(defn- wiring-block
  "What `spi/bound-attributes` is asked about: the operation block the entry
   actually runs — its inline `:source` merged **over** the shared source it
   names, same order as `engine/impl.compile`, one fact not two — **plus** the
   entry's own `:input` and `:eql-params`.

   Those two ride along because a binding is not decided by the statement
   alone: D2.1's identity default (\"placeholder name = attribute local name
   or eql-param name\") spans exactly this seam, so a connector asked without
   them would report every identity-bound name as unbound."
  [model x]
  (assoc (merge (get (:sources model) (:source-ref x)) (:source x))
         :input      (:input x)
         :eql-params (:eql-params x)))

(defn row-security-findings
  "**E1 / [D5.14](../../../../../../../../docs/design/50-security.md#d514-行级安全门禁改为声明与接线核对)**
   (owner 2026-09-03): a `:row-security` declaration must match how the entry is
   actually wired.

   Until E1 this ns only *listed* the declarations (`:row-security-declared`).
   Listing is what D5.6 could do while nothing enforced them; D5.14 changed the
   judgement, because an entry that declares \"partitioned by tenant\" while
   running `SELECT * FROM orders` is **worse than one that declares nothing** —
   operations believes someone is filtering.

   Four outcomes, all `:error`, i.e. **the cut is refused** (D5.14: the failure
   direction does not change, only the point of judgement moves earlier). They
   are four codes and not one because they have four different fixes, and a
   refusal that sends the reader to the wrong file costs more than it saves:

   | | code | who fixes it |
   |---|---|---|
   | `:attribute` is not one of the entry's `:input` | `:rowsec.wiring/not-an-input` | the modeller |
   | no connector is registered for this source type | `:rowsec.wiring/no-connector` | whoever assembled this host |
   | the connector cannot say what it binds | `:rowsec.wiring/unverifiable` | the connector's author |
   | it can, and this attribute is not among them | `:rowsec.wiring/not-bound` | the modeller |

   `no-connector` exists because nothing else in the pipeline notices a missing
   connector: cutting never looked at source types, and `engine` drops such a
   resolver from the compiled catalog as a `:warning`. So this check was the
   first thing to see it — and it used to report it as `unverifiable`, pointing
   at row security when the truth was an assembly gap.

   No statement text in any finding — a refusal names the entry, the attribute
   and the missing half, and nothing else (finding-v1 §5)."
  [{:keys [registered-source-types bound-attributes]} model]
  (let [known?  (or registered-source-types #{})
        bound-of (or bound-attributes (constantly nil))]
    (vec
     (mapcat
      (fn [[kind coll]]
        (keep
         (fn [x]
           (when-let [attr (:attribute (:row-security x))]
             (let [path   [(if (= "resolver" kind) :resolvers :mutations) (:id x) :row-security]
                   inputs (set (:input x))
                   src    (wiring-block model x)
                   type   (:type src)]
               (cond
                 (not (contains? inputs attr))
                 (f/make :rowsec.wiring/not-an-input :error path
                         (str "this " kind " declares row security on an attribute that is not "
                              "one of its :input, so nothing narrows the source — the "
                              "declaration claims a filter the wiring does not perform")
                         :got attr :expected (vec (sort (:input x)))
                         :refs ["D5.14" "D5.6"])

                 (not (contains? known? type))
                 (f/make :rowsec.wiring/no-connector :error path
                         (str "no connector for this source type is registered on the host "
                              "cutting this version, so the row-security wiring cannot be "
                              "checked; this is an assembly gap, not a fault in the model — "
                              "the host must load the connector for this type")
                         :got type :expected (vec (sort known?))
                         :refs ["D5.14" "D2.15"])

                 :else
                 (if-let [bound (bound-of src)]
                   (when-not (contains? bound attr)
                     (f/make :rowsec.wiring/not-bound :error path
                             (str "the attribute is an :input of this " kind " but its "
                                  "operation binds no value for it, so every caller reads "
                                  "every row")
                             :got attr :expected (vec (sort bound))
                             :refs ["D5.14"]))
                   (f/make :rowsec.wiring/unverifiable :error path
                           (str "the connector for this source type cannot report which of "
                                "the :input attributes it binds, so the row-security wiring "
                                "cannot be verified; it is not assumed bound")
                           :got type
                           :refs ["D5.14"]))))))
         (sort-by :id coll)))
      [["resolver" (:resolvers model)] ["mutation" (:mutations model)]]))))

(defn- declared-ids
  "`\"resolver:<id>\"` / `\"mutation:<id>\"` for every entry carrying a
   `:row-security` declaration."
  [model]
  (concat (keep #(when (:row-security %) (str "resolver:" (:id %))) (:resolvers model))
          (keep #(when (:row-security %) (str "mutation:" (:id %))) (:mutations model))))

(defn- verified-ids
  "Declared ids **minus** the ones `row-security-findings` refused. This is
   what the D5.14 gate reads: it must be able to name the members of a
   closure that did not pass, so the report carries ids, not a boolean."
  [model rs-findings]
  (let [failed (into #{} (map (fn [{[coll id _] :path}]
                                (str (if (= :resolvers coll) "resolver" "mutation") ":" id)))
                     rs-findings)]
    (vec (sort (remove failed (declared-ids model))))))

(defn- dialect-recheck
  "把投影产物重新过一遍 B0 的 reader。**这是 D2.8 的落点**（分页强制、
   `:allowed ⊆ :values`），也是「不写第二个 schema」的落点。"
  [model]
  (try
    (let [{:keys [findings]} (dialect/read-dialect (dialect/write-dialect model))]
      {:errors  (vec (filter #(= :error (:severity %)) findings))
       :carried (vec (sort (distinct (map #(pr-str (:path %))
                                          (filter #(= :dialect.carried/key-present (:code %))
                                                  findings)))))})
    (catch Throwable t
      {:errors [(f/make :cut.version/malformed :error []
                        (str "the projected model is not writable as dialect: " (.getMessage t))
                        :refs ["D2.12"])]
       :carried []})))

;; ---------------------------------------------------------------------------
;; 入口
;; ---------------------------------------------------------------------------

(defn check
  "投影产物 + 闭包 → 体检结论。

   `=> {:findings [...] :health {:findings … :carried-only-keys …
        :row-security-declared … :restricted-from-references …}}`

   `:health` 的键面是 `artifact/seal-graph` 的 `health-input-keys`（D4.9）,
   **原样交给密封**——「体检结论封进制品」是 D4.1：server 没有池，它无法重算
   「这个属性有没有第二个产出者」这种全池视野的结论。

   `:findings` 有任一 `:error` 即切不出版本（README 裁定 ②：四步串行，前一步
   不过不进下一步）。"
  ([model cl] (check model cl {} model))
  ([model cl opts] (check model cl opts model))
  ([model cl opts local]
   ;; **两个视图，两类检查**（2026-08-24 补）：
   ;;
   ;;   `model` —— 合并后的（R3.2）。**闭包类**检查看它：一条 link 的两端可能一端在
   ;;              本地、一端由引用带入，只看本地会把合法的判成断链。
   ;;   `local` —— 只有自己的对象。**声明类**检查（D2.2 的漂移）只看它。
   ;;
   ;; 为什么声明类不能看合并后的：被引用的那个版本**切出来的时候已经自己体检过一遍**
   ;; （D4.1：体检结论就封在它的制品里）。在这里再查一遍，等于让**别人**图里的漂移
   ;; 挡住**我**的切版本——而报出来的位置指向别人的对象，我既改不动也说不清。
   ;;
   ;; 这与池的投机校验做差集是同一条纪律：**拒绝要指向这次动作**。
   (let [{:keys [errors carried]} (dialect-recheck model)
         ;; E1 / D5.14: kept in a local because the report needs it twice —
         ;; once as refusals, once (by subtraction) as `:row-security-verified`.
         rs-findings (row-security-findings
                      ;; **由 opts 供给，而不是在这里读全局分派表**：本 ns 的
                      ;; docstring 说体检是纯函数（跑在投影产物 + 闭包上）,
                      ;; 而「这台机器装了哪些连接器」是宿主配置——伸手去摸
                      ;; multimethod 的注册表，会让同一份模型在两台机器上体检
                      ;; 出不同结论而没有任何入参说明这一点。同 `:timeout-
                      ;; enforcing-types` 的处理（见 `timeout-findings`）。
                      {:registered-source-types
                       (or (:registered-source-types opts) (spi/registered-types))
                       :bound-attributes
                       (or (:bound-attributes opts) spi/bound-attributes)}
                      model)
         findings (f/sort-findings
                   (vec (concat errors
                                rs-findings
                                ;; doc 106 §106.12 13.3 — declaration-class,
                                ;; hence `local`: it is this graph's recipe.
                                (empty-recipe local)
                                (dangling-inputs model)
                                (broken-links model)
                                (multiple-producers model)
                                (incomplete-root-collections model)
                                ;; D2.13 是**声明类**：无锚 list 该由它自己的作者签字,
                                ;; 引用带入的那些在它们自己切版本时已经签过。
                                (unsigned-named-queries local)
                                ;; 8.4.19 裁定十三 ②：与 D2.13 同为**声明类**
                                ;; ——引用带入的对象在它们自己切版本时已经过
                                ;; 这道门，所以看 `local`。
                                (uncompilable-resolvers opts local)
                                (attribute-drift local)
                                (timeout-findings opts model)
                                (idempotency-findings opts model))))]
     {:findings findings
      :health
      {:findings findings
       :carried-only-keys carried
       ;; D5.6：声明了 `:row-security` 的清单（D4.9 点名的四项之一）。**报告仍要
       ;; 列名**——E1 之后它不再等于「不执法的清单」，而是「声明过的清单」。
       :row-security-declared (vec (sort (declared-ids model)))
       ;; E1 / D5.14：其中**接线核对通过**的。门禁读的是这一项，不是上一项：
       ;; 判据从「有声明即拦」变成「有声明且不在这里即拦」。两项都要在制品里，
       ;; 因为门禁必须点得出闭包里是哪几个没过（`:row-security-declared` 减去它）。
       :row-security-verified (verified-ids model rs-findings)
       ;; 曾经这里还有 `:restricted-from-references`（D5.5：引用带入的
       ;; `:restricted` 属性清单）。两个前提都没了——跨图引用随 D3.17 退役，
       ;; 属性档位随 owner 裁定 2026-08-27 退役——而 `health-input-keys`
       ;; 是**封闭**的（D4.9），所以它不是留一个空值，是不再存在。
       }})))
