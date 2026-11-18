(ns open.atlas.common.candidates.candidates-test
  "**机器提议**本体（D2.25 / D2.26 / D2.27 / D2.13 / D5.1）。

   本 ns 守的是搬迁之后仍然只有**一份**提议实现该有的行为。B2 语料上的那批断言
   当年留在 legacy-import（它们判的是「这份语料应当挖出什么」，那是 B2 的事）,
   2026-08-24 随语料与转换器一并删除（fixtures/demo/README.md）——判据没了，
   断言就不该留着；要看它们走 `git log -- modules/legacy-import`。
   本 ns 的模型是**手写的小而全**，不依赖任何语料，所以那次删除动不到它。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.candidates :as cc]))

(def ^:private model
  "一个**小而全**的模型：两个 namespace、一簇兼容重名、一簇类型不兼容、
   一簇码值编码不兼容、一条无锚 list resolver、一个疑似需要授权的属性。"
  {:namespaces {"a" {} "b" {}}
   :attributes {:a/order_no  {:type :string}
                :b/order_no  {:type :string}
                :a/entry_id  {:type :string}
                :b/entry_id  {:type :string}
                :a/code      {:type :string :values {"Y" {}}}
                :b/code      {:type :string :values {1 {}}}
                :a/n         {:type :integer}
                :b/n         {:type :string}
                :a/credit_limit {:type :decimal}}
   :resolvers [{:id "a-list" :result_type "list" :input [] :label "所有 a"}]})

(deftest term-candidate-ids-are-never-invented-by-the-machine
  (testing "D2.26：白名单命中的按**概念**命名；其余的 ID 留空，等 owner 命名"
    (let [by-local (into {} (for [t (:terms (cc/candidates model))] [(:local-name t) t]))]
      (is (= "common.audit/created_by" (:term (by-local "entry_id"))))
      (is (= :audit-whitelist (:source (by-local "entry_id"))))
      (is (nil? (:term (by-local "order_no"))))
      (is (true? (:id-required-from-owner (by-local "order_no")))
          "沿用历史字段名当概念名是 D2.26 的明文禁令，所以这里必须留空"))))

(deftest incompatible-bindings-produce-no-candidate
  (testing "D2.27：类型或码值编码不一致 → **不出候选**，改报 finding
            （猜编码的失败形态是静默错配）"
    (let [{:keys [candidates findings]} (cc/term-candidates model)]
      (is (empty? (filter #(#{"code" "n"} (:local-name %)) candidates)))
      (is (= #{:candidate/type-mismatch} (set (map :code findings))))
      (is (= #{[:terms "code"] [:terms "n"]} (set (map :path findings)))))))

(deftest findings-are-registered-and-well-formed
  (testing "finding-v1 §6：产出未注册 code 是实现 bug（`make` 直接 assert 失败）"
    (let [fs (:findings (cc/candidates model))]
      (is (seq fs))
      (is (every? cc/finding? fs))
      (is (every? cc/finding-codes (map :code fs))))))

(deftest curated-is-a-proposal-not-a-conclusion
  (testing "D2.13：无锚的 list resolver 待签，不由 `:input` 为空机械推定"
    (let [c (:curated (cc/candidates model))]
      (is (= ["a-list"] (mapv :resolver c))))))

;; ---------------------------------------------------------------------------
;; 候选身份：签字通道的键
;; ---------------------------------------------------------------------------

(deftest candidate-keys-carry-their-kind
  (testing "四张清单四套寻址法，而 `confirm_candidates` 收到的是两个参数、没有
            上下文——所以身份必须自带种类"
    (let [idx (cc/keyed (cc/candidates model))]
      (is (contains? idx [:term "order_no"]))
      (is (contains? idx [:curated "a-list"]))
      (is (= (set cc/kinds) (into #{} (map first) (keys idx)))
          "这个模型两种候选各有至少一条"))))

(deftest proposing-never-touches-the-model
  (testing "本模块的全部意义：**不改 model**"
    (is (= model (do (cc/candidates model) model)))))
