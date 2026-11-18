(ns open.atlas.common.token
  "The token contract shared by every runtime instance.

   A token is an opaque bearer plus one instance-local ledger record:

   ```clojure
   {:token-id … :graph-id … :version :latest
    :status :active
    :created-at … :expires-at nil}
   ```

   One token authorises one graph. Studio's embedded runtime and a standalone
   Server use this same contract and implementation, but keep separate ledgers,
   peppers, serving pointers, and secrets."
  (:require [open.atlas.common.token.impl.finding :as finding]
            [open.atlas.common.token.impl.format :as format*]
            [open.atlas.common.token.impl.grant :as grant]))

;; --- bearer format and keyed digest ----------------------------------------

(def format-prefix format*/prefix)
(def min-secret-chars format*/min-secret-chars)
(def format-token format*/format-token)
(def parse-token format*/parse)
(def digest-input format*/digest-input)
(def digest format*/digest)
(def digest-matches? format*/digest-matches?)

;; --- single-graph authorisation --------------------------------------------

(def version-latest grant/version-latest)
(def version-spec? grant/version-spec?)
(def authorization-keys grant/authorization-keys)
(def record-keys grant/record-keys)
(def statuses grant/statuses)
(def validate-authorization grant/validate-authorization)
(def validate-record grant/validate-record)
(def expired? grant/expired?)
(def authorize grant/authorize)

;; --- findings ---------------------------------------------------------------

(def max-diagnostic-chars finding/max-diagnostic-chars)
(def error finding/error)
(def secret-safe-error finding/secret-safe-error)
(def finding-codes finding/codes)
(defn finding? [x] (finding/valid? x))
(def sort-findings finding/sort-findings)
