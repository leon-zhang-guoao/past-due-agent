(ns open.atlas.studio.version.cut-test
  "**切版本 = 投影 → 闭包 → 体检 → 密封**，以及 B5b.1 的三条退出条件。

   - **退出条件 1**：体检任一类不过 → **切不出版本，且一个字节的制品都没写**;
   - **退出条件 2**：钻石冲突被拒绝（`closure_test` 逐条测判定本身，这里测的是
     它**真的挡在密封之前**）；
   - **退出条件 3**：E6.2 的三条 + `retry_on` 那一条（`health_test` 测判定，
     这里测其中两条走到 `cut-version!` 也拦得住）。

   端到端装料用 **demo 模型**（`fixtures/demo/model.edn`）：一百多个属性、
   三十多个 resolver 指着同一个 source，其中有一批是 join 属性（D2.29）——
   玩具模型证不出 D2.2 的存在性闭包对分支键判得对不对，而那一条判错会拒掉
   一份既成事实的模型。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.version :as version]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

;; ---------------------------------------------------------------------------
;; 装料
;; ---------------------------------------------------------------------------

(def base-model
  "干净的基线模型：两个属性都在 `:output` 里，一个 sql source，一个 single
   resolver。每条不过的测试都从它派生**一处**改动——两处改动会让「是哪条判定
   拦住的」不可判。"
  {:attributes {:s.a/id   {:type :integer :format :id :identity? true :label "A"}
                :s.a/name {:type :string :format :text :label "Name"}}
   :namespaces {"s.a" {:kind :master :label "A"}}
   :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
   :resolvers  [{:id "a_by_id" :input [:s.a/id] :output [:s.a/id :s.a/name]
                 :result_type "single"
                 :source {:type "sql" :query "SELECT 1"} :source-ref "db"}]})

(defn- objects-of [model]
  ;; `pool/dialect->objects` is the public door; this used to be `#'` into
  ;; `pool.impl.model`, which pierced both the module boundary and the var's
  ;; privacy — and did it without requiring that ns at all, so it only worked
  ;; while `pool` happened to load it transitively.
  (mapv #(assoc % :pool/revision 1 :pool/workspace "demo") (pool/dialect->objects model)))

(defn- open-pool-with! [objects]
  (let [cfg  (pool/config {:backend :mem :id (str (gensym "cut"))})
        _    (pool/delete-pool! cfg)
        conn (version/install! (graph/install! (pool/open-pool! cfg)))
        r    (pool/import-pool! conn {:pool/version 1 :pool/objects (vec objects)})]
    (assert (empty? (:findings r)) (pr-str r))
    [cfg conn]))

(defn- check! [r] (assert (empty? (:findings r)) (pr-str r)) r)

(defn- register-sales!
  "登记 sales/core，菜谱 = 快照里 `s.*` 下的一切。`:except` 剔掉几个属性——
   给那些「删掉一个对象之后会怎样」的用例用：菜谱点名了才谈得上点名一个幽灵。"
  [conn & {:keys [except] :or {except #{}}}]
  (check! (graph/register-workspace! conn {:name "sales" :members [{:principal {:issuer "test-idp" :subject "a"} :role :owner}]}))
  ;; 菜谱是对象清单（D3.19）：把快照里 `s.*` 下的一切展开进去——界面上那个
  ;; 「按前缀批量加入」的动作，在测试里就是这一句。
  (let [m (pool/as-dialect (pool/db conn))
        ns-names (into #{} (filter #(clojure.string/starts-with? % "s.")) (keys (:namespaces m)))]
    (check! (graph/register-graph!
             conn {:workspace "sales" :name "core"
                   :members {:namespaces ns-names
                             :attributes (into #{} (comp (filter #(contains? ns-names (namespace %)))
                                                         (remove except))
                                               (keys (:attributes m)))
                             :units (set (keys (:units m)))
                             :terms (set (keys (:terms m)))
                             :metrics (set (keys (:metrics m)))
                             :resolvers (set (map :id (:resolvers m)))
                             :mutations (set (map :id (:mutations m)))
                             :links (set (map (juxt :from :to) (:links m)))}}))))

(defn- tmp-root [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix (into-array java.nio.file.attribute.FileAttribute []))))

(defn- cut!
  "切一版。`:cut-at` / `:cut-by` **显式传入**——本模块不读时钟、不读当前用户
   （D4.10-a），测试因此完整可复现。"
  [conn root & {:keys [graph resolved opts]
                :or   {graph "sales/core" resolved []}}]
  (version/cut-version! conn (cond-> {:graph graph :resolved resolved
                                      :artifact-root root
                                      :cut-at "2026-08-24T09:00:00Z" :cut-by "alice"}
                              opts (assoc :opts opts))))

(defn- empty-root?
  "一个字节的制品都没写——**整棵目录树里一个文件都没有**，不是「版本目录不存在」。
   后者会放过「写了一半又留了个空壳」的形态。"
  [root]
  (empty? (filter #(.isFile ^java.io.File %) (file-seq (io/file root)))))

(defn- codes [r] (set (map :code (:findings r))))

;; ---------------------------------------------------------------------------
;; 退出条件 1：体检不过 → 切不出版本，且一个字节都没写
;; ---------------------------------------------------------------------------

(defn- refuses-and-writes-nothing!
  "跑一次切版本，断言：拒绝、没有版本号、**没有版本登记**、**一个文件都没写**。"
  [model expect-code & {:keys [opts]}]
  (let [[cfg conn] (open-pool-with! (objects-of model))
        root (tmp-root "cut-refuse-")]
    (try
      (register-sales! conn)
      (let [r (cut! conn root :opts opts)]
        (is (nil? (:version r)) "错误与产物互斥（finding-v1 §2）")
        (is (contains? (codes r) expect-code) (pr-str (codes r)))
        (is (every? version/finding? (:findings r)))
        (is (empty? (version/versions (pool/db conn) "sales/core"))
            "版本登记也没有——拒绝不是「先记下再说这次不算」")
        (is (empty-root? root)
            "**一个字节的制品都没写**：密封是第四步，前三步不过就走不到它"))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

;; ---------------------------------------------------------------------------
;; preflight：候选态与基线的差分
;; ---------------------------------------------------------------------------

(defn- preflight! [conn action]
  (version/preflight conn {:graph "sales/core" :action action :resolved []}))

(def ^:private competing
  "与 `a_by_id` 争产出的第二个 `\"single\"` resolver —— D2.11 结构那一格。"
  {:workspace "demo" :action :create :type :resolver :id "a_again" :expected_revision 0
   :entry {:input [:s.a/id] :output [:s.a/name] :result_type "single"
           :source {:type "sql" :query "SELECT 2"} :source-ref "db"}})

(deftest an-illegal-projection-is-refused-before-any-bytes-are-written
  "**制品出生前的方言闸门。**

   投影用 `write-dialect` 产文本，而 writer 不校验键面——它逐字打印交给它的
   东西；`encode-content` 又把 `:local` 原样嵌进 `:objects`。真发生过的形态是
   `:security`：它从方言退役（D5.12）之后，一份仍带着它的模型会一路静默写进
   制品，直到**目标 server 装载时**才炸成 `:artifact.model/rejected`。

   这里用 with-redefs 伪造一个非法投影，因为池今天已经存不下退役键了（写入
   闸门 + schema + 读面三道）——而这道闸门正是为「万一还是漏过来了」存在的。
   要测纵深防御，就得从它防的那一层灌进来。"
  (let [[cfg conn] (open-pool-with! (objects-of base-model))
        root (tmp-root "cut-illegal")]
    (try
      (register-sales! conn)
      (let [real @#'graph/project
            r (with-redefs [graph/project
                            (fn [& args]
                              (let [p (apply real args)]
                                ;; 一处改动：把一个已退役的键塞进将被密封的文本。
                                (update p :local-edn
                                        #(str/replace % ":type :integer"
                                                      ":security {:roles [\"credit\"]} :type :integer"))))]
                (cut! conn root))]
        (is (nil? (:version r)) "非法投影不得切出版本")
        (is (seq (:findings r)) "拒绝必须是结构化的，不是静默通过")
        (is (some #(= :dialect.schema/unknown-key (:code %)) (:findings r))
            (str "拒绝要逐条点名是哪个键，用方言自己的 finding: "
                 (pr-str (mapv :code (:findings r)))))
        (is (empty-root? root)
            "**一个字节都不许写**：制品切出即不可变，一份非法制品上架之后它的坏是永久的"))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest cuttable-answers-the-question-preflight-cannot-be-asked
  "`preflight` **requires an action** — it answers «would THIS break the next
   cut». «Can this graph be cut at all» had no reader, so the only way to learn
   it was to attempt a cut and read the refusal: a write attempted as a
   question, on the one path where D2.2 leaves a person no way to look down
   before stepping."
  (let [[cfg conn] (open-pool-with! (objects-of base-model))]
    (try
      (register-sales! conn)
      (testing "a clean graph says so, with nothing to report"
        (let [r (version/cuttable conn {:graph "sales/core"})]
          (is (true? (:would-cut? r)))
          (is (empty? (:health-findings r)))
          (testing "and `:findings` stays empty — the health is the ANSWER, not
                    a refusal of this call"
            (is (empty? (:findings r))))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest cuttable-names-the-checks-that-refuse-without-being-asked-about-an-action
  (let [dirty (assoc-in base-model [:attributes :s.a/orphan]
                        {:type :string :format :text :label "Orphan"})
        [cfg conn] (open-pool-with! (objects-of dirty))]
    (try
      (register-sales! conn)
      (let [r (version/cuttable conn {:graph "sales/core"})]
        (is (false? (:would-cut? r)))
        (is (= [:cut.attribute/unregistered-use] (mapv :code (:health-findings r))))
        (testing "and it agrees with what preflight calls the graph's existing debt —
                  one assess, so the two cannot tell different stories"
          (is (= (mapv :code (:health-findings r))
                 (mapv :code (:existing-findings (preflight! conn competing)))))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest cuttable-writes-nothing
  (let [[cfg conn] (open-pool-with! (objects-of base-model))]
    (try
      (register-sales! conn)
      (let [before (pool/as-dialect (pool/db conn))]
        (version/cuttable conn {:graph "sales/core"})
        (is (= before (pool/as-dialect (pool/db conn)))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest cuttable-refuses-an-unregistered-graph-by-name
  (let [[cfg conn] (open-pool-with! (objects-of base-model))]
    (try
      (register-sales! conn)
      (let [r (version/cuttable conn {:graph "sales/nope"})]
        (is (false? (:would-cut? r)))
        (is (= [:cut.version/malformed] (mapv :code (:health-findings r)))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest preflight-names-what-this-action-introduces
  (let [[cfg conn] (open-pool-with! (objects-of base-model))]
    (try
      (register-sales! conn)
      (testing "干净基线上，一个争产出的 resolver → **切不出版本**，
                而且拒绝就指着这次动作"
        (let [r (preflight! conn competing)]
          (is (false? (:would-cut? r)))
          (is (= [:cut.structure/multiple-producers]
                 (mapv :code (:introduced-findings r))))
          (is (empty? (:existing-findings r)) "基线本来是干净的")
          (is (str/includes? (pr-str (:introduced-findings r)) "a_again")
              "点名两个竞争者，作者才知道该改哪一个")))

      (testing "**一个字节都没写**：preflight 是读，不是试写"
        (is (nil? (pool/object (pool/db conn) :resolver "a_again"))))

      (testing "无害的动作 → 没有引入任何东西，且仍然切得出去"
        (let [r (preflight! conn {:workspace "demo" :action :update :type :attribute :id :s.a/name
                                  :expected_revision 1
                                  :entry {:label "Renamed"}})]
          (is (true? (:would-cut? r)))
          (is (empty? (:introduced-findings r)))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest preflight-does-not-bill-this-action-for-the-graphs-existing-debt
  (testing "图上原来就有的毛病**不算在这次动作头上**——否则作者会以为
            「我刚把这些全弄坏了」，而那正是差分要防的误伤"
    (let [dirty (assoc-in base-model [:attributes :s.a/orphan]
                          {:type :string :format :text :label "Orphan"})
          [cfg conn] (open-pool-with! (objects-of dirty))]
      (try
        (register-sales! conn)
        (let [r (preflight! conn competing)]
          (testing "既有的漂移报在 :existing-findings，不在 :introduced"
            (is (= [:cut.attribute/unregistered-use]
                   (mapv :code (:existing-findings r))))
            (is (= [:cut.structure/multiple-producers]
                   (mapv :code (:introduced-findings r)))))
          (testing ":would-cut? 问的是候选态整体 —— 有旧债也切不出去"
            (is (false? (:would-cut? r)))))
        (finally (pool/close-pool! conn) (pool/delete-pool! cfg))))))

(deftest preflight-reports-what-an-action-fixes
  (testing "这次动作**修好**的东西也值得说：否则作者看不出自己解决了什么"
    (let [dirty (assoc-in base-model [:attributes :s.a/orphan]
                          {:type :string :format :text :label "Orphan"})
          [cfg conn] (open-pool-with! (objects-of dirty))]
      (try
        (register-sales! conn)
        ;; **动作是「给它接上一个产出者」，不是「把它删掉」**：菜谱是清单
        ;; （D3.19），删掉一个被点名的对象会让整张图投不出来（下一条测那个），
        ;; 于是「这次动作修好了什么」要用一个不动成员关系的动作来问。
        (let [r (preflight! conn {:workspace "demo" :action :update :type :resolver :id "a_by_id"
                                  :expected_revision 1
                                  :entry {:input [:s.a/id]
                                          :output [:s.a/id :s.a/name :s.a/orphan]
                                          :result_type "single"
                                          :source {:type "sql" :query "SELECT 1"}
                                          :source-ref "db"}})]
          (is (= [:cut.attribute/unregistered-use]
                 (mapv :code (:resolved-findings r))))
          (is (true? (:would-cut? r)) "债还清了就切得出去了"))
        (finally (pool/close-pool! conn) (pool/delete-pool! cfg))))))

(deftest an-action-the-pool-itself-refuses-has-no-candidate
  (testing "池自己就会拒的动作没有候选态可查 —— 说清楚比给一份空差分有用"
    (let [[cfg conn] (open-pool-with! (objects-of base-model))]
      (try
        (register-sales! conn)
        (let [r (preflight! conn {:workspace "demo" :action :create :type :resolver :id "a_by_id"
                                  :expected_revision 0
                                  :entry {:input [] :output [:s.a/name]
                                          :result_type "single"
                                          :source {:type "sql" :query "SELECT 1"}
                                          :source-ref "db"}})]
          (is (= [:pool.action/id-taken] (mapv :code (:findings r))))
          (is (nil? (:would-cut? r))))
        (finally (pool/close-pool! conn) (pool/delete-pool! cfg))))))

(deftest exit-1-attribute-drift-refuses-and-writes-nothing
  "D2.2：注册表里有、任何 `:output` 里都没有的属性。**日常写入只警告，密封严格**。"
  (refuses-and-writes-nothing!
   (assoc-in base-model [:attributes :s.a/orphan]
             {:type :string :format :text :label "Orphan"})
   :cut.attribute/unregistered-use))

(deftest exit-1-multiple-producers-refuses-and-writes-nothing
  "D2.11 结构那一格：两个 `\"single\"` resolver 从同一个锚产出同一个属性。"
  (refuses-and-writes-nothing!
   (update base-model :resolvers conj
           {:id "a_again" :input [:s.a/id] :output [:s.a/name] :result_type "single"
            :source {:type "sql" :query "SELECT 2"} :source-ref "db"})
   :cut.structure/multiple-producers))

(deftest exit-1-pagination-is-already-refused-one-door-earlier
  "D2.8 第 3 条（`\"list\"` 必带 `:limit`）在**池的写面**就挡住了：池不收不过
   B0 schema 的模型，所以这一类在 `cut-version!` 这一层**造不出来**。

   仍然把它写下来，而不是当作已覆盖：体检里那一次 `read-dialect` 回落是
   **纵深**——投影不重跑 B0 的校验（`impl.projection` 只做 scope 那十步），
   将来若出现别的入池路径，那道回落就是最后一关。判定本身在
   `health_test/d2-8-pagination-is-mandatory` 逐条测。"
  (let [cfg  (pool/config {:backend :mem :id (str (gensym "cut"))})
        _    (pool/delete-pool! cfg)
        conn (pool/open-pool! cfg)]
    (try
      (let [r (pool/import-pool!
               conn {:pool/version 1
                     :pool/objects (objects-of
                                    (update base-model :resolvers conj
                                            {:id "a_all" :input [:s.a/id] :output [:s.a/name]
                                             :result_type "list"
                                             :source {:type "sql" :query "SELECT 2"}
                                             :source-ref "db"}))})]
        (is (contains? (set (map :code (:findings r)))
                       :dialect.invariant/list-limit-missing)))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest exit-1-timeout-not-enforceable-refuses-and-writes-nothing
  "E6.2 第一句：enforce 不了超时的连接器不允许发布。"
  (refuses-and-writes-nothing! base-model :cut.resilience/timeout-not-enforceable
                               :opts {:timeout-enforcing-types #{"api"}}))

(deftest exit-1-mutation-idempotency-refuses-and-writes-nothing
  "E6.2 第二句：生效 policy 含 retry 而 mutation 幂等性未知（D6.4）。"
  (refuses-and-writes-nothing!
   (-> base-model
       (assoc-in [:sources "db" :resilience] {:timeout_ms 3000 :max_retries 1})
       (assoc :mutations [{:id "touch_a" :result_type "single" :returns [:s.a/id]
                           :source {:type "sql" :query "UPDATE a SET n=1"}
                           :source-ref "db"
                           :params {:s.a/id {:required? true}}}]))
   :cut.resilience/mutation-idempotency-unknown))

(deftest exit-1-retry-on-at-the-source-layer-refuses-and-writes-nothing
  "**退出条件 3 的第四条走到密封之前**：`retry_on` 在 source 层不能替 mutation
   声明幂等。"
  (refuses-and-writes-nothing!
   (-> base-model
       (assoc-in [:sources "db" :resilience] {:timeout_ms 3000 :retry_on #{:connection}})
       (assoc :mutations [{:id "touch_a" :result_type "single" :returns [:s.a/id]
                           :source {:type "sql" :query "UPDATE a SET n=1"}
                           :source-ref "db"
                           :params {:s.a/id {:required? true}}}]))
   :cut.resilience/mutation-idempotency-unknown))

(deftest exit-3-a-mutation-that-declares-idempotency-cuts
  "**退出条件 3（三）**：显式声明自然幂等 → 切得出来。前两条在 `health_test`
   逐条判定，这里守的是「通过的那一条真的通过到底」。"
  (let [model (-> base-model
                  (assoc-in [:sources "db" :resilience] {:timeout_ms 3000 :max_retries 1})
                  (assoc :mutations [{:id "touch_a" :result_type "single" :returns [:s.a/id]
                                      :idempotency :natural
                                      :source {:type "sql" :query "UPDATE a SET n=1"}
                                      :source-ref "db"
                                      :params {:s.a/id {:required? true}}}]))
        [cfg conn] (open-pool-with! (objects-of model))
        root (tmp-root "cut-idem-")]
    (try
      (register-sales! conn)
      (let [r (cut! conn root)]
        (is (empty? (:findings r)) (pr-str (:findings r)))
        (is (= 1 (:version r))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

;; ---------------------------------------------------------------------------
;; 退出条件 2：钻石冲突挡在密封之前
;; ---------------------------------------------------------------------------

(def ^:private ref-model
  {:attributes {:c.k/id {:type :integer :format :id :identity? true :label "K"}}
   :namespaces {"c.k" {:kind :master :label "K"}}})

(defn- read! [m]
  (let [{:keys [model findings]} (dialect/read-dialect (pr-str m))]
    (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    model))

(defn- pinned [qn v refs]
  (let [{:keys [envelope content-hash findings]}
        (artifact/encode-content (read! ref-model) (vec refs))]
    (assert (empty? findings) (pr-str findings))
    {:graph qn :version v :content envelope :content-hash content-hash
     :artifact-digest (str "sha256:" (apply str (repeat 64 "a")))}))


;; ---------------------------------------------------------------------------
;; 版本号：具体数字、单调递增、切出即不可变
;; ---------------------------------------------------------------------------

(deftest versions-are-concrete-monotonic-and-immutable
  (let [[cfg conn] (open-pool-with! (objects-of base-model))
        root (tmp-root "cut-mono-")]
    (try
      (register-sales! conn)
      (let [a (cut! conn root) b (cut! conn root)]
        (is (= 1 (:version a)))
        (is (= 2 (:version b)) "单调递增，具体数字（R3.1 永不指 latest）")
        (is (= [1 2] (mapv :version/number (version/versions (pool/db conn) "sales/core"))))
        (testing "同一个版本目录已存在即**拒绝**，不合并进去（D4.8）"
          (let [dir (io/file root "sales" "core" "3")]
            (.mkdirs dir)
            (spit (io/file dir "content.edn") "{}")
            (let [c (cut! conn root)]
              (is (nil? (:version c)))
              (is (= #{:artifact.store/version-exists} (codes c)))))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

;; ---------------------------------------------------------------------------
;; D3.10 的另一半
;; ---------------------------------------------------------------------------

(deftest d3-10-rename-is-allowed-only-before-the-first-cut
  "B5a.1 把限定名做成「登记即不可变」，并注明「B5b 若要放开是**加**一个查过
   版本的函数」。这就是那个函数，而它**只放开「还没切过任何版本」这一种情形**。"
  (let [[cfg conn] (open-pool-with! (objects-of base-model))
        root (tmp-root "cut-rename-")]
    (try
      (register-sales! conn)
      (testing "没切过版本：改得动"
        (let [r (version/rename-graph! conn "sales/core" "core2")]
          (is (empty? (:findings r)) (pr-str r))
          (is (= "sales/core2" (:graph/qualified-name (:graph r))))
          (is (nil? (graph/graph (pool/db conn) "sales/core")) "旧限定名不再存在")
          (is (some? (graph/graph (pool/db conn) "sales/core2")))))
      (check! (cut! conn root :graph "sales/core2"))
      (testing "切过一次之后：拒绝——改名是发布一个新东西，不是重命名（D3.10）"
        (let [r (version/rename-graph! conn "sales/core2" "core3")]
          (is (= #{:version.rename/already-cut} (codes r)))
          (is (= ["sales/core2@v1"] (:expected (first (:findings r))))
              "点名已经切过哪些版本")
          (is (every? version/finding? (:findings r)))
          (is (some? (graph/graph (pool/db conn) "sales/core2")) "一个字节都没改")))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

;; ---------------------------------------------------------------------------
;; 端到端：demo 模型
;; ---------------------------------------------------------------------------

(defn- repo-root []
  (loop [d (io/file (System/getProperty "user.dir"))]
    (cond (nil? d) (throw (ex-info "repo root not found" {}))
          (.isDirectory (io/file d "fixtures/demo")) d
          :else (recur (.getParentFile d)))))

(defonce ^{:doc "demo 模型。`fixtures/demo/model.edn` 是它的家（见那里的 README）。"}
  demo-model
  (delay (edn/read-string (slurp (io/resource "model.edn")))))

(deftest demo-model-cuts-a-version-end-to-end
  "**端到端**：demo 真模型切出一个版本，三份文件写出来，`read-artifact` /
   `load-artifact` 读得回来，content-hash 与 `encode-content` **直接算的一致**。

   最后一条是裁定 ① 的验收：密封没有第二条序列化路径。若 `cut-version!` 自己
   写了一份 writer，这一条就是它露馅的地方——而 content-hash **就是**内容身份
   （D4.3），两个来源迟早给出两个身份。"
  (let [model @demo-model
        [cfg conn] (open-pool-with! (objects-of model))
        root (tmp-root "cut-b2-")]
    (try
      (let []
        (check! (graph/register-workspace! conn {:name "cis" :members [{:principal {:issuer "test-idp" :subject "a"} :role :owner}]}))
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
        (let [r (version/cut-version! conn {:graph "cis/all" :resolved []
                                            :artifact-root root
                                            :cut-at "2026-08-24T09:00:00Z"
                                            :cut-by "alice" :tier :core})]
          (is (empty? (:findings r)) (pr-str (take 3 (:findings r))))
          (is (= 1 (:version r)))

          (testing "D4.2 的三份文件"
            (is (= #{"content.edn" "manifest.edn" "report.edn"}
                   (set (map #(.getName ^java.io.File %)
                             (.listFiles ^java.io.File (:dir r)))))))

          (testing "content-hash 与 `encode-content` 直接算的一致（裁定 ①）"
            (let [proj (graph/project (pool/db conn) "cis/all")
                  enc  (artifact/encode-content (:model proj) [])]
              (is (= (:content-hash enc) (:content-hash r)))
              (is (= (:content-edn enc) (get (:files r) "content.edn")))))

          (testing "`read-artifact` 读得回来，模型与投影产物逐字节相同"
            (let [rd (artifact/read-artifact (:files r)
                                             {:expected-digest (:artifact-digest r)
                                              :closure :unverified})]
              (is (empty? (filter #(= :error (:severity %)) (:findings rd)))
                  (pr-str (:findings rd)))
              (is (= (dialect/write-dialect (:model rd))
                     (:edn (graph/project (pool/db conn) "cis/all"))))))

          (testing "`load-artifact` 从制品架上装得起来（D4.6）"
            (let [ld (artifact/load-artifact root {:workspace "cis" :graph "all" :version 1}
                                             {:expected-digest (:artifact-digest r)})]
              (is (empty? (filter #(= :error (:severity %)) (:findings ld)))
                  (pr-str (:findings ld)))
              (is (some? (:model ld)))))

          (testing "体检结论封进 manifest（D4.1：server 没有池，它无法重算）"
            (let [man (edn/read-string (get (:files r) "manifest.edn"))]
              (is (true? (get-in man [:health :structural-ok?])))
              (is (= {:workspace "cis" :graph "all" :version 1
                      :cut-at "2026-08-24T09:00:00Z" :cut-by "alice"}
                     (dissoc (:provenance man) :object-revisions)))
              (testing "每个对象的 revision 也在（E3）——见下面 object-revisions 一组"
                (is (= (count (pool/dialect->objects
                               (:local (graph/project (pool/db conn) "cis/all"))))
                       (count (:object-revisions (:provenance man))))))
              (is (= (:content-hash r) (:content-hash man)))))

          (testing "版本登记记着 artifact-digest（D4.3：它不写回三份文件）"
            (let [[v] (version/versions (pool/db conn) "cis/all")]
              (is (= (:artifact-digest r) (:version/artifact-digest v)))
              (is (= (:content-hash r) (:version/content-hash v)))))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest cut-version-never-reads-the-clock-or-the-current-user
  "D4.10-a：发布上下文全部显式入参。**不给缺省**——「没传就用现在」会让同一份
   内容在两次切版本得到两份不同的 manifest，测试也就复现不出来。"
  (let [[cfg conn] (open-pool-with! (objects-of base-model))
        root (tmp-root "cut-pure-")]
    (try
      (register-sales! conn)
      (doseq [k [:cut-at :cut-by]]
        (let [r (version/cut-version! conn (dissoc {:graph "sales/core" :resolved []
                                                    :artifact-root root
                                                    :cut-at "t" :cut-by "u"} k))]
          (is (= #{:cut.version/malformed} (codes r)) (str k))))
      (is (empty-root? root))
      (let [src (slurp (io/resource "open/atlas/studio/version/impl/cut.clj"))]
        (doseq [b ["System/currentTimeMillis" "(java.util.Date." "java.time.Instant/now"
                   "System/getenv" "System/getProperty"]]
          (is (not (str/includes? src b))
              (str "impl.cut 里出现了显式入参之外的发布上下文：" b))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

;; ---------------------------------------------------------------------------
;; D4.1 / D4.2：制品**不含被引用版本的内容**
;; ---------------------------------------------------------------------------



(deftest deleting-a-listed-object-makes-the-graph-unprojectable
  "**D3.19 的代价，钉在这里**：菜谱是清单，删掉一个被点名的对象之后，
   那张图就投不出来了（`:scope/unknown-member`）——selector 时代删对象是**静默
   移出**，图照切，没人被告知少了一块。

   两者都不完美，但方向不同：现在是**吵闹地坏掉**，从前是**安静地少东西**。
   本仓库反复选后者的代价，这次选前者。菜谱怎么跟着移除走（自动摘掉？拒绝删除？）
   是移除规则那一侧的事，尚待裁定。"
  (let [dirty (assoc-in base-model [:attributes :s.a/orphan]
                        {:type :string :format :text :label "Orphan"})
        [cfg conn] (open-pool-with! (objects-of dirty))]
    (try
      (register-sales! conn)                       ; 菜谱点名了 orphan
      (check! (pool/execute-action conn {:workspace "demo" :action :delete :type :attribute
                                         :id :s.a/orphan :expected_revision 1}))
      (let [r (version/cut-version! conn {:graph "sales/core"
                                          :artifact-root (tmp-root "cut-ghost-")
                                          :cut-at "2026-08-27T00:00:00Z" :cut-by "a"})]
        (is (nil? (:version r)))
        (is (= #{:scope/unknown-member} (set (map :code (:findings r))))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

;; ---------------------------------------------------------------------------
;; `:object-revisions`：这一版的每个对象在池里是第几版（E3，artifact-v1 §4.1）
;;
;; 守的是三件事：(1) 清单覆盖投影里的**全部**对象——漏一个，那个对象的改动史
;; 就在制品这一头断了，而读者不会知道自己少看了什么；(2) 只有被改的那个对象的
;; 数字会动——否则「这一版哪里变了」得不到答案；(3) **content-hash 不动**
;; （D4.3 / artifact-v1 §5：发布上下文不改内容身份）。
;; ---------------------------------------------------------------------------

(defn- provenance-of [r]
  (:provenance (edn/read-string (get (:files r) "manifest.edn"))))

(deftest a-cut-records-every-objects-revision
  (let [[cfg conn] (open-pool-with! (objects-of base-model))
        root (tmp-root "cut-e3-")]
    (try
      (register-sales! conn)
      (let [r    (check! (cut! conn root))
            revs (:object-revisions (provenance-of r))
            proj (graph/project (pool/db conn) "sales/core")]
        (is (= (set (map (juxt :pool/type :pool/id) (pool/dialect->objects (:local proj))))
               (set (keys revs)))
            "投影里的对象集就是清单的键集——不多一个，也不少一个")
        (is (every? pos-int? (vals revs))
            "正整数：0 在池里意思是「不存在过」，而进了投影的对象存在过")
        (is (= 1 (get revs [:resolver "a_by_id"]))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest only-the-changed-objects-revision-moves
  (let [[cfg conn] (open-pool-with! (objects-of base-model))
        root (tmp-root "cut-e3-move-")]
    (try
      (register-sales! conn)
      (let [before (:object-revisions (provenance-of (check! (cut! conn root))))
            _ (check! (pool/execute-action
                       conn {:workspace "demo" :action :update :type :attribute :id :s.a/name
                             :entry {:label "Name 改过"} :expected_revision 1}
                       {:entrypoint :ui}))
            after (:object-revisions (provenance-of (check! (cut! conn root))))]
        (is (= 2 (get after [:attribute :s.a/name])))
        (is (= (dissoc before [:attribute :s.a/name])
               (dissoc after [:attribute :s.a/name]))
            "别的对象一个都没动——「这一版哪里变了」因此答得出"))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest the-revisions-do-not-touch-the-content-hash
  "D4.3 / artifact-v1 §5：同一份内容从不同 revision 切出必须是**同一个身份**。
   一次「改成原样」的写只让 revision 前进，内容逐字节不变——content-hash 必须
   不动，artifact-digest 必须动（它覆盖 manifest）。"
  (let [[cfg conn] (open-pool-with! (objects-of base-model))
        root (tmp-root "cut-e3-hash-")]
    (try
      (register-sales! conn)
      (let [attr (pool/object (pool/db conn) :attribute :s.a/name)
            r1 (check! (cut! conn root))
            _  (check! (pool/execute-action
                        conn {:workspace "demo" :action :update :type :attribute :id :s.a/name
                              :entry (:pool/entry attr) :expected_revision 1}
                        {:entrypoint :ui}))
            r2 (check! (cut! conn root))]
        (is (= (:content-hash r1) (:content-hash r2))
            "内容没变，身份就没变")
        (is (not= (:artifact-digest r1) (:artifact-digest r2))
            "但 manifest 变了，而 artifact-digest 覆盖三份文件")
        (is (= 1 (get (:object-revisions (provenance-of r1)) [:attribute :s.a/name])))
        (is (= 2 (get (:object-revisions (provenance-of r2)) [:attribute :s.a/name])))
        (is (not (str/includes? (get (:files r1) "content.edn") "object-revisions"))
            "红线：revision 一个字节都不进 content.edn"))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest a-source-type-with-no-connector-refuses-before-sealing
  "doc 104 §8.17.7 A + CLAUDE.md「缺失都拒绝而非截断/猜测」：本机没有该 source
   type 的连接器时，`spi/package-compat` 会写一个 nil 版本、`declared-files`
   回落成 `[]`——密封出去的包**少声明**了自己要什么，而没有一个字告诉谁。
   所以密封之前就拒，点名 type 与那条 source。"
  ;; 「本机没有连接器」在测试里的形状：type 用 `"api"`（方言词表封闭，编造一个
  ;; type 连池都进不去），并把 `contract-version` 对它的答案按下为 nil——单模块
  ;; 跑时 connector-api 本就不在 classpath 上，根聚合跑时它在，而这条测的是
  ;; **宿主漏 require 一个连接器**那一幕，不该随聚合的 classpath 变。
  (let [model (-> base-model
                  (assoc :sources {"db" {:type "api" :label "DB"
                                         :base_url "https://example.invalid"}})
                  (assoc :resolvers
                         [{:id "a_by_id" :input [:s.a/id] :output [:s.a/id :s.a/name]
                           :result_type "single"
                           :source {:type "api" :endpoint "/a" :method "GET"}
                           :source-ref "db"}]))
        [cfg conn] (open-pool-with! (objects-of model))
        root (tmp-root "cut-nosuch-")]
    (try
      (register-sales! conn)
      (let [r (with-redefs [spi/contract-version (fn [t] (when-not (= "api" t) "v1"))]
                (cut! conn root))]
        (is (nil? (:version r)))
        (is (contains? (codes r) :package/unknown-connector) (pr-str (codes r)))
        (is (every? version/finding? (:findings r)))
        (let [f (first (filter #(= :package/unknown-connector (:code %)) (:findings r)))]
          (is (= [:sources "db" :type] (:path f)) "点名那条 source")
          (is (str/includes? (:message f) "\"api\"") "点名 type"))
        (is (empty-root? root) "一个字节都没写"))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest a-thing-fetched-from-two-places-cuts-clean
  "doc 106 §106.14 item 7 (owner 2026-09-07). need-grammar-v1 A2 used to say a
   second by-key resolver's `:output` holds «the new attributes AND that key»,
   and a graph shaped that way was refused with
   `cut.structure/multiple-producers`: two `single` resolvers off the same
   anchor producing one attribute. The contract moved — the key has ONE
   producer — and this is the shape it now names."
  (let [two-sources
        (-> base-model
            (update :resolvers conj
                    {:id "a_by_id_from_state" :input [:s.a/id] :output [:s.a/status]
                     :result_type "single"
                     :source {:type "sql" :query "SELECT 2"} :source-ref "db"})
            (assoc-in [:attributes :s.a/status]
                      {:type :string :format :text :label "Status"}))
        [cfg conn] (open-pool-with! (objects-of two-sources))]
    (try
      (register-sales! conn)
      (let [r (version/cuttable conn {:graph "sales/core"})]
        (is (true? (:would-cut? r)) (pr-str (:health-findings r)))
        (is (empty? (:health-findings r))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))
