(ns open.atlas.common.mcp-transport.impl.jsonrpc
  "JSON-RPC 2.0 + MCP tool-result envelopes.

   Migrated from `ent.dx.mcp.jsonrpc` (CLAUDE.md: migrate and adapt, do not
   rewrite).  The only thing dropped is the legacy per-user usage recorder:
   its accounting was keyed on a console user, and this platform's data plane
   knows a token, not a person.  Observability comes back as an injected hook
   at the transport, so this namespace stays pure.

   Moved down from `server/mcp` unchanged (84 P1a): the envelope is protocol,
   not judgement, so both hosts share exactly this one."
  (:require [cheshire.core :as json]))

(def parse-error
  "Sentinel returned by parse-json when the body isn't valid JSON at all — a
   genuine -32700 Parse error, distinct from valid JSON that isn't a Request."
  ::parse-error)

(defn parse-json
  "Decode body-str as JSON, returning the decoded value (a map for an object, a
   seq for an array, a scalar otherwise), or the `parse-error` sentinel when the
   body isn't valid JSON."
  [body-str]
  (try
    (json/parse-string body-str true)
    (catch Exception _ parse-error)))

(defn parse-error?
  "True when x is the parse-error sentinel returned by parse-json."
  [x]
  (= parse-error x))

(defn as-request
  "Coerce an already-decoded JSON value to a JSON-RPC 2.0 request map
   {:method :params :id}, or nil when it isn't a well-formed Request (e.g. a
   non-object, or an object without :jsonrpc \"2.0\") — an -32600 Invalid Request."
  [m]
  (when (and (map? m) (= "2.0" (:jsonrpc m)))
    {:method (:method m) :params (:params m) :id (:id m)}))

(defn success [id result]
  (json/generate-string {:jsonrpc "2.0" :id id :result result}))

(defn error [id code message]
  (json/generate-string {:jsonrpc "2.0" :id id :error {:code code :message message}}))

(defn tool-content
  "MCP tools/call success result. A string is sent as-is; anything else is
   JSON-encoded into the single text block."
  [id content]
  (success id {:content [{:type "text"
                          :text (if (string? content) content (json/generate-string content))}]}))

(defn tool-error
  "MCP tools/call error result (isError true)."
  [id message]
  (success id {:content [{:type "text" :text message}] :isError true}))
