(ns open.atlas.common.artifact.impl.graph
  "**D1.12**：引用图的可达性属于 `common`，两侧各自的判断留在各自那边。

   一张 graph 版本引用别的 graph 版本，引用钉版本，`(graph, 版本)` 上是一张
   DAG。studio 切版本时要走这张图（R3.4 的钻石冲突、R3.8 的档位取最严），
   server 装载时也要走（R3.3：传递引用的每个版本都得在场）。两侧对「从这个
   版本出发能到达哪些版本、有没有环、缺了谁、经由哪条路径」必须答得一样——
   所以可达性、环检测、缺失节点点名、路径枚举住在这里，纯函数，只吃一个
   `resolve` 解析函数，不认识制品架、不认识池、不碰 IO。

   **不在这里**：档位取严（R3.8）、钻石冲突判定（R3.4，studio 的建模期判断）、
   文件/hash 是否在场（server 的装载期判断）。这里只回答图长什么样。"
  )

(defn traverse
  "`root`：虚拟根节点的 key，只作为路径首段出现，不进 `:order`/`:paths`——
   一张图不引用自己（D1.12）。

   `root-children`：root 的直接子节点 key 列表。

   `resolve`：`(fn [node-key path]) => 子节点 key 列表，或 nil`。`nil` 表示
   `node-key` 本身在调用方那里找不到（缺失节点）；空 vector 表示找到了、只是
   没有子引用。`path` 是从 root（含）到 `node-key`（含）的完整路径——调用方
   在自己的 resolver 里用它拼自己的 finding（比如 R3.1 的 unpinned-version
   要点名「经由哪条路径到达这条坏边」），本函数不解释 `path` 的内容，只负责
   把它维护对。

   node-key 只要求：可以放进集合当元素（用于成环检测的成员判断），可以喂给
   `pr-str` 得到一个稳定的排序键。本函数不认识 key 的内部结构。

   => `{:order   [node-key…]                       ; 全部可达节点，确定性排序
        :paths   {node-key [[node-key…]…]}          ; 到达每个节点的全部路径
                                                     ;  （已排序去重，不含 root）
        :cycles  [{:path [node-key…]}…]              ; 每个环一条，path 含闭合边、含 root
        :missing [{:node node-key :path [node-key…]}…]} ; path 含缺失节点自身、含 root

   **确定性是承重的**：`:order`、每个节点的 `:paths`、`:cycles`、`:missing`
   全部按 `pr-str` 排序（`:cycles`/`:missing` 额外去重）——同一输入两次调用，
   逐字节相同的输出，这是下游 content-hash 与调用方测试都依赖的性质。

   环检测：一条路径回到自己已经走过的祖先就是环，拒绝但不截断——截断会让
   闭包少一段而没人知道，判断只看当前这一条路径的祖先链，与遍历子节点的
   先后顺序无关。

   ── 对 `resolve` 的两条调用契约（**调用方会依赖，不要优化掉**）──────────────

   1. **每次访问调用一次，按路径不按节点。** 一个节点经 n 条路径可达就被
      `resolve` n 次。**不做「已访问过就跳过」的记忆化**——那是遍历这类代码最
      自然的优化（菱形的公共子树会被重复走），但调用方可以在自己的 resolver 里
      按「每次访问」记账：studio 的 R3.8 档位传播就是这么算的（见
      `open.atlas.studio.version.impl.closure` 的 ns 注释），去重会让它的推与弹
      失衡、队列见底。要加记忆化，先去改那边。
   2. **一个节点只在其父节点的 `resolve` 返回之后才被访问。** 父节点在自己的
      `resolve` 里为子节点准备的东西，子节点被访问时一定已经就绪。

   这两条**有测试守着**（`graph_test.clj` 的 `resolve-is-called-once-per-visit-not-once-per-node`
   与 `a-child-is-visited-only-after-its-parent-resolve-returned`）。契约之外的
   东西**不要依赖**：子节点的访问顺序、DFS 还是 BFS、以及「第 k 次访问对应哪条
   路径」都不在承诺内。"
  [root root-children resolve]
  (let [acc  (atom {:nodes {} :cycles [] :missing []})
        walk (fn walk [path k]
               (let [p (conj path k)]
                 (cond
                   (some #{k} path)
                   (swap! acc update :cycles conj {:path p})

                   :else
                   (let [children (resolve k p)]
                     (if (nil? children)
                       (swap! acc update :missing conj {:node k :path p})
                       (do
                         (swap! acc update-in [:nodes k :paths]
                                (fnil conj []) (vec (rest p)))
                         (doseq [c children] (walk p c))))))))]
    (doseq [c root-children] (walk [root] c))
    (let [{:keys [nodes cycles missing]} @acc]
      {:order   (vec (sort-by pr-str (keys nodes)))
       :paths   (into {} (for [[k n] nodes]
                            [k (vec (sort-by pr-str (distinct (:paths n))))]))
       :cycles  (vec (sort-by pr-str (distinct cycles)))
       :missing (vec (sort-by pr-str (distinct missing)))})))
