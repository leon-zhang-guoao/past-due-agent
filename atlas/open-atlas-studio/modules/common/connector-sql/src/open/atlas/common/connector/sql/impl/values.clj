(ns open.atlas.common.connector.sql.impl.values
  "Declared-type conversion of mapped SQL values, for Semantic Contract 2.0
   resolvers only.

   The 2.0 result check (`semantic-record/scalar?`) accepts exactly BigDecimal,
   LocalDate and Instant for :decimal, :date and :datetime, and does not coerce.
   JDBC answers Double for a float column, java.sql.Date for a date and text
   when the query casts to text, so a legacy query rebuilt as 2.0 is refused
   although the value is right. This namespace closes that gap at the connector,
   where the physical value is known.

   It runs only when the compiler put `:semantic/output-types` on the resolver,
   which only the 2.0 compiler does: a legacy dialect graph returns exactly what
   it returned before.

   A value that cannot be converted without inventing information is returned
   unchanged, so the result check refuses it instead of the connector guessing.
   A zoneless date-time is the case in point: it is not an instant until a
   zone is chosen, and the driver, not this code, knows the column's zone (see
   `exec/instant-reader`). The per-value rules are the SPI's
   `declared-value`, shared with the api connector."
  (:require [open.atlas.common.connector.spi :as spi]))

(defn coerce-record
  "Convert the declared-type attributes of one mapped record, recursing into
   nested join values. `types` is `{attribute-kw declared-type}`."
  [types record]
  (cond
    (map? record)
    (persistent!
     (reduce-kv (fn [m k v]
                  (assoc! m k (cond
                                (nil? v) v
                                (or (map? v) (sequential? v)) (coerce-record types v)
                                :else (spi/declared-value (get types k) v))))
                (transient {}) record))
    (sequential? record) (mapv #(coerce-record types %) record)
    :else record))

(defn coercer
  "`(coercer types)` → a function over a mapped result (nil, a record, or a
   vector of records / of vectors from a batch), or `identity` when the
   resolver declares nothing to convert."
  [types]
  (let [types (select-keys types (for [[k t] types :when (spi/declared-value-types t)] k))]
    (if (empty? types) identity #(coerce-record types %))))
