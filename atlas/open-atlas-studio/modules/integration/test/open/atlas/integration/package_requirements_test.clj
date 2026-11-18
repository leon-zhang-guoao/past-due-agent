(ns open.atlas.integration.package-requirements-test
  "doc 104 §8.17.7 A on a model that mixes both connectors.

   Why here: `package-requirements` dispatches per source type, and only the
   assembly point sees sql and api on one classpath. Each connector's own
   module can show its `declared-files`; neither can show that a package
   declares the union — which is exactly what an installer is handed."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.api]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql]))

(def ^:private model
  {:sources
   {"dvd-db"   {:type "sql"
                :jdbc_url "jdbc:sqlite:data/open-atlas-demo.db"}
    "cis-db"   {:type "sql"
                :jdbc_url "jdbc:postgresql://${PG_HOST}/cis"
                :username "${PG_USER}"
                :password "${PG_PASSWORD}"}
    "nifi"     {:type "api"
                :base_url "https://${NIFI_HOST}:9443/nifi-api"
                :tls {:keystore "studio/nifi-client.p12"
                      :keystore_pass "${NIFI_CLIENT_STORE_PASS}"
                      :truststore "studio/nifi-truststore.jks"}}}})

(deftest package-requirements-unions-both-connectors
  (let [{:keys [vars files]} (spi/package-requirements model)]
    (testing "files come from each connector's own knowledge: sqlite's path
              hides in :jdbc_url, api's two live under :tls"
      (is (= ["data/open-atlas-demo.db"
              "studio/nifi-client.p12"
              "studio/nifi-truststore.jks"]
             files))
      (testing "a networked sql source contributes no file"
        (is (not-any? #(re-find #"cis" %) files))))
    (testing "vars are found at any nesting depth — api's credential key is
              nested, which is why the walk is over values and not a key list"
      (is (= ["NIFI_CLIENT_STORE_PASS" "NIFI_HOST" "PG_HOST" "PG_PASSWORD" "PG_USER"]
             vars)))))

(deftest package-compat-names-both-contracts
  (is (= {:dialect "v1" :connectors {"api" "v1" "sql" "v1"}}
         (spi/package-compat model))))
