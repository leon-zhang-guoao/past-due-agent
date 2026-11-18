(ns open.atlas.server.data.data-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.server.data :as data])
  (:import [java.io ByteArrayInputStream]))

(def ^:private secret (apply str (repeat 43 "a")))
(def ^:private credential (str "oat_v1.tok-1." secret))
(defn- deps [answer] {:instance ::fake :now (constantly "2026-08-25T10:00:00Z") :answer answer})
(defn- request [body & {:keys [auth]}]
  (cond-> {:request-method :post :uri "/eql"
           :body (ByteArrayInputStream. (.getBytes ^String body "UTF-8"))}
    (not= ::none auth) (assoc :headers {"authorization" (str "Bearer " (or auth credential))})))
(defn- body-of [response] (edn/read-string (:body response)))

(deftest bearer-and-eql-are-required
  (let [handler (data/routes (deps (fn [_ _] (assert false "answer must not run"))))]
    (is (= 401 (:status (handler (request "{:request [:a.x/name]}" :auth ::none)))))
    (is (= 400 (:status (handler (request "{}")))))))

(deftest the-shell-forwards-eql-without-a-graph-selector
  (let [seen (atom nil)
        handler (data/routes (deps (fn [_ request]
                                    (reset! seen request)
                                    {:engine/status :ok :engine/data {:a.x/name "n"}
                                     :token-id "tok-1" :env/version 7})))
        response (handler (request "{:request [:a.x/name]}"))]
    (is (= 200 (:status response)))
    (is (= [:a.x/name] (:request @seen)))
    (is (not (contains? @seen :graph-id)))
    (is (= {:a.x/name "n"} (:result (body-of response))))
    (is (= "tok-1" (:token-id (body-of response))))) )

(deftest token-refusals-stay-out-of-the-success-path
  (let [handler (data/routes (deps (fn [_ _]
                                    {:findings [{:code :token.authz/unknown :severity :error
                                                 :path [:token] :message "unknown token"}]})))
        response (handler (request "{:request [:a.x/name]}"))]
    (is (= 401 (:status response)))
    (is (= :rejected (:status (body-of response))))))

(deftest responses-never-echo-the-bearer
  (let [handler (data/routes (deps (fn [_ _] {:engine/status :ok :engine/data {}
                                               :token-id "tok-1" :env/version 7})))
        response (handler (request "{:request []}"))]
    (is (not (str/includes? (:body response) secret)))))

(deftest metrics-is-scrapeable-without-a-token-and-says-nothing-about-anyone
  ;; 免认证是刻意的：吐出来的东西与谁在问无关。但「与谁无关」这句话要有人盯着,
  ;; 否则哪天有人往 render 里加一个带实体名的 label，就成了一个免认证的数据出口。
  (let [r (data/routes {:instance nil :now (constantly "2026-08-31T00:00:00Z")})
        resp (r {:request-method :get :uri "/metrics"})]
    (is (= 200 (:status resp)) "不带 token 也答")
    (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/plain"))
    (is (str/includes? (:body resp) "atlas_uptime_seconds"))
    (testing "只有聚合量——没有用户、没有实体、没有图名、没有值"
      (let [body (:body resp)]
        (is (not (str/includes? body "@")) "没有邮箱一类的主体")
        (is (every? #(re-matches #"^(#.*|atlas_[a-z_]+\{?[^}]*\}? [0-9.]+|)$" %)
                    (str/split-lines body))
            "每一行要么是注释，要么是 atlas_* 指标加一个数字")))))

(deftest a-missing-row-is-200-with-the-anchor-named-on-the-envelope
  ;; 0.2 验收缺陷 11：按键查一个不存在的行曾以 400 `:engine.execution/failed`
  ;; 作答——「库坏了」。缺行是答案：200，属性缺席，信封上说清哪个锚没找着。
  (let [handler (data/routes
                 (deps (fn [_ _] {:engine/status :ok
                                  :engine/data {}
                                  :engine/not-found {:anchor [:a.x/id]
                                                     :attributes [:a.x/name]}
                                  :token-id "tok-1" :env/version 7})))
        response (handler (request "{:request {:entity {:a.x/id \"nope\"} :query [:a.x/name]}}"))
        body (body-of response)]
    (is (= 200 (:status response)))
    (is (= {} (:result body)))
    (is (= {:anchor [:a.x/id] :attributes [:a.x/name]} (:not-found body)))
    (testing "the value the caller looked up is never echoed back"
      (is (not (str/includes? (:body response) "nope"))))))

(deftest a-hit-carries-no-not-found-key-at-all
  ;; 恒有的空键读作「每次都有话要说」；键缺席才是「这次没有未命中」。
  (let [handler (data/routes (deps (fn [_ _] {:engine/status :ok :engine/data {:a.x/name "n"}
                                              :token-id "tok-1" :env/version 7})))
        body (body-of (handler (request "{:request [:a.x/name]}")))]
    (is (not (contains? body :not-found)))))


(deftest partial-data-never-loses-its-failure-evidence
  (let [issues [{:code :engine.execution/batch-input-failed :http-status 400}]
        outcomes [{:path [:p/index 1] :status :source-error :http-status 400}]
        handler (data/routes (deps (fn [& _]
                                    {:engine/status :ok :engine/data {:p/index [{:p/id "good" :p/name "Good"} {:p/id "bad"}]}
                                     :engine/partial? true :engine/issues issues :engine/row-outcomes outcomes})))
        response (handler (request "{:request [{:p/index [:p/id :p/name]}]}"))
        body (body-of response)]
    (is (= 200 (:status response)))
    (is (true? (:partial body)))
    (is (= :partial (:source-completeness body)))
    (is (= issues (:issues body)))
    (is (= outcomes (:row-outcomes body)))
    (is (= 2 (count (get-in body [:result :p/index]))))))

;; D1.9b: the shell checks the shape of :on-behalf-of and forwards it; whether the
;; token may vouch is answer's decision, mapped to 403 here.
(deftest on-behalf-of-is-shape-checked-forwarded-and-its-refusal-is-403
  (let [seen (atom nil)
        ok (data/routes (deps (fn [_ request] (reset! seen request)
                                {:engine/status :ok :engine/data {} :token-id "wb" :env/version 1})))]
    (is (= 200 (:status (ok (request "{:request [:w.item/reason] :on-behalf-of {:issuer \"workbench\" :subject \"alex@example.com\"}}")))))
    (is (= {:issuer "workbench" :subject "alex@example.com"} (:on-behalf-of @seen)))
    (reset! seen nil)
    (ok (request "{:request [:w.item/reason]}"))
    (is (not (contains? @seen :on-behalf-of)) "absent stays absent"))
  (let [never (data/routes (deps (fn [_ _] (assert false "answer must not run"))))]
    (doseq [obo ["{:issuer \"agent\" :subject \"x@y\"}" "{:issuer \"workbench\" :subject \" \"}"
                 "{:issuer \"workbench\" :subject \"x@y\" :role :admin}" "\"alex@example.com\""]]
      (let [response (never (request (str "{:request [] :on-behalf-of " obo "}")))]
        (is (= 400 (:status response)) obo)
        (is (= [:body :on-behalf-of] (:path (first (:findings (body-of response)))))))))
  (let [forbidden (data/routes (deps (fn [_ _] {:findings [{:code :token.authz/user-assertion-forbidden
                                                            :severity :error :path [:on-behalf-of] :message "no"}]})))]
    (is (= 403 (:status (forbidden (request "{:request [] :on-behalf-of {:issuer \"workbench\" :subject \"a@b\"}}")))))))

(deftest a-mutation-result-is-encodable-and-keyed-by-the-keyword-of-its-symbol
  ;; Live 2026-10-05: the write committed, then encoding the symbol root threw
  ;; and the caller saw 503 and retried, duplicating the write.
  (let [handler (data/routes (deps (fn [_ _] {:engine/status :ok
                                              :engine/data {'vmt.write/event_act {:vmt.action/id 2}}
                                              :token-id "tok-1" :env/version 7})))
        response (handler (request "{:request [(vmt.write/event_act {:x 1})]}"))]
    (is (= 200 (:status response)))
    (is (= {:vmt.write/event_act {:vmt.action/id 2}} (:result (body-of response))))))

(deftest meta-contracts-refuses-rather-than-inventing-a-version
  ;; doc 110 §110.3: outside a built package there is no BUILD.json, and the answer says so.
  (when-not (System/getenv "OPEN_ATLAS_PACKAGE_ROOT")
    (let [r ((data/routes {:instance nil}) {:request-method :get :uri "/meta/contracts"})]
      (is (= 404 (:status r)))
      (is (str/includes? (:body r) ":build/info-absent")))))

(deftest eql-carries-collection-coverage-and-alerts
  ;; data-plane-api 1.1: /eql gives the coverage facts MCP's execute_eql gives, and only when present.
  (let [handler (data/routes (deps (fn [_ _]
                                     {:engine/status :ok :engine/data {:a.x/items []}
                                      :engine/collections {[:a.x/items] {:limit 50 :returned 50}}
                                      :engine/alerts [{:code :result/collection-coverage-unknown}]
                                      :token-id "tok-1" :env/version 7})))
        body (body-of (handler (request "{:request [:a.x/items]}")))]
    (is (= [{:limit 50 :returned 50 :path [:a.x/items]}] (:collections body)))
    (is (= [{:code :result/collection-coverage-unknown}] (:alerts body))))
  (let [handler (data/routes (deps (fn [_ _] {:engine/status :ok :engine/data {} :token-id "t" :env/version 1})))
        body (body-of (handler (request "{:request [:a.x/name]}")))]
    (is (not (contains? body :collections)))
    (is (not (contains? body :alerts)))))

(defn- self-signed-keystore! [password]
  (let [f (doto (java.io.File/createTempFile "data-tls" ".p12") (.delete))
        keytool (str (System/getProperty "java.home") "/bin/keytool")
        p (.waitFor (.start (doto (ProcessBuilder.
                                    [keytool "-genkeypair" "-alias" "test" "-keyalg" "EC"
                                     "-groupname" "secp256r1" "-validity" "1" "-dname" "CN=localhost" "-ext" "SAN=ip:127.0.0.1"
                                     "-storetype" "PKCS12" "-keystore" (str f)
                                     "-storepass" password "-keypass" password])
                              (.redirectErrorStream true))))]
    (assert (zero? p) "keytool failed")
    (str f)))

(defn- get-status [^String url]
  (let [trust-all (reify javax.net.ssl.X509TrustManager
                    (checkClientTrusted [_ _ _]) (checkServerTrusted [_ _ _])
                    (getAcceptedIssuers [_] (make-array java.security.cert.X509Certificate 0)))
        ctx (doto (javax.net.ssl.SSLContext/getInstance "TLS")
              (.init nil (into-array javax.net.ssl.TrustManager [trust-all]) nil))
        client (-> (java.net.http.HttpClient/newBuilder) (.sslContext ctx) .build)]
    (.statusCode (.send client (.build (java.net.http.HttpRequest/newBuilder (java.net.URI. url)))
                        (java.net.http.HttpResponse$BodyHandlers/discarding)))))

(deftest a-keystore-makes-the-listener-https-only
  (let [port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))
        {:keys [server]} (data/start! (assoc (deps (fn [_ _] nil)) :port port
                                             :tls {:keystore (self-signed-keystore! "pw-123456")
                                                   :password "pw-123456"}))]
    (try
      (is (= 200 (get-status (str "https://127.0.0.1:" port "/health"))))
      (is (= 200 (get-status (str "https://localhost:" port "/health")))
          "a host name the certificate does not carry is not refused")
      (is (thrown? java.io.IOException (get-status (str "http://127.0.0.1:" port "/health")))
          "no plain-HTTP connector beside the TLS one")
      (finally (data/stop! server)))))

(deftest a-saved-query-body-is-bound-then-answered-like-any-request
  (let [seen (atom nil)
        bound (atom nil)
        handler (data/routes
                 (assoc (deps (fn [_ r] (reset! seen r)
                                {:engine/status :ok :engine/data {:a.x/name "n"} :token-id "tok-1" :env/version 7}))
                        :saved-query (fn [c _ n params]
                                       (reset! bound [c n params])
                                       (if (= "by_id" n)
                                         {:request [{[:a.x/id (get params "id")] [:a.x/name]}]}
                                         {:findings [{:code :saved-query/unknown :severity :error
                                                      :path [:saved-query] :message "no such saved query"}]}))))]
    (testing "bound with the bearer's credential, then sent through the same door"
      (let [r (handler (request "{:saved-query \"by_id\" :params {\"id\" 3}}"))]
        (is (= 200 (:status r)))
        (is (= [credential "by_id" {"id" 3}] @bound))
        (is (= [{[:a.x/id 3] [:a.x/name]}] (:request @seen)))
        (is (= {:a.x/name "n"} (:result (body-of r))))))
    (testing "a refusal is 400 and nothing runs"
      (reset! seen nil)
      (let [r (handler (request "{:saved-query \"nope\"}"))]
        (is (= 400 (:status r)))
        (is (= :saved-query/unknown (-> r body-of :findings first :code)))
        (is (nil? @seen))))
    (testing "both shapes at once is malformed"
      (is (= 400 (:status (handler (request "{:request [] :saved-query \"by_id\"}"))))))))

(deftest a-data-plane-without-saved-queries-knows-no-name
  (let [handler (data/routes (deps (fn [_ _] (assert false "answer must not run"))))
        r (handler (request "{:saved-query \"by_id\"}"))]
    (is (= 400 (:status r)))
    (is (= :saved-query/unknown (-> r body-of :findings first :code)))))
