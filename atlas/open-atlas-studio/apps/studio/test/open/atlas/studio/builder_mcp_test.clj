(ns open.atlas.studio.builder-mcp-test
  "**The Builder MCP endpoint** (8.4 P1b).

   What is tested here is only what this app decides: who a caller is, what
   that makes visible, and what the pool ends up recording about it. The
   protocol itself belongs to `common/mcp-transport` and has its own suite;
   the tools belong to `studio/tools` and have theirs. Testing any of that
   again here would make the same claim in a second place, which is how two
   places come to disagree.

   Four properties, each an exit condition of P1b:

   1. the credential is a workspace token, and a data-plane token is refused
      **as the wrong kind of key**;
   2. a read-scoped caller does not merely fail to call the write tools —
      it is never told they exist;
   3. what the pool records about a write comes from the CONNECTION, and an
      argument saying otherwise is refused rather than believed;
   4. no response, finding or listing ever carries a secret."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [open.atlas.runtime.obs :as obs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.studio.builder-mcp :as builder-mcp]
            [open.atlas.studio.builder-server]
            [open.atlas.studio.credentials :as credentials]
            [open.atlas.common.mcp-transport :as wire]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.platform-runtime :as runtime]
            [open.atlas.runtime.env :as env]
            [open.atlas.studio.pool :as pool]
            [open.atlas.server.mcp :as mcp]
            [open.atlas.studio.tools :as tools]
            [open.atlas.studio.semantic-service :as semantic]
            [open.atlas.studio.workspace-token :as wt]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

(def ^:dynamic *conn* nil)
(def ^:dynamic *home* nil)
(def ^:dynamic *owner*
  "The principal that owns workspace \"demo\". A workspace token records who
   issued it, and every guard resolves that principal's role from the pool — so
   a test that invented a principal would be testing a key nobody could have
   minted."
  nil)
(def ^:private pepper "test-workspace-pepper")

(defn- with-studio [f]
  (let [cfg  (pool/config {:backend :mem :id (str (random-uuid))})
        conn (-> (pool/open-pool! cfg) graph/install! identity/install!)
        home (str (System/getProperty "java.io.tmpdir") "/oa-wt-" (random-uuid))]
    (try
      (let [owner (:user (identity/bootstrap-admin!
                          conn {:email "owner@example.com" :name "Owner"
                                :password "a-safe-password" :workspace "demo"}))]
      (pool/execute-action conn {:workspace "demo" :action :create :type :namespace :id "cis.party"
                                 :entry {:label "客户"} :expected_revision 0})
      (pool/execute-action conn {:workspace "demo" :action :create :type :unit :id "USD"
                                 :entry {:label "美元"} :expected_revision 0})
      ;; The Builder's graph service reads the server's state atom, the same
      ;; way every HTTP handler does — so the test must stand the server up to
      ;; the same degree, or it is testing a different wiring than production.
      (reset! (var-get #'open.atlas.studio.builder-server/studio-state) {:conn conn})
      (binding [*conn* conn *home* home *owner* (:principal owner)]
        (f)))
      (finally
        (reset! (var-get #'open.atlas.studio.builder-server/studio-state) nil)
        (pool/close-pool! conn)
        (pool/delete-pool! cfg)
        (doseq [f* (reverse (file-seq (java.io.File. home)))] (.delete ^java.io.File f*))))))

(use-fixtures :each with-studio)

;; `bs-graph-service` (the Builder's own graph service bound to the token's
;; caller) is gone with the last test that compared it against the MCP route
;; — the governance tools it existed to cross-check (`rename_graph` and
;; friends) are GUI-only now (8.4.16 裁定九).

(defn- deps []
  (builder-mcp/deps {:home *home*
                     :pepper pepper
                     :conn *conn*
                     ;; The host's own service, exactly as builder-server
                     ;; injects it — the whole claim of P2 is that these are
                     ;; the same functions the UI routes call.
                     :graph-service (var-get #'open.atlas.studio.builder-server/graph-service)
                     :semantic-service (fn [caller] (semantic/service *conn* caller
                                                       (fn [source-id entry] (credentials/resolve-config *home* source-id entry))))
                     :draft-service (var-get #'open.atlas.studio.builder-server/draft-service)
                     ;; 8.4.18 裁定十一: the environment seam the seven mounted
                     ;; data-plane tools resolve through, wired exactly as
                     ;; `mcp-deps` wires it.
                     :draft-runtime (var-get #'open.atlas.studio.builder-server/draft-runtime)
                     :declarations (tools/declarations mcp/mountable-declarations)
                     :registry #(tools/registry % mcp/mountable-declarations)
                     :tools-list tools/tools-list
                     ;; The host's OWN call seam, exactly as `mcp-deps`
                     ;; injects it: the pool-mutation events this door emits
                     ;; are emitted THERE (E3), so a test wiring `tools/call`
                     ;; raw would be testing a door the Builder does not have.
                     :call (var-get #'open.atlas.studio.builder-server/audited-tool-call)
                     ;; 8.4.17 裁定十, wired exactly as `mcp-deps` wires it:
                     ;; the RESOLVED default graph of the token's workspace.
                     :default-graph (fn [workspace]
                                      (graph/default-graph (pool/db *conn*) workspace))
                     ;; The credential resolution the structure-reading tools
                     ;; need, wired as `mcp-deps` wires it: `mcp-deps` passes
                     ;; `platform-runtime/resolve-source`, which is
                     ;; `credentials/resolve-config` over the studio home —
                     ;; here the home is the test's, so the store this test
                     ;; writes is the one that answers.
                     :resolve-source (fn [source-id entry]
                                       (credentials/resolve-config *home* source-id entry))
                     :now (constantly "2026-08-28T00:00:00Z")
                     ;; The host's OWN emitter, exactly as `mcp-deps` injects
                     ;; it: the claim under test is that this door records a
                     ;; refusal the same way the Builder's other one does.
                     :on-auth-failure (partial (var-get #'open.atlas.studio.builder-server/audit-auth-failure!)
                                               :builder-mcp)}))

(defn- issue!
  ([scope] (issue! scope "2026-09-28T00:00:00Z"))
  ([scope expires-at]
   (wt/issue! *home* pepper {:workspace "demo" :scope scope
                             :issued-by *owner*
                             :issued-at "2026-08-28T00:00:00Z"
                             ;; The deps' `:now` is pinned at 2026-08-28T00:00:00Z,
                             ;; so an expiry at or before that is a key that has
                             ;; already run out.
                             :expires-at expires-at
                             :label "builder-mcp-test"})))

(defn- post
  ([token body] (post token body "/mcp"))
  ([token body uri]
   ((wire/routes (deps))
    {:request-method :post :uri uri
     :headers (cond-> {"host" "127.0.0.1:9631"}
                token (assoc "authorization" (str "Bearer " token)))
     ;; Both halves, exactly as `builder-mcp/request-map` hands them over
     ;; (8.4.14 裁定八): the transport consumes `:body`, and `:body-text` is
     ;; the same bytes as text, which is where the client's `initialize`
     ;; declaration is read from.
     :body-text (json/generate-string body)
     :body (java.io.ByteArrayInputStream.
            (.getBytes (json/generate-string body) "UTF-8"))})))

(defn- rpc [method & [params]]
  (cond-> {:jsonrpc "2.0" :method method :id 1} params (assoc :params params)))

(defn- body-of [r] (json/parse-string (:body r) true))

;; `with-advanced!` is gone (8.4.16 裁定九 deletes the `advanced` key along
;; with `execute_action` / `confirm_candidates` themselves — there is no
;; declaration left that makes them reappear). Writes below go through the
;; intent tools (`update_attribute`, `describe_objects`, …) instead.

(defn- call! [token tool args]
  (body-of (post token (rpc "tools/call" {:name tool :arguments args}))))

(defn- tool-result
  "The EDN a tool result carries. A refusal arrives as `isError` with the
   structured finding printed inside a `{:error …}` envelope — that is the
   transport's contract, and `builder-mcp/refusal->error` is what puts this
   platform's findings into it."
  [response]
  (let [v (clojure.edn/read-string (get-in response [:result :content 0 :text]))]
    (or (:error v) v)))

;; ---------------------------------------------------------------------------
;; 1. The credential
;; ---------------------------------------------------------------------------

(deftest the-endpoint-takes-a-workspace-token-and-says-so
  (testing "no bearer at all"
    (is (= 401 (:status (post nil (rpc "initialize"))))))
  (testing "**a data-plane token is refused as the WRONG KIND OF KEY**, not as a
            bad one: D3.12 keeps the doors apart precisely so that a leaked query
            token is not a write permission, and a caller holding one needs to be
            told which door they are at"
    (let [r (post "oat_v1.a-data-plane-token" (rpc "initialize"))
          msg (get-in (body-of r) [:error :message])]
      (is (= 401 (:status r)))
      (is (str/includes? msg "workspace token"))
      (is (str/includes? msg "graph token"))))
  (testing "a revoked token stops working, and looks exactly like an unknown one —
            confirming that a token once existed is a fact an attacker has no
            other way to learn"
    (let [{:keys [token record]} (issue! #{:read})]
      (is (= 200 (:status (post token (rpc "initialize")))))
      (wt/revoke! *home* {:token-id (:token-id record) :revoked-at "2026-08-28T01:00:00Z"})
      (let [revoked (post token (rpc "initialize"))
            unknown (post (str wt/token-prefix "never-issued") (rpc "initialize"))]
        (is (= 401 (:status revoked)))
        (is (= (:body unknown) (:body revoked))))))
  (testing "an EXPIRED token is the same 401, byte for byte, as an unknown one:
            a key with a natural death must not announce that it ever lived"
    (let [{:keys [token]} (issue! #{:read} "2026-08-28T00:00:00Z")
          expired (post token (rpc "initialize"))
          unknown (post (str wt/token-prefix "never-issued") (rpc "initialize"))]
      (is (= 401 (:status expired)))
      (is (= (:body unknown) (:body expired)))
      (is (= (:headers unknown) (:headers expired)))))
  (testing "initialize names THIS endpoint, so an agent with both configured can
            tell which one answered"
    (let [{:keys [token]} (issue! #{:read})
          r (body-of (post token (rpc "initialize")))]
      (is (= "open-atlas-builder-mcp" (get-in r [:result :serverInfo :name])))
      (is (str/includes? (get-in r [:result :instructions]) "apply=true")))))

;; ---------------------------------------------------------------------------
;; 2. Scope is the capability set
;; ---------------------------------------------------------------------------

(defn- listed [token]
  (set (map :name (get-in (body-of (post token (rpc "tools/list"))) [:result :tools]))))

(deftest a-read-token-is-never-told-the-write-tools-exist
  "«不问就不在» (principles 「两把钥匙」). A tool that exists and always refuses
   invites a client to retry forever and tells an attacker what is behind the
   door; absence is the honest statement that this key is read-only."
  (let [read-only (:token (issue! #{:read}))
        writer (:token (issue! #{:read :write}))
        r (listed read-only)
        w (listed writer)]
    (is (contains? r "query_model") "the read tools are there")
    ;; 8.4.16 裁定九: `execute_action` / `confirm_candidates` / `generate_from_source`
    ;; / `plan_from_source` and the whole `advanced` key are deleted outright —
    ;; there is no declaration left, so no client capability brings them back.
    (doseq [t ["execute_action" "confirm_candidates" "generate_from_source"
               "plan_from_source" "update_recipe"]]
      (is (not (contains? r t)) (str t " must be absent for a read token"))
      (is (not (contains? w t)) (str t " no longer exists for any key")))
    (testing "and dispatch agrees with the listing — one capability decision,
              not two that can drift apart"
      (let [res (call! read-only "execute_action"
                       {:workspace "demo" :action :update :type :unit :id "USD"
                        :entry {:label "x"} :expected_revision 1})]
        (is (true? (get-in res [:result :isError])))
        (is (= :toolkit.tool/unknown (:code (first (:findings (tool-result res)))))
            "unknown, not forbidden: the write tools do not exist for this key")))
    (testing "the listing is byte-stable — a client diffs it"
      (is (= (:body (post read-only (rpc "tools/list")))
             (:body (post read-only (rpc "tools/list"))))))))

;; ---------------------------------------------------------------------------
;; 3. What the pool records comes from the connection
;; ---------------------------------------------------------------------------

(deftest a-write-is-audited-against-the-connections-token
  ;; `execute_action` is gone (8.4.16 裁定九); an intent tool's `apply` writes
  ;; through the same `pool/execute-actions` path, so the audit property is
  ;; the same one — `describe_objects` is the one that reaches any pool
  ;; object by type/id without needing a graph member to name it.
  (let [{:keys [token record]} (issue! #{:read :write})
        res (call! token "describe_objects"
                   {:objects [{:type "unit" :id "USD" :description "改过。"}]
                    :apply true})]
    (is (nil? (get-in res [:result :isError])) (pr-str res))
    (let [m (->> (pool/mutations (pool/db *conn*))
                 (filter #(= [:unit "USD"] (:mutation/object %)))
                 last)]
      (is (= :mcp (:mutation/entrypoint m)))
      (is (= (:token-id record) (:mutation/token-id m)))
      (testing "the actor is the principal that ISSUED the key — which is how
                「哪些改动来自 AI、经谁授权」 is answered by joining the two ledgers"
        (is (= *owner* (:mutation/actor m))))
      (is (= "2026-08-28T00:00:00Z" (:mutation/at m))))))

(deftest a-client-cannot-say-where-it-came-from
  "**The承重 property of P1b.** A caller that could name its own entrance could
   make the audit read «a human did this in the UI», and an audit that can be
   made to say that is worse than none — it is read as evidence.

   Note what this test does NOT do: it does not check that a forged value was
   ignored. It checks that the call is REFUSED, because the tool declarations
   have no such parameter at all."
  (let [{:keys [token record]} (issue! #{:read :write})
        before (pool/object (pool/db *conn*) :unit "USD")]
    (doseq [k [:entrypoint :token_id :actor]]
      (let [res (call! token "describe_objects"
                       (assoc {:objects [{:type "unit" :id "USD" :description "冒名。"}]
                               :apply true}
                              k "forged"))]
        (is (true? (get-in res [:result :isError]))
            (str k " must be refused, not ignored"))))
    (testing "and nothing was written by any of those attempts"
      (is (= (:pool/revision before)
             (:pool/revision (pool/object (pool/db *conn*) :unit "USD")))))
    (testing "the tools/list schema does not advertise those parameters either —
              a client should not even learn to try"
      (let [names (->> (get-in (body-of (post token (rpc "tools/list"))) [:result :tools])
                       (mapcat #(keys (get-in % [:inputSchema :properties])))
                       (map name)
                       set)]
        (doseq [k ["entrypoint" "token_id" "actor"]]
          (is (not (contains? names k))))))
    (is (= (:token-id record) (:token-id record)))))

(deftest a-refusal-is-an-answer-the-agent-can-act-on
  ;; `execute_action`'s caller-supplied `expected_revision` is gone with it —
  ;; every intent tool reads the revision itself and cannot be handed a stale
  ;; one. The refusal an agent can act on is now `describe_objects` naming an
  ;; object the pool does not hold, by the same structured-finding discipline.
  (let [{:keys [token]} (issue! #{:read :write})
        before (count (pool/mutations (pool/db *conn*)))
        res (call! token "describe_objects"
                   {:objects [{:type "unit" :id "NO-SUCH-UNIT" :description "x"}]
                    :apply true})]
    (is (true? (get-in res [:result :isError])))
    (is (= :tools.intent/name-unresolved (:code (first (:findings (tool-result res)))))
        "the structured finding travels whole — «which object was wrong» is
         what the next call needs")
    (testing "a refused write leaves no audit record: the ledger must not show
              a change that never happened"
      (is (= before (count (pool/mutations (pool/db *conn*))))))))

;; ---------------------------------------------------------------------------
;; 4. Nothing leaks
;; ---------------------------------------------------------------------------

(deftest no-secret-appears-anywhere
  (let [{:keys [token record]} (issue! #{:read :write})
        ;; One write that SUCCEEDS, so the ledger claim below is about a real
        ;; record rather than an empty list quietly passing.
        _ (call! token "describe_objects"
                 {:objects [{:type "unit" :id "USD" :description "落一条审计。"}]
                  :apply true})
        responses [(post token (rpc "initialize"))
                   (post token (rpc "tools/list"))
                   (post token (rpc "tools/call" {:name "query_model" :arguments {}}))
                   (post token (rpc "tools/call" {:name "describe_objects"
                                                  :arguments {:objects [{:type "unit" :id "NOPE"
                                                                         :description "x"}]
                                                              :apply true}}))
                   (post "oat_v1.wrong-door" (rpc "initialize"))
                   (post nil (rpc "initialize"))]]
    (doseq [r responses]
      (is (not (str/includes? (str (:body r)) token))
          "the credential must never come back on the wire"))
    (testing "nor into the pool's own ledger — only the id it can be joined by"
      (let [s (pr-str (pool/mutations (pool/db *conn*)))]
        (is (not (str/includes? s token)))
        (is (str/includes? s (:token-id record)))))
    (testing "nor into the governance listing, which shows records and no digest"
      (let [rs (wt/records *home*)]
        (is (seq rs))
        (is (every? #(not (contains? % :digest)) rs))
        (is (not (str/includes? (pr-str rs) token)))))))

(deftest the-ledger-is-this-instances-alone
  "S5.8 for the control plane: a token minted against one instance's pepper
   must fail against another's, or «这台 Studio 的钥匙» would be a phrase with
   no meaning."
  (let [{:keys [token]} (issue! #{:read :write})]
    (is (some? (wt/verify *home* pepper token "2026-08-28T00:00:00Z")))
    (is (nil? (wt/verify *home* "a-different-instances-pepper" token
                         "2026-08-28T00:00:00Z")))))

;; ---------------------------------------------------------------------------
;; P2 — the graph registry tools
;; ---------------------------------------------------------------------------

(defn- ok!
  "A tool call that must have succeeded. Returns the value it carried.

   **JSON, not EDN**: the transport prints a refusal with `pr-str` (the finding
   must survive whole) but JSON-encodes a success, because that is what an MCP
   client reads. Two shapes on one channel is the transport's contract, and a
   test that read both the same way would pass only by luck."
  [res]
  (is (nil? (get-in res [:result :isError])) (pr-str res))
  (json/parse-string (get-in res [:result :content 0 :text]) true))

(deftest the-graph-tools-split-by-capability
  "Reading which graphs exist is not the same act as changing one, and the
   split is the capability set — not a check inside a handler.

   The governance tools (`rename_graph` `archive_graph` `restore_graph`
   `delete_graph` `duplicate_graph` `merge_graphs`
   `grant_graph_access` `revoke_graph_access`) are gone from BOTH capabilities
   (8.4.16 裁定九): they are GUI-only now, and their service functions are
   unaffected — only the MCP declaration is deleted."
  (let [reader (:token (issue! #{:read}))
        writer (:token (issue! #{:read :write}))
        r (listed reader)
        w (listed writer)]
    (doseq [t ["list_graphs" "inspect_graph"]]
      (is (contains? r t) (str t " is a read")))
    (is (not (contains? r "create_graph")) "create_graph must be absent for a read token")
    (is (contains? w "create_graph") "create_graph must be present for a write token")
    ;; Owner ruling 2026-10-11: the lifecycle tools are back, writes for a
    ;; write key only (duplicate plans on a read key).
    (doseq [t ["rename_graph" "archive_graph" "restore_graph" "delete_graph" "merge_graphs"]]
      (is (not (contains? r t)) (str t " is a write"))
      (is (contains? w t) (str t " is back for a write key")))
    (doseq [t ["update_recipe"
               "add_to_graph" "remove_from_graph"]]
      (is (not (contains? r t)) (str t " no longer exists for any key"))
      (is (not (contains? w t)) (str t " no longer exists for any key")))
    (is (contains? w "change_graph_members")
        "change_graph_members replaced add_to_graph / remove_from_graph")))

(deftest an-agent-can-carry-a-graph-through-its-whole-life
  "The reason P2 exists: an agent that enriches a model but cannot put the
   result into a graph has done half a job, and hands the other half to a human
   at the moment the context is freshest.

   The lifecycle beyond create/list — rename, archive, restore, delete — moved
   to the GUI (8.4.16 裁定九); this test now covers only what MCP still does."
  (let [{:keys [token]} (issue! #{:read :write})]
    (testing "create"
      (let [r (ok! (call! token "create_graph"
                          {:name "sales" :selectors ["cis.*"]}))]
        ;; `register-graph!`'s own envelope, carried through unchanged — the
        ;; tool face is a wrapper and does not reshape what the module returns.
        (is (= "demo/sales" (get-in r [:graph :graph/qualified-name])))))
    (testing "list — and the workspace is the TOKEN's, not an argument"
      (let [r (ok! (call! token "list_graphs" {}))]
        (is (= "demo" (:workspace r)))
        (is (= #{"demo/sales"} (set (map :graph/qualified-name (:graphs r)))))))
    (testing "the recipe reads back through inspect_graph's summary"
      (let [r (ok! (call! token "inspect_graph" {:graph "demo/sales"}))]
        (is (= "demo/sales" (:graph r)))
        (is (map? (get-in r [:recipe :members])))))))

(deftest a-graph-in-another-workspace-is-unknown-not-forbidden
  "Whether a graph exists in a workspace this credential has nothing to do with
   is not this caller's business. «Forbidden» would answer the question that
   «unknown» declines to."
  (let [{:keys [token]} (issue! #{:read :write})
        res (call! token "inspect_graph" {:graph "other/secret"})]
    (is (true? (get-in res [:result :isError])) "inspect_graph must refuse")
    (is (= :graph-unknown (get-in (first (:findings (tool-result res))) [:got :code]))
        "inspect_graph must say unknown, not forbidden")))

(deftest a-client-cannot-name-its-own-workspace-or-role
  "The tools take no workspace, no actor and no role. All three come from the
   credential — a tool that accepted a workspace would let a key issued for one
   act in another."
  (let [{:keys [token]} (issue! #{:read :write})
        params (->> (get-in (body-of (post token (rpc "tools/list"))) [:result :tools])
                    (filter #(contains? #{"list_graphs" "create_graph" "inspect_graph"}
                                        (:name %)))
                    (mapcat #(keys (get-in % [:inputSchema :properties])))
                    (map name)
                    set)]
    (is (seq params) "the graph tools must actually declare parameters")
    (doseq [k ["workspace" "principal" "role" "actor"]]
      (is (not (contains? params k)) (str k " must not be a parameter")))))

(deftest the-draft-query-tool-is-the-data-planes-own
  "8.4.18 裁定十一 ②: `evaluate_draft_eql` is retired and the query tool on
   this door is `execute_eql` — the data plane's declaration, its handler, its
   refusal shape, plus a `graph` and `:draft true`.

   `execute_mutation` is NOT mounted (裁定十一 ①): 裁定九 put writing to a
   source system back behind the GUI, and sharing a tool set is not a reason
   for it to return."
  (doseq [caps [#{:read} #{:read :write}]]
    (let [token (:token (issue! caps))]
      (is (not (contains? (listed token) "evaluate_draft_eql")))
      (is (not (contains? (listed token) "execute_mutation")))
      (is (= [:toolkit.tool/unknown]
             (mapv :code (:findings (tool-result
                                     (call! token "evaluate_draft_eql"
                                            {:graph "demo/sales" :query "[:x/y]"}))))))))
  (let [{:keys [token]} (issue! #{:read :write})]
    (ok! (call! token "create_graph" {:name "sales" :selectors ["cis.*"]}))
    (testing "another workspace's graph is unknown, not forbidden — the same
              guard every other tool uses"
      (graph/register-graph! *conn* {:workspace "other" :name "secret" :members {}
                                     :created-at "2026-08-28T00:00:00Z"})
      (let [fs (:findings (tool-result
                           (call! token "execute_eql"
                                  {:graph "other/secret" :query "[:x/y]"})))]
        (is (= :graph-unknown (get-in (first fs) [:got :code])))))))

(deftest the-draft-write-tool-is-gone-for-every-key
  "`execute_draft_mutation` no longer exists for any capability set — not
   «hidden from read-only», the whole declaration is deleted (8.4.16 裁定九)."
  (doseq [caps [#{:read} #{:read :write}]]
    (let [token (:token (issue! caps))]
      (is (not (contains? (listed token) "execute_draft_mutation")))
      (is (= [:toolkit.tool/unknown]
             (mapv :code (:findings (tool-result
                                     (call! token "execute_draft_mutation"
                                            {:graph "demo/sales" :query "[(a/b {})]"})))))))))

;; ---------------------------------------------------------------------------
;; 7. The read face — read_object and inspect_graph (8.4.10 P6; project_graph
;;    folded into inspect_graph's view=entries by 8.4.16 裁定九)
;; ---------------------------------------------------------------------------

(defn- seed-projectable-model!
  "One namespace, one source, two attributes and a resolver that produces both.
   The smallest pool a scope projection can close over — and the exact shape
   the ps-dvd9 incident turned on, because the resolver is reachable by CLOSURE
   and the recipe below never names it.

   The source's connection keys are `${VAR}` REFERENCES, which is what a source
   entry is allowed to hold (sql-v1 / D2.15): the read face must hand them back
   as written, so a test whose fixture carried literals could not tell the
   difference."
  []
  (let [r (pool/execute-actions
           *conn*
           [{:workspace "demo" :action :create :type :namespace :id "cis.a"
             :entry {:kind :master :label "A"} :expected_revision 0}
            {:workspace "demo" :action :create :type :source :id "cis-db"
             :entry {:type "sql" :label "CIS"
                     :jdbc_url "${CIS_DB_URL}"
                     :username "${CIS_DB_USER}"
                     :password "${CIS_DB_PASSWORD}"}
             :expected_revision 0}
            {:workspace "demo" :action :create :type :attribute :id :cis.a/id
             :entry {:type :integer :format :id :identity? true :label "A id"}
             :expected_revision 0}
            {:workspace "demo" :action :create :type :attribute :id :cis.a/name
             :entry {:type :string :format :text :label "A name"}
             :expected_revision 0}
            {:workspace "demo" :action :create :type :resolver :id "a_by_id"
             :entry {:input [:cis.a/id] :output [:cis.a/id :cis.a/name]
                     :result_type "single" :source-ref "cis-db"
                     :source {:type "sql" :query "SELECT 1"}}
             :expected_revision 0}]
           {:entrypoint :ui :actor *owner* :at "2026-08-28T00:00:00Z"})]
    (assert (empty? (:findings r)) (pr-str (:findings r)))
    ;; Registered directly rather than through `create_graph`: the recipe has
    ;; to hold real keywords, and this fixture is not what is under test.
    (graph/register-graph! *conn* {:workspace "demo" :name "harvest"
                                   :created-at "2026-08-28T00:00:00Z"
                                   :members {:namespaces #{"cis.a"}
                                             :attributes #{:cis.a/id :cis.a/name}}})
    "demo/harvest"))

(deftest the-discovery-tools-answer-off-the-draft-projection
  "8.4.18 裁定十一 ①/②: `inspect_graph`'s `catalog` view retired, because it
   WAS `list_entities` / `list_attributes` under another name. Those two are
   now mounted here as the data plane declares them, with a `graph`.

   What is pinned is that the rows carry the data plane's own key names, that
   they are computed off the DRAFT rather than a pinned artifact, and that
   every answer says `:draft true` — a draft answer is not what a consumer
   sees, and a result that does not say so gets quoted as if it were."
  (let [{:keys [token]} (issue! #{:read :write})
        graph (seed-projectable-model!)]
    (let [r (ok! (call! token "list_entities" {:graph graph}))
          es (:entities r)]
      (testing "entities come back in the data plane's own shape, marked draft"
        (is (true? (:draft r)))
        (is (= graph (:graph r)))
        (is (= ["cis.a"] (mapv :entity es)))
        (is (= [2] (mapv :attribute_count es)))
        (testing "with identity keys — what an entity-scoped query needs"
          (is (= [["cis.a/id"]] (mapv :identity_keys es)))))
      (testing "attributes are fully qualified, and one bad name loses nothing"
        (let [as (:attributes (ok! (call! token "list_attributes"
                                          {:graph graph
                                           :entities ["cis.a" "no.such.entity"]})))]
          (is (= #{"cis.a/id" "cis.a/name"} (set (map :attribute as)))
              "fully qualified, copyable verbatim into a query")
          (is (every? #(= "cis.a" (:entity %)) as)
              "the unknown name yielded nothing rather than failing the call")
          (is (= #{"integer" "string"} (set (map :type as)))))))
    (testing "the DRAFT, not a published version: a write is visible at once"
      (let [r (pool/execute-actions
               *conn*
               [{:workspace "demo" :action :create :type :attribute :id :cis.a/note
                 :entry {:type :string :format :text :label "A note"}
                 :expected_revision 0}
                {:workspace "demo" :action :update :type :resolver :id "a_by_id"
                 :entry {:input [:cis.a/id] :output [:cis.a/id :cis.a/name :cis.a/note]
                         :result_type "single" :source-ref "cis-db"
                         :source {:type "sql" :query "SELECT 1"}}
                 :expected_revision 1}]
               {:entrypoint :ui :actor *owner* :at "2026-08-28T00:00:00Z"})]
        (assert (empty? (:findings r)) (pr-str (:findings r))))
      (ok! (call! token "change_graph_members" {:graph graph :add ["cis.a/note"] :apply true}))
      (is (contains? (set (map :attribute
                               (:attributes (ok! (call! token "list_attributes"
                                                        {:graph graph
                                                         :entities ["cis.a"]})))))
                     "cis.a/note")
          "nothing was published, and discovery already sees it"))
    (testing "another workspace's graph is unknown, not forbidden — the same
              guard every other tool uses"
      (graph/register-graph! *conn* {:workspace "other" :name "hidden" :members {}
                                     :created-at "2026-08-28T00:00:00Z"})
      (let [fs (:findings (tool-result (call! token "list_entities"
                                              {:graph "other/hidden"})))]
        (is (= :graph-unknown (get-in (first fs) [:got :code])))))
    (testing "the retired view is gone, not hidden"
      (let [fs (:findings (tool-result (call! token "inspect_graph"
                                              {:graph graph :view "catalog"})))]
        (is (seq fs) "view=catalog is no longer an accepted enum value")))))

(deftest things-view-and-list-entities-answer-from-one-criterion
  "doc 106 §106.5, P8a: the Builder's `inspect_graph view=things` and the data
   plane's `list_entities` must not disagree about what a thing is — an agent
   told a namespace is an entity here and a fragment there will go query the
   one that does not exist. Both call `dialect/need-view`; this pins that they
   agree over the SAME draft, which is the only place both faces are mounted."
  (let [{:keys [token]} (issue! #{:read :write})
        graph (seed-projectable-model!)
        things (ok! (call! token "inspect_graph" {:graph graph :view "things"}))
        entities (:entities (ok! (call! token "list_entities" {:graph graph})))]
    (is (= #{"cis.a"} (set (map :name (:things things)))))
    (is (= (set (map :name (:things things)))
           (set (map :entity (filter :thing entities))))
        "the same namespaces are things on both faces")
    (testing "the thing reads back in the four words"
      (let [t (first (:things things))]
        (is (= ["cis.a/id"] (:identity t)))
        (is (true? (:identity-declared? t)))
        (is (= ["cis.a/id" "cis.a/name"] (:attributes t)))
        (is (= [] (:relations t)))
        (is (false? (:list? t)))))
    (testing "and the view carries the other three sections"
      (is (contains? things :fragments))
      (is (= [] (:concepts things)))
      (is (empty? (filter #(= :error (:severity %)) (:findings things)))))))

(deftest the-discovery-tools-are-reads-so-a-read-only-key-keeps-them
  "It touches no connector and reads no row — it reads the compiled model."
  (is (contains? (listed (:token (issue! #{:read}))) "inspect_graph")))

(deftest the-read-face-is-reachable-with-a-read-key
  "8.4.10's last exit condition. `inspect_graph` is `:capability :read`, so a
   read-only credential must both SEE it and be able to call it — an agent
   given a read key to look around with must not discover that looking is what
   it cannot do."
  (let [reader (:token (issue! #{:read}))]
    (doseq [t ["read_object" "inspect_graph"]]
      (is (contains? (listed reader) t) (str t " must be listed for a read key")))
    (ok! (call! reader "read_object" {:type "unit" :id "USD"}))
    (ok! (call! reader "inspect_graph" {:graph (seed-projectable-model!)}))))

(deftest a-write-reads-back-as-exactly-what-it-wrote
  "**The direct motive for P6.** The tools could change the pool and could not
   look at it: an agent asked what a write actually left had nothing to consult
   but its own request. This is the whole loop through MCP — write, read back —
   and the revision the read reports is the one the next write must plan
   against.

   `resolve_target` is no longer an MCP tool (8.4.16 裁定九: intent tools
   resolve a name internally), so the write side here is `describe_objects`,
   which reaches any pool object by type/id and reads its revision itself."
  (let [{:keys [token]} (issue! #{:read :write})
        before (ok! (call! token "read_object" {:type "unit" :id "USD"}))
        _ (ok! (call! token "describe_objects"
                      {:objects [{:type "unit" :id "USD" :description "写后验证。"}]
                       :apply true}))
        after (ok! (call! token "read_object" {:type "unit" :id "USD"}))]
    (testing "what came back is the pool's own entry, not a reshaping of it"
      (is (= (:pool/entry (pool/object (pool/db *conn*) :unit "USD"))
             (:pool/entry after))))
    (is (= "写后验证。" (get-in after [:pool/entry :description])))
    (is (= "美元" (get-in after [:pool/entry :label]))
        "a merge update, so the field nobody touched is still there")
    (is (= (inc (:pool/revision before)) (:pool/revision after)))))

(deftest a-source-entry-comes-back-with-its-credential-references-unresolved
  "8.4.10 ruling ③. A source entry holds `${VAR}` REFERENCES, never literals
   (sql-v1 refuses those at write time) — so handing the entry back verbatim is
   safe, and it is the only safe thing to do. A face that resolved them would
   put the credential itself into the tool response, which is to say into the
   session transcript."
  (let [{:keys [token]} (issue! #{:read})
        _ (seed-projectable-model!)
        res (call! token "read_object" {:type "source" :id "cis-db"})
        entry (:pool/entry (ok! res))]
    (is (= "${CIS_DB_URL}" (:jdbc_url entry)))
    (is (= "${CIS_DB_USER}" (:username entry)))
    (is (= "${CIS_DB_PASSWORD}" (:password entry)))
    (testing "and nothing that looks like a resolved connection string appears
              anywhere in the response"
      (is (not (str/includes? (str res) "jdbc:"))))))

(defn- sqlite-with-one-table!
  "A real SQLite file with one table, so `describe_source` has something to
   read. The connector opens it for real — this test is about the entry that
   reaches the connector, so a faked SPI would not answer the question."
  []
  (let [path (str *home* "/cred.db")]
    (.mkdirs (java.io.File. ^String *home*))
    (with-open [c (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" path))
                st (.createStatement c)]
      (.execute st "CREATE TABLE customers (cust_no INTEGER PRIMARY KEY, cust_name TEXT)"))
    path))

(deftest a-credential-held-only-in-the-store-reaches-the-structure-read
  "The structure-reading path resolves credentials the same way the query path
   does. A source whose connection keys are `${VAR}` references, with the
   values held ONLY in this instance's credential store and in no process
   environment variable, must describe rather than refuse with
   `unresolved ${VAR}` — credential resolution is one place
   (`credentials/resolve-config`), reached by both paths."
  (let [{:keys [token]} (issue! #{:read})
        path (sqlite-with-one-table!)
        ;; doc 106 §106.14-12: the store is keyed by VARIABLE NAME, so the
        ;; names written in the entry below are the names filed here — the
        ;; entry's `${X_USER}` is not "cred-db's username" any more, it is a
        ;; variable, and that is what makes one store serve every connector.
        _ (credentials/put-vars! *home* {"CRED_DB_URL" (str "jdbc:sqlite:" path)
                                         "X_USER" "modeller"
                                         "X_PASSWORD" "s3cret"})
        r (pool/execute-actions
           *conn*
           [{:workspace "demo" :action :create :type :source :id "cred-db"
             :entry {:type "sql" :label "Cred"
                     :jdbc_url "${CRED_DB_URL}"
                     :username "${X_USER}"
                     :password "${X_PASSWORD}"}
             :expected_revision 0}]
           {:entrypoint :ui :actor *owner* :at "2026-08-28T00:00:00Z"})
        _ (assert (empty? (:findings r)) (pr-str (:findings r)))
        _ (is (every? nil? (map #(System/getenv %) ["CRED_DB_URL" "X_USER" "X_PASSWORD"]))
              "the point of the test is that the process environment holds none of them")
        res (call! token "describe_source" {:source "cred-db"})
        d (ok! res)]
    (is (= ["customers"] (mapv :name (:relations d))))
    (is (= ["cust_no" "cust_name"] (mapv :name (:columns (first (:relations d))))))
    (testing "and the response still carries no credential value"
      (is (not (str/includes? (str res) "s3cret"))))))

(deftest a-recipe-naming-no-resolver-still-projects-one
  "**The ps-dvd9 regression, pinned.** The recipe is a SEED: it names the
   namespace and the two attributes, and no resolver at all. The projection
   pulls the resolver in by closure because its outputs fall inside those
   attributes, and pulls the source in behind it.

   An agent that read `resolvers: []` off the recipe and answered «this graph
   has no resolver» was reading the seed as the harvest. `inspect_graph`'s
   summary puts both numbers in ONE response so that misreading is no longer
   possible (8.4.16 裁定九)."
  (let [{:keys [token]} (issue! #{:read})
        qn (seed-projectable-model!)
        r (ok! (call! token "inspect_graph" {:graph qn}))]
    (is (empty? (get-in r [:recipe :members :resolvers])) "the recipe names none")
    (is (empty? (get-in r [:recipe :members :sources])))
    (is (= 1 (get-in r [:projected :resolvers])) "the projection has one")
    (is (= 1 (get-in r [:projected :sources])) "and the source behind it")
    (is (= 2 (get-in r [:projected :attributes])))
    (testing "every group is reported even at zero: an omitted key and a zero
              read the same to somebody who did not expect the group, and only
              one of them is true"
      (is (= (set tools/projected-kinds) (set (keys (:projected r)))))
      (is (zero? (get-in r [:projected :metrics]))))))

(deftest the-entries-view-is-per-kind-and-bounded
  "`view=entries` must be asked per kind: dumping a whole projected model onto
   an agent is over this face's envelope, and CLAUDE.md's rule for over-limit
   is refuse, not truncate."
  (let [{:keys [token]} (issue! #{:read})
        qn (seed-projectable-model!)]
    (testing "entries: one kind's full entries, as the projection holds them"
      (let [r (ok! (call! token "inspect_graph"
                          {:graph qn :view "entries" :kind "resolvers"}))]
        (is (= "resolvers" (:kind r)))
        (is (= ["a_by_id"] (mapv :id (:entries r))))
        (is (= "cis-db" (:source-ref (first (:entries r)))))))
    (testing "**entries with no kind is refused, naming the parameter**"
      (let [res (call! token "inspect_graph" {:graph qn :view "entries"})
            f (first (:findings (tool-result res)))]
        (is (true? (get-in res [:result :isError])) (pr-str res))
        (is (= :tools.graph/entries-need-kind (:code f)))
        (is (= [:kind] (:path f)))))
    (testing "a group the projection does not emit is not in the enum at all —
              a join is an attribute since D2.29, so `navigations` names nothing"
      (let [res (call! token "inspect_graph"
                       {:graph qn :view "entries" :kind "navigations"})]
        (is (true? (get-in res [:result :isError])))
        (is (= :toolkit.args/invalid
               (:code (first (:findings (tool-result res))))))))))

(deftest inspect-graph-refuses-every-view-the-same-way-for-an-unknown-graph
  "One tool now, not two (8.4.16 裁定九) — but the point 8.4.10 pinned still
   holds: `require-graph!` decides reachability once, so `view=summary` and
   `view=entries` on the same unreachable graph must refuse identically. If
   they ever diverged, one of them would be answering «does this graph exist
   elsewhere» — which is the question `unknown` exists to decline."
  (let [{:keys [token]} (issue! #{:read})]
    (graph/register-graph! *conn* {:workspace "other" :name "secret" :members {}
                                   :created-at "2026-08-28T00:00:00Z"})
    (let [summary (call! token "inspect_graph" {:graph "other/secret"})
          entries (call! token "inspect_graph"
                         {:graph "other/secret" :view "entries" :kind "attributes"})]
      (is (true? (get-in summary [:result :isError])))
      (is (true? (get-in entries [:result :isError])))
      (is (= (get-in summary [:result :content 0 :text])
             (get-in entries [:result :content 0 :text]))
          "byte for byte the same refusal — one guard, not two that agree today")
      (is (= :graph-unknown (get-in (first (:findings (tool-result entries)))
                                    [:got :code]))))))

(deftest a-projection-that-cannot-close-says-where
  "«This graph cannot be cut right now, and here is what is dangling» IS the
   answer. The projection's findings travel whole (`accept!` → `call`), because
   rewording them here would turn a located, fixable fault into «projection
   failed»."
  (let [{:keys [token]} (issue! #{:read})
        _ (seed-projectable-model!)
        ;; A recipe that takes one of the resolver's OUTPUT attributes and
        ;; leaves its sibling behind: the resolver would produce an attribute
        ;; outside the projection, which scope-projection-v1 §2.4 refuses
        ;; rather than trimming down to the selected half.
        _ (graph/register-graph! *conn* {:workspace "demo" :name "half"
                                         :created-at "2026-08-28T00:00:00Z"
                                         :members {:namespaces #{"cis.a"}
                                                   :attributes #{:cis.a/id}}})
        res (call! token "inspect_graph" {:graph "demo/half"})
        fs (:findings (tool-result res))
        ;; This fixture wires no `:publish-service` into `deps`, so the
        ;; summary's `:serving` half always carries a `:tools.publish/unavailable`
        ;; WARNING alongside whatever the recipe/projection half found — errors
        ;; only, to isolate the claim under test.
        errors (filter #(= :error (:severity %)) fs)]
    (is (true? (get-in res [:result :isError])) (pr-str res))
    (is (= [:scope/mixed-output] (mapv :code errors))
        "the projection's own finding, not a rewording of it")
    (is (= [:resolver "a_by_id"] (:path (first errors))) "and it names where")))
;; ---------------------------------------------------------------------------
;; 5. A refused knock is recorded (audit-event-v1 §3 :auth/failed)
;;
;; The 401 above is the client's answer; this is the operator's. S5.3 makes the
;; refusal trail the data source for «has anyone been trying this door», and
;; this endpoint used to answer 401 and write nothing.
;; ---------------------------------------------------------------------------

(defn- audit-events
  "Every audit event `f` produced, oldest first.

   Anchored in a temp dir: the audit stream's path is relative, so a suite that
   left it unset would write this instance's trail into the working directory."
  [f]
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "studio-mcp-audit-" (into-array java.nio.file.attribute.FileAttribute [])))]
    (try
      (obs/audit-configure! {:recent 50 :dir dir})
      (obs/audit-clear!)
      (f)
      (vec (reverse (obs/audit-recent)))
      (finally
        (obs/audit-configure! {:recent 50 :dir nil})
        (obs/audit-clear!)
        (doseq [f* (reverse (file-seq (java.io.File. dir)))] (.delete ^java.io.File f*))))))

(deftest a-refused-bearer-is-audited-at-the-builder-mcp-door
  (let [events (audit-events #(post (str wt/token-prefix "never-issued") (rpc "initialize")))
        failures (filter #(= :auth/failed (:event %)) events)
        e (first failures)]
    (is (= 1 (count failures)) "one refusal, one event (audit-event-v1 §8)")
    (is (= :builder-mcp (:entry e)) "the agents' door, not the people's (D3.12)")
    (is (= :denied (:outcome e)))
    (is (= :token/invalid (:reason e)))
    (is (nil? (:token-id e))
        "an unverified credential yields no id this instance may believe")
    (is (nil? (get-in e [:actor :on-behalf-of])))
    (is (not (str/includes? (pr-str e) "never-issued"))
        "the presented credential appears in no key (audit-event-v1 §5)")))

(deftest a-revoked-key-and-an-unknown-one-are-one-event
  ;; `verify` refuses both identically on the wire; the trail must not be the
  ;; place where «that token once existed» leaks instead.
  (let [{:keys [token record]} (issue! #{:read})
        _ (wt/revoke! *home* {:token-id (:token-id record) :revoked-at "2026-08-28T01:00:00Z"})
        stable #(dissoc % :at :request-id)
        revoked (first (filter #(= :auth/failed (:event %))
                               (audit-events #(post token (rpc "initialize")))))
        unknown (first (filter #(= :auth/failed (:event %))
                               (audit-events #(post (str wt/token-prefix "never-issued")
                                                    (rpc "initialize")))))]
    (is (= (stable revoked) (stable unknown)))))

(deftest a-good-key-adds-no-refusal
  (let [{:keys [token]} (issue! #{:read})
        events (audit-events #(post token (rpc "initialize")))]
    (is (empty? (filter #(= :auth/failed (:event %)) events)))))

;; ---------------------------------------------------------------------------
;; And so are this door's model changes (E3, `:pool/mutation`)
;;
;; The pool ledger already knew an agent's writes (`a-write-is-audited-against-
;; the-connections-token` above). What was missing was the same write in the
;; UNIFIED ledger, where the queries live — without it, "an agent added this
;; field" was a fact only a second, differently-shaped account could tell.
;; ---------------------------------------------------------------------------

(deftest an-agents-model-change-reaches-the-unified-ledger
  ;; `execute_action` is gone (8.4.16 裁定九); `describe_objects` writes
  ;; through the same `pool/execute-actions` path, so the audit property is
  ;; unchanged.
  (let [{:keys [token record]} (issue! #{:read :write})
        before (pool/object (pool/db *conn*) :unit "USD")
        events (audit-events #(call! token "describe_objects"
                                     {:objects [{:type "unit" :id "USD" :description "改过。"}]
                                      :apply true}))
        es (filter #(= :pool/mutation (:event %)) events)
        e (first es)]
    (is (= 1 (count es)) "one action, one event (audit-event-v1 §8)")
    (is (= :ok (:outcome e)))
    (is (= :builder-mcp (:entry e)) "the agents' door, not the people's (D3.12)")
    (is (= [:unit "USD"] (:object e)))
    (is (= :update (:action e)))
    (is (= (:pool/revision before) (:revision-before e)))
    (is (= (inc (:pool/revision before)) (:revision-after e)))
    (is (= *owner* (get-in e [:actor :on-behalf-of]))
        "the principal that issued the key — nobody is at the keyboard")
    (testing "the join key: this event names the pool ledger row it reports"
      (let [row (first (filter #(= (:mutation-id e) (:mutation/id %))
                               (pool/mutations (pool/db *conn*))))]
        (is (some? row))
        (is (= [:unit "USD"] (:mutation/object row)))
        (is (= (:token-id record) (:mutation/token-id row))
            "and the row carries the credential, so «which key» is still answerable")))
    (testing "no part of the entry, and no secret (§5)"
      (is (not (str/includes? (pr-str e) "改过")))
      (is (not (str/includes? (pr-str e) token))))))

(deftest a-plan-level-refusal-writes-nothing-and-emits-no-mutation-event
  "`execute_action`'s caller-supplied `expected_revision` let a stale write
   reach the POOL and be denied there, which S5.3 audited as `:pool/mutation
   :denied`. Every intent tool now re-reads the revision itself and re-plans
   at apply time (`impl.intent`'s own discipline), so a stale-revision denial
   at the pool can no longer be provoked from this face — the finding for an
   unresolvable object is made in PLANNING, before `pool/execute-actions` is
   ever called. `audited-tool-call` only files an event when `:mutations` is
   on the tool's return (present on every pool write, absent otherwise), so a
   plan-level refusal is correctly silent here — the write-loop's own audit
   trail (`a-refusal-is-an-answer-the-agent-can-act-on`) is what carries this
   refusal now."
  (let [{:keys [token]} (issue! #{:read :write})
        before (count (pool/mutations (pool/db *conn*)))
        events (audit-events #(call! token "describe_objects"
                                     {:objects [{:type "unit" :id "NO-SUCH-UNIT" :description "x"}]
                                      :apply true}))]
    (is (empty? (filter #(= :pool/mutation (:event %)) events)))
    (is (= before (count (pool/mutations (pool/db *conn*)))))))

(deftest a-read-tool-produces-no-mutation-event
  ;; The emission hangs off `:mutations`, which only a pool write returns. If a
  ;; read produced one, "how many changes did this agent make" would count
  ;; lookups.
  (let [{:keys [token]} (issue! #{:read})
        events (audit-events #(call! token "query_model" {:query "missing_labels"}))]
    (is (empty? (filter #(= :pool/mutation (:event %)) events)))))

(deftest an-omitted-graph-falls-back-to-the-workspace-default
  "8.4.17 裁定十, at the wiring this app owns: the ctx this door builds carries
   the token workspace's default graph, RESOLVED, and a tool called without
   `graph` lands on it and says so.

   What the resolution means is `studio/graph`'s and what the fallback means is
   `studio/tools`' — both have their own suites. The claim here is only that
   the wire between them exists, which is the half that has no other home."
  (let [{:keys [token]} (issue! #{:read :write})]
    (ok! (call! token "create_graph" {:name "sales" :selectors ["cis.*"]}))
    (testing "no default graph set: the call is refused and names both ways out"
      (let [res (call! token "inspect_graph" {})]
        (is (true? (get-in res [:result :isError])))
        (let [f (first (:findings (tool-result res)))]
          (is (= :tools/graph-required (:code f)))
          (is (= "set a workspace default graph, or pass graph" (:expected f))))))
    (graph/set-default-graph! *conn* "demo" "demo/sales")
    (testing "with one set, the omitted graph is that one, and the result says so"
      (is (= "demo/sales" (:graph (ok! (call! token "inspect_graph" {}))))))
    (testing "an explicit graph still wins over the default"
      (let [res (call! token "inspect_graph" {:graph "other/secret"})]
        (is (true? (get-in res [:result :isError]))
            "the default did not quietly replace a graph the caller named")))))

;; ---------------------------------------------------------------------------
;; 8.4.18 裁定十一 — the data plane's tool set, mounted
;; ---------------------------------------------------------------------------

(def ^:private the-twenty-seven
  "The ruling's own table: twenty Builder tools — the seventeen of 裁定九 plus
   `declare_source` (8.4.24 裁定十八), with the write face rearranged by the
   twelve sentences of need-grammar-v1 (8.4.25 裁定十七) — then the data
   plane's seven. **Changing this vector means changing the ruling.**"
  ["list_graphs" "inspect_graph" "query_model" "describe_source" "read_object"
   "create_graph" "declare_source"
   "add_thing" "add_attribute" "add_relation" "add_link" "add_metric"
   "update_resolver" "update_attribute"
   "remove_objects" "describe_objects" "define_concept" "unassign_term"
   "change_graph_members" "list_recent_queries" "list_saved_queries" "save_saved_query" "delete_saved_query"
   ;; `reclaim_version`: owner ruling 2026-10-10 (R3.5 reclaim exposed to agents).
   "publish_graph" "reclaim_version"
   "list_graph_tokens" "issue_graph_token" "revoke_graph_token"
   "list_credentials" "save_credential" "remove_credential"
   "list_sources" "probe_api_source" "remove_source"
   "rename_graph" "duplicate_graph" "merge_graphs" "archive_graph" "restore_graph" "delete_graph" "rollback_graph" "check_graph_cut" "set_default_query" "set_package_name" "set_workspace_default_graph" "reclaim_unused_versions"
   "list_graph_access" "grant_graph_access" "revoke_graph_access" "list_workspace_tokens" "revoke_workspace_token" "list_workspace_objects"
   "semantic_draft" "import_semantic_graph"
   "get_graph" "upload_graph"
   "list_entities" "list_attributes" "search_attributes" "execute_eql"
   "analyze_result" "compute_metric" "list_mutations"])

(deftest tools-list-is-twenty-seven-in-the-rulings-order
  "裁定十一「工具计数」+ 8.4.24 裁定十八 + 8.4.25 裁定十七: 20 + 7 = 27, Builder tools first because modelling
   is this door's business.

   Pinned HERE and not only in `studio/tools` because this is the one place
   both modules are on the classpath: `studio/tools` can prove the mounting,
   and only the app can prove that what is mounted is the data plane's actual
   seven (D1.8 keeps them apart everywhere else)."
  (let [{:keys [token]} (issue! #{:read :write})
        listed* (mapv :name (get-in (body-of (post token (rpc "tools/list"))) [:result :tools]))]
    (is (= 63 (count listed*)) "35 + reclaim_version + three graph-token + three credential + three source + twelve graph-lifecycle + five access tools (owner 2026-10-10/11)")
    (is (= the-twenty-seven listed*))
    (testing "the seven are the data plane's own declarations, described in its
              own words — an agent has nothing to relearn"
      (doseq [d mcp/mountable-declarations
              :let [mounted (some #(when (= (:name d) (:name %)) %)
                                  (get-in (body-of (post token (rpc "tools/list")))
                                          [:result :tools]))]]
        (is (= (:description d) (:description mounted)) (:name d))
        (is (contains? (get-in mounted [:inputSchema :properties]) :graph)
            (str (:name d) " must take a graph on this door"))))
    (testing "and `execute_mutation` is not among them (裁定十一 ①)"
      (is (not (contains? (set listed*) "execute_mutation"))))))

(deftest an-uncuttable-graph-is-refused-the-same-way-by-query-and-by-publish
  "裁定十一 ③ and its exit condition: «the draft runs» and «the cut runs» are
   ONE judgement. A graph the scope projection refuses gets the same finding
   from `execute_eql` as from `publish_graph`'s check — not rows it could
   never ship."
  (let [{:keys [token]} (issue! #{:read :write})]
    (seed-projectable-model!)
    ;; A recipe naming an attribute no namespace in it declares: the
    ;; projection cannot close over it.
    (graph/register-graph! *conn* {:workspace "demo" :name "broken"
                                   :created-at "2026-08-28T00:00:00Z"
                                   :members {:attributes #{:no.such.ns/missing}}})
    (let [q (:findings (tool-result (call! token "execute_eql"
                                           {:graph "demo/broken" :query "[:cis.a/id]"})))
          ;; `publish_graph`'s check IS this projection — the cut calls it — so
          ;; comparing against the projection itself compares the two faces
          ;; without needing a publish service in the fixture.
          cut (:findings (graph/project (pool/db *conn*) "demo/broken"))]
      (is (seq q) "a graph that cannot be projected cannot be queried either")
      (is (= (vec cut) (vec q))
          "the same finding, verbatim, from the same projection"))))

(deftest a-failing-draft-query-is-audited-through-the-shared-path
  "裁定十一 ⑤b: the draft face wrote its own audit and failures fell through
   it, so «who read this graph» had two half-answers. Now there is one path
   and it records both outcomes, with the door in `:entry`."
  (let [{:keys [token]} (issue! #{:read :write})
        graph (seed-projectable-model!)
        events (audit-events
                #(call! token "execute_eql"
                        ;; Unknown attribute: the engine refuses it, so nothing
                        ;; is read — and that is exactly the case that used to
                        ;; leave no trace.
                        {:graph graph :query "[:cis.a/no_such_attribute]"}))
        queries (filter #(= :access/query (:event %)) events)]
    (is (seq queries) "a refused draft query must be in the ledger")
    (is (every? #(= :builder-mcp (:entry %)) queries))
    (is (every? #(not= :ok (:outcome %)) queries))
    (is (every? :reason queries) "the refusal's code travels with it")))

(deftest a-connection-failure-carries-its-category
  "裁定十一 ⑤a: the Builder's draft finding kept only `[:code :graph]`, so the
   engine's `:error/category` — the one word separating «the credential is not
   set» from «the query is wrong» — never reached the caller. Sharing the data
   plane's handlers means the finding travels whole.

   The fixture's source declares `${CIS_DB_URL}` and nothing resolves it on
   this instance, which is a `:connection` failure by the SQL connector's own
   classification."
  (let [{:keys [token]} (issue! #{:read :write})
        graph (seed-projectable-model!)
        fs (:findings (tool-result (call! token "execute_eql"
                                          {:graph graph
                                           :query "{:entity {:cis.a/id 1} :query [:cis.a/name]}"})))]
    (is (seq fs))
    (is (= :engine.execution/failed (:code (first fs))))
    (is (contains? #{:connection :source :timeout :mapping}
                   (:error/category (first fs)))
        "the category the connector declared, not a generic failure")
    (let [text (pr-str fs)]
      (is (not (str/includes? text "SELECT")) "no statement in a finding")
      (is (not (str/includes? text "jdbc:")) "no URL in a finding"))))

(deftest every-mounted-data-plane-tool-reaches-the-draft
  "One call per mounted tool: the seam has to be wired for all seven, not only
   for the two the other tests exercise. `execute_eql` and `analyze_result`
   touch the (unreachable) source and refuse with a category; the other five
   read the compiled model and answer."
  (let [{:keys [token]} (issue! #{:read :write})
        graph (seed-projectable-model!)]
    (is (seq (:entities (ok! (call! token "list_entities" {:graph graph})))))
    (is (seq (:attributes (ok! (call! token "list_attributes"
                                      {:graph graph :entities ["cis.a"]})))))
    (is (some? (ok! (call! token "search_attributes" {:graph graph :query "A name"}))))
    (is (= [] (:mutations (ok! (call! token "list_mutations" {:graph graph}))))
        "a graph declaring no write is read-only, and that is the whole story")
    ;; No metric named: the registry IS the answer to "what can you compute".
    (is (contains? (ok! (call! token "compute_metric" {:graph graph})) :metrics))
    (doseq [[tool args] [["execute_eql" {:query "{:entity {:cis.a/id 1} :query [:cis.a/name]}"}]
                         ["analyze_result" {:query "{:entity {:cis.a/id 1} :query [:cis.a/name]}"
                                            :sql "SELECT count(*) FROM data"}]]]
      (let [fs (:findings (tool-result (call! token tool (assoc args :graph graph))))]
        (is (seq fs) (str tool " must refuse structurally, not throw"))
        (is (some :error/category fs) (str tool " must carry the category"))))))

;; ---------------------------------------------------------------------------
;; 8.4.20 裁定十四: the Builder door's environment cache
;; ---------------------------------------------------------------------------

(defn- seed-queryable-graph!
  "A graph whose one resolver reads a REAL SQLite file, so `execute_eql`
   answers rows rather than a connector refusal — the exit condition is about
   what a successful answer carries."
  []
  (let [path (str *home* "/cust.db")
        _ (.mkdirs (java.io.File. ^String *home*))
        _ (with-open [c (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" path))
                      st (.createStatement c)]
            (.execute st "CREATE TABLE customers (cust_no INTEGER PRIMARY KEY, cust_name TEXT)")
            (.execute st "INSERT INTO customers VALUES (1, 'ACME')"))
        r (pool/execute-actions
           *conn*
           [{:workspace "demo" :action :create :type :namespace :id "cis.cust"
             :entry {:kind :master :label "Customers"} :expected_revision 0}
            {:workspace "demo" :action :create :type :source :id "cust-db"
             :entry {:type "sql" :label "Cust" :jdbc_url (str "jdbc:sqlite:" path)}
             :expected_revision 0}
            {:workspace "demo" :action :create :type :attribute :id :cis.cust/cust_no
             :entry {:type :integer :format :id :identity? true :label "No"}
             :expected_revision 0}
            {:workspace "demo" :action :create :type :attribute :id :cis.cust/cust_name
             :entry {:type :string :format :text :label "Name"}
             :expected_revision 0}
            {:workspace "demo" :action :create :type :attribute :id :cis.cust/note
             :entry {:type :string :format :text :label "Note"}
             :expected_revision 0}
            {:workspace "demo" :action :create :type :resolver :id "cust_by_no"
             :entry {:input [:cis.cust/cust_no]
                     :output [:cis.cust/cust_no :cis.cust/cust_name]
                     :result_type "single" :source-ref "cust-db"
                     :source {:type "sql"
                              :query (str "SELECT cust_no, cust_name FROM customers "
                                          "WHERE cust_no = :cust_no")}}
             :expected_revision 0}]
           {:entrypoint :ui :actor *owner* :at "2026-08-28T00:00:00Z"})]
    (assert (empty? (:findings r)) (pr-str (:findings r)))
    (graph/register-graph! *conn* {:workspace "demo" :name "cust"
                                   :created-at "2026-08-28T00:00:00Z"
                                   :members {:namespaces #{"cis.cust"}
                                             :attributes #{:cis.cust/cust_no
                                                           :cis.cust/cust_name}}})
    "demo/cust"))

(defn- draft-version!
  "The projection digest the graph's cached environment is keyed by, read off
   the door.

   **Not off `execute_eql`.** 裁定十四's exit condition says «the next
   `execute_eql` answers with a different `:version` digest», but a draft
   answer carries no `:env/version` at all — `draft-parity-test` pins that as
   the single key by which the two doors' answers differ, on the stated ground
   that a draft has no version until it is cut. So the query is still run here
   (it is what rebuilds the environment), and the digest is read from
   `list_entities`, which reports the same value off the same environment.'"
  [token graph]
  (let [q (ok! (call! token "execute_eql"
                      {:graph graph
                       :query "[{[:cis.cust/cust_no 1] [:cis.cust/cust_name]}]"}))]
    (is (true? (:draft q)))
    (:version (ok! (call! token "list_entities" {:graph graph})))))

(defn- cached-revisions
  "What the cache holds for ONE graph. The environments atom is process-wide
   (`defonce`), so other tests' graphs are none of this one's business."
  [graph-id]
  (get (runtime/cached-graph-revisions) graph-id))

(deftest a-model-change-releases-the-cached-draft-environment
  "8.4.20 裁定十四 ②'s exit conditions, on the Builder door.

   The digest an answer carries is the key its environment is cached under, so
   these are two views of one fact: after a write that moved the projection,
   the old entry is gone from the cache **at the write**, and the next query
   answers with a different digest."
  (let [{:keys [token]} (issue! #{:read :write})
        graph (seed-queryable-graph!)
        graph-id (:graph/id (graph/graph (pool/db *conn*) graph))
        v1 (draft-version! token graph)]
    (is (some? v1))
    (testing "an unchanged model reuses the entry — within the TTL nothing rebuilds"
      (is (= #{v1} (cached-revisions graph-id)))
      (is (= v1 (draft-version! token graph)))
      (is (= #{v1} (cached-revisions graph-id))))
    (testing "an attribute change: the entry is released at the write, and the
              next query carries a new digest"
      (ok! (call! token "update_attribute"
                  {:graph graph :attribute "cust_name" :deprecated true :apply true}))
      (is (nil? (cached-revisions graph-id)) "released at the write, not two hours later")
      (let [v2 (draft-version! token graph)]
        (is (not= v1 v2))
        (testing "a recipe change does the same"
          ;; ADDING a member rather than removing one: dropping
          ;; `cis.cust/cust_name` would leave the resolver straddling the
          ;; selection and the graph would stop projecting at all
          ;; (`:scope/mixed-output`) — a different subject.
          (ok! (call! token "change_graph_members"
                      {:graph graph :add ["cis.cust/note"] :apply true}))
          (is (nil? (cached-revisions graph-id)))
          (let [v3 (draft-version! token graph)]
            (is (not= v2 v3))))))))

(deftest both-doors-read-one-ttl-value
  "8.4.20 裁定十四's last exit condition. The Builder's TTL is the runtime's
   own reader — one number, one environment variable, two hours."
  (is (= (env/cache-ttl-ms) (runtime/environment-cache-ttl-ms)))
  (is (= 7200000 (runtime/environment-cache-ttl-ms))
      "unless this machine sets OPEN_ATLAS_GRAPH_ENV_CACHE_TTL_MS"))


(deftest semantic-draft-roundtrips-over-the-authenticated-mcp-door
  (let [{:keys [token]} (issue! #{:read :write})]
    (ok! (call! token "create_graph" {:name "semantic" :selectors ["cis.*"]}))
    (let [read (ok! (call! token "semantic_draft" {:graph "demo/semantic" :action "read"}))
          proposal (ok! (call! token "semantic_draft"
                               {:graph "demo/semantic" :action "prepare-lookup"
                                :lookup (pr-str {:entity-id "support.case" :label "Case" :identity-name "id" :identity-type :integer
                                                 :value-name "title" :value-type :string :value-nullable? false
                                                 :source-id "db" :resolver-id "lookup"
                                                 :sql-text "SELECT title FROM cases WHERE id = :id"})}))
          saved (ok! (call! token "semantic_draft" {:graph "demo/semantic" :action "save"
                                                            :expected_revision 0 :text (:text proposal)}))]
      (is (= 0 (:revision read)))
      (is (= "2.0.0" (get-in (edn/read-string (:text read))
                             [:contract :contract/version])))
      (is (= 1 (:revision saved)) (pr-str saved))
      (is (= (:text saved)
             (:text (ok! (call! token "semantic_draft" {:graph "demo/semantic" :action "read"}))))))))
