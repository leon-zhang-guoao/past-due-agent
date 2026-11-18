(ns open.atlas.studio.ui.form-logic
  "The entry editor's pure decisions, extracted from the components that
   render them.

   Every function here answers one write-path question with no DOM in the
   room, because each of these decisions has already produced a shipped bug
   while it lived inline in a component — the one still standing being a
   merge-type save that dropped a key instead of deleting it (revision
   climbed, value unchanged), which `patch-entry` below fixes.

   The field form these were first extracted from is **gone** (owner ruling
   2026-09-05: creating and editing any pool object in Studio is EDN-only —
   \"forms are unreliable\"), and the widget-side decisions went with it. What
   is left is what the EDN path still has to decide: how an id box is read,
   what a save must send, and what a seed starts from.

   Keeping them here keeps them testable by plain `cljs.test` — no component
   harness, no browser."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Keywords as a modeller types them
;; ---------------------------------------------------------------------------

(defn kw-text
  "A keyword as a modeller types it: no leading colon. Round-trips through
   `text-kw` so what the form shows is what the entry holds."
  [v]
  (cond (keyword? v) (subs (str v) 1)
        (nil? v) ""
        :else (str v)))

(defn text-kw
  "What a modeller typed, as a keyword — nil for nothing typed, so a blank box
   never becomes the keyword `:`."
  [s]
  (let [s (str/trim (or s ""))]
    (when-not (str/blank? s)
      (keyword (str/replace s #"^:" "")))))

(defn typed-id
  "What a modeller typed in the create form's id box, as the id the pool
   addresses that type by.

   `id-kind` is the server's `:id-kind` for the type (`pool/type-id-kind`):
   `:keyword` (attribute) → a qualified keyword, colon optional; `:attr-pair`
   (link) → the two-keyword vector, read as EDN; anything else (namespace,
   resolver, term, unit, metric, mutation, source) → the trimmed string.

   Until this existed the form sent the raw string for every type, so an
   attribute could not be created from any door: the pool refused it with
   `:pool.action/invalid-id` (measured 2026-09-05). Returns
   `{:id …}` or `{:error message}` — a shape the box cannot hold is said here,
   not one server round trip later."
  [id-kind s]
  (let [s (str/trim (or s ""))]
    (case id-kind
      :keyword (let [k (text-kw s)]
                 (if (and k (namespace k))
                   {:id k}
                   {:error "An attribute id is entity/name, e.g. nifi.instance/host."}))
      :attr-pair (let [v (try (reader/read-string s) (catch :default _ nil))]
                   (if (and (vector? v) (= 2 (count v)) (every? qualified-keyword? v))
                     {:id v}
                     {:error "A link id is two attribute keywords, e.g. [:cis.a/x :cis.b/y]."}))
      {:id s})))

(defn complete-link-entry
  "A link's identity IS its endpoint pair (D2.3), so `:from` / `:to` are
   already said by the id; fill them into the entry when the author left them
   out, and leave an explicit pair alone (a disagreement is then the pool's
   to refuse, not something to paper over here). No-op for any other type."
  [type id entry]
  (if (and (= :link type) (vector? id) (= 2 (count id)) (map? entry))
    (merge {:from (first id) :to (second id)} entry)
    entry))

(defn patch-entry
  "The entry as the pool must receive it, for one save.

   **Deletion completion.** The editor's contract is \"this entry, whole\";
   the pool's `:update` on a merge type is a patch where an absent key
   *survives*.  So every key the original had and the edited entry no longer
   has must ride along as an explicit `nil` — the pool's deletion marker — or
   the removal is swallowed: the save succeeds, the revision climbs, and the
   value sits exactly where it was.  (`:listed?` re-checked was the case that
   found this: the form drops the key at the default, merge kept the stored
   `false`.)

   Guards: a `create` carries no nils (there is nothing to delete), and a
   `:replace`-type update needs none (the whole entry is the message)."
  [{:keys [create? update-kind original value]}]
  (if (and (not create?)
           (= :merge update-kind)
           (map? original))
    (merge (into {} (for [k (keys original)
                          :when (not (contains? value k))]
                      [k nil]))
           value)
    value))

;; ---------------------------------------------------------------------------
;; What a resolver needs before it will compile
;; ---------------------------------------------------------------------------

(defn join-roots
  "The keys a resolver's `:output` hangs its rows from — the `k` in every
   `{k [...]}` entry. Each one must be a registered **join attribute** (an
   `:attributes` entry carrying `:target`, D2.29) before the resolver compiles,
   which is why creating one without the other is a dead end rather than a
   warning."
  [output]
  (into [] (comp (filter map?) (mapcat keys)) (or output [])))

(defn inferred-target
  "The entity a join's members belong to — the `:target` a missing join
   attribute needs.

   Read off the output rather than asked for: the members of
   `{:a/kids [:b/x :b/y]}` say the path goes to `b`, and making someone retype
   it invites a mismatch they cannot see. nil when the members span more than
   one entity or none — then the form ASKS, because a target guessed from an
   ambiguous output would send the path somewhere plausible and wrong."
  [output join]
  (some (fn [item]
          (when (and (map? item) (contains? item join))
            (let [nss (into #{} (keep namespace) (get item join))]
              (when (= 1 (count nss)) (first nss)))))
        output))

(defn missing-joins
  "`[{:join … :target …}]` for the join roots an output needs and `declared`
   does not have."
  [declared output]
  (vec (for [j (join-roots output)
             :when (and (keyword? j) (not (contains? declared j)))]
         {:join j :target (inferred-target output j)})))

;; ---------------------------------------------------------------------------
;; The `:source` operation block a form edits
;; ---------------------------------------------------------------------------

(defn source-template
  "The `:source` operation block a new resolver starts from, for the type its
   `:source-ref` declares.

   Seeded per connector because a seed of the wrong shape is worse than none:
   an api resolver starting life as `{:type \"sql\" :query \"SELECT …\"}` is
   refused for keys the modeller never chose, and the fix is to delete
   everything the form offered. An unknown or absent type keeps the sql shape —
   sql is what a source is unless something says otherwise, and guessing a
   third shape would be inventing one.

   The api seed is the api-v1 operation block's own key face. `:data_path` is
   deliberately absent — canonical form does not write a default — while
   `:field_mapping` is seeded empty because it is the one key the modeller has
   to fill in for the resolver to return anything, and an empty map says so
   where an absent key would not."
  [source-type]
  (case source-type
    "api" {:type "api" :method "GET" :endpoint "/path/{ns/attr}" :field_mapping {}}
    {:type "sql" :query "SELECT …"}))

(defn resolver-template
  "A create-form seed in dialect-v1 resolver shape. The pool validates the real
   thing on save; this only spares the modeller typing the skeleton.

   Seeded from a dragged hop, the two ends become `:input` and `:output` and
   the result type is `\"single\"` — a hop drawn from one attribute to another
   says 'given this anchor, what is that', which is exactly what `\"single\"`
   answers, and it is the form D2.11 checks for competing producers.

   **Without a hop the seed signs itself** (owner ruling 2026-09-05): no hop
   means `:input []` and `\"list\"`, which is exactly the shape D2.13 refuses
   without `:curated? true`. The seed carries the key so the rule is on screen
   while the resolver is being written rather than at the cut that refuses it;
   deleting the key is how an author declines to sign, and then the anchor has
   to go into `:input`."
  [source-ref source-type from to]
  (let [hop? (and from to)]
    (cond-> {:input (if hop? [(keyword from)] [])
             :output (if hop? [(keyword to)] [])
             :result_type (if hop? "single" "list")
             :source-ref (or source-ref "source-id")
             :source (source-template source-type)
             :limit {:default 50 :max 500}
             :label ""
             :description ""}
      (not hop?) (assoc :curated? true))))

(defn asset-template
  "The create box's seed per pool type: the keys the contract REQUIRES, plus
   the two everybody fills in, in dialect-v1 shape — so a seed saved as it is
   passes the pool, and what is left to type is the values.

   It used to be `{:label \"\" :description \"\"}` for every type but the
   resolver, and an attribute saved from that is refused: D2.14 requires
   `:type` on a leaf (or `:target` on a join). Measured 2026-09-06."
  [kind]
  (case kind
    "attribute" {:type :string :format :text :label "" :description "" :synonyms []}
    "namespace" {:kind :master :label "" :description "" :synonyms []}
    "resolver"  (resolver-template nil nil nil nil)
    "link"      {:from nil :to nil :cardinality :one-to-many :description ""}
    "term"      {:label "" :description "" :format :text :synonyms []}
    "unit"      {:label "" :description ""}
    "metric"    {:entity "" :expression {:contract :duckdb-spill/v1 :text ""}
                 :dimensions [] :label "" :description "" :synonyms []}
    "mutation"  {:result_type "none" :params {} :source {:type "sql" :statement ""}
                 :source-ref "" :label "" :description ""}
    "source"    {:type "sql" :label "" :description ""}
    {:label "" :description ""}))

(def asset-seed-notes
  "The FALLBACK note lines, per type: the shape choices the contract will not
   forgive, said where the keys are (same reasoning as the D2.13 note on a
   resolver). The reader drops them; nothing is saved.

   What the editor normally shows is `key-face-notes` below — the full key face
   derived from the dialect's closed schemas (owner ruling 2026-09-07, doc 84
   §8.4.25), because a key list written here would be a second statement of the
   closed key set and free to drift from it (D1.8). These few lines stay as
   what the box opens with when that route is unreachable."
  {"attribute" [";; leaf: :type (required) + :format / :identity? / :values / :unit / :term"
                ";; join:  replace :type with :target \"other.entity\" + :cardinality :one-to-many"]
   "namespace" [";; :kind is one of :master :transaction :reference :event :bridge"]
   "link"      [";; :from / :to are attribute keywords; omitted, they are filled from the id pair"]
   "metric"    [";; :entity is a namespace string; :dimensions are attribute keywords"]
   "mutation"  [";; :result_type \"none\" ⇔ no :returns; \"single\"/\"list\" need :returns"]})

(def ^:private key-face-footer
  ";; keys already present below are the object's; everything else is optional unless marked required")

(defn- pad-right [s width]
  (str s (apply str (repeat (max 0 (- width (count s))) " "))))

(defn- key-face-say
  "The middle column: what the key is, plus its closed value list when the
   schema declares one. `:required?` and `:variants` are said here rather than
   in the prose, because they come from the schema and the prose does not."
  [{:keys [required? enum variants meaning]}]
  (str (when required? "required — ")
       (when (seq variants) (str "[" (str/join "/" variants) "] "))
       meaning
       (when (seq enum) (str "; one of " (str/join " " (map pr-str enum))))))

(defn- key-face-line [key-width say-width entry]
  (str ";; " (pad-right (pr-str (:key entry)) key-width)
       " " (pad-right (key-face-say entry) say-width)
       (when-let [e (:example entry)] (str "  e.g. " e))))

(defn key-face-notes
  "The EDN comment lines shown above an entry of `type`, built from the key
   face the server derived from the dialect's closed schemas (owner ruling
   2026-09-07, doc 84 §8.4.25).

   Three columns — key, what it is, the literal bytes that write it — because
   the reader is scanning for a key name, and the example is the half a type
   name cannot supply (OQ-MCP-WRITE-ERGONOMICS item 1).

   They are comments: `editor/parse-edn` drops them, so not one byte of them —
   and no template default — reaches the pool (D2.12 canonical form; a written
   `:label \"\"` would fake 'someone described this as empty').

   No key face (the route is unreachable, or an older server) → the short notes
   below. The box has to open with something, and saying less is not the same
   as saying something wrong."
  [type key-face]
  (if-let [entries (seq (:keys key-face))]
    (let [key-width (apply max (map #(count (pr-str (:key %))) entries))
          ;; Align the `e.g.` column against the ordinary lines only: a
          ;; sub-block literal (`:source`, `:batch`) is far longer than a
          ;; screen, and letting it set the width would push every other
          ;; example out of sight.
          say-width (apply max (cons 0 (remove #(> % 72)
                                               (map #(count (key-face-say %)) entries))))]
      (-> [(str ";; " (name type)
                (when-let [s (:summary key-face)] (str " — " s)))]
          (into (map #(key-face-line key-width say-width %)) entries)
          (conj key-face-footer)))
    (vec (get asset-seed-notes (name type)))))

(defn derived-locals
  "The symbols a derived `:expr` may name, in the order the inputs were picked.

   derived-v1 binds each `:input` attribute by its **local name** —
   `:cis.purchase.vpo/total_cost` is `total_cost` inside the expression — so
   the form has to be able to say which words the modeller will actually
   write. Guessing them from the qualified name at typing time is what makes
   the seeded expression compile instead of being a shape to delete."
  [inputs]
  (into [] (comp (filter qualified-keyword?) (map name)) (or inputs [])))

(defn derived-template
  "The create-form seed for a derived resolver.

   Three positions are the contract rather than a convenience:

   - **no `:source-ref`.** derived has no connection at all (derived-v1
     \"连接 schema\"), and a key that names a source the resolver never dials
     is a fact nobody can act on.
   - **`:result_type \"single\"`.** The connector refuses anything but exactly
     one `:output` attribute, and a rule computed from one entity's own
     columns answers for that entity.
   - **`:expr` naming the picked inputs.** Seeded so the first save compiles:
     an expression full of placeholder symbols would be refused at compile
     time for a reason the modeller did not choose."
  [inputs output]
  (let [locals (derived-locals inputs)]
    {:input  (vec (filter qualified-keyword? (or inputs [])))
     :output (if output [output] [])
     :result_type "single"
     :source {:type "derived"
              :expr (if (seq locals)
                      (str "(str " (str/join " \" \" " locals) ")")
                      "(str )")}
     :label ""
     :description ""}))

;; ---------------------------------------------------------------------------
;; Declaring a connection (the Sources page's connect card)
;; ---------------------------------------------------------------------------

(def ^:private env-reference-re #"\$\{[A-Za-z_][A-Za-z0-9_]*\}")

(defn env-reference?
  "Whether a value is one whole `${VAR}` reference.

   api-v1's credential keys take a reference and **refuse a literal at write
   time** (owner ruling 2026-08-28, contracts/connectors/api-v1.md `:auth`).
   The form uses this to say so beside the box rather than let someone travel
   to the server to find out; it never blocks a submit, because
   `validate-source` is the authority on what the contract accepts and a
   second copy of that judgement here would be free to drift."
  [v]
  (boolean (re-matches env-reference-re (str/trim (str v)))))

(def connect-auth-variants
  "api-v1's `:auth` discriminated block, as far as this card takes it.

   `:required` is what the variant cannot be declared without — the whole
   point of the discriminator is that a key is required *per variant*
   (oauth2 without `:token_url` is broken, api_key without it is normal).

   `:defaults` are api-v1's own. The controls show them and the request
   **omits them unchanged**: canonical form has no key for a value equal to
   its default (dialect-v1), the connector applies them anyway, and this form
   has the same reason `put-field` does not to write a key the next reader
   would strip.

   Every other key api-v1 accepts (`:scope`, `:audience`, `:username`,
   `:expiry_skew_s`, `:tls`, …) is deliberately absent and stays reachable
   through the EDN view — the same treatment `:headers` gets, and for the same
   reason: a control per key would be a copy of the contract."
  {"none"    {:keys [] :required []}
   "oauth2"  {:keys [:token_url :client_id :client_secret :grant_type :client_auth]
              :required [:token_url]
              :credential-keys #{:client_id :client_secret}
              :defaults {:grant_type "client_credentials" :client_auth "body"}}
   "api_key" {:keys [:value :header_name :header_prefix]
              :required [:value]
              :credential-keys #{:value}
              :defaults {:header_name "Authorization"}}})

(def connect-shapes
  "The connector types this card can draw a form for, and the connection keys
   it offers for each.

   Which types exist on an instance is the **server's** answer
   (`schema-data`'s `:source-types`, from `spi/registered-types`); this map
   only says which of them this card knows enough to draw. The key faces are
   sql-v1's and api-v1's connection schemas, not a list invented here."
  {"sql" {:keys [:jdbc_url :username :password] :required [:jdbc_url]}
   "api" {:keys [:base_url :health_endpoint]    :required [:base_url] :auth? true}})

(defn connect-shape
  [type]
  (get connect-shapes (or type "sql")))

(defn connect-auth-variant
  "The `:auth` variant a form state has chosen — `none` when nothing has been."
  [form]
  (get connect-auth-variants (or (:auth_type form) "none")
       (get connect-auth-variants "none")))

(defn connect-switch-type
  "The form state after the type selector moves.

   `:id` and `:label` survive the switch: they name the source and describe
   it, and neither belongs to a connector. Every connector key is dropped,
   because a `:jdbc_url` left behind from the previous choice would be
   submitted alongside an api declaration — a key api-v1 has never heard of,
   refused for a reason the modeller did not choose."
  [form type]
  (cond-> {:id (:id form) :label (:label form) :type type}
    (:auth? (connect-shape type)) (assoc :auth_type "none")))

(defn- connect-text
  [form k]
  (str/trim (str (get form k))))

(defn connect-text-present?
  "Whether a connector text field contains a non-blank value.

   Type switches intentionally discard connector-specific keys, so this must
   also be safe for an absent field while the newly selected form renders."
  [form k]
  (boolean (seq (connect-text form k))))

(defn connect-ready?
  "Whether this form has the keys its own type cannot be submitted without.

   Per type, and per `:auth` variant within api: a card that stayed on sql's
   `:jdbc_url` would keep the button dark for an api declaration that is
   complete."
  [form]
  (let [{:keys [required auth?]} (connect-shape (:type form))]
    (and (connect-text-present? form :id)
         (every? #(connect-text-present? form %) required)
         (or (not auth?)
             (every? #(connect-text-present? form %)
                     (:required (connect-auth-variant form)))))))

(defn connect-auth-block
  "The `:auth` block one form state declares — **nil** for `none`.

   nil rather than `{}` or `nil`-valued: an absent key is how canonical form
   says 'no auth' (dialect-v1), while `:auth {}` would be a discriminated
   block missing the `:type` api-v1 requires, refused on arrival."
  [form]
  (let [type (or (:auth_type form) "none")
        {:keys [keys defaults]} (connect-auth-variant form)]
    (when-not (= "none" type)
      (into {:type type}
            (for [k keys
                  :let [v (connect-text form k)]
                  :when (and (seq v) (not= v (get defaults k)))]
              [k v])))))

(defn connect-request
  "The form state as the request the connect endpoint receives.

   **Only the chosen type's keys travel.** A blank box is not a value — an
   absent key is how canonical form says 'not declared' — and a `:jdbc_url`
   riding along with an api declaration would be refused by api-v1 for a
   reason nobody chose. `:prefix` and `:register-unverified?` are the caller's
   to add: they are about this run, not about the source."
  [form]
  (let [type (or (:type form) "sql")
        {:keys [keys auth?]} (get connect-shapes type)
        auth (when auth? (connect-auth-block form))]
    (cond-> (into {} (for [k (into [:id :type :label] keys)
                           :let [v (connect-text form k)]
                           :when (seq v)]
                       [k v]))
      auth (assoc :auth auth))))

;; ---------------------------------------------------------------------------
;; Signing a named query (D2.13)
;; ---------------------------------------------------------------------------

(defn curated-required?
  "Whether this resolver entry is the shape D2.13 refuses without
   `:curated? true`: a `\"list\"` with no anchor in `:input`.

   The editor seeds the key and refuses a save without it (owner ruling
   2026-09-05). The signature is still the author's act — D2.13 asks for a
   person to sign for the convergence baked into the source — the editor only
   stops hiding the pen, because today the rule is learned at cut time
   (`:cut.resolver/unsigned-named-query`) and the author comes back to a
   refusal nothing had mentioned.

   Declining to sign means deleting the key and anchoring `:input`, never
   writing `:curated? false`: false is the default and a written default is
   not canonical form (D2.12)."
  [entry]
  (and (= "list" (:result_type entry))
       (empty? (:input entry))))

;; ---------------------------------------------------------------------------
;; What a resolver's output leaves need declared (D2.14)
;; ---------------------------------------------------------------------------

(defn output-leaves
  "The **scalar** attributes an `:output` tree names: the bare keywords at the
   top and the members under every branch.

   The branch keys themselves are join attributes and belong to `join-roots`,
   which declares them with `:target` instead of `:type` (D2.29). Two kinds of
   entry, two declarations — reading them off one tree in one pass would give
   the join a `:type` it must not have."
  [output]
  (into []
        (distinct)
        (concat (filter keyword? (or output []))
                (mapcat (fn [item]
                          (when (map? item) (mapcat val item)))
                        (or output [])))))

(defn missing-output-attributes
  "The output leaves this catalog does not declare.

   Without them the resolver write is refused with
   `:dialect.invariant/unregistered-reference` — a refusal that names the
   attribute and offers no way to make it exist. The form asks for the
   declaration first instead (owner ruling 2026-09-05), the same way it
   already asks for the join roots."
  [declared output]
  (vec (for [a (output-leaves output)
             :when (and (keyword? a) (not (contains? declared a)))]
         a)))

(defn- value-kind
  "The coarse kind of one static value. Numbers are one kind here on purpose:
   JavaScript has a single number type, so `2.0` and `2` are the same value and
   a per-value integer/decimal split would make a column's type depend on which
   row happened to be round."
  [v]
  (cond (string? v) :string
        (boolean? v) :boolean
        (number? v) :number
        :else nil))

(defn static-rows
  "A static operation block's `:data` as a row seq — one map is one row
   (static-v1: `:data` is a map or a seq of maps)."
  [source]
  (when (= "static" (:type source))
    (let [d (:data source)]
      (cond (map? d) [d]
            (sequential? d) (filter map? d)
            :else nil))))

(defn inferred-attribute-type
  "The `:type` a missing attribute can be pre-filled with, or nil for \"ask\".

   Only a **static** source answers: its `:data` carries the values, so the
   type is read rather than guessed. nil when the key is absent from every
   row, or when the rows disagree — a type filled in from a column that is a
   string in one row and a number in the next would be a guess wearing the
   author's signature. A numeric column is `:integer` only when every value is
   integral, `:decimal` otherwise: dialect-v1 has no single numeric type, and
   `:decimal` is the one that holds both. sql and api answer nil by
   construction: nothing here dials a source, and probing one from a form
   would be a query the author did not ask for."
  [source attr]
  (let [vals* (into [] (keep #(get % attr)) (static-rows source))
        kinds (into #{} (map value-kind) vals*)]
    (when (= 1 (count kinds))
      (case (first kinds)
        :number (if (every? integer? vals*) :integer :decimal)
        nil nil
        (first kinds)))))


(defn missing-attributes-template
  "The EDN block the declare-attributes gate opens with: one entry per missing
   output leaf, `{:type …}` pre-filled where a static source says so and
   `{:type nil}` where nothing can be inferred.

   A block rather than a table (owner ruling 2026-09-05: creating and editing a
   pool object is EDN-only). `nil` is written on purpose — an absent `:type`
   and an unanswered one look the same in a map, and the author has to be able
   to see which entries are still asking. Sorted so two passes over the same
   output read the same way."
  [declared entry]
  (into (sorted-map-by (fn [a b] (compare (str a) (str b))))
        (for [a (missing-output-attributes declared (:output entry))]
          [a {:type (inferred-attribute-type (:source entry) a)}])))

(defn parse-attribute-declarations
  "The gate's EDN block as the attribute writes it stands for, or one message
   saying why it cannot be.

   Refused here rather than one server round trip later: an entry with neither
   `:type` (D2.14, a scalar) nor `:target` (D2.29, a join) is one the pool will
   refuse for a key the author can see is empty. Every entry is written to the
   pool **verbatim** — this reads the block, it does not rewrite it, so a label
   or a `:cardinality` the author added travels as typed."
  [value]
  (cond
    (not (map? value))
    {:error "The declarations are an EDN map of attribute id → entry."}

    (empty? value)
    {:error "Declare at least one attribute, or go back to editing."}

    :else
    (let [bad (for [[k v] value
                    :let [why (cond
                                (not (qualified-keyword? k))
                                "an attribute id is entity/name, e.g. nifi.instance/host"
                                (not (map? v)) "an entry is an EDN map"
                                (and (nil? (:type v)) (nil? (:target v)))
                                "needs a :type (D2.14), or a :target if it is a join (D2.29)")]
                    :when why]
                (str k " — " why))]
      (if (seq bad)
        {:error (str/join "; " bad)}
        {:rows (vec (for [[k v] (sort-by (comp str key) value)]
                      {:attribute k :entry v}))}))))

;; ---------------------------------------------------------------------------
;; Request shapes
;; ---------------------------------------------------------------------------
;;
;; What the UI hands to a route, built here rather than inline in a component
;; — because the seam between the two is where a shipped bug lived unnoticed:
;; the Assets drawer read an id back before sending it, the route read it
;; again, and `"demo-db"` arrived at the pool as the SYMBOL `demo-db`. No
;; identity, no actions, and `/api/studio/removal` answered 200 with
;; `:removed? false`, which the screen reported as a successful removal.
;;
;; Nothing on the clj side could catch that: the server suite sends the right
;; shape. So the shapes live here, where plain `cljs.test` can pin them.

(defn object-ref
  "One pool object's identity as every route takes it: a **string** type and a
   **printed** id.

   Ids are printed because a keyword (`:cis.users/name`), a string (a source's
   `\"demo-db\"`) and a link's endpoint pair (`[:a/b :c/d]`) share one field,
   and only EDN text carries all three back unchanged. `id` here is the VALUE
   — pass the printed text to `printed-object-ref` instead; a value printed
   twice is as wrong as a value never printed."
  [type id]
  {:type (name type) :id (pr-str id)})

(defn printed-object-ref
  "The same identity, from an id that **already arrived printed** — the form
   every object list carries, so that a row's id can be sent back without
   being read and re-printed on the way.

   Refuses anything but text: an id that was read first is exactly the defect
   this shape exists to prevent, and a silent `pr-str` of it here would print
   a keyword to `\":a/b\"`… which reads back as a STRING, not as the keyword."
  [type printed-id]
  (when-not (string? printed-id)
    (throw (ex-info "An object id must already be printed EDN text here"
                    {:type type :id printed-id})))
  {:type (name type) :id printed-id})

(defn removal-request
  "The body `/api/studio/removal/plan` and `/api/studio/removal` take for a
   picked set — `{:objects [ref …]}`, refs from `object-ref` /
   `printed-object-ref`.

   The picked-set form is used even for one object: it plans the closure that
   goes with it (the resolver whose source is being removed), where the single
   `{:type … :id …}` form plans that object alone."
  [refs]
  {:objects (vec refs)})

(defn duplicate-namespace
  "Suggest a valid asset namespace from a graph name, preserving valid spelling."
  [graph-name]
  (let [value (str/replace (str/trim graph-name) #"[^A-Za-z0-9_.-]+" "-")]
    (cond (str/blank? value) ""
          (re-find #"^[A-Za-z]" value) value
          :else (str "graph-" value))))

(defn valid-user-email? [email]
  (boolean (re-matches #"[^\s@]+@[^\s@]+\.[^\s@]+" (str/trim (or email "")))))
