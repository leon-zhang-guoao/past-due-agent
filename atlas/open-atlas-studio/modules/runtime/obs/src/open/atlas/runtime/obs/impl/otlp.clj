(ns open.atlas.runtime.obs.impl.otlp
  "OTLP/HTTP trace export — dependency-free (plain OTLP JSON to the collector's
   /v1/traces; works with any OpenTelemetry collector, Jaeger, Tempo, etc.).

   Configured by the app through `configure!` (see below), which typically
   feeds it from config.edn:
     :otlp {:endpoint \"http://otel-collector:4318\"   ; base URL, no path
            :headers  {\"Authorization\" \"${OTLP_TOKEN}\"}
            :service-name \"open-atlas\"}

   Two span kinds, both from data ALREADY captured on the hot path (no new
   instrumentation cost, §5 values-free: names/counts/timings only):
     atlas.query      — one span per completed EQL query/mutation
     atlas.agent.task — one span per completed data-agent task

   Batched + async via obs.ship (at-most-once, fail-open, drop on overflow)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clj-http.client :as http]
            [open.atlas.runtime.obs.impl.ship :as ship])
  (:import [java.security SecureRandom]))

(set! *warn-on-reflection* true)

;; The app owns config: it hands this module a PROVIDER FN returning
;; {:endpoint :service-name :headers}. A fn, not a map, because the previous
;; in-app implementation read (config/config) on every call — a reloaded config
;; took effect without a restart, and a snapshot taken at configure! time would
;; silently drop that. Unconfigured = tracing disabled, which is why the default
;; provider yields nil rather than throwing.
(defonce ^:private provider* (atom (constantly nil)))

(defn configure!
  "Install the config provider fn (nil resets to 'tracing disabled')."
  [f]
  (reset! provider* (or f (constantly nil)))
  nil)

(defn- cfg [] (or (@provider*) {}))

(defn endpoint
  "The collector base URL, or nil when tracing is disabled."
  []
  (:endpoint (cfg)))

(defn- service-name []
  (or (:service-name (cfg)) "open-atlas"))

(defn- headers []
  (or (:headers (cfg)) {}))

(defn- rand-hex [n]
  (let [bs (byte-array n)]
    (.nextBytes (SecureRandom.) bs)
    (apply str (map #(format "%02x" %) bs))))

(defn- attr [k v]
  {:key k :value (if (number? v) {:intValue (str (long v))} {:stringValue (str v)})})

(defn span
  "Build one OTLP span map. PURE (ids passed in). `at-ms` is the END instant;
   the start is derived from :latency-ms."
  [{:keys [trace-id span-id name at-ms latency-ms attrs status-ok?]}]
  (let [end-ns   (* (long at-ms) 1000000)
        start-ns (- end-ns (* (long (or latency-ms 0)) 1000000))]
    {:traceId trace-id
     :spanId  span-id
     :name    name
     :kind    2                       ; SPAN_KIND_SERVER
     :startTimeUnixNano (str start-ns)
     :endTimeUnixNano   (str end-ns)
     :status  {:code (if status-ok? 1 2)}   ; OK / ERROR
     :attributes (mapv (fn [[k v]] (attr k v)) attrs)}))

(defn query-span
  "Span for one completed query record (metrics/record-query! shape). PURE
   except id generation."
  [{:keys [request-id source op status latency-ms eql-shape]}]
  (span {:trace-id (rand-hex 16) :span-id (rand-hex 8)
         :name "atlas.query"
         :at-ms (System/currentTimeMillis)
         :latency-ms latency-ms
         :status-ok? (not= :error status)
         :attrs (cond-> [["atlas.source" (name (or source :unknown))]
                         ["atlas.op" (name (or op :query))]
                         ["atlas.status" (name (or status :unknown))]]
                  request-id (conj ["atlas.request_id" request-id])
                  (seq (:entity-namespaces eql-shape))
                  (conj ["atlas.entities"
                         (str/join "," (:entity-namespaces eql-shape))]))}))

(defn agent-span
  "Span for one completed agent task ({:status :usage :timing})."
  [{:keys [status usage timing]}]
  (span {:trace-id (rand-hex 16) :span-id (rand-hex 8)
         :name "atlas.agent.task"
         :at-ms (System/currentTimeMillis)
         :latency-ms (or (:total-ms timing) 0)
         :status-ok? (= :ok status)
         :attrs [["atlas.status" (name (or status :unknown))]
                 ["atlas.rounds" (or (:rounds timing) 0)]
                 ["atlas.tokens.input" (or (:input usage) 0)]
                 ["atlas.tokens.output" (or (:output usage) 0)]
                 ["atlas.tokens.cache_read" (or (:cache-read usage) 0)]]}))

(defn envelope
  "Wrap spans in the OTLP resourceSpans envelope. PURE."
  [spans service]
  {:resourceSpans
   [{:resource {:attributes [(attr "service.name" service)]}
     :scopeSpans [{:scope {:name "open.atlas.runtime.obs.impl.otlp"} :spans (vec spans)}]}]})

(def shipper (ship/make-shipper "otlp"))

(defn send-batch!
  "POST one batch of spans. Var-passed so tests redef it."
  [spans]
  (when-let [ep (endpoint)]
    (http/post (str ep "/v1/traces")
               {:body (json/generate-string (envelope spans (service-name)))
                :headers (merge {"Content-Type" "application/json"} (headers))
                :socket-timeout 5000 :connection-timeout 5000
                :throw-exceptions true})))

(defn offer-query!
  "Queue a span for a completed query record. No-op unless :otlp is configured.
   Never throws (called from metrics on the request path)."
  [query-record]
  (try
    (when (endpoint)
      (ship/enqueue! shipper #'send-batch! (query-span query-record)))
    (catch Exception _ nil))
  nil)

(defn offer-agent!
  "Queue a span for a completed agent task. No-op unless configured."
  [result]
  (try
    (when (endpoint)
      (ship/enqueue! shipper #'send-batch! (agent-span result)))
    (catch Exception _ nil))
  nil)
