(ns open.atlas.common.engine.impl.model
  "归一模型上的纯查询函数：档位表、output 语法树的行走、组展开表、join 属性表。

   全部是模型的纯函数——不认识 pathom、不认识 token。编译（impl.compile）与
   执法（impl.gate）都从这里取事实，避免两处各自从模型里再挖一遍而漂移
  （参考实现把 catalog 从 pathom index 反读回来，同一条动机）。"
  (:require [open.atlas.common.dialect :as dialect]))

;; --- order_by aliases -------------------------------------------------------

(defn with-order-aliases
  "Attach `:aliases` to a resolver's `:order`: every spelling a caller may use
   for an allowed sort attribute besides the keyword itself -- the attribute
   as a \"ns/name\" string, its local name, and its mapped result column.
   Legacy chart graphs documented `order_by` as a \"mapped result column\", and
   agents written against them still send one (owner 2026-09-29, pastdue
   upgrade). A spelling that fits two allowed attributes is dropped: the call
   is then refused instead of sorted by a guess."
  [order field-mapping]
  (if-not (seq (:allowed order))
    order
    (let [fm (into {} (keep (fn [[k v]] (when (string? v) [(keyword k) v]))) field-mapping)
          pairs (for [a (:allowed order)
                      alias (distinct (remove nil? [(subs (str a) 1) (name a) (get fm a)]))]
                  [alias a])]
      (assoc order :aliases
             (into {} (keep (fn [[alias ps]]
                              (let [targets (distinct (map second ps))]
                                (when (= 1 (count targets)) [alias (first targets)]))))
                   (group-by first pairs))))))

(defn resolve-order-by
  "A caller's `order_by` → the allowed attribute it names, or the value itself
   (which the gate then refuses) when it names none."
  [order v]
  (cond
    (contains? (set (:allowed order)) v) v
    (keyword? v) (get (:aliases order) (subs (str v) 1) v)
    (string? v) (get (:aliases order) v v)
    :else v))

;; --- 属性与 entity ---------------------------------------------------------

(defn entity-of
  "属性 → entity。**entity 由 namespace 推导**（principles「模型与方言」）：
   一个 entity 就是属性共享的 namespace，不从 resolver 输入推断。"
  [attr]
  (namespace attr))

(defn identity-attrs
  "标了 `:identity? true` 的属性——identity 只认声明，不推断（principles）。"
  [model]
  (into #{} (keep (fn [[a d]] (when (:identity? d) a))) (:attributes model)))

;; --- output 语法树（D2.16/D2.29：叶是标量属性，分支是 join 属性）------------

(defn output-leaves
  "语法树 → 全部**叶**属性（不含分支的 key）。

   D2.29 之后分支键同样是登记属性，所以这个函数的名字比过去更该照字面读：
   它给的是「有值可取的那些键」，不是「所有属性」。要分支键用 `output-joins`。"
  [tree]
  (letfn [(step [node]
            (cond
              (keyword? node) [node]
              (map? node)     (mapcat step (vals node))
              (vector? node)  (mapcat step node)
              :else           []))]
    (into #{} (step tree))))

(defn output-joins
  "语法树 → 全部分支（join 属性）的 key。"
  [tree]
  (letfn [(step [node]
            (cond
              ;; Semantic contract 2.0 §6.5: a union branch's value is a map of
              ;; identity attribute → subtree. Those keys discriminate, they
              ;; are not joins; only the subtrees are walked.
              (map? node)    (mapcat (fn [[k v]] (cons k (if (map? v) (mapcat step (vals v)) (step v)))) node)
              (vector? node) (mapcat step node)
              :else          []))]
    (into #{} (step tree))))

(defn output-unions
  "语法树 → 全部 union 分支（2.0 §6.5：值是 `{identity-kw 子树}` 的 map）的 key。"
  [tree]
  (letfn [(step [node]
            (cond
              (map? node)    (mapcat (fn [[k v]]
                                       (cond (map? v) (cons k (mapcat step (vals v)))
                                             (vector? v) (step v)
                                             :else []))
                                     node)
              (vector? node) (mapcat step node)
              :else          []))]
    (into #{} (step tree))))

(defn recursion-depths
  "语法树 → `{join-kw 深度}`：每个递归分支（2.0 §6.5：值是整数）声明的深度上限；
   同一键声明两次取较小者。"
  [tree]
  (letfn [(step [node]
            (cond
              (map? node)    (apply merge-with min {}
                                    (map (fn [[k v]]
                                           (cond (integer? v) {k v}
                                                 (map? v) (apply merge-with min {} (map step (vals v)))
                                                 :else (step v)))
                                         node))
              (vector? node) (apply merge-with min {} (map step node))
              :else          {}))]
    (step tree)))

(defn join-root
  "语法树的根若恰是**一个** join 分支且无同层叶属性，返回该 key，否则 nil。
   `:result_type \"list\"` 的 resolver 只能是这一形（D2.8 有锚 + D2.16）：
   行装在这个 key 下面。参考实现在这一点上留了血泪注释——根是字符串键时
   pathom 填不进去。"
  [tree]
  (when (and (vector? tree) (= 1 (count tree)) (map? (first tree)))
    (let [m (first tree)]
      (when (= 1 (count m)) (key (first m))))))

(defn prune-output
  "按 `keep?` 裁剪语法树：不留的叶去掉；分支的孩子全没了则该分支也去掉。
   返回裁剪后的树（可能为空向量）。D5.3 的档位物化用它——见 impl.compile
   对「整条 resolver 出局 vs 只裁 output」的裁量说明。"
  [tree keep?]
  (letfn [(step [node]
            (cond
              (keyword? node) (when (keep? node) node)
              ;; A recursion depth (2.0 §6.5) is not prunable: it names no
              ;; attribute of its own and stays with its key.
              (integer? node) node
              (map? node)     (let [kept (into {} (keep (fn [[k v]]
                                                          (let [v' (step v)]
                                                            (when (or (integer? v') (seq v')) [k v'])))
                                                        node))]
                                (when (seq kept) kept))
              (vector? node)  (vec (keep step node))
              :else           nil))]
    (or (step tree) [])))

(defn joins
  "模型 → {join 属性 kw → 目标 namespace}。

   **不在这里重新推导**（D2.29）：判别式住在 dialect 的公共接口上，engine 只是
   转发。曾经这个函数要从 `:namespaces` 的 `:navigations` 里挖，那是第二个
   注册表还在的年代；现在 join 属性与标量属性同住 `:attributes`，
   「哪些是 join」只有一个答案，而它不归 engine 定义。"
  [model]
  (dialect/join-attributes model))
