(ns open.atlas.common.connector.api
  "api (HTTP) connector — implementation of docs/contracts/connectors/api-v1.md.

   Cross-module code may only require THIS ns; `.impl.*` is private.
   Requiring this ns completes registration: the \"api\" methods for the SPI
   dispatch points are attached here, with no other assembly step (the host
   decides which connectors to load by deciding which nses to require).

   Contract highlights and where they land:
   - {attr} endpoint interpolation, URL-encoded in the URL ..... impl.interp + impl.run
   - unresolved placeholders are REJECTED, never sent raw ...... impl.run (runtime)
                                                                 + impl.validate (write time)
   - :data_path row location, uniform batch/non-batch ........... impl.rows/locate-rows
   - JSONPath :field_mapping ($.a.b, $[*], $[0], nested [*]) .... impl.jsonpath
   - batch shape 1 (id list; placeholder/separator/chunk-size) .. impl.run/compile-batch
   - literal credentials rejected at write time ................. impl.validate
   - :auth discriminated block (oauth2 | api_key) ............... impl.oauth + impl.validate
   - tls > insecure > default connection tiers .................. impl.conn
   - failures via the SPI constructor, never nil-on-error ....... impl.request
   - 401 with an OAuth token: refresh + retry ONCE .............. impl.request

   describe-schema is deliberately NOT implemented: an HTTP upstream
   exposes no introspectable relational structure, so the SPI's refusing
   :default answers for it."
  (:require [clojure.string :as str]
            [open.atlas.common.connector.api.impl.interp :as interp]
            [open.atlas.common.connector.api.impl.nifi :as nifi]
            [open.atlas.common.connector.api.impl.rows :as rows]
            [open.atlas.common.connector.api.impl.run :as run]
            [open.atlas.common.connector.api.impl.validate :as validate]
            [open.atlas.common.connector.spi :as spi]))

(def source-type "api")

(defmethod spi/compile-source source-type
  [source-config _connection resolver-config]
  (run/compile-read source-config resolver-config))

(defmethod spi/compile-batch-source source-type
  [source-config _connection resolver-config]
  (run/compile-batch source-config resolver-config))

;; api-v1「能力」：offset ✓（best-effort）。impl.interp maps it onto the
;; upstream's own offset field, so answering true here matches what runs.
(defmethod spi/supports-offset? source-type [_] true)

(defmethod spi/bound-attributes source-type
  [source-config]
  ;; E1 / D5.14。委托 impl.interp，因为占位符的拼法与解析规则都在那里——
  ;; 在这里重写一遍等于给「这个模板绑了什么」造第二个来源。
  (interp/bound-input-attributes source-config))

(defmethod spi/connection-bound-attributes source-type
  [source-config]
  ;; api-v1 «:base_url placeholders» (owner 2026-09-07, doc 106 §106.14 item
  ;; 11): the authority may name an attribute, and every resolver on this
  ;; source then takes it as an `:input`. Delegated to impl.interp for the same
  ;; reason `bound-attributes` is — the spelling and the position rule live in
  ;; one place.
  (into #{} (keep interp/edn-attr->kw)
        (interp/base-url-bound-names (:base_url source-config))))

(defmethod spi/in-record-collection-attributes source-type
  [source-config]
  ;; §106.14 item 17: api-v1 says «记录内集合» with `:_from` inside the
  ;; `:field_mapping`, and impl.rows is where that spelling is already read —
  ;; naming it a second time here is the second vocabulary D1.8 forbids.
  (rows/collection-branch-attributes (:field_mapping source-config)))

(defmethod spi/validate-source source-type
  [source-config]
  (validate/validate-source source-config))

(defmethod spi/health-check source-type
  [source-config connection]
  (run/health source-config connection))

(defmethod spi/compile-mutation source-type
  [source-config _connection mutation-config]
  (run/compile-write source-config mutation-config))

(defmethod spi/validate-mutation source-type
  [source-config]
  (validate/validate-mutation source-config))

(defmethod spi/contract-version source-type [_] "v1")

(defmethod spi/declared-files source-type
  [source-config]
  ;; api-v1 §"连接 schema": mutual TLS names a keystore (required inside the
  ;; `:tls` block) and optionally a truststore. Both are FILES the installing
  ;; machine must already hold, and they are the only ones this connector opens
  ;; (doc 104 §8.17.7 A). Paths answered as written — resolving `${VAR}` here
  ;; would put a value into a manifest that carries names only.
  (let [tls (:tls source-config)]
    (vec (keep #(let [v (get tls %)] (when (and (string? v) (seq (.trim ^String v))) v))
               [:keystore :truststore]))))

(def probe-max-bytes
  "The probe's response ceiling in bytes (8.4.14 裁定七). Exported so the tool
   face states the same number in its refusal that this connector enforces."
  run/probe-max-bytes)

(def default-sample-size
  "How many records a probe samples unless the caller sets `:sample_size`
   (补裁 2026-09-04, doc 84 §8.4.23). Exported so the tool face and the wizard
   name one number and not three."
  run/default-sample-size)

(defn probe
  "One real call to this api source, and the response SAMPLE it answered with
   — the field evidence `add_resolver` needs where `describe-schema`
   deliberately has none (8.4.14 裁定七).

   `describe-schema` stays unimplemented: that seam reads structure and never
   content. This one reads content, once, bounded, and only for a caller
   holding the `:probe` capability — which is why it is a separate entry and
   not a new answer from the same seam.

   See `impl.run/probe` for the return shape and the ceiling."
  [source-config request]
  (run/probe source-config request))

(defn bind-base-url
  "This source config with its `:base_url` placeholders filled from
   `bindings` ({attr-kw → value}) — how a host checks ONE instance of a
   templated source (owner 2026-09-07, doc 106 §106.14 item 11).

   `health-check` REFUSES a templated `:base_url` («a templated source cannot
   be checked as a whole»), so a caller that knows which instance it means —
   a `sample`, or one row of a static roster — binds it here first and checks
   the result. The bound config lives for that call only; nothing is stored.

   Exported because the decision «which host» belongs to the caller and the
   substitution rules belong to this connector; re-implementing them upstairs
   would be the second answer D1.8 warns about."
  [source-config bindings]
  (run/bind-base-url source-config bindings))

(defn locate-rows
  "Apply this contract's `:data_path` to a parsed response — the SAME locator
   the read path uses (`impl.rows/locate-rows`).

   Exported for 8.4.22 裁定十六 ②: the api modelling path has to read the rows
   out of a probe sample to infer fields, and a second locator there would be
   a second answer to «where are the records» — the one that shows up as a
   plan whose `:data_path` finds nothing at query time."
  [response data-path]
  (rows/locate-rows response data-path))

(defn reset-oauth-cache!
  "Drop every cached OAuth token (tests, REPL, secret rotation)."
  []
  (run/reset-oauth-cache!))

(defn reset-resilience-state!
  "Drop every per-source bulkhead and circuit breaker (tests, REPL).

   The state is process-wide and keyed by source identity ON PURPOSE (D6.5) --
   it outlives any one compiled env. That is also why it needs a way back to a
   known start: without this, one test that trips a breaker decides the verdict
   of the next test against the same host."
  []
  (run/reset-resilience-state!))

(def nifi-query! nifi/query!)

(defn binding-operation
  "Lower a semantic API Binding (read or write) to the api-v1 operation shape,
   preserving nested `:field_mapping` branches. Output-map values are slot
   names only (owner ruling 2026-09-18, ruling #3): the physical JSON paths
   live in `:field_mapping`, which is required. Connection data is never copied."
  [binding]
  (let [implementation (:binding/implementation binding)]
    ;; `:policy` and `:status-map` ride through into the operation block:
    ;; the binding override of the four-level merge (doc 107 §107.3) and
    ;; the upstream's own failure spelling (§107.5).
    (assoc (dissoc implementation :result-root :context :transforms) :type source-type)))

(defn- finding
  "A binding finding (finding-v1): path relative to the binding, message states
   the expectation and never echoes a value."
  [code path message]
  {:code code :severity :error :path path :message message})

(defn- slot [a] (subs (str a) 1))

(def ^:private read-keys
  ;; `:policy` (doc 107 §107.3 binding override) and `:status-map` (§107.5)
  ;; are read-only keys; their contents are checked by validate-source.
  #{:method :endpoint :query_params :json_body :data_path :field_mapping :result-root :context :transforms :batch :policy :status-map})
(def ^:private write-keys #{:method :endpoint :query_params :json_body :data_path :field_mapping})
(def ^:private read-methods #{"GET" "POST"})
(def ^:private transform-kinds #{:iso-to-instant :number-to-decimal})

(defn- slot-findings
  "api-v1 (ruling #3): input-map / output-map values are slot names and must
   equal the attribute's qualified name, `ns/name`."
  [root m]
  (if-not (map? m)
    [(finding :connector.binding/shape [root] "Expected a map of attribute to slot name")]
    (for [[a n] m :when (not (and (qualified-keyword? a) (= (slot a) n)))]
      (finding :connector.binding/slot-mismatch [root a]
               "Slot name must equal the attribute's qualified name (ns/name)"))))

(defn- mapped-attributes [nested]
  (set (filter qualified-keyword? (tree-seq coll? seq nested))))

(defn- output-coverage-findings
  "Output-map keys must be exactly the attributes the implementation produces
   (`expected`) plus, for reads, input attributes passed through."
  [outputs expected pass-through]
  (concat
   (for [a expected :when (not (contains? outputs a))]
     (finding :connector.binding/output-map-missing [:binding/output-map a]
              "Every attribute the implementation produces must appear in the output map"))
   (for [a (keys outputs) :when (not (or (contains? expected a) (contains? pass-through a)))]
     (finding :connector.binding/output-map-unmapped [:binding/output-map a]
              "Nothing in :field_mapping, :context or :result-root produces this attribute"))))

(defn- json-body-findings [implementation method-ok?]
  (when (contains? implementation :json_body)
    (concat
     (when-not method-ok?
       [(finding :connector.binding/json-body-method [:binding/implementation :json_body]
                 ":json_body is only allowed with a POST read or a write method")])
     (when-not (map? (:json_body implementation))
       [(finding :connector.binding/shape [:binding/implementation :json_body] "Expected :json_body to be a map")]))))

(defn- read-findings [implementation inputs outputs]
  (let [method (:method implementation)
        nested (:field_mapping implementation)
        context (:context implementation)
        transforms (:transforms implementation)
        result-root (:result-root implementation)
        mapped (when (map? nested) (mapped-attributes nested))
        outputs? (map? outputs)]
    (concat
     (for [k (keys implementation) :when (not (read-keys k))]
       (finding :connector.binding/unknown-key [:binding/implementation k]
                "Not an api-v1 read binding implementation key"))
     (when-not (read-methods method)
       [(finding :connector.binding/method [:binding/implementation :method]
                 "A read binding's :method must be GET or POST")])
     (when-not (map? nested)
       [(finding :connector.binding/missing-key [:binding/implementation :field_mapping]
                 ":field_mapping is required: output-map values are slot names, not JSON paths")])
     (when (and (contains? implementation :batch) (not (map? (:batch implementation))))
       [(finding :connector.binding/shape [:binding/implementation :batch] "Expected :batch to be a map")])
     (json-body-findings implementation (= "POST" method))
     (when (and (some? result-root) (not (qualified-keyword? result-root)))
       [(finding :connector.binding/shape [:binding/implementation :result-root]
                 "Expected :result-root to be a qualified attribute keyword")])
     (when (some? transforms)
       (if-not (map? transforms)
         [(finding :connector.binding/shape [:binding/implementation :transforms] "Expected :transforms to be a map")]
         (for [[a kind] transforms
               :let [target-ok? (and outputs? (contains? outputs a))
                     kind-ok? (transform-kinds kind)]
               :when (not (and target-ok? kind-ok?))]
           (if target-ok?
             (finding :connector.binding/transform-kind [:binding/implementation :transforms a]
                      "Transform must be :iso-to-instant or :number-to-decimal")
             (finding :connector.binding/transform-target [:binding/implementation :transforms a]
                      "A transform must target an attribute in the output map")))))
     (when (some? context)
       (if-not (map? context)
         [(finding :connector.binding/shape [:binding/implementation :context] "Expected :context to be a map")]
         (for [[a spec] context
               :let [problem
                     (cond
                       (not (and outputs? (contains? outputs a)))
                       [:connector.binding/context-target "A context entry must target an attribute in the output map"]
                       (contains? mapped a)
                       [:connector.binding/context-target "A context entry cannot overwrite an attribute :field_mapping produces"]
                       (not (map? spec))
                       [:connector.binding/context-spec "Expected {:input attr} or {:value literal}"]
                       (= #{:input} (set (keys spec)))
                       (when-not (and (map? inputs) (contains? inputs (:input spec)))
                         [:connector.binding/context-spec "{:input attr} must name an input-map attribute"])
                       (= #{:value} (set (keys spec)))
                       (when-not (or (string? (:value spec)) (integer? (:value spec)) (boolean? (:value spec)))
                         [:connector.binding/context-spec "{:value literal} must be a string, integer or boolean"])
                       :else
                       [:connector.binding/context-spec "Expected exactly one of :input or :value"])]
               :when problem]
           (finding (first problem) [:binding/implementation :context a] (second problem)))))
     (slot-findings :binding/input-map inputs)
     (slot-findings :binding/output-map outputs)
     (when (and (map? nested) outputs? (map? inputs) (or (nil? context) (map? context)))
       (let [expected (cond-> (into mapped (keys context))
                        result-root (conj result-root))]
         (output-coverage-findings outputs expected (set (keys inputs))))))))

(defn- write-findings
  "Owner ruling 2026-09-18 (#4, option A): a write binding is an api-v1
   mutation. Read-only machinery (:batch, :result-root, :transforms, :context)
   is refused; :json_body may accompany any write method; :field_mapping is
   required; output-map is empty for a :none operation, else the slot names of
   what :field_mapping produces."
  [implementation inputs outputs]
  (let [method (:method implementation)
        nested (:field_mapping implementation)]
    (concat
     (for [k (keys implementation) :when (not (write-keys k))]
       (if (= :batch k)
         (finding :connector.binding/batch-effect [:binding/implementation :batch]
                  "A write binding cannot declare :batch (batch is a read optimisation)")
         (finding :connector.binding/unknown-key [:binding/implementation k]
                  "Not an api-v1 write binding implementation key")))
     (when-not (validate/mutation-methods method)
       [(finding :connector.binding/method [:binding/implementation :method]
                 "A write binding's :method must be POST, PUT, PATCH or DELETE")])
     (when-not (map? nested)
       [(finding :connector.binding/missing-key [:binding/implementation :field_mapping]
                 ":field_mapping is required on a write binding")])
     (json-body-findings implementation true)
     (slot-findings :binding/input-map inputs)
     (slot-findings :binding/output-map outputs)
     (when (and (map? nested) (map? outputs) (seq outputs))
       (output-coverage-findings outputs (mapped-attributes nested) #{})))))

(defn- placeholder-in?
  "Does `text` spell attribute `a` as a placeholder in any of the three
   spellings (`{a}`, `{+a}`, `{?a}`)? Position, not spelling, is what contract
   2.0 §2.2 rules on, so all three count."
  [text a]
  (let [s (slot a)]
    (some #(str/includes? (str text) %) [(str "{" s "}") (str "{+" s "}") (str "{?" s "}")])))

(defn- input-form-findings
  "Semantic contract 2.0 §2.2 for api: an optional root may only sit in
   `:query_params` -- when it is absent the whole parameter is omitted
   (impl.interp/templated-query-params); in `:endpoint` or `:json_body` an
   absent value leaves a template nothing can send. A nested root arrives as
   rows and has no placeholder shape at all. The execution layer says which
   roots those are through `:binding/input-optional` / `:binding/input-nested`."
  [binding]
  (let [implementation (:binding/implementation binding)
        endpoint (:endpoint implementation)
        body (when (contains? implementation :json_body) (pr-str (:json_body implementation)))]
    (concat
     (for [a (sort (:binding/input-optional binding))
           :when (or (placeholder-in? endpoint a) (placeholder-in? body a))]
       (finding :connector.binding/optional-placement [:binding/input-map a]
                "An optional input may only be a :query_params placeholder; in :endpoint or :json_body its absence leaves an unsendable template"))
     (for [a (sort (keys (:binding/input-nested binding)))]
       (finding :connector.binding/nested-input-unsupported [:binding/input-map a]
                "An api binding cannot take a nested input: a placeholder binds one value, not rows")))))

(defn- output-form-findings
  "Semantic contract 2.0 §6.3 for api: a union output is supported when its
   `:field_mapping` entry is a BRANCH SCOPE (a map), because only a scope
   yields a record the executor can discriminate on -- a leaf JSONPath yields
   one value and no identity. Which branch attributes the scope must produce
   is already enforced by the execution layer's output-map rule plus the
   api coverage rule, so the adapter judges the shape only. Recursion needs
   nothing of api. The execution layer says which keys are unions through
   `:binding/output-union`."
  [binding]
  (let [nested (get-in binding [:binding/implementation :field_mapping])]
    (for [a (sort (:binding/output-union binding))
          :when (and (map? nested) (not (map? (get nested a))))]
      (finding :connector.binding/union-identity-mapping [:binding/output-map a]
               "A union output needs a :field_mapping branch scope (a map) that yields each branch's identity attribute; a leaf path yields one value and no identity"))))

(defn- parameter-slot-findings
  "Semantic contract 2.0 §3.1 (owner 2026-09-18): api addresses a parameter
   by its own name in the template (`{keyword}`), so `:binding/parameter-map`
   may only spell the parameter's name back. Any other physical name would be
   a slot nothing reads."
  [binding]
  (let [pm (:binding/parameter-map binding)]
    (when (map? pm)
      (for [[p n] (sort-by (comp str key) pm)
            :when (not (and (keyword? p) (= (name p) n)))]
        (finding :connector.binding/parameter-slot [:binding/parameter-map p]
                 "An api parameter-map value must equal the parameter name: templates address parameters by name")))))

(defn validate-binding
  "Validate an api semantic Binding without IO. Branches on `:binding/effect`:
   `:read` keeps the read rules, `:write` applies the mutation rules (owner
   ruling 2026-09-18). Returns `{:findings [...]}` with one code and precise
   path per clause; all clause findings are collected. The underlying api-v1
   `validate-source` / `validate-mutation` findings are kept minus `:got`.
   A crash here is a platform defect and propagates to the caller."
  [binding]
  (let [implementation (:binding/implementation binding)
        effect (:binding/effect binding)
        inputs (:binding/input-map binding)
        outputs (:binding/output-map binding)
        structural
        (cond
          (not (#{:read :write} effect))
          [(finding :connector.binding/effect [:binding/effect] "Effect must be :read or :write")]
          (not (map? implementation))
          [(finding :connector.binding/shape [:binding/implementation] "Expected the implementation to be a map")]
          (= :read effect) (concat (read-findings implementation inputs outputs)
                                   (input-form-findings binding)
                                   (output-form-findings binding)
                                   (parameter-slot-findings binding))
          :else (concat (write-findings implementation inputs outputs)
                        (input-form-findings binding)
                        (parameter-slot-findings binding)))]
    (if (seq structural)
      {:findings (vec structural)}
      (let [config (assoc (binding-operation binding)
                          ;; Validation needs an authority; this inert value is
                          ;; never returned, opened or stored.
                          :base_url "https://fixture.invalid"
                          :input (vec (keys inputs)))
            validator (if (= :write effect) validate/validate-mutation validate/validate-source)]
        {:findings (mapv #(select-keys % [:code :severity :path :message])
                         (:findings (validator config)))}))))

(defn- blocking-findings
  "The checker protocol (owner ruling 2026-09-18) treats EVERY returned finding
   as blocking. doc 107 §107.5 makes `:isolate-statuses` a rewrite reported as
   `:info` (dialect-v1 §6: a rewrite is not a deletion), and an already-published
   graph carrying it must keep compiling -- so only `:error` findings reach the
   execution layer here. The `:info` notice is still reported by
   `spi/validate-source` on the source face, where authors read it."
  [binding]
  (update (validate-binding binding) :findings
          (fn [fs] (filterv #(= :error (:severity %)) fs))))

(defmethod spi/semantic-adapter "api" [_]
  ;; Checker protocol (owner ruling 2026-09-18): `:validate` answers the
  ;; connector's `{:findings [...]}` directly; the execution layer prefixes
  ;; each path with `[:bindings bid]`. Empty findings = valid.
  {:validate blocking-findings :lower binding-operation
   :source-collection? true :collection-limit? false})

(def ^:private nifi-keys #{:operation :defaults :data_path :field_mapping :result-root :context :transforms})

(def ^:private nifi-parameters
  {:provenance #{"uuid" "node-id" "search-field" "search-value" "start-date" "end-date" "max-results"}
   :lineage #{"uuid" "event-id" "node-id" "direction"}
   :event #{"event-id" "node-id"}
   :search-options #{"node-id"}})

(defn- nifi-anchor-findings
  "Each nifi operation needs an anchor parameter to be bound at all."
  [operation names]
  (let [ok? (case operation
              :provenance (or (names "uuid") (and (names "search-field") (names "search-value")))
              :lineage (or (names "uuid") (names "event-id"))
              :event (names "event-id")
              :search-options true
              false)]
    (when-not ok?
      [(finding :connector.binding/missing-parameter [:binding/input-map]
                "The operation's anchor parameter is not bound by any input, parameter or default")])))

(defn- nifi-name-findings
  "Per-entry checks shared by input-map and parameter-map: allowed parameter
   name for the operation, bound once."
  [root m key-ok? key-message allowed counts]
  (for [[k n] m
        :let [problem (cond (not (key-ok? k)) [:connector.binding/shape key-message]
                            (not (allowed n)) [:connector.binding/unknown-parameter "Not a parameter of this operation"]
                            (> (get counts n 0) 1) [:connector.binding/duplicate-physical-name "Each parameter may be bound once"])]
        :when problem]
    (finding (first problem) [root k] (second problem))))

(defn validate-nifi-binding
  "Validate a nifi semantic Binding: the nifi-specific clauses first (operation,
   parameter names, defaults), then the api read rules on the rewritten binding.
   Input-map values here are nifi parameter names, not slot names."
  [binding]
  (let [impl (:binding/implementation binding)
        operation (:operation impl)
        inputs (:binding/input-map binding)
        parameters (:binding/parameter-map binding)
        defaults (:defaults impl)
        allowed (get nifi-parameters operation #{})
        bound-names (concat (when (map? inputs) (vals inputs)) (when (map? parameters) (vals parameters)))
        counts (frequencies bound-names)
        names (into (set bound-names) (when (map? defaults) (map name (keys defaults))))
        structural
        (concat
         (when-not (= :read (:binding/effect binding))
           [(finding :connector.binding/effect [:binding/effect] "A nifi binding is read-only")])
         (if-not (map? impl)
           [(finding :connector.binding/shape [:binding/implementation] "Expected the implementation to be a map")]
           (concat
            (for [k (keys impl) :when (not (nifi-keys k))]
              (finding :connector.binding/unknown-key [:binding/implementation k]
                       "Not a nifi binding implementation key"))
            (when-not (contains? nifi-parameters operation)
              [(finding :connector.binding/operation [:binding/implementation :operation]
                        "Operation must be :provenance, :lineage, :event or :search-options")])
            (when (some? defaults)
              (if-not (map? defaults)
                [(finding :connector.binding/shape [:binding/implementation :defaults] "Expected :defaults to be a map")]
                (for [[k v] defaults
                      :let [problem (cond (not (keyword? k)) [:connector.binding/shape "Default keys must be keywords"]
                                          (not (or (string? v) (integer? v))) [:connector.binding/shape "Default values must be strings or integers"]
                                          (not (allowed (name k))) [:connector.binding/unknown-parameter "Not a parameter of this operation"]
                                          (contains? counts (name k)) [:connector.binding/duplicate-physical-name "A default cannot repeat a bound parameter"])]
                      :when problem]
                  (finding (first problem) [:binding/implementation :defaults k] (second problem)))))))
         (if-not (map? inputs)
           [(finding :connector.binding/shape [:binding/input-map] "Expected a map of attribute to parameter name")]
           (nifi-name-findings :binding/input-map inputs qualified-keyword?
                               "Input keys must be qualified attribute keywords" allowed counts))
         (when (some? parameters)
           (if-not (map? parameters)
             [(finding :connector.binding/shape [:binding/parameter-map] "Expected a map of parameter to parameter name")]
             (nifi-name-findings :binding/parameter-map parameters keyword?
                                 "Parameter keys must be keywords" allowed counts)))
         (when (contains? nifi-parameters operation)
           (nifi-anchor-findings operation names))
         ;; Semantic contract 2.0 §2.2: nifi parameters are determinate values
         ;; of a fixed operation, so neither form is bindable.
         (for [a (sort (:binding/input-optional binding))]
           (finding :connector.binding/optional-unsupported [:binding/input-map a]
                    "A nifi binding cannot take an optional input: every bound parameter needs a value"))
         (for [a (sort (keys (:binding/input-nested binding)))]
           (finding :connector.binding/nested-input-unsupported [:binding/input-map a]
                    "A nifi binding cannot take a nested input: a parameter binds one value, not rows"))
         ;; Semantic contract 2.0 §6.3: nifi's fixed operations answer flat
         ;; records of one shape and never a tree, so neither output form.
         (for [a (sort (:binding/output-union binding))]
           (finding :connector.binding/union-output-unsupported [:binding/output-map a]
                    "A nifi binding cannot produce a union output: its operations answer one record shape"))
         (for [a (sort (:binding/output-recursion binding))]
           (finding :connector.binding/recursion-output-unsupported [:binding/output-map a]
                    "A nifi binding cannot produce a recursive output: its operations answer one level")))]
    (if (seq structural)
      {:findings (vec structural)}
      (validate-binding
       (-> binding
           ;; Already judged above; the api rules must not report them again.
           ;; The parameter-map holds nifi PARAMETER names, judged by the
           ;; allowed list above, not api slot names (contract 2.0 §3.1).
           (dissoc :binding/input-optional :binding/input-nested
                   :binding/output-union :binding/output-recursion
                   :binding/parameter-map)
           (assoc :binding/input-map (into {} (map (fn [a] [a (slot a)])) (keys inputs)))
           (assoc :binding/implementation (assoc (dissoc impl :operation :defaults)
                                                 :method "GET" :endpoint "/provenance/search-options")))))))

(defmethod spi/semantic-adapter "nifi" [_]
  ;; Same checker protocol as "api": findings map, not a boolean.
  {:validate validate-nifi-binding :lower nifi/binding-operation
   :source-collection? true
   :collection-limit? #(= :provenance (get-in % [:binding/implementation :operation]))})

(defmethod spi/compile-source "nifi" [source _ config] (nifi/compile-read source config))
(defmethod spi/contract-version "nifi" [_] "v1")
(defmethod spi/bound-attributes "nifi" [source] (set (vals (:parameter_mapping source))))
(defmethod spi/declared-files "nifi" [source] (spi/declared-files (assoc source :type "api")))
(defmethod spi/health-check "nifi" [source _] (run/health (assoc source :type "api")))
(defmethod spi/validate-source "nifi" [source]
  (validate/validate-source (assoc (select-keys source validate/connection-keys)
                                   :type "api" :method "GET" :endpoint "/provenance/search-options")))
