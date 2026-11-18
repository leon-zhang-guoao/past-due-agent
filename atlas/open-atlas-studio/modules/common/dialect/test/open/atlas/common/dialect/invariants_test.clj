(ns open.atlas.common.dialect.invariants-test
  "跨条目不变量层测试（TODO(B0-5)）。
   conformance 矩阵 dialect-v1 §7 中属于不变量的每一行至少一个用例，正例同测。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            #_{:clj-kondo/ignore [:discouraged-namespace]}
            [open.atlas.common.dialect.impl.invariants :as inv]
            #_{:clj-kondo/ignore [:discouraged-namespace]}
            [open.atlas.common.dialect :as dialect]))

;; ---------------------------------------------------------------------------
;; 测试辅助
;; ---------------------------------------------------------------------------

(defn- codes [findings] (set (map :code findings)))

(defn- errors [findings] (filter #(= :error (:severity %)) findings))

(defn- has?
  "findings 中存在该 code（可选：该 path）。"
  ([findings code] (contains? (codes findings) code))
  ([findings code path]
   (boolean (some #(and (= code (:code %)) (= path (:path %))) findings))))

(def base-attrs
  {:cis.master.part/sku_no    {:type :integer :identity? true :format :id}
   :cis.master.part/part_no   {:type :string}
   :cis.master.part/prod_code {:type :string :format :code
                               :values {"A" {:label "A"} "B" {:label "B"}}}})

(defn- resolver
  "合法形状的 list resolver 骨架，测试按需覆盖键。"
  [& {:as over}]
  (merge {:id "demo_all_parts"
          :input []
          :output [:cis.master.part/part_no]
          :result_type "list"
          :limit {:default 50 :max 500}
          :source {:type "sql" :query "SELECT part_no FROM parts"}}
         over))

(defn- model [& {:as over}]
  (merge {:attributes base-attrs} over))

(deftest findings-are-well-formed
  (testing "产出的每个 finding 都过 finding-v1 §2 封闭 schema，且顺序确定"
    (let [data (model :resolvers [(resolver :result_type nil)]
                      :attributes (assoc base-attrs :x/bad {:type :decimal
                                                            :values {1 {:label "x"}}}))
          fs   (inv/check data)]
      (is (every? dialect/finding? fs))
      (is (= fs (inv/check data))))))

;; --- D2.19 ------------------------------------------------------------------

(deftest d2-19-result-type-returns
  (testing "缺 :result_type 拒绝"
    (is (has? (inv/check (model :resolvers [(dissoc (resolver) :result_type)]))
              :dialect.invariant/result-type-returns-mismatch
              [:resolvers 0 :result_type])))
  (testing "\"none\" 带 :returns 拒绝"
    (is (has? (inv/check (model :mutations [{:id "m" :result_type "none"
                                             :returns [:cis.master.part/part_no]
                                             :source {:type "sql" :query "q"}}]))
              :dialect.invariant/result-type-returns-mismatch
              [:mutations 0 :returns])))
  (testing "\"list\" 空 :returns 拒绝"
    (is (has? (inv/check (model :mutations [{:id "m" :result_type "list" :returns []
                                             :source {:type "sql" :query "q"}}]))
              :dialect.invariant/result-type-returns-mismatch)))
  (testing "正例：\"none\" 无 :returns、\"single\" 有非空 :returns"
    (is (empty? (errors (inv/check
                         (model :mutations
                                [{:id "m1" :result_type "none" :source {:type "sql" :query "q"}}
                                 {:id "m2" :result_type "single"
                                  :returns [:cis.master.part/part_no]
                                  :source {:type "sql" :query "q"}}])))))))

;; --- D2.16 ------------------------------------------------------------------

(def join-model
  "D2.29：分支键是**属性**，登记处只有 `:attributes` 一个。
   `:namespaces` 必须登记 join 的 `:target`（owner 2026-09-02 补回的门）。"
  (model :attributes (assoc base-attrs
                            :cis.master.part/all_parts {:target "cis.master.part"})
         :namespaces {"cis.master.part" {:kind :master}}))

(deftest d2-16-output-branches
  (testing "未登记的分支键拒绝（分支键现在也是属性引用）"
    (is (has? (inv/check (model :resolvers
                                [(resolver :output [{:cis.master.part/nope
                                                     [:cis.master.part/part_no]}])]))
              :dialect.invariant/unregistered-reference)))
  (testing ":target 与子属性 namespace 不一致拒绝"
    (is (has? (-> join-model
                  (assoc-in [:attributes :other.ns/x] {:type :string})
                  (assoc :resolvers [(resolver :output [{:cis.master.part/all_parts
                                                         [:other.ns/x]}])])
                  inv/check)
              :dialect.invariant/join-target-mismatch)))
  (testing "标量属性写在分支位置拒绝"
    (is (has? (inv/check (assoc join-model :resolvers
                                [(resolver :output [{:cis.master.part/part_no
                                                     [:cis.master.part/sku_no]}])]))
              :dialect.invariant/join-attribute-invalid)))
  (testing "join 属性写在叶位置拒绝（判别式的另一半）"
    (is (has? (inv/check (assoc join-model :resolvers
                                [(resolver :output [:cis.master.part/all_parts])]))
              :dialect.invariant/join-attribute-invalid)))
  (testing "正例：登记且 target 一致的嵌套分支"
    (is (empty? (errors (inv/check
                         (assoc join-model :resolvers
                                [(resolver :output [{:cis.master.part/all_parts
                                                     [:cis.master.part/part_no]}])])))))))

;; --- D2.29 join :target 先注册后使用 -----------------------------------------

(deftest d2-29-join-target-registered
  (testing "join :target 指向未登记 namespace 拒绝——即使这条 join 无人导航"
    (let [fs (inv/check (model :attributes
                               (assoc base-attrs
                                      :cis.master.part/all_parts {:target "no.such.ns"})
                               :namespaces {"cis.master.part" {:kind :master}}))]
      (is (has? fs :dialect.invariant/join-target-unregistered
                [:attributes :cis.master.part/all_parts :target]))
      (is (= #{:dialect.invariant/join-target-unregistered} (codes fs)))))
  (testing "正例：:target 已登记则零 finding"
    (is (empty? (inv/check join-model))))
  (testing "demo 模型（真实装料）在这条门下读干净"
    (let [m (edn/read-string (slurp (io/resource "model.edn")))]
      (is (empty? (filter #(= :dialect.invariant/join-target-unregistered (:code %))
                          (inv/check m)))))))

;; --- D2.18 ------------------------------------------------------------------

(deftest d2-18-mutation-params
  (testing "mutation 参数未登记拒绝"
    (is (has? (inv/check (model :mutations [{:id "m" :result_type "none"
                                             :params {:cis.master.part/ghost {}}
                                             :source {:type "sql" :query "q"}}]))
              :dialect.invariant/unregistered-reference
              [:mutations 0 :params :cis.master.part/ghost])))
  (testing "正例：参数已登记"
    (is (empty? (errors (inv/check
                         (model :mutations [{:id "m" :result_type "none"
                                             :params {:cis.master.part/part_no {}}
                                             :source {:type "sql" :query "q"}}])))))))

;; --- D2.20 ------------------------------------------------------------------

(deftest d2-20-eql-params
  (testing ":attribute 无法经映射解析拒绝（属性已登记但不在本 resolver 闭包内）"
    (is (has? (inv/check (model :resolvers
                                [(resolver :eql-params
                                           {:prod_code {:attribute :cis.master.part/prod_code
                                                        :op :eq}})]))
              :dialect.invariant/eql-param-unresolvable
              [:resolvers 0 :eql-params :prod_code :attribute])))
  (testing ":allowed ⊄ :values 键集拒绝"
    (is (has? (inv/check (model :resolvers
                                [(resolver :output [:cis.master.part/prod_code]
                                           :eql-params
                                           {:prod_code {:attribute :cis.master.part/prod_code
                                                        :op :eq
                                                        :allowed ["A" "Z"]}})]))
              :dialect.invariant/eql-param-unresolvable
              [:resolvers 0 :eql-params :prod_code :allowed])))
  (testing "a :type-anchored parameter's :allowed is checked against its type, not an empty :values (2.0 §3.1 params)"
    (let [typed (fn [decl] (inv/check (model :resolvers [(resolver :eql-params {:scope decl})])))]
      (is (not (has? (typed {:type :string :allowed ["standalone" "cluster"] :default "standalone"})
                     :dialect.invariant/eql-param-unresolvable)))
      (is (not (has? (typed {:type :date :allowed ["2026-09-18"]}) :dialect.invariant/eql-param-unresolvable))
          "a date is an ISO-8601 string")
      (is (has? (typed {:type :string :allowed ["ok" 2]})
                :dialect.invariant/eql-param-unresolvable [:resolvers 0 :eql-params :scope :allowed]))
      (is (has? (typed {:type :integer :allowed ["1"]})
                :dialect.invariant/eql-param-unresolvable [:resolvers 0 :eql-params :scope :allowed]))))
  (testing ":attribute 与 :type 同写拒绝；两者皆无也拒绝"
    (is (has? (inv/check (model :resolvers
                                [(resolver :output [:cis.master.part/prod_code]
                                           :eql-params
                                           {:prod_code {:attribute :cis.master.part/prod_code
                                                        :type :string :op :eq}})]))
              :dialect.invariant/eql-param-anchor-invalid))
    (is (has? (inv/check (model :resolvers [(resolver :eql-params {:q {:op :like}})]))
              :dialect.invariant/eql-param-anchor-invalid)))
  (testing "保留调用名被声明为参数拒绝"
    (is (has? (inv/check (model :resolvers
                                [(resolver :eql-params {:limit {:type :integer}})]))
              :dialect.invariant/reserved-param-name)))
  (testing ":op × 类型矩阵：:like 仅 :string"
    (is (has? (inv/check (model :resolvers
                                [(resolver :output [:cis.master.part/sku_no]
                                           :eql-params
                                           {:sku {:attribute :cis.master.part/sku_no
                                                  :op :like}})]))
              :dialect.invariant/type-compat-violation)))
  (testing "未登记的 :attribute 拒绝（D2.2 的登记门也管 eql-param）"
    (let [fs (inv/check (model :resolvers
                               [(resolver :output [:cis.master.part/prod_code]
                                          :eql-params
                                          {:prod_code {:attribute :typo.ns/nope
                                                       :op :eq}})]))]
      ;; 曾经这里一条 finding 都没有：下游 eql-param 判定都以「属性有登记」
      ;; 为前提，写错 namespace 的参数静默通过。
      (is (has? fs :dialect.invariant/unregistered-reference
                [:resolvers 0 :eql-params :prod_code :attribute]))))
  (testing "正例：锚在 output 属性上、:allowed ⊆ :values、无 :type"
    (is (empty? (errors (inv/check
                         (model :resolvers
                                [(resolver :output [:cis.master.part/prod_code]
                                           :eql-params
                                           {:prod_code {:attribute :cis.master.part/prod_code
                                                        :op :eq
                                                        :allowed ["A"]}
                                            :q {:type :string :op :like}})])))))))

;; --- D2.22 ------------------------------------------------------------------

(deftest d2-22-replaced-by
  (testing "目标缺失拒绝"
    (is (has? (inv/check (model :attributes
                                (assoc-in base-attrs [:cis.master.part/part_no :replaced-by]
                                          :cis.master.part/ghost)))
              :dialect.invariant/replaced-by-invalid)))
  (testing ":type 不同拒绝"
    (is (has? (inv/check (model :attributes
                                (assoc-in base-attrs [:cis.master.part/part_no :replaced-by]
                                          :cis.master.part/sku_no)))
              :dialect.invariant/replaced-by-invalid
              [:attributes :cis.master.part/part_no :replaced-by])))
  (testing "替换环拒绝"
    (is (has? (inv/check (model :attributes
                                {:a/x {:type :string :replaced-by :a/y}
                                 :a/y {:type :string :replaced-by :a/x}}))
              :dialect.invariant/replacement-cycle)))
  (testing "词条替换环拒绝（D2.26 推广）"
    (is (has? (inv/check (model :terms {"d/a" {:label "a" :replaced-by "d/b"}
                                        "d/b" {:label "b" :replaced-by "d/a"}}))
              :dialect.invariant/replacement-cycle
              [:terms "d/a" :replaced-by])))
  (testing "正例：目标存在、同 :type、无环"
    (is (empty? (errors (inv/check
                         (model :attributes {:a/x {:type :string :replaced-by :a/y}
                                             :a/y {:type :string}}
                                :terms {"d/a" {:label "a" :replaced-by "d/b"}
                                        "d/b" {:label "b"}})))))))

;; --- D2.26 ------------------------------------------------------------------

(def term-model
  (model :terms {"common.audit/created_by" {:label "录入人" :format :id}}
         :attributes {:cis.master.part/crt_usr {:type :string
                                                :term "common.audit/created_by"}}))

(deftest d2-26-terms
  (testing ":term 指向未注册词条拒绝"
    (is (has? (inv/check (assoc term-model :terms {}))
              :dialect.invariant/unregistered-reference
              [:attributes :cis.master.part/crt_usr :term])))
  (testing ":term 与词条携带字段同写拒绝"
    (doseq [k [:description :format :label :synonyms :values]]
      (is (has? (inv/check (assoc-in term-model [:attributes :cis.master.part/crt_usr k]
                                     (case k
                                       :synonyms ["x"]
                                       :values {"A" {:label "a"}}
                                       :format :id
                                       "x")))
                :dialect.invariant/term-violation
                [:attributes :cis.master.part/crt_usr k]))))
  (testing "正例：只绑定 :term + 本地物理治理字段"
    (is (empty? (errors (inv/check term-model))))))

;; --- D2.27 ------------------------------------------------------------------

(deftest d2-27-term-context-and-type-compat
  (testing ":term-context 无 :term 拒绝"
    (is (has? (inv/check (model :attributes
                                {:a/x {:type :string :term-context "买方"}}))
              :dialect.invariant/term-violation
              [:attributes :a/x :term-context])))
  (testing "同 namespace 同词条多绑定缺互异 :term-context 拒绝"
    (is (has? (inv/check (model :terms {"common.party/customer_id" {:label "客户编号"}}
                                :attributes
                                {:a/cust     {:type :string :term "common.party/customer_id"}
                                 :a/ship_cust {:type :string :term "common.party/customer_id"}}))
              :dialect.invariant/term-violation)))
  (testing "正例：多绑定带互异 :term-context"
    (is (empty? (errors (inv/check
                         (model :terms {"common.party/customer_id" {:label "客户编号"}}
                                :attributes
                                {:a/cust      {:type :string :term "common.party/customer_id"
                                               :term-context "购买方客户"}
                                 :a/ship_cust {:type :string :term "common.party/customer_id"
                                               :term-context "收货方客户"}}))))))
  (testing "码值键 EDN 类型与 :type 不一致拒绝"
    (is (has? (inv/check (model :attributes
                                {:a/x {:type :integer :values {"1" {:label "一"}}}}))
              :dialect.invariant/type-compat-violation
              [:attributes :a/x :values])))
  (testing ":decimal/:date/:datetime/:json 声明 :values 拒绝"
    (doseq [t [:decimal :date :datetime :json]]
      (is (has? (inv/check (model :attributes {:a/x {:type t :values {"A" {:label "a"}}}}))
                :dialect.invariant/type-compat-violation))))
  (testing "词条携带的 :values 同样按本地 :type 严格核对，点名到 :term"
    (is (has? (inv/check (model :terms {"d/flag" {:label "flag" :values {"Y" {:label "是"}}}}
                                :attributes {:a/x {:type :integer :term "d/flag"}}))
              :dialect.invariant/type-compat-violation
              [:attributes :a/x :term])))
  (testing ":format × :type 非法组合拒绝"
    (is (has? (inv/check (model :attributes {:a/x {:type :string :format :currency}}))
              :dialect.invariant/type-compat-violation
              [:attributes :a/x :format])))
  (testing "正例：合法组合"
    (is (empty? (errors (inv/check
                         (model :attributes {:a/x {:type :decimal :format :currency}
                                             :a/y {:type :integer :values {1 {:label "一"}}}
                                             :a/z {:type :boolean :format :code
                                                   :values {true {:label "是"}}}})))))))

;; --- 注册表：units -----------------------------------------------------------

(deftest units-registered-before-use
  (testing "未注册 :unit 拒绝"
    (is (has? (inv/check (model :attributes {:a/x {:type :decimal :unit "USD"}}))
              :dialect.invariant/unregistered-reference
              [:attributes :a/x :unit])))
  (testing "正例：已注册"
    (is (empty? (errors (inv/check (model :units {"USD" {:label "美元"}}
                                          :attributes {:a/x {:type :decimal :unit "USD"}})))))))

;; --- carried ----------------------------------------------------------------

(deftest carried-keys-named
  (testing "carried 键逐项 :info 点名，路径指到键位置"
    (let [fs (inv/check
              (model :resolvers [(resolver :cache {:ttl-ms 600000}
                                           :row-security {:x 1})]
                     :mutations [{:id "m" :result_type "none"
                                  :row-security {:x 1} :source {:type "sql" :query "q"}}]
                     :metrics {"m1" {:entity "a" :expression {:contract :duckdb-spill/v1
                                                              :text "sum(x)"}}}))]
      (is (every? #(= :info (:severity %))
                  (filter #(= :dialect.carried/key-present (:code %)) fs)))
      (doseq [p [[:resolvers 0 :cache]
                 [:resolvers 0 :row-security]
                 [:mutations 0 :row-security]
                 [:mutations]
                 [:metrics "m1" :expression]]]
        (is (has? fs :dialect.carried/key-present p) (str "carried 未点名 " p)))))
  (testing "carried 点名不阻断模型（:info 不是 :error）"
    (is (empty? (errors (inv/check (model :resolvers
                                          [(resolver :row-security {:x 1})])))))))

(deftest clean-model-has-no-findings
  (testing "完整合法模型零 finding"
    (is (empty? (inv/check
                 {:attributes (assoc base-attrs
                                     :cis.master.part/all_parts {:target "cis.master.part"})
                  :namespaces {"cis.master.part" {:kind :master}}
                  :resolvers [(resolver :output [{:cis.master.part/all_parts
                                                  [:cis.master.part/part_no
                                                   :cis.master.part/prod_code]}]
                                        :order {:allowed [:cis.master.part/sku_no]
                                                :default [:cis.master.part/sku_no :asc]}
                                        :eql-params {:prod_code
                                                     {:attribute :cis.master.part/prod_code
                                                      :op :eq :allowed ["A" "B"]}})]
                  :links [{:from :cis.master.part/sku_no :to :cis.master.part/part_no}]})))))

(deftest d2-8-list-limit-exempts-an-in-record-collection
  ;; doc 106 §106.14 item 15 (owner 2026-09-07): the rows are an array inside
  ;; the host record, so their cardinality is fixed by that record and there is
  ;; no next page a `:limit` could bound.
  (let [check (fn [fm]
                (inv/check
                 {:attributes {:a/bs {:target "b" :cardinality :one-to-many}
                               :b/id {:type :string}}
                  :resolvers [{:id "x" :input [] :result_type "list"
                               :output [{:a/bs [:b/id]}]
                               :source {:type "api" :method "GET" :endpoint "/a"
                                        :field_mapping fm}}]}))]
    (is (empty? (filter #(= :dialect.invariant/list-limit-missing (:code %))
                        (check {:a/bs {:_from "$.bs[*]" :b/id "$.id"}}))))
    (testing "a list resolver reading a list endpoint is asked for :limit as before"
      (is (some #(= :dialect.invariant/list-limit-missing (:code %))
                (check {:b/id "$[*].id"}))))))

(deftest d2-8-list-limit
  (testing "list resolver 缺 :limit 拒绝（D2.8）"
    (let [r {:id "x" :input [] :result_type "list"
             :output [:a/b]
             :source {:type "sql" :query "SELECT 1"}}
          fs (inv/check {:attributes {:a/b {:type :string}}
                                :resolvers [r]})]
      (is (some #(= :dialect.invariant/list-limit-missing (:code %)) fs))
      (is (empty? (filter #(= :dialect.invariant/list-limit-missing (:code %))
                          (inv/check
                           {:attributes {:a/b {:type :string}}
                            :resolvers [(assoc r :limit {:default 50 :max 500})]})))))))
