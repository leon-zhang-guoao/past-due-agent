(ns open.atlas.common.artifact.impl.seal
  "`seal_graph`（D4.10-a）：`content.edn` + 发布上下文 → `manifest.edn` +
   `report.edn` + `artifact-digest`。

   ## 纯函数，发布上下文全部显式入参

   「纯函数不得读取时钟、当前用户、环境变量或上一版本，调用者取到这些值后
   作为参数传入，测试才能完整复现」（D4.10-a）。本 ns 因此**没有**任何对
   `System/currentTimeMillis`、`System/getenv`、`System/getProperty` 的调用；
   `:cut-at`、`:cut-by` 都是入参。B5 的 `cut-version` 只组织池投影、解析闭包、
   跑体检、生成显式上下文，然后调用**同一个** sealer——它不是另一种序列化路径。

   ## 两份文件的分工（D4.9）

   - `manifest.edn` 只带**机器**装载或治理要判定的事实：content-hash、格式
     回显、provenance、结构完整性结论、carried-only 键清单、声明了
     `:row-security` 而当前不执法的 resolver 清单（D5.6）、引用带入的
     `:restricted` 属性清单（D5.5）。
   - `report.edn` 带**面向人**的质量指标与相对上一版本的差异。装载不依赖它。

   同一个指标只允许一个权威位置：manifest 记门禁的结构化结论，详细度量只在
   report。

   ## 为什么这两份文件不进 content-hash，却仍必须被保护

   切版本时间、操作者、差异报告与质量指标都是**发布上下文**：同一份对象在
   周二切和周四切内容一模一样，若时间进 hash，两次得到两个身份，
   「这两个版本是不是同一个东西」就不可判定（D4.3）。但不进内容身份不等于
   可以被无声替换——`artifact-digest` 覆盖三份文件，正是为此。"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [open.atlas.common.artifact.impl.canonical :as c]
            [open.atlas.common.artifact.impl.envelope :as env]
            [open.atlas.common.artifact.impl.finding :as f]))

(def provenance-keys
  "provenance 键面，封闭（D4.5：只记「哪个 workspace、哪次切版本、什么时候」，
   不搬运池的事务日志）。

   `:object-revisions` 是 E3（owner 2026-09-03）加的**可选**键：这一版的每个
   对象在池里是第几版。D4.5 的界限没有移动——进来的是**指针**（对象 → revision），
   不是事务日志本身；日志仍住在池里，这里只记它的行号，于是「这次查询用的版本里
   这个 resolver 是谁改的」能从制品走到统一审计而不必查池的库。"
  #{:workspace :graph :version :cut-at :cut-by :object-revisions})

(def ^:private required-provenance-keys
  ;; `:object-revisions` 不在必填里，而且**永远不该**在：这个键之前密封的制品
  ;; 一个都没有它，把它列为必填等于让那些制品从今天起装载不了——一次为了新事实
  ;; 而作废既往身份的改动（D4.3 正是要避免的）。
  #{:workspace :graph :version :cut-at :cut-by})

(defn- check-object-revisions
  "`:object-revisions` 的形状：`{[类型 id] 正整数}`。

   键是**对象键**，与池的 `[类型 id]` 同形——id 按类型分别是 keyword（属性）、
   字符串（注册表）或二元向量（link），所以这里只钉「二元向量、第一格是
   keyword」，不去替池重述它的 id 规则（那会长出第二套语义，D1.8）。

   值是**正整数**：0 在池里意思是「不存在过」，而一个进了投影的对象存在过。
   拒绝而非放行，理由同别处——一个被静默接受的坏形状，会在半年后被当成
   「这个对象是第 0 版」读。"
  [rev]
  (cond
    (nil? rev) []

    (not (map? rev))
    [(f/error :artifact.envelope/invalid-shape [:provenance :object-revisions]
              ":object-revisions must be a map of [type id] → revision"
              :got (f/redact rev) :refs ["D4.5"] :files ["manifest.edn"])]

    :else
    (let [bad-keys (remove #(and (vector? %) (= 2 (count %)) (keyword? (first %)))
                           (keys rev))
          bad-vals (remove #(and (integer? (val %)) (pos? (val %))) rev)]
      (cond
        (seq bad-keys)
        [(f/error :artifact.envelope/invalid-shape [:provenance :object-revisions]
                  (str ":object-revisions keys must be [type id] pairs: "
                       (pr-str (vec (sort-by str bad-keys))))
                  :refs ["D4.5"] :files ["manifest.edn"])]

        (seq bad-vals)
        [(f/error :artifact.envelope/invalid-shape [:provenance :object-revisions]
                  (str ":object-revisions values must be positive integers; "
                       "0 means «never existed» and a projected object existed: "
                       (pr-str (vec (sort-by str (map key bad-vals)))))
                  :refs ["D4.5"] :files ["manifest.edn"])]

        :else []))))

(def health-input-keys
  "调用方**可以传入**的体检键面（D4.9 的四项结论）。全部是入参：B3 阶段没有池，
   算不出「全池视野」的结论，正确性归 B5 的 `cut-version`；B3 承诺的是键面与
   书写纪律。这与 D4.10-a 的纯函数纪律一致。"
  ;; E1 / D5.14 added `:row-security-verified`: the subset of the declared ids
  ;; whose wiring the cut-time check verified. It is an **input** for the same
  ;; reason `:row-security-declared` is — B3 has no model to re-derive it from
  ;; (D4.1: the health conclusion is sealed by whoever reached it). The gate
  ;; reads both: declared-minus-verified is exactly what it must refuse on.
  #{:findings :carried-only-keys :row-security-declared :row-security-verified})

(def derived-health-keys
  "**推导**出来的体检键，调用方不得传入。

   `:structural-ok?` 是同一批 findings 的一句话结论——存在任一 `:severity :error`
   即 false。允许调用方另行声明一遍，就是让同一个事实有两个权威位置，而两个位置
   迟早会相互矛盾，届时装载方不知道该信哪个（D4.9「同一个指标只允许一个权威
   位置」）。曾经的实现在调用方显式传值时采信调用方，于是一份
   `:structural-ok? true` 却带着 error findings 的 manifest 是可以被密封出来的
   ——那正是这条禁令要防的事，而且是现役实例。

   传了就**结构化拒绝**，不静默忽略：静默忽略会让调用方以为自己的设置生效了，
   而它其实被丢掉——那是另一种形式的静默降级。"
  #{:structural-ok?})

(def health-keys
  "体检块在 `manifest.edn` 里**写出来**的全部键（D4.9）。每个键总是书写（哪怕是
   空清单）：缺席与空在这里语义不同——空清单是「查过，没有」，缺席是「不知道
   查没查过」，而 server 无法重算这些结论（D4.1）。"
  (into health-input-keys derived-health-keys))

(def diff-keys
  "report 的差异块键面，封闭（D4.9）。`:previous-version` 为 nil 表示首个版本。"
  #{:previous-version :added-attributes :removed-attributes :modified-objects})

(defn- sort-stable
  "语义无序的清单先按稳定对象键排序（D4.3）——不能把 map 遍历或体检发现
   顺序带入 digest。去重同时发生：同一个键出现两次不该让 digest 变。"
  [xs]
  (vec (sort-by pr-str (distinct (or xs [])))))

(defn- check-map [m path file allowed required label]
  (cond
    (not (map? m))
    [(f/error :artifact.envelope/invalid-shape path (str label " must be a map")
              :got (f/redact m) :refs ["D4.9"] :files [file])]

    (seq (remove allowed (keys m)))
    [(f/error :artifact.envelope/unknown-key path
              (str "unknown key(s) in " label ": "
                   (pr-str (vec (sort-by str (remove allowed (keys m))))))
              :expected (vec (sort-by str allowed))
              :refs ["D4.9" "D4.6"] :files [file])]

    (seq (remove #(contains? m %) required))
    [(f/error :artifact.envelope/missing-key path
              (str "missing key(s) in " label ": "
                   (pr-str (vec (sort-by str (remove #(contains? m %) required)))))
              :expected (vec (sort-by str required))
              :refs ["D4.9"] :files [file])]

    :else []))

(defn health-facts
  "三份文本 → S5.7 门禁要的那几样事实。**纯读，不校验**——装载语义只有一处
   （impl.reader），这里回答的是「这一版声明过 `:row-security` 吗、它的身份是
   什么」。

   ```clojure
   {:content-hash          <sha256 十六进制>
    :row-security-declared [<resolver id>…]
    :row-security-verified [<resolver id>…]
    :finding-set-hash      <sha256 十六进制>}
   ```

   `:finding-set-hash` 是**体检清单**的身份。S5.7 说豁免要绑
   `(instance, 限定名, version, content-hash, finding-set-hash)`,
   「任何重切版本、内容变化、**体检清单变化**都要求重新确认」——体检清单变了
   意味着这一版被判定的结论变了，而豁免是针对当初那份结论点的头。

   算在**归一后**的 findings 上（`normalize-health` 已排过序），所以同一份结论
   任何时候算出同一个值。"
  [texts]
  (let [m (try (edn/read-string (get texts "manifest.edn")) (catch Throwable _ nil))
        h (:health m)]
    (when (map? m)
      {:content-hash          (:content-hash m)
       :row-security-declared (vec (or (:row-security-declared h) []))
       ;; E1 / D5.14: the gate's new criterion is declared-minus-verified.
       ;; An artifact sealed before E1 carries no such key, and `(or … [])`
       ;; makes that "nothing verified" — the closed direction: such a graph
       ;; is refused until it is re-cut. Failing open here would silently
       ;; grandfather in every unchecked declaration there is.
       :row-security-verified (vec (or (:row-security-verified h) []))
       :finding-set-hash      (c/content-hash-of-text
                               (first (c/canonical-str-or-error
                                       (vec (or (:findings h) [])))))})))

(defn- normalize-health
  "体检结论归一：findings 按 finding-v1 §6 排序，三张清单按稳定对象键排序。

   `:structural-ok?` **永远**从 `:findings` 推导，没有第二条路径（见
   derived-health-keys）：存在任一 `:severity :error` 即 false。"
  [health]
  (let [findings (vec (or (:findings health) []))]
    {:structural-ok?              (not (f/errors? findings))
     ;; 排序键与 finding-v1 §6 **逐字一致**：按 `:path` 字典序、同 path 按
     ;; `:code`。这里曾经多带一段 `:message`，为的是让同 path 同 code 的两条
     ;; finding 顺序也确定、digest 不漂移。那是收紧不是冲突，但两处文字不一致
     ;; 本身就是将来分叉的种子——要收紧就去改 finding-v1，不在这里偷偷加。
     ;; 代价明说：`sort-by` 稳定，故同 path 同 code 的并列项保持**入参顺序**，
     ;; 这一段的确定性因此由调用方负责（B5 的体检需产出稳定顺序）。
     :findings                    (vec (sort-by (juxt (comp pr-str :path)
                                                      (comp str :code))
                                                findings))
     :carried-only-keys           (sort-stable (:carried-only-keys health))
     :row-security-declared       (sort-stable (:row-security-declared health))
     ;; E1 / D5.14. Always written, empty list included: absent would mean
     ;; "this artifact predates the check", and the gate must not read that as
     ;; "nothing passed" — nor as "everything did".
     :row-security-verified       (sort-stable (:row-security-verified health))}))

(defn- check-quality
  "`:quality` 收「**非空** `{keyword → any}`」。

   两难：设计没有定义质量指标的键面，封闭键面会挡住 B5 的真实度量；完全不校验
   又与「每类条目 schema 封闭」的通则不一致。按既有先例处理——dialect-v1 §3
   carried 汇总里，resolver/mutation 的 `:row-security`（键面归 D5.6）与 source 的
   `:resilience`（键面归 D6.2）就是这么收的：键面尚未定形的块，收「`{keyword →
   any}`」，把「是一张以 keyword 为键的表」这一层钉住，把内部键留给定形它的那个
   阶段。

   **非空**这一层同样来自那条先例，并且自己站得住：空 map 与「没算」不可区分，
   而 report 的 `:quality` 是部署前 owner 要看的那份东西（D4.9）。要表达「这次
   没有度量」，写一个说得出口的键，不要交一张空表。"
  [quality]
  (cond
    (not (map? quality))
    [(f/error :artifact.envelope/invalid-shape [:quality]
              "quality-metrics must be a map" :got (f/redact quality)
              :refs ["D4.9"] :files ["report.edn"])]

    (empty? quality)
    [(f/error :artifact.envelope/invalid-shape [:quality]
              (str "quality-metrics must not be empty; an empty map is "
                   "indistinguishable from \"not measured\"")
              :refs ["D4.9"] :files ["report.edn"])]

    (seq (remove keyword? (keys quality)))
    [(f/error :artifact.envelope/invalid-shape [:quality]
              (str "quality-metrics keys must all be keywords: "
                   (pr-str (vec (sort-by pr-str (remove keyword? (keys quality))))))
              :expected "{keyword → any}"
              :refs ["D4.9" "dialect-v1 §3"] :files ["report.edn"])]

    :else []))

(defn- normalize-diff [diff]
  {:previous-version   (:previous-version diff)
   :added-attributes   (sort-stable (:added-attributes diff))
   :removed-attributes (sort-stable (:removed-attributes diff))
   :modified-objects   (sort-stable (:modified-objects diff))})

(def package-keys
  "The `:package` block's key face, closed (doc 104 §8.17.7 A):

     :package {:name     \"open-atlas/dvd\"
               :requires {:vars [\"NIFI_CLIENT_STORE_PASS\"] :files [\"studio/x.p12\"]}
               :compat   {:dialect \"v1\" :connectors {\"sql\" \"v1\"}}}

   The block is **optional**: a manifest sealed without one is byte-identical
   to what this sealer wrote before doc 104, which is what keeps the frozen
   golden vectors valid (fixtures/README.md — the envelope did not change, an
   optional key was added to what a caller may seal into it)."
  #{:name :requires :compat})

(def ^:private package-requires-keys #{:vars :files})

(defn- check-package
  "Shape of the optional `:package` block. nil → no findings (absent is the
   pre-doc-104 shape and stays legal, 8.17.7 C: an old bundle is warned about
   at install time, not refused at seal time).

   `:requires` carries **names and paths only** — never a resolved value. This
   sealer cannot tell a name from a value, so it enforces what it can (strings)
   and the derivation stays with `spi/package-requirements`, which never
   resolves anything."
  [package]
  (cond
    (nil? package) []

    (not (map? package))
    [(f/error :artifact.envelope/invalid-shape [:package] "package must be a map"
              :got (f/redact package) :refs ["doc 104 §8.17.7"] :files ["manifest.edn"])]

    :else
    (let [{:keys [name requires compat]} package]
      (vec
       (concat
        (check-map package [:package] "manifest.edn" package-keys #{:name} "package")
        (when (and (contains? package :name) (not (and (string? name) (seq (str/trim name)))))
          [(f/error :artifact.envelope/invalid-shape [:package :name]
                    "package :name must be a non-blank string (e.g. \"open-atlas/dvd\")"
                    :got (f/redact name) :refs ["doc 104 §8.17.7 D"]
                    :files ["manifest.edn"])])
        (when (some? requires)
          (concat
           (check-map requires [:package :requires] "manifest.edn"
                      package-requires-keys #{} "package :requires")
           (when (map? requires)
             (for [k [:vars :files]
                   :when (and (contains? requires k)
                              (not (and (sequential? (get requires k))
                                        (every? string? (get requires k)))))]
               (f/error :artifact.envelope/invalid-shape [:package :requires k]
                        (str "package :requires " k " must be a list of strings"
                             " (names and paths, never resolved values)")
                        :got (f/redact (get requires k))
                        :refs ["doc 104 §8.17.7 A"] :files ["manifest.edn"])))))
        (when (and (some? compat) (not (map? compat)))
          [(f/error :artifact.envelope/invalid-shape [:package :compat]
                    "package :compat must be a map"
                    :got (f/redact compat) :refs ["doc 104 §8.17.7 A"]
                    :files ["manifest.edn"])]))))))

(defn- normalize-package
  "`:requires` lists sorted + de-duplicated, for the same reason every other
   list in this file is (D4.3): map iteration order must not reach the digest.
   `:compat` is left as given — its values are versions, not a set."
  [package]
  (when (some? package)
    (cond-> package
      (map? (:requires package))
      (update :requires
              (fn [r]
                (reduce (fn [acc k]
                          (if (contains? acc k)
                            (update acc k #(vec (sort (distinct %))))
                            acc))
                        r [:vars :files]))))))

(defn seal-graph
  "`seal_graph(content.edn, content-hash, provenance, health-findings,
   previous-version-diff, quality-metrics[, package])`
   → `{:manifest-edn :report-edn :artifact-digest :files :findings}`。

   `:files` 是 `{文件名 → 文本}` 的三元组，供落盘与 digest 复算共用同一份
   文本——不让「写下去的」与「算过的」有机会分叉。

   `package` is the OPTIONAL 7th input (doc 104 §8.17.7 A). Given, it is
   written into `manifest.edn` under `:package` and therefore covered by
   `artifact-digest` like everything else in that file; nil or omitted, the
   manifest is **byte-identical** to what the six-arg call has always written
   — the key is not written at all. Both cut paths (studio's and the server's
   `example.clj`) call this one function, so neither can grow its own block
   (8.17.6's lesson)."
  ([content-edn content-hash provenance health diff quality]
   (seal-graph content-edn content-hash provenance health diff quality nil))
  ([content-edn content-hash provenance health diff quality package]
   (let [findings
        (vec (concat
              (when-not (string? content-edn)
                [(f/error :artifact.envelope/invalid-shape []
                          "content.edn text must be a string"
                          :got (f/redact content-edn) :files ["content.edn"])])
              (when-not (c/hash-str? content-hash)
                [(f/error :artifact.integrity/content-hash-mismatch [:content-hash]
                          "content-hash must be \"sha256:\" + 64 lowercase hex digits"
                          :got (f/redact content-hash)
                          :refs ["D4.3"] :files ["manifest.edn"])])
              (check-map provenance [:provenance] "manifest.edn"
                         provenance-keys required-provenance-keys "provenance")
              (when (map? provenance)
                (check-object-revisions (:object-revisions provenance)))
              (when (map? health)
                (concat
                 ;; 未知键用**写出来的**全部键面判定，而不是可传入的那一份：
                 ;; 否则 `:structural-ok?` 会同时挨一条 unknown-key 与一条
                 ;; derived-key-supplied，同一个事实报两遍，读的人得自己去
                 ;; 判断哪一条才是真正的毛病。
                 (check-map health [:health] "manifest.edn"
                            health-keys #{} "health")
                 (for [k (filter #(contains? health %) derived-health-keys)]
                   (f/error :artifact.envelope/derived-key-supplied [:health k]
                            (str k " is derived from :findings and is not an input;"
                                 " remove it from the health map — supplying a value would"
                                 " make it a second authoritative place for the same fact")
                            :got (get health k)
                            :refs ["D4.9"] :files ["manifest.edn"]))))
              (when-not (map? health)
                [(f/error :artifact.envelope/invalid-shape [:health]
                          "health must be a map" :got (f/redact health)
                          :refs ["D4.9"] :files ["manifest.edn"])])
              (when (map? diff)
                (check-map diff [:diff] "report.edn" diff-keys #{} "diff"))
              (when-not (map? diff)
                [(f/error :artifact.envelope/invalid-shape [:diff]
                          "previous-version-diff must be a map" :got (f/redact diff)
                          :refs ["D4.9"] :files ["report.edn"])])
              (check-quality quality)
              (check-package package)))]
     (if (f/errors? findings)
       {:findings (f/sort-findings findings)}
       (let [manifest (cond-> {:artifact/format-version env/format-version ; D4.7 回显
                               :content-hash            content-hash       ; manifest 不自引用
                               :provenance              provenance
                               :health                  (normalize-health health)}
                        ;; doc 104 §8.17.7 A. `some?`, not truthiness of a
                        ;; merge: with no package the key is ABSENT, so a
                        ;; manifest sealed today is byte-identical to one
                        ;; sealed before this key existed.
                        (some? package)
                        (assoc :package (normalize-package package)))
             report   {:artifact/format-version env/format-version
                       :content-hash            content-hash
                       :diff                    (normalize-diff diff)
                       :quality                 quality}
             [m-text m-err] (c/canonical-str-or-error manifest)
             [r-text r-err] (c/canonical-str-or-error report)
             write-errs (vec (concat
                              (when m-err
                                [(f/error :artifact.envelope/invalid-shape [:provenance]
                                          (str "manifest.edn is not canonically writable: " m-err)
                                          :refs ["D4.3"] :files ["manifest.edn"])])
                              (when r-err
                                [(f/error :artifact.envelope/invalid-shape [:quality]
                                          (str "report.edn is not canonically writable: " r-err)
                                          :refs ["D4.3"] :files ["report.edn"])])))]
         (if (seq write-errs)
           {:findings (f/sort-findings write-errs)}
           (let [files {"content.edn"  content-edn
                        "manifest.edn" m-text
                        "report.edn"   r-text}]
             {:manifest-edn    m-text
              :report-edn      r-text
              :files           files
              :artifact-digest (c/artifact-digest files)
              :findings        []})))))))
