(ns open.atlas.common.semantic-static
  "Pure semantic lowering for the engine's existing static data implementation."
  (:require [open.atlas.common.connector.spi :as spi]))

(defn binding-operation [binding]
  (assoc (:binding/implementation binding) :type "static"))

(defn- finding
  "A binding finding (finding-v1): path relative to the binding, message states
   the expectation and never echoes a value."
  [code path message]
  {:code code :severity :error :path path :message message})

(defn- slot-findings
  "static-v1 (owner ruling 2026-09-18, #3): input-map / output-map values are
   slot names and must equal the attribute's qualified name, `ns/name`."
  [root m]
  (if-not (map? m)
    [(finding :connector.binding/shape [root] "Expected a map of attribute to slot name")]
    (for [[a n] m :when (not (and (qualified-keyword? a) (= (subs (str a) 1) n)))]
      (finding :connector.binding/slot-mismatch [root a]
               "Slot name must equal the attribute's qualified name (ns/name)"))))

(defn- input-form-findings
  "Semantic contract 2.0 §2.2: static matches rows on determinate values, so
   neither an optional root (may be absent) nor a nested root (arrives as rows)
   can be bound. The execution layer says which roots those are through
   `:binding/input-optional` / `:binding/input-nested` on the Binding."
  [binding]
  (concat
   (for [a (sort (:binding/input-optional binding))]
     (finding :connector.binding/optional-unsupported [:binding/input-map a]
              "A static binding cannot take an optional input: :match-on needs a determinate value"))
   (for [a (sort (keys (:binding/input-nested binding)))]
     (finding :connector.binding/nested-input-unsupported [:binding/input-map a]
              "A static binding cannot take a nested input: :match-on needs a determinate value"))))

(defn- output-form-findings
  "Semantic contract 2.0 §6.3: static supports union and recursion output. A
   union value must be a record the executor can discriminate -- a map (or a
   vector of maps for a to-many) carrying one branch's identity -- so a row
   whose union slot holds anything else is refused at write time. The
   execution layer says which output keys are unions through
   `:binding/output-union`; recursion needs nothing of static."
  [binding rows]
  (for [a (sort (:binding/output-union binding))
        :when (and (sequential? rows)
                   (some (fn [row]
                           (let [v (get row a)]
                             (not (or (nil? v) (map? v) (and (vector? v) (every? map? v))))))
                         rows))]
    (finding :connector.binding/union-identity-mapping [:binding/output-map a]
             "A union output's value in :data must be a record map (or a vector of them) carrying one branch's identity attribute")))

(defn validate-binding
  "Validate a static semantic Binding. Returns `{:findings [...]}` with one
   code and precise path per clause (owner ruling 2026-09-18); all clause
   findings are collected. Empty findings = valid."
  [binding]
  (let [implementation (:binding/implementation binding)
        data (:data implementation)
        inputs (:binding/input-map binding)
        match (:match-on implementation)
        match-keys (cond (nil? match) [] (keyword? match) [match] (vector? match) match :else nil)
        rows (if (map? data) [data] data)]
    {:findings
     (vec
      (concat
       (when-not (= :read (:binding/effect binding))
         [(finding :connector.binding/effect [:binding/effect] "A static binding is read-only")])
       (input-form-findings binding)
       (output-form-findings binding rows)
       (if-not (map? implementation)
         [(finding :connector.binding/shape [:binding/implementation] "Expected the implementation to be a map")]
         (concat
          (for [k (keys implementation) :when (not (#{:data :match-on} k))]
            (finding :connector.binding/unknown-key [:binding/implementation k]
                     "static-v1 binding implementation accepts :data and :match-on only"))
          (cond
            (not (or (map? data) (and (vector? data) (every? map? data))))
            [(finding :connector.binding/shape [:binding/implementation :data]
                      "Expected :data to be a row map or a vector of row maps")]
            (not (every? #(every? qualified-keyword? (keys %)) rows))
            [(finding :connector.binding/shape [:binding/implementation :data]
                      "Row keys must be qualified attribute keywords")])
          (cond
            (nil? match-keys)
            [(finding :connector.binding/shape [:binding/implementation :match-on]
                      "Expected :match-on to be an attribute keyword or a vector of them")]
            (map? inputs)
            (for [k match-keys :when (not (contains? inputs k))]
              (finding :connector.binding/match-on [:binding/implementation :match-on]
                       "Every :match-on attribute must appear in the input map")))))
       (slot-findings :binding/input-map inputs)
       (slot-findings :binding/output-map (:binding/output-map binding))))}))

(defmethod spi/semantic-adapter "static" [_]
  ;; Checker protocol (owner ruling 2026-09-18): `:validate` answers the
  ;; connector's `{:findings [...]}` directly; the execution layer prefixes
  ;; each path with `[:bindings bid]`. Empty findings = valid.
  {:validate validate-binding :lower binding-operation
   :source-collection? true :collection-limit? true})
