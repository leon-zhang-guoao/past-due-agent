(ns open.atlas.common.oidc.impl.flow
  "The browser round trip of doc §8.16.4 rules 1–3: authorization request,
   one-time transaction, code exchange, id_token verification, claim mapping.

   ## The transaction is consumed before anything can fail

   `complete` removes the transaction from the table *first*, before the token
   exchange and before any verification. A replayed `state` therefore always
   answers `auth/expired-oidc-transaction`, whatever happened on the first
   attempt — including an attempt that crashed halfway. Consuming late would
   turn every transient IdP failure into a window in which the same
   authorization code could be presented again.

   ## Failures collapse on purpose

   Every id_token defect — bad signature, wrong `iss`, wrong `aud`, expired,
   wrong `nonce` — is one code, `auth/invalid-oidc-response` (§8.16.6). This is
   not laziness about diagnosis: telling the browser *which* check failed tells
   an attacker which half of a forged token to fix next. The operator's
   diagnosis lives in the audit record (§8.16.4 rule 7), which names the code
   and the principal and never the token."
  (:require [clojure.string :as str]
            [open.atlas.common.crypto :as crypto]
            [open.atlas.common.oidc.impl.finding :as finding]
            [open.atlas.common.oidc.impl.http :as http]
            [open.atlas.common.oidc.impl.jws :as jws])
  (:import [java.security SecureRandom]))

(def ^:private ^SecureRandom rng (SecureRandom.))

(def transaction-ttl-ms
  "10 minutes (§8.16.4 rule 1). Long enough for a person to type a password and
   answer an MFA prompt at the IdP, short enough that an abandoned tab's state
   is not still live an hour later."
  (* 10 60 1000))

(def ^:private clock-skew-ms
  "`exp` is checked with 60 seconds of tolerance: the alternative is that a
   correctly issued token is refused because two machines disagree by a second."
  60000)

(defn- random-token
  "256 bits, base64url. Used for `state`, `nonce` and the PKCE verifier — all
   three are unguessability requirements, none of them is a name."
  []
  (let [b (byte-array 32)]
    (.nextBytes rng b)
    (jws/b64url-encode-no-pad b)))

(defn- code-challenge
  "S256: base64url(SHA-256(verifier)), unpadded (RFC 7636 §4.2)."
  [verifier]
  (jws/b64url-encode-no-pad (crypto/unhex (crypto/sha256-hex verifier))))

(defn sanitize-return-to
  "§8.16.4 rule 1: a same-origin path only — starts with `/` and not `//`,
   otherwise `/`. `//host/path` is a protocol-relative URL, so accepting it
   would make the post-login redirect an open redirector."
  [return-to]
  (if (and (string? return-to)
           (str/starts-with? return-to "/")
           (not (str/starts-with? return-to "//"))
           (not (re-find #"[\\\\\x00-\x20\x7f]" return-to)))
    return-to
    "/"))

;; --- discovery and keys ------------------------------------------------------

(defn- discovery
  "Fetch the discovery document once and cache it on the handle. `{:doc …}` or
   `{:findings […]}` with `auth/unavailable-idp`."
  [{:keys [config http-client discovery-cache]}]
  (if-let [cached @discovery-cache]
    {:doc cached}
    (let [{:keys [json error]} (http/get-json http-client (:discovery-url config))]
      (if error
        {:findings [(finding/error :auth/unavailable-idp [:auth :oidc]
                                   "the identity provider's discovery document could not be retrieved")]}
        (do (reset! discovery-cache json)
            {:doc json})))))

(defn- jwks-fetch!
  "Refresh the key cache from the JWKS endpoint, keyed by `kid`. Keys without a
   `kid` are stored under nil, which is what a single-key IdP produces."
  [{:keys [http-client jwks-cache]} jwks-uri]
  (let [{:keys [json error]} (http/get-json http-client jwks-uri)]
    (if error
      :unavailable
      (let [ks (into {} (for [jwk (get json "keys")
                              :let [k (jws/public-key jwk)]
                              :when k]
                          [(get jwk "kid") k]))]
        (reset! jwks-cache ks)
        ks))))

(defn- key-for
  "The verification key for `kid`, refetching **once** on a miss (§8.16.5):
   an IdP that rotated keys should cost one extra request, not a restart, and
   a made-up `kid` should not cost a request per callback."
  [oidc jwks-uri kid]
  (let [cached @(:jwks-cache oidc)]
    (or (get cached kid)
        (let [fresh (jwks-fetch! oidc jwks-uri)]
          (when-not (= :unavailable fresh)
            (get fresh kid))))))

;; --- begin -------------------------------------------------------------------

(defn begin
  [{:keys [config transactions now-fn] :as oidc} return-to]
  (let [{:keys [doc findings]} (discovery oidc)]
    (if findings
      {:findings findings}
      (if-let [authorize (get doc "authorization_endpoint")]
        (let [state    (random-token)
              nonce    (random-token)
              verifier (random-token)
              now      (now-fn)]
          (swap! transactions
                 (fn [txs]
                   ;; Expired entries are dropped on every begin: the table is
                   ;; process-local and unbounded otherwise, and a login page
                   ;; being crawled is a cheap way to grow it.
                   (-> (into {} (remove (fn [[_ t]] (< (:expires-at t) now))) txs)
                       (assoc state {:code-verifier verifier
                                     :nonce         nonce
                                     :return-to     (sanitize-return-to return-to)
                                     :expires-at    (+ now transaction-ttl-ms)}))))
          {:state state
           :url   (str authorize
                       (if (str/includes? authorize "?") "&" "?")
                       (http/form-encode
                        [["client_id" (:client-id config)]
                         ["redirect_uri" (:redirect-uri config)]
                         ["response_type" "code"]
                         ["response_mode" "query"]
                         ["scope" (str/join " " (:scopes config))]
                         ["state" state]
                         ["nonce" nonce]
                         ["code_challenge" (code-challenge verifier)]
                         ["code_challenge_method" "S256"]]))})
        {:findings [(finding/error :auth/unavailable-idp [:auth :oidc]
                                   "the discovery document names no authorization endpoint")]}))))

;; --- complete ----------------------------------------------------------------

(defn- param [params k]
  (let [v (or (get params k) (get params (keyword k)))]
    (when (and (string? v) (not (str/blank? v))) v)))

(defn- consume!
  "Remove the transaction for `state` and return it if it was live. One-time by
   construction: the removal is the read."
  [{:keys [transactions now-fn]} state]
  (let [[old _] (swap-vals! transactions dissoc state)
        tx      (get old state)]
    (when (and tx (>= (:expires-at tx) (now-fn)))
      tx)))

(defn- invalid-response []
  {:findings [(finding/error :auth/invalid-oidc-response [:auth :oidc]
                             "the identity provider's response could not be accepted")]})

(defn- unavailable []
  {:findings [(finding/error :auth/unavailable-idp [:auth :oidc]
                             "the identity provider could not be reached")]})

(defn- audience-ok? [aud client-id]
  (cond
    (string? aud) (= aud client-id)
    (coll? aud)   (boolean (some #(= % client-id) aud))
    :else         false))

(defn- verify-id-token
  "All of §8.16.4 rule 2, or nil. Returns the verified payload."
  [{:keys [config] :as oidc} doc id-token nonce now]
  (when-let [{:keys [header payload] :as jws} (jws/parse id-token)]
    (when (jws/supported-alg? (get header "alg"))
      (when-let [key (key-for oidc (get doc "jwks_uri") (get header "kid"))]
        (when (jws/verify? jws key)
          (let [expected-issuer (or (:issuer config) (get doc "issuer"))
                exp             (get payload "exp")]
            (when (and (some? expected-issuer)
                       (= expected-issuer (get payload "iss"))
                       (audience-ok? (get payload "aud") (:client-id config))
                       (number? exp)
                       (< now (+ (* 1000 (long exp)) clock-skew-ms))
                       (some? nonce)
                       (= nonce (get payload "nonce")))
              payload)))))))

(defn- first-claim
  "The first non-blank string among `ks`, in order."
  [payload ks]
  (some (fn [k]
          (let [v (get payload k)]
            (when (and (string? v) (not (str/blank? v))) v)))
        ks))

(defn- map-claims
  "§8.16.4 rule 3 plus §8.16.3's claim configuration: `(issuer, sub)` is the
   principal; email and name are attributes."
  [{:keys [config]} payload]
  (let [subject (first-claim payload ["sub"])
        email   (some-> (first-claim payload (into [(:identifying-claim config)]
                                                   (:fallback-claims config)))
                        str/lower-case)]
    (cond
      (nil? subject)
      {:findings [(finding/error :auth/invalid-oidc-claims [:auth :oidc :sub]
                                 "the id_token carries no subject claim")]}

      (nil? email)
      {:findings [(finding/error :auth/invalid-oidc-claims [:auth :oidc :email]
                                 (str "the id_token carries neither the configured "
                                      "identifying claim nor any configured fallback"))]}

      :else
      {:identity {:issuer  (get payload "iss")
                  :subject subject
                  :email   email
                  :name    (or (first-claim payload ["name" "displayName" "given_name"
                                                     "preferred_username"
                                                     (:identifying-claim config)])
                               email)
                  :claims  payload}})))

(defn complete
  [{:keys [config http-client now-fn] :as oidc} params]
  (let [state (param params "state")
        code  (param params "code")]
    (if-not (and state code)
      ;; Includes the IdP's own `error=...` callback: from the browser's side
      ;; there is nothing to distinguish, and §8.16.6 gives both one code.
      (invalid-response)
      (if-let [tx (consume! oidc state)]
        (let [{:keys [doc findings]} (discovery oidc)]
          (if findings
            {:findings findings}
            (let [token-endpoint (get doc "token_endpoint")]
              (if-not token-endpoint
                (unavailable)
                (let [{:keys [json error]}
                      (http/post-form http-client token-endpoint
                                      ;; client_secret_post (§8.16.3): the secret
                                      ;; is a body field, never a query parameter.
                                      [["grant_type" "authorization_code"]
                                       ["code" code]
                                       ["redirect_uri" (:redirect-uri config)]
                                       ["client_id" (:client-id config)]
                                       ["client_secret" (:client-secret config)]
                                       ["code_verifier" (:code-verifier tx)]])]
                  (cond
                    (= :unavailable error) (unavailable)
                    (some? error)          (invalid-response)
                    :else
                    (let [id-token (get json "id_token")]
                      ;; No userinfo call (§8.16.5): the id_token is the verified
                      ;; document, and a token response without one is not a
                      ;; response this relying party can use.
                      (if-not (string? id-token)
                        (invalid-response)
                        (if-let [payload (verify-id-token oidc doc id-token (:nonce tx) (now-fn))]
                          (let [mapped (map-claims oidc payload)]
                            (if (:findings mapped)
                              mapped
                              (assoc mapped :return-to (:return-to tx))))
                          (invalid-response))))))))))
        {:findings [(finding/error :auth/expired-oidc-transaction [:auth :oidc :state]
                                   (str "no live single sign-on transaction for this state; "
                                        "it expired, was already used, or was never issued"))]}))))
