(ns open.atlas.studio.tools.impl.finding
  "建模面工具的 finding 注册表。域 = `tools.*`（finding-v1 §3「后续模块续接」）。

   同 pool/impl/finding.clj 的理由：各模块登记自己的域，谁也不去改内核那张表，
   否则 D1.8 的依赖方向就反过来了。

   本 ns 的 code **少得刻意**：工具面自身的错（名字、入参形状）姓 `toolkit.*`，
   模型结构的错姓 `dialect.*`，池的错姓 `pool.*`。剩给 `tools.*` 的只有
   本层独有的判定——目前只有 `resolve_target` 的候选上限一条。"
  (:require [open.atlas.common.dialect :as dialect]))

(def codes
  "已发布 :code 注册表（finding-v1 §6：产出未注册 code 是实现 bug）。"
  #{;; 候选数超过信封上限。principles「信封对每次调用都有界」；
    ;; CLAUDE.md「超限、冲突、缺失都拒绝而非截断/猜测」——截断成前 50 条会让
    ;; 调用方以为自己看到了全部候选，而它正要靠这份清单判断歧义。
    :tools.resolve/candidates-over-limit
    ;; ── graph 注册表（8.4 P2）─────────────────────────────────────────
    ;; **只有两条**，同本 ns 一贯的克制：图本身的拒绝（撞名、切过版本、
    ;; 声明不合法）由 `studio/graph` 产出并**原样**带出来，重述一遍就是
    ;; 给同一个失败发明第二套词。这两条只说本层独有的两件事。
    :tools.draft/unavailable              ; 宿主没挂 draft 服务（不是调用方的错）
    :tools.draft/refused                  ; draft 查询被守卫或执行拒绝（含读写串门）
    :tools.graph/unavailable              ; 宿主没挂 graph 服务（不是调用方的错）
    :tools.graph/refused                  ; 守卫拒绝：角色不够、图不可见、不在本 workspace
    ;; ── 读面补全（8.4.10 P6）──────────────────────────────────────────
    ;; 两条，同上一贯的克制。两条都是**工具面自己的判定**，下游没有对应的
    ;; 失败可以带出来：池对「读一个不存在的对象」返回 nil 而不拒绝，投影对
    ;; 「把整个模型吐出来」没有意见——是这一层的信封有意见。
    :tools.read/unknown-object            ; 池里没有这个 [type id]
    ;; The id is not the SHAPE this type's identity has — a JSON string where
    ;; the pool keys by qualified keyword, say. Split from the line above on
    ;; 2026-09-07 (OQ-MCP-WRITE-ERGONOMICS item 2): sharing one refusal made a
    ;; misspelled id read as «that object does not exist», and a caller
    ;; verifying a write it had just made concluded the write had not landed.
    :tools.read/malformed-id              ; id 的形状不是该类型的身份形状
    :tools.graph/entries-need-kind        ; detail=entries 缺 kind：整模型全量是超限
    ;; ── publish 闭环（8.4 P3）─────────────────────────────────────────
    ;; 同上，两条。切版本的体检、部署命令的拒绝都由 version / deploy 产出，
    ;; **原样带出来**——一次被体检拒绝的切版本要说出是哪一条检查，重述会把
    ;; 那句话变成「发布失败」。
    :tools.publish/unavailable            ; 宿主不发布（没有内嵌 runtime）
    :tools.publish/refused                ; 守卫或本层判定拒绝：角色、未切过的版本、已在服役
    ;; ── intent tools (8.4.14 P7) ─────────────────────────────────────
    ;; These say what the INTENT layer alone can judge. Everything the pool,
    ;; the dialect, the graph service or the projection can judge is carried
    ;; out whole from there — this layer invents no second vocabulary for a
    ;; failure somebody downstream already names (same restraint as the
    ;; graph group above).
    :tools.intent/name-ambiguous          ; a local name resolves to several pool objects; all candidates listed
    :tools.intent/name-unresolved         ; a name resolves to nothing in the pool
    :tools.intent/source-undecidable      ; no `source` given and the graph does not name exactly one
    :tools.intent/namespace-undecidable   ; no `namespace` given and sibling resolvers do not agree on one
    :tools.intent/type-undecidable        ; the machine cannot assert this column's dialect :type
    :tools.intent/unknown-relation        ; the source does not carry this relation
    :tools.intent/unknown-column          ; the relation does not carry this column
    ;; 8.4.19 裁定十三 ①: two parameters filling one slot. Refused rather than
    ;; ranked — «which of the two did they mean» is the caller's sentence to
    ;; fix, and picking one would write a model they did not describe.
    :tools.intent/mutually-exclusive      ; two arguments say different things about the same slot
    ;; The same sentence, said twice, with something changed in between
    ;; (doc 84 §8.4.25). A repeat that says the SAME thing is not a finding at
    ;; all — the plan answers `:existing` and writes nothing; this is the other
    ;; half, where writing it would make this tool the second producer of one
    ;; thing's key and the cut would refuse the whole graph with
    ;; `:cut.structure/multiple-producers` without ever saying why.
    :tools.intent/already-said            ; this sentence was already said, and what is said now differs
    :tools.intent/blocked-plan            ; apply was asked for a plan an :error blocks
    :tools.intent/recipe-not-updated      ; the pool write landed and the recipe delta did not (no rollback)
    :tools.intent/removal-blocked         ; something still names the object; :blocked-by carries the list
    :tools.intent/probe-required          ; api source, and this connection holds no :probe capability
    :tools.intent/probe-refused           ; the probe call itself was refused (status, size, transport)
    ;; The sample value carries a `/` and the path is templated `{attr}`, which
    ;; would encode it into ONE segment — build, cut, then 404 on every keyed
    ;; query (OQ-PATH-KEY-ENCODING). Refused rather than rewritten to `{+attr}`:
    ;; owner ruling 2026-09-07 (doc 106 §106.14 item 8) has the write side point
    ;; at the other spelling, not choose it.
    :tools.intent/placeholder-encoding    ; a slashed key value under a {attr} template
    :tools.intent/verify-failed           ; the post-apply limit-1 draft query failed; the write stands
    ;; ── declare_source (8.4.24 裁定十八) ───────────────────────────────
    ;; ONE, and the same restraint again: everything about the ENTRY — its
    ;; shape (the toolkit's `:toolkit.args/invalid`), a literal credential, an
    ;; unknown key, a connection that did not answer, a stale
    ;; `expected_revision` — is judged by the toolkit, the connector contract,
    ;; the health check or the pool, and carried out whole.
    :tools.source/unavailable             ; the host serves no source-declaration path
    ;; ── credentials (owner ruling 2026-10-11) ──────────────────────────
    :tools.credential/value-required      ; save_credential without a value and without a rename
    ;; ── the workspace default graph (8.4.17 裁定十) ────────────────────
    ;; `graph` omitted and the workspace has no default graph. It is this
    ;; layer's own judgement — the pool and the graph registry are never
    ;; reached, because there is no graph to reach them about — and it names
    ;; the two ways out rather than guessing «the only graph»: a workspace
    ;; with one graph today has two tomorrow, and a tool that guessed would
    ;; then write into whichever one sorted first.
    :tools/graph-required})               ; `graph` omitted and no workspace default graph

(defn make [code severity path message & {:as opts}]
  (assert (contains? codes code) (str "unregistered finding code: " code))
  (assert (contains? #{:error :warning :info} severity))
  (assert (vector? path))
  (assert (string? message))
  (assert (every? #{:expected :got :refs :files} (keys opts))
          (str "finding key set is closed: " (pr-str (keys opts))))
  (merge {:code code :severity severity :path path :message message} opts))

(defn valid? [f]
  (and (map? f)
       (empty? (remove #{:code :severity :path :message :expected :got :refs :files} (keys f)))
       (contains? codes (:code f))
       (contains? #{:error :warning :info} (:severity f))
       (vector? (:path f))
       (string? (:message f))))

(def redact
  "不泄漏截断（finding-v1 §5）——复用 B0 导出的那一个。"
  dialect/redact)
