(ns open.atlas.runtime.graph
  "一台 instance 上 graph 的生命周期（D3.12 / D3.16 / D1.13）：制品架、serving 指针、
   五个命令。

   **两种形态跑的是这一份**：独立 Server 与 studio 内嵌的那台 runtime instance
   （D1.13）。studio 切出制品之后走的是同一条命令边界——同进程内直接调 `decide`/
   `handle!` 是允许的（省一次 HTTP 往返），但绕过校验、审计、闭包检查与原子激活不是。

   跨模块只许 require 本 ns；`open.atlas.runtime.graph.impl.*` 视为私有。

   ## 五个命令

   `:offer` 送闭包清单（**含根**），回缺哪些 → `:stage` 送缺的字节 → `:load`
   装载一个根版本（R3.3 的依赖检查落在**这里**）→ `:activate` 原子切指针 /
   `:rollback` 切回一个**显式**目标。

   ## 判定是纯的，写是描述出来的

   `decide` 吃一组注入的读函数与一份信封，产出 `{:result :effects :findings}`;
   `:effects` 是「要落哪些字节」的描述，由 impl.store 施加。CAS 冲突、plan 被改、
   digest 对不上、闭包缺件，全都是一次函数调用就能断言的事——不必铺目录、
   不必起进程（D3.16 / D1.6）。

   ## 这台机器只有一个写进程

   一个 instance 由一个共享 root 定义。v1 对该 root **只允许一个写进程**：
   启动时必须取得 root 级独占租约（`FileChannel/tryLock`，终身持有），取不到就
   **拒绝启动**，不降级成「大家小心别同时写」。数据面进程可以有多个，但只能经
   原子 pointer 快照读，不得直接写制品/receipt/event/pointer。多控制面进程与
   HA leader election 不属于 v1。

   **那把租约属于整台 instance，不属于本模块**（`runtime/store`，owner 裁定
   2026-08-25）：token 台账写在同一个 root 下，两个子系统各开一次锁的话,
   同一个 JVM 内的第二次 `tryLock` 不会像跨进程那样被拒。"
  (:require [clojure.java.io]
            [open.atlas.runtime.obs :as obs]
            [open.atlas.runtime.store :as rs]
            [open.atlas.runtime.graph.impl.command :as command]
            [open.atlas.runtime.graph.impl.finding :as finding]
            [open.atlas.runtime.graph.impl.store :as store]))

(def decide
  "`(decide reads env {:instance-id … :service-principal … :now …})`
   → `{:result :effects :findings}`。**纯**：给定 `reads` 的返回值与 `ctx`,
   结果确定（不读时钟——`:now` 是传进来的）。"
  command/execute)

(def path-segment
  "外部 ID → 可安全当路径段用的 64 位 hex。`request-id`/`deployment-id` 是外部
   字符串，可以含 `/` 或 `..`；落盘用这个，原始 ID 存在文件内容里。"
  command/path-segment)

(def qualified->coords command/qualified->coords)
(def digest-hex        command/digest-hex)
(def plan-hash         command/plan-hash)

;; ---------------------------------------------------------------------------
;; store：租约、读、落盘、恢复
;; ---------------------------------------------------------------------------

(def open!
  "`(open! instance)` → 本子系统在这台 instance 上的句柄。

   **租约不在这里取**：`control.lock` 由 `runtime/store` 在进程启动时取一次,
   graph 与 token 拿的是**同一个已打开的 instance**（owner 裁定 2026-08-25，③-1）。
   同一个 JVM 里各开一次的话，第二次 `tryLock` 不会像跨进程那样被拒——
   「每台 instance 只有一个写进程」这条保证会在最需要它的那一刻悄悄失效。

   关闭是 instance 的事（`runtime.store/close!`），不是本子系统的。"
  store/open!)

(def instance    "本句柄所在的 instance。" store/instance)
(def lease-held? store/lease-held?)
(def recover!     "启动恢复。**接受任何命令之前跑一次**。" store/recover!)
(def store-reads  "命令核心要的九个读函数里，store 供得起的那七个。" store/reads)

;; --- 给别的子系统的公开读接口 ------------------------------------------------
;;
;; token 要问「这一版装了吗」，env 要问「latest 指哪一版、当初按哪个 digest 装的」。
;; 它们**不自己去看目录**（owner 裁定 2026-08-25）：那样「已装载」「latest」就各有
;; 了第二个定义，而两个定义迟早分岔。参数一律是**限定名 + 版本**，所以调用方
;; 也不需要解析 `<workspace>/<graph>`。

(def loaded?
  "`(loaded? store graph version)` —— 目录在 + receipt 在 + digest 记得住。
   三者缺一都不算（见 impl.store）。"
  store/loaded?)

(def serving-version
  "`(serving-version store graph)` —— 本机该图的 serving 指针指向的版本号。
   这是 `:latest` 的**唯一**定义（R3.6）。"
  store/serving-version)

(def stop-serving!
  "Clear the local serving pointer after an explicit owner action. Artifacts,
   deployment evidence, and tokens are retained."
  store/stop-serving!)

(def receipt-digest
  "`(receipt-digest store graph version)` —— 装载时记下的可信 digest。"
  store/receipt-digest)

(def shelf-texts
  "`(shelf-texts store graph version)` —— 制品架上的三份文本。"
  store/shelf-texts)

(def shelf-metadata "Review metadata, without reading content.edn." store/shelf-metadata)

(def unload-version!
  "`(unload-version! store graph version)`  — retract the load receipt of a non-serving version (R3.5 reclaim)."
  store/unload-version!)

(def shelf-versions
  "`(shelf-versions store)` —— 架上的全部 `{:graph … :version n}`（R3.5 的第一个输入）。"
  store/shelf-versions)

(def graph-uuid
  "Immutable UUID of the token-authorized version. The runtime coordinate may
   still be workspace/name; this UUID travels in the sealed artifact and is
   the same in Studio and a standalone Server. Nil if identity cannot be verified."
  store/graph-uuid)

(def closure-facts
  "`(closure-facts store graph version)` —— 整个传递闭包每一成员的 S5.7 门禁事实,
   有成员不在架子上则 nil。"
  store/closure-facts)

(def references-of
  "`(references-of store graph version)` —— 该版本的直接引用坐标。
   枚举用，digest 仍然验、闭包声明为不验。"
  store/references-of)
(def apply-effects! "按序施加 effect。任何一条失败 → 进程中毒，此后拒绝一切写。" store/apply-effects!)

(defn lifecycle-records [graph-store]
  (or (rs/read-edn (clojure.java.io/file (rs/root (instance graph-store)) "graph-lifecycle.edn")) {}))

(defn lifecycle-state [graph-store qn]
  (get-in (lifecycle-records graph-store) [qn :state] :active))

(defn set-lifecycle-state! [graph-store qn state {:keys [actor now]}]
  (let [inst (instance graph-store)]
    (rs/with-lock inst :graph-maintenance
      (when (seq (rs/writable-findings inst))
        (throw (ex-info "Runtime is not writable" {:code :graph-write-refused})))
      (let [file (clojure.java.io/file (rs/root inst) "graph-lifecycle.edn")
            records (lifecycle-records graph-store)]
        (when-not (contains? #{:active :archived :deleted} state)
          (throw (ex-info "Invalid Graph state" {:code :graph-state-invalid})))
        (let [version (last (sort (keep #(when (= qn (:graph %)) (:version %)) (shelf-versions graph-store))))
              uuid (or (when version (graph-uuid graph-store qn version)) (get-in records [qn :graph-uuid]))]
          (rs/write-edn! file (assoc records qn {:state state :actor actor :at now :graph-uuid uuid})))
        (obs/audit-record! (rs/subsystem-dir inst "obs")
                          {:v 1 :event :graph/lifecycle :op state :at now :graph qn
                           :instance-id (rs/instance-id inst) :outcome :ok
                           :actor {:on-behalf-of actor :asserted-by :verified}})
        {:graph-id qn :state state}))))

(defn- handle-command!
  "一条控制命令，从头到尾：**租约闸 → graph 串行 → 判定 → 施加**。

   `reads` 由装配方给——通常是 `(merge (store-reads store) {:actor-key … :service-allows? …})`:
   钥匙串与 ACL 从哪来（文件、环境变量、KMS）不是 store 该认识的事。

   → `{:result … :findings […]}`。判定不过就一个字节都不写；施加中途失败,
   进程中毒、此后拒绝一切写，直到 `recover!` 跑过。"
  [store reads env ctx]
  (let [qn (get-in env [:control/payload :graph])
        audited! (fn [outcome extra]
                   ;; 8.10 P6（owner 裁定 2026-08-31，方案 a）：**引用而不复制**。
                   ;; 权威记录是下面那条链式 event——它带二元 principal、信封哈希、
                   ;; previous-event-id，还承重（指针带 head event-id）。这里只往
                   ;; 审计流水里写一条精简事件并用 :event-id 指过去，这样
                   ;; 「这个人这一天做了什么」有一个地方能问，而不会多出第二个权威。
                   (try
                     (obs/audit-record!
                      (rs/subsystem-dir (store/instance store) "obs")
                      (merge {:v 1
                              :event      :graph/control
                              :op         (:control/op env)
                              :at         (:now ctx)
                              :request-id (:control/request-id env)
                              :instance-id (:instance-id ctx)
                              :entry      :internal
                              :outcome    outcome
                              :actor      {:service      (:service-principal ctx)
                                           :on-behalf-of (get-in env [:control/actor :actor/principal])
                                           :asserted-by  :verified}
                              :graph      qn}
                             extra))
                     (catch Throwable _ nil)))]
    (if-let [fs (seq (store/writable-findings store))]
      (do (audited! :denied {:reason (:code (first fs))})
          {:findings (vec fs)})
      (if (and qn (store/quarantined? store qn))
        (do
          (audited! :denied {:reason :deploy.pointer/broken-chain})
          {:findings [(finding/error :deploy.pointer/broken-chain [:control/payload :graph]
                                   (str "this graph is quarantined: its event chain did not "
                                        "close at startup, so how the version it points at got "
                                        "here has no answer")
                                     :got qn)]})
        (store/with-graph-lock store (or qn ::global)
          (let [{:keys [result effects findings] :as out} (command/execute reads env ctx)]
            (if (seq findings)
              (do (audited! :denied {:reason (:code (first findings))})
                  {:findings findings})
              (let [applied (store/apply-effects! store effects)]
                (if (seq (:findings applied))
                  (do (audited! :error {:error (:message (first (:findings applied)))})
                      applied)
                  (do (audited! :ok (cond-> {:result result}
                                      ;; the authoritative record this refers to
                                      (:control/request-id env)
                                      (assoc :event-id (:control/request-id env))))
                      (cond-> {:result result :findings []}
                        (:replayed out) (assoc :replayed true))))))))))))

(def finding-codes
  "本模块自己产出的 `:code`（域 `deploy.*`）。"
  finding/codes)

(def all-finding-codes
  "自己的 + 三个**回落**来源（artifact / control / dialect）。装载一份制品的拒绝
   有一半是回落来的，重贴一层皮会让下游按 code 分流时漏掉一半。"
  finding/all-codes)

(defn finding? [x] (finding/valid? x))

(defn handle! [store reads envelope context]
  (rs/with-lock (instance store) :graph-maintenance
    (if (and (contains? #{:load :activate :rollback} (:control/op envelope))
             (= :archived (lifecycle-state store (get-in envelope [:control/payload :graph]))))
      {:findings [{:code :graph-archived :severity :error :path [:graph]
                   :message "Restore the archived Graph before publishing."}]}
      (handle-command! store reads envelope context))))
