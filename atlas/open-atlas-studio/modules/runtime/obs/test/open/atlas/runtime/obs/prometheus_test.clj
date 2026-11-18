(ns open.atlas.runtime.obs.prometheus-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [open.atlas.runtime.obs.impl.metrics :as metrics]
            [open.atlas.runtime.obs.impl.prometheus :as prom]))

(use-fixtures :each (fn [f] (metrics/reset-all!) (try (f) (finally (metrics/reset-all!)))))

(deftest render-covers-all-metric-families
  (metrics/record-query! {:source :api :op :query :status :ok :latency-ms 12})
  (metrics/record-query! {:source :api :op :query :status :error :latency-ms 5})
  (metrics/record-query! {:source :mcp :op :mutation :status :ok :latency-ms 30})
  (metrics/record-rejection! :rate-limited)
  (metrics/record-rejection! :rate-limited)
  (metrics/record-rejection! :unauthorized)
  (metrics/record-agent-run! {:status :ok
                              :usage {:input 1000 :output 200 :cache-read 700}
                              :timing {:rounds 4}})
  (let [out (prom/render)]
    (testing "request counters by source/op"
      (is (str/includes? out "atlas_requests_total{source=\"api\",op=\"query\"} 2"))
      (is (str/includes? out "atlas_requests_total{source=\"mcp\",op=\"mutation\"} 1"))
      (is (str/includes? out "atlas_request_errors_total{source=\"api\"} 1")))
    (testing "latency summary"
      (is (str/includes? out "atlas_query_latency_ms_count{source=\"api\"} 2"))
      (is (str/includes? out "atlas_query_latency_ms_sum{source=\"api\"} 17"))
      (is (str/includes? out "quantile=\"0.95\"")))
    (testing "429/401 counters"
      (is (str/includes? out "atlas_rejected_requests_total{kind=\"rate_limited\"} 2"))
      (is (str/includes? out "atlas_rejected_requests_total{kind=\"unauthorized\"} 1")))
    (testing "agent runs/steps/tokens"
      (is (str/includes? out "atlas_agent_runs_total{status=\"ok\"} 1"))
      (is (str/includes? out "atlas_agent_rounds_total 4"))
      (is (str/includes? out "atlas_agent_tokens_total{kind=\"input\"} 1000"))
      (is (str/includes? out "atlas_agent_tokens_total{kind=\"cache_read\"} 700")))
    (testing "uptime gauge present"
      (is (str/includes? out "atlas_uptime_seconds")))))

(deftest render-empty-state-is-valid
  (let [out (prom/render)]
    (is (str/includes? out "atlas_rejected_requests_total{kind=\"rate_limited\"} 0"))
    (is (str/includes? out "atlas_agent_rounds_total 0"))
    (testing "no dangling HELP without body for counters that always render"
      (is (str/includes? out "# TYPE atlas_uptime_seconds gauge")))))

(deftest handler-content-type
  (let [resp (prom/handler {})]
    (is (= 200 (:status resp)))
    (is (str/starts-with? (get-in resp [:headers "Content-Type"]) "text/plain"))
    (is (string? (:body resp)))))
