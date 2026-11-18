(ns open.atlas.common.oidc.impl.finding
  "This module's finding constructor and its published `:code` registry
   (finding-v1 §3: every module registers its own domain).

   Two domains appear here on purpose (doc 8.16.6): `oidc.config/*` is a
   startup refusal, and `auth/*` is shared verbatim with the password login
   face of each app — a caller routing on `:code` must not have to know whether
   the rejection came from a password check or from an id_token check. That is
   also why `auth/invalid-credentials` is in this list: a disabled linked
   account gets the *same* refusal as a wrong password (§8.16.4 rule 4.1).

   Nothing built here may carry the IdP response, the authorization code, the
   client secret, or any token — §8.16.4 rule 2 and §8.16.7. The constructors
   below have no slot for a value at all, which is the cheapest way to keep
   that true: not a redaction that must be remembered, but an absent argument."
  (:require [clojure.string :as str]))

(def codes
  "Published `:code`s (doc 8.16.6). Once published they are not renamed;
   producing an unregistered code is an implementation bug."
  #{:oidc.config/missing              ; three keys present but redirect URI absent
    :auth/oidc-disabled               ; SSO routes hit while SSO is not configured
    :auth/invalid-oidc-response       ; missing state/code, exchange failed, id_token bad
    :auth/expired-oidc-transaction    ; unknown / expired / already-consumed state
    :auth/invalid-oidc-claims         ; no `sub`, or identifying claim and all fallbacks absent
    :auth/oidc-domain-not-allowed     ; email domain outside the allow list
    :auth/identity-not-linked         ; email matched an account that has a password
    :auth/identity-not-provisioned    ; no account and this app does not auto-provision
    :auth/invalid-credentials         ; linked account is disabled (same refusal as a bad password)
    :auth/unavailable-idp})           ; discovery / token / JWKS unreachable or not JSON

(defn error
  "Build a finding (finding-v1 §2). There is deliberately no `:got` parameter:
   everything this module could put there — the IdP body, the code, the token —
   is in §5's never-present class."
  [code path message]
  (assert (contains? codes code) (str "unregistered oidc finding code: " code))
  {:code code :severity :error :path path :message message})

(defn valid?
  "Is x a well-formed finding of this domain (finding-v1 §2 closed key set)?"
  [x]
  (boolean
   (and (map? x)
        (contains? codes (:code x))
        (contains? #{:error :warning :info} (:severity x))
        (vector? (:path x))
        (string? (:message x))
        (empty? (remove #{:code :severity :path :message :expected :got :refs :files}
                        (keys x))))))

(defn sort-findings
  "Deterministic order (finding-v1 §6)."
  [fs]
  (vec (sort-by (fn [f] [(pr-str (:path f)) (pr-str (:code f)) (:message f)]) fs)))

(defn blank? [s] (or (nil? s) (not (string? s)) (str/blank? s)))
