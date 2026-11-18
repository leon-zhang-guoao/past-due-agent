(ns open.atlas.studio.oidc-test
  "Studio's half of enterprise SSO (doc
   [8.16](../../../../../../docs/design/101-sso-oidc.md) §8.16.7): one real
   round trip against the same fake identity provider the shared module's own
   suite uses, one test per sub-rule of §8.16.4 rule 4, the refusal codes of
   §8.16.6 with their statuses, and the `pr-str` sweep for credential material.

   The fake IdP is `open.atlas.common.oidc.fake-idp`, on the test classpath by
   `:extra-paths` rather than copied: a second fake is a fake that drifts from
   the one the protocol is actually proved against."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.oidc :as oidc]
            [open.atlas.common.oidc.fake-idp :as fake]
            [open.atlas.studio.auth :as auth]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.http :as http]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.pool :as pool])
  (:import [com.sun.net.httpserver HttpExchange]
           [java.net URI URLDecoder]
           [java.nio.charset StandardCharsets]))

(def ^:private client-secret "studio-client-secret-never-shown")
(def ^:private client-id "studio-client")
(def ^:private kid "studio-key-1")

(def ^:dynamic *conn* nil)
(def ^:dynamic *idp* nil)
(def ^:dynamic *ctl* nil)
(def ^:dynamic *kp* nil)

(defn- env [base extra]
  (merge {"OIDC_DISCOVERY_URL" (str base "/.well-known/openid-configuration")
          "OIDC_CLIENT_ID"     client-id
          "OIDC_CLIENT_SECRET" client-secret
          "OIDC_REDIRECT_URI"  "http://127.0.0.1:9631/api/auth/oidc/callback"}
         extra))

(defn- handle
  "An open handle for this test's fake IdP, with `extra` environment applied."
  ([] (handle {}))
  ([extra]
   (oidc/open (:config (oidc/config (env (:base-url *idp*) extra))))))

(use-fixtures
 :each
 (fn [run]
   (let [cfg  (pool/config {:backend :mem :id (str (random-uuid))})
         conn (-> (pool/open-pool! cfg) graph/install! identity/install!)
         kp   (fake/rsa-keypair)
         ctl  (atom {:jwks [(fake/rsa-jwk kp kid)]})
         idp  (fake/start! ctl)]
     (identity/reset-sessions!)
     (try
       (binding [*conn* conn *idp* idp *ctl* ctl *kp* kp] (run))
       (finally
         (fake/stop! idp)
         (pool/close-pool! conn)
         (pool/delete-pool! cfg))))))

;; --- the transport, stubbed -------------------------------------------------

(defn- exchange
  "An exchange that answers only what the two handlers ask of it: the request
   URI (for the query string) and its headers."
  ([uri] (exchange uri nil))
  ([uri browser-state] (exchange uri browser-state {}))
  ([uri browser-state headers]
   (proxy [HttpExchange] []
     (getRequestURI [] (URI/create uri))
     (getRequestMethod [] "GET")
     (getRequestHeaders []
       (let [h (cond-> (com.sun.net.httpserver.Headers.)
                 browser-state (doto (.set "Cookie" (str "open_atlas_studio_oidc_state=" browser-state))))]
         (doseq [[k v] headers] (.set h k v))
         h)))))

(defn- replies
  "Run `f` with the reply writers captured. → `[{:status :body :headers}]`."
  [f]
  (let [out (atom [])]
    (with-redefs [http/reply! (fn [_ status body _ct & [headers]]
                                (swap! out conj {:status status :body body
                                                 :headers (or headers {})}))
                  http/reply-edn! (fn [_ status body & [headers]]
                                    (swap! out conj {:status status :body body
                                                     :headers (or headers {})}))]
      (f))
    @out))

(defn- query-of [url]
  (into {} (for [pair (str/split (or (.getQuery (URI/create url)) "") #"&")
                 :when (seq pair)
                 :let [[k v] (str/split pair #"=" 2)]]
             [k (URLDecoder/decode (or v "") StandardCharsets/UTF_8)])))

(defn- now-seconds [] (quot (System/currentTimeMillis) 1000))

;; --- driving one round trip -------------------------------------------------

(defn- begin!
  "`GET /api/auth/oidc` → the single reply it wrote."
  ([h] (begin! h "/dashboard"))
  ([h return-to]
   (first (replies #(auth/handle-oidc-begin!
                     h (exchange (str "http://studio/api/auth/oidc?return_to="
                                      (java.net.URLEncoder/encode (str return-to)
                                                                  StandardCharsets/UTF_8)))
                     (fn [_]))))))

(defn- callback!
  "`GET /api/auth/oidc/callback?state=…&code=…` → `{:reply … :audit …}`."
  [h state & {:keys [code browser-state] :or {code "the-authorization-code" browser-state state}}]
  (let [audit (atom [])
        reply (first (replies
                      #(auth/handle-oidc-callback!
                        *conn* h
                        (exchange (str "http://studio/api/auth/oidc/callback?state="
                                       (java.net.URLEncoder/encode (str state) StandardCharsets/UTF_8)
                                       "&code=" code) browser-state)
                        (fn [reason] (swap! audit conj [:denied reason]))
                        (fn [principal] (swap! audit conj [:ok principal])))))]
    {:reply reply :audit @audit}))

(defn- sign-in!
  "The whole browser round trip for one identity: begin, mint an id_token for
   the nonce that begin asked for, then callback."
  [h {:keys [email name subject] :or {subject "idp-subject-1"}}]
  (let [b     (begin! h)
        url   (get-in b [:headers "Location"])
        q     (query-of url)
        token (fake/sign-jwt *kp* kid "RS256"
                             {"iss" (:issuer @*ctl*) "sub" subject "aud" client-id
                              "exp" (+ (now-seconds) 300) "nonce" (get q "nonce")
                              "email" email "name" (or name "A Person")})]
    (swap! *ctl* assoc :token-status 200
           :token-body {"id_token" token "access_token" "at-1"})
    (assoc (callback! h (get q "state")) :begin b :id-token token)))

(defn- error-code [reply] (get-in reply [:body :error :code]))

;; --- §8.16.5: what the login face advertises --------------------------------

(deftest auth-state-advertises-the-provider
  (let [h (handle {"OIDC_PROVIDER_NAME" "Company Login" "OIDC_PROVIDER_ID" "entra"})
        state (:body (first (replies #(auth/handle-auth-state! *conn* (exchange "http://studio/api/auth/state") h))))]
    (is (= {:id "entra" :name "Company Login"} (:oidc state)))
    (is (false? (:authenticated? state)))))

(deftest without-oidc-nothing-changes
  (testing "no OIDC_* configured is not a finding — SSO is simply off (§8.16.3)"
    (is (nil? (oidc/config {}))))
  (let [state (:body (first (replies #(auth/handle-auth-state! *conn* (exchange "http://studio/api/auth/state") nil))))]
    (is (nil? (:oidc state)))
    (is (false? (:authenticated? state)))
    (is (true? (:needs-setup? state)) "and every other key answers what it answered before"))
  (testing "the two routes answer 404 auth/oidc-disabled (§8.16.6)"
    (let [b (first (replies #(auth/handle-oidc-begin! nil (exchange "http://studio/api/auth/oidc") (fn [_]))))
          c (first (replies #(auth/handle-oidc-callback! *conn* nil (exchange "http://studio/api/auth/oidc/callback")
                                                         (fn [_]) (fn [_]))))]
      (is (= 404 (:status b)))
      (is (= 404 (:status c)))
      (is (= :auth/oidc-disabled (error-code b) (error-code c))))))

;; --- §8.16.4 rule 1: the redirect -------------------------------------------

(deftest begin-redirects-with-pkce-s256
  (let [b (begin! (handle))
        q (query-of (get-in b [:headers "Location"]))]
    (is (= 302 (:status b)))
    (is (= "S256" (get q "code_challenge_method")) "PKCE S256 is not optional (§8.16.4 rule 1)")
    (is (seq (get q "code_challenge")))
    (is (seq (get q "state")))
    (is (seq (get q "nonce")))
    (is (= "code" (get q "response_type")))
    (is (= client-id (get q "client_id")))))

;; --- §8.16.4 rule 4: linking and provisioning -------------------------------

(deftest a-new-identity-is-provisioned-with-a-personal-workspace
  (identity/bootstrap-admin! *conn* {:email "admin@example.com" :name "Admin"
                                     :password "a-safe-password" :workspace "demo"})
  (let [h (handle)
        {:keys [reply audit]} (sign-in! h {:email "Ada@Example.com" :name "Ada"})
        user (identity/user-by-email (pool/db *conn*) "ada@example.com")]
    (is (= 302 (:status reply)))
    (is (= "/dashboard" (get-in reply [:headers "Location"])) "back to where the browser started")
    (is (str/includes? (get-in reply [:headers "Set-Cookie"]) "open_atlas_studio_session="))
    (is (str/includes? (get-in reply [:headers "Set-Cookie"]) "HttpOnly"))
    (is (some? user))
    (is (= :member (:system-role user)) "studio auto-provisions members (§8.16.5)")
    (is (nil? (:password-hash user)) "an SSO account has no password (§8.16.4 rule 5)")
    (is (= :owner (graph/workspace-role (pool/db *conn*) "ada@example.com"
                                        (:principal (identity/public-user user))))
        "and its own workspace, owned by the LOCAL principal")
    (testing "the success is audited with the identity provider principal (rule 7)"
      (is (= [[:ok {:issuer (:issuer @*ctl*) :subject "idp-subject-1"}]] audit)))
    (testing "the session cookie names that user"
      (let [token (second (re-find #"open_atlas_studio_session=([^;]+)"
                                   (get-in reply [:headers "Set-Cookie"])))]
        (is (= (:id user) (:id (identity/session-user token))))))))

(deftest the-same-principal-signs-into-the-same-account
  (let [h (handle)
        _ (sign-in! h {:email "ada@example.com" :name "Ada"})
        first-id (:id (identity/user-by-email (pool/db *conn*) "ada@example.com"))
        ;; A changed email on the second visit: the principal is the key, the
        ;; address is an attribute (§8.16.4 rule 3).
        {:keys [reply]} (sign-in! h {:email "ada.new@example.com" :name "Ada"})]
    (is (= 302 (:status reply)))
    (is (= 1 (count (identity/users (pool/db *conn*)))) "one account, not two")
    (is (= first-id (:id (identity/user-by-principal (pool/db *conn*)
                                                     (:issuer @*ctl*) "idp-subject-1"))))))

(deftest an-address-with-a-password-is-not-taken-over
  ;; §8.16.4 rule 4.4 — this is where we deliberately do not copy OMA.
  (identity/bootstrap-admin! *conn* {:email "admin@example.com" :name "Admin"
                                     :password "a-safe-password" :workspace "demo"})
  (let [h (handle)
        {:keys [reply audit]} (sign-in! h {:email "admin@example.com" :name "Admin"})]
    (is (= 403 (:status reply)))
    (is (= :auth/identity-not-linked (error-code reply)))
    (is (= [[:denied :auth/identity-not-linked]] audit))
    (is (nil? (:sso-subject (identity/user-by-email (pool/db *conn*) "admin@example.com")))
        "and nothing was written onto the password account")))

(deftest a-pre-registered-sso-account-is-bound-once
  ;; §8.16.4 rule 4.3 — the one time an address is matched.
  (identity/bootstrap-admin! *conn* {:email "admin@example.com" :name "Admin"
                                     :password "a-safe-password" :workspace "demo"})
  (let [created (identity/create-local-user! *conn* {:email "grace@example.com"
                                                     :name "Grace" :sso true})
        h (handle)
        {:keys [reply]} (sign-in! h {:email "grace@example.com" :name "Grace"})
        bound (identity/user-by-email (pool/db *conn*) "grace@example.com")]
    (is (= :ok (:status created)))
    (is (= 302 (:status reply)))
    (is (= (:id (:user created)) (:id bound)) "bound, not a second account")
    (is (= "idp-subject-1" (:sso-subject bound)))
    (is (= 2 (count (identity/users (pool/db *conn*)))))))

;; --- §8.16.4 rule 4.4's remedy: an administrator pre-registers (§8.16.9 item 3)
;; Mirrors Workbench's sso-preregistration tests so the three apps behave alike.

(defn- password-user! [email]
  (identity/bootstrap-admin! *conn* {:email "admin@example.com" :name "Admin"
                                     :password "a-safe-password" :workspace "demo"})
  (:user (identity/create-local-user! *conn* {:email email :name "Jie"
                                              :password "another-safe-password"})))

(deftest a-password-account-no-administrator-pre-registered-is-refused
  (password-user! "jie.gao@example.com")
  (let [{:keys [reply]} (sign-in! (handle) {:email "jie.gao@example.com"})]
    (is (= 403 (:status reply)))
    (is (= :auth/identity-not-linked (error-code reply)))))

(deftest a-pre-registered-password-account-is-linked-once
  (let [jie (password-user! "jie.gao@example.com")
        h (handle)
        db #(pool/db *conn*)]
    (testing "set: normalised and shown, the link state without issuer or subject"
      (let [r (identity/set-sso-email! *conn* (:id jie) " Jie.Gao@Corp.example.com ")]
        (is (= "jie.gao@corp.example.com" (get-in r [:user :sso-email])))
        (is (false? (get-in r [:user :sso-linked?])))
        (is (not-any? #(contains? (:user r) %) [:sso-issuer :sso-subject]))))
    (testing "the next sign-in asserting that address binds and consumes it"
      (is (= 302 (:status (:reply (sign-in! h {:email "jie.gao@corp.example.com"}))))))
    (let [bound (identity/user-by-id (db) (:id jie))]
      (is (= "idp-subject-1" (:sso-subject bound)))
      (is (nil? (:sso-email bound)))
      (is (nil? (identity/user-by-sso-email (db) "jie.gao@corp.example.com"))))
    (testing "the password keeps working (hybrid)"
      (is (some? (identity/authenticate-password (db) "jie.gao@example.com"
                                                 "another-safe-password"))))
    (testing "a second subject asserting the same email is not bound"
      (let [{:keys [reply]} (sign-in! h {:email "jie.gao@example.com" :subject "someone-else"})]
        (is (= 403 (:status reply)))
        (is (= :auth/identity-not-linked (error-code reply)))))
    (testing "a linked account cannot be pre-registered again"
      (is (= :auth/identity-already-linked
             (get-in (identity/set-sso-email! *conn* (:id jie) "other@corp.example.com")
                     [:error :code]))))
    (testing "an email change at the IdP does not move the link"
      (is (= 302 (:status (:reply (sign-in! h {:email "renamed@corp.example.com"})))))
      (is (= 2 (count (identity/users (db))))))))

(deftest setting-an-sso-email-is-refused-with-the-shared-codes
  (let [jie (password-user! "jie.gao@example.com")]
    (is (= :auth/invalid-email
           (get-in (identity/set-sso-email! *conn* (:id jie) "not-an-address") [:error :code])))
    (is (= :auth/email-in-use
           (get-in (identity/set-sso-email! *conn* (:id jie) "ADMIN@example.com") [:error :code]))
        "another account's sign-in address")
    (is (= :ok (:status (identity/set-sso-email! *conn* (:id jie) "jie@corp.example.com"))))
    (testing "clearing"
      (is (nil? (get-in (identity/set-sso-email! *conn* (:id jie) "") [:user :sso-email]))))))

(deftest a-domain-outside-the-allow-list-is-refused
  (let [h (handle {"OIDC_ALLOWED_EMAIL_DOMAINS" "corp.example"})
        {:keys [reply audit]} (sign-in! h {:email "ada@example.com" :name "Ada"})]
    (is (= 403 (:status reply)))
    (is (= :auth/oidc-domain-not-allowed (error-code reply)))
    (is (= [[:denied :auth/oidc-domain-not-allowed]] audit))
    (is (empty? (identity/users (pool/db *conn*))) "no account was created")))

(deftest a-disabled-linked-account-gets-the-password-refusal
  ;; §8.16.4 rule 4.1: the same refusal as a wrong password, deliberately.
  (let [h (handle)
        _ (sign-in! h {:email "ada@example.com" :name "Ada"})
        user (identity/user-by-email (pool/db *conn*) "ada@example.com")
        _ (d/transact *conn* [{:identity.user/id (:id user)
                                          :identity.user/disabled true}])
        {:keys [reply]} (sign-in! h {:email "ada@example.com" :name "Ada"})]
    (is (= 401 (:status reply)))
    (is (= :auth/invalid-credentials (error-code reply)))))

;; --- §8.16.6 refusals on the protocol ---------------------------------------

(deftest a-replayed-state-is-refused
  (let [h (handle)
        b (begin! h)
        state (get (query-of (get-in b [:headers "Location"])) "state")
        nonce (get (query-of (get-in b [:headers "Location"])) "nonce")
        token (fake/sign-jwt *kp* kid "RS256"
                             {"iss" (:issuer @*ctl*) "sub" "idp-subject-1" "aud" client-id
                              "exp" (+ (now-seconds) 300) "nonce" nonce
                              "email" "ada@example.com" "name" "Ada"})
        _ (swap! *ctl* assoc :token-status 200 :token-body {"id_token" token})
        first-try (callback! h state)
        replay    (callback! h state)]
    (is (= 302 (:status (:reply first-try))))
    (is (= 401 (:status (:reply replay))))
    (is (= :auth/expired-oidc-transaction (error-code (:reply replay))))
    (is (= [[:denied :auth/expired-oidc-transaction]] (:audit replay)))))

(deftest a-callback-without-a-state-is-refused
  (let [h (handle)
        reply (first (replies #(auth/handle-oidc-callback!
                                *conn* h (exchange "http://studio/api/auth/oidc/callback?code=x")
                                (fn [_]) (fn [_]))))]
    (is (= 401 (:status reply)))
    (is (contains? #{:auth/invalid-oidc-response :auth/expired-oidc-transaction}
                   (error-code reply)))))

(deftest a-callback-must-belong-to-the-browser-that-started-login
  (let [h (handle)
        begin (begin! h)
        state (get (query-of (get-in begin [:headers "Location"])) "state")
        cookie (get-in begin [:headers "Set-Cookie"])]
    (is (str/starts-with? cookie (str "open_atlas_studio_oidc_state=" state ";")))
    (is (str/includes? cookie "HttpOnly; SameSite=Lax"))
    (doseq [browser-state [nil "another-browser"]]
      (let [result (callback! h state :browser-state browser-state)]
        (is (= 401 (get-in result [:reply :status])))
        (is (= :auth/invalid-oidc-response (error-code (:reply result))))
        (is (contains? @(:transactions h) state))))))

;; --- §8.16.7 the sweep ------------------------------------------------------

(deftest no-credential-material-leaves-this-face
  (let [h (handle)
        {:keys [reply id-token begin]} (sign-in! h {:email "ada@example.com" :name "Ada"})
        printed (str (pr-str reply) (pr-str begin))]
    (doseq [secret [client-secret id-token "the-authorization-code"]]
      (is (not (str/includes? printed secret))
          "no client secret, id_token or authorization code in anything returned"))
    (testing "and none in a refusal either"
      (let [refused (:reply (callback! h "no-such-state"))]
        (is (not (str/includes? (pr-str refused) client-secret)))
        (is (not (str/includes? (pr-str refused) "the-authorization-code")))))))

;; --- one origin for the whole round trip ------------------------------------

(deftest begin-moves-to-the-redirect-uri-origin-first
  (testing "reached as localhost while the redirect URI names 127.0.0.1: the
            state cookie would land on a host the callback never visits"
    (let [r (first (replies #(auth/handle-oidc-begin!
                              (handle) (exchange "http://localhost:9631/api/auth/oidc?return_to=%2F" nil
                                                 {"Host" "localhost:9631"})
                              (fn [_]))))]
      (is (= 302 (:status r)))
      (is (= "http://127.0.0.1:9631/api/auth/oidc?return_to=%2F" (get-in r [:headers "Location"])))
      (is (nil? (get-in r [:headers "Set-Cookie"])) "no state is minted on the wrong host")))
  (testing "on the redirect URI's own origin begin proceeds to the provider"
    (let [r (first (replies #(auth/handle-oidc-begin!
                              (handle) (exchange "http://127.0.0.1:9631/api/auth/oidc" nil
                                                 {"Host" "127.0.0.1:9631"})
                              (fn [_]))))]
      (is (= 302 (:status r)))
      (is (str/starts-with? (get-in r [:headers "Set-Cookie"]) "open_atlas_studio_oidc_state=")))))

(deftest a-browser-refusal-is-a-page-with-a-way-back
  (let [r (first (replies #(auth/handle-oidc-callback!
                            *conn* (handle)
                            (exchange "http://127.0.0.1:9631/api/auth/oidc/callback?state=s&code=c" nil
                                      {"Accept" "text/html,application/xhtml+xml"})
                            (fn [_]) (fn [_]))))]
    (is (= 401 (:status r)))
    (is (str/includes? (:body r) "Sign-in failed"))
    (is (str/includes? (:body r) "href=\"/\""))))
