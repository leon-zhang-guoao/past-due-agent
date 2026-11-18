(ns open.atlas.studio.graph.impl.report
  "**发现面报表**（[§3.3 照镜子](../../../../../../../../docs/design/30-workspace-graph.md#33-照镜子可见性义务)，
   graph README 裁定 ⑥）。

   ## 为什么发现面必须开着（D3.1 / D3.14）

   池只做三件不出现在用户面前的事，**发现面是它不可替代的那一件**：把发现缩到
   「别人已经 share 的 graph」，一个还没切过图的 domain 就完全不可见，而 D3.14 的
   「找谁 request」也就退化成按登记表盲猜。所以本 ns 的每个函数都**不问调用方是谁**
   ——发现是开放的，授权管的是依赖，两件事（D3.14 那段「主体不同」）。

   ## 只做现在做得了的那一半（裁定 ⑥）

   | 报表 | 本 ns | 依赖 |
   |---|---|---|
   | 归属（对象归哪个 workspace） | `ownership` | 池对象的 `:pool/workspace`（D3.22） |
   | 引用反查（谁引用 `core@v3`） | `referrers` | 菜谱 |
   | 含量清单（**池内版**） | `membership-by-current-recipe` | **投影** |

   **滞后报告（B5c）与僵尸版本（B5b）不在这里**：它们问的是版本与部署的问题,
   而 B5a 既没有版本也没有部署指针。写一个「暂时总是返回空」的版本比不写更坏——
   空清单与「没有滞后」在报表上长得一模一样。

   ## 含量清单跟着投影走，**不另写一份闭包遍历**（裁定 ⑥ 原话）

   「这个属性进了哪些图」= 「对每张图跑一次投影、看它在不在结果里」。分开实现等于
   把同一份闭包规则写两遍，而两份闭包规则迟早对不上——那时报表会说「它在 A 图里」
   而 A 图实际投不出它。所以 `membership-by-current-recipe` 真的调
   `impl.projection/project`，一个字节的闭包逻辑都不在本 ns 里。

   ## 名字里必须带「按当前菜谱」

   §3.3 那张清单里的含量清单问的是「X 对象出现在哪些 **graph 版本**里」；本 ns 交付的
   是**池内版**：「按**当前菜谱**会进哪些图」。菜谱改了、还没重切时两者答案不同,
   **同名会让人读错**——所以函数名与 docstring 都把这件事说全（裁定 ⑥ 最后一段）。"
  (:require [open.atlas.studio.pool :as pool]
            [open.atlas.studio.graph.impl.finding :as f]
            [open.atlas.studio.graph.impl.projection :as projection]
            [open.atlas.studio.graph.impl.selector :as sel]
            [open.atlas.studio.graph.impl.store :as store]))

;; ---------------------------------------------------------------------------
;; 归属：这个属性 / namespace 归哪个 workspace
;; ---------------------------------------------------------------------------

(defn ownership
  "**归属报表**（§3.3 第一问）：一个对象归哪个 workspace。

   收一个属性 kw（`:sales.order/id`）、一个 namespace 名（`\"sales.order\"`）或一个
   限定注册表 ID（`\"common.audit/g_pii\"`），答池里同名对象身上**存着的**那个
   workspace（[D3.22](../../../../../../../../docs/design/30-workspace-graph.md#d322-池对象的归属是存下来的一列)）。

   以前这里是把名字拿去与地盘 selector 逐条匹配（D3.3 / D3.4 的登记表）。
   [D3.17](../../../../../../../../docs/design/30-workspace-graph.md#d317-workspace-是封闭世界跨-workspace-只有-import)
   拆了地盘制度之后那张表不再是判据，D3.22 把归属做成池对象上的一列——所以本函数
   **读那一列**，不再推导：同一个问题只有一个答案，而它写在对象身上。

   池里没有这个 id 的对象时 `:workspace` 为 nil、`:owned?` 为 false——那是真信息
   （「这台 Studio 里没有这个对象」），不是空答案。"
  [db x]
  (let [owner (some (fn [o] (when (= x (:pool/id o)) (:pool/workspace o)))
                    (pool/objects db))]
    {:query     x
     :namespace (sel/namespace-of x)
     :workspace owner
     :owned?    (some? owner)}))

;; ---------------------------------------------------------------------------
;; 引用反查：谁的菜谱引用了 <workspace>/<graph>@vN
;; ---------------------------------------------------------------------------

(defn normalize-version
  "版本归一成 `\"v3\"`。菜谱写的是字符串 `\"v3\"`，制品信封（D4.4）写的是整数 `3`
   ——`impl.projection/ref-key` 为同一个理由做同一件事：两处对不上，同一个版本会
   被当成两个东西，而反查会漏掉一半。"
  [v]
  (cond (nil? v)     nil
        (integer? v) (str "v" v)
        :else        (str v)))

(defn referrers
  "**引用反查**（§3.3「谁引用 `core@v3`」）：哪些 graph 的**当前菜谱**里钉着
   `qn@version`。

   `version` 给 `nil` = 不限版本（「谁引用了这张图，任何版本」）。给了版本就
   **逐版本精确匹配**：`core@v4` 的引用方不该出现在 `core@v3` 的反查结果里——
   R3.1「引用只钉版本，永不指 latest」正是靠这一点让反查答得准。

   **读的是菜谱，不是已切版本**：B5a 没有版本（B5b 才有），所以这份反查回答的是
   「谁**打算**引用它」。等 B5b 之后还要有一份按已切版本的反查，两者不同名。"
  [db qn version]
  (let [want (normalize-version version)]
    (vec
     (for [g (store/all-graphs db)
           r (:graph/references g)
           :when (and (= qn (:graph r))
                      (or (nil? want) (= want (normalize-version (:version r)))))]
       {:graph     (:graph/qualified-name g)
        :workspace (:graph/workspace g)
        :reference {:graph (:graph r)
                    :version (normalize-version (:version r))
                    :up-to (:up-to r)}}))))

;; ---------------------------------------------------------------------------
;; 含量清单（池内版）：这个对象按当前菜谱会进哪些图
;; ---------------------------------------------------------------------------

(def object-types
  "报表认得的对象类型。与池的十个类型同一套寻址（`[类型 id]`），
   因为问这一问的人手里拿的就是 `pool/objects` 给的那个 id。"
  ;; D2.29（owner 裁定 2026-09-02）：`:navigation` 不再是一类对象——`:output`
  ;; 树里的分支是一条 join 属性，问它含在哪些图里就是问 `:attribute`。
  #{:attribute :link :metric :mutation
    :namespace :resolver :source :term :unit})

(defn- in-model?
  "这个对象在不在一份**投影产物**里。逐类型按方言的形状取——投影产物就是方言
   （契约第 10 步交给同一个 writer），所以这里没有第二套形状知识。"
  [model [t id]]
  (case t
    :attribute      (contains? (:attributes model) id)
    :namespace      (contains? (:namespaces model) id)
    :source         (contains? (:sources model) id)
    :metric         (contains? (:metrics model) id)
    :term           (contains? (:terms model) id)
    :unit           (contains? (:units model) id)
    :resolver       (boolean (some #(= id (:id %)) (:resolvers model)))
    :mutation       (boolean (some #(= id (:id %)) (:mutations model)))
    :link           (boolean (some #(= id [(:from %) (:to %)]) (:links model)))))

(defn membership-by-current-recipe
  "**含量清单（池内版）**：这个对象**按当前菜谱**会进哪些图。

   ⚠️ **不是 §3.3 的含量清单**。§3.3 问的是「X 对象出现在哪些 **graph 版本**里」，
   那个要等 B5b 的切版本；这里答的是「**按今天的菜谱**投影，它会不会进这张图」。
   菜谱改过、还没重切时**两者答案不同**，所以两份报表永远分开命名（裁定 ⑥）。

   实现就是**对每张登记的图跑一次投影**（`impl.projection/project`），看对象在不在
   `:model` 里。**不另写闭包遍历**：写两遍的那天，报表会说「它在 A 图里」而 A 图
   实际投不出它（裁定 ⑥ 原话）。

   `resolved-for` 是 `限定名 → 已解析的钉版本引用集合` 的函数（投影的第三个参数）。
   缺省 `(constantly [])`：**不去猜引用**——一张带引用的图在缺引用时投不出来,
   那时它落进 `:undecidable` 并带着投影自己的 finding，而不是被悄悄算成
   「这个对象不在里面」。少报一条与「本来就没有」在报表上长得一样。

   返回
   `{:object [类型 id] :in [限定名…] :not-in [限定名…] :undecidable [{:graph … :findings […]}…] :findings []}`,
   三份清单都按限定名字典序。"
  ([db object] (membership-by-current-recipe db object (constantly [])))
  ([db [t _id :as object] resolved-for]
   (cond
     (not (and (vector? object) (= 2 (count object))))
     {:findings [(f/make :graph.report/malformed :error [:object]
                         "an object is addressed as [type id], the same way the pool addresses it"
                         :got (f/redact object))]}

     (not (contains? object-types t))
     {:findings [(f/make :graph.report/malformed :error [:object 0]
                         "unknown object type"
                         :expected (vec (sort-by str object-types)) :got t)]}

     :else
     (reduce
      (fn [acc g]
        (let [qn (:graph/qualified-name g)
              r  (projection/project db qn)]
          (cond
            (seq (:findings r))
            (update acc :undecidable conj {:graph qn :findings (:findings r)})

            (in-model? (:model r) object) (update acc :in conj qn)
            :else                         (update acc :not-in conj qn))))
      {:object object :in [] :not-in [] :undecidable [] :findings []}
      ;; `all-graphs` 已按限定名字典序（`impl.store`），顺序不靠 Datahike。
      (store/all-graphs db)))))
