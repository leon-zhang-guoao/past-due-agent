(ns open.atlas.studio.graph.impl.sync
  "**sync 按 graph 单飞**（[R3.7](../../../../../../../../docs/design/30-workspace-graph.md#32-版本与引用规则)，
   B5a 退出条件 3）。

   > 同一张 graph 的并发 sync 请求合并为一次执行，后到者等待同一结果；不同 graph
   > 各自独立分道，互不排队。

   ## 为什么不做全局串行（R3.7 原话）

   一个域的大图重建会阻塞所有人的小改动，而它们之间**没有任何共享可变状态需要
   保护**——sync 是「从池快照物化某 graph 的 env」（D3.7），两张图的 env 各自独立。
   全局锁换来的不是正确性，只是别人的等待。

   ## 没有 `:superseded`，没有取消路径（R3.7 原话）

   合流沿用「同时只有一个候选在建」的既有语义：后到者**等同一个结果**，而不是
   取消在建的那个再重来。于是这里既没有「被取代」的状态要表达，也没有取消路径要
   设计和测试——**这句话写在这里，是因为它是一条不许被自作主张补上的缺席**。

   ## 时机语义

   合流窗口 = 「这一次执行还没结束」。**执行一结束闸就放开**：此后到达的请求会
   跑一次新的执行，而不是拿到一份已经过期的结果。sync 的全部意义是「取此刻的池
   快照」（D3.2 的运行态隔离），把结果缓存给后来者就是把那个意义抹掉。

   ## 建 env 的那件事不在这里

   本 ns 交付的是**合流语义**，实际的物化动作由调用方以 `build` 传进来：物化要走
   engine（`open.atlas.common.engine`），而那是投影产物的下游，跨模块的装配点不在
   本模块（D1.8）。合流与物化分开，也让「只执行了一次」这件事**可测**——测试传一个
   数着自己被调了几次的 `build` 就行。"
  (:require [open.atlas.studio.graph.impl.finding :as f]))

(defn single-flight
  "一道单飞闸。**按 graph 限定名分道**：`{限定名 → 在建的那一次执行}`。

   状态是显式的值而不是本 ns 的全局 atom——一个进程里可以有两个互不相干的 studio
   实例（测试就是），全局状态会让它们莫名其妙地互相合流。"
  []
  (atom {}))

(defn in-flight
  "此刻在建的 graph 限定名集合。给报表与测试看的，**不是同步原语**：
   读到的一瞬间它就可能变了。"
  [flight]
  (set (keys @flight)))

(defn sync!
  "对 `qn` 跑一次 sync：`build` 是 0 参函数，返回这次 sync 的结果。

   同一 `qn` 上并发调用**只执行一次** `build`，全部调用方拿到**同一个**返回值
   （同一个对象，不是相等的两个）；不同 `qn` 互不排队。

   `build` 抛异常时异常传给全部合流的调用方，且闸**照样放开**——一次失败不该把
   这张图的 sync 永久钉死在一个坏结果上。"
  [flight qn build]
  (let [d   (delay (build))
        ;; `swap!` 可能重试，于是可能造出几个 delay；**只有落进 map 的那个会被
        ;; deref**，其余的连一次 `build` 都不会跑（delay 不 deref 不执行）。
        ;; 这正是用 delay 而不是 future 的理由：future 一造出来就开跑。
        cur (get (swap! flight #(if (contains? % qn) % (assoc % qn d))) qn)]
    (try
      @cur
      (finally
        ;; 只摘掉**自己这一次**：`identical?` 挡的是「我在收尾时，闸上已经是
        ;; 别人开的新一轮」——那种情况下 dissoc 会把别人的合流窗口提前打断，
        ;; 于是同一时刻出现两次执行，而这正是本 ns 要消灭的东西。
        (swap! flight #(if (identical? (get % qn) cur) (dissoc % qn) %))))))

(defn unknown-graph
  "sync 一张没登记过的 graph 的拒绝。结构化，且点名的是限定名（D3.10：裸名不进
   任何持久化标识，所以拒绝里出现的也是限定名）。"
  [qn]
  (f/make :graph.registry/unknown :error [:graph]
          "no such graph is registered; sync materialises a registered recipe"
          :got (str qn) :refs ["R3.7" "D3.10"]))
