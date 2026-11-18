(ns open.atlas.common.connector.sql.impl.describe
  "Schema introspection over `java.sql.DatabaseMetaData`.

   One implementation serves every JDBC engine: the metadata API is the
   portable seam, so SQLite, PostgreSQL, MySQL and ClickHouse walk the same
   code and differ only in what they are *able* to answer (see `capabilities`).

   Two disciplines carried over from the rest of this connector:

   - **Identifiers and declared types only.** No `SELECT` is issued, no row is
     read, no count is taken. A schema is configuration-shaped and may be
     shown to a modeller; the contents behind it are business data and this is
     not the door for them.
   - **A gap is declared, never silent.** An engine without foreign keys says
     `:foreign-keys false` in `:capabilities`; a relation the driver refuses to
     describe becomes a finding, not a quietly shorter list."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql.impl.exec :as exec])
  (:import [java.sql Connection DatabaseMetaData ResultSet Types]))

;; ---------------------------------------------------------------------------
;; Declared type → coarse class
;; ---------------------------------------------------------------------------

(def ^:private type-classes
  "`java.sql.Types` constant → `spi/column-classes` member. Anything absent is
   `:unknown` — an honest answer that makes the modelling face ask, which beats
   a guess that silently mistypes a column."
  {Types/CHAR          :string
   Types/VARCHAR       :string
   Types/LONGVARCHAR   :string
   Types/NCHAR         :string
   Types/NVARCHAR      :string
   Types/LONGNVARCHAR  :string
   Types/CLOB          :string
   Types/NCLOB         :string
   Types/TINYINT       :integer
   Types/SMALLINT      :integer
   Types/INTEGER       :integer
   Types/BIGINT        :integer
   Types/DECIMAL       :decimal
   Types/NUMERIC       :decimal
   Types/REAL          :decimal
   Types/FLOAT         :decimal
   Types/DOUBLE        :decimal
   Types/BOOLEAN       :boolean
   Types/BIT           :boolean
   Types/DATE          :date
   Types/TIME          :datetime
   Types/TIME_WITH_TIMEZONE :datetime
   Types/TIMESTAMP     :datetime
   Types/TIMESTAMP_WITH_TIMEZONE :datetime
   Types/BINARY        :binary
   Types/VARBINARY     :binary
   Types/LONGVARBINARY :binary
   Types/BLOB          :binary})

(def ^:private type-name-classes
  "Fallback by the driver's own type name, for engines that report a vendor
   type code `java.sql.Types` has no constant for. Matched on the leading word
   so `DECIMAL(18,2)`, `Nullable(String)` and `VARCHAR2` all land."
  {"json" :json "jsonb" :json
   "uuid" :string "text" :string "string" :string "varchar" :string
   "bool" :boolean "boolean" :boolean
   "date" :date "date32" :date
   "datetime" :datetime "datetime64" :datetime "timestamp" :datetime})

(defn column-class
  "`{:sql-type name :jdbc-type int}` → a `spi/column-classes` member."
  [type-name jdbc-type]
  (or (get type-classes jdbc-type)
      (let [head (-> (or type-name "")
                     str/lower-case
                     (str/replace #"^nullable\(" "")
                     (str/replace #"^lowcardinality\(" "")
                     (str/split #"[(\[ ]")
                     first)]
        (get type-name-classes head :unknown))))

;; ---------------------------------------------------------------------------
;; What each engine can answer
;; ---------------------------------------------------------------------------

(def ^:private no-foreign-keys
  "Engines whose data model has no foreign-key concept at all. Their
   `getImportedKeys` returns an empty set, which is indistinguishable from
   \"this table happens to have no FKs\" — so the difference is declared here
   instead of being inferred from an empty result."
  #{"clickhouse"})

(defn capabilities
  "What this connection can be asked about. `product` is the driver's
   `getDatabaseProductName`, lower-cased."
  [product]
  (let [p (str/lower-case (or product ""))
        clickhouse? (some #(str/includes? p %) no-foreign-keys)]
    {:primary-keys true
     :views        true
     ;; ClickHouse sorting keys are reported through getPrimaryKeys, so
     ;; :primary-keys stays true — it answers. What it answers with is the
     ;; ORDER BY key: the shape of a key, none of a key's uniqueness. That
     ;; gap is declared here rather than left for a consumer to trip over
     ;; (sql-v1 §capabilities, owner 2026-08-30): deriving :identity? from a
     ;; declared key is only sound when this is true — uniqueness is an
     ;; empirical claim about the data, and the schema's shape cannot make
     ;; it. A key the caller nominated is a choice and stands.
     :primary-keys-unique? (not clickhouse?)
     :foreign-keys (not clickhouse?)}))

;; ---------------------------------------------------------------------------
;; System schemas
;; ---------------------------------------------------------------------------

(def ^:private system-schemas
  #{"pg_catalog" "pg_toast" "information_schema" "mysql" "performance_schema"
    "sys" "system" "sysaux" "innodb"})

(defn- system-relation?
  "Catalogue plumbing that no one models. Filtered unless the caller asked for
   a schema by name, in which case they get exactly what they asked for."
  [{:keys [schema name]}]
  (or (contains? system-schemas (some-> schema str/lower-case))
      (str/starts-with? (str/lower-case (or name "")) "sqlite_")))

;; ---------------------------------------------------------------------------
;; Walking the metadata
;; ---------------------------------------------------------------------------

(defn- rs-seq
  "Fully realise a metadata ResultSet through `row-fn`, then close it.
   Realised eagerly on purpose: these result sets are bound to the connection
   and a lazy seq would escape the `with-open` that owns it."
  [^ResultSet rs row-fn]
  (with-open [rs rs]
    (loop [acc (transient [])]
      (if (.next rs)
        (recur (conj! acc (row-fn rs)))
        (persistent! acc)))))

(defn- jdbc-kind [k] (case k :table "TABLE" :view "VIEW"))

(defn- with-remarks [entry ^ResultSet rs]
  ;; Remarks are optional evidence, never a prerequisite for introspection.
  (let [remarks (try (.getString rs "REMARKS") (catch java.sql.SQLException _ nil))]
    (cond-> entry (seq remarks) (assoc :remarks remarks))))

(defn- relations*
  [^DatabaseMetaData md {:keys [catalog schema kinds]}]
  (rs-seq (.getTables md catalog schema "%"
                      (into-array String (map jdbc-kind (sort kinds))))
          (fn [^ResultSet rs]
            (with-remarks {:catalog (.getString rs "TABLE_CAT")
             :schema  (.getString rs "TABLE_SCHEM")
             :name    (.getString rs "TABLE_NAME")
             :kind    (if (= "VIEW" (str/upper-case (or (.getString rs "TABLE_TYPE") "")))
                        :view :table)} rs))))

(defn- column-row [^ResultSet rs]
  (let [type-name (.getString rs "TYPE_NAME")
        jdbc-type (.getInt rs "DATA_TYPE")]
    (with-remarks {:catalog   (.getString rs "TABLE_CAT")
     :schema    (.getString rs "TABLE_SCHEM")
     :relation  (.getString rs "TABLE_NAME")
     :name      (.getString rs "COLUMN_NAME")
     :class     (column-class type-name jdbc-type)
     :sql-type  type-name
     ;; columnNoNulls = 0; anything else (nullable or unknown) is reported as
     ;; nullable — claiming NOT NULL on a driver that said "unknown" would be
     ;; a fact we do not have.
     :nullable? (not= 0 (.getInt rs "NULLABLE"))
     :nullable-known? (contains? #{0 1} (.getInt rs "NULLABLE"))
     :position  (.getInt rs "ORDINAL_POSITION")} rs)))

(defn- group-columns [rows]
  (->> rows
       (group-by (juxt :catalog :schema :relation))
       (reduce-kv (fn [m k cols]
                    (assoc m k (->> cols
                                    (sort-by :position)
                                    (mapv #(dissoc % :catalog :schema :relation)))))
                  {})))

(defn- columns-one-by-one
  [^DatabaseMetaData md relations]
  (group-columns
   (mapcat (fn [{:keys [catalog schema name]}]
             (rs-seq (.getColumns md catalog schema name "%") column-row))
           relations)))

(defn- columns-by-relation
  "Every column of the relations in scope, grouped by `[catalog schema name]`.

   Asked in **one** call where that works: the driver takes a table-name
   pattern, so a schema with several hundred tables costs one round trip
   instead of several hundred — against a remote database that is the
   difference between a moment and a minute.

   Two reasons it falls back to one call per relation:

   - **The caller named the relations.** Then the bulk call would fetch every
     column of every table to keep a handful, which is slower, not faster.
   - **The driver cannot do it at this size.** sqlite-jdbc implements the
     pattern form as a `UNION ALL` with one term per table and SQLite refuses
     a compound SELECT past 500 terms, so a database of ~300 tables fails
     outright. Falling back is a change in round trips, not in the answer:
     both paths return the same columns."
  [^DatabaseMetaData md {:keys [catalog schema names]} relations]
  (if (seq names)
    (columns-one-by-one md relations)
    (try
      (group-columns (rs-seq (.getColumns md catalog schema "%" "%") column-row))
      (catch Exception _
        (columns-one-by-one md relations)))))

(defn- primary-key*
  [^DatabaseMetaData md {:keys [catalog schema name]}]
  (->> (rs-seq (.getPrimaryKeys md catalog schema name)
               (fn [^ResultSet rs]
                 {:column (.getString rs "COLUMN_NAME")
                  :seq    (.getShort rs "KEY_SEQ")}))
       (sort-by :seq)
       (mapv :column)))

(defn- by-constraint
  "JDBC 的 key 行 → 逐个约束的分组，**按 `KEY_SEQ` 重置切分**。

   一条外键是多行，一列一行，`KEY_SEQ` 从 1 开始逐列递增；驱动按表、再按
   `KEY_SEQ` 顺序返回。所以 `KEY_SEQ` 退回 1 的那一行，就是下一条约束的开头
   ——这是 JDBC 契约给的、与命名无关的边界。

   原先按 `[约束名, 对端表]` 分组。它挡住了「无名约束指向**不同**父表」这一
   种，但挡不住指向**同一张**父表的两条：SQLite 的 `FK_NAME` 是空串，于是
   `sso` 的 `cust_no → customers.cust_no` 与 `to_acct_no → customers.cust_no`
   撞进同一组，被拼成一条数据库从未声明的复合外键
   `{:columns [\"cust_no\" \"to_acct_no\"]}`。

   后果不止于形状错：from-source 见到复合外键 + 集合导航就整张关系拒绝,
   于是那张表**从生成的图里整个消失**，而拒绝信息告诉建模者「这里有个复合
   外键」——一个不存在的约束。不要集合导航时它更安静：两条 link 都从同一个
   父列出发，其中一条落在错的列上，没有任何提示。

   命名的约束不受影响：同名的行本来就属于同一条，它们的 `KEY_SEQ` 连续。"
  [rows]
  (->> rows
       (reduce (fn [{:keys [idx out prev] :as acc} r]
                 (let [start? (or (nil? prev) (<= (long (:seq r)) 1))
                       idx    (if start? (inc idx) idx)]
                   (assoc acc :idx idx :prev r
                          :out (conj out (assoc r ::constraint idx)))))
               {:idx 0 :out [] :prev nil})
       :out
       (group-by ::constraint)))

(defn- foreign-keys*
  [^DatabaseMetaData md {:keys [catalog schema name]}]
  (->> (rs-seq (.getImportedKeys md catalog schema name)
               (fn [^ResultSet rs]
                 {:fk-name    (.getString rs "FK_NAME")
                  :seq        (.getShort rs "KEY_SEQ")
                  :column     (.getString rs "FKCOLUMN_NAME")
                  :pk-relation (.getString rs "PKTABLE_NAME")
                  :pk-schema  (.getString rs "PKTABLE_SCHEM")
                  :pk-column  (.getString rs "PKCOLUMN_NAME")}))
       ;; One FK is several rows, one per column pair — split them by KEY_SEQ
       ;; reset, not by name (see `by-constraint`).
       by-constraint
       (mapv (fn [[_ rows]]
               (let [rows (sort-by :seq rows)]
                 {:name       (:fk-name (first rows))
                  :columns    (mapv :column rows)
                  :references {:relation (:pk-relation (first rows))
                               :schema   (:pk-schema (first rows))
                               :columns  (mapv :pk-column rows)}})))
       ;; Deterministic order, independent of the driver's: a schema read twice
       ;; must describe the same source the same way.
       (sort-by (juxt #(get-in % [:references :relation]) :columns))
       vec))

(defn- referenced-by*
  "The other direction of the same fact: which relations point **at** this one.

   `getImportedKeys` answers \"whose keys do I hold\", and that is the only
   direction a foreign key is stored in — but it cannot answer \"who hangs off
   me\", because the answer lives in tables the caller may not have asked
   about. A face that only reads imported keys can never show that
   `film_actor` references `film` unless the modeller already happened to pick
   `film_actor`, which is the wrong way round: picking it is the decision the
   list is supposed to inform."
  [^DatabaseMetaData md {:keys [catalog schema name]}]
  (->> (rs-seq (.getExportedKeys md catalog schema name)
               (fn [^ResultSet rs]
                 {:fk-name     (.getString rs "FK_NAME")
                  :seq         (.getShort rs "KEY_SEQ")
                  :fk-relation (.getString rs "FKTABLE_NAME")
                  :fk-schema   (.getString rs "FKTABLE_SCHEM")
                  :fk-column   (.getString rs "FKCOLUMN_NAME")
                  :pk-column   (.getString rs "PKCOLUMN_NAME")}))
       ;; Same splitting rule as the imported side, for the same reason
       ;; (see `by-constraint`): two unnamed constraints of one table must not
       ;; merge (D2.3).
       by-constraint
       (mapv (fn [[_ rows]]
               (let [rows (sort-by :seq rows)]
                 {:name     (:fk-name (first rows))
                  :relation (:fk-relation (first rows))
                  :schema   (:fk-schema (first rows))
                  :columns  (mapv :fk-column rows)
                  :references {:columns (mapv :pk-column rows)}})))
       (sort-by (juxt :relation :columns))
       vec))

(defn- unreadable-finding [rel facet]
  {:code     :connector.schema/unreadable-relation
   :severity :warning
   :path     [:source :schema (str (:name rel))]
   :message  (str "driver did not describe " (name facet) " for this relation; "
                  "it is reported without them")
   :refs     ["sql-v1 §capabilities"]})

(defn- describe-relation
  "One relation, plus whatever findings its facets produced. A facet the driver
   refuses is a warning against that relation — the relation still ships, with
   the missing facet visibly empty, because dropping it entirely would hide a
   table the modeller asked to see.

   `keys?` false skips the per-relation key calls entirely. Unlike columns,
   `getPrimaryKeys` / `getImportedKeys` / `getExportedKeys` take a table
   *name* and not a pattern,
   so they cost one round trip each per relation and there is no portable bulk
   form. A face browsing a large schema does not need them yet; it asks again
   with `:names` once the modeller has chosen. `:primary-key`/`:foreign-keys`/`:referenced-by`
   come back empty in that mode, which is why the result says `:keys-read?
   false` — empty must not be mistakable for \"this table declares none\"."
  [^DatabaseMetaData md rel caps columns keys?]
  (let [facet (fn [enabled? facet-name f]
                (if-not (and keys? enabled?)
                  [nil nil]
                  (try [(f md rel) nil]
                       (catch Exception _ [nil (unreadable-finding rel facet-name)]))))
        [pk pk-finding]  (facet (:primary-keys caps) :primary-keys primary-key*)
        [fks fk-finding] (facet (:foreign-keys caps) :foreign-keys foreign-keys*)
        [in in-finding]  (facet (:foreign-keys caps) :foreign-keys referenced-by*)]
    {:relation (assoc rel
                      :columns       (or columns [])
                      :primary-key   (or pk [])
                      :foreign-keys  (or fks [])
                      :referenced-by (or in []))
     :findings (remove nil? [pk-finding fk-finding in-finding])}))

(def ^:private capability-gap-messages
  ;; :primary-keys-unique? is not an absence the generic template can word:
  ;; the keys ARE reported — what is missing is their uniqueness.
  {:primary-keys-unique?
   (str "this engine's declared keys are sort keys, not uniqueness "
        "constraints; a declared key must not become :identity? unless "
        "the caller nominates it")})

(defn- capability-findings [caps]
  (for [[facet enabled?] (sort-by key caps)
        :when (false? enabled?)]
    {:code     :connector.schema/unsupported-capability
     :severity :info
     :path     [:source :schema]
     :message  (or (get capability-gap-messages facet)
                   (str "this engine has no " (name facet)
                        "; proposals that would derive from them are "
                        "unavailable for this source"))
     :refs     ["sql-v1 §capabilities"]}))

(defn describe
  "`describe-schema` for source type \"sql\". See the SPI's `schema-description?`
   for the returned shape."
  [source-config connection opts]
  (let [ctx  {:resolver-id nil :source-type (spi/normalize-type (:type source-config))}
        ds   (exec/datasource! source-config connection ctx)
        kinds (or (not-empty (:kinds opts)) spi/relation-kinds)
        wanted (some-> (:names opts) set)]
    (try
      (with-open [^Connection con (jdbc/get-connection ds)]
        (let [md    (.getMetaData con)
              caps  (capabilities (.getDatabaseProductName md))
              ;; Keys are read by default, and skipped only when the caller
              ;; says so — a caller who forgets the option gets the complete
              ;; answer, not a quietly key-less one.
              keys? (not (false? (:keys? opts)))
              rels  (cond->> (relations* md (assoc opts :kinds kinds))
                      ;; An explicitly named schema is taken at face value; only
                      ;; an unrestricted walk hides catalogue plumbing.
                      (nil? (:schema opts)) (remove system-relation?)
                      wanted (filter #(contains? wanted (:name %))))
              cols  (columns-by-relation md opts rels)
              described (mapv #(describe-relation
                                md % caps
                                (get cols [(:catalog %) (:schema %) (:name %)])
                                keys?)
                              rels)]
          {:relations    (mapv :relation described)
           :capabilities caps
           :keys-read?   keys?
           :findings     (vec (concat (capability-findings caps)
                                      (mapcat :findings described)))}))
      (catch clojure.lang.ExceptionInfo e
        (if (spi/failure? e) (throw e) (spi/fail! :source ctx "schema introspection failed")))
      (catch Exception e
        (spi/fail! (exec/classify e) ctx "schema introspection failed")))))
