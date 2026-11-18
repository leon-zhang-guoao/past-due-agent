(ns open.atlas.common.toolkit.impl.registry
  "单一 registry：**一份声明**同时产出 `tools/list` 与 dispatch。

   principles「MCP 与工具面」：「在一处声明、在另一处分派会漂移，而漂移在客户端
   调到一个不存在的工具之前是看不见的」。本 ns 让这句话在**类型层面**成立——
   `tools-list` 与 `dispatch` 都只从 `:by-name` 这一个 map 取数，`:order` 只是它的
   键序，两者不可能各自算出不同的集合。

   ## 能力过滤只算一次（studio/tools README 裁定 ②）

   `registry` 在**构造时**按能力集切掉不该挂载的声明，之后 `:by-name` 里就没有
   它们了。没有写能力时，写工具在 `tools/list` 里缺席，dispatch 报的是
   `:toolkit.tool/unknown`——与「这个名字根本不存在」逐字相同。

   principles：「一个存在且永远失败的工具会诱使客户端反复重试，还告诉攻击者门后
   有什么；不存在才是『这台 Studio 是只读的』的诚实说法。」如果过滤写在每个
   handler 开头的一句 if，`tools/list` 就要**再算一次**同样的判断——那正是这条
   规则要防的那种两处判断。

   ## 声明形状的错误是 assert，运行期的错误是 finding

   声明是**代码**：重名、缺 handler、能力不认识，都是写这个模块的人犯的错，
   在 registry 构造时 assert 炸掉（同 finding/impl 的「未注册 code 直接 assert
   失败」）。入参不合 schema 是**调用方**犯的错，走 finding-v1 结构化拒绝。

   ## 这里的 schema 只管**工具入参**，不管方言

   CLAUDE.md「不写第二个 schema」说的是模型的结构校验——那一律回落到
   `dialect/read-dialect` 与池。工具入参 schema 管的是另一件事：`tools/list` 里
   那份 JSON Schema 是**给客户端看的**，没有它 MCP 客户端连怎么调都不知道。
   于是这里的校验刻意停在「键面封闭、必填在、类型与枚举对」，条目内容一个字
   不判，交给下游。"
  (:require [clojure.set :as set]
            [open.atlas.common.toolkit.impl.finding :as f]))

(def capabilities
  "两把钥匙（principles）。`:write` **独立签发绝不从 `:read` 派生**——
   这里只是能力名的词表，签发与比较是 Builder MCP（8.4）的事，但词表在这里定死，
   免得两处各拼一遍。"
  #{:read :write})

(def ^:private param-types
  "入参的值形态 → JSON Schema 的 \"type\"。

   `:any` 不产出 \"type\"：`execute_action` 的 `:id` 按类型分别是 keyword、
   字符串、二元向量（pool/impl/model 的 `:id-kind`），JSON Schema 说不出这件事，
   **说一半比不说糟**——客户端会照着半句去构造。真正的判定在 pool，
   那里说得出「这个类型的身份该长什么样」。"
  {:string  "string"
   :integer "integer"
   :boolean "boolean"
   :keyword "string"
   :map     "object"
   :vector  "array"
   :any     nil})

(defn- check-param! [tool-name {:keys [name type required? description enum] :as p}]
  (assert (string? name) (str tool-name ": param :name must be a string"))
  (assert (contains? param-types type)
          (str tool-name "/" name ": unknown param type " (pr-str type)))
  (assert (string? description) (str tool-name "/" name ": param needs a :description"))
  (assert (or (nil? required?) (boolean? required?)))
  (assert (or (nil? enum) (and (vector? enum) (seq enum))))
  (assert (or (nil? (:coerce p)) (ifn? (:coerce p)))
          (str tool-name "/" name ": :coerce must be a function"))
  (assert (empty? (set/difference (set (keys p))
                                  #{:name :type :required? :description :enum :items :coerce}))
          (str tool-name "/" name ": param key set is closed")))

(defn- check-decl! [{:keys [name description capability params handler] :as d}]
  (assert (string? name) (str "tool :name must be a string: " (pr-str name)))
  (assert (string? description) (str name ": tool needs a :description"))
  (assert (contains? capabilities capability)
          (str name ": :capability must be one of " (pr-str capabilities)))
  (assert (vector? params) (str name ": :params must be a vector (顺序进 tools/list)"))
  (assert (ifn? handler) (str name ": :handler must be a function"))
  (assert (empty? (set/difference (set (keys d))
                                  #{:name :description :capability :params :handler}))
          (str name ": tool declaration key set is closed"))
  (run! #(check-param! name %) params)
  (let [ns- (map :name params)]
    (assert (= (count ns-) (count (set ns-))) (str name ": duplicate param name"))))

(defn registry
  "[声明…] × 能力集 → registry。

   **能力过滤在这里发生，且只发生这一次**（裁定 ②）。返回的 map 里
   `:order` 与 `:by-name` 说的是同一件事，`tools-list` 与 `dispatch` 各取一面。"
  [decls caps]
  (run! check-decl! decls)
  (let [names (map :name decls)]
    (assert (= (count names) (count (set names))) "duplicate tool name in registry"))
  (assert (every? capabilities caps) (str "unknown capability: " (pr-str caps)))
  (let [caps    (set caps)
        visible (filterv #(contains? caps (:capability %)) decls)]
    {:capabilities caps
     ;; 声明顺序即 tools/list 顺序：`tools/list` 两次调用逐字节相同，
     ;; 客户端 diff 得动（同 dialect-v1 §4 的规范形纪律）。
     :order        (mapv :name visible)
     :by-name      (into {} (map (juxt :name identity)) visible)}))

;; ---------------------------------------------------------------------------
;; tools/list
;; ---------------------------------------------------------------------------

(defn- input-schema [params]
  (cond-> {"type" "object"
           "properties"
           (into {} (for [{:keys [name type description enum items]} params]
                      [name (cond-> {"description" description}
                              (param-types type) (assoc "type" (param-types type))
                              ;; `:items` says what a vector HOLDS. Optional,
                              ;; but a client generating a call reads it — an
                              ;; array announced without its element type is a
                              ;; schema that stopped answering a question it
                              ;; used to answer, and the caller finds out by
                              ;; being refused.
                              items (assoc "items" {"type" (clojure.core/name items)})
                              enum (assoc "enum" (mapv #(if (keyword? %) (str (symbol %)) %)
                                                       enum)))]))
           ;; 键面封闭（dialect-v1 §7 的同一条纪律）：未知键拒绝而非忽略。
           ;; 静默忽略一个拼错的参数名，调用方会以为它生效了。
           "additionalProperties" false}
    (seq (filter :required? params))
    (assoc "required" (mapv :name (filter :required? params)))))

(defn tools-list
  "registry → MCP `tools/list` 的 JSON-able 形状。

   只从 `:order` + `:by-name` 取数——与 dispatch 同源（本 ns 的立身之本）。
   **不做网络传输**（README 裁定 ③）：这里到「能算出这个 map」为止，
   序列化与挂载归 Builder MCP 的 transport 模块（8.4）。"
  [{:keys [order by-name]}]
  (mapv (fn [n]
          (let [{:keys [name description params]} (by-name n)]
            {"name" name "description" description "inputSchema" (input-schema params)}))
        order))

(defn tool-names
  "dispatch 认识的名字集合。与 `tools-list` 的名字集合**恒等**——
   两者都是 `:by-name` 的键，这个恒等式没有可以漂移的余地。"
  [{:keys [by-name]}]
  (set (keys by-name)))

;; ---------------------------------------------------------------------------
;; 入参校验
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; JSON→EDN 入参转换（owner 裁定 2026-09-07，doc 106 §106.14 第 9 条）
;; ---------------------------------------------------------------------------

(defn coerce-args
  "对声明了 `:coerce` 的参数位置，把值交给那个函数改写一遍，然后再校验。

   **为什么钩子在这里而不在各 handler 里**：JSON 客户端与 EDN 模型之间的差
   （JSON 的 map 键只能是字符串）是**一处**事实，不是每个工具各自的事；写在
   handler 开头的一句转换就是 registry ns 开头那条「两处判断会漂移」说的第二处。
   `:coerce` 本身**不判定**：它按契约 schema 改写拼法，改不动就原样放过，
   由下游契约拒——工具面不做第二道校验门。"
  [params args]
  (if-not (map? args)
    args
    (reduce (fn [a {:keys [name coerce]}]
              (let [k (keyword name)]
                (if (and coerce (contains? a k)) (update a k coerce) a)))
            args params)))

(def ^:private type-examples
  "声明类型 → 一条**字面样例**，进拒绝的 `:expected`（doc 106 §106.14 第 9 条）。

   拒绝里给类型名不够：OQ-MCP-WRITE-ERGONOMICS 第 1 条量到的四连拒，卡的是
   **拼法**不是类型。样例住在 finding 里、不进 `tools/list` 的 JSON Schema——
   客户端照它构造不出调用，所以它不是第二个 schema。"
  {:string  "\"cis.party\""
   :integer "42"
   :boolean "true"
   :keyword ":cis.party/customer_no"
   :map     "{\"key\": …}"
   :vector  "[\"cis.party/name\"]"})

(defn- type-ok? [t v]
  (case t
    :string  (string? v)
    :integer (integer? v)
    :boolean (boolean? v)
    :keyword (keyword? v)
    :map     (map? v)
    :vector  (vector? v)
    :any     true))

(defn- check-args [tool-name params args]
  (if-not (map? args)
    [(f/make :toolkit.args/malformed :error [:tool tool-name]
             "arguments must be a map"
             :expected "map" :got (f/redact args))]
    (let [by-name (into {} (map (juxt :name identity)) params)
          keys-   (set (map (fn [k] (if (keyword? k) (clojure.core/name k) (str k))) (keys args)))
          unknown (set/difference keys- (set (keys by-name)))]
      (vec
       (concat
        (when (seq unknown)
          [(f/make :toolkit.args/malformed :error [:tool tool-name]
                   "argument key set is closed"
                   :expected (vec (sort (keys by-name)))
                   :got (vec (sort unknown)))])
        (for [{:keys [name required?]} params
              :when (and required? (not (contains? args (keyword name))))]
          (f/make :toolkit.args/missing :error [:tool tool-name name]
                  "required argument is absent"
                  :expected "present" :refs ["finding-v1 §2"]))
        (for [{:keys [name type enum]} params
              :let [v (get args (keyword name))]
              :when (contains? args (keyword name))
              fnd   (cond-> []
                      (not (type-ok? type v))
                      (conj (f/make :toolkit.args/invalid :error [:tool tool-name name]
                                    "argument does not match the declared type"
                                    ;; The literal example is what the caller
                                    ;; could not recover from the type name
                                    ;; alone (doc 106 §106.14 item 9).
                                    :expected (if-let [e (type-examples type)]
                                                (str type " — e.g. " e)
                                                (str type))
                                    :got (f/redact v)))
                      ;; `:got` 走 redact：入参里可能坐着 source 条目
                      ;; （`:jdbc_url`，B4 退出条件 2），一个字节都不许回显。
                      (and enum (type-ok? type v) (not (some #{v} enum)))
                      (conj (f/make :toolkit.args/invalid :error [:tool tool-name name]
                                    "argument is not one of the declared values"
                                    :expected (mapv str enum) :got (f/redact v))))]
          fnd))))))

(defn dispatch
  "registry × ctx × 工具名 × 入参 → handler 的返回值，或 `{:findings […]}`。

   查的是 `:by-name`——`tools-list` 列的也是它，于是「列出来了但调不了」
   与「没列出来却调得动」两种漂移都构造不出来。

   未知名字的拒绝**不区分「不存在」与「被能力过滤掉」**（裁定 ②）：
   两者若可区分，`tools/list` 的缺席就白做了——攻击者照样数得出门后有什么。"
  [{:keys [by-name]} ctx tool-name args]
  (if-let [{:keys [params handler]} (by-name tool-name)]
    ;; 转换在校验**之前**：`:coerce` 改的是拼法，校验判的是形状，顺序反过来
    ;; 就等于用 JSON 送不出的拼法去拒一个 JSON 客户端（第 9 条那条裁定的起因）。
    (let [args (coerce-args params args)
          fs   (check-args tool-name params args)]
      (if (seq fs)
        {:findings fs}
        (handler ctx args)))
    ;; `:path` 是 `[:tool]` 而**不带调用方给的名字**，`:got` 也不回显它。两条理由，
    ;; 各自独立成立：
    ;; 1. 名字是**未经校验的调用方输入**——`"jdbc:sqlite:…"` 当工具名传进来，
    ;;    回显它就是把配置值印进 finding（finding-v1 §5 / B4 退出条件 2）；
    ;; 2. 回显了名字，「被能力过滤掉」与「压根不存在」两种拒绝就**可区分**了，
    ;;    而裁定 ② 要的恰是两者逐字相同。调用方本来就知道自己传了什么。
    {:findings [(f/make :toolkit.tool/unknown :error [:tool]
                        "unknown tool"
                        ;; `:expected` 给的是**本次挂载**的名字集合——这不是泄漏：
                        ;; 它逐字等于 `tools/list` 已经给过的东西。
                        :expected (vec (sort (keys by-name))))]}))
