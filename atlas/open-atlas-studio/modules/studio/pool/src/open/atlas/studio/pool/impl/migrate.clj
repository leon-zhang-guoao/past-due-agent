(ns open.atlas.studio.pool.impl.migrate
  "**The D2.29 pool migration**: a pool written before «a branch is an
   attribute» is repaired when it is opened, not refused (owner ruling
   2026-09-02).

   D2.29 merged the `:navigation` registry into `:attributes` and retired
   `:collection?`. A pool written before it still holds, on disk:

   - `:navigation` objects (`:pool.object/type :navigation`, id `[\"ns\" \"name\"]`,
     fields under `:pool.navigation/*`, hanging off `:pool.child/parent`);
   - `:pool.attribute/collection?` datoms.

   Neither is addressable any more. `store/all-objects` reads a navigation
   through a type table that no longer has that row and hands back an object
   with an empty entry, `objects->dialect` then drops it on the floor, and
   `import-pool!` refuses the type outright at the envelope. A model that is
   silently one third smaller than it was is the worst of the three outcomes,
   which is why this is a migration and not a version check.

   ## What it does, mirroring exactly what D2.29 did to `fixtures/demo/model.edn`

   | old shape | new shape |
   |---|---|
   | navigation + same-name `:collection? true` attribute | ONE join attribute: the navigation's `:target`/`:cardinality`, the attribute's own `:label`/`:description`/`:synonyms` where it had them, no `:collection?`, no `:type` |
   | navigation alone | a join attribute `ns/name` carrying the navigation's keys |
   | `:collection? true` alone | a join attribute onto **its own namespace**, `:cardinality :one-to-many` |

   Those three rows are the same three the demo model went through by hand:
   `all_cpos` was both a navigation and a collection attribute (row 1),
   `cpo_details` was a navigation only (row 2), and a collection attribute
   with no navigation is what `:cis.master.part/all_parts` would have been
   (row 3).

   ## Only the two retired keys are dropped

   `:collection?` and the scalar `:type`, and nothing else. A pre-D2.29
   collection attribute could legally carry `:format`, `:term`, `:unit` …,
   and a join attribute's key set is closed against all of them (dialect-v1
   §3). Rather than guess which of those the author meant to keep, the
   migration writes what D2.29 says and lets the dialect gate below refuse —
   **by name, naming the object and the key**. A finding someone can act on
   beats a key that disappeared without one (CLAUDE.md: 拒绝而非截断/猜测).

   ## All or nothing, and safe to re-enter

   One `d/transact`, so there is no half-migrated pool to come back to: a
   crash before it commits leaves the pre-D2.29 pool exactly as it was, and
   the next open runs the whole migration again. Detection is by the presence
   of the legacy datoms rather than by a marker, so «already migrated» and
   «migrated and then crashed» cannot be told apart — because they need not
   be.

   ## It is an auditable act

   Every object it touches gets a mutation record in the same transaction, as
   any other write does (audit ruling ③), with a system provenance
   (`audit/system-provenance`): no entrance and a machine actor. See that fn
   for why that shape is the honest one and why a caller cannot reach it."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [open.atlas.studio.pool.impl.action :as action]
            [open.atlas.studio.pool.impl.audit :as audit]
            [open.atlas.studio.pool.impl.finding :as f]
            [open.atlas.studio.pool.impl.model :as model]
            [open.atlas.studio.pool.impl.schema :as schema]
            [open.atlas.studio.pool.impl.store :as store]))

(def ^:const ruling
  "The ruling this migration carries out; it is what the audit records name."
  "D2.29")

(def ^:const stamp-ruling
  "The second migration's ruling (§ «Stamping the owner» below)."
  "D3.22")

(def ^:private collection-ident
  "The retired `:collection?` field's `:db/ident` (D2.29)."
  :pool.attribute/collection?)

(def ^:private legacy-cardinality
  "What a global collection's cardinality is, said once.

   Not invented here: `:collection? true` meant «the declared join parent of a
   list resolver», and the demo model's collection attributes all came out of
   D2.29 as `:one-to-many`."
  :one-to-many)

;; ---------------------------------------------------------------------------
;; Detection
;; ---------------------------------------------------------------------------

(defn- ident-installed?
  "Is this `:db/ident` known to the db at all?

   Asked before querying `:pool.attribute/collection?`: a pool created after
   D2.29 never installed that ident, and the schema flexibility is `:write`.
   `:pool.object/type` needs no such guard — it is in the current schema."
  [db ident]
  (some? (d/q '[:find ?e . :in $ ?i :where [?e :db/ident ?i]] db ident)))

;; Every object this migration reads is selected with a `[?e :pool.object/id]`
;; clause. A pool object's identity lives in that datom (`navigation-attribute`
;; and the collection branch both `decode-edn` it); a `:pool.object/type`
;; datom with **no** `:pool.object/id` beside it is a **half-written object** —
;; an interrupted write that never got its id, target, or cardinality. One such
;; navigation exists in a real pool (`[:navigation ["demo.ch.posts" "all_posts"]]`,
;; carrying only key/type/revision). It cannot become a valid join attribute —
;; a join needs a `:target`, which it never had — so converting it would make
;; the migration write a target-less attribute and then refuse itself at the
;; dialect gate, leaving the whole pool unopenable over one stub. Selecting on
;; `:pool.object/id` skips exactly that stub and nothing else: a real object
;; without an id is not a thing this migration can (or should) convert. This
;; only ever skips a genuinely partial object; it must NOT be relied on to
;; paper over a store whose index cannot read a real object's id — the honest
;; fix for that is to repair the store, not to drop the object here.
(defn needed?
  "Does this pool still hold pre-D2.29 shapes?

   Two `:find ?e .` queries, which stop at the first hit — the cost on an
   already-migrated pool (the overwhelmingly common case, since every open
   asks) is two misses, not a read of the pool. The `:pool.object/id` clause
   keeps a half-written stub (no id) from making an already-migrated pool look
   like it still needs migrating on every open."
  [db]
  (boolean
   (or (some? (d/q '[:find ?e . :where [?e :pool.object/type :navigation]
                                       [?e :pool.object/id]] db))
       (and (ident-installed? db collection-ident)
            (some? (d/q '[:find ?e . :in $ ?a :where [?e ?a ?v]
                                                      [?e :pool.object/id]] db collection-ident))))))

(defn- legacy-navigations [db]
  (vec (sort-by :pool.object/key
                (d/q '[:find [(pull ?e [*]) ...]
                       :where [?e :pool.object/type :navigation]
                              [?e :pool.object/id]] db))))

(defn- legacy-collections [db]
  (if (ident-installed? db collection-ident)
    (vec (sort-by :pool.object/key
                  (d/q '[:find [(pull ?e [*]) ...] :in $ ?a
                         :where [?e ?a ?v]
                                [?e :pool.object/id]] db collection-ident)))
    []))

;; ---------------------------------------------------------------------------
;; Old shapes → new entries
;; ---------------------------------------------------------------------------

(defn- navigation-entry
  "A raw navigation entity → the dialect keys it carried.

   Read field by field rather than through `schema/entity->object`: that fn
   goes through the type table, and the `:navigation` row is exactly what
   D2.29 deleted from it. Reading history needs the historical key list, and
   this is the one place that may hold it."
  [raw]
  (cond-> {}
    (:pool.navigation/target raw)      (assoc :target (:pool.navigation/target raw))
    (:pool.navigation/cardinality raw) (assoc :cardinality (:pool.navigation/cardinality raw))
    (:pool.navigation/label raw)       (assoc :label (:pool.navigation/label raw))
    (:pool.navigation/description raw) (assoc :description (:pool.navigation/description raw))
    (:pool.navigation/synonyms raw)    (assoc :synonyms (model/decode-edn
                                                         (:pool.navigation/synonyms raw)))))

(defn- navigation-attribute
  "A navigation's id `[\"ns\" \"name\"]` → the attribute kw it becomes.

   Same identity, said in the surviving registry's vocabulary: a navigation
   was addressed by namespace + local name, and that is what a qualified
   keyword is (D2.17)."
  [raw]
  (let [[ns-name local] (model/decode-edn (:pool.object/id raw))]
    (keyword ns-name local)))

(defn- merged-entry
  "Attribute entry + navigation entry → the one join entry they become.

   The join shape comes from the navigation (`:target`/`:cardinality` — the
   run semantics have no other source, D2.16); the description keys come from
   the attribute **when it has them**, because the attribute is where an
   author edits a label, and fall back to the navigation's otherwise. Neither
   side's prose is dropped."
  [attr-entry nav-entry]
  (reduce (fn [m k] (if (or (contains? m k) (nil? (get nav-entry k)))
                      m
                      (assoc m k (get nav-entry k))))
          (-> (dissoc attr-entry :type)
              (merge (select-keys nav-entry [:target :cardinality])))
          [:label :description :synonyms]))

(defn- collection-entry
  "A `:collection? true` attribute with no navigation → a join onto its own
   namespace (D2.29: 「一个『全局集合』就是 `:target` 指向自己 namespace 的 join
   属性」). `:collection?` itself is not in the entry — the current type table
   cannot read it — it is retracted as a datom below."
  [akw entry]
  (-> (dissoc entry :type)
      (assoc :target (namespace akw))
      (update :cardinality #(or % legacy-cardinality))))

;; ---------------------------------------------------------------------------
;; The plan
;; ---------------------------------------------------------------------------

(defn- attribute-write
  "Tx + audit entry for writing `entry` as the attribute `akw`.

   `cur` is the raw attribute entity when one already exists. Updating goes
   through `action/update-tx`, which retracts the fields the new value does
   not carry — that is what removes the `:pool.attribute/collection?` and
   `:pool.attribute/type` datoms, and it is the same fn `execute_action` uses
   so «gone means gone» cannot mean two things."
  [cur akw entry revision]
  (let [obj (cond-> {:pool/type :attribute :pool/id akw :pool/entry entry}
              ;; D3.22: `update-tx` retracts what the new value does not carry,
              ;; so a stamped attribute must be handed back its stamp. A
              ;; pre-D2.29 pool has none, and the stamp pass below fills it in.
              (:pool.object/workspace cur)
              (assoc :pool/workspace (:pool.object/workspace cur)))]
    {:tx    (if cur
              (action/update-tx (:db/id cur) cur obj revision)
              [(schema/object->tx obj revision)])
     :audit {:action (if cur :update :create) :type :attribute :id akw
             :revision-before (if cur (:pool.object/revision cur) 0)
             :revision-after revision}}))

(defn- plan
  "The whole migration as `{:tx […] :audit […] :summary {…}}`, read-only.

   Order is deterministic (navigations by object key, then the collection
   attributes no navigation claimed) so the audit records of a migration read
   the same way twice — the same discipline export byte-equality rests on."
  [db]
  (let [navs  (legacy-navigations db)
        colls (legacy-collections db)]
    (loop [[nav & more] navs
           claimed #{}
           tx      []
           records []
           summary {:merged 0 :navigations 0 :collections 0 :cleared 0}]
      (if nav
        (let [akw   (navigation-attribute nav)
              cur   (store/find-object db (model/object-key :attribute akw))
              ;; A same-id attribute is **the** counterpart whether or not it
              ;; carried `:collection?`: post-D2.29 one registry holds one
              ;; entry per name, so there is no shape in which both survive.
              entry (merged-entry (if cur (:pool/entry (schema/entity->object cur)) {})
                                  (navigation-entry nav))
              ;; A navigation with no attribute counterpart keeps its own
              ;; revision: the object did not restart its history, it moved
              ;; registry. With a counterpart, the attribute moves on by one
              ;; as any edit of it would.
              rev   (if cur (inc (:pool.object/revision cur)) (:pool.object/revision nav))
              w     (attribute-write cur akw entry rev)]
          (recur more
                 (conj claimed akw)
                 (into tx (conj (vec (:tx w)) [:db/retractEntity (:db/id nav)]))
                 (conj records
                       (:audit w)
                       {:action :delete :type :navigation
                        :id (model/decode-edn (:pool.object/id nav))
                        :revision-before (:pool.object/revision nav)
                        :revision-after nil})
                 (update summary (if cur :merged :navigations) inc)))
        (let [left (remove #(claimed (model/decode-edn (:pool.object/id %))) colls)
              ws   (for [c left
                         :let [akw   (model/decode-edn (:pool.object/id c))
                               entry (:pool/entry (schema/entity->object c))]]
                     ;; `:collection? false` said nothing — it was the default
                     ;; written out. Such an attribute is a scalar one with a
                     ;; retired key on it, so only the datom goes; turning it
                     ;; into a join would invent a branch its author never
                     ;; wrote. The entry is handed back unchanged and
                     ;; `update-tx` retracts the field the new value lacks.
                     (assoc (attribute-write c akw
                                             (if (get c collection-ident)
                                               (collection-entry akw entry)
                                               entry)
                                             (inc (:pool.object/revision c)))
                            :collection? (boolean (get c collection-ident))))]
          {:tx      (into tx (mapcat :tx ws))
           :audit   (into records (map :audit ws))
           :summary (assoc summary
                           :collections (count (filter :collection? ws))
                           :cleared     (count (remove :collection? ws)))})))))

;; ---------------------------------------------------------------------------
;; Running it
;; ---------------------------------------------------------------------------

(defn migrate-d229!
  "Bring one pool up to D2.29. Idempotent; a no-op on a pool that has nothing
   pre-D2.29 in it.

   → `{:migrated? bool :summary {:merged n :navigations n :collections n}
       :findings []}`, or `{:migrated? false :findings [error…]}` when the
   result would not pass the dialect — in which case **not one byte is
   written** and the pool stays as it was.

   Only errors this migration *introduces* refuse it (`speculative-findings`,
   the same diff `execute_action` runs): a pre-D2.29 pool that was already
   carrying dialect errors still gets migrated, because those errors are not
   this migration's to pay for and refusing on them would leave the pool
   permanently unopenable.

   Under the write lock for the same reason `execute` takes it: the audit
   ordinal and the revisions are read from a db that must not move between the
   read and the transact."
  [conn]
  (locking conn
    (let [db (d/db conn)]
      (if-not (needed? db)
        {:migrated? false :summary {:merged 0 :navigations 0 :collections 0 :cleared 0} :findings []}
        (let [{:keys [tx audit summary]} (plan db)
              db-after (:db-after (d/with db tx))
              {:keys [errors]} (action/speculative-findings db db-after :migration
                                                            (keyword ruling))]
          (if (seq errors)
            {:migrated? false :summary summary :findings (vec errors)}
            (do (d/transact conn (into (vec tx)
                                       (audit/tx (audit/system-provenance ruling)
                                                 (count (store/all-mutations db))
                                                 audit)))
                {:migrated? true :summary summary :findings []})))))))

;; ---------------------------------------------------------------------------
;; Stamping the owner (D3.22)
;; ---------------------------------------------------------------------------
;;
;; D3.17 (2026-08-27) retired the global namespace claim table: a workspace is
;; a closed world, and a namespace is no longer registered for uniqueness.
;; D3.19 finished the job («no selector, and no turf»). But the write boundary
;; kept asking that retired table who owned an object, and refused every name
;; no surviving claim happened to cover — measured 2026-09-05, `+ entity` on
;; any fresh name was refused with `:asset-outside-current-workspace`.
;;
;; D3.22 (owner ruling 2026-09-05) settles it: **ownership is a stored fact**,
;; stamped by the write that created the object, never derived from its name.
;; That leaves exactly one question — who owns the objects written BEFORE the
;; column existed — and this is where it is answered, once, for good.
;;
;; ## The derivation lives here, not in the app
;;
;; It used to live in `apps/studio/.../builder_server.clj` (`asset-workspace`,
;; `source-owner`, `source-owners`) and ran on **every write**. It runs here
;; **once**, and the app's copy is deleted: a rule that decides history must
;; not stay on the hot path, where it keeps deciding the present.
;;
;; The rules are exactly the ones that were in force, reproduced verbatim in
;; effect:
;;
;; | type | owner |
;; |---|---|
;; | `:link` | the owner of its `:from` attribute |
;; | `:resolver` | the single owner of its `:output` attributes, else undecided |
;; | `:source` | the single owner of the resolvers referencing it, else undecided |
;; | everything else | the owner of its own id (namespace, or vocabulary domain) |
;;
;; The claim table is read as raw datoms rather than through the graph module:
;; the pool must not depend on `studio/graph` (D1.8, and `deps.edn` enforces
;; it). Reading three attribute names off a table that is being retired is a
;; smaller debt than inverting the dependency direction for one migration.
;;
;; ## Undecided is refused, never guessed — with one honest exception
;;
;; The fallback has two levels, in order (D3.22 migration table, §1b.1 of the
;; E4 task sheet, owner ruling 2026-09-05):
;;
;; 1. If exactly one workspace is registered in this Studio, an undecided
;;    object belongs to it: there is nobody else it could belong to.
;; 2. Otherwise, if exactly one workspace holds any claim at all, that one is
;;    where the modelling happened — a claim is the evidence, and the other
;;    workspaces are empty personal ones. Measured on a real pool 2026-09-05:
;;    four workspaces, all nine claims under one of them, 169 objects no claim
;;    covered.
;;
;; With neither level available the migration **refuses and names every object
;; it could not decide** (`:pool.migrate/unowned`) so a person settles it.
;; Guessing here would silently hand one workspace's model to another
;; (CLAUDE.md: 拒绝而非截断/猜测).

(def ^:private claim-selector-ident :graph.claim/selector)

(defn- claims
  "The retired claim table, as `[[selector workspace] …]`, longest selector
   first so an exact name beats a prefix that also covers it."
  [db]
  (if (ident-installed? db claim-selector-ident)
    (->> (d/q '[:find ?s ?w :where [?e :graph.claim/selector ?s]
                                   [?e :graph.claim/workspace ?w]] db)
         (sort-by (fn [[s _]] [(- (count s)) s]))
         vec)
    []))

(defn- selector-matches?
  "scope-projection-v1 §1's segment-boundary match, for the two canonical
   selector forms. A private copy of `studio.graph.impl.selector/matches?` —
   see the dependency note above; it dies with the claim table."
  [selector nm]
  (boolean
   (and (string? nm) (string? selector)
        (if (and (> (count selector) 2)
                 (str/ends-with? selector "*")
                 (contains? #{\. \/} (nth selector (- (count selector) 2))))
          (let [p (subs selector 0 (dec (count selector)))]
            (and (str/starts-with? nm p) (> (count nm) (count p))))
          (= selector nm)))))

(defn- owner-of-name [cs nm]
  (some (fn [[selector workspace]]
          (when (selector-matches? selector nm) workspace))
        cs))

(defn- lookup-names
  "An object id → the names to ask the claim table about, most specific first.
   The one implementation the retired registry had (`registry/lookup-names`)."
  [x]
  (cond
    (qualified-keyword? x) [(namespace x)]
    (keyword? x)           []
    (string? x)            (if-let [i (str/index-of x "/")] [x (subs x 0 i)] [x])
    :else                  []))

(defn- owner-of-object [cs x]
  (some #(owner-of-name cs %) (lookup-names x)))

(defn- output-attributes
  "A resolver `:output` tree → every attribute it names, parents included."
  [output]
  (mapcat #(cond
             (keyword? %) [%]
             (map? %) (let [[parent children] (first %)] (cons parent children))
             :else [])
          (or output [])))

(defn- registered-workspaces
  "Workspace names registered in this Studio's single Datahike store."
  [db]
  (if (ident-installed? db :graph.workspace/name)
    (vec (sort (d/q '[:find [?n ...] :where [?e :graph.workspace/name ?n]] db)))
    []))

(defn- sole-claimant
  "The one workspace that holds any claim, or nil when none or several do.

   Level 2 of the migration fallback: with the turf table retired (D3.17) the
   claims left on disk are no longer a rule, only a record of where modelling
   used to happen — and when exactly one workspace has any, that is the
   modelling workspace (D3.22)."
  [cs]
  (let [ws (set (map second cs))]
    (when (= 1 (count ws)) (first ws))))

(defn- derive-owners
  "`{[type id] workspace-or-nil}` for every object, by the pre-D3.22 rules."
  [cs objects]
  (let [own      (fn [x] (owner-of-object cs x))
        resolver-owner
        (fn [entry]
          (let [os (set (keep own (output-attributes (:output entry))))]
            (when (= 1 (count os)) (first os))))
        resolvers (filter #(= :resolver (:pool/type %)) objects)
        source-owner
        (fn [id]
          (let [os (set (keep (fn [r] (when (= id (:source-ref (:pool/entry r)))
                                        (resolver-owner (:pool/entry r))))
                              resolvers))]
            (when (= 1 (count os)) (first os))))]
    (into {}
          (for [{:pool/keys [type id entry]} objects]
            [[type id]
             (case type
               :link     (own (:from entry))
               :resolver (resolver-owner entry)
               :source   (source-owner id)
               (own id))]))))

(defn- unstamped
  "Objects with no `:pool.object/workspace` datom, in a deterministic order."
  [db]
  (vec (remove :pool/workspace (store/all-objects db))))

(defn stamp-needed?
  "Does this pool still hold objects with no owner stamped on them?"
  [db]
  (boolean (seq (unstamped db))))

(defn stamp-workspace!
  "Stamp every unstamped pool object with its owning workspace (D3.22).

   → `{:migrated? bool :summary {:stamped n :by-claim n :by-sole-workspace n}
       :findings []}`, or `{:migrated? false :findings [error…]}` naming every
   object whose owner could not be decided — in which case **not one byte is
   written**.

   Idempotent, one transaction, one mutation record per object touched with a
   system provenance — the same skeleton `migrate-d229!` uses, for the same
   reasons (see this ns's header)."
  [conn]
  (locking conn
    (let [db (d/db conn)
          objs (unstamped db)]
      (if (empty? objs)
        {:migrated? false :summary {:stamped 0 :by-claim 0 :by-sole-workspace 0} :findings []}
        (let [cs     (claims db)
              owners (derive-owners cs objs)
              wss    (registered-workspaces db)
              claimants (sole-claimant cs)
              ;; Level 1 then level 2; see «Undecided is refused» above.
              sole   (if (= 1 (count wss)) (first wss) claimants)
              decide (fn [o] (or (get owners [(:pool/type o) (:pool/id o)]) sole))
              undecided (remove decide objs)]
          (if (seq undecided)
            {:migrated? false
             :summary {:stamped 0 :by-claim 0 :by-sole-workspace 0}
             :findings
             (vec (for [{:pool/keys [type id]} undecided]
                    ;; Names the object and nothing else: an entry's contents
                    ;; never enter a finding (finding-v1 §5).
                    (f/make :pool.migrate/unowned :error [:pool type id]
                            (str "no owning workspace could be decided for this object; "
                                 "this Studio holds " (count wss) " workspaces and no single one "
                                 "of them holds every claim, so there is no owner to fall back "
                                 "to; assign it before opening the pool")
                            :got {:workspaces (count wss)}
                            :refs ["D3.17" "D3.22"])))}
            (let [tx (vec (for [o objs
                                :let [cur (store/find-object
                                           db (model/object-key (:pool/type o) (:pool/id o)))]]
                            {:db/id (:db/id cur)
                             :pool.object/workspace (decide o)}))
                  audit-entries
                  (vec (for [o objs]
                         ;; An `:update` that changes no dialect key but does
                         ;; change who may write the object next — exactly the
                         ;; kind of change a ledger exists to carry.
                         {:action :update :type (:pool/type o) :id (:pool/id o)
                          :revision-before (:pool/revision o)
                          :revision-after (:pool/revision o)}))]
              (d/transact conn (into tx
                                     (audit/tx (audit/system-provenance stamp-ruling)
                                               (count (store/all-mutations db))
                                               audit-entries)))
              {:migrated? true
               :summary {:stamped (count objs)
                         :by-claim (count (filter #(get owners [(:pool/type %) (:pool/id %)]) objs))
                         :by-sole-workspace
                         (count (remove #(get owners [(:pool/type %) (:pool/id %)]) objs))}
               :findings []})))))))

;; ---------------------------------------------------------------------------
;; Repairing the recipes D2.29 left behind (doc 106 §106.14 item 19)
;; ---------------------------------------------------------------------------
;;
;; D2.29 moved navigations into the attribute registry **in the pool** and
;; touched no graph's recipe. Two defects were measured on a real Studio
;; (2026-09-02, OQ-D229-RECIPES, now ruled in doc 106 §106.14 item 19):
;;
;; 1. Recipes still carry the retired `:navigations` group. The member key set
;;    is closed at eight groups (`registry/member-kinds`, scope-projection-v1
;;    §1), so `update-recipe!` refuses those graphs outright with
;;    `:graph.registry/malformed` — editing a recipe from the UI is impossible.
;; 2. Recipes do not name the join attributes the navigations became. A
;;    resolver's `:output` tree then straddles the selection — the branch key is
;;    outside it, its leaves inside — and the cut is refused with
;;    `:scope/mixed-output` (scope-projection-v1 §4). Every pre-D2.29 graph
;;    became unpublishable, with a refusal that never mentions the migration.
;;
;; The ruling puts the repair **here** rather than in a one-shot tool or at
;; graph open: `migrate!` already runs inside `open-pool!`, is idempotent, is
;; one transaction, and writes one mutation record per changed object with a
;; system provenance. A graph a hand-written script already repaired has
;; nothing to change and is a no-op — no record is written for it.
;;
;; ## Why the graph tables are read as raw datoms
;;
;; Same reason the claim table is, above: the pool must not depend on
;; `studio/graph` (D1.8, enforced by `deps.edn`). Both live in one Datahike
;; store, so the recipe is reachable as `:graph.graph/members` — one EDN string
;; per graph, encoded exactly as this ns encodes EDN (`schema/encode-edn` there
;; sets the same `*print-namespace-maps* false`). `ident-installed?` guards the
;; read: a pool opened before `graph/install!` has no such ident, and that is
;; not an error, it is a pool with no graphs yet.
;;
;; ## A recipe this pass cannot read is left alone
;;
;; `:graph.graph/members` that does not decode to a map is not repaired and not
;; recorded. Refusing here would make one malformed recipe stop the whole pool
;; from opening; the graph registry already refuses that recipe **by name**
;; (`:graph.registry/malformed`) at the place that owns recipe shape, which is
;; where a person can act on it.

(def ^:const recipe-ruling
  "The ruling the recipe repair carries out; it is what its audit records name."
  "D2.29-recipes")

(def ^:private graph-name-ident :graph.graph/qualified-name)
(def ^:private graph-members-ident :graph.graph/members)

(def ^:private retired-group
  "The ninth recipe group D2.29 retired (registry/member-kinds names the
   surviving eight)."
  :navigations)

(defn- registered-recipes
  "`[{:db/id … :name … :members …} …]` for every registered graph, by name.

   Deterministic order for the same reason `plan` sorts: the audit records of a
   migration must read the same way twice."
  [db]
  (if (ident-installed? db graph-name-ident)
    (->> (d/q '[:find [(pull ?e [:db/id :graph.graph/qualified-name
                                 :graph.graph/members]) ...]
                :where [?e :graph.graph/qualified-name]] db)
         (map (fn [g] {:db/id   (:db/id g)
                       :name    (get g graph-name-ident)
                       :members (some-> (get g graph-members-ident) model/decode-edn)}))
         (sort-by :name)
         vec)
    []))

(defn- resolver-outputs
  "`{resolver-id output-tree}` for every resolver in the pool.

   The recipe names resolvers by id (a string), which is what
   `:pool/id` holds for them, so no further addressing is needed."
  [db]
  (into {} (for [{:pool/keys [type id entry]} (store/all-objects db)
                 :when (= :resolver type)]
             [id (:output entry)])))

(defn- branch-roots
  "An `:output` tree → `[[join-attribute [leaf…]] …]` for its branches only.
   D2.16: a leaf is a scalar attribute, a branch is a join attribute."
  [output]
  (for [node (or output [])
        :when (map? node)
        :let [[parent children] (first node)]
        :when (qualified-keyword? parent)]
    [parent (filter qualified-keyword? children)]))

(defn- straddling-join-attributes
  "Join attributes a graph's resolvers produce that its recipe does not name,
   while it does name at least one leaf under them — the root cause of
   `:scope/mixed-output` (scope-projection-v1 §4).

   A branch whose leaves are **all** outside the recipe is not straddling
   anything: that subtree is simply not in this graph, and adding its root
   would pull model into the cut that its author never selected."
  [members outputs]
  (let [attrs (set (:attributes members))]
    (into (sorted-set)
          (for [rid (:resolvers members)
                [parent children] (branch-roots (get outputs rid))
                :when (and (not (contains? attrs parent))
                           (some #(contains? attrs %) children))]
            parent))))

(defn- repair-recipe
  "One recipe → `{:members … :moved […] :dropped […] :added […]}`, or nil when
   there is nothing to do.

   The retired group's members are duplicated in `:attributes` in every case
   measured (`demo/ps-dvd9` held 26 of them, verbatim), so dropping the group is
   normally all that happens. A member that is **not** already in `:attributes`
   and is a valid attribute id is **moved** there instead of lost — the two
   outcomes are told apart in the record this pass writes, because «the group
   was redundant» and «the group held the only mention of an attribute» are
   different facts about the graph."
  [members outputs]
  (when (map? members)
    (let [attrs   (set (:attributes members))
          navs    (vec (sort-by pr-str (get members retired-group)))
          moved   (vec (remove #(or (not (qualified-keyword? %)) (contains? attrs %)) navs))
          dropped (vec (remove (set moved) navs))
          attrs'  (into attrs moved)
          added   (vec (straddling-join-attributes (assoc members :attributes attrs') outputs))]
      (when (or (contains? members retired-group) (seq added))
        {:members (-> (dissoc members retired-group)
                      (assoc :attributes (into attrs' added)))
         :moved moved :dropped dropped :added added}))))

(defn migrate-recipes!
  "Bring every registered graph's recipe up to D2.29 (doc 106 §106.14 item 19).
   Idempotent; a no-op on a pool whose recipes are already repaired — including
   one an out-of-ledger script repaired, which has nothing to change and gets no
   record.

   → `{:migrated? bool :summary {:recipes n :dropped n :moved n :added n}
       :findings []}`.

   One transaction, one mutation record per **changed graph**, system
   provenance — the same skeleton `migrate-d229!` and `stamp-workspace!` use,
   for the same reasons (see this ns's header).

   The record's object is `[:graph/recipe {…}]`: not a pool object type, so no
   reader of the ledger mistakes it for one, and the map says which of the two
   repairs happened to which names. A recipe is a list of names, and a record
   saying only «the recipe changed» would leave the next reader to diff two
   versions of an EDN blob to learn what this pass did."
  [conn]
  (locking conn
    (let [db      (d/db conn)
          outputs (resolver-outputs db)
          work    (vec (for [g (registered-recipes db)
                             :let [r (repair-recipe (:members g) outputs)]
                             :when r]
                         (assoc r :db/id (:db/id g) :name (:name g))))]
      (if (empty? work)
        {:migrated? false :summary {:recipes 0 :dropped 0 :moved 0 :added 0} :findings []}
        (let [tx (vec (for [w work]
                        {:db/id (:db/id w)
                         :graph.graph/members (model/encode-edn (:members w))}))
              audit-entries
              (vec (for [w work]
                     {:action :update :type :graph/recipe
                      :id (cond-> {:graph (:name w)}
                            (seq (:dropped w)) (assoc :dropped-duplicates (:dropped w))
                            (seq (:moved w))   (assoc :moved-to-attributes (:moved w))
                            (seq (:added w))   (assoc :added-join-attributes (:added w)))
                      ;; A recipe carries no revision of its own; 0/0 says «the
                      ;; object's revision did not move», which is the truth
                      ;; here — nothing in the pool changed, the graph's list of
                      ;; names did.
                      :revision-before 0 :revision-after 0}))]
          (d/transact conn (into tx
                                 (audit/tx (audit/system-provenance recipe-ruling)
                                           (count (store/all-mutations db))
                                           audit-entries)))
          {:migrated? true
           :summary {:recipes (count work)
                     :dropped (reduce + (map (comp count :dropped) work))
                     :moved   (reduce + (map (comp count :moved) work))
                     :added   (reduce + (map (comp count :added) work))}
           :findings []})))))

(defn migrate!
  "Bring one pool up to date: **D2.29 first, then D3.22, then the recipes**.

   The order is not incidental. D2.29 creates and rewrites attributes, and a
   D3.22 stamp written before it would have to be carried through that rewrite
   for objects that may not survive it; run second, the stamp pass simply sees
   the finished set of objects. The recipe repair runs **last** for the same
   reason once more: it decides which join attributes a recipe must name by
   reading the resolvers' `:output` trees, and those trees only mean what D2.29
   says they mean after D2.29 has run (doc 106 §106.14 item 19).

   → `{:migrated? bool :summary {…the three summaries merged…} :findings []}`,
   or `{:migrated? false :findings [error…]}` from whichever pass refused. Each
   pass is its own transaction, and a refusal in a later one leaves the earlier
   ones committed — that is honest: D2.29 succeeded, and the pool says so."
  [conn]
  (let [a (migrate-d229! conn)]
    (if (seq (:findings a))
      a
      (let [b (stamp-workspace! conn)]
        (if (seq (:findings b))
          {:migrated? (boolean (:migrated? a))
           :summary   (merge (:summary a) (:summary b))
           :findings  (vec (:findings b))}
          (let [c (migrate-recipes! conn)]
            {:migrated? (boolean (or (:migrated? a) (:migrated? b) (:migrated? c)))
             :summary   (merge (:summary a) (:summary b) (:summary c))
             :findings  (vec (:findings c))}))))))
