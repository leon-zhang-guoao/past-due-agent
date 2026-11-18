(ns open.atlas.common.connector.api-kit.se-conformance-test
  "The kit against the Schneider Electric v3 Semantic document — the proof
   that a generated suite really does hold a real source.

   Nothing here is hand-written per binding: the four `defconformance` forms
   read the document's own api bindings and define one `deftest` per
   capability each. That is the whole claim of this module — the hundredth API
   gets this by writing four lines, not by writing a suite.

   The document is read READ-ONLY from 业务案例; the fixtures beside this
   module were recorded from it with `api-kit/record!` (see
   `fixtures/se/README.md`). No socket is opened.

   Since the repository split (repo-split §5) the SE document lives in the
   atlas-biz-se repository, not beside this one. When it is not checked out
   next to this repository the suite is skipped and says so on stdout, rather
   than failing the whole aggregate on a file this repository cannot carry."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.api-kit :as api-kit]))

(def fixture-dir
  ;; The fixtures are on the classpath (the module's `fixtures` path), but the
  ;; recorder and the CLI both speak in DIRECTORIES — so the suite resolves the
  ;; same directory a `record!` would write to, and not a resource.
  (.getParent (.getParentFile
               (io/file (.getPath (io/resource "se/se-catalog-api/se-index-api.fixture.edn"))))))

(def document-path
  "The SE v3 document, read-only. Found by walking up from this module, so the
   suite runs both from the repository root and from the module directory.
   The SE case moved v3 under history/v3 on 2026-09-30 when v4 replaced it."
  (->> ["业务案例/se/history/v3/se-product-semantic-contract-1.4-v3.edn"
        "../../../业务案例/se/history/v3/se-product-semantic-contract-1.4-v3.edn"]
       (filter #(.exists (io/file %)))
       first))

(def document (some-> document-path slurp edn/read-string))

(def source-defs
  (into {} (map (juxt :id identity)) (api-kit/semantic-source-defs document)))

(defn sd [id] (get source-defs id))

(def ^:private this-ns *ns*)

(when-not document
  (println "SKIP se-conformance-test: SE v3 document not found (业务案例/se/history/v3, atlas-biz-se)"))

(when document
;; The generated suites. One line per binding — this is the whole onboarding
;; cost once a fixture exists.
(api-kit/defconformance se-detail-api (sd "se-detail-api") fixture-dir)
(api-kit/defconformance se-index-api  (sd "se-index-api")  fixture-dir)
(api-kit/defconformance se-delta-api  (sd "se-delta-api")  fixture-dir)
(api-kit/defconformance se-gtin-api   (sd "se-gtin-api")   fixture-dir)

;; ---------------------------------------------------------------------------
;; What the generator decided — pinned, because a generator that quietly stops
;; generating a capability is the failure mode this module exists to prevent.
;; ---------------------------------------------------------------------------

(deftest generated-capabilities-per-binding
  (testing "the batch binding's `:status-map` is what brings the per-row capabilities"
    ;; SE declares `{400 :input-rejected 404 :input-rejected}` (doc 107 §107.5).
    ;; Nothing about «400» is in the generator: drop the declaration and these
    ;; three deftests disappear, which is the visible gap the kit is for.
    (is (= [:read :row-outcome :unmapped-status
            :batch-alignment :batch-duplicates :batch-chunking
            :batch-isolation :batch-isolation-budget
            :failure-unauthorized :failure-rate-limited :failure-unavailable
            :failure-timeout :failure-cancelled :audit-receipt]
           (api-kit/capabilities (sd "se-detail-api")))))
  (testing "a declared collection brings the list and paging capabilities"
    (is (= [:read :unmapped-status :list :paging
            :failure-unauthorized :failure-rate-limited :failure-unavailable
            :failure-timeout :failure-cancelled :audit-receipt]
           (api-kit/capabilities (sd "se-index-api"))))
    (is (= (api-kit/capabilities (sd "se-index-api"))
           (api-kit/capabilities (sd "se-delta-api"))
           (api-kit/capabilities (sd "se-gtin-api")))))
  (testing "no binding of this document is a by-key single read, so none is generated"
    (is (not-any? #(some #{:by-key-not-found} (api-kit/capabilities %))
                  (vals source-defs)))))

(deftest the-effective-policy-comes-from-the-document
  ;; §107.3 four-level merge: the slot's `:source/policy` is what decides
  ;; whether the generated `:failure-rate-limited` case expects a retry.
  (let [p (:policy (sd "se-detail-api"))]
    (is (= 1 (:max_retries p)) "the SE slot declares one retry")
    (is (= #{:rate_limited :unavailable} (:retry_on p)))
    (is (every? #(contains? p %) [:timeout_ms :backoff_ms :jitter :bulkhead :circuit_breaker])
        "every capacity key is present too — no call site re-derives a default"))
  (testing "the document declares :source/policy-v1, so `:timeout_ms` is read as api-v2"
    (is (true? (get-in (sd "se-detail-api")
                       [:source-config :semantic/attempt-total-timeout?])))))

(deftest generated-test-names
  (is (= '[se-detail-api-read se-detail-api-row-outcome se-detail-api-unmapped-status
           se-detail-api-batch-alignment se-detail-api-batch-duplicates
           se-detail-api-batch-chunking se-detail-api-batch-isolation
           se-detail-api-batch-isolation-budget se-detail-api-failure-unauthorized
           se-detail-api-failure-rate-limited se-detail-api-failure-unavailable
           se-detail-api-failure-timeout se-detail-api-failure-cancelled
           se-detail-api-audit-receipt]
         (api-kit/test-names (sd "se-detail-api"))))
  (is (= 44 (count (mapcat api-kit/test-names (vals source-defs))))
      "four bindings, forty-four generated deftests")
  (testing "every generated name is a real var in this ns"
    (doseq [n (mapcat api-kit/test-names (vals source-defs))]
      (is (some? (ns-resolve this-ns n)) (str n " was generated but not defined"))))))

(deftest the-fixtures-are-clean
  (is (= [] (api-kit/redaction-check fixture-dir))
      "a recorded fixture must carry nothing that looks like a secret"))
