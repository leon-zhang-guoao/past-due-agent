(ns open.atlas.studio.pool.ownership-test
  (:require [clojure.test :refer :all] [clojure.java.io :as io]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.pool.impl.store :as store]
            [open.atlas.studio.pool.impl.maintenance :as maintenance])
  (:import [java.nio.file StandardOpenOption]
           [java.nio.channels FileChannel OverlappingFileLockException]))

(defn lock-channel [home]
  (FileChannel/open (.toPath (io/file home "pool.writer.lock"))
                    (into-array java.nio.file.OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE])))
(defn busy? [channel]
  (try (if-let [lock (.tryLock ^FileChannel channel)] (do (.release lock) false) true)
       (catch OverlappingFileLockException _ true)))
(defn temporary-home []
  (.toFile (java.nio.file.Files/createTempDirectory "studio-owner-test-" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn remove-home! [home] (doseq [f (reverse (file-seq home))] (io/delete-file f true)))

(deftest refuses-an-already-owned-file-pool
  (let [home (temporary-home) cfg (store/config {:backend :file :path (str (io/file home "pool"))})]
    (try
      (with-open [channel (lock-channel home) lock (.lock channel)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already owned" (store/create! cfg))))
      (is (not (.exists (io/file home "pool"))))
      (finally (remove-home! home)))))

(deftest lease-survives-pool-replacement-and-releases-on-final-close
  (let [home (temporary-home) cfg (store/config {:backend :file :path (str (io/file home "pool"))})
        current (atom (store/create! cfg))]
    (try
      (with-open [channel (lock-channel home)]
        (is (busy? channel))
        (is (= :applied (:status (maintenance/compact! @current {:publish-connection! #(reset! current %)}))))
        (is (busy? channel))
        (store/release! @current)
        (reset! current nil)
        (is (not (busy? channel))))
      (finally (when @current (store/release! @current)) (remove-home! home)))))

(deftest failed-open-does-not-leak-pool-ownership
  (let [home (temporary-home) cfg (store/config {:backend :file :path (str (io/file home "pool"))})]
    (try
      (with-redefs [pool/migrate-pool! (fn [_] (throw (ex-info "refused migration" {})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused migration" (pool/open-pool! cfg))))
      (with-open [channel (lock-channel home)] (is (not (busy? channel))))
      (finally (remove-home! home)))))
