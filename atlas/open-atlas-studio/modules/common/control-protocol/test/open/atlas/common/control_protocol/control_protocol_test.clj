(ns open.atlas.common.control-protocol.control-protocol-test
  "控制面协议的守门测试（D3.16）。

   两条承重的性质各有一组：**信封键面封闭**（多一个键就是拒绝，因为签名覆盖
   整个信封）与**签名输入两侧逐字节相同**（studio 签、server 验，算的必须是同
   一串字节——只断言「验得过」的话，两边同时错也是绿的）。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.control-protocol :as cp]))

(def ^:private secret "test-actor-key-do-not-use")

(defn- envelope
  "一份形状合法的 `:activate` 信封（未签名）。"
  [& {:as overrides}]
  (merge
   {:control/version       1
    :control/op            :activate
    :control/request-id    "req-0001"
    :control/deployment-id "dep-0001"
    :control/audience      "prod-instance-a"
    :control/issued-at     "2026-08-24T09:00:00Z"
    :control/expires-at    "2026-08-24T09:05:00Z"
    :control/actor         {:actor/principal {:issuer "corp-idp" :subject "00u123"}
                            :actor/key-id    "studio-prod-2026-08"
                            :actor/signature "hmac-sha256:0000000000000000000000000000000000000000000000000000000000000000"}
    :control/payload       {:graph "sales/core" :version 7 :expected-current 6}}
   overrides))

(defn- codes [fs] (into (sorted-set) (map :code) fs))

;; ---------------------------------------------------------------------------
;; 信封：键面封闭
;; ---------------------------------------------------------------------------

(deftest a-well-formed-envelope-has-no-findings
  (is (empty? (cp/validate-envelope (envelope)))))

(deftest an-unknown-top-level-key-is-refused-not-ignored
  (testing "**签名覆盖整个信封**，所以未知键既不能忽略（攻击者可以在签名够不着
            的地方塞东西）也不能保留（两侧对「签的是什么」就分了家）"
    (is (= #{:control.envelope/malformed}
           (codes (cp/validate-envelope (envelope :control/extra "x")))))))

(deftest every-envelope-key-is-required
  (doseq [k cp/envelope-keys]
    (is (seq (cp/validate-envelope (dissoc (envelope) k)))
        (str "缺 " k " 应当拒绝"))))

(deftest an-unknown-op-is-refused
  (testing "不认识的动词被静默忽略，调用方会以为自己部署过了"
    (is (contains? (codes (cp/validate-envelope (envelope :control/op :deploy)))
                   :control.envelope/unknown-op))))

(deftest only-protocol-version-1-is-accepted
  (is (contains? (codes (cp/validate-envelope (envelope :control/version 2)))
                 :control.envelope/unsupported-version)))

;; ---------------------------------------------------------------------------
;; actor 主体：D5.10 的不可变二元组
;; ---------------------------------------------------------------------------

(deftest an-email-is-not-a-principal
  (testing "D5.10：邮箱与显示名只能作属性，永远不作主键——人会改名、换邮箱,
            离职后邮箱被回收给新人，而审计必须仍指向同一个人"
    (let [fs (cp/validate-envelope
              (envelope :control/actor (assoc (:control/actor (envelope))
                                              :actor/principal "alice@corp")))]
      (is (contains? (codes fs) :control.envelope/malformed))
      (is (some #(= [:control/actor :actor/principal] (:path %)) fs)))))

(deftest a-principal-needs-both-issuer-and-subject
  (doseq [p [{:issuer "corp-idp"} {:subject "00u123"} {}]]
    (is (seq (cp/validate-envelope
              (envelope :control/actor (assoc (:control/actor (envelope))
                                              :actor/principal p))))
        (str "不完整的主体应当拒绝：" (pr-str p)))))

;; ---------------------------------------------------------------------------
;; payload：每个 op 各自封闭
;; ---------------------------------------------------------------------------

(deftest expected-current-nil-is-an-assertion-not-a-skip
  (testing "nil = 「我认为这张图从没激活过」，是一句断言；键**不能缺席**"
    (is (empty? (cp/validate-envelope
                 (envelope :control/payload {:graph "sales/core" :version 1
                                             :expected-current nil}))))
    (is (seq (cp/validate-envelope
              (envelope :control/payload {:graph "sales/core" :version 1})))
        "缺 :expected-current 是拒绝，不是「不检查」")))

(deftest rollback-needs-an-explicit-target
  (testing "「回上一个」在连回滚两次之后指哪儿说不清，而且它不幂等"
    (is (empty? (cp/validate-envelope
                 (envelope :control/op :rollback
                           :control/payload {:graph "sales/core" :to-version 6
                                             :expected-current 7}))))))

(deftest a-closure-member-must-carry-its-artifact-digest
  (testing "server 要靠它在 load/activate 时重新校验——制品文件里没有可信的
            自报值（D4.3 刻意不把 digest 写回三份文件）"
    (let [h  (fn [c] (str "sha256:" (apply str (repeat 64 c))))
          ;; 清单**含根**，所以 fixture 里必须有 sales/core@7 自己
          root {:graph "sales/core" :version 7 :content-hash (h "c") :artifact-digest (h "d")}
          ok   {:graph "core-team/core" :version 3 :content-hash (h "a") :artifact-digest (h "b")}
          env  #(envelope :control/op :offer
                          :control/payload {:graph "sales/core" :version 7 :closure %})]
      (is (empty? (cp/validate-envelope (env [root ok]))))
      (is (seq (cp/validate-envelope (env [root (dissoc ok :artifact-digest)])))))))

(deftest the-closure-must-name-the-root-exactly-once
  (let [h    (fn [c] (str "sha256:" (apply str (repeat 64 c))))
        root {:graph "sales/core" :version 7 :content-hash (h "c") :artifact-digest (h "d")}
        env  #(envelope :control/op :offer
                        :control/payload {:graph "sales/core" :version 7 :closure %})]
    (testing "清单含根，server 才能只做一次集合差、不必猜根算不算在内"
      (is (seq (cp/validate-envelope (env [])))  "清单里没有根")
      (is (empty? (cp/validate-envelope (env [root])))))))

(deftest a-duplicate-coordinate-in-the-closure-is-refused
  (testing "重复坐标会被下游的 index **静默折叠**：两条不同的 digest 落到同一个键上,
            赢的那条取决于遍历顺序"
    (let [h    (fn [c] (str "sha256:" (apply str (repeat 64 c))))
          root {:graph "sales/core" :version 7 :content-hash (h "c") :artifact-digest (h "d")}
          dup  (assoc root :artifact-digest (h "e"))]
      (is (seq (cp/validate-envelope
                (envelope :control/op :offer
                          :control/payload {:graph "sales/core" :version 7
                                            :closure [root dup]})))))))

(deftest a-timestamp-must-be-a-real-date-not-just-the-right-shape
  (testing "正则只管拼法：`2026-13-45T99:99:99Z` 过得了正则，解析不出来"
    (doseq [bad ["2026-13-01T00:00:00Z" "2026-02-30T00:00:00Z" "2026-01-01T25:00:00Z"]]
      (is (seq (cp/validate-envelope (envelope :control/issued-at bad)))
          (str "不该接受：" bad)))))

(deftest a-digest-must-be-sha256-plus-64-lowercase-hex
  (doseq [bad ["sha256:short" "deadbeef" (str "SHA256:" (apply str (repeat 64 "a")))
               (str "sha256:" (apply str (repeat 64 "A")))]]
    (is (seq (cp/validate-envelope
              (envelope :control/op :stage
                        :control/payload {:artifact-digest bad :files {}})))
        (str "不该接受的 digest：" bad))))

;; ---------------------------------------------------------------------------
;; 签名输入 —— 两侧算的必须是同一串字节
;; ---------------------------------------------------------------------------

(deftest the-signing-input-covers-the-whole-envelope
  (testing "只绑 op + graph 不够：同一张断言仍可被改成另一个版本号、另一个回滚
            目标、另一台 instance，或换一份 payload 重放。逐个字段改一处，
            签名输入就必须变"
    (let [base (cp/actor-signing-input (envelope))]
      (doseq [[what env*] [["版本号"   (envelope :control/payload
                                                 {:graph "sales/core" :version 8
                                                  :expected-current 6})]
                           ["op"       (envelope :control/op :rollback
                                                 :control/payload
                                                 {:graph "sales/core" :to-version 6
                                                  :expected-current 7})]
                           ["instance" (envelope :control/audience "prod-instance-b")]
                           ["graph"    (envelope :control/payload
                                                 {:graph "other/graph" :version 7
                                                  :expected-current 6})]
                           ["请求 ID"  (envelope :control/request-id "req-0002")]
                           ["有效期"   (envelope :control/expires-at "2026-08-24T10:00:00Z")]
                           ["主体"     (envelope :control/actor
                                                 (assoc (:control/actor (envelope))
                                                        :actor/principal
                                                        {:issuer "corp-idp" :subject "00u999"}))]
                           ["钥匙 ID"  (envelope :control/actor
                                                 (assoc (:control/actor (envelope))
                                                        :actor/key-id "other-key"))]]]
        (is (not= base (cp/actor-signing-input env*))
            (str what "改了，签名输入却没变"))))))

(deftest only-the-signature-field-is-carved-out
  (testing "挖的只是签名那一个字段。挖多了就把 :actor/principal 与
            :actor/key-id 排除在签名外——那正好是签名要防的东西"
    (let [a (cp/actor-signing-input (envelope))
          b (cp/actor-signing-input
             (envelope :control/actor (assoc (:control/actor (envelope))
                                             :actor/signature "hmac-sha256:ffff")))]
      (is (= a b) "换一个签名值，签名输入不变")
      (is (not (re-find #"actor/signature" a))
          "签名字段本身不在输入里（否则自引用）"))))

(deftest signing-input-is-stable-across-key-order
  (testing "规范 EDN：书写顺序不进内容，两侧各自构造的 map 算出同一串字节"
    (let [e1 (envelope)
          e2 (into (sorted-map-by (fn [a b] (compare (str b) (str a)))) e1)]
      (is (= (cp/actor-signing-input e1) (cp/actor-signing-input e2))))))

(deftest a-signature-round-trips-and-a-tampered-envelope-does-not
  (let [env    (envelope)
        signed (assoc-in env [:control/actor :actor/signature] (cp/sign-actor env secret))]
    (is (cp/well-formed-signature? (get-in signed [:control/actor :actor/signature])))
    (is (cp/actor-signature-matches? signed secret))
    (testing "改 payload 之后同一签名验不过——重放挡在这里"
      (is (not (cp/actor-signature-matches?
                (assoc-in signed [:control/payload :version] 8) secret))))
    (testing "换一把钥匙验不过"
      (is (not (cp/actor-signature-matches? signed "another-key"))))))

;; ---------------------------------------------------------------------------
;; service 请求认证
;; ---------------------------------------------------------------------------

(deftest the-service-input-is-a-fixed-structure-not-a-concatenation
  (testing "裸拼接有边界歧义：`(str a b)` 对 [\"x\" \"yz\"] 与 [\"xy\" \"z\"] 得到
            同一串字节，于是两份不同的输入共用一个签名"
    (let [a (cp/service-signing-input {:key-id "svc" :timestamp "2026-08-24T09:00:00Z"
                                       :body-text "xyz"})
          b (cp/service-signing-input {:key-id "svcx" :timestamp "yz2026-08-24T09:00:00Z"
                                       :body-text "xyz"})]
      (is (not= a b)))))

(deftest the-service-signature-binds-the-body
  (let [ctx  {:key-id "svc-1" :timestamp "2026-08-24T09:00:00Z" :body-text "{:a 1}"}
        sig  (cp/sign-request ctx secret)]
    (is (cp/request-signature-matches? ctx secret sig))
    (is (not (cp/request-signature-matches? (assoc ctx :body-text "{:a 2}") secret sig))
        "body 改了，签名就该验不过")))

;; ---------------------------------------------------------------------------
;; 杂项
;; ---------------------------------------------------------------------------

(deftest constant-time-compare-answers-correctly
  (testing "它比的是**规范签名串**，不是任意字符串：解成字节交给 JDK 的
            `MessageDigest/isEqual`，形状不对的直接 false、不进比较"
    (let [a (str cp/signature-prefix (apply str (repeat 64 "a")))
          b (str cp/signature-prefix (apply str (repeat 63 "a") "b"))]
      (is (cp/constant-time= a a))
      (is (not (cp/constant-time= a b)))
      (doseq [bad [nil "" "abc" (apply str (repeat 64 "a"))          ; 缺前缀
                   (str cp/signature-prefix "short")
                   (str cp/signature-prefix (apply str (repeat 64 "A")))]] ; 大写不是规范形
        (is (not (cp/constant-time= a bad)) (str "形状不对却比成了相等：" (pr-str bad)))
        (is (not (cp/constant-time= bad a)) (str "形状不对却比成了相等：" (pr-str bad)))))))

(deftest stage-files-must-be-exactly-the-three-artifact-files
  (testing "wire shape 在 common 就封闭：缺一份或多一份不该等到 server 层才拒绝。
            文件名与顺序由 artifact-v1 定死——digest 正是按那个顺序算的（D4.3）"
    (let [three {"content.edn" "a" "manifest.edn" "b" "report.edn" "c"}
          env*  #(envelope :control/op :stage
                           :control/payload
                           {:artifact-digest (str "sha256:" (apply str (repeat 64 "a")))
                            :files %})]
      (is (empty? (cp/validate-envelope (env* three))))
      (doseq [[what files] [["少一份"   (dissoc three "report.edn")]
                            ["多一份"   (assoc three "extra.edn" "x")]
                            ["名字错"   (-> three (dissoc three "content.edn")
                                            (assoc "contents.edn" "a"))]
                            ["空"       {}]]]
        (is (seq (cp/validate-envelope (env* files))) what)))))

(deftest every-emitted-code-is-registered
  (testing "产出未注册 code 是实现 bug（finding-v1 §6）"
    (doseq [env* [{} (envelope :control/version 2) (envelope :control/op :nope)
                  (envelope :control/extra 1) (dissoc (envelope) :control/audience)
                  (envelope :control/payload {})]]
      (doseq [f (cp/validate-envelope env*)]
        (is (contains? cp/finding-codes (:code f)) (pr-str f))
        (is (cp/finding? f) (pr-str f))))))
