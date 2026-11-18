(ns open.atlas.common.connector.spi.impl.batch
  "batch 对齐——本模块最要紧的一段纯函数。

   principles「执行与连接」：
     「批量是显式声明，对齐永不丢位。落到没有 `compile-batch-source` 的连接器上
      是配置错误，不是静默退回逐行……对齐先按 `:source-field` 分组再做字段映射，
      然后每个输入发一条：没有行的父项拿 `[]` 或 `nil`，绝不丢掉一个位置，
      丢一位会把后面每个结果静默移到错误的父项上。」
   sql-v1 batch 块把那个 `:source-field` 具名为 `:child-fk`。

   实现纪律：输出向量与 `inputs` **等长且同序**，永远。没有一条分支能改变长度。"
  (:require [clojure.string :as str]
            [open.atlas.common.connector.spi.impl.mapping :as mapping]))

(defn join-key
  "分组用的连接键归一。

   父项的键值来自上游（EQL 参数、上一层结果），子行的 fk 来自 JDBC；同一个
   业务键在两边可能是 Long 与 \"5001\"。不归一就会整批落空——而「落空」在本
   契约里是合法结果（空位），于是这个 bug 静默。只做一件事：整数形状的字符串
   与整数收敛到同一个 long。其余原样（不猜小数、不猜日期——猜错比不猜更糟）。"
  [v]
  (cond
    (nil? v)                       nil
    (integer? v)                   (long v)
    (and (string? v)
         (re-matches #"-?\d{1,18}" v)) (Long/parseLong v)
    :else                          v))

(defn as-vec
  "batch 的 `:parent-key` / `:child-fk`：单个即单键，向量即复合键
   （owner 2026-09-07，doc 106 §106.14 第 14 条）。两端逐位配对。"
  [x]
  (cond (nil? x)        []
        (sequential? x) (vec x)
        :else           [x]))

(defn- row-fk
  "一行的连接键：单键是归一后的值，复合键是**逐位归一后的向量**——两边同样
   构造，所以 Long 与 \"5001\" 在复合键里也能对上。"
  [row child-fks]
  (let [vs (mapv #(join-key (get row (keyword (str/lower-case (name %))))) child-fks)]
    (if (= 1 (count vs)) (first vs) vs)))

(defn group-rows-by-fk
  "扁平行 → `{归一后的 fk → [row …]}`。桶内保持 rows 的原始顺序
   （source 的 ORDER BY 是唯一的顺序权威，分组不得重排）。"
  [rows child-fk]
  (let [ks (as-vec child-fk)]
    (group-by #(row-fk % ks) rows)))

(defn window
  "per-parent 切窗：在**一个父项的桶内**取 `[offset, offset+limit)`。

   为什么是切片而不是排序：声明的 `:order` 已由连接器作为**全局** ORDER BY
   附加到批量语句上，而 `group-rows-by-fk` 保持桶内原始顺序——于是每个桶
   天然就是声明的顺序。窗口函数（`ROW_NUMBER() OVER (PARTITION BY …)`）
   要解决的「按父排序」这一半，在这条路径上根本不存在，只剩「按父取前 N 条」。

   `nil` 的 limit/offset = 不切。负数与 0 按缺席处理（写入端已夹过一次，
   这里不再第二次判定语义，只保证不产出负长度的切片）。"
  [bucket {:keys [limit offset]}]
  (let [off (if (and offset (pos? (long offset))) (long offset) 0)
        b   (if (pos? off) (vec (drop off bucket)) (vec bucket))]
    (if (and limit (pos? (long limit)))
      (vec (take (long limit) b))
      b)))

(defn align
  "把一次批量取数的扁平行对齐回 `inputs`。

   参数：
     rows          —— 一条批量语句取回的全部行（顺序 = source 给的顺序）
     inputs        —— 父项参数 map 的向量，`[{:ns/attr v} …]`
     batch         —— `{:parent-key 属性kw :child-fk 列名}`（sql-v1 batch 块）；
                        两者都可以是向量 = 复合键，逐位配对（§106.14 第 14 条）
     opts          —— `{:result-type \"single\"|\"list\"
                        :field-mapping {属性kw → 列名}
                        :window {:limit n :offset k}   ; per-parent，缺席 = 不切
                        :ctx {:resolver-id … :source-type …}}`

   返回与 `inputs` 等长同序的向量：
     \"list\"   第 i 位 = 第 i 个父项的行（映射后），没有行就是 `[]`
     \"single\" 第 i 位 = 第一行（映射后），没有行就是 `nil`
   `nil` 的意思是「这个 resolver 供不出这些属性」，不是错误（principles）。

   顺序：**先分组、再切窗、后映射**。分组只看 source 的列名，field mapping 的
   死活不影响对齐；乱序、重复父键、空位都被分组吸收。切窗在映射之前——被窗口
   切掉的行不属于结果，没有理由为它们付映射的代价，也没有理由让它们的映射问题
   冒充成本次调用的故障。

   **切窗不改变长度**：窗口只让某个桶变短（可以短到空），永远不会让一个父项
   失去它的位置。`inputs` 有几项，返回就有几项——这条不变量高于一切
   （principles：丢一位会把后面每个结果静默移到错误的父项上）。"
  ;; `:window` 解构成 `win`：同名局部会遮蔽上面的 `window` 函数。
  [rows inputs {:keys [parent-key child-fk]}
   {:keys [result-type field-mapping ctx] win :window}]
  (let [by-fk (group-rows-by-fk (or rows []) child-fk)
        pks   (as-vec parent-key)
        ;; 复合键两端逐位配对：第 i 个父键属性对第 i 个子列。
        key-of (fn [input]
                 (let [vs (mapv #(join-key (get input %)) pks)]
                   (if (= 1 (count vs)) (first vs) vs)))
        single? (not= "list" result-type)]
    (mapv (fn [input]
            (let [bucket (get by-fk (key-of input) [])]
              (if single?
                ;; 没有行 → nil；有行 → 第一行。绝不给 {}。
                ;; "single" 不切窗：它按定义只取一行，窗口无从表达。
                (when-let [row (first bucket)]
                  (mapping/apply-field-mapping field-mapping row ctx))
                ;; 空就是 []，不是 nil，也不是「跳过这一位」。
                (mapv #(mapping/apply-field-mapping field-mapping % ctx)
                      (window bucket win)))))
          (vec inputs))))
