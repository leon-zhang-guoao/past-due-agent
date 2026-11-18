(ns open.atlas.common.connector.derived.impl.validate
  "写入端校验 —— derived 连接器**唯一真正的闸门**。

   derived 没有连接（契约：不写 `:source-ref`），所以 `health-check` 无意义。
   闸全在这里，它挡的是一件具体的事：**发布了一个永远算不出来的属性**。

   四条，逐条对契约：
   - 操作块键面封闭 `#{:type :expr}`——契约「未知操作键 `:reject`」；
   - `:expr` 必填且非空字符串——契约「必；非空字符串」；
   - **预解析**——契约「表达式在编译期预解析，解析失败即校验失败」；
   - **禁止形式扫描**——白名单挡不住 SCI 的特殊形式（见 impl.sandbox 抬头的实测表）,
     而 `eval` / `atom` / `loop` 正在其中。这一条契约没有明写，但它是「显式拒绝 I/O、
     反射、`eval`、命名空间操作」这句话真正生效所必需的。"
  (:require [clojure.string :as str]
            [open.atlas.common.connector.derived.impl.sandbox :as sandbox]
            [open.atlas.common.connector.spi :as spi]))

(def ^:private op-keys
  "操作块的封闭键面（契约那张表恰好两行）。"
  #{:type :expr})

(defn- finding [code path message & {:as extra}]
  (merge {:code code :severity :error :path (vec path) :message message
          :refs ["connectors/derived-v1.md"]}
         extra))

(defn validate-source
  "→ `{:findings [finding …]}`。有 `:error` 即不得发布（SPI 契约）。

   **两种强度，因为 SPI 的 `validate-source` 签名只有 `[source-config]`**——它看不到
   resolver，于是看不到 `:input`，也就做不了完整的 allowlist（每个 input local name
   在它眼里都是「不被允许的符号」）：

   - 1-arity（SPI 那一路）：形状 + 预解析 + **弱扫描**（只拒「一定不可能是 local
     name」的符号：带命名空间限定、`.` 开头或结尾）；
   - 2-arity（拿得到 resolver 的那一路）：**完整 allowlist**。

   **别把 1-arity 当完整的闸**：`slurp` 在它眼里长得像一个 input local name，过得去。
   完整的闸是 2-arity 与装配期的 `compile-read`——那两处都有 inputs。这条分界写在这里,
   因为「写入端校验过了」是最容易产生的错觉。"
  ([source-config] (validate-source source-config nil))
  ([source-config inputs]
  (let [expr    (:expr source-config)
        unknown (vec (sort (map str (remove op-keys (keys source-config)))))]
    {:findings
     (cond-> []
       (seq unknown)
       (conj (finding :connector.source/unknown-key [:source]
                      (str "derived 的操作块键面封闭，只有 :type 与 :expr；"
                           "多出来的键一律拒绝（support policy :reject）")
                      :expected (vec (sort (map str op-keys)))
                      :got unknown))

       (not (contains? source-config :expr))
       (conj (finding :connector.source/missing-key [:source :expr]
                      ":expr 必填——没有它这条 resolver 永远算不出任何东西"))

       (and (contains? source-config :expr) (not (string? expr)))
       (conj (finding :connector.source/invalid-shape [:source :expr]
                      ":expr 必须是字符串"
                      :got (str (type expr))))

       (and (string? expr) (str/blank? expr))
       (conj (finding :connector.source/invalid-shape [:source :expr]
                      ":expr 不得为空——空表达式与「忘了写」在结果上没有区别"))

       :always
       (into
        (when (and (string? expr) (not (str/blank? expr)))
          (let [{:keys [form error]} (sandbox/parse expr)]
            (cond
              error
              ;; **`:expr` 文本进 finding 是有意的**：它是**配置**不是数据
              ;; （同 sql-v1 允许点名键名的道理）。求值结果与输入行值一律不进——
              ;; principles「source 失败与日志不携带业务数据」。别把这一条当漏洞修掉。
              [(finding :connector.source/expr-unparsable [:source :expr]
                        (str "预解析失败：" error)
                        :got expr)]

              (if inputs (sandbox/scan form inputs) (sandbox/scan-without-inputs form))
              [(finding :connector.source/expr-unparsable [:source :expr]
                        (str "表达式里有不被允许的符号 "
                             (if inputs (sandbox/scan form inputs)
                                        (sandbox/scan-without-inputs form))
                             "——allowlist：允许集合之外一律拒绝。SCI 默认就带一份"
                             " clojure.core 子集，所以黑名单管不住；而无界迭代尤其必须"
                             "在发布闸上拦，因为超时停不下一个 recur 循环")
                        :got (str (if inputs (sandbox/scan form inputs)
                                              (sandbox/scan-without-inputs form))))]

              :else nil)))))})))

(defn validate-mutation
  "derived 的能力表：mutation ✗。**结构化拒绝，不是静默不支持**——
   静默不支持会让写入端接受一个永远跑不起来的 mutation。"
  [_source-config]
  {:findings
   [(finding :connector.source/mutation-unsupported [:source :type]
             "derived 连接器不支持 mutation（derived-v1 能力表：mutation ✗）")]})

(defn batch-unsupported!
  "batch ✗。抛而不是返 finding：`compile-batch-source` 的契约是返回一个函数,
   没有 finding 的位置；而这是**装配期**的错，不是运行期的数据问题。"
  [resolver-config]
  (spi/fail! :source
             {:resolver-id (:id resolver-config) :source-type "derived"}
             "derived connector does not support batch (derived-v1: batch ✗)"))
