(ns open.atlas.common.connector.spi.batch-test
  "batch 对齐是 SPI 里唯一有算法的部分，也是「丢一位会把后面每个结果静默移到
   错误的父项上」的唯一防线（principles「批量是显式声明，对齐永不丢位」）。
   因此它是纯函数，且这里把空位、重复父键、乱序、类型错配逐个钉住。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.spi :as spi]))

(def ^:private batch
  ;; fixtures/b1 的 demo_part_by_id：{:child-fk "sku_no" :parent-key :cis.master.part/sku_no}
  {:parent-key :cis.master.part/sku_no :child-fk "sku_no"})

(def ^:private fm
  {:cis.master.part/sku_no  "sku_no"
   :cis.master.part/part_no "part_no"})

(def ^:private ctx {:resolver-id "demo_part_by_id" :source-type "sql"})

(defn- opts [result-type] {:result-type result-type :field-mapping fm :ctx ctx})

(defn- inputs [& ids] (mapv #(hash-map :cis.master.part/sku_no %) ids))

(deftest single-aligns-and-keeps-empty-slots-as-nil
  (testing "没有行的父项得 nil（single），且位置不动"
    (let [rows   [{:sku_no 5001 :part_no "ACM-100"}
                  {:sku_no 5003 :part_no "ACM-900"}]
          result (spi/align-batch rows (inputs 5001 9999 5003) batch (opts "single"))]
      (is (= 3 (count result)) "输出必须与 inputs 等长——这是「永不丢位」本身")
      (is (= [{:cis.master.part/sku_no 5001 :cis.master.part/part_no "ACM-100"}
              nil
              {:cis.master.part/sku_no 5003 :cis.master.part/part_no "ACM-900"}]
             result))
      (is (nil? (second result))
          "nil 意思是「供不出这些属性」，不是错误；绝不是 {}"))))

(deftest list-empty-slot-is-empty-vector
  (testing "没有行的父项得 []（list），不是 nil、不是跳过"
    (let [rows [{:sku_no 5001 :part_no "ACM-100"}
                {:sku_no 5001 :part_no "ACM-100b"}]
          result (spi/align-batch rows (inputs 5001 5002) batch (opts "list"))]
      (is (= 2 (count result)))
      (is (= 2 (count (first result))))
      (is (= [] (second result)))
      (is (vector? (second result))))))

(deftest out-of-order-rows-land-on-the-right-parent
  (testing "source 的行序与 inputs 序无关；对齐按 :child-fk 分组而不是按位置"
    (let [rows   [{:sku_no 5005 :part_no "BET-002"}
                  {:sku_no 5001 :part_no "ACM-100"}
                  {:sku_no 5003 :part_no "ACM-900"}]
          result (spi/align-batch rows (inputs 5001 5003 5005) batch (opts "single"))]
      (is (= ["ACM-100" "ACM-900" "BET-002"]
             (mapv :cis.master.part/part_no result))))))

(deftest duplicate-parent-keys-each-get-their-own-slot
  (testing "重复父键：每个位置各自成立，行被复制而不是被消重掉一位"
    (let [rows   [{:sku_no 5001 :part_no "ACM-100"}]
          result (spi/align-batch rows (inputs 5001 5001 5002) batch (opts "list"))]
      (is (= 3 (count result)))
      (is (= (first result) (second result)))
      (is (= [] (nth result 2))))))

(deftest bucket-order-follows-source-order
  (testing "桶内顺序 = source 给的顺序（ORDER BY 是唯一的顺序权威）"
    (let [rows   [{:sku_no 5001 :part_no "b"}
                  {:sku_no 5001 :part_no "a"}
                  {:sku_no 5001 :part_no "c"}]
          result (spi/align-batch rows (inputs 5001) batch (opts "list"))]
      (is (= ["b" "a" "c"] (mapv :cis.master.part/part_no (first result)))))))

(deftest no-rows-at-all-still-fills-every-slot
  (testing "一行都没取回来也不许缩短向量"
    (is (= [nil nil nil] (spi/align-batch [] (inputs 1 2 3) batch (opts "single"))))
    (is (= [[] [] []]    (spi/align-batch nil (inputs 1 2 3) batch (opts "list"))))))

(deftest nil-parent-key-is-a-slot-too
  (testing "父键为 nil 的输入照样占一位（它只是匹配不到行）"
    (let [result (spi/align-batch [{:sku_no 5001 :part_no "x"}]
                                  [{:cis.master.part/sku_no nil}
                                   {:cis.master.part/sku_no 5001}]
                                  batch (opts "single"))]
      (is (= 2 (count result)))
      (is (nil? (first result))))))

(deftest join-key-normalizes-integer-shaped-strings
  (testing "Long 与 \"5001\" 归一到同一个桶——不归一会整批静默落空"
    (is (= (spi/join-key 5001) (spi/join-key "5001")))
    (is (= 5001 (spi/join-key (int 5001))))
    (testing "非整数形状原样，不猜"
      (is (= "R" (spi/join-key "R")))
      (is (= 1.5 (spi/join-key 1.5)))
      (is (nil? (spi/join-key nil)))))
  (testing "父键是字符串、子行 fk 是 Long 时仍然对齐"
    (let [result (spi/align-batch [{:sku_no 5001 :part_no "ACM-100"}]
                                  [{:cis.master.part/sku_no "5001"}]
                                  batch (opts "single"))]
      (is (= "ACM-100" (:cis.master.part/part_no (first result)))))))

(deftest grouping-precedes-mapping
  (testing "先按 :child-fk 分组、后做 field mapping（契约原文的顺序）：
            分组列不在 field mapping 里也照样对齐"
    (let [result (spi/align-batch [{:sku_no 5001 :part_no "ACM-100"}]
                                  (inputs 5001)
                                  batch
                                  {:result-type "single"
                                   :field-mapping {:cis.master.part/part_no "part_no"}
                                   :ctx ctx})]
      (is (= [{:cis.master.part/part_no "ACM-100"}] result)))))

(deftest missing-column-is-a-mapping-failure-not-a-half-filled-map
  (testing "映射到的列不在结果集里 → :mapping 故障，不产半填 map"
    (let [e (try (spi/align-batch [{:sku_no 5001}] (inputs 5001) batch (opts "single"))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (spi/failure? e))
      (is (= :mapping (spi/error-category e))))))

;; --- per-parent 切窗（sql-v1「分页在 batch 上是 per-parent 窗口，不下推」）---

(defn- win-opts [w] (assoc (opts "list") :window w))

(deftest window-is-per-parent-not-global
  (testing "每个父项各自取前 N 条——全局 LIMIT 会截断合并后的行集，丢的是别人的行"
    (let [rows   [{:sku_no 5001 :part_no "a1"} {:sku_no 5001 :part_no "a2"}
                  {:sku_no 5001 :part_no "a3"} {:sku_no 5002 :part_no "b1"}
                  {:sku_no 5002 :part_no "b2"}]
          result (spi/align-batch rows (inputs 5001 5002) batch
                                  (win-opts {:limit 2}))]
      (is (= ["a1" "a2"] (mapv :cis.master.part/part_no (first result))))
      (is (= ["b1" "b2"] (mapv :cis.master.part/part_no (second result)))
          "第二个父项拿到自己的两条，而不是被第一个父项吃掉配额"))))

(deftest window-never-drops-a-slot
  (testing "切窗只让桶变短（可以短到空），永远不让父项失去位置"
    (let [rows   [{:sku_no 5001 :part_no "a1"} {:sku_no 5001 :part_no "a2"}]
          result (spi/align-batch rows (inputs 5001 9999 5002) batch
                                  (win-opts {:limit 1}))]
      (is (= 3 (count result)) "长度不变量高于一切")
      (is (= ["a1"] (mapv :cis.master.part/part_no (first result))))
      (is (= [] (second result)) "无行的父项仍是 []")
      (is (= [] (nth result 2))))))

(deftest window-offset-applies-inside-the-bucket
  (testing "offset 是桶内偏移，不是全局偏移"
    (let [rows   [{:sku_no 5001 :part_no "a1"} {:sku_no 5001 :part_no "a2"}
                  {:sku_no 5001 :part_no "a3"} {:sku_no 5002 :part_no "b1"}
                  {:sku_no 5002 :part_no "b2"}]
          result (spi/align-batch rows (inputs 5001 5002) batch
                                  (win-opts {:limit 2 :offset 1}))]
      (is (= ["a2" "a3"] (mapv :cis.master.part/part_no (first result))))
      (is (= ["b2"] (mapv :cis.master.part/part_no (second result)))
          "第二个父项只有两条，跳过一条后剩一条——不向别的桶借"))))

(deftest window-absent-means-no-slicing
  (testing "没有 :window 时行为与切窗前逐字相同（现存语料不受影响）"
    (let [rows [{:sku_no 5001 :part_no "a1"} {:sku_no 5001 :part_no "a2"}]]
      (is (= (spi/align-batch rows (inputs 5001) batch (opts "list"))
             (spi/align-batch rows (inputs 5001) batch (win-opts nil))
             (spi/align-batch rows (inputs 5001) batch (win-opts {})))))))

(deftest window-slices-before-mapping
  (testing "被窗口切掉的行不参与映射——它们不属于结果，其映射问题不该冒充本次故障"
    (let [rows [{:sku_no 5001 :part_no "a1"}
                {:sku_no 5001 :no_such_column "x"}]] ; 第二条缺 part_no
      (is (= ["a1"] (mapv :cis.master.part/part_no
                          (first (spi/align-batch rows (inputs 5001) batch
                                                  (win-opts {:limit 1})))))
          "limit 1 把坏行切掉了，因此不该抛映射故障")
      (is (thrown? clojure.lang.ExceptionInfo
                   (spi/align-batch rows (inputs 5001) batch (opts "list")))
          "不切窗时它仍然是映射故障——切窗不是在掩盖问题"))))

(deftest single-is-not-windowed
  (testing "\"single\" 按定义只取一行，窗口无从表达，声明了也不影响"
    (let [rows [{:sku_no 5001 :part_no "a1"} {:sku_no 5001 :part_no "a2"}]]
      (is (= (spi/align-batch rows (inputs 5001) batch (opts "single"))
             (spi/align-batch rows (inputs 5001) batch
                              (assoc (opts "single") :window {:limit 1 :offset 1})))))))
