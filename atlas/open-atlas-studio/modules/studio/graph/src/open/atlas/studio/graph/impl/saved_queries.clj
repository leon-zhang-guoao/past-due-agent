(ns open.atlas.studio.graph.impl.saved-queries
  "Where saved queries are kept (proposal studio-saved-queries §3).

   Storage only. What makes a saved query well formed, and what binding a
   parameter does, is `server.mcp`'s — the host checks before it writes here,
   the same split `set-default-query!` has with its own shape check."
  (:require [datahike.api :as d]
            [open.atlas.studio.graph.impl.schema :as schema]))

(defn draft
  "The graph's draft set, or `[]`. An unknown graph is `[]` as well: the
   caller has already decided the graph exists and is visible."
  [db qn]
  (or (some-> (d/q '[:find ?v . :in $ ?qn
                     :where [?g :graph.graph/qualified-name ?qn]
                            [?g :graph.graph/saved-queries ?v]]
                   db qn)
              schema/decode-edn)
      []))

(defn set-draft!
  "Replace the draft set. An empty set RETRACTS the attribute, so «no saved
   queries» has one representation — the one every older graph already has."
  [conn qn queries]
  (let [current (d/q '[:find ?v . :in $ ?qn
                       :where [?g :graph.graph/qualified-name ?qn]
                              [?g :graph.graph/saved-queries ?v]]
                     (d/db conn) qn)]
    (cond
      (seq queries)
      (d/transact conn [{:db/id [:graph.graph/qualified-name qn]
                         :graph.graph/saved-queries (schema/encode-edn (vec queries))}])
      current
      (d/transact conn [[:db/retract [:graph.graph/qualified-name qn] :graph.graph/saved-queries current]]))
    (vec queries)))

(defn- pin-key [graph-uuid version] (str graph-uuid "@" version))

(defn pinned
  "The set pinned to one published version, or nil when that version has no
   record (published before saved queries existed)."
  [db graph-uuid version]
  (some-> (d/q '[:find ?v . :in $ ?k
                 :where [?e :graph.pinned-queries/key ?k]
                        [?e :graph.pinned-queries/queries ?v]]
               db (pin-key graph-uuid version))
          schema/decode-edn))

(defn pin!
  "Record the set a version was published with. A version is immutable, so
   an existing record is left as it is: re-pinning is refused, not merged."
  [conn graph-uuid version queries]
  (let [k (pin-key graph-uuid version)]
    (when-not (d/q '[:find ?e . :in $ ?k :where [?e :graph.pinned-queries/key ?k]] (d/db conn) k)
      (d/transact conn [{:graph.pinned-queries/key k
                         :graph.pinned-queries/queries (schema/encode-edn (vec queries))}]))
    (vec queries)))

(defn unpin!
  "Forget the set pinned to one version — only when that version itself is
   reclaimed (R3.5); a version that still exists keeps its set."
  [conn graph-uuid version]
  (let [k (pin-key graph-uuid version)]
    (when (d/q '[:find ?e . :in $ ?k :where [?e :graph.pinned-queries/key ?k]] (d/db conn) k)
      (d/transact conn [[:db/retractEntity [:graph.pinned-queries/key k]]]))
    nil))

;; ---------------------------------------------------------------------------
;; Pinned requests (proposal studio-saved-queries §4.1b)
;; ---------------------------------------------------------------------------

(def max-pinned-per-workspace
  "Past this a new pin is refused rather than pushing an old one out: a pin is
   somebody's decision to keep a thing, and evicting it silently would undo it."
  200)

(defn- pinned-rows [db where-attr value]
  (d/q '[:find ?id ?ws ?g ?e ?at ?by ?exp
         :in $ ?attr ?v
         :where [?p ?attr ?v]
                [?p :graph.pinned-request/id ?id]
                [?p :graph.pinned-request/workspace ?ws]
                [?p :graph.pinned-request/graph-id ?g]
                [?p :graph.pinned-request/entry ?e]
                [?p :graph.pinned-request/pinned-at ?at]
                [?p :graph.pinned-request/pinned-by ?by]
                [?p :graph.pinned-request/expires-at ?exp]]
       db where-attr value))

(defn- row->pinned [[id ws g e at by exp]]
  {:id id :workspace ws :graph-id g :entry (schema/decode-edn e)
   :pinned-at at :pinned-by by :expires-at exp})

(defn- live? [now {:keys [expires-at]}] (pos? (compare expires-at now)))

(defn pinned-requests
  "The live pins of one graph (by UUID), newest first. `now` is an ISO
   instant; an expired pin is simply not returned."
  [db graph-id now]
  (->> (pinned-rows db :graph.pinned-request/graph-id graph-id)
       (map row->pinned)
       (filter (partial live? now))
       (sort-by :pinned-at #(compare %2 %1))
       vec))

(defn pin-request!
  "Keep one recorded request. Idempotent per id; refused past the workspace cap.
   → `{:pinned …}` or `{:findings […]}`."
  [conn {:keys [id workspace graph-id entry pinned-at pinned-by expires-at]}]
  (let [db (d/db conn)
        existing (first (pinned-rows db :graph.pinned-request/id id))
        live (filter (partial live? pinned-at)
                     (map row->pinned (pinned-rows db :graph.pinned-request/workspace workspace)))]
    (cond
      existing {:pinned (row->pinned existing)}
      (>= (count live) max-pinned-per-workspace)
      {:findings [{:code :recent-query/pin-limit :severity :error :path [:workspace]
                   :message "this workspace already keeps the most pinned requests it may; unpin some first"
                   :expected max-pinned-per-workspace}]}
      :else
      (do (d/transact conn [{:graph.pinned-request/id id
                             :graph.pinned-request/workspace workspace
                             :graph.pinned-request/graph-id graph-id
                             :graph.pinned-request/entry (schema/encode-edn entry)
                             :graph.pinned-request/pinned-at pinned-at
                             :graph.pinned-request/pinned-by pinned-by
                             :graph.pinned-request/expires-at expires-at}])
          {:pinned {:id id :workspace workspace :graph-id graph-id :entry entry
                    :pinned-at pinned-at :pinned-by pinned-by :expires-at expires-at}}))))

(defn unpin-request!
  "Forget one pin. Unknown ids are not an error: the outcome is the same."
  [conn id]
  (when (d/q '[:find ?p . :in $ ?id :where [?p :graph.pinned-request/id ?id]] (d/db conn) id)
    (d/transact conn [[:db/retractEntity [:graph.pinned-request/id id]]]))
  {:unpinned id})

(defn purge-expired-pins!
  "Retract every pin past its expiry. Called opportunistically by the host."
  [conn now]
  (let [ids (d/q '[:find [?id ...] :in $ ?now
                   :where [?p :graph.pinned-request/id ?id]
                          [?p :graph.pinned-request/expires-at ?exp]
                          [(compare ?exp ?now) ?c]
                          [(<= ?c 0)]]
                 (d/db conn) now)]
    (when (seq ids)
      (d/transact conn (mapv (fn [id] [:db/retractEntity [:graph.pinned-request/id id]]) ids)))
    (count ids)))
