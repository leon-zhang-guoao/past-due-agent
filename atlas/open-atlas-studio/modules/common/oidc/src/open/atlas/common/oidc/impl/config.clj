(ns open.atlas.common.oidc.impl.config
  "The configuration contract of doc §8.16.3, read the same way in all three
   apps so that one IdP registration can be copied between them.

   Three states, and the difference between the second and third is the whole
   point of the section:

   | env | result |
   |---|---|
   | discovery URL / client id / client secret not all present | `nil` — SSO is **not enabled**, and that is not an error |
   | those three present, `OIDC_REDIRECT_URI` absent | `{:findings [...]}` with `:oidc.config/missing` — **refuse to start**, do not guess |
   | all four present | `{:config {...}}` |

   Guessing the redirect URI is the failure this refusal exists to prevent: a
   guessed value is accepted by the app and rejected by the IdP, at which point
   the diagnosis lives in someone else's logs.

   `OIDC_ALLOWED_EMAIL_DOMAINS` defaults to **empty = unrestricted**, unlike
   OMA's `tdsynnex.com` (§8.16.1 rule 3): a factory kernel does not ship one
   company's domain as a default."
  (:require [clojure.string :as str]
            [open.atlas.common.oidc.impl.finding :as finding]))

(defn- env
  "Read `k` from an env map that may be keyed by string or keyword. Values are
   trimmed and a blank value counts as absent — an env var set to the empty
   string is how a deployment turns a setting off, not a setting of \"\"."
  [m k]
  ;; `(System/getenv)` is a Java map that throws ClassCastException on a
  ;; non-String key, so the keyword lookup must not reach it (studio boots
  ;; with that map directly). Try the string key first and only fall back to
  ;; the keyword when the map is a Clojure one.
  (let [v (or (get m k)
              (when (map? m) (get m (keyword k))))]
    (when (and (string? v) (not (str/blank? v)))
      (str/trim v))))

(defn- csv
  "Split a comma-separated env value, dropping blanks."
  [s]
  (if (nil? s)
    []
    (into [] (comp (map str/trim) (remove str/blank?)) (str/split s #","))))

(defn- dedupe-keep-order [xs]
  (into [] (distinct) xs))

(def ^:private base-scopes ["openid" "email" "profile"])

(def ^:private default-fallback-claims ["upn" "preferred_username" "sub"])

(defn- normalize-domain
  "Domains compare lowercase and without a leading `@`, so that both
   `@example.com` and `Example.COM` in the env mean the same allow-list entry."
  [d]
  (-> d str/trim str/lower-case (str/replace #"^@" "")))

(defn config
  "env map → `{:config …}` / `{:findings […]}` / `nil` (§8.16.3)."
  [env-map]
  (let [discovery (env env-map "OIDC_DISCOVERY_URL")
        client-id (env env-map "OIDC_CLIENT_ID")
        secret    (env env-map "OIDC_CLIENT_SECRET")]
    (cond
      ;; Not all three: SSO is simply off. The login page shows no button and
      ;; the two routes answer 404 `auth/oidc-disabled` — not a finding.
      (not (and discovery client-id secret))
      nil

      (nil? (env env-map "OIDC_REDIRECT_URI"))
      {:findings [(finding/error :oidc.config/missing [:auth :oidc :redirect-uri]
                                 (str "OIDC_DISCOVERY_URL, OIDC_CLIENT_ID and "
                                      "OIDC_CLIENT_SECRET are set, so single sign-on is "
                                      "enabled and OIDC_REDIRECT_URI is required; it is "
                                      "an absolute URL and is not guessed"))]}

      :else
      {:config
       {:discovery-url        discovery
        :client-id            client-id
        :client-secret        secret
        :redirect-uri         (env env-map "OIDC_REDIRECT_URI")
        :provider-id          (or (env env-map "OIDC_PROVIDER_ID") "oidc")
        :provider-name        (or (env env-map "OIDC_PROVIDER_NAME") "Single Sign-On")
        ;; Optional: when absent, the discovery document's `issuer` is the
        ;; authority (§8.16.3).
        :issuer               (env env-map "OIDC_ISSUER")
        :scopes               (dedupe-keep-order
                               (concat base-scopes
                                       (csv (env env-map "OIDC_ADDITIONAL_SCOPES"))))
        :identifying-claim    (or (env env-map "OIDC_CLAIM_IDENTIFYING_USER") "email")
        :fallback-claims      (let [fs (csv (env env-map "OIDC_FALLBACK_CLAIMS_IDENTIFYING_USER"))]
                                (if (seq fs) fs default-fallback-claims))
        ;; Empty = unrestricted (§8.16.3).
        :allowed-email-domains (mapv normalize-domain
                                     (csv (env env-map "OIDC_ALLOWED_EMAIL_DOMAINS")))}})))
