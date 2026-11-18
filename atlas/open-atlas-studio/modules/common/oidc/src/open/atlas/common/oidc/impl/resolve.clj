(ns open.atlas.common.oidc.impl.resolve
  "Doc §8.16.4 rule 4: linking and provisioning, decided **once** for all three
   apps.

   Pure by construction — the caller passes two lookup functions and gets back
   an action to perform. That is what makes 'one implementation' true rather
   than aspirational: server keeps operators in SQLite and studio keeps users in
   Datahike, so the only thing they could ever share is the decision, and the
   decision is here.

   ## Why the order matters

   The five sub-rules are tried in order and the first hit wins. In particular
   the principal lookup comes *before* the domain check: an account already
   linked to `(issuer, subject)` logs in even if an administrator later narrowed
   `OIDC_ALLOWED_EMAIL_DOMAINS`, because that link was an explicit act. And the
   email match happens exactly once in an account's life (sub-rule 3, on an
   unlinked account with no password, or one an administrator pre-registered
   for SSO); from then on only sub-rule 1 applies,
   because email is an attribute that can be reassigned and a principal is not
   (D5.10).

   ## What we deliberately did not copy from OMA

   OMA's `accountLinking.trustedProviders` lets an IdP assertion take over a
   local password account with the same address. We do not (§8.16.1 rule 3):
   OMA is self-serve SaaS, we have an administrator in all three places, and
   takeover is an administrator's act. That is sub-rule 4, `identity-not-linked`."
  (:require [clojure.string :as str]
            [open.atlas.common.oidc.impl.finding :as finding]))

(defn- domain-of [email]
  (let [at (str/last-index-of (str email) "@")]
    (when (and at (< (inc at) (count email)))
      (str/lower-case (subs email (inc at))))))

(defn domain-allowed?
  "Empty allow list = unrestricted (§8.16.3)."
  [allowed email]
  (or (empty? allowed)
      (boolean (some #(= % (domain-of email)) allowed))))

(defn resolve-identity
  [{:keys [allowed-email-domains auto-provision?]}
   {:keys [by-principal by-email]}
   {:keys [issuer subject email]}]
  (if-let [linked (by-principal issuer subject)]
    ;; 4.1 — a disabled account gets the same refusal as a wrong password, on
    ;; purpose: "this account exists but is switched off" is not something a
    ;; login face should confirm to whoever is asking.
    (if (:disabled linked)
      {:findings [(finding/error :auth/invalid-credentials [:auth :oidc]
                                 "the credentials were not accepted")]}
      {:action :login :account linked})

    (if-not (domain-allowed? allowed-email-domains email)
      ;; 4.2
      {:findings [(finding/error :auth/oidc-domain-not-allowed [:auth :oidc :email]
                                 "this identity provider account's email domain is not permitted")]}

      (if-let [by-mail (by-email email)]
        (cond
          ;; 4.3 — the administrator pre-registered this address for SSO. The
          ;; caller writes (issuer, subject) onto the account and logs in.
          ;; `:pre-registered?` is 4.4's remedy (§8.16.9 item 3): an
          ;; administrator set this account's SSO email, which is the explicit
          ;; act that may link an account that has a password. The caller
          ;; clears the pre-registration on bind, so it is consumed once.
          (and (not (:linked? by-mail))
               (or (not (:has-password? by-mail)) (:pre-registered? by-mail)))
          (if (:disabled by-mail)
            ;; Same refusal as 4.1 and for the same reason; binding a principal
            ;; onto a switched-off account would hand it a session.
            {:findings [(finding/error :auth/invalid-credentials [:auth :oidc]
                                       "the credentials were not accepted")]}
            {:action :bind :account by-mail})

          :else
          ;; 4.4 — has a password, or is already linked to a *different*
          ;; principal (sub-rule 1 missed, so it cannot be this one). Both are
          ;; an administrator's job, not a login's.
          {:findings [(finding/error :auth/identity-not-linked [:auth :oidc]
                                     (str "an account with this address exists but is not "
                                          "linked to this identity provider account; an "
                                          "administrator must link it"))]})
        ;; 4.5
        (if auto-provision?
          {:action :provision}
          {:findings [(finding/error :auth/identity-not-provisioned [:auth :oidc]
                                     (str "there is no account for this identity provider "
                                          "account, and this application does not create "
                                          "accounts on sign-in"))]})))))
