(ns open.atlas.common.connector.api.impl.run
  "Compile api operation blocks into executable functions. The interface
   ns's defmethod bodies stay thin; the logic lives here.

   Result shapes follow the SPI verbatim: compile-source →
   (fn [input-params eql-params] → single-map|nil | [map ...]);
   compile-batch-source → (fn [inputs eql-params] → vector aligned with
   inputs). No join wrapping (see impl.rows ns doc)."
  (:require [clojure.string :as str]
            [open.atlas.common.connector.api.impl.interp :as interp]
            [open.atlas.common.connector.api.impl.oauth :as oauth]
            [open.atlas.common.connector.api.impl.request :as request]
            [open.atlas.common.connector.api.impl.paging :as paging]
            [open.atlas.common.connector.api.impl.resilience :as resilience]
            [open.atlas.common.connector.api.impl.rows :as rows]
            [open.atlas.common.connector.api.impl.stream :as stream]
            [open.atlas.common.connector.api.impl.values :as values]
            [open.atlas.common.connector.spi :as spi]))

(def isolation-max-extra-requests
  "Per batch invocation, shared across original chunks; excludes baseline calls."
  (let [v (if-let [s (System/getenv "ATLAS_API_BATCH_ISOLATION_MAX_EXTRA_REQUESTS")]
            (parse-long s) 64)]
    (when-not (and (integer? v) (<= 0 v))
      (throw (ex-info "Batch isolation request budget must be a nonnegative integer"
                      {:setting "ATLAS_API_BATCH_ISOLATION_MAX_EXTRA_REQUESTS"})))
    v))

(defn ctx-of [source-config config]
  {:resolver-id (:id config)
   :source-type (spi/normalize-type (:type source-config))})

(defn full-url
  "api-v1: :base_url (required, connection schema) + :endpoint (operation
   block). The migrated plugin only prefixed :base_url on the mutation
   path; the contract unifies URL construction, so all paths prefix it. An
   :endpoint that is already absolute passes through verbatim."
  [source-config endpoint]
  (if (re-find #"^https?://" (str endpoint))
    (str endpoint)
    (str (:base_url source-config) endpoint)))

(defn- interpolated-url
  "Interpolate + URL-encode {attr} placeholders into the endpoint, then
   REJECT (fail! :mapping) when any placeholder is left unresolved — api-v1
   conformance: refuse rather than send the raw template upstream."
  [source-config endpoint param-map ctx]
  (let [template (full-url source-config endpoint)
        ;; The path-injection boundary (api-v1, doc 106 §106.14 item 8): a
        ;; `{+attr}` value that carries `..`, `?`, `#`, a backslash, an empty
        ;; segment or whitespace is REFUSED before substitution. Encoding it
        ;; here would be the silent rewrite the ruling forbids, and sending it
        ;; would let a key value re-address the request.
        bad (interp/reserved-value-findings template param-map)
        _ (when (seq bad)
            (spi/fail! :mapping ctx
                       (str "value bound to a reserved {+attr} placeholder in :endpoint "
                            "is not a legal path: "
                            (str/join ", " (map #(str "{+" (:name %) "} carries " (:what %)) bad)))))
        url (interp/interpolate-url-params template param-map)
        left (interp/unresolved-placeholders url)]
    (when (seq left)
      (spi/fail! :mapping ctx
                 (str "unresolved placeholder(s) in :endpoint: "
                      (str/join ", " (map #(str "{" % "}") left)))))
    url))

(defn- resolved-json-body
  "Interpolate :json_body and REJECT on unresolved placeholders — the
   migrated plugin silently dropped such entries; the contract refuses."
  [json-body param-map ctx]
  (when json-body
    (let [;; Check the template, never the substituted data. A JSON string or
          ;; a user's literal "{name}" is data, not another interpolation pass.
          template (interp/drop-missing-optional-entries json-body param-map)
          left (remove #(contains? param-map (if (str/starts-with? % "?") (subs % 1) %))
                       (interp/body-unresolved-placeholders template))]
      (when (seq left)
        (spi/fail! :mapping ctx
                   (str "unresolved placeholder(s) in :json_body: "
                        (str/join ", " (map #(str "{" % "}") (distinct left))))))
      (interp/interpolate-body template param-map))))

(defn- param-maps
  "The two string-keyed interpolation maps: URL/endpoint params (from the
   :input attrs) and body params (eql-params by simple name merged UNDER
   input params — :input wins on a name clash, it identifies the entity)."
  [params eql-params]
  (let [param-map (into {} (map (fn [[k v]] [(interp/kw->edn-attr k) v])) params)]
    {:param-map param-map
     :body-param-map (merge (into {} (map (fn [[k v]] [(name k) v])) eql-params)
                            param-map)}))

(defn- build-request
  "Assemble one request map for impl.request/execute! from the operation
   block + call params (the non-batch fetch path of the migrated plugin,
   minus root-body machinery — api-v1 v1 does not accept it)."
  [source-config config params eql-params ctx]
  (let [{:keys [method endpoint headers json_body query_params]} source-config
        declared (:eql-params config)
        {:keys [param-map body-param-map]} (param-maps params eql-params)
        url         (interpolated-url source-config endpoint param-map ctx)
        filter-body (interp/merge-eql-params-into-body eql-params declared)
        body        (cond-> (resolved-json-body json_body body-param-map ctx)
                      (seq filter-body) (interp/merge-into-body filter-body))
        body        (if (and (nil? body) (seq filter-body)) filter-body body)
        pagination  (interp/pagination-query-params eql-params declared)
        query       (merge (interp/templated-query-params query_params body-param-map)
                           pagination
                           (interp/declared-query-params eql-params declared))
        post-with-body? (and (= "POST" (str/upper-case (name (or method "GET"))))
                             (some? body))
        ;; POST+body routes pagination into the body instead of the URL.
        body  (if post-with-body? (interp/merge-into-body body pagination) body)
        query (if post-with-body? (apply dissoc query (keys (or pagination {}))) query)
        final-url (if (seq query)
                    (let [sep (if (str/includes? url "?") "&" "?")]
                      (str url sep (interp/url-encode-params query)))
                    url)]
    {:method method :url final-url :headers headers
     ;; E2b：四条出网路径都打标——源系统运维要认出的是**我们的全部流量**,
     ;; 漏一条就等于「有时候是我们，有时候不知道是谁」。在这里算而不是在
     ;; execute! 里，是为了不把整个 source-config 递进去：那个 ns 明令实现方
     ;; 不得把 source-config 放进回执（凭证住在里面）。
     :tagging (request/tagging-headers source-config headers)
     ;; D6.2 / doc 107 §107.3: the effective policy and the source's identity
     ;; travel with the request. Computed HERE rather than in execute! for the
     ;; same reason the tagging headers are: that ns is forbidden to put a
     ;; source-config into a receipt (credentials live in it), so it never gets
     ;; one -- it gets a policy and a fingerprint.
     :policy (request/effective-policy source-config config)
     :fingerprint (request/fingerprint-of source-config)
     ;; doc 107 §107.5: how THIS upstream spells failure. A single read is not a
     ;; batch, so `:isolatable?` stays false -- a per-row category on a read
     ;; that already addresses one row is the `:not-found` answer, which
     ;; `execute!` handles via `:by-key?`.
     :status-map (resilience/effective-status-map (merge source-config config))
     :timeout (request/timeout-ms source-config)
     :json-body body
     :tls (:tls source-config) :insecure? (:ssl_insecure? source-config)
     :auth (:auth source-config)
     :ctx ctx :endpoint-template endpoint}))

(defn transform-values [value transforms ctx]
  (cond
    (vector? value) (mapv #(transform-values % transforms ctx) value)
    (map? value) (into {} (for [[a v] value]
                           [a (if-let [conversion (and (some? v) (get transforms a))]
                                (try (case conversion
                                       :iso-to-instant (java.time.Instant/parse v)
                                       :number-to-decimal (if (number? v) (bigdec v) (throw (ex-info "numeric value required" {}))))
                                     (catch Exception _ (spi/fail! :mapping ctx "API value does not match its declared conversion")))
                                (transform-values v transforms ctx))]))
    :else value))

(defn compile-read
  "Per-row (non-batch) read. → (fn [input-params eql-params] →
   single-map|nil | [map ...]), shape by :result_type."
  [source-config config]
  (let [ctx   (ctx-of source-config config)
        ;; An in-record collection answers ONE record (doc 106 §106.14 item 15):
        ;; the rows are a branch INSIDE it, so the single view is the right
        ;; reading even for `:result_type "list"` — the engine lifts them out of
        ;; the join root, which is the same thing it does for a map-shaped
        ;; answer from any other source (D2.16).
        nested? (rows/in-record-collection? (:field_mapping source-config))
        list? (and (= "list" (:result_type config)) (not nested?))
        opts  {:data-path (:data_path source-config)
               :field-mapping (:field_mapping source-config)}
        ;; A single record addressed by its `:input` attributes: a 404 from
        ;; that endpoint means the row is not there, not that the source
        ;; failed (0.2 release note defect 11). Every other status keeps its
        ;; meaning, and a `"list"` read is untouched — an absent COLLECTION is
        ;; a different claim from an absent record.
        by-key? (and (not list?) (seq (:input config)))
        coerce (values/coercer (:semantic/output-types config))]
    (fn [input-params eql-params]
      (let [req (assoc (build-request source-config config input-params eql-params ctx)
                       :by-key? (boolean by-key?))
            collection (:semantic/collection config)
            bounds (:bounds collection)
            resp (cond
                   ;; doc 107 §107.6: a declared strategy means the CONNECTOR
                   ;; walks the pages. Handled below, where the rows of each
                   ;; page are appended -- a paged read has no single response.
                   (paging/pages? collection) ::paged
                   bounds (request/execute-bounded! req bounds)
                   :else (request/execute! req))
            paged (when (= ::paged resp)
                    (paging/drive!
                     {:collection collection :ctx ctx
                      :base-url (interp/substituted-base-url source-config)
                      :limit (:limit eql-params)}
                     (fn [{:keys [params url page]}]
                       ;; One page = one attempt: its own deadline check, permit,
                       ;; breaker, retry and statement receipt (§107.7).
                       (let [page-req (cond-> (assoc req :page page :with-headers? true)
                                        url (assoc :url url)
                                        (and (seq params) (not url))
                                        (update :url interp/add-query-params params))]
                         (if bounds
                           (request/execute-bounded! page-req bounds)
                           (request/execute! page-req))))
                     (fn [page-resp] (rows/response->rows (:body page-resp) opts))))
            resp (if paged ::paged resp)]
        (let [context (into {} (map (fn [[a spec]] [a (if (contains? spec :input) (get input-params (:input spec)) (:value spec))]))
                            (:semantic/output-context config))
              result (cond
                       (= ::request/not-found resp) nil
                       paged (rows/stamp-input-on-list-rows
                              (:rows paged) input-params (:input config))
                       list? (rows/stamp-input-on-list-rows
                              (rows/response->rows resp opts) input-params (:input config))
                       :else (rows/response->single resp opts))]
          (coerce
           (transform-values
            (cond (or (empty? context) (nil? result)) result
                  (vector? result) (mapv #(merge % context) result)
                  :else (merge result context))
            (:semantic/output-transforms config) ctx)))))))

(defn- fetch-chunks
  "Run `chunks` through `fetch` with at most `concurrency` in flight, answering
   their results IN INPUT ORDER (doc 107 §107.7).

   **`concurrency 1` is the serial path, unchanged.** Not «a pool of one»: the
   default must stay byte-identical to what every published graph does today --
   same call order, same results, same receipts -- and a pool of one still moves
   the work to another thread, which changes which thread the dynamic trace and
   deadline are read on.

   Above one, three things the ruling asks for and one it does not:

   - each chunk takes its own bulkhead permit and its own deadline check,
     because each is an attempt (that happens inside `fetch` -> `execute!`);
   - the dynamic request trace is CARRIED to the workers by hand: a `binding`
     does not follow work onto another thread, and without it a chunk would run
     with no deadline and no correlation ids -- the same trap D6.3's design note
     describes for Pathom's own parallel resolvers;
   - the FIRST whole-resolver failure stops the rest: chunks not yet started are
     abandoned rather than sent. The query is already lost, and the remaining
     requests would cost the upstream real work to produce an answer nobody
     will read.
   - bisection is NOT parallel: it happens inside one `fetch` call and stays
     serial there (§107.7 -- splitting is how we find one bad key, and doing it
     in parallel only multiplies the requests)."
  [chunks fetch concurrency]
  (if (= 1 concurrency)
    (mapv #(fetch % false) chunks)
    (let [trace     spi/*request-trace*
          sink      spi/*statement-trace*
          failure   (atom nil)
          pool      (java.util.concurrent.Executors/newFixedThreadPool
                     (int concurrency)
                     (reify java.util.concurrent.ThreadFactory
                       (newThread [_ r]
                         (doto (Thread. r "atlas-api-chunk") (.setDaemon true)))))]
      (try
        (let [tasks (mapv (fn [c]
                            ^{:type Callable}
                            (fn []
                              (if @failure
                                ::abandoned
                                (binding [spi/*request-trace* trace
                                          spi/*statement-trace* sink]
                                  (try (fetch c false)
                                       (catch Throwable t
                                         (compare-and-set! failure nil t)
                                         ::abandoned))))))
                          chunks)
              results (mapv (fn [^java.util.concurrent.Future f] (.get f))
                            (.invokeAll pool ^java.util.Collection tasks))]
          (when-let [t @failure] (throw t))
          (mapv #(if (= ::abandoned %) [] %) results))
        (finally (.shutdownNow pool))))))

(defn compile-batch
  "Batch read, api-v1 shape 1 (id-list-in-request): the distinct parent ids
   are joined by :separator into the :placeholder (endpoint and json_body),
   one request per :chunk-size chunk, the echoed :child-fk groups the rows
   back per input. → vector aligned with inputs; a missing to-one row is
   nil, a missing to-many bucket is [] — never a half-filled map.

   The reference implementation's second batch shape (:body-mode and
   friends) is NOT migrated: api-v1 support policy rejects those keys."
  [source-config config]
  (let [ctx   (ctx-of source-config config)
        {:keys [method endpoint headers json_body]} source-config
        batch (:batch source-config)
        {:keys [placeholder separator]} batch
        fm    (:field_mapping source-config)
        item-fm (rows/batch-field-mapping fm)
        to-one? (not= "list" (:result_type config))
        data-path (:data_path source-config)
        ;; doc 107 §107.5: the trigger is no longer two hardcoded numbers but
        ;; the source's own `:status-map` (with `:isolate-statuses` rewritten to
        ;; its equivalent). The literal status sets live in the dialect now.
        status-map (resilience/effective-status-map (merge source-config config))
        per-row?   (boolean (some #{:not-found :input-rejected} (vals status-map)))
        ;; The GET restriction of the 2026-09-17 ruling is gone (§107.5): a POST
        ;; body ID-list batch is the same shape and gets the same treatment.
        ;; What remains is the shape that MAKES a per-row answer meaningful --
        ;; one flat record per input. On a to-many or nested read there is no
        ;; single slot a «this row is absent» claim could land in.
        _ (when (and per-row?
                     (or (not to-one?) (not-every? keyword? (:output config))))
            (spi/fail! :mapping ctx "Per-row status mapping requires one flat output record per input"))
        ;; doc 107 §107.7 / §107.9 item 10: how many chunks may be in flight.
        ;; Already clamped by the deployment's `:bulkhead :concurrency` when the
        ;; effective policy was merged (§107.3), so this number is the smaller
        ;; of what the modeller asked for and what operations granted.
        concurrency (max 1 (or (get-in (request/effective-policy source-config config)
                                       [:batch :concurrency])
                               1))
        coerce (values/coercer (:semantic/output-types config))]
    (fn [inputs _eql-params]
      (let [inputs (vec inputs)
            failures (atom {})
            extra-requests (atom 0)
            fetch-chunk
            (fn fetch-chunk [ids extra?]
              ;; Check before issuing HTTP; a source-wide 400 must not expand
              ;; every catalog chunk into a full binary tree of requests.
              (when (and extra? (> (swap! extra-requests inc) isolation-max-extra-requests))
                (spi/fail! :source ctx "Batch isolation extra-request budget exceeded"))
              (let [id-list (str/join (or separator ",") (map str ids))
                    url  (interpolated-url source-config endpoint
                                           {placeholder id-list} ctx)
                    body (when json_body
                           (rows/interpolate-batch-body json_body placeholder ids id-list))
                    resp (request/execute!
                          {:method method :url url :headers headers
                           :tagging (request/tagging-headers source-config headers)
                           :policy (request/effective-policy source-config config)
                           :fingerprint (request/fingerprint-of source-config)
                           :timeout (request/timeout-ms source-config)
                           :json-body body
                           :tls (:tls source-config)
                           :insecure? (:ssl_insecure? source-config)
                           :auth (:auth source-config)
                           :ctx ctx :endpoint-template endpoint
                           ;; A chunk is never `:by-key?`: it carries many ids,
                           ;; so a status about "the row" is about one of them
                           ;; and has to be bisected for.
                           :status-map status-map :isolatable? per-row?})]
                (if (spi/batch-item-failure? resp)
                  (if (= 1 (count ids))
                    ;; Singleton: the claim is now about THIS input.
                    ;; `:not-found` says the row does not exist -- an answer, so
                    ;; the slot is simply left empty and the engine reports
                    ;; `:engine/not-found`, exactly as a by-key 404 does.
                    ;; `:input-rejected` says we could not fetch it, which is
                    ;; NOT proof of absence, so it stays a typed failure and
                    ;; surfaces as `:engine/row-outcomes` (§107.4).
                    (do (when (not= :not-found (:error/category (spi/batch-item-failure-info resp)))
                          (swap! failures assoc (first ids) resp))
                        [])
                    (let [half (quot (count ids) 2)]
                      ;; Realize left before right; never grow source concurrency.
                      (into (vec (fetch-chunk (subvec (vec ids) 0 half) true))
                            (fetch-chunk (subvec (vec ids) half) true))))
                  (let [located (rows/locate-rows resp data-path)]
                    (cond (sequential? located) located
                          (nil? located) []
                          :else [located])))))
            chunks  (vec (rows/chunked-ids inputs batch))
            items   (into [] (mapcat identity) (fetch-chunks chunks fetch-chunk concurrency))
            grouped (rows/group-api-items items inputs batch)]
        (mapv (fn [input bucket]
                (let [context (into {} (map (fn [[a spec]]
                                             [a (if (contains? spec :input)
                                                  (get input (:input spec)) (:value spec))]))
                                    (:semantic/output-context config))
                      ;; Only mapped records are coerced; a failure slot is
                      ;; the SPI's typed evidence and keeps its own shape.
                      map-item #(coerce (transform-values (merge (rows/map-api-item item-fm %) context)
                                                          (:semantic/output-transforms config) ctx))]
                  (if-let [failure (get @failures (ffirst (rows/chunked-ids [input] batch)))]
                    failure
                    (if to-one?
                      ;; Missing records retain their slot without fabricating context-only rows.
                      (when-let [item (first bucket)] (map-item item))
                      (mapv map-item bucket)))))
              inputs grouped)))))

(defn compile-write
  "Mutation. :result_type \"none\" ignores the response body and returns
   nil (api-v1 capability table: mutation over POST/PUT/PATCH/DELETE)."
  [source-config config]
  (let [ctx (ctx-of source-config config)
        {:keys [method endpoint headers json_body field_mapping]} source-config
        rt  (:result_type config)
        opts {:data-path (:data_path source-config)
              :field-mapping field_mapping}
        coerce (values/coercer (:semantic/output-types config))]
    (fn [mutation-params]
      (let [param-map (into {} (map (fn [[k v]] [(interp/kw->edn-attr k) v]))
                            mutation-params)
            url  (interpolated-url source-config endpoint param-map ctx)
            body (resolved-json-body json_body param-map ctx)
            resp (request/execute!
                  {:method method :url url :headers headers
                   :tagging (request/tagging-headers source-config headers)
                   :policy (request/effective-policy source-config config)
                   :fingerprint (request/fingerprint-of source-config)
                   ;; D6.4: a mutation is not idempotent unless it says so, and
                   ;; api-v1 has no key for saying so yet. It still takes a
                   ;; permit and still sees the breaker -- only the retry is off.
                   :idempotent? false
                   :timeout (request/timeout-ms source-config)
                   :json-body body
                   :tls (:tls source-config)
                   :insecure? (:ssl_insecure? source-config)
                   :auth (:auth source-config)
                   :ctx ctx :endpoint-template endpoint})]
        (case rt
          "none" nil
          "list" (coerce (rows/response->rows resp opts))
          (coerce (rows/response->single resp opts)))))))

;; ---------------------------------------------------------------------------
;; Health check
;; ---------------------------------------------------------------------------

(defn- config-missing-vars
  "Names of ${VAR} references health should verify resolve: base_url,
   header values, tls passwords, plus everything in the :auth block (its
   key set is owned by impl.oauth, so it is asked rather than duplicated —
   the two lists drifting apart is how :client_id went unresolved)."
  [source-config]
  (let [t (:tls source-config)]
    (distinct
     (concat (interp/missing-vars
              (concat [(:base_url source-config)]
                      (vals (:headers source-config))
                      [(:keystore_pass t) (:truststore_pass t)]))
             (oauth/missing-vars (:auth source-config))))))

(defn bind-base-url
  "A source config whose `:base_url` placeholders are filled from `bindings`
   ({attr-kw → value}) — used to check ONE instance of a templated source
   (owner ruling 2026-09-07, doc 106 §106.14 item 11).

   The bound config is for the CALL and nothing else: nothing is written back,
   which is why the caller passes a value rather than the source storing one.
   Values are URL-encoded exactly as they are on the query path
   (`interpolate-url-params`), so a host that checks out here is the host a
   query would reach."
  [source-config bindings]
  (assoc source-config
         :base_url
         (interp/interpolate-url-params
          (:base_url source-config)
          (into {} (map (fn [[k v]] [(interp/kw->edn-attr k) v])) bindings))))

(defn templated-health-finding
  "The refusal a templated `:base_url` gets instead of a health verdict.

   A source whose host is a query-time binding has no ONE upstream to check:
   answering `:down` for it (which is what happened before this ruling — the
   `{…}` stayed in the URL and the connection failed) reports a fault that
   belongs to no instance. So the check refuses, structurally, and names the
   way to check one: bind the attribute, or walk a static roster of them.

   The ruling spells the code `:source/templated-health`; it is registered in
   the connector domain (`connector.*`, finding-v1 §3) like every other code
   this connector produces."
  [source-config]
  {:code :connector.source/templated-health
   :severity :error
   :path [:source :base_url]
   :message (str "a templated source cannot be checked as a whole: :base_url binds its host "
                 "per query, so there is no single upstream to answer for. Check one instance "
                 "by binding the attribute, or check each row of a static roster.")
   :expected (str "a value for " (str/join ", "
                                           (map #(str "{" % "}")
                                                (interp/base-url-bound-names
                                                 (:base_url source-config)))))
   :refs ["api-v1 :base_url placeholders" "doc 106 §106.14 item 11"]})

(defn health
  "On-demand health check → {:status :ok|:down :latency-ms n}; :down
   carries :error/category, and unresolved config variables carry
   :missing-vars (variable NAMES — dialect configuration, never values).
   No message text, no URL — same no-leak discipline as failures.

   The migrated implementation had a :degraded tier for non-2xx answers;
   the platform vocabulary has :ok|:down only, so non-2xx is folded into
   :down. Whether a reachable-but-erroring endpoint deserves its own tier
   is OQ-HEALTH-TIERS (docs/design/90-open-questions.md) — do not extend
   the vocabulary here ahead of that ruling.

   A TEMPLATED `:base_url` gets a third answer: `{:status :refused …
   :findings [finding]}` (owner 2026-09-07, doc 106 §106.14 item 11) — see
   `templated-health-finding` for why that is not a `:down`.

   `connection` (a host-injected handle) has no HTTP meaning and is
   ignored; the signature matches the SPI dispatch point."
  [source-config _connection]
  (let [source-type (spi/normalize-type (:type source-config))
        ctx {:resolver-id nil :source-type source-type}
        missing (config-missing-vars source-config)]
    (if (interp/templated-base-url? (:base_url source-config))
      ;; **Refused, not :down** (owner 2026-09-07, doc 106 §106.14 item 11).
      ;; Reported before the ${VAR} check for one reason: which instance the
      ;; check would even talk to is unanswered, so nothing further about this
      ;; source can be measured.
      {:status :refused :latency-ms -1 :source-type source-type
       :findings [(templated-health-finding source-config)]}
      (if (seq missing)
      {:status :down :latency-ms -1 :error/category :connection
       :missing-vars (vec missing) :source-type source-type}
      (try
        (let [;; :health_endpoint (api-v1: read by the health check only),
              ;; else the source's :base_url.
              url (full-url source-config
                            (or (:health_endpoint source-config) ""))
              start (System/nanoTime)
              ;; Same header build order as the request path — static
              ;; :headers first, then auth — so a gateway requiring a static
              ;; header is not reported down on a check that omitted it.
              _ (request/execute!
                 {:method "GET" :url url :headers (:headers source-config)
                  ;; 体检也打标。它没有 request trace（不属于任何一次查询），
                  ;; 所以只带 User-Agent——那正是对的：对方日志里它该显示成
                  ;; 「Open Atlas 在探活」，而不是某次业务调用。
                  :tagging (request/tagging-headers source-config (:headers source-config))
                  ;; A health check never retries: principles says availability
                  ;; is verified ON DEMAND, and a check that retries reports a
                  ;; source as up that a real query would have found flapping.
                  :policy (assoc (request/effective-policy source-config) :max_retries 0)
                  :fingerprint (request/fingerprint-of source-config)
                  :timeout (request/timeout-ms source-config)
                  :tls (:tls source-config)
                  :insecure? (:ssl_insecure? source-config)
                  :auth (:auth source-config)
                  :ctx ctx :endpoint-template (or (:health_endpoint source-config)
                                                  ":base_url")})]
          {:status :ok
           :latency-ms (quot (- (System/nanoTime) start) 1000000)})
        (catch clojure.lang.ExceptionInfo e
          {:status :down :latency-ms -1
           :error/category (or (spi/error-category e) :connection)
           :source-type source-type})
        (catch Exception _
          {:status :down :latency-ms -1
           :error/category :connection :source-type source-type}))))))

;; Referenced once so 'timeouts have a default' stays single-sourced with
;; the validator (same pattern as connector-sql impl.validate).
(def default-timeout-ms request/default-timeout-ms)

;; oauth cache reset is re-exported for tests via the interface ns.
(def reset-oauth-cache! oauth/reset-cache!)
(def reset-resilience-state! resilience/reset-state!)

;; ---------------------------------------------------------------------------
;; probe —— one real call, one sample (8.4.14 裁定七)
;; ---------------------------------------------------------------------------

(def probe-max-bytes
  "The ceiling on the bytes a probe READS, in bytes (裁定七, restated by
   8.4.23 裁定十七).

   Still a ceiling and not a window: what comes out is a COMPLETE record or
   nothing, because a truncated JSON body infers a truncated field list and
   nothing downstream could tell that list apart from the endpoint's real
   shape. What CHANGED in 裁定十七 is what it bounds — the bytes read into
   memory, not the size of the upstream answer: a catalog endpoint that takes
   no page parameter answers with the whole feed, and the probe needs a sample
   out of it.

   Raised from 256 KiB to 8 MiB by the owner's second 补裁 of 2026-09-04: a
   thousand records of an ordinary catalog do not fit in 256 KiB. What it
   bounds is unchanged — bytes streamed into memory; the sample never lands on
   disk and the audit still carries no response body."
  (* 8 1024 1024))

(def default-sample-size
  "How many records a probe samples unless the caller says otherwise
   (补裁 2026-09-04). Exported through the interface ns so the tool face and
   the wizard name ONE number."
  stream/default-sample-size)

(defn probe
  "Call this api source ONCE and hand back a response SAMPLE — the input
   `add_resolver` uses where `describe-schema` has nothing to say (裁定七).

   Why it is not `describe-schema`: that seam reads structure and never
   content, and this reads content. Making it a separate entry keeps that
   boundary a fact about the code rather than a promise in a docstring, and
   lets the tool face gate it on its own `:probe` capability.

   `request` is `{:path … :params … :method …}` — `:path` relative to the
   source's `:base_url`, `:params` a query-parameter map. One call, one page:
   paging parameters are the caller's to send and are never followed.

   `:data_path` is the caller's answer to «where are the records» (api-v1
   `:data_path` forms). Given, the probe reads `:sample_size` records
   (`default-sample-size`, 补裁 2026-09-04) and disconnects (8.4.23 裁定十七); absent, it
   reads up to the ceiling. Whichever comes first stops it.

   → `{:status n :bytes-read n :sample <parsed body>}` when the whole answer
   fit under the ceiling, `{:status n :bytes-read n :sample [<record> …]
   :records n :records-path p :truncated? true}` when it did not (or when
   `:data_path` let it stop early), `{:status n :bytes-read n :over-limit? true}` when no
   COMPLETE record could be read, and a map with no `:sample` for a non-2xx —
   a status code is an answer the plan must be able to report. Transport
   failures still throw the SPI's failure, as everywhere else.

   **The body never reaches a log or a finding through this function**: it is
   returned to the one caller that asked for it, and the audit record the tool
   face writes carries counts and never content."
  [source-config {:keys [path params method data_path sample_size bindings]}]
  (let [source-type (spi/normalize-type (:type source-config))
        ctx {:resolver-id nil :source-type source-type}
        ;; **`:bindings` fills a templated `:base_url`** (owner 2026-09-07, doc
        ;; 106 §106.14 item 11): a source whose host is a query-time attribute
        ;; has no address until one instance is named, and a probe that sent
        ;; the template would 404 or resolve `{` as a hostname. The values are
        ;; the caller's `sample` — used for this one call and never stored,
        ;; exactly like the sample that fills a templated PATH.
        source-config (cond-> source-config
                        (seq bindings) (bind-base-url bindings))
        _ (when (interp/templated-base-url? (:base_url source-config))
            (spi/fail! :mapping ctx
                       (str "this source's :base_url is templated on "
                            (str/join ", " (map #(str "{" % "}")
                                                (interp/base-url-bound-names
                                                 (:base_url source-config))))
                            "; a probe needs one real value for it")))
        url (full-url source-config (or path ""))
        url (if (seq params)
              (str url (if (str/includes? url "?") "&" "?")
                   (str/join "&" (for [[k v] (sort-by (comp str key) params)]
                                   (str (name k) "="
                                        (java.net.URLEncoder/encode (str v) "UTF-8")))))
              url)]
    (request/execute!
     {:method (or method "GET") :url url
      :headers (:headers source-config)
      :tagging (request/tagging-headers source-config (:headers source-config))
      :policy (request/effective-policy source-config)
      :fingerprint (request/fingerprint-of source-config)
      :timeout (request/timeout-ms source-config)
      :tls (:tls source-config)
      :insecure? (:ssl_insecure? source-config)
      :auth (:auth source-config)
      :ctx ctx
      ;; The TEMPLATE, never the interpolated url: the trace channel is
      ;; configuration-only, and `:base_url` may itself be a `${VAR}`.
      :endpoint-template (str path)
      :raw? true
      :max-bytes probe-max-bytes
      ;; 8.4.23 裁定十七: the body is walked as it arrives. `data_path` given,
      ;; the walk stops at the first complete record and the connection is
      ;; dropped there; absent, it reads to the ceiling and looks for one
      ;; record inside what it read.
      :stream-sample (fn [in] (stream/sample! in probe-max-bytes data_path
                                              (or sample_size default-sample-size)))})))
