(ns open.atlas.common.contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.contract :as contract]))

(deftest only-the-2-0-descriptor-is-identified
  (is (= {:contract contract/semantic-v2 :findings []}
         (contract/identify {:contract contract/semantic-v2})))
  (testing "a document without a descriptor is the retired legacy form, not a fallback reader"
    (is (= [contract/version-retired] (:findings (contract/identify {}))))
    (is (= [contract/version-retired]
           (:findings (contract/identify {:unknown-field "must not be silently accepted"})))))
  (testing "identification is not validation: any well-formed descriptor is answered as-is"
    (doseq [descriptor [{:contract/family "other" :contract/version "1.0.0"}
                        {:contract/family "atlas-semantic" :contract/version "1.4.0"}]]
      (is (= {:contract descriptor :findings []} (contract/identify {:contract descriptor}))))))

(deftest retired-descriptors-are-named-by-one-predicate
  ;; Task book «contract-upgrade-and-retirement» PR1 (owner 2026-09-19): the
  ;; legacy reader and 1.0-1.4 are retired; another family was never ours.
  (doseq [retired [nil
                   {:contract/family "atlas-legacy" :contract/version "v1"}
                   {:contract/family "atlas-semantic" :contract/version "1.0.0"}
                   {:contract/family "atlas-semantic" :contract/version "1.1.0"}
                   {:contract/family "atlas-semantic" :contract/version "1.2.0"}
                   {:contract/family "atlas-semantic" :contract/version "1.3.0"}
                   {:contract/family "atlas-semantic" :contract/version "1.4.0"}
                   {:contract/family "atlas-semantic" :contract/version "2.0.1"}]]
    (is (contract/retired? retired) (pr-str retired)))
  (is (not (contract/retired? contract/semantic-v2)))
  (is (not (contract/retired? {:contract/family "other" :contract/version "1.0.0"})))
  (is (= :contract/version-retired (:code contract/version-retired)))
  (is (= [:contract] (:path contract/version-retired)))
  (is (.contains ^String (:message contract/version-retired) "open-atlas-graph-builder")))

(deftest malformed-markers-do-not-fall-back-or-leak
  (doseq [document [nil [] "secret"
                    {:contract nil} {:contract {}}
                    {:contract {:contract/family "atlas-semantic"}}
                    {:contract (assoc contract/semantic-v2 :credential "secret")}
                    {:contract/family "atlas-semantic"}
                    {:contract/version "2.0.0"}]]
    (let [result (contract/identify document)]
      (is (seq (:findings result)) (pr-str document))
      (is (nil? (:contract result)))
      (is (not (.contains (pr-str result) "secret")))))
  (is (= :contract/misplaced-descriptor
         (-> (contract/identify {:contract/version "2.0.0"}) :findings first :code)))
  (is (= :contract/descriptor-invalid
         (-> (contract/identify {:contract {}}) :findings first :code))))
