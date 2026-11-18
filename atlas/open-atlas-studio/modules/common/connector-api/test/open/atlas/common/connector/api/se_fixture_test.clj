(ns open.atlas.common.connector.api.se-fixture-test
  "The Schneider Electric partner-API corpus (fixtures/se) held against
   api-v1. It is a REAL migrated corpus, not an invented one, so it is the
   fixture that says whether this connector can express what production
   already asked of its predecessor: OAuth2 with HTTP Basic client auth, a
   required gateway header, a batched id-list lookup with a nested echoed
   id, deep dotted field paths, and a list feed with a query-routed param.

   Two things are checked, and they fail for different reasons:
   `validate-source` proves the corpus is api-v1 SHAPE-clean (a contract
   drift breaks this), and the extraction cases prove the recorded field
   paths actually reach their values in a response of the recorded SHAPE
   (a jsonpath regression breaks this)."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.connector.api :as api]
            [open.atlas.common.connector.api.test-env :as test-env]
            [open.atlas.common.connector.spi :as spi]))

;; Bulkhead and breaker state is process-wide and keyed by source identity
;; (D6.5) -- it deliberately outlives a compiled env, so tests that trip a
;; breaker must not decide the next test's verdict.
(use-fixtures :each (fn [t] (api/reset-resilience-state!) (t)))

(def graph
  (edn/read-string (slurp (io/resource "se/graph.edn"))))

(def sample-detail
  (slurp (io/resource "se/sample-detail.json")))

(def source (get-in graph [:sources "se-partner-api"]))

(defn- resolver [id]
  (first (filter #(= id (:id %)) (:resolvers graph))))

(defn- source-config
  "api-v1 validate input: the source entry merged with the operation block,
   carrying the resolver context."
  [r]
  (merge source (:source r)
         (select-keys r [:id :input :output :result_type :eql-params :limit :order])))

(def ^:private fixture-env
  {"SE_CLIENT_ID" "fixture-client-id"
   "SE_CLIENT_SECRET" "fixture-client-secret"})

(defmacro with-fixture-env
  "Resolve the corpus's ${VAR} references from a fixture map rather than the
   real environment — the corpus is shareable precisely because the secrets
   are not in it."
  [& body]
  `(test-env/with-env fixture-env ~@body))

(defn- token-post
  "Stand in for Schneider's token endpoint. The corpus authenticates, so
   every request path here acquires a token first — stubbing only the data
   call would send the test to the real IdP."
  [_url _opts]
  {:status 200 :body (json/generate-string {:access_token "fixture-token"
                                            :expires_in 3600})})

(defmacro with-upstream
  "Run `body` against a stubbed upstream: `get-fn` answers the data calls,
   the token endpoint answers a fresh token, and the process-wide token
   cache starts empty so one test cannot see another's token."
  [get-fn & body]
  `(do (api/reset-oauth-cache!)
       (with-redefs [http/get ~get-fn
                     http/post token-post]
         ~@body)))

;; ---------------------------------------------------------------------------
;; api-v1 shape
;; ---------------------------------------------------------------------------

(deftest every-resolver-is-api-v1-clean
  (with-fixture-env
    (doseq [r (:resolvers graph)]
      (testing (:id r)
        (is (= [] (:findings (spi/validate-source (source-config r)))))))))

(deftest corpus-covers-the-features-it-was-chosen-for
  ;; A corpus that quietly lost its batch block or its oauth2 auth would
  ;; still validate — and would stop being evidence for anything.
  (let [detail (resolver "se_product_detail")]
    (is (= "oauth2" (get-in source [:auth :type])))
    (is (= "basic" (get-in source [:auth :client_auth]))
        "HTTP Basic client auth is the variant this upstream requires")
    (is (= 25 (get-in detail [:source :batch :chunk-size]))
        "a measured chunk size — api-v1 makes :chunk-size live, so it must survive")
    (is (= "identifiers.manufacturerPID" (get-in detail [:source :batch :child-fk]))
        "the echoed id is NESTED; a dotted :child-fk is what makes alignment work")
    (is (= "query" (get-in (resolver "se_product_delta")
                           [:eql-params :since :param_location])))
    (testing ":limit is an owner ruling (2026-08-28), not a tunable"
      ;; Pinned because the index window (5000) is BELOW the measured catalog
      ;; size (5760): the ruling knowingly caps a full-catalog read, and the
      ;; README says so. Someone "fixing" that number is changing a decision.
      (is (= {:default 500 :max 5000} (:limit (resolver "se_product_index"))))
      (is (= {:default 500 :max 5000} (:limit (resolver "se_product_delta")))))))

;; ---------------------------------------------------------------------------
;; The recorded field paths against a response of the recorded shape
;; ---------------------------------------------------------------------------

(deftest batch-detail-extracts-the-recorded-paths
  (with-fixture-env
    (let [r (resolver "se_product_detail")
          f (spi/compile-batch-source (merge source (:source r)) nil r)]
      (with-upstream (fn [_ _] {:status 200 :body sample-detail})
        (let [[row _] (f [{:vendor.schneider.product/product_reference "NSYMM86"}
                          {:vendor.schneider.product/product_reference "NSYPLM108G"}]
                         {})]
          (testing "deep dotted paths reach through the vendor's nesting"
            (is (= "3606480183430" (:vendor.schneider.product/gtin row)))
            (is (= "Plain mounting plate H800xW600mm made of galvanised sheet steel"
                   (:vendor.schneider.product/short_desc row)))
            (is (= "EC000213" (:vendor.schneider.product/etim_class row)))
            (is (= "Mounting plate for distribution board"
                   (:vendor.schneider.product/etim_class_name row))
                "two array hops and a key — the path most likely to be mistyped by hand")
            (is (= 74.82 (:vendor.schneider.product/list_price row)))
            (is (= "USD" (:vendor.schneider.product/price_currency row)))
            (is (= "Commercialised" (:vendor.schneider.product/commercial_status row)))
            (is (= "17.637 lb(US) (8.000 kg)" (:vendor.schneider.product/pack_weight row)))
            (is (= "Compliant" (:vendor.schneider.product/rohs_status row))))
          (testing "$[*] hands back the whole item as the escape hatch"
            (is (= "NSYMM86" (get-in row [:vendor.schneider.product/detail_raw
                                          :identifiers :manufacturerPID]))))
          (testing ":documents is the vendor's array, carried as json"
            (is (= 1 (count (:vendor.schneider.product/documents row))))))))))

(deftest batch-alignment-follows-the-nested-echoed-id
  (with-fixture-env
    (let [r (resolver "se_product_detail")
          f (spi/compile-batch-source (merge source (:source r)) nil r)]
      (with-upstream (fn [_ _] {:status 200 :body sample-detail})
        (let [rows (f [{:vendor.schneider.product/product_reference "NSYPLM108G"}
                       {:vendor.schneider.product/product_reference "NSYMM86"}
                       {:vendor.schneider.product/product_reference "NOT-IN-CATALOG"}]
                      {})]
          (is (= 3 (count rows)))
          (testing "each input gets ITS row, in input order — not response order"
            (is (= "SAMPLE second product — proves batch alignment groups by manufacturerPID"
                   (:vendor.schneider.product/short_desc (nth rows 0))))
            (is (= "3606480183430" (:vendor.schneider.product/gtin (nth rows 1)))))
          (testing "a reference the vendor did not answer for is nil, never a half-filled map"
            (is (nil? (nth rows 2)))))))))

(deftest index-list-extracts-references-through-data-path
  (with-fixture-env
    (let [r (resolver "se_product_index")
          f (spi/compile-source (merge source (:source r)) nil r)]
      (with-upstream (fn [_ _] {:status 200 :body sample-detail})
        (is (= [{:vendor.schneider.product/product_reference "NSYMM86"}
                {:vendor.schneider.product/product_reference "NSYPLM108G"}]
               (f {} {}))
            ":data_path locates the rows; the index maps one field off each")))))

(deftest the-batch-request-carries-the-joined-id-list
  (with-fixture-env
    (let [r    (resolver "se_product_detail")
          urls (atom [])
          f    (spi/compile-batch-source (merge source (:source r)) nil r)]
      (with-upstream (fn [url _] (swap! urls conj url)
                       {:status 200 :body sample-detail})
        (f [{:vendor.schneider.product/product_reference "NSYMM86"}
            {:vendor.schneider.product/product_reference "NSYPLM108G"}]
           {})
        (let [url (first @urls)]
          (is (= 1 (count @urls)) "two references, ONE call — the point of the batch block")
          ;; The joined list lands in the URL, so api-v1 percent-encodes it:
          ;; the separator arrives as %2C, not a literal comma. The reference
          ;; implementation interpolated verbatim — recorded in the fixture
          ;; README as a deliberate behaviour difference, not a defect.
          (is (clojure.string/includes? url "product-reference=NSYMM86%2CNSYPLM108G"))
          (testing "the entitlement pairing stays baked into the endpoint"
            (is (clojure.string/includes? url "supplierid=Schneider_US"))
            (is (clojure.string/includes? url "customerid=TDSYNNEX_US"))))))))
