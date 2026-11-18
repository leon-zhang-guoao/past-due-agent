(ns open.atlas.studio.graph.impl.schema
  "治理数据在池里的 Datahike schema（README 裁定 ①）。

   **五种独立实体**，每种一个**自己的** `:db.unique/identity` 键，
   **一律不共用 `:pool.object/key`**：

   | 实体 | unique 键 | 出处 |
   |---|---|---|
   | workspace | `:graph.workspace/name` | D3.3 |
   | graph | `:graph.graph/qualified-name` | D3.10（两段限定名） |
   | 授权 | `:graph.grant/key` | D3.14 |

   D3.22 (owner 2026-09-05) removed two of these: the global namespace claim
   table (`:graph.claim/*`, retired with the territory rules by D3.17) and the
   in-workspace trespass / unclaimed mirror (`:graph.trespass/*` /
   `:graph.unclaimed/*`, D3.4).  Ownership is a stored column on the pool
   object, so a write cannot land outside its workspace and the mirror had
   nothing left to reflect.  Attributes already on disk in an existing pool
   stay readable — Datahike keeps an installed ident — which is what the
   one-shot D3.22 stamping migration reads (`pool/impl/migrate.clj`).

   **「不共用」是整条纪律的落点**，与 B4.4 的确认记录逐字同理：`pool/objects`、
   `as-dialect`、投机校验、导出的对象清单全部经由 `:pool.object/key` 那一条查询,
   于是治理数据**查不到**——「不进模型」不是一句要靠人遵守的话。混进模型的代价很
   具体：同一份模型会因为「谁 own 它」不同而产出不同的 content-hash，而制品的身份
   必须只由模型决定（D4.3）。

   schema 由本模块**自己**装（`install!`），不去改 pool 的那一份：pool 不认识
   workspace（B4 README 裁定 ⑤），让它替 graph 声明 schema 等于让下层认识上层。"
  (:require [clojure.edn :as edn]
            [datahike.api :as d]))

(defn encode-edn
  "值 → 规范 EDN 字符串。`*print-namespace-maps* false` 与池同一套，
   否则同一份数据在两处打印出不同的字节。"
  [v]
  (binding [*print-namespace-maps* false] (pr-str v)))

(defn decode-edn [s] (when s (edn/read-string s)))

(def schema
  [{:db/ident       :graph.workspace/name
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   ;; 成员整块存 EDN：`[{:principal {:issuer … :subject …} :role :owner} …]`。
   ;; 主体是 D5.10 的不可变二元组——整块存意味着**改内容契约不用动
   ;; Datahike 的 value type**，只改校验（impl.registry）。
   ;; 角色只有 owner / editor（D3.3 明确不设 reviewer、不设审批流）。
   {:db/ident :graph.workspace/members :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   ;; 打开 Studio 时落在哪张图上（owner 裁定 2026-09-01，D3.21）。
   ;;
   ;; 与 D3.20 是配套的两层：那一条说一张图**打开时跑什么**，这一条说**打开时
   ;; 是哪张图**。发一个 demo 的人要能决定新用户第一眼看到的是哪一张；在此之前
   ;; 落地是按限定名字典序，发布者说不上话。
   ;;
   ;; 存限定名而不是 UUID：这是一条**偏好**，指向一张不存在的图时回退到字典序
   ;; 即可（见 registry/default-graph）。存 UUID 会让「图被删了」变成一个要迁移
   ;; 的悬空引用，而这条设定不值那个代价。
   ;;
   ;; 不设就是键不存在，与 D3.20 同一条纪律。
   {:db/ident :graph.workspace/default-graph :db/valueType :db.type/string :db/cardinality :db.cardinality/one}


   {:db/ident       :graph.graph/qualified-name
    ;; D3.10：全局身份是 `<workspace>/<graph>`。裸名不进任何持久化标识,
    ;; 所以 unique 的是限定名，`:graph.graph/name` 只是它的可查分解。
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :graph.graph/workspace  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :graph.graph/name       :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :graph.graph/display-name :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   ;; Qualified name remains the human/addressable name used by pinned version
   ;; references.  UUID is the immutable instance-wide graph identity used by
   ;; UI routes, runtime-cache keys, and audit records; a future rename never
   ;; changes it.
   {:db/ident :graph.graph/id          :db/valueType :db.type/string :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :graph.graph/state       :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   ;; Membership makes every team graph visible.  This separate per-graph
   ;; access list only records explicit viewer/editor grants; it never hides a
   ;; graph from another member of the same workspace.
   {:db/ident :graph.graph/access      :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   ;; 菜谱是**一张对象清单**（D3.19）：按类型分组，每组是限定身份的集合。
   ;; 整块存 EDN，理由与 workspace members 同：改内容契约不用动 value type。
   {:db/ident :graph.graph/members    :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   ;; 创建时刻（D3.18）。本模块不读时钟——调用方传入，与 `cut-version!` 的
   ;; `:cut-at` 同一条纪律（D4.10-a）。
   {:db/ident :graph.graph/created-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   ;; Query Studio 开场那一条查询（owner 裁定 2026-09-01）。
   ;;
   ;; **住在图上，不在本机偏好里**：发布一个 demo 的人要连「打开就能按 Run 的
   ;; 那一条」一起发出去，而本机文件不随制品旅行——新装的机器上就只剩一个空
   ;; 编辑器。在这里它跟着 recipe 一起走。
   ;;
   ;; 整块存 EDN（`{:query … :entity …}`），理由与 `members`、`access` 同：
   ;; 以后要往里加东西（比如一段说明）不用动 value type。查询与 entity 是**两个**
   ;; 值：一条需要入口值才跑得通的默认查询，只存查询等于存了个一按就错的东西
   ;; ——那正是 2026-09-01 之前硬编码在 UI 里的那一份的形状。
   ;;
   ;; 没设就是**键不存在**（见 store/->graph 的 cond->），不是空串：图上没有这
   ;; 条设定与「设成了空」是两件事，而 Lab 对前者的回答是空编辑器。
   {:db/ident :graph.graph/default-query :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   ;; The RELEASE name a cut version is published under — `open-atlas/dvd`
   ;; (doc 104 §8.17.7 D, owner ruling 2026-09-06 H item 2). Stored as written,
   ;; not derived: the qualified name's workspace segment is the login
   ;; workspace, which for a personal one is an email address, and a package
   ;; name must not carry a person.
   ;;
   ;; Unset means the KEY IS ABSENT (see store/->graph), not an empty string:
   ;; "this graph has no release name" is what selects the DEFAULT name
   ;; (`registry/package-name-for`: `<workspace>/<graph>`, or `open-atlas/<graph>`
   ;; for a personal workspace — owner ruling 2026-10-01).
   {:db/ident :graph.graph/package-name :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   ;; Saved queries, the DRAFT set (proposal studio-saved-queries §3, owner
   ;; ruling 2026-10-10). A vector of saved-query maps, stored whole as EDN for
   ;; the reason `members` and `default-query` are: the shape can grow without
   ;; touching the value type. Absent = none — every graph made before this
   ;; attribute existed reads as having no saved queries, with no migration.
   ;;
   ;; On the graph, not in the model: a saved query is not a dialect object, so
   ;; it never enters `content.edn` and never changes a content-hash (the
   ;; artifact's key set is closed, artifact-v1 §1).
   {:db/ident :graph.graph/saved-queries :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   ;; The set PINNED to one published version, written when that version is
   ;; published and never changed afterwards. Keyed by the graph's UUID (not
   ;; its qualified name, which a rename changes) and the version number, so a
   ;; rollback serves exactly the set that version was published with. A
   ;; version published before saved queries existed has no record: none.
   {:db/ident       :graph.pinned-queries/key
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :graph.pinned-queries/queries :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   ;; A data-plane request someone chose to keep for debugging (proposal
   ;; studio-saved-queries §4.1b): pinned from the in-memory recent list, or by
   ;; a capture rule. The recent list itself never touches the pool; only what
   ;; a person pins does, and only for a while (`expires-at`). The entry is the
   ;; recorded request as EDN — EQL, parameters, caller, outcome; never result
   ;; data.
   {:db/ident       :graph.pinned-request/id
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :graph.pinned-request/workspace :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :graph.pinned-request/graph-id  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :graph.pinned-request/entry     :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :graph.pinned-request/pinned-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :graph.pinned-request/pinned-by :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :graph.pinned-request/expires-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   ;; **已废弃，仅供迁移读取**：D3.19 前的菜谱是 selector 集合（D3.11）,
   ;; D3.17 前还带跨 graph 引用清单（D3.14）。两者都不再被写入；留在 schema 里
   ;; 是为了让 `migrate-recipes!` 能读出**旧池**里的既有值——把它们从这张表里
   ;; 删掉，旧池的数据就变成读不出来的字节，而迁移正需要读它们。
   {:db/ident :graph.graph/selectors  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :graph.graph/references :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   {:db/ident       :graph.grant/key
    ;; `[被授权方 graph 限定名]` 的打印形：同一对至多一条授权。
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   ;; 被授权方：workspace 名字符串，或 `:public`（对全体 workspace 开放，D3.14）。
   ;; 两种形状存同一个字段，故存 EDN 打印形。
   {:db/ident :graph.grant/grantee :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :graph.grant/graph   :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :graph.grant/ceiling :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}])

(defn install!
  "把治理 schema 装进一个已开的池连接。幂等（Datahike 的 `:db/ident` 是 upsert）。"
  [conn]
  (d/transact conn schema)
  conn)
