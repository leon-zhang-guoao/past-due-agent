(ns open.atlas.studio.tools.impl.intent-declarations
  "The **intent tools** (8.4.14 裁定六, owner 2026-09-03; consolidated by
   8.4.16 裁定九).

   Ten tools: the three additions, the two edits, one removal, the words
   batch, the two term bindings and the recipe edit. Every one of them returns
   a PLAN by default and writes only with `apply: true`, re-planning from the
   same arguments on the way in — see `impl.intent` for why that discipline is
   load-bearing rather than tidy.

   ## These declarations hold no modelling logic

   The planning is `impl.intent`, the write is `pool/execute-actions`, the
   recipe delta is the host's graph service and the verification is the host's
   draft service. What lives here is the vocabulary: what a person says, and
   which of those words is which parameter. That is the whole of the owner's
   first principle — the encoding questions are answered inside, and there is
   no second schema in this file.

   ## The low-level face is gone

   `execute_action` and `confirm_candidates` were the `advanced` key's two
   tools; 8.4.16 裁定九 deleted that key and both declarations — an agent
   reads names before descriptions, and a name it does not need is a name it
   can pick by mistake. Their pool functions are untouched and still serve the
   Builder's own routes.

   `update_recipe` is retired (裁定八); `change_graph_members` below replaces
   it and speaks in object NAMES rather than in a whole recipe map."
  (:require [clojure.string :as str]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.tools.impl.finding :as f]
            [open.atlas.studio.tools.impl.intent :as intent]
            [open.atlas.studio.tools.impl.repair :as repair]))

(defn- db-of [{:keys [db conn]}] (or db (pool/db conn)))

(def ^:private capability-note
  "**Why every intent tool is `:capability :read`.**

   A plan writes nothing, and 8.4.14's exit conditions require a read-only
   workspace token to SEE the plan and be refused the apply. A `:write`
   declaration would make the tool ABSENT for that token (裁定 ②), and the
   read-only half — which is most of the tool's value to a reviewer — would go
   with it.

   So the key is checked where the WRITE happens: `apply: true` without
   `:write` in this connection's scope is refused by name, and the plan it
   would have written comes back with the refusal. That is 裁定 ②'s rule
   applied to the half of the tool that actually writes."
  :read)

;; ---------------------------------------------------------------------------
;; Service edges — the two effectful ends, both injected by the host
;; ---------------------------------------------------------------------------

(defn- graph-members
  "This graph's recipe, through the SAME service `graph_members` calls — one
   place decides which graphs this credential may see."
  [ctx graph]
  (if-let [svc (:graph-service ctx)]
    (try ((:graph-members svc) {:graph graph})
         (catch clojure.lang.ExceptionInfo e
           (let [d (ex-data e)]
             (if (seq (get-in d [:error :findings]))
               {:findings (vec (get-in d [:error :findings]))}
               {:findings [(f/make :tools.graph/refused :error [:graph]
                                   (ex-message e)
                                   :got (select-keys d [:code :graph :workspace]))]}))))
    {:findings [(f/make :tools.graph/unavailable :error [:graph]
                        "this host does not serve the graph registry")]}))

(defn- members-of
  "The recipe as a plain `{kind #{id …}}` map, whatever the service wraps it
   in."
  [result]
  (or (:members result) (:graph/members result) (get result :recipe) {}))

(defn- recipe-applier
  "The recipe-delta executor, or nil when this host does not serve one.

   It is a service function and not a tool: 8.4.14 rules explicitly against an
   `add_members` tool that speaks in object ids — the delta is generated
   inside the intent, and only the intent applies it."
  [ctx]
  (when-let [svc (:graph-service ctx)]
    (when-let [ap (:apply-recipe-delta! svc)]
      (fn [args]
        ;; The host's refusals become findings HERE, in the same shape
        ;; `graph_declarations`' `call` gives them — «this caller may not edit
        ;; this graph» must read identically whichever door asked. Re-wording
        ;; it inside the planner would be this layer inventing a second
        ;; vocabulary for a refusal the service already names.
        (try (ap args)
             (catch clojure.lang.ExceptionInfo e
               (let [d (ex-data e)]
                 (if (seq (get-in d [:error :findings]))
                   {:findings (vec (get-in d [:error :findings]))}
                   {:findings [(f/make :tools.graph/refused :error [:graph]
                                       (ex-message e)
                                       :got (select-keys d [:code :graph :workspace
                                                            :graph-workspace :state]))]}))))))))

(defn- evaluator
  "The `limit 1` draft run folded into apply (item 3). Absent on a host that
   runs no draft queries — then the apply simply reports no statements."
  [ctx]
  (when-let [svc (:draft-service ctx)]
    (fn [args] ((:evaluate! svc) (select-keys args [:graph :query])))))

(defn- with-repairs
  "**A defect carries its own repair** (8.4.21 裁定十五 ①). Attached at the
   FACE, by the one ns that infers repairs — a planner produces a finding-v1
   finding and nothing else, exactly as `inspect_graph` and `publish_graph` do
   with theirs."
  [ctx args plan]
  (update plan :findings repair/attach (repair/context ctx (:graph args))))

(defn- write-key? [ctx]
  (contains? (set (or (:scope ctx) (:capabilities ctx))) :write))

(defn- run
  "One intent: plan, and on `apply` write through the one apply path.

   Both halves read the SAME planner output, which is what the acceptance test
   («apply's actions are byte-identical to the preceding plan's») asserts."
  [ctx args plan]
  (let [;; **A sentence said twice is the same sentence** (doc 84 §8.4.25).
        ;; Folded HERE, on the finished plan, because it is the one path every
        ;; intent tool goes through — and because `add_relation` assembles its
        ;; resolver in two halves, so nothing earlier holds the entry the pool
        ;; would end up with. A plan that creates no resolver is returned
        ;; untouched, so every other tool is byte-identical to before.
        plan (if (some #(and (= :create (:action %)) (= :resolver (:type %)))
                       (:actions plan))
               (intent/fold-said-before (pool/as-dialect (db-of ctx))
                                        (members-of (graph-members ctx (:graph args)))
                                        plan)
               plan)
        ;; The plan is validated against the pool BEFORE it is shown or
        ;; applied: `preflight` is the pool's own dry path, so a shape apply
        ;; would refuse is refused here with the same finding (8.4.14 裁定六
        ;; principle 1 — a caller may not learn the encoding one apply at a
        ;; time). Apply re-plans through the same call, so the actions it
        ;; writes stay byte-identical to the plan's.
        ;; D3.22: the workspace is the TOKEN's / session's, stamped onto every
        ;; action before the plan is validated or shown. No tool parameter
        ;; carries a workspace, so no caller can write on another's behalf.
        plan (intent/preflight (:conn ctx) (intent/stamp-workspace plan (:workspace ctx)))
        ;; The plan says what it does in the four words, in at most three
        ;; lines (doc 106 §106.7 P8a). Assembled HERE, once, for every intent
        ;; tool: a summary each planner wrote for itself would be eleven
        ;; vocabularies for one shape. It is DERIVED from `:actions` and
        ;; `:recipe-delta` and never carried in from a caller.
        plan (assoc plan :summary (intent/summarize plan))
        plan (with-repairs ctx args plan)]
    (cond
      (not (:apply args)) plan

      (not (write-key? ctx))
      ;; The plan travels WITH the refusal: a reviewer holding a read-only key
      ;; is exactly who should be reading it, and making them lose it to learn
      ;; they may not apply would be two calls for one answer.
      (assoc plan :written? false
             :findings (conj (vec (:findings plan))
                             (f/make :tools.intent/blocked-plan :error [:apply]
                                     (str "this connection is read-only; the plan is above and "
                                          "nothing was written")
                                     :expected "write" :refs ["8.4 裁定 ②"])))

      :else
      (intent/apply-plan! plan
                          {:conn (:conn ctx)
                           :provenance (:provenance ctx)
                           :recipe! (recipe-applier ctx)
                           :evaluate! (evaluator ctx)
                           :graph (:graph args)
                           :model (pool/as-dialect (db-of ctx))}))))

(defn- with-graph
  "Read the graph's recipe first, then plan against it. A graph the caller may
   not see never reaches the planner: the refusal is the service's and travels
   whole."
  [ctx args k]
  (let [m (graph-members ctx (:graph args))]
    (if (seq (filter #(= :error (:severity %)) (:findings m)))
      m
      (k (members-of m)))))

;; ---------------------------------------------------------------------------
;; The source structure — describe_source for sql, the probe for api (裁定七)
;; ---------------------------------------------------------------------------

(defn- api-source? [src]
  (= "api" (some-> (:type src) name)))

(defn- structure-of
  "The relation structure `add_resolver` plans from.

   `describe_source` for a sql source — identifiers and declared types, not a
   row. For an api source there is nothing to introspect (connector-api
   deliberately implements no `describe-schema`), so 裁定七 allows ONE real
   call and infers the fields from the sample. The caller does not choose
   between the two: it sends `path` for an API because that is what a person
   knows about an API, and this decides."
  [ctx db src {:keys [source relation path params data_path sample_size outputs
                      bindings]}]
  (if-not (api-source? src)
    ;; **The narrowed read cannot name candidates** (0.2 acceptance defect 9).
    ;; `:names [relation]` answers with that one relation, so a wrong guess —
    ;; `add_relation` derives one from the child's name when `from` is absent —
    ;; left the planner's `:tools.intent/unknown-relation` with `:expected []`,
    ;; i.e. a refusal that withheld exactly the list the caller needed. On that
    ;; path only, read the source's relation names (`:keys? false` — the keys
    ;; are the part that cannot be read in bulk) so the refusal can name them.
    (let [narrowed (spi/describe-schema src nil {:names [relation]})]
      (if (some #(= (str relation) (str (:name %))) (:relations narrowed))
        {:description narrowed}
        {:description (spi/describe-schema src nil {:keys? false})}))
    (cond
      ;; **Refused before any request is made** (exit condition: no probe
      ;; means no HTTP request at all, not a request whose result is
      ;; discarded).
      ;;
      ;; 8.4.22 裁定十六 ①: a WRITE key carries `:probe`. 裁定七 had the client
      ;; declare the capability in `initialize`, which no standard MCP client
      ;; does, so every agent was refused here. The implication is applied on
      ;; this side rather than at one door, because the GUI's probe route
      ;; reaches the same planner and must read the gate the same way.
      (not (some (set (or (:scope ctx) (:capabilities ctx))) [:probe :write]))
      {:findings [(f/make :tools.intent/probe-required :error [:source]
                          (str "this is an api source, whose fields can only be learned by "
                               "calling it once; that takes a key that may write this "
                               "workspace, and this connection holds a read-only one")
                          :expected "write" :refs ["8.4.14 裁定七" "8.4.22 裁定十六 ①"])]}

      (nil? (:probe-source ctx))
      {:findings [(f/make :tools.intent/probe-required :error [:source]
                          "this host does not run source probes")]}

      (nil? path)
      {:findings [(f/make :tools.intent/probe-refused :error [:path]
                          "an api source needs the path to call, relative to its base URL")]}

      :else
      (let [r (try ((:probe-source ctx) src (cond-> {:path path :params params
                                                     :data_path data_path}
                                              sample_size (assoc :sample_size sample_size)
                                              ;; A templated `:base_url` has no
                                              ;; address until one instance is
                                              ;; named (doc 106 §106.14 item
                                              ;; 11); the caller's `sample` is
                                              ;; that name, for this call only.
                                              (seq bindings) (assoc :bindings bindings)))
                   (catch clojure.lang.ExceptionInfo e
                     {:refused (or (:error/category (ex-data e)) :source)}))]
        (cond
          (:refused r)
          {:findings [(f/make :tools.intent/probe-refused :error [:source]
                              "the probe call did not complete"
                              :got (str (:refused r)))]}
          (:over-limit? r)
          {:findings [(f/make :tools.intent/probe-refused :error [:source]
                              ;; 8.4.23 裁定十七: the probe now stops at the
                              ;; FIRST record, so this refusal means no
                              ;; complete record fit in what it read — and the
                              ;; way out is to say where the records are, which
                              ;; lets the probe stop far earlier.
                              (str "no complete record was found in the bytes a probe may read; "
                                   "it is refused rather than truncated, because fields inferred "
                                   "from half a record are not this endpoint's fields. Give "
                                   "data_path (the JSONPath of the records array) and the probe "
                                   "stops at the first record instead of reading up to the ceiling")
                              ;; The ceiling itself lives in the connector
                              ;; (`connector-api/probe-max-bytes`), which this
                              ;; module may not require (D1.8) — so the refusal
                              ;; reports what WAS read and names the way out
                              ;; rather than restating a number it cannot see.
                              :expected "a complete record within the probe's read ceiling"
                              :got (str (:bytes-read r) " bytes read"))]}
          (nil? (:sample r))
          {:findings [(f/make :tools.intent/probe-refused :error [:source]
                              "the endpoint answered, and not with a sample fields can be read from"
                              :got (str "HTTP " (:status r)))]}
          :else
          (let [nested (filterv :path (intent/normalize-outputs (or outputs [])))
                d (intent/probe->description r relation path
                                             {:data-path data_path
                                              :nested nested
                                              ;; The connector's own locator when
                                              ;; the host injected it (8.4.22
                                              ;; 裁定十六 ②) — one answer about
                                              ;; where the rows are, shared with
                                              ;; the query path.
                                              :locate-rows (:locate-rows ctx)})
                chosen (get-in d [:probe :data-path])]
            {:description d
             :data-path chosen
             :probe (intent/probe-audit {:source source :path path :params params
                                         :data-path chosen
                                         :data-path-from (get-in d [:probe :data-path-from])
                                         ;; A whole-body read located nothing,
                                         ;; so the row count at the chosen path
                                         ;; is what «how many records» means.
                                         :records (get-in d [:probe :rows])}
                                        r (count (:columns (first (:relations d)))))
             ;; A path the caller did not give was CHOSEN here; the plan says
             ;; which, and a person accepts it like every other inference.
             :probe-fill (cond-> (intent/probe-fill d relation)
                           (nil? data_path)
                           (conj [:source "data_path" (str chosen)]))}))))))

;; ---------------------------------------------------------------------------
;; The resolver planner, and the SENTENCES that reach it
;; (need-grammar-v1 A1/A2 and B1; doc 106 §106.6 item 1, owner 2026-09-06)
;;
;; `add_resolver` is gone from the tool list: it named a Pathom object, and the
;; twelve sentences name things and relations. The PLANNER it called is
;; unchanged and is reached from `add_thing`, `add_attribute` and
;; `add_relation` — 8.4.19 裁定十三's three-piece child collection is now
;; `add_relation`'s sql realization rather than a third shape a caller picks
;; between.
;; ---------------------------------------------------------------------------

(defn- qualified-key? [k] (str/includes? (str (if (keyword? k) (symbol k) k)) "/"))

(defn path-sample
  "The one real value of the PATH's key, out of whatever `sample` was given.

   A bare string is that value. A map names each placeholder it fills — a
   qualified attribute id (`nifi.instance/host`) is the source's URL-bound
   one, anything else is the path's — because with an authority-templated
   `:base_url` (§106.14 item 11) a keyed endpoint has TWO placeholders and one
   string cannot fill both. Before this, `/process-groups/{id}` over such a
   source dialled the group id as its hostname.

   The three-argument arity answers for ONE placeholder of a possibly COMPOUND
   key (owner 2026-09-07, doc 106 §106.14 item 14): `/namespaces/{ns}/pods/{name}`
   needs a value per placeholder, so the map is read by that placeholder's own
   name; a bare string still answers when the path names exactly one key."
  ([sample]
   (if (map? sample)
     (some (fn [[k v]] (when-not (qualified-key? k) (str v))) sample)
     (str sample)))
  ([sample placeholder idents]
   (let [n (str/replace (str placeholder) #"[{}+?]" "")
         ;; The stored template is attribute-qualified; the caller writes the
         ;; local name. Both spellings find the same value.
         local (last (str/split n #"/"))]
     (cond
       (map? sample)
       (str (or (get sample n) (get sample (keyword n))
                (get sample local) (get sample (keyword local))
                (when (<= (count idents) 1) (path-sample sample))))
       ;; One key, one string — the shape every single-key sentence has.
       (<= (count idents) 1) (str sample)
       :else ""))))

(defn url-bindings
  "What to bind the `:base_url`'s authority placeholders to for the ONE probe
   call, or nil. `url-bound` is the connector's own answer
   (`spi/connection-bound-attributes`), never a re-reading of `:base_url`.

   A map sample names them; a bare string means them only when the path is not
   templated too — with both templated, which placeholder a lone string fills
   is not the machine's to guess, so nothing is bound and the connector
   refuses on the unresolved URL rather than dialling a key as a host."
  [sample url-bound templated-path?]
  (when (seq url-bound)
    (cond
      (map? sample)
      (not-empty
       (into {} (for [a url-bound
                      :let [v (or (get sample a)
                                  (get sample (str (symbol a)))
                                  (get sample (keyword (name a)))
                                  (get sample (name a)))]
                      :when (seq (str v))]
                  [a (str v)])))

      (and (not templated-path?) (seq (str sample)))
      {(first (sort url-bound)) (str sample)})))

(defn- resolver-plan
  "One resolver-shaped sentence -> its plan, in `add_resolver`'s old argument
   vocabulary (`relation` / `input` / `outputs` / `parent` / `foreign_key`).

   Nothing outside this file calls it: the tools below translate a person's
   words into these keys, which is the whole of P8b — one translation layer
   over the same planner, so the entry a sentence produces is the entry the
   old tool produced."
  [ctx members rargs]
  (let [db (db-of ctx)
        model (pool/as-dialect db)
        ;; A hand-written list is not a source: static-v1 has no connection
        ;; entry to decide between and nothing to introspect, so the whole
        ;; source arm is skipped rather than answered with a placeholder
        ;; (need-grammar-v1 A1).
        rows  (:rows rargs)
        srcd  (if rows {} (intent/decide-source model members (:source rargs)))]
    (if (seq (:findings srcd))
      srcd
      (let [src (when-not rows (intent/source-config ctx db (:source srcd)))
            ;; What the CONNECTION binds (doc 106 §106.14 item 11). Asked of
            ;; the connector, never read off `:base_url` here: the position
            ;; rule («authority only») has one reading, and it is api-v1's.
            url-bound (when src (spi/connection-bound-attributes src))
            ;; With the host templated, `sample` names the instance to read
            ;; the fields FROM — the same parameter, the same one-off use, as
            ;; the sample that fills a templated path.
            ;; `:endpoint` is the TEMPLATE the caller wrote (`:path` is the
            ;; probe's, already substituted), which is what says whether a lone
            ;; string sample is already spoken for by the path.
            bindings (url-bindings (:sample rargs) url-bound
                                   (boolean (re-find #"\{[^{}]*\}" (str (:endpoint rargs)))))]
        (if (and (not rows) (nil? src))
          {:findings [(f/make :tools.intent/name-unresolved :error [:source]
                              "no source is registered in the pool under this id"
                              :got (:source srcd))]}
          (let [st (if rows
                     {:description (intent/rows->description rows (:relation rargs))}
                     (structure-of ctx db src (assoc rargs :source (:source srcd)
                                                      :bindings bindings)))]
            (if (seq (:findings st))
              st
              (let [nsd (intent/decide-namespace members (:namespace rargs) (:relation rargs))
                    ;; Words in, ids out (8.4.14 item 5): the parent is named
                    ;; the way a person names it and resolved through
                    ;; resolve_target's four verdicts.
                    ;; A compound parent key arrives as a LIST — one name per
                    ;; part (owner 2026-09-07, doc 106 §106.14 item 14) — and
                    ;; each is resolved through resolve_target's four verdicts
                    ;; just as the single one always was.
                    par-names (when (contains? rargs :parent)
                                (let [p (:parent rargs)]
                                  (if (sequential? p) (vec p) [p])))
                    pars (mapv #(intent/resolve-name db :attribute %
                                                     {:abbreviations (:abbreviations ctx)
                                                      :path [:parent]})
                               (or par-names []))
                    par-findings (vec (mapcat :findings pars))]
                (if (or (seq (:findings nsd)) (seq par-findings))
                  (if (seq (:findings nsd)) nsd {:findings par-findings})
                  (let [plan (intent/plan-add-resolver
                              db
                              {:members members :model model
                               :description (:description st)}
                              {:relation (:relation rargs)
                               :source (:source srcd)
                               :api (when (:probe st)
                                      {:path (:path rargs)
                                       ;; The endpoint STORED may be a template
                                       ;; the probe could not call (doc 106
                                       ;; §106.12 item 5 ②): the probe was
                                       ;; given one concrete key, the model
                                       ;; keeps `{X/k}`.
                                       :endpoint (or (:endpoint rargs) (:path rargs))
                                       :params (:params rargs)
                                       :data-path (:data-path st)})
                               :static (when rows {:rows rows})
                               :input (:input rargs)
                               :outputs (:outputs rargs)
                               :prefer-outputs (:prefer_outputs rargs)
                               :namespace (:namespace nsd)
                               :identity-columns (:identity-columns rargs)
                               :parent (when (seq pars)
                                         (if (= 1 (count pars))
                                           (:id (first pars))
                                           (mapv :id pars)))
                               :foreign-key (:foreign_key rargs)
                               :collection-name (:collection_name rargs)
                               :filters (:filters rargs)
                               ;; **What the CONNECTION binds** (owner
                               ;; 2026-09-07, doc 106 §106.14 item 11): an
                               ;; api source whose `:base_url` names
                               ;; `{nifi.instance/host}` picks its instance
                               ;; from that attribute, so a lookup under it is
                               ;; keyed on a value the records never echo. The
                               ;; connector answers which attribute that is —
                               ;; this face does not read `:base_url` itself,
                               ;; or the position rule would have two readings.
                               :url-bound url-bound})]
                    (cond-> plan
                      (:probe st) (assoc :probe (:probe st))
                      (:probe-fill st) (update :fill into (:probe-fill st)))))))))))))

(defn- errors-of [m] (filter #(= :error (:severity %)) (:findings m)))

;; ---------------------------------------------------------------------------
;; The planner's words -> this tool's words (doc 106 §106.12 item 8.8)
;; ---------------------------------------------------------------------------

(def ^:private tool-parameter-names
  "One table, per tool: the planner argument a refusal points at → the
   parameter the CALLER actually sent.

   `plan-add-resolver` speaks `relation` / `input` / `outputs` /
   `foreign_key`, and no sentence tool declares any of them. A refusal at
   `:path [:input]` reaching `add_attribute` names a parameter that tool does
   not have, and finding-v1 §2 makes `:path` the position to FIX — so it is a
   refusal nobody can act on (8.4.25, measured again on the v10 rebuild).

   It is a rename of the position and nothing else: the code, the message and
   the `:got` are the planner's and travel whole, because two wordings for one
   defect is the second vocabulary D1.8 forbids."
  {"add_thing"     {:relation :from  :outputs :attributes :input :identity
                    :foreign_key :from :resolver :name :parent :from}
   "add_attribute" {:relation :from  :outputs :name       :input :thing
                    :foreign_key :from :source :thing     :parent :thing}
   "add_relation"  {:relation :from  :outputs :attributes :input :via
                    :foreign_key :via  :source :source}})

(defn- name-paths
  "Rewrite the HEAD of every finding's `:path` through this tool's table.
   Segments after the head are the planner's detail (a relation name, a column)
   and are left as they are."
  [tool plan]
  (if-let [table (get tool-parameter-names tool)]
    (update plan :findings
            (fn [fs]
              (mapv (fn [f]
                      (if-let [p (get table (first (:path f)))]
                        (assoc f :path (into [p] (rest (:path f))))
                        f))
                    (or fs []))))
    plan))

(defn- combine-plans
  "Two plans of ONE sentence -> one plan.

   B2 is not a second act: «every group has many processors, by parentGroupId»
   states the way back at the same time (need-grammar-v1 B1), and two applies
   for one sentence is the half-applied state every intent tool refuses to
   create."
  [a b]
  (-> a
      (assoc :actions (into (vec (:actions a)) (:actions b)))
      (assoc :fill (into (vec (:fill a)) (:fill b)))
      (assoc :findings (into (vec (:findings a)) (:findings b)))
      (assoc :recipe-delta
             (merge-with (fn [x y] (merge-with into x y))
                         (:recipe-delta a) (:recipe-delta b)))))

(defn- run-resolver-plan
  "Plan through `resolver-plan`, then hand it to the ONE apply path. A plan
   already carrying an error is returned as it stands — nothing is preflighted
   or written for a sentence the pool could not read.

   `tool` names which table `name-paths` translates the planner's parameter
   names through (item 8.8): the planner's refusals are the planner's, but the
   position they point at must be one this caller sent."
  [tool ctx args members rargs]
  (let [p (name-paths tool (resolver-plan ctx members rargs))]
    (if (seq (errors-of p))
      ;; A refusal carries its repair here too (8.4.21 裁定十五 ①): this half
      ;; never reaches `run`, and a caller reading `:tools.intent/already-said`
      ;; without the call that fixes it is exactly who the ruling is for.
      (name-paths tool (with-repairs ctx args p))
      (name-paths tool (run ctx args p)))))

(def ^:private api-shape-params
  "The api-only parameters every sentence that READS a source carries, plus
   `apply`. One list, because a person answering «where do these records live»
   answers it the same way whichever sentence they are saying."
  [{:name "params" :type :map :required? false
    ;; The value lands in the resolver's `:query_params`, declared
    ;; `[:map-of :string :string]`. A JSON client can only send string keys, so
    ;; they arrive keywordised — restated against that schema rather than by a
    ;; rule written here (doc 106 §106.14 item 9).
     :coerce #(dialect/coerce-json-keys :query-params %)
    :description
    (str "api sources only: query parameters for the ONE call this tool makes, fixed in the "
         "model — ?recursive=true. NOT what a query may narrow the result by; that is "
         "`filters`.")}
   {:name "data_path" :type :string :required? false
    :description
    (str "api sources only: the JSONPath locating the RECORDS in the response — "
         "$.data.items for {\"data\":{\"items\":[…]}}. Omitted, the first array of objects "
         "is used and the plan reports which path that was, under :fill.")}
   {:name "sample_size" :type :integer :required? false
    :description
    (str "api sources only: how many records are read before sampling stops. Default 1000; "
         "the byte ceiling still wins when it comes first.")}
   {:name "apply" :type :boolean :required? false
    :description "Write the plan. Default false — you get the plan back and nothing is written."}])

(def ^:private sample-param
  "`sample` — one real key value, used ONCE to call a templated path.

   Every sentence that reads an api path UNDER a key needs it, not just
   `add_thing`: A2's second source is fetched at `/processors/{id}/state`, and
   B1's child collection at `/process-groups/{id}/processors`. 5.2 gave it to
   `add_thing` alone, and the v9 rebuild paid for that with twelve
   `update_resolver` calls whose only job was putting the template back (doc
   106 §106.12 item 7.1)."
  {:name "sample" :type :any :required? false
   :description
   (str "One real value of the key the path is templated on, used ONCE to call it so the "
        "fields can be read — /processors/{id}/state with sample=3f2a… calls "
        "/processors/3f2a…/state. The value is never stored; the model keeps the template. "
        "When the source's :base_url also names an attribute in its HOST there are two "
        "placeholders and one string cannot fill both: give a map naming each — "
        "{\"nifi.instance/host\": \"localhost\", \"id\": \"3f2a…\"}.")})

(def ^:private filters-param
  "`filters` — the ADVANCED half of a collection, carried rather than said.

   `params` and `filters` are two different things and the v10 rebuild
   conflated them (doc 106 §106.12 item 8.3): `params` are the HTTP query
   parameters of the one call this tool makes, fixed in the model; `filters`
   are what a QUERY may narrow the collection by, dialect `:eql-params`.
   The grammar has no sentence for a parameterised query and gets none
   (need-grammar-v1 §4) — this is the tool carrying the Pathom word to where it
   belongs, so that saying it does not cost an `update_resolver`."
  {:name "filters" :type :map :required? false
   :coerce #(dialect/coerce-json-keys :eql-params %)
   :description
   (str "Advanced: what a QUERY may narrow this collection by — "
        "{\"state\": {\"attribute\": \"state\", \"op\": \"eq\", \"description\": \"…\"}}. "
        "Written to the resolver's :eql-params as given. This is NOT `params`: `params` are "
        "the query string of the one call this tool makes and are fixed in the model, while "
        "these are parameters the caller of a query supplies. `attribute` is a field of this "
        "thing (a full id also works); `op` is one of eq, in, lt, lte, gt, gte, like.")})

(defn- templated-path-1
  "A templated api path with ONE placeholder + one sample -> `{:probe …
   :endpoint …}`, or a refusal.

   The path PROBED has the sample in it; the path STORED is the template, and
   the planner qualifies its placeholder with the attribute the records are
   keyed on (need-grammar-v1 §3.2). Shared by `add_thing`, `add_attribute` and
   `add_relation` so one sentence's answer to «which record» is the same in
   all three."
  [from sample]
  (let [ph (re-find #"\{[^{}]*\}" (str from))]
    (cond
      (nil? ph) {:probe (str from) :endpoint (str from)}
      (str/blank? (str sample))
      {:findings [(f/make :tools.intent/probe-refused :error [:sample]
                          (str "a templated path cannot be called as it stands; give one real "
                               "value of the key and it is called once, with that value, to "
                               "read the fields")
                          :got (str from)
                          :refs ["need-grammar-v1 §3.2"])]}
      ;; A key value that is ITSELF a path (`apache/nifi`) under a plain
      ;; `{attr}` placeholder encodes to `apache%2Fnifi` — one segment — and the
      ;; graph builds, cuts, and then 404s on every keyed query
      ;; (OQ-PATH-KEY-ENCODING). api-v1 spells the other case `{+attr}`. This
      ;; refuses and points there rather than rewriting the template: which of
      ;; the two a path means is the modeller's sentence, not ours (owner
      ;; 2026-09-07, doc 106 §106.14 item 8). The template is echoed — it is
      ;; configuration; the sample is not — it is a live key value.
      (and (str/includes? (str sample) "/") (not (str/starts-with? ph "{+")))
      {:findings [(f/make :tools.intent/placeholder-encoding :error [:from]
                          (str "the key value contains a / and the path is templated " ph
                               ", which encodes the whole value into one path segment; spell it "
                               "{+" (subs ph 1) " (api-v1 reserved expansion) to keep the separators")
                          :expected (str/replace (str from) ph (str "{+" (subs ph 1)))
                          :got (str from)
                          :refs ["api-v1 «{+属性} 保留展开»"])]}
      :else {:probe (str/replace (str from) ph (str sample)) :endpoint (str from)})))

(defn- templated-path
  "The same judgement over a path that may name SEVERAL keys.

   A compound key templates one placeholder per part —
   `/namespaces/{ns}/pods/{name}` (owner 2026-09-07, doc 106 §106.14 item 14)
   — and each is substituted from its own entry in a `sample` map. One
   placeholder keeps the bare-string spelling and the original judgement."
  ([from sample] (templated-path from sample nil))
  ([from sample keys*]
   (let [phs (vec (re-seq #"\{[^{}]*\}" (str from)))]
     (if (<= (count phs) 1)
       (templated-path-1 from (if (map? sample) (path-sample sample) sample))
       (let [val-of  (fn [ph] (str (path-sample sample ph phs)))
             missing (filterv #(str/blank? (val-of %)) phs)
             slashed (filterv #(and (str/includes? (val-of %) "/")
                                    (not (str/starts-with? % "{+")))
                              phs)]
         (cond
           (seq missing)
           {:findings [(f/make :tools.intent/probe-refused :error [:sample]
                               (str "a templated path cannot be called as it stands; give one "
                                    "real value of EVERY key it names and it is called once, "
                                    "with those values, to read the fields")
                               :expected (mapv #(str/replace % #"[{}+?]" "") missing)
                               :got (str from)
                               :refs ["need-grammar-v1 §3.2"])]}
           (seq slashed)
           {:findings [(f/make :tools.intent/placeholder-encoding :error [:from]
                               (str "the key value contains a / and the path is templated "
                                    (first slashed) ", which encodes the whole value into one "
                                    "path segment; spell it {+" (subs (first slashed) 1)
                                    " (api-v1 reserved expansion) to keep the separators")
                               :expected (str/replace (str from) (first slashed)
                                                      (str "{+" (subs (first slashed) 1)))
                               :got (str from)
                               :refs ["api-v1 «{+属性} 保留展开»"])]}
           :else
           {:probe (reduce (fn [acc ph] (str/replace acc ph (val-of ph))) (str from) phs)
            :endpoint (str from)}))))))

;; ---------------------------------------------------------------------------
;; add_thing — «有一种东西叫 X，用 k 认，来自 ⟨源⟩» (need-grammar-v1 A1 + A2)
;; ---------------------------------------------------------------------------

(def add-thing-tool
  {:name "add_thing"
   :description
   (str "Say that a kind of THING exists: «there is a thing called processor, recognised by "
        "id, and it comes from /processors». Give its name, what recognises one of them "
        "(`identity`), and where they come from (`from` — a table for a database source, an "
        "endpoint path for an API one). Everything else is generated: where the thing's "
        "fields are registered, the recognising key marked as such, the fields themselves, "
        "and the way to fetch one by that key. Omit `attributes` and you get every field the "
        "source can be trusted to type; name a few and you get those. Omit `identity` when "
        "there is no key to look one up by — you then get «all of them», the only sentence "
        "that answers without being told which one. Returns a PLAN; send apply=true to write "
        "and the same words are re-read then. For an API the fields come from one real call, "
        "they are GUESSES, and every one of them is listed under :fill for you to check.")
   :capability capability-note
   :params
   (into
    [{:name "graph" :type :string :required? false
      :description "Qualified graph name, as list_graphs reports it. Omit it to use the workspace's default graph."}
     {:name "name" :type :string :required? true
      :description
      (str "What this kind of thing is called — «processor», «customer». A name with dots in "
           "it is taken as the exact place to register it.")}
     {:name "identity" :type :any :required? false
      :description
      (str "The field that recognises ONE of them — «id», «cust_no». It is registered as this "
           "thing's key. Give a LIST when it takes several fields TOGETHER to recognise one — "
           "[\"namespace\", \"name\"] for a Kubernetes pod: all of them are registered as the "
           "key, the lookup takes all of them, and a templated path names each in its own "
           "{…}. Omitted, the plan produces «all of them» rather than a lookup.")}
     {:name "from" :type :string :required? false
      :description
      (str "Where they come from: a table name for a database source, or the path to call for "
           "an API source (relative to its base URL). For an API thing fetched ONE AT A TIME "
           "write the path as a template with the key in braces — /processors/{id} — and give "
           "one real key in `sample`; the template is what gets stored. A collection endpoint "
           "with no such segment — /flow/bulletin-board — is fine with an `identity` too: you "
           "get «all of them» plus the key declared, and no one-at-a-time lookup. Leave it out "
           "only when you are giving `rows` instead.")}
     {:name "sample" :type :any :required? false
      :description
      (str "One real value of the `identity` key, used ONCE to call the templated path so the "
           "fields can be read — /processors/{id} with sample=3f2a… calls /processors/3f2a…. "
           "The value is never stored; the model keeps the template. When the source's "
           ":base_url also names an attribute in its HOST, or the key takes several fields, "
           "give a map naming each placeholder — {\"nifi.instance/host\": \"localhost\", "
           "\"id\": \"3f2a…\"} — one entry per {…} in the path.")}
     {:name "rows" :type :vector :required? false
      :description
      (str "The thing comes from a HAND-WRITTEN list instead of a source: "
           "[{\"host\": \"nifi-01\", \"env\": \"prod\"}, …]. The rows ship with the model and "
           "are part of its version, so `from` and `source` must be absent. With `identity` "
           "the list is looked up by that field; without one it answers «all of them».")}
     {:name "attributes" :type :vector :required? false
      :description
      (str "Which fields to take. Omitted, every field the source gives a type to. For an API "
           "an entry may instead be {\"name\": \"city\", \"path\": \"$.address.city\"} — a "
           "value nested inside one record, named on its own.")}
     {:name "source" :type :string :required? false
      :description "Source id. Omit when the graph names exactly one source."}
     {:name "namespace" :type :string :required? false
      :description "Advanced: the exact place to register the fields. Omitted, derived from the name."}
     {:name "collection_name" :type :string :required? false
      :description "Advanced: the local name of the «all of them» key. Defaults to all_<name>."}
     filters-param]
    api-shape-params)
   :handler
   (fn [ctx args]
     (with-graph
       ctx args
       (fn [members]
         (let [db  (db-of ctx)
               rows (:rows args)
               srcd (when-not rows
                      (intent/decide-source (pool/as-dialect db) members (:source args)))
               src (when-not rows (intent/source-config ctx db (:source srcd)))
               api? (api-source? src)
               ;; **A LIST is a compound key**: the fields named recognise one
               ;; of them TOGETHER (owner 2026-09-07, doc 106 §106.14 item 14).
               ;; A bare string is the one-field spelling and reads back as a
               ;; one-element vector, so everything below is written once.
               idents (let [i (:identity args)]
                        (cond (nil? i)        []
                              (sequential? i) (mapv name i)
                              :else           [(name i)]))
               ident (first idents)
               outs  (when (seq (:attributes args))
                       (let [named (intent/normalize-outputs (:attributes args))]
                         ;; EVERY part of the key is always produced: a thing
                         ;; whose key is not among its fields cannot be looked
                         ;; up by it, and «recognised by id» said it should be.
                         (reduce (fn [acc i]
                                   (if (some #(= i (:name %)) acc)
                                     acc
                                     (conj acc {:name i :path nil})))
                                 (vec named) idents)))
               ;; A keyed api thing is fetched at a TEMPLATE, and a template
               ;; cannot be called; the real key values make the single call
               ;; the fields are read from (doc 106 §106.12 item 5 ②). A
               ;; compound key templates SEVERAL placeholders — one per part —
               ;; each substituted from its own entry in `sample`.
               placeholders (if (and api? (seq idents))
                              (vec (re-seq #"\{[^{}]*\}" (str (:from args))))
                              [])
               placeholder (first placeholders)
               probe-path (if (seq placeholders)
                            (reduce (fn [pth ph]
                                      (str/replace pth ph
                                                   (str (path-sample (:sample args) ph idents))))
                                    (str (:from args)) placeholders)
                            (:from args))]
           (cond
             (and rows (or (seq (str (:from args))) (seq (str (:source args)))))
             {:findings [(f/make :tools.intent/mutually-exclusive :error [:rows]
                                 (str "a hand-written list IS where this thing comes from, so "
                                      "there is no source to read and no table or endpoint to "
                                      "name")
                                 :refs ["need-grammar-v1 A1" "static-v1"])]}

             (and rows (not (and (sequential? rows) (seq rows) (every? map? rows))))
             {:findings [(f/make :tools.intent/type-undecidable :error [:rows]
                                 "a hand-written list is a non-empty list of records"
                                 :expected "[{\"host\": \"nifi-01\", …}, …]")]}

             (and (not rows) (str/blank? (str (:from args))))
             {:findings [(f/make :tools.intent/name-unresolved :error [:from]
                                 (str "say where they come from — a table, an endpoint, or "
                                      "`rows` for a hand-written list"))]}

             (some #(str/blank? (str (path-sample (:sample args) % idents))) placeholders)
             {:findings [(f/make :tools.intent/probe-refused :error [:sample]
                                 (str "a templated path cannot be called as it stands; give one "
                                      "real value of EVERY key it names and it is called once, "
                                      "with those values, to read the fields")
                                 :expected (mapv #(str/replace % #"[{}+?]" "") placeholders)
                                 :got (str (:from args)))]}

             ;; Same judgement `templated-path` makes for `add_attribute` and
             ;; `add_relation`, and for the same reason (owner 2026-09-07, doc
             ;; 106 §106.14 item 8): a key value that is itself a path under a
             ;; plain `{attr}` encodes to one segment, so the thing builds, cuts
             ;; and then 404s on every keyed query. Refuse and point at api-v1's
             ;; `{+attr}`; do not choose it for the modeller.
             ;; Judged PER placeholder: with a compound key one part may be a
             ;; path and another a plain segment.
             (some (fn [ph]
                     (and (str/includes? (str (path-sample (:sample args) ph idents)) "/")
                          (not (str/starts-with? ph "{+"))))
                   placeholders)
             (let [ph (first (filter (fn [ph]
                                       (and (str/includes?
                                             (str (path-sample (:sample args) ph idents)) "/")
                                            (not (str/starts-with? ph "{+"))))
                                     placeholders))]
               {:findings [(f/make :tools.intent/placeholder-encoding :error [:from]
                                   (str "the key value contains a / and the path is templated "
                                        ph ", which encodes the whole value into one "
                                        "path segment; spell it {+" (subs ph 1)
                                        " (api-v1 reserved expansion) to keep the separators")
                                   :expected (str/replace (str (:from args)) ph
                                                          (str "{+" (subs ph 1)))
                                   :got (str (:from args))
                                   :refs ["api-v1 «{+属性} 保留展开»"])]})

             :else
             (run-resolver-plan
              "add_thing" ctx args members
              (cond-> {:relation (if (or api? rows) (:name args) (:from args))
                       :source (:source args)
                       ;; **«All of the X, recognised by k» is legal** (doc 106
                       ;; §106.12 item 7.7): an api collection endpoint with no
                       ;; `/{id}` path — /flow/bulletin-board — still answers
                       ;; records with a key, so A1's identity is declared and
                       ;; the root collection is what fetches them. Only a
                       ;; TEMPLATED path gets a by-key resolver; a literal one
                       ;; would answer the same thing for every key, which is
                       ;; why the by-key shape needs the placeholder and this
                       ;; shape does not.
                       :input (if (and (seq idents) (or (not api?) placeholder))
                                idents
                                [])
                       :identity-columns (when (seq idents) idents)
                       :outputs outs
                       :namespace (or (:namespace args)
                                      (when (str/includes? (str (:name args)) ".") (:name args)))
                       :collection_name (:collection_name args)
                       :params (:params args)
                       :filters (:filters args)
                       :data_path (:data_path args)
                       :sample_size (:sample_size args)
                       ;; Carried so a host-templated `:base_url` can be bound
                       ;; for the one probe call (doc 106 §106.14 item 11).
                       :sample (:sample args)}
                rows (assoc :rows (vec rows))
                api? (assoc :path probe-path :endpoint (:from args)))))))))})

;; ---------------------------------------------------------------------------
;; add_relation — «每个 B 有很多 A» / «每个 A 属于一个 B» / «每个 A 有一个 B»
;; (need-grammar-v1 B1 / B2 / B4)
;; ---------------------------------------------------------------------------

(defn- thing-namespace
  "A thing's name -> where its fields are registered.

   A person says «processor»; the pool holds `nifi.flow.processor`. An exact
   match wins; otherwise the last segment is matched, and two things ending in
   the same word are refused with both rather than ranked — the discipline
   `resolve_target` applies to object names."
  [model name* path]
  (let [n0 (str name*)
        ;; A person may hand over the thing's KEY («shop.customers/cust_no»)
        ;; where the thing was asked for — every other tool takes ids there,
        ;; and refusing on that would be pedantry about which half they wrote.
        n (if (str/includes? n0 "/") (first (str/split n0 #"/")) n0)
        nss (keys (:namespaces model))]
    (if (contains? (:namespaces model) n)
      {:namespace n}
      (let [hits (filterv #(= n (last (str/split % #"\."))) nss)]
        (case (count hits)
          1 {:namespace (first hits)}
          0 {:findings [(f/make :tools.intent/name-unresolved :error [path]
                                "no thing in this pool goes by that name"
                                :got n :expected (vec (sort nss)))]}
          {:findings [(f/make :tools.intent/name-ambiguous :error [path]
                              "more than one thing goes by that name; say which"
                              :got (vec (sort hits)))]})))))

(defn- carried-into
  "`{namespace #{attribute …}}` — what the engine writes INTO a thing's rows
   rather than reading out of a record (doc 106 §106.14 items 13 and 17).

   Asked of the SPI seam, which is where «which attributes does this source
   bind» is decided; the write side must read a key back the same way
   `need-view` does, or `add_thing` would say `[host id]` and `inspect_graph`
   would answer `[id]` (§106.5)."
  [model]
  (spi/carried-attributes model))

(defn- thing-identity
  "The fields a thing is recognised by — `{:attribute k :attributes [k …]
   :own [k …]}`.

   **Several are ONE compound key, not an ambiguity** (owner 2026-09-07, doc
   106 §106.14 item 14): a relation to such a thing hangs on all of them
   together. Before the ruling this refused with `:name-ambiguous`, which
   asked the modeller to pick one half of a key — there was no answer to give.

   `:attributes` is the WHOLE key since item 18: the namespace's own
   `:identity? true` attributes plus the attributes CARRIED into its rows that
   its by-key resolver takes in, in that resolver's `:input` order. That is the
   set a lookup has to be matched against — a templated source's lookup is
   keyed on `[host id]`, and comparing it against `[id]` found no lookup at all.

   `:own` is the declared half alone, and it is what a relation's `via` pairs
   with: a carried attribute needs no field in the child pointing back, because
   the engine already writes it into the child's rows. Asking for one would be
   asking the modeller to store the host in every row.

   A thing with NO key is still refused and named: there is nothing for the
   relation to hang on, and guessing would be picking what the relation is
   keyed on. `:attribute` is the first key, kept for the callers that name one
   field in a message."
  [model ns-str path]
  (let [own (vec (sort (for [[k v] (:attributes model)
                             :when (and (= ns-str (namespace k)) (:identity? v)
                                        (not (:target v)))]
                         k)))
        carried (get (carried-into model) ns-str)
        full (or (some-> (dialect/by-key-resolver-of model {ns-str carried} ns-str own)
                         :input
                         (->> (filterv (into (set own) carried))))
                 own)]
    (if (seq own)
      {:attribute (first own) :attributes (if (seq full) full own) :own own}
      {:findings [(f/make :tools.intent/name-unresolved :error [path]
                          (str "this thing has nothing marked as the field that recognises "
                               "one of them, so a relation to it has no key to hang on")
                          :got ns-str)]})))

(defn- via-names
  "`via` -> the child's own field names, always a vector. A list points at a
   COMPOUND parent key, one field per part (owner 2026-09-07, doc 106 §106.14
   item 14); a bare string is the one-field spelling."
  [via]
  (cond (nil? via)        []
        (sequential? via) (mapv #(str (if (keyword? %) (symbol %) %)) via)
        (str/blank? (str via)) []
        :else             [(str via)]))

(defn- child-namespace
  "Where the child's fields go.

   Unlike the parent, the child of «has many» need NOT exist yet: «every user
   has many posts» is often the sentence that introduces posts, and the same
   planner that registers them decides where they live. So an unknown name is
   not a refusal here — it is passed to the same namespace decision every
   other new thing goes through."
  [model members child]
  (let [t (thing-namespace model child :child)]
    (if (:namespace t)
      t
      (intent/decide-namespace members
                               (when (str/includes? (str child) ".") child)
                               child))))

(defn- via-path
  "`add_relation`'s share of `name-paths` — the planner's `foreign_key` is this
   tool's `via`, and the rest of its table with it."
  [plan]
  (name-paths "add_relation" plan))

(def add-relation-tool
  {:name "add_relation"
   :description
   (str "Say how two things relate, in the two sentences a person actually says. «Every "
        "process group HAS MANY processors» is kind=many (the default): name the parent, the "
        "child, and — for a database source — the child field pointing back at the parent "
        "(`via`); for an API name the child endpoint instead (`from`), and `via` is optional "
        "there because the endpoint already says which parent it hangs under. You get the key "
        "on the PARENT that reaches its children in one query, and, when `via` is known, the "
        "child's key back. «Every processor HAS ONE group» is kind=one: a reference, so only "
        "the child's key back is made and no reverse «the group has many processors» is "
        "invented — an instance has a root group, but a group does not have many instances. "
        "Returns a PLAN; apply=true writes it.")
   :capability capability-note
   :params
   (into
    [{:name "graph" :type :string :required? false
      :description "Qualified graph name. Omit it to use the workspace's default graph."}
     {:name "parent" :type :string :required? true
      :description "The thing on the ONE side — «process group». Its name, or the exact place it is registered."}
     {:name "child" :type :string :required? true
      :description
      (str "The thing on the MANY side — «processor». For kind=many over a database source "
           "this is also the table the children come from.")}
     {:name "kind" :type :keyword :required? false
      :description "many (default) — the parent has many children; or one — the child has one parent."
      :enum [:many :one]}
     {:name "via" :type :any :required? false
      :description
      (str "The child's own field pointing at the parent — «parentGroupId». Required over a "
           "database source; optional over an API one. When the parent is recognised by "
           "several fields together, give a LIST with one field per part of that key, in the "
           "same order list_entities reports it.")}
     {:name "from" :type :string :required? false
      :description
      (str "Where the children come from, when it is not called the same as the child: an "
           "endpoint under the parent for an API source (/process-groups/{id}/processors), or "
           "the table name for a database one. Refused with kind=one, which fetches nothing. "
           "Starting with $ it is an ARRAY INSIDE THE PARENT'S OWN RECORD "
           "($.processors[*]): the children come back with the parent, so no endpoint of "
           "their own is called. Give `sample` — one real value of the PARENT's key — and "
           "that record is read once to learn the child's fields, so the child needs no "
           "separate add_thing first; without it the child's fields must already be "
           "registered. `attributes` narrows to some of them either way.")}
     {:name "name" :type :string :required? false
      :description "What to call the new key. Defaults to the child's name (many) or the parent's (one)."}
     {:name "attributes" :type :vector :required? false
      :description
      (str "Which of the child's fields the collection carries. Omitted over a database source "
           "you get every field it can be trusted to type; omitted over an API one you get the "
           "fields the child thing is ALREADY known to have, so a collection endpoint does not "
           "smuggle in whatever else that record happens to carry. An entry may be "
           "{\"name\": \"city\", \"path\": \"$.address.city\"}, as in add_thing.")}
     {:name "source" :type :string :required? false
      :description "Source id. Omit when the graph names exactly one source."}
     filters-param
     sample-param]
    api-shape-params)
   :handler
   (fn [ctx args]
     (with-graph
       ctx args
       (fn [members]
         (let [db (db-of ctx)
               model (pool/as-dialect db)
               srcd (intent/decide-source model members (:source args))
               src (intent/source-config ctx db (:source srcd))
               api? (api-source? src)
               one? (= :one (some-> (:kind args) name keyword))
               pn (thing-namespace model (:parent args) :parent)
               cn (child-namespace model members (:child args))]
           (cond
             (seq (:findings pn)) pn
             (seq (:findings cn)) cn

             ;; **The children are in the parent's own record** (owner
             ;; 2026-09-07, doc 106 §106.14 item 15): `from` written as a
             ;; JSONPath says so. Judged FIRST, because it is a different
             ;; sentence from «read them from somewhere» — the checks below
             ;; ask which statement or endpoint fetches them, and this one
             ;; fetches nothing of its own.
             (and (not one?) (str/starts-with? (str (:from args)) "$"))
             (cond
               (not api?)
               {:findings [(f/make :tools.intent/mutually-exclusive :error [:from]
                                   (str "a collection inside a record is an API shape; "
                                        "a JSON column of a database source is not read "
                                        "this way")
                                   :got (str (:from args))
                                   :refs ["doc 106 §106.14 item 15"])]}
               (seq (str (:via args)))
               ;; `via` is how a SEPARATE request is matched back to a parent;
               ;; rows that came out of the parent's own record are already
               ;; where they belong. The way back is still sayable — as its own
               ;; sentence, `kind one`.
               {:findings [(f/make :tools.intent/mutually-exclusive :error [:via]
                                   (str "children that come out of the parent's own "
                                        "record need no field pointing back at it; say "
                                        "the way back on its own with kind=one")
                                   :got (str (:via args))
                                   :refs ["need-grammar-v1 B4" "doc 106 §106.14 item 15"])]}
               :else
               (let [pid (thing-identity model (:namespace pn) :parent)]
                 (if (seq (:findings pid))
                   pid
                   ;; **The child's fields are typed from the PARENT's record**
                   ;; (doc 106 §106.12 item 10.4). The elements of the array
                   ;; are the child's rows, and they arrive in the very request
                   ;; that reads the parent — so `sample` (one real value of
                   ;; the parent's key) re-reads that record once and the child
                   ;; needs no scaffold `add_thing` said and then deleted. No
                   ;; endpoint is asked for: it is already written on the
                   ;; parent's own lookup.
                   (let [host (intent/api-lookup-of model (:attribute pid))
                         tmpl (get-in host [:source :endpoint])
                         ;; `:_from` names the array WITH its `[*]` and is
                         ;; relative to the parent's RECORD; the probe locates
                         ;; the array in the whole response, so the parent's
                         ;; own `data_path` goes in front of it.
                         arr  (let [a (str/replace (str (:from args)) #"\[\*\]$" "")
                                    pdp (str (get-in host [:source :data_path]))]
                                (if (and (seq pdp) (not= "$" pdp)
                                         (str/starts-with? a "$"))
                                  (str pdp (subs a 1))
                                  a))
                         sample? (seq (str (:sample args)))
                         url-bound (spi/connection-bound-attributes src)
                         tp   (when (and sample? host) (templated-path tmpl (:sample args)))
                         st   (when (and tp (empty? (:findings tp)))
                                (structure-of
                                 ctx db src
                                 {:source (:source srcd)
                                  :relation (last (str/split (str (:namespace cn)) #"\."))
                                  :path (:probe tp)
                                  :data_path arr
                                  ;; A `{name, path}` entry names a value
                                  ;; inside ONE element and gives it a name of
                                  ;; its own — the same spelling `add_thing`
                                  ;; takes, and the probe types it the same
                                  ;; way, so `attributes` is one vocabulary
                                  ;; across the two tools.
                                  :outputs (:attributes args)
                                  :bindings (url-bindings (:sample args) url-bound
                                                          (boolean (re-find #"\{[^{}]*\}"
                                                                            (str tmpl))))}))]
                     (cond
                       (and tp (seq (:findings tp))) tp
                       (and st (seq (errors-of st))) st
                       :else
                       (via-path
                        (run ctx args
                             (cond->
                              (intent/plan-add-in-record-collection
                               db {:model model :description (:description st)}
                               {:parent (:attribute pid)
                                :child-ns (:namespace cn)
                                :from (str (:from args))
                                :name (:name args)
                                :attributes (:attributes args)
                                :filters (:filters args)
                                :url-bound url-bound})
                               (:probe st) (assoc :probe (:probe st))))))))))

             ;; A sql child collection is `WHERE fk IN (…)`, and there is no
             ;; such statement without the field (need-grammar-v1 B1-sql).
             (and (not one?) (not api?) (empty? (via-names (:via args))))
             {:findings [(f/make :tools.intent/unknown-column :error [:via]
                                 (str "over a database source, «has many» is read by matching "
                                      "the child's own field against the parent's key; name "
                                      "that field")
                                 :refs ["need-grammar-v1 B1" "8.4.19"])]}

             ;; «Has one» reads nothing: it states a reference between things
             ;; already modelled, so an endpoint to fetch children from answers
             ;; a question this sentence did not ask.
             (and one? (seq (str (:from args))))
             {:findings [(f/make :tools.intent/mutually-exclusive :error [:from]
                                 (str "«has one» states a reference between things already "
                                      "modelled and fetches nothing, so there is no endpoint "
                                      "to call")
                                 :refs ["need-grammar-v1 B4"])]}

             (and one? (empty? (via-names (:via args))))
             {:findings [(f/make :tools.intent/unknown-column :error [:via]
                                 "«has one» is held by the child's own field pointing at the parent; name it")]}

             :else
             (let [pid (thing-identity model (:namespace pn) :parent)]
               (if (seq (:findings pid))
                 pid
                 ;; ONE resolution per part of the key (owner 2026-09-07, doc
                 ;; 106 §106.14 item 14): a compound parent key is pointed at
                 ;; by one child field per part, paired in the order given.
                 (let [vs (via-names (:via args))
                       via-attrs (mapv (fn [v]
                                         (intent/resolve-name
                                          db :attribute
                                          (if (str/includes? v "/")
                                            v
                                            (str (:namespace cn) "/" v))
                                          {:abbreviations (:abbreviations ctx) :path [:via]}))
                                       vs)
                       via-attr (first via-attrs)
                       via-findings (vec (mapcat :findings via-attrs))]
                   (cond
                     ;; The parent's key and the child's fields must pair up,
                     ;; or the branch names half a key and reaches no parent.
                     ;; Judged BEFORE the name lookups: «you gave one field for
                     ;; a two-field key» is what went wrong, and reporting the
                     ;; unresolved name first points at the wrong sentence.
                     (and (seq vs) (not= (count vs) (count (:own pid))))
                     {:findings [(f/make :tools.intent/type-undecidable :error [:via]
                                         (str "this thing is recognised by "
                                              (count (:own pid))
                                              " fields together, so the child needs one field "
                                              "per part, in that order")
                                         :expected (mapv #(str (symbol %)) (:own pid))
                                         :got vs
                                         :refs ["doc 106 §106.14 item 14"])]}

                     (and one? (seq via-findings)) {:findings via-findings}

                     one?
                     (run ctx args
                          (intent/plan-add-relation-one
                           db {:model model}
                           ;; `:own`, not the whole key: a carried half needs
                           ;; no field in the child pointing back — the engine
                           ;; writes it into the child's rows already (doc 106
                           ;; §106.14 items 13, 17 and 18).
                           {:parent (:own pid) :via (mapv :id via-attrs)
                            :name (:name args)}))

                     :else
                     ;; B1: the parent's collection — 8.4.19's three-piece set
                     ;; over sql, an endpoint under the parent over api.
                     (let [child-name (last (str/split (:namespace cn) #"\."))
                           ;; **What the collection carries** (doc 106 §106.12
                           ;; item 5 ③). A sql relation's typeable columns ARE
                           ;; the child's fields; an api collection endpoint
                           ;; answers whatever that record holds, and taking
                           ;; all of it registered camelCase junk beside the
                           ;; fields the child thing was modelled with. So over
                           ;; api the default is the child's ALREADY registered
                           ;; fields, and only when the child is new does the
                           ;; probe's own list stand.
                           known (vec (sort (for [[k _] (dialect/scalar-attributes model)
                                                  :when (= (:namespace cn) (clojure.core/namespace k))]
                                              (clojure.core/name k))))
                           named (when (seq (:attributes args))
                                   (intent/normalize-outputs (:attributes args)))
                           defaulted (when (and (nil? named) api? (seq known)) known)
                           ;; The child's own field pointing at the parent must
                           ;; stay in the rows: §106.14 item 5's key is read out
                           ;; of it, and a narrowed list that dropped it would
                           ;; refuse the sentence it is part of.
                           with-fk (fn [os nm]
                                     (reduce (fn [acc fk]
                                               (if (some #(= fk (nm %)) acc)
                                                 acc
                                                 (conj acc (if (= nm :name)
                                                             {:name fk :path nil}
                                                             fk))))
                                             (vec os)
                                             (mapv #(last (str/split % #"/")) vs)))
                           outs (when named (with-fk named :name))
                           prefer (when defaulted (with-fk defaulted identity))
                           ;; A child collection lives UNDER the parent's key
                           ;; (/process-groups/{id}/processors), so the path is
                           ;; templated exactly as `add_thing`'s is: probed
                           ;; with one real key, stored as the template (doc
                           ;; 106 §106.12 item 7.1).
                           tp (when api? (templated-path (:from args) (:sample args)))
                           base (if (seq (:findings tp))
                                 tp
                                 (resolver-plan
                                 ctx members
                                 (cond-> {:relation (or (:from args) child-name)
                                          :source (:source args)
                                          :namespace (:namespace cn)
                                          :parent (mapv #(str (symbol %)) (:own pid))
                                          :foreign_key (mapv #(last (str/split % #"/")) vs)
                                          :outputs outs
                                          :prefer_outputs prefer
                                          :collection_name (or (:name args) child-name)
                                          :params (:params args)
                                          :filters (:filters args)
                                          :data_path (:data_path args)
                                          :sample_size (:sample_size args)
                                          :sample (:sample args)}
                                   api? (assoc :path (:probe tp)
                                               :endpoint (:endpoint tp)))))]
                       (cond
                         (seq (errors-of base)) (via-path base)

                         ;; **B2 rides along** whenever the child's own field
                         ;; is known (doc 106 §106.3 B1, restored by §106.14
                         ;; item 5 on 2026-09-06): the way back is not a second
                         ;; sentence, and it is publishable now that the branch
                         ;; `{A/b [B/k]}` is produced by the child's resolvers
                         ;; rather than by nobody (need-grammar-v1 §5.1).
                         (empty? vs) (via-path (run ctx args base))

                         :else
                         (let [pending (vec (keep (fn [a] (when (= :resolver (:type a))
                                                            (:entry a)))
                                                  (:actions base)))
                               one (intent/plan-add-relation-one
                                    db {:model model}
                                    {:parent (:own pid)
                                     :via (mapv (fn [a v]
                                                  (or (:id a)
                                                      (keyword (:namespace cn)
                                                               (last (str/split v #"/")))))
                                                via-attrs vs)
                                     ;; B1 registers the child's columns in the
                                     ;; same write, so the fk is legitimately
                                     ;; not in the pool yet.
                                     :via-pending? true
                                     :pending pending})]
                           (run ctx args
                                (via-path (intent/merge-relation-plans base one)))))))))))))))})

;; ---------------------------------------------------------------------------
;; add_attribute — «X 有属性 a…» / «来自另一个来源» / «算出来的»
;; (need-grammar-v1 A2 / A3)
;; ---------------------------------------------------------------------------

(defn- by-key-resolver-of
  "The way a thing is fetched by its own key — the resolver `add_thing` wrote.

   A thing fetched from several places has several; the FIRST by id is taken
   and is deterministic, because «which of my own lookups gets one more field»
   is not a modelling judgement (8.4.21 裁定十五 ④: structural naming is
   defaulted, never asked)."
  [model ns-str id-attrs]
  ;; The input is compared as a SET, so a compound key matches whatever order
  ;; the resolver wrote it in (owner 2026-09-07, doc 106 §106.14 item 14).
  ;; `id-attrs` is the WHOLE key — the carried half included (item 18) — which
  ;; is what makes this equality hold on a templated source, where every
  ;; lookup also takes the host in.
  (let [want (set (if (sequential? id-attrs) id-attrs [id-attrs]))]
    (first (sort-by :id (filter #(and (= want (set (:input %)))
                                      (= "single" (:result_type %)))
                                (:resolvers model))))))

(defn- thing-source
  "Which source «another place for THIS thing's fields» reads from.

   The thing is already fetched from somewhere, and that somewhere is written
   on its own resolvers — so a pool holding two sources does not make the
   answer a judgement call. Only when the thing's own resolvers disagree is
   the caller asked, and then at `:thing`: `add_attribute` declares no
   `source`… it does now, but a refusal must never point at a parameter the
   caller did not send (8.4.25 «拒绝点名声明里的参数名»; doc 106 §106.12 item
   7.3, measured on the v9 rebuild)."
  [model ns-str explicit members]
  (if explicit
    {:source explicit}
    (let [refs (into (sorted-set)
                     (keep (fn [r]
                             (when (and (:source-ref r)
                                        (some #(= ns-str (clojure.core/namespace %))
                                              (dialect/direct-output-keys (:output r))))
                               (:source-ref r))))
                     (:resolvers model))]
      (case (count refs)
        1 {:source (first refs)}
        ;; The thing reads from nowhere yet — fall back to the graph's own
        ;; single source, which is `decide-source`'s question, not this one's.
        0 (intent/decide-source model members nil)
        {:findings [(f/make :tools.intent/source-undecidable :error [:thing]
                            (str "this thing is already fetched from more than one place, so "
                                 "which of them the new field comes from is not the machine's "
                                 "to pick; name the thing's other lookup with `resolver`")
                            :got (vec refs))]}))))

(defn- path-shape
  "A path with every `{…}` reduced to `{}` — «is this the same endpoint».

   The stored template is qualified (`/processors/{ns/id}`) and the one a
   caller just wrote is not (`/processors/{id}`), so they are compared by the
   shape they share rather than by re-deriving the qualification here (doc 106
   §106.12 item 8.1)."
  [p]
  (str/replace (str p) #"\{[^{}]*\}" "{}"))

(defn- existing-source-resolver
  "The resolver that ALREADY fetches this thing by its key from this place, or
   nil (doc 106 §106.12 item 8.1).

   `explicit` is the caller's own `resolver` and wins outright — that is the
   parameter being honoured rather than ignored. Otherwise: same key as input,
   single result, same source, and the same endpoint (api) or the same table
   (sql). Deterministic by id, like every other structural default
   (8.4.21 裁定十五 ④)."
  [model id-attr source explicit endpoint relation]
  (if (seq (str explicit))
    (some #(when (= (str explicit) (:id %)) %) (:resolvers model))
    (first (sort-by :id
                    (filter (fn [e]
                              ;; The key is AMONG its inputs, not the whole of
                              ;; them: over a host-templated source every
                              ;; resolver also takes the URL's attribute
                              ;; (§106.14 item 11), so an equality test made
                              ;; the second sentence about one endpoint miss
                              ;; the resolver the first had just created and
                              ;; collide with it on `name-ambiguous`.
                              (and (some #{id-attr} (:input e))
                                   (= "single" (:result_type e))
                                   (= source (:source-ref e))
                                   (if endpoint
                                     (= (path-shape endpoint)
                                        (path-shape (get-in e [:source :endpoint])))
                                     (and relation
                                          (= (str relation)
                                             (str (intent/resolver-relation e)))))))
                            (:resolvers model))))))

(defn- qualified-attr
  "A field name a person said -> the id it means inside one thing."
  [ns-str n]
  (if (str/includes? (str n) "/") (str n) (str ns-str "/" (name n))))

(def add-attribute-tool
  {:name "add_attribute"
   :description
   (str "Give a thing one more field. Three sentences, and the parameters say which: «the "
        "customer has a field cust_name» — name the thing and the field, and it is taken from "
        "where that thing already comes from; «the processor has fields from ANOTHER place» — "
        "add `from` (a table, or an endpoint) and a second way of fetching that thing by its "
        "own key is made, so both sets of fields sit on one thing instead of two; «the order "
        "has a field computed from a and b» — give `computed_from` and `expression`, and "
        "nothing is read from any source. `values` states the codes THIS field may hold; when "
        "the same list of codes is shared with other things it is a concept instead "
        "(define_concept). Returns a PLAN; apply=true writes it as one transaction. Label and "
        "description are yours to give — left out they are listed under :fill and the write "
        "still runs.")
   :capability capability-note
   :params
   [{:name "graph" :type :string :required? false
     :description "Qualified graph name. Omit it to use the workspace's default graph."}
    {:name "thing" :type :string :required? false
     :description "The thing this field belongs to — «customer». Omitted, give `resolver` instead."}
    ;; Not `:required?` at the schema level because `attribute` is still
     ;; accepted as its former spelling; the handler refuses by name when
     ;; neither arrives.
    {:name "name" :type :string :required? false
     :description "The field's name, as a person says it."}
    {:name "from" :type :string :required? false
     :description
     (str "Another place this thing's fields come from — a table, or an endpoint path. Given "
          "and different from where the thing already comes from, a second way of fetching it "
          "by its own key is made. Say it again with the SAME place and the field joins that "
          "one rather than starting a third: one endpoint is one lookup.")}
    sample-param
    {:name "computed_from" :type :vector :items :string :required? false
     :description "The fields this one is computed from. With `expression`; nothing is read from a source."}
    {:name "expression" :type :string :required? false
     :description "With `computed_from`: how to compute it from those fields."}
    {:name "values" :type :map :required? false
     :coerce #(dialect/coerce-json-keys :values %)
     :description
     (str "The codes this field may hold: {\"D\": {\"label\": \"dev\"}, …}. A list of codes "
          "shared across several things belongs on a concept, not here.")}
    {:name "resolver" :type :string :required? false
     :description
     (str "Advanced: extend this exact lookup rather than the thing's own. It is honoured with "
          "`from` too — that is which of the thing's several lookups grows.")}
    {:name "attribute" :type :string :required? false
     :description "Deprecated spelling of `name`."}
    {:name "column" :type :string :required? false
     :description
     (str "Source column, when it is not called the same as the field. Over an API source it "
          "may be a JSONPath ($.status.aggregateSnapshot.queued): the field is read at that "
          "path and typed from it.")}
    {:name "type" :type :keyword :required? false
     :description "Value type. Omitted, read from the source; required for a computed field."
     :enum [:string :integer :decimal :boolean :date :datetime :json]}
    {:name "label" :type :string :required? false :description "Human label for the field."}
    {:name "description" :type :string :required? false :description "One sentence about it."}
    {:name "params" :type :map :required? false
     :description "api sources only, with `from`: query parameters for that one call."}
    {:name "data_path" :type :string :required? false
     :description "api sources only, with `from`: the JSONPath locating the records."}
    {:name "sample_size" :type :integer :required? false
     :description "api sources only, with `from`: how many records are read before sampling stops."}
    {:name "apply" :type :boolean :required? false :description "Write the plan."}]
   :handler
   (fn [ctx args]
     (let [db (db-of ctx)]
       (with-graph
         ctx args
         (fn [members]
           (let [model (pool/as-dialect db)
                 fname (or (:name args) (:attribute args))
                 tn    (when (:thing args) (thing-namespace model (:thing args) :thing))]
             (cond
               (str/blank? (str fname))
               {:findings [(f/make :tools.intent/name-unresolved :error [:name]
                                   "say what the field is called")]}

               (and tn (seq (:findings tn))) tn

               ;; A3 — computed. It reads no source, so neither `from` nor a
               ;; resolver is consulted.
               (seq (:computed_from args))
               (if (nil? tn)
                 {:findings [(f/make :tools.intent/name-unresolved :error [:thing]
                                     "a computed field belongs to a thing; name it")]}
                 (let [ns-str (:namespace tn)
                       ins (mapv #(intent/resolve-name db :attribute (qualified-attr ns-str %)
                                                       {:abbreviations (:abbreviations ctx)
                                                        :path [:computed_from]})
                                 (:computed_from args))]
                   (if-let [fs (seq (mapcat :findings ins))]
                     {:findings (vec fs)}
                     (run ctx args
                          (intent/plan-add-computed-attribute
                           db {:model model}
                           {:attribute (keyword ns-str (name fname))
                            :type (:type args)
                            :inputs (mapv :id ins)
                            :expression (:expression args)
                            :label (:label args)
                            :description (:description args)})))))

               ;; A2 second source — one more way of fetching the SAME thing by
               ;; its own key. This is the sentence doc 106 §106.13 found the
               ;; old tool face could not say: eight endpoint-shaped name spaces
               ;; that were really the fields of three things.
               (seq (str (:from args)))
               (if (nil? tn)
                 {:findings [(f/make :tools.intent/name-unresolved :error [:thing]
                                     "«from another place» is said about a thing; name it")]}
                 (let [ns-str (:namespace tn)
                       pid (thing-identity model ns-str :thing)]
                   (if (seq (:findings pid))
                     pid
                     (let [srcd (thing-source model ns-str (:source args) members)
                           src (when-not (seq (:findings srcd))
                                 (intent/source-config ctx db (:source srcd)))
                           api? (api-source? src)
                           ;; The WHOLE key: a second source is read by the
                           ;; same key as the first, and a compound key means
                           ;; every part of it (doc 106 §106.14 item 14).
                           ;; The WHOLE key, the carried half included (doc
                           ;; 106 §106.14 items 14 and 18). It is what keys the
                           ;; second lookup, so it is also what its generated id
                           ;; is named after — with `:own` the id came out
                           ;; `…_by_id` while the thing's own lookup is
                           ;; `…_by_host_id`, the two did not collide, and the
                           ;; second source was written as a THIRD resolver
                           ;; keyed on half the key instead of joining the
                           ;; `…_from_<endpoint>` shape (measured rebuilding
                           ;; nifi-local v15). The URL-bound half is excused
                           ;; from being echoed by the record either way.
                           idns (mapv name (:attributes pid))
                           idn (first idns)
                           col (some-> (:column args) name)
                           tp (when api? (templated-path (:from args) (:sample args)))
                           ;; The relation the probe / the schema describes: an
                           ;; api endpoint has no table name, so the thing's own
                           ;; short name stands for it (the same one
                           ;; `resolver-plan` passes below).
                           relname (if api? (last (str/split ns-str #"\.")) (:from args))
                           ;; **The same endpoint is the same second source**
                           ;; (doc 106 §106.12 item 8.1). Two fields from
                           ;; /flow/processors/{id}/status are one more way of
                           ;; fetching this thing, not two: the generated
                           ;; resolver id is derived from the namespace and the
                           ;; place, so the second sentence collided with the
                           ;; first on `name-ambiguous` and the caller had no
                           ;; way to say «the one you just made». Now it is
                           ;; found and EXTENDED — which is also why `resolver`
                           ;; is honoured here rather than ignored.
                           existing (existing-source-resolver
                                     model (:attribute pid) (:source srcd)
                                     (:resolver args)
                                     (when api? (:endpoint tp))
                                     (when-not api? (:from args)))]
                       (cond
                         (seq (:findings srcd)) srcd
                         (seq (:findings tp)) tp

                         ;; 7.2: the field is MATCHED by `column` and NAMED by
                         ;; `name`. Over api the rename is the nested-value
                         ;; spelling `{name, path}`; sql has no rename in this
                         ;; sentence, and a silent mis-naming is worse than
                         ;; being told so.
                         (and col (not api?) (not= col (name fname)))
                         {:findings [(f/make :tools.intent/unknown-column :error [:column]
                                             (str "over a database source this sentence names "
                                                  "the field after the column; say them the "
                                                  "same, or add the column to the thing's own "
                                                  "lookup instead")
                                             :got col
                                             :expected (name fname))]}

                         ;; Same place, resolver already there: one more
                         ;; field on it, not a second resolver (item 8.1).
                         existing
                         (let [st (if api?
                                    (structure-of ctx db src
                                                  {:source (:source srcd)
                                                   :relation relname
                                                   :path (:probe tp)
                                                   :params (:params args)
                                                   :data_path (:data_path args)
                                                   :sample_size (:sample_size args)
                                                   ;; **A JSONPath `column` is
                                                   ;; typed like `add_thing`'s
                                                   ;; `{name path}`** (doc 106
                                                   ;; §106.12 item 10.3): the
                                                   ;; probe reads that path out
                                                   ;; of the sampled records
                                                   ;; and types the field under
                                                   ;; the name it will carry.
                                                   ;; Without this the mapping
                                                   ;; was written and the type
                                                   ;; was not, so the sentence
                                                   ;; was refused unless the
                                                   ;; caller stated the type.
                                                   :outputs
                                                   (when (and col (str/starts-with? col "$"))
                                                     [{:name (name fname) :path col}])
                                                   ;; Same one-off binding as
                                                   ;; every other probe on a
                                                   ;; host-templated source.
                                                   :bindings
                                                   (url-bindings
                                                    (:sample args)
                                                    (spi/connection-bound-attributes src)
                                                    (boolean (re-find #"\{[^{}]*\}"
                                                                     (str (:from args)))))})
                                    {:description (spi/describe-schema
                                                   src nil {:names [(:from args)]})})]
                           (if (seq (errors-of st))
                             st
                             (name-paths
                              "add_attribute"
                              (run ctx args
                                   (intent/plan-add-attribute
                                    db {:model model :description (:description st)}
                                    {:resolver (:id existing)
                                     :relation relname
                                     :attribute fname
                                     :column (or col (name fname))
                                     :type (:type args)
                                     :values (:values args)
                                     :label (:label args)
                                     :description* (:description args)})))))

                         :else
                         (run-resolver-plan
                          "add_attribute" ctx args members
                          (cond-> {:relation (if api? (last (str/split ns-str #"\.")) (:from args))
                                   :source (:source srcd)
                                   :namespace ns-str
                                   :input idns
                                   ;; **The key is NOT re-produced here**
                                   ;; (§106.14 item 7, owner 2026-09-07): a
                                   ;; second by-key resolver's `:output` holds
                                   ;; the new fields only, so the key keeps one
                                   ;; producer and the cut's
                                   ;; `cut.structure/multiple-producers` stands.
                                   :outputs [(if (and col api?)
                                               {:name (name fname)
                                                :path (if (str/starts-with? col "$")
                                                        col (str "$." col))}
                                               {:name (name fname) :path nil})]
                                   :params (:params args)
                                   :data_path (:data_path args)
                                   :sample_size (:sample_size args)
                                   :sample (:sample args)}
                            api? (assoc :path (:probe tp) :endpoint (:endpoint tp)))))))))

               :else
               ;; A2 — one more field from where the thing already comes from.
               (let [rid (or (:resolver args)
                             (when tn
                               (let [pid (thing-identity model (:namespace tn) :thing)]
                                 (when (empty? (:findings pid))
                                   (:id (by-key-resolver-of model (:namespace tn)
                                                            (:attributes pid)))))))
                     entry (some #(when (= rid (:id %)) %) (:resolvers model))]
                 (if (nil? entry)
                   {:findings [(f/make :tools.intent/name-unresolved :error [:thing]
                                       (str "there is no way of fetching this thing by its own "
                                            "key to hang one more field on; say which thing, or "
                                            "give `from`")
                                       :got (str (or (:thing args) (:resolver args))))]}
                   (let [rel (some-> (get-in entry [:source :query])
                                     (->> (re-find #"(?i)\bFROM\s+([A-Za-z_][A-Za-z0-9_.]*)"))
                                     second)
                         src (intent/source-config ctx db (:source-ref entry))
                         desc (when (and src rel (not (api-source? src)))
                                (spi/describe-schema src nil {:names [rel]}))]
                     (run ctx args
                          (intent/plan-add-attribute
                           db {:model model :description desc}
                           {:resolver rid
                            :attribute fname
                            :column (:column args)
                            :type (:type args)
                            :values (:values args)
                            :label (:label args)
                            :description* (:description args)})))))))))))})

;; ---------------------------------------------------------------------------
;; add_metric — «X 的数字口径 m 是 ⟨表达式⟩» (need-grammar-v1 A4, D2.21)
;; ---------------------------------------------------------------------------

(def add-metric-tool
  {:name "add_metric"
   :description
   (str "State how a NUMBER about a thing is worked out: «the order's revenue is the sum of "
        "total_order, by customer and by month». The definition is registered against the "
        "thing and named in the graph, so everyone asking for that number gets the same one "
        "instead of each writing their own sum. `dimensions` are the fields it may be broken "
        "down by; every one of them must already be a field of some thing. Returns a PLAN; "
        "apply=true writes it.")
   :capability capability-note
   :params
   [{:name "graph" :type :string :required? false
     :description "Qualified graph name. Omit it to use the workspace's default graph."}
    {:name "thing" :type :string :required? true
     :description "The thing this number is about — «order»."}
    {:name "name" :type :string :required? true
     :description "What the number is called, e.g. sales_by_customer_month."}
    {:name "expression" :type :string :required? true
     :description "How it is worked out over that thing's rows, e.g. sum(total_order)."}
    {:name "dimensions" :type :vector :items :string :required? false
     :description "The fields it may be broken down by — names of fields, or their full ids."}
    {:name "label" :type :string :required? false :description "Human label for the number."}
    {:name "description" :type :string :required? false
     :description "What it counts, and — as important — what it does NOT count."}
    {:name "apply" :type :boolean :required? false :description "Write the plan."}]
   :handler
   (fn [ctx args]
     (let [db (db-of ctx)]
       (with-graph
         ctx args
         (fn [_members]
           (let [model (pool/as-dialect db)
                 tn (thing-namespace model (:thing args) :thing)]
             (if (seq (:findings tn))
               tn
               (let [ds (mapv #(intent/resolve-name
                                db :attribute (qualified-attr (:namespace tn) %)
                                {:abbreviations (:abbreviations ctx) :path [:dimensions]})
                              (or (:dimensions args) []))]
                 (if-let [fs (seq (mapcat :findings ds))]
                   {:findings (vec fs)}
                   (run ctx args
                        (intent/plan-add-metric
                         db {:model model}
                         {:id (:name args)
                          :entity (:namespace tn)
                          :expression (:expression args)
                          :dimensions (mapv :id ds)
                          :label (:label args)
                          :description (:description args)}))))))))))})

;; ---------------------------------------------------------------------------
;; add_link
;; ---------------------------------------------------------------------------

(def add-link-tool
  {:name "add_link"
   :description
   (str "State that two attributes are the same key (D2.3). Give the two ends in the words "
        "you have — a local name is accepted when it is unique in the pool, and an ambiguous "
        "one is refused with every candidate rather than ranked. Write it from the ONE end: "
        ":from is the parent key, :to the child's foreign key; there is no :many-to-one. "
        "A bidirectional link normalizes its two ends, so A↔B and B↔A are one link and never "
        "two. No recipe change: a link is pulled into a projection by closure once both ends "
        "are in it. Returns a PLAN; apply=true writes it.")
   :capability capability-note
   :params
   [{:name "graph" :type :string :required? false
     :description "Qualified graph name. Omit it to use the workspace's default graph."}
    {:name "from" :type :string :required? true
     :description "The parent-key attribute: a local name, or a qualified id like cis.party/customer_no."}
    {:name "to" :type :string :required? true
     :description "The child's foreign-key attribute, same forms."}
    {:name "direction" :type :keyword :required? false
     :description "bi (default) or one — whether the equivalence reads both ways."
     :enum [:both :one]}
    {:name "cardinality" :type :keyword :required? false
     :description "one-to-one or one-to-many. There is no many-to-one (D2.3)."
     :enum [:one-to-one :one-to-many]}
    {:name "apply" :type :boolean :required? false :description "Write the plan."}]
   :handler
   (fn [ctx args]
     (let [db (db-of ctx)
           a (intent/resolve-name db :attribute (:from args)
                                  {:abbreviations (:abbreviations ctx) :path [:from]})
           b (intent/resolve-name db :attribute (:to args)
                                  {:abbreviations (:abbreviations ctx) :path [:to]})]
       (if (or (seq (:findings a)) (seq (:findings b)))
         ;; Both ends are reported at once: fixing one name and being told
         ;; about the other on the next call is two round trips for one
         ;; mistake.
         {:findings (into (vec (:findings a)) (:findings b))}
         (run ctx args
              (intent/plan-add-link db {:model (pool/as-dialect db)}
                                    {:from (:id a) :to (:id b)
                                     :direction (:direction args)
                                     :cardinality (:cardinality args)})))))})

;; ---------------------------------------------------------------------------
;; remove_objects — one removal tool, kinds resolved inside (8.4.16 裁定九)
;; ---------------------------------------------------------------------------

(defn- merge-delta [a b]
  (merge-with (fn [x y] (merge-with into x y)) a b))

(defn- merge-plans
  "Several single-object plans → one plan.

   The removal of three objects is ONE act with one apply, so it is one plan:
   applying them separately would let the second refuse after the first had
   already been written, which is the half-applied state every intent tool
   refuses to create."
  [plans]
  {:actions (vec (mapcat :actions plans))
   :fill (vec (mapcat :fill plans))
   :recipe-delta (reduce merge-delta {:add {} :remove {}} (map :recipe-delta plans))
   :removed (vec (mapcat :removed plans))
   :blocked-by (vec (mapcat :blocked-by plans))
   :cascade (vec (mapcat :cascade plans))
   :orphaned (vec (mapcat :orphaned plans))
   :findings (vec (mapcat :findings plans))})

(def ^:private removal-order
  "What must be deleted before what.

   The pool refuses to delete an object something still names (`impl.action`
   step 4), so a batch is not a set — it is an order. Everything that REFERS to
   an attribute goes first, the attributes next, and the containers they lived
   in last. A source goes last of all: a resolver names it."
  {:resolver 0 :mutation 1 :link 2 :metric 3
   :attribute 4 :term 5 :unit 6 :namespace 7 :source 8})

(defn- order-removals
  "The batch's actions, de-duplicated and put in an order the pool accepts
   (doc 106 §106.12 item 8.4).

   Two things one batch has that n calls did not: an object may be named twice
   — once by the caller, once as a resolver's cascade — and the second delete
   would be refused for a revision that no longer exists; and the caller's
   order is the order they thought of the names in, not the order the reference
   graph allows. Both are decided here rather than asked of the caller, for the
   same reason `object-removal` decides the closure."
  [plan]
  (assoc plan :actions
         (vec (->> (:actions plan)
                   (reduce (fn [[seen acc] a]
                             (let [k [(:type a) (:id a)]]
                               (if (contains? seen k)
                                 [seen acc]
                                 [(conj seen k) (conj acc a)])))
                           [#{} []])
                   second
                   (sort-by #(get removal-order (:type %) 9))
                   vec))))

(defn- link-of
  "A pair of attribute names → the link they name, in either order.

   The normalization D2.3 applies on the way in is applied again on the way to
   the lookup, so «remove the link between these two» does not depend on which
   end the caller says first."
  [ctx db [from to]]
  (let [a (intent/resolve-name db :attribute from
                               {:abbreviations (:abbreviations ctx) :path [:objects]})
        b (intent/resolve-name db :attribute to
                               {:abbreviations (:abbreviations ctx) :path [:objects]})]
    (if (or (seq (:findings a)) (seq (:findings b)))
      {:findings (into (vec (:findings a)) (:findings b))}
      (let [ids [[(:id a) (:id b)]
                 (vec (sort-by (comp str symbol) [(:id a) (:id b)]))]]
        (if-let [found (some #(when (pool/object db :link %) %) ids)]
          {:id [:link found]}
          {:findings [(f/make :tools.intent/name-unresolved :error [:objects]
                              "the pool holds no link between these two attributes"
                              :got (mapv (comp str symbol) (first ids)))]})))))

(def remove-objects-tool
  "**One tool for the three removals** (8.4.16 裁定九, merging
   `remove_attribute` / `remove_resolver` / `remove_link`).

   The kind is worked out here, exactly as `change_graph_members` works it out:
   a person saying «remove these» does not sort them into pool types first.
   What each removal DOES is unchanged — `intent/plan-remove` and the pool's
   `object-removal` still decide the closure, the `:blocked-by` and the
   `:cascade`."
  {:name "remove_objects"
   :description
   (str "Take objects OUT OF THE POOL and out of every recipe naming them. Returns a PLAN "
        "first, and read it: an object something still produces or references is REFUSED "
        "with the list of what names it (:blocked-by) — quietly rewriting somebody's "
        "resolver to stop mentioning it is not what «remove this» asked for. Removing a "
        "resolver lists what goes WITH it (:cascade — attributes nothing else produces, "
        "which would otherwise stop the graph being cut) and what is left because something "
        "else still produces them (:orphaned). This removes what YOU named; it is not a "
        "sweep for objects no recipe names. To take an object out of ONE GRAPH without "
        "deleting it, use change_graph_members. apply=true writes the whole list as one "
        "transaction — all of it or none.")
   :capability capability-note
   :params
   [{:name "graph" :type :string :required? false
     :description "Qualified graph name. Omit it to use the workspace's default graph."}
    {:name "objects" :type :vector :required? true
     :description
     (str "What to remove, in the words you have: a local name unique in the pool, or a "
          "qualified id (\":cis.party/name\", a resolver id). Which KIND each one is, is "
          "worked out here. A LINK has no name of its own — it IS its endpoint pair (D2.3) "
          "— so name it as a two-element array of its ends, in either order: "
          "[\":cis.party/cust_no\", \":cis.orders/cust_no\"].")}
    {:name "apply" :type :boolean :required? false :description "Write the plan."}]
   :handler
   (fn [ctx args]
     (let [db (db-of ctx)
           resolved (mapv (fn [o]
                            (if (sequential? o)
                              (if (= 2 (count o))
                                (link-of ctx db o)
                                {:findings [(f/make :tools.intent/name-unresolved :error [:objects]
                                                    "a link is named by exactly two ends"
                                                    :got (str (count o) " given"))]})
                              (intent/resolve-any db o {:abbreviations (:abbreviations ctx)
                                                        :path [:objects]})))
                          (:objects args))]
       (if-let [fs (seq (mapcat :findings resolved))]
         ;; Every unresolved name at once: fixing one and being told about the
         ;; next on the following call is n round trips for one mistake.
         {:findings (vec fs)}
         ;; **The batch judges itself** (doc 106 §106.12 item 8.4): every
         ;; object is planned against the pool MINUS the whole list, so a
         ;; resolver and the attributes only it produces go in one call. The
         ;; resolvers are planned first because they are what names the
         ;; attributes, which is the order the pool accepts deletes in; a name
         ;; that appears twice (once said, once cascaded) is one delete.
         (let [batch (set (map :id resolved))
               ordered (concat (filter #(= :resolver (first (:id %))) resolved)
                               (remove #(= :resolver (first (:id %))) resolved))]
           (run ctx args
                (order-removals
                 (merge-plans (for [[type id] (map :id ordered)]
                                (intent/plan-remove db type id batch)))))))))})

;; ---------------------------------------------------------------------------
;; describe_objects (item 2)
;; ---------------------------------------------------------------------------

(def describe-objects-tool
  "**The words face** (8.4.16 裁定九, merging `define_term` and
   `update_entity` into 8.4.14's enrichment batch).

   All three wrote the same kind of thing — what a thing is CALLED and what it
   MEANS — onto three different kinds of object, and an agent choosing between
   three names for that was choosing by the object's type rather than by the
   act. Here the type is a field of the entry, which is where it belongs.

   The planning is unchanged: `plan-describe-objects` for pool objects,
   `plan-define-term` for terms (which creates one that does not exist yet and
   still refuses a key a term does not carry), `plan-update-entity` for
   namespaces. Nothing about D2.26 moves — the machine still names no
   concept."
  {:name "describe_objects"
   :description
   (str "Write WORDS onto many objects in one transaction — labels, descriptions, "
        "synonyms — which is the commonest act after any modelling step and used to cost "
        "one read and one write per object. The words are yours: the machine does not name "
        "a concept (D2.26), it only carries what you wrote. Revisions are read here, so you "
        "need not fetch them first.\n\n"
        "Every kind of object goes through this one tool, and a few kinds carry more than "
        "the three words: a NAMESPACE (= an entity, D2.5) also takes `kind`; a TERM also "
        "takes `format` and `values`, and a term that does not exist yet is REGISTERED by "
        "naming it here (a new term needs a label — it is the concept's name and the "
        "machine may not pick one). A term is only DEFINED here; binding it to attributes "
        "is assign_term.\n\n"
        "Returns a PLAN; apply=true writes it, all of them or none.")
   :capability capability-note
   :params
   [{:name "objects" :type :vector :required? true
     :description
     (str "One entry per object: {\"type\": \"attribute\", \"id\": \":cis.party/name\", "
          "\"label\": \"…\", \"description\": \"…\", \"synonyms\": [\"…\"]}. type is any pool "
          "object type; id is that type's identity. On a \"namespace\" entry `kind` is "
          "accepted too; on a \"term\" entry `format` and `values` are.")}
    {:name "graph" :type :string :required? false
     :description
     (str "The graph a newly registered TERM is filed into — a bound term outside the "
          "graph's visibility fails the cut. Not needed when no entry is a term. "
          "Omit it to use the workspace's default graph.")}
    {:name "apply" :type :boolean :required? false :description "Write the plan."}]
   :handler
   (fn [ctx args]
     (let [db (db-of ctx)
           model (pool/as-dialect db)
           objects (vec (:objects args))
           type-of (fn [o] (keyword (name (:type o))))
           term? (fn [o] (= :term (type-of o)))
           ;; **The whole entry, minus its addressing keys.** Not a
           ;; hand-picked list: `plan-define-term` refuses a key a term does
           ;; not carry BY NAME (D2.26 — a caller who sent `:unit` believes
           ;; the unit was recorded), and filtering here would drop it
           ;; silently and take that refusal away.
           definition-of (fn [o] (dissoc o :type :id))
           entity-patch
           (fn [o] (cond-> {}
                     (contains? o :label) (assoc :label (:label o))
                     (contains? o :kind) (assoc :kind (:kind o))
                     (contains? o :synonyms) (assoc :synonyms (:synonyms o))
                     (contains? o :description) (assoc :description (:description o))))
           ;; The plain objects are planned TOGETHER, because
           ;; `plan-describe-objects` reads every revision in one pass; the
           ;; two special kinds have a planner each.
           plain (remove #(contains? #{:term :namespace} (type-of %)) objects)
           plans
           (cond-> (mapv (fn [o]
                           (case (type-of o)
                             :term (intent/plan-define-term
                                    db {:model model}
                                    {:term (:id o) :definition (definition-of o)})
                             :namespace (intent/plan-update-entity
                                         db {:model model}
                                         {:namespace (:id o) :patch (entity-patch o)})
                             nil))
                         (filter #(contains? #{:term :namespace} (type-of %)) objects))
             (seq plain)
             (conj (intent/plan-describe-objects
                    db (mapv (fn [o] {:type (type-of o) :id (:id o)
                                      :label (:label o) :description (:description o)
                                      :synonyms (:synonyms o)})
                             plain))))
           plan (assoc (merge-plans plans)
                       :described (vec (mapcat :described plans))
                       :created (vec (keep :created plans)))]
       (cond
         ;; A term is filed into a recipe when it is registered, so a term
         ;; entry without a graph is refused rather than written into a pool
         ;; no graph can see — the refusal names the missing argument.
         (and (some term? objects) (str/blank? (str (:graph args))))
         {:findings [(f/make :tools.intent/name-unresolved :error [:graph]
                             (str "a term entry registers the term into a graph's recipe, so "
                                  "name the graph")
                             :refs ["D3.19"])]}

         ;; Reading the recipe first is also how a graph this caller may not
         ;; edit is refused before any planning happens.
         (some term? objects)
         (with-graph ctx args (fn [_members] (run ctx args plan)))

         :else (run ctx args plan))))})

;; ---------------------------------------------------------------------------
;; assign_term / unassign_term (owner, 2026-09-03)
;; ---------------------------------------------------------------------------

(defn- resolve-attributes [ctx db names]
  (let [rs (mapv #(intent/resolve-name db :attribute %
                                       {:abbreviations (:abbreviations ctx) :path [:attributes]})
                 names)]
    (if-let [fs (seq (mapcat :findings rs))]
      {:findings (vec fs)}
      {:ids (mapv :id rs)})))

(def define-concept-tool
  "`assign_term`'s successor (need-grammar-v1 C1–C4; doc 106 §106.6 item 1).

   The tool is renamed, not rebuilt: what a person says is «these five fields
   all mean the customer number», which is a sentence about a CONCEPT and not
   about a glossary entry. The planner, the stripping of the local words and
   the recipe entry are `plan-assign-term`'s, unchanged."
  {:name "define_concept"
   :description
   (str "Say that several fields all mean the SAME THING — «these five fields are all the "
        "customer number». The concept is registered the first time you name it (send "
        "`definition` then; sending the very same words again for a concept that exists says "
        "nothing new and just binds the fields, while DIFFERENT words are refused, because a "
        "concept has one source of truth), each field is pointed at it, and the local words those fields "
        "carried are STRIPPED — the plan lists every one with its current value so you see "
        "what you are giving up. A local name that disagrees with the concept's is never "
        "overwritten silently: it lands in :fill for you to rule on. `values` states the codes "
        "the concept may hold, once, for every field bound to it. `contexts` says what a field "
        "means HERE when one thing holds two of them — «in an order, one is the buyer and one "
        "is the ship-to» — and is required in that case. Nothing is ever bound by name "
        "matching: only the fields you name. Returns a PLAN; apply=true writes it as one "
        "transaction.")
   :capability capability-note
   :params
   [{:name "graph" :type :string :required? false
     :description "Qualified graph name. Omit it to use the workspace's default graph."}
    ;; Not `:required?` at the schema level because `term` is still accepted
     ;; as its former spelling; exactly one of the two must arrive, and the
     ;; handler refuses by name when neither does.
    {:name "concept" :type :string :required? false
     :description "The concept's id, e.g. \"cis.party/customer_no\"."}
    {:name "term" :type :string :required? false
     :description "Deprecated spelling of `concept`."}
    {:name "attributes" :type :vector :items :string :required? true
     :description "The fields that mean it: names unique in the pool, or full ids."}
    {:name "definition" :type :map :required? false
     :coerce #(dialect/coerce-json-keys :term %)
     :description
     (str "The concept's words — {\"label\": …, \"description\": …, \"synonyms\": [...], "
          "\"format\": …, \"values\": …}. Required the first time you name it; afterwards "
          "the same words again are fine and change nothing, different ones are refused.")}
    {:name "values" :type :map :required? false
     :coerce #(dialect/coerce-json-keys :values %)
     :description
     (str "The codes this concept may hold: {\"D\": {\"label\": \"dev\"}, …}. Same as putting "
          "them in `definition`; refused for a concept that already exists.")}
    {:name "contexts" :type :map :required? false
     :description
     (str "What a field means here, per field: {\"to_acct_no\": \"buyer\", \"ship_acct_no\": "
          "\"ship-to\"}. Required when one thing binds this concept more than once.")}
    {:name "apply" :type :boolean :required? false :description "Write the plan."}]
   :handler
   (fn [ctx args]
     (let [db (db-of ctx)]
       (with-graph
         ctx args
         (fn [_members]
           (let [concept (or (:concept args) (:term args))
                 r (resolve-attributes ctx db (:attributes args))]
             (cond
               (str/blank? (str concept))
               {:findings [(f/make :tools.intent/name-unresolved :error [:concept]
                                   "say which concept these fields all mean")]}
               (seq (:findings r)) r
               :else
               (let [cs (mapv (fn [[k v]]
                                [(intent/resolve-name db :attribute (str (name k))
                                                      {:abbreviations (:abbreviations ctx)
                                                       :path [:contexts]})
                                 v])
                              (or (:contexts args) {}))]
                 (if-let [fs (seq (mapcat (comp :findings first) cs))]
                   {:findings (vec fs)}
                   (run ctx args
                        (intent/plan-assign-term
                         db {:model (pool/as-dialect db)}
                         {:term concept
                          :attributes (:ids r)
                          :contexts (into {} (for [[a v] cs] [(:id a) (str v)]))
                          :definition (cond-> (:definition args)
                                        (seq (:values args))
                                        (assoc :values (:values args)))})))))))))) })

(def unassign-term-tool
  {:name "unassign_term"
   :description
   (str "Take the term binding off the attributes you name, and COPY the term's words back "
        "onto each of them — an attribute that lost its term and kept nothing is a bare column "
        "with no description, which is worse than either state around it. The term itself is "
        "never deleted: other attributes may still bind it. The recipe is not changed for the "
        "same reason. Returns a PLAN; apply=true writes it.")
   :capability capability-note
   :params
   [{:name "graph" :type :string :required? false
     :description "Qualified graph name. Omit it to use the workspace's default graph."}
    {:name "attributes" :type :vector :items :string :required? true
     :description "The attributes to unbind: local names unique in the pool, or qualified ids."}
    {:name "apply" :type :boolean :required? false :description "Write the plan."}]
   :handler
   (fn [ctx args]
     (let [db (db-of ctx)]
       (with-graph
         ctx args
         (fn [_members]
           (let [r (resolve-attributes ctx db (:attributes args))]
             (if (seq (:findings r))
               r
               (run ctx args
                    (intent/plan-unassign-term
                     db {:model (pool/as-dialect db)}
                     {:attributes (:ids r)}))))))))})


;; ---------------------------------------------------------------------------
;; change_graph_members — one recipe-editing tool (8.4.16 裁定九)
;; ---------------------------------------------------------------------------

(defn- resolve-objects [ctx db names]
  (let [rs (mapv #(intent/resolve-any db % {:abbreviations (:abbreviations ctx)
                                            :path [:objects]})
                 names)]
    (if-let [fs (seq (mapcat :findings rs))]
      {:findings (vec fs)}
      {:ids (mapv :id rs)})))

(def change-graph-members-tool
  "`update_recipe`'s successor, and the merge of 裁定八's `add_to_graph` /
   `remove_from_graph` (8.4.16 裁定九).

   One edit of one list, said the way a person says it: these in, those out.
   Two tools meant two calls and two plans for what is one change to one
   recipe — and a caller who moved an object from one group's wording to
   another had to read two refusals to find that out."
  {:name "change_graph_members"
   :description
   (str "Edit which objects this graph's recipe NAMES: `add` puts objects the POOL ALREADY "
        "HOLDS into it, `remove` takes objects out of it. Nothing is created and **nothing "
        "is deleted** — a graph is a list over the pool (D3.19), the pool is the object's "
        "home, and usually other graphs name it too; this edits the list. To DELETE, use "
        "remove_objects, which says so in its name. To put a NEW object in a graph, use "
        "add_resolver / add_attribute: they file it as they create it. Name the objects the "
        "way you would say them; the recipe group each one belongs in is worked out here. "
        "Returns a PLAN — the :recipe-delta is the whole of it, because no pool action is "
        "involved — and apply=true writes it.")
   :capability capability-note
   :params
   [{:name "graph" :type :string :required? false
     :description "Qualified graph name. Omit it to use the workspace's default graph."}
    {:name "add" :type :vector :items :string :required? false
     :description
     (str "Objects to put IN the recipe, in the words you have: a local name unique in the "
          "pool, or a qualified id (\":cis.party/name\", a resolver id, a namespace name).")}
    {:name "remove" :type :vector :items :string :required? false
     :description "Objects to take OUT of the recipe. They stay in the pool."}
    {:name "apply" :type :boolean :required? false :description "Write the plan."}]
   :handler
   (fn [ctx args]
     (let [db (db-of ctx)]
       (with-graph
         ctx args
         (fn [_members]
           (if (and (empty? (:add args)) (empty? (:remove args)))
             {:findings [(f/make :tools.intent/blocked-plan :error [:add]
                                 "name something to add or to remove")]}
             (let [a (resolve-objects ctx db (:add args))
                   r (resolve-objects ctx db (:remove args))]
               (if (or (seq (:findings a)) (seq (:findings r)))
                 ;; Both lists at once — one round trip per mistake, not per
                 ;; list.
                 {:findings (into (vec (:findings a)) (:findings r))}
                 (run ctx args
                      (merge-plans
                       [(intent/plan-graph-membership :add (:ids a))
                        (intent/plan-graph-membership :remove (:ids r))])))))))))})

;; ---------------------------------------------------------------------------
;; The four edit tools (裁定八 §3)
;; ---------------------------------------------------------------------------

(def update-resolver-tool
  {:name "update_resolver"
   :description
   (str "Change what a resolver DOES: its statement, parts of its source block, its page "
        "window, its ordering, its declared eql-params, its input and output. The current "
        "entry is read here and your keys are laid over it — which is the difference that "
        "matters, because a resolver entry is REPLACED wholesale by the pool: an "
        "execute_action carrying only :description is a legal, finding-free write that "
        "silently drops the :query. The source block is merged, not replaced, so changing "
        "the query does not take the :batch block with it (D2.4). "
        "Labels and descriptions are not here — those are describe_objects. "
        "Returns a PLAN; apply=true writes it.")
   :capability capability-note
   :params
   [{:name "graph" :type :string :required? false
     :description "Qualified graph name. Omit it to use the workspace's default graph."}
    {:name "resolver" :type :string :required? true :description "The resolver id."}
    {:name "query" :type :string :required? false
     :description "The statement this resolver sends. Replaces the source block's :query."}
    {:name "source" :type :map :required? false
     :coerce #(dialect/coerce-json-keys :resolver-source %)
     :description "Other source-block keys to change (:batch, :data_path, :endpoint, …). Merged over the current block."}
    ;; Every attribute below is named the way a person names it — bare local
    ;; name or qualified id, colon or no colon — and resolved in here through
    ;; resolve_target's four verdicts (8.4.14 裁定六 principle 1). One literal
    ;; example per position, because the 2026-09-04 session that lost its
    ;; budget to `:order` had none to copy.
    {:name "input" :type :vector :items :string :required? false
     :description
     (str "The resolver's :input — the attributes it is looked up BY. Replaces it. "
          "Example: [\"ch.demo.posts/Id\"].")}
    {:name "output" :type :vector :required? false
     :description
     (str "The resolver's :output tree. Replaces it — a branch is a join attribute (D2.29). "
          "Example: [\"ch.demo.posts/Id\", {\"ch.demo.users/posts\": [\"ch.demo.posts/Title\"]}].")}
    {:name "limit" :type :map :required? false
     :description
     (str "The page window. Every list resolver must declare one (D2.8). "
          "Example: {\"default\": 50, \"max\": 500}.")}
    {:name "order" :type :map :required? false
     :description
     (str "Declarative ordering, which the caller may override — the reason not to write "
          "ORDER BY into the statement. Attribute names as usual, direction as \"asc\" or "
          "\"desc\". Example: "
          "{\"allowed\": [\"ch.demo.posts/CreationDate\"], "
          "\"default\": [\"ch.demo.posts/CreationDate\", \"desc\"]}.")}
    {:name "eql_params" :type :map :required? false
     :description
     (str "Declared filter parameters, keyed by the name a query uses. Reserved names are "
          "limit/offset/order_by/sort_dir (D2.20). Ops are eq/in/lt/lte/gt/gte/like. Example: "
          "{\"prod_code\": {\"attribute\": \"cis.master.part/prod_code\", \"op\": \"eq\"}}.")}
    {:name "cache" :type :map :required? false
     :description "The resolver's cache block. Example: {\"ttl-ms\": 60000}."}
    ;; A merge has no spelling for «drop this key», and the JSON face refuses
    ;; an explicit null, so a source key once written could never be taken
    ;; back (doc 106 §106.12 item 7.4).
    {:name "remove" :type :vector :items :string :required? false
     :description
     (str "Source-block keys to REMOVE rather than change — merging cannot say «drop this». "
          "Only optional keys may go: data_path, query_params, json_body, batch, "
          "field_mapping, params, limit. Example: [\"data_path\"].")}
    {:name "apply" :type :boolean :required? false :description "Write the plan."}]
   :handler
   (fn [ctx args]
     (let [db (db-of ctx)
           patch (cond-> {}
                   (contains? args :input) (assoc :input (:input args))
                   (contains? args :output) (assoc :output (:output args))
                   (contains? args :limit) (assoc :limit (:limit args))
                   (contains? args :order) (assoc :order (:order args))
                   (contains? args :eql_params) (assoc :eql-params (:eql_params args))
                   (contains? args :cache) (assoc :cache (:cache args))
                   (or (contains? args :query) (contains? args :source))
                   (assoc :source (cond-> (or (:source args) {})
                                    (contains? args :query) (assoc :query (:query args)))))]
       ;; ONE decoding pass over the nested positions before anything is
       ;; planned: a name inside :order or :eql_params is resolved by the same
       ;; four verdicts as a name at the top level, and :asc / :eq / :string
       ;; are accepted as plain strings (8.4.14 裁定六 principle 1).
       (let [d (intent/decode-resolver-patch db {:abbreviations (:abbreviations ctx)} patch)]
         (if (seq (:findings d))
           {:findings (vec (:findings d))}
           (with-graph
             ctx args
             (fn [_members]
               (run ctx args
                    (intent/plan-update-resolver db {:model (pool/as-dialect db)}
                                                 {:resolver (:resolver args)
                                                  :patch (:patch d)
                                                  :remove-keys (mapv #(keyword (name %))
                                                                     (or (:remove args) []))}))))))))})

(def update-attribute-tool
  {:name "update_attribute"
   :description
   (str "Change an attribute's PHYSICAL and governance facts: its type, format, closed value "
        "set, whether it is an identity key, whether it is deprecated and what replaces it. "
        "Its WORDS are not here — label, description and synonyms go through describe_objects, "
        "and when a term is bound they come from the term (D2.26). A merge patch: only the "
        "keys you send travel, and everything else survives. A join attribute has no :type "
        "(it carries :target) and changing it is refused rather than half-applied. "
        "Returns a PLAN; apply=true writes it.")
   :capability capability-note
   :params
   [{:name "graph" :type :string :required? false
     :description "Qualified graph name. Omit it to use the workspace's default graph."}
    {:name "attribute" :type :string :required? true
     :description "The attribute: a local name unique in the pool, or a qualified id."}
    {:name "type" :type :keyword :required? false
     :description "Dialect type."
     :enum [:string :integer :decimal :boolean :date :datetime :json]}
    {:name "format" :type :keyword :required? false :description "Declared format (D2.14)."}
    {:name "values" :type :map :required? false
     :coerce #(dialect/coerce-json-keys :values %)
     :description "The closed value set this attribute may hold."}
    {:name "identity" :type :boolean :required? false
     :description "Whether this attribute is an identity key of its entity."}
    {:name "deprecated" :type :boolean :required? false
     :description "Whether this attribute is retired."}
    {:name "replaced_by" :type :string :required? false
     :description
     (str "The attribute that supersedes it (D2.22). Named like every other attribute here — "
          "a local name unique in the pool, or a qualified id. "
          "Example: \"cis.master.part/prod_code\".")}
    {:name "apply" :type :boolean :required? false :description "Write the plan."}]
   :handler
   (fn [ctx args]
     (let [db (db-of ctx)
           r (intent/resolve-name db :attribute (:attribute args)
                                  {:abbreviations (:abbreviations ctx) :path [:attribute]})]
       (if (seq (:findings r))
         r
         ;; `:replaced-by` used to be keywordised right here, which accepted
         ;; only a fully qualified spelling — the one nested position of this
         ;; tool, and the one place it asked the caller to do the encoding.
         ;; It now goes through the same pass as every other reference.
         (let [patch (cond-> {}
                       (contains? args :type) (assoc :type (:type args))
                       (contains? args :format) (assoc :format (:format args))
                       (contains? args :values) (assoc :values (:values args))
                       (contains? args :identity) (assoc :identity? (:identity args))
                       (contains? args :deprecated) (assoc :deprecated? (:deprecated args))
                       (contains? args :replaced_by) (assoc :replaced-by (:replaced_by args)))
               d (intent/decode-attribute-patch db {:abbreviations (:abbreviations ctx)} patch)]
           (if (seq (:findings d))
             {:findings (vec (:findings d))}
             (with-graph
               ctx args
               (fn [_members]
                 (run ctx args
                      (intent/plan-update-attribute db {:model (pool/as-dialect db)}
                                                    {:attribute (:id r)
                                                     :patch (:patch d)})))))))))})

;; ---------------------------------------------------------------------------
;; declare_source (8.4.24 裁定十八)
;; ---------------------------------------------------------------------------

(def declare-source-tool
  "**The only write tool for a source on this face**, and its semantics are
   wholesale replacement (8.4.24 裁定十八).

   No `add_source` / `update_source` pair: creating and updating are one
   sentence — «it should now be this» — and `expected_revision` is which of
   the two the caller means. And no merge write, deliberately: api-v1's `:tls`
   and `:ssl_insecure?` are mutually exclusive, so `update_resolver`'s
   merge-don't-replace rule (D2.4, which exists to keep a `:batch` block from
   being dropped) would make switching a source to certificate authentication
   impossible. Replacing is what the pool already does to an entry.

   The three guarded steps — validate, prove the connection, write — are the
   HOST's `:declare-source`, injected the same way `:resolve-source` is: this
   module opens no connection and depends on no connector, and the GUI's
   «Connect a database» card must write through the very same path (裁定十六 ③
   read backwards)."
  {:name "declare_source"
   :description
   (str "Declare ONE source — how this model reaches a database or an HTTP api — as it SHOULD "
        "now be. There is no separate add and update: send the whole entry, and "
        "`expected_revision` says which you mean (omitted = it must not exist yet; given = it "
        "must be at exactly that revision). The entry REPLACES what is registered — a key you "
        "leave out is GONE, which is how a source moves from an api_key `auth` block to a "
        "`tls` one. The connection is proved before anything is written: the plan carries the "
        "health check's status and latency, and nothing is registered unless it answered. Send "
        "verify=false for a source whose credential or database is not there yet — it is then "
        "registered anyway and the reply says connected? false rather than pretending. "
        "Credentials are ${VAR} references only; a literal password is refused before any "
        "connection is opened. Returns a PLAN; send apply=true to write, and the same "
        "arguments are re-planned then (an action list is never accepted from the caller). "
        "This tool does not RENAME a source: the id is what every resolver's :source-ref "
        "quotes, so changing it is remove_objects plus a fresh declaration, with each of those "
        "resolvers edited too.")
   :capability capability-note
   :params
   [{:name "id" :type :string :required? true
     :description
     (str "The source id, as a resolver's :source-ref quotes it. With expected_revision this "
          "REPLACES that source's entry; without one, the id must still be free.")}
    {:name "entry" :type :map :required? true
     :coerce #(dialect/coerce-json-keys :source %)
     :description
     (str "The WHOLE source entry, :type included (\"sql\" or \"api\"). What gets written is "
          "this and nothing else — it is not merged with what is registered. Credential keys "
          "take a ${VAR} reference; its value is resolved on this machine for the health check "
          "alone and never travels back here.")}
    {:name "expected_revision" :type :integer :required? false
     :description
     (str "The revision this source is at now, as read_object reports it. Omitted, this "
          "declares a NEW source and a taken id is refused. Given and stale, nothing is "
          "written and the conflict is reported: somebody changed it while you were planning.")}
    {:name "sample" :type :map :required? false
     :description
     (str "For an api source whose :base_url names an attribute in its HOST — "
          "https://{nifi.instance/host}:9443/nifi-api — one real value to check ONE instance "
          "with: {\"nifi.instance/host\": \"nifi-01\"}. Such a source has no single upstream, "
          "so the health check refuses to answer for it as a whole; this says which one to "
          "ask. The value is used for that call and never stored.")}
    {:name "verify" :type :boolean :required? false
     :description
     (str "Default true: register only if the health check answered. False registers it "
          "regardless — a credential that will exist later, a database down this afternoon — "
          "and the reply still carries the health result and connected? false.")}
    {:name "apply" :type :boolean :required? false
     :description "Write the plan. Default false — you get the plan back and nothing is written."}]
   :handler
   (fn [ctx args]
     (if-let [declare! (:declare-source ctx)]
       (let [id (str/trim (str (:id args)))
             request {:id id
                      :entry (:entry args)
                      :expected-revision (:expected_revision args)
                      ;; Which instance to check, for a host-templated source
                      ;; (doc 106 §106.14 item 11). Spelled by the caller as
                      ;; attribute ids; the ids become keywords here, the way
                      ;; every other id a caller writes does.
                      :sample (not-empty
                               (into {} (map (fn [[k v]]
                                               ;; A keyword key keeps its
                                               ;; namespace: `name` would drop
                                               ;; it and bind `{host}` instead
                                               ;; of `{nifi.instance/host}`.
                                               [(if (keyword? k)
                                                  k
                                                  (keyword (str/replace (str k) #"^:" "")))
                                                v]))
                                     (:sample args)))
                      ;; The trusted half, exactly as `apply-plan!` takes it:
                      ;; built by the transport from what authentication
                      ;; established, reachable by no argument the client sent.
                      :provenance (:provenance ctx)
                      ;; `verify` and the card's `register-unverified?` are one
                      ;; switch under two names, and the default is the safe
                      ;; half.
                      :verify? (if (contains? args :verify)
                                 (boolean (:verify args))
                                 true)}]
         ;; The entry's SHAPE is the toolkit's to judge (a `:map` parameter
         ;; that arrived as a scalar is `:toolkit.args/invalid` before this
         ;; handler runs) and its CONTENT is the connector contract's. Neither
         ;; is re-judged here — this layer would only be inventing a third
         ;; vocabulary for a refusal that already has one.
         (cond
           (str/blank? id)
           {:findings [(f/make :tools.intent/name-unresolved :error [:id]
                               "a source id is required" :got (:id args))]}

           (not (:apply args)) (declare! (assoc request :apply? false))

           (not (write-key? ctx))
           ;; The plan travels WITH the refusal, as it does for every other
           ;; intent tool: a read-only reviewer is exactly who should read it
           ;; (8.4 裁定 ②). Planning costs one health check and no write.
           (let [plan (declare! (assoc request :apply? false))]
             (assoc plan :written? false
                    :findings (conj (vec (:findings plan))
                                    (f/make :tools.intent/blocked-plan :error [:apply]
                                            (str "this connection is read-only; the plan is "
                                                 "above and nothing was written")
                                            :expected "write" :refs ["8.4 裁定 ②"]))))

           ;; P7: apply re-plans from the SAME arguments — the host runs the
           ;; three steps again and writes at the end of them.
           :else (declare! (assoc request :apply? true))))
              {:findings [(f/make :tools.source/unavailable :error [:source]
                           "this host does not register sources")]}))})

;; `define_term` 与 `update_entity` 的**声明**在 8.4.16 裁定九合进
;; `describe_objects`：三条工具写的是同一类东西——一个东西叫什么、是什么意思——
;; 只是落在三种对象上，而按对象类型分工具，让 agent 按类型而不是按动作选名字。
;; 规划函数 `plan-define-term` / `plan-update-entity` 原样保留，由
;; `describe_objects` 按条目的 `type` 分派。

(def declarations
  "Fixed order, same reason as every other group: two `tools/list` calls are
   byte-identical so a client can diff them.

   Additions first, then their removals, then the two passes that speak about
   many objects at once — that is the order the work happens in, and
   `tools/list` order is what an agent reads first."
  [declare-source-tool
   add-thing-tool add-attribute-tool add-relation-tool add-link-tool add-metric-tool
   update-resolver-tool update-attribute-tool
   remove-objects-tool describe-objects-tool
   define-concept-tool unassign-term-tool
   change-graph-members-tool])
