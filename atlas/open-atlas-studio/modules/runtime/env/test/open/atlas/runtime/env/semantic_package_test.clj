(ns open.atlas.runtime.env.semantic-package-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql]
            [open.atlas.common.contract :as version]
            [open.atlas.common.semantic-contract :as contract]
            [open.atlas.common.semantic-graph-package :as package]
            [open.atlas.runtime.env :as env]))

(def adapters {"sql" (spi/semantic-adapter "sql")})
(def caps #{:semantic/core-v1 :graph/single-owner-v1
            :deployment/source-slot-v1 :artifact/embedded-closure-v1})
(def checkers (update-vals adapters :validate))

(defn ref-of [document]
  {:graph/id (get-in document [:graph :graph/id])
   :release/version (get-in document [:publication :release/version])
   :content/hash (contract/content-hash (:text (contract/write-document document checkers)))})

(defn document [{:keys [graph entity identity value table physical term dependencies terms]}]
  (let [resolver (str graph "/by-id") binding (str graph "/sql")
        source? (boolean entity)]
    {:contract version/semantic-v2
     :workspace {:workspace/id "portable"}
     :graph {:graph/usage-guide {:guide/purpose "Fixture behavior" :guide/asset-scope "Declared fixture assets"
 :guide/applicability "Test environment only" :guide/limitations ["Not production data"]
 :guide/composition "Pinned dependencies; review before reuse" :guide/examples []} :graph/id graph :graph/label graph :graph/exposure :restricted
             :graph/entities (if entity [entity] [])
             :graph/resolvers (if entity [resolver] []) :graph/operations []
             :graph/terms (vec (keys terms)) :graph/dependencies (vec dependencies)}
     :vocabulary {:entities (if entity {entity {:entity/label entity :entity/identity [identity]
                                                :entity/attributes [identity value]}} {})
                  :attributes (if entity
                                {identity {:attribute/label (:term/label term)
                                           :attribute/description (:term/description term)
                                           :attribute/type :string :attribute/nullable? false
                                           :attribute/term (:id term)}
                                 value {:attribute/label "Value" :attribute/type :string
                                        :attribute/nullable? true}} {})
                  :terms (or terms {}) :aliases [] :references [] :equivalences []}
     :execution {:resolvers (if entity {resolver {:label resolver :effect :read :input [identity]
                                                  :output [value] :result/cardinality :one}} {})
                 :operations {}
                 :sources (if source? {"primary" {:source/kind "sql" :source/effects #{:read}}} {})
                 :bindings (if source?
                             {binding {:binding/for [:resolver resolver] :binding/source "primary"
                                       :binding/effect :read
                                       :binding/implementation {:query (str "SELECT value FROM " table " WHERE " physical " = :id")}
                                       :binding/input-map {identity "id"}
                                       :binding/output-map {value "value"}}} {})
                 :selection (if source? {[:resolver resolver] binding} {})}
     :ownership {:entities (if entity {entity graph} {})
                 :resolvers (if entity {resolver graph} {}) :operations {}
                 :terms (into {} (map (fn [id] [id graph]) (keys terms)))}
     :publication {:release/version "1" :source/revision 1
                   :required-capabilities (cond-> caps
                                            source? (conj :connector/sql)
                                            (seq dependencies) (conj :graph/dependencies-v1))}}))

(deftest parent-package-binds-two-child-graphs-to-one-source
  (let [vend-term {:id "common/vend_no" :term/label "供应商编码"
                   :term/description "共享供应商业务编码"}
        sku-term {:id "common/sku_no" :term/label "产品编码"
                  :term/description "共享产品业务编码"}
        common (document {:graph "common" :terms {"common/vend_no" (dissoc vend-term :id)
                                                   "common/sku_no" (dissoc sku-term :id)}})
        vendor (document {:graph "vendor" :entity "demo.vendor" :identity :demo.vendor/vend_no
                          :value :demo.vendor/name :table "vendor" :physical "vend_no"
                          :term vend-term :dependencies [(ref-of common)]})
        part (document {:graph "part" :entity "demo.part" :identity :demo.part/sku_no
                        :value :demo.part/description :table "part" :physical "sku_No"
                        :term sku-term :dependencies [(ref-of common)]})
        root (document {:graph "commerce" :dependencies [(ref-of vendor) (ref-of part)]})
        built (package/build root [root vendor part common] checkers)
        url (str "jdbc:sqlite:file:" (random-uuid) "?mode=memory&cache=shared")
        shared {:type "sql" :jdbc_url url}]
    (is (empty? (:findings built)) (pr-str built))
    (with-open [db (java.sql.DriverManager/getConnection url)]
      (jdbc/execute! db ["CREATE TABLE vendor (vend_no TEXT PRIMARY KEY, value TEXT)"])
      (jdbc/execute! db ["CREATE TABLE part (sku_No TEXT PRIMARY KEY, value TEXT)"])
      (jdbc/execute! db ["INSERT INTO vendor VALUES ('V-1', 'Vendor One')"])
      (jdbc/execute! db ["INSERT INTO part VALUES ('SKU-1', 'Part One')"])
      (let [missing (env/for-semantic-graph-package (:text built) (:package-digest built)
                                                    {:adapters adapters :bindings {}})
            incompatible (env/for-semantic-graph-package
                          (:text built) (:package-digest built)
                          {:adapters adapters :bindings {["vendor" "primary"] shared
                                                        ["part" "primary"] {:type "api"}}})
            forbidden (env/for-semantic-graph-package
                       (:text built) (:package-digest built)
                       {:adapters adapters
                        :bindings {["vendor" "primary"] shared ["part" "primary"] shared}
                        :authorize-binding? (fn [{:keys [graph-id]} _] (not= graph-id "part"))})
            unreachable (env/for-semantic-graph-package
                         (:text built) (:package-digest built)
                         {:adapters adapters
                          :bindings {["vendor" "primary"] shared ["part" "primary"] shared}
                          :verify-binding (fn [{:keys [graph-id]} _] (not= graph-id "vendor"))})
            candidate (env/for-semantic-graph-package
                       (:text built) (:package-digest built)
                       {:adapters adapters :bindings {["vendor" "primary"] shared
                                                     ["part" "primary"] shared}})]
        (is (= 2 (count (:findings missing))) "each data Graph names its missing slot")
        (is (= :semantic.runtime/source-binding-incompatible
               (get-in incompatible [:findings 0 :code])))
        (is (= :semantic.runtime/source-binding-forbidden
               (get-in forbidden [:findings 0 :code])))
        (is (= :semantic.runtime/source-binding-unreachable
               (get-in unreachable [:findings 0 :code])))
        (is (empty? (:findings candidate)) (pr-str candidate))
        (is (= "Vendor One"
               (get-in (env/query-semantic-candidate candidate
                                                     {:graph/id "commerce"
                                                      :entity {:demo.vendor/vend_no "V-1"}
                                                      :query [:demo.vendor/name]})
                       [:response :engine/data :demo.vendor/name])))
        (is (= "Part One"
               (get-in (env/query-semantic-candidate candidate
                                                     {:graph/id "commerce"
                                                      :entity {:demo.part/sku_no "SKU-1"}
                                                      :query [:demo.part/description]})
                       [:response :engine/data :demo.part/description])))
        (testing "run-stats ride along only when asked (Query Studio's Plan and Trace)"
          (let [request {:graph/id "commerce" :entity {:demo.vendor/vend_no "V-1"}
                         :query [:demo.vendor/name]}
                plain (env/query-semantic-candidate candidate request)
                with-stats (env/query-semantic-candidate candidate request {:stats? true})]
            (is (not (contains? (:response plain) :engine/stats)))
            (is (contains? (:response with-stats) :engine/stats))
            (is (= (get-in plain [:response :engine/data])
                   (get-in with-stats [:response :engine/data]))
                "asking for stats never changes the answer")))
        (is (= #{["vendor" "primary"] ["part" "primary"]} (:bindings candidate))
            "the same registered Source instance may satisfy multiple isolated Graph slots")))))

(deftest root-graph-query-budget-is-compiled-and-packaged
  (let [with-budget (fn [d ms] (-> d
                                  (assoc-in [:graph :graph/query-budget] {:execution-ms ms})
                                  (update-in [:publication :required-capabilities] conj :graph/query-budget-v1)))
        base (document {:graph "catalog"})
        configured (with-budget base 120000)
        options {:adapters adapters :connections {}}
        candidate (env/for-semantic-document configured options)
        built (package/build configured [configured] checkers)
        deployed (env/for-semantic-graph-package (:text built) (:package-digest built)
                                                 {:adapters adapters :bindings {}})]
    (is (= 120000 (get-in candidate [:env :engine/limits :execution-ms])) (pr-str (:findings candidate)))
    (is (= 120000 (get-in deployed [:env :engine/limits :execution-ms])) (pr-str (:findings deployed)))
    (is (= 30000 (get-in (env/for-semantic-document base options) [:env :engine/limits :execution-ms])))
    (doseq [ms [0 -1 "120000" 900001]]
      (is (seq (:findings (env/for-semantic-document (with-budget base ms) options)))))
    (is (seq (:findings (contract/check-support configured caps))) "old runtime must reject a budget it cannot enforce")
    (let [child (with-budget (document {:graph "child"}) 120000)
          root (document {:graph "consumer" :dependencies [(ref-of child)]})
          p (package/build root [root child] checkers)
          c (env/for-semantic-graph-package (:text p) (:package-digest p) {:adapters adapters :bindings {}})]
      (is (= 30000 (get-in c [:env :engine/limits :execution-ms])) (pr-str (:findings c))))))

;; ---------------------------------------------------------------------------
;; doc 107 §107.3 (owner 2026-09-18): the four-level merge, and the deployment
;; clamp that may only tighten.
;; ---------------------------------------------------------------------------

(defn- with-policy [d slot-policy binding-policy]
  (cond-> (update-in d [:publication :required-capabilities] conj :source/policy-v1)
    slot-policy    (assoc-in [:execution :sources "primary" :source/policy] slot-policy)
    binding-policy (assoc-in [:execution :bindings (str (get-in d [:graph :graph/id]) "/sql")
                              :binding/implementation :policy]
                             binding-policy)))

(defn- policy-case [{:keys [slot binding capacity]}]
  (let [term {:id "common/vend_no" :term/label "供应商编码"
              :term/description "共享供应商业务编码"}
        common (document {:graph "common" :terms {"common/vend_no" (dissoc term :id)}})
        vendor (with-policy (document {:graph "vendor" :entity "demo.vendor"
                                       :identity :demo.vendor/vend_no :value :demo.vendor/name
                                       :table "vendor" :physical "vend_no" :term term
                                       :dependencies [(ref-of common)]})
                 slot binding)
        root (document {:graph "commerce" :dependencies [(ref-of vendor)]})
        built (package/build root [root vendor common] checkers)
        url (str "jdbc:sqlite:file:" (random-uuid) "?mode=memory&cache=shared")]
    (is (empty? (:findings built)) (pr-str built))
    (with-open [db (java.sql.DriverManager/getConnection url)]
      (jdbc/execute! db ["CREATE TABLE vendor (vend_no TEXT PRIMARY KEY, value TEXT)"])
      (env/for-semantic-graph-package
       (:text built) (:package-digest built)
       {:adapters adapters
        :bindings {["vendor" "primary"] (cond-> {:type "sql" :jdbc_url url}
                                          capacity (assoc :resilience capacity))}}))))

(deftest a-declared-policy-is-merged-clamped-and-handed-to-the-connector
  (let [candidate (policy-case {:slot {:max_retries 2 :retry_on #{:connection}}
                                :capacity {:timeout_ms 12000
                                           :bulkhead {:concurrency 3 :max_wait_ms 50}}})
        effective (get-in candidate [:source-policies "vendor::source::primary"])]
    (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
    (is (= 2 (:max_retries effective)) "the graph's semantic half survives")
    (is (= #{:connection} (:retry_on effective)))
    (is (= 12000 (:timeout_ms effective)) "the deployment owns the capacity half")
    (is (= 3 (get-in effective [:bulkhead :concurrency])))
    (is (= 10000 (get-in effective [:circuit_breaker :reset_ms]))
        "unset capacity keys fall through to the platform default, so no connector re-derives one")))

(deftest a-binding-override-wins-over-the-slot-and-is-still-clamped
  (let [candidate (policy-case {:slot {:max_retries 1 :retry_on #{:connection}}
                                :binding {:max_retries 4}
                                :capacity {:max_retries 2}})
        ;; The binding's own effective policy is what the connector receives in
        ;; its operation block; the slot-level one stays at the slot's value.
        slot (get-in candidate [:source-policies "vendor::source::primary"])]
    (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
    (is (= 1 (:max_retries slot)) "the slot keeps its own declaration")
    (is (= 2 (:max_retries (get-in candidate [:binding-policies "vendor::binding::vendor/sql"])))
        "binding (4) beats slot (1) in the merge, then the deployment tightens to 2")))

(deftest a-deployment-that-tries-to-widen-the-declared-policy-is-refused
  ;; E6.2 decides this statically at version-switch time. Refused, not silently
  ;; clamped: a deployment quietly multiplying the upstream load a modeller
  ;; sized is exactly the kind of drift nobody reading the graph could see.
  (let [candidate (policy-case {:slot {:max_retries 1 :retry_on #{:connection}}
                                :capacity {:max_retries 5}})
        finding (first (:findings candidate))]
    (is (nil? (:env candidate)))
    (is (= :deployment.policy/loosened (:code finding)))
    (is (= :error (:severity finding)))
    (is (= [:sources "vendor::source::primary" :max_retries] (:path finding))))
  (let [candidate (policy-case {:slot {:retry_on #{:connection}}
                                :capacity {:retry_on #{:connection :rate_limited}}})]
    (is (= :deployment.policy/loosened (:code (first (:findings candidate))))
        "adding a category the graph did not declare is widening too")))
