(ns open.atlas.studio.builder-mcp
  "The Builder's **MCP endpoint**: the modelling registry on the wire
   ([8.4 P1b](../../../../../../docs/design/84-builder-mcp.md)).

   ## The shape of one call

       Authorization: Bearer oaw_v1.…
         → workspace-token/verify              (before any JSON-RPC parsing)
         → {:workspace … :scope … :token-id …}
         → toolkit registry built from THAT scope
         → tools/list or dispatch — one capability decision, not two
         → the tool's handler, with provenance the caller cannot reach
         → pool mutation audit

   Three properties are load-bearing, and each is a shape rather than a check:

   1. **The credential is consumed before parsing.** `common/mcp-transport`
      runs `:authenticate` on the raw request and hands the rest of the
      pipeline an opaque ctx. Nothing downstream — no tool argument, no
      finding, no trace — is in a position to carry the secret (ruling ③).
   2. **No write scope ⇒ the write tools are absent.** The scope IS the
      capability set handed to `toolkit/registry`, so `tools/list` and
      dispatch are computed from one value. A read-scoped caller is not told
      the write tools exist and refused; it is told nothing.
   3. **Provenance is in the ctx, never in the arguments.** The tool
      declarations have no `entrypoint` / `token_id` / `actor` parameter, so a
      client cannot even name them — forging an identity is unaddressable
      rather than filtered (pool `impl/audit.clj` ruling ①).

   ## Why this mounts on the Builder listener, never the data plane's

   D3.12 keeps the two doors apart so that a leaked query token cannot become
   a deploy permission. Mounting the modelling registry where graph tokens are
   accepted would undo exactly that, and D5.7's «the data plane has exactly one
   kind of key» would become a sentence with an exception in it.

   ## The adapter

   The Builder runs on `com.sun.net.httpserver`; the transport speaks Ring.
   The translation lives here and is deliberately small — it moves bytes and
   headers, and makes no decision of its own. It is not in `studio/http`
   because that ns is shared by every Builder route and this shape is only
   needed by this one."
  (:require [open.atlas.runtime.obs :as obs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [open.atlas.common.mcp-transport :as wire]
            [open.atlas.common.toolkit :as toolkit]
            [open.atlas.studio.workspace-token :as wt])
  (:import [com.sun.net.httpserver HttpExchange]
           [java.nio.charset StandardCharsets]))

(def server-info
  "Announced by `initialize`. Deliberately NOT the data plane's
   `open-atlas-mcp`: an agent with both endpoints configured must be able to
   tell from the handshake which one answered, or a misrouted write becomes a
   puzzle about why a tool 'disappeared'."
  {:name "open-atlas-builder-mcp" :version "1"})

(def ^:private unauthorized-message
  "Names the kind of key, because the honest failure for a caller holding a
   data-plane token is «wrong door», not «bad token» (workspace-token ns)."
  (str "Unauthorized — the Builder MCP endpoint needs a workspace token ("
       wt/token-prefix "…), issued in the Builder. A data-plane graph token is "
       "not accepted here."))

(def instructions
  "What an agent is told on `initialize`.

   Rule-shaped and short on purpose: the tool descriptions are the instruction
   sheet, and a second copy of that guidance here is a second thing to keep
   true. What belongs here is only what no single tool can say — the STANCE:
   this face exists to evaluate and improve a model, and every paragraph below
   is aimed at a named failure mode of an agent that forgot that.

   The most expensive of those failure modes arrived with the draft tools
   (8.4.12): an agent that can query data defaults to being a data analyst —
   it answers the question through a clever workaround and reports success.
   The workaround never reaches the modeller, because the answer looked right;
   the model stays wrong, now with evidence that it works. The inversion at
   the top is written against exactly that.

   **The vocabulary is need-grammar-v1's** (8.4.25 裁定十七): a thing, its
   fields, how things relate, and the concepts several fields share. Outside
   the ADVANCED paragraph this text does not use the model's own words for its
   parts — a modeller who has to learn them before saying «every group has many
   processors» is the friction doc 106 measured. The twelve sentences are the
   contract; this is their short form.

   The draft-query paragraph is a DISTILLATION of the data plane's one-rule,
   not a verbatim share: that text lives on `server.mcp`'s private impl, its
   examples are record-centric data-plane prose, and the draft tool packages
   `:query`/`:entity` as separate params. Same engine, same behaviour — if the
   engine's join contract ever changes, both faces change with it."
  (str "This is the modelling face of an Open Atlas Studio Builder. You are working on a "
       "DRAFT model held in the pool, not on a running graph.\n\n"

       "YOUR JOB IS THE MODEL, NOT THE ANSWER. A user question is a test case: run it "
       "against the draft and judge how well the model expresses it. If answering took a "
       "workaround — a path the model should declare as a relation, a filter that should be a "
       "field, a business word you translated yourself because no concept carries it — the "
       "workaround IS the finding, and the answer is only its evidence. Answering at any cost "
       "and reporting success is the failure mode this face exists to prevent.\n\n"

       "SAY IT IN FOUR WORDS. Everything you can model is one of four: a THING (a kind of "
       "object one can be picked out of — a processor, an order, a customer), a FIELD of a "
       "thing, a RELATION between two things, and a CONCEPT that several fields all mean. "
       "Say them in that order:\n"
       "1. The thing first — add_thing: what it is called, what recognises one of them, and "
       "where they come from. Its fields come with it.\n"
       "2. More fields — add_attribute. A field from ANOTHER place still belongs to the same "
       "thing: give `from` and it stays one thing, not two. A field worked out from other "
       "fields is `computed_from`.\n"
       "3. Relations — add_relation, in the words a person says them: «every group HAS MANY "
       "processors» (kind=many, the default) or «every processor HAS ONE group» (kind=one). "
       "Has-one is a reference and makes no reverse collection: an instance has a root group, "
       "a group does not have many instances. Two things that are the same key across "
       "sources are add_link instead.\n"
       "4. Concepts — define_concept, BEFORE you go hunting for synonyms by field name. «These "
       "five fields are all the customer number» is a sentence only a person can say; nothing "
       "here ever binds a concept by matching names. Codes shared by several things belong on "
       "the concept, not on each field.\n"
       "A number everyone should compute the same way is add_metric; who may see a field is "
       "describe_objects.\n\n"

       "Every use case you evaluate ends in exactly ONE of four outcomes:\n"
       "1. Answered directly — the model covers this. Say so and stop: a covered case is a "
       "real, reportable result, and inventing an improvement to seem useful is worse than "
       "silence.\n"
       "2. Answered via a detour — name the detour; the detour is the gap.\n"
       "3. Cannot answer — name what is missing in the four words: which field, which "
       "relation, which concept, or a whole thing.\n"
       "4. Empty or wrong result — read :statements before concluding anything. The "
       "statement that actually ran is what separates «no data» from «wrong relation» from "
       "«one filter too many»; the result alone cannot.\n\n"

       "Judge with the model's own checkable facts — is the field registered, labelled, "
       "described; does the user's word bind to a concept; is the relation declared and in "
       "the right direction — never with generic modelling folklore. Start with query_model: "
       "it answers what the model is MISSING, and every gap it reports carries the action "
       "that fixes it. Positions marked :tools/fill-in are for a human to decide — naming a "
       "concept is not yours to do.\n\n"

       "A finding without its fix is half a finding: make the change with the tool named "
       "after the sentence you would say, READ THE PLAN it returns, send it again with "
       "apply=true, then RE-RUN the use case that produced the finding. The draft is not what "
       "a consumer sees — that is the published artifact: publish_graph without apply says "
       "whether the graph would cut at all, and with apply it publishes; verify on the data "
       "plane.\n\n"

       "EVERY WRITING TOOL PLANS FIRST. Called without apply it writes nothing and shows "
       "you what it would do; apply=true re-plans from the same arguments and writes. The "
       "same words twice produce the same plan twice — it is a translation, not a guess. Name "
       "things the way a person says them; an ambiguous name is refused with every candidate "
       "rather than picked between.\n\n"

       "DRAFT QUERIES (execute_eql with graph; the answer carries :draft true): say WHAT you want — the fields — and the "
       "engine works out HOW. Never hunt for paths between things. One starting record "
       "reaches every thing it connects to in ONE query: single values flat (even "
       "cross-domain, even a to-one neighbour's fields), nesting only for has-many "
       "collections. Splitting one record's data across calls is a failure mode; so is "
       "concluding «not reachable» without reading the refusal.\n\n"

       "GRAPH TOKENS. After a publish, issue_graph_token gives a consumer its credential for "
       "the data plane (workbench=true for a Workbench service token that may name its user). "
       "Ask the user first and say who it is for; the secret is returned once — hand it to that "
       "consumer's config and do not repeat it. list_graph_tokens and revoke_graph_token manage "
       "them.\n\n"

       "CREDENTIALS. A source's secrets are ${VAR} references. list_credentials shows which are "
       "missing (never a value); save_credential sets one the user gave you for it — do not repeat "
       "it afterwards. remove_credential plans first and is refused while a source still uses it.\n\n"

       "SOURCES. list_sources is the register (who uses each source, which credentials it lacks). "
       "probe_api_source makes one real call to an api source to show its response before "
       "add_thing. remove_source plans first and names whatever still needs the source.\n\n"

       "GRAPH LIFECYCLE. rename_graph, duplicate_graph (plans first), merge_graphs, archive_graph / "
       "restore_graph, rollback_graph, check_graph_cut, set_default_query, set_package_name, "
       "set_workspace_default_graph, reclaim_unused_versions. delete_graph is permanent: only an "
       "archived graph, only on the user's explicit instruction, with confirm repeating its name.\n\n"

       "ACCESS. list_graph_access / grant_graph_access / revoke_graph_access manage per-graph grants "
       "for workspace members. list_workspace_tokens / revoke_workspace_token manage Builder MCP "
       "keys; issuing one is a person's act in Studio. Ask before granting or revoking.\n\n"

       "SAVED QUERIES. When a query took several attempts to get right, or is complex enough "
       "(joins, parameters, a particular filter or order) that the next agent would struggle "
       "to write it, OFFER to save it with save_saved_query: a name, a description written for "
       "whoever will choose it, typed parameters as ?name in value positions, and an example "
       "for each. Ask the user first; skip one-off lookups; check list_saved_queries so you "
       "extend rather than duplicate. save_saved_query has no plan step — instead it checks "
       "the attributes and RUNS the query on the draft before keeping it. It reaches "
       "consumers only when publish_graph publishes the next version.\n\n"

       "A refusal is an answer. It names the position and what was expected; read it and "
       "correct the next call rather than retrying the same one.\n\n"

       "VALUES ARE EDN. JSON cannot express a keyword, so send EDN in a JSON string "
       "wherever this model wants one: id \":cis.party/customer_no\", entry \"{:type "
       ":string :label \\\"客户号\\\"}\", type \"attribute\". A bare keyword name "
       "without the colon is accepted too.\n\n"

       "ADVANCED. The four words are a closed grammar and the model underneath is richer: "
       "resolvers with several inputs, optional inputs, parameterised queries, cache and "
       "limit windows, one attribute produced by two resolvers, and the namespace a thing's "
       "attributes are registered under. There is no sentence for those; update_resolver and "
       "update_attribute speak the model's own vocabulary, and read_object shows an entry as "
       "it is stored. Reach for them when the grammar cannot say what you mean — not before, "
       "because a hand-written shape is how a graph acquires a resolver the engine compiles "
       "to nothing."))

;; ---------------------------------------------------------------------------
;; HttpExchange ↔ Ring
;; ---------------------------------------------------------------------------

(defn- request-map
  "The exchange as the Ring request the transport expects. Header names are
   lower-cased because the transport reads them that way and
   `com.sun.net.httpserver` preserves the case the client sent."
  [^HttpExchange exchange]
  (let [uri (.getRequestURI exchange)
        text (slurp (.getRequestBody exchange) :encoding "UTF-8")]
    {:request-method (keyword (str/lower-case (.getRequestMethod exchange)))
     :uri (.getPath uri)
     :remote-addr (some-> exchange .getRemoteAddress .getAddress .getHostAddress)
     :query-string (.getQuery uri)
     :headers (into {} (for [[k vs] (.getRequestHeaders exchange)]
                         [(str/lower-case k) (first vs)]))
     ;; The body is read here and handed to the transport as a fresh stream
     ;; over the same bytes. 8.4.14 裁定八 also read it on this side, to learn
     ;; what `initialize` declared; 8.4.22 裁定十六 deleted that reading —
     ;; `:probe` now follows the credential — so only the transport reads it.
     :body (java.io.ByteArrayInputStream. (.getBytes ^String text StandardCharsets/UTF_8))}))

(defn- respond!
  "Write a Ring response back onto the exchange.

   A body that is a **function** is the SSE stream the transport built: it is
   handed the raw output stream and owns it until the client goes away. That
   is why `:sse-writer` below is `identity` — the transport's contract is that
   the host decides what a streaming body IS, and here it is the function
   itself."
  [^HttpExchange exchange {:keys [status headers body]}]
  (let [response-headers (.getResponseHeaders exchange)]
    (doseq [[k v] headers] (.set response-headers (str k) (str v)))
    (if (fn? body)
      (do (.sendResponseHeaders exchange status 0)
          (with-open [out (.getResponseBody exchange)] (body out)))
      (let [bytes (.getBytes ^String (or body "") StandardCharsets/UTF_8)]
        ;; 0 would mean "chunked, length unknown"; -1 means "no body at all",
        ;; which is what a 202 or a 405 is.
        (.sendResponseHeaders exchange status (if (zero? (alength bytes)) -1 (alength bytes)))
        (when (pos? (alength bytes))
          (with-open [out (.getResponseBody exchange)] (.write out bytes)))))))

;; ---------------------------------------------------------------------------
;; The seam
;; ---------------------------------------------------------------------------

(defn- coerce-arg
  "One argument, as the declared parameter type wants it.

   **JSON cannot say `:keyword`.** Every id, type, action and dialect entry in
   this platform is EDN, and an MCP client can only send JSON — so without a
   stated convention the modelling tools are literally uncallable over the
   wire: an `action` sent as JSON arrives as the string \"update\" and the
   registry refuses it for not being a keyword. That is what the first wiring
   of this endpoint did, and every write failed with a type error.

   The convention is the one the data plane already uses — `execute_eql` takes
   its query as **a string of EDN**, read with `clojure.edn` and no tag
   handlers. Applying the same rule here rather than inventing a second one:

   - a `:keyword` parameter accepts \"update\" or \":update\";
   - a `:map` / `:vector` / `:any` parameter accepts an EDN **string**, which
     is how a client sends `{:type :string}` or `:cis.party/name` through JSON;
   - anything already of the right shape passes through untouched, so the
     Builder's own UI (which speaks EDN natively) is unaffected.

   `clojure.edn/read-string` and not `read-string`: the reader must not
   evaluate, and a tool argument is the most attacker-reachable input here."
  [type* v]
  (cond
    (and (= :keyword type*) (string? v))
    (keyword (if (str/starts-with? v ":") (subs v 1) v))

    ;; Only when the string LOOKS like the EDN the client meant. Reading every
    ;; string would turn the plain id \"USD\" into the symbol USD — which is
    ;; what the first version did, and the pool then refused a perfectly good
    ;; unit id for not being a string. An opening delimiter or a leading colon
    ;; is the client saying \"this is EDN\"; anything else is a plain value.
    (and (contains? #{:map :vector :any} type*)
         (string? v)
         (re-find #"^\s*[:\[{(]" v))
    (try (edn/read-string v) (catch Exception _ v))

    :else v))

(defn- coerce-args
  "Arguments coerced by the tool's OWN declared parameter types.

   Declaration-driven on purpose: a hand-written list of «which arguments are
   keywords» here would be a second schema, and the day a tool gains a
   parameter is the day the two stop agreeing (`studio/tools` ruling: the tool
   face is a wrapper, not a second implementation)."
  [declarations tool args]
  (if-let [params (some #(when (= tool (:name %)) (:params %)) declarations)]
    (reduce (fn [m {:keys [name type]}]
              (let [k (keyword name)]
                (if (contains? m k) (update m k #(coerce-arg type %)) m)))
            args params)
    args))

(defn deps
  "Assemble the transport deps for this Builder.

   `parts` is what the host already has:

   - `:home` — the Studio home the token ledger lives in;
   - `:pepper` — this instance's workspace-token pepper (S5.9: not the
     data-plane token pepper);
   - `:conn` — the pool connection;
   - `:graph-service` — `(fn [caller])` → the graph operations bound to that
     caller (8.4 P2). The SAME functions the Builder's HTTP routes call: an
     agent and a person get one set of guards, not two that agree today;
   - `:publish-service` — `(fn [caller])` → cut / publish / rollback, same
     rule (8.4 P3);
   - `:draft-service` — `(fn [caller])` → the Builder-specific half of the
     draft face (the intent tools' post-apply verification). The same
     execution the Builder's own `/api/pathom` route runs, so an agent and a
     person are looking at one draft, not two;
   - `:draft-runtime` — `(fn [caller])` → the environment seam the seven
     mounted data-plane tools resolve through (8.4.18 裁定十一 ①). It lands on
     the ctx as `:runtime`, which is the key `modules/server/mcp`'s handlers
     read; absent, those tools refuse rather than falling through to the data
     plane's token-based default;
   - `:declarations` — the tool declarations, for wire-level argument coercion;
   - `:registry` — `(fn [caps])` → a toolkit registry for that capability set;
   - `:tools-list` — `(fn [reg])` → the `tools/list` vector for that registry;
   - `:call` — `(fn [reg ctx tool args])` → the tool's result;
   - `:default-graph` — optional `(fn [workspace])` → the workspace's default
     graph, RESOLVED (8.4.17 裁定十 / D3.21). Injected rather than read here
     because this ns opens no pool of its own, and resolved rather than taken
     as stored because the setting names a qualified name that a delete,
     rename or archive can leave dangling — a tool must not be handed a graph
     it cannot land on;
   - `:resolve-source` — optional `(fn [source-id entry])` → the same source
     entry with its `${VAR}` credential references substituted for this
     instance. The SAME function the runtime is handed (8.4.18 裁定十一 ④), so
     the structure-reading tools and the query path resolve a credential in
     exactly one place; absent, entries reach the connector as declared;
   - `:declare-source` — optional `(fn [caller])` → `(fn [request])`, the
     host's ONE source-write path (8.4.24 裁定十八): validate, prove the
     connection, write. Bound to a caller for the same reason the graph
     service is — the workspace comes from the key, not from an argument;
     absent, `declare_source` refuses rather than writing unguarded;
   - `:locate-rows` — optional `(fn [response data-path])` → the value the
     api contract's `:data_path` points at. The CONNECTOR's own, injected for
     the same reason `:probe-source` is (8.4.22 裁定十六 ②);
   - `:probe-source` — optional `(fn [source-config request])` → one sampled
     response from an api source (8.4.14 裁定七). Injected rather than
     required, because `studio/tools` declares the SPI seam and not any one
     connector: which connectors exist is the host's decision;
   - `:now` — 0-arg fn returning an ISO instant string;
   - `:on-auth-failure` — optional `(fn [reason])`, called when a request is
     turned away at this door. Injected for the same reason the services are:
     this ns opens no instance and so cannot know where that instance's audit
     trail lives.

   The registry and the two tool functions are injected rather than required
   here so this ns never has to know which tools exist — that list belongs to
   `studio/tools`, and this is the wire."
  [{:keys [home pepper conn graph-service publish-service draft-service semantic-service semantic-package-service
           draft-runtime probe-source locate-rows resolve-source declare-source default-graph
           declarations registry tools-list call now on-auth-failure]}]
  {:server-info server-info
   :instructions instructions
   :unauthorized-message unauthorized-message
   ;; The transport hands `:sse-writer` a `(fn [out])`; on this listener the
   ;; streaming body simply IS that function — see `respond!`.
   :sse-writer identity
   :authenticate
   (fn [request]
     ;; `(now)` is what makes an expired key a 401 here: the ledger reads no
     ;; clock of its own, so the transport supplies the one it already has.
     (if-let [record (wt/verify home pepper (wire/bearer request) (now))]
       ;; `:id` is the token-id and NEVER the secret: the transport keeps this
       ;; value in its SSE session map, where it outlives the request (S5.2).
       {:id (:token-id record)
        :ctx {:query-source {:token-id (:token-id record)
                             :remote-addr (:remote-addr request) :path (:uri request)
                             :user-agent (get-in request [:headers "user-agent"])}
              :conn conn
              ;; **The credential's scope, and nothing else** (8.4.22
              ;; 裁定十六 ①). 裁定七 had the client declare `:probe` for
              ;; itself in `initialize`; a standard MCP client declares no
              ;; such thing, so every agent on an OMA-shaped client was
              ;; refused with `:tools.intent/probe-required`. The capability
              ;; now follows the write key — whoever may change the model and
              ;; publish it is by far the more dangerous of the two (the
              ;; reasoning of 裁定四) — and that implication is applied in
              ;; `studio/tools`, so the GUI path reads it the same way.
              :scope (set (:scope record))
              :workspace (:workspace record)
              ;; **The caller is the TOKEN's**, never an argument: a workspace
              ;; parameter would let a key issued for one workspace act in
              ;; another, which is the boundary these tokens exist to draw.
              ;; The principal is whoever issued the key — so 「经谁授权」 has
              ;; an answer even though no person is at the keyboard.
              :graph-service (when graph-service
                               (graph-service {:workspace (:workspace record)
                                               :principal (:issued-by record)}))
              :publish-service (when publish-service
                                 (publish-service {:workspace (:workspace record)
                                                   :principal (:issued-by record)}))
              :semantic-service (when semantic-service
                                  (semantic-service {:workspace (:workspace record) :principal (:issued-by record)}))
              :semantic-package-service (when semantic-package-service
                                          (semantic-package-service
                                           {:workspace (:workspace record) :principal (:issued-by record)}))
              :draft-service (when draft-service
                               (draft-service {:workspace (:workspace record)
                                               :principal (:issued-by record)}))
              ;; **The one thing that differs from a data-plane call**
              ;; (8.4.18 裁定十一 ①): where the environment comes from. The
              ;; caller is the TOKEN's, like every other scope here, so no
              ;; argument can point these tools at another workspace's graph.
              :runtime (when draft-runtime
                         (draft-runtime {:workspace (:workspace record)
                                         :principal (:issued-by record)}))
              ;; 裁定七's probe, injected the same way every other effectful
              ;; end is: `studio/tools` may not depend on a concrete
              ;; connector (its deps declare the SPI seam, not connector-api),
              ;; so the host — which decides which connectors are loaded —
              ;; hands the function in.
              :probe-source probe-source
              ;; 8.4.22 裁定十六 ②: the connector's `:data_path` locator, so a
              ;; probe sample is read where the query path reads its rows.
              :locate-rows locate-rows
              ;; The structure-reading path (`describe_source`, `add_resolver`'s
              ;; plan) and the probe hand a source entry straight to a
              ;; connector, so they need the credentials the query path already
              ;; resolves. Injected, not looked up: credential resolution stays
              ;; one place (`credentials/resolve-config`, 8.4.18 裁定十一) and
              ;; `studio/tools` may not depend on this app (D1.8).
              :resolve-source resolve-source
              ;; 8.4.24 裁定十八: the source write path, bound to the TOKEN's
              ;; workspace like every other service on this ctx — `id` names a
              ;; source, never a workspace, so no argument can move a write
              ;; into another one.
              :declare-source (when declare-source
                                (declare-source {:workspace (:workspace record)
                                                 :principal (:issued-by record)}))
              ;; 8.4.17 裁定十: what a tool's omitted `graph` falls back to.
              ;; It is the TOKEN's workspace that answers, like every other
              ;; scope on this ctx — a default graph is a workspace setting,
              ;; so no argument can point it at another workspace's graph.
              :default-graph (when default-graph (default-graph (:workspace record)))
              ;; **The trusted half.** Built here from what authentication
              ;; established, and reachable by no argument the client sends.
              :provenance {:entrypoint :mcp
                           :token-id (:token-id record)
                           :actor (:issued-by record)
                           :at (now)}}}
       ;; A refused knock is audited (S5.3), and carries NO subject: `verify`
       ;; answers nil to a wrong prefix, an unknown digest, a revoked key and
       ;; an expired one alike, so nothing here is a token-id this instance
       ;; has any grounds to believe. Filing the presented credential's
       ;; claimed id would put an unverified identifier in the subject index
       ;; and hand the audit reader the very distinction the 401 withholds.
       ;; The reason is `:token/invalid` for the same reason.
       (do (when on-auth-failure (on-auth-failure :token/invalid))
           nil)))
   ;; Both go through `registry` with the SAME scope, so the announced set and
   ;; the dispatchable set are one capability decision — "listed but not
   ;; callable" and "callable but not listed" are both unreachable
   ;; (toolkit ruling ②).
   :tools-list (fn [ctx] (tools-list (registry (:scope ctx))))
   ;; `toolkit/as-tool-result` is the single place finding-v1 and the MCP
   ;; result convention meet (8.4 P4) — this ns had its own copy until the
   ;; data-plane endpoint needed the same translation.
   :call-tool  (fn [ctx tool args]
                 (binding [obs/*query-source* (assoc (:query-source ctx) :tool tool)]
                   (toolkit/as-tool-result
                    (call (registry (:scope ctx)) ctx tool
                          (coerce-args declarations tool args)))))})

(defn handles?
  "Whether this path belongs to the MCP endpoint. The Builder's route loop asks
   before it applies its own session-cookie guard: these four routes carry a
   bearer token instead, and running the cookie guard over them would refuse
   every agent before the transport ever saw the request."
  [path]
  (contains? #{"/mcp" "/mcp/sse" "/mcp/messages"} path))

(defn handle!
  "Serve one exchange through the shared transport."
  [deps* ^HttpExchange exchange]
  (respond! exchange ((wire/routes deps*) (request-map exchange))))
