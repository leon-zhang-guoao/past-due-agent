(ns open.atlas.common.connector.api.batch-isolation-test
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.api :as api]
            [open.atlas.common.connector.spi :as spi])
  (:import [java.net SocketTimeoutException URLDecoder]))

(def source
  {:type "api" :base_url "https://fixture.invalid" :method "GET"
   :endpoint "/parts?ids={ids}" :field_mapping {:p/detail "$.id" :p/brand "$.brand"}
   :batch {:parent-key :p/id :child-fk "id" :placeholder "ids"
           :chunk-size 4 :isolate-statuses #{400 404}}})
(def resolver {:id "details" :input [:p/id] :output [:p/detail :p/brand] :result_type "single"})
(defn ids [url] (str/split (last (str/split (URLDecoder/decode url "UTF-8") #"=")) #","))
(defn inputs [xs] (mapv #(hash-map :p/id %) xs))
(defn ok [xs]
  {:status 200 :body (json/generate-string (mapv #(hash-map :id % :brand "Fixture") (reverse xs)))})

(deftest isolates-failed-keys-and-keeps-input-alignment
  (let [calls (atom []) statements (atom [])
        f (spi/compile-batch-source source nil resolver)]
    (with-redefs [http/get (fn [url _]
                            (let [xs (ids url)]
                              (swap! calls conj xs)
                              (if (some #{"BAD"} xs) {:status 400 :body "private-upstream-error"}
                                (ok (remove #{"ABSENT"} xs)))))]
      (binding [spi/*statement-trace* #(swap! statements conj %)]
        (let [[a bad b absent dup] (f (inputs ["A" "BAD" "B" "ABSENT" "BAD"]) {})]
          (is (= "A" (:p/detail a)))
          (is (= "B" (:p/detail b)))
          (is (nil? absent) "a successful response omitting a key still uses the normal missing slot")
          (is (spi/batch-item-failure? bad))
          (is (identical? bad dup) "a duplicate failed key retains its own aligned slot")
          ;; doc 107 §107.4: the category now NAMES the claim. `:input-rejected`
          ;; is what isolation always meant -- «this input could not be
          ;; fetched», which is deliberately not «this row does not exist» --
          ;; and `:isolate-statuses` rewrites to exactly that (§107.5).
          (is (= {:resolver-id "details" :source-type "api" :http-status 400
                  :error/category :input-rejected}
                 (spi/batch-item-failure-info bad)))
          (is (= [["A" "BAD" "B" "ABSENT"] ["A" "BAD"] ["A"] ["BAD"] ["B" "ABSENT"]] @calls))
          (is (= [false false true false true] (mapv :ok? @statements)))
          (is (= [400 400 200 400 200] (mapv :http-status @statements)))
          (is (not (str/includes? (pr-str (spi/statement-audit-summary @statements)) "BAD")))
          (is (not (str/includes? (pr-str (spi/statement-audit-summary @statements)) "private-upstream-error"))))))))

(defn- platform-category
  "§107.4's default status -> category table, as the test reads it."
  [status]
  (condp contains? status
    #{401 403} :unauthorized
    #{429}     :rate_limited
    #{502 503 504} :unavailable
    :source))

(deftest default-and-nonselected-failures-abort-without-splitting
  (doseq [[statuses status] [[nil 400] [nil 404] [#{400} 404] [#{404} 400]
                             [#{400 404} 401] [#{400 404} 403] [#{400 404} 429]
                             [#{400 404} 500] [#{400 404} 503]]]
    (let [calls (atom 0)
          src (update source :batch #(if statuses (assoc % :isolate-statuses statuses) (dissoc % :isolate-statuses)))
          f (spi/compile-batch-source src nil resolver)]
      (with-redefs [http/get (fn [& _] (swap! calls inc) {:status status :body "private"})]
        (let [e (try (f (inputs ["A" "BAD"]) {}) (catch Exception e e))]
          (is (spi/failure? e) (str [statuses status]))
          ;; doc 107 §107.4: the whole chunk still fails without splitting --
          ;; what changed is that the CATEGORY now names what happened. A
          ;; protected status keeps the platform's classification precisely so
          ;; that isolation cannot be used to bisect past authorization
          ;; (401/403), throttling (429) or a system failure (502/503/504).
          (is (= (platform-category status) (spi/error-category e))
              (str [statuses status]))
          (is (= 1 @calls)))))))

(deftest transport-timeouts-still-abort
  (let [calls (atom 0) f (spi/compile-batch-source source nil resolver)]
    (with-redefs [http/get (fn [& _] (swap! calls inc) (throw (SocketTimeoutException. "private")))]
      (let [e (try (f (inputs ["A" "BAD"]) {}) (catch Exception e e))]
        (is (= :timeout (spi/error-category e)))
        (is (= 1 @calls))))))

(deftest all-failing-chunks-have-a-finite-request-bound
  (doseq [status [400 404]]
    (let [calls (atom 0) f (spi/compile-batch-source source nil resolver)]
      (with-redefs [http/get (fn [& _] (swap! calls inc) {:status status :body "{}"})]
        (let [out (f (inputs ["A" "B" "C" "D" "E"]) {})]
          (is (= 5 (count out)))
          (is (every? spi/batch-item-failure? out))
          (is (= 8 @calls) "at most 2*N-1 per distinct-key chunk"))))))

(defn- codes [config] (mapv (juxt :code :severity) (:findings (spi/validate-source config))))

(deftest isolate-statuses-is-rewritten-and-reported-not-deleted
  ;; doc 107 §107.5 + dialect-v1 §6 «a rewrite is not a deletion»: a published
  ;; graph keeps working and its author is told what to write instead. One
  ;; contract version of this; the next rejects it.
  (let [config (merge source resolver)
        [finding :as fs] (:findings (spi/validate-source config))]
    (is (= 1 (count fs)))
    (is (= :connector.source/superseded-key (:code finding)))
    (is (= :info (:severity finding)) "an info, not an error -- it still compiles")
    (is (= [:source :batch :isolate-statuses] (:path finding)))
    (is (str/includes? (:message finding) ":input-rejected")
        "the message says what it is read AS, so the author can copy it"))
  (testing "declaring both spellings is refused rather than reconciled by guessing"
    (let [config (-> (merge source resolver)
                     (assoc :status-map {400 :not-found}))]
      (is (= [[:connector.source/invalid-shape :error]] (codes config)))))
  (testing "a malformed set is still refused"
    (doseq [bad [#{} [400] #{"400"} #{399}]]
      (is (some #(= [:source :batch :isolate-statuses] (:path %))
                (:findings (spi/validate-source
                            (assoc-in (merge source resolver)
                                      [:batch :isolate-statuses] bad))))
          (pr-str bad)))))

(deftest a-status-map-is-judged-against-the-four-column-table
  (let [batch-cfg (-> (merge source resolver) (update :batch dissoc :isolate-statuses))
        by-key    (-> batch-cfg (dissoc :batch) (assoc :endpoint "/parts/{p/id}"))
        list-cfg  (-> by-key (assoc :result_type "list" :endpoint "/parts")
                      (assoc :input []))
        errors    #(filterv (comp #{:error} :severity) (:findings (spi/validate-source %)))]
    (testing "the legal cells pass"
      (doseq [[cfg m] [[by-key   {400 :not-found}]
                       [by-key   {404 :unauthorized}]   ; GitHub-style hidden objects
                       [by-key   {404 :source}]         ; opt out of the missing-row default
                       [batch-cfg {400 :input-rejected}]
                       [batch-cfg {404 :not-found}]
                       [list-cfg {404 :source}]
                       [list-cfg {418 :source}]
                       [by-key   {429 :rate_limited}]   ; identity on a protected status
                       [by-key   {503 :unavailable}]]]
        (is (empty? (errors (assoc cfg :status-map m))) (pr-str m))))

    (testing "a platform judgement about OUR call cannot be declared"
      (doseq [target [:timeout :connection :mapping :circuit_open :bulkhead_rejected
                      :cancelled]]
        (is (seq (errors (assoc by-key :status-map {400 target}))) (str target))))

    (testing "a protected status keeps the platform's classification"
      ;; Otherwise a source could declare {429 :input-rejected} and have the
      ;; connector bisect its way through a rate limit one request at a time.
      (doseq [[status target] [[401 :source] [403 :not-found] [429 :input-rejected]
                               [502 :source] [503 :not-found] [504 :input-rejected]
                               [408 :source]]]
        (is (seq (errors (assoc batch-cfg :status-map {status target})))
            (str status " -> " target))))

    (testing "a target must suit the read shape"
      (is (seq (errors (assoc by-key :status-map {400 :input-rejected})))
          ":input-rejected needs a batch slot to land in")
      (is (seq (errors (assoc list-cfg :status-map {400 :not-found})))
          "nothing on a list read addresses one row")
      (is (seq (errors (assoc list-cfg :status-map {400 :input-rejected})))))

    (testing "a list/key-less 404 is an absent COLLECTION and may only be :source"
      (doseq [target [:not-found :input-rejected :unauthorized :unavailable]]
        (is (seq (errors (assoc list-cfg :status-map {404 target}))) (str target))))

    (testing "the key domain is 400..599 -- a 2xx is an answer, not a failure"
      (doseq [status [200 204 302 399 600]]
        (is (seq (errors (assoc by-key :status-map {status :source}))) (str status))))))

(deftest isolation-budget-is-shared-across-chunks-and-resets-per-invocation
  (let [calls (atom 0)
        src (assoc-in source [:batch :chunk-size] 2)
        f (spi/compile-batch-source src nil resolver)]
    (with-redefs [open.atlas.common.connector.api.impl.run/isolation-max-extra-requests 2
                  http/get (fn [& _] (swap! calls inc) {:status 400 :body "private"})]
      (let [e (try (f (inputs ["A" "B" "C" "D"]) {}) (catch Exception e e))]
        (is (spi/failure? e))
        (is (= :source (spi/error-category e)))
        (is (str/includes? (.getMessage e) "extra-request budget exceeded"))
        (is (= 4 @calls) "two original requests and two extra requests; no third extra request"))
      (reset! calls 0)
      (is (every? spi/batch-item-failure? (f (inputs ["A" "B"]) {})))
      (is (= 3 @calls) "a new invocation starts a fresh budget"))))

(deftest zero-extra-budget-still-allows-successful-baseline-requests
  (let [calls (atom 0) f (spi/compile-batch-source source nil resolver)]
    (with-redefs [open.atlas.common.connector.api.impl.run/isolation-max-extra-requests 0
                  http/get (fn [url _] (swap! calls inc) (ok (ids url)))]
      (is (= ["A" "B"] (mapv :p/detail (f (inputs ["A" "B"]) {}))))
      (is (= 1 @calls)))
    (reset! calls 0)
    (with-redefs [open.atlas.common.connector.api.impl.run/isolation-max-extra-requests 0
                  http/get (fn [& _] (swap! calls inc) {:status 400 :body "{}"})]
      (is (thrown? clojure.lang.ExceptionInfo (f (inputs ["A" "B"]) {})))
      (is (= 1 @calls)))))

(def ^:private by-key-source
  (-> source (dissoc :batch) (assoc :endpoint "/parts/{p/id}")))

(defn- answers [status]
  (fn [_ _] {:status status :body "private-upstream-error"}))

(defn- f-batch [src]
  ((spi/compile-batch-source src nil resolver) (inputs ["A" "B"]) {}))

(defn- f-single [src]
  ((spi/compile-source src nil resolver) {:p/id "A"} {}))

;; ---------------------------------------------------------------------------
;; doc 107 §107.5 / §107.8: ONE stub, four mappings. The point of the key is
;; that the same upstream answer means different things at different sources,
;; so the only thing that changes between these cases is the declaration.
;; ---------------------------------------------------------------------------

(deftest the-same-400-means-four-different-things-under-four-declarations
  (testing "{400 :not-found} on a by-key read: nil, the engine reports :engine/not-found"
    (api/reset-resilience-state!)
    (with-redefs [http/get (answers 400)]
      (let [f (spi/compile-source (assoc by-key-source :status-map {400 :not-found})
                                  nil resolver)]
        (is (nil? (f {:p/id "A"} {}))
            "nil = cannot supply; the wire shape is the same one a by-key 404 has always had"))))

  (testing "{400 :input-rejected} on a batch: a typed per-row failure, via bisection"
    (api/reset-resilience-state!)
    (let [calls (atom 0)
          src (-> source (update :batch dissoc :isolate-statuses)
                  (assoc :status-map {400 :input-rejected}))]
      (with-redefs [http/get (fn [url opts] (swap! calls inc) ((answers 400) url opts))]
        (let [[a b] (f-batch src) ]
          (is (spi/batch-item-failure? a))
          (is (= :input-rejected (:error/category (spi/batch-item-failure-info a))))
          (is (spi/batch-item-failure? b))
          (is (= 3 @calls) "the chunk, then each half -- the existing bisection")))))

  (testing "{400 :not-found} on a batch: an EMPTY slot, not a failure"
    ;; The opposite action from `:input-rejected`, which is exactly why §107.4
    ;; gives them separate names: one says «no such row», the other says «I
    ;; could not find out», and a caller must not confuse them.
    (api/reset-resilience-state!)
    (let [src (-> source (update :batch dissoc :isolate-statuses)
                  (assoc :status-map {400 :not-found}))]
      (with-redefs [http/get (answers 400)]
        (let [out (f-batch src)]
          (is (= [nil nil] out))
          (is (not-any? spi/batch-item-failure? out))))))

  (testing "an UNMAPPED 400 fails the whole resolver, as before"
    (api/reset-resilience-state!)
    (let [calls (atom 0)
          src (update source :batch dissoc :isolate-statuses)]
      (with-redefs [http/get (fn [url opts] (swap! calls inc) ((answers 400) url opts))]
        (let [e (try (f-batch src) (catch Exception e e))]
          (is (= :source (spi/error-category e)))
          (is (= 1 @calls) "no bisection: nothing declared this status per-row"))))))

(deftest a-404-declared-unauthorized-on-a-by-key-read-is-never-retried
  ;; GitHub-style hidden objects: a 404 that really means "you may not see
  ;; this". Declaring it keeps the missing-row default from telling a caller
  ;; the row does not exist, and D6.4 still refuses to retry it.
  (api/reset-resilience-state!)
  (let [calls (atom 0)
        src (assoc by-key-source :status-map {404 :unauthorized}
                   :resilience {:max_retries 3 :retry_on #{:rate_limited :unavailable :connection}
                                :backoff_ms [1 2 1.0] :jitter 0})]
    (with-redefs [http/get (fn [url opts] (swap! calls inc) ((answers 404) url opts))]
      (let [e (try (f-single src) (catch clojure.lang.ExceptionInfo e e))]
        (is (= :unauthorized (spi/error-category e)))
        (is (= 1 @calls))))))

(deftest a-by-key-404-still-answers-a-missing-row-unless-declared-otherwise
  (api/reset-resilience-state!)
  (with-redefs [http/get (answers 404)]
    (is (nil? ((spi/compile-source by-key-source nil resolver) {:p/id "A"} {}))
        "§107.4: the by-key 404 default is unchanged")
    (is (= :source (spi/error-category
                    (try ((spi/compile-source (assoc by-key-source :status-map {404 :source})
                                              nil resolver)
                          {:p/id "A"} {})
                         (catch clojure.lang.ExceptionInfo e e))))
        "an explicit declaration opts out of it")))

(deftest both-spellings-produce-identical-behaviour
  ;; §107.5 promises `:isolate-statuses #{400 404}` IS
  ;; `{400 :input-rejected 404 :input-rejected}`. Asserted by running the same
  ;; stub through both and comparing the calls AND the results -- a promise of
  ;; equivalence that only the docs check is a promise nobody checks.
  (let [old-src source
        new-src (-> source (update :batch dissoc :isolate-statuses)
                    (assoc :status-map {400 :input-rejected 404 :input-rejected}))
        run (fn [src]
              (api/reset-resilience-state!)
              (let [calls (atom [])]
                (with-redefs [http/get (fn [url _]
                                         (let [xs (ids url)]
                                           (swap! calls conj xs)
                                           (if (some #{"BAD"} xs)
                                             {:status 400 :body "private"}
                                             (ok (remove #{"ABSENT"} xs)))))]
                  [(mapv #(if (spi/batch-item-failure? %)
                            (spi/batch-item-failure-info %) %)
                         ((spi/compile-batch-source src nil resolver)
                          (inputs ["A" "BAD" "B" "ABSENT"]) {}))
                   @calls])))]
    (is (= (run old-src) (run new-src)))))

;; ---------------------------------------------------------------------------
;; doc 107 §107.7 / §107.9 item 10: bounded chunk concurrency under the bulkhead
;; ---------------------------------------------------------------------------

(defn- concurrent-source
  "The batch source with `n` chunks in flight and a bulkhead of `permits`.
   One id per chunk keeps the arithmetic obvious."
  [n permits]
  (-> source
      (update :batch #(-> % (dissoc :isolate-statuses) (assoc :chunk-size 1)))
      (assoc :policy {:batch {:concurrency n}}
             :resilience {:timeout_ms 2000
                          :bulkhead {:concurrency permits :max_wait_ms 5000}})))

(deftest the-bulkhead-still-bounds-what-concurrency-asks-for
  ;; §107.3: `:batch :concurrency` is the modeller's knowledge about the
  ;; upstream, but operations' permit count is what actually gates the wire.
  ;; Asking for 4 where 2 were granted must put 2 in flight, not 4.
  (api/reset-resilience-state!)
  (let [in-flight (atom 0) peak (atom 0)
        f (spi/compile-batch-source (concurrent-source 4 2) nil resolver)]
    (with-redefs [http/get (fn [url _]
                             (swap! in-flight (fn [n] (swap! peak max (inc n)) (inc n)))
                             (Thread/sleep 60)
                             (swap! in-flight dec)
                             (ok (ids url)))]
      (let [out (f (inputs ["A" "B" "C" "D" "E" "F"]) {})]
        (is (= 6 (count out)))
        (is (<= @peak 2) (str "peak in flight was " @peak))))))

(deftest concurrency-one-is-the-serial-path-byte-for-byte
  ;; The default must not change what a published graph does. Same call ORDER,
  ;; same results, same receipts -- asserted by running both and comparing.
  (let [run (fn [src]
              (api/reset-resilience-state!)
              (let [calls (atom []) statements (atom [])]
                (with-redefs [http/get (fn [url _]
                                         (let [xs (ids url)]
                                           (swap! calls conj xs)
                                           (if (some #{"BAD"} xs)
                                             {:status 400 :body "private"}
                                             (ok xs))))]
                  (binding [spi/*statement-trace* #(swap! statements conj %)]
                    [(mapv #(if (spi/batch-item-failure? %)
                              (spi/batch-item-failure-info %) %)
                           ((spi/compile-batch-source src nil resolver)
                            (inputs ["A" "BAD" "B"]) {}))
                     @calls
                     (mapv #(select-keys % [:ok? :http-status :attempt]) @statements)]))))
        plain      (update source :batch assoc :chunk-size 1)
        declared-1 (assoc plain :policy {:batch {:concurrency 1}})]
    (is (= (run plain) (run declared-1))
        "declaring 1 is exactly not declaring anything")))

(deftest mixed-good-and-bad-chunks-align-the-same-way-under-concurrency
  ;; Alignment is the property isolation exists to preserve, and a pool is the
  ;; obvious way to lose it. Three in flight, bad keys scattered.
  (let [run (fn [n]
              (api/reset-resilience-state!)
              (with-redefs [http/get (fn [url _]
                                       (let [xs (ids url)]
                                         (Thread/sleep (rand-int 20))
                                         (if (some #{"BAD"} xs)
                                           {:status 400 :body "private"}
                                           (ok xs))))]
                (mapv #(if (spi/batch-item-failure? %)
                         (:error/category (spi/batch-item-failure-info %))
                         (:p/detail %))
                      ((spi/compile-batch-source
                        (-> source (update :batch assoc :chunk-size 1)
                            (assoc :policy {:batch {:concurrency n}}))
                        nil resolver)
                       (inputs ["A" "BAD" "B" "BAD" "C"]) {}))))]
    (is (= ["A" :input-rejected "B" :input-rejected "C"] (run 1)))
    (is (= (run 1) (run 3)) "concurrency does not move a row")))

(deftest the-first-fatal-failure-stops-the-remaining-chunks
  ;; A 503 with no retry policy is fatal to the query. Everything still in the
  ;; queue would cost the upstream real work for an answer nobody reads.
  (api/reset-resilience-state!)
  (let [calls (atom 0)
        f (spi/compile-batch-source
           (-> (concurrent-source 2 8)
               (assoc-in [:resilience :bulkhead] {:concurrency 1 :max_wait_ms 5000}))
           nil resolver)]
    (with-redefs [http/get (fn [& _]
                             (swap! calls inc)
                             (Thread/sleep 20)
                             {:status 503 :body "private"})]
      (let [e (try (f (inputs ["A" "B" "C" "D" "E" "F" "G" "H"]) {}) (catch Exception e e))]
        (is (= :unavailable (spi/error-category e)))
        (is (< @calls 8) (str "abandoned the rest; sent " @calls " of 8"))))))

(deftest a-spent-deadline-mid-run-cancels-the-chunks-not-yet-sent
  (api/reset-resilience-state!)
  (let [calls (atom 0)
        f (spi/compile-batch-source (concurrent-source 2 2) nil resolver)]
    (with-redefs [http/get (fn [url _] (swap! calls inc) (Thread/sleep 40) (ok (ids url)))]
      (binding [spi/*request-trace* {:deadline-at (+ (System/currentTimeMillis) 60)}]
        (let [e (try (f (inputs ["A" "B" "C" "D" "E" "F" "G" "H"]) {}) (catch Exception e e))]
          (is (= :cancelled (spi/error-category e)))
          (is (< @calls 8) (str "sent " @calls " of 8 before the budget ran out")))))))
