(ns open.atlas.studio.version
  "**B5b.1 的唯一对外接口**。跨模块只许 require 本 ns；
   `open.atlas.studio.version.impl.*` 视为私有（`.clj-kondo/config.edn` 的
   `:discouraged-namespace` 执法，由 `module_boundary_test` 逐条对账）。

   交付范围是 **B5b.1**（modules/studio/version/README.md 的切片表）：

   | 交付 | 落点 | 决策 |
   |---|---|---|
   | 闭包解析 | `resolve-closure` | R3.1 · R3.4（R3.8 已随档位退役） |
   | 切版本体检（**拒绝**那一半） | `health` | D2.11 · D2.2 · D2.8 · E6.2 |
   | 切版本 = 投影 → 闭包 → 体检 → 密封 | `cut-version!` | 裁定 ① ② · D4.1–D4.4 · D4.10-a |
   | D3.10 的另一半 | `rename-graph!` | D3.10 |
   | 「这个动作会不会毁掉下一次切版本」 | `preflight` | D2.2 |
   | 「这张图现在切得出去吗」 | `cuttable` | D2.2 · D2.11 |

   `preflight` 与 `cuttable` 走**同一个** `assess`，所以「这次动作引入的」与
   「这张图本来就有的」不可能各说各话。

   **质量指标不在这里**（B5b.2 的剩下那半），**真实边界 conformance 也不在**
   （B5b.3）；**差异报告整项已取消**（owner 2026-08-30，D4.9）。本 ns 里没有任何一个覆盖率——D2.11 把「拒绝」与「展示」分成两栏
   是裁定过的，混用同一条通道就是把展示接回拒绝（README 裁定 ③）。

   契约：docs/contracts/artifact-v1.md、docs/contracts/scope-projection-v1.md、
        docs/contracts/finding-v1.md。"
  (:require [open.atlas.studio.graph :as graph]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.version.impl.closure :as closure]
            [open.atlas.studio.version.impl.cut :as cut]
            [open.atlas.studio.version.impl.semantic :as semantic]
            [open.atlas.studio.version.impl.finding :as f]
            [open.atlas.studio.version.impl.finding :as finding]
            [open.atlas.studio.version.impl.health :as health]
            [open.atlas.studio.version.impl.store :as store]))

;; ---------------------------------------------------------------------------
;; 装 schema
;; ---------------------------------------------------------------------------

(def datahike-schema
  "版本登记的 Datahike schema。**与池、与治理数据分开装**（D1.8 单向）。"
  store/schema)

(defn install!
  "把版本登记 schema 装进一个已开的池连接。幂等。"
  [conn]
  (store/install! conn))

;; ---------------------------------------------------------------------------
;; 1. 闭包解析（R3.1 / R3.4；R3.8 已随访问档位退役，D5.11）
;; ---------------------------------------------------------------------------

(def resolve-closure
  "菜谱引用 + 已解析的钉版本引用 → **传递引用闭包**。

   `(resolve-closure root-qn recipe-refs resolved)` →
   `{:nodes {[限定名 版本] {:paths [[节点…]…]}} :order […] :findings []}`。

   - **R3.1**：版本永远是具体版本号，读不出正整数即拒绝（永不指 latest）；
   - **R3.4**：同一 graph 出现两个版本 → 拒绝，点名 graph、两个版本、**两条路径**;
     不做自动仲裁；
   - **R3.8 已退役**（owner 裁定 2026-08-27，D5.11）：它要的是「同一已钉版本经
     多条路径到达时取全部路径的最小档位上限」，而没有档位就没有可取最小的东西。
     闭包遍历本身照旧——它从来不是为档位存在的。"
  closure/resolve-closure)

(def normalize-version
  "版本归一成正整数：菜谱写 `\"v3\"`、制品信封（D4.4）写 `3`，两处必须是同一个
   身份。读不出正整数给 nil——那是 R3.1 的拒绝口，不是可以猜的值。"
  closure/normalize-version)

(def direct-references
  "闭包 → `content.edn` 的 `:references`（D4.4：三元组、钉版本、带 `:up-to`）。
   **只写菜谱的直接引用**：被引用版本各自成目录、不内联（D4.2）。"
  closure/direct-references)

;; ---------------------------------------------------------------------------
;; 2. 切版本体检（D2.11 的**拒绝**那一半）
;; ---------------------------------------------------------------------------

(def health
  "`(health model closure opts)` → `{:findings […] :health {…}}`。

   跑在**投影产物 + 闭包**上，不是跑在池上（README 裁定 ②）。四类拒绝：
   结构（dangling input · link 断链 · 多方产出）、D2.2 属性声明漂移、
   D2.8（**回落 `dialect/read-dialect`**，不写第二个 schema）、E6.2 韧性。

   `:health` 的键面是 `artifact/seal-graph` 的 `health-input-keys`——体检结论
   **封进制品**（D4.1：server 没有池，它无法重算这些结论）。

   opts：`{:timeout-enforcing-types #{\"sql\" …} :connector-defaults {\"sql\" {…}}
          :platform-timeout-ms 30000}`。"
  health/check)

(def default-timeout-enforcing-types
  "能强制超时的连接器契约（E6.2：enforce 不了超时的连接器不允许发布）。"
  health/default-timeout-enforcing-types)

(def effective-resilience
  "D6.2 的四级合并：resolver 覆盖 > source policy > 连接器类型缺省 > 平台缺省。"
  health/effective-resilience)

;; ---------------------------------------------------------------------------
;; 3. 切版本
;; ---------------------------------------------------------------------------

(defn- assess
  "One pool snapshot → the findings a cut would refuse on, via the **same**
   projection → health path `cut-version!` walks. No shortcut merge into a
   projected model: a spliced-in entry would bypass the graph's recipe and its
   closure, and then the answer would be about a model that never exists.

   The closure step is gone with references (D3.17); the empty closure is still
   handed to `health/check` because its key face is D4.9's, and that shape does
   not change just because one of its inputs is now always empty."
  [db graph _resolved opts]
  (if (graph/graph db graph)
    (let [proj (graph/project db graph)]
      (if (seq (:findings proj))
        (vec (:findings proj))
        (vec (:findings (health/check (:model proj)
                                      {:nodes {} :order [] :reference-attributes #{} :findings []}
                                      (or opts {}) (:local proj))))))
    [(f/make :cut.version/malformed :error [:graph]
             "no such graph is registered" :got graph :refs ["D3.18"])]))

(defn cuttable
  "**Would this graph cut right now?** — and if not, which checks refuse it.

   ```clojure
   (cuttable conn {:graph \"sales/app\"})
   ```

   → `{:would-cut? bool :health-findings […] :findings []}`

   The same `assess` `preflight` diffs against, asked about the pool as it
   stands rather than about a candidate. It exists because the answer had no
   reader: `preflight` **requires an action**, so «is this graph publishable»
   could only be learned by attempting a cut and reading the refusal — which is
   a write attempt asked as a question, and the one path D2.2's «free to write,
   strict to seal» leaves a person no way to look down before stepping.

   `:health-findings`, not `:findings`. The findings ARE the answer here, and
   this ns uses `:findings` for «this call was refused» everywhere else
   (`preflight` splits them the same way, for the same reason). A caller that
   read a graph's backlog as a refusal of its own request would report the
   wrong thing.

   **Not cheap.** It projects and health-checks, which is most of a cut minus
   the seal. Ask it about one graph a person is looking at, not about every row
   of a list."
  [conn {:keys [graph resolved opts]}]
  (let [findings (assess (pool/db conn) graph resolved opts)]
    {:would-cut? (not (f/errors? findings))
     :health-findings (vec findings)
     :findings []}))

(defn preflight
  "**Would this action break the next cut?** — answered by the checks that
   would actually refuse it, and scoped to what the action itself introduces.

   ```clojure
   (preflight conn {:graph \"sales/app\" :action {…execute-action shape…}})
   ```

   → `{:would-cut? bool :introduced-findings […] :existing-findings […]
       :resolved-findings […] :findings []}`

   Two things this is careful about:

   1. **The candidate walks the same path.** A candidate pool is derived with
      `pool/candidate-db` (nothing committed) and then projected, closed over
      and checked exactly as a real cut would. Splicing an entry into an
      already-projected model would sidestep selectors, ownership and the
      reference closure.
   2. **The diff is the answer.** A graph that was already unhealthy would
      otherwise hand this action the whole backlog, and the author would read
      it as \"I just broke all of this\". Only `:introduced-findings` belong to
      the action; what was already there is reported separately, and anything
      the action *fixes* is worth saying too.

   `:would-cut?` is about the candidate as a whole — a cut refuses on any
   error, inherited or not — so it is deliberately not the same question as
   \"did this action introduce something\"."
  [conn {:keys [graph action resolved opts]}]
  (let [cand (pool/candidate-db conn action)]
    (if (seq (:findings cand))
      ;; The action would be refused by the pool itself; there is no candidate
      ;; to check, and saying so is more useful than an empty diff.
      {:findings (vec (:findings cand))}
      (let [before (assess (pool/db conn) graph resolved opts)
            after  (assess (:db cand) graph resolved opts)
            key-of (fn [x] [(:code x) (:path x) (:message x)])
            b      (set (map key-of before))
            a      (set (map key-of after))]
        {:would-cut? (not (f/errors? after))
         :introduced-findings (vec (remove #(contains? b (key-of %)) after))
         :existing-findings   (vec (filter #(contains? b (key-of %)) after))
         :resolved-findings   (vec (remove #(contains? a (key-of %)) before))
         :findings []}))))

(defn cut-version!
  "**切一个版本**：投影 → 闭包 → 体检 → 密封，四步串行，前一步不过不进下一步。

   ```clojure
   (cut-version! conn {:graph \"sales/core\"
                       :resolved [{:graph \"core-team/core\" :version \"v3\" …}]
                       :artifact-root \"/srv/artifacts\"
                       :cut-at \"2026-08-24T09:00:00Z\" :cut-by \"alice\"
                       :tier :standard          ; 可选，缺省 :standard（D2.11）
                       :opts {…体检 opts…}})
   ```

   → `{:version 1 :content-hash … :artifact-digest … :dir … :closure … :health …
       :findings []}`，或 `{:findings […]}` 且**一个字节的制品都没写**。

   版本号是**具体数字、单调递增、切出即不可变**。密封调
   `open.atlas.common.artifact` 的 `encode-content` / `seal-graph`,
   **一行序列化都不重写**（D4.10-a、README 裁定 ①）。

   `:cut-at` / `:cut-by` **必传**：本模块不读时钟、不读当前用户（D4.10-a）。"
  [conn req]
  (locking conn (cut/cut-version! conn req)))

(defn versions
  "一张图已切出的全部版本（按版本号）。不给 `qn` 则给整池的。"
  ([db] (store/all-versions db))
  ([db qn] (store/versions-of db qn)))

(defn cut-any?
  "这张图切过版本没有——D3.10 那一半的判据。"
  [db qn]
  (store/cut-any? db qn))

(defn mark-reclaimed!
  "Mark one cut version reclaimed (R3.5): its record stays, its artifact is gone."
  [conn qn number at-by]
  (store/mark-reclaimed! conn qn number at-by))

(def deletion-tx
  "Transaction fragment for permanently deleting one Graph's cut-version records."
  store/deletion-tx)

(defn next-version
  "下一个版本号（已登记最大值 + 1）。号码不回收：同一个号码指向两份内容会让
   制品身份不唯一（D4.3）。"
  [db qn]
  (store/next-number db qn))

;; ---------------------------------------------------------------------------
;; 4. D3.10 的另一半
;; ---------------------------------------------------------------------------

(defn rename-graph!
  "改 graph 的限定名——**只有「还没切过任何版本」时才允许**（D3.10 原话：
   「限定名在切出第一个版本后不可变」）。切过即拒绝。

   B5a.1 把限定名做成「登记即不可变」，并注明「B5b 若要放开是**加**一个查过
   版本的函数，不是放松那里」。这就是那个函数：它把版本这个事实带给
   `graph/rename-graph!`（graph 模块自己不认识版本，反向依赖会成环）。

   **为什么切过就不许改**：一个 graph 的限定名同时出现在别人的引用清单、在跑的
   token、目标 instance 的部署指针和历史审计里，其中两处（已发出去的 token、
   已封存的制品）按定义不可改。所以改名不是重命名，是**发布一个新东西**。"
  [conn qn new-name]
  (graph/rename-graph!
   conn qn new-name
   (fn [db qn]
     (when (store/cut-any? db qn)
       [(finding/make :version.rename/already-cut :error [:graph]
                      (str "this graph has already cut a version; the qualified name is immutable "
                           "after the first cut — renaming is publishing a new graph, not a rename")
                      :got qn
                      :expected (vec (map :version/id (store/versions-of db qn)))
                      :refs ["D3.10"])]))))

;; ---------------------------------------------------------------------------
;; finding
;; ---------------------------------------------------------------------------

(def finding-codes
  "本模块已发布的 `:code` 注册表（finding-v1 §6）。域是 `cut.*` / `version.*`。
   **一条质量指标都没有**——D2.11 的两栏走两条路。"
  finding/codes)

(def finding?
  "x 是否为合法 finding。认本模块 + 三个**回落**来源（dialect / artifact /
   graph）的 code：切版本的拒绝有一半是回落来的，重贴一层皮会让下游按 code
   分流时漏掉一半。"
  finding/valid?)

(def cut-semantic-version! semantic/cut!)
