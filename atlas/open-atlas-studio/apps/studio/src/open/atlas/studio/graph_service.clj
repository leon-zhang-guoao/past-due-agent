(ns open.atlas.studio.graph-service
  "Graph registry operations, **once**, for both entrances
   ([8.4 P2](../../../../../../docs/design/84-builder-mcp.md)).

   ## What moved, and why it had to

   These operations already existed as Builder HTTP handlers. Every one of them
   read its caller from two ambient places: `auth/*request-user*` (the session
   cookie) and the per-user *current workspace* remembered in the Studio home.
   Neither exists for an agent holding a workspace token, so an MCP endpoint
   could not have called them at all — and writing a second set that applied
   «the same» guards is precisely the drift a single registry is meant to
   prevent (principles「MCP 与工具面」: declared in one place, dispatched in
   another).

   So the caller became an **argument**. A `caller` is
   `{:workspace \"demo\" :principal {:issuer … :subject …}}`, built by whichever
   entrance authenticated it:

   - the Builder's UI routes build it from the session and the remembered
     workspace;
   - the MCP endpoint builds it from the workspace token's own record.

   Nothing else changed. The guards are the same guards, in the same order, and
   `studio/graph`'s semantics are untouched — this ns calls it exactly as the
   handlers did.

   ## Why the guards live here and not in the tool declarations

   A tool declaration says what an argument looks like; it must not also decide
   who may act. If the MCP face carried its own role checks, then «owner may
   archive» would be stated twice and enforced twice, and the day one copy
   learned about a new role the other would not. The tools are a wrapper
   (`studio/tools`' standing rule); the authority is here.

   **A caller never states its own role.** `caller` carries a principal and a
   workspace, both established by authentication; the role is looked up from
   the pool on every call. An entrance that could pass `:role :owner` would be
   an entrance that could grant itself one.

   ## Who may hand out access to a graph: the WORKSPACE OWNER

   `grant-graph-access!` is guarded by `require-workspace-owner!`, not by
   `require-editable!`. The reason is that `studio/graph` has no such thing as
   a per-graph owner: `graph-access-roles` is `#{:editor}`, and the
   `:owner` that `graph-access-role` can return comes from workspace ownership
   and nowhere else. So «the graph's owner or editor» resolves to «a graph
   editor», and a graph editor is either a workspace editor (who has that role
   on EVERY graph in the workspace, having been given it once, for modelling)
   or a workspace viewer who was granted it here.

   Letting either of those grant would make the capability self-propagating: a
   viewer granted `:editor` could grant the next viewer `:editor`, and the set
   of people who may rewrite a recipe would grow without an owner ever being
   asked. Granting is not an edit — it changes WHO MAY EDIT, which is the same
   kind of act as creating, archiving and deleting, all of which already sit at
   `require-workspace-owner!` because they change what a workspace IS.

   The cost is real and accepted: an owner is in the loop for every grant. That
   is the point — the alternative is an authority boundary that anyone inside
   it can widen.

   ## Refusals

   Every refusal is an `ex-info` whose `ex-data` carries a `:code` (and, for
   the ones `studio/graph` itself rejects, the finding vector under `:error`).
   Both entrances render that shape: the HTTP layer already did, and the MCP
   face turns it into a finding-v1 refusal. Same failure, same words, whichever
   door it arrived at — which is the property P2's exit condition names."
  (:require [clojure.edn :as edn]
            [open.atlas.studio.graph :as studio-graph]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.pool :as pool]
            ;; For `projected-kinds` only (8.4.10): the tool declaration
            ;; advertises those nine groups as an enum and this ns counts them,
            ;; so the word list is read from one place instead of typed twice.
            [open.atlas.studio.tools :as tools]
            [open.atlas.studio.version :as version]))

;; ---------------------------------------------------------------------------
;; Guards — the same ones, taking their caller explicitly
;; ---------------------------------------------------------------------------

(defn- refuse! [code message data]
  (throw (ex-info message (assoc data :code code))))

(defn- accept!
  "`studio/graph` reports a refusal as findings rather than by throwing. Both
   entrances need it to travel as one shape, so it becomes an `ex-info` here —
   at the single place that knows the operation's name."
  [message result]
  (when (seq (:findings result))
    (throw (ex-info message {:code :graph-rejected :error result})))
  result)

(defn require-graph!
  "The definition, or a refusal. Visibility and workspace are checked in the
   order the Builder always checked them: a graph the caller cannot see is
   «unknown», never «forbidden» — whether it exists is not their business.

   **Public** (8.4 P4): the Builder's own UI-only routes — duplicate, merge,
   the source and asset handlers — used to carry their own copies of this
   check, reading the session directly. Two copies answered the same question
   differently: one said «forbidden», which tells a caller in another workspace
   that the graph exists, and the other said «unknown», which does not. One
   definition, and the answer that leaks nothing wins."
  [db {:keys [workspace principal]} qn]
  (let [definition (or (studio-graph/graph db qn)
                       (refuse! :graph-unknown "Unknown Studio graph" {:graph qn}))]
    (when-not (studio-graph/graph-visible? db qn principal)
      (refuse! :graph-unknown "Unknown Studio graph" {:graph qn}))
    (when-not (= workspace (:graph/workspace definition))
      (refuse! :graph-outside-current-workspace
               "This graph belongs to a different workspace"
               {:graph qn :workspace workspace
                :graph-workspace (:graph/workspace definition)}))
    definition))

(defn require-editable! [db {:keys [principal]} definition]
  (let [qn (:graph/qualified-name definition)]
    (when-not (studio-graph/graph-editable? db qn principal)
      (refuse! :graph-editor-required "Only an owner or editor may edit this graph"
               {:graph qn}))
    definition))

(defn require-workspace-owner!
  "Owner of the caller's workspace. Archiving, renaming and deleting are the
   acts that change what a workspace IS, so they sit above editing."
  [db {:keys [workspace principal]}]
  (when-not (= :owner (studio-graph/workspace-role db workspace principal))
    (refuse! :workspace-owner-required "The workspace owner is required"
             {:workspace workspace}))
  workspace)

(defn- workspace-editor! [db {:keys [workspace principal]}]
  (when-not (contains? #{:owner :editor} (studio-graph/workspace-role db workspace principal))
    (refuse! :workspace-editor-required "An owner or editor of this workspace is required"
             {:workspace workspace}))
  workspace)

(defn- no-cut-versions!
  "Renaming and deleting are refused once a version has been cut: the artifacts
   carry the qualified name in their manifest, and a name that no longer
   resolves is worse than a name nobody likes (D3.18 / D3.6)."
  [path]
  (fn [db qn]
    (when (version/cut-any? db qn)
      [{:code :graph.registry/malformed :severity :error :path [path]
        :message (str "this graph has cut versions whose artifacts name it; "
                      "changing it would leave them pointing at a name that no longer resolves")
        :refs ["D3.18" "D3.6"]}])))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(defn list-graphs
  "The caller's workspace's graphs. `:include-archived?` opts in to the ones
   put away — absent, they are absent, because an archived graph is one someone
   deliberately stopped looking at.

   `:with-guide?` (2026-10-11) adds what each graph is FOR, read off its
   Semantic Contract draft: `:graph/label` and the optional
   `:graph/usage-guide` (purpose, scope, applicability, limitations). A graph
   with no draft, or no guide, simply has none — absence is not an error."
  [{:keys [conn caller]} {:keys [include-archived? with-guide?]}]
  (let [db (pool/db conn)
        guide (fn [g]
                (if-not with-guide?
                  g
                  (let [d (get-in (pool/semantic-draft db (:workspace caller) (:graph/id g))
                                  [:document :graph])]
                    (cond-> g
                      (:graph/label d) (assoc :graph/label (:graph/label d))
                      (:graph/usage-guide d) (assoc :graph/usage-guide (:graph/usage-guide d))))))]
    {:workspace (:workspace caller)
     :graphs (->> (studio-graph/visible-graphs db (:principal caller))
                  (filter #(= (:workspace caller) (:graph/workspace %)))
                  (filter #(or include-archived? (= :active (:graph/state %))))
                  (mapv #(-> (select-keys % [:graph/id :graph/name :graph/display-name :graph/qualified-name
                                             :graph/workspace :graph/state :graph/members])
                             (assoc :graph/can-edit?
                                    (studio-graph/graph-editable?
                                     db (:graph/qualified-name %) (:principal caller)))
                             guide)))}))

(defn graph-members
  "One graph's recipe — the object list it holds (D3.19). A read, so it needs
   only visibility."
  [{:keys [conn caller]} {:keys [graph]}]
  (let [db (pool/db conn)
        definition (require-graph! db caller graph)]
    {:graph (:graph/qualified-name definition)
     :members (studio-graph/normalize-members (:graph/members definition))}))

(def ^:private projection-id
  "kind → how the projection NAMES one entry of that group.

   `nil` marks a group the projection emits as a map keyed by id; a function
   marks one it emits as a vector, and reads the id off an entry. Step 10 of
   `studio/graph`'s projection fixes both the shape and the order (`sorted-map`
   for the keyed groups, `vec-by` for the vectors), so nothing here re-sorts:
   a second ordering rule would be a second answer to «what is the canonical
   order of this model», and the day they disagree the artifact and the tool
   face describe different graphs.

   The key set is `tools/projected-kinds`, asserted below rather than trusted —
   a group added to the projection and not to this table would otherwise be
   counted as empty, which reads exactly like «this graph has none»."
  {:namespaces nil :attributes nil :metrics nil :sources nil :terms nil :units nil
   :resolvers :id :mutations :id :links (juxt :from :to)})

(assert (= (set tools/projected-kinds) (set (keys projection-id)))
        "project_graph's enum and its shaping table must name the same groups")

(defn- projected-ids [model kind]
  (let [group (get model kind)]
    (if-let [id-of (projection-id kind)]
      (mapv id-of group)
      (vec (keys group)))))

(defn project-graph
  "What a graph would CONTAIN if cut right now — `studio/graph`'s real scope
   projection over the current draft pool, shaped for a reader (8.4.10).

   **Why this is not `graph-members`.** A recipe is a seed: it names
   namespaces, attributes, units and terms, and the projection pulls in the
   resolvers, mutations, links, metrics and sources whose outputs
   fall inside those attributes. An agent reading `resolvers: []` off a recipe
   and answering «this graph has no resolver» is reading the seed as the
   harvest — which is the incident this function exists to make impossible.

   A read, so `require-graph!` is the whole guard, and it is the same call
   `graph-members` makes: a graph in another workspace is «unknown» here word
   for word, not «forbidden».

   The projection's own refusal travels WHOLE (`accept!`): «this graph cannot
   be cut, and here is where it fails to close» is the answer a caller needs,
   and rewording it here would be this layer inventing a second vocabulary for
   scope-projection-v1.

   `:detail` is defaulted HERE and nowhere else — the tool declaration passes
   through what the caller actually sent, so «absent» has one meaning."
  [{:keys [conn caller]} {:keys [graph detail kind]}]
  (let [db (pool/db conn)
        definition (require-graph! db caller graph)
        qn (:graph/qualified-name definition)
        detail (or detail :counts)]
    (when-not (contains? #{:counts :ids :entries :model} detail)
      (refuse! :graph-detail-invalid "detail must be :counts, :ids, :entries or :model" {:got detail}))
    ;; Covers a missing kind as well as an unknown one. The tool face refuses
    ;; the missing case first and in its own words — that refusal is about an
    ;; unbounded dump, not about this table — but the authority to say which
    ;; kinds exist stays here, where the shaping is.
    (when (and (= :entries detail) (not (contains? projection-id kind)))
      (refuse! :graph-kind-invalid
               "detail :entries returns ONE group; name one the projection emits"
               {:got kind}))
    (let [model (:model (accept! "Projecting the graph was rejected"
                                 (studio-graph/project db qn)))]
      (case detail
        ;; A sorted map, and every group present even at zero: an omitted key
        ;; and a zero are the same sentence to a reader who did not expect the
        ;; group, and only one of them is true.
        :counts  {:graph qn :detail detail
                  :projected (into (sorted-map)
                                   (map (fn [k] [k (count (get model k))]))
                                   tools/projected-kinds)}
        :ids     {:graph qn :detail detail
                  :projected (into (sorted-map)
                                   (map (fn [k] [k (projected-ids model k)]))
                                   tools/projected-kinds)}
        ;; The group exactly as the projection holds it — a map keyed by id for
        ;; the keyed groups, a vector for links/resolvers/mutations. Rebuilding
        ;; one shape into the other would put an `:id` key on an attribute
        ;; entry, which dialect-v1 has no such thing as.
        :entries {:graph qn :detail detail :kind kind
                  :entries (get model kind (if (projection-id kind) [] {}))}
        ;; The projected model WHOLE — not for a caller to read (no tool
        ;; renders it; `:entries` is still the only dump face, and it is
        ;; per-kind for envelope reasons). It exists because the four-word
        ;; read-back (doc 106 §106.5) is a judgement about attributes,
        ;; resolvers, namespaces and terms TOGETHER: asking for four groups in
        ;; four calls would project the graph four times and let the four
        ;; answers come from four different pool snapshots.
        :model   {:graph qn :detail detail :model model}))))

;; ---------------------------------------------------------------------------
;; Writes
;; ---------------------------------------------------------------------------

(defn create-graph!
  "A new graph. `:members` is the recipe; `:selectors` is a convenience expanded
   against the pool right here — **a selector never reaches the recipe**, which
   is what keeps a recipe a value rather than a query whose answer drifts."
  [{:keys [conn caller now invalidate!]} {:keys [name members selectors package-name]}]
  (let [db (pool/db conn)
        workspace (require-workspace-owner! db caller)
        members (cond
                  (seq members) members
                  (seq selectors) (studio-graph/expand-selectors (pool/as-dialect db) selectors)
                  :else {})
        result (accept! "Graph creation was rejected"
                        (studio-graph/register-graph!
                         conn (cond-> {:workspace workspace :name name :members members
                                       ;; The clock is read at the EDGE and passed in
                                       ;; (D4.10-a): nothing below this line depends on
                                       ;; wall time, which is what makes it testable.
                                       :created-at (now)}
                                ;; doc 104 §8.17.7 D. Optional at creation: a
                                ;; graph earns a release name when someone
                                ;; decides to publish it, and the cut is where
                                ;; the absence is refused, not here.
                                (some? package-name) (assoc :package-name package-name))))]
    (when invalidate! (invalidate! nil))
    result))

(defn update-recipe!
  "Three different acts, told apart by which key the caller sent — `:members`
   REPLACES, `:add-selectors` ADDS, `:remove-selectors` TAKES OUT.

   They were one path once, and it replaced: a button labelled *add by prefix*
   quietly wiped the recipe and left only what the prefix matched. Naming the
   act in the request is what keeps «add» from meaning «replace» again.

   `:remove-selectors` is the mirror of `:add-selectors`, and it is a **recipe**
   edit only: it takes names out of this graph's list and touches no pool
   object (D3.19 — the recipe is the list, the pool is the objects' home). Both
   expand against the pool at the moment of the call, so neither is a standing
   rule; what the pool grows afterwards is unaffected either way."
  [{:keys [conn caller invalidate!]}
   {:keys [graph members add-selectors remove-selectors] :as request}]
  (let [db (pool/db conn)
        definition (->> (require-graph! db caller graph) (require-editable! db caller))
        current (studio-graph/normalize-members (:graph/members definition))
        expand (fn [sel] (studio-graph/normalize-members
                          (studio-graph/expand-selectors (pool/as-dialect db) sel)))
        members* (cond
                   (contains? request :members) members
                   (seq add-selectors)
                   (merge-with into current (expand add-selectors))
                   (seq remove-selectors)
                   ;; Only the groups the expansion names are touched; a group
                   ;; the prefixes match nothing in keeps every member it had.
                   (reduce-kv (fn [ms kind gone]
                                (cond-> ms
                                  (seq gone) (update kind #(into #{} (remove (set gone)) %))))
                              current
                              (expand remove-selectors))
                   :else current)
        result (accept! "Graph recipe update was rejected"
                        (studio-graph/update-recipe!
                         conn (:graph/qualified-name definition) {:members members*}))]
    (when invalidate! (invalidate! (:graph/id definition)))
    result))

(def pool-type->member-kind
  "A pool object's `:pool/type` → the recipe group that names it. `:source` has
   no group: sources enter a projection through the objects that reference one
   (scope-projection-v1 §2.5), so no recipe ever names one."
  {:namespace :namespaces
   :attribute :attributes
   :resolver  :resolvers
   :mutation  :mutations
   :link      :links
   :metric    :metrics
   :term      :terms
   :unit      :units})

(defn sweep-removed!
  "Take `removed` — `[[type id] …]`, as a removal plan reports them — out of
   every recipe that names them. Returns `[{:graph … :counts {kind n}} …]` for
   the graphs it changed, empty when there was nothing to sweep. Idempotent.

   **Deleting an object has to finish the job.** A recipe is a list of names and
   `update-recipe!` deliberately does not check that they exist — existence is
   the projection's one answer (`:scope/unknown-member`), and asking it twice
   is how two answers start to differ. The cost of that choice is that a graph
   CAN name a ghost; the choice does not say ghosts may be created quietly.
   Removing from the pool without this sweep does exactly that: every graph
   naming the object becomes un-publishable, and the refusal it eventually
   gives names the member without ever mentioning the removal that orphaned it.

   Every graph, not only the current workspace's: a name that resolves to
   nothing is wrong wherever it sits, and the removal that made it so is
   already refused if it reaches objects another workspace owns."
  [{:keys [conn]} removed]
  (let [gone (reduce (fn [m [t id]]
                       (if-let [kind (pool-type->member-kind t)]
                         (update m kind (fnil conj #{}) id)
                         m))
                     {} removed)]
    (when (seq gone)
      (vec (for [g (studio-graph/graphs (pool/db conn))
                 :let [members (studio-graph/normalize-members (:graph/members g))
                       counts  (into {} (for [[kind ids] gone
                                              :let [hit (count (filter ids (get members kind)))]
                                              :when (pos? hit)]
                                          [kind hit]))]
                 :when (seq counts)]
             (do (accept! "Sweeping removed objects out of a recipe was rejected"
                          (studio-graph/update-recipe!
                           conn (:graph/qualified-name g)
                           {:members (reduce-kv (fn [ms kind ids]
                                                  (update ms kind #(into #{} (remove ids) %)))
                                                members gone)}))
                 {:graph (:graph/qualified-name g) :counts counts}))))))

(defn set-default-query!
  "Set — or clear, with an explicit nil — the query Query Studio opens this
   graph with (owner ruling 2026-09-01).

   Here rather than in the HTTP handler for the same reason every other
   operation on this ns is: the Builder page and the MCP face must refuse an
   ill-shaped default with the same code and the same words, which is only true
   while there is one function to refuse from.

   Editable-graph, not owner: choosing what the editor opens with is an act of
   modelling, the same weight as editing the recipe. It does NOT invalidate the
   runtime cache — a compiled env does not depend on it, and invalidating on a
   setting nothing serves reads from would make every save cost a recompile."
  [{:keys [conn caller]} {:keys [graph default-query]}]
  (let [db (pool/db conn)
        definition (->> (require-graph! db caller graph) (require-editable! db caller))]
    (accept! "Default query update was rejected"
             (studio-graph/set-default-query!
              conn (:graph/qualified-name definition) default-query))))

(defn set-package-name!
  "Set — or clear, with an explicit nil — the name this graph's versions are
   published under (doc 104 §8.17.7 D).

   Editable-graph, the same weight as `set-default-query!` and the recipe: it
   is a modelling decision about what the graph is called elsewhere, and it
   changes nothing this instance serves. It does NOT invalidate the runtime
   cache — no compiled env reads it."
  [{:keys [conn caller]} {:keys [graph package-name]}]
  (let [db (pool/db conn)
        definition (->> (require-graph! db caller graph) (require-editable! db caller))]
    (accept! "Package name update was rejected"
             (studio-graph/set-package-name!
              conn (:graph/qualified-name definition) package-name))))

(defn set-default-graph!
  "The graph this workspace lands on (D3.21). Owner-only, unlike
   `set-default-query!`: that one is a setting ON a graph and follows the
   recipe's write access, while this one decides what every member of the
   workspace sees first — the same weight as membership, which is already
   owner-only."
  [{:keys [conn caller]} {:keys [graph]}]
  (let [db (pool/db conn)
        ;; The caller's own workspace, refused by the SAME guard archiving and
        ;; renaming use. A second hand-written owner check is a second answer
        ;; waiting to disagree with this one.
        workspace (require-workspace-owner! db caller)]
    (accept! "Landing graph update was rejected"
             (studio-graph/set-default-graph! conn workspace graph))))

(defn rename-graph!
  "Rename the display label, including published graphs. Keep UUID and the
   legacy deployment coordinates used by existing versions and tokens."
  [{:keys [conn caller invalidate!]} {:keys [graph name]}]
  (let [db (pool/db conn)
        definition (require-graph! db caller graph)
        _ (require-workspace-owner! db caller)
        result (accept! "Renaming the graph was rejected"
                        (studio-graph/set-display-name!
                         conn (:graph/qualified-name definition) name))]
    (when invalidate! (invalidate! (:graph/id definition)))
    result))

(defn delete-graph!
  "Permanently erase an archived Graph registration, Semantic draft/history,
   and cut-version records. The host first stops local serving and revokes the
   Graph's active data-plane tokens, and removes its local artifact copies.
   References from other retained artifacts block deletion. Deployment and
   audit evidence remain historical records."
  [{:keys [conn caller invalidate! prepare-delete!]} {:keys [graph]}]
  (locking conn
    (let [db (pool/db conn)
          definition (require-graph! db caller graph)
          _ (require-workspace-owner! db caller)]
      ;; Cleanup can interrupt serving and revoke credentials, so it must never
      ;; run before the registry has proved that this is an archived Graph.
      (when-not (= :archived (:graph/state definition))
        (refuse! :graph-archive-required
                 "Only an archived Graph can be permanently deleted"
                 {:graph (:graph/qualified-name definition)}))
      (let [cleanup (when prepare-delete! (prepare-delete! definition caller))
            result (accept! "Deleting the graph was rejected"
                            (studio-graph/delete-graph!
                             conn (:graph/qualified-name definition) version/deletion-tx))
            result (cond-> result cleanup (assoc :runtime-cleanup cleanup))]
        (when invalidate! (invalidate! (:graph/id definition)))
        result))))

(defn admin-retire-graph!
  "Archive (if still active) and permanently delete a graph in ANY workspace,
   for a Studio administrator (owner 2026-10-04). The host proves
   `identity/admin?` before calling; this fn skips the membership guards on
   purpose, because the case it exists for is a graph in a workspace no
   account can enter (the pre-identity `demo` seed, owned only by the retired
   `studio/local-builder` principal). Everything else — serving stop, token
   revocation, the registry's own refusals (cut versions referenced by other
   artifacts) — is the same path `delete-graph!` takes."
  [{:keys [conn invalidate! prepare-delete!]} {:keys [graph actor]}]
  (locking conn
    (let [definition (or (studio-graph/graph (pool/db conn) graph)
                         (refuse! :graph-unknown "Unknown Studio graph" {:graph graph}))
          qn (:graph/qualified-name definition)]
      (when (= :active (:graph/state definition))
        (accept! "Archiving was rejected" (studio-graph/archive-graph! conn qn)))
      (let [cleanup (when prepare-delete!
                      (prepare-delete! (assoc definition :graph/state :archived)
                                       {:workspace (:graph/workspace definition) :principal actor}))
            result (accept! "Deleting the graph was rejected"
                            (studio-graph/delete-graph! conn qn version/deletion-tx))]
        (when invalidate! (invalidate! (:graph/id definition)))
        (cond-> (assoc result :graph qn :workspace (:graph/workspace definition))
          cleanup (assoc :runtime-cleanup cleanup))))))

(defn- set-graph-state-impl!
  "Archive or restore. One function for both because they are one decision with
   two values — split in two, the guard would be written twice."
  [{:keys [conn caller invalidate!]} {:keys [graph state]}]
  (when-not (contains? #{:archived :active} state)
    (refuse! :graph-state-invalid "state must be :archived or :active" {:got state}))
  (let [db (pool/db conn)
        definition (require-graph! db caller graph)
        _ (require-workspace-owner! db caller)
        qn (:graph/qualified-name definition)
        result (accept! (if (= :archived state) "Archiving was rejected" "Restoring was rejected")
                        (if (= :archived state)
                          (studio-graph/archive-graph! conn qn)
                          (studio-graph/restore-graph! conn qn)))]
    (when invalidate! (invalidate! (:graph/id definition)))
    result))

(defn- workspace-user!
  "Email → the principal it names, or a refusal.

   Access is granted to a PERSON, and the handle a person is known by in this
   Builder is their email; `{:issuer … :subject …}` is an identity-provider
   detail. The workspace-membership routes already take an email for exactly
   this reason, and a second handle for the same person would be a second way
   to get it subtly wrong — a mistyped subject grants nobody and says so like a
   success.

   The refusal carries **no** `:email`: it is unvalidated caller input, and
   echoing it into a finding is what finding-v1 §5 forbids."
  [db email]
  (or (some-> (identity/user-by-email db email) identity/public-user :principal)
      (refuse! :studio-user-not-found "No Studio user has that email address" {})))

(defn grant-graph-access!
  "Make one workspace member an editor of ONE graph. An upsert, so re-granting
   leaves one row rather than two that disagree.

   `:viewer` retired 2026-08-30 (it could not change a decision), and a grant to
   somebody whose workspace role already edits every graph is refused rather
   than filed — both in `studio/graph`, so both doors refuse identically.

   Requires the **workspace owner** — see the ns docstring on why not a graph
   editor. Everything else is `studio/graph`'s: it refuses an outsider (add the
   workspace membership first), an unknown graph, and a malformed role, and
   those findings travel whole."
  [{:keys [conn caller]} {:keys [graph email role]}]
  (let [db (pool/db conn)
        definition (require-graph! db caller graph)
        _ (require-workspace-owner! db caller)
        principal (workspace-user! db email)]
    (accept! "Granting graph access was rejected"
             (studio-graph/grant-graph-access!
              conn (:graph/qualified-name definition)
              {:principal principal :role (keyword role)}))))

(defn revoke-graph-access!
  "Remove one explicit grant. The person keeps their workspace membership and
   therefore keeps SEEING the graph — visibility is a property of the
   workspace, not of the grant (`studio/graph`: «all workspace members see all
   team graphs»). Revoking here is not a way to hide a graph from someone.

   Idempotent by construction: `studio/graph` removes a grant that matches and
   is content when none does, for the same reason revoking a token twice is not
   an error — the caller's intent is already true."
  [{:keys [conn caller]} {:keys [graph email]}]
  (let [db (pool/db conn)
        definition (require-graph! db caller graph)
        _ (require-workspace-owner! db caller)
        principal (workspace-user! db email)]
    (accept! "Revoking graph access was rejected"
             (studio-graph/revoke-graph-access!
              conn (:graph/qualified-name definition) principal))))

(defn graph-access
  "The explicit grants on one graph, with each principal resolved back to the
   person it names. A read, so visibility is enough — the panel that shows who
   may edit is exactly what an owner needs before deciding to change it.

   **Explicit grants only.** A workspace owner or editor is not listed here:
   their authority comes from the workspace and is not a row anyone could
   revoke on this graph. Listing them would invite trying."
  [{:keys [conn caller]} {:keys [graph]}]
  (let [db (pool/db conn)
        definition (require-graph! db caller graph)
        by-principal (into {} (map (juxt :principal identity)
                                   (map identity/public-user (identity/users db))))]
    {:graph (:graph/qualified-name definition)
     :roles (vec (sort (map name studio-graph/graph-access-roles)))
     :access (->> (:graph/access definition)
                  (mapv (fn [{:keys [principal role]}]
                          (let [u (by-principal principal)]
                            (cond-> {:role role}
                              u (assoc :email (:email u) :name (:name u))))))
                  (sort-by (juxt :email))
                  vec)}))

(defn- unique-name!
  "`orders` is taken, so try `orders-copy`, `orders-copy-2`, … giving up at the
   50th and saying so.

   An operation that INVENTS a name must be able to stop: without the bound it
   would spin in a pool full of collisions until something else timed out, and
   a timeout does not tell the caller what went wrong."
  [db workspace base]
  (let [taken? #(some? (studio-graph/graph db (str workspace "/" %)))]
    (or (first (remove taken? (cons base (map #(if (= 1 %) (str base "-copy")
                                                   (str base "-copy-" %))
                                              (range 1 51)))))
        (throw (ex-info "Could not find a free name for the copy"
                        {:code :graph-name-exhausted :base base})))))

(defn duplicate-graph!
  "One recipe, a second graph. **What is copied is the LIST, not the objects**
   (D3.19): the pool holds exactly one of each, and the two graphs point at the
   same batch — which is what a list is for.

   Requires the workspace owner, the same as creating one: a copy is a new
   registration. The source only has to be visible to the caller, which
   `require-graph!` establishes.

   `name` is a wish, not a guarantee — a taken name becomes `-copy`."
  [{:keys [conn caller now]} {:keys [graph name]}]
  (let [db (pool/db conn)
        source (require-graph! db caller graph)
        workspace (require-workspace-owner! db caller)]
    (accept! "Duplicating the graph was rejected"
             (studio-graph/register-graph!
              conn {:workspace workspace
                    :name (unique-name! db workspace
                                        (or (not-empty name) (:graph/name source)))
                    :members (:graph/members source)
                    :created-at (now)}))))

(defn merge-graphs!
  "Several graphs → one new graph, its recipe the **union**.

   With references retired (D3.17) this is the only shape «put A and B
   together» can have, and the right one: one pool, one batch of objects,
   union and dedupe — there is no semantic conflict to arbitrate.

   Every source is checked by `require-graph!` in turn, so a merge cannot be
   used to read a graph the caller cannot see."
  [{:keys [conn caller now]} {:keys [graphs name]}]
  (let [db (pool/db conn)
        workspace (require-workspace-owner! db caller)
        _ (when (< (count graphs) 2)
            (refuse! :graph-merge-needs-two "Merging takes at least two graphs"
                     {:graphs (vec graphs)}))
        sources (mapv #(require-graph! db caller %) graphs)
        members (reduce (fn [acc g]
                          (merge-with into acc (studio-graph/normalize-members
                                                (:graph/members g))))
                        {} sources)]
    (accept! "Merging the graphs was rejected"
             (studio-graph/register-graph!
              conn {:workspace workspace
                    :name (unique-name! db workspace (or (not-empty name) "merged"))
                    :members members
                    :created-at (now)}))))

(defn- apply-member-edit
  "One `{:kind :ids :in?}` against a normalized recipe. `:ids` arrive printed,
   because that is the only shape a keyword, a string and a link's `[from to]`
   pair all survive a JSON-less HTTP hop in."
  [members {:keys [kind ids in?]}]
  (let [k   (keyword kind)
        ids (mapv #(if (string? %) (edn/read-string %) %) ids)]
    (update members k (fn [cur] (reduce (if in? conj disj) (or cur #{}) ids)))))

(defn set-members!
  "Tick or untick recipe members. `:edits` is a vector of
   `{:kind :ids :in?}`; the single-kind `{:kind :ids :in?}` form is still
   accepted and means one edit.

   The whole recipe is rewritten from the edited set, because a recipe is a
   value — sending a delta would need a second shape saying what a delta means,
   and the two would drift.

   **Several kinds must arrive as ONE call, not several.** Ticking an entity is
   three kinds at once (its namespace, its attributes, its resolvers), and this
   function is read-modify-write over the whole value: three concurrent calls
   each read the same recipe and each write it back whole, so the last one to
   commit silently discards the other two. That is a lost update with no
   conflict to report — the modeller sees a tick that did not take. One call,
   one read, one write is what makes the gesture atomic."
  [{:keys [conn caller invalidate!]} {:keys [graph kind ids in? edits]}]
  (let [db (pool/db conn)
        definition (->> (require-graph! db caller graph) (require-editable! db caller))
        edits (or (seq edits) [{:kind kind :ids ids :in? in?}])
        members (reduce apply-member-edit
                        (studio-graph/normalize-members (:graph/members definition))
                        edits)
        result (accept! "Graph recipe update was rejected"
                        (studio-graph/update-recipe!
                         conn (:graph/qualified-name definition) {:members members}))]
    (when invalidate! (invalidate! (:graph/id definition)))
    result))

;; The editor-level guard, exported for the source/asset routes that need the
;; same answer. Kept here so «who may write in this workspace» has one
;; definition rather than one per caller.
(defn require-workspace-editor! [{:keys [conn caller]}]
  (workspace-editor! (pool/db conn) caller))

(defn set-graph-state! [ctx request]
  (locking (:conn ctx) (set-graph-state-impl! ctx request)))
