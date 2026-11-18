(ns open.atlas.common.connector.spi.impl.guard
  "Bulkhead, circuit breaker and the retry loop -- the RUNTIME half of the
   resilience model (D6.4 / D6.5, doc 107 §107.7; owner 2026-09-18).

   **In the SPI and not in a connector**, because D1.8's «second semantics» is
   what two copies of a breaker would be: the same rule would answer one way
   over HTTP and another over JDBC, and the day they drifted nobody would find
   out from a test. What is connector-specific stays in the connector -- how to
   fingerprint a connection, how to classify an upstream answer. What is
   general is here.

   **Why the state is process-wide and keyed by source identity.** D6.5 keys
   protection on the SOURCE, not on the graph: two graphs pointing at one host
   are one load on that host, and a breaker that forgot this would let the
   second keep hammering a source the first already found down. A compiled env
   is per graph and is rebuilt on every version switch; a connection pool to a
   host is neither -- so this state lives with the pool, not the env.

   **Leak discipline.** A fingerprint is an opaque string the connector builds
   from CONFIGURED connection facts and hashes; no credential, response body or
   row value ever enters this ns, and nothing here logs."
  (:require [open.atlas.common.connector.spi.impl.deadline :as deadline]
            [open.atlas.common.connector.spi.impl.failure :as failure]
            [open.atlas.common.connector.spi.impl.policy :as policy])
  (:import [java.util.concurrent Semaphore TimeUnit]))

;; ---------------------------------------------------------------------------
;; Bulkhead (D6.5: a total gate per source identity)
;; ---------------------------------------------------------------------------

(defonce ^:private bulkheads
  ;; fingerprint -> {:permits n :semaphore Semaphore}. The permit count is part
  ;; of the value so a deployment that lowers `:concurrency` gets a new
  ;; semaphore rather than a stale one sized for the old number.
  (atom {}))

(defn- semaphore-for ^Semaphore [fp permits]
  (let [existing (get @bulkheads fp)]
    (if (= permits (:permits existing))
      (:semaphore existing)
      (:semaphore (get (swap! bulkheads
                              (fn [m]
                                (if (= permits (:permits (get m fp)))
                                  m
                                  (assoc m fp {:permits permits
                                               :semaphore (Semaphore. (int permits) true)}))))
                       fp)))))

(defn with-permit
  "Run `f` holding one of this source's permits.

   No permit within `:max_wait_ms` is `:bulkhead_rejected` -- OUR refusal, and
   never retried: the queue is already full, so waiting again in a moment is
   the same request making the queue worse. Refusing beats queueing forever
   (the whole point of a bulkhead is that one slow source cannot consume every
   caller thread)."
  [fp policy ctx f]
  (let [permits (or (get-in policy [:bulkhead :concurrency]) 8)
        wait    (or (get-in policy [:bulkhead :max_wait_ms]) 100)
        ^Semaphore sem (semaphore-for fp permits)]
    (if-not (.tryAcquire sem (long wait) TimeUnit/MILLISECONDS)
      (failure/fail! :bulkhead_rejected ctx "no source permit within the configured wait")
      (try (f) (finally (.release sem))))))

;; ---------------------------------------------------------------------------
;; Circuit breaker (D6.5, per source identity; NOT per graph)
;; ---------------------------------------------------------------------------

(defonce ^:private breakers
  ;; fingerprint -> {:calls n :failures n :opened-at ms|nil :trial? bool}
  (atom {}))

(def ^:private breaker-failure-categories
  "Which failures say «this SOURCE is unhealthy».

   Not every failure does, and counting the wrong ones is how a breaker starts
   cutting off a healthy source: `:not-found` and `:input-rejected` are answers
   ABOUT A ROW, `:mapping` is our model disagreeing with a fine response, and
   `:unauthorized` is a credential problem that opening a breaker neither
   detects sooner nor fixes. `:circuit_open` and `:bulkhead_rejected` are our
   own refusals -- counting them would make the breaker feed itself."
  #{:timeout :connection :source :unavailable :rate_limited})

(defn reset-state!
  "Drop every bulkhead and breaker (tests, REPL). Process-wide state needs a
   way back to a known start, or one test's opened breaker fails the next."
  []
  (reset! bulkheads {})
  (reset! breakers {}))

(defn- ratio [policy]
  (or (get-in policy [:circuit_breaker :failure_ratio]) [20 50]))

(defn- reset-ms [policy]
  (or (get-in policy [:circuit_breaker :reset_ms]) 10000))

(defn- admit!
  "Decide whether this call may go out, and mark a half-open trial when it is
   the one probe the breaker allows. Answers :open when it must not."
  [fp policy]
  (let [now (System/currentTimeMillis)]
    (-> (swap! breakers update fp
               (fn [{:keys [opened-at] :as st}]
                 (cond
                   (nil? opened-at) (assoc st :admit :closed)
                   (< now (+ opened-at (reset-ms policy))) (assoc st :admit :open)
                   ;; Past the reset window: let exactly one request through and
                   ;; judge the source by its answer (half-open).
                   :else (assoc st :admit :trial :trial? true))))
        (get fp) :admit)))

(defn- record!
  "Fold one outcome into the window. `category` nil means success."
  [fp policy category]
  (let [[min-calls percent] (ratio policy)
        counts? (or (nil? category) (contains? breaker-failure-categories category))]
    (when counts?
      (swap! breakers update fp
             (fn [{:keys [calls failures trial?] :or {calls 0 failures 0} :as st}]
               (cond
                 ;; The half-open probe decides on its own: one success closes
                 ;; the breaker, one failure re-opens it for another window.
                 trial? (if category
                          {:opened-at (System/currentTimeMillis) :calls 0 :failures 0}
                          {:calls 0 :failures 0})
                 :else
                 (let [calls (inc calls)
                       failures (cond-> failures category inc)]
                   (if (< calls min-calls)
                     (assoc st :calls calls :failures failures :opened-at nil)
                     ;; A window is judged once it is long enough to mean
                     ;; something, then starts over -- a ratio over «every call
                     ;; since the process started» would never recover.
                     (if (>= (* 100 failures) (* percent calls))
                       {:opened-at (System/currentTimeMillis) :calls 0 :failures 0}
                       {:calls 0 :failures 0 :opened-at nil})))))))))

(defn with-breaker
  "Run `f` under this source's breaker.

   An open breaker fails FAST with `:circuit_open` and sends no HTTP at all --
   that is the point: a source that is down should stop costing us sockets and
   deadline. `:circuit_open` is never retried (it is our own refusal)."
  [fp policy ctx f]
  (case (admit! fp policy)
    :open (failure/fail! :circuit_open ctx "source breaker is open")
    (let [result (try
                   {:value (f)}
                   (catch clojure.lang.ExceptionInfo e
                     (record! fp policy (or (failure/category e) :source))
                     (throw e))
                   (catch Exception e
                     (record! fp policy :source)
                     (throw e)))]
      (record! fp policy nil)
      (:value result))))

;; ---------------------------------------------------------------------------
;; Retry (D6.4)
;; ---------------------------------------------------------------------------

(def ^:dynamic *sleep-ms*
  "How the retry loop waits between attempts.

   Dynamic so a conformance test can assert «it would have waited 2000 ms»
   without actually costing two seconds -- doc 107 §107.8 pins the DURATION,
   and a test that paid it would be a test nobody runs."
  (fn [ms] (when (pos? (long ms)) (Thread/sleep (long ms)))))

(defn sleep-ms [ms] (*sleep-ms* ms))

(defn call!
  "One guarded call: breaker, then bulkhead permit, then `attempt` -- retried
   per the effective policy.

   `attempt` takes the 1-based attempt number (it rides into the statement
   receipt, §107.8). Reads are idempotent by default (D6.4); a mutation gets
   `:idempotent? false` and is never retried.

   The retry consumes NO extra budget of its own: it repeats the same attempt
   under the same policy, so a caller's deadline bounds the whole sequence
   (PR C threads that deadline through and turns an exhausted one into
   `:cancelled`)."
  [{:keys [fingerprint policy ctx idempotent? deadline-at] :or {idempotent? true}} attempt]
  (loop [n 1]
    ;; **Before every attempt** (D6.3, doc 107 §107.7). Not only before the
    ;; first: a batch's later chunks, a bisection's sub-requests and every retry
    ;; come through here, and each is a fresh decision about whether there is
    ;; still any point. A request whose answer arrives after the caller has been
    ;; told the query timed out costs the source real work for nothing, and
    ;; holds a connection while it does it.
    (when (deadline/exceeded? deadline-at)
      (failure/fail! :cancelled ctx "query deadline spent before this attempt"))
    (let [outcome (try
                    {:value (with-breaker fingerprint policy ctx
                              #(with-permit fingerprint policy ctx (fn [] (attempt n))))}
                    (catch clojure.lang.ExceptionInfo e
                      (if (failure/failure? e) {:failure e} (throw e))))]
      (if-let [e (:failure outcome)]
        (let [category (failure/category e)]
          (if (and idempotent? (policy/retry? policy category n))
            (let [wait      (policy/backoff-ms policy n (failure/retry-after-ms e))
                  remaining (deadline/remaining-ms deadline-at)]
              ;; A backoff that outlasts the budget is a sleep whose only
              ;; product is a later `:cancelled`. Give up now, while the caller
              ;; can still be told WHY: after the deadline the answer would be
              ;; "the query timed out" and the throttling that actually caused
              ;; it would be invisible.
              (if (and remaining (>= wait remaining))
                (throw e)
                (do (sleep-ms wait) (recur (inc n)))))
            (throw e)))
        (:value outcome)))))
