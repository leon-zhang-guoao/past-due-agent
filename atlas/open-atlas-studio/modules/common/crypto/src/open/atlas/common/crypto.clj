(ns open.atlas.common.crypto
  "Cryptographic primitives: hex, SHA-256, HMAC-SHA-256, and a constant-time
   comparison. Zero dependencies, no protocol knowledge, no key management.

   Two consumers so far, and that is exactly why this is its own module: the
   control plane's two signatures (`common/control-protocol`, D3.16) and the
   data plane's keyed token digest (`common/token`, S5.9). A second copy would
   not fail to compile — it would fail by having one of the two comparisons
   quietly degrade to `=`, which short-circuits on the first differing byte and
   therefore leaks how many bytes the attacker got right.

   MAC strings are `\"hmac-sha256:\" + 64 lowercase hex`. Shape is checked
   before any comparison, so a malformed MAC is refused as malformed rather
   than reported as a mismatch."
  (:require [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]
           [javax.crypto Mac SecretKeyFactory]
           [javax.crypto.spec PBEKeySpec SecretKeySpec]))

(def ^:private algorithm "HmacSHA256")

(def mac-prefix
  "The algorithm tag every MAC string carries. It is part of the value: a bare
   hex string cannot say which algorithm produced it, and \"we will just know\"
   stops being true the first time a second algorithm exists."
  "hmac-sha256:")

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn hex
  "bytes → lowercase hex."
  [^bytes bs]
  (let [sb (StringBuilder. (* 2 (alength bs)))]
    (dotimes [i (alength bs)]
      (let [b (bit-and (aget bs i) 0xff)]
        (when (< b 0x10) (.append sb \0))
        (.append sb (Integer/toString b 16))))
    (.toString sb)))

(defn unhex
  "64 lowercase hex chars → 32 bytes; anything else → nil. **Never throws**: a
   malformed value is a refusal, not an exception."
  ^bytes [^String s]
  (when (and (string? s) (re-matches #"[0-9a-f]{64}" s))
    (let [n  (quot (count s) 2)
          bs (byte-array n)]
      (dotimes [i n]
        (aset-byte bs i (unchecked-byte (Integer/parseInt (subs s (* 2 i) (+ 2 (* 2 i))) 16))))
      bs)))

(defn sha256-hex
  "text → 64 lowercase hex chars (no `sha256:` prefix)."
  [^String s]
  (hex (.digest (MessageDigest/getInstance "SHA-256") (utf8 s))))

(defn hmac-sha256
  "`(hmac-sha256 secret data)` → `\"hmac-sha256:<64 hex>\"`."
  [^String secret ^String data]
  (let [mac (Mac/getInstance algorithm)]
    (.init mac (SecretKeySpec. (utf8 secret) algorithm))
    (str mac-prefix (hex (.doFinal mac (utf8 data))))))

(defn well-formed-mac?
  "`\"hmac-sha256:\" + 64 lowercase hex`. Check the shape first and compare
   second, so a MAC that is not even the right shape never reaches the
   fixed-length comparison — and so the refusal can say \"malformed\" instead of
   \"does not match\"."
  [x]
  (boolean (and (string? x)
                (str/starts-with? x mac-prefix)
                (re-matches #"[0-9a-f]{64}" (subs x (count mac-prefix))))))

(defn constant-time=
  "Are two MAC strings equal? **Not `=`**: string comparison short-circuits at
   the first differing character, so its running time leaks how many characters
   the caller guessed right — and a MAC is something one can guess a character
   at a time.

   Decoded to bytes and handed to the JDK's `MessageDigest/isEqual`, so that
   *nobody has to read a hand-rolled XOR fold and believe it is constant time*;
   that one is a platform guarantee. A malformed input is false without
   entering the comparison at all."
  [^String a ^String b]
  (let [pa (when (well-formed-mac? a) (unhex (subs a (count mac-prefix))))
        pb (when (well-formed-mac? b) (unhex (subs b (count mac-prefix))))]
    (boolean (and pa pb (MessageDigest/isEqual pa pb)))))

;; --- 口令 -------------------------------------------------------------------
;; A MAC and a password hash are different jobs and the difference is the whole
;; point: a MAC must be fast, a password hash must be slow, because the
;; attacker's advantage is throughput. PBKDF2-SHA256 at 600k iterations is the
;; OWASP 2023 floor; the cost is ~0.3s per verification, which is a login and
;; not a hot path.
;;
;; **Why this lives in `common/crypto` and not in whichever app needs a login.**
;; There are two apps with local operator accounts — the Builder (apps/studio)
;; and the standalone Server. Two implementations would not fail to compile;
;; they would fail the way this module's opening note already describes, by one
;; of them quietly drifting — a lowered iteration count, a salt reused, a
;; comparison that short-circuits. The failure is invisible until it matters.
;; (apps/studio still carries its own copy while this one settles; converging
;; it is that app's own change, not a drive-by here.)

(def ^:private pbkdf2-algorithm "PBKDF2WithHmacSHA256")
(def ^:private pbkdf2-iterations 600000)
(def ^:private pbkdf2-key-bits 256)
(def ^:private pbkdf2-salt-bytes 16)
(def ^:private ^SecureRandom rng (SecureRandom.))

(def password-hash-prefix
  "The algorithm tag every password hash carries, for the same reason
   `mac-prefix` exists: a bare hash cannot say what produced it, and the day
   the parameters change, the stored value has to be self-describing or every
   existing account becomes unverifiable."
  "pbkdf2-sha256$")

(defn- b64 ^String [^bytes value]
  (.encodeToString (Base64/getEncoder) value))

(defn- unb64 ^bytes [^String value]
  (try (.decode (Base64/getDecoder) value) (catch IllegalArgumentException _ nil)))

(defn- derive-key ^bytes [^String plaintext ^bytes salt rounds]
  (let [spec (PBEKeySpec. (.toCharArray plaintext) salt rounds pbkdf2-key-bits)]
    (.getEncoded (.generateSecret (SecretKeyFactory/getInstance pbkdf2-algorithm) spec))))

(defn hash-password
  "plaintext → `\"pbkdf2-sha256$<rounds>$<b64 salt>$<b64 key>\"`.

   A fresh 128-bit salt per call, so two accounts with the same password do not
   have the same stored value — which is what keeps one cracked hash from being
   an answer about every other account."
  [^String plaintext]
  (let [salt (byte-array pbkdf2-salt-bytes)]
    (.nextBytes rng salt)
    (str password-hash-prefix pbkdf2-iterations "$" (b64 salt) "$"
         (b64 (derive-key plaintext salt pbkdf2-iterations)))))

(defn well-formed-password-hash?
  "Shape first, comparison second — see `well-formed-mac?`."
  [x]
  (boolean
   (and (string? x)
        (str/starts-with? x password-hash-prefix)
        (let [[rounds salt key*] (str/split (subs x (count password-hash-prefix)) #"\$")]
          (and rounds salt key*
               (re-matches #"[1-9][0-9]{0,8}" rounds)
               (some? (unb64 salt))
               (some? (unb64 key*)))))))

(defn verify-password
  "Does `plaintext` produce `stored`? **Never throws**: this sits on the login
   path, where an exception becomes a 500 and a 500 on a wrong password is an
   oracle of its own. Anything malformed is simply false.

   The rounds come from the stored value rather than from the constant above,
   so hashes written under an older cost still verify; re-hashing them is the
   caller's decision, made after a successful verification."
  [^String plaintext ^String stored]
  (boolean
   (when (and (string? plaintext) (well-formed-password-hash? stored))
     (let [[rounds salt key*] (str/split (subs stored (count password-hash-prefix)) #"\$")]
       (MessageDigest/isEqual (derive-key plaintext (unb64 salt) (Long/parseLong rounds))
                              (unb64 key*))))))
