(ns open.atlas.server.data
  "数据面的传输壳：`POST /eql`。跨模块只许 require 本 ns。

   ## 与控制面分开（D3.12）

   **不同的入口、不同的认证、不同的监听。** 数据面的凭据是一张 graph token,
   控制面的权限是「能改这台机器装什么」——放同一个入口，就等于让一张查询
   token 的泄漏变成一次部署权泄漏。分开之后，「数据面永远只有 token 一种钥匙」
   （D5.7）这句话才继续成立。

   ## 它一条业务判断都不做

   解出 bearer、解出目标 graph 与 EQL、交给 `runtime/instance` 的 `answer`、
   把应答写成规范 EDN。grant 判断在 `common/token`，物化在 `runtime/env`,
   档位执法在 `common/engine`——本模块**不得重新实现其中任何一样**
   （owner 裁定 2026-08-25）。这与控制面壳是同一条纪律：判定在内核，传输是壳。

   The request body contains only EQL. Bearer verification resolves the graph
   UUID and version; a caller has no graph selector to assert or override.

   ## 不记 token，不记结果

   拒绝与日志都不携带 secret（S5.2）也不携带业务数据（S5.3 / finding-v1 §5）。
   应答里回 `:token-id` 与 `:env/version`——前者是审计要的，后者是消费者要的
   「我这次读到的是哪一版」。"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [compojure.core :as compojure]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.semantic-protocol :as semantic-protocol]
            [open.atlas.runtime.instance :as instance]
            [open.atlas.runtime.obs :as obs]
            [ring.adapter.jetty :as jetty])
  (:import [java.io ByteArrayOutputStream InputStream]))

(def default-max-body-bytes
  "请求体上限。EQL 请求本来就该小；没有上限就是无界堆。"
  (* 1 1024 1024))

(def header-authorization "authorization")
(def bearer-prefix "Bearer ")

(defn- read-capped
  "读至多 `cap` 字节。**超限拒绝而不截断**——截断出来的 EQL 会解析成另一个
   意思，然后按那个意思应答。"
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

(defn- wire-result
  "Mutation roots come back from the engine keyed by the mutation SYMBOL, but
   symbols are outside the dialect value domain (dialect-v1), so the response
   could not be encoded after the write had already committed. Rename only the
   top-level symbol keys to the keyword of the same name (the same symbol ->
   keyword mapping the engine gate applies to mutation params, D2.18); values
   and non-mutation data are untouched. The MCP path unwraps by symbol instead."
  [data]
  (if (map? data)
    (into {} (map (fn [[k v]] [(if (symbol? k) (keyword (namespace k) (name k)) k) v])) data)
    data))

(defn- edn-response [status body]
  {:status  status
   :headers {"content-type" "application/edn; charset=utf-8"
             "cache-control" "no-store"}
   :body    (dialect/canonical-str (semantic-protocol/wire-value body))})

(defn- status-for
  "finding → HTTP 状态。**状态码是给基础设施看的**（重试、告警、限流），
   结构化的 body 才是给调用方看的。"
  [findings]
  (let [codes (set (map :code findings))
        ns*   (set (map (comp namespace :code) findings))]
    (cond
      (some #{:token.authz/unknown :token.authz/secret-mismatch
              :token.authz/revoked :token.authz/expired} codes) 401
      (contains? codes :token.authz/graph-disabled)              403
      (contains? codes :token.authz/user-assertion-forbidden)    403
      (some #{"token.format"} ns*)                              401
      (some #{"instance.lease" "instance.store"} ns*)           503
      :else                                                     400)))

(def ^:private refusals-of
  "拒绝的两种形状收成一种——**定义在 `runtime/instance`**，因为两种形状都是
   `answer` 产出的。

   壳原本只看 `:findings`，于是「引擎拒绝」被当成成功，回 200 加一个空结果:
   调用方分不清「你不够格」与「本来就没有这一行」，而 D5.3 花代价买来的正是
   这条区分。修好之后这里有过一份**自己的**实现——直到 8.10 P6 发现审计那一侧
   同样只看 `:findings`，把被拒的读记成了成功。同一条知识第二次被写下时,
   它已经在第一处漏掉了一个调用方。一处定义，两处用。

   引擎那张 map **原样带出**（只摘掉 `:engine/status`）：`unknown-attribute`
   刻意让两种「读不到」逐字相同，重写措辞就会把那条性质退掉（D5.4）。"
  instance/refusals)

(defn- status-for
  "finding → HTTP 状态。**状态码是给基础设施看的**（重试、告警、限流），
   结构化的 body 才是给调用方看的。"
  [findings]
  (let [codes (set (map :code findings))
        ns*   (set (map (comp namespace :code) findings))]
    (cond
      (some #{:token.authz/unknown :token.authz/secret-mismatch
              :token.authz/revoked :token.authz/expired} codes) 401
      (contains? codes :token.authz/graph-disabled)              403
      (contains? codes :token.authz/user-assertion-forbidden)    403
      (some #{"token.format"} ns*)                              401
      (some #{"instance.lease" "instance.store"} ns*)           503
      :else                                                     400)))

;; D1.9b: the body may name the user a Workbench token vouches for. The shape is
;; checked here; whether this token may vouch at all is answer's decision.
(defn- on-behalf-of-finding [obo]
  (when (and (some? obo)
             (not (and (map? obo) (= "workbench" (:issuer obo))
                       (string? (:subject obo)) (seq (str/trim (:subject obo)))
                       (empty? (dissoc obo :issuer :subject)))))
    {:code :token.format/malformed :severity :error :path [:body :on-behalf-of]
     :message "on-behalf-of must be {:issuer \"workbench\" :subject <email>} and nothing else"}))

(defn- unauthenticated [message]
  (edn-response 401 {:status :rejected
                     :findings [{:code :token.format/malformed :severity :error
                                 :path [:authorization] :message message}]}))

(defn handle
  "`(handle deps request)` → Ring 应答。

   `deps`：`:instance`（`runtime/instance` 起出来的那套）、`:now`（注入是为了
   可测）、`:max-body-bytes`（可选）、`:answer`（可选，缺省
   `runtime.instance/answer`）、`:saved-query`（可选，data-plane-api 1.2：
   `(fn [credential now name params] → {:request eql} | {:findings …})`，由宿主
   用 `server.mcp/saved-query-request` 装配；缺省 = 本数据面不存预定义查询）。

   `:answer` 可注入是为了让**壳自己**能被单测——壳要证的是「没带 token 是 401」
   「body 不是规范 EDN 是 400」「哪种拒绝映到哪个状态码」，那些与制品架无关。
   真装料的那一条端到端在装配点。**注入的只有壳与判定之间那一步**，判定本身
   仍然只有一份。"
  [{:keys [instance now max-body-bytes answer saved-query] :or {answer instance/answer}} request]
  (let [cap (or max-body-bytes default-max-body-bytes)
        {:keys [text over]} (read-capped (:body request) cap)
        auth (get (:headers request) header-authorization)]
    (cond
      over
      (edn-response 413 {:status :rejected
                         :findings [{:code :token.format/malformed :severity :error
                                     :path [] :message "request body exceeds the cap"
                                     :expected cap}]})

      (not (and (string? auth) (str/starts-with? auth bearer-prefix)))
      (unauthenticated "the data plane always needs a token (D5.7); present it as a bearer")

      :else
      (let [body (try (edn/read-string text) (catch Throwable _ ::unreadable))
            credential (subs auth (count bearer-prefix))]
        (if (or (= ::unreadable body) (not (map? body))
                ;; data-plane-api 1.2: `:saved-query` is the second body shape,
                ;; and exactly one of the two is present.
                (= (contains? body :request) (contains? body :saved-query)))
          (edn-response 400 {:status :rejected
                             :findings [{:code :token.format/malformed :severity :error
                                         :path [:body] :message
                                         "the body must be canonical EDN carrying :request, or :saved-query (not both)"}]})
          (if-let [bad (on-behalf-of-finding (:on-behalf-of body))]
            (edn-response 400 {:status :rejected :findings [bad]})
          (let [bound (when (contains? body :saved-query)
                        (cond
                          (not (and (string? (:saved-query body))
                                    (or (nil? (:params body)) (map? (:params body)))))
                          {:findings [{:code :token.format/malformed :severity :error :path [:body]
                                       :message ":saved-query is a name and :params a map"}]}
                          ;; A host that keeps no saved queries (the standalone
                          ;; Server, until it does) knows no name.
                          (nil? saved-query)
                          {:findings [{:code :saved-query/unknown :severity :error :path [:saved-query]
                                       :message "this data plane keeps no saved queries"}]}
                          :else (saved-query credential (now) (:saved-query body) (:params body))))]
          (if (seq (:findings bound))
            (edn-response (status-for (:findings bound)) {:status :rejected :findings (vec (:findings bound))})
          (let [r (answer instance
                                   (cond-> {:credential credential
                                    :request (if bound (:request bound) (:request body))
                                    :source (cond-> {:remote-addr (:remote-addr request)
                                                     :path (:uri request)
                                                     :user-agent (get-in request [:headers "user-agent"])}
                                              bound (assoc :saved-query {:name (:saved-query body)
                                                                         :params (:params body)}))
                                    :now (now)
                                    ;; 哪扇门由传输层说——`answer` 不知道自己是从
                                    ;; 哪条线上被调用的，猜一个会给审计假答案。
                                    :entry :data-eql}
                                     (contains? body :on-behalf-of) (assoc :on-behalf-of (:on-behalf-of body))))]
            (if-let [refusals (refusals-of r)]
              (edn-response (status-for refusals)
                            {:status :rejected :findings refusals})
              ;; `runtime/instance` 的 `answer` 交出来的是 engine 的键面
              ;; （`:engine/data`），不是 `:result`。这里**改名不改内容**：
              ;; 线上的名字是 `:result`。**不再有 `:filtered-attributes`**——
              ;; 它随访问档位退役（D5.11 / D7.12）：一次放行的应答里不会有任何
              ;; 东西被过滤掉，一个恒为空的键只会让消费方以为还有一道会挑人的门。
              (edn-response 200
                            (cond-> {:result (wire-result (:engine/data r))
                                     :token-id (:token-id r)
                                     :env/version (:env/version r)}
                              ;; **缺行是答案不是错误**（0.2 验收缺陷 11）：按键查
                              ;; 一个不存在的行是 200，请求的属性缺席，信封上多一个
                              ;; `:not-found` 说清是哪个锚没有找到东西——只有属性名,
                              ;; 没有调用方给的那个值。键缺席即「这次没有未命中」,
                              ;; 恒有的空键会让消费方以为每次都有话要说。
                              ;; data-plane-api 1.1 (§8 item 4): the same coverage facts MCP's
                              ;; execute_eql gives, so a /eql reader can tell a capped list from a whole one.
                              (:engine/collections r)
                              (assoc :collections (mapv (fn [[path info]] (assoc info :path path)) (:engine/collections r)))
                              (:engine/alerts r)
                              (assoc :alerts (:engine/alerts r))
                              (:engine/partial? r)
                              (assoc :partial true :issues (:engine/issues r) :source-completeness :partial)
                              (:engine/row-outcomes r)
                              (assoc :row-outcomes (:engine/row-outcomes r))
                              (:engine/not-found r)
                              (assoc :not-found (:engine/not-found r))))))))))))))

(defn- build-info-response
  "BUILD.json from the package root, verbatim. A process not started from a built package
   (a dev REPL) has none, and says so instead of inventing a version."
  []
  (let [root (System/getenv "OPEN_ATLAS_PACKAGE_ROOT")
        f    (when root (java.io.File. ^String root "BUILD.json"))]
    (if (and f (.isFile f))
      {:status 200 :headers {"content-type" "application/json"} :body (slurp f)}
      (edn-response 404 {:status :rejected
                         :findings [{:code :build/info-absent :severity :error :path []
                                     :message "this process was not started from a built package, so it has no BUILD.json"
                                     :expected "OPEN_ATLAS_PACKAGE_ROOT/BUILD.json"}]}))))

(defn routes
  "Ring handler。**不起服务器**——可以直接喂一个 request map 测。

   `:extra-routes` 是数据面监听器上的**挂载点**：一个 Ring handler，排在
   `/eql` 之后、catch-all 404 之前。它存在是因为数据面的第二层外衣
   （`server/mcp` 的 `/mcp`）与 `/eql` 说的是同一件事、用的是同一张 token,
   分两个端口只会让「数据面的钥匙只有一种」这句话在部署时多一个要解释的例外。

   **本模块不因此依赖 mcp**：挂进来的是一个值，不是一条模块边（D1.8）。
   顺序要紧——catch-all 必须最后，否则挂上去的东西永远到不了。"
  [{:keys [extra-routes] :as deps}]
  ;; 8.10 P4：请求关联挂在**这里**而不是各 app 里。数据面在两种形态下是同一个
  ;; handler，关联要是各挂各的，「同一件事在两种形态下留下同样的记录」就成了
  ;; 一句要靠人守的话（D1.13）。上游带 X-Request-Id 就沿用，回应里原样带回。
  (obs/wrap-request-id
   (compojure/routes
    (compojure/POST "/eql" request (handle deps request))
    (compojure/GET  "/health" _ {:status 200 :headers {"content-type" "text/plain"}
                                :body "ok"})
    ;; doc 110 §110.3: the build's own version and the contract versions it provides, read
    ;; from BUILD.json in the package root. No auth, like /health: it names versions, no data.
    (compojure/GET  "/meta/contracts" _ (build-info-response))
    ;; `/metrics`：**免认证，和 /health 一样**，因为吐出来的东西与谁在问无关——
    ;; uptime、按 source/op 的计数、时延分位、拒绝计数。**没有用户 id、没有实体名、
    ;; 没有图名、没有值**（`obs.impl.prometheus/render` 逐行可查），所以它不构成
    ;; 一个需要凭证的答案。
    ;;
    ;; 挂在**数据面**而不是控制面：这个监听器默认只在 loopback,
    ;; 而控制面可能对外（`OPEN_ATLAS_STUDIO_BUILDER_HOST`）——把一个免认证端点
    ;; 加到可能对外的监听器上是另一个决定，不该搭这次的车。
    (compojure/GET  "/metrics" request (obs/prometheus-handler request))
    (or extra-routes (fn [_] nil))
    (fn [_] {:status 404 :headers {"content-type" "text/plain"} :body "not found"}))))

(defn jetty-options
  "Ring-Jetty options for one listener on `host`:`port`. With `:tls`
   (`{:keystore <PKCS12 path> :password …}`) the port speaks HTTPS only — no
   plain-HTTP connector beside it, so a configured keystore can never be
   bypassed by dropping the `s`. No SNI host check: a self-signed certificate
   rarely names every address a client uses (LAN IP, container alias), and the
   Builder's listener does not check either."
  [{:keys [port host tls]}]
  (if tls
    {:host host :join? false :http? false :ssl? true :ssl-port port
     :keystore (:keystore tls) :keystore-type "PKCS12" :key-password (:password tls)
     :sni-host-check? false}
    {:host host :port port :join? false}))

(defn start!
  "起数据面的 Jetty。**与控制面是不同的监听**（D3.12）——同一个进程可以两个都起,
   但它们不共用端口，也不共用认证。`:tls` 见 `jetty-options`。"
  [{:keys [port host tls] :as deps}]
  {:server (jetty/run-jetty (routes deps)
                            (jetty-options {:port (or port 8444) :host (or host "127.0.0.1")
                                            :tls tls}))})

(defn stop! [server] (.stop server))
