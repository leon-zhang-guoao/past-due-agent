(ns open.atlas.studio.model.output-shape-test
  "The output tree is the only place `:output` and `:field_mapping` are derived
   from each other, so the round trip is what these tests are really about."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.studio.model.output-shape :as shape]))

(def ^:private joined
  "A resolver whose output mixes a plain attribute with a collection join, and
   whose mapping names a column for one member and one leaf."
  {:id "demo_mixed"
   :input []
   :result_type "list"
   :output [:c.a/plain
            {:c.a/all_things [:c.a/id :c.a/name]}]
   :source {:type "sql"
            :query "SELECT …"
            :field_mapping {:c.a/plain "plain_col"
                            :c.a/name  "thing_name"}}})

(deftest a-join-survives-reading-and-writing-unchanged
  (testing "读 → 写不丢结构，也不改写任何一个字节"
    (is (= (select-keys joined [:output :source])
           (select-keys (shape/tree->entry joined (shape/entry->tree joined))
                        [:output :source])))))

(deftest bindings-come-back-onto-the-attributes-that-carry-them
  (let [tree (shape/entry->tree joined)
        [plain coll] tree]
    (is (= :c.a/plain (:attribute plain)))
    (is (= "plain_col" (:bind plain)))
    (testing "父 collection 永远没有 binding —— 结果里的列是成员，不是它"
      (is (nil? (:bind coll)))
      (is (= :c.a/all_things (:attribute coll))))
    (testing "成员各自带自己的列，没写的就是恒等缺省"
      (is (= [:c.a/id :c.a/name] (mapv :attribute (:members coll))))
      (is (nil? (:bind (first (:members coll)))))
      (is (= "thing_name" (:bind (second (:members coll))))))))

(deftest a-collection-can-never-carry-a-binding
  (testing "就算调用方硬塞一个，写回时也不许出现 —— 这条规则在这里执法，
            而不是指望 UI 不那么做"
    (let [tree [{:attribute :c.a/all_things :bind "sneaky"
                 :members [{:attribute :c.a/id :bind nil :members nil}]}]
          out  (shape/tree->entry {:source {:type "sql"}} tree)]
      (is (nil? (get-in out [:source :field_mapping])))
      (is (= [{:c.a/all_things [:c.a/id]}] (:output out))))))

(deftest removing-a-member-takes-its-mapping-with-it
  (let [tree (shape/entry->tree joined)
        pruned (update-in (vec tree) [1 :members] #(vec (remove (fn [m] (= :c.a/name (:attribute m))) %)))
        out (shape/tree->entry joined pruned)]
    (is (= [{:c.a/all_things [:c.a/id]}] (filterv map? (:output out))))
    (testing "被删掉那个成员的列一并消失，不留成孤儿映射"
      (is (= {:c.a/plain "plain_col"} (get-in out [:source :field_mapping]))))))

(deftest editing-a-plain-output-leaves-join-members-alone
  (testing "**回归**：平铺行重写整张 field_mapping 时，最容易顺手把 join
            成员的映射抹掉"
    (let [tree (shape/entry->tree joined)
          renamed (assoc-in (vec tree) [0 :bind] "renamed_col")
          out (shape/tree->entry joined renamed)]
      (is (= {:c.a/plain "renamed_col" :c.a/name "thing_name"}
             (get-in out [:source :field_mapping]))))))

(deftest an-identity-binding-is-not-written
  (testing "列名等于属性 local name 就是缺省，写出来便不是规范形（D2.1）"
    (let [tree [{:attribute :c.a/plain :bind "plain" :members nil}]
          out (shape/tree->entry {:source {:type "sql"}} tree)]
      (is (nil? (get-in out [:source :field_mapping]))))))

(deftest several-joins-keep-their-order
  (let [entry {:output [{:c.a/first [:c.a/x]} :c.a/mid {:c.a/second [:c.a/y]}]
               :source {:type "sql"}}
        out (shape/tree->entry entry (shape/entry->tree entry))]
    (is (= [{:c.a/first [:c.a/x]} :c.a/mid {:c.a/second [:c.a/y]}] (:output out)))))

;; ---------------------------------------------------------------------------
;; Shapes across a model
;; ---------------------------------------------------------------------------

(deftest shapes-are-per-producer-and-never-unioned
  (let [model {:resolvers [{:id "p1" :output [{:c.a/all [:c.a/x :c.a/y]}]}
                           {:id "p2" :output [{:c.a/all [:c.a/x]}]}
                           {:id "p3" :output [:c.a/plain]}]}
        shapes (shape/collection-shapes model)]
    (is (= 2 (count shapes)) "只有 join 出现在 shapes 里")
    (is (= #{"p1" "p2"} (set (map :producer shapes))))

    (testing "同一个 collection 两个 producer 成员不同 → **明说冲突**，
              不静默 union 出一个谁都没声明过的成员表"
      (let [by (shape/shapes-by-collection shapes)]
        (is (false? (:consistent? (get by :c.a/all))))
        (is (= [["p1" [:c.a/x :c.a/y]] ["p2" [:c.a/x]]]
               (mapv (juxt :producer :members) (:producers (get by :c.a/all)))))))

    (testing "形状一致时可以折叠"
      (let [same (shape/shapes-by-collection
                  [{:collection :c.a/all :members [:c.a/x] :producer "p1"}
                   {:collection :c.a/all :members [:c.a/x] :producer "p2"}])]
        (is (true? (:consistent? (get same :c.a/all))))))))
