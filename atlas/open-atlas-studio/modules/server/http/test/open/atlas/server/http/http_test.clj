(ns open.atlas.server.http.http-test
  "壳的守门测试。**不起 Jetty**——handler 吃 request map、吐 response map,
   所以「签名验没验」「body 超了拒不拒」「service 身份是不是自称的」全都是
   一次函数调用。

   端到端那一次（真 Jetty、真端口）留给装配点。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.control-protocol :as cp]
            [open.atlas.runtime.graph :as control]
            [open.atlas.runtime.store :as rs]
            [open.atlas.server.http :as http])
  (:import [java.io ByteArrayInputStream]))

(def ^:private svc-secret "service-key-for-tests")
(def ^:private svc-key-id "studio-prod")
(def ^:private now "2026-08-25T09:00:00Z")
(def ^:private instance-id "prod-instance-a")

(defn- tmp-root []
  (str (java.nio.file.Files/createTempDirectory
        "b5c1-http-" (into-array java.nio.file.attribute.FileAttribute []))))

(defn- rm-r [f]
  (let [f (io/file f)] (when (.isDirectory f) (run! rm-r (.listFiles f))) (.delete f)))

(defn- deps [store]
  {:store store
   :reads (merge (control/store-reads store)
                 {:actor-key (constantly nil) :service-allows? (constantly true)
                  ;; S5.7 门禁显式注入：缺席即拒绝激活。
                  :row-security-gate (constantly [])})
   :instance-id instance-id
   :service-key (fn [k] (when (= svc-key-id k) svc-secret))
   :now (constantly now)})

(defn- request
  "一次已签名的 POST。`over` 用来逐项破坏。"
  [body-text & {:keys [key-id timestamp signature]}]
  (let [kid (or key-id svc-key-id)
        ts  (or timestamp now)
        sig (or signature (cp/sign-request {:key-id kid :timestamp ts :body-text body-text}
                                           svc-secret))]
    {:request-method :post
     :uri "/control"
     :headers {http/header-key-id kid
               http/header-timestamp ts
               http/header-signature sig}
     :body (ByteArrayInputStream. (.getBytes ^String body-text "UTF-8"))}))

(defmacro with-deps [[sym] & body]
  `(let [root#   (tmp-root)
         opened# (rs/open! {:instance-id instance-id :root root#})
         inst#   (:instance opened#)
         _#      (assert (some? inst#) (pr-str (:findings opened#)))
         store#  (control/open! inst#)
         ~sym    (deps store#)]
     (try ~@body (finally (rs/close! inst#) (rm-r root#)))))

(defn- resp-body [r] (edn/read-string (:body r)))
(defn- codes [r] (into (sorted-set) (map :code) (:findings (resp-body r))))

;; ---------------------------------------------------------------------------
;; 启动守卫：fail-closed，且**没有** dev 后门
;; ---------------------------------------------------------------------------

(deftest an-empty-keyring-refuses-to-start
  (testing "参考实现在没配 secret 时接受任意 Bearer 为 admin。**这里根本没有那个
            分支**——钥匙串为空时不存在「放行」这条路，每个请求都验不过。于是
            守卫是「拒绝启动」，而不是「敢不敢监听公网」"
    (is (seq (http/startup-findings {:service-keys {} :instance-id instance-id})))
    (is (seq (http/startup-findings {:service-keys {"k" "s"} :instance-id ""}))
        "没有 instance-id 就没有信封能指向它——audience 正是为了防跨机重放")
    (is (empty? (http/startup-findings {:service-keys {"k" "s"} :instance-id instance-id})))))

;; ---------------------------------------------------------------------------
;; service 认证
;; ---------------------------------------------------------------------------

(deftest a-request-without-credentials-is-refused
  (with-deps [d]
    (let [r ((http/routes d) (dissoc (request "{}") :headers))]
      (is (= 401 (:status r)))
      (is (contains? (codes r) :control.signature/missing)))))

(deftest a-signature-that-does-not-cover-this-body-is-refused
  (testing "**签名绑的是 body 的 hash**：改一个字节就验不过"
    (with-deps [d]
      (let [good (request "{:a 1}")
            r ((http/routes d)
               (assoc good :body (ByteArrayInputStream. (.getBytes "{:a 2}" "UTF-8"))))]
        (is (= 401 (:status r)))))))

(deftest an-unknown-service-key-is-refused
  (with-deps [d]
    (is (= 401 (:status ((http/routes d) (request "{}" :key-id "nope")))))))

(deftest a-clock-far-out-of-skew-is-refused
  (with-deps [d]
    (is (= 401 (:status ((http/routes d) (request "{}" :timestamp "2026-08-25T12:00:00Z")))))))

;; ---------------------------------------------------------------------------
;; service 身份来自已认证的通道，不是请求体
;; ---------------------------------------------------------------------------

(deftest the-service-identity-comes-from-the-verified-key-id
  (testing "信封里没有「我是谁」这个字段——**自称的身份不是身份**。壳把验出来的
            key-id 当作 service principal 交给判定"
    (with-deps [d]
      (let [body (pr-str {:control/version 1 :control/op :offer
                          ;; 就算请求体里塞一个 service 字段，它也进不了判定:
                          ;; 信封键面是封闭的，多一个键就是拒绝。
                          :control/service-principal {:issuer "x" :subject "root"}})
            r ((http/routes d) (request body))]
        (is (= 400 (:status r)) "封闭键面：多出来的键在协议层就被拒")
        (is (contains? (codes r) :control.envelope/malformed))))))

;; ---------------------------------------------------------------------------
;; body 上限
;; ---------------------------------------------------------------------------

(deftest an-oversized-body-is-refused-not-truncated
  (testing "截断出来的 body 会算出一个合法却对不上的 hash，然后报「签名不匹配」
            ——一个把「太大」说成「签错了」的错误信息"
    (with-deps [d]
      (let [big (apply str (repeat 2000 "x"))
            r ((http/routes (assoc d :max-body-bytes 100)) (request big))]
        (is (= 413 (:status r)))))))

;; ---------------------------------------------------------------------------
;; 路由与应答形状
;; ---------------------------------------------------------------------------

(deftest there-is-exactly-one-control-endpoint
  (testing "op 在**签名覆盖范围内**。把它也写进 URL，URL 与签名内容就成了两个
            可以互相矛盾的事实源"
    (with-deps [d]
      (is (= 404 (:status ((http/routes d) {:request-method :post :uri "/control/activate"}))))
      (is (= 200 (:status ((http/routes d) {:request-method :get :uri "/health"})))))))

(deftest responses-are-canonical-edn
  (with-deps [d]
    (let [r ((http/routes d) (request "{}"))]
      (is (= "application/edn; charset=utf-8" (get-in r [:headers "content-type"])))
      (is (map? (resp-body r)))
      (is (= :rejected (:control/status (resp-body r)))))))

(deftest an-unreadable-body-is-a-400-not-a-crash
  (with-deps [d]
    (is (= 400 (:status ((http/routes d) (request "{:unbalanced")))))))
