(ns open.atlas.common.connector.sql.write-reason-test
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.sql.impl.exec :as exec])
  (:import [java.sql DriverManager]))

(deftest fixed-trigger-reasons-survive-without-leaking-driver-data
  (Class/forName "org.sqlite.JDBC")
  (with-open [c (DriverManager/getConnection "jdbc:sqlite::memory:")
              s (.createStatement c)]
    (.execute s "CREATE TABLE commands(id TEXT)")
    (.execute s "CREATE TRIGGER reject_command BEFORE INSERT ON commands BEGIN SELECT RAISE(ABORT,'unknown_actor'); END")
    (try
      (.execute s "INSERT INTO commands VALUES('private-value')")
      (is false "The trigger must reject the insert")
      (catch Exception e
        (let [reason (#'exec/safe-write-reason e)]
          (is (re-find #"unknown_actor" reason))
          (is (not (re-find #"private-value" reason)))
          (is (= reason (#'exec/safe-write-reason (ex-info "wrapper" {} e)))))))
    (with-open [r (.executeQuery s "SELECT count(*) FROM commands")]
      (.next r)
      (is (= 0 (.getInt r 1)))))
  (is (nil? (#'exec/safe-write-reason (Exception. "secret SQL or URL"))))
  (is (nil? (#'exec/safe-write-reason (Exception. "unknown_actor with private details")))))

(defn- raised [message] (java.sql.SQLException. message "P0001"))

(deftest declared-raise-codes-pass-without-values
  (is (= "SQL statement rejected: open_event_exists."
         (#'exec/safe-write-reason (raised "ERROR: open_event_exists:EVT-42 (status open, opened 2026-10-05 09:00); add an action\n  Where: PL/pgSQL function write_event"))))
  (is (= "SQL statement rejected: invalid_value:status."
         (#'exec/safe-write-reason (ex-info "wrapper" {} (raised "ERROR: invalid_value:status must be open|pending|closed")))))
  (is (= "SQL statement rejected: void_window_expired."
         (#'exec/safe-write-reason (raised "ERROR: void_window_expired"))))
  (testing "a value glued to the code is cut, not forwarded"
    (is (= "SQL statement rejected: unknown_rule." (#'exec/safe-write-reason (raised "ERROR: unknown_rule:r12-secret")))))
  (testing "free text and other SQLSTATEs stay private"
    (is (nil? (#'exec/safe-write-reason (raised "ERROR: Secret Row Value"))))
    (is (nil? (#'exec/safe-write-reason (java.sql.SQLException. "ERROR: open_event_exists" "23505"))))))
