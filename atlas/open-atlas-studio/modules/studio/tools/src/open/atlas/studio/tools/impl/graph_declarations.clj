(ns open.atlas.studio.tools.impl.graph-declarations
  "The **graph registry** tools ([8.4 P2](../../../../../../../docs/design/84-builder-mcp.md)).

   A graph is what an agent's work becomes usable through: the pool holds every
   object every workspace ever modelled, and a graph is the list saying which of
   them this consumer gets (D3.19). An agent that can enrich a model but cannot
   put the result into a graph has done half a job and must hand the other half
   to a human at the exact moment the context is freshest.

   ## These declarations hold no authority

   Every handler here is one line: it calls `(:graph-service ctx)`. The service
   lives in the host (`apps/studio/graph_service`) and is the SAME function the
   Builder's own HTTP routes call, so «who may archive a graph» is decided in
   one place for both entrances.

   Putting the role checks here instead would state them twice — and the day a
   role is added, the copy nobody edited keeps enforcing yesterday's rule while
   still looking correct. That is the drift a single registry exists to
   prevent (principles「MCP 与工具面」).

   ## Why the service arrives through ctx and not a require

   `studio/tools` may not depend on `apps/studio` — nothing in `modules` may
   depend on an app. The host injects the service when it assembles the
   registry, exactly as it injects the pool connection and the trusted
   provenance. The dependency direction stays `app → tools`.

   ## The caller is not an argument

   No tool here takes a workspace, an actor or a role. All three come from the
   credential this connection authenticated with — a tool that accepted a
   workspace would let a token issued for one workspace act in another, which
   is the whole boundary these tokens exist to draw.

   ## Three tools, not thirteen (8.4.16 裁定九)

   `list_graphs`, `inspect_graph` and `create_graph`. Everything governance —
   renaming, archiving, deleting, duplicating, merging,
   granting and revoking access — went back to the GUI: they are rare, they
   are a person's decision, and on the tool face they were mostly names an
   agent could pick by mistake. Their service functions are all still here and
   still called by the Builder's own routes."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.tools.impl.finding :as f]
            [open.atlas.studio.tools.impl.repair :as repair]))

(defn- service
  "The host's graph service, or a structured refusal saying it is absent.

   A host may legitimately mount the modelling tools without the graph ones
   (a headless pool with no registry, say). Absent, every graph tool refuses by
   name rather than throwing a NullPointerException that reads like a bug in
   the caller's request."
  [ctx]
  (or (:graph-service ctx)
      (throw (ex-info "no graph service" {::absent true}))))

(defn- call
  "Run one service operation, turning the host's `ex-info` refusals into
   finding-v1.

   The service refuses with `{:code …}` and, when `studio/graph` itself
   rejected, the finding vector under `:error`. Those findings travel WHOLE:
   they already name the position and the reason, and re-wording them here
   would be this layer inventing a second vocabulary for the same failure."
  [ctx op args]
  (try
    (op (service ctx) args)
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        (cond
          (::absent d)
          {:findings [(f/make :tools.graph/unavailable :error [:graph]
                              "this host does not serve the graph registry")]}

          (seq (get-in d [:error :findings]))
          {:findings (vec (get-in d [:error :findings]))}

          :else
          {:findings [(f/make :tools.graph/refused :error [:graph]
                              (ex-message e)
                              :got (select-keys d [:code :graph :workspace
                                                   :graph-workspace :state]))]})))))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(def list-graphs-tool
  {:name "list_graphs"
   :description
   (str "The graphs of the workspace this credential belongs to: id, name, state, the "
        "recipe's membership, and whether you may edit each one. Archived graphs are "
        "omitted unless asked for — an archived graph is one someone deliberately stopped "
        "looking at. The workspace is NOT a parameter: it is the one this credential was "
        "issued for.")
   :capability :read
   :params
   [{:name "include_archived" :type :boolean :required? false
     :description "Include archived graphs as well as active ones."}
    {:name "with_guide" :type :boolean :required? false
     :description (str "Also return each graph's label and usage guide (purpose, asset scope, "
                       "applicability, limitations) from its Semantic Contract draft, where it has one.")}]
   :handler
   (fn [ctx args]
     (call ctx (fn [svc a] ((:list-graphs svc) a))
           {:include-archived? (boolean (:include_archived args))
            :with-guide? (boolean (:with_guide args))}))})

(def projected-kinds
  "The groups a scope projection can put in its `:model`, in a fixed order
   (8.4.10; `studio/graph`'s `project`, step 10).

   **Nine, and there is no tenth to look for.** Before D2.29 a reader of D2.16
   could reasonably expect a `:navigations` group and find none; navigations
   were a registry of their own, closed inside each namespace's entry rather
   than emitted separately. D2.29 retired that registry — a join is an entry of
   `:attributes` like any other — so joins are not a missing group here, they
   are already counted in `:attributes`.

   Exported on `open.atlas.studio.tools` and read by the host's service rather
   than written out twice: the enum a client reads and the groups the service
   counts have to be the same words (CLAUDE.md「摆到接口上，不要各写一份」)."
  [:namespaces :attributes :resolvers :mutations :links
   :metrics :sources :terms :units])

(defn- other-service
  "The NEIGHBOURING service `inspect_graph` reads — the publish service, for
   the serving pointer.

   It is reached the same way the graph service is (injected by the host), and
   a host that mounts one and not the other is legitimate: the summary then
   carries the absence as a finding rather than pretending the graph serves
   nothing."
  [ctx k op args on-absent]
  (if-let [svc (k ctx)]
    (try (op svc args)
         (catch clojure.lang.ExceptionInfo e
           (let [d (ex-data e)]
             (if (seq (get-in d [:error :findings]))
               {:findings (vec (get-in d [:error :findings]))}
               {:findings [(f/make :tools.graph/refused :error [:graph]
                                   (ex-message e)
                                   :got (select-keys d [:code :graph :workspace :state]))]}))))
    {:findings [(f/make on-absent :warning [:graph]
                        "this host does not serve that half of the answer")]}))

(defn- compile-section
  "**The resolvers this graph cannot compile** (8.4.19 裁定十三 ②).

   A resolver the engine cannot compile is not a smaller graph: it is present
   in the draft, absent from every query, and the engine says so only as a
   compile-time warning nobody reads. The incident behind the ruling spent a
   whole session on «the tool says my resolver is there and the query says the
   path does not exist».

   The findings are the ENGINE's, carried whole — this face adds a count and
   no vocabulary of its own. A host that does not compile (no embedded
   runtime) says so rather than reporting zero: «none» and «not asked» are
   different answers, and only one of them means the graph is well."
  [ctx graph seen]
  (if-let [op (:compile-status (:graph-service ctx))]
    (let [r (try (op {:graph graph})
                 (catch clojure.lang.ExceptionInfo e
                   (let [d (ex-data e)]
                     (if (seq (get-in d [:error :findings]))
                       {:findings (vec (get-in d [:error :findings]))}
                       {:findings [(f/make :tools.graph/refused :error [:graph]
                                           (ex-message e)
                                           :got (select-keys d [:code :graph :workspace]))]}))))
          ;; 8.4.21 裁定十五 ①: every defect the machine can repair carries its
          ;; repair, from the ONE function that infers it — this face computes
          ;; nothing of its own about links.
          rctx (repair/context ctx graph)
          bad (mapv (fn [e]
                      (cond-> e
                        (map? (:finding e))
                        (update :finding repair/repair-for rctx)))
                    (:uncompilable r))]
      {:uncompilable (count bad)
       :resolvers bad
       ;; A graph that does not PROJECT does not compile either, and the
       ;; projection half of this summary has already said why. Saying it
       ;; twice would read as two faults.
       :findings (-> (into [] (remove #(contains? seen [(:code %) (:path %) (:message %)]))
                           (:findings r))
                     (repair/attach rctx))})
    {:findings [(f/make :tools.graph/unavailable :warning [:graph]
                        "this host does not compile graphs, so it cannot say which resolvers fail to")]}))

(defn- things-section
  "**view=things** — the graph read back as thing / attribute / relation /
   concept (doc 106 §106.5, P8a).

   The criterion is `dialect/need-view`'s, and this face computes nothing of
   its own about what a thing is: §106.5 requires the Builder and the data
   plane's `list_entities` to answer from ONE implementation, or the same graph
   is five things here and six entities there.

   The two defects it can name — an identity nothing declared, a relation
   hanging on the child namespace — carry their repair, from the same
   `impl.repair` every other face uses (8.4.21 裁定十五 ①: 一个形状，一个产生处)."
  [ctx graph]
  (let [projected (call ctx (fn [svc a] ((:project-graph svc) a))
                        {:graph graph :detail :model})]
    (if (seq (filter #(= :error (:severity %)) (:findings projected)))
      {:things [] :fragments [] :concepts []
       :findings (vec (:findings projected))}
      (let [model (:model projected)
            ;; What the engine writes into each thing's rows rather than
            ;; reading out of a record — the second half of a key since doc 106
            ;; §106.14 item 18. Asked of the SPI, exactly as the engine asks it:
            ;; two derivations would make `[host id]` here and `[id]` on the
            ;; data plane, the split §106.5 forbids.
            view  (dialect/need-view model (spi/carried-attributes model))
            rctx  (repair/context ctx graph)]
        (assoc view :findings (-> (vec (concat (:findings projected) (:findings view)))
                                  (repair/attach rctx)))))))

(defn- counts-of
  "`{kind #{id …}} → {kind n}` — the recipe's numbers, in the projection's own
   group order so the two columns of the summary line up."
  [members]
  (into {} (for [k projected-kinds] [k (count (get members k))])))

(def inspect-graph-tool
  "**One graph, three views** (8.4.16 裁定九; the catalog view retired in
   8.4.18 裁定十一; `things` added by doc 106 §106.7 P8a).

   It merges what used to be three tools: `graph_members` (the recipe = the
   SEED), `project_graph` (the harvest) and `serving_status` (the pointer).

   **The catalog view is gone because the data plane's own tools are mounted
   here now** (裁定十一 ②): `view=catalog` WAS `list_entities` /
   `list_attributes` with a different name, and 裁定九's «fewer tools» meant
   fewer second ways of saying a thing, not fewer names. An agent that already
   knows those two tools from the data plane now uses them here, with a
   `graph`.

   **The summary puts the seed and the harvest side by side**, which is the
   whole reason 8.4.10 could split them and 裁定九 can join them again: the
   incident behind both was a recipe with `resolvers: []` read as «this graph
   has no resolver». Two numbers in one response make that misreading
   impossible, so the split that guarded it is no longer earning its second
   tool.

   The summary and the entries view are the graph's REGISTRATION and its
   projection — the two questions no data-plane tool answers."
  {:name "inspect_graph"
   :description
   (str "Everything about ONE graph, in two views.\n\n"
        "view=summary (the default) puts three answers side by side: what the RECIPE names "
        "(the seed, per kind), what the graph would actually CONTAIN if cut right now (the "
        "harvest, per kind — resolvers, links, metrics and sources are pulled in by closure "
        "even when the recipe names none), which of its resolvers the engine CANNOT compile "
        "(:compile — a resolver that fails to compile is present in the draft and absent from "
        "every query, so read this before believing a path exists), and where the serving "
        "pointer is. Read them "
        "together: recipe resolvers 0 with projected resolvers 12 is an ordinary graph built "
        "from selectors, NOT an empty one.\n\n"
        "To learn how to QUERY it, use list_entities / list_attributes / search_attributes "
        "with this graph's name — the same tools the data plane serves, over this graph's "
        "draft.\n\n"
        "view=entries returns ONE group's full projected entries and REQUIRES `kind` — a "
        "whole-model dump is over this face's envelope.\n\n"
        "view=things reads the graph back in the four words a person uses: THINGS (a kind of "
        "object you can identify, with its attributes and its relations — 'has many' or "
        "'belongs to'), CONCEPTS (a meaning several attributes share), and FRAGMENTS (what "
        "reads back as neither, each saying why). A thing with a root collection is marked "
        "list? — that is where a query starts. Read this before modelling: the fragments are "
        "the distance between this graph and the language a person asks in, and the findings "
        "carry their repair.\n\n"
        "The projection is the DRAFT as of this call, not a published version's content; "
        "the serving block is what consumers see.")
   :capability :read
   :params
   [{:name "graph" :type :string :required? false
     :description "Qualified graph name, as list_graphs reports it. Omit it to use the workspace's default graph."}
    {:name "view" :type :keyword :required? false
     :description "Which view. Defaults to summary."
     :enum [:summary :entries :things]}
    {:name "kind" :type :keyword :required? false
     :description "view=entries only: which group's entries to return. Required with that view."
     :enum projected-kinds}]
   :handler
   (fn [ctx args]
     (let [graph (:graph args)
           view (or (:view args) :summary)]
       (case view
         :things (things-section ctx graph)

         :entries
         ;; The refusal is made here rather than in the service for the same
         ;; reason `:tools.graph/unavailable` is: the projection is happy to
         ;; hand over the whole model — it is this face's envelope refusing an
         ;; unbounded dump (CLAUDE.md「超限拒绝而非截断」).
         (if-not (contains? args :kind)
           {:findings [(f/make :tools.graph/entries-need-kind :error [:kind]
                               (str "view=entries returns one kind's entries; naming no kind "
                                    "would mean the whole projected model, which is over this "
                                    "face's envelope")
                               :expected (mapv str projected-kinds))]}
           (call ctx (fn [svc a] ((:project-graph svc) a))
                 {:graph graph :detail :entries :kind (:kind args)}))

         :summary
         (let [recipe (call ctx (fn [svc a] ((:graph-members svc) a)) {:graph graph})]
           (if (seq (filter #(= :error (:severity %)) (:findings recipe)))
             ;; A graph this credential may not see never reaches the other
             ;; two services: the registry's refusal is the whole answer and
             ;; travels alone.
             recipe
             (let [members (or (:members recipe) (:graph/members recipe) {})
                   projected (call ctx (fn [svc a] ((:project-graph svc) a))
                                   {:graph graph :detail :counts})
                   compiled (compile-section ctx (or (:graph recipe) graph)
                                             (into #{} (map (juxt :code :path :message))
                                                   (concat (:findings recipe)
                                                           (:findings projected))))
                   serving (other-service ctx :publish-service
                                          (fn [svc a] ((:serving-status svc) a))
                                          {:graph graph} :tools.publish/unavailable)]
               {:graph (or (:graph recipe) graph)
                ;; The seed: what the recipe NAMES, both as numbers and as the
                ;; ids themselves — nothing else on this face reports them,
                ;; and «which attribute did I file here» is a question the
                ;; harvest cannot answer.
                :recipe {:counts (counts-of members) :members members}
                ;; The harvest, in the SAME group order. `project-graph` with
                ;; `:detail :counts` answers `{:graph :detail :projected {…}}`
                ;; (the same envelope `:detail :entries` needs its `:kind` in)
                ;; — unwrapped here so a caller reads counts straight off
                ;; `:projected` instead of `:projected :projected`.
                :projected (:projected projected)
                ;; The third column the ruling added: what the harvest would
                ;; not RUN. Counted beside the other two on purpose — a
                ;; resolver in `:projected` that is not compilable is exactly
                ;; the pair of numbers an agent has to see together.
                :compile compiled
                :serving serving
                :findings (vec (concat (:findings recipe) (:findings projected)
                                       (:findings compiled) (:findings serving)))}))))))})

;; ---------------------------------------------------------------------------
;; Writes
;; ---------------------------------------------------------------------------

(def create-graph-tool
  {:name "create_graph"
   :description
   (str "Register a new graph in this credential's workspace. Requires the workspace "
        "OWNER. Give members as an explicit object list, or selectors as a convenience — "
        "selectors are expanded against the pool at once and the expansion is what is "
        "stored, so a recipe never becomes a query whose answer drifts under it.")
   :capability :write
   :params
   [{:name "name" :type :string :required? true
     :description "The graph's name within the workspace."}
    {:name "members" :type :map :required? false
     :description "The recipe: {:attributes #{…} :resolvers #{…} …} of object ids."}
    {:name "selectors" :type :vector :required? false
     :description "Namespace selectors (e.g. [\"cis.party.*\"]) expanded into members now."}]
   :handler
   (fn [ctx args]
     (call ctx (fn [svc a] ((:create-graph! svc) a))
           (select-keys args [:name :members :selectors])))})

;; `update_recipe` **退役**（8.4.14 裁定八，owner 2026-09-03）。整体替换一张
;; 对象清单是按 id 说话的动作，正是意图工具要吃掉的那一类；接替它的是
;; `change_graph_members`（`intent_declarations`，8.4.16 裁定九把 `add_to_graph` /
;; `remove_from_graph` 也合成了它），它按对象**名字**收，菜谱增量在工具内部算。
;; 按前缀圈图的入口仍在 `create_graph`（D3.19 的定格语义不动）。
;;
;; 服务侧的 `update-recipe!` 没有退役：Builder 自己的 HTTP 路由还在用它，
;; 意图工具的菜谱增量也落到它身上——退役的是**那条 MCP 工具**，不是那个操作。

(def declarations
  "Fixed order, same reason as the modelling declarations: two `tools/list`
   calls are byte-identical so a client can diff them.

   **The governance tools are gone** (8.4.16 裁定九): rename / archive /
   restore / delete / duplicate / merge and the two access
   grants are administration a person does in the GUI, and every one of them was a
   name an agent could pick by mistake while modelling. The SERVICE functions
   are untouched — the Builder's own HTTP routes still call them; what was
   deleted is the MCP declaration."
  [list-graphs-tool inspect-graph-tool create-graph-tool])

;; ---------------------------------------------------------------------------
;; Saved queries (proposal studio-saved-queries §3.2, owner 2026-10-10)
;; ---------------------------------------------------------------------------
;;
;; The owner's scenario: an agent debugging a graph finds a query that keeps
;; failing, or one complex enough that nobody should write it twice, makes it
;; work — and SAVES it, so every later consumer runs it by name. These three
;; tools write the DRAFT; `publish_graph` publishes it with the next version,
;; and only then does the data plane's `list_saved_queries` show it.
;;
;; Same discipline as the tools above: one line each into the host's graph
;; service, which owns the guards, the shape check and the verifying run.

(def list-saved-queries-tool
  {:name "list_saved_queries"
   :description
   (str "The DRAFT saved queries of a graph, with their EQL: what the next publish will carry. "
        "A saved query is a named, described EQL query with typed parameters that consumers "
        "(agents through the data plane, applications through /eql) run by name instead of "
        "writing EQL. Check here before saving one, so you extend an existing query rather than "
        "adding a near-duplicate.")
   :capability :read
   :params [{:name "graph" :type :string :required? false
             :description "Qualified graph name. Omit it to use the workspace's default graph."}]
   :handler (fn [ctx args] (call ctx (fn [svc a] ((:list-saved-queries svc) a)) args))})

(def save-saved-query-tool
  {:name "save_saved_query"
   :description
   (str "Save a query into a graph's draft as a SAVED QUERY, or replace the one with the same name.\n\n"
        "WHEN TO PROPOSE ONE: while debugging or answering, you hit a query that kept failing until "
        "you found the right shape, or one complex enough (several joins, parameters, a specific "
        "order or filter) that the next agent would struggle to write it. Tell the user what you "
        "would save and why, and save it when they agree. Do not save one-off lookups.\n\n"
        "WHAT TO GIVE: a snake_case `name`; a `description` saying what question it answers and "
        "when to use it — consumers choose by this text, so write it for them; `params`, each "
        "{name, type, required, description, example} with type one of string, integer, number, "
        "boolean, date (YYYY-MM-DD), datetime, keyword; and `eql`, the EDN text of the query with "
        "every parameter written as ?name where a VALUE goes, e.g. "
        "{:entity {:acme.invoice/id ?id} :query [:acme.invoice/amount]} or "
        "[{(:acme.invoice/all {:due_before ?as_of :limit 50}) [:acme.invoice/id]}]. Keys, attribute "
        "names and joins cannot be parameters. An optional parameter must stand as a map value; "
        "when a caller omits it, that entry is dropped.\n\n"
        "IT IS CHECKED BEFORE IT IS KEPT: every attribute must exist in the draft, and the query is "
        "RUN against the draft with the examples (give an example for every required parameter). "
        "A refusal carries the same finding execute_eql would give; fix and save again. "
        "verify=false skips the run (the attribute check still applies).\n\n"
        "It is saved to the DRAFT. publish_graph with apply=true publishes it with the next version "
        "(a change to saved queries alone is enough for a new version); from then on the data "
        "plane's list_saved_queries / run_saved_query serve it.")
   :capability :write
   :params [{:name "graph" :type :string :required? false
             :description "Qualified graph name. Omit it to use the workspace's default graph."}
            {:name "name" :type :string :required? true
             :description "snake_case, at most 64 characters, unique in the graph."}
            {:name "description" :type :string :required? true
             :description "What question it answers and when to use it. Consumers pick by this."}
            {:name "params" :type :vector :items :object :required? false
             :description "[{name, type, required, description, example}] — one per ?placeholder."}
            {:name "eql" :type :string :required? true
             :description "The query as EDN text, parameters as ?name in value positions."}
            {:name "verify" :type :boolean :required? false
             :description "Run the query against the draft with the examples before saving. Default true."}]
   :handler (fn [ctx args] (call ctx (fn [svc a] ((:save-saved-query! svc) a)) args))})

(def delete-saved-query-tool
  {:name "delete_saved_query"
   :description
   (str "Remove a saved query from a graph's draft. Versions already published keep theirs; the "
        "next publish no longer carries it, and consumers calling it by name will be refused — say "
        "so to the user before deleting one that applications may use.")
   :capability :write
   :params [{:name "graph" :type :string :required? false
             :description "Qualified graph name. Omit it to use the workspace's default graph."}
            {:name "name" :type :string :required? true
             :description "The saved query's name."}]
   :handler (fn [ctx args] (call ctx (fn [svc a] ((:delete-saved-query! svc) a)) args))})

(def list-recent-queries-tool
  {:name "list_recent_queries"
   :description
   (str "What consumers recently sent to this graph's PUBLISHED data plane — agents over MCP, "
        "applications and Workbench over /eql — newest first: the EQL (or saved query and "
        "parameters), the outcome, the refusal's findings, latency, the token and, when Workbench "
        "vouched for one, the user. Never result data.\n\n"
        "USE IT TO DEBUG: layer \"failed\" shows the refused requests (kept apart, so heavy "
        "successful traffic cannot push them out); reproduce one with execute_eql on the draft, "
        "fix the model or the query, and if the right query is worth keeping, offer to save it "
        "with save_saved_query. A refusal a consumer received carries :studio/recent-id — pass it "
        "as id to read exactly that request.\n\n"
        "Memory only for \"all\" and \"failed\" (the last 50 per graph each, lost on restart); "
        "\"pinned\" is what a person or a capture rule kept, for 7 days.")
   :capability :read
   :params [{:name "graph" :type :string :required? false
             :description "Qualified graph name. Omit it to use the workspace's default graph."}
            {:name "layer" :type :string :required? false :enum ["all" "failed" "pinned"]
             :description "Which list. Default \"failed\"."}
            {:name "id" :type :string :required? false
             :description "One request's id (a :studio/recent-id). Returns just that request."}
            {:name "limit" :type :integer :required? false
             :description "At most this many entries. Default 20."}]
   :handler
   (fn [ctx args]
     (if (:id args)
       (call ctx (fn [svc a] {:entry ((:recent-query svc) a)}) (select-keys args [:graph :id]))
       (call ctx (fn [svc a]
                   (let [r ((:recent-queries svc) a)
                         layer (keyword (or (:layer args) "failed"))]
                     {:graph (:graph r)
                      :layer (name layer)
                      :entries (vec (take (or (:limit args) 20) (get r layer)))
                      :counts {:all (count (:all r)) :failed (count (:failed r)) :pinned (count (:pinned r))}}))
             (select-keys args [:graph]))))})

;; ---------------------------------------------------------------------------
;; Graph tokens (owner ruling 2026-10-11, revising P3 ruling ③)
;; ---------------------------------------------------------------------------
;;
;; P3 kept issuing a graph token as «the one human step the loop keeps». The
;; owner reversed it: an agent holding a workspace token may issue every kind
;; of graph token, Workbench service tokens included. The authority did not
;; move — these call the very functions the Builder page's token panel calls,
;; with the editor bar, the ledger and the audit unchanged; only the caller
;; comes from the key instead of the session.

(def list-graph-tokens-tool
  {:name "list_graph_tokens"
   :description
   (str "The graph tokens issued on this Studio for your workspace's graphs: token id, graph, "
        "status, expiry, whether it is a Workbench service token, and which graphs you may issue "
        "for or revoke on. Never a secret — a secret is shown once, when issued.")
   :capability :read
   :params [{:name "graph" :type :string :required? false
             :description "Only this graph's tokens. Omit for every graph in the workspace."}]
   :handler (fn [ctx args] (call ctx (fn [svc a] ((:graph-tokens svc) a)) (select-keys args [:graph])))})

(def issue-graph-token-tool
  {:name "issue_graph_token"
   :description
   (str "Issue a graph token: the credential a consumer uses to read this graph on the data plane "
        "(/eql and the data-plane MCP at /mcp/<graph-uuid>). It follows the graph's serving "
        "version. Requires owner or editor on the graph.\n\n"
        "THE SECRET IS RETURNED ONCE, in this result, and never again. Hand it to exactly the "
        "consumer that needs it (an agent's MCP config, an application's secret store) and do not "
        "repeat it elsewhere in the conversation. Ask the user before issuing one, and say who it is "
        "for.\n\n"
        "workbench=true issues a Workbench service token: it may name the signed-in user it reads "
        "for (on-behalf-of), so the graph can filter by user. Use it only for Workbench; leave it off "
        "for agents. expires_at (an ISO instant) is optional but recommended. Revoke with "
        "revoke_graph_token.")
   :capability :write
   :params [{:name "graph" :type :string :required? false
             :description "Qualified graph name. Omit it to use the workspace's default graph."}
            {:name "workbench" :type :boolean :required? false
             :description "Issue a Workbench service token (may name the user it reads for). Default false."}
            {:name "expires_at" :type :string :required? false
             :description "When it stops working, e.g. 2027-01-01T00:00:00Z. Omit for no expiry."}]
   :handler
   (fn [ctx args]
     (call ctx (fn [svc a] ((:issue-graph-token! svc) a))
           (cond-> {:graph (:graph args)}
             (contains? args :workbench) (assoc :asserts-user? (boolean (:workbench args)))
             (:expires_at args) (assoc :expires-at (:expires_at args)))))})

(def revoke-graph-token-tool
  {:name "revoke_graph_token"
   :description
   (str "Revoke a graph token by id (list_graph_tokens shows the ids). Its consumer is refused from "
        "the next request on. Requires owner or editor on that token's graph; an unknown id and one "
        "you may not touch are refused alike. Say so to the user before revoking a token a running "
        "consumer uses.")
   :capability :write
   ;; `graph_token_id`, not `token_id`: that name is reserved for provenance and
   ;; no tool may take it (builder_mcp_test a-client-cannot-say-where-it-came-from).
   :params [{:name "graph_token_id" :type :string :required? true
             :description "The graph token's id, as list_graph_tokens reports it."}]
   :handler (fn [ctx args] (call ctx (fn [svc a] ((:revoke-graph-token! svc) a)) {:token-id (:graph_token_id args)}))})

;; ---------------------------------------------------------------------------
;; Credentials (owner ruling 2026-10-11)
;; ---------------------------------------------------------------------------
;;
;; The credentials page's own functions, bound to the token's caller: the same
;; editor bar, the same store. A value goes in and never comes back out — the
;; list reports names, where they are referenced and whether they are held.

(defn- scope-has? [ctx k] (contains? (set (or (:scope ctx) (:capabilities ctx))) k))

(def list-credentials-tool
  {:name "list_credentials"
   :description
   (str "Every ${VAR} credential variable your workspace's sources reference: its name, which "
        "sources use it, whether this Studio holds a value (stored or from the environment), plus "
        "held values no source references any more. NEVER a value. Use it to see what a source "
        "still needs before save_credential.")
   :capability :read
   :params []
   :handler (fn [ctx _] (call ctx (fn [svc a] ((:credentials svc) a)) {}))})

(def save-credential-tool
  {:name "save_credential"
   :description
   (str "Set a credential variable's value (a password, API token, JDBC URL…) that sources reference "
        "as ${NAME}. Overwrites an existing value; it takes effect on the next query, nothing restarts. "
        "Give previous_name to RENAME a variable: this workspace's sources are rewritten to the new "
        "name and the old value stays for other workspaces. Requires owner or editor.\n\n"
        "Only use a value the user gave you for this purpose, and do not repeat it in the "
        "conversation. The value is never returned by any tool.")
   :capability :write
   :params [{:name "name" :type :string :required? true
             :description "Variable name: a letter or underscore, then letters, digits, underscores."}
            {:name "value" :type :string :required? false
             :description "The secret value. Required unless renaming (a rename keeps the old value when omitted)."}
            {:name "previous_name" :type :string :required? false
             :description "Rename from this variable to `name`."}]
   :handler
   (fn [ctx {:keys [name value previous_name]}]
     (if (and previous_name (not= previous_name name))
       (call ctx (fn [svc a] ((:save-credential! svc) a))
             {:name name :previous-name previous_name :value value
              :provenance (:provenance ctx)})
       (if (and (string? value) (not (str/blank? value)))
         (call ctx (fn [svc a] ((:put-credentials! svc) a)) {:values {name value}})
         {:findings [(f/make :tools.credential/value-required :error [:value]
                             "a non-blank value (to clear one, use remove_credential)")]})))})

(def remove-credential-tool
  {:name "remove_credential"
   :description
   (str "Remove a stored credential value. Without apply=true it only PLANS: says whether it is held "
        "and which sources still reference it. It is refused while any source references it — change "
        "or remove that source first. Requires owner or editor.")
   ;; `:read` + apply, like every intent tool: a read-only key sees the plan and
   ;; is refused the removal (intent_declarations `capability-note`).
   :capability :read
   :params [{:name "name" :type :string :required? true :description "The variable name."}
            {:name "apply" :type :boolean :required? false
             :description "true to remove; omitted or false to plan only."}]
   :handler
   (fn [ctx {:keys [name apply]}]
     (let [plan (call ctx (fn [svc a] ((:credential-removal-plan svc) a)) {:variable name})]
       (cond
         (not apply) plan
         (not (scope-has? ctx :write))
         (update plan :findings (fnil conj [])
                 (f/make :tools.intent/blocked-plan :error [:apply]
                         "this connection is read-only; the plan is above and nothing was removed"
                         :expected "write"))
         :else (call ctx (fn [svc a] ((:remove-credential! svc) a)) {:variable name}))))})

;; ---------------------------------------------------------------------------
;; Sources (owner ruling 2026-10-11)
;; ---------------------------------------------------------------------------
;;
;; The Sources page's own handlers, run as the token's caller. Connecting and
;; editing a source stay `declare_source`, reading its structure
;; `describe_source`; these add the register, the api probe and the removal.
;; `:provenance` is handed over from ctx — the trusted half, never an argument.

(defn- as-caller-args [ctx args] (assoc args :provenance (:provenance ctx)))

(def list-sources-tool
  {:name "list_sources"
   :description
   (str "Every source declared in your workspace: id, type (sql / api), label, which resolvers "
        "and graph drafts use it, which keys are set, which credential variables it references "
        "and whether this Studio holds them, and whether its structure can be described. Never a "
        "credential value. Use read_object for one source's full entry and revision.")
   :capability :read
   :params []
   :handler (fn [ctx _] (call ctx (fn [svc a] ((:sources svc) a)) (as-caller-args ctx {})))})

(def probe-api-source-tool
  {:name "probe_api_source"
   :description
   (str "Make ONE real call to a declared api source and return a sample of the response: status, "
        "size, how many records, where the records array is (data_path, a guess you may override) "
        "and the sample itself. Use it to see an endpoint's shape before add_thing. The call spends "
        "the source's credentials; its audit record names the path and parameter names, never the "
        "body. Responses over the connector's ceiling are refused, not truncated — give data_path "
        "or an endpoint returning fewer items.")
   :capability :read
   :params [{:name "source" :type :string :required? true :description "The api source id."}
            {:name "path" :type :string :required? true
             :description "The path to call, relative to the source's base_url, e.g. /orders."}
            {:name "method" :type :string :required? false :description "HTTP method. Default GET."}
            {:name "params" :type :map :required? false
             :description "Query or path parameters for this call."}
            {:name "sample_size" :type :integer :required? false
             :description "How many records to read. Default is the connector's."}]
   :handler
   (fn [ctx args]
     (if-not (some #(scope-has? ctx %) [:probe :write])
       {:findings [(f/make :tools.intent/probe-required :error [:source]
                           "probing calls the upstream; it needs a key with write (or probe) scope"
                           :expected "write")]}
       (call ctx (fn [svc a] ((:probe-api-source svc) a))
             (as-caller-args ctx (select-keys args [:source :path :method :params :sample_size])))))})

(def remove-source-tool
  {:name "remove_source"
   :description
   (str "Remove a declared source. Without apply=true it only PLANS: what goes with it (resolvers, "
        "operations, attributes and links only it feeds and no graph needs, its deployment bindings "
        "and the credential values only it used) and what blocks it. It is REFUSED while a graph "
        "still needs something it feeds, a graph draft binds it, or a serving version runs on it — "
        "the plan names each. apply=true re-plans and removes it all in one transaction. "
        "Requires owner or editor.")
   :capability :read
   :params [{:name "source" :type :string :required? true :description "The source id."}
            {:name "apply" :type :boolean :required? false
             :description "true to remove; omitted or false to plan only."}]
   :handler
   (fn [ctx {:keys [source apply]}]
     (let [plan (call ctx (fn [svc a] ((:source-removal-plan svc) a))
                      (as-caller-args ctx {:source source}))]
       (cond
         (or (not apply) (seq (:findings plan))) plan
         (not (scope-has? ctx :write))
         (update plan :findings (fnil conj [])
                 (f/make :tools.intent/blocked-plan :error [:apply]
                         "this connection is read-only; the plan is above and nothing was removed"
                         :expected "write"))
         :else (call ctx (fn [svc a] ((:remove-source! svc) a))
                     (as-caller-args ctx {:source source})))))})

;; ---------------------------------------------------------------------------
;; Graph lifecycle (owner ruling 2026-10-11)
;; ---------------------------------------------------------------------------
;;
;; 裁定九 sent these back to the GUI; the owner returned them to the agent so
;; Studio can be managed over MCP. They run the Graphs page's own handlers as
;; the token's caller — the owner/editor bars, the archive-before-delete rule
;; and the audit are the page's, not a second copy.

(defn- op [k] (fn [svc a] ((k svc) a)))

(defn- graph-param [what]
  {:name "graph" :type :string :required? true
   :description (str "Qualified graph name, as list_graphs reports it. " what)})

(defn- write-gate
  "A plan-faced tool's apply on a read-only key: the plan, plus the refusal."
  [ctx plan]
  (if (scope-has? ctx :write)
    nil
    (update plan :findings (fnil conj [])
            (f/make :tools.intent/blocked-plan :error [:apply]
                    "this connection is read-only; the plan is above and nothing was written"
                    :expected "write"))))

(def rename-graph-tool
  {:name "rename_graph"
   :description
   (str "Change a graph's display name. Its UUID, published versions, tokens and data-plane URLs "
        "are unchanged. Requires owner or editor.")
   :capability :write
   :params [(graph-param "")
            {:name "name" :type :string :required? true :description "The new name (no '/')."}]
   :handler (fn [ctx a] (call ctx (op :page-rename-graph!) (as-caller-args ctx (select-keys a [:graph :name]))))})

(def duplicate-graph-tool
  {:name "duplicate_graph"
   :description
   (str "Copy a graph — its recipe and, when it has one, its Semantic Contract draft — into a new "
        "graph in this workspace. Without apply=true it only PLANS: the plan names conflicts (a taken "
        "name, a namespace overlapping existing assets). `namespace` renames the copied draft's own "
        "assets so the two graphs do not collide. Dependencies, terms and source slots stay "
        "references; credentials and serving state are never copied. Requires workspace owner.")
   :capability :read
   :params [(graph-param "The graph to copy.")
            {:name "name" :type :string :required? true :description "The new graph's name."}
            {:name "namespace" :type :string :required? false
             :description "New namespace for the copied draft's assets. Needed when the source graph has a draft."}
            {:name "apply" :type :boolean :required? false :description "true to create the copy."}]
   :handler
   (fn [ctx {:keys [apply] :as a}]
     (let [req (as-caller-args ctx (select-keys a [:graph :name :namespace]))
           plan (call ctx (op :duplicate-graph-plan) req)]
       (cond
         (or (not apply) (seq (:findings plan))) plan
         :else (or (write-gate ctx plan)
                   (call ctx (op :page-duplicate-graph!) (assoc req :plan-token (:plan-token plan)))))))})

(def merge-graphs-tool
  {:name "merge_graphs"
   :description
   (str "Create a NEW graph from two or more graphs of this workspace: its recipe is their union and "
        "their Semantic drafts are combined. The source graphs are left as they are. Requires "
        "workspace owner.")
   :capability :write
   :params [{:name "graphs" :type :vector :required? true
             :description "Two or more qualified graph names."}
            {:name "name" :type :string :required? true :description "The new graph's name."}]
   :handler (fn [ctx a] (call ctx (op :page-merge-graphs!) (as-caller-args ctx (select-keys a [:graphs :name]))))})

(def archive-graph-tool
  {:name "archive_graph"
   :description
   (str "Archive a graph: it leaves the active list and can no longer be edited; restore_graph brings "
        "it back. Archiving is the step before delete_graph. Requires workspace owner. Say so to the "
        "user before archiving a graph a consumer is reading.")
   :capability :write
   :params [(graph-param "")]
   :handler (fn [ctx a] (call ctx (op :archive-graph!) (as-caller-args ctx (select-keys a [:graph]))))})

(def restore-graph-tool
  {:name "restore_graph"
   :description "Restore an archived graph to active. Requires workspace owner."
   :capability :write
   :params [(graph-param "")]
   :handler (fn [ctx a] (call ctx (op :restore-graph!) (as-caller-args ctx (select-keys a [:graph]))))})

(def delete-graph-tool
  {:name "delete_graph"
   :description
   (str "PERMANENTLY delete an ARCHIVED graph: its registration, Semantic draft and history, and "
        "cut versions; its local serving stops and its graph tokens are revoked. Cannot be undone. "
        "Refused unless the graph is archived. Requires workspace owner. Only on the user's explicit "
        "instruction; `confirm` must repeat the graph's qualified name.")
   :capability :write
   :params [(graph-param "Must be archived.")
            {:name "confirm" :type :string :required? true
             :description "The same qualified graph name again, as confirmation."}]
   :handler
   (fn [ctx {:keys [graph confirm]}]
     (if (not= graph confirm)
       {:findings [(f/make :tools.graph/refused :error [:confirm]
                           "confirm must repeat the graph's qualified name exactly" :got confirm)]}
       (call ctx (op :page-delete-graph!) (as-caller-args ctx {:graph graph}))))})

(def rollback-graph-tool
  {:name "rollback_graph"
   :description
   (str "Point a graph's serving version back at a version cut earlier (inspect_graph lists them). "
        "Data-plane readers get that version from the next request. Requires owner or editor.")
   :capability :write
   :params [(graph-param "")
            {:name "to_version" :type :string :required? true :description "The version to serve."}]
   :handler (fn [ctx {:keys [graph to_version]}]
              (call ctx (op :rollback-graph!) (as-caller-args ctx {:graph graph :to-version to_version})))})

(def check-graph-cut-tool
  {:name "check_graph_cut"
   :description
   (str "Would this graph's draft cut into a version right now? Runs the publish checks without "
        "cutting or writing anything and returns their findings.")
   :capability :read
   :params [(graph-param "")]
   :handler (fn [ctx a] (call ctx (op :cut-check) (as-caller-args ctx (select-keys a [:graph]))))})

(def set-default-query-tool
  {:name "set_default_query"
   :description
   (str "Set the query Query Studio opens this graph with. Omit default_query to clear it. "
        "Requires owner or editor.")
   :capability :write
   :params [(graph-param "")
            {:name "default_query" :type :string :required? false
             :description (str "EDN map: {:query [...EQL...]} plus an optional :entity, e.g. "
                               "{:entity {:demo.order/id 1001} :query [:demo.order/status]}. Omit to clear.")}]
   :handler (fn [ctx {:keys [graph default_query]}]
              (let [q (when-not (str/blank? default_query)
                        (try (edn/read-string default_query) (catch Exception _ ::bad)))]
                (if (= ::bad q)
                  {:findings [(f/make :tools.graph/refused :error [:default_query]
                                      "default_query is not readable EDN" :got default_query)]}
                  (call ctx (op :page-set-default-query!)
                        (as-caller-args ctx {:graph graph :default-query q})))))})

(def set-package-name-tool
  {:name "set_package_name"
   :description
   (str "Set the release name this graph's next versions are sealed under. Omit package_name to "
        "go back to the default name. Requires owner or editor.")
   :capability :write
   :params [(graph-param "")
            {:name "package_name" :type :string :required? false :description "Two segments, e.g. acme/orders, or omit to clear."}]
   :handler (fn [ctx {:keys [graph package_name]}]
              (call ctx (op :page-set-package-name!)
                    (as-caller-args ctx {:graph graph :package-name package_name})))})

(def set-workspace-default-graph-tool
  {:name "set_workspace_default_graph"
   :description
   (str "Set the graph this workspace lands on — also what every tool's omitted `graph` means. "
        "Omit graph to clear it. Requires workspace owner.")
   :capability :write
   :params [{:name "graph" :type :string :required? false
             :description "Qualified graph name, or omit to clear."}]
   :handler (fn [ctx a] (call ctx (op :page-set-default-graph!) (as-caller-args ctx {:graph (:graph a)})))})

(def reclaim-unused-versions-tool
  {:name "reclaim_unused_versions"
   :description
   (str "Remove a graph's old cut versions that nothing serves or pins, keeping the newest `keep`. "
        "Irreversible. Requires owner or editor. reclaim_version removes one named version.")
   :capability :write
   :params [(graph-param "")
            {:name "keep" :type :integer :required? false :description "How many recent unused versions to keep."}]
   :handler (fn [ctx a] (call ctx (op :reclaim-unused-versions!) (as-caller-args ctx (select-keys a [:graph :keep]))))})

;; ---------------------------------------------------------------------------
;; Graph access and workspace tokens (owner ruling 2026-10-11)
;; ---------------------------------------------------------------------------
;;
;; Access: the graph service's own grant functions (8.4 P2), the same the
;; Graphs page calls. Workspace tokens: list and revoke only — issuing one
;; stays a person's act, so no key can mint another key.

(def list-graph-access-tool
  {:name "list_graph_access"
   :description
   (str "Who has an explicit grant on one graph (by email) and with which role, beyond the "
        "workspace's own members. Requires that you can see the graph.")
   :capability :read
   :params [(graph-param "")]
   :handler (fn [ctx a] (call ctx (op :graph-access) (select-keys a [:graph])))})

(def grant-graph-access-tool
  {:name "grant_graph_access"
   :description
   (str "Give one workspace member, by email, a role on ONE graph (an upsert: granting again "
        "changes the role). The person must already be a member of this workspace. Requires "
        "workspace owner. Ask the user before granting.")
   :capability :write
   :params [(graph-param "")
            {:name "email" :type :string :required? true :description "The member's email."}
            {:name "role" :type :string :required? false :description "The role on the graph. Default editor."}]
   :handler (fn [ctx {:keys [graph email role]}]
              (call ctx (op :grant-graph-access!)
                    {:graph graph :email email :role (or role "editor")}))})

(def revoke-graph-access-tool
  {:name "revoke_graph_access"
   :description
   (str "Remove one person's explicit grant on one graph. They keep their workspace membership. "
        "Requires workspace owner.")
   :capability :write
   :params [(graph-param "")
            {:name "email" :type :string :required? true :description "The member's email."}]
   :handler (fn [ctx a] (call ctx (op :revoke-graph-access!) (select-keys a [:graph :email])))})

(def list-workspace-tokens-tool
  {:name "list_workspace_tokens"
   :description
   (str "The workspace tokens (Builder MCP keys) of your workspace: id, label, scope, issuer, "
        "expiry and status (active / expired / revoked). Never a secret. Issuing a new one is done "
        "by a person in Studio, not by a tool.")
   :capability :read
   :params []
   :handler (fn [ctx _] (call ctx (op :workspace-tokens) (as-caller-args ctx {})))})

(def revoke-workspace-token-tool
  {:name "revoke_workspace_token"
   :description
   (str "Revoke one workspace token by id (list_workspace_tokens shows them); whoever holds it is "
        "refused from the next request on. Revoking the key YOU are using ends this session. "
        "Requires owner or editor. Ask the user first.")
   :capability :write
   ;; `workspace_token_id`: `token_id` is reserved for provenance.
   :params [{:name "workspace_token_id" :type :string :required? true
             :description "The workspace token's id."}]
   :handler (fn [ctx a] (call ctx (op :revoke-workspace-token!)
                              (as-caller-args ctx {:token-id (:workspace_token_id a)})))})

;; ---------------------------------------------------------------------------
;; Workspace objects (owner ruling 2026-10-11)
;; ---------------------------------------------------------------------------

(def list-workspace-objects-tool
  {:name "list_workspace_objects"
   :description
   (str "Every pool object your workspace owns — namespaces, attributes, resolvers, operations, "
        "metrics, links, sources — whether or not any graph selects it, with a short note per object "
        "(never a source's config). Use it to find objects no graph uses; remove_objects removes them. "
        "Requires owner or editor.")
   :capability :read
   :params []
   :handler (fn [ctx _] (call ctx (op :workspace-objects) (as-caller-args ctx {})))})
