(ns open.atlas.runtime.obs.impl.log
  "Timbre configuration and structured event logging.

   Two outputs: Timbre's default console appender (human-readable, unchanged)
   plus a JSON-lines file appender (logs/app.jsonl) for grep/jq analysis.

   Query diagnostics explicitly include EQL input values when enabled by
   the application. Never log credentials or query results."
  (:require [clojure.java.io :as io]
            [open.atlas.runtime.obs.impl.retention :as retention]
            [taoensso.timbre :as timbre]
            [cheshire.core :as json]
            [open.atlas.runtime.obs.impl.jsonl :as jsonl]
            [open.atlas.runtime.obs.impl.trace :as trace]
))

(def ^:private default-max-bytes (* 50 1024 1024))

(defn- json-safe
  "Stringify values Cheshire can't encode (Throwables, arbitrary Java objects)."
  [v]
  (if (or (nil? v) (string? v) (number? v) (boolean? v)
          (keyword? v) (symbol? v) (map? v) (coll? v))
    v
    (str v)))

(defn- json-line
  "Render one Timbre log event as a JSON string. Structured events (a single
   map varg with :event) serialize their keys top-level; plain log calls
   serialize as {:msg ...}. Falls back to pr-str payload on encoding failure."
  [{:keys [level instant vargs msg_]}]
  (let [event-map (when (and (= 1 (count vargs))
                             (map? (first vargs))
                             (:event (first vargs)))
                    (first vargs))
        base {:ts    (str (.toInstant ^java.util.Date instant))
              :level (name level)}
        payload (if event-map
                  (merge base (-> event-map
                                  (update :event name)
                                  (update-vals json-safe)))
                  (assoc base :msg (force msg_)))]
    (try
      (json/generate-string payload)
      (catch Exception _
        (json/generate-string (assoc base :msg (pr-str payload)))))))

(defn- jsonl-appender [path max-bytes]
  {:enabled? true
   :async?   false
   :fn (fn [data]
         (jsonl/append-line! path max-bytes (json-line data)))})

(defn configure!
  "Install the JSON-lines appender. Call once at startup (and from tests
   with a temp :log-file). Console appender and global min-level are left
   as Timbre's defaults; :min-level here applies to the jsonl appender only."
  ([] (configure! {}))
  ([{:keys [log-file max-bytes min-level retention-home]
     :or   {log-file "logs/app.jsonl"
            max-bytes default-max-bytes
            min-level :info}}]
   (when retention-home (retention/configure! retention-home))
   (timbre/merge-config!
    {;; Console sanity: framework DEBUG chatter arriving over the slf4j bridge
     ;; (Jetty thread-pool "Evict skipped" lines every few ms, HikariCP
     ;; housekeeping, Apache HTTP wire logs — which can dump headers) drowns the
     ;; console on an idle server. Everything else lands on the `*` :info floor.
     ;;
     ;; There used to be an `ent.dx.*` :debug entry here for apps/server's
     ;; inherited stack. That stack was retired (8.16) and the prefix no longer
     ;; names anything; dropping the entry changes no surviving namespace's
     ;; level, since `open.atlas.*` was always on the `*` floor.
     :min-level [[#{"org.eclipse.jetty.*"}                :info]
                 [#{"com.zaxxer.hikari.*"}                :info]
                 [#{"org.apache.http.*" "org.apache.hc.*"} :warn]
                 [#{"*"}                                  :info]]
     :appenders {:jsonl (assoc (jsonl-appender log-file max-bytes)
                               :min-level min-level)}})))

(defn log-event!
  "Log a structured event map. Merges the current request-id when bound.
   Events with :status :error log at error level, otherwise info.
   Fail-open: a logging failure must never break the caller."
  [event m]
  (try
    (let [payload (cond-> (assoc m :event event)
                    trace/*request-id* (assoc :request-id trace/*request-id*))]
      (if (= :error (:status m))
        (timbre/error payload)
        (timbre/info payload)))
    (catch Exception _ nil)))

(defn query-details [home events]
  (let [ids (set (keep :request-id events))
        file (when home (io/file home "logs" "app.jsonl"))]
    (if (and (seq ids) file)
      (retention/reduce-lines file (fn [result line]
                  (let [entry (try (json/parse-string line true) (catch Exception _ nil))]
                    (if (and (= "query-executed" (:event entry))
                             (contains? ids (:request-id entry)))
                      (update result (:request-id entry) (fnil conj [])
                              (select-keys entry [:eql :token-id :graph :graph-version :entry :tool
                                                  :remote-addr :user-agent :path :latency-ms :outcome :reason :principal :execution-target]))
                      result)))
                {})
      {})))
