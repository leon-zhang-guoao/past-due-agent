(ns open.atlas.studio.graph.impl.export
  "治理数据的导出 / 导入（裁定 ①）。

   **为什么单开一节，而不是塞进 `pool/export-data` 的 `:pool/objects`**：与 B4.4 的
   确认记录逐字同理——治理数据不是模型内容，混进对象清单会让同一份模型因为「谁 own
   它」不同而产出不同的 content-hash（D4.3）。

   **为什么是本模块自己的信封，而不是往池的信封上加一个键**：pool 不认识 workspace
   （B4 README 裁定 ⑤），也不该认识——D1.8 的依赖方向是 graph → pool。让池的信封长出
   `:graph/*` 键，等于下层替上层声明数据形状。于是重建的完整动作是**两节**：
   `pool/import!` 装模型，`graph/import!` 装治理。**owner 请裁**：若希望「一个文件、
   一次导入」，那要在 pool 的信封上开一个通用的「附加节」槽位，那是 pool 的改动，
   我不自行改它的契约。

   信封的键都带 `graph` 前缀（同池的 `pool` 前缀），与方言顶层键在读的人眼里一眼分得开。
   规范形复用 B0 的**同一个 writer**（`dialect/write-dialect`）：第二个打印器 = 第二份
   规范形定义。缺省不写（空节缺席），所以一个没有治理数据的池导出与 B5a 之前逐字节相同。"
  (:require [open.atlas.studio.graph.impl.registry :as registry]
            [clojure.set :as set]
            [datahike.api :as d]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.graph.impl.finding :as f]
            [open.atlas.studio.graph.impl.store :as store]))

(def ^:const version 2)

(def ^:private supported-versions
  "Envelope versions this importer reads.  Version 1 carried three sections
   that no longer exist (see `retired-sections`); an old backup must still
   import, so it is read and those sections are skipped, not refused."
  #{1 2})

(def ^:private retired-sections
  "Sections written by version 1 and retired by D3.22 (owner 2026-09-05).

   `:graph/claims` was the global namespace claim table D3.17 retired; the two
   mirror registers recorded writes that landed outside the writing
   workspace's territory (D3.4), which stored ownership makes impossible.
   Nothing installs their schema any more, so importing them would write
   datoms no reader reads.  They are **ignored and named in the result** —
   refusing would make every backup taken before today unrestorable, and
   dropping them silently would let someone believe governance data came back
   when it did not."
  #{:graph/claims :graph/trespasses :graph/unclaimed})

(def ^:private sections
  "节 → [读函数 建 tx 的函数]。加一节要同时加读与写，一张表管两头。"
  {:graph/workspaces [store/all-workspaces store/workspace-tx]
   :graph/graphs     [store/all-graphs     store/graph-tx]
   :graph/grants     [store/all-grants     store/grant-tx]})

(defn export-data
  "整池的治理数据 → 导出信封（EDN 数据）。各节顺序由 `store/all-*` 定死。"
  [db]
  (reduce (fn [acc [k [read-fn _]]]
            (let [rows (read-fn db)]
              (cond-> acc (seq rows) (assoc k rows))))
          {:graph/version version}
          (sort-by str sections)))

(defn export-str [db]
  (dialect/write-dialect (export-data db)))

(def ^:private known-keys
  (into (conj (set (keys sections)) :graph/version) retired-sections))

(defn- envelope-findings [data]
  (cond
    (not (map? data))
    [(f/make :graph.export/malformed :error [] "governance export must be a map"
             :got (f/redact data))]

    (seq (set/difference (set (keys data)) known-keys))
    [(f/make :graph.export/malformed :error []
             "governance export envelope key set is closed"
             :expected (vec (sort (map str known-keys)))
             :got (vec (sort (map str (set/difference (set (keys data)) known-keys)))))]

    (not (supported-versions (:graph/version data)))
    [(f/make :graph.export/version-unsupported :error [:graph/version]
             "unsupported governance export version"
             :expected (vec (sort supported-versions)) :got (:graph/version data))]

    :else
    (concat
     (for [[k _] (concat sections (map vector retired-sections (repeat nil)))
           :when (not (or (nil? (get data k)) (vector? (get data k))))]
       (f/make :graph.export/malformed :error [k] "section must be a vector"
               :got (f/redact (get data k))))
     ;; **成员形状要重判**。上面那条「不重跑登记判定」说的是查重、授权、地盘
     ;; 覆盖——那些是对「新登记一条」说的判断，拿去判一份历史状态会误报。
     ;; 但成员长什么样是**内容契约**，不是当初那次登记合不合法：一份成员写错
     ;; 类型的信封导进来，池里就多了一条永远匹配不上的授权记录，而它看起来
     ;; 完全正常（D5.10 的主体主键正是靠这一条守住的）。
     (for [ws (:graph/workspaces data)
           :when (and (map? ws) (not (registry/members-shape-ok? (:workspace/members ws))))]
       (f/make :graph.export/malformed :error [:graph/workspaces]
               (str "this workspace's members do not match the member contract: "
                    "{:principal {:issuer … :subject …} :role :owner|:editor} (D5.10)")
               :got (f/redact (:workspace/name ws)) :refs ["D5.10"])))))

(defn import!
  "导出信封 → 池。一次事务：治理数据之间没有互相的 tempid 依赖，各节独立。

   **不重跑登记判定**（查重、授权、地盘覆盖）：导入的是**已经成立过**的登记状态,
   而那些判定是对「新登记一条」说的。拿它们去判一份历史状态，等于要求过去满足今天的
   规则。信封形状仍然判。

   **旧信封（`:graph/version 1`）里退役的三节被忽略**，返回里 `:ignored` 逐节点名
   （D3.22）：老备份必须还能导进来，而静默丢掉会让人以为治理数据回来了。"
  [conn data]
  (let [errs (vec (envelope-findings data))]
    (if (seq errs)
      {:findings (f/sort-findings errs)}
      (let [tx (vec (for [[k [_ tx-fn]] (sort-by str sections)
                          row (get data k)]
                      (tx-fn row)))]
        (when (seq tx) (d/transact conn tx))
        (cond-> {:count (count tx) :findings []}
          (seq (filter #(seq (get data %)) retired-sections))
          (assoc :ignored (vec (sort (filter #(seq (get data %)) retired-sections)))))))))
