(ns open.atlas.common.connector.derived
  "derived 连接器 —— docs/contracts/connectors/derived-v1.md 的实现。

   跨模块只许 require 本 ns；`.impl.*` 私有。require 本 ns 即完成注册：
   六个 SPI 分派点的 `\"derived\"` 方法在这里挂上去，没有别的装配步骤
   （与 connector-sql 同则——宿主决定加载哪些连接器，就是决定 require 哪些 ns）。

   ## 为什么它现在才出现

   `\"derived\"` 在 D2.15 的 v1 注册表里**写着，而一行代码都没有**——写入端会接受
   `:type \"derived\"`（契约已注册），装载时才发现没有实现。这是这个仓库最忌讳的
   「写了却没生效」，而且比一般的失效更隐蔽。D2.25 把组的「一次问全」交给 derived
   之后，它从「早该补」变成「挡在路上」。

   ## 契约要点与落点

   - 操作块封闭 `#{:type :expr}`、`:expr` 必填非空 ……… impl.validate
   - **预解析**，解析失败即校验失败 ……………………… impl.sandbox/parse + impl.validate
   - **allowlist 扫描**（SCI 默认带 core 子集，黑名单管不住）… impl.sandbox/scan
   - 白名单封闭，扩项即契约升版 ……………………………… impl.sandbox/whitelist
   - `:input` 按 local name 绑定，缺失绑 nil ………… impl.run/local-bindings
   - 求值失败/超时 → 属性缺席 + finding，不失败整个实体 … impl.run/compile-read
   - batch ✗ / mutation ✗，**结构化拒绝** ……………… impl.validate
   - 没有连接，health 恒 :ok ………………………………… impl.run/health"
  (:require [open.atlas.common.connector.derived.impl.run :as run]
            [open.atlas.common.connector.derived.impl.sandbox :as sandbox]
            [open.atlas.common.connector.derived.impl.validate :as validate]
            [open.atlas.common.connector.spi :as spi]))

(def source-type "derived")

(defn binding-operation [binding]
  {:type source-type :expr (get-in binding [:binding/implementation :expr])})

(defn- finding
  "A binding finding (finding-v1): path relative to the binding, message states
   the expectation and never echoes a value."
  [code path message]
  {:code code :severity :error :path path :message message})

(defn- slot-findings
  "derived-v1 (owner ruling 2026-09-18, #3): input-map / output-map values are
   slot names and must equal the attribute's local name — the `:expr` binds
   inputs by local symbol, so the slot is that symbol."
  [root m]
  (if-not (map? m)
    [(finding :connector.binding/shape [root] "Expected a map of attribute to slot name")]
    (for [[a n] m :when (not (and (qualified-keyword? a) (= (name a) n)))]
      (finding :connector.binding/slot-mismatch [root a]
               "Slot name must equal the attribute's local name"))))

(defn validate-binding
  "Full semantic Binding check with declared input names; never use the weak
   one-arity scan for publication. Derived has no connection and cannot write.

   Returns `{:findings [...]}` with one code and precise path per clause (owner
   ruling 2026-09-18); all clause findings are collected. The underlying
   derived-v1 `validate-source` findings are kept minus `:got`. A crash here
   is a platform defect and propagates to the caller."
  [binding]
  (let [implementation (:binding/implementation binding)
        inputs (:binding/input-map binding)
        outputs (:binding/output-map binding)
        structural
        (concat
         (when-not (= :read (:binding/effect binding))
           [(finding :connector.binding/effect [:binding/effect]
                     "A derived binding is read-only (derived-v1: mutation ✗)")])
         (if-not (map? implementation)
           [(finding :connector.binding/shape [:binding/implementation] "Expected the implementation to be a map")]
           (concat
            (for [k (keys implementation) :when (not= :expr k)]
              (finding :connector.binding/unknown-key [:binding/implementation k]
                       "derived-v1 binding implementation accepts :expr only"))
            (when-not (contains? implementation :expr)
              [(finding :connector.binding/missing-key [:binding/implementation :expr] ":expr is required")])))
         (slot-findings :binding/input-map inputs)
         (slot-findings :binding/output-map outputs)
         (when (and (map? outputs) (not= 1 (count outputs)))
           [(finding :connector.binding/output-count [:binding/output-map]
                     "A derived binding produces exactly one attribute")])
         (when (map? inputs)
           (let [counts (frequencies (map name (filter keyword? (keys inputs))))]
             (for [a (keys inputs) :when (and (keyword? a) (> (get counts (name a) 0) 1))]
               (finding :connector.binding/duplicate-physical-name [:binding/input-map a]
                        "Two inputs cannot share one local name"))))
         ;; Semantic contract 2.0 §6.3: a derived expression yields one scalar,
         ;; never a record of several shapes nor a tree.
         (for [a (sort (:binding/output-union binding))]
           (finding :connector.binding/union-output-unsupported [:binding/output-map a]
                    "A derived binding cannot produce a union output: an expression yields one scalar"))
         (for [a (sort (:binding/output-recursion binding))]
           (finding :connector.binding/recursion-output-unsupported [:binding/output-map a]
                    "A derived binding cannot produce a recursive output: an expression yields one scalar")))]
    (if (seq structural)
      {:findings (vec structural)}
      {:findings (mapv #(select-keys % [:code :severity :path :message])
                       (:findings (validate/validate-source (binding-operation binding) (vec (keys inputs)))))})))

(defmethod spi/compile-source source-type
  [source-config connection resolver-config]
  (run/compile-read source-config connection resolver-config))

(defmethod spi/compile-batch-source source-type
  [_source-config _connection resolver-config]
  (validate/batch-unsupported! resolver-config))

(defmethod spi/bound-attributes source-type
  [_source-config]
  ;; E1 / D5.14. A derived source runs an `:expr` over values other resolvers
  ;; already produced; it queries nothing and narrows nothing, so it binds no
  ;; attribute. **`#{}`, not `nil`** — this is "the answer is none", not "I
  ;; cannot tell", and the difference matters: the caller turns `#{}` into
  ;; `:rowsec.wiring/not-bound`, which tells the author the true thing (row
  ;; security has nowhere to land on a derived source), where
  ;; `:rowsec.wiring/unverifiable` would send them to fix a connector.
  #{})

(defmethod spi/validate-source source-type
  [source-config]
  (validate/validate-source source-config))

(defmethod spi/health-check source-type
  [source-config connection]
  (run/health source-config connection))

(defmethod spi/compile-mutation source-type
  [_source-config _connection mutation-config]
  (spi/fail! :source
             {:resolver-id (:id mutation-config) :source-type source-type}
             "derived connector does not support mutation (derived-v1: mutation ✗)"))

(defmethod spi/validate-mutation source-type
  [source-config]
  (validate/validate-mutation source-config))

;; ---------------------------------------------------------------------------
;; 给宿主与测试的把手
;; ---------------------------------------------------------------------------

(def whitelist
  "沙箱白名单的符号集合。测试拿它与 derived-v1 那张表逐字对账——
   挡的是「顺手多给一个」，而多给一个在契约里是**升版**级别的改动。"
  sandbox/whitelist)

(def contract-symbols
  "derived-v1 那张表逐字点名的符号（不含 clojure.core 子集）。"
  sandbox/contract-symbols)

(def permitted-forms
  "允许的特殊形式与宏（`if` `cond` `let` `->` 之类）。**封闭**，判据是**有界**——
   `loop`/`recur`、`iterate`/`range` 一类不在其中，见 impl.sandbox。"
  sandbox/permitted-forms)

(def scan
  "语法树 × inputs → 第一个不被允许的符号（allowlist）。测试直接拿它对账。"
  sandbox/scan)

(defn validate-source-with-inputs
  "**完整的闸**：形状 + 预解析 + allowlist（带 resolver 的 `:input`）。

   为什么不能只用 SPI 的 `validate-source`：那个签名是 `[source-config]`,
   **看不到 resolver**，于是看不到 inputs——而每个 input local name 在没有 inputs 的
   眼里都是「不被允许的符号」。所以 1-arity 只做弱扫描（拒「一定不可能是 local name」
   的符号），完整 allowlist 走这一路。

   **拿得到 resolver 的校验路径都应当调这个**（切版本体检、池的写面）。
   只调 1-arity 会让 `slurp` 过得去——它长得像一个 input local name。"
  [source-config resolver-config]
  (validate/validate-source source-config (:input resolver-config)))

(defn with-finding-sink
  "把运行期求值 finding 收进 `sink`，跑 `f`。

   **这是权宜之计**（见 impl.run 抬头）：结果信封该有一个「随行事实」的槽位
   承载它，那是 B7。"
  [sink f]
  (binding [run/*finding-sink* sink] (f)))

(defmethod spi/semantic-adapter "derived" [_]
  ;; Checker protocol (owner ruling 2026-09-18): `:validate` answers the
  ;; connector's `{:findings [...]}` directly; the execution layer prefixes
  ;; each path with `[:bindings bid]`. Empty findings = valid.
  {:validate validate-binding :lower binding-operation})
