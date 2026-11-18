(ns open.atlas.common.artifact.graph-test
  "D1.12：引用图可达性——纯图论，studio 与 server 共用的那一份。

   本文件只测**图论**：可达性、路径枚举、成环、缺失节点、确定性。不测
   R3.4/R3.8 那类判断——那些是 studio 的 finding（`modules/studio/version`
   的 closure-test），本模块不产生任何一条。

   用最简单的 `{node-key -> children}` map 当被测图，`resolve` 就是查这张表；
   不存在的 key 对应 nil（缺失），存在但没有出边的 key 对应 `[]`（叶子）。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.artifact :as artifact]))

(defn- resolver [graph]
  (fn [k _path] (get graph k)))

;; ---------------------------------------------------------------------------
;; 基本可达性
;; ---------------------------------------------------------------------------

(deftest a-linear-chain-is-fully-reachable
  (let [g {:a [:b] :b [:c] :c []}
        r (artifact/graph-traverse :root [:a] (resolver g))]
    (is (= [:a :b :c] (:order r)))
    (is (= [[:a]] (get (:paths r) :a)))
    (is (= [[:a :b]] (get (:paths r) :b)))
    (is (= [[:a :b :c]] (get (:paths r) :c)))
    (is (empty? (:cycles r)))
    (is (empty? (:missing r)))))

(deftest no-references-is-the-empty-closure
  (let [r (artifact/graph-traverse :root [] (resolver {}))]
    (is (= [] (:order r)))
    (is (= {} (:paths r)))
    (is (empty? (:cycles r)))
    (is (empty? (:missing r)))))

(deftest a-resolver-returning-nothing-for-the-root-children-yields-missing
  "root 的每个直接子节点若在 resolver 那里查不到（nil），本身就是一个缺失节点
   ——不需要更深的图。"
  (let [r (artifact/graph-traverse :root [:ghost] (resolver {}))]
    (is (= [] (:order r)) "缺失节点不进 :order——它不是一个已知的东西")
    (is (= [{:node :ghost :path [:root :ghost]}] (:missing r)))))

;; ---------------------------------------------------------------------------
;; 钻石：同一节点经两条路径到达
;; ---------------------------------------------------------------------------

(deftest a-diamond-enumerates-both-paths
  "a → b、a → c → b：b 经两条路径到达，两条都要枚举出来（供 R3.4 点名）。"
  (let [g {:a [:b :c] :c [:b] :b []}
        r (artifact/graph-traverse :root [:a] (resolver g))]
    (is (= [:a :b :c] (:order r)))
    (is (= [[:a :b] [:a :c :b]] (sort (get (:paths r) :b)))
        "两条到达 b 的路径都在")))

;; ---------------------------------------------------------------------------
;; 成环
;; ---------------------------------------------------------------------------

(deftest a-cycle-is-reported-not-truncated
  "a → b → c → a：成环，拒绝的责任在调用方，本函数只负责**点名**，不截断。"
  (let [g {:a [:b] :b [:c] :c [:a]}
        r (artifact/graph-traverse :root [:a] (resolver g))]
    (is (= 1 (count (:cycles r))))
    (is (= [:root :a :b :c :a] (:path (first (:cycles r)))))))

(deftest a-self-cycle-is-reported
  (let [g {:a [:a]}
        r (artifact/graph-traverse :root [:a] (resolver g))]
    (is (= [{:path [:root :a :a]}] (:cycles r)))))

;; ---------------------------------------------------------------------------
;; 缺失节点
;; ---------------------------------------------------------------------------

(deftest a-missing-node-carries-the-path-that-reached-it
  "缺失节点带上到达它的完整路径——调用方（studio）拿这条路径点名 finding。"
  (let [g {:a [:b]} ;; :b 不在图里
        r (artifact/graph-traverse :root [:a] (resolver g))]
    (is (= [{:node :b :path [:root :a :b]}] (:missing r)))
    (is (= [:a] (:order r)) "只有真正解析到的节点进 :order")))

;; ---------------------------------------------------------------------------
;; 深链
;; ---------------------------------------------------------------------------

(deftest a-deep-chain-is-fully-enumerated
  (let [g (into {} (for [i (range 10)] [i [(inc i)]]))
        g (assoc g 10 [])
        r (artifact/graph-traverse :root [0] (resolver g))]
    (is (= (set (range 11)) (set (:order r))) "全部 11 个节点都可达")
    (is (= [(vec (range 0 11))] (get (:paths r) 10))
        "唯一路径就是整条链，从第一个真实节点到 10 自己")))

;; ---------------------------------------------------------------------------
;; 确定性
;; ---------------------------------------------------------------------------

(deftest same-input-twice-is-byte-identical
  "同一输入两次调用，输出逐字节相同——下游 content-hash 靠这个。"
  (let [g {:a [:c :b] :b [:d] :c [:d] :d []}
        r1 (artifact/graph-traverse :root [:a] (resolver g))
        r2 (artifact/graph-traverse :root [:a] (resolver g))]
    (is (= r1 r2))
    (is (= [:a :b :c :d] (:order r1)) "顺序按 pr-str 排定，与输入枚举顺序无关")))

(deftest sibling-order-does-not-affect-the-result
  "子节点用什么顺序返回，最终 :order/:paths 都会被排到同一处——遍历顺序不是
   语义的一部分。"
  (let [r1 (artifact/graph-traverse :root [:a] (resolver {:a [:x :y] :x [] :y []}))
        r2 (artifact/graph-traverse :root [:a] (resolver {:a [:y :x] :x [] :y []}))]
    (is (= r1 r2))))

;; ---------------------------------------------------------------------------
;; 对 `resolve` 的调用契约 —— studio 的 R3.8 直接依赖这两条
;; ---------------------------------------------------------------------------
;;
;; 这两条不是「实现碰巧如此」，是**承诺**（见 impl.graph/traverse 的 ns 文档）。
;; studio 的档位传播按「每次访问」记账：父节点在自己的 resolve 里为子节点推一个
;; 值，子节点被访问时弹一个。记忆化会让推弹失衡、队列见底；父子顺序反过来会让
;; 弹的时候还没推。红了不要改这里，去看 closure.clj 的 ns 注释。

(deftest resolve-is-called-once-per-visit-not-once-per-node
  "菱形：`d` 经两条路径可达，就必须被 resolve **两次**。**不许记忆化**——
   那是这类遍历最自然的优化，也正是会悄悄弄坏调用方记账的那一个。"
  (let [calls (atom [])
        g     {:a [:b :c] :b [:d] :c [:d] :d []}
        r     (artifact/graph-traverse
               :root [:a]
               (fn [k path] (swap! calls conj [k (vec path)]) (get g k)))]
    (is (= 2 (count (filter #(= :d (first %)) @calls)))
        "d 经 a→b→d 与 a→c→d 两条路径可达，resolve 就该被调用两次")
    (is (= #{[:root :a :b :d] [:root :a :c :d]}
           (set (map second (filter #(= :d (first %)) @calls))))
        "两次调用各自带着自己那条路径")
    (is (= 2 (count (get (:paths r) :d))) "两条路径都进了结果")))

(deftest a-child-is-visited-only-after-its-parent-resolve-returned
  "父节点的 `resolve` 必须**先返回**，子节点才被访问——调用方在父节点的
   resolve 里为子节点备的东西，子节点用得上。"
  (let [events (atom [])
        g      {:a [:b] :b [:c] :c []}
        _      (artifact/graph-traverse
                :root [:a]
                (fn [k _path]
                  (swap! events conj [:enter k])
                  (let [children (get g k)]
                    (swap! events conj [:return k])
                    children)))]
    (is (= [[:enter :a] [:return :a]
            [:enter :b] [:return :b]
            [:enter :c] [:return :c]]
           @events)
        "每个节点都是 enter→return 成对闭合之后，才轮到它的子节点 enter")))
