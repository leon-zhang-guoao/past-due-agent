(ns open.atlas.studio.version.impl.store
  "**已切版本的登记**：Datahike schema + 读写（[D3.6](../../../../../../../../docs/design/30-workspace-graph.md#d36-graph-版本--密封的成品不是菜谱的版本号)）。

   与 B5a 的治理数据同则（graph README 裁定 ①）：**自己的
   `:db.unique/identity` 键，不共用 `:pool.object/key`**，于是 `pool/objects`、
   `as-dialect`、投机校验与池的导出**查不到**它——版本登记不是方言概念,
   混进模型会让同一份模型因为「它被切过几次」而产出不同的 content-hash（D4.3）。

   记的是 D4.3 的三种身份加 provenance：`(workspace, graph, version)` 是 graph
   版本身份，`content-hash` 是内容身份，`artifact-digest` 是制品完整性——后者
   **由不可变的制品登记记录保存**，不写回三份文件（D4.3 原话），这里就是那条
   记录。schema 由本模块自己装，不去改 graph 或 pool 的那一份（D1.8 单向）。"
  (:require [datahike.api :as d]))

(def schema
  [{:db/ident       :studio.version/id
    ;; `<workspace>/<graph>@vN` 的打印形。**unique identity 就是「切出即不可变」
    ;; 在存储层的落点**：同一个版本号第二次 transact 不会新建一条，而是撞上
    ;; 同一个实体——所以「已存在即拒绝」在上层判一次，存储层再兜一次。
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :studio.version/graph           :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :studio.version/number          :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
   {:db/ident :studio.version/content-hash    :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :studio.version/artifact-digest :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :studio.version/cut-at          :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :studio.version/cut-by          :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   ;; R3.5 reclaim: the artifact bytes are gone from this instance, the record
   ;; stays (numbers are never reused; audit and diff reports still name it).
   {:db/ident :studio.version/reclaimed-at    :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :studio.version/reclaimed-by    :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])

(defn install! [conn] (d/transact conn schema) conn)

(defn id [qn n] (str qn "@v" n))

(defn- ->version [m]
  (cond-> {:version/id              (:studio.version/id m)
           :version/graph           (:studio.version/graph m)
           :version/number          (:studio.version/number m)
           :version/content-hash    (:studio.version/content-hash m)
           :version/artifact-digest (:studio.version/artifact-digest m)
           :version/cut-at          (:studio.version/cut-at m)
           :version/cut-by          (:studio.version/cut-by m)}
    (:studio.version/reclaimed-at m)
    (assoc :version/reclaimed-at (:studio.version/reclaimed-at m)
           :version/reclaimed-by (:studio.version/reclaimed-by m))))

(defn all-versions
  "全部已切版本，按 `(限定名, 版本号)` 排序。顺序在本 ns 里定死——Datahike 的
   返回顺序不进任何输出。"
  [db]
  (vec (sort-by (juxt :version/graph :version/number)
                (map ->version
                     (d/q '[:find [(pull ?e [*]) ...]
                            :where [?e :studio.version/id]] db)))))

(defn versions-of [db qn] (vec (filter #(= qn (:version/graph %)) (all-versions db))))

(defn cut-any?
  "**这张图切过版本没有**——D3.10 那一半的判据（B5a.1 注释里说的「查过版本的
   那个函数」查的就是它）。"
  [db qn]
  (boolean (seq (versions-of db qn))))

(defn deletion-tx
  "Transaction fragment that retracts every cut-version registry record owned
   by one Graph. Runtime deployment evidence and artifact bytes live outside
   Datahike and are deliberately not addressed here."
  [db qn]
  (vec
   (for [entity (d/q '[:find [?e ...]
                         :in $ ?graph
                         :where [?e :studio.version/graph ?graph]]
                       db qn)]
     [:db/retractEntity entity])))

(defn next-number
  "下一个版本号：**具体数字，单调递增**。取的是已登记的最大值 + 1，而不是
   计数——一个版本被作废（R3.5）之后号码不回收，否则同一个号码会指向两份
   内容，而制品身份要求它唯一（D4.3）。"
  [db qn]
  (inc (reduce max 0 (map :version/number (versions-of db qn)))))

(defn record!
  [conn {:keys [graph number content-hash artifact-digest cut-at cut-by]}]
  (d/transact conn [{:studio.version/id              (id graph number)
                     :studio.version/graph           graph
                     :studio.version/number          number
                     :studio.version/content-hash    content-hash
                     :studio.version/artifact-digest artifact-digest
                     :studio.version/cut-at          cut-at
                     :studio.version/cut-by          cut-by}]))

(defn mark-reclaimed!
  "Record that one version's artifact was reclaimed (R3.5). Write-once: the
   first reclaim's time and actor stay."
  [conn qn number {:keys [reclaimed-at reclaimed-by]}]
  (when-not (:studio.version/reclaimed-at (d/pull (d/db conn) '[*] [:studio.version/id (id qn number)]))
    (d/transact conn [{:studio.version/id           (id qn number)
                       :studio.version/reclaimed-at reclaimed-at
                       :studio.version/reclaimed-by reclaimed-by}])))
