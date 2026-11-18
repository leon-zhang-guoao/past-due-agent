(ns open.atlas.runtime.obs.impl.ratelimit
  "Per-token (JWT sub) token-bucket rate limiting. In-memory, resets on
   restart — same best-effort posture as usage/metrics.

   Disabled unless configured with a positive :limit-per-minute
   (PATHOM_RATE_LIMIT env var, wired in server -main).")

(def ^:private max-buckets 10000)

;; {:limit-per-minute N|nil :clock (fn [] millis)}
(defonce ^:private config* (atom {:limit-per-minute nil
                                  :clock #(System/currentTimeMillis)}))

;; user-id -> {:tokens double :ts millis}
(defonce ^:private buckets* (atom {}))

(defn configure!
  "Set limit (nil/0/negative = disabled) and optionally :clock (test injection).
   Clears all buckets."
  [{:keys [limit-per-minute clock]}]
  (reset! buckets* {})
  (reset! config* {:limit-per-minute (when (and limit-per-minute
                                                (pos? limit-per-minute))
                                       limit-per-minute)
                   :clock (or clock #(System/currentTimeMillis))}))

(defn limit
  "Configured requests-per-minute, or nil when disabled."
  []
  (:limit-per-minute @config*))

(defn- take-token
  "Refill bucket by elapsed time, then try to take one token.
   Returns updated bucket with :allowed? decision attached."
  [{:keys [tokens ts] :as _bucket} now capacity rate-per-ms]
  (let [tokens (min capacity (+ (or tokens capacity)
                                (* rate-per-ms (- now (or ts now)))))]
    (if (>= tokens 1.0)
      {:tokens (- tokens 1.0) :ts now :allowed? true}
      {:tokens tokens :ts now :allowed? false})))

(defn allow?
  "Check/consume one request for user-id.
   Returns {:allowed? true} or {:allowed? false :retry-after-secs N}."
  [user-id]
  (let [{:keys [limit-per-minute clock]} @config*]
    (if-not limit-per-minute
      {:allowed? true}
      (let [now (clock)
            capacity (double limit-per-minute)
            rate-per-ms (/ capacity 60000.0)
            bucket (-> (swap! buckets*
                              (fn [bs]
                                (let [bs (if (> (count bs) max-buckets) {} bs)]
                                  (update bs user-id take-token now capacity rate-per-ms))))
                       (get user-id))]
        (if (:allowed? bucket)
          {:allowed? true}
          {:allowed? false
           :retry-after-secs (max 1 (long (Math/ceil
                                           (/ (- 1.0 (:tokens bucket))
                                              (* rate-per-ms 1000.0)))))})))))
