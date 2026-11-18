(ns open.atlas.common.connector.spi.impl.deadline
  "The query deadline (D6.3, doc 107 §107.7) and the abort hooks that enforce
   it, shared by every connector.

   **An absolute instant, never a duration.** A duration would be re-read as
   «you may have this long» at every hop, and three hops of 5 s each is 15 s
   against a 5 s budget -- the exact arithmetic D6.3 forbids. It is minted once,
   by the engine, which is the only place that knows both the limit and when
   the clock started.

   **Pure arithmetic over an explicit value.** The deadline reaches a connector
   on `spi/*request-trace*` (the same channel E2's correlation ids ride, for
   the reason that var's docstring gives: a binding cannot follow Pathom onto
   its own threads, so the value rides the env to the leaf). But the retry loop
   in `impl.guard` needs it too, and that ns cannot require the interface ns
   that requires it. So the var stays on the interface and what travels in here
   is the number."
  (:import [java.util.concurrent CopyOnWriteArraySet]))

(defn remaining-ms
  "How long is left, or nil when there is no deadline.

   May be zero or negative: the caller is expected to treat that as
   `:cancelled` rather than as «no limit», which is why this does not clamp."
  [deadline-at]
  (when deadline-at
    (- (long deadline-at) (System/currentTimeMillis))))

(defn exceeded?
  "Is the budget already spent? False when there is no deadline."
  [deadline-at]
  (boolean (when-let [remaining (remaining-ms deadline-at)] (<= remaining 0))))

(defn attempt-timeout-ms
  "The timeout for ONE attempt: `min(timeout-ms, remaining)` (D6.3).

   D6.3's point is that these do not add up. An attempt allowed its full
   `:timeout_ms` when 200 ms of the query's budget is left would hold a
   connection long past the answer being useful to anyone -- and the answer,
   when it came, would arrive after the caller had already been told the query
   timed out."
  [timeout-ms deadline-at]
  (let [remaining (remaining-ms deadline-at)]
    (if (and remaining (< remaining (long timeout-ms)))
      (max 0 remaining)
      (long timeout-ms))))

(defonce ^:private abort-hooks
  ;; Zero-arg functions that abort an in-flight source call. A concurrent set:
  ;; hooks are registered and dropped from every resolver thread, and
  ;; `abort-all!` walks it from the timing-out caller's thread.
  (CopyOnWriteArraySet.))

(defn register-abort!
  "Register `f` as «how to abort the call now in flight», and return a zero-arg
   function that unregisters it.

   D6.3: «a timed-out attempt must not go on holding a slot». Before this, the
   engine's deadline only stopped WAITING for the answer (`future-cancel`),
   which interrupts a thread -- and Apache HttpClient's blocking socket read
   ignores an interrupt, so the connection stayed leased until the source felt
   like replying. A connector registers here so the deadline can reach into the
   transport it owns.

   Process-wide rather than per-query on purpose: `abort-all!` runs when a whole
   query's budget expires, and every call still in flight under it is equally
   doomed. A per-query registry would need the query id to travel to a place
   that, by then, has already given up on it."
  [f]
  (.add ^CopyOnWriteArraySet abort-hooks f)
  (fn [] (.remove ^CopyOnWriteArraySet abort-hooks f)))

(defn abort-all!
  "Abort every in-flight source call. Each hook's own failure is swallowed:
   aborting is best-effort cleanup and must not replace the timeout the caller
   is already being told about."
  []
  (doseq [f abort-hooks]
    (try (f) (catch Throwable _ nil)))
  nil)

(defn in-flight-count
  "How many calls currently have an abort hook registered. For tests: it is the
   assertion that a timed-out attempt actually let go."
  []
  (.size ^CopyOnWriteArraySet abort-hooks))
