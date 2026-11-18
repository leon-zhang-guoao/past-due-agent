(ns open.atlas.runtime.graph.impl.store
  "**本模块唯一碰 IO 的 ns**（同池、同 artifact 的分工）：九个读、七种 effect
   的落盘、以及启动恢复。判定不在这里——它在 impl.command，纯的；租约与原子写
   原语也不在这里——它们在 `runtime/store`，整台 instance 共用一份。

   ## 磁盘布局

   ```
   <root>/control.lock                                    整台 instance 的租约（runtime/store）
   <root>/deployments/<hash(deployment-id)>.edn           已认证的 closure plan（写一次）
   <root>/staging/<64hex>/{三份文件}                       内容寻址
   <root>/staging/<64hex>/stage-receipt.edn               evidence，**在三文件之外**
   <root>/artifacts/<ws>/<graph>/<version>/…              制品架（复用 common/artifact）
   <root>/receipts/<ws>/<graph>/<version>.edn             loaded 的 commit marker
   <root>/events/<ws>/<graph>/<hash(event-id)>.edn        不可变事件
   <root>/pointers/<ws>/<graph>.edn                       activated 的 commit marker
   <root>/requests/<hash(request-id)>.edn                 幂等索引
   ```

   **哪些段名 hash、哪些不 hash**：`workspace`/`graph`/`version` 照 D3.10 是人要
   读要 grep 的东西（「身份要出现在制品路径、部署指针、审计与指标标签里」），所以
   原样用，并按与 artifact 相同的白名单**拒绝**非法段——不是净化。
   `request-id`/`deployment-id`/`event-id` 是**外部字符串**，只被要求非空，可以含
   `/` 或 `..`：它们一律取 SHA-256 hex 作段名，原值存在文件内容里。

   ## 底座在 `runtime/store`

   租约、poisoned、原子落盘原语与子系统锁**不在这里**——它们属于整台 instance,
   而 token 台账写在同一个 root 下（owner 裁定 2026-08-25，③-1）。各写一份的
   后果见那个模块的 ns 注释；这里只负责**本子系统的布局、effect 与恢复**。

   进程内按**限定 graph 名**加锁（键 `[:graph qn]`），不同 graph 可并行。
   锁防的是并发写，`:expected-current` 的 CAS 防的是陈旧请求与逻辑重放——
   **两者不是替代关系**。

   ## 写失败即停写

   任何一条 effect 落盘失败，**整台 instance** 进入 poisoned：此后一切写命令
   直接拒绝，直到恢复完成。带着半应用状态继续服务，比停下来更坏。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.runtime.graph.impl.command :as cmd]
            [open.atlas.runtime.graph.impl.finding :as f]
            [open.atlas.runtime.store :as rs])
  (:import [java.io File]))

(defn- safe-segment! [x what] (rs/safe-segment! x what))
(defn- write-edn! [^File file value] (rs/write-edn! file value))
(defn- write-dir! [^File dir files] (rs/write-dir! dir files))
(defn- read-edn [^File file] (rs/read-edn file))

;; ---------------------------------------------------------------------------
;; 路径
;; ---------------------------------------------------------------------------

(defn- ->file ^File [root & segs] (apply io/file root segs))

(defn- graph-parts
  "`\"sales/app\"` → `[\"sales\" \"app\"]`，两段都过白名单。"
  [qualified]
  (let [i (.indexOf ^String qualified "/")]
    (when (pos? i)
      [(safe-segment! (subs qualified 0 i) "workspace")
       (safe-segment! (subs qualified (inc i)) "graph")])))

(defn- deployment-file [root id] (->file root "deployments" (str (cmd/path-segment id) ".edn")))
(defn- request-file    [root id] (->file root "requests"    (str (cmd/path-segment id) ".edn")))
(defn- staging-dir     [root hex] (->file root "staging" (safe-segment! hex "staging digest")))
(defn- shelf-root      [root] (->file root "artifacts"))

(defn- receipt-file [root {:keys [workspace graph version]}]
  (->file root "receipts" (safe-segment! workspace "workspace") (safe-segment! graph "graph")
          (str (safe-segment! (str version) "version") ".edn")))

(defn- pointer-file [root qualified]
  (let [[ws g] (graph-parts qualified)]
    (->file root "pointers" ws (str g ".edn"))))

(defn- event-file [root qualified event-id]
  (let [[ws g] (graph-parts qualified)]
    (->file root "events" ws g (str (cmd/path-segment event-id) ".edn"))))

;; ---------------------------------------------------------------------------
;; 租约
;; ---------------------------------------------------------------------------

(defrecord GraphStore [instance last-recovery])

(defn open!
  "在一个**已打开的 instance** 上建本子系统的句柄。

   **不再自己开租约**：`control.lock` 由 `runtime/store` 在进程启动时取一次,
   graph 与 token 拿的是同一个 instance（owner 裁定 2026-08-25，③-1）。同一个
   JVM 里各开一次的话，第二次 `tryLock` 不会像跨进程那样被拒——「单写进程」
   这条保证会在最需要它的那一刻悄悄失效。"
  [instance]
  (->GraphStore instance (atom {})))

(defn instance [store] (:instance store))
(defn root ^File [store] (rs/root (:instance store)))
(defn lease-held? [store] (rs/lease-held? (:instance store)))

(defn- writable! [store] (rs/writable-findings (:instance store)))

;; ---------------------------------------------------------------------------
;; 九个读
;; ---------------------------------------------------------------------------

(defn reads
  "命令核心要的九个读函数。**只读**，一个都不写。"
  [store]
  (let [root (root store)]
  ;; **The read hands back the plan itself, not the file.** The file also
  ;; carries the deployment id and the evidence of who authenticated this plan,
  ;; which belong on disk for audit; the command core asks this read for
  ;; `:root` and `:closure` and nothing else. Handing it the wrapper made both
  ;; of those nil — every plan looked rooted nowhere and empty of members.
  {:deployment      (fn [id] (:plan (read-edn (deployment-file root id))))
   :request         (fn [id] (read-edn (request-file root id)))
   :event           (fn [qn eid] (read-edn (event-file root qn eid)))
   :staged-artifact (fn [hex]
                      (let [d (staging-dir root hex)]
                        (when (.isDirectory d)
                          (let [m (into {} (for [n artifact/artifact-files
                                                 :let [file (File. d ^String n)]
                                                 :when (.isFile file)]
                                             [n (slurp file :encoding "UTF-8")]))]
                            (when (= (count artifact/artifact-files) (count m)) m)))))
   :shelf-artifact  (fn [coords]
                      (let [m (artifact/read-shelf-texts (shelf-root root) coords)]
                        (when (= (count artifact/artifact-files) (count m)) m)))
   :receipt         (fn [coords] (read-edn (receipt-file root coords)))
   :pointer         (fn [qn] (read-edn (pointer-file root qn)))
   ;; 钥匙串与 ACL 由装配方注入——store 不认识密钥从哪来（文件、环境变量、KMS
   ;; 都可能），也不该认识。
   :actor-key       (constantly nil)
   :service-allows? (constantly false)}))

;; ---------------------------------------------------------------------------
;; 七种 effect
;; ---------------------------------------------------------------------------

(defn- apply-effect! [store {:effect/keys [kind] :as e}]
  (let [root (root store)]
   (case kind
    :write-deployment
    (write-edn! (deployment-file root (:deployment-id e))
                {:deployment-id (:deployment-id e) :plan (:plan e) :evidence (:evidence e)})

    ;; 内容目录与 evidence **分两次落**：三文件的 digest 是身份，evidence 塞进去
    ;; 就改了身份。目录先落（它是 marker），evidence 随后补在旁边。
    :stage-artifact
    (let [d (staging-dir root (:digest-hex e))]
      (write-dir! d (:files e))
      (write-edn! (File. d "stage-receipt.edn")
                  {:artifact-digest (:digest e) :evidence (:evidence e)}))

    :land-artifact
    (let [{:keys [findings]} (artifact/write-artifact! (shelf-root root) (:coords e) (:files e))]
      (when (seq findings) (throw (ex-info "landing refused" {::findings findings}))))

    :write-receipt (write-edn! (receipt-file root (:coords e)) (:receipt e))
    :append-event  (write-edn! (event-file root (:graph e) (:event-id e)) (:event e))
    :set-pointer   (write-edn! (pointer-file root (:graph e)) (:pointer e))
    :write-request (write-edn! (request-file root (:request-id e))
                               (assoc (:record e) :request-id (:request-id e))))))

(defn apply-effects!
  "按序施加。**任何一条失败，进程中毒**：此后拒绝一切写，直到恢复跑过。
   顺序是命令给的，内容在前、marker 在后——半途失败留下的是「有目录无 receipt」
   或「有 event 未被 head 引用」，两者都不被当作成功状态。"
  [store effects]
  ;; **闸在这里也要有一道**，不只在 `handle!` 里：一个绕过编排直接落盘的调用点
  ;; 迟早会出现（测试、修复脚本、下一个人），而中毒之后继续写正是这道闸要防的。
  (if-let [fs (seq (writable! store))]
    {:findings (vec fs)}
    (try
      (doseq [e effects] (apply-effect! store e))
      {:findings []}
      (catch Throwable t
        (rs/poison! (:instance store)
                    {:effect (:effect/kind (ex-data t)) :message (.getMessage t)})
        {:findings (or (::findings (ex-data t))
                       (when-let [x (rs/finding-of t)] [x])
                       [(f/make :instance.lease/lost :error []
                                "an effect failed to land; this process now refuses writes"
                                :got (.getMessage t))])}))))

;; ---------------------------------------------------------------------------
;; 启动恢复
;; ---------------------------------------------------------------------------

(defn- walk-chain
  "从 head 沿 `previous-event-id` 走回去。→ `{:events [事件…]}` 或 `{:broken …}`。"
  [root qn head-id]
  (loop [id head-id, acc [], seen #{}]
    (cond
      (nil? id)        {:events acc}
      (contains? seen id) {:broken {:reason :cycle :at id}}
      :else
      (if-let [ev (read-edn (event-file root qn id))]
        (recur (:previous-event-id ev) (conj acc ev) (conj seen id))
        {:broken {:reason :missing :at id}}))))

(defn recover!
  "启动恢复。**在接受任何命令之前跑一次。**

   1. 清扫 temp（崩在原子 move 之前的残留）；
   2. 逐 graph 验事件链：head 必须存在、`previous` 链必须闭合。断了就**隔离该
      graph 并报告**，不服务——一条走不通的链意味着「现在指着的这一版是怎么来的」
      答不出来；
   3. **从 pointer 可达的事件回填缺失的幂等索引**。这一条不补的话：事件已写、
      指针已切、`write-request` 前崩溃、重启后别的事件又推进了 head——原请求重放
      时既没有 request 记录、也不再是当前 head，于是收到一次**假 CAS 冲突**。
      **只回填 pointer 可达的事件**：孤儿事件是未提交残留，回填它等于把没提交的
      东西认成提交过的；
   4. 报告「有目录无 receipt」的制品——**不自动修复**：那需要 plan 与重算 digest,
      是 `:load` 的活。它们本来也不算已装载（「已装载」的定义就是目录 + 匹配的
      receipt），所以放着是安全的，只是要被看见。

   → `{:swept n :quarantined [graph…] :backfilled n :orphan-artifacts [坐标…] :findings […]}`"
  [{:keys [instance last-recovery] :as store}]
  (let [root  (root store)
        swept (rs/sweep-temps! root)
        ptr-dir (io/file root "pointers")
        pointers (for [^File f (file-seq ptr-dir)
                       :when (and (.isFile f) (str/ends-with? (.getName f) ".edn"))
                       :let [ws (.getName (.getParentFile f))
                             g  (str/replace (.getName f) #"\.edn$" "")]]
                   [(str ws "/" g) (read-edn f)])
        results (for [[qn ptr] pointers]
                  (let [r (walk-chain root qn (:head-event-id ptr))]
                    [qn ptr r]))
        broken  (vec (for [[qn _ r] results :when (:broken r)] qn))
        backfilled (atom 0)]
    ;; ③ 回填
    (doseq [[_ _ r] results
            ev (:events r)
            :let [rf (request-file root (:request-id ev))]
            :when (and (:request-id ev) (not (.isFile rf)))]
      (write-edn! rf {:request-id    (:request-id ev)
                      :envelope-hash (:envelope-hash ev)
                      :op            (:op ev)
                      :result        (:result ev)
                      :service-principal (:service-principal ev)
                      :actor-principal   (:actor-principal ev)
                      :accepted-at   (:accepted-at ev)
                      :backfilled-from-event (:event/id ev)})
      (swap! backfilled inc))
    ;; ④ 有目录无 receipt
    (let [orphans (vec (for [^File vd (file-seq (shelf-root root))
                             :when (and (.isDirectory vd)
                                        (.isFile (File. vd "content.edn")))
                             :let [version (.getName vd)
                                   g  (.getName (.getParentFile vd))
                                   ws (.getName (.getParentFile (.getParentFile vd)))
                                   coords {:workspace ws :graph g :version (parse-long version)}]
                             :when (and (:version coords)
                                        (not (.isFile (receipt-file root coords))))]
                         coords))]
      (rs/clear-poison! instance)
      (reset! last-recovery {:quarantined broken})
      {:swept swept
       :quarantined broken
       :backfilled @backfilled
       :orphan-artifacts orphans
       :findings
       (f/sort-findings
        (vec (concat
              (for [qn broken]
                (f/error :deploy.pointer/broken-chain [:pointers]
                         (str "this graph's event chain does not close, so \"how did the "
                              "version it points at get here\" has no answer; the graph is "
                              "quarantined until someone looks")
                         :got qn))
              (for [c orphans]
                (f/make :deploy.load/receipt-conflict :warning [:artifacts]
                        (str "artifact bytes with no receipt — debris from a load that did not "
                             "finish. It is NOT counted as loaded (that requires directory AND "
                             "matching receipt), so it is safe where it is; the next :load for "
                             "this coordinate recovers it")
                        :got c)))))})))

(defn loaded?
  "这台机器上，`(graph, version)` **算不算已装载**。

   判据只有一条，且这一条是本模块定义的：**制品目录在 + receipt 在 + receipt
   记的 digest 与架上的对得上**。有目录无 receipt 是上一次没跑完的残留（那个
   期望 digest 当初来自哪份已认证信封，没有证据）；有 receipt 无目录是字节没了。

   公开出去是因为别的子系统要问这个问题——`runtime/token` 签发钉版本 grant 前
   要确认「这一版本机真的有」（owner 裁定 2026-08-25）。它**不该自己去看目录**:
   那样「已装载」就有了第二个定义，而两个定义迟早会分岔。"
  [store graph version]
  (let [reads  (reads store)
        coords (cmd/qualified->coords graph version)]
    (boolean
     (when coords
       (let [r ((:receipt reads) coords)]
         (and r ((:shelf-artifact reads) coords) (some? (:artifact-digest r))))))))

(defn serving-version
  "这台机器上该 graph 的 **serving / 部署指针**指向的版本号，没有则 nil。
   `:latest` 的唯一定义（R3.6）——不是版本号最大者，也不是最新切出的那一版。"
  [store graph]
  (:version ((:pointer (reads store)) graph)))

(defn stop-serving!
  "Local owner action that clears one Graph's serving pointer with a CAS guard.
   The previous event head stays attached to the nil pointer so recovery and a
   later explicit activation retain the deployment history. This does not
   remove artifacts, receipts, events, deployments, requests, or tokens."
  [store graph {:keys [expected-current stopped-at stopped-by]}]
  (if-let [findings (seq (writable! store))]
    {:findings (vec findings)}
    (rs/with-lock
     (:instance store) [:graph graph]
     (let [file (pointer-file (root store) graph)
           current (read-edn file)
           current-version (:version current)]
       (cond
         (nil? current-version)
         {:graph graph :from nil :serving nil :findings []}

         (not= expected-current current-version)
         {:findings [(f/error :deploy.activate/stale-expectation
                              [:expected-current]
                              "the serving pointer changed; reload before stopping service"
                              :expected expected-current :got current-version)]}

         :else
         (do
           (write-edn! file {:version nil
                             :head-event-id (:head-event-id current)
                             :stopped-at stopped-at
                             :stopped-by stopped-by})
           {:graph graph :from current-version :serving nil :findings []}))))))

(defn unload-version!
  "Retract the local load receipt of one version that is NOT serving (R3.5
   reclaim). Without its receipt the version is no longer loaded; the artifact
   bytes left behind are the known «directory without receipt» debris until
   the caller removes them. Events, deployments and requests are kept."
  [store graph version]
  (if-let [findings (seq (writable! store))]
    {:findings (vec findings)}
    (rs/with-lock
     (:instance store) [:graph graph]
     (if (= version (serving-version store graph))
       {:findings [(f/error :deploy.reclaim/serving [:version]
                            "this version is the one being served; move the pointer first"
                            :got version)]}
       (let [coords (cmd/qualified->coords graph version)]
         (when coords (io/delete-file (receipt-file (root store) coords) true))
         {:graph graph :version version :findings []})))))

(defn receipt-digest
  "该版本 receipt 里记着的 `artifact-digest`，没有 receipt 则 nil。
   物化时的可信期望值取自这里（artifact-v1 §11 第 8 条）。"
  [store graph version]
  (when-let [coords (cmd/qualified->coords graph version)]
    (:artifact-digest ((:receipt (reads store)) coords))))

(defn shelf-texts
  "该版本在本机制品架上的三份文本，缺一则给回不齐的那份（调用方据此判断）。"
  [store graph version]
  (if-let [coords (cmd/qualified->coords graph version)]
    ((:shelf-artifact (reads store)) coords)
    {}))

(defn shelf-metadata
  "Read review metadata without loading the potentially large content payload.
   This is a console projection, never an authorization or activation check."
  [store graph version]
  (when-let [{:keys [workspace graph version]} (cmd/qualified->coords graph version)]
    (let [dir (clojure.java.io/file (shelf-root (root store)) workspace graph (str version))]
      {:manifest (read-edn (clojure.java.io/file dir "manifest.edn"))
       :report (read-edn (clojure.java.io/file dir "report.edn"))})))

(defn shelf-versions
  "本机制品架上的全部 `{:graph … :version n}`。回收判定要先知道「架上有什么」
   才谈得上「谁还指着它」（R3.5）。"
  [store]
  (let [shelf (shelf-root (root store))]
    (into #{}
          (for [^File vd (file-seq shelf)
                :when (and (.isDirectory vd) (.isFile (File. vd "content.edn")))
                :let [v  (parse-long (.getName vd))
                      g  (.getName (.getParentFile vd))
                      ws (.getName (.getParentFile (.getParentFile vd)))]
                :when v]
            {:graph (str ws "/" g) :version v}))))

(defn graph-uuid
  "Read the immutable UUID from the authorized, loaded version's manifest.
   Verify all artifact bytes against the trusted receipt before using identity;
   this is an identity read, not a second compilation or closure validation."
  [store graph version-spec]
  (when-let [version (if (integer? version-spec) version-spec (serving-version store graph))]
    (when-let [digest (receipt-digest store graph version)]
      (let [texts (shelf-texts store graph version)]
        (when (and (= (set artifact/artifact-files) (set (keys texts)))
                   (= digest (artifact/artifact-digest texts)))
          (try
            (let [id (:graph/id (edn/read-string (get texts "manifest.edn")))]
              (when (and (string? id)
                         (re-matches #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" id))
                (str (java.util.UUID/fromString id))))
            (catch Exception _ nil)))))))

(defn references-of
  "该版本 `content.edn` 里的直接引用 → `[{:graph … :version n}…]`。

   **digest 仍然验，闭包声明为不验**：这里在**枚举**而不是在装载——问的是
   「谁指着谁」，不是「这一份能不能跑」。两样都必须显式声明（artifact-v1 §11
   第 8 条），默认沉默地少做一道校验才是静默降级。"
  [store graph version]
  (let [texts (shelf-texts store graph version)
        dig   (receipt-digest store graph version)]
    (when (= (count artifact/artifact-files) (count texts))
      (let [{:keys [references]}
            (artifact/read-artifact texts (if dig
                                            {:expected-digest dig :closure :unverified}
                                            {:digest :unverified :closure :unverified}))]
        (vec (for [r references]
               {:graph (str (:workspace r) "/" (:graph r)) :version (:version r)}))))))

(defn closure-facts
  "root 版本 → 它**整个传递装载闭包**每一个成员的门禁事实，或 nil（有成员不在
   架子上）。

   ```clojure
   [{:graph … :version n :content-hash … :row-security-declared […]
     :finding-set-hash …}…]
   ```

   S5.7 说门禁「检查整个传递装载闭包，而不只看根 graph」——被引用图里一个声明了
   `:row-security` 的 resolver，经合并同样会进这份 env 的 index（R3.2）,
   只看根等于漏掉一半入口。

   遍历用 `artifact/graph-traverse`，与装载、与 studio 切版本是**同一份图论**
   （D1.12）：两处对「闭包是哪些」答得不一样，是最难查的那类事故。"
  [store graph version]
  (let [missing? (atom false)
        facts    (atom {})
        resolve*
        (fn [k _path]
          (let [[qn v] k
                texts  (shelf-texts store qn v)]
            (if-not (= (count artifact/artifact-files) (count texts))
              (do (reset! missing? true) nil)
              (do (swap! facts assoc k (assoc (artifact/health-facts texts)
                                              :graph qn :version v))
                  (vec (for [r (references-of store qn v)]
                         [(:graph r) (:version r)]))))))
        {:keys [order missing cycles]}
        (artifact/graph-traverse ::root [[graph version]] resolve*)]
    (when-not (or @missing? (seq missing) (seq cycles))
      (vec (for [k (sort-by pr-str order)] (get @facts k))))))

(defn quarantined?
  "该 graph 是否在上一次恢复里被隔离。隔离期间拒绝对它的写。"
  [store qn]
  (contains? (set (:quarantined @(:last-recovery store))) qn))

;; ---------------------------------------------------------------------------
;; 按 graph 串行
;; ---------------------------------------------------------------------------

(defn graph-monitor
  "该 graph 的进程内监视器。键按子系统加了命名空间（`[:graph qn]`）——
   与 token 共用同一个 instance 的锁注册表，裸字符串会让两个子系统互相串行。

   **公开是因为 `with-graph-lock` 是宏**：展开后的代码住在调用方的 ns 里,
   private 就引用不到。"
  [store qn]
  (rs/monitor (:instance store) [:graph qn]))

(defmacro with-graph-lock
  "进程内按限定 graph 名串行；不同 graph 并行。**锁防并发写，CAS 防陈旧请求,
   两者不是替代关系**（owner 裁定 2026-08-24）。"
  [store qn & body]
  `(locking (graph-monitor ~store ~qn) ~@body))

(defn writable-findings [store] (writable! store))
