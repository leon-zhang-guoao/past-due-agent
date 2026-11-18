(ns open.atlas.runtime.instance.instance-test
  "装配的守门测试。它要证的不是四个子系统各自能跑（那各有各的测试），而是
   **线接对了**：token 问 graph 的是 graph 的答案、env 拿的是 graph 的可信 digest、
   retention 看得见另外三者，以及**半台 instance 起不来**。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.runtime.env :as env]
            [open.atlas.runtime.graph :as graph]
            [open.atlas.runtime.instance :as instance]
            [open.atlas.runtime.obs :as obs]
            [open.atlas.runtime.retention :as retention]
            [open.atlas.runtime.store :as store]
            [open.atlas.runtime.token :as token]))

(def ^:private now "2026-08-25T10:00:00Z")
(def ^:private alice {:issuer "corp-idp" :subject "00u-alice"})

(defn- tmp-root []
  (str (java.nio.file.Files/createTempDirectory
        "rt-inst-" (into-array java.nio.file.attribute.FileAttribute []))))

(defn- rm-r [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-r (.listFiles f)))
    (.delete f)))

(defmacro with-instance [[sym root-sym & {:as over}] & body]
  `(let [~root-sym (tmp-root)
         parts# (instance/start! (merge {:instance-id "prod-a" :root ~root-sym
                                         :token-pepper "pepper-a"}
                                        ~over))
         ~sym parts#]
     (assert (empty? (:findings parts#)) (pr-str (:findings parts#)))
     (try ~@body (finally (instance/stop! parts#) (rm-r ~root-sym)))))

;; ---------------------------------------------------------------------------
;; fail-closed：半台 instance 比起不来更坏
;; ---------------------------------------------------------------------------

(deftest a-missing-pepper-brings-down-the-whole-instance
  (testing "租约拿到了而 pepper 没配的话，它会接受部署、却签不出牌——
            而那两件事的日志在两个地方"
    (let [root (tmp-root)]
      (try
        (let [r (instance/start! {:instance-id "i" :root root})]
          (is (= [:token.pepper/unavailable] (mapv :code (:findings r))))
          (is (nil? (:instance r)))
          (testing "**租约也放掉了**——否则这台机器会因为一次失败的启动而被自己锁住"
            (let [again (instance/start! {:instance-id "i" :root root
                                          :token-pepper "p"})]
              (is (empty? (:findings again)))
              (instance/stop! again))))
        (finally (rm-r root))))))

(deftest a-second-process-on-the-same-root-cannot-start
  (with-instance [p root]
    (let [second* (instance/start! {:instance-id "prod-a" :root root :token-pepper "x"})]
      (is (= [:instance.lease/unavailable] (mapv :code (:findings second*)))))))

;; ---------------------------------------------------------------------------
;; 线接对了
;; ---------------------------------------------------------------------------

(deftest all-four-subsystems-share-one-lease-and-one-poisoned-flag
  (testing "③-1 的整条理由：各开一次锁、各写一份原子落盘、各有一份 poisoned,
            都会让保证在最需要它的那一刻悄悄失效"
    (with-instance [p root]
      (is (store/lease-held? (:instance p)))
      (is (.isFile (io/file root store/lock-file-name)))
      (store/poison! (:instance p) {:effect :x :message "disk full"})
      (testing "一个子系统写失败，**另一个也停写**"
        (is (= [:instance.lease/lost]
               (mapv :code (:findings (token/issue! (:token p)
                                                    {:graph-id "a/x" :version 1
                                                     :read-level :public
                                                     :actor alice :request-id "r" :now now}))))))
      (store/clear-poison! (:instance p)))))

(deftest token-asks-graph-and-gets-graphs-answer
  (testing "「已装载」只有一个定义，在 graph 那儿。token 不自己去看目录——
            那样就有了第二个定义，而两个定义迟早分岔"
    (with-instance [p root]
      (is (not (graph/loaded? (:graph p) "a/root" 1)))
      (is (= #{:token.issue/graph-not-loaded}
             (into #{} (map :code)
                   (:findings (token/issue! (:token p)
                                            {:graph-id "a/root" :version 1
                                             :read-level :public
                                             :actor alice :request-id "r" :now now}))))
          "本机没装 → 签不出钉版本 grant，理由来自 graph 的读"))))

(deftest retention-can-see-all-three-holders
  (testing "四样输入分属四个子系统，谁都不该单独成为回收系统的 owner（③-3）"
    (with-instance [p root]
      (is (empty? (retention/reclaimable (:retention p) now)) "架上什么都没有")
      (is (= #{:referenced :served :pinned :materialised}
             (set (keys (retention/holders (:retention p) now))))
          "四样都接上了，不是接了三样悄悄少一样")
      ;; 「接上了」不等于「说同一种话」：本测试的四样都是空集，而空集之间的
      ;; 坐标形状是**看不出**分歧的。台账说 `:graph-id`、retention 说 `:graph`,
      ;; 这个差别只有在真有一张牌的时候才显形——被钉住的版本报成可回收。
      ;; 那一格由 `studio/app` 的 retention-on-the-embedded-runtime 端到端证明,
      ;; 翻译本身在 `instance.clj` 的 `:pinned-coordinates`。
      )))

(deftest env-is-wired-to-this-instances-shelf
  (with-instance [p root]
    (is (= #{:env.artifact/absent}
           (into #{} (map :code) (:findings (env/materialize (:env p) "a/root" 1))))
        "问的是本机的架子——空架子上什么都物化不出来")
    (is (empty? (env/held (:env p))) "懒：起来的时候一个 env 都没物化")))

(deftest the-control-plane-reads-are-fail-closed-by-default
  (testing "钥匙串与 ACL 从哪来不是 runtime 该认识的事；不给就是谁都不放行"
    (with-instance [p root]
      (is (nil? ((:actor-key (:reads p)) "any-key")))
      (is (false? (boolean ((:service-allows? (:reads p)) {:issuer "x" :subject "y"} :activate))))
      (is (= 9 (count (select-keys (:reads p)
                                   [:deployment :request :event :staged-artifact
                                    :shelf-artifact :receipt :pointer
                                    :actor-key :service-allows?])))
          "命令核心要的九个读齐了"))))

(deftest an-instance-is-recovered-before-it-serves
  (with-instance [p root]
    (let [r (instance/recover! p)]
      (is (empty? (:quarantined r)))
      (is (= 0 (:swept r))))))

;; ---------------------------------------------------------------------------
;; 数据面访问审计（8.10 P6）
;;
;; 「谁读了哪些数据」以前一处都没记：审计流水里只有控制面的事实。发在 answer 上
;; 是因为数据面执法只有这一份——/eql、MCP、studio 内嵌路径包的都是它。
;; ---------------------------------------------------------------------------

(defn- audited [p kind id]
  (obs/audit-for-subject (store/subsystem-dir (:instance p) "obs") kind id))

(deftest a-refused-read-is-audited-with-the-reason-and-no-values
  (with-instance [p _root]
    (let [r (instance/answer p {:credential "oat_v1.nope.nope"
                                :request [:a.x/name] :now now :entry :data-eql})]
      (is (seq (:findings r)) "先确认它确实被拒了，否则下面在测一个没发生的事")
      ;; 被拒的读没有 token-id、没有 graph——它只落在发起它的那个入口下,
      ;; 所以按 principal/token/graph 都查不到。这正是「拒绝流水」要留在
      ;; 主体索引之外、由流水本身承载的原因（audit-event-v1 §6）。
      (let [dir (store/subsystem-dir (:instance p) "obs")]
        (is (= [] (audited p :graph "a/root")) "没解析出 graph 就不该挂到某张图下")
        (is (.exists (clojure.java.io/file dir "logs" "access.jsonl"))
            "但它必须出现在流水里——S5.3：拒绝是「谁在敲哪扇门」的唯一数据源")
        (let [line (slurp (clojure.java.io/file dir "logs" "access.jsonl"))]
          (is (clojure.string/includes? line "\"access/query\""))
          (is (clojure.string/includes? line "\"denied\""))
          (is (clojure.string/includes? line "data-eql") "记下了是哪扇门")
          (is (clojure.string/includes? line "\"a.x\"") "记实体命名空间")
          (is (clojure.string/includes? line "latency-ms") "记时延——「慢不慢」也要答得出")
          (is (not (clojure.string/includes? line "oat_v1")) "永不记凭证"))))))

(deftest the-entry-is-told-not-guessed
  (with-instance [p _root]
    (instance/answer p {:credential "oat_v1.nope.nope" :request [] :now now})
    (let [line (slurp (clojure.java.io/file
                       (store/subsystem-dir (:instance p) "obs") "logs" "access.jsonl"))]
      (is (clojure.string/includes? line "internal")
          "没人说是哪扇门时记 :internal，而不是替调用方猜一个"))))

(deftest a-refusal-shaped-as-a-flat-map-is-still-a-refusal
  ;; 拒绝有两种形状：token/env 给 :findings，engine 给一张扁平 map
  ;; （:engine/status :rejected + finding 自己的键）。只看 :findings 的那一版
  ;; 把引擎的拒绝记成了 :outcome :ok——一条说「他读到了」的审计，而他没有。
  (is (= [{:code :engine.gate/unknown-attribute :message "no such thing"}]
         (instance/refusals {:engine/status :rejected
                             :code :engine.gate/unknown-attribute
                             :message "no such thing"})))
  (is (= [{:code :token.authz/expired}]
         (instance/refusals {:findings [{:code :token.authz/expired}]})))
  (is (nil? (instance/refusals {:engine/status :ok :engine/data {}}))
      "放行就是放行，不许被读成拒绝"))

(deftest server-query-log-carries-query-and-source-without-credential
  (with-instance [p _root]
    (let [events (atom [])
          request {:entity {:a.x/id 42} :query [:a.x/name]}
          input {:credential "private-bearer" :request request :now now :entry :data-mcp
                 :source {:remote-addr "127.0.0.1" :path "/mcp/example" :tool "execute_eql"
                          :authorization "private-bearer"}}]
      (with-redefs [token/verify (fn [& _] {:token-id "token-123" :authorization {:graph-id "a/root" :version 1}})
                    env/query (fn [& _] {:engine/status :ok :engine/data {} :env/version 1})
                    obs/log-event! (fn [event data] (swap! events conj [event data]))]
        (let [parts (assoc-in p [:token :graph-enabled?] (constantly true))]
          (instance/answer parts input)
          (is (empty? @events) "Query text requires Server opt-in")
          (instance/answer (assoc parts :query-log? true) input)
          (let [[event data] (first @events)]
            (is (= :query-executed event))
            (is (= (pr-str request) (:eql data)))
            (is (= "token-123" (:token-id data)))
            (is (= "127.0.0.1" (:remote-addr data)))
            (is (= "execute_eql" (:tool data)))
            (is (= :ok (:outcome data)))
            (is (not (clojure.string/includes? (pr-str @events) "private-bearer")))))))))

;; ---------------------------------------------------------------------------
;; D1.9b：Workbench 担保的用户
;; ---------------------------------------------------------------------------

(def ^:private alex {:issuer "workbench" :subject "alex@example.com"})

(defn- answer-with [p verified obo]
  (let [seen (atom nil)]
    (with-redefs [token/verify (fn [& _] verified)
                  env/query (fn [_ opts] (reset! seen opts) {:engine/status :ok :engine/data {} :env/version 1})]
      {:r (instance/answer (assoc-in p [:token :graph-enabled?] (constantly true))
                           (cond-> {:credential "c" :request [:w.item/reason] :now now :entry :data-eql}
                             obo (assoc :on-behalf-of obo)))
       :query @seen})))

(deftest a-token-without-the-capability-cannot-name-a-user
  (with-instance [p _root]
    (let [{:keys [r query]} (answer-with p {:token-id "agent-token" :authorization {:graph-id "a/root" :version 1}} alex)]
      (is (= [:token.authz/user-assertion-forbidden] (mapv :code (:findings r))))
      (is (nil? query) "refused before any query ran")
      (let [line (slurp (io/file (store/subsystem-dir (:instance p) "obs") "logs" "access.jsonl"))]
        (is (clojure.string/includes? line "user-assertion-forbidden"))))))

(deftest a-vouching-token-injects-the-user-and-the-audit-says-who-vouched
  (with-instance [p _root]
    (let [{:keys [r query]} (answer-with p {:token-id "wb-token" :asserts-user? true
                                            :authorization {:graph-id "a/root" :version 1}} alex)]
      (is (= :ok (:engine/status r)))
      (is (= {:open-atlas.caller/email "alex@example.com"} (:caller query)))
      (let [[event] (audited p :token "wb-token")]
        (is (= alex (get-in event [:actor :on-behalf-of])))
        (is (= :workbench-asserted (get-in event [:actor :asserted-by]))
            "the person was vouched for, not verified here")))))

(deftest without-on-behalf-of-nothing-is-injected-and-the-actor-stays-the-service
  (with-instance [p _root]
    (let [{:keys [query]} (answer-with p {:token-id "wb-token" :asserts-user? true
                                          :authorization {:graph-id "a/root" :version 1}} nil)]
      (is (nil? (:caller query)))
      (let [[event] (audited p :token "wb-token")]
        (is (= :verified (get-in event [:actor :asserted-by])))
        (is (not (contains? (:actor event) :on-behalf-of)))))))
