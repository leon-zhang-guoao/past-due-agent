(ns open.atlas.studio.pool.impl.batch
  "Several actions, applied as one.

   ## Why this is not a loop over `execute-action`

   Some proposals are only meaningful whole. A table generated from a database
   schema is a namespace, n attributes (scalar ones and the join attribute that
   reaches the collection, D2.29) and two resolvers, and applying four of those
   six leaves the pool holding an entity that answers questions wrongly — an
   attribute registered but produced by nothing, a join with no resolver behind
   it. Run as n separate calls, \"half a table\" becomes a state the pool can be
   left in, and nothing in the system can tell that state apart from a table
   someone is still writing by hand.

   Same argument `confirm_candidates` makes for being one action rather than
   n+1 (see `impl/confirm.clj`), reached from the other direction.

   ## Order matters, and is the caller's

   Actions are applied in the order given, each validated against the pool **as
   the earlier ones left it**. That is what lets a batch create an object and
   then update it: the second action's `expected_revision` is checked against
   the revision the first one wrote, not against the pool as the batch began. A
   caller that orders its actions wrongly gets a structured refusal naming the
   object, not a silent reordering — this ns does not know which of two possible
   orders the caller meant.

   ## Atomicity is real, not approximated

   Validation runs entirely on speculative databases; nothing is written until
   every action has passed. The accumulated transaction is then committed **in
   one `d/transact`**, which is possible because `schema/object->tx` addresses
   each object by a string tempid derived from `:pool.object/key`, a unique
   identity — so two actions in one batch that touch the same object land on
   one entity rather than two."
  (:require [datahike.api :as d]
            [open.atlas.studio.pool.impl.action :as action]
            [open.atlas.studio.pool.impl.audit :as audit]
            [open.atlas.studio.pool.impl.finding :as f]
            [open.atlas.studio.pool.impl.model :as model]
            [open.atlas.studio.pool.impl.store :as store]))

(defn- indexed-finding
  "A batch's findings must say *which* action they are about. Without the
   index, a refusal on the ninth of twenty identical-looking attribute writes
   is a puzzle rather than a message."
  [i finding]
  (update finding :path #(into [:batch i] (or % []))))

(defn prepare
  "Validate the whole batch against the pool, writing nothing.
   → `{:tx […] :db-after db :revisions [n …]}` or `{:findings […]}`."
  [db actions]
  (loop [[a & more] (seq actions)
         i          0
         db*        db
         tx         []
         revisions  []
         ;; one audit entry per action, in the order they will be applied
         entries    []]
    (if (nil? a)
      {:tx tx :db-after db* :revisions revisions :entries entries}
      (let [shape (if (map? a)
                    (action/check-shape a)
                    [(f/make :pool.action/malformed :error [] "action must be a map"
                             :got (f/redact a))])
            {:keys [findings] :as r} (if (seq shape) {:findings shape} (action/prepare db* a))]
        (if (seq findings)
          {:findings (mapv #(indexed-finding i %) findings)}
          (let [key*   (model/object-key (:type a) (:id a))
                ;; Read the revision from the db this action was validated
                ;; against, not from the pool as it stood when the batch
                ;; began: a batch may write the same object twice, and the
                ;; second record must say what the first one left behind.
                before (:pool.object/revision (store/find-object db* key*))]
            (recur more (inc i) (:db-after r)
                   (into tx (:tx r))
                   (conj revisions (:revision r))
                   (conj entries {:action (:action a) :type (:type a) :id (:id a)
                                  :revision-before (or before 0)
                                  :revision-after (when-not (= :delete (:action a))
                                                    (:revision r))}))))))))

(defn execute
  "Apply `actions` as one unit.

   → `{:revisions [n …] :mutations [record …] :findings [warning …]}` when
   committed, or `{:findings [error …]}` when nothing was written. As with a single action,
   success can carry warnings: dirt the pool already had rides along rather
   than being charged to this write (studio/graph ruling ⑤)."
  ([conn actions] (execute conn actions nil))
  ([conn actions prov]
   (cond
    (not (sequential? actions))
    {:findings [(f/make :pool.action/malformed :error [:batch]
                        "a batch is a sequence of actions"
                        :got (f/redact actions))]}

    (empty? actions)
    ;; An empty batch is almost always a caller bug — a selection that filtered
    ;; down to nothing, or a plan whose proposals were all blocked. Committing
    ;; nothing and reporting success would hide that.
    {:findings [(f/make :pool.action/malformed :error [:batch]
                        "a batch must contain at least one action"
                        :refs ["§7.3"])]}

    (seq (audit/check prov))
    {:findings (f/sort-findings (vec (audit/check prov)))}

    :else
    (locking conn
      (let [db (d/db conn)
            {:keys [tx db-after revisions entries findings]} (prepare db actions)]
        (if (seq findings)
          {:findings (f/sort-findings findings)}
          (let [{:keys [errors warnings]}
                ;; D3.22: scoped to the workspace the batch writes on behalf
                ;; of. Every action carries one, and each is checked against
                ;; its object's stored owner regardless.
                (action/speculative-findings db db-after :batch (count actions)
                                             (:workspace (first actions)))]
            (if (seq errors)
              {:findings (vec errors)}
              ;; One `d/transact` for the objects AND their audit: a batch that
              ;; committed half its provenance would be worse than one that
              ;; committed none, because the gap would look like a fact.
              (let [audit-tx (audit/tx prov (count (store/all-mutations db)) entries)]
                (d/transact conn (into (vec tx) audit-tx))
                ;; One summary per record, in the order the actions applied
                ;; (E3): the assembly layer turns each into one unified-audit
                ;; event, so N objects touched is N events, never one.
                {:revisions (vec revisions)
                 :mutations (audit/summaries audit-tx)
                 :findings  (vec warnings)})))))))))
