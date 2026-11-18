(ns open.atlas.studio.saved-query-service
  "Writing a graph's DRAFT saved queries, for both entrances — the Builder MCP
   (`list_saved_queries` / `save_saved_query` / `delete_saved_query`) and,
   later, Query Studio (proposal studio-saved-queries §3.2).

   The guards are `graph-service`'s: reading needs the graph visible in the
   caller's workspace, writing needs it editable. The shape check and the
   binding are `server.mcp`'s — the very functions the data plane runs a saved
   query with — so a query this accepts is one the data plane can bind.

   ## Checked before it is kept

   An agent saves a query because it worked, or because it kept failing and
   was finally made to work. Either way the save is where a wrong one is
   cheapest to stop, so the host's `:check` runs on every save:

   - **static**: every attribute the query names is in the draft's catalog;
   - **a real run** against the draft, with the parameters' examples, unless
     the caller says `verify false` or a required parameter has no example.

   The run's refusal comes back WHOLE, so the agent fixes the query with the
   same finding `execute_eql` would have given it."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [open.atlas.server.mcp :as mcp]
            [open.atlas.studio.graph :as studio-graph]
            [open.atlas.studio.graph-service :as gsvc]
            [open.atlas.studio.pool :as pool]))

(defn- refuse! [code message data]
  (throw (ex-info message (assoc data :code code))))

(defn- reject! [message findings]
  (throw (ex-info message {:code :saved-query-rejected :error {:findings (vec findings)}})))

(defn- read-eql
  "The EQL as data. MCP clients send it as EDN text (as `execute_eql` takes its
   query); `read-string` from clojure.edn, so no reader eval."
  [eql]
  (if (string? eql)
    (try (edn/read-string eql)
         (catch Exception e
           (reject! "The saved query's EQL is not readable"
                    [{:code :saved-query/invalid :severity :error :path [:eql]
                      :message (str "the EQL is not readable EDN: " (ex-message e))}])))
    eql))

(defn- param-of
  "One parameter as a client sends it (JSON: string type, `required`) → the
   stored shape (keyword type, `:required?`)."
  [p]
  (if-not (map? p)
    p
    (let [p (into {} (map (fn [[k v]] [(keyword (name k)) v])) p)
          required (if (contains? p :required?) (:required? p) (:required p))]
      (cond-> (select-keys p [:name :description :example])
        (some? (:type p)) (assoc :type (keyword (str/replace-first (name (:type p)) #"^:" "")))
        (some? required) (assoc :required? required)
        ;; Unknown keys are kept so the shape check names them, not dropped.
        :always (merge (apply dissoc p [:name :description :example :type :required :required?]))))))

(defn normalize
  "A save request → the saved-query map that is checked and stored."
  [{:keys [name description params eql]}]
  (cond-> {:name name
           :description description
           :params (if (sequential? params) (mapv param-of params) (or params []))
           :eql (read-eql eql)}))

(defn- graph-for-read [conn caller graph]
  (gsvc/require-graph! (pool/db conn) caller graph))

(defn- graph-for-write [conn caller graph]
  (let [db (pool/db conn)]
    (->> (gsvc/require-graph! db caller graph) (gsvc/require-editable! db caller))))

(defn list-saved-queries
  "The draft set as an author reads it: EQL included."
  [{:keys [conn caller]} {:keys [graph]}]
  (let [definition (graph-for-read conn caller graph)
        qn (:graph/qualified-name definition)]
    {:graph qn
     :saved-queries (mapv #(mcp/describe-saved-query % true)
                          (studio-graph/saved-queries (pool/db conn) qn))}))

(defn save-saved-query!
  "Add a saved query to the draft, or replace the one with the same name.
   Published with the graph's next version; until then the data plane does
   not see it."
  [{:keys [conn caller check]} {:keys [graph verify] :as request}]
  (let [definition (graph-for-write conn caller graph)
        qn (:graph/qualified-name definition)
        sq (normalize request)
        shape (mcp/saved-query-findings sq)
        _ (when (seq shape) (reject! "The saved query is not well formed" shape))
        current (studio-graph/saved-queries (pool/db conn) qn)
        replaced? (boolean (some #(= (:name sq) (:name %)) current))
        next-set (if replaced?
                   (mapv #(if (= (:name sq) (:name %)) sq %) current)
                   (conj current sq))
        set-findings (mcp/saved-query-set-findings next-set)
        _ (when (seq set-findings) (reject! "The graph's saved queries would not be valid" set-findings))
        checked (when check (check {:graph qn :saved-query sq :verify? (not (false? verify))}))]
    (when (seq (:findings checked))
      (reject! "The saved query does not work against this graph's draft" (:findings checked)))
    (studio-graph/set-saved-queries! conn qn next-set)
    {:graph qn
     :saved-query (mcp/describe-saved-query sq true)
     :replaced replaced?
     :verification (or (:verification checked) {:ran false :reason "this host does not check saved queries"})
     :published false
     :next-step "Saved to the draft. publish_graph with apply=true publishes it with the graph's next version; until then the data plane's list_saved_queries does not show it."}))

(defn delete-saved-query!
  "Remove a saved query from the draft. Versions already published keep theirs."
  [{:keys [conn caller]} {:keys [graph name]}]
  (let [definition (graph-for-write conn caller graph)
        qn (:graph/qualified-name definition)
        current (studio-graph/saved-queries (pool/db conn) qn)]
    (when-not (some #(= name (:name %)) current)
      (refuse! :saved-query-unknown "No saved query by that name in this graph's draft"
               {:graph qn :name name :known (mapv :name current)}))
    (studio-graph/set-saved-queries! conn qn (vec (remove #(= name (:name %)) current)))
    {:graph qn :deleted name :published false}))
