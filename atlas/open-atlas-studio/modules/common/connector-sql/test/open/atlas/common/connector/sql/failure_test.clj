(ns open.atlas.common.connector.sql.failure-test
  "故障不携带业务数据 + 超时强制。

   principles：「每个连接器故障都是带 `{:error/category …, :resolver-id,
   :source-type}` 的 `ex-info`，`ex-data` 里永远没有语句、带查询串的 URL、
   响应体、凭证或行值——这个异常要一路走到 UI 与 MCP。」
   sql-v1 conformance：「超时强制且故障 `ex-data` 不含语句与行值。」"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql]
            [open.atlas.common.connector.sql.db :as db]
            [open.atlas.common.connector.sql.impl.exec :as exec]))

(use-fixtures :once db/with-fixture-db)

(def ^:private secret-ish
  {:type "sql"
   ;; 故意把每一类不该泄漏的东西都塞进 config：语句、URL、凭证。
   :jdbc_url "jdbc:sqlite:file:atlas_b1_fixture?mode=memory&cache=shared&password=hunter2"
   :username "sa"
   :password "hunter2"
   :query "SELECT secret_column FROM table_that_does_not_exist WHERE x = :vend_no"})

(def ^:private resolver
  {:id "demo_vendor_by_id" :input [:cis.master.vendor/vend_no]
   :output [:cis.master.vendor/vend_name] :result_type "single"})

(defn- caught [f] (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(defn- run-failing []
  (let [read (spi/compile-source secret-ish db/*ds* resolver)]
    (read {:cis.master.vendor/vend_no 1001} nil)))

(deftest source-error-ex-data-is-exactly-the-three-contract-keys
  (let [e (caught run-failing)]
    (is (some? e))
    (is (spi/failure? e))
    (is (= {:error/category :source
            :resolver-id    "demo_vendor_by_id"
            :source-type    "sql"}
           (ex-data e)))))

(deftest nothing-anywhere-on-the-exception-leaks-config-or-data
  (let [e (caught run-failing)
        surface (str (pr-str (ex-data e)) " " (ex-message e))]
    (doseq [leak ["secret_column" "table_that_does_not_exist" "SELECT"
                  "hunter2" "jdbc:sqlite" "1001" "Acme Industrial"]]
      (is (not (str/includes? surface leak)) (str "泄漏了：" leak)))
    (testing "也没有 cause 链可以走回驱动原文"
      (is (nil? (.getCause e))))))

(deftest unresolved-env-var-is-a-connection-failure
  (testing "`${B1_JDBC_URL}` 没配就是连不上，不是「查无此行」"
    (let [cfg  {:type "sql" :jdbc_url "${OPEN_ATLAS_DEFINITELY_UNSET_VAR}"
                :query "SELECT 1 AS x"}
          read (spi/compile-source cfg nil (assoc resolver :input [] :output []))
          e    (caught #(read {} nil))]
      (is (spi/failure? e))
      (is (= :connection (spi/error-category e)))
      (testing "变量名可以出现在 message——它是方言里的配置，不是凭证值"
        (is (str/includes? (ex-message e) "OPEN_ATLAS_DEFINITELY_UNSET_VAR"))))))

(deftest missing-driver-is-a-connection-failure
  (testing "a missing JDBC driver is :connection -- it was :source until 2026-09-01"
    ;; Verbatim shape of what HikariCP 5.1.0 throws when no driver on the
    ;; classpath handles the jdbc_url: the naming words are in the CAUSE, and
    ;; the wrapper says something else entirely. Classifying on the wrapper
    ;; alone put a missing jar in front of the operator as a query fault.
    (let [cause (java.sql.SQLException. "No suitable driver")
          e     (RuntimeException.
                 "Failed to get driver instance for jdbcUrl=jdbc:clickhouse://h:8123/d"
                 cause)]
      (is (= :connection (exec/classify e)))))
  (testing "and still :connection when the driver-level cause is absent"
    (is (= :connection
           (exec/classify
            (RuntimeException. "Failed to get driver instance for jdbcUrl=jdbc:postgresql://h/d")))))
  (testing "an ordinary source error stays :source"
    (is (= :source (exec/classify (java.sql.SQLException. "no such column: nope")))))
  (testing "a cyclic cause chain terminates rather than hanging"
    ;; `.initCause` refuses self-causation, so a driver that produces a cycle
    ;; does it by overriding `getCause` -- which is exactly what the identity
    ;; guard in `message-chain` is there to survive.
    (let [e (proxy [RuntimeException] ["boom"] (getCause [] this))]
      (is (= :source (exec/classify e))))))

(deftest timeout-has-a-default
  (testing "principles：「超时强制且有默认值」"
    (is (= 30000 exec/default-timeout-ms))
    (is (= 30 (exec/timeout-s {})) "不写 :resilience 也有超时")
    (is (= 5 (exec/timeout-s {:resilience {:timeout_ms 5000}})))
    (is (= 1 (exec/timeout-s {:resilience {:timeout_ms 1}})) "向上取整，最小 1 秒")))

(deftest timeout-is-actually-enforced
  (testing "跑飞的查询被掐断，归类为 :timeout，且 ex-data 仍是那三个键"
    (let [cfg  {:type "sql" :resilience {:timeout_ms 1000}
                :query (str "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL "
                            "SELECT x+1 FROM c WHERE x < 900000000) "
                            "SELECT count(*) AS n FROM c")}
          read (spi/compile-source cfg db/*ds*
                                   {:id "runaway" :input [] :output [] :result_type "single"})
          e    (caught #(read {} nil))]
      (is (some? e) "跑飞的查询必须被掐断，而不是一直占着调用线程")
      (is (spi/failure? e))
      (is (= :timeout (spi/error-category e)))
      (is (= #{:error/category :resolver-id :source-type} (set (keys (ex-data e))))))))

(deftest health-check-reports-status-without-leaking
  (testing "principles：「可用性按需验证、绝不在装载时验证」"
    (let [ok (spi/health-check {:type "sql"} db/*ds*)]
      (is (= :ok (:status ok)))
      (is (nat-int? (:latency-ms ok))))
    (let [down (spi/health-check {:type "sql" :jdbc_url "${OPEN_ATLAS_DEFINITELY_UNSET_VAR}"} nil)]
      (is (= :down (:status down)))
      (is (= :connection (:error/category down)))
      (is (not (str/includes? (pr-str down) "SELECT"))))))

(deftest mapping-failure-when-the-query-does-not-select-a-mapped-column
  (testing "拿到了行但对不上模型 → :mapping，不是半填 map"
    (let [cfg  {:type "sql" :query "SELECT vend_no FROM vendors WHERE vend_no = :vend_no"}
          read (spi/compile-source cfg db/*ds* resolver)
          e    (caught #(read {:cis.master.vendor/vend_no 1001} nil))]
      (is (spi/failure? e))
      (is (= :mapping (spi/error-category e))))))

;; ---------------------------------------------------------------------------
;; doc 107 §107.3 / §107.11: the policy vocabulary and the guards, on sql
;; ---------------------------------------------------------------------------

(deftest the-effective-policy-is-the-same-arithmetic-as-api
  ;; One implementation (`spi/effective-policy`), two connectors. What differs
  ;; is only the connector-level default, which is why this asserts the MERGE
  ;; rather than re-testing the merge itself.
  (let [p (exec/effective-policy {:resilience {:max_retries 2 :retry_on #{:connection}}}
                                 {:policy {:max_retries 3}})]
    (is (= 3 (:max_retries p)) "the binding override wins over the slot")
    (is (= #{:connection} (:retry_on p)))
    (is (= exec/default-timeout-ms (:timeout_ms p))
        "sql's own default, not the api one"))
  (testing "a non-transient category never becomes effective"
    (is (= #{} (:retry_on (exec/effective-policy {:resilience {:retry_on #{:source}}}))))))

(deftest the-fingerprint-is-the-database-not-the-credentials
  (let [a (exec/fingerprint {:jdbc_url "jdbc:postgresql://db/x?password=hunter2" :username "sa"})
        b (exec/fingerprint {:jdbc_url "jdbc:postgresql://db/x?password=rotated" :username "sa"})
        c (exec/fingerprint {:jdbc_url "jdbc:postgresql://db/y" :username "sa"})]
    (is (= a b) "two pools to one database are one load on that database")
    (is (not= a c))
    (is (not (str/includes? a "hunter2")))
    (is (not (str/includes? a "db")) "hashed: not even the host travels as a key")))

(deftest a-connection-failure-is-retried-only-when-the-policy-says-so
  (spi/reset-guards!)
  (let [calls (atom 0)
        ctx {:resolver-id "r" :source-type "sql"}
        ds (reify Object)
        attempts (fn [policy]
                   (reset! calls 0)
                   (try
                     (spi/with-retry-sleep (fn [_] nil)
                       (spi/guarded-call!
                        {:fingerprint "sql-test" :policy policy :ctx ctx}
                        (fn [_] (swap! calls inc) (spi/fail! :connection ctx))))
                     (catch clojure.lang.ExceptionInfo _ nil))
                   @calls)]
    (is (= 1 (attempts (exec/effective-policy {}))) "retry is opt-in")
    (is (= 3 (attempts (exec/effective-policy
                        {:resilience {:max_retries 2 :retry_on #{:connection}}}))))
    (testing "a timeout is never retried: it is not a transient category"
      (reset! calls 0)
      (try
        (spi/guarded-call! {:fingerprint "sql-test-2"
                            :policy (exec/effective-policy
                                     {:resilience {:max_retries 2 :retry_on #{:connection}}})
                            :ctx ctx}
                           (fn [_] (swap! calls inc) (spi/fail! :timeout ctx)))
        (catch clojure.lang.ExceptionInfo _ nil))
      (is (= 1 @calls)))
    (is (some? ds))))

(deftest a-bulkhead-refuses-rather-than-queueing-forever
  (spi/reset-guards!)
  (let [ctx {:resolver-id "r" :source-type "sql"}
        policy (exec/effective-policy {:resilience {:bulkhead {:concurrency 1 :max_wait_ms 5}}})
        gate (promise)
        held (future (spi/guarded-call! {:fingerprint "sql-bh" :policy policy :ctx ctx}
                                        (fn [_] @gate :done)))]
    (Thread/sleep 50)
    (let [e (try (spi/guarded-call! {:fingerprint "sql-bh" :policy policy :ctx ctx}
                                    (fn [_] :never))
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :bulkhead_rejected (spi/error-category e))))
    (deliver gate true)
    (is (= :done @held))))

(deftest an-open-breaker-fails-fast-without-touching-the-driver
  (spi/reset-guards!)
  (let [ctx {:resolver-id "r" :source-type "sql"}
        policy (exec/effective-policy
                {:resilience {:circuit_breaker {:failure_ratio [4 50] :reset_ms 60000}}})
        calls (atom 0)
        run! (fn [] (try (spi/guarded-call! {:fingerprint "sql-cb" :policy policy :ctx ctx}
                                            (fn [_] (swap! calls inc) (spi/fail! :connection ctx)))
                         (catch clojure.lang.ExceptionInfo e e)))]
    (dotimes [_ 4] (run!))
    (is (= 4 @calls))
    (is (= :circuit_open (spi/error-category (run!))))
    (is (= 4 @calls) "no fifth statement reached the driver")))

;; ---------------------------------------------------------------------------
;; D6.3 / doc 107 §107.7: the query deadline narrows a statement's timeout too
;; ---------------------------------------------------------------------------

(deftest a-statement-never-gets-more-than-the-budget-has-left
  (let [src {:resilience {:timeout_ms 30000}}]
    (testing "with budget to spare the source's own timeout stands"
      (binding [spi/*request-trace* {:deadline-at (+ (System/currentTimeMillis) 600000)}]
        (is (= 30 (exec/timeout-s src)))))
    (testing "a 1500 ms budget cuts a 30 s statement down, it does not add to it"
      (binding [spi/*request-trace* {:deadline-at (+ (System/currentTimeMillis) 1500)}]
        (is (= 2 (exec/timeout-s src)) "ceil(1500ms) in JDBC's whole seconds")))
    (testing "seconds are JDBC's granularity, so the floor is 1 -- never 0, which means no limit"
      (binding [spi/*request-trace* {:deadline-at (+ (System/currentTimeMillis) 10)}]
        (is (= 1 (exec/timeout-s src)))))
    (testing "with no deadline at all it is simply the source's timeout"
      (is (= 30 (exec/timeout-s src))))))

(deftest a-spent-budget-starts-no-statement
  (spi/reset-guards!)
  (let [ctx {:resolver-id "r" :source-type "sql"}
        calls (atom 0)]
    (binding [spi/*request-trace* {:deadline-at (- (System/currentTimeMillis) 1)}]
      (let [e (try (spi/guarded-call!
                    {:fingerprint "sql-deadline" :policy (exec/effective-policy {}) :ctx ctx
                     :deadline-at (spi/deadline-at)}
                    (fn [_] (swap! calls inc) :sent))
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :cancelled (spi/error-category e)))
        (is (zero? @calls)
            "nothing was sent: its result could only come back after the caller gave up")))))
