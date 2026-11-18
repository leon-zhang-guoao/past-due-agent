(ns open.atlas.common.connector.api.impl.rows
  "Response → rows. Pure functions turning a parsed upstream response into
   flat row maps ({attr-kw → value}).

   The join shaping the migrated plugin did in-connector — wrapping list
   rows as {join-parent rows} — is deliberately GONE: the platform ruling
   is 'connectors hand over rows; the :output syntax tree belongs to the
   engine'. compile-source returns single-map|nil or [map ...]; the engine
   does any join wrapping."
  (:require [clojure.string :as str]
            [open.atlas.common.connector.api.impl.interp :as interp]
            [open.atlas.common.connector.api.impl.jsonpath :as jsonpath]
            [open.atlas.common.connector.spi :as spi]))

(declare branch-rows)

(defn- ->attr [a] (if (keyword? a) a (interp/edn-attr->kw a)))

(defn locate-rows
  "Apply api-v1's ':data_path' (row-locating path, uniform across batch and
   non-batch — the contract has no ':items-path') to a response. Without a
   :data_path the response itself is the data. Returns the located value
   as-is; row-vs-single interpretation is the caller's."
  [response data-path]
  (if (and data-path (some? response))
    (jsonpath/extract response data-path)
    response))

(defn- item-value
  "One item-relative JSONPath against one item: the leading `$[*].`/`$[*]`/`$.`
   marker is stripped, an empty remainder means the item itself.

   A remainder that still carries an array marker goes through the full
   extractor: `$.tags[*]` on a record is a SCALAR ARRAY, and a scalar array is
   an attribute value like any other (doc 106 §106.14 item 15) — collections
   are said with `:_from`, never inferred from a `[*]` in a leaf path."
  [item jsonpath]
  (let [single-path (-> (str jsonpath)
                        (str/replace #"^\$\[\*\]\." "")
                        (str/replace #"^\$\[\*\]" "")
                        (str/replace #"^\$\." ""))]
    (cond
      (not (seq single-path))          item
      (str/includes? single-path "[")  (jsonpath/extract item (str "$." single-path))
      :else                            (jsonpath/get-in-path item single-path))))

(def from-key
  "The key a branch names its own array under (api-v1 «记录内集合», doc 106
   §106.14 item 15). It is not an attribute kw and cannot collide with one:
   every attribute in a `:field_mapping` is namespace-qualified."
  :_from)

(defn collection-branch?
  "Is this `:field_mapping` value a branch that carries its OWN rows?"
  [v]
  (and (map? v) (contains? v from-key)))

(defn array-path
  "A branch's `:_from` with its trailing `[*]` removed — the path of the array
   ITSELF, so `nil` (no such array) stays distinguishable from `[]` (an empty
   one). The extractor answers `[]` for both, and the two mean opposite things:
   «this record has no such collection» vs «this record has none of them»."
  [from]
  (str/replace (str from) #"\[\*\]$" ""))

(defn map-api-item
  "Map one raw upstream item to attrs via field_mapping. field_mapping
   values are item-relative here: a leading `$[*].`/`$[*]`/`$.` marker is
   stripped.

   A mapping value that is itself a MAP is a BRANCH-SCOPED mapping (doc 106
   §106.14 item 6): its key is a join attribute and the map is that branch's
   own `{attr → path}`. It answers an already-shaped nested row, which the
   engine's `nest-row` leaves alone — the only way a self relation
   (`{A/parent [A/k]}` beside a top-level `A/k`) can carry two values for one
   attribute kw."
  [field-mapping item]
  (reduce-kv
   (fn [acc attr jsonpath]
     (assoc acc (->attr attr)
            (cond
              (collection-branch? jsonpath) (branch-rows item jsonpath)
              (map? jsonpath)
              (let [vs (reduce-kv (fn [a a* p] (assoc a (->attr a*) (item-value item p)))
                                  {} jsonpath)]
                (when (some some? (vals vs)) vs))
              :else (item-value item jsonpath))))
   {} field-mapping))

(defn branch-rows
  "One IN-RECORD collection branch against one record (doc 106 §106.14 item 15,
   owner 2026-09-07): `{B/as {:_from \"$.processors[*]\" A/id \"$.id\"}}` —
   the branch names the array, and every other key of the map is a leaf path
   RELATIVE to one element. A leaf that is itself a `:_from` map recurses, so
   nesting has no depth limit.

   Three answers, and they are three different facts:
   absent / nil array → `nil` (this record has no such collection);
   empty array → `[]` (it has none of them; never nil, or «none» would read
   back as «no such thing»); rows → a vector of already-assembled maps, which
   the engine's `nest-row` leaves alone exactly as it leaves a branch-scoped
   answer (item 6 — same mechanism, one rule)."
  [item branch]
  (let [arr (item-value item (array-path (get branch from-key)))
        leaves (dissoc branch from-key)]
    (when (sequential? arr)
      (mapv #(map-api-item leaves %) arr))))

(defn collection-branch-attributes
  "Every join attribute of a `:field_mapping` whose rows come from INSIDE the
   record, at any depth (doc 106 §106.14 item 17). The engine reads this
   through the SPI to decide which branches carry the parent's identity into
   their child rows; an endpoint-backed branch is not in this set, so it is
   unaffected."
  [field-mapping]
  (reduce-kv
   (fn [acc attr v]
     (if (collection-branch? v)
       (into (conj acc (->attr attr))
             (collection-branch-attributes (dissoc v from-key)))
       acc))
   #{} (or field-mapping {})))

(defn in-record-collection?
  "Does this `:field_mapping` hang a collection inside ONE record (§106.14
   item 15)? Such a response is a single record whose rows sit in a branch of
   it, not a list — so the read takes the single view even under
   `:result_type \"list\"`, and the engine lifts the rows out of the join root
   it already knows how to lift them out of (D2.16)."
  [field-mapping]
  (boolean (some collection-branch? (vals (or field-mapping {})))))

(defn map-join-from-jsonpaths
  "Zip per-field JSONPath extractions (each an index-aligned column vector)
   into a seq of row maps — the flat-row view of a list response."
  [response-data field-mapping]
  (let [cols (mapv (fn [[attr jsonpath]]
                     {:attr (->attr attr)
                      :vals (vec (or (jsonpath/extract response-data (str jsonpath)) []))})
                   field-mapping)
        n (apply max 0 (map #(count (:vals %)) cols))]
    (mapv (fn [i]
            (into {} (keep (fn [{:keys [attr vals]}]
                             (when-let [v (nth vals i nil)]
                               [attr v]))
                           cols)))
          (range n))))

(defn batch-field-mapping
  "Rewrite single-item JSONPaths ([0]) to list paths ([*]) for batch
   responses."
  [field-mapping]
  (update-vals field-mapping
               (fn [v] (if (map? v) v (str/replace (str v) #"\[0\]" "[*]")))))

(defn stamp-input-on-list-rows
  "Merge the resolver :input params onto each list row so parent identity
   keys are present on child rows for cross-domain aliases. The row's own
   values win on conflict. Row-level logic, so it stays in the connector."
  [rows params input-attrs]
  (if (and (seq rows) (seq params) (seq input-attrs))
    (let [input-kws (mapv ->attr input-attrs)]
      (mapv #(merge (select-keys params input-kws) %) rows))
    rows))

(defn list-mapping?
  "Whether any field_mapping path is a [*] list path — selects the
   column-zip row view over the per-item view."
  [field-mapping]
  (boolean (some #(and (not (map? (val %))) (str/includes? (str (val %)) "[*]"))
                 field-mapping)))

(defn response->rows
  "A list response → vector of flat row maps, honoring :data_path. List
   paths in field_mapping go through the column-zip (handles nested [*]
   flattening); item-relative mappings map per located item."
  [response {:keys [data-path field-mapping]}]
  (let [located (locate-rows response data-path)]
    (cond
      (nil? located) []
      (list-mapping? field-mapping)
      (map-join-from-jsonpaths located field-mapping)
      :else
      (let [items (if (sequential? located) located [located])]
        (mapv #(map-api-item field-mapping %) items)))))

(defn response->single
  "A single response → one row map or nil, honoring :data_path.
   nil response/row = 'this resolver cannot supply these attributes'."
  [response {:keys [data-path field-mapping]}]
  (let [located (locate-rows response data-path)]
    (cond
      (nil? located) nil
      (sequential? located)
      ;; :result_type \"single\" is OBEYED even over a list-shaped answer
      ;; (api-v1 conformance / D2.19: the declaration is honored, not the
      ;; reference implementation's habit of ignoring it).
      (when-let [item (first located)]
        (map-api-item field-mapping item))
      :else
      ;; Absolute field-mapping paths against the located value.
      (not-empty
       (reduce-kv (fn [acc attr jsonpath]
                    (assoc acc (->attr attr)
                           (cond
                             ;; An in-record collection (§106.14 item 15): the
                             ;; located value IS the host record, and the
                             ;; branch reads its array out of it.
                             (collection-branch? jsonpath)
                             (branch-rows located jsonpath)
                             (map? jsonpath)
                             ;; Branch-scoped (§106.14 item 6): each leaf read
                             ;; against the same located value.
                             (let [vs (reduce-kv
                                       (fn [a a* p]
                                         (assoc a (->attr a*) (jsonpath/extract located (str p))))
                                       {} jsonpath)]
                               (when (some some? (vals vs)) vs))
                             :else (jsonpath/extract located (str jsonpath)))))
                  {} field-mapping)))))

;; ---------------------------------------------------------------------------
;; Batch (shape 1: id-list-in-request) grouping
;; ---------------------------------------------------------------------------

(defn group-api-items
  "Group a flat seq of upstream `items` by their `:child-fk` field and
   return a vector aligned to `inputs`: the i-th element holds the items
   whose child-fk equals the i-th input's `:parent-key` value (empty when
   none). The upstream MUST echo the id back on each row. `:child-fk` may
   be a DOTTED path into the item for an upstream that nests the echoed id."
  [items inputs {:keys [parent-key child-fk]}]
  ;; Both may be VECTORS — a compound key, paired position by position (owner
  ;; 2026-09-07, doc 106 §106.14 item 14). One key stays one scalar bucket, so
  ;; the single-key grouping is unchanged.
  (let [parent-kws (mapv ->attr (spi/batch-keys parent-key))
        fks        (spi/batch-keys child-fk)
        key-of     (fn [f x] (let [vs (mapv #(f x %) fks)]
                               (if (= 1 (count vs)) (first vs) vs)))
        by-fk      (group-by #(key-of (fn [item p] (jsonpath/get-in-path item p)) %) items)]
    (mapv (fn [input]
            (let [k (let [vs (mapv #(get input %) parent-kws)]
                      (if (= 1 (count vs)) (first vs) vs))]
              (vec (get by-fk k []))))
          inputs)))

(defn chunked-ids
  "Distinct non-nil parent ids from inputs, split into chunks of
   `:chunk-size` (one request per chunk; a nil/absent chunk-size means one
   chunk). api-v1 batch block declares :chunk-size, so it is enforced here
   rather than accepted-and-ignored."
  [inputs {:keys [parent-key chunk-size]}]
  (let [parent-kws (mapv ->attr (spi/batch-keys parent-key))
        single?    (= 1 (count parent-kws))
        ids (->> inputs
                 (map (fn [in] (if single?
                                 (get in (first parent-kws))
                                 (mapv #(get in %) parent-kws))))
                 (remove #(if single? (nil? %) (every? nil? %)))
                 distinct vec)]
    (if (and (integer? chunk-size) (pos? chunk-size))
      (mapv vec (partition-all chunk-size ids))
      (if (seq ids) [ids] []))))

(defn interpolate-batch-body
  "Walk a json_body and inject the batch ids. A leaf that is EXACTLY the
   placeholder token becomes the ids ARRAY (real JSON list); any other
   {placeholder} occurrence is string-interpolated with the joined id-list."
  [node placeholder ids id-list]
  (cond
    (and (string? node) (= node (str "{" placeholder "}"))) ids
    (string? node)     (interp/interpolate-params node {placeholder id-list})
    (map? node)        (reduce-kv (fn [m k v]
                                    (assoc m k (interpolate-batch-body v placeholder ids id-list)))
                                  {} node)
    (sequential? node) (mapv #(interpolate-batch-body % placeholder ids id-list) node)
    :else node))
