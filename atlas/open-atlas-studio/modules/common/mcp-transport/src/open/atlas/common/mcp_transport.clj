(ns open.atlas.common.mcp-transport
  "MCP's **wire**, and nothing else — the shell both MCP endpoints mount
   (84 P1a). Cross-module callers may only require this ns;
   `open.atlas.common.mcp-transport.impl.*` is private (same rule as
   `common/toolkit`).

   ## What lives here, and why it is in common

   Two endpoints in this repo speak MCP: the data plane's (`server/mcp`) and
   the Builder's (docs/design/84-builder-mcp.md). Everything they share is
   protocol — JSON-RPC framing, version negotiation, batching, the two wire
   shapes, the SSE session bookkeeping — and everything they differ in is
   judgement: who a caller is, what they may call, and what a call does.

   Had the second endpoint copied the first, the copies would have drifted at
   the first spec revision, and the drift would have been invisible until a
   client hit the one that had not been updated. `server` and `studio` may not
   depend on each other (D1.8), so the shared half can only live in `common` —
   the same reasoning that put `common/toolkit` here.

   ## What this module does NOT know

   It never learns any of the vocabulary its two hosts are built out of —
   what a caller's credential means, what a tool does, what either endpoint
   serves. All of it arrives as injected functions, and the module's tests
   assert the absence by scanning this source for those words. Keep it that
   way: the day one of them appears here, mounting the same code for the
   other host drags one endpoint's ideas into the other.

   ## Deps

   - `:authenticate` — `(fn [request])` → `nil` (refuse) or `{:id … :ctx …}`.
     `:id` is opaque, used only for rate-limit keying and SSE session
     ownership; `:ctx` is handed back to the two tool fns untouched. **Never
     return a credential in `:id`** — it outlives the request in the session
     map (S5.2). Parse the bearer with `bearer` so there is one such parse;
   - `:tools-list` — `(fn [ctx])` → the vector `tools/list` announces. It takes
     ctx so capability filtering is ONE decision shared with dispatch, not two
     that can disagree;
   - `:call-tool` — `(fn [ctx tool-name arguments])` → result. `{:error …}` is
     a refusal (becomes an `isError` tool result the agent can read and act
     on); a thrown exception is a server fault and is reported as one;
   - `:server-info` — `{:name … :version …}` for `initialize`;
   - `:instructions` — optional string for `initialize`;
   - `:unauthorized-message` — what a 401 says, injected because the honest
     sentence differs per host;
   - `:sse-writer` — wraps `(fn [^OutputStream out])` into whatever the host's
     HTTP adapter accepts as a streaming body;
   - `:rate-limit?` — optional `(fn [id] {:allowed? … :retry-after-secs …})`.
     Absent means no limiting: a visible config fact, not a silent default."
  (:require [compojure.core :as compojure]
            [open.atlas.common.mcp-transport.impl.transport :as transport]))

(defn routes
  "Ring handler for the four MCP routes. **Does not start a server** — feed it
   a request map to test.

   Both hosts mount THIS, so the route set itself cannot drift: an agent
   cannot tell from the wire which endpoint it is talking to, which is what
   makes a client written against one work against the other."
  [deps]
  (compojure/routes
   (compojure/POST "/mcp" request (transport/handle-post deps request))
   (compojure/GET  "/mcp" request (transport/handle-get request))
   ;; The 2024-11-05 transport, kept because many clients and gateways still
   ;; speak only it. Same dispatch, same auth — only the framing differs.
   (compojure/GET  "/mcp/sse" request (transport/handle-sse deps request))
   (compojure/POST "/mcp/messages" request (transport/handle-messages deps request))))

(defn bearer
  "The request's Bearer credential, or nil.

   Exposed for hosts to use inside their own `:authenticate`: the prefix
   comparison should exist once, not once per endpoint."
  [request]
  (transport/bearer request))

(defn handle-post
  "POST /mcp on its own, for a host that assembles its own route table."
  [deps request]
  (transport/handle-post deps request))

(defn handle-get
  "GET /mcp on its own — 405 naming POST (this server opens no server-initiated
   stream)."
  [request]
  (transport/handle-get request))

(defn handle-sse
  "GET /mcp/sse on its own — the 2024-11-05 transport's handshake and stream."
  [deps request]
  (transport/handle-sse deps request))

(defn handle-messages
  "POST /mcp/messages on its own — the 2024-11-05 transport's upstream leg."
  [deps request]
  (transport/handle-messages deps request))
