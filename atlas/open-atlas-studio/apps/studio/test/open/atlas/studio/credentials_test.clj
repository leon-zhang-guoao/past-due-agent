(ns open.atlas.studio.credentials-test
  "The invariant this ns exists for: a modeller may type a password, and that
   password must be unable to reach the model — not stripped from it, never in
   it. Everything else here is in service of that one line."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.studio.credentials :as credentials]))

(def ^:dynamic *home* nil)

(use-fixtures :each
  (fn [f]
    (let [dir (str (java.nio.file.Files/createTempDirectory
                    "atlas-cred" (into-array java.nio.file.attribute.FileAttribute [])))]
      (binding [*home* dir] (f)))))

(deftest the-variable-name-is-what-production-will-configure
  ;; Derived, not asked for: someone typing a password should not also have to
  ;; invent a shell identifier. It is shown to them, never guessed at twice.
  (is (= "SALES_DB_PASSWORD" (credentials/variable-name "sales-db" :password)))
  (is (= "DVD_RENTAL_USER" (credentials/variable-name "dvd-rental" :username)))
  (is (= "MY_DB_URL" (credentials/variable-name "my.db" :jdbc_url)))
  (is (= "${SALES_DB_USER}" (credentials/reference "sales-db" :username))))

(deftest manually-managed-variables-can-be-created-before-any-reference
  (let [prepared (credentials/prepare-variable-change *home* {:name "MY_TOKEN" :value "first-value"})]
    (is (= {"MY_TOKEN" "first-value"} (:values prepared)))
    (is (empty? (credentials/held-variables *home*)) "preparing performs no write")
    (credentials/put-vars! *home* (:values prepared))
    (is (= {} (:values (credentials/prepare-variable-change *home*
                         {:name "MY_TOKEN" :previous-name "MY_TOKEN" :value ""}))))
    (is (= {"NEW_TOKEN" "first-value"}
           (:values (credentials/prepare-variable-change *home*
                      {:name "NEW_TOKEN" :previous-name "MY_TOKEN" :value ""}))))
    (is (thrown? clojure.lang.ExceptionInfo
          (credentials/prepare-variable-change *home* {:name "MY_TOKEN" :value "overwrite"})))
    (is (thrown? clojure.lang.ExceptionInfo
          (credentials/prepare-variable-change *home* {:name "INVALID-NAME" :value "x"})))
    (is (thrown? clojure.lang.ExceptionInfo
          (credentials/prepare-variable-change *home* {:name "NEW_TOKEN" :value ""})))))

(deftest renaming-a-variable-updates-nested-and-embedded-references-only
  (let [entry {:type "nifi"
               :tls {:keystore_pass "${NIFI_CLIENT_STORE_PASS}"
                     :truststore_pass "${NIFI_CLIENT_STORE_PASS}"}
               :headers {"Authorization" "Bearer ${NIFI_CLIENT_STORE_PASS}"}
               :label "NIFI_CLIENT_STORE_PASS"}
        renamed (credentials/rename-variable-reference entry "NIFI_CLIENT_STORE_PASS" "NIFI_PASS")]
    (is (= "${NIFI_PASS}" (get-in renamed [:tls :keystore_pass])))
    (is (= "${NIFI_PASS}" (get-in renamed [:tls :truststore_pass])))
    (is (= "Bearer ${NIFI_PASS}" (get-in renamed [:headers "Authorization"])))
    (is (= (:label entry) (:label renamed)))))

(deftest a-stored-value-resolves-a-reference
  (credentials/put! *home* "sales-db" {:password "hunter2" :username "reader"})
  (let [entry {:type "sql" :jdbc_url "jdbc:postgresql://h/db"
               :username "${SALES_DB_USER}" :password "${SALES_DB_PASSWORD}"}
        out   (credentials/resolve-config *home* "sales-db" entry (constantly nil))]
    (is (= "reader" (:username out)))
    (is (= "hunter2" (:password out)))
    (is (= "jdbc:postgresql://h/db" (:jdbc_url out)) "a literal is left exactly as it is")))

(deftest the-store-outranks-the-environment
  ;; Reversed by doc 106 §106.14-12: renewing a short-lived token is writing
  ;; the store, and a stale value left in `<home>/.env` must not shadow it.
  (credentials/put! *home* "sales-db" {:password "renewed-just-now"})
  (let [out (credentials/resolve-config *home* "sales-db"
                                        {:password "${SALES_DB_PASSWORD}"}
                                        {"SALES_DB_PASSWORD" "stale-in-dot-env"})]
    (is (= "renewed-just-now" (:password out))))
  (testing "and the environment still answers what the store does not hold"
    (is (= "from-the-vm"
           (:password (credentials/resolve-config *home* "other-db"
                                                  {:password "${OTHER_DB_PASSWORD}"}
                                                  {"OTHER_DB_PASSWORD" "from-the-vm"}))))))

(deftest a-value-is-stored-and-read-back-by-variable-name
  ;; §106.14-12: the store's keys ARE the variable names, so a value typed for
  ;; one source resolves anywhere that variable is written.
  (credentials/put-vars! *home* {"NIFI_TOKEN" "jwt-value"})
  (is (= ["NIFI_TOKEN"] (credentials/held-variables *home*)))
  (is (= {:type "api" :auth {:type "api_key" :value "jwt-value"}}
         (credentials/resolve-config *home* "nifi"
                                     {:type "api" :auth {:type "api_key"
                                                         :value "${NIFI_TOKEN}"}}
                                     (constantly nil)))
      "a NESTED credential key, which the old three-key store could not hold"))

(deftest an-embedded-variable-resolves-too
  (credentials/put-vars! *home* {"NIFI_HOST" "nifi.example"})
  (is (= "https://nifi.example:9443/nifi-api"
         (:base_url (credentials/resolve-config
                     *home* "nifi" {:base_url "https://${NIFI_HOST}:9443/nifi-api"}
                     (constantly nil))))))

(deftest a-store-written-in-the-old-shape-is-read-by-variable-name
  ;; The migration: `{source-id {key value}}` reads as the variable names the
  ;; card had already written into the entry in those values' place.
  (spit (io/file *home* "credentials.edn")
        (pr-str {"sales-db" {:password "hunter2" :username "reader"}}))
  (is (= ["SALES_DB_PASSWORD" "SALES_DB_USER"] (credentials/held-variables *home*)))
  (is (= "hunter2" (:password (credentials/resolve-config
                               *home* "sales-db" {:password "${SALES_DB_PASSWORD}"}
                               (constantly nil))))))

(deftest a-variable-nobody-can-answer-is-a-finding-not-a-nil
  ;; The v11 rebuild's `Cannot read the array length because "password" is
  ;; null`: the api connector reached its TLS keystore with a nil password.
  (let [entry {:type "api" :tls {:keystore "client.p12"
                                 :keystore_pass "${NIFI_STORE_PASS}"}}
        fs (credentials/missing-findings *home* "nifi" entry (constantly nil))]
    (is (= [:credential/missing] (mapv :code fs)))
    (is (= [[:source "nifi" :tls :keystore_pass]] (mapv :path fs)))
    (is (= ["NIFI_STORE_PASS"] (mapv :expected fs)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing credential variable"
                          (credentials/resolve-config! *home* "nifi" entry (constantly nil))))
    (is (= :credential/missing
           (:code (ex-data (try (credentials/resolve-config! *home* "nifi" entry
                                                             (constantly nil))
                                (catch clojure.lang.ExceptionInfo e e))))))))

(deftest the-status-table-is-the-same-for-sql-and-api
  ;; §106.14-12: 「sql 与 api 同一张表」 — one row per `${VAR}`, names and
  ;; paths only, whatever the connector.
  (credentials/put-vars! *home* {"NIFI_TOKEN" "jwt"})
  (let [rows (credentials/variable-status
              *home* {:type "api" :base_url "https://${NIFI_HOST}/x"
                      :auth {:value "${NIFI_TOKEN}"}}
              (constantly nil))]
    (is (= [{:name "NIFI_TOKEN" :path [":auth" ":value"]
             :stored? true :in-env? false :present? true}
            {:name "NIFI_HOST" :path [":base_url"]
             :stored? false :in-env? false :present? false}]
           rows))
    (is (not (str/includes? (pr-str rows) "jwt")) "names, never values")))

(deftest an-unheld-reference-is-left-alone
  ;; It must stay a reference: the connector then reports the variable by name,
  ;; which is the message someone can act on.
  (is (= "${SALES_DB_PASSWORD}"
         (:password (credentials/resolve-config *home* "sales-db"
                                                {:password "${SALES_DB_PASSWORD}"}
                                                (constantly nil))))))

(deftest a-blank-value-is-removed-not-stored-empty
  ;; "no password" and "a password that is the empty string" are different
  ;; answers, and only one of them is ever meant.
  (credentials/put! *home* "sales-db" {:password "hunter2"})
  (credentials/put! *home* "sales-db" {:password "   "})
  (is (= [] (credentials/holds *home* "sales-db"))))

(deftest holds-says-names-never-values
  (credentials/put! *home* "sales-db" {:username "reader" :password "hunter2"})
  (is (= ["password" "username"] (credentials/holds *home* "sales-db")))
  (is (not (str/includes? (pr-str (credentials/holds *home* "sales-db")) "hunter2"))))

(deftest forgetting-a-source-forgets-its-credentials
  (credentials/put! *home* "sales-db" {:password "hunter2"})
  (credentials/forget! *home* "sales-db")
  (is (= [] (credentials/holds *home* "sales-db")))
  (is (not (str/includes? (slurp (io/file *home* "credentials.edn")) "hunter2"))))

(deftest the-store-is-owner-only-from-the-moment-it-exists
  ;; A file created under a permissive umask and fixed "later" was readable in
  ;; between, and that window is the whole exposure.
  (credentials/put! *home* "sales-db" {:password "hunter2"})
  (let [f (io/file *home* "credentials.edn")]
    (is (.canRead f))
    (let [perms (java.nio.file.Files/getPosixFilePermissions
                 (.toPath f) (into-array java.nio.file.LinkOption []))]
      (is (empty? (filter #(str/starts-with? (str %) "GROUP") perms)))
      (is (empty? (filter #(str/starts-with? (str %) "OTHERS") perms))))))

(deftest a-corrupt-store-reads-as-empty-rather-than-stopping-studio
  (spit (io/file *home* "credentials.edn") "{not edn at all")
  (is (= {} (credentials/read-store *home*)))
  (is (= "${SALES_DB_PASSWORD}"
         (:password (credentials/resolve-config *home* "sales-db"
                                                {:password "${SALES_DB_PASSWORD}"}
                                                (constantly nil))))
      "the connection fails by name, which is actionable; Studio still starts"))
