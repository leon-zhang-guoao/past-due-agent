(ns open.atlas.common.connector.spi.failure-test
  "故障契约：principles「source 失败与日志不携带业务数据」。
   ex-data 键面**恰好**三个键——多一个键就是多一条泄漏通道，所以这里断言
   的是相等而不是包含。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.spi :as spi]))

(def ^:private ctx {:resolver-id "demo_vendor_by_id" :source-type "sql"})

(deftest ex-data-key-set-is-exactly-the-contract
  (doseq [cat spi/error-categories]
    (let [e (try (spi/fail! cat ctx) (catch clojure.lang.ExceptionInfo e e))]
      (is (= {:error/category cat
              :resolver-id    "demo_vendor_by_id"
              :source-type    "sql"}
             (ex-data e))
          (str cat " 的 ex-data 必须逐键相等，不多不少")))))

(deftest categories-are-the-controlled-vocabulary
  ;; principles pinned the first four; doc 60 E6.3 added five and doc 107
  ;; §107.4 three more (owner 2026-09-18). Asserted as EQUALITY: the set is
  ;; closed, and a category nobody ruled is a category no `:retry_on` check,
  ;; audit field or UI can be written against.
  (is (= #{:timeout :connection :source :mapping
           :unavailable :rate_limited :circuit_open :bulkhead_rejected :cancelled
           :unauthorized :not-found :input-rejected}
         spi/error-categories))
  (testing "retry may only be declared on transient categories (D6.4)"
    (is (= #{:rate_limited :unavailable :connection} spi/transient-categories))
    (is (every? spi/error-categories spi/transient-categories)))
  (testing "protected statuses keep the platform's classification (§107.5)"
    (is (= #{401 403 408 429 502 503 504} spi/protected-statuses))))

(deftest retry-after-ms-is-the-one-upstream-number-allowed-in-ex-data
  ;; doc 107 §107.4. A `:rate_limited` that cannot say "wait 2 s" forces the
  ;; retry loop to guess against a source that already told us the answer.
  (let [e (try (spi/fail! :rate_limited ctx "HTTP 429" {:retry-after-ms 2000})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (spi/failure? e))
    (is (= 2000 (spi/retry-after-ms e)))
    (is (= {:error/category :rate_limited :resolver-id "demo_vendor_by_id"
            :source-type "sql" :retry-after-ms 2000}
           (ex-data e))
        "still closed: the three contract keys plus that one duration"))
  (testing "no other key rides in on it"
    (is (not (spi/failure? (ex-info "x" {:error/category :source :resolver-id "a"
                                         :source-type "sql" :retry-after-ms 1
                                         :url "https://host/x"})))))
  (testing "absent when the upstream did not say"
    (is (nil? (spi/retry-after-ms
               (try (spi/fail! :rate_limited ctx) (catch Exception e e)))))))

(deftest unknown-category-is-a-programming-error
  (is (thrown? AssertionError (spi/fail! :whatever ctx))))

(deftest no-cause-chain-to-walk-back-to-the-driver-message
  (testing "刻意不挂 cause：挂了下游就能 (.getCause e) 取回驱动原文（含语句与主机）"
    (let [e (try (spi/fail! :source ctx "statement rejected")
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (nil? (.getCause e))))))

(deftest failure-predicate-rejects-foreign-ex-info
  (is (not (spi/failure? (ex-info "x" {:error/category :source}))))
  (is (not (spi/failure? (ex-info "x" {:error/category :source :resolver-id "a"
                                       :source-type "sql" :query "SELECT 1"}))))
  (is (not (spi/failure? (RuntimeException. "x"))))
  (is (nil? (spi/error-category (ex-info "x" {})))))

(deftest redact-degrades-anything-that-could-carry-data
  (is (= "string(8 chars)" (spi/redact "SELECT 1")))
  (is (= "map(2 keys)" (spi/redact {:a 1 :b 2})))
  (is (= "coll(3 items)" (spi/redact [1 2 3])))
  (is (= 42 (spi/redact 42)))
  (testing "凭证与 URL 都是字符串，因此都只剩长度"
    (is (str/starts-with? (spi/redact "jdbc:sqlite:/tmp/x?password=hunter2") "string("))
    (is (not (str/includes? (spi/redact "hunter2") "hunter2")))))
