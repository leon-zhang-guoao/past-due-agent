(ns open.atlas.common.oidc.fake-idp
  "A local identity provider for the tests (doc §8.16.5: the round trip is
   proved against a real HTTP conversation, with keys generated in the test).

   `com.sun.net.httpserver` on a loopback port, three endpoints — discovery,
   token, JWKS — and a mutable `control` atom the test steers between calls.
   The point is that the module talks JSON over a socket to something it did
   not write: a fake built out of function stubs would pass while, say, the
   form encoding of the token request was wrong."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io ByteArrayOutputStream]
           [java.math BigInteger]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.security KeyPair KeyPairGenerator Signature]
           [java.security.interfaces ECPublicKey RSAPublicKey]
           [java.security.spec ECGenParameterSpec]
           [java.util Base64]))

;; --- keys and token minting --------------------------------------------------

(defn rsa-keypair ^KeyPair []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))))

(defn ec-keypair ^KeyPair []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "EC")
                      (.initialize (ECGenParameterSpec. "secp256r1")))))

(defn- b64url ^String [^bytes b]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn- unsigned ^bytes [^BigInteger n size]
  ;; BigInteger#toByteArray may carry a leading sign byte, and EC coordinates
  ;; must be left-padded to the field size; JWK integers are neither.
  (let [bs (.toByteArray n)
        bs (if (and (> (alength bs) 1) (zero? (aget bs 0))) (byte-array (rest bs)) bs)]
    (if (and size (< (alength bs) size))
      (let [out (byte-array size)]
        (System/arraycopy bs 0 out (- size (alength bs)) (alength bs))
        out)
      bs)))

(defn rsa-jwk [^KeyPair kp kid]
  (let [pk ^RSAPublicKey (.getPublic kp)]
    {"kty" "RSA" "use" "sig" "alg" "RS256" "kid" kid
     "n" (b64url (unsigned (.getModulus pk) nil))
     "e" (b64url (unsigned (.getPublicExponent pk) nil))}))

(defn ec-jwk [^KeyPair kp kid]
  (let [w (.getW ^ECPublicKey (.getPublic kp))]
    {"kty" "EC" "use" "sig" "alg" "ES256" "crv" "P-256" "kid" kid
     "x" (b64url (unsigned (.getAffineX w) 32))
     "y" (b64url (unsigned (.getAffineY w) 32))}))

(defn- der->raw
  "JCA emits ECDSA signatures in DER; JWS wants raw r||s (RFC 7515 §3.4)."
  ^bytes [^bytes der]
  (let [b (vec (map #(bit-and % 0xff) der))
        ;; SEQUENCE, length, INTEGER r, INTEGER s — lengths are short form here.
        rlen (nth b 3)
        r    (subvec b 4 (+ 4 rlen))
        slen (nth b (+ 5 rlen))
        s    (subvec b (+ 6 rlen) (+ 6 rlen slen))
        pad  (fn [v] (let [v (vec (drop-while zero? v))]
                       (into (vec (repeat (- 32 (count v)) 0)) v)))]
    (byte-array (map unchecked-byte (into (pad r) (pad s))))))

(defn sign-jwt
  "Mint a compact JWS. `alg` is \"RS256\" or \"ES256\"; `tamper?` flips a
   signature byte so that a well-formed token fails verification."
  ([^KeyPair kp kid alg claims] (sign-jwt kp kid alg claims false))
  ([^KeyPair kp kid alg claims tamper?]
   (let [header  (b64url (.getBytes (json/generate-string
                                     (cond-> {"alg" alg "typ" "JWT"} kid (assoc "kid" kid)))
                                    StandardCharsets/UTF_8))
         payload (b64url (.getBytes (json/generate-string claims) StandardCharsets/UTF_8))
         input   (.getBytes (str header "." payload) StandardCharsets/UTF_8)
         sig     (doto (Signature/getInstance (if (= alg "ES256") "SHA256withECDSA" "SHA256withRSA"))
                   (.initSign (.getPrivate kp))
                   (.update input))
         raw     (cond-> (.sign sig) (= alg "ES256") der->raw)
         raw     (if tamper?
                   (let [c (aclone ^bytes raw)]
                     (aset-byte c 0 (unchecked-byte (bit-xor (aget c 0) 0x5a)))
                     c)
                   raw)]
     (str header "." payload "." (b64url raw)))))

;; --- the server --------------------------------------------------------------

(defn- respond [^HttpExchange ex status body]
  (let [bs (.getBytes ^String body StandardCharsets/UTF_8)]
    (.add (.getResponseHeaders ex) "content-type" "application/json")
    (.sendResponseHeaders ex status (alength bs))
    (with-open [os (.getResponseBody ex)] (.write os bs))
    (.close ex)))

(defn- read-body ^String [^HttpExchange ex]
  (with-open [in (.getRequestBody ex) out (ByteArrayOutputStream.)]
    (.transferTo in out)
    (.toString out StandardCharsets/UTF_8)))

(defn- form->map [^String s]
  (into {} (for [pair (str/split s #"&") :when (seq pair)
                 :let [[k v] (str/split pair #"=" 2)]]
             [(java.net.URLDecoder/decode k StandardCharsets/UTF_8)
              (java.net.URLDecoder/decode (or v "") StandardCharsets/UTF_8)])))

(defn- handler [f]
  (reify HttpHandler (handle [_ ex] (f ex))))

(defn start!
  "Start the fake IdP.

   `control` is an atom the test mutates between calls:
   `{:token-status 200 :token-body {\"id_token\" …} :jwks [jwk …]
     :issuer \"…\" :last-token-request {…}}`.
   Returns `{:base-url … :server … :control control}`."
  [control]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        port   (.getPort (.getAddress server))
        base   (str "http://127.0.0.1:" port)]
    (swap! control assoc :issuer (or (:issuer @control) base))
    (.createContext server "/.well-known/openid-configuration"
                    (handler (fn [ex]
                               (respond ex 200 (json/generate-string
                                                {"issuer" (:issuer @control)
                                                 "authorization_endpoint" (str base "/authorize")
                                                 "token_endpoint" (str base "/token")
                                                 "jwks_uri" (str base "/jwks")})))))
    (.createContext server "/token"
                    (handler (fn [ex]
                               (let [form (form->map (read-body ex))]
                                 (swap! control assoc :last-token-request form)
                                 (respond ex (or (:token-status @control) 200)
                                          (json/generate-string
                                           (or (:token-body @control) {})))))))
    (.createContext server "/jwks"
                    (handler (fn [ex]
                               (swap! control update :jwks-fetches (fnil inc 0))
                               (respond ex 200 (json/generate-string
                                                {"keys" (vec (:jwks @control))})))))
    (.start server)
    {:base-url base :server server :control control}))

(defn stop! [{:keys [^HttpServer server]}] (.stop server 0))
