(ns open.atlas.common.dialect.impl.write
  "Canonical writer（实现顺序第 2 步——先于 reader，往返判据在这边）。
   规范形 = 唯一形（D2.12/D4.3）：
   - map 键字典序、set 字典序、向量保持书写顺序
   - keyword 全限定不缩写（不产 #:ns{}）
   - 缺省值不书写（reader 保证模型里根本没有，writer 只断言）
   - UTF-8 无 BOM、\\n、文件尾单个换行

   自己写打印器而不用 pprint/fipp：规范形要求逐字节确定，
   任何带宽度启发式的 pretty printer 都不满足。

   布局（dialect-v1 §4「布局」）：
   - 唯一常数是 100 列；除它以外没有任何宽度启发式；
   - 集合先试单行（emit），起始列 + 单行宽度 ≤ 100 列即单行；
   - 放不下时：map 一键一行，向量/set 元素间单空格、在元素边界换行，
     续行一律缩进对齐到开括号后一列；
   - 叶子值（字符串/数字/keyword/布尔/nil）永不折行，故超长 SQL 字符串
     所在行可以突破 100 列——这是规则的结论，不是例外。
   同一模型任何时候输出逐字节相同：布局只依赖「起始列 + 单行宽度」两个整数。"
  (:require [clojure.string :as str]
            [open.atlas.common.dialect.impl.vocab :as vocab]))

(def ^:const max-width
  "规范形唯一的宽度常数（dialect-v1 §4）。"
  100)

(defn- kw-str
  "keyword → 字面量，永远全限定形，不走 *print-namespace-maps*。"
  [k]
  (str k))

(defn- edn-key-sort
  "键排序的全序：按打印形字典序（keyword/字符串/整数混合键场景一致）。"
  [ks]
  (sort-by pr-str ks))

(defn emit
  "任意规范化模型值 → 确定性 EDN 字符串（单行核心；布局在 write-dialect）。"
  [v]
  (cond
    (keyword? v) (kw-str v)
    (map? v)     (str "{" (str/join " " (for [k (edn-key-sort (keys v))]
                                          (str (emit k) " " (emit (get v k)))))
                      "}")
    (set? v)     (str "#{" (str/join " " (map emit (edn-key-sort v))) "}")
    (vector? v)  (str "[" (str/join " " (map emit v)) "]")
    (or (string? v) (number? v) (boolean? v) (nil? v)) (pr-str v)
    :else (throw (ex-info "type not in dialect value domain"
                          {:type (type v)}))))

(defn- coll-like?
  "只有这三种集合参与布局；其余一律叶子，永不折行。"
  [v]
  (or (map? v) (set? v) (vector? v)))

(defn- spaces [n] (apply str (repeat n \space)))

(declare layout)

(defn- layout-map
  "map 展开形：一键一行，续行对齐到 `{` 后一列。"
  [m col]
  (let [indent (inc col)
        lines  (for [k (edn-key-sort (keys m))
                     :let [ks (emit k)]]
                 (str ks " " (layout (get m k) (+ indent (count ks) 1))))]
    (str "{" (str/join (str "\n" (spaces indent)) lines) "}")))

(defn- layout-seq
  "向量/set 展开形：元素间单空格，超 100 列在元素边界换行，
   续行对齐到开括号后一列。元素自身放不下时就地展开。"
  [items open-str col]
  (let [indent (+ col (count open-str))]
    (loop [[x & more :as items] items
           cur indent
           out (transient [open-str])]
      (if (empty? items)
        (str (apply str (persistent! out)) (if (= open-str "#{") "}" "]"))
        (let [inline (emit x)
              start? (= cur indent)
              fits?  (<= (+ cur (if start? 0 1) (count inline)) max-width)]
          (cond
            fits? (recur more (+ cur (if start? 0 1) (count inline))
                        (conj! out (if start? inline (str " " inline))))
            ;; 行内放不下：先换行，再看新行首能否容下
            (not start?)
            (recur items indent (conj! out (str "\n" (spaces indent))))
            ;; 已在行首仍放不下：集合就地展开，叶子照写（叶子不折行）
            :else
            (let [s (layout x indent)]
              (recur more (+ indent (count (last (str/split-lines s))))
                     (conj! out s)))))))))

(defn- layout
  "值 → 从第 col 列开始书写的字符串（首行不含缩进，续行自带缩进）。"
  [v col]
  (let [inline (emit v)]
    (if (or (not (coll-like? v))
            (<= (+ col (count inline)) max-width)
            (empty? v))
      inline
      (cond
        (map? v)    (layout-map v col)
        (vector? v) (layout-seq v "[" col)
        (set? v)    (layout-seq (edn-key-sort v) "#{" col)))))

(defn- walk-defaults!
  "沿 vocab/defaults 逐位置检查：模型里凡出现「键 = 缺省值」即断言失败。
   dialect-v1 §4：`:curated? false`、`:direction :bi`、
   `:required? false` 均以缺席为唯一规范形（reader 见到书写即拒绝，writer 兜底）。"
  [v path]
  (cond
    (map? v)
    (do (doseq [[k val] v]
          (when (and (contains? vocab/defaults k)
                     (= (get vocab/defaults k) val))
            (throw (AssertionError.
                    (str "default value written at " (pr-str (conj path k))
                         ": " (pr-str k) " " (pr-str val)
                         " —— 缺省值不书写（dialect-v1 §4）"))))
          (walk-defaults! val (conj path k)))
        v)

    (vector? v)
    (do (dorun (map-indexed #(walk-defaults! %2 (conj path %1)) v)) v)

    (set? v)
    (do (doseq [x v] (walk-defaults! x path)) v)

    :else v))

(defn- assert-no-defaults!
  "写入端断言：模型里不得残留缺省值书写（reader 的责任，这里兜底）。"
  [model]
  (walk-defaults! model []))

(defn write-dialect
  "内部模型 → 规范形字符串。文件尾单个 \\n。"
  [model]
  (str (layout (assert-no-defaults! model) 0) "\n"))
