(ns open.atlas.common.token.token-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.token :as token]))

(def ^:private secret (apply str (repeat 43 "a")))
(def ^:private pepper "instance-local-pepper-for-tests")
(def ^:private now "2026-08-25T10:00:00Z")

(defn- codes [result]
  (into #{} (map :code) (if (vector? result) result (:findings result))))
(defn- record [& {:as over}]
  (merge {:token-id "tok-1" :graph-id "graph-orders" :version :latest
          :status :active
          :created-at "2026-08-25T09:00:00Z" :expires-at nil}
         over))

(deftest formatted-bearers-are-round-trippable-and-safe-to-reject
  (let [bearer (token/format-token "tok-1" secret)]
    (is (= {:token-id "tok-1" :secret secret} (token/parse-token bearer)))
    (let [rejection (token/parse-token (str bearer "!"))]
      (is (= #{:token.format/malformed} (codes rejection)))
      (is (not (str/includes? (pr-str rejection) secret))))))

(deftest digest-binds-the-instance-and-token-id
  (let [parts {:instance-id "studio-a" :token-id "tok-1" :secret secret}
        digest (token/digest pepper parts)]
    (is (token/digest-matches? pepper parts digest))
    (is (not (token/digest-matches? pepper (assoc parts :instance-id "server-a") digest)))
    (is (not (token/digest-matches? pepper (assoc parts :token-id "tok-2") digest)))))

(deftest a-record-has-one-closed-authorization
  (is (empty? (token/validate-record (record))))
  (testing "a legacy grants collection is not a compatibility path"
    (is (contains? (codes (token/validate-record (assoc (record) :grants [])))
                   :token.record/malformed)))
  (doseq [authorization [{:graph-id "graph-orders" :version :latest}
                         {:graph-id "graph-orders" :version 3}]]
    (is (empty? (token/validate-authorization authorization))))
  (is (contains? (codes (token/validate-authorization
                         {:graph-id "graph-orders" :version :latest :read-level :secret}))
                 :token.grants/invalid)))

(deftest authorisation-resolves-the-token-graph
  (let [ok (token/authorize (record) {:now now})]
    (is (= {:graph-id "graph-orders" :version :latest} (:authorization ok)))))

(deftest revoked-and-expired-records-never-authorise
  (is (= #{:token.authz/revoked}
         (codes (token/authorize (record :status :revoked)
                                 {:now now}))))
  (is (= #{:token.authz/expired}
         (codes (token/authorize (record :expires-at "2026-08-25T09:30:00Z")
                                 {:now now})))))
