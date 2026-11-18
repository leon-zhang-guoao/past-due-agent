(ns open.atlas.runtime.env
  "R3.2 on one instance: a loaded closure becomes **one** merged, compiled
   execution environment. Cross-module callers may require only this ns;
   `open.atlas.runtime.env.impl.*` is private.

   ```
   root 版本 → 读本机制品架 → 解析完整引用闭包
            → 确定性合并 → 编译一次
   ```

   ## 它编排，不新造语义

   | 借的是 | 谁的 |
   |---|---|
   | artifact reader、引用图遍历 | `common/artifact`（D1.12 修订） |
   | 多模型确定性合并与冲突拒绝 | `common/dialect` |
   | 合并模型 → 一份 compiled env | `common/engine` |
   | 制品架、receipt、serving 指针 | `runtime/graph`，**经注入的三个读能力** |

   注入而不是直接依赖 `runtime/graph`，是为了让这一层能在没有制品架的情况下被
   测（同 `runtime/token` 的两个能力）。装配点把两边接起来，**studio 内嵌的那台
   runtime 与独立 Server 接的是同一份**——不为 studio 另写一条物化路径（D1.13）。

   ## 边界的另一半：它不做什么

   不在 `runtime/graph` 里顺手编译；不在 `runtime/token` 里物化；**不逐个引用
   制品各编一份再拼 compiled env**（那会切断跨图的解析路径，而引用存在的理由
   正是那条路径）；graph 之间不做运行时网络调用；**不读 token 对被引用 graph 的
   独立 grant**——本次请求的档位由调用方交进来的那一个 grant 决定（D5.5）。

   ## 一次请求固定一次版本快照

   `for-request` 先把版本说明解析成**具体版本号**，之后整个请求都用它。
   `:latest` 在请求中途被一次部署改掉时，这一次请求仍然完整地跑在它开始时看到
   的那一版上——半个请求换一张图，比慢一拍更难解释。

   Contract: docs/contracts/finding-v1.md。域 `env.*`；`artifact.*` 与 `dialect.*`
   原样回落，不重贴一层皮。"
  (:require [open.atlas.common.engine :as engine]
            [open.atlas.common.semantic-result :as semantic-result]
            [open.atlas.runtime.env.impl.cache :as cache]
            [open.atlas.runtime.env.impl.semantic :as semantic]
            [open.atlas.runtime.env.impl.closure :as closure]
            [open.atlas.runtime.env.impl.finding :as finding]))

(defrecord EnvStore [instance caps limits resolve-source cache])

(def ^:private default-cache-ttl-ms
  "Two hours (8.4.20 裁定十四). **The one number both doors use**: the data
   plane's `[graph version]` cache lives here, the Builder's draft cache lives
   in `apps/studio`'s `platform-runtime`, and that app reads this function
   rather than keeping a second default — the runtime module cannot depend on
   the app, so the shared value lives on the side that both can see."
  7200000)

(defn- positive-long [value]
  (try (let [parsed (Long/parseLong (str value))] (when (pos? parsed) parsed))
       (catch Exception _ nil)))

(defn cache-ttl-ms
  "The configured idle TTL for a cached environment, in milliseconds.

   `OPEN_ATLAS_GRAPH_ENV_CACHE_TTL_MS` has millisecond precision and wins;
   `OPEN_ATLAS_GRAPH_ENV_CACHE_TTL_SECONDS` is the friendlier deployment
   setting. Invalid, zero or negative values fall back to two hours."
  []
  (or (some-> (System/getenv "OPEN_ATLAS_GRAPH_ENV_CACHE_TTL_MS") positive-long)
      (some-> (System/getenv "OPEN_ATLAS_GRAPH_ENV_CACHE_TTL_SECONDS") positive-long (* 1000))
      default-cache-ttl-ms))

(defn open!
  "`(open! instance {:shelf-texts … :receipt-digest … :serving-version … :limits {}})`
   → `{:store s :findings []}` 或 `{:findings […]}`（**拒绝启动**）。

   三个读能力，都按**限定名 + 版本**取，所以这一层既不解析 `<workspace>/<graph>`
   也不认识制品架的目录结构：

   - `:shelf-texts`     `(fn [graph version] → {文件名 文本})`
   - `:receipt-digest`  `(fn [graph version] → \"sha256:…\"|nil)`
   - `:serving-version` `(fn [graph] → 版本号|nil)`

   缺任何一个都不启动：没有它们，物化就只能靠猜「这一版在不在、当初是按哪个
   digest 装进来的」，而那两件正是装载期已经答过、不该再猜一遍的事。

   `:resolve-source` `(fn [source-id entry] → entry)` 是**可选**的第四个能力：
   把模型里声明的 source 换成这台 instance 真开得出的连接。凭据是 instance
   本地状态（D1.13），模型里只留 `${VAR}` 名字，所以宿主持有 store 时要把它
   交进来。缺席 = 按模型原样连——没有 store 的宿主本来就该那样。

   `:cache-ttl-ms` 覆盖缓存的空闲过期（缺省 `cache-ttl-ms`，见 8.4.20 裁定十四 ①）,
   `:clock` 覆盖它读的时钟——两者都只为测试而在。"
  [instance {:keys [shelf-texts receipt-digest serving-version limits resolve-source clock]
             ttl-ms :cache-ttl-ms}]
  (if-not (and (ifn? shelf-texts) (ifn? receipt-digest) (ifn? serving-version))
    {:findings [(finding/error :env.capabilities/missing [:capabilities]
                               "materialising a closure needs this instance's shelf, its
                                receipts and its serving pointers; without all three the
                                runtime would be guessing at what load time already settled")]}
    {:store (->EnvStore instance
                        {:shelf-texts shelf-texts :receipt-digest receipt-digest
                         :serving-version serving-version}
                        limits resolve-source
                        ;; 8.4.20 裁定十四 ①: idle expiry, TTL from the shared
                        ;; configuration value. `:clock` is for tests only —
                        ;; ageing a cache without sleeping two hours.
                        (cache/new-cache {:ttl-ms (or ttl-ms (cache-ttl-ms))
                                          :clock clock}))
     :findings []}))

(defn resolve-version
  "版本说明 → 具体版本号。`:latest` 走**本机 serving 指针**（R3.6），不是版本号
   最大者、也不是最新切出的那一版。"
  [{:keys [caps]} graph version-spec]
  (if (integer? version-spec)
    {:version version-spec}
    (if-let [v ((:serving-version caps) graph)]
      {:version v}
      {:findings [(finding/error :env.version/unresolved [:version]
                                 "this graph has no serving pointer on this instance, so
                                  :latest resolves to nothing (R3.6)"
                                 :got graph)]})))

(defn materialize
  "`(materialize store graph version)` → `{:env compiled :members […]}` 或
   `{:findings […]}`。**懒**：第一次被需要时才编译，之后缓存；同一个键并发命中
   只编译一次（single-flight）。"
  [{:keys [instance caps limits resolve-source cache] :as store} graph version]
  (cache/get-or-materialize!
   instance cache [graph version]
   #(closure/compile-closure caps graph version
                             {:limits limits :resolve-source resolve-source})))

(defn for-model
  "`(for-model model)` / `(for-model model {:limits … :resolve-source …})`
   → `{:env compiled}`.

   The same builder `for-request` ends in, entered one step later: a model
   handed in rather than read off this instance's shelf. `for-request` reads
   the version, resolves the closure, merges it, and finishes HERE — so the
   Builder's draft (on no shelf, and therefore unable to enter through
   `for-request`) and a published artifact are compiled by literally the same
   code (8.4.18 裁定十二 ①).

   Takes a `store`'s options rather than the store: this door owns no cache
   and no version pinning — a draft has no version to pin, and caching a model
   the caller minted would need a key only the caller can compute. A host
   holding a store passes its `:resolve-source` so that a draft connects
   through the same credentials a served version would."
  ([model] (for-model model nil))
  ([model opts] (closure/compile-model model (or opts {}))))

(defn for-request
  "`(for-request store {:graph-id … :version-spec …})`
   → `{:version n :env compiled :members […]}` 或 `{:findings […]}`。

   **版本快照在这里固定一次**，此后整个请求都用它。"
  [store {:keys [graph-id graph version-spec]}]
  (let [graph-id (or graph-id graph)
        r (resolve-version store graph-id version-spec)]
    (if (seq (:findings r))
      r
      (let [m (materialize store graph-id (:version r))]
        (if (:env m) (assoc m :version (:version r)) m)))))

(defn query
  "`(query store {:graph-id … :authorization a :request …})` → result or findings.
   Authorization supplies the immutable graph UUID/version; no access level is
   part of either the request or the compiled environment."
  [store {:keys [graph-id graph authorization grant request stats? lenient? trace caller]}]
  (let [graph-id (or graph-id graph)
        authorization (or authorization
                          (when grant {:graph-id graph-id :version (:version grant)}))
        r (for-request store {:graph-id graph-id :version-spec (:version authorization)})]
    (if (seq (:findings r))
      r
      ;; `:stats?` / `:lenient?` 原样转给 engine（它的第四个参数）。两者都默认关,
      ;; 省略 opts 时返回形状与以前逐字节相同。**转发而不是自己解释**：
      ;; engine 的文档说 `:stats?` 只是「别丢掉本来就收了的东西」,
      ;; 这里再定义一遍它的语义就是第二个答案。
      (let [response (engine/query (:env r) nil request
                                   (cond-> {} stats? (assoc :stats? true)
                                     lenient? (assoc :lenient? true) trace (assoc :trace trace)
                                     ;; D1.9b: vouched by answer, never by the request.
                                     caller (assoc :caller caller)))
            checked (when (:document r)
                      (semantic-result/validate-execution-result (get-in r [:document :vocabulary]) response))]
        (assoc (if checked (or (:response checked) (select-keys checked [:findings])) response)
               :env/version (:version r))))))

(def invalidate!
  "丢掉缓存（全部，或一个 `[限定名 版本]`）。**不是正确性手段**——键是具体版本,
   而版本不可变，所以指针变更不需要它；它是给腾内存与回收用的。"
  (fn ([store] (cache/invalidate! (:cache store)))
      ([store k] (cache/invalidate! (:cache store) k))))

(defn held
  "缓存当前握着哪些 `[限定名 版本]`。R3.5 的「env/cache retention」那一项要它。"
  [store]
  (cache/held (:cache store)))

(def finding-codes finding/codes)
(def all-finding-codes finding/all-codes)
(defn finding? [x] (finding/valid? x))


(def semantic-preview-capabilities
  "Currently executable capabilities; declared writes have a separate host-authorized entry."
  semantic/preview-capabilities)

(def for-semantic-document
  "Validated semantic document plus explicit adapters/deployment connections → candidate env.
   Shared by Studio and immutable candidate verification; does not authenticate or activate."
  semantic/compile-document)

(def for-semantic-artifact
  "Format-2 bytes and externally trusted digest → candidate env using the same semantic compiler.
   Host options contain adapters, connections and exact snapshot. No live serving pointer is changed."
  semantic/compile-artifact)

(def for-semantic-graph-package
  "Verified self-contained Contract 1.4 Graph package plus explicit
   [graph-id source-slot] deployment bindings → one compiled Runtime Graph."
  semantic/compile-package)

(def query-semantic-candidate
  "Read-query an already compiled semantic candidate. Host must authorize access first."
  semantic/query)


(def execute-semantic-candidate
  "Execute one declared operation. The host must authorize Graph write access."
  semantic/execute)
