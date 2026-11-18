(ns open.atlas.runtime.env.impl.semantic
  "Shared semantic compilation for authoring and immutable candidates. Host owns
   authenticated access and deployment connections; no workspace database here."
  (:require [clojure.set :as set]
            [open.atlas.common.semantic-contract :as contract]
            [open.atlas.common.semantic-artifact :as artifact]
            [open.atlas.common.semantic-graph-package :as graph-package]
            [open.atlas.common.semantic-compiler :as compiler]
            [open.atlas.common.semantic-protocol :as protocol]
            [open.atlas.common.semantic-result :as result]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.engine :as engine]))

(def preview-capabilities artifact/runtime-capabilities)

(defn- graph-budget [compiled document]
  ;; The root Graph owns the whole-query deadline. A dependency's budget must
  ;; not silently widen its consumer's limit. Contract validation bounds this
  ;; value to 1..900000 ms; absent declarations retain the engine default.
  (if-let [ms (get-in document [:graph :graph/query-budget :execution-ms])]
    (assoc-in compiled [:engine/limits :execution-ms] ms)
    compiled))

;; ---------------------------------------------------------------------------
;; Effective resilience policy (D6.2 four-level merge, doc 107 §107.3 clamp)
;; ---------------------------------------------------------------------------
;;
;; The merge happens HERE, at compile time, for the same reason the graph budget
;; does: this is the one place that sees BOTH halves. The graph carries the
;; semantic half (a Source slot's `:source/policy`, a binding's override)
;; deployment carries the capacity half (the registered Source entry's
;; `:resilience`). A connector sees only what it is handed, so if the two halves
;; were merged down there, every connector would need the deployment's entry --
;; and the tighten-only rule would be enforced once per connector instead of
;; once, which is how two implementations of one rule drift apart (D1.8).
;;
;; What a connector receives is therefore ALREADY effective: the slot's
;; connection entry carries the merged `:resilience`, and each binding carries
;; its own merged `:policy`.

(defn- slot-policies
  "Per source slot: the graph's declared semantic policy and the bindings that
   point at that slot."
  [document]
  (let [e (:execution document)]
    (into {}
          (map (fn [[slot-id source]]
                 [slot-id {:declared (:source/policy source)
                           :bindings (into {} (filter (fn [[_ b]] (= slot-id (:binding/source b))))
                                           (:bindings e))}]))
          (:sources e))))

(defn- declared-envelope
  "The most permissive thing the GRAPH declares for one slot: the slot policy
   widened by every binding override on it.

   §107.3 merges slot and binding FIRST and clamps the result, so the
   tighten-only test has to be made against that merged shape. Testing against
   the slot baseline alone would call a deployment «loosening» when it sits
   comfortably inside a binding's own, higher declaration -- refusing a
   perfectly legal pairing."
  [declared bindings]
  (reduce (fn [envelope [_ b]]
            (let [override (get-in b [:binding/implementation :policy])]
              (-> envelope
                  (assoc :max_retries (max (or (:max_retries envelope) 0)
                                           (or (:max_retries override) 0)))
                  (assoc :retry_on (into (set (:retry_on envelope)) (:retry_on override))))))
          (merge {:max_retries 0 :retry_on #{}} declared)
          bindings))

(defn- policy-findings
  "One `:deployment.policy/loosened` per key a deployment entry tries to widen.

   Names the key and the two numbers, never a value (finding-v1 §5). E6.2 puts
   this decision in the version-switch check and makes it static, so it is an
   ERROR rather than a warning: a deployment that quietly raised `:max_retries`
   multiplies the upstream load a modeller sized, and nobody reading the graph
   could tell."
  [graph-path slot-id declared capacity]
  (for [{:keys [key declared deployment]} (spi/policy-loosenings declared capacity)]
    ;; `declared` here is the ENVELOPE of everything the graph declares for this
    ;; slot (see `declared-envelope`): §107.3 clamps the merged model, so a
    ;; deployment that sits inside a binding's override has tightened nothing,
    ;; even where it exceeds the slot's own baseline.
    {:code :deployment.policy/loosened :severity :error
     :path (into (vec graph-path) [:sources slot-id (if (vector? key) (first key) key)])
     :message (str "This deployment's :resilience tries to WIDEN the graph's declared "
                   "policy for " (pr-str key) " (graph " (pr-str declared)
                   ", deployment " (pr-str deployment)
                   "); a deployment may only tighten it (doc 107 §107.3).")
     :expected "a value at or inside the graph's declaration"}))

(defn- apply-policies
  "Merge and clamp every slot's policy into `connections`, and every binding's
   override into the execution definitions. Returns
   `{:connections … :execution … :policies … :binding-policies … :findings …}`,
   where `:policies` and `:binding-policies` are the EFFECTIVE policies per slot
   and per overriding binding -- what the connector will actually run under. It is reported rather than left implicit because «which policy is in
   force» is a question an operator asks about a running graph, and deriving it
   again from two halves elsewhere would be a second implementation.

   `connection-key` maps a slot id to its key in `connections`."
  [document connections connection-key graph-path]
  (let [;; api-v2 (doc 107 §107.2/§107.12): a graph opts into the re-read
        ;; `:timeout_ms` -- an attempt TOTAL rather than an idle timeout -- by
        ;; declaring `:source/policy-v1`. Decided here, once, from what the
        ;; graph published: the connector must not have to guess which promise
        ;; a stored number was making, and an old graph must keep the meaning
        ;; it was written against.
        v2? (contains? (set (get-in document [:publication :required-capabilities]))
                       :source/policy-v1)]
   (reduce
   (fn [acc [slot-id {:keys [declared bindings]}]]
     (let [ckey     (connection-key slot-id)
           entry    (get connections ckey)
           capacity (:resilience entry)
           slot     (spi/effective-policy [declared] capacity)
           envelope (declared-envelope declared bindings)]
       (-> acc
           (assoc-in [:policies slot-id] slot)
           (cond-> entry (-> (assoc-in [:connections ckey :resilience] slot)
                             (assoc-in [:connections ckey :semantic/attempt-total-timeout?] v2?)))
           (as-> acc acc
             (reduce (fn [acc [bid b]]
                       (if-let [override (get-in b [:binding/implementation :policy])]
                         (assoc-in acc [:binding-policies bid]
                                   (spi/effective-policy [declared override] capacity))
                         acc))
                     acc bindings))
           (update :execution
                   (fn [execution]
                     (reduce (fn [execution [bid b]]
                               (let [override (get-in b [:binding/implementation :policy])]
                                 (if (nil? override)
                                   execution
                                   ;; Only the SEMANTIC half is written back. The
                                   ;; capacity half rides on the slot's
                                   ;; `:resilience`, and `:policy` is a closed
                                   ;; key face that refuses capacity keys -- the
                                   ;; connector merges the two itself.
                                   (assoc-in execution
                                             [:bindings bid :binding/implementation :policy]
                                             (select-keys (spi/effective-policy [declared override] capacity)
                                                          spi/policy-semantic-keys)))))
                             execution bindings)))
           (update :findings into (policy-findings graph-path slot-id envelope capacity)))))
   {:connections connections :execution (:execution document)
    :policies {} :binding-policies {} :findings []}
   (slot-policies document))))

(defn compile-document [document {:keys [adapters connections snapshot] :or {snapshot {}}}]
  (let [checked (contract/verify-closure document snapshot (update-vals adapters :validate))
        support (when (:document checked) (contract/check-support document preview-capabilities))]
    (cond
      (seq (:findings checked)) (select-keys checked [:findings])
      (seq (:findings support)) support
      :else
      (let [policy (apply-policies document (or connections {}) identity [])]
        (if (seq (:findings policy))
          (select-keys policy [:findings])
          ;; Contract 2.0: the descriptor decides how `:input` is read, so
          ;; the compiler is told which contract this document is under.
          (let [candidate (compiler/compile-candidate (:vocabulary document) (:execution policy)
                                                      adapters (:connections policy)
                                                      {:contract (:contract document)})]
            (if-let [compiled (:compiled candidate)]
              {:env (graph-budget compiled document) :document document
               :source-policies (:policies policy)
               :binding-policies (:binding-policies policy) :findings []}
              (select-keys candidate [:findings]))))))))

(defn compile-artifact [files digest {:keys [adapters snapshot] :or {snapshot {}} :as options}]
  (let [read (artifact/read-candidate files digest snapshot (update-vals adapters :validate) preview-capabilities)]
    (if-let [document (:document read)]
      (let [candidate (compile-document document options)]
        (if (:env candidate)
          (assoc candidate :artifact-digest digest :content-hash (:content-hash read)) candidate))
      (select-keys read [:findings]))))

(defn- qualify-source [graph-id source-id]
  (str graph-id "::source::" source-id))

(defn- qualify-binding [graph-id binding-id]
  (str graph-id "::binding::" binding-id))

(defn- merge-package-documents [root documents]
  (let [merge-maps #(apply merge (map (fn [document] (or (% document) {})) documents))
        vocabulary {:entities (merge-maps #(get-in % [:vocabulary :entities]))
                    :attributes (merge-maps #(get-in % [:vocabulary :attributes]))
                    :terms (merge-maps #(get-in % [:vocabulary :terms]))
                    :units (merge-maps #(get-in % [:vocabulary :units]))
                    :aliases (vec (mapcat #(get-in % [:vocabulary :aliases]) documents))
                    :references (vec (mapcat #(get-in % [:vocabulary :references]) documents))
                    :equivalences (vec (mapcat #(get-in % [:vocabulary :equivalences]) documents))}
        per-graph
        (for [document documents
              :let [gid (get-in document [:graph :graph/id])
                    execution (:execution document)
                    source-name #(qualify-source gid %)
                    binding-name #(qualify-binding gid %)]]
          {:sources (into {} (map (fn [[id source]] [(source-name id) source])) (:sources execution))
           :bindings (into {} (map (fn [[id binding]]
                                     [(binding-name id) (update binding :binding/source source-name)]))
                           (:bindings execution))
           :selection (into {} (map (fn [[target id]] [target (binding-name id)])) (:selection execution))
           :resolvers (:resolvers execution)
           :operations (:operations execution)
           :metrics (:metrics execution)})
        merge-execution #(or (apply merge (keep % per-graph)) {})
        execution {:sources (merge-execution :sources)
                   :bindings (merge-execution :bindings)
                   :selection (merge-execution :selection)
                   :resolvers (merge-execution :resolvers)
                   :operations (merge-execution :operations)
                   :metrics (merge-execution :metrics)}]
    (-> root
        (assoc :vocabulary vocabulary :execution execution)
        (assoc-in [:graph :graph/entities] (vec (sort (keys (:entities vocabulary)))))
        (assoc-in [:graph :graph/resolvers] (vec (sort (keys (:resolvers execution)))))
        (assoc-in [:graph :graph/operations] (vec (sort (keys (:operations execution)))))
        (assoc-in [:graph :graph/terms] (vec (sort (keys (:terms vocabulary)))))
        (assoc-in [:publication :required-capabilities]
                  (apply set/union (map #(get-in % [:publication :required-capabilities]) documents))))))

(defn compile-package
  "Verify a self-contained 1.4 package, bind every data-access Graph's one
   logical Source slot to an operator-owned instance, then compile one Runtime
   Graph. `bindings` is keyed by [graph-id source-slot-id]. Multiple keys may
   intentionally point at the same registered Source entry."
  [text digest {:keys [adapters bindings authorize-binding? verify-binding]
                :or {authorize-binding? (fn [_ _] true)
                     verify-binding (fn [_ _] true)}}]
  (let [checkers (update-vals adapters :validate)
        read (graph-package/read-package text digest checkers)]
    (if (seq (:findings read)) (select-keys read [:findings])
      (let [documents (mapv :document (get-in read [:package :graphs]))
            requirements (for [document documents
                               [slot source] (get-in document [:execution :sources])
                               :when (not (contains? #{"derived" "static"} (:source/kind source)))]
                           {:graph-id (get-in document [:graph :graph/id])
                            :slot-id slot :source source})
            resolved (mapv (fn [{:keys [graph-id slot-id source] :as requirement}]
                             (let [entry (get bindings [graph-id slot-id])
                                   path [:graphs graph-id :sources slot-id]
                                   context (select-keys requirement [:graph-id :slot-id :source])]
                               (cond
                                 (nil? entry) (assoc requirement :finding
                                                   {:code :semantic.runtime/source-binding-missing
                                                    :severity :error :path path})
                                 (not= (:source/kind source) (:type entry))
                                 (assoc requirement :finding
                                                   {:code :semantic.runtime/source-binding-incompatible
                                                    :severity :error :path path})
                                 (not (try (boolean (authorize-binding? context entry))
                                           (catch Exception _ false)))
                                 (assoc requirement :finding
                                                   {:code :semantic.runtime/source-binding-forbidden
                                                    :severity :error :path path})
                                 (not (try (boolean (verify-binding context entry))
                                           (catch Exception _ false)))
                                 (assoc requirement :finding
                                                   {:code :semantic.runtime/source-binding-unreachable
                                                    :severity :error :path path})
                                 :else (assoc requirement :entry entry))))
                           requirements)
            findings (vec (keep :finding resolved))]
        (if (seq findings) {:findings findings}
          (let [connections (into {} (map (fn [{:keys [graph-id slot-id entry]}]
                                            [(qualify-source graph-id slot-id) entry])) resolved)
                combined (merge-package-documents (:root read) documents)
                ;; doc 107 §107.3: merge each slot's declared policy with the
                ;; operator's capacity block and refuse a deployment that tries
                ;; to widen it. Runs on the COMBINED document, so a slot's
                ;; qualified name is its connection key.
                policy (apply-policies combined connections identity [])
                ;; The combined document keeps the root's descriptor (a package
                ;; is one contract version throughout, `semantic-graph-package`).
                candidate (when (empty? (:findings policy))
                            (compiler/compile-candidate (:vocabulary combined) (:execution policy)
                                                        adapters (:connections policy)
                                                        {:contract (:contract combined)}))]
            (cond
              (seq (:findings policy)) (select-keys policy [:findings])
              (:compiled candidate)
              {:env (graph-budget (:compiled candidate) combined) :document combined
               :package (:package read) :package-digest digest
               :source-policies (:policies policy)
               :binding-policies (:binding-policies policy)
               :bindings (set (map (juxt :graph-id :slot-id) resolved)) :findings []}
              :else (select-keys candidate [:findings]))))))))

(defn- typed-input
  "The entity as the input check should see it. Callers spell dates, instants
   and decimals the only way EDN and JSON share -- ISO text and plain numbers,
   as `:params` already accept (contract 2.0 §3.1) -- and the published data
   plane hands them to the engine unchanged. The check runs on a copy with
   those values in their declared classes (`spi/declared-value`), so preview
   refuses what the result check would, not a spelling. Text needing a guessed
   zone stays text and is refused."
  [vocabulary entity]
  (letfn [(walk [x]
            (cond
              (map? x) (into {} (map (fn [[k v]]
                                       [k (if (or (map? v) (sequential? v))
                                            (walk v)
                                            (spi/declared-value (get-in vocabulary [:attributes k :attribute/type]) v))]))
                             x)
              (sequential? x) (mapv walk x)
              :else x))]
    (walk entity)))

(defn query
  "Read-query a compiled candidate. `opts` `{:stats? true}` also returns
   `:engine/stats` — the engine's unscrubbed result with Pathom's run-stats —
   which an authoring host turns into Query Studio's Plan and Trace. Off by
   default: the stats are as large as the answer, and a data-plane or agent
   reply has no use for them."
  ([candidate request] (query candidate request {}))
  ([candidate request {:keys [stats?]}]
  (let [document (:document candidate)
        validation (protocol/validate-query-request
                     (merge {:graph/id (get-in document [:graph :graph/id]) :request/id "semantic-preview"} request))]
    (cond
      (not (:env candidate)) {:findings [{:code :semantic.runtime/uncompiled :severity :error :path []}]}
      (seq (:findings validation)) (select-keys validation [:findings])
      (seq (:cursors request)) {:findings [{:code :semantic.runtime/cursors-unsupported :severity :error :path [:cursors]}]}
      (not= (:graph/id (:request validation)) (get-in document [:graph :graph/id]))
      {:findings [{:code :semantic.runtime/graph-mismatch :severity :error :path [:graph/id]}]}
      :else
      (let [input (result/validate-query-result (:vocabulary document)
                    {:engine/status :ok :engine/data (typed-input (:vocabulary document) (or (:entity request) {}))})]
        (if (seq (:findings input))
          (select-keys input [:findings])
          (result/validate-query-result (:vocabulary document)
            (engine/query (:env candidate) nil
              {:query (:query request) :entity (or (:entity request) {})}
              (cond-> {:lenient? (boolean (:lenient? request))}
                stats? (assoc :stats? true))))))))))


(defn execute [candidate {:keys [mutation params]}]
  (let [document (:document candidate)
        operation (get-in document [:execution :operations mutation])
        invalid (fn [code] {:engine/status :rejected :code code})]
    (cond
      (not (:env candidate)) (invalid :semantic.runtime/uncompiled)
      (nil? operation) (invalid :semantic.runtime/unknown-operation)
      (not (map? params)) (invalid :semantic.runtime/operation-input)
      (seq (:findings (result/validate-query-result (:vocabulary document)
                       {:engine/status :ok :engine/data params})))
      (invalid :semantic.runtime/operation-input)
      :else
      (let [response (engine/query (:env candidate) nil
                                  {:query [(list (symbol mutation) params)] :entity {}})]
        (:response (result/validate-execution-result (:vocabulary document) response))))))
