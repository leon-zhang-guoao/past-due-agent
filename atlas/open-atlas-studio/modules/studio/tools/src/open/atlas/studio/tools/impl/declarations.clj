(ns open.atlas.studio.tools.impl.declarations
  "建模面工具的**声明**（§7.3 那张表的建模面几行）。

   机制在 `open.atlas.common.toolkit`；这里只把「名字 / 描述 / 入参 / 能力 /
   handler」摆出来。一份声明同时产出 `tools/list` 与 dispatch——本 ns 里写下的
   `:capability` 决定它在没有对应钥匙时是否**存在**（裁定 ②）。

   **handler 不重写任何逻辑**：`query_model` 的判定读的是 `pool/as-dialect`
   与 `pool/objects` 给的形状；`describe_source` 只调用 connector schema 读取。

   **可信出处从 ctx 来，不从入参来**（8.4 P1b / pool audit 裁定 ①）：写工具把
   `(:provenance ctx)` 原样交给池，而 ctx 是**宿主装配 registry 时构造的**，
   JSON-RPC 那一侧递不进来。入参里因此没有 `entrypoint` / `token_id` /
   `actor` 这三个字段——不是「有但会被忽略」，是**声明里没有**，于是
   `tools/list` 也不会把它们告诉客户端去试。"
  (:require [open.atlas.common.connector.spi :as spi]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.tools.impl.finding :as f]
            [open.atlas.studio.tools.impl.intent :as intent]
            [open.atlas.studio.tools.impl.query-model :as qm]))

(defn- db-of
  "ctx 给 `:db` 就用它（同一次读数上跑多条查询，读到的是同一个 revision）；
   只给 `:conn` 就现取一个。"
  [{:keys [db conn]}]
  (or db (pool/db conn)))

;; ---------------------------------------------------------------------------
;; query_model —— 缺口报告，每条带一个能修它的 action
;; ---------------------------------------------------------------------------

(def query-model-tool
  {:name "query_model"
   :description
   (str "Named queries answering \"what is the model missing\" (the opposite question to "
        "lexical search). Every gap carries an :action that fixes it, in execute_action's "
        "input shape; positions a human must fill are marked :tools/fill-in and listed "
        "under :fill. An empty :fill means the action is runnable as-is. "
        "Scope is the WHOLE POOL of this workspace's draft model, narrowed only by "
        "`namespace`. It takes no graph and its counts are not any one graph's: a graph "
        "is a list over the pool (D3.19) — graph_members reads its recipe, project_graph "
        "what it would actually contain.")
   :capability :read
   :params
   [{:name "query" :type :keyword :required? false
     :description "Run a single named query; omit to run all of them."
     :enum qm/queries}
    {:name "namespace" :type :string :required? false
     :description "Restrict the report to gaps under this namespace prefix."}]
   :handler
   (fn [ctx args]
     (let [db (db-of ctx)]
       (qm/run (pool/as-dialect db)
               (into {} (for [o (pool/objects db)]
                          [[(:pool/type o) (:pool/id o)] (:pool/revision o)]))
               {:query (:query args) :ns-prefix (:namespace args)})))})

;; ---------------------------------------------------------------------------
;; resolve_target —— 写目标闸门，**不再是 MCP 工具**（8.4.16 裁定九）
;; ---------------------------------------------------------------------------
;;
;; The four verdicts are still the write-target gate, and `impl.intent` still
;; calls them on every name an intent tool takes in (`resolve-name` /
;; `resolve-any`). What was deleted is the DECLARATION: an agent asking «which
;; object do these words mean» is always about to do something with the answer,
;; and the intent tools take the words directly. `open.atlas.studio.tools`
;; still exports `verdicts`, and `impl.resolve-target` is untouched.

;; ---------------------------------------------------------------------------
;; read_object —— the read half of the write pair (8.4.10 P6)
;; ---------------------------------------------------------------------------

(def read-object-tool
  "**Why the tool face exists at all when `pool/object` already did**
   (8.4.10): the tools had a complete write loop — resolve, preflight,
   execute, audit — and no way back in. An agent asked what a write actually
   left in the pool had to believe its own request, and an agent asked for a
   resolver's parameters had to say the read face did not exist.

   **The refusal is made HERE, not in the pool.** `pool/object` returns nil for
   an object that is not registered, and that is correct for a lookup: absence
   is an answer, not an error. Over a tool face it is not — a JSON null tells
   the caller nothing about which of the two arguments was wrong. So this
   declaration turns nil into a finding, the same way `graph_declarations`
   makes `:tools.graph/unavailable` for a condition its module has no opinion
   about. Nothing else is checked here: 8.4.4's invariant stands, and the type
   and id are judged by the pool."
  {:name "read_object"
   :description
   (str "Read ONE pool object: its full entry and current revision. Pool-scoped — takes "
        "no graph. This is where expected_revision comes from when you already KNOW the "
        "target's type and id; resolve_target is for when you only have words for it. "
        "Call it after execute_action to verify what a write actually left in the pool. "
        "Source entries carry `${VAR}` credential references verbatim; values are never "
        "resolved here.")
   :capability :read
   :params
   [{:name "type" :type :keyword :required? true
     :description "Object type." :enum (vec pool/object-types)}
    ;; Same wire discipline as `execute_action`'s `:id`, and for the same
    ;; reason: JSON Schema cannot say «keyword here, string there, pair for
    ;; links», and half a sentence is what a client builds a wrong call from.
    {:name "id" :type :any :required? true
     ;; No `:coerce` hook here, unlike the map positions: the spelling an id
     ;; takes depends on `type`, which a per-parameter hook cannot see. The
     ;; restatement is the pool's own `:id-kind` table, applied in the handler
     ;; below — still one mechanism, still not a second rule (§106.14 item 9).
     :description "Object identity; its shape follows the type (qualified keyword for attributes, string for registry entries, [from to] pair for links)."}]
   :handler
   (fn [ctx args]
     ;; **Two different refusals** (OQ-MCP-WRITE-ERGONOMICS item 2, ruled
     ;; 2026-09-07). They used to be one, and the one they shared said "not in
     ;; the pool" — so `read_object attribute "cis.party/name"` reported the
     ;; object missing when what was wrong was the SPELLING, and the caller went
     ;; looking for a write that had in fact landed. The id is restated first
     ;; (a JSON client can send nothing but a string), then judged against the
     ;; pool's own id-kind table; only an id of the right shape can be absent.
     (let [t  (:type args)
           id (pool/coerce-id t (:id args))]
       (cond
         (not (pool/valid-id? t id))
         {:findings [(f/make :tools.read/malformed-id :error [t]
                             "this is not the shape an id of that type has"
                             :expected (get pool/id-examples (get pool/type-id-kind t)
                                            "the identity shape this type uses")
                             :got (f/redact (:id args)))]}
         :else
         (or (pool/object (db-of ctx) t id)
             ;; The path is the pool's own object key — `[type id]` names the
             ;; position the caller asked about, exactly as `unknown-source`
             ;; above names `[:sources id]`. An id is an identity, not a
             ;; credential.
             {:findings [(f/make :tools.read/unknown-object :error [t id]
                                 "the id is well formed, and no object of this type and id is registered in the pool"
                                 :expected "an object registered in the pool")]}))))})

;; ---------------------------------------------------------------------------
;; Source schema inspection
;; ---------------------------------------------------------------------------

(defn- unknown-source [source-id]
  {:findings [{:code :pool.action/unknown-entity :severity :error
               :path [:sources source-id]
               :message "no such source in the pool; register it before reading its schema"}]})

(def describe-source-tool
  "Read the schema exposed by a registered Source without reading any rows."
  {:name "describe_source"
   :description
   (str "Read relations, columns, declared keys, and connector capabilities. "
        "No row is read and no Graph content is inferred or generated.")
   :capability :read
   :params
   [{:name "source" :type :string :required? true
     :description "Source id registered in the current workspace."}
    {:name "schema" :type :string :required? false
     :description "Restrict to one database schema."}
    {:name "names" :type :vector :required? false
     :description "Restrict to these relation names."}]
   :handler
   (fn [ctx args]
     (let [db (db-of ctx)]
       (if-let [src (intent/source-config ctx db (:source args))]
         (spi/describe-schema src nil (select-keys args [:schema :names]))
         (unknown-source (:source args)))))})

(def declarations
  "固定顺序：读工具只读取明确事实，不生成 Graph 内容。"
  [query-model-tool read-object-tool describe-source-tool])
