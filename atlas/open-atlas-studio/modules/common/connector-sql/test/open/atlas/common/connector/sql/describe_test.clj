(ns open.atlas.common.connector.sql.describe-test
  "Schema introspection against a real SQLite database.

   This suite carries its own schema rather than reusing fixtures/b1: it needs
   shapes b1 deliberately does not have (a view, a composite primary key, a
   composite foreign key, two foreign keys on one table, and two foreign keys
   on one table pointing at the SAME parent), and fixtures/b1 is a settled
   fact that the engine and connector both read — growing it to suit a
   new test would move the ground under those suites."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql :as sql]
            [open.atlas.common.connector.sql.impl.describe :as describe]))

(def ^:private jdbc-url
  "jdbc:sqlite:file:atlas_describe_fixture?mode=memory&cache=shared")

(def ^:private schema
  ["CREATE TABLE vendors (
      vend_no   INTEGER PRIMARY KEY,
      vend_name TEXT NOT NULL,
      note      TEXT)"
   "CREATE TABLE parts (
      sku_no   INTEGER PRIMARY KEY,
      part_no  TEXT NOT NULL,
      ave_cost NUMERIC,
      vend_no  INTEGER REFERENCES vendors(vend_no))"
   ;; Composite PK and a composite FK back to it, plus a second FK to another
   ;; table: the two hardest things for a metadata walker to keep straight.
   "CREATE TABLE order_lines (
      order_no INTEGER NOT NULL,
      line_no  INTEGER NOT NULL,
      sku_no   INTEGER REFERENCES parts(sku_no),
      qty      INTEGER NOT NULL,
      PRIMARY KEY (order_no, line_no))"
   "CREATE TABLE line_notes (
      order_no INTEGER NOT NULL,
      line_no  INTEGER NOT NULL,
      body     TEXT,
      FOREIGN KEY (order_no, line_no) REFERENCES order_lines(order_no, line_no))"
   ;; Two UNNAMED foreign keys to the SAME parent. SQLite reports FK_NAME as
   ;; an empty string, so a walker that separates constraints by name — even
   ;; one that also looks at the referenced table — folds these two into one
   ;; fabricated composite key. Real shape, found in a real schema
   ;; (`sso.cust_no` and `sso.to_acct_no` both reference `customers.cust_no`),
   ;; and it cost that table its whole place in a generated graph.
   "CREATE TABLE shipments (
      ship_no  INTEGER PRIMARY KEY,
      cust_no  INTEGER REFERENCES vendors(vend_no),
      bill_to  INTEGER REFERENCES vendors(vend_no),
      sku_no   INTEGER REFERENCES parts(sku_no))"
   "CREATE VIEW expensive_parts AS
      SELECT sku_no, part_no, ave_cost FROM parts WHERE ave_cost > 100"])

(def ^:private ^:dynamic *ds* nil)

(defn- with-db [f]
  (let [keep-alive (java.sql.DriverManager/getConnection jdbc-url)]
    (try
      (doseq [s schema] (jdbc/execute! keep-alive [s]))
      (binding [*ds* (jdbc/get-datasource jdbc-url)] (f))
      (finally (.close keep-alive)))))

(use-fixtures :once with-db)

(defn- described
  ([] (described {}))
  ([opts] (spi/describe-schema {:type "sql"} *ds* opts)))

(defn- relation [d n] (first (filter #(= n (:name %)) (:relations d))))

;; ---------------------------------------------------------------------------

(deftest result-conforms-to-the-spi-shape
  (is (spi/schema-description? (described))
      "a connector that drifts from the seam's shape breaks every modelling
       face at once, so the shape is asserted here rather than only implied"))

(deftest tables-and-views-both-listed-and-distinguished
  (let [d (described)]
    (is (= #{"vendors" "parts" "order_lines" "line_notes" "shipments" "expensive_parts"}
           (set (map :name (:relations d)))))
    (is (= :table (:kind (relation d "parts"))))
    (is (= :view (:kind (relation d "expensive_parts"))))))

(deftest sqlite-internal-tables-are-not-offered-for-modelling
  ;; sqlite_sequence and friends exist in any database with AUTOINCREMENT and
  ;; are catalogue plumbing, not something anyone models.
  (is (empty? (filter #(str/starts-with? (:name %) "sqlite_")
                      (:relations (described))))))

(deftest kinds-opt-narrows-what-is-walked
  (is (= #{:table} (set (map :kind (:relations (described {:kinds #{:table}}))))))
  (is (= ["expensive_parts"]
         (map :name (:relations (described {:kinds #{:view}}))))))

(deftest names-opt-narrows-to-the-relations-asked-for
  (is (= ["parts"] (map :name (:relations (described {:names ["parts"]}))))))

;; ---------------------------------------------------------------------------

(deftest columns-carry-name-class-and-nullability-in-declaration-order
  (let [cols (:columns (relation (described) "parts"))]
    (is (= ["sku_no" "part_no" "ave_cost" "vend_no"] (mapv :name cols)))
    (is (= [:integer :string :decimal :integer] (mapv :class cols)))
    (is (every? true? (map :nullable-known? cols)))
    (is (false? (:nullable? (second cols)))
        "a NOT NULL column is reported as required")
    (is (true? (:nullable? (first cols)))
        "SQLite reports its INTEGER PRIMARY KEY (a rowid alias) as nullable.
         Taking the driver at its word is deliberate: nullability is a hint to
         the modeller here, and claiming NOT NULL where the driver did not say
         so would be a fact we do not have. The primary key is carried by
         :primary-key, which is where a generator should read it from.")))

(deftest an-unrecognised-declared-type-is-unknown-not-guessed
  (is (= :unknown (describe/column-class "GEOGRAPHY" java.sql.Types/OTHER)))
  (is (= :json (describe/column-class "jsonb" java.sql.Types/OTHER))
      "vendor type names the JDBC constants do not cover still resolve by name")
  (is (= :datetime (describe/column-class "DateTime64(3)" java.sql.Types/OTHER))
      "a parameterised vendor type resolves on its leading word"))

;; ---------------------------------------------------------------------------

(deftest primary-key-is-reported-in-key-order
  (let [d (described)]
    (is (= ["vend_no"] (:primary-key (relation d "vendors"))))
    (is (= ["order_no" "line_no"] (:primary-key (relation d "order_lines")))
        "a composite key in declaration order — the order is what a by-key
         resolver's WHERE clause has to be built from")))

(deftest a-view-has-no-declared-key
  (is (= [] (:primary-key (relation (described) "expensive_parts")))
      "empty means the modeller must nominate a key, which is why views are
       reported rather than skipped"))

(deftest foreign-keys-carry-both-ends
  (let [[fk] (:foreign-keys (relation (described) "parts"))]
    (is (= ["vend_no"] (:columns fk)))
    (is (= {:relation "vendors" :columns ["vend_no"]}
           (select-keys (:references fk) [:relation :columns])))))

(deftest a-relation-is-told-who-points-at-it
  ;; The other direction of the same constraint. Not derivable from
  ;; :foreign-keys unless every relation was described, which a narrowed read
  ;; deliberately does not do — so a face asking only about `vendors` still
  ;; learns that `parts` hangs off it.
  (let [[in] (:referenced-by (relation (described {:names ["vendors"]}) "vendors"))]
    (is (= "parts" (:relation in)))
    (is (= ["vend_no"] (:columns in)) "the columns on the referencing relation")
    (is (= ["vend_no"] (get-in in [:references :columns])) "and the ones they point at")))

(deftest a-relation-nothing-references-says-so-with-an-empty-vector
  (is (= [] (:referenced-by (relation (described) "line_notes")))
      "empty, not absent — a missing key would be indistinguishable from unread"))

(deftest a-composite-inbound-key-keeps-its-columns-paired
  (let [[in] (:referenced-by (relation (described) "order_lines"))]
    (is (= "line_notes" (:relation in)))
    (is (= ["order_no" "line_no"] (:columns in)))
    (is (= ["order_no" "line_no"] (get-in in [:references :columns])))))

(deftest a-composite-foreign-key-stays-one-key-with-paired-columns
  (let [fks (:foreign-keys (relation (described) "line_notes"))]
    (is (= 1 (count fks)) "two column pairs are one constraint, not two")
    (is (= ["order_no" "line_no"] (:columns (first fks))))
    (is (= ["order_no" "line_no"] (get-in (first fks) [:references :columns]))
        "child and parent columns stay positionally paired")))

(deftest two-foreign-keys-on-one-table-do-not-collapse
  ;; SQLite reports no constraint name, so grouping on the name alone would
  ;; fuse these into a single bogus composite key.
  (let [fks (:foreign-keys (relation (described) "order_lines"))]
    (is (= 1 (count fks)))
    (is (= "parts" (get-in (first fks) [:references :relation]))))
  (let [d (described {:names ["line_notes"]})]
    (is (= ["order_lines"]
           (map #(get-in % [:references :relation])
                (:foreign-keys (relation d "line_notes")))))))

;; ---------------------------------------------------------------------------

(deftest engine-capabilities-are-declared-not-inferred
  (is (= {:primary-keys true :primary-keys-unique? true :views true
          :foreign-keys true}
         (:capabilities (described))))
  (testing "an engine with no foreign-key concept says so"
    (let [caps (describe/capabilities "ClickHouse")]
      (is (false? (:foreign-keys caps)))
      (is (true? (:primary-keys caps)))))
  (testing "sort keys answer getPrimaryKeys but carry no uniqueness (sql-v1, 2026-08-30)"
    (let [caps (describe/capabilities "ClickHouse")]
      (is (false? (:primary-keys-unique? caps))
          "ClickHouse's declared keys are ORDER BY sort keys")
      (is (true? (:primary-keys-unique? (describe/capabilities "SQLite")))
          "an enforced-PK engine does not have to declare what always held")))
  (testing "a declared gap produces a finding, so a face cannot miss it"
    (is (= [:connector.schema/unsupported-capability
            :connector.schema/unsupported-capability]
           (map :code (#'describe/capability-findings
                       (describe/capabilities "ClickHouse"))))
        "one finding per declared gap: foreign keys and key uniqueness")))

(deftest findings-are-well-formed-connector-findings
  (is (every? spi/finding? (:findings (described))))
  (is (every? spi/finding?
              (#'describe/capability-findings (describe/capabilities "ClickHouse")))))

;; ---------------------------------------------------------------------------

(deftest introspection-reads-no-business-data
  ;; The whole point of the seam: a modeller may see the shape of a database
  ;; they are not cleared to read rows from.
  (let [d (described)
        text (pr-str d)]
    (is (spi/schema-description? d))
    (doseq [value ["Acme" "Bearing" "12.50" "ap@acme"]]
      (is (not (str/includes? text value))
          (str "a row value reached the description: " value)))))

(deftest an-unregistered-source-type-refuses-instead-of-reporting-an-empty-database
  (let [e (try (spi/describe-schema {:type "no-such-connector"} nil {})
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (spi/failure? e))
    (is (= :connection (spi/error-category e))
        "an empty :relations would read as \"this database has no tables\";
         a generator fed that proposes nothing and reports success")))

(deftest sql-type-is-registered-as-describable
  (is (spi/supports-describe? sql/source-type))
  (is (not (spi/supports-describe? "no-such-connector"))))

;; ---------------------------------------------------------------------------
;; Scale: a schema with hundreds of relations is ordinary
;; ---------------------------------------------------------------------------

(deftest columns-come-back-for-every-relation-without-asking-per-relation
  ;; Asking per relation is the obvious shape and the wrong one: several
  ;; hundred tables then cost several hundred round trips. The driver takes a
  ;; table-name pattern, so one call answers for all of them — this asserts the
  ;; result is still complete under that single call.
  (let [d (described)]
    (is (every? #(seq (:columns %)) (:relations d)))
    (is (= 3 (count (:columns (relation d "expensive_parts"))))
        "a view's columns come from the same bulk call as a table's")))

(deftest keys-can-be-skipped-and-the-result-says-so
  (let [d (described {:keys? false})]
    (is (false? (:keys-read? d)))
    (is (every? #(= [] (:primary-key %)) (:relations d)))
    (is (every? #(= [] (:foreign-keys %)) (:relations d)))
    (is (every? #(= [] (:referenced-by %)) (:relations d)))
    (is (seq (:columns (relation d "parts")))
        "columns still come back — they are the part that is cheap in bulk")))

(deftest reading-keys-is-the-default
  ;; A caller who forgets the option gets the complete answer, not a quietly
  ;; key-less one.
  (is (true? (:keys-read? (described))))
  (is (= ["vend_no"] (:primary-key (relation (described) "vendors")))))

(deftest two-unnamed-keys-to-one-parent-stay-two-keys
  "**The bug this shape exists for.** SQLite reports every constraint with an
   empty FK_NAME, so grouping by name — even name plus referenced relation —
   merges two single-column keys into one composite key the database never
   declared. Splitting on the KEY_SEQ reset is what keeps them apart: JDBC
   returns key rows ordered by table then KEY_SEQ, so a reset to 1 IS the
   constraint boundary.

   What the merge cost, before this test existed: `from-source` saw a
   composite FK, refused the whole relation the moment a child collection was
   asked for, and the table vanished from the generated graph — while the
   refusal told the modeller their schema had a composite foreign key it does
   not have. Without the collection it was quieter and no better: two links
   from one parent column, one of them onto the wrong column, no finding."
  (let [fks (:foreign-keys (relation (described) "shipments"))
        to-vendors (filterv #(= "vendors" (:relation (:references %))) fks)]
    (is (= 2 (count to-vendors))
        (str "two constraints, not one composite: " (pr-str fks)))
    (is (= [["bill_to"] ["cust_no"]] (sort (mapv :columns to-vendors)))
        "each keeps its own single column")
    (is (every? #(= ["vend_no"] (:columns (:references %))) to-vendors)
        "and each points at the parent column once, not twice")
    (testing "the unrelated third key is untouched"
      (is (= [["sku_no"]] (mapv :columns (filterv #(= "parts" (:relation (:references %))) fks)))))))

(deftest the-other-direction-splits-the-same-way
  "`referenced-by` reads the same constraints from the parent's side and had
   the same grouping — so `vendors` would have been told ONE relation hangs off
   it by a two-column key, rather than two."
  (let [ins (filterv #(= "shipments" (:relation %))
                     (:referenced-by (relation (described {:names ["vendors"]}) "vendors")))]
    (is (= 2 (count ins)))
    (is (= [["bill_to"] ["cust_no"]] (sort (mapv :columns ins))))))
