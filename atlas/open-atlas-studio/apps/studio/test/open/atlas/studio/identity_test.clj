(ns open.atlas.studio.identity-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.pool :as pool]))

(def ^:dynamic *conn*)

(use-fixtures
 :each
 (fn [run]
   (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
         conn (-> (pool/open-pool! cfg) graph/install! identity/install!)]
     (binding [*conn* conn] (run))
     (pool/close-pool! conn)
     (pool/delete-pool! cfg))))

(deftest bootstrap-is-one-time-and-owns-workspace
  (let [result (identity/bootstrap-admin!
                *conn* {:email " Admin@Example.com " :name "Admin"
                        :password "a-safe-password" :workspace "demo"})
        user (:user result)]
    (is (= :ok (:status result)))
    (is (= "admin@example.com" (:email user)))
    (is (= :admin (:system-role user)))
    (is (= :owner (graph/workspace-role (pool/db *conn*) "demo" (:principal user))))
    (is (= :bootstrap-closed
           (get-in (identity/bootstrap-admin!
                    *conn* {:email "other@example.com" :name "Other"
                            :password "another-password" :workspace "other"})
                   [:error :code])))))

(deftest local-login-uses-immutable-principal
  (let [created (:user (identity/bootstrap-admin!
                        *conn* {:email "admin@example.com" :name "Admin"
                                :password "a-safe-password" :workspace "demo"}))
        logged-in (identity/authenticate-password
                   (pool/db *conn*) "ADMIN@example.com" "a-safe-password")]
    (is (= (:principal created) (:principal logged-in)))
    (is (nil? (identity/authenticate-password
               (pool/db *conn*) "admin@example.com" "wrong-password")))))

(deftest a-user-can-change-only-their-own-local-password
  (let [user (:user (identity/bootstrap-admin!
                     *conn* {:email "admin@example.com" :name "Admin"
                             :password "a-safe-password" :workspace "demo"}))
        other (:user (identity/create-local-user!
                      *conn* {:email "other@example.com" :name "Other"
                              :password "another-password"}))
        session (identity/create-session! user)]
    (testing "the current password and minimum length are both enforced"
      (is (= :invalid-current-password
             (get-in (identity/change-password! *conn* user "wrong-password"
                                                 "a-new-safe-password")
                     [:error :code])))
      (is (= :weak-password
             (get-in (identity/change-password! *conn* user "a-safe-password" "too-short")
                     [:error :code]))))
    (testing "success replaces the hash and revokes existing sessions"
      (is (= :ok (:status (identity/change-password!
                           *conn* user "a-safe-password" "a-new-safe-password"))))
      (is (nil? (identity/authenticate-password (pool/db *conn*)
                                                "admin@example.com" "a-safe-password")))
      (is (= (:id user)
             (:id (identity/authenticate-password (pool/db *conn*)
                                                  "admin@example.com" "a-new-safe-password"))))
      (is (nil? (identity/session-user (:token session)))))
    (testing "the request identity cannot be used to change someone else"
      (is (= (:id other)
             (:id (identity/authenticate-password (pool/db *conn*)
                                                  "other@example.com" "another-password")))))))

(deftest a-pure-sso-user-is-not-offered-a-local-password-conversion
  (identity/bootstrap-admin! *conn* {:email "admin@example.com" :name "Admin"
                                     :password "a-safe-password" :workspace "demo"})
  (let [sso-user (:user (identity/create-local-user!
                         *conn* {:email "sso@example.com" :name "SSO" :sso true}))]
    (is (= :local-password-unavailable
           (get-in (identity/change-password! *conn* sso-user "" "a-new-safe-password")
                   [:error :code])))))

(deftest an-admin-reset-replaces-a-local-password-and-revokes-target-sessions
  (let [admin (:user (identity/bootstrap-admin!
                      *conn* {:email "admin@example.com" :name "Admin"
                              :password "a-safe-password" :workspace "demo"}))
        target (:user (identity/create-local-user!
                       *conn* {:email "target@example.com" :name "Target"
                               :password "another-password"}))
        session (identity/create-session! target)]
    (is (= :ok (:status (identity/reset-password!
                         *conn* (:id target) "replacement-password"))))
    (is (nil? (identity/authenticate-password (pool/db *conn*)
                                              "target@example.com" "another-password")))
    (is (= (:id target)
           (:id (identity/authenticate-password (pool/db *conn*)
                                                "target@example.com" "replacement-password"))))
    (is (nil? (identity/session-user (:token session))))
    (is (= (:id admin)
           (:id (identity/authenticate-password (pool/db *conn*)
                                                "admin@example.com" "a-safe-password"))))))

(deftest removing-a-user-disables-access-but-preserves-identity-and-attribution
  (let [admin (:user (identity/bootstrap-admin!
                      *conn* {:email "admin@example.com" :name "Admin"
                              :password "a-safe-password" :workspace "demo"}))
        target (:user (identity/create-local-user!
                       *conn* {:email "target@example.com" :name "Target"
                               :password "another-password"}))
        session (identity/create-session! target)
        result (identity/remove-user! *conn* admin (:id target))
        stored (identity/user-by-id (pool/db *conn*) (:id target))]
    (is (= :ok (:status result)))
    (is (true? (:disabled? stored)))
    (is (= (:principal target) (:principal (identity/public-user stored))))
    (is (= :owner (graph/workspace-role (pool/db *conn*) "target@example.com"
                                        (:principal target)))
        "workspace membership remains as durable ownership attribution")
    (is (nil? (identity/authenticate-password (pool/db *conn*)
                                                "target@example.com" "another-password")))
    (is (nil? (identity/session-user (:token session))))))

(deftest removal-protects-self-and-the-last-enabled-admin
  (let [admin (:user (identity/bootstrap-admin!
                      *conn* {:email "admin@example.com" :name "Admin"
                              :password "a-safe-password" :workspace "demo"}))
        second (:user (identity/create-local-user!
                       *conn* {:email "second@example.com" :name "Second"
                               :password "another-password" :role :admin}))]
    (is (= :cannot-remove-self
           (get-in (identity/remove-user! *conn* admin (:id admin)) [:error :code])))
    (is (= :ok (:status (identity/remove-user! *conn* admin (:id second)))))
    (is (= :last-admin-required
           (get-in (identity/remove-user! *conn* second (:id admin)) [:error :code])))))

(deftest studio-admin-provisioning-creates-an-independent-personal-workspace
  (identity/bootstrap-admin! *conn* {:email "admin@example.com" :name "Admin"
                                     :password "a-safe-password" :workspace "demo"})
  (let [created (identity/create-local-user!
                 *conn* {:email "Ada.Lovelace@example.com" :name "Ada"
                          :password "a-safe-password"})
        user (:user created)]
    (is (= :ok (:status created)))
    (is (= :member (:system-role user)))
    (is (= "ada.lovelace@example.com" (:workspace created))
        "the personal workspace is the lowercased email itself (§1.3b)")
    (is (= :owner (graph/workspace-role (pool/db *conn*) "ada.lovelace@example.com"
                                        (:principal user))))
    (is (= :email-taken
           (get-in (identity/create-local-user!
                    *conn* {:email "ada.lovelace@example.com" :name "Again"
                            :password "a-safe-password"})
                   [:error :code])))))

(deftest offline-provisioning-can-add-an-admin-without-changing-existing-identities
  (let [founder (:user (identity/bootstrap-admin!
                        *conn* {:email "founder@example.com" :name "Founder"
                                :password "a-safe-password" :workspace "demo"}))
        created (identity/create-local-user!
                 *conn* {:email "Victor@example.com" :name "Victor"
                         :password "another-safe-password" :role :admin})
        admin (:user created)]
    (is (= :ok (:status created)))
    (is (= :admin (:system-role admin)))
    (is (= :owner (graph/workspace-role (pool/db *conn*) "victor@example.com"
                                        (:principal admin))))
    (is (= (:id founder)
           (:id (identity/user-by-email (pool/db *conn*) "founder@example.com"))))
    (is (= :email-taken
           (get-in (identity/create-local-user!
                    *conn* {:email "victor@example.com" :name "Duplicate"
                            :password "another-safe-password" :role :admin})
                   [:error :code])))))

(deftest promotion-preserves-password-principal-and-workspace-membership
  (identity/bootstrap-admin! *conn* {:email "founder@example.com" :name "Founder"
                                     :password "a-safe-password" :workspace "demo"})
  (let [member (:user (identity/create-local-user!
                       *conn* {:email "jack@example.com" :name "Jack"
                               :password "another-safe-password"}))
        before-role (graph/workspace-role (pool/db *conn*) "jack@example.com" (:principal member))
        promoted (identity/promote-admin! *conn* "JACK@example.com")]
    (is (true? (:changed? promoted)))
    (is (= :admin (get-in promoted [:user :system-role])))
    (is (= (:principal member) (get-in promoted [:user :principal])))
    (is (= before-role (graph/workspace-role (pool/db *conn*) "jack@example.com" (:principal member))))
    (is (= (:id member)
           (:id (identity/authenticate-password (pool/db *conn*) "jack@example.com"
                                                "another-safe-password"))))
    (is (false? (:changed? (identity/promote-admin! *conn* "jack@example.com"))))))

;; ---------------------------------------------------------------------------
;; SSO accounts (doc [8.16](../../../../../../docs/design/101-sso-oidc.md)
;; §8.16.5). The protocol is tested in `oidc-test`; what follows is only the
;; store's half — an account may have no password, a link is unique, and a
;; link is what a login is found by.
;; ---------------------------------------------------------------------------

(deftest an-sso-account-has-no-password-and-cannot-be-logged-into-with-one
  (identity/bootstrap-admin! *conn* {:email "admin@example.com" :name "Admin"
                                     :password "a-safe-password" :workspace "demo"})
  (let [created (identity/create-local-user! *conn* {:email "Grace@Example.com"
                                                     :name "Grace" :sso true})
        user (:user created)]
    (is (= :ok (:status created)))
    (is (= :member (:system-role user)))
    (is (false? (:has-password? user)))
    (is (false? (:sso-linked? user)) "pre-registered is not yet linked")
    (is (= :owner (graph/workspace-role (pool/db *conn*) "grace@example.com" (:principal user))))
    (testing "the hash never leaves this ns"
      (is (not (contains? user :password-hash))))
    (testing "and no password opens it — the same nil as a wrong one (§8.16.4 rule 5)"
      (is (nil? (identity/authenticate-password (pool/db *conn*) "grace@example.com" "")))
      (is (nil? (identity/authenticate-password (pool/db *conn*) "grace@example.com"
                                                "a-safe-password"))))))

(deftest a-password-is-still-required-when-the-account-is-not-sso
  (is (= :weak-password
         (get-in (identity/create-local-user! *conn* {:email "a@example.com" :name "A"
                                                      :password "short"})
                 [:error :code]))))

(deftest binding-links-a-principal-and-is-how-the-account-is-found
  (identity/bootstrap-admin! *conn* {:email "admin@example.com" :name "Admin"
                                     :password "a-safe-password" :workspace "demo"})
  (let [created (:user (identity/create-local-user! *conn* {:email "grace@example.com"
                                                            :name "Grace" :sso true}))
        bound (identity/bind-sso! *conn* (:id created) "https://idp.example" "sub-1")]
    (is (= :ok (:status bound)))
    (is (true? (:sso-linked? (:user bound))))
    (is (= (:id created) (:id (identity/user-by-principal (pool/db *conn*)
                                                          "https://idp.example" "sub-1"))))
    (is (nil? (identity/user-by-principal (pool/db *conn*) "https://idp.example" "sub-2"))
        "a different subject is a different person, not a near miss")
    (testing "a second link on one account is refused, not silently moved"
      (is (= :already-linked
             (get-in (identity/bind-sso! *conn* (:id created) "https://idp.example" "sub-2")
                     [:error :code]))))))

(deftest one-account-per-identity-provider-principal
  (identity/bootstrap-admin! *conn* {:email "admin@example.com" :name "Admin"
                                     :password "a-safe-password" :workspace "demo"})
  (let [first-user (:user (identity/create-sso-user!
                           *conn* {:email "ada@example.com" :name "Ada"
                                   :issuer "https://idp.example" :subject "sub-1"}))
        other (:user (identity/create-local-user! *conn* {:email "grace@example.com"
                                                          :name "Grace" :sso true}))]
    (is (true? (:sso-linked? first-user)))
    (is (= :principal-taken
           (get-in (identity/create-sso-user!
                    *conn* {:email "other@example.com" :name "Other"
                            :issuer "https://idp.example" :subject "sub-1"})
                   [:error :code])))
    (is (= :principal-taken
           (get-in (identity/bind-sso! *conn* (:id other) "https://idp.example" "sub-1")
                   [:error :code])))))

(deftest founding-account-on-another-workspace-still-owns-the-seed
  ;; 0.2 acceptance defect 5. The seed's objects are stamped with
  ;; `identity/seed-workspace` (D3.22) and its graphs registered under it
  ;; (D3.19), and Studio has no route to create or join a workspace after
  ;; bootstrap — so a founder who named another workspace left the bundled demo
  ;; on the machine with nobody able to reach it. The objects do not move; the
  ;; founder is made an owner of the workspace they already belong to.
  (graph/register-workspace!
   *conn* {:name identity/seed-workspace
           :members [{:principal {:issuer "studio" :subject "local-builder"}
                      :role :owner}]})
  (let [result (identity/bootstrap-admin!
                *conn* {:email "ops@example.com" :name "Ops"
                        :password "a-safe-password" :workspace "acceptance"})
        principal (:principal (:user result))
        db (pool/db *conn*)]
    (is (= :ok (:status result)))
    (is (= "acceptance" (:workspace result)) "the founder still works where they said")
    (is (= :owner (graph/workspace-role db "acceptance" principal)))
    (is (= :owner (graph/workspace-role db identity/seed-workspace principal))
        "and can reach the seed")
    (testing "the local Builder principal is kept, not evicted by the grant"
      (is (= :owner (graph/workspace-role db identity/seed-workspace
                                          {:issuer "studio" :subject "local-builder"}))))))

(deftest founding-account-on-the-seed-workspace-is-unchanged
  (graph/register-workspace!
   *conn* {:name identity/seed-workspace
           :members [{:principal {:issuer "studio" :subject "local-builder"}
                      :role :owner}]})
  (let [result (identity/bootstrap-admin!
                *conn* {:email "ops@example.com" :name "Ops"
                        :password "a-safe-password"})
        db (pool/db *conn*)]
    (is (= identity/seed-workspace (:workspace result)) "the default is the seed's")
    (is (= :owner (graph/workspace-role db identity/seed-workspace
                                        (:principal (:user result)))))
    (testing "on this path the seed member IS dropped, as it always was"
      (is (nil? (graph/workspace-role db identity/seed-workspace
                                      {:issuer "studio" :subject "local-builder"}))))))

(deftest admin-can-maintain-user-profile-and-role
  (let [admin (:user (identity/bootstrap-admin! *conn* {:email "admin@example.com" :name "Admin" :password "a-safe-password" :workspace "demo"}))
        member (:user (identity/create-local-user! *conn* {:email "member@example.com" :name "Member" :password "a-safe-password" :role :member}))
        session (identity/create-session! member)
        updated (identity/update-user! *conn* {:user-id (:id member) :name "Updated" :email "updated@example.com" :role :admin})]
    (is (= :ok (:status updated)))
    (is (= (:id member) (get-in updated [:user :id])))
    (is (= (:default-workspace member) (get-in updated [:user :default-workspace])))
    (is (= :admin (:system-role (identity/session-user (:token session)))))
    (is (= "Updated" (get-in updated [:user :name])))
    (is (= :email-taken (get-in (identity/update-user! *conn* {:user-id (:id member) :name "Changed" :email (:email admin) :role :member}) [:error :code])))
    (is (= :ok (:status (identity/update-user! *conn* {:user-id (:id member) :name "Updated" :email "updated@example.com" :role :member}))))
    (is (= :member (:system-role (identity/session-user (:token session)))))
    (is (= :last-admin-required (get-in (identity/update-user! *conn* {:user-id (:id admin) :name "Admin" :email (:email admin) :role :member}) [:error :code])))
    (is (= :invalid-system-role (get-in (identity/create-local-user! *conn* {:email "bad@example.com" :name "Bad" :password "a-safe-password" :role :owner}) [:error :code])))
    (is (= :admin (get-in (identity/create-local-user! *conn* {:email "another@example.com" :name "Another" :password "a-safe-password" :role :admin}) [:user :system-role])))))

(deftest restoring-user-preserves-access-and-requires-fresh-login
  (let [admin (:user (identity/bootstrap-admin! *conn* {:email "admin@example.com" :name "Admin" :password "a-safe-password" :workspace "demo"}))
        target (:user (identity/create-local-user! *conn* {:email "restore@example.com" :name "Restore" :password "another-password" :role :admin}))
        session (identity/create-session! target)]
    (identity/remove-user! *conn* admin (:id target))
    (is (true? (:changed? (identity/restore-user! *conn* (:id target)))))
    (is (= (:id target) (:id (identity/authenticate-password (pool/db *conn*) "restore@example.com" "another-password"))))
    (is (= :admin (:system-role (identity/user-by-id (pool/db *conn*) (:id target)))))
    (is (= :owner (graph/workspace-role (pool/db *conn*) "restore@example.com" (:principal target))))
    (is (nil? (identity/session-user (:token session))))
    (is (false? (:changed? (identity/restore-user! *conn* (:id target)))))
    (is (= :no-such-user (get-in (identity/restore-user! *conn* "missing") [:error :code])))))
