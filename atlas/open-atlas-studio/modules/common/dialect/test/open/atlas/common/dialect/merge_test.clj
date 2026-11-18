(ns open.atlas.common.dialect.merge-test
  "分拆文件纯并集测试（TODO(B0-6)）。dialect-v1 §4 冲突面逐项 + §7「合并」行。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.dialect :as dialect]))

(defn- codes [findings] (set (map :code findings)))

(defn- dup [findings]
  (first (filter #(= :dialect.merge/duplicate-entry (:code %)) findings)))

(def part-attrs
  {:cis.master.part/sku_no  {:type :integer :identity? true :format :id}
   :cis.master.part/part_no {:type :string}})

(defn- named [file model] {:file file :model model})

(deftest pure-union
  (testing "无冲突：两文件并集，键面完整"
    (let [{:keys [model findings]}
          (dialect/merge-dialects
           [(named "attributes.edn" {:attributes part-attrs})
            (named "resolvers.edn"
                   {:resolvers [{:id "demo_part_by_id"
                                 :input [:cis.master.part/sku_no]
                                 :output [:cis.master.part/part_no]
                                 :result_type "single"
                                 :source {:type "sql" :query "q"}}]})])]
      (is (empty? findings))
      (is (= part-attrs (:attributes model)))
      (is (= 1 (count (:resolvers model)))))))

(deftest cross-file-registry-reference
  (testing "注册表引用跨文件成立：units.edn 与用它的属性分处两文件不报错（§4）"
    (let [{:keys [model findings]}
          (dialect/merge-dialects
           [(named "units.edn" {:units {"USD" {:label "美元"}}})
            (named "attributes.edn" {:attributes {:a/cost {:type :decimal :unit "USD"}}})])]
      (is (empty? findings))
      (is (some? model))))
  (testing "合并后整体重跑不变量：跨文件也查不出的引用照样拒绝"
    (let [{:keys [model findings]}
          (dialect/merge-dialects
           [(named "units.edn" {:units {"EUR" {:label "欧元"}}})
            (named "attributes.edn" {:attributes {:a/cost {:type :decimal :unit "USD"}}})])]
      (is (nil? model))
      (is (contains? (codes findings) :dialect.invariant/unregistered-reference)))))

(deftest duplicate-resolver-id
  (testing "两文件同 id resolver 报错并点名两个文件"
    (let [r {:id "demo_part_by_id" :input [] :output [:cis.master.part/part_no]
             :result_type "single" :source {:type "sql" :query "q"}}
          {:keys [model findings]}
          (dialect/merge-dialects
           [(named "a.edn" {:attributes part-attrs :resolvers [r]})
            (named "b.edn" {:resolvers [r]})])]
      (is (nil? model))
      (is (= :dialect.merge/duplicate-entry (:code (dup findings))))
      (is (= ["a.edn" "b.edn"] (:files (dup findings))))
      (is (= "demo_part_by_id" (:got (dup findings)))))))

(deftest duplicate-mutation-id
  (testing "两文件同 id mutation 报错"
    (let [m {:id "create_part" :result_type "none" :source {:type "sql" :query "q"}}
          {:keys [findings]} (dialect/merge-dialects
                              [(named "a.edn" {:mutations [m]})
                               (named "b.edn" {:mutations [m]})])]
      (is (= ["a.edn" "b.edn"] (:files (dup findings)))))))

(deftest duplicate-map-entries
  (testing "同键 attribute/source/namespace/unit/metric/term 一律报错点名两文件"
    (doseq [[k entry] [[:attributes {:a/x {:type :string}}]
                       [:sources {"db" {:type "sql"}}]
                       [:namespaces {"a" {:kind :master}}]
                       [:units {"USD" {:label "美元"}}]
                       [:metrics {"m" {:entity "a"
                                       :expression {:contract :duckdb-spill/v1 :text "1"}}}]
                       [:terms {"common.audit/created_by" {:label "录入人"}}]]]
      (let [{:keys [model findings]}
            (dialect/merge-dialects [(named "a.edn" {k entry})
                                     (named "b.edn" {k entry})])]
        (is (nil? model) (str k " 未拒绝"))
        (is (= ["a.edn" "b.edn"] (:files (dup findings))) (str k " 未点名两个文件"))
        (is (= [k (ffirst entry)] (:path (dup findings))) (str k " path 未指到条目"))))))

(deftest duplicate-link-endpoints
  (testing ":bi 两端字典序规范化后同端点即冲突（D2.3）"
    (let [{:keys [model findings]}
          (dialect/merge-dialects
           [(named "a.edn" {:attributes part-attrs
                            :links [{:from :cis.master.part/sku_no
                                     :to :cis.master.part/part_no}]})
            (named "b.edn" {:links [{:from :cis.master.part/part_no
                                     :to :cis.master.part/sku_no}]})])]
      (is (nil? model))
      (is (= ["a.edn" "b.edn"] (:files (dup findings))))))
  (testing ":one-way 反向不是同端点，合法并集"
    (let [{:keys [model findings]}
          (dialect/merge-dialects
           [(named "a.edn" {:attributes part-attrs
                            :links [{:from :cis.master.part/sku_no
                                     :to :cis.master.part/part_no
                                     :direction :one-way}]})
            (named "b.edn" {:links [{:from :cis.master.part/part_no
                                     :to :cis.master.part/sku_no
                                     :direction :one-way}]})])]
      (is (empty? findings))
      (is (= 2 (count (:links model)))))))

(deftest findings-are-well-formed
  (testing "merge 产出的 finding 过 finding-v1 封闭 schema"
    (let [{:keys [findings]}
          (dialect/merge-dialects [(named "a.edn" {:units {"USD" {:label "美元"}}})
                                   (named "b.edn" {:units {"USD" {:label "美元"}}})])]
      (is (seq findings))
      (is (every? dialect/finding? findings)))))
