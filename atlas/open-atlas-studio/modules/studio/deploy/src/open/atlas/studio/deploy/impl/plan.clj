(ns open.atlas.studio.deploy.impl.plan
  "owner 校验与 closure plan 的构造——**纯的那一半**。

   HTTP 与签名在 impl.client；本 ns 不碰网络，也不碰时钟：`now` 传进来。"
  (:require [open.atlas.common.artifact :as artifact]
            [open.atlas.common.control-protocol :as cp]
            [open.atlas.studio.deploy.impl.finding :as f]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.version :as version]))

;; ---------------------------------------------------------------------------
;; owner 校验 —— D3.12 授权链的第一环
;; ---------------------------------------------------------------------------

(defn owner?
  "`actor`（D5.10 的不可变二元组 `{:issuer :subject}`）是不是 `workspace` 的 owner。

   **只认 owner，不认 editor**（D5.7）：签发 token、编辑属性档位、设置引用上限
   是 owner 独占动作，部署同理——否则「自律建模」的信任模型会被顺手扩大成
   「自律发布」。

   **issuer 与 subject 都参与相等判断**：只按 subject 比，两个 IdP 下同名的两个人
   就成了同一个人。邮箱与显示名若要留，只能进成员记录的 `:attributes`,
   那里的东西不参与匹配。

   ── 为什么这里与信封里的 actor 必须是**同一个值** ──────────────────────────

   曾经这里收的是「studio 成员表的键」（一个裸字符串），而信封与审计里是二元组
   ——两者不绑定的话，可以拿 Alice 的 owner 资格发一份请求，而审计完整地记成
   Mallory。D5.10 要的正相反：**通过 owner 检查的那个 actor，就是审计里的那个
   actor**。所以成员表在 2026-08-25 收紧成同一种主体（owner 裁定），入参只剩
   一个。"
  [db workspace actor]
  (boolean
   (some (fn [m] (and (= actor (:principal m)) (= :owner (:role m))))
         (:workspace/members (first (filter #(= workspace (:workspace/name %))
                                            (graph/workspaces db)))))))

(defn check-owner
  "→ `[finding…]`（空 = 放行）。`actor` 就是信封里那个 `:actor/principal`——
   **同一个值**，见 `owner?`。"
  [db qualified actor]
  (let [[ws _] (graph/parse-qualified-name qualified)]
    (cond
      (nil? ws)
      [(f/error :deploy.graph/unknown [:graph]
                "not a qualified graph name" :got qualified)]

      (nil? (graph/graph db qualified))
      [(f/error :deploy.graph/unknown [:graph]
                "no such graph is registered in this studio" :got qualified)]

      (not (owner? db ws actor))
      [(f/error :deploy.authz/not-owner [:actor]
                (str "only an owner of this workspace may deploy this graph; editor is not "
                     "enough — publishing is the promise to consumers, and widening "
                     "\"self-disciplined modelling\" into \"self-disciplined publishing\" is "
                     "exactly what D5.7 refuses")
                :got {:workspace ws} :refs ["D3.12" "D5.7" "D3.3"])])))

;; ---------------------------------------------------------------------------
;; closure plan
;; ---------------------------------------------------------------------------

(defn- version-row [db qualified n]
  (first (filter #(= n (:version/number %)) (version/versions db qualified))))

(defn build-plan
  "root 版本 → `:offer` 要送的闭包清单（**含根自己**）。

   闭包**从制品架上真遍历**（`artifact/graph-traverse`，D1.12 的共用能力）,
   而不是从菜谱重算：要送出去的是**架子上那些字节**，而菜谱描述的是「切版本时
   想要什么」。两者本该一致，但一致该是被证出来的，不是被假设的——真遍历一遍,
   digest 从版本登记里取，对不上就在这里露出来，而不是等 server 拒。

   → `{:closure [成员…]}` 或 `{:findings […]}`。"
  [db artifact-root qualified n]
  (let [problems (atom [])
        note!    (fn [x] (swap! problems conj x))
        texts    (fn [qn v]
                   (let [[ws g] (graph/parse-qualified-name qn)]
                     (when ws (artifact/read-shelf-texts artifact-root
                                                         {:workspace ws :graph g :version v}))))
        resolve*
        (fn [[qn v] _path]
          (let [m (texts qn v)]
            (when (= (count artifact/artifact-files) (count m))
              (let [{:keys [findings references]}
                    (artifact/read-artifact
                     m {:expected-digest (:version/artifact-digest (version-row db qn v))
                        :resolve-reference
                        (fn [ref] (get (texts (str (:workspace ref) "/" (:graph ref))
                                              (:version ref))
                                       "content.edn"))})]
                ;; **只有 `:error` 挡路**。carried 键（D5.8/D2.9）是逐项 `:info`
                ;; 点名——「合法、能往返、暂无执行语义」，它出现在任何用了
                ;; `:row-security`、`:cache` 之类键的模型里。把它当拒绝，等于
                ;; 一张图只要写过一个 carried 键就永远部署不了，而那与 D5.8 说的
                ;; 「点名而不是拒绝」正好相反。
                (if (some #(= :error (:severity %)) findings)
                  (do (run! note! (filter #(= :error (:severity %)) findings)) [])
                  (vec (for [r references]
                         [(str (:workspace r) "/" (:graph r)) (:version r)])))))))
        {:keys [order missing cycles]}
        (artifact/graph-traverse ::root [[qualified n]] resolve*)
        ;; `order` **already contains the root**: it is the one root-child fed to
        ;; the traversal, so it is a visited node like any other. Consing it in
        ;; again listed it twice, and the protocol rejects a closure that names
        ;; the same coordinate twice — leaving it out also means a root that is
        ;; not on the shelf shows up in `missing` instead of being asserted into
        ;; the plan by this very line.
        members
        (for [[qn v] order
              :let [row (version-row db qn v)]]
          (if (nil? row)
            (do (note! (f/error :deploy.plan/unknown-version [:closure]
                                (str "this version is in the closure on the shelf but the "
                                     "version registry has no row for it, so there is no "
                                     "trusted digest to send")
                                :got {:graph qn :version v}))
                nil)
            {:graph qn :version v
             :content-hash    (:version/content-hash row)
             :artifact-digest (:version/artifact-digest row)}))]
    (doseq [{:keys [node]} missing]
      (note! (f/error :deploy.plan/unknown-version [:closure]
                      "this transitively referenced version is not on the studio shelf"
                      :got node)))
    (doseq [{:keys [path]} cycles]
      (note! (f/error :deploy.plan/unknown-version [:closure]
                      "the reference closure contains a cycle" :got (vec (rest path)))))
    (if (seq @problems)
      {:findings (f/sort-findings (vec @problems))}
      ;; 顺序不影响身份（server 那边按坐标排过序再算 plan-hash），排一遍只是
      ;; 让人读日志时舒服。
      {:closure (vec (sort-by (juxt :graph :version) (remove nil? members)))})))

;; ---------------------------------------------------------------------------
;; 信封
;; ---------------------------------------------------------------------------

(defn shelf-files
  "闭包里的一个成员 → 它在制品架上的三份文本。"
  [artifact-root {:keys [graph version]}]
  (let [[ws g] (graph/parse-qualified-name graph)]
    (when ws
      (artifact/read-shelf-texts artifact-root {:workspace ws :graph g :version version}))))

(defn envelope
  "构造并**签名**一份控制信封。

   actor 断言签的是**整个信封减签名那一个字段**（D3.16）——所以必须先把除签名
   之外的一切都填好，最后一步才是签。"
  [opts]
  (cp/build-envelope opts))
