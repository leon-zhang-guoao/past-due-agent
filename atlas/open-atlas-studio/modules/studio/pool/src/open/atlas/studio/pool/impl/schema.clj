(ns open.atlas.studio.pool.impl.schema
  "Datahike schema（由 impl.model 的类型表机械推出）+ 对象 ↔ 实体的双向映射。

   schema **不手写**：一份类型表推出全部 `:db/ident`，于是「属性名与 dialect-v1
   的条目键一一对应」是构造出来的事实，不是要靠人核对的约定。手写第二份的那天
   就是两份开始分家的那天（CLAUDE.md 关于 `redact` 与档位词表的那段）。

   **这里不做任何结构判定**（裁定 ②）。`:db/valueType` 看着像校验，它管的是
   「这个值能不能存」，不是「这个值合不合方言」——后者一律回落到
   `dialect/read-dialect`。所以 EDN 家族的字段一律 `:db.type/string`：池不替
   B0 判 `:values` 的形状。"
  (:require [open.atlas.studio.pool.impl.model :as model]))

(def ^:private value-type
  {:string  :db.type/string
   :keyword :db.type/keyword
   :boolean :db.type/boolean
   :edn     :db.type/string})

(defn field-ident
  "字段 → `:db/ident`。命名规则：`:pool.<类型>/<条目键>`，条目键**原样**
   （含 `?` 与 snake_case 的 `:result_type`）——一一对应的前提是不改名。"
  [t k]
  (keyword (str "pool." (name t)) (name k)))

(def core-schema
  "每个对象都有的五条。

   D2.29 之前还有第五条 `:pool.child/parent`：navigation 是住在 namespace 条目
   里的子对象，靠这条 ref 指回父实体。joins 变成普通属性之后池里再没有子对象,
   这条边随之删去——留着一条没有任何对象写得出的 schema 属性，等于告诉读的人
   池里还有两层结构。

   D3.22 (owner ruling 2026-09-05) added a fifth of its own,
   `:pool.object/workspace`: a pool object's owning workspace is a **stored**
   fact — the workspace whose editor created it — never one derived from its
   name. D3.17 retired the global namespace claim table the old derivation
   read, so after it there was nothing left to derive an owner from."
  [{:db/ident       :pool.object/key
    ;; 一个标量同时编码类型与 id，理由见 model/object-key。
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :pool.object/type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   ;; id 的形状按类型不同（kw / 字符串 / 两段向量），存 EDN 字符串是唯一
   ;; 一种不按类型分叉的存法。
   {:db/ident :pool.object/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   ;; 对象级 revision（裁定 ④：不是全局单调计数）。
   {:db/ident :pool.object/revision :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   ;; D3.22: the owning workspace, stamped by the write that created the object
   ;; and never derived. Absent only on a pool that has not been through
   ;; `impl/migrate`'s stamp-workspace pass — `open-pool!` runs it.
   {:db/ident :pool.object/workspace :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])

(def confirmation-schema
  "**确认记录**（B4.4 裁定 ②）：池里一种**独立的实体**，与九个对象类型不共用
   `:pool.object/key`。这条「不共用」是整条纪律的落点——`store/all-objects` 查的是
   `:pool.object/key`，而 `as-dialect`、投机校验、导出的对象清单全部经由它，于是
   「确认记录不进模型」不是一句要靠人遵守的话，而是**查不到**。

   混进模型的代价是具体的：同一份模型会因为「谁签的」不同而产出不同的
   content-hash，而制品的身份必须只由模型决定（D4.3）。

   **不可变**在这里同样是结构而不是纪律：`execute_action` 按 `model/known-type?`
   收类型，`:confirmation` 不在那张表里，于是没有任何写面**寻址得到**它；
   `confirm_candidates` 自己遇到已存在的记录一律拒绝（`already-confirmed`）。
   要改一条确认记录，得先改代码——那正是 D1.8「把拒绝做成结构」。"
  [{:db/ident       :pool.confirmation/key
    ;; `[kind candidate-key]` 的打印形。同一条候选至多一条记录（unique/identity）,
    ;; 「重复签同一条候选」于是在存储层也是一次撞车，不只靠上层那句 if。
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :pool.confirmation/kind       :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :pool.confirmation/candidate  :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :pool.confirmation/decision   :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   ;; 产出的对象 + **前后 revision**，一条 EDN：`[{:object [类型 id]
   ;; :revision-before n :revision-after n} …]`。不存在写 0，与 `:create` 的
   ;; `expected_revision` 取 0 同一套规则（§7.3），调用方只记一套。
   {:db/ident :pool.confirmation/produced   :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   ;; **入口**：principles「存储与同步」点名要的那一项——「才答得出这个 revision
   ;; 里哪些改动来自 AI」。缺省不给不行：它是记录存在的理由之一。
   {:db/ident :pool.confirmation/entrypoint :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :pool.confirmation/note       :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   ;; 8.4 P1b：入口之外再记**谁**。`entrypoint` 答得出「来自 agent」，答不出
   ;; 「经谁授权」——而撤销一把钥匙之后要回答的正是后者。三者皆可缺席，理由同
   ;; mutation-schema 那一段。
   {:db/ident :pool.confirmation/token-id    :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :pool.confirmation/actor       :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :pool.confirmation/at          :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}])

(def mutation-schema
  "**变更审计**（8.4 P1b，`impl/audit.clj`）：又一个**独立的实体族**，与九个
   对象类型、与确认记录三者互不共用寻址键。

   与确认记录同一条理由（confirmation-schema 上面那段）：`store/all-objects` 查
   `:pool.object/key`，审计记录没有那个属性，于是「审计不进模型」是**查不到**，
   不是一句要记得遵守的话。制品的 content-hash 因此不随「谁改的」而变（D4.3）。

   不可变同样是结构：`execute_action` 按 `model/known-type?` 收类型，`:mutation`
   不在那张表里，没有任何写面**寻址得到**它。

   `:pool.mutation/token-id` 与 `:actor` 是**可缺席**的：一次 UI 写有 actor 没有
   token，一次 MCP 写两者都有，一次没有出处的写两者都没有——三种情况在这里长得
   不一样，正是审计要能分开的三件事。"
  [{:db/ident       :pool.mutation/id
    ;; `<写序>#<序号>`：由写入本身推出，不是 UUID 也不是时钟——池的纪律是同样的
    ;; 写产出同样的字节（`export/export-str`），随机 id 会让审计成为导出里唯一
    ;; 永远比不相等的那一段。
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :pool.mutation/entrypoint      :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :pool.mutation/token-id        :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :pool.mutation/actor           :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :pool.mutation/at              :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :pool.mutation/action          :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   ;; `[类型 id]` 的 EDN 形，与 `:pool.object/id` 同一种存法（id 的形状按类型分叉，
   ;; 存 EDN 是唯一不跟着分叉的存法）。
   {:db/ident :pool.mutation/object          :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :pool.mutation/revision-before :db/valueType :db.type/long    :db/cardinality :db.cardinality/one}
   {:db/ident :pool.mutation/revision-after  :db/valueType :db.type/long    :db/cardinality :db.cardinality/one}])

(def schema
  "完整 schema：核心四条 + 类型表推出的每一个条目键 + source 的连接器键整块。"
  (into (into (into core-schema confirmation-schema) mutation-schema)
        (for [t model/type-order
              :let [{:keys [fields open-keys]} (get model/types t)]
              [k kind] (concat (sort-by str fields)
                               (when open-keys [[open-keys :edn]]))]
          {:db/ident       (field-ident t k)
           :db/valueType   (value-type kind)
           :db/cardinality :db.cardinality/one})))

;; ---------------------------------------------------------------------------
;; 对象 → 事务数据
;; ---------------------------------------------------------------------------

(defn object->tx
  "对象 + revision → 一张 tx map。

   `:pool/workspace` (D3.22) rides along when the object carries one. It is
   written like any other datom rather than through a separate path, so
   `update-tx`'s «fields the new value does not carry are retracted» covers it
   too — which is why every caller that updates an object must hand back the
   workspace it already had."
  [{:pool/keys [type id entry workspace]} revision]
  (let [{:keys [known open]} (model/split-entry type entry)
        open-attr (model/open-key-attr type)]
    (cond-> (into {:db/id                (str "obj-" (model/object-key type id))
                   :pool.object/key      (model/object-key type id)
                   :pool.object/type     type
                   :pool.object/id       (model/encode-edn id)
                   :pool.object/revision revision}
                  (for [[k v] known
                        :when (some? v)]
                    [(field-ident type k)
                     (if (= :edn (model/field-kind type k)) (model/encode-edn v) v)]))
      workspace                  (assoc :pool.object/workspace workspace)
      (and open-attr (seq open)) (assoc (field-ident type open-attr) (model/encode-edn open)))))

;; ---------------------------------------------------------------------------
;; 实体 → 对象
;; ---------------------------------------------------------------------------

(defn entity->object
  "一张 pull 出来的实体 map → {:pool/type :pool/id :pool/entry :pool/revision
   :pool/workspace}。"
  [m]
  (let [t     (:pool.object/type m)
        id    (model/decode-edn (:pool.object/id m))
        fs    (:fields (get model/types t))
        open  (model/open-key-attr t)
        entry (into {}
                    (for [[k kind] fs
                          :let [v (get m (field-ident t k))]
                          :when (some? v)]
                      [k (if (= :edn kind) (model/decode-edn v) v)]))]
    (cond-> {:pool/type     t
             :pool/id       id
             :pool/revision (:pool.object/revision m)
             :pool/entry    (merge entry
                                   (when open
                                     (some-> (get m (field-ident t open))
                                             model/decode-edn)))}
      ;; D3.22: absent on a pool that has not been stamped yet, so the key is
      ;; added only when it is stored — a nil-valued key would make «not yet
      ;; stamped» read as a workspace.
      (:pool.object/workspace m) (assoc :pool/workspace (:pool.object/workspace m)))))
