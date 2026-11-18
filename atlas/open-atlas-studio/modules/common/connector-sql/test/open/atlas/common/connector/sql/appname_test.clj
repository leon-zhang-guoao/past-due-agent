(ns open.atlas.common.connector.sql.appname-test
  "E2 的连接级打标：`application_name`。逐 driver 对账，外加「不认识的 driver
   不猜」这一条——猜错的属性会让建池当场抛，把「没打上标」变成「连不上库」。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql.impl.pool :as pool]))

(deftest each-known-driver-gets-its-own-property
  (testing "postgres"
    (is (= {"ApplicationName" "open-atlas"}
           (:properties (pool/app-name-plan "jdbc:postgresql://h:5432/db")))))
  (testing "sql server"
    (is (= {"applicationName" "open-atlas"}
           (:properties (pool/app-name-plan "jdbc:sqlserver://h:1433;databaseName=db")))))
  (testing "mysql / mariadb 走 connectionAttributes"
    (is (= {"connectionAttributes" "program_name:open-atlas"}
           (:properties (pool/app-name-plan "jdbc:mysql://h:3306/db"))))
    (is (= {"connectionAttributes" "program_name:open-atlas"}
           (:properties (pool/app-name-plan "jdbc:mariadb://h:3306/db"))))))

(deftest an-unknown-driver-is-reported-not-guessed
  (let [{:keys [properties findings]} (pool/app-name-plan "jdbc:sqlite:file:x?mode=memory")]
    (is (nil? properties) "一个属性都不加")
    (is (= 1 (count findings)))
    (let [f (first findings)]
      (is (spi/finding? f) (pr-str f))
      (is (= :connector.source/untagged-driver (:code f)))
      (is (= :info (:severity f)) "连接照开——这不是拒绝"))))

(deftest instance-id-becomes-a-suffix-and-is-whitelisted
  (binding [spi/*instance-id* "rt-7"]
    (is (= {"ApplicationName" "open-atlas-rt-7"}
           (:properties (pool/app-name-plan "jdbc:postgresql://h/db")))))
  (testing "值只留 [A-Za-z0-9-]，它改不了别的连接属性"
    (binding [spi/*instance-id* "a;b=c d"]
      (is (= "open-atlas-abcd" (spi/application-name)))))
  (testing "没绑就没有后缀"
    (is (= "open-atlas" (spi/application-name)))))
