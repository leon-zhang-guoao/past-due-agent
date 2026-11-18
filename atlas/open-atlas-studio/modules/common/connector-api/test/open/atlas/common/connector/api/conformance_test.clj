(ns open.atlas.common.connector.api.conformance-test
  "api-v1 conformance over REAL sockets (a JDK HttpServer stub on
   127.0.0.1) — the runtime paths failure-test's clj-http mocks cannot
   reach: auth-header assembly on the wire, the OAuth2 token lifecycle
   (acquire, cache, single-flight, failure cooldown), and the 401
   refresh-and-retry-ONCE rule with its fresh-token guard.

   Time is controlled by redef-ing oauth/now-ms; the token cache is global,
   so every test resets it."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.connector.api :as api]
            [open.atlas.common.connector.api.impl.oauth :as oauth]
            [open.atlas.common.connector.api.test-env :refer [with-env]]
            [open.atlas.common.connector.spi :as spi])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(use-fixtures :each (fn [t] (api/reset-oauth-cache!) (api/reset-resilience-state!) (t)))

;; ---------------------------------------------------------------------------
;; Stub HTTP server
;; ---------------------------------------------------------------------------

(defn- start-stub
  "Start an HttpServer on an ephemeral port. `handler` receives
   {:method :path :headers {lowercase-name [v ...]} :body string} and
   returns {:status n :body string}. Returns {:server s :port n
   :requests atom-of-request-maps}."
  [handler]
  (let [requests (atom [])
        server   (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/"
     ;; proxy, not reify: HttpHandler/handle is void, and reify rejects a
     ;; body whose last form is Object-typed.
     (proxy [HttpHandler] []
       (handle [^HttpExchange ex]
         (let [req  {:method  (.getRequestMethod ex)
                     :path    (.getPath (.getRequestURI ex))
                     :headers (into {}
                                    (map (fn [[k vs]]
                                           [(str/lower-case (str k)) (vec vs)]))
                                    (.getRequestHeaders ex))
                     :body    (slurp (.getRequestBody ex))}
               _    (swap! requests conj req)
               {:keys [status body] :or {status 200 body "{}"}} (handler req)
               bytes (.getBytes ^String body "UTF-8")]
           (.add (.getResponseHeaders ex) "Content-Type" "application/json")
           (.sendResponseHeaders ex status (alength bytes))
           (with-open [os (.getResponseBody ex)]
             (.write os bytes))))))
    (.start server)
    {:server server :port (.getPort (.getAddress server)) :requests requests}))

(defmacro with-stub
  "Bind {:keys [port requests]} of a running stub for `handler`, stopping
   it afterwards."
  [[binding handler] & body]
  `(let [stub# (start-stub ~handler)
         ~binding stub#]
     (try ~@body
          (finally (.stop ^HttpServer (:server stub#) 0)))))

(defn- token-response
  ([] (token-response "tok-1"))
  ([access & {:as extra}]
   {:status 200
    :body (json/generate-string (merge {:access_token access :expires_in 3600}
                                       extra))}))

(defn- source-for [port & {:as overrides}]
  (merge {:type "api" :base_url (str "http://127.0.0.1:" port)
          :method "GET" :endpoint "/parts/{p/sku}"
          :field_mapping {:p/sku "$.sku"}}
         overrides))

(def resolver {:id "part_by_sku" :input [:p/sku] :result_type "single"})

(defn- oauth2-auth [port]
  ;; Literal secret: the literal-credential rule is WRITE-time (validate);
  ;; the runtime accepts what the store already holds.
  {:type "oauth2" :token_url (str "http://127.0.0.1:" port "/token")
   :client_id "cid" :client_secret "sec"
   :allow_insecure_token_url? true})

(defn- token-hits [requests]
  (count (filter #(= "/token" (:path %)) @requests)))

(defn- data-requests [requests]
  (filter #(not= "/token" (:path %)) @requests))

(defn- auth-header [req]
  (first (get-in req [:headers "authorization"])))

(defn- failure-category [thunk]
  (try (thunk) ::no-throw
       (catch clojure.lang.ExceptionInfo e
         (is (spi/failure? e))
         (:error/category (ex-data e)))))

;; ---------------------------------------------------------------------------
;; Auth headers on the wire
;; ---------------------------------------------------------------------------

(deftest api-key-header-reaches-the-wire
  (with-stub [{:keys [port requests]}
              (fn [_] {:status 200 :body "{\"sku\":\"A\"}"})]
    (let [src (source-for port
                          :headers {"X-Static" "s1"}
                          :auth {:type "api_key" :value "k-123"
                                 :header_name "X-Api-Key"})
          f   (spi/compile-source src nil resolver)]
      (is (= {:p/sku "A"} (f {:p/sku "A"} {})))
      (let [req (first (data-requests requests))]
        (is (= ["k-123"] (get-in req [:headers "x-api-key"]))
            "api_key lands under its configured :header_name, no prefix by default")
        (testing "static :headers ride along with the auth header"
          (is (= ["s1"] (get-in req [:headers "x-static"]))))))))

;; ---------------------------------------------------------------------------
;; ${VAR} substitution reaches the wire
;; ---------------------------------------------------------------------------

(deftest oauth-client-id-is-substituted-before-the-token-request
  ;; The regression this guards: :client_id was NOT ${VAR}-resolved, so a
  ;; corpus written as "${SE_CLIENT_ID}" sent that literal to the IdP as the
  ;; client id — and with :client_auth "basic" it went into the Basic header.
  (with-env {"CID" "real-client-id" "SEC" "real-secret"}
    (with-stub [{:keys [port requests]}
                (fn [req] (if (= "/token" (:path req))
                            (token-response "tok-1")
                            {:status 200 :body "{\"sku\":\"A\"}"}))]
      (let [src (source-for port
                            :auth {:type "oauth2"
                                   :token_url (str "http://127.0.0.1:" port "/token")
                                   :client_id "${CID}"
                                   :client_secret "${SEC}"
                                   :allow_insecure_token_url? true})
            f   (spi/compile-source src nil resolver)]
        (is (= {:p/sku "A"} (f {:p/sku "A"} {})))
        (let [tok (first (filter #(= "/token" (:path %)) @requests))]
          (is (str/includes? (:body tok) "client_id=real-client-id"))
          (is (not (str/includes? (:body tok) "CID"))
              "no placeholder text survives anywhere in the token request"))))))

(deftest header-and-base-url-vars-are-substituted
  (with-env {"GATEWAY_KEY" "gk-42"}
    (with-stub [{:keys [port requests]}
                (fn [_] {:status 200 :body "{\"sku\":\"A\"}"})]
      (let [src (source-for port :headers {"X-Api-Key" "${GATEWAY_KEY}"})
            f   (spi/compile-source src nil resolver)]
        (f {:p/sku "A"} {})
        (is (= ["gk-42"] (get-in (first @requests) [:headers "x-api-key"]))
            "a credential header the contract says is ${VAR}-only must arrive resolved")))))

(deftest an-unresolved-variable-refuses-before-any-request
  (with-env {}
    (let [called (atom false)]
      (with-redefs [http/get (fn [_ _] (reset! called true) {:status 200 :body "{}"})
                    http/post (fn [_ _] (reset! called true) {:status 200 :body "{}"})]
        (let [src (assoc (source-for 1) :headers {"X-Api-Key" "${DEFINITELY_UNSET}"})
              f   (spi/compile-source src nil resolver)]
          (is (= :connection (failure-category #(f {:p/sku "A"} {})))
              "a blanked-out variable is a connection fault, not a silent empty header")
          (is (false? @called) "nothing is sent while the config is unresolved"))))))

(deftest unresolved-variable-names-are-reported-values-are-not
  (with-env {"KNOWN" "v"}
    (let [src (assoc (source-for 1) :headers {"X-Api-Key" "${SECRET_VAR_NAME}"})
          f   (spi/compile-source src nil resolver)]
      (try (f {:p/sku "A"} {}) (is false "expected a failure")
           (catch clojure.lang.ExceptionInfo e
             (is (str/includes? (.getMessage e) "SECRET_VAR_NAME")
                 "the NAME is dialect configuration and is what makes the fault fixable")
             (is (= #{:error/category :resolver-id :source-type}
                    (set (keys (ex-data e))))
                 "the closed failure face is unchanged"))))))

;; ---------------------------------------------------------------------------
;; OAuth2 lifecycle
;; ---------------------------------------------------------------------------

(deftest oauth2-token-is-fetched-once-and-cached
  (with-stub [{:keys [port requests]}
              (fn [req] (if (= "/token" (:path req))
                          (token-response "tok-1")
                          {:status 200 :body "{\"sku\":\"A\"}"}))]
    (let [src (source-for port :auth (oauth2-auth port))
          f   (spi/compile-source src nil resolver)]
      (is (= {:p/sku "A"} (f {:p/sku "A"} {})))
      (is (= {:p/sku "A"} (f {:p/sku "A"} {})))
      (testing "one token roundtrip serves both requests"
        (is (= 1 (token-hits requests))))
      (testing "the client_credentials grant + body client auth were POSTed"
        (let [tok (first (filter #(= "/token" (:path %)) @requests))]
          (is (str/includes? (:body tok) "grant_type=client_credentials"))
          (is (str/includes? (:body tok) "client_id=cid"))))
      (testing "every data request carried the bearer token"
        (is (= ["Bearer tok-1" "Bearer tok-1"]
               (map auth-header (data-requests requests))))))))

(deftest expired-token-401-refreshes-and-retries-once
  (let [clock (atom 1000000)]
    (with-redefs [oauth/now-ms (fn [] @clock)]
      (let [tokens  (atom 0)
            expired (atom false)]
        (with-stub [{:keys [port requests]}
                    (fn [req]
                      (cond
                        (= "/token" (:path req))
                        (token-response (str "tok-" (swap! tokens inc)))

                        ;; tok-1 works until the upstream expires it.
                        (and @expired (= (auth-header req) "Bearer tok-1"))
                        {:status 401 :body "{}"}

                        :else {:status 200 :body "{\"sku\":\"A\"}"}))]
          (let [src (source-for port :auth (oauth2-auth port))
                f   (spi/compile-source src nil resolver)]
            ;; Seed the cache, then age the token past the 10 s fresh window
            ;; (still short of its 3600 s expiry) and have the upstream
            ;; reject it — the 401 now reads as 'stale token'.
            (f {:p/sku "A"} {})
            (reset! expired true)
            (swap! clock + 60000)
            (is (= {:p/sku "A"} (f {:p/sku "A"} {}))
                "the 401 answer is replaced by the retried request's answer")
            (is (= 2 (token-hits requests)))
            (is (= ["Bearer tok-1" "Bearer tok-1" "Bearer tok-2"]
                   (map auth-header (data-requests requests)))
                "exactly one retry, with the fresh token")))))))

(deftest fresh-token-401-is-final-no-refresh
  (with-stub [{:keys [port requests]}
              (fn [req] (if (= "/token" (:path req))
                          (token-response "tok-1")
                          {:status 401 :body "{}"}))]
    (let [src (source-for port :auth (oauth2-auth port))
          f   (spi/compile-source src nil resolver)]
      ;; A token issued milliseconds ago cannot have expired: the 401 is an
      ;; authorization decision about the request. doc 107 §107.4 gives that
      ;; decision its own category so D6.4's "never retry an authorization
      ;; refusal" has something to test; the refresh behaviour is unchanged.
      (is (= :unauthorized (failure-category #(f {:p/sku "A"} {}))))
      (is (= 1 (token-hits requests)) "no refresh roundtrip")
      (is (= 1 (count (data-requests requests))) "no retry"))))

(deftest second-401-after-refresh-is-final
  (let [clock (atom 1000000)]
    (with-redefs [oauth/now-ms (fn [] @clock)]
      (with-stub [{:keys [port requests]}
                  (fn [req] (if (= "/token" (:path req))
                              (token-response (str "tok-" (rand-int 1e9)))
                              {:status 401 :body "{}"}))]
        (let [src (source-for port :auth (oauth2-auth port))
              f   (spi/compile-source src nil resolver)]
          ;; Seed the cache, then age the token out of the fresh window.
          (is (= :unauthorized (failure-category #(f {:p/sku "A"} {}))))
          (swap! clock + 60000)
          (is (= :unauthorized (failure-category #(f {:p/sku "A"} {}))))
          (testing "refresh-and-retry happened at most ONCE for the second call"
            ;; call 1: token + request (fresh guard, final). call 2:
            ;; request + refresh + one retried request — never a third.
            (is (= 2 (token-hits requests)))
            (is (= 3 (count (data-requests requests))))))))))

(deftest token-endpoint-failure-is-connection-and-cools-down
  (with-stub [{:keys [port requests]}
              (fn [req] (if (= "/token" (:path req))
                          {:status 500 :body "{}"}
                          {:status 200 :body "{\"sku\":\"A\"}"}))]
    (let [src (source-for port :auth (oauth2-auth port))
          f   (spi/compile-source src nil resolver)]
      (is (= :connection (failure-category #(f {:p/sku "A"} {})))
          "the source's IdP is part of its connectivity")
      (is (= :connection (failure-category #(f {:p/sku "A"} {}))))
      (testing "the second call failed fast from the cooldown cache"
        (is (= 1 (token-hits requests))))
      (testing "no data request was attempted without a token"
        (is (= 0 (count (data-requests requests))))))))

(deftest token-acquisition-is-single-flight
  (let [tokens (atom 0)]
    (with-stub [{:keys [port requests]}
                (fn [req]
                  (if (= "/token" (:path req))
                    (do (Thread/sleep 200)
                        (token-response (str "tok-" (swap! tokens inc))))
                    {:status 200 :body "{\"sku\":\"A\"}"}))]
      (let [src (source-for port :auth (oauth2-auth port))
            f   (spi/compile-source src nil resolver)
            calls (doall (repeatedly 4 #(future (f {:p/sku "A"} {}))))]
        (is (every? #(= {:p/sku "A"} @%) calls))
        (testing "concurrent first calls share ONE token roundtrip"
          (is (= 1 (token-hits requests))))))))

(deftest refresh-token-rotation-is-honored
  (let [clock (atom 1000000)]
    (with-redefs [oauth/now-ms (fn [] @clock)]
      (with-stub [{:keys [port requests]}
                  (fn [req]
                    (if (= "/token" (:path req))
                      ;; Short-lived tokens force a refresh on the second
                      ;; call; each answer rotates the RT (RFC 6749 §6).
                      (if (str/includes? (:body req) "refresh_token=rt-1")
                        (token-response "tok-2" :expires_in 30 :refresh_token "rt-2")
                        (token-response "tok-1" :expires_in 30 :refresh_token "rt-1"))
                      {:status 200 :body "{\"sku\":\"A\"}"}))]
        (let [src (source-for port :auth (oauth2-auth port))
              f   (spi/compile-source src nil resolver)]
          (f {:p/sku "A"} {})
          (swap! clock + 60000)                 ;; past the 30 s expiry
          (f {:p/sku "A"} {})
          (let [toks (filter #(= "/token" (:path %)) @requests)]
            (is (= 2 (count toks)))
            (testing "the second acquisition used the rotated refresh token"
              (is (str/includes? (:body (second toks))
                                 "grant_type=refresh_token"))
              (is (str/includes? (:body (second toks)) "refresh_token=rt-1"))))
          (is (= ["Bearer tok-1" "Bearer tok-2"]
                 (map auth-header (data-requests requests)))))))))

(deftest offset-capability-matches-the-contract
  ;; api-v1「能力」：offset ✓（best-effort）。impl.interp maps it onto the
  ;; upstream's own offset field; the gate refuses it for undeclared types (8.17).
  (is (true? (spi/supports-offset? api/source-type))))
