(ns open.atlas.runtime.store.impl.lease
  "The instance's single lease, its poisoned flag, and the subsystem lock
   registry.

   ## One writer per instance (owner ruling 2026-08-24)

   An instance *is* a root directory. v1 allows exactly one control-plane
   WRITER process on it: at startup the process must take a root-level
   exclusive lock (`FileChannel/tryLock`, held for life) and **refuse to start**
   if it cannot. It does not degrade into \"everyone be careful\". The OS
   releases it when the process exits or dies, so nothing stale is left behind
   — which is the reason it is a real file lock and not a lock *file*.

   ## Why the lease is opened once for the whole instance

   A JVM's `FileLock` is held **by the process, not by the channel**. A second
   subsystem calling `tryLock` on the same region of the same file inside the
   same JVM does not get refused — it throws `OverlappingFileLockException`, or
   worse, a second open of the same path gets its own channel and the code
   *believes* it holds an independent lease. Either way the guarantee that made
   the design safe stops being tested at the moment it matters. So the lease is
   opened once by the composition and handed to every subsystem
   (owner ruling 2026-08-25, ③-1).

   ## Poisoned means poisoned for the whole instance

   If any effect fails to land, this process is holding half-applied state and
   refuses **all** further writes until recovery has run. That flag is per
   instance, not per subsystem: a token ledger writing happily on while the
   graph subsystem is known to be half-applied is exactly the state nobody can
   reason about afterwards."
  (:require [clojure.java.io :as io]
            [open.atlas.runtime.store.impl.finding :as f])
  (:import [java.io File]
           [java.nio.channels FileChannel FileLock]
           [java.nio.file StandardOpenOption]))

(defrecord Instance [instance-id ^File root ^FileChannel channel ^FileLock lock
                     locks poisoned])

(def lock-file-name "control.lock")

(defn open!
  "`{:instance-id … :root …}` → `{:instance i :findings []}` or `{:findings […]}`.
   **The caller refuses to start on findings** — there is no degraded mode."
  [{:keys [instance-id root]}]
  (let [file (io/file root lock-file-name)]
    (io/make-parents file)
    (let [ch   (FileChannel/open (.toPath file)
                                 (into-array StandardOpenOption
                                             [StandardOpenOption/CREATE
                                              StandardOpenOption/WRITE]))
          lock (try (.tryLock ch) (catch Throwable _ nil))]
      (if (nil? lock)
        (do (.close ch)
            {:findings
             [(f/error :instance.lease/unavailable []
                       (str "another process already holds this instance's lease on this "
                            "root; v1 allows exactly one control-plane WRITER per instance, "
                            "and refusing to start is the whole point — degrading to "
                            "\"everyone be careful\" is how two writers corrupt a pointer")
                       :got (str file))]})
        {:instance (->Instance instance-id (io/file root) ch lock (atom {}) (atom nil))
         :findings []}))))

(defn close! [{:keys [^FileLock lock ^FileChannel channel]}]
  (when (and lock (.isValid lock)) (.release lock))
  (when channel (.close channel)))

(defn held? [{:keys [^FileLock lock]}] (boolean (and lock (.isValid lock))))

(defn poison! [{:keys [poisoned]} reason] (reset! poisoned reason))
(defn poisoned [{:keys [poisoned]}] @poisoned)
(defn clear-poison! [{:keys [poisoned]}] (reset! poisoned nil))

(defn writable-findings
  "The two gates before any write: is the lease still ours, and is this process
   holding half-applied state?"
  [inst]
  (cond
    (not (held? inst))
    [(f/error :instance.lease/lost []
              (str "this instance's lease is no longer valid; writes stop immediately "
                   "rather than continue without the single-writer guarantee"))]
    (some? (poisoned inst))
    [(f/error :instance.lease/lost []
              (str "a previous effect failed to land, so this process is holding half-applied "
                   "state; it refuses further writes until recovery has run")
              :got (poisoned inst))]))

(defn monitor
  "The in-process monitor for one lock key. **Public because `with-lock` is a
   macro**: the expansion lives in the caller's ns, so a private var would be
   unreachable from there.

   Keys are namespaced by subsystem (`[:graph \"sales/app\"]`, `[:token \"tok-1\"]`)
   so two subsystems cannot collide on a bare string and serialise each other
   for no reason."
  [{:keys [locks]} key]
  (or (get @locks key)
      (get (swap! locks update key #(or % (Object.))) key)))
