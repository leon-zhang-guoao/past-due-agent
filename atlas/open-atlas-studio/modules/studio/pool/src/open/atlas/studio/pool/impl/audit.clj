(ns open.atlas.studio.pool.impl.audit
  "**Mutation audit**: who changed what, from which entrance
   ([8.4 P1b](../../../../../../../docs/design/84-builder-mcp.md)).

   principles「存储与同步」asks the pool to answer *「这个 revision 里哪些改动
   来自 AI」*. Until now only `confirm_candidates` could answer it — it carried
   an `:entrypoint`, and ordinary model writes carried nothing at all. So the
   question had an answer for one write face and no answer for the other, which
   is worse than no answer anywhere: a report built on it reads as complete.

   ## Ruling ①: provenance is a SEPARATE argument, never a key in the action

   `execute_action`'s key set is closed (`action/action-keys`), and this ns
   does not widen it. A caller that puts `:entrypoint` or `:token-id` in the
   action map is refused with `:pool.action/malformed` — **forging an identity
   is not filtered out, it is unaddressable**. The trusted values arrive on a
   different argument, which only server-side code constructs.

   That distinction is the whole security property. A filter is a line of code
   someone can move; an argument the wire cannot reach is a shape.

   ## Ruling ②: an unattributed write is RECORDED, not skipped

   Provenance is optional, because the pool has callers (tests, migrations,
   fixtures) with no entrance to name. When it is absent the record is still
   written, with `:entrypoint :unattributed`.

   The alternative — write no record — makes «this change had no provenance»
   and «this change was never made» indistinguishable in the ledger, and the
   ledger is consulted precisely when someone is asking which is which. A
   caller may NOT pass `:unattributed` explicitly: it is the shape of an
   absence, and a caller claiming it would be asserting something they cannot
   know.

   ## Ruling ③: audit records are not model content

   Same discipline as the confirmation records (`impl/confirm.clj` ruling ②):
   a separate entity family, addressed by `:pool.mutation/id` and never by
   `:pool.object/key`. `as-dialect`, the speculative validation and the export
   object list all go through `store/all-objects`, so «audit does not reach the
   model» is a fact those queries **cannot** see past, not a rule to remember.
   If it were model content, the same model would produce a different
   content-hash depending on who wrote it (D4.3).

   Immutability is structural too: no write face takes `:mutation` as a type
   (`model/known-type?` does not know it), so nothing can address a record to
   update or retract it."
  (:require [clojure.set :as set]
            [open.atlas.studio.pool.impl.finding :as f]
            [open.atlas.studio.pool.impl.model :as model]))

(def entrypoints
  "The entrance vocabulary a caller may name: `#{:ui :http :mcp}`.

   One vocabulary for both ledgers — the confirmation records and the mutation
   audit — because «which entrance» must mean the same thing in a report that
   joins them. Two copies would be two lists to keep in step, and the day they
   part is invisible (CLAUDE.md on `redact` and the tier vocabulary).

   `:unattributed` is deliberately NOT in here: see ruling ② — it marks an
   absence, and only this ns may write it."
  #{:ui :http :mcp})

(def ^:private unattributed
  "The entrance of a write whose caller named none."
  :unattributed)

(def ^:private provenance-keys #{:entrypoint :token-id :actor :at})

(defn system-provenance
  "The provenance of a write the **pool itself** performs — today only the
   D2.29 pool migration (`impl/migrate`).

   Shape: an `:actor` and no `:entrypoint`, which is exactly the shape `check`
   refuses. That is not an oversight, it is the point twice over:

   - **No entrance.** `entrypoints` is «the entrance a caller may name»; a
     migration has none (ruling ②, which names migrations among the callers
     with nothing to name), so the record says `:unattributed` — the shape of
     an absence, which only this ns may write.
   - **No human actor.** The actor is a machine identity naming the ruling that
     moved the data. Putting a person there would forge the one field the
     ledger exists to be believed about; leaving it out would make a migration
     indistinguishable from a write nobody can account for.

   `check` refusing this shape means a caller cannot reach it over the wire —
   the same argument ruling ① makes for provenance being a separate argument."
  [what]
  {:actor {:system "open.atlas.studio.pool" :migration what}})

(defn check
  "Findings for a provenance map, or `[]`. `nil` is legal — an unattributed
   write (ruling ②).

   Note what is NOT checked: whether the token-id exists, or whether the actor
   may write. The pool is not the place that decides either — it records what
   the trusted caller states. The caller's own authentication is what makes
   that statement worth recording, and it happened before this call."
  [prov]
  (cond
    (nil? prov) []

    (not (map? prov))
    [(f/make :pool.provenance/malformed :error [:provenance]
             "provenance must be a map"
             :got (f/redact prov))]

    :else
    (cond-> []
      (seq (set/difference (set (keys prov)) provenance-keys))
      (conj (f/make :pool.provenance/malformed :error [:provenance]
                    "provenance key set is closed"
                    :expected (vec (sort (map str provenance-keys)))
                    :got (vec (sort (map str (set/difference (set (keys prov))
                                                             provenance-keys))))))

      (not (contains? entrypoints (:entrypoint prov)))
      (conj (f/make :pool.provenance/malformed :error [:provenance :entrypoint]
                    (str "entrypoint must name an entrance; omit the whole provenance map "
                         "for an unattributed write rather than naming one")
                    :expected (vec (sort entrypoints)) :got (:entrypoint prov)
                    :refs ["principles 存储与同步" "8.4 P1b"]))

      (not (or (nil? (:token-id prov)) (and (string? (:token-id prov)) (seq (:token-id prov)))))
      (conj (f/make :pool.provenance/malformed :error [:provenance :token-id]
                    "token-id, when given, must be a non-empty string"
                    :got (f/redact (:token-id prov))))

      ;; The actor is stored **as it is**, EDN-encoded, because a control-plane
      ;; principal is a map (`{:issuer … :subject …}`, D5.10) and flattening it
      ;; to a string here would make the audit hold a shape nothing else in the
      ;; platform uses — and the joins this ledger exists for are joins ON the
      ;; principal. Anything non-nil is therefore acceptable; what it means is
      ;; the host's business.
      (and (contains? prov :actor) (nil? (:actor prov)))
      (conj (f/make :pool.provenance/malformed :error [:provenance :actor]
                    "actor, when the key is present, must not be nil; omit the key instead"
                    :got (f/redact (:actor prov))))

      (not (or (nil? (:at prov)) (and (string? (:at prov)) (seq (:at prov)))))
      (conj (f/make :pool.provenance/malformed :error [:provenance :at]
                    "at, when given, must be a non-empty ISO instant string"
                    :got (f/redact (:at prov)))))))

(defn- record-id
  "A record's identity: its ordinal in this pool's audit, zero-padded.

   Not a UUID and not a clock. This pool's discipline is that the same write
   history produces the same bytes (`export/export-str`), and either of those
   would make the audit the one part of an export that never compares equal.

   **Not derived from the object key either**, which is what the first version
   did and what `create-and-delete-say-what-they-are` caught: `:pool.mutation/id`
   is a unique identity, so a second write to the SAME object produced the same
   id and Datahike upserted the new record onto the old one — the delete's
   record silently became an edit of the create's. An audit whose records
   overwrite each other is worse than none: it reports the last write as if it
   were the only one.

   Zero-padded so lexicographic order is chronological order; the ordinal is
   read inside the write lock, so two writers cannot claim the same one."
  [n]
  (format "%012d" n))

(defn entry-tx
  "One audit record as a tx map.

   `entry` is `{:action :type :id :revision-before :revision-after}`, and `n`
   is the ordinal the first of them takes.
   `revision-before` is 0 on a create — «did not exist» said in the same
   vocabulary `expected_revision` uses on `:create` (§7.3), so a reader needs
   one rule rather than two. `revision-after` is nil on a delete, and the key
   is then absent rather than 0: 0 already means «did not exist», and a deleted
   object did."
  [prov n {:keys [action type id revision-before revision-after]}]
  (cond-> {:db/id (str "mut-" (record-id n))
           :pool.mutation/id (record-id n)
           :pool.mutation/entrypoint (or (:entrypoint prov) unattributed)
           :pool.mutation/action action
           :pool.mutation/object (model/encode-edn [type id])
           :pool.mutation/revision-before (or revision-before 0)}
    (some? revision-after) (assoc :pool.mutation/revision-after revision-after)
    (:token-id prov)       (assoc :pool.mutation/token-id (:token-id prov))
    (:actor prov)          (assoc :pool.mutation/actor (model/encode-edn (:actor prov)))
    (:at prov)             (assoc :pool.mutation/at (:at prov))))

(defn tx
  "Audit records for a whole write, as tx data. `n` is the pool's current audit
   count, read inside the write lock.

   Committed in the **same** `d/transact` as the objects themselves, which is
   what makes «every model write has a record» a property of the storage rather
   than of the code path: there is no ordering in which one lands and the other
   does not."
  [prov n entries]
  (vec (map-indexed (fn [i e] (entry-tx prov (+ n i) e)) entries)))

(defn summaries
  "What the records this write is about to commit say, for the caller.

   Derived from the very tx maps `tx` produced, not rebuilt from the entries:
   the assembly layer emits one unified-audit event per record (E3), and the
   two must agree about the id, the object and the two revisions. Two
   constructions of one fact is how they come to disagree.

   The record id rides along so a `:pool/mutation` event and the pool ledger
   row it reports can be joined by name rather than by timestamp — E3's whole
   point is that guessing the correspondence is not an answer."
  [audit-tx]
  (mapv (fn [m]
          (cond-> {:mutation-id     (:pool.mutation/id m)
                   :action          (:pool.mutation/action m)
                   :object          (model/decode-edn (:pool.mutation/object m))
                   :entrypoint      (:pool.mutation/entrypoint m)
                   :revision-before (:pool.mutation/revision-before m)}
            (contains? m :pool.mutation/revision-after)
            (assoc :revision-after (:pool.mutation/revision-after m))))
        audit-tx))
