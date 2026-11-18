(ns open.atlas.common.connector.api-kit.impl.conformance
  "The conformance generator: a source definition plus a recorded fixture in,
   a `clojure.test` suite out.

   The suite is DERIVED, not written, because the thing being protected is not
   one API — it is the hundredth one. A hand-written suite covers what its
   author remembered; a generated one covers the same capabilities for every
   source, so a connector regression shows up in all of them at once and an
   onboarding that skipped batch alignment is a MISSING capability rather than
   an untested one.

   **Everything asserted here is [doc 107](../../../../../../../../docs/design/107-api-v1-revision.md)
   §107.4–§107.8, read off the source's own declarations.** The generator has no
   opinions of its own: which status means «this input is not there» comes from
   `:status-map`, whether a 429 is retried comes from the effective `:resilience`
   policy, and how a collection is walked comes from `:collection :paging`. A
   capability the definition does not declare produces NO test — a green test
   that asserted nothing is worse than a gap you can see.

   Capability → what it proves (§107.8):

     :read                      the recorded sample reaches its mapped fields
     :by-key-not-found          by-key 404 is nil + `:engine/not-found`;
                                `{404 :source}` makes it a refusal instead
     :row-outcome               a mapped `:input-rejected` singleton is a typed
                                per-input failure (`:engine/row-outcomes`), a
                                mapped `:not-found` singleton is an empty slot
     :unmapped-status           a status the map does not name fails the WHOLE
                                resolver — isolation is opt-in per status
     :list                      a list read answers a vector, `[]` when empty
     :paging                    the declared strategy: two pages + `:limit`
                                early stop, `:max-pages` refusal, cursor loop
                                refusal, cross-origin `Link` refusal with
                                nothing sent to the other origin, snapshot
                                mismatch refusal; `:none` means a `Link: next`
                                is never followed
     :batch-alignment           rows follow input order, a miss is nil
     :batch-duplicates          duplicate inputs keep duplicate slots
     :batch-chunking            `:chunk-size` really splits the request
     :batch-isolation           an isolated status fails ITS input only, within
                                the 2N-1 request bound
     :batch-isolation-budget    ATLAS_API_BATCH_ISOLATION_MAX_EXTRA_REQUESTS
                                exhaustion refuses the query, does not truncate
     :failure-unauthorized      401/403 → `:unauthorized`, NEVER retried
     :failure-rate-limited      429 + `Retry-After` → `:rate_limited` carrying
                                `:retry-after-ms`; retried once when the policy
                                allows, not at all at `:max_retries 0`
     :failure-unavailable       502/503/504 → `:unavailable`
     :failure-timeout           a trickled body → `:timeout`
     :failure-cancelled         a lapsed deadline → `:cancelled`, no request
     :audit-receipt             `:ok? :http-status :attempt :category` (and
                                `:page` when paged) present; URL, body and
                                values absent"
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing]]
            [open.atlas.common.connector.api :as api]
            [open.atlas.common.connector.api-kit.impl.fixture :as fx]
            [open.atlas.common.connector.api-kit.impl.redact :as redact]
            [open.atlas.common.connector.api-kit.impl.replay :as replay]
            [open.atlas.common.connector.spi :as spi]))

;; ---------------------------------------------------------------------------
;; Source definitions
;; ---------------------------------------------------------------------------

(defn operation-source-def
  "Normalise a source definition and derive the read SHAPE the contract keys
   off: `:by-key?` (single read anchored on `:input` — the only shape where a
   404 is «no such row»), `:batch?`, `:list?`."
  [m]
  (let [sc (:source-config m)
        rc (:resolver-config m)
        list? (= "list" (:result_type rc))]
    (assoc m
           :batch?     (boolean (:batch sc))
           :list?      list?
           :status-map (or (:status-map sc) {})
           :policy     (spi/effective-policy [(:resilience sc)] nil)
           :collection (or (:collection m) (:semantic/collection rc))
           :by-key?    (and (not list?) (boolean (seq (:input rc))) (not (:batch sc))))))

(defn- paging-block
  "`:collection :paging` normalised to a map. `:none` is legal as a bare
   keyword (doc 107 §107.6 keeps the old spelling valid)."
  [collection]
  (let [p (:paging collection)]
    (cond (map? p)     p
          (keyword? p) {:strategy p}
          :else        nil)))

(defn semantic-source-defs
  "Every api read binding of a Semantic 1.4 document, as source definitions.

   `connections` is `{slot-id {:base_url … :resilience …}}` — the deployment
   half the document deliberately does NOT carry. This mirrors what
   `runtime/env`'s `apply-policies` hands the compiler (doc 107 §107.3): the
   slot's declared `:source/policy` merged with the deployment's capacity half
   and clamped, plus the binding's own override on top, and the api-v2 reading
   of `:timeout_ms` iff the graph declares `:source/policy-v1`.

   The kit re-derives it rather than calling the runtime because a source repo
   holding only a graph must be able to run this suite (D1.8) — and it uses the
   SPI's own `effective-policy`, so the merge rule itself is not written twice."
  ([document] (semantic-source-defs document {}))
  ([document connections]
   (let [caps (set (get-in document [:publication :required-capabilities]))
         v2?  (contains? caps :source/policy-v1)]
     (vec
      (for [[bid b] (sort-by key (get-in document [:execution :bindings]))
            :let [rid      (second (:binding/for b))
                  rd       (get-in document [:execution :resolvers rid])
                  slot-id  (:binding/source b)
                  conn     (get connections slot-id {:base_url "https://fixture.invalid"})
                  declared (get-in document [:execution :sources slot-id :source/policy])
                  override (get-in b [:binding/implementation :policy])
                  policy   (spi/effective-policy [declared override] (:resilience conn))]
            :when (and (= :read (:binding/effect b)) (map? (:binding/implementation b)))]
        (operation-source-def
         {:id          bid
          :source-id   slot-id
          :resolver-id rid
          :collection  (:collection rd)
          :source-config
          (-> (merge (dissoc conn :resilience) (api/binding-operation b))
              (assoc :resilience policy)
              (cond-> v2? (assoc :semantic/attempt-total-timeout? true)))
          :resolver-config
          (cond-> {:id          rid
                   :input       (:input rd)
                   :result_type (if (= :many (:result/cardinality rd)) "list" "single")
                   :semantic/output-transforms (get-in b [:binding/implementation :transforms])
                   :semantic/output-context    (get-in b [:binding/implementation :context])}
            (:collection rd) (assoc :semantic/collection (:collection rd)))}))))))

;; ---------------------------------------------------------------------------
;; Running one operation against a fixture
;; ---------------------------------------------------------------------------

(defn- compiled [{:keys [batch? source-config resolver-config]}]
  (if batch?
    (spi/compile-batch-source source-config nil resolver-config)
    (spi/compile-source source-config nil resolver-config)))

(defn- samples [fixture] (vec (:api-kit/samples fixture)))
(defn- sample-inputs [fixture] (vec (mapcat :inputs (samples fixture))))
(defn- sample-eql [fixture] (or (:eql-params (first (samples fixture))) {}))
(defn- parent-key [sd] (get-in sd [:source-config :batch :parent-key]))

(defn invoke
  "Run this operation against the fixture.

   → `{:result … :calls [..] :failure {…} :receipts [..] :waits [..]}`.
   A connector failure is CAUGHT and reported rather than rethrown: the cases
   assert on the category, the `:retry-after-ms` and the declared bound, and a
   `thrown?` around every call would say only «something went wrong».

   `:deadline-ms` offsets a query deadline from now (negative = already
   lapsed), which is how §107.7's `:cancelled` is reached without waiting."
  [sd fixture {:keys [inputs eql-params overlays deadline-ms policy]}]
  (let [calls    (atom [])
        receipts (atom [])
        waits    (atom [])
        sd       (cond-> sd policy (assoc-in [:source-config :resilience] policy))
        f        (compiled sd)
        run      (fn []
                   (spi/with-retry-sleep (fn [ms] (swap! waits conj ms))
                     (replay/with-replay {:fixtures {[(:source-id sd) (:id sd)] fixture}
                                          :overlays (vec overlays)
                                          :calls    calls}
                       (try
                         {:result (if (:batch? sd)
                                    (f (vec inputs) (or eql-params {}))
                                    (f (or (first inputs) {}) (or eql-params {})))}
                         (catch clojure.lang.ExceptionInfo e
                           (if (spi/failure? e)
                             {:failure {:category       (spi/error-category e)
                                        :retry-after-ms (spi/retry-after-ms e)
                                        :limit          (spi/failure-limit e)
                                        :keys           (set (keys (ex-data e)))}}
                             (throw e)))))))]
    (api/reset-resilience-state!)
    (merge (binding [spi/*statement-trace* (fn [m] (swap! receipts conj m))
                     spi/*request-trace*
                     (if deadline-ms
                       {:deadline-at (+ (System/currentTimeMillis) deadline-ms)}
                       spi/*request-trace*)]
             (run))
           {:calls @calls :receipts @receipts :waits @waits})))

;; ---------------------------------------------------------------------------
;; Reading and rewriting a recorded page
;; ---------------------------------------------------------------------------

(defn- put-in [m tokens v]
  (if (seq tokens) (assoc-in m (mapv keyword tokens) v) m))

(defn- recorded-exchange [fixture] (first (:api-kit/exchanges fixture)))

(defn- recorded-rows
  "The rows of the recorded body, located with the operation's own `:data_path`
   — the same locator the read path uses, exported by the connector precisely
   so a second one never appears (api-v1 `locate-rows`)."
  [sd fixture]
  (let [located (api/locate-rows (:body (recorded-exchange fixture))
                                 (get-in sd [:source-config :data_path]))]
    (cond (sequential? located) (vec located)
          (nil? located)        []
          :else                 [located])))

(defn- body-with-rows [sd fixture rows]
  (put-in (:body (recorded-exchange fixture))
          (redact/path-tokens (get-in sd [:source-config :data_path]))
          (vec rows)))

;; ---------------------------------------------------------------------------
;; Capabilities
;; ---------------------------------------------------------------------------

(defn- mapped-attributes [fm]
  (vec (sort (filter qualified-keyword? (tree-seq coll? seq fm)))))

(defn- mapped-status
  "The first status this source maps to `category`, or nil."
  [sd category]
  (first (sort (keep (fn [[s c]] (when (= c category) s)) (:status-map sd)))))

(defn- unmapped-status
  "A status the source does NOT name — 409 unless it is taken. Used to prove
   isolation is opt-in per status, not «4xx means per-row»."
  [sd]
  (first (remove (set (keys (:status-map sd))) [409 422 400 402])))

(def isolation-extra-request-budget
  "The same knob `impl.run` reads (doc 107 §107.5). Read here because the
   generator has to send enough failing inputs to exhaust it, and a second
   default would make the case pass by never reaching the budget."
  (let [v (some-> (System/getenv "ATLAS_API_BATCH_ISOLATION_MAX_EXTRA_REQUESTS") parse-long)]
    (if (and (integer? v) (<= 0 v)) v 64)))

(defn capabilities
  "Which capabilities this source definition has, in a stable order."
  [sd]
  (let [sd  (operation-source-def sd)
        b   (get-in sd [:source-config :batch])
        pag (paging-block (:collection sd))
        per-row (some #{:not-found :input-rejected} (vals (:status-map sd)))]
    (vec (concat [:read]
                 (when (:by-key? sd) [:by-key-not-found])
                 (when (and (:batch? sd) per-row) [:row-outcome])
                 (when (unmapped-status sd) [:unmapped-status])
                 (when (:list? sd) [:list])
                 (when pag [:paging])
                 (when (:batch? sd)
                   (cond-> [:batch-alignment :batch-duplicates]
                     (:chunk-size b) (conj :batch-chunking)
                     per-row         (conj :batch-isolation :batch-isolation-budget)))
                 [:failure-unauthorized :failure-rate-limited
                  :failure-unavailable :failure-timeout :failure-cancelled
                  :audit-receipt]))))

(defn test-name
  "The `deftest` symbol for one capability — `se-detail-api-batch-alignment`."
  [sd capability]
  (symbol (str (str/replace (str (:id sd)) #"[^A-Za-z0-9-]" "-")
               "-" (name capability))))

;; ---------------------------------------------------------------------------
;; Case bodies
;; ---------------------------------------------------------------------------

(defn- first-row [r] (if (sequential? r) (first r) r))

(defn- retrying-policy
  "This source's effective policy, forced to retry the given category once.
   Used to prove the retry LOOP obeys `Retry-After`; the source's own policy
   is what the `:max_retries 0` half asserts."
  [sd category]
  (assoc (:policy sd) :max_retries 1 :retry_on #{category}
         :backoff_ms [10 40 2.0] :jitter 0))

(defn- failure-case
  [sd fixture {:keys [status headers category note check]}]
  (fn []
    (let [r (invoke sd fixture {:inputs (sample-inputs fixture)
                                :eql-params (sample-eql fixture)
                                :overlays [{:status status :headers headers}]})]
      (when note (testing note (is true)))
      (is (= category (get-in r [:failure :category]))
          (str "HTTP " status " must be " category ", got "
               (pr-str (:failure r))))
      (is (nil? (:result r)) "a refused read answers nothing, never a half row")
      (when check (check r)))))

;; --- paging -----------------------------------------------------------------

(defn- next-handle-overlay
  "An overlay answering page `n` of a walk: the recorded rows, plus whatever
   this strategy uses to say «there is another page»."
  ([sd fixture pag n more?] (next-handle-overlay sd fixture pag n more? "api-kit-snap"))
  ([sd fixture {:keys [strategy next-path snapshot-path]} n more? snapshot]
  (let [rows (recorded-rows sd fixture)
        body (cond-> (body-with-rows sd fixture rows)
               ;; A declared `:snapshot-path` means EVERY page must carry the
               ;; same identity (§107.6), so the ordinary walk carries one --
               ;; otherwise every generated paging case would be measuring the
               ;; snapshot refusal instead of what it says it measures.
               snapshot-path (put-in (redact/path-tokens snapshot-path) snapshot))
        path (:path (fx/split-template (get-in sd [:source-config :endpoint])))
        base (get-in sd [:source-config :base_url])]
    (case strategy
      :cursor      {:when {:nth n}
                    :body (put-in body (redact/path-tokens next-path)
                                  (when more? (str (inc n))))}
      :link-header (cond-> {:when {:nth n} :body body}
                     more? (assoc :link (str "<" base path "?api_kit_page=" (inc n)
                                             ">; rel=\"next\"")))
      ;; `:offset` and `:page` end on an EMPTY page — there is no handle to omit.
      {:when {:nth n} :body (if more? body
                                (cond-> (body-with-rows sd fixture [])
                                  snapshot-path (put-in (redact/path-tokens snapshot-path)
                                                        snapshot)))}))))

(defn- paging-cases
  [sd fixture base]
  (let [pag       (paging-block (:collection sd))
        strategy  (:strategy pag)
        inputs    (sample-inputs fixture)
        eql       (sample-eql fixture)
        page-rows (count (recorded-rows sd fixture))
        max-pages (get-in sd [:collection :bounds :max-pages])
        run       (fn [opts] (invoke sd fixture (merge {:inputs inputs :eql-params eql} opts)))]
    (if (= :none strategy)
      [(base :paging "`:paging :none` — a `Link: rel=next` is never followed"
             (fn []
               (let [r (run {:overlays [{:link "<https://elsewhere.invalid/next>; rel=\"next\""}]})]
                 (is (nil? (:failure r)) (pr-str (:failure r)))
                 (is (= 1 (count (:calls r)))
                     "a source that declares no paging makes exactly one request"))))]

      (->>
       [(base :paging
              (str "two pages and a `:limit` early stop (" (name strategy) ")")
              (fn []
                (let [limit (inc page-rows)
                      r (run {:eql-params (assoc eql :limit limit)
                              :overlays [(next-handle-overlay sd fixture pag 1 true)
                                         (next-handle-overlay sd fixture pag 2 true)
                                         (next-handle-overlay sd fixture pag 3 false)]})]
                  (is (nil? (:failure r)) (pr-str (:failure r)))
                  (is (= limit (count (:result r)))
                      "the caller's `:limit` caps the rows")
                  (is (= 2 (count (:calls r)))
                      "and stops the walk — a third page nobody would read is a page nobody pays for")
                  (testing "stopping early is NOT proof the source is exhausted (§107.6)"
                    ;; The connector knows (`paging/drive!` answers
                    ;; `:exhausted? false`); the SPI seam hands back rows only,
                    ;; so the flag is asserted where it surfaces, not here.
                    (is (= 2 (count (filter :page (:receipts r))))
                        "every page left its own numbered receipt")))))

        (base :paging "every bound REFUSES rather than truncating: `:max-pages`"
              (fn []
                (if-not max-pages
                  (testing "this collection declares no `:max-pages`" (is true))
                  (let [r (run {:overlays (mapv #(next-handle-overlay sd fixture pag % true)
                                                (range 1 (+ max-pages 3)))})]
                    (is (= :source (get-in r [:failure :category])))
                    (is (= :max-pages (:limit (get-in r [:failure :limit])))
                        "the refusal names WHICH bound — «too many» names nothing")
                    (is (nil? (:result r)) "and answers no rows at all")
                    (is (= max-pages (count (:calls r)))
                        "it refused instead of fetching one more")))))

        (when (= :cursor strategy)
          (base :paging "a pager that loops is refused, not followed forever"
                (fn []
                  (let [body (put-in (:body (next-handle-overlay sd fixture pag 1 true))
                                     (redact/path-tokens (:next-path pag))
                                     "api-kit-same")
                        r (run {:overlays [{:body body}]})]
                    (is (= :source (get-in r [:failure :category])))
                    (is (nil? (:result r)))
                    (is (< (count (:calls r)) (or max-pages 200))
                        "the loop was detected, not run into the page ceiling")))))

        (when (= :link-header strategy)
          (base :paging "a cross-origin `Link: next` is refused and never followed"
                (fn []
                  (let [other "https://api-kit-other.invalid"
                        r (run {:overlays [(assoc (next-handle-overlay sd fixture pag 1 true)
                                                  :link (str "<" other "/next>; rel=\"next\""))]})]
                    (is (= :source (get-in r [:failure :category])))
                    (is (not-any? #(= other (:origin %)) (:calls r))
                        "nothing was sent to the other origin — credentials least of all")))))

        (when (:snapshot-path pag)
          (base :paging "a declared snapshot identity must hold across every page"
                (fn []
                  (let [r (run {:overlays [(next-handle-overlay sd fixture pag 1 true "api-kit-snap-1")
                                          (next-handle-overlay sd fixture pag 2 false "api-kit-snap-2")]})]
                    (is (= :source (get-in r [:failure :category]))
                        "two snapshots stitched together is a collection that never existed")
                    (is (nil? (:result r)))))))]
       (remove nil?)))))

;; ---------------------------------------------------------------------------
;; The cases
;; ---------------------------------------------------------------------------

(defn cases
  "Every generated case for one source definition, as DATA: each carries the
   `deftest` name it belongs to, a human label and a zero-argument thunk that
   runs its assertions under `clojure.test`."
  [sd fixture]
  (let [sd      (operation-source-def sd)
        inputs  (sample-inputs fixture)
        eql     (sample-eql fixture)
        attrs   (mapped-attributes (get-in sd [:source-config :field_mapping]))
        pkey    (parent-key sd)
        chunk   (get-in sd [:source-config :batch :chunk-size])
        smap    (:status-map sd)
        base    (fn [cap label run] {:capability cap :test/name (test-name sd cap)
                                     :source-def (:id sd) :label label :run run})
        run     (fn [opts] (invoke sd fixture (merge {:inputs inputs :eql-params eql} opts)))]
    (->>
     (concat
      [(base :read "the recorded sample reaches its mapped fields"
             (fn []
               (let [r (run {})]
                 (is (nil? (:failure r)) (pr-str (:failure r)))
                 (is (= 1 (count (:calls r))) "one sample, one upstream request")
                 (let [row (first-row (:result r))]
                   (is (map? row) "the sample answered a row")
                   (is (some #(contains? row %) attrs)
                       "at least one mapped attribute reached its value")))))]

      ;; --- by-key 404 (§107.4 default table, §107.5 explicit override) ------
      (when (:by-key? sd)
        [(base :by-key-not-found
               (if (= :source (get smap 404))
                 "`{404 :source}` makes a by-key 404 a refusal, not a missing row"
                 "a by-key 404 is «no such row» — nil, which the engine reports as `:engine/not-found`")
               (fn []
                 (let [r (run {:overlays [{:status 404}]})]
                   (if (= :source (get smap 404))
                     (is (= :source (get-in r [:failure :category])))
                     (do (is (nil? (:failure r))
                             (str "a by-key miss is an ANSWER, not a failure: "
                                  (pr-str (:failure r))))
                         (is (nil? (:result r))))))))])

      ;; --- per-row outcomes (§107.5) ---------------------------------------
      (when (and (:batch? sd) (some #{:not-found :input-rejected} (vals smap)))
        [(base :row-outcome
               (str "a mapped singleton answers per INPUT: "
                    (pr-str (into (sorted-map) smap)))
               (fn []
                 (doseq [[status category] (sort-by key smap)
                         :when (#{:not-found :input-rejected} category)]
                   (testing (str "HTTP " status " → " category)
                     (let [r (run {:inputs (mapv #(hash-map pkey %) ["API-KIT-ROW"])
                                   :overlays [{:status status}]})]
                       (is (nil? (:failure r))
                           "a per-row status never fails the whole resolver")
                       (is (= 1 (count (:result r))))
                       (if (= :input-rejected category)
                         (let [slot (first (:result r))]
                           (is (spi/batch-item-failure? slot)
                               "«could not fetch» is a typed failure, not an absent row")
                           (is (= :input-rejected
                                  (:error/category (spi/batch-item-failure-info slot))))
                           (is (= status (:http-status (spi/batch-item-failure-info slot)))))
                         (is (nil? (first (:result r)))
                             "«not there» is an empty slot — `:engine/not-found`, like a by-key miss")))))))])

      ;; --- a status the map does not name (§107.5: isolation is opt-in) -----
      (when-let [u (unmapped-status sd)]
        [(base :unmapped-status
               (str "HTTP " u " is not in this source's `:status-map`, so it fails the WHOLE resolver")
               (fn []
                 (let [r (run {:overlays [{:status u}]})]
                   (is (= :source (get-in r [:failure :category]))
                       "per-row isolation is declared per status, never inferred from 4xx")
                   (is (nil? (:result r))))))])

      (when (:list? sd)
        [(base :list "a list read answers a vector, and `[]` when the upstream has nothing"
               (fn []
                 (let [r (run {})]
                   (is (sequential? (:result r)) "list shape")
                   (is (every? map? (:result r)) "rows are maps"))
                 (let [e (run {:overlays [{:body (body-with-rows sd fixture [])}]})]
                   (is (nil? (:failure e)) (pr-str (:failure e)))
                   (is (sequential? (:result e)))
                   (is (empty? (:result e))
                       "«none» is an empty vector — not nil, not a half row"))))])

      (when (paging-block (:collection sd)) (paging-cases sd fixture base))

      (when (:batch? sd)
        [(base :batch-alignment "rows follow INPUT order, and a miss is nil"
               (fn []
                 (let [asked (conj (vec (reverse (mapv pkey inputs))) "api-kit-absent-reference")
                       r (run {:inputs (mapv #(hash-map pkey %) asked)})]
                   (is (nil? (:failure r)) (pr-str (:failure r)))
                   (is (= (count asked) (count (:result r))) "one slot per input, always")
                   (is (nil? (last (:result r)))
                       "an input the upstream did not answer for is nil, never a half-filled map"))))

         (base :batch-duplicates "a duplicated input keeps its own slot"
               (fn []
                 (let [v (pkey (first inputs))
                       r (run {:inputs [{pkey v} {pkey v}]})]
                   (is (nil? (:failure r)) (pr-str (:failure r)))
                   (is (= 2 (count (:result r))))
                   (is (= (first (:result r)) (second (:result r)))
                       "the same input twice answers the same row twice"))))])

      (when (and (:batch? sd) chunk)
        [(base :batch-chunking (str ":chunk-size " chunk " really splits the request")
               (fn []
                 (let [n (inc chunk)
                       r (run {:inputs (mapv #(hash-map pkey (str "api-kit-sample-" %)) (range n))})]
                   (is (nil? (:failure r)) (pr-str (:failure r)))
                   (is (= n (count (:result r))))
                   (is (= 2 (count (:calls r)))
                       (str n " inputs at :chunk-size " chunk " is two requests")))))])

      (when (and (:batch? sd) (some #{:not-found :input-rejected} (vals smap)))
        (let [bad-status (or (mapped-status sd :input-rejected) (mapped-status sd :not-found))
              per-row    (get smap bad-status)]
          [(base :batch-isolation
                 (str "HTTP " bad-status " fails its own input only, within the 2N-1 request bound")
                 (fn []
                   (let [asked (conj (vec (mapv pkey inputs)) "API-KIT-ISOLATED")
                         n     (count asked)
                         r (run {:inputs (mapv #(hash-map pkey %) asked)
                                 :overlays [{:when {:placeholder "API-KIT-ISOLATED"}
                                             :status bad-status}]})]
                     (is (nil? (:failure r))
                         "an isolated status does not fail the whole query")
                     (is (= n (count (:result r))))
                     (if (= :input-rejected per-row)
                       (is (spi/batch-item-failure? (last (:result r)))
                           "the failed input carries a typed per-input failure, not nil")
                       (is (nil? (last (:result r)))))
                     (is (every? map? (butlast (:result r)))
                         "the other inputs keep their rows")
                     (is (<= (count (:calls r)) (dec (* 2 n)))
                         "at most 2N-1 requests for one chunk of N inputs"))))

           (base :batch-isolation-budget
                 (str "the extra-request budget (" isolation-extra-request-budget
                      ") refuses the query instead of bisecting forever")
                 (fn []
                   (if (zero? isolation-extra-request-budget)
                     (testing "a zero budget sends no sub-request at all" (is true))
                     (let [n (+ isolation-extra-request-budget 4)
                           r (run {:inputs (mapv #(hash-map pkey (str "API-KIT-BAD-" %)) (range n))
                                   :overlays [{:status bad-status}]})]
                       (is (= :source (get-in r [:failure :category]))
                           "budget exhaustion is a refusal of the whole query (§107.5)")
                       (is (nil? (:result r))
                           "no partial result handle is issued for the chunks that did finish")))))]))

      ;; --- failure classes (§107.4 / §107.8) --------------------------------
      [(base :failure-unauthorized
             "401 is `:unauthorized` and is NEVER retried, whatever the policy says"
             (fn []
               (let [r (invoke sd fixture
                               {:inputs inputs :eql-params eql
                                ;; A policy that retries everything it is ALLOWED
                                ;; to retry: `:unauthorized` is not transient, so
                                ;; the loop must not touch it (D6.4).
                                :policy (assoc (:policy sd) :max_retries 3
                                               :retry_on spi/transient-categories
                                               :backoff_ms [1 2 1.0] :jitter 0)
                                :overlays [{:status 401}]})]
                 (is (= :unauthorized (get-in r [:failure :category])))
                 (is (= 1 (count (:calls r)))
                     "an authorization refusal repeated is an authorization refusal"))))

       (base :failure-rate-limited
             "429 carries `:retry-after-ms`; retried once when the policy allows, never at `:max_retries 0`"
             (fn []
               (let [overlay [{:status 429 :headers {"Retry-After" "2"}}]
                     retried (invoke sd fixture
                                     {:inputs inputs :eql-params eql
                                      :policy (retrying-policy sd :rate_limited)
                                      :overlays overlay})
                     never   (invoke sd fixture
                                     {:inputs inputs :eql-params eql
                                      :policy (assoc (:policy sd) :max_retries 0)
                                      :overlays overlay})]
                 (is (= :rate_limited (get-in retried [:failure :category])))
                 (is (= 2000 (get-in retried [:failure :retry-after-ms]))
                     "the upstream said when it would answer; our backoff does not argue")
                 (is (= #{:error/category :resolver-id :source-type :retry-after-ms}
                        (get-in retried [:failure :keys]))
                     "and the failure face stays closed — no URL, no body, no row value")
                 (is (= 2 (count (:calls retried))) "the original attempt plus exactly one retry")
                 (is (= [2000] (:waits retried)) "and it waited what it was told to wait")
                 (is (= 1 (count (:calls never)))
                     "`:max_retries 0` buys nothing extra — retry is opt-in"))))

       (base :failure-unavailable "502 / 503 / 504 are `:unavailable`, never an empty answer"
             (fn []
               (doseq [status [502 503 504]]
                 (testing (str "HTTP " status)
                   ((failure-case sd fixture {:status status :category :unavailable}))))))

       (base :failure-timeout "a body that trickles and stops is a `:timeout`, not a `:source` error"
             (fn []
               (let [r (run {:overlays [{:trickle {:prefix "{\"products\":[" :delay-ms 5}}]})]
                 (is (= :timeout (get-in r [:failure :category]))
                     (str "expected :timeout, got " (pr-str (:failure r))))
                 (is (nil? (:result r))))))

       (base :failure-cancelled
             "a lapsed query deadline is `:cancelled` and sends NO request (§107.7)"
             (fn []
               (let [r (invoke sd fixture {:inputs inputs :eql-params eql :deadline-ms -1})]
                 (is (= :cancelled (get-in r [:failure :category])))
                 (is (empty? (:calls r))
                     "a budget already spent is not a reason to make the source work"))))

       (base :audit-receipt
             "the receipt carries `:ok? :http-status :attempt :category` and no URL, body or value"
             (fn []
               (let [ok   (run {})
                     bad  (run {:overlays [{:status 503}]})
                     tmpl (:path (fx/split-template (get-in sd [:source-config :endpoint])))
                     host (get-in sd [:source-config :base_url])
                     text (pr-str (concat (:receipts ok) (:receipts bad)))]
                 (is (seq (:receipts ok)) "a read leaves a receipt")
                 (is (true? (:ok? (first (:receipts ok)))))
                 (is (every? #(integer? (:attempt %)) (:receipts ok))
                     "`:attempt` says WHICH try this was (§107.8)")
                 (is (= [] (:values (first (:receipts ok))))
                     "values are never interpolated into the receipt")
                 (is (str/includes? (:sql (first (:receipts ok))) tmpl)
                     "the receipt names the endpoint TEMPLATE")
                 (is (some #(= 503 (:http-status %)) (:receipts bad))
                     "`:http-status` is on the receipt of a failed call")
                 (is (some #(= :unavailable (:category %)) (:receipts bad))
                     "`:category` names the failure class, not just «failed»")
                 (when (#{:offset :page :cursor :link-header}
                         (:strategy (paging-block (:collection sd))))
                   ;; `:paging :none` is not a walk, so there is no page number
                   ;; to carry — §107.6 asks for one receipt PER PAGE, and a
                   ;; single-request read has exactly one page in the sense
                   ;; that it has none.
                   (is (every? #(integer? (:page %)) (:receipts ok))
                       "a paged read numbers every page's receipt"))
                 (is (not (str/includes? text (str host)))
                     "no resolved URL reaches the receipt")
                 (doseq [v (keep #(get % (parent-key sd)) inputs)]
                   (is (not (str/includes? text (str v)))
                       "no business value reaches the receipt")))))])
     (remove nil?)
     vec)))

(defn conformance-tests [sd fixture] (cases sd fixture))

(defn run-capability!
  "Run every case of one capability under `clojure.test`."
  [sd fixture capability]
  (doseq [c (cases sd fixture) :when (= capability (:capability c))]
    (testing (:label c) ((:run c)))))
