(ns open.atlas.studio.version.impl.closure
  "**闭包解析**：一张 graph 的菜谱引用 → 传递引用闭包（R3.1 / R3.4 / R3.8）。

   ## 三条规则的先后是承重的

   1. **R3.1**：引用永远钉具体版本号，**永不指 latest**。版本读不成一个正整数就
      停在这里——后面两条都以「节点身份 = (限定名, 版本)」为前提，而 latest 让
      这个身份不存在。
   2. **R3.4**：完整传递引用集里同一 graph 出现两个版本即**拒绝切**，点名是哪个
      graph、哪两个版本、**经由哪两条路径**。不做自动仲裁（R3.4 原话：「层级浅，
      拒绝够用」）。
   3. **R3.8**：同一已钉版本经多条路径到达时，对该对象取**所有路径的最小值**。
      **R3.4 在 R3.8 之前**——R3.8 自己就写着「若多条路径带来同一 graph 的不同
      版本，先按 R3.4 拒绝，不能用档位合并掩盖版本冲突」：两个版本各自取严会得
      到两个都合法的上限，冲突就此消失在一次 `meet` 里。

   ## D1.12：图论住在 common，这里只剩 R3.1/R3.4/R3.8

   可达性、环检测、缺失节点点名、路径枚举**不在这里**——它们是 studio 与
   server 必须答得一样的事实，搬进了 `open.atlas.common.artifact/graph-traverse`
   （D1.12）。本 ns 只做三件事，全部挂在同一次遍历上（**不再走第二遍**）：

   1. 把菜谱引用 / 制品内容引用转换成 common 认识的 node-key，喂给
      `graph-traverse` 当 resolver；顺带在这里做 R3.1 的钉版本检查——那是
      **判断**，不是图论，common 不认识「latest 不合法」这回事。
   2. **R3.8 的档位取严挂在同一个 resolver 回调上**，用先进先出队列做增量
      传播（见下）。
   3. 遍历结束后，用 common 给回的 `:paths` 事实判 R3.4 钻石冲突。

   ## 曾经的第三件事：档位取严

   R3.8「引用档位上限沿路径取最严」**已随访问档位退役**（owner 裁定 2026-08-27,
   D5.11），承载它的 `common/artifact` ceiling tracker 一并删除。遍历本身没变
   ——它从来就不是为档位存在的。"
  (:require [clojure.string :as str]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.version.impl.finding :as f]))

(defn normalize-version
  "版本归一成**正整数**。菜谱写 `\"v3\"`，制品信封（D4.4）写整数 `3`——两处必须
   落到同一个身份，否则同一个版本会被当成两个节点，而 R3.4 的钻石检查正靠这个
   身份。读不出正整数给 nil：那是 R3.1 的拒绝口，不是一个可以猜的值。"
  [v]
  (cond
    (and (integer? v) (pos? v)) v
    (string? v) (let [s (str/trim v)
                      s (if (str/starts-with? s "v") (subs s 1) s)]
                  (when (re-matches #"[1-9][0-9]*" s) (parse-long s)))
    :else nil))

(defn node-label
  "节点在 finding 与路径里的写法：`core-team/core@v3`（D3.10 的限定名 + 钉版本）。
   根节点没有版本（一张正在被切的图还不是任何版本），只写限定名。"
  [[qn v]]
  (if (nil? v) (str qn) (str qn "@v" v)))

(defn- path-label [path] (str/join " → " (map node-label path)))

(defn- ref-key [r] [(str (or (:graph r) (str (:workspace r) "/" (:graph r))))
                    (normalize-version (:version r))])

(defn- envelope-ref-key
  "制品信封里的引用是三元组 `{:workspace :graph :version}`（D4.4）。"
  [r]
  [(str (:workspace r) "/" (:graph r)) (normalize-version (:version r))])

(defn- resolved-index [resolved]
  (into {} (for [r resolved] [[(str (:graph r)) (normalize-version (:version r))] r])))

;; `:up-to` was a tier ceiling on a reference edge (D5.5). Both halves retired —
;; references with D3.17, tiers with the owner ruling of 2026-08-27 — so the key
;; is carried through untouched when a legacy envelope still has one, and never
;; defaulted into existence.
(defn- up-to-of [r] (:up-to r))

(defn- unpinned
  [path-prefix r]
  (f/make :cut.closure/unpinned-version :error [:references]
          "a reference always pins a concrete version; latest is never a reference target"
          :got (f/redact (:version r))
          :expected "a positive integer, or \"vN\""
          :refs ["R3.1" "D4.4"]
          :files (when (seq path-prefix) [(path-label path-prefix)])))

;; The ceiling tracker retired with access tiers (owner ruling 2026-08-27):
;; a ceiling was the strictest tier reachable along a path (R3.8), and with no
;; tiers there is nothing to take a minimum of. The traversal itself is
;; unchanged — it was never about tiers.

(defn resolve-closure
  "菜谱引用 + 已解析的钉版本引用 → 传递闭包。

   ```clojure
   (resolve-closure \"sales/core\"
                    [{:graph \"core-team/core\" :version \"v3\" :up-to :sensitive}]
                    [{:graph \"core-team/core\" :version \"v3\"
                      :content <content.edn 信封> :content-hash \"sha256:…\"}])
   ```

   → `{:nodes {[限定名 版本] {:paths [[节点…]…]}} :order [节点…]
       :findings []}`，或 `{:findings […]}`。

   `:paths` 是到达该节点的全部路径（已排序，供 R3.4 点名与差异报告使用）。
   **没有 `:ceiling`**——它是 R3.8 的全路径最小档位，随档位退役一并删除。`root-qn` 只作为路径的第一段出现,
   它不是闭包的成员——一张图不引用自己。"
  [root-qn recipe-refs resolved]
  (let [idx  (resolved-index resolved)
        root [root-qn nil]
        shape-errs
        (vec (for [r recipe-refs
                   :when (or (not (map? r)) (not (string? (:graph r))))]
               (f/make :cut.closure/malformed :error [:references]
                       "a recipe reference is {:graph \"<workspace>/<graph>\" :version \"vN\" :up-to tier?}"
                       :got (f/redact r) :refs ["D3.11" "D4.4"])))
        pin-errs (when (empty? shape-errs)
                   (vec (for [r recipe-refs :when (nil? (normalize-version (:version r)))]
                          (unpinned [] r))))]
    (if (seq (concat shape-errs pin-errs))
      {:findings (f/sort-findings (vec (concat shape-errs pin-errs)))}
      (let [nested-findings (atom [])
            ;; 菜谱层：直接推子节点的初始档位——root 没有自己的上限，
            ;; `up-to-of r` 就是这条边（也是这个节点）自己的档位。
            root-children
            (vec (keep (fn [r]
                         (let [k (ref-key r)]
                           (when (some? (second k))
                             k)))
                       recipe-refs))
            resolve-fn
            (fn [k path]
              (do
                (when-let [artifact-entry (get idx k)]
                  (vec (keep (fn [r]
                               (let [ck (envelope-ref-key r)]
                                 (if (nil? (second ck))
                                   (do (swap! nested-findings conj (unpinned path r)) nil)
                                   ck)))
                             (:references (:content artifact-entry)))))))
            {:keys [order paths cycles missing]}
            (artifact/graph-traverse root root-children resolve-fn)
            cycle-findings
            (vec (for [{:keys [path]} cycles]
                   (f/make :cut.closure/cycle :error [:references]
                           "the reference closure contains a cycle; (graph, version) is a DAG"
                           :got (path-label path) :refs ["R3.3"])))
            missing-findings
            (vec (for [{:keys [node path]} missing]
                   (f/make :cut.closure/reference-missing :error [:references]
                           "this pinned version is in the transitive reference closure but was not supplied"
                           :got (node-label node) :expected (path-label path)
                           :refs ["R3.2" "scope-projection-v1 §2.3"])))
            findings (vec (concat @nested-findings cycle-findings missing-findings))]
        (cond
          (seq findings) {:findings (f/sort-findings (vec (distinct findings)))}

          ;; ── R3.4 **在 R3.8 之前** ────────────────────────────────
          :else
          (let [by-graph (->> order (group-by first) (sort-by first))
                conflicts
                (vec (for [[qn ks] by-graph
                           :when (> (count ks) 1)
                           :let [ks (vec (sort-by second ks))]]
                       (f/make :cut.closure/version-conflict :error [:references]
                               (str "the transitive reference set pins two versions of the same graph; "
                                    "align them yourself — no automatic arbitration")
                               :got qn
                               :expected (vec (for [k ks]
                                                {:version (str "v" (second k))
                                                 :via (path-label
                                                       (into [root]
                                                             (first (sort-by pr-str (get paths k)))))}))
                               :refs ["R3.4"])))]
            (if (seq conflicts)
              {:findings (f/sort-findings conflicts)}
              {:nodes (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b))))
                            (for [k order]
                              [k {:paths (get paths k)}]))
               :order order
               :findings []})))))))

(defn direct-references
  "闭包 + 已解析引用 → `content.edn` 的 `:references`（D4.4 三元组，钉版本，
   带 `:up-to`）。

   **只写菜谱的直接引用**：被引用的版本各自成为独立目录、**不内联**（D4.2），
   装载方按各自制品的引用清单继续解析传递闭包。`:up-to` 写的是这条边上的
   R3.8 取严结果——制品是一个 snapshot，它把「我这条边最多带出什么档位」说尽。"
  [recipe-refs resolved closure]
  (let [idx (resolved-index resolved)]
    (vec (sort-by (juxt :workspace :graph :version)
                  (for [r recipe-refs
                        :let [k (ref-key r)
                              [ws nm] (graph/parse-qualified-name (first k))]]
                    {:workspace ws
                     :graph     nm
                     :version   (second k)
                     :content-hash (:content-hash (get idx k))
                     })))))
