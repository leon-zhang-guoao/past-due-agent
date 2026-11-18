(ns open.atlas.common.dialect.impl.schema
  "结构层（实现顺序第 4 步）：malli 封闭 map + 词表。
   顶层 + 十类条目 + 操作块公共骨架；未知键 :error 并点名键与位置。
   连接器契约的扩展键（source 连接键、eql-param 扩展键、操作块）由
   已注册契约的 schema 补充——B0 先只登记四个 v1 契约的键面。
   跨条目的事这里不管（归 invariants）。carried 块完整校验：
   carried = 合法、往返、进 hash——不往 hash 里放没验过的东西。"
  (:require [malli.core :as m]
            [open.atlas.common.dialect.impl.finding :as finding]
            [open.atlas.common.dialect.impl.vocab :as vocab]))

;; ---------------------------------------------------------------------------
;; 0. schema 属性约定
;;
;; 本 ns 给若干叶子 schema 挂两个自定义 property，供尾部翻译器把 malli
;; explain 分类成 finding-v1 §3 的 code：
;;   :oap/vocab   —— 该处是受控词表（dialect-v1 §3），违反 → vocab-violation，
;;                   :expected 带词表集合；
;;   :oap/default —— 该键的缺省值不书写（dialect-v1 §4），写出来 → default-written。
;; ---------------------------------------------------------------------------

(defn- vocab-enum
  "受控词表 → [:enum …]，带词表集合以便翻译器填 :expected。
   词表集合是 vocab.clj 的常量——词表增补是契约变更，不是代码变更。"
  [s]
  (into [:enum {:oap/vocab s}] (sort-by pr-str s)))

(defn- non-default
  "缺省值不书写的键（dialect-v1 §4）：合法取值 = 词表去掉缺省值。
   写出缺省值即 :dialect.schema/default-written。"
  [allowed default]
  (into [:enum {:oap/default default :oap/allowed (disj allowed default)}]
        (sort-by pr-str (disj allowed default))))

;; ---------------------------------------------------------------------------
;; 1. 叶子与公共片段
;; ---------------------------------------------------------------------------

(def ^:private AttrKw
  "属性引用：全限定 keyword（D2.17 唯一拼法；字符串形不是合法输入）。"
  [:and :keyword [:fn {:error/message "attribute reference must be a qualified keyword"}
                  #(some? (namespace %))]])

(def ^:private NsStr  [:string {:min 1}])   ; namespace 引用是字符串
(def ^:private IdStr  [:string {:min 1}])
(def ^:private Synonyms [:vector :string])

(def ^:private CodeKey
  "码值键：EDN 类型与属性 :type 的严格一致性归 invariants（D2.27），
   结构层只圈定 :string|:integer|:boolean 三种键类型。"
  [:or :string :int :boolean])

(def ^:private Values
  "`:values` 条目：{码值 → {:description :label}}（dialect-v1 §3）。"
  [:map-of CodeKey
   [:map {:closed true}
    [:description {:optional true} :string]
    [:label {:optional true} :string]]])

;; carried 键（dialect-v1 §3「carried 键汇总」）：合法、往返、进 hash，
;; 故此处完整校验内部结构——不往 hash 里放没验过的东西。
;; （`:security {:roles […]}` 已退役，D5.12：它标着 carried 却有一个真消费者
;;  在拿它做角色门禁，同一个键两种语义。）
(def ^:private RowSecurity
  ;; D5.14 (owner 2026-09-03), E1: this key stopped being purely carried. The
  ;; cut-time check reads `:attribute` and verifies it is wired — an `:input`
  ;; of the resolver AND a bound placeholder in its statement. A key face that
  ;; a checker reads has to be closed: an extra key here would be a declaration
  ;; nobody verifies, which is exactly the state D5.14 was ruled to end
  ;; ("declaring row security while `:input []` is more dangerous than not
  ;; declaring it"). So: one key, required, qualified — surplus keys refused,
  ;; never silently ignored.
  [:map {:closed true}
   [:attribute qualified-keyword?]])

(def ^:private Cache
  [:map {:closed true} [:ttl-ms pos-int?]])

(def failure-categories
  "The controlled failure vocabulary (doc 60 E6.3, extended by doc 107 §107.4).
   Defined HERE rather than in connector-spi because the dialect must validate
   `:retry_on` against it and `common` dependencies run spi -> dialect, never
   back; `spi/error-categories` aliases this set so there is exactly one
   definition (CLAUDE.md: shared things sit on the interface, not in two
   copies)."
  #{:timeout :connection :source :mapping
    :unavailable :rate_limited :circuit_open :bulkhead_rejected :cancelled
    :unauthorized :not-found :input-rejected})

(def transient-failure-categories
  "The only categories a retry may be declared on (D6.4: retry needs a
   transient fault). Everything else is deterministic -- retrying it just
   repeats the same answer more expensively."
  #{:rate_limited :unavailable :connection})

(def protected-statuses
  "Statuses whose classification the platform keeps (doc 107 §107.5): a source
   may not re-map authorization, rate limiting or system failure into a
   per-row outcome and so slip past them."
  #{401 403 408 429 502 503 504})

(def status-map-targets
  "The categories a source may declare a status as meaning (doc 107 §107.5).

   The excluded ones are excluded for one reason each: `:timeout`,
   `:connection`, `:circuit_open`, `:bulkhead_rejected` and `:cancelled` are
   PLATFORM judgements about our own call -- a source cannot know, and must not
   be able to claim, that we timed out or that our breaker is open. `:mapping`
   is our model disagreeing with a response the source considers fine."
  #{:not-found :input-rejected :unauthorized :rate_limited :unavailable :source})

(def ^:private TransientCategory
  (into [:enum] (sort transient-failure-categories)))

(def ^:private StatusMap
  "`{400..599 → category}` (doc 107 §107.5). Success codes are outside the key
   domain: a 2xx is an answer, and a source that wants to call one a failure is
   describing a body, which is `:field_mapping`'s job. The finer rules -- which
   target is legal for which read shape and which status -- are a connector
   check (`:connector.source/invalid-shape`), not a schema one: they need the
   operation's read shape, which this schema does not see."
  [:map-of [:int {:min 400 :max 599}] (into [:enum] (sort status-map-targets))])

(def ^:private Policy
  "The SEMANTIC half of a source policy (doc 107 §107.3): what this data source
   is like, hence the modeller's to declare and the artifact's to carry.
   Closed -- an unlisted key would be a declaration nobody honours, which is
   the state §107.1 exists to end. `:status-map` and `:batch :concurrency`
   join it in later steps of the doc 107 plan."
  [:map {:closed true}
   [:max_retries {:optional true} nat-int?]
   [:retry_on {:optional true} [:set TransientCategory]]
   ;; doc 107 §107.5: the source-wide default for «how this upstream spells
   ;; failure». A binding overrides it per status.
   [:status-map {:optional true} StatusMap]
   ;; doc 107 §107.7 / §107.9 item 10 (owner 2026-09-18): how many chunks of a
   ;; batch may be in flight at once. SEMANTIC half deliberately -- «how much
   ;; parallelism will not anger this upstream» is knowledge about the upstream,
   ;; which is the modeller's, not an operations number. Default 1 keeps the
   ;; serial behaviour every published graph has today. The deployment's
   ;; `:bulkhead :concurrency` still bounds it (§107.3).
   [:batch {:optional true}
    [:map {:closed true} [:concurrency {:optional true} pos-int?]]]])

(def ^:private Resilience
  "doc 107 §107.3 key face, closed (owner ruling 2026-09-18, §107.12). Numbers
   are candidate values pending OQ-RESILIENCE-DEFAULTS; the key face is not.
   `:timeout_ms` is an ATTEMPT total (connect + first byte + read) -- its
   re-reading as an idle timeout is the api-v2 migration, not this schema."
  [:map {:closed true}
   [:timeout_ms {:optional true} pos-int?]
   [:max_retries {:optional true} nat-int?]
   [:retry_on {:optional true} [:set TransientCategory]]
   ;; [first max factor]
   [:backoff_ms {:optional true} [:tuple pos-int? pos-int? number?]]
   [:jitter {:optional true} [:and number? [:fn #(<= 0 % 1)]]]
   [:bulkhead {:optional true}
    [:map {:closed true}
     [:concurrency {:optional true} pos-int?]
     [:max_wait_ms {:optional true} nat-int?]]]
   [:circuit_breaker {:optional true}
    [:map {:closed true}
     ;; [min-calls percent]
     [:failure_ratio {:optional true} [:tuple pos-int? [:and int? [:fn #(<= 1 % 100)]]]]
     [:reset_ms {:optional true} pos-int?]]]])

;; ---------------------------------------------------------------------------
;; 2. 连接器契约的键面（docs/contracts/connectors/*.md）
;; ---------------------------------------------------------------------------

(def contract-types
  "已注册连接器契约名（dialect-v1 §3 source `:type`）。"
  #{"sql" "api" "static" "derived" "nifi"})

(def ^:private auth-types
  "api-v1 `:auth` 的判别符词表。"
  #{"oauth2" "api_key"})

(def ^:private http-methods
  "api-v1 操作块 `:method` 词表。"
  #{"GET" "POST" "PUT" "PATCH" "DELETE"})

(def ^:private http-mutation-methods
  "api-v1 能力：mutation 的 `:method` 限这四个。"
  #{"POST" "PUT" "PATCH" "DELETE"})

(def ^:private ApiAuth
  "api-v1「`:auth`：判别式块」：`:type` 是必填判别符，两个变体各自封闭。
   为什么不合成一张表：没有判别符只能按最松的并集校验，于是半配好的
   OAuth 块（缺 `:token_url`）能过闸，故障推迟到第一次真调用。
   凭证键只收 `${VAR}` 由写入端执法，此处只管键面与形状。"
  [:multi {:dispatch :type}
   ["oauth2"  [:map {:closed true}
               [:type [:= "oauth2"]]
               [:token_url :string]                       ; 必（api-v1）
               [:grant_type {:optional true} :string]
               [:client_id {:optional true} :string]
               [:client_secret {:optional true} :string]
               [:client_auth {:optional true} :string]
               [:scope {:optional true} :string]
               [:audience {:optional true} :string]
               [:username {:optional true} :string]
               [:password {:optional true} :string]
               [:refresh_token {:optional true} :string]
               [:extra_params {:optional true} [:map-of :string :string]]
               [:expiry_skew_s {:optional true} nat-int?]
               [:timeout_ms {:optional true} pos-int?]
               [:header_name {:optional true} :string]
               [:header_prefix {:optional true} :string]]]
   ["api_key" [:map {:closed true}
               [:type [:= "api_key"]]
               [:value :string]                           ; 必、凭证（api-v1）
               [:header_name {:optional true} :string]
               [:header_prefix {:optional true} :string]]]
   ;; 判别符缺席 → dispatch nil 落这里 → missing-key；
   ;; 判别符不在词表 → vocab-violation。
   [::m/default [:map [:type (vocab-enum auth-types)]]]])

(defn- source-entry
  "source 条目：核心键（dialect-v1 §3）+ 该 type 的连接键（连接器契约）。"
  [type-name conn-keys]
  (into [:map {:closed true}
         [:type [:= type-name]]
         [:label {:optional true} :string]
         [:description {:optional true} :string]
         ;; **Runtime-set, never authored.** Which contract version's reading of
         ;; `:timeout_ms` this source runs under: api-v2 (an attempt TOTAL) when
         ;; the graph declared `:source/policy-v1`, api-v1 (an idle timeout)
         ;; otherwise (doc 107 §107.2). It is on the source entry because that
         ;; is what reaches the connector, and it is a KEY rather than a
         ;; convention because a closed schema that silently tolerated it would
         ;; be a hole the next runtime-set flag slips through unnoticed.
         [:semantic/attempt-total-timeout? {:optional true} :boolean]]
        conn-keys))

(def ^:private ApiConnectionFields
  [[:base_url :string]                       ; 必（api-v1）
                             [:headers {:optional true} [:map-of :string :string]]
                             [:auth {:optional true} ApiAuth]
                             [:tls {:optional true}
                              [:map {:closed true}
                               [:keystore :string]
                               [:keystore_type {:optional true} :string]
                               [:keystore_pass {:optional true} :string]
                               [:truststore {:optional true} :string]
                               [:truststore_type {:optional true} :string]
                               [:truststore_pass {:optional true} :string]]]
                             [:ssl_insecure? {:optional true} :boolean]
                             [:health_endpoint {:optional true} :string]
                             ;; E2b：这个 API 认哪些 header 承载关联 id。声明了才发
                             ;; ——往第三方请求里塞建模者没写过的 header，是在改一次
                             ;; 他授权之外的调用（有的 API 白名单 header，有的把未知
                             ;; header 计入签名）。`User-Agent` 不在这里：它是行业惯例、
                             ;; 无条件发，见 spi/application-name。
                             [:trace_headers {:optional true}
                              [:map {:closed true}
                               [:request-id {:optional true} [:string {:min 1}]]
                               [:graph-id {:optional true} [:string {:min 1}]]]]
                             [:resilience {:optional true} Resilience]])

(def ^:private Source
  [:multi {:dispatch :type}
   ["sql"     (source-entry "sql"
                            [[:jdbc_url :string]                       ; 必（sql-v1）
                             [:username {:optional true} :string]
                             [:password {:optional true} :string]
                             [:max_pool_size {:optional true} pos-int?]
                             [:min_idle {:optional true} nat-int?]
                             [:resilience {:optional true} Resilience]])]
   ["api"     (source-entry "api"
                            ApiConnectionFields)]
   ["nifi" (source-entry "nifi" ApiConnectionFields)]
   ;; static-v1 / derived-v1：无扩展键
   ["static"  (source-entry "static" [])]
   ["derived" (source-entry "derived" [])]
   [::m/default [:map [:type (vocab-enum contract-types)]]]])

;; --- 操作块（各连接器契约「操作块 schema（封闭）」）-------------------------

;; A field mapping value is a source path — or, for a JOIN attribute, that
;; branch's OWN `{attr → path}` scope (doc 106 §106.14 item 6, owner
;; 2026-09-07). A self relation needs the scope: parent and child share a
;; namespace, so the branch's leaf and the resolver's own identity output are
;; the same attribute kw and one flat row cannot carry two values for it.
;; api-v1 `:query_params {名 → 模板}`: the names are upstream parameter names,
;; hence strings. Named rather than inlined because the tool face coerces a
;; JSON client's keys against this very schema (§106.14 item 9) — one
;; definition, not a copy at the tool face.
(def ^:private QueryParams [:map-of :string :string])

(def ^:private FieldMapping
  [:map-of AttrKw [:or :string [:map-of AttrKw :string]]])

(def ^:private ApiFieldMapping
  "api's `:field_mapping`, which has one shape sql does not: a branch may name
   the ARRAY its rows come from under `:_from` (doc 106 §106.14 item 15,
   api-v1 «记录内集合»), the remaining keys being that element's leaf paths —
   themselves possibly branches, so the value recurses."
  [:schema {:registry {::branch [:map-of [:or [:= :_from] AttrKw]
                                 [:or :string [:ref ::branch]]]}}
   [:map-of AttrKw [:or :string [:ref ::branch]]]])

(def ^:private SqlOp
  [:map {:closed true}
   [:type [:= "sql"]]
   ;; doc 107 §107.3: a binding's override of the SEMANTIC half of the source
   ;; policy, lowered into the operation block. The capacity half never appears
   ;; here -- it lives on the deployment's Source entry.
   [:policy {:optional true} Policy]
   [:query [:string {:min 1}]]
   [:params {:optional true} [:map-of :keyword [:or AttrKw :keyword]]]
   [:field_mapping {:optional true} FieldMapping]
   [:batch {:optional true}
    [:map {:closed true}
     ;; Vectors are the compound-key spelling: the i-th parent key pairs with
     ;; the i-th child column, and the connector compares them as a row value
     ;; (owner 2026-09-07, doc 106 §106.14 item 14).
     [:parent-key [:or AttrKw [:vector {:min 1} AttrKw]]]
     [:child-fk [:or :string [:vector {:min 1} :string]]]
     [:placeholder :string]]]])

(defn- api-op [methods]
  [:map {:closed true}
   [:type [:= "api"]]
   ;; doc 107 §107.3: a binding's override of the SEMANTIC half of the source
   ;; policy, lowered into the operation block. The capacity half never appears
   ;; here -- it lives on the deployment's Source entry.
   [:policy {:optional true} Policy]
   [:method (vocab-enum methods)]
   [:endpoint [:string {:min 1}]]
   [:query_params {:optional true} QueryParams]
   [:json_body {:optional true} :any]
   [:data_path {:optional true} :string]
   [:field_mapping {:optional true} ApiFieldMapping]
   ;; doc 107 §107.5 (owner 2026-09-18): how THIS upstream spells failure.
   ;; It supersedes `:isolate-statuses` -- the 2026-09-17 isolation ruling wrote
   ;; one API's accidental choice of 400 into the platform; this asks the source.
   [:status-map {:optional true} StatusMap]
   [:batch {:optional true}
    [:map {:closed true}
     ;; Vectors are the compound-key spelling: the i-th parent key pairs with
     ;; the i-th child column, and the connector compares them as a row value
     ;; (owner 2026-09-07, doc 106 §106.14 item 14).
     [:parent-key [:or AttrKw [:vector {:min 1} AttrKw]]]
     [:child-fk [:or :string [:vector {:min 1} :string]]]
     [:placeholder :string]
     [:separator {:optional true} :string]
     [:chunk-size {:optional true} pos-int?]
     [:isolate-statuses {:optional true} [:set {:min 1} [:enum 400 404]]]]]])

(def ^:private StaticOp
  [:map {:closed true}
   [:type [:= "static"]]
   [:data [:or [:map-of :keyword :any] [:sequential [:map-of :keyword :any]]]]
   ;; A VECTOR is a compound key: the row matches when every listed attribute
  ;; matches (owner 2026-09-07, doc 106 §106.14 item 14). A bare kw is the
  ;; single-key spelling and stays legal — the ruling adds no dialect key.
  [:match-on {:optional true} [:or AttrKw [:vector {:min 1} AttrKw]]]])

(def ^:private DerivedOp
  [:map {:closed true}
   [:type [:= "derived"]]
   [:expr [:string {:min 1}]]])

(def ^:private NifiOp
  [:map {:closed true}
   [:type [:= "nifi"]]
   [:operation [:enum :provenance :lineage :event :search-options]]
   [:parameter_mapping [:map-of :keyword AttrKw]]
   [:eql_parameter_mapping {:optional true} [:map-of :keyword :keyword]]
   [:defaults {:optional true} [:map-of :keyword [:or :string :int]]]
   [:data_path {:optional true} :string]
   [:field_mapping ApiFieldMapping]])

(def ^:private ResolverOp
  [:multi {:dispatch :type}
   ["sql" SqlOp] ["api" (api-op http-methods)]
   ["static" StaticOp] ["derived" DerivedOp] ["nifi" NifiOp]
   [::m/default [:map [:type (vocab-enum contract-types)]]]])

(defn- no-batch
  "mutation 操作块不得声明 :batch——batch 是消 N+1 的读优化，写路径
   声明它必是配置错误（dialect-v1 §3 裁定）。"
  [op-schema]
  [:and op-schema
   [:fn {:error/message "mutation operation block must not declare :batch"}
    #(not (contains? % :batch))]])

(def ^:private MutationOp
  ;; 能力（连接器契约「能力」节）：mutation 仅 sql / api。
  [:multi {:dispatch :type}
   ["sql" (no-batch SqlOp)] ["api" (no-batch (api-op http-mutation-methods))]
   [::m/default [:map [:type (vocab-enum #{"sql" "api"})]]]])

;; --- eql-param（dialect-v1 §3 + 契约扩展键 D2.20）---------------------------

(defn- eql-param [ext-keys]
  (into [:map {:closed true}
         [:attribute {:optional true} AttrKw]
         [:op {:optional true} (vocab-enum vocab/ops)]
         [:allowed {:optional true} [:vector CodeKey]]
         [:default {:optional true} :any]
         [:type {:optional true} (vocab-enum vocab/attribute-types)]
         [:description {:optional true} :string]]
        (conj (vec ext-keys) [:required? {:optional true} [:= true]])))

(def ^:private eql-param-ext
  "契约声明的 eql-param 扩展键：sql/static/derived 无，api 两个。"
  {"sql" [] "static" [] "derived" []
   "api" [[:field {:optional true} :string]
          [:param_location {:optional true} (vocab-enum #{"query" "body"})]]})

;; ---------------------------------------------------------------------------
;; 3. 条目 schema
;; ---------------------------------------------------------------------------

(def ^:private NamespaceEntry
  ;; D2.29 (owner 2026-09-02): the `:navigations` registry that used to live
  ;; here is retired.  A branch in an `:output` tree is a *join attribute*, and
  ;; join attributes live in `:attributes` like every other attribute — see the
  ;; Attribute schema below.  A namespace entry is now purely descriptive.
  [:map {:closed true}
   [:description {:optional true} :string]
   ;; 词表成员在 20-dialect.md D2.7 表列举，常量归 vocab.clj。
   [:kind {:optional true} (vocab-enum vocab/namespace-kinds)]
   [:label {:optional true} :string]
   [:notes {:optional true} :string]
   [:synonyms {:optional true} Synonyms]])

(def ^:private ScalarAttribute
  "A leaf of the `:output` tree: it carries a value, hence `:type` (D2.29).
   `:collection?` retired with D2.29 — a declared collection IS a join
   attribute whose `:target` happens to be its own namespace."
  [:map {:closed true}
   [:type (vocab-enum vocab/attribute-types)]
   [:deprecated? {:optional true} :boolean]
   [:description {:optional true} :string]
   [:format {:optional true} (vocab-enum vocab/formats)]
   [:identity? {:optional true} :boolean]
   [:label {:optional true} :string]
   ;; D2.28 `:listed?`：缺省 `true`，所以**只有 `false` 是合法书写**（同 `:curated?`）。
   ;; 不区分 token；图本身是唯一的数据访问边界。
   [:listed? {:optional true} (non-default #{true false} (:listed? vocab/defaults))]
   [:replaced-by {:optional true} AttrKw]
   [:synonyms {:optional true} Synonyms]
   [:term {:optional true} IdStr]
   [:term-context {:optional true} :string]
   [:unit {:optional true} :string]
   [:values {:optional true} Values]])

(def ^:private JoinAttribute
  "A branch of the `:output` tree: it carries *rows of another entity*, hence
   `:target` and no `:type` (D2.29, owner 2026-09-02).

   `:target` is a HARD GATE, exactly as it was on the retired navigation
   entry: it is the only thing that says which entity a nested sub-query is
   asked against, so the run semantics (D2.14-style) have no fallback if it
   is absent.

   **No `:term`.** Terms carry `:format`/`:values`/`:label` — a value
   vocabulary — and a join has no value to describe.  Binding one would give
   the join a second, contradictory identity, which is the very thing D2.29
   collapses.  v1 does not offer it (owner-accepted default); the closed key
   set below IS the rejection.

   **No `:type`.** This is where the `:ref-many` lesson (D2.16) becomes
   structure rather than documentation: a join key can never be handed a
   scalar type, because the two key sets are disjoint and the entry
   dispatches on `:target`.  「类型词表不为语法背锅」 is now a schema shape."
  [:map {:closed true}
   [:target NsStr]
   [:cardinality {:optional true} (vocab-enum vocab/cardinalities)]
   [:deprecated? {:optional true} :boolean]
   [:description {:optional true} :string]
   [:label {:optional true} :string]
   [:replaced-by {:optional true} AttrKw]
   [:synonyms {:optional true} Synonyms]])

(def ^:private UnionAttribute
  "A join branch with SEVERAL target entities (Semantic contract 2.0 §6.1,
   lowered from the vocabulary's `:attribute/targets`; written by the 2.0
   compiler only). Same key face as `JoinAttribute` with `:targets` in place
   of `:target`: the two are exclusive, and an entry writing both dispatches
   to no variant, which is the rejection."
  (into [:map {:closed true}
         [:targets [:set {:min 2} NsStr]]]
        (remove #(= :target (first %)) (drop 2 JoinAttribute))))

(def ^:private Attribute
  "Discriminated on the presence of `:target` / `:targets` (D2.29; 2.0 §6.1).
   Writing both `:type` and `:target` therefore cannot validate under either
   branch — the entry dispatches to `:join` and `:type` comes back as an
   unknown key.  That is the intended rejection: scalar and join are mutually
   exclusive kinds, not a map with two optional halves."
  [:multi {:dispatch (fn [m] (cond (not (map? m)) :scalar
                                   (and (contains? m :target) (contains? m :targets)) :both
                                   (contains? m :target) :join
                                   (contains? m :targets) :union
                                   :else :scalar))}
   [:join   JoinAttribute]
   [:union  UnionAttribute]
   [:scalar ScalarAttribute]])

(def ^:private Term
  [:map {:closed true}
   [:label :string]
   [:deprecated? {:optional true} :boolean]
   [:description {:optional true} :string]
   [:format {:optional true} (vocab-enum vocab/formats)]
   [:replaced-by {:optional true} IdStr]
   [:synonyms {:optional true} Synonyms]
   [:values {:optional true} Values]])

(def ^:private UnitEntry
  [:map {:closed true}
   [:description {:optional true} :string]
   [:label {:optional true} :string]])

(def ^:private Output
  "output 语法树（D2.16/D2.29）：叶是标量属性 kw，分支是 {join-属性-kw [子树…]}。
   两种键都是 `:attributes` 里的登记物；哪个是哪个由条目形状决定，不由语法位置
   决定（invariants 交叉核对二者一致）。

   Semantic contract 2.0 §6.5 adds two branch forms, written by the 2.0
   compiler only: a union `{join-kw {identity-kw [子树…] …}}` whose keys are the
   branch entities' identity attributes (EQL's own union spelling), and a
   recursion `{join-kw n}` whose value is the declared depth ceiling (1..16)."
  [:schema {:registry {::node [:or AttrKw
                               [:map-of AttrKw [:ref ::tree]]
                               [:map-of AttrKw [:map-of AttrKw [:ref ::tree]]]
                               [:map-of AttrKw [:int {:min 1 :max 16}]]]
                       ::tree [:vector [:ref ::node]]}}
   [:ref ::tree]])

(defn- resolver-entry
  "resolver 条目（dialect-v1 §3）。op / eql-param 键面按 `:source` 的
   契约 type 收口——同一份条目 schema，按 type 生成四份。"
  [op-schema param-ext]
  [:map {:closed true}
   [:id IdStr]
   [:input [:vector AttrKw]]
   ;; Semantic contract 2.0 §2.4: `:input` stays the flat vector of roots so
   ;; every consumer that reads it keeps working; which roots are optional and
   ;; which carry a subtree ride in these two keys, written only by the 2.0
   ;; compiler and read only by the engine's `::pco/input` construction.
   [:input-optional {:optional true} [:set AttrKw]]
   [:input-nested {:optional true} [:map-of AttrKw Output]]
   ;; Semantic contract 2.0 §4.4: the rank among several producers of one
   ;; attribute, written by the 2.0 compiler only and read only by the
   ;; engine's `::pco/priority`. Absent means 0; higher wins.
   [:priority {:optional true} [:int {:min 0}]]
   [:output Output]
   [:result_type (vocab-enum vocab/result-types)]
   [:source op-schema]
   [:source-ref {:optional true} IdStr]
   [:curated? {:optional true} (non-default #{true false} (:curated? vocab/defaults))]
   [:description {:optional true} :string]
   [:eql-params {:optional true} [:map-of :keyword (eql-param param-ext)]]
   [:label {:optional true} :string]
   ;; list 必带 :limit 是跨键不变量，归 invariants；结构层只管形状
   [:limit {:optional true}
    [:map {:closed true} [:default pos-int?] [:max pos-int?]]]
   [:order {:optional true}
    [:map {:closed true}
     [:allowed {:optional true} [:vector AttrKw]]
     [:default {:optional true} [:tuple AttrKw [:enum :asc :desc]]]]]
   [:cache {:optional true} Cache]                              ; carried
   [:row-security {:optional true} RowSecurity]])               ; carried


(def ^:private Resolver
  [:multi {:dispatch #(get-in % [:source :type])}
   ["sql"     (resolver-entry SqlOp (eql-param-ext "sql"))]
   ["api"     (resolver-entry (api-op http-methods) (eql-param-ext "api"))]
   ["nifi" (resolver-entry NifiOp [])]
   ["static"  (resolver-entry StaticOp (eql-param-ext "static"))]
   ["derived" (resolver-entry DerivedOp (eql-param-ext "derived"))]
   [::m/default (resolver-entry ResolverOp [])]])

(def ^:private Mutation
  [:map {:closed true}
   [:id IdStr]
   ;; Semantic contract 2.0 §2.4, same as on a resolver: the roots stay in
   ;; `:params`; these two say which are optional and which carry a subtree.
   [:input-optional {:optional true} [:set AttrKw]]
   [:input-nested {:optional true} [:map-of AttrKw Output]]
   [:result_type (vocab-enum vocab/mutation-result-types)]
   [:source MutationOp]
   [:description {:optional true} :string]
   [:idempotency {:optional true}
    [:or [:= :natural] [:map {:closed true} [:key AttrKw]]]]
   [:label {:optional true} :string]
   [:params {:optional true}
    [:map-of AttrKw
     [:map {:closed true}
      ;; 缺省 :required? false 不书写（dialect-v1 §4）
      [:required? {:optional true} (non-default #{true false} (:required? vocab/defaults))]]]]
   [:returns {:optional true} Output]
   [:row-security {:optional true} RowSecurity]                 ; carried
   [:source-ref {:optional true} IdStr]])

(def ^:private Link
  [:map {:closed true}
   [:from AttrKw]
   [:to AttrKw]
   [:direction {:optional true}
    (non-default vocab/link-directions (:direction vocab/defaults))]
   [:cardinality {:optional true} (vocab-enum vocab/cardinalities)]
   [:condition {:optional true} :string]
   [:description {:optional true} :string]])

(def ^:private Metric
  [:map {:closed true}
   [:entity NsStr]
   ;; carried：无执行者阶段的 metric :expression，内部结构完整校验
   [:expression
    [:map {:closed true}
     [:contract [:= :duckdb-spill/v1]]
     [:text [:string {:min 1}]]]]
   [:description {:optional true} :string]
   [:dimensions {:optional true} [:vector AttrKw]]
   [:grain {:optional true} (vocab-enum vocab/metric-grains)]
   [:label {:optional true} :string]
   [:synonyms {:optional true} Synonyms]])

(def Dialect
  "顶层（dialect-v1 §2）：九个键全部可选，顶层封闭。"
  [:map {:closed true}
   [:attributes {:optional true} [:map-of AttrKw Attribute]]
   [:links {:optional true} [:vector Link]]
   [:metrics {:optional true} [:map-of IdStr Metric]]
   [:mutations {:optional true} [:vector Mutation]]
   [:namespaces {:optional true} [:map-of NsStr NamespaceEntry]]
   [:resolvers {:optional true} [:vector Resolver]]
   [:sources {:optional true} [:map-of IdStr Source]]
   [:terms {:optional true} [:map-of IdStr Term]]
   [:units {:optional true} [:map-of IdStr UnitEntry]]])

(def fragments
  "The contract FRAGMENTS a tool face hands a JSON-shaped value to, by name.

   A modelling tool never receives a whole dialect: it receives one source
   entry, one operation block, one code list. The key coercion of doc 106
   §106.14 item 9 is schema-driven, so it needs the schema that governs the
   position — and a fragment is exactly that schema, not a second copy of it.
   Naming the fragments here rather than at the tool face keeps the contract's
   shapes in the one place that defines them (D1.8)."
  {:source          Source
   :resolver-source ResolverOp
   :mutation-source MutationOp
   :values          Values
   :term            Term
   :query-params    QueryParams
   :field-mapping   FieldMapping
   :eql-params      [:map-of :keyword (eql-param (eql-param-ext "api"))]
   :attribute       Attribute
   :dialect         Dialect})

(def entry-schemas
  "The nine pool object types → the entry schema that governs one of them.

   The editor's note lines are DERIVED from these (owner ruling 2026-09-07,
   doc 84 §8.4.25): a hand-written key list in the browser would be a second
   statement of the closed key set, i.e. exactly the drift D1.8 forbids. So the
   key face is read off the same schemas `check` and `coerce-json-keys` use."
  {"attribute" Attribute
   "link"      Link
   "metric"    Metric
   "mutation"  Mutation
   "namespace" NamespaceEntry
   "resolver"  Resolver
   "source"    Source
   "term"      Term
   "unit"      UnitEntry})

(defn- enum-values
  "The closed value list of a leaf schema, or nil. Covers both spellings a
   vocabulary is written with here: `vocab-enum` / `non-default` produce
   `[:enum …]`, a discriminator produces `[:= \"sql\"]`."
  [s]
  (let [s (m/deref s)]
    (case (m/type s)
      :enum (vec (m/children s))
      :=    (vec (m/children s))
      nil)))

(defn- map-key-face
  "The declared keys of one closed map schema, in declaration order."
  [s variant]
  (vec (for [[k props v] (m/children (m/deref s))]
         (cond-> {:key k :required? (not (:optional props))}
           variant (assoc :variant variant)
           (enum-values v) (assoc :enum (enum-values v))))))

(defn key-face
  "The ordered key face of one object type: which keys the closed schema
   declares, which are required, and the closed value list where there is one.

   Multi-variant types (an attribute is scalar-or-join, a source is one per
   connector) contribute every variant's keys in variant order; a key that not
   every variant declares carries `:variants`, the ones that do. The
   `::m/default` branch is skipped — it exists to turn a missing or unknown
   discriminator into a finding, not to declare a key face."
  [type]
  (when-let [s (get entry-schemas type)]
    (let [s (m/schema s)]
      (if (= :multi (m/type s))
        (let [variants (remove #(= ::m/default (first %)) (m/children s))
              vname    (fn [v] (if (keyword? v) (name v) (str v)))
              names    (mapv (comp vname first) variants)
              faces    (vec (mapcat (fn [[v _ vs]] (map-key-face vs (vname v))) variants))
              by-key   (reduce (fn [acc {:keys [key variant enum] :as e}]
                                 (update acc key
                                         (fn [prev]
                                           (cond-> (-> (or prev (dissoc e :variant))
                                                       (update :variants (fnil conj []) variant))
                                             ;; A discriminator's value list is
                                             ;; per variant (`[:= "sql"]`); the
                                             ;; face states what the key accepts
                                             ;; across all of them.
                                             enum (update :enum #(vec (distinct (concat % enum))))))))
                               {} faces)]
          (vec (for [k (distinct (map :key faces))
                     :let [e (get by-key k)]]
                 (if (= (count (:variants e)) (count names))
                   (dissoc e :variants)
                   e))))
        (map-key-face s nil)))))

(def ^:private compiled (m/schema Dialect))

;; ---------------------------------------------------------------------------
;; 4. explain → finding 翻译器（本 ns 尾部；finding-v1 §2/§3/§5）
;;
;; malli 的 `:path` 是 schema 内部路径（含 :multi 的 dispatch 值等），
;; 对作者无意义；`:in` 才是数据路径 = 方言键路径（如 [:resolvers 3 :output]）。
;; 翻译器一律取 `:in`。
;; ---------------------------------------------------------------------------

(defn- redact-path?
  "不泄漏约束（finding-v1 §5）：操作块内容与 metric 表达式一律截断。"
  [path]
  (boolean (some #{:source :expression} path)))

(defn- got-of [path value]
  (if (redact-path? path) (finding/redact value) value))

(defn- props [error]
  (some-> error :schema m/properties))

(def ^:private shape-examples
  "One LITERAL example per declared shape, for `:expected` on a shape refusal
   (owner ruling 2026-09-07, doc 106 §106.14 item 9).

   Why an example rather than a fuller schema: the measured friction was four
   consecutive refusals across the JSON↔EDN boundary in one modelling session
   (OQ-MCP-WRITE-ERGONOMICS item 1), and what the caller could not recover from
   a type name was the SPELLING. The example lives in the finding, not in the
   declaration a client reads — a client cannot build a call from it, so it is
   not a second schema; it is one line of prose that happens to be exact."
  {:keyword "a qualified keyword, e.g. :cis.party/customer_no"
   :string  "a string, e.g. \"cis.party\""
   :int     "an integer, e.g. 42"
   :boolean "true or false"
   :vector  "a vector, e.g. [:cis.party/name]"
   :map     "a map of the keys this contract declares"
   :map-of  "a map, e.g. {:cis.party/name \"$.name\"}"
   :=       "exactly the value the contract names here"
   :enum    "one of the values this key's vocabulary lists"})

(defn- shape-example
  "The literal example for the schema that refused, or nil. `:and` (the shape
   `AttrKw` is spelled with) is unwrapped to the constraint that carries the
   meaning — a bare \":and\" tells the caller nothing."
  [schema]
  (when schema
    (let [t (m/type schema)]
      (or (get shape-examples t)
          (when (= :and t)
            (some #(get shape-examples (m/type %)) (m/children schema)))))))

(defn- error->finding [{:keys [in value type] :as error}]
  (let [path (vec in)
        p    (props error)]
    (cond
      (= type :malli.core/extra-key)
      (finding/make :dialect.schema/unknown-key :error path
                    (str "unknown key " (pr-str (last path)) " is not in the closed key set")
                    :got (last path)
                    :refs ["dialect-v1 §2" "dialect-v1 §7"])

      (= type :malli.core/missing-key)
      (finding/make :dialect.schema/missing-key :error path
                    (str "required key " (pr-str (last path)) " is missing")
                    :expected (last path)
                    :refs ["dialect-v1 §3"])

      (contains? p :oap/default)
      (finding/make :dialect.schema/default-written :error path
                    (str "default value " (pr-str (:oap/default p))
                         " must not be written out; omit the key")
                    :expected (:oap/allowed p)
                    :got (got-of path value)
                    :refs ["dialect-v1 §4"])

      (contains? p :oap/vocab)
      (finding/make :dialect.schema/vocab-violation :error path
                    (str "value " (pr-str value) " is outside the controlled vocabulary")
                    :expected (:oap/vocab p)
                    :got (got-of path value)
                    :refs ["dialect-v1 §3"])

      :else
      (finding/make :dialect.schema/invalid-shape :error path
                    "value does not match the shape declared by the contract"
                    :expected (shape-example (:schema error))
                    :got (got-of path value)
                    :refs ["dialect-v1 §3"]))))

(defn- dedupe-findings
  "同一位置在 :multi / :or 分支下可能重复报；按 [:path :code :got] 去重，
   保证同一输入两次校验产出逐字节相同（finding-v1 §6）。"
  [findings]
  (->> findings (reduce (fn [[seen acc] f]
                          (let [k [(:path f) (:code f) (:got f)]]
                            (if (seen k) [seen acc] [(conj seen k) (conj acc f)])))
                        [#{} []])
       second))

(defn check
  "data → [finding…]。只做单条目结构；合法时空向量。"
  [data]
  (if-not (map? data)
    [(finding/make :dialect.schema/invalid-shape :error []
                   "dialect root must be an EDN map"
                   :got (finding/redact data)
                   :refs ["dialect-v1 §2"])]
    (->> (some-> (m/explain compiled data) :errors)
         (map error->finding)
         dedupe-findings
         finding/sort-findings
         vec)))
