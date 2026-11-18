(ns open.atlas.studio.graph.report-test
  "**发现面报表**（B5a.3；graph README 裁定 ⑥ / §3.3 照镜子）。

   三张报表各有守门测试，每条 deftest 的 docstring 第一行说它守的是哪一条：

   1. **归属** —— 这个对象归哪个 workspace（读池对象上存着的 `:pool/workspace`，D3.22）；
   2. **引用反查** —— 谁的菜谱引用了 `core-team/core@v3`，**逐版本精确**；
   3. **含量清单（池内版）** —— 这个对象按**当前菜谱**会进哪些图，
      且**结果与直接跑 `project` 一致**（裁定 ⑥：含量清单必须跑投影，
      不许另写一份闭包遍历）；

   **越界写 / 无人认领两册已删**（D3.22）：归属存下来之后写入不可能越界。

   装料复用 `projection-test` 的那一份（手写小模型 + demo 模型）：投影的输入必须
   是合法方言，而那份 fixture 已经过 `read-dialect`。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.graph.projection-test :as pt]
            [open.atlas.studio.pool :as pool]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

(def ^:dynamic *conn* nil)

(defn with-pool [f]
  (let [[cfg conn] (#'pt/open-pool-with! (#'pt/objects-of (#'pt/read! pt/local-edn)))]
    (#'pt/register-baseline! conn)
    (binding [*conn* conn]
      (try (f) (finally (pool/close-pool! conn) (pool/delete-pool! cfg))))))

(use-fixtures :each with-pool)

(defn- db [] (pool/db *conn*))

(defn- ok! [r] (assert (empty? (:findings r)) (pr-str (:findings r))) r)

(defn- members-of
  "「装下 `sales.*` 的一切」展开成清单——界面上那个批量加入动作的测试形（D3.19）。"
  [selectors]
  (let [model    (pool/as-dialect (pool/db *conn*))
        ns-names (into #{} (filter (fn [n] (some #(graph/selector-matches? % n) selectors))
                                   (keys (:namespaces model))))
        reg      (fn [kind] (into #{} (filter (fn [id] (some #(graph/selector-matches? % id) selectors))
                                              (keys (get model kind)))))]
    {:namespaces ns-names
     :attributes (into #{} (filter #(contains? ns-names (namespace %)))
                       (keys (:attributes model)))
     :units (reg :units) :terms (reg :terms)}))

(defn- graph! [ws nm selectors]
  (ok! (graph/register-graph! *conn* {:workspace ws :name nm :members (members-of selectors)}))
  (graph/qualified-name ws nm))

(defn- core-graph!
  "core-team 也登记一张自己的图——workspace 是封闭世界（D3.17），
   它与 sales 的图之间没有引用关系，只是各装各的。"
  []
  (graph! "core-team" "core" ["core.*"]))

;; ---------------------------------------------------------------------------
;; 1. 归属
;; ---------------------------------------------------------------------------

(deftest ownership-answers-who-owns-this
  "**归属**（§3.3 第一问）：读的是池对象上**存着**的那一列（D3.22），不再拿名字去
   与地盘 selector 匹配——D3.17 拆了地盘制度，那张表已经删了。发现是开放的
   （D3.14）：不问调用方是谁。

   本 fixture 的对象全部盖着 `demo`（见 `projection-test/objects-of`）。"
  (testing "属性与 namespace 两种问法答同一件事"
    (is (= "demo" (:workspace (graph/report-ownership (db) :sales.order/id))))
    (is (= "sales.order" (:namespace (graph/report-ownership (db) :sales.order/id))))
    (is (= "demo" (:workspace (graph/report-ownership (db) "sales.order")))))
  (testing "词表域的词条同样答得出"
    (is (= "demo" (:workspace (graph/report-ownership (db) "common.audit/note"))))
    (is (= "common.audit" (:namespace (graph/report-ownership (db) "common.audit/note")))))
  (testing "池里没有这个对象是真信息，不是空答案"
    (let [r (graph/report-ownership (db) "nobody.land")]
      (is (nil? (:workspace r)))
      (is (false? (:owned? r)))))
  (testing "报表不重写判定：与池对象上存着的那一列逐个对得上"
    (doseq [[t id] [[:namespace "sales.order"] [:attribute :sales.order/id]]]
      (is (= (:pool/workspace (pool/object (db) t id))
             (:workspace (graph/report-ownership (db) id)))))))

;; ---------------------------------------------------------------------------
;; 2. 引用反查
;; ---------------------------------------------------------------------------

(deftest membership-is-answered-by-running-the-projection
  "**含量清单（池内版）**：两张菜谱不同的图，同一个属性在一张里、不在另一张里,
   且**结果与直接跑 `project` 一致**——裁定 ⑥ 要的就是这条等价：报表不许有第二份
   闭包规则，否则它会说「它在 A 图里」而 A 图实际投不出它。"
  (core-graph!)
  (let [note (graph! "sales" "note" ["sales.note"])
        ;; `sales.*` 那张图要自己装上 core.customer——`order_by_cust` 的 input
        ;; 在那儿，而引用已经退役（D3.17），没有别人替它带进来。
        all  (graph! "sales" "all" ["sales.*" "core.customer"])
        r    (graph/report-membership-by-current-recipe
              (db) [:attribute :sales.order/id])]
    (is (empty? (:findings r)))
    (is (= [all] (:in r)) "只有 sales.* 那张图装得下 sales.order/id")
    (is (contains? (set (:not-in r)) note))
    (is (empty? (:undecidable r)))
    (testing "与直接跑 project 的结果逐张对得上"
      (doseq [qn [note all "core-team/core"]]
        (let [p (graph/project (db) qn)
              in? (contains? (set (keys (:attributes (:model p)))) :sales.order/id)]
          (is (= in? (contains? (set (:in r)) qn))
              (str qn "：报表说的与 project 说的必须是同一件事")))))
    (testing "另一个属性落在另一张图里"
      (let [n (graph/report-membership-by-current-recipe
               (db) [:attribute :sales.note/id])]
        (is (= #{note all} (set (:in n))))))
    (testing "词表域的对象也答得出（含量清单不只管属性）"
      (let [t (graph/report-membership-by-current-recipe
               (db) [:term "common.terms/amount"])]
        (is (= [all] (:in t)) "词条随最小闭包进 sales.* 那张图")))))

(deftest a-graph-that-cannot-be-projected-is-undecidable-not-absent
  "**投不出来的图落进 `:undecidable`，绝不悄悄算成「不在里面」**。

   少报一条与「本来就没有」在报表上长得一模一样——这个仓库栽在这种事上不止一次
   （CLAUDE.md）。所以投不出来时报表带着投影自己的 finding 交出来。

   这里的投不出来是 `sales.*` 装了 `order_by_cust` 却没装它 input 所在的
   `core.customer`（`:scope/unclosed-input`）——引用退役之后（D3.17），
   「输入在别人图里」这种投不出来已经不存在，取而代之的是清单自己不闭合。"
  (core-graph!)
  (let [all (graph! "sales" "all" ["sales.*"])
        r   (graph/report-membership-by-current-recipe (db) [:attribute :sales.order/id])]
    (is (= [all] (mapv :graph (:undecidable r))))
    (is (seq (:findings (first (:undecidable r)))))
    (is (every? graph/finding? (:findings (first (:undecidable r)))))
    (is (not (contains? (set (:not-in r)) all))
        "投不出来 ≠ 不含——两者混为一谈就是在猜")))

(deftest membership-refuses-a-question-it-cannot-read
  "问题本身问不通时**结构化拒绝**，不返回空清单（finding-v1）。"
  (let [r (graph/report-membership-by-current-recipe (db) [:no-such-type "x"])]
    (is (= #{:graph.report/malformed} (set (map :code (:findings r)))))
    (is (every? graph/finding? (:findings r)))
    (is (nil? (:in r)) "拒绝与产物互斥")))

;; ---------------------------------------------------------------------------
;; demo 模型：报表在真模型上答得出
;; ---------------------------------------------------------------------------

(defn- register-demo!
  "cis 一个 workspace 登记一张圈全部 namespace 与词表域的 `cis/all`
   ——与 `projection-test` 的端到端同一套摆法（那里已经证过这张图投得出来）。"
  [conn model]
  (let [check! (fn [r] (assert (empty? (:findings r)) (pr-str (:findings r))))]
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
                                                     :links (set (map (juxt :from :to) (:links m)))})}))))

(deftest demo-reports-end-to-end
  "**demo 模型**（188 属性 / 23 词条 / 35 resolver）。玩具模型证不出报表在一份
   真模型上还答得出——`cis/all` 那张图的投影要跑第 4、5、7 步的全部判定。"
  (let [model @pt/demo-model
        [cfg conn] (#'pt/open-pool-with! (#'pt/objects-of model))]
    (try
      (let [attr (first (sort (keys (:attributes model))))
            nm   (namespace attr)]
        (register-demo! conn model)
        ;; The fixture stamps every object with `demo` (D3.22's stored column);
        ;; `cis` is only the workspace the graph is registered under.
        (is (= "demo" (:workspace (graph/report-ownership (pool/db conn) attr))))
        (is (= nm (:namespace (graph/report-ownership (pool/db conn) attr))))
        (let [r (graph/report-membership-by-current-recipe
                 (pool/db conn) [:attribute attr])]
          (is (empty? (:undecidable r)) (pr-str (:undecidable r)))
          (is (= ["cis/all"] (:in r)))
          (is (= (:in r)
                 (vec (for [g ["cis/all"]
                            :when (contains? (set (keys (:attributes
                                                         (:model (graph/project (pool/db conn) g)))))
                                             attr)]
                        g)))
              "与直接跑 project 一致")))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))
