(ns open.atlas.runtime.env.impl.closure
  "One root version on this machine's shelf → one merged, compiled env (R3.2).

   ```
   读根制品 → 遍历引用闭包 → 逐成员读模型
            → 确定性合并 → 编译一次
   ```

   ## 为什么必须合并了再编译，而不是各编各的再合并

   R3.2 说组合是 **index 合并**，不是运行时调用。逐个引用制品各编一份 compiled
   env 再拼起来，等于把一次解析可能跨越两张图的路径切成两段——planner 就再也
   走不通「从 a 的锚跳到 b 的属性」那条路，而那正是引用存在的理由。
   合并发生在**模型**这一层，编译只跑一次（owner 裁定 2026-08-25）。

   ## 缓存为什么不需要「指针变更失效」

   缓存键是 `[限定名 具体版本]`，而版本是不可变的（D4.1/D3.6）。一次部署或回滚
   改的是 **`:latest` 解析到哪一版**，不是任何一版的内容——所以指针一动，
   下一次请求自然算出另一个键，旧条目既没错也不必清。要清只有两个理由：
   腾内存，或那一版被回收了。"
  (:require [open.atlas.common.artifact :as artifact]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.runtime.env.impl.semantic :as semantic]
            [open.atlas.runtime.env.impl.finding :as f]))

(defn- node-key [graph version] [graph version])

(defn- read-member
  "一个闭包成员 → `{:model …}` 或 `{:findings […]}`。

   **digest 显式给出**（artifact-v1 §11 第 8 条）：期望值取自本机的 **receipt**
   ——那是「这一版当初是经哪份已认证信封装载进来的」的唯一证据，而制品目录里
   任何自报值都可以被一起替换掉。"
  [{:keys [shelf-texts receipt-digest]} graph version]
  (let [texts (shelf-texts graph version)]
    (cond
      (not (= (count artifact/artifact-files) (count texts)))
      {:findings [(f/error :env.artifact/absent [:closure]
                           "this closure member is not on the shelf of this instance"
                           :got {:graph graph :version version})]}

      (nil? (receipt-digest graph version))
      {:findings [(f/error :env.artifact/unreceipted [:closure]
                           "this version has bytes on the shelf but no receipt, so it does
                            not count as loaded and there is no trusted digest to verify
                            it against"
                           :got {:graph graph :version version})]}

      :else
      (let [{:keys [model document deployment references findings]}
            (artifact/read-artifact
             texts {:expected-digest (receipt-digest graph version)
                    :resolve-reference
                    (fn [ref] (get (shelf-texts (str (:workspace ref) "/" (:graph ref))
                                                (:version ref))
                                   "content.edn"))})]
        (if (or model document)
          (if (and document (not= {:workspace (first (clojure.string/split graph #"/"))
                                   :graph (second (clojure.string/split graph #"/")) :version version} deployment))
            {:findings [{:code :semantic.artifact/deployment :severity :error :path []}]}
            {:model model :document document :references (vec references)})
          {:findings (vec findings)})))))

(defn resolve-closure
  "root → `{:members [{:graph :version :ceiling :model}…]}` 或 `{:findings […]}`。

   成员按 `[限定名 版本]` 排序，**根在最前**：合并的输入顺序确定，产物因此确定。"
  [{:keys [shelf-texts] :as caps} graph version]
  (let [problems (atom [])
        models   (atom {})
        ;; 已经点过名的成员：读失败会让 `resolve` 返回 nil，遍历于是把它记成
        ;; `missing`——而「不在架子上」是那一堆原因里最泛的一条。有目录无 receipt
        ;; 的成员照样会被记成 missing，再补一条「不在架子上」就把真正的原因盖掉了。
        explained (atom #{})
        root     (node-key graph version)
        resolve*
        ;; **每一次访问都要弹一次、也要给每个子节点推一次**——`graph-traverse`
        ;; 的契约是「按路径调用，不按节点去重」，而档位传播正靠这个平衡
        ;; （见 `artifact.impl.ceiling`）。缓存的只有「读这份制品」这件 IO,
        ;; 推与弹一次都不能省：省掉的那一次会让某个子节点的队列见底。
        (fn [k _path]
          (let [[qn v]  k
                entry   (or (get @models k)
                            (let [r (read-member caps qn v)]
                              (if (seq (:findings r))
                                (do (swap! problems into (:findings r))
                                    (swap! explained conj k)
                                    nil)
                                (let [e {:model (:model r) :document (:document r) :references (:references r)}]
                                  (swap! models assoc k e)
                                  e))))]
            (when entry
              (vec (for [ref (:references entry)]
                     (node-key (str (:workspace ref) "/" (:graph ref)) (:version ref)))))))
        {:keys [order cycles missing]}
        (artifact/graph-traverse ::root [root] resolve*)]
    (doseq [{:keys [path]} cycles]
      (swap! problems conj
             (f/error :env.closure/cycle [:closure]
                      "the reference closure contains a cycle; (graph, version) is a DAG"
                      :got (vec (rest path)))))
    (doseq [{:keys [node]} missing
            :when (not (contains? @explained node))]
      (swap! problems conj
             (f/error :env.artifact/absent [:closure]
                      "this transitively referenced version is not on the shelf"
                      :got {:graph (first node) :version (second node)})))
    (if (seq @problems)
      {:findings (f/sort-findings (vec (distinct @problems)))}
      {:members (vec (for [k (sort-by pr-str order)
                           :let [[qn v] k]]
                       {:graph qn :version v
                        :model (:model (get @models k)) :document (:document (get @models k))}))})))

;; ---------------------------------------------------------------------------
;; 压低 → 合并 → 编译一次
;; ---------------------------------------------------------------------------

(defn- with-credentials
  "把模型里的 source 条目换成**这台 instance 真的能开的连接**。

   凭据是 instance 本地状态（[D1.13](docs/design/10-platform-boundary.md) 明文
   列了 source credentials），**不进模型**：模型里只留 `${VAR}` 名字，值住在
   这台机器自己的家里。所以「声明的 source」到「开得出的连接」之间必然有一步
   替换，而那一步**必须在这里**——也就是两种形态共用的这一条编译路径上。

   放在别处的后果是真发生过的：Builder 自己的查询路径做了这步替换，runtime
   这条没有，于是同一张图、同一个源，界面里查得到、`/eql` 与 `/mcp` 查不到,
   而 D1.13 说的正是两种形态必须表现一致。

   `resolve-source` 缺席 = 不替换（模型原样）。那是诚实的缺省：没有凭据 store
   的宿主本来就该按模型里写的去连。"
  [model resolve-source]
  (if (and resolve-source (seq (:sources model)))
    (update model :sources
            (fn [ss] (into {} (for [[id entry] ss] [id (resolve-source id entry)]))))
    model))

(defn compile-model
  "One model → `{:env compiled}`: the LAST TWO STEPS of `compile-closure`,
   named so a caller that already holds a model can take exactly this path.

   Why it is a function rather than two lines repeated at each caller
   (8.4.18 裁定十二 ①): the Builder's draft is not on any shelf, so it cannot
   enter through `for-request` — but «the draft runs» and «the cut runs» are
   one judgement only if the two are compiled by the same builder. When those
   two lines were copied into `apps/studio`, the credential substitution and
   the compile options were free to drift apart from these, and D1.13 says
   the two forms must behave alike."
  [model {:keys [limits resolve-source]}]
  ;; 不传 `:tier-cap`——它是全模型一个上限，会把根自己的属性也压下去,
  ;; 而逐成员的上限已经在 `capped` 里落过了。
  {:env (engine/compile-env (with-credentials model resolve-source)
                            (cond-> {} limits (assoc :limits limits)))})

(defn compile-closure
  "root 版本 → `{:env compiled :members […]}` 或 `{:findings […]}`。"
  [caps graph version opts]
  (let [{:keys [members findings]} (resolve-closure caps graph version)]
    (if (seq findings)
      {:findings findings}
      (if-let [document (:document (first members))]
        (if (not= 1 (count members))
          {:findings [{:code :semantic.runtime/dependency-unsupported :severity :error :path []}]}
          (let [sources (get-in document [:execution :sources])
                resolver (:resolve-source opts)
                connections (into {} (for [[id source] sources :when (not (#{"derived" "static"} (:source/kind source)))]
                                       [id (when resolver (resolver id (assoc source :semantic/workspace (get-in document [:workspace :workspace/id])
                                                                                    :semantic/graph (get-in document [:graph :graph/id])
                                                                                    :semantic/release (get-in document [:publication :release/version]))))]))
                adapters (into {} (for [kind (set (map :source/kind (vals sources)))] [kind (spi/semantic-adapter kind)]))
                candidate (semantic/compile-document document {:adapters adapters :connections connections})]
            (assoc candidate :members members)))
      (let [named (vec (for [{:keys [graph version model]} members]
                         {:file (str graph "@v" version) :model model}))
            {:keys [model findings]} (dialect/merge-closure named)]
        (if (nil? model)
          {:findings (f/sort-findings (vec findings))}
          ;; **编译只跑一次**，在合并后的那一份模型上（R3.2）——经 `compile-model`,
          ;; 也就是草稿走的那同一扇门（裁定十二 ①）。
          (assoc (compile-model model opts) :members members)))))))
