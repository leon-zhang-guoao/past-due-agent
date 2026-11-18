(ns open.atlas.studio.app
  "studio = Builder + 一台内嵌的 Server Runtime Instance（D1.13）。

   本模块是 studio 这个 app 的装配点。运行时**整台**由 `runtime/instance` 装
   ——那是唯一一条装配路径；这里只加 studio 自己那一半：

   ```
   Builder（池 · 圈图 · sync · 切版本 · 发布制品）
      │  切出的制品落在 Builder 自己的制品架
      ▼  与独立 Server **完全相同**的 load/activate 命令边界
   内嵌 runtime instance（制品架 · serving 指针 · token 台账 · 数据面）
   ```

   ## 两个制品架，不是一个

   Builder 切出来的落在它自己的架子上；发布是把字节**送进**内嵌 runtime 的架子,
   走的是与推给远端 Server 一模一样的 offer/stage/load/activate。看着绕,
   但正是这一步让「studio 上自测过的东西等于线上会发生的事」成立——少掉它,
   studio 就是在读一份别人不会那样读的东西。

   两个架子因此**各算各的保留条件**（R3.5，owner 裁定 2026-08-25），不得默认成
   同一个全局架子。

   ## 状态隔离

   Builder **不直接改 runtime 的库**。它能做的只有：构造并签名一份控制信封,
   交给 `handle!`。同进程直调省掉的只有一次 HTTP 往返——命令校验、actor 验签、
   闭包检查、审计与原子激活一样都不少（owner 裁定 2026-08-25）。"
  (:require [open.atlas.runtime.graph :as rgraph]
            [open.atlas.runtime.instance :as instance]
            [open.atlas.runtime.store :as store]
            [open.atlas.studio.deploy :as deploy]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.version :as version]))

(def builder-service-principal
  "Builder 对内嵌 runtime 的 service 身份。

   它**仍然是一个 service principal**，仍然要过 `service-allows?`——同进程不等于
   有特权。D5.7 说得很死：dev 也不例外，没有隐式全权路径。"
  {:issuer "studio-builder" :subject "embedded"})

(defn local-transport
  "同进程的控制面传输。与 `client/post-envelope` **同形**，所以
   `studio/deploy` 对应答的解读只有一处，两种传输不可能对「这算不算成功」
   有两种看法。

   **它不是旁路**：信封原样交给 `runtime.graph/handle!`——租约闸、graph 串行、
   五个命令的判定、effect 落盘，与 HTTP 那一侧逐条相同。省掉的只有序列化与
   一次往返。"
  [{:keys [graph reads instance]} now-fn]
  (fn [_conf env]
    (let [{:keys [result findings replayed]}
          (rgraph/handle! graph reads env {:instance-id (store/instance-id instance)
                                           :service-principal builder-service-principal
                                           :now (now-fn)})]
      (if (seq findings)
        ;; 状态码对同进程调用没有意义，但形状要一致：`->finding-list` 只看
        ;; 「是不是 200」与 body 里的 findings。
        {:status 409 :body {:control/status :rejected :findings (vec findings)}}
        {:status 200 :body (cond-> {:control/status :ok :control/result result}
                             replayed (assoc :control/replayed true))}))))

(defn start!
  "起一个 studio：池 + 内嵌 runtime。

   ```clojure
   (start! {:pool {:backend :mem :id \"studio\"}
            :instance-id \"studio-embedded\"
            :runtime-root \"/srv/studio/runtime\"
            :artifact-root \"/srv/studio/artifacts\"   ; Builder 自己的架子
            :token-pepper (secrets \"token-pepper\")
            :actor-key (fn [key-id] …)                 ; 内嵌 runtime 验 actor 断言
            :now (fn [] …)})
   ```

   → `{:conn :runtime :artifact-root :send-envelope :findings []}` 或
   `{:findings […]}`。**任何一段起不来就整个起不来**，已开的一并关掉。"
  [{:keys [pool* instance-id runtime-root artifact-root token-pepper actor-key now
           resolve-source]
    :or   {now (fn [] (str (.truncatedTo (java.time.Instant/now)
                                         java.time.temporal.ChronoUnit/SECONDS)))}}]
  (let [rt (instance/start!
            {:instance-id instance-id :root runtime-root :token-pepper token-pepper
             :actor-key actor-key
             ;; 凭据是这台 instance 的本地状态（D1.13），值不进模型；宿主把
             ;; 解析函数交给 runtime，于是 Builder 那条查询路径与内嵌 runtime
             ;; 那条（`/eql`、`/mcp`）用的是**同一份**连接事实。
             :resolve-source resolve-source
             ;; **Builder 也要过 ACL**：同进程不等于有特权（D5.7）。
             :service-allows? (fn [principal _op] (= principal builder-service-principal))})]
    (if (seq (:findings rt))
      rt
      (let [cfg  (pool/config pool*)
            conn (version/install! (graph/install! (pool/open-pool! cfg)))]
        (instance/recover! rt)
        {:conn conn :pool-config cfg :runtime rt
         :instance-id instance-id
         :artifact-root artifact-root
         :send-envelope (local-transport rt now)
         :findings []}))))

(defn stop! [{:keys [conn pool-config runtime]}]
  (when runtime (instance/stop! runtime))
  (when conn (pool/close-pool! conn))
  (when pool-config (pool/delete-pool! pool-config)))

(defn publish!
  "把 Builder 已切的一版**发布到内嵌 runtime**：owner 校验 → 闭包 plan →
   offer → stage → load → activate，与推给远端 Server 逐条相同。

   `conf` 除 `deploy!` 要的那些之外不需要 `:endpoint`——传输由本模块注入。"
  [{:keys [conn artifact-root send-envelope instance-id]} conf]
  (deploy/deploy! (merge {:db (pool/db conn)
                          :artifact-root artifact-root
                          :audience instance-id
                          :send-envelope send-envelope
                          ;; service 凭据对同进程传输无用，但 `deploy!` 的形状
                          ;; 要求它们在场——留空串而不是留 nil，免得「没配」与
                          ;; 「配成空」在日志里长得一样。
                          :service-key-id "" :service-secret ""}
                         conf)))

(defn rollback!
  "切到一个**显式**目标版本，同样走同进程的命令边界。**证据必须是目标那一版当初的
   plan**（R3.6 / D3.16），不是当前版本的。

   线上签的 op 由**方向**决定（D3.16 ⑤）：目标更早是 `:rollback`，更晚或当前什么
   都没在服务是 `:activate`。函数名说的是调用方要的那件事，不是信封上的动词。"
  [{:keys [conn send-envelope instance-id]} conf]
  (deploy/rollback! (merge {:db (pool/db conn) :send-envelope send-envelope
                            :audience instance-id
                            :service-key-id "" :service-secret ""}
                           conf)))
