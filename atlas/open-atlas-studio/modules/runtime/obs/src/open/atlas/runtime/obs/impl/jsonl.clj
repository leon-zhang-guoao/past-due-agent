(ns open.atlas.runtime.obs.impl.jsonl
  "Shared rolling JSON-lines file writer. Append one line; when the file exceeds
   max-bytes, roll it through a small ring of generations (<path>.1 .. <path>.N,
   newest = .1). Fail-open: never throws to the caller — instead SIGNALS success
   so a caller on the audit path can surface a persistent write loss (a swallowed
   full-disk/permission error is otherwise invisible)."
  (:require [clojure.java.io :as io]
            [open.atlas.runtime.obs.impl.retention :as retention]))

(def ^:private lock retention/lock)

;; How many rolled generations to keep (<path>.1 .. <path>.N) beyond the live
;; file. Small by default — enough history to survive a burst without letting a
;; single always-overwritten .1 be the whole retained trail. Callers may override.
(def default-generations 3)

(defn- roll-if-needed!
  "When f is over max-bytes, shift the generation ring: drop <path>.N (oldest),
   move <path>.i -> <path>.(i+1) for i = N-1..1, then <path> -> <path>.1."
  [^java.io.File f max-bytes generations]
  (when (and (.exists f) (> (.length f) max-bytes))
    (let [base (.getPath f)
          gens (max 1 (long generations))]
      (let [oldest (io/file (str base "." gens))]
        (when (.exists oldest) (.delete oldest)))
      (doseq [i (range (dec gens) 0 -1)]
        (let [src (io/file (str base "." i))]
          (when (.exists src)
            (.renameTo src (io/file (str base "." (inc i)))))))
      (.renameTo f (io/file (str base ".1"))))))

(defn append-line!
  "Append line + newline to path, rolling at max-bytes and keeping `generations`
   rolled files (default `default-generations`). Fail-open: never throws.
   Returns true when the line was written, false when the write failed — so an
   audit-path caller can count/surface an otherwise-silent persistence loss."
  ([path max-bytes line] (append-line! path max-bytes line default-generations))
  ([path max-bytes line generations]
   (try
     (let [f (io/file path)]
       (io/make-parents f)
       (locking lock
         (if-let [cfg (retention/for-path path)]
           (retention/rotate! f cfg (System/currentTimeMillis))
           (roll-if-needed! f max-bytes generations))
         (spit f (str line "\n") :append true)))
     true
     (catch Exception _ false))))
