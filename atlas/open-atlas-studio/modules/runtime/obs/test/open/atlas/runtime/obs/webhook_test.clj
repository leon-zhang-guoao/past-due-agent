(ns open.atlas.runtime.obs.webhook-test
  "Audit-webhook shipping. All network is redef'd; delivery is verified via
   ship/drain-now! (synchronous, caller thread) so nothing races the daemon."
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.runtime.obs.impl.ship :as ship]
            [open.atlas.runtime.obs.impl.webhook :as webhook]))

(defmacro with-webhook
  "Install a webhook config provider for the body. Replaces the config.edn
   fixture this used before 8.10 P2 moved the ns into the module — same lookup
   timing (per call), different owner."
  [m & body]
  `(do (webhook/configure! (constantly ~m))
       (try ~@body (finally (webhook/configure! nil)))))

(defn- await-count
  "Poll until (get-count) reaches n or 3s pass — the daemon thread may hold an
   item drain-now! can't see; waiting INSIDE the with-redefs scope keeps its
   delivery on the redef'd sender."
  [get-count n]
  (let [deadline (+ (System/currentTimeMillis) 3000)]
    (loop []
      (when (and (< (System/currentTimeMillis) deadline)
                 (< (long (get-count)) (long n)))
        (Thread/sleep 20)
        (recur)))))

(deftest webhook-disabled-without-config
  (with-webhook nil
    (is (nil? (webhook/url)))
    ;; offer! is a no-op — nothing lands on the queue
    (webhook/offer! "{\"event\":\"x\"}")
    (is (zero? (.size ^java.util.concurrent.LinkedBlockingQueue (:queue webhook/shipper))))))

(deftest webhook-ships-ndjson-batches
  (with-webhook {:url "https://siem.example/ingest"
                 :headers {"X-Token" "t1"}}
    (let [posts (atom [])]
      (with-redefs [webhook/send-batch!
                    (fn [lines] (swap! posts conj lines))]
        (webhook/offer! "{\"event\":\"a\"}")
        (webhook/offer! "{\"event\":\"b\"}")
        (ship/drain-now! webhook/shipper webhook/send-batch!)
        (await-count #(count (mapcat identity @posts)) 2))
      ;; drain may overlap with the daemon: everything shipped, order per batch
      (is (= #{"{\"event\":\"a\"}" "{\"event\":\"b\"}"}
             (set (mapcat identity @posts)))))))
