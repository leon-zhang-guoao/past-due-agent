(ns open.atlas.common.connector.api.validate-test
  "Write-time validation: finding-v1 shapes, api-v1 closed key faces, the
   :auth discriminated block, and the support-policy rejections."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.api.test-env :refer [with-env]]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.api]))

(def valid
  {:type "api" :base_url "https://upstream.test"
   :method "GET" :endpoint "/parts/{p/sku}"
   :input [:p/sku]
   :field_mapping {:p/sku "$.sku"}})

(defn- validate [config] (spi/validate-source config))
(defn- codes [r] (mapv :code (:findings r)))

(deftest every-finding-passes-the-registry-shape
  (doseq [config [(dissoc valid :base_url :method :endpoint)
                  (assoc valid :bogus 1 :cache_ttl_ms 5)
                  (assoc valid :auth {:client_secret "literal"})
                  (assoc valid :batch {:body-mode "root-array"})]]
    (let [{:keys [findings]} (validate config)]
      (is (seq findings))
      (doseq [f findings]
        (is (spi/finding? f) (pr-str f))))))

(deftest clean-config-has-no-findings
  (is (= [] (:findings (validate valid)))))

(deftest required-keys
  (let [r (validate (dissoc valid :base_url :method :endpoint))]
    (is (= 3 (count (filter #(= :connector.source/missing-key (:code %))
                            (:findings r)))))))

(deftest method-vocabulary
  (is (some #{:connector.source/invalid-shape}
            (codes (validate (assoc valid :method "FETCH"))))))

(deftest unknown-and-rejected-keys
  (is (some #{:connector.source/unknown-key}
            (codes (validate (assoc valid :bogus 1)))))
  (testing "cache_ttl_ms is deferred with OQ-CACHE-KEY"
    (is (some #{:connector.source/unknown-key}
              (codes (validate (assoc valid :cache_ttl_ms 5)))))))

(deftest tls-and-insecure-are-mutually-exclusive
  (is (some #{:connector.source/invalid-shape}
            (codes (validate (assoc valid
                                    :tls {:keystore "certs/x.p12"
                                          :keystore_pass "${P}"}
                                    :ssl_insecure? true))))))

(deftest header-literal-credentials-are-rejected
  (is (some #{:connector.source/literal-credential}
            (codes (validate (assoc valid :headers {"X-Api-Key" "sekret"})))))
  (testing "a ${VAR} header value is the legal shape"
    (is (not (some #{:connector.source/literal-credential}
                   (codes (validate (assoc valid :headers
                                           {"X-Api-Key" "${API_KEY}"}))))))))

(deftest auth-discriminator-is-mandatory
  (let [r (validate (assoc valid :auth {:token_url "https://idp/token"}))]
    (is (some #(= [:source :auth :type] (:path %)) (:findings r)))))

(deftest auth-api-key-variant
  (testing ":value is required and must be a ${VAR}"
    (is (some #{:connector.source/missing-key}
              (codes (validate (assoc valid :auth {:type "api_key"})))))
    (is (some #{:connector.source/literal-credential}
              (codes (validate (assoc valid :auth {:type "api_key"
                                                   :value "sekret"})))))))

(deftest auth-oauth2-variant
  (testing "missing token_url"
    (is (some #(= [:source :auth :token_url] (:path %))
              (:findings (validate (assoc valid :auth {:type "oauth2"}))))))
  (testing "cleartext token_url to a non-localhost host"
    (is (some #{:connector.source/invalid-shape}
              (codes (validate (assoc valid :auth
                                      {:type "oauth2"
                                       :token_url "http://idp.example/token"
                                       :client_id "a"
                                       :client_secret "${S}"}))))))
  (testing "literal client_secret"
    (is (some #{:connector.source/literal-credential}
              (codes (validate (assoc valid :auth
                                      {:type "oauth2"
                                       :token_url "https://idp/token"
                                       :client_id "a"
                                       :client_secret "sekret"})))))))

(deftest the-public-half-of-a-credential-pair-is-a-credential-key
  ;; Owner ruling 2026-08-28: the credential-key list is drawn around "part
  ;; of the authentication material", not "is a secret" — so :client_id and
  ;; :username are starred alongside the secrets they pair with.
  (letfn [(literal-at [path auth]
            (some #(and (= :connector.source/literal-credential (:code %))
                        (= path (:path %)))
                  (:findings (validate (assoc valid :auth auth)))))]
    (testing "literal :client_id is refused"
      (is (literal-at [:source :auth :client_id]
                      {:type "oauth2" :token_url "https://idp/token"
                       :client_id "acme-prod" :client_secret "${S}"})))
    (testing "literal :username is refused whatever the grant"
      (is (literal-at [:source :auth :username]
                      {:type "oauth2" :token_url "https://idp/token"
                       :grant_type "password"
                       :username "svc-reader" :password "${P}"})))
    (testing "an embedded var is not enough on a credential key — whole value only"
      (is (literal-at [:source :auth :client_id]
                      {:type "oauth2" :token_url "https://idp/token"
                       :client_id "svc-${ENV}-reader" :client_secret "${S}"})))))

(deftest a-credential-key-is-reported-once-not-twice
  ;; :client_id used to be reachable by two rules at once; a key reported
  ;; twice reads as two problems and sends the reader looking for a second
  ;; one that is not there.
  (let [fs (:findings (validate (assoc valid :auth
                                       {:type "oauth2"
                                        :token_url "https://idp/token"
                                        :client_id "${DEFINITELY_UNSET_XYZ}"
                                        :client_secret "${ALSO_UNSET_XYZ}"})))]
    (is (= 1 (count (filter #(= [:source :auth :client_id] (:path %)) fs))))
    (testing "and it is reported as unresolved, not as missing"
      (is (= :connector.source/invalid-shape
             (:code (first (filter #(= [:source :auth :client_id] (:path %)) fs))))))))

(deftest non-credential-auth-keys-still-allow-embedded-vars
  ;; The star governs credential keys only. :token_url is ordinary config,
  ;; so a host injected mid-string stays legal there.
  (with-env {"IDP_HOST" "idp.example" "CID" "c" "SEC" "s"}
    (is (= [] (:findings (validate (assoc valid :auth
                                          {:type "oauth2"
                                           :token_url "https://${IDP_HOST}/token"
                                           :client_id "${CID}"
                                           :client_secret "${SEC}"}))))
        "an embedded var in :token_url is config, not a literal credential")))

(deftest batch-support-policy
  (testing "reference-implementation extensions are rejected, pointedly"
    (let [r (validate (assoc valid :batch {:body-mode "root-array"
                                           :collect-key :p/sku}))]
      (is (some #(and (= :connector.source/unknown-key (:code %))
                      (re-find #"body-mode" (:message %)))
                (:findings r)))))
  (testing "shape-1 required keys"
    (is (some #{:connector.source/missing-key}
              (codes (validate (assoc valid :batch {:parent-key :p/sku}))))))
  (testing "placeholder must occur in :endpoint or :json_body"
    (is (some #{:connector.source/placeholder-mismatch}
              (codes (validate (assoc valid :batch
                                      {:parent-key :p/sku :child-fk "sku"
                                       :placeholder "sku_nos"}))))))
  (testing "a well-formed shape-1 batch validates clean"
    (is (= [] (:findings
               (validate (assoc valid
                                :endpoint "/parts?skus={sku_nos}"
                                :batch {:parent-key :p/sku :child-fk "sku"
                                        :placeholder "sku_nos"
                                        :chunk-size 50})))))))

(deftest param-location-vocabulary
  (is (some #{:connector.source/invalid-shape}
            (codes (validate (assoc valid :eql-params
                                    {:ids {:field "ids"
                                           :param_location "root"}}))))))

(deftest optional-placeholders-not-allowed-in-endpoint
  (is (some #(and (= :connector.source/invalid-shape (:code %))
                  (= [:source :endpoint] (:path %)))
            (:findings (validate (assoc valid :endpoint "/v/{p/sku}/{?rev}"))))))

(deftest auth-tls-and-insecure-exclusive
  (is (some #(= [:source :auth :ssl_insecure?] (:path %))
            (:findings (validate (assoc valid :auth
                                        {:type "oauth2"
                                         :token_url "https://idp/token"
                                         :client_id "a" :client_secret "${S}"
                                         :tls {:keystore "certs/x.p12"
                                               :keystore_pass "${P}"}
                                         :ssl_insecure? true}))))))

(deftest endpoint-placeholder-must-bind
  (is (some #{:connector.source/placeholder-mismatch}
            (codes (validate (assoc valid :endpoint "/v/{v/unknown}"))))))

(deftest timeout-cannot-be-disabled
  (is (some #{:connector.source/timeout-not-enforceable}
            (codes (validate (assoc valid :resilience {:timeout_ms 0}))))))

(deftest mutation-validation
  (testing "GET is not a mutation method"
    (is (some #{:connector.source/invalid-shape}
              (mapv :code (:findings (spi/validate-mutation valid))))))
  (testing ":batch on a mutation is rejected"
    (is (some #(= [:source :batch] (:path %))
              (:findings (spi/validate-mutation
                          (assoc valid :method "POST"
                                 :endpoint "/parts?skus={sku_nos}"
                                 :batch {:parent-key :p/sku :child-fk "sku"
                                         :placeholder "sku_nos"}))))))
  (testing "a POST mutation with body + mapping validates clean"
    (is (= [] (:findings (spi/validate-mutation
                          (assoc valid :method "POST"
                                 :json_body {:sku "{p/sku}"})))))))

;; ---------------------------------------------------------------------------
;; :base_url placeholders — authority only (owner 2026-09-07, doc 106 §106.14 item 11)
;; ---------------------------------------------------------------------------

(def templated
  (assoc valid
         :base_url "https://{nifi.instance/host}:9443/nifi-api"
         :endpoint "/flow/about"
         :input [:nifi.instance/host]
         :field_mapping {:nifi.instance/version "$.about.version"}))

(deftest a-host-placeholder-is-accepted
  (is (= [] (:findings (validate templated))))
  (testing "host with no port, and the reserved spelling, are the same position"
    (is (= [] (:findings (validate (assoc templated :base_url
                                          "https://{nifi.instance/host}/nifi-api")))))
    (is (= [] (:findings (validate (assoc templated :base_url
                                          "https://{+nifi.instance/host}:9443/nifi-api")))))))

(deftest a-scheme-or-path-placeholder-is-refused-by-position
  (let [scheme (validate (assoc templated :base_url "{p/scheme}://host:9443/nifi-api"))
        path   (validate (assoc templated :base_url
                                "https://{nifi.instance/host}:9443/{p/tenant}/nifi-api"))]
    ;; A templated scheme is also not `http(s)://`, so it collects the shape
    ;; finding too — both are true and both are reported.
    (is (some #{:connector.source/placeholder-mismatch} (codes scheme)))
    (is (some #(re-find #"SCHEME" (:message %)) (:findings scheme)))
    (is (= [:connector.source/placeholder-mismatch] (codes path)))
    (is (re-find #"PATH" (:message (first (:findings path)))))
    (testing "every refusal stays registry-shaped"
      (doseq [f (concat (:findings scheme) (:findings path))]
        (is (spi/finding? f) (pr-str f))))))

(deftest a-host-placeholder-no-input-binds-is-refused
  (let [r (validate (assoc templated :input []))]
    (is (= [:connector.source/placeholder-mismatch] (codes r)))
    (is (re-find #"no binding in :base_url" (:message (first (:findings r)))))))

(deftest a-bare-source-entry-is-not-asked-to-bind-anything
  (testing "declare_source validates the ENTRY, where no resolver is in view;
            refusing there would make a templated source undeclarable"
    (is (= [] (:findings (validate (dissoc templated :input)))))))

;; ---------------------------------------------------------------------------
;; In-record collections (doc 106 §106.14 item 15)
;; ---------------------------------------------------------------------------

(defn- branch [b] (assoc valid :field_mapping (merge (:field_mapping valid) b)))

(deftest an-in-record-collection-validates-clean
  (is (= [] (:findings (validate (branch {:p/parts {:_from "$.parts[*]"
                                                    :part/id "$.id"}}))))))

(deftest a-branch-array-path-ends-in-the-array-marker
  (let [r (validate (branch {:p/parts {:_from "$.parts" :part/id "$.id"}}))]
    (is (some #{:connector.source/invalid-shape} (codes r)))
    (is (= [[:source :field_mapping :p/parts :_from]]
           (mapv :path (:findings r))))))

(deftest a-branch-that-names-only-its-array-maps-nothing
  (is (some #{:connector.source/missing-key}
            (codes (validate (branch {:p/parts {:_from "$.parts[*]"}}))))))

(deftest a-branch-leaf-is-read-against-ONE-element
  (testing "a leaf written from the response root is refused, naming the leaf"
    (let [r (validate (branch {:p/parts {:_from "$.parts[*]"
                                         :part/id "$.parts[*].id"}}))]
      (is (some #{:connector.source/invalid-shape} (codes r)))
      (is (= [[:source :field_mapping :p/parts :part/id]]
             (mapv :path (:findings r)))))))

(deftest a-nested-branch-is-judged-too
  (is (some #{:connector.source/invalid-shape}
            (codes (validate (branch {:p/parts {:_from "$.parts[*]"
                                                :part/id "$.id"
                                                :part/bins {:_from "$.bins"
                                                            :bin/id "$.id"}}}))))))

(deftest a-list-data-path-beside-a-branch-is-ambiguous
  (testing "«允许同现，仅当不含歧义»: a :data_path locating a LIST is not"
    (let [r (validate (assoc (branch {:p/parts {:_from "$.parts[*]" :part/id "$.id"}})
                             :data_path "$.items[*]"))
          fs (filter #(= [:source :data_path] (:path %)) (:findings r))]
      (is (= 1 (count fs)))
      (is (re-find #"p/parts" (:message (first fs))))))
  (testing "a :data_path locating ONE record is the ordinary record+collection shape"
    (is (= [] (:findings (validate (assoc (branch {:p/parts {:_from "$.parts[*]"
                                                             :part/id "$.id"}})
                                          :data_path "$.processGroupFlow")))))))

;; ---------------------------------------------------------------------------
;; :resilience / :policy key face (doc 107 §107.3 / §107.8, owner 2026-09-18)
;; ---------------------------------------------------------------------------

(deftest resilience-key-face-is-closed
  (testing "the ruled keys pass"
    (is (= [] (:findings (validate (assoc valid :resilience
                                          {:timeout_ms 5000 :max_retries 2
                                           :retry_on #{:rate_limited :unavailable}
                                           :backoff_ms [200 2000 2.0] :jitter 0.2
                                           :bulkhead {:concurrency 8 :max_wait_ms 100}
                                           :circuit_breaker {:failure_ratio [20 50]
                                                             :reset_ms 10000}}))))))
  (testing "an unlisted key is REFUSED, not accepted and ignored"
    ;; §107.1's worked example: a modeller writes a key, validation passes, and
    ;; nothing happens. Closed means the refusal names the key.
    (let [r (validate (assoc valid :resilience {:timeout_ms 5000 :retry_forever true}))]
      (is (= [:connector.source/unknown-key] (codes r)))
      (is (= [:source :resilience] (:path (first (:findings r)))))
      (is (re-find #"retry_forever" (:message (first (:findings r)))))))
  (testing "a retry declared on a non-transient category is refused (D6.4)"
    (doseq [cat [:source :mapping :unauthorized :not-found :input-rejected
                 :circuit_open :bulkhead_rejected :cancelled :timeout]]
      (let [r (validate (assoc valid :resilience {:retry_on #{cat}}))]
        (is (= [:connector.source/invalid-shape] (codes r)) (str cat))
        (is (= [:source :resilience :retry_on] (:path (first (:findings r))))))))
  (testing "a transient category is accepted"
    (doseq [cat spi/transient-categories]
      (is (= [] (:findings (validate (assoc valid :resilience {:retry_on #{cat}})))))))
  (testing "a negative retry budget is refused"
    (is (= [:connector.source/invalid-shape]
           (codes (validate (assoc valid :resilience {:max_retries -1})))))))

(deftest policy-carries-only-the-semantic-half
  (testing "the modeller's keys pass"
    (is (= [] (:findings (validate (assoc valid :policy {:max_retries 2
                                                         :retry_on #{:rate_limited}}))))))
  (testing "a capacity key in the graph's half is refused"
    ;; §107.3 draws the line: how many permits a source gets is an operations
    ;; judgement and does not belong in an artifact.
    (doseq [k [:timeout_ms :bulkhead :circuit_breaker :backoff_ms :jitter]]
      (is (= [:connector.source/unknown-key]
             (codes (validate (assoc valid :policy {k 1}))))
          (str k))))
  (testing "the same retry_on rule applies to a binding override"
    (is (= [:connector.source/invalid-shape]
           (codes (validate (assoc valid :policy {:retry_on #{:source}})))))))
