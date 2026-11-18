(ns open.atlas.runtime.obs.ship-test
  "Shipper + OTLP tests. All network is redef'd; delivery is verified via
   ship/drain-now! (synchronous, caller thread) so nothing races the daemon.

   The audit-webhook half of this file stayed with apps/server: the webhook
   reads the app's config, so it did not come along in 8.10 P1."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [open.atlas.runtime.obs.impl.ship :as ship]
            [open.atlas.runtime.obs.impl.otlp :as otlp]))

(defmacro with-otlp
  "Install an OTLP config provider for the body, restoring the prior one after.
   Replaces the config.edn fixture this file used before the module owned the
   code — same lookup timing (per call), different owner."
  [m & body]
  `(let [prior# (otlp/endpoint)]
     (otlp/configure! (constantly ~m))
     (try ~@body (finally (otlp/configure! (when prior# (constantly {:endpoint prior#}))))))) 

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

;; ── generic shipper ─────────────────────────────────────────────────────────

(deftest shipper-batches-and-drains
  (let [s    (ship/make-shipper "t")
        sent (atom [])]
    ;; enqueue without config gating — raw shipper
    (dotimes [i 3] (.offer ^java.util.concurrent.LinkedBlockingQueue (:queue s) i))
    (is (= 3 (ship/drain-now! s #(swap! sent conj %))))
    (is (= [[0 1 2]] @sent))))

(deftest shipper-drop-on-overflow-never-throws
  (let [s (ship/make-shipper "t2")]
    (dotimes [i 2000]
      (.offer ^java.util.concurrent.LinkedBlockingQueue (:queue s) i))
    (is (<= (.size ^java.util.concurrent.LinkedBlockingQueue (:queue s)) 1000))))

;; ── OTLP ────────────────────────────────────────────────────────────────────

(deftest otlp-span-shape
  (let [s (otlp/span {:trace-id "aa" :span-id "bb" :name "atlas.query"
                      :at-ms 1000 :latency-ms 250
                      :attrs [["atlas.source" "api"] ["atlas.rounds" 3]]
                      :status-ok? true})]
    (is (= "atlas.query" (:name s)))
    (is (= "750000000" (:startTimeUnixNano s)))
    (is (= "1000000000" (:endTimeUnixNano s)))
    (is (= 1 (get-in s [:status :code])))
    (testing "int attrs become intValue, strings stringValue"
      (is (= {:stringValue "api"} (:value (first (:attributes s)))))
      (is (= {:intValue "3"} (:value (second (:attributes s))))))))

(deftest otlp-envelope-shape
  (let [env (otlp/envelope [{:name "s1"}] "open-atlas")]
    (is (= "open-atlas"
           (get-in env [:resourceSpans 0 :resource :attributes 0 :value :stringValue])))
    (is (= [{:name "s1"}] (get-in env [:resourceSpans 0 :scopeSpans 0 :spans])))))

(deftest otlp-disabled-without-config
  (with-otlp nil
    (otlp/offer-query! {:source :api :status :ok :latency-ms 1})
    (is (zero? (.size ^java.util.concurrent.LinkedBlockingQueue (:queue otlp/shipper))))))

(deftest otlp-query-and-agent-spans-ship
  (with-otlp {:endpoint "http://collector:4318"}
    (let [bodies (atom [])]
      (with-redefs [otlp/send-batch! (fn [spans] (swap! bodies conj spans))]
        (otlp/offer-query! {:request-id "r1" :source :api :op :query :status :ok
                            :latency-ms 42
                            :eql-shape {:entity-namespaces ["cis.master.vendor"]}})
        (otlp/offer-agent! {:status :ok
                            :usage {:input 10 :output 5 :cache-read 0}
                            :timing {:rounds 2 :total-ms 900}})
        (ship/drain-now! otlp/shipper otlp/send-batch!)
        (await-count #(count (mapcat identity @bodies)) 2))
      (let [spans (mapcat identity @bodies)
            by-name (group-by :name spans)]
        (is (= #{"atlas.query" "atlas.agent.task"} (set (keys by-name))))
        (testing "query span carries request id + entities"
          (let [attrs (into {} (map (juxt :key :value))
                            (:attributes (first (get by-name "atlas.query"))))]
            (is (= {:stringValue "r1"} (get attrs "atlas.request_id")))
            (is (= {:stringValue "cis.master.vendor"} (get attrs "atlas.entities")))))
        (testing "agent span carries rounds + tokens"
          (let [attrs (into {} (map (juxt :key :value))
                            (:attributes (first (get by-name "atlas.agent.task"))))]
            (is (= {:intValue "2"} (get attrs "atlas.rounds")))
            (is (= {:intValue "10"} (get attrs "atlas.tokens.input")))))))))

(deftest otlp-send-batch-posts-json
  (with-otlp {:endpoint "http://collector:4318"
              :headers {"Authorization" "tok"}}
    (let [req (atom nil)]
      (with-redefs [http/post (fn [url opts] (reset! req [url opts]) {:status 200})]
        (otlp/send-batch! [{:name "s"}]))
      (let [[url opts] @req]
        (is (= "http://collector:4318/v1/traces" url))
        (is (= "tok" (get-in opts [:headers "Authorization"])))
        (is (str/includes? (:body opts) "resourceSpans"))
        (is (map? (json/parse-string (:body opts))))))))

;; ── flush-all! (graceful shutdown drain) ─────────────────────────────────────

(deftest flush-all-waits-for-queues
  (testing "nothing pending → immediate true"
    (is (true? (ship/flush-all! 100))))
  (testing "a queued-but-unserved item → false at timeout; true once drained"
    (let [shipper (ship/make-shipper "flush-test")]
      ;; Enqueue WITHOUT starting the daemon (offer directly) so the item
      ;; stays pending deterministically.
      (.offer ^java.util.concurrent.LinkedBlockingQueue (:queue shipper) {:x 1})
      (is (false? (ship/flush-all! 150)) "pending item → timeout false")
      (ship/drain-now! shipper (fn [_batch]))
      (is (true? (ship/flush-all! 100)) "drained → true"))))
