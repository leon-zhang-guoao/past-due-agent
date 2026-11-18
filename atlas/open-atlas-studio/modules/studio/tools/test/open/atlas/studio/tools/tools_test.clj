(ns open.atlas.studio.tools.tools-test
  "B4.2 三个建模面工具的守门测试。

   **装料是 demo 模型**（`fixtures/demo/model.edn`，207 属性 / 23 词条 /
   12 张码值表）。玩具数据证不出 `query_model` 有没有**真找到缺口**
   ——三个属性的玩具里没有零绑定的词条、没有没进任何 resolver `:output` 的属性、
   也没有同一个 local name 在两个 entity 上重现（那正是 `resolve_target` 的
   `:exact-ambiguous` 唯一的来源）。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.search :as search]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.tools :as tools]
            [open.atlas.studio.tools.impl.query-model :as qm]
            [open.atlas.studio.tools.impl.resolve-target :as rt]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

;; ---------------------------------------------------------------------------
;; 装料（照 studio/pool 的 pool_test）
;; ---------------------------------------------------------------------------

(defn- repo-root []
  (loop [d (io/file (System/getProperty "user.dir"))]
    (cond (nil? d) (throw (ex-info "repo root not found" {}))
          (.isDirectory (io/file d "fixtures/demo")) d
          :else (recur (.getParentFile d)))))

(defn- tmp-dir [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix (into-array java.nio.file.attribute.FileAttribute []))))

(defn- rm-r [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-r (.listFiles f)))
    (.delete f)))

(defonce ^{:doc "demo 模型。`fixtures/demo/model.edn` 是它的家（见那里的 README）。"}
  demo-model
  (delay (edn/read-string (slurp (io/resource "model.edn")))))

(def ^:dynamic *conn* nil)

(defn with-loaded-pool [f]
  (let [cfg (pool/config {:backend :mem :id (str (gensym "tools"))})]
    (pool/delete-pool! cfg)
    (let [conn (pool/open-pool! cfg)
          ;; 装料走 import 而不是 308 次 action：装料本身不是被测行为
          ;; （pool_test 的同一条理由）。
          objs (pool/dialect->objects @demo-model)
          r    (pool/import-pool! conn {:pool/version 1
                                        :pool/objects (mapv #(assoc % :pool/revision 1 :pool/workspace "demo") objs)})]
      (assert (empty? (:findings r)) (pr-str (:findings r)))
      (binding [*conn* conn]
        (try (f) (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))))

(use-fixtures :each with-loaded-pool)

(defn- ctx [& {:as extra}] (merge {:conn *conn*} extra))

(defn- resolve!
  "过去 `resolve_target` 那个 handler 做的事，逐字照搬：索引池对象与词条，
   按输入词裁决。"
  [q & {:as extra}]
  (let [db (pool/db *conn*)
        model (pool/as-dialect db)
        abbr (:abbreviations (:ctx extra))]
    (rt/resolve-target (rt/index (pool/objects db) (:terms model) abbr)
                       q
                       {:type (:type extra)
                        :namespace (:namespace extra)
                        :abbreviations abbr
                        :max-candidates (:max-candidates (:ctx extra))})))

(def ^:private config-values
  "demo 模型里真实存在的配置值。任何拒绝里出现即泄漏（B4 退出条件 2 / finding-v1 §5）。"
  ["jdbc:sqlite" "cis-demo.db" "data/cis-demo"])

(defn- rejected! [res]
  (let [fs (:findings res)]
    (is (seq fs) "拒绝必须有 finding，不许静默失败")
    (is (every? tools/finding? fs) "每条 finding 过 finding-v1 §2 封闭 schema")
    (is (some #(= :error (:severity %)) fs))
    (let [s (binding [*print-namespace-maps* false] (pr-str fs))]
      (doseq [v config-values]
        (is (not (str/includes? s v)) (str "finding 泄漏了 " (pr-str v) "：" s))))
    (set (map :code fs))))

;; ---------------------------------------------------------------------------
;; 核心：同源 —— 声明与分派在每一种能力组合下集合恒等
;; ---------------------------------------------------------------------------

(def ^:private caps-combos [#{} #{:read} #{:write} #{:read :write}])

(deftest list-and-dispatch-same-source-test
  (doseq [caps caps-combos]
    (let [reg    (tools/registry caps)
          listed (set (map #(get % "name") (tools/tools-list reg)))]
      ;; 裁定九 删掉了 `advanced` 那把钥匙，于是 toolkit 裁定 ②
      ;;「列出集合 = 可调集合」恢复为**唯一**规则，且逐字恒等。
      (is (= listed (tools/tool-names reg)) (str "能力 " (pr-str caps) " 下漂移了"))
      (is (= listed (set (map :name (filter #(contains? caps (:capability %))
                                            (tools/declarations)))))))))

(def ^:private the-twenty
  "8.4.16 裁定九那张表减去 `evaluate_draft_eql`（8.4.18 裁定十一退役），
   加上 `declare_source`（8.4.24 裁定十八），再按 8.4.25 裁定十七的需求语法
   重排写面：`add_resolver` 拆成 `add_thing` / `add_relation`，新增
   `add_metric`，`assign_term` 更名 `define_concept`。逐条逐序。
   **改这个 vector 要先改裁定**。"
  ["list_graphs" "inspect_graph" "query_model" "describe_source" "read_object"
   "create_graph" "declare_source"
   "add_thing" "add_attribute" "add_relation" "add_link" "add_metric"
   "update_resolver" "update_attribute"
   "remove_objects" "describe_objects" "define_concept" "unassign_term"
   "change_graph_members" "list_recent_queries" "list_saved_queries" "save_saved_query" "delete_saved_query"
   ;; `reclaim_version`: owner ruling 2026-10-10 (R3.5 reclaim exposed to agents).
   "publish_graph" "reclaim_version"
   "list_graph_tokens" "issue_graph_token" "revoke_graph_token"
   "list_credentials" "save_credential" "remove_credential"
   "list_sources" "probe_api_source" "remove_source"
   "rename_graph" "duplicate_graph" "merge_graphs" "archive_graph" "restore_graph" "delete_graph" "rollback_graph" "check_graph_cut" "set_default_query" "set_package_name" "set_workspace_default_graph" "reclaim_unused_versions"
   "list_graph_access" "grant_graph_access" "revoke_graph_access" "list_workspace_tokens" "revoke_workspace_token" "list_workspace_objects"
   "semantic_draft" "import_semantic_graph" "get_graph" "upload_graph"])

(def ^:private the-seven
  "裁定十一挂进来的数据面七条，逐条逐序。"
  ["list_entities" "list_attributes" "search_attributes" "execute_eql"
   "analyze_result" "compute_metric" "list_mutations"])

(def ^:private fake-data-plane
  "Stand-ins for `open.atlas.server.mcp/mountable-declarations`.

   Stand-ins because D1.8 forbids this module from depending on
   `modules/server`: what is testable HERE is the mounting — the count, the
   order, the added `graph` parameter, the `:draft` mark. That the real seven
   are these seven is pinned where both are on the classpath, in
   `apps/studio`."
  (mapv (fn [n] {:name n :description (str "stub " n) :capability :read
                 :params [{:name "query" :type :string :required? false
                           :description "stub"}]
                 :handler (fn [_ctx _args] {:stub n})})
        the-seven))

(deftest tools-list-is-exactly-twenty-seven-in-a-fixed-order-test
  "裁定十一「工具计数」，8.4.25 裁定十七重数：`tools/list` 恒为 27 条——
   Builder 专属二十条在前,
   数据面七条在后——顺序固定，加一条要改裁定。

   顺序是契约的一部分：agent 先读名字再读描述（8.4.5 的教训），而两次
   `tools/list` 逐字节相同才让客户端 diff 得动。"
  (let [all (into the-twenty the-seven)
        reg (tools/registry #{:read :write} fake-data-plane)]
    (is (= 56 (count (tools/declarations))) "28 + reclaim_version + three graph-token + three credential + three source + twelve graph-lifecycle + five access tools (owner 2026-10-10/11)")
    (is (= the-twenty (mapv :name (tools/declarations))))
    (is (= 63 (count (tools/declarations fake-data-plane))))
    (is (= all (mapv :name (tools/declarations fake-data-plane))))
    (is (= all (mapv #(get % "name") (tools/tools-list reg))))
    (testing "只读 token 看到的是同一张表去掉写工具，顺序不变（裁定 ②）"
      (let [ro (mapv #(get % "name") (tools/tools-list (tools/registry #{:read} fake-data-plane)))]
        (is (= ro (filterv (complement #{"create_graph" "save_saved_query" "delete_saved_query"
                                               "semantic_draft" "import_semantic_graph" "upload_graph" "reclaim_version"
                                               "issue_graph_token" "revoke_graph_token" "save_credential"
                                               "rename_graph" "merge_graphs" "archive_graph" "restore_graph" "delete_graph" "rollback_graph" "set_default_query" "set_package_name" "set_workspace_default_graph" "reclaim_unused_versions"
                                               "grant_graph_access" "revoke_graph_access" "revoke_workspace_token"}) all)))))
    (testing "挂进来的七条各多一个 graph 参数，其余逐字不动"
      (doseq [d (drop 56 (tools/declarations fake-data-plane))
              :let [orig (first (filter #(= (:name d) (:name %)) fake-data-plane))]]
        (is (= "graph" (:name (first (:params d)))))
        (is (= (:params orig) (vec (rest (:params d)))))
        (is (= (:description orig) (:description d)))))
    (testing "响应带 :draft true（agent 据此分清自己在测还是在用）"
      (is (= {:stub "execute_eql" :draft true :graph "w/g"}
             (tools/call reg (assoc (ctx) :runtime {}) "execute_eql" {:graph "w/g"}))))
    (testing "宿主没挂环境接缝时结构化拒绝，而不是掉进数据面的 token 缺省"
      (is (= [:tools.draft/unavailable]
             (mapv :code (:findings (tools/call reg (ctx) "execute_eql" {:graph "w/g"}))))))))

(def ^:private the-deleted-twenty-six
  "裁定九退出 MCP 的 26 条，加上裁定十一退役的 `evaluate_draft_eql`。按名调用必须得 `:toolkit.tool/unknown`。"
  ;; rename/archive/restore/delete/duplicate/merge/rollback came back on
  ;; 2026-10-11 (owner ruling: Studio managed over MCP) and left this list.
  ;; grant/revoke_graph_access came back on 2026-10-11 too.
  ["confirm_candidates" "execute_draft_mutation" "execute_action"
   "generate_from_source" "plan_from_source"
   "graph_members" "project_graph" "serving_status" "list_draft_entities"
   "list_draft_attributes" "preflight_graph" "evaluate_draft_eql"
   "remove_attribute" "remove_resolver" "remove_link" "define_term" "update_entity"
   "add_to_graph" "remove_from_graph" "resolve_target"])

(deftest deleted-tools-are-gone-not-hidden-test
  "裁定九「删掉，不是隐藏」：26 条退出 MCP 的工具在**每一种**能力组合下都既不
   列出、也调不动，拒绝与压根没有过这个名字逐字节相同。

   （表里 26 条，这里 29 个名字：`add_to_graph` / `remove_from_graph` /
   `resolve_target` 在合并那一行里，同样必须消失。）"
  (doseq [caps [#{:read} #{:read :write}]]
    (let [reg (tools/registry caps)]
      (doseq [t the-deleted-twenty-six]
        (is (not (contains? (tools/tool-names reg) t)) (str t " 还在 " (pr-str caps)))
        (is (= [:toolkit.tool/unknown]
               (mapv :code (:findings (tools/call reg (ctx) t {}))))
            (str t " 应当与没有这个名字逐字节相同"))
        (is (= (:findings (tools/call reg (ctx) t {}))
               (:findings (tools/call reg (ctx) "no_such_tool" {}))))))))

(deftest advanced-key-is-gone-test
  "裁定九删掉了第三把钥匙。**它不是「声明了也没用」，是这个词不存在了**：
   `advanced-tools` 这个 var 没了，而 toolkit 的能力词表只认两把钥匙，所以
   一个还在声明它的老客户端在宿主那一层就被滤掉（`builder-mcp` 的
   `client-declarable`），根本递不到这里。"
  (is (nil? (resolve 'open.atlas.studio.tools/advanced-tools)))
  (is (thrown? AssertionError (tools/registry #{:read :advanced})))
  (is (= 2 (count (into #{} (map :capability) (tools/declarations))))))

(deftest read-only-mount-test
  (let [reg (tools/registry #{:read})]
    (testing "只有读钥匙时挂读工具（含每条写工具的 plan 面），会写的那条缺席"
      ;; 这批工具里**只有 `create_graph` 是 `:write`**：它没有 plan 面——注册一张
      ;; 新图要么做了要么没做。其余会写的工具都是 `:read` + `apply`，理由写在
      ;; `intent_declarations/capability-note`：plan 什么都不写，而退出条件要求
      ;; 只读 token **看得见 plan**、apply 被拒。声明成 `:write` 会让它对只读
      ;; token 整条缺席（裁定 ②），连 plan 一起没了；钥匙因此查在 apply 那一步。
      ;; `publish_graph` 在裁定九合并后同样如此。
      ;; `save_saved_query` / `delete_saved_query` have no plan face either
      ;; (the save's own check is the plan), so they are `:write` like it.
      (is (= (disj (set the-twenty) "create_graph" "save_saved_query" "delete_saved_query"
                   "semantic_draft" "import_semantic_graph" "upload_graph" "reclaim_version"
                   "issue_graph_token" "revoke_graph_token" "save_credential"
                   "rename_graph" "merge_graphs" "archive_graph" "restore_graph" "delete_graph" "rollback_graph" "set_default_query" "set_package_name" "set_workspace_default_graph" "reclaim_unused_versions"
                   "grant_graph_access" "revoke_graph_access" "revoke_workspace_token")
             (tools/tool-names reg)))
      (is (not (contains? (tools/tool-names reg) "create_graph"))
          "建图是写，只读钥匙上必须缺席，不是列出来再拒"))
    (testing "dispatch 报「不认识这个工具」而不是「拒绝」"
      (let [fs (:findings (tools/call reg (ctx) "create_graph" {:name "x"}))]
        (is (= [:toolkit.tool/unknown] (mapv :code fs)))))
    (testing "被过滤掉的与压根不存在的逐字节相同（裁定 ②）"
      (is (= (:findings (tools/call reg (ctx) "create_graph" {}))
             (:findings (tools/call reg (ctx) "no_such_tool" {})))))
    (testing "池一个字节都没动"
      (is (= 207 (count (filter #(= :attribute (:pool/type %))
                                (pool/objects (pool/db *conn*)))))))))

;; ---------------------------------------------------------------------------
;; `confirm_candidates` 与 `execute_action` —— **MCP 声明已删**（8.4.16 裁定九）
;; ---------------------------------------------------------------------------
;;
;; 前者是 owner 签字（本就该在界面上做），后者过于抽象（剩余用途是 source 登记与
;; metric / mutation / unit / group 那类低频界面活）。两个**池函数**没删——
;; `pool/confirm-candidates!` 与 `pool/execute-action` 由 `studio/pool` 自己的
;; 测试守，Builder 的 HTTP 路由也还在调它们；这里删掉的是「工具面包对了没有」
;; 那两条，因为工具面已经没有这两条。它们不再被列、不再调得动，由
;; `deleted-tools-are-gone-not-hidden-test` 钉住。

;; ---------------------------------------------------------------------------
;; read_object —— 写面的读对子（8.4.10 P6）
;; ---------------------------------------------------------------------------

(deftest read-object-is-the-pools-answer-unreshaped-test
  (let [reg (tools/registry #{:read})]
    (testing "读回来的就是 `pool/object` 的四个键，工具面一个字节不重塑"
      (let [res (tools/call reg (ctx) "read_object"
                            {:type :attribute :id :cis.master.part/sku_no})]
        (is (= (pool/object (pool/db *conn*) :attribute :cis.master.part/sku_no) res))
        (is (= :attribute (:pool/type res)))
        (is (map? (:pool/entry res)))))
    (testing "**与写目标闸门同源**（8.4.10 退出条件 1）：同一个对象的
              `expected_revision` 与 `:pool/revision` 相等，否则两条读面会给出
              两个不同的「按哪个版本规划」，而写只认一个。闸门自裁定九起是
              意图工具内部的一步，不再是一条 MCP 工具，判定本身没变"
      (let [rt-res (resolve! "cis.master.part/sku_no")
            {:keys [type id expected_revision]} (:action-params rt-res)]
        (is (= :exact-unique (:verdict rt-res)) (pr-str rt-res))
        (is (= expected_revision
               (:pool/revision (tools/call reg (ctx) "read_object" {:type type :id id}))))))
    (testing "池里没有的对象是**结构化拒绝**，不是 nil：一个 JSON null 说不出
              是两个入参里的哪一个错了"
      (let [res (tools/call reg (ctx) "read_object" {:type :unit :id "NO-SUCH-UNIT"})]
        (is (contains? (rejected! res) :tools.read/unknown-object))
        (is (= [:unit "NO-SUCH-UNIT"] (:path (first (:findings res))))
            "path 点名 `[type id]`——池自己的对象键")))
    (testing "入参不合 schema 时拒绝停在 toolkit 层，池没被调到"
      (is (contains? (rejected! (tools/call reg (ctx) "read_object" {:type :unit}))
                     :toolkit.args/missing))
      (is (contains? (rejected! (tools/call reg (ctx) "read_object"
                                            {:type :not-a-type :id "USD"}))
                     :toolkit.args/invalid)))
    ;; OQ-MCP-WRITE-ERGONOMICS item 2, ruled 2026-09-07 (doc 106 §106.14 item 9).
    (testing "一个 JSON 客户端只送得出字符串，属性的身份却是全限定 kw：
              **改写拼法，不是报「池里没有」**"
      (let [res (tools/call reg (ctx) "read_object"
                            {:type :attribute :id "cis.master.part/sku_no"})]
        (is (= :attribute (:pool/type res)) (pr-str res))
        (is (= :cis.master.part/sku_no (:pool/id res)))))
    (testing "「id 写错」与「池里没有」是两条不同的拒绝，前者带字面样例"
      (let [res (tools/call reg (ctx) "read_object" {:type :attribute :id "sku_no"})]
        (is (contains? (rejected! res) :tools.read/malformed-id))
        (is (= "a qualified keyword, e.g. \"cis.party/customer_no\""
               (:expected (first (:findings res))))))
      (is (contains? (rejected! (tools/call reg (ctx) "read_object"
                                            {:type :attribute :id "no.such/attr"}))
                     :tools.read/unknown-object)))))

;; ---------------------------------------------------------------------------
;; inspect_graph —— 声明这一侧（投影本身由 studio/graph 的测试守）
;; ---------------------------------------------------------------------------

(deftest inspect-graph-declaration-test
  (let [reg (tools/registry #{:read})]
    (testing "宿主没挂 graph 服务时按名字拒绝，而不是抛一个读起来像调用方 bug 的 NPE"
      (is (contains? (rejected! (tools/call reg (ctx) "inspect_graph" {:graph "demo/sales"}))
                     :tools.graph/unavailable)))
    (testing "**`view=entries` 缺 `kind` 是超限拒绝**：整个投影模型全量吐出来是这一层
              的信封说了不算，所以它在**调服务之前**就拒绝——上面那条证明了这里
              连服务都没有"
      (let [res (tools/call reg (ctx) "inspect_graph"
                            {:graph "demo/sales" :view :entries})]
        (is (contains? (rejected! res) :tools.graph/entries-need-kind))
        (is (= [:kind] (:path (first (:findings res)))) "点名的是缺的那个参数")))
    (testing "枚举在声明上：投影不产出的组根本填不进来"
      (is (contains? (rejected! (tools/call reg (ctx) "inspect_graph"
                                            {:graph "demo/sales" :view :entries
                                             :kind :navigations}))
                     :toolkit.args/invalid)
          "`:navigations` 顶层组自 D2.29 起不存在：join 已并进 `:attributes`"))
    (testing "三个 view 就是全部——第四个词在声明上被枚举挡住"
      (is (contains? (rejected! (tools/call reg (ctx) "inspect_graph"
                                            {:graph "demo/sales" :view :everything}))
                     :toolkit.args/invalid)))
    (testing "九个组的词表只有一份：`tools/list` 的枚举读的就是它"
      (let [enum (->> (tools/tools-list reg)
                      (some #(when (= "inspect_graph" (get % "name")) %))
                      (#(get-in % ["inputSchema" "properties" "kind" "enum"])))]
        (is (= (mapv #(str (symbol %)) tools/projected-kinds) enum))
        (is (= 9 (count enum)))))))

;; ---------------------------------------------------------------------------
;; query_model —— 在真模型上真找出缺口，每条都带得动的 action
;; ---------------------------------------------------------------------------

(defn- report [& {:as args}]
  (tools/call (tools/registry #{:read}) (ctx) "query_model" (or args {})))

(deftest query-model-finds-real-gaps-test
  (let [r (report)]
    (testing "七条具名查询全跑"
      (is (= tools/query-names (:queries r)))
      (is (= (set tools/query-names) (set (keys (:counts r))))))
    (testing "在 demo 真模型上确实找出了缺口（玩具数据证不出这一条）"
      (is (pos? (:count r)))
      (is (= (:count r) (count (:gaps r))))
      ;; **不再逐条断言「这一类必须非空」。** 那种断言把「模型是健康的」写成了
      ;; 失败：owner 修完一类缺口，守着它的测试就变红，而红的原因是好事——
      ;; 于是下一个人只能把断言删掉，覆盖跟着一起没。
      ;; 「每一条查询都产得出缺口」由 query-model-every-query-produces-a-well-formed-gap-test
      ;; 在一份**故意每类都犯一次**的最小模型上守着，那份模型不会被修好。
      ;; 这里只守「真模型上确实跑得出结果、形状合规」。
      (is (pos? (get (:counts r) :attribute/code-format-without-values))
          "码值表那一类 owner 明确说了后期再补，所以它此刻必然非空——
           哪天它也归零了，把这条一起删掉，不要改成期待 0"))
    (testing "裁定 ⑥：每条缺口都带一个能修它的 action（8.4.21 裁定十五 ③ 起,
              也可以是一条 :repair——修 unreachable 的是一次工具调用，不是池 action）"
      (doseq [g (:gaps r)]
        (if (:action g)
          (do (is (contains? #{:create :update :delete} (:action (:action g))) (pr-str g))
              (is (contains? (set pool/object-types) (:type (:action g))) (pr-str g)))
          (is (string? (:tool (:repair g))) (pr-str g)))
        (is (string? (:message g)))
        (is (vector? (:fill g)))
        ;; :fill 与 action 里的标记必须一一对应——一个漏标的标记会让调用方
        ;; 把 :tools/fill-in 当成真值写进池。
        (is (= (count (:fill g))
               (count (filter #(= tools/fill-in %) (tree-seq coll? seq (:action g))))))))))

(deftest query-model-every-query-produces-a-well-formed-gap-test
  (testing "七条查询**每一条**都产得出缺口，且形状都合规（裁定 ⑥）"
    ;; 真模型上大部分缺口已经被修掉了（漂移 0、同义词 0），逐 gap 的形状检查于是
    ;; 只剩两三类在跑。这条用一份**故意每一类都犯一次**的最小模型补回覆盖：
    ;; 七条查询各出至少一条，逐条过同一套形状断言。
    (let [model {:namespaces {"m.a" {} "m.b" {} "m.c" {}}
                 :terms {"t/unused" {:label "没人绑"}
                         "t/bound"  {:label "有人绑"}}
                 :attributes {:m.a/no_desc   {:type :string}
                              :m.a/code_col  {:type :string :format :code}
                              :m.a/orphan    {:type :string}
                              :m.a/bound_1   {:type :string :term "t/bound"}
                              :m.a/bound_2   {:type :string :term "t/bound"}
                              :m.b/id        {:type :integer :identity? true}
                              ;; 8.4.21 裁定十五 ③: 一个谁也到不了的 entity——
                              ;; 没有根集合，也没有任何 join 属性指着它。
                              :m.c/id        {:type :integer :identity? true}}
                 :resolvers [{:id "r" :output [:m.a/no_desc :m.a/code_col
                                               :m.a/bound_1 :m.a/bound_2 :m.b/id]}]}
          r (#'qm/run model {} {})]
      (doseq [q tools/query-names]
        (is (pos? (get (:counts r) q)) (str q " 在这份「每类都犯一次」的模型上没出缺口")))
      (doseq [g (:gaps r)]
        (if (:action g)
          (do (is (contains? #{:create :update :delete} (:action (:action g))) (pr-str g))
              (is (contains? (set pool/object-types) (:type (:action g))) (pr-str g)))
          (is (string? (:tool (:repair g))) (pr-str g)))
        (is (string? (:message g)))
        (is (vector? (:fill g)))
        (is (= (count (:fill g))
               (count (filter #(= tools/fill-in %) (tree-seq coll? seq (:action g))))))))))

(deftest query-model-unlisted-is-a-metric-not-a-gap-test
  (testing "「未列出属性」是账单项不是缺陷（D2.28）：它在 :metrics 里，不在 gap 清单里"
    (let [r (report)]
      (is (not (contains? (set tools/query-names) :attribute/unlisted))
          "它不是具名查询——不列出是一次建模判断，不是要修的缺口；
           混进 gap 清单会诱使人把它追到 0，等于取消这个建模手段")
      (is (every? #(not= :attribute/unlisted (:query %)) (:gaps r)))
      (let [m (get-in r [:metrics :attribute/unlisted])]
        (is (pos? (:count m)) "demo 模型的审计族标了 :listed? false，这个数字不该是 0")
        (is (= (:count m) (reduce + 0 (vals (:by-entity m)))) "分布加起来等于总数")
        (is (map? (:by-entity m)))
        (is (not (contains? m :action))
            "指标不带 :action——报告给数字，不递一条催人执行的命令")))))

(deftest query-model-declaration-drift-is-zero-test
  (testing "D2.2 的存在性闭包在 demo 模型上是闭合的：每个登记的属性都有产出方"
    ;; 本轮把 6 个漂移属性（五条行项 resolver 的父单号 + 两张主档的 cust_no）
    ;; 补进了各自 resolver 的 `:output`（当年 B2 scope.edn 的 `:correct-resolvers`
    ;; 与 additions 里的 `demo_cpo_details`）。这条断言钉住那个结果：再有一个属性
    ;; 登记了却没有产出方，这里就红。
    (is (zero? (get (:counts (report)) :attribute/declaration-drift)))))

(deftest query-model-drift-action-has-two-branches-test
  (testing "漂移缺口：**要么直接可跑，要么标出待补位置——没有第三种**"
    ;; 真模型上漂移已经是 0（上一条断言钉着），所以两支分别用一份最小模型证。
    ;; 造两个都没有产出方的属性：一个没人引用（可 delete），一个是 link 端点
    ;; （delete 必被 `:pool.reference/inbound` 拒，所以给待补型）。
    (let [model {:attributes {:m.a/orphan {:type :string}
                              :m.a/linked {:type :string}
                              :m.b/id     {:type :integer :identity? true}}
                 :namespaces {"m.a" {} "m.b" {}}
                 :resolvers  [{:id "r" :output [:m.b/id]}]
                 :links      [{:from :m.a/linked :to :m.b/id}]}
          revs {[:attribute :m.a/orphan] 1 [:attribute :m.a/linked] 1}
          gs  (:gaps (#'qm/run model revs {:query :attribute/declaration-drift}))
          by  (into {} (for [g gs] [(second (:target g)) g]))]
      (is (= #{:m.a/orphan :m.a/linked} (set (keys by))))
      (is (= [] (:fill (by :m.a/orphan))) "没被引用的那些，delete 骨架原样就跑得动")
      (is (= :delete (:action (:action (by :m.a/orphan)))))
      (is (seq (:fill (by :m.a/linked))) "被引用的那些，给的是待补型骨架")
      (is (= :resolver (:type (:action (by :m.a/linked))))
          "待补型修的是「哪条 resolver 该产出它」，不是删属性")
      (is (str/includes? (:message (by :m.a/linked)) "deleting it is not available")
          "消息要说明为什么这一条不能删"))))

(deftest query-model-fill-marker-is-illegal-test
  (testing "待补的 action 原样跑会被 B0 结构化拒绝——比静默删掉该补的键便宜
            （标记是非法值是有意的）"
    ;; 走 `pool/execute-action`，因为 `execute_action` 的 MCP 声明在裁定九删了。
    ;; 被钉住的是同一句话：骨架**照原样跑得到结构化拒绝**，而不是静默少写一个键。
    (let [g (first (filter #(seq (:fill %))
                           (:gaps (report :query :attribute/code-format-without-values))))
          a (:action g)]
      (is (some? g) "真模型上应当有需要人补码值表的属性")
      (is (seq (rejected! (pool/execute-action
                           *conn*
                           (cond-> (select-keys a [:action :type :id :expected_revision])
                             (contains? a :entry) (assoc :entry (:entry a)))
                           {:entrypoint :mcp})))))))

(deftest query-model-single-query-and-prefix-test
  (let [one (report :query :term/zero-binding)]
    (is (= [:term/zero-binding] (:queries one)))
    (is (every? #(= :term/zero-binding (:query %)) (:gaps one))))
  (testing "namespace 前缀圈选（同 D3.11 的圈法）"
    (let [r (report :namespace "cis.purchase")]
      (is (every? (fn [{[t id] :target}]
                    (str/starts-with? (case t :attribute (namespace id) (str id)) "cis.purchase"))
                  (:gaps r)))
      (is (< (:count r) (:count (report))))))
  (testing "不认识的具名查询在 toolkit 层就被枚举挡住"
    (is (contains? (rejected! (report :query :no/such-query)) :toolkit.args/invalid))))

;; ---------------------------------------------------------------------------
;; 写目标闸门 —— 四个裁决，只有 :exact-unique 带可执行参数
;; ---------------------------------------------------------------------------
;;
;; **它不再是一条 MCP 工具**（8.4.16 裁定九）：问「这些词指的是哪个对象」的
;; agent 总是马上要拿这个答案去做事，而意图工具直接收词。判定一个字没改，
;; 意图工具的每一个名字入参都走它（`intent/resolve-name` / `resolve-any`），
;; 所以这里改的是**怎么调它**，四个裁决的断言原样保留。

(defn- no-executable-params! [res]
  (is (nil? (:target res)) "只有 :exact-unique 才允许带 :target")
  (is (nil? (:action-params res))
      "安全只能是参数的缺席（principles）——拿着格式正确 action 的 agent 一定会跑它")
  (is (not-any? #{:target :action-params} (keys res))))

(deftest resolve-target-exact-unique-test
  (let [res (resolve! "cis.master.part/sku_no")]
    (is (= :exact-unique (:verdict res)) (pr-str res))
    (is (= [:attribute :cis.master.part/sku_no] (:target res)))
    (testing "带出的参数直接拼得进 execute_action"
      (let [p (:action-params res)]
        (is (= :attribute (:type p)))
        (is (= :cis.master.part/sku_no (:id p)))
        (is (integer? (:expected_revision p)))))))

(deftest resolve-target-exact-ambiguous-test
  (testing "local name 跨 entity 重名是歧义唯一的真实来源（D2.25：72% 的 local name 重名）"
    (let [res (resolve! "entry_id")]
      (is (= :exact-ambiguous (:verdict res)) (pr-str res))
      (is (> (count (:candidates res)) 1))
      (no-executable-params! res)))
  (testing "限定类型不会把歧义变成确定——它只是缩小候选集"
    (let [res (resolve! "entry_id" :type :attribute)]
      (is (= :exact-ambiguous (:verdict res)))
      (no-executable-params! res))))

(deftest resolve-target-fuzzy-test
  (let [res (resolve! "part sku")]
    (is (= :fuzzy (:verdict res)) (pr-str res))
    (is (seq (:candidates res)))
    (no-executable-params! res)))

(deftest resolve-target-none-test
  (let [res (resolve! "这个词在模型里根本不存在 zzzz")]
    (is (= :none (:verdict res)))
    (is (empty? (:candidates res)))
    (no-executable-params! res)))

(deftest resolve-target-verdicts-are-closed-test
  (testing "四个裁决就是全部"
    (is (= [:exact-unique :exact-ambiguous :fuzzy :none] tools/verdicts))
    (doseq [q ["cis.master.part/sku_no" "entry_id" "part sku" "zzzz-nope"]]
      (is (contains? (set tools/verdicts) (:verdict (resolve! q)))))))

(deftest resolve-target-abbreviations-are-input-test
  (testing "默认空表：不认识的缩写落到 :fuzzy 或 :none，那是诚实的回答（裁定 ⑤）"
    (is (contains? #{:fuzzy :none} (:verdict (resolve! "part sku number")))))
  (testing "owner 给了表，两侧同时展开之后才对得上"
    (let [res (resolve! "cis master part sku number"
                        :ctx {:abbreviations {:global {"no" "number"}}})]
      (is (= :exact-unique (:verdict res)) (pr-str res))
      (is (= [:attribute :cis.master.part/sku_no] (:target res)))))
  (testing "按域覆盖不影响别的域"
    (is (= :exact-unique
           (:verdict (resolve! "cis master part sku number"
                               :namespace "cis.master.part"
                               :ctx {:abbreviations
                                     {:global {}
                                      :by-namespace {"cis.master.part" {"no" "number"}}}}))))))

(deftest resolve-target-envelope-test
  (testing "候选超限拒绝而不是截断——调用方正要靠这份清单判断歧义（CLAUDE.md）"
    (let [res (resolve! "entry_id" :ctx {:max-candidates 1})]
      (is (contains? (rejected! res) :tools.resolve/candidates-over-limit))
      (no-executable-params! res))))

;; ---------------------------------------------------------------------------
;; B4.3 裁定 ③ —— 匹配层共用、裁决层各自保留
;; ---------------------------------------------------------------------------
;;
;; 这条测试同时钉住两句话，它们必须**一起**成立，否则裁定 ③ 就只剩一半：
;;
;;   1. **匹配相同**——同一个输入词，写闸门 `resolve_target` 与读解析
;;      `search_attrs` 折叠出的候选集合逐字节相同（共用 common/search 的匹配层）;
;;   2. **裁决不同**——两套裁决词汇互不相交，谁也不许合并进谁
;;      （写侧错了是静默的模型损坏，读侧错了只是白看一眼，principles）。
;;
;; 只测 1 会让人以为可以把两个工具合成一个；只测 2 会让匹配层重新分叉成两份
;; ——而分叉的那天没有人会知道（CLAUDE.md）。

(defn- attr-objects
  "该 entity 的属性 → 写目标闸门吃的池对象形状。"
  [model entity]
  (for [[k e] (:attributes model) :when (= entity (namespace k))]
    {:pool/type :attribute :pool/id k :pool/entry e :pool/revision 1}))

(defn- rt-matched
  "写侧匹配到的**属性集合**（裁决之前的那一步）。"
  [model entity q]
  (let [i (rt/index (attr-objects model entity) (:terms model) nil)]
    (set (map second (search/exact-hits (:by-phrase i) (search/fold nil entity q))))))

(defn- rd-matched
  "读侧匹配到的属性集合：`:direct`/`:via-term` 是一个，`:ambiguous` 是一组。"
  [r]
  (cond (:attribute r)  #{(:attribute r)}
        (:candidates r) (set (map :attribute (:candidates r)))
        :else           #{}))

(def ^:private ambiguous-model
  "真模型上 `:ambiguous` 现在是 0 条（`sso/cust_no` 已删），而它恰是两侧裁决
   词汇分叉得最明显的那一支——所以造一份最小合成模型，不改 fixtures。"
  {:terms      {"demo/customer_no" {:label "客户号" :synonyms ["客户编号"]}}
   :namespaces {"demo.order" {:label "Order"}}
   :attributes {:demo.order/bill_to {:term "demo/customer_no" :term-context "购买方"}
                :demo.order/ship_to {:term "demo/customer_no" :term-context "收货方"}}})

(deftest match-layer-is-shared-verdicts-are-not-test
  (testing "折叠本身就是同一个函数——两侧对同一个词得到逐字节相同的短语"
    (doseq [q ["to_acct_no" "toAcctNo" "TO.ACCT.NO" "客户编号"]]
      (is (= (search/fold nil "cis.purchase.vpo" q)
             (search/fold nil "cis.purchase.vpo" q)))))

  (testing "唯一命中：匹配相同（同一个属性），裁决不同（:exact-unique vs :via-term）"
    (let [m @demo-model
          e "cis.master.customer"
          q "客户编号"
          i (search/build-index m 1)
          rd (search/search-attrs i q e 1)
          rw (rt/resolve-target (rt/index (attr-objects m e) (:terms m) nil) q
                                {:type :attribute})]
      (is (= #{:cis.master.customer/cust_no} (rt-matched m e q)))
      (is (= (rt-matched m e q) (rd-matched rd)) "**匹配相同**")
      (is (= :exact-unique (:verdict rw)))
      (is (= :via-term (:verdict rd)))
      (is (not= (:verdict rw) (:verdict rd)) "**裁决不同**")
      (search/close-index! i)))

  (testing "歧义：匹配相同（同两个属性），裁决不同（:exact-ambiguous vs :ambiguous）"
    (let [m ambiguous-model
          e "demo.order"
          q "客户编号"
          i (search/build-index m 1)
          rd (search/search-attrs i q e 1)
          rw (rt/resolve-target (rt/index (attr-objects m e) (:terms m) nil) q
                                {:type :attribute})]
      (is (= #{:demo.order/bill_to :demo.order/ship_to} (rt-matched m e q)))
      (is (= (rt-matched m e q) (rd-matched rd)) "**匹配相同**")
      (is (= :exact-ambiguous (:verdict rw)))
      (is (= :ambiguous (:verdict rd)))
      (search/close-index! i)))

  (testing "两套裁决词汇**互不相交**——不许有人把它们合并成一套"
    (is (empty? (clojure.set/intersection
                 (set tools/verdicts)
                 #{:direct :via-term :ambiguous})))
    (is (= #{:none} (clojure.set/intersection
                     (set tools/verdicts)
                     #{:direct :via-term :ambiguous :none}))
        ":none 是唯一同名的一支，且两边含义一致：如实说没有")))

;; ---------------------------------------------------------------------------
;; 8.4.17 裁定十：`graph` 省略时取 workspace 默认图
;; ---------------------------------------------------------------------------

(defn- graph-only-service
  "A graph service that does nothing but report which graph it was asked
   about. The point of these tests is which graph reaches the service, so the
   service is reduced to exactly that answer — a real projection would prove
   the same thing while also failing whenever the demo model changes."
  []
  (let [seen (atom nil)]
    [seen {:graph-members (fn [a] (reset! seen (:graph a)) {:members {} :graph (:graph a)})
           :project-graph (fn [a] (reset! seen (:graph a)) {:projected {}})}]))

(deftest default-graph-fills-an-omitted-graph-test
  (let [reg (tools/registry #{:read})
        [seen svc] (graph-only-service)]
    (testing "设了默认图：省略 `graph` 落在默认图上"
      (let [res (tools/call reg (ctx :graph-service svc :default-graph "demo/sales")
                            "inspect_graph" {})]
        (is (= "demo/sales" @seen) "服务收到的是默认图")
        (is (= "demo/sales" (:graph res)) "响应回显实际落在的图")))
    (testing "显式 `graph` 仍优先"
      (let [res (tools/call reg (ctx :graph-service svc :default-graph "demo/sales")
                            "inspect_graph" {:graph "demo/other"})]
        (is (= "demo/other" @seen))
        (is (= "demo/other" (:graph res)))))))

(deftest graph-required-when-neither-given-test
  (let [reg (tools/registry #{:read :write})
        [seen svc] (graph-only-service)]
    (testing "没设默认图且没传：拒绝，且**服务没被调到**（于是什么也没写）"
      (doseq [tool ["inspect_graph" "publish_graph"
                    "add_thing" "add_attribute" "add_relation" "add_link" "add_metric"
                    "update_resolver" "update_attribute" "remove_objects"
                    "define_concept" "unassign_term"
                    "change_graph_members"]]
        (let [res (tools/call reg (ctx :graph-service svc) tool {})]
          (is (= #{:tools/graph-required} (rejected! res)) tool)
          (is (= [:graph] (:path (first (:findings res)))) tool)
          (is (= "set a workspace default graph, or pass graph"
                 (:expected (first (:findings res)))) tool))))
    (is (nil? @seen) "拒绝发生在分派之前")
    (testing "`describe_objects` 的 `graph` 裁定十之前就是**有条件**的（只在登记词条时
              需要），它的缺席仍然合法——裁定十说的是省略时取什么，不是把一个
              条件参数变成必填"
      (is (empty? (:findings (tools/call reg (ctx :graph-service svc)
                                         "describe_objects" {:objects []})))))))

(deftest graph-default-does-not-touch-the-two-graphless-tools-test
  (let [reg (tools/registry #{:read :write})]
    (testing "`list_graphs` 与 `create_graph` 不带 `graph`，默认图与它们无关"
      (doseq [tool ["list_graphs" "create_graph"]]
        (is (not (contains? (rejected! (tools/call reg (ctx :default-graph "demo/sales")
                                                   tool {}))
                            :tools/graph-required))
            tool)))))
