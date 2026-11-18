(ns open.atlas.studio.tools.impl.publish-declarations
  "The **publish** tools ([8.4 P3](../../../../../../../docs/design/84-builder-mcp.md)).

   The last step of the loop, and the one an agent is least able to hand back.
   Everything that decided a publish — which gaps were filled, which candidates
   were signed, what went into the recipe — happened in the agent's turn; a
   human asked to press the button afterwards is being asked to approve a
   decision whose reasons have already scrolled past.

   ## Same shape as the graph tools

   No authority here. Every handler calls `(:publish-service ctx)`, injected by
   the host, and the host's own HTTP routes call the SAME service — so «who may
   publish» and «which pointer must the CAS guard see» are decided once.

   `studio/tools` may not require `apps/studio`, and it must not require
   `studio/app` or `studio/deploy` either: taking actions or a deployment plan
   from this side would make «this was cut from the current recipe» a claim
   nothing guarantees. The service re-derives all of it.

   ## Why publish is one verb and not four

   offer / stage / load / activate are the command protocol's steps, and they
   are visible in the result. They are not four tools: a caller that could run
   them separately could load an artifact and never activate it, leaving a
   graph half-published — a state the runtime can express but nobody wants to
   own. Same reasoning `pool/execute-actions` uses for a batch."
  (:require [open.atlas.studio.tools.impl.finding :as f]
            [open.atlas.studio.tools.impl.repair :as repair]))

(defn- with-repairs
  "8.4.21 裁定十五 ①: the check's findings carry their repair, from the one
   function that infers it. Both lists are treated — `:findings` is the
   envelope's and `:health-findings` is the cut check's, and a reader who
   happens to look at the second one must not get a poorer answer."
  [result ctx graph]
  (if (map? result)
    (let [rctx (repair/context ctx graph)]
      (cond-> result
        (seq (:findings result))        (update :findings repair/attach rctx)
        (seq (:health-findings result)) (update :health-findings repair/attach rctx)))
    result))

(defn- service [ctx]
  (or (:publish-service ctx)
      (throw (ex-info "no publish service" {::absent true}))))

(defn- call
  "Run one service operation, turning the host's `ex-info` refusals into
   finding-v1. Findings the modules themselves produced travel WHOLE — a cut
   refused by a health check says exactly which check, and rewording that here
   would be inventing a second vocabulary for it."
  [ctx op args]
  (try
    (op (service ctx) args)
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        (cond
          (::absent d)
          {:findings [(f/make :tools.publish/unavailable :error [:publish]
                              "this host does not publish")]}

          (seq (get-in d [:error :findings]))
          {:findings (vec (get-in d [:error :findings]))}

          :else
          {:findings [(f/make :tools.publish/refused :error [:publish]
                              (ex-message e)
                              :got (select-keys d [:code :graph :to-version :cut
                                                   :workspace :version :serving
                                                   :pinned-tokens :referenced-by
                                                   :reclaimed-at]))]})))))

(defn- write-key? [ctx]
  (contains? (set (or (:scope ctx) (:capabilities ctx))) :write))

(def publish-graph-tool
  "**One verb for the whole publish step** (8.4.16 裁定九).

   It merges `preflight_graph` (now the DEFAULT answer of this tool) and
   `rollback_graph` (now `to_version`), on the same ground the four command
   steps were merged into one publish in the first place: a caller who has to
   pick between three names picks the wrong one, and the check that ought to
   precede a publish is the one most easily skipped when it is a separate
   tool.

   **`:capability :read`, checked at the write.** The preflight half is the
   half a reviewer holding a read-only key most needs; declaring `:write`
   would make the whole tool ABSENT for that key (裁定 ②) and take the
   report with it. So the key is checked where the publish happens, and the
   report travels with the refusal — the same discipline the intent tools use."
  {:name "publish_graph"
   :description
   (str "Release the latest saved Graph and activate it with apply=true. After upload_graph, "
        "call this tool with the same graph. The existing Graph token follows the active "
        "release; verify it through the data-plane MCP execute_eql (Builder queries read drafts). "
        "The result reports the released version and serving version. "
        "The publish step, check first. WITHOUT apply it WRITES NOTHING and answers "
        "«would this graph cut right now, and what would go wrong» — the real cut checks, "
        "and, when you name a candidate `action`, the diff that action introduces (the pool "
        "is free to write and strict to seal, D2.2, so this is how you find out what a write "
        "would cost the graph's next version before spending it).\n\n"
        "apply=true cuts the current recipe into a version and publishes it into this "
        "Studio's embedded runtime — offer, stage, load, activate, the same steps a remote "
        "Server receives. Requires owner or editor. The activate step carries a "
        "compare-and-swap on the serving pointer, so a publish racing another one is REFUSED "
        "rather than silently winning: read inspect_graph's summary again and decide.\n\n"
        "to_version points the graph at a version cut earlier — or at a later one, which is "
        "the ordinary state after a rollback. The evidence used is that version's OWN "
        "deployment plan, not the current one's (R3.6). It is checked first like everything "
        "else here and moves the pointer only with apply=true. A version that was never cut, "
        "or one already being served, is refused by name.")
   :capability :read
   :params
   [{:name "graph" :type :string :required? false
     :description "Qualified graph name. Omit it to use the workspace's default graph."}
    {:name "action" :type :map :required? false
     :description
     (str "A candidate pool action to diff the checks against, in the pool's action shape. "
          "Omitted, the answer is whether the graph cuts as it stands.")}
    {:name "to_version" :type :integer :required? false
     :description "Roll back (or forward) to this cut version, as the summary lists under :cut."}
    {:name "apply" :type :boolean :required? false
     :description "Actually publish — or, with to_version, actually move the pointer."}]
   :handler
   (fn [ctx args]
     (let [graph (:graph args)
           ;; **The check runs on EVERY path, including the apply one**
           ;; (裁定九: `to_version` 给了即回滚「同样先体检」). Which check it is
           ;; follows the argument: with an action it is the diff `preflight`
           ;; answers, without one it is `cut-check`'s «does this graph cut at
           ;; all» — the two are neighbouring questions and the service keeps
           ;; them apart, so this face does too rather than inventing a third.
           check (-> (if (map? (:action args))
                       (call ctx (fn [svc a] ((:preflight svc) a))
                             {:graph graph :action (:action args)})
                       (call ctx (fn [svc a] ((:cut-check svc) a)) {:graph graph}))
                     (with-repairs ctx graph))
           refused? (seq (filter #(= :error (:severity %)) (:findings check)))]
       (cond
         (not (:apply args)) check

         ;; A check that refused is not a publish that failed: nothing was
         ;; attempted, and the report says why.
         refused? (assoc check :published? false)

         (not (write-key? ctx))
         (assoc check :published? false
                :findings (conj (vec (:findings check))
                                (f/make :tools.publish/refused :error [:apply]
                                        (str "this connection is read-only; the check is above "
                                             "and nothing was published")
                                        :expected "write" :refs ["8.4 裁定 ②"])))

         (contains? args :to_version)
         (assoc (call ctx (fn [svc a] ((:rollback! svc) a))
                      {:graph graph :to-version (:to_version args)})
                :preflight check)

         :else
         (assoc (call ctx (fn [svc a] ((:publish! svc) a)) {:graph graph})
                :preflight check))))})

(def reclaim-version-tool
  "Remove a published version's artifact from this instance (R3.5). Owner
   ruling 2026-10-10: exposed to agents as well as the GUI. Irreversible, so
   it answers first and acts only with apply=true, like `publish_graph`."
  {:name "reclaim_version"
   :description
   (str "Remove published versions' artifacts from THIS instance to reclaim space (R3.5). "
        "WITHOUT apply it WRITES NOTHING and lists every version with whether it may be "
        "reclaimed now and, if not, why: it is serving, an active unexpired token is pinned "
        "to it, or another retained artifact references it. apply=true with `version` removes "
        "that one version; apply=true with `keep` instead removes every reclaimable version "
        "except the newest `keep`, skipping (and listing) the ones still in use. "
        "IRREVERSIBLE on this instance: the version record stays marked reclaimed and its "
        "number is never reused, but it can no longer be rolled back to or exported. "
        "Requires owner or editor and a write key.")
   :capability :write
   :params
   [{:name "graph" :type :string :required? false
     :description "Qualified graph name. Omit it to use the workspace's default graph."}
    {:name "version" :type :integer :required? false
     :description "The one cut version to reclaim."}
    {:name "keep" :type :integer :required? false
     :description "Sweep instead: reclaim everything reclaimable except the newest `keep` versions."}
    {:name "apply" :type :boolean :required? false
     :description "Actually remove. Without it the answer is the per-version reclaim status."}]
   :handler
   (fn [ctx args]
     (let [graph (:graph args)]
       (cond
         (not (:apply args))
         (let [r (call ctx (fn [svc a] ((:versions svc) a)) {:graph graph})]
           (if (seq (:findings r))
             r
             {:graph (:graph r) :serving (:serving r)
              :versions (vec (for [v (:versions r)]
                               (cond-> {:version (:version/number v)
                                        :reclaimable (boolean (get-in v [:version/reclaim :reclaimable?]))}
                                 (:version/reclaimed-at v) (assoc :reclaimed_at (:version/reclaimed-at v))
                                 (seq (get-in v [:version/reclaim :blockers]))
                                 (assoc :blockers (get-in v [:version/reclaim :blockers])))))
              :findings []}))

         (and (contains? args :version) (contains? args :keep))
         {:findings [(f/make :tools.publish/refused :error [:version]
                             "give either version (one) or keep (sweep), not both")]}

         (contains? args :version)
         (call ctx (fn [svc a] ((:reclaim-version! svc) a))
               {:graph graph :version (:version args)})

         (contains? args :keep)
         (call ctx (fn [svc a] ((:reclaim-unused! svc) a))
               {:graph graph :keep (:keep args)})

         :else
         {:findings [(f/make :tools.publish/refused :error [:version]
                             "apply=true needs version (one) or keep (sweep)")]})))})

(def declarations
  "One tool. `serving_status` moved into `inspect_graph`'s summary, where it
   sits beside the recipe and the projection (8.4.16 裁定九); `preflight_graph`
   and `rollback_graph` are this tool's default and its `to_version`."
  [publish-graph-tool reclaim-version-tool])
