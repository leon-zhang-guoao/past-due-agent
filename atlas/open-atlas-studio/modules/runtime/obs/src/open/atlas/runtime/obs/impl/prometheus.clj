(ns open.atlas.runtime.obs.impl.prometheus
  "Prometheus text exposition (format 0.0.4) over the in-process metrics state.

   GET /metrics — unauthenticated like /health (aggregate counters only: no
   user ids, no entity names, no values — safe under the §5 hand-off contract).

   Counters reset on process restart (the in-memory state is the source);
   Prometheus rate()/increase() handle counter resets natively."
  (:require [clojure.string :as str]
            [open.atlas.runtime.obs.impl.metrics :as metrics]))

(defn- fmt-labels [m]
  (if (seq m)
    (str "{" (str/join "," (map (fn [[k v]] (str (name k) "=\"" v "\"")) m)) "}")
    ""))

(defn- line [metric labels value]
  (str metric (fmt-labels labels) " " value))

(defn- header [metric type help]
  (str "# HELP " metric " " help "\n# TYPE " metric " " type))

(defn render
  "The full exposition document. PURE over the metrics getters."
  []
  (let [counters   (metrics/counter-snapshot)
        lats       (metrics/latency-stats)
        rej        (metrics/rejections)
        agent      (metrics/agent-stats)
        secs       (/ (metrics/uptime-ms) 1000.0)]
    (str
     (str/join
      "\n"
      (concat
       [(header "atlas_uptime_seconds" "gauge" "Seconds since process start.")
        (line "atlas_uptime_seconds" {} (format "%.1f" secs))]

       [(header "atlas_requests_total" "counter"
                "Completed queries/mutations by source and op (errors included).")]
       (for [[src {:keys [queries mutations]}] counters
             [op n] [["query" queries] ["mutation" mutations]]
             :when (pos? (or n 0))]
         (line "atlas_requests_total" {:source (name src) :op op} n))

       [(header "atlas_request_errors_total" "counter"
                "Completed requests that ended in an engine error, by source.")]
       (for [[src {:keys [errors]}] counters
             :when (pos? (or errors 0))]
         (line "atlas_request_errors_total" {:source (name src)} errors))

       [(header "atlas_query_latency_ms" "summary"
                "Query latency; quantiles over the last 500 samples per source.")]
       (mapcat (fn [[src {:keys [count sum p50 p95 p99]}]]
                 (let [l {:source (name src)}]
                   (cond-> [(line "atlas_query_latency_ms_count" l count)
                            (line "atlas_query_latency_ms_sum" l sum)]
                     p50 (conj (line "atlas_query_latency_ms" (assoc l :quantile "0.5") p50))
                     p95 (conj (line "atlas_query_latency_ms" (assoc l :quantile "0.95") p95))
                     p99 (conj (line "atlas_query_latency_ms" (assoc l :quantile "0.99") p99)))))
               lats)

       [(header "atlas_rejected_requests_total" "counter"
                "Rejected requests: rate_limited (429) and unauthorized (401).")
        (line "atlas_rejected_requests_total" {:kind "rate_limited"} (:rate-limited rej))
        (line "atlas_rejected_requests_total" {:kind "unauthorized"} (:unauthorized rej))]

       [(header "atlas_agent_runs_total" "counter"
                "Completed data-agent tasks by terminal status.")]
       (for [[status n] (:statuses agent)]
         (line "atlas_agent_runs_total" {:status (name status)} n))

       [(header "atlas_agent_rounds_total" "counter"
                "LLM round-trips (agent steps) across all tasks.")
        (line "atlas_agent_rounds_total" {} (:rounds agent))

        (header "atlas_agent_tokens_total" "counter"
                "LLM tokens consumed by the data agent.")
        (line "atlas_agent_tokens_total" {:kind "input"} (get-in agent [:tokens :input]))
        (line "atlas_agent_tokens_total" {:kind "output"} (get-in agent [:tokens :output]))
        (line "atlas_agent_tokens_total" {:kind "cache_read"} (get-in agent [:tokens :cache-read]))]))
     "\n")))

(defn handler
  "Ring handler for GET /metrics."
  [_req]
  {:status 200
   :headers {"Content-Type" "text/plain; version=0.0.4; charset=utf-8"}
   :body (render)})
