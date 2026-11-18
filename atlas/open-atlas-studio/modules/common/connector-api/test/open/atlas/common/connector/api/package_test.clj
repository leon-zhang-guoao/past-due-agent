(ns open.atlas.common.connector.api.package-test
  "doc 104 §8.17.7 A: which files an api source makes this connector open."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.api]))

(deftest tls-keystore-and-truststore-are-the-declared-files
  (is (= ["studio/nifi-client.p12" "studio/nifi-truststore.jks"]
         (spi/declared-files {:type "api"
                              :base_url "https://h:9443/nifi-api"
                              :tls {:keystore "studio/nifi-client.p12"
                                    :keystore_pass "${NIFI_CLIENT_STORE_PASS}"
                                    :truststore "studio/nifi-truststore.jks"}})))
  (testing "keystore alone is the common shape"
    (is (= ["studio/nifi-client.p12"]
           (spi/declared-files {:type "api" :tls {:keystore "studio/nifi-client.p12"}}))))
  (testing "no :tls block → no file"
    (is (= [] (spi/declared-files {:type "api" :base_url "https://h/api"})))
    (is (= [] (spi/declared-files {:type "api" :tls {:keystore "  "}})))))

(deftest contract-version-is-api-v1
  (is (= "v1" (spi/contract-version "api"))))
