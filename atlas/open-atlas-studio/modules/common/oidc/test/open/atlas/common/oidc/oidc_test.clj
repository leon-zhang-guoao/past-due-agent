(ns open.atlas.common.oidc.oidc-test
  "Doc 8.16 acceptance (§8.16.7): one real round trip against a fake IdP, one
   test per refusal code of §8.16.6, one per sub-rule of §8.16.4 rule 4, and the
   `pr-str` sweep for secrets."
  (:require [cheshire.core]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.oidc :as oidc]
            [open.atlas.common.oidc.fake-idp :as fake])
  (:import [java.net URI URLDecoder]
           [java.nio.charset StandardCharsets]))

(def ^:private client-secret "s3cr3t-not-in-any-finding")
(def ^:private client-id "client-abc")
(def ^:private kid "test-key-1")

(defn- env
  ([base] (env base {}))
  ([base extra]
   (merge {"OIDC_DISCOVERY_URL" (str base "/.well-known/openid-configuration")
           "OIDC_CLIENT_ID"     client-id
           "OIDC_CLIENT_SECRET" client-secret
           "OIDC_REDIRECT_URI"  "https://app.example/callback"}
          extra)))

(defn- query-of [url]
  (into {} (for [pair (str/split (.getQuery (URI/create url)) #"&")
                 :let [[k v] (str/split pair #"=" 2)]]
             [k (URLDecoder/decode (or v "") StandardCharsets/UTF_8)])))

(defn- now-seconds [] (quot (System/currentTimeMillis) 1000))

(defn- codes [result] (set (map :code (:findings result))))

(defn- claims
  "A well-formed id_token payload; `overrides` bends one field at a time."
  [issuer nonce overrides]
  (merge {"iss" issuer "sub" "subject-1" "aud" client-id
          "exp" (+ (now-seconds) 300) "nonce" nonce
          "email" "Person@Example.COM" "name" "A Person"}
         overrides))

(defn- with-idp*
  "Start a fake IdP with an RSA key, open a handle, run `f` with a context map."
  [{:keys [env-extra jwk-fn]} f]
  (let [kp   (fake/rsa-keypair)
        ctl  (atom {:jwks [((or jwk-fn fake/rsa-jwk) kp kid)]})
        idp  (fake/start! ctl)]
    (try
      (let [cfg (:config (oidc/config (env (:base-url idp) (or env-extra {}))))
            h   (oidc/open cfg)]
        (try
          (f {:kp kp :ctl ctl :idp idp :config cfg :handle h
              :issuer (:issuer @ctl)})
          (finally (oidc/close! h))))
      (finally (fake/stop! idp)))))

(defn- run-callback
  "begin → mint an id_token from `claim-overrides` → complete."
  ([ctx] (run-callback ctx {} {}))
  ([ctx claim-overrides opts]
   (let [{:keys [handle kp ctl issuer]} ctx
         {:keys [url state]} (oidc/begin handle (get opts :return-to "/dashboard"))
         nonce (get (query-of url) "nonce")
         tok   (fake/sign-jwt kp (get opts :kid kid) (get opts :alg "RS256")
                              (claims issuer nonce claim-overrides)
                              (boolean (:tamper? opts)))]
     (swap! ctl assoc
            :token-status (get opts :token-status 200)
            :token-body   (get opts :token-body {"id_token" tok "access_token" "at-1"}))
     {:begin-url url
      :state state
      :id-token tok
      :result (oidc/complete handle {"state" state "code" "the-authorization-code"})})))

;; --- §8.16.3 configuration ---------------------------------------------------

(deftest config-not-enabled-is-nil-not-a-finding
  (testing "any of the three keys missing means SSO is simply off"
    (is (nil? (oidc/config {})))
    (is (nil? (oidc/config {"OIDC_DISCOVERY_URL" "https://idp/.well-known/openid-configuration"})))
    (is (nil? (oidc/config {"OIDC_DISCOVERY_URL" "https://idp/x"
                            "OIDC_CLIENT_ID" client-id})))
    (testing "a blank value counts as absent"
      (is (nil? (oidc/config {"OIDC_DISCOVERY_URL" "https://idp/x"
                              "OIDC_CLIENT_ID" client-id
                              "OIDC_CLIENT_SECRET" "   "}))))))

(deftest config-missing-redirect-uri-refuses
  (let [r (oidc/config {"OIDC_DISCOVERY_URL" "https://idp/x"
                        "OIDC_CLIENT_ID" client-id
                        "OIDC_CLIENT_SECRET" client-secret})]
    (is (= #{:oidc.config/missing} (codes r)))
    (is (every? oidc/valid-finding? (:findings r)))
    (is (nil? (:config r)))))

(deftest config-defaults
  (let [c (:config (oidc/config (env "https://idp")))]
    (is (= "oidc" (:provider-id c)))
    (is (= "Single Sign-On" (:provider-name c)))
    (is (nil? (:issuer c)))
    (is (= ["openid" "email" "profile"] (:scopes c)))
    (is (= "email" (:identifying-claim c)))
    (is (= ["upn" "preferred_username" "sub"] (:fallback-claims c)))
    (testing "empty allow list = unrestricted, not one company's domain"
      (is (= [] (:allowed-email-domains c))))))

(deftest config-csv-and-normalization
  (let [c (:config (oidc/config
                    (env "https://idp"
                         {"OIDC_ADDITIONAL_SCOPES" " offline_access , email ,, groups "
                          "OIDC_FALLBACK_CLAIMS_IDENTIFYING_USER" "upn, sub"
                          "OIDC_ALLOWED_EMAIL_DOMAINS" " @Example.COM ,corp.test "
                          "OIDC_PROVIDER_ID" " entra "
                          "OIDC_PROVIDER_NAME" "Company Login"
                          "OIDC_ISSUER" "https://issuer.example"})))]
    (testing "additional scopes append and dedupe against the base three"
      (is (= ["openid" "email" "profile" "offline_access" "groups"] (:scopes c))))
    (is (= ["upn" "sub"] (:fallback-claims c)))
    (testing "domains normalize: lowercase, no leading @"
      (is (= ["example.com" "corp.test"] (:allowed-email-domains c))))
    (is (= "entra" (:provider-id c)))
    (is (= "Company Login" (:provider-name c)))
    (is (= "https://issuer.example" (:issuer c)))))

(deftest provider-info-is-the-button
  (let [h (oidc/open (:config (oidc/config (env "https://idp" {"OIDC_PROVIDER_NAME" "Corp SSO"}))))]
    (is (= {:id "oidc" :name "Corp SSO"} (oidc/provider-info h)))))

(deftest return-to-sanitization
  (is (= "/x" (oidc/sanitize-return-to "/x")))
  (testing "a protocol-relative URL would make this an open redirector"
    (is (= "/" (oidc/sanitize-return-to "//evil.example/x"))))
  (is (= "/" (oidc/sanitize-return-to "https://evil.example")))
  (is (= "/" (oidc/sanitize-return-to nil))))

(deftest browser-normalized-return-to-stays-on-origin
  (doseq [path ["/\\evil.example" "/\t/evil.example" "/\n/evil.example"
                "/chat\r\nLocation: elsewhere"]]
    (is (= "/" (oidc/sanitize-return-to path)))))

(deftest browser-state-is-checked-before-consuming-the-transaction
  (let [h (oidc/open {})]
    (reset! (:transactions h) {"state" {:sentinel true}})
    (doseq [cookie [nil "" "another-browser"]]
      (is (= #{:auth/invalid-oidc-response}
             (codes (oidc/complete h {"state" "state" "code" "code"} cookie))))
      (is (= {"state" {:sentinel true}} @(:transactions h))))))

;; --- §8.16.4 rule 1: the authorization request -------------------------------

(deftest begin-builds-a-pkce-request
  (with-idp* {}
    (fn [{:keys [handle]}]
      (let [{:keys [url state]} (oidc/begin handle "/reports/7")
            q (query-of url)]
        (is (str/starts-with? url "http://127.0.0.1"))
        (is (= client-id (get q "client_id")))
        (is (= "https://app.example/callback" (get q "redirect_uri")))
        (is (= "code" (get q "response_type")))
        (is (= "query" (get q "response_mode")))
        (is (= "openid email profile" (get q "scope")))
        (is (= "S256" (get q "code_challenge_method")))
        (is (seq (get q "code_challenge")))
        (is (= state (get q "state")))
        (is (not= (get q "state") (get q "nonce")))
        (testing "state and nonce are unguessable, not counters"
          (is (<= 40 (count state)))
          (is (not= state (:state (oidc/begin handle "/")))))))))

(deftest begin-refuses-when-discovery-is-unreachable
  ;; A port nothing listens on: doc §8.16.6 sends this to 502, not 401.
  (let [cfg (:config (oidc/config (env "http://127.0.0.1:1")))
        h   (oidc/open cfg)]
    (is (= #{:auth/unavailable-idp} (codes (oidc/begin h "/"))))))

;; --- the happy round trip (§8.16.7) ------------------------------------------

(deftest full-round-trip
  (with-idp* {}
    (fn [{:keys [handle ctl issuer] :as ctx}]
      (let [{:keys [result state]} (run-callback ctx {} {:return-to "/dashboard"})]
        (is (nil? (:findings result)) (pr-str (:findings result)))
        (is (= {:issuer issuer :subject "subject-1"
                :email "person@example.com" :name "A Person"}
               (dissoc (:identity result) :claims)))
        (testing "the full verified payload is available to the caller"
          (is (= issuer (get-in result [:identity :claims "iss"]))))
        (is (= "/dashboard" (:return-to result)))
        (testing "the exchange used client_secret_post with the PKCE verifier"
          (let [req (:last-token-request @ctl)]
            (is (= "authorization_code" (get req "grant_type")))
            (is (= client-secret (get req "client_secret")))
            (is (= "the-authorization-code" (get req "code")))
            (is (seq (get req "code_verifier")))))
        (testing "the transaction is one-time (§8.16.4 rule 1)"
          (is (= #{:auth/expired-oidc-transaction}
                 (codes (oidc/complete handle {"state" state "code" "the-authorization-code"})))))))))

(deftest round-trip-accepts-keyword-keyed-params
  (with-idp* {}
    (fn [{:keys [handle kp ctl issuer]}]
      (let [{:keys [url state]} (oidc/begin handle "/")
            nonce (get (query-of url) "nonce")]
        (swap! ctl assoc :token-body
               {"id_token" (fake/sign-jwt kp kid "RS256" (claims issuer nonce {}))})
        (is (= "subject-1" (get-in (oidc/complete handle {:state state :code "c"})
                                   [:identity :subject])))))))

(deftest es256-is-verified-too
  (with-idp* {}
    (fn [{:keys [handle ctl issuer]}]
      ;; Swap the fake IdP's whole key set for an EC one before the first fetch.
      (let [kp (fake/ec-keypair)]
        (swap! ctl assoc :jwks [(fake/ec-jwk kp kid)])
        (let [{:keys [url state]} (oidc/begin handle "/")
              nonce (get (query-of url) "nonce")]
          (swap! ctl assoc :token-body
                 {"id_token" (fake/sign-jwt kp kid "ES256" (claims issuer nonce {}))})
          (is (= "subject-1" (get-in (oidc/complete handle {"state" state "code" "c"})
                                     [:identity :subject]))))))))

(defn- standard-base64-rsa-jwk
  "mycIS publishes `n`/`e` in the standard base64 alphabet (`+` `/`), not
   base64url as RFC 7517 requires."
  [kp kid]
  (let [enc (fn [^bytes bs] (.encodeToString (java.util.Base64/getEncoder) bs))
        jwk (fake/rsa-jwk kp kid)
        pk  ^java.security.interfaces.RSAPublicKey (.getPublic ^java.security.KeyPair kp)]
    (assoc jwk
           "n" (enc (#'fake/unsigned (.getModulus pk) nil))
           "e" (enc (#'fake/unsigned (.getPublicExponent pk) nil)))))

(deftest jwks-with-standard-base64-modulus-is-accepted
  (with-idp* {:jwk-fn standard-base64-rsa-jwk}
    (fn [ctx]
      (is (re-find #"[+/]" (get-in @(:ctl ctx) [:jwks 0 "n"]))
          "the fixture must actually contain a character base64url forbids")
      (let [{:keys [result]} (run-callback ctx)]
        (is (empty? (:findings result)))
        (is (= "subject-1" (get-in result [:identity :subject])))))))

(deftest identifying-claim-falls-back-in-order
  (with-idp* {:env-extra {"OIDC_CLAIM_IDENTIFYING_USER" "email"
                          "OIDC_FALLBACK_CLAIMS_IDENTIFYING_USER" "upn,preferred_username"}}
    (fn [ctx]
      (let [{:keys [result]} (run-callback ctx {"email" "  " "upn" "Fallback@Corp.Test"} {})]
        (is (= "fallback@corp.test" (get-in result [:identity :email])))
        (testing "name falls back to the address when no name claim is present"
          (is (= "A Person" (get-in result [:identity :name]))))))))

(deftest name-falls-back-to-email
  (testing "no name-ish claim and no identifying claim: the address is the display name"
    (with-idp* {:env-extra {"OIDC_FALLBACK_CLAIMS_IDENTIFYING_USER" "upn"}}
      (fn [ctx]
        (let [{:keys [result]} (run-callback ctx {"name" nil "email" nil
                                                  "upn" "Person@Example.COM"} {})]
          (is (= "person@example.com" (get-in result [:identity :name]))))))))

;; --- §8.16.6 refusal codes ---------------------------------------------------

(deftest callback-without-state-or-code
  (with-idp* {}
    (fn [{:keys [handle]}]
      (is (= #{:auth/invalid-oidc-response} (codes (oidc/complete handle {}))))
      (is (= #{:auth/invalid-oidc-response} (codes (oidc/complete handle {"state" "s"}))))
      (testing "an error= callback from the IdP is the same code"
        (is (= #{:auth/invalid-oidc-response}
               (codes (oidc/complete handle {"state" "s" "error" "access_denied"}))))))))

(deftest unknown-state
  (with-idp* {}
    (fn [{:keys [handle]}]
      (is (= #{:auth/expired-oidc-transaction}
             (codes (oidc/complete handle {"state" "never-issued" "code" "c"})))))))

(deftest expired-transaction
  (with-idp* {}
    (fn [{:keys [config ctl kp issuer]}]
      (let [clock (atom 0)
            h     (oidc/open config {:now-fn (fn [] @clock)})
            {:keys [url state]} (oidc/begin h "/")
            nonce (get (query-of url) "nonce")]
        (swap! ctl assoc :token-body
               {"id_token" (fake/sign-jwt kp kid "RS256" (claims issuer nonce {}))})
        (testing "11 minutes later the transaction is gone (10-minute TTL)"
          (reset! clock (* 11 60 1000))
          (is (= #{:auth/expired-oidc-transaction}
                 (codes (oidc/complete h {"state" state "code" "c"})))))))))

(deftest token-endpoint-rejects-the-code
  (with-idp* {}
    (fn [ctx]
      (let [{:keys [result]} (run-callback ctx {} {:token-status 400
                                                   :token-body {"error" "invalid_grant"}})]
        (testing "a 400 is the IdP answering, not the IdP being down"
          (is (= #{:auth/invalid-oidc-response} (codes result))))))))

(deftest token-response-without-id-token
  (with-idp* {}
    (fn [ctx]
      ;; §8.16.5: userinfo is never called, so a response with only an access
      ;; token gives this relying party nothing it can verify.
      (let [{:keys [result]} (run-callback ctx {} {:token-body {"access_token" "at"}})]
        (is (= #{:auth/invalid-oidc-response} (codes result)))))))

(deftest token-endpoint-unreachable
  (with-idp* {}
    (fn [{:keys [handle idp]}]
      (let [{:keys [state]} (oidc/begin handle "/")]
        (fake/stop! idp)
        (is (= #{:auth/unavailable-idp}
               (codes (oidc/complete handle {"state" state "code" "c"}))))))))

(deftest bad-signature
  (with-idp* {}
    (fn [ctx]
      (is (= #{:auth/invalid-oidc-response}
             (codes (:result (run-callback ctx {} {:tamper? true}))))))))

(deftest wrong-issuer
  (with-idp* {}
    (fn [ctx]
      (is (= #{:auth/invalid-oidc-response}
             (codes (:result (run-callback ctx {"iss" "https://someone-else.example"} {}))))))))

(deftest configured-issuer-overrides-discovery
  (with-idp* {:env-extra {"OIDC_ISSUER" "https://pinned.example"}}
    (fn [ctx]
      (testing "the discovery issuer is no longer accepted once OIDC_ISSUER pins one"
        (is (= #{:auth/invalid-oidc-response} (codes (:result (run-callback ctx {} {}))))))
      (testing "and the pinned one is"
        (is (nil? (:findings (:result (run-callback ctx {"iss" "https://pinned.example"} {}))))))))) 

(deftest wrong-audience
  (with-idp* {}
    (fn [ctx]
      (is (= #{:auth/invalid-oidc-response}
             (codes (:result (run-callback ctx {"aud" "another-client"} {})))))
      (testing "an array audience containing the client id is accepted"
        (is (nil? (:findings (:result (run-callback ctx {"aud" ["another-client" client-id]} {})))))))))

(deftest expired-id-token
  (with-idp* {}
    (fn [ctx]
      (is (= #{:auth/invalid-oidc-response}
             (codes (:result (run-callback ctx {"exp" (- (now-seconds) 600)} {})))))
      (testing "60 seconds of clock skew is tolerated"
        (is (nil? (:findings (:result (run-callback ctx {"exp" (- (now-seconds) 30)} {})))))))))

(deftest wrong-nonce
  (with-idp* {}
    (fn [ctx]
      (testing "a token minted for another session must not be replayable here"
        (is (= #{:auth/invalid-oidc-response}
               (codes (:result (run-callback ctx {"nonce" "some-other-nonce"} {})))))))))

(deftest unsupported-algorithm
  (with-idp* {}
    (fn [{:keys [handle ctl issuer]}]
      (let [{:keys [url state]} (oidc/begin handle "/")
            nonce (get (query-of url) "nonce")
            b64   (fn [s] (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder))
                                           (.getBytes ^String s StandardCharsets/UTF_8)))]
        ;; `alg: none` with an empty signature — the classic JWS defeat. The
        ;; check is a whitelist, so this never reaches a verifier.
        (swap! ctl assoc :token-body
               {"id_token" (str (b64 "{\"alg\":\"none\"}") "."
                                (b64 (cheshire.core/generate-string (claims issuer nonce {})))
                                ".")})
        (is (= #{:auth/invalid-oidc-response}
               (codes (oidc/complete handle {"state" state "code" "c"}))))))))

(deftest unknown-kid-after-one-refetch
  (with-idp* {}
    (fn [{:keys [handle kp ctl issuer]}]
      (let [{:keys [url state]} (oidc/begin handle "/")
            nonce (get (query-of url) "nonce")]
        (swap! ctl assoc :token-body
               {"id_token" (fake/sign-jwt kp "kid-that-does-not-exist" "RS256"
                                          (claims issuer nonce {}))})
        (is (= #{:auth/invalid-oidc-response}
               (codes (oidc/complete handle {"state" state "code" "c"}))))
        (testing "the miss cost exactly one JWKS fetch, not one per attempt"
          (is (= 1 (:jwks-fetches @ctl))))))))

(deftest rotated-kid-is-picked-up-on-refetch
  (with-idp* {}
    (fn [{:keys [handle ctl issuer]}]
      ;; Warm the cache with the original key, then rotate at the IdP.
      (let [kp2 (fake/rsa-keypair)]
        (oidc/begin handle "/")
        (swap! ctl assoc :jwks [(fake/rsa-jwk kp2 "rotated-kid")])
        (let [{:keys [url state]} (oidc/begin handle "/")
              nonce (get (query-of url) "nonce")]
          (swap! ctl assoc :token-body
                 {"id_token" (fake/sign-jwt kp2 "rotated-kid" "RS256" (claims issuer nonce {}))})
          (is (= "subject-1" (get-in (oidc/complete handle {"state" state "code" "c"})
                                     [:identity :subject]))))))))

(deftest missing-subject-claim
  (with-idp* {}
    (fn [ctx]
      (is (= #{:auth/invalid-oidc-claims}
             (codes (:result (run-callback ctx {"sub" nil} {}))))))))

(deftest no-identifying-claim-and-no-fallback
  (with-idp* {:env-extra {"OIDC_FALLBACK_CLAIMS_IDENTIFYING_USER" "upn,preferred_username"}}
    (fn [ctx]
      (is (= #{:auth/invalid-oidc-claims}
             (codes (:result (run-callback ctx {"email" nil} {}))))))))

;; --- §8.16.4 rule 4: linking and provisioning --------------------------------

(def ^:private identity-1
  {:issuer "https://idp.example" :subject "subject-1" :email "person@example.com"})

(defn- lookup
  ([principal-account] (lookup principal-account nil))
  ([principal-account email-account]
   {:by-principal (fn [_ _] principal-account)
    :by-email     (fn [_] email-account)}))

(deftest rule-4-1-linked-account-logs-in
  (let [account {:id 7 :disabled false :has-password? false :linked? true}]
    (is (= {:action :login :account account}
           (oidc/resolve-identity {:auto-provision? false} (lookup account) identity-1)))))

(deftest rule-4-1-disabled-linked-account-gets-the-password-refusal
  (testing "a login face must not confirm that a disabled account exists"
    (is (= #{:auth/invalid-credentials}
           (codes (oidc/resolve-identity
                   {:auto-provision? true}
                   (lookup {:disabled true :has-password? true :linked? true})
                   identity-1))))))

(deftest rule-4-1-wins-over-a-narrowed-domain-list
  (testing "an existing link is an explicit act and survives a later allow list"
    (let [account {:disabled false :has-password? false :linked? true}]
      (is (= :login (:action (oidc/resolve-identity
                              {:allowed-email-domains ["corp.test"]}
                              (lookup account) identity-1)))))))

(deftest rule-4-2-domain-not-allowed
  (is (= #{:auth/oidc-domain-not-allowed}
         (codes (oidc/resolve-identity {:allowed-email-domains ["corp.test"]
                                        :auto-provision? true}
                                       (lookup nil nil) identity-1))))
  (testing "an empty allow list is unrestricted"
    (is (= :provision (:action (oidc/resolve-identity {:allowed-email-domains []
                                                       :auto-provision? true}
                                                      (lookup nil nil) identity-1))))))

(deftest rule-4-3-binds-a-pre-registered-passwordless-account
  (let [account {:id 3 :disabled false :has-password? false :linked? false}]
    (is (= {:action :bind :account account}
           (oidc/resolve-identity {:auto-provision? false}
                                  (lookup nil account) identity-1)))))

(deftest rule-4-4-password-account-is-not-taken-over
  (testing "OMA's trustedProviders takeover is deliberately not copied (§8.16.1)"
    (is (= #{:auth/identity-not-linked}
           (codes (oidc/resolve-identity
                   {:auto-provision? true}
                   (lookup nil {:disabled false :has-password? true :linked? false})
                   identity-1))))))

(deftest rule-4-3-binds-an-administrator-pre-registered-password-account
  (testing "§8.16.9 item 3: pre-registration is 4.4's remedy, so a password does not block it"
    (let [account {:id 4 :disabled false :has-password? true :linked? false
                   :pre-registered? true}]
      (is (= {:action :bind :account account}
             (oidc/resolve-identity {:auto-provision? false}
                                    (lookup nil account) identity-1)))))
  (testing "a pre-registration does not move an existing link"
    (is (= #{:auth/identity-not-linked}
           (codes (oidc/resolve-identity
                   {:auto-provision? false}
                   (lookup nil {:disabled false :has-password? true :linked? true
                                :pre-registered? true})
                   identity-1)))))
  (testing "a disabled account is not bound"
    (is (= #{:auth/invalid-credentials}
           (codes (oidc/resolve-identity
                   {:auto-provision? false}
                   (lookup nil {:disabled true :has-password? true :linked? false
                                :pre-registered? true})
                   identity-1))))))

(deftest rule-4-4-account-linked-to-a-different-principal
  (testing "sub-rule 1 missed, so this link belongs to someone else"
    (is (= #{:auth/identity-not-linked}
           (codes (oidc/resolve-identity
                   {:auto-provision? true}
                   (lookup nil {:disabled false :has-password? false :linked? true})
                   identity-1))))))

(deftest rule-4-5-provision-or-refuse
  (is (= {:action :provision}
         (oidc/resolve-identity {:auto-provision? true} (lookup nil nil) identity-1)))
  (testing "console does not create accounts on sign-in"
    (is (= #{:auth/identity-not-provisioned}
           (codes (oidc/resolve-identity {:auto-provision? false}
                                         (lookup nil nil) identity-1))))))

(deftest every-finding-is-well-formed-and-registered
  (doseq [r [(oidc/config {"OIDC_DISCOVERY_URL" "x" "OIDC_CLIENT_ID" "y" "OIDC_CLIENT_SECRET" "z"})
             (oidc/resolve-identity {:auto-provision? false} (lookup nil nil) identity-1)
             (oidc/resolve-identity {:allowed-email-domains ["corp.test"]}
                                    (lookup nil nil) identity-1)]]
    (doseq [f (:findings r)]
      (is (oidc/valid-finding? f) (pr-str f))
      (is (contains? oidc/finding-codes (:code f))))))

;; --- §8.16.7 secrets in nothing that is returned -----------------------------

(deftest failure-paths-leak-nothing
  (with-idp* {}
    (fn [{:keys [handle] :as ctx}]
      (let [results (for [opts [{:tamper? true}
                                {:token-status 400 :token-body {"error" "invalid_grant"
                                                                "error_description" client-secret}}
                                {:token-body {"access_token" "at"}}]
                          :let [{:keys [result id-token]} (run-callback ctx {} opts)]]
                      [result id-token])
            extra   [[(oidc/complete handle {"state" "nope" "code" "the-authorization-code"}) nil]
                     [(:result (run-callback ctx {"sub" nil} {})) nil]]]
        (doseq [[result id-token] (concat results extra)]
          (let [printed (pr-str result)]
            (is (not (str/includes? printed client-secret)) printed)
            (is (not (str/includes? printed "the-authorization-code")) printed)
            (when id-token
              (is (not (str/includes? printed id-token))))))))))

(deftest config-accepts-the-raw-jvm-environment-map
  ;; Regression: studio passes `(System/getenv)` straight in; that Java map
  ;; refuses non-String keys with a ClassCastException.
  (is (nil? (oidc/config (System/getenv))))
  (is (nil? (oidc/config (java.util.HashMap. {"OIDC_CLIENT_ID" "x"})))))
