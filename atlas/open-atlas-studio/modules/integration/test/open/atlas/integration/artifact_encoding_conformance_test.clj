(ns open.atlas.integration.artifact-encoding-conformance-test
  "**编码 conformance**（D4.10 第一层）在 env 层的那一半：

   > 同一规范化模型**直接编译**的 env ⟷ 经 `encode_content → content.edn →
   > artifact reader` 后编译的 env

   守住的是「序列化与反序列化不改变模型」。它在 B3 就能跑（不必等 B4 的池），
   但它证明不了池投影本身没走样——那是 B5 的**真实边界 conformance**
   （池投影编译的 env ⟷ 正式切版本制品装载的 env），必须等池与切版本都在。
   **两层都是测试套件里的一等公民**，不是「上线前记得手工比一次」。

   ## 为什么住在 integration 而不是 artifact 模块里

   `modules/common/artifact` **不依赖 engine**：制品是模型内容契约，与执行
   无关（D4.6——artifact reader 甚至不解析模型内容）。在 artifact 的 deps.edn
   里写下 engine（哪怕只在 `:test`），就等于把那条边界降级成「运行时不依赖、
   构建期依赖」，而依赖方向要的正是构建期可验证（D1.8）。装配是 app 的职责，
   本模块是那个职责的最小替身——与 vertical-slice-test 同一条理由。

   ## 「等价」怎么比：比可观察行为，不比对象 identity

   D4.10 把等价定义为对同一批 EQL 给出：**逐字相同的结果、相同的 index 形状、
   相同的档位过滤行为、相同的失败分类**。这四件事全部是**可观察的**，而
   `compile-env` 返回的 `compiled` 里有一样东西不可观察也不可比：每档的
   `:env` 里挂着一批 resolver **函数对象**，每次编译都是新的闭包，两侧永远
   `not=`。拿它做判据只会得到一个恒假的测试。

   因此本 ns 用三个判据，逐条对应 D4.10 的四件事：

   | 判据 | 覆盖 D4.10 的哪一件 |
   |---|---|
   | ① 三档各跑同一批 EQL（含真 SQLite 取数），响应 map 逐字相同 | 逐字相同的结果、档位过滤行为、失败分类 |
   | ② pathom index 的**可序列化投影**逐字相同（`index-oir`、`index-io`、resolver id 集合） | 相同的 index 形状 |
   | ③ `compiled` 去掉每档 `:env` 后的其余部分逐字相同（catalog、`attrs-by-entity`、`param-decls`、`:engine/tiers`、`:engine/limits`、`:engine/findings`…） | index 形状的其余面，以及编译期结论 |

   **为什么这三条加起来够**：`:env` 里唯一不可比的是函数对象本身，而
   ① 恰好穿过它们——同一批查询真的走到 SQLite 取回真的行，两侧逐字相同就说明
   这些闭包的**行为**相同；② 与 ③ 覆盖它们被组织成的那张表。剩下能瞒过这三条
   的差异，只有「两个行为完全相同的 env」，而那正是「等价」这个词的意思。

   反过来，为什么不能只留 ①：EQL 批次是有限的，它测不到没被查到的属性；
   ② 与 ③ 是全量的结构对照，把「有个属性悄悄没进 index」这类差异挡在
   查询覆盖率之外。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]
            ;; 装配：require 即注册 "sql" 的六个 SPI 方法。
            [open.atlas.common.connector.sql :as sql]))

;; --- 真库（与 vertical-slice-test 同一套接法，不同的库名以免互相踩）---------

(def ^:private jdbc-url
  "jdbc:sqlite:file:atlas_b3_conformance?mode=memory&cache=shared")

(defn- statements [sql]
  (->> (str/split-lines sql)
       (remove #(str/starts-with? (str/triml %) "--"))
       (str/join "\n")
       (#(str/split % #";"))
       (map str/trim)
       (remove str/blank?)))

(defn- with-db [f]
  (let [keep-alive (java.sql.DriverManager/getConnection jdbc-url)]
    (try
      (doseq [s (statements (slurp (io/resource "schema.sql")))]
        (jdbc/execute! keep-alive [s]))
      (f)
      (finally
        (sql/close-all-pools!)
        (.close keep-alive)))))

(use-fixtures :once with-db)

;; --- 两侧 --------------------------------------------------------------------

(defn- direct-model
  "左侧：规范化模型直接来自方言 reader。"
  []
  (let [{:keys [model findings]} (dialect/read-dialect (slurp (io/resource "graph.edn")))]
    (assert model (str "fixture must read clean: " (pr-str findings)))
    (assoc-in model [:sources "cis-demo-db" :jdbc_url] jdbc-url)))

(defn- via-artifact-model
  "右侧：同一个模型经 `encode_content → seal_graph → artifact reader` 走一圈。

   **走的是公共接口的全程**，包括 digest 复算：这条路径与 B5 的 `cut-version`
   将要走的是同一条（D4.10-a），不是为测试铺的捷径。

   `${B1_JDBC_URL}` 的替换在**装载之后**做，与左侧同一处：连接串是本机事实，
   它根本不该进制品（D4.5——凡是会自己变的、或因机器而异的，都不进制品）。
   这一条本身就是被这个测试顺带守住的——若哪天有人把它编进 content.edn，
   两侧的模型会一致，但制品会变得不可移植。"
  []
  (let [{:keys [model findings]} (dialect/read-dialect (slurp (io/resource "graph.edn")))
        _ (assert model (str "fixture must read clean: " (pr-str findings)))
        {:keys [content-edn content-hash] :as enc} (artifact/encode-content model [])
        _ (assert (empty? (:findings enc)) (pr-str (:findings enc)))
        sealed (artifact/seal-graph
                content-edn content-hash
                {:workspace "core-team" :graph "b1" :version 1
                 :cut-at "2026-08-22T00:00:00Z" :cut-by "b3-conformance"}
                {} {} {:attribute-count 12})
        _ (assert (empty? (:findings sealed)) (pr-str (:findings sealed)))
        ;; 两样外部校验都**显式声明**：digest 用密封时算出的那个值真的验一遍，
        ;; 闭包声明为不验（这份 fixture 没有引用）。reader 不接受沉默跳过
        ;; （D4.3/D4.11），右侧因此走的是 server 会走的那条完整路径。
        loaded (artifact/read-artifact (:files sealed)
                                       {:expected-digest (:artifact-digest sealed)
                                        :closure         :unverified})]
    (assert (some? (:model loaded)) (str "artifact must load clean: " (pr-str (:findings loaded))))
    (assert (= :verified (:artifact-digest (:verified loaded)))
            "右侧必须真的验过 digest，否则这个对照测的是一条 server 不会走的路径")
    (assoc-in (:model loaded) [:sources "cis-demo-db" :jdbc_url] jdbc-url)))

(def ^:private direct   (delay (engine/compile-env (direct-model))))
(def ^:private via-art  (delay (engine/compile-env (via-artifact-model))))

;; --- ① 同一批 EQL，逐字相同的结果 --------------------------------------------

(def ^:private eql-batch
  "覆盖 D4.10 点名的四件事：取数、参数收窄、跨 entity 导航、
   wildcard 过滤（档位过滤行为）、点名不够档属性与不存在属性（失败分类）。"
  [;; 取数
   {:entity {:cis.master.vendor/vend_no 1001}
    :query  [:cis.master.vendor/vend_name :cis.master.vendor/discontinued]}
   ;; list resolver + 排序 + 分页
   [{:cis.master.part/all_parts [:cis.master.part/sku_no :cis.master.part/part_no]}]
   ;; eql-param 收窄
   [{(list :cis.master.part/all_parts {:prod_code "A"}) [:cis.master.part/sku_no]}]
   ;; 跨 entity 导航（batch 对齐）
   {:entity {:cis.master.vendor/vend_no 1002}
    :query  [:cis.master.vendor/vend_name
             {:cis.master.vendor/parts [:cis.master.part/part_no]}]}
   ;; :restricted 属性
   {:entity {:cis.master.vendor/vend_no 1001}
    :query  [:cis.master.vendor/contact_email]}
   ;; wildcard —— 档位过滤行为
   {:entity {:cis.master.vendor/vend_no 1001} :query ['*]}
   ;; 点名不够档的属性 —— 失败分类
   {:entity {:cis.master.vendor/vend_no 1001}
    :query  [:cis.master.vendor/vend_name :cis.master.vendor/entry_by]}
   ;; 点名根本不存在的属性 —— 失败分类
   {:entity {:cis.master.vendor/vend_no 1001}
    :query  [:cis.master.vendor/no_such_attribute_at_all]}
   ;; 边界：超深查询
   [{:cis.master.part/all_parts [:cis.master.part/nonexistent]}]])

(defn- run-batch [compiled tier]
  (mapv #(engine/query compiled {:tier tier :graph "demo/b1" :version 1} %) eql-batch))

(deftest same-eql-batch-gives-verbatim-identical-answers-in-every-tier
  (testing "同一批 EQL 在三档上逐字相同——含真 SQLite 取回的真行、
            档位过滤报告与结构化拒绝（D4.10「逐字相同的结果 / 相同的档位过滤
            行为 / 相同的失败分类」）"
    (doseq [tier [:public :sensitive :restricted]]
      (let [a (run-batch @direct tier)
            b (run-batch @via-art tier)]
        (is (= a b) (str tier " 档的应答不逐字相同"))
        (testing "对照本身不是空的：这一档确实既取到了数据也产生了拒绝"
          (is (some #(= :ok (:engine/status %)) a) (str tier " 档没有任何成功应答"))
          (is (some #(= :rejected (:engine/status %)) a)
              (str tier " 档没有任何拒绝——失败分类那一半没被比到")))))))

(deftest the-batch-really-reaches-the-database
  (testing "对照跑的是真库而不是空表：否则「两边都返回空」也会全绿"
    (let [res (first (run-batch @via-art :restricted))]
      (is (= "Acme Industrial" (get-in res [:engine/data :cis.master.vendor/vend_name]))))))

;; --- ② index 的可序列化投影 --------------------------------------------------

(def ^:private index-oir :com.wsscode.pathom3.connect.indexes/index-oir)
(def ^:private index-io  :com.wsscode.pathom3.connect.indexes/index-io)
(def ^:private index-res :com.wsscode.pathom3.connect.indexes/index-resolvers)

(defn- index-shape
  "pathom index 的可序列化投影。

   `index-oir` 与 `index-io` 是纯数据（属性 → resolver 符号集合），逐字可比；
   `index-resolvers` 的**值**里挂着函数对象，因此只取它的**键集**——
   resolver id 是编译期决定的、稳定的、可序列化的东西，而函数对象每次编译
   都是新闭包，比它等于比 identity。"
  [compiled tier]
  (let [env (get-in compiled [:engine/by-tier tier :env])]
    {:oir          (get env index-oir)
     :io           (get env index-io)
     :resolver-ids (set (keys (get env index-res)))}))

(deftest index-shape-is-identical
  "相同的 index 形状（D4.10）——全量结构对照，不受 EQL 批次覆盖率限制。

   曾经这条按三个档位各比一次；档位退役之后（owner 裁定 2026-08-27）一张图只
   编译一份 env，于是只剩一次比较——比的东西没变，少的是那两份不再存在的副本。"
  (is (= (index-shape @direct :authoring) (index-shape @via-art :authoring)))
  (is (seq (:oir (index-shape @direct :authoring))) "对照本身不是空的"))

;; --- ③ compiled 去掉 :env 后的其余部分 ----------------------------------------

(defn- comparable-projection
  "`compiled` 里除 pathom env 以外的全部内容：catalog、`attrs-by-entity`、
   `param-decls`、`:engine/limits`、`:engine/model`、`:engine/navs`、
   `:engine/findings`。全部是可序列化数据。

   env **两处都要剥**：档位退役之后（owner 裁定 2026-08-27）它的正本住在
   `:engine/bundle`，`:engine/by-tier` 只是给 Studio explain 面留的同一份引用。
   漏剥哪一处，比较里就混进 pathom 的函数对象——那种东西永远不等于自己。"
  [compiled]
  (-> compiled
      (update :engine/bundle dissoc :env)
      (update :engine/by-tier update-vals #(dissoc % :env))))

(deftest the-rest-of-compiled-is-identical
  (testing "catalog、档位表、四道边界、编译期 findings 逐字相同"
    (is (= (comparable-projection @direct) (comparable-projection @via-art)))))

(deftest catalogs-are-identical-in-every-tier
  (testing "catalog 是「这一档看得见什么」的可观察投影（D5.7）——单独点名一次，
            因为它是部署前 owner 真正会看的那张表"
    (doseq [tier [:public :sensitive :restricted]]
      (is (= (engine/catalog @direct tier) (engine/catalog @via-art tier))
          (str tier " 档的 catalog 不同")))
    (is (seq (:attributes (engine/catalog @direct :restricted))))))

;; --- 前提：两侧的模型确实同源 -------------------------------------------------

(deftest the-two-sides-start-from-the-same-normalized-model
  (testing "env 等价的前提是模型恒等；恒等在 artifact 模块里对每一份 fixture
            逐一施加，这里只钉住本 ns 用的这一份"
    (is (= (direct-model) (via-artifact-model)))))

(deftest the-artifact-is-a-real-sealed-artifact-not-a-shortcut
  (testing "右侧确实走了完整的密封与 digest 校验；否则这个对照测的是一条
            B5 不会走的路径（D4.10-a 明确否掉了「B3 自己写一份简化的制品生成」）"
    (let [{:keys [content-edn content-hash]} (artifact/encode-content (direct-model) [])
          sealed (artifact/seal-graph content-edn content-hash
                                      {:workspace "core-team" :graph "b1" :version 1
                                       :cut-at "2026-08-22T00:00:00Z" :cut-by "t"}
                                      {} {} {:coverage 1.0})]
      (is (= 3 (count (:files sealed))))
      (is (re-matches #"sha256:[0-9a-f]{64}" (:artifact-digest sealed)))
      (testing "digest 对不上时装载被拒——右侧的 env 不可能来自一份被改过的制品"
        (is (nil? (:model (artifact/read-artifact
                           (update (:files sealed) "content.edn" str "\n")
                           {:expected-digest (:artifact-digest sealed)
                            :closure         :unverified}))))))))
