(ns open.atlas.studio.pool.impl.semantic
  "Versioned semantic documents, separate from legacy pool objects. Host owns
   authentication; workspace and provenance are supplied from trusted context."
  (:require [clojure.edn :as edn]
            [datahike.api :as d]
            [open.atlas.studio.pool.impl.audit :as audit]
            [open.atlas.common.semantic-contract :as contract]
            [open.atlas.common.dialect :as dialect]))

(def schema
  (vec (for [[k t unique?] [[:semantic.draft/key :db.type/string true]
                            [:semantic.draft/revision :db.type/long false]
                            [:semantic.draft/document :db.type/string false]
                            [:semantic.history/key :db.type/string true]
                            [:semantic.history/document :db.type/string false]
                            [:semantic.history/provenance :db.type/string false]]]
         (cond-> {:db/ident k :db/valueType t :db/cardinality :db.cardinality/one}
           unique? (assoc :db/unique :db.unique/identity)))))

(defn read-draft [db workspace graph]
  (when-let [e (first (d/q '[:find [(pull ?e [*]) ...] :in $ ?k :where [?e :semantic.draft/key ?k]] db (pr-str [workspace graph])))]
    (when (:semantic.draft/document e)
      (let [revision (:semantic.draft/revision e)
            history (d/pull db [:semantic.history/provenance]
                            [:semantic.history/key (pr-str [workspace graph revision])])
            saved-at (some-> (:semantic.history/provenance history) edn/read-string :at)]
        (cond-> {:document (edn/read-string (:semantic.draft/document e))
                 :revision revision :findings []}
          saved-at (assoc :saved-at saved-at))))))

(defn- reject [code path]
  {:findings [{:code code :severity :error :path path}]})

(defn save! [conn workspace graph expected document checkers provenance]
  (cond
    (seq (audit/check provenance)) {:findings (audit/check provenance)}
    (not (and (string? workspace) (seq workspace) (string? graph) (seq graph) (nat-int? expected)))
    (reject :semantic.draft/invalid-request [])
    (not= workspace (get-in document [:workspace :workspace/id]))
    (reject :semantic.draft/workspace-mismatch [:workspace])
    (not= graph (get-in document [:graph :graph/id]))
    (reject :semantic.draft/graph-mismatch [:graph])
    :else
    (locking conn
      (let [before (read-draft (d/db conn) workspace graph)
            revision (or (:revision before) 0)]
        (if (not= expected revision)
          (assoc (reject :semantic.draft/revision-conflict [:expected_revision]) :revision revision)
          (let [next-revision (inc revision)
                document (assoc-in document [:publication :source/revision] next-revision)
                encoded (contract/write-document document checkers)]
            (if (seq (:findings encoded))
              (select-keys encoded [:findings])
              (do
                (d/transact conn
                  [{:semantic.draft/key (pr-str [workspace graph])
                    :semantic.draft/revision next-revision
                    :semantic.draft/document (:text encoded)}
                   {:semantic.history/key (pr-str [workspace graph next-revision])
                    :semantic.history/document (:text encoded)
                    :semantic.history/provenance (pr-str provenance)}])
                {:document document :revision next-revision :findings []}))))))))

(defn snapshot [db]
  {:drafts (mapv #(select-keys % [:semantic.draft/key :semantic.draft/revision :semantic.draft/document])
                (sort-by :semantic.draft/key
                  (d/q '[:find [(pull ?e [*]) ...] :where [?e :semantic.draft/key]] db)))
   :history (mapv #(select-keys % [:semantic.history/key :semantic.history/document :semantic.history/provenance])
                 (sort-by :semantic.history/key
                   (d/q '[:find [(pull ?e [*]) ...] :where [?e :semantic.history/key]] db)))})

(defn drafts [db]
  "Decoded current drafts for repository-wide ownership conflict checks."
  (mapv (fn [row]
          (let [[workspace graph] (edn/read-string (:semantic.draft/key row))]
            {:workspace workspace :graph graph
             :revision (:semantic.draft/revision row)
             :document (edn/read-string (:semantic.draft/document row))}))
        (:drafts (snapshot db))))

(defn restore-plan [db value checkers]
  (try
    (let [{:keys [drafts history]} value
          valid-doc? (fn [text workspace graph revision]
                       (let [document (:document (contract/read-document text checkers))]
                         (and document (= workspace (get-in document [:workspace :workspace/id]))
                              (= graph (get-in document [:graph :graph/id]))
                              (= revision (get-in document [:publication :source/revision])))))
          draft? (fn [entry]
                   (let [[workspace graph :as k] (edn/read-string (:semantic.draft/key entry))]
                     (and (= #{:semantic.draft/key :semantic.draft/revision :semantic.draft/document} (set (keys entry)))
                          (vector? k) (= 2 (count k)) (= (pr-str k) (:semantic.draft/key entry)) (pos-int? (:semantic.draft/revision entry))
                          (valid-doc? (:semantic.draft/document entry) workspace graph (:semantic.draft/revision entry)))))
          history? (fn [entry]
                     (let [[workspace graph revision :as k] (edn/read-string (:semantic.history/key entry))]
                       (and (= #{:semantic.history/key :semantic.history/document :semantic.history/provenance} (set (keys entry)))
                            (vector? k) (= 3 (count k)) (= (pr-str k) (:semantic.history/key entry)) (pos-int? revision)
                            (empty? (audit/check (edn/read-string (:semantic.history/provenance entry))))
                            (valid-doc? (:semantic.history/document entry)
                                        (get-in (edn/read-string (:semantic.history/document entry)) [:workspace :workspace/id]) graph revision))))]
      (if (and (= #{:drafts :history} (set (keys value)))
               (vector? drafts) (vector? history) (every? draft? drafts) (every? history? history)
               (= (count drafts) (count (set (map :semantic.draft/key drafts))))
               (= (count history) (count (set (map :semantic.history/key history))))
               (empty? (:drafts (snapshot db))) (empty? (:history (snapshot db)))
               (= (set (map :semantic.draft/key drafts))
                  (set (map #(pr-str (subvec (edn/read-string (:semantic.history/key %)) 0 2)) history)))
               (every? (fn [draft]
                         (let [k (edn/read-string (:semantic.draft/key draft))
                               rows (filter #(= k (subvec (edn/read-string (:semantic.history/key %)) 0 2)) history)
                               latest (last (sort-by #(last (edn/read-string (:semantic.history/key %))) rows))]
                           (and (= (count rows) (:semantic.draft/revision draft))
                                (= (set (range 1 (inc (:semantic.draft/revision draft))))
                                   (set (map #(last (edn/read-string (:semantic.history/key %))) rows)))
                                (= (:semantic.draft/document draft) (:semantic.history/document latest))))) drafts))
        {:tx (into drafts history) :findings []}
        (reject :semantic.draft/invalid-backup [:pool/semantic])))
    (catch Exception _ (reject :semantic.draft/invalid-backup [:pool/semantic]))))


(defn rename-workspace-plan
  "Move authoring addresses in the surrounding workspace rename transaction.
   Historical document bytes remain unchanged; the new workspace gets a fresh revision."
  [db old new]
  (let [{:keys [drafts history]} (snapshot db)]
    (vec
      (concat
        (for [row history
              :let [[workspace graph revision] (edn/read-string (:semantic.history/key row))]
              :when (= old workspace)]
          {:db/id [:semantic.history/key (:semantic.history/key row)]
           :semantic.history/key (pr-str [new graph revision])})
        (mapcat
          (fn [row]
            (let [[workspace graph] (edn/read-string (:semantic.draft/key row))]
              (when (= old workspace)
                (let [revision (inc (:semantic.draft/revision row))
                      document (-> (edn/read-string (:semantic.draft/document row))
                                   (assoc-in [:workspace :workspace/id] new)
                                   (assoc-in [:publication :source/revision] revision))
                      text (dialect/canonical-str document)]
                  [{:db/id [:semantic.draft/key (:semantic.draft/key row)]
                    :semantic.draft/key (pr-str [new graph])
                    :semantic.draft/revision revision :semantic.draft/document text}
                   {:semantic.history/key (pr-str [new graph revision])
                    :semantic.history/document text
                    :semantic.history/provenance (pr-str {:actor {:issuer "atlas-system" :subject "workspace-rename"}})}])))) drafts)))))

(defn deletion-tx
  "Remove one Graph's document and revision contents in its registry transaction.
   Only addresses are read; large document contents are never materialized."
  [db workspace graph]
  (let [address [workspace graph]
        key (pr-str address)
        draft-id (d/q '[:find ?e . :in $ ?key :where [?e :semantic.draft/key ?key]] db key)
        histories (d/q '[:find ?e ?key :where [?e :semantic.history/key ?key]] db)]
    (vec (concat
           (when draft-id [[:db/retractEntity draft-id]])
           (for [[id history-key] histories
                 :let [[history-workspace history-graph] (edn/read-string history-key)]
                 :when (= address [history-workspace history-graph])]
             [:db/retractEntity id])))))
