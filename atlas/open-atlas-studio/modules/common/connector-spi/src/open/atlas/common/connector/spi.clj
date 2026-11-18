(ns open.atlas.common.connector.spi
  "连接器 SPI —— engine 与各连接器之间的唯一接缝。

   跨模块只许 require 本 ns；`open.atlas.common.connector.spi.impl.*` 视为私有
   （与 dialect 同纪律）。

   principles「执行与连接」：
     「连接器操作契约在所有 source type 上可观察地一致……结果形状只有 single
      （可以是 `nil`，意思是\"这个 resolver 供不出这些属性\"，不是错误）与 list；
      空就是空，没有哪个 source type 拿 `{}` `[]` 或半填的 map 顶替缺失的行。
      **SPI 按归一后的 source type 分派**，这些契约不随方言改变。」

   本 ns 三件事：七个分派点、故障构造器、batch 对齐与 field mapping 的纯函数。
   它不认识 SQL、HTTP 或任何具体 source type——认识了就不是接缝了。

   ## 调用约定（分派点共用的三个参数）

   - `source-config`：**source 条目与操作块的合并 map**。source 条目提供连接键
     （`:type` `:jdbc_url` `:resilience` …，dialect-v1 §3），操作块提供
     `:query` `:params` `:field_mapping` `:batch` 等（各连接器契约）。合并由
     装配方做——SPI 只要求 `:type` 在这张 map 上。
   - `connection`：可选的、已备好的连接句柄（如 `javax.sql.DataSource`）。
     nil 表示「按 source-config 自己去开」。测试与宿主注入用。
   - `resolver-config` / `mutation-config`：方言里那条 resolver / mutation
     （`:id` `:input` `:output` `:result_type` `:eql-params` …）。

   ## 编译产物的形状

   - `compile-source`       → `(fn [input-params eql-params] → single-map|nil | [map …])`
   - `compile-batch-source` → `(fn [inputs eql-params] → 与 inputs 等长同序的向量)`
   - `compile-mutation`     → `(fn [mutation-params] → nil | single-map|nil | [map …])`

   `:output` 的 join 整形（把 list 塞进 `{:ns/parent [...]}`）**不在这里做**：
   连接器给的是行，语法树是模型的事，归 engine。`join-parent` 在此导出，供
   engine 复用同一份解析。"
  (:require [clojure.string :as str]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.connector.spi.impl.batch :as batch]
            [open.atlas.common.connector.spi.impl.deadline :as deadline]
            [open.atlas.common.connector.spi.impl.failure :as failure]
            [open.atlas.common.connector.spi.impl.mapping :as mapping]
            [open.atlas.common.connector.spi.impl.guard :as guard]
            [open.atlas.common.connector.spi.impl.policy :as policy]
            [open.atlas.common.connector.spi.impl.redact :as redact]
            [open.atlas.common.connector.spi.impl.values :as values]))

;; ---------------------------------------------------------------------------
;; 分派键：归一后的 source type
;; ---------------------------------------------------------------------------

(defn normalize-type
  "source type 的归一形：trim + 小写字符串。
   方言里 `:type` 是「已注册连接器契约名」（dialect-v1 §3），字符串；归一让
   `\"SQL\"`/`\" sql \"` 不会变成第二个 source type（那会绕过 support policy）。"
  [t]
  (when (some? t)
    (-> (if (keyword? t) (name t) (str t)) str/trim str/lower-case not-empty)))

(defn- dispatch-source [source-config & _] (normalize-type (:type source-config)))

;; ---------------------------------------------------------------------------
;; 七个分派点
;; ---------------------------------------------------------------------------

(defmulti compile-source
  "操作块 → `(fn [input-params eql-params] → 结果)`。
   结果形状按 `:result_type`：`\"single\"` 给一张 map 或 `nil`，`\"list\"` 给
   向量（空就是 `[]`）。"
  {:arglists '([source-config connection resolver-config])}
  dispatch-source)

(defmulti compile-batch-source
  "**声明了 `:batch` 的**操作块 → `(fn [inputs eql-params] → 向量)`。
   必须发一条语句取回全部父项的行，返回与 `inputs` 等长同序的向量
   （对齐用 `align-batch`）。"
  {:arglists '([source-config connection resolver-config])}
  dispatch-source)

(defmulti validate-source
  "写入端校验：操作块 + 连接键。→ `{:findings [finding …]}`
   （finding 形状同 finding-v1 §2，code 取自本 ns 的 `finding-codes`）。
   有 `:severity :error` 即不得发布——「错误与产物互斥」。"
  {:arglists '([source-config])}
  dispatch-source)

(defmulti health-check
  "按需体检一个连接。→ `{:status :ok|:down :latency-ms n}`，
   `:down` 时附 `:error/category`，未解析的配置变量另附 `:missing-vars`
   （变量**名**，不是值——名字是方言里的配置）。**不附**驱动原文/URL——
   体检结果和故障走同一条不泄漏纪律。
   principles：「可用性按需验证、绝不在装载时验证」。"
  {:arglists '([source-config connection])}
  dispatch-source)

(defmulti compile-mutation
  "mutation 操作块 → `(fn [mutation-params] → 结果)`。
   `:result_type \"none\"` 时不读结果集，返回 `nil`。"
  {:arglists '([source-config connection mutation-config])}
  dispatch-source)

(defmulti validate-mutation
  "mutation 写入端校验。→ `{:findings [finding …]}`。"
  {:arglists '([source-config])}
  dispatch-source)

(defmulti connection-bound-attributes
  "Which attributes does this source ENTRY bind at query time, before any
   operation block is looked at — `#{:nifi.instance/host}`.

   The seam the templated `:base_url` needs (owner ruling 2026-09-07, doc 106
   §106.14 item 11): api-v1 lets a `:base_url` carry `{ns/attr}` in the
   authority position, so one source over many like-for-like instances picks
   the host per query. That binding belongs to the CONNECTION, not to any one
   operation, and every resolver on such a source therefore takes the
   attribute as an `:input` — which the write side has to know while it is
   still planning, with no operation block in hand.

   Answers a **set of qualified keywords**, empty when the entry binds nothing
   — the honest answer for every source type that has no such notion, which is
   why the default is `#{}` and not `nil`. It says nothing about whether a
   given resolver USES the binding; `bound-attributes` answers that, per
   operation, for row security.

   `source-config` is the source ENTRY (dialect shape), not merged with an
   operation block."
  {:arglists (quote ([source-config]))}
  dispatch-source)

(defmulti in-record-collection-attributes
  "Which join attributes of THIS operation's `:output` carry rows that come
   from inside the parent record — `#{:nifi.proc/relationships}`.

   The seam item 17 needs (owner ruling 2026-09-07, doc 106 §106.14 item 17):
   the parent record's identity attributes ride an IN-RECORD collection into
   its child rows, because those rows were assembled out of the parent's own
   response and the parent's identity is therefore known at assembly time —
   exactly the reason item 13 carries a connection binding. An endpoint-backed
   child collection is NOT such a branch: its rows came from a second call and
   the parent's key was never in them.

   Whether a branch is in-record is a SOURCE-DIALECT fact — api-v1 says it
   with `:_from` in the `:field_mapping`, and no other source type says it at
   all — so the engine must ask here rather than read a connector's spelling.

   Answers a **set of qualified keywords** at any depth of the `:output` tree,
   empty when this operation has no such branch. `source-config` is the source
   entry MERGED with the operation block (the shape `compile-source` takes),
   because the branch lives in the operation."
  {:arglists (quote ([source-config]))}
  dispatch-source)

(defmulti bound-attributes
  "Which of the entry's `:input` attributes does this operation actually bind a
   value for — `#{:order/tenant_id}`.

   This is the seam E1 (D5.14, owner 2026-09-03) needs and nothing else could
   provide: the cut-time row-security check has to answer \"does this operation
   really narrow by this attribute\" without knowing SQL, HTTP or any other
   dialect of a source. The answer is per source type, so it belongs on the
   dispatch.

   **Attributes, not binding names.** The first cut of this seam returned the
   local placeholder names (`#{\"tenant_id\"}`) and left the caller to compare
   `(name attr)`. That silently assumed sql's spelling: the api connector
   interpolates `{order/tenant_id}` — the *qualified* attr — and static matches
   on an attribute keyword with no name string anywhere. A caller comparing
   local names would have judged every api operation unbound. Each connector
   knows how it spells a binding; **nobody else should have to**.

   **Only `:input` bindings count.** A placeholder bound to an `:eql-params`
   value is a caller-supplied narrowing, and D5.6 consensus 2 (upheld by D5.14)
   says row security never binds a caller-supplied value — a predicate the
   caller chooses is not a predicate. So such a binding must NOT satisfy the
   check, and implementations exclude it.

   Returns a **set of qualified keywords**, or **nil** for \"I cannot tell\".
   `nil` is not \"none\": a caller that treats an unanswerable source as unbound
   would refuse correct models, and one that treats it as bound would pass
   wired-up-by-nobody ones. The caller must report it — E1 raises
   `:rowsec.wiring/unverifiable` — rather than guess either way. Same discipline
   as `describe-schema`'s refusing default.

   `source-config` is the effective operation block (`:source` merged over the
   `:source-ref`ed shared source) **carrying the entry's `:input` and
   `:eql-params`** — the same merged shape `validate-source` is handed. Both are
   needed because a binding is not decided by the statement alone: D2.1's
   identity default (placeholder name = attribute local name or eql-param name)
   spans exactly that seam."
  {:arglists '([source-config])}
  dispatch-source)

(defmulti describe-schema
  "Read the *structure* a source exposes: relations, their columns, their
   declared keys. This is the seam a modelling face uses to propose default
   attributes and resolvers instead of asking a human to retype a schema.

   → the shape documented by `schema-description?` below. Identifiers and
   declared types only — **never a row, never a sample value, never a count**.
   Structure is configuration-shaped and may be shown; contents are business
   data and this seam is not the door for them.

   `opts` narrows what to walk (all optional):
     `:catalog` `:schema`   restrict to one catalog / schema (strings)
     `:kinds`              subset of `relation-kinds`, default both
     `:names`              only these relation names (set of strings)
     `:keys?`              false to skip primary and foreign keys

   `:keys? false` exists for scale: a schema with several hundred relations is
   normal, and declared keys are the part that cannot be read in bulk. A face
   listing a large schema asks without keys, then asks again with `:names` for
   the handful the modeller selected. The result then carries `:keys-read?
   false`, because an empty `:primary-key` must never be mistakable for \"this
   relation declares none\" — the same reason the default method refuses
   instead of reporting an empty database.

   A source type that cannot answer must **refuse** through the `:default`
   method rather than report an empty database — an empty `:relations` means
   \"this source really has no relations\", and a modelling face that cannot
   tell the two apart would silently propose nothing at all. A source type
   that answers but cannot answer *fully* (no foreign keys in the engine, for
   instance) says so in `:capabilities`; a declared gap is not a silent one."
  {:arglists '([source-config connection opts])}
  dispatch-source)

;; ---------------------------------------------------------------------------
;; 缺省方法：默认行为必须是「说出来」，不是「凑合」
;; ---------------------------------------------------------------------------

(defn- unknown-type-finding [source-config what]
  {:code     :connector.source/unknown-type
   :severity :error
   :path     [:source :type]
   :message  (str "No connector registered for source type "
                  (pr-str (:type source-config)) " (" what ").")
   :got      (redact/redact (:type source-config))
   :refs     ["dialect-v1 §3" "D2.15"]})

(defmethod validate-source :default [source-config]
  {:findings [(unknown-type-finding source-config "validate-source")]})

(defmethod validate-mutation :default [source-config]
  {:findings [(unknown-type-finding source-config "validate-mutation")]})

(defmethod bound-attributes :default [_source-config]
  ;; "I cannot tell", not "it binds nothing" — see the multimethod's docstring.
  ;; A registered connector that never implements this leaves every
  ;; row-security declaration on it unverifiable, which is the closed
  ;; direction; a type with no connector at all is a different diagnosis and
  ;; the caller separates the two (`spi/registered-types`).
  nil)

(defmethod compile-source :default [source-config _connection resolver-config]
  (failure/fail! :connection
                 {:resolver-id (:id resolver-config)
                  :source-type (normalize-type (:type source-config))}
                 "no connector registered for this source type"))

(defmethod compile-mutation :default [source-config _connection mutation-config]
  (failure/fail! :connection
                 {:resolver-id (:id mutation-config)
                  :source-type (normalize-type (:type source-config))}
                 "no connector registered for this source type"))

(defmethod compile-batch-source :default [source-config _connection resolver-config]
  ;; principles：「落到没有 `compile-batch-source` 的连接器上是**配置错误**，
  ;; 不是静默退回逐行——用户要了一次取数就必须被告知没拿到。」
  ;; 所以这里既不 fallback 到 compile-source，也不返回 nil 让调用方去猜。
  (failure/fail! :connection
                 {:resolver-id (:id resolver-config)
                  :source-type (normalize-type (:type source-config))}
                 (str "source type does not implement compile-batch-source; "
                      ":batch on it is a configuration error, not a silent "
                      "fallback to per-row fetches")))

(defmethod describe-schema :default [source-config _connection _opts]
  ;; Refuse rather than hand back `{:relations []}`: "this source type cannot
  ;; be introspected" and "this database has no tables" are two different
  ;; answers, and a generator fed the second one proposes nothing and reports
  ;; success. Same discipline as compile-batch-source's default.
  (failure/fail! :connection
                 {:resolver-id nil
                  :source-type (normalize-type (:type source-config))}
                 (str "source type does not implement describe-schema; "
                      "schema introspection is unavailable for it")))

(defmethod in-record-collection-attributes :default [_source-config]
  ;; Empty, not a refusal: "no branch of this operation carries rows out of
  ;; the parent record" is the true answer for sql, static and derived.
  #{})

(defmethod connection-bound-attributes :default [_source-config]
  ;; Empty, not a refusal: "this source type binds nothing from its connection
  ;; entry" is the true answer for sql, static and derived, and a caller must
  ;; be able to ask every source the same question.
  #{})

(defmethod health-check :default [source-config _connection]
  {:status          :down
   :latency-ms      -1
   :error/category  :connection
   :source-type     (normalize-type (:type source-config))})

;; ---------------------------------------------------------------------------
;; 能力查询
;; ---------------------------------------------------------------------------

(defn supports-batch?
  "该 source type 是否实现了 `compile-batch-source`（而不是落到缺省方法）。
   体检期回答「这个 `:batch` 声明能不能跑」用；执行期不靠它——执行期直接调，
   缺省方法自己会抛配置错误。"
  [source-type]
  (let [t (normalize-type source-type)]
    (boolean (and t (get-method compile-batch-source t)
                  (not= (get-method compile-batch-source t)
                        (get-method compile-batch-source :default))))))

(defn supports-describe?
  "Whether this source type implements `describe-schema` (rather than falling
   through to the refusing default). A modelling face asks before offering
   \"build from this source\" — offering a button that can only fail is worse
   than not offering it."
  [source-type]
  (let [t (normalize-type source-type)]
    (boolean (and t (get-method describe-schema t)
                  (not= (get-method describe-schema t)
                        (get-method describe-schema :default))))))

(defmulti supports-offset?
  "Whether this source type honours the `:offset` reserved call name
   (dialect-v1 §3).

   **Declared, not detected.** `supports-batch?` and `supports-describe?` read
   their answer straight off the dispatch table, because batch and
   introspection each own a dispatch point. `:offset` owns none — it rides the
   flat eql-params map into `compile-source`, so nothing about a connector's
   methods reveals whether it applies it. A connector says so here instead.

   **The default is false**, and that direction is the whole point: a connector
   that ignores `:offset` answers the UNSKIPPED rows, and the caller then
   reasons about a page that never existed (measured on static, 8.17 —
   `{:offset 2}` returned the same three rows with an `:ok` status). Silence is
   therefore read as a NO, and the engine's gate refuses the parameter with
   `:engine.request/offset-unsupported` rather than passing it down to be
   dropped. A connector that gains offset support declares it in the same
   commit that implements it.

   This is a **capability predicate, not an eighth dispatch point**: the seven
   dispatch points are what a connector DOES, this is what it CAN do, and it
   belongs beside `supports-batch?` / `supports-describe?`."
  {:arglists '([source-type])}
  (fn [source-type] (normalize-type source-type)))

(defmethod supports-offset? :default [_] false)

(defn registered-types
  "已注册的 source type 集合（按 `compile-source` 计）。"
  []
  (disj (set (keys (methods compile-source))) :default))

(defn registry-version
  "An opaque, equality-comparable snapshot of the connector methods that can
   affect engine compilation.

   Connectors are ordinary multimethod implementations and may be loaded,
   replaced or removed at runtime. Returning the method tables themselves
   makes those changes visible without imposing a second registration API on
   connectors. Callers must only compare or use this value as a cache key; its
   shape is deliberately not a public contract."
  []
  [(methods compile-source)
   (methods compile-batch-source)
   (methods compile-mutation)
   (methods connection-bound-attributes)
   (methods in-record-collection-attributes)
   (methods supports-offset?)])

;; ---------------------------------------------------------------------------
;; Schema description: the shape `describe-schema` returns
;; ---------------------------------------------------------------------------

(def relation-kinds
  "What a described relation can be. A view is described like a table but has
   no declared primary key, so a modelling face must ask for its key."
  #{:table :view})

(def column-classes
  "Coarse classes a described column's declared type is folded into. These are
   *database* facts, deliberately not dialect `:type` values — mapping a class
   onto a dialect type is modelling policy and lives with the modelling face,
   not in a connector. `:unknown` is honest: the driver reported a type this
   seam will not guess about."
  #{:string :integer :decimal :boolean :date :datetime :binary :json :unknown})

(def describe-capabilities
  "Facets of a description a source type may or may not be able to fill in.
   Each maps to `true` (asked and answered), `false` (the engine has no such
   concept, or the driver will not report it), in the returned
   `:capabilities`. A `false` here is what turns a silent gap into a declared
   one — e.g. an engine without foreign keys reports `:foreign-keys false`
   and the face stops offering FK-derived proposals for it.

   `:primary-keys-unique?` (sql-v1 §capabilities, owner 2026-08-30) separates
   \"the engine answers getPrimaryKeys\" from \"what it answers is a
   uniqueness constraint\". ClickHouse answers with its ORDER BY sort key —
   the shape of a key, none of a key's uniqueness — so `:primary-keys` stays
   true while this is false. A consumer that would derive `:identity?` from a
   declared key must not when this is false; a key the caller nominated is a
   choice and stands."
  #{:primary-keys :primary-keys-unique? :foreign-keys :views})

(defn schema-description?
  "Whether `x` is a well-formed `describe-schema` result.

   Shape:

     {:relations    [{:name \"orders\"        ; string, as the driver reports it
                      :schema \"public\"      ; string | nil
                      :catalog nil            ; string | nil
                      :kind :table            ; relation-kinds
                      :columns [{:name \"vend_no\"
                                 :class :integer      ; column-classes
                                 :sql-type \"INTEGER\" ; driver's type name
                                 :nullable? false
                                 :position 1}]
                      :primary-key [\"id\"]   ; column names, ordered; [] if none
                      :foreign-keys [{:name \"fk_orders_vendor\" | nil
                                      :columns [\"vend_no\"]
                                      :references {:relation \"vendors\"
                                                   :schema nil
                                                   :columns [\"vend_no\"]}}]
                      ;; The same facts, read from the other end: who points at
                      ;; me. Not derivable from `:foreign-keys` unless every
                      ;; relation in the database was described, which is
                      ;; exactly what a narrowed read does not do.
                      :referenced-by [{:name \"fk_lines_order\" | nil
                                       :relation \"order_lines\"
                                       :schema nil
                                       :columns [\"order_no\"]      ; on that relation
                                       :references {:columns [\"id\"]}}]}]
      :capabilities {:primary-keys true :primary-keys-unique? true
                     :foreign-keys true :views true}
      :keys-read?   true            ; false when opts asked to skip keys
      :findings     [finding …]}

   Checked here because this is the seam's contract, and a connector that
   drifts from it breaks every modelling face at once rather than only its
   own tests."
  [x]
  (letfn [(col? [c]
            (and (map? c)
                 (string? (:name c))
                 (contains? column-classes (:class c))
                 (contains? #{true false} (:nullable? c))))
          (fk? [k]
            (and (map? k)
                 (vector? (:columns k))
                 (seq (:columns k))
                 (every? string? (:columns k))
                 (map? (:references k))
                 (string? (get-in k [:references :relation]))
                 (= (count (:columns k))
                    (count (get-in k [:references :columns])))))
          (in? [k]
            (and (map? k)
                 (string? (:relation k))
                 (vector? (:columns k))
                 (seq (:columns k))
                 (every? string? (:columns k))
                 (= (count (:columns k))
                    (count (get-in k [:references :columns])))))
          (rel? [r]
            (and (map? r)
                 (string? (:name r))
                 (contains? relation-kinds (:kind r))
                 (vector? (:columns r))
                 (every? col? (:columns r))
                 (vector? (:primary-key r))
                 (every? string? (:primary-key r))
                 (vector? (:foreign-keys r))
                 (every? fk? (:foreign-keys r))
                 (vector? (:referenced-by r))
                 (every? in? (:referenced-by r))))]
    (boolean
     (and (map? x)
          (vector? (:relations x))
          (every? rel? (:relations x))
          (map? (:capabilities x))
          (every? #(contains? describe-capabilities %) (keys (:capabilities x)))
          (sequential? (:findings x))))))

;; ---------------------------------------------------------------------------
;; 故障契约（转发 impl.failure；键面封闭见该 ns）
;; ---------------------------------------------------------------------------

(def error-categories failure/categories)

(def transient-categories
  "The only categories a `:retry_on` may name (D6.4 / doc 107 §107.3). Single
   definition: connectors, the dialect's write-side check and the retry loop
   all read this one set."
  failure/transient-categories)

(def protected-statuses
  "HTTP statuses whose classification the platform keeps -- a source's
   `:status-map` may not re-map authorization, rate limiting or system failure
   (doc 107 §107.5). Defined once here because api and sql must give the same
   answer to the same rule (D1.8)."
  failure/protected-statuses)

(defn fail!
  "抛出连接器故障。ex-data 恰为
   `{:error/category … :resolver-id … :source-type …}`，永不含语句、URL、
   响应体、凭证、行值（principles「source 失败与日志不携带业务数据」）。
   `detail` 只进 message，且只允许配置层面的短语。"
  ([category ctx] (failure/fail! category ctx))
  ([category ctx detail] (failure/fail! category ctx detail))
  ([category ctx detail opts]
   ;; `opts` may carry `:retry-after-ms` (§107.4) and `:limit` (§107.6), nothing
   ;; else -- both are configuration-shaped numbers, never source content.
   (failure/fail! category ctx detail opts)))

(def retry-after-ms
  "The upstream-declared wait (ms) carried by a `:rate_limited` failure, or
   nil. The one upstream-derived number a connector failure may carry."
  failure/retry-after-ms)

(def failure-limit
  "The declared bound a failure hit -- `{:limit kw :max n :actual n}` -- or nil.
   The engine reports it as `:engine.limit/exceeded`, the same shape the four
   EQL boundaries use (doc 107 §107.6)."
  failure/limit)

;; ---------------------------------------------------------------------------
;; Effective resilience policy (D6.2 four-level merge, doc 107 §107.3 clamp)
;; ---------------------------------------------------------------------------

(def platform-default-policy
  "The platform floor of the four-level merge. Candidate numbers pending
   OQ-RESILIENCE-DEFAULTS."
  policy/platform-default)

(def policy-semantic-keys
  "The half that rides with the artifact (the modeller's)."
  policy/semantic-keys)

(def policy-capacity-keys
  "The half the deployment owns (operations')."
  policy/capacity-keys)

(def effective-policy
  "`(effective-policy levels capacity)` -- merge platform default, connector
   type default, slot policy and binding override in that order, then let the
   deployment's `:resilience` tighten. One implementation for every connector."
  policy/merge-policy)

(def policy-loosenings
  "Where a deployment entry tries to WIDEN a graph's declared policy; the
   version-switch check refuses each with `:deployment.policy/loosened`."
  policy/loosenings)

(def retry-allowed?
  "`(retry-allowed? policy category attempt)` -- D6.4's preconditions."
  policy/retry?)

(def retry-backoff-ms
  "`(retry-backoff-ms policy attempt upstream-ms?)` -- exponential backoff with
   jitter; an upstream `Retry-After` wins."
  policy/backoff-ms)

;; ---------------------------------------------------------------------------
;; Bulkhead / breaker / retry (D6.5, D6.4) -- one implementation, every connector
;; ---------------------------------------------------------------------------

(def guarded-call!
  "`(guarded-call! {:fingerprint … :policy … :ctx … :idempotent? …} attempt)`
   -- run `attempt` (which takes the 1-based attempt number) under this
   source's circuit breaker and bulkhead, retried per the effective policy.

   The protections sit on ONE function so a call site cannot forget them, and
   in the SPI so api and sql give the same answer to the same rule (D1.8)."
  guard/call!)

(def with-source-permit
  "`(with-source-permit fingerprint policy ctx f)` -- hold one of this source's
   bulkhead permits for the duration of `f`."
  guard/with-permit)

(def with-source-breaker
  "`(with-source-breaker fingerprint policy ctx f)` -- run `f` under this
   source's breaker; an open one refuses with `:circuit_open` and sends
   nothing."
  guard/with-breaker)

(defmacro with-retry-sleep
  "Run `body` with `f` standing in for the retry loop's wait.

   Exported as a macro because the dynamic var it binds is private to the impl
   ns: doc 107 §107.8 pins the DURATION a retry waits, and a conformance test
   that actually waited two seconds is a test nobody runs. Production code has
   no reason to call this."
  [f & body]
  `(binding [guard/*sleep-ms* ~f] ~@body))

(def reset-guards!
  "Drop every bulkhead and breaker (tests, REPL). The state is deliberately
   process-wide, so it needs a way back to a known start."
  guard/reset-state!)

(def status-map-targets
  "The categories a source's `:status-map` may name (doc 107 §107.5). One
   definition, in the dialect, because the schema and the connector check must
   agree on it."
  dialect/status-map-targets)

(deftype BatchItemFailure [info])

(defn batch-item-failure
  "Value-free per-input failure for an isolated batch input. This is not
   nil/not-found and is never a business record (api-v1 isolation ruling).

   `category` (doc 107 §107.5) says WHICH claim this is. It defaults to
   `:input-rejected` -- «this input could not be fetched», which is what the
   2026-09-17 ruling produced and deliberately NOT «this row does not exist».
   A source that really means the row is absent declares `:not-found`, and the
   connector answers with an empty slot instead of one of these."
  ([ctx http-status] (batch-item-failure ctx http-status :input-rejected))
  ([ctx http-status category]
   (BatchItemFailure. (assoc (select-keys ctx [:resolver-id :source-type])
                            :http-status http-status
                            :error/category category))))

(defn batch-item-failure? [x] (instance? BatchItemFailure x))
(defn batch-item-failure-info [^BatchItemFailure x] (.-info x))

(def ^:dynamic *source-diagnostics*
  "Authoring-only, in-memory diagnostic sink. Hosts must explicitly bind this
   for an authenticated query preview. Never copy these records into findings,
   audit logs, query history, or data-plane responses. Connectors redact
   credentials before calling it; sink failures must not affect execution."
  nil)

(def ^:dynamic *statement-trace*
  "宿主装上就收得到「刚才真的发了哪条语句」的回执；默认 `nil`，什么都不做。

   **这不是日志。** principles 说「source 失败与日志不携带业务数据」，那条仍然
   逐字成立：`fail!` 不带语句，本仓库的 modules 里也没有任何 logger。这里是另一
   条通道——一个**由宿主显式绑定**的函数，只在绑定的动态作用域内存在，不写盘、
   不进 findings、不进制品。

   谁绑它（2026-08-31 修订）：

   - **Studio 的 preview**：建模者本来就在接收这次查询的行，把产生那些行的语句
     给他看，不多给任何东西。收到的是**整条回执**，含 `:sql` 与 `:values`。
   - **数据面**（`runtime/instance` 的 `answer`）：为审计绑，而且**只取
     `statement-audit-summary` 那几项**——resolver、源类型、耗时、行数。
     语句原文与绑定值**到不了**审计：它们在这条通道上出现过，但从不被留下。
     本条原先写的是「数据面不绑、那里不存在这条通道」；owner 2026-08-31 裁定
     数据面的读也要看得见步骤，据此修订。**代码改了文档就改**，不留第二种说法。

   两处的区别不在通道，在**留下什么**：preview 交给人，审计只留名称与计数。

   收到的 map：

     {:sql        \"SELECT … WHERE film_id IN (?)\"  ; 值从不插值进串
      :values     [133]                               ; 绑定值，原样
      :elapsed-ms 12
      :rows       7                                   ; 读语句才有
      :resolver-id \"pg_dvd3_film_film_actor\"
      :source-type \"sql\"}

   实现方**不得**把 source-config 放进去：`:jdbc_url` 与凭证住在那里。"
  nil)

(def application-name-base
  "E2：本平台对外自称的名字。源库的 `pg_stat_activity` 里、被调 API 的访问日志
   里，看到的就是它——「连过来的是 Open Atlas」。

   **住在 SPI 而不是某个连接器里**：它是**平台**的身份，不是 sql 的概念。两个
   连接器各写一份的那天，同一台机器会在 PG 里叫一个名字、在 HTTP 日志里叫另一个,
   而对方运维要对账的正是这个名字（CLAUDE.md「需要跨模块共用的东西，摆到接口上」
   ——`redact` 与档位词表都出过这种事）。

   **连接/进程级，不带 request-id**：连接是池化的、HTTP 客户端是复用的，把某一个
   请求的 id 焊在这里只会指错人。「这条语句/这次调用属于哪个请求」是
   `*request-trace*` 那条通道的活。"
  "open-atlas")

(def ^:dynamic *instance-id*
  "宿主（一台 runtime instance）可以在启动时绑上自己的 id，让 application name
   变成 `open-atlas-<instance-id>`——同一个源被几台 instance 连着时，对方运维才
   分得清是哪一台。没绑就是不带后缀。"
  nil)

(defn application-name
  "本进程对外自称的名字。只留 `[A-Za-z0-9-]`：这个值要被拼进 JDBC 连接属性、URL
   参数与 HTTP header，过滤让「它改不了别的字段」成为代码事实，而不是一句约定。"
  []
  (let [id (some-> *instance-id* str (str/replace #"[^A-Za-z0-9-]" "") not-empty)]
    (if id (str application-name-base "-" id) application-name-base)))

(def ^:dynamic *request-trace*
  "E2: correlation ids for the request a connector call belongs to, or `nil`
   outside one (unit tests, CLI, health checks).

   Shape `{:request-id \"…\" :graph-id \"…\"}`; `:graph-id` is the graph UUID
   (D3.18). The engine binds it from the pathom env around each source call, so
   it is set on whatever thread the resolver actually runs on -- a dynamic var
   bound by the caller cannot travel to Pathom's parallel threads on its own,
   which is why the ids ride the env down to here and are re-bound at the leaf.

   It carries **names and ids only**, never a value from the request."
  nil)

(defn deadline-at
  "The wall-clock instant (ms epoch) the whole query must be finished by, or
   nil outside a budgeted request (D6.3, doc 107 §107.7). The engine mints it
   and it rides `*request-trace*` to every connector call."
  []
  (:deadline-at *request-trace*))

(defn remaining-ms
  "How long is left of the query's budget, or nil when there is no deadline.
   May be zero or negative -- the caller treats that as `:cancelled`."
  []
  (deadline/remaining-ms (deadline-at)))

(defn attempt-timeout-ms
  "One attempt's timeout, narrowed to the remaining budget (D6.3: an attempt's
   timeout and the query's budget do not add up)."
  [timeout-ms]
  (deadline/attempt-timeout-ms timeout-ms (deadline-at)))

(defn deadline-exceeded?
  "Is the query's budget already spent? False when there is no deadline."
  []
  (deadline/exceeded? (deadline-at)))

(def register-abort!
  "Register «how to abort the call this thread has in flight»; returns a
   zero-arg unregister function (D6.3). A connector calls this around each
   attempt so the engine's deadline can reach the transport."
  deadline/register-abort!)

(def abort-all!
  "Abort every in-flight source call. The engine calls it when the whole
   query's deadline expires -- `future-cancel` alone only stops WAITING."
  deadline/abort-all!)

(def calls-in-flight
  "How many source calls currently have an abort hook registered. For
   conformance tests: a timed-out attempt must have let go."
  deadline/in-flight-count)

(defn ctx-with-request-trace
  "Merge the current request trace (if any) into a connector `ctx`."
  [ctx]
  (if-let [t *request-trace*]
    (assoc ctx :request-id (:request-id t) :graph-id (:graph-id t))
    ctx))

(defn trace-statement!
  "把一条语句的回执交给宿主。没绑就是零开销的 no-op。
   自身异常一律吞掉——观测手段不许把被观测的查询搞挂。"
  [m]
  (when-let [sink *statement-trace*]
    (try (sink m) (catch Throwable _ nil)))
  nil)

(def statement-audit-keys
  "Safe statement receipt fields (audit-event-v1 §5). SQL, bound values,
   URLs and bodies never belong to an audit event. New status fields are
   also type-checked by statement-audit-summary."
  [:resolver-id :source-type :elapsed-ms :rows :ok? :http-status :error/category
   :attempt :category :page])

(defn statement-audit-summary
  "One allowlisted summary shared by all hosts. Absent statements yield nil.
   Only booleans, HTTP status integers and closed failure categories may enter
   the additional diagnostic fields; arbitrary driver text is never retained."
  [statements]
  (when (seq statements)
    {:count (count statements)
     :rows (reduce + 0 (keep :rows statements))
     :by-resolver
     (mapv (fn [receipt]
             (cond-> (select-keys receipt [:resolver-id :source-type :elapsed-ms :rows])
               (boolean? (:ok? receipt)) (assoc :ok? (:ok? receipt))
               (and (integer? (:http-status receipt)) (<= 100 (:http-status receipt) 599))
               (assoc :http-status (:http-status receipt))
               (contains? failure/categories (:error/category receipt))
               (assoc :error/category (:error/category receipt))
               ;; doc 107 §107.8: an audit reader must be able to see that the
               ;; answer took three attempts, and in which category each one
               ;; failed -- otherwise a retried 429 and a clean call look
               ;; identical. Type-checked the same way: a counter and a closed
               ;; category, never driver or upstream text.
               (and (integer? (:attempt receipt)) (<= 1 (:attempt receipt) 1000))
               (assoc :attempt (:attempt receipt))
               (contains? failure/categories (:category receipt))
               (assoc :category (:category receipt))
               ;; doc 107 §107.6: which page of a collection read this was. A
               ;; single summary receipt would report ten upstream calls as one.
               (and (integer? (:page receipt)) (<= 1 (:page receipt) 10000))
               (assoc :page (:page receipt))))
           statements)}))

(defn failure? [x] (failure/failure? x))
(defn error-category [x] (failure/category x))

(defn redact
  "不泄漏截断（finding-v1 §5 同源；见 impl.redact 的 ns 文档）。"
  [v]
  (redact/redact v))

;; ---------------------------------------------------------------------------
;; field mapping / :output 语法树
;; ---------------------------------------------------------------------------

(def output-attributes mapping/output-attributes)
(def join-parent mapping/join-parent)
(def resolve-field-mapping mapping/resolve-field-mapping)
(def apply-field-mapping mapping/apply-field-mapping)

;; ---------------------------------------------------------------------------
;; batch 对齐
;; ---------------------------------------------------------------------------

(def align-batch batch/align)
;; per-parent 切窗（sql-v1「分页在 batch 上是 per-parent 窗口，不下推」）。
;; 导出是给「将来做了 SQL 下推的连接器」用的对照基准：下推路径必须与这一份
;; 逐位一致，等价性按 D4.10 的同一条纪律验证。
(def window-bucket batch/window)
(def group-rows-by-fk batch/group-rows-by-fk)
;; batch 的 `:parent-key` / `:child-fk` 归一成向量：单个即单键，向量即复合键
;; （owner 2026-09-07，doc 106 §106.14 第 14 条）。连接器与对齐共用一份读法。
(def batch-keys batch/as-vec)
(def join-key batch/join-key)

;; ---------------------------------------------------------------------------
;; finding code 注册表（finding-v1 §3 的 `connector.*` 域、§6 的注册表纪律）
;; ---------------------------------------------------------------------------

(def finding-codes
  "连接器域已发布的 `:code`。一经发布不改名；产出未注册 code 是实现 bug。

   为什么不并进 dialect 的注册表：那张表是 dialect 模块的私有 def，且本模块
   不改 dialect。finding-v1 §3 明说「后续模块续接：`artifact.*`、`pool.*`、
   `connector.*`」——续接的意思正是各自登记自己的域。"
  #{:connector.source/unknown-type          ; source type 没有注册的连接器
    :connector.source/missing-key           ; 契约必填键缺席
    :connector.source/unknown-key           ; 操作块封闭键面之外的键（support policy :reject）
    :connector.source/literal-credential    ; 凭证键写了字面值而不是 ${VAR}
    :connector.source/placeholder-mismatch  ; 占位符与 :params 不是恰好互相覆盖
    :connector.source/timeout-not-enforceable ; 超时被关掉/不可强制
    :connector.source/batch-unsupported     ; 该 type 没有 compile-batch-source
    :connector.source/mutation-unsupported  ; 该 type 不支持 mutation（能力表里是 ✗）
    :connector.source/expr-unparsable       ; derived 的 :expr 预解析失败（derived-v1）
    :connector.eval/failed                  ; 运行期求值失败 → 该属性缺席（不失败整个实体）
    :connector.eval/timeout                 ; 运行期求值超时 → 同上
    :connector.source/unreachable           ; a connection that validated but did not answer
    :connector.schema/unsupported-capability ; describe-schema: engine has no such concept (e.g. FK)
    :connector.schema/unreadable-relation    ; describe-schema: a relation the driver refused to describe
    :connector.source/templated-health      ; :base_url 带占位符，整体体检无从谈起（doc 106 §106.14 item 11）
    :connector.source/untagged-driver       ; E2: 该 driver 没有已知的 application name 属性，连接未打标
    ;; doc 107 §107.5：本版改写 + :info，下一契约版本 :reject
    ;; （dialect-v1 §6「改写不是删除」）。
    :connector.source/superseded-key        ; 键已被新键取代，本版按等价形式读
    :connector.source/invalid-shape})       ; 其余结构违规

(defn finding?
  "x 是否为合法的连接器 finding（finding-v1 §2 封闭键面 + 本域 code）。"
  [x]
  (boolean
   (and (map? x)
        (contains? finding-codes (:code x))
        (contains? #{:error :warning :info} (:severity x))
        (vector? (:path x))
        (string? (:message x))
        (empty? (remove #{:code :severity :path :message :expected :got :refs :files}
                        (keys x))))))

(defn errors?
  "findings 里有没有 `:error`。有即不得发布。"
  [findings]
  (boolean (some #(= :error (:severity %)) findings)))

;; ---------------------------------------------------------------------------
;; Package declaration: what a model needs on the machine that installs it
;; (doc 104 §8.17.7 A, items 1 and 2)
;; ---------------------------------------------------------------------------

(def var-pattern
  "`${VAR}` as it is written in a source config.

   **One regex for the whole platform.** connector-sql's `impl.envsub` used to
   own this pattern; the package block has to find the same variables in every
   connector's config (api-v1's nested credential keys included), and two
   copies of a pattern is the shape CLAUDE.md names — one gets tightened, the
   other does not, and the two faces then disagree about what a variable is."
  #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")

(defn var-names
  "The `${VAR}` names in one string, in the order written. Non-strings → nil."
  [s]
  (when (string? s) (mapv second (re-seq var-pattern s))))

(defn workspace-base-dir
  "The anchor a RELATIVE file path in a source config resolves against:
   $OPEN_ATLAS_CONFIG_PATH, else $OPEN_ATLAS_HOME, else `~/.open-atlas`
   (doc 104 §8.17.7 H item 1, owner ruling 2026-09-06).

   **One copy of the rule.** It was written three times — connector-sql's
   sqlite anchor, connector-api's cert-store anchor, and the server console's
   install plan — and the three faces have to agree: \"the plan says the file
   is here\" and \"the connector opens that file\" disagreeing is exactly the
   drift CLAUDE.md warns about. It lives next to `declared-files` because the
   two are one question asked twice: which files, and where from.

   `getenv` is a `(fn [name] value-or-nil)` standing in for the process
   environment, so this can be exercised over a known one."
  ([] (workspace-base-dir #(System/getenv %)))
  ([getenv]
   (or (not-empty (getenv "OPEN_ATLAS_CONFIG_PATH"))
       (not-empty (getenv "OPEN_ATLAS_HOME"))
       (str (System/getProperty "user.home") "/.open-atlas"))))

(defmulti declared-files
  "Which FILES will this source config make the connector open —
   `[\"data/open-atlas-demo.db\"]`.

   Paths are answered **as written**: relative stays relative, `${VAR}` stays
   unresolved. The caller is an installer deciding \"is this here?\", not an
   executor; resolving a variable here would put a value into a manifest, and
   the package block carries names only (doc 104 §8.17.7 A).

   Why on the dispatch: only the connector knows which of its keys hold a file
   path — sqlite hides one inside `:jdbc_url`, api-v1 keeps two under `:tls`.
   Deriving the list anywhere else means hardcoding connector knowledge into
   the artifact, which is the second semantics D1.8 forbids.

   The default is `[]`, not a refusal: a source type that opens no file is the
   normal case (every network database), so silence here is a true answer and
   not a missing implementation."
  {:arglists '([source-config])}
  dispatch-source)

(defmethod declared-files :default [_source-config] [])

(defmulti contract-version
  "The connector contract version this source type implements — `\"v1\"` for
   `\"sql\"` (docs/contracts/connectors/sql-v1.md) and `\"api\"`.

   It goes into a package's `:compat` block so an installing instance can tell
   \"this package was cut against a contract I do not implement\" apart from
   \"this package is broken\". Unknown type → nil, which the caller reports as
   an unknown connector rather than as an incompatible one."
  {:arglists '([source-type])}
  (fn [source-type] (normalize-type source-type)))

(defmethod contract-version :default [_source-type] nil)

(defn- string-values
  "Every string in a nested value (maps, vectors, sets, lists), depth-first.
   Map KEYS are not values — a `${VAR}` can only be written on the value side."
  [v]
  (cond
    (string? v) [v]
    (map? v)    (mapcat string-values (vals v))
    (coll? v)   (mapcat string-values v)
    :else       []))

(defn package-requirements
  "A dialect model → `{:vars [name …] :files [path …]}`: what the machine
   installing this model must already have (doc 104 §8.17.7 A).

   `:vars` is every `${VAR}` name written anywhere in any `(:sources model)`
   entry — nested keys included, which is why it walks the entry instead of
   reading a key list (studio's credential store reads three top-level sql
   keys, and api-v1's credentials are nested; see OQ-API-CREDENTIALS). Names
   only, never values.

   `:files` is `declared-files` across the sources. Both are sorted and
   distinct so the block is a pure function of the model and does not carry
   map iteration order into the artifact digest (D4.3)."
  [model]
  (let [sources (vals (:sources model))]
    {:vars  (vec (sort (distinct (mapcat #(mapcat var-names (string-values %))
                                         sources))))
     :files (vec (sort (distinct (mapcat declared-files sources))))}))

(defn package-compat
  "A dialect model → `{:dialect \"v1\" :connectors {\"sql\" \"v1\" …}}`: the
   contract versions this package was cut against, for the source types it
   actually uses (doc 104 §8.17.7 A).

   A type with no registered connector is listed with a nil version rather
   than dropped: \"I do not know this type\" is a fact the installing side has
   to see, and an omitted entry reads as \"nothing to check\"."
  [model]
  {:dialect    dialect/format-version
   :connectors (into (sorted-map)
                     (for [t (distinct (keep #(normalize-type (:type %))
                                             (vals (:sources model))))]
                       [t (contract-version t)]))})

;; ---------------------------------------------------------------------------
;; ${VAR} in a source entry: where they are, and substituting them
;; (doc 106 §106.14-12, owner ruling 2026-09-07)
;; ---------------------------------------------------------------------------

(defn config-variables
  "Every `${VAR}` reference written anywhere in one source entry, as
   `[{:name \"NIFI_TOKEN\" :path [:auth :value]} …]` — nested keys included,
   in deterministic (sorted by path, then name) order.

   **Why a walk and not a key list.** The credential store used to name three
   top-level sql keys, so api-v1's nested `[:auth :value]` and
   `[:tls :keystore_pass]` had nowhere to live (OQ-API-CREDENTIALS). The
   ruling keys the store by variable NAME, which makes «which variables does
   this entry need» the one question every face asks — the UI listing what to
   fill in, `declare_source`'s plan, the install plan, and resolution itself.

   Names and paths only, never values: a path is dialect configuration and a
   name is what a production administrator configures."
  [entry]
  (letfn [(walk [path v]
            (cond
              (string? v) (for [n (var-names v)] {:name n :path path})
              (map? v)    (mapcat (fn [[k x]] (walk (conj path k) x)) v)
              (coll? v)   (mapcat (fn [i x] (walk (conj path i) x))
                                  (range) v)
              :else       []))]
    (->> (walk [] entry)
         distinct
         (sort-by (juxt (comp pr-str :path) :name))
         vec)))

(def ^:dynamic *config-lookup*
  "Where a `${VAR}` written in a source entry gets its value on THIS host, as
   `(fn [var-name] value-or-nil)`.

   The default is the process environment, which is all a Server has. A
   Studio binds it to «its credential store first, the environment second»
   (doc 106 §106.14-12) — one binding, so every place a connector resolves a
   variable answers the same way. Before this existed the write-side
   validation (`validate-source`) read `System/getenv` directly while the
   connect path went through the store, so a store-only credential was
   refused at declaration and accepted at query time: the same entry, two
   answers, which is exactly the second semantics D1.8 forbids."
  (fn [n] (System/getenv n)))

(defn substitute-config
  "One source entry → the same entry with every `${VAR}` `lookup` can answer
   replaced by its value, at any depth.

   A variable `lookup` answers nil for is **left as written**: the reference
   is the actionable fact (\"this needs ${NIFI_TOKEN}\"), and blanking it
   would hand the connector an empty string that fails far from its cause —
   the same reasoning connector-api's `substitute` states. Callers refuse on
   `config-variables` before dialling; see
   `open.atlas.studio.credentials/missing-variables`."
  [entry lookup]
  (letfn [(walk [v]
            (cond
              (string? v) (str/replace v var-pattern
                                       (fn [[whole n]] (or (lookup n) whole)))
              (map? v)    (reduce-kv (fn [m k x] (assoc m k (walk x))) v v)
              (vector? v) (mapv walk v)
              (set? v)    (into #{} (map walk) v)
              (seq? v)    (map walk v)
              :else       v))]
    (walk entry)))

;; ---------------------------------------------------------------------------
;; What a thing's rows carry (doc 106 §106.14 items 13, 17 and 18)
;; ---------------------------------------------------------------------------

(defn carried-attributes
  "Whole model → `{namespace-string #{attribute …}}`: which attributes are
   CARRIED into the rows of each namespace, rather than read out of a record.

   Two kinds, one walk (doc 106 §106.14 items 13 and 17): the attributes a
   source binds from its connection entry — a templated `:base_url`'s host —
   ride every branch of every resolver on that source, and an in-record
   collection additionally carries the ancestor records' identity attributes.

   **This is the seam's answer, and the only one.** Item 18 lets such an
   attribute be part of a thing's identity, so `need-view` must ask the same
   question the engine asks when it writes those values into child rows; a
   second derivation would put `[host id]` on one face and `[id]` on the other,
   which is the split §106.5 rules out in as many words. It lives here because
   answering it needs the two SPI dispatch points above — `common/dialect`
   cannot depend on this module (D1.8) — while the tree walk itself is
   dialect's `carried-into-namespaces`, shared with the engine.

   Pure: a model in, a map out, no connection dialled."
  [model]
  (let [ids (into #{} (keep (fn [[a d]] (when (and (:identity? d) (not (:target d))) a)))
                  (:attributes model))]
    (reduce
     (fn [acc r]
       (let [src-def   (merge (get-in model [:sources (:source-ref r)]) (:source r))
             src-entry (or (get-in model [:sources (:source-ref r)]) src-def)
             input     (vec (:input r))
             bound     (vec (filter (set (connection-bound-attributes src-entry)) input))
             in-rec    (set (in-record-collection-attributes src-def))
             tree      (:output r)
             top       (dialect/record-identities
                        ids (into (filterv keyword? tree) input))]
         (merge-with into acc
                     (dialect/carried-into-namespaces tree bound in-rec ids top))))
     {}
     (:resolvers model))))

;; ---------------------------------------------------------------------------
;; Declared value classes (Contract 2.0 result check, D2.20)
;; ---------------------------------------------------------------------------

(def declared-value-types
  "The declared types `declared-value` converts."
  (set (keys values/converters)))

(defn declared-value
  "One mapped value -> the class the 2.0 result check accepts for its declared
   `type` (:decimal BigDecimal, :date LocalDate, :datetime Instant). nil, an
   unlisted type, or a value that would need a guess (a zoneless date-time,
   non-ISO text, NaN) comes back unchanged for the result check to judge.
   One rule for every connector: sql and api share it rather than each keeping
   a copy."
  [type v]
  (if-let [f (and (some? v) (get values/converters type))] (f v) v))

(defmulti semantic-adapter
  "Installed connector's pure semantic Binding validator and lowering adapter.
   Unknown connectors fail closed; requiring a connector installs its adapter."
  identity)
(defmethod semantic-adapter :default [_] nil)

(defn connection-shape
  "Shared connection form metadata. Probe operations only validate connection
   fields; they are never persisted as model operations or executed."
  [type]
  (get {"sql" {:connection-key :jdbc_url :probe {:query "SELECT 1"}
               :keeps-credentials? true}
        "api" {:connection-key :base_url :probe {:method "GET" :endpoint "/"}
               :declared [:base_url :auth :headers :health_endpoint] :keeps-credentials? false}
        "nifi" {:connection-key :base_url :probe {:method "GET" :endpoint "/"}
                :declared [:base_url :auth :headers :health_endpoint] :keeps-credentials? false}}
       (or type "sql")
       {:connection-key :jdbc_url :probe {:query "SELECT 1"} :keeps-credentials? true}))
