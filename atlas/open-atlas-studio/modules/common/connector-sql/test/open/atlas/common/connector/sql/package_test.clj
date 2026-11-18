(ns open.atlas.common.connector.sql.package-test
  "doc 104 §8.17.7 A: which files a sql source makes this connector open."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql]))

(defn- files [jdbc-url] (spi/declared-files {:type "sql" :jdbc_url jdbc-url}))

(deftest sqlite-file-path-is-answered-as-written
  (testing "relative stays relative and ${VAR} stays unresolved — the package
            block carries names, never resolved values"
    (is (= ["data/open-atlas-demo.db"] (files "jdbc:sqlite:data/open-atlas-demo.db")))
    (is (= ["/srv/atlas/dvd.db"] (files "jdbc:sqlite:/srv/atlas/dvd.db")))
    (is (= ["${DVD_DB}"] (files "jdbc:sqlite:${DVD_DB}")))
    (testing "JDBC options are not part of the file name"
      (is (= ["data/x.db"] (files "jdbc:sqlite:data/x.db?journal_mode=WAL"))))))

(deftest sources-with-no-file-answer-empty
  (testing "in-memory and non-sqlite engines open no file for an installer to check"
    (is (= [] (files "jdbc:sqlite::memory:")))
    (is (= [] (files "jdbc:sqlite:file::memory:?cache=shared")))
    (is (= [] (files "jdbc:sqlite:")))
    (is (= [] (files "jdbc:postgresql://${PG_HOST}:5432/dvd")))
    (is (= [] (spi/declared-files {:type "sql"})))))

(deftest contract-version-is-sql-v1
  (is (= "v1" (spi/contract-version "sql"))))
