(ns open.atlas.common.dialect.schema-test
  "结构层（B0-4）测试：conformance 矩阵（dialect-v1 §7）中属于单条目结构的行——
   封闭（未知键点名）、词表（词表外拒绝）、缺省值书写拒绝，以及 §5 三个规范形
   样例零 findings。跨条目关系（D2.19/20/22/25/26/27）不在本层。"
  (:require [clojure.test :refer [deftest is testing]]
            #_{:clj-kondo/ignore [:discouraged-namespace]}
            [open.atlas.common.dialect.impl.schema :as schema]
            #_{:clj-kondo/ignore [:discouraged-namespace]}
            [open.atlas.common.dialect.impl.finding :as finding]))

(defn- codes [findings] (set (map :code findings)))

(defn- f-at
  "取指定 :path 上的 finding。"
  [findings path]
  (first (filter #(= path (:path %)) findings)))

;; --- §5 样例（规范形种子）---------------------------------------------------

(def sample-single
  {:resolvers
   [{:cache {:ttl-ms 600000}
     :description "Vendor master record by vendor number."
     :id "demo_vendor_by_id"
     :input [:cis.master.vendor/vend_no]
     :label "Vendor by vend_no"
     :output [:cis.master.vendor/vend_name :cis.master.vendor/vend_type
              :cis.master.vendor/primary_loc :cis.master.vendor/discontinued]
     :result_type "single"
     :source {:query "SELECT vend_no FROM vendors WHERE vend_no = :vend_no"
              :type "sql"}
     :source-ref "cis-demo-db"}]})

(def sample-batch
  {:resolvers
   [{:cache {:ttl-ms 600000}
     :description "Catalog part by SKU"
     :id "demo_part_by_id"
     :input [:cis.master.part/sku_no]
     :label "Part by sku_no"
     :output [:cis.master.part/part_no :cis.master.part/short_desc]
     :result_type "single"
     :source {:batch {:child-fk "sku_no"
                      :parent-key :cis.master.part/sku_no
                      :placeholder "sku_nos"}
              :query "SELECT sku_no FROM parts WHERE sku_no IN (:sku_nos)"
              :type "sql"}
     :source-ref "cis-demo-db"}]})

(def sample-curated
  {:resolvers
   [{:curated? true
     :description "SKU 目录全集"
     :eql-params {:prod_code {:attribute :cis.master.part/prod_code
                              :description "只取该产品码的 part。"
                              :op :eq}}
     :id "demo_all_parts"
     :input []
     :label "All parts (SKU catalog)"
     :limit {:default 50 :max 500}
     :order {:allowed [:cis.master.part/sku_no :cis.master.part/part_no]
             :default [:cis.master.part/sku_no :asc]}
     :output [{:cis.master.part/all_parts [:cis.master.part/sku_no
                                           :cis.master.part/part_no]}]
     :result_type "list"
     :source {:query "SELECT sku_no FROM parts" :type "sql"}
     :source-ref "cis-demo-db"}]})

(def sample-namespaces
  {:attributes
   {:cis.master.part/all_parts
    {:label "SKU 目录全集" :target "cis.master.part"}
    :cis.master.vendor/discontinued
    {:description "Whether the vendor is discontinued."
     :format :code
     :label "Discontinued"
     :synonyms ["是否停用" "停用" "discontinued" "inactive"]
     :type :string
     :values {"N" {:label "在用"} "Y" {:label "已停用"}}}
    :cis.master.vendor/vend_no
    {:description "Unique vendor number."
     :format :id
     :identity? true
     :label "Vendor No"
     :synonyms ["供应商编号"]
     :type :integer}}
   :namespaces
   {"cis.master.part"
    {:kind :master
     :label "Part — catalog item (SKU)"}
    "cis.master.vendor"
    {:kind :master
     :label "Vendor — supplier on a purchase order"
     :synonyms ["供应商" "supplier"]}}})

(deftest canonical-samples-clean
  (testing "dialect-v1 §5 的样例零 findings"
    (doseq [[name sample] [["single" sample-single] ["batch" sample-batch]
                           ["curated" sample-curated] ["namespaces" sample-namespaces]]]
      (is (= [] (schema/check sample)) name))))

(deftest discriminated-attribute-shape
  "D2.29「分支即属性」：标量与 join 是两种互斥的条目，判别键是 `:target`。
   这些用例是 `:ref-many` 那条教训的结构化身——类型词表不再为语法背锅，
   因为 join 键根本没有 `:type` 这个键可写。"
  (testing ":type 与 :target 同写即拒绝（不是「两个可选键都填了」）"
    (let [fs (schema/check {:attributes {:a.b/c {:type :json :target "a.b"}}})]
      (is (= #{:dialect.schema/unknown-key} (codes fs)))
      (is (= :type (:got (f-at fs [:attributes :a.b/c :type]))))))
  (testing "join 属性不得绑定 :term（v1 不提供，owner-accepted default）"
    (let [fs (schema/check {:attributes {:a.b/c {:target "a.b" :term "t"}}})]
      (is (= :dialect.schema/unknown-key
             (:code (f-at fs [:attributes :a.b/c :term]))))))
  (testing "join 属性没有 :values / :format / :unit / :identity? 可写"
    (doseq [[k v] [[:values {"A" {}}] [:format :code] [:unit "USD"] [:identity? true]]]
      (is (= :dialect.schema/unknown-key
             (:code (f-at (schema/check {:attributes {:a.b/c {:target "a.b" k v}}})
                          [:attributes :a.b/c k])))
          (str k " 应作为未知键拒绝"))))
  (testing ":collection? 已退役（D2.29 取代 2026-08-26 的 collection 裁定）"
    (is (= :dialect.schema/unknown-key
           (:code (f-at (schema/check {:attributes {:a.b/c {:type :json :collection? true}}})
                        [:attributes :a.b/c :collection?])))))
  (testing "namespace 条目不再有 :navigations"
    (is (= :dialect.schema/unknown-key
           (:code (f-at (schema/check {:namespaces {"a.b" {:navigations {"x" {:target "a.b"}}}}})
                        [:namespaces "a.b" :navigations])))))
  (testing "正例：两种形状各自干净"
    (is (= [] (schema/check {:attributes {:a.b/c {:type :string}
                                          :a.b/d {:target "a.b"
                                                  :cardinality :one-to-many
                                                  :label "All"
                                                  :synonyms ["全部"]
                                                  :description "d"}}})))))

(deftest closed-key-sets
  (testing "顶层未知键点名"
    (let [fs (schema/check {:resolverz []})]
      (is (= #{:dialect.schema/unknown-key} (codes fs)))
      (is (= :resolverz (:got (f-at fs [:resolverz]))))))
  (testing "条目未知键点名并给出方言键路径（非 malli 内部路径）"
    (let [fs (schema/check (assoc-in sample-single [:resolvers 0 :nope] 1))
          f  (f-at fs [:resolvers 0 :nope])]
      (is (= :dialect.schema/unknown-key (:code f)))
      (is (= :nope (:got f)))))
  (testing "操作块未知键点名（连接器契约 support policy :reject）"
    (let [fs (schema/check (assoc-in sample-single [:resolvers 0 :source :data_path] "$.x"))]
      (is (= :dialect.schema/unknown-key
             (:code (f-at fs [:resolvers 0 :source :data_path]))))))
  (testing "attribute 未知键"
    (is (= :dialect.schema/unknown-key
           (:code (f-at (schema/check (assoc-in sample-namespaces
                                                [:attributes :cis.master.vendor/vend_no :nullable?] true))
                        [:attributes :cis.master.vendor/vend_no :nullable?]))))))

(deftest required-keys
  (testing "缺 :result_type"
    (is (= :dialect.schema/missing-key
           (:code (f-at (schema/check (update-in sample-single [:resolvers 0] dissoc :result_type))
                        [:resolvers 0 :result_type])))))
  (testing "sql 操作块缺 :query"
    (is (= :dialect.schema/missing-key
           (:code (f-at (schema/check (update-in sample-single [:resolvers 0 :source] dissoc :query))
                        [:resolvers 0 :source :query]))))))

(deftest vocabulary-enforcement
  (testing ":result_type 词表外拒绝并带 :expected 词表集合"
    (let [f (f-at (schema/check (assoc-in sample-single [:resolvers 0 :result_type] "many"))
                  [:resolvers 0 :result_type])]
      (is (= :dialect.schema/vocab-violation (:code f)))
      (is (= #{"single" "list"} (:expected f)))))
  (testing "attribute :type 词表外拒绝"
    (let [f (f-at (schema/check (assoc-in sample-namespaces
                                          [:attributes :cis.master.vendor/vend_no :type] :bigint))
                  [:attributes :cis.master.vendor/vend_no :type])]
      (is (= :dialect.schema/vocab-violation (:code f)))
      (is (contains? (:expected f) :decimal))))
  (testing "eql-param :op 词表外拒绝"
    (is (= :dialect.schema/vocab-violation
           (:code (f-at (schema/check (assoc-in sample-curated
                                                [:resolvers 0 :eql-params :prod_code :op] :contains))
                        [:resolvers 0 :eql-params :prod_code :op])))))
  (testing "source :type 非已注册契约名拒绝"
    (let [f (f-at (schema/check {:sources {"s" {:type "meilisearch" :label "x"}}})
                  [:sources "s" :type])]
      (is (= :dialect.schema/vocab-violation (:code f)))
      (is (= #{"sql" "api" "static" "derived" "nifi"} (:expected f)))))
  (testing "mutation 操作块 type 不支持 mutation 的连接器拒绝"
    (is (= :dialect.schema/vocab-violation
           (:code (f-at (schema/check {:mutations [{:id "m" :result_type "none"
                                                    :source {:type "static" :data {}}}]})
                        [:mutations 0 :source :type]))))))

(deftest defaults-must-not-be-written
  (testing ":curated? false 书写即拒绝"
    (let [f (f-at (schema/check (assoc-in sample-single [:resolvers 0 :curated?] false))
                  [:resolvers 0 :curated?])]
      (is (= :dialect.schema/default-written (:code f)))
      (is (false? (:got f)))))
  (testing ":direction :bi 书写即拒绝"
    (is (= :dialect.schema/default-written
           (:code (f-at (schema/check {:links [{:from :a/b :to :c/d :direction :bi}]})
                        [:links 0 :direction])))))
  (testing ":sensitivity 不再是方言模型的一部分"
    (is (= :dialect.schema/unknown-key
           (:code (f-at (schema/check {:attributes {:a/b {:type :string :sensitivity :public}}})
                        [:attributes :a/b :sensitivity])))))
  (testing "mutation 参数 :required? false 书写即拒绝"
    (is (= :dialect.schema/default-written
           (:code (f-at (schema/check {:mutations [{:id "m" :result_type "none"
                                                    :source {:type "sql" :query "UPDATE t SET x=1"}
                                                    :params {:a/b {:required? false}}}]})
                        [:mutations 0 :params :a/b :required?])))))
  (testing "合法的非缺省值通过"
    (is (= [] (schema/check {:links [{:from :a/b :to :c/d :direction :one-way}]})))))

(deftest attribute-reference-spelling
  (testing "字符串形属性引用不是合法输入（D2.17）"
    (is (= :dialect.schema/invalid-shape
           (:code (f-at (schema/check (assoc-in sample-single [:resolvers 0 :input]
                                                ["cis.master.vendor/vend_no"]))
                        [:resolvers 0 :input 0]))))))

(deftest carried-blocks-validated
  (testing ":cache :row-security 内部结构完整校验"
    (is (= :dialect.schema/invalid-shape
           (:code (f-at (schema/check (assoc-in sample-single [:resolvers 0 :cache :ttl-ms] -1))
                        [:resolvers 0 :cache :ttl-ms]))))
    ;; E1 / D5.14：`:row-security` 的键面从「非空 {keyword → any}」收紧成封闭
    ;; 单键——它不再是纯 carried，切版本时有人**读** `:attribute` 并核对接线。
    (is (= [] (schema/check (assoc-in sample-single [:resolvers 0 :row-security]
                                      {:attribute :cis.master.part/tenant_id}))))
    (testing "多余键拒绝而不是静默忽略（否则就是一条没人核对的声明）"
      (is (= :dialect.schema/unknown-key
             (:code (f-at (schema/check (assoc-in sample-single [:resolvers 0 :row-security]
                                                  {:attribute :cis.master.part/tenant_id
                                                   :policy :tenant}))
                          [:resolvers 0 :row-security :policy])))))
    (testing ":attribute 必填、且必须是限定 keyword"
      (is (= :dialect.schema/missing-key
             (:code (f-at (schema/check (assoc-in sample-single [:resolvers 0 :row-security] {}))
                          [:resolvers 0 :row-security :attribute]))))
      (is (= :dialect.schema/invalid-shape
             (:code (f-at (schema/check (assoc-in sample-single [:resolvers 0 :row-security]
                                                  {:attribute :tenant_id}))
                          [:resolvers 0 :row-security :attribute])))))
    (is (= [] (schema/check {:metrics {"m" {:entity "cis.master.part"
                                            :expression {:contract :duckdb-spill/v1
                                                         :text "sum(x)"}}}}))))
  (testing "metric :expression 契约名错拒绝"
    (is (= :dialect.schema/invalid-shape
           (:code (f-at (schema/check {:metrics {"m" {:entity "e"
                                                      :expression {:contract :other/v1
                                                                   :text "x"}}}})
                        [:metrics "m" :expression :contract]))))))

(deftest no-leak-in-got
  (testing "操作块内容进 :got 前过 redact（finding-v1 §5）"
    (let [f (f-at (schema/check (assoc-in sample-single [:resolvers 0 :source :query] 42))
                  [:resolvers 0 :source :query])]
      (is (= "42" (str (:got f)))))
    (let [f (f-at (schema/check {:metrics {"m" {:entity "e"
                                                :expression {:contract :duckdb-spill/v1
                                                             :text 1}}}})
                  [:metrics "m" :expression :text])]
      (is (some? f)))
    (let [f (f-at (schema/check {:resolvers [{:id "r" :input [] :output []
                                              :result_type "single"
                                              :source {:type "sql" :query "SELECT secret"
                                                       :params "not-a-map"}}]})
                  [:resolvers 0 :source :params])]
      (is (= "string(9 chars)" (:got f))))))

(deftest findings-are-well-formed-and-deterministic
  (testing "全部 finding 过 finding-v1 §2 schema 且 code 已注册"
    (let [fs (schema/check (-> sample-single
                               (assoc-in [:resolvers 0 :curated?] false)
                               (assoc-in [:resolvers 0 :zzz] 1)
                               (assoc-in [:resolvers 0 :result_type] "many")))]
      (is (seq fs))
      (is (every? finding/valid? fs))
      (is (every? #(contains? finding/codes (:code %)) fs))
      (testing "两次校验逐字节相同"
        (is (= (pr-str fs)
               (pr-str (schema/check (-> sample-single
                                         (assoc-in [:resolvers 0 :curated?] false)
                                         (assoc-in [:resolvers 0 :zzz] 1)
                                         (assoc-in [:resolvers 0 :result_type] "many")))))))))
  (testing "根非 map"
    (is (= :dialect.schema/invalid-shape (:code (first (schema/check [1 2])))))))

(deftest connector-key-faces
  (testing "sql 连接键封闭：:base_url 是 api 的键，出现在 sql source 即未知键"
    (is (= :dialect.schema/unknown-key
           (:code (f-at (schema/check {:sources {"s" {:type "sql" :jdbc_url "${URL}"
                                                      :base_url "https://x"}}})
                        [:sources "s" :base_url])))))
  (testing "api source 缺 :base_url"
    (is (= :dialect.schema/missing-key
           (:code (f-at (schema/check {:sources {"s" {:type "api"}}})
                        [:sources "s" :base_url])))))
  (testing "static/derived 无扩展键"
    (is (= [] (schema/check {:sources {"s" {:type "static" :label "inline"}}})))
    (is (= :dialect.schema/unknown-key
           (:code (f-at (schema/check {:sources {"s" {:type "derived" :jdbc_url "x"}}})
                        [:sources "s" :jdbc_url])))))
  (testing "api eql-param 扩展键仅在 api resolver 上合法"
    (is (= [] (schema/check {:resolvers [{:id "r" :input [] :output [] :result_type "list"
                                          :eql-params {:q {:type :string :param_location "query"}}
                                          :source {:type "api" :method "GET" :endpoint "/x"}}]})))
    (is (= :dialect.schema/unknown-key
           (:code (f-at (schema/check {:resolvers [{:id "r" :input [] :output []
                                                    :result_type "list"
                                                    :eql-params {:q {:type :string
                                                                     :param_location "query"}}
                                                    :source {:type "sql" :query "SELECT 1"}}]})
                        [:resolvers 0 :eql-params :q :param_location])))))
  (testing "derived 操作块 :expr 必填"
    (is (= :dialect.schema/missing-key
           (:code (f-at (schema/check {:resolvers [{:id "r" :input [] :output []
                                                    :result_type "single"
                                                    :source {:type "derived"}}]})
                        [:resolvers 0 :source :expr]))))))

(deftest api-auth-discriminated-block
  ;; api-v1「`:auth`：判别式块」：`:type` 必填判别符，两个变体各自封闭。
  (letfn [(chk [auth] (schema/check {:sources {"s" {:type "api" :base_url "https://x"
                                                    :auth auth}}}))]
    (testing "oauth2 变体合法"
      (is (= [] (chk {:type "oauth2" :token_url "https://t" :grant_type "client_credentials"
                      :client_id "${ID}" :client_secret "${SECRET}" :client_auth "basic"
                      :scope "read" :expiry_skew_s 30 :timeout_ms 5000}))))
    (testing "api_key 变体合法（:header_name / :header_prefix 有缺省，可省）"
      (is (= [] (chk {:type "api_key" :value "${K}"})))
      (is (= [] (chk {:type "api_key" :value "${K}" :header_name "X-Api-Key"
                      :header_prefix "Bearer "}))))
    (testing "缺判别符 → missing-key"
      (is (= :dialect.schema/missing-key
             (:code (f-at (chk {:token_url "https://t"}) [:sources "s" :auth :type])))))
    (testing "判别符不在词表 → vocab-violation"
      (let [f (f-at (chk {:type "basic" :value "${K}"}) [:sources "s" :auth :type])]
        (is (= :dialect.schema/vocab-violation (:code f)))
        (is (= #{"oauth2" "api_key"} (:expected f)))))
    (testing "变体必填键缺席 → missing-key"
      (is (= :dialect.schema/missing-key
             (:code (f-at (chk {:type "oauth2" :client_id "${ID}"})
                          [:sources "s" :auth :token_url]))))
      (is (= :dialect.schema/missing-key
             (:code (f-at (chk {:type "api_key" :header_name "X-Api-Key"})
                          [:sources "s" :auth :value])))))
    (testing "变体内未知键 → unknown-key（键面按判别符分，不取并集）"
      ;; 这正是判别符要解决的问题：合成一张表就只能按最松的并集校验。
      (is (= :dialect.schema/unknown-key
             (:code (f-at (chk {:type "api_key" :value "${K}" :scope "read"})
                          [:sources "s" :auth :scope]))))
      (is (= :dialect.schema/unknown-key
             (:code (f-at (chk {:type "oauth2" :token_url "https://t" :value "${K}"})
                          [:sources "s" :auth :value])))))))

(deftest mutation-op-no-batch
  (testing "mutation 操作块声明 :batch 拒绝（dialect-v1 §3 裁定）"
    (let [m {:mutations [{:id "m" :result_type "none"
                          :source {:type "sql" :query "DELETE FROM t WHERE id = :id"
                                   :batch {:parent-key :a/b :child-fk "id" :placeholder "ids"}}}]}]
      (is (seq (schema/check m))))))

(deftest kind-grain-vocab
  (testing ":kind / :grain 词表外拒绝（D2.7）"
    (is (seq (schema/check {:namespaces {"x" {:kind :made-up}}})))
    (is (seq (schema/check {:metrics {"m" {:entity "x"
                                           :expression {:contract :duckdb-spill/v1 :text "count(*)"}
                                           :grain :hour}}})))))

(deftest security-is-not-a-key-any-more
  "D5.12：`:security {:roles […]}` 从 attribute / resolver / mutation 三处退役。
   它曾标着 carried（「本平台不解释」）却有一个真消费者拿它做角色门禁——同一个键
   在 Studio 里是装饰、在独立 server 上是锁。**没上锁而以为上了锁**是这条要清的
   东西，所以写出它必须是拒绝，不是忽略。"
  (doseq [[path label] [[[:resolvers 0 :security] "resolver"]
                        [[:attributes :cis.master.part/sku_no :security] "attribute"]]]
    (is (= :dialect.schema/unknown-key
           (:code (f-at (schema/check (assoc-in sample-single path {:roles ["a"]})) path)))
        (str label " 上的 :security 应被拒"))))
