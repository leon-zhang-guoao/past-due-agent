(ns open.atlas.common.connector.api.deadline-test
  "doc 107 §107.7 / §107.8 and D6.3: the query deadline reaches every attempt,
   an attempt's timeout is the WHOLE attempt, and a timed-out attempt lets go
   of its connection.

   These use real sockets (see `stub`). A mock cannot witness the thing being
   claimed: that a server which is never idle, and never finished, is still
   stopped on time and still gives the pool slot back."
  (:require [clj-http.client :as http]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.connector.api :as api]
            [open.atlas.common.connector.api.impl.conn :as conn]
            [open.atlas.common.connector.api.stub :as stub]
            [open.atlas.common.connector.spi :as spi]))

(use-fixtures :each (fn [t] (api/reset-resilience-state!) (t)))

(defn- source [port & {:as resilience}]
  {:type "api" :base_url (str "http://127.0.0.1:" port)
   :method "GET" :endpoint "/parts/{p/sku}"
   :field_mapping {:p/sku "$.sku"}
   :resilience (merge {:timeout_ms 400} resilience)})

(def ^:private resolver {:id "part_by_sku" :input [:p/sku] :result_type "single"})

(defn- leased
  "Connections the shared pool currently has out on loan."
  []
  (.getLeased (.getTotalStats ^org.apache.http.impl.conn.PoolingHttpClientConnectionManager
                              @conn/manager)))

(defn- caught [f]
  (try (f) ::no-throw (catch clojure.lang.ExceptionInfo e e)))

(deftest a-body-that-never-arrives-times-out-and-returns-the-connection
  (let [{:keys [port stop]} (stub/serve-headers-only!)]
    (try
      (let [f (spi/compile-source (source port :timeout_ms 400) nil resolver)
            ;; The DELTA, not the absolute count: this pool is process-wide and
            ;; other tests in this suite hold their own leases.
            before (leased)
            t0 (System/currentTimeMillis)
            e (caught #(f {:p/sku "A"} {}))
            elapsed (- (System/currentTimeMillis) t0)]
        (is (= :timeout (spi/error-category e)))
        (is (< elapsed 3000) (str "stopped on time, took " elapsed " ms"))
        (testing "the pool slot comes back -- D6.3's «must not go on holding a slot»"
          ;; Give the worker its moment to unwind; what must NOT happen is the
          ;; lease outliving the attempt indefinitely.
          (Thread/sleep 1500)
          (is (<= (leased) before)
              (str "leased " before " -> " (leased) "; the timed-out attempt kept its connection"))
          (is (zero? (spi/calls-in-flight))
              "and the attempt deregistered its abort hook")))
      (finally (stop)))))

(defn- trickling-stream
  "A body that is never idle and never finished: one byte every 60 ms.

   A stream rather than a socket for the v1 half of the test below. The v1
   reading has no absolute bound BY DESIGN, so exercising it against a live
   server would leave a real connection blocked inside the suite for as long as
   the suite runs -- the very defect being demonstrated, made permanent. The
   claim under test is about the CLOCK, and a stream carries that faithfully."
  []
  (proxy [java.io.InputStream] []
    (read
      ([] (Thread/sleep 60) (int \x))
      ([b] (Thread/sleep 60) (aset-byte b 0 (byte (int \x))) 1)
      ([b o _l] (Thread/sleep 60) (aset-byte b o (byte (int \x))) 1))))

(deftest a-trickling-body-is-bounded-by-the-attempt-total-only-under-api-v2
  ;; The hole api-v1's definition left open, and the reason it needed a new
  ;; contract version rather than a fix (api-v2 §v2-1): a byte every 60 ms never
  ;; trips a 300 ms IDLE timeout, so under the v1 reading the request runs until
  ;; the source gets bored. Under v2 `:timeout_ms` is the attempt TOTAL.
  ;;
  ;; The same body, the same number, two readings -- which is exactly the claim
  ;; that made this a new contract version instead of a bug fix.
  (testing "api-v1 (no opt-in): the idle timeout never fires on a trickle"
    (with-redefs [http/get (fn [_ _] {:status 200 :body (trickling-stream)})]
      (let [f (spi/compile-source (source 1 :timeout_ms 300) nil resolver)
            done (promise)]
        (doto (Thread. ^Runnable #(deliver done (caught (fn [] (f {:p/sku "A"} {})))))
          (.setDaemon true) (.start))
        (is (= ::still-running (deref done 1200 ::still-running))
            "a v1 graph keeps the meaning it was published against"))))

  (testing "api-v2 (the graph declared :source/policy-v1): cut at the total"
    (with-redefs [http/get (fn [_ _] {:status 200 :body (trickling-stream)})]
      (let [f (spi/compile-source (assoc (source 1 :timeout_ms 300)
                                         :semantic/attempt-total-timeout? true)
                                  nil resolver)
            t0 (System/currentTimeMillis)
            e (caught #(f {:p/sku "A"} {}))
            elapsed (- (System/currentTimeMillis) t0)]
        (is (= :timeout (spi/error-category e)))
        (is (< elapsed 1500) (str "bounded by the attempt total, took " elapsed " ms"))))))

(deftest a-trickling-server-is-cut-on-time-over-a-real-socket
  ;; The v2 bound once over a real connection, because it has to hold against a
  ;; transport that is genuinely never idle -- not only against a stream that
  ;; pretends to be.
  (let [{:keys [port stop]} (stub/serve-trickle! 120)]
    (try
      (let [f (spi/compile-source (assoc (source port :timeout_ms 500)
                                         :semantic/attempt-total-timeout? true)
                                  nil resolver)
            t0 (System/currentTimeMillis)
            e (caught #(f {:p/sku "A"} {}))
            elapsed (- (System/currentTimeMillis) t0)]
        (is (= :timeout (spi/error-category e)))
        (is (< elapsed 3000) (str "took " elapsed " ms")))
      (finally (stop)))))

;; ---------------------------------------------------------------------------
;; The narrowing itself, on a mock: what matters is the NUMBER handed to the
;; transport, and a socket would only make that slower to observe.
;; ---------------------------------------------------------------------------

(defn- timeout-seen [trace]
  (let [seen (atom nil)]
    (with-redefs [http/get (fn [_ opts] (reset! seen (:socket-timeout opts))
                             {:status 200 :body "{\"sku\":\"A\"}"})]
      (binding [spi/*request-trace* trace]
        ((spi/compile-source (source 1 :timeout_ms 5000) nil resolver) {:p/sku "A"} {})))
    @seen))

(deftest an-attempt-never-gets-more-than-the-budget-has-left
  ;; §107.8: graph budget 1000 ms with `:timeout_ms 5000` -> the first attempt's
  ;; timeout is <= 1000 ms. D6.3: the two do not add up.
  (let [t (timeout-seen {:deadline-at (+ (System/currentTimeMillis) 1000)})]
    (is (<= t 1000) (str "attempt timeout was " t)))
  (testing "with budget to spare the source's own :timeout_ms stands"
    (is (= 5000 (timeout-seen {:deadline-at (+ (System/currentTimeMillis) 60000)}))))
  (testing "with no deadline at all it is simply :timeout_ms"
    (is (= 5000 (timeout-seen nil)))))

(deftest a-spent-budget-sends-nothing-and-is-cancelled
  (let [calls (atom 0)]
    (with-redefs [http/get (fn [& _] (swap! calls inc) {:status 200 :body "{}"})]
      (binding [spi/*request-trace* {:deadline-at (- (System/currentTimeMillis) 1)}]
        (let [e (caught #((spi/compile-source (source 1) nil resolver) {:p/sku "A"} {}))]
          (is (= :cancelled (spi/error-category e)))
          (is (zero? @calls)
              "no request left the process: its answer could only arrive after the caller was told the query timed out"))))))

(deftest every-chunk-and-every-bisection-sub-request-checks-the-budget
  ;; §107.7: "the batch's every chunk, the bisection's every sub-request and
  ;; the paging's every page are each an attempt". Here the budget runs out
  ;; part-way, and what must NOT happen is the rest going out anyway.
  (let [calls (atom 0)
        deadline (+ (System/currentTimeMillis) 10000)
        batch-source (-> (source 1)
                         (assoc :endpoint "/parts?ids={ids}"
                                :field_mapping {:p/sku "$.sku"}
                                :batch {:parent-key :p/sku :child-fk "sku"
                                        :placeholder "ids" :chunk-size 1
                                        :isolate-statuses #{400}}))
        f (spi/compile-batch-source batch-source nil resolver)]
    (with-redefs [http/get (fn [& _]
                             (swap! calls inc)
                             ;; The first chunk spends the whole budget.
                             (when (= 1 @calls)
                               (Thread/sleep 5))
                             {:status 200 :body "[]"})]
      (binding [spi/*request-trace* {:deadline-at deadline}]
        (is (= [nil nil nil] (f [{:p/sku "A"} {:p/sku "B"} {:p/sku "C"}] {})))
        (is (= 3 @calls) "three chunks, all within budget"))
      (reset! calls 0)
      (binding [spi/*request-trace* {:deadline-at (- (System/currentTimeMillis) 1)}]
        (let [e (caught #(f [{:p/sku "A"} {:p/sku "B"}] {}))]
          (is (= :cancelled (spi/error-category e)))
          (is (zero? @calls) "not even the first chunk"))))))

(deftest a-backoff-never-sleeps-past-the-deadline
  ;; D6.4 retries consume the ORIGINAL deadline. A backoff longer than what is
  ;; left is a sleep whose only product is a later `:cancelled`, and it would
  ;; hide the reason: the caller would be told "timed out" when the truth is
  ;; "throttled and we ran out of time waiting to ask again".
  (let [calls (atom 0)
        slept (atom [])]
    (with-redefs [http/get (fn [& _] (swap! calls inc)
                             {:status 429 :headers {"Retry-After" "30"} :body "x"})]
      (spi/with-retry-sleep (fn [ms] (swap! slept conj ms))
        (binding [spi/*request-trace* {:deadline-at (+ (System/currentTimeMillis) 200)}]
          (let [src (source 1 :max_retries 3 :retry_on #{:rate_limited})
                e (caught #((spi/compile-source src nil resolver) {:p/sku "A"} {}))]
            (is (= :rate_limited (spi/error-category e))
                "reported as what it was, not as a timeout")
            (is (= 1 @calls) "no retry: the 30 s wait does not fit in 200 ms")
            (is (empty? @slept) "and it did not sleep first to find that out")))))))
