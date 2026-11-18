(ns open.atlas.common.connector.spi.mapping-test
  "field mapping 方向永远 `{属性kw → source 路径}`（principles）；
   恒等映射缺省（D2.1）——解析时按「属性 local name = 列名」补齐。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.spi :as spi]))

(deftest identity-mapping-is-filled-in
  (testing "D2.1：没写的按 local name = 列名补齐"
    (is (= {:cis.master.part/sku_no  "sku_no"
            :cis.master.part/part_no "part_no"}
           (spi/resolve-field-mapping nil [:cis.master.part/sku_no :cis.master.part/part_no]))))
  (testing "写出来的覆盖恒等缺省，列名统一成字符串"
    (is (= {:cis.master.part/part_no "mfg_part"}
           (spi/resolve-field-mapping {:cis.master.part/part_no "mfg_part"}
                                      [:cis.master.part/part_no])))
    (is (= {:cis.master.part/part_no "mfg_part"}
           (spi/resolve-field-mapping {:cis.master.part/part_no :mfg_part}
                                      [:cis.master.part/part_no])))))

(deftest output-attributes-walks-the-syntax-tree
  (testing "扁平 output"
    (is (= [:cis.master.part/part_no :cis.master.part/short_desc]
           (spi/output-attributes [:cis.master.part/part_no :cis.master.part/short_desc]))))
  (testing "join output：叶子属性进 mapping，父键不进（它不是 source 列）"
    (let [out [{:cis.master.vendor/parts [:cis.master.part/sku_no :cis.master.part/part_no]}]]
      (is (= [:cis.master.part/sku_no :cis.master.part/part_no] (spi/output-attributes out)))
      (is (= :cis.master.vendor/parts (spi/join-parent out)))))
  (testing "扁平 output 没有 join parent"
    (is (nil? (spi/join-parent [:cis.master.part/part_no])))))

(deftest sql-null-is-a-value-missing-column-is-a-failure
  (let [ctx {:resolver-id "r" :source-type "sql"}]
    (testing "列在、值是 NULL → 保留 nil（source 的真实答案）"
      (is (= {:cis.master.vendor/contact_email nil}
             (spi/apply-field-mapping {:cis.master.vendor/contact_email "contact_email"}
                                      {:contact_email nil} ctx))))
    (testing "列不在结果集里 → :mapping 故障，而不是悄悄少一个键"
      (is (= :mapping
             (try (spi/apply-field-mapping {:cis.master.vendor/contact_email "contact_email"}
                                           {:vend_no 1} ctx)
                  nil
                  (catch clojure.lang.ExceptionInfo e (spi/error-category e))))))
    (testing "列名大小写按 unqualified-lower 归一"
      (is (= {:cis.master.vendor/vend_no 1001}
             (spi/apply-field-mapping {:cis.master.vendor/vend_no "VEND_NO"}
                                      {:vend_no 1001} ctx))))))

(deftest a-branch-scoped-mapping-gives-a-self-relation-its-own-value
  "doc 106 §106.14 item 6 (owner 2026-09-07). Parent and child in ONE
   namespace means the branch's leaf and the resolver's own identity output
   are the SAME attribute kw, and one flat row cannot carry two values for it.
   The branch therefore declares its own scope, and the row comes back already
   shaped — which the engine's `nest-row` leaves alone."
  (let [ctx {:resolver-id "r" :source-type "sql"}
        fm  {:nifi.pg/id "id" :nifi.pg/parent {:nifi.pg/id "parent_id"}}]
    (testing "the top-level identity is untouched and the branch carries the parent"
      (is (= {:nifi.pg/id "g2" :nifi.pg/parent {:nifi.pg/id "g1"}}
             (spi/apply-field-mapping fm {:id "g2" :parent_id "g1"} ctx))))
    (testing "a root with no parent answers nil, not a half-filled map"
      (is (= {:nifi.pg/id "g1" :nifi.pg/parent nil}
             (spi/apply-field-mapping fm {:id "g1" :parent_id nil} ctx))))
    (testing "resolve-field-mapping carries the scope through and does not flatten it"
      (is (= {:nifi.pg/id "id" :nifi.pg/parent {:nifi.pg/id "parent_id"}}
             (spi/resolve-field-mapping fm [:nifi.pg/id]))))))
