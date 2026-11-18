(ns open.atlas.server.http.impl.handler
  "Ring 请求 → Ring 应答。**没有 Jetty，也没有起进程**——这一层是可以单元测试的。

   壳只做四件事：读原始 body（带上限）、验 **service 请求签名**、把信封交给判定、
   把应答写成规范 EDN。一条业务判断都不在这里。

   ## 为什么只有一个端点

   `POST /control`，op 在信封里。五个端点会把 op 写进 URL——而 op **在签名覆盖
   范围内**，于是 URL 与签名内容成了两个可以互相矛盾的事实源。一个端点就没有
   这个问题：能被改的只有信封，而改信封签名就失效。

   ## service 签名为什么必须在壳里验

   它算在**原始请求体**上（`{key-id, 时间戳, body 的 sha256}`），而只有壳手里有
   那串原始字节——判定拿到的是已经解析过的信封。actor 那一边相反：它算在规范
   信封上，那是两侧共用的定义，所以留在核心（D3.16）。

   ## 不记 body

   拒绝与日志都不携带业务数据（finding-v1 §5）。这里连 body 的长度之外的东西
   都不往应答里放——控制信封里坐着制品全文。"
  (:require [clojure.edn :as edn]
            [open.atlas.common.control-protocol :as cp]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.runtime.graph :as control])
  (:import [java.io ByteArrayOutputStream InputStream]))

(def default-max-body-bytes
  "请求体上限。`:stage` 要送三份制品全文，所以它不能太小；但没有上限就是无界
   堆——每个 handler 都要把 body 读进内存才能算 hash。"
  (* 32 1024 1024))

(def header-key-id    "x-atlas-service-key-id")
(def header-timestamp "x-atlas-service-timestamp")
(def header-signature "x-atlas-service-signature")

(defn- read-capped
  "读至多 `cap` 字节。→ `{:text …}` 或 `{:over cap}`（超限**拒绝而不截断**：
   截断出来的 body 会算出一个合法却对不上的 hash，然后报「签名不匹配」——
   一个把「太大」说成「签错了」的错误信息）。"
  [^InputStream in cap]
  (if (nil? in)
    {:text ""}
    (let [buf (byte-array 8192)
          out (ByteArrayOutputStream.)]
      (loop []
        (let [n (.read in buf)]
          (cond
            (neg? n) {:text (.toString out "UTF-8")}
            (> (+ (.size out) n) cap) {:over cap}
            :else (do (.write out buf 0 n) (recur))))))))

(defn- edn-response [status body]
  {:status  status
   :headers {"content-type" "application/edn; charset=utf-8"
             "cache-control" "no-store"}
   :body    (dialect/canonical-str body)})

(defn- status-for
  "finding → HTTP 状态。**状态码是给基础设施看的**（重试、告警、限流），
   结构化的 body 才是给调用方看的——两者分工不同，所以这张表刻意小。"
  [findings]
  (let [codes (set (map :code findings))
        ns*   (set (map (comp namespace :code) findings))]
    (cond
      (some #{"control.signature"} ns*)                    401
      (contains? codes :deploy.authz/service-not-permitted) 403
      (some #{"control.envelope"} ns*)                      400
      ;; 租约拿不到、盘不合用、进程中毒——都是**这台机器现在服务不了**,
      ;; 与调用方的请求无关（`instance.*` 域，runtime/store）。
      (some #{"instance.lease" "instance.store"} ns*)      503
      :else                                                 409)))

(defn- unauthenticated [message]
  (edn-response 401 {:control/status :rejected
                     :findings [{:code :control.signature/missing :severity :error
                                 :path [] :message message}]}))

(defn handle
  "`(handle deps request)` → Ring 应答。

   `deps`：
   - `:store`             —— `runtime/graph` 的 store
   - `:reads`             —— 九个读（含钥匙串与 ACL，由装配方注入）
   - `:instance-id`       —— 本机身份，信封的 `:control/audience` 要对上
   - `:service-key`       —— `(fn [key-id] → secret|nil)`
   - `:now`               —— `(fn [] → RFC3339 UTC 字符串)`；注入是为了可测
   - `:max-body-bytes`    —— 可选

   **service 身份来自这里验出来的 key-id，不是请求体里的自称。**"
  [{:keys [store reads instance-id service-key now max-body-bytes]} request]
  (let [cap (or max-body-bytes default-max-body-bytes)
        {:keys [text over]} (read-capped (:body request) cap)]
    (cond
      over
      (edn-response 413 {:control/status :rejected
                         :findings [{:code :control.envelope/malformed :severity :error
                                     :path [] :message "request body exceeds the cap"
                                     :expected cap}]})

      :else
      (let [h         (:headers request)
            key-id    (get h header-key-id)
            timestamp (get h header-timestamp)
            presented (get h header-signature)
            secret    (when key-id (service-key key-id))
            ctx       {:key-id key-id :timestamp timestamp :body-text text}]
        (cond
          (not (and key-id timestamp presented))
          (unauthenticated "the service credential headers are missing")

          (nil? (cp/parse-instant timestamp))
          (unauthenticated "the service timestamp is not RFC3339 UTC with second precision")

          ;; **时钟偏移有界**。信封自己的有效期已经把重放框住了（而且 request-id
          ;; 让重放成为 no-op），但一个时间戳离谱到几小时之外的请求，说明对面
          ;; 要么坏了要么在试探——当场失败比让它与信封窗口的组合去兜底便宜。
          (> (abs (- (.getEpochSecond (cp/parse-instant timestamp))
                     (.getEpochSecond (cp/parse-instant (now)))))
             cp/max-assertion-seconds)
          (unauthenticated "the service timestamp is outside the accepted clock skew")

          (nil? secret)
          (unauthenticated "no key is on file for this service key id")

          (not (cp/request-signature-matches? ctx secret presented))
          (unauthenticated "the service request signature does not match this body")

          :else
          (let [env (try (edn/read-string text) (catch Throwable _ ::unreadable))]
            (if (= ::unreadable env)
              (edn-response 400 {:control/status :rejected
                                 :findings [{:code :control.envelope/malformed :severity :error
                                             :path [] :message
                                             "the body is not readable as EDN"}]})
              (let [{:keys [result findings replayed]}
                    (control/handle! store reads env
                                     {:instance-id instance-id
                                      ;; 已认证的 key-id **就是** service 身份。
                                      :service-principal {:issuer "service-key" :subject key-id}
                                      :now (now)})]
                (if (seq findings)
                  (edn-response (status-for findings)
                                {:control/status :rejected :findings (vec findings)})
                  (edn-response 200 (cond-> {:control/status :ok :control/result result}
                                      replayed (assoc :control/replayed true))))))))))))
