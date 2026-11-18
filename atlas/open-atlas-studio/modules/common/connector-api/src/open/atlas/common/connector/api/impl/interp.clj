(ns open.atlas.common.connector.api.impl.interp
  "Pure interpolation and parameter-routing functions for the api connector.

   Migrated from the open-atlas api plugin's helpers plus the pure functions
   it pulled from ent.dx.pathom.plugins.core (interpolate-params and the
   attr<->keyword bridges), so this module depends on nothing but the SPI.

   api-v1: ':endpoint — {attr-kw} placeholder interpolation, URL-encoded
   where it lands in the URL'; ':json_body — recursive interpolation; a leaf
   that is exactly one placeholder keeps the raw value type'; eql-param
   ':param_location \"query\" or \"body\" (default body)'."
  (:require [clojure.string :as str]
            [open.atlas.common.connector.spi :as spi])
  (:import [java.net URLEncoder]))

;; ---------------------------------------------------------------------------
;; attr bridges + string interpolation
;; ---------------------------------------------------------------------------

(defn edn-attr->kw
  "\"vpo/order_no\" → :vpo/order_no."
  [edn-attr]
  (when edn-attr
    (let [[ns attr] (str/split (str edn-attr) #"/")]
      (keyword ns attr))))

(defn kw->edn-attr
  ":vpo/order_no → \"vpo/order_no\"."
  [kw]
  (when kw (str (namespace kw) "/" (name kw))))

(defn interpolate-params
  "Replace {name}, {?name} and reserved-expansion {+name} placeholders in a
   string with values from `params` ({string-name → value}). Unknown
   placeholders are left untouched — the runtime rejects a still-templated
   value before sending (api-v1 conformance: refuse rather than send the raw
   template; only unresolved OPTIONAL {?name} placeholders may drop out, owner
   ruling 2026-08-28).

   `{+name}` is spelled here too so that a reserved placeholder outside the URL
   position (a `:query_params` template, a `:json_body` leaf) resolves to the
   same value rather than being left behind as an unresolved template — the
   `+` marks how the value is ENCODED (doc 106 §106.14 item 8), not a second
   binding."
  [template params]
  (reduce (fn [s [k v]]
            (-> s
                (str/replace (str "{" k "}") (str v))
                (str/replace (str "{?" k "}") (str v))
                (str/replace (str "{+" k "}") (str v))))
          (str template)
          params))

(defn- percent-encode
  "Percent-encode one path segment. URLEncoder does FORM encoding (space →
   \"+\"), and a path segment needs percent encoding, so rewrite that one
   difference."
  [v]
  (str/replace (URLEncoder/encode (str v) "UTF-8") "+" "%20"))

(def reserved-violations
  "Why a value may not go through a `{+attr}` reserved expansion — the path
   injection boundary (api-v1 \"占位符\" section, owner ruling 2026-09-07,
   doc 106 §106.14 item 8). Ordered, so a value that trips several reports the
   same first reason on every run.

   Every entry is a way to make the request address a resource the model never
   named: `..` climbs out of the templated path, `?` and `#` end the path and
   start a query or fragment, `\\` is a path separator on the server side of
   some stacks, an empty segment collapses or re-roots the path, and whitespace
   or a control character smuggles a request line. `/` alone is what `{+attr}`
   is FOR and is therefore not here."
  [[:dot-segment  #(re-find #"(^|/)\.\.?(/|$)" %)
    "a . or .. path segment"]
   [:query-or-fragment #(re-find #"[?#]" %) "a ? or # character"]
   [:backslash    #(str/includes? % "\\") "a backslash"]
   [:empty-segment #(or (str/starts-with? % "/")
                        (str/ends-with? % "/")
                        (str/includes? % "//"))
    "a leading, trailing or doubled /"]
   [:whitespace   #(re-find #"\s" %) "whitespace"]
   [:control      #(re-find #"[\x00-\x1f\x7f]" %) "a control character"]])

(defn reserved-value-violation
  "nil, or `{:reason kw :what \"…\"}` — the first rule of `reserved-violations`
   the value breaks. Names the rule, never the value (finding-v1 §5)."
  [v]
  (let [s (str v)]
    (some (fn [[reason pred what]] (when (pred s) {:reason reason :what what}))
          reserved-violations)))

(defn- encode-reserved
  "RFC 6570 reserved expansion, narrowed to `/`: every segment is percent-encoded
   and the separators are put back. Callers reject an illegal value BEFORE
   substituting (`reserved-value-violation`); encoding one here would be the
   silent rewrite the ruling forbids."
  [v]
  (str/join "/" (map percent-encode (str/split (str v) #"/" -1))))

(defn interpolate-url-params
  "Like `interpolate-params` but URL-encodes each substituted VALUE — for
   the :endpoint path position (api-v1 connection schema: placeholders are
   URL-encoded where they land in the URL). The template itself passes
   through verbatim.

   Two spellings (api-v1, owner ruling 2026-09-07):
   `{attr}` encodes the whole value, `/` included — one path segment;
   `{+attr}` is RFC 6570 reserved expansion narrowed to `/`: separators survive,
   everything else is still encoded. `{+attr}` is how an identity value that
   IS a path (`apache/nifi`) reaches the wire — OQ-PATH-KEY-ENCODING measured
   such a key building, cutting, and then 404-ing on every keyed query."
  [template params]
  (reduce (fn [s [k v]]
            (-> s
                (str/replace (str "{" k "}") (percent-encode v))
                (str/replace (str "{+" k "}") (encode-reserved v))))
          (str template)
          params))

(defn reserved-value-findings
  "`[{:name attr-name :reason kw :what \"…\"} …]` for every `{+name}` placeholder
   in `template` whose bound value breaks the boundary. The caller turns these
   into findings; nothing is substituted for a value that appears here."
  [template params]
  (into []
        (keep (fn [[k v]]
                (when (str/includes? (str template) (str "{+" k "}"))
                  (some-> (reserved-value-violation v) (assoc :name k)))))
        params))

(defn unresolved-placeholders
  "REQUIRED placeholder names still present in an interpolated string.
   Optional {?name} placeholders are excluded — they are allowed to drop
   out (owner ruling 2026-08-28).

   A reserved `{+name}` reports the BARE name: `+` says how the value is
   encoded, not what it binds to, so validation (`endpoint-placeholder-findings`)
   and `bound-input-attributes` see one attribute under either spelling."
  [s]
  (into [] (keep (fn [[_ ph]]
                   (cond
                     (str/starts-with? ph "?") nil
                     (str/starts-with? ph "+") (subs ph 1)
                     :else ph)))
        (re-seq #"\{([^{}]+)\}" (str s))))

(defn optional-placeholders
  "Optional {?name} placeholder names present in a template (the leading ?
   stripped)."
  [s]
  (into [] (keep (fn [[_ ph]] (when (str/starts-with? ph "?") (subs ph 1))))
        (re-seq #"\{([^{}]+)\}" (str s))))

(def ^:private authority-pattern
  "`scheme://` + the authority, up to the first `/`, `?` or `#`. The authority
   is the ONLY position a `:base_url` placeholder may sit in (api-v1
   «:base_url placeholders», owner ruling 2026-09-07, doc 106 §106.14 item 11)."
  #"(?i)^([a-z][a-z0-9+.-]*)://([^/?#]*)")

(def ^:private attr-placeholder-pattern
  "A `{…}` placeholder that is NOT a `${VAR}`. `${DB_HOST}` is a configuration
   variable resolved on the host at request time (api-v1 «凭证键»); reading its
   braces as an attribute placeholder would report every enved `:base_url` as
   templated and refuse its health check."
  #"(?<!\$)\{([^{}]+)\}")

(defn- masked
  "The string with every `{…}` replaced by same-length filler.

   The delimiters that mark out a URL's regions — `/`, `?`, `#` — also occur
   INSIDE a placeholder, because an attribute is spelled `ns/attr`. Splitting
   the raw string would cut `{nifi.instance/host}` in half and put the two
   halves in two different regions, which is how a legal host template read
   back as no placeholder at all. So regions are decided on the masked string
   and the placeholders are then located by OFFSET."
  [s]
  (str/replace s attr-placeholder-pattern
               (fn [m] (apply str (repeat (count (first m)) "x")))))

(defn- placeholder-name
  "A `{…}` body → the name it binds, spelled BARE: a reserved `{+attr}` reports
   `attr` (`+` says how a value is encoded, not what it binds to) and an
   optional `{?name}` reports nothing — it may drop out, and a host that may
   drop out is not a host."
  [body]
  (cond (str/starts-with? body "?") nil
        (str/starts-with? body "+") (subs body 1)
        :else body))

(defn base-url-placeholder-positions
  "Where each `{…}` in a `:base_url` sits: `{:scheme [name …] :authority […]
   :path […]}`.

   Position, not syntax, is what the ruling turns on: a placeholder in the host
   picks WHICH of many like-for-like instances answers, while one in the scheme
   or the path re-addresses the request itself — and the path case is
   deliberately left for the day a multi-tenant prefix is asked for, with the
   boundary table of `{+attr}` (api-v1) to inherit."
  [base-url]
  (let [s (str base-url)
        m (re-find authority-pattern (masked s))
        authority-end (when m (count (first m)))
        scheme-end (when m (+ (count (nth m 1)) 3))
        region (fn [start]
                 (cond (nil? m) :path
                       (< start scheme-end) :scheme
                       (< start authority-end) :authority
                       :else :path))
        found (let [mt (re-matcher attr-placeholder-pattern s)]
                (loop [acc []]
                  (if (.find mt)
                    (recur (conj acc [(.start mt) (.group mt 1)]))
                    acc)))]
    (reduce (fn [acc [start body]]
              (if-let [n (placeholder-name body)]
                (update acc (region start) conj n)
                acc))
            {:scheme [] :authority [] :path []}
            found)))

(defn base-url-bound-names
  "The attribute-spelled placeholder names a `:base_url` binds — the qualified
   ones in the AUTHORITY position. An unqualified name there is eql-param
   spelling and is not an attribute (the rule `bound-input-attributes` already
   applies to `:endpoint`)."
  [base-url]
  (into [] (filter #(str/includes? % "/"))
        (:authority (base-url-placeholder-positions base-url))))

(defn templated-base-url?
  "Does this `:base_url` carry an ATTRIBUTE placeholder — the question
   health-check asks before deciding it cannot check the source as a whole.

   A `${VAR}` is not one: it is resolved before the request, from this host's
   configuration, and a source that uses one has exactly one upstream."
  [base-url]
  (let [pos (base-url-placeholder-positions base-url)]
    (boolean (seq (concat (:scheme pos) (:authority pos) (:path pos))))))


;; ---------------------------------------------------------------------------
;; json_body interpolation
;; ---------------------------------------------------------------------------

(defn interpolate-body
  "Recursively walk a json_body structure (maps, vectors, strings) and
   replace {name} placeholders with values from `param-map`. A string leaf
   that is EXACTLY one placeholder whose param is present is replaced by the
   RAW value (preserving numbers/booleans); any other string goes through
   string interpolation, leaving unknown placeholders untouched."
  [node param-map]
  (cond
    (map? node)
    (reduce-kv (fn [m k v] (assoc m k (interpolate-body v param-map))) {} node)

    (sequential? node)
    (mapv #(interpolate-body % param-map) node)

    (string? node)
    (if-let [[_ ph] (re-matches #"\{([^{}]+)\}" node)]
      (let [name (if (str/starts-with? ph "?") (subs ph 1) ph)]
        (if (contains? param-map name) (get param-map name) node))
      (interpolate-params node param-map))

    :else node))

(defn drop-optional-entries
  "Remove map entries (recursively) whose string value still contains an
   un-substituted OPTIONAL {?name} placeholder — the explicit opt-out syntax
   for a json_body template carrying optional id fields (owner ruling
   2026-08-28: only {?...} may drop; a plain {name} left unresolved is
   rejected, see body-unresolved-placeholders)."
  [node]
  (cond
    (map? node)
    (into {} (keep (fn [[k v]]
                     (let [v2 (drop-optional-entries v)]
                       (when-not (and (string? v2) (str/includes? v2 "{?"))
                         [k v2])))
                   node))

    (sequential? node)
    (mapv drop-optional-entries node)

    :else node))

(defn drop-missing-optional-entries
  "Drop optional map entries based on template bindings, before interpolation."
  [node param-map]
  (cond
    (map? node)
    (into {} (keep (fn [[k v]]
                    (when-not (and (string? v)
                                   (some (fn [ph]
                                           (and (str/starts-with? ph "?")
                                                (not (contains? param-map (subs ph 1)))))
                                         (map second (re-seq #"\{([^{}]+)\}" v))))
                      [k (drop-missing-optional-entries v param-map)]))) node)
    (sequential? node) (mapv #(drop-missing-optional-entries % param-map) node)
    :else node))

(defn body-unresolved-placeholders
  "Placeholder names still present anywhere in an interpolated json_body
   structure. api-v1 conformance: a body that failed to resolve is REJECTED,
   never sent with the raw template — the migrated implementation silently
   dropped such entries; that deviation is recorded and not carried over."
  [node]
  (cond
    (map? node)        (into [] (mapcat body-unresolved-placeholders) (vals node))
    (sequential? node) (into [] (mapcat body-unresolved-placeholders) node)
    (string? node)     (unresolved-placeholders node)
    :else              []))

;; ---------------------------------------------------------------------------
;; URL query string + param routing
;; ---------------------------------------------------------------------------

(defn url-encode-params
  "Encode a param map into a URL query string: k1=v1&k2=v2."
  [params]
  (str/join "&"
            (map (fn [[k v]]
                   (str (URLEncoder/encode (name k) "UTF-8")
                        "="
                        (URLEncoder/encode (str v) "UTF-8")))
                 params)))

(defn add-query-params
  "Append `params` to `url`'s query string (doc 107 §107.6: a page's position
   rides the query, and the URL was already assembled by `build-request`)."
  [url params]
  (if (seq params)
    (str url (if (str/includes? (str url) "?") "&" "?") (url-encode-params params))
    url))

(defn query-param? [spec] (= "query" (:param_location spec)))

;; :param_location "root" (and the batch executor's :__batch_root_body hook it
;; served) is NOT migrated: api-v1 support policy names it explicitly among the
;; reference-implementation extensions v1 does not accept. Contract upgrade
;; first, code second.

(defn merge-eql-params-into-body
  "Merge declared filter eql-params into the JSON body. Only params with a
   :field key merge; pagination (:limit :offset :order_by :sort_dir) and
   \"query\"-routed params are excluded — they route to the query string."
  [eql-params declared-params]
  (when (and (seq eql-params) (seq declared-params))
    (reduce-kv
     (fn [acc param-key param-val]
       (if-let [spec (get declared-params param-key)]
         (if (and (:field spec) (not (query-param? spec)))
           (assoc acc (:field spec) param-val)
           acc)
         acc))
     {}
     (dissoc eql-params :limit :offset :order_by :sort_dir))))

(defn pagination-query-params
  "Query-string params for pagination/sorting; declared :field names win,
   defaults _limit/_offset/_sort/_order (api-v1 eql-param table)."
  [eql-params declared-params]
  (when (seq eql-params)
    (let [limit-field  (get-in declared-params [:limit :field] "_limit")
          offset-field (get-in declared-params [:offset :field] "_offset")
          sort-field   (get-in declared-params [:order_by :field] "_sort")
          order-field  (get-in declared-params [:sort_dir :field] "_order")]
      (cond-> {}
        (:limit eql-params)    (assoc limit-field (:limit eql-params))
        (:offset eql-params)   (assoc offset-field (:offset eql-params))
        (:order_by eql-params) (assoc sort-field (:order_by eql-params))
        (:sort_dir eql-params) (assoc order-field (:sort_dir eql-params))))))

(defn merge-into-body
  "Merge extra keys into a JSON body — only when the body is a MAP. A
   root-ARRAY body has no place for named keys; merging into a vector would
   conj the pairs onto it and silently corrupt the request."
  [body extras]
  (if (and (map? body) (seq extras)) (merge body extras) body))

(defn declared-query-params
  "URL query params from eql-params declared with :param_location \"query\"."
  [eql-params declared-params]
  (when (and (seq eql-params) (seq declared-params))
    (reduce-kv
     (fn [acc param-key param-val]
       (if-let [spec (get declared-params param-key)]
         (if (and (:field spec) (query-param? spec) (some? param-val))
           (assoc acc (:field spec) param-val)
           acc)
         acc))
     {}
     eql-params)))

(defn templated-query-params
  "api-v1 ':query_params {name → template}': interpolate each template with
   the param map. Entries whose template still holds an unresolved
   placeholder are dropped (optional filters fall out cleanly)."
  [query-params param-map]
  (when (seq query-params)
    (into {} (keep (fn [[k template]]
                     (let [v (interpolate-params template param-map)]
                       (when (empty? (unresolved-placeholders v))
                         [k v]))))
          query-params)))

;; ---------------------------------------------------------------------------
;; ${VAR} config substitution (mirrors connector-sql impl.envsub)
;; ---------------------------------------------------------------------------

(def var-pattern #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")

(defn only-placeholder?
  "The whole value is exactly one ${VAR} — the only legal shape for
   credential keys (api-v1: credential keys only accept ${VAR})."
  [s]
  (boolean (and (string? s) (re-matches var-pattern s))))

(defn var-names [s]
  (when (string? s) (mapv second (re-seq var-pattern s))))

(defn resolve-value
  "Resolve a config value: a full ${VAR} match resolves via `lookup`
   (default: process env; unresolved → nil), anything else passes through."
  ([v] (resolve-value v #(spi/*config-lookup* %)))
  ([v lookup]
   (if (only-placeholder? v)
     (lookup (second (re-matches var-pattern v)))
     v)))

(defn substitute
  "Replace EVERY `${VAR}` occurrence in `s` with its looked-up value.
   → `{:value substituted :missing [unresolved-name …]}`.

   Unlike `resolve-value` (whole-value only, for credential keys) this
   handles a var EMBEDDED in a larger string — `https://${SE_HOST}/v2`, a
   header value, a token URL. An unresolved var does NOT keep its literal
   placeholder: sending `${SE_CLIENT_ID}` upstream as though it were the
   value turns a misconfiguration into a puzzling 401 from the vendor, with
   the failure surfacing far from its cause. Callers report `:connection`
   on a non-empty `:missing` instead. Same contract as connector-sql
   impl.envsub/substitute — one substitution semantics across connectors."
  ([s] (substitute s #(spi/*config-lookup* %)))
  ([s lookup]
   (if-not (string? s)
     {:value s :missing []}
     (let [missing (volatile! [])
           v (str/replace s var-pattern
                          (fn [[_ n]]
                            (or (lookup n)
                                (do (vswap! missing conj n) ""))))]
       {:value v :missing @missing}))))

(defn substituted-base-url
  "The source's `:base_url` with its `${VAR}`s resolved -- the ORIGIN a
   `Link: rel=next` target must match (§107.6). Resolved here because the
   comparison is meaningless against a template."
  [source-config]
  (:value (substitute (:base_url source-config))))

(defn substitute-vals
  "`substitute` across a map's VALUES (header maps). Keys are configuration
   names and are never templated."
  ([m] (substitute-vals m #(spi/*config-lookup* %)))
  ([m lookup]
   (if-not (seq m)
     {:value m :missing []}
     (reduce-kv (fn [acc k v]
                  (let [{:keys [value missing]} (substitute v lookup)]
                    (-> acc
                        (assoc-in [:value k] value)
                        (update :missing into missing))))
                {:value {} :missing []} m))))

(defn missing-vars
  "Names of ${VAR} references in `values` that resolve to nothing.
   Names only, never values — a name is dialect configuration."
  ([values] (missing-vars values #(spi/*config-lookup* %)))
  ([values lookup]
   (into []
         (comp (mapcat var-names)
               (distinct)
               (remove #(some? (lookup %))))
         (filter string? values))))

;; ---------------------------------------------------------------------------
;; E1 / D5.14: which :input attributes does this operation narrow by
;; ---------------------------------------------------------------------------

(defn- required-placeholder-names
  "Required `{name}` placeholder names anywhere in a template tree (strings,
   maps, vectors). Optional `{?name}` are **excluded**: they are allowed to
   drop out of the sent request (owner ruling 2026-08-28), and a filter that
   may silently vanish is not a row-level guarantee — the closed direction."
  [node]
  (cond
    (map? node)        (mapcat required-placeholder-names (vals node))
    (sequential? node) (mapcat required-placeholder-names node)
    (string? node)     (unresolved-placeholders node)
    :else              nil))

(defn bound-input-attributes
  "The operation's `:input` attributes that actually reach the wire as a value
   (E1 / D5.14).

   The api connector spells an input placeholder with the **qualified** attr —
   `{vpo/order_no}` — while an eql-param is spelled by its simple name. So the
   intersection with `:input` does two jobs at once: it maps the spelling back
   to attributes, and it drops caller-supplied eql-param bindings, which
   D5.6 consensus 2 forbids row security from relying on.

   `:headers` are not walked: their templates are resolved from environment
   configuration (`substitute-vals`), not from the caller's entity, so a
   placeholder there never carries a row-level value.

   `:base_url` IS walked (owner ruling 2026-09-07, doc 106 §106.14 item 11):
   a host placeholder picks which of a dozen like-for-like instances answers,
   which is as row-level a narrowing as one in the path — E1 asking «does this
   operation really narrow by this attribute» must see it, or a source
   templated on the host would look unnarrowed to row security."
  [{:keys [base_url endpoint query_params json_body input]}]
  (let [declared (set input)]
    (into #{}
          (comp (mapcat required-placeholder-names)
                ;; An unqualified `{name}` is an eql-param by construction —
                ;; only input attrs are spelled `{ns/name}`. Filtering here and
                ;; not inside `edn-attr->kw` because that helper is shared and
                ;; its callers all hand it a qualified attr already.
                (filter #(str/includes? % "/"))
                (keep edn-attr->kw)
                (filter declared))
          [base_url endpoint query_params json_body])))
