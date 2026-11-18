(ns open.atlas.studio.auth
  "The Studio's session boundary: who is making this request.

   Extracted from builder-server along the seam that was already there — the
   four auth handlers, the session cookie, and the request-scoped user.  The
   identity store itself stays in `open.atlas.studio.identity`; this ns is the
   HTTP-shaped skin over it.  Handlers take the pool `conn` explicitly rather
   than reaching for the server's state atom, so this ns holds no state at
   all — the one dynamic var below is request-scoped, bound per exchange by
   the route loop."
  (:require [clojure.string :as str]
            [open.atlas.common.oidc :as oidc]
            [open.atlas.studio.http :as http]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.pool :as pool])
  (:import [com.sun.net.httpserver HttpExchange]
           [java.net URLDecoder]
           [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(def ^:dynamic *request-user*
  "The authenticated Studio user for the request being served, bound by the
   route loop before any handler runs.  nil = unauthenticated."
  nil)

(def ^:private session-cookie-name "open_atlas_studio_session")
(def ^:private oidc-state-cookie-name "open_atlas_studio_oidc_state")

(defn- session-cookie [{:keys [token max-age]}]
  (str session-cookie-name "=" token
       "; HttpOnly; SameSite=Lax; Path=/; Max-Age=" max-age))

(defn- cleared-session-cookie []
  (str session-cookie-name "=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0"))

(defn request-user
  "The session's user, straight off the cookie — or nil."
  [exchange]
  (some-> (http/request-cookie exchange session-cookie-name)
          identity/session-user))

(defn request-principal
  "The control-plane principal of the current request (D5.10 immutable key).
   Throws when unauthenticated — data may only be served to someone."
  []
  (or (:principal *request-user*)
      (throw (ex-info "Authentication is required"
                      {:code :authentication-required}))))

;; ---------------------------------------------------------------------------
;; Admin service token (doc 112 §112.1)
;;
;; The release tool talks to Studio's `/api/studio/**` with a long-lived
;; Bearer secret the operator generated. Studio stores only its SHA-256
;; (several, comma separated, so a rotation can overlap) and the account the
;; token acts as. It is deliberately NOT a workspace token (`oaw_v1.`): those
;; are minted by Studio, scoped to one workspace and verified by
;; `workspace-token/verify`; this one is configured, never minted, and acts as
;; a whole account. A bearer with the `oaw_v1.` prefix is never compared here.
;; ---------------------------------------------------------------------------

(def ^:dynamic *admin-token*
  "Bound to `{:digest-prefix \"<8 hex>\"}` while a request authenticated by the
   admin service token is served, nil otherwise — so audit can name the
   service actor (§112.1 audit row) without the token ever being stored."
  nil)

(def admin-token-sha-env "OPEN_ATLAS_STUDIO_ADMIN_TOKEN_SHA256")
(def admin-token-user-env "OPEN_ATLAS_STUDIO_ADMIN_TOKEN_USER")

(defn admin-token-config
  "Environment map -> nil (feature off), `{:digests [..] :user \"..\"}`, or
   `{:findings [..]}` when half-written or malformed. Refuse, never guess: a
   digest that is not 64 lowercase hex would simply never match, and an
   operator would chase a 401 instead of reading a startup message."
  [env]
  (let [raw-sha (some-> (get env admin-token-sha-env) str/trim not-empty)
        user (some-> (get env admin-token-user-env) str/trim not-empty)
        digests (when raw-sha
                  (->> (str/split raw-sha #",") (map str/trim) (remove str/blank?) vec))
        bad (remove #(re-matches #"[0-9a-f]{64}" %) digests)]
    (cond
      (and (nil? raw-sha) (nil? user)) nil

      (nil? raw-sha)
      {:findings [{:code :auth/admin-token-config
                   :message (str admin-token-user-env " is set but "
                                 admin-token-sha-env " is not.")
                   :expected "set both, or neither"}]}

      (or (empty? digests) (seq bad))
      {:findings [{:code :auth/admin-token-config
                   :message (str admin-token-sha-env " must be one or more lowercase"
                                 " hex SHA-256 digests, comma separated.")
                   :expected "64 characters [0-9a-f] each"
                   :malformed (if (empty? digests) 1 (count bad))}]}

      (nil? user)
      {:findings [{:code :auth/admin-token-config
                   :message (str admin-token-sha-env " is set but "
                                 admin-token-user-env " is not.")
                   :expected "the email of an existing Studio account"}]}

      :else {:digests digests :user user})))

(defn bearer-token
  "The raw Bearer credential of the request, or nil."
  [^HttpExchange exchange]
  (let [h (str (.getFirst (.getRequestHeaders exchange) "Authorization"))]
    (when (str/starts-with? (str/lower-case h) "bearer ")
      (not-empty (str/trim (subs h 7))))))

(defn workspace-token-shaped?
  "Workspace tokens keep their own door; the admin path never compares them."
  [raw]
  (str/starts-with? raw "oaw_v1."))

(defn- sha256-bytes ^bytes [^String s]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s StandardCharsets/UTF_8)))

(defn- unhex ^bytes [^String h]
  (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16)) (partition 2 h))))

(defn admin-token-match
  "The configured digest `raw` hashes to, or nil. Every configured digest is
   compared with `MessageDigest/isEqual` (constant time) and none is skipped
   early, so timing does not reveal which slot matched."
  [{:keys [digests]} raw]
  (let [presented (sha256-bytes raw)]
    (reduce (fn [hit d] (if (MessageDigest/isEqual presented (unhex d)) (or hit d) hit))
            nil digests)))

(defn admin-token-user
  "The configured account as a session user, or a refusal naming the problem.
   Resolved on every request so a disabled or removed account stops the token
   at once rather than at the next restart."
  [db {:keys [user]}]
  (let [found (identity/user-by-email db user)]
    (cond
      (nil? found)
      {:error {:code :auth/admin-token-user-missing
               :message (str admin-token-user-env " names no Studio account.")}}
      (:disabled? found)
      {:error {:code :auth/admin-token-user-disabled
               :message (str "The account " admin-token-user-env " names is disabled.")}}
      :else {:user (identity/public-user found)})))

(def ^:private admin-token-denied-prefixes
  "Inside `/api/studio/**`, what the admin token must not reach (§112.1 「不能做」):
   account management, workspace membership, the Studio-administrator routes,
   and minting workspace tokens — a configured key must not mint further
   standing keys."
  ["/api/studio/users" "/api/studio/workspaces/members" "/api/studio/admin/"
   "/api/studio/workspace-tokens/issue"])

(defn admin-token-route?
  "May the admin service token reach `path`?"
  [path]
  (and (str/starts-with? path "/api/studio/")
       (not-any? #(str/starts-with? path %) admin-token-denied-prefixes)))

(defn require-studio-admin! []
  ;; §112.1: the admin token acts as a workspace administrator, never as a
  ;; Studio administrator, even when the configured account is one.
  (when (or *admin-token* (not (identity/admin? *request-user*)))
    (throw (ex-info "A Studio administrator is required"
                    {:code :studio-admin-required}))))

(defn handle-auth-state!
  "`oidc` is the module handle or nil. `:oidc` is `{:id :name}` when enterprise
   SSO is configured and nil when it is not (doc
   [8.16](../../../../../../docs/design/101-sso-oidc.md) §8.16.5) — it is what
   the sign-in screen decides whether to offer a button from, so a Studio with
   no `OIDC_*` configured answers exactly what it answered before."
  ([conn exchange] (handle-auth-state! conn exchange nil))
  ([conn exchange oidc]
   (let [provider (when oidc (oidc/provider-info oidc))]
     (if-let [user (request-user exchange)]
       (http/reply-edn! exchange 200 {:authenticated? true :user user :oidc provider})
       (http/reply-edn! exchange 200 {:authenticated? false
                                      :oidc provider
                                      :needs-setup? (identity/needs-setup?
                                                     (pool/db conn))})))))

(defn handle-register! [conn exchange]
  (let [request (http/request-edn exchange)
        result (identity/bootstrap-admin! conn request)]
    (if-let [error (:error result)]
      (http/reply-edn! exchange (if (= :bootstrap-closed (:code error)) 409 400)
                       {:error error})
      (let [session (identity/create-session! (:user result))]
        (http/reply-edn! exchange 200 result
                         {"Set-Cookie" (session-cookie session)})))))

(defn handle-login!
  "Exchange an email and password for a session cookie.

   `audit-failed-login!` is `(fn [reason])`, called on refusal only — S5.3
   makes the refusal trail («who is knocking on which door») the data source
   for deciding whether someone should have been let in, and a trail of
   successful logins alone cannot answer that. It is threaded in rather than
   reached for: this ns holds no state and knows no instance (see the ns
   docstring), and the recorder belongs to the instance.

   The event carries the machine-readable reason and nothing else. It must
   not say which of «no such user» and «wrong password» happened —
   `authenticate-password` deliberately answers nil to both, and an audit
   reader who is less privileged than the identity store would otherwise
   learn from the trail exactly what the 401 refuses to tell the client.
   The attempted email and the password are values (audit-event-v1 §5) and
   appear in no key."
  [conn exchange audit-failed-login!]
  (let [{:keys [email password]} (http/request-edn exchange)]
    (if-let [user (identity/authenticate-password (pool/db conn) email password)]
      (let [session (identity/create-session! user)]
        (http/reply-edn! exchange 200 {:status :ok :user user}
                         {"Set-Cookie" (session-cookie session)}))
      (do
        (audit-failed-login! :auth/invalid-credentials)
        (http/reply-edn! exchange 401
                         {:error {:code :invalid-credentials
                                  :message "Invalid email or password."}})))))

(defn handle-logout! [exchange]
  (when-let [token (http/request-cookie exchange session-cookie-name)]
    (identity/destroy-session! token))
  (http/reply-edn! exchange 200 {:status :ok}
                   {"Set-Cookie" (cleared-session-cookie)}))

(defn handle-change-password!
  "Change only the signed-in user's local password, then expire every one of
   that user's Studio sessions.  The cleared cookie makes the required fresh
   login explicit to the browser that performed the change."
  [conn exchange]
  (let [{:keys [current-password new-password]} (http/request-edn exchange)
        result (identity/change-password! conn *request-user*
                                          current-password new-password)]
    (if-let [error (:error result)]
      (http/reply-edn! exchange
                       (case (:code error)
                         :invalid-current-password 401
                         :local-password-unavailable 409
                         400)
                       {:error error})
      (http/reply-edn! exchange 200 {:status :ok :reauthenticate? true}
                       {"Set-Cookie" (cleared-session-cookie)}))))


;; ---------------------------------------------------------------------------
;; Enterprise SSO (doc 8.16)
;;
;; The protocol is `common/oidc`'s and the linking decision is
;; `oidc/resolve-identity`'s — §8.16.4 rule 4 is decided once for all three
;; login faces. What is left here is exactly the two things that are studio's:
;; the store (Datahike) and the session cookie. Nothing below re-judges a
;; rejection the module already made.
;; ---------------------------------------------------------------------------

(def ^:private oidc-status
  "HTTP status per finding code (§8.16.6). A code with no entry is a bug in
   this table, not a reason to answer 200 — hence the explicit default of 400."
  {:auth/oidc-disabled            404
   :auth/invalid-oidc-response    401
   :auth/expired-oidc-transaction 401
   :auth/invalid-oidc-claims      401
   :auth/oidc-domain-not-allowed  403
   :auth/identity-not-linked      403
   :auth/identity-not-provisioned 403
   :auth/invalid-credentials      401
   :auth/unavailable-idp          502})

(defn- query-params
  "The callback's query string as a string-keyed map. Parsed here rather than
   reached for from builder-server: this ns takes an exchange and plain data."
  [^HttpExchange exchange]
  (let [q (.getQuery (.getRequestURI exchange))]
    (into {} (for [pair (str/split (or q "") #"&")
                   :when (seq pair)
                   :let [[k v] (str/split pair #"=" 2)]]
               [(URLDecoder/decode k StandardCharsets/UTF_8)
                (URLDecoder/decode (or v "") StandardCharsets/UTF_8)]))))

(defn- browser-navigation?
  "The callback is reached by a top-level navigation, so a browser asks for
   HTML; an EDN body there renders as an apparently blank page."
  [^HttpExchange exchange]
  (str/includes? (str (.getFirst (.getRequestHeaders exchange) "Accept")) "text/html"))

(defn- escape-html [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(defn- refusal-page [code message]
  (str "<!doctype html><meta charset=utf-8><title>Sign-in failed</title>"
       "<body style=\"font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem\">"
       "<h1 style=\"font-size:1.25rem\">Sign-in failed</h1>"
       "<p>" (escape-html (or message "Single sign-on could not be completed.")) "</p>"
       "<p style=\"color:#777\"><code>" (escape-html code) "</code></p>"
       "<p><a href=\"/\">Back to sign-in</a></p></body>"))

(defn- refuse-oidc!
  "Answer a finding in the envelope every other refusal on this face uses. The
   message is the module's, which carries no email, claim, code or token
   (§8.16.4 rule 2) — this ns adds nothing to it. A browser navigation gets the
   same message as a page with a way back."
  [exchange findings audit-failed-login!]
  (let [f (first findings)
        status (get oidc-status (:code f) 400)]
    (audit-failed-login! (:code f))
    (if (browser-navigation? exchange)
      (http/reply! exchange status (refusal-page (:code f) (:message f)) "text/html")
      (http/reply-edn! exchange status
                       {:error {:code (:code f) :message (:message f)}
                        :findings (vec findings)}))))

(defn- canonical-origin-redirect
  "The begin URL on the redirect URI's origin when the browser reached Studio
   through another one (`localhost` vs `127.0.0.1`). The state cookie is set on
   the host that serves begin, and the identity provider returns to the
   redirect URI's host — on different hosts the callback never sees the cookie."
  [^HttpExchange exchange redirect-uri]
  (let [target (java.net.URI/create (str redirect-uri))
        authority (.getAuthority target)
        host (.getFirst (.getRequestHeaders exchange) "Host")]
    (when (and host authority (not= (str/lower-case host) (str/lower-case authority)))
      (str (.getScheme target) "://" authority (.getRawPath (.getRequestURI exchange))
           (when-let [q (.getRawQuery (.getRequestURI exchange))] (str "?" q))))))

(def ^:private disabled-finding
  {:code :auth/oidc-disabled :severity :error :path [:auth :oidc]
   :message "single sign-on is not configured on this Studio."})

(defn handle-oidc-begin!
  "`GET /api/auth/oidc` — redirect the browser to the identity provider
   (§8.16.4 rule 1). 404 when SSO is not configured: the route exists in the
   table either way so that «not configured» and «not a route» are one answer
   a client can read, rather than a 404 page."
  [oidc exchange audit-failed-login!]
  (let [canonical (when oidc
                    (canonical-origin-redirect exchange (get-in oidc [:config :redirect-uri])))]
    (cond
      (not oidc) (refuse-oidc! exchange [disabled-finding] audit-failed-login!)
      canonical (http/reply! exchange 302 "" "text/plain" {"Location" canonical})
      :else
      (let [return-to (get (query-params exchange) "return_to")
            {:keys [url state findings]} (oidc/begin oidc return-to)]
        (if (seq findings)
          (refuse-oidc! exchange findings audit-failed-login!)
          (http/reply! exchange 302 "" "text/plain"
                       {"Location" url
                        "Set-Cookie" (str oidc-state-cookie-name "=" state
                                          "; HttpOnly; SameSite=Lax; Path=/api/auth/oidc; Max-Age=600"
                                          (when (str/starts-with? (get-in oidc [:config :redirect-uri]) "https://")
                                            "; Secure"))}))))))

(defn handle-oidc-callback!
  "`GET /api/auth/oidc/callback` — the whole of §8.16.4 rules 2-5 for studio.

   `audit-login!` is called with the identity provider principal on success and
   `audit-failed-login!` with the refusal's code otherwise, threaded in for the
   same reason `handle-login!` threads its recorder: this ns knows no instance.

   Studio's provisioning policy is `:auto-provision? true` (doc §8.16.5) — a
   verified identity inside the domain allow list gets a member account and its
   own workspace. The allow list is the gate; when it is empty every verified
   identity is one, which is the documented default and the thing an operator
   narrows by setting `OIDC_ALLOWED_EMAIL_DOMAINS`."
  [conn oidc exchange audit-failed-login! audit-login!]
  (if-not oidc
    (refuse-oidc! exchange [disabled-finding] audit-failed-login!)
    (let [{who :identity :keys [return-to findings]}
          (oidc/complete oidc (query-params exchange)
                         (http/request-cookie exchange oidc-state-cookie-name))]
      (if (seq findings)
        (refuse-oidc! exchange findings audit-failed-login!)
        (let [db (pool/db conn)
              decision (oidc/resolve-identity
                        {:allowed-email-domains (:allowed-email-domains (:config oidc))
                         :auto-provision? true}
                        {:by-principal
                         (fn [issuer subject]
                           (when-let [u (identity/user-by-principal db issuer subject)]
                             (assoc (identity/public-user u)
                                    :disabled (:disabled? u)
                                    :linked? true)))
                         :by-email
                         (fn [email]
                           ;; An administrator's pre-registration first, as in
                           ;; Workbench: it is the explicit act that may link
                           ;; an account with a password (§8.16.9 item 3).
                           (let [pre (identity/user-by-sso-email db email)]
                             (when-let [u (or pre (identity/user-by-email db email))]
                               (assoc (identity/public-user u)
                                      :disabled (:disabled? u)
                                      :has-password? (some? (:password-hash u))
                                      :linked? (some? (:sso-subject u))
                                      :pre-registered? (some? pre)))))}
                        who)]
          (if (seq (:findings decision))
            (refuse-oidc! exchange (:findings decision) audit-failed-login!)
            (let [{:keys [action account]} decision
                  result (case action
                           ;; Re-read rather than reusing the lookup's account:
                           ;; that one carries the three keys `resolve-identity`
                           ;; asked for, and a session user is the store's shape.
                           :login {:status :ok
                                   :user (identity/public-user
                                          (identity/user-by-id db (:id account)))}
                           :bind  (identity/bind-sso! conn (:id account)
                                                      (:issuer who) (:subject who))
                           :provision (identity/create-sso-user! conn who))]
              (if-let [error (:error result)]
                ;; A store refusal at this point is a race (two callbacks for
                ;; one new identity) or a link an administrator moved between
                ;; the read above and this write. Neither is something to tell
                ;; a browser about in detail.
                (refuse-oidc! exchange
                              [{:code :auth/identity-not-linked :severity :error
                                :path [:auth :oidc]
                                :message (str "this identity provider account could not be "
                                              "linked to a Studio user; an administrator "
                                              "must resolve it")}]
                              audit-failed-login!)
                (let [user (:user result)
                      session (identity/create-session! user)]
                  (audit-login! {:issuer (:issuer who) :subject (:subject who)})
                  (http/reply! exchange 302 "" "text/plain"
                               {"Location" (or return-to "/")
                                "Set-Cookie" (session-cookie session)}))))))))))
