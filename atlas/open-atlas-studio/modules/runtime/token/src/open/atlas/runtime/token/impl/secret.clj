(ns open.atlas.runtime.token.impl.secret
  "Where the randomness comes from, and where the token id comes from.

   ## The secret

   256 bits from `SecureRandom`, base64url without padding — 43 characters,
   which is exactly `common/token`'s floor. `SecureRandom` and not `Random`:
   the latter is a linear congruential generator whose entire future is
   recoverable from a couple of outputs, and a bearer token is the one place
   where that is not an abstract concern.

   ## The token id is derived, not random

   ```
   token-id = \"t\" + first 32 hex of sha256(instance-id, actor, request-id)
   ```

   **This is what makes issuance idempotent without a marker file.** The
   alternative — random id, then a separate `requests/` index — has a crash
   window: write the record, die before the marker, and a retry mints a
   *second* token that nobody holds the secret to, while it goes on pinning
   versions against reclamation forever. Deriving the id means a retry lands on
   the same row, sees it already exists, and reports a replay.

   Hashing the actor in as well as the request id means two callers who happen
   to choose the same request id do not collide into each other's token; a
   caller can only ever collide with itself, which is the intent.

   A derived id is not a weakness: the id is **public** by ruling (it is what
   lookups, audits and operator reports name). The unguessable half is the
   secret, and that half is random."
  (:require [open.atlas.common.crypto :as crypto]
            [open.atlas.common.dialect :as dialect])
  (:import [java.security SecureRandom]
           [java.util Base64]))

(def secret-bytes 32)

(def ^:private ^SecureRandom rng (SecureRandom.))

(defn generate-secret
  "256 bits of CSPRNG output, base64url, unpadded → 43 characters."
  []
  (let [bs (byte-array secret-bytes)]
    (.nextBytes rng bs)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs)))

(defn derive-token-id
  "`{:instance-id :actor :request-id}` → a deterministic, public token id."
  [{:keys [instance-id actor request-id]}]
  (str "t" (subs (crypto/sha256-hex
                  (dialect/canonical-str ["token-id-v1" instance-id actor request-id]))
                 0 32)))
