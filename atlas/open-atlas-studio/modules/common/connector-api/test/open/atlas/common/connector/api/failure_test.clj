(ns open.atlas.common.connector.api.failure-test
  "Failure semantics: the migrated open-atlas plugin returned nil on any
   HTTP failure; the platform SPI defines nil as 'cannot supply these
   attributes', so every failure must be a categorized spi/fail! instead.
   HTTP is mocked — no sockets."
  (:require [clj-http.client :as http]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.connector.api :as api]
            [open.atlas.common.connector.api.impl.request :as request]
            [open.atlas.common.connector.spi :as spi])
  (:import [java.net ConnectException SocketTimeoutException]))

;; Bulkhead and breaker state is process-wide and keyed by source identity
;; (D6.5) -- it deliberately outlives a compiled env, so tests that trip a
;; breaker must not decide the next test's verdict.
(use-fixtures :each (fn [t] (api/reset-resilience-state!) (t)))

(def source
  {:type "api" :base_url "http://upstream.test"
   :method "GET" :endpoint "/parts/{p/sku}"
   :field_mapping {:p/sku "$.sku" :p/name "$.name"}})

(def resolver {:id "part_by_sku" :input [:p/sku] :result_type "single"})

(defn- failure-category [thunk]
  (try (thunk) ::no-throw
       (catch clojure.lang.ExceptionInfo e
         (is (spi/failure? e) "ex-data must be exactly the closed failure face")
         (:error/category (ex-data e)))))

(deftest non-2xx-fails-with-a-category-that-names-what-happened
  ;; doc 107 §107.4 (owner 2026-09-18): before this every non-2xx was
  ;; `:source`, so 429 and 500 were the same answer and D6.4's "retry only
  ;; transient faults" had no category to test against.
  (doseq [[status category] {503 :unavailable 502 :unavailable 504 :unavailable
                             429 :rate_limited 408 :timeout
                             401 :unauthorized 403 :unauthorized
                             500 :source 409 :source 422 :source}]
    (api/reset-resilience-state!)
    (with-redefs [http/get (fn [_url _opts] {:status status :body "boom"})]
      (let [f (spi/compile-source source nil resolver)]
        (is (= category (failure-category #(f {:p/sku "A"} {})))
            (str "HTTP " status))))))

(deftest timeout-exception-fails-with-timeout-category
  (with-redefs [http/get (fn [_ _] (throw (SocketTimeoutException. "read timed out")))]
    (let [f (spi/compile-source source nil resolver)]
      (is (= :timeout (failure-category #(f {:p/sku "A"} {})))))))

(deftest connect-exception-fails-with-connection-category
  (with-redefs [http/get (fn [_ _] (throw (ConnectException. "refused")))]
    (let [f (spi/compile-source source nil resolver)]
      (is (= :connection (failure-category #(f {:p/sku "A"} {})))))))

(deftest failure-never-leaks-url-or-driver-text
  (with-redefs [http/get (fn [_ _] (throw (ConnectException. "refused to upstream.test")))]
    (let [f (spi/compile-source source nil resolver)]
      (try (f {:p/sku "A"} {}) (is false "expected a failure")
           (catch clojure.lang.ExceptionInfo e
             (is (not (re-find #"upstream\.test" (.getMessage e))))
             (is (= #{:error/category :resolver-id :source-type}
                    (set (keys (ex-data e))))))))))

(deftest unresolved-endpoint-placeholder-rejects-before-sending
  (let [called (atom false)]
    (with-redefs [http/get (fn [_ _] (reset! called true) {:status 200 :body "{}"})]
      (let [f (spi/compile-source source nil resolver)]
        (is (= :mapping (failure-category #(f {} {}))))
        (testing "the raw template was never sent upstream (api-v1 conformance)"
          (is (false? @called)))))))

(deftest unresolved-body-placeholder-rejects
  (let [src (assoc source :method "POST" :json_body {:sku "{p/sku}" :line "{lineNo}"})]
    (with-redefs [http/post (fn [_ _] {:status 200 :body "{}"})]
      (let [f (spi/compile-source src nil resolver)]
        (is (= :mapping (failure-category #(f {:p/sku "A"} {}))))))))

(deftest optional-body-placeholder-drops-instead-of-rejecting
  (let [sent (atom nil)
        src  (assoc source :method "POST"
                    :json_body {:sku "{p/sku}" :line "{?lineNo}"})]
    (with-redefs [http/post (fn [_ opts] (reset! sent (:body opts))
                              {:status 200 :body "{\"sku\":\"A\"}"})]
      (let [f (spi/compile-source src nil resolver)]
        (f {:p/sku "A"} {})
        (is (= "{\"sku\":\"A\"}" @sent))))))

(deftest substituted-body-content-is-not-a-template
  (doseq [value ["{\"changes\":{\"title\":\"Example\"}}" "Literal {name}" "Literal {?optional}"]]
    (let [sent (atom nil)
          src (assoc source :method "POST" :json_body {:sku "{p/sku}" :payload "{payload}"})]
      (with-redefs [http/post (fn [_ opts] (reset! sent (:body opts))
                               {:status 200 :body "{\"sku\":\"A\"}"})]
        (let [f (spi/compile-source src nil (assoc resolver :eql-params {:payload {}}))]
          (f {:p/sku "A"} {:payload value})
          (is (= value (get (cheshire.core/parse-string @sent) "payload"))))))))

(deftest success-path-returns-mapped-single-or-nil
  (with-redefs [http/get (fn [_ _] {:status 200 :body "{\"sku\":\"A\",\"name\":\"Bolt\"}"})]
    (let [f (spi/compile-source source nil resolver)]
      (is (= {:p/sku "A" :p/name "Bolt"} (f {:p/sku "A"} {})))))
  (testing "an empty list answer under result_type list is []"
    (with-redefs [http/get (fn [_ _] {:status 200 :body "[]"})]
      (let [f (spi/compile-source (assoc source :field_mapping {:p/sku "sku"})
                                  nil (assoc resolver :result_type "list"))]
        (is (= [] (f {:p/sku "A"} {})))))))

(deftest batch-aligns-and-missing-to-one-is-nil
  (let [src (assoc source
                   :endpoint "/parts?skus={sku_nos}"
                   :batch {:parent-key :p/sku :child-fk "sku"
                           :placeholder "sku_nos"})]
    (with-redefs [http/get (fn [_ _] {:status 200
                                      :body "[{\"sku\":\"B\",\"name\":\"Nut\"}]"})]
      (let [f (spi/compile-batch-source src nil resolver)]
        (is (= [{:p/sku "B" :p/name "Nut"} nil]
               (f [{:p/sku "B"} {:p/sku "C"}] {})))))))

(deftest batch-chunking-issues-one-request-per-chunk
  (let [urls (atom [])
        src  (assoc source
                    :endpoint "/parts?skus={sku_nos}"
                    :batch {:parent-key :p/sku :child-fk "sku"
                            :placeholder "sku_nos" :chunk-size 2})]
    (with-redefs [http/get (fn [url _] (swap! urls conj url)
                             {:status 200 :body "[]"})]
      (let [f (spi/compile-batch-source src nil (assoc resolver :result_type "list"))]
        (is (= [[] [] []] (f [{:p/sku "A"} {:p/sku "B"} {:p/sku "C"}] {})))
        (is (= 2 (count @urls)))))))

(deftest health-check-shape
  (testing "ok"
    (with-redefs [http/get (fn [_ _] {:status 200 :body "{}"})]
      (let [r (spi/health-check source nil)]
        (is (= :ok (:status r)))
        (is (int? (:latency-ms r))))))
  (testing "non-2xx folds into :down (no :degraded in the platform vocabulary)"
    (with-redefs [http/get (fn [_ _] {:status 500 :body ""})]
      (let [r (spi/health-check source nil)]
        (is (= :down (:status r)))
        (is (= :source (:error/category r)))
        (is (not (contains? r :message))))))
  (testing "unresolved ${VAR} reports the variable NAME, no roundtrip"
    (let [called (atom false)]
      (with-redefs [http/get (fn [_ _] (reset! called true) {:status 200 :body ""})]
        (let [r (spi/health-check
                 (assoc source :headers {"X-Api-Key" "${DEFINITELY_UNSET_VAR_XYZ}"})
                 nil)]
          (is (= :down (:status r)))
          (is (= ["DEFINITELY_UNSET_VAR_XYZ"] (:missing-vars r)))
          (is (false? @called)))))))

(deftest interface-ns-registers-the-api-type
  (is (contains? (spi/registered-types) api/source-type)))

;; ---------------------------------------------------------------------------
;; A templated :base_url (owner 2026-09-07, doc 106 §106.14 item 11)
;; ---------------------------------------------------------------------------

(deftest a-templated-source-is-refused-a-whole-source-health-check
  (let [called (atom false)
        src (assoc source :base_url "https://{nifi.instance/host}:9443/nifi-api")]
    (with-redefs [http/get (fn [_ _] (reset! called true) {:status 200 :body "{}"})]
      (let [r (spi/health-check src nil)]
        (testing "refused, not :down — the fault would belong to no instance"
          (is (= :refused (:status r)))
          (is (false? @called)))
        (let [f (first (:findings r))]
          (is (spi/finding? f) (pr-str f))
          (is (= :connector.source/templated-health (:code f)))
          (is (= [:source :base_url] (:path f)))
          (is (re-find #"\{nifi.instance/host\}" (:expected f))))))
    (testing "bind the attribute and the same source is checked as one host"
      (with-redefs [http/get (fn [_ _] {:status 200 :body "{}"})]
        (let [bound (api/bind-base-url src {:nifi.instance/host "nifi-01.test"})]
          (is (= "https://nifi-01.test:9443/nifi-api" (:base_url bound)))
          (is (= :ok (:status (spi/health-check bound nil)))))))))

;; ---------------------------------------------------------------------------
;; 0.2 release note defect 11: «no such row» is an answer, not a failure.

(deftest a-404-on-a-by-key-read-is-no-such-row-not-a-failure
  (with-redefs [http/get (fn [_ _] {:status 404 :body "{\"message\":\"not found\"}"})]
    (let [f (spi/compile-source source nil resolver)]
      (is (nil? (f {:p/sku "nope"} {}))
          "nil = cannot supply; the engine turns that into an absent attribute"))))

(deftest every-other-4xx-and-5xx-stays-a-failure
  ;; The 404 above is the only status a by-key read answers with a missing row.
  ;; Every other non-2xx is still a FAILURE -- §107.4 changed which category
  ;; names it, not whether it is one.
  (doseq [status [400 401 403 409 500 503]]
    (api/reset-resilience-state!)
    (with-redefs [http/get (fn [_ _] {:status status :body "boom"})]
      (let [f (spi/compile-source source nil resolver)]
        (is (contains? spi/error-categories (failure-category #(f {:p/sku "A"} {})))
            (str "HTTP " status " says the request or the source is wrong"))))))

(deftest a-404-on-a-list-read-is-still-a-failure
  ;; An absent COLLECTION is a different claim from an absent record: nobody
  ;; asked for one row, so a 404 here is the endpoint being wrong.
  (with-redefs [http/get (fn [_ _] {:status 404 :body "[]"})]
    (let [f (spi/compile-source (assoc source :endpoint "/parts")
                                nil
                                {:id "all_parts" :input [] :result_type "list"})]
      (is (= :source (failure-category #(f {} {})))))))

;; ---------------------------------------------------------------------------
;; doc 107 §107.8 conformance: retry, breaker, bulkhead (owner 2026-09-18)
;; ---------------------------------------------------------------------------

(defn- with-policy
  "The source with an effective policy on it. Values are CANDIDATES
   (OQ-RESILIENCE-DEFAULTS); what is pinned is the behaviour, not the numbers."
  [& {:as resilience}]
  (assoc source :resilience (merge {:backoff_ms [10 40 2.0] :jitter 0} resilience)))

(deftest a-429-carries-its-retry-after-and-is-retried-exactly-once
  (let [calls (atom 0)
        waited (atom nil)
        src (with-policy :max_retries 1 :retry_on #{:rate_limited})]
    (with-redefs [http/get (fn [_ _] (swap! calls inc)
                             {:status 429 :headers {"Retry-After" "2"} :body "slow down"})]
      ;; The wait is asserted, not slept: a conformance test must not cost two
      ;; seconds to prove it would have waited two.
      (spi/with-retry-sleep (fn [ms] (reset! waited ms))
        (let [f (spi/compile-source src nil resolver)]
          (is (= :rate_limited (failure-category #(f {:p/sku "A"} {}))))
          (is (= 2 @calls) "the original attempt plus exactly one retry")
          (is (= 2000 @waited)
              "the upstream told us when it would answer; our backoff does not argue"))))))

(deftest retry-after-reaches-the-caller-as-the-one-allowed-upstream-number
  (with-redefs [http/get (fn [_ _] {:status 429 :headers {"retry-after" "2"} :body "x"})]
    (let [f (spi/compile-source (with-policy) nil resolver)
          e (try (f {:p/sku "A"} {}) (catch clojure.lang.ExceptionInfo e e))]
      (is (= 2000 (spi/retry-after-ms e)))
      (is (= #{:error/category :resolver-id :source-type :retry-after-ms}
             (set (keys (ex-data e))))
          "still closed: no URL, no body, no row value"))))

(deftest max-retries-zero-does-not-retry
  (let [calls (atom 0)]
    (with-redefs [http/get (fn [_ _] (swap! calls inc)
                             {:status 429 :headers {"Retry-After" "2"} :body "x"})]
      (let [f (spi/compile-source (with-policy :max_retries 0 :retry_on #{:rate_limited})
                                  nil resolver)]
        (is (= :rate_limited (failure-category #(f {:p/sku "A"} {}))))
        (is (= 1 @calls) "retry is opt-in; the default buys nothing extra")))))

(deftest an-unauthorized-is-never-retried
  ;; D6.4: an authorization refusal is deterministic. Repeating it is the same
  ;; answer, one more failed audit line, and one more chance to lock an account.
  (let [calls (atom 0)]
    (with-redefs [http/get (fn [_ _] (swap! calls inc) {:status 403 :body "no"})]
      (let [f (spi/compile-source
               (with-policy :max_retries 3
                 :retry_on #{:rate_limited :unavailable :connection})
               nil resolver)]
        (is (= :unauthorized (failure-category #(f {:p/sku "A"} {}))))
        (is (= 1 @calls))))))

(deftest a-502-is-unavailable-and-retried-when-the-policy-says-so
  (let [calls (atom 0)]
    (with-redefs [http/get (fn [_ _] (swap! calls inc)
                             (if (= 1 @calls)
                               {:status 502 :body "bad gateway"}
                               {:status 200 :body "{\"sku\":\"A\",\"name\":\"Bolt\"}"}))]
      (spi/with-retry-sleep (fn [_] nil)
        (let [f (spi/compile-source (with-policy :max_retries 2 :retry_on #{:unavailable})
                                    nil resolver)]
          (is (= {:p/sku "A" :p/name "Bolt"} (f {:p/sku "A"} {})))
          (is (= 2 @calls)))))))

(deftest an-open-breaker-fails-fast-without-sending-anything
  ;; D6.5: a source that is down should stop costing us sockets and deadline.
  (let [calls (atom 0)
        src (with-policy :circuit_breaker {:failure_ratio [4 50] :reset_ms 60000})]
    (with-redefs [http/get (fn [_ _] (swap! calls inc) {:status 500 :body "down"})]
      (let [f (spi/compile-source src nil resolver)]
        (dotimes [_ 4] (failure-category #(f {:p/sku "A"} {})))
        (is (= 4 @calls))
        (testing "the next call is refused BEFORE any HTTP"
          (is (= :circuit_open (failure-category #(f {:p/sku "A"} {}))))
          (is (= 4 @calls) "no fifth request left the process"))))))

(deftest an-open-breaker-half-opens-after-its-reset-window
  (let [calls (atom 0)
        answer (atom {:status 500 :body "down"})
        ;; The window has to outlast the four failing calls themselves, or the
        ;; breaker is already half-open by the time we look at it.
        src (with-policy :circuit_breaker {:failure_ratio [4 50] :reset_ms 300})]
    (with-redefs [http/get (fn [_ _] (swap! calls inc) @answer)]
      (let [f (spi/compile-source src nil resolver)]
        (dotimes [_ 4] (failure-category #(f {:p/sku "A"} {})))
        (is (= :circuit_open (failure-category #(f {:p/sku "A"} {}))))
        (Thread/sleep 350)
        (reset! answer {:status 200 :body "{\"sku\":\"A\",\"name\":\"Bolt\"}"})
        (testing "one probe is allowed through, and a good answer closes it"
          (is (= {:p/sku "A" :p/name "Bolt"} (f {:p/sku "A"} {})))
          (is (= {:p/sku "A" :p/name "Bolt"} (f {:p/sku "A"} {}))))))))

(deftest a-bulkhead-refuses-rather-than-queueing-forever
  ;; One permit, a wait short enough to expire, and a request that holds the
  ;; permit longer than that: the second caller is refused, not parked.
  (let [src (with-policy :bulkhead {:concurrency 1 :max_wait_ms 5})
        gate (promise)]
    (with-redefs [http/get (fn [_ _] @gate {:status 200 :body "{}"})]
      (let [f (spi/compile-source src nil resolver)
            held (future (f {:p/sku "A"} {}))]
        (Thread/sleep 50)
        (is (= :bulkhead_rejected (failure-category #(f {:p/sku "B"} {}))))
        (deliver gate true)
        @held))))

(deftest a-mutation-is-never-retried
  ;; D6.4: retry needs known idempotency, and api-v1 has no key for declaring
  ;; it on a mutation. So the answer is "no" rather than "probably fine".
  (let [calls (atom 0)
        src (assoc (with-policy :max_retries 3 :retry_on #{:unavailable})
                   :method "POST" :endpoint "/parts")]
    (with-redefs [http/post (fn [_ _] (swap! calls inc) {:status 503 :body "x"})]
      (let [m (spi/compile-mutation src nil {:id "make_part" :result_type "none"})]
        (is (= :unavailable (failure-category #(m {}))))
        (is (= 1 @calls))))))
