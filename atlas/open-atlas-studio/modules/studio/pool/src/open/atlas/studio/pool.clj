(ns open.atlas.studio.pool
  "B4 池内核的唯一对外接口。跨模块只许 require 本 ns；
   open.atlas.studio.pool.impl.* 视为私有（clj-kondo :discouraged-namespace 执法，
   同 B0 的 open.atlas.common.dialect）。

   本模块是**本仓库第一个 studio 模块**，于是 D1.8 的依赖方向第一次在两个方向上
   都可验证：这里 `:local/root` 依赖 common/dialect，而 common 的每一份 deps.edn
   里都查不到 studio。

   交付范围是 **B4.1**（modules/studio/pool/README.md 的切片表）：
   Datahike schema、`execute_action` 写面、对象级 `expected_revision`、投机校验、
   池级导出/导入往返；工具 registry（B4.2）与搜索索引（B4.3）不在这里,
   投影（B5a）也不在。

   **B4.4 追加候选签字通道**：`pool-candidates`（从池挖候选，裁定 ①）、
   `confirm-candidates!`（owner 逐条裁决）、`confirmations`（不可变确认记录,
   裁定 ②——`as-dialect` 里没有它）、`provenance`（签字派生 vs 作者直写，裁定 ③）。

   契约：docs/contracts/dialect-v1.md（池里的对象就是方言条目）、
        docs/contracts/finding-v1.md（所有拒绝的形状）。"
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [open.atlas.common.candidates :as candidates]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.pool.impl.action :as action]
            [open.atlas.studio.pool.impl.audit :as audit]
            [open.atlas.studio.pool.impl.batch :as batch]
            [open.atlas.studio.pool.impl.confirm :as confirm]
            [open.atlas.studio.pool.impl.export :as export]
            [open.atlas.studio.pool.impl.finding :as finding]
            [open.atlas.studio.pool.impl.migrate :as migrate]
            [open.atlas.studio.pool.impl.maintenance :as maintenance]
            [open.atlas.studio.pool.impl.model :as model]
            [open.atlas.studio.pool.impl.removal :as removal]
            [open.atlas.studio.pool.impl.schema :as schema]
            [open.atlas.studio.pool.impl.semantic :as semantic]
            [open.atlas.studio.pool.impl.store :as store]))

;; ---------------------------------------------------------------------------
;; 池的开合
;; ---------------------------------------------------------------------------

(defn config
  "`{:backend :mem|:file :path … :id …}` → Datahike 配置。
   同一份配置给 `open-pool!` 与 `delete-pool!`，退出条件 1 的「删掉 store」
   才删得到刚才写的那一个。"
  [opts]
  (store/config opts))

(defn migrate-pool!
  "Bring one already-open pool up to **D2.29** («a branch is an attribute»),
   then **D3.22** («a pool object's owning workspace is a stored column»), then
   the **recipes D2.29 left behind** (doc 106 §106.14 item 19), idempotently. `open-pool!` calls it; it is public so a host can ask what a
   pool needed, and so the migration can be tested for what it did rather than
   only for what it left behind.

   → `{:migrated? bool :summary {:merged n :navigations n :collections n
   :cleared n :stamped n :by-claim n :by-sole-workspace n :recipes n :dropped n
   :moved n :added n} :findings []}`, or
   `{:migrated? false :findings [error…]}` when the migrated model would not
   pass the dialect, or when the D3.22 pass could not decide who owns an object
   (`:pool.migrate/unowned`, one finding per object — it never guesses) — and then **nothing is
   written**: the migration is one transaction, so there is no half-migrated
   pool. A pool that was already carrying dialect errors before the migration
   still migrates; only errors the migration itself introduces refuse it, the
   same diff `execute-action` runs.

   The D3.22 pass stamps each object with the owner the **retired** claim rules
   derived (link → its `:from`, resolver → its `:output` attributes, source →
   the resolvers referencing it, everything else → its own namespace), falling
   back to the sole registered workspace when this Studio has exactly one. See
   `impl/migrate.clj`'s «Stamping the owner» section.

   The recipe pass drops the retired `:navigations` group from every registered
   graph's recipe and adds the join attributes a resolver's `:output` straddles
   — the two defects D2.29 left behind, which made every pre-D2.29 graph both
   uneditable (`:graph.registry/malformed`) and unpublishable
   (`:scope/mixed-output`). A graph a script already repaired is a no-op and
   gets no record. See `impl/migrate.clj`'s «Repairing the recipes» section.

   The migration is an auditable act like any other write: one mutation record
   per object touched, in the same transaction, with a system provenance —
   no entrance (`:unattributed`, audit ruling ②, which names migrations) and a
   machine actor. It does not forge a human one."
  [conn]
  (migrate/migrate! conn))

(defn open-pool!
  "建库（不存在则建）+ 装 schema + 连接 + **把旧池就地迁移**（D2.29，然后 D3.22）。

   A pool written before D2.29 still holds `:navigation` objects and
   `:pool.attribute/collection?` datoms, and nothing addresses either any more
   — the model would come back silently smaller (owner ruling 2026-09-02:
   such pools are fixed, not refused). So the migration hangs off opening: no
   host has to remember to call it, and there is no window in which a caller
   holds a connection to a pool that has not been migrated.

   Throws when the migration is refused. That case means the migrated model
   does not pass the dialect, and the alternative — hand back a connection to
   a pool that is neither old nor new — is the silent half-support this
   migration exists to prevent. The findings ride on the exception's data,
   naming the objects."
  [cfg]
  (let [conn (store/create! cfg)]
    (try
      (let [{:keys [findings] :as report} (migrate-pool! conn)]
        (when (seq findings)
          (throw (ex-info (str "the pool holds pre-D2.29 objects that cannot be migrated: "
                               (count findings) " finding(s)")
                          (assoc report :cfg-store (:store cfg)))))
        conn)
      (catch Throwable e (store/release! conn) (throw e)))))

(defn close-pool! [conn] (store/release! conn))

(defn storage-size [conn]
  (maintenance/size-of (get-in @conn [:config :store :path])))

(defn compact-storage!
  "Back up and reclaim a file pool inside an exclusive, drained host access
   window. opts must publish the replacement connection before access resumes."
  [conn opts]
  (maintenance/compact! conn opts))

(defn delete-pool! [cfg] (store/delete! cfg))

(def datahike-schema
  "池的 Datahike schema。由 impl.model 的类型表机械推出——
   `:db/ident` 与 dialect-v1 的条目键一一对应（裁定 ①）。"
  schema/schema)

;; ---------------------------------------------------------------------------
;; 写面
;; ---------------------------------------------------------------------------

(defn execute-action
  "七概念 + 两注册表的唯一写面（§7.3 建模面）。

   action：`{:action :create|:update|:delete
             :type   :attribute|:link|:metric|:mutation
                     |:namespace|:resolver|:source|:term|:unit
             :id     该类型的身份（属性是全限定 kw、注册表是字符串、
                     link 是 [from to]）
             :workspace 拥有该对象的 workspace 名，**必带**，非空字符串（D3.22）
             :entry  方言条目（create/update；键面即 dialect-v1 的条目键）
             :expected_revision 整数，**必带**，对象级（裁定 ④）}`

   `:create` 的 `expected_revision` 是 0 ——「这个对象还不该存在」也是一句关于
   revision 的断言，用同一个键说，调用方就不必记两套规则。

   **`:workspace`（D3.22，owner 裁定 2026-09-05）**：池对象的归属是**存下来的
   一列**，不从名字推导。`:create` 把这个值盖在对象上；`:update` / `:delete` 要
   与库里盖着的**相等**，不等即 `:pool.workspace/mismatch`（拒绝里点名库里那个
   workspace——调用方得知道去问谁——但不带一个字节的条目内容）。缺席即
   `:pool.action/malformed`（键面封闭的现行做法）。
   它由**宿主**从 token / 会话取，**永远不来自工具参数**：一个能自称 workspace
   的调用方，等于没有边界。

   投机校验（写前拼方言交 B0 判）也按这个 workspace 缩范围：D3.17 之后 workspace
   是封闭世界，跨 workspace 没有不变量可判，而整池判会让 A 的脏挡住 B 的写。

   返回 `{:revision n :mutation-id … :mutations [记录] :findings […]}`（已提交；
   delete 的 `:revision` 为 nil）或 `{:findings [错误…]}`（一个字节都没写）。

   `:mutation-id` 是这次写在变更台账里留下的那条记录的 id，`:mutations` 是同一条
   记录的摘要（`{:mutation-id :action :object :entrypoint :revision-before
   :revision-after}`）。回传它们是为了让**装配层**能按名字把统一审计事件与台账行
   对上（E3）——按时间猜对应关系不算答案。台账记录的形状本身没有变。

   **成功也可能带 finding，但全是 `:warning`**（studio/graph README 裁定 ⑤）：
   投机校验做差集，只有**这次写引入的**新 error 才拒绝；写之前池里就有的脏随行
   报出（`:pool.dialect/pre-existing` 摘要 + 逐条明细）。判有没有被拒看
   `:revision` 或 finding 的 `:severity`，不看 `:findings` 空不空。
   拒绝一律 finding-v1 形状，`:got` 里**不含任何条目内容**——source 条目里
   坐着 `:jdbc_url`（B4 退出条件 2）。

   `prov` 是**可信出处**（8.4 P1b）：`{:entrypoint :ui|:http|:mcp :token-id …
   :actor … :at …}`，全部可选，由**服务端**构造。它是另一个参数而不是 action 的
   一个键：action 键面封闭，所以调用方把 `:entrypoint` 写进 action 得到的是
   `:pool.action/malformed`——伪造身份寻址不到，而不是被过滤掉。
   不给也照样留审计记录（记 `:unattributed`），见 `mutations`。"
  ([conn action] (execute-action conn action nil))
  ([conn action prov]
   ;; `:mutations` is present on EVERY return, empty when nothing was written:
   ;; the assembly layer reads the key to tell «this was a pool write» from
   ;; «this was something else», and a key that appears only on success would
   ;; make a refused write indistinguishable from a read (E3).
   (update (action/execute conn action prov) :mutations #(or % []))))

(defn execute-actions
  "Several actions, applied as **one** unit — all of them, or none.

   For proposals that are only meaningful whole: a table generated from a
   database schema is a namespace, its attributes (scalar and join alike, D2.29)
   and its resolvers, and applying some of those leaves the pool holding an
   entity that answers questions wrongly. Run as n separate `execute-action` calls, \"half
   a table\" becomes a state the pool can be left in — and nothing downstream
   can tell it apart from a table someone is still writing by hand. Same
   argument `confirm-candidates!` makes for being one call rather than n+1.

   Actions apply **in the order given**, each validated against the pool as the
   earlier ones left it, so a batch may create an object and then update it.
   A wrong order is refused by name, never reordered.

   → `{:revisions [n …] :mutations [record …] :findings [warning …]}` when
   committed, or `{:findings [error …]}` when nothing was written. Findings carry
   the index of the action they are about at the head of their `:path`.

   `:mutations` is one summary per touched object, in the order the actions
   applied — see `execute-action` for the shape and for why the ids come back.

   This is not a second write face: every action goes through the same
   `prepare` and the same speculative validation `execute-action` uses.

   `prov` is the trusted provenance (8.4 P1b), exactly as `execute-action`
   takes it; one audit record per action, committed in the same transaction."
  ([conn actions] (execute-actions conn actions nil))
  ([conn actions prov]
   ;; Same as `execute-action`: the key is always there, empty when nothing
   ;; was written.
   (update (batch/execute conn actions prov) :mutations #(or % []))))

;; ---------------------------------------------------------------------------
;; 读面（B4.1 只到「把池读成方言形状」；query_model / search 是 B4.2 / B4.3）
;; ---------------------------------------------------------------------------

(defn namespace-removal
  "What removing a namespace prefix would delete, in an order the pool accepts.

   There is no rename here, deliberately: renaming a namespace means renaming
   every attribute in it, every resolver that names one and every link that
   ends in one, and a half-finished rename still reads as a whole model. The
   supported move is **remove and author again** — cheap, because the schema
   generator exists.

   → `{:prefix … :objects [[type id]…] :actions […] :reaching-in [[type id]…]}`

   `:actions` is `execute-actions` input, ordered so the pool's
   inbound-reference refusal never fires. `:reaching-in` names the objects that
   are **not** in the namespace and go anyway because they refer into it — a
   resolver whose `:output` includes a deleted attribute is not a smaller
   resolver, it is a broken one. That list is the part a caller must see before
   agreeing, which is why this plans rather than deletes.

   Reads only; nothing is written until the actions are executed."
  [db prefix]
  (removal/plan db prefix))

(defn objects-removal
  "What removing an explicit `[[type id] …]` selection would do, and the actions
   that would do it — the same closure, order and refusals `namespace-removal`
   applies, seeded by a picked set instead of a prefix.

   For the case a prefix cannot express: several objects that share no
   namespace, chosen one by one. `:selection` echoes what was asked for and
   `:unknown` names the identities this pool does not hold — a selection that
   silently shrank would have the caller agreeing to a plan for something other
   than what it picked.

   Reads only; `:actions` goes to `execute-actions`."
  [db pairs]
  (removal/plan-objects db pairs))

(defn object-removal
  "What removing one object would do, and the actions that would do it.

   Three rules, one per kind, deliberately not the same:

   - **An attribute anything still names is refused**, with the list in
     `:blocked-by`. Quietly rewriting a resolver to stop mentioning it would
     edit an object whose author did not ask for that; deleting is the
     caller's to ask for, editing someone else's resolver is theirs.
   - **A resolver takes with it the attributes nothing else produces**
     (`:cascade`). Those are not survivors: an attribute no `:output` names is
     `:cut.attribute/unregistered-use` and stops the graph being cut, so this
     finishes the delete rather than extending it. One still referenced
     elsewhere is left and named in `:orphaned`.
   - **A link has no dependants** — it is its endpoint pair (D2.3).

   `also-removed` — `#{[type id] …}` — is the rest of the SAME batch, and the
   references are read from the pool minus it: a resolver and the attributes
   only it produces go in one call rather than three rounds (doc 106 §106.12
   item 8.4). Nothing is removed that the caller did not name; this widens
   what one call may say, not what a removal decides (D3.19).

   Reads only; `:actions` goes to `execute-actions` in an order the pool
   accepts. `:blocked-by` non-empty means `:actions` is empty."
  ([db type id] (removal/object-removal db type id))
  ([db type id also-removed] (removal/object-removal db type id also-removed)))

(defn object-removals
  "`object-removal` for each `[type id]` in `pairs`, all judged against the same
   `also-removed` batch, from one read of the pool. Same answers as calling
   `object-removal` per pair, in the order asked; it exists because a Source
   removal judges hundreds of objects and a full pool read per judgement made
   that plan take minutes (owner 2026-10-04)."
  [db pairs also-removed]
  (removal/object-removals db pairs also-removed))

(defn candidate-db
  "The pool **as it would be** if this action were applied — without applying
   it. `{:db …}` or `{:findings […]}` when the action would be refused anyway.

   Same decision path as `execute-action`: both go through one `prepare`, so a
   preview of an action and the write of that action cannot come to disagree
   about what the action means. Nothing is committed and no lock is taken —
   this is a read.

   It exists so a caller can run the **real** cut checks against a proposal
   instead of reimplementing them (see `studio/version`'s preflight). A second
   copy of D2.11 that answers slightly differently from the one that refuses
   at seal time would be worse than no warning at all."
  [conn action]
  (let [db (d/db conn)
        ;; Shape first, exactly as `execute-action` does: a preview that skipped
        ;; it reported a missing `:workspace` as an ownership mismatch against
        ;; nil (D3.22) instead of as the malformed action it is.
        shape (if (map? action)
                (action/check-shape action)
                [(finding/make :pool.action/malformed :error [] "action must be a map"
                               :got (finding/redact action))])
        r  (if (seq shape) {:findings shape} (action/prepare db action))]
    (if (seq (:findings r))
      {:findings (vec (:findings r))}
      ;; **The dialect gate runs here too.** `prepare` checks shape and
      ;; revision and builds the tx; the B0 diff (`speculative-findings`) used
      ;; to run only in `execute` — so this fn could hand back a candidate db
      ;; holding a model the pool would refuse to commit. Every consumer of
      ;; that db then walks an illegal model: `version/preflight`'s projection
      ;; hit the dialect *writer*, whose assert is not a validator's refusal
      ;; but an AssertionError — which sailed past the HTTP boundary's
      ;; `catch Exception` and left the save hanging forever. One decision
      ;; path means one, gate included.
      (let [{:keys [errors]} (action/speculative-findings
                              db (:db-after r) (:type action) (:id action)
                              (:workspace action))]
        (if (seq errors)
          {:findings (vec errors)}
          {:db (:db-after r)})))))

(defn candidate-actions
  "The dry half of `execute-actions`: what a whole action LIST would be refused
   for, without writing a byte. `{:db db-after}` or `{:findings [error …]}`.

   Same relation to `execute-actions` that `candidate-db` has to
   `execute-action`, and it exists for the same reason: a caller that wants to
   know whether a *plan* would be accepted must ask the code that accepts it.
   The alternative — a planner re-deciding legality on its own — is the second
   semantics D1.8 names, and 8.4.14 裁定六 puts the encoding questions inside
   the tool precisely so a caller never learns them one refusal at a time.

   Actions are validated in order, each against the pool as the earlier ones
   left it (`batch/prepare`), then the same speculative dialect diff
   `execute-actions` runs before it commits. Warnings — dirt the pool already
   carried — are not returned: they are not this list's fault and it is not
   being committed.

   Nothing is committed and no lock is taken. This is a read."
  [conn actions]
  (let [db (d/db conn)
        {:keys [db-after findings]} (batch/prepare db actions)]
    (if (seq findings)
      {:findings (vec findings)}
      (let [{:keys [errors]} (action/speculative-findings
                              db db-after :batch (count actions)
                              (:workspace (first actions)))]
        (if (seq errors) {:findings (vec errors)} {:db db-after})))))

(defn db [conn] (d/db conn))

(defn objects
  "整池 → [{:pool/type :pool/id :pool/revision :pool/entry :pool/workspace}…]，
   确定性顺序。

   `(objects db workspace)` 只给**那个 workspace 拥有的**对象（D3.22）——
   D3.17 之后这就是「一个 workspace 拥有的一切」的完整答案，不用再展开地盘
   selector。整池那个 arity 留给迁移、导出与「这台 Studio 里有什么」这类
   确实跨 workspace 的问题。"
  ([db] (store/all-objects db))
  ([db workspace] (store/all-objects db workspace)))

(defn object
  "单个对象，取不到给 nil。`expected_revision` 与 `:pool/workspace` 的来源。"
  [db type id]
  (some-> (store/find-object db (model/object-key type id)) schema/entity->object))

(defn pool-candidates
  "整池 → **机器提议**的三张候选清单（B4.4，`open.atlas.common.candidates`）。

   裁定 ①：池里的候选**从池挖**，不从别处。池是活的模型——新属性经 action 进来,
   不经转换——所以候选必须读池，否则 owner 过目的是一份关于别的东西的清单。

   「机器提议 / owner 处置」在池里依然成立，只是形状变了：机器提议的是**结构
   事实**（这个本地名在 n 个 entity 上重名且类型兼容），owner 裁的是**意义**。

   **确认记录不参与**：它不在 `as-dialect` 里，于是也不在候选的输入里。签过的
   候选从清单里消失是**因为模型变了**（词条绑上了、组实例建了），不是因为有人拿
   签字文件把它划掉。

   ── 「已定」的那一半是本函数补的，不在 `common/candidates` 里 ────────────
   提议器回答的是**转换器当年的问题**：「这份**未签**的模型里，什么可能成为
   词条？」它按本地名聚类，**不看属性上已有的 `:term`**——在 B2 那条路上这是
   对的，因为候选是从**签字之前**的产物挖的。

   池问的是**另一个问题**：「什么**还需要一次裁决**？」一个簇若每个成员都已经
   绑好词条，它就不是提议了，它是既成事实。原样报上去的后果很具体：owner 可能
   对 `sku_no` 再签一次，于是池里多出**第二个**同义词条，而绑定被改到新的那个上。

   所以过滤放在**这一层**——提议器一个字节不动。它是 `common/candidates` 里
   **唯一一份**提议实现（那个模块存在的全部理由），在它身上改「什么算候选」,
   就是把池这一层的问题写进公共的挖法里。**过滤掉的不静默丢**：`:settled` 逐条列出
   被withheld 的是哪些、为什么。少报一条候选与「本来就没有」在清单上长得一样,
   而这个仓库栽在这种事上不止一次。"
  [db]
  (let [model (model/objects->dialect (store/all-objects db))
        cands (candidates/candidates model)
        attrs (:attributes model)
        bound?   (fn [a] (some? (:term (get attrs a))))
        settled-term?  (fn [c] (every? bound? (:attributes c)))]
    (-> cands
        (assoc :terms (vec (remove settled-term? (:terms cands)))
               :settled
               {:terms (vec (for [c (:terms cands) :when (settled-term? c)]
                              {:local-name (:local-name c)
                               :attributes (:attributes c)
                               :reason :every-binding-already-carries-a-term}))}))))

(defn candidate-index
  "候选清单 → `{[种类 键] 候选行}`。`confirm_candidates` 的 `candidate` 参数取
   这张表的键。"
  [cands]
  (candidates/keyed cands))

(def candidate-kinds
  "三种候选，固定顺序：`[:term :curated :sensitive]`。"
  candidates/kinds)

;; ---------------------------------------------------------------------------
;; 候选签字通道（B4.4）
;; ---------------------------------------------------------------------------

(defn confirm-candidates!
  "owner 对**一条**候选的裁决（§7.3 待钉第 2 条）。

   请求：`{:workspace 写入所属的 workspace 名（**必带**，D3.22）
           :kind :term|:curated|:sensitive
           :candidate  候选键字符串        ; `candidate-index` 的键的第二段
           :decision   :sign|:decline      ; 不签也是裁决，一样留记录
           :concept-id 概念 ID 字符串       ; 签词条必带——机器不许命名概念（D2.26）
           :definition {…}                 ; 词条尚未登记时必带
           :attributes [属性 kw…]           ; 只签一部分绑定；不给 = 全签
           :note 一句话
           :expected_revision 0}`          ; **必带**，对象级；确认记录还不该存在

   **入口从 `prov` 来，不在请求里**（8.4 P1b / audit 裁定 ①）：一个自称
   `:entrypoint :ui` 的 agent 会让签字记录说谎，所以那个键在请求里是
   `:pool.candidate/malformed`。`prov` 的形状同 `execute-action`。

   返回 `{:confirmation 记录 :findings []}`（已提交）或 `{:findings […]}`
   （**一个字节都没写**）。为什么是单独一条 action 而不是 `execute_action` 的
   一个 `:type`，见 impl/confirm.clj 的 ns 注释。"
  ([conn request] (confirm/confirm conn request nil))
  ([conn request prov] (confirm/confirm conn request prov)))

(defn confirmations
  "整池的**不可变确认记录**，按记录键字典序（裁定 ②）。

   `as-dialect` 里**没有它**：它是治理痕迹不是模型内容，混进去会让同一个模型
   因为「谁签的」不同而产出不同的 content-hash，而制品的身份必须只由模型决定
   （D4.3）。"
  [db]
  (store/all-confirmations db))

(defn provenance
  "一个对象是 `:signoff`（签字派生）还是 `:authored`（作者直写）—— 裁定 ③。

   B2 退出条件那句「owner 未签字的候选不写入模型」在池里的准确形态：作者直接写
   一条词条绑定仍然合法，但它在 provenance 上与签字派生的**分得开**。分不开,
   签字就退化成一次多余的点击。"
  [db t id]
  (confirm/provenance db t id))

(defn mutations
  "整池的**变更审计**（8.4 P1b），按记录 id 字典序。

   每条模型写入一条：`{:mutation/id :mutation/entrypoint :mutation/action
   :mutation/object [类型 id] :mutation/revision-before :mutation/revision-after
   :mutation/token-id :mutation/actor :mutation/at}`（后三者可缺席）。

   与 `objects`、`confirmations` 三条查询互不共用寻址键：审计查不进模型，
   模型也查不出审计（audit 裁定 ③）。这是「制品的 content-hash 不随谁改的而变」
   （D4.3）在存储层的落点，不是一句纪律。"
  [db]
  (store/all-mutations db))

(defn system-mutation-tx
  "Audit records, as tx data, for a write **the platform itself** performs —
   a migration or a one-shot repair naming the ruling that moved the data.

   `migration` is that ruling (`\"D3.22\"`); `entries` are
   `{:action :type :id :revision-before :revision-after}` maps, one per object.
   The caller splices the result into its OWN `d/transact`, so the records and
   the change they describe land together or not at all — the same property
   `execute-action` has, said once rather than twice.

   On the interface because a second writer of system mutation records now
   exists outside this module (`studio/graph`'s `rename-workspace!`, D3.22 /
   E4 §1b.3), and CLAUDE.md is explicit: what two modules need goes on the
   interface, it does not get written twice. The provenance shape is
   `audit/system-provenance`'s and cannot be reached over the wire — see that
   fn for why."
  [db migration entries]
  (audit/tx (audit/system-provenance migration) (count (store/all-mutations db)) entries))

(def entrypoints
  "入口词表 `#{:ui :http :mcp}`——**调用方可以点名的那些**。两张台账
   （确认记录与变更审计）共用这一份，工具入参的枚举也取它，不各写一遍。

   `:unattributed` 不在词表里：它是**缺席的形状**，只有池自己写得出（audit
   裁定 ②）。"
  audit/entrypoints)

(def confirmation-entrypoints
  "`entrypoints` 的旧名。保留是因为它已经出现在别的模块里，删名字是另一件事。"
  audit/entrypoints)

(def confirmation-decisions confirm/decisions)

(defn as-dialect
  "整池 → 方言模型（**不是投影**：投影是 B5a 的 scope 菜谱，裁定 ⑤）。
   投机校验与「精确重建」的比对都用它。

   `(as-dialect db workspace)` 是同一件事**限于一个 workspace**（D3.22）。"
  ([db] (model/objects->dialect (store/all-objects db)))
  ([db workspace] (model/objects->dialect (store/all-objects db workspace))))

;; ---------------------------------------------------------------------------
;; 导出 / 导入
;; ---------------------------------------------------------------------------

(defn dialect->objects
  "A dialect model **as written** → pool objects, ready to hand to `import-pool!`
   as `:pool/objects`. Normalisation and schema validation happen inside
   `import-pool!`, which refuses with `:pool.dialect/rejected` — so this function
   is a shape conversion, not a gate.

   The reverse of `as-dialect`. It is public because filling a pool from a
   committed model file is what `fixtures/demo/model.edn` exists for, and what
   an assembly point has to do before it can cut anything — and the only way to
   do it used to be piercing this module's private impl var from another
   module's test, i.e. the exact thing the boundary rule forbids.

   Callers still set `:pool/revision` themselves: which revision these objects
   land as is the importer's judgment, not this function's."
  [model]
  (model/dialect->objects model))

(defn export-pool
  "整池 → 规范形 EDN 字符串（裁定 ③）。同一池任何时候导出逐字节相同。"
  [db]
  (export/export-str db))

(defn import-pool!
  "导出信封（EDN 数据）→ 空池。返回 `{:count n :findings []}` 或 `{:findings […]}`。"
  ([conn data] (export/import! conn data))
  ([conn data checkers] (export/import! conn data checkers)))

;; ---------------------------------------------------------------------------
;; finding
;; ---------------------------------------------------------------------------

(defn finding?
  "x 是否为合法 finding。池的拒绝里同时有 `pool.*`（池特有的判定）与
   `dialect.*`（回落到 B0 的结构校验，裁定 ②）两种 code；`pool-candidates` 还会带
   `candidate.*`（机器提议本身的话，B4.4）。三张注册表都认——只认一张会让调用方
   拿到一条**合法却判为非法**的 finding，而那种错最难查。"
  [x]
  (or (finding/valid? x) (dialect/finding? x) (candidates/finding? x)))

(def finding-codes
  "全部已发布 `pool.*` code 的注册表（finding-v1 §6）。"
  finding/codes)

(def object-types
  "池认识的九个对象类型，固定顺序。"
  model/type-order)

(def type-fields
  "类型 → `{字段 种类}`，种类是 `:string` / `:keyword` / `:boolean` / `:edn`。

   Builder 的表单按这张表出字段，**而不是另抄一份键表**：池认得的键与表单给
   得出的键必须是同一个集合，否则表单会长出一个写下去就被
   `:pool.action/unknown-entry-key` 拒掉的输入框。"
  (into {} (for [[t d] model/types] [t (:fields d)])))

(def type-update
  "类型 → 更新语义（`:merge` / `:replace`）。

   编辑器要这张表，因为它与池说的不是同一种话：编辑器给的是**整份条目**,
   `:update` 收的是**补丁**——merge 类型下补丁里缺席的键**存活**，于是「从
   整份条目里删掉一个键」必须翻译成显式的 `键 nil`（删除记号），否则那次
   删除被静默吞掉：保存成功、revision 上涨、值纹丝不动。"
  (into {} (for [[t d] model/types] [t (:update d)])))

(def type-id-kind
  "类型 → 身份的形状（`:keyword` / `:string` / `:ns+name` / `:attr-pair`）。
   表单据此决定 id 输入框怎么读用户敲进去的东西。"
  (into {} (for [[t d] model/types] [t (:id-kind d)])))

(defn open-entry-keys
  "该类型是否收留契约之外的键（只有 `:source`，见 D2.15）。"
  [t]
  (:open-keys (get model/types t)))

;; ---------------------------------------------------------------------------
;; 身份的 JSON 拼法（owner 裁定 2026-09-07，doc 106 §106.14 第 9 条）
;; ---------------------------------------------------------------------------

(defn valid-id?
  "这个 id 的形状符合该类型的身份约定吗（`type-id-kind` 那张表）。"
  [t id]
  (model/valid-id? t id))

(defn coerce-id
  "把 JSON 客户端送来的 id 改写成该类型的身份拼法，改不动就原样返回。

   JSON 只送得出字符串，而属性的身份是全限定 keyword（D2.17）、link 的是一对
   keyword。于是 `read_object type=attribute id=\"a.b/c\"` 过去查的是
   `[:attribute \"a.b/c\"]`——池里当然没有，答的却是「池里没有这个对象」
   （OQ-MCP-WRITE-ERGONOMICS 第 2 条）。判据是 `type-id-kind` 这张表本身，
   不是另写一份；形状对不上的一律原样交回，由调用方拒并给样例。"
  [t id]
  (case (:id-kind (get model/types t))
    :keyword   (if (and (string? id) (str/includes? id "/")) (keyword id) id)
    :attr-pair (if (and (sequential? id) (= 2 (count id)) (every? string? id))
                 (mapv keyword id)
                 id)
    id))

(def id-examples
  "身份形状 → 一条**字面样例**，进拒绝的 `:expected`（doc 106 §106.14 第 9 条）。
   `:id` 在声明里仍是 `:any`（JSON Schema 说不出「这个类型的身份长什么样」，
   `tools/impl/declarations.clj` 写着理由）——样例住在 finding 里，不在声明里。"
  {:keyword   "a qualified keyword, e.g. \"cis.party/customer_no\""
   :string    "a string, e.g. \"cis.party\""
   :ns+name   "a string, e.g. \"cis.party\""
   :attr-pair "a pair of qualified keywords, e.g. [\"a.x/id\", \"b.y/id\"]"})


(defn semantic-draft
  "Read one semantic draft in a trusted workspace context; legacy objects are separate."
  [db workspace graph]
  (semantic/read-draft db workspace graph))

(defn semantic-drafts
  "Read all current Semantic Contract drafts for repository integrity checks."
  [db]
  (semantic/drafts db))

(defn save-semantic-draft!
  "Save a complete validated semantic document with optimistic revision and
   same-transaction immutable history. Workspace/provenance come from the host;
   document identity must match them. A rejected save changes nothing."
  [conn workspace graph expected-revision document checkers provenance]
  (semantic/save! conn workspace graph expected-revision document checkers provenance))


(def semantic-workspace-rename-tx
  "Transaction fragment for the existing workspace rename; new revision, original historical bytes."
  semantic/rename-workspace-plan)

(def semantic-deletion-tx
  "Graph deletion transaction fragment: its Semantic draft and history only."
  semantic/deletion-tx)
