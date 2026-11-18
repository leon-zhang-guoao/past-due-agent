(ns open.atlas.studio.semantic-import-capabilities-test
  "Import and Source selection swap connector KIND capabilities only; feature
   capabilities that happen to live in the `connector` namespace (doc 107
   §107.9 item 7, e.g. `:connector/api-status-map-v1`) are the author's and
   must survive — the SE graph import was refused with
   `:contract/capability-undeclared` on 2026-09-18 because they did not."
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.studio.semantic-service :as semantic]))

(def old-sources {"a" #:source{:kind "api" :effects #{:read}}})
(def new-sources {"a" #:source{:kind "api" :effects #{:read}}
                  "b" #:source{:kind "sql" :effects #{:read}}})

(deftest feature-capabilities-in-the-connector-namespace-survive
  (is (= #{:semantic/core-v1 :connector/api :connector/sql :connector/api-status-map-v1 :source/policy-v1}
         (semantic/replace-connector-kind-capabilities
          #{:semantic/core-v1 :connector/api :connector/api-status-map-v1 :source/policy-v1}
          old-sources new-sources))))

(deftest removed-kinds-are-dropped-and-unknown-kind-capabilities-are-kept
  (is (= #{:semantic/core-v1 :connector/sql}
         (semantic/replace-connector-kind-capabilities
          #{:semantic/core-v1 :connector/api} old-sources {"b" #:source{:kind "sql" :effects #{:read}}})))
  (is (= #{:connector/nifi :connector/api}
         (semantic/replace-connector-kind-capabilities #{:connector/nifi} {} old-sources))
      "a kind capability the previous slots did not own is not this function's to remove"))

(deftest imported-slot-policy-follows-its-bindings-onto-the-graph-source
  (let [policy {:retry_on #{:rate_limited} :max_retries 1}
        base {:source/kind "api" :source/effects #{:read}}]
    (is (= (assoc base :source/policy policy)
           (semantic/carry-slot-policy base #:source{:kind "api" :effects #{:read} :policy policy})))
    (is (= base (semantic/carry-slot-policy base #:source{:kind "api" :effects #{:read}}))
        "a policy-less imported slot leaves the Graph Source untouched")
    (is (= base (semantic/carry-slot-policy base nil)))))
