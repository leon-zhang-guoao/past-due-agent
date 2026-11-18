(ns open.atlas.studio.pool.impl.action
  "`execute_action` —— 七概念 + 三注册表的**唯一写面**（§7.3 建模面那一行）。

   一次 action 的完整流程，五步，缺一步就有一种拒绝会漏掉：

   1. **形状**：action map 键面封闭、`expected_revision` 必带；
   2. **寻址**：create 撞已有 id → `:id-taken`；update/delete 指不存在 →
      `:unknown-entity`。**没有公开 upsert**——upsert 让「我在改一个已有的东西」
      与「我在造一个新东西」这两件事在调用点上不可区分，而它们的错法完全不同；
   3. **对象级 revision**（裁定 ④）：不对即拒，绝不「以我为准」覆盖；
   4. **引用**：delete 的目标仍被引用即**点名**拒绝（谁引用的，逐个列出）；
   5. **投机事务**（裁定 ②）+ **差集**（studio/graph README 裁定 ⑤）：`d/with`
      前后各把整池拼回方言交 `dialect/read-dialect` 一次，**只有这次写引入的新
      error 才拒绝**；写之前就在的脏当 `:warning` 随行报出。
      整池里任何一处不干净就拒掉所有人的写，而报出来的是「你的模型不合法」——
      B5a 让池里同时住着多个 workspace 之后，那句话指向的往往是别人的对象。

   第 5 步在第 1–4 步**之后**是有意的：前四步的拒绝能说出「你这次动作错在哪」，
   而 B0 只会说「这份模型不合法」。同一次错误的两种说法里，指向动作的那种
   才修得动。

   **不泄漏**（finding-v1 §5 / B4 退出条件 2）：本 ns 产出的任何 finding 都
   只带**类型、id 与计数**，条目内容一个字节不进 `:got`——source 条目里就坐着
   `:jdbc_url`。"
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.pool.impl.audit :as audit]
            [open.atlas.studio.pool.impl.finding :as f]
            [open.atlas.studio.pool.impl.model :as model]
            [open.atlas.studio.pool.impl.schema :as schema]
            [open.atlas.studio.pool.impl.store :as store]))

(def ^:private action-keys
  "The closed key set of an action.

   `:workspace` joined it with D3.22: a pool object's owner is a stored fact,
   so every write has to say **on whose behalf** it is writing. It is a key of
   the action rather than of `prov` because it is not provenance — it decides
   whether the write happens at all, and the host must supply it from the
   caller's token or session, never from a tool parameter."
  #{:action :type :id :entry :expected_revision :workspace})

(defn- path
  "finding 的 `:path` 指到「这次动作打的是哪个对象」，不是方言根下的键路径——
   池的作者面是 action，位置说给他听才有用。"
  [t id]
  [:pool t id])

(defn check-shape
  "The action's shape findings, or empty. Public so `impl/batch` runs the SAME
   check per action that `execute` runs for one: a batch that skipped it let a
   nil `:workspace` reach the ownership check and be reported as a mismatch
   against nil (D3.22) instead of as the malformed action it was."
  [{:keys [action type id entry expected_revision workspace] :as a}]
  (cond-> []
    (seq (set/difference (set (keys a)) action-keys))
    (conj (f/make :pool.action/malformed :error []
                  "action key set is closed"
                  :expected (vec (sort (map str action-keys)))
                  :got (vec (sort (map str (set/difference (set (keys a)) action-keys))))
                  :refs ["dialect-v1 §7.3"]))

    (not (contains? #{:create :update :delete} action))
    (conj (f/make :pool.action/unknown-action :error []
                  "action must be one of :create :update :delete"
                  :expected [:create :update :delete] :got action))

    (not (model/known-type? type))
    (conj (f/make :pool.action/unknown-type :error []
                  "unknown object type"
                  :expected (vec model/type-order) :got type))

    (not (and (string? workspace) (seq workspace)))
    ;; D3.22: no default, and no «the pool will work it out». A write with no
    ;; workspace used to be answerable by deriving one from the object's name;
    ;; D3.17 retired the table that made that possible.
    (conj (f/make :pool.action/malformed :error []
                  ":workspace is required on every action (D3.22: ownership is stored)"
                  :expected "non-empty string" :got (f/redact workspace)
                  :refs ["D3.17" "D3.22" "§7.3"]))

    (not (integer? expected_revision))
    (conj (f/make :pool.action/malformed :error []
                  "expected_revision is required on every action (object-level)"
                  :expected "integer" :got (f/redact expected_revision)
                  :refs ["D3.5" "§7.3"]))

    (and (model/known-type? type) (not (model/valid-id? type id)))
    (conj (f/make :pool.action/invalid-id :error []
                  (str "id shape does not match the identity convention of " type)
                  :expected (:id-kind (get model/types type))
                  :got (f/redact id)
                  :refs ["dialect-v1 §3" "D2.17"]))

    (and (contains? #{:create :update} action) (not (map? entry)))
    (conj (f/make :pool.action/malformed :error []
                  ":entry must be a map on :create/:update"
                  :got (f/redact entry)))

    ;; split-entry's contract: a non-empty :open bucket on a type without
    ;; :open-keys is an error, never a silent drop (CLAUDE.md 拒绝而非截断).
    ;; Before this check, object->tx discarded such keys and the write still
    ;; reported success — a typo'd key vanished without a trace.  One key is
    ;; exempt: registry entries carry their own :id in dialect form, so an
    ;; :id that restates the action's id is identity, not data — but a
    ;; *different* :id is an ambiguity and is rejected below.
    (and (contains? #{:create :update} action)
         (map? entry)
         (model/known-type? type)
         (nil? (model/open-key-attr type))
         (seq (dissoc (:open (model/split-entry type entry)) :id)))
    (conj (let [open (dissoc (:open (model/split-entry type entry)) :id)]
            (f/make :pool.action/unknown-entry-key :error []
                    (str "these keys are not part of the " type " entry contract")
                    :expected (vec (sort (map str (keys (:fields (get model/types type))))))
                    :got (vec (sort (map str (keys open))))
                    :refs ["dialect-v1 §7.3"])))

    (and (contains? #{:create :update} action)
         (map? entry)
         (contains? entry :id)
         (not= (:id entry) id))
    (conj (f/make :pool.action/malformed :error []
                  "the entry's own :id disagrees with the action's :id"
                  :expected (f/redact id) :got (f/redact (:id entry))
                  :refs ["dialect-v1 §7.3"]))

    ;; A link **is** its endpoint pair (D2.3): `dialect->objects` derives the
    ;; id from the entry, and `objects->dialect` reads the entry back and
    ;; ignores the id entirely. So an action whose id and entry name different
    ;; endpoints does not store a contradiction — it stores the *entry*, and
    ;; the link silently moves to endpoints the caller never asked for.
    (and (contains? #{:create :update} action)
         (= :link type)
         (map? entry)
         (or (contains? entry :from) (contains? entry :to))
         (not= [(:from entry) (:to entry)] id))
    (conj (f/make :pool.action/malformed :error []
                  "a link's :from/:to must be exactly the endpoint pair it is addressed by"
                  :expected (f/redact id)
                  :got (f/redact [(:from entry) (:to entry)])
                  :refs ["dialect-v1 §3" "D2.3"]))))

(def ^:private default-keys-of
  "各类型条目上带缺省值的键（缺省表本体在 dialect 词表，单一事实源）。
   mutation 的 `:required?` 住在 `:params` 的值里，单列。"
  {:attribute [:listed?]
   :resolver  [:curated?]
   :link      [:direction]})

(defn- strip-defaults
  "存储点的规范化：值恰等于缺省的键**不落库**。

   reader 已把书写出来的缺省当等价拼法归一（owner 裁定 2026-08-27），但池的
   写面收的是逐条 entry、不经 reader——这里不剥，`:listed? true` 就会落进池,
   而 `as-dialect → write-dialect` 的 writer 只认规范形，投影一碰就断言。
   收得宽的门有几扇，存得严的点必须只有一个：就是这里。

   顺带把 merge 语义捋顺了：patch 里写 `:listed? true`（= 缺省）经 merge 后在
   这里被剥掉，效果就是删键——「写出缺省」与「删掉这个键」说的本来就是同一句话。"
  [t entry]
  (let [defaults (:defaults dialect/vocabulary)
        entry (reduce (fn [m k] (if (= (get m k ::absent) (get defaults k))
                                  (dissoc m k) m))
                      entry (get default-keys-of t []))]
    (if (and (= :mutation t) (map? (:params entry)))
      (update entry :params update-vals
              (fn [p] (if (= (:required? p ::absent) (get defaults :required?))
                        (dissoc p :required?) p)))
      entry)))

(defn new-entry
  "§7.3：resolver/mutation/source **整体替换**、entity/attribute 元数据 **merge patch**
   （其余类型的归属见 model/types 的 `:update` 一行）。
   merge patch 里显式 `nil` = 删除该键；值恰为缺省的键不落库（见 strip-defaults）。"
  [t old patch]
  (strip-defaults
   t
   (case (:update (get model/types t))
     :replace patch
     :merge   (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v)))
                         (or old {}) patch))))

(defn update-tx
  "把一个对象写成 tx：新值 upsert 到同一个实体上，**旧值里多出来的字段逐条 retract**。
   不用 retractEntity 再重建：那会换掉 eid，而 eid 的稳定性是 `d/with` 之后
   还认得出「这是同一个对象」的前提。

   Public because `impl/migrate` writes objects too (the D2.29 pool migration
   rewrites a collection attribute into a join one). «Fields the new value does
   not carry are retracted» has to mean the same thing on both paths — a second
   copy is how `:pool.attribute/collection?` would survive one of them."
  [eid old-ent obj revision]
  (let [tx      (dissoc (schema/object->tx obj revision) :db/id)
        keep-ks (set (keys tx))
        drop-ks (remove #(or (contains? keep-ks %) (= :db/id %))
                        (keys old-ent))]
    (into [(assoc tx :db/id eid)]
          (for [k (sort-by str drop-ks)] [:db/retract eid k (get old-ent k)]))))

(defn object-tx
  "一个对象的写入 tx（create 与 update 各一种形状）。`confirm_candidates`
   与本 ns 共用它——签字派生的写入与作者直写的写入**落到同一条路上**，
   否则「签字写出来的东西」与「execute_action 写出来的东西」会长得不一样。"
  [db t id entry revision workspace]
  (let [cur (store/find-object db (model/object-key t id))
        ;; D3.22: the stamp travels with the object on both branches. On an
        ;; update it MUST — `update-tx` retracts every field the new value does
        ;; not carry, and an object that lost its stamp is one nobody can write
        ;; again.
        obj {:pool/type t :pool/id id :pool/entry entry :pool/workspace workspace}]
    (if cur
      (update-tx (:db/id cur) cur obj revision)
      [(schema/object->tx obj revision)])))

(defn- dialect-errors
  "把一个快照拼回方言交 B0，取其中的 error。

   `pr-str` 而不是 `write-dialect`：writer 是产出规范形的，不是第二道校验门
   （它对非法输入抛断言）；判合不合法是 reader 的活。

   `workspace` scopes the snapshot (D3.22); the one-arity form (nil) is the
   whole pool."
  ([db] (dialect-errors db nil))
  ([db workspace]
  (let [model (model/objects->dialect (if workspace
                                        (store/all-objects db workspace)
                                        (store/all-objects db)))
        {:keys [findings]} (dialect/read-dialect
                            (binding [*print-namespace-maps* false] (pr-str model)))]
    (vec (filter #(= :error (:severity %)) findings)))))

(defn finding-key
  "两次 B0 运行之间比对同一条 finding 的键：**`[:code :path :message]`**。

   为什么是这三样（B5a.3 / graph README 裁定 ⑤ 要求写明理由）：

   - `:code` 是「哪一条判定」，`:path` 是「说的是哪个对象/哪个键」——两者合起来
     就是这条拒绝的身份，而这正是差集要问的问题：**这条毛病，写之前是不是就在**;
   - 加 `:message` 是因为同一个 `[code path]` 上可以并存好几条**不同**的毛病
     （同一个 resolver 的两个 `:output` 各缺一个属性）。少了它，写之前一条、
     写之后两条会被当成「都是老的」，**新引入的那条就被静默放过**——而放过一条
     本该拒绝的写，比多报一条严重得多；
   - **不取 `:expected` / `:got`**：它们带计数与被截断的值（`:got {:error-count n}`
     这类），会随一次**无关**的写而变动；把它们算进键，等于让一条本来就在的脏
     因为计数变了而被判成「你引入的」——那正好是裁定 ⑤ 要消灭的误伤。

   两次运行的 finding 都由同一个 reader 从同一份形状产出，所以同一条毛病的这三个
   字段逐字节相同——键的稳定性不靠约定，靠 reader 是确定的。"
  [f]
  [(:code f) (:path f) (:message f)])

(defn diff-findings
  "多重集差集：`after` 里**这次写引入的**那些（裁定 ⑤ 第 3 步）。

   用多重集而不是集合：同一个键出现两次（同一条判定、同一位置、同一句话）时,
   写前一条、写后两条应当报出**一条**新的，而不是零条。"
  [before after]
  (loop [[f & more] (seq after)
         budget     (frequencies (map finding-key before))
         out        []]
    (if (nil? f)
      out
      (let [k (finding-key f)]
        (if (pos? (get budget k 0))
          (recur more (update budget k dec) out)
          (recur more budget (conj out f)))))))

(defn speculative-findings
  "裁定 ②（投机事务过 B0）+ **graph README 裁定 ⑤（差集）**。

   B4.1 原本把投机事务之后的**整池**拼回方言交 B0，任何一条 error 都拒绝。病在
   这里：**池里任何一处不干净，所有人的写都被拒**，而报出来的是「你的模型不合法」。
   B5a 让池里同时住着多个 workspace 的地盘之后，那句话指向的往往是**别人的**对象。

   所以判两次、做差集：

   1. 写之前对同一范围跑一次 B0（`db-before`），记下**已有**的 error；
   2. 投机事务之后再跑一次（`db-after`）；
   3. **只有这次写引入的新 error 才拒绝**（`:errors`）；
   4. 已经存在、这次写之后**仍在**的脏当 `:warning` 随行报出（`:warnings`）——
      它是真的，但不是这次写的责任，也不该由这次写来偿还。

   **范围是本 workspace**（D3.22）：D3.17 把 workspace 定为封闭世界之后跨
   workspace 再没有不变量可判，而整池判会让 A 的脏挡住 B 的写——正是上面那段
   描述的病。不给 `workspace` 时仍判整池（迁移、导入这类真正跨 workspace 的
   场合）。

   返回 `{:errors [新引入的…] :warnings [已存在的…]}`。
   **B4.4 起也给 `confirm_candidates` 用**（那条通道一次写好几个对象，但要判的
   是同一件事）。"
  ([db-before db-after t id] (speculative-findings db-before db-after t id nil))
  ([db-before db-after t id workspace]
  (let [before (dialect-errors db-before workspace)
        after  (dialect-errors db-after workspace)
        new    (diff-findings before after)
        ;; 随行报出的是**这次写之后仍然在**的那些（`after` 减去新引入的），
        ;; 不是 `db-before` 那一份：这次写若顺手修好了一条，报它就是假的。
        stale  (diff-findings new after)]
    {:errors
     (when (seq new)
       (into [(f/make :pool.dialect/rejected :error (path t id)
                      (str "this write introduces " (count new)
                           " new dialect error finding(s); they follow. "
                           "Findings that were already in the pool before this write are "
                           "reported as warnings, not as this write's fault")
                      :got {:error-count (count new)}
                      :refs ["dialect-v1 §7" "B4 README 裁定 ②"
                             "studio/graph README 裁定 ⑤"])]
             new))
     :warnings
     (when (seq stale)
       (into [(f/make :pool.dialect/pre-existing :warning (path t id)
                      (str "the pool already carried " (count stale)
                           " dialect error finding(s) before this write; they are reported "
                           "as warnings and did not block it")
                      :got {:error-count (count stale)}
                      :refs ["studio/graph README 裁定 ⑤"])]
             (mapv #(assoc % :severity :warning) stale)))})))

(defn prepare
  "Everything `execute` decides **before** it writes: the validation errors,
   and — when there are none — the transaction and the db as it would be after
   it. Read-only.

   `execute` runs through this, so a preview of an action and the write of that
   action can never come to differ about what the action means. That is the
   whole reason it is a function rather than a copy."
  [db {:keys [action type id entry expected_revision workspace]}]
  (let [k    (model/object-key type id)
        cur  (store/find-object db k)
        rev  (:pool.object/revision cur)
        ;; D3.22. Absent on `cur` means this pool has not been through
        ;; `impl/migrate`'s stamp pass — `open-pool!` runs it, so by the time a
        ;; write arrives every existing object carries one.
        owner (:pool.object/workspace cur)
        errs
        (cond
          (and (= :create action) cur)
          [(f/make :pool.action/id-taken :error (path type id)
                   "an object with this id already exists; there is no public upsert"
                   :got {:revision rev} :refs ["§7.3"])]

          (and (not= :create action) (nil? cur))
          [(f/make :pool.action/unknown-entity :error (path type id)
                   "no such object; there is no public upsert"
                   :refs ["§7.3"])]

          (and (= :create action) (not= 0 expected_revision))
          [(f/make :pool.conflict/revision-mismatch :error (path type id)
                   "on :create the object must not exist yet, so expected_revision must be 0"
                   :expected 0 :got expected_revision :refs ["D3.5"])]

          (and (not= :create action) (not= rev expected_revision))
          [(f/make :pool.conflict/revision-mismatch :error (path type id)
                   "the object has moved on since this revision was read"
                   :expected expected_revision :got rev :refs ["D3.5" "§7.3"])]

          ;; D3.22: an existing object belongs to the workspace stamped on it,
          ;; and to no other. The owner's NAME is in the refusal — the caller
          ;; has to be told which workspace to go and ask — but not one byte of
          ;; the entry (finding-v1 §5; a source entry holds `:jdbc_url`).
          (and (not= :create action) (not= workspace owner))
          [(f/make :pool.workspace/mismatch :error (path type id)
                   "this object belongs to another workspace"
                   :expected owner :got workspace
                   :refs ["D3.17" "D3.22"])])]
    (if (seq errs)
      {:findings (f/sort-findings errs)}
      (let [refs  (when (= :delete action)
                    (model/referrers (store/all-objects db) type id))]
        (cond
          (seq refs)
          {:findings [(f/make :pool.reference/inbound :error (path type id)
                              (str "still referenced by " (count refs)
                                   " object(s); delete or repoint them first")
                              :got refs :refs ["D3.5"])]}

          :else
          (let [next-rev (if (= :create action) 1 (inc rev))
                obj      {:pool/type type :pool/id id
                          ;; D3.22. On :create this stamps the object; on
                          ;; :update it restates what is already stored (equal
                          ;; by the check above), which it MUST — `update-tx`
                          ;; retracts every field the new value does not carry,
                          ;; and a silently unstamped object is one nobody can
                          ;; write again.
                          :pool/workspace workspace
                          :pool/entry (new-entry type (:pool/entry
                                                       (when cur (schema/entity->object cur)))
                                                 entry)}
                tx (case action
                     :create [(schema/object->tx obj next-rev)]
                     :update (update-tx (:db/id cur) cur obj next-rev)
                     :delete [[:db/retractEntity (:db/id cur)]])]
            {:tx tx :db-after (:db-after (d/with db tx)) :revision next-rev
             :findings []}))))))

(defn execute
  "一次 action。返回 `{:revision n :mutation-id … :mutations [记录] :findings []}`
   （已提交）或 `{:findings [错误…]}`（一个字节都没写）。

   `prov` 是**可信出处**（8.4 P1b，`impl/audit.clj`）：`{:entrypoint :token-id
   :actor :at}`，由服务端构造。它是**另一个参数**而不是 action 的一个键——
   action 的键面封闭，所以调用方把 `:entrypoint` 写进 action 里得到的是
   `:pool.action/malformed`：伪造身份不是被过滤掉，而是**寻址不到**。
   不给 `prov` 也照样留记录（entrypoint 记 `:unattributed`），理由见 audit 裁定 ②。"
  ([conn a] (execute conn a nil))
  ([conn {:keys [action type id] :as a} prov]
   (let [shape (if (map? a)
                 (into (check-shape a) (audit/check prov))
                 [(f/make :pool.action/malformed :error [] "action must be a map"
                          :got (f/redact a))])]
     (if (seq shape)
       {:findings (f/sort-findings shape)}
       ;; 读—判—写在一把锁里：`d/with` 与 `d/transact` 之间若被别人插进来，
       ;; revision 检查判的就是一个已经过期的事实——而那正是这道闸要挡的东西。
       (locking conn
         (let [db (d/db conn)
               before (:pool.object/revision (store/find-object db (model/object-key type id)))
               {:keys [tx db-after revision findings]} (prepare db a)]
           (if (seq findings)
             {:findings findings}
             (let [{:keys [errors warnings]} (speculative-findings db db-after type id
                                                                   (:workspace a))]
               (if (seq errors)
                 {:findings (vec errors)}
                 ;; 审计与对象写在**同一次** transact 里：没有哪种顺序能让一个
                 ;; 落地而另一个没有（audit 裁定 ③）。
                 (let [audit-tx (audit/tx prov (count (store/all-mutations db))
                                          [{:action action :type type :id id
                                            :revision-before (or before 0)
                                            :revision-after (when-not (= :delete action) revision)}])]
                   (d/transact conn (into (vec tx) audit-tx))
                   ;; The audit record's id travels back with the write (E3):
                   ;; the assembly layer files one unified-audit event per
                   ;; record, and it must name the row rather than look for
                   ;; the nearest one by time.
                   (let [[m] (audit/summaries audit-tx)]
                     {:revision (when-not (= :delete action) revision)
                      :mutation-id (:mutation-id m)
                      :mutations [m]
                      :findings (vec warnings)})))))))))))
