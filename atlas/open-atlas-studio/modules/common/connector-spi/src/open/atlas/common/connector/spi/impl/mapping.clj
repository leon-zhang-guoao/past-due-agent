(ns open.atlas.common.connector.spi.impl.mapping
  "field mapping 与 :output 语法树的纯函数。

   principles「执行与连接」：「field mapping 方向永远是 `{属性 -> source 路径}`。」
   D2.1（dialect-v1 §3 source 操作块表）：「`:field_mapping` `{属性kw → 列名}`；
   恒等映射省略」——所以**解析**时要把省略的补回来：属性 local name 即 source 路径。"
  (:require [clojure.string :as str]
            [open.atlas.common.connector.spi.impl.failure :as failure]))

(defn output-attributes
  "从 resolver 的 `:output` 语法树（D2.16）取出全部**叶子属性 kw**。
   两种形状：扁平 `:ns/attr`，与 join `{:ns/parent [:ns/child …]}`；
   join 的父键是一条 **join 属性**（D2.29），不是 source 列，不参与 field mapping。

   **任意深度**：分支下面还可以有分支——106.14 第 5 条的「属于」join
   `{B/as [A/… {A/b [B/k]}]}` 就是一层套一层，而 `B/k` 同样是要从行里取的列。
   只走一层会把内层的那个 map 当成叶子交给 `resolve-field-mapping`，那里
   `(name …)` 一个 map 会抛——连接器整条编译不出来，engine 只报一句
   `source-not-executable`。"
  [output]
  (into []
        (distinct)
        ((fn step [node]
           (cond
             (keyword? node)   [node]
             (map? node)       (mapcat step (vals node))
             (sequential? node) (mapcat step node)
             :else             nil))
         output)))

(defn join-parent
  "join 形 `:output` 的父键（`{:cis.master.vendor/parts [...]}` → 该 kw）；
   扁平 output 给 nil。结果整形归 engine，SPI 只把这个事实算出来备用。"
  [output]
  (when (sequential? output)
    (some #(when (map? %) (first (keys %))) output)))

(defn branch-scoped
  "The declared entries whose value is itself a map: a BRANCH-SCOPED mapping
   (doc 106 §106.14 item 6, owner 2026-09-07).

   `{A/parent {A/k \"parent_id\"}}` says «inside the `A/parent` branch, `A/k`
   is read from `parent_id`» — which is the only way a SELF relation can be
   said at all: parent and child share a namespace, so the branch's leaf and
   the resolver's own identity output are the SAME attribute kw, and one flat
   row cannot hold two values for one key. Giving the branch its own scope
   keeps the top-level identity mapping untouched."
  [declared]
  (into {} (filter (comp map? val)) (or declared {})))

(defn resolve-field-mapping
  "`{属性kw → 列名}`，恒等映射按 D2.1 补齐。

   `declared` 是操作块里写出来的那部分（可以是 nil / 部分）；`attrs` 是这个
   操作块要供出的属性（一般来自 `output-attributes`）。缺席的属性按
   「属性 local name = 列名」补。列名统一成字符串——方言里写成什么都行，
   到这里只有一种形状。

   **分支作用域**（§106.14 第 6 条）：`declared` 里值是 map 的条目原样带过，
   键是 join 属性、值是该分支内的 `{属性kw → 列名}`。它不参与顶层补齐——
   分支里的叶子在顶层可能是**另一个**值（自关系时正是同一个 kw）。"
  [declared attrs]
  (let [scoped (branch-scoped declared)]
    (into (reduce (fn [acc attr]
                    (let [c (get declared attr)]
                      (if (map? c)
                        acc
                        (assoc acc attr (if c (name c) (name attr))))))
                  {}
                  attrs)
          (for [[k m] scoped]
            [k (into {} (for [[a c] m] [a (name c)]))]))))

(defn- row-key
  "JDBC 行的键。结果集统一用 unqualified-lower-maps 取，所以列名小写成 kw。"
  [col]
  (keyword (str/lower-case col)))

(defn apply-field-mapping
  "一行 source 数据 → `{属性kw → 值}`。

   严格：映射到的列若**根本不在行里**（查询没 SELECT 它、列名拼错），这是
   `:mapping` 故障，不是「这个属性没值」。默默丢掉它就会产出契约禁止的
   「半填的 map」（principles：「没有哪个 source type 拿 `{}` `[]` 或半填的
   map 顶替缺失的行」）。
   列**在**但值是 SQL NULL 时保留 `nil`——那是 source 的真实答案。

   分支作用域的条目（§106.14 第 6 条）答一个**已经成形**的 map，engine 的
   `nest-row` 对已成形的分支不再动手。"
  [field-mapping row ctx]
  (letfn [(cell [attr col]
            (let [k (row-key col)]
              (when-not (contains? row k)
                (failure/fail! :mapping ctx
                               (str "column for " attr " absent from result set")))
              (get row k)))]
    (reduce-kv (fn [acc attr col]
                 (assoc acc attr
                        (if (map? col)
                          (let [vs (reduce-kv (fn [a a* c] (assoc a a* (cell a* c))) {} col)]
                            (when (some some? (vals vs)) vs))
                          (cell attr col))))
               {}
               field-mapping)))
