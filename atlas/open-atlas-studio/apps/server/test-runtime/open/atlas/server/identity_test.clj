(ns open.atlas.server.identity-test
  "8.12 P2's exit conditions, one deftest each:

   - a bootstrapped instance still logs in after a restart;
   - an instance with no operator refuses rather than admits;
   - no password, and no password hash, ever leaves this module;
   - and, from doc 8.16, the single sign-on half: an account with no password,
     the link made once by address and matched by principal thereafter, and the
     migration of a database written before any of that existed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [open.atlas.runtime.store :as store]
            [open.atlas.server.identity :as identity]))

(defn- temp-root []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "atlas-identity-" (System/nanoTime)))]
    (.mkdirs dir)
    dir))

(defn- with-instance
  "Open an instance, run f, always release the lease — a leaked lease would
   make the NEXT test's open! fail for a reason that has nothing to do with it."
  [root f]
  (let [{:keys [instance findings]} (store/open! {:instance-id "identity-test" :root root})]
    (assert (empty? findings) (pr-str findings))
    (try (f instance) (finally (store/close! instance)))))

(def ^:private password "a-long-enough-password")

(deftest an-account-outlives-the-process-that-created-it
  (testing "accounts are on disk; a restart is a new lease over the same file"
    (let [root (temp-root)]
      (with-instance root
        (fn [instance]
          (let [db (identity/open! instance)]
            (is (false? (identity/bootstrapped? db)))
            (is (empty? (:findings (identity/create-operator!
                                    db {:username "admin" :password password})))))))
      ;; Everything above is gone: the lease, the datasource, the sessions.
      (identity/sign-out-all!)
      (with-instance root
        (fn [instance]
          (let [db (identity/open! instance)
                r  (identity/authenticate db {:username "admin" :password password})]
            (is (identity/bootstrapped? db))
            (is (empty? (:findings r)))
            (is (some? (get-in r [:session :token])))
            (is (= "admin" (get-in r [:operator :username])))
            (testing "the session resolves back to the same immutable principal"
              (let [op (identity/session-operator (get-in r [:session :token]))]
                (is (= (get-in r [:operator :principal]) (:principal op)))
                (is (= "open-atlas-local" (get-in op [:principal :issuer])))))))))))

(deftest an-instance-with-no-operator-is-closed-not-open
  (testing "D5.7: there is no 'nobody is configured, so let anyone in' branch"
    (with-instance (temp-root)
      (fn [instance]
        (let [db (identity/open! instance)
              r  (identity/authenticate db {:username "admin" :password password})]
          (is (false? (identity/bootstrapped? db)))
          (is (= [:identity.auth/not-bootstrapped] (mapv :code (:findings r))))
          (is (nil? (:session r))))))))

(deftest one-refusal-for-every-way-a-login-can-fail
  (testing "unknown user, wrong password and disabled account are indistinguishable"
    (with-instance (temp-root)
      (fn [instance]
        (let [db (identity/open! instance)]
          (identity/create-operator! db {:username "admin" :password password})
          (identity/create-operator! db {:username "retired" :password password})
          (identity/set-disabled! db "retired" true)
          (let [answers (for [creds [{:username "nobody"  :password password}
                                     {:username "admin"   :password "wrong-but-long-enough"}
                                     {:username "retired" :password password}]]
                          (identity/authenticate db creds))]
            (is (every? #(= [:identity.auth/rejected] (mapv :code (:findings %))) answers))
            (is (= 1 (count (distinct (map #(:message (first (:findings %))) answers))))
                "three causes, one message — otherwise the login form enumerates accounts")
            (is (every? #(nil? (:session %)) answers))))))))

(deftest a-disabled-account-loses-the-session-it-already-holds
  (testing "\"disabled\" must not mean \"cannot log in AGAIN\""
    (with-instance (temp-root)
      (fn [instance]
        (let [db (identity/open! instance)
              _  (identity/create-operator! db {:username "admin" :password password})
              token (get-in (identity/authenticate db {:username "admin" :password password})
                            [:session :token])]
          (is (some? (identity/session-operator token)))
          (identity/set-disabled! db "admin" true)
          (is (nil? (identity/session-operator token))))))))

(deftest neither-the-password-nor-its-hash-ever-leaves-this-module
  (testing "operator maps and findings are safe to log, echo and serialise"
    (with-instance (temp-root)
      (fn [instance]
        (let [db      (identity/open! instance)
              created (identity/create-operator! db {:username "admin" :password password})
              listed  (identity/operators db)
              refused (identity/authenticate db {:username "admin" :password "wrong-one-here"})
              texts   (mapv pr-str [created listed refused
                                    (identity/authenticate db {:username "admin"
                                                               :password password})])]
          (doseq [text texts]
            (is (not (str/includes? text password))
                "a plaintext password reached a value a caller may log")
            (is (not (str/includes? text "pbkdf2-sha256$"))
                "a password hash reached a value a caller may log")))))))

(deftest an-account-is-refused-before-it-is-created-not-after
  (with-instance (temp-root)
    (fn [instance]
      (let [db (identity/open! instance)]
        (is (= [:identity.account/invalid]
               (mapv :code (:findings (identity/create-operator!
                                       db {:username "admin" :password "short"}))))
            "a password floor that only the UI enforces is not enforced")
        (is (= [:identity.account/invalid]
               (mapv :code (:findings (identity/create-operator!
                                       db {:username "has space" :password password})))))
        (identity/create-operator! db {:username "admin" :password password})
        (is (= [:identity.account/exists]
               (mapv :code (:findings (identity/create-operator!
                                       db {:username "ADMIN" :password password}))))
            "usernames match case-insensitively at login, so they must be unique that way")))))

;; --- single sign-on (doc 8.16, apps/server's half) ---------------------------

(deftest an-sso-operator-has-no-password-at-all
  (testing "doc 8.16.4 rule 5: pre-registered by address, no password anywhere"
    (with-instance (temp-root)
      (fn [instance]
        (let [db (identity/open! instance)
              r  (identity/create-operator! db {:username "sso-only"
                                                :sso-email "Person@Example.COM"})]
          (is (empty? (:findings r)) (pr-str (:findings r)))
          (testing "the address is stored lowercased, as the claim will assert it"
            (is (= "person@example.com" (get-in r [:operator :sso-email]))))
          (is (false? (get-in r [:operator :has-password?])))
          (is (false? (get-in r [:operator :sso-linked?]))
              "pre-registered is not linked; the link is made at first sign-in")
          (testing "and password login answers exactly what a wrong password does"
            (let [no-password (identity/authenticate db {:username "sso-only" :password ""})
                  guessed     (identity/authenticate db {:username "sso-only"
                                                         :password "a-long-enough-password"})]
              (is (= [:identity.auth/rejected] (mapv :code (:findings no-password))))
              (is (= [:identity.auth/rejected] (mapv :code (:findings guessed))))
              (is (= (:message (first (:findings guessed)))
                     (:message (first (:findings
                                       (identity/authenticate
                                        db {:username "nobody" :password "wrong-but-long"})))))
                  "otherwise the login form says which accounts are SSO-only"))))))))

(deftest an-account-needs-a-password-an-sso-email-or-both
  (with-instance (temp-root)
    (fn [instance]
      (let [db (identity/open! instance)]
        (is (= [:identity.account/credentials-required]
               (mapv :code (:findings (identity/create-operator! db {:username "empty"})))))
        (is (= [:identity.account/invalid]
               (mapv :code (:findings (identity/create-operator!
                                       db {:username "bad" :sso-email "not-an-address"}))))
            "a value the identity provider could never assert is refused before it is stored")
        (testing "both is allowed — the account can then use either door"
          (let [r (identity/create-operator! db {:username "both" :password password
                                                 :sso-email "both@example.com"})]
            (is (empty? (:findings r)))
            (is (true? (get-in r [:operator :has-password?])))
            (is (empty? (:findings (identity/authenticate db {:username "both"
                                                              :password password}))))))))))

(deftest an-sso-email-is-unique-the-way-a-username-is
  (with-instance (temp-root)
    (fn [instance]
      (let [db (identity/open! instance)]
        (identity/create-operator! db {:username "first" :sso-email "person@example.com"})
        (is (= [:identity.account/exists]
               (mapv :code (:findings (identity/create-operator!
                                       db {:username "second"
                                           :sso-email "PERSON@EXAMPLE.COM"}))))
            "the claim arrives lowercased, so two rows differing only in case are one account")
        (testing "and rows with no address at all do not collide with each other"
          (is (empty? (:findings (identity/create-operator! db {:username "pw-1"
                                                                :password password}))))
          (is (empty? (:findings (identity/create-operator! db {:username "pw-2"
                                                                :password password})))))))))

(deftest a-link-is-made-once-and-then-matched-by-principal
  (testing "doc 8.16.4 rules 4.1 and 4.3"
    (with-instance (temp-root)
      (fn [instance]
        (let [db (identity/open! instance)
              op (:operator (identity/create-operator! db {:username "sso-only"
                                                           :sso-email "person@example.com"}))]
          (is (nil? (identity/operator-by-principal db "https://idp.example" "subject-1")))
          (is (= (:id op) (:id (identity/operator-by-sso-email db "Person@Example.com"))))

          (let [bound (identity/bind-sso! db (:id op) "https://idp.example" "subject-1")]
            (is (empty? (:findings bound)))
            (is (true? (get-in bound [:operator :sso-linked?])))
            (is (= (:id op) (:id (identity/operator-by-principal
                                  db "https://idp.example" "subject-1")))))

          (testing "a different subject at the same issuer is a different person"
            (is (nil? (identity/operator-by-principal db "https://idp.example" "subject-2")))
            (is (nil? (identity/operator-by-principal db "https://other.example" "subject-1"))))

          (testing "and the link is not silently overwritten"
            (is (= [:identity.sso/link-conflict]
                   (mapv :code (:findings (identity/bind-sso! db (:id op)
                                                              "https://idp.example" "subject-9")))))
            (let [other (:operator (identity/create-operator!
                                    db {:username "other" :sso-email "other@example.com"}))]
              (is (= [:identity.sso/link-conflict]
                     (mapv :code (:findings (identity/bind-sso! db (:id other)
                                                                "https://idp.example"
                                                                "subject-1"))))
                  "two operators on one identity provider account is what the unique index forbids"))))))))

(deftest an-administrator-may-give-an-sso-operator-a-password
  (testing "doc 8.16.4 rule 5: afterwards both ways in work"
    (with-instance (temp-root)
      (fn [instance]
        (let [db (identity/open! instance)]
          (identity/create-operator! db {:username "sso-only" :sso-email "person@example.com"})
          (is (empty? (:findings (identity/set-password! db {:username "sso-only"
                                                             :password password}))))
          (let [r (identity/authenticate db {:username "sso-only" :password password})]
            (is (empty? (:findings r)))
            (is (some? (get-in r [:session :token]))))
          (testing "and the SSO pre-registration is still there"
            (is (= "person@example.com"
                   (:sso-email (identity/operator-by-sso-email db "person@example.com"))))))))))

(deftest a-database-written-before-sso-is-migrated-not-abandoned
  (testing "SQLite cannot drop NOT NULL, so the table is rebuilt — once"
    (let [root (temp-root)]
      (with-instance root
        (fn [instance]
          ;; The pre-8.16 schema, verbatim, written before `open!` ever runs.
          (let [ds (jdbc/get-datasource
                    {:dbtype "sqlite"
                     :dbname (.getPath (identity/db-file instance))})]
            (.mkdirs (.getParentFile (identity/db-file instance)))
            (jdbc/execute! ds ["CREATE TABLE operator (
                                  id            TEXT PRIMARY KEY,
                                  username      TEXT NOT NULL UNIQUE,
                                  display_name  TEXT,
                                  password_hash TEXT NOT NULL,
                                  disabled      INTEGER NOT NULL DEFAULT 0,
                                  created_at    TEXT NOT NULL,
                                  updated_at    TEXT NOT NULL)"])
            (jdbc/execute! ds ["CREATE UNIQUE INDEX operator_username_ci
                                ON operator (LOWER(username))"])
            (jdbc/execute! ds ["INSERT INTO operator VALUES
                                ('old-id', 'legacy', 'Legacy', 'pbkdf2-sha256$x', 0,
                                 '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')"]))

          (let [db (identity/open! instance)]
            (testing "the account survives the rebuild"
              (let [legacy (first (filter #(= "legacy" (:username %)) (identity/operators db)))]
                (is (= "old-id" (:id legacy)))
                (is (= "Legacy" (:display-name legacy)))
                (is (true? (:has-password? legacy)))
                (is (false? (:sso-linked? legacy)))))
            (testing "and an SSO-only account can now be created, which is the point"
              (is (empty? (:findings (identity/create-operator!
                                      db {:username "sso-only"
                                          :sso-email "person@example.com"})))))
            (testing "a second open! is a no-op, not a second rebuild"
              (let [db2 (identity/open! instance)]
                (is (= 2 (count (identity/operators db2))))))))))))
