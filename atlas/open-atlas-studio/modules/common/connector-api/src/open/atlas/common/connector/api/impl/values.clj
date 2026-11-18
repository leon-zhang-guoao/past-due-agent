(ns open.atlas.common.connector.api.impl.values
  "Declared-type conversion of mapped API values, for Semantic Contract 2.0
   resolvers and operations only. The api counterpart of connector-sql's
   impl.values (D2.20); the per-value rules for :decimal / :date / :datetime are
   the SPI's `declared-value`, shared with sql.

   The 2.0 result check does not coerce. It accepts a :json value only when
   every map inside it has string keys, and :decimal / :date / :datetime only as
   BigDecimal / LocalDate / Instant. This connector parses response bodies with
   keyword keys (the JSONPath mapping and the batch grouping read them that
   way) and JSON has no decimal or date type, so a raw payload, a price sent as
   a JSON number or a date sent as ISO text was refused although the value is
   exactly what the upstream sent. So is a date carried from a request input
   through `:context`, since inputs arrive as ISO text.

   What is converted: :json values get their original key text back; :decimal
   from a finite JSON number; :date from yyyy-MM-dd text; :datetime from ISO
   text with an explicit offset or Z. Text that would need a guessed zone or
   format is left for the result check to refuse. An explicit binding
   `:transforms` still runs first; a value it already converted passes through.

   It runs only when the compiler put `:semantic/output-types` on the
   definition, which only the 2.0 compiler does: a legacy dialect graph returns
   exactly what it returned before."
  (:require [open.atlas.common.connector.spi :as spi]))

(defn- key-text
  "The key as the upstream wrote it. Cheshire keywordizes \"a/b\" into the
   qualified keyword :a/b, so `name` would drop \"a/\"; the keyword's own text
   without its colon is the original string."
  [k]
  (if (keyword? k) (subs (str k) 1) k))

(defn- json-value
  [v]
  (cond
    (map? v) (persistent! (reduce-kv (fn [m k x] (assoc! m (key-text k) (json-value x))) (transient {}) v))
    (sequential? v) (mapv json-value v)
    :else v))

(defn- coerce-record
  "Convert the declared-type attributes of one mapped record, recursing into
   nested join values (records under a join root, or vectors of them)."
  [types record]
  (cond
    (map? record)
    (persistent!
     (reduce-kv (fn [m k v]
                  (let [t (get types k)]
                    (assoc! m k (cond
                                  (nil? v) v
                                  (= :json t) (json-value v)
                                  t (spi/declared-value t v)
                                  (or (map? v) (sequential? v)) (coerce-record types v)
                                  :else v))))
                (transient {}) record))
    (sequential? record) (mapv #(coerce-record types %) record)
    :else record))

(defn coercer
  "`(coercer types)` -> a function over a mapped result (nil, a record, or a
   vector of records), or `identity` when the definition declares nothing to
   convert. `types` is `{attribute-kw declared-type}` from the compiler."
  [types]
  (let [types (select-keys types (for [[k t] types :when (or (= :json t) (spi/declared-value-types t))] k))]
    (if (empty? types) identity #(coerce-record types %))))
