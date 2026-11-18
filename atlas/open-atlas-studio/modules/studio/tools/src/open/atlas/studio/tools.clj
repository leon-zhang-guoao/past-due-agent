(ns open.atlas.studio.tools
  "B4.2 建模面工具的唯一对外接口。跨模块只许 require 本 ns；
   `open.atlas.studio.tools.impl.*` 视为私有（同 B0 / B4.1）。

   **二十八条 = 二十一条 Builder 专属 + 七条数据面**（8.4.18 裁定十一，
   8.4.25 裁定十七按需求语法重排写面后计数由十八升到二十）。
   裁定九（十八条）定的是「MCP 只承担 agent 建模的常用功能」；裁定十一把数据面的
   发现/查询/分析/指标工具**原封不动**挂进来，并退役 `evaluate_draft_eql`——
   同名同形不是第二种说法，而「对一张图跑 EQL」本来就不该有两份实现（D1.13）。
   那七条的声明由宿主交进来（`declarations` / `registry` 的第二参），
   因为 D1.8 禁止 studio 依赖 server。

   机制（registry → `tools/list` + dispatch、能力过滤）在
   `open.atlas.common.toolkit`：B7 的读面工具跑在 server 上，而 D1.8 禁止 server
   依赖 studio——机制若住这里，server 就只能再写一份（README 裁定 ①）。

   **不做网络传输**（裁定 ③）：到「算得出 `tools/list`、按名字 dispatch」为止。

   契约：docs/contracts/dialect-v1.md、docs/contracts/finding-v1.md。"
  (:require [clojure.edn :as edn]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.toolkit :as toolkit]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.tools.impl.declarations :as decl]
            [open.atlas.studio.tools.impl.semantic-declarations :as semantic-decl]
            [open.atlas.studio.tools.impl.finding :as finding]
            [open.atlas.studio.tools.impl.graph-declarations :as graph-decl]
            [open.atlas.studio.tools.impl.publish-declarations :as publish-decl]
            [open.atlas.studio.tools.impl.intent :as intent]
            [open.atlas.studio.tools.impl.intent-declarations :as intent-decl]
            [open.atlas.studio.tools.impl.query-model :as qm]
            [open.atlas.studio.tools.impl.repair :as repair]
            [open.atlas.studio.tools.impl.resolve-target :as rt]))

(def builder-declarations
  "**The fifty-six Builder-specific tools** (twenty-four, plus the three
   saved-query tools, `list_recent_queries` and `reclaim_version` of
   2026-10-10, the three graph-token tools and the three credential, three source, twelve
   graph-lifecycle and five access tools and list_workspace_objects of 2026-10-11), in the order of 8.4.16 裁定九's
   table minus `evaluate_draft_eql` (retired by 8.4.18 裁定十一).

   The order is fixed and is part of the contract: `tools/list` order is what
   an agent reads first (the lesson of 8.4.5), and two calls being
   byte-identical is what lets a client diff them. It runs reads first, then
   the create, then the write loop in the order the work happens in, and ends
   with the Semantic Contract and file-editing workflow (8.4.27).

   The list is assembled here from the declaration files rather than by
   concatenating them, because the ruling's order crosses those files: an
   agent does not care which namespace a tool's declaration lives in, and the
   grouping that made sense while the tools were being built is not the
   grouping that reads well at the top of a session."
  [graph-decl/list-graphs-tool          ; 1
   graph-decl/inspect-graph-tool        ; 2
   decl/query-model-tool                ; 3
   decl/describe-source-tool            ; 4
   decl/read-object-tool                ; 5
   graph-decl/create-graph-tool         ; 6
   ;; 8.4.24 裁定十八: the source is declared BEFORE anything is modelled over
   ;; it, so it sits with the other create and ahead of `add_resolver`, whose
   ;; `source` argument quotes the id this one writes.
   intent-decl/declare-source-tool      ; 7
   ;; 8.4.25 裁定十七 (doc 106 §106.6 item 1, owner 2026-09-06): the write face
   ;; is ordered by the SENTENCES of need-grammar-v1 — say the thing, then its
   ;; fields, then how things relate, then the numbers over them. `add_resolver`
   ;; is gone: it named a Pathom object, and `add_thing` / `add_relation` say
   ;; the two intents it was carrying: 18 − 1 + 3 = 20.
   intent-decl/add-thing-tool           ; 8   A1 + A2
   intent-decl/add-attribute-tool       ; 9   A2 / A3
   intent-decl/add-relation-tool        ; 10  B1 / B2 / B4
   intent-decl/add-link-tool            ; 11  B3
   intent-decl/add-metric-tool          ; 12  A4
   intent-decl/update-resolver-tool     ; 13
   intent-decl/update-attribute-tool    ; 14
   intent-decl/remove-objects-tool      ; 15
   intent-decl/describe-objects-tool    ; 16  D1 / D2
   intent-decl/define-concept-tool      ; 17  C1–C4
   intent-decl/unassign-term-tool       ; 18
   intent-decl/change-graph-members-tool ; 19
   ;; Saved queries (proposal studio-saved-queries, owner 2026-10-10): written
   ;; in the loop and published by the next tool, so they sit right before it.
   graph-decl/list-recent-queries-tool
   graph-decl/list-saved-queries-tool
   graph-decl/save-saved-query-tool
   graph-decl/delete-saved-query-tool
   publish-decl/publish-graph-tool
   publish-decl/reclaim-version-tool
   ;; Graph tokens (owner ruling 2026-10-11): what follows a publish.
   graph-decl/list-graph-tokens-tool
   graph-decl/issue-graph-token-tool
   graph-decl/revoke-graph-token-tool
   ;; Credentials (owner ruling 2026-10-11): what a declared source needs
   ;; before it can be queried.
   graph-decl/list-credentials-tool
   graph-decl/save-credential-tool
   graph-decl/remove-credential-tool
   ;; Sources (owner ruling 2026-10-11): next to declare_source's credentials.
   graph-decl/list-sources-tool
   graph-decl/probe-api-source-tool
   graph-decl/remove-source-tool
   ;; Graph lifecycle (owner ruling 2026-10-11, reversing 裁定九 for these).
   graph-decl/rename-graph-tool
   graph-decl/duplicate-graph-tool
   graph-decl/merge-graphs-tool
   graph-decl/archive-graph-tool
   graph-decl/restore-graph-tool
   graph-decl/delete-graph-tool
   graph-decl/rollback-graph-tool
   graph-decl/check-graph-cut-tool
   graph-decl/set-default-query-tool
   graph-decl/set-package-name-tool
   graph-decl/set-workspace-default-graph-tool
   graph-decl/reclaim-unused-versions-tool
   ;; Access and workspace tokens (owner ruling 2026-10-11).
   graph-decl/list-graph-access-tool
   graph-decl/grant-graph-access-tool
   graph-decl/revoke-graph-access-tool
   graph-decl/list-workspace-tokens-tool
   graph-decl/revoke-workspace-token-tool
   graph-decl/list-workspace-objects-tool
   semantic-decl/draft-tool
   semantic-decl/graph-import-tool
   semantic-decl/get-graph-tool
   semantic-decl/upload-graph-tool])

(def data-plane-tool-names
  "**The seven data-plane tools this face mounts**, in 裁定十一's order.

   They come from `modules/server/mcp` as declarations — same description,
   same handler, same refusal shape — and this module never requires that one
   (D1.8: studio and server do not depend on each other). The host is the
   assembly point and hands them to `declarations` / `registry`.

   The NAMES live here rather than only there because this module owns the
   order and the count, and because a host that hands over the wrong set must
   be refused loudly at assembly rather than quietly announced to an agent.

   `execute_mutation` is not among them: 裁定九 put writing to a source system
   back behind the GUI, and sharing a tool set is not a reason for it to come
   back (裁定十一 ①)."
  ["list_entities" "list_attributes" "search_attributes" "execute_eql"
   "analyze_result" "compute_metric" "list_mutations"])

(def ^:private data-plane-graph-param
  "The ONE difference between a data-plane call and a Builder call (裁定十一 ①):
   the data plane's caller cannot select a graph — the bearer names it — while
   here the caller says which of its workspace's graphs it means. An omitted
   value falls back to the workspace default in `call` (裁定十)."
  {:name "graph" :type :string :required? false
   :description (str "Qualified graph name, as list_graphs reports it. It must be in your "
                     "workspace. Omit it to use the workspace's default graph. The graph is "
                     "read as its DRAFT — the pool as it stands, projected exactly as cutting "
                     "a version would project it — so what answers here is what the next "
                     "published version will answer.")})

(defn- query-entity-namespaces
  "The entities an EQL request STARTS from — the keys of its `:entity` map, as
   namespaces.

   Read here because only this face has the arguments; `impl.repair` decides
   what to do with them. A query it cannot read yields nil, which means «any
   entity» rather than a refusal: a repair is a convenience and never a second
   place a query can be rejected."
  [query]
  (try
    (let [q (edn/read-string (str query))]
      (when (map? q)
        (not-empty (into #{} (keep namespace) (keys (:entity q))))))
    (catch Exception _ nil)))

(defn- mount-data-plane
  "One data-plane declaration, as this face mounts it.

   Three changes and no fourth: the `graph` parameter goes on the front of the
   parameter list, and the result is marked `:draft true` and carries the
   `:statements` the run sent. The description, the handler and the refusal
   shape are the data plane's own — an agent that learned `execute_eql` there
   has nothing to relearn, which is the whole point of 裁定十一.

   `:draft true` rides on the RESULT rather than the description because the
   agent needs it at the moment it reads rows: a draft answer is not what a
   consumer sees, and a result that does not say so gets quoted as if it were.

   `:statements` is the same kind of addition (8.4.18 裁定十二 ③). The data
   plane never returns statements and is not touched here; but 8.4.12's reason
   holds for a modeller: an empty result has three different causes — the
   statement was not sent, it was sent and matched nothing, or it was sent
   against the wrong thing — and only the statement tells them apart. The
   modeller already receives these rows, so the statement that produced them
   gives away nothing the answer did not. It is collected through
   `spi/*statement-trace*`, the channel the host binds, so this face reads
   what the run really sent rather than reconstructing it."
  [d]
  (-> d
      (update :params #(into [data-plane-graph-param] %))
      (update :handler
              (fn [h]
                (fn [ctx args]
                  (if-not (:runtime ctx)
                    ;; The host mounted the declaration but not the seam that
                    ;; gives it an environment. Refusing names the wiring;
                    ;; falling through would reach the data plane's own
                    ;; token-based default with no token in the ctx.
                    {:findings [(finding/make :tools.draft/unavailable :error [:graph]
                                              "this host does not run draft queries")]}
                    (let [seen (atom [])
                          ;; Chained, not replaced: a host that already binds
                          ;; the channel (the Builder audits every statement it
                          ;; sends) must keep receiving it — a receipt this face
                          ;; swallowed would be a hole in that record.
                          outer spi/*statement-trace*
                          r (binding [spi/*statement-trace*
                                      (fn [receipt]
                                        (swap! seen conj receipt)
                                        (when outer (outer receipt)))]
                              (h ctx args))]
                      (if (map? r)
                        (cond-> (assoc r :draft true)
                          (seq @seen) (assoc :statements @seen)
                          ;; 8.4.21 裁定十五 ①: a refusal a modeller CAN repair
                          ;; arrives with the repair prefilled. The data plane
                          ;; itself is untouched — it has no pool to infer from
                          ;; and its response stays byte for byte what it was;
                          ;; this is the Builder mount adding what only the
                          ;; Builder knows.
                          (seq (:findings r))
                          (update :findings repair/attach
                                  (repair/context ctx (:graph args)
                                                  {:entity-namespaces
                                                   (query-entity-namespaces (:query args))})))
                        r))))))))

(defn declarations
  "The 32 Builder tools in the fixed order defined by 8.4.27: the 24 of that
   ruling, the three saved-query tools and `list_recent_queries` (proposal
   studio-saved-queries, 2026-10-10), `reclaim_version` (R3.5, 2026-10-10) and
   the three graph-token tools after it (owner ruling 2026-10-11). With
   data-plane declarations, append the seven shared query tools for a total of
   39. The host supplies these declarations to preserve D1.8."
  ([] builder-declarations)
  ([data-plane]
   (let [by-name (into {} (map (juxt :name identity)) data-plane)
         missing (remove by-name data-plane-tool-names)]
     (when (seq missing)
       (throw (ex-info "the host did not supply every data-plane tool this face mounts"
                       {:missing (vec missing)})))
     (into builder-declarations
           (map (comp mount-data-plane by-name))
           data-plane-tool-names))))

(defn registry
  "能力集 → registry。没有 `:write` 时写工具**缺席**且 dispatch 不认识它
   （principles「两把钥匙」/ README 裁定 ②）。

   **两把钥匙，不是三把**（8.4.16 裁定九）：`advanced` 那一层连同它挂载的两条
   底层写面一起删了——GUI 里都有的东西，MCP 不必再留一份，而那一层本身就是
   复杂度。「列出集合 = 可调集合」因此恢复为唯一规则。

   `probe` 不进 toolkit 的能力词表：它是工具内部的判定（裁定七），不需要在
   机制层开口子。

   `data-plane` 是 `modules/server/mcp` 的 `mountable-declarations`,
   由宿主交进来（D1.8：本模块不依赖 server）。不给 = 只挂二十条 Builder 工具。"
  ([caps] (registry caps nil))
  ([caps data-plane]
   (toolkit/registry (if data-plane (declarations data-plane) (declarations))
                     (disj (set caps) :probe))))

(defn tools-list
  "registry → MCP `tools/list` 的 JSON-able 形状。"
  [reg]
  (toolkit/tools-list reg))

(defn tool-names
  "dispatch 认识的名字集合，与 `tools-list` 的名字集合恒等。"
  [reg]
  (toolkit/tool-names reg))

(def ^:private graph-is-conditional
  "Tools whose `graph` was ALREADY optional before 裁定十, and optional in a
   different sense: `describe_objects` needs one only when an entry registers
   a term (a bound term outside the graph's visibility fails the cut), and
   describing an existing attribute names no graph at all.

   The default still fills it in — an omitted `graph` there means the default
   graph like everywhere else — but its ABSENCE stays legal: refusing every
   `describe_objects` on a workspace with no default graph would take away a
   call that works today, and 裁定十 is about what an omitted argument falls
   back to, not about making a conditional argument mandatory.

   One name, and it earns its place by naming the reason. A second entry
   without one is this list turning into the hand-kept table `takes-graph?`
   exists to avoid."
  #{"describe_objects"})

(defn- takes-graph?
  "Does this tool, as mounted on `reg`, declare a `graph` parameter?

   Read off the declarations rather than from a hand-kept list of tool names,
   so a tool that gains or loses the parameter needs no edit here (the same
   reason the host coerces arguments declaration-driven). The registry is
   consulted too: a tool the capability set left unmounted must keep answering
   «unknown tool», not a refusal about a graph it never got the chance to
   want."
  [reg tool-name]
  (and (contains? (toolkit/tool-names reg) tool-name)
       (not (contains? graph-is-conditional tool-name))
       (boolean (or (some #(= tool-name %) data-plane-tool-names)
                    (some (fn [d]
                            (and (= tool-name (:name d))
                                 (some #(= "graph" (:name %)) (:params d))))
                          builder-declarations)))))

(defn call
  "registry × ctx × 工具名 × 入参 → 结果或 `{:findings […]}`。



   ctx：`{:conn 池连接
          :db   可选，同一次读数（不给就现取）
          :provenance {:entrypoint :mcp :token-id … :actor … :at …}  ; 8.4 P1b
          :graph-service {…}                                    ; 8.4 P2，宿主注入
          :publish-service {…}                                  ; 8.4 P3，宿主注入
          :abbreviations {:global {…} :by-namespace {ns {…}}}   ; 默认空（裁定 ⑤）
          :default-graph 默认图的名字，可选                      ; 8.4.17 裁定十，宿主解析
          :max-candidates 可选，写目标闸门的信封上限}`

   **`:provenance` 由宿主构造，不来自 `args`**（pool audit 裁定 ①）：写工具原样
   把它交给池，而客户端在 JSON-RPC 那一侧碰不到 ctx。这就是「伪造身份寻址不到」
   在工具面的落点——工具声明里根本没有那几个字段可填。

   **`graph` 的缺省在这里补齐，只此一处**（8.4.17 裁定十）：ctx 的
   `:default-graph` 是宿主按 D3.21 解析出来的 workspace 默认图**名字**。
   分派前补，handler 因此仍旧假定 `graph` 在场——十三个 handler 各判一次
   缺省，就是十三份会各自漂移的同一条规则。"
  [reg ctx tool-name args]
  (if (takes-graph? reg tool-name)
    (let [resolved (or (:graph args) (:default-graph ctx))]
      (if (nil? resolved)
        ;; Neither given nor set. It is NOT resolved by picking «the only
        ;; graph»: a workspace with one graph today has two tomorrow, and
        ;; D3.21's default graph is the setting that exists for this
        ;; question. Nothing is dispatched, so nothing is written.
        {:findings [(finding/make :tools/graph-required :error [:graph]
                                  "no graph given and this workspace has no default graph"
                                  :expected "set a workspace default graph, or pass graph")]}
        ;; Echo the graph actually used — the same discipline as 「词进、id 出」:
        ;; an agent that omitted the argument has to be told where its work
        ;; landed, and reading it back from the result is the only way.
        (let [result (toolkit/dispatch reg ctx tool-name (assoc args :graph resolved))]
          (if (and (map? result) (not (contains? result :graph)))
            (assoc result :graph resolved)
            result))))
    ;; Not graph-gated, but the default still fills a `graph` the tool
    ;; declares and the caller omitted (`describe_objects`, above).
    (let [resolved (or (:graph args) (:default-graph ctx))]
      (toolkit/dispatch reg ctx tool-name
                        (if (and resolved
                                 (contains? graph-is-conditional tool-name)
                                 (not (contains? args :graph)))
                          (assoc args :graph resolved)
                          args)))))

(def projected-kinds
  "`inspect_graph` 的九个组与顺序（8.4.10）——scope 投影的 `:model` 能出现的
   顶层键。

   **摆在接口上是因为宿主要读它**：投影的塑形（counts / ids / entries）住宿主
   的 graph service，而枚举住声明；两处各写一份九个词的那天，客户端读到的枚举
   与服务数得出的组就是两套词表。"
  graph-decl/projected-kinds)

(def query-names
  "`query_model` 的具名查询清单与顺序（八条，覆盖 README 那张表的七行）。"
  qm/queries)

(def fill-in
  "`query_model` 的 action 骨架里「待人补内容」的标记。**是个非法值**：
   照原样跑会被 B0 结构化拒绝，比静默地把该补的键删掉便宜得多（见 impl 注释）。"
  qm/fill-in)

(defn infer-data-path
  "A probe sample to the JSONPath of its record array, or nil. The API resolver
   editor and its server planner share this heuristic so preview and apply
   cannot choose different rows."
  [sample]
  (intent/infer-data-path sample))

(defn probe-audit
  "The one audit record a probe writes (8.4.14 裁定七): source, path, parameter
   NAMES, status, response size, field count, and which data path the rows
   were read from. No response body, ever.

   Exposed so the Builder's own probe route files the same record the MCP path
   returns, rather than a second one shaped differently."
  [request result field-count]
  (intent/probe-audit request result field-count))

(def verdicts
  "写目标闸门的四个离散裁决（`resolve_target` 的 MCP 声明在 8.4.16 裁定九删除,
   判定本身仍是意图工具解析名字的那一步）。只有 `:exact-unique` 带 `:target` 与
   `:action-params`（principles：安全只能是参数的缺席）。"
  rt/verdicts)

(defn finding?
  "x 是否为合法 finding。本模块的拒绝里有四种 code 域：`toolkit.*`（名字与
   入参形状）、`tools.*`（本层独有的判定）、`pool.*` 与 `dialect.*`（下游）。
   一律不含配置值（B4 退出条件 2 / finding-v1 §5）。"
  [x]
  (or (finding/valid? x) (toolkit/finding? x) (pool/finding? x) (dialect/finding? x)))

(def finding-codes
  "全部已发布 `tools.*` code 的注册表（finding-v1 §6）。"
  finding/codes)
