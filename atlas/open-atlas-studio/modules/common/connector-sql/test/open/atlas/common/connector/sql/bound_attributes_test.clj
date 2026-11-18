(ns open.atlas.common.connector.sql.bound-attributes-test
  "E1 / D5.14: `spi/bound-attributes` on the sql connector.

   What the cut-time row-security check asks this seam is narrow and exact:
   **which of the entry's `:input` attributes does this statement bind a value
   for**. The shapes below are the ways a sql statement gets a binding, plus
   the three that look like a binding and must not count."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql]))

(defn- bound [src] (spi/bound-attributes src))

(deftest identity-binding
  (testing "D2.1 恒等缺省：占位名 = `:input` 属性的 local name，`:params` 可以不写"
    (is (= #{:order/id :order/tenant_id}
           (bound {:type "sql"
                   :input [:order/id :order/tenant_id]
                   :query "SELECT * FROM orders WHERE id = :id AND tenant_id = :tenant_id"})))))

(deftest explicit-params-binding
  (testing ":params 显式绑定——占位名与属性 local name 不同名时唯一的写法"
    (is (= #{:order/tenant_id}
           (bound {:type "sql"
                   :input [:order/tenant_id]
                   :params {:t :order/tenant_id}
                   :query "SELECT * FROM orders WHERE tenant_id = :t"})))))

(deftest an-eql-param-binding-is-not-an-input-binding
  (testing "D5.6 共识 2（D5.14 维持）：行级安全绝不绑调用方提供的值——调用方
            自己挑的谓词等于没有谓词。所以绑到 eql-param 的占位符不算数,
            哪怕同名属性也在 `:input` 里"
    (is (= #{:order/id}
           (bound {:type "sql"
                   :input [:order/id :order/tenant_id]
                   :eql-params {:tenant_id {:attribute :order/tenant_id}}
                   :params {:tenant_id :tenant_id}
                   :query "SELECT * FROM orders WHERE id = :id AND tenant_id = :tenant_id"})))))

(deftest in-list-expansion-is-one-bound-name
  (testing "`IN (:tenant_id)` 展开成 N 个 `?`，但绑定名仍是一个"
    (is (= #{:order/tenant_id}
           (bound {:type "sql"
                   :input [:order/tenant_id]
                   :query "SELECT * FROM orders WHERE tenant_id IN (:tenant_id)"})))))

(deftest batch-placeholder-is-not-a-binding
  (testing "batch 的 `:placeholder` 由 N 个父键绑定、不经 `:params`——它不是
            行级切分的绑定名，算进来会让一个只按父键取数的 resolver 看起来
            像是按租户切过分"
    (is (= #{:order/tenant_id}
           (bound {:type "sql"
                   :input [:order/customer_id :order/tenant_id]
                   :batch {:placeholder "ids"}
                   :query (str "SELECT * FROM orders WHERE customer_id IN (:ids) "
                               "AND tenant_id = :tenant_id")})))))

(deftest an-unresolvable-placeholder-is-not-bound
  (testing "语句里写着 `:tenant_id` 却既不在 `:input` 也不在 `:params` 里——
            写入端已由 `:connector.source/placeholder-mismatch` 拒；这里也不能
            把它算成「绑上了」，否则核对恰在接错线的那一类模型上放行"
    (is (= #{:order/id}
           (bound {:type "sql"
                   :input [:order/id]
                   :query "SELECT * FROM orders WHERE id = :id AND tenant_id = :tenant_id"})))))

(deftest an-unregistered-source-type-cannot-tell
  (testing "缺省方法返回 nil =「我答不出」，不是「一个都没绑」。两个方向都不猜——
            调用方（E1 的体检）再把「本机没装这个连接器」与「装了但答不出」
            分成两条 finding，因为两件事两个人修"
    (is (nil? (bound {:type "no-such-connector" :query "SELECT 1"})))))
