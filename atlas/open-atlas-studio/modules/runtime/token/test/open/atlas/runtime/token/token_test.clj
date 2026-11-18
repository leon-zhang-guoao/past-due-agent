(ns open.atlas.runtime.token.token-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.token :as token]
            [open.atlas.runtime.store :as store]
            [open.atlas.runtime.token :as runtime-token])
  (:import [java.io File]))

(def ^:private actor {:issuer "admin" :subject "alice"})
(def ^:private now "2026-08-25T10:00:00Z")

(defn- tmp-root []
  (str (java.nio.file.Files/createTempDirectory
        "runtime-token-" (into-array java.nio.file.attribute.FileAttribute []))))
(defn- rm-r [path]
  (let [file (io/file path)]
    (when (.isDirectory file) (run! rm-r (.listFiles file)))
    (.delete file)))

(def ^:private loaded {"graph-orders" #{1 2 3}})
(def ^:private serving {"graph-orders" 2})
(defn- capabilities []
  {:graph-loaded? (fn [graph-id version] (contains? (get loaded graph-id #{}) version))
   :graph-serving-version (fn [graph-id] (get serving graph-id))
   :graph-enabled? (constantly true)
   :row-security-gate (constantly [])})

(defmacro with-token-store [[token-store instance & {:keys [instance-id pepper]}] & body]
  `(let [root# (tmp-root)
         opened# (store/open! {:instance-id (or ~instance-id "instance-a") :root root#})
         ~instance (:instance opened#)
         started# (runtime-token/open! ~instance
                                       (merge (capabilities) {:pepper (or ~pepper "pepper-a")}))
         ~token-store (:store started#)]
     (assert ~token-store (pr-str (:findings started#)))
     (try ~@body (finally (store/close! ~instance) (rm-r root#)))))

(defn- issue! [token-store & {:as overrides}]
  (runtime-token/issue!
   token-store
   (merge {:graph-id "graph-orders" :version :latest
           :actor actor :request-id "issue-1" :now now}
          overrides)))
(defn- codes [result] (into #{} (map :code) (:findings result)))

(deftest issue-verify-and-audit-share-the-single-graph-contract
  (with-token-store [tokens instance]
    (let [{:keys [token-id secret record]} (issue! tokens)
          verified (runtime-token/verify tokens secret {:now now})]
      (is (= "graph-orders" (:graph-id record)))
      (is (= {:graph-id "graph-orders" :version :latest} (:authorization verified)))
      (is (= token-id (:token-id verified)))
      (is (= [{:token-id token-id :op :issue :actor actor :request-id "issue-1"
               :at now :outcome :ok
               :authorization {:graph-id "graph-orders" :version :latest}}]
             (runtime-token/audit tokens token-id))))))

(deftest a-secret-is-returned-once-and-never-persisted
  (with-token-store [tokens instance]
    (let [{:keys [token-id secret]} (issue! tokens)
          raw (:secret (token/parse-token secret))
          replay (issue! tokens)]
      (is (:replayed replay))
      (is (nil? (:secret replay)))
      (is (not (str/includes?
                (slurp (File. (io/file (store/root instance) "tokens" "records")
                              (str token-id ".edn")))
                raw))))))

(deftest issuance-fails-closed-for-an-unloaded-or-unserved-graph
  (with-token-store [tokens instance]
    (is (= #{:token.issue/graph-not-loaded}
           (codes (issue! tokens :version 9))))
    (is (= #{:token.issue/no-serving-version}
           (codes (issue! tokens :graph-id "graph-customer" :version :latest))))))

(deftest legacy-input-is-normalised-only-when-it-names-one-graph
  (with-token-store [tokens instance]
    (let [issued (runtime-token/issue!
                  tokens
                  {:grants [{:graph "graph-orders" :version :latest}]
                   :actor actor :request-id "legacy-one" :now now})]
      (is (= "graph-orders" (:graph-id (:record issued))))
      (is (not (contains? (:record issued) :grants))))
    (is (= #{:token.grants/invalid}
           (codes (runtime-token/issue!
                   tokens
                   {:grants [{:graph "graph-orders" :version :latest}
                             {:graph "graph-other" :version :latest}]
                    :actor actor :request-id "legacy-many" :now now}))))))

(deftest pinned-tokens-retain-their-one-graph-version
  (with-token-store [tokens instance]
    (let [{:keys [token-id]} (issue! tokens :version 1 :request-id "pinned")]
      (is (= #{{:graph-id "graph-orders" :version 1}}
             (runtime-token/pinned-coordinates tokens now)))
      (runtime-token/revoke-token! tokens {:token-id token-id :actor actor
                                            :request-id "revoke" :now now})
      (is (empty? (runtime-token/pinned-coordinates tokens now))))))

(deftest tokens-never-cross-runtime-instances
  (with-token-store [a ia :instance-id "studio" :pepper "shared"]
    (let [{:keys [secret]} (issue! a)]
      (with-token-store [b ib :instance-id "server" :pepper "shared"]
        (is (= #{:token.authz/unknown}
               (codes (runtime-token/verify b secret {:now now}))))))))

;; ---------------------------------------------------------------------------
;; 行级安全门禁的**发牌**那一处（E1 / D5.14 的三处之一）
;; ---------------------------------------------------------------------------

(deftest a-blocked-graph-cannot-be-issued-a-token
  (testing "发牌是 D5.14 门禁的三处读之一。判据翻向之后**拦的是别的东西**,
            但拦这个动作本身必须还在——三处漏一处，系统就处于「一半说不行、
            一半说行」，那比翻向之前更危险"
    (let [blocked [{:code :rowsec.gate/blocked :severity :error :path [:closure]
                    :message "declared but not verified"
                    :got {:graph "graph-orders" :version 2
                          :resolvers ["resolver:orders_by_tenant"]}}]
          root (tmp-root)
          inst (:instance (store/open! {:instance-id "instance-a" :root root}))]
      (try
        (let [tokens (:store (runtime-token/open!
                              inst (merge (capabilities)
                                          {:pepper "pepper-a"
                                           :row-security-gate (constantly blocked)})))]
          (testing "钉版本"
            (let [r (runtime-token/issue! tokens {:graph-id "graph-orders" :version 2
                                                  :actor actor :request-id "b1" :now now})]
              (is (= #{:rowsec.gate/blocked} (codes r)))
              (is (nil? (:secret r)) "拦住了就不给密钥")
              (is (= ["resolver:orders_by_tenant"] (:resolvers (:got (first (:findings r)))))
                  "拒绝点名未核对的 resolver，一路传到调用方")))
          (testing "latest 同样拦——已有 serving 指针不构成绕过"
            (is (= #{:rowsec.gate/blocked}
                   (codes (runtime-token/issue! tokens {:graph-id "graph-orders"
                                                        :version :latest :actor actor
                                                        :request-id "b2" :now now}))))))
        (finally (store/close! inst))))))

(deftest active-records-exclude-expired-and-revoked-keys
  (is (runtime-token/active? {:status :active} "2026-09-26T00:00:00Z"))
  (is (not (runtime-token/active? {:status :active :expires-at "2026-09-26T00:00:00Z"}
                                "2026-09-26T00:00:00Z")))
  (is (not (runtime-token/active? {:status :revoked} "2026-09-26T00:00:00Z"))))

(deftest only-a-token-issued-with-asserts-user-carries-the-capability
  ;; D1.9b: the capability is opt-in, rides beside the closed authorization, and
  ;; anything other than true/false is refused rather than read as a yes.
  (with-token-store [tokens instance]
    (let [plain    (issue! tokens :request-id "plain")
          vouching (issue! tokens :request-id "vouching" :asserts-user? true)
          declined (issue! tokens :request-id "declined" :asserts-user? false)]
      (is (not (contains? (runtime-token/verify tokens (:secret plain) {:now now}) :asserts-user?)))
      (is (not (contains? (:record declined) :asserts-user?)))
      (let [v (runtime-token/verify tokens (:secret vouching) {:now now})]
        (is (true? (:asserts-user? v)))
        (is (= {:graph-id "graph-orders" :version :latest} (:authorization v))))
      (is (= #{:token.record/malformed}
             (codes (issue! tokens :request-id "junk" :asserts-user? "yes")))))))
