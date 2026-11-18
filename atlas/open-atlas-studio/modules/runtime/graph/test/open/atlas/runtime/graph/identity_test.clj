(ns open.atlas.runtime.graph.identity-test
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.runtime.graph :as graph]
            [open.atlas.runtime.graph.impl.store :as store]))

(def id "12345678-1234-4234-8234-123456789abc")
(def texts {"content.edn" "{}" "report.edn" "{}"
            "manifest.edn" (pr-str {:graph/id id})})

(deftest uuid-comes-from-the-receipt-verified-artifact
  (let [reads (atom [])]
    (with-redefs [store/serving-version (fn [_ _] 2)
                  store/receipt-digest (fn [_ _ _] (artifact/artifact-digest texts))
                  store/shelf-texts (fn [_ name version]
                                      (swap! reads conj [name version]) texts)]
      (is (= id (graph/graph-uuid nil "demo/old-name" :latest)))
      (is (= id (graph/graph-uuid nil "demo/new-name" :latest)))
      (is (= id (graph/graph-uuid nil "demo/new-name" 1)))
      (is (= [["demo/old-name" 2] ["demo/new-name" 2] ["demo/new-name" 1]] @reads))
      (with-redefs [store/shelf-texts (fn [& _] (assoc texts "manifest.edn" "{:graph/id \"forged\"}"))]
        (is (nil? (graph/graph-uuid nil "demo/old-name" :latest))))
      (with-redefs [store/receipt-digest (constantly nil)]
        (is (nil? (graph/graph-uuid nil "demo/old-name" :latest))))
      (with-redefs [store/serving-version (constantly nil)]
        (is (nil? (graph/graph-uuid nil "demo/old-name" :latest)))))))

(deftest missing-or-non-uuid-identity-is-not-a-name-fallback
  (doseq [manifest ["{}" "{:graph/id \"demo/app\"}" "{:graph/id \"1-1-1-1-1\"}" "{"]]
    (let [files (assoc texts "manifest.edn" manifest)]
      (with-redefs [store/receipt-digest (fn [& _] (artifact/artifact-digest files))
                    store/shelf-texts (fn [& _] files)]
        (is (nil? (graph/graph-uuid nil "demo/app" 1)))))))
