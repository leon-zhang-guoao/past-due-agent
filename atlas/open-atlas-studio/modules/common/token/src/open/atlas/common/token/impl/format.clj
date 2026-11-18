(ns open.atlas.common.token.impl.format
  "The token's external form and its keyed digest (S5.9).

   ```
   oat_v1.<token-id>.<secret>
   ```

   Three parts, two dots. The **token-id is public** — it is what audits,
   lookups and operator reports name — and the **secret never leaves the
   issuing response**. Carrying the id in the presented value is what lets the
   ledger find the one candidate row before doing any cryptography: without it
   verification would have to compute a digest against every row, which is both
   slow and a much larger timing surface.

   ## The digest

   ```
   HMAC-SHA-256(instance-token-pepper,
                canonical [\"token-v1\" instance-id token-id secret])
   ```

   **The input binds instance-id and token-id, and that is not decoration.**
   Without `instance-id`, the same secret digests to the same value on two
   machines — and S5.8 says a token is valid only where it was issued, which
   would then rest on nothing but the ledger not having a row. Without
   `token-id`, one row's secret has a chance of validating against another row.

   **Canonical EDN rather than concatenation** (the same reason as D3.16's
   signing inputs): `(str a b)` maps [\"x\" \"yz\"] and [\"xy\" \"z\"] onto the same
   bytes, so two different inputs would share one digest. A canonical EDN
   vector is self-delimiting.

   ## The secret is not in any finding here

   Every refusal about a presented value is built with
   `finding/secret-safe-error`, which has no slot for a value at all (S5.2).
   That is deliberately structural: `redact` would work, but it would work by
   being remembered every time."
  (:require [clojure.string :as str]
            [open.atlas.common.crypto :as crypto]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.token.impl.finding :as f]))

(def prefix "oat_v1")

(def digest-domain
  "The domain tag that opens every digest input. It exists so a digest computed
   for a token can never collide with a digest computed for something else that
   happens to hash the same tuple."
  "token-v1")

(def min-secret-chars
  "256 bit encoded as base64url is 43 characters. The ruling says **at least**
   256 bit of cryptographically secure randomness; this module can only check
   the length — that the bytes came from a CSPRNG is the issuer's obligation
   (`runtime/token`), and it is untestable from a value alone."
  43)

(def ^:private id-pattern     #"[A-Za-z0-9_-]{1,128}")
(def ^:private secret-pattern #"[A-Za-z0-9_-]{1,512}")

(defn format-token
  "`(format-token token-id secret)` → the value handed to the consumer **once**."
  [token-id secret]
  (str prefix "." token-id "." secret))

(defn parse
  "presented value → `{:token-id … :secret …}` or `{:findings […]}`.

   Splits on the **first two** dots only, so a secret containing a dot would
   still round-trip; the character classes forbid it anyway, and being explicit
   here means the shape check is what refuses such a value, not the split."
  [presented]
  (let [bad (fn [code msg] {:findings [(f/secret-safe-error code [:token] msg)]})]
    (if-not (string? presented)
      (bad :token.format/malformed "the presented credential is not a string")
      (let [parts (str/split presented #"\." 3)]
        (cond
          (not= 3 (count parts))
          (bad :token.format/malformed
               (str "the credential is not " prefix ".<token-id>.<secret>"))

          (not= prefix (nth parts 0))
          (bad :token.format/malformed
               (str "unknown credential format prefix; expected " prefix))

          (not (re-matches id-pattern (nth parts 1)))
          (bad :token.format/malformed "the token id is not a well-formed identifier")

          (not (re-matches secret-pattern (nth parts 2)))
          (bad :token.format/malformed "the secret is not a well-formed opaque value")

          (< (count (nth parts 2)) min-secret-chars)
          (bad :token.format/weak-secret
               (str "the secret is shorter than " min-secret-chars
                    " characters, i.e. below the 256-bit floor"))

          :else {:token-id (nth parts 1) :secret (nth parts 2)})))))

(defn digest-input
  "`{:instance-id :token-id :secret}` → the exact text the HMAC is computed over."
  [{:keys [instance-id token-id secret]}]
  (dialect/canonical-str [digest-domain instance-id token-id secret]))

(defn digest
  "`(digest pepper parts)` → `\"hmac-sha256:<64 hex>\"`."
  [^String pepper parts]
  (crypto/hmac-sha256 pepper (digest-input parts)))

(defn digest-matches?
  "Does the presented material digest to the value the ledger stored?
   **Constant-time** (see `common/crypto`); a stored value that is not even a
   well-formed MAC compares false rather than throwing."
  [^String pepper parts ^String stored]
  (crypto/constant-time= (digest pepper parts) stored))
