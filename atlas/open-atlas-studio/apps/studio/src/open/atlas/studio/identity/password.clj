(ns open.atlas.studio.identity.password
  "Dependency-free PBKDF2 password hashing for Studio local identities."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest SecureRandom]
           [java.util Base64]
           [javax.crypto SecretKeyFactory]
           [javax.crypto.spec PBEKeySpec]))

(set! *warn-on-reflection* true)

(def ^:private iterations 600000)
(def ^:private key-bits 256)
(def ^:private salt-bytes 16)
(def ^:private ^SecureRandom rng (SecureRandom.))

(defn- b64 [^bytes value] (.encodeToString (Base64/getEncoder) value))
(defn- unb64 ^bytes [^String value] (.decode (Base64/getDecoder) value))

(defn- derive-key ^bytes [^String plaintext ^bytes salt rounds]
  (let [spec (PBEKeySpec. (.toCharArray plaintext) salt rounds key-bits)]
    (.getEncoded (.generateSecret (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256") spec))))

(defn hash-password [plaintext]
  (let [salt (byte-array salt-bytes)]
    (.nextBytes rng salt)
    (str "pbkdf2-sha256$" iterations "$" (b64 salt) "$"
         (b64 (derive-key plaintext salt iterations)))))

(defn verify-password [plaintext stored]
  (try
    (let [[algorithm rounds salt expected] (str/split (str stored) #"\$")]
      (and (= "pbkdf2-sha256" algorithm)
           rounds salt expected
           (MessageDigest/isEqual (unb64 expected)
                                  (derive-key plaintext (unb64 salt) (Long/parseLong rounds)))))
    (catch Exception _ false)))
