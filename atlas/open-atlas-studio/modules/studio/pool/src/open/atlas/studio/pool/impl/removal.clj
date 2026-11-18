(ns open.atlas.studio.pool.impl.removal
  "Taking a namespace back out.

   There is no rename in the pool, and deliberately so: renaming a namespace is
   renaming every attribute in it, every resolver that names one, and every
   link that ends in one — an operation whose failure mode is a half-renamed
   model that still reads as a whole one. What there is instead is **remove and
   generate again**, which is cheap precisely because the generator exists.

   Removing is not a loop over deletes. The pool refuses to delete an object
   something still refers to (`impl.action` step 4), so the order is forced by
   the reference graph, and a caller working it out by hand gets it wrong on
   the first table with a link.

   ## The blast radius is stated, never guessed at

   Deleting a namespace necessarily takes with it **every object that names
   anything inside it** — including resolvers and links that live outside and
   reach in. That is not an over-reach: a resolver whose `:output` includes a
   deleted attribute is not a smaller resolver afterwards, it is a broken one.
   So the plan enumerates all of it up front, cross-namespace casualties
   included, and the owner rules on the whole list rather than discovering the
   second half after the first is gone."
  (:require [clojure.string :as str]
            [open.atlas.studio.pool.impl.model :as model]
            [open.atlas.studio.pool.impl.schema :as schema]
            [open.atlas.studio.pool.impl.store :as store]))

(defn within?
  "Whether `ns-str` is `prefix` itself or lives under it.

   Matched on segment boundaries, the same rule a graph selector uses: `cis.dv`
   must not swallow `cis.dvd`, or removing one namespace would quietly take a
   neighbour with it."
  [prefix ns-str]
  (let [p (str prefix) n (str ns-str)]
    (or (= p n) (str/starts-with? n (str p ".")))))

(defn- core-member?
  "Objects that *are* the namespace: it, its attributes (join ones included —
   D2.29 put them in the same registry), its metrics. Everything else reaches it
   by reference and is handled as such."
  [prefix {:pool/keys [type id entry]}]
  (case type
    :namespace  (within? prefix id)
    :attribute  (within? prefix (namespace id))
    :metric     (within? prefix (str (:entity entry)))
    false))

(defn- object-key [o] [(:pool/type o) (:pool/id o)])

(defn- plan-from
  "Closure, order and actions for a seed set of object keys.

   Shared by the prefix plan and the explicit-selection plan: only how `core`
   is chosen differs between them, and everything after it — what must go
   because it names a member, what order the pool will accept, what the actions
   are — is the same question. Two copies of a reference closure would answer
   it differently on the first reference shape the dialect grows."
  [db core]
  (let [objects (store/all-objects db)
        ;; Anything that names a member has to go too — the pool would refuse
        ;; the delete otherwise, and what it would leave behind is worse than
        ;; the refusal: an object whose inputs no longer exist.
        doomed  (loop [acc core]
                  (let [next (into acc
                                   (comp (remove #(contains? acc (object-key %)))
                                         (filter (fn [o]
                                                   (some #(contains? acc %)
                                                         (concat
                                                          (map (fn [a] [:attribute a])
                                                               (model/attr-refs o))
                                                          (model/string-refs o)))))
                                         (map object-key))
                                   objects)]
                    (if (= next acc) acc (recur next))))
        doomed-objects (filterv #(contains? doomed (object-key %)) objects)
        ;; Delete what nothing else doomed still refers to, repeatedly. Derived
        ;; from the reference graph rather than a hardcoded type order, so a
        ;; new reference in the dialect cannot leave this quietly wrong.
        ordered (loop [remaining doomed-objects out []]
                  (if (empty? remaining)
                    out
                    (let [keys* (into #{} (map object-key) remaining)
                          free  (filterv
                                 (fn [o]
                                   (empty? (filter #(contains? keys* %)
                                                   (model/referrers remaining
                                                                    (:pool/type o)
                                                                    (:pool/id o)))))
                                 remaining)
                          ;; A cycle would leave nothing free. It cannot arise
                          ;; from the dialect's reference shapes, but if it ever
                          ;; did, taking the rest in one lump is a better answer
                          ;; than looping forever.
                          free  (if (seq free) free remaining)]
                      ;; Compared by key, not by identity: `remaining` holds
                      ;; objects and `free` holds objects, but a set of *keys*
                      ;; used as a predicate on an object answers nil for every
                      ;; one of them — nothing is removed and the loop never
                      ;; ends.
                      (let [freed (into #{} (map object-key) free)]
                        (recur (remove #(contains? freed (object-key %)) remaining)
                               (into out free))))))]
    {:objects   (mapv object-key ordered)
     :reaching-in (vec (sort-by pr-str
                                (into [] (comp (remove #(contains? core (object-key %)))
                                               (map object-key))
                                      ordered)))
     :actions   (mapv (fn [o]
                        {:action :delete
                         :type (:pool/type o)
                         :id (:pool/id o)
                         :expected_revision (:pool/revision o)})
                      ordered)}))

(defn plan
  "What removing `prefix` would delete, in an order the pool accepts.

   → `{:prefix … :objects [[type id]…] :actions [action…] :reaching-in [[type id]…]}`

   `:objects` is everything that goes, ordered. `:reaching-in` names the ones
   that are **not** in the namespace but had to go anyway because they refer
   into it — the part of the answer a caller must see before agreeing to it.

   Empty `:objects` means the prefix holds nothing; that is an answer, not a
   failure."
  [db prefix]
  (let [core (into #{} (comp (filter #(core-member? prefix %)) (map object-key))
                   (store/all-objects db))]
    (assoc (plan-from db core) :prefix prefix)))

(defn plan-objects
  "The same plan, seeded by an explicit `[[type id] …]` selection rather than a
   prefix. For clearing several objects at once when they do not share one.

   **The caller names what to remove, never how.** It hands over identities and
   gets back the actions this function derived; a caller that could hand over
   its own action list would be `execute-actions` under a friendlier name, and
   the model could be taken apart in ways nothing checked.

   Identities the pool does not hold are reported in `:unknown` rather than
   ignored: a selection that silently shrinks would have the caller agreeing to
   a plan for something other than what it picked."
  [db pairs]
  (let [held (into #{} (map object-key) (store/all-objects db))
        want (into #{} (map vec) pairs)]
    (assoc (plan-from db (into #{} (filter held) want))
           :selection (vec (sort-by pr-str want))
           :unknown (vec (sort-by pr-str (remove held want))))))

;; ---------------------------------------------------------------------------
;; Removing one object
;; ---------------------------------------------------------------------------

(defn- produced-by
  "The attributes a resolver's `:output` produces — leaves and branches alike.
   Since D2.29 a branch key is an ordinary registered attribute (a join one),
   so it is produced by that resolver like any other."
  [entry]
  (into #{} (filter keyword?) (tree-seq coll? seq (:output entry))))

(declare judge-removal)

(defn object-removal
  "What removing one object would do, and the actions that would do it.

   Four rules, and they are deliberately not the same:

   - **An attribute that anything still names is refused**, with the list.
     Rewriting someone's resolver to stop mentioning an attribute — quietly,
     as a side effect of deleting that attribute — edits an object its author
     did not touch. Deleting is the caller's to ask for; editing someone
     else's resolver is theirs.

   - **A resolver takes with it the attributes nothing else produces.** Those
     are not survivors, they are drift: an attribute in the registry that no
     `:output` names is `:cut.attribute/unregistered-use` and stops the graph
     being cut. Cleaning them up is finishing the delete, not extending it.
     One that is *still referenced* by something else is left in place and
     **named** — taking it would break that reference, and following the
     reference further is how one click deletes half a model.

   - **A link has no dependants**: it is its endpoint pair (D2.3) and nothing
     refers to it. The plain delete it appears to be.

   - **Everything else takes the attribute's rule** — a source, a namespace, a
     term, a unit, a metric, a mutation is refused while anything names it,
     with the list. Silence here was a lying plan: a source in use planned as
     one clean action and was refused by the pool at execution.

   → `{:target [type id] :actions […] :cascade [[…]…] :orphaned [[…]…]
       :blocked-by [[type id]…]}`

   `:blocked-by` non-empty means nothing may be removed; `:actions` is empty
   and the caller has the names to go and deal with. Reads only.

   **`also-removed` is the rest of the same batch** (doc 106 §106.12 item 8.4,
   measured on the v10 rebuild). One call removing a resolver AND the
   attributes only it produces used to need three rounds: every object was
   judged against the pool as it stands, so the attributes were «still named»
   by a resolver the very same call was deleting. The references that decide
   `:blocked-by` and `:cascade` are therefore read from the pool MINUS the
   batch — the state the transaction actually leaves behind. It is not a
   cascade option on the caller's behalf (D3.19 stands): nothing is removed
   that the caller did not name."
  ([db type id] (object-removal db type id #{}))
  ([db type id also-removed]
   (judge-removal (store/all-objects db) type id also-removed)))

(defn object-removals
  "`object-removal` for several `[type id]` targets judged against the SAME
   batch, reading the pool once. A Source removal judges ~80 feeders and ~200
   attributes; one full pool read and decode per judgement was what made that
   plan take minutes (owner 2026-10-04). Same answers, in the order asked."
  [db pairs also-removed]
  (let [all (store/all-objects db)
        gone (set also-removed)]
    (mapv (fn [[t id]] (judge-removal all t id gone)) pairs)))

(defn- judge-removal
  [all type id also-removed]
  (let [gone-set (set also-removed)
        gone?   (fn [o] (contains? gone-set (object-key o)))
        ;; What still exists once this batch is applied. The TARGET is judged
        ;; against it too — its own presence in the batch is what makes the
        ;; batch one act rather than n.
        objects (vec (remove gone? all))
        target  (first (filter #(= [type id] (object-key %)) all))
        del     (fn [o] {:action :delete :type (:pool/type o) :id (:pool/id o)
                         :expected_revision (:pool/revision o)})]
    (cond
      (nil? target)
      {:target [type id] :actions [] :cascade [] :orphaned [] :blocked-by []}

      (= :attribute type)
      (let [refs (model/referrers objects type id)]
        (if (seq refs)
          {:target [type id] :actions [] :cascade [] :orphaned []
           :blocked-by (vec refs)}
          {:target [type id] :actions [(del target)]
           :cascade [] :orphaned [] :blocked-by []}))

      (= :resolver type)
      (let [mine      (produced-by (:pool/entry target))
            ;; Produced by anything *else* — those survive untouched.
            elsewhere (into #{}
                            (comp (filter #(and (= :resolver (:pool/type %))
                                                (not= id (:pool/id %))))
                                  (mapcat #(produced-by (:pool/entry %))))
                            objects)
            orphans   (remove elsewhere mine)
            ;; What still names an orphan, once this resolver is out of the way.
            others    (remove #(= [type id] (object-key %)) objects)
            {:keys [free held]} (group-by (fn [a]
                                            (if (seq (model/referrers others :attribute a))
                                              :held :free))
                                          orphans)
            ;; Looked up in `all`, not in what survives the batch: an
            ;; attribute the caller ALSO named is still to be deleted, and
            ;; emitting it here is what keeps the resolver-before-attribute
            ;; order the pool insists on (the batch de-duplicates).
            attr-obj  (let [idx (into {} (comp (filter #(= :attribute (:pool/type %)))
                                               (map (juxt :pool/id identity)))
                                      all)]
                        idx)]
        {:target   [type id]
         ;; The resolver first: it is what names the orphans, so every delete
         ;; after it is one the pool will accept.
         :actions  (into [(del target)] (keep #(some-> (attr-obj %) del)) (sort-by pr-str free))
         :cascade  (mapv (fn [a] [:attribute a]) (sort-by pr-str free))
         :orphaned (mapv (fn [a] [:attribute a]) (sort-by pr-str held))
         :blocked-by []})

      ;; Everything else — a source, a namespace, a term, a unit, a metric, a
      ;; mutation — takes the attribute's rule: refused while anything names
      ;; it, with the list.
      ;;
      ;; This branch used to be a bare delete with an empty `:blocked-by`, so
      ;; the plan for a source in use said "one action, nothing in the way" and
      ;; the pool then refused the execution with `:pool.reference/inbound`. A
      ;; plan that cannot run is worse than a refusal: the refusal at least
      ;; arrives before the caller has agreed to anything.
      :else
      (let [refs (model/referrers objects type id)]
        (if (seq refs)
          {:target [type id] :actions [] :cascade [] :orphaned []
           :blocked-by (vec refs)}
          {:target [type id] :actions [(del target)]
           :cascade [] :orphaned [] :blocked-by []})))))
