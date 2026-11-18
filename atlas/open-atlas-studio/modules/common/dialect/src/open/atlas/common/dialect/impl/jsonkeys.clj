(ns open.atlas.common.dialect.impl.jsonkeys
  "JSON → EDN map-key coercion, driven by the contract's own schema
   (owner ruling 2026-09-07, doc 106 §106.14 item 9).

   ## The one thing this does

   JSON object keys can only be strings. A JSON-RPC client that sends
   `{\"headers\": {\"Accept\": \"application/json\"}}` reaches this layer with
   `{:Accept \"application/json\"}` after keywordisation, and api-v1 declares
   `:headers` as `[:map-of :string :string]` — so the value is refused with
   `:dialect.schema/invalid-shape` for a difference the client could not have
   expressed. The mirror case is `:field_mapping`, declared `[:map-of AttrKw …]`:
   there the string the client CAN send is the wrong half.

   Both are the same defect and get one mechanism: at every `[:map-of K V]`
   position the SCHEMA names, restate each key in the spelling `K` accepts.
   The positions are derived from the schema — nothing here lists `:headers`,
   `:query_params`, `:field_mapping` or `:values` by name, so a contract that
   grows another map-of position is covered the day it lands.

   ## What it deliberately does not do

   - **Keys only.** A value that is the wrong type is a modelling error, not
     an encoding one, and guessing at it would be the second schema
     CLAUDE.md forbids.
   - **No guessing.** A key is rewritten only when the rewritten form
     `m/validate`s against the declared key schema and the original does not.
     A key that already fits is untouched; one that fits neither is left as it
     came, so the contract — not this ns — writes the refusal.
   - **Ambiguity is left alone.** Under `:or` / `:multi` a branch is entered
     only when the value can belong to it and no sibling branch competes for
     the same value shape."
  (:require [malli.core :as m]))

(defn- candidates
  "The other spellings of `k` a JSON client could have meant. A JSON key is a
   string, so exactly two are reachable: the string a keyword came from, and
   the keyword a string names. `(str (symbol k))` rather than `(name k)` —
   a qualified keyword's namespace is part of its spelling."
  [k]
  (cond
    (keyword? k) [(str (symbol k))]
    (string? k)  [(keyword k)]
    :else        []))

(defn- coerce-key [kschema k]
  (if (m/validate kschema k)
    k
    (or (first (filter #(m/validate kschema %) (candidates k))) k)))

(declare coerce*)

(defn- map-of [schema value]
  (if-not (map? value)
    value
    (let [[kschema vschema] (m/children schema)]
      (into {} (map (fn [[k v]] [(coerce-key kschema k) (coerce* vschema v)])) value))))

(defn- entries-of
  "A `:map` schema's `key → child schema`."
  [schema]
  (into {} (map (fn [[k _ child]] [k child])) (m/children schema)))

(defn- branch-for
  "The single branch of an `:or` / `:and` / `:maybe` that could hold `value` —
   or nil when none or several could. Only map-shaped branches matter here;
   everything else has no keys to restate."
  [schema value]
  (let [maps (filter #(#{:map :map-of} (m/type (m/deref %))) (m/children schema))]
    (when (and (map? value) (= 1 (count maps)))
      (first maps))))

(defn- multi-branch
  "The `:multi` branch the dispatch value selects, or nil. `:multi` is how the
   contract spells source entries and operation blocks, so this is the branch
   that actually carries `:headers` / `:field_mapping`."
  [schema value]
  (let [dispatch (:dispatch (m/properties schema))
        d        (when (and dispatch (map? value)) (dispatch value))]
    (some (fn [[k _ child]] (when (= k d) child)) (m/children schema))))

(defn- coerce* [schema value]
  (let [s (m/deref (m/schema schema))]
    (case (m/type s)
      :map     (if (map? value)
                 (let [es (entries-of s)]
                   (reduce-kv (fn [m k v]
                                (assoc m k (if-let [child (get es k)] (coerce* child v) v)))
                              {} value))
                 value)
      :map-of  (map-of s value)
      :multi   (if-let [b (multi-branch s value)] (coerce* b value) value)
      (:or :and :maybe :orn)
      (if-let [b (branch-for s value)] (coerce* b value) value)
      (:vector :sequential :set)
      (if (sequential? value)
        (let [child (first (m/children s))] (mapv #(coerce* child %) value))
        value)
      value)))

(defn coerce
  "`schema × value → value` with every map key restated in the spelling the
   schema declares. Pure; returns the value untouched when nothing applies."
  [schema value]
  (coerce* schema value))
