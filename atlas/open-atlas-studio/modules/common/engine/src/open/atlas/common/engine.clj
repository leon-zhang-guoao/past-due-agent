(ns open.atlas.common.engine
  "B1 引擎的唯一对外接口。跨模块只许 require 本 ns；
   `open.atlas.common.engine.impl.*` 视为私有。

   职责三件：**编译**（归一模型 → pathom env）、**档位物化**（同一张图对不同档位的
   token 呈现不同的可解析集合，D5.3）、**执法**（EQL 应答口那一道门，D5.4，
   外加 EQL 四道边界）。执法点在内核而不在 server（D1.6）：studio 与 server
   装载同一份，dev 上自测也走这里，没有隐式全权路径（D5.7）。

   engine **不依赖任何具体连接器**——只经 `open.atlas.common.connector.spi`
   的六个 multimethod 调 source（见 impl.spi 的 docstring），连接器由装配处注入。

   ## 公共接口与签名理由

   ```clojure
   (compile-env model)            ;; => compiled
   (compile-env model opts)
   (catalog compiled tier)        ;; => 该档位可见的 catalog
   (query compiled token request) ;; => 结果信封 或 结构化拒绝
   ```

   **`compile-env` 返回的不是一个 pathom env，是一个 `compiled`（三档各一份 env
   + 各一份 catalog）。** 这是签名上最要紧的一个决定：D5.3 要求按 (graph, 档位)
   键控物化，一个 pathom env 在结构上表达不了「同一张图对不同档位呈现不同的可解析
   集合」。若接口交出单个 env，调用方就只能在 env 之外再补一层过滤——那正是
   D5.4「执法点唯一」要防的第二处实现。v1 直接实现三个独立 index/catalog，
   是否共享底层不可变对象是纯物理优化，不改变任何外部可观察行为（D5.3 明说）。

   `opts`：`:limits`（EQL 四道边界的覆盖，默认见 impl.limits/defaults）。

   **`query` 的 token 位形参已退役**（D5.11：档位整套退役，一张图只编译一份
   env）。三参 arity 保留、实参忽略——纯粹是调用方迁移期的源兼容；执法在
   `instance/answer` 的 `token/verify`，engine 不再有第二道要判的东西。

   **`query` 的 `request` 收字符串或已 parse 的结构。** 第一道边界（请求文本 8 KB）
   只有拿到文本才查得动，「在能查得最便宜的那一点检查」要求接口能收文本；
   已 parse 的结构（进程内调用）跳过第一道并明说跳过，不假装量过。
   合法形状只有 EQL 向量与 `{:entity map :query vector}`，其余拒绝，
   **不做强制转换**（principles）。

   **`catalog` 只有一份**（D5.11）：图声明什么，catalog 就列什么。可选第二参
   同样是迁移期源兼容，忽略。

   ## 返回形状

   放行：`{:engine/status :ok :engine/data …}`
   拒绝：`{:engine/status :rejected :code :engine.* :severity :error :path … :message … …}`

   拒绝沿用 finding-v1 的形状精神（结构化、点名、不泄漏值，S5.3「永不记录值」），
   code 用 `:engine.*` 域，与方言的 finding code 不共用注册表。"
  (:require [clojure.string :as str]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.connect.planner :as pcp]
            [com.wsscode.pathom3.connect.runner :as pcr]
            [com.wsscode.pathom3.error :as p.error]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [open.atlas.common.engine.impl.model :as m]
            [open.atlas.common.engine.impl.compile :as c]
            [open.atlas.common.engine.impl.gate :as gate]
            [open.atlas.common.engine.impl.limits :as lim]
            [open.atlas.common.engine.impl.spi :as spi]))

(def ^:dynamic *result-byte-budget*
  "Trusted host transport budget for this call, never an EQL parameter.
   MCP can retain larger results server-side. Other boundaries remain intact;
   nil preserves the compiled result limit for ordinary EQL callers."
  nil)

(def ^:dynamic *execution-budget-ms*
  "Trusted host execution budget for this call, never an EQL parameter, and it
   can only TIGHTEN — `min` with the graph's own `:execution-ms`, never max.

   Doc 107 §107.7 made the whole-query budget an absolute instant that rides
   `::trace` down to every connector, where each attempt is narrowed to
   `min(:timeout_ms, remaining)`. A caller that has LESS time than the graph
   allows — MCP's analysis deadline (doc 108 §108.5 item 6) is the one today —
   needs that instant computed from its own remaining budget, or the connectors
   keep attempting against a deadline that outlives the call waiting on them.
   Binding it here means there is one deadline channel, not a second one racing
   the first. nil leaves the compiled limit alone."
  nil)

(def ^:private ^:const default-compile-cache-size 64)

(def ^:private compile-cache
  "Bounded completed-result LRU plus unbounded-only-while-running single-flight
   entries. Keys include the connector registry version, so loading, replacing
   or removing a connector cannot reuse a bundle compiled against old methods."
  (atom {:entries {}
         :order   []
         :in-flight {}}))

(defn- touch [order cache-key]
  (conj (vec (remove #(= % cache-key) order)) cache-key))

(defn- complete-flight [cache-key flight bundle]
  (swap! compile-cache
         (fn [{:keys [entries order in-flight] :as state}]
           (if (identical? flight (get in-flight cache-key))
             (let [next-order   (touch order cache-key)
                   overflow     (- (count next-order) default-compile-cache-size)
                   evicted      (take (max 0 overflow) next-order)
                   next-entries (reduce dissoc (assoc entries cache-key bundle) evicted)]
               (assoc state
                      :entries next-entries
                      :order (vec (drop (max 0 overflow) next-order))
                      :in-flight (dissoc in-flight cache-key)))
             state)))
  bundle)

(defn- abandon-flight [cache-key flight]
  (swap! compile-cache
         (fn [{:keys [in-flight] :as state}]
           (if (identical? flight (get in-flight cache-key))
             (assoc state :in-flight (dissoc in-flight cache-key))
             state))))

(defn- compile-base
  [model disable-cache?]
  (if disable-cache?
    (c/compile-model model)
    (let [cache-key [(spi/registry-version) model]
          candidate (delay (c/compile-model model))
          selected  (volatile! nil)]
      (swap! compile-cache
             (fn [{:keys [entries order in-flight] :as state}]
               (if-let [bundle (get entries cache-key)]
                 (do (vreset! selected [:complete bundle])
                     (assoc state :order (touch order cache-key)))
                 (let [flight (or (get in-flight cache-key) candidate)]
                   (vreset! selected [:flight flight])
                   (assoc-in state [:in-flight cache-key] flight)))))
      (let [[kind value] @selected]
        (if (= :complete kind)
          value
          (try
            (complete-flight cache-key value @value)
            (catch Throwable t
              (abandon-flight cache-key value)
              (throw t))))))))

(defn compile-env
  "归一模型（B0 `read-dialect` 的产物）→ `compiled`。

   `compiled` 的形状：
   ```clojure
   {:engine/limits   {四道边界}
    :engine/bundle   {:env pathom-env :catalog … :param-decls …}
    :engine/findings [finding…]}
   ```
   （`:engine/by-tier` 仍指向同一个 bundle——调用方迁移期的源兼容，没有第二份 env。）
   `:engine/findings` 收「跑不了的」——没有连接器、source-ref 指不着、
   `:result_type \"list\"` 却没有锚。**不因此拒绝整个模型**（principles）：
   那些 resolver 排除出编译，模型其余部分照常可用。

  （The former `:authoring? true` option is gone with the tier axis, D5.11:
  there is exactly one compiled view, and it IS the modeller's full view —
  `:listed?`/catalog narrowing are consumer-presentation concerns handled
  above this layer, not compile-time prunes.）"
  ([model] (compile-env model {}))
  ([model {:keys [limits disable-compile-cache]}]
   (let [bundle (compile-base model disable-compile-cache)]
     {:engine/limits   (lim/resolve-limits (or limits {}))
      :engine/model    model
      :engine/joins    (m/joins model)
      :engine/bundle   (dissoc bundle :findings)
      ;; Temporary source compatibility for Studio's explain surface. Both keys
      ;; point at the exact same env; there are no access-tier variants.
      :engine/by-tier  {:authoring (dissoc bundle :findings)}
      :engine/findings (:findings bundle)})))

(defn compile-complete
  "Strict candidate compilation using the SAME compiler as compile-env.
   A compiler finding can mean a declared capability was excluded, so no
   executable candidate is returned in that case. Legacy compile-env behavior
   is unchanged. Input must already be a validated normalized model; this is
   not a semantic reader, authorization check or publication operation."
  ([model] (compile-complete model {}))
  ([model options]
   (let [compiled (compile-env model options)
         findings (:engine/findings compiled)]
     (if (seq findings)
       {:findings findings}
       {:compiled compiled :findings []}))))

(defn catalog
  "The graph's single public catalog. The optional second argument is ignored
   for source compatibility while callers migrate away from access tiers."
  ([compiled] (get-in compiled [:engine/bundle :catalog]))
  ([compiled _] (catalog compiled)))

(defn- normalize-request
  "EDN 用 clojure.edn 无 tag handler 读；不是 EQL vector 或
   `{:entity map :query vector}` 就拒绝，**不做强制转换**（principles）。"
  [request limits]
  (let [parsed (if (string? request)
                 (if-let [over (lim/over-request-bytes request limits)]
                   {:reject (gate/limit-exceeded over)}
                   (try {:value (lim/read-request request)}
                        (catch Throwable _
                          {:reject (gate/reject :engine.request/unreadable [:request]
                                                "request is not readable as EDN without reader tags"
                                                :refs ["principles: 执行与连接"])})))
                 {:value request})]
    (cond
      (:reject parsed) parsed
      (vector? (:value parsed)) {:entity {} :query (:value parsed)}
      (and (map? (:value parsed))
           (map? (:entity (:value parsed)))
           (vector? (:query (:value parsed))))
      (select-keys (:value parsed) [:entity :query])
      :else
      {:reject (gate/reject :engine.request/malformed [:request]
                            "request must be an EQL vector or {:entity map :query vector}"
                            :refs ["principles: 执行与连接"])})))

(defn mutation?
  "这条 EQL 是不是一次写？——EQL 的调用语法 `(ns/verb {…})`，即某个节点是个
   头为 symbol 的 seq。

   **公开的**（8.4.12 施工，2026-08-31）：Builder 侧的 draft 工具面要把读和写
   分成两个工具，于是需要同一个判定。在别处另写一份，就是「一个东西两份实现」;
   而这条判定错了的后果不是显示不一致，是**一次写被当成读放行**。

   判定只看**形状**，不看这次调用合不合法——声明存不存在、参数对不对，是
   `impl.gate/check-mutation` 在执行前的事。"
  [query]
  (some (fn [node] (and (seq? node) (symbol? (first node)))) query))

(defn- connector-failure
  "The connector's own failure under this throwable, if there is one.

   Pathom wraps what a resolver throws, so the `:error/category` a connector
   declared (`spi/fail!`) is usually one or more causes down — reading only the
   top throwable's ex-data reported every wrapped failure as the `:source`
   default. Walk the chain, like `planner-unreachable` does, and take the first
   throwable that declares a category."
  [^Throwable t]
  (loop [x t]
    (when x
      (if (:error/category (ex-data x))
        x
        (recur (.getCause x))))))

(defn- execution-message
  "The refusal's message for a failed execution.

   `:connection` carries the connector's own wording (8.4.18 裁定十二 ②):
   an unresolved `${VAR}` names the variable, and a variable NAME is
   configuration in the dialect, not a credential value — while `spi/fail!`
   guarantees statements, URLs and row values never enter a message at all,
   and deliberately drops the driver's cause so driver text cannot travel
   either. Without it, «connection failed» is a dead end: nothing else in the
   refusal says which setting is missing.

   Every other category keeps the flat sentence."
  [failure category]
  (or (:error/safe-reason (ex-data failure))
      (when (= :connection category)
        (some->> (some-> failure ex-message) (str "source execution failed: ")))
      "source execution failed"))

(defn- missing-attributes
  "The attributes a resolver was asked for and did not supply, if that is what
   this throwable (or any cause under it) says — sorted, or nil.

   Pathom's strict mode raises `::p.error/attribute-missing` when a resolver
   RAN and returned nil (a by-key read that matched no row). That is not an
   execution failure: nothing broke, the row simply is not there
   (0.2 release note defect 11). The caller of this function still has to
   check that no connector under the same throwable declared a failure —
   `connector-failure` wins, because a source that threw is an outage no
   matter which attribute went missing on the way out."
  [^Throwable t]
  (loop [t t]
    (when t
      (let [d (ex-data t)]
        (if (= :com.wsscode.pathom3.error/attribute-missing
               (:com.wsscode.pathom3.error/cause d))
          (some->> (keys (:com.wsscode.pathom3.connect.runner/attributes-missing d))
                   seq sort vec)
          (recur (.getCause t)))))))

(defn- root-attributes
  "The attribute keywords this query asks for AT THE ROOT — plain keys and
   join keys alike. Used to tell «the anchor row is not there» (every missing
   attribute hangs off the root) from a miss deeper inside a join, where the
   root data really did resolve and answering `{}` would throw it away."
  [query]
  (into #{}
        (keep (fn [node]
                (cond (keyword? node) node
                      (map? node)     (let [k (ffirst node)]
                                        (when (keyword? k) k)))))
        query))

(def ^:private attribute-errors-key
  :com.wsscode.pathom3.connect.runner/attribute-errors)

(defn- lenient-issues
  "Turn Pathom's internal attribute-error maps into a stable, value-free
   engine envelope. Only model attribute names and projection paths leave the
   engine; connector exceptions and returned values do not."
  ([x] (lenient-issues x []))
  ([x path]
   (cond
     (map? x)
     (into (vec (for [attribute (sort (keys (get x attribute-errors-key {})))]
                  {:code :engine.execution/partial
                   :severity :error
                   :path (conj path attribute)
                   :message "attribute resolution failed in lenient mode"}))
           (mapcat (fn [[k v]]
                     (when-not (= k attribute-errors-key)
                       (lenient-issues v (conj path k)))))
           x)

     (sequential? x)
     (into [] (mapcat (fn [[i v]] (lenient-issues v (conj path i))))
           (map-indexed vector x))

     :else [])))

(defn- generated-resolver-id
  "The dialect resolver id behind a Pathom op-name, or nil for anything the
   engine did not generate from a model entry (aliases, conditional references)."
  [op-name]
  (when (and (symbol? op-name) (= "open.atlas.common.engine.generated" (namespace op-name)))
    (name op-name)))

(defn- or-node-fallbacks
  "Semantic contract 2.0 §4.2 from ONE run-stats map: for every OR node whose
   runner took more than one path, the fallback that happened and the failure
   that caused it.

   Pathom's runner tries the branches of an OR node in `::pco/priority` order
   and moves on when a branch throws OR answers nothing (measured on pathom3
   2025.01.16-alpha: a throw, a `nil` and an `{}` all fall through, in strict
   mode as in lenient). It records the walk as `::pcr/taken-paths` /
   `::pcr/success-path` on the OR node and the thrown error as
   `::pcr/node-error` on the branch -- so everything the contract wants said
   is already in the stats and nothing wraps a resolver to say it twice.

   Two kinds of issue, both value-free, one per contested attribute the
   fallback answered at `path` (see `contested` below):
   - `:engine.execution/fallback` (`:info`): which resolver answered and which
     were tried before it -- ids are model names, not data.
   - `:engine.execution/failed` (`:error`) for each tried branch that THREW:
     the preferred resolver's failure stays on record «by the 1.x rule» -- a
     fallback that silently absorbed a dead source would hide exactly what
     operations needs to see. A branch that merely answered nothing (no row)
     leaves no failure: a miss is an answer, not a fault."
  [stats path]
  (let [nodes (::pcp/nodes stats)
        run   (let [s (::pcr/node-run-stats stats)] (if (instance? clojure.lang.IDeref s) @s s))
        id-of (fn [n] (generated-resolver-id (get-in nodes [n ::pco/op-name])))
        ;; The attributes this plan had MORE THAN ONE candidate for. The
        ;; planner builds one OR node per requested attribute, runs the
        ;; fallback on the first it reaches and skips the rest once the
        ;; winning branch's output has satisfied them -- so the OR node that
        ;; ran names one attribute while the fallback answered several. What
        ;; it answered is the winner's output, restricted to the contested
        ;; attributes; one the plan reached through a single resolver was no
        ;; fallback and is not reported as one.
        contested (into #{} (keep (fn [[a node-ids]] (when (> (count node-ids) 1) a)))
                        (::pcp/index-attrs stats))]
    (for [[nid s] run
          :when (and (map? s) (> (count (::pcr/taken-paths s)) 1))
          :let [taken    (::pcr/taken-paths s)
                success  (::pcr/success-path s)
                chosen   (id-of success)
                tried    (mapv id-of (remove #{success} taken))
                answered (let [out (get-in run [success ::pcr/node-resolver-output])]
                           (when (map? out) (filter contested (keys out))))]
          :when (and chosen (every? some? tried))
          attribute (sort (or (seq answered)
                              (into #{} (mapcat #(keys (::pcp/expects (get nodes %)))) (cons nid taken))))
          issue (concat
                 (for [n (remove #{success} taken)
                       :let [err (get-in run [n ::pcr/node-error])]
                       :when err]
                   {:code :engine.execution/failed :severity :error
                    :path (conj path attribute)
                    :message (str "resolver " (id-of n) " failed; the planner fell back to the next candidate")
                    :resolver-id (id-of n)
                    :error/category (or (:error/category (::p.error/error-data err)) :source)})
                 [{:code :engine.execution/fallback :severity :info
                   :path (conj path attribute)
                   :message (str "answered by resolver " chosen " after " (str/join ", " tried))
                   :resolver-id chosen
                   :tried tried}])]
      issue)))

(defn- fallback-issues
  "Contract 2.0 §4.2 over a whole Pathom result: the run-stats of the root and
   of every nested row (a collection's rows carry their own plan and stats),
   with the same ordinal paths `lenient-issues` uses so the two kinds of issue
   point at the same place."
  ([x] (fallback-issues x []))
  ([x path]
   (cond
     (map? x)
     (into (vec (some-> (meta x) ::pcr/run-stats (or-node-fallbacks path)))
           (mapcat (fn [[k v]]
                     (when-not (= k attribute-errors-key)
                       (fallback-issues v (conj path k)))))
           x)

     (sequential? x)
     (into [] (mapcat (fn [[i v]] (fallback-issues v (conj path i))))
           (map-indexed vector x))

     :else [])))

(defn- level-inputs
  "The resolver inputs Pathom ran at one nested level, from that level's
   run-stats -- the identities of the record this level describes, as the
   planner saw them. Recorded even for a cached call, so a repeat is visible
   whether or not the resolver body ran again (measured on 2025.01.16-alpha)."
  [x]
  (let [stats (some-> (meta x) ::pcr/run-stats)
        run (let [r (::pcr/node-run-stats stats)] (if (instance? clojure.lang.IDeref r) @r r))]
    (into #{} (keep #(let [in (::pcr/node-resolver-input %)] (when (seq in) in))) (vals (or run {})))))

(defn- cut-cycles
  "Semantic contract 2.0 §6.4: a record that already appeared on the SAME
   recursion path (the chain of recursive joins from the root) is a cycle. Its
   recursive joins are cut -- `[]` for a collection, `nil` for a single -- and
   an `:engine.execution/recursion-cycle` info issue names the place. The depth
   ceiling already bounded the walk; this makes the loop visible instead of
   letting it fill the ceiling with repeats. `seen` accumulates only along
   recursive joins; an ordinary join starts a fresh path. Returns `[data issues]`."
  [x rec-keys path seen]
  (cond
    (map? x)
    (let [ids (level-inputs x)]
      (if (some seen ids)
        [(reduce (fn [m k] (if (contains? m k) (assoc m k (if (sequential? (get m k)) [] nil)) m)) x rec-keys)
         (for [k (sort rec-keys) :when (contains? x k)]
           {:code :engine.execution/recursion-cycle :severity :info :path (conj path k)
            :message "this record already appears on this recursion path; the walk stops here"
            :refs ["ATLAS-SEMANTIC-CONTRACT-V2 §6.4"]})]
        (reduce-kv (fn [[m issues] k v]
                     (if (or (map? v) (sequential? v))
                       (let [[v' more] (cut-cycles v rec-keys (conj path k) (if (rec-keys k) (into seen ids) #{}))]
                         [(assoc m k v') (into issues more)])
                       [m issues]))
                   [x []] x)))

    (sequential? x)
    (let [rs (map-indexed (fn [i c] (cut-cycles c rec-keys (conj path i) seen)) x)]
      [(with-meta (mapv first rs) (meta x)) (into [] (mapcat second) rs)])

    :else [x []]))

(defn- strip-attribute-errors
  "Drop Pathom's `::pcr/attribute-errors` bookkeeping at every level.

   The not-found answer is produced by re-running the same query in lenient
   mode (see `query`), and lenient mode writes its per-attribute errors INTO
   the data. Those keys are Pathom's vocabulary, not the graph's: letting them
   out would put engine internals on the wire under a key no contract names."
  [x]
  (cond
    (map? x)        (into {} (keep (fn [[k v]]
                                     (when-not (= k attribute-errors-key)
                                       [k (strip-attribute-errors v)])))
                          x)
    (sequential? x) (mapv strip-attribute-errors x)
    :else           x))

(defn- planner-unreachable
  "Pathom's planner exception, if this throwable (or any cause under it) is one.

   The planner throws BEFORE anything executes (`verify-plan!`,
   `:com.wsscode.pathom3.error/cause :attribute-unreachable`), so reporting it
   as \"source execution failed\" sends the caller to debug SQL and
   connections when the truth is \"this serving graph has no resolver path to
   the attribute\" — two agent sessions burned a dozen retries on exactly that
   misdirection against demo-ch (2026-08-31). Walk the cause chain because
   Pathom wraps errors when nested under joins.

   Returns the sorted attribute keywords the plan could not reach, or nil."
  [^Throwable t]
  (loop [t t]
    (when t
      (let [d (ex-data t)]
        (if (= :com.wsscode.pathom3.error/attribute-unreachable
               (:com.wsscode.pathom3.error/cause d))
          (->> (keys (:com.wsscode.pathom3.connect.planner/unreachable-paths d))
               sort vec)
          (recur (.getCause t)))))))

(defn- isolated-batch-results
  "Consume typed SPI failures before exposing data. Keep input records while
   omitting failed attributes. Findings carry value-free paths; data-side row
   outcomes retain exact paths so a consumer can correlate its input rows."
  [data]
  (let [issues (atom []) outcomes (atom [])]
    (letfn [(visit [x path safe-path]
              (cond
                (map? x)
                (let [failures (filter (comp spi/batch-item-failure? val) x)]
                  (doseq [[info entries] (group-by (comp spi/batch-item-failure-info val) failures)]
                    (let [attrs (vec (sort (map key entries)))]
                      (swap! issues conj (merge info {:code :engine.execution/batch-input-failed
                                                     :severity :error :path safe-path :attributes attrs
                                                     :message "Detail retrieval failed for this input; not a not-found result"}))
                      (swap! outcomes conj (merge info {:path path :status :source-error :attributes attrs}))))
                  (with-meta (into {} (keep-indexed
                             (fn [i [k v]]
                               (when-not (spi/batch-item-failure? v)
                                 [k (visit v (conj path k)
                                           (if (vector? k) (into safe-path [:idents i]) (conj safe-path k)))])) x)) (meta x)))
                (sequential? x)
                (with-meta (mapv (fn [i v] (visit v (conj path i) (conj safe-path i))) (range) x) (meta x))
                :else x))]
      {:data (visit data [] []) :issues @issues :outcomes @outcomes})))

(defn- recursion-form-of
  "Does `query` ask to recurse into join `k` (`{k n}` or `{k ...}`) anywhere?"
  [query k]
  (some (fn [node]
          (cond
            (map? node) (some (fn [[mk v]]
                                (let [mk (if (seq? mk) (first mk) mk)]
                                  (or (and (= mk k) (or (integer? v) (= '... v)))
                                      (and (vector? v) (recursion-form-of v k)))))
                              node)
            (seq? node) (recursion-form-of [(first node)] k)
            :else nil))
        (when (vector? query) query)))

(defn- query*
  "env + token + EQL → 结果或结构化拒绝。

   判定顺序按 D5.4，**不可换**：
   ① 取该 token 档位的 catalog 与 index（编译期已物化，见 impl.compile）
   ② 用裁剪后的 catalog 校验查询里点名的每个属性（impl.gate/check-query）
   ③ 规划与执行，wildcard 展开按档位过滤（impl.gate/scrub）

   EQL 四道边界的检查点（各自在能查得最便宜的那一点，超限即拒绝而非截断）：
   请求文本在 parse **之前**、深度在 parse 之后规划**之前**、执行时限包住
   pathom 的那一次调用、序列化结果在档位过滤**之后**（离开平台的是过滤后的那份）。

   Fourth arg `opts` (doc 89 Phase 1, both new keys default off — omitting
   `opts` entirely keeps the return shape byte-identical to before either
   existed):

   - `:lenient?` sets Pathom3's `::p.error/lenient-mode?` on the tier's env
     for this call only (the compiled env itself is never mutated): a failed
     resolver leaves a per-attribute error in its place instead of throwing
     and losing the whole answer. Off by default — an error is an error
     unless the caller asked to see around it.
   - `:trace` `{:request-id … :graph-id …}` rides the env down to the
     connectors for this call only, so a source system can tag the statement it
     receives (E2). Engine does not mint the ids and does not know about
     `runtime/obs` — it carries what the caller hands it.
   - `:stats?` additionally returns `:engine/stats`, the **unscrubbed** Pathom
     result with its `::pcr/run-stats` metadata intact at every nested join
     level (Pathom3 attaches this by default — `:or {omit-run-stats? false}`
     in `com.wsscode.pathom3.connect.runner` — so this costs nothing beyond
     not discarding what already ran). A caller builds a plan/trace DTO from
     it. Typed isolated-error values are removed from the result body while
     its run-stats metadata is preserved. Engine itself does not know what a plan DTO looks like, the same way
     it does not know what a catalog UI looks like.

   - `:caller` `{:open-atlas.caller/email <string>}` — values the DATA PLANE vouches
     for (D1.9b), never the request. The request may not write the reserved
     `:open-atlas.caller/*` namespace at all; these values join the entity only
     after the request has passed step ②, so the gate never mistakes them for
     caller-supplied input, and the row-security check and resolvers see them.

   The token argument is IGNORED (tier retired, D5.11) — the arity survives
   only so callers can migrate without a flag day."
  ([compiled request] (query* compiled nil request {}))
  ([compiled token request] (query* compiled token request {}))
  ([compiled _ request {:keys [lenient? stats? trace caller]}]
   (let [limits (cond-> (:engine/limits compiled)
                  (and (integer? *result-byte-budget*) (pos? *result-byte-budget*))
                  (assoc :result-bytes *result-byte-budget*)
                  ;; The tighter of the two wins, always. A host budget can
                  ;; shorten a graph's execution window; it must never widen
                  ;; one, which would make the graph author's declared limit
                  ;; advisory (D1.6: a boundary nobody enforces is a boundary
                  ;; that first takes effect in production).
                  (and (integer? *execution-budget-ms*) (pos? *execution-budget-ms*))
                  (update :execution-ms min *execution-budget-ms*))
         req    (normalize-request request limits)]
     (or (:reject req)
         (gate/check-caller-values caller)
         (let [{:keys [entity query]} req
               ;; What resolvers and the row-security check see; the gate's
               ;; caller-supplied checks keep looking at `entity` alone.
               served (merge entity caller)]
           (or
                ;; 第二道边界：深度。树已经在手上，走一遍是常数级；规划一棵 40 层的树不是。
                (some-> (lim/over-depth query limits) gate/limit-exceeded)

                ;; **一个 mutation 只跑它自己。** 混在读查询里的写，其读的
                ;; 那一半是在写之前还是之后看到的世界，取决于规划器——同一个
                ;; 请求两次可以给出两个答案。拒绝而不是替调用方定序
                ;; （principles：拒绝而非猜测）。
                (when (and (mutation? query) (not= 1 (count query)))
                  (gate/reject :engine.request/mutation-not-alone [:query]
                               (str "a mutation must be the only thing in its request; "
                                    "read what it returns, then query separately")
                               :refs ["principles: 模型与方言"]))

                ;; The write half of D5.4 step ②, checked against the
                ;; mutation's own declaration (D2.18) before anything reaches
                ;; a connector.
                (gate/check-mutation (:mutation-decls (:engine/bundle compiled)) query)

                (let [bundle (:engine/bundle compiled)
                      [q expanded] (gate/expand-query
                                    query
                                    {:attrs-by-entity (:attrs-by-entity bundle)
                                     :joins           (:engine/joins compiled)
                                     :entity          entity})
                      ctx    (assoc bundle :joins (:engine/joins compiled))]
                  (or
                   ;; 步骤 ②
                   (gate/check-reserved-caller ctx entity q expanded)
                   (gate/check-query ctx entity q expanded)
                   ;; 步骤 ② 的行级安全那半条（E1 / D5.14 ③）。分开一个函数
                   ;; 而不是并进 check-query：前者判「这次请求说得通吗」，
                   ;; 这一条判「行级切分的那个值真的在吗」——同一个门，两条
                   ;; 不同的理由，混在一起下次改哪条都要重读全部。
                   (gate/check-row-security ctx served q expanded)
                   ;; 步骤 ② 的递归那半条（2.0 §6.4）：深度夹到声明值，未声明
                   ;; 递归的 join 不许递归。
                   (let [clamped (gate/clamp-recursion q (:recursion-decls bundle))]
                     (when (= :rejected (:engine/status clamped)) clamped))
                   ;; 步骤 ③
                   (let [clamped (gate/clamp-recursion q (:recursion-decls bundle))
                         q (:query clamped)
                         depth-applied (:applied clamped)
                         ;; The recursive joins this request walks; empty for
                         ;; every 1.x request, which then skips the cycle pass.
                         rec-keys (into #{} (keep (fn [k] (when (recursion-form-of q k) k)))
                                        (keys (:recursion-decls bundle)))
                         union-issues (atom [])
                         ;; D6.3 / doc 107 §107.7: the whole-query budget becomes an
                         ;; ABSOLUTE instant here -- the one place that knows both
                         ;; the limit and when the clock started -- and rides the
                         ;; trace channel down to every connector call. As a
                         ;; duration it would be re-read as "you may have this
                         ;; long" at each hop and three hops would silently get
                         ;; three budgets.
                         trace (cond-> (or trace {})
                                 (:execution-ms limits)
                                 (assoc :deadline-at (+ (System/currentTimeMillis)
                                                        (long (:execution-ms limits)))))
                         env (cond-> (:env bundle)
                               lenient? (assoc ::p.error/lenient-mode? true)
                               ;; 2.0 §6.4: the sink a resolver reports an
                               ;; ambiguous union value into, per call.
                               true (assoc ::union-issues union-issues)
                               ;; E2: correlation ids for source-system tagging,
                               ;; passed through per call (the compiled env is
                               ;; never mutated). engine does not mint them and
                               ;; does not know about runtime/obs -- it carries
                               ;; what the caller (instance/answer, D3.18) hands
                               ;; it down to the connectors on the pathom env.
                               (seq trace) (assoc ::trace trace))
                         r   (lim/with-deadline
                               limits
                               #(if (seq served)
                                  (p.eql/process env served q)
                                  (p.eql/process env q)))]
                     (case (first r)
                       :timeout (gate/limit-exceeded (second r))
                       :error   (let [t (second r)]
                                  (if-let [attrs (planner-unreachable t)]
                                    ;; 规划失败：没有任何连接器被调用。它与执行
                                    ;; 故障是两种病——报成一种会把调用方引去查
                                    ;; 一条从未发出的 SQL（principles：静默的降级
                                    ;; 让调用方对着少掉的东西推理出错误结论）。
                                    ;; 属性名是模型对象的名字，不是数据，可以点名
                                    ;; （与 unknown-attribute 同则；档位已退役,
                                    ;; catalog 即图的全部声明，无差分泄漏面）。
                                    ;; 8.4.21 裁定十五 ②: the FIRST sentence is the
                                    ;; user's own question, not the engine's
                                    ;; vocabulary — an agent that can only quote
                                    ;; this line has to be able to say what is
                                    ;; wrong. The engine words stay after it, and
                                    ;; `:code` / `:path` / `:refs` do not move.
                                    (gate/reject :engine.request/unreachable [:query]
                                                 (str "these attributes cannot be reached from "
                                                      "where this query starts: nothing on the "
                                                      "starting entity points at them, so no data "
                                                      "was read. No resolver path reaches them in "
                                                      "this graph")
                                                 :attributes attrs
                                                 :refs ["D5.4"])
                                    (if-let [missing (let [failure (connector-failure t)]
                                                       (when (and (not failure) (seq entity))
                                                         (let [m (missing-attributes t)]
                                                           (when (and m (every? (root-attributes q) m))
                                                             m))))]
                                      ;; **A row that is not there is an answer,
                                      ;; not a failure** (0.2 release note defect
                                      ;; 11). A by-key read whose resolver found
                                      ;; no row returns nil; Pathom's strict mode
                                      ;; turns that into `attribute-missing`, and
                                      ;; reporting it as `:engine.execution/failed`
                                      ;; sent the caller to debug a database that
                                      ;; is perfectly healthy. The requested
                                      ;; attributes are simply absent, and the
                                      ;; envelope says which anchor found nothing.
                                      ;;
                                      ;; Re-run in lenient mode rather than answer
                                      ;; `{}`: whatever DID resolve for this anchor
                                      ;; must survive, and dropping it silently is
                                      ;; the degradation the principles forbid.
                                      ;; Only the miss path pays for the second
                                      ;; pass, and a miss is the cheap case.
                                      (let [r2 (lim/with-deadline
                                                 limits
                                                 #(p.eql/process
                                                   (assoc env ::p.error/lenient-mode? true)
                                                   entity q))
                                            data (if (= :ok (first r2))
                                                   (strip-attribute-errors (second r2))
                                                   {})]
                                        {:engine/status    :ok
                                         :engine/data      data
                                         ;; Names only, never the value looked up
                                         ;; (S5.3): the caller supplied the value
                                         ;; and does not need it echoed.
                                         :engine/not-found {:anchor     (vec (sort (keys entity)))
                                                            :attributes missing}})
                                      (let [failure (connector-failure t)
                                          category (:error/category (ex-data failure) :source)]
                                      (if-let [limit (spi/failure-limit failure)]
                                        ;; doc 107 §107.6: a declared bound a
                                        ;; connector hit is the SAME refusal as
                                        ;; one of the four EQL boundaries -- the
                                        ;; request is rejected, not truncated,
                                        ;; and the caller is told which bound.
                                        ;; Reusing `gate/limit-exceeded` keeps
                                        ;; that one shape instead of minting a
                                        ;; second one per subsystem.
                                        (gate/limit-exceeded limit)
                                        (gate/reject :engine.execution/failed [:query]
                                                   ;; 故障不携带业务数据（principles）：
                                                   ;; 只放连接器自己声明的分类，绝不放语句/URL/行值。
                                                   ;; `:connection` 例外的只是**措辞**（8.4.18 裁定十二 ②）:
                                                   ;; 连接器的 message 只含配置层面的短语——未解析的
                                                   ;; `${VAR}` 是变量**名**，那是方言里的配置不是凭证值,
                                                   ;; 而 `spi/fail!` 的契约已经保证语句、URL、行值从不
                                                   ;; 进 message。少了它，「连不上」与「哪个变量没配」
                                                   ;; 之间没有任何线索，调用方只能去猜。
                                                   (execution-message failure category)
                                                   :error/category category
                                                   :error/statement-status (:error/statement-status (ex-data failure))))))))
                       :ok    (let [[raw cycles] (if (seq rec-keys)
                                                     (cut-cycles (second r) rec-keys [] #{})
                                                     [(second r) []])
                                      isolated   (isolated-batch-results (if lenient? (strip-attribute-errors raw) raw))
                                      issues     (into (vec (when lenient? (lenient-issues raw))) (:issues isolated))
                                      ;; Contract 2.0 §4.2: a fallback is reported,
                                      ;; not partiality -- the answer is complete,
                                      ;; it just came from the next candidate. So
                                      ;; these ride `:engine/issues` without turning
                                      ;; `:engine/partial?` on; the preferred
                                      ;; resolver's own failure travels with them.
                                      fallbacks  (into (fallback-issues raw) cycles)
                                      ;; 2.0 §6.4: an ambiguous union value IS
                                      ;; partiality -- the value is withheld.
                                      issues     (into issues @union-issues)
                                      data       (:data isolated)
                                      serialized (pr-str (if (seq (:outcomes isolated))
                                                           {:data data :issues issues :row-outcomes (:outcomes isolated)} data))]
                                  (or (some-> (lim/over-result-bytes serialized limits)
                                              gate/limit-exceeded)
                                      ;; No `:engine/filtered-attributes`: with access tiers
                                      ;; retired (D5.11) a released response never has anything
                                      ;; filtered out of it, and a key that is always empty reads
                                      ;; as "there is still a door that picks and chooses"
                                      ;; (owner ruling 2026-08-28, D7.12).
                                      (cond-> {:engine/status :ok
                                               :engine/data data}
                                        stats? (assoc :engine/stats (if (seq (:outcomes isolated))
                                                                      (with-meta data (meta raw)) raw))
                                        (seq (:outcomes isolated)) (assoc :engine/row-outcomes (:outcomes isolated))
                                        (seq issues) (assoc :engine/partial? true
                                                            :engine/issues issues)
                                        (seq fallbacks) (update :engine/issues (fnil into []) fallbacks)
                                        ;; 2.0 §6.4: the one permitted clamp, reported.
                                        (seq depth-applied) (assoc :recursion/depth-applied depth-applied))))))))))))))


(defn query
  "Shared query entry with semantic collection evidence and fail-closed bounds.
   Legacy models have no collection declarations and keep their exact response."
  ([compiled request] (query compiled nil request {}))
  ([compiled token request] (query compiled token request {}))
  ([compiled token request opts]
   (let [raw-answer (query* compiled token request opts)
         answer (cond-> raw-answer
                  (= :ok (:engine/status raw-answer))
                  (assoc :engine/result-scope :returned-data
                         :engine/completeness (if (:engine/partial? raw-answer) :partial :unknown))
                  (and (= :ok (:engine/status raw-answer))
                       (or (sequential? (:engine/data raw-answer))
                           (some #(and (map? %) (some sequential? (vals %)))
                                 (tree-seq coll? seq (:engine/data raw-answer)))))
                  (assoc :engine/alerts
                         [{:code :result/collection-coverage-unknown
                           :message "Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows."}]))
         declarations (into {} (keep (fn [[a d]] (when (:source-collection d) [a (:source-collection d)])))
                            (get-in compiled [:engine/bundle :param-decls]))]
     (if (or (empty? declarations) (not= :ok (:engine/status answer))) answer
       (let [evidence (atom {}) over (atom nil)]
         (letfn [(visit [value path]
                   (cond
                     (map? value)
                     (doseq [[a v] value]
                       (when-let [c (get declarations a)]
                         (let [n (if (sequential? v) (count v) 0)
                               size (alength (.getBytes (pr-str v) "UTF-8"))
                               bounds (:bounds c)]
                           (when (or (> n (:max-items bounds)) (> size (:max-response-bytes bounds)))
                             (reset! over (conj path a)))
                           (swap! evidence assoc (conj path a)
                                  {:ordering :source-defined :paging :none
                                   :completeness :not-guaranteed :returned-count n})))
                       (visit v (conj path a)))
                     (sequential? value)
                     (doseq [[i v] (map-indexed vector value)] (visit v (conj path i)))))]
           (visit (:engine/data answer) []))
         (if @over
           (gate/reject :engine.limit/exceeded @over "Source collection exceeded its declared bounds; no partial result is returned")
           (cond-> answer (seq @evidence) (assoc :engine/collections @evidence))))))))
