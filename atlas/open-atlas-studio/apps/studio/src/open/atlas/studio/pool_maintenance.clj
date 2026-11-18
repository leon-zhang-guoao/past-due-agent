(ns open.atlas.studio.pool-maintenance
  "Application-owned scheduling and request admission for pool maintenance.
   All Builder requests hold the shared gate through response completion. GC
   takes the exclusive gate only when idle; requests during it receive 503."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [open.atlas.studio.pool :as pool]
            [taoensso.timbre :as log])
  (:import [java.nio.file Files CopyOption StandardCopyOption]
           [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]
           [java.util.concurrent.locks ReentrantReadWriteLock Lock]))

(def defaults {:enabled? true :check-ms 300000 :interval-ms 43200000
               :min-interval-ms 900000 :min-bytes 67108864
               :growth-bytes 134217728 :retain-backups 1})

(defn configuration [environment]
  (reduce (fn [config [name key scale]]
            (if-let [raw (not-empty (get environment name))]
              (let [n (try (Long/parseLong raw)
                           (catch Exception _ (throw (ex-info (str name " must be a positive integer") {}))))]
                (when-not (pos? n) (throw (ex-info (str name " must be positive") {})))
                (assoc config key (Math/multiplyExact n (long scale))))
              config))
          (assoc defaults :enabled?
                 (case (get environment "OPEN_ATLAS_STUDIO_POOL_GC_ENABLED" "true")
                   "true" true "false" false
                   (throw (ex-info "OPEN_ATLAS_STUDIO_POOL_GC_ENABLED must be true or false" {}))))
          [["OPEN_ATLAS_STUDIO_POOL_GC_CHECK_MINUTES" :check-ms 60000]
           ["OPEN_ATLAS_STUDIO_POOL_GC_INTERVAL_HOURS" :interval-ms 3600000]
           ["OPEN_ATLAS_STUDIO_POOL_GC_MIN_INTERVAL_MINUTES" :min-interval-ms 60000]
           ["OPEN_ATLAS_STUDIO_POOL_GC_MIN_MIB" :min-bytes 1048576]
           ["OPEN_ATLAS_STUDIO_POOL_GC_GROWTH_MIB" :growth-bytes 1048576]
           ["OPEN_ATLAS_STUDIO_POOL_GC_BACKUPS" :retain-backups 1]]))

(defn due? [config status bytes now]
  (let [last-ms (:last-success-ms status)
        growth (- bytes (or (:last-compacted-bytes status) 0))
        elapsed (if last-ms (max 0 (- now last-ms)) Long/MAX_VALUE)]
    (and (:enabled? config) (not (:paused? status))
         (>= bytes (:min-bytes config)) (pos? growth)
         (or (and (>= elapsed (:interval-ms config)) (pos? growth))
             (and (>= elapsed (:min-interval-ms config))
                  (>= growth (:growth-bytes config)))))))

(defn- state-file [controller] (io/file (:home controller) "pool-maintenance-state.edn"))
(defn- persist! [controller]
  (let [target (state-file controller) temp (io/file (str target ".tmp"))]
    (spit temp (str (pr-str @(:status controller)) "\n"))
    (Files/move (.toPath temp) (.toPath target)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))))

(defn controller [{:keys [home config get-connection publish-connection! now]
                   :or {config defaults now #(System/currentTimeMillis)}}]
  (let [file (io/file home "pool-maintenance-state.edn")
        saved (if (.exists file)
                (try (let [value (edn/read-string (slurp file))]
                       (when-not (map? value) (throw (ex-info "Invalid maintenance state" {}))) value)
                     (catch Exception _ {:paused? true :outcome :invalid-state})) {})]
    {:home home :config config :get-connection get-connection
     :publish-connection! publish-connection! :now now
     :gate (ReentrantReadWriteLock. true) :status (atom saved)
     :stopped? (atom false) :blocked? (atom false) :executor (atom nil)}))

(defn with-access
  "Run a complete synchronous request, or busy! before it touches the pool."
  [controller run! busy!]
  (if-not controller
    (run!)
    (let [^Lock gate (.readLock ^ReentrantReadWriteLock (:gate controller))]
      (if (and (not @(:stopped? controller)) (not @(:blocked? controller))
               (.tryLock gate 100 TimeUnit/MILLISECONDS))
        (try
          (if (or @(:stopped? controller) @(:blocked? controller)) (busy!) (run!))
          (finally (.unlock gate)))
        (busy!)))))

(defn run-once!
  "One scheduler tick. Skip busy windows; never interrupt foreground work."
  [{:keys [config gate status get-connection publish-connection! now stopped? blocked?] :as controller}]
  (let [^Lock exclusive (.writeLock ^ReentrantReadWriteLock gate)]
    (cond
      (or @stopped? @blocked? (not (:enabled? config)) (:paused? @status)) :disabled
      (not (.tryLock exclusive))
      (let [updated (swap! status #(-> % (update :busy-checks (fnil inc 0))
                                        (assoc :last-busy-ms (now))))]
        (when (zero? (mod (:busy-checks updated) 12))
          (log/warn "Studio pool maintenance has been deferred by active requests"
                    {:consecutive-checks (:busy-checks updated)}))
        :busy)
      :else
      (try
        (if @stopped?
          :disabled
          (let [conn (get-connection) bytes (:bytes (pool/storage-size conn)) now-ms (now)]
            (swap! status assoc :last-check-ms now-ms :active-bytes bytes :busy-checks 0)
            (if-not (due? config @status bytes now-ms)
              :not-due
              (do
                (log/info "Studio pool maintenance started" {:before-bytes bytes})
                (let [report (pool/compact-storage! conn {:publish-connection! publish-connection!
                                                        :retain-backups (:retain-backups config)})
                      after (get-in report [:after :bytes])]
                  (swap! status assoc :last-success-ms (now) :last-compacted-bytes after
                         :active-bytes after :outcome :applied :last-run (:run report)
                         :reclaimed-bytes (- bytes after) :duration-ms (- (now) now-ms))
                  (persist! controller)
                  (log/info "Studio pool maintenance completed; records verified"
                            {:before-bytes bytes :after-bytes after :deleted-blobs (:deleted-blobs report)})
                  :applied)))))
        (catch Throwable e
          (when (= :pool/maintenance-recovery-required (:type (ex-data e))) (reset! blocked? true))
          ;; A failed run is retained for diagnosis; repeated timer attempts
          ;; must not fill the disk with more backups. Restart alone does not
          ;; clear this persisted pause.
          (swap! status assoc :paused? true :outcome :failed :failed-at-ms (now)
                 :recovery-required? @blocked? :message (.getMessage e))
          (try (persist! controller) (catch Exception _ nil))
          (log/error "Studio pool maintenance paused; inspect maintenance state and backup" (.getMessage e))
          :failed)
        (finally (.unlock exclusive))))))

(defn start! [controller]
  (when (and (get-in controller [:config :enabled?]) (not @(:stopped? controller)))
    (locking (:executor controller)
      (when-not @(:executor controller)
        (let [executor (Executors/newSingleThreadScheduledExecutor
                         (reify ThreadFactory
                           (newThread [_ runnable]
                             (doto (Thread. runnable "studio-pool-maintenance") (.setDaemon true)))))
              check-ms (get-in controller [:config :check-ms])]
          (reset! (:executor controller) executor)
          (.scheduleWithFixedDelay ^ScheduledExecutorService executor
                                   ^Runnable (fn [] (run-once! controller))
                                   check-ms check-ms TimeUnit/MILLISECONDS)
          (log/info "Studio automatic pool maintenance configured"
                    (assoc (:config controller) :paused? (:paused? @(:status controller))))))))
  controller)

(defn stop! [controller]
  (when controller
    (reset! (:stopped? controller) true)
    (when-let [^ScheduledExecutorService executor @(:executor controller)] (.shutdown executor))
    ;; Do not interrupt a copy/swap. Drain it and admitted requests before the
    ;; host closes the newly published connection.
    (let [^Lock exclusive (.writeLock ^ReentrantReadWriteLock (:gate controller))]
      (.lock exclusive)
      (try nil (finally (.unlock exclusive))))))
