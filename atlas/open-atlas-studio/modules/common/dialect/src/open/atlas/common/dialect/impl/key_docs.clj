(ns open.atlas.common.dialect.impl.key-docs
  "One line of meaning and one literal example per declared key, keyed by
   [type key].

   Why here and not in the browser (owner ruling 2026-09-07, doc 84 §8.4.25):
   the Studio editor is EDN-only (2026-09-05), so what a type may carry can be
   said only inside the text — and a key list written in cljs would be a second
   statement of the closed key set that is free to drift from `impl/schema.clj`
   (D1.8). The key face is derived from the schema; this map supplies the prose
   the schema does not carry, and `key_docs_test` fails the build when the two
   disagree in either direction.

   An example is LITERAL — the exact bytes an author can type — for the same
   reason `shape-examples` gives one on a refusal (OQ-MCP-WRITE-ERGONOMICS
   item 1): what a type name cannot convey is the spelling. Sub-blocks
   (`:source` per connector type, `:batch`, `:eql-params`, `:values`) therefore
   show a nested literal, not the word \"map\".

   Nothing here is a default: these lines are rendered as EDN comments above
   the entry and the reader drops them, so no key of them reaches the pool
   (D2.12 canonical form — an absent key stays absent).")

(def summaries
  "The one-line headline for a type, shown above its key lines."
  {"attribute" "one column of an entity, or a branch to another entity"
   "link"      "how two entities join, stated once for the whole model"
   "metric"    "a named aggregate over one entity"
   "mutation"  "a write the graph offers, and what it answers with"
   "namespace" "what kind of thing this is and how people call it"
   "resolver"  "how rows of an entity are fetched, and which attributes come back"
   "source"    "one connection this instance may dial"
   "term"      "a shared value vocabulary attributes bind to"
   "unit"      "a unit of measure attributes bind to"})

(def docs
  "[type key] → {:meaning one line, :example the literal bytes}."
  {;; --- namespace ---------------------------------------------------------
   ["namespace" :kind]        {:meaning "what kind of entity this is"
                               :example ":kind :master"}
   ["namespace" :label]       {:meaning "human name shown in catalogs"
                               :example ":label \"Customer\""}
   ["namespace" :description] {:meaning "one or two sentences for agents and people"
                               :example ":description \"A person or company we sell to.\""}
   ["namespace" :synonyms]    {:meaning "other names people use for it"
                               :example ":synonyms [\"client\" \"客户\"]"}
   ["namespace" :notes]       {:meaning "modelling notes, not shown to consumers"
                               :example ":notes \"one row per party, not per contract\""}

   ;; --- attribute ---------------------------------------------------------
   ["attribute" :type]         {:meaning "the value this leaf carries"
                                :example ":type :string"}
   ["attribute" :target]       {:meaning "the entity a join branch reaches"
                                :example ":target \"cis.order\""}
   ["attribute" :targets]      {:meaning "the entities a union join branch may reach; one record is exactly one of them (Semantic 2.0 §6.1; written by the 2.0 compiler only)"
                                :example ":targets #{\"device\" \"person\"}"}
   ["attribute" :cardinality]  {:meaning "how many rows of the target one row reaches"
                               :example ":cardinality :one-to-many"}
   ["attribute" :format]       {:meaning "how the value reads, beyond its type"
                                :example ":format :currency"}
   ["attribute" :identity?]    {:meaning "true when this leaf identifies its entity"
                                :example ":identity? true"}
   ["attribute" :listed?]      {:meaning "only false is written — hide it from listings"
                                :example ":listed? false"}
   ["attribute" :term]         {:meaning "id of the term whose vocabulary this leaf uses"
                                :example ":term \"order_status\""}
   ["attribute" :term-context] {:meaning "why this leaf binds that term"
                                :example ":term-context \"status of the order, not of payment\""}
   ["attribute" :unit]         {:meaning "id of the unit the value is measured in"
                                :example ":unit \"cny\""}
   ["attribute" :values]       {:meaning "the code list, code → what it means"
                                :example ":values {\"A\" {:label \"Active\"}}"}
   ["attribute" :label]        {:meaning "human name shown in catalogs"
                                :example ":label \"Total cost\""}
   ["attribute" :description]  {:meaning "one or two sentences for agents and people"
                                :example ":description \"Sum of the order lines, tax included.\""}
   ["attribute" :synonyms]     {:meaning "other names people use for it"
                                :example ":synonyms [\"amount\" \"金额\"]"}
   ["attribute" :deprecated?]  {:meaning "true when it is on the way out"
                                :example ":deprecated? true"}
   ["attribute" :replaced-by]  {:meaning "the attribute that took its place"
                                :example ":replaced-by :cis.order/total_amount"}

   ;; --- link --------------------------------------------------------------
   ["link" :from]        {:meaning "the attribute this side joins on"
                          :example ":from :cis.order/customer_no"}
   ["link" :to]          {:meaning "the attribute the other side joins on"
                          :example ":to :cis.party/customer_no"}
   ["link" :direction]   {:meaning "only :one-way is written — :bi is the default"
                          :example ":direction :one-way"}
   ["link" :cardinality] {:meaning "how many rows of :to one row of :from reaches"
                          :example ":cardinality :one-to-many"}
   ["link" :condition]   {:meaning "an extra restriction the join must satisfy"
                          :example ":condition \"status = 'A'\""}
   ["link" :description] {:meaning "one or two sentences for agents and people"
                          :example ":description \"An order belongs to one customer.\""}

   ;; --- metric ------------------------------------------------------------
   ["metric" :entity]      {:meaning "the namespace the metric aggregates"
                            :example ":entity \"cis.order\""}
   ["metric" :expression]  {:meaning "the aggregate, under the contract that runs it"
                            :example ":expression {:contract :duckdb-spill/v1 :text \"sum(total_cost)\"}"}
   ["metric" :dimensions]  {:meaning "attributes it may be broken down by"
                            :example ":dimensions [:cis.order/status]"}
   ["metric" :grain]       {:meaning "the time bucket it is reported at"
                            :example ":grain :month"}
   ["metric" :label]       {:meaning "human name shown in catalogs"
                            :example ":label \"Order revenue\""}
   ["metric" :description] {:meaning "one or two sentences for agents and people"
                            :example ":description \"Revenue booked per month.\""}
   ["metric" :synonyms]    {:meaning "other names people use for it"
                            :example ":synonyms [\"turnover\" \"营收\"]"}

   ;; --- resolver ----------------------------------------------------------
   ["resolver" :id]           {:meaning "the resolver's own id"
                               :example ":id \"cis.order/by-customer\""}
   ["resolver" :input]        {:meaning "the attributes a caller must already hold"
                               :example ":input [:cis.party/customer_no]"}
   ["resolver" :output]       {:meaning "the attribute tree it produces; a branch is a join attribute"
                               :example ":output [:cis.order/order_no {:cis.order/lines [:cis.line/qty]}]"}
   ["resolver" :result_type]  {:meaning "one row or many"
                               :example ":result_type \"list\""}
   ["resolver" :source]       {:meaning "the operation block, shaped by its connector type"
                               :example ":source {:type \"sql\" :query \"SELECT …\" :params {:customer_no :cis.party/customer_no} :field_mapping {:cis.order/order_no \"order_no\"} :batch {:parent-key :cis.party/customer_no :child-fk \"customer_no\" :placeholder \":customer_no\"}}"}
   ["resolver" :source-ref]   {:meaning "id of the source it dials (derived has none)"
                               :example ":source-ref \"demo-db\""}
   ["resolver" :curated?]     {:meaning "only true is written — you sign for a list with no :input (D2.13)"
                               :example ":curated? true"}
   ["resolver" :eql-params]   {:meaning "call parameters the query face accepts"
                               :example ":eql-params {:status {:attribute :cis.order/status :op :eq}}"}
   ["resolver" :limit]        {:meaning "page size a list answers with"
                               :example ":limit {:default 50 :max 500}"}
   ["resolver" :order]        {:meaning "which orderings a caller may ask for"
                               :example ":order {:allowed [:cis.order/order_no] :default [:cis.order/order_no :desc]}"}
   ["resolver" :cache]        {:meaning "how long a result may be reused"
                               :example ":cache {:ttl-ms 60000}"}
   ["resolver" :row-security] {:meaning "the attribute rows are filtered by (D5.14)"
                               :example ":row-security {:attribute :cis.party/tenant_id}"}
   ["resolver" :input-optional] {:meaning "which :input roots may be absent (Semantic 2.0 §2.4; written by the 2.0 compiler only)"
                                 :example ":input-optional #{:cis.party/email}"}
   ["resolver" :input-nested] {:meaning "which :input roots arrive as evaluated subtrees (Semantic 2.0 §2.4; written by the 2.0 compiler only)"
                               :example ":input-nested {:cis.order/items [:cis.item/qty]}"}
   ["resolver" :priority]     {:meaning "rank among several resolvers producing the same attribute; higher wins, absent is 0 (Semantic 2.0 §4.4; written by the 2.0 compiler only)"
                               :example ":priority 10"}
   ["resolver" :label]        {:meaning "human name shown in catalogs"
                               :example ":label \"Orders of a customer\""}
   ["resolver" :description]  {:meaning "one or two sentences for agents and people"
                               :example ":description \"Every order placed by one customer.\""}

   ;; --- mutation ----------------------------------------------------------
   ["mutation" :id]           {:meaning "the mutation's own id"
                               :example ":id \"cis.order/place\""}
   ["mutation" :result_type]  {:meaning "what it answers with; \"none\" means no :returns"
                               :example ":result_type \"single\""}
   ["mutation" :source]       {:meaning "the operation block; sql or api only, and never :batch"
                               :example ":source {:type \"sql\" :query \"INSERT INTO orders …\" :params {:customer_no :cis.party/customer_no}}"}
   ["mutation" :source-ref]   {:meaning "id of the source it dials"
                               :example ":source-ref \"demo-db\""}
   ["mutation" :params]       {:meaning "what a caller passes, and which are required"
                               :example ":params {:cis.order/customer_no {:required? true}}"}
   ["mutation" :returns]      {:meaning "the attribute tree it answers with"
                               :example ":returns [:cis.order/order_no]"}
   ["mutation" :idempotency]  {:meaning "how a repeat of the same call is recognised"
                               :example ":idempotency {:key :cis.order/order_no}"}
   ["mutation" :row-security] {:meaning "the attribute rows are filtered by (D5.14)"
                               :example ":row-security {:attribute :cis.party/tenant_id}"}
   ["mutation" :input-optional] {:meaning "which :params roots may be absent (Semantic 2.0 §2.4; written by the 2.0 compiler only)"
                                 :example ":input-optional #{:cis.party/email}"}
   ["mutation" :input-nested] {:meaning "which :params roots arrive as evaluated subtrees (Semantic 2.0 §2.4; written by the 2.0 compiler only)"
                               :example ":input-nested {:cis.order/items [:cis.item/qty]}"}
   ["mutation" :label]        {:meaning "human name shown in catalogs"
                               :example ":label \"Place an order\""}
   ["mutation" :description]  {:meaning "one or two sentences for agents and people"
                               :example ":description \"Creates one order for a customer.\""}

   ;; --- source ------------------------------------------------------------
   ["source" :type]            {:meaning "which connector contract this entry speaks"
                                :example ":type \"sql\""}
   ["source" :label]           {:meaning "human name shown in catalogs"
                                :example ":label \"Demo database\""}
   ["source" :description]     {:meaning "one or two sentences for agents and people"
                                :example ":description \"The read replica behind the demo model.\""}
   ["source" :jdbc_url]        {:meaning "the JDBC url"
                                :example ":jdbc_url \"jdbc:postgresql://db:5432/cis\""}
   ["source" :username]        {:meaning "the login name, or a ${VAR} reference"
                                :example ":username \"${CIS_DB_USER}\""}
   ["source" :password]        {:meaning "never a literal here — a ${VAR} reference"
                                :example ":password \"${CIS_DB_PASSWORD}\""}
   ["source" :max_pool_size]   {:meaning "most connections held open"
                                :example ":max_pool_size 8"}
   ["source" :min_idle]        {:meaning "connections kept idle"
                                :example ":min_idle 1"}
   ["source" :resilience]      {:meaning "retry and timeout settings (D6.2; closed key face, doc 107 §107.3)"
                                :example ":resilience {:timeout_ms 5000 :max_retries 2 :retry_on #{:rate_limited}}"}
   ["source" :semantic/attempt-total-timeout?]
   {:meaning "runtime-set: whether :timeout_ms is an attempt total (api-v2) or an idle timeout (api-v1)"
    :example ":semantic/attempt-total-timeout? true"}
   ["source" :base_url]        {:meaning "the service root"
                                :example ":base_url \"https://nifi.example:9443/nifi-api\""}
   ["source" :headers]         {:meaning "headers sent on every call"
                                :example ":headers {\"Accept\" \"application/json\"}"}
   ["source" :auth]            {:meaning "the credential block; values are ${VAR} references"
                                :example ":auth {:type \"api_key\" :value \"${NIFI_TOKEN}\" :header_name \"Authorization\" :header_prefix \"Bearer \"}"}
   ["source" :tls]             {:meaning "the client certificate and trust material"
                                :example ":tls {:keystore \"/etc/oma/client.p12\" :keystore_type \"PKCS12\" :keystore_pass \"${CLIENT_P12_PASS}\"}"}
   ["source" :ssl_insecure?]   {:meaning "skip certificate checks (local only)"
                                :example ":ssl_insecure? true"}
   ["source" :health_endpoint] {:meaning "the path a health check calls"
                                :example ":health_endpoint \"/system-diagnostics\""}
   ["source" :trace_headers]   {:meaning "which headers may carry correlation ids"
                                :example ":trace_headers {:request-id \"X-Request-Id\"}"}

   ;; --- term --------------------------------------------------------------
   ["term" :label]       {:meaning "human name shown in catalogs"
                          :example ":label \"Order status\""}
   ["term" :description] {:meaning "one or two sentences for agents and people"
                          :example ":description \"Where an order sits in its lifecycle.\""}
   ["term" :format]      {:meaning "how the values read"
                          :example ":format :code"}
   ["term" :values]      {:meaning "the code list, code → what it means"
                          :example ":values {\"A\" {:label \"Active\" :description \"Open and payable.\"}}"}
   ["term" :synonyms]    {:meaning "other names people use for it"
                          :example ":synonyms [\"state\" \"状态\"]"}
   ["term" :deprecated?] {:meaning "true when it is on the way out"
                          :example ":deprecated? true"}
   ["term" :replaced-by] {:meaning "id of the term that took its place"
                          :example ":replaced-by \"order_state\""}

   ;; --- unit --------------------------------------------------------------
   ["unit" :label]       {:meaning "human name shown in catalogs"
                          :example ":label \"CNY\""}
   ["unit" :description] {:meaning "one or two sentences for agents and people"
                          :example ":description \"Chinese yuan, two decimal places.\""}})
