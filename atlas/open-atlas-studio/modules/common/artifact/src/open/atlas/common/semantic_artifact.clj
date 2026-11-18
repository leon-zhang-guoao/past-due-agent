(ns open.atlas.common.semantic-artifact
  "Pure semantic Artifact format 2 codec. Does not write files, activate graphs,
   authenticate publishers or certify runtime support. Legacy format 1 is untouched."
  (:require [clojure.edn :as edn]
            [malli.core :as m]
            [open.atlas.common.artifact.impl.canonical :as artifact]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.semantic-contract :as contract]))

(def format-version 2)
(def ^:private files #{"content.edn" "manifest.edn" "report.edn"})
(def ^:private content-schema
  [:map {:closed true} [:artifact/format-version [:= 2]] [:document :map]])
(def ^:private manifest-schema
  [:map {:closed true} [:artifact/format-version [:= 2]] [:content-hash contract/digest]
   [:graph/id contract/id] [:release/version contract/id]
   [:deployment {:optional true} [:map {:closed true}
     [:workspace contract/id] [:graph contract/id] [:version pos-int?]]]])
(def ^:private report-schema
  [:map {:closed true} [:artifact/format-version [:= 2]] [:content-hash contract/digest]
   [:validation [:= :contract-validated]]])
(defn- error [code]
  {:findings [{:code code :severity :error :path []}]})

(defn encode-candidate
  "Encode a structurally validated semantic document with a verified dependency
   snapshot. Report attests ONLY contract validation, not runtime tests or approval.
   Return files and externally transported digest, never a self-trusted digest."
  ([document snapshot checkers] (encode-candidate document snapshot checkers nil))
  ([document snapshot checkers deployment]
  (let [delivery (contract/validate-deliverable document checkers)
        checked (if (seq (:findings delivery)) delivery
                    (contract/verify-closure document snapshot checkers))]
    (if (seq (:findings checked)) checked
      (let [content (artifact/canonical-str {:artifact/format-version format-version :document document})
            hash (artifact/content-hash-of-text content)
            manifest (cond-> {:artifact/format-version format-version :content-hash hash
                      :graph/id (get-in document [:graph :graph/id])
                      :release/version (get-in document [:publication :release/version])}
                       deployment (assoc :deployment deployment))
            texts {"content.edn" content "manifest.edn" (artifact/canonical-str manifest)
                   "report.edn" (artifact/canonical-str {:artifact/format-version format-version
                                                         :content-hash hash :validation :contract-validated})}]
        (cond
          (not (m/validate manifest-schema manifest)) (error :semantic.artifact/schema)
          (and deployment
               (or (not= (:workspace deployment) (get-in document [:workspace :workspace/id]))
                   (not= (str (:version deployment)) (get-in document [:publication :release/version]))))
          (error :semantic.artifact/deployment)
          (some #(> (alength (.getBytes ^String % "UTF-8")) (* 4 1024 1024)) (vals texts))
          (error :semantic.artifact/files)
          :else {:files texts :content-hash hash :artifact-digest (artifact/artifact-digest texts) :findings []}))))))

(defn- parse-one [text]
  (let [r (java.io.PushbackReader. (java.io.StringReader. text))
        eof (Object.) opts {:eof eof :readers {} :default (fn [_ _] (throw (ex-info "tag" {})))}
        result (edn/read opts r)]
    (when-not (identical? eof (edn/read opts r)) (throw (ex-info "trailing form" {})))
    result))

(defn read-candidate
  "Verify the externally supplied digest before parsing; require exact files,
   bounded size, envelope hash/identity agreement, supported capabilities and a
   complete pinned closure. Success returns a document, not a running Graph."
  [texts expected-digest snapshot checkers supported-capabilities]
  (try
    (cond
      (or (not (map? texts)) (not= files (set (keys texts)))
          (not-every? #(and (string? %) (<= (alength (.getBytes ^String % "UTF-8")) (* 4 1024 1024))) (vals texts)))
      (error :semantic.artifact/files)
      (not= expected-digest (artifact/artifact-digest texts)) (error :semantic.artifact/digest)
      :else
      (let [content (parse-one (get texts "content.edn"))
            manifest (parse-one (get texts "manifest.edn"))
            report (parse-one (get texts "report.edn"))
            document (:document content)
            hash (artifact/content-hash-of-text (get texts "content.edn"))]
        (cond
          (not (and (m/validate content-schema content) (m/validate manifest-schema manifest) (m/validate report-schema report)))
          (error :semantic.artifact/schema)
          (or (not= hash (:content-hash manifest) (:content-hash report))
              (not= (:graph/id manifest) (get-in document [:graph :graph/id]))
              (not= (:release/version manifest) (get-in document [:publication :release/version])))
          (error :semantic.artifact/integrity)
          :else
          (let [support (contract/check-support document supported-capabilities)]
            (if (seq (:findings support)) support
              (let [closure (contract/verify-closure document snapshot checkers)]
                (if (seq (:findings closure)) closure
                  {:document document :content-hash hash :artifact-digest expected-digest
                   :validation :contract-validated :findings []})))))))
    (catch Exception _ (error :semantic.artifact/unreadable))))

(def runtime-capabilities
  #{:semantic/core-v1 :semantic/alias-v1 :semantic/equivalence-v1
    :semantic/conditional-reference-v1 :connector/sql :connector/derived
    :query/pagination-v1 :query/sort-v1 :query/filter-v1 :operation/write-v1
    :query/batch-isolation-v1 :query/source-collection-v1 :connector/api :connector/static :connector/nifi
    :graph/query-budget-v1 :graph/single-owner-v1 :deployment/source-slot-v1 :artifact/embedded-closure-v1
    ;; doc 107 §107.3 (owner 2026-09-18): this runtime honours the closed
    ;; resilience policy -- the four-level merge, the deployment clamp, and the
    ;; retry/bulkhead/breaker it describes.
    :source/policy-v1
    ;; doc 107 §107.5: and the source-declared status map. It implies
    ;; `:query/batch-isolation-v1`, which is listed above in its own right so
    ;; graphs written against the superseded key keep loading.
    :connector/api-status-map-v1
    ;; doc 107 §107.6: this runtime drives the paging loop itself and enforces
    ;; the collection bounds by refusing rather than truncating.
    :query/paging-strategy-v1
    ;; Semantic contract 2.0 §2 (doc 109 §109.8 items 1-2): this runtime reads
    ;; optional and nested `:input` elements and plans them as Pathom `pco/?`
    ;; and nested inputs rather than as a flat vector of required keys.
    :execution/input-v2
    ;; Semantic contract 2.0 §3 (doc 109 §109.8 items 5 and 7): this runtime
    ;; lowers a definition's free `:params` into the gate-checked eql-params
    ;; and reconciles `:batch?` against the binding's physical `:batch` block.
    :execution/params-v2 :execution/batch-v2
    ;; Semantic contract 2.0 §4 (doc 109 §109.8 item 4): this runtime plans
    ;; several producers of one attribute by `::pco/priority`, falls back on
    ;; failure and reports the fallback as an info issue.
    :execution/priority-v2
    ;; Semantic contract 2.0 §6 (doc 109 §109.8 item 3): this runtime plans
    ;; union output by branch identity and refuses an ambiguous record, and
    ;; clamps a recursive request to the declared depth before planning.
    :execution/output-union-v2 :execution/output-recursion-v2})

(defn format-two? [texts]
  (try (let [s (get texts "content.edn")]
         (and (string? s) (<= (count s) (* 4 1024 1024))
              (let [content (parse-one s)]
                (and (= 2 (:artifact/format-version content)) (contains? content :document)))))
       (catch Exception _ false)))

(defn read-runtime
  "Format 2 through the existing shelf/deploy reader. Runtime dependencies remain unsupported. Declared operations use the
   existing authorized mutation engine and connector execution path. An artifact sealed under a retired contract
   (legacy, 1.0-1.4) is refused with `:contract/version-retired` by the closure check (task book PR1, 2026-09-19)."
  [texts {:keys [expected-digest] :as opts}]
  (let [result (read-candidate texts expected-digest {}
                               ;; Pass each installed adapter's validator through unchanged: since
                               ;; 2026-09-18 it answers {:findings [...]} (semantic-execution-v1
                               ;; checker protocol), and coercing that map with `boolean` made
                               ;; every binding pass on the runtime path. A kind with no installed
                               ;; adapter stays absent so validate-execution fails closed.
                               (into {} (for [kind ["sql" "derived" "api" "static" "nifi"]
                                              :let [f (:validate (spi/semantic-adapter kind))]
                                              :when f]
                                          [kind f]))
                               runtime-capabilities)]
    (if-not (:document result) result
      (let [manifest (parse-one (get texts "manifest.edn"))
            deployment (:deployment manifest)]
        (if (or (nil? deployment)
                (not= (:workspace deployment) (get-in result [:document :workspace :workspace/id]))
                (not= (str (:version deployment)) (get-in result [:document :publication :release/version])))
          (error :semantic.artifact/deployment)
          (assoc result :deployment deployment :references []
                        :verified {:digest true :closure true :content-hash true}))))))
