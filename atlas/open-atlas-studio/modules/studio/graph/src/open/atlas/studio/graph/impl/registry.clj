(ns open.atlas.studio.graph.impl.registry
  "workspace 登记、namespace 地盘登记、graph 登记与限定名（D3.3 / D3.9 / D3.10 / D3.11）。

   三件事写在一个 ns 里，因为它们是**同一张登记网**：graph 的菜谱 selector 必须落在
   本 workspace 已登记的地盘里（D3.9 / scope projection v1 第 1、2 步），而地盘只能属于
   一个已登记的 workspace。分成三个 ns 会让这两条判定各自去 require 对方的表。

   拒绝一律 finding-v1，**点名先登记的那一个**——「这个 namespace 被占了」而不说被谁占,
   等于让作者去打听（D3.14 那句「目的就是让人知道该找谁 request」的反面）。"
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.graph.impl.finding :as f]
            [open.atlas.studio.graph.impl.selector :as sel]
            [open.atlas.studio.graph.impl.store :as store]))

(def roles
  "Workspace membership roles.  An `:editor` modifies the workspace model and
   existing graph recipes; an `:owner` alone creates/archives graphs and
   manages workspace membership."
  #{:owner :member :editor})

(def graph-access-roles
  "Per-graph rights — **`:editor` and nothing else** (owner ruling 2026-08-30).

   `:viewer` used to be here, described as «explicit documentation/audit data».
   It could not change a decision: `graph-access-role` consults an explicit
   grant only for a plain workspace member, and a plain member already resolves
   to `:viewer`, so granting it was `(or :viewer :viewer)`. A grantable value
   that cannot alter any answer is not documentation — it is a control that
   reads as policy and enforces nothing, and it was the default in the Builder's
   own picker.

   Existing `:viewer` rows still READ back: this set gates writes only, and such
   a row resolves to exactly what its holder had without it."
  #{:editor})

;; ---------------------------------------------------------------------------
;; workspace
;; ---------------------------------------------------------------------------

(def ^:private workspace-keys #{:name :members})

(defn- name-ok? [s] (and (string? s) (seq s) (not (str/includes? s "/"))))

(defn principal?
  "D5.10 的不可变主体主键：`{:issuer … :subject …}`，两段都非空字符串。

   **邮箱与显示名永远不是主键**——人会改名、换邮箱，离职后邮箱被回收给新人,
   而审计必须在这些之后仍指向同一个人。要留它们就放 `:attributes`，
   那里的东西**不参与授权匹配**。"
  [p]
  (boolean (and (map? p)
                (= #{:issuer :subject} (set (keys p)))
                (string? (:issuer p)) (seq (:issuer p))
                (string? (:subject p)) (seq (:subject p)))))

(defn- member-ok? [m]
  (and (map? m)
       (empty? (remove #{:principal :role :attributes} (keys m)))
       (principal? (:principal m))
       (contains? roles (:role m))
       (or (nil? (:attributes m)) (map? (:attributes m)))))

(defn- members-ok? [members]
  (and (vector? members) (every? member-ok? members)))

(defn members-shape-ok?
  "成员形状是否合法。**导入也要判这一条**（见 impl.export）：形状是内容契约,
   不是「当初那次登记合不合法」的判定。"
  [members]
  (members-ok? members))

(defn- duplicate-principals
  "同一 workspace 内重复出现的主体。**issuer 与 subject 都参与相等判断**——
   只按 subject 比，两个 IdP 下同名的两个人就成了同一个人。"
  [members]
  (when (members-ok? members)
    (let [dupes (->> members (map :principal) frequencies
                     (keep (fn [[p n]] (when (> n 1) p))))]
      (when (seq dupes) (vec (sort-by pr-str dupes))))))

(defn- workspace-shape [{:keys [name members] :as req}]
  (cond-> []
    (seq (set/difference (set (keys req)) workspace-keys))
    (conj (f/make :workspace.registry/malformed :error []
                  "register-workspace key set is closed"
                  :expected (vec (sort (map str workspace-keys)))
                  :got (vec (sort (map str (set/difference (set (keys req)) workspace-keys))))))

    (not (name-ok? name))
    (conj (f/make :workspace.registry/malformed :error [:name]
                  "workspace name must be a non-empty string without '/' — '/' is the separator of the qualified graph name"
                  :got (f/redact name) :refs ["D3.10"]))

    (not (members-ok? members))
    (conj (f/make :workspace.registry/malformed :error [:members]
                  (str "members must be a vector of "
                       "{:principal {:issuer … :subject …} :role :owner|:member} "
                       "with an optional :attributes map — the principal is the immutable "
                       "(issuer, subject) pair (D5.10); an email or display name is an "
                       "attribute and never takes part in authorization matching")
                  :expected (vec (sort (map str roles)))
                  :got (f/redact members) :refs ["D5.10" "D3.3"]))

    (duplicate-principals members)
    (conj (f/make :workspace.registry/malformed :error [:members]
                  (str "the same principal appears more than once in this workspace; one "
                       "subject holding both :owner and :editor makes \"is this person an "
                       "owner\" depend on which row is read first")
                  :got (duplicate-principals members) :refs ["D5.10" "D3.3"]))))

(defn register-workspace!
  [conn {:keys [name] :as req}]
  (let [shape (workspace-shape req)]
    (cond
      (seq shape) {:findings (f/sort-findings shape)}

      (store/find-workspace (d/db conn) name)
      {:findings [(f/make :workspace.registry/name-taken :error [:name]
                          "a workspace with this name is already registered"
                          :got name)]}

      :else
      (let [ws {:workspace/name name :workspace/members (:members req)}]
        (d/transact conn [(store/workspace-tx ws)])
        {:workspace ws :findings []}))))

(defn workspace-role
  "The member's workspace role, or nil.  This is intentionally independent of
   graph access: every member can discover all team graphs."
  [db workspace principal]
  (some (fn [member]
          (when (= principal (:principal member)) (:role member)))
        (:workspace/members (store/find-workspace db workspace))))

(defn workspace-member?
  [db workspace principal]
  (boolean (workspace-role db workspace principal)))

(defn add-workspace-member!
  "Persist the accepted side of an invitation.  Delivery/acceptance tokens live
   in the identity service; this graph module only accepts a canonical principal
   after that service has authenticated the user.  Re-inviting the same person
   is an idempotent role update, not a duplicate membership."
  [conn workspace member]
  (let [db (d/db conn)
        ws (store/find-workspace db workspace)]
    (cond
      (nil? ws)
      {:findings [(f/make :workspace.registry/unknown :error [:workspace]
                          "no such workspace is registered" :got workspace)]}

      (not (member-ok? member))
      {:findings [(f/make :workspace.registry/malformed :error [:member]
                          "member must carry a canonical principal and workspace role"
                          :got (f/redact member))]}

      :else
      (let [members (vec (sort-by #(pr-str (:principal %))
                                  (conj (vec (remove #(= (:principal member) (:principal %))
                                                     (:workspace/members ws)))
                                        member)))
            ws' (assoc ws :workspace/members members)]
        (d/transact conn [(store/workspace-tx ws')])
        {:workspace ws' :findings []}))))

(defn remove-workspace-member!
  "Remove one member from a workspace.

   **The last owner cannot be removed.** A workspace with no owner can never
   again grant access to itself — membership is only editable by an owner —
   and there is no platform-level repair path, so the workspace would be
   stranded with its graphs inside it. Refusing is the only answer that does
   not destroy something quietly; demote-by-removal has to be an explicit
   \"appoint another owner first\".

   Idempotent: removing someone who is not a member is not an error, because
   the caller's intent — that this principal is not a member — already holds."
  [conn workspace principal]
  (let [db (d/db conn)
        ws (store/find-workspace db workspace)]
    (cond
      (nil? ws)
      {:findings [(f/make :workspace.registry/unknown :error [:workspace]
                          "no such workspace is registered" :got workspace)]}

      (and (= :owner (some (fn [m] (when (= principal (:principal m)) (:role m)))
                           (:workspace/members ws)))
           (= 1 (count (filter #(= :owner (:role %)) (:workspace/members ws)))))
      {:findings [(f/make :workspace.registry/last-owner :error [:member]
                          (str "this is the workspace's only owner; removing them would "
                               "leave nobody able to manage it — appoint another owner first")
                          :got workspace)]}

      :else
      (let [members (vec (remove #(= principal (:principal %)) (:workspace/members ws)))
            ws' (assoc ws :workspace/members members)]
        (d/transact conn [(store/workspace-tx ws')])
        {:workspace ws' :findings []}))))

;; The global namespace claim table is gone (D3.17 retired the territory rules,
;; D3.22 removed the last readers): `claim!` / `owner-of` / `owner-of-object` /
;; `lookup-names` lived here and decided who owned a name.  Ownership is now a
;; stored column on the pool object, so there is nothing left to derive.

;; ---------------------------------------------------------------------------
;; graph 与限定名（D3.10）
;; ---------------------------------------------------------------------------

(defn qualify
  "`<workspace>/<graph>`（D3.10）。**裸名不进任何持久化标识**，所以池里、导出里、
   授权里出现的一律是这个函数的产物。"
  [workspace nm]
  (str workspace "/" nm))

(defn unqualify
  "限定名 → `[workspace 裸名]`，形状不对给 nil。"
  [qn]
  (when (string? qn)
    (let [i (str/index-of (str qn) "/")]
      (when (and i (pos? i) (< (inc i) (count qn)))
        [(subs qn 0 i) (subs qn (inc i))]))))

(def ^:private graph-keys #{:workspace :name :members :created-at :package-name})

(defn package-name-ok?
  "Is this a well-formed RELEASE name — `open-atlas/dvd` (doc 104 §8.17.7 D)?

   Exactly two segments with the same character rules as a graph id's, because
   an installing instance shows it where a qualified name goes and the two must
   not be told apart by their punctuation. Nothing else is allowed in: a name
   that carries a `/` in a segment parses into a third segment somewhere
   downstream, which is how identity stops being unique (D3.10, same reason)."
  [s]
  (boolean (and (string? s) (re-matches #"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+" s))))

(def package-name-expectation
  "The words a `:package-name` refusal shows. One copy, shared by registration
   and by the setter — two wordings for one rule read as two rules."
  "a two-segment release name, e.g. open-atlas/dvd; each segment is letters, digits, '_', '.' or '-'")

(def member-kinds
  "菜谱的八组（scope-projection-v1 §1）。每组一个身份形状判定。

   曾经还有第九组 `:navigations`。D2.29（owner 裁定 2026-09-02）之后 navigation
   不再是一类对象——`:output` 树里的分支是一条 **join 属性**，按 `:attributes`
   点名，所以它没有自己的身份形状可判。

   **只判形状，不判存在**：「这个对象在不在池里」由投影一处回答
   （`:scope/unknown-member`）。在这里再判一次就是第二份实现，而两份「同一件事」
   迟早会分叉——本仓库在 `:batch` 上刚付过这笔账（D1.8）。"
  {:namespaces  #(string? %)
   :attributes  #(qualified-keyword? %)
   :resolvers   #(and (string? %) (seq %))
   :mutations   #(and (string? %) (seq %))
   :links       #(and (vector? %) (= 2 (count %)) (every? qualified-keyword? %))
   :terms       #(and (string? %) (seq %))
   :units       #(and (string? %) (seq %))
   :metrics     #(and (string? %) (seq %))})

(def member-kind-shapes
  "Each group's identity shape, in words — the `:expected` of a members rejection.

   A shape predicate can only answer yes/no; the caller who got «no» still has to
   guess which group and which shape. Saying it here is what makes the rejection
   actionable (finding-v1 §2: name the place, give the expectation)."
  {:namespaces  "namespace name (string)"
   :attributes  "qualified keyword, e.g. :cis.master.part/sku_no"
   :resolvers   "resolver id (non-empty string)"
   :mutations   "mutation id (non-empty string)"
   :links       "[from to], both qualified keywords"
   :terms       "term id (non-empty string)"
   :units       "unit id (non-empty string)"
   :metrics     "metric id (non-empty string)"})

(defn members-findings
  "菜谱形状的拒绝，**逐条点名**（scope-projection-v1 §1）。

   为什么不是一条布尔：这条拒绝原先只给 `:got (f/redact members)`，而 redact 把 map
   降成 `map(N keys)`——**每一种写错都拿回同一句话**，调用方只能换形态硬试。同一个
   文件里 `graph-shape` 的键集拒绝早就给了 `:expected` 与出错的键名，两处同类拒绝
   一处给期望一处不给是漏做（finding-v1 §2）。

   成员 id 明文出现在 `:got`，与 `:scope/unknown-member` 同则：它们是模型对象的名字,
   不是数据。"
  [members]
  (if-not (map? members)
    [(f/make :graph.registry/malformed :error [:members]
             "members must be a map of object-kind → set of identities"
             :expected (vec (sort (map str (keys member-kind-shapes))))
             :got (f/redact members)
             :refs ["scope-projection-v1 §1" "D3.19"])]
    (let [unknown (set/difference (set (keys members)) (set (keys member-kind-shapes)))]
      (f/sort-findings
       (concat
        (when (seq unknown)
          [(f/make :graph.registry/malformed :error [:members]
                   "the member key set is closed"
                   :expected (vec (sort (map str (keys member-kind-shapes))))
                   :got (vec (sort (map str unknown)))
                   :refs ["scope-projection-v1 §1" "D3.19"])])
        (for [[kind xs] members
              :when (contains? member-kind-shapes kind)
              f (cond
                  ;; 集合语义：set / vector / list 都收（EDN 过一趟 HTTP 常回 vector）,
                  ;; map 不是——它带着一个不该有的键，收下就是猜作者的意思。
                  (or (not (coll? xs)) (map? xs))
                  [(f/make :graph.registry/malformed :error [:members kind]
                           "a member group is a set of identities (a vector or list is taken as one)"
                           :expected (str "collection of " (member-kind-shapes kind))
                           :got (f/redact xs)
                           :refs ["scope-projection-v1 §1" "§2.10"])]

                  :else
                  (for [x xs :when (not ((member-kinds kind) x))]
                    (f/make :graph.registry/malformed :error [:members kind]
                            "this member's identity has the wrong shape"
                            :expected (member-kind-shapes kind)
                            :got (pr-str x)
                            :refs ["scope-projection-v1 §1"])))]
          f))))))

(defn graph-members-ok?
  "菜谱形状：键集封闭，每组是**集合语义的集合体**且成员形状正确。缺席的组按空集处理。

   接受 set / vector / list——EDN 走一趟 HTTP 回来常常是 vector，而菜谱是集合语义
   （§2.10：排列不进结果），所以**在这里收敛**而不是要求调用方先转换。
   `normalize-members` 是那次收敛。

   判定与拒绝共用 `members-findings` 一份实现：两份「同一件事」迟早分叉（D1.8）。"
  [members]
  (empty? (members-findings members)))

(defn normalize-members
  "把缺席的组补成空集，并把顺序无关的输入收敛成集合——菜谱是**集合语义**,
   书写顺序不进结果（scope-projection-v1 §2.10）。"
  [members]
  (into {} (for [kind (sort (keys member-kinds))]
             [kind (set (get members kind))])))

(defn- graph-shape [{:keys [workspace name members created-at package-name] :as req}]
  (cond-> []
    (seq (set/difference (set (keys req)) graph-keys))
    (conj (f/make :graph.registry/malformed :error []
                  "register-graph key set is closed"
                  :expected (vec (sort (map str graph-keys)))
                  :got (vec (sort (map str (set/difference (set (keys req)) graph-keys))))))

    (not (name-ok? workspace))
    (conj (f/make :graph.registry/malformed :error [:workspace]
                  "workspace must be a non-empty string without '/'"
                  :got (f/redact workspace)))

    (not (name-ok? name))
    (conj (f/make :graph.registry/malformed :error [:name]
                  ;; 裸名带 `/` 会让限定名解析出第二个断句点——身份从此不唯一。
                  "graph name must be a non-empty string without '/' — the qualified name is exactly two segments"
                  :got (f/redact name) :refs ["D3.10"]))

    (not (graph-members-ok? members))
    ;; D3.19：菜谱是对象清单，不是 selector 集合。前缀圈选是**界面上的批量加入
    ;; 动作**，点击时展开成对象——它不进菜谱，因此也不进这里的校验。
    (into (members-findings members))

    (not (or (nil? created-at) (and (string? created-at) (seq created-at))))
    (conj (f/make :graph.registry/malformed :error [:created-at]
                  ;; 本模块不读时钟（D4.10-a 同则）：创建时刻由调用方给,
                  ;; 否则「什么时候建的」会随执行机器的时区与时钟漂移。
                  "created-at, when given, is a non-empty timestamp string supplied by the caller"
                  :got (f/redact created-at) :refs ["D3.18"]))

    (not (or (nil? package-name) (package-name-ok? package-name)))
    (conj (f/make :graph.registry/malformed :error [:package-name]
                  package-name-expectation
                  :got (f/redact package-name) :refs ["doc 104 §8.17.7 D"]))))

(defn expand-selectors
  "selector 集合 × 池快照 → 对象清单。

   这是**界面上那个「把 `cis.master.*` 整个加进来」按钮**的实现，也是旧菜谱迁移
   走的同一条路——一处实现，两个入口。展开发生在**调用的那一刻**：此后池里新增
   的对象不会自动入伙（D3.19 认下的代价），编辑器用「有 N 个对象不在这张图里」
   的视图把它变成看得见的待办。

   只展开 namespace / attribute / 词表域词条这三样；resolver、link、metric、
   source 由投影的闭包自动带回来（契约 §2.4–§2.8）——在这里再算一遍就是第二份
   闭包实现，而两份闭包迟早对不上。"
  [model selectors]
  (let [ns-names (into #{} (filter (fn [n] (some #(sel/matches? % n) selectors)))
                       (keys (:namespaces model)))
        reg      (fn [kind] (into #{} (filter (fn [id] (some #(sel/matches? % id) selectors)))
                                  (keys (get model kind))))]
    {:namespaces ns-names
     :attributes (into #{} (filter #(contains? ns-names (namespace %)))
                       (keys (:attributes model)))
     :units (reg :units)
     :terms (reg :terms)}))

(defn- migrated-members
  "旧菜谱（selector 集合）→ 对象清单，按**当下的池快照**展开一次。

   D3.19 把菜谱从 selector 改成清单，于是 D3.19 之前登记的图在新算法下**没有成员**
   ——那正是「空投影与本来就该是空的长得一样」这条老陷阱（契约 §1）。所以迁移是
   显式的一次性展开，而不是让旧图默默变空。

   展开只取 namespace 与 attribute；resolver/link/metric 等由投影的闭包
   自动带回来（契约 §2.4–§2.8），不必也不应在这里复述一遍。"
  [model selectors]
  (expand-selectors model selectors))

(defn- display-name-taken? [db workspace label except-qn]
  (some #(and (= workspace (:graph/workspace %))
              (not= except-qn (:graph/qualified-name %))
              (= label (or (:graph/display-name %) (:graph/name %))))
        (store/all-graphs db)))

(defn register-graph!
  [conn {:keys [workspace name members created-at package-name] :as req}]
  (locking conn
   (let [shape (graph-shape req)
        db    (d/db conn)]
    (cond
      (seq shape) {:findings (f/sort-findings shape)}

      (nil? (store/find-workspace db workspace))
      {:findings [(f/make :workspace.registry/unknown :error [:workspace]
                          "no such workspace is registered" :got workspace)]}

      (or (store/find-graph db (qualify workspace name))
          (display-name-taken? db workspace name nil))
      ;; 同一 workspace 内裸名唯一；**不同 workspace 的同裸名合法**（D3.17 更进一步:
      ;; 连 namespace 同名也合法，workspace 是封闭世界）。裸名的唯一性只是为了让
      ;; 人在一个 workspace 里叫得出名字——身份是 UUID（D3.18）。
      {:findings [(f/make :graph.registry/name-taken :error [:name]
                          "this workspace already has a graph with this name"
                          :got (qualify workspace name) :refs ["D3.18"])]}

      :else
      (let [g (cond-> {:graph/id             (str (java.util.UUID/randomUUID))
                       :graph/state          :active
                       :graph/qualified-name (qualify workspace name)
                       :graph/workspace      workspace
                       :graph/name           name
                       :graph/members        (normalize-members members)
                       :graph/access         []}
                created-at   (assoc :graph/created-at created-at)
                package-name (assoc :graph/package-name package-name))]
        (d/transact conn [(store/graph-tx g)])
        {:graph g :findings []})))))

(defn apply-members-delta
  "Current members × `{:add {kind #{id …}} :remove {kind #{id …}}}` → the new
   members. **Pure**, and internal (8.4.14 item 3 of the follow-ons).

   This is the primitive the intent tools' `recipe-delta` executes through, and
   it is deliberately NOT an MCP tool: the owner ruled on 2026-09-03 against an
   `add_members` face, because a tool that speaks in object ids violates the
   principle the intent tools exist to satisfy — the caller says what they
   want, not which ids to splice into which group.

   It does not conflict with D3.19 «selectors freeze at graph creation»: what
   changes here is the list of NAMED members, not a selector being re-expanded.
   Adding then removing the same id leaves the recipe as it was — `:remove` is
   applied after `:add` so the two have a defined meaning together rather than
   an order the caller has to know.

   Groups outside `member-kinds` are refused rather than silently dropped: a
   delta naming a group that does not exist is a caller mistake, and a recipe
   that quietly ignored it would report success for a change it did not make."
  [current delta]
  (let [known (set (keys member-kinds))
        bad   (remove known (concat (keys (:add delta)) (keys (:remove delta))))]
    (if (seq bad)
      {:findings [(f/make :graph.registry/malformed :error [:members]
                          "a recipe delta names a group that is not one of the eight"
                          :got (vec (sort (map str bad)))
                          :expected (mapv str (sort known)))]}
      {:members
       (reduce-kv
        (fn [ms kind ids]
          (cond-> ms (seq ids) (update kind #(into #{} (remove (set ids)) %))))
        (reduce-kv (fn [ms kind ids]
                     (cond-> ms (seq ids) (update kind (fnil into #{}) ids)))
                   (normalize-members current)
                   (:add delta))
        (:remove delta))})))

(defn update-recipe!
  "改菜谱：换一张对象清单（D3.19）。**身份改不了**——`:graph/id` 是 UUID,
   本函数不碰它。

   清单里的对象**存在与否不在这里判**：那是投影的一处判定
   （`:scope/unknown-member`，scope-projection-v1 §1）。在这里再判一次就是同一件事
   的第二份实现，而两份实现迟早分叉——本仓库在 `:batch` 上刚付过这笔账（D1.8）。
   代价是一张点名了幽灵对象的图能被存下来，收益是「它到底在不在」永远只有一个答案；
   而切版本是硬闸门（D2.14），幽灵在那里必然现形。"
  [conn qn {:keys [members]}]
  (let [db (d/db conn)
        g  (store/find-graph db qn)]
    (cond
      (nil? g)
      {:findings [(f/make :graph.registry/unknown :error [:graph]
                          "no such graph is registered" :got qn :refs ["D3.18"])]}

      (not (graph-members-ok? members))
      {:findings (members-findings members)}

      :else
      (let [g' (assoc g :graph/members (normalize-members members))]
        (d/transact conn [(store/graph-tx g')])
        {:graph g' :findings []}))))

(defn- default-query-findings [{:keys [query entity] :as dq}]
  (cond-> []
    (not (map? dq))
    (conj (f/make :graph.registry/default-query-invalid :error [:graph :default-query]
                  "a default query is a map of :query and optional :entity"
                  :refs ["D3.20"]))

    (and (map? dq) (not (vector? query)))
    (conj (f/make :graph.registry/default-query-invalid :error [:graph :default-query :query]
                  "the query must be an EQL vector" :refs ["D3.20"]))

    (and (map? dq) (vector? query) (empty? query))
    (conj (f/make :graph.registry/default-query-invalid :error [:graph :default-query :query]
                  "the query must not be empty -- clear it with nil instead"
                  :refs ["D3.20"]))

    (and (map? dq) (some? entity) (not (map? entity)))
    (conj (f/make :graph.registry/default-query-invalid :error [:graph :default-query :entity]
                  "the entity must be a map of attribute to value" :refs ["D3.20"]))))

(defn set-default-query!
  "Set (or clear, with `nil`) the query Query Studio opens this graph with.

   Owner ruling 2026-09-01, three parts, and each is a shape rather than a
   preference:

   1. **It lives on the graph**, so it travels with a published demo. The one
      it replaces was a string literal in the UI, which meant every graph on
      every instance opened with the same query -- pointed, by then, at
      namespaces no shipped demo had.
   2. **Query AND entity**, because a default that needs an entry value and
      does not carry one is a default that fails on the first Run.
   3. **Absent stays absent.** `nil` retracts the datom rather than writing an
      empty one, so «this graph has no default» remains distinguishable from
      «its default is empty» -- the Lab answers the first with an empty editor.

   Unlike `rename-graph!` this needs no version guard: the setting is not part
   of a graph's identity and a cut version does not freeze it. What a version
   sealed stays sealed; this only decides what the editor is pre-filled with."
  [conn qn default-query]
  (let [db (d/db conn)
        g  (store/find-graph db qn)]
    (cond
      (nil? g)
      {:findings [(f/make :graph.registry/unknown :error [:graph]
                          "no such graph is registered" :got qn :refs ["D3.18"])]}

      (and (some? default-query) (seq (default-query-findings default-query)))
      {:findings (default-query-findings default-query)}

      :else
      (let [g' (if (some? default-query)
                 (assoc g :graph/default-query default-query)
                 (dissoc g :graph/default-query))]
        ;; Retracting rather than writing an empty value: `graph-tx` omits the
        ;; attribute for a nil, and an omitted attribute in a `d/transact` map
        ;; does not clear a datom that is already there -- so the retraction is
        ;; explicit or it does not happen.
        (when (and (some? (:graph/default-query g)) (nil? default-query))
          (d/transact conn [(store/retract-default-query-tx qn (:graph/default-query g))]))
        (d/transact conn [(store/graph-tx g')])
        {:graph g' :findings []}))))

(defn set-package-name!
  "Set — or clear, with `nil` — the name this graph's versions are published
   under (doc 104 §8.17.7 D, owner ruling 2026-09-06 H item 2).

   Its own function rather than a key on `update-recipe!`: the recipe says what
   is IN the graph, this says what the graph is CALLED on someone else's
   instance. Folding it into the recipe write would make every recipe save able
   to silently rename a published package.

   Absent stays absent, like `set-default-query!`: `nil` retracts, so «this
   graph uses the default release name» (`package-name-for`) remains reachable."
  [conn qn package-name]
  (let [db (d/db conn)
        g  (store/find-graph db qn)]
    (cond
      (nil? g)
      {:findings [(f/make :graph.registry/unknown :error [:graph]
                          "no such graph is registered" :got qn :refs ["D3.18"])]}

      (and (some? package-name) (not (package-name-ok? package-name)))
      {:findings [(f/make :graph.registry/malformed :error [:package-name]
                          package-name-expectation
                          :got (f/redact package-name) :refs ["doc 104 §8.17.7 D"])]}

      :else
      (let [g' (if (some? package-name)
                 (assoc g :graph/package-name package-name)
                 (dissoc g :graph/package-name))]
        (when (and (some? (:graph/package-name g)) (nil? package-name))
          (d/transact conn [(store/retract-package-name-tx qn (:graph/package-name g))]))
        (d/transact conn [(store/graph-tx g')])
        {:graph g' :findings []}))))

(def default-release-namespace
  "The first segment of a release name nobody chose, when the workspace's own
   name cannot serve as one (a personal workspace is an email address)."
  "open-atlas")

(defn- default-release-segment
  "A graph's bare name as a release-name segment (`[A-Za-z0-9_.-]`): anything
   else becomes `-`, leading and trailing separators are dropped. nil when
   nothing usable is left."
  [nm]
  (let [seg (-> (str nm)
                (str/replace #"[^A-Za-z0-9_.-]+" "-")
                (str/replace #"^[-.]+|[-.]+$" ""))]
    (when (seq seg) seg)))

(defn package-name-for
  "The release name a version of this graph is sealed under
   (doc 104 §8.17.7 D): `{:name … :default? bool}`, or `{:findings […]}` only
   in the one case below where no name can be made.

   `:package-name` when the graph has one. Otherwise a DEFAULT, so publishing
   needs no input (owner ruling 2026-10-01, reversing the 2026-09-06 refusal):
   `<workspace>/<graph>` for a team workspace, where the qualified name already
   reads as a release name, and `open-atlas/<graph>` for a PERSONAL workspace,
   whose name is an email address. The earlier rule refused the personal case
   because a package named after a person, or a name invented silently, is an
   identity nobody chose; the default keeps the first half of that concern (the
   person never appears in it — the namespace is the product's own) and answers
   the second by being visible and editable: the Publish tab shows the name in
   force, tagged `default`, and `set-package-name!` replaces it. What it does
   not promise is uniqueness across publishers — two personal workspaces with a
   graph of the same name publish the same default; name one if both ship.

   `:package/name-required` remains for the edge where a personal workspace's
   graph name has nothing a release name can use.

   Pure, and here rather than in the cut: naming a graph is this module's
   subject, and the cut must not grow a second opinion about it (D1.8). The
   Publish tab reads the same answer (`:graph/release-name` on the graph row)
   instead of re-deriving it."
  [{:graph/keys [package-name workspace name qualified-name]}]
  (cond
    (some? package-name) {:name package-name}

    (str/includes? (str workspace) "@")
    (if-let [seg (default-release-segment name)]
      {:name (str default-release-namespace "/" seg) :default? true}
      {:findings [(f/make :package/name-required :error [:graph :package-name]
                          (str "this graph is in a personal workspace and its name has nothing a "
                               "release name can use; set a release name on the graph before "
                               "cutting a version")
                          :got (str qualified-name)
                          :expected package-name-expectation
                          :refs ["doc 104 §8.17.7 D"])]})

    :else {:name (qualify workspace name) :default? true}))

(defn set-default-graph!
  "Set — or clear, with `nil` — the graph a workspace lands on (D3.21).

   Pairs with `set-default-query!`: that one decides what a graph opens WITH,
   this one decides WHICH graph is open. Landing was the first live graph in
   qualified-name order, so a publisher shipping two demos could not say which
   one a new user should see first.

   The named graph must be in THIS workspace. A graph from another workspace is
   refused rather than stored and ignored later — D3.17 makes a workspace a
   closed world, and a preference pointing out of it would be the one place
   that stopped being true."
  [conn workspace qn]
  (let [db (d/db conn)
        w  (store/find-workspace db workspace)
        g  (when qn (store/find-graph db qn))]
    (cond
      (nil? w)
      {:findings [(f/make :graph.registry/unknown :error [:workspace]
                          "no such workspace is registered" :got workspace
                          :refs ["D3.3"])]}

      (and qn (nil? g))
      {:findings [(f/make :graph.registry/unknown :error [:workspace :default-graph]
                          "no such graph is registered" :got qn :refs ["D3.21"])]}

      (and g (not= workspace (:graph/workspace g)))
      {:findings [(f/make :graph.registry/default-graph-foreign :error
                          [:workspace :default-graph]
                          "a workspace can only land on its own graph"
                          :got qn :refs ["D3.17" "D3.21"])]}

      :else
      (let [w' (if qn
                 (assoc w :workspace/default-graph qn)
                 (dissoc w :workspace/default-graph))]
        (when (and (some? (:workspace/default-graph w)) (nil? qn))
          (d/transact conn [(store/retract-default-graph-tx
                             workspace (:workspace/default-graph w))]))
        (d/transact conn [(store/workspace-tx w')])
        {:workspace w' :findings []}))))

(defn default-graph
  "Which graph this workspace lands on, or nil to let the caller choose.

   **A stale pointer is nil, not an error.** The setting names a qualified name
   rather than a UUID (see the schema), so a deleted or renamed graph leaves one
   behind; answering nil lets the Lab fall back to its own ordering, which is
   what it did before this setting existed. Refusing here would turn deleting a
   graph into an event that breaks the workspace's landing."
  [db workspace]
  (when-let [qn (:workspace/default-graph (store/find-workspace db workspace))]
    (when-let [g (store/find-graph db qn)]
      (when (and (= workspace (:graph/workspace g))
                 (not= :archived (:graph/state g)))
        qn))))

(defn migrate-recipes!
  "把 D3.19 之前登记的 selector 菜谱**一次性**展开成对象清单。

   幂等：已有 `:graph/members` 的图不动。返回 `{:migrated [限定名…] :findings []}`
   ——**说出改了哪些**，因为一次无声的迁移与「本来就是这样」在结果上没有区别，
   而这正是本仓库反复付过代价的那种形态。

   `model` 由调用方传入（`pool/as-dialect` 的结果），本 ns 不自己去渲染整池。"
  [conn model]
  (let [db (d/db conn)
        stale (filter (fn [g] (and (empty? (:graph/members g))
                                   (seq (:graph/legacy-selectors g))))
                      (store/all-graphs db))
        txs (for [g stale]
              (store/graph-tx (assoc g :graph/members
                                     (normalize-members
                                      (migrated-members model (:graph/legacy-selectors g))))))]
    (when (seq txs) (d/transact conn (vec txs)))
    {:migrated (vec (sort (map :graph/qualified-name stale))) :findings []}))

(defn set-state!
  "Archive/restore is state-only: the graph UUID, recipe, versions, and audit
   references remain intact.  There is intentionally no delete operation."
  [conn qn state]
  (let [db (d/db conn)
        g (store/find-graph db qn)]
    (cond
      (nil? g)
      {:findings [(f/make :graph.registry/unknown :error [:graph]
                          "no such graph is registered" :got qn)]}

      (not (contains? #{:active :archived} state))
      {:findings [(f/make :graph.registry/malformed :error [:state]
                          "graph state must be :active or :archived" :got state)]}

      :else
      (let [g' (assoc g :graph/state state)]
        (d/transact conn [(store/graph-tx g')])
        {:graph g' :findings []}))))

(defn delete-graph!
  "Permanently remove an archived Graph registration, its Semantic draft/history,
   and the version records supplied by `version-deletion-tx` in one transaction.
   Legacy pool objects remain shared and retained. Active Graphs are refused."
  [conn qn version-deletion-tx]
  (locking conn
    (let [db (d/db conn)
          g (store/find-graph db qn)]
      (cond
        (nil? g)
        {:findings [(f/make :graph.registry/unknown :error [:graph]
                            "no such graph is registered" :got qn :refs ["D3.18"])]}

        (not= :archived (:graph/state g))
        {:findings [(f/make :graph.registry/malformed :error [:graph]
                            "only an archived graph can be permanently deleted — archive it first"
                            :got qn :refs ["D3.19"])]}

        :else
        (let [semantic-tx (pool/semantic-deletion-tx db (:graph/workspace g) (:graph/id g))
              version-tx (vec (version-deletion-tx db qn))]
          (d/transact conn
                      (into [[:db/retractEntity [:graph.graph/qualified-name qn]]]
                            (concat semantic-tx version-tx)))
          {:graph g
           :semantic-records-deleted (count semantic-tx)
           :version-records-deleted (count version-tx)
           :findings []})))))

(defn- graph-access-ok? [access]
  (and (map? access)
       (= #{:principal :role} (set (keys access)))
       (principal? (:principal access))
       (contains? graph-access-roles (:role access))))

(defn graph-access-role
  "Effective graph role for one workspace member.  Workspace membership grants
   visibility to every graph.  A workspace editor edits existing graph recipes;
   an explicit graph `:editor` remains the narrower alternative for a member."
  [db qn principal]
  (when-let [graph (store/find-graph db qn)]
    (let [workspace (:graph/workspace graph)
          membership (workspace-role db workspace principal)]
      (cond
        (= :owner membership) :owner
        (= :editor membership) :editor
        membership (or (some (fn [access]
                               (when (= principal (:principal access)) (:role access)))
                             (:graph/access graph))
                       :viewer)
        :else nil))))

(defn graph-visible?
  [db qn principal]
  (boolean (graph-access-role db qn principal)))

(defn graph-editable?
  [db qn principal]
  (contains? #{:owner :editor} (graph-access-role db qn principal)))

(defn graph-creatable?
  "Only workspace owners create graphs.  Editors are grants on an existing
   graph, therefore cannot create a new graph with unreviewed ownership."
  [db workspace principal]
  (= :owner (workspace-role db workspace principal)))

(defn visible-graphs
  "Every workspace member sees every graph in that workspace.  This is the
   deliberate team visibility rule; per-graph grants affect editability only."
  [db principal]
  (->> (store/all-graphs db)
       (filter #(workspace-member? db (:graph/workspace %) principal))
       vec))

(defn grant-graph-access!
  "Make one existing workspace **member** an editor of one graph.

   An upsert, so re-granting does not leave two ambiguous rows. It cannot invite
   an outsider; add the workspace membership first.

   **A grant that cannot change anything is refused** (owner ruling 2026-08-30).
   A workspace owner or editor already edits every graph in the workspace, and
   `graph-access-role` never consults the grant list for them — so writing the
   row would file a record that looks like access control and enforces nothing.
   The refusal names the role they already hold, which is also the answer to
   «then how do I narrow them?»: you cannot, from here — narrow the workspace
   role instead."
  [conn qn access]
  (let [db (d/db conn)
        graph (store/find-graph db qn)]
    (cond
      (nil? graph)
      {:findings [(f/make :graph.registry/unknown :error [:graph]
                          "no such graph is registered" :got qn)]}

      (not (graph-access-ok? access))
      {:findings [(f/make :graph.access/malformed :error [:access]
                          "graph access must be {:principal {:issuer … :subject …} :role :editor}"
                          :got (f/redact access))]}

      (not (workspace-member? db (:graph/workspace graph) (:principal access)))
      {:findings [(f/make :graph.access/not-workspace-member :error [:access :principal]
                          "grant the user workspace membership before assigning graph access"
                          :got (f/redact (:principal access)))]}

      ;; Refused, not written: see the docstring. The check reads the workspace
      ;; role rather than the grant list because that is precisely what
      ;; `graph-access-role` does before it ever looks at a grant.
      (contains? #{:owner :editor}
                 (workspace-role db (:graph/workspace graph) (:principal access)))
      {:findings [(f/make :graph.access/redundant :error [:access :principal]
                          (str "this person is already a workspace "
                               (name (workspace-role db (:graph/workspace graph)
                                                     (:principal access)))
                               ", which edits every graph in the workspace — a grant here "
                               "would change nothing; narrow their workspace role instead")
                          :got (f/redact (:principal access)))]}

      :else
      (let [access' (vec (sort-by #(pr-str (:principal %))
                                  (conj (vec (remove #(= (:principal access) (:principal %))
                                                     (:graph/access graph)))
                                        access)))
            graph' (assoc graph :graph/access access')]
        (d/transact conn [(store/graph-tx graph')])
        {:graph graph' :findings []}))))

(defn revoke-graph-access!
  "Remove an explicit graph grant.  The person remains a workspace viewer until
   their workspace membership itself is removed."
  [conn qn principal]
  (let [db (d/db conn)
        graph (store/find-graph db qn)]
    (cond
      (nil? graph)
      {:findings [(f/make :graph.registry/unknown :error [:graph]
                          "no such graph is registered" :got qn)]}

      (not (principal? principal))
      {:findings [(f/make :graph.access/malformed :error [:principal]
                          "principal must be {:issuer … :subject …}"
                          :got (f/redact principal))]}

      :else
      (let [graph' (update graph :graph/access
                           #(vec (remove (fn [access] (= principal (:principal access))) %)))]
        (d/transact conn [(store/graph-tx graph')])
        {:graph graph' :findings []}))))

(defn ensure-identities!
  "Backfill UUID and active state for graphs created before graph identity was
   introduced.  The operation is idempotent and preserves every old qualified
   name, so pinned version references stay valid."
  [conn]
  (let [missing (filter #(nil? (:graph/id %)) (store/all-graphs (d/db conn)))]
    (doseq [graph missing]
      (d/transact conn [(store/graph-tx (assoc graph :graph/id (str (java.util.UUID/randomUUID))
                                             :graph/state (or (:graph/state graph) :active)))]))
    (count missing)))

(defn set-display-name!
  "Rename the user-facing label without moving deployment coordinates or UUID.
   Published versions and tokens continue resolving their original coordinates."
  [conn qn new-name]
  (locking conn
    (let [db (d/db conn)
          g (store/find-graph db qn)
          label (when (string? new-name) (str/trim new-name))]
      (cond
        (nil? g)
        {:findings [(f/make :graph.registry/unknown :error [:graph] "no such graph is registered")]}
        (or (str/blank? label) (> (count label) 128) (re-find #"[\p{Cntrl}]" label))
        {:findings [(f/make :graph.registry/malformed :error [:name] "Graph name must contain 1–128 characters and no control characters")]}
        (display-name-taken? db (:graph/workspace g) label qn)
        {:findings [(f/make :graph.registry/name-taken :error [:name] "This workspace already has a graph with this name")]}
        :else
        (let [updated (assoc g :graph/display-name label)]
          (d/transact conn [(store/graph-tx updated)])
          {:graph updated :findings []})))))

(defn rename-graph!
  "改限定名——**D3.10 那一半的机制**。

   `update-recipe!` 的注释写着：「B5b 切版本接上时若要放开『还没切过版本的图
   可以改名』，那是**加一个函数**、并在里面查『有没有版本』——而不是放松这里的
   任何东西。」这就是那个函数，而「有没有版本」不在本模块：graph 不认识版本
   （B5b 的 `open.atlas.studio.version` 依赖 graph，反过来会成环）。

   所以 `guard` 是**必传**的：`(fn [db qn] → findings)`，由认识版本的那一层给。
   没有无 guard 的 arity——一个可以不带守卫调用的 rename，等于把 D3.10 从结构
   退回成纪律。

   改名等于新建一张图（D3.10）：旧名下的已切版本、已发的 token、已部署的指针
   继续指向旧身份。所以这个函数只在**一个版本都没切过**时说得通，而那正是
   guard 要判的事。"
  [conn qn new-name guard]
  (locking conn
   (let [db (d/db conn)
        g  (store/find-graph db qn)
        ;; The facade documents `new-name` as the string it is, and this took a
        ;; map — so a caller following the docstring passed a string, `:name`
        ;; destructured to nil, and the rename was refused for having no name.
        ;; The Builder's rename button had never worked, and nothing noticed
        ;; because nothing tested rename (owner ruling 2026-08-28: the facade's
        ;; signature stands; the implementation follows it).
        name new-name]
    (cond
      (nil? g)
      {:findings [(f/make :graph.registry/unknown :error [:graph]
                          "no such graph is registered" :got qn :refs ["D3.10"])]}

      (not (name-ok? name))
      {:findings [(f/make :graph.registry/malformed :error [:name]
                          "graph name must be a non-empty string without '/' — the qualified name is exactly two segments"
                          :got (f/redact name) :refs ["D3.10"])]}

      :else
      (let [ws   (:graph/workspace g)
            qn'  (qualify ws name)
            errs (vec (guard db qn))]
        (cond
          (seq errs) {:findings errs}

          (or (and (not= qn qn') (store/find-graph db qn'))
              (display-name-taken? db ws (or (:graph/display-name g) name) qn))
          {:findings [(f/make :graph.registry/name-taken :error [:name]
                              "this workspace already has a graph with this name"
                              :got qn' :refs ["D3.10"])]}

          :else
          (let [eid (first (d/q '[:find [?e ...] :in $ ?q
                                  :where [?e :graph.graph/qualified-name ?q]] db qn))
                g'  (assoc g :graph/qualified-name qn' :graph/name name)]
            ;; 先 retract 整个实体、再写新的：限定名是 `:db.unique/identity`,
            ;; 原地改它等于让同一个实体先后有两个身份。
            (d/transact conn [[:db/retractEntity eid]])
            (d/transact conn [(store/graph-tx g')])
            {:graph g' :findings []})))))))
