(ns open.atlas.common.connector.derived.impl.run
  "运行期：把 `:expr` 编译成 `(fn [input-params eql-params] → map)`。

   ## 绑定语义（契约原文）

   resolver `:input` 的每个属性按 **local name** 绑定为局部符号
   （`:cis.purchase.vpo/total_cost` → `total_cost`）；**缺失输入绑 `nil`**
   （合法，表达式自行处理）。

   ## 输出

   成功给 `{输出属性kw 值}`；失败或超时给 `{}`——**该属性缺席**，不是整个实体失败
   （契约：「与『single 可 nil』同一语义」）。

   ## finding 的通道是权宜之计（README 裁定 ③）

   契约说「缺席**并记 finding**」。「缺席」有现成语义，「记 finding」**没有通道**：
   `compile-source` 的返回形状是 `(fn [input-params eql-params] → map)`，里面没有
   finding 的位置；而 `spi/fail!` 是**抛**——那会失败整个实体，正是契约禁的。

   所以 v1 送进一个**可注入的 sink**（`*finding-sink*`），缺省丢弃并按 `:warn` 记
   日志、**不含行值**。

   **到期日明确**：derived 的求值失败该在结果信封里有一个「随行事实」的槽位，那是
   B7（[§7.3](../../../../../../../../docs/design/70-consumption.md)）。曾经指望的
   先例 `:filtered-sensitive` **已随访问档位退役而删除**（D5.11 / D7.12，owner 裁定
   2026-08-28）——所以这是一个要新开的槽位，不是照抄一个现成的。在它出现之前，这个
   sink 的存在本身要写在代码里，否则「记了 finding」会变成一句没有人在听的话。"
  (:require [open.atlas.common.connector.derived.impl.sandbox :as sandbox]
            [open.atlas.common.connector.spi :as spi]))

(def ^:dynamic *finding-sink*
  "运行期 finding 的去处。缺省：丢弃 + `:warn` 日志（**不含行值**）。
   测试注入一个收集器来断言 finding 的形状与 code。"
  (fn [finding]
    (binding [*out* *err*]
      (println "WARN [connector.derived]" (:code finding) (:message finding)))))

(def default-budget-ms
  "单次求值预算。参考实现 300ms（derived-v1）；正式缺省随
   OQ-RESILIENCE-DEFAULTS 压测定。"
  300)

(defn- local-bindings
  "`input-params` → `{局部符号 值}`，按 **local name**（契约）。
   声明过的每个 input 都要在 map 里出现——**缺失绑 nil**，而不是不绑：
   不绑的话表达式里那个符号会「解析不到」，报出来是沙箱错误，而真相是这一行没数据。
   两件事的诊断完全不同。"
  [declared-inputs input-params]
  (into {} (for [a declared-inputs]
             [(symbol (name a)) (get input-params a)])))

(defn- eval-finding [code resolver-id out-attr detail]
  {:code code :severity :warning
   :path [:resolvers resolver-id out-attr]
   :message detail
   :refs ["connectors/derived-v1.md"]})

(defn compile-read
  "操作块 + resolver → `(fn [input-params eql-params] → map)`。

   `:expr` 在**这里**预解析一次（编译期），运行期只求值——契约「表达式在编译期
   预解析」。预解析失败在 `validate-source` 就该被拦下；万一走到这里（例如绕过写入端
   直接装载一份手改的制品），抛装配期故障而不是每行数据静默缺席。"
  [source-config _connection resolver-config]
  (let [{:keys [form error]} (sandbox/parse (:expr source-config))
        rid      (:id resolver-config)
        out      (first (spi/output-attributes (:output resolver-config)))
        inputs   (vec (:input resolver-config))
        budget   (or (:timeout_ms source-config) default-budget-ms)]
    (when error
      (spi/fail! :source {:resolver-id rid :source-type "derived"}
                 "derived :expr failed to pre-parse at compile time"))
    (when-let [bad (sandbox/scan form inputs)]
      (spi/fail! :source {:resolver-id rid :source-type "derived"}
                 (str "derived :expr contains a symbol outside the allowlist: " bad)))
    (when (nil? out)
      (spi/fail! :source {:resolver-id rid :source-type "derived"}
                 "derived resolver must declare exactly one :output attribute"))
    (fn [input-params eql-params]
      (let [today (or (:today eql-params) (java.time.LocalDate/now))
            r     (sandbox/evaluate form (local-bindings inputs input-params) today budget)]
        (cond
          (contains? r :value) {out (:value r)}

          (:timeout r)
          (do (*finding-sink*
               (eval-finding :connector.eval/timeout rid out
                             (str "求值超过 " budget "ms 的预算，该属性缺席"
                                  "（不影响同实体其他属性）")))
              {})

          :else
          (do (*finding-sink*
               (eval-finding :connector.eval/failed rid out
                             ;; 只带短语，**不带求值结果与输入行值**
                             (str "求值失败，该属性缺席：" (:error r))))
              {}))))))

(defn health
  "没有连接概念（契约：不写 `:source-ref`），恒 `:ok`。
   **不是「假装体检过了」**：体检问的是「这个 source 还活着吗」，而 derived 没有
   source 可以死。"
  [_source-config _connection]
  {:status :ok :latency-ms 0})
