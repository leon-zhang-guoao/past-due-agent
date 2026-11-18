(ns open.atlas.common.oidc
  "Enterprise single sign-on (OIDC), shared by all three login faces
   (doc [8.16](../../../../../../docs/design/101-sso-oidc.md)).

   This namespace is the module's whole public face; everything under
   `.impl.*` is private (CLAUDE.md, enforced by `:discouraged-namespace`).

   ## What is shared and what is not

   Shared: the walk from a browser callback to a verified
   `(issuer, subject, claims)`, and the linking/provisioning decision of
   §8.16.4 rule 4. Not shared: **identity storage** — studio keeps users in
   Datahike and server keeps operators in SQLite, and doc 8.12.4-bis's reason
   for not sinking identity still holds for the store. What changed in
   2026-09-04 is that the *protocol* now has two real callers, which is what
   makes sinking it justified rather than speculative.

   ## Shape of the API

   Everything is a pure function except the three that must talk to the IdP or
   hold the one-time transaction table, and those take an explicit handle from
   `open` — there is no global state in this module. Refusals are always
   `{:findings [...]}` per [finding-v1](../../../../../../docs/contracts/finding-v1.md);
   no finding here carries the IdP response, the authorization code, the client
   secret, or any token (§8.16.4 rule 2, §8.16.7).

   ```clojure
   (when-let [result (oidc/config (System/getenv))]      ; nil = SSO not enabled
     (if-let [fs (:findings result)] (refuse-startup fs)
       (let [handle (oidc/open (:config result))]
         ;; GET /…/auth/oidc
         (let [{:keys [url state findings]} (oidc/begin handle return-to)] …)
         ;; GET /…/auth/oidc/callback
         (let [{:keys [identity return-to findings]} (oidc/complete handle query-params)]
           (oidc/resolve-identity rules lookup identity)))))
   ```"
  (:require [open.atlas.common.oidc.impl.config :as config]
            [open.atlas.common.oidc.impl.finding :as finding]
            [open.atlas.common.oidc.impl.flow :as flow]
            [open.atlas.common.oidc.impl.http :as http]
            [open.atlas.common.oidc.impl.resolve :as resolve]))

(def finding-codes
  "Every `:code` this module may produce (doc §8.16.6). Registered in one place
   so a reader routing on a code finds it without grepping the implementation."
  finding/codes)

(defn valid-finding?
  "Is x a well-formed finding of this module's domain?"
  [x]
  (finding/valid? x))

(defn config
  "Env map → SSO configuration (§8.16.3).

   - `nil` — `OIDC_DISCOVERY_URL` / `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET` are
     not all present, so SSO is **not enabled**. Not an error: the login page
     shows no button and the two routes answer `auth/oidc-disabled`.
   - `{:findings [...]}` — those three are present but `OIDC_REDIRECT_URI` is
     not (`:oidc.config/missing`). The redirect URI is never guessed.
   - `{:config {...}}` — keys `:discovery-url :client-id :client-secret
     :redirect-uri :provider-id :provider-name :issuer :scopes
     :identifying-claim :fallback-claims :allowed-email-domains`.

   Values are trimmed; a blank value counts as absent."
  [env-map]
  (config/config env-map))

(defn open
  "Build the handle the stateful calls take: the config plus the one-time
   transaction table and the discovery/JWKS caches.

   `opts` is for tests — `:http-client` (a `java.net.http.HttpClient`) and
   `:now-fn` (→ epoch millis). Production passes neither."
  ([cfg] (open cfg {}))
  ([cfg {:keys [http-client now-fn]}]
   {:config          cfg
    :transactions    (atom {})
    :discovery-cache (atom nil)
    :jwks-cache      (atom {})
    :http-client     (or http-client (http/client))
    :now-fn          (or now-fn #(System/currentTimeMillis))}))

(defn close!
  "Release the handle. Nothing is spawned today — the JDK client owns its own
   executor and the caches are plain atoms — so this exists so that callers do
   not have to change shape if that stops being true. Clearing the transaction
   table on close is not merely tidiness: a shut-down login face should not hold
   live PKCE verifiers."
  [oidc]
  (reset! (:transactions oidc) {})
  nil)

(defn provider-info
  "`{:id :name}` for each app's auth-state endpoint (§8.16.3)."
  [oidc]
  (let [c (:config oidc)]
    {:id (:provider-id c) :name (:provider-name c)}))

(defn begin
  "Start the round trip (§8.16.4 rule 1).

   → `{:url \"<authorization endpoint …>\" :state \"…\"}`, having stored a
   one-time transaction `(state → code-verifier, nonce, return-to, expires-at)`
   with a 10-minute TTL. `return-to` is sanitized to a same-origin path.
   → `{:findings [...]}` with `:auth/unavailable-idp` if discovery fails.

   Discovery is fetched lazily on the first call and cached, so a
   misconfigured or down IdP costs a 502 on a login attempt rather than a
   refusal to start."
  [oidc return-to]
  (flow/begin oidc return-to))

(defn complete
  "Finish the round trip from the callback query parameters (a map keyed by
   string or keyword).

   → `{:identity {:issuer :subject :email :name :claims} :return-to \"…\"}`
   → `{:findings [...]}` with one of `:auth/invalid-oidc-response`,
     `:auth/expired-oidc-transaction`, `:auth/invalid-oidc-claims`,
     `:auth/unavailable-idp`.

   The transaction is consumed **before** the token exchange, so a replayed
   `state` always answers `:auth/expired-oidc-transaction` — including after an
   attempt that failed midway. HTTP callers must use the three-argument arity
   with the state cookie issued to the browser that started the round trip."
  ([oidc query-params]
   (flow/complete oidc query-params))
  ([oidc query-params browser-state]
   (let [state (or (get query-params "state") (:state query-params))]
     (if (and (string? state) (seq state) (= state browser-state))
       (flow/complete oidc query-params)
       {:findings [(finding/error :auth/invalid-oidc-response [:auth :oidc]
                                  "the callback must belong to this browser's sign-on request")]}))))

(defn resolve-identity
  "Linking and provisioning (§8.16.4 rule 4). Pure.

   - `rules`    — `{:allowed-email-domains [...] :auto-provision? bool}`
   - `lookup`   — `{:by-principal (fn [issuer subject]) :by-email (fn [email])}`,
     each returning an account map carrying `:disabled`, `:has-password?` and
     `:linked?`, or nil. `:by-email` sets `:pre-registered?` on an account an
     administrator pre-registered for SSO (§8.16.9 item 3): such an account
     binds even with a password, and the caller clears the pre-registration
   - `identity` — as returned by `complete`

   → `{:action :login :account a}` / `{:action :bind :account a}` /
     `{:action :provision}` / `{:findings [...]}`.

   The caller performs the action; it does not re-decide."
  [rules lookup identity]
  (resolve/resolve-identity rules lookup identity))

(defn sanitize-return-to
  "The `return-to` rule of §8.16.4 rule 1, exposed because each app's login
   form passes a path through its own routing before `begin` sees it."
  [return-to]
  (flow/sanitize-return-to return-to))
