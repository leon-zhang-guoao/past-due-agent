(ns open.atlas.runtime.graph.command-test
  "五个命令的守门测试。

   **一个目录都不铺、一个进程都不起**：判定是纯的（D3.16），`reads` 是几个
   返回常量的函数。CAS 冲突、plan 被改、digest 对不上、闭包缺件、重放，
   全都是一次函数调用。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.control-protocol]
            [open.atlas.runtime.graph :as control]))

(def ^:private instance-id "prod-instance-a")
(def ^:private svc {:issuer "corp-idp" :subject "svc-studio-prod"})
(def ^:private actor {:issuer "corp-idp" :subject "00u123"})

;; --- 两份真制品：core 是被引用方，app 引用它 --------------------------------

(defn- seal [model refs]
  (let [enc (artifact/encode-content model refs)
        s   (artifact/seal-graph (:content-edn enc) (:content-hash enc)
                                 {:workspace "t" :graph "g" :version 1
                                  :cut-at "2026-08-24T00:00:00Z" :cut-by "test"}
                                 {:findings [] :carried-only-keys []
                                  :row-security-declared []}
                                 {:previous-version nil :added-attributes []
                                  :removed-attributes [] :modified-objects []}
                                 {:attribute-count 0})]
    {:files (:files s) :digest (:artifact-digest s) :content-hash (:content-hash enc)}))

(def ^:private core (delay (seal {:namespaces {"core.a" {:kind :master}}} [])))
(def ^:private app
  (delay (seal {:namespaces {"app.b" {:kind :master}}}
               [{:workspace "core-team" :graph "core" :version 3
                 :content-hash (:content-hash @core)}])))

(def ^:private app-v3 (delay (seal {:namespaces {"app.old" {:kind :master}}} [])))

(defn- member [qn v {:keys [digest content-hash]}]
  {:graph qn :version v :content-hash content-hash :artifact-digest digest})

(defn- plan-closure []
  [(member "core-team/core" 3 @core)
   (member "sales/app" 7 @app)])

;; --- reads：默认什么都没有，逐个用例往里塞 ----------------------------------

(def ^:private actor-secret "actor-key-for-tests")
(def ^:private now "2026-08-24T09:01:00Z")

(defn- reads [& {:keys [deployment request staged shelf receipt pointer event allow?]
                 :or   {allow? true}}]
  {:deployment      (fn [_] deployment)
   :request         (fn [_] request)
   :event           (fn [_ _] event)
   :staged-artifact (fn [hex] (get staged hex))
   :shelf-artifact  (fn [c] (get shelf ((juxt :workspace :graph :version) c)))
   :receipt         (fn [c] (get receipt ((juxt :workspace :graph :version) c)))
   :pointer         (fn [_] pointer)
   :actor-key       (fn [kid] (when (= "k" kid) actor-secret))
   :service-allows? (fn [_ _] allow?)
   ;; **S5.7 的门禁显式注入**：缺席即拒绝激活（fail-closed）。测试里放行也要
   ;; 写出来——一句看得见的「这里不查」比一个安静的缺省好查得多。
   :row-security-gate (constantly [])})

(defn- env [op payload & {:as over}]
  (merge {:control/version 1 :control/op op
          :control/request-id "req-1" :control/deployment-id "dep-1"
          :control/audience instance-id
          :control/issued-at "2026-08-24T09:00:00Z"
          :control/expires-at "2026-08-24T09:05:00Z"
          :control/actor {:actor/principal actor :actor/key-id "k"}
          :control/payload payload}
         over))

(defn- signed
  "把 actor 签名补上——**认证是真做的**，未签名的信封在入口就被拒。"
  [e]
  (assoc-in e [:control/actor :actor/signature]
            (open.atlas.common.control-protocol/sign-actor
             (assoc-in e [:control/actor :actor/signature] nil) actor-secret)))

(defn- ctx [] {:instance-id instance-id :service-principal svc :now now})
(defn- run [rds e] (control/decide rds (signed e) (ctx)))
(defn- codes [out] (into (sorted-set) (map :code) (:findings out)))
(defn- kinds [out] (mapv :effect/kind (:effects out)))

;; ---------------------------------------------------------------------------
;; 前置：audience / 幂等 / plan
;; ---------------------------------------------------------------------------

(deftest an-envelope-for-another-instance-is-refused
  (testing "在这里验得过、却是发给别处的信封，就是一次重放"
    (is (contains? (codes (run (reads) (env :offer {:graph "sales/app" :version 7
                                                    :closure (plan-closure)}
                                            :control/audience "prod-instance-b")))
                   :deploy.audience/mismatch))))

(deftest the-same-request-id-with-a-different-envelope-is-refused
  (let [e   (env :offer {:graph "sales/app" :version 7 :closure (plan-closure)})
        rec {:envelope-hash "sha256:something-else" :result {:missing []}}]
    (is (contains? (codes (run (reads :request rec) e))
                   :deploy.request/envelope-conflict))))

(deftest the-same-request-id-with-the-same-envelope-returns-the-original-result
  (testing "严格幂等：重试同一条命令拿回同一个答案，且**不产生任何 effect**"
    (let [e   (env :offer {:graph "sales/app" :version 7 :closure (plan-closure)})
          rec {:envelope-hash (control/plan-hash (signed e)) :result {:missing [:original]}}
          out (run (reads :request rec) e)]
      (is (= {:missing [:original]} (:result out)))
      (is (true? (:replayed out)))
      (is (empty? (:effects out))))))

(deftest the-plan-is-immutable-under-one-deployment-id
  (testing "否则「重算 missing」会变成「原地改部署计划」，而 plan 正是那条可信
            digest 链的锚"
    (let [e      (env :offer {:graph "sales/app" :version 7 :closure (plan-closure)})
          stored {:root {:graph "sales/app" :version 7}
                  :audience instance-id
                  :closure [(member "core-team/core" 3 @core)]}]  ; 少一个成员
      (is (contains? (codes (run (reads :deployment stored) e))
                     :deploy.plan/immutable)))))

(deftest a-new-request-id-under-the-same-deployment-recomputes-missing
  (testing "同 deployment-id + 新 request-id：重读当前状态、重算 missing"
    (let [e      (env :offer {:graph "sales/app" :version 7 :closure (plan-closure)}
                      :control/request-id "req-2")
          stored {:root {:graph "sales/app" :version 7}
                  :audience instance-id
                  :closure (vec (sort-by (juxt :graph :version) (plan-closure)))}
          ;; core 已经在暂存区里了 → 只缺 app
          out    (run (reads :deployment stored
                             :staged {(control/digest-hex (:digest @core)) (:files @core)})
                      e)]
      (is (= ["sales/app"] (mapv :graph (:missing (:result out)))))
      (is (not (some #{:write-deployment} (kinds out))) "plan 已在，不重写")
      ;; 幂等记录照写：新的 request-id 是一条**新命令**，它的答案该被记下来
      (is (= [:write-request] (kinds out))))))

;; ---------------------------------------------------------------------------
;; offer
;; ---------------------------------------------------------------------------

(deftest offer-on-an-empty-shelf-asks-for-everything-and-saves-the-plan
  (let [out (run (reads) (env :offer {:graph "sales/app" :version 7
                                      :closure (plan-closure)}))]
    (is (= 2 (count (:missing (:result out)))))
    (is (= [:write-deployment :write-request] (kinds out)))))

(deftest a-member-already-loaded-is-not-asked-for-again
  (let [coords ["core-team" "core" 3]
        out (run (reads :shelf   {coords (:files @core)}
                        :receipt {coords {:artifact-digest (:digest @core)}})
                 (env :offer {:graph "sales/app" :version 7 :closure (plan-closure)}))]
    (is (= ["sales/app"] (mapv :graph (:missing (:result out)))))))

;; ---------------------------------------------------------------------------
;; stage
;; ---------------------------------------------------------------------------

(deftest staging-without-a-plan-is-refused
  (testing "offer 先行——它才是把坐标绑到可信 digest 的那一步"
    (is (contains? (codes (run (reads) (env :stage {:artifact-digest (:digest @core)
                                                    :files (:files @core)})))
                   :deploy.plan/unknown))))

(deftest staging-something-outside-the-plan-is-refused
  (testing "一个已认证但跑偏的 service 不该能往这台机器上灌任意制品"
    (let [stored {:root {:graph "sales/app" :version 7} :audience instance-id
                  :closure [(member "sales/app" 7 @app)]}]
      (is (contains? (codes (run (reads :deployment stored)
                                 (env :stage {:artifact-digest (:digest @core)
                                              :files (:files @core)})))
                     :deploy.plan/not-in-plan)))))

(deftest the-digest-is-recomputed-from-the-bytes
  (testing "期望值来自已认证信封，但**字节自己得算得出同一个值**"
    (let [stored {:root {:graph "sales/app" :version 7} :audience instance-id
                  :closure (plan-closure)}
          tampered (assoc (:files @core) "report.edn"
                          (str (get (:files @core) "report.edn") " "))]
      (is (contains? (codes (run (reads :deployment stored)
                                 (env :stage {:artifact-digest (:digest @core)
                                              :files tampered})))
                     :deploy.stage/digest-mismatch)))))

(deftest a-good-stage-lands-content-addressed
  (let [stored {:root {:graph "sales/app" :version 7} :audience instance-id
                :closure (plan-closure)}
        out    (run (reads :deployment stored)
                    (env :stage {:artifact-digest (:digest @core) :files (:files @core)}))]
    (is (= [:stage-artifact :write-request] (kinds out)))
    (is (= (control/digest-hex (:digest @core)) (:digest-hex (first (:effects out))))
        "目录名是去掉 sha256: 前缀的 64 位——冒号不是合法路径段")))

;; ---------------------------------------------------------------------------
;; load
;; ---------------------------------------------------------------------------

(def ^:private stored-plan
  (delay {:root {:graph "sales/app" :version 7} :audience instance-id
          :closure (vec (sort-by (juxt :graph :version) (plan-closure)))}))

(deftest loading-with-a-member-absent-is-refused
  (testing "R3.3：传递引用的每个版本都得在场，依赖检查落在**装载**上。

            core 不在场时，`read-artifact` 的一跳闭包校验先点名它——回落的
            `artifact.*` code **原样透传**，不重贴一层 `deploy.*` 的皮（那会让
            下游按 code 分流时漏掉一半）。plan 那一头也报：许诺了却到不了。"
    (let [out (run (reads :deployment @stored-plan
                          :staged {(control/digest-hex (:digest @app)) (:files @app)})
                   (env :load {:graph "sales/app" :version 7}))]
      (is (contains? (codes out) :artifact.reference/missing))
      (is (contains? (codes out) :deploy.closure/plan-mismatch))
      (is (empty? (:effects out)) "**验证通过之前不产生任何 effect**"))))

(deftest an-incomplete-plan-is-refused-at-load-not-only-at-activate
  (testing "**这是「只逐个装 plan 列出的成员」抓不到的那一个**：plan 漏掉 core,
            而 core 恰好在架上。字节全在场，装载照样该拒——R3.3 说依赖检查落在
            装载上，而 receipt 一旦签发就是不可改写的证据，等到激活才拒已经太晚"
    (let [thin {:root {:graph "sales/app" :version 7} :audience instance-id
                :closure [(member "sales/app" 7 @app)]}   ; 漏掉 core
          out  (run (reads :deployment thin
                           :shelf  {["core-team" "core" 3] (:files @core)}
                           :staged {(control/digest-hex (:digest @app)) (:files @app)})
                    (env :load {:graph "sales/app" :version 7}))]
      (is (contains? (codes out) :deploy.closure/plan-mismatch))
      (is (empty? (:effects out)) "一份 receipt 都不签"))))

(deftest a-good-load-lands-then-receipts-in-that-order
  (testing "**先落内容，后落 marker**：崩在中间留下的是「有目录无 receipt」,
            而那不被当作成功状态"
    (let [out (run (reads :deployment @stored-plan
                          :staged {(control/digest-hex (:digest @core)) (:files @core)
                                   (control/digest-hex (:digest @app))  (:files @app)})
                   (env :load {:graph "sales/app" :version 7}))]
      (is (= [:land-artifact :write-receipt :land-artifact :write-receipt :write-request]
             (kinds out))))))

(deftest an-artifact-dir-without-a-receipt-gets-its-receipt-backfilled
  (testing "「目录先落、receipt 后提交」的可恢复路径：重算 digest 相符就补 receipt"
    (let [out (run (reads :deployment @stored-plan
                          :shelf {["core-team" "core" 3] (:files @core)}
                          :staged {(control/digest-hex (:digest @app)) (:files @app)})
                   (env :load {:graph "sales/app" :version 7}))]
      (is (= [:write-receipt :land-artifact :write-receipt :write-request] (kinds out))
          "core 只补 receipt，app 才是落盘 + receipt"))))

(deftest a-shelf-entry-with-a-different-digest-is-refused
  (testing "张冠李戴的字节：**verify-plan 以 plan 的 digest 为期望值重读了每一份**,
            所以在产生任何 effect 之前就被挡住"
    (let [out (run (reads :deployment @stored-plan
                          :shelf {["core-team" "core" 3] (:files @app)})
                   (env :load {:graph "sales/app" :version 7}))]
      (is (seq (:findings out)))
      (is (empty? (:effects out)) "一个字节都不写"))))

(deftest loading-a-root-the-plan-was-not-authenticated-for-is-refused
  (is (contains? (codes (run (reads :deployment @stored-plan)
                             (env :load {:graph "sales/app" :version 8})))
                 :deploy.plan/immutable)))

;; ---------------------------------------------------------------------------
;; activate / rollback
;; ---------------------------------------------------------------------------

(def ^:private loaded-reads
  (delay (reads :deployment @stored-plan
                :shelf   {["core-team" "core" 3] (:files @core)
                          ["sales" "app" 7]      (:files @app)}
                :receipt {["core-team" "core" 3] {:artifact-digest (:digest @core)}
                          ["sales" "app" 7]      {:artifact-digest (:digest @app)}})))

(deftest a-stale-expectation-is-refused
  (testing "CAS：有人在中间动过指针"
    (let [rds (assoc @loaded-reads :pointer (fn [_] {:version 6 :head-event-id "other"}))
          out (control/decide rds (signed (env :activate {:graph "sales/app" :version 7
                                                  :expected-current 5}))
                              (ctx))]
      (is (contains? (codes out) :deploy.activate/stale-expectation)))))

(deftest a-first-activation-expects-nil
  (testing "nil = 「我认为这张图从没激活过」，是一句断言"
    (let [out (control/decide @loaded-reads
                              (signed (env :activate {:graph "sales/app" :version 7
                                              :expected-current nil}))
                              (ctx))]
      (is (empty? (:findings out)))
      (is (= [:append-event :set-pointer :write-request] (kinds out))
          "**先落事件，后切指针**：指针是 commit marker")
      (is (= {:from nil :to 7} (:result out))))))

(deftest a-committed-request-replays-the-original-result-out-of-the-event
  (testing "崩在「切了指针、还没写 requests」时：事件 id 就是 request-id，所以
            「我这次请求应用过没有」答得出来——不看这一条会报一次**假拒绝**。

            而且返回的必须是**事件里存着的原始结果**：现算给出 `{:from 7 :to 7}`,
            第一次的答案却是 `{:from nil :to 7}`——同一条请求两个答案，严格幂等
            就已经破了。"
    (let [e   (signed (env :activate {:graph "sales/app" :version 7 :expected-current nil}))
          ev  {:event/id "req-1" :envelope-hash (control/plan-hash e)
               :result {:from nil :to 7}}
          rds (assoc @loaded-reads
                     :pointer (fn [_] {:version 7 :head-event-id "req-1"})
                     :event   (fn [_ _] ev))
          out (control/decide rds e (ctx))]
      (is (empty? (:findings out)))
      (is (= {:from nil :to 7} (:result out)) "原始结果，不是现算的 {:from 7 :to 7}")
      (is (true? (:replayed out)))
      (is (empty? (:effects out)) "已提交的东西不再写第二遍"))))

(deftest a-pointer-whose-head-event-is-gone-is-a-broken-chain
  (let [e   (signed (env :activate {:graph "sales/app" :version 7 :expected-current nil}))
        rds (assoc @loaded-reads :pointer (fn [_] {:version 7 :head-event-id "req-1"})
                   :event (fn [_ _] nil))]
    (is (contains? (codes (control/decide rds e (ctx))) :deploy.pointer/broken-chain))))

(deftest activation-reverifies-the-whole-closure-against-receipt-digests
  (testing "D3.15 的 invalid 比「引用不在架子上」宽：这里把被引用方的字节换掉,
            digest 就对不上——依据是 receipt 里的值，制品文件里没有可信自报值"
    (let [rds (assoc @loaded-reads
                     :shelf-artifact
                     (fn [c] (get {["core-team" "core" 3] (:files @app)   ; 被换过
                                   ["sales" "app" 7]      (:files @app)}
                                  ((juxt :workspace :graph :version) c))))
          out (control/decide rds (signed (env :activate {:graph "sales/app" :version 7
                                                  :expected-current nil}))
                              (ctx))]
      (is (seq (:findings out)))
      (is (empty? (:effects out)) "**不下线已经在跑的**，只是挡住这次新激活"))))

(deftest a-member-without-a-receipt-blocks-activation
  (let [rds (assoc @loaded-reads :receipt (fn [_] nil))
        out (control/decide rds (signed (env :activate {:graph "sales/app" :version 7
                                                :expected-current nil}))
                            (ctx))]
    (is (contains? (codes out) :deploy.activate/not-loaded))))

(deftest the-plan-must-be-rooted-at-the-version-being-pointed-at
  (testing "不绑的话，拿 app@v7 的 plan 验完闭包、再把指针指向 v3 是通的——
            那等于用 A 的证据给 B 背书"
    (let [rds (assoc @loaded-reads :pointer (fn [_] {:version 7 :head-event-id "evt-old"}))
          out (control/decide rds (signed (env :rollback {:graph "sales/app" :to-version 3
                                                          :expected-current 7}))
                              (ctx))]
      (is (contains? (codes out) :deploy.plan/immutable)
          "@stored-plan 的根是 app@7，回滚到 v3 必须拿 v3 自己那一版的 plan"))))

(deftest rollback-is-the-same-move-to-an-earlier-target
  (testing "回滚不是另一种操作，是切到一个更早的目标——写成两份实现，CAS 或
            重验哪天只改了一处，那一处就成了后门。证据是**目标那一版**的 plan。"
    (let [v3-plan {:root {:graph "sales/app" :version 3} :audience instance-id
                   :closure [(member "sales/app" 3 @app-v3)]}
          rds (assoc @loaded-reads
                     :deployment (fn [_] v3-plan)
                     :pointer    (fn [_] {:version 7 :head-event-id "evt-old"})
                     :shelf-artifact  (fn [c] (get {["sales" "app" 3] (:files @app-v3)}
                                                   ((juxt :workspace :graph :version) c)))
                     :receipt         (fn [c] (get {["sales" "app" 3]
                                                    {:artifact-digest (:digest @app-v3)}}
                                                   ((juxt :workspace :graph :version) c))))
          out (control/decide rds (signed (env :rollback {:graph "sales/app" :to-version 3
                                                  :expected-current 7}))
                              (ctx))]
      (is (empty? (:findings out)))
      (is (= [:append-event :set-pointer :write-request] (kinds out)))
      (is (= {:from 7 :to 3} (:result out)))
      (is (= :rollback (get-in (:effects out) [0 :event :op])))
      (is (= "evt-old" (get-in (:effects out) [0 :event :previous-event-id]))
          "事件链要能走：previous 指向上一个 head")
      (is (= "req-1" (get-in (:effects out) [1 :pointer :head-event-id]))
          "指针带 head event-id——它才是 commit marker，恢复靠它走链"))))

;; ---------------------------------------------------------------------------
;; finding 注册表
;; ---------------------------------------------------------------------------

(deftest every-emitted-code-is-registered
  (doseq [out [(run (reads) (env :stage {:artifact-digest (:digest @core)
                                         :files (:files @core)}))
               (run (reads) (env :offer {:graph "x/y" :version 1 :closure []}))
               (run (reads :deployment @stored-plan) (env :load {:graph "sales/app" :version 7}))]]
    (doseq [f (:findings out)]
      (is (contains? control/all-finding-codes (:code f)) (pr-str f))
      (is (control/finding? f) (pr-str f)))))

;; ---------------------------------------------------------------------------
;; 认证：签名 / 时效 / 权限 —— 真的在做，不只写在文档里
;; ---------------------------------------------------------------------------

(deftest an-unsigned-or-tampered-envelope-is-refused
  (let [e (signed (env :offer {:graph "sales/app" :version 7 :closure (plan-closure)}))]
    (testing "合法签名放行"
      (is (empty? (:findings (control/decide (reads) e (ctx))))))
    (testing "签完再改一个字段——签名覆盖整个信封，改哪儿都作废"
      (is (contains? (codes (control/decide (reads) (assoc e :control/request-id "req-9") (ctx)))
                     :control.signature/mismatch)))
    (testing "钥匙 ID 不认识：拒绝，不回退到某个默认钥匙"
      (is (contains? (codes (control/decide (reads) (assoc-in e [:control/actor :actor/key-id] "?")
                                            (ctx)))
                     :control.signature/unknown-key)))))

(deftest an-expired-envelope-is-refused
  (let [e (signed (env :offer {:graph "sales/app" :version 7 :closure (plan-closure)}))]
    (is (contains? (codes (control/decide (reads) e (assoc (ctx) :now "2026-08-24T09:06:00Z")))
                   :control.signature/expired))
    (is (contains? (codes (control/decide (reads) e (assoc (ctx) :now "2026-08-24T08:59:00Z")))
                   :control.signature/not-yet-valid))))

(deftest a-service-without-permission-is-refused
  (testing "server 的控制面只回答「谁有权对这台机器做这五件事」（D3.12）"
    (is (contains? (codes (run (reads :allow? false)
                               (env :offer {:graph "sales/app" :version 7
                                            :closure (plan-closure)})))
                   :deploy.authz/service-not-permitted))))

;; ---------------------------------------------------------------------------
;; 闭包重验：真遍历，不是走清单
;; ---------------------------------------------------------------------------

(deftest a-reference-the-plan-never-mentioned-cannot-ride-along
  (testing "**这是「走清单」抓不到的那一个**：plan 漏掉 core，而 core 恰好在架上
            且 app 引用它。root 的一跳 hash 会过，但没有人为 core 背过书"
    (let [thin {:root {:graph "sales/app" :version 7} :audience instance-id
                :closure [(member "sales/app" 7 @app)]}   ; 漏掉 core
          rds  (assoc @loaded-reads :deployment (fn [_] thin))
          out  (control/decide rds (signed (env :activate {:graph "sales/app" :version 7
                                                           :expected-current nil}))
                               (ctx))]
      (is (contains? (codes out) :deploy.closure/plan-mismatch))
      (is (empty? (:effects out))))))

(deftest a-plan-member-nothing-reaches-is-refused
  (testing "反方向：清单许诺了一个引用图里到不了的成员"
    (let [fat {:root {:graph "sales/app" :version 7} :audience instance-id
               :closure (conj (vec (plan-closure)) (member "ghost/g" 1 @app-v3))}
          rds (assoc @loaded-reads :deployment (fn [_] fat))
          out (control/decide rds (signed (env :activate {:graph "sales/app" :version 7
                                                          :expected-current nil}))
                              (ctx))]
      (is (contains? (codes out) :deploy.closure/plan-mismatch)))))

;; ---------------------------------------------------------------------------
;; receipt 不可改写
;; ---------------------------------------------------------------------------

(deftest a-receipt-for-a-different-digest-is-refused-without-looking-at-the-shelf
  (testing "receipt 是「那个期望 digest 当初来自已认证信封」的唯一证据。
            让它被一份「字节碰巧对得上新期望值」的推断改写，等于把一条历史改成
            另一条——而且改的正是那份证据本身"
    (let [coords ["core-team" "core" 3]
          out (run (reads :deployment @stored-plan
                          ;; 字节**恰好**与 plan 的期望值相符，但 receipt 记的是别的
                          :shelf   {coords (:files @core)}
                          :receipt {coords {:artifact-digest (:digest @app-v3)}}
                          :staged  {(control/digest-hex (:digest @app)) (:files @app)})
                   (env :load {:graph "sales/app" :version 7}))]
      (is (contains? (codes out) :deploy.load/receipt-conflict))
      (is (empty? (:effects out)) "一个字节都不写"))))

;; ---------------------------------------------------------------------------
;; 外部 ID 不直接进路径
;; ---------------------------------------------------------------------------

(deftest external-ids-are-hashed-before-they-become-path-segments
  (testing "request-id / deployment-id 只被要求非空，可以含 `/` 或 `..`"
    (doseq [id ["req-1" "../../etc/passwd" "a/b/c" ""]]
      (is (re-matches #"[0-9a-f]{64}" (control/path-segment id))
          (str "不是合法路径段：" (pr-str id))))
    (is (not= (control/path-segment "a") (control/path-segment "b")))))

;; ---------------------------------------------------------------------------
;; 行级安全门禁的**激活**那一处（E1 / D5.14 的三处之一）
;; ---------------------------------------------------------------------------

(deftest a-blocked-closure-fails-activation-and-the-pointer-keeps-its-value
  (testing "激活是 D5.14 门禁的三处读之一。判据翻向之后拦的是「声明了但接线
            核对没通过」的成员——拦这个动作本身必须还在，而且必须排在任何
            effect **之前**：指针保持原值正是「已有 latest token 不构成绕过」
            的实现方式（S5.7）"
    (let [blocked [{:code :rowsec.gate/blocked :severity :error :path [:closure]
                    :message "declared but not verified"
                    :got {:graph "sales/app" :version 7
                          :resolvers ["resolver:orders_by_tenant"]}}]
          out (control/decide (assoc @loaded-reads :row-security-gate (constantly blocked))
                              (signed (env :activate {:graph "sales/app" :version 7
                                                      :expected-current nil}))
                              (ctx))]
      (is (= [:rowsec.gate/blocked] (mapv :code (:findings out))))
      (is (= ["resolver:orders_by_tenant"] (:resolvers (:got (first (:findings out)))))
          "拒绝点名未核对的 resolver")
      (is (empty? (:effects out)) "一个 effect 都不产生——指针一个字节不动")
      (is (nil? (:result out))))))
