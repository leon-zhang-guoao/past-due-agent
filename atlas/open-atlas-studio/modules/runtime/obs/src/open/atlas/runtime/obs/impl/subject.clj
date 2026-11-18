(ns open.atlas.runtime.obs.impl.subject
  "The subject-keyed index over audit events (audit-event-v1 §6).

   The JSONL stream is the authority for the full flow; this index is the
   authority for «what happened to THIS token / THIS graph». They answer
   different questions and neither replaces the other: the stream rolls, and a
   rolled-out generation is unreadable, so a question asked months later about
   one token cannot be served from it.

   One file per (subject, request-id, event), written atomically through
   runtime/store — so a crash mid-write leaves either the old state or the new
   one, never half a record. Writing the same event twice lands on the same
   path and is therefore idempotent, which is what makes a retried caller safe.

   The layout is deliberately the one `runtime/token` used before the ledgers
   were merged (owner ruling 2026-08-31, 8.10.4): a directory per subject, one
   EDN record per event. Merging changed WHO writes it, not what the operator
   finds on disk."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [open.atlas.common.crypto :as crypto]
            [open.atlas.runtime.store :as store])
  (:import [java.io File]))

(def ^:private index-dir "audit-index")

(defn- subject-dir ^File [dir kind id]
  ;; A qualified name («sales/app») is TWO identifiers with a separator, not one
  ;; illegal segment. Splitting it nests the directories and keeps the identity
  ;; byte-for-byte — that is structuring, not sanitising, which D4.8 forbids:
  ;; nothing is rewritten, and a «/» cannot occur inside a segment, so the split
  ;; is reversible and collision-free.
  (apply io/file dir index-dir
         (store/safe-segment! (name kind) "audit subject kind")
         (mapv #(store/safe-segment! % "audit subject id")
               (str/split (str id) #"/"))))

(defn subjects
  "Every (kind, id) an event is filed under. An event with no subject is stream
   only — it is still audited, it just cannot be asked about by subject."
  [{:keys [token-id graph actor]}]
  (cond-> []
    token-id (conj [:token (str token-id)])
    graph    (conj [:graph (str graph)])
    ;; A principal is TWO free-form strings and a path segment is one
    ;; whitelisted token, so the pair is hashed rather than sanitised: a
    ;; sanitised name is a second identity for the same person (D4.8 / D3.10).
    ;; Look-up hashes the same way, so this stays queryable.
    (:on-behalf-of actor)
    (conj [:principal (crypto/sha256-hex (pr-str (select-keys (:on-behalf-of actor)
                                                             [:issuer :subject])))])))

(defn principal-key
  "The index id a principal is filed under — the caller-side half of `subjects`."
  [principal]
  (crypto/sha256-hex (pr-str (select-keys principal [:issuer :subject]))))

(defn append!
  "File `event` under each of its subjects. Fail-open like every other obs
   write: the caller is on a request path. → the number of subjects written."
  [dir event]
  (if (nil? dir)
    0
    (let [name* (str (crypto/sha256-hex (pr-str [(:request-id event) (:event event)])) ".edn")]
      (reduce (fn [n [kind id]]
                (try
                  (store/write-edn! (File. (subject-dir dir kind id) name*) event)
                  (inc n)
                  (catch Throwable _ n)))
              0
              (subjects event)))))

(defn for-subject
  "Every event filed under (kind, id), oldest first. Ordering is by :at then
   :request-id — a stable total order, so two readers see the same sequence.
   An absent directory (or an id that is not a legal path segment) reads as
   empty; it must never fall back to scanning some other directory, which is
   how a reader ends up with files that are not audit records at all."
  [dir kind id]
  (if-let [^File d (when dir (try (subject-dir dir kind id) (catch Throwable _ nil)))]
    (vec (sort-by (juxt :at :request-id)
                  (for [^File f (or (.listFiles d) [])
                        :when (and (.isFile f) (.endsWith (.getName f) ".edn")
                                   (not (.startsWith (.getName f) ".tmp-")))
                        :let [e (store/read-edn f)]
                        :when (map? e)]
                    e)))
    []))

(defn for-principal
  "Every event filed under `principal` ({:issuer :subject})."
  [dir principal]
  (for-subject dir :principal (principal-key principal)))
