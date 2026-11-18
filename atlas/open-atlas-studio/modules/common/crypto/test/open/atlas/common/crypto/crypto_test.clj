(ns open.atlas.common.crypto.crypto-test
  "原语的守门测试。最要紧的一条是**恒时比较拒绝形状不对的输入**——那是
   「malformed 被报成 mismatch」与「短路比较悄悄回来」的共同入口。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.crypto :as crypto]))

(deftest hex-round-trips
  (let [bs (byte-array (map unchecked-byte (range 0 32)))]
    (is (= 64 (count (crypto/hex bs))))
    (is (java.security.MessageDigest/isEqual bs (crypto/unhex (crypto/hex bs))))))

(deftest unhex-refuses-instead-of-throwing
  (testing "形状不对是**拒绝**，不是异常——它出现在验签路径上，抛出去会变成 500"
    (is (nil? (crypto/unhex "not hex")))
    (is (nil? (crypto/unhex "ABCD")) "大写不收：规范形只有一种")
    (is (nil? (crypto/unhex (apply str (repeat 63 "a")))) "长度不足")
    (is (nil? (crypto/unhex nil)))))

(deftest sha256-is-the-known-vector
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
         (crypto/sha256-hex ""))))

(deftest hmac-is-the-known-vector
  (testing "RFC 4231 test case 1"
    (is (= (str crypto/mac-prefix
                "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7")
           (crypto/hmac-sha256 (apply str (repeat 20 (char 0x0b))) "Hi There")))))

(deftest a-mac-carries-its-algorithm
  (is (crypto/well-formed-mac? (crypto/hmac-sha256 "k" "d")))
  (is (not (crypto/well-formed-mac? (subs (crypto/hmac-sha256 "k" "d")
                                          (count crypto/mac-prefix))))
      "**裸 hex 不是合法 MAC**：它说不出自己是哪个算法算的"))

(deftest constant-time-compare-agrees-with-equality-on-well-formed-values
  (let [a (crypto/hmac-sha256 "k" "d")
        b (crypto/hmac-sha256 "k" "d")
        c (crypto/hmac-sha256 "k" "e")]
    (is (crypto/constant-time= a b))
    (is (not (crypto/constant-time= a c)))))

(deftest constant-time-compare-refuses-malformed-input
  (testing "形状不对**不进比较**。放进去的话，两个同样畸形的值会「相等」,
            而畸形正是攻击者能自由控制的那一半"
    (let [a (crypto/hmac-sha256 "k" "d")]
      (is (not (crypto/constant-time= a nil)))
      (is (not (crypto/constant-time= a "")))
      (is (not (crypto/constant-time= "garbage" "garbage")) "两个同样畸形的值也不相等")
      (is (not (crypto/constant-time= a (subs a (count crypto/mac-prefix))))
          "少了算法前缀就不是同一个值"))))

;; --- 口令 -------------------------------------------------------------------

(deftest a-password-hash-is-salted-per-call
  (testing "同一个口令两次哈希不相等——否则一次破解就是关于每个账号的答案"
    (let [a (crypto/hash-password "correct horse battery staple")
          b (crypto/hash-password "correct horse battery staple")]
      (is (not= a b))
      (is (crypto/verify-password "correct horse battery staple" a))
      (is (crypto/verify-password "correct horse battery staple" b)))))

(deftest a-password-hash-says-what-produced-it
  (let [h (crypto/hash-password "pw")]
    (is (clojure.string/starts-with? h crypto/password-hash-prefix))
    (is (crypto/well-formed-password-hash? h))
    (is (clojure.string/includes? h "$600000$")
        "轮数写在值里：参数改了，存量值仍然自解释")))

(deftest verify-refuses-instead-of-throwing
  (testing "它在登录路径上——抛出去就是 500，而错口令返回 500 本身就是一个 oracle"
    (is (false? (crypto/verify-password "pw" nil)))
    (is (false? (crypto/verify-password "pw" "")))
    (is (false? (crypto/verify-password "pw" "not-a-hash")))
    (is (false? (crypto/verify-password "pw" "pbkdf2-sha256$600000$!!!$!!!")))
    (is (false? (crypto/verify-password nil (crypto/hash-password "pw"))))
    (is (false? (crypto/verify-password "wrong" (crypto/hash-password "pw"))))))

(deftest an-older-cost-still-verifies
  (testing "轮数取自存量值而不是当前常量，否则调高代价会锁死所有既有账号"
    (let [legacy (str crypto/password-hash-prefix "1000$"
                      ;; 与 hash-password 同一编码，轮数刻意低。
                      (.encodeToString (java.util.Base64/getEncoder) (byte-array 16))
                      "$"
                      (.encodeToString
                       (java.util.Base64/getEncoder)
                       (.getEncoded
                        (.generateSecret
                         (javax.crypto.SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")
                         (javax.crypto.spec.PBEKeySpec.
                          (.toCharArray "pw") (byte-array 16) 1000 256)))))]
      (is (crypto/verify-password "pw" legacy))
      (is (false? (crypto/verify-password "nope" legacy))))))
