(ns open.atlas.studio.graph
  "B5a 的唯一对外接口。跨模块只许 require 本 ns；
   `open.atlas.studio.graph.impl.*` 视为私有（`.clj-kondo/config.edn` 的
   `:discouraged-namespace` 执法，由 `module_boundary_test` 逐条对账）。

   **B5a.3 追加三样**：发现面报表（裁定 ⑥ 的四张，`report-*`）、sync 单飞
   （R3.7，`single-flight` / `sync!`），以及池那一侧的投机校验差集（裁定 ⑤,
   落在 `open.atlas.studio.pool` 的写面里，不在本 ns）。

   **交付范围是 B5a.1**（modules/studio/graph/README.md 的切片表）：
   graph 登记与限定名、D3.14 授权的登记与查询。**namespace 地盘登记与越界写标记
   已删**（D3.17 / D3.22）：归属是池对象上的一列，写入不可能越界。**scope 投影（B5a.2）与 sync 单飞（B5a.3）不在这里**——
   本 ns 里没有任何一个函数读池里的对象来算模型。

   B5a.2 用到本 ns 的 `selector-matches?` / `selector-covers?`
   （scope projection v1 §1 的分段边界匹配，**唯一实现**）。地盘判据
   （`namespace-owner` / `asset-owner`）与访问档位的 `effective-ceiling`
   都已退役——前者随 D3.17 / D3.22（归属是池对象上存下来的一列），
   后者随访问档位（owner 裁定 2026-08-27）。

   治理数据**住在池里但不进 `as-dialect`**（README 裁定 ①）：每种实体有自己的
   `:db.unique/identity` 键，一律不共用 `:pool.object/key`，于是 `pool/objects`、
   投机校验与池的导出**查不到**它们。它们随本模块自己的信封导出（见 `export-data`）。

   契约：docs/contracts/scope-projection-v1.md（selector 的规范形与分段边界）、
        docs/contracts/finding-v1.md（所有拒绝的形状）。"
  (:require [clojure.edn :as edn]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.graph.impl.export :as export]
            [open.atlas.studio.graph.impl.finding :as finding]
            [open.atlas.studio.graph.impl.projection :as projection]
            [open.atlas.studio.graph.impl.registry :as registry]
            [open.atlas.studio.graph.impl.rename :as rename]
            [open.atlas.studio.graph.impl.report :as report]
            [open.atlas.studio.graph.impl.saved-queries :as saved-queries]
            [open.atlas.studio.graph.impl.sync :as sync]
            [open.atlas.studio.graph.impl.schema :as schema]
            [open.atlas.studio.graph.impl.selector :as selector]
            [open.atlas.studio.graph.impl.store :as store]))

;; ---------------------------------------------------------------------------
;; 装 schema
;; ---------------------------------------------------------------------------

(def datahike-schema
  "治理数据的 Datahike schema（裁定 ①）。**与池的 schema 分开装**：pool 不认识
   workspace（B4 裁定 ⑤），依赖方向是 graph → pool。"
  schema/schema)

(defn install!
  "把治理 schema 装进一个已开的池连接（`pool/open-pool!` 的产物）。幂等。"
  [conn]
  (schema/install! conn))

;; ---------------------------------------------------------------------------
;; selector：scope projection v1 §1 的分段边界匹配（B5a.2 共用这一份）
;; ---------------------------------------------------------------------------

(def selector-canonical?
  "selector 是不是 §1 的两种规范形之一（精确名，或 `.*` / `/*` 前缀）。"
  selector/canonical?)

(def selector-matches?
  "selector 是否选中这个名字。`sales.*` 选中 `sales.order`，不选中 `salesforce.order`。"
  selector/matches?)

(def selector-overlap?
  "两个 selector 是否可能选中同一个名字——地盘查重的判据（D3.3）。"
  selector/overlap?)

(def selector-covers?
  "地盘 selector 是否**完全覆盖**另一个 selector——菜谱 selector 的合法性判据（D3.9）。"
  selector/covers?)

(def object-namespace
  "对象 id → 它所在的 namespace / 词表域，取不出给 nil。"
  selector/namespace-of)

;; ---------------------------------------------------------------------------
;; workspace 与地盘（D3.3）
;; ---------------------------------------------------------------------------

(def roles "Workspace member roles, including legacy `:editor` during migration." registry/roles)
(def graph-access-roles
  "The grantable per-graph roles — `#{:editor}` since the owner retired `:viewer`
   (2026-08-30), which could not change any decision. See the impl for why."
  registry/graph-access-roles)

(defn register-workspace!
  "`{:name \"sales\" :members [{:member \"alice\" :role :owner} …]}`
   → `{:workspace … :findings []}` 或 `{:findings [错误…]}`。"
  [conn req]
  (registry/register-workspace! conn req))

(defn rename-workspace-plan
  "What `rename-workspace!` would do, without doing it (E4 §1b.3). Read-only."
  [db old new shelf-parents]
  (rename/plan db old new shelf-parents))

(defn rename-workspace!
  "改一个 workspace 的名字，连同它名下的图、授权、池对象归属、已切版本登记、
   用户默认 workspace 与货架目录（owner 裁定 2026-09-05，E4 §1b.3）。

   `shelf-parents` 是持有 `<workspace>` 子目录的目录清单（`<home>/artifacts`
   与 runtime root 下的 `artifacts` / `receipts` / `events` / `pointers`）。
   一个事务落库，成功后移目录；移动失败则把已移的移回并把改名事务反向做掉。
   幂等：新名已在、旧名已不在，即空操作成功。制品的 `manifest.edn` **不改**
   ——`artifact-digest` 覆盖它（artifact-v1 §7.3），详见 `impl.rename`。"
  [conn old new shelf-parents]
  (rename/rename-workspace! conn old new shelf-parents))

(defn add-workspace-member!
  "Record an accepted workspace invitation for a canonical authenticated principal."
  [conn workspace member]
  (registry/add-workspace-member! conn workspace member))

(defn remove-workspace-member!
  "Remove a member. The workspace's last owner cannot be removed — that would
   leave nobody able to manage it, and there is no repair path."
  [conn workspace principal]
  (registry/remove-workspace-member! conn workspace principal))

(defn workspace-role
  "Return this principal's workspace role, or nil."
  [db workspace principal]
  (registry/workspace-role db workspace principal))

(defn workspace-member?
  "Whether a principal belongs to the workspace and may see all of its graphs."
  [db workspace principal]
  (registry/workspace-member? db workspace principal))

(defn workspaces
  "已登记的 workspace，按名字字典序。"
  [db]
  (store/all-workspaces db))

(defn graphs
  "已登记的 graph，按限定名字典序。"
  [db]
  (store/all-graphs db))

(defn graph
  "按限定名取一张 graph（含菜谱），取不到给 nil。"
  [db qn]
  (store/find-graph db qn))

(defn graph-by-id
  "按 immutable UUID 取一张 graph。限定名可被显示层使用，但 UUID 才是实例内
   永久 identity。"
  [db id]
  (store/find-graph-by-id db id))

;; ---------------------------------------------------------------------------
;; graph 与限定名（D3.10 / D3.11）
;; ---------------------------------------------------------------------------

(def qualified-name
  "`<workspace>/<graph>`（D3.10）。裸名不进任何持久化标识。"
  registry/qualify)

(def parse-qualified-name
  "限定名 → `[workspace 裸名]`，形状不对给 nil。"
  registry/unqualify)

(defn register-graph!
  "`{:workspace \"sales\" :name \"core\"
     :members {:namespaces #{\"sales.order\"}
               :attributes #{:sales.order/id}
               :resolvers #{\"order_by_id\"}}      ; 对象清单，D3.19
     :created-at \"2026-08-27T09:00:00Z\"}`      ; 调用方给，本模块不读时钟

   菜谱两部分（D3.11），**结构先立住，按它投影是 B5a.2**。
   拒绝的三种：裸名在本 workspace 内重复（不同 workspace 同裸名合法，D3.10）、
   selector 不在本 workspace 地盘里（D3.9）、引用未被授权（D3.14）。"
  [conn req]
  (registry/register-graph! conn req))

(defn set-display-name!
  "Change the user-facing name while retaining UUID and deployment coordinates."
  [conn qn name]
  (registry/set-display-name! conn qn name))

(defn rename-graph!
  "改限定名（D3.10）。**`guard` 必传**：`(fn [db qn] → findings)`,
   由认识版本的那一层（`open.atlas.studio.version`）给出「这张图切过版本没有」。
   graph 模块自己不认识版本——反向依赖会成环。见 `impl.registry/rename-graph!`。"
  [conn qn new-name guard]
  (registry/rename-graph! conn qn new-name guard))

(defn update-recipe!
  "改菜谱（selector 与引用清单）。**这个函数改不了限定名**——改名走
   `rename-graph!`，而它必须带一个「切过版本没有」的 guard（D3.10）。
   B5a 时这里比 D3.10 更严（登记即不可变）；B5b 有了版本之后，收敛到
   D3.10 的原话「切出第一个版本后不可变」——**放开的方式是加那个带 guard
   的函数，不是放松这里的任何东西**。"
  [conn qn recipe]
  (registry/update-recipe! conn qn recipe))

(def apply-members-delta
  "菜谱的**按成员集增删**原语（8.4.14）。纯函数：当前成员 × `{:add … :remove …}`
   → 新成员，或 `{:findings …}`。

   **不作为 MCP 工具暴露**（owner 裁定 2026-09-03）：按对象 id 说话的工具违反
   意图工具的立项原则，增量由意图工具内部生成，这里只是它的执行者。写仍然走
   `update-recipe!`——权限判定一处，不在这里再来一份。"
  registry/apply-members-delta)

(defn set-package-name!
  "Set — or clear, with `nil` — the name this graph's versions are published
   under (doc 104 §8.17.7 D). `open-atlas/dvd`: two segments, graph-id
   character rules, refused with a finding when malformed."
  [conn qn package-name]
  (registry/set-package-name! conn qn package-name))

(def package-name-ok?
  "Is this a well-formed release name? Exported so the write faces refuse with
   the same test the registry applies, not a second regex."
  registry/package-name-ok?)

(def package-name-for
  "A registered graph → `{:name \"open-atlas/dvd\" :default? bool}` or
   `{:findings […]}` (doc 104 §8.17.7 D). `:package-name` if set, else a
   default: `<workspace>/<graph>`, or `open-atlas/<graph>` for a personal
   workspace (an email address never becomes a package name). Findings only when
   a personal workspace's graph name leaves nothing to build a name from."
  registry/package-name-for)

(defn set-default-graph!
  "Set — or clear, with `nil` — the graph a workspace lands on (D3.21).
   The graph must belong to this workspace (D3.17)."
  [conn workspace qn]
  (registry/set-default-graph! conn workspace qn))

(defn default-graph
  "The workspace's landing graph, or nil. A pointer to a graph that is gone,
   archived, or in another workspace answers nil rather than refusing — it is a
   preference, and the caller's own ordering is the fallback."
  [db workspace]
  (registry/default-graph db workspace))

(defn set-default-query!
  "Set — or clear, with `nil` — the query Query Studio opens this graph with
   (owner ruling 2026-09-01). `default-query` is `{:query <EQL vector>
   :entity <map, optional>}`.

   On the graph rather than in a local preference file, so it travels with a
   published demo; query AND entity, so a default that needs an entry value
   carries one; absent stays absent, so «no default» and «an empty default»
   remain two different things. See `impl.registry/set-default-query!`.

   No version guard, unlike `rename-graph!`: this is not part of a graph's
   identity, and a cut version does not freeze it."
  [conn qn default-query]
  (registry/set-default-query! conn qn default-query))

;; Saved queries (proposal studio-saved-queries §3). Storage only — the shape
;; check is the host's, through `server.mcp`, before anything is written.

(defn saved-queries
  "A graph's DRAFT saved queries, or `[]`."
  [db qn]
  (saved-queries/draft db qn))

(defn set-saved-queries!
  "Replace a graph's draft saved queries; an empty vector clears them."
  [conn qn queries]
  (saved-queries/set-draft! conn qn queries))

(defn pinned-saved-queries
  "The saved queries a published version carries, by graph UUID and version
   number; nil for a version published before saved queries existed."
  [db graph-uuid version]
  (saved-queries/pinned db graph-uuid version))

(defn pin-saved-queries!
  "Record the set a version is published with. Write-once per version."
  [conn graph-uuid version queries]
  (saved-queries/pin! conn graph-uuid version queries))

(defn unpin-saved-queries!
  "Drop the set pinned to a reclaimed version (R3.5)."
  [conn graph-uuid version]
  (saved-queries/unpin! conn graph-uuid version))

;; Pinned data-plane requests (proposal studio-saved-queries §4.1b).

(def max-pinned-requests-per-workspace saved-queries/max-pinned-per-workspace)

(defn pinned-requests
  "A graph's live pinned requests (by graph UUID), newest first."
  [db graph-id now]
  (saved-queries/pinned-requests db graph-id now))

(defn pin-request!
  "Keep one recorded request → `{:pinned …}` or `{:findings …}`."
  [conn pin]
  (saved-queries/pin-request! conn pin))

(defn unpin-request! [conn id] (saved-queries/unpin-request! conn id))

(defn purge-expired-pins! [conn now] (saved-queries/purge-expired-pins! conn now))

(defn archive-graph!
  "Archive a graph without deleting it.  Archived graphs cannot be selected for
   execution; owner-only access policy is enforced by the Builder service."
  [conn qn]
  (registry/set-state! conn qn :archived))

(defn restore-graph!
  "Restore a previously archived graph with its UUID and recipe unchanged."
  [conn qn]
  (registry/set-state! conn qn :active))

(defn ensure-graph-identities!
  "Backfill UUIDs/state for existing graph records after installing the schema."
  [conn]
  (registry/ensure-identities! conn))

(defn grant-graph-access!
  "Grant a workspace member viewer/editor rights on an existing graph."
  [conn qn access]
  (registry/grant-graph-access! conn qn access))

(defn revoke-graph-access!
  "Remove one explicit graph grant without removing workspace visibility."
  [conn qn principal]
  (registry/revoke-graph-access! conn qn principal))

(defn graph-access-role
  "Effective :owner/:editor/:viewer role for one graph, or nil for outsiders."
  [db qn principal]
  (registry/graph-access-role db qn principal))

(defn graph-visible?
  "All workspace members can see all team graphs."
  [db qn principal]
  (registry/graph-visible? db qn principal))

(defn graph-editable?
  "Only the workspace owner or explicit graph editor may edit a graph."
  [db qn principal]
  (registry/graph-editable? db qn principal))

(defn graph-creatable?
  "Only the workspace owner may create a new graph."
  [db workspace principal]
  (registry/graph-creatable? db workspace principal))

(defn visible-graphs
  "List every graph visible to a workspace member across their workspaces."
  [db principal]
  (registry/visible-graphs db principal))

;; ---------------------------------------------------------------------------
;; 授权（D3.14）
;; ---------------------------------------------------------------------------



;; The reference-grant surface (`grant-reference!` / `revoke-reference!` /
;; `references-available` / `grants-issued` / `effective-ceiling`) retired with
;; cross-graph references themselves (D3.17): a workspace is a closed world, so
;; there is nothing to authorise a reference to. `effective-ceiling` went with
;; access tiers on top of that (owner ruling 2026-08-27).
;; ---------------------------------------------------------------------------
;; scope 投影（B5a.2）—— docs/contracts/scope-projection-v1.md 整份
;; ---------------------------------------------------------------------------

(defn migrate-recipes!
  "把 D3.19 之前登记的 selector 菜谱一次性展开成对象清单。幂等；
   返回 `{:migrated [限定名…]}`——**说出改了哪些**，因为一次无声的迁移与
   「本来就是这样」在结果上没有区别。`model` 由调用方给（`pool/as-dialect`）。"
  [conn model]
  (registry/migrate-recipes! conn model))

(defn delete-graph!
  "Permanently delete an archived Graph, its owned Semantic draft/history, and
   supplied cut-version records. Shared legacy pool objects remain. The caller
   supplies `version-deletion-tx` to avoid a module cycle."
  [conn qn version-deletion-tx]
  (registry/delete-graph! conn qn version-deletion-tx))

(def expand-selectors
  "selector 集合 × 池快照 → 对象清单。界面上「按前缀批量加入」的实现,
   展开在**调用的那一刻**发生；此后池里新增的对象不会自动入伙（D3.19）。"
  registry/expand-selectors)

(def graph-members-ok?
  "菜谱形状判定（scope-projection-v1 §1）。写面与面向用户的校验共用这一份。"
  registry/graph-members-ok?)

(def members-findings
  "菜谱形状的拒绝，逐条点名出错的组与身份（finding-v1 §2）。界面要在发之前就把话
   说清楚时用它——`graph-members-ok?` 只答是/否，那正是调用方换形态硬试的由来。"
  registry/members-findings)

(def normalize-members
  "缺席的组补空集、顺序收敛成集合——菜谱是集合语义。"
  registry/normalize-members)

(defn project
  "**scope projection v1**：同一事务点的池快照 + graph 限定名 → 规范化模型。

   ```clojure
   (project db \"sales/core\")
   ```

   → `{:model … :edn 规范形文本 :local … :local-edn … :references [] :findings []}`
   ；失败 → `{:findings […]}`，**不产半份模型**。

   菜谱是**对象清单**（D3.19），从快照里的登记取（同一事务点）。`:references`
   恒为空——引用机制已随 D3.17 退役，键保留是为了让制品 manifest 与装载方读到
   「这张图不依赖任何别的图」这句话，而不是读到一个消失的键。

   十步固定算法在 `impl.projection` 里一步一个函数，逐条对着契约读。"
  [db qn]
  (projection/project db qn))

;; ---------------------------------------------------------------------------
;; 导出 / 导入（裁定 ①：治理数据跟着池走）
;; ---------------------------------------------------------------------------


;; ---------------------------------------------------------------------------
;; 发现面报表（B5a.3，README 裁定 ⑥ / §3.3）
;; ---------------------------------------------------------------------------
;;
;; 只做**现在做得了的那四张**。滞后报告要 server 控制面（B5c）、僵尸版本要切版本
;; （B5b）——一个「暂时总返回空」的实现比不写更坏：空清单与「没有滞后」在报表上
;; 长得一模一样。

(defn report-ownership
  "**归属**：这个属性 / namespace / 限定注册表 ID 归哪个 workspace（§3.3 第一问）。

   `{:query x :namespace \"sales.order\" :workspace \"sales\" :owned? true}`;
   池里没有这个 id 的对象时 `:workspace` 为 nil 且 `:owned?` 为 false——那是真
   信息（「这台 Studio 里没有这个对象」），不是空答案。

   **判据是池对象上存着的 `:pool/workspace`**（D3.22），不再拿名字去问地盘表
   （D3.17 拆了那张表，D3.22 删了它）。"
  [db x]
  (report/ownership db x))

(defn report-referrers
  "**引用反查**：谁的**当前菜谱**里钉着 `qn@version`（§3.3「谁引用 core@v3」）。
   `version` 给 nil = 不限版本。逐版本精确匹配：引用 `core@v4` 的不在 `core@v3`
   的结果里（R3.1 引用只钉版本）。

   B5a 没有已切版本，所以这答的是「谁**打算**引用它」；按已切版本的那一份要等
   B5b，且**不与本函数同名**。"
  ([db qn] (report/referrers db qn nil))
  ([db qn version] (report/referrers db qn version)))

(defn report-membership-by-current-recipe
  "**含量清单（池内版）**：这个对象**按当前菜谱**会进哪些图。

   ⚠️ **与 §3.3 的含量清单不是同一问**。§3.3 问「X 出现在哪些 **graph 版本**里」
   （要 B5b）；这里答「按今天的菜谱投影，它会不会进这张图」。菜谱改过还没重切时
   两者答案不同——**同名会让人读错**，所以名字里带着 `by-current-recipe`（裁定 ⑥）。

   实现是**对每张登记的图跑一次 `project`**，不另写闭包遍历（裁定 ⑥）。
   `resolved-for` 是 `限定名 → 已解析引用集合` 的函数，缺省不给引用：那时带引用的图
   投不出来，**落进 `:undecidable` 并带着投影自己的 finding**，绝不悄悄算成
   「不在里面」。

   `(report-membership-by-current-recipe db [:attribute :sales.order/id])`
   → `{:object … :in [限定名…] :not-in […] :undecidable [{:graph … :findings […]}…] :findings []}`。"
  ([db object] (report/membership-by-current-recipe db object))
  ([db object resolved-for] (report/membership-by-current-recipe db object resolved-for)))

;; ---------------------------------------------------------------------------
;; sync 单飞（R3.7，B5a 退出条件 3）
;; ---------------------------------------------------------------------------

(defn single-flight
  "一道 sync 单飞闸（R3.7）。**按 graph 限定名分道**，状态是显式的值不是全局
   atom——一个进程里的两个 studio 实例不该莫名其妙地互相合流。"
  []
  (sync/single-flight))

(defn in-flight
  "此刻在建的 graph 限定名集合。观测用，不是同步原语。"
  [flight]
  (sync/in-flight flight))

(defn sync!
  "**同一张 graph 的并发 sync 合并为一次执行，后到者等待同一结果；不同 graph
   各自独立分道，互不排队**（R3.7 原话）。

   `build` 是 0 参函数——物化 env 走 engine，那是投影产物的下游，装配点不在本模块
   （D1.8）。本模块交付的是**合流语义**本身。

   沿用「同时只有一个候选在建」的既有语义，**因此没有 `:superseded` 状态，也没有
   候选取消路径**（R3.7）——那句缺席是规定，不是遗漏。

   graph 没登记就结构化拒绝，不去跑 `build`：sync 物化的是一份**已登记的菜谱**。"
  ([flight qn build] (sync/sync! flight qn build))
  ([flight db qn build]
   (if (nil? (store/find-graph db qn))
     {:findings [(sync/unknown-graph qn)]}
     (sync/sync! flight qn build))))

(defn export-data
  "整池的治理数据 → 导出信封。空节缺席，所以没有治理数据的池导出是
   `{:graph/version 1}`。"
  [db]
  (export/export-data db))

(defn export-str
  "规范形 EDN 字符串（B0 的同一个 writer，逐字节确定）。"
  [db]
  (export/export-str db))

(defn import!
  "导出信封 → 池。`{:count n :findings []}` 或 `{:findings […]}`。"
  [conn data]
  (export/import! conn data))

;; --- 整备份：两节一起走，因为**少一节是静默的** -----------------------------
;;
;; 治理数据与池对象分两个信封（裁定 ①：pool 不认识 workspace，下层替上层声明数据
;; 形状会反转 D1.8）。代价是恢复变成两步，而**只做了第一步的池看起来完全正常**：
;; `workspaces` 与 `graphs` 都返回 `[]`，与「一个全新的空池」逐字节相同,
;; 一个 finding 都不报。
;;
;; 「缺失与从来没有过长得一样」是本仓库反复付过代价的形态（签字键拼错、fixtures
;; 掉出 classpath、`-d` 前缀被吃掉、lint 清单指向不存在的 ns）。所以整备份走这一对
;; 函数：**上层知道下层是允许的**（D1.8 是单向的），于是恢复重新变回一个动作。

(def bundle-version
  "整备份信封的版本。与 `:pool/version`、`:graph/version` 各自独立——
   它记的是「这两节是一起导出的」，不是任何一节的格式。"
  1)

(defn export-bundle
  "池对象 + 治理数据 → 一个信封。两节都在，缺一即不是整备份。"
  [db]
  {:bundle/version bundle-version
   :bundle/pool    (edn/read-string (pool/export-pool db))
   :bundle/graph   (export/export-data db)})

(defn import-bundle!
  "整备份 → 空池。**两节都导，顺序固定**（池在前：治理数据点名的对象要先在）。

   信封缺任何一节即拒绝——那正是这一对函数存在的理由：拒绝一次，好过让一个
   看起来正常的半份备份跑上三个月。"
  [conn bundle]
  (let [missing (vec (remove #(contains? bundle %) [:bundle/pool :bundle/graph]))]
    (cond
      (not= bundle-version (:bundle/version bundle))
      {:findings [(finding/make :graph.export/version-unsupported :error [:bundle/version]
                          "unsupported bundle version"
                          :expected bundle-version :got (:bundle/version bundle))]}

      (seq missing)
      {:findings [(finding/make :graph.export/malformed :error [:bundle]
                          (str "a bundle carries both sections; " (count missing)
                               " missing. Restoring only the pool leaves the pool looking "
                               "exactly like a fresh install — no workspaces, no graphs, "
                               "and no finding at all")
                          :expected [":bundle/pool" ":bundle/graph"]
                          :got (mapv str missing))]}

      :else
      (let [p (pool/import-pool! conn (:bundle/pool bundle))]
        (if (seq (:findings p))
          p
          (let [g (import! conn (:bundle/graph bundle))]
            (if (seq (:findings g))
              g
              {:count [(:count p) (:count g)] :findings []})))))))

;; ---------------------------------------------------------------------------
;; finding
;; ---------------------------------------------------------------------------

(def finding-codes
  "本模块已发布的 `:code` 注册表（finding-v1 §6）。域是 `workspace.*` / `graph.*`。
   **越界写不在里面**：它不是拒绝（D3.4）。"
  finding/codes)

(def finding? finding/valid?)
