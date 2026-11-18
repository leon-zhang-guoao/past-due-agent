(ns open.atlas.common.semantic-record
  "Validation of materialized records, not partial query responses. Pure, no
   coercion. Missing non-identity attributes are distinct from explicit null."
  (:require [open.atlas.common.contract :as version]
            [open.atlas.common.semantic :as semantic]))

(defn- json? [value depth]
  (and (< depth 64)
       (or (nil? value) (string? value) (boolean? value)
           (integer? value) (instance? java.math.BigDecimal value)
           (and (or (instance? Double value) (instance? Float value)) (Double/isFinite (double value)))
           (and (vector? value) (every? #(json? % (inc depth)) value))
           (and (map? value) (every? string? (keys value)) (every? #(json? % (inc depth)) (vals value))))))

(defn- scalar? [type value]
  (case type
    :string (string? value)
    :integer (integer? value)
    :decimal (instance? java.math.BigDecimal value)
    :boolean (boolean? value)
    :date (instance? java.time.LocalDate value)
    :datetime (instance? java.time.Instant value)
    :json (json? value 0)
    false))

(defn- validate*
  "Validate an identity-bearing record and nested records in a complete vocabulary.
   required is an explicit set of additional required attributes for this root.
   Dates are LocalDate, timestamps Instant, decimals BigDecimal in this internal
   representation; wire/connector conversion must happen explicitly beforehand.
   No record is returned on failure. Findings omit values and unknown key names."
  [vocabulary entity-id record required identity-required? options]
   (let [;; Records carry no descriptor of their own. The 2.0 vocabulary schema
         ;; is a strict superset of 1.4's (one optional key, `:attribute/targets`),
         ;; so reading every vocabulary through it validates a 1.x closure
         ;; exactly as before and lets a 2.0 union ref through (contract 2.0 §6.4).
         vf (:findings (semantic/validate-vocabulary vocabulary (merge {:contract version/semantic-v2} options)))
         issue (fn [code path] {:code code :severity :error :path path})
         ;; Contract 2.0 §6.4: the branch a union value belongs to is the ONE
         ;; target Entity whose identity attribute the value carries. Zero or
         ;; several is not a record of any branch and is refused, not guessed.
         branch-of (fn [targets value]
                     (let [hits (filter (fn [e] (contains? value (first (get-in vocabulary [:entities e :entity/identity]))))
                                        targets)]
                       (when (= 1 (count hits)) (first hits))))]
     (cond
       (seq vf) {:findings vf}
       (not (contains? (:entities vocabulary) entity-id))
       {:findings [(issue :record/entity [])]}
       (or (not (set? required))
           (not-every? (set (get-in vocabulary [:entities entity-id :entity/attributes])) required))
       {:findings [(issue :record/required-invalid [])]}
       :else
       (letfn [(check [eid row path required depth]
                 (cond
                   (> depth 64) [(issue :record/depth-limit path)]
                   (not (map? row)) [(issue :record/shape path)]
                   :else
                   (let [entity (get-in vocabulary [:entities eid])
                         members (set (:entity/attributes entity))
                         mandatory (if identity-required? (into required (:entity/identity entity)) required)]
                     (concat
                      (when (some #(not (members %)) (keys row))
                        [(issue :record/unknown-attribute path)])
                      (for [a (sort-by str mandatory) :when (not (contains? row a))]
                        (issue :record/missing (conj path a)))
                      (mapcat
                       (fn [[a value]]
                         (when (members a)
                           (let [definition (get-in vocabulary [:attributes a])
                                 p (conj path a)]
                             (cond
                               (nil? value) (when-not (:attribute/nullable? definition)
                                              [(issue :record/null p)])
                               (= :ref (:attribute/type definition))
                               (let [targets (:attribute/targets definition)
                                     child-check (fn [child cp]
                                                   (if targets
                                                     (if-let [e (and (map? child) (branch-of targets child))]
                                                       (check e child cp #{} (inc depth))
                                                       [(issue :record/union-ambiguous cp)])
                                                     (check (:attribute/target definition) child cp #{} (inc depth))))]
                                 (if (= :many (:attribute/cardinality definition))
                                   (if (vector? value)
                                     (mapcat (fn [[i child]] (child-check child (conj p i)))
                                             (map-indexed vector value))
                                     [(issue :record/cardinality p)])
                                   (child-check value p)))
                               (not (scalar? (:attribute/type definition) value))
                               [(issue :record/type p)]
                               :else [])))) row)))))]
         (let [findings (vec (check entity-id record [] required 0))]
           (if (seq findings) {:findings findings}
               {:record record :findings []}))))))

(defn validate-record
  "Validate a materialized record with complete identity, recursively."
  ([vocabulary entity-id record] (validate-record vocabulary entity-id record #{}))
  ([vocabulary entity-id record required]
   (validate-record vocabulary entity-id record required {}))
  ([vocabulary entity-id record required options]
   (validate* vocabulary entity-id record required true options)))

(defn validate-projection
  "Validate present values only, including nested projections. Omitted fields
   (including identity) are not missing-record errors. Does not establish that a
   requested field was found, authorized, or returned; result-state handling is
   a separate concern. No values are synthesized."
  ([vocabulary entity-id projection]
   (validate-projection vocabulary entity-id projection {}))
  ([vocabulary entity-id projection options]
   (validate* vocabulary entity-id projection #{} false options)))
