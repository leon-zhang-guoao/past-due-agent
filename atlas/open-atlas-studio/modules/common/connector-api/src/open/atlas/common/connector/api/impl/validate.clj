(ns open.atlas.common.connector.api.impl.validate
  "Write-time validation (api-v1 'connection schema', 'operation block
   schema (closed)', ':auth discriminated block', 'support policy',
   'conformance'). Produces findings (finding-v1 §2 shape, codes from the
   SPI's connector.* registry); :got always passes spi/redact.

   Input convention (same as connector-sql): `source-config` is the source
   entry merged with the operation block, optionally carrying the
   resolver/mutation :input and :eql-params context."
  (:require [clojure.string :as str]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.connector.api.impl.conn :as conn]
            [open.atlas.common.connector.api.impl.interp :as interp]
            [open.atlas.common.connector.api.impl.oauth :as oauth]
            [open.atlas.common.connector.api.impl.resilience :as resilience]
            [open.atlas.common.connector.spi :as spi]))

(def methods-allowed #{"GET" "POST" "PUT" "PATCH" "DELETE"})
(def mutation-methods #{"POST" "PUT" "PATCH" "DELETE"})

(def operation-keys
  "api-v1 'operation block schema (closed)'."
  #{:type :method :endpoint :query_params :json_body :data_path
    :field_mapping :batch
    ;; doc 107 §107.5: how this upstream spells failure. Supersedes the
    ;; `:isolate-statuses` of the 2026-09-17 isolation ruling.
    :status-map})

(def connection-keys
  "api-v1 'connection schema' + dialect-v1 §3 source core keys."
  #{:type :label :description :base_url :headers :auth :tls :ssl_insecure?
    :health_endpoint :resilience :trace_headers
    ;; doc 107 §107.3: the SEMANTIC half of the policy, when a Semantic 1.4
    ;; slot or binding declares one. The capacity half stays in `:resilience`.
    :policy})

(def ^:private context-keys
  "Resolver/mutation context carried alongside the config (see ns doc) —
   not part of the connector key face."
  #{:id :input :output :returns :result_type :eql-params :limit :order
    :source-ref :params-context
    ;; Which contract version's semantics this graph opted into (api-v2:
    ;; `:timeout_ms` as an attempt TOTAL rather than an idle timeout). Set by
    ;; the runtime from the graph's declared capabilities, never by a modeller
    ;; -- which is why it is context and not a key of the api key face.
    :semantic/attempt-total-timeout?})

(def batch-keys
  "api-v1 batch block."
  #{:parent-key :child-fk :placeholder :separator :chunk-size :isolate-statuses})

(def ^:private batch-required #{:parent-key :child-fk :placeholder})

(def ^:private rejected-batch-extensions
  "Reference-implementation batch extensions api-v1 explicitly does not
   accept (support policy): contract upgrade first, code second."
  #{:body-mode :collect-key :align-key :align-keys :body-field :dedupe
    :record-template :items-path :item_defaults})

(def oauth2-keys
  #{:type :token_url :grant_type :client_id :client_secret :client_auth
    :scope :audience :username :password :refresh_token :extra_params
    :expiry_skew_s :timeout_ms :header_name :header_prefix
    ;; Token-endpoint transport options carried over from the migrated
    ;; corpus; connection-level, not credentials.
    :ssl_insecure? :tls :allow_insecure_token_url?})

(def api-key-keys #{:type :value :header_name :header_prefix})

(defn- f [code severity path message & {:as extra}]
  (merge {:code code :severity severity :path path :message message} extra))

;; ---------------------------------------------------------------------------
;; Structure
;; ---------------------------------------------------------------------------

(defn- required-findings [{:keys [base_url method endpoint]}]
  (cond-> []
    (not base_url) (conj (f :connector.source/missing-key :error [:source :base_url]
                            ":base_url is required for an api source."
                            :refs ["api-v1 连接 schema"]))
    (not method)   (conj (f :connector.source/missing-key :error [:source :method]
                            ":method is required for an api operation block."
                            :refs ["api-v1 操作块 schema"]))
    (not endpoint) (conj (f :connector.source/missing-key :error [:source :endpoint]
                            ":endpoint is required for an api operation block."
                            :refs ["api-v1 操作块 schema"]))))

(defn- method-findings [{:keys [method]}]
  (let [m (some-> method name str/upper-case)]
    (if (and m (not (methods-allowed m)))
      [(f :connector.source/invalid-shape :error [:source :method]
          ":method must be one of the api-v1 HTTP methods."
          :expected (vec (sort methods-allowed)) :got (spi/redact method)
          :refs ["api-v1 操作块 schema"])]
      [])))

(defn- unknown-key-findings [config]
  (let [allowed (into (into operation-keys connection-keys) context-keys)]
    (into []
          (keep (fn [k]
                  (cond
                    ;; support policy: connection-level response cache is
                    ;; deferred with OQ-CACHE-KEY, v1 rejects it.
                    (= k :cache_ttl_ms)
                    (f :connector.source/unknown-key :error [:source :cache_ttl_ms]
                       ":cache_ttl_ms is deferred with OQ-CACHE-KEY; api-v1 rejects it."
                       :refs ["api-v1 support policy" "OQ-CACHE-KEY"])
                    (not (allowed k))
                    (f :connector.source/unknown-key :error [:source k]
                       (str "Unknown key " k " in an api source/operation block; "
                            "api-v1 rejects unknown keys.")
                       :got (spi/redact (get config k))
                       :refs ["api-v1 support policy"]))))
          (keys config))))

(defn- unbound-authority
  "Authority-position placeholders in `:base_url` the resolver `:input` does
   not bind."
  [pos config]
  (sort (remove (set (map interp/kw->edn-attr (:input config))) (:authority pos))))

(defn- base-url-findings
  "`:base_url`'s shape, and WHERE its `{attr}` placeholders sit.

   api-v1 (owner ruling 2026-09-07, doc 106 §106.14 item 11) allows `{ns/attr}`
   and `{+ns/attr}` in the AUTHORITY position only — the host, or host:port —
   so one model can serve a dozen like-for-like instances with the instance
   chosen per query. A placeholder in the scheme or in the path is refused
   here, NAMING the position: those re-address the request itself, and the path
   case waits for the day a multi-tenant prefix is asked for (it will inherit
   the boundary table of `{+attr}`)."
  [{:keys [base_url] :as config}]
  (if-not base_url
    []
    (let [pos (interp/base-url-placeholder-positions base_url)
          templated? (boolean (seq (concat (:scheme pos) (:authority pos) (:path pos))))
          bad-position
          (fn [where names]
            (f :connector.source/placeholder-mismatch :error [:source :base_url]
               (str "A :base_url placeholder may only sit in the authority (the host, or "
                    "host:port); these sit in the " where ": "
                    (str/join ", " (map #(str "{" % "}") (sort names))) ".")
               :expected "https://{ns/attr}:9443/path"
               :got (spi/redact base_url)
               :refs ["api-v1 :base_url placeholders"]))]
      (cond-> []
        (and (not (re-find #"^https?://" (str base_url)))
             (not (interp/only-placeholder? base_url)))
        (conj (f :connector.source/invalid-shape :error [:source :base_url]
                 ":base_url must start with http:// or https://."
                 :got (spi/redact base_url) :refs ["api-v1 连接 schema"]))

        (seq (:scheme pos)) (conj (bad-position "SCHEME" (:scheme pos)))
        (seq (:path pos))   (conj (bad-position "PATH" (:path pos)))

        ;; **The binding is checked only where a resolver is in view.** This
        ;; validator also runs on a BARE source entry (declare_source proves the
        ;; connection before writing), where there is no `:input` to bind
        ;; against — refusing then would make a templated source undeclarable.
        ;; With a resolver in view, an authority placeholder no `:input` covers
        ;; is the stray {…} URL this ruling exists to prevent, refused at write
        ;; time instead of on the wire.
        (and templated? (contains? config :input)
             (seq (unbound-authority pos config)))
        (conj (f :connector.source/placeholder-mismatch :error [:source :base_url]
                 (str "Placeholders with no binding in :base_url: "
                      (str/join ", " (map #(str "{" % "}") (unbound-authority pos config)))
                      ". A templated :base_url binds from the resolver :input, so every "
                      "resolver on such a source names that attribute.")
                 :refs ["api-v1 :base_url placeholders" "api-v1 conformance"]))))))

(defn- tls-exclusive-findings [{:keys [tls ssl_insecure?]}]
  (if (and (seq tls) ssl_insecure?)
    [(f :connector.source/invalid-shape :error [:source :ssl_insecure?]
        ":tls and :ssl_insecure? are mutually exclusive; declaring both is rejected."
        :refs ["api-v1 连接 schema"])]
    []))

(defn- tls-findings [{:keys [tls]}]
  (mapv (fn [{:keys [path message]}]
          (f :connector.source/invalid-shape :error path message
             :refs ["api-v1 连接 schema"]))
        (conn/tls-config-problems tls)))

(defn- timeout-findings [config]
  (let [ms (get-in config [:resilience :timeout_ms])]
    (if (and (some? ms) (not (and (integer? ms) (pos? ms))))
      ;; principles: timeouts are enforced and have a default; a timeout
      ;; written as 0/negative/non-integer is a disabled timeout.
      [(f :connector.source/timeout-not-enforceable :error
          [:source :resilience :timeout_ms]
          "Request timeout must be a positive integer of milliseconds; it cannot be disabled."
          :expected "positive integer (ms)" :got (spi/redact ms)
          :refs ["principles 执行与连接" "D6.2"])]
      [])))

;; ---------------------------------------------------------------------------
;; :resilience / :policy key face (doc 107 §107.3, owner 2026-09-18)
;; ---------------------------------------------------------------------------

(def resilience-keys
  "The closed key face. Taken from the dialect rather than spelled again here:
   one definition is the whole point -- a second copy would let the Builder
   offer a key the reader refuses (CLAUDE.md)."
  dialect/resilience-keys)

(def policy-keys
  "The semantic half a binding may override (§107.3)."
  dialect/policy-keys)

(defn- policy-findings
  "Validate one policy-ish block. §107.3 makes it CLOSED: an unlisted key is
   refused rather than «accepted then ignored», which is the state that let a
   modeller write `:max_retries 2`, pass validation, and have nothing happen."
  [block path allowed]
  (when (some? block)
    (let [unknown (remove allowed (keys block))
          retry-on (:retry_on block)]
      (cond-> []
        (not (map? block))
        (conj (f :connector.source/invalid-shape :error path
                 "This block must be a map."
                 :expected "map" :got (spi/redact block)
                 :refs ["doc 107 §107.3"]))

        (and (map? block) (seq unknown))
        (conj (f :connector.source/unknown-key :error path
                 (str "Unknown key(s): " (str/join ", " (sort (map str unknown)))
                      ". The key face is closed (doc 107 §107.3): a key nobody "
                      "honours is a declaration that silently does nothing.")
                 :expected (str/join ", " (sort (map str allowed)))
                 :refs ["doc 107 §107.3"]))

        (and (some? retry-on)
             (or (not (set? retry-on))
                 (not (every? spi/transient-categories retry-on))))
        (conj (f :connector.source/invalid-shape :error (conj (vec path) :retry_on)
                 (str "A retry may only be declared on a transient failure "
                      "category (D6.4): retrying a deterministic one buys the "
                      "same answer twice.")
                 :expected (str "subset of " (pr-str (sort spi/transient-categories)))
                 :got (spi/redact retry-on)
                 :refs ["doc 107 §107.3" "D6.4"]))

        (and (some? (:max_retries block)) (not (nat-int? (:max_retries block))))
        (conj (f :connector.source/invalid-shape :error (conj (vec path) :max_retries)
                 "Retry budget must be a non-negative integer."
                 :expected "non-negative integer" :got (spi/redact (:max_retries block))
                 :refs ["doc 107 §107.3"]))))))

(defn- resilience-findings [config]
  (into (vec (policy-findings (:resilience config) [:source :resilience] resilience-keys))
        (policy-findings (:policy config) [:source :policy] policy-keys)))

;; ---------------------------------------------------------------------------
;; Credentials in :headers
;; ---------------------------------------------------------------------------

(def ^:private credential-header-name
  #"(?i)auth|token|secret|api[-_]?key|password")

(defn- header-findings [{:keys [headers]}]
  (into []
        (keep (fn [[hname hval]]
                (when (and (string? hval)
                           (re-find credential-header-name (str hname))
                           (not (interp/only-placeholder? hval))
                           (seq (str hval)))
                  (f :connector.source/literal-credential :error
                     [:source :headers (str hname)]
                     (str "Header " (pr-str (str hname)) " carries a credential "
                          "component; api-v1 only accepts a ${VAR} placeholder there.")
                     :expected "${VAR}" :got (spi/redact hval)
                     :refs ["api-v1 连接 schema" "D2.15"]))))
        headers))

;; ---------------------------------------------------------------------------
;; :auth — discriminated block
;; ---------------------------------------------------------------------------

(defn- literal-credential-finding [path key raw]
  (f :connector.source/literal-credential :error path
     (str key " only accepts a ${VAR} placeholder; literal credentials are "
          "rejected at write time.")
     :expected "${VAR}" :got (spi/redact raw)
     :refs ["api-v1 :auth 判别式块" "D2.15"]))

(defn- unresolved-var-finding [path key raw]
  (f :connector.source/invalid-shape :error path
     (str key " did not resolve to a value (from " (pr-str raw) ").")
     :refs ["api-v1 :auth 判别式块"]))

(defn- credential-key-findings
  "A credential key must be a ${VAR}, and that ${VAR} must resolve."
  [auth path-prefix key]
  (let [raw (get auth key)]
    (cond
      (nil? raw) []
      (not (interp/only-placeholder? raw))
      [(literal-credential-finding (conj path-prefix key) key raw)]
      (nil? (interp/resolve-value raw))
      [(unresolved-var-finding (conj path-prefix key) key raw)]
      :else [])))

(def ^:private local-hosts #{"localhost" "127.0.0.1" "::1" "[::1]"})

(defn- token-url-findings [auth r]
  (let [url (str (:token_url r))]
    (cond
      (str/blank? url)
      [(f :connector.source/missing-key :error [:source :auth :token_url]
          ":auth type \"oauth2\" requires a :token_url (the IdP token endpoint)."
          :refs ["api-v1 :auth 判别式块"])]
      :else
      (let [uri    (try (java.net.URI. url) (catch Exception _ nil))
            scheme (some-> uri .getScheme str/lower-case)
            host   (some-> uri .getHost)]
        (cond
          (or (nil? uri) (str/blank? (str scheme)))
          [(f :connector.source/invalid-shape :error [:source :auth :token_url]
              ":auth :token_url is not a valid absolute URL — a scheme like https:// is required."
              :got (spi/redact (:token_url auth)) :refs ["api-v1 :auth 判别式块"])]

          (and (= "http" scheme)
               (not (:allow_insecure_token_url? r))
               (not (contains? local-hosts host)))
          [(f :connector.source/invalid-shape :error [:source :auth :token_url]
              (str ":auth :token_url uses cleartext http:// to a non-localhost "
                   "host — client_secret would be POSTed unencrypted; use "
                   "https://, or set :allow_insecure_token_url? true.")
              :refs ["api-v1 :auth 判别式块"])]
          :else [])))))

(defn- unresolved-config-findings
  "A NON-credential `:auth` key whose `${VAR}` resolves to nothing. These are
   substituted at request time (impl.oauth/config-keys), so an unresolved one
   would otherwise reach the IdP blanked out.

   Credential keys are deliberately not here: `credential-key-findings`
   already reports both of their failure modes, and reporting a key twice
   makes the caller think there are two problems."
  [auth]
  (into []
        (keep (fn [k]
                (let [raw (get auth k)]
                  (when (seq (interp/missing-vars [raw]))
                    (unresolved-var-finding [:source :auth k] k raw)))))
        oauth/config-keys))

(defn- unresolved? [auth k]
  (seq (interp/missing-vars [(get auth k)])))

(defn- oauth2-findings [auth]
  (let [r (oauth/resolve-auth-config auth)
        grant (:grant_type r)
        unknown (remove oauth2-keys (keys auth))]
    (-> []
        (into (when (seq unknown)
                [(f :connector.source/unknown-key :error [:source :auth]
                    (str "Unknown keys in the \"oauth2\" :auth variant: "
                         (str/join ", " (sort (map str unknown))) ".")
                    :refs ["api-v1 :auth 判别式块"])]))
        (into (when (and (seq (:tls auth)) (:ssl_insecure? auth))
                ;; Same mutual exclusion as the source level — the migrated
                ;; implementation missed this guard on the auth block
                ;; (owner ruling 2026-08-28: :tls / :ssl_insecure? /
                ;; :allow_insecure_token_url? enter the contract as-is).
                [(f :connector.source/invalid-shape :error
                    [:source :auth :ssl_insecure?]
                    ":auth :tls and :ssl_insecure? are mutually exclusive; declaring both is rejected."
                    :refs ["api-v1 :auth 判别式块"])]))
        (into (mapv (fn [{:keys [path message]}]
                      (f :connector.source/invalid-shape :error
                         (into [:source :auth] (rest path)) message
                         :refs ["api-v1 :auth 判别式块"]))
                    (conn/tls-config-problems (:tls auth))))
        (into (unresolved-config-findings auth))
        ;; An unresolved key is reported ONCE, as unresolved: running its
        ;; shape checks over the blanked-out value would add a second,
        ;; misleading finding ("requires a :token_url" for a token_url that
        ;; is declared and merely unresolvable here).
        (into (when-not (unresolved? auth :token_url) (token-url-findings auth r)))
        (into (when-not (oauth/grant-types grant)
                [(f :connector.source/invalid-shape :error [:source :auth :grant_type]
                    ":auth :grant_type is outside the oauth2 grant vocabulary."
                    :expected (vec (sort oauth/grant-types)) :got (spi/redact grant)
                    :refs ["api-v1 :auth 判别式块"])]))
        (into (when (and (= grant "client_credentials")
                         (not (unresolved? auth :client_id))
                         (str/blank? (str (:client_id r))))
                [(f :connector.source/missing-key :error [:source :auth :client_id]
                    ":auth grant_type client_credentials requires a :client_id."
                    :refs ["api-v1 :auth 判别式块"])]))
        ;; The public half of each pair is a credential key too (owner ruling
        ;; 2026-08-28) — checked whatever the grant, like the secret half:
        ;; a literal is refused wherever it appears, not only where required.
        (into (credential-key-findings auth [:source :auth] :client_id))
        (into (credential-key-findings auth [:source :auth] :username))
        (into (when (= grant "client_credentials")
                (credential-key-findings auth [:source :auth] :client_secret)))
        (into (when (and (= grant "password")
                         (not (unresolved? auth :username))
                         (str/blank? (str (:username r))))
                [(f :connector.source/missing-key :error [:source :auth :username]
                    ":auth grant_type password requires a :username."
                    :refs ["api-v1 :auth 判别式块"])]))
        (into (when (= grant "password")
                (credential-key-findings auth [:source :auth] :password)))
        (into (when (= grant "refresh_token")
                (credential-key-findings auth [:source :auth] :refresh_token)))
        ;; Non-required credential keys still reject literals when present.
        (into (when (not= grant "client_credentials")
                (credential-key-findings auth [:source :auth] :client_secret)))
        (into (when (not= grant "password")
                (credential-key-findings auth [:source :auth] :password))))))

(defn- api-key-findings [auth]
  (let [unknown (remove api-key-keys (keys auth))]
    (-> []
        (into (when (seq unknown)
                [(f :connector.source/unknown-key :error [:source :auth]
                    (str "Unknown keys in the \"api_key\" :auth variant: "
                         (str/join ", " (sort (map str unknown))) ".")
                    :refs ["api-v1 :auth 判别式块"])]))
        (into (if (nil? (:value auth))
                [(f :connector.source/missing-key :error [:source :auth :value]
                    ":auth type \"api_key\" requires a :value."
                    :refs ["api-v1 :auth 判别式块"])]
                (credential-key-findings auth [:source :auth] :value))))))

(defn- auth-findings [{:keys [auth]}]
  (if-not (seq auth)
    []
    ;; :type is the MANDATORY discriminator (api-v1): without it the
    ;; validator cannot tell which keys are required right now, and a
    ;; half-configured block would pass the gate. The migrated
    ;; implementation defaulted a missing :type to oauth2 — not carried over.
    (case (:type auth)
      nil       [(f :connector.source/missing-key :error [:source :auth :type]
                    ":auth requires the :type discriminator (\"oauth2\" or \"api_key\")."
                    :expected ["oauth2" "api_key"]
                    :refs ["api-v1 :auth 判别式块"])]
      "oauth2"  (into [] (distinct) (oauth2-findings auth))
      "api_key" (api-key-findings auth)
      [(f :connector.source/invalid-shape :error [:source :auth :type]
          ":auth :type is outside the api-v1 variant vocabulary."
          :expected ["oauth2" "api_key"] :got (spi/redact (:type auth))
          :refs ["api-v1 :auth 判别式块"])])))

;; ---------------------------------------------------------------------------
;; Placeholders + batch
;; ---------------------------------------------------------------------------

(defn- known-placeholder-names
  "Names an :endpoint / :query_params placeholder may bind to: the resolver
   :input attrs (their edn string names), declared eql-param names, and the
   batch :placeholder."
  [{:keys [input eql-params batch]}]
  (into (set (concat (map interp/kw->edn-attr input)
                     (map name (keys (or eql-params {})))))
        (when-let [ph (:placeholder batch)] [ph])))

(defn- endpoint-placeholder-findings [{:keys [endpoint] :as config}]
  (when endpoint
    (let [known    (known-placeholder-names config)
          optional (interp/optional-placeholders endpoint)
          unbound  (remove known (interp/unresolved-placeholders endpoint))]
      (concat
       (when (seq optional)
         ;; Owner ruling 2026-08-28: optional {?name} placeholders are a
         ;; json_body concept — a URL path segment that 'drops out' is
         ;; meaningless, so :endpoint rejects them at write time.
         [(f :connector.source/invalid-shape :error [:source :endpoint]
             (str "Optional {?name} placeholders are not allowed in :endpoint: "
                  (str/join ", " (map #(str "{?" % "}") (sort optional))) ".")
             :refs ["api-v1 操作块 schema"])])
       (when (seq unbound)
         ;; conformance: refuse rather than send the raw template — the
         ;; write-time edition of the runtime rejection.
         [(f :connector.source/placeholder-mismatch :error [:source :endpoint]
             (str "Placeholders with no binding in :endpoint: "
                  (str/join ", " (map #(str "{" % "}") (sort unbound)))
                  ". Bind them from the resolver :input, a declared eql-param, "
                  "or the :batch :placeholder.")
             :refs ["api-v1 conformance"])])))))

;; ---------------------------------------------------------------------------
;; :status-map -- how THIS upstream spells failure (doc 107 §107.5)
;; ---------------------------------------------------------------------------

(defn- read-shape
  "Which of §107.5's read shapes this operation is.

   `:by-key` -- a single record addressed by `:input` attributes.
   `:batch`  -- an id-list batch, flat and to-one, so one input has one slot.
   `:list`   -- a collection read, or a key-less read.

   The distinction is the whole reason the table has a «read shape» column: a
   404 on a by-key read is a missing ROW, and on a list read it is a missing
   COLLECTION. Conflating them is how a caller ends up reasoning about absent
   data that was really a wrong endpoint."
  [{:keys [input result_type output batch]}]
  (let [to-one? (and (or (nil? result_type) (= "single" result_type))
                     (or (nil? output) (every? keyword? output)))]
    (cond
      (and batch to-one?) :batch
      (and (seq input) to-one?) :by-key
      :else :list)))

(def ^:private status-map-rules
  "§107.5's four-column table, as data: target → what it requires.

   `:shapes` are the read shapes on which the target is meaningful.
   `:protected?` says whether the target may be declared on a protected status
   -- only as the IDENTITY mapping, which the check below enforces."
  {:not-found      {:shapes #{:by-key :batch}}
   :input-rejected {:shapes #{:batch}}
   :unauthorized   {:shapes #{:by-key :batch :list} :protected? true}
   :rate_limited   {:shapes #{:by-key :batch :list}}
   :unavailable    {:shapes #{:by-key :batch :list}}
   :source         {:shapes #{:by-key :batch :list}}})

(defn- status-map-findings
  "Judge a declared `:status-map` against §107.5.

   Four independent rules, each with its own reason:

   1. **The target must be declarable at all.** `:timeout` / `:connection` /
      `:mapping` / `:circuit_open` / `:bulkhead_rejected` / `:cancelled` are
      judgements about OUR call, not the upstream's answer.
   2. **A protected status keeps the platform's classification.** Authorization,
      throttling and system failure must not be re-describable -- otherwise a
      source could declare `{429 :input-rejected}` and have the connector
      bisect its way through a rate limit one request at a time.
   3. **The target must suit the read shape.** `:input-rejected` needs a batch
      slot; `:not-found` needs a single row to be absent.
   4. **A list/key-less 404 may only be `:source`** (§107.4): an absent
      collection is the endpoint being wrong, and no declaration changes that."
  [config]
  (let [declared (:status-map config)]
    (when (map? declared)
      (let [shape (read-shape config)
            path  [:source :status-map]]
        (vec
         (mapcat
          (fn [[status target]]
            (let [rule (get status-map-rules target)
                  platform (resilience/platform-status-category status)]
              (cond
                ;; The key domain is 400..599. A 2xx is an ANSWER, and a source
                ;; that wants to call one a failure is describing a body, which
                ;; is `:field_mapping`'s job, not this key's.
                (not (and (integer? status) (<= 400 status 599)))
                [(f :connector.source/invalid-shape :error path
                    "A :status-map key must be an HTTP status in 400..599."
                    :expected "400..599" :got (spi/redact status)
                    :refs ["doc 107 §107.5"])]

                (nil? rule)
                [(f :connector.source/invalid-shape :error (conj path status)
                    (str "A source may not declare " (pr-str target)
                         ": it is a judgement about our own call, not about the "
                         "upstream's answer.")
                    :expected (str/join ", " (sort (map str spi/status-map-targets)))
                    :refs ["doc 107 §107.5"])]

                (and (contains? spi/protected-statuses status) (not= target platform))
                [(f :connector.source/invalid-shape :error (conj path status)
                    (str "HTTP " status " keeps the platform's classification ("
                         platform "): authorization, rate limiting and system "
                         "failure cannot be re-described by a source.")
                    :expected (str platform)
                    :refs ["doc 107 §107.5"])]

                (not (contains? (:shapes rule) shape))
                [(f :connector.source/invalid-shape :error (conj path status)
                    (str (pr-str target) " has no meaning on a " (name shape)
                         " read: " (if (= :list shape)
                                     "nothing here addresses one row"
                                     "it needs one flat record per input"))
                    :expected (str/join ", " (sort (map name (:shapes rule))))
                    :refs ["doc 107 §107.5"])]

                (and (= :list shape) (= 404 status) (not= :source target))
                [(f :connector.source/invalid-shape :error (conj path status)
                    (str "A 404 on a list or key-less read is an absent "
                         "COLLECTION, which is the endpoint being wrong; it may "
                         "only be :source.")
                    :expected ":source"
                    :refs ["doc 107 §107.4" "doc 107 §107.5"])]

                :else [])))
          (sort-by key declared)))))))

(defn- superseded-key-findings
  "`:isolate-statuses` is superseded by `:status-map` (§107.5).

   Alone it is REWRITTEN to the equivalent `{status :input-rejected …}` and
   reported as `:info` -- dialect-v1 §6's «a rewrite is not a deletion»: an
   already-published graph keeps working and its author is told what to write
   next. Co-present with `:status-map` it is REFUSED, because guessing which of
   two contradictory declarations the modeller meant is how a graph ends up
   isolating statuses nobody asked it to."
  [{:keys [batch] :as config}]
  (let [declared (:isolate-statuses batch)]
    (cond
      (and declared (contains? config :status-map))
      [(f :connector.source/invalid-shape :error [:source :batch :isolate-statuses]
          (str ":isolate-statuses and :status-map cannot both be declared; "
               ":status-map supersedes it and the two would have to be "
               "reconciled by guessing.")
          :expected "only :status-map"
          :refs ["doc 107 §107.5"])]

      (seq declared)
      [(f :connector.source/superseded-key :info [:source :batch :isolate-statuses]
          (str ":isolate-statuses is superseded by :status-map (doc 107 §107.5) "
               "and is read as " (pr-str (into (sorted-map)
                                               (map (fn [s] [s :input-rejected]))
                                               declared))
               ". The next contract version rejects it.")
          :expected ":status-map"
          :refs ["doc 107 §107.5" "dialect-v1 §6"])]

      :else [])))

(defn- batch-findings [{:keys [batch endpoint json_body method result_type output]}]
  (when batch
    (let [rejected (filter rejected-batch-extensions (keys batch))
          unknown  (remove (into batch-keys rejected-batch-extensions) (keys batch))
          missing  (remove #(contains? batch %) batch-required)
          ph       (:placeholder batch)
          cs       (:chunk-size batch)]
      (cond-> []
        ;; doc 107 §107.5: the shape rule stays (a per-row claim needs a slot to
        ;; land in), the GET restriction and the hardcoded `#{400 404}` are
        ;; gone. Which statuses may be isolated is now the source's to say, and
        ;; `status-map-findings` judges that; all this checks is that the set is
        ;; well formed before the rewrite reads it.
        (and (contains? batch :isolate-statuses)
             (not (and (set? (:isolate-statuses batch)) (seq (:isolate-statuses batch))
                       (every? #(and (integer? %) (<= 400 % 599)) (:isolate-statuses batch)))))
        (conj (f :connector.source/invalid-shape :error [:source :batch :isolate-statuses]
                 "Isolation takes a nonempty set of 400..599 status codes."
                 :refs ["doc 107 §107.5"]))
        (seq rejected)
        (conj (f :connector.source/unknown-key :error [:source :batch]
                 (str "Reference-implementation batch extensions are not part of "
                      "api-v1 (contract upgrade first): "
                      (str/join ", " (sort (map str rejected))) ".")
                 :refs ["api-v1 support policy"]))
        (seq unknown)
        (conj (f :connector.source/unknown-key :error [:source :batch]
                 (str "Unknown :batch keys: "
                      (str/join ", " (sort (map str unknown))) ".")
                 :refs ["api-v1 support policy"]))
        (seq missing)
        (conj (f :connector.source/missing-key :error [:source :batch]
                 (str ":batch requires " (str/join ", " (sort missing)) ".")
                 :refs ["api-v1 batch 块"]))
        (and ph (not (or (str/includes? (str endpoint) (str "{" ph "}"))
                         (str/includes? (pr-str json_body) (str "{" ph "}")))))
        (conj (f :connector.source/placeholder-mismatch :error
                 [:source :batch :placeholder]
                 (str ":batch :placeholder " (pr-str ph)
                      " occurs in neither :endpoint nor :json_body.")
                 :refs ["api-v1 batch 块"]))
        ;; A compound key pairs the i-th parent attribute with the i-th child
        ;; path (owner 2026-09-07, doc 106 §106.14 item 14); unequal counts
        ;; would drop a column from the bucket key and read as «no children».
        (not= (count (spi/batch-keys (:parent-key batch)))
              (count (spi/batch-keys (:child-fk batch))))
        (conj (f :connector.source/invalid-shape :error [:source :batch]
                 (str ":batch :parent-key and :child-fk must name the same number of "
                      "keys; a compound key pairs them position by position.")
                 :expected (str (count (spi/batch-keys (:parent-key batch)))
                                " child paths")
                 :got (count (spi/batch-keys (:child-fk batch)))
                 :refs ["api-v1 batch 块" "doc 106 §106.14 item 14"]))
        (and (some? cs) (not (and (integer? cs) (pos? cs))))
        (conj (f :connector.source/invalid-shape :error [:source :batch :chunk-size]
                 ":batch :chunk-size must be a positive integer."
                 :got (spi/redact cs) :refs ["api-v1 batch 块"]))))))

(def ^:private from-key
  "api-v1 «记录内集合»: the key a branch names its own array under (doc 106
   §106.14 item 15). Spelled here rather than required from impl.rows so the
   validator carries no dependency on the extractor."
  :_from)

(defn- branch-mapping-findings
  "`:field_mapping` branches that carry their OWN rows (§106.14 item 15).

   `{B/as {:_from \"$.processors[*]\" A/id \"$.id\"}}` says «B's processors are
   in B's own record». Three things are refused here rather than at query time,
   where each of them answers an empty collection with no reason given."
  [path branch]
  (let [from   (get branch from-key)
        leaves (dissoc branch from-key)
        prefix (str/replace (str from) #"\[\*\]$" "")]
    (cond-> []
      (not (and (string? from) (str/ends-with? (str from) "[*]")))
      (conj (f :connector.source/invalid-shape :error (conj path from-key)
               (str ":_from names the ARRAY a branch's rows come from, so it ends in [*].")
               :expected "$.processors[*]" :got (spi/redact from)
               :refs ["api-v1 记录内集合"]))

      (empty? leaves)
      (conj (f :connector.source/missing-key :error path
               (str "A branch that names an array must also say which fields one of its "
                    "elements carries; :_from alone maps nothing.")
               :refs ["api-v1 记录内集合"]))

      :always
      (into (for [[a v] leaves
                  :when (and (string? v)
                             (seq prefix)
                             (str/starts-with? (str v) prefix))]
              ;; A leaf written from the response ROOT would re-enter the array
              ;; once per element and answer every element's value in every row.
              (f :connector.source/invalid-shape :error (conj path a)
                 (str "A leaf under a branch that names an array is read against ONE element, "
                      "so its path is relative to that element, not to the response.")
                 :expected "$.id" :got (spi/redact v)
                 :refs ["api-v1 记录内集合"])))

      :always
      (into (mapcat (fn [[a v]]
                      (when (and (map? v) (contains? v from-key))
                        (branch-mapping-findings (conj path a) v)))
                    leaves)))))

(defn- field-mapping-findings
  "api-v1 `:field_mapping` shapes that only the whole block can judge."
  [{:keys [field_mapping data_path]}]
  (let [branches (into {} (filter (fn [[_ v]] (and (map? v) (contains? v from-key)))
                                  (or field_mapping {})))]
    (into (vec
           (when (and (seq branches) data_path (str/includes? (str data_path) "[*]"))
             ;; Both may be declared — a `:data_path` that locates ONE record
             ;; and branches read out of that record is the ordinary «record
             ;; plus its collections» shape. What cannot be read two ways is a
             ;; `:data_path` that locates a LIST beside a branch written from
             ;; `$`: the branch's array is then either per-row or top-level,
             ;; and nothing in the block says which.
             [(f :connector.source/invalid-shape :error [:source :data_path]
                 (str "A :data_path that locates a LIST cannot be combined with a branch that "
                      "names its own array: " (str/join ", " (map (comp str symbol key) branches))
                      " would be read either once against the response or once per row, and the "
                      "block does not say which. Locate one record, or drop the :data_path.")
                 :got (spi/redact data_path)
                 :refs ["api-v1 记录内集合"])]))
          (mapcat (fn [[a v]] (branch-mapping-findings [:source :field_mapping a] v))
                  branches))))

(defn- eql-param-findings [{:keys [eql-params]}]
  (into []
        (keep (fn [[pname spec]]
                (when-let [loc (:param_location spec)]
                  (when-not (contains? #{"query" "body"} loc)
                    ;; "root" is a rejected reference-implementation
                    ;; extension (support policy), same as any other value
                    ;; outside the vocabulary.
                    (f :connector.source/invalid-shape :error
                       [:eql-params pname :param_location]
                       ":param_location is outside the api-v1 vocabulary."
                       :expected ["query" "body"] :got (spi/redact loc)
                       :refs ["api-v1 eql-param 扩展键" "api-v1 support policy"])))))
        (or eql-params {})))

;; ---------------------------------------------------------------------------
;; Entry points
;; ---------------------------------------------------------------------------

(defn- sorted [findings]
  {:findings (vec (sort-by (juxt (comp pr-str :path) :code)
                           (remove nil? findings)))})

(defn validate-source [config]
  (sorted (concat (required-findings config)
                  (method-findings config)
                  (unknown-key-findings config)
                  (base-url-findings config)
                  (tls-exclusive-findings config)
                  (tls-findings config)
                  (timeout-findings config)
                  (resilience-findings config)
                  (status-map-findings config)
                  (superseded-key-findings config)
                  (header-findings config)
                  (auth-findings config)
                  (endpoint-placeholder-findings config)
                  (batch-findings config)
                  (field-mapping-findings config)
                  (eql-param-findings config))))

(defn validate-mutation [config]
  (let [{:keys [findings]} (validate-source config)
        m (some-> (:method config) name str/upper-case)
        extra
        (cond-> []
          ;; dialect-v1 §3: a mutation operation block must not declare
          ;; :batch — batch is a read optimization.
          (:batch config)
          (conj (f :connector.source/unknown-key :error [:source :batch]
                   "A mutation operation block must not declare :batch."
                   :refs ["dialect-v1 §3 mutation 条目"]))
          (and m (methods-allowed m) (not (mutation-methods m)))
          (conj (f :connector.source/invalid-shape :error [:source :method]
                   "A mutation :method is limited to POST/PUT/PATCH/DELETE."
                   :expected (vec (sort mutation-methods)) :got (spi/redact m)
                   :refs ["api-v1 能力"]))
          (not (:field_mapping config))
          (conj (f :connector.source/missing-key :error [:source :field_mapping]
                   ":field_mapping is required for an api mutation."
                   :refs ["api-v1 能力"])))]
    (sorted (concat (remove #(= [:source :batch] (:path %)) findings) extra))))
