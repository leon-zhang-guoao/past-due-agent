(ns open.atlas.common.toolkit.toolkit-test
  "toolkit 的守门测试。本模块要证的只有一件事：**声明与分派同源**。

   于是核心断言是一条恒等式，且要在**每一种能力组合**下都成立——
   `tools/list` 列出的名字集合 ≡ dispatch 认识的名字集合。漂移在客户端调到一个
   不存在的工具之前是看不见的（principles），所以它必须在这里被看见。

   这里的声明是玩具，**这是对的**：被测的是机制，与工具做什么无关。真模型上的
   证据在 studio/tools（`query_model` 找不找得出缺口那种事，玩具证不出来）。"
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.toolkit :as toolkit]))

(def ^:private decls
  [{:name "read_thing" :description "A read tool." :capability :read
    :params [{:name "q" :type :string :required? true :description "The query."}
             {:name "mode" :type :keyword :required? false :description "Mode."
              :enum [:fast :slow]}]
    :handler (fn [ctx args] {:ok :read :ctx ctx :args args})}
   {:name "write_thing" :description "A write tool." :capability :write
    :params [{:name "n" :type :integer :required? true :description "How many."}]
    :handler (fn [_ args] {:ok :write :args args})}])

(def ^:private all-caps-combos
  "两把钥匙的全部四种组合（principles：写钥匙独立签发，绝不从读钥匙派生，
   所以 `#{:write}` 是一种真实存在的挂载，不是不可能的情况）。"
  [#{} #{:read} #{:write} #{:read :write}])

;; ---------------------------------------------------------------------------
;; 核心：同源
;; ---------------------------------------------------------------------------

(deftest list-and-dispatch-share-one-filter-test
  (testing "每一种能力组合下，tools/list 的名字集合 ≡ dispatch 认识的名字集合"
    (doseq [caps all-caps-combos]
      (let [reg    (toolkit/registry decls caps)
            listed (set (map #(get % "name") (toolkit/tools-list reg)))]
        (is (= listed (toolkit/tool-names reg))
            (str "能力 " (pr-str caps) " 下声明与分派漂移了"))
        ;; 恒等式两侧还要与「按能力算出来的期望」一致，否则两边一起错也过得去。
        (is (= listed (set (map :name (filter #(contains? caps (:capability %)) decls))))
            (pr-str caps))))))

(deftest every-listed-name-dispatches-test
  (testing "list 里的每个名字都真的调得动（不是「列出来了但调不了」）"
    (doseq [caps all-caps-combos
            :let [reg (toolkit/registry decls caps)]
            n   (toolkit/tool-names reg)]
      (let [args (case n "read_thing" {:q "x"} "write_thing" {:n 1})
            res  (toolkit/dispatch reg {:ctx true} n args)]
        (is (nil? (:findings res)) (str n " 在 " (pr-str caps) " 下列出了却调不动"))
        (is (contains? res :ok))))))

;; ---------------------------------------------------------------------------
;; 裁定 ②：不挂载 ≠ 挂载了永远拒绝
;; ---------------------------------------------------------------------------

(deftest write-tool-absent-without-write-key-test
  (let [reg (toolkit/registry decls #{:read})]
    (testing "写工具在 tools/list 里缺席"
      (is (not (contains? (set (map #(get % "name") (toolkit/tools-list reg))) "write_thing"))))
    (testing "dispatch 报的是「不认识这个工具」，不是「拒绝」"
      (let [res (toolkit/dispatch reg {} "write_thing" {:n 1})
            f   (first (:findings res))]
        (is (= :toolkit.tool/unknown (:code f)))
        (is (every? toolkit/finding? (:findings res)))))
    (testing "被过滤掉的工具与压根不存在的名字**逐字不可区分**——否则 tools/list
              的缺席就白做了，攻击者照样数得出门后有什么"
      (let [a (toolkit/dispatch reg {} "write_thing" {})
            b (toolkit/dispatch reg {} "no_such_tool_at_all" {})]
        (is (= (:findings a) (:findings b)) "两种拒绝必须逐字节相同")))
    (testing "handler 一次都没跑"
      (is (nil? (:ok (toolkit/dispatch reg {} "write_thing" {:n 1})))))))

(deftest empty-capability-set-mounts-nothing-test
  (let [reg (toolkit/registry decls #{})]
    (is (empty? (toolkit/tools-list reg)))
    (is (empty? (toolkit/tool-names reg)))
    (is (= :toolkit.tool/unknown (:code (first (:findings (toolkit/dispatch reg {} "read_thing" {:q "x"}))))))))

;; ---------------------------------------------------------------------------
;; 入参校验：结构化拒绝，不含配置值
;; ---------------------------------------------------------------------------

(def ^:private config-values
  "拒绝里出现任何一个都是泄漏（finding-v1 §5）。工具入参里会坐着 source 条目。"
  ["jdbc:sqlite" "cis-demo.db" "s3cr3t"])

(defn- rejected! [res]
  (let [fs (:findings res)]
    (is (seq fs) "拒绝必须有 finding，不许静默失败")
    (is (every? toolkit/finding? fs))
    (is (some #(= :error (:severity %)) fs))
    (let [s (pr-str fs)]
      (doseq [v config-values]
        (is (not (str/includes? s v)) (str "finding 泄漏了 " (pr-str v) "：" s))))
    (set (map :code fs))))

(deftest argument-schema-test
  (let [reg (toolkit/registry decls #{:read :write})
        d   #(toolkit/dispatch reg {} "read_thing" %)]
    (testing "入参不是 map"
      (is (contains? (rejected! (d "not-a-map")) :toolkit.args/malformed)))
    (testing "键面封闭：未知键拒绝而非忽略"
      (is (contains? (rejected! (d {:q "x" :jdbc_url "jdbc:sqlite:data/cis-demo.db"}))
                     :toolkit.args/malformed)))
    (testing "必填缺席"
      (is (contains? (rejected! (d {:mode :fast})) :toolkit.args/missing)))
    (testing "类型不符，且值不回显"
      (is (contains? (rejected! (d {:q "jdbc:sqlite:data/cis-demo.db?password=s3cr3t"
                                    :mode "fast"}))
                     :toolkit.args/invalid)))
    (testing "枚举不符"
      (is (contains? (rejected! (d {:q "x" :mode :sideways})) :toolkit.args/invalid)))
    (testing "合法入参放行"
      (is (= {:ok :read} (select-keys (d {:q "x" :mode :fast}) [:ok]))))))

(deftest unknown-tool-does-not-leak-test
  (let [reg (toolkit/registry decls #{:read})]
    (is (contains? (rejected! (toolkit/dispatch reg {} "jdbc:sqlite:data/cis-demo.db" {}))
                   :toolkit.tool/unknown))))

;; ---------------------------------------------------------------------------
;; tools/list 的形状
;; ---------------------------------------------------------------------------

(deftest tools-list-shape-test
  (let [reg (toolkit/registry decls #{:read :write})
        l   (toolkit/tools-list reg)]
    (testing "MCP 形状，且 JSON-able（键是字符串，值里没有 keyword）"
      (is (= ["read_thing" "write_thing"] (mapv #(get % "name") l)))
      (is (every? #(and (string? (get % "description")) (map? (get % "inputSchema"))) l))
      (let [schema (get (first l) "inputSchema")]
        (is (= "object" (get schema "type")))
        (is (= ["q"] (get schema "required")))
        (is (false? (get schema "additionalProperties")))
        (is (= "string" (get-in schema ["properties" "q" "type"])))
        (is (= ["fast" "slow"] (get-in schema ["properties" "mode" "enum"])))))
    (testing "顺序即声明顺序，两次调用逐字节相同（客户端 diff 得动）"
      (is (= (pr-str l) (pr-str (toolkit/tools-list (toolkit/registry decls #{:read :write}))))))))

;; ---------------------------------------------------------------------------
;; 声明本身的错是实现 bug，构造时炸掉
;; ---------------------------------------------------------------------------

(deftest malformed-declaration-throws-test
  (testing "重名"
    (is (thrown? AssertionError (toolkit/registry (conj decls (first decls)) #{:read}))))
  (testing "能力不认识"
    (is (thrown? AssertionError
                 (toolkit/registry [(assoc (first decls) :capability :admin)] #{:read}))))
  (testing "声明键面封闭"
    (is (thrown? AssertionError
                 (toolkit/registry [(assoc (first decls) :extra 1)] #{:read}))))
  (testing "参数缺描述（tools/list 是给客户端看的，没描述等于没挂）"
    (is (thrown? AssertionError
                 (toolkit/registry [(assoc (first decls) :params [{:name "q" :type :string}])]
                                   #{:read})))))

(deftest finding-codes-registered-test
  (testing "全部产出的 code 都在注册表里（finding-v1 §6）"
    (is (set/subset? #{:toolkit.tool/unknown :toolkit.args/malformed
                       :toolkit.args/missing :toolkit.args/invalid}
                     toolkit/finding-codes))))
