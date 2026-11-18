(ns open.atlas.runtime.instance
  "Assemble one Server Runtime Instance. Cross-module callers require only this ns.

   D1.13 says there is exactly one runtime: a standalone Server is it deployed on
   its own, and studio is a Builder plus an embedded copy of it. For that sentence
   to hold, **assembly can only happen in one place**. Two apps wiring the parts
   themselves is the \"second materialisation path for studio\" the ruling forbids,
   and the two wirings would eventually differ on some capability — most likely
   one nobody looks at until it matters.

   ```
   store      租约、poisoned、原子写、锁注册表        （所有子系统共用一份）
     ├ graph  制品架、receipt、serving 指针、五个命令
     ├ token  本机台账、签发、验证、吊销
     ├ env    闭包合并 → 编译一次，懒物化与缓存
   ```

   ## 谁向谁提问，走的都是公开读接口

   `token` 问 graph「这一版装了吗、latest 指哪」；`env` 问 graph「三份文本、
   可信 digest、serving 指针」；`retention` 问三者各要一样。**全部是注入的函数**,
   所以每个子系统都能单独被测，而「已装载」「latest」这些定义各只有一处
   （owner 裁定 2026-08-25）。接线在这里，且只在这里。

   ## fail-closed

   任何一段起不来就整台起不来，已经打开的一并关掉。半台 instance——比如租约拿到
   了而 pepper 没配——比起不来更坏：它会接受部署、却签不出牌，而两件事的日志在
   两个地方。"
  (:require [clojure.java.io :as io]
            [open.atlas.runtime.artifact-cleanup :as cleanup]
            [open.atlas.common.artifact :as artifact]
            [clojure.string :as str]
            [open.atlas.runtime.env :as env]
            [open.atlas.runtime.graph :as graph]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.runtime.obs :as obs]
            [open.atlas.runtime.retention :as retention]
            [open.atlas.runtime.rowsec :as rowsec]
            [open.atlas.runtime.store :as store]
            [open.atlas.runtime.token :as token]))

(defn- graph-parts [graph-id]
  (let [[workspace graph-name & more] (str/split (str graph-id) #"/" -1)]
    (when (and (empty? more)
               (not (str/blank? workspace))
               (not (str/blank? graph-name)))
      [(store/safe-segment! workspace "workspace")
       (store/safe-segment! graph-name "graph")])))

(defn- availability-file [inst graph-id]
  (when-let [[workspace graph-name] (graph-parts graph-id)]
    (io/file (store/subsystem-dir inst "graph-availability")
             workspace (str graph-name ".edn"))))

(defn- not-disabled-on-instance? [inst graph-id]
  (not= false (:enabled? (some-> (availability-file inst graph-id)
                                 store/read-edn))))

(defn graph-enabled?
  "Whether this runtime currently serves a graph. A graph must have an active
   serving version and must not be explicitly disabled on this instance."
  [{:keys [instance graph]} graph-id]
  (boolean (and (graph/serving-version graph graph-id)
                (not-disabled-on-instance? instance graph-id))))

(defn set-graph-enabled!
  "Persist the instance-local serving switch. Disabling immediately blocks
   existing tokens; enabling requires an activated serving version."
  [{:keys [instance graph] :as runtime} graph-id enabled? {:keys [actor now]}]
  (let [file   (availability-file instance graph-id)
        known? (some #(= graph-id (:graph %)) (graph/shelf-versions graph))]
    (cond
      (nil? file)
      {:findings [{:code :graph.access/invalid-id :severity :error
                   :path [:graph-id] :message "graph-id must be workspace/graph"
                   :got graph-id}]}

      (not known?)
      {:findings [{:code :graph.access/unknown :severity :error
                   :path [:graph-id] :message "this runtime has no such graph"
                   :got graph-id}]}

      (and enabled? (nil? (graph/serving-version graph graph-id)))
      {:findings [{:code :graph.access/no-serving-version :severity :error
                   :path [:graph-id] :message "activate a graph version before enabling it"
                   :got graph-id}]}

      :else
      (if-let [fs (seq (store/writable-findings instance))]
        {:findings (vec fs)}
        (store/with-lock instance [:graph-availability graph-id]
          (store/write-edn! file {:graph-id graph-id :enabled? (boolean enabled?)
                                  :updated-by actor :updated-at now})
          {:graph-id graph-id :enabled? (boolean enabled?) :findings []})))))

(defn- graph-capabilities
  "graph 对外的那几个公开读，包成注入用的闭包。"
  [g]
  {:loaded?         (fn [qn v] (graph/loaded? g qn v))
   :serving-version (fn [qn] (graph/serving-version g qn))
   :receipt-digest  (fn [qn v] (graph/receipt-digest g qn v))
   :shelf-texts     (fn [qn v] (graph/shelf-texts g qn v))
   :shelf-versions  (fn [] (graph/shelf-versions g))
   :references-of   (fn [qn v] (graph/references-of g qn v))
   :closure-facts   (fn [qn v] (graph/closure-facts g qn v))})

(defn start!
  "起一台 instance。→ `{:instance :graph :token :env :retention :reads :findings []}`
   或 `{:findings […]}`。

   ```clojure
   (start! {:instance-id \"prod-a\" :root \"/srv/atlas\"
            :token-pepper (secrets \"token-pepper\")
            :actor-key (fn [key-id] …) :service-allows? (fn [principal op] …)
            :limits {…}})
   ```

   - `:token-pepper` 必给，且**与其它任何密钥分开**（S5.9）；
   - `:actor-key` / `:service-allows?` 是控制面的钥匙串与 ACL，从哪来不是 runtime
     该认识的事（文件、环境变量、KMS 都可能）——缺省 fail-closed。

   - `:resolve-source` `(fn [source-id entry] → entry)` 可选：把模型里声明的
     source 换成这台机器真开得出的连接。凭据是 instance 本地状态（D1.13）,
     值不进模型，所以持有凭据 store 的宿主要把这个函数交进来；缺席就按模型
     原样连。

   `:reads` 是命令核心要的九个读：store 供得起的七个 + 这两个。"
  [{:keys [instance-id root token-pepper actor-key service-allows? limits
           resolve-source]}]
  (let [opened (store/open! {:instance-id instance-id :root root})]
    (if (seq (:findings opened))
      opened
      (let [inst (:instance opened)
            g    (graph/open! inst)
            caps (graph-capabilities g)
            ;; S5.7 的门禁：闭包事实来自 graph（含每个成员的
            ;; `:row-security-verified`，由制品的体检报告经
            ;; `artifact/health-facts` 带出），判定在 rowsec。**同一个门禁**同时
            ;; 挂在激活（graph 的 reads）与发牌（token）两处——D5.6 的三条入口
            ;; 要两处各查一次才盖得全。判据自 D5.14 起是「声明且核对通过即
            ;; 放行」，签名不变，所以这里一个字不用改：两处读的永远是同一个判定。
            rw   (rowsec/open! inst {:closure-facts (:closure-facts caps)})
            gate (fn [qn v] (rowsec/check (:store rw) qn v))
            t    (token/open! inst {:pepper token-pepper
                                    :graph-loaded? (:loaded? caps)
                                    :graph-serving-version (:serving-version caps)
                                    :graph-enabled? #(not-disabled-on-instance? inst %)
                                    :row-security-gate gate})
            e    (env/open! inst {:shelf-texts (:shelf-texts caps)
                                  :receipt-digest (:receipt-digest caps)
                                  :serving-version (:serving-version caps)
                                  :limits limits
                                  :resolve-source resolve-source})
            fs   (vec (concat (:findings rw) (:findings t) (:findings e)))]
        (if (seq fs)
          ;; **半台 instance 比起不来更坏**：租约已经拿到、pepper 却没配的话,
          ;; 它会接受部署、签不出牌，而两件事的日志在两个地方。
          (do (store/close! inst) {:findings fs})
          (let [r (retention/open!
                   {:shelf-versions     (:shelf-versions caps)
                    :references-of      (:references-of caps)
                    :serving-version    (:serving-version caps)
                    ;; **词汇在这里换，不在两头换。** 台账说 `:graph-id`（那是它
                    ;; 发牌的语言），retention 的坐标说 `:graph`（架、serving 指针
                    ;; 与 env 缓存都是这个形状）。两套词汇相遇的地方是装配层——
                    ;; retention 不许反过来知道台账（该模块自己的理由：token 不能
                    ;; 成为回收的主人），台账也不该为回收改自己的字段名。
                    ;;
                    ;; 少了这次翻译，`:pinned` 与其余三样的坐标**永远不相等**,
                    ;; 于是一个被牌钉住的版本报成可回收——正是 R3.5 说「只有东西
                    ;; 已经删掉之后才看得见」的那类错误。
                    :pinned-coordinates (fn [now]
                                          (into #{}
                                                (map (fn [{:keys [graph-id version]}]
                                                       {:graph graph-id :version version}))
                                                (token/pinned-coordinates (:store t) now)))
                    :env-held           (fn [] (env/held (:store e)))})]
            (if (seq (:findings r))
              (do (store/close! inst) {:findings (:findings r)})
              {:instance  inst
               :graph     g
               :token     (:store t)
               :env       (:store e)
               :retention (:store r)
               :rowsec    (:store rw)
               :reads     (merge (graph/store-reads g)
                                 {:actor-key         (or actor-key (constantly nil))
                                  :service-allows?   (or service-allows? (constantly false))
                                  :row-security-gate gate})
               :findings  []})))))))

(defn refusals
  "一次 `answer` 的拒绝，收成一种形状 → `[finding …]`，没有拒绝则 nil。

   拒绝有**两种形状**：`runtime/token` 与 `runtime/env` 给 `:findings [...]`;
   `common/engine` 给的是一张**扁平的 map**——`:engine/status :rejected` 加
   finding 自己的键。只看 `:findings` 就会把引擎的拒绝当成成功,
   `modules/server/data` 的壳踩过这一次（见它 `refusals-of` 的注释：
   「于是『档位不够』被当成成功，回 200 加一个空结果」）。

   放在这里而不是各壳里：两种形状都是 `answer` 产出的,
   那么「怎么读它们」也归它——两处各写一份，改一处忘另一处的那天就是
   一次「被拒绝的读被记成成功」。"
  [r]
  (cond
    (seq (:findings r)) (vec (:findings r))
    (= :rejected (:engine/status r)) [(dissoc r :engine/status)]
    :else nil))

(defn- entity-namespaces-of
  "请求里出现过的实体命名空间——**只有名字**。走整棵请求树，因为限定 keyword
   可能嵌在任何一层；值一律不看（audit-event-v1 §5）。"
  [request]
  (->> (tree-seq coll? seq request)
       (filter qualified-keyword?)
       (map namespace)
       distinct
       sort
       vec))

(defn answer
  "**一次数据面请求的完整判定**（D5.4 的那一道门）。

   ```clojure
   (answer parts {:credential \"oat_v1.…\" :graph-id \"a/root\"
                  :request [:a.x/name] :now \"…\"})
   ```

   → `{:token-id … :version n :result …}` 或 `{:findings […]}`。

   三步，一步都不重写：

   1. `runtime/token` 验出示值并交出**本次请求唯一的那一个 grant**（目标 graph 的）;
   2. `runtime/env` 按那个 grant 的版本说明固定一次版本快照并物化;
   3. `common/engine` 在那一档上应答。

   ## 为什么放在装配层

   它要 token 与 env 两个子系统同时在场，而两者互不依赖（token 不物化 env,
   env 不读台账内部结构——owner 裁定 2026-08-25）。放在任何一边都会长出那条边。

   ## 为什么两种形态共用这一份

   D1.13 说数据面执法是**同一份实现**。独立 Server 的 HTTP 端点与 studio 内嵌
   runtime 的入口，包的都是这个函数——B6/B7 的传输层**只能调用它，不得重新
   实现 graph grant 判断**（owner 裁定 2026-08-25）。

   The caller cannot select a graph: bearer verification resolves it.

   ## 审计发在这里，不在传输层

   数据面的执法只有这一份（D1.13），所以「谁读了哪些数据」也只该有一处记录:
   `/eql`、MCP、以及 studio 内嵌路径包的都是这个函数。挂到某一个传输层上,
   另外那些就成了**没有记录的门**——而 S5.3 要的恰恰是「谁在敲哪扇门」。

   `:entry` 由传输层传进来（`:data-eql` / `:data-mcp`，缺省 `:internal`）:
   这个函数不知道自己是从哪条线上被调用的，猜一个会给审计一个假答案。"
  [{:keys [token env] :as parts} {:keys [credential request now entry source on-behalf-of]}]
  (let [vouched (when (some? on-behalf-of) (select-keys on-behalf-of [:issuer :subject]))
        started (System/nanoTime)
        elapsed #(quot (- (System/nanoTime) started) 1000000)
        ;; 数据面的读也要看得见步骤（owner 裁定 2026-08-31）。收据在这条动态
        ;; 通道上出现，但**只有 spi 的白名单那几项**会被留下——语句原文与绑定值
        ;; 到不了审计。注意并行 resolver 跑在别的线程上时收不到它们的回执:
        ;; 少记不等于没发生，这条与 Builder 那侧的限制是同一个。
        statements (atom [])
        ;; 审计回答「谁做了什么」，指标回答「多少、多快」。两者从**同一次调用**
        ;; 记下，否则 `/metrics` 上的数会和流水里的条数对不上,
        ;; 而对不上的那天没人知道该信哪一个。
        measure! (fn [outcome]
                   (try
                     (obs/metrics-record-query!
                      {:request-id (obs/request-id)
                       :source (or entry :internal)
                       :op :query
                       :status (if (= :ok outcome) :ok :error)
                       :latency-ms (elapsed)})
                     (catch Throwable _ nil)))
        ;; The host's request recorder (Studio's recent queries, proposal
        ;; studio-saved-queries §4). Optional and outside the audit: it sees the
        ;; EQL text, which the audit ledger never does, and it lives only in the
        ;; host's memory. A host without one (the standalone Server) is unchanged.
        ;; Returns an id or nil; a refusal carries the id as `:studio/recent-id`
        ;; (finding-v1 allows namespaced extension keys) so a caller can say
        ;; «debug this one».
        record! (fn [{:keys [outcome reason token-id graph version findings]}]
                  (when-let [on-query (:on-query parts)]
                    (try
                      (on-query {:at now :entry (or entry :internal) :outcome outcome
                                 :reason reason :findings findings
                                 :token-id token-id :graph graph :graph-version version
                                 :latency-ms (elapsed) :request request
                                 :on-behalf-of vouched
                                 :tool (:tool source) :saved-query (:saved-query source)})
                      (catch Throwable _ nil))))
        tag (fn [r id]
              (if-not id
                r
                (cond-> r
                  (seq (:findings r)) (update :findings (fn [fs] (mapv #(assoc % :studio/recent-id id) fs)))
                  (= :rejected (:engine/status r)) (assoc :studio/recent-id id))))
        audit! (fn [{:keys [outcome reason token-id graph version trace]}]
                 ;; Server explicitly opts into query text diagnostics; the
                 ;; value-free audit ledger and Studio defaults stay unchanged.
                 (when (:query-log? parts)
                   (obs/log-event! :query-executed
                                   (merge (select-keys source [:remote-addr :user-agent :path :tool])
                                          {:execution-target :published :entry (or entry :internal) :eql (pr-str request)
                                           :token-id token-id :graph graph :graph-version version
                                           :outcome outcome :reason reason :latency-ms (elapsed)})))
                 (try
                   (obs/audit-record!
                    (store/subsystem-dir (:instance parts) "obs")
                    (cond-> {:v 1
                             :event   :access/query
                             :at      now
                             :request-id (obs/request-id)
                             :instance-id (store/instance-id (:instance parts))
                             :entry   (or entry :internal)
                             :outcome outcome
                             ;; 数据面的主体是**一张 token**，不是一个人;
                             ;; 谁持有它由 token 自己那条审计回答（并账之后
                             ;; 两条在同一本账里，靠 :token-id 串起来）。
                             ;; D1.9b: a Workbench-vouched user is recorded as such --
                             ;; the token's capability was verified here, the person
                             ;; was not (audit-event-v1 §4 :workbench-asserted).
                             :actor   (if vouched
                                        {:service {:instance-id (store/instance-id (:instance parts))}
                                         :on-behalf-of vouched
                                         :asserted-by :workbench-asserted}
                                        {:service {:instance-id (store/instance-id (:instance parts))}
                                         :asserted-by :verified})
                             ;; 只记**名字**：请求里出现过哪些实体命名空间。
                             ;; 不记键、不记值、不记语句（audit-event-v1 §5）。
                             ;; 「这次读花了多久」——时延是计数不是值,
                             ;; 而没有它，数据面的每一条都只回答「发生过」,
                             ;; 回答不了「慢不慢」。
                             :latency-ms (elapsed)
                             :entities (entity-namespaces-of request)}
                      (spi/statement-audit-summary @statements)
                      (assoc :statements (spi/statement-audit-summary @statements))
                      ;; 每-resolver 的时间线。`:stats?` 按 engine 自己的文档
                      ;; 「不额外花钱——Pathom3 本来就在收，只是别丢掉」。
                      trace (assoc :resolver-trace trace)
                      reason   (assoc :reason reason)
                      token-id (assoc :token-id token-id)
                      graph    (assoc :graph graph)
                      version  (assoc :graph-version version)))
                   (catch Throwable _ nil)))
        v (token/verify token credential {:now now})]
    (if-let [fs (seq (:findings v))]
      (do (audit! {:outcome :denied :reason (:code (first fs))})
          (measure! :denied)
          ;; Not recorded: an unverified bearer names no graph to file it under.
          v)
      (if (and (some? on-behalf-of) (not (:asserts-user? v)))
        ;; D1.9b: only a token issued with :asserts-user? may name a user.
        (do (audit! {:outcome :denied :reason :token.authz/user-assertion-forbidden
                     :token-id (:token-id v) :graph (get-in v [:authorization :graph-id])})
            (measure! :denied)
            {:findings [{:code :token.authz/user-assertion-forbidden :severity :error
                         :path [:on-behalf-of]
                         :message "this token was not issued to vouch for a user"}]})
      (let [authorization (:authorization v)
            graph-id      (:graph-id authorization)]
        (if-not ((:graph-enabled? token) graph-id)
          (do (audit! {:outcome :denied :reason :token.authz/graph-disabled
                       :token-id (:token-id v) :graph graph-id})
              (measure! :denied)
              {:findings [{:code :token.authz/graph-disabled :severity :error
                           :path [:graph-id]
                           :message "this graph is disabled on this runtime instance"
                           :got graph-id}]})
          (let [r (binding [spi/*statement-trace* #(swap! statements conj %)]
                    (env/query env {:graph-id graph-id :authorization authorization
                                    :request request :stats? true
                                    :caller (when vouched {:open-atlas.caller/email (:subject vouched)})
                                    ;; E2：源系统打标的关联 id。**唯一**读
                                    ;; dynamic var 的地方，且在请求线程上——
                                    ;; 并行 resolver 跑在别的线程，读不到它，
                                    ;; 所以往下必须经 env 显式传。
                                    ;; `graph-id` 已经是 graph 的 UUID（D3.18）。
                                    :trace {:request-id (obs/request-id)
                                            :graph-id graph-id}}))
                ;; `:engine/stats` 是**未裁剪**的结果（带值）。摘要之后当场丢掉:
                ;; 它一步都不许离开这个函数,否则一条审计开关会变成一条数据出口。
                trace (obs/summarize-run-stats (:engine/stats r))
                r (dissoc r :engine/stats)]
            ;; **两种拒绝形状都算拒绝。** 只看 `:findings` 会把引擎的扁平拒绝
            ;; 记成 `:outcome :ok`——一条说「他读到了」的审计，而他没有。
            (if-let [fs (seq (refusals r))]
              (do (audit! {:outcome :denied :reason (:code (first fs))
                           :token-id (:token-id v) :graph graph-id :trace trace})
                  (measure! :denied)
                  (tag r (record! {:outcome :denied :reason (:code (first fs)) :findings (vec fs)
                                   :token-id (:token-id v) :graph graph-id
                                   :version (:version authorization)})))
              (do (audit! {:outcome :ok :token-id (:token-id v) :graph graph-id
                           :version (:env/version r) :trace trace})
                  (measure! :ok)
                  (record! {:outcome :ok :token-id (:token-id v) :graph graph-id
                            :version (:env/version r)})
                  (assoc r :token-id (:token-id v) :graph-id graph-id
                           :version (:env/version r)))))))))))

(defn recover!
  "启动恢复。**接受任何命令之前跑一次**（`runtime/graph` 的那一套）。"
  [{:keys [graph]}]
  (graph/recover! graph))

(defn stop!
  "放掉租约。子系统没有各自的资源——它们共用这一台 instance 的那一份。"
  [{:keys [instance]}]
  (when instance (store/close! instance)))

(defn prepare-graph-deletion!
  "Shared Studio/Server deletion: inspect dependencies before any mutation,
   stop serving, revoke active keys, invalidate environments, remove owned
   artifacts. The host retains its archived registration until this succeeds.
   Deployment/audit evidence and Source instances are never deleted."
  [{:keys [instance graph token env] :as parts}
   {qn :graph :keys [graph-id artifact-roots actor now]}]
  (locking (if instance (store/monitor instance :graph-maintenance) (Object.))
    (when (and instance (seq (store/writable-findings instance)))
      (throw (ex-info "Runtime is not writable; deletion was not started"
                      {:code :graph-artifact-cleanup-failed})))
    (let [plan (cleanup/plan! (cond-> (vec artifact-roots) instance
                                (into [(io/file (store/root instance) "artifacts")
                                       (io/file (store/root instance) "staging")])) qn graph-id)
          serving (when (and graph (every? artifact/portable-segment? (str/split qn #"/")))
                    (graph/serving-version graph qn))
          records (filter #(and (token/active? % now)
                                (contains? #{qn graph-id} (:graph-id %)))
                          (when token (token/all-records token)))
          stopped (when serving (graph/stop-serving! graph qn {:expected-current serving
                                                :stopped-at now :stopped-by actor}))]
      (when (seq (:findings stopped))
        (throw (ex-info "Stopping Graph service was rejected"
                        {:code :graph-delete-cleanup-failed :error stopped})))
      (doseq [record records]
        (let [r (token/revoke-token! token {:token-id (:token-id record) :actor actor
                                          :request-id (str (random-uuid)) :now now})]
          (when (seq (:findings r))
            (throw (ex-info "Revoking Graph access was rejected"
                            {:code :graph-delete-cleanup-failed :error r})))))
      (when env (env/invalidate! env))
      (merge {:stopped-version serving :tokens-revoked (count records)}
             (cleanup/apply! plan)))))


(defn- reclaim-roots [instance artifact-roots]
  (cond-> (vec artifact-roots) instance
    (into [(io/file (store/root instance) "artifacts")
           (io/file (store/root instance) "staging")])))

(defn version-reclaim-blockers
  "Why one Graph version may NOT be reclaimed on this instance, derived from
   current state — never a counter (R3.5): the serving pointer, active and
   unexpired tokens pinned to it, and retained artifacts referencing it.
   Empty map = reclaimable. Throws (`:graph-artifact-cleanup-failed`) when an
   artifact cannot be inspected: unknown is not «unreferenced»."
  [{:keys [instance graph token]} {qn :graph :keys [graph-id version artifact-roots now]}]
  (let [plan (cleanup/plan-version! (reclaim-roots instance artifact-roots) qn graph-id version)
        serving (when graph (graph/serving-version graph qn))
        tokens (->> (when token (token/all-records token))
                    (filter #(and (token/active? % now)
                                  (= version (:version %))
                                  (contains? #{qn graph-id} (:graph-id %))))
                    (mapv :token-id) sort vec)]
    (cond-> {}
      (= serving version) (assoc :serving version)
      (seq tokens) (assoc :pinned-tokens tokens)
      (seq (:referenced-by plan)) (assoc :referenced-by (:referenced-by plan)))))

(defn reclaim-version!
  "Reclaim one Graph version's local copies (R3.5): retract its load receipt,
   invalidate environments, remove its retained and staged artifact copies.
   Re-derives the blockers under the graph-maintenance lock and refuses if any
   remain. Deployment/audit evidence, events and tokens are never touched."
  [{:keys [instance graph env] :as parts}
   {qn :graph :keys [graph-id version artifact-roots] :as request}]
  (locking (if instance (store/monitor instance :graph-maintenance) (Object.))
    (when (and instance (seq (store/writable-findings instance)))
      (throw (ex-info "Runtime is not writable; reclaim was not started"
                      {:code :graph-artifact-cleanup-failed})))
    (let [blockers (version-reclaim-blockers parts request)]
      (when (seq blockers)
        (throw (ex-info "This version is still in use on this instance"
                        (merge {:code :version-in-use :graph qn :version version} blockers))))
      (when graph
        (let [r (graph/unload-version! graph qn version)]
          (when (seq (:findings r))
            (throw (ex-info "Unloading the version was rejected"
                            {:code :version-in-use :graph qn :version version :error r})))))
      (when env (env/invalidate! env))
      (let [plan (cleanup/plan-version! (reclaim-roots instance artifact-roots) qn graph-id version)]
        (cleanup/apply! plan)))))
