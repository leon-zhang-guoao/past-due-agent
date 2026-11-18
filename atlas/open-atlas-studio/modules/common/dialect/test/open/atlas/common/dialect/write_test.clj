(ns open.atlas.common.dialect.write-test
  "Canonical writer 布局测试（dialect-v1 §4「布局」+ §7 往返一条）。
   判据是逐字节，不是「看起来对」：黄金向量在 fixtures/canonical/，
   等价变体（乱序键 + #:ns{} 缩写）在 fixtures/variant/<同名>/。"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            #_{:clj-kondo/ignore [:discouraged-namespace]}
            [open.atlas.common.dialect.impl.write :as write]))

(def fixture-names
  ["namespace-attributes" "resolver-batch" "resolver-curated" "resolver-single"])

(defn- slurp-resource [path]
  (some-> (io/resource path) slurp))

(deftest layout-determinism
  (testing "同一模型两次输出逐字节相同（布局只依赖起始列与单行宽度）"
    (doseq [n fixture-names
            :let [m (edn/read-string (slurp-resource (str "canonical/" n ".edn")))]]
      (is (= (write/write-dialect m) (write/write-dialect m)) n)))
  (testing "键序不影响输出（字典序是唯一形，D2.12）"
    (is (= (write/write-dialect (array-map :b 1 :a 2))
           (write/write-dialect (array-map :a 2 :b 1)))))
  (testing "文件尾单个换行"
    (doseq [n fixture-names]
      (let [s (slurp-resource (str "canonical/" n ".edn"))]
        (is (str/ends-with? s "\n") n)
        (is (not (str/ends-with? s "\n\n")) n)))))

(deftest hundred-column-wrapping
  (testing "放得下就单行：起始列 + 单行宽度 ≤ 100"
    (is (= "{:a [1 2 3]}\n" (write/write-dialect {:a [1 2 3]}))))
  (testing "向量超 100 列在元素边界换行，续行对齐到 `[` 后一列"
    (let [v (mapv #(keyword "cis.master.part" (str "attr_" %)) (range 6))
          out (write/write-dialect {:output v})
          lines (str/split-lines out)]
      (is (< 1 (count lines)))
      (is (every? #(<= (count %) 100) lines))
      ;; " :output [" —— `[` 在第 9 列（0 基），元素起于第 10 列
      (is (every? #(str/starts-with? % (apply str (repeat 10 \space))) (rest lines)))))
  (testing "叶子值不折行：超长字符串所在行可以突破 100 列"
    (let [long-sql (apply str (repeat 150 \x))
          out (write/write-dialect {:source {:query long-sql :type "sql"}})]
      (is (some #(< 100 (count %)) (str/split-lines out)))
      (is (str/includes? out (str \" long-sql \")))))
  (testing "map 放不下则一键一行，续行对齐到 `{` 后一列"
    (let [out (write/write-dialect {:a (apply str (repeat 60 \x))
                                    :b (apply str (repeat 60 \y))})]
      (is (= 2 (count (str/split-lines out))))
      (is (str/starts-with? (second (str/split-lines out)) " :b ")))))

(deftest fixtures-round-trip
  (testing "canonical 读入 → write → 逐字节相同（§7 往返）"
    (doseq [n fixture-names
            :let [s (slurp-resource (str "canonical/" n ".edn"))]]
      (is (= s (write/write-dialect (edn/read-string s))) n)))
  (testing "variant（乱序键 + #:ns{} 缩写）→ write → 与 canonical 同字节"
    (doseq [n fixture-names
            :let [canon (slurp-resource (str "canonical/" n ".edn"))
                  var-s (slurp-resource (str "variant/" n "/shuffled-ns-abbrev.edn"))]]
      (is (some? var-s) n)
      (is (= canon (write/write-dialect (edn/read-string var-s))) n))))

(deftest defaults-must-not-be-written
  (testing "缺省值残留即断言失败（dialect-v1 §4 缺省值不书写）"
    (is (thrown? AssertionError (write/write-dialect {:curated? false})))
    (is (thrown? AssertionError (write/write-dialect {:links [{:direction :bi}]})))
    (is (thrown? AssertionError
                 (write/write-dialect {:eql-params {:p {:required? false}}}))))
  (testing "非缺省值照写"
    (is (= "{:curated? true}\n" (write/write-dialect {:curated? true})))
    (is (= "{:label \"Name\"}\n" (write/write-dialect {:label "Name"})))))
