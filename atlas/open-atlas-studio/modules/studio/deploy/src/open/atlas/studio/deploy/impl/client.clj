(ns open.atlas.studio.deploy.impl.client
  "**唯一碰网络的 ns**。HTTP 客户端用 JDK 自带的 `java.net.http`——不引依赖。

   这里做两件事：给请求签 **service 签名**（算在原始 body 上），把应答读回来。
   一条判断都不做。"
  (:require [clojure.edn :as edn]
            [open.atlas.common.control-protocol :as cp]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.deploy.impl.finding :as f])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

;; 与 server/http 那一侧**同一份名字**。各写一份字符串就是各有一份协议——
;; 但 studio 不依赖 server（D1.8），所以两处只能靠这条注释与测试对齐。
(def header-key-id    "x-atlas-service-key-id")
(def header-timestamp "x-atlas-service-timestamp")
(def header-signature "x-atlas-service-signature")

(defn- client ^HttpClient [timeout-ms]
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofMillis (or timeout-ms 10000)))
      (.build)))

(defn post-envelope
  "签名并送出一份控制信封。→ `{:status n :body 已读的 EDN}` 或 `{:findings […]}`。

   **body 逐字节就是签名输入的来源**：先规范化成文本，再对那串文本算签名,
   再把同一串文本发出去。先发再签、或发一份签另一份，是这条路上最容易犯又
   最难查的错。"
  [{:keys [endpoint service-key-id service-secret now timeout-ms]} env]
  (let [body (dialect/canonical-str env)
        ts   now
        sig  (cp/sign-request {:key-id service-key-id :timestamp ts :body-text body}
                              service-secret)
        req  (-> (HttpRequest/newBuilder (URI/create endpoint))
                 (.header "content-type" "application/edn; charset=utf-8")
                 (.header header-key-id service-key-id)
                 (.header header-timestamp ts)
                 (.header header-signature sig)
                 (.POST (HttpRequest$BodyPublishers/ofString body))
                 (.build))]
    (try
      (let [resp (.send (client timeout-ms) req (HttpResponse$BodyHandlers/ofString))
            text (.body resp)
            edn* (try (edn/read-string text) (catch Throwable _ ::unreadable))]
        (if (= ::unreadable edn*)
          {:findings [(f/error :deploy.transport/unexpected [:response]
                               "the control plane answered with something that is not EDN"
                               :got (.statusCode resp))]}
          {:status (.statusCode resp) :body edn*}))
      (catch Exception e
        {:findings [(f/error :deploy.transport/failed [:endpoint]
                             "the control plane could not be reached"
                             :got (.getMessage e))]}))))
