(ns open.atlas.common.engine.impl.spi
  "连接器 SPI 的调用面。engine **只依赖 SPI 接口，不依赖任何具体连接器**
   （D1.8：common 内部也不许 engine 反向依赖某个 source type 的实现）。

   本 ns 极薄——它存在只是为了让 engine 里「调 source」这件事有一个可指认的
   位置，以及把 SPI 的调用约定（三个参数、编译产物的两种形状）写在离用它的
   代码最近的地方：

     (spi/compile-source       source-config connection resolver-config)
       => (fn [input-params eql-params] → single-map|nil | [row …])
     (spi/compile-batch-source source-config connection resolver-config)
       => (fn [inputs eql-params] → 与 inputs 等长同序的向量)
     (spi/compile-mutation     source-config connection mutation-config)
       => (fn [mutation-params] → 结果；`:result_type` 为 none 时不读结果集）

   `source-config` 是 **source 条目与操作块的合并 map**，合并由装配方做——
   engine 就是这里的装配方（见 impl.compile/compile-resolver）。
   `connection` 为 nil 表示「按 source-config 自己去开」。

   `eql-params` 是**扁平 map**：声明过的参数名 → 值，外加保留调用名
   `:limit` `:offset` `:order_by` `:sort_dir`（dialect-v1 §3）。sql-v1 的
   `:params {占位名 → 属性kw 或 eql-param 名}` 正是从 `input-params` 与这张
   扁平 map 两处取值，所以形状不能再包一层。

   **:output 的 join 整形不在 SPI 做**（SPI ns 文档明说）：连接器给的是行，
   语法树是模型的事，归 engine——见 impl.compile/shape-result。

   ## provenance 的裁量点（D5.3）

   D5.3 要求「连接器也必须区分 `:planner-derived` 与 `:caller-supplied`」，
   但落地的 SPI 六个分派点没有 provenance 槽位（arglists 是封闭的，且 engine
   不许改 connector-spi）。因此 v1 里这条**整个由 engine 在门上执法**：
   impl.gate/check-query 对每一个 caller-supplied 绑定跑
   `security/usable-as-intermediate?` 的 `:caller-supplied` 分支，不够档就拒绝，
   值根本到不了连接器。这不放松任何可观察性质——D5.4 说执法点唯一，门就是那个点；
   连接器侧的 provenance 是纵深防御，SPI 补上槽位后应在此加回。"
  (:require [open.atlas.common.connector.spi :as spi]))

(defn compile-source [source-config resolver-config]
  (spi/compile-source source-config nil resolver-config))

(defn compile-batch-source [source-config resolver-config]
  (spi/compile-batch-source source-config nil resolver-config))

(defn compile-mutation
  "mutation 操作块 → `(fn [mutation-params] → 结果)`。与读那两个同一条约定：
   `connection` 传 nil = 「按 source-config 自己去开」。"
  [source-config mutation-config]
  (spi/compile-mutation source-config nil mutation-config))

(defmacro with-request-trace
  "Run `body` with the connector SPI's request trace bound to `t` (E2).

   A macro, not a `def` alias: aliasing a dynamic var with `def` would make a
   SECOND var, and the connector reading the original would never see this
   binding."
  [t & body]
  `(binding [spi/*request-trace* ~t] ~@body))

(defn connection-bound-attributes
  "Which attributes the SOURCE ENTRY binds at query time (a templated
   `:base_url`), as a set — `#{}` for every source type without that notion.
   §106.14 item 13 reads it to carry those attributes across a join."
  [source-entry]
  (spi/connection-bound-attributes source-entry))

(defn in-record-collection-attributes
  "Which join attributes of this OPERATION carry rows assembled out of the
   parent record, as a set — `#{}` for every source type without that notion.
   §106.14 item 17 reads it to carry the parent's identity into those rows.
   Takes the merged source-config, the same shape `compile-source` takes."
  [source-config]
  (spi/in-record-collection-attributes source-config))

(defn supports-batch? [source-type]
  (spi/supports-batch? source-type))

(defn supports-offset?
  "该 source type 是否实现 `:offset`（声明式，缺省为否）。gate 拒绝一个不会
   被应用的 `:offset`，而不是把它交下去被丢掉（8.17）。"
  [source-type]
  (spi/supports-offset? source-type))

(defn known-type?
  "该 source type 有没有注册的连接器。没有 → 那条 resolver 带
   `:executable? false` 排除出编译，**不拒绝整个模型**（principles）。"
  [source-type]
  (contains? (spi/registered-types) (spi/normalize-type source-type)))

(defn registry-version
  "Opaque connector-registry version for compilation cache consistency."
  []
  (spi/registry-version))

(defn error-category
  "连接器故障的分类。故障不携带业务数据——engine 往上只放这一个 keyword。"
  [t]
  (or (spi/error-category t) :source))

(defn caller-supplied-ok?
  "调用方提供的值绑定到属性时，档位是否够。

   **档位已随 owner 裁定 2026-08-27 整套退役**：属性不再有 `:sensitivity`,
   给不给由 graph 的成员资格决定——不在这张图里的属性根本不在 catalog 里。
   于是这条判定恒真。函数留着而不是删掉，是因为它的调用点是 D5.3 曾经落地的
   那一处，留一个恒真的具名判定比让调用点忘记这里曾经有一道闸要好读。"
  [_token-tier _attr-tier]
  true)

(defn failure-limit
  "The declared bound a connector failure hit, or nil (doc 107 §107.6). The
   engine answers it with `:engine.limit/exceeded` -- the same code and fields
   the four EQL boundaries use, because to the caller it is the same refusal.

   Walks the cause chain for the same reason `connector-failure` does: Pathom
   wraps what a resolver throws, and the wrapper carries the category forward
   while the bound sits one level down. Reading only the top throwable reported
   every bounded collection as a generic execution failure."
  [^Throwable t]
  (loop [x t]
    (when x
      (or (spi/failure-limit x) (recur (.getCause x))))))

(def batch-item-failure? spi/batch-item-failure?)
(def batch-item-failure-info spi/batch-item-failure-info)
