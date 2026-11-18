(ns open.atlas.common.semantic
  "Semantic vocabulary validation. This fragment is not a Graph/artifact reader
   and is not registered as a runtime-supported contract. No IO or inference."
  (:require [malli.core :as m]
            [open.atlas.common.contract :as version]))

(def ^:private id [:string {:min 1}])
(def ^:private attr [:fn qualified-keyword?])
(def ^:private types [:enum :string :integer :decimal :boolean :date :datetime :json :ref])
(def ^:private codes [:map-of [:or :string :int :boolean]
                      [:map {:closed true} [:label {:optional true} :string] [:description {:optional true} :string]]])

(def ^:private format-enum [:enum :id :code :currency :percent :number :date :datetime :text])

(def vocabulary-schema-v2
  "The one vocabulary schema: Semantic contract 2.0 §2 (task book
   «contract-upgrade-and-retirement» PR1, owner 2026-09-19). Standalone -- it
   no longer walks the retired 1.0-1.2 schemas it used to be derived from.
   2.0 §6.1 (doc 109 §109.8 item 3, owner 2026-09-18): a `:ref` attribute may
   name SEVERAL target Entities with `:attribute/targets` (a set of at least
   two), exclusive with `:attribute/target`."
  [:map {:closed true}
   [:entities [:map-of id
               [:map {:closed true}
                [:entity/label id]
                [:entity/description {:optional true} id]
                [:entity/kind {:optional true} [:enum :master :transaction :reference :event :bridge]]
                [:entity/synonyms {:optional true} [:vector :string]]
                [:entity/identity [:vector {:min 1} attr]]
                [:entity/attributes [:vector {:min 1} attr]]
                [:entity/notes {:optional true} :string]]]]
   [:attributes [:map-of attr
                 [:map {:closed true}
                  [:attribute/label id]
                  [:attribute/description {:optional true} id]
                  [:attribute/type types]
                  [:attribute/nullable? :boolean]
                  [:attribute/term {:optional true} id]
                  [:attribute/format {:optional true} format-enum]
                  [:attribute/unit {:optional true} id]
                  [:attribute/values {:optional true} codes]
                  [:attribute/synonyms {:optional true} [:vector :string]]
                  [:attribute/listed? {:optional true} :boolean]
                  [:attribute/deprecated? {:optional true} :boolean]
                  [:attribute/replaced-by {:optional true} attr]
                  [:attribute/cardinality {:optional true} [:enum :one :many]]
                  [:attribute/target {:optional true} id]
                  [:attribute/identity-scope {:optional true} id]
                  [:attribute/identity-component {:optional true} attr]
                  [:attribute/targets {:optional true} [:set {:min 2} id]]]]]
   [:terms {:optional true} [:map-of id
                            [:map {:closed true}
                             [:term/label id]
                             [:term/description id]
                             [:term/synonyms {:optional true} [:vector :string]]
                             [:term/values {:optional true} codes]
                             [:term/format {:optional true} format-enum]]]]
   [:units {:optional true} [:map-of id [:map {:closed true} [:unit/label id] [:unit/description {:optional true} id]]]]
   [:aliases {:optional true} [:vector
                              [:map {:closed true}
                               [:alias/from attr]
                               [:alias/to attr]
                               [:alias/direction [:= :one-way]]
                               [:alias/description {:optional true} :string]]]]
   [:equivalences {:optional true} [:vector
                                   [:map {:closed true}
                                    [:equivalence/attributes [:vector {:min 2} attr]]
                                    [:equivalence/scope id]
                                    [:equivalence/direction [:= :two-way]]
                                    [:equivalence/description {:optional true} :string]]]]
   [:references {:optional true} [:vector
                                 [:map {:closed true}
                                  [:reference/from attr] [:reference/to attr]
                                  [:reference/direction [:enum :one-way :two-way]]
                                  [:reference/on-null [:= :omit]]
                                  [:reference/description {:optional true} :string]]]]])

(defn- finding [code path] {:code code :severity :error :path path})
(defn- duplicate? [xs] (not= (count xs) (count (distinct xs))))
(defn- signature [a]
  [(:attribute/type a) (get a :attribute/cardinality :one)
   (or (:attribute/target a) (:attribute/targets a)) (:attribute/nullable? a)])

(defn- relationship-findings
  "Contract 2.0 §6.1 on one attribute: a ref names exactly one of `:target` /
   `:targets`, every named Entity exists, and a scalar names neither (the 1.x
   rule, unchanged). A union target's identity must be exactly one attribute --
   it is what the executor discriminates a record's branch on (§6.4), and two
   identity attributes would leave «which one decides» unanswered."
  [entities a definition]
  (let [ref? (= :ref (:attribute/type definition))
        target (:attribute/target definition)
        targets (:attribute/targets definition)]
    (concat
     (when (if ref?
             (or (and target targets) (and (nil? target) (nil? targets))
                 (and target (not (contains? entities target)))
                 (and targets (not-every? #(contains? entities %) targets)))
             (or target targets (contains? definition :attribute/cardinality)))
       [(finding :semantic/relationship [:attributes a :attribute/target])])
     (when ref?
       (for [e (sort targets)
             :when (and (contains? entities e)
                        (not= 1 (count (get-in entities [e :entity/identity]))))]
         (finding :semantic/union-identity [:attributes a :attribute/targets e]))))))

(defn validate-vocabulary
  "Validate a complete vocabulary closure. References must be resolved by the
   caller before validation. Returns unchanged :vocabulary only on success.
   Findings never echo attribute values, descriptions or supplied credentials."
  ([vocabulary] (validate-vocabulary vocabulary {}))
  ([{:keys [entities attributes aliases equivalences] :as vocabulary}
    {:keys [allow-unresolved-terms?] :as options}]
  (if-let [explanation (if (and (contains? options :contract) (version/retired? (:contract options)))
                         ;; A caller naming a retired descriptor is refused, not read as 2.0
                         ;; (task book PR1 red line: no silent 2.0 reading of an old document).
                         ::retired
                         (m/explain vocabulary-schema-v2 vocabulary))]
    {:findings (if (= ::retired explanation)
                 [version/version-retired]
                 (mapv #(finding :semantic/schema (:in %)) (:errors explanation)))}
    (let [errors
          (concat
           (mapcat
            (fn [[eid entity]]
              (let [members (:entity/attributes entity)
                    identity (:entity/identity entity)]
                (concat
                 (for [k [:entity/attributes :entity/identity]
                       :when (duplicate? (get entity k))]
                   (finding :semantic/duplicate [:entities eid k]))
                 (for [[i a] (map-indexed vector members) :when (not (contains? attributes a))]
                   (finding :semantic/reference [:entities eid :entity/attributes i]))
                 (for [[i a] (map-indexed vector identity)
                       :when (or (not (some #{a} members))
                                 (not (contains? attributes a))
                                 (not= false (get-in attributes [a :attribute/nullable?]))
                                 (= :ref (get-in attributes [a :attribute/type])))]
                   (finding :semantic/identity [:entities eid :entity/identity i])))))
            entities)
           (for [[a _] attributes
                 :let [owners (keep (fn [[eid e]]
                                      (when (some #{a} (:entity/attributes e)) eid)) entities)]
                 :when (not= [(namespace a)] (vec owners))]
             (finding :semantic/ownership [:attributes a]))
           (mapcat
            (fn [[a definition]]
              (let [ref? (= :ref (:attribute/type definition))
                    scope (:attribute/identity-scope definition)
                    component (:attribute/identity-component definition)]
                (concat
                 (when (and (not allow-unresolved-terms?)
                            (contains? definition :attribute/term)
                            (not (contains? (:terms vocabulary) (:attribute/term definition))))
                   [(finding :semantic/reference [:attributes a :attribute/term])])
                 (when (and (:attribute/unit definition)
                            (not (contains? (:units vocabulary) (:attribute/unit definition))))
                   [(finding :semantic/reference [:attributes a :attribute/unit])])
                 (when (and (:attribute/replaced-by definition)
                            (not (contains? attributes (:attribute/replaced-by definition))))
                   [(finding :semantic/reference [:attributes a :attribute/replaced-by])])
                 (when (and (:attribute/format definition)
                            (not (contains? (get {:id #{:string :integer} :code #{:string :integer :boolean}
                                                  :currency #{:decimal} :percent #{:decimal} :number #{:integer :decimal}
                                                  :date #{:date} :datetime #{:datetime} :text #{:string}}
                                                 (:attribute/format definition)) (:attribute/type definition))))
                   [(finding :semantic/format-type [:attributes a :attribute/format])])
                 (when (and ref? (some #(contains? definition %) [:attribute/term :attribute/unit :attribute/values :attribute/format :attribute/listed?]))
                   [(finding :semantic/relationship-metadata [:attributes a])])
                 (when (and (:attribute/values definition)
                            (not-every? (case (:attribute/type definition) :integer integer? :string string? :boolean boolean? (constantly false))
                                        (keys (:attribute/values definition))))
                   [(finding :semantic/value-type [:attributes a :attribute/values])])
                 (relationship-findings entities a definition)
                 (when (and scope (or ref? (not (contains? entities scope))
                                      (:attribute/nullable? definition)))
                   [(finding :semantic/identity-scope [:attributes a :attribute/identity-scope])])
                 (when (and (or scope component)
                            (or (nil? scope) (nil? component)
                                (not (some #{component} (get-in entities [scope :entity/identity])))
                                (and (some #{a} (get-in entities [scope :entity/identity]))
                                     (not= a component))
                                (not= (signature definition) (signature (attributes component)))))
                   [(finding :semantic/identity-component [:attributes a :attribute/identity-component])]))))
            attributes)
           (mapcat
            (fn [[i alias]]
              (let [from (:alias/from alias) to (:alias/to alias)]
                (cond
                  (or (not (contains? attributes from)) (not (contains? attributes to)))
                  [(finding :semantic/reference [:aliases i])]
                  (= from to) [(finding :semantic/self-alias [:aliases i])]
                  (not= (signature (attributes from)) (signature (attributes to)))
                  [(finding :semantic/alias-type [:aliases i])]
                  :else [])))
            (map-indexed vector aliases))
           (mapcat
            (fn [[i r]]
              (let [from (:reference/from r) to (:reference/to r)
                    f (attributes from) t (attributes to)]
                (when (or (= from to) (nil? f) (nil? t)
                          (= :ref (:attribute/type f))
                          (not= (butlast (signature f)) (butlast (signature t)))
                          (not= true (:attribute/nullable? f))
                          (not= false (:attribute/nullable? t))
                          (not= [to] (get-in entities [(namespace to) :entity/identity])))
                  [(finding :semantic/conditional-reference [:references i])])))
            (map-indexed vector (:references vocabulary)))
           (mapcat
            (fn [[i equivalence]]
              (let [members (:equivalence/attributes equivalence)
                    scope (:equivalence/scope equivalence)]
                (concat
                 (when (duplicate? members) [(finding :semantic/duplicate [:equivalences i])])
                 (when (or (some #(nil? (:attribute/identity-component (attributes %))) members)
                           (not (apply = (map #(:attribute/identity-component (attributes %)) members))))
                   [(finding :semantic/identity-component [:equivalences i])])
                 (when (or (not (contains? entities scope))
                           (some #(not (contains? attributes %)) members)
                           (some #(not= scope (:attribute/identity-scope (attributes %))) members))
                   [(finding :semantic/identity-scope [:equivalences i])])
                 (when (and (every? #(contains? attributes %) members)
                            (not (apply = (map #(signature (attributes %)) members))))
                   [(finding :semantic/equivalence-type [:equivalences i])]))))
            (map-indexed vector equivalences)))
          findings (vec (sort-by (juxt (comp pr-str :path) :code) errors))]
      (if (seq findings) {:findings findings}
          {:vocabulary vocabulary :findings []})))))
