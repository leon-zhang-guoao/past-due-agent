(ns open.atlas.studio.publish-service
  "Cutting, publishing and rolling back, **once**, for both entrances
   ([8.4 P3](../../../../../../docs/design/84-builder-mcp.md)).

   ## The same extraction P2 made, for the last step of the loop

   `publish-graph!` was a Builder HTTP handler reading its actor from the
   session. An agent that can enrich a model and put it in a graph but cannot
   publish it stops one step short of the thing being usable — and that last
   step is the one where a human has the least context, because everything
   that decided it happened in the agent's turn.

   So, as in `graph-service`, the caller is an argument. Nothing about the
   orchestration changed: `studio/version` cuts (health checks and all) and
   `studio/app` sends the artifact through the very offer/stage/load/activate
   a remote Server would get. **The point of the round trip is that what you
   self-test is what production does**, and a second publish path for agents
   would have quietly ended that.

   ## Reading the serving pointer is not optional

   `:expected-current` is the CAS guard on activate: *swap only if the pointer
   is still where I last saw it*. It was once hardcoded `nil`, which claims
   «nothing is serving this graph» — true exactly once, on the first publish.
   Every publish after it was refused, and the guard was right to refuse: the
   lie was ours. It is read here, immediately before the call, so the guard's
   real job — someone else moved the pointer in between — stays intact.

   That matters more with an agent in the loop than it did without one: two
   publishers is no longer a rare human coincidence but a person and an agent
   working the same graph at the same time.

   ## What is NOT here

   No token issuing, and no self-test verb (ruling ③). Issuing is not here
   because it is not publishing — but it is no longer a human-only step: the
   owner reversed that part of ③ on 2026-10-11, and the Builder MCP's
   `issue_graph_token` calls the token panel's own function (builder_server
   `issue-graph-token*`), so an agent can publish, issue a token and verify on
   the data plane without a person in between."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.runtime.graph :as rgraph]
            [open.atlas.runtime.instance :as instance]
            [open.atlas.runtime.source-bindings :as source-bindings]
            [open.atlas.studio.app :as app]
            [open.atlas.studio.graph :as studio-graph]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.semantic-service :as semantic]
            [open.atlas.studio.version :as version]))

(defn- refuse! [code message data]
  (throw (ex-info message (assoc data :code code))))

(defn- accept! [message result]
  (when (seq (:findings result))
    (throw (ex-info message {:code :publish-rejected :error result})))
  result)

(defn- graph!
  "Visible, in the caller's workspace, and editable — publishing is an edit of
   what consumers see, so it sits at the editor bar, not below it."
  [db {:keys [workspace principal]} qn]
  (let [definition (or (studio-graph/graph db qn)
                       (refuse! :graph-unknown "Unknown Studio graph" {:graph qn}))]
    (when-not (studio-graph/graph-visible? db qn principal)
      (refuse! :graph-unknown "Unknown Studio graph" {:graph qn}))
    (when-not (= workspace (:graph/workspace definition))
      (refuse! :graph-outside-current-workspace
               "This graph belongs to a different workspace"
               {:graph qn :workspace workspace}))
    (when-not (studio-graph/graph-editable? db qn principal)
      (refuse! :graph-editor-required "Only an owner or editor may publish this graph"
               {:graph qn}))
    definition))

(defn- semantic-draft
  [db definition]
  (let [draft (pool/semantic-draft db (:graph/workspace definition) (:graph/id definition))]
    (when (pos? (or (:revision draft) 0)) draft)))

(defn preflight
  "Would this action leave the graph un-cuttable — and is that this action's
   doing?

   **Writes nothing.** It runs the real cut checks against a candidate pool and
   diffs them against the current one. The author still gets to save: D2.2 is
   «free to write, strict to seal», so turning this into a block would change
   that ruling rather than serve it. For an agent the same rule reads
   differently and better — it can ask «would this break the seal?» before
   spending a write, which a human doing it by hand rarely bothers to."
  [{:keys [conn caller]} {:keys [graph action]}]
  (let [db (pool/db conn)]
    (graph! db caller graph)
    ;; **The action is required.** `version/preflight` answers «would THIS
    ;; action break the next cut», and its answer is a diff against the pool as
    ;; it stands — there is no question to ask without one. Passing nil reached
    ;; the pool as an unaddressable object and came back as
    ;; `:pool.action/unknown-entity`, which reads like the graph is broken
    ;; rather than like the call was empty.
    (when-not (map? action)
      (refuse! :action-required
               "preflight answers whether a specific action would break the next cut; give one"
               {:graph graph}))
    ;; D3.22: the preview takes the SAME decision path as the write, and the
    ;; write stamps the caller's workspace onto the action. Stamped here from
    ;; the caller — never from the request — or the pool's ownership check
    ;; sees no workspace at all and refuses a preview of an edit it would take.
    (version/preflight conn {:graph graph
                             :action (assoc action :workspace (:workspace caller))
                             :resolved []})))

(defn serving-status
  "Where this graph's serving pointer is, and what is on the shelf.

   An agent about to publish needs to know what is live now; an agent that has
   just published needs to confirm the pointer moved. Both are this."
  [{:keys [conn caller runtime]} {:keys [graph]}]
  (let [db (pool/db conn)
        definition (graph! db caller graph)
        store (:graph runtime)]
    {:graph (:graph/qualified-name definition)
     :serving (rgraph/serving-version store graph)
     ;; `:version/number`, not `:version` — the version records are namespaced
     ;; (`studio/version`'s own shape). Reading the wrong key gave a vector of
     ;; nils that still looked like a list of versions.
     :cut (vec (sort (map :version/number (version/versions db graph))))
     :on-shelf (->> (rgraph/shelf-versions store)
                    (filter #(= graph (:graph %)))
                    (mapv :version)
                    sort
                    vec)}))

(declare export)

(defn- publication-content [document]
  ;; Saving/importing identical content advances the draft counter; cutting
  ;; assigns a release number. Neither should create another identical release.
  (update document :publication dissoc :source/revision :release/version))

(defn- saved-queries-changed?
  "Do the draft's saved queries differ from the set the serving version was
   published with? A version published before saved queries existed has
   none, so a draft with none is unchanged against it (compatibility: an old
   graph's status does not move because this feature arrived)."
  [ctx graph serving]
  (let [db (pool/db (:conn ctx))
        definition (studio-graph/graph db graph)]
    (not= (studio-graph/saved-queries db graph)
          (or (studio-graph/pinned-saved-queries db (:graph/id definition) serving) []))))

(defn- draft-release-status [ctx graph draft]
  (let [serving (rgraph/serving-version (get-in ctx [:runtime :graph]) graph)
        base {:draft-revision (:revision draft) :serving-version serving}]
    (if-not serving
      (assoc base :state :unpublished)
      (try
        ;; Use the verified shelf bytes, not the latest version number: rollback
        ;; may leave an older revision serving. Missing evidence is never "current".
        (let [files (:files (export ctx {:graph graph :version serving}))
              document (:document (edn/read-string (get files "content.edn")))
              revision (get-in document [:publication :source/revision])
              draft-document (:document draft)
              workspace (get-in draft-document [:workspace :workspace/id])
              graph-id (get-in draft-document [:graph :graph/id])
              db (pool/db (:conn ctx))
              published-entry (source-bindings/file-resolver (:source-bindings-path ctx) (fn [_ entry] entry))
              same-content? (= (publication-content document) (publication-content draft-document))
              same-sources? (and same-content? (every?
                             (fn [[id source]]
                               (or (contains? #{"derived" "static"} (:source/kind source))
                                   (let [stored (pool/object db :source id)]
                                     (and (= workspace (:pool/workspace stored))
                                          (= (:pool/entry stored)
                                             (published-entry id (assoc source
                                                                   :semantic/workspace workspace
                                                                   :semantic/graph graph-id
                                                                   :semantic/release (str serving))))))))
                             (get-in draft-document [:execution :sources])))]
          (assoc base :serving-draft-revision revision
                 :state (cond (not (map? document)) :changed
                              (nil? revision) :unknown
                              ;; Owner ruling 2026-10-10 (studio-saved-queries
                              ;; §6 item 1): a change to the saved queries alone
                              ;; is a change worth a version. The artifact cut
                              ;; for it is byte-identical to the previous one.
                              (and same-content? same-sources?
                                   (not (saved-queries-changed? ctx graph serving))) :current
                              :else :changed)))
        (catch Exception _ (assoc base :state :unknown))))))

(defn cut-check
  "Would this graph cut right now, and which checks refuse it if not.

   `preflight` is the neighbouring question and it **requires an action**: it
   answers «would this write break the next cut». Nobody could ask «is this
   graph publishable at all» without attempting a cut and reading the refusal —
   a write attempted as a question, right where D2.2's «free to write, strict to
   seal» leaves a person no way to look down before stepping.

   Costlier than the reads around it (it projects and health-checks — most of a
   cut minus the seal), which is why it answers about ONE graph and is not a
   column on the list."
  [{:keys [conn caller resolve-source] :as ctx} {:keys [graph]}]
  (let [db (pool/db conn)
        definition (graph! db caller graph)]
    (assoc
     (if-let [draft (semantic-draft db definition)]
       (-> (semantic/publishability db (:workspace caller) (:graph/id definition) resolve-source)
           (dissoc :entries)
           (assoc :execution-target :semantic-contract
                  :config-revision (:revision draft)
                  :release-status (draft-release-status ctx graph draft)
                  :model-label (get-in draft [:document :graph :graph/label])
                  :saved-at (:saved-at draft)
                  :findings []))
       (assoc (version/cuttable conn {:graph graph})
              :execution-target :legacy-projection))
     :graph (:graph/qualified-name definition))))

(defn- reclaim-status
  "R3.5 on this instance, derived now: `{:reclaimable? bool :blockers {…}}`.
   A version already reclaimed is `{:reclaimed? true}`; an uninspectable shelf
   is not reclaimable, with the reason."
  [{:keys [runtime state now]} definition record]
  (if (:version/reclaimed-at record)
    {:reclaimed? true :reclaimable? false}
    (try
      (let [blockers (instance/version-reclaim-blockers
                      runtime
                      {:graph (:graph/qualified-name definition) :graph-id (:graph/id definition)
                       :version (:version/number record)
                       :artifact-roots [(:artifact-root state)] :now (now)})]
        {:reclaimable? (empty? blockers) :blockers blockers})
      (catch clojure.lang.ExceptionInfo e
        {:reclaimable? false
         :blockers {:uninspectable (ex-message e)}}))))

(defn versions
  "Every version cut for this graph, as records, with where the pointer is.

   `serving-status` answers a neighbouring question in the shape an agent about
   to publish needs: three lists of numbers, cheap to compare before and after.
   A person deciding whether to roll BACK needs the other half — when each
   version was cut and by whom — because the number alone does not say which of
   them is the one from before this morning's edit.

   Same guard and the same reads, so the two cannot disagree about what has been
   cut; only the shape differs."
  [{:keys [conn caller runtime] :as ctx} {:keys [graph]}]
  (let [db (pool/db conn)
        definition (graph! db caller graph)
        store (:graph runtime)]
    {:graph (:graph/qualified-name definition)
     :serving (rgraph/serving-version store graph)
     :on-shelf (->> (rgraph/shelf-versions store)
                    (filter #(= graph (:graph %)))
                    (mapv :version)
                    sort
                    vec)
     ;; Newest first: the question asked of this list is almost always about
     ;; the last few, and a rollback target is nearly always the one before.
     ;; Each record carries `:version/reclaim` — whether it may be reclaimed
     ;; on this instance and, if not, why (R3.5), so the Remove button can be
     ;; disabled with a reason instead of failing after a click.
     :versions (vec (for [r (sort-by :version/number #(compare %2 %1)
                                     (version/versions db graph))]
                      (assoc r :version/reclaim (reclaim-status ctx definition r))))}))

(defn- refuse-reclaimed!
  "A reclaimed version (R3.5) has no artifact left on this instance: neither
   rollback nor export can be served from it. Said by name, not as the
   «artifact incomplete» that missing files would otherwise produce."
  [graph record]
  (when (:version/reclaimed-at record)
    (refuse! :version-reclaimed
             "That version was reclaimed on this instance; its artifact no longer exists here"
             {:graph graph :version (:version/number record)
              :reclaimed-at (:version/reclaimed-at record)})))

(defn export
  "One cut version's three files, verbatim off the shelf, for an offline
   delivery bundle (D4.13).

   **Verbatim, and checked.** The texts are read as bytes-in-a-string and the
   `artifact-digest` is recomputed from them and compared with the one the
   version registry recorded at cut time. A mismatch is a refusal, not a
   warning: a bundle is handed to somebody who will load it on a machine this
   one cannot see, and «the shelf changed under us» is exactly the thing they
   have no way to notice. It is also the cheapest possible check — the digest is
   already the artifact's identity (D4.3).

   No zipping here. This returns the files; the envelope is the HTTP layer's
   business, because D4.13 is a **transport** form and this ns is about what a
   version IS."
  [{:keys [conn caller state]} {:keys [graph version]}]
  (let [db (pool/db conn)
        definition (graph! db caller graph)
        n (cond
            (integer? version) version
            (and (string? version) (re-matches #"[0-9]+" version)) (parse-long version)
            :else (refuse! :version-invalid "export needs a version number"
                           {:graph graph :version version}))
        record (first (filter #(= n (:version/number %)) (version/versions db graph)))
        _ (when-not record
            (refuse! :version-unknown "That version was never cut for this graph"
                     {:graph graph :to-version n
                      :cut (vec (sort (map :version/number (version/versions db graph))))}))
        _ (refuse-reclaimed! graph record)
        [ws nm] [(:graph/workspace definition) (:graph/name definition)]
        dir (io/file (:artifact-root state) ws nm (str n))
        missing (remove #(.isFile (io/file dir %)) artifact/artifact-files)
        _ (when (seq missing)
            (refuse! :artifact-incomplete
                     "This version's artifact is not complete on the shelf"
                     {:graph graph :version n :missing (vec missing)}))
        files (into {} (for [f artifact/artifact-files]
                         [f (slurp (io/file dir f))]))
        digest (artifact/artifact-digest files)]
    (when-not (= digest (:version/artifact-digest record))
      (refuse! :artifact-digest-mismatch
               "The files on the shelf do not match the digest recorded when this version was cut"
               {:graph graph :version n
                :expected (:version/artifact-digest record) :got digest}))
    {:graph (:graph/qualified-name definition)
     :workspace ws :name nm :version n
     :artifact-digest digest
     :files files}))

(defn- publish-impl!
  "Cut the current recipe into a version on the Builder's own shelf, then
   publish it into the embedded runtime — offer, stage, load, activate."
  [{:keys [conn caller state runtime now resolve-source source-bindings-path]} {:keys [graph]}]
  (let [db (pool/db conn)
        definition (graph! db caller graph)
        secrets (:secrets state)
        at (now)
        draft (semantic-draft db definition)
        cut (accept!
             "Cutting a version was rejected"
             (if draft
               ((semantic/service
                 conn caller resolve-source
                 {:cut-artifact!
                  (fn [{:keys [workspace graph-id expected-revision entries]}]
                    (locking conn
                      (let [release (str (version/next-version (pool/db conn) graph))]
                        (source-bindings/install-missing!
                         source-bindings-path
                         (into {} (map (fn [[id entry]] [[workspace graph-id release id] entry])) entries))
                        (version/cut-semantic-version!
                         conn {:graph graph :expected-revision expected-revision
                               :artifact-root (:artifact-root state) :cut-at at
                               :cut-by (pr-str (:principal caller))}))))})
                :cut-artifact {:graph graph :expected_revision (:revision draft)} nil)
               (version/cut-version! conn {:graph graph :resolved []
                                           :artifact-root (:artifact-root state)
                                           :cut-at at
                                           ;; Who cut it. For an agent this is
                                           ;; the principal that issued its key.
                                           :cut-by (pr-str (:principal caller))})))
        v (:version cut)
        deployment (str "studio-" (:graph/id definition) "-v" v)
        ;; Read the pointer, do not assume it — see the ns docstring.
        serving (rgraph/serving-version (:graph runtime) graph)
        out (accept! "Publishing to the embedded runtime was rejected"
                     (app/publish! state
                                   {:graph graph :version v :actor (:principal caller)
                                    :actor-key-id (:actor-key-id secrets)
                                    :actor-secret (:actor-secret secrets)
                                    :deployment-id deployment
                                    :request-id-fn (fn [step] (str deployment "-" (pr-str step)))
                                    :now at
                                    :expires-at (str (.plusSeconds (java.time.Instant/parse at) 300))
                                    :expected-current serving}))]
    {:graph graph :version v
     :steps (mapv :step (:steps out))
     ;; Re-read: `serving` above is the PRE-publish pointer (the CAS guard).
     ;; What the caller wants back is where it points now.
     :serving (rgraph/serving-version (:graph runtime) graph)}))

(defn- rollback-impl!
  "Move the pointer to an explicit target version.

   Any cut version that is not already serving is a legal target — **including
   one newer than the pointer**, which is the ordinary state after a rollback.
   The op signed onto the wire is therefore chosen by direction, not by this
   function's name (D3.16 ⑤): earlier is `:rollback`, later (or nothing serving)
   is `:activate`, so the event log can still tell «we went back» from «we went
   forward».

   **The evidence must be the plan of the version being pointed AT** (R3.6 /
   D3.16), not the current one's — so the deployment id is rebuilt from the
   target, exactly as the original publish of that version formed it. Passing
   the current version's id would offer the runtime a receipt for a different
   thing and let it accept a rollback nobody had ever planned."
  [{:keys [conn caller state runtime now]} {:keys [graph to-version]}]
  (let [db (pool/db conn)
        definition (graph! db caller graph)
        ;; `:version/number` — same namespaced shape `serving-status` reads.
        records (version/versions db graph)
        cut (set (map :version/number records))]
    (when-not (contains? cut to-version)
      (refuse! :version-unknown "That version was never cut for this graph"
               {:graph graph :to-version to-version :cut (vec (sort cut))}))
    (refuse-reclaimed! graph (first (filter #(= to-version (:version/number %)) records)))
    (let [at (now)
          deployment (str "studio-" (:graph/id definition) "-v" to-version)
          serving (rgraph/serving-version (:graph runtime) graph)]
      (when (= serving to-version)
        (refuse! :version-already-serving
                 "That version is already the one being served"
                 {:graph graph :to-version to-version}))
      (accept! "Rolling back was rejected"
               (app/rollback! state
                              {:graph graph :to-version to-version
                               :actor (:principal caller)
                               :actor-key-id (:actor-key-id (:secrets state))
                               :actor-secret (:actor-secret (:secrets state))
                               :deployment-id deployment
                               ;; **A fresh request id per attempt**, under the
                               ;; SAME deployment id — which is exactly what the
                               ;; protocol's own refusal prescribes: «reuse it
                               ;; only to retry the identical command, and use a
                               ;; new one (under the same deployment id) to ask
                               ;; the question again».
                               ;;
                               ;; It used to be derived from the deployment and
                               ;; the op alone, so every move to the same target
                               ;; carried one id forever. Going v2 → v1, forward
                               ;; to v2, then back to v1 again sent a
                               ;; byte-identical envelope, and the runtime —
                               ;; correctly — treated the second decision as a
                               ;; **replay of the first**: it returned the
                               ;; original success and moved nothing. The person
                               ;; who pressed the button saw no error and no
                               ;; effect, which is the worst of the three
                               ;; possible outcomes.
                               ;;
                               ;; Safety does not rest on this id: `expected-current`
                               ;; is the CAS, so a genuine retry after a lost
                               ;; response is refused for naming a pointer that
                               ;; has moved, rather than applied twice.
                               :request-id-fn (fn [step]
                                                (str deployment "-" (pr-str step) "-"
                                                     (java.util.UUID/randomUUID)))
                               :now at
                               :expires-at (str (.plusSeconds (java.time.Instant/parse at) 300))
                               :expected-current serving}))
      {:graph graph :to-version to-version
       :serving (rgraph/serving-version (:graph runtime) graph)})))

;; Artifact removal and publication share the authoring connection lock. A
;; dependency cannot be published between deletion's reference scan and purge.
(defn- refuse-stale-saved-queries!
  "A saved query that names an attribute the model no longer has would be
   published broken, so the publish is refused and names it (proposal
   studio-saved-queries §3.2). Static — no source is touched. The host
   provides the check; a host without one publishes as before."
  [ctx graph]
  (when-let [stale (:saved-query-stale ctx)]
    (let [queries (studio-graph/saved-queries (pool/db (:conn ctx)) graph)
          findings (when (seq queries) (stale graph queries))]
      (when (seq findings)
        (throw (ex-info "A saved query no longer matches the model"
                        {:code :publish-rejected :error {:findings (vec findings)}}))))))

(defn- pin-saved-queries!
  "The draft's saved queries become the published version's — write-once, so
   a rollback to this version later serves exactly this set."
  [ctx graph version]
  (let [db (pool/db (:conn ctx))
        definition (studio-graph/graph db graph)]
    (studio-graph/pin-saved-queries! (:conn ctx) (:graph/id definition) version
                                     (studio-graph/saved-queries db graph))))

(defn publish! [ctx {:keys [graph] :as request}]
  (locking (:conn ctx)
    ;; The same lock covers comparison, cut and activation, so concurrent
    ;; clicks/retries cannot both create a version of the same saved content.
    (let [db (pool/db (:conn ctx))
          definition (graph! db (:caller ctx) graph)
          draft (semantic-draft db definition)
          status (when draft (draft-release-status ctx graph draft))]
      (case (:state status)
        :current {:graph graph :version (:serving-version status)
                  :serving (:serving-version status) :steps [] :unchanged? true}
        :unknown (refuse! :publication-status-unavailable
                         "Cannot verify the published model. Restore the published artifact and Source bindings before retrying."
                         {:graph graph})
        (do (refuse-stale-saved-queries! ctx graph)
            (let [out (publish-impl! ctx request)]
              (pin-saved-queries! ctx graph (:version out))
              out))))))

(defn rollback! [ctx request]
  (locking (:conn ctx) (rollback-impl! ctx request)))

(defn- reclaim-impl!
  [{:keys [conn caller state runtime now]} definition record]
  (let [qn (:graph/qualified-name definition)
        n (:version/number record)
        at (now)]
    (try
      (instance/reclaim-version!
       runtime {:graph qn :graph-id (:graph/id definition) :version n
                :artifact-roots [(:artifact-root state)] :now at})
      (catch clojure.lang.ExceptionInfo e
        (if (= :version-in-use (:code (ex-data e)))
          (refuse! :version-in-use
                   "That version is still in use on this instance and cannot be reclaimed"
                   (-> (ex-data e) (dissoc :code) (assoc :graph qn :version n)))
          (throw e))))
    ;; Record after the bytes are gone: a failed cleanup leaves the version
    ;; unmarked, and pressing Remove again retries the same plan.
    (version/mark-reclaimed! conn qn n {:reclaimed-at at
                                        :reclaimed-by (pr-str (:principal caller))})
    (studio-graph/unpin-saved-queries! conn (:graph/id definition) n)
    n))

(defn- version-record! [db graph n]
  (or (first (filter #(= n (:version/number %)) (version/versions db graph)))
      (refuse! :version-unknown "That version was never cut for this graph"
               {:graph graph :version n})))

(defn reclaim-version!
  "Reclaim ONE published version on this instance (R3.5): its artifact copies
   are removed, its record stays marked reclaimed — numbers are never reused,
   and audit, deployment records and diff reports keep naming it. Refused by
   name while anything still needs it: the serving pointer, an active and
   unexpired token pinned to it, or a retained artifact referencing it. Editor
   bar, like publish."
  [{:keys [conn caller] :as ctx} {:keys [graph version]}]
  (locking conn
    (let [db (pool/db conn)
          definition (graph! db caller graph)
          n (if (integer? version) version
                (or (some-> version str (->> (re-matches #"[0-9]+")) parse-long)
                    (refuse! :version-invalid "reclaim needs a version number"
                             {:graph graph :version version})))
          record (version-record! db graph n)]
      (when (:version/reclaimed-at record)
        (refuse! :version-reclaimed "That version was already reclaimed on this instance"
                 {:graph graph :version n :reclaimed-at (:version/reclaimed-at record)}))
      (reclaim-impl! ctx definition record)
      {:graph graph :reclaimed [n]})))

(defn reclaim-unused!
  "Reclaim every version that is reclaimable now, keeping the newest `keep`
   cut versions (default 1) whatever their state. Versions still in use are
   skipped and listed with their reason — this is a sweep, not a demand."
  [{:keys [conn caller] :as ctx} {:keys [graph] n-keep :keep}]
  (locking conn
    (let [db (pool/db conn)
          definition (graph! db caller graph)
          n-keep (cond (nil? n-keep) 1
                       (and (integer? n-keep) (not (neg? n-keep))) n-keep
                       (and (string? n-keep) (re-matches #"[0-9]+" n-keep)) (parse-long n-keep)
                       :else (refuse! :keep-invalid "keep must be a non-negative number of versions"
                                      {:graph graph :keep n-keep}))
          candidates (->> (version/versions db graph)
                          (sort-by :version/number #(compare %2 %1))
                          (drop n-keep)
                          (remove :version/reclaimed-at))
          results (doall
                   (for [r candidates
                         :let [status (reclaim-status ctx definition r)]]
                     (if (:reclaimable? status)
                       {:reclaimed (reclaim-impl! ctx definition r)}
                       {:skipped {:version (:version/number r) :blockers (:blockers status)}})))]
      {:graph graph :keep n-keep
       :reclaimed (vec (sort (keep :reclaimed results)))
       :skipped (vec (keep :skipped results))})))
