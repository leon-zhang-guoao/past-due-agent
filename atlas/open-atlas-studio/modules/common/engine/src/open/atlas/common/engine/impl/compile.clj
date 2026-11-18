(ns open.atlas.common.engine.impl.compile
  "归一模型 → 每档位一份 pathom env（D5.3 的档位物化）。

   **D5.3 的物化裁量点，明说**：契约要求「同一张图对不同档位的 token 呈现不同的
   可解析集合」，并点名 `:restricted` 不够档时「该属性不进这个 token 的 catalog，
   凡输出含它的 resolver 也不进这个 token 的 index」。这里把「不进 index」实现为
   **按档位裁剪 resolver 的 `:output` 语法树**，而不是整条 resolver 出局：

   - 该档位的 index 里**没有任何条目声称能供出**那个属性——D5.3 花大代价买来的
     「与不存在不可区分」原样成立，规划器不可能规划出一条到达它的路径，
     点名它只能得 `:unknown-attribute`（D5.4 第 3 种）；
   - 连接器即使照旧 SELECT 了那一列，行在离开 resolver 前按裁剪后的 output 过滤，
     **值不越过边界**；
   - 裁剪后 output 为空的 resolver 整条出局；`:input` 含不够档 `:restricted` 属性的
     resolver 也整条出局——那条路径本身确实不存在（D5.3「可作为中间量」一栏对
     `:restricted` 是「否」）。

   为什么不按字面「整条出局」：fixtures/b1 的四条 resolver 每条都含一个
   `:restricted` 属性（`ave_cost` / `contact_email`），整条出局会让 `:public` 与
   `:sensitive` 档的 index 空无一物——而同一份 fixture 的 README 明确要求
   `:public` token 点名 part 的 `vend_no` 得到结构化拒绝、wildcard 得到过滤计数，
   那要求 `demo_part_by_id` 在 `:public` 的 index 里。字面读法与它自己的验收集
   互斥；裁剪读法两者都满足，且不放弃任何一条可观察性质。"
  (:require [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine.impl.model :as m]
            [open.atlas.common.engine.impl.limits :as limits]
            [open.atlas.common.engine.impl.spi :as spi]))

(defn- collection-call [collection f]
  (if-not collection (f)
    (let [[status result] (limits/with-deadline {:execution-ms (get-in collection [:bounds :timeout-ms])} f)]
      (case status
        :ok result
        :error (throw result)
        :timeout (throw (ex-info "Source collection exceeded its execution budget" {:error/category :timeout}))))))

(def reserved-params
  "保留调用名（dialect-v1 §3）：不得作为 eql-param 名声明，且对每个 resolver 通行。"
  #{:limit :offset :order_by :sort_dir})

(defn- finding [code severity path message & {:as extra}]
  (merge {:code code :severity severity :path path :message message
          :refs ["D5.3" "D5.4"]}
         extra))

;; --- 行过滤 ----------------------------------------------------------------

(defn- allowed-keys
  "该档位下这条 resolver 允许出现在结果里的键：裁剪后的叶属性 + join 分支。"
  [pruned]
  (into (m/output-leaves pruned) (m/output-joins pruned)))

(defn- scrub-row
  "行 map → 只留允许的键。连接器多给的列（`SELECT ave_cost` 照旧发生）
   在这里被挡住——档位物化不能只靠 index 声明，值也不许越过边界。"
  [row ks]
  (when row (into {} (filter (fn [[k _]] (contains? ks k))) row)))

(defn- row-tree
  "ONE row's share of the `:output` tree: the whole of it, or — when the
   resolver hangs its rows under a single join root (D2.8/D2.16) — that
   branch's children."
  [pruned join-root]
  (if join-root (get (first pruned) join-root) pruned))

(defn- nest-row
  "Flat row -> the shape `:output` declares (D2.16: output is a syntax tree).

   Connectors answer FLAT `{attribute value}` maps — sql's
   `spi/apply-field-mapping` reads one column per attribute, api's
   `:field_mapping` one JSONPath per attribute — so a branch BELOW the root is
   assembled here. That branch is the «belongs to / has one» join of
   need-grammar-v1 B2/B4: `{A/b [B/k]}`, whose only leaf is the parent's
   identity attribute, mapped from the child row's foreign key (doc 106
   §106.14 item 5, owner 2026-09-06). Nesting it is what lets the planner walk
   from the child into the parent's by-key resolver without a second read —
   the value was already in the row.

   A branch whose leaves are all absent or nil answers `nil` (this child has
   no parent), never a half-filled map."
  [tree row]
  (if (nil? row)
    row
    (reduce
     (fn [acc branch]
       (reduce-kv
        (fn [a k sub]
          ;; A connector that already ANSWERED for this branch is left alone —
          ;; including when its answer was «no parent» (nil). This layer
          ;; completes flat rows, it does not re-shape, and it must not
          ;; re-derive a branch-scoped answer from the top-level leaves: a SELF
          ;; relation's branch leaf is the SAME attribute kw as the resolver's
          ;; own identity output (§106.14 item 6), so doing so moved the
          ;; group's own id into its parent slot and left the group without one.
          ;; A recursion branch (2.0 §6.5, `{k n}`) is never assembled from
          ;; the row's own leaves -- its rows come from the resolver that
          ;; produces `k`, which the planner calls per child. Left absent.
          (if (or (contains? row k) (integer? sub))
            a
            (let [ks (m/output-leaves sub)
                  vs (select-keys row ks)]
              (assoc (apply dissoc a ks)
                     k (when (some some? (vals vs)) (nest-row sub vs))))))
        acc branch))
     row
     (filter map? tree))))

(def ^:private record-identities
  "doc 106 §106.14 item 17, from `common/dialect`: one implementation, three
   callers (the engine here, the SPI seam's `carried-attributes`, and
   `need-view`'s compound identity)."
  dialect/record-identities)

(def ^:private carried-by-branch
  "Every join branch of an `:output` tree → what it inherits from its parent
   (doc 106 §106.14 items 13 and 17). Moved to `common/dialect` when item 18
   made `need-view` need the same walk: two copies of «what rides this branch»
   would put a thing's key on one face and not the other (D1.8).

   The engine still decides WHICH attributes are bound and which branches are
   in-record — those are source-dialect facts and stay behind the SPI."
  dialect/carried-by-branch)

(defn- with-carried-branches
  "Add each branch's carried attributes as leaves of that branch (item 13 for
   the bound ones, item 17 for the ancestors' keys).

   Pathom 3's plain nested join does NOT inherit the parent's attributes, so a
   child resolver whose `:input` needs the host — or the parent's key — is
   simply unreachable; the standard remedy is for the parent resolver to
   DECLARE and EMIT what it already knows. The dialect `:output` is untouched:
   this is mechanical bookkeeping over `:input` and the declared identities,
   which is why it lives here and not in the model.

   An empty `carry` answers the tree itself, so a source that carries nothing
   (sql, static, derived) compiles to the very same `::pco/output` as before."
  [tree carry]
  (letfn [(level [v]
            (mapv (fn [node]
                    (if (map? node)
                      (into {}
                            (map (fn [[k sub]]
                                   (cond
                                     (vector? sub)
                                     (let [walked  (level sub)
                                           present (set (filter keyword? walked))]
                                       [k (into walked (remove present) (get carry k))])
                                     ;; 2.0 §6.5: a union's branches are walked
                                     ;; each on their own; nothing is carried
                                     ;; into a union or a recursion node.
                                     (map? sub) [k (update-vals sub level)]
                                     :else [k sub])))
                            node)
                      node))
                  v))]
    (if (empty? carry) tree (level tree))))

(defn- inject-carried
  "Write the carried values into every child row/map the `:output` tree hangs
   below this one (items 13 and 17).

   `avail` is what the parent level can supply: this call's `:input` values at
   the top, and at every level below, the values already written into that
   child plus the child row's own. A branch the connector answered `nil` (this
   child has no parent) stays nil — the values travel WITH a child, they do not
   conjure one.

   **What the child already answered wins.** A `:_from` branch whose rows are
   the SAME namespace as their host — a process group's status inside a
   process group's status — carries the parent's identity attribute under the
   very key the child fills with its own id, and merging the carried value
   last replaced every child's id with its parent's (measured on nifi-local
   v14: each child status then answered the ROOT's connections). Same reading
   as item 6's branch scope: this layer completes a row, it never overrules
   one."
  [tree row carry avail]
  (if (or (empty? carry) (nil? row) (not (map? row)) (not (vector? tree)))
    row
    (let [avail (merge avail row)]
      (reduce
       (fn [acc branch]
         (reduce-kv
          (fn [a k sub]
            (let [vs    (select-keys avail (get carry k))
                  child (get a k)]
              (cond
                (map? child)        (assoc a k (merge vs (inject-carried sub child carry vs)))
                (sequential? child) (assoc a k (mapv #(merge vs (inject-carried sub % carry vs))
                                                     child))
                :else               a)))
          acc branch))
       row
       (filter map? tree)))))

(defn- discriminate-unions
  "Semantic contract 2.0 §6.4 on ONE shaped row: every union branch's value
   must belong to exactly one branch -- carry exactly one branch's identity
   attribute (the union's keys after lowering). Zero or several is not a record
   of any branch: the value is dropped to `nil` and `report!` is told the
   relative path, never the value. Pathom left to itself would pick the first
   branch present and say nothing, which is the guess D2.9 forbids. Nested
   joins and unions are walked so a union below a join is judged too."
  [tree row report! rel]
  (if-not (map? row)
    row
    (reduce
     (fn [acc branch]
       (reduce-kv
        (fn [a k sub]
          (let [v (get a k)
                one (fn [child p]
                      (cond
                        (not (map? child)) child
                        ;; An identity counts when it has a VALUE: an api branch
                        ;; scope maps every branch's attributes, so the other
                        ;; branch's identity arrives present-but-nil. Those nil
                        ;; keys are dropped from the matched record, because
                        ;; Pathom dispatches a union sub-query on `contains?`.
                        (map? sub) (let [hits (filter #(some? (get child %)) (keys sub))]
                                     (if (= 1 (count hits))
                                       (discriminate-unions (get sub (first hits))
                                                            (apply dissoc child (remove #{(first hits)} (keys sub)))
                                                            report! p)
                                       (do (report! p) nil)))
                        (vector? sub) (discriminate-unions sub child report! p)
                        :else child))]
            (cond
              (not (contains? a k)) a
              (integer? sub) a
              (sequential? v) (assoc a k (vec (map-indexed (fn [i c] (one c (conj rel k i))) v)))
              (map? v) (assoc a k (one v (conj rel k)))
              :else a)))
        acc branch))
     row
     (filter map? tree))))

(defn- shape-result
  "按条目级 `:result_type` 定形（D2.19；连接器服从声明，engine 不从 output 推断）。
   `join-root` 非 nil 时行装在那个 key 下（D2.16 分支）；行**内**的分支由
   `nest-row` 装（106.14 第 5 条）。

   `carry` is the per-branch table of carried attributes (§106.14 items 13 and
   17) and `avail` this call's `:input` values, from which each branch's share
   is selected: they are written into every nested child, and — when the rows
   hang under a root collection — into each row, that join root being a branch
   like any other.

   `report!` (2.0 §6.4) receives the relative path of a union value that
   matched zero or several branches; nil when the tree has no union, so a 1.x
   row is shaped exactly as before."
  ([result result-type join-root ks rtree carry avail]
   (shape-result result result-type join-root ks rtree carry avail nil))
  ([result result-type join-root ks rtree carry avail report!]
  (let [root-vals (if join-root (select-keys avail (get carry join-root)) {})]
    (letfn [(shape [row rel]
              (let [r (inject-carried rtree (nest-row rtree (scrub-row row ks))
                                      carry (if join-root root-vals avail))
                    r (if report! (discriminate-unions rtree r report! rel) r)]
                (if (and join-root (map? r)) (merge root-vals r) r)))]
      (case result-type
        "single" (let [row (if (and join-root (map? result) (contains? result join-root))
                             (get result join-root)
                             result)]
                   (cond-> (shape row (if join-root [join-root] []))
                     join-root (->> (array-map join-root))))
        "list"   (let [rows (cond
                              (and join-root (map? result)) (get result join-root)
                              (sequential? result)          result
                              (nil? result)                 []
                              :else                         [result])
                       rows (vec (map-indexed (fn [i row] (shape row (if join-root [join-root i] [i]))) rows))]
                   (if join-root {join-root rows} rows)))))))

(defn- union-reporter
  "The `report!` a resolver hands `shape-result` (2.0 §6.4): appends a
   value-free `:engine.execution/union-ambiguous` finding to the per-request
   sink the engine put on the env, at the record's position (Pathom's own
   `::p.path/path`) plus the relative path inside the row. nil when the tree
   opens no union or the env carries no sink, so nothing changes for 1.x."
  [env id rtree]
  (when-let [sink (and (seq (m/output-unions rtree)) (:open.atlas.common.engine/union-issues env))]
    (fn [rel]
      (swap! sink conj {:code :engine.execution/union-ambiguous :severity :error
                        :path (into (vec (:com.wsscode.pathom3.path/path env)) rel)
                        :message (str "resolver " id " answered a union value that carries the identity of "
                                      "zero or several branches; the value is withheld, not guessed")
                        :resolver-id id
                        :refs ["ATLAS-SEMANTIC-CONTRACT-V2 §6.4" "D2.9"]}))))

;; --- 参数 ------------------------------------------------------------------

(defn- resolve-params
  "声明过的 eql-params + 保留调用名 → 给连接器的**扁平** eql-params map。
   校验（未声明的参数、`:allowed` 子集、`:limit` 上限、`:order_by` 白名单、
   caller-supplied 档位）已在 impl.gate 做完——这里只取值与填缺省。

   **分页强制**（D2.8）：`:limit` 有声明就一定发出去，调用方没给就用
   `:default`——「agent 不会一句话打死数据库」靠的正是这一句没有 else 分支。"
  [declared limit-spec order-spec caller]
  (let [named (into {} (for [[pname decl] declared
                             :let [v (get caller pname (:default decl))]
                             :when (some? v)]
                         [pname v]))
        [ord dir] (:default order-spec)]
    (cond-> named
      limit-spec         (assoc :limit (or (:limit caller) (:default limit-spec)))
      (:offset caller)   (assoc :offset (:offset caller))
      (or (:order_by caller) ord)
      (assoc :order_by (m/resolve-order-by order-spec (or (:order_by caller) ord))
             :sort_dir (or (:sort_dir caller) dir :asc)))))

(defn- source-def-of
  "The source config a resolver actually runs on: the registered `:sources`
   entry merged with the inline operation block. One definition, because
   `compile-resolver` dispatches on it and `compile-model` reports its type
   into `:param-decls` — two copies of this merge would drift."
  [model entry]
  (merge (get-in model [:sources (:source-ref entry)]) (:source entry)))

;; --- static → in-memory resolver (8.17) --------------------------------------

(defn- match-keys
  "`:match-on` → the vector of attributes rows are matched on. A bare keyword
   is the single-key spelling, a vector is a compound key (owner 2026-09-07,
   doc 106 §106.14 item 14); absent means «no match», the whole `:data` is the
   row set."
  [match-on]
  (cond (nil? match-on)     []
        (keyword? match-on) [match-on]
        :else               (vec match-on)))

(defn- input-failure [input]
  (some #(when (spi/batch-item-failure? %) %) (vals input)))

(defn- pathom-input
  "The dialect's flat `:input` roots → Pathom's `::pco/input`. Semantic
   contract 2.0 §2.3/§2.4: a root in `:input-optional` becomes `(pco/? a)` --
   the planner still schedules the resolver when it is unreachable and the key
   is simply absent from the input map -- and a root in `:input-nested` becomes
   `{a children}`, which the planner resolves first and hands over as a vector
   of maps. Everything else in this ns keeps reading the flat `:input`; this is
   the one place the two extra keys are consumed."
  [input input-optional input-nested]
  (mapv (fn [a]
          (cond
            (contains? input-nested a) {a (get input-nested a)}
            (contains? input-optional a) (pco/? a)
            :else a))
        input))

(defn- failed-projection [output failure]
  (zipmap (map #(if (map? %) (ffirst %) %) output) (repeat failure)))

(defn- with-priority
  "Semantic contract 2.0 §4.4: a dialect `:priority` becomes `::pco/priority`,
   which Pathom's planner reads to order the branches of an OR node (highest
   first) and its runner to fall back along. Written only when the model has
   the key, so a 1.x resolver's config is byte-for-byte what it was."
  [config {:keys [priority]}]
  (cond-> config (some? priority) (assoc ::pco/priority priority)))

(defn- compile-static-resolver
  "A `\"static\"` operation block → a Pathom resolver over its own `:data`.

   **Not through the connector SPI** — same precedent as `compile-links`: a
   link has no source to call, and neither does static (static-v1: no external
   service, no credential, no I/O). The admission test for this translation
   layer is exactly *no callable source*; anything with a connection,
   credential or I/O goes behind the SPI (D2.15, 8.17). `spi/registered-types`
   therefore does not contain `\"static\"` by design.

   Semantics borrowed from Pathom's built-ins, constructors deliberately not
   used: `global-data-resolver` (no `:match-on`, the whole `:data` is the row
   set) and `static-table-resolver` (`:match-on` indexes rows by an input
   value). Both derive `::pco/output` from the data via `data->query`, which
   would let data widen the declared output — D2.19 says output obeys the
   declaration only, so `::pco/output` is `pruned` and rows pass through the
   same `shape-result` as sql / api.

   `:limit` comes from `resolve-params` like every other resolver (static-v1:
   `:limit` still declared and honoured); no second paging path. `:order` is
   REFUSED instead of honoured (owner 2026-09-05): static-v1 grants static no
   ordering capability, so rows answer in the order `:data` lists them, and a
   declaration the contract does not grant is an error rather than something
   this branch quietly implements on its own.
   A `:match-on` miss answers `nil` (single) / `[]` (list) — contract
   conformance, not a failure."
  [{:keys [id input input-optional input-nested result_type eql-params limit order row-security] :as res} src-def
   path pruned jr ks rtree]
  (let [{:keys [data match-on]} src-def
        rows (if (map? data) [data] (vec data))]
    (cond
      ;; Semantic contract 2.0 §2.2: static matches rows on DETERMINATE input
      ;; values, so an optional root (which may be absent) or a nested root
      ;; (which arrives as rows, not a value) has nothing to match on. Refused
      ;; here as well as at the binding adapter, because a hand-edited model
      ;; can reach the engine without passing through `semantic-static`.
      (or (seq input-optional) (seq input-nested))
      [nil [(finding :engine.compile/static-input-v2-unsupported :error path
                     (str "resolver " id " declares optional or nested inputs on a static "
                          "source; static :match-on needs a determinate value per input")
                     :got (vec (concat (sort input-optional) (sort (keys input-nested))))
                     :expected "plain inputs only"
                     :refs ["ATLAS-SEMANTIC-CONTRACT-V2 §2.2" "static-v1"])]]

      ;; `:data` is model content, same tier as the attributes it feeds; a
      ;; row-security declaration on it can only ever verify as "unknown"
      ;; (`bound-attributes :default` → nil), so it is refused, not carried.
      row-security
      [nil [(finding :engine.compile/static-row-security :error path
                     (str "resolver " id " declares :row-security on a static source; "
                          "static :data is model content and carries no row-level "
                          "security to enforce")
                     :got (:attribute row-security)
                     :refs ["static-v1" "8.17"])]]

      ;; static-v1 conformance: "list" over a single map is a refusal.
      (and (= "list" result_type) (map? data))
      [nil [(finding :engine.compile/static-list-not-sequential :error path
                     (str "resolver " id " is a \"list\" static resolver but its :data "
                          "is a single map; a list needs a sequence of rows")
                     :got "map" :expected "a sequential of row maps"
                     :refs ["static-v1" "8.17"])]]

      ;; static-v1's capability line grants static neither ordering nor offset;
      ;; `:data` answers in the order it is written. Sorting it here would be
      ;; the engine handing out a capability the contract withheld, and
      ;; ignoring the declaration would be a silent degradation — so the
      ;; declaration itself is refused. This also closes the caller's door:
      ;; with no `:order` declared, `impl.gate` refuses an `:order_by` param
      ;; with `:engine.request/order-not-allowed`.
      order
      [nil [(finding :engine.compile/static-order-unsupported :error path
                     (str "resolver " id " declares :order but source type \"static\" "
                          "has no ordering capability; static rows answer in the "
                          "order :data lists them")
                     :got (vec (keys order))
                     :expected "no :order declaration on a static resolver"
                     :refs ["static-v1" "8.17"])]]

      ;; `:match-on` IS the ::pco/input of Pathom's static-table semantics;
      ;; a key the resolver never receives can never match. A VECTOR matches on
      ;; every listed attribute at once — a compound key (owner 2026-09-07,
      ;; doc 106 §106.14 item 14) — so each part is held to the same test.
      (seq (remove (set input) (match-keys match-on)))
      [nil [(finding :engine.compile/static-match-on-not-input :error path
                     (str "resolver " id " matches static rows on " (pr-str match-on)
                          " but " (pr-str (vec (remove (set input) (match-keys match-on))))
                          " is not in its :input")
                     :got match-on :expected (str "all of " (pr-str (vec input)))
                     :refs ["static-v1" "8.17"])]]

      :else
      [(pco/resolver (symbol "open.atlas.common.engine.generated" id)
                     (with-priority {::pco/input  (vec input)
                                     ::pco/output pruned
                                     ::pco/cache? true}
                                    res)
                     (fn [env in*]
                       ;; `order` is nil here — a non-nil one never reaches this
                       ;; branch — so `resolve-params` is called exactly as the
                       ;; sql path calls it and yields no `:order_by`.
                       (if-let [failure (input-failure in*)]
                         (failed-projection pruned failure)
                         (let [ps  (resolve-params eql-params limit order (pco/params env))
                             mks (match-keys match-on)
                             ;; Every part must agree — a compound key names
                             ;; one row, not one row per part.
                             hit (if (seq mks)
                                   (let [v (mapv #(get in* %) mks)]
                                     (filterv #(= v (mapv (fn [k] (get % k)) mks)) rows))
                                   rows)
                             hit (if-let [n (:limit ps)] (into [] (take n) hit) hit)]
                         (shape-result (if (= "single" result_type) (first hit) hit)
                                       result_type jr ks rtree {} {}
                                       (union-reporter env id rtree))))))
       []])))

;; --- 单条 resolver ----------------------------------------------------------

(defn- compile-resolver
  "一条 resolver 条目 → 该档位的 pathom resolver，或 nil（该档位下不存在）。
   第二个返回值是 findings（跑不了的照样携带，只是排除出编译——principles）。

   `in-cat?` is always `(constantly true)` now — it was where D5.3's tier
   pruning entered, and the tiers retired (D5.11). The parameter survives
   until a dedicated pass collapses it out of every gate below. The D5.4
   *correctness* findings (source-unresolved, list-without-anchor,
   source-not-executable, batch-unsupported) were never about tiering and run
   as they always did."
  [model in-cat? {:keys [id input input-optional input-nested output result_type source source-ref
                         eql-params limit order] :as res}]
  (let [path     [:resolvers id]]
    (cond
      ;; :input 含不够档的 :restricted 属性 → 这条路径本身不存在（D5.3）
      (not (every? in-cat? input))
      [nil []]

      :else
      (let [pruned (m/prune-output output in-cat?)]
        (if (empty? pruned)
          [nil []]
          (let [src-def   (source-def-of model res)
                order     (some-> order (m/with-order-aliases (:field_mapping src-def)))
                ;; §106.14 item 13: the attributes THIS source binds from its
                ;; connection entry (a templated `:base_url`) and that this
                ;; resolver actually takes in. The intersection, not the
                ;; source's set: a resolver that does not take one cannot pass
                ;; it on. `connection-bound-attributes` reads the source ENTRY,
                ;; so ask the registry when the entry is registered.
                src-entry (or (get-in model [:sources source-ref]) src-def)
                bound     (vec (filter (set (spi/connection-bound-attributes src-entry))
                                       input))
                ;; §106.14 item 17: the branches whose rows come from INSIDE
                ;; the parent record additionally carry the record's identity
                ;; attributes. The source dialect says which those are, so ask
                ;; the SPI rather than read a connector's spelling here.
                in-rec    (set (spi/in-record-collection-attributes src-def))
                carry     (carried-by-branch
                           pruned bound in-rec (m/identity-attrs model)
                           (record-identities
                            (m/identity-attrs model)
                            (into (filterv keyword? pruned) input)))
                pruned    (with-carried-branches pruned carry)
                jr        (m/join-root pruned)
                ks        (allowed-keys pruned)
                rtree     (row-tree pruned jr)
                ;; 8.17: static is translated here, not dispatched through the
                ;; SPI, so the known-type gate must not exclude it. The
                ;; list-without-anchor and batch-unsupported gates above and
                ;; below still apply to it unchanged (static-v1: batch ✗).
                static?   (= "static" (:type src-def))
                batch?    (boolean (:batch source))]
            (cond
              (nil? (:type src-def))
              [nil [(finding :engine.compile/source-unresolved :warning path
                             (str "resolver " id " references unknown source-ref " source-ref)
                             :got source-ref)]]

              (and (= "list" result_type) (nil? jr))
              [nil [(finding :engine.compile/list-without-anchor :warning path
                             ;; 8.4.21 裁定十五 ②: plain first, engine words after.
                             (str "resolver " id " returns a list but hangs under no entity's "
                                  "collection, so it does not exist for queries. Its :output has "
                                  "no single join attribute to carry the rows (D2.8/D2.16)")
                             :refs ["D2.8" "D2.16" "D2.19"])]]

              (and (not static?) (not (spi/known-type? (:type src-def))))
              [nil [(finding :engine.compile/source-not-executable :warning path
                             (str "no connector is registered for source type "
                                  (pr-str (:type src-def)) "; resolver " id
                                  " is carried but excluded from compilation")
                             :got (:type src-def)
                             :refs ["principles: 模型与方言"])]]

              (and batch? (not (spi/supports-batch? (:type src-def))))
              ;; 落到没有 compile-batch-source 的连接器上是**配置错误**，
              ;; 不是静默退回逐行——用户要了一次取数就必须被告知没拿到（principles）。
              [nil [(finding :engine.compile/batch-unsupported :warning path
                             (str "resolver " id " declares :batch but source type "
                                  (pr-str (:type src-def))
                                  " does not implement compile-batch-source")
                             :got (:type src-def)
                             :refs ["principles: 执行与连接"])]]

              ;; No callable source → engine translation, not SPI (8.17).
              static?
              (compile-static-resolver res src-def path pruned jr ks rtree)

              :else
              (try
                (let [src-fn (if batch?
                               (spi/compile-batch-source src-def res)
                               (spi/compile-source src-def res))
                      ;; E2: the correlation ids ride the pathom env (engine's
                      ;; `::trace` opt) and are re-bound here, on the thread the
                      ;; resolver actually runs on, so a connector can tag the
                      ;; statement it is about to send. Bound only when present,
                      ;; so a call without a trace is byte-for-byte as before.
                      trace-of (fn [env] (:open.atlas.common.engine/trace env))
                      body   (if batch?
                               (fn [env inputs]
                                 (let [ps (resolve-params eql-params limit order (pco/params env))
                                       active (filterv #(not (input-failure %)) inputs)]
                                   (spi/with-request-trace (trace-of env)
                                     (let [answers (volatile! (seq (when (seq active) (src-fn active ps))))]
                                       (mapv (fn [in*]
                                               (let [r (or (input-failure in*)
                                                           (let [r (first @answers)]
                                                             (vswap! answers next) r))]
                                                 (if (spi/batch-item-failure? r)
                                                   ;; Keep errors typed through planning; never pass them
                                                   ;; to another source or fabricate nil detail facts.
                                                   (failed-projection pruned r)
                                                   (shape-result r result_type jr ks rtree
                                                                 carry (select-keys in* input)
                                                                 (union-reporter env id rtree)))))
                                             inputs)))))
                               (fn [env in*]
                                 (if-let [failure (input-failure in*)]
                                   (failed-projection pruned failure)
                                   (let [ps (resolve-params eql-params limit order (pco/params env))]
                                     (spi/with-request-trace (trace-of env)
                                       (shape-result (collection-call (:semantic/collection res) #(src-fn in* ps)) result_type jr ks rtree
                                                     carry (select-keys in* input)
                                                     (union-reporter env id rtree)))))))]
                  [(pco/resolver (symbol "open.atlas.common.engine.generated" id)
                                 (-> {::pco/input  (pathom-input input input-optional input-nested)
                                      ::pco/output pruned
                                      ::pco/cache? false}
                                     (cond-> batch? (assoc ::pco/batch? true))
                                     (with-priority res))
                                 body)
                   []])
                ;; 装载是模型问题，能不能跑是运行时问题；跑不了的照样携带、
                ;; 只是排除出编译（principles「模型与方言」）。
                (catch Throwable t
                  [nil [(finding :engine.compile/source-not-executable :warning path
                                 (str "resolver " id " could not be compiled against source type "
                                      (pr-str (:type src-def)))
                                 :got (:type src-def)
                                 :expected "a source type with a registered connector SPI method")]])))))))))

;; --- link → alias resolver ---------------------------------------------------

(defn- compile-links
  "link 的 `:direction`/alias 语义 → alias resolver。**不经连接器**——它没有 source
   可调（principles「模型与方言」）。`:bi`（缺省）编译成两条方向相反的 alias，
   `:one-way` 只编 from→to。`:cardinality` 不影响构造，它是给 agent 与 catalog 的
   声明，pathom 的导航从不依赖它。
   `in-cat?` 恒为 `(constantly true)`（档位退役，D5.11）；形参与「任一端不进
   catalog 则整条 link 不编」的闸门保留，等参数收拢的那一趟一起动。"
  [model in-cat?]
  (into []
        (comp (filter (fn [{:keys [from to]}] (and (in-cat? from) (in-cat? to))))
              (mapcat (fn [{:keys [from to direction]}]
                        (if (= :one-way direction)
                          [(pbir/alias-resolver from to)]
                          [(pbir/alias-resolver from to)
                           (pbir/alias-resolver to from)]))))
        (:links model)))

(defn- compile-conditional-references [model]
  (vec
   (mapcat
    (fn [[i r]]
      (for [[from to] (cond-> [[(:reference/from r) (:reference/to r)]]
                       (= :two-way (:reference/direction r))
                       (conj [(:reference/to r) (:reference/from r)]))]
        (pco/resolver
         (symbol "open.atlas.common.engine.conditional-reference" (str i "-" (if (= from (:reference/from r)) "forward" "reverse")))
         {::pco/input [from] ::pco/output [to]}
         (fn [_ input]
           (if-some [value (get input from)] {to value} {})))))
    (map-indexed vector (:semantic/conditional-references model)))))

;; --- catalog ----------------------------------------------------------------

(defn- build-catalog
  "这张图的 catalog（D5.4 步骤 ①）。**只有一份**：可见性的粒度是 graph,
   一张图的 catalog 对它的每一个 grant 持有者逐字节相同——访问档位整套退役之后
   （D5.11），「按 token 各看各的」这回事没有了。

   **唯一还会让 catalog 少东西的是 `:listed? false`**（D2.28 / §7.3）：它对所有
   调用方一视同仁，是「这一列不是这张图对外语义的一部分」这个**建模**判断,
   不是安全分级，也不该被当成安全控制用（D2.28 逐字写着这句）。点名它得
   `:unknown-attribute`，与点名一个不存在的属性逐字相同——消费端因此分辨不出
   「不列」与「没有」，这正是它只能是建模判断的理由。

   **规划器仍可把它当中间量**：`reachable` 不受本条影响，只有 catalog 受,
   derived 属性因此照样算得出来（D5.3 把 `:planner-derived` 与 `:caller-supplied`
   分开正是为此）。

   `listed-filter?` 是本 fn 仅剩的旋钮，而**唯一的调用处传 `true`**：编译出来的那
   一份 catalog 就是消费侧看到的对外语义。旋钮留着是给建模侧一条「看见自己写下的
   一切」的路，眼下没人走——它不是一道会按调用方变的门。"
  [model listed-filter? reachable]
  (let [attrs (into (sorted-map)
                    ;; **Scalar attributes only** (D2.29). Join attributes live in
                    ;; the same `:attributes` registry now, but they are a different
                    ;; *answer*: a scalar row says "here is a value you can ask for",
                    ;; a join row says "here is an entity you can nest into". They
                    ;; are rendered from one registry into two lists — `:joins`
                    ;; below — not stored in two.
                    (for [[attr def*] (dialect/scalar-attributes model)
                          :when (and (or (not listed-filter?) (not (false? (:listed? def*))))
                                     (contains? reachable attr))]
                      [attr (-> (select-keys def* [:label :description :type :format
                                                   :identity? :values :synonyms :unit
                                                   :deprecated? :term])
                                (assoc :entity (m/entity-of attr)))]))]
    {:attributes   attrs
     :entities     (into (sorted-map)
                         (for [[ns-name ns-def] (:namespaces model)]
                           [ns-name (select-keys ns-def [:label :kind :synonyms :description])]))
     ;; D2.29: was `:navigations`, fed from the retired per-namespace registry.
     ;; Keeping that name would keep exactly the dual identity the ruling ends.
     :joins        (into (sorted-map) (m/joins model))
     ;; 词条注册表（D2.26）。属性绑了词条之后，本地的 `:label` / `:description` /
     ;; `:format` / `:synonyms` 就被摘掉了——语义只有词条一个事实源。catalog 里若
     ;; 只带 `:term` 这个 ID 而不带注册表，调用方拿到的就是**一个指向不存在目的地
     ;; 的引用**：属性没有描述，ID 也查不到东西。合并语义于是止步在方言层，
     ;; 到不了消费端。
     ;;
     ;; **只登记本档位 catalog 里真有属性绑着的词条**。
     ;; 全量登记会让一个只绑在 `:restricted` 属性上的概念在低档 catalog 里现身，
     ;; 而 D5.3 的成员预言机防御要的正是「不够档 = 逐字节地不存在」——一个概念
     ;; 的**存在本身**同样是信息。
     :terms        (let [bound (into #{} (keep (comp :term val)) attrs)]
                     (into (sorted-map)
                           (filter (comp bound key))
                           (:terms model)))}))

;; --- 入口 -------------------------------------------------------------------

(defn- compile-mutation
  "One dialect mutation → a Pathom3 `pco/mutation`, or `[nil findings]`.

   Mirrors `compile-resolver` deliberately, gate for gate: an unresolvable
   source-ref, a source type with no registered connector, and a connector
   that refuses the write block are all **warnings that exclude this one
   mutation** — the rest of the model stays usable (principles). A write is
   not special enough to fail the whole compile that a read would not.

   The write itself is the connector's (`spi/compile-mutation`, implemented by
   sql-v1's `compile-write`); this function only binds it into the index and
   shapes the result. Nothing about authorization appears here: a caller can
   run a mutation exactly when the graph they hold a token for contains it
   (D5.13) — the grant IS the permission, so there is no second check to write."
  [model {:keys [id result_type source returns] :as mut}]
  (let [path    [:mutations id]
        src-ref (:source-ref mut)
        src-def (merge (get-in model [:sources src-ref]) source)]
    (cond
      (nil? (:type src-def))
      [nil [(finding :engine.compile/source-unresolved :warning path
                     (str "mutation " id " references unknown source-ref " src-ref)
                     :got src-ref)]]

      (not (spi/known-type? (:type src-def)))
      [nil [(finding :engine.compile/source-not-executable :warning path
                     (str "mutation " id " has source type " (pr-str (:type src-def))
                          " with no registered connector")
                     :got (:type src-def)
                     :refs ["principles: 执行与连接"])]]

      :else
      (try
        (let [write-fn (spi/compile-mutation src-def mut)
              ks       (when (seq returns) (allowed-keys returns))]
          [(pco/mutation
            (symbol id)
            {::pco/params (vec (keys (:params mut)))
             ::pco/output (if (seq returns) returns [])}
            (fn [env params]
              ;; `"none"` returns nothing to shape — sql-v1 says the connector
              ;; does not read a result set at all in that case, so an empty
              ;; map is the honest answer rather than an invented one.
              ;; E2: same trace binding as the read side.
              (let [r (spi/with-request-trace (:open.atlas.common.engine/trace env)
                        (write-fn params))]
                (if (= "none" result_type)
                  {:mutation/ok true}
                  (shape-result r result_type nil ks returns {} {})))))
           []])
        (catch Throwable e
          [nil [(finding :engine.compile/source-not-executable :warning path
                         (str "mutation " id " could not be compiled: " (.getMessage e))
                         :got (:type src-def))]])))))

(defn compile-model
  "The one compilation path (D5.11: the per-tier variants it once shared a
   body with are gone). `in-cat?` survives as `(constantly true)` because
   `compile-resolver` still takes the predicate — collapsing that parameter
   is safe now but touches every gate below, so it waits for its own pass."
  [model]
  (let [in-cat?   (constantly true)
        pairs     (mapv #(compile-resolver model in-cat? %) (:resolvers model))
        resolvers (into [] (keep first) pairs)
        ;; Mutations compile alongside resolvers and land in the SAME index —
        ;; Pathom keeps them apart itself, and a separate index would be a
        ;; second place for "what can this graph do" to be answered from.
        mut-pairs (mapv #(compile-mutation model %) (:mutations model))
        mutations (into [] (keep first) mut-pairs)
        findings  (into (into [] (mapcat second) pairs)
                        (mapcat second) mut-pairs)
        kept-ids  (into #{} (keep identity)
                        (map (fn [[r _] res] (when r (:id res))) pairs (:resolvers model)))
        kept      (filterv #(contains? kept-ids (:id %)) (:resolvers model))
        ;; 该档位可达的属性集：只有 index 里真有条目声称能供出的才进 catalog。
        reachable (into #{} (mapcat (fn [r] (m/output-leaves (m/prune-output (:output r) in-cat?))))
                        kept)
        reachable (into reachable (mapcat :input) kept)
        reachable (into reachable (mapcat (juxt :reference/from :reference/to))
                        (:semantic/conditional-references model))
        ;; The `:collection? true` special-case that used to sit here is **gone**
        ;; with D2.29, and so is the problem it patched. It existed because a
        ;; collection key was an attribute AND a navigation at once, so it fell
        ;; between `reachable` (leaves only) and `:navigations` — findable in
        ;; neither, and 「查不到」与「不存在」在消费端逐字相同. A collection is
        ;; now simply a join attribute whose `:target` is its own namespace, so
        ;; it is answered by `:joins` like every other join. One kind, one place.
        aliases   (into (compile-links model in-cat?) (compile-conditional-references model))
        env       (pci/register (into (into resolvers aliases) mutations))
        cat       (build-catalog model true reachable)]
    {:env         env
     :catalog     cat
     ;; entity（= namespace，principles「entity 由 namespace 推导」）→ 该档位
     ;; index 里可达的属性集。`*` 的展开取的就是这张表（impl.gate/expand-query）。
     ;; `:restricted` 不够档的属性在编译期就已经不在 catalog 里，因此天然不会被
     ;; 展开进来——「对这个 token 而言它整个不存在」（D5.3），此处不需要第二层特判。
     :attrs-by-entity (reduce-kv (fn [m a d] (update m (:entity d) (fnil conj #{}) a))
                                 {} (:attributes cat))
     ;; 每个 output key（含 join 根）→ 该 resolver 的参数/分页/排序声明，
     ;; 供 impl.gate 在**执行之前**校验调用方给的参数（D5.4 步骤 ②）。
     ;; The mutation declarations the write gate checks a call against. Keyed
     ;; by id string because that is what arrives on the wire; the value is the
     ;; dialect entry itself, so there is no second shape to keep in step.
     :mutation-decls (into {} (for [m (:mutations model)] [(:id m) m]))
     ;; Semantic contract 2.0 §6.4: the depth ceiling each recursive join
     ;; declares, read by `impl.gate` to clamp a request's recursion before
     ;; the planner sees it. The smallest declaration wins when two resolvers
     ;; declare one key. Empty for a 1.x model.
     :recursion-decls (apply merge-with min {} (map #(m/recursion-depths (:output %)) kept))
     ;; Semantic contract 2.0 §4: when several kept resolvers produce one key,
     ;; the PREFERRED one's declarations are the ones the gate checks a call
     ;; against -- `into {}` keeps the last writer, so order ascending by rank
     ;; (absent is 0; the sort is stable, so 1.x models keep their order).
     :param-decls (into {}
                        (for [r (sort-by #(or (:priority %) 0) kept)
                              :let [jr (m/join-root (:output r))
                                    src-type (:type (source-def-of model r))]
                              k (if jr [jr] (m/output-leaves (:output r)))]
                          [k {:resolver-id (:id r)
                              :eql-params  (:eql-params r)
                              :limit       (:limit r)
                              :order       (some-> (:order r)
                                                   (m/with-order-aliases (:field_mapping (source-def-of model r))))
                              :source-collection (:semantic/collection r)
                              ;; E1 / D5.14 ③: carried so `impl.gate` can refuse a
                              ;; **present but nil** row-security input without
                              ;; re-planning. Cut-time already proved this
                              ;; declaration is wired (`:rowsec.wiring/*`); what
                              ;; is left is the value, and only the caller's
                              ;; entity has that.
                              :row-security (:row-security r)
                              ;; The source type this key's rows come from, and
                              ;; whether it honours `:offset`. Computed here
                              ;; because compile is where the merged source
                              ;; config lives; `impl.gate` refuses on the flag
                              ;; and never parses a type name itself (8.17).
                              ;;
                              ;; The answer comes from the SPI's declared
                              ;; capability, so the engine names no type: sql
                              ;; and api declare `offset ✓`, derived declares
                              ;; nothing, and `"static"` — which has no SPI
                              ;; methods at all by design — lands on the same
                              ;; default-deny as any other type that never
                              ;; claimed it.
                              :source-type src-type
                              :offset-unsupported?
                              (or (some? (:semantic/collection r)) (not (spi/supports-offset? src-type)))}]))
     :findings    findings}))
