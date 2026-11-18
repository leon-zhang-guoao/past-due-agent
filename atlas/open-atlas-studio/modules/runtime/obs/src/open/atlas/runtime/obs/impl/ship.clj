(ns open.atlas.runtime.obs.impl.ship
  "Shared async shipper for outbound observability (audit webhook, OTLP spans).

   One bounded queue + one lazy daemon thread per shipper. Everything is
   fail-open by construction: enqueue on a full queue DROPS (a slow collector
   must never back-pressure the request path), send errors are logged at debug
   and the batch is discarded (at-most-once delivery). State resets on restart."
  (:require [clojure.tools.logging :as log])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(set! *warn-on-reflection* true)

(def ^:private queue-cap 1000)
(def ^:private batch-max 50)
(def ^:private idle-poll-ms 500)

;; Every shipper ever made, so flush-all! (graceful shutdown) can wait on their
;; queues without each consumer namespace exporting its shipper.
(defonce ^:private registry* (atom []))

(defn make-shipper
  "A named shipper: {:queue :running? :name}. `send-batch!` is (fn [items])
   invoked on the daemon thread with 1..batch-max items."
  [name]
  (let [shipper {:name name
                 :queue (LinkedBlockingQueue. queue-cap)
                 :running? (atom false)}]
    (swap! registry* conj shipper)
    shipper))

(defn- drain-batch [^LinkedBlockingQueue q]
  (when-let [first-item (.poll q idle-poll-ms TimeUnit/MILLISECONDS)]
    (let [batch (java.util.ArrayList.)]
      (.add batch first-item)
      (.drainTo q batch (dec batch-max))
      (vec batch))))

(defn- run-loop! [{:keys [^LinkedBlockingQueue queue name]} send-batch!]
  (loop []
    (when-let [batch (try (drain-batch queue)
                          (catch InterruptedException _ nil)
                          (catch Exception _ nil))]
      (try (send-batch! batch)
           (catch Exception e
             (log/debug name "shipper send failed (batch dropped):" (.getMessage e)))))
    (recur)))

(defn- ensure-running! [{:keys [running? name] :as shipper} send-batch!]
  (when (compare-and-set! running? false true)
    (doto (Thread. #(run-loop! shipper send-batch!) (str "obs-ship-" name))
      (.setDaemon true)
      (.start))))

(defn enqueue!
  "Offer an item; start the sender on first use. Full queue → drop. Never throws."
  [{:keys [^LinkedBlockingQueue queue] :as shipper} send-batch! item]
  (try
    (ensure-running! shipper send-batch!)
    (.offer queue item)
    (catch Exception _ false)))

(defn drain-now!
  "TEST HOOK: synchronously drain everything queued through send-batch! on the
   caller's thread (bypasses the daemon). Returns the number of items shipped."
  [{:keys [^LinkedBlockingQueue queue]} send-batch!]
  (let [batch (java.util.ArrayList.)]
    (.drainTo queue batch)
    (when (pos? (.size batch))
      (send-batch! (vec batch)))
    (.size batch)))

(defn flush-all!
  "Graceful-shutdown drain: wait (up to timeout-ms) until every registered
   shipper's queue is empty, giving the daemon sender threads a beat to post
   the tail of the audit/OTLP queues before the JVM exits. Best-effort by the
   same posture as the shippers themselves (an in-flight batch on a dead-slow
   collector can still be cut off — at-most-once holds). Returns true when
   everything drained, false on timeout."
  [timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))
        pending? (fn []
                   (some (fn [{:keys [^LinkedBlockingQueue queue]}]
                           (not (.isEmpty queue)))
                         @registry*))]
    (loop []
      (cond
        (not (pending?)) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 50) (recur))))))
