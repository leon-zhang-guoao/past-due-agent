(ns open.atlas.studio.recent-queries
  "The data plane's recent requests, for debugging (proposal
   studio-saved-queries §4, owner rulings 2026-10-10).

   ## Three layers

   | layer  | what                           | size              | where     |
   |--------|--------------------------------|-------------------|-----------|
   | all    | every request                  | per graph, 50     | memory    |
   | failed | refused ones only              | per graph, 50     | memory    |
   | pinned | what a person or a rule kept   | per workspace 200 | the pool, 7 days |

   Failures have their own ring so a burst of successful traffic cannot push
   out the one request somebody needs to look at. Memory only for the first
   two: a restart forgets them, and nothing here is an audit (the ledger is).

   ## What an entry holds

   The EQL (or the saved query and its parameters), who asked — token id and,
   when Workbench vouched for one, the user — the outcome, the refusal, the
   latency. **Never result data.** An EQL text over 8 KB is cut and marked:
   this is a debugging view, not a contract surface.

   ## Who sees it

   Everyone who can see the graph in its workspace (owner ruling 2026-10-10,
   §6 item 5) — the same `require-graph!` every other graph read uses."
  (:require [clojure.string :as str]
            [open.atlas.studio.graph :as studio-graph]
            [open.atlas.studio.graph-service :as gsvc]
            [open.atlas.studio.pool :as pool]))

(def ^:private default-size 50)
(def ^:private max-eql-chars 8192)
(def ^:private pin-days 7)
(def ^:private max-rule-hours 24)

(defn ring-size
  "`OPEN_ATLAS_STUDIO_RECENT_QUERIES`: entries kept per graph and per layer;
   0 turns recording off."
  [env]
  (or (some-> (get env "OPEN_ATLAS_STUDIO_RECENT_QUERIES") str/trim not-empty parse-long)
      default-size))

;; {graph-key {:all [entry …] :failed [entry …]}} — newest last.
(defonce ^:private buffers (atom {}))
;; [{:id :graph-keys #{…} :graph-id :workspace :match {…} :expires-at :created-by}]
(defonce ^:private rules (atom []))

(defn reset-state!
  "For tests."
  []
  (reset! buffers {})
  (reset! rules []))

(defn- push [v entry n] (vec (take-last n (conj (or v []) entry))))

(defn- eql-text [request]
  (let [t (pr-str request)]
    (if (> (count t) max-eql-chars)
      {:eql (subs t 0 max-eql-chars) :truncated true}
      {:eql t})))

(defn- plus-hours [iso hours] (str (.plusSeconds (java.time.Instant/parse iso) (* 3600 (long hours)))))
(defn- plus-days [iso days] (plus-hours iso (* 24 days)))

(defn- entry-of [{:keys [at entry tool outcome reason findings token-id graph graph-version
                         latency-ms request on-behalf-of saved-query]}]
  (merge {:id (str (random-uuid))
          :at at
          :entry (name entry)
          :outcome (name outcome)
          :graph graph
          :token-id token-id
          :latency-ms latency-ms}
         (eql-text request)
         (cond-> {}
           tool (assoc :tool tool)
           graph-version (assoc :graph-version graph-version)
           reason (assoc :reason (subs (str reason) 1))
           (seq findings) (assoc :findings (mapv #(select-keys % [:code :path :message :expected])
                                                 (take 5 findings)))
           on-behalf-of (assoc :on-behalf-of (:subject on-behalf-of))
           saved-query (assoc :saved-query saved-query))))

(defn- rule-matches? [{:keys [graph-keys match expires-at]} e]
  (and (contains? graph-keys (:graph e))
       (pos? (compare expires-at (:at e)))
       (or (not (:failures-only? match)) (not= "ok" (:outcome e)))
       (or (nil? (:token-id match)) (= (:token-id match) (:token-id e)))
       (or (nil? (:subject match)) (= (:subject match) (:on-behalf-of e)))
       (or (nil? (:saved-query match)) (= (:saved-query match) (get-in e [:saved-query :name])))))

(defn- pin-entry! [conn {:keys [workspace graph-id]} e by now]
  (studio-graph/pin-request! conn {:id (:id e) :workspace workspace :graph-id graph-id
                                   :entry e :pinned-at now :pinned-by by
                                   :expires-at (plus-days now pin-days)}))

(defn recorder
  "The `:on-query` callback for `runtime/instance`. Records, applies capture
   rules, returns the entry id. `conn-fn` returns the pool connection (read at
   call time: the host's state is set after the runtime starts)."
  [conn-fn size]
  (fn [event]
    (when (pos? size)
      (let [e (entry-of event)
            k (:graph e)]
        (swap! buffers update k
               (fn [b] (cond-> (update b :all push e size)
                         (not= "ok" (:outcome e)) (update :failed push e size))))
        (doseq [r @rules :when (rule-matches? r e)]
          (pin-entry! (conn-fn) r e (str "rule:" (:id r)) (:at e)))
        (:id e)))))

;; ---------------------------------------------------------------------------
;; The service, bound to one caller
;; ---------------------------------------------------------------------------

(defn- graph-scope
  "Visibility (workspace level) and the keys the runtime may have filed this
   graph's requests under: its qualified name and its UUID."
  [conn caller graph]
  (let [d (gsvc/require-graph! (pool/db conn) caller graph)]
    {:qn (:graph/qualified-name d)
     :graph-id (:graph/id d)
     :workspace (:graph/workspace d)
     :graph-keys (set (remove nil? [(:graph/qualified-name d) (:graph/id d)]))}))

(defn- newest-first [es] (vec (reverse es)))

(defn- find-entry [graph-keys id]
  (some (fn [k] (some #(when (= id (:id %)) %)
                      (concat (get-in @buffers [k :all]) (get-in @buffers [k :failed]))))
        graph-keys))

(defn recent
  "All three layers for one graph, newest first, plus the live capture rules."
  [{:keys [conn caller now]} {:keys [graph]}]
  (let [{:keys [qn graph-id graph-keys]} (graph-scope conn caller graph)
        at (now)
        bs (map #(get @buffers %) graph-keys)]
    {:graph qn
     :all (newest-first (sort-by :at (mapcat :all bs)))
     :failed (newest-first (sort-by :at (mapcat :failed bs)))
     :pinned (mapv #(assoc (:entry %) :pinned-at (:pinned-at %) :pinned-by (:pinned-by %)
                           :expires-at (:expires-at %))
                   (studio-graph/pinned-requests (pool/db conn) graph-id at))
     :rules (vec (for [r @rules :when (and (= graph-id (:graph-id r)) (pos? (compare (:expires-at r) at)))]
                   (select-keys r [:id :match :expires-at :created-by])))}))

(defn pin!
  "Pin one recorded request so it outlives the ring and a restart (7 days).
   A request already pinned is returned as it is."
  [{:keys [conn caller now]} {:keys [graph id]}]
  (let [{:keys [graph-keys graph-id] :as scope} (graph-scope conn caller graph)
        at (now)
        existing (some #(when (= id (:id %)) %) (studio-graph/pinned-requests (pool/db conn) graph-id at))
        e (or (:entry existing) (find-entry graph-keys id))]
    (cond
      existing {:pinned id}
      (nil? e) (throw (ex-info "That request is no longer in the recent list — it was pushed out or the Studio restarted"
                               {:code :recent-query-expired :graph graph :id id}))
      :else
      (let [r (pin-entry! conn scope e (pr-str (:principal caller)) at)]
        (if (:findings r)
          (throw (ex-info "Pinning was refused" {:code :recent-query-pin-refused :error r}))
          {:pinned id :expires-at (get-in r [:pinned :expires-at])})))))

(defn unpin! [{:keys [conn caller]} {:keys [graph id]}]
  (graph-scope conn caller graph)
  (studio-graph/unpin-request! conn id))

(defn entry
  "One request by id — from the rings or the pins — for a debug link."
  [{:keys [conn caller now]} {:keys [graph id]}]
  (let [{:keys [graph-keys graph-id]} (graph-scope conn caller graph)]
    (or (find-entry graph-keys id)
        (some #(when (= id (:id %)) (:entry %)) (studio-graph/pinned-requests (pool/db conn) graph-id (now)))
        (throw (ex-info "That request has expired: it left the recent list, or the Studio restarted, and nobody pinned it"
                        {:code :recent-query-expired :graph graph :id id})))))

(defn add-rule!
  "Pin every matching request for the next `hours` (at most 24). Match on
   token id, the Workbench user, a saved query's name, and/or failures only."
  [{:keys [conn caller now]} {:keys [graph hours token-id subject saved-query failures-only?]}]
  (let [{:keys [graph-id] :as scope} (graph-scope conn caller graph)
        h (or hours max-rule-hours)]
    (when-not (and (integer? h) (< 0 h (inc max-rule-hours)))
      (throw (ex-info "A capture rule lasts between 1 and 24 hours" {:code :recent-query-rule-invalid :graph graph})))
    (let [r (merge (select-keys scope [:graph-id :workspace :graph-keys])
                   {:id (str (random-uuid))
                    :match (cond-> {}
                             (not (str/blank? token-id)) (assoc :token-id token-id)
                             (not (str/blank? subject)) (assoc :subject subject)
                             (not (str/blank? saved-query)) (assoc :saved-query saved-query)
                             failures-only? (assoc :failures-only? true))
                    :expires-at (plus-hours (now) h)
                    :created-by (pr-str (:principal caller))})]
      (swap! rules (fn [rs] (conj (filterv #(pos? (compare (:expires-at %) (now))) rs) r)))
      (assoc (select-keys r [:id :match :expires-at]) :graph-id graph-id))))

(defn remove-rule! [{:keys [conn caller]} {:keys [graph id]}]
  (let [{:keys [graph-id]} (graph-scope conn caller graph)]
    (swap! rules (fn [rs] (filterv #(not (and (= id (:id %)) (= graph-id (:graph-id %)))) rs)))
    {:removed id}))
