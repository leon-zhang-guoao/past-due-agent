(ns open.atlas.studio.graph.graph-test
  "B5a.1 的守门测试：graph 登记与限定名、D3.14 授权。

   **地盘登记与越界写留痕已删**（D3.17 拆了地盘制度，D3.22 归属改为池对象上存下来
   的一列）：写入不可能越界，镜子里永远是空的。裁定 ① 由
   `governance-lives-in-the-pool-but-not-in-the-model` 守——它是 B4.1 退出条件 1
   的延长线：治理数据要跟着池走，但一个字节都不许进模型。"
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.pool :as pool]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

(def ^:dynamic *conn* nil)
(def ^:dynamic *cfg* nil)

(defn with-pool [f]
  (let [cfg (pool/config {:backend :mem :id (str (gensym "graph-pool"))})]
    (pool/delete-pool! cfg)
    (let [conn (graph/install! (pool/open-pool! cfg))]
      (binding [*conn* conn *cfg* cfg]
        (try (f) (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))))

(use-fixtures :each with-pool)

(defn- ok! [r]
  (is (empty? (:findings r)) (pr-str (:findings r)))
  r)

(defn- rejected! [r]
  (is (seq (:findings r)) "应当拒绝")
  (is (every? graph/finding? (:findings r)) "拒绝一律 finding-v1 且 code 已注册")
  (:findings r))

(defn- codes-of [fs] (set (map :code fs)))

(defn- sales! []
  (ok! (graph/register-workspace! *conn* {:name "sales"
                                          :members [{:principal {:issuer "test-idp" :subject "alice"} :role :owner}]})))
(defn- core-team! []
  (ok! (graph/register-workspace! *conn* {:name "core-team"
                                          :members [{:principal {:issuer "test-idp" :subject "bob"} :role :owner}]})))

;; ---------------------------------------------------------------------------
;; selector：scope projection v1 §1 的分段边界（B5a.2 共用这一份）
;; ---------------------------------------------------------------------------

(deftest selector-matches-on-segment-boundaries
  (testing "`sales.*` 匹配 `sales.order`，不匹配 `salesforce.order` —— 契约 §1 的原话"
    (is (graph/selector-matches? "sales.*" "sales.order"))
    (is (graph/selector-matches? "sales.*" "sales.order.line"))
    (is (not (graph/selector-matches? "sales.*" "salesforce.order")))
    (is (not (graph/selector-matches? "sales.*" "sales"))
        "前缀形不选中前缀自己——那是一个独立的 namespace，要它就精确写"))

  (testing "裸名只做精确匹配"
    (is (graph/selector-matches? "sales" "sales"))
    (is (not (graph/selector-matches? "sales" "sales.order"))))

  (testing "词表域同一套规则（契约 §7）"
    (is (graph/selector-matches? "common.audit/*" "common.audit/g_pii"))
    (is (not (graph/selector-matches? "common.audit/*" "common.auditing/g_pii"))))

  (testing "规范形只有两种：带 `*` 的必须是分段前缀形"
    (is (graph/selector-canonical? "sales"))
    (is (graph/selector-canonical? "sales.*"))
    (is (not (graph/selector-canonical? "sal*es")))
    (is (not (graph/selector-canonical? "sales.")))
    (is (not (graph/selector-canonical? ""))))

  (testing "重叠（查重用）与覆盖（菜谱用）不是同一个判定"
    (is (graph/selector-overlap? "sales.*" "sales.order"))
    (is (graph/selector-overlap? "sales.*" "sales.order.*"))
    (is (not (graph/selector-overlap? "sales.*" "salesforce.*")))
    (is (graph/selector-covers? "sales.*" "sales.order"))
    (is (not (graph/selector-covers? "sales.order" "sales.*"))
        "精确地盘覆盖不了一个更宽的 selector —— 用 overlap? 判这里就等于放行越界")))

(deftest workspace-roles-are-owner-or-member
  (testing "workspace membership has owner/member; graph editing is a separate grant"
    (is (= #{:owner :member :editor} graph/roles))
    (is (= #{:workspace.registry/malformed}
           (codes-of (rejected! (graph/register-workspace!
                                 *conn* {:name "sales"
                                         :members [{:principal {:issuer "test-idp" :subject "carol"} :role :reviewer}]})))))))

(deftest membership-can-be-changed-and-withdrawn
  (sales!)
  (let [alice {:issuer "test-idp" :subject "alice"}
        bob   {:issuer "test-idp" :subject "bob"}]
    (testing "re-inviting the same principal is a role update, not a second membership"
      (ok! (graph/add-workspace-member! *conn* "sales" {:principal bob :role :member}))
      (is (= :member (graph/workspace-role (pool/db *conn*) "sales" bob)))
      (ok! (graph/add-workspace-member! *conn* "sales" {:principal bob :role :editor}))
      (is (= :editor (graph/workspace-role (pool/db *conn*) "sales" bob)))
      (is (= 2 (count (:workspace/members
                       (first (filter #(= "sales" (:workspace/name %))
                                      (graph/workspaces (pool/db *conn*)))))))
          "一个人一条成员记录——改角色不该长出第二条"))

    (testing "removing a member withdraws access"
      (ok! (graph/remove-workspace-member! *conn* "sales" bob))
      (is (nil? (graph/workspace-role (pool/db *conn*) "sales" bob)))
      (is (not (graph/workspace-member? (pool/db *conn*) "sales" bob))))

    (testing "removing someone who is not a member is not an error —
              the caller's intent already holds"
      (ok! (graph/remove-workspace-member! *conn* "sales" bob)))

    (testing "**最后一个 owner 摘不掉**：没有 owner 的 workspace 从此无人可管,
              而平台没有修复路径——拒绝是唯一不悄悄毁掉东西的答案"
      (is (= #{:workspace.registry/last-owner}
             (codes-of (rejected! (graph/remove-workspace-member! *conn* "sales" alice)))))
      (is (= :owner (graph/workspace-role (pool/db *conn*) "sales" alice))
          "拒绝之后成员表一个字节都没变"))

    (testing "先立一个新 owner，原 owner 就摘得掉了"
      (ok! (graph/add-workspace-member! *conn* "sales" {:principal bob :role :owner}))
      (ok! (graph/remove-workspace-member! *conn* "sales" alice))
      (is (nil? (graph/workspace-role (pool/db *conn*) "sales" alice)))
      (is (= :owner (graph/workspace-role (pool/db *conn*) "sales" bob))))))

;; ---------------------------------------------------------------------------
;; graph 登记与限定名（D3.10）
;; ---------------------------------------------------------------------------

(defn- two-workspaces-with-land!
  "Two registered workspaces. The name is historical: there is no land any more
   (D3.17 / D3.22), only two workspaces that own what they write."
  []
  (sales!) (core-team!))

(deftest the-default-query-lives-on-the-graph
  ;; Owner ruling 2026-09-01. Before it, Query Studio's opening query was a
  ;; string literal in the UI -- every graph on every instance opened with the
  ;; same one, and by then it named namespaces no shipped demo had.
  (two-workspaces-with-land!)
  (ok! (graph/register-graph! *conn* {:workspace "sales" :name "core" :members {}}))

  (testing "a graph with no default carries no key at all -- not an empty one"
    (is (not (contains? (graph/graph (pool/db *conn*) "sales/core") :graph/default-query))))

  (let [dq {:query [:sales.customer/name {:sales.customer/orders [:sales.order/no]}]
            :entity {:sales.customer/id 1}}]
    (testing "query AND entity, because a default needing an entry value must carry one"
      (ok! (graph/set-default-query! *conn* "sales/core" dq))
      (is (= dq (:graph/default-query (graph/graph (pool/db *conn*) "sales/core")))))

    (testing "it survives a recipe write -- the two are different settings"
      (ok! (graph/update-recipe! *conn* "sales/core" {:members {}}))
      (is (= dq (:graph/default-query (graph/graph (pool/db *conn*) "sales/core")))))

    (testing "nil RETRACTS it; the key goes away rather than turning empty"
      (ok! (graph/set-default-query! *conn* "sales/core" nil))
      (is (not (contains? (graph/graph (pool/db *conn*) "sales/core") :graph/default-query)))))

  (testing "refusals are finding-v1, and an empty query is one of them"
    (is (= #{:graph.registry/default-query-invalid}
           (codes-of (rejected! (graph/set-default-query! *conn* "sales/core" {:query []})))))
    (is (= #{:graph.registry/default-query-invalid}
           (codes-of (rejected! (graph/set-default-query!
                                 *conn* "sales/core" {:query "not-a-vector"})))))
    (is (= #{:graph.registry/default-query-invalid}
           (codes-of (rejected! (graph/set-default-query!
                                 *conn* "sales/core"
                                 {:query [:a/b] :entity "not-a-map"}))))))

  (testing "an unregistered graph is refused by name"
    (is (= #{:graph.registry/unknown}
           (codes-of (rejected! (graph/set-default-query!
                                 *conn* "sales/nope" {:query [:a/b]})))))))

(deftest the-landing-graph-is-a-workspace-preference
  ;; D3.21. Landing was the first live graph in qualified-name order, so a
  ;; publisher shipping two demos could not say which one a new user sees.
  (two-workspaces-with-land!)
  (ok! (graph/register-graph! *conn* {:workspace "sales" :name "core" :members {}}))
  (ok! (graph/register-graph! *conn* {:workspace "core-team" :name "core" :members {}}))

  (testing "unset is nil, and the caller keeps its own ordering"
    (is (nil? (graph/default-graph (pool/db *conn*) "sales"))))

  (testing "set, then read back"
    (ok! (graph/set-default-graph! *conn* "sales" "sales/core"))
    (is (= "sales/core" (graph/default-graph (pool/db *conn*) "sales"))))

  (testing "a graph from another workspace is refused, not stored and ignored (D3.17)"
    (is (= #{:graph.registry/default-graph-foreign}
           (codes-of (rejected! (graph/set-default-graph! *conn* "sales" "core-team/core")))))
    (is (= "sales/core" (graph/default-graph (pool/db *conn*) "sales"))
        "the refused write left the previous value alone"))

  (testing "a graph that does not exist is refused by name"
    (is (= #{:graph.registry/unknown}
           (codes-of (rejected! (graph/set-default-graph! *conn* "sales" "sales/ghost"))))))

  (testing "an archived graph answers nil -- a preference, not a trap"
    (ok! (graph/archive-graph! *conn* "sales/core"))
    (is (nil? (graph/default-graph (pool/db *conn*) "sales")))
    (ok! (graph/restore-graph! *conn* "sales/core"))
    (is (= "sales/core" (graph/default-graph (pool/db *conn*) "sales"))))

  (testing "nil RETRACTS it"
    (ok! (graph/set-default-graph! *conn* "sales" nil))
    (is (nil? (graph/default-graph (pool/db *conn*) "sales")))))

(deftest graph-identity-is-the-qualified-name
  (two-workspaces-with-land!)
  (ok! (graph/register-graph! *conn* {:workspace "sales" :name "core"
                                      :members {}}))

  (testing "同一 workspace 内重名拒绝"
    (is (= #{:graph.registry/name-taken}
           (codes-of (rejected! (graph/register-graph!
                                 *conn* {:workspace "sales" :name "core"
                                         :members {}}))))))

  (testing "不同 workspace 的同一个裸名合法 —— 这正是 D3.10 选限定名的理由"
    (ok! (graph/register-graph! *conn* {:workspace "core-team" :name "core"
                                        :members {}}))
    (is (= ["core-team/core" "sales/core"] (mapv :graph/qualified-name (graph/graphs (pool/db *conn*)))))
    (is (= ["core-team" "core"] (graph/parse-qualified-name "core-team/core"))))

  (testing "裸名带 `/` 拒绝：限定名恰好两段"
    (is (= #{:graph.registry/malformed}
           (codes-of (rejected! (graph/register-graph!
                                 *conn* {:workspace "sales" :name "a/b"
                                         :members {}}))))))

  (testing "菜谱形状不对即拒（D3.19：清单是 类型 → 身份集合）"
    (is (= #{:graph.registry/malformed}
           (codes-of (rejected! (graph/register-graph!
                                 *conn* {:workspace "sales" :name "bad"
                                         :members {:namespaces [42]}})))))
    (is (= #{:graph.registry/malformed}
           (codes-of (rejected! (graph/register-graph!
                                 *conn* {:workspace "sales" :name "bad2"
                                         :members {:nope #{"x"}}})))))))

(deftest a-members-rejection-names-the-group-and-the-offending-identity
  "菜谱形状的拒绝要**能照着改**（finding-v1 §2：点名位置、给出期望）。

   它曾经只给 `:got (f/redact members)`，而 redact 把 map 降成 `map(N keys)`——
   键写错、组写错、成员写错，三种病拿回同一句话，调用方只能换形态硬试。"
  (two-workspaces-with-land!)
  (ok! (graph/register-graph! *conn* {:workspace "sales" :name "g" :members {}}))

  (testing "键集不对：说出九组的名字，并点名多出来的那个键"
    (let [[f] (rejected! (graph/update-recipe! *conn* "sales/g" {:members {:nope #{"x"}}}))]
      (is (= [:members] (:path f)))
      (is (= [":nope"] (:got f)))
      (is (contains? (set (:expected f)) ":resolvers"))))

  (testing "成员形状不对：path 下钻到那一组，got 是出错的那一个"
    (let [[f] (rejected! (graph/update-recipe!
                          *conn* "sales/g" {:members {:attributes ["cis.master.part/sku_no"]}}))]
      (is (= [:members :attributes] (:path f)))
      (is (= "\"cis.master.part/sku_no\"" (:got f)))
      (is (re-find #"qualified keyword" (:expected f)))))

  (testing "一次调用把每一处都说完 —— 不是修一个再拿回下一个"
    (let [fs (rejected! (graph/update-recipe!
                         *conn* "sales/g" {:members {:attributes [:a/b "c/d"]
                                                     :links      [[:a/b :c/d] ["a/b" "c/d"]]}}))]
      (is (= [[:members :attributes] [:members :links]] (mapv :path fs)))))

  (testing "组是集合语义：vector 与 list 都收下（EDN 过一趟 HTTP 常回 vector）"
    (ok! (graph/update-recipe! *conn* "sales/g" {:members {:namespaces ["sales.order"]}})))

  (testing "但 map 不收 —— 它带着一个不该有的键，收下就是猜作者的意思"
    (let [[f] (rejected! (graph/update-recipe!
                          *conn* "sales/g" {:members {:namespaces {"sales.order" true}}}))]
      (is (= [:members :namespaces] (:path f))))))

(deftest graph-uuid-and-archive-state-are-stable
  (two-workspaces-with-land!)
  (let [created (ok! (graph/register-graph! *conn*
                                             {:workspace "sales" :name "orders"
                                              :members {}}))
        graph   (:graph created)
        id      (:graph/id graph)]
    (testing "every graph has a server-wide immutable UUID in addition to its readable name"
      (is (uuid? (java.util.UUID/fromString id)))
      (is (= graph (graph/graph-by-id (pool/db *conn*) id))))

    (testing "archive is reversible state, never deletion"
      (let [archived (:graph (ok! (graph/archive-graph! *conn* "sales/orders")))]
        (is (= :archived (:graph/state archived)))
        (is (= id (:graph/id archived)))
        (is (= archived (graph/graph-by-id (pool/db *conn*) id))))
      (let [restored (:graph (ok! (graph/restore-graph! *conn* "sales/orders")))]
        (is (= :active (:graph/state restored)))
        (is (= id (:graph/id restored)))))))

(deftest workspace-members-see-all-team-graphs-but-editing-is-per-graph
  (two-workspaces-with-land!)
  (let [alice {:issuer "test-idp" :subject "alice"}
        bob   {:issuer "test-idp" :subject "bob"}
        orders (:graph (ok! (graph/register-graph! *conn*
                                                   {:workspace "sales" :name "orders"
                                                    :members {}})))
        pricing (:graph (ok! (graph/register-graph! *conn*
                                                    {:workspace "sales" :name "pricing"
                                                     :members {}})))]
    (testing "the accepted invitation makes the teammate a workspace member"
      (ok! (graph/add-workspace-member! *conn* "sales" {:principal bob :role :member}))
      (is (= :member (graph/workspace-role (pool/db *conn*) "sales" bob)))
      (is (graph/workspace-member? (pool/db *conn*) "sales" bob)))

    (testing "membership shows every graph in the team workspace, without a per-graph read ACL"
      (is (= #{"sales/orders" "sales/pricing"}
             (set (map :graph/qualified-name (graph/visible-graphs (pool/db *conn*) bob)))))
      (is (graph/graph-visible? (pool/db *conn*) "sales/orders" bob))
      (is (graph/graph-visible? (pool/db *conn*) "sales/pricing" bob)))

    (testing "only the workspace owner creates; membership alone does not edit"
      (is (graph/graph-creatable? (pool/db *conn*) "sales" alice))
      (is (not (graph/graph-creatable? (pool/db *conn*) "sales" bob)))
      (is (not (graph/graph-editable? (pool/db *conn*) "sales/orders" bob))))

    (testing "an editor grant is scoped to one graph and can be revoked"
      (ok! (graph/grant-graph-access! *conn* "sales/orders" {:principal bob :role :editor}))
      (is (= :editor (graph/graph-access-role (pool/db *conn*) "sales/orders" bob)))
      (is (graph/graph-editable? (pool/db *conn*) "sales/orders" bob))
      (is (not (graph/graph-editable? (pool/db *conn*) "sales/pricing" bob)))
      (ok! (graph/revoke-graph-access! *conn* "sales/orders" bob))
      (is (= :viewer (graph/graph-access-role (pool/db *conn*) "sales/orders" bob)))
      (is (not (graph/graph-editable? (pool/db *conn*) "sales/orders" bob))))

    (testing "a graph grant never smuggles an outsider into the workspace"
      (is (= #{:graph.access/not-workspace-member}
             (codes-of (rejected! (graph/grant-graph-access!
                                   *conn* (:graph/qualified-name orders)
                                   {:principal {:issuer "test-idp" :subject "eve"}
                                    :role :editor}))))))

    (testing "`:viewer` is not a grantable role (owner 2026-08-30) — it could not
              change a decision: a plain member already resolves to :viewer, so
              granting it was a control that read as policy and enforced nothing"
      (is (= #{:graph.access/malformed}
             (codes-of (rejected! (graph/grant-graph-access!
                                   *conn* "sales/orders"
                                   {:principal bob :role :viewer})))))
      (is (= #{:editor} graph/graph-access-roles))
      (testing "and a member with no grant still READS as viewer — the effective
                role is unchanged, only the grantable set narrowed"
        (is (= :viewer (graph/graph-access-role (pool/db *conn*) "sales/orders" bob)))))

    (testing "a grant that cannot change anything is refused rather than filed"
      (ok! (graph/add-workspace-member! *conn* "sales" {:principal bob :role :editor}))
      (let [fs (rejected! (graph/grant-graph-access!
                           *conn* "sales/orders" {:principal bob :role :editor}))]
        (is (= #{:graph.access/redundant} (codes-of fs)))
        (is (re-find #"already a workspace editor" (:message (first fs)))
            "names the role they hold, which is also the answer to «then how do I
             narrow them» — you cannot, from here"))
      (testing "and the owner is refused for the same reason"
        (is (= #{:graph.access/redundant}
               (codes-of (rejected! (graph/grant-graph-access!
                                     *conn* "sales/orders"
                                     {:principal alice :role :editor}))))))
      (testing "nothing was filed"
        (is (empty? (:graph/access (graph/graph (pool/db *conn*) "sales/orders"))))))))

;; ---------------------------------------------------------------------------
;; 授权（D3.14）
;; ---------------------------------------------------------------------------



;; ---------------------------------------------------------------------------
;; 越界写标记（D3.4，退出条件 2）
;; ---------------------------------------------------------------------------

(defn- create-attribute! [attr]
  (let [ns-name (namespace attr)]
    (when-not (pool/object (pool/db *conn*) :namespace ns-name)
      (ok! (pool/execute-action *conn* {:workspace "demo" :action :create :type :namespace :id ns-name
                                        :entry {:kind :master :label ns-name}
                                        :expected_revision 0})))
    (ok! (pool/execute-action *conn* {:workspace "demo" :action :create :type :attribute :id attr
                                      :entry {:type :string} :expected_revision 0}))))

;; ---------------------------------------------------------------------------
;; 裁定 ①：住在池里，不进模型，跟着导出走
;; ---------------------------------------------------------------------------

(deftest governance-lives-in-the-pool-but-not-in-the-model
  (two-workspaces-with-land!)
  (let [before (pool/export-pool (pool/db *conn*))]
    (ok! (graph/register-graph! *conn* {:workspace "core-team" :name "core"
                                        :members {}}))
    (create-attribute! :core.customer/id)

    (testing "治理数据不在 `as-dialect` 里（裁定 ①：不进模型）"
      (let [m (pool/as-dialect (pool/db *conn*))]
        (is (every? #(contains? #{:namespaces :attributes} %) (keys m))
            (pr-str (keys m)))
        (is (not (re-find #"workspace|grant"
                          (binding [*print-namespace-maps* false] (pr-str m)))))))

    (testing "治理数据不在池的对象清单里（不共用 :pool.object/key）"
      (is (every? #(contains? #{:attribute :namespace} (:pool/type %))
                  (pool/objects (pool/db *conn*))))
      (is (= 2 (count (pool/objects (pool/db *conn*))))))

    (testing "池的导出信封里一个 :graph/* 键都没有"
      ;; 断言的是**没有 graph 的键**，不是「恰好只有这两个键」：池自己的信封会
      ;; 长（8.4 P1b 加了 `:pool/mutations`，B4.4 之前加过 `:pool/confirmations`），
      ;; 而那些是池的东西，不是这条测试要守的边界。原来的集合相等把「池又多了
      ;; 一种自己的记录」也报成 graph 泄漏，那是**另一件事**——两件事共用一条
      ;; 断言，红的时候没人说得清红在哪一件。
      (is (every? #(= "pool" (namespace %))
                  (keys (edn/read-string (pool/export-pool (pool/db *conn*))))))
      (is (not (re-find #":graph" (pool/export-pool (pool/db *conn*))))))

    (testing "治理数据出现之前的池导出与之后**同样只由模型决定**"
      (is (not= before (pool/export-pool (pool/db *conn*)))
          "多了两个模型对象，导出当然变了")
      (is (= (pool/export-pool (pool/db *conn*)) (pool/export-pool (pool/db *conn*)))
          "两次导出逐字节相同"))

    (testing "治理数据随自己的信封走：删库、导回、逐字节相同且登记跟着走"
      (let [pool-edn  (edn/read-string (pool/export-pool (pool/db *conn*)))
            gov-edn   (graph/export-data (pool/db *conn*))
            gov-str   (graph/export-str (pool/db *conn*))
            cfg2      (pool/config {:backend :mem :id (str (gensym "rebuilt"))})
            _         (pool/delete-pool! cfg2)
            conn2     (graph/install! (pool/open-pool! cfg2))]
        (try
          (ok! (pool/import-pool! conn2 pool-edn))
          (ok! (graph/import! conn2 gov-edn))
          (is (= gov-str (graph/export-str (pool/db conn2))) "治理导出逐字节相同")
          (is (= (pool/export-pool (pool/db *conn*)) (pool/export-pool (pool/db conn2)))
              "模型导出逐字节相同（B4.1 退出条件 1 不许破）")
          (is (= 2 (count (graph/workspaces (pool/db conn2)))) "登记跟着治理信封回来了")
          (finally (pool/close-pool! conn2) (pool/delete-pool! cfg2)))))

    (testing "空治理的池导出是一个只有版本号的信封（缺省不写）"
      (let [cfg3  (pool/config {:backend :mem :id (str (gensym "empty"))})
            _     (pool/delete-pool! cfg3)
            conn3 (graph/install! (pool/open-pool! cfg3))]
        (try (is (= {:graph/version 2} (graph/export-data (pool/db conn3))))
             (finally (pool/close-pool! conn3) (pool/delete-pool! cfg3)))))))

(deftest every-rejection-is-finding-v1-and-leaks-nothing
  (sales!)
  (let [fs (concat (:findings (graph/register-workspace! *conn* {:name "sales" :members []}))
                   (:findings (graph/register-graph! *conn* {:workspace "sales" :name "g"
                                                             :members {}}))
                   (:findings (graph/delete-graph! *conn* "sales/none" (fn [_ _] []))))]
    (is (seq fs))
    (is (every? graph/finding? fs) "全部过 finding-v1 §2 且 code 已注册")
    (testing "不泄漏：:got 里没有成员名单、菜谱内容这类配置值（finding-v1 §5）"
      (is (every? #(not (map? (:got %))) fs)))
    (testing "顺序确定（finding-v1 §6）"
      (is (= (:findings (graph/delete-graph! *conn* "sales/none" (fn [_ _] [])))
             (:findings (graph/delete-graph! *conn* "sales/none" (fn [_ _] []))))))))

;; ---------------------------------------------------------------------------
;; 整备份：**少一节是静默的**，所以两节必须一起走
;; ---------------------------------------------------------------------------

(deftest a-half-restore-is-silent-so-the-bundle-refuses-one
  (two-workspaces-with-land!)
  (ok! (graph/register-graph! *conn* {:workspace "core-team" :name "core"
                                      :members {}}))
  (create-attribute! :core.customer/id)
  (let [bundle (graph/export-bundle (pool/db *conn*))
        fresh  (fn [] (let [cfg (pool/config {:backend :mem :id (str (gensym "half"))})]
                        (pool/delete-pool! cfg)
                        (graph/install! (pool/open-pool! cfg))))]

    (testing "先把病症钉住：只恢复池，治理数据**与全新空池逐字节相同**，
              一个 finding 都没有 —— 「缺失」与「从来没有过」在这里长得一样"
      (let [half  (fresh)
            blank (fresh)]
        (is (empty? (:findings (pool/import-pool! half (:bundle/pool bundle)))))
        (is (seq (pool/objects (pool/db half))) "池那一半确实恢复了")
        (is (= (graph/workspaces (pool/db blank)) (graph/workspaces (pool/db half)) []))
        (is (= (graph/graphs (pool/db blank)) (graph/graphs (pool/db half)) []))
        (pool/close-pool! half) (pool/close-pool! blank)))

    (testing "整备份两节都在 → 池与治理都回来了"
      (let [c (fresh)
            r (graph/import-bundle! c bundle)]
        (is (empty? (:findings r)))
        (is (seq (pool/objects (pool/db c))))
        (is (= 2 (count (graph/workspaces (pool/db c)))))
        (is (= ["core-team/core"] (mapv :graph/qualified-name (graph/graphs (pool/db c)))))
        (is (= bundle (graph/export-bundle (pool/db c))) "整备份往返逐字节相同")
        (pool/close-pool! c)))

    (testing "缺任何一节即拒绝 —— 拒绝一次，好过让半份备份跑上三个月"
      (doseq [k [:bundle/pool :bundle/graph]]
        (let [c (fresh)
              r (graph/import-bundle! c (dissoc bundle k))]
          (is (= [:graph.export/malformed] (mapv :code (:findings r))))
          (is (every? graph/finding? (:findings r)))
          (is (re-find (re-pattern (name k)) (pr-str (:findings r)))
              "拒绝要点名缺的是哪一节")
          (is (empty? (pool/objects (pool/db c))) "拒绝之后一个字节都没写")
          (pool/close-pool! c))))

    (testing "版本不认识也拒绝"
      (let [c (fresh)
            r (graph/import-bundle! c (assoc bundle :bundle/version 99))]
        (is (= [:graph.export/version-unsupported] (mapv :code (:findings r))))
        (pool/close-pool! c)))))

(deftest import-revalidates-member-shape
  (testing "「不重跑登记判定」说的是查重、授权、地盘覆盖——那些是对「新登记一条」
            说的话。**成员长什么样是内容契约**，不是当初那次登记合不合法：一份
            成员写错类型的信封导进来，池里就多一条永远匹配不上的授权记录，
            而它看起来完全正常（D5.10）"
    (let [bad {:graph/version 1
               :graph/workspaces [{:workspace/name "sales"
                             :workspace/members [{:member "alice" :role :owner}]}]}]
      (is (= [:graph.export/malformed]
             (mapv :code (:findings (graph/import! *conn* bad)))))))
  (testing "合法形状照常导得进"
    (let [good {:graph/version 1
                :graph/workspaces [{:workspace/name "sales"
                              :workspace/members
                              [{:principal {:issuer "test-idp" :subject "alice"}
                                :role :owner}]}]}]
      (is (empty? (:findings (graph/import! *conn* good)))))))

(deftest an-old-envelope-imports-and-says-what-it-dropped
  "D3.22：老备份必须还能导进来。`:graph/version 1` 带着三节已经退役的治理数据
   （地盘登记与两册镜子），它们**被忽略并在返回里逐节点名**——拒绝会让今天之前
   的每一份备份都恢复不了，静默丢掉会让人以为治理数据回来了。"
  (let [old {:graph/version 1
             :graph/workspaces [{:workspace/name "sales"
                                 :workspace/members
                                 [{:principal {:issuer "test-idp" :subject "alice"}
                                   :role :owner}]}]
             :graph/claims     [{:claim/selector "sales.*" :claim/workspace "sales"}]
             :graph/trespasses [{:trespass/object [:attribute :core.customer/id]
                                 :trespass/namespace "core.customer"
                                 :trespass/owner "core-team" :trespass/by "sales"
                                 :trespass/at "2026-08-24T10:00:00Z"}]}
        r   (graph/import! *conn* old)]
    (is (empty? (:findings r)) "不拒绝")
    (is (= ["sales"] (mapv :workspace/name (graph/workspaces (pool/db *conn*))))
        "还在的那几节照常导进来")
    (is (= [:graph/claims :graph/trespasses] (:ignored r))
        "丢掉了什么要说出来")))

(deftest delete-removes-the-recipe-and-nothing-else
  "只有 archived Graph 可以永久删除；共享的 legacy pool 对象仍保留。版本模块
   提供的删除事务片段与 Graph 登记在同一事务提交。"
  (two-workspaces-with-land!)
  (ok! (graph/register-graph! *conn* {:workspace "sales" :name "gone" :members {}}))
  (testing "active Graph 必须先 archive"
    (let [fs (rejected! (graph/delete-graph! *conn* "sales/gone" (fn [_ _] [])))]
      (is (= #{:graph.registry/malformed} (codes-of fs)))
      (is (re-find #"archive" (:message (first fs))))
      (is (some? (graph/graph (pool/db *conn*) "sales/gone")) "拒绝就没动过")))
  (ok! (graph/archive-graph! *conn* "sales/gone"))
  (testing "archived Graph 删得掉，并采用版本模块给出的事务片段"
    (let [called? (atom false)]
      (ok! (graph/delete-graph! *conn* "sales/gone"
                                (fn [_ qn]
                                  (reset! called? (= "sales/gone" qn))
                                  [])))
      (is @called?))
    (is (nil? (graph/graph (pool/db *conn*) "sales/gone")))
    (is (not (contains? (set (map :graph/qualified-name (graph/graphs (pool/db *conn*))))
                        "sales/gone"))))
  (testing "删一张不存在的图 → 结构化拒绝"
    (is (= #{:graph.registry/unknown}
           (codes-of (rejected! (graph/delete-graph! *conn* "sales/gone" (fn [_ _] []))))))))

;; ---------------------------------------------------------------------------
;; rename：门面的签名就是它收的东西（owner 裁定 2026-08-28）
;; ---------------------------------------------------------------------------

(deftest rename-takes-the-string-its-facade-documents
  "**这条测试是这个 bug 能活这么久的原因**：`rename-graph!` 一条覆盖都没有。

   门面写着 `[conn qn new-name guard]`，实现却 `{:keys [name]}` 解构——照文档传
   字符串，`name` 恒为 nil，改名必然被「名字不合法」拒掉。Builder 的改名按钮从
   写下那天起就是坏的，而唯一能用的调用方（`version/rename-graph!`）恰好包了一层
   map，于是这条缝两边各自自洽，谁也没发现。

   owner 裁定（2026-08-28）：门面的签名站住，实现跟着它改。"
  (sales!)
  (ok! (graph/register-graph! *conn* {:workspace "sales" :name "core"
                                      :members {} :created-at "2026-08-28T00:00:00Z"}))
  (testing "照文档传字符串就改得动"
    (let [r (ok! (graph/rename-graph! *conn* "sales/core" "revenue" (fn [_ _] nil)))]
      (is (= "sales/revenue" (:graph/qualified-name (:graph r))))
      (is (= "revenue" (:graph/name (:graph r)))))
    (is (some? (graph/graph (pool/db *conn*) "sales/revenue")))
    (is (nil? (graph/graph (pool/db *conn*) "sales/core"))
        "旧限定名不再解析——改名是换身份，不是加别名"))
  (testing "身份是 UUID，不是名字（D3.18）：改名前后是同一张图"
    (let [before (graph/register-graph! *conn* {:workspace "sales" :name "tmp"
                                                :members {} :created-at "2026-08-28T00:00:00Z"})
          id (:graph/id (:graph before))]
      (graph/rename-graph! *conn* "sales/tmp" "tmp2" (fn [_ _] nil))
      (is (= id (:graph/id (graph/graph (pool/db *conn*) "sales/tmp2"))))))
  (testing "guard 说了不行就不行 —— 切过版本的图改名会让制品里的名字解析不到"
    (is (= #{:version.rename/already-cut}
           (codes-of (:findings (graph/rename-graph!
                                 *conn* "sales/revenue" "again"
                                 (fn [_ _] [{:code :version.rename/already-cut
                                             :severity :error :path [:graph]
                                             :message "already cut"}])))))))
  (testing "撞名与非法名仍按名字拒绝，且 `:got` 里是那个名字本身"
    (ok! (graph/register-graph! *conn* {:workspace "sales" :name "other"
                                        :members {} :created-at "2026-08-28T00:00:00Z"}))
    (is (= #{:graph.registry/name-taken}
           (codes-of (:findings (graph/rename-graph! *conn* "sales/revenue" "other"
                                                     (fn [_ _] nil))))))
    (is (= #{:graph.registry/malformed}
           (codes-of (:findings (graph/rename-graph! *conn* "sales/revenue" "a/b"
                                                     (fn [_ _] nil))))))
    (is (= #{:graph.registry/malformed}
           (codes-of (:findings (graph/rename-graph! *conn* "sales/revenue" nil
                                                     (fn [_ _] nil))))))))


(deftest display-name-keeps-the-registered-identity
  (sales!)
  (let [before (:graph (ok! (graph/register-graph! *conn* {:workspace "sales" :name "core"
                                                         :members {} :created-at "2026-09-25T00:00:00Z"})))
        renamed (:graph (ok! (graph/set-display-name! *conn* "sales/core" "  Revenue graph  ")))]
    (is (= "Revenue graph" (:graph/display-name renamed)))
    (is (= before (dissoc renamed :graph/display-name)))
    (is (= renamed (graph/graph (pool/db *conn*) "sales/core")))
    (is (nil? (graph/graph (pool/db *conn*) "sales/Revenue graph")))
    (doseq [bad [nil "" "   " (apply str (repeat 129 "x")) "bad\nname"]]
      (is (seq (:findings (graph/set-display-name! *conn* "sales/core" bad)))))
    (ok! (graph/register-graph! *conn* {:workspace "sales" :name "other" :members {}
                                        :created-at "2026-09-25T00:00:00Z"}))
    (is (= #{:graph.registry/name-taken}
           (codes-of (:findings (graph/set-display-name! *conn* "sales/other" "Revenue graph")))))
    (is (= "Revenue graph" (:graph/display-name (graph/graph (pool/db *conn*) "sales/core"))))))

(deftest display-name-uniqueness-on-all-name-writers
  (sales!)
  (doseq [n ["first" "second"]]
    (ok! (graph/register-graph! *conn* {:workspace "sales" :name n :members {}})))
  (ok! (graph/set-display-name! *conn* "sales/first" "reserved"))
  (is (= #{:graph.registry/name-taken}
         (codes-of (:findings (graph/register-graph! *conn* {:workspace "sales" :name "reserved" :members {}})))))
  (is (= #{:graph.registry/name-taken}
         (codes-of (:findings (graph/rename-graph! *conn* "sales/second" "reserved" (fn [_ _] nil))))))
  (is (some? (graph/graph (pool/db *conn*) "sales/second")))
  (is (nil? (graph/graph (pool/db *conn*) "sales/reserved")))
  ;; A custom label remains the visible name when only coordinates change.
  (ok! (graph/set-display-name! *conn* "sales/second" "different"))
  (ok! (graph/rename-graph! *conn* "sales/second" "reserved" (fn [_ _] nil)))
  (is (= "different" (:graph/display-name (graph/graph (pool/db *conn*) "sales/reserved")))))

(deftest saved-queries-draft-and-pinned
  (two-workspaces-with-land!)
  (ok! (graph/register-graph! *conn* {:workspace "sales" :name "core" :members {}}))
  (let [sq {:name "by_id" :description "One customer."
            :params [{:name "id" :type :integer :required? true}]
            :eql '{:entity {:sales.customer/id ?id} :query [:sales.customer/name]}}
        db #(pool/db *conn*)]
    (testing "a graph made before saved queries existed has none"
      (is (= [] (graph/saved-queries (db) "sales/core"))))
    (testing "the draft round-trips, symbols and lists included"
      (graph/set-saved-queries! *conn* "sales/core" [sq])
      (is (= [sq] (graph/saved-queries (db) "sales/core"))))
    (testing "it survives a recipe write"
      (ok! (graph/update-recipe! *conn* "sales/core" {:members {}}))
      (is (= [sq] (graph/saved-queries (db) "sales/core"))))
    (testing "empty clears it back to absent"
      (graph/set-saved-queries! *conn* "sales/core" [])
      (is (= [] (graph/saved-queries (db) "sales/core"))))
    (testing "a pinned set is per version and write-once"
      (is (nil? (graph/pinned-saved-queries (db) "uuid-1" 1)))
      (graph/pin-saved-queries! *conn* "uuid-1" 2 [sq])
      (graph/pin-saved-queries! *conn* "uuid-1" 2 [])
      (is (= [sq] (graph/pinned-saved-queries (db) "uuid-1" 2)))
      (is (nil? (graph/pinned-saved-queries (db) "uuid-1" 1))))))

(deftest pinned-requests-expire-and-are-capped
  (let [db #(pool/db *conn*)
        pin (fn [id at exp] (graph/pin-request! *conn* {:id id :workspace "sales" :graph-id "g1"
                                                        :entry {:request [:a/b]} :pinned-at at
                                                        :pinned-by "me" :expires-at exp}))]
    (is (:pinned (pin "r1" "2026-10-10T00:00:00Z" "2026-10-17T00:00:00Z")))
    (is (:pinned (pin "r1" "2026-10-10T00:00:00Z" "2026-10-17T00:00:00Z")) "idempotent")
    (is (:pinned (pin "r2" "2026-10-11T00:00:00Z" "2026-10-12T00:00:00Z")))
    (is (= ["r2" "r1"] (map :id (graph/pinned-requests (db) "g1" "2026-10-11T12:00:00Z"))))
    (is (= ["r1"] (map :id (graph/pinned-requests (db) "g1" "2026-10-13T00:00:00Z"))) "expired is gone")
    (is (= {:request [:a/b]} (:entry (first (graph/pinned-requests (db) "g1" "2026-10-13T00:00:00Z")))))
    (is (= 1 (graph/purge-expired-pins! *conn* "2026-10-13T00:00:00Z")))
    (graph/unpin-request! *conn* "r1")
    (is (= [] (graph/pinned-requests (db) "g1" "2026-10-13T00:00:00Z")))
    (with-redefs [open.atlas.studio.graph.impl.saved-queries/max-pinned-per-workspace 1]
      (is (:pinned (pin "r3" "2026-10-13T00:00:00Z" "2026-10-20T00:00:00Z")))
      (is (= :recent-query/pin-limit (-> (pin "r4" "2026-10-13T00:00:00Z" "2026-10-20T00:00:00Z")
                                         :findings first :code))))))
