(ns open.atlas.studio.pool-maintenance-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [datahike.api :as d]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.pool.impl.maintenance :as storage]
            [open.atlas.studio.pool-maintenance :as maintenance]
            [open.atlas.studio.builder-server :as builder]
            [taoensso.timbre :as log]))

(defn fixture [history?]
  (let [home (.toFile (java.nio.file.Files/createTempDirectory
                       "studio-auto-gc-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg {:store {:backend :file :path (str (io/file home "pool"))}
             :keep-history? history? :schema-flexibility :write}
        _ (d/create-database cfg)
        conn (d/connect cfg)
        current (atom conn)]
    (d/transact conn (mapv (fn [a] {:db/ident a :db/valueType :db.type/string :db/cardinality :db.cardinality/one})
                          [:sample/value :semantic.draft/document :semantic.history/document]))
    (let [tx (d/transact conn [{:db/id -1 :sample/value "initial"
                               :semantic.draft/document "production-specific current draft"}])
          eid (get (:tempids tx) -1)]
      (doseq [n (range 10)]
        (d/transact conn [{:db/id eid :sample/value (str n (apply str (repeat 20000 "x")))}]))
      (d/transact conn [{:semantic.history/document "an unpublished revision"}]))
    {:home home :current current :cfg cfg}))

(defn dispose! [{:keys [home current]}]
  (d/release @current true)
  (doseq [f (reverse (file-seq home))] (io/delete-file f true)))

(defn control [{:keys [home current]} & [options]]
  (maintenance/controller
   (merge {:home (str home) :config (assoc maintenance/defaults :min-bytes 1)
           :get-connection #(deref current) :publish-connection! #(reset! current %)} options)))

(defn automatic-runs [home]
  (filter #(.startsWith (.getName %) "pool-maintenance-auto-") (.listFiles home)))

(use-fixtures :once (fn [f] (log/with-min-level :fatal (f))))

(deftest real-store-collection-preserves-data-and-can-keep-writing
  (doseq [history? [false true]]
    (let [{:keys [home current] :as f} (fixture history?) controller (control f)]
      (try
        (let [expected (storage/fingerprint @@current)
              before (storage/tree-digest (io/file home "pool"))
              old @current]
          (is (= :applied (maintenance/run-once! controller)))
          (is (not (identical? old @current)))
          (is (= expected (storage/fingerprint @@current)))
          (let [run (first (automatic-runs home))
                report (edn/read-string (slurp (io/file run "report.edn")))]
            (is (= before (:backup-sha256 report) (storage/tree-digest (io/file run "original"))))
            (is (pos? (:deleted-blobs report)))
            (is (< (get-in report [:after :bytes]) (get-in report [:before :bytes]))))
          (d/transact @current [{:sample/value "write after reopen"}])
          (is (some #{["write after reopen"]} (d/q '[:find ?v :where [_ :sample/value ?v]] @@current)))
          (is (not (.exists (io/file home "pool.maintenance.lock")))))
        (finally (maintenance/stop! controller) (dispose! f))))))

(deftest foreground-work-is-never-collected-underneath
  (let [f (fixture false) controller (control f)
        entered (promise) release (promise)
        reader (future (maintenance/with-access controller #(do (deliver entered true) @release :read-done) (constantly :busy)))]
    (try
      (is (= true (deref entered 5000 :timeout)))
      (is (= :busy (maintenance/run-once! controller)))
      (is (empty? (automatic-runs (:home f))))
      (deliver release true)
      (is (= :read-done (deref reader 5000 :timeout)))
      (is (= :applied (maintenance/run-once! controller)))
      (finally (deliver release true) @reader (maintenance/stop! controller) (dispose! f)))))

(deftest arrivals-during-collection-are-refused-before-operation
  (let [f (fixture false) controller (control f) entered (promise) release (promise)
        called (atom false) collect storage/collect-copy!]
    (try
      (with-redefs [storage/collect-copy! (fn [candidate] (deliver entered true) @release (collect candidate))]
        (let [worker (future (maintenance/run-once! controller))]
          (try
            (is (= true (deref entered 5000 :timeout)))
            (is (= :retry (maintenance/with-access controller #(reset! called true) (constantly :retry))))
            (is (false? @called))
            (finally (deliver release true)))
          (is (= :applied (deref worker 10000 :timeout)))))
      (is (= :resumed (maintenance/with-access controller (constantly :resumed) (constantly :retry))))
      (finally (deliver release true) (maintenance/stop! controller) (dispose! f)))))

(deftest failures-restore-and-pause-further-automatic-backups
  (doseq [failure [:collect :swap :verify]]
    (let [{:keys [home current] :as f} (fixture false) controller (control f)
          before (storage/tree-digest (io/file home "pool"))
          expected (storage/fingerprint @@current)
          move storage/atomic-move! open storage/open-verified! first-open? (atom true)]
      (try
        (with-redefs [storage/collect-copy! (if (= failure :collect)
                                             (fn [_] (throw (ex-info "injected collection error" {}))) storage/collect-copy!)
                      storage/atomic-move! (fn [from to]
                                             (if (and (= failure :swap) (= "candidate" (.getName (io/file from))))
                                               (throw (ex-info "injected swap error" {})) (move from to)))
                      storage/open-verified! (fn [pool expected]
                                               (if (and (= failure :verify) (compare-and-set! first-open? true false))
                                                 (throw (ex-info "injected verification error" {})) (open pool expected)))]
          (is (= :failed (maintenance/run-once! controller))))
        (is (= before (storage/tree-digest (io/file home "pool"))))
        (is (= expected (storage/fingerprint @@current)))
        (is (= :disabled (maintenance/run-once! controller)))
        (is (= 1 (count (automatic-runs home))))
        (is (:paused? @(:status (control f))))
        (is (= :available (maintenance/with-access controller (constantly :available) (constantly :blocked))))
        (finally (maintenance/stop! controller) (dispose! f))))))

(deftest unsuccessful-recovery-fails-closed
  (let [{:keys [home] :as f} (fixture false) controller (control f)]
    (try
      (with-redefs [storage/open-verified! (fn [& _] (throw (ex-info "recovery unavailable" {})))]
        (is (= :failed (maintenance/run-once! controller))))
      (is (true? @(:blocked? controller)))
      (is (.exists (io/file home "pool.maintenance.lock")))
      (is (.isDirectory (io/file (first (automatic-runs home)) "original")))
      (is (= :blocked (maintenance/with-access controller (constantly :unsafe) (constantly :blocked))))
      (finally (maintenance/stop! controller) (dispose! f)))))

(deftest retains-newest-automatic-backup-and-preserves-manual-folders
  (let [{:keys [home current] :as f} (fixture false) clock (atom 100000000)
        controller (control f {:now #(deref clock)})
        manual (io/file home "pool-maintenance-manual-existing")]
    (try
      (.mkdir manual) (spit (io/file manual "keep") "manual backup")
      (is (= :applied (maintenance/run-once! controller)))
      (let [first-run (first (automatic-runs home))]
        (d/transact @current [{:sample/value "another update"}])
        (swap! clock + (* 13 60 60 1000))
        (is (= :applied (maintenance/run-once! controller)))
        (is (not (.exists first-run)))
        (is (= 1 (count (automatic-runs home))))
        (is (= "manual backup" (slurp (io/file manual "keep"))))
        (is (= :not-due (maintenance/run-once! (control f {:now #(deref clock)})))))
      (finally (maintenance/stop! controller) (dispose! f)))))

(deftest internal-scheduler-runs-and-stops
  (let [f (fixture false)
        controller (control f {:config (assoc maintenance/defaults :min-bytes 1 :check-ms 20)})
        completed (promise)]
    (try
      (add-watch (:status controller) :test
                 (fn [_ _ _ after] (when (= :applied (:outcome after)) (deliver completed true))))
      (maintenance/start! controller)
      (is (= true (deref completed 10000 :timeout)))
      (maintenance/stop! controller)
      (is (= :disabled (maintenance/run-once! controller)))
      (is (.isShutdown ^java.util.concurrent.ScheduledExecutorService @(:executor controller)))
      (finally (maintenance/stop! controller) (dispose! f)))))

(deftest scheduling-and-invalid-configuration
  (let [c maintenance/defaults m (* 1024 1024) base {:last-success-ms 1000 :last-compacted-bytes (* 64 m)}]
    (is (not (maintenance/due? c {} (* 38 m) 1000)))
    (is (maintenance/due? c {} (* 100 m) 1000))
    (is (not (maintenance/due? c base (* 65 m) 2000)))
    (is (maintenance/due? c base (* 65 m) (+ 1000 (:interval-ms c))))
    (is (maintenance/due? c base (* 256 m) (+ 1000 (:min-interval-ms c))))
    (is (not (maintenance/due? c base (* 64 m) (+ 1000 (:interval-ms c)))))
    (is (not (maintenance/due? c (assoc base :paused? true) (* 500 m) Long/MAX_VALUE)))
    (is (= (* 2 60 60 1000) (:interval-ms (maintenance/configuration {"OPEN_ATLAS_STUDIO_POOL_GC_INTERVAL_HOURS" "2"}))))
    (is (false? (:enabled? (maintenance/configuration {"OPEN_ATLAS_STUDIO_POOL_GC_ENABLED" "false"}))))
    (is (thrown? clojure.lang.ExceptionInfo (maintenance/configuration {"OPEN_ATLAS_STUDIO_POOL_GC_BACKUPS" "0"})))
    (is (thrown? clojure.lang.ExceptionInfo (maintenance/configuration {"OPEN_ATLAS_STUDIO_POOL_GC_INTERVAL_HOURS" "invalid"})))))

(deftest http-and-mcp-admission-with-static-health-available
  (let [f (fixture false) controller (control f)
        state @(ns-resolve 'open.atlas.studio.builder-server 'studio-state)
        saved @state handler (ns-resolve 'open.atlas.studio.builder-server 'handler)
        server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)
        executor (java.util.concurrent.Executors/newCachedThreadPool)
        client (java.net.http.HttpClient/newHttpClient)
        exclusive (.writeLock ^java.util.concurrent.locks.ReentrantReadWriteLock (:gate controller))]
    (try
      (reset! state {:conn @(:current f) :pool-maintenance controller})
      (.createContext server "/" (reify com.sun.net.httpserver.HttpHandler (handle [_ exchange] (handler exchange))))
      (.setExecutor server executor)
      (.start server)
      (let [base (str "http://127.0.0.1:" (.getPort (.getAddress server)))
            request (fn [path]
                      (.send client (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base path)))
                                         (.timeout (java.time.Duration/ofSeconds 5)) .GET .build)
                             (java.net.http.HttpResponse$BodyHandlers/ofString)))]
        (.lock exclusive)
        (try
          (doseq [path ["/api/auth/state" "/mcp"]]
            (let [r (request path)]
              (is (= 503 (.statusCode r)))
              (is (= "5" (.orElse (.firstValue (.headers r) "Retry-After") "")))))
          (is (= 200 (.statusCode (request "/"))))
          (finally (.unlock exclusive)))
        (is (= 200 (.statusCode (request "/api/auth/state")))))
      (finally
        (.stop server 0) (.shutdownNow executor)
        (reset! state saved) (maintenance/stop! controller) (dispose! f)))))
