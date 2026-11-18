(ns open.atlas.studio.version.impl.finding
  "本模块的 finding 注册表与构造器（[finding-v1](../../../../../../../../docs/contracts/finding-v1.md)）。

   形状照抄 finding-v1 §2（封闭键面、severity 三档、`:path`、不泄漏），只有 `:code`
   的域不同：§3「后续模块续接」——B5b 续的是 `cut.*`（切一个版本时的判定）与
   `version.*`（版本登记本身）。

   **为什么自带注册表**：`dialect/finding-codes` 是 B0 的常量，只登记 `dialect.*`；
   让 studio 去改内核的注册表就把 D1.8 的依赖方向反过来了（B2 的 `import.*`、
   B4 的 `pool.*`、B5a 的 `graph.*` 都走的这一条）。

   **`valid?` 认四张表的并集**，而不是只认自己这一张：切版本的拒绝有一半是
   **回落**来的——D2.8 的分页与 `:allowed` 子集由 `dialect/read-dialect` 判
   （硬要求「不写第二个 schema」），投影的拒绝由 `graph/project` 判，密封的
   拒绝由 `artifact` 判。把它们重贴一层 `cut.*` 的皮，等于给同一条已发布的拒绝
   造第二个 code，下游按 code 分流就会漏掉一半。

   **截断复用内核导出的 `dialect/redact`**（finding-v1 §5 明说同一个截断函数）。"
  (:require [open.atlas.common.artifact :as artifact]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.graph :as graph]))

(def codes
  "已发布 `:code` 注册表（finding-v1 §6：产出未注册 code 是实现 bug）。

   **这里没有任何一条属于质量指标**——D2.11 把「拒绝」与「展示」分成两栏，
   质量指标那一栏只展示不拒绝（README 裁定 ③）。若哪天覆盖率出现在这张表上，
   就说明有人把展示接回了拒绝通道。"
  #{;; ── cut.closure —— 闭包解析（R3.1 / R3.4 / R3.8）──────────────────
    :cut.closure/malformed            ; 引用形状不对（不是 {:graph :version …}）
    :cut.closure/unpinned-version     ; R3.1：版本不是具体版本号（latest / 空 / 非法）
    :cut.closure/version-conflict     ; R3.4：钻石冲突，点名两个版本与两条路径
    :cut.closure/reference-missing    ; 传递闭包里的版本没有被交进来
    :cut.closure/cycle                ; 引用成环——(graph, 版本) 上本该是 DAG（R3.3）
    ;; ── cut.structure —— D2.11 的结构那一格 ───────────────────────────
    :cut.structure/dangling-input     ; resolver/mutation 的 input 不在属性全集里
    :cut.structure/broken-link        ; link 的一端不在属性全集里
    :cut.structure/multiple-producers ; 同一属性有两个产出者（判据见 impl.health）
    :cut.structure/incomplete-root-collection ; 复合键东西的根集合没产出全部键（§106.14 第 14 条）
    :cut.structure/empty-recipe       ; 菜谱一个对象都没点名（doc 106 §106.12 13.3）
    ;; ── cut.resolver —— D2.13 具名查询必须签字 ────────────────────────
    :cut.resolver/unsigned-named-query ; 无锚 list 没写 `:curated? true`
    ;; ── cut.attribute —— D2.2 属性单一声明；漂移在切版本时拒绝 ─────────
    :cut.attribute/undeclared         ; 存在性闭包里有、`:attributes` 注册表里无
    :cut.attribute/unregistered-use   ; 注册表里有、任何 `:output`/mutation 键面里无
    :cut.attribute/metadata-missing   ; 注册表条目缺必填元数据（`:type` 是唯一类型事实源）
    ;; ── cut.resilience —— E6.2 韧性（拒绝）────────────────────────────
    :cut.resilience/timeout-not-enforceable      ; 超时 enforce 不了 → 不允许发布
    :cut.resilience/mutation-idempotency-unknown ; 含 retry 而幂等性未知（D6.4）
    ;; ── cut.version —— 切出即不可变 ───────────────────────────────────
    :cut.version/exists               ; 这个版本号已经切过
    :cut.version/malformed            ; 切版本请求的形状
    ;; ── rowsec.wiring —— E1 / D5.14 声明与接线核对（owner 2026-09-03）─────
    ;; 域名是 `rowsec.*` 而不是 `cut.*`：门禁（runtime/rowsec）已经用这个域，
    ;; 而下游按 code 分流时「行级安全的事」应当是一处，不是切版本一处、发牌
    ;; 一处。三条都是 `:error`——D5.14 的失败方向是拒绝切版本。
    :rowsec.wiring/not-an-input       ; 声明点名的属性不在该 resolver 的 `:input` 里
    :rowsec.wiring/no-connector       ; 本机没有该 source type 的连接器 → 装配缺口，不是模型问题
    :rowsec.wiring/not-bound          ; 是 `:input` 但语句没绑它 → 每个调用方读全表
    :rowsec.wiring/unverifiable       ; 连接器答不出自己绑了哪些名字 → 不假设已绑
    ;; ── package —— 图包的密封前置（doc 104 §8.17.7 A）─────────────────
    ;; 域名是 `package.*`：同一条拒绝在 server 的导入 plan 里已经用这个 code
    ;; （`console/ops` 的 `compat-findings`），出厂端与安装端说同一个名字。
    :package/unknown-connector        ; source 的 type 本机没有连接器 → :compat 会写 nil，密封即少声明
    ;; ── version.rename —— D3.10 的另一半 ──────────────────────────────
    :version.rename/already-cut       ; 切过版本之后改名 = 发布一个新东西，不是重命名
    :version.rename/malformed})

(def engine-compile-codes
  "**引擎的编译类 code，本模块原样携带**（8.4.19 裁定十三 ②，owner 2026-09-04）。

   编不出来的 resolver 在草稿里像存在、在查询里不存在，引擎只发一条 warning
   就把它丢掉。裁定要求体检把这类判定当 **error**：一条编不出来的 resolver
   不是更小的图，是一张会在查询时断路的图（与 scope-projection「半张表不是
   更小的表」同则）。

   **不新起 `cut.*` 的 code**：同一个缺陷在 `inspect_graph` 的 `:compile`、
   发布体检与 `add_resolver` 的 plan 三处出现，三个名字就是三套词表
   （finding-v1 §6 各模块登记自己的域，本条是**回落**——与 ns docstring 里
   dialect / artifact / graph 三个来源同理）。本模块不 require engine：判据是
   **形状**（list resolver 有没有单一 join 根、source-ref 指不指得着、
   type 有没有连接器），编译本身要凭据，体检没有也不该有。"
  #{:engine.compile/list-without-anchor
    :engine.compile/source-unresolved
    :engine.compile/source-not-executable})

(def ^:private known
  "`valid?` 认的全部 code：本模块 + 三个**回落**来源。见 ns docstring。"
  (into codes (concat engine-compile-codes
                      dialect/finding-codes artifact/finding-codes graph/finding-codes)))

(defn make
  "构造并断言（finding-v1 §2 封闭键面）。opts 只收 `:expected :got :refs :files`。
   **未注册 code 直接 assert 失败**——finding-v1 §6 说它是实现 bug，那就让它像
   bug 一样炸，而不是安静地发布一条没人认识的拒绝。"
  [code severity path message & {:as opts}]
  (assert (or (contains? codes code) (contains? engine-compile-codes code))
          (str "unregistered finding code: " code))
  (assert (contains? #{:error :warning :info} severity))
  (assert (vector? path))
  (assert (string? message))
  (assert (every? #{:expected :got :refs :files} (keys opts))
          (str "finding key set is closed: " (pr-str (keys opts))))
  (merge {:code code :severity severity :path path :message message} opts))

(defn valid? [f]
  (and (map? f)
       (empty? (remove #{:code :severity :path :message :expected :got :refs :files} (keys f)))
       (contains? known (:code f))
       (contains? #{:error :warning :info} (:severity f))
       (vector? (:path f))
       (string? (:message f))
       (or (nil? (:refs f)) (and (vector? (:refs f)) (every? string? (:refs f))))
       (or (nil? (:files f)) (and (vector? (:files f)) (every? string? (:files f))))))

(def redact
  "不泄漏截断（finding-v1 §5）—— B0 导出的那一个函数，不另写。"
  dialect/redact)

(defn sort-findings
  "确定性顺序（finding-v1 §6）：按 `:path` 字典序、同 path 按 `:code`。
   **再按 `:message` 兜底**：密封时 `normalize-health` 只按前两项排序且
   `sort-by` 是稳定排序，同 path 同 code 的并列项保持入参顺序——那份确定性
   由本函数负责（seal.clj 的 `normalize-health` 注释点名了这个交接）。"
  [findings]
  (vec (sort-by (juxt (comp pr-str :path) (comp str :code) :message) findings)))

(defn errors? [findings] (boolean (some #(= :error (:severity %)) findings)))
