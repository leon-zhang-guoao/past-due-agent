(ns open.atlas.common.toolkit
  "工具面的**机制**（B4.2）：一份 registry 同时产出 `tools/list` 与 dispatch。
   跨模块只许 require 本 ns；`open.atlas.common.toolkit.impl.*` 视为私有
   （同 B0 的 open.atlas.common.dialect）。

   **为什么在 common 而不是 studio**（studio/tools README 裁定 ①）：B7 的读面工具
   跑在 server 上，而 D3.8 的 server 是「版本架」、D1.8 禁止它依赖 studio。机制若
   住在 studio，server 就只能再写一份——而「在一处声明、在另一处分派会漂移」正是
   这条规则要防的失败，写两份是它的加强版。

   **本 ns 不做网络传输**（裁定 ③）：交付到「算得出 `tools/list` 的 JSON 形状、
   能按名字 dispatch」为止，stdio / HTTP 挂载归 Builder MCP 的 transport 模块
   （docs/design/84-builder-mcp.md）。

   契约：docs/contracts/finding-v1.md（所有拒绝的形状）。"
  (:require [open.atlas.common.toolkit.impl.finding :as finding]
            [open.atlas.common.toolkit.impl.registry :as registry]))

(def capabilities
  "两把钥匙的词表（principles「MCP 与工具面」）：`#{:read :write}`。"
  registry/capabilities)

(defn registry
  "[工具声明…] × 能力集 → registry。

   一条声明的形状（键面封闭）：

       {:name        \"query_model\"
        :description \"…给客户端看的一句话…\"
        :capability  :read                     ; :read | :write
        :params [{:name \"query\" :type :keyword :required? false
                  :description \"…\" :enum [:attribute/missing-description …]}]
        :handler (fn [ctx args] …)}            ; args 的键是 keyword

   **能力过滤只在这里发生一次**：没有 `:write` 时，写工具在 `tools-list` 里缺席
   且 `dispatch` 不认识它（不是「在但永远拒绝」）。声明本身的错误（重名、缺
   handler、能力不认识）是实现 bug，构造时 assert 失败。"
  [decls caps]
  (registry/registry decls caps))

(defn tools-list
  "registry → MCP `tools/list` 的 JSON-able 向量，顺序即声明顺序（可 diff）。"
  [reg]
  (registry/tools-list reg))

(defn tool-names
  "dispatch 认识的名字集合。与 `tools-list` 的名字集合**恒等**：两者同取
   registry 的一个 map，这是本模块的核心不变量。"
  [reg]
  (registry/tool-names reg))

(defn dispatch
  "registry × ctx × 工具名 × 入参 map → handler 返回值，或 `{:findings […]}`。
   未知名字与入参不合 schema 一律 finding-v1 结构化拒绝，且**不含配置值**。"
  [reg ctx tool-name args]
  (registry/dispatch reg ctx tool-name args))

(defn refusal?
  "这个结果是**拒绝**吗——带 `:error` 严重度的 finding 即是（8.4 P4）。

   为什么这条判据住这里：dispatch 与工具 handler 说的拒绝是 finding-v1
   （`{:findings [… :severity :error …]}`），而 MCP 的工具结果说的是
   `{:error …}`。两种词汇必须在某处对上，而**在哪里对上必须只有一处**——
   两个端点各写一份的那天，其中一份会把拒绝当成功送给 agent（这正是
   Builder 端点第一次接线时发生的事：每一次拒绝都以成功送达）。

   **只带 warning 的结果仍是成功**：池里既有的脏随一次已提交的写报出
   （studio/graph 裁定 ⑤），把它报成失败会教 agent 重试一个已经落地的写。"
  [result]
  (boolean (and (map? result)
                (some #(= :error (:severity %)) (:findings result)))))

(defn as-tool-result
  "dispatch 的返回值 → MCP 工具结果的形状。拒绝装进 `{:error …}`,
   **finding 整条带走**——「哪个位置错了」是调用方唯一能据以改下一次调用的东西。"
  [result]
  (if (refusal? result) {:error result} result))

(defn finding?
  "x 是否为合法 `toolkit.*` finding（finding-v1 §2 封闭 schema）。"
  [x]
  (finding/valid? x))

(def finding-codes
  "全部已发布 `toolkit.*` code 的注册表（finding-v1 §6）。"
  finding/codes)
