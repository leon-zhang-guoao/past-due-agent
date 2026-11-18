(ns open.atlas.common.connector.sql.impl.validate
  "写入端校验（sql-v1「连接 schema」「操作块 schema（封闭）」「support policy」
   「conformance 要点」）。

   产出 finding（finding-v1 §2 形状，code 取自 SPI 的 `connector.*` 注册表）。
   `:got` 一律过 `spi/redact`——finding-v1 §5：「`:source :query` 里的 SQL 属于
   配置且可能内嵌敏感字面量——`:path` 指到位置即可」。

   **入参约定**：`source-config` 是 source 条目 + 操作块的合并 map，另外允许携带
   该 resolver / mutation 的 `:input` 与 `:eql-params`。校验「占位符与 `:params`
   恰好互相覆盖」必须知道恒等缺省能从哪里补（D2.1：占位名 = 属性 local name 或
   参数名），而这两样只在 resolver 条目上——SPI 的 `validate-source` 是一元分派，
   所以它们随 config 一起来。"
  (:require [clojure.string :as str]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql.impl.envsub :as envsub]
            [open.atlas.common.connector.sql.impl.exec :as exec]
            [open.atlas.common.connector.sql.impl.narrow :as narrow]
            [open.atlas.common.connector.sql.impl.query :as query]))

(def operation-keys
  "sql-v1「操作块 schema（封闭）」。"
  #{:type :query :params :field_mapping :batch})

(def connection-keys
  "sql-v1「连接 schema（source 条目扩展键）」+ dialect-v1 §3 的 source 核心键。"
  #{:type :label :description :jdbc_url :username :password
    :max_pool_size :min_idle :resilience
    ;; doc 107 §107.3: the SEMANTIC half of the policy, when a Semantic 1.4 slot
    ;; or binding declares one. Same key face as api -- one rule, one answer.
    :policy})

(def ^:private context-keys
  "随 config 一起来的 resolver/mutation 上下文（见 ns 文档），不是连接器键面。"
  #{:id :input :output :result_type :eql-params :limit :order :source-ref
    :params-context})

(def batch-keys #{:parent-key :child-fk :placeholder})

(defn- f [code severity path message & {:as extra}]
  (merge {:code code :severity severity :path path :message message} extra))

(defn- unknown-key-findings [config]
  (let [allowed (into (into operation-keys connection-keys) context-keys)]
    (into []
          (keep (fn [k]
                  (cond
                    ;; sql-v1 support policy：「`:data_path` 非 sql 概念，`:reject`」
                    (= k :data_path)
                    (f :connector.source/unknown-key :error [:source :data_path]
                       ":data_path is not a sql concept; sql-v1 support policy rejects it."
                       :refs ["sql-v1 support policy"])
                    ;; 「未知操作键一律 `:reject`」
                    (not (allowed k))
                    (f :connector.source/unknown-key :error [:source k]
                       (str "Unknown key " k " in a sql operation block; sql-v1 rejects unknown keys.")
                       :got (spi/redact (get config k))
                       :refs ["sql-v1 support policy"]))))
          (keys config))))

(defn- credential-findings [{:keys [jdbc_url username password]}]
  (cond-> []
    (and username (not (envsub/only-placeholder? username)))
    (conj (f :connector.source/literal-credential :error [:source :username]
             ":username only accepts a ${VAR} placeholder; literal credentials are rejected at write time."
             :expected "${VAR}" :got (spi/redact username) :refs ["sql-v1 连接 schema" "D2.15"]))

    (and password (not (envsub/only-placeholder? password)))
    (conj (f :connector.source/literal-credential :error [:source :password]
             ":password only accepts a ${VAR} placeholder; literal credentials are rejected at write time."
             :expected "${VAR}" :got (spi/redact password) :refs ["sql-v1 连接 schema" "D2.15"]))

    (and jdbc_url (envsub/url-has-literal-credential? jdbc_url))
    (conj (f :connector.source/literal-credential :error [:source :jdbc_url]
             ":jdbc_url accepts a ${VAR} placeholder or a credential-free URL; it carries literal credentials."
             :expected "${VAR} or a credential-free URL"
             :got (spi/redact jdbc_url) :refs ["sql-v1 连接 schema"]))))

(defn- timeout-findings [config]
  (let [ms (get-in config [:resilience :timeout_ms])]
    (if (and (some? ms) (not (and (integer? ms) (pos? ms))))
      ;; principles：「超时强制且有默认值，enforce 不了超时的连接器不允许发布」——
      ;; 一个被写成 0/负数/非整数的超时就是「关掉超时」，等价于不可强制。
      [(f :connector.source/timeout-not-enforceable :error [:source :resilience :timeout_ms]
          "Query timeout must be a positive integer of milliseconds; it cannot be disabled."
          :expected "positive integer (ms)" :got (spi/redact ms)
          :refs ["principles 执行与连接" "D6.2"])]
      [])))

(defn- policy-findings
  "The closed `:resilience` / `:policy` key face (doc 107 §107.3, owner
   2026-09-18). Byte-for-byte the same rule the api connector applies, because
   it IS the same rule -- the key sets and the transient vocabulary come from
   the dialect and the SPI, not from a copy per connector (D1.8, CLAUDE.md)."
  [block path allowed]
  (when (some? block)
    (let [unknown (remove allowed (keys block))
          retry-on (:retry_on block)]
      (cond-> []
        (not (map? block))
        (conj (f :connector.source/invalid-shape :error path
                 "This block must be a map."
                 :expected "map" :got (spi/redact block) :refs ["doc 107 §107.3"]))

        (and (map? block) (seq unknown))
        (conj (f :connector.source/unknown-key :error path
                 (str "Unknown key(s): " (str/join ", " (sort (map str unknown)))
                      ". The key face is closed (doc 107 §107.3).")
                 :expected (str/join ", " (sort (map str allowed)))
                 :refs ["doc 107 §107.3"]))

        (and (some? retry-on)
             (or (not (set? retry-on)) (not (every? spi/transient-categories retry-on))))
        (conj (f :connector.source/invalid-shape :error (conj (vec path) :retry_on)
                 (str "A retry may only be declared on a transient failure "
                      "category (D6.4).")
                 :expected (str "subset of " (pr-str (sort spi/transient-categories)))
                 :got (spi/redact retry-on) :refs ["doc 107 §107.3" "D6.4"]))

        (and (some? (:max_retries block)) (not (nat-int? (:max_retries block))))
        (conj (f :connector.source/invalid-shape :error (conj (vec path) :max_retries)
                 "Retry budget must be a non-negative integer."
                 :expected "non-negative integer" :got (spi/redact (:max_retries block))
                 :refs ["doc 107 §107.3"]))))))

(def ^:private sql-policy-keys
  "The semantic-half keys sql HONOURS.

   Narrower than `dialect/policy-keys` by exactly one: `:status-map` is an
   api concept (doc 107 §107.5 maps HTTP statuses), and sql's counterpart --
   SQLSTATE -> category -- is explicitly NOT ruled yet (§107.9 item 11 defers
   it until there is corpus). Accepting the key here and doing nothing with it
   is the «declared and silently ignored» state §107.1 exists to end, so it is
   refused instead. When item 11 is ruled this narrowing goes away."
  (disj dialect/policy-keys :status-map))

(defn- resilience-findings [config]
  (into (vec (policy-findings (:resilience config) [:source :resilience]
                              dialect/resilience-keys))
        (policy-findings (:policy config) [:source :policy] sql-policy-keys)))

(defn- placeholder-findings [{:keys [query params input eql-params batch] :as _config}]
  (let [{:keys [unresolved unused]}
        (query/resolve-bindings {:params params :input input :eql-params eql-params
                                 :batch-placeholder (:placeholder batch)}
                                query)]
    (cond-> []
      ;; conformance：「占位符与 `:params` 恰好互相覆盖，多、少均拒绝」——「少」
      (seq unresolved)
      (conj (f :connector.source/placeholder-mismatch :error [:source :query]
               (str "Named placeholders with no binding: "
                    (str/join ", " (map #(str ":" %) (sort unresolved)))
                    ". Declare them in :params, or rely on the D2.1 identity default "
                    "(placeholder name = attribute local name or eql-param name).")
               :refs ["sql-v1 conformance" "D2.1"]))
      ;; ——「多」
      (seq unused)
      (conj (f :connector.source/placeholder-mismatch :error [:source :params]
               (str ":params keys with no matching placeholder in :query: "
                    (str/join ", " (sort unused)) ".")
               :refs ["sql-v1 conformance"])))))

(defn- eql-param-findings [{:keys [eql-params field_mapping output]}]
  (let [attrs (into (set (spi/output-attributes output))
                    (keep :attribute) (vals (or eql-params {})))
        fm    (spi/resolve-field-mapping field_mapping attrs)]
    (into []
          (keep (fn [[pname {:keys [attribute op]}]]
                  (cond
                    (and attribute (nil? (get fm attribute)))
                    (f :connector.source/invalid-shape :error [:eql-params pname :attribute]
                       (str "eql-param " pname " does not resolve to a column via :field_mapping.")
                       :refs ["sql-v1 eql-param 扩展键" "D2.20"])
                    (and op (not (contains? narrow/ops op)))
                    (f :connector.source/invalid-shape :error [:eql-params pname :op]
                       (str "Unsupported :op " op " for a sql source.")
                       :expected (vec (sort (keys narrow/ops)))
                       :refs ["sql-v1"]))))
          (or eql-params {}))))

(defn- batch-findings [{:keys [batch query params]}]
  (when batch
    (let [missing (remove #(contains? batch %) batch-keys)
          extra   (remove batch-keys (keys batch))
          ph      (:placeholder batch)]
      (cond-> []
        (seq missing)
        (conj (f :connector.source/missing-key :error [:source :batch]
                 (str ":batch requires " (str/join ", " (sort missing)) ".")
                 :refs ["sql-v1 batch 块"]))
        (seq extra)
        (conj (f :connector.source/unknown-key :error [:source :batch]
                 (str "Unknown :batch keys: " (str/join ", " (sort (map str extra))) ".")
                 :refs ["sql-v1 support policy"]))
        (and ph (not (contains? (set (query/placeholders query)) ph)))
        (conj (f :connector.source/placeholder-mismatch :error [:source :batch :placeholder]
                 (str ":batch :placeholder \"" ph "\" does not occur in :query.")
                 :refs ["sql-v1 batch 块"]))
        ;; A compound key pairs the i-th parent attribute with the i-th child
        ;; column (owner 2026-09-07, doc 106 §106.14 item 14). Unequal counts
        ;; would silently drop or duplicate a column in the row-value
        ;; comparison, which reads as «this parent has no children».
        (not= (count (spi/batch-keys (:parent-key batch)))
              (count (spi/batch-keys (:child-fk batch))))
        (conj (f :connector.source/invalid-shape :error [:source :batch]
                 (str ":batch :parent-key and :child-fk must name the same number of "
                      "keys; a compound key pairs them position by position.")
                 :expected (str (count (spi/batch-keys (:parent-key batch)))
                                " child columns")
                 :got (count (spi/batch-keys (:child-fk batch)))
                 :refs ["sql-v1 batch 块" "doc 106 §106.14 item 14"]))
        (and ph (contains? (query/declared-bindings params) ph))
        (conj (f :connector.source/placeholder-mismatch :error [:source :params]
                 (str ":batch :placeholder \"" ph "\" is bound from the parent keys and "
                      "must not also appear in :params.")
                 :refs ["sql-v1 batch 块"]))))))

(defn- required-findings [{:keys [jdbc_url query]}]
  (cond-> []
    (not jdbc_url) (conj (f :connector.source/missing-key :error [:source :jdbc_url]
                            ":jdbc_url is required for a sql source."
                            :refs ["sql-v1 连接 schema"]))
    (not query)    (conj (f :connector.source/missing-key :error [:source :query]
                            ":query is required for a sql operation block."
                            :refs ["sql-v1 操作块 schema"]))))

(defn validate-source [config]
  (let [findings (concat (required-findings config)
                         (unknown-key-findings config)
                         (credential-findings config)
                         (timeout-findings config)
                         (resilience-findings config)
                         (eql-param-findings config)
                         (batch-findings config)
                         (when (:query config) (placeholder-findings config)))]
    {:findings (vec (sort-by (juxt (comp pr-str :path) :code) findings))}))

(defn validate-mutation [config]
  (let [{:keys [findings]} (validate-source config)
        ;; dialect-v1 §3：「mutation 操作块**不得声明 `:batch`**——batch 是消 N+1
        ;; 的读优化，写路径声明它必是配置错误。」
        extra (when (:batch config)
                [(f :connector.source/unknown-key :error [:source :batch]
                    "A mutation operation block must not declare :batch."
                    :refs ["dialect-v1 §3 mutation 条目"])])]
    {:findings (vec (sort-by (juxt (comp pr-str :path) :code)
                             (concat (remove #(= [:source :batch] (:path %)) findings)
                                     extra)))}))

;; exec 的超时缺省在这里被引用一次，确保「有默认值」这条与校验同源。
(def default-timeout-ms exec/default-timeout-ms)
