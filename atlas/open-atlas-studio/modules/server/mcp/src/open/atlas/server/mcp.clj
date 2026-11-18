(ns open.atlas.server.mcp
  "MCP over Streamable HTTP — the data plane's **second skin**.
   Cross-module callers may only require this ns.

   ## Why this is a shell and not a service

   `/eql` and `/mcp` answer the same question in two languages: one speaks
   canonical EDN to a program, the other JSON-RPC to an agent. Underneath,
   both call `runtime/instance`'s `answer` — the single enforcement point
   (D5.4). This module therefore makes **no** judgement of its own: grant
   resolution is `common/token`, version pinning is `runtime/env`, execution
   is `common/engine`. Re-implementing any of them here would be precisely the
   'second set of semantics' D1.13 forbids — and it would be the dangerous
   kind, because an agent asking 'what may I read' would be answered by a
   different mechanism than the one that enforces it.

   ## Which is why it lives in modules/server, not apps/studio

   One runtime, two product shapes (D1.13). Mounting these routes in the
   Studio app would give the embedded runtime an MCP surface the standalone
   Server does not have — two shapes that no longer behave alike. Here, both
   assemble the same shell: Studio's `-main` and `apps/server`'s composition
   root each mount it, and an agent cannot tell which one it is talking to.
   That indistinguishability is the point: what you test against Studio is
   what production does.

   ## The wire moved down (84 P1a), and the registry followed (84 P4)

   JSON-RPC framing, version negotiation, batching and the two remote
   transports live in `common/mcp-transport`; the tool list and dispatch are
   `common/toolkit`'s single registry, built from `impl/tools`'s declarations.
   What is left in this ns is assembly and nothing else:

   - `authenticate` — the data plane always needs a graph token (D5.7);
   - the ctx a handler receives;
   - the instructions and server-info an agent plans against.

   Before P4 this module announced its tools from one vector and dispatched
   them from a `case` over the tool name. Two lists of the same eight names is
   the drift a single registry exists to prevent, and it is invisible until a
   client calls the one that was not updated.

   **The capability filter has nothing to filter here, and that is correct.**
   A data-plane caller holds one kind of key and the graph IS the grant
   (D5.13): a caller may run exactly the mutations their graph declares, so
   there is no second permission to carry. The registry is therefore built with
   both capabilities and `execute_mutation` is still declared `:write` —
   honestly, because it writes — rather than being called a read to make a
   filter come out right.

   ## Deps

   - `:instance` — the runtime parts (`runtime/instance` start!'s result);
   - `:now` — a 0-arg fn returning an ISO instant string (injected so the
     shell is testable);
   - `:sse-writer` — wraps a `(fn [^OutputStream out])` into whatever the
     host's HTTP adapter accepts as a streaming body. Injected because the
     streaming protocol belongs to the adapter, not to MCP;
   - `:rate-limit?` — optional `(fn [token-id] {:allowed? bool
     :retry-after-secs n})`. Absent means no limiting, which is a visible
     config fact rather than a silent default."
  (:require [clojure.string :as str]
            [open.atlas.common.mcp-transport :as wire]
            [open.atlas.common.toolkit :as toolkit]
            [open.atlas.runtime.graph :as graph]
            [open.atlas.runtime.token :as token]
            [open.atlas.server.mcp.impl.catalog :as catalog]
            [open.atlas.server.mcp.impl.instructions :as instructions]
            [open.atlas.server.mcp.impl.saved-query :as saved-query]
            [open.atlas.server.mcp.impl.tools :as tools]))

(def ^:private registry
  "One registry, built once. See the ns docstring on why both capabilities are
   present: the data plane's permission is the graph, not the key's scope."
  (toolkit/registry tools/declarations #{:read :write}))

(def ^:private registry-with-saved-queries
  "The same registry plus the two saved-query tools, for a host that keeps
   saved queries (`:saved-queries` in deps). Chosen once per host, so
   `tools/list` and dispatch still come from one registry."
  (toolkit/registry (into tools/declarations tools/saved-query-declarations) #{:read :write}))

(defn- registry-for [deps]
  (if (:saved-queries deps) registry-with-saved-queries registry))

(def ^:private server-info {:name "open-atlas-mcp" :version "1"})

(def ^:private unauthorized-message
  "Named here rather than in the wire because the honest sentence differs per
   endpoint: this one tells the caller which KIND of key is missing, and the
   control plane's would name a different one."
  "Unauthorized — the data plane always needs a graph token")

(defn- authenticate
  "Verify the bearer. → `{:id token-id :ctx credential}`, or nil.

   The credential rides in `:ctx` because every tool call needs it (the graph
   is resolved FROM it, never from anything the caller asserts). It is
   deliberately not the `:id`: the wire keeps `:id` in its SSE session map,
   where a secret would outlive the request and become a place to leak from
   (S5.2)."
  [deps request]
  (when-let [c (wire/bearer request)]
    (let [v (token/verify (:token (:instance deps)) c {:now ((:now deps))})]
      (when (seq (:findings v))
        ;; Only codes cross the diagnostics boundary: findings may contain
        ;; caller-supplied values, and credentials must never enter logs.
        (when-let [report! (:on-auth-failure deps)]
          (report! {:reason :token-rejected
                    :finding-codes (mapv :code (:findings v))})))
      (when-not (seq (:findings v))
        ;; The ctx every handler receives. The credential rides in it because
        ;; the graph is resolved FROM it, never from anything the caller
        ;; asserts; the clock rides in it so the handlers stay testable.
        {:id (:token-id v)
         :authorization (:authorization v)
         :ctx {:parts (:instance deps) :credential c :now ((:now deps))
               :saved-queries (:saved-queries deps)
               :source {:remote-addr (:remote-addr request) :path (:uri request)
                        :user-agent (get-in request [:headers "user-agent"])}}}))))

(defn- deps->wire
  "This module's half of the seam: assembly only. The list and the dispatch
   both come from `registry`, so they cannot disagree."
  [deps]
  (let [registry (registry-for deps)]
  (assoc deps
         :authenticate #(authenticate deps %)
         :tools-list (fn [_ctx] (toolkit/tools-list registry))
         ;; `as-tool-result` is the ONE place finding-v1 and the MCP result
         ;; convention meet (8.4 P4). Without it a refusal is delivered to the
         ;; agent as a success — which is exactly what happened the first time
         ;; the Builder's endpoint was wired up.
         :call-tool (fn [ctx tool arguments]
                      (toolkit/as-tool-result (toolkit/dispatch registry (assoc-in ctx [:source :tool] tool) tool arguments)))
         :server-info server-info
         :instructions instructions/instructions
         :unauthorized-message unauthorized-message)))

(defn routes
  "Ring handler. Keeps the legacy endpoints and adds /mcp/<graph-uuid> for
   HTTP clients whose credential store selects credentials by URL. Names are
   never accepted as IDs on the new route."
  [deps]
  (let [wire-deps (deps->wire deps)
        legacy (wire/routes wire-deps)]
    (fn [request]
      (or (legacy request)
          (when-let [[_ path] (re-matches #"/mcp/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/?" (:uri request))]
              (if-not (= :post (:request-method request))
                (wire/handle-get request)
                (let [graph-id (str/lower-case path)
                      auth (authenticate deps request)
                      authorization (:authorization auth)]
                  (cond
                    (and auth (not= graph-id (graph/graph-uuid
                                             (:graph (:instance deps))
                                             (:graph-id authorization) (:version authorization))))
                    (do
                      (when-let [report! (:on-auth-failure deps)]
                        (report! {:reason :graph-mismatch :token-id (:id auth)
                                  :graph-uuid graph-id}))
                      {:status 403 :body "Token does not belong to the requested graph"})

                    :else
                    ;; Let the shared transport own 401, Origin validation,
                    ;; rate limits and dispatch. Verify the token only once.
                    (wire/handle-post (assoc wire-deps :authenticate (constantly auth)) request)))))))))

(def tool-definitions
  "The tool declarations — what an agent is told, and what dispatch runs, as
   one value (8.4 P4). Exported so a host can show them without opening a
   session."
  tools/declarations)

;; ---------------------------------------------------------------------------
;; Catalog rendering, exported for the Builder's draft face (8.4.13)
;; ---------------------------------------------------------------------------
;;
;; **Why the data plane exports this at all.** The Builder had the execution
;; half of the loop (`evaluate_draft_eql`, 8.4.12) and no discovery half: an
;; agent asked "how do I start a query on this graph" had to dump every
;; resolver entry and look for `:input []` by eye. The rendering that answers
;; it already exists here, is a pure function of a compiled env, and touches
;; neither token nor version pinning — so the Builder gets THE SAME function
;; over its draft rather than a second rendering of its own. A second one is
;; what D1.13 forbids, and it would be the dangerous kind: two answers to
;; "what exists" that drift apart.
;;
;; What is NOT shared is the endpoint. `/mcp` here is a graph token pinned to
;; a PUBLISHED version (`impl.catalog`'s own docstring says so); the Builder
;; asks about the draft, which that door cannot see. Hence three forwarded
;; functions and no new route.
;;
;; These are `def` forwards, not re-implementations — `impl.catalog` stays the
;; only place the rendered shape is decided, because an agent has been reading
;; those keys for a long time and two renderings would eventually disagree.

(def catalog-of
  "`(catalog-of compiled)` → `{:catalog … :param-decls …}`, both off the same
   compiled env the query path uses. `compiled` is `engine/compile-env`'s
   result, whether it came from a pinned artifact or a draft projection."
  catalog/of)

(def entity-registry
  "`(entity-registry catalog)` → the entity descriptors `list_entities`
   renders: `:entity`, `:description`, `:attribute_count`, `:kind`,
   `:identity_keys`. An entity with nothing reachable in it is omitted."
  catalog/entity-registry)

(def attributes-for-entities
  "`(attributes-for-entities catalog param-decls entity-names)` → the rows
   `list_attributes` renders: scalar attributes plus `:type \"join\"` rows
   carrying each join's declared `:params` / `:limit` / `:order`, and — since
   contract 2.0 — the producer's `:priority` / `:batch`, a union's `:targets`
   and a recursive join's `:recursion_depth`. An unknown entity name yields
   nothing rather than an error."
  catalog/attributes-for-entities)

;; ---------------------------------------------------------------------------
;; The seven read tools, mountable elsewhere (8.4.18 裁定十一)
;; ---------------------------------------------------------------------------
;;
;; **Why the data plane exports its own tools.** "Run a query against a graph"
;; had two implementations in this repository: this one, and the Builder's
;; `evaluate_draft_eql` over its own model assembly. Three layers differed —
;; model assembly, execution environment, tool face — and the symptom was a
;; query that passed on the data plane, failed on the draft, and had no layer
;; that could say what differed (D1.13's second set of semantics).
;;
;; So the Builder mounts THESE declarations: the same descriptions, the same
;; handlers, the same refusal shape. Only the environment differs, and it
;; differs through the one seam `impl.tools/token-runtime` names — a ctx key,
;; not a fork.
;;
;; `execute_mutation` is deliberately not in this set: 裁定九 put writing to a
;; source system back behind the GUI, and sharing a tool set is not a reason
;; for it to come back (裁定十一 ①).

(def runtime-seam
  "The ctx key a host sets to say **how these tools obtain an environment**,
   and the data plane's own value for it. See
   `open.atlas.server.mcp.impl.tools/token-runtime` for the two functions."
  tools/token-runtime)

(def ^:private mountable-names
  ["list_entities" "list_attributes" "search_attributes" "execute_eql"
   "analyze_result" "compute_metric" "list_mutations"])

(def mountable-declarations
  "The seven read tools, in 裁定十一's order, as toolkit declarations.

   A host mounts them by putting its own `{:pinned … :answer …}` map under
   `:runtime` in the ctx it hands `toolkit/dispatch`. It may add parameters of
   its own (the Builder adds `graph`) — what it may not do is re-describe or
   re-implement the call."
  (mapv (fn [n]
          (or (some #(when (= n (:name %)) %) tools/declarations)
              (throw (ex-info "data-plane tool declaration is missing" {:tool n}))))
        mountable-names))

;; ---------------------------------------------------------------------------
;; Saved queries (proposal studio-saved-queries, data-plane-api 1.2)
;; ---------------------------------------------------------------------------
;;
;; Exported because three places need the SAME answers: the data plane runs a
;; saved query, the Builder writes one and the publish step checks one. The
;; Builder and the publish step live in apps/studio, which may require this ns
;; and nothing under `impl` (D1.8).

(def saved-query-findings
  "`(saved-query-findings sq)` → finding-v1 vector for one saved query's shape."
  saved-query/findings)

(def saved-query-set-findings
  "`(saved-query-set-findings sqs)` → findings for a whole graph's set."
  saved-query/set-findings)

(def saved-query-stale-findings
  "`(saved-query-stale-findings sq known-attributes)` → findings for attributes
   the model does not have."
  saved-query/stale-findings)

(def bind-saved-query
  "`(bind-saved-query sq params)` → `{:request eql}` or `{:findings …}`."
  saved-query/bind)

(def saved-query-example-params
  "`(saved-query-example-params sq)` → the examples as a params map, or nil."
  saved-query/example-params)

(def describe-saved-query
  "`(describe-saved-query sq include-eql?)` → the listing shape."
  saved-query/describe)

(defn saved-query-request
  "For the `/eql` shell: `(fn [credential now name params] → {:request eql} |
   {:findings …})`, resolving the graph and version from the credential
   exactly as the MCP tools do. `deps` is the same map `routes` takes."
  [deps]
  (fn [credential now name params]
    (tools/saved-query-request {:parts (:instance deps) :credential credential :now now
                                :saved-queries (:saved-queries deps)}
                               {} name params)))
