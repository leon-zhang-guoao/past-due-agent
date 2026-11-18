(ns open.atlas.common.artifact.impl.reader
  "artifact reader（D4.6）：**只拆信封**，模型内容交给方言 reader。

   它只做三件事：
   1. 拆信封（格式版本、顶层封闭键面）；
   2. 校验完整性与 `:references`（版本、hash——`:up-to` 已随访问档位退役，D5.11）；
   3. 把 `:objects` **原样**交给同一个方言 reader。

   **为什么这样切**：模型内容的解析规则只有一份实现，制品与手写方言文件
   因此不可能被解释成两个模型——这正是 D1.3「两个 reader 可以，两个编译器
   不行」在文件层的落法。副产品是 B3 省掉一整个解析器。

   本 ns **无 IO**：入参是三份文件的文本。落盘边界（路径安全、原子写、部分
   写入）在 impl.store。这样「制品的装载语义」与「文件系统的脾气」各自可测，
   而黄金向量根本不需要碰磁盘。

   装载失败必须点名**哪个文件、哪条规则、哪个对象**——server 上没有池可以
   回头看，错误信息就是全部线索（D4.6）。

   ## 校验必须被显式声明，不许沉默跳过

   D4.3/D4.11 说 server「在暂存、装载与激活前都必须重算比对」。三样校验里只有
   一样能自证：

   - **content-hash** —— `manifest.edn` 自带它，reader 重算 `content.edn` 就能比，
     不需要任何入参，因此**永远执行**，没有关掉它的开关。
   - **artifact-digest** —— 无法自证。制品里没有可信的自报值（这是有意的：digest
     不写回三份文件，否则它可以和文件一起被替换，D4.3），期望值只能来自已认证的
     部署控制信封或离线签名清单。
   - **引用闭包** —— 同样要外部输入：制品架长什么样 reader 不知道也不该知道。

   后两样曾经是「入参缺席就跳过」。那把 D4.3/D4.11 的**必须**悄悄挪到了调用方
   身上，而调用方那一侧没有任何测试守着——默认沉默地少做一道校验，就是静默降级。

   现在改成**二选一，必须选**：要么给出验的依据（`:expected-digest` /
   `:resolve-reference`），要么显式写下「我知道我没在验」（`:digest :unverified` /
   `:closure :unverified`）。两个都不给 → 结构化拒绝，点名这是一次**未声明的
   跳过**；两个都给 → 也拒绝，那是自相矛盾的声明，替调用方挑一个才是真正危险的事。

   返回值里带 `:verified`，如实说明这一次**实际验了哪几样**——让下游能断言，
   也让「没验」在日志里看得见而不是消失。"
  (:require [open.atlas.common.dialect :as dialect]
            [open.atlas.common.artifact.impl.canonical :as c]
            [open.atlas.common.artifact.impl.envelope :as env]
            [open.atlas.common.artifact.impl.finding :as f]))

(defn- check-manifest-echo
  "`:artifact/format-version` 的权威位置在 `content.edn`（因此进 content-hash），
   同时在 `manifest.edn` 里回显一份——装载方需要在解析 `content.edn` 之前就
   知道自己认不认识这个格式，而解析本身依赖格式版本（D4.7）。

   **两处不一致即拒绝装载**：那说明制品被人手改过。"
  [manifest content-version]
  (let [echoed (:artifact/format-version manifest)]
    (cond
      (not (contains? manifest :artifact/format-version))
      [(f/error :artifact.envelope/missing-key [:artifact/format-version]
                "manifest.edn must echo :artifact/format-version"
                :refs ["D4.7"] :files ["manifest.edn"])]

      (seq (env/check-format-version echoed [:artifact/format-version] "manifest.edn"))
      (env/check-format-version echoed [:artifact/format-version] "manifest.edn")

      (not= echoed content-version)
      [(f/error :artifact.compat/format-version-mismatch [:artifact/format-version]
                (str "content.edn declares format-version " (pr-str content-version)
                     " but manifest.edn echoes " (pr-str echoed)
                     "; the artifact has been hand-edited")
                :expected content-version :got echoed
                :refs ["D4.7"] :files ["content.edn" "manifest.edn"])]

      :else [])))

(defn- check-content-hash
  "装载方先读 `content.edn` 重算 content-hash，再与 manifest 记的值比对
   （D4.3「manifest 不自引用」）。"
  [manifest recomputed]
  (let [declared (:content-hash manifest)]
    (cond
      (not (c/hash-str? declared))
      [(f/error :artifact.integrity/content-hash-mismatch [:content-hash]
                "manifest.edn must record a :content-hash of \"sha256:\" + 64 lowercase hex"
                :got (f/redact declared) :refs ["D4.3"] :files ["manifest.edn"])]

      (not= declared recomputed)
      [(f/error :artifact.integrity/content-hash-mismatch [:content-hash]
                (str "content.edn hashes to " (f/short-name recomputed)
                     " but manifest.edn records " (f/short-name declared))
                :expected declared :got recomputed
                :refs ["D4.3"] :files ["content.edn" "manifest.edn"])]

      :else [])))

(defn- check-digest
  "server 在暂存、装载与激活前都必须重算比对（D4.3/D4.11）。

   期望值必须来自**已认证的控制信封或离线签名清单**，不能信任与文件放在
   同一目录、可被一起替换的自报值——所以它是入参，而不是从制品里读出来的。
   digest 不写回三份文件，正是为了让「自报」这条路根本不存在。

   只在调用方给出期望值时调用；「不验」是一次显式声明，由 check-declarations
   把关，不是这里的一个 nil 分支。"
  [texts expected]
  (let [actual (c/artifact-digest texts)]
    (if (= actual expected)
      []
      [(f/error :artifact.integrity/artifact-digest-mismatch []
                (str "artifact-digest is " (f/short-name actual)
                     " but the deployment envelope expects " (f/short-name expected)
                     "; per-file hashes are in :got so the changed file can be named")
                :expected expected
                :got (into {} (c/file-hashes texts))
                :refs ["D4.3" "D4.11"]
                :files (vec c/digest-file-order))])))

(defn- check-reference-closure
  "引用的 `:content-hash` 让装载方能验证拿到的确实是当初解析的那一份——
   **对不上即拒绝装载，不做「大概是同一个」的推测**（D4.4）。

   `resolve-ref` 是 `(fn [{:keys [workspace graph version]}] content.edn 文本 | nil)`：
   装载闭包不是一种新的文件格式，只是「装这张图需要哪几个目录」的名字（D4.1），
   因此解析闭包成员的方式由调用方注入，reader 不知道也不需要知道制品架长什么样。
   缺席（nil）时只验形状不验闭包——离线校验一份孤立制品是合法用法。"
  [references resolve-ref]
  (vec (mapcat
          (fn [[idx r]]
            (let [path [:references idx]
                  text (resolve-ref r)]
              (cond
                (nil? text)
                [(f/error :artifact.reference/missing path
                          (str "referenced version " (f/qualified-name r)
                               " is not present in the load closure")
                          :got (f/qualified-name r)
                          :refs ["D4.1" "D4.4" "R3.3"] :files ["content.edn"])]

                :else
                (let [actual (c/content-hash-of-text text)]
                  (when (not= actual (:content-hash r))
                    [(f/error :artifact.reference/content-hash-mismatch path
                              (str "referenced version " (f/qualified-name r)
                                   " hashes to " (f/short-name actual)
                                   " but this artifact was sealed against "
                                   (f/short-name (:content-hash r))
                                   "; an immutable version was changed upstream")
                              :expected (:content-hash r) :got actual
                            :refs ["D4.3" "D4.4"] :files ["content.edn"])])))))
       (map-indexed vector references))))

(def ^:private unverified
  "「我知道我没在验」的唯一合法写法。只有一个取值——若还有第二种拼法，
   「有没有声明」本身就会变成需要解释的事（D2.12 的同一条纪律）。"
  :unverified)

(defn- check-declarations
  "装载校验的声明检查（见本 ns docstring「校验必须被显式声明」）。

   => `{:findings [...] :verify-digest? bool :verify-closure? bool}`。

   两样各自二选一、必须选。**先于一切其它检查**：这是一次调用方契约违规，
   不是制品的毛病；在契约没谈拢之前对制品下任何结论，都是在回答一个没被
   完整提出的问题。"
  [{:keys [expected-digest digest resolve-reference closure] :as opts}]
  (let [pair (fn [path evidence evidence-key decl decl-key what how]
               (let [has-ev?   (contains? opts evidence-key)
                     has-decl? (contains? opts decl-key)]
                 (cond
                   (and has-ev? has-decl?)
                   {:findings [(f/error :artifact.verification/contradictory-declaration path
                                        (str "both " evidence-key " and " decl-key
                                             " were given; that is a contradictory declaration"
                                             " about whether " what " is verified —"
                                             " picking one on the caller's behalf is exactly"
                                             " the dangerous move")
                                        :refs ["D4.3" "D4.11"])]
                    :verify? false}

                   has-decl?
                   (if (= decl unverified)
                     {:findings [] :verify? false}
                     {:findings [(f/error :artifact.verification/undeclared-skip path
                                          (str decl-key " must be " unverified
                                               " when present; it is the declaration that"
                                               " this load knowingly does not verify " what)
                                          :got decl :expected unverified
                                          :refs ["D4.3" "D4.11"])]
                      :verify? false})

                   has-ev?
                   {:findings [] :verify? true}

                   :else
                   {:findings [(f/error :artifact.verification/undeclared-skip path
                                        (str what " would not be verified and the caller did"
                                             " not say so; pass " evidence-key " (" how ")"
                                             " or " decl-key " " unverified
                                             " to declare the skip")
                                        :refs ["D4.3" "D4.11"])]
                    :verify? false})))
        d (pair [:expected-digest] expected-digest :expected-digest digest :digest
                "artifact-digest"
                "from an authenticated deployment control envelope or a signed offline manifest")
        c (pair [:resolve-reference] resolve-reference :resolve-reference closure :closure
                "the reference closure"
                "a fn resolving a closure member's content.edn text")]
    {:findings        (f/sort-findings (concat (:findings d) (:findings c)))
     :verify-digest?  (:verify? d)
     :verify-closure? (:verify? c)}))

(defn- check-report-echo
  "`report.edn` 的两个回显**参与判定**（D4.7 的对称面）。

   report 里回显 `:artifact/format-version` 与 `:content-hash`，是为了让这份报告
   能自述「我说的是哪一份内容的哪一种格式」。曾经它们在场却不参与任何装载判定
   ——那比没有更糟：它**看起来像门禁**，读的人会以为有人在把关，实际改动它只会
   改 `artifact-digest`。要么删掉，要么让它真的把关。这里选后者：三份文件是被
   一起密封的一组，任何一份的自述与另外两份对不上，都说明制品被人拆开改过。"
  [report content-version recomputed]
  (cond
    (not (map? report))
    [(f/error :artifact.envelope/invalid-shape []
              "report.edn must be a map at the top level"
              :got (f/redact report) :refs ["D4.9"] :files ["report.edn"])]

    :else
    (vec (concat
          (cond
            (not (contains? report :artifact/format-version))
            [(f/error :artifact.envelope/missing-key [:artifact/format-version]
                      "report.edn must echo :artifact/format-version"
                      :refs ["D4.7"] :files ["report.edn"])]

            (seq (env/check-format-version (:artifact/format-version report)
                                           [:artifact/format-version] "report.edn"))
            (env/check-format-version (:artifact/format-version report)
                                      [:artifact/format-version] "report.edn")

            (not= (:artifact/format-version report) content-version)
            [(f/error :artifact.compat/format-version-mismatch [:artifact/format-version]
                      (str "content.edn declares format-version " (pr-str content-version)
                           " but report.edn echoes "
                           (pr-str (:artifact/format-version report))
                           "; the artifact has been hand-edited")
                      :expected content-version :got (:artifact/format-version report)
                      :refs ["D4.7"] :files ["content.edn" "report.edn"])]
            :else [])

          (cond
            (not (contains? report :content-hash))
            [(f/error :artifact.envelope/missing-key [:content-hash]
                      "report.edn must echo the :content-hash it reports on"
                      :refs ["D4.9"] :files ["report.edn"])]

            (not= (:content-hash report) recomputed)
            [(f/error :artifact.integrity/content-hash-mismatch [:content-hash]
                      (str "content.edn hashes to " (f/short-name recomputed)
                           " but report.edn says it reports on "
                           (f/short-name (:content-hash report))
                           "; the three files were not sealed as one set")
                      :expected recomputed :got (:content-hash report)
                      :refs ["D4.3" "D4.9"] :files ["content.edn" "report.edn"])]
            :else [])))))

(defn- read-objects
  "`:objects` 原样交给**同一个方言 reader**（D4.6）。

   方言 reader 的入口收字符串，因此这里把已 parse 的 `:objects` 用**同一个
   规范 writer** 写回文本再读。这不是一次多余的往返：encode 端写下的就是这
   份文本，所以它逐字节相同；而对一份手改过的制品，往返恰好把「方言层根本
   无法书写的东西」暴露在写入端，与 D2.9「目标不能表达的就在写入/导出处
   拒绝」同一条纪律。**关键是模型内容的解析规则仍然只有一份**——
   `read-dialect`。"
  [objects]
  (let [[text err] (c/canonical-str-or-error objects)]
    (if err
      {:model nil
       :findings [(f/error :artifact.model/rejected [:objects]
                           (str "`:objects` is not writable in dialect canonical form: " err)
                           :refs ["D2.12" "D4.6"] :files ["content.edn"])]}
      (let [{:keys [model findings]} (dialect/read-dialect text)]
        {:model model
         :findings
         (if model
           ;; 方言的 warning/info（carried 键点名等）原样带出——它们不是拒绝。
           (vec findings)
           [(f/error :artifact.model/rejected [:objects]
                     (str "the dialect reader rejected `:objects` ("
                          (count (filter #(= :error (:severity %)) findings))
                          " error finding(s)); the dialect findings are in :got")
                     :got (vec (map #(select-keys % [:code :path :message]) findings))
                     :refs ["D4.6"] :files ["content.edn"])])}))))

(defn read-artifact
  "三份文件的文本 → `{:model :content-hash :artifact-digest :references
   :manifest :report :verified :findings}`。

   存在任一 `:error` 时 `:model` 为 nil——错误与产物互斥，不产带病模型
   （finding-v1 §2）。

   opts（两样校验各自**二选一，必须选**，见本 ns docstring）：

   | 给这个 | 或者这个 | 管的是 |
   |---|---|---|
   | `:expected-digest \"sha256:…\"` | `:digest :unverified` | artifact-digest（D4.11） |
   | `:resolve-reference (fn [ref] …)` | `:closure :unverified` | 引用闭包（D4.1/D4.4） |

   content-hash 无需声明——manifest 自带它，**永远**校验。

   `:verified` 如实说明这一次实际验了哪几样，形如
   `{:content-hash :verified :artifact-digest :unverified :references :verified}`。
   用 map 而不是「验过的集合」：集合要求读的人先知道全集才能解释缺席，而
   「没验」正是最需要被看见的那一半。只在装载成功时给出——被拒的装载没有模型
   可供这份保证依附。

   判定顺序**不可换**：声明 → 文件齐全 → 格式版本（三处）→ digest → content-hash
   （三处）→ 信封形状与引用 → 闭包 → 模型。前面的每一步都是后面一步的前提：
   格式版本没谈拢就解析信封，等于用今天的规则读明天的文件；hash 没对上就解析
   模型，等于把被改过的内容当成模型。"
  [texts {:keys [expected-digest resolve-reference] :as opts}]
  (let [decl (check-declarations (or opts {}))]
    (if (seq (:findings decl))
      ;; 调用方契约违规：在契约谈拢之前不对制品下任何结论。
      {:model nil :findings (:findings decl)}
      (let [missing (remove #(string? (get texts %)) c/digest-file-order)]
        (if (seq missing)
          ;; 部分写入的版本不得被装载（D4.8）。三份文件缺一即拒绝——D4.3 的
          ;; 整体完整性覆盖的就是这一组，缺一份时它根本无从计算。
          {:model nil
           :findings (f/sort-findings
                      (for [m missing]
                        (f/error :artifact.integrity/missing-file []
                                 (str "artifact file " m " is missing or unreadable;"
                                      " a partially written version must never load")
                                 :refs ["D4.8" "D4.3"] :files [m])))}
          (let [content-text  (get texts "content.edn")
                recomputed    (c/content-hash-of-text content-text)
                parsed-c      (env/parse content-text)
                parsed-m      (env/parse (get texts "manifest.edn"))
                parsed-r      (env/parse (get texts "report.edn"))
                parse-errs    (vec (concat (:findings parsed-c)
                                           (map #(assoc % :files ["manifest.edn"])
                                                (:findings parsed-m))
                                           (map #(assoc % :files ["report.edn"])
                                                (:findings parsed-r))))
                content       (:data parsed-c)
                manifest      (:data parsed-m)
                report        (:data parsed-r)
                content-ver   (when (map? content) (:artifact/format-version content))
                ;; ① 格式版本：content.edn 是权威位置，manifest 与 report 各回显一份
                version-errs  (when (empty? parse-errs)
                                (vec (concat
                                      (if (map? content)
                                        (if (contains? content :artifact/format-version)
                                          (env/check-format-version
                                           content-ver
                                           [:artifact/format-version] "content.edn")
                                          [(f/error :artifact.envelope/missing-key
                                                    [:artifact/format-version]
                                                    "content.edn must declare :artifact/format-version"
                                                    :refs ["D4.7"] :files ["content.edn"])])
                                        [(f/error :artifact.envelope/invalid-shape []
                                                  "content.edn must be a map at the top level"
                                                  :got (f/redact content)
                                                  :refs ["D4.2"] :files ["content.edn"])])
                                      (when (map? manifest)
                                        (check-manifest-echo manifest content-ver)))))
                stage1        (vec (concat parse-errs version-errs))
                ;; ② 完整性：digest 先于 content-hash——digest 覆盖三份文件，
                ;;    它不符时 manifest 记的 content-hash 本身已不可信。
                stage2        (when (empty? stage1)
                                (vec (concat
                                      (when (:verify-digest? decl)
                                        (check-digest texts expected-digest))
                                      (if (map? manifest)
                                        (check-content-hash manifest recomputed)
                                        [(f/error :artifact.envelope/invalid-shape []
                                                  "manifest.edn must be a map"
                                                  :got (f/redact manifest)
                                                  :refs ["D4.9"] :files ["manifest.edn"])])
                                      ;; report 的两个回显同样参与判定
                                      (check-report-echo report content-ver recomputed))))
                ;; ③ 信封形状与引用清单
                stage3        (when (and (empty? stage1) (empty? stage2))
                                (vec (env/check-shape content)))
                ;; ④ 闭包
                stage4        (when (and (empty? stage1) (empty? stage2) (empty? stage3)
                                         (:verify-closure? decl))
                                (check-reference-closure (:references content)
                                                         resolve-reference))
                ;; ⑤ 模型内容
                objects       (when (and (empty? stage1) (empty? stage2)
                                         (empty? stage3) (empty? stage4))
                                (read-objects (:objects content)))
                all           (f/sort-findings (concat stage1 stage2 stage3 stage4
                                                       (:findings objects)))]
            (if (f/errors? all)
              {:model nil :findings all}
              {:model            (:model objects)
               :content-hash     recomputed
               :artifact-digest  (c/artifact-digest texts)
               :references       (vec (:references content))
               :manifest         manifest
               ;; doc 104 §8.17.7 A/C: optional. Absent → nil, which the
               ;; import path reports as `:package/undeclared` (a warning for
               ;; bundles cut before the key existed) rather than a broken
               ;; artifact. Read straight off the manifest — the reader does
               ;; not re-derive it, it has no sources to derive it from.
               :package          (:package manifest)
               :report           report
               :verified         {:content-hash    :verified
                                  :artifact-digest (if (:verify-digest? decl)
                                                     :verified :unverified)
                                  :references      (if (:verify-closure? decl)
                                                     :verified :unverified)}
               :findings         all})))))))
