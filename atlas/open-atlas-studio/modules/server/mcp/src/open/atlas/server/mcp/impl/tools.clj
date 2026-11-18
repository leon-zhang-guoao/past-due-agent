(ns open.atlas.server.mcp.impl.tools
  "The tool surface: definitions the agent reads, and the calls it makes.

   Migrated from `ent.dx.mcp.tools`, narrowed to the four that answer
   \"what is here\" and \"what does it say\".  The legacy registry also carried
   `analyze_result` / `compute_metric` / `list_mutations` / `execute_mutation`;
   those come in a later slice, and `execute_mutation` needs its own ruling —
   a write on the data plane is not a read with a different verb.

   ## One door, twice

   Every tool here resolves its graph the same way the `/eql` shell does:
   the bearer names it (`instance/answer` verifies and resolves — the caller
   has no graph selector to assert). Discovery additionally pins the version
   through `env/for-request`, which is the same pinning `answer` performs
   internally. Nothing here re-implements grant judgement (D1.13)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.semantic-protocol :as semantic-protocol]
            [open.atlas.runtime.env :as env]
            [open.atlas.runtime.graph :as rgraph]
            [open.atlas.runtime.instance :as instance]
            [open.atlas.runtime.token :as token]
            [open.atlas.server.mcp.impl.catalog :as catalog]
            [open.atlas.server.mcp.impl.duck :as duck]
            [open.atlas.server.mcp.impl.metrics :as metrics]
            [open.atlas.server.mcp.impl.rows :as rows]
            [open.atlas.server.mcp.impl.results :as results]
            [open.atlas.server.mcp.impl.saved-query :as saved-query]))

;; ---------------------------------------------------------------------------
;; Definitions (the text an agent plans against)
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Calls
;; ---------------------------------------------------------------------------

(defn- pinned-by-token
  "The compiled env for the version this credential authorises, plus its
   catalog. Findings pass straight through — a caller that cannot be resolved
   must hear why, not get an empty catalog that reads as \"nothing here\".

   Takes the whole tool ctx (8.4 P4): the registry hands every handler the same
   map, so the three values these tools need travel together rather than as
   three positional arguments each handler had to thread."
  [{:keys [parts credential now]}]
  (let [{:keys [token env]} parts
        verified (token/verify token credential {:now now})
        v (if (and (:authorization verified)
                   (not ((:graph-enabled? token) (get-in verified [:authorization :graph-id]))))
            {:findings [{:code :token.authz/graph-disabled :severity :error
                         :path [:graph-id] :message "this graph is disabled on this runtime instance"}]}
            verified)]
    (if (seq (:findings v))
      v
      (let [a (:authorization v)
            r (env/for-request env {:graph-id (:graph-id a)
                                    :version-spec (:version a)})]
        (if (seq (:findings r))
          r
          (assoc (catalog/of (:env r))
                 :version (:version r)
                 ;; `[graph-id version]` is the search index's cache key
                 ;; (D7.10: keyed by graph version, stale is discarded).
                 ;; It rides on the pinning that already happened rather
                 ;; than being derived a second way.
                 :graph-id (:graph-id a)
                 ;; The metric registry is model content, not catalog content —
                 ;; a 口径 is a definition, not a reachable attribute.
                 :model (:engine/model (:env r))))))))

(def token-runtime
  "**How these tools obtain an execution environment** — the data plane's
   answer: the bearer names the graph and pins the version (D5.7).

   Named and injectable because 8.4.18 裁定十一 mounts these same seven
   declarations on the Builder, where the environment is a graph's DRAFT — an
   unsealed cut — rather than a pinned artifact. Everything else about a call
   (the description an agent plans against, the rendering, the refusal shape)
   is identical, so the environment is the ONE thing that varies and it varies
   through this seam. Forking the handlers would give the platform two answers
   to \"what does execute_eql do\", which is D1.13's second set of semantics.

   - `:pinned` `(fn [ctx args] → {:catalog :param-decls :version :graph-id :model}
     | {:findings […]})`. Hosts without a data-plane credential also provide
     `:result-owner`, a stable authenticated caller/workspace identity. Pinning
     must revalidate current access; result handles are not bearer grants.
   - `:answer` `(fn [ctx args request] → engine result | {:findings […]})`

   `args` is the tool's own arguments, so a host that selects the environment
   by an argument (the Builder's `graph`) can read it; this one ignores it,
   because the data plane's caller has no graph selector to assert."
  {:pinned (fn [ctx _args] (pinned-by-token ctx))
   :answer (fn [{:keys [parts credential now source]} _args request]
             (instance/answer parts {:credential credential
                                     :request request :source source
                                     :now now :entry :data-mcp}))})

(defn- runtime-of
  "The environment seam for this call. Absent from the ctx = the data plane's,
   so the data plane's own wiring is untouched."
  [ctx]
  (or (:runtime ctx) token-runtime))

(defn- pinned [ctx args] ((:pinned (runtime-of ctx)) ctx args))
(defn- answer [ctx args request] ((:answer (runtime-of ctx)) ctx args request))

(defn- result-scope [ctx args]
  ;; Revalidate access on every read; a handle never substitutes for a grant.
  (let [p (pinned ctx args)]
    (if (seq (:findings p))
      {:error "result access was refused" :findings (:findings p)}
      (if-let [owner (or (:result-owner p)
                        (when (:credential ctx)
                          [(System/identityHashCode (:env (:parts ctx))) (:credential ctx)]))]
        {:owner (results/fingerprint [owner (:graph-id p) (:version p)]) :version (:version p)}
        {:error "this runtime does not provide a result owner"}))))

(defn- declared-types
  "attribute keyword → declared type keyword, from the PINNED catalog.

   §108.3: the graph's own declaration outranks value inference, and the
   catalog it comes from is the same one `list_attributes` renders — a second
   source of truth about an attribute's type is D1.13's second semantics.
   Findings are not an error here: whatever refused the pinning will refuse
   the fetch a moment later with its own reasons, and typing simply falls back
   to inference rather than inventing a refusal of its own."
  [pinned*]
  (when-not (seq (:findings pinned*))
    (into {}
          (keep (fn [[k v]] (when-let [t (:type v)] [k t])))
          (:attributes (:catalog pinned*)))))

(defn- declared-types-of
  "Typing must not invent an authorization call a host does not provide: a
   runtime with no `:pinned` seam (a test double, a host that only answers)
   simply has no declared types, and inference stands in."
  [ctx args]
  (when (:pinned (runtime-of ctx))
    (declared-types (pinned ctx args))))

(defn- large-answer [ctx args request]
  (binding [engine/*result-byte-budget* results/max-bytes]
    (answer ctx args request)))

(defn- read-eql
  "EDN text → an EQL request, or a refusal. Deliberately `read-string`, not
   `read`: no reader eval on a request path."
  [s]
  (try
    (let [v (edn/read-string s)]
      (if (or (vector? v) (map? v))
        {:request v}
        {:error "the query must be an EQL vector, or {:entity {…} :query [...]}"}))
    (catch Exception e
      {:error (str "the query is not readable EDN: " (.getMessage e))})))

(defn- analysis-status-collision? [data]
  (some (fn [x]
          (when (map? x)
            (some #(and (keyword? %)
                        (#{"_atlas_fetch_status" "_atlas_http_status"}
                          (str/lower-case (str/replace (name %) #"[^A-Za-z0-9_]" "_")))) (keys x))))
        (tree-seq coll? seq data)))

(defn- flatten-with-outcomes
  "EQL result → flat rows, with the per-row source-failure columns PR B/F
   established. Shared by the single-handle and multi-handle paths so the
   failure evidence has ONE shape: an agent that learned to read
   `_atlas_fetch_status` on one handle must find it unchanged on several."
  [data outcomes budget deadline]
  (let [marked (reduce (fn [tree {:keys [path http-status]}]
                         (let [mark #(assoc % :_atlas_fetch_status "source-error" :_atlas_http_status http-status)]
                           (if (seq path) (update-in tree path mark) (mark tree))))
                       data outcomes)
        flat (rows/bounded-flatten-result marked budget
                                          (when deadline #(duck/check-deadline! deadline :flatten)))]
    (if (and flat (seq outcomes))
      (mapv #(merge {:_atlas_fetch_status "returned" :_atlas_http_status nil} %) flat)
      flat)))

(defn- run-sql-over
  "EQL result → flat rows → one DuckDB statement. A SQL error comes back as a
   readable refusal rather than a stack trace: the agent's next move is to fix
   the statement, and it can only do that if it is told what DuckDB objected to.

   `deadline` is the call's END-TO-END deadline; the flatten, load, sql and
   encode stages each receive only what is left of it. `typing` carries
   `{:declared … :version …}` (doc 108 §108.5 item 3)."
  ([data sql] (run-sql-over data sql duck/input-default-rows))
  ([data sql budget] (run-sql-over data sql budget []))
  ([data sql budget outcomes] (run-sql-over data sql budget outcomes nil))
  ([data sql budget outcomes deadline] (run-sql-over data sql budget outcomes deadline {}))
  ([data sql budget outcomes deadline typing]
   (let [t0 (System/currentTimeMillis)
         flat (flatten-with-outcomes data outcomes budget deadline)
         flatten-ms (- (System/currentTimeMillis) t0)
         scope {:scope :returned-data :source-completeness (if (seq outcomes) :partial :unknown)
                :failed-inputs (count (distinct (map :path outcomes)))
                :input-row-limit budget :input-byte-limit duck/input-max-bytes
                :note "Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals."}]
     (cond
       (and (seq outcomes) (analysis-status-collision? data))
       {:error "Result attributes collide with reserved analysis status columns; no aggregate was computed."
        :analysis scope}

       (> (count flat) budget)
       {:error (str "Analysis exceeds " budget " flattened rows. Narrow the scope, request a larger input_row_limit within the server maximum, or use a dedicated source-side resolver; no partial aggregate was computed.")
        :analysis scope}

       (duck/input-too-large? flat)
       (assoc (duck/refusal :analysis.limit/input-bytes
                            "Analysis exceeds the server input byte budget; no partial aggregate was computed. Narrow the scope or select fewer fields."
                            {:max duck/input-max-bytes})
              :analysis scope)

       (nil? flat)
       {:error "that query returned a bare scalar — there are no rows to analyze"}

       (nil? sql)
       (assoc (duck/describe-rows flat (:declared typing) (select-keys typing [:infer-time?]))
              :analysis (assoc scope :materialized false
                               :stages {:flatten flatten-ms})
              :analysis/version (or (:version typing) duck/default-analysis-version))

       (empty? flat)
       ;; Zero rows is an ANSWER (an empty window), not a failure — but DuckDB
       ;; cannot build a table with no columns, so it is answered directly.
       {:columns [] :rows [] :row-count 0 :note "the query returned no rows"
        :analysis (assoc scope :materialized false :stages {:flatten flatten-ms})
        :analysis/version (or (:version typing) duck/default-analysis-version)}

       :else
       ;; §108.4 / §108.5 item 6: the per-call input byte cap above is not the same
       ;; guard as the PROCESS total. Four calls each half the per-call cap are
       ;; each legal and together are not, and `memory_limit` is a per-connection
       ;; setting that cannot see them. Reserved before the table is built and
       ;; released on every exit path.
       (let [reserved (duck/input-bytes flat)]
         (if-not (duck/reserve-input-bytes! reserved)
           (assoc (duck/refusal :analysis.limit/total-bytes
                                "Concurrent analyses already hold the server's total input byte budget; no partial aggregate was computed. Retry, or narrow the scope."
                                {:max duck/total-input-bytes :actual reserved})
                  :analysis scope)
           (try
             (let [mat (when-let [after (:after-flatten typing)] (after flat))
                   t1 (System/currentTimeMillis)
                   out (duck/query-rows flat sql deadline typing)
                   ;; Stage timings are EVIDENCE, like `:truncation`: they are
                   ;; how a caller sees that a materialized handle skipped
                   ;; flatten and load rather than having to trust a boolean.
                   stages {:flatten flatten-ms
                           :load-and-sql (- (System/currentTimeMillis) t1)}]
               (assoc out :row-count (count flat)
                      :analysis (merge scope {:materialized false :stages stages}
                                       (select-keys mat [:materialize-refused]))))
             (catch java.sql.SQLException e
               {:error (str "DuckDB refused the statement: " (.getMessage e))
                :columns-available (mapv second (duck/column-names flat))})
             (finally (duck/release-input-bytes! reserved)))))))))

;; ---------------------------------------------------------------------------
;; The analysis budget wrapper (doc 108 §108.5 items 5 and 6, ruled §108.6)
;; ---------------------------------------------------------------------------

(defn- fetch-within
  "Run a fetch with only what the deadline has left.

   The budget goes IN through `engine/*execution-budget-ms*`, which the engine
   folds into `:execution-ms` with `min` and turns into the absolute
   `:deadline-at` doc 107 §107.7 threads down `spi/*request-trace*` — so every
   connector attempt is narrowed to `min(:timeout_ms, remaining)` against THIS
   call's clock, and an attempt that cannot finish is never started. The graph's
   own `:execution-ms` still bounds it: the tighter of the two wins.

   The outer timed deref stays as belt and braces. It covers the stages the
   trace channel does not reach — the handle read, and any fetch seam a host
   supplies that is not the engine (the Builder mounts its own `:answer`) —
   and it is what turns an overrun into a stage-named refusal rather than a
   wait. `future-cancel` also interrupts, while the connector-side abort is
   what actually returns a blocked socket to the pool (see `lim/with-deadline`)."
  [deadline stage f]
  (duck/check-deadline! deadline stage)
  (let [fut (future (binding [engine/*execution-budget-ms* (duck/remaining-ms deadline)]
                      (f)))]
    (try
      (let [v (deref fut (duck/remaining-ms deadline) ::timeout)]
        (if (= ::timeout v)
          (do (future-cancel fut)
              (throw (ex-info "analysis deadline exhausted"
                              {:analysis/refusal (duck/deadline-refusal deadline stage)})))
          v))
      (catch java.util.concurrent.ExecutionException e
        (throw (or (.getCause e) e))))))

(defn- with-analysis-budget
  "ONE budget per analysis call, and everything that must be given back is
   given back on every exit path — the permit above all, because a permit lost
   to a refusal reduces the server's capacity permanently and silently.

   `f` receives the deadline."
  [f]
  (if-not (duck/acquire-permit!)
    (duck/refusal :analysis.limit/concurrency
                  (str "The server is already running " duck/max-concurrent
                       " analyses and none freed up within " duck/max-wait-ms
                       " ms; nothing was computed. Retry.")
                  {:max duck/max-concurrent :actual duck/max-wait-ms})
    (try
      (let [deadline (duck/start-deadline)
            response (try
                       (f deadline)
                       (catch clojure.lang.ExceptionInfo e
                         (or (:analysis/refusal (ex-data e)) (throw e))))
            size (duck/output-bytes response)]
        ;; §108.4: the response cap REFUSES. A response cut to fit would be
        ;; indistinguishable from a complete one, and the caller would quote it.
        (if (> size duck/max-output-bytes)
          (duck/refusal :analysis.limit/output-bytes
                        (str "The aggregate is larger than the server's response budget of "
                             duck/max-output-bytes
                             " bytes; nothing was returned. Aggregate further, or select fewer columns.")
                        {:max duck/max-output-bytes :actual size})
          response))
      (finally (duck/release-permit!)))))

;; ---------------------------------------------------------------------------
;; Handlers
;;
;; One fn per tool, named, instead of one `case` over a tool-name string
;; (8.4 P4). The `case` was the second dispatch this module had — the first
;; being the definitions vector — and "declared in one place, dispatched in
;; another" is the drift a single registry exists to prevent. Now the handler
;; sits IN the declaration and there is nothing to keep in step.
;; ---------------------------------------------------------------------------
(defn- h-list-entities [ctx args]
  (let [p (pinned ctx args)]
    (if (seq (:findings p))
      {:error "this token does not resolve a served graph" :findings (:findings p)}
      ;; The MODEL travels beside the catalog so the «what is a thing»
      ;; criterion is the SAME function the Builder's `inspect_graph :things`
      ;; calls (doc 106 §106.5) — the catalog carries no resolvers, and a
      ;; criterion written against the catalog alone would be the second
      ;; semantics §106.5 rules out.
      {:entities (catalog/entity-registry (:catalog p) (:model p))
       :version (:version p)})))

(defn- h-list-attributes [ctx args]
  (let [p (pinned ctx args)]
    (if (seq (:findings p))
      {:error "this token does not resolve a served graph" :findings (:findings p)}
      {:attributes (catalog/attributes-for-entities
                    (:catalog p) (:param-decls p)
                    (map str (or (:entities args) [])))})))

(defn- h-search-attributes [ctx args]
  (let [p (pinned ctx args)]
    (if (seq (:findings p))
      {:error "this token does not resolve a served graph" :findings (:findings p)}
      ;; The MODEL, not the catalog: the index is built from the dialect
      ;; model the compiled env carries, which is where `:listed?`, terms and
      ;; synonyms live (D7.10).
      (catalog/search-attributes [(:graph-id p) (:version p)]
                                 (:model p)
                                 (:version p)
                                 (:query args)
                                 (:limit args)))))

(declare execute-request)

(defn- h-execute-eql [ctx args]
  (let [{:keys [request error]} (read-eql (:query args))]
    (if error
      {:error error}
      (execute-request ctx args request))))

(defn- execute-request
  "One parsed EQL request through the data plane's door, rendered the way
   `execute_eql` renders it. `run_saved_query` comes through here too, so a
   saved query's answer is byte-identical to the same EQL sent by hand
   (data-plane-api 1.2, appendix A item 2)."
  [ctx args request]
  (do
      ;; **The same door `/eql` opens.** Not a parallel path: token
      ;; verification, version pinning and enforcement all happen inside
      ;; `answer`, and this shell adds none of them (D1.13).
      (let [r (large-answer ctx args request)]
        (cond
          (seq (:findings r))
          {:error "the query was refused" :findings (vec (:findings r))}

          (= :rejected (:engine/status r))
          {:error "the query was refused"
           :findings [(dissoc r :engine/status)]}

          :else
          ;; No `:filtered-attributes`: nothing is filtered out of a released
          ;; response now that access tiers are retired (D5.11 / D7.12).
          ;; `:not-found` rides along only when a by-key read matched no row —
          ;; an answer, not a refusal (0.2 release note defect 11). An agent
          ;; reading a bare `{}` cannot tell "no such row" from "I asked
          ;; wrong", and would retry the same query forever.
          (let [response (cond-> {:result (semantic-protocol/wire-value (:engine/data r)) :env/version (:env/version r)}
            (:engine/collections r) (assoc :collections (mapv (fn [[path info]] (assoc info :path path)) (:engine/collections r)))
            (:engine/alerts r) (assoc :alerts (:engine/alerts r))
            (:engine/partial? r) (assoc :partial true :issues (:engine/issues r) :source-completeness :partial)
            (:engine/row-outcomes r) (assoc :row-outcomes (:engine/row-outcomes r))
            (:engine/not-found r) (assoc :not-found (:engine/not-found r)))]
            (if (or (:retain_result args) (results/large? response))
              (let [scope (result-scope ctx args)]
                (cond
                  (:error scope) scope
                  (and (or (:env/version r) (:mcp/result-version r))
                       (not= (:version scope) (or (:env/version r) (:mcp/result-version r))))
                  {:error "Graph changed during execution; execute the query again."}
                  :else (results/retain! (:owner scope) response)))
              response))))))

(defn- materialization-key
  "What makes two analyses the SAME table. The row budget is in the key because
   a different `input_row_limit` flattens to a different table — reusing one for
   the other would answer a narrower question with a wider table's rows. Owner,
   graph and version are in it because a materialization is not a bearer grant:
   the same handle under a changed version is a different (and un-readable)
   thing."
  [handle scope version budget infer-time?]
  ;; `infer_time` is in the key because it changes the TABLE, not the query: a
  ;; column materialized as VARCHAR cannot answer a `date_trunc` the switched-on
  ;; call would ask of it.
  (results/fingerprint [handle (:owner scope) (:version scope) version budget
                        (boolean infer-time?)]))

(defn- run-sql-materialized
  "The fast path: the table already exists on disk for this key, so parse,
   flatten and load are all skipped and only the SQL is paid for."
  [m sql deadline typing]
  (let [t0 (System/currentTimeMillis)
        {:keys [row-count source]} (duck/read-materialized-meta (:meta-path m))
        version (or (:version typing) duck/default-analysis-version)
        scope {:scope :returned-data
               :source-completeness (or (:source-completeness source) :unknown)
               :failed-inputs (:failed-inputs source 0)
               :input-row-limit (:input-row-limit source)
               :input-byte-limit duck/input-max-bytes
               :materialized true
               :note "Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals."}]
    (if (nil? sql)
      (assoc (duck/describe-materialized (:meta-path m))
             :analysis (assoc scope :stages {:describe (- (System/currentTimeMillis) t0)})
             :analysis/version version)
      (try
        (assoc (duck/query-materialized (:path m) sql deadline version)
               :row-count row-count
               :analysis (assoc scope :stages {:sql (- (System/currentTimeMillis) t0)}))
        (catch java.sql.SQLException e
          {:error (str "DuckDB refused the statement: " (.getMessage e))})))))

(defn- source-evidence
  "What a reader needs in order NOT to mistake a union for a joint snapshot:
   when each input was captured, when it expires, and whether it was complete
   (§108.2 「不能因 inner join 消除了失败行就声称输入完整」)."
  [saved]
  {:captured-at (:captured-at saved)
   :expires-at (:expires-at saved)
   :source-completeness (or (:source-completeness saved) :unknown)
   :partial (boolean (:partial saved))
   :failed-inputs (count (distinct (map :path (:row-outcomes saved))))})

(defn- at-position
  "Re-throw a per-handle type refusal with the handle's POSITION attached. The
   refusal is raised where the column is typed, which cannot know which handle
   it is looking at; the position is what the caller needs to fix their call,
   and it is known only here."
  [position f]
  (try (f)
       (catch clojure.lang.ExceptionInfo e
         (if-let [r (:analysis/refusal (ex-data e))]
           (throw (ex-info (ex-message e)
                           {:analysis/refusal
                            (update r :refusal
                                    (fn [d] (update d :positions #(or % [position]))))}))
           (throw e)))))

(defn- handle-input
  "One authorized handle → `{:specs … :rows …}` to load, or `{:specs … :path …}`
   to attach when PR I already materialized it. Re-uses the materialization
   rather than rebuilding: the table exists, it is this caller's, and the key
   already encodes everything that would make it the wrong table."
  [ctx args deadline scope typing budget handle position]
  (let [version (or (:version typing) duck/default-analysis-version)
        key (materialization-key handle scope version budget (:infer-time? typing))]
    (if-let [m (results/materialized (:owner scope) handle key)]
      (let [{:keys [specs row-count source]} (duck/read-materialized-meta (:meta-path m))]
        {:path (:path m) :meta (:meta-path m) :specs specs :row-count row-count
         :materialized true :source (or source {})})
      (let [saved (fetch-within deadline :handle-read
                                #(results/fetch! (:owner scope) handle))]
        (if (:error saved)
          saved
          (let [flat (flatten-with-outcomes (:result saved) (:row-outcomes saved) budget deadline)]
            (if (nil? flat)
              {:error "one of these handles returned a bare scalar — there are no rows to analyze"}
              {:rows flat :specs (at-position position
                                              #(duck/column-specs flat (:declared typing)
                                                                  (select-keys typing [:infer-time?])))
               :row-count (count flat) :materialized false
               :source (source-evidence saved)})))))))

(defn- analyze-handles*
  "Several handles in ONE analysis (doc 108 §108.2 second paragraph, §108.5
   item 2). Initial version: one identity, one environment, one graph VERSION —
   cross-graph is the explicit next step in §108.6, not a gap here.

   Each handle is authorized on its own; one failure refuses the whole call.
   Half an answer over half the inputs is the silent degradation D2.9 forbids,
   and the caller could not tell which input was missing."
  [ctx args deadline typing budget sql]
  (let [scope (do (duck/check-deadline! deadline :authorization) (result-scope ctx args))]
    (if (:error scope)
      scope
      (let [handles (mapv str (:result_handles args))
            n (count handles)
            ;; Positions only. Naming the handle id would tell the caller
            ;; something about another owner's result, and the position is
            ;; what they need to fix their own call anyway.
            gate (first (keep-indexed
                         (fn [i h]
                           (case (results/probe (:owner scope) h)
                             :ok nil
                             :missing (duck/refusal :analysis.handles/unavailable
                                                    (str "The handle at position " (inc i) " of " n
                                                         " is unknown or expired; nothing was computed. "
                                                         "Execute that query again.")
                                                    {:position (inc i) :count n})
                             :scope (duck/refusal :analysis.handles/scope-mismatch
                                                  (str "The handle at position " (inc i) " of " n
                                                       " belongs to a different caller, graph or graph "
                                                       "version; nothing was computed. This version "
                                                       "combines handles from ONE graph version only.")
                                                  {:position (inc i) :count n})))
                         handles))]
        (or gate
            (let [t0 (System/currentTimeMillis)
                  inputs (vec (map-indexed
                               #(handle-input ctx args deadline scope typing budget %2 (inc %1))
                               handles))
                  failed (first (filter :error inputs))
                  flatten-ms (- (System/currentTimeMillis) t0)
                  total-rows (reduce + 0 (map #(or (:row-count %) 0) inputs))
                  loaded (remove :path inputs)
                  total-bytes (reduce + 0 (map #(duck/input-bytes (:rows %)) loaded))
                  scope* {:scope :returned-data
                          :handles n
                          :materialized (mapv #(boolean (:materialized %)) inputs)
                          :input-row-limit budget :input-byte-limit duck/input-max-bytes
                          :note (str "Analysis covers the returned rows of each handle only. The inputs "
                                     "were captured separately: there is NO joint snapshot, and an inner "
                                     "JOIN that drops source-error rows does not make the inputs complete. "
                                     "The union keeps duplicates; deduplicate only by a business key you "
                                     "declare.")}]
              (cond
                failed failed

                (> total-rows budget)
                (assoc (duck/refusal :analysis.limit/rows
                                     (str "These handles flatten to more than " budget
                                          " rows in total; no partial aggregate was computed. "
                                          "The budget is the SUM across handles, not per handle.")
                                     {:max budget :actual total-rows})
                       :analysis scope*)

                (> total-bytes duck/input-max-bytes)
                (assoc (duck/refusal :analysis.limit/input-bytes
                                     "These handles exceed the server input byte budget in total; no partial aggregate was computed."
                                     {:max duck/input-max-bytes})
                       :analysis scope*)

                (duck/cross-join-risk? sql (map #(or (:row-count %) 0) inputs))
                (assoc (duck/refusal :analysis.limit/join-rows
                                     (str "This statement joins the handles without an equality predicate, "
                                          "and the inputs are large enough that the intermediate result "
                                          "could exceed " duck/max-join-rows
                                          " rows; nothing was computed. Give the JOIN an ON key.")
                                     {:max duck/max-join-rows})
                       :analysis scope*)

                :else
                (if-not (duck/reserve-input-bytes! total-bytes)
                  (assoc (duck/refusal :analysis.limit/total-bytes
                                       "Concurrent analyses already hold the server's total input byte budget; no partial aggregate was computed."
                                       {:max duck/total-input-bytes :actual total-bytes})
                         :analysis scope*)
                  (try
                    (let [t1 (System/currentTimeMillis)
                          out (duck/query-handles inputs sql deadline typing)]
                      (assoc out
                             :row-count total-rows
                             :result_handles handles
                             :sources (mapv :source inputs)
                             :analysis (assoc scope*
                                              :stages (cond-> {:load-and-sql (- (System/currentTimeMillis) t1)}
                                                        (seq loaded) (assoc :flatten flatten-ms)))))
                    (catch java.sql.SQLException e
                      {:error (str "DuckDB refused the statement: " (.getMessage e))
                       :tables-available (into ["data"] (map #(str "h" (inc %)) (range n)))})
                    (finally (duck/release-input-bytes! total-bytes)))))))))))

(defn- analyze-result* [ctx args deadline]
  ;; The clock is already running when the arguments are read: authorization,
  ;; pinning and the handle read are stages of this call like any other.
  (duck/check-deadline! deadline :authorization)
  (let [{:keys [request error]} (when (:query args) (read-eql (:query args)))
        sql (when (some? (:sql args)) (str (:sql args)))
        budget (duck/input-budget (:input_row_limit args))
        version (or (:analysis_version args) duck/default-analysis-version)
        typing {:declared (declared-types-of ctx args) :version version
                :infer-time? (boolean (:infer_time args))}]
    (cond
      (not (contains? duck/analysis-versions version))
      {:error (str "analysis_version must be one of "
                   (str/join ", " (sort duck/analysis-versions))
                   "; 1 returns every cell as text, 2 returns JDBC-typed cells.")}

      (not= 1 (count (filter identity [(:query args) (:result_handle args)
                                       (seq (:result_handles args))])))
      {:error "give exactly one of query, result_handle, or result_handles"}

      (and (seq (:result_handles args)) (< (count (:result_handles args)) 2))
      {:error "result_handles needs at least two handles; use result_handle for one"}

      (nil? budget)
      {:error (str "input_row_limit must be a positive integer no greater than " duck/input-max-rows)}

      error {:error error}

      (and (some? sql) (str/blank? sql))
      {:error "give the SQL to run over table `data`"}

      (and sql (re-find duck/forbidden sql))
      {:error (str "this statement is refused: ATTACH / INSTALL / LOAD / COPY / "
                   "EXPORT / IMPORT_DATABASE cannot be used. The only data is "
                   "the table `data` built from your query's rows.")}

      (seq (:result_handles args))
      (analyze-handles* ctx args deadline typing budget sql)

      (:result_handle args)
      ;; Authorization and pinning are INSIDE the deadline: a slow token or
      ;; artifact read spends the call's time like any other stage.
      (let [scope (do (duck/check-deadline! deadline :authorization)
                      (result-scope ctx args))]
        (if (:error scope) scope
          (let [handle (:result_handle args)
                key (materialization-key handle scope version budget (:infer-time? typing))]
            ;; **Re-authorized every time.** `result-scope` above re-pins, and
            ;; `results/materialized` re-checks owner and TTL — a cache hit
            ;; skips the LOAD, never the permission check (§108.2).
            (if-let [m (results/materialized (:owner scope) handle key)]
              (assoc (run-sql-materialized m sql deadline typing) :result_handle handle)
              (let [saved (fetch-within deadline :handle-read
                                        #(results/fetch! (:owner scope) handle))]
                (if (:error saved) saved
                  (let [source-meta (dissoc saved :result)
                        after (fn [flat]
                                (let [r (results/materialize!
                                         (:owner scope) handle key (duck/remaining-ms deadline)
                                         (fn [db meta]
                                           (duck/materialize!
                                            db meta flat (:declared typing) deadline
                                            (assoc source-meta
                                                   :input-row-limit budget
                                                   :failed-inputs (count (distinct (map :path (:row-outcomes saved))))
                                                   :source-completeness (if (:partial saved) :partial :unknown))
                                            (select-keys typing [:infer-time?]))))]
                                  ;; A refused or failed materialization is NOT
                                  ;; a failed analysis — it falls back to the
                                  ;; in-memory path. But it says so: a caller
                                  ;; comparing timings across calls would
                                  ;; otherwise read the slow path as a mystery.
                                  (when (:error r) {:materialize-refused (:code r)})))]
                    (assoc (run-sql-over (:result saved) sql budget (:row-outcomes saved) deadline
                                         (assoc typing :after-flatten after))
                           :result_handle handle
                           :source source-meta))))))))

      :else
      (let [r (fetch-within deadline :fetch #(large-answer ctx args request))]
        (cond
          (seq (:findings r)) {:error "the query was refused" :findings (vec (:findings r))}
          (= :rejected (:engine/status r))
          {:error "the query was refused" :findings [(dissoc r :engine/status)]}
          :else (cond-> (run-sql-over (:engine/data r) sql budget (:engine/row-outcomes r) deadline typing)
                  (:engine/partial? r) (assoc :partial true :issues (:engine/issues r) :source-completeness :partial)))))))

(defn- h-analyze-result [ctx args]
  (with-analysis-budget #(analyze-result* ctx args %)))

(defn- compute-metric* [ctx args deadline]
  (let [p (do (duck/check-deadline! deadline :authorization) (pinned ctx args))]
    (cond
      (seq (:findings p))
      {:error "this token does not resolve a served graph" :findings (:findings p)}

      ;; No metric named → the registry. Listing IS the answer to "what can
      ;; you compute", and making the agent guess a name first is how it ends
      ;; up improvising one in analyze_result instead.
      (not (contains? duck/analysis-versions
                      (or (:analysis_version args) duck/default-analysis-version)))
      {:error (str "analysis_version must be one of "
                   (str/join ", " (sort duck/analysis-versions))
                   "; 1 returns every cell as text, 2 returns JDBC-typed cells.")}

      (str/blank? (str (:metric args)))
      {:metrics (metrics/registry (:model p))}

      :else
      (let [name* (str (:metric args))
            hit (metrics/find-metric (:model p) name*)]
        (if-not hit
          {:error (str "no declared metric matches " (pr-str name*))
           :metrics (metrics/registry (:model p))}
          (let [[id m] hit
                {:keys [query columns error]} (metrics/query-for (:catalog p) m)]
            (if error
              {:error error}
              (let [group-by* (mapv (fn [s] (let [s (str s)]
                                              (if (str/includes? s "/")
                                                (keyword (subs s 0 (str/index-of s "/"))
                                                         (subs s (inc (str/index-of s "/"))))
                                                (keyword s))))
                                    (or (:group_by args) []))
                    r (fetch-within deadline :fetch #(answer ctx args query))]
                (cond
                  (seq (:findings r))
                  {:error "the metric's rows could not be read" :findings (vec (:findings r))}

                  (= :rejected (:engine/status r))
                  {:error "the metric's rows could not be read"
                   :findings [(dissoc r :engine/status)]}

                  (:engine/partial? r)
                  {:error "The metric input has source failures; no governed aggregate was computed."
                   :partial true :issues (:engine/issues r)}

                  :else
                  (let [out (run-sql-over (:engine/data r)
                                          (metrics/sql-for m columns group-by*)
                                          duck/input-default-rows [] deadline
                                          {:declared (declared-types p)
                                           :version (or (:analysis_version args)
                                                        duck/default-analysis-version)
                                           :infer-time? (boolean (:infer_time args))})]
                    (if (:error out)
                      out
                      (assoc out :metric id
                             :expression (:text (:expression m))
                             ;; The 口径 travels WITH the number. A figure
                             ;; whose definition is one call away is a figure
                             ;; that gets quoted without it.
                             :definition (:description m)))))))))))))

(defn- h-compute-metric [ctx args]
  (with-analysis-budget #(compute-metric* ctx args %)))

(defn- h-list-mutations [ctx args]
  (let [p (pinned ctx args)]
    (if (seq (:findings p))
      {:error "this token does not resolve a served graph" :findings (:findings p)}
      {:mutations
       (vec (for [m (:mutations (:model p))]
              (cond-> {:mutation (:id m)
                       :result_type (:result_type m)
                       :description (or (:description m) "")
                       :params (vec (for [[k d] (:params m)]
                                      (cond-> {:name (str (namespace k) "/" (name k))}
                                        (:required? d) (assoc :required true))))}
                (:label m) (assoc :label (:label m))
                (seq (:returns m)) (assoc :returns (mapv pr-str (:returns m))))))})))

(def ^:private pre-write-codes
  "Engine codes raised only by `gate/check-mutation`, before any connector."
  #{:engine.request/unknown-mutation
    :engine.request/unknown-mutation-param
    :engine.request/missing-mutation-param})

(defn- pre-write-refusal? [r]
  (and (= :rejected (:engine/status r)) (contains? pre-write-codes (:code r))))

(defn- h-execute-mutation [ctx args]
  (let [{parts* :parts credential* :credential now :now} ctx
        id (str (:mutation args))
        {:keys [request error]} (read-eql (:params args))]
    (cond
      error {:error (str "the params are not readable EDN: " error)}

      (not (map? request))
      {:error "params must be an EDN map of fully-qualified attribute names"}

      (str/blank? id)
      {:error "name the mutation; call list_mutations for the ids"}

      :else
      ;; The SAME door again. Engine checks the call against the mutation's
      ;; own declaration and refuses before a connector is touched, so a
      ;; refusal here means nothing was written.
      ;;
      ;; **Not routed through `answer`'s seam** (8.4.18 裁定十一): this tool is
      ;; not among the seven mounted on the Builder — 裁定九 put writing to a
      ;; source system back behind the GUI, and a shared environment seam is
      ;; not a reason for it to return.
      (let [r (instance/answer parts* {:credential credential*
                                       :request [(list (symbol id) request)] :source (:source ctx)
                                       :now now :entry :data-mcp})]
        (cond
          (seq (:findings r))
          {:error "the write was refused — nothing changed" :findings (vec (:findings r))}

          ;; The mutation-call gate (D2.18 / D5.4 step ②) runs before any
          ;; connector, so its refusals are provably pre-write. Matched by
          ;; code, NOT by `:rejected`: a timeout is also `:rejected` and may
          ;; land mid-write, which must stay `:unknown`. Reporting a gate
          ;; refusal as unknown sent an agent hunting a receipt that cannot
          ;; exist (2026-10-03).
          (pre-write-refusal? r)
          {:error "the write was refused before reaching the source — nothing was written; fix the call and resend"
           :operation/status :rejected
           :details (select-keys r [:engine/status :code :message :path :expected :refs])}

          (not= :ok (:engine/status r))
          ;; data-plane-api-v1 §8 item 6: receipts are a graph convention, the platform has none to
          ;; "check". Point at the graph's own idempotent read instead of a mechanism that does not exist.
          {:error "The write did not return a confirmed success and may or may not have taken effect. Do not retry yet: read this graph's record for the idempotency key you sent (the graph's usage guide names the query) and retry only if it is absent."
           :operation/status :unknown
           :details (select-keys r [:engine/status :code :message :error/category :error/statement-status :engine/issues])}

          :else {:result (get (:engine/data r) (symbol id))
                 :env/version (:env/version r)})))))

(def declarations
  "The tool declarations — description, parameters, capability and handler in
   ONE place (8.4 P4), fed to `common/toolkit`'s registry.

   Before P4 this vector held only the descriptions and JSON schemas, and a
   `case` over the tool name held the handlers. Two lists of the same eight
   names is exactly the drift a single registry prevents: a tool could be
   announced and not dispatchable, or dispatchable and unannounced, and neither
   shows up until a client tries it.

   The descriptions are unchanged and remain **at length on purpose**.

   Migrated from `ent.dx.mcp.tools`, and **at length on purpose**. These are
   not labels: they are the only instruction an agent gets about query shape,
   and the first version of this migration compressed `execute_eql` from two
   thousand characters to five lines. An agent then wrote

       :query [order_no issue_date total_order]

   — bare names, because it had never been shown a qualified one. Every
   worked example below is load-bearing.

   Adapted where this platform differs, never trimmed for brevity:
   the legacy `:count_only` / `:aggregate` pushdown does not exist here (that
   work goes to `analyze_result`), and the `:rls_denied` / `:role_denied`
   section has no subject since a token grants one graph and that is the whole
   grant (D5.11/D5.13)."
  [{:name "list_entities"
    :description
    (str "List the entities in the graph this token grants, with descriptions "
         "and :identity_keys. Use this as the FIRST step to see what data is "
         "available; the identity keys are what go in execute_eql's :entity "
         "map. NEVER reason about paths between entities — the engine "
         "auto-navigates, and any attributes can be combined in one query.")
    :capability :read
    :params []
    :handler (fn [ctx args] (h-list-entities ctx args))}

   {:name "list_attributes"
    :description
    (str "List attributes for the named entities. Returns FULLY-QUALIFIED "
         "attribute names, types, descriptions, closed value sets (:values), "
         "synonyms and units. Call ONCE with ALL relevant entities. Copy the "
         "names EXACTLY as returned — `:cis.sales.mso/order_no`, never "
         "`order_no`. Rows of type \"join\" hold rows of another entity — nest "
         "into them with their declared parameters; never use this tool to verify a path "
         "between entities (the engine auto-navigates). A row may also carry what "
         "its resolver declares. Input/Output define graph dependencies and path planning; every Input is required. Parameters are per-call controls, may be optional, and never establish a path. Put Input values in :entity and Parameters on the EQL property/join. :params (name, type, required, description, "
         "allowed values), :priority (rank among several resolvers of the same "
         "attribute; higher wins), :batch (evaluated for a set of inputs at once), "
         ":targets with a union description (a join reaching SEVERAL entities — "
         "nest with the spelled branch form, one identity key per entity) and "
         ":recursion_depth (the join may be recursed into, up to this ceiling; "
         "a deeper request is clamped and reported).")
    :capability :read
    :params [{:name "entities" :type :vector :items :string :required? true
              :description "Entity names from list_entities."}]
    :handler (fn [ctx args] (h-list-attributes ctx args))}

   {:name "search_attributes"
    :description
    (str "Search this graph's semantics to LOCATE where a business concept "
         "lives ('credit limit', '交期', 'past due amount') instead of listing "
         "attributes entity by entity. Matches glossary terms, entities and "
         "attribute names/labels/descriptions/synonyms, in Chinese or English, "
         "Uses all-word matching first; only if there are no exact-name or all-word hits, "
         "falls back to typo/partial-word matching. TOLERATES TYPOS and partial words ('custmer', '客户编'). "
         "Each hit says :kind — \"term\" is a glossary term and carries the "
         "attributes bound to it (one row, however many bindings), \"entity\" "
         "is a place to look, \"attribute\" is a field you can name in "
         "execute_eql. :match \"exact-name\" means the name matched exactly; "
         "the rest are ordered but NOT scored — never treat position as "
         "confidence. A query too broad to answer is REFUSED with the limit "
         "and the actual count, never silently cut: narrow it and call again.")
    :capability :read
    :params [{:name "query" :type :string :required? true
              :description "Business term(s), e.g. \"credit limit\" or \"客户 信用额度\""}
             {:name "limit" :type :integer :required? false
              :description "Max hits before the search is refused (default 200)."}]
    :handler (fn [ctx args] (h-search-attributes ctx args))}

   {:name "execute_eql"
    :description
    (str
     "Execute one EQL query against the graph. Returns exactly the requested attributes.

THE ONE RULE: never discover, verify, or reason about paths between entities — the engine auto-navigates. List every attribute you want in ONE query, whatever entity it belongs to.

## Format
`{:entity <identity-map> :query <eql-vector>}` — an EDN map STRING. `:entity` scopes the query, `{}` for global/collection queries.

**Every attribute name is a FULLY-QUALIFIED keyword** — `:cis.sales.mso/order_no`, never `order_no` and never `\"order_no\"`. Copy them verbatim from list_attributes; a bare name is refused as an unknown attribute.

Scalar attributes go FLAT in `:query` even across entities — the engine navigates for you. Join syntax `{:key [...]}` is ONLY for one-to-many child collections (list_attributes shows them as type \"join\").

```
{:entity {:cis.purchase.vpo/order_no \"32701794\"}
 :query [:cis.purchase.vpo/total_cost
         :cis.master.vendor/vend_name
         {:cis.purchase.vpo/order_detail [:cis.purchase.vpo_order_detail/line_no
                                          :cis.purchase.vpo_order_detail/sku_no]}]}

{:entity {} :query [{:cis.sales.mso/all_msos [:cis.sales.mso/order_no
                                              :cis.sales.mso/total_order]}]}
```

Note the second form: a global collection is a JOIN key, so it is wrapped — `[{:ns/all_x [...]}]`, not `[:ns/all_x]`.

## Parameters (optional)
Wrap the target key in a call: `({:cis.sales.mso/all_msos [...]} {:limit 50 :order_by \"issue_date\" :sort_dir \"desc\"})` on a collection, `{(:cis.purchase.vpo/order_detail {:limit 5}) [...]}` on a join. Common: :limit :offset :order_by :sort_dir; filter parameters vary by resolver and list_attributes declares them.

## Union and recursive joins
A join whose list_attributes row has :targets reaches several entities; nest it with one branch per entity, keyed by that entity's identity attribute: `{:t.ticket/subject {:t.device/id [:t.device/name] :t.person/id [:t.person/email]}}`. Each record answers under exactly one branch. A join with :recursion_depth may be recursed into: `{:t.group/children 3}` (an integer depth) or `{:t.group/children ...}`; the graph's declared ceiling is applied and a clamp is reported as :recursion/depth-applied. Recursing into a join without :recursion_depth is refused.

## Large results
Large responses are saved completely in a temporary server file and return result_handle instead of inline result. Pass that handle to analyze_result with SQL. retain_result=true also retains small results. Handles expire; they do not prove source completeness. No new Source or business database is needed. Heavy catalog reads can use a published Graph :graph/query-budget {:execution-ms 120000}; that deadline is configured by the Graph author, not guessed as an EQL parameter. This is a bounded synchronous query: the MCP client's transport timeout must also allow it. Keep ident inputs scalar: use separate {[:ns/id value] [...]} joins in one EQL vector, not a vector/set as the id value.

## Counting and aggregating
For full-scope business statistics, prefer a dedicated source-side resolver or a matching declared metric with verified coverage. analyze_result only processes returned rows; it does not bypass collection limits or prove completeness. Its flattened input budget defaults to the server maximum; consult its tool description for the current value, the call deadline and the input_row_limit parameter (which only lowers the budget). Never present a page aggregate as a full-source total. Nested joins repeat parent values and sibling collections multiply rows; choose the intended grain before aggregating.

## Self-correction
A refusal is structured: it names the code, the path and what was expected. Read it and change the query — an unknown attribute means the name is not in this graph's catalog (check spelling and qualification with list_attributes), not that the data is missing. Never answer \"no data\" without reading the refusal, and never retry the same shape unchanged.")
    :capability :read
    :params [{:name "query" :type :string :required? true
              :description (str "EDN map with :entity and :query. Example: "
                                "{:entity {:cis.sales.mso/order_no 143100011} "
                                ":query [:cis.sales.mso/total_order]}")}
             {:name "retain_result" :type :boolean :required? false
              :description "Return a temporary result_handle even for small results; large results are retained automatically."}]
    :handler (fn [ctx args] (h-execute-eql ctx args))}

   {:name "analyze_result"
    :description
    (str "Analyze only the bounded rows an EQL query returns, not the full source. "
         "Flattened input budget: " duck/input-default-rows
         " rows by default, which IS the server maximum (" duck/input-max-rows
         ") unless an operator tightened it; input_row_limit only lowers it. "
         "Input byte cap: " duck/input-max-bytes
         ". Whole call deadline: " duck/deadline-ms
         " ms; response byte cap: " duck/max-output-bytes
         ". Excess is refused, not silently truncated — refusals carry :refusal {:code …} "
         "(:analysis.limit/deadline names the stage that ran out, "
         ":analysis.limit/concurrency, :analysis.limit/total-bytes, "
         ":analysis.limit/input-bytes, :analysis.limit/output-bytes). "
         "The ROW/CELL display caps are different: they label the answer with "
         ":truncation {:rows? :cells? :rows-shown :rows-total} so an aggregate read off part "
         "of the SQL output can be told from one read off all of it. "
         "This does not increase EQL source limits. "
         "Report coverage as unknown unless independently established. Nested joins "
         "can repeat parent amounts. For full business totals prefer a dedicated "
         "source-side resolver. Run ONE DuckDB SQL — for counts, "
         "sums, medians, percentiles, pivots, time buckets or window "
         "functions. Omit SQL to discover table, row-count, columns (name/from/type/type-source, plus nested=true for columns whose cells are printed EDN structures) and analysis.failed-inputs without row values. Give exactly one of result_handle (reuse execute_eql data), result_handles (two or more handles at once) "
         "or query (fetch fresh EQL data). Handles expire and are bound to the caller and graph version.\n\n"
         "Partial results preserve source failures. When row outcomes exist, the temporary data table includes "
         "_atlas_fetch_status ('returned' or 'source-error') and _atlas_http_status. "
         "Exclude source-error rows from detail distributions and report them separately; "
         "'returned' alone does not prove a detail exists. Inspect analysis.failed-inputs and source.partial. "
         "The rows are loaded into one in-memory table named `data` whose "
         "columns are the attributes' LOCAL names: "
         ":cis.sales.mso/total_order becomes total_order. Write the whole "
         "statement:\n\n"
         "  SELECT to_acct_no, sum(total_order) AS total\n"
         "  FROM data GROUP BY to_acct_no ORDER BY total DESC\n\n"
         "The data never leaves the server; only your aggregate comes back. "
         "If a DECLARED metric already answers the question, use "
         "compute_metric instead — it is the governed definition and this is "
         "not.\n\n"
         "Column types come from the graph's DECLARED attribute types where it has them "
         "(reported as type-source declared) and otherwise from the values (inferred); "
         "discovery and execution use the same schema. A decimal column keeps its full "
         "precision and scale; an integer beyond BIGINT, a decimal wider than 38 digits, "
         "or one column holding both numbers and text is REFUSED "
         "(:analysis.type/out-of-range, :analysis.type/precision, :analysis.type/mixed) "
         "naming the column — never rounded and never silently turned into text. "
         "A date/timestamp-declared attribute is carried as text unless infer_time=true "
         "(type-source declared-temporal); see that parameter. In analysis_version 2 a moment "
         "comes back as an ISO-8601 string KEEPING its offset (JSON has no datetime) and "
         "column-types states each result column's SQL type.\n\n"
         "The first SQL over a result_handle materialises its table once; later SQL over "
         "the same handle, row budget and analysis_version reuses it read-only and reports "
         "analysis.materialized=true with analysis.stages showing which stages were skipped. "
         "The cache is re-authorized on every read, dies with the handle (same TTL, no renewal "
         "on a hit), and does NOT survive a server restart — it is an internal compute cache, "
         "never a Source and never a business snapshot. If it cannot be built or would exceed "
         "the server's result storage budget, the analysis still answers from memory and says so "
         "(analysis.materialized=false with analysis.materialize-refused).")
    :capability :read
    :params [{:name "query" :type :string :required? false
              :description "EQL as EDN text; omit when using result_handle."}
             {:name "result_handle" :type :string :required? false
              :description "Opaque handle returned by execute_eql; reuses the complete saved result without querying the Source."}
             {:name "result_handles" :type :vector :items :string :required? false
              :description (str "Two or more handles analyzed together. Each becomes a table h1, h2, … "
                                "in order, and `data` is their UNION ALL by column name (missing columns "
                                "NULL) with an extra _atlas_handle column carrying the 1-based position. "
                                "All handles must be yours, from the SAME graph and graph version; one "
                                "that is expired or out of scope refuses the whole call by position. "
                                "Row and byte budgets are the SUM across handles. The union KEEPS "
                                "duplicates — deduplicate with COUNT(DISTINCT <business key>), never "
                                "implicitly. The inputs were captured at different moments: :sources "
                                "carries each one's captured-at and completeness, there is no joint "
                                "snapshot, and an inner JOIN that drops source-error rows does not make "
                                "the inputs complete. Give every JOIN an ON key: a predicate-less join "
                                "over large inputs is refused up front.")}
             {:name "sql" :type :string :required? false
              :description "One DuckDB statement over table `data`; omit to discover its columns, types and row count."}
             {:name "analysis_version" :type :integer :required? false
              :description (str "Result cell protocol. 1 (default) returns every cell as text. "
                                "2 returns JDBC types: integers as integers, DOUBLE as numbers, "
                                "BOOLEAN as booleans, SQL NULL as null, and DECIMAL as an EXACT "
                                "string (JSON numbers are parsed as doubles by most clients and "
                                "would silently round a high-precision amount). Strings are "
                                "clipped at the cell cap in both versions, with :truncation saying so.")}
             {:name "infer_time" :type :boolean :required? false
              :description (str "Default false. When false, date/timestamp columns are text "
                                "(type-source declared-temporal) and nothing is inferred. When "
                                "true, an attribute the graph declares as instant/timestamp loads "
                                "as TIMESTAMP WITH TIME ZONE if EVERY value carries an offset or Z "
                                "(otherwise the column is refused, :analysis.type/time-zone-missing), "
                                "a declared date loads as DATE, and an UNdeclared text column is "
                                "promoted only when every value matches one fixed rule: full ISO "
                                "with offset/Z, or YYYY-MM-DD. An ISO datetime with NO zone is left "
                                "as text (time-status zone-missing), and DD/MM/YYYY-style or bare "
                                "digits are never read as times. No value is ever assumed to be UTC "
                                "or the server's zone.")}
             {:name "input_row_limit" :type :integer :required? false
              :description (str "Flattened input row budget. Default and server maximum are both "
                                duck/input-default-rows
                                ", so this parameter only LOWERS it; a larger value is refused. "
                                "Does not change source paging.")}]
    :handler (fn [ctx args] (h-analyze-result ctx args))}

   {:name "compute_metric"
    :description
    (str "Compute a DECLARED metric — a governed definition (口径) that lives "
         "in the model, not in your prompt. Call with NO arguments to list "
         "what is declared. Two people asking the same question get the same "
         "number because the expression is registered, so PREFER this over "
         "analyze_result whenever a metric matches: improvising the formula is "
         "how two 'monthly sales' figures come to disagree. Optionally group "
         "by declared dimensions (pass them fully qualified, as listed). The 口径's "
         "number keeps its declared precision: a decimal is never rounded to fit a "
         "double, and analysis_version 2 returns it as an exact string.")
    :capability :read
    :params [{:name "metric" :type :string :required? false
              :description "Metric id or synonym. Omit to list."}
             {:name "group_by" :type :vector :items :string :required? false
              :description "Declared dimensions, fully qualified."}
             {:name "analysis_version" :type :integer :required? false
              :description (str "Result cell protocol, as for analyze_result: 1 (default) "
                                "all text, 2 JDBC-typed with DECIMAL as an exact string.")}
             {:name "infer_time" :type :boolean :required? false
              :description (str "Default false, as for analyze_result: load declared "
                                "date/timestamp attributes as real time types when every value "
                                "carries a zone, so a monthly grain can be computed by date_trunc "
                                "rather than by string prefixes.")}]
    :handler (fn [ctx args] (h-compute-metric ctx args))}

   {:name "list_mutations"
    :description
    (str "List the writes this graph declares — id, parameters (with which are "
         "required), and what each returns. Call this before execute_mutation: "
         "parameter names are exact and fully qualified, and a misspelled one "
         "is refused rather than ignored. A graph with no mutations is "
         "read-only, and that is the whole permission story — there is no role "
         "to acquire.")
    :capability :read
    :params []
    :handler (fn [ctx args] (h-list-mutations ctx args))}

   {:name "execute_mutation"
    :description
    (str "Run one declared write. Pass `mutation` (an id from list_mutations) "
         "and `params` as an EDN map with FULLY-QUALIFIED attribute names:\n\n"
         "  {:cis.purchase.vpo/order_no \"32701794\"\n"
         "   :cis.purchase.vpo/expected_date \"2026-12-15\"}\n\n"
         "A mutation runs ALONE — you cannot read in the same call; run it, "
         "then query for what you want to see. Every parameter must be "
         "declared: an undeclared or a missing required one is refused BEFORE "
         "anything is written, so a refusal here means nothing changed.")
    :capability :write
    :params [{:name "mutation" :type :string :required? true
              :description "Mutation id from list_mutations."}
             {:name "params" :type :string :required? true
              :description "EDN map of arguments, fully qualified."}]
    :handler (fn [ctx args] (h-execute-mutation ctx args))}])

;; ---------------------------------------------------------------------------
;; Saved queries (data-plane-api 1.2, proposal studio-saved-queries §3)
;; ---------------------------------------------------------------------------
;;
;; A host that keeps saved queries puts `:saved-queries` in the ctx:
;; `(fn [graph-uuid version] → [saved-query …] | nil)`, the set PINNED to that
;; version. The graph and version come from the bearer, exactly as for every
;; other tool here; the caller names a query, never a graph. A host without it
;; does not mount these two tools at all (see `saved-query-declarations`).

(defn- pinned-saved-queries
  "→ `{:queries […] :version n}` for the version this credential pins, or a
   refusal. A version published before saved queries existed has none: `[]`."
  [ctx args]
  (let [p (pinned ctx args)]
    (if (seq (:findings p))
      {:error "this token does not resolve a served graph" :findings (:findings p)}
      (let [lookup (:saved-queries ctx)
            uuid (when lookup (rgraph/graph-uuid (:graph (:parts ctx)) (:graph-id p) (:version p)))]
        {:version (:version p)
         :queries (vec (or (when (and lookup uuid) (lookup uuid (:version p))) []))}))))

(defn- h-list-saved-queries [ctx args]
  (let [{:keys [queries version] :as r} (pinned-saved-queries ctx args)
        eql? (boolean (some #{"eql"} (map str (:include args))))]
    (if (:error r)
      r
      {:saved-queries (mapv #(saved-query/describe % eql?) queries)
       :env/version version})))

(defn saved-query-request
  "Name and parameters → `{:request <EQL> :version n}` for the version this
   ctx's credential pins, or `{:findings […]}`. Shared by `run_saved_query`
   and the `/eql` shell's `:saved-query` body, so both refuse alike."
  [ctx args name params]
  (let [{:keys [queries version] :as r} (pinned-saved-queries ctx args)]
    (cond
      (:error r) {:findings (:findings r)}
      :else
      (if-let [sq (some #(when (= name (:name %)) %) queries)]
        (assoc (saved-query/bind sq params) :version version)
        {:findings [{:code :saved-query/unknown :severity :error :path [:saved-query]
                     :message (str "no saved query named " (pr-str name) " on the version this token reads")
                     :expected (mapv :name queries)}]}))))

(defn- h-run-saved-query [ctx args]
  (let [n (str (:name args))
        params (:params args)]
    (cond
      (str/blank? n) {:error "name the saved query; call list_saved_queries for the names"}
      (and (some? params) (not (map? params))) {:error "params is a JSON object of parameter values"}
      :else
      (let [{:keys [request findings]} (saved-query-request ctx args n params)]
        (if findings
          {:error "the saved query was refused — nothing ran" :findings (vec findings)}
          ;; The source names the saved query, so a host's request recorder
          ;; can show «run_saved_query order_status {id 7}» rather than only
          ;; the bound EQL.
          (execute-request (assoc-in ctx [:source :saved-query] {:name n :params params})
                           args request))))))

(def saved-query-declarations
  "The two saved-query tools. Separate from `declarations` because only a host
   that keeps saved queries mounts them: announcing a tool that can only ever
   answer «none» is the «present and always failing» shape the registry
   exists to avoid (studio/tools README 裁定 ②)."
  [{:name "list_saved_queries"
    :description
    (str "List the saved queries of the graph this token reads: named, described EQL queries "
         "that someone who knows the model wrote and tested, each with typed parameters (JSON "
         "Schema). CALL THIS FIRST when a question might already be answered by one — running a "
         "saved query is more reliable than writing EQL yourself, because its shape is fixed and "
         "checked when the graph is published. Pick by description; run it with run_saved_query. "
         "Pass include=[\"eql\"] to see the EQL itself (for debugging or as a starting point for "
         "your own query).")
    :capability :read
    :params [{:name "include" :type :vector :items :string :required? false
              :description "[\"eql\"] to include each query's EQL text."}]
    :handler (fn [ctx args] (h-list-saved-queries ctx args))}
   {:name "run_saved_query"
    :description
    (str "Run one saved query by name. params is a JSON object matching that query's parameter "
         "schema from list_saved_queries; values are type-checked on the server and bound into the "
         "query as values (never spliced as text). An optional parameter you leave out drops its "
         "filter. The answer has exactly execute_eql's shape — result, collections, alerts, "
         "not-found, result_handle for large results — and the same limits apply. A refusal names "
         "the parameter and what was expected: saved-query/unknown (no such name on this version), "
         "param-missing, param-type, param-unknown. Nothing ran when it was refused.")
    :capability :read
    :params [{:name "name" :type :string :required? true
              :description "The saved query's name, as list_saved_queries reports it."}
             {:name "params" :type :map :required? false
              :description "Parameter values by name, e.g. {\"as_of\": \"2026-10-01\"}."}
             {:name "retain_result" :type :boolean :required? false
              :description "Return a temporary result_handle even for small results."}]
    :handler (fn [ctx args] (h-run-saved-query ctx args))}])
