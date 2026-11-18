(ns open.atlas.studio.pool.impl.export
  "池级导出 / 导入（裁定 ③）。

   **不是方言文件**：池装着共享的对象目录（D3.1），方言文件是**一张 graph 的
   投影结果**。把导出写成方言等于提前替 B5a 做了投影。

   规范形复用 B0 的纪律与**同一个 writer**：`dialect/write-dialect` 就是那条
   「键字典序、缺省不写、100 列版式、逐字节确定」的实现。池自己再写一个打印器
   就是第二份规范形定义，而两份规范形分家的那天，导出还是导出，只是 hash 变了
   却没人知道为什么。

   B4.4 起信封多一个键 `:pool/confirmations` —— **候选签字的确认记录**。
   它与 `:pool/objects` **分开**是裁定 ② 的落点：确认记录不是模型内容，
   `as-dialect` 里没有它，制品的 content-hash 因此不随「谁签的」而变（D4.3）。
   但它必须**跟着池走**：退出条件 1 的「删掉 store、导入最后一次导出」若把治理
   痕迹丢在地上，那份痕迹就成了「只要重建一次就消失」的东西——不可变的记录
   连一次重建都撑不过，说它不可变是自欺。键**可缺席**（老导出仍导得进）。

   8.4 P1b 起同理再多一个键 `:pool/mutations` —— **变更审计**。同一条理由，
   而且更硬：一条「重建一次就消失」的审计记录，在它唯一被需要的那个场合
   （事后问「这个 revision 里哪些改动来自 AI」）恰好是空的。键同样**可缺席**。

   信封的键都带 `pool` 前缀，与方言顶层键（全部裸键）在读的人眼里
   一眼分得开：

       {:pool/confirmations [{:confirmation/candidate … :confirmation/decision … …} …]
        :pool/version 1
        :pool/objects [{:pool/entry {…} :pool/id … :pool/revision 1 :pool/type :attribute} …]}

   `:pool/revision` 进导出，因为退出条件 1 要的是「精确重建出**同一模型**」——
   revision 是对象状态的一部分，丢了它，导回来的池对每个 `expected_revision`
   都会给出与导出前不同的答案。"
  (:require [open.atlas.common.dialect :as dialect]
            [datahike.api :as d]
            [open.atlas.studio.pool.impl.finding :as f]
            [open.atlas.studio.pool.impl.model :as model]
            [open.atlas.studio.pool.impl.schema :as schema]
            [open.atlas.studio.pool.impl.semantic :as semantic]
            [open.atlas.studio.pool.impl.store :as store]))

(def ^:const version 1)

(defn export-data
  "整池 → 导出信封（EDN 数据）。对象顺序按 `[type id]` 打印形字典序——
   与写入历史无关是「两次导出逐字节相同」的前提。"
  [db]
  (cond-> {:pool/version version
           ;; `:pool/workspace` (D3.22) is part of an object's state, exactly as
           ;; `:pool/revision` is: an export that dropped it would restore into
           ;; a pool nobody could write to. It is absent on objects an
           ;; unstamped pool exports, and `open-pool!`'s stamp pass fills those
           ;; in after the import — there is no second derivation here.
           :pool/objects (mapv #(select-keys % [:pool/type :pool/id :pool/revision
                                                :pool/entry :pool/workspace])
                               (store/all-objects db))}
    ;; 缺省不写（dialect-v1 §4）：没有确认记录的池，导出与 B4.1 时代逐字节相同。
    (seq (store/all-confirmations db))
    (assoc :pool/confirmations (store/all-confirmations db))

    (seq (store/all-mutations db))
    (assoc :pool/mutations (store/all-mutations db))

    (seq (:drafts (semantic/snapshot db)))
    (assoc :pool/semantic (semantic/snapshot db))))

(defn export-str [db]
  (dialect/write-dialect (export-data db)))

(defn- envelope-findings [data]
  (cond
    (not (map? data))
    [(f/make :pool.export/malformed :error [] "export must be a map"
             :got (f/redact data))]

    (seq (remove #{:pool/version :pool/objects :pool/confirmations :pool/mutations :pool/semantic} (keys data)))
    [(f/make :pool.export/malformed :error [] "export envelope key set is closed"
             :expected [:pool/version :pool/objects :pool/confirmations :pool/mutations]
             :got (vec (sort (map str (remove #{:pool/version :pool/objects
                                                :pool/confirmations :pool/mutations :pool/semantic}
                                              (keys data))))))]

    (not (or (nil? (:pool/confirmations data)) (vector? (:pool/confirmations data))))
    [(f/make :pool.export/malformed :error [:pool/confirmations]
             ":pool/confirmations must be a vector"
             :got (f/redact (:pool/confirmations data)))]

    (not (or (nil? (:pool/mutations data)) (vector? (:pool/mutations data))))
    [(f/make :pool.export/malformed :error [:pool/mutations]
             ":pool/mutations must be a vector"
             :got (f/redact (:pool/mutations data)))]

    (not= version (:pool/version data))
    [(f/make :pool.export/version-unsupported :error [:pool/version]
             "unsupported pool export version"
             :expected version :got (:pool/version data))]

    (not (vector? (:pool/objects data)))
    [(f/make :pool.export/malformed :error [:pool/objects] ":pool/objects must be a vector"
             :got (f/redact (:pool/objects data)))]

    :else
    (let [objs (:pool/objects data)]
      (concat
       (for [[i o] (map-indexed vector objs)
             :when (not (and (map? o)
                             (empty? (remove #{:pool/type :pool/id :pool/revision
                                               :pool/entry :pool/workspace}
                                             (keys o)))
                             (model/known-type? (:pool/type o))
                             (model/valid-id? (:pool/type o) (:pool/id o))
                             (integer? (:pool/revision o))
                             (or (nil? (:pool/workspace o)) (string? (:pool/workspace o)))
                             (map? (:pool/entry o))))]
         (f/make :pool.export/malformed :error [:pool/objects i]
                 "object envelope is malformed"
                 :got {:type (:pool/type o)}))
       ;; D2.29 之前这里还有一道「navigation 的父 namespace 必须也在导出里」：
       ;; 子对象无处可挂时 `objects->dialect` 会**静默丢掉**它。池里再没有子对象
       ;; （navigation 成了普通属性），这一层的静默丢弃随之消失——一个 namespace
       ;; 缺席现在是模型层的悬空引用，由导入末尾那次 `read-dialect` 点名拒绝。
       ;; 同一个 [type id] 出现两次：导入是逐对象 upsert，后者会静默盖掉前者。
       ;; 静默覆盖正是 dialect-v1 §4 拒绝继承参考实现的那件事。
       (for [[k n] (frequencies (map #(model/object-key (:pool/type %) (:pool/id %)) objs))
             :when (> n 1)]
         (f/make :pool.export/duplicate-object :error [:pool/objects]
                 "the same [type id] appears more than once in the export"
                 :got {:count n}))))))

(defn import!
  "导出信封 → 空池。全部对象**一次事务**写入，对象之间没有先后要求：
   D2.29 之后池里全是平的，每个对象自带 `:pool.object/key`。

   写完照裁定 ② 再判一次整池：导出文件是外部输入，它凭什么是干净的？"
  ([conn data] (import! conn data {}))
  ([conn data checkers]
  (let [semantic-plan (if (:pool/semantic data) (semantic/restore-plan (d/db conn) (:pool/semantic data) checkers) {:tx []})
        env (concat (envelope-findings data) (:findings semantic-plan))]
    (if (seq env)
      {:findings (f/sort-findings (vec env))}
      (let [objs (:pool/objects data)
            confs (:pool/confirmations data)
            muts (:pool/mutations data)
            tx   (mapv (fn [{:pool/keys [revision] :as o}] (schema/object->tx o revision))
                       objs)
            ;; 确认记录随同一笔事务装回去。它们**不进** `objects->dialect`
            ;; （查的是另一个 unique 属性），所以下面那次 B0 重检看不到它们
            ;; ——那正是「不进模型」的意思。
            tx   (into tx (map-indexed
                           (fn [i c]
                             (cond-> {:db/id (str "conf-" i)
                                      :pool.confirmation/key
                                      (binding [*print-namespace-maps* false]
                                        (pr-str (:confirmation/key c)))
                                      :pool.confirmation/kind       (:confirmation/kind c)
                                      :pool.confirmation/candidate  (:confirmation/candidate c)
                                      :pool.confirmation/decision   (:confirmation/decision c)
                                      :pool.confirmation/entrypoint (:confirmation/entrypoint c)
                                      :pool.confirmation/produced
                                      (model/encode-edn (:confirmation/produced c))}
                               (:confirmation/note c)
                               (assoc :pool.confirmation/note (:confirmation/note c))
                               (:confirmation/token-id c)
                               (assoc :pool.confirmation/token-id (:confirmation/token-id c))
                               (:confirmation/actor c)
                               (assoc :pool.confirmation/actor
                                      (model/encode-edn (:confirmation/actor c)))
                               (:confirmation/at c)
                               (assoc :pool.confirmation/at (:confirmation/at c))))
                           (or confs [])))
            ;; 审计记录同样随同一笔事务装回去，同样查不进模型。
            tx   (into tx (map-indexed
                           (fn [i m]
                             (cond-> {:db/id (str "mut-" i)
                                      :pool.mutation/id         (:mutation/id m)
                                      :pool.mutation/entrypoint (:mutation/entrypoint m)
                                      :pool.mutation/action     (:mutation/action m)
                                      :pool.mutation/object     (model/encode-edn
                                                                 (:mutation/object m))
                                      :pool.mutation/revision-before
                                      (:mutation/revision-before m)}
                               (:mutation/revision-after m)
                               (assoc :pool.mutation/revision-after (:mutation/revision-after m))
                               (:mutation/token-id m)
                               (assoc :pool.mutation/token-id (:mutation/token-id m))
                               (:mutation/actor m)
                               (assoc :pool.mutation/actor
                                      (model/encode-edn (:mutation/actor m)))
                               (:mutation/at m)
                               (assoc :pool.mutation/at (:mutation/at m))))
                           (or muts [])))
            tx (into tx (:tx semantic-plan))
            {:keys [db-after]} (d/with (d/db conn) tx)
            model (model/objects->dialect (store/all-objects db-after))
            {:keys [findings]} (dialect/read-dialect
                                (binding [*print-namespace-maps* false] (pr-str model)))
            errs  (filter #(= :error (:severity %)) findings)]
        (if (seq errs)
          {:findings (into [(f/make :pool.dialect/rejected :error []
                                    (str "the imported model does not pass the dialect schema: "
                                         (count errs) " error finding(s) follow")
                                    :got {:error-count (count errs)}
                                    :refs ["dialect-v1 §7"])]
                           errs)}
          (do (d/transact conn tx)
              {:count (count objs) :findings []})))))))
