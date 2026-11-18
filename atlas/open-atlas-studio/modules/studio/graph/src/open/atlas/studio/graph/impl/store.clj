(ns open.atlas.studio.graph.impl.store
  "治理数据的读写查询。**每种实体一条独立查询**，一条都不经 `:pool.object/key`
   ——裁定 ① 的落点在这里是可以逐行核对的（同 B4.4 的 `all-confirmations`）。

   顺序一律在本 ns 里定死（按各自的 unique 键字典序）：Datahike 的返回顺序不进
   任何输出，否则「两次导出逐字节相同」就是一句碰运气的话。"
  (:require [datahike.api :as d]
            [open.atlas.studio.graph.impl.schema :as schema]))

(defn- pull-all [db attr]
  (d/q '[:find [(pull ?e [*]) ...] :in $ ?a :where [?e ?a]] db attr))

;; --- workspace -------------------------------------------------------------

(defn- ->workspace [m]
  (cond-> {:workspace/name    (:graph.workspace/name m)
           :workspace/members (schema/decode-edn (:graph.workspace/members m))}
    ;; Absent stays absent (D3.21), same discipline as a graph's default query.
    (:graph.workspace/default-graph m)
    (assoc :workspace/default-graph (:graph.workspace/default-graph m))))

(defn all-workspaces [db]
  (vec (sort-by :workspace/name (map ->workspace (pull-all db :graph.workspace/name)))))

(defn find-workspace [db nm]
  (some-> (first (d/q '[:find [(pull ?e [*]) ...] :in $ ?n
                        :where [?e :graph.workspace/name ?n]] db nm))
          ->workspace))

(defn workspace-tx [{:workspace/keys [name members default-graph]}]
  (cond-> {:graph.workspace/name    name
           :graph.workspace/members (schema/encode-edn members)}
    default-graph (assoc :graph.workspace/default-graph default-graph)))

(defn retract-default-graph-tx
  "Retract a workspace's landing graph. Same reason `graph-tx` needs its own
   retraction: an omitted attribute does not clear the datom already there."
  [workspace current]
  [:db/retract [:graph.workspace/name workspace]
   :graph.workspace/default-graph current])

;; --- graph -----------------------------------------------------------------

(defn- ->graph [m]
  (merge
   {:graph/id             (:graph.graph/id m)
   :graph/state          (or (:graph.graph/state m) :active)
   :graph/qualified-name (:graph.graph/qualified-name m)
   :graph/workspace      (:graph.graph/workspace m)
   :graph/name           (:graph.graph/name m)
   :graph/members        (or (schema/decode-edn (:graph.graph/members m)) {})
   :graph/access         (or (schema/decode-edn (:graph.graph/access m)) [])}
  ;; 缺席的键**不出现**，而不是出现为 nil：读回来的图要与写进去的那一份相等,
  ;; 否则「登记返回的 graph」与「查出来的 graph」在 `=` 上就分了叉。
  ;; 迁移期读面：D3.19 之前的菜谱。`migrate-recipes!` 是唯一的读者,
  ;; 新写入永不产生它们（见 `graph-tx`）。
  (cond-> {}
    (:graph.graph/display-name m) (assoc :graph/display-name (:graph.graph/display-name m))
    (:graph.graph/created-at m) (assoc :graph/created-at (:graph.graph/created-at m))
    ;; 不设就不出现——「图上没有默认查询」与「默认查询是空的」是两件事,
    ;; 而 Lab 只对前者回答空编辑器。
    (:graph.graph/default-query m)
    (assoc :graph/default-query (schema/decode-edn (:graph.graph/default-query m)))
    ;; Absent stays absent, same discipline: an unnamed graph must not read
    ;; back carrying an empty release name (doc 104 §8.17.7 D).
    (:graph.graph/package-name m)
    (assoc :graph/package-name (:graph.graph/package-name m))
    (:graph.graph/selectors m)
    (assoc :graph/legacy-selectors (schema/decode-edn (:graph.graph/selectors m)))
    (:graph.graph/references m)
    (assoc :graph/legacy-references (schema/decode-edn (:graph.graph/references m))))))

(defn all-graphs [db]
  (vec (sort-by :graph/qualified-name (map ->graph (pull-all db :graph.graph/qualified-name)))))

(defn find-graph [db qn]
  (some-> (first (d/q '[:find [(pull ?e [*]) ...] :in $ ?q
                        :where [?e :graph.graph/qualified-name ?q]] db qn))
          ->graph))

(defn find-graph-by-id [db id]
  (some-> (first (d/q '[:find [(pull ?e [*]) ...] :in $ ?id
                        :where [?e :graph.graph/id ?id]] db id))
          ->graph))

(defn graph-tx [{:graph/keys [id state qualified-name workspace name display-name members created-at access
                              default-query package-name]}]
  (cond-> {:graph.graph/qualified-name qualified-name
           :graph.graph/state          (or state :active)
           :graph.graph/workspace      workspace
           :graph.graph/name           name
           :graph.graph/members        (schema/encode-edn (or members {}))
           :graph.graph/access         (schema/encode-edn (vec (or access [])))}
    id         (assoc :graph.graph/id id)
    display-name (assoc :graph.graph/display-name display-name)
    created-at (assoc :graph.graph/created-at created-at)
    ;; Same absent-means-absent discipline as the read side: a graph written
    ;; without one must not come back carrying an empty setting.
    default-query (assoc :graph.graph/default-query (schema/encode-edn default-query))
    ;; A plain string, not EDN: it is one scalar and the installing side reads
    ;; it out of a manifest, not out of this store.
    package-name (assoc :graph.graph/package-name package-name)))

(defn retract-package-name-tx
  "Retract a graph's release name — same reason `retract-default-query-tx`
   exists: `graph-tx` omits the attribute for a nil, and an omitted attribute
   leaves the datom that is already there."
  [qn current]
  [:db/retract [:graph.graph/qualified-name qn] :graph.graph/package-name current])

(defn retract-default-query-tx
  "Retract a graph's default query.

   Needed as its own tx because `graph-tx` OMITS the attribute when there is no
   default, and an omitted attribute in a transacted map leaves whatever datom
   is already there — so «clear it» has to be said, not implied. Encoding stays
   here rather than at the call site: this ns owns the EDN seam."
  [qn current]
  [:db/retract [:graph.graph/qualified-name qn]
   :graph.graph/default-query (schema/encode-edn current)])

;; --- 授权（D3.14）----------------------------------------------------------

(defn grant-key [grantee graph]
  (schema/encode-edn [grantee graph]))

(defn- ->grant [m]
  {:grant/grantee (schema/decode-edn (:graph.grant/grantee m))
   :grant/graph   (:graph.grant/graph m)
   :grant/ceiling (:graph.grant/ceiling m)})

(defn all-grants [db]
  (vec (sort-by #(grant-key (:grant/grantee %) (:grant/graph %))
                (map ->grant (pull-all db :graph.grant/key)))))

(defn find-grant [db grantee graph]
  (some-> (first (d/q '[:find [(pull ?e [*]) ...] :in $ ?k
                        :where [?e :graph.grant/key ?k]] db (grant-key grantee graph)))
          ->grant))

(defn grant-tx [{:grant/keys [grantee graph ceiling]}]
  {:graph.grant/key     (grant-key grantee graph)
   :graph.grant/grantee (schema/encode-edn grantee)
   :graph.grant/graph   graph
   :graph.grant/ceiling ceiling})

(defn retract-grant!
  "撤销 = 整条实体退役。**只影响将来**（D3.14）：这里退役的是**授权登记**,
   不碰任何 graph 的菜谱，更不碰已切版本——B5a 还没有版本，所以这条现在只体现为
   「已有的引用登记一个字节不动」；**B5b 切版本接上之后**它的完整含义才出现：
   撤销之后不能再切出新的引用它的版本，已切出/已部署/已发 token 的版本一律不动。"
  [conn grantee graph]
  (when-let [e (first (d/q '[:find [?e ...] :in $ ?k
                             :where [?e :graph.grant/key ?k]] (d/db conn)
                           (grant-key grantee graph)))]
    (d/transact conn [[:db/retractEntity e]])
    true))
