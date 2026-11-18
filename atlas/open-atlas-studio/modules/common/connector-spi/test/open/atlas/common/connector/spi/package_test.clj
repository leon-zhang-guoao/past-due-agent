(ns open.atlas.common.connector.spi.package-test
  "doc 104 §8.17.7 A: what a package declares it needs, derived at the seam.

   The two real connectors are tested in their own modules (this one must not
   see them — the SPI is the seam, not an assembly point). Here the dispatch
   itself is under test, with two locally registered types."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]))

(defmethod spi/declared-files "spi-test-files" [cfg] (vec (:files cfg)))
(defmethod spi/contract-version "spi-test-files" [_] "v9")

(deftest declared-files-defaults-to-empty
  (testing "a source type that opens no file answers [], not a refusal"
    (is (= [] (spi/declared-files {:type "spi-test-no-files"})))
    (is (= [] (spi/declared-files {:type "sql"})))))

(deftest declared-files-dispatches-on-normalized-type
  (is (= ["a.db"] (spi/declared-files {:type " SPI-Test-Files " :files ["a.db"]}))))

(deftest contract-version-is-nil-for-unknown-types
  (testing "unknown type and incompatible version are different diagnoses"
    (is (= "v9" (spi/contract-version "spi-test-files")))
    (is (nil? (spi/contract-version "no-such-connector")))))

(deftest dialect-version-is-a-single-constant
  (is (= "v1" dialect/format-version)))

(deftest var-names-reads-nested-values-only
  (is (= ["A" "B"] (spi/var-names "${A}/x/${B}")))
  (is (nil? (spi/var-names 42))))

(def ^:private model
  {:sources {"one" {:type "spi-test-files"
                    :files ["studio/one.p12" "data/one.db"]
                    :jdbc_url "jdbc:x://${HOST}/db?user=${DB_USER}"
                    :tls {:keystore_pass "${STORE_PASS}"
                          :headers [{:value "${DB_USER}"}]}}
             "two" {:type "spi-test-no-files"
                    :endpoint "https://${HOST}/api"}}})

(deftest package-requirements-walks-every-string-in-every-source
  (testing ":vars is sorted, distinct, and found at any nesting depth —
            the credential store's three top-level keys are not the key face"
    (is (= ["DB_USER" "HOST" "STORE_PASS"] (:vars (spi/package-requirements model)))))
  (testing ":files is declared-files across sources, sorted and distinct"
    (is (= ["data/one.db" "studio/one.p12"] (:files (spi/package-requirements model)))))
  (testing "no sources → empty lists, not nil"
    (is (= {:vars [] :files []} (spi/package-requirements {})))))

(deftest package-compat-names-the-versions-of-the-types-present
  (is (= {:dialect "v1" :connectors {"spi-test-files" "v9" "spi-test-no-files" nil}}
         (spi/package-compat model)))
  (testing "an unknown type is listed with a nil version, not dropped"
    (is (contains? (:connectors (spi/package-compat model)) "spi-test-no-files"))))
