(ns open.atlas.common.connector.spi.dispatch-test
  "分派点与缺省方法。最要紧的一条：没有 compile-batch-source 的连接器上跑
   batch 是**配置错误**，不是静默退回逐行（principles）。"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.connector.spi :as spi]))

;; 一个只实现 compile-source 的假连接器——正是「声明了 :batch 却没有 batch 能力」
;; 这个配置错误的现场。
(defmethod spi/compile-source "fake-nobatch" [_ _ _] (fn [_ _] nil))

(defmethod spi/compile-source "fake-offset" [_ _ _] (fn [_ _] nil))
(defmethod spi/supports-offset? "fake-offset" [_] true)

(use-fixtures :once
  (fn [f] (f)
    (remove-method spi/compile-source "fake-nobatch")
    (remove-method spi/compile-source "fake-offset")
    (remove-method spi/supports-offset? "fake-offset")))

(deftest type-is-normalized-before-dispatch
  (is (= "sql" (spi/normalize-type "SQL")))
  (is (= "sql" (spi/normalize-type " sql ")))
  (is (= "sql" (spi/normalize-type :sql)))
  (is (nil? (spi/normalize-type nil)))
  (is (nil? (spi/normalize-type "   ")))
  (testing "归一后分派：大小写不同不会变成第二个 source type（那会绕过 support policy）"
    (is (fn? (spi/compile-source {:type "FAKE-NOBATCH"} nil {:id "r"})))))

(defn- caught [f] (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest batch-on-a-connector-without-batch-is-a-configuration-error
  (let [e (caught #(spi/compile-batch-source {:type "fake-nobatch"} nil {:id "r1"}))]
    (is (some? e) "绝不许静默返回 nil 或退回逐行")
    (is (spi/failure? e))
    (is (= "fake-nobatch" (:source-type (ex-data e))))
    (is (= "r1" (:resolver-id (ex-data e))))))

(deftest unknown-type-defaults-say-so
  (testing "compile 面：抛结构化故障"
    (let [e (caught #(spi/compile-source {:type "nope"} nil {:id "r"}))]
      (is (spi/failure? e))
      (is (= :connection (spi/error-category e))))
    (let [e (caught #(spi/compile-mutation {:type "nope"} nil {:id "m"}))]
      (is (spi/failure? e))))
  (testing "validate 面：产 finding，不抛"
    (doseq [f [spi/validate-source spi/validate-mutation]]
      (let [{:keys [findings]} (f {:type "nope"})]
        (is (spi/errors? findings))
        (is (every? spi/finding? findings))
        (is (= :connector.source/unknown-type (:code (first findings)))))))
  (testing "health-check 面：:down，且不带驱动原文"
    (let [r (spi/health-check {:type "nope"} nil)]
      (is (= :down (:status r)))
      (is (= :connection (:error/category r))))))

(deftest supports-batch?-reports-capability-without-executing
  (is (false? (spi/supports-batch? "fake-nobatch")))
  (is (false? (spi/supports-batch? nil)))
  (is (contains? (spi/registered-types) "fake-nobatch")))

(deftest finding-shape-is-closed
  (is (spi/finding? {:code :connector.source/missing-key :severity :error
                     :path [:source :query] :message "m"}))
  (is (not (spi/finding? {:code :nope/x :severity :error :path [] :message "m"}))
      "未注册的 code 是实现 bug")
  (is (not (spi/finding? {:code :connector.source/missing-key :severity :error
                          :path [] :message "m" :query "SELECT 1"}))
      "键面封闭：finding-v1 §2 之外的键一律不合法"))

(deftest offset-capability-is-declared-and-defaults-to-no
  ;; `:offset` owns no dispatch point, so it cannot be detected the way
  ;; `supports-batch?` detects batch — a connector declares it. Silence means
  ;; NO, because a connector that ignores `:offset` answers the unskipped rows
  ;; and the caller reasons about a page that never existed (8.17).
  (testing "a type that never declared it does not get it"
    (is (false? (spi/supports-offset? "fake-nobatch")))
    (is (false? (spi/supports-offset? "nope")))
    (is (false? (spi/supports-offset? nil))))
  (testing "a type that declares it does"
    (is (true? (spi/supports-offset? "fake-offset"))))
  (testing "the declaration is looked up on the normalized type"
    (is (true? (spi/supports-offset? " FAKE-OFFSET ")))))
