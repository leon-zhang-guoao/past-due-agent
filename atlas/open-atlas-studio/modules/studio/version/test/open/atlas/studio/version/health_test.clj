(ns open.atlas.studio.version.health-test
  "**切版本体检的拒绝那一半**（D2.11）。每类各一条不过的，外加 E6.2 的四条判定。

   本文件**不开池**：体检是纯函数，跑在投影产物 + 闭包上（README 裁定 ②）。
   基线模型经 `read-dialect` 走一遍——手搓一个非法模型去测体检，测到的是
   writer 的断言不是判定。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.dialect :as dialect]
            ;; E1 / D5.14：require 即注册。体检要问连接器「这条语句绑了哪些
            ;; 名字」，没有任何连接器在场时它只能答 unverifiable——那是本模块
            ;; 的**生产**行为（宿主决定加载谁），但测接线核对必须有个真的。
            [open.atlas.common.connector.sql]
            [open.atlas.studio.version :as version]))

(defn- read! [m]
  (let [{:keys [model findings]} (dialect/read-dialect (pr-str m))]
    (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    model))

(def ^:private base
  "干净的基线：两个属性都在 `:output` 里（D2.2 的存在性闭包），
   一个 sql source，一个 `\"single\"` resolver。"
  (read!
   {:attributes {:x.a/id   {:type :integer :format :id :identity? true :label "A"}
                 :x.a/name {:type :string :format :text :label "Name"}}
    :namespaces {"x.a" {:kind :master :label "A"}}
    :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
    :resolvers  [{:id "a_by_id" :input [:x.a/id] :output [:x.a/id :x.a/name]
                  :result_type "single"
                  :source {:type "sql" :query "SELECT 1"} :source-ref "db"}]}))

(def ^:private empty-closure {:nodes {} :order [] :reference-attributes {}})

(defn- check
  ([model] (check model {}))
  ([model opts] (version/health model empty-closure opts)))

(defn- codes [r] (set (map :code (:findings r))))
(defn- ok! [r]
  (is (empty? (filter #(= :error (:severity %)) (:findings r))) (pr-str (:findings r)))
  r)

(deftest the-baseline-passes
  "前提自检：基线本身干净——否则下面每条测试都可能是在为别的毛病鼓掌。"
  (let [r (ok! (check base))]
    (is (empty? (:findings r)))
    ;; `:structural-ok?` **不在这里**：它由 `seal-graph` 从同一批 findings 推导,
    ;; 传进去就是同一个事实的第二个权威位置（`impl.seal/derived-health-keys`）。
    (is (not (contains? (:health r) :structural-ok?)))))

;; ---------------------------------------------------------------------------
;; 结构（D2.11 第一格）
;; ---------------------------------------------------------------------------

(deftest dangling-input-is-refused
  (let [m (update-in base [:resolvers 0 :input] conj :x.b/missing)
        r (check m)]
    (is (contains? (codes r) :cut.structure/dangling-input))
    (is (every? version/finding? (:findings r)))))

(deftest broken-link-is-refused
  (let [m (assoc base :links [{:from :x.a/id :to :x.b/gone :cardinality :one-to-many}])
        r (check m)]
    (is (contains? (codes r) :cut.structure/broken-link))))

(deftest two-single-resolvers-on-the-same-anchor-are-refused
  "**多方产出**：两个 `\"single\"` resolver 从同一个锚产出同一个属性——规划器
   替谁选都是错的。

   判据**故意收在 `\"single\"` 上**：同一属性经不同锚点各有一条路是 Pathom 的
   常态（demo 模型里 28 个属性如此）；同一锚上两个具名集合（`\"list\"`）是
   D2.8 第 1 条要的形态。见 `impl.health/multiple-producers` 的裁量点。"
  (let [m (update base :resolvers conj
                  {:id "a_again" :input [:x.a/id] :output [:x.a/name]
                   :result_type "single"
                   :source {:type "sql" :query "SELECT 2"} :source-ref "db"})
        r (check m)]
    (is (contains? (codes r) :cut.structure/multiple-producers))
    (is (= ["a_again" "a_by_id"]
           (:expected (first (filter #(= :cut.structure/multiple-producers (:code %))
                                     (:findings r))))))))

(deftest ranked-single-resolvers-on-the-same-anchor-pass
  "D2.11 amendment 2026-09-18 (contract 2.0 §4.1): the same pair is *not*
   multiple producers once their `:priority` values are pairwise distinct —
   the planner has a total order to fall back along, nothing is ambiguous."
  (let [m (-> base
              (assoc-in [:resolvers 0 :priority] 10)
              (update :resolvers conj
                      {:id "a_again" :input [:x.a/id] :output [:x.a/name]
                       :result_type "single" :priority 5
                       :source {:type "sql" :query "SELECT 2"} :source-ref "db"}))
        r (check m)]
    (is (not (contains? (codes r) :cut.structure/multiple-producers)) (pr-str (:findings r)))))

(deftest tied-or-default-priority-on-the-same-anchor-is-still-refused
  "The exemption is for a *ranking*, not for the key's presence: two equal
   `:priority` values are a tie, and an explicit 0 next to an absent one
   (which defaults to 0) is the same tie. Both keep the original refusal,
   and the message now points at the way out."
  (testing "equal explicit priorities"
    (let [m (-> base
                (assoc-in [:resolvers 0 :priority] 3)
                (update :resolvers conj
                        {:id "a_again" :input [:x.a/id] :output [:x.a/name]
                         :result_type "single" :priority 3
                         :source {:type "sql" :query "SELECT 2"} :source-ref "db"}))
          r (check m)
          fnd (first (filter #(= :cut.structure/multiple-producers (:code %)) (:findings r)))]
      (is (some? fnd))
      (is (= ["a_again" "a_by_id"] (:expected fnd)))
      (is (re-find #"distinct :priority \(contract 2\.0 §4\)" (:message fnd)))))
  (testing "explicit 0 beside an absent priority (defaults to 0)"
    (let [m (update base :resolvers conj
                    {:id "a_again" :input [:x.a/id] :output [:x.a/name]
                     :result_type "single" :priority 0
                     :source {:type "sql" :query "SELECT 2"} :source-ref "db"})
          r (check m)]
      (is (contains? (codes r) :cut.structure/multiple-producers)))))

(def ^:private compound
  "The ruling's proof shape (doc 106 §106.14 item 14): a pod recognised by its
   namespace AND its name, listed by a root collection."
  (read!
   {:attributes {:k8s.pod/namespace {:type :string :format :id :identity? true :label "NS"}
                 :k8s.pod/name      {:type :string :format :id :identity? true :label "Name"}
                 :k8s.pod/phase     {:type :string :format :text :label "Phase"}
                 :k8s.pod/all_pods  {:target "k8s.pod" :cardinality :one-to-many :label "所有 pod"}}
    :namespaces {"k8s.pod" {:kind :master :label "Pod"}}
    :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
    :resolvers  [{:id "pod_by_key" :input [:k8s.pod/namespace :k8s.pod/name]
                  :output [:k8s.pod/phase] :result_type "single"
                  :source {:type "sql" :query "SELECT 1"} :source-ref "db"}
                 {:id "pod_all" :input [] :curated? true :limit {:default 50 :max 500}
                  :output [{:k8s.pod/all_pods [:k8s.pod/namespace :k8s.pod/name
                                               :k8s.pod/phase]}]
                  :result_type "list"
                  :source {:type "sql" :query "SELECT 2"} :source-ref "db"}]}))

(deftest a-compound-key-graph-cuts
  "Two `:identity? true` attributes in one namespace are ONE key, and a graph
   built on one is ordinary (owner 2026-09-07, doc 106 §106.14 item 14)."
  (ok! (check compound)))

(deftest a-root-collection-missing-half-the-key-is-refused
  "The entry point has to hand back the WHOLE key or nothing can be read from
   the rows it answers — the planner reaches the list and stops there."
  (let [m (assoc-in compound [:resolvers 1 :output]
                    [{:k8s.pod/all_pods [:k8s.pod/name :k8s.pod/phase]}])
        r (check m)
        f (first (filter #(= :cut.structure/incomplete-root-collection (:code %))
                         (:findings r)))]
    (is (some? f))
    (is (= ["k8s.pod/namespace"] (:files f)) "the refusal names the missing key")
    (is (= ["k8s.pod/name" "k8s.pod/namespace"] (:expected f)))))

(deftest a-single-key-root-collection-is-not-held-to-the-new-test
  "One key either is in the branch or the branch is empty, and that case is
   already D2.2's — the check must not fire a second, differently worded
   refusal on today's graphs."
  (let [m (-> base
              (assoc-in [:attributes :x.a/all_a] {:target "x.a" :cardinality :one-to-many})
              (update :resolvers conj
                      {:id "a_all" :input [] :curated? true :limit {:default 50 :max 500}
                       :output [{:x.a/all_a [:x.a/name]}] :result_type "list"
                       :source {:type "sql" :query "SELECT 2"} :source-ref "db"}))]
    (is (not (contains? (codes (check m)) :cut.structure/incomplete-root-collection)))))

(deftest two-list-resolvers-on-the-same-anchor-are-fine
  "同一锚上两个 `\"list\"`：两条 WHERE 各自收敛的具名集合（D2.8 第 1 条）。
   demo 模型里 `demo_mso_vpos` 与 `demo_mso_dropship_vpos` 正是这一对——把它判成
   多方产出会拒掉一份**既成事实**的模型。"
  (let [m (-> base
              (assoc-in [:attributes :x.a/rows] {:target "x.a" :cardinality :one-to-many})
              (update :resolvers conj
                      {:id "a_all" :input [:x.a/id] :output [{:x.a/rows [:x.a/name]}]
                       :result_type "list" :limit {:default 50 :max 500}
                       :source {:type "sql" :query "SELECT 2"} :source-ref "db"})
              (update :resolvers conj
                      {:id "a_some" :input [:x.a/id] :output [{:x.a/rows [:x.a/name]}]
                       :result_type "list" :limit {:default 50 :max 500}
                       :source {:type "sql" :query "SELECT 3"} :source-ref "db"}))]
    (ok! (check m))))

;; ---------------------------------------------------------------------------
;; D2.13 具名查询必须签字
;; ---------------------------------------------------------------------------

(defn- with-rows
  "基线 + 一个 join 属性。**list resolver 的 `:output` 必须挂在它下面**
   （8.4.19 裁定十三 ②：无锚 list 编不出来，体检拒绝），所以凡是往基线上加
   list resolver 的用例都从这里起步。"
  [m]
  (assoc-in m [:attributes :x.a/rows] {:target "x.a" :cardinality :one-to-many}))

(defn- unanchored-list [& {:as over}]
  (merge {:id "a_all" :input [] :output [{:x.a/rows [:x.a/name]}] :result_type "list"
          :limit {:default 50 :max 500}
          :source {:type "sql" :query "SELECT 2"} :source-ref "db"}
         over))

(deftest an-empty-recipe-cannot-be-cut
  "doc 106 §106.12 13.3: a graph whose recipe names nothing used to pass every
   check and publish — a version carrying nothing, refused nowhere."
  (let [r (check (read! {}))]
    (is (contains? (codes r) :cut.structure/empty-recipe))
    (is (every? version/finding? (:findings r)))))

(deftest a-graph-with-one-object-is-not-empty
  "The baseline is untouched by 13.3 — existing non-empty graphs are unaffected."
  (is (not (contains? (codes (check base)) :cut.structure/empty-recipe))))

(deftest d2-13-an-unanchored-list-without-a-signature-is-refused
  "D2.13：`:input` 为空的 `\"list\"` 是具名查询，作者必须签 `:curated? true`。
   D2.13 把这条的执法**逐字放在切版本**，所以判定在这里而不在 B0。"
  (let [r (check (update (with-rows base) :resolvers conj (unanchored-list)))]
    (is (contains? (codes r) :cut.resolver/unsigned-named-query))
    (is (every? version/finding? (:findings r)))))

(deftest d2-13-a-signed-named-query-passes
  (ok! (check (update (with-rows base) :resolvers conj (unanchored-list :curated? true)))))

(deftest d2-13-an-anchored-list-needs-no-signature
  "有锚就不签字——`:curated? false` 不是合法规范形（D2.12），所以「不写」
   就是有锚那一侧的唯一写法。"
  (ok! (check (update (with-rows base) :resolvers conj (unanchored-list :input [:x.a/id])))))

(deftest a-list-without-a-join-root-is-refused
  "**8.4.19 裁定十三 ②**（owner 2026-09-04）：`:output` 平铺的 list 编译不出来
   ——引擎只发一条 warning 就把这条 resolver 丢掉，草稿里它像存在、查询时它
   不存在。code 是引擎那一条，不是本模块新起的名字。"
  (let [r (check (update (with-rows base) :resolvers conj
                         (unanchored-list :curated? true :output [:x.a/name])))]
    (is (contains? (codes r) :engine.compile/list-without-anchor) (pr-str (:findings r)))
    (is (every? version/finding? (:findings r)))))

;; ---------------------------------------------------------------------------
;; D2.2 属性声明漂移
;; ---------------------------------------------------------------------------

(deftest d2-2-produced-but-unregistered-is-refused
  "属性以**存在性闭包**中的出现为存在依据；类型只有 `:attributes` 一个事实源。"
  (let [m (update-in base [:resolvers 0 :output] conj :x.a/ghost)
        r (check m)]
    (is (contains? (codes r) :cut.attribute/undeclared))))

(deftest d2-2-registered-but-never-produced-is-refused
  "反向漂移：注册表里有，任何 `:output` / mutation 键面里都没有。
   **日常写入只警告，密封严格**（D2.2：书写自由、密封严格，与 D3.4 同哲学）。"
  (let [m (assoc-in base [:attributes :x.a/orphan]
                    {:type :string :format :text :label "Orphan"})
        r (check m)]
    (is (contains? (codes r) :cut.attribute/unregistered-use))))

(def ^:private with-join
  "D2.29：`:output` 树里的**分支**是一条 join 属性，和叶子一样是 `:attributes`
   的普通条目。"
  {:attributes {:x.a/id {:type :integer :format :id :identity? true :label "A"}
                :x.a/bs {:target "x.b" :cardinality :one-to-many :label "Bs"}
                :x.b/id {:type :integer :format :id :identity? true :label "B"}}
   :namespaces {"x.a" {:kind :master :label "A"}
                "x.b" {:kind :master :label "B"}}
   :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
   :resolvers  [{:id "a_by_id" :input [:x.a/id]
                 :output [:x.a/id {:x.a/bs [:x.b/id]}]
                 :result_type "single"
                 :source {:type "sql" :query "SELECT 1"} :source-ref "db"}]})

(deftest d2-2-join-attributes-are-attributes
  "**D2.29（owner 裁定 2026-09-02）**：分支键进属性存在性闭包，和叶子走同一条路。

   从前它们被剔出闭包，因为 navigation 在 namespace 条目上另有声明处；那个
   声明处已经退役。登记了 join 属性的分支键因此两个方向都不漂移——它既不是
   「产出而未登记」，也不是「登记而没人产出」——而且**不欠 `:type`**：
   `:target` 就是它的类型事实。"
  (let [m (read! with-join)
        r (ok! (check m))]
    (is (empty? (:findings r)))))

(deftest d2-2-an-unregistered-branch-key-is-refused
  "分支键既然是属性，没登记就和没登记的叶子一样拒绝——从前它会被当成
   navigation 静默放过。

   改动加在**已读进来的模型**上，不再过一次 `read-dialect`：B0 的
   `:dialect.invariant/unregistered-reference` 会先一步拒掉它，而这里要测的是
   体检那一层也拦得住（纵深，同 `d2-2-produced-but-unregistered-is-refused`）。"
  (let [m (update-in (read! with-join) [:resolvers 0 :output]
                     conj {:x.a/ghosts [:x.b/id]})
        r (check m)]
    (is (contains? (codes r) :cut.attribute/undeclared))))

;; ---------------------------------------------------------------------------
;; D2.8 —— **回落 `read-dialect`**，不写第二个 schema
;; ---------------------------------------------------------------------------

(deftest d2-8-pagination-is-mandatory
  "D2.8 第 3 条：`\"list\"` resolver 必须声明 `:limit`。判定**回落 B0 的
   reader**（`:dialect.invariant/list-limit-missing`）——重写一份等于给同一条
   规则造第二个事实源。code 保持 `dialect.*`，不重贴一层 `cut.*` 的皮。"
  (let [m (update base :resolvers conj
                  {:id "a_all" :input [:x.a/id] :output [:x.a/name]
                   :result_type "list"
                   :source {:type "sql" :query "SELECT 2"} :source-ref "db"})
        r (check m)]
    (is (contains? (codes r) :dialect.invariant/list-limit-missing))
    (is (every? version/finding? (:findings r)) "回落来的 code 也过 finding-v1")))

(deftest d2-8-allowed-must-be-a-subset-of-values
  "D2.8 第 4 条：码值只有一个定义处；`:allowed` 写了就必须是 `:values` 键集的
   **子集**。同样回落 B0 的 reader。"
  (let [m (-> base
              (assoc-in [:attributes :x.a/name]
                        {:type :string :format :code :label "Name"
                         :values {"N" {:label "New"}}})
              (assoc-in [:resolvers 0 :eql-params :status]
                        {:attribute :x.a/name :op :eq :allowed ["N" "Z"]}))
        r (check m)]
    (is (contains? (codes r) :dialect.invariant/eql-param-unresolvable))))

;; ---------------------------------------------------------------------------
;; E6.2 韧性 —— **退出条件 3**
;; ---------------------------------------------------------------------------

(defn- with-source-policy [m pol] (assoc-in m [:sources "db" :resilience] pol))

(defn- with-mutation [m mut]
  (update m :mutations (fnil conj [])
          (merge {:id "m" :result_type "none"
                  :source {:type "sql" :query "UPDATE x"} :source-ref "db"
                  :params {:x.a/id {:required? true}}}
                 mut)))

(deftest e6-2-a-connector-that-cannot-enforce-a-timeout-must-not-be-published
  "E6.2 第一句：**每个 source 有可强制的超时**——enforce 不了超时的连接器
   不允许发布。清单可传入，因为将来的连接器不一定能。"
  (let [r (check base {:timeout-enforcing-types #{"api"}})]
    (is (contains? (codes r) :cut.resilience/timeout-not-enforceable))))

(deftest e6-2-an-effective-timeout-must-resolve-to-a-positive-number
  "`:resilience` 的键面在方言里收作 `{keyword → any}`（D6.2 定形前的既有先例），
   所以 `:timeout_ms \"3s\"` 过得了 B0 而过不了体检——这条判定不在 B0 那一层。"
  (let [r (check (with-source-policy base {:timeout_ms "3s"}))]
    (is (contains? (codes r) :cut.resilience/timeout-not-enforceable)))
  (testing "缺省链兜底：source 没写超时时用平台缺省（D6.2 四级合并），不是拒绝"
    (ok! (check base))))

(deftest e6-2-a-read-resolver-with-retry-passes
  "**退出条件 3（一）**：read resolver 带 retry **通过**——D6.4「resolver 的读
   默认幂等」，读同一份数据两次不产生额外业务副作用。"
  (ok! (check (with-source-policy base {:timeout_ms 3000 :max_retries 1
                                        :retry_on #{:connection}}))))

(deftest e6-2-a-mutation-with-retry-and-no-idempotency-is-refused
  "**退出条件 3（二）**：mutation 带 retry 且**未声明**幂等 → 拒绝。
   mutation 默认非幂等，未声明即不 retry，失败方向朝关闭（D6.4）。"
  (let [m (-> base (with-source-policy {:timeout_ms 3000 :max_retries 1}) (with-mutation {}))
        r (check m)]
    (is (contains? (codes r) :cut.resilience/mutation-idempotency-unknown))
    (is (not-any? #(re-find #"UPDATE" (pr-str %)) (:findings r))
        "拒绝不泄漏语句原文（finding-v1 §5）")))

(deftest e6-2-a-mutation-with-retry-and-a-declared-idempotency-passes
  "**退出条件 3（三）**：显式声明自然幂等，或携带被强制执行的 idempotency key。"
  (let [pol {:timeout_ms 3000 :max_retries 1}]
    (ok! (check (-> base (with-source-policy pol)
                    (with-mutation {:idempotency :natural}))))
    (ok! (check (-> base (with-source-policy pol)
                    (with-mutation {:idempotency {:key :x.a/id}}))))))

(deftest e6-2-retry-on-at-the-source-layer-does-not-declare-idempotency
  "**退出条件 3 的第四条**（E6.2 逐字）：`retry_on` 定义可重试的**故障类别**，
   **source 层不能替 mutation 声明幂等**。只在 source 上写了 `:retry_on`、
   mutation 自己没声明的模型，照样拒绝——这一处最容易被读成「我已经配过了」。"
  (let [m (-> base
              (with-source-policy {:timeout_ms 3000 :retry_on #{:connection :rate_limited}})
              (with-mutation {}))
        r (check m)
        f (first (filter #(= :cut.resilience/mutation-idempotency-unknown (:code %))
                         (:findings r)))]
    (is (some? f))
    (is (re-find #":retry_on" (:message f))
        "消息点名是 source 层的 retry_on，因为那正是被误读的地方")
    (testing "同一个 policy 挂在 read resolver 上则通过"
      (ok! (check (with-source-policy base {:timeout_ms 3000
                                            :retry_on #{:connection}}))))))

;; ---------------------------------------------------------------------------
;; 体检结论随行（D4.1 / D4.9）
;; ---------------------------------------------------------------------------

(deftest health-block-is-the-seal-input-key-face
  "体检结论**封进制品**（D4.1：server 没有池，它无法重算这些结论）。
   `:health` 的键面必须正是 `seal-graph` 收的那一份，否则密封会拒。"
  (let [{:keys [health]} (check base)]
    (is (= #{:findings :carried-only-keys :row-security-declared :row-security-verified}
           (set (keys health))))))

(deftest no-coverage-metric-ever-reaches-the-refusal-channel
  "D2.11 把「拒绝」与「展示」分成两栏，**两栏走两条路**（README 裁定 ③）。
   本模块的 code 注册表里一条覆盖率都不许有——它若出现，就说明有人把展示
   接回了拒绝通道。"
  (is (not-any? #(re-find #"coverage|quality|metric" (str %)) version/finding-codes)))

;; ---------------------------------------------------------------------------
;; 行级安全：声明与接线核对（E1 / D5.14，owner 2026-09-03）
;; ---------------------------------------------------------------------------
;;
;; **三张最小图，不动 `fixtures/demo`**：demo 语料是 demo 模型的家，不是 E1 的
;; 试验台，而这三张图的全部意义就是「接线对 / 属性不在 :input / 在 :input 但语句
;; 没绑」这三种形状。

(defn- rowsec-model
  "接线正确的最小图：`:tenant_id` 既是 `:input`，语句也按它绑。"
  [{:keys [input query row-security source-type]}]
  (read!
   {:attributes {:r.o/id        {:type :integer :format :id :identity? true :label "O"}
                 :r.o/tenant_id {:type :string :format :text :label "Tenant"}}
    :namespaces {"r.o" {:kind :master :label "O"}}
    :sources    {"db" {:type (or source-type "sql") :label "DB"
                       :jdbc_url "jdbc:sqlite:memory"}}
    :resolvers  [{:id "o_by_id"
                  :input (or input [:r.o/id :r.o/tenant_id])
                  :output [:r.o/id :r.o/tenant_id]
                  :result_type "single"
                  :source {:type (or source-type "sql")
                           :query (or query (str "SELECT id, tenant_id FROM orders "
                                                 "WHERE id = :id AND tenant_id = :tenant_id"))}
                  :source-ref "db"
                  :row-security (or row-security {:attribute :r.o/tenant_id})}]}))

(deftest wired-row-security-cuts-and-is-listed-as-verified
  (testing "声明点名的属性是 `:input`、语句也绑了它 → 切得出版本，且它进
            `:row-security-verified`（PR 2 的门禁读的正是这一项）"
    (let [r (ok! (check (rowsec-model {})))]
      (is (= ["resolver:o_by_id"] (:row-security-declared (:health r))))
      (is (= ["resolver:o_by_id"] (:row-security-verified (:health r)))))))

(deftest row-security-on-a-non-input-is-refused
  (testing "声明「按租户切分」而 `:tenant_id` 不在 `:input` 里——平台唯一真在
            执法的那条接线根本没接上。**这比没有声明更危险**（D5.14）"
    (let [r (check (rowsec-model {:input [:r.o/id]
                                  :query "SELECT id, tenant_id FROM orders WHERE id = :id"}))
          f (first (filter #(= :rowsec.wiring/not-an-input (:code %)) (:findings r)))]
      (is (some? f))
      (is (= :error (:severity f)))
      (is (= [:resolvers "o_by_id" :row-security] (:path f)) "点名到那个 resolver")
      (is (= :r.o/tenant_id (:got f)))
      (is (every? version/finding? (:findings r)))
      (testing "拒绝切版本，且它不在 verified 里"
        (is (some #(= :error (:severity %)) (:findings r)))
        (is (= [] (:row-security-verified (:health r))))
        (is (= ["resolver:o_by_id"] (:row-security-declared (:health r)))
            "报告仍要列名——`:row-security-declared` 是「声明过的」，不是「没过的」")))))

(deftest row-security-not-bound-by-the-statement-is-refused
  (testing "属性在 `:input` 里，语句却不绑它：resolver 跑得起来，SELECT 是全表,
            每个调用方读到每个租户的行"
    (let [r (check (rowsec-model {:query "SELECT id, tenant_id FROM orders WHERE id = :id"}))
          f (first (filter #(= :rowsec.wiring/not-bound (:code %)) (:findings r)))]
      (is (some? f))
      (is (= :error (:severity f)))
      (is (= [:resolvers "o_by_id" :row-security] (:path f)))
      (is (= :r.o/tenant_id (:got f)))
      (is (= [] (:row-security-verified (:health r)))))))

(deftest a-finding-never-carries-the-statement-text
  (testing "finding-v1 §5：拒绝点名位置，不搬语句文本出来"
    (let [r (check (rowsec-model {:query "SELECT secret_column FROM orders WHERE id = :id"}))]
      (is (not-any? #(re-find #"secret_column" (pr-str %)) (:findings r))))))

(deftest a-source-type-with-no-connector-is-an-assembly-gap-not-a-model-fault
  (testing "本机没装该 type 的连接器 → `:rowsec.wiring/no-connector`。**这不是
            unverifiable**：一个是「没人来答」（改装配），一个是「答的人说不出」
            （改连接器），两件事两个人修。整条流水线上没有别处会注意到连接器
            缺席——切版本从不看 source type，engine 只把那条 resolver 当
            `:warning` 悄悄剔除。

            注册表由 opts 给，不靠「本次 JVM 恰好 require 了谁」——单模块跑与
            全量聚合跑装的连接器不是同一批，靠环境的测试会随跑法变结论"
    (let [r (check (rowsec-model {}) {:registered-source-types #{"api"}})
          f (first (filter #(= :rowsec.wiring/no-connector (:code %)) (:findings r)))]
      (is (some? f))
      (is (= :error (:severity f)))
      (is (= "sql" (:got f)) "点名是哪个 type 没有连接器")
      (is (= ["api"] (:expected f)) "以及这台机器上装了哪些——运维照着这个修")
      (is (not (contains? (codes r) :rowsec.wiring/unverifiable)))
      (is (= [] (:row-security-verified (:health r)))))))

(deftest a-registered-connector-that-cannot-answer-is-unverifiable
  (testing "连接器在场、但答不出自己绑了什么 → `:rowsec.wiring/unverifiable`。
            既不当作「已绑」放行，也不当作「没绑」报 not-bound——后者的消息会
            把作者指去改一条其实写对了的语句"
    (let [r (check (rowsec-model {})
                   {:registered-source-types #{"sql"}
                    :bound-attributes (constantly nil)})
          cs (codes r)]
      (is (contains? cs :rowsec.wiring/unverifiable))
      (is (not (contains? cs :rowsec.wiring/not-bound)))
      (is (not (contains? cs :rowsec.wiring/no-connector)))
      (is (= [] (:row-security-verified (:health r)))))))

(deftest the-connector-registry-is-an-input-not-ambient-state
  (testing "体检自称是纯函数（跑在投影产物 + 闭包上）。「这台机器装了哪些
            连接器」是宿主配置,由 opts 供给、缺省才回落到 SPI 的分派表——
            否则同一份模型在两台机器上体检出不同结论，而没有任何入参说明
            这一点。同 `:timeout-enforcing-types` 的处理"
    (let [m (rowsec-model {})]
      (is (empty? (filter #(= :error (:severity %)) (:findings (check m))))
          "缺省回落到 SPI 的分派表，而本模块的测试 classpath 上装了 sql")
      (is (contains? (codes (check m {:registered-source-types #{}}))
                     :rowsec.wiring/no-connector)
          "给一张空注册表，同一份模型就切不出来了——这正是「体检依赖宿主」
           这件事，写在入参里而不是藏在环境里"))))

(deftest an-eql-param-binding-does-not-satisfy-row-security
  (testing "D5.6 共识 2（D5.14 维持）：行级安全绝不绑调用方提供的值——
            「调用方自己挑的谓词等于没有谓词」。语句里 `:tenant_id` 绑的是一个
            eql-param 而不是 `:input` 属性时，核对必须**不通过**"
    (let [m (read!
             {:attributes {:r.o/id        {:type :integer :format :id :identity? true :label "O"}
                           :r.o/tenant_id {:type :string :format :text :label "Tenant"}}
              :namespaces {"r.o" {:kind :master :label "O"}}
              :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
              :resolvers  [{:id "o_by_id"
                            :input [:r.o/id :r.o/tenant_id]
                            :output [:r.o/id :r.o/tenant_id]
                            :result_type "single"
                            :eql-params {:tenant_id {:attribute :r.o/tenant_id}}
                            :source {:type "sql"
                                     :params {:tenant_id :tenant_id}
                                     :query (str "SELECT id, tenant_id FROM orders "
                                                 "WHERE id = :id AND tenant_id = :tenant_id")}
                            :source-ref "db"
                            :row-security {:attribute :r.o/tenant_id}}]})]
      (is (contains? (codes (check m)) :rowsec.wiring/not-bound)))))

(deftest a-graph-without-declarations-is-untouched
  (testing "没有 `:row-security` 声明的图行为不变——核对只看有声明的"
    (let [r (ok! (check base))]
      (is (= [] (:row-security-declared (:health r))))
      (is (= [] (:row-security-verified (:health r)))))))
