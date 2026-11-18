(ns open.atlas.studio.graph.projection-test
  "**scope projection v1 的 conformance 矩阵**（docs/contracts/scope-projection-v1.md §3）。
   矩阵每一行至少一条测试，行与测试的对应写在每个 deftest 的 docstring 第一行。

   重心是最后一行——`byte-identical-under-shuffled-everything`：打乱池返回顺序、
   selector 顺序与引用顺序，**输出字节及 content-hash 不变**。它挡的是「今天恰好
   排对了」，所以打乱是真打乱：对象集合 shuffle 之后重建一个新池（Datahike 的
   entity id 与返回顺序随之变化），selector 与引用清单用不同排列。"
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.pool :as pool]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

;; ---------------------------------------------------------------------------
;; 装料：一份手写的小模型（矩阵要的边界情形都在里面）
;; ---------------------------------------------------------------------------

(def local-edn
  "池快照的内容。**经 `read-dialect` 过一遍**——投影的输入必须是合法方言，
   手搓一个非法模型去测投影，测到的是 writer 的断言不是契约。

   刻意安排的几处：
   - `sales.order` 与 `sales.ship` 之间有一条 link（单选一个 namespace 即悬空）；
   - `order_by_cust` 的 input 在 `core.customer`（本地不可用，须由引用带入）；
   - `order_with_ship` 的 output 跨两个 namespace（单选一个即 mixed-output）；
   - `sales.note` 没有任何 link/resolver（精确 selector 的干净样本）；
   - `common.terms/spare` 与 `common.audit/g_spare` 无人引用（不进闭包）。"
  (pr-str
   {:attributes
    {:sales.order/id      {:type :integer :format :id :identity? true :label "Order No"}
     :sales.order/total   {:type :decimal :unit "common.units/usd"
                           :term "common.terms/amount"}
     :sales.order/status  {:type :string :format :code :label "Status"
                           :values {"N" {:label "New"}}}
     :sales.order/cust_id {:type :integer :format :id :label "Customer"}
     :sales.ship/id       {:type :integer :format :id :identity? true :label "Ship No"}
     :sales.ship/order_id {:type :integer :format :id :label "Order"}
     ;; 跨 namespace 的 join 属性（D2.29）：单选 sales.ship 时目标不在图里。
     :sales.ship/order    {:target "sales.order" :cardinality :one-to-many :label "Order"}
     :sales.note/id       {:type :integer :format :id :identity? true :label "Note No"}
     :sales.note/text     {:type :string :format :text :label "Text"}
     :core.customer/id    {:type :integer :format :id :identity? true :label "Customer No"}
     :core.customer/name  {:type :string :format :text :label "Customer Name"}}
    :namespaces
    {"sales.order" {:kind :transaction :label "Order"}
     "sales.ship"  {:kind :transaction :label "Shipment"}
     "sales.note"  {:kind :reference :label "Note"}
     "core.customer" {:kind :master :label "Customer"}}
    :units  {"common.units/usd" {:label "USD"}}
    :terms  {"common.terms/amount" {:label "Amount" :format :currency}
             "common.terms/spare"  {:label "Spare"}
             ;; 词表域前缀 selector 用例（registry-prefix-selector）圈的第二个域。
             "common.audit/note"   {:label "Note"}
             "common.audit/spare"  {:label "Spare"}}
    :links  [{:from :sales.ship/order_id :to :sales.order/id :cardinality :one-to-many}]
    :metrics {"sales.order/revenue"
              {:entity "sales.order"
               :expression {:contract :duckdb-spill/v1 :text "sum(total)"}
               :dimensions [:sales.order/status]}}
    :sources {"sales-db" {:type "sql" :label "Sales DB" :jdbc_url "jdbc:sqlite:memory"}}
    :resolvers
    [{:id "order_by_id" :input [:sales.order/id]
      :output [:sales.order/total :sales.order/status :sales.order/cust_id]
      :result_type "single" :source {:type "sql" :query "SELECT 1"} :source-ref "sales-db"}
     {:id "order_by_cust" :input [:core.customer/id] :output [:sales.order/id]
      :result_type "list" :limit {:default 50 :max 500}
      :source {:type "sql" :query "SELECT 2"} :source-ref "sales-db"}
     {:id "order_with_ship" :input [:sales.order/id]
      :output [:sales.order/status :sales.ship/id]
      :result_type "single" :source {:type "sql" :query "SELECT 3"} :source-ref "sales-db"}
     {:id "note_by_id" :input [:sales.note/id] :output [:sales.note/text]
      :result_type "single" :source {:type "sql" :query "SELECT 4"} :source-ref "sales-db"}]}))

(def core-edn
  "被引用的 core graph 的内容（`content.edn` 里的 `:objects`）。"
  (pr-str
   {:attributes {:core.customer/id   {:type :integer :format :id :identity? true
                                      :label "Customer No"}
                 :core.customer/name {:type :string :format :text :label "Customer Name"}}
    :namespaces {"core.customer" {:kind :master :label "Customer"}}}))

(defn- read! [s]
  (let [{:keys [model findings]} (dialect/read-dialect s)]
    (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    model))

(def ^:dynamic *conn* nil)
(def ^:dynamic *cfg* nil)

(defn- objects-of [model]
  (mapv #(assoc % :pool/revision 1 :pool/workspace "demo")
        (pool/dialect->objects model)))

(defn- open-pool-with!
  "把一组池对象装进一个新池（顺序由调用方给定——乱序测试正靠这个入口）。"
  [objects]
  (let [cfg  (pool/config {:backend :mem :id (str (gensym "proj"))})
        _    (pool/delete-pool! cfg)
        conn (graph/install! (pool/open-pool! cfg))
        r    (pool/import-pool! conn {:pool/version 1 :pool/objects (vec objects)})]
    (assert (empty? (:findings r)) (pr-str (:findings r)))
    [cfg conn]))

(defn- register-baseline!
  "两个 workspace。地盘登记已随 D3.17 / D3.22 退役——投影只按菜谱走。"
  [conn]
  (doseq [r [(graph/register-workspace! conn {:name "sales" :members [{:principal {:issuer "test-idp" :subject "a"} :role :owner}]})
             (graph/register-workspace! conn {:name "core-team" :members [{:principal {:issuer "test-idp" :subject "b"} :role :owner}]})]]
    (assert (empty? (:findings r)) (pr-str (:findings r)))))

(defn with-pool [f]
  (let [[cfg conn] (open-pool-with! (objects-of (read! local-edn)))]
    (register-baseline! conn)
    (binding [*conn* conn *cfg* cfg]
      (try (f) (finally (pool/close-pool! conn) (pool/delete-pool! cfg))))))

(use-fixtures :each with-pool)

;; ---------------------------------------------------------------------------
;; 共用：登记一张图、造引用
;; ---------------------------------------------------------------------------

(defn- members-of
  "「装下 `sales.*` 的一切」在 D3.19 之后仍然说得出来，只是**展开成清单**。
   这就是界面上那个批量加入动作在测试里的样子：前缀在**这一刻**展开,
   展开之后菜谱里只有对象。"
  [selectors]
  (let [model    (pool/as-dialect (pool/db *conn*))
        ns-names (into #{} (filter (fn [n] (some #(graph/selector-matches? % n) selectors))
                                   (keys (:namespaces model))))
        reg      (fn [kind] (into #{} (filter (fn [id] (some #(graph/selector-matches? % id) selectors))
                                              (keys (get model kind)))))]
    {:namespaces ns-names
     :attributes (into #{} (filter #(contains? ns-names (namespace %)))
                       (keys (:attributes model)))
     :units (reg :units)
     :terms (reg :terms)}))

(defn- graph!
  ([selectors] (graph! "sales" "core" selectors))
  ([ws nm selectors]
   (let [r (graph/register-graph! *conn* {:workspace ws :name nm
                                          :members (members-of selectors)})]
     (assert (empty? (:findings r)) (pr-str (:findings r)))
     (graph/qualified-name ws nm))))

(defn- graph-with-members!
  "直接给清单——测清单本身的那几条用它。"
  ([members] (graph-with-members! "sales" "core" members))
  ([ws nm members]
   (let [r (graph/register-graph! *conn* {:workspace ws :name nm :members members})]
     (assert (empty? (:findings r)) (pr-str (:findings r)))
     (graph/qualified-name ws nm))))




(defn- db [] (pool/db *conn*))

(defn- project!
  "先登记、后取快照——**参数求值顺序不该决定测试测的是什么**：
   `(project (db) (graph! …))` 会在登记之前就把快照读走。"
  [qn] (graph/project (pool/db *conn*) qn))
(defn- codes [r] (set (map :code (:findings r))))
(defn- ok! [r] (is (empty? (:findings r)) (pr-str (:findings r))) r)


;; ---------------------------------------------------------------------------
;; 矩阵第 1 行：清单选中什么、点名不存在的东西、集合语义
;; ---------------------------------------------------------------------------

(deftest a-listed-namespace-brings-exactly-its-own
  "矩阵 1：清单列一个 namespace。"
  (let [qn (graph! ["sales.note"])
        r  (ok! (project! qn))]
    (is (= #{"sales.note"} (set (keys (:namespaces (:model r))))))
    (is (= #{:sales.note/id :sales.note/text} (set (keys (:attributes (:model r))))))
    (is (= ["note_by_id"] (mapv :id (:resolvers (:model r))))
        "只有 output 全在本地所选属性里的那一个进来（第 4 步）")
    (is (nil? (:links (:model r))) "跨 namespace 的 link 与本图无关，不进也不报错")))

(deftest a-listed-set-of-namespaces-brings-all-of-them
  "矩阵 1：清单可以列若干 namespace。**`core.customer` 直接列进来即可**——
   workspace 是封闭世界（D3.17），池里的对象都是自己的，没有「只能经引用进来」
   这回事了。"
  (let [qn (graph! ["sales.*" "core.customer"])
        r  (ok! (project! qn))]
    (is (= #{"sales.order" "sales.ship" "sales.note" "core.customer"}
           (set (keys (:namespaces (:model r))))))))

(deftest a-member-that-is-not-in-the-pool-is-rejected
  "矩阵 1：清单点名了池里没有的对象 → **拒绝，不静默跳过**（第 1 步）。

   「我列了它却没出现在结果里」与「我根本没列它」必须是两句不同的话——
   静默跳过会让一张图在某个对象被删之后悄悄少掉一块。"
  (let [qn (graph-with-members! {:namespaces #{"sales.note" "sales.ghost"}})
        r  (project! qn)]
    (is (nil? (:model r)) "错误与产物互斥")
    (is (= #{:scope/unknown-member} (codes r)))
    (is (every? graph/finding? (:findings r))))
  (testing "属性同理"
    (is (= #{:scope/unknown-member}
           (codes (project! (graph-with-members! "sales" "g2"
                                                 {:attributes #{:sales.note/nope}})))))))

(deftest the-recipe-is-a-set-not-a-sequence
  "矩阵 1：清单是**集合语义**——重复与书写顺序不进结果（§2.10）。"
  (let [a (ok! (project! (graph! ["sales.note"])))
        b (ok! (project! (graph-with-members!
                          "sales" "core2"
                          {:namespaces #{"sales.note"}
                           :attributes #{:sales.note/text :sales.note/id}})))]
    (is (= (:edn a) (:edn b)))))


;; ---------------------------------------------------------------------------
;; 矩阵第 2 行：输入闭合 / 缺 input / 混合 output
;; ---------------------------------------------------------------------------

(deftest local-input-closure
  "矩阵 2：本地输入闭合——`note_by_id` 的 input 就在本地所选属性里。"
  (let [r (ok! (project! (graph! ["sales.note"])))]
    (is (= ["note_by_id"] (mapv :id (:resolvers (:model r)))))))

(deftest missing-input-is-rejected
  "矩阵 2：缺 input → `:scope/unclosed-input`（逐字，不许改名）。"
  (let [r (project! (graph! ["sales.*"]))]
    (is (contains? (codes r) :scope/unclosed-input))
    (is (nil? (:model r)))))


(deftest mixed-output-is-rejected-never-trimmed
  "矩阵 2：混合 output → `:scope/mixed-output`，**绝不删掉部分 output 后纳入**。"
  (let [r (project! (graph! ["sales.order"]))]
    (is (contains? (codes r) :scope/mixed-output))
    (is (nil? (:model r)) "拒绝就不产物——半个 resolver 一个字节都不许出去")
    ;; 8.4.21 裁定十五 ②：首句说人话。引擎/投影的词汇（join 根、投影、D 编号）
    ;; 留在 `:refs` 与文档里——只会转述这一句的 agent 必须说得出哪里错了。
    (let [msg (:message (first (filter #(= :scope/mixed-output (:code %)) (:findings r))))
          head (clojure.string/lower-case (first (clojure.string/split msg #"(?<=\.)\s")))]
      (doseq [w ["join root" "projection" "d2."]]
        (is (not (clojure.string/includes? head w)) (str head " 里出现了 " w))))))

;; ---------------------------------------------------------------------------
;; 矩阵第 3 行：source / link / 三张注册表的最小闭包与悬空错误
;; ---------------------------------------------------------------------------

(deftest source-closure-is-by-reference-only
  "矩阵 3：source 最小闭包——只有被纳入执行对象引用的 source 进来。"
  (let [r (ok! (project! (graph! ["sales.note"])))]
    (is (= #{"sales-db"} (set (keys (:sources (:model r))))))))

(deftest dangling-source-is-rejected
  "矩阵 3：source 悬空 → `:scope/unclosed-source`。

   **裁量点**：健康的池里做不出这个形态——池的删除会先点名 4 个 resolver 把
   `delete source` 拒掉（`:pool.reference/inbound`）。所以这里装的是一份**缺了
   source 对象**的快照：第 5 步是防守性的一道，它挡的是快照本身残缺（导入了半份
   备份、或将来按投影范围拼校验时漏了一类），而不是一次正常的建模动作。"
  (let [objs (remove #(= :source (:pool/type %)) (objects-of (read! local-edn)))
        [cfg conn] (open-pool-with! objs)]
    (try
      (register-baseline! conn)
      (assert (empty? (:findings (graph/register-graph!
                                  conn {:workspace "sales" :name "core"
                                        :members {:namespaces #{"sales.note"}
                                                  :attributes #{:sales.note/id :sales.note/text}}}))))
      (let [r (graph/project (pool/db conn) "sales/core")]
        (is (contains? (set (map :code (:findings r))) :scope/unclosed-source)))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest unclosed-link-is-rejected
  "矩阵 3：link 一端缺失 → `:scope/unclosed-link`，不得生成悬空 link。"
  (let [r (project! (graph! ["sales.ship"]))]
    (is (contains? (codes r) :scope/unclosed-link))))

(deftest unclosed-join-target-is-rejected
  "**契约 §2 没写 join**（十步里只有 link）。这里按 link 的同一条规则处理:
   join 属性的 `:target` namespace 不在投影里就拒绝，而不是悄悄删掉那条属性——
   静默删掉是唯一确定不对的选项。这一处已在报告里交 owner 裁定。

   D2.29 之前同一条判定作用在 namespace 上的 navigation，code 叫
   `:scope/unclosed-navigation`。"
  (let [r (project! (graph! ["sales.ship"]))]
    (is (contains? (codes r) :scope/unclosed-join))))

(deftest registry-closure-is-by-reference-only
  "矩阵 3：unit / term 两张表的最小闭包——**引用即入闭包，未被引用的不进**。
   这里的「引用」是属性上的 `:unit`/`:term`，不是 graph 之间的引用（那个已退役）。"
  (let [r (ok! (project! (graph! ["sales.*" "core.customer"])))
        m (:model r)]
    (is (= #{"common.units/usd"} (set (keys (:units m)))))
    (is (= #{"common.terms/amount"} (set (keys (:terms m))))
        "`common.terms/spare` 无人引用，不进这张图")))

(deftest a-dictionary-graph-lists-its-terms-directly
  "矩阵 3：字典 graph 把自己域里的词条**直接列进清单**，包括没人引用的那些
   （第 7 步）。前缀圈选是界面上的动作，展开之后菜谱里只有词条 ID。"
  (testing "整域：`common.terms/*` 展开成两条"
    (let [r (ok! (project! (graph! "core-team" "dict" ["common.terms/*" "common.audit/*"])))]
      (is (= #{"common.terms/amount" "common.terms/spare"
               "common.audit/note" "common.audit/spare"}
             (set (keys (:terms (:model r))))))))
  (testing "只列一条"
    (let [r (ok! (project! (graph-with-members! "core-team" "dict2"
                                                {:terms #{"common.terms/spare"}})))]
      (is (= #{"common.terms/spare"} (set (keys (:terms (:model r))))))))
  (testing "列一个不存在的词条 → :scope/unknown-member"
    (is (= #{:scope/unknown-member}
           (codes (project! (graph-with-members! "core-team" "dict3"
                                                 {:terms #{"common.void/nope"}})))))))


;; ---------------------------------------------------------------------------
;; 矩阵第 4 行：metric
;; ---------------------------------------------------------------------------

(deftest metric-follows-its-entity
  "矩阵 4：metric 随 `:entity` 的 namespace 纳入。"
  (let [r (ok! (project! (graph! ["sales.*" "core.customer"])))]
    (is (= #{"sales.order/revenue"} (set (keys (:metrics (:model r))))))
    (is (nil? (:metrics (:model (ok! (project! (graph! "sales" "notes" ["sales.note"]))))))
        "entity 不在本图，metric 不进")))

(deftest metric-with-a-missing-dimension-is-rejected
  "矩阵 4：dimensions 缺失 → `:scope/unclosed-metric`。"
  (ok! (pool/execute-action
        *conn* {:workspace "demo" :action :update :type :metric :id "sales.order/revenue"
                :entry {:entity "sales.order"
                        :expression {:contract :duckdb-spill/v1 :text "sum(total)"}
                        :dimensions [:sales.order/status :core.customer/name]}
                :expected_revision 1}))
  (let [r (project! (graph! ["sales.order" "sales.ship"]))]
    (is (contains? (codes r) :scope/unclosed-metric))))

;; ---------------------------------------------------------------------------
;; 矩阵第 5 行：多路径引用的上限与版本冲突
;; ---------------------------------------------------------------------------








;; ---------------------------------------------------------------------------
;; 矩阵第 6 行：重复与冲突
;; ---------------------------------------------------------------------------



;; ---------------------------------------------------------------------------
;; 矩阵第 7 行（重心）：打乱一切，输出字节与 content-hash 不变
;; ---------------------------------------------------------------------------

(deftest byte-identical-under-shuffled-everything
  "矩阵 7：**打乱池返回顺序与清单排列，输出字节及 content-hash 不变。**

   打乱是真打乱：对象集合 shuffle 之后**重建一个新池**（Datahike 的 entity id
   与返回顺序随之变化），菜谱用不同排列喂进去——而菜谱是集合语义，排列不该
   进结果（§2.10）。"
  (let [objects (objects-of (read! local-edn))
        members {:namespaces #{"sales.order" "sales.ship" "sales.note" "core.customer"}
                 :attributes #{:sales.order/id :sales.order/total :sales.order/status
                               :sales.order/cust_id :sales.ship/id :sales.ship/order_id
                               :sales.ship/order
                               :sales.note/id :sales.note/text
                               :core.customer/id :core.customer/name}}
        run! (fn [objs ms]
               (let [[cfg conn] (open-pool-with! objs)]
                 (try
                   (register-baseline! conn)
                   (let [r (graph/register-graph!
                            conn {:workspace "sales" :name "core" :members ms})]
                     (assert (empty? (:findings r)) (pr-str (:findings r))))
                   (let [r (graph/project (pool/db conn) "sales/core")]
                     (assert (empty? (:findings r)) (pr-str (:findings r)))
                     (let [{:keys [content-hash]} (artifact/encode-content (:model r) [])]
                       {:edn (:edn r) :hash content-hash :refs (:references r)}))
                   (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))
        ;; 同一份清单，三种写法：集合、打乱成向量、反序向量。
        as-vec  (fn [ms] (into {} (for [[k v] ms] [k (vec (shuffle (vec v)))])))
        a (run! objects members)
        b (run! (shuffle objects) (as-vec members))
        c (run! (shuffle objects) (into {} (for [[k v] members] [k (reverse (sort-by pr-str v))])))]
    (is (= (:edn a) (:edn b) (:edn c)) "输出字节不变")
    (is (= (:hash a) (:hash b) (:hash c)) "content-hash 不变")
    (is (= [] (:refs a) (:refs b) (:refs c))
        "引用清单恒为空，且是空清单而不是消失的键（D3.17）")))

;; ---------------------------------------------------------------------------
;; 契约 §1 的那句话本身可测：纯函数
;; ---------------------------------------------------------------------------

(deftest projection-is-a-pure-function
  "契约 §1：**实现不得读取 latest、当前时间或池快照之外的状态。**

   两条断言：(a) 同一快照跑两次逐字节相同；(b) 实现源码里没有时钟/随机数/
   环境变量的读取——「纯」在这里不是风格，是产物可复现的前提。"
  (let [qn (graph! ["sales.note"])
        a  (project! qn)
        b  (project! qn)]
    (is (= (:edn a) (:edn b))))
  (let [src (slurp (io/resource "open/atlas/studio/graph/impl/projection.clj"))
        banned ["System/currentTimeMillis" "(java.util.Date." "java.time.Instant/now"
                "System/getenv" "System/getProperty" "rand-int" "(rand)" "shuffle"
                "d/db " "(slurp"]]
    (doseq [b banned]
      (is (not (str/includes? src b))
          (str "impl.projection 里出现了快照之外的状态：" b)))))

;; ---------------------------------------------------------------------------
;; demo 模型：端到端
;; ---------------------------------------------------------------------------

(defonce ^{:doc "demo 模型。`fixtures/demo/model.edn` 是它的家（见那里的 README）。"}
  demo-model
  (delay (edn/read-string (slurp (io/resource "model.edn")))))

(deftest demo-projection-equals-as-dialect-when-everything-is-selected
  "**demo 模型端到端**：装进池、登记 graph、投影出来，与直接
   `as-dialect` 的结果对得上（选全部 namespace 与全部词表域时应当等价），并过 B0。

   玩具模型证不出这条：188 个属性、23 个词条、35 个 resolver 指着
   同一个 source——第 4、5、7 步要判的正是这些。"
  (let [model @demo-model
        [cfg conn] (open-pool-with! (objects-of model))]
    (try
      (let [check! (fn [r] (assert (empty? (:findings r)) (pr-str (:findings r))))]
        (check! (graph/register-workspace!
                 conn {:name "cis" :members [{:principal {:issuer "test-idp" :subject "a"} :role :owner}]}))
        (check! (graph/register-graph! conn {:workspace "cis" :name "all"
                                               :members (let [m (pool/as-dialect (pool/db conn))]
                                                          {:namespaces (set (keys (:namespaces m)))
                                                           :attributes (set (keys (:attributes m)))
                                                           :units (set (keys (:units m)))
                                                           :terms (set (keys (:terms m)))
                                                           :metrics (set (keys (:metrics m)))
                                                           :resolvers (set (map :id (:resolvers m)))
                                                           :mutations (set (map :id (:mutations m)))
                                                           :links (set (map (juxt :from :to) (:links m)))})}))
        (let [r (graph/project (pool/db conn) "cis/all")]
          (is (empty? (:findings r)) (pr-str (take 5 (:findings r))))
          (is (= (dialect/write-dialect (pool/as-dialect (pool/db conn))) (:edn r))
              "选全部 namespace 与词表域时，投影 ≡ as-dialect")
          (is (empty? (filter #(= :error (:severity %))
                              (:findings (dialect/read-dialect (:edn r)))))
              "投影产物过 B0 的结构与不变量校验")))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))
