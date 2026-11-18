(ns open.atlas.studio.deploy.deploy-test
  "studio 侧的守门测试。

   最要紧的一条是 **owner 校验真的在做**：没有它，「双主体可审计」只成立一半
   ——server 记得下是谁**声称**触发的，却没有任何地方判过他是不是 owner。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.control-protocol :as cp]
            [open.atlas.studio.deploy :as deploy]
            [open.atlas.studio.graph :as graph]
            [datahike.api :as d]))

(def ^:private actor {:issuer "corp-idp" :subject "00u-alice"})
(def ^:private editor {:issuer "corp-idp" :subject "00u-bob"})

(defn- fresh-db
  "一个只有 workspace 与 graph 登记的最小 studio。"
  []
  (let [cfg {:store {:backend :mem :id (str (gensym "deploy"))}
             :schema-flexibility :read}]
    (d/delete-database cfg)
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (graph/install! conn)
      (graph/register-workspace! conn {:name "sales"
                                       :members [{:principal actor  :role :owner}
                                                 {:principal editor :role :editor}]})
      ;; 菜谱是对象清单（D3.19）。这些用例只关心部署链，池里也没有装料,
      ;; 空清单是它们最诚实的菜谱。
      (graph/register-graph! conn {:workspace "sales" :name "app" :members {}})
      conn)))

;; ---------------------------------------------------------------------------
;; owner 校验 —— D3.12 授权链的第一环
;; ---------------------------------------------------------------------------

(deftest an-owner-may-deploy
  (let [conn (fresh-db)]
    (is (empty? (deploy/check-owner (d/db conn) "sales/app" actor)))))

(deftest an-editor-may-not-deploy
  (testing "**只认 owner，不认 editor**（D5.7）：发布是对消费者的承诺，把
            「自律建模」顺手扩大成「自律发布」正是它拒绝的事"
    (let [conn (fresh-db)
          fs   (deploy/check-owner (d/db conn) "sales/app" editor)]
      (is (= [:deploy.authz/not-owner] (mapv :code fs))))))

(deftest a-stranger-may-not-deploy
  (let [conn (fresh-db)]
    (is (= [:deploy.authz/not-owner]
           (mapv :code (deploy/check-owner (d/db conn) "sales/app"
                                           {:issuer "corp-idp" :subject "00u-nobody"}))))))

(deftest an-owner-of-another-workspace-may-not-deploy-this-graph
  (testing "owner 是**按 workspace** 的，不是全局角色"
    (let [conn (fresh-db)]
      (graph/register-workspace! conn {:name "other"
                                       :members [{:principal editor :role :owner}]})
      (is (= [:deploy.authz/not-owner]
             (mapv :code (deploy/check-owner (d/db conn) "sales/app" editor)))))))

(deftest an-unregistered-graph-is-refused
  (let [conn (fresh-db)]
    (is (= [:deploy.graph/unknown]
           (mapv :code (deploy/check-owner (d/db conn) "sales/nope" actor))))
    (is (= [:deploy.graph/unknown]
           (mapv :code (deploy/check-owner (d/db conn) "not-qualified" actor))))))

;; ---------------------------------------------------------------------------
;; 信封：studio 签的，server 验得过
;; ---------------------------------------------------------------------------

(def ^:private secret "actor-key-for-tests")

(defn- an-envelope [& {:as over}]
  (deploy/envelope
   (merge {:op :activate
           :payload {:graph "sales/app" :version 7 :expected-current nil}
           :deployment-id "dep-1" :request-id "req-1"
           :audience "prod-instance-a"
           :actor-principal actor :actor-key-id "studio-2026-08"
           :actor-secret secret
           :now "2026-08-25T09:00:00Z" :expires-at "2026-08-25T09:05:00Z"}
          over)))

(deftest a-signed-envelope-passes-the-shared-validator
  (testing "信封形状由 **common/control-protocol** 判——两侧共用那一份,
            所以 studio 签出来的东西 server 一定认得（D1.12 同则）"
    (let [e (an-envelope)]
      (is (empty? (cp/validate-envelope e)))
      (is (cp/actor-signature-matches? e secret)))))

(deftest the-signature-covers-the-whole-envelope
  (testing "签完再动任何一个字段都作废——版本、回滚目标、instance、payload 全在内"
    (let [e (an-envelope)]
      (doseq [[what e*] [["版本"     (assoc-in e [:control/payload :version] 8)]
                         ["instance" (assoc e :control/audience "prod-instance-b")]
                         ["请求 ID"  (assoc e :control/request-id "req-2")]
                         ["主体"     (assoc-in e [:control/actor :actor/principal]
                                               {:issuer "corp-idp" :subject "00u-mallory"})]]]
        (is (not (cp/actor-signature-matches? e* secret)) what)))))

(deftest the-actor-principal-is-the-immutable-pair-not-an-email
  (testing "D5.10：邮箱与显示名只能作属性，永远不作主键"
    (is (seq (cp/validate-envelope (an-envelope :actor-principal "alice@corp"))))))

(deftest the-envelope-never-carries-who-the-service-is
  (testing "service 身份来自已认证的通道，**自称的身份不是身份**——信封键面里
            根本没有那个位置"
    (is (not (contains? cp/envelope-keys :control/service-principal)))
    (is (empty? (cp/validate-envelope (an-envelope))))))

;; ---------------------------------------------------------------------------
;; 编排：第一次拒绝就停
;; ---------------------------------------------------------------------------

(deftest deployment-stops-at-the-first-refusal-and-says-where
  (testing "owner 不过就一步都不走——**一个字节都不会送出去**"
    (let [conn (fresh-db)
          out  (deploy/deploy! {:db (d/db conn) :graph "sales/app" :version 7
                                :actor editor
                                :artifact-root "/nonexistent"
                                :request-id-fn (fn [op] (str "req-" (name op)))
                                :audience "x" :actor-key-id "k" :actor-secret secret
                                :now "2026-08-25T09:00:00Z"
                                :expires-at "2026-08-25T09:05:00Z"})]
      (is (= [:deploy.authz/not-owner] (mapv :code (:findings out))))
      (is (empty? (:steps out))))))

(deftest rollback-also-checks-owner-first
  (let [conn (fresh-db)
        out  (deploy/rollback! {:db (d/db conn) :graph "sales/app" :to-version 3
                                :actor editor
                                :request-id-fn (constantly "r")
                                :audience "x" :actor-key-id "k" :actor-secret secret
                                :now "2026-08-25T09:00:00Z"
                                :expires-at "2026-08-25T09:05:00Z"
                                :expected-current 7})]
    (is (= [:deploy.authz/not-owner] (mapv :code (:findings out))))))

;; ---------------------------------------------------------------------------
;; 指针移动的 op 按**方向**选（owner 裁定 2026-08-30）
;;
;; runtime 里 `:activate` 与 `:rollback` 是同一个函数：同一次 CAS、同一次 plan
;; 根绑定、同一次闭包重验、同一道门禁。差别只有目标从哪个 payload 键来，以及
;; **事件里记的 op**。而「往前推」与「往回退」是关于一台机器怎么被运维的两个
;; 不同事实，事件日志是唯一分得开它们的地方——所以这里按方向选，而不是按调用
;; 的函数名选。
;; ---------------------------------------------------------------------------

(defn- captured
  "跑一次 `rollback!`，把送出去的信封抓下来。传输是注入的（`send!` 的整个用意），
   所以不需要网络也不需要一台 runtime。"
  [conn to-version expected-current]
  (let [sent (atom nil)]
    (deploy/rollback! {:db (d/db conn) :graph "sales/app"
                       :to-version to-version :expected-current expected-current
                       :actor actor
                       ;; The deployment id is the one that deployed the TARGET —
                       ;; the evidence has to be that version's own plan (R3.6),
                       ;; which is the same for either direction.
                       :deployment-id (str "sales-app-v" to-version)
                       :request-id-fn (fn [op] (str "req-" (name op)))
                       :audience "x" :actor-key-id "k" :actor-secret secret
                       :now "2026-08-25T09:00:00Z"
                       :expires-at "2026-08-25T09:05:00Z"
                       :send-envelope (fn [_ env] (reset! sent env) {:status 200 :body {}})})
    @sent))

(deftest going-back-is-a-rollback-and-going-forward-is-an-activate
  (let [conn (fresh-db)]
    (testing "更早的目标 → :rollback，目标在 :to-version"
      (let [e (captured conn 3 7)]
        (is (= :rollback (:control/op e)))
        (is (= 3 (get-in e [:control/payload :to-version])))
        (is (= 7 (get-in e [:control/payload :expected-current])))))

    (testing "更晚的目标 → :activate，目标在 :version —— 回滚之后再切回去
              走的就是这条，而它记成 rollback 的话审计就在说反话"
      (let [e (captured conn 7 3)]
        (is (= :activate (:control/op e)))
        (is (= 7 (get-in e [:control/payload :version])))
        (is (nil? (get-in e [:control/payload :to-version])))
        (is (= 3 (get-in e [:control/payload :expected-current])))))

    (testing "什么都没在服务 → :activate：没有更早的状态可回"
      (let [e (captured conn 1 nil)]
        (is (= :activate (:control/op e)))
        (is (= 1 (get-in e [:control/payload :version])))))

    (testing "两种信封都过协议校验 —— payload 键面是按 op 封闭的"
      (is (empty? (cp/validate-envelope (captured conn 3 7))))
      (is (empty? (cp/validate-envelope (captured conn 7 3)))))))

(deftest the-request-id-follows-the-op-so-two-directions-are-two-commands
  (testing "request-id 是一条命令的幂等键。往前与往回是两条命令，共用一个 id
            会让其中一条看起来是另一条的重放"
    (let [conn (fresh-db)]
      (is (not= (:control/request-id (captured conn 3 7))
                (:control/request-id (captured conn 7 3)))))))

;; ---------------------------------------------------------------------------
;; 两侧的 header 名必须一致（studio 不依赖 server，只能靠这条对齐）
;; ---------------------------------------------------------------------------

(deftest the-service-credential-headers-match-the-shell
  (testing "studio 不依赖 server（D1.8），两处的 header 名只能靠这条测试对齐——
            各写一份字符串就是各有一份协议"
    (is (= ["x-atlas-service-key-id" "x-atlas-service-timestamp" "x-atlas-service-signature"]
           [(deploy/header-key-id) (deploy/header-timestamp) (deploy/header-signature)]))))

;; ---------------------------------------------------------------------------
;; owner 检查的 actor **就是**审计里的 actor（D5.10）
;; ---------------------------------------------------------------------------

(deftest the-checked-actor-and-the-audited-actor-are-one-value
  (testing "曾经这里收两个入参：owner 校验用成员表的键，签名与审计用二元组。
            两者不绑定的话，可以拿 Alice 的 owner 资格发请求、而审计完整地记成
            Mallory。现在只有一个 `:actor`——**这种错配在结构上不可能**"
    (let [conn (fresh-db)]
      ;; 同一个值：owner 校验放行它
      (is (empty? (deploy/check-owner (d/db conn) "sales/app" actor)))
      ;; ……而信封里签的、审计里记的也是它
      (let [e (an-envelope :actor-principal actor)]
        (is (= actor (get-in e [:control/actor :actor/principal])))
        (is (cp/actor-signature-matches? e secret)))
      ;; Mallory 拿不到放行，也就没有「用别人的资格、记自己的名字」这条路
      (is (seq (deploy/check-owner (d/db conn) "sales/app"
                                   {:issuer "corp-idp" :subject "00u-mallory"}))))))

(deftest issuer-takes-part-in-the-match-not-only-subject
  (testing "只按 subject 比，两个 IdP 下同名的两个人就成了同一个人"
    (let [conn (fresh-db)]
      (is (seq (deploy/check-owner (d/db conn) "sales/app"
                                   {:issuer "other-idp" :subject "00u-alice"})))))) 

(deftest one-principal-cannot-hold-two-roles-in-a-workspace
  (testing "同一主体同时是 owner 与 editor 时，「这个人是不是 owner」就取决于
            先读到哪一行"
    (let [cfg {:store {:backend :mem :id (str (gensym "dup"))} :schema-flexibility :read}]
      (d/delete-database cfg) (d/create-database cfg)
      (let [conn (d/connect cfg)]
        (graph/install! conn)
        (is (seq (:findings (graph/register-workspace!
                             conn {:name "sales"
                                   :members [{:principal actor :role :owner}
                                             {:principal actor :role :editor}]}))))))))

(deftest an-email-is-an-attribute-not-a-principal
  (testing "D5.10：邮箱与显示名只能作属性，**不参与授权匹配**"
    (let [cfg {:store {:backend :mem :id (str (gensym "attr"))} :schema-flexibility :read}]
      (d/delete-database cfg) (d/create-database cfg)
      (let [conn (d/connect cfg)]
        (graph/install! conn)
        (is (seq (:findings (graph/register-workspace!
                             conn {:name "a" :members [{:principal "alice@corp" :role :owner}]})))
            "裸字符串不再是合法主体")
        (is (empty? (:findings (graph/register-workspace!
                                conn {:name "b"
                                      :members [{:principal actor :role :owner
                                                 :attributes {:email "alice@corp"
                                                              :display-name "Alice"}}]})))
            "要留邮箱就放 :attributes")))))
