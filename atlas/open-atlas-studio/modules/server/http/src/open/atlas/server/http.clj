(ns open.atlas.server.http
  "控制面的**传输壳**（D3.16）。跨模块只许 require 本 ns。

   路由只有两条：`POST /control`（唯一的控制端点）与 `GET /health`。

   ## 启动是 fail-closed 的，而且**没有** dev 后门

   参考实现在「未配置 secret」时接受任意 Bearer 为 admin。本平台
   [D5.7](docs/design/50-security.md) 写死了「dev 也不例外，没有隐式全权路径」,
   所以这里**根本没有那个分支**：钥匙串为空时不存在「放行」这条路,
   每个请求都验不过。

   于是启动守卫就不是「没 secret 还敢监听公网吗」，而是更简单的一条：
   **钥匙串为空就拒绝启动**。反正它谁都服务不了，而一句明确的拒绝比让运维
   对着满屏 401 猜配置便宜得多。

   ## 数据面不在这里

   控制面与数据面**分离**（D3.12：同一个入口就等于让一张查询 token 的泄漏变成
   一次部署权泄漏）。本模块只有控制面；EQL 那一侧是 B6/B7 的另一个进程/端点。"
  (:require [compojure.core :as compojure]
            [open.atlas.server.http.impl.handler :as handler]
            [ring.adapter.jetty :as jetty]))

(def max-body-bytes handler/default-max-body-bytes)

;; service 凭据的三个 header。导出是为了让**两侧**（壳与 studio 的 client）
;; 用同一份名字——各写一份字符串就是各有一份协议。
(def header-key-id    handler/header-key-id)
(def header-timestamp handler/header-timestamp)
(def header-signature handler/header-signature)

(defn startup-findings
  "启动前的 fail-closed 检查。空 = 可以起。"
  [{:keys [service-keys instance-id]}]
  (cond-> []
    (empty? service-keys)
    (conj {:code :deploy.authz/service-not-permitted :severity :error :path [:service-keys]
           :message (str "no service keys are configured, so every request would fail "
                         "authentication; there is no dev mode that accepts anything (D5.7 — "
                         "dev has no back door either), so refusing to start is both correct "
                         "and the only diagnosable outcome")})
    (not (and (string? instance-id) (seq instance-id)))
    (conj {:code :deploy.audience/mismatch :severity :error :path [:instance-id]
           :message (str "this instance has no id, so no envelope can be addressed to it; "
                         ":control/audience exists precisely so a signed envelope cannot be "
                         "replayed onto another machine")})))

(defn routes
  "Ring handler。**不起服务器**——可以直接喂一个 request map 测。"
  [deps]
  (compojure/routes
   (compojure/POST "/control" request (handler/handle deps request))
   (compojure/GET  "/health"  _ {:status 200
                                 :headers {"content-type" "text/plain"}
                                 :body "ok"})
   (fn [_] {:status 404 :headers {"content-type" "text/plain"} :body "not found"})))

(defn start!
  "起 Jetty。`deps` 见 `impl.handler/handle`，外加 `:port` `:host` `:service-keys`。
   启动守卫不过就**不起**，把 findings 交回去。"
  [{:keys [port host] :as deps}]
  (let [fs (startup-findings deps)]
    (if (seq fs)
      {:findings fs}
      {:server (jetty/run-jetty (routes deps)
                                {:port (or port 8443) :host (or host "127.0.0.1") :join? false})
       :findings []})))

(defn stop! [server] (.stop server))
