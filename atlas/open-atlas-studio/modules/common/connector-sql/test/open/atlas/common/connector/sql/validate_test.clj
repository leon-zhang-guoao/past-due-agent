(ns open.atlas.common.connector.sql.validate-test
  "写入端校验：sql-v1「连接 schema」「操作块 schema（封闭）」「support policy」
   与 conformance 的「占位符与 `:params` 恰好互相覆盖，多、少均拒绝」。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql]))

(defn- findings [cfg] (:findings (spi/validate-source cfg)))
(defn- codes [cfg] (set (map :code (findings cfg))))

(def base
  {:type "sql"
   :jdbc_url "${B1_JDBC_URL}"
   :query "SELECT vend_no, vend_name FROM vendors WHERE vend_no = :vend_no"
   :input [:cis.master.vendor/vend_no]
   :output [:cis.master.vendor/vend_name]
   :result_type "single"})

(deftest every-finding-is-well-formed
  (doseq [cfg [base
               (assoc base :password "hunter2")
               (assoc base :data_path "x")
               (dissoc base :query)]]
    (is (every? spi/finding? (findings cfg))
        "finding 过 finding-v1 §2 封闭键面，且 code 已登记")))

(deftest identity-default-covers-the-placeholder
  (testing "D2.1：占位名 = 属性 local name，`:params` 可以整个省掉"
    (is (empty? (findings base)))))

(deftest placeholder-without-a-binding-is-rejected
  (testing "conformance「少」：语句里有 :missing_thing，没人能绑它"
    (let [cfg (assoc base :query "SELECT 1 FROM vendors WHERE a = :vend_no AND b = :nobody")]
      (is (contains? (codes cfg) :connector.source/placeholder-mismatch))
      (is (some #(str/includes? (:message %) ":nobody") (findings cfg))))))

(deftest params-key-without-a-placeholder-is-rejected
  (testing "conformance「多」：`:params` 里写了语句里没有的占位名"
    (let [cfg (assoc base :params {"vend_no" :cis.master.vendor/vend_no
                                   "ghost"   :cis.master.vendor/vend_name})]
      (is (contains? (codes cfg) :connector.source/placeholder-mismatch))
      (is (some #(= [:source :params] (:path %)) (findings cfg))))))

(deftest explicit-params-that-exactly-cover-are-accepted
  (is (empty? (findings (assoc base :params {"vend_no" :cis.master.vendor/vend_no})))))

(deftest eql-param-name-can-bind-a-placeholder
  (let [cfg (assoc base
                   :query "SELECT vend_name FROM vendors WHERE discontinued = :flag"
                   :input []
                   :eql-params {:flag {:attribute :cis.master.vendor/discontinued :op :eq}})]
    (is (empty? (findings cfg)))))

(deftest literal-credentials-are-rejected-at-write-time
  (testing "sql-v1：「`:username`/`:password` 只收 `${VAR}`；字面值在写入端拒绝」"
    (is (contains? (codes (assoc base :password "hunter2"))
                   :connector.source/literal-credential))
    (is (contains? (codes (assoc base :username "sa"))
                   :connector.source/literal-credential))
    (is (empty? (findings (assoc base :username "${DB_USER}" :password "${DB_PASS}")))))
  (testing "「`:jdbc_url` 可含凭证成分，只收 `${VAR}` 或无凭证 URL」"
    (doseq [url ["jdbc:postgresql://sa:hunter2@host/db"
                 "jdbc:postgresql://host/db?user=sa&password=hunter2"
                 "jdbc:sqlserver://host;password=hunter2"]]
      (is (contains? (codes (assoc base :jdbc_url url))
                     :connector.source/literal-credential)
          url))
    (testing "凭证成分全在 ${VAR} 里的 URL 合法"
      (is (empty? (findings (assoc base :jdbc_url "jdbc:postgresql://${U}:${P}@host/db"))))
      (is (empty? (findings (assoc base :jdbc_url "jdbc:sqlite:file:x?mode=memory")))))))

(deftest findings-never-carry-the-value-they-reject
  (testing "finding-v1 §5：`:got` 与 `:message` 不得含凭证/语句原文"
    (let [fs (findings (assoc base :password "hunter2"
                              :jdbc_url "jdbc:postgresql://sa:hunter2@host/db"))
          blob (pr-str fs)]
      (is (seq fs))
      (is (not (str/includes? blob "hunter2")))
      (is (not (str/includes? blob "host/db")))
      (is (every? #(or (nil? (:got %)) (string? (:got %)) (number? (:got %))) fs))
      (is (some #(str/starts-with? (str (:got %)) "string(") fs)
          "操作块/连接串内容一律截断为类型描述"))))

(deftest timeout-cannot-be-disabled
  (testing "principles：「enforce 不了超时的连接器不允许发布」"
    (doseq [bad [0 -1 "30s" 1.5]]
      (is (contains? (codes (assoc base :resilience {:timeout_ms bad}))
                     :connector.source/timeout-not-enforceable)
          (str bad)))
    (is (empty? (findings (assoc base :resilience {:timeout_ms 5000}))))
    (testing "不写 :resilience 也合法——超时「有默认值」"
      (is (empty? (findings base))))))

(deftest unknown-keys-are-rejected
  (testing "sql-v1 support policy：「未知操作键一律 `:reject`」"
    (is (contains? (codes (assoc base :retries 3)) :connector.source/unknown-key)))
  (testing "「`:data_path` 非 sql 概念，`:reject`」"
    (let [fs (findings (assoc base :data_path "data.rows"))]
      (is (some #(= [:source :data_path] (:path %)) fs))
      (is (some #(str/includes? (:message %) "not a sql concept") fs)))))

(deftest required-keys
  (is (contains? (codes (dissoc base :query)) :connector.source/missing-key))
  (is (contains? (codes (dissoc base :jdbc_url)) :connector.source/missing-key)))

(deftest batch-block-shape
  (let [batched (assoc base
                       :query "SELECT sku_no, part_no FROM parts WHERE sku_no IN (:sku_nos)"
                       :input [:cis.master.part/sku_no]
                       :output [:cis.master.part/part_no])]
    (testing "完整的 batch 块合法"
      (is (empty? (findings (assoc batched :batch {:parent-key :cis.master.part/sku_no
                                                   :child-fk "sku_no"
                                                   :placeholder "sku_nos"})))))
    (testing "缺键"
      (is (contains? (codes (assoc batched :batch {:child-fk "sku_no"}))
                     :connector.source/missing-key)))
    (testing ":placeholder 必须真的出现在语句里"
      (is (contains? (codes (assoc batched :batch {:parent-key :cis.master.part/sku_no
                                                   :child-fk "sku_no"
                                                   :placeholder "nope"}))
                     :connector.source/placeholder-mismatch)))
    (testing ":placeholder 由父键绑定，不许同时写进 :params"
      (is (contains? (codes (assoc batched
                                   :batch {:parent-key :cis.master.part/sku_no
                                           :child-fk "sku_no" :placeholder "sku_nos"}
                                   :params {"sku_nos" :cis.master.part/sku_no}))
                     :connector.source/placeholder-mismatch)))))

(deftest mutation-must-not-declare-batch
  (testing "dialect-v1 §3：「mutation 操作块不得声明 `:batch`——batch 是消 N+1 的
            读优化，写路径声明它必是配置错误」"
    (let [cfg (assoc base
                     :query "UPDATE vendors SET vend_name = :vend_name WHERE vend_no IN (:ids)"
                     :params {"vend_name" :cis.master.vendor/vend_name
                              "ids" :cis.master.vendor/vend_no}
                     :batch {:parent-key :cis.master.vendor/vend_no :child-fk "vend_no"
                             :placeholder "ids"})
          fs (:findings (spi/validate-mutation cfg))]
      (is (spi/errors? fs))
      (is (some #(str/includes? (:message %) "must not declare :batch") fs)))))

(deftest unregistered-source-type-falls-to-the-spi-default
  (let [fs (:findings (spi/validate-source {:type "sqlite-ish"}))]
    (is (= [:connector.source/unknown-type] (mapv :code fs)))))

;; ---------------------------------------------------------------------------
;; :resilience / :policy key face (doc 107 §107.3 / §107.11, owner 2026-09-18)
;;
;; sql lands in the SAME batch as api and applies the SAME rule. These are the
;; api assertions restated here on purpose: the day the two connectors answer
;; differently, one of the two suites goes red rather than nobody noticing.
;; ---------------------------------------------------------------------------

(deftest resilience-key-face-is-closed
  (testing "the ruled keys pass"
    (is (= #{} (codes (assoc base :resilience
                             {:timeout_ms 30000 :max_retries 2
                              :retry_on #{:connection}
                              :backoff_ms [200 2000 2.0] :jitter 0.2
                              :bulkhead {:concurrency 8 :max_wait_ms 100}
                              :circuit_breaker {:failure_ratio [20 50] :reset_ms 10000}})))))
  (testing "an unlisted key is refused, not accepted and ignored"
    (is (= #{:connector.source/unknown-key}
           (codes (assoc base :resilience {:timeout_ms 30000 :retry_forever true})))))
  (testing "a retry declared on a non-transient category is refused (D6.4)"
    (doseq [cat [:source :mapping :timeout :unauthorized :not-found :input-rejected]]
      (is (= #{:connector.source/invalid-shape}
             (codes (assoc base :resilience {:retry_on #{cat}})))
          (str cat))))
  (testing "every transient category is accepted"
    (doseq [cat spi/transient-categories]
      (is (= #{} (codes (assoc base :resilience {:retry_on #{cat}})))))))

(deftest policy-carries-only-the-semantic-half
  (is (= #{} (codes (assoc base :policy {:max_retries 1 :retry_on #{:connection}}))))
  (doseq [k [:timeout_ms :bulkhead :circuit_breaker :backoff_ms :jitter]]
    (is (= #{:connector.source/unknown-key} (codes (assoc base :policy {k 1}))) (str k))))

(deftest status-map-is-refused-on-a-sql-source-until-sqlstate-is-ruled
  ;; doc 107 §107.9 item 11 defers sql's SQLSTATE -> category mapping until
  ;; there is corpus to rule it on. Until then the key is REFUSED rather than
  ;; accepted and ignored: a modeller writing it, passing validation and having
  ;; nothing happen is precisely the failure §107.1 documents.
  (let [finding (first (findings (assoc base :policy {:status-map {40001 :not-found}})))]
    (is (= :connector.source/unknown-key (:code finding)))
    (is (= :error (:severity finding)))
    (is (= [:source :policy] (:path finding)))
    (is (str/includes? (:message finding) ":status-map")))
  (testing "the keys sql does honour are unaffected"
    (is (= #{} (codes (assoc base :policy {:max_retries 1 :retry_on #{:connection}}))))))
