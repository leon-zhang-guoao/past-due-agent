(ns open.atlas.common.dialect.impl.carry
  "**What a join branch inherits from its parent** — the one mechanical answer
   behind rulings 13, 17 and 18 of doc 106 §106.14.

   Pathom 3's plain nested join does NOT inherit the parent's attributes, so a
   child resolver whose `:input` needs the host — or the parent's key — is
   simply unreachable. The remedy the owner ruled on is that the parent
   resolver DECLARES and EMITS what it already knows: the connection's
   URL-bound attributes ride every branch (item 13), and an in-record
   collection additionally carries each ancestor record's identity attributes
   (item 17).

   It lives in `common/dialect` because it reads nothing but a dialect
   `:output` tree plus two facts a caller supplies, and because THREE callers
   need the same answer (D1.8「不要各写一份」): the engine, which writes the
   carried keys into child rows; the SPI seam, which turns it into «what is
   carried into this namespace»; and `need-view`, which reads a compound
   identity back out of it (item 18). Deciding WHICH attributes a source binds
   is a source-dialect judgement and stays behind the SPI — this ns only walks
   the tree.")

(defn record-identities
  "The identity attributes of the record one LEVEL of an `:output` tree
   describes: every identity attribute of the namespaces that level speaks in
   (doc 106 §106.14 item 17). `identity` is declared, never inferred
   (principles), so this only reads the model back."
  [ids attrs]
  (let [nss (into #{} (keep namespace) attrs)]
    (into [] (sort (filter (comp nss namespace) ids)))))

(defn carried-by-branch
  "Every join branch of `tree`, at every depth, mapped to the attributes the
   engine carries into it.

   Two rulings, one mechanism (doc 106 §106.14 items 13 and 17):

   - the source's URL-BOUND attributes that this resolver takes in ride EVERY
     branch, because the child is read from the same connection (item 13);
   - an IN-RECORD collection (`:_from`) additionally carries every ancestor
     record's IDENTITY attributes, because its rows were assembled out of the
     parent's own response and the parent's key was therefore known at
     assembly time (item 17). An endpoint-backed child collection carries none
     of them: its rows came from a second call and never held the parent key.

   Ancestor identities accumulate only THROUGH in-record branches; an
   endpoint-backed branch resets them, so nothing is claimed that the row
   cannot supply. `top` is the level-0 record's identities."
  [tree bound in-record? ids top]
  (let [acc (volatile! {})]
    (letfn [(level [v own inherited]
              (doseq [node v :when (map? node)
                      [k sub] node]
                (let [in?   (contains? in-record? k)
                      seen  (if in? (into (vec inherited) own) [])
                      carry (into [] (distinct) (concat bound seen))]
                  (vswap! acc assoc k carry)
                  ;; Semantic contract 2.0 §6: a recursion node `{k n}` opens no
                  ;; subtree of its own, and a union `{k {branch [..]}}` opens
                  ;; one per branch -- each walked as its own level.
                  (doseq [child (cond (vector? sub) [sub] (map? sub) (vals sub) :else [])]
                    (level child (record-identities ids (filterv keyword? child)) seen)))))]
      (level tree top [])
      (into {} (remove (comp empty? val)) @acc))))

(defn carried-by-namespace
  "One resolver's `:output` tree → `{namespace-string #{attribute …}}`: which
   attributes are carried INTO the rows each namespace's attributes appear in.

   The same walk `carried-by-branch` makes, read from the other end. The
   engine asks «what do I write into this branch»; `need-view` asks «what is
   in the rows of this thing», because item 18 lets a carried attribute be
   part of an identity. Answering both from one walk is what keeps a thing's
   key the same on both faces (§106.5)."
  [tree bound in-record? ids top]
  (let [carry (carried-by-branch tree bound in-record? ids top)]
    (letfn [(add [acc attrs c]
              (reduce (fn [a ns'] (update a ns' (fnil into #{}) c))
                      acc
                      (into #{} (keep namespace) attrs)))
            (level [acc v c]
              (let [acc (add acc (filter keyword? v) c)]
                (reduce (fn [a node]
                          (if (map? node)
                            (reduce (fn [a2 [k sub]]
                                      (reduce (fn [a3 child] (level a3 child (get carry k [])))
                                              a2
                                              (cond (vector? sub) [sub] (map? sub) (vals sub) :else [])))
                                    a node)
                            a))
                        acc v)))]
      (into {} (remove (comp empty? val)) (level {} tree bound)))))
