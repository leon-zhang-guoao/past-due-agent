(ns open.atlas.runtime.env.env-test
  "R3.2 的守门测试：**真制品架、真闭包、真编译**。

   最要紧的三条：

   1. **合并了再编译**——被引用图的属性在合并后的 env 里解析得到（各编各的
      再拼起来就走不通跨图那条路）；
   2. **`:up-to` 逐成员压低、根不压**（D5.5）——一个属性经引用带进来是一档,
      作为根自己的属性是另一档；
   3. **指针变更不需要失效**——键是具体版本，版本不可变。"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.semantic-artifact :as semantic-artifact]
            [open.atlas.common.connector.sql :as sql]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]
            [open.atlas.runtime.env :as env]
            [open.atlas.runtime.store :as rs]))

(defn- tmp-root []
  (str (java.nio.file.Files/createTempDirectory
        "rt-env-" (into-array java.nio.file.attribute.FileAttribute []))))

(defn- rm-r [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-r (.listFiles f)))
    (.delete f)))

(defn- model
  "一张最小但合法的图：一个 id、一个普通属性、一个普通字段。"
  [ns*]
  (let [m {:attributes {(keyword (str ns*) "id")
                        {:type :integer :format :id :identity? true :label "ID"}
                        (keyword (str ns*) "name")
                        {:type :string :format :text :label "Name"}
                        (keyword (str ns*) "secret_field")
                        {:type :string :format :text :label "Secret"}}
           :namespaces {(str ns*) {:kind :master :label "NS"}}
           :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
           :resolvers  [{:id (str ns* "_by_id")
                         :input [(keyword (str ns*) "id")]
                         :output [(keyword (str ns*) "id") (keyword (str ns*) "name")
                                  (keyword (str ns*) "secret_field")]
                         :result_type "single"
                         :source {:type "sql" :query "SELECT 1"} :source-ref "db"}]}
        {:keys [model findings]} (dialect/read-dialect (pr-str m))]
    (assert (some? model) (pr-str findings))
    model))

(defn- seal!
  "把一张图密封进 `shelf` 并登记它的 digest。→ `{:digest …}`。"
  [shelf digests {:keys [workspace graph version model references]}]
  (let [{:keys [content-edn content-hash findings]} (artifact/encode-content model (vec references))
        _ (assert (empty? findings) (pr-str findings))
        {:keys [files artifact-digest findings]}
        (artifact/seal-graph content-edn content-hash
                             {:workspace workspace :graph graph :version version
                              :cut-at "2026-08-25T09:00:00Z" :cut-by "test"}
                             {} {} {:coverage 1.0})
        _ (assert (empty? findings) (pr-str findings))
        r (artifact/write-artifact! shelf {:workspace workspace :graph graph :version version}
                                    files)]
    (assert (empty? (:findings r)) (pr-str (:findings r)))
    (swap! digests assoc [(str workspace "/" graph) version] artifact-digest)
    {:digest artifact-digest :content-hash content-hash}))

(defn- caps [shelf digests serving reads]
  {:shelf-texts (fn [g v]
                  (swap! reads inc)
                  (let [[ws nm] (clojure.string/split g #"/" 2)]
                    (artifact/read-shelf-texts shelf {:workspace ws :graph nm :version v})))
   :receipt-digest (fn [g v] (get @digests [g v]))
   :serving-version (fn [g] (get @serving g))})

(defmacro with-env
  "`sym` 拿到 env store，其余绑定给测试用来摆布制品架与指针。
   `opts` 并进 `env/open!` 的入参（例如 `:resolve-source`）。"
  [[sym shelf-sym digests-sym serving-sym reads-sym opts] & body]
  `(let [root#    (tmp-root)
         ~shelf-sym (str (io/file root# "artifacts"))
         ~digests-sym (atom {})
         ~serving-sym (atom {})
         ~reads-sym   (atom 0)
         inst#    (:instance (rs/open! {:instance-id "i" :root root#}))
         opened#  (env/open! inst# (merge (caps ~shelf-sym ~digests-sym ~serving-sym ~reads-sym)
                                          ~(or opts {})))
         ~sym     (:store opened#)]
     (assert (some? ~sym) (pr-str (:findings opened#)))
     (try ~@body (finally (rs/close! inst#) (rm-r root#)))))

(defn- codes [x] (into (sorted-set) (map :code) (:findings x)))

;; ---------------------------------------------------------------------------
;; 启动门禁
;; ---------------------------------------------------------------------------

(deftest missing-capabilities-refuse-to-start
  (let [root (tmp-root)
        i    (:instance (rs/open! {:instance-id "i" :root root}))]
    (try
      (is (= [:env.capabilities/missing] (mapv :code (:findings (env/open! i {})))))
      (is (= [:env.capabilities/missing]
             (mapv :code (:findings (env/open! i {:shelf-texts (constantly {})})))))
      (finally (rs/close! i) (rm-r root)))))

;; ---------------------------------------------------------------------------
;; 合并了再编译
;; ---------------------------------------------------------------------------

(deftest a-single-graph-closure-compiles
  (with-env [s shelf digests serving reads]
    (seal! shelf digests {:workspace "a" :graph "root" :version 1 :model (model "a.x")})
    (let [r (env/materialize s "a/root" 1)]
      (is (empty? (:findings r)) (pr-str (:findings r)))
      (is (contains? (:attributes (:engine/model (:env r))) :a.x/name))
      (is (= 1 (count (:members r)))))))

(deftest a-referenced-graph-is-merged-into-one-env
  (testing "R3.2：组合是 **index 合并**。逐个引用制品各编一份再拼 compiled env,
            跨图那条解析路径就断了——而引用存在的理由正是那条路径"
    (with-env [s shelf digests serving reads]
      (let [dep (seal! shelf digests {:workspace "b" :graph "dep" :version 3
                                      :model (model "b.y")})]
        (seal! shelf digests
               {:workspace "a" :graph "root" :version 1 :model (model "a.x")
                :references [{:workspace "b" :graph "dep" :version 3
                              :content-hash (:content-hash dep)}]})
        (let [r     (env/materialize s "a/root" 1)
              model (:engine/model (:env r))]
          (is (empty? (:findings r)) (pr-str (:findings r)))
          (is (= 2 (count (:members r))))
          (is (contains? (:attributes model) :a.x/name) "根自己的属性在")
          (is (contains? (:attributes model) :b.y/name)
              "**被引用图的属性在同一份模型里**——编译只跑了一次")
          (is (= #{"a.x_by_id" "b.y_by_id"} (set (map :id (:resolvers model))))
              "两张图的 resolver 也在同一份 index 里；跨图那条解析路径因此走得通")
          (is (= 1 (count (:sources model)))
              "同名同值的 source 折叠成一个——闭包成员全部投影自同一个共享池（D1.2）"))))))

;; ---------------------------------------------------------------------------
;; 闭包不齐就不物化
;; ---------------------------------------------------------------------------

(deftest a-missing-member-refuses-the-whole-materialisation
  (with-env [s shelf digests serving reads]
    (let [dep (seal! shelf digests {:workspace "b" :graph "dep" :version 3
                                    :model (model "b.y")})]
      (seal! shelf digests
             {:workspace "a" :graph "root" :version 1 :model (model "a.x")
              :references [{:workspace "b" :graph "dep" :version 3
                            :content-hash (:content-hash dep)}]})
      (rm-r (io/file shelf "b"))
      (let [r (env/materialize s "a/root" 1)]
        (is (nil? (:env r)) "半份闭包不产出 env")
        (is (= #{:artifact.reference/missing} (codes r))
            "**回落到 reader 自己的 code，不重贴一层 `env.*` 的皮**——它比
             「不在架子上」更准：是根这一份在解析它的引用时发现对方不在")
        (is (every? env/finding? (:findings r)))))))

(deftest an-absent-root-is-named-by-this-layer
  (testing "根没有父节点替它发现问题，所以「不在架子上」这条由本层给出"
    (with-env [s shelf digests serving reads]
      (is (= #{:env.artifact/absent} (codes (env/materialize s "a/root" 1)))))))

(deftest bytes-without-a-receipt-do-not-count-as-loaded
  (testing "「已装载」= 目录 **且** receipt。有目录无 receipt 是上一次没跑完的
            残留，而那个期望 digest 当初来自哪份已认证信封，没有证据"
    (with-env [s shelf digests serving reads]
      (seal! shelf digests {:workspace "a" :graph "root" :version 1 :model (model "a.x")})
      (reset! digests {})
      (is (= #{:env.artifact/unreceipted} (codes (env/materialize s "a/root" 1)))))))

(deftest a-digest-that-does-not-match-the-receipt-refuses
  (testing "回落到 `artifact.*`，不重贴一层 `env.*` 的皮"
    (with-env [s shelf digests serving reads]
      (seal! shelf digests {:workspace "a" :graph "root" :version 1 :model (model "a.x")})
      (swap! digests assoc ["a/root" 1] (str "sha256:" (apply str (repeat 64 "a"))))
      (let [r (env/materialize s "a/root" 1)]
        (is (nil? (:env r)))
        (is (every? env/finding? (:findings r)))
        (is (some #(= "artifact" (namespace-munge (first (clojure.string/split
                                                          (str (namespace (:code %))) #"\."))))
                  (:findings r))
            (pr-str (codes r)))))))

;; ---------------------------------------------------------------------------
;; latest / 版本快照 / 缓存
;; ---------------------------------------------------------------------------

(deftest latest-resolves-against-the-serving-pointer
  (with-env [s shelf digests serving reads]
    (seal! shelf digests {:workspace "a" :graph "root" :version 1 :model (model "a.x")})
    (seal! shelf digests {:workspace "a" :graph "root" :version 2 :model (model "a.x")})
    (is (= #{:env.version/unresolved}
           (codes (env/for-request s {:graph "a/root" :version-spec :latest})))
        "没有指针时 latest 解析不出东西（R3.6）")
    (reset! serving {"a/root" 2})
    (is (= 2 (:version (env/for-request s {:graph "a/root" :version-spec :latest}))))
    (is (= 1 (:version (env/for-request s {:graph "a/root" :version-spec 1})))
        "钉版本不看指针")))

(deftest a-pointer-move-needs-no-invalidation
  (testing "缓存键是 `[限定名 具体版本]`，而版本不可变。部署/回滚改的是 latest
            **解析到哪一版**，不是任何一版的内容——指针一动，下一次请求自然算出
            另一个键。把 latest 当键才需要失效，而那种设计还会留一个「指针已动、
            缓存未清」的窗口，正是 R3.6 要防的"
    (with-env [s shelf digests serving reads]
      (seal! shelf digests {:workspace "a" :graph "root" :version 1 :model (model "a.x")})
      (seal! shelf digests {:workspace "a" :graph "root" :version 2 :model (model "a.x")})
      (reset! serving {"a/root" 1})
      (is (= 1 (:version (env/for-request s {:graph "a/root" :version-spec :latest}))))
      (reset! serving {"a/root" 2})
      (is (= 2 (:version (env/for-request s {:graph "a/root" :version-spec :latest})))
          "**没有调用过任何 invalidate**")
      (is (= #{["a/root" 1] ["a/root" 2]} (env/held s)) "两版各自缓存，互不冒充"))))

(deftest materialisation-is-lazy-and-happens-once-per-version
  (with-env [s shelf digests serving reads]
    (seal! shelf digests {:workspace "a" :graph "root" :version 1 :model (model "a.x")})
    (is (zero? @reads) "**懒**：open! 不物化任何东西（R3.5）")
    (env/materialize s "a/root" 1)
    (let [after-first @reads]
      (is (pos? after-first))
      (env/materialize s "a/root" 1)
      (env/materialize s "a/root" 1)
      (is (= after-first @reads) "命中缓存，不再读盘也不再编译"))
    (testing "失效之后会重算——它是给腾内存与回收用的，不是正确性手段"
      (env/invalidate! s)
      (is (empty? (env/held s)))
      (env/materialize s "a/root" 1)
      (is (pos? @reads)))))

(deftest an-entry-expires-after-the-configured-idle-ttl
  (testing "8.4.20 裁定十四 ①：键仍是 `[限定名 具体版本]`（版本不可变，过期不是
            正确性手段），但空闲够久就该释放。时钟注入，不睡两小时"
    (let [now (atom 1000)]
      (with-env [s shelf digests serving reads {:cache-ttl-ms 60000 :clock #(deref now)}]
        (seal! shelf digests {:workspace "a" :graph "root" :version 1 :model (model "a.x")})
        (env/materialize s "a/root" 1)
        (let [after-first @reads]
          (is (pos? after-first))
          (swap! now + 59000)
          (env/materialize s "a/root" 1)
          (is (= after-first @reads) "TTL 之内复用同一条，不重编译")
          (is (= #{["a/root" 1]} (env/held s)))
          ;; 上一次访问把 last-used 推到了 60000，所以再空闲一个 TTL 才过期。
          (swap! now + 60000)
          (is (empty? (env/held s)) "空闲满一个 TTL：条目已释放")
          (env/materialize s "a/root" 1)
          (is (> @reads after-first) "下一次访问重建"))))))

(deftest a-refusal-is-not-cached
  (testing "闭包缺件是可修复的状态：把它缓存住，补齐制品之后仍然拒绝"
    (with-env [s shelf digests serving reads]
      (is (seq (:findings (env/materialize s "a/root" 1))))
      (is (empty? (env/held s)))
      (seal! shelf digests {:workspace "a" :graph "root" :version 1 :model (model "a.x")})
      (is (:env (env/materialize s "a/root" 1))))))

;; ---------------------------------------------------------------------------
;; 凭据：模型说名字，instance 说值
;; ---------------------------------------------------------------------------

(deftest a-source-is-materialised-with-this-instances-credentials
  "**凭据是 instance 本地状态**（D1.13 明文列了 source credentials），所以它不
   进模型：模型里只留 `${VAR}` 名字，值住在这台机器自己的家里。于是「声明的
   source」到「开得出的连接」之间必然有一步替换，而那一步必须在**这条**编译路径
   上——两种形态共用的这一条。

   这条测试是补的：Builder 自己的查询路径一直在做这步替换，runtime 这条没有,
   于是同一张图、同一个源，界面里查得到而 `/eql`、`/mcp` 报 `source execution
   failed`。它逃过了很久，因为当时唯一走 runtime 的读路径是 Studio 的 preview,
   而 preview 退役（D1.14）之后 runtime 的读就再没人走过——直到 agent 从 MCP
   接了出来。**一条没有测试穿过的路径，不会因为没人走就不腐坏。**"
  (let [seen (atom [])]
    (with-env [s shelf digests serving reads
               {:resolve-source (fn [id entry]
                                  (swap! seen conj id)
                                  (assoc entry :jdbc_url "jdbc:sqlite:/real/path.db"))}]
      (seal! shelf digests {:workspace "a" :graph "root" :version 1 :model (model "a.x")})
      (let [r (env/materialize s "a/root" 1)]
        (is (empty? (:findings r)) (pr-str (:findings r)))
        (is (= ["db"] @seen) "每个 source 过一次解析")
        (is (= "jdbc:sqlite:/real/path.db"
               (get-in (:engine/model (:env r)) [:sources "db" :jdbc_url]))
            "编译看见的是解析后的连接，不是模型里的声明")))))

(deftest without-a-resolver-the-model-is-used-as-declared
  "缺席 = 不替换。那是诚实的缺省：没有凭据 store 的宿主本来就该按模型里写的去连,
   而不是拿到一个悄悄改过的连接串。"
  (with-env [s shelf digests serving reads]
    (seal! shelf digests {:workspace "a" :graph "root" :version 1 :model (model "a.x")})
    (let [r (env/materialize s "a/root" 1)]
      (is (= "jdbc:sqlite:memory"
             (get-in (:engine/model (:env r)) [:sources "db" :jdbc_url]))))))

(deftest semantic-artifact-materializes-on-the-existing-shelf
  (let [resolved (atom [])
        document (-> (edn/read-string (slurp (io/resource "semantic-v2.edn")))
                     (assoc-in [:publication :release/version] "8")
                     (assoc-in [:execution :bindings "lookup-sql" :binding/implementation :query]
                               "SELECT 'verified' AS title WHERE :id = 1"))
        coords {:workspace "support-dev" :graph "support" :version 8}
        encoded (semantic-artifact/encode-candidate document {} {"sql" #(empty? (:findings (sql/validate-binding %)))} coords)]
    (with-env [s shelf digests serving reads
               {:resolve-source (fn [id entry]
                                  (swap! resolved conj [id entry])
                                  {:type "sql" :label "Isolated fixture" :jdbc_url "jdbc:sqlite::memory:"})}]
      (is (empty? (:findings encoded)))
      (is (empty? (:findings (artifact/write-artifact! shelf coords (:files encoded)))))
      (swap! digests assoc ["support-dev/support" 8] (:artifact-digest encoded))
      (reset! serving {"support-dev/support" 8})
      (let [loaded (env/for-request s {:graph "support-dev/support" :version-spec :latest})]
        (is (empty? (:findings loaded)) (pr-str (:findings loaded)))
        (is (some? (:env loaded)))
        (when (:env loaded)
          (is (= {:support.case/title "verified"}
                 (:engine/data (engine/query (:env loaded) nil {:entity {:support.case/id 1} :query [:support.case/title]})))))
        (is (= "support-dev" (get-in @resolved [0 1 :semantic/workspace])))
        (is (= "support" (get-in @resolved [0 1 :semantic/graph])))))))
