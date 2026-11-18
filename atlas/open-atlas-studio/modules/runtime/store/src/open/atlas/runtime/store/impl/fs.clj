(ns open.atlas.runtime.store.impl.fs
  "Atomic landing primitives and the path-segment whitelist. **The only place on
   this instance that writes bytes**, so that D4.12's rule — an unavailable
   atomic move is a refusal, never a fallback — is enforced once rather than
   once per subsystem.

   Every write is: same-directory temp → fsync → ATOMIC_MOVE. Same directory
   matters: a temp elsewhere can land on another filesystem, and then the move
   is not atomic — which is the very thing D4.12 refuses to paper over."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.runtime.store.impl.finding :as f])
  (:import [java.io File FileOutputStream]
           [java.nio.file AtomicMoveNotSupportedException Files LinkOption Path
            StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def tmp-prefix ".tmp-")

(def segment-pattern
  "The same whitelist `common/artifact`'s store uses (artifact-v1 §8). Two
   copies would be two answers to \"what is a legal path segment\", so this one
   is kept byte-identical; this module does not depend on `common/artifact`.

   `@` was admitted by the owner on 2026-09-05: the whitelist exists to stop
   path escape (`..`, `/` and other separators), `@` cannot escape and is legal
   on every filesystem we ship to, and since D3.22 a personal workspace is
   named after an email address."
  #"[A-Za-z0-9][A-Za-z0-9._@-]*")

(defn safe-segment!
  "Whitelist a path segment, or throw a structured refusal. **Refuse, do not
   sanitise** (D4.8): a sanitised name is a second identity for the same thing,
   and D3.10 says the qualified name is *the* identity."
  [s what]
  (when-not (and (string? s) (re-matches segment-pattern s))
    (throw (ex-info "illegal path segment"
                    {::finding (f/error :instance.store/illegal-segment []
                                        (str what " is not a legal path segment")
                                        :got s)})))
  s)

(defn finding-of
  "The structured refusal carried by an exception this ns threw, if any."
  [t]
  (::finding (ex-data t)))

(defn- fsync-to! [^File file ^String text]
  (io/make-parents file)
  (with-open [out (FileOutputStream. file)]
    (.write out (.getBytes text "UTF-8"))
    (.flush out)
    (.sync (.getFD out))))

(defn atomic-move!
  "The one atomic landing. It sits behind its own var so a test can make it
   raise (the D4.12 branch is otherwise reachable only by putting the target on
   a second filesystem)."
  [^Path from ^Path to]
  (try
    (Files/move from to (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
    (catch AtomicMoveNotSupportedException _
      ;; D4.12: **refuse, never fall back**. Falling back to a plain move gives
      ;; up atomicity on a misconfigured machine with nobody the wiser.
      (throw (ex-info "atomic move unavailable"
                      {::finding
                       (f/error :instance.store/filesystem-unfit []
                                (str "the staging area and the target are not on the same "
                                     "filesystem, so this landing cannot be atomic; that is a "
                                     "configuration error, not a runtime fallback")
                                :refs ["D4.8" "D4.12"])})))))

(defn write-edn!
  "One EDN value landed **atomically**: same-directory temp → fsync → move."
  [^File file value]
  (io/make-parents file)
  (let [tmp (File. (.getParentFile file) (str tmp-prefix (.getName file)))]
    (try
      (fsync-to! tmp (dialect/canonical-str value))
      (atomic-move! (.toPath tmp) (.toPath file))
      (finally (.delete tmp)))))

(defn write-dir!
  "A set of files landed **atomically** as one directory. An existing target is
   idempotent success (content-addressed: same digest, same bytes)."
  [^File dir files]
  (when-not (.isDirectory dir)
    (io/make-parents (File. dir "x"))
    (let [parent  (.getParentFile dir)
          staging (Files/createTempDirectory (.toPath parent) tmp-prefix
                                             (into-array FileAttribute []))]
      (try
        (doseq [[name* text] (sort files)]
          (fsync-to! (File. (.toFile staging) ^String name*) text))
        (atomic-move! staging (.toPath dir))
        (finally
          (when (Files/exists staging (into-array LinkOption []))
            (doseq [^File leftover (reverse (file-seq (.toFile staging)))] (.delete leftover))))))))

(defn read-edn
  "An EDN file, or nil if absent or unreadable. **Never throws**: a corrupted
   row reads as absent, and every caller already has to handle absence."
  [^File file]
  (when (and (.isFile file) (.canRead file))
    (try (edn/read-string (slurp file :encoding "UTF-8")) (catch Throwable _ nil))))

(defn sweep-temps!
  "Delete leftovers from a crash before an atomic move. → how many were swept."
  [root]
  (let [n (atom 0)]
    (doseq [^File file (file-seq (io/file root))
            :when (and (.exists file) (str/starts-with? (.getName file) tmp-prefix))]
      (doseq [^File x (reverse (file-seq file))] (.delete x))
      (swap! n inc))
    @n))
