(ns open.atlas.common.engine.core-test
  "B1 退出条件里属于 engine 的那部分。fixture 从 classpath 读 \"graph.edn\"
   （:test alias 把 fixtures/b1 挂进来），engine 与 connector-sql 共用同一份——
   两个模块若对它的理解不一致，测试会当场分叉，这正是要的。"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.wsscode.pathom3.connect.runner :as pcr]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.engine.fake-connector :as fake]))

(def model
  (let [{:keys [model findings]} (dialect/read-dialect (slurp (io/resource "graph.edn")))]
    (assert (empty? findings) (str "fixture must read with zero findings: " (pr-str findings)))
    (fake/inline-model model)))

(def compiled (engine/compile-env model))

(defn- tok [t] {:graph "b1/demo" :version "v1" :tier t})

(defn- q
  ([tier request] (engine/query compiled (tok tier) request))
  ([c tier request] (engine/query c (tok tier) request)))

;; ---------------------------------------------------------------------------

(deftest compiles-fixture-without-blocking-findings
  (is (empty? (:engine/findings compiled))
      "fixture 的四条 resolver 都该编得出来")
  (is (= #{:authoring} (set (keys (:engine/by-tier compiled))))
      "档位退役（owner 裁定 2026-08-27）：一张图只编译**一份** env。
       `:engine/by-tier` 暂留是给 Studio 的 explain 面做源码兼容，里面永远只有一份"))


;; --- D5.4 三种处理，同一张图 --------------------------------------------------



;; --- caller-supplied（防 membership oracle，D5.3 / S5.6）----------------------


;; --- EQL 四道边界，超限即拒绝而非截断 -----------------------------------------

(deftest four-eql-boundaries-reject-rather-than-truncate
  (testing "① 请求文本 8 KB"
    (let [text (str "[" (apply str (repeat 900 ":cis.master.part/sku_no ")) "]")
          r    (q :public text)]
      (is (= :engine.limit/exceeded (:code r)))
      (is (= :request-bytes (:limit r)))
      (is (= 8192 (:max r)))
      (is (> (:actual r) 8192) "拒绝点名实际值")))

  (testing "② 深度 8 层"
    (let [deep (reduce (fn [inner _] [{:cis.master.vendor/parts inner}])
                       [:cis.master.part/sku_no] (range 10))
          r    (q :public deep)]
      (is (= :engine.limit/exceeded (:code r)))
      (is (= :depth (:limit r)))
      (is (= 8 (:max r)))
      (is (= 11 (:actual r)))))

  (testing "③ 执行 30 秒（本例配成 80ms 以免测试自己跑 30 秒）"
    (let [slow (engine/compile-env (fake/inline-model model "test-slow")
                                   {:limits {:execution-ms 80}})
          r    (q slow :restricted {:entity {:cis.master.vendor/vend_no 1001}
                                    :query  [:cis.master.vendor/vend_name]})]
      (is (= :engine.limit/exceeded (:code r)))
      (is (= :execution-ms (:limit r)))
      (is (= 80 (:max r)))))

  (testing "④ 序列化结果 256 KB（本例配成 40 字节）"
    (let [tiny (engine/compile-env model {:limits {:result-bytes 40}})
          r    (q tiny :restricted [{:cis.master.part/all_parts
                                     [:cis.master.part/sku_no :cis.master.part/short_desc]}])]
      (is (= :engine.limit/exceeded (:code r)))
      (is (= :result-bytes (:limit r)))
      (is (= 40 (:max r)))
      (is (> (:actual r) 40))
      (is (nil? (:engine/data r)) "超限即拒绝，绝不返回截断的结果")))

  (testing "四道界限可配置但有默认值"
    (is (= {:request-bytes 8192 :depth 8 :execution-ms 30000 :result-bytes 262144}
           (:engine/limits compiled)))))

;; --- 请求形状：不做强制转换 ---------------------------------------------------

(deftest request-shape-is-not-coerced
  (testing "不是 EQL vector 或 {:entity map :query vector} 就拒绝"
    (doseq [bad ["{:foo 1}" "\"a string\"" "42" "{:query [:a/b]}"]]
      (is (= :engine.request/malformed (:code (q :public bad))) (str "bad=" bad))))
  (testing "EDN 用 clojure.edn 无 tag handler 读"
    (is (= :engine.request/unreadable (:code (q :public "[#inst \"2026-01-01\"]"))))
    (is (= :engine.request/unreadable (:code (q :public "[:a/b")))))
  ;; 「数据面永远需要 token」这条判定**搬到了 instance**（`instance/answer` 的
  ;; `token/verify`）。档位退役之后 engine 不再从 token 上读任何东西——它编译并
  ;; 执行**一张图**的模型，谁够格碰到这份 env 由上一层决定。执法点仍然唯一
  ;; （D5.4），只是不在这一层了。
  )

;; --- 查询纪律（D2.8）---------------------------------------------------------

(deftest eql-params-narrow-on-the-anchor
  (testing "声明过的 eql-param 收窄"
    (let [r (q :public [(list {:cis.master.part/all_parts [:cis.master.part/sku_no]}
                              {:prod_code "A"})])]
      (is (= :ok (:engine/status r)))
      (is (= [3] (mapv :cis.master.part/sku_no
                       (get-in r [:engine/data :cis.master.part/all_parts]))))))
  (testing "未声明的参数拒绝而不静默丢弃"
    (let [r (q :public [(list {:cis.master.part/all_parts [:cis.master.part/sku_no]}
                              {:whatever "A"})])]
      (is (= :engine.request/unknown-param (:code r)))))
  (testing ":limit 不得超过声明的 :max"
    (let [r (q :public [(list {:cis.master.part/all_parts [:cis.master.part/sku_no]}
                              {:limit 9999})])]
      (is (= :engine.request/limit-exceeds-max (:code r)))
      (is (= 500 (:max r)))))
  (testing ":order_by 必须在声明的白名单里"
    (let [r (q :public [(list {:cis.master.part/all_parts [:cis.master.part/sku_no]}
                              {:order_by :cis.master.part/short_desc})])]
      (is (= :engine.request/order-not-allowed (:code r))))))

;; --- link → alias resolver（不经连接器）--------------------------------------

(deftest links-compile-to-alias-resolvers
  (testing ":bi（缺省）两个方向都能走，且不调 source"
    (let [r (q :restricted {:entity {:cis.master.vendor/vend_no 1001}
                            :query  [:cis.master.part/vend_no]})]
      (is (= :ok (:engine/status r)))
      (is (= 1001 (get-in r [:engine/data :cis.master.part/vend_no]))))))

(deftest batch-alignment-never-drops-a-slot
  (testing "batch resolver 经 SPI 的 compile-batch-source，按输入顺序对齐"
    (let [r (q :restricted {:entity {:cis.master.vendor/vend_no 1002}
                            :query  [{:cis.master.vendor/parts
                                      [:cis.master.part/sku_no :cis.master.part/short_desc]}]})]
      (is (= :ok (:engine/status r)))
      (is (= ["Frame"] (mapv :cis.master.part/short_desc
                             (get-in r [:engine/data :cis.master.vendor/parts]))))))
  (testing "无 part 的 vendor 拿空位而不是丢位"
    (let [r (q :restricted {:entity {:cis.master.vendor/vend_no 1003}
                            :query  [{:cis.master.vendor/parts [:cis.master.part/sku_no]}]})]
      (is (= :ok (:engine/status r)))
      (is (= [] (get-in r [:engine/data :cis.master.vendor/parts]))))))

(deftest batch-resolver-goes-through-the-spi-batch-path
  (testing "声明了 :batch 的 resolver 经 compile-batch-source，一条语句消 N+1"
    (let [r (q :restricted {:entity {:cis.master.part/sku_no 2}
                            :query  [:cis.master.part/part_no :cis.master.part/short_desc]})]
      (is (= :ok (:engine/status r)))
      (is (= "P-2" (get-in r [:engine/data :cis.master.part/part_no])))))
  (testing "batch 路径上一切声明过的属性都取得到——档位退役之后没有第二种结果"
    (let [r (q :public {:entity {:cis.master.part/sku_no 2}
                        :query  [:cis.master.part/part_no :cis.master.part/ave_cost]})]
      (is (= :ok (:engine/status r)))
      (is (some? (get-in r [:engine/data :cis.master.part/ave_cost]))))))

(deftest ident-keys-are-caller-supplied
  "EQL ident 是 caller-supplied 绑定：调用方把一个值绑到一个属性上。

   曾经这里还有第二半——绑到**不够档**的属性要拒绝（防 membership oracle）。
   档位退役之后（owner 裁定 2026-08-27）没有「不够档的属性」了：一张图里的
   属性对拿到这张图的 token 一律可读，要更窄就切更窄的图（D3.19 让这件事变
   便宜，正是档位那一层停止偿还其复杂度的原因）。"
  (let [r (q :public [{[:cis.master.part/sku_no 3] [:cis.master.part/part_no]}])]
    (is (= :ok (:engine/status r)))
    (is (= "P-3" (get-in r [:engine/data [:cis.master.part/sku_no 3]
                            :cis.master.part/part_no])))))


;; --- `'*` 是查询宏，必须在规划前展开 ------------------------------------------
;;
;; 回归防线：本组用例的 `:entity` **只带 ident**，一个属性值都不预置。
;; 之前这个缺陷能藏住，正是因为假连接器预置了实体数据，`*` 恰好有东西可 scrub；
;; 真连接器下 `*` 只回 ident 本身，D5.4 第 2 种处理在实践中不可达。

(deftest wildcard-expands-before-planning
  "`'*` 是查询宏，必须在规划前展开。

   档位退役之后（owner 裁定 2026-08-27）这条只剩一种结果：**图里有的都给**。
   原来的三个 testing 分别测「够档全给」「不够档过滤给键名不给值」「不够档的
   :restricted 整个不存在」——后两个的主语是档位，随它一起没了。
   `:engine/filtered-attributes` 也**随之删除**（owner 裁定 2026-08-28）:
   它恒为空，而一个恒为空的键读起来像是「还有一道会挑人的门」（D7.12）。"
  (let [r (q :public {:entity {:cis.master.vendor/vend_no 1001} :query ['*]})]
    (is (= :ok (:engine/status r)))
    (is (= #{:cis.master.vendor/vend_no :cis.master.vendor/vend_name
             :cis.master.vendor/discontinued :cis.master.vendor/contact_email
             :cis.master.vendor/entry_by :cis.master.vendor/entry_date}
           (set (keys (:engine/data r))))
        "Pathom3 的 `*` 只给实体里已有的数据；平台的 wildcard 是「把这张图里
         这个实体能到的都给我」——回归防线仍在：`:entity` 只带 ident，
         一个属性值都不预置")
    (is (not (contains? r :engine/filtered-attributes))
        "没有档位可过滤了，键也不留：恒为空的键会让消费方以为还有一道会挑人的门")))



;; ---------------------------------------------------------------------------
;; 词条注册表进 catalog（D2.26 + D5.3）
;; ---------------------------------------------------------------------------
;;
;; 属性绑了词条之后，本地的 `:label` / `:description` / `:format` / `:synonyms`
;; 就被摘掉了——语义只有词条一个事实源。catalog 若只带 `:term` 这个 ID 而不带
;; 注册表，调用方拿到的是**一个指向不存在目的地的引用**：属性没有描述，ID 也
;; 查不到东西。绑定越多这个洞越大。

(defn- bind-term
  "把 `a` 绑到 `tid` 上，并按 D2.26 摘掉本地的词条携带字段（真管线做的同一件事）。"
  [m a tid]
  (update-in m [:attributes a]
             #(-> (apply dissoc % [:description :format :label :synonyms :values])
                  (assoc :term tid))))

(def ^:private termed
  (-> model
      (assoc :terms {"cat/sku_no"  {:label "物料号" :description "目录里一个可售卖单元的内部编号。"}
                     "cost/avg"    {:label "平均成本" :description "加权平均成本。"}})
      (bind-term :cis.master.part/sku_no   "cat/sku_no")
      ;; `ave_cost` 是 `:restricted` —— 只绑在它上面的那个词条不该出现在低档 catalog
      (bind-term :cis.master.part/ave_cost "cost/avg")))

(def ^:private termed-compiled (engine/compile-env termed))

(deftest catalog-carries-the-term-registry-not-just-the-id
  (testing "绑了词条的属性，描述在注册表里查得到——否则 :term 是个悬空引用"
    (let [cat (engine/catalog termed-compiled :public)
          a   (get-in cat [:attributes :cis.master.part/sku_no])]
      (is (= "cat/sku_no" (:term a)))
      (is (nil? (:description a)) "本地描述被摘掉了，这正是注册表必须在场的原因")
      (is (= {:label "物料号" :description "目录里一个可售卖单元的内部编号。"}
             (get-in cat [:terms "cat/sku_no"]))))))


;; ---------------------------------------------------------------------------
;; D2.28 `:listed?`——不上目录，但仍可作规划器中间量
;; ---------------------------------------------------------------------------

(def ^:private unlisted
  "把一个 `:public` 属性标成不列出。选 `part_no`：它是**公开**的，
   于是这条测试量的是 `:listed?` 本身，不是档位。"
  (assoc-in model [:attributes :cis.master.part/part_no :listed?] false))

(def ^:private unlisted-compiled (engine/compile-env unlisted))


;; ---------------------------------------------------------------------------
;; A collection is a join attribute onto its own namespace (D2.29, 2026-09-02)
;; ---------------------------------------------------------------------------

(def ^:private collection-model
  "D2.29 之后「全局集合」不再是一个同时存在于两张注册表里的同名物：它就是一条
   `:target` 指向**自己 namespace** 的 join 属性。`:collection?` 与
   `:navigations` 一并退役，于是这份模型里没有任何一个键需要解释「它为什么
   同时是两样东西」。"
  (let [{:keys [model findings]}
        (dialect/read-dialect
         (pr-str
          {:namespaces {"t.thing" {}}
           :attributes {:t.thing/id         {:type :integer :identity? true}
                        :t.thing/nm         {:type :string}
                        :t.thing/all_things {:target "t.thing"
                                             :cardinality :one-to-many
                                             :label "All things"}}
           ;; 写成 `"sql"` 再由 `fake/inline-model` 换成假连接器：方言的
           ;; `:type` 是受控词表（D2.15），测试专用的 type 写进方言文本会被
           ;; 判 vocab-violation——换在内存里，与 b1 那份走同一条路。
           :sources    {"s" {:type "sql" :jdbc_url "${X}"}}
           :resolvers  [{:id "all_things" :curated? true :input []
                         :result_type "list" :limit {:default 50 :max 500}
                         :output [{:t.thing/all_things [:t.thing/id :t.thing/nm]}]
                         :source {:type "sql" :query "SELECT id, nm FROM t"}
                         :source-ref "s"}]}))]
    (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    (fake/inline-model model)))

(deftest a-collection-attribute-reaches-the-catalog
  ;; The property under test is unchanged from the `:collection?` era: a
  ;; registered, described, resolver-produced collection must be FINDABLE, since
  ;; a consumer cannot tell "not in the catalog" from "does not exist". What
  ;; changed with D2.29 is where it is found: it is a join, so it answers in
  ;; `:joins` rather than needing a special case to smuggle it into
  ;; `:attributes` alongside the scalars.
  (let [c (engine/compile-env collection-model)]
    (doseq [tier [:public :sensitive :restricted]]
      (let [cat (engine/catalog c tier)]
        (is (= "t.thing" (get (:joins cat) :t.thing/all_things))
            (str tier " catalog is missing the collection join"))
        (is (not (contains? (:attributes cat) :t.thing/all_things))
            "a join is not a scalar row — it has no :type and no value to give")))))

;; ---------------------------------------------------------------------------
;; Authoring mode (doc 89 Phase 1): apps/studio's Query Studio "builder · full
;; model" view runs on this engine now instead of a second dialect->legacy
;; translation. `:authoring? true` compiles one extra, unpruned entry; a
;; `compiled` that never asked for it must go on refusing `:tier :authoring`
;; exactly as it always has.
;; ---------------------------------------------------------------------------

(def ^:private authoring-compiled (engine/compile-env model {:authoring? true}))

(def ^:private authoring-unlisted-compiled (engine/compile-env unlisted {:authoring? true}))

(defn- aq
  ([request] (aq authoring-compiled request))
  ([c request] (engine/query c {:graph "b1/demo" :version "v1" :tier :authoring} request))
  ([c request opts] (engine/query c {:graph "b1/demo" :version "v1" :tier :authoring} request opts)))




(deftest authoring-still-refuses-bare-navigation
  (testing "D2.16/D5.4's join-required is correctness, not tiering — it stays"
    (let [r (aq {:entity {:cis.master.vendor/vend_no 1001}
                :query  [:cis.master.vendor/parts]})]
      (is (= :rejected (:engine/status r)))
      (is (= :engine.query/join-required (:code r)))))
  (testing "with a sub-query it runs the nested join like any other tier"
    (let [r (aq {:entity {:cis.master.vendor/vend_no 1001}
                :query  [{:cis.master.vendor/parts [:cis.master.part/sku_no
                                                     :cis.master.part/ave_cost]}]})]
      (is (= :ok (:engine/status r)))
      (is (seq (get-in r [:engine/data :cis.master.vendor/parts])))
      (is (every? :cis.master.part/ave_cost (get-in r [:engine/data :cis.master.vendor/parts]))
          "the batched child collection carries the :restricted attribute too"))))

;; --- :lenient? -----------------------------------------------------------

(def ^:private lenient-model
  "One extra resolver whose `:id` `rows-for` (fake-connector) has no `case`
   clause for — calling it throws, on purpose, so a query naming it alongside
   a working attribute has something real for lenient mode to catch."
  (-> model
      (update :attributes assoc :t.acct/broken {:type :string :label "Deliberately broken"})
      (update :resolvers conj
              {:id "definitely-not-a-known-resolver-id"
               :input [:cis.master.vendor/vend_no]
               :output [:t.acct/broken]
               :result_type "single"
               :source {:type "test-inline" :query "n/a"}
               :source-ref "cis-demo-db"})))

(def ^:private lenient-compiled (engine/compile-env lenient-model {:authoring? true}))

(def ^:private unconnectable-compiled
  "A resolver whose source cannot be opened — the fake connector fails it with
   `:connection` and the unresolved variable's name."
  (engine/compile-env
   (-> model
       (update :attributes assoc :t.conn/broken {:type :string :label "Unconnectable"})
       (update :sources assoc "no-conn" {:type "test-unconnectable" :label "No conn"})
       (update :resolvers conj
               {:id "a_resolver_whose_source_cannot_be_opened"
                :input [:cis.master.vendor/vend_no]
                :output [:t.conn/broken]
                :result_type "single"
                :source {:type "test-unconnectable" :query "n/a"}
                :source-ref "no-conn"}))
   {:authoring? true}))

(deftest a-connection-failure-says-which-variable-is-unresolved
  ;; 8.4.18 裁定十二 ②: `:error/category :connection` alone tells a modeller
  ;; that the connector never opened, and nothing at all about why. The
  ;; connector's own message is the only place the name lives, and it is safe
  ;; to carry: `spi/fail!` guarantees configuration-level phrases only.
  (let [r (aq unconnectable-compiled
              {:entity {:cis.master.vendor/vend_no 1001}
               :query  [:t.conn/broken]})]
    (is (= :rejected (:engine/status r)))
    (is (= :engine.execution/failed (:code r)))
    (is (= :connection (:error/category r)))
    (is (re-find #"\$\{DB_PASSWORD\}" (:message r))
        "the variable NAME reaches the caller; without it «connection failed»
         is a dead end")
    (is (str/starts-with? (:message r) "source execution failed: ")
        "and it stays a source-execution refusal, not a new kind of answer"))
  (testing "a non-connection failure keeps the flat sentence — driver text
            never travels (spi/fail! drops the cause on purpose)"
    (let [r (aq lenient-compiled {:entity {:cis.master.vendor/vend_no 1001}
                                  :query  [:t.acct/broken]})]
      (is (= :engine.execution/failed (:code r)))
      (is (= "source execution failed" (:message r))))))

(deftest a-plan-with-no-path-is-not-reported-as-a-source-failure
  ;; The planner throws before any connector is called. Reporting that as
  ;; "source execution failed" (with :error/category :source, no less) sent two
  ;; agent sessions off to debug SQL that was never sent — the demo-ch join
  ;; incident, 2026-08-31. The two failures are different diseases and must be
  ;; two different rejections.
  (testing "anchored by an attribute no resolver takes as input → unreachable, named"
    (let [r (q :authoring {:entity {:cis.master.part/part_no "PN-1"}
                           :query [:cis.master.part/short_desc]})]
      (is (= :rejected (:engine/status r)))
      (is (= :engine.request/unreachable (:code r))
          "a planning failure must not wear the execution-failure code")
      (is (= [:cis.master.part/short_desc] (:attributes r))
          "the rejection names the attributes the plan cannot reach")
      (is (nil? (:error/category r))
          ":source would say a connector was consulted; none was"))))
  ;; 反面（连接器真抛 → :engine.execution/failed）由
  ;; lenient-mode-returns-partial-data… 的第一个 testing 钉住，不再写第二份。

(deftest lenient-mode-returns-partial-data-instead-of-failing-the-whole-call
  (let [req {:entity {:cis.master.vendor/vend_no 1001}
            :query  [:cis.master.vendor/vend_name :t.acct/broken]}]
    (testing "off by default: a resolver that throws fails the whole request"
      (let [r (aq lenient-compiled req)]
        (is (= :rejected (:engine/status r)))
        (is (= :engine.execution/failed (:code r)))))
    (testing ":lenient? true: the working sibling attribute still comes back"
      (let [r (aq lenient-compiled req {:lenient? true})]
        (is (= :ok (:engine/status r)))
        (is (= "Acme" (get-in r [:engine/data :cis.master.vendor/vend_name])))
        (is (not (contains? (:engine/data r) :t.acct/broken))
            "the broken attribute is missing rather than poisoning the whole answer")))))

;; --- :stats? ---------------------------------------------------------------

(deftest stats-option-exposes-raw-pathom-run-stats
  (let [request {:entity {:cis.master.part/sku_no 1}
                :query  [:cis.master.part/part_no]}]
    (testing "off by default: the envelope shape is exactly what it always was"
      (let [r (q :public request)]
        (is (= :ok (:engine/status r)))
        (is (not (contains? r :engine/stats)))))
    (testing ":stats? true: the raw (unscrubbed) result rides along, run-stats and all"
      (let [r (engine/query compiled (tok :public) request {:stats? true})]
        (is (= :ok (:engine/status r)))
        (is (contains? r :engine/stats))
        (is (some? (::pcr/run-stats (meta (:engine/stats r))))
            "Pathom3 attaches this by default; :stats? just stops it from being discarded")
        (is (= (:engine/data r) (select-keys (:engine/stats r) (keys (:engine/data r))))
            "the scrubbed data is a view of the same result, not a second run")))))

;; ---------------------------------------------------------------------------
;; Mutations (D5.13: the write path exists; the GRAPH is the permission)
;; ---------------------------------------------------------------------------

(def ^:private write-model
  (assoc model
         :mutations
         [{:id "demo_touch_vendor" :result_type "none"
           :params {:cis.master.vendor/vend_no {:required? true}}
           :source {:type "test-inline" :query "UPDATE v SET x = 1"}}
          {:id "demo_rename_vendor" :result_type "single"
           :params {:cis.master.vendor/vend_no {:required? true}
                    :cis.master.vendor/vend_name {:required? true}}
           :returns [:cis.master.vendor/vend_no :cis.master.vendor/vend_name]
           :source {:type "test-inline" :query "UPDATE v SET nm = :nm"}}]))

(deftest a-mutation-compiles-into-the-same-index-as-the-resolvers
  "One index, not two: 'what can this graph do' must have exactly one place to
   be answered from, or a caller can be told a mutation exists by one mechanism
   and refused by another."
  (let [c (engine/compile-env write-model)]
    (is (empty? (filter #(= :error (:severity %)) (:engine/findings c))))
    (testing "and a read still works alongside it"
      (is (= {:cis.master.vendor/vend_name "Acme"}
             (:engine/data (engine/query c nil {:entity {:cis.master.vendor/vend_no 1001}
                                                :query [:cis.master.vendor/vend_name]})))))))

(deftest a-mutation-runs-and-its-params-reach-the-connector
  (reset! fake/writes [])
  (let [c (engine/compile-env write-model)
        r (engine/query c nil ['(demo_rename_vendor {:cis.master.vendor/vend_no 1001
                                                     :cis.master.vendor/vend_name "Renamed"})])]
    (is (not= :rejected (:engine/status r)) (pr-str r))
    (is (= [{:id "demo_rename_vendor"
             :params {:cis.master.vendor/vend_no 1001
                      :cis.master.vendor/vend_name "Renamed"}}]
           @fake/writes)
        "the connector received exactly the declared params")
    (is (= "written" (get-in (:engine/data r) ['demo_rename_vendor
                                               :cis.master.vendor/vend_name]))
        "and its answer is shaped by :returns")))

(deftest a-result-type-none-mutation-answers-without-inventing-a-row
  "sql-v1 says a \"none\" write does not read a result set at all. Returning a
   fabricated row would be worse than useless: a caller would read fields off
   a record the database never produced."
  (reset! fake/writes [])
  (let [c (engine/compile-env write-model)
        r (engine/query c nil ['(demo_touch_vendor {:cis.master.vendor/vend_no 1001})])]
    (is (not= :rejected (:engine/status r)) (pr-str r))
    (is (= {:mutation/ok true} (get (:engine/data r) 'demo_touch_vendor)))
    (is (= 1 (count @fake/writes)))))

(deftest a-mutation-must-be-the-only-thing-in-its-request
  "Mixing a write with reads makes 'did the read see the write' a question only
   the planner's ordering answers — the same request could return two different
   things. Refused rather than silently ordered for the caller."
  (let [c (engine/compile-env write-model)
        r (engine/query c nil ['(demo_touch_vendor {:cis.master.vendor/vend_no 1001})
                               :cis.master.vendor/vend_name])]
    (is (= :rejected (:engine/status r)))
    (is (= :engine.request/mutation-not-alone (:code r)))))

(deftest an-unwritable-source-excludes-only-that-mutation
  "The same rule reads follow: a source that cannot be compiled costs its own
   entry a place in the index, and nothing else. A write is not special enough
   to fail a whole model that a read would not."
  (let [c (engine/compile-env
           (assoc write-model :mutations
                  (conj (:mutations write-model)
                        {:id "demo_broken" :result_type "none"
                         :params {:cis.master.vendor/vend_no {:required? true}}
                         :source {:type "test-unwritable" :query "x"}})))]
    (is (some #(= :engine.compile/source-not-executable (:code %)) (:engine/findings c))
        "named, not swallowed")
    (is (every? #(not= :error (:severity %)) (:engine/findings c))
        "a warning — the rest of the model stays usable")
    (reset! fake/writes [])
    (is (not= :rejected (:engine/status
                         (engine/query c nil
                                       ['(demo_touch_vendor {:cis.master.vendor/vend_no 1001})])))
        "the healthy mutation still runs")))

(deftest a-mutation-call-is-checked-against-its-own-declaration
  "D5.4 step ② has a write half, and it reads a DIFFERENT declaration from the
   read half: a mutation's `:params` (D2.18), not a resolver's `:eql-params`.
   Both refusals below happen before anything reaches a connector."
  (let [c (engine/compile-env write-model)]
    (testing "a misspelled parameter is refused, not silently dropped

              Dropping it would turn one write into a DIFFERENT, incomplete
              write that still reports success."
      (reset! fake/writes [])
      (let [r (engine/query c nil ['(demo_rename_vendor
                                     {:cis.master.vendor/vend_no 1001
                                      :cis.master.vendor/vend_nmae "typo"})])]
        (is (= :engine.request/unknown-mutation-param (:code r)))
        (is (empty? @fake/writes) "and nothing was written")))

    (testing "the refusal names the undeclared key and the declared set, never a value"
      (let [r (engine/query c nil ['(demo_rename_vendor
                                     {:cis.master.vendor/vend_no 1001
                                      :cis.master.vendor/vend_nmae "secret-value-xyz"})])]
        (is (= :cis.master.vendor/vend_nmae (:param r)))
        (is (re-find #":cis\.master\.vendor/vend_nmae" (:message r)))
        (is (some #{:cis.master.vendor/vend_no} (:expected r)))
        (is (not (re-find #"secret-value-xyz|1001" (pr-str r))) "no argument value echoed")))

    (testing "a string key whose keyword is declared gets the keyword hint; refused, not coerced"
      (reset! fake/writes [])
      (let [r (engine/query c nil ['(demo_rename_vendor
                                     {"cis.master.vendor/vend_no" 1001
                                      :cis.master.vendor/vend_name "secret-value-xyz"})])]
        (is (= :engine.request/unknown-mutation-param (:code r)))
        (is (= "cis.master.vendor/vend_no" (:param r)))
        (is (re-find #"parameter names are keywords: write :cis\.master\.vendor/vend_no, not \"cis\.master\.vendor/vend_no\"" (:message r)))
        (is (not (re-find #"secret-value-xyz|1001" (pr-str r))))
        (is (empty? @fake/writes))))

    (testing "a missing required parameter is refused here, not by a database constraint"
      (reset! fake/writes [])
      (let [r (engine/query c nil ['(demo_rename_vendor {:cis.master.vendor/vend_no 1001})])]
        (is (= :engine.request/missing-mutation-param (:code r)))
        (is (empty? @fake/writes))))

    (testing "a mutation this graph does not contain is unknown

              This is also where D5.13 lands: a caller may run exactly the
              mutations their graph holds, because the graph IS the grant."
      (let [r (engine/query c nil ['(demo_not_in_this_graph {})])]
        (is (= :engine.request/unknown-mutation (:code r)))))))

;; ---------------------------------------------------------------------------
;; 行级安全的运行期那一条（E1 / D5.14 ③）
;; ---------------------------------------------------------------------------

(def ^:private rowsec-model
  "一个接线正确的 row-security resolver。它的 id **故意不在** fake-connector 的
   `rows-for` 里：这条 resolver 一旦真的跑起来，假连接器会当场抛——所以下面两
   条测试若因为门漏了而放行，是**炸**，不是安静地拿到答案。"
  (fake/inline-model
   (:model
    (dialect/read-dialect
     (pr-str
            {:attributes {:t.o/id        {:type :integer :format :id :identity? true :label "O"}
                          :t.o/tenant_id {:type :string :format :text :label "Tenant"}
                          :t.o/amount    {:type :decimal :format :currency :label "Amount"}}
             :namespaces {"t.o" {:kind :master :label "O"}}
             :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
             :resolvers  [{:id "never_runs"
                           :input [:t.o/id :t.o/tenant_id]
                           :output [:t.o/id :t.o/tenant_id :t.o/amount]
                           :result_type "single"
                           :source {:type "sql"
                                    :query "SELECT * FROM o WHERE id = :id AND tenant_id = :tenant_id"}
                           :source-ref "db"
                           :row-security {:attribute :t.o/tenant_id}}]})))))

(def ^:private rowsec-compiled (engine/compile-env rowsec-model))

(deftest a-null-row-security-input-is-refused-before-any-statement
  ;; pathom 判「输入就绪」按 `contains?`：`{:t.o/tenant_id nil}` 会让 resolver
  ;; 跑起来，SQL 上绑成 `= NULL` 恰好返回 0 行，API 连接器上未必。D5.14 ③ 说
  ;; **不靠这个巧合**。
  (let [r (engine/query rowsec-compiled {:graph "t/o" :version "v1" :tier :authoring}
                        {:entity {:t.o/id 1 :t.o/tenant_id nil}
                         :query  [:t.o/amount]})]
    (is (= :rejected (:engine/status r)))
    (is (= :rowsec.input/null (:code r)))
    (is (= :t.o/tenant_id (:attribute r)) "拒绝点名是哪个属性")
    (is (= "never_runs" (:resolver-id r)) "以及哪个 resolver")
    (is (nil? (:engine/data r)))))

(deftest a-missing-row-security-input-is-still-the-planner-s-refusal
  ;; 回归：**两条路都关着，而且是两条**。键缺失由规划器判 unreachable（连接器
  ;; 一个都没被调），键在而值为 nil 由上一条判。报成同一种会让作者以为改一处
  ;; 就够了。
  (let [r (engine/query rowsec-compiled {:graph "t/o" :version "v1" :tier :authoring}
                        {:entity {:t.o/id 1}
                         :query  [:t.o/amount]})]
    (is (= :rejected (:engine/status r)))
    (is (= :engine.request/unreachable (:code r)))
    (is (nil? (:engine/data r)))))

;; ---------------------------------------------------------------------------
;; D1.9b：数据面担保的调用者（:open-atlas.caller/*）
;; ---------------------------------------------------------------------------

(def ^:private caller-compiled
  "A per-user resolver keyed on the reserved caller attribute. Like
   `rowsec-model`, its id is not in the fake connector, so reaching execution
   shows up as :engine.execution/failed rather than a quiet answer."
  (engine/compile-env
   (fake/inline-model
    (:model
     (dialect/read-dialect
      (pr-str
       {:attributes {:open-atlas.caller/email {:type :string :format :text :label "Caller"}
                     :w.item/id     {:type :integer :format :id :identity? true :label "Item"}
                     :w.item/reason {:type :string :format :text :label "Reason"}}
        :namespaces {"open-atlas.caller" {:kind :master :label "Caller"}
                     "w.item" {:kind :master :label "Item"}}
        :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
        :resolvers  [{:id "my_work_never_runs"
                      :input [:open-atlas.caller/email]
                      :output [:w.item/id :w.item/reason]
                      :result_type "single"
                      :source {:type "sql" :query "SELECT id, reason FROM items WHERE owner = :email"}
                      :source-ref "db"
                      :row-security {:attribute :open-atlas.caller/email}}]}))))))

(def ^:private caller-ctx {:graph "w/item" :version "v1" :tier :authoring})

(deftest a-request-cannot-write-the-reserved-caller-namespace
  (testing "through {:entity}"
    (let [r (engine/query caller-compiled caller-ctx
                          {:entity {:open-atlas.caller/email "ceo@example.com"} :query [:w.item/reason]})]
      (is (= :engine.gate/reserved-caller-attribute (:code r)))
      (is (= :open-atlas.caller/email (:attribute r)))
      (is (not (str/includes? (pr-str r) "ceo@example.com")) "the refusal does not echo the claimed value")))
  (testing "through an ident"
    (let [r (engine/query caller-compiled caller-ctx
                          [{[:open-atlas.caller/email "ceo@example.com"] [:w.item/reason]}])]
      (is (= :engine.gate/reserved-caller-attribute (:code r)))))
  (testing "even when the host also vouches for a user"
    (let [r (engine/query caller-compiled caller-ctx
                          {:entity {:open-atlas.caller/email "ceo@example.com"} :query [:w.item/reason]}
                          {:caller {:open-atlas.caller/email "alex@example.com"}})]
      (is (= :engine.gate/reserved-caller-attribute (:code r))))))

(deftest a-vouched-caller-reaches-the-resolver-and-its-absence-is-refused
  (let [served (engine/query caller-compiled caller-ctx [:w.item/reason]
                             {:caller {:open-atlas.caller/email "alex@example.com"}})
        missing (engine/query caller-compiled caller-ctx [:w.item/reason])]
    (is (= :engine.execution/failed (:code served))
        "the gate let the vouched value through; the fake connector proves execution was reached")
    (is (= :engine.request/unreachable (:code missing)) "no vouched user, no per-user rows")))

(deftest malformed-caller-values-are-refused-not-passed-on
  (doseq [caller [{:open-atlas.caller/email ""} {:open-atlas.caller/email nil}
                  {:w.item/id "1"} "alex@example.com"]]
    (is (= :engine.gate/reserved-caller-attribute
           (:code (engine/query caller-compiled caller-ctx [:w.item/reason] {:caller caller})))
        (pr-str caller))))

(deftest a-present-non-nil-row-security-input-passes-the-gate
  ;; 门只判空，不判值——放行之后是那条 resolver 自己的事（这里由假连接器
  ;; 的 `case` 抛出来证明：门确实放行了，请求确实到了执行那一步）。
  (let [r (engine/query rowsec-compiled {:graph "t/o" :version "v1" :tier :authoring}
                        {:entity {:t.o/id 1 :t.o/tenant_id "acme"}
                         :query  [:t.o/amount]})]
    (is (= :rejected (:engine/status r)))
    (is (= :engine.execution/failed (:code r))
        "不是 :rowsec.input/null——门放行了，炸在执行期")))
