(ns open.atlas.runtime.obs.log-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [open.atlas.runtime.obs.impl.log :as obs-log]
            [open.atlas.runtime.obs.impl.trace :as trace]
))

(defn- temp-log []
  (str (System/getProperty "java.io.tmpdir") "/obs-log-test-" (System/nanoTime) ".jsonl"))

(deftest writes-structured-json-line
  (let [path (temp-log)]
    (obs-log/configure! {:log-file path})
    (obs-log/log-event! :eql-query {:source :api :status :ok :latency-ms 12})
    (let [parsed (json/parse-string (first (str/split-lines (slurp path))) true)]
      (is (= "eql-query" (:event parsed)))
      (is (= "api" (:source parsed)))
      (is (= 12 (:latency-ms parsed)))
      (is (string? (:ts parsed)))
      (is (= "info" (:level parsed))))))

(deftest merges-bound-request-id
  (let [path (temp-log)]
    (obs-log/configure! {:log-file path})
    (binding [trace/*request-id* "abc12345"]
      (obs-log/log-event! :eql-query {:source :api :status :ok}))
    (let [parsed (json/parse-string (first (str/split-lines (slurp path))) true)]
      (is (= "abc12345" (:request-id parsed))))))

(deftest error-status-logs-at-error-level
  (let [path (temp-log)]
    (obs-log/configure! {:log-file path})
    (obs-log/log-event! :eql-query {:source :api :status :error :error "boom"})
    (let [parsed (json/parse-string (first (str/split-lines (slurp path))) true)]
      (is (= "error" (:level parsed))))))

(deftest rolls-when-over-max-size
  (let [path (temp-log)]
    (obs-log/configure! {:log-file path :max-bytes 200})
    (dotimes [_ 20]
      (obs-log/log-event! :eql-query {:source :api :status :ok :latency-ms 1}))
    (is (.exists (java.io.File. (str path ".1"))) "rolled file should exist")
    (is (pos? (.length (java.io.File. (str path ".1")))) "rolled file keeps data")
    (is (< (.length (java.io.File. ^String path)) 1000))))

(deftest non-serializable-values-are-stringified
  (let [path (temp-log)]
    (obs-log/configure! {:log-file path})
    (obs-log/log-event! :eql-query {:source :api :status :ok
                                    :error (RuntimeException. "boom")})
    (let [parsed (json/parse-string (first (clojure.string/split-lines (slurp path))) true)]
      (is (= "eql-query" (:event parsed)))
      (is (string? (:error parsed))))))

(deftest log-event-is-fail-open
  ;; nil event map and weird inputs must not throw
  (is (nil? (do (obs-log/log-event! :eql-query nil) nil))))
