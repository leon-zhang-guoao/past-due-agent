(ns open.atlas.common.engine.trace-test
  "E2：`:trace` opt 从 `engine/query` 走到连接器。

   engine 不生成 request-id，也不认识 runtime/obs——它只透传调用方（数据面的
   `instance/answer`，D3.18）给的 `{:request-id … :graph-id …}`，并在 resolver
   真正跑的那条线程上把它绑到 SPI 的 `*request-trace*` 上（pathom 的并行
   resolver 读不到调用方线程的 dynamic var）。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.engine.impl.limits :as limits]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.engine.fake-connector :as fake]))

(def ^:private seen (atom ::unset))

;; 自己的 source type，跑在**真** SPI 分派上——测试不开旁门（见 fake-connector）。
(defmethod spi/compile-source "test-trace" [_source-config _connection _resolver-config]
  (fn [_input-params _eql-params]
    (reset! seen spi/*request-trace*)
    {:cis.master.vendor/vend_name "Acme"}))

(def compiled
  (let [{:keys [model findings]} (dialect/read-dialect (slurp (io/resource "graph.edn")))]
    (assert (empty? findings))
    (engine/compile-env (fake/inline-model model "test-trace"))))

(def ^:private request [{[:cis.master.vendor/vend_no 1001] [:cis.master.vendor/vend_name]}])

(defn- seen-trace
  "跑一次查询，回答「连接器看见的 trace 是什么」。"
  [opts]
  (reset! seen ::unset)
  (engine/query compiled nil request opts)
  @seen)

(deftest trace-reaches-the-connector
  (let [t {:request-id "req-1" :graph-id "6f1c9e40-0000-4000-8000-abcdef012345"}
        seen (seen-trace {:trace t})]
    (is (= t (dissoc seen :deadline-at)))))

(deftest the-deadline-reaches-the-connector-with-or-without-trace-ids
  ;; D6.3 / doc 107 §107.7: the whole-query budget becomes an ABSOLUTE instant
  ;; and rides this same channel, because it has to arrive at exactly the place
  ;; the ids do -- every connector call, on whatever thread Pathom ran it on.
  ;; It travels even when the caller minted no correlation ids: a deadline that
  ;; only applied to traced requests would be a limit that is off in production
  ;; for anyone who did not ask for tracing.
  (doseq [opts [{:trace {:request-id "req-1"}} {}]]
    (let [before (System/currentTimeMillis)
          seen   (seen-trace opts)
          after  (System/currentTimeMillis)]
      (is (number? (:deadline-at seen)) (pr-str opts))
      (is (<= (+ before 30000) (:deadline-at seen) (+ after 30000))
          "now + the execution-ms limit, not a duration to be re-read per hop"))))

(deftest a-host-execution-budget-only-ever-tightens-the-deadline
  "`*execution-budget-ms*` exists for a caller that has LESS time than the graph
   allows — MCP's end-to-end analysis deadline (doc 108 §108.5 item 6). It rides
   the SAME channel doc 107 §107.7 built, so the connectors narrow each attempt
   against the caller's clock instead of one that outlives the call waiting on
   them. It must never widen a graph's own window: that would make a declared
   `:execution-ms` advisory."
  (testing "a shorter host budget wins"
    (let [before (System/currentTimeMillis)
          seen   (binding [engine/*execution-budget-ms* 750] (seen-trace {}))
          after  (System/currentTimeMillis)]
      (is (<= (+ before 750) (:deadline-at seen) (+ after 750)))))

  (testing "a LONGER host budget does not widen the graph's 30 s window"
    (let [before (System/currentTimeMillis)
          seen   (binding [engine/*execution-budget-ms* 600000] (seen-trace {}))
          after  (System/currentTimeMillis)]
      (is (<= (+ before 30000) (:deadline-at seen) (+ after 30000)))))

  (testing "unbound leaves the compiled limit exactly as it was"
    (let [before (System/currentTimeMillis)
          seen   (seen-trace {})
          after  (System/currentTimeMillis)]
      (is (<= (+ before 30000) (:deadline-at seen) (+ after 30000))))))

(deftest compiled-env-is-never-mutated
  (let [before (:env (:engine/bundle compiled))]
    (engine/query compiled nil request {:trace {:request-id "req-2"}})
    (is (identical? before (:env (:engine/bundle compiled)))
        "trace 是按调用 assoc 进 env 的，编译产物不变")))

(deftest an-expired-query-deadline-aborts-the-in-flight-source-call
  ;; D6.3: «a timed-out attempt must not go on holding a slot». `future-cancel`
  ;; alone only stops WAITING -- it interrupts a thread, and a blocking socket
  ;; read ignores an interrupt, so the connection stayed leased until the source
  ;; felt like replying. The engine therefore reaches into the transport the
  ;; connector owns, through the SPI's abort hook.
  (let [aborted (atom false)
        unregister (spi/register-abort! #(reset! aborted true))]
    (try
      (let [r (limits/with-deadline {:execution-ms 30}
                (fn [] (Thread/sleep 5000) :never))]
        (is (= :timeout (first r)))
        (is (true? @aborted) "the connector's abort hook ran"))
      (finally (unregister))))
  (testing "a call that finishes in time is not aborted"
    (let [aborted (atom false)
          unregister (spi/register-abort! #(reset! aborted true))]
      (try
        (is (= [:ok :done] (limits/with-deadline {:execution-ms 5000} (fn [] :done))))
        (is (false? @aborted))
        (finally (unregister))))))

;; ---------------------------------------------------------------------------
;; doc 107 §107.6: a collection bound the CONNECTOR hit is the same refusal as
;; one of the four EQL boundaries -- same code, same fields.
;; ---------------------------------------------------------------------------

(def ^:private bounded-env
  (delay
    (let [{:keys [model findings]}
          (dialect/read-dialect (slurp (io/resource "graph.edn")))]
      (assert (empty? findings))
      (engine/compile-env (fake/inline-model model "test-bounded")))))

(deftest a-bound-the-connector-hit-is-reported-as-engine-limit-exceeded
  (doseq [bound [{:limit :max-items :max 120 :actual 200}
                 {:limit :max-pages :max 2 :actual 3}]]
    (binding [fake/*bound-hit* bound]
      (let [resp (engine/query @bounded-env nil request {})]
        (is (= :rejected (:engine/status resp)) (pr-str resp))
        (is (= :engine.limit/exceeded (:code resp))
            "not :engine.execution/failed -- the request was refused, not broken")
        (is (= (:limit bound) (:limit resp)) "which bound")
        (is (= (:max bound) (:max resp)))
        (is (= (:actual bound) (:actual resp))
            "and by how much: the same three fields an EQL boundary publishes")))))
