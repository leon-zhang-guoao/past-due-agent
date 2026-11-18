(ns open.atlas.common.oidc.impl.jws
  "Compact JWS parsing and signature verification with `java.security` only
   (doc §8.16.5: no third-party JOSE library — one fewer supply-chain surface).

   Scope is deliberately the minimum an OIDC relying party needs: RS256
   (required) and ES256 (optional), both verified against a key rebuilt from a
   JWKS entry. There is no `alg` dispatch beyond those two, and in particular
   no `none` branch — the check is a whitelist, because the classic JWS defeat
   is a header the verifier is willing to believe.

   Everything here returns `nil` on failure rather than a reason: the caller
   maps every id_token failure to one code, `auth/invalid-oidc-response`
   (§8.16.6), precisely so that the browser cannot tell a wrong signature from
   a wrong audience."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.math BigInteger]
           [java.nio.charset StandardCharsets]
           [java.security AlgorithmParameters KeyFactory PublicKey Signature]
           [java.security.spec ECGenParameterSpec ECParameterSpec ECPoint ECPublicKeySpec
            RSAPublicKeySpec]
           [java.util Base64]))

(defn b64url-decode ^bytes [^String s]
  (.decode (Base64/getUrlDecoder) s))

(defn b64url-encode ^String [^bytes b]
  (.encodeToString (Base64/getUrlEncoder) b))

(defn b64url-encode-no-pad ^String [^bytes b]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn- jwk-b64-decode
  "Decode a JWK key parameter (`n`, `e`, `x`, `y`).

   RFC 7517 says base64url, but at least one production IdP (mycIS) publishes
   its RSA modulus in the *standard* alphabet (`+` and `/`). A strict url
   decoder refuses every such key, `key-for` finds nothing, and the whole
   sign-in dies as `auth/invalid-oidc-response` with no hint why. Key material
   is public and the alphabets do not overlap in meaning, so accepting both is
   safe here. It is deliberately NOT done in `b64url-decode`: JWS segments of
   an id_token stay strictly base64url."
  ^bytes [^String s]
  (b64url-decode (-> s (str/replace \+ \-) (str/replace \/ \_))))

(defn- positive-int ^BigInteger [^String b64]
  ;; `1` as the signum: JWK integers are unsigned big-endian, and a leading
  ;; 0x80 byte would otherwise be read as a negative modulus.
  (BigInteger. 1 (jwk-b64-decode b64)))

(defn parse
  "Compact JWS → `{:header {…} :payload {…} :signing-input bytes :signature bytes}`,
   or nil when it is not three dot-separated base64url parts of JSON."
  [^String token]
  (when (string? token)
    (let [parts (str/split token #"\." -1)]
      (when (= 3 (count parts))
        (try
          (let [[h p s] parts
                header  (json/parse-string (String. (b64url-decode h) StandardCharsets/UTF_8))
                payload (json/parse-string (String. (b64url-decode p) StandardCharsets/UTF_8))]
            (when (and (map? header) (map? payload))
              {:header        header
               :payload       payload
               :signing-input (.getBytes (str h "." p) StandardCharsets/UTF_8)
               :signature     (b64url-decode s)}))
          (catch Exception _ nil))))))

(defn- rsa-key ^PublicKey [jwk]
  (when (and (get jwk "n") (get jwk "e"))
    (.generatePublic (KeyFactory/getInstance "RSA")
                     (RSAPublicKeySpec. (positive-int (get jwk "n"))
                                        (positive-int (get jwk "e"))))))

(defn- p256-params ^ECParameterSpec []
  (let [ap (AlgorithmParameters/getInstance "EC")]
    (.init ap (ECGenParameterSpec. "secp256r1"))
    (.getParameterSpec ap ECParameterSpec)))

(defn- ec-key ^PublicKey [jwk]
  (when (and (get jwk "x") (get jwk "y")
             ;; ES256 is P-256 and nothing else; a JWK naming another curve is
             ;; not a key this verifier will use.
             (contains? #{nil "P-256"} (get jwk "crv")))
    (.generatePublic (KeyFactory/getInstance "EC")
                     (ECPublicKeySpec. (ECPoint. (positive-int (get jwk "x"))
                                                 (positive-int (get jwk "y")))
                                       (p256-params)))))

(defn public-key
  "JWK map → `java.security.PublicKey`, or nil if the JWK is not an RSA/EC key
   this module verifies."
  ^PublicKey [jwk]
  (try
    (case (get jwk "kty")
      "RSA" (rsa-key jwk)
      "EC"  (ec-key jwk)
      nil)
    (catch Exception _ nil)))

(defn- der-length [n]
  ;; DER short form below 128, else one length-of-length byte plus the value.
  ;; An ECDSA integer here is at most 33 bytes, so the long form is never hit,
  ;; but writing the branch costs nothing and reading a truncated one costs a
  ;; debugging session.
  (if (< n 128)
    [n]
    (let [bs (loop [v n acc ()] (if (zero? v) acc (recur (quot v 256) (conj acc (mod v 256)))))]
      (into [(bit-or 0x80 (count bs))] bs))))

(defn- der-integer [^bytes raw]
  (let [v  (vec raw)
        v  (let [t (drop-while zero? v)] (if (seq t) (vec t) [0]))
        ;; DER integers are signed: a high bit in the first byte needs a 0x00
        ;; pad, or the value reads as negative.
        v  (if (>= (bit-and (first v) 0xff) 0x80) (into [0] v) v)]
    (into (into [0x02] (der-length (count v))) v)))

(defn raw->der
  "ES256 signatures arrive as raw `r || s` (64 bytes); `SHA256withECDSA` wants
   a DER SEQUENCE of two INTEGERs (RFC 7515 §3.4 vs the JCA provider)."
  ^bytes [^bytes raw]
  (let [n (quot (alength raw) 2)]
    (when (and (pos? n) (= (alength raw) (* 2 n)))
      (let [r (der-integer (byte-array (take n raw)))
            s (der-integer (byte-array (drop n raw)))
            body (into (vec r) s)]
        (byte-array (map unchecked-byte
                         (into (into [0x30] (der-length (count body))) body)))))))

(def ^:private algorithms
  {"RS256" "SHA256withRSA"
   "ES256" "SHA256withECDSA"})

(defn supported-alg? [alg] (contains? algorithms alg))

(defn verify?
  "Verify a parsed JWS against a public key. False on any failure, including a
   header `alg` outside the whitelist."
  [{:keys [header signing-input signature]} ^PublicKey key]
  (boolean
   (when-let [jca (get algorithms (get header "alg"))]
     (try
       (let [sig (doto (Signature/getInstance jca)
                   (.initVerify key)
                   (.update ^bytes signing-input))
             bytes' (if (= "ES256" (get header "alg"))
                      (raw->der signature)
                      signature)]
         (boolean (and bytes' (.verify sig ^bytes bytes'))))
       (catch Exception _ false)))))
