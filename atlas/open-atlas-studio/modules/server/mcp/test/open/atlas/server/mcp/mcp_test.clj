(ns open.atlas.server.mcp.mcp-test
  "The shell's own tests: protocol framing, auth, and the fact that a refusal
   travels as an answer rather than a fault.

   Deliberately **not** end-to-end: what a real graph returns is settled at the
   assembly point, and the seam injected here (`:instance`) is only the step
   between the shell and the judgement — the judgement itself still has exactly
   one implementation. Same discipline as `server/data`'s shell tests."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.server.mcp :as mcp]))

;; ---------------------------------------------------------------------------
;; A fake instance: token/verify and answer are the only two doors the shell
;; touches, and both are function positions, so a map stands in for a runtime.
;; ---------------------------------------------------------------------------

(def ^:private good-token "oat_v1.good")

(defn- deps
  ([] (deps {}))
  ([over]
   (merge {:instance {:token {::verify (fn [c _]
                                         (if (= good-token c)
                                           {:token-id "tok-1"
                                            :authorization {:graph-id "g" :version 1}}
                                           {:findings [{:code :token.verify/unknown
                                                        :severity :error}]}))}}
           :now (constantly "2026-08-28T00:00:00Z")
           :sse-writer identity}
          over)))

(defn- post
  "One POST /mcp with a JSON-RPC body."
  [d body & {:keys [auth uri] :or {auth good-token uri "/mcp"}}]
  ((mcp/routes d)
   {:request-method :post :uri uri
    :headers (cond-> {"host" "127.0.0.1:9632"}
               auth (assoc "authorization" (str "Bearer " auth)))
    :body (java.io.ByteArrayInputStream. (.getBytes (json/generate-string body) "UTF-8"))}))

(defn- rpc [method & [params id]]
  (cond-> {:jsonrpc "2.0" :method method :id (or id 1)}
    params (assoc :params params)))

(defn- body-of [response] (json/parse-string (:body response) true))

(def ^:private graph-uuid "12345678-1234-4234-8234-123456789abc")
(def ^:private graph-url (str "/mcp/" graph-uuid))

(deftest graph-addresses-check-the-token-before-dispatch
  (let [calls (atom 0)
        d (deps {:rate-limit? (fn [_] (swap! calls inc) {:allowed? true})})]
    (with-redefs [open.atlas.runtime.token/verify
                  (fn [_ c _]
                    (if (= good-token c)
                      {:token-id "tok-1" :authorization {:graph-id "demo/app" :version :latest}}
                      {:findings [{:code :token.authz/unknown}]}))
                  open.atlas.runtime.graph/graph-uuid
                  (fn [_ name version]
                    (is (= ["demo/app" :latest] [name version]))
                    graph-uuid)]
      (doseq [uri [graph-url (str graph-url "/") (str/upper-case graph-url)]]
        ;; Only UUID case is insensitive; the route prefix stays lowercase.
        (let [uri (str/replace uri "/MCP/" "/mcp/")]
          (is (= 200 (:status (post d (rpc "initialize") :uri uri))))
          (is (= (get-in (body-of (post d (rpc "tools/list"))) [:result :tools])
                 (get-in (body-of (post d (rpc "tools/list") :uri uri)) [:result :tools])))))
      (reset! calls 0)
      (doseq [method ["initialize" "tools/list" "tools/call"]]
        (is (= 403 (:status (post d (rpc method) :uri "/mcp/00000000-0000-4000-8000-000000000000")))))
      (is (zero? @calls) "A mismatched UUID never reaches dispatch")
      (doseq [auth [nil "oat_v1.forged"]]
        (is (= 401 (:status (post d (rpc "initialize") :uri graph-url :auth auth)))))
      (doseq [uri ["/mcp/demo/app" "/mcp/%ZZ" "/mcp/1234"]]
        (is (nil? (post d (rpc "initialize") :uri uri)) "Names are not UUIDs"))
      (is (= 405 (:status ((mcp/routes d) {:request-method :get :uri graph-url}))))
      (is (nil? ((mcp/routes d) {:request-method :get :uri "/mcp/messages"})))
      (is (nil? ((mcp/routes d) {:request-method :post :uri "/mcp/sse"}))))))

(deftest graph-addresses-preserve-origin-validation
  (with-redefs [open.atlas.runtime.token/verify
                (fn [store c opts] ((::verify store) c opts))
                open.atlas.runtime.graph/graph-uuid (fn [& _] graph-uuid)]
    (is (= 403 (:status ((mcp/routes (deps))
                         {:request-method :post :uri graph-url
                          :headers {"host" "127.0.0.1:9632" "origin" "https://evil.example"
                                    "authorization" (str "Bearer " good-token)}}))))))

(deftest missing-verified-identity-fails-closed
  (with-redefs [open.atlas.runtime.token/verify
                (fn [store c opts] ((::verify store) c opts))
                open.atlas.runtime.graph/graph-uuid (constantly nil)]
    (is (= 403 (:status (post (deps) (rpc "initialize") :uri graph-url))))))

;; ---------------------------------------------------------------------------

(deftest the-data-plane-always-needs-a-token
  "D5.7 has no exception for MCP: an agent is a data-plane caller like any
   other. Pinned here because a discovery surface is exactly where a
   convenience hole gets cut — 'just let it list the entities'."
  (with-redefs [open.atlas.runtime.token/verify
                (fn [store c opts] ((::verify store) c opts))]
    (testing "no bearer at all"
      (is (= 401 (:status (post (deps) (rpc "initialize") :auth nil)))))
    (testing "a bearer this runtime does not know"
      (is (= 401 (:status (post (deps) (rpc "initialize") :auth "oat_v1.forged")))))))

(deftest initialize-negotiates-the-protocol-version
  "The spec's rule: echo a version we speak, otherwise answer with our
   preferred one — never fail the handshake over it."
  (with-redefs [open.atlas.runtime.token/verify
                (fn [store c opts] ((::verify store) c opts))]
    (testing "a version we speak comes back unchanged"
      (is (= "2024-11-05"
             (get-in (body-of (post (deps) (rpc "initialize" {:protocolVersion "2024-11-05"})))
                     [:result :protocolVersion]))))
    (testing "one we do not speak gets our preferred version, not an error"
      (let [r (body-of (post (deps) (rpc "initialize" {:protocolVersion "1999-01-01"})))]
        (is (= "2025-03-26" (get-in r [:result :protocolVersion])))
        (is (nil? (:error r)))))
    (testing "instructions ride along — the agent plans against them"
      (let [r (body-of (post (deps) (rpc "initialize")))]
        (is (str/includes? (get-in r [:result :instructions]) "THE ONE RULE"))))))

(deftest the-announced-tools-are-exactly-these
  "Pinned as a SET, not a count: a tool appearing or vanishing changes what an
   agent will attempt, and that is an interface change — it should have to be
   written down here, not discovered in a transcript."
  (with-redefs [open.atlas.runtime.token/verify
                (fn [store c opts] ((::verify store) c opts))]
    (let [names (set (map :name (get-in (body-of (post (deps) (rpc "tools/list")))
                                        [:result :tools])))]
      (is (= #{"list_entities" "list_attributes" "search_attributes"
               "execute_eql" "analyze_result" "compute_metric"
               "list_mutations" "execute_mutation"}
             names))
      (is (contains? names "execute_mutation")
          "writes ride the same door as reads (D5.13): a caller may run exactly
           the mutations their GRAPH holds, so the tool needs no second
           permission and a read-only consumer simply gets a graph with no
           mutations in it"))))

(deftest a-refusal-is-an-answer-not-a-fault
  "The distinction the whole surface turns on. A wrong attribute name, an
   unauthorised graph, an unreadable query — all are things the agent must
   READ and act on, so they come back as an MCP tool result with `isError`
   and the structured finding inside. A thrown exception is a different thing
   and stays a different thing: that one is our bug, not the agent's."
  (with-redefs [open.atlas.runtime.token/verify
                (fn [store c opts] ((::verify store) c opts))]
    (let [r (body-of (post (deps) (rpc "tools/call" {:name "execute_eql"
                                                     :arguments {:query "[[[not edn"}})))]
      (is (nil? (:error r)) "a refusal is NOT a JSON-RPC protocol error")
      (is (true? (get-in r [:result :isError])))
      (is (str/includes? (get-in r [:result :content 0 :text]) "not readable EDN")
          "and it says what was wrong, so the next call can be right"))))

(deftest an-unknown-method-is-a-protocol-error
  (with-redefs [open.atlas.runtime.token/verify
                (fn [store c opts] ((::verify store) c opts))]
    (is (= -32601 (get-in (body-of (post (deps) (rpc "tools/nope"))) [:error :code])))))

(deftest a-notification-gets-no-body
  "`notifications/initialized` has no id and expects no reply — answering it
   with a JSON-RPC response is a protocol violation some clients hard-fail on."
  (with-redefs [open.atlas.runtime.token/verify
                (fn [store c opts] ((::verify store) c opts))]
    (is (= 202 (:status (post (deps) {:jsonrpc "2.0" :method "notifications/initialized"}))))))

(deftest a-batch-is-dispatched-elementwise
  "JSON-RPC 2.0 batching: auth is checked once, each element dispatched on the
   same path, notifications contribute no response."
  (with-redefs [open.atlas.runtime.token/verify
                (fn [store c opts] ((::verify store) c opts))]
    (let [r (post (deps) [(rpc "ping" nil 1)
                          {:jsonrpc "2.0" :method "notifications/initialized"}
                          (rpc "ping" nil 2)])
          parsed (json/parse-string (:body r) true)]
      (is (= 200 (:status r)))
      (is (= [1 2] (mapv :id parsed)) "two replies, the notification silent"))
    (testing "an empty batch is itself an Invalid Request"
      (is (= -32600 (get-in (body-of (post (deps) [])) [:error :code]))))))

(deftest a-cross-origin-browser-request-is-refused
  "The spec's DNS-rebinding guard. Non-browser clients send no Origin and pass
   untouched; a page on another origin does not."
  (let [r ((mcp/routes (deps))
           {:request-method :post :uri "/mcp"
            :headers {"host" "127.0.0.1:9632"
                      "origin" "https://evil.example"
                      "authorization" (str "Bearer " good-token)}
            :body (java.io.ByteArrayInputStream. (.getBytes "{}" "UTF-8"))})]
    (is (= 403 (:status r)))))

(deftest get-mcp-says-post-instead
  "This server opens no server-initiated stream, so the Streamable HTTP spec
   wants a 405 naming the method that does work."
  (let [r ((mcp/routes (deps)) {:request-method :get :uri "/mcp" :headers {}})]
    (is (= 405 (:status r)))
    (is (= "POST" (get-in r [:headers "Allow"])))))

(deftest every-attribute-named-in-a-tool-description-is-real
  "**A wrong worked example is worse than none.** The descriptions teach query
   shape by example, and an agent copies what it is shown — so a name that
   drifted out of the model becomes a refusal the agent cannot diagnose,
   arriving from the one source it has most reason to trust.

   This test walks every `:cis.…/…` in every description and checks it against
   the demo model on the classpath. It caught two on the day it was written:
   a join key invented wholesale, and one attached to the wrong entity.

   Since D2.29 the check reads ONE registry: a branch key and a leaf key are
   both entries of `:attributes`, so there is no second place a demonstrated
   name could legitimately come from."
  (let [model (edn/read-string (slurp (io/resource "model.edn")))
        known (set (map #(subs (str %) 1) (keys (:attributes model))))
        text  (str/join "\n" (map :description mcp/tool-definitions))
        named (distinct (re-seq #":cis\.[A-Za-z0-9_.]+/[A-Za-z0-9_]+" text))
        bad   (remove #(contains? known (subs % 1)) named)]
    (is (seq named) "the descriptions must carry worked examples at all")
    (is (empty? bad)
        (str "these names appear in a tool description but not in the model: "
             (pr-str (vec bad))))))

(deftest execute-eql-teaches-qualified-names
  "The failure this whole family of tests exists for: an agent that had never
   been shown a qualified keyword wrote `:query [order_no issue_date]` — bare
   names — because the migrated description had been compressed to five lines.
   The rule is now stated AND demonstrated; both halves are load-bearing."
  (let [d (:description (first (filter #(= "execute_eql" (:name %)) mcp/tool-definitions)))]
    (is (str/includes? d "FULLY-QUALIFIED"))
    (is (str/includes? d ":cis.purchase.vpo/order_no") "shown, not only told")
    (is (> (count d) 1500)
        "this description is an instruction sheet; compressing it is how the
         bare-name bug happened")))

(deftest temporal-result-and-collection-evidence-survive-json-tool-response
  (with-redefs [open.atlas.runtime.token/verify (fn [store c opts] ((::verify store) c opts))
                open.atlas.runtime.instance/answer
                (fn [& _] {:engine/status :ok :env/version 8
                           :engine/data {:event/time (java.time.Instant/parse "2026-09-08T12:00:00Z")}
                           :engine/collections {[:event/all] {:ordering :source-defined :paging :none :completeness :not-guaranteed :returned-count 5}}})]
    (let [r (body-of (post (deps) (rpc "tools/call" {:name "execute_eql" :arguments {:query "[:event/time]"}})))
          content (json/parse-string (get-in r [:result :content 0 :text]) true)]
      (is (nil? (:error r)))
      (is (= "2026-09-08T12:00:00Z" (get-in content [:result :event/time])))
      (is (= "not-guaranteed" (get-in content [:collections 0 :completeness])))
      (is (= 5 (get-in content [:collections 0 :returned-count]))))))

(deftest auth-diagnostics-never-include-presented-secrets
  (let [events (atom [])
        d (deps {:on-auth-failure #(swap! events conj %)})]
    (with-redefs [open.atlas.runtime.token/verify
                  (fn [& _] {:findings [{:code :token.authz/secret-mismatch
                                        :got "sensitive-input"}]})]
      (is (= 401 (:status (post d (rpc "initialize") :auth "sensitive-input" :uri graph-url))))
      (is (= [{:reason :token-rejected :finding-codes [:token.authz/secret-mismatch]}] @events)))
    (reset! events [])
    (with-redefs [open.atlas.runtime.token/verify
                  (fn [& _] {:token-id "tok-1" :authorization {:graph-id "other" :version 1}})
                  open.atlas.runtime.graph/graph-uuid (constantly nil)]
      (is (= 403 (:status (post d (rpc "initialize") :uri graph-url))))
      (is (= [{:reason :graph-mismatch :token-id "tok-1" :graph-uuid graph-uuid}] @events)))))

(deftest only-gate-refusals-of-a-write-are-reported-as-pre-write
  "A mutation-call gate refusal happens before any connector, so the agent is
   told nothing was written. A timeout is also `:rejected` but can land
   mid-write, so it must stay `:unknown` (2026-10-03)."
  (let [pre? @(requiring-resolve 'open.atlas.server.mcp.impl.tools/pre-write-refusal?)]
    (is (pre? {:engine/status :rejected :code :engine.request/unknown-mutation-param}))
    (is (pre? {:engine/status :rejected :code :engine.request/missing-mutation-param}))
    (is (not (pre? {:engine/status :rejected :code :engine.limit/exceeded})))
    (is (not (pre? {:engine/status :error :code :engine.execution/failed})))))

;; ---------------------------------------------------------------------------
;; Saved queries (proposal studio-saved-queries, data-plane-api 1.2)
;; ---------------------------------------------------------------------------

(def ^:private saved
  [{:name "invoice_amount"
    :description "One invoice's amount."
    :params [{:name "id" :type :integer :required? true :example 7}]
    :eql '{:entity {:acme.invoice/id ?id} :query [:acme.invoice/amount]}}])

(defn- tool-call [d tool arguments]
  (let [r (body-of (post d (rpc "tools/call" {:name tool :arguments arguments})))]
    {:error? (get-in r [:result :isError])
     :content (when-not (get-in r [:result :isError])
                (json/parse-string (get-in r [:result :content 0 :text]) true))
     :text (get-in r [:result :content 0 :text])}))

(deftest saved-query-tools-are-mounted-only-where-saved-queries-are-kept
  (with-redefs [open.atlas.runtime.token/verify (fn [store c opts] ((::verify store) c opts))]
    (let [names (fn [d] (set (map :name (get-in (body-of (post d (rpc "tools/list"))) [:result :tools]))))]
      (is (not (contains? (names (deps)) "list_saved_queries"))
          "a host without saved queries announces nothing that could only answer «none»")
      (is (every? (names (deps {:saved-queries (fn [_ _] saved)}))
                  ["list_saved_queries" "run_saved_query" "execute_eql"])))))

(deftest a-saved-query-runs-through-the-same-door-as-execute-eql
  (let [sent (atom nil)
        looked-up (atom nil)
        d (deps {:saved-queries (fn [uuid version] (reset! looked-up [uuid version]) saved)})]
    (with-redefs [open.atlas.runtime.token/verify (fn [store c opts] ((::verify store) c opts))
                  open.atlas.server.mcp.impl.tools/pinned-by-token
                  (fn [_] {:graph-id "acme/billing" :version 3 :catalog {}})
                  open.atlas.runtime.graph/graph-uuid (fn [_ g v] (when (= ["acme/billing" 3] [g v]) graph-uuid))
                  open.atlas.runtime.instance/answer
                  (fn [_ {:keys [request]}]
                    (reset! sent request)
                    {:engine/status :ok :env/version 3 :engine/data {:acme.invoice/amount 120}})]
      (testing "listing reads the set pinned to the token's version"
        (let [{:keys [content]} (tool-call d "list_saved_queries" {})]
          (is (= [graph-uuid 3] @looked-up))
          (is (= ["invoice_amount"] (map :name (:saved-queries content))))
          (is (nil? (:eql (first (:saved-queries content)))) "the EQL travels only when asked for")))
      (testing "a run binds the value and answers in execute_eql's shape"
        (let [{:keys [content error?]} (tool-call d "run_saved_query" {:name "invoice_amount" :params {:id 7}})]
          (is (not error?))
          (is (= '{:entity {:acme.invoice/id 7} :query [:acme.invoice/amount]} @sent))
          (is (= {:result {:acme.invoice/amount 120} :env/version 3}
                 (select-keys content [:result :env/version])))))
      (testing "a bad parameter is refused before anything runs"
        (reset! sent nil)
        (let [{:keys [error? text]} (tool-call d "run_saved_query" {:name "invoice_amount" :params {:id "seven"}})]
          (is error?)
          (is (str/includes? text "param-type"))
          (is (nil? @sent))))
      (testing "an unknown name"
        (is (str/includes? (:text (tool-call d "run_saved_query" {:name "nope"})) "saved-query/unknown")))
      (testing "/eql's helper resolves the same way"
        (is (= '{:entity {:acme.invoice/id 7} :query [:acme.invoice/amount]}
               (:request ((mcp/saved-query-request d) good-token "2026-10-10T00:00:00Z" "invoice_amount" {:id 7}))))))))

(deftest a-version-published-before-saved-queries-has-none
  (let [d (deps {:saved-queries (fn [_ _] nil)})]
    (with-redefs [open.atlas.runtime.token/verify (fn [store c opts] ((::verify store) c opts))
                  open.atlas.server.mcp.impl.tools/pinned-by-token
                  (fn [_] {:graph-id "acme/billing" :version 1 :catalog {}})
                  open.atlas.runtime.graph/graph-uuid (constantly graph-uuid)]
      (is (= [] (:saved-queries (:content (tool-call d "list_saved_queries" {}))))))))
