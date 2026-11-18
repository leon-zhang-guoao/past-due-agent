(ns open.atlas.server.mcp.impl.duck
  "Tier ③ of the aggregation ladder: ONE DuckDB SQL over the rows a query just
   returned.

   Migrated from `ent.dx.mcp.analyze`. **Computation goes to the data; the data
   never goes to the model.** The medium is SQL because it is the language
   models write most reliably, and DuckDB because median / percentile / PIVOT /
   time buckets are first-class.

   This is also the executor for
   [metric-expression-duckdb-spill-v1](docs/contracts/metric-expression-duckdb-spill-v1.md):
   the contract says an executor materialises the rows as a single table named
   `data` and embeds the declared `:text` in
   `SELECT <text> FROM data [GROUP BY …]`. That is literally what happens here,
   so the metric registry finally has something that runs it.

   ## Containment (migrated rule for rule)

   - `SET enable_external_access=false` — SQL cannot read files, ATTACH
     databases, or touch the network; the ONLY data is the one loaded table;
   - an in-memory database **per call**, dropped with the connection;
   - wall-clock timeout via `Statement.cancel` on a watchdog;
   - result truncated to `max-rows` rows / `max-cell` chars per cell;
   - memory capped (`SET memory_limit`) so one query cannot OOM the host.

   ## Retained results

   MCP's results store retains complete responses in private temporary files.
   analyze_result reauthorizes the opaque handle, reads that file in application
   code, and passes its bounded rows here. SQL external access stays disabled;
   no filename or persistent business database is exposed. Query mode remains
   available when a fresh source read is desired."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.nio.file Files Path]
           [java.sql Connection DriverManager ResultSet SQLException Statement]
           [java.util Properties]
           [java.util.concurrent Executors Semaphore TimeUnit]))

(set! *warn-on-reflection* true)

(def max-rows   "Rows returned to the model, hard cap." 200)
(def max-cell   "Chars per returned cell, hard cap."    500)
(defn- positive-env [key fallback]
  (let [value (if-let [s (System/getenv key)] (parse-long s) fallback)]
    (when-not (and (integer? value) (pos? value))
      (throw (ex-info "Analysis configuration must be a positive integer" {:setting key})))
    value))

(def input-max-rows (positive-env "ATLAS_ANALYSIS_MAX_ROWS" 20000))

(def input-default-rows
  "Doc 108 §108.5 item 5, ruled in §108.6: **the default IS the maximum.** A default below the
   maximum only taught agents to re-send the same call with `input_row_limit`
   set, which costs a second full fetch to answer the same question.
   `ATLAS_ANALYSIS_DEFAULT_ROWS` survives as a TIGHTEN-DOWN knob only: a value
   above the maximum is refused at startup rather than silently clamped, because
   a clamped setting reads as honoured in the operator's config file."
  (let [v (positive-env "ATLAS_ANALYSIS_DEFAULT_ROWS" input-max-rows)]
    (when (> v input-max-rows)
      (throw (ex-info "Default analysis rows cannot exceed the server maximum"
                      {:setting "ATLAS_ANALYSIS_DEFAULT_ROWS" :max input-max-rows})))
    v))

(def input-max-bytes (positive-env "ATLAS_ANALYSIS_MAX_BYTES" (* 16 1024 1024)))
(def timeout-ms "SQL execution budget, excluding source fetch and table loading."
  (positive-env "ATLAS_ANALYSIS_TIMEOUT_MS" 5000))

;; ---------------------------------------------------------------------------
;; The analysis budget (doc 108 §108.5 items 5 and 6, ruled in §108.6:
;; item 6 first, then item 5)
;;
;; Three separate things are bounded, and they are separate on purpose:
;;   - TIME: one absolute deadline per call, spent down by every stage;
;;   - CONCURRENCY: how many analyses may hold DuckDB at once;
;;   - BYTES: input (per call and in total across calls) and output.
;; A stage that runs out is REFUSED naming the stage — never a partial
;; aggregate, which the caller cannot tell from a complete one.
;; ---------------------------------------------------------------------------

(def deadline-ms
  "End-to-end budget for ONE analysis call: authorization and pinning, handle
   read or fresh EQL fetch, flattening, table load, SQL, and encoding. Each
   stage receives only what the previous ones left."
  (positive-env "ATLAS_ANALYSIS_DEADLINE_MS" 30000))

(def max-concurrent (positive-env "ATLAS_ANALYSIS_MAX_CONCURRENT" 4))
(def max-wait-ms (positive-env "ATLAS_ANALYSIS_MAX_WAIT_MS" 2000))
(def total-input-bytes (positive-env "ATLAS_ANALYSIS_TOTAL_INPUT_BYTES" (* 64 1024 1024)))
(def max-output-bytes (positive-env "ATLAS_ANALYSIS_MAX_OUTPUT_BYTES" (* 1024 1024)))

(def max-join-rows
  "Guard on the intermediate size a multi-handle JOIN can reach (§108.2
   「限制 JOIN 中间结果，不能仅限制最终输出」). See `cross-join-risk?` — it is a
   pre-check, honestly a GUARD and not a guarantee."
  (positive-env "ATLAS_ANALYSIS_MAX_JOIN_ROWS" 200000))

(defn- now-ms [] (System/currentTimeMillis))

(defn start-deadline
  "An absolute instant, not a duration: a budget that restarts per stage is not
   a budget at all."
  ([] (start-deadline deadline-ms))
  ([ms] {:limit ms :started (now-ms) :at (+ (now-ms) ms)}))

(defn remaining-ms [deadline] (max 0 (- (:at deadline) (now-ms))))

(defn refusal
  "Structured refusal (finding-v1 spirit: name the position, give the limit,
   leak no value). `:error` stays human-readable for the agent; `:refusal`
   carries the machine-readable code."
  [code message data]
  {:error message :refusal (assoc data :code code)})

(defn deadline-refusal [deadline stage]
  (refusal :analysis.limit/deadline
           (str "Analysis exceeded its end-to-end deadline of " (:limit deadline)
                " ms during the " (name stage)
                " stage; no partial aggregate was computed.")
           {:stage stage :max (:limit deadline) :actual (- (now-ms) (:started deadline))}))

(defn check-deadline!
  "Throws the stage-named refusal when nothing is left. Thrown rather than
   returned so a stage buried inside DuckDB loading can abort without every
   intermediate function growing a refusal branch."
  [deadline stage]
  (when (and deadline (zero? (remaining-ms deadline)))
    (throw (ex-info "analysis deadline exhausted" {:analysis/refusal (deadline-refusal deadline stage)})))
  deadline)

(defonce ^:private ^Semaphore permits (Semaphore. max-concurrent true))

(defn available-permits
  "Test and operator visibility: after any refusal or timeout this must be back
   at `max-concurrent`, or the process leaks capacity one bad call at a time."
  []
  (.availablePermits permits))

(defn acquire-permit!
  "Bounded wait — an unbounded queue turns a concurrency limit into a latency
   limit nobody declared."
  []
  (.tryAcquire permits (long max-wait-ms) TimeUnit/MILLISECONDS))

(defn release-permit! [] (.release permits))

(def ^:private in-flight-bytes (atom 0))

(defn in-flight-input-bytes [] @in-flight-bytes)

(defn reserve-input-bytes!
  "Reserve n bytes of the PROCESS-WIDE input budget, or false. `memory_limit`
   is per connection and therefore per call; it is not a process guarantee, so
   the sum across concurrent analyses is bounded here as well."
  [n]
  (loop []
    (let [current @in-flight-bytes
          next* (+ current n)]
      (cond
        (> next* total-input-bytes) false
        (compare-and-set! in-flight-bytes current next*) true
        :else (recur)))))

(defn release-input-bytes! [n]
  (swap! in-flight-bytes #(max 0 (- % n))))

(defn input-budget [requested]
  (let [budget (if (nil? requested) input-default-rows requested)]
    (when (and (integer? budget) (pos? budget) (<= budget input-max-rows)) budget)))

(defn input-bytes
  "UTF-8 EDN size of the flattened input, counted row by row and abandoned at
   the first row that passes `stop-at` — measuring is not a reason to serialize
   the whole collection into one allocation. §108.4: this is measured
   SEPARATELY from handle file bytes and from response bytes, because the three
   fail for different reasons and a single number cannot say which one it was."
  ([flat-rows] (input-bytes flat-rows input-max-bytes))
  ([flat-rows stop-at]
   (reduce (fn [total row]
             (let [n (+ total 1 (alength (.getBytes ^String (pr-str row) "UTF-8")))]
               (if (> n stop-at) (reduced n) n)))
           2 flat-rows)))

(defn input-too-large? [flat-rows]
  (> (input-bytes flat-rows) input-max-bytes))

(defn output-bytes
  "UTF-8 size of the response as the transport will carry it."
  [response]
  (let [^String text (binding [*print-length* nil *print-level* nil] (pr-str response))]
    (alength (.getBytes text "UTF-8"))))

(def forbidden
  "Belt over DuckDB's own suspenders (`enable_external_access=false` already
   blocks these, and THAT is the real guard); rejecting up front gives the model
   a clearer error than a driver-level failure.

   Statement-leading only. The earlier `\\b(load|copy|…)\\b` matched any word
   anywhere, so `SELECT load, copy FROM data` — perfectly ordinary column names
   in a data model that has them — was refused with a message about ATTACH. A
   pattern that refuses valid questions teaches the agent to rewrite its SQL
   around a phantom rule."
  #"(?is)^\s*(attach|install|load|copy|export|import_database)\b")

;; ---------------------------------------------------------------------------
;; Column naming — flat row keys are namespaced keywords; SQL wants plain names
;; ---------------------------------------------------------------------------

(defn- sanitize [s]
  (-> (str s) (str/replace #"[^A-Za-z0-9_]" "_") (str/replace #"^(\d)" "c$1")))

(defn column-names
  "Distinct keys across flat rows → a vector of [key sql-name] pairs. The SQL
   name is the keyword's NAME part (what the model naturally writes, and what
   metric-expression-duckdb-spill-v1 §2 requires: the column name IS the
   attribute's local name); on a collision the namespace's last segment is
   prefixed; still colliding → the full namespace, underscored. Deterministic;
   first-seen order."
  [flat-rows]
  (let [ks (vec (distinct (mapcat keys flat-rows)))
        base (fn [k] (sanitize (name k)))
        pref (fn [k] (sanitize (str (last (str/split (or (namespace k) "") #"\.")) "_" (name k))))
        full (fn [k] (sanitize (str (namespace k) "_" (name k))))]
    (loop [ks ks taken #{} out []]
      (if-let [k (first ks)]
        (let [n (some #(when-not (taken %) %) [(base k) (pref k) (full k)])]
          (recur (rest ks) (conj taken n) (conj out [k n])))
        out))))

;; ---------------------------------------------------------------------------
;; Column typing (doc 108 §108.3, §108.5 item 3)
;;
;; ONE function decides a column's type, and both discovery (`describe-rows`)
;; and execution (`load-table!`) read it. Two derivations would let an agent
;; plan `sum(amount)` against a DECIMAL it was shown and have it loaded as
;; VARCHAR — §108.3's «发现接口与 SQL 使用同一 schema».
;; ---------------------------------------------------------------------------

(def ^:private max-decimal-digits
  "DuckDB's DECIMAL ceiling. Beyond it we refuse: rounding a 口径's amount to
   fit is the silent degradation D2.9 forbids."
  38)

(defn type-refusal!
  "Refusals name the COLUMN and the limit, never a value (S5.3)."
  [code message data]
  (throw (ex-info "analysis type refusal"
                  {:analysis/refusal (refusal code message data)})))

(defn- decimal? [v] (instance? java.math.BigDecimal v))

;; ---------------------------------------------------------------------------
;; Time (doc 108 §108.3 second paragraph, §108.5 item 4)
;;
;; Behind an EXPLICIT switch, default OFF, and with a FIXED rule set checked
;; against EVERY non-nil value — never the first row. A column that is a date
;; in its first page and a free-text note in its second is not a date, and the
;; discovery an agent planned against would have promised otherwise.
;;
;; **No value is ever assumed to be UTC or the host's zone.** A wall-clock time
;; with no offset does not denote an instant; picking a zone for it invents one,
;; and every downstream `date_trunc('day', …)` then answers for a day that may
;; not be the day the business meant.
;; ---------------------------------------------------------------------------

(def ^:private iso-offset-re
  #"^\d{4}-\d{2}-\d{2}[Tt ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?([Zz]|[+-]\d{2}:?\d{2})$")
(def ^:private iso-date-re #"^\d{4}-\d{2}-\d{2}$")
(def ^:private iso-zoneless-re
  #"^\d{4}-\d{2}-\d{2}[Tt ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?$")
(def ^:private iso-time-re #"^\d{2}:\d{2}(:\d{2}(\.\d+)?)?$")
(def ^:private ambiguous-date-re #"^\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}$")
(def ^:private digits-re #"^\d+$")

(defn- all-match? [re vs]
  (and (seq vs) (every? #(and (string? %) (re-matches re %)) vs)))

(defn- offset-date-time [^String v]
  (java.time.OffsetDateTime/parse (-> v (str/replace #"^(\S+) " "$1T") (str/replace #"z$" "Z"))))

(defn temporal-declaration
  "Which temporal kind a declared type names, or nil."
  [declared]
  (case declared
    (:instant :timestamp :datetime) :timestamptz
    :date :date
    :time :time
    nil))

(defn- declared-time-spec
  "A temporal declaration WITH `infer_time` on. The declaration says the column
   is a moment; the values must actually be able to carry one, or it is refused
   rather than loaded as something that only looks like a time."
  [column kind vs]
  (case kind
    :timestamptz
    (if (all-match? iso-offset-re vs)
      {:sql "TIMESTAMP WITH TIME ZONE" :base :timestamptz :type-source :declared
       :time-source :declared :time-status :ok}
      (type-refusal! :analysis.type/time-zone-missing
                     (str "Column \"" column "\" is declared as a moment in time, but not every "
                          "value carries a UTC offset or Z; nothing was loaded. A wall-clock "
                          "reading without a zone does not name an instant, and this analysis "
                          "will not pick a zone for it.")
                     {:column column}))

    :date
    (if (all-match? iso-date-re vs)
      {:sql "DATE" :base :date :type-source :declared :time-source :declared :time-status :ok}
      (type-refusal! :analysis.type/time-format
                     (str "Column \"" column "\" is declared as a date, but not every value is "
                          "an ISO YYYY-MM-DD date; nothing was loaded.")
                     {:column column :expected "YYYY-MM-DD"}))

    :time
    (if (all-match? iso-time-re vs)
      {:sql "TIME" :base :time :type-source :declared :time-source :declared :time-status :ok}
      (type-refusal! :analysis.type/time-format
                     (str "Column \"" column "\" is declared as a time of day, but not every "
                          "value is an ISO HH:MM[:SS] time; nothing was loaded.")
                     {:column column :expected "HH:MM[:SS]"}))))

(defn- inferred-time-spec
  "An UNDECLARED text column, with `infer_time` on. Infers only what one fixed
   rule can prove about every value; everything else stays VARCHAR and SAYS why."
  [vs]
  (cond
    (all-match? iso-offset-re vs)
    {:sql "TIMESTAMP WITH TIME ZONE" :base :timestamptz :time-source :inferred :time-status :ok}

    (all-match? iso-date-re vs)
    {:sql "DATE" :base :date :time-source :inferred :time-status :ok}

    ;; An ISO datetime with no zone is the case where guessing is most tempting
    ;; and most wrong. It stays text, and the status names the missing piece so
    ;; the modeller can declare a zone at the source instead.
    (all-match? iso-zoneless-re vs)
    {:sql "VARCHAR" :base :string :time-source :none :time-status :zone-missing}

    ;; 01/02/2024 is the first of February or the second of January depending on
    ;; who typed it. There is no evidence in the data, so there is no inference.
    (all-match? ambiguous-date-re vs)
    {:sql "VARCHAR" :base :string :time-source :none :time-status :ambiguous}

    ;; Bare digits are ids far more often than they are epochs, and reading an
    ;; order number as a millisecond timestamp is a silent, confident error.
    (all-match? digits-re vs)
    {:sql "VARCHAR" :base :string :time-source :none :time-status :not-temporal}

    :else nil))

(defn- nested-value? [v] (or (map? v) (and (coll? v) (not (string? v)))))

(defn- decimal-shape
  "Precision and scale wide enough for every value: the widest integer part
   seen plus the deepest scale seen. Inferring from the first row only is how a
   column of 2-decimal prices silently rounds the one 6-decimal rate in it."
  [column values]
  (let [ds (map #(if (decimal? %) % (bigdec %)) values)
        scale (apply max 0 (map #(max 0 (.scale ^java.math.BigDecimal %)) ds))
        int-digits (apply max 1 (map #(max 1 (- (.precision ^java.math.BigDecimal %)
                                                (.scale ^java.math.BigDecimal %)))
                                     ds))
        precision (+ int-digits scale)]
    (when (> precision max-decimal-digits)
      (type-refusal! :analysis.type/precision
                     (str "Column \"" column "\" needs " precision
                          " decimal digits, beyond DuckDB's maximum of " max-decimal-digits
                          "; nothing was loaded. Round or narrow the selection in the source query — "
                          "this analysis will not round for you.")
                     {:column column :max max-decimal-digits :actual precision}))
    {:precision precision :scale scale}))

(defn- bigint-safe!
  [column values]
  (when-not (every? #(and (<= (long Long/MIN_VALUE) %) (<= % (long Long/MAX_VALUE))) values)
    (type-refusal! :analysis.type/out-of-range
                   (str "Column \"" column "\" holds an integer outside the range a "
                        "64-bit BIGINT can carry; nothing was loaded. Select it as text "
                        "in the source query if the exact digits matter.")
                   {:column column :max "BIGINT"})))

(defn- inferred-spec
  "Type from the values themselves, for an attribute the graph does not declare."
  [column values infer-time?]
  (let [vs (remove nil? values)]
    (cond
      ;; All-NULL stays VARCHAR: nothing in the data says what it would have
      ;; been, and guessing gives the agent a type the next page contradicts.
      ;; `:all-nil` marks it as carrying no evidence, so when several handles
      ;; are unioned (§108.2) the handle that DOES have values decides the type
      ;; instead of the empty one forcing everything to text.
      (empty? vs) {:sql "VARCHAR" :base :string :all-nil true}

      (some nested-value? vs) {:sql "VARCHAR" :base :string :nested true}

      (every? boolean? vs) {:sql "BOOLEAN" :base :boolean}

      (every? number? vs)
      (if (some decimal? vs)
        (let [{:keys [precision scale]} (decimal-shape column vs)]
          {:sql (str "DECIMAL(" precision "," scale ")") :base :decimal})
        (if (every? integer? vs)
          (do (bigint-safe! column vs) {:sql "BIGINT" :base :integer})
          {:sql "DOUBLE" :base :double}))

      (every? string? vs)
      (or (when infer-time? (inferred-time-spec vs))
          {:sql "VARCHAR" :base :string})

      ;; A number beside a string is not a VARCHAR column with some numbers in
      ;; it — stringifying it silently turns `sum(amount)` into a DuckDB error
      ;; the agent cannot explain, or worse, a lexicographic MAX.
      (or (some number? vs) (some boolean? vs))
      (type-refusal! :analysis.type/mixed
                     (str "Column \"" column "\" mixes incompatible value types "
                          "(numeric/boolean and text); nothing was loaded. Select one "
                          "shape in the source query, or cast in the source.")
                     {:column column})

      :else {:sql "VARCHAR" :base :string})))

(defn- declared-contradiction!
  "A declaration says what the column IS; it cannot make a value into something
   it is not. Text arriving in a `:decimal` column is a conflict between the
   model and the data, and the two ways to paper over it — coercing and
   dropping — both hand the reader a number that is not the one in the source.
   So it is refused, with the same code an inference-level conflict gets."
  [column declared vs pred]
  (when-not (every? pred vs)
    (type-refusal! :analysis.type/mixed
                   (str "Column \"" column "\" is declared " (name declared)
                        " but carries a value of another kind; nothing was loaded. "
                        "Fix the declaration or cast in the source query.")
                   {:column column :declared declared})))

(defn- declared-spec
  "The graph's own declaration wins over inference (§108.3: 优先使用 Graph 属性类型).
   A column is what the model says it is; values are a sample of it — but a
   value that CONTRADICTS the declaration is refused, not coerced."
  [column declared values infer-time?]
  (let [vs (remove nil? values)]
    (case declared
      :boolean (do (declared-contradiction! column declared vs boolean?)
                   {:sql "BOOLEAN" :base :boolean :type-source :declared})
      :integer (do (declared-contradiction! column declared vs integer?)
                   (bigint-safe! column vs)
                   {:sql "BIGINT" :base :integer :type-source :declared})
      :decimal (let [_ (declared-contradiction! column declared vs number?)
                     {:keys [precision scale]} (if (seq vs)
                                                 (decimal-shape column vs)
                                                 {:precision 38 :scale 6})]
                 ;; With no values there is no evidence about precision or
                 ;; scale, so the widest default stands in — but it must not
                 ;; then dictate the scale of a UNION with a handle that does
                 ;; have values (§108.2), hence the same `:all-nil` marker the
                 ;; inference path uses.
                 (cond-> {:sql (str "DECIMAL(" precision "," scale ")") :base :decimal
                          :type-source :declared}
                   (empty? vs) (assoc :all-nil true)))
      :string {:sql "VARCHAR" :base :string :type-source :declared}
      ;; Temporal types load as themselves only with `infer_time` ON
      ;; (§108.5 item 4). With the switch off this is exactly today's shape:
      ;; text, reported as `:declared-temporal` so an agent is not told a date
      ;; is an ordinary string.
      (:instant :date :datetime :time :timestamp)
      (if infer-time?
        (declared-time-spec column (temporal-declaration declared) vs)
        {:sql "VARCHAR" :base :string :type-source :declared-temporal
         :time-source :none :time-status :off})
      nil)))

(defn column-specs
  "[{:key :name :sql :base :type-source :time-source :time-status :nested}] —
   the ONE schema. `declared` maps attribute keyword → declared type keyword
   (from the pinned catalog); absent keys fall through to value inference.
   `opts` carries `:infer-time?` (§108.5 item 4, default off)."
  ([flat-rows] (column-specs flat-rows nil {}))
  ([flat-rows declared] (column-specs flat-rows declared {}))
  ([flat-rows declared {:keys [infer-time?]}]
   (mapv (fn [[k n]]
           (let [values (map #(get % k) flat-rows)
                 spec (or (when-let [d (get declared k)] (declared-spec n d values infer-time?))
                          (assoc (inferred-spec n values infer-time?) :type-source :inferred))]
             (assoc spec :key k :name n)))
         (column-names flat-rows))))

(defn describe-rows
  "Describe the exact SQL column names and load types, without values. Reports
   where each type came from (`:type-source`) and flags columns whose cells are
   printed structures rather than scalars, so an agent does not write
   `sum(...)` over an EDN string."
  ([flat-rows] (describe-rows flat-rows nil {}))
  ([flat-rows declared] (describe-rows flat-rows declared {}))
  ([flat-rows declared opts]
   {:table "data" :row-count (count flat-rows)
    :columns (mapv (fn [{:keys [key name sql type-source nested time-source time-status]}]
                     (cond-> {:name name :from (subs (str key) 1)
                              :type sql :type-source type-source}
                       nested (assoc :nested true)
                       time-source (assoc :time-source time-source)
                       time-status (assoc :time-status time-status)))
                   (column-specs flat-rows declared opts))}))

(defn- load-value
  "One cell, coerced to what its column was declared to hold. A DECIMAL column
   takes BigDecimal even when this particular row's value arrived as a Long —
   the column's type is the column's, not this row's."
  [base v]
  (cond
    (nil? v) nil
    (= :decimal base) (if (instance? java.math.BigDecimal v) v (bigdec v))
    (= :integer base) (long v)
    (= :double base) (double v)
    (= :boolean base) (boolean v)
    (= :timestamptz base) (offset-date-time (str v))
    (= :date base) (java.time.LocalDate/parse (str v))
    (= :time base) (java.time.LocalTime/parse (str v))
    :else (str v)))

(defn- load-table!
  "CREATE TABLE <table> (…) and batch-insert the flat rows, using the SAME
   specs discovery reports. The table is `data` for a single-handle analysis
   and `h1`…`hN` when several handles are bound (§108.2)."
  ([^Connection conn flat-rows specs] (load-table! conn flat-rows specs "data"))
  ([^Connection conn flat-rows specs table]
  (let [ddl (str "CREATE TABLE " table " ("
                 (str/join ", " (map (fn [{:keys [name sql]}] (str "\"" name "\" " sql)) specs))
                 ")")
        ins (str "INSERT INTO " table " VALUES ("
                 (str/join ", " (repeat (count specs) "?")) ")")]
    (with-open [st (.createStatement conn)]
      (.execute st ddl))
    (with-open [ps (.prepareStatement conn ins)]
      (doseq [row flat-rows]
        (doseq [[i {:keys [key base]}] (map-indexed vector specs)]
          (.setObject ps (inc i) (load-value base (get row key))))
        (.addBatch ps))
      (.executeBatch ps)))))

(defn- utc-string
  "A moment, rendered in UTC with an explicit offset.

   The JDBC driver builds its OffsetDateTime from the JVM's default zone, so
   without this the same instant is printed differently on two servers and the
   reader cannot tell which zone they are looking at. The INSTANT is unchanged —
   this normalizes the rendering only, and nothing is ever assumed about a value
   that arrived with no zone (that one is refused or kept as text)."
  [v]
  (cond
    (instance? java.time.OffsetDateTime v)
    (str (.withOffsetSameInstant ^java.time.OffsetDateTime v java.time.ZoneOffset/UTC))

    (instance? java.time.ZonedDateTime v)
    (str (.toOffsetDateTime (.withZoneSameInstant ^java.time.ZonedDateTime v java.time.ZoneOffset/UTC)))

    :else (str v)))

(def default-analysis-version
  "**1 until a later ruling flips it.** Version 1 is what every deployed agent
   parses today (every cell a string); changing the default would rewrite the
   shape under clients that never asked. §108.3: 改变既有字符串返回形状需要协议版本."
  1)

(def analysis-versions #{1 2})

(defn- typed-cell-fn
  "Version 2 (§108.5 item 3): cells carry their JDBC type — integers as
   integers, DOUBLE as numbers, BOOLEAN as booleans, SQL NULL as nil.

   **DECIMAL comes back as an exact STRING.** The wire is JSON-RPC
   (`mcp-transport` → cheshire), and while cheshire would print every digit of
   a BigDecimal, the readers on the other end parse JSON numbers into IEEE-754
   doubles — a 21-digit amount would arrive rounded, and nothing in the payload
   would say so. `semantic-protocol/wire-value` makes the same call for the
   other type JSON cannot carry (java.time scalars become ISO strings). An
   exact string is the only lossless option, so the 口径's number is never
   quietly approximated."
  [^java.sql.ResultSetMetaData md ^long i]
  (let [t (.getColumnType md (int i))]
    (condp contains? t
      #{java.sql.Types/BIGINT java.sql.Types/INTEGER java.sql.Types/SMALLINT
        java.sql.Types/TINYINT}
      (fn [^ResultSet rs] (let [v (.getLong rs (int i))] (when-not (.wasNull rs) v)))

      #{java.sql.Types/DOUBLE java.sql.Types/FLOAT java.sql.Types/REAL}
      (fn [^ResultSet rs] (let [v (.getDouble rs (int i))] (when-not (.wasNull rs) v)))

      #{java.sql.Types/BOOLEAN java.sql.Types/BIT}
      (fn [^ResultSet rs] (let [v (.getBoolean rs (int i))] (when-not (.wasNull rs) v)))

      #{java.sql.Types/DECIMAL java.sql.Types/NUMERIC}
      (fn [^ResultSet rs] (some-> (.getBigDecimal rs (int i)) str))

      ;; JSON has no datetime, so a moment travels as an ISO-8601 string that
      ;; KEEPS its offset. Dropping the offset would be the same mistake as
      ;; assuming a zone, one step later.
      #{java.sql.Types/TIMESTAMP_WITH_TIMEZONE java.sql.Types/TIMESTAMP
        java.sql.Types/DATE java.sql.Types/TIME}
      (fn [^ResultSet rs] (some-> (.getObject rs (int i)) utc-string))

      (fn [^ResultSet rs] (.getObject rs (int i))))))

(defn- read-result
  "ResultSet → {:columns [..] :rows [[..]..] :truncated? bool :truncation {…}}.

   §108.4: the display caps stay, but they are reported as EVIDENCE rather than
   applied silently. `:truncation` says whether rows or cells were cut, how many
   rows were shown and how many the SQL actually produced — an aggregate read
   off 200 of 4000 rows is a different claim from one read off all of them, and
   the reader can only know which if we say so. This is distinct from the INPUT
   limits, which refuse instead of truncating."
  [^ResultSet rs deadline version]
  (let [md    (.getMetaData rs)
        n     (.getColumnCount md)
        names (mapv #(.getColumnLabel md (int %)) (range 1 (inc n)))
        cut?  (volatile! false)
        ;; Strings are still truncated at `max-cell` in EVERY version, and the
        ;; `:truncation` evidence says so. Typing a cell does not make it
        ;; unbounded.
        clip  (fn [v] (if (and (string? v) (> (count v) max-cell))
                        (do (vreset! cut? true) (str (subs v 0 max-cell) "…"))
                        v))
        readers (mapv (fn [i]
                        (if (= 2 version)
                          (let [f (typed-cell-fn md i)] (fn [rs] (clip (f rs))))
                          (fn [^ResultSet rs]
                            (let [v (.getObject rs (int i))]
                              (clip (when-not (nil? v) (utc-string v)))))))
                      (range 1 (inc n)))]
    (loop [rows []]
      (if (and (< (count rows) max-rows) (.next rs))
        (do (check-deadline! deadline :encode)
            (recur (conj rows (mapv #(% rs) readers))))
        ;; Drain to learn the TRUE row count. Without it `:truncated? true` says
        ;; "there was more" and nothing about how much more, which is the part
        ;; a reader needs to judge the aggregate in front of them.
        (let [;; Version 2 carries a moment as an ISO string, so the cell alone
              ;; no longer says whether it IS a moment. The column's SQL type
              ;; does, and an agent deciding whether `date_trunc` will work needs
              ;; to read it rather than guess from the text.
              types (when (= 2 version)
                      (mapv (fn [i] {:name (.getColumnLabel md (int i))
                                     :type (.getColumnTypeName md (int i))})
                            (range 1 (inc n))))
              total (loop [t (count rows)]
                      (if (.next rs)
                        (do (check-deadline! deadline :encode) (recur (inc t)))
                        t))]
          (cond-> {:columns names :rows rows :truncated? (> total (count rows))
                   :truncation {:rows? (> total (count rows)) :cells? @cut?
                                :rows-shown (count rows) :rows-total total}}
            types (assoc :column-types types)))))))

(defn- run-sql
  "Execute the SQL with a wall-clock watchdog (`Statement.cancel`).

   The watchdog fires at `min(ATLAS_ANALYSIS_TIMEOUT_MS, remaining)`: the SQL
   stage gets its own budget or what the call has left, whichever is smaller.
   A cancel that lands because the end-to-end deadline ran out is reported as
   the deadline, not as a DuckDB syntax complaint the agent would try to fix."
  [^Connection conn sql deadline version]
  (let [budget (if deadline (min timeout-ms (remaining-ms deadline)) timeout-ms)
        dog (Executors/newSingleThreadScheduledExecutor)
        st  (.createStatement conn)]
    (try
      (.schedule dog ^Runnable #(try (.cancel ^Statement st) (catch Exception _ nil))
                 (long (max 1 budget)) TimeUnit/MILLISECONDS)
      (with-open [rs (.executeQuery st (str sql))]
        (read-result rs deadline version))
      (catch SQLException e
        (check-deadline! deadline :sql)
        (throw e))
      (finally
        ;; Every exit path: the statement is cancelled and closed, the watchdog
        ;; thread is shut down. The connection and the permit are released by
        ;; the callers' own `finally`.
        (try (.cancel ^Statement st) (catch Exception _ nil))
        (.shutdownNow dog)
        (.close st)))))

;; ---------------------------------------------------------------------------
;; Materialization (doc 108 §108.2 first paragraph, §108.5 item 1)
;;
;; The rows behind one handle do not change, so loading them again for every
;; SQL is work the second question pays for the first question's answer. The
;; file is an INTERNAL, bounded, read-only compute cache — never a Source and
;; never a business snapshot (§108.2) — and it dies with the handle that
;; spawned it.
;; ---------------------------------------------------------------------------

(defn- configure! [^Connection conn]
  (with-open [st (.createStatement conn)]
    (.execute st "SET enable_external_access=false")
    ;; **The host's zone must not leak into the OUTPUT either** (§108.3). DuckDB
    ;; renders a TIMESTAMPTZ in the session zone, so without this the same
    ;; instant comes back as a different string on two servers, and the reader
    ;; has no way to know which one they are looking at. Rendering is UTC with
    ;; an explicit offset; the instants themselves are untouched, and nothing is
    ;; ever assumed about a value that arrived WITHOUT a zone — that one is
    ;; refused or left as text.
    (.execute st "SET TimeZone='UTC'")
    ;; Per CONNECTION, therefore per call — not a process-wide guarantee.
    (.execute st "SET memory_limit='512MB'")
    (.execute st "SET threads=2")))

(defn materialize!
  "Write the loaded `data` table into a DuckDB database FILE plus an EDN
   sidecar holding the column specs. The sidecar exists so discovery over a
   materialized handle answers with the SAME `:type-source` / `:nested` facts
   the in-memory path reports — `DESCRIBE data` alone knows the SQL type but
   not where it came from, and two different discovery answers for one handle
   is the drift §108.3 rules out."
  [^Path db ^Path meta flat-rows declared deadline source opts]
  (let [specs (column-specs flat-rows declared opts)]
    (check-deadline! deadline :materialize)
    (with-open [conn (DriverManager/getConnection (str "jdbc:duckdb:" (str db)))]
      (configure! conn)
      (load-table! conn flat-rows specs))
    (check-deadline! deadline :materialize)
    (Files/writeString
     meta
     ^CharSequence (binding [*print-length* nil *print-level* nil]
                     (pr-str {:specs (mapv #(dissoc % :key) specs)
                              :from (mapv (fn [{:keys [key name]}] [name (subs (str key) 1)]) specs)
                              :row-count (count flat-rows)
                              :source source}))
     (make-array java.nio.file.OpenOption 0))
    specs))

(defn read-materialized-meta [^Path meta]
  (edn/read-string (Files/readString meta)))

(defn describe-materialized
  "Discovery over a materialized handle, from the sidecar — byte-identical in
   shape to `describe-rows` so the agent cannot tell which path answered."
  [meta]
  (let [{:keys [specs from row-count]} (read-materialized-meta meta)
        from* (into {} from)]
    {:table "data" :row-count row-count
     :columns (mapv (fn [{:keys [name sql type-source nested]}]
                      (cond-> {:name name :from (get from* name) :type sql
                               :type-source type-source}
                        nested (assoc :nested true)))
                    specs)}))

(defn query-materialized
  "ONE SQL over a materialized handle, opened READ-ONLY. The file is never
   written by a query: a model-written `INSERT` or `CREATE` is refused by
   DuckDB itself and reaches the agent as an ordinary SQL refusal, so the cache
   cannot become a place to put things."
  [^Path db sql deadline version]
  (check-deadline! deadline :load)
  (with-open [conn (DriverManager/getConnection
                    (str "jdbc:duckdb:" (str db))
                    (doto (Properties.)
                      (.setProperty "duckdb.read_only" "true")
                      (.setProperty "access_mode" "READ_ONLY")))]
    (configure! conn)
    (assoc (run-sql conn (str sql) deadline version)
           :analysis/version version)))

;; ---------------------------------------------------------------------------
;; Several handles in one analysis (doc 108 §108.2 second paragraph,
;; §108.5 item 2 — initial version: one identity, one graph, one version)
;; ---------------------------------------------------------------------------

(defn cross-join-risk?
  "A deliberately crude PRE-CHECK, and named as one: does this statement join
   the handle tables without any equality predicate to pair rows by?

   It reads the SQL text, so it is a guard and NOT a guarantee — DuckDB has no
   setting that expresses «refuse if the intermediate exceeds N rows», and
   `memory_limit` only notices after the memory is gone. What it catches is the
   case that actually bites: `FROM h1, h2` or a bare `JOIN` over two large
   handles, where the intermediate is the product of the inputs and the final
   SELECT looks perfectly small. A join WITH an equality predicate is let
   through and bounded by `memory_limit` alone; say so rather than implying
   the cap is enforced."
  [sql row-counts]
  (let [sql (str sql)
        joins? (or (re-find #"(?i)\bjoin\b" sql)
                   ;; comma join: `FROM h1, h2`
                   (re-find #"(?i)\bfrom\b[^;]*?\bh\d+\b\s*,\s*\w" sql))
        paired? (re-find #"(?i)\b(on|using)\b" sql)
        [a b] (take 2 (sort > (conj (vec row-counts) 0 0)))]
    (and joins? (not paired?) (> (* (long a) (long b)) max-join-rows))))

(defn reconcile-specs
  "One column list for the unioned `data` view over several handles' specs.

   Same name, compatible type, or a REFUSAL naming the column and the handle
   POSITIONS — never another owner's handle id. Widening follows what the
   values can carry: BIGINT beside DECIMAL widens to DECIMAL, anything beside
   DOUBLE widens to DOUBLE. Text beside a number does not widen at all: the
   union would have to stringify the numeric side, and `sum()` over that is
   either an error or a lexicographic answer that reads as a number."
  [spec-lists]
  (let [order (vec (distinct (mapcat #(map :name %) spec-lists)))
        by-name (fn [n] (keep-indexed (fn [i specs]
                                        (when-let [sp (first (filter #(= n (:name %)) specs))]
                                          (assoc sp :position (inc i))))
                                      spec-lists))]
    (mapv
     (fn [n]
       (let [all (by-name n)
             known (remove :all-nil all)
             bases (set (map :base known))]
         (cond
           (empty? known) {:name n :sql "VARCHAR" :base :string :positions (mapv :position all)}

           (and (contains? bases :string) (> (count bases) 1))
           (type-refusal! :analysis.type/mixed
                          (str "Column \"" n "\" is text in one handle and numeric or boolean in "
                               "another; nothing was loaded. The union would have to stringify the "
                               "numeric side. Select one shape, or cast in the source query.")
                          {:column n :positions (mapv :position known)})

           (and (contains? bases :boolean) (> (count bases) 1))
           (type-refusal! :analysis.type/mixed
                          (str "Column \"" n "\" is boolean in one handle and something else in "
                               "another; nothing was loaded.")
                          {:column n :positions (mapv :position known)})

           ;; A moment in one handle and text in another is the zone-missing
           ;; case wearing a union: widening to text would silently drop the
           ;; offsets the typed side actually has.
           (and (some #{:timestamptz :date :time} bases) (> (count bases) 1))
           (type-refusal! :analysis.type/mixed
                          (str "Column \"" n "\" is a time type in one handle and something "
                               "else in another (often a value with no UTC offset); nothing was "
                               "loaded.")
                          {:column n :positions (mapv :position known)})

           (contains? bases :double)
           {:name n :sql "DOUBLE" :base :double :positions (mapv :position all)}

           (contains? bases :decimal)
           (let [ds (filter #(= :decimal (:base %)) known)
                 shape (fn [sp] (let [[_ p s] (re-find #"DECIMAL\((\d+),(\d+)\)" (:sql sp))]
                                  [(parse-long p) (parse-long s)]))
                 scale (apply max (map (comp second shape) ds))
                 ints (apply max (concat (map #(- (first (shape %)) (second (shape %))) ds)
                                         ;; a BIGINT's widest integer part
                                         (when (contains? bases :integer) [19])))
                 precision (min max-decimal-digits (+ ints scale))]
             {:name n :sql (str "DECIMAL(" precision "," scale ")") :base :decimal
              :positions (mapv :position all)})

           :else
           (let [sp (first known)]
             (assoc (select-keys sp [:sql :base :nested]) :name n
                    :positions (mapv :position all))))))
     order)))

(defn- union-view-sql
  "`data` = UNION ALL of every handle by column NAME, missing columns filled
   with a typed NULL, plus `_atlas_handle` (1-based). Duplicates are KEPT:
   deduplication needs a declared business key, and guessing one is how two
   rows that genuinely describe two events become one (§108.2)."
  [unified spec-lists]
  (str "CREATE VIEW data AS "
       (str/join
        " UNION ALL "
        (map-indexed
         (fn [i specs]
           (let [have (set (map :name specs))]
             (str "SELECT " (inc i) " AS _atlas_handle, "
                  (str/join ", "
                            (map (fn [{:keys [name sql]}]
                                   (if (contains? have name)
                                     (str "CAST(\"" name "\" AS " sql ") AS \"" name "\"")
                                     (str "CAST(NULL AS " sql ") AS \"" name "\"")))
                                 unified))
                  " FROM h" (inc i))))
         spec-lists))))

(defn- bind-handles!
  "Bind each authorized handle as table `h1`, `h2`, … in ONE connection.

   A materialized handle (PR I) is ATTACHed read-only and exposed as a view, so
   a multi-handle call reuses the table that already exists instead of building
   it again. **The ATTACH happens before `enable_external_access` is turned
   off**, which is the only window in which it can: afterwards DuckDB refuses
   to open any file at all, model-written `ATTACH` included. SQL therefore
   never receives a path, and cannot reach one."
  [^Connection conn inputs]
  (doseq [[i {:keys [path]}] (map-indexed vector inputs) :when path]
    (with-open [st (.createStatement conn)]
      (.execute st (str "ATTACH '" (str path) "' AS m" (inc i) " (READ_ONLY)"))
      (.execute st (str "CREATE VIEW h" (inc i) " AS SELECT * FROM m" (inc i) ".data")))))

(defn query-handles
  "Several handles → tables `h1`…`hN` plus the unioned `data` → ONE SQL, or a
   per-table description when `sql` is nil.

   `inputs` is a vector of `{:rows flat-rows :specs specs}` (load it) or
   `{:path db-file :specs specs}` (attach the materialization)."
  [inputs sql deadline {:keys [version] :or {version default-analysis-version}}]
  (let [unified (reconcile-specs (mapv :specs inputs))]
    (check-deadline! deadline :load)
    (with-open [conn (DriverManager/getConnection "jdbc:duckdb:")]
      ;; ATTACH first — see `bind-handles!`.
      (bind-handles! conn inputs)
      (configure! conn)
      (doseq [[i {:keys [rows specs path]}] (map-indexed vector inputs) :when (nil? path)]
        (load-table! conn rows specs (str "h" (inc i))))
      (check-deadline! deadline :load)
      (with-open [st (.createStatement conn)]
        (.execute st (union-view-sql unified (mapv :specs inputs))))
      (if (nil? sql)
        {:tables (conj (vec (map-indexed
                             (fn [i {:keys [specs rows path meta]}]
                               (assoc (if path
                                        (describe-materialized meta)
                                        {:table "data" :row-count (count rows)
                                         :columns (mapv (fn [{:keys [name sql type-source nested]}]
                                                          (cond-> {:name name :type sql
                                                                   :type-source type-source}
                                                            nested (assoc :nested true)))
                                                        specs)})
                                      :table (str "h" (inc i))))
                             inputs))
                       {:table "data"
                        :row-count (reduce + 0 (map #(or (:row-count %) (count (:rows %))) inputs))
                        :columns (into [{:name "_atlas_handle" :type "BIGINT" :type-source :analysis}]
                                       (map (fn [{:keys [name sql]}] {:name name :type sql
                                                                      :type-source :reconciled}))
                                       unified)})
         :analysis/version version}
        (assoc (run-sql conn (str sql) deadline version)
               :analysis/version version)))))

(defn query-rows
  "flat rows → an in-memory DuckDB table `data` → ONE SQL → the capped result.
   The connection is per-call and dropped with it. Throws
   `java.sql.SQLException` on a bad statement — the caller turns that into its
   own self-correcting error — and an `ex-info` carrying `:analysis/refusal`
   when the end-to-end deadline runs out at the load, sql or encode stage."
  ([flat-rows sql] (query-rows flat-rows sql nil {}))
  ([flat-rows sql deadline] (query-rows flat-rows sql deadline {}))
  ([flat-rows sql deadline {:keys [declared version infer-time?]}]
   (let [version (or version default-analysis-version)
         cols (column-specs flat-rows declared {:infer-time? infer-time?})]
     (check-deadline! deadline :load)
     (with-open [conn (DriverManager/getConnection "jdbc:duckdb:")]
       ;; The process-wide half of the memory story is `total-input-bytes`
       ;; plus the permit count; see `configure!`.
       (configure! conn)
       (load-table! conn flat-rows cols)
       (check-deadline! deadline :load)
       (assoc (run-sql conn (str sql) deadline version)
              :analysis/version version)))))
