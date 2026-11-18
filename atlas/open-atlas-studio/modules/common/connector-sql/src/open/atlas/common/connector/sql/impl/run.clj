(ns open.atlas.common.connector.sql.impl.run
  "把操作块编译成可执行函数。六个 multimethod 的方法体都薄，逻辑在这里。"
  (:require [clojure.string :as str]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql.impl.exec :as exec]
            [open.atlas.common.connector.sql.impl.narrow :as narrow]
            [open.atlas.common.connector.sql.impl.pool :as pool]
            [open.atlas.common.connector.sql.impl.query :as query]
            [open.atlas.common.connector.sql.impl.values :as values]))

(defn ctx-of
  "编译期的 ctx（谁发的、发给什么类型的源）。E2 的关联 id 是**每次调用**的东西,
   由 `spi/ctx-with-request-trace` 在调用现场并入——编译产物是复用的，把 request-id
   烤进去就会让第二个请求带着第一个请求的 id。"
  [source-config config]
  {:resolver-id (:id config)
   :source-ref (:source-ref config)
   ::exec/diagnostic-redactor (exec/diagnostic-redactor source-config)
   :source-type (spi/normalize-type (:type source-config))})

(defn field-mapping-of
  "解析后的 `{属性kw → 列名}`（D2.1 恒等缺省补齐）。"
  [source-config config]
  (spi/resolve-field-mapping (:field_mapping source-config)
                             (spi/output-attributes (:output config))))

(defn narrowing-field-mapping-of
  "Resolve physical columns for filter and sorting inputs without adding those
   fields to the resolver's declared response output."
  [source-config config]
  (let [attrs (concat (spi/output-attributes (:output config))
                      (keep :attribute (vals (:eql-params config)))
                      (get-in config [:order :allowed]))]
    (spi/resolve-field-mapping (:field_mapping source-config)
                               attrs)))

(defn- value-plan
  "2.0 resolvers carry `:semantic/output-types` (set by the semantic compiler);
   legacy dialect resolvers do not, and keep their exact JDBC values. Only the
   types this connector converts count: a resolver that declares nothing but
   :json (converted by the api connector, not here) keeps the default reader."
  [config]
  (let [types (into {} (filter (comp spi/declared-value-types val)) (:semantic/output-types config))]
    {:read-opts {:instants? (boolean (seq types))}
     :coerce (values/coercer types)}))

(defn- binding-value
  "占位名 → 绑定值。`:input` 绑定取自 input-params，eql-param 绑定取自本次调用
   的参数（缺席时用声明的 `:default`）。**只产值，不产 SQL 片段。**"
  [bindings input-params call-params declared-eql]
  (fn [pname]
    (let [b (get bindings pname)]
      (case (:from b)
        :input     (get input-params (:attr b))
        :eql-param (let [k (:name b)]
                     (get call-params k (get-in declared-eql [k :default])))
        nil))))

(defn- narrowed
  "基础语句 + 收窄。batch 路径 `paginate?` 为 false——见 compile-batch 的说明。"
  [source-config config call-params fm paginate?]
  (narrow/apply-narrowing
   (:query source-config)
   {:declared-params (:eql-params config)
    :call-params     (or call-params {})
    :field-mapping   fm
    :order           (:order config)
    :limit           (:limit config)
    :paginate?       paginate?}))

(defn compile-read
  "逐行（非 batch）读。→ `(fn [input-params eql-params] → map|nil | [map …])`"
  [source-config connection config]
  (let [ctx      (ctx-of source-config config)
        fm       (field-mapping-of source-config config)
        narrow-fm (narrowing-field-mapping-of source-config config)
        list?    (= "list" (:result_type config))
        declared (:eql-params config)
        {:keys [read-opts coerce]} (value-plan config)
        {:keys [bindings]} (query/resolve-bindings
                            {:params (:params source-config)
                             :input  (:input config)
                             :eql-params declared}
                            (:query source-config))]
    (fn [input-params call-params]
      (let [ctx (spi/ctx-with-request-trace ctx)
            {:keys [sql values]} (narrowed source-config config call-params narrow-fm list?)
            value-of (binding-value bindings input-params call-params declared)
            ;; Expand explicit vector inputs only in IN (:name) positions. Values
            ;; remain prepared-statement parameters, including empty collections.
            list-names (map second (re-seq #"(?i)\bIN\s*\(\s*:(\w+)\s*\)" sql))
            in-list (into {} (keep (fn [n]
                                    (let [v (value-of n)]
                                      (when (sequential? v) [n v]))) list-names))
            rendered (query/render sql {:value-of value-of :in-list in-list})
            ;; 收窄的绑定值接在占位绑定之后——它们的 `?` 也接在语句尾部。
            sql-params (into rendered values)
            ds   (exec/datasource! source-config connection ctx)
            rows (exec/query! ds sql-params (exec/timeout-s source-config) ctx
                              (exec/guard-for source-config ctx config) read-opts)]
        (if list?
          ;; 空就是 `[]`（principles / sql-v1 conformance）。
          (coerce (mapv #(spi/apply-field-mapping fm % ctx) rows))
          ;; 无行返回 `nil`——「这个 resolver 供不出这些属性」，不是错误，
          ;; 更不是 `{}`。
          (when-let [row (first rows)]
            (coerce (spi/apply-field-mapping fm row ctx))))))))

(defn compile-batch
  "batch 读：一条 `IN (:placeholder)` 取回全部父项的行，再用 SPI 的对齐工具
   还原成与 `inputs` 等长同序的向量。

   `:limit`/`:offset` **不进这条批量语句**：它们是 per-parent 的窗口，一个全局
   LIMIT 会把所有父项的行合在一起截断（丢的还是别人的行）。因此收窄只带
   WHERE 与 ORDER BY——这两样对每个父项一致——分组之后再按父切窗，切窗由
   `spi/align-batch` 的 `:window` 完成（sql-v1「分页在 batch 上是 per-parent
   窗口，不下推」）。

   顺序不需要 `PARTITION BY`：ORDER BY 是全局附加的，分组保持桶内原始顺序，
   于是每个桶天然就是声明的顺序，切窗只是切片。"
  [source-config connection config]
  (let [ctx      (ctx-of source-config config)
        fm       (field-mapping-of source-config config)
        narrow-fm (narrowing-field-mapping-of source-config config)
        batch    (:batch source-config)
        ph       (:placeholder batch)
        declared (:eql-params config)
        {:keys [read-opts coerce]} (value-plan config)
        {:keys [bindings]} (query/resolve-bindings
                            {:params (:params source-config)
                             :input  (:input config)
                             :eql-params declared
                             :batch-placeholder ph}
                            (:query source-config))]
    (fn [inputs call-params]
      (let [ctx    (spi/ctx-with-request-trace ctx)
            inputs (vec inputs)
            ;; A compound `:parent-key` binds a ROW VALUE per parent — the
            ;; statement is `(k1, k2) IN ((?,?), …)` (owner 2026-09-07, doc 106
            ;; §106.14 item 14). One key stays one scalar per parent, so the
            ;; single-key statement is byte-identical to before.
            pks    (spi/batch-keys (:parent-key batch))
            ids    (if (= 1 (count pks))
                     (mapv #(get % (first pks)) inputs)
                     (mapv (fn [in] (mapv #(get in %) pks)) inputs))
            {:keys [sql values]} (narrowed source-config config call-params narrow-fm false)
            rendered (query/render sql
                                   {:value-of (binding-value bindings (first inputs)
                                                             call-params declared)
                                    :in-list  {ph ids}})
            sql-params (into rendered values)
            ds     (exec/datasource! source-config connection ctx)
            rows   (exec/query! ds sql-params (exec/timeout-s source-config) ctx
                                (exec/guard-for source-config ctx config) read-opts)]
        (coerce (spi/align-batch rows inputs batch
                                 {:result-type   (:result_type config)
                                  :field-mapping fm
                                  :window        (narrow/window (:limit config) call-params)
                                  :ctx           ctx}))))))

(defn compile-write
  "mutation。`:result_type \"none\"` 时不读结果集（sql-v1「能力」）。"
  [source-config connection config]
  (let [ctx      (ctx-of source-config config)
        fm       (spi/resolve-field-mapping (:field_mapping source-config)
                                            (spi/output-attributes (:returns config)))
        rt       (:result_type config)
        {:keys [read-opts coerce]} (value-plan config)
        {:keys [bindings]} (query/resolve-bindings
                            {:params (:params source-config)
                             ;; mutation 的绑定来自 `:params`（dialect-v1 §3
                             ;; mutation 条目：`{属性kw → {:required?}}`），
                             ;; 恒等缺省同样按属性 local name 补。
                             :input  (keys (:params config))}
                            (:query source-config))]
    (fn [mutation-params]
      (let [ctx (spi/ctx-with-request-trace ctx)
            sql-params (query/render (:query source-config)
                                     {:value-of (binding-value bindings mutation-params nil nil)})
            ds      (exec/datasource! source-config connection ctx)
            timeout (exec/timeout-s source-config)
            guard   (exec/guard-for source-config ctx config)]
        (if (= "none" rt)
          (exec/execute-no-result! ds sql-params timeout ctx guard)
          ;; A mutation that returns rows is still a mutation: D6.4 does not
          ;; let us retry it just because it happens to read its own result.
          (let [rows (exec/query! ds sql-params timeout ctx
                                  (assoc guard :idempotent? false) read-opts)]
            (coerce
             (if (= "list" rt)
               (mapv #(spi/apply-field-mapping fm % ctx) rows)
               (when-let [row (first rows)]
                 (spi/apply-field-mapping fm row ctx))))))))))

(defn health
  "按需体检。结果不带驱动原文/URL——体检与故障同一条不泄漏纪律。

   **未解析的 `${VAR}` 单独报**（`:missing-vars`）：它与「库没应答」都归
   `:connection`，但要做的事完全相反——一个是去 export 一个变量，一个是去看
   数据库。只给同一句「连不上」，调用方会去查那台好端端的机器。

   变量**名**可以出现，值当然不能——`impl.exec/datasource!` 的注释定的就是
   这条线：名字是方言里的配置，不是凭证。"
  [source-config connection]
  (let [ctx {:resolver-id nil :source-type (spi/normalize-type (:type source-config))}
        missing (when-not connection
                  (not-empty (:missing (pool/connection-config source-config))))]
    (if missing
      {:status :down :latency-ms -1 :error/category :connection
       :missing-vars (vec missing) :source-type (:source-type ctx)}
      (try
        (let [ds    (exec/datasource! source-config connection ctx)
              start (System/nanoTime)
              ;; E2：这台源库的 driver 认不认 application name。体检是这条
              ;; `:info` 唯一有人看的地方——它不是拒绝，只是「连接级看不见
              ;; 我们，语句注释仍然看得见」。
              cc    (pool/connection-config source-config)
              untagged (:findings (pool/app-name-plan (:jdbc-url cc)))
              ;; doc 104 §8.17.7 B: when a relative sqlite path was anchored,
              ;; say which file was opened — the rewrite must not be invisible.
              anchored (:anchored-path cc)]
          ;; A health check never retries: principles says availability is
          ;; verified ON DEMAND, and a check that retries reports a source as up
          ;; that a real query would have found flapping.
          (exec/query! ds ["SELECT 1"] (exec/timeout-s source-config) ctx
                       (update (exec/guard-for source-config ctx)
                               :policy assoc :max_retries 0))
          (cond-> {:status :ok :latency-ms (quot (- (System/nanoTime) start) 1000000)}
            (seq untagged) (assoc :findings untagged)
            anchored       (assoc :anchored-path anchored)))
        (catch clojure.lang.ExceptionInfo e
          {:status :down :latency-ms -1
           :error/category (or (spi/error-category e) :connection)
           :source-type (:source-type ctx)})))))
