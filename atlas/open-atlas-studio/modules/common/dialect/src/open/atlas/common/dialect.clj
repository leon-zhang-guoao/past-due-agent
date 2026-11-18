(ns open.atlas.common.dialect
  "B0 内核的唯一对外接口。跨模块只许 require 本 ns；
   open.atlas.common.dialect.impl.* 视为私有（clj-kondo :discouraged-namespace 执法）。

   三个纯函数 + 一个 finding 工具面。无 IO：输入输出都是字符串/EDN，
   文件读写归调用方。

   契约：docs/contracts/dialect-v1.md（键表/规范形/conformance 矩阵）、
        docs/contracts/finding-v1.md（finding 形状）。"
  (:require [open.atlas.common.dialect.impl.carry :as carry]
            [open.atlas.common.dialect.impl.read :as read]
            [open.atlas.common.dialect.impl.write :as write]
            [open.atlas.common.dialect.impl.merge :as merge*]
            [open.atlas.common.dialect.impl.finding :as finding]
            [open.atlas.common.dialect.impl.jsonkeys :as jsonkeys]
            [open.atlas.common.dialect.impl.key-docs :as key-docs]
            [open.atlas.common.dialect.impl.need :as need]
            [open.atlas.common.dialect.impl.schema :as schema]
            [open.atlas.common.dialect.impl.vocab :as vocab]))

(def format-version
  "The public dialect contract version this implementation reads and writes —
   docs/contracts/dialect-v1.md.

   A package's `:compat` block names it (doc 104 §8.17.7 A) so an installing
   instance can tell \"cut against a dialect I do not implement\" apart from
   \"broken bundle\". It lives here because the version is the dialect's own
   fact; a copy in the artifact or in a connector would be a second place to
   forget to bump.

   **Not `:artifact/format-version`** (an integer, docs/contracts/artifact-v1.md):
   the envelope and the model contract version independently."
  "v1")

(defn read-dialect
  "字符串 → {:model 内部模型|nil :findings [finding…]}。
   parse + 归一（D2.17 全限定 kw、键序无关）+ 结构 schema（封闭、词表）
   + 跨条目不变量（D2.19/D2.20/D2.22/D2.25–27）。
   存在 :error 时 :model 为 nil——不产带病模型。"
  [s]
  (read/read-dialect s))

(defn write-dialect
  "内部模型 → 规范形字符串（D2.12 唯一形）。
   map 键字典序、UTF-8 无 BOM、\\n、kw 全限定不缩写（不产 #:ns{}）、
   缺省值不书写。往返不变量：(write (read s)) 逐字节 = (write 任何等价输入)。
   只接受 read-dialect 产出的合法模型；对非法输入抛断言，不产 finding
   （writer 不是第二道校验门）。"
  [model]
  (write/write-dialect model))

(defn merge-dialects
  "[{:file 名 :model 内部模型}…] → {:model|nil :findings}。
   作者端分拆文件的纯并集：同 id resolver/mutation、同键 source/namespace/
   attribute/unit/metric、同端点 link 一律 :error 并在 :files 点名两个文件。
   不继承参考实现「后文件静默覆盖」。合并后的模型需整体重过跨条目不变量
   （注册表「先注册后使用」可能跨文件成立）。"
  [named-models]
  (merge*/merge-dialects named-models))

(defn merge-closure
  "引用闭包的成员模型 → 一份合并模型（R3.2）。`[{:file 名 :model …}…]` →
   `{:model|nil :findings}`。

   **与 `merge-dialects` 的区别是有理由的，不是放松。** 作者端把一个模型拆成
   多文件时，同键就是笔误——声明一次就够了。闭包里出现同键则是**常态**：
   全公司共享一个语义池（D1.2），两张图各自的投影会带着同一个池对象过来,
   逐字节相同；按作者端那份严格性判，几乎每一个真实闭包都合不起来
   （最先撞上的就是两张图各有一个同名的 source，比如都叫 db）。

   所以：**同键同值折叠，同键异值拒绝**（`:dialect.merge/closure-conflict`,
   owner 裁定 2026-08-25 认可）。异值意味着两版是在池里那个对象不同的两个时刻
   密封的，一份 env 服务不了两种定义——而猜哪一个才是灾难。

   **这条规则有一个已知盲区，且已登记为未决项 OQ-MULTI-STUDIO**：它的依据是
   「闭包成员全部投影自同一个池」。一台 Server 将来装载**两个 studio** 的制品时,
   两个池各有一个同名 source、定义又碰巧相同，会被静默折叠成一个——而它们是两个
   库。多 studio 是要走的路（owner 2026-08-25），届时的备选是按图给 source 键加
   命名空间。**在单池下这条规则是正确的**，所以现在不改。

   合并产物同样整体重跑 invariants。"
  [named-models]
  (merge*/merge-closure named-models))

(defn format-dialect
  "字符串 → {:formatted 字符串|nil :findings}。
   read + write 的组合：conformance「打乱键序或 #:ns{} 输入经 format 后
   同字节同 hash」即测它。"
  [s]
  (let [{:keys [model findings]} (read/read-dialect s)]
    {:formatted (some-> model write/write-dialect)
     :findings  findings}))

(defn finding?
  "x 是否为合法 finding（finding-v1 §2 封闭 schema）。"
  [x]
  (finding/valid? x))

(defn redact
  "不泄漏截断（finding-v1 §5）：任意值降为类型描述（`\"string(214 chars)\"`）。

   **导出到公共接口是有意的**：finding-v1 §5 说这条「在 B1『连接器故障的
   ex-data 不含语句/URL/行值/凭证』处复用**同一个**截断函数」。不导出，
   下游就只能各写一份——两份逐字节相同的实现今天没事，改一处忘另一处的
   那天就是泄漏。跨模块引私有 impl 与复制实现之间，正确的第三条路是把它
   摆到接口上。"
  [v]
  (finding/redact v))

(defn canonical-str
  "任意规范化 EDN 值 → 规范形文本（尾随单个 `\\n`），**并把会改变输出的
   print var 全部钉死**。

   `write-dialect` 是那条「键字典序、缺省不写、逐字节确定」的实现，但它的输出
   仍受调用线程的 `*print-namespace-maps*` 等 var 影响——REPL、测试 runner、
   future、agent 各自可能带着不同的值。不钉就等于让「同一内容」依赖调用者的
   线程状态。

   **为什么住在 dialect**：这是**唯一**一处「什么叫同一份内容」的定义
   （D2.12），而依赖它的地方一处比一处要命——content-hash（D4.3）、
   artifact-digest、以及控制面的签名输入（D3.16）。分成两份实现的那天，
   两边算出不同的字节，而症状是「签名验不过」或「hash 对不上」，
   没有人会想到去查是不是有两个 writer（CLAUDE.md：跨模块共用的东西摆到
   接口上，不要各写一份）。"
  [v]
  (binding [*print-namespace-maps* false
            *print-length*         nil
            *print-level*          nil
            *print-readably*       true
            *print-dup*            false
            *print-meta*           false]
    (write/write-dialect v)))

;; ---------------------------------------------------------------------------
;; JSON → EDN 键编码（owner 裁定 2026-09-07，doc 106 §106.14 第 9 条）
;; ---------------------------------------------------------------------------

(def fragments
  "本契约里**工具面会单独收到**的片段 schema，按名字取（`:source`、
   `:resolver-source`、`:values`、`:eql-params`…）。`coerce-json-keys` 的第一个
   参数就是这里的名字。"
  (set (keys schema/fragments)))

(defn coerce-json-keys
  "把一个 JSON 客户端送来的值里的 **map 键**改写成契约声明的拼法。

   `fragment` 是 `fragments` 里的名字，`value` 是那个位置的值。契约里
   `[:map-of :string …]` 的位置（`:headers`、`:query_params`、码值表）收字符串键，
   JSON 只送得出字符串，于是 keyword 键还原成字符串；`[:map-of 属性kw …]` 的位置
   （`:field_mapping`）反过来。**位置从 schema 推**，本函数与它的调用方都不点名
   任何一个键——契约多长一个 map-of 位置，它落地当天就被覆盖。

   只改键、不改值、不猜：只有当改写后的键通过声明的键 schema、而原键不通过时
   才改写。剩下的一律原样交给 `read-dialect` 去拒——这里不是第二道校验门。"
  [fragment value]
  (assert (contains? schema/fragments fragment)
          (str "unknown dialect fragment: " (pr-str fragment)))
  (jsonkeys/coerce (get schema/fragments fragment) value))

;; ---------------------------------------------------------------------------
;; Key face（owner 裁定 2026-09-07，doc 84 §8.4.25）
;; ---------------------------------------------------------------------------

(def object-types
  "The nine pool object types `key-face` answers for."
  (set (keys schema/entry-schemas)))

(defn key-face
  "What one object type may carry: the ordered key list of its closed schema,
   each key with `:required?`, its closed value list (`:enum`) where there is
   one, the variants that declare it (`:variants`, when not all do), and — from
   `impl/key_docs.clj` — one line of `:meaning` and one LITERAL `:example`.

   `nil` for an unknown type; the caller refuses, this does not guess.

   The one caller today is the Studio editor, which is EDN-only (owner ruling
   2026-09-05): what a type may carry can only be said inside the text, and a
   key list written in the browser would be a second statement of the closed
   key set (D1.8). Nothing here is a default — the editor renders these as EDN
   comments, which the reader drops, so an absent key stays absent (D2.12)."
  [type]
  (when-let [keys* (schema/key-face type)]
    {:type type
     :summary (get key-docs/summaries type)
     :keys (mapv (fn [{:keys [key] :as e}] (merge e (get key-docs/docs [type key])))
                 keys*)}))


;; ---------------------------------------------------------------------------
;; join 属性（D2.29「分支即属性」，owner 裁定 2026-09-02）
;; ---------------------------------------------------------------------------

(defn join-attribute?
  "属性条目是 join 属性吗（`:output` 语法树里的**分支**）。

   判别键是 `:target`（或 2.0 §6.1 的多目标 `:targets`）：join 属性有其一没有 `:type`，
   标量属性反之，两者互斥由结构层执法（dialect-v1 §3）。

   **摆在接口上而不是让每个消费端各写一次 `(contains? d :target)`**
   （CLAUDE.md）：engine 要靠它决定一个键是不是需要子查询，search 要靠它决定
   索不索 `:type`，Builder 的表单要靠它出哪一套字段。三处各判一次，就是三个
   会分叉的「什么算 join」——`redact` 与档位词表出现过两份实现的那天教过一次了。"
  [attr-def]
  (or (contains? attr-def :target) (contains? attr-def :targets)))

(defn join-attributes
  "模型 → `{join 属性 kw → 目标 namespace 字符串}`。

   `:target` 是硬门（D2.16 的运行语义要求，D2.29 之后长在属性上）：agent、
   catalog 与执法闸门都要知道这条路到达哪个 entity，而嵌套子查询没有第二个
   来源可推。"
  [model]
  (into {}
        (for [[akw attr-def] (:attributes model)
              :when (join-attribute? attr-def)]
          ;; Semantic contract 2.0 §6.1: a union join reaches several entities;
          ;; it answers the sorted vector of them, so a consumer that only ever
          ;; met a string sees a different shape, not a wrong entity.
          [akw (or (:target attr-def) (vec (sort (:targets attr-def))))])))

(defn scalar-attributes
  "模型 → `{属性 kw → 条目}`，只留标量属性。写「所有有 `:type` 的属性」的地方
   一律用它，免得各处再判一次 join（同 `join-attribute?` 的理由）。"
  [model]
  (into {}
        (remove (comp join-attribute? val))
        (:attributes model)))

;; ---------------------------------------------------------------------------
;; 反向翻译：把模型读回四个词（doc 106 §106.5，P8a）
;; ---------------------------------------------------------------------------

(def need-view
  "模型 → `{:things :fragments :concepts :findings}`：东西 · 属性 · 关系 · 概念
   （doc 106 §106.5）。纯函数，确定性。

   **摆在接口上是硬要求，不是方便**：数据面的 `list_entities` 与 Builder 的
   `inspect_graph :things` 必须共用这一份判据。两边判据一旦不同，同一张图在
   Builder 里是 5 个东西、在 MCP 里是 6 个 entity，agent 会照着不存在的那个去查
   （§106.5 逐字写着这句；D1.8「第二套语义」）。"
  need/need-view)

(def direct-output-keys
  "一条 resolver 的 `:output` → 它**直接**产出的属性键：顶层的标量叶 + 顶层的
   分支键（join 属性本身）。分支**下面**的叶子不算。

   摆在接口上是因为有两处必须用同一条判据（D1.8）：P8a 的「produced-twice」，
   与 Builder 写面判断「这条 resolver 的属性住在哪个 namespace」。后者若把分支
   下的叶子也算进去，一个东西一旦有了 §106.14 第 5 条的 `{A/b [B/k]}` 一枝，它的
   namespace 就永远算不出来，`add_attribute` 一律拒（doc 106 §106.12 第 5 条 ①）。"
  need/output-top-keys)

(def carried-by-branch
  "一条 resolver 的 `:output` 树 → 每个 join 分支**从父继承**的属性
   （doc 106 §106.14 第 13、17 条）。

   摆在接口上是因为三处必须同一份答案（D1.8）：引擎把这些键写进子行、
   SPI 接缝把它翻成「带进这个 namespace 的属性」、`need-view` 由它读回复合身份
   （第 18 条）。判「哪些属性由连接绑定」是源方言的事，留在 SPI；这里只走树。"
  carry/carried-by-branch)

(def carried-into-namespaces
  "同一次走树，从另一端读：`:output` 树 → `{namespace #{被带进这一行的属性}}`。
   引擎问「我要往这一枝里写什么」，`need-view` 问「这个东西的行里有什么」——
   第 18 条让被带进来的属性可以是身份的一部分，两个问题必须同源。"
  carry/carried-by-namespace)

(def record-identities
  "一层 `:output` 描述的那条记录的身份属性（doc 106 §106.14 第 17 条）。"
  carry/record-identities)

(def by-key-resolver-of
  "模型 + carried + namespace + 自有身份属性 → 「按它自己的键读一个」的那条
   resolver。摆在接口上是因为写面与 `need-view` 必须认同**同一条** lookup：
   第 18 条之后模板化源上那条 lookup 的 `:input` 是 `[host id]`，写面若仍按
   `[id]` 找就一条也找不到（doc 106 §106.14 第 18 条）。"
  need/by-key-resolver-of)

(def thing-namespaces
  "模型 → 读回来算「东西」的 namespace 集合。`need-view` 的同一份判定，
   只要集合的调用方用它。"
  need/thing-namespaces)

(def finding-codes
  "全部已发布 :code 的注册表（finding-v1 §6）。"
  finding/codes)

;; ---------------------------------------------------------------------------
;; 韧性 policy 的受控词表（doc 107 §107.3/§107.4，owner 2026-09-18）
;; ---------------------------------------------------------------------------

(def failure-categories
  "The controlled connector failure vocabulary. One definition, reachable from
   both directions: `spi/error-categories` aliases it, and the dialect's own
   `:retry_on` validation reads it (dependencies run spi -> dialect)."
  schema/failure-categories)

(def transient-failure-categories
  "The subset a `:retry_on` may name (D6.4). `spi/transient-categories`."
  schema/transient-failure-categories)

(def protected-statuses
  "HTTP statuses whose classification the platform keeps (doc 107 §107.5).
   `spi/protected-statuses`."
  schema/protected-statuses)

(def resilience-keys
  "The closed `:resilience` key face (doc 107 §107.3). Named here so a Builder
   form and the reader offer/accept the same set."
  #{:timeout_ms :max_retries :retry_on :backoff_ms :jitter
    :bulkhead :circuit_breaker})

(def policy-keys
  "The semantic half a graph carries on a Source slot or binding (§107.3)."
  #{:max_retries :retry_on :status-map :batch})

(def status-map-targets
  "The categories a source's `:status-map` may name (doc 107 §107.5). Named
   here so a Builder form and the reader offer/accept the same set."
  schema/status-map-targets)

;; ---------------------------------------------------------------------------
;; 受控词表（dialect-v1 §2）
;; ---------------------------------------------------------------------------

(def vocabulary
  "方言的受控词表、缺省值与 `:format` × `:type` 合法组合。

   **摆在接口上而不是让调用方各抄一份**（CLAUDE.md）：Builder 的表单要按词表
   出选项，reader 按同一份词表拒绝——两处一旦分叉，表单就会让人选出一个写不
   进去的值，而那种不一致只在用户点保存的那一刻才看得见。"
  {:attribute-types  vocab/attribute-types
   :formats          vocab/formats
   :result-types     vocab/result-types
   :mutation-result-types vocab/mutation-result-types
   :ops              vocab/ops
   :link-directions  vocab/link-directions
   :cardinalities    vocab/cardinalities
   :namespace-kinds  vocab/namespace-kinds
   :metric-grains    vocab/metric-grains
   :format->types    vocab/format->types
   :values-capable-types vocab/values-capable-types
   :defaults         vocab/defaults})
