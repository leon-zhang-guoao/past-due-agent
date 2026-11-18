(ns open.atlas.common.dialect-test
  "B0 接口层测试。conformance 矩阵（dialect-v1 §7）逐条落在这里与
   fixtures/ 的黄金向量上；当前先覆盖已实现的 finding 契约与 emit 确定性。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.dialect :as dialect]
            #_{:clj-kondo/ignore [:discouraged-namespace]}
            [open.atlas.common.dialect.impl.write :as write]
            #_{:clj-kondo/ignore [:discouraged-namespace]}
            [open.atlas.common.dialect.impl.finding :as finding]))

(deftest finding-contract
  (testing "构造合法 finding"
    (is (dialect/finding?
         (finding/make :dialect.schema/unknown-key :error [:resolvers 0]
                       "unknown key :foo" :got :foo :refs ["dialect-v1 §3"]))))
  (testing "未注册 code 拒绝"
    (is (thrown? AssertionError
                 (finding/make :dialect.schema/made-up :error [] "x"))))
  (testing "不泄漏截断"
    (is (= "string(11 chars)" (finding/redact "SELECT 1 --")))))

(deftest emit-determinism
  (testing "map 键字典序，与输入顺序无关"
    (is (= (write/emit {:b 1 :a 2}) (write/emit {:a 2 :b 1})))
    (is (= "{:a 2 :b 1}" (write/emit {:b 1 :a 2}))))
  (testing "keyword 全限定形，不产 #:ns{}"
    (is (= "{:cis.master.vendor/vend_no 1}"
           (write/emit {:cis.master.vendor/vend_no 1}))))
  (testing "值域外类型拒绝"
    (is (thrown? clojure.lang.ExceptionInfo (write/emit #inst "2026-01-01")))))

(deftest read-parse-errors
  (testing "非法 EDN → 结构化 finding，无模型"
    (let [{:keys [model findings]} (dialect/read-dialect "{:a")]
      (is (nil? model))
      (is (= :dialect.schema/parse-error (:code (first findings)))))))

(deftest a-written-default-normalizes-instead-of-rejecting
  "owner 裁定 2026-08-27（改判 dialect-v1 §4 的 reader 半边）：书写出来的缺省值
   是等价拼法，读入即消除。writer 仍只产规范形，往返不变量不降反升。"
  (let [r (dialect/read-dialect
           (pr-str {:namespaces {"s.a" {}}
                    :attributes {:s.a/x {:type :string :listed? true}}}))]
    (is (empty? (:findings r)))
    (is (= {:type :string} (get-in r [:model :attributes :s.a/x]))
        "归一后与不写这个键逐字节同一个模型"))
  (testing "恰等才是缺省；词表外的值仍拒"
    (let [r (dialect/read-dialect
             (pr-str {:namespaces {"s.a" {}}
                      :attributes {:s.a/x {:type :string :listed? "yes"}}}))]
      (is (seq (filter #(= :error (:severity %)) (:findings r)))))))
