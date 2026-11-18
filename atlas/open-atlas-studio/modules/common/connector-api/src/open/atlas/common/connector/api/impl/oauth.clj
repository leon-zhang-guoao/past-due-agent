(ns open.atlas.common.connector.api.impl.oauth
  "OAuth2 access-token manager for outbound api sources. Migrated from
   open-atlas ent.dx.http.oauth, with the platform's leak discipline:
   no logging, and token-endpoint failures throw ex-info whose message and
   ex-data carry the HTTP status and grant only — never the request params
   (they hold the client secret), never the response body.

   Lifecycle (`access-token`):
     - one cached token per DISTINCT resolved auth config (keyed by the
       resolved config itself — rotating a secret = a new config = a fresh
       entry);
     - a token is reused until :expiry_skew_s seconds BEFORE expires_in;
     - refresh prefers grant_type=refresh_token when an RT is held, falling
       back to the primary grant when the IdP rejects it (except when the
       primary grant IS refresh_token, where the seed RT is retried once);
     - a token response carrying a new refresh_token rotates it (RFC 6749 §6);
     - acquisition is single-flight per config (locking + double-check);
     - `invalidate!` drops a REJECTED access token (the 401 path) but keeps
       the refresh token — only the access token was proven bad."
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [open.atlas.common.connector.api.impl.conn :as conn]
            [open.atlas.common.connector.api.impl.interp :as interp]))

(def grant-types #{"client_credentials" "password" "refresh_token"})

(defn now-ms
  "Wall clock, in a var so tests can freeze time."
  []
  (System/currentTimeMillis))

(def credential-keys
  "`:auth` keys api-v1 marks with a star: whole-value `${VAR}` only, a
   literal rejected at write time. `:client_id`/`:username` joined the list
   by owner ruling 2026-08-28 — the list is drawn around 'part of the
   authentication material', not around 'is a secret', so the public half of
   each pair is covered too.

   `:client_id` is also where the substitution bug lived: the migrated
   corpus writes it as `${SE_CLIENT_ID}`, and with nothing resolving it the
   literal placeholder went to the IdP as the client id — under
   `:client_auth \"basic\"`, straight into the Basic header."
  [:client_id :client_secret :username :password :refresh_token :value])

(def config-keys
  "Non-credential `:auth` keys that may carry a `${VAR}`. Ordinary
   configuration, so an EMBEDDED var is allowed here (`${IDP_HOST}/token`)
   where a credential key would be refused."
  [:token_url :scope :audience])

(def substituted-keys
  "Every `:auth` key resolved at request time. Credential keys are included:
   the star governs WRITE time (whole-value only), while at run time they go
   through the same substitution as the rest — which is what lets an
   unresolved one be named in the failure instead of silently blanked."
  (into config-keys credential-keys))

(defn- substituted [v] (:value (interp/substitute v)))

(defn missing-vars
  "Names of `${VAR}` references in an `:auth` block that resolve to nothing
   — names only, never values (a name is dialect configuration). Callers
   refuse with `:connection` rather than calling out half-configured."
  [auth]
  (when (seq auth)
    (interp/missing-vars (mapv #(get auth %) substituted-keys))))

(defn resolve-auth-config
  "Resolve a raw `:auth` block: ${VAR} secrets resolved (unresolved → nil,
   caught by impl.validate), the non-credential `var-bearing-keys`
   substituted (unresolved → empty, reported by `missing-vars`), defaults
   applied. nil when `auth` is nil/blank.
   Reads env but never the network — safe at validate time."
  [auth]
  (when (seq auth)
    (-> auth
        (update :client_secret interp/resolve-value)
        (update :password interp/resolve-value)
        (update :refresh_token interp/resolve-value)
        (update :client_id substituted)
        (update :token_url substituted)
        (update :username substituted)
        (update :scope substituted)
        (update :audience substituted)
        (update :grant_type #(or (not-empty (str %)) "client_credentials"))
        (update :client_auth #(or (not-empty (str %)) "body"))
        (update :expiry_skew_s #(or % 60))
        (update :timeout_ms #(or % 10000))
        (update :header_name #(or (not-empty (str %)) "Authorization"))
        (update :header_prefix #(if (nil? %) "Bearer" (str %))))))

;; ---------------------------------------------------------------------------
;; Token endpoint
;; ---------------------------------------------------------------------------

(defn- client-cred-params
  [{:keys [client_id client_secret client_auth]}]
  (when (= "body" client_auth)
    (cond-> {}
      (some? client_id)     (assoc "client_id" client_id)
      (some? client_secret) (assoc "client_secret" client_secret))))

(defn- grant-params
  "Grant-specific form params. `grant` is passed explicitly (not read off the
   config) because a refresh call uses grant_type=refresh_token whatever the
   primary grant is."
  [resolved grant refresh-token]
  (case grant
    "client_credentials" {"grant_type" "client_credentials"}
    "password"           {"grant_type" "password"
                          "username"   (:username resolved)
                          "password"   (:password resolved)}
    "refresh_token"      {"grant_type"    "refresh_token"
                          "refresh_token" refresh-token}))

(defn- token-request-form [resolved grant refresh-token]
  (merge (grant-params resolved grant refresh-token)
         (client-cred-params resolved)
         (when-let [s (:scope resolved)] {"scope" s})
         (when-let [a (:audience resolved)] {"audience" a})
         (:extra_params resolved)))

(defn- parse-expires-in
  "expires_in seconds → long; some IdPs send it as a string. nil/garbage →
   nil (a token without expiry is kept until a 401 invalidates it)."
  [v]
  (try
    (cond (number? v) (long v)
          (string? v) (Long/parseLong (str/trim v))
          :else nil)
    (catch Exception _ nil)))

(defn fetch-token!
  "ONE token-endpoint roundtrip. Returns the new token state
   {:access-token .. :expires-at .. :refresh-token ..}; `prev-rt` survives as
   the state's refresh token when the response does not rotate it. Throws
   ex-info on non-2xx or a body without access_token — status + grant only,
   never the form params or the response body (leak discipline)."
  [resolved grant refresh-token prev-rt]
  (let [{:keys [token_url timeout_ms client_id client_secret client_auth]} resolved
        basic? (and (= "basic" client_auth) (some? client_id))
        resp   (http/post token_url
                          (cond-> {:form-params      (token-request-form resolved grant refresh-token)
                                   :accept           :json
                                   :socket-timeout   timeout_ms
                                   :conn-timeout     timeout_ms
                                   :connection-request-timeout timeout_ms
                                   :throw-exceptions false
                                   :connection-manager (conn/select-manager
                                                        (:tls resolved) (:ssl_insecure? resolved))}
                            basic? (assoc :basic-auth [client_id (or client_secret "")])))
        status (:status resp)
        parsed (try (json/parse-string (:body resp) true)
                    (catch Exception _ nil))]
    (when-not (and status (<= 200 status 299))
      (throw (ex-info (str "OAuth2 token request failed: HTTP " status)
                      {:status status :grant grant})))
    (let [access (:access_token parsed)]
      (when (str/blank? (str access))
        (throw (ex-info "OAuth2 token response has no access_token"
                        {:status status :grant grant})))
      (let [sec      (parse-expires-in (:expires_in parsed))
            cfg-skew (long (:expiry_skew_s resolved))
            ;; Clamp the effective skew to at most half the token lifetime:
            ;; with a short IdP token and the default 60 s skew, expires-at -
            ;; skew would equal the fetch instant and every request would
            ;; miss the cache.
            eff-skew (when sec (min cfg-skew (quot sec 2)))]
        {:access-token  access
         :acquired-at   (now-ms)
         :expires-at    (when sec (+ (now-ms) (* 1000 sec)))
         :expiry-skew-s eff-skew
         :refresh-token (or (not-empty (str (:refresh_token parsed))) prev-rt)}))))

;; ---------------------------------------------------------------------------
;; Cache + single-flight acquisition
;; ---------------------------------------------------------------------------

(defonce ^{:private true
           :doc "resolved auth config → {:lock Object :state (atom token-state)}.
   Keyed by the resolved config VALUE, not its 32-bit hash — a hash collision
   between two configs would share one token across sources.
   The outer atom only manages entry creation; token state lives in the
   entry's own atom so token writes never contend with entry creation."}
  token-cache
  (atom {}))

(defn reset-cache!
  "Test/REPL hook: drop every cached token."
  []
  (reset! token-cache {}))

(defn- cache-entry [k]
  (or (get @token-cache k)
      (get (swap! token-cache
                  (fn [m] (if (contains? m k)
                            m
                            (assoc m k {:lock (Object.) :state (atom nil)}))))
           k)))

(def ^:private failure-cooldown-ms
  "After a terminal acquisition failure, callers fail fast with the cached
   error for this long before another IdP roundtrip is attempted — an IdP
   outage degrades to fast failure instead of every caller serializing
   behind the per-config lock."
  10000)

(defn- live? [state resolved]
  (and (:access-token state)
       (or (nil? (:expires-at state))
           (< (now-ms) (- (:expires-at state)
                          (* 1000 (long (or (:expiry-skew-s state)
                                            (:expiry_skew_s resolved)))))))))

(defn- cooling-down [state]
  (when-let [t (:failed-at state)]
    (when (< (- (now-ms) t) failure-cooldown-ms)
      (:last-error state))))

(defn- acquire-token!
  "Fresh token state, preferring the refresh_token grant when an RT is held.
   A rejected RT falls back to the primary grant — except when the primary
   grant IS refresh_token: there the config's seed RT is retried once
   (rotation may not have committed server-side), then the failure
   propagates."
  [resolved prev]
  (let [grant   (:grant_type resolved)
        seed-rt (:refresh_token resolved)
        rt      (or (:refresh-token prev) seed-rt)]
    (if rt
      (try
        (fetch-token! resolved "refresh_token" rt rt)
        (catch Exception e
          (cond
            (not= grant "refresh_token")
            (fetch-token! resolved grant nil nil)

            (and seed-rt (not= rt seed-rt))
            (fetch-token! resolved "refresh_token" seed-rt seed-rt)

            :else (throw e))))
      (fetch-token! resolved grant nil nil))))

(defn access-token
  "The current access token for an `:auth` config — cached until near
   expiry, refreshed/acquired under a per-config single-flight lock. Throws
   ex-info when the token endpoint fails (see fetch-token!)."
  [auth]
  (let [resolved (resolve-auth-config auth)
        {:keys [lock state]} (cache-entry resolved)
        s   @state
        err (cooling-down s)]
    (cond
      (live? s resolved) (:access-token s)
      err (throw err)
      :else
      (locking lock
        ;; double-check: another thread may have refreshed (or recorded a
        ;; failure) while we waited on the lock.
        (let [s   @state
              err (cooling-down s)]
          (cond
            (live? s resolved) (:access-token s)
            err (throw err)
            :else
            (try
              (:access-token (reset! state (acquire-token! resolved s)))
              (catch Exception e
                (swap! state assoc :last-error e :failed-at (now-ms))
                (throw e)))))))))

(defn invalidate!
  "Drop a REJECTED access token (the 401 path) so the next access-token call
   re-acquires. Only clears when the cache still holds `token`; the refresh
   token survives — the 401 proved the ACCESS token bad, nothing more."
  [auth token]
  (let [resolved (resolve-auth-config auth)]
    (when-let [{:keys [state]} (get @token-cache resolved)]
      (swap! state (fn [s]
                     (if (= token (:access-token s))
                       (assoc s :access-token nil :expires-at nil)
                       s))))))

(def ^:private fresh-token-window-ms
  "How long after acquisition a token counts as too young to have expired."
  10000)

(defn fresh-token?
  "True when `token` is still the cached access token for `auth` AND was
   issued less than 10 s ago. The 401 refresh-and-retry loop assumes 401
   means 'expired token', but real gateways also answer 401 for
   AUTHORIZATION decisions about the request itself; a token issued seconds
   ago cannot have expired, so the caller skips the refresh and reports the
   401 as what it is (api-v1 conformance: refresh-and-retry at most once)."
  [auth token]
  (let [resolved (resolve-auth-config auth)]
    (boolean
     (when-let [{:keys [state]} (get @token-cache resolved)]
       (let [{:keys [access-token acquired-at]} @state]
         (and (= token access-token)
              acquired-at
              (< (- (now-ms) acquired-at) fresh-token-window-ms)))))))

(defn bearer-header
  "Request header map for a token: {header-name \"<prefix> <token>\"}
   (default {\"Authorization\" \"Bearer <token>\"}); a blank :header_prefix
   sends the bare token. Pure — no fetch."
  [auth token]
  (let [{:keys [header_name header_prefix]} (resolve-auth-config auth)]
    {header_name (if (str/blank? header_prefix)
                   (str token)
                   (str header_prefix " " token))}))

(defn api-key-header
  "Header map for the \"api_key\" auth variant (api-v1 :auth table):
   :value (required, ${VAR}-resolved), :header_name (default
   \"Authorization\"), :header_prefix (default: no prefix)."
  [auth]
  (let [value  (interp/resolve-value (:value auth))
        hname  (or (not-empty (str (:header_name auth))) "Authorization")
        prefix (not-empty (str (:header_prefix auth)))]
    {hname (if prefix (str prefix " " value) (str value))}))
