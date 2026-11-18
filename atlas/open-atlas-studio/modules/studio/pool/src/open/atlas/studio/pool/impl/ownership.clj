(ns open.atlas.studio.pool.impl.ownership
  "One process owns a file pool. Keep the OS lock outside the replaceable pool."
  (:require [clojure.java.io :as io])
  (:import [java.nio.channels FileChannel OverlappingFileLockException]
           [java.nio.file StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(defonce ^:private owners (atom {}))
(defn key-for [cfg]
  (when (= :file (get-in cfg [:store :backend]))
    (.getCanonicalPath (io/file (get-in cfg [:store :path])))))

(defn acquire! [cfg]
  (when-let [key (key-for cfg)]
    (locking owners
      (if (get @owners key)
        (swap! owners update-in [key :references] inc)
        (let [file (io/file (str key ".writer.lock"))
              _ (io/make-parents file)
              channel (FileChannel/open (.toPath file)
                                        #{StandardOpenOption/CREATE StandardOpenOption/WRITE}
                                        (into-array FileAttribute
                                                    [(PosixFilePermissions/asFileAttribute
                                                      (PosixFilePermissions/fromString "rw-------"))]))]
          (try
            (let [lock (try (.tryLock channel) (catch OverlappingFileLockException _ nil))]
              (when-not lock
                (throw (ex-info "Studio pool is already owned by another process. Stop it before opening this pool."
                                {:type :pool/already-owned})))
              (swap! owners assoc key {:channel channel :lock lock :references 1}))
            (catch Throwable e (.close channel) (throw e))))))))

(defn release! [cfg]
  (when-let [key (key-for cfg)]
    (locking owners
      (when-let [{:keys [references channel lock]} (get @owners key)]
        (if (> references 1)
          (swap! owners update-in [key :references] dec)
          (do
            (.release ^java.nio.channels.FileLock lock)
            (.close ^FileChannel channel)
            (swap! owners dissoc key)))))))
