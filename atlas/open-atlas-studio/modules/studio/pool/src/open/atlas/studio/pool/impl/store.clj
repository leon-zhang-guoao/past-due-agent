(ns open.atlas.studio.pool.impl.store
  "Datahike 连接的开合，以及「把整池读成对象」这一个查询。

   本 ns 是池里唯一碰 IO 的地方：规则留在纯函数里可以在内存里单测，读盘写盘关在
   一个 ns 里。同则当年也是 B2 的 `legacy-import.cli` 那么切的（那份实现随转换器
   删除，见 `git log -- modules/legacy-import`）。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [open.atlas.studio.pool.impl.ownership :as ownership]
            [open.atlas.studio.pool.impl.schema :as schema]
            [open.atlas.studio.pool.impl.semantic :as semantic]))

(defn config
  "`:mem` 用于单测，`:file` 用于「删掉 store」那条退出条件——退出条件 1 要的是
   真的把库删掉再从导出建回来，内存库删了等于什么都没发生。"
  [{:keys [backend path id]}]
  {:store              (case (or backend :mem)
                         :mem  {:backend :mem :id (or id "pool")}
                         :file {:backend :file :path path})
   :schema-flexibility :write
   :keep-history?      false})

(defn- relocated-file-store? [{:keys [type config stored-config]}]
  ;; Datahike's error contains normalized store identities: [backend scope path].
  ;; For file stores scope defaults to the local hostname. Both scope and path
  ;; change on another machine; neither changes how the file data is decoded.
  ;; All database settings other than this file location must still agree.
  (let [stored-config (if (true? (:allow-unsafe-config stored-config))
                        (dissoc stored-config :allow-unsafe-config)
                        stored-config)
        current (:store config)
        stored (:store stored-config)]
    (and (= type :config-does-not-match-stored-db)
         (vector? current) (vector? stored)
         (= 3 (count current) (count stored))
         (= :file (first current) (first stored))
         (string? (nth current 1)) (string? (nth stored 1))
         (string? (nth current 2)) (string? (nth stored 2))
         (not= current stored)
         (= (assoc config :store [:file])
            (assoc stored-config :store [:file])))))

(defn- connect! [cfg]
  (try
    (d/connect cfg)
    (catch clojure.lang.ExceptionInfo e
      (if (or (relocated-file-store? (ex-data e))
              ;; Datahike persists this migration flag on the first transaction.
              ;; On later starts accept only that exact residual difference.
              (let [{:keys [type config stored-config]} (ex-data e)]
                (and (= type :config-does-not-match-stored-db)
                     (= :file (first (:store config)))
                     (true? (:allow-unsafe-config stored-config))
                     (= config (dissoc stored-config :allow-unsafe-config)))))
        (d/connect (assoc cfg :allow-unsafe-config true))
        (throw e)))))

(defn create! [cfg]
  (when (= :file (get-in cfg [:store :backend]))
    (let [pool (.getCanonicalFile (io/file (get-in cfg [:store :path])))
          lock (io/file (.getParentFile pool) (str (.getName pool) ".maintenance.lock"))]
      (when (.exists lock)
        (throw (ex-info "Studio pool is under offline maintenance. Finish or recover that maintenance before starting Studio."
                        {:type :pool/maintenance-in-progress})))))
  (ownership/acquire! cfg)
  (try
    (when-not (d/database-exists? cfg) (d/create-database cfg))
    (let [conn (connect! cfg)]
      (try
        (d/transact conn (into schema/schema semantic/schema))
        conn
        (catch Throwable e (d/release conn) (throw e))))
    (catch Throwable e (ownership/release! cfg) (throw e))))

(defn release! [conn]
  (let [cfg (:config @conn)]
    (try (d/release conn) (finally (ownership/release! cfg)))))

(defn delete! [cfg] (when (d/database-exists? cfg) (d/delete-database cfg)))

(defn all-objects
  "整池 → [对象…]（带 :pool/revision）。

   为什么每次写都读整池：裁定 ② 要把受影响对象拼回方言形状交 B0 判，而
   B0 的跨条目不变量（先注册后使用、成员须已登记、词条绑定互异语境）**按定义**
   跨对象——只拼受影响的那几个，判出来的是另一个问题的答案。整池读一次的代价
   在 178 属性这个量级上可以忽略；真正贵的时候再谈增量，那时至少还有这份
   慢而正确的实现做判据。

   The two-arity form answers the same question **for one workspace** (D3.22).
   It is not an optimisation: after D3.17 a workspace is a closed world, so
   there is no cross-workspace invariant left to judge, and judging the whole
   pool would let one workspace's dirt block another's write. The one-arity
   form stays the whole pool, because migration, export and «what does this
   Studio hold» are genuinely pool-wide questions."
  ([db]
   (->> (d/q '[:find [(pull ?e [*]) ...] :where [?e :pool.object/key]] db)
        (map schema/entity->object)
        (sort-by #(pr-str [(:pool/type %) (:pool/id %)]))
        vec))
  ([db workspace]
   (->> (d/q '[:find [(pull ?e [*]) ...] :in $ ?w
               :where [?e :pool.object/key] [?e :pool.object/workspace ?w]] db workspace)
        (map schema/entity->object)
        (sort-by #(pr-str [(:pool/type %) (:pool/id %)]))
        vec)))

(defn- confirmation->map [m]
  (cond-> {:confirmation/key        (edn/read-string (:pool.confirmation/key m))
           :confirmation/kind       (:pool.confirmation/kind m)
           :confirmation/candidate  (:pool.confirmation/candidate m)
           :confirmation/decision   (:pool.confirmation/decision m)
           :confirmation/entrypoint (:pool.confirmation/entrypoint m)
           :confirmation/produced   (edn/read-string (:pool.confirmation/produced m))}
    (:pool.confirmation/note m)     (assoc :confirmation/note (:pool.confirmation/note m))
    (:pool.confirmation/token-id m) (assoc :confirmation/token-id (:pool.confirmation/token-id m))
    (:pool.confirmation/actor m)    (assoc :confirmation/actor
                                           (edn/read-string (:pool.confirmation/actor m)))
    (:pool.confirmation/at m)       (assoc :confirmation/at (:pool.confirmation/at m))))

(defn- mutation->map [m]
  (cond-> {:mutation/id              (:pool.mutation/id m)
           :mutation/entrypoint      (:pool.mutation/entrypoint m)
           :mutation/action          (:pool.mutation/action m)
           :mutation/object          (edn/read-string (:pool.mutation/object m))
           :mutation/revision-before (:pool.mutation/revision-before m)}
    (:pool.mutation/revision-after m) (assoc :mutation/revision-after
                                             (:pool.mutation/revision-after m))
    (:pool.mutation/token-id m)       (assoc :mutation/token-id (:pool.mutation/token-id m))
    (:pool.mutation/actor m)          (assoc :mutation/actor
                                             (edn/read-string (:pool.mutation/actor m)))
    (:pool.mutation/at m)             (assoc :mutation/at (:pool.mutation/at m))))

(defn all-mutations
  "整池的**变更审计**（8.4 P1b），按记录 id 字典序。

   **第三条查询**，与 `all-objects`、`all-confirmations` 三者互不共用寻址键：
   审计查不进模型，模型也查不出审计（audit 裁定 ③）。"
  [db]
  (->> (d/q '[:find [(pull ?e [*]) ...] :where [?e :pool.mutation/id]] db)
       (map mutation->map)
       (sort-by :mutation/id)
       vec))

(defn all-confirmations
  "整池的确认记录（B4.4 裁定 ②），按记录键字典序。

   **另一条查询**，与 `all-objects` 不共用 `:pool.object/key`——这是「确认记录
   不进模型」的落点：`as-dialect` 与导出的对象清单都经 `all-objects`，于是它们
   **查不到**确认记录，不是「记得别放进去」。"
  [db]
  (->> (d/q '[:find [(pull ?e [*]) ...] :where [?e :pool.confirmation/key]] db)
       (map confirmation->map)
       (sort-by #(pr-str (:confirmation/key %)))
       vec))

(defn find-confirmation [db k]
  (first (d/q '[:find [(pull ?e [*]) ...] :in $ ?k :where [?e :pool.confirmation/key ?k]] db k)))

(defn find-object
  "按 [type id] 取一张实体 map（原始属性形，update 要拿它算该 retract 哪些字段）。"
  [db k]
  (first (d/q '[:find [(pull ?e [*]) ...] :in $ ?k :where [?e :pool.object/key ?k]] db k)))
