(ns open.atlas.common.connector.sql
  "sql 连接器 —— docs/contracts/connectors/sql-v1.md 的实现。

   跨模块只许 require 本 ns；`.impl.*` 私有。require 本 ns 即完成注册：
   七个 SPI 分派点的 `\"sql\"` 方法在这里挂上去，没有别的装配步骤
   （宿主决定加载哪些连接器，就是决定 require 哪些 ns）。

   契约要点与它们的落点：
   - 具名占位走 prepared statement，值永不字符串插值 …… impl.query/render
   - 语句按哪些 `:input` 属性切分（E1 / D5.14）………… impl.query/bound-input-attributes
   - 占位符与 `:params` 恰好互相覆盖，多、少均拒绝 …… impl.query/resolve-bindings
                                                     + impl.validate
   - `${VAR}` 与字面凭证写入端拒绝 …………………………… impl.envsub + impl.validate
   - `IN (:placeholder)` 展开为 N 个绑定、对齐不丢位 …… impl.run/compile-batch
                                                     + spi/align-batch
   - 超时强制且有默认值 ………………………………………… impl.exec
   - 故障一律经 SPI 的构造器 ………………………………… impl.exec/classify + spi/fail!
   - `:result_type \"none\"` 不读结果集 …………………… impl.exec/execute-no-result!
   - schema 内省只给标识符与声明类型，不发 SELECT ……… impl.describe"
  (:require [clojure.string :as str]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql.impl.describe :as describe]
            [open.atlas.common.connector.sql.impl.paths :as paths]
            [open.atlas.common.connector.sql.impl.pool :as pool]
            [open.atlas.common.connector.sql.impl.query :as query]
            [open.atlas.common.connector.sql.impl.run :as run]
            [open.atlas.common.connector.sql.impl.validate :as validate]))

(def source-type "sql")

(defn binding-operation
  "Translate semantic Binding mappings to the existing SQL operation shape.
   No connection configuration is read or copied. Call validate-binding first.
   `:field_mapping` is the output-map alone: the former `:column-map` merge was
   removed by the owner ruling of 2026-09-18 (ruling #8, zero usage)."
  [binding]
  (cond-> {:type source-type
   :query (get-in binding [:binding/implementation :query])
   :params (merge
            (into {} (map (fn [[attribute parameter]] [parameter attribute]))
                  (apply dissoc (:binding/input-map binding)
                         (spi/batch-keys (get-in binding [:binding/implementation :batch :parent-key]))))
            (into {} (keep (fn [[parameter physical]]
                             (when (contains? (set (query/placeholders (get-in binding [:binding/implementation :query]))) physical)
                               [physical parameter])))
                  (:binding/parameter-map binding)))
   :field_mapping (:binding/output-map binding)}
    (get-in binding [:binding/implementation :batch])
    (assoc :batch (get-in binding [:binding/implementation :batch]))

    ;; The binding override of the four-level merge (doc 107 §107.3) rides into
    ;; the operation block; the connector reads it beside `:resilience`.
    (get-in binding [:binding/implementation :policy])
    (assoc :policy (get-in binding [:binding/implementation :policy]))))

(def ^:private implementation-keys
  ;; `:policy` -- doc 107 §107.3: a binding may override the slot's semantic
  ;; half for this one statement; its contents are checked by validate-source.
  #{:query :batch :policy})

(defn- finding
  "A binding finding (finding-v1): path relative to the binding, message states
   the expectation and never echoes a value."
  [code path message]
  {:code code :severity :error :path path :message message})

(defn- text? [v] (and (string? v) (not (str/blank? v))))

(defn- mapping-findings
  "Per-entry findings for an input-map / output-map: qualified-keyword keys,
   non-blank string values (sql-v1: placeholder names / result column names)."
  [root m]
  (if-not (map? m)
    [(finding :connector.binding/shape [root] "Expected a map of attribute to physical name")]
    (for [[a n] m :when (not (and (qualified-keyword? a) (text? n)))]
      (finding :connector.binding/shape [root a]
               "Expected a qualified attribute keyword mapped to a non-blank name"))))

(defn- duplicate-name-findings
  "sql-v1: two input attributes cannot share one placeholder — the statement
   would bind only one of them."
  [input]
  (when (map? input)
    (let [counts (frequencies (vals input))]
      (for [[a n] input :when (> (get counts n 0) 1)]
        (finding :connector.binding/duplicate-physical-name [:binding/input-map a]
                 "Each input attribute needs its own placeholder name")))))

(defn- batch-findings [effect implementation input]
  (when (contains? implementation :batch)
    (let [batch (:batch implementation)
          parents (when (map? batch) (spi/batch-keys (:parent-key batch)))]
      (concat
       (when (= :write effect)
         [(finding :connector.binding/batch-effect [:binding/implementation :batch]
                   "A write binding cannot declare :batch (batch is a read optimisation)")])
       (cond
         (not (map? batch))
         [(finding :connector.binding/shape [:binding/implementation :batch] "Expected :batch to be a map")]
         (empty? parents)
         [(finding :connector.binding/missing-key [:binding/implementation :batch :parent-key]
                   ":batch needs a :parent-key naming one or more input attributes")]
         :else
         (for [p parents :when (not (and (map? input) (contains? input p)))]
           (finding :connector.binding/batch-parent [:binding/implementation :batch :parent-key]
                    "Every :parent-key attribute must appear in the input map")))))))

(defn validate-binding
  "Validate SQL template/mapping structure without IO. Does not prove SQL is
   read-only: deployment authorization and database privileges remain required.
   No runtime registration is implied by this validation adapter.

   Returns `{:findings [...]}`; each clause reports its own code and path (owner
   ruling 2026-09-18) and all clause findings are collected. The underlying
   sql-v1 `validate-source` / `validate-mutation` findings are kept minus their
   `:got`. A crash here is a platform defect and propagates to the caller."
  [binding]
  (let [implementation (:binding/implementation binding)
        effect (:binding/effect binding)
        input (:binding/input-map binding)
        output (:binding/output-map binding)
        structural
        (concat
         (when-not (#{:read :write} effect)
           [(finding :connector.binding/effect [:binding/effect] "Effect must be :read or :write")])
         (if-not (map? implementation)
           [(finding :connector.binding/shape [:binding/implementation] "Expected the implementation to be a map")]
           (concat
            (for [k (keys implementation) :when (not (implementation-keys k))]
              (finding :connector.binding/unknown-key [:binding/implementation k]
                       "sql-v1 binding implementation accepts :query, :batch and :policy only"))
            (when-not (text? (:query implementation))
              [(finding :connector.binding/missing-key [:binding/implementation :query]
                        "A non-blank :query string is required")])
            (batch-findings effect implementation input)))
         (mapping-findings :binding/input-map input)
         (mapping-findings :binding/output-map output)
         (duplicate-name-findings input)
         ;; Semantic contract 2.0 §2.2: an optional root is fine -- its
         ;; placeholder binds SQL NULL when absent (impl.run/binding-value) --
         ;; but a nested root arrives as rows, and a prepared statement has no
         ;; placeholder shape for a table of values.
         (for [a (sort (keys (:binding/input-nested binding)))]
           (finding :connector.binding/nested-input-unsupported [:binding/input-map a]
                    "A sql binding cannot take a nested input: a placeholder binds one value, not rows"))
         ;; Semantic contract 2.0 §6.3: a flat row has one shape, so it cannot
         ;; carry a union value (a record of one of several entities).
         ;; Recursion needs nothing of sql -- the planner recurses by calling
         ;; the children resolver per row.
         (for [a (sort (:binding/output-union binding))]
           (finding :connector.binding/union-output-unsupported [:binding/output-map a]
                    "A sql binding cannot produce a union output: a flat row cannot carry a record of one of several entities")))]
    (if (seq structural)
      {:findings (vec structural)}
      (let [config (assoc (binding-operation binding)
                          ;; Validation requires a connection locator; this
                          ;; inert value is never returned, opened or stored.
                          :jdbc_url "jdbc:sqlite::memory:")
            validator (if (= :write effect) validate/validate-mutation validate/validate-source)]
        {:findings (mapv #(select-keys % [:code :severity :path :message])
                         (:findings (validator config)))}))))

(defmethod spi/compile-source source-type
  [source-config connection resolver-config]
  (run/compile-read source-config connection resolver-config))

(defmethod spi/compile-batch-source source-type
  [source-config connection resolver-config]
  (run/compile-batch source-config connection resolver-config))

;; sql-v1「能力」：offset ✓（受平台分页纪律约束）。impl.narrow 把它拼进语句,
;; 所以这里答 true 与那段实现是同一件事的两面。
(defmethod spi/supports-offset? source-type [_] true)

(defmethod spi/validate-source source-type
  [source-config]
  (validate/validate-source source-config))

(defmethod spi/health-check source-type
  [source-config connection]
  (run/health source-config connection))

(defmethod spi/compile-mutation source-type
  [source-config connection mutation-config]
  (run/compile-write source-config connection mutation-config))

(defmethod spi/validate-mutation source-type
  [source-config]
  (validate/validate-mutation source-config))

(defmethod spi/bound-attributes source-type
  [{:keys [params input eql-params batch] :as source-config}]
  ;; E1 / D5.14。委托 impl.query 的同一个 `resolve-bindings`——写入端的
  ;; `:connector.source/placeholder-mismatch` 判的也是它，不给同一条事实
  ;; 造第二个来源。
  (query/bound-input-attributes
   {:params params :input input :eql-params eql-params
    :batch-placeholder (:placeholder batch)}
   (:query source-config)))

(defmethod spi/describe-schema source-type
  [source-config connection opts]
  (describe/describe source-config connection opts))

(defmethod spi/contract-version source-type [_] "v1")

(defmethod spi/declared-files source-type
  [source-config]
  ;; sqlite only: every other engine this connector speaks to is reached over
  ;; the network, so there is no file for the installing machine to have
  ;; (doc 104 §8.17.7 A).
  ;; The `jdbc:sqlite:` URL is parsed in ONE place (impl.paths): this ns used
  ;; to carry a second copy of it, and two answers to "what counts as a sqlite
  ;; path" drift apart (D1.8).
  (vec (keep identity [(paths/declared-file (:jdbc_url source-config))])))

(defn close-all-pools!
  "关掉本连接器持有的全部连接池（进程退出、热重载、测试收尾）。"
  []
  (pool/close-all!))

(defmethod spi/semantic-adapter "sql" [_]
  ;; Checker protocol (owner ruling 2026-09-18): `:validate` answers the
  ;; connector's `{:findings [...]}` directly; the execution layer prefixes
  ;; each path with `[:bindings bid]`. Empty findings = valid.
  {:validate validate-binding :lower binding-operation})
