(ns open.atlas.common.artifact
  "B3 制品的唯一对外接口。跨模块只许 require 本 ns；
   `open.atlas.common.artifact.impl.*` 视为私有。

   制品是 studio 与 server 之间**唯一的模型内容接口**（D1.4）。它同时承担
   四个角色：装载输入、兼容边界、审计对象、契约凭证。

   ## 公共接口

   ```clojure
   ;; 产出 —— D4.10-a 的两个纯函数，B3 与 B5 共用
   (encode-content model references format-version) ;; => {:content-edn :content-hash :findings}
   (seal-graph content-edn content-hash provenance health diff quality)
                                                    ;; => {:manifest-edn :report-edn
                                                    ;;     :artifact-digest :files :findings}
   ;; 装载 —— D4.6 的 artifact reader
   (read-artifact texts opts)                       ;; => {:model :content-hash :verified … :findings}
   (load-artifact root coords opts)                 ;; 制品架上的一份 → 同上

   ;; 图论 —— D1.12：引用图可达性，studio 与 server 共用同一份遍历
   (graph-traverse root root-children resolve)       ;; => {:order :paths :cycles :missing}

   ;; 落盘 —— D4.8
   (write-artifact! root coords files)              ;; => {:dir :findings}

   ;; 身份 —— D4.3
   (canonical-str v) (content-hash-of-text text) (artifact-digest texts)

   finding-codes
   ```

   ## 三种身份，名称与用途不得互换（D4.3）

   | 名称 | 定义 | 回答的问题 |
   |---|---|---|
   | **graph 版本身份** | `(workspace, graph, version)` | 部署、引用与审计指的是哪个已切版本？ |
   | **内容身份** | `content-hash` | 两个版本的可执行内容是否逐字节相同？ |
   | **制品完整性** | `artifact-digest` | 三份文件是否仍是密封时那一组？ |

   `content-hash` 只覆盖 `content.edn`，直接哈希整份规范化文件的字节；
   `artifact-digest` 覆盖三份文件，**不写回制品**（否则自引用），由不可变的
   制品登记记录保存并随经认证的部署控制信封传输（D4.11）。

   ## 为什么 `encode-content` 与 `seal-graph` 是两个函数而不是一个

   两件事的输入完全不同：编码只吃**内容**（模型 + 引用 + 格式版本），密封还吃
   **发布上下文**（时间、操作者、体检结论、差异、质量指标）。合成一个函数就等于
   让内容身份的计算路径上出现发布上下文，而 D4.3 花整整一节说的就是它们必须
   分开。拆开还让 B5 的 `cut-version` 只能是「组织上下文 + 调同一对函数」，
   而不是第二条序列化路径（D4.10-a）。

   两个函数都是**纯**的：不读时钟、当前用户、环境变量或上一版本。

   ## 本模块不依赖 engine

   制品是模型内容契约，与执行无关。「同一规范化模型直接编译的 env ≡ 经
   encode → seal → 装载后编译的 env」那条编码 conformance（D4.10）住在
   `modules/integration`——那里才允许同时见到 artifact 与 engine。

   契约：docs/design/40-artifacts.md、docs/contracts/dialect-v1.md §4（规范形，
   与 content-hash 同源）、docs/contracts/finding-v1.md。"
  (:require [open.atlas.common.semantic-artifact :as semantic]
            [open.atlas.common.artifact.impl.canonical :as c]
            [open.atlas.common.artifact.impl.envelope :as env]
            [open.atlas.common.artifact.impl.finding :as f]
            [open.atlas.common.artifact.impl.graph :as g]
            [open.atlas.common.artifact.impl.reader :as reader]
            [open.atlas.common.artifact.impl.seal :as seal]
            [open.atlas.common.artifact.impl.store :as store]))

(def format-version
  "v1 的装载方只支持版本 1（D4.7）。"
  env/format-version)

(def artifact-files
  "三份文件的固定名称与顺序（D4.2/D4.3）。"
  c/digest-file-order)

(def finding-codes
  "全部已发布 `artifact.*` code 的注册表（finding-v1 §6）。
   与方言、engine 各自的注册表并列，不共用。"
  f/codes)

(defn finding?
  "x 是否为合法 finding（finding-v1 §2 封闭 schema，code 取本模块注册表）。"
  [x]
  (f/valid? x))

;; --- 身份 -------------------------------------------------------------------

(defn canonical-str
  "任意规范化 EDN 值 → 规范形文本（D4.3，规则与 dialect-v1 §4 同源）。
   全部影响被哈希文本的 print var 在此钉死——见 impl.canonical 的 docstring。"
  [v]
  (c/canonical-str v))

(defn content-hash-of-text
  "`content.edn` 的规范文本 → content-hash（`\"sha256:\" + 64 位小写 hex`）。"
  [content-edn]
  (c/content-hash-of-text content-edn))

(defn artifact-digest
  "`{文件名 → 文本}`（三份齐全）→ artifact-digest（D4.3）。"
  [texts]
  (c/artifact-digest texts))

;; --- 产出：D4.10-a 的两个纯函数 ---------------------------------------------

(defn encode-content
  "`encode_content(规范化模型, references, format-version)` → `content.edn` + content-hash。

   `model` 是 `open.atlas.common.dialect/read-dialect` 产出的归一模型，作为
   `:objects` **原样嵌入**——`content.edn` 不定义第二套模型形状（D4.2）。
   `references` 按 `(workspace, graph, version)` 字典序排序后写入，因此菜谱里
   的引用顺序不进入内容语义，也不会无故改变 content-hash。

   => `{:content-edn 文本|nil :content-hash …|nil :envelope …|nil :findings [...]}`。
   存在 `:error` 时不产文本（finding-v1 §2：错误与产物互斥）。"
  ([model references] (encode-content model references format-version))
  ([model references fmt]
   (let [{:keys [envelope findings]} (env/build model (vec (or references [])) fmt)]
     (if (or (nil? envelope) (f/errors? findings))
       {:content-edn nil :content-hash nil :envelope nil :findings findings}
       (let [[text err] (c/canonical-str-or-error envelope)]
         (if err
           {:content-edn nil :content-hash nil :envelope nil
            :findings (f/sort-findings
                       (conj findings
                             (f/error :artifact.envelope/invalid-shape [:objects]
                                      (str "content.edn is not writable in canonical form: "
                                           err)
                                      :refs ["D2.12" "D4.3"] :files ["content.edn"])))}
           {:content-edn  text
            :content-hash (c/content-hash-of-text text)
            :envelope     envelope
            :findings     findings}))))))

(defn seal-graph
  "`seal_graph(content.edn, content-hash, provenance, health-findings,
   previous-version-diff, quality-metrics)`
   → `manifest.edn` + `report.edn` + artifact-digest。

   全部发布上下文显式入参——纯函数不得读取时钟、当前用户、环境变量或上一
   版本（D4.10-a）。`provenance` 至少含 `:workspace :graph :version :cut-at
   :cut-by`。

   `package` 是**可选**的第七个入参（doc 104 §8.17.7 A）：

   ```edn
   {:name     \"open-atlas/dvd\"
    :requires {:vars [\"NIFI_CLIENT_STORE_PASS\"] :files [\"studio/nifi-client.p12\"]}
    :compat   {:dialect \"v1\" :connectors {\"sql\" \"v1\"}}}
   ```

   传了就写进 `manifest.edn` 的 `:package`（因而进 `artifact-digest`）；不传或
   nil 时**这个键根本不书写**，manifest 与本键出现之前逐字节相同——冻结的黄金
   向量因此仍然复现。块的内容由调用方算（`spi/package-requirements` /
   `spi/package-compat`）：连接器知识不进 artifact（D1.8）。

   => `{:manifest-edn :report-edn :files :artifact-digest :findings}`。"
  ([content-edn content-hash provenance health diff quality]
   (seal/seal-graph content-edn content-hash provenance health diff quality))
  ([content-edn content-hash provenance health diff quality package]
   (seal/seal-graph content-edn content-hash provenance health diff quality package)))

(def package-keys
  "`:package` 块的键面（doc 104 §8.17.7 A），封闭。导出给写入端与导入端共用
   同一份，不各写一份。"
  seal/package-keys)

;; --- 装载：D4.6 的 artifact reader -------------------------------------------

(def health-facts
  "三份文本 → S5.7 门禁要的事实：`{:content-hash :row-security-declared
   :finding-set-hash}`，manifest 读不出来则 nil。

   **纯读，不做装载校验**——装载语义只有一处，就是 `read-artifact`。这里回答的
   是「这一版声明过 `:row-security` 吗、它这份体检结论的身份是什么」,
   而豁免正是绑在那个身份上的（S5.7）。"
  seal/health-facts)

(defn read-artifact
  "三份文件的文本 → 装载结果。**无 IO**：黄金向量与 conformance 用例不碰磁盘。

   两样校验各自**二选一，必须选**——默认沉默地少做一道校验是静默降级，
   而 D4.3/D4.11 说的是「必须」：

   | 给这个 | 或者这个 | 管的是 |
   |---|---|---|
   | `:expected-digest \"sha256:…\"` | `:digest :unverified` | artifact-digest（D4.11） |
   | `:resolve-reference (fn [ref] …)` | `:closure :unverified` | 引用闭包（D4.1/D4.4） |

   两者都不给 → 结构化拒绝（`:artifact.verification/undeclared-skip`）；
   两者都给 → 结构化拒绝（`:artifact.verification/contradictory-declaration`）。
   content-hash 无需声明，**永远**校验：manifest 自带它，可自证。

   返回值的 `:verified` 如实说明这一次实际验了哪几样。

   任一文件或引用 hash 对不上 → `:model` 为 nil，findings 点名哪个文件、
   哪条规则、哪个对象（D4.6）。"
  ([texts] (read-artifact texts {}))
  ([texts opts] (if (semantic/format-two? texts)
                  (semantic/read-runtime texts (or opts {}))
                  (reader/read-artifact texts (or opts {})))))

(defn load-artifact
  "**server 的最小制品装载形态**（B3 交付）：制品架上的一份 → 模型。

   `root` 是 `<artifact-root>`，`coords` 是 `{:workspace :graph :version}`。

   引用闭包默认在同一个制品架上解析（D4.1「装载闭包」= 一个 graph 制品 + 其
   传递引用的全部制品），因此闭包那一样**默认就是已声明的**。调用方自己传了
   `:resolve-reference` 或 `:closure` 时，默认让位——否则注入的默认会和调用方
   的声明撞成一次「自相矛盾的声明」，而那本该是调用方真的写错时才出现的诊断。

   digest 那一样**没有默认**：期望值只能来自已认证的部署控制信封或离线签名
   清单（D4.11），制品架上没有可信的自报值可用。调用方必须给
   `:expected-digest`，或显式写下 `:digest :unverified`。

   路径全部相对于 `root` 解析，**绝不相对于进程 `cwd`**（D4.8）。
   这里不做 server 进程、不做部署协议——B3 的交付止于「能装载并交出模型」。"
  ([root coords] (load-artifact root coords {}))
  ([root coords opts]
   (let [{:keys [dir findings]} (store/version-dir root coords)]
     (if (seq findings)
       {:model nil :findings findings}
       (let [opts (or opts {})
             opts (if (or (contains? opts :resolve-reference) (contains? opts :closure))
                    opts
                    (assoc opts :resolve-reference (store/shelf-reader root)))]
         (read-artifact (store/read-texts dir) opts))))))

;; --- 图论：D1.12 引用图可达性 -------------------------------------------------

;; The per-path **ceiling tracker** retired with access tiers (owner ruling
;; 2026-08-27): a ceiling was the strictest tier reachable along a path
;; (R3.8/D5.5), and with no tiers there is nothing to take a minimum of. Its
;; traversal half — `graph-traverse` — stays; it was never about tiers.


(defn graph-traverse
  "`(fn [root root-children resolve])` → 引用图的可达性事实（D1.12）。

   纯图论：可达节点集、每个节点的全部路径、环、缺失节点，全部是**事实**，
   不是 finding——studio 与 server 各自的 finding domain 不同，这里不产生
   任何一条。调用方拿着事实自己判断（studio 判 R3.4/R3.8，server 判 hash/
   digest）。参数与返回形状见 `open.atlas.common.artifact.impl.graph/traverse`
   的 docstring。"
  [root root-children resolve]
  (g/traverse root root-children resolve))

;; --- 落盘：D4.8 --------------------------------------------------------------

(defn portable-segment?
  "制品架路径段是否合法（artifact-v1 §8：`[A-Za-z0-9][A-Za-z0-9._@-]*`）。

   摆在接口上是因为**判定只能有一份**（D1.8）：拒绝在 `write-artifact!` /
   `version-dir` 里发生，而 studio 的 workspace 改名要在动手之前用同一条规则
   警告，两边各写一个正则就是两个「合法」的定义。"
  [s]
  (store/portable-segment? s))


(defn read-shelf-texts
  "制品架上一个版本目录 → `{文件名 → 文本}`。**只读字节，不做任何校验**——
   装载语义只有一处，就是 `read-artifact`（它要 `:expected-digest` 与
   `:resolve-reference`，而那两样制品架给不出）。

   缺席或不可读的文件在结果里就是缺席：调用方据此判断「三份齐不齐」。"
  [root coords]
  (store/read-shelf-texts root coords))

(defn write-artifact!
  "三份文件原子落盘到 `<root>/<workspace>/<graph>/<version>/`。
   `files` 用 `seal-graph` 的 `:files`——写下去的与算过 digest 的是同一份文本。

   已存在的版本目录是拒绝而不是合并；部分写入的版本不会出现（先写临时目录、
   再对目录做一次原子 move）。=> `{:dir File :findings []}` 或 `{:findings [...]}`。"
  [root coords files]
  (store/write-artifact! root coords files))
