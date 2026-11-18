(ns open.atlas.server.mcp.impl.saved-query
  "Saved queries: an EQL request written once by someone who knows the model,
   with a name, a description and typed parameters, run by name
   (proposal studio-saved-queries §3, owner ruling 2026-10-10).

   Pure functions only. The data plane runs a saved query, the Builder writes
   one and the publish step checks one — all three through THIS ns, so «what
   is a well-formed saved query» and «what does binding a parameter do» have
   one answer (D1.13).

   ## Parameters are values, never text

   A parameter is a `?name` symbol standing where a VALUE goes. Binding
   replaces the symbol with the caller's value after checking it against the
   declared type; nothing is spliced into a string. Keys, attribute names and
   join structure cannot be parameters, so the query's shape is fixed when it
   is saved — which is what makes the publish check (`stale-findings`) mean
   anything.

   An optional parameter may only stand as a MAP VALUE (`{:due_before ?as_of}`):
   when the caller leaves it out, that entry is dropped. Anywhere else there
   would be nothing sensible to drop, so saving it is refused."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(def max-saved-queries
  "Per graph. A list an agent reads before choosing; past this it stops being
   a list anybody reads."
  200)

(def max-eql-bytes
  "The data plane's own request-size limit (data-plane-api-v1 §3.3): a saved
   query larger than a request could never run."
  8192)

(def param-types
  #{:string :integer :number :decimal :boolean :date :datetime :keyword})

(def ^:private name-re #"[a-z][a-z0-9_]{0,63}")

(defn- finding [code path message & {:as extra}]
  (merge {:code code :severity :error :path path :message message} extra))

;; ---------------------------------------------------------------------------
;; Placeholders
;; ---------------------------------------------------------------------------

(defn placeholder-name
  "`?as_of` → \"as_of\"; anything else → nil."
  [x]
  (when (and (symbol? x) (nil? (namespace x)) (str/starts-with? (name x) "?")
             (> (count (name x)) 1))
    (subs (name x) 1)))

(defn- placeholder-uses
  "Every placeholder in `eql`, with whether it stands as a map value."
  [eql]
  (let [uses (atom [])]
    (letfn [(visit [x map-value?]
              (if-let [n (placeholder-name x)]
                (swap! uses conj {:name n :map-value? map-value?})
                (cond
                  (map? x) (doseq [[k v] x] (visit k false) (visit v true))
                  (coll? x) (doseq [v x] (visit v false)))))]
      (visit eql false))
    @uses))

;; ---------------------------------------------------------------------------
;; Attributes an EQL request names
;; ---------------------------------------------------------------------------

(defn- qualified-kw [x] (when (and (keyword? x) (namespace x)) x))

(declare query-attributes)

(defn- key-attributes [k]
  (cond
    (keyword? k) (keep qualified-kw [k])
    ;; `(:ns/all_x {:limit 5})` — a parameterised key.
    (seq? k) (key-attributes (first k))
    ;; `[:ns/id 42]` — an ident.
    (vector? k) (keep qualified-kw [(first k)])
    :else nil))

(defn- element-attributes [e]
  (cond
    (keyword? e) (keep qualified-kw [e])
    (seq? e) (let [head (first e)]
               (if (map? head) (element-attributes head) (key-attributes head)))
    (map? e) (mapcat (fn [[k v]]
                       (concat (key-attributes k)
                               (cond
                                 (vector? v) (query-attributes v)
                                 ;; union: one branch per target entity
                                 (map? v) (mapcat (fn [[uk uv]]
                                                    (concat (key-attributes uk)
                                                            (when (vector? uv) (query-attributes uv))))
                                                  v)
                                 :else nil)))
                     e)
    :else nil))

(defn query-attributes
  "The qualified attributes an EQL vector names — selections, join keys,
   parameterised keys and idents. Parameter maps are not read: their keys are
   resolver parameters, not attributes."
  [q]
  (when (vector? q) (mapcat element-attributes q)))

(defn eql-attributes
  "Every attribute a saved query's `:eql` names, including its `:entity` keys."
  [eql]
  (set (if (map? eql)
         (concat (keep qualified-kw (keys (:entity eql))) (query-attributes (:query eql)))
         (query-attributes eql))))

(defn- has-mutation-call? [eql]
  (some (fn [x] (and (seq? x) (symbol? (first x)) (not (placeholder-name (first x)))))
        (tree-seq coll? seq eql)))

;; ---------------------------------------------------------------------------
;; Shape
;; ---------------------------------------------------------------------------

(defn- param-findings [i p]
  (let [path [:params i]]
    (cond
      (not (map? p))
      [(finding :saved-query/invalid path "a parameter is a map" :expected "{:name … :type …}")]

      :else
      (cond-> []
        (not (and (string? (:name p)) (re-matches name-re (:name p))))
        (conj (finding :saved-query/invalid (conj path :name)
                       "a parameter name is lower snake_case, at most 64 characters"
                       :expected "[a-z][a-z0-9_]*"))
        (not (contains? param-types (:type p)))
        (conj (finding :saved-query/invalid (conj path :type) "unknown parameter type"
                       :expected (vec (sort param-types))))
        (and (contains? p :required?) (not (boolean? (:required? p))))
        (conj (finding :saved-query/invalid (conj path :required?) "required? is a boolean"))
        (and (contains? p :description) (not (string? (:description p))))
        (conj (finding :saved-query/invalid (conj path :description) "a parameter description is a string"))
        (seq (remove #{:name :type :required? :description :example} (keys p)))
        (conj (finding :saved-query/invalid path "unknown parameter key"
                       :got (vec (remove #{:name :type :required? :description :example} (keys p)))
                       :expected [:name :type :required? :description :example]))))))

(declare coerce)

(defn findings
  "One saved query's shape → finding-v1 vector (empty = well formed).

   Checks only what can be checked without a model: names, types, that every
   placeholder is declared and every declaration used, and that an optional
   parameter stands where it can be dropped. Whether the attributes exist is
   `stale-findings`, against a catalog."
  [{:keys [name description kind params eql] :as sq}]
  (if-not (map? sq)
    [(finding :saved-query/invalid [] "a saved query is a map")]
    (let [extra (remove #{:name :description :kind :params :eql} (keys sq))
          params (or params [])
          base (cond-> []
                 (seq extra)
                 (conj (finding :saved-query/invalid [] "unknown key" :got (vec extra)
                                :expected [:name :description :kind :params :eql]))
                 (not (and (string? name) (re-matches name-re name)))
                 (conj (finding :saved-query/invalid [:name]
                                "the name is lower snake_case, at most 64 characters"
                                :expected "[a-z][a-z0-9_]*"))
                 (not (and (string? description) (not (str/blank? description))))
                 (conj (finding :saved-query/invalid [:description]
                                "a description is required: it is what an agent chooses a query by"))
                 (and (string? description) (> (count description) 2000))
                 (conj (finding :saved-query/invalid [:description] "the description is over 2000 characters"))
                 (not (contains? #{nil :query} kind))
                 (conj (finding :saved-query/kind-unsupported [:kind]
                                "only :query saved queries exist so far; mutations come later"
                                :expected :query))
                 (not (vector? params))
                 (conj (finding :saved-query/invalid [:params] "params is a vector")))
          eql-shape (cond
                      (vector? eql) []
                      (and (map? eql) (map? (:entity eql)) (vector? (:query eql))
                           (empty? (dissoc eql :entity :query))) []
                      :else [(finding :saved-query/invalid [:eql]
                                      "the EQL is a vector, or {:entity {…} :query [...]}")])]
      (if (or (seq eql-shape) (not (vector? params)))
        (into base eql-shape)
        (let [declared (into {} (keep (fn [p] (when (map? p) [(:name p) p]))) params)
              uses (placeholder-uses eql)
              used (set (map :name uses))]
          (cond-> (into base (mapcat param-findings (range) params))
            (< (count declared) (count (filter map? params)))
            (conj (finding :saved-query/invalid [:params] "two parameters share a name"))
            (seq (remove declared used))
            (conj (finding :saved-query/invalid [:eql] "a placeholder is not declared in params"
                           :got (vec (sort (remove declared used)))))
            (seq (remove used (keys declared)))
            (conj (finding :saved-query/invalid [:params] "a declared parameter is never used in the EQL"
                           :got (vec (sort (remove used (keys declared))))))
            (seq (for [{n :name mv :map-value?} uses
                       :when (and (not mv) (not (:required? (declared n))))] n))
            (conj (finding :saved-query/invalid [:eql]
                           "an optional parameter must stand as a map value, so it can be dropped when absent"
                           :got (vec (sort (distinct (for [{n :name mv :map-value?} uses
                                                           :when (and (not mv) (not (:required? (declared n))))] n))))))
            (has-mutation-call? eql)
            (conj (finding :saved-query/kind-unsupported [:eql] "a saved query may not call a mutation"))
            (> (count (.getBytes (pr-str eql) "UTF-8")) max-eql-bytes)
            (conj (finding :saved-query/invalid [:eql] "the EQL is larger than a request may be"
                           :expected max-eql-bytes))
            (seq (for [p params :when (and (map? p) (contains? p :example) (contains? param-types (:type p))
                                           (:findings (coerce p (:example p))))] p))
            (conj (finding :saved-query/invalid [:params] "an example does not match its parameter's type"))))))))

(defn set-findings
  "A whole draft set: each query's shape, unique names, and the size cap."
  [sqs]
  (cond
    (not (vector? sqs)) [(finding :saved-query/invalid [] "saved queries are a vector")]
    :else
    (let [names (keep :name sqs)
          dupes (vec (sort (for [[n c] (frequencies names) :when (> c 1)] n)))]
      (cond-> (vec (mapcat (fn [i sq] (map #(update % :path (fn [p] (into [i] p))) (findings sq)))
                           (range) sqs))
        (seq dupes) (conj (finding :saved-query/invalid [] "two saved queries share a name" :got dupes))
        (> (count sqs) max-saved-queries)
        (conj (finding :saved-query/invalid [] "too many saved queries on one graph"
                       :expected max-saved-queries))))))

(defn stale-findings
  "Attributes a saved query names that `known` (a set of attribute keywords,
   the catalog of the model it will run on) does not have. Static: no source
   is touched, so it can run on every publish."
  [sq known]
  (let [missing (sort (remove known (eql-attributes (:eql sq))))]
    (when (seq missing)
      [(finding :saved-query/stale [:eql]
                (str "saved query " (:name sq) " names attributes this model does not have")
                :got (mapv #(subs (str %) 1) missing))])))

;; ---------------------------------------------------------------------------
;; Binding
;; ---------------------------------------------------------------------------

(defn- type-error [p v]
  {:findings [(finding :saved-query/param-type [:params (:name p)]
                       (str "parameter " (:name p) " must be " (clojure.core/name (:type p)))
                       :expected (:type p) :got (cond (nil? v) "nil" :else (.getSimpleName (class v))))]})

(defn coerce
  "One value against its declaration → `{:value v}` or `{:findings […]}`.
   Values arrive from JSON (MCP) or EDN (/eql), so both spellings of a number
   are accepted; nothing is parsed out of a string except dates and keywords,
   whose string form IS their JSON form."
  [p v]
  (case (:type p)
    :string (if (string? v) {:value v} (type-error p v))
    :integer (cond (integer? v) {:value (long v)}
                   (and (number? v) (== v (Math/rint (double v)))) {:value (long v)}
                   :else (type-error p v))
    (:number :decimal) (if (number? v) {:value v} (type-error p v))
    :boolean (if (boolean? v) {:value v} (type-error p v))
    :date (if (and (string? v) (try (java.time.LocalDate/parse v) true (catch Exception _ false)))
            {:value v} (type-error p v))
    :datetime (if (and (string? v) (or (try (java.time.Instant/parse v) true (catch Exception _ false))
                                       (try (java.time.OffsetDateTime/parse v) true (catch Exception _ false))))
                {:value v} (type-error p v))
    :keyword (cond (keyword? v) {:value v}
                   (and (string? v) (re-matches #"[A-Za-z][A-Za-z0-9_.\-]*(/[A-Za-z0-9_.\-]+)?" v))
                   {:value (keyword (str/replace-first v #"^:" ""))}
                   :else (type-error p v))
    (type-error p v)))

(defn bind
  "A saved query and the caller's parameters → `{:request <EQL>}` or
   `{:findings […]}`. Keys of `params` may be strings or keywords."
  [sq params]
  (let [params (into {} (map (fn [[k v]] [(if (keyword? k) (clojure.core/name k) (str k)) v])) (or params {}))
        declared (into {} (map (juxt :name identity)) (:params sq))
        unknown (sort (remove declared (keys params)))
        missing (sort (for [[n p] declared :when (and (:required? p) (not (contains? params n)))] n))
        coerced (into {} (for [[n v] params :when (declared n)] [n (coerce (declared n) v)]))
        bad (vec (mapcat :findings (vals coerced)))]
    (cond
      (seq unknown)
      {:findings [(finding :saved-query/param-unknown [:params] "unknown parameter"
                           :got (vec unknown) :expected (vec (sort (keys declared))))]}
      (seq missing)
      {:findings [(finding :saved-query/param-missing [:params] "a required parameter is missing"
                           :got (vec missing))]}
      (seq bad) {:findings bad}
      :else
      (let [values (into {} (map (fn [[n r]] [n (:value r)])) coerced)
            absent? (fn [x] (when-let [n (placeholder-name x)] (not (contains? values n))))]
        {:request
         (walk/prewalk
          (fn [x]
            (cond
              (and (map? x) (not (record? x)))
              (into (empty x) (remove (fn [[_ v]] (absent? v))) x)
              (placeholder-name x) (get values (placeholder-name x))
              :else x))
          (:eql sq))}))))

;; ---------------------------------------------------------------------------
;; What a caller is shown
;; ---------------------------------------------------------------------------

(defn params-schema
  "The parameters as JSON Schema — what an MCP client validates against."
  [sq]
  {"type" "object"
   "additionalProperties" false
   "properties"
   (into {} (for [p (:params sq)]
              [(:name p)
               (cond-> (case (:type p)
                         :string {"type" "string"}
                         :integer {"type" "integer"}
                         (:number :decimal) {"type" "number"}
                         :boolean {"type" "boolean"}
                         :date {"type" "string" "format" "date"}
                         :datetime {"type" "string" "format" "date-time"}
                         :keyword {"type" "string" "description" "a keyword, e.g. ns/value"})
                 (:description p) (assoc "description" (:description p))
                 (contains? p :example) (assoc "examples" [(let [e (:example p)] (if (keyword? e) (subs (str e) 1) e))]))]))
   "required" (vec (for [p (:params sq) :when (:required? p)] (:name p)))})

(defn describe
  "One saved query as a listing shows it. The EQL travels only when asked for:
   a caller depends on the name and the parameters, not on the query text."
  [sq include-eql?]
  (cond-> {:name (:name sq)
           :description (:description sq)
           :kind (name (or (:kind sq) :query))
           :params (params-schema sq)}
    include-eql? (assoc :eql (pr-str (:eql sq)))))

(defn example-params
  "The examples given for every parameter, as a params map — what a check run
   uses. nil when a required parameter has no example."
  [sq]
  (let [ps (:params sq)]
    (when (every? #(or (not (:required? %)) (contains? % :example)) ps)
      (into {} (for [p ps :when (contains? p :example)] [(:name p) (:example p)])))))
