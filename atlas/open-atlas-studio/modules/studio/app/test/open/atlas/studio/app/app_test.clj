(ns open.atlas.studio.app.app-test
  "studio = Builder + 内嵌 runtime，**真的跑一遍**（D1.13）。

   这一条要钉住的是裁决 §15 的第 1、2 两条：

   1. **Builder 与内嵌 runtime 状态隔离**——Builder 不直接改 runtime 的库,
      它只能构造并签名一份控制信封，走与独立 Server 完全相同的命令边界；
   2. **两种形态同一份实现**——这里用的 load/activate、发牌、latest、物化,
      与 `server/http` 那一侧调的是同一批函数。

   全链路：建模入池 → 圈图 → 切版本 → **发布到内嵌 runtime** → 本机发牌 →
   持 token 查询。"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            ;; E1 / D5.14：require 即注册。切版本的接线核对问连接器「这条语句绑了
            ;; 哪些名字」——生产里由 apps/studio require（宿主决定加载谁），这条
            ;; 端到端要一个真的在场，否则带声明的图只会得到 unverifiable。
            [open.atlas.common.connector.sql :as sql]
            [open.atlas.runtime.env :as renv]
            [open.atlas.runtime.source-bindings :as source-bindings]
            [open.atlas.runtime.instance :as instance]
            [open.atlas.runtime.obs :as obs]
            [open.atlas.runtime.store :as store]
            [open.atlas.runtime.graph :as rgraph]
            [open.atlas.runtime.retention :as retention]
            [open.atlas.runtime.token :as rtoken]
            [open.atlas.studio.app :as app]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.version :as version]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

(def ^:private alice {:issuer "corp-idp" :subject "00u-alice"})
(def ^:private actor-key-id "studio-actor")
(def ^:private actor-secret "actor-secret-not-the-data-plane-one")
(def ^:private now "2026-08-25T09:00:00Z")

(defn- tmp [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix (into-array java.nio.file.attribute.FileAttribute []))))

(defn- rm-r [f]
  (let [f (io/file f)] (when (.isDirectory f) (run! rm-r (.listFiles f))) (.delete f)))

(def ^:private tenant-model
  "一个声明了 `:row-security` 的 resolver。**接线是对的**（E1 / D5.14 起
   `:attribute` 必须是 `:input` 且被语句绑上，否则切版本就过不去）——门禁要拦
   的正是这样一张切得出版本的图。"
  {:attributes {:t.o/id        {:type :integer :format :id :identity? true :label "O"}
                :t.o/tenant_id {:type :string :format :text :label "Tenant"}
                :t.o/amount    {:type :decimal :format :currency :label "Amount"}}
   :namespaces {"t.o" {:kind :master :label "O"}}
   :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
   :resolvers  [{:id "o_by_id" :input [:t.o/id :t.o/tenant_id]
                 :output [:t.o/id :t.o/tenant_id :t.o/amount]
                 :result_type "single"
                 :source {:type "sql"
                          :query "SELECT 1 WHERE id = :id AND tenant_id = :tenant_id"}
                 :source-ref "db"
                 :row-security {:attribute :t.o/tenant_id}}]})

(def ^:private model
  {:attributes {:s.a/id     {:type :integer :format :id :identity? true :label "A"}
                :s.a/name   {:type :string :format :text :label "Name"}
                :s.a/salary {:type :decimal :format :currency :label "Salary"}}
   :namespaces {"s.a" {:kind :master :label "A"}}
   :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
   :resolvers  [{:id "a_by_id" :input [:s.a/id]
                 :output [:s.a/id :s.a/name :s.a/salary]
                 :result_type "single" :source {:type "sql" :query "SELECT 1"}
                 :source-ref "db"}]})

(defn- check! [r] (assert (empty? (:findings r)) (pr-str (:findings r))) r)

(defmacro with-studio [[sym roots-sym options] & body]
  `(let [~roots-sym {:runtime (tmp "studio-rt-") :artifacts (tmp "studio-art-")}
         started# (app/start! (merge {:pool* {:backend :mem :id (str (gensym "studio"))}
                               :instance-id "studio-embedded"
                               :runtime-root (:runtime ~roots-sym)
                               :artifact-root (:artifacts ~roots-sym)
                               :token-pepper "studio-pepper"
                               :actor-key (fn [k#] (when (= actor-key-id k#) actor-secret))
                               :now (constantly ~now)} ~options))
         ~sym started#]
     (assert (empty? (:findings started#)) (pr-str (:findings started#)))
     (try ~@body
          (finally (app/stop! started#)
                   (rm-r (:runtime ~roots-sym)) (rm-r (:artifacts ~roots-sym))))))

(defn- model! [{:keys [conn]}]
  (check! (pool/import-pool! conn {:pool/version 1
                                   :pool/objects (mapv #(assoc % :pool/revision 1 :pool/workspace "demo")
                                                       (pool/dialect->objects model))}))
  (check! (graph/register-workspace! conn {:name "sales"
                                           :members [{:principal alice :role :owner}]}))
  ;; 菜谱是对象清单（D3.19）：把 `s.*` 下的一切展开进去。
  (let [m (pool/as-dialect (pool/db conn))
        ns-names (into #{} (filter #(clojure.string/starts-with? % "s.")) (keys (:namespaces m)))]
    (check! (graph/register-graph!
             conn {:workspace "sales" :name "app"
                   :members {:namespaces ns-names
                             :attributes (into #{} (filter #(contains? ns-names (namespace %)))
                                               (keys (:attributes m)))
                             :units (set (keys (:units m)))
                             :terms (set (keys (:terms m)))
                             :metrics (set (keys (:metrics m)))
                             :resolvers (set (map :id (:resolvers m)))
                             :mutations (set (map :id (:mutations m)))
                             :links (set (map (juxt :from :to) (:links m)))}}))))

(defn- cut! [{:keys [conn artifact-root]}]
  (check! (version/cut-version! conn {:graph "sales/app"
                                      :artifact-root artifact-root
                                      :cut-at now :cut-by "alice"})))

(defn- ids [p] (fn [step] (str p "-" (pr-str step))))

(defn- publish! [app* v & {:as over}]
  (app/publish! app* (merge {:graph "sales/app" :version v :actor alice
                             :actor-key-id actor-key-id :actor-secret actor-secret
                             :deployment-id (str "dep-v" v)
                             :request-id-fn (ids (str "r" v))
                             :now now :expires-at "2026-08-25T09:05:00Z"
                             :expected-current nil}
                            over)))

;; ---------------------------------------------------------------------------
;; 全链路
;; ---------------------------------------------------------------------------

(deftest a-second-publish-must-carry-the-pointer-it-actually-found
  "**发布不是只发一次。**

   `:expected-current` 是 activate 的 CAS 护栏：「只有指针还在我上次看见的地方
   才换」。调用方把它写死成 `nil` 时，等于每次都声称「这张图什么都没在服务」
   ——那句话**恰好只有第一次是真的**。第二次起 CAS 必然拒绝
   （`:deploy.activate/stale-expectation`），而护栏是对的，撒谎的是调用方。

   这个 bug 只在**第二次**现形，所以只发布一次的测试永远看不见它——本测试
   存在的全部理由就是发布两次。护栏本身的职责（真有别人在中间移了指针就该拒）
   由下面第二段钉住：谎报一个陈旧的值，仍然必须被拒。"
  (with-studio [s roots]
    (model! s)
    (let [rt (:runtime s)
          v1 (:version (cut! s))
          v2 (:version (cut! s))
          v3 (:version (cut! s))]
      (is (= [1 2 3] [v1 v2 v3]) "版本号单调递增，三份制品各自在架上")

      (is (empty? (:findings (publish! s v1))) "第一次：指针是空的，nil 属实")
      (is (= v1 (rgraph/serving-version (:graph rt) "sales/app")))

      (testing "第二次：把**读到的**指针带上去，换版本成功"
        (let [cur (rgraph/serving-version (:graph rt) "sales/app")]
          (is (= v1 cur) "换之前，指针还在 v1")
          (let [out (publish! s v2 :expected-current cur)]
            (is (empty? (:findings out)) (pr-str (:findings out)))
            (is (= v2 (rgraph/serving-version (:graph rt) "sales/app"))))))

      (testing "护栏没被削弱：谎报一个陈旧的指针仍然被拒"
        (let [out (publish! s v3 :expected-current v1)]
          (is (some #(= :deploy.activate/stale-expectation (:code %)) (:findings out))
              (pr-str (mapv :code (:findings out))))
          (is (= v2 (rgraph/serving-version (:graph rt) "sales/app"))
              "被拒之后指针纹丝不动"))))))

(deftest a-cut-version-is-published-into-the-embedded-runtime-and-then-served
  (with-studio [s roots]
    (model! s)
    (let [v   (:version (cut! s))
          rt  (:runtime s)
          out (publish! s v)]
      (is (empty? (:findings out)) (pr-str (:findings out)))
      (is (= [:owner :plan :offer :stage :load :activate] (mapv :step (:steps out)))
          "**与推给远端 Server 逐条相同的六步**")

      (testing "指针落在内嵌 runtime 上"
        (is (= v (rgraph/serving-version (:graph rt) "sales/app")))
        (is (rgraph/loaded? (:graph rt) "sales/app" v)))

      (testing "两个制品架各有一份字节——Builder 切在自己的架子上，
                发布是把它**送进** runtime 的架子（R3.5 因此各算各的保留条件）"
        (is (.isDirectory (io/file (:artifacts roots) "sales" "app" (str v))))
        (is (.isDirectory (io/file (:runtime roots) "artifacts" "sales" "app" (str v)))))

      (testing "本机发牌 → 验证 → 物化 → 查询，全在内嵌的那台 runtime 上"
        (let [{:keys [secret]}
              (check! (rtoken/issue! (:token rt)
                                     {:graph-id "sales/app" :version :latest
                                      :actor alice :request-id "tok-1" :now now}))
              verified (rtoken/verify (:token rt) secret {:graph-id "sales/app" :now now})]
          (is (empty? (:findings verified)) (pr-str (:findings verified)))
          (let [m (renv/for-request (:env rt) {:graph-id "sales/app" :version-spec
                                               (:version (:authorization verified))})]
            (is (= v (:version m)) "latest 解析到本机指针指着的那一版")
            ;; 档位退役（owner 裁定 2026-08-27）：token 只说「这张图」，
            ;; env 里也不再有一张按属性分档的表。要更窄就切更窄的图。
            (is (contains? (:attributes (:engine/model (:env m))) :s.a/name))
            (is (contains? (:attributes (:engine/model (:env m))) :s.a/salary)
                "图里声明过的属性一律在——没有第二种可见性"))

          ;; 8.10 P6：数据面的读也进审计，而且带得出「步骤」。这条端到端是
          ;; 唯一能证明它的地方——单模块测试里没有装好的图与真发出去的牌。
          (testing "一次真实的数据面读取，在审计里留下了什么"
            (let [r (instance/answer rt {:credential secret
                                         ;; 请求的形状是 `{:entity :query}`。
                                         ;; 之前这里写的是一个裸向量,
                                         ;; engine 直接拒绝——而拒绝当时被记成了
                                         ;; `:ok`，那才是这条测试真正抓到的东西。
                                         :request {:entity {:s.a/id 1}
                                                   :query [:s.a/name]}
                                         :now now
                                         :entry :data-eql})
                  filed (obs/audit-for-subject
                         (store/subsystem-dir (:instance rt) "obs") :graph "sales/app")
                  e (last (filter #(= :access/query (:event %)) filed))]
              (is (nil? (:engine/stats r))
                  "未裁剪的结果一步都不许离开 answer——审计开关不是数据出口")
              (is (some? e) "这次读被记下了")
              ;; **结果与审计必须说同一件事。** 这一条抓到过一个真 bug：
              ;; engine 的拒绝是一张扁平 map（没有 `:findings`），而审计
              ;; 只看 `:findings`，于是被拒的读被记成 `:outcome :ok`——
              ;; 一条说「他读到了」的审计，而他没有。
              (is (= (if (seq (instance/refusals r)) :denied :ok) (:outcome e))
                  "被拒的读绝不能被记成成功")
              (is (= :data-eql (:entry e)) "门是传输层说的")
              (is (number? (:latency-ms e)) "「慢不慢」答得出")
              (is (= ["s.a"] (:entities e)) "只有实体命名空间的名字")
              (when-let [st (:statements e)]
                (is (pos? (:count st)) "发过的语句进了摘要")
                ;; doc 107 §107.8（owner 裁定 2026-09-18）加了 `:attempt`：
                ;; 没有它，重试两回才答出来的查询与一次干净的调用在审计里长得
                ;; 一模一样。它仍是计数，不是内容——白名单的纪律没有松。
                (is (every? #(= #{:resolver-id :source-type :elapsed-ms :rows :attempt}
                                (set (keys %)))
                            (:by-resolver st))
                    "白名单：resolver / 源类型 / 耗时 / 行数 / 第几次，一个字段都不多"))
              (is (not (str/includes? (pr-str e) "SELECT"))
                  "语句原文永不进审计")
              (when-not (seq (instance/refusals r))
                (is (some? (:resolver-trace e))
                    "读成功了就有时间线，而且它在盘上——重启后详情面板照样打得开")))))))))

;; ---------------------------------------------------------------------------
;; §15.1：Builder 不直接改 runtime 的库
;; ---------------------------------------------------------------------------

(deftest the-builder-cannot-reach-past-the-command-boundary
  (testing "同进程**不等于**有特权（D5.7：dev 也不例外，没有隐式全权路径）。
            换掉的只有传输，命令边界一样都不少"
    (with-studio [s roots]
      (model! s)
      (let [v (:version (cut! s))]
        (testing "① actor 断言仍然要验签——密钥不对就发布不了"
          (let [out (publish! s v :actor-secret "wrong-secret"
                              :deployment-id "dep-bad" :request-id-fn (ids "bad"))]
            (is (seq (:findings out)))
            (is (nil? (rgraph/serving-version (:graph (:runtime s)) "sales/app")))))

        (testing "② 信封指向别的 instance 也发布不了——audience 就是为了防这个"
          (let [out (publish! s v :audience "some-other-instance"
                              :deployment-id "dep-aud" :request-id-fn (ids "aud"))]
            (is (seq (:findings out)))
            (is (nil? (rgraph/serving-version (:graph (:runtime s)) "sales/app")))))

        (testing "③ 非 owner 一个字节都送不出去——授权链的第一环仍在 Builder 这边"
          (let [out (publish! s v :actor {:issuer "corp-idp" :subject "00u-mallory"}
                              :deployment-id "dep-m" :request-id-fn (ids "m"))]
            (is (= [:deploy.authz/not-owner] (mapv :code (:findings out))))
            (is (empty? (:steps out)))))

        (testing "④ 正路走得通——说明上面三条挡住的是它们各自那一件事,
                  而不是「同进程根本没接上」"
          (is (empty? (:findings (publish! s v)))))))))

;; ---------------------------------------------------------------------------
;; 回滚与保留
;; ---------------------------------------------------------------------------

(deftest a-rollback-moves-the-embedded-pointer-and-latest-follows
  (with-studio [s roots]
    (model! s)
    (let [v1 (:version (cut! s))
          v2 (:version (cut! s))
          rt (:runtime s)]
      (check! (publish! s v1))
      (check! (publish! s v2 :expected-current v1))
      (is (= v2 (rgraph/serving-version (:graph rt) "sales/app")))
      (let [r (app/rollback! s {:graph "sales/app" :to-version v1 :actor alice
                                :actor-key-id actor-key-id :actor-secret actor-secret
                                :deployment-id (str "dep-v" v1)
                                :request-id-fn (ids "rb")
                                :now now :expires-at "2026-08-25T09:05:00Z"
                                :expected-current v2})]
        (is (empty? (:findings r)) (pr-str (:findings r)))
        (is (= {:from v2 :to v1} (:result r)))
        (is (= v1 (rgraph/serving-version (:graph rt) "sales/app")))))))

(deftest retention-on-the-embedded-runtime-sees-its-own-holders
  (with-studio [s roots]
    (model! s)
    (let [v1 (:version (cut! s))
          v2 (:version (cut! s))
          rt (:runtime s)]
      (check! (publish! s v1))
      (check! (publish! s v2 :expected-current v1))
      (is (= #{:served} (retention/explain (:retention rt) now
                                           {:graph "sales/app" :version v2})))
      (is (empty? (retention/explain (:retention rt) now
                                     {:graph "sales/app" :version v1}))
          "v1 装载过但没人指着它了——可回收")
      (testing "钉住 v1 的一张牌立刻把它变成不可回收（推导，不累加）"
        (check! (rtoken/issue! (:token rt)
                               {:graph-id "sales/app" :version v1 :read-level :public
                                :actor alice :request-id "pin" :now now}))
        (is (= #{:pinned} (retention/explain (:retention rt) now
                                             {:graph "sales/app" :version v1})))))))


;; ---------------------------------------------------------------------------
;; S5.7：行级安全门禁盖住激活与发牌两处
;; ---------------------------------------------------------------------------

(defn- import-graph!
  "把一份 dialect 导入池、开个 workspace、认领命名空间、登记一张图。
   `prefix` 是命名空间前缀（也是 selector 的前缀），`gname` 是图名。"
  [{:keys [conn]} dialect prefix gname]
  (check! (pool/import-pool! conn {:pool/version 1
                                   :pool/objects (mapv #(assoc % :pool/revision 1 :pool/workspace "demo")
                                                       (pool/dialect->objects dialect))}))
  (check! (graph/register-workspace! conn {:name "ops"
                                           :members [{:principal alice :role :owner}]}))
  (let [m (pool/as-dialect (pool/db conn))
        ns-names (into #{} (filter #(clojure.string/starts-with? % (str prefix ".")))
                       (keys (:namespaces m)))
        ids      (into #{} (map :id) (filter #(contains? ns-names
                                                         (namespace (first (:input %))))
                                             (:resolvers m)))]
    (check! (graph/register-graph!
             conn {:workspace "ops" :name gname
                   :members {:namespaces ns-names
                             :attributes (into #{} (filter #(contains? ns-names (namespace %)))
                                               (keys (:attributes m)))
                             :units (set (keys (:units m)))
                             :terms (set (keys (:terms m)))
                             :metrics (set (keys (:metrics m)))
                             :resolvers ids
                             :mutations (set (map :id (:mutations m)))
                             :links (set (map (juxt :from :to) (:links m)))}}))))

(defn- tenant-model! [s] (import-graph! s tenant-model "t" "orders"))

(deftest a-verified-row-security-declaration-activates-and-issues
  (testing "**D5.14 的翻向，端到端**（owner 2026-09-03）：判据从「有声明即拦」
            改成「声明且接线核对通过即放行」。这张图的 `:tenant_id` 既是
            `:input`、语句也按它绑——E1 之前它是拦死的，而它的 resolver 一直
            是执法的（输入缺失时规划器判 unreachable，输入在时绑进 WHERE）"
    (with-studio [s roots]
      (tenant-model! s)
      (let [v  (:version (check! (version/cut-version!
                                  (:conn s) {:graph "ops/orders"
                                             :artifact-root (:artifact-root s)
                                             :cut-at now :cut-by "alice"})))
            rt (:runtime s)
            pub #(app/publish! s (merge {:graph "ops/orders" :version v :actor alice
                                         :actor-key-id actor-key-id :actor-secret actor-secret
                                         :deployment-id "dep-ops" :request-id-fn (ids "ops")
                                         :now now :expires-at "2026-08-25T09:05:00Z"
                                         :expected-current nil}
                                        %))]
        (testing "① 激活放行（读 gate 的第一处：runtime/graph 的 activate）"
          (is (empty? (:findings (pub {}))))
          (is (= v (rgraph/serving-version (:graph rt) "ops/orders"))))

        (testing "制品带着核对结论：声明一条，核对通过一条——门禁读的正是这两项"
          (let [facts (first (rgraph/closure-facts (:graph rt) "ops/orders" v))]
            (is (= ["resolver:o_by_id"] (:row-security-declared facts)))
            (is (= ["resolver:o_by_id"] (:row-security-verified facts)))))

        (testing "② 钉版本 token 发得出（第二处：runtime/token 的 issue!）"
          (is (:secret (check! (rtoken/issue!
                                (:token rt)
                                {:graph-id "ops/orders" :version v :read-level :public
                                 :actor alice :request-id "t-ok" :now now})))))

        (testing "③ latest token 同样发得出——D5.6 点名的第三条入口"
          (is (:secret (check! (rtoken/issue!
                                (:token rt)
                                {:graph-id "ops/orders" :version :latest :read-level :public
                                 :actor alice :request-id "t-latest" :now now})))))

        (testing "④ 三处读的是**同一个** gate（第三处：instance.clj 的装配）。
                  激活那条走 `:reads`、发牌那条走 token store 自己的字段,
                  两处若不是同一个函数，就会出现「体检说不行、发牌说行」——
                  D5.14 的红线正是这一条"
          (is (some? (:row-security-gate (:reads rt))))
          (is (identical? (:row-security-gate (:reads rt))
                          (:row-security-gate (:token rt)))))))))

(def ^:private miswired-model
  "同样的形状，但 `:tenant_id` **不在 `:input` 里**——声明「按租户切分」而语句
   全表。E1 之前这张图切得出版本，只是发不了牌；现在它在切版本就被拦下。"
  {:attributes {:u.o/id        {:type :integer :format :id :identity? true :label "O"}
                :u.o/tenant_id {:type :string :format :text :label "Tenant"}
                :u.o/amount    {:type :decimal :format :currency :label "Amount"}}
   :namespaces {"u.o" {:kind :master :label "O"}}
   :sources    {"db2" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
   :resolvers  [{:id "u_by_id" :input [:u.o/id]
                 :output [:u.o/id :u.o/tenant_id :u.o/amount]
                 :result_type "single"
                 :source {:type "sql" :query "SELECT 1 WHERE id = :id"}
                 :source-ref "db2"
                 :row-security {:attribute :u.o/tenant_id}}]})

(deftest a-miswired-declaration-never-reaches-the-gate-because-it-cannot-be-cut
  (testing "**判定点前移**（D5.14）：从「发牌时拦一整张图」移到「切版本时拦一个
            接错线的 resolver」。前者拦得晚且粗，后者让问题在作者手上就暴露"
    (with-studio [s roots]
      (import-graph! s miswired-model "u" "bad")
      (let [r (version/cut-version! (:conn s) {:graph "ops/bad"
                                               :artifact-root (:artifact-root s)
                                               :cut-at now :cut-by "alice"})]
        (is (contains? (into #{} (map :code) (:findings r))
                       :rowsec.wiring/not-an-input))
        (is (nil? (:version r)) "切不出版本，所以门禁根本没有机会看到它")))))

(defn delivery-document [document]
  (-> document
      (assoc-in [:contract :contract/version] "2.0.0")
      (assoc-in [:graph :graph/terms] [])
      (assoc-in [:ownership :terms] {})
      (update-in [:publication :required-capabilities] into
                 #{:graph/single-owner-v1 :deployment/source-slot-v1 :artifact/embedded-closure-v1})
      (assoc-in [:graph :graph/usage-guide] {:guide/purpose "Fixture behavior" :guide/asset-scope "Declared fixture assets"
 :guide/applicability "Test environment only" :guide/limitations ["Not production data"]
 :guide/composition "Pinned dependencies; review before reuse" :guide/examples []})))

(deftest saved-semantic-draft-cuts-publishes-and-answers-through-existing-runtime
  (with-studio [s roots {:resolve-source (fn [_ _] {:type "sql" :label "Fixture" :jdbc_url "jdbc:sqlite::memory:"})}]
    (model! s)
    (let [gid (:graph/id (graph/graph (pool/db (:conn s)) "sales/app"))
          document (-> (edn/read-string (slurp (io/resource "semantic-v2.edn")))
                       (assoc-in [:workspace :workspace/id] "sales")
                       (assoc-in [:graph :graph/id] gid)
                       (assoc :ownership {:entities {"support.case" gid} :resolvers {"lookup" gid} :operations {}})
                       (assoc-in [:execution :bindings "lookup-sql" :binding/implementation :query]
                                 "SELECT 'semantic release' AS title WHERE :id = 1")
                       delivery-document)
          checkers {"sql" #(empty? (:findings (sql/validate-binding %)))}]
      (check! (pool/save-semantic-draft! (:conn s) "sales" gid 0 document checkers nil))
      (is (= :semantic.draft/revision-conflict
             (-> (version/cut-semantic-version! (:conn s) {:graph "sales/app" :expected-revision 2
                                                          :artifact-root (:artifact-root s) :cut-at now :cut-by "alice"})
                 :findings first :code)))
      (let [cut (check! (version/cut-semantic-version! (:conn s) {:graph "sales/app" :expected-revision 1
                                                               :artifact-root (:artifact-root s) :cut-at now :cut-by "alice"}))
            rt (:runtime s)]
        (is (nil? (rgraph/serving-version (:graph rt) "sales/app")) "Cut never activates")
        (is (= 1 (:revision (pool/semantic-draft (pool/db (:conn s)) "sales" gid))) "Cut does not rewrite draft")
        (let [out (publish! s (:version cut))]
          (is (empty? (:findings out)) (pr-str out))
          (is (= [:owner :plan :offer :stage :load :activate] (mapv :step (:steps out)))))
        (let [{:keys [secret]} (check! (rtoken/issue! (:token rt) {:graph-id "sales/app" :version :latest
                                                                :actor alice :request-id "semantic-token" :now now}))
              response (instance/answer rt {:credential secret :request {:entity {:support.case/id 1}
                                                                          :query [:support.case/title]}
                                           :now now :entry :data-eql})]
          (is (= :ok (:engine/status response)) (pr-str response))
          (is (= {:support.case/title "semantic release"} (:engine/data response))))))))


(deftest semantic-file-bindings-publish-and-rollback-through-token-query
  (let [binding-path (atom nil)]
    (with-studio [s roots {:resolve-source (fn [id source]
                                           ((source-bindings/file-resolver @binding-path (fn [_ entry] entry)) id source))}]
      (model! s)
      (let [gid (:graph/id (graph/graph (pool/db (:conn s)) "sales/app"))
            document (-> (edn/read-string (slurp (io/resource "semantic-v2.edn")))
                         (assoc-in [:workspace :workspace/id] "sales")
                         (assoc-in [:graph :graph/id] gid)
                         (assoc :ownership {:entities {"support.case" gid} :resolvers {"lookup" gid} :operations {}})
                         delivery-document)
            source-id (get-in document [:execution :bindings "lookup-sql" :binding/source])
            _ (reset! binding-path (str (io/file (:runtime roots) "source-bindings.edn")))
            _ (source-bindings/install-missing! @binding-path {["sales" gid source-id] {:type "sql" :jdbc_url "jdbc:sqlite::memory:"}})
            cut! (fn [revision title]
                   (check! (pool/save-semantic-draft! (:conn s) "sales" gid revision
                             (assoc-in document [:execution :bindings "lookup-sql" :binding/implementation :query]
                                       (str "SELECT '" title "' AS title WHERE :id = 1"))
                             {"sql" #(empty? (:findings (sql/validate-binding %)))} nil))
                   (:version (check! (version/cut-semantic-version! (:conn s)
                                       {:graph "sales/app" :expected-revision (inc revision) :artifact-root (:artifact-root s)
                                        :cut-at now :cut-by "alice"}))))
            v1 (cut! 0 "first")
            _ (check! (publish! s v1))
            rt (:runtime s)
            token (:secret (check! (rtoken/issue! (:token rt) {:graph-id "sales/app" :version :latest :actor alice
                                                            :request-id "binding-rollback-token" :now now})))
            answer (fn [] (instance/answer rt {:credential token :request {:entity {:support.case/id 1} :query [:support.case/title]}
                                              :now now :entry :data-eql}))
            _ (is (= "first" (get-in (answer) [:engine/data :support.case/title])))
            v2 (cut! 1 "second")]
        (check! (publish! s v2 :expected-current v1))
        (is (= "second" (get-in (answer) [:engine/data :support.case/title])))
        (check! (app/rollback! s {:graph "sales/app" :to-version v1 :actor alice
                                :actor-key-id actor-key-id :actor-secret actor-secret :deployment-id (str "dep-v" v1)
                                :request-id-fn (ids "semantic-binding-rb") :now now :expires-at "2026-08-25T09:05:00Z"
                                :expected-current v2}))
        (is (= "first" (get-in (answer) [:engine/data :support.case/title])))
        (is (= 2 (:revision (pool/semantic-draft (pool/db (:conn s)) "sales" gid))))))))

(deftest new-semantic-cut-allows-missing-guide
  (with-studio [s roots]
    (model! s)
    (let [gid (:graph/id (graph/graph (pool/db (:conn s)) "sales/app"))
          document (-> (edn/read-string (slurp (io/resource "semantic-v2.edn")))
                       (assoc-in [:workspace :workspace/id] "sales")
                       (assoc-in [:graph :graph/id] gid)
                       (assoc :ownership {:entities {"support.case" gid} :resolvers {"lookup" gid} :operations {}})
                       delivery-document
                       (update :graph dissoc :graph/usage-guide))
          checkers {"sql" #(empty? (:findings (sql/validate-binding %)))}
          request {:graph "sales/app" :expected-revision 1
                   :artifact-root (:artifact-root s) :cut-at now :cut-by "alice"}]
      (check! (pool/save-semantic-draft! (:conn s) "sales" gid 0 document checkers nil))
      (let [before (set (map str (file-seq (io/file (:artifact-root s)))))
            result (version/cut-semantic-version! (:conn s) request)]
        (is (empty? (:findings result)))
        (is (some? (:version result)))
        (is (not= before (set (map str (file-seq (io/file (:artifact-root s)))))))))))
