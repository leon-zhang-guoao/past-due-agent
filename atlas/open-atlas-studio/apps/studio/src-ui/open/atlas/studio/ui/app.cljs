(ns open.atlas.studio.ui.app
  "The workbench: two pages, built for a model of 500 resolvers, 3,000 attributes
   and 100 entities.

   Graph Editor answers the question a modeller actually arrives with — 'I have
   this attribute; who takes it as input, and what can I reach from it' — so that
   ledger leads. Query keeps the request in two documents, the wanted
   attributes above and the known entity below, because that is how the request
   is thought about.

   Nothing is persisted in the browser: no headers, no credentials, no result
   history (ADR-012, ADR-012-A §4)."
  (:require [cljs.pprint :as pprint]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [open.atlas.studio.ui.api :as api]
            [open.atlas.common.ui.access :as access]
            [open.atlas.common.ui.query-details :as query-details]
            [open.atlas.studio.ui.mcp-connections :as mcp-connections]
            [open.atlas.studio.ui.variables :as variables]
            [open.atlas.studio.ui.graph-actions :as graph-actions]
            [open.atlas.studio.ui.guidance :as guidance]
            [open.atlas.studio.ui.navigation :as navigation]
            [open.atlas.studio.ui.table :as table]
            [open.atlas.studio.ui.attribute-graph :as attribute-graph]
            [open.atlas.studio.ui.contract-editor :as contract-editor]
            [open.atlas.studio.ui.editor :as editor]
            [open.atlas.studio.ui.form-logic :as fl]
            [open.atlas.studio.ui.graph :as graph]
            [open.atlas.studio.ui.split :refer [gutter]]
            [open.atlas.studio.ui.theme :as theme]
            [open.atlas.studio.ui.semantic :as semantic-ui :refer [semantic-editor]]
            [open.atlas.studio.ui.source-summary :as source-summary]
            [open.atlas.studio.ui.query-tools :as query-tools :refer [contract-query-tools]]
            [open.atlas.studio.ui.trace :as trace]
            [open.atlas.studio.ui.vlist :refer [vlist]]
            [uix.core :as uix :refer [defui $]]
            [uix.dom]))

(def workspace-scope
  "The graph picker's value for \"the whole workspace\". A qualified name always
   contains a slash (D3.10), so this cannot be one."
  "*")

(defn- workspace-scope? [root] (= workspace-scope root))

;; ---------------------------------------------------------------------------
;; Where the person was — so a reload lands there, not on the default
;; ---------------------------------------------------------------------------

(def ^:private ui-state-key "open-atlas-studio.ui")

(defn- ui-recall
  "The navigation state a previous render of this browser left behind: the
   page, the workspace tab, and what was selected on the pages that have a
   selection. A reload used to land on the Graphs page whatever was open,
   which reads as «it jumped somewhere else» (owner, 2026-09-05).

   Browser-local on purpose: this is where ONE person was, not a fact about
   the model or the workspace, so it belongs to the browser and not to the
   server's context. Read defensively — storage can be absent, full or hold
   something an older build wrote."
  []
  (try
    (let [v (some-> (.getItem js/localStorage ui-state-key) reader/read-string)]
      (when (map? v) v))
    (catch :default _ nil)))

(defn- ui-remember!
  "Record one key of navigation state. Whole-value replace under `k`; every
   other key is left as it was."
  [k v]
  (try
    (.setItem js/localStorage ui-state-key (pr-str (assoc (or (ui-recall) {}) k v)))
    (catch :default _ nil)))

(defn- ui-recall-in [ks] (get-in (ui-recall) ks))

(defn- entity-of [attr] (first (str/split attr #"/")))

(defn- graph-label
  "How a scope is named in the picker and the header.

   The workspace scope has no qualified name, so it is named after the
   workspace itself — showing the sentinel would read as a graph called `*`.
   The arity without a workspace is for callers that have only the value."
  ([qualified-name] (graph-label qualified-name nil))
  ([qualified-name workspace]
   (cond
     (= workspace-scope qualified-name) (str "Workspace-" (or workspace "…"))
     ;; `demo/cis` is the aggregate graph (the old `demo-all` name was
     ;; retired), so make that fact explicit in the selector rather than
     ;; making a modeller infer it from the registry name.
     (= "demo/cis" qualified-name)      "All · demo/cis"
     :else                              qualified-name)))

(defn- error-text [{:keys [message diagnostics] :as error}]
  ;; Error payloads are deliberately structured by Studio's server.  Pretty
  ;; print them in the Result pane so the modeller can see the failing Pathom
  ;; phase, resolver/source metadata, and any missing attributes rather than
  ;; one shortened outer exception.
  (str (or message "Request failed")
       (when (seq diagnostics)
         (str "\n\nDiagnostics\n"
              (with-out-str (pprint/pprint diagnostics))))))

(def ^:private error-code-messages
  {:forbidden "You do not have permission for this action."
   :unauthorized "Your session is not authorized. Please sign in again."
   :not-found "The requested item could not be found."
   :request-timeout "The request timed out. The platform did not answer in time."
   :upstream-timeout "The upstream service timed out. Please retry."
   :network "A network error occurred. Please check connectivity and retry."
   :connection "Could not connect to the requested service. Please retry."
   :upstream-connection "Could not connect to the upstream service. Please retry."
   :validation-error "The provided input is invalid. Please check the form values."
   :invalid-argument "The provided input is invalid. Please check the form values."
   :already-exists "This value already exists."
   :conflict "That change conflicts with the current state."
   :rate-limited "The service is temporarily rate limited. Please try again in a moment."
   :maintenance "The platform is temporarily unavailable. Please try again later."
   :network-error "Network request failed. Please check your connection and retry."})

(defn- status-code-message [status]
  (case status
    400 "The request is invalid."
    401 "Your session is not authorized. Please sign in again."
    403 "You do not have permission for this action."
    404 "The requested item could not be found."
    409 "The change conflicts with the current state."
    422 "The provided input is invalid. Please check the form values."
    429 "The service is temporarily rate limited. Please try again in a moment."
    503 "The platform is temporarily unavailable. Please try again later."
    nil))

(defn- normalize-api-error [error]
  (let [trimmed (fn [value]
                  (when (string? value)
                    (str/trim value)))
        request-id (when (map? error) (:request-id error))
        retry-after-ms (when (map? error) (:retry-after-ms error))
        attempt (when (map? error) (:attempt error))
        next-retry-ms (when (map? error) (:next-retry-ms error))
        remaining (when (map? error) (:remaining-retries error))
        request-hint (when request-id (str "Request ID: " request-id))
        retry-hint (when retry-after-ms
                     (str "Retry after: " (js/Math.round (/ retry-after-ms 1000)) "s"))
        attempt-hint (when attempt (str "Attempt: " attempt (when (number? remaining) (str "/" (inc remaining)))))
        delay-hint (when next-retry-ms
                     (str "Next retry in: " (js/Math.round (/ next-retry-ms 1000)) "s"))
        normalize-code (fn [code]
                        (when (some? code)
                          (error-code-messages (if (keyword? code) code (keyword (str code))))))
        normalize-status (fn [status]
                          (when (some? status)
                            (status-code-message
                              (if (number? status)
                                status
                                (js/parseInt (str status) 10)))))
        extract-message (fn extract-message [error]
                         (cond
                           (string? error) (trimmed error)
                           (and (map? error) (string? (:message error)))
                           (trimmed (:message error))
                           (and (map? error) (string? (:problem error)))
                           (trimmed (:problem error))
                           (and (map? error) (:error error))
                           (extract-message (:error error))
                           :else nil))]
    (if-let [message (or (extract-message error)
                         (and (and (map? error) (:retryable error))
                              "This failure is temporary. Retry may succeed.")
                         (and (and (map? error) (:code error)) (normalize-code (:code error)))
                         (and (and (map? error) (:status error)) (normalize-status (:status error))))]
      (str message
           (when request-hint
             (str " " request-hint))
           (when attempt-hint
             (str " " attempt-hint))
           (when delay-hint
             (str " " delay-hint))
           (when retry-hint
             (str " " retry-hint)))
      "Could not complete this action.")))

(def ^:private ns-width
  "How much of a namespace a rail row shows before it gives way to the name."
  16)

(defn- short-ns
  "`cis.sales.mso_order_detail` -> `…es.mso_order_detail`.

   Truncated here rather than with CSS: `direction: rtl` clips from the left as
   wanted but also drags the trailing slash to the front of the row, and the
   bidi rules that cause that are not worth fighting for one label."
  [ns']
  (if (> (count ns') ns-width)
    (str "…" (subs ns' (- (count ns') ns-width)))
    ns'))

;; ---------------------------------------------------------------------------
;; Left rail: one filter across the three kinds
;; ---------------------------------------------------------------------------

(defui member-menu
  "The right-click menu on a rail row: put this object — or the whole entity it
   belongs to — into the graph, or take it out.

   **Where you see a thing is where you should be able to act on it.** Adding
   an object used to mean leaving what you were looking at, opening the Recipe
   panel, and finding the same object again in a different list.

   Two items, not one, because there are two honest intents and guessing
   between them is worse than asking: the object under the cursor, and the
   entity it is part of. An attribute alone is usually not what someone means
   by `add SSO` — but sometimes it is exactly what they mean."
  [{:keys [at on-close on-apply]}]
  (let [{:keys [label in? entity entity-in?]} at]
    ($ :div.ctx-backdrop {:on-click on-close
                          :on-context-menu (fn [e] (.preventDefault e) (on-close))}
       ($ :div.ctx-menu {:style {:left (:x at) :top (:y at)}
                         :on-click (fn [e] (.stopPropagation e))}
          ($ :div.ctx-head ($ :code.mono label))
          ($ :button {:on-click #(do (on-apply {:scope :object :in? (not in?)}) (on-close))}
             (if in? "remove from this graph" "add to this graph"))
          (when entity
            ($ :button {:on-click #(do (on-apply {:scope :entity :in? (not entity-in?)})
                                       (on-close))}
               (str (if entity-in? "remove all of " "add all of ") entity)))))))

(defui rail [{:keys [catalog query on-query kind on-kind selected on-select
                     can-edit? on-create-resolver on-create-term on-create-link
                     on-create-source on-create-join on-create-entity on-create-attribute
                     on-create-derived
                     on-create-metric on-create-mutation on-create-unit
                     on-context]}]
  (let [counts     (:counts catalog)
        namespaces (map :entity (:attributes catalog))
        items      (case kind
                     ;; One list, both kinds (D2.29): a join root is an
                     ;; attribute carrying `:target`, so it arrives here with
                     ;; the scalars instead of being merged in from a second
                     ;; catalog key. It was always queried the way an attribute
                     ;; is, and a filter that cannot find `user_posts` reads as
                     ;; "it does not exist" when it is published and serving
                     ;; (demo-ch, 2026-08-31).
                     :attributes (:attributes catalog)
                     :resolvers  (:resolvers catalog)
                     :terms      (:terms catalog)
                     :links      (:links catalog)
                     :sources    (:sources catalog)
                     :metrics    (:metrics catalog)
                     :mutations  (:mutations catalog)
                     :units      (:units catalog)
                     (:entities catalog))]
    ($ :<>
       ($ :div.pane-head
          ($ :span "Explorer")
          ($ :span.spacer)
          (when (:truncated? catalog) ($ :span.count "first matches"))
          ;; What "new" makes follows the tab you are on, so the button never
          ;; offers to create the thing you are not looking at.
          (when can-edit?
            (case kind
              :terms ($ :button.btn {:title "Create a term" :on-click on-create-term} "+ term")
              :links ($ :button.btn {:title "Declare a link" :on-click on-create-link} "+ link")
              :sources ($ :button.btn {:title "Declare a source" :on-click on-create-source} "+ source")
              ;; The three registries that had no door (owner, 2026-09-05):
              ;; they fell through to «+ resolver» — a button that was there
              ;; and made the wrong thing. They live here and not on the
              ;; Assets tab because their forms pick entities, attributes and
              ;; sources, and only this page holds the catalog to pick from.
              :metrics ($ :button.btn {:title "Define a metric" :on-click on-create-metric} "+ metric")
              :mutations ($ :button.btn {:title "Declare a mutation" :on-click on-create-mutation} "+ mutation")
              :units ($ :button.btn {:title "Register a unit" :on-click on-create-unit} "+ unit")
              ;; The entities tab is where a path starts, so that is where the
              ;; button to declare one belongs — a join is an attribute
              ;; (D2.29), but the two things it needs are an entity to leave
              ;; from and an entity to reach.
              ;; Two buttons, because a path needs both ends: the entity to
              ;; leave from, and the hop. «+ entity» was only on the Workspace
              ;; page, so declaring one meant leaving the graph you were
              ;; building and coming back to tick it (owner, 2026-09-05). It
              ;; opens the SAME create form that page opens — one form, two
              ;; doors — and here it keeps the form's «add to graph» tick, so
              ;; the new entity joins the recipe on save.
              ;; The attributes tab fell through to «+ resolver» too: a scalar
              ;; attribute could only be declared from the Workspace page, and
              ;; then ticked into the graph on a second visit. Same door as
              ;; «+ entity»: the one create form, with the graph in context.
              :attributes ($ :button.btn {:title "Declare an attribute"
                                          :on-click on-create-attribute} "+ attribute")
              :entities ($ :<>
                           ($ :button.btn {:title "Declare an entity"
                                           :on-click on-create-entity} "+ entity")
                           ($ :button.btn {:title "Declare a join from this entity to another"
                                           :on-click on-create-join} "+ join"))
              ;; Two buttons on the resolvers tab, because there are two kinds
              ;; of resolver and only one of them has a database behind it. A
              ;; derived rule authored through the sql form means deleting the
              ;; skeleton and remembering that `:source-ref` must not be there.
              :resolvers ($ :<>
                            ($ :button.btn {:title "Create a resolver"
                                            :on-click on-create-resolver}
                               "+ resolver")
                            ($ :button.btn {:title "Compute an attribute from other attributes"
                                            :on-click on-create-derived}
                               "+ derived"))
              ($ :button.btn {:title "Create a resolver" :on-click on-create-resolver}
                 "+ resolver"))))
       ($ :input.search {:type "search" :value query
                         :placeholder "filter — try an attribute"
                         :aria-label "Filter the model"
                         :auto-focus true
                         :on-change #(on-query (.. % -target -value))})
       ;; Its own head row: the standard `.pane-head` is a fixed-height nowrap
       ;; line, so six kinds could only overflow it and be clipped.
       ($ :div.pane-head.kinds-head
          ($ :div.seg.kinds
             (for [[k label n] [[:attributes "attrs" (:attributes counts)]
                                [:resolvers "resolvers" (:resolvers counts)]
                                [:entities "entities" (:entities counts)]
                                [:terms "terms" (:terms counts)]
                                [:links "links" (:links counts)]
                                [:sources "sources" (:sources counts)]
                                [:metrics "metrics" (:metrics counts)]
                                [:mutations "mutations" (:mutations counts)]
                                [:units "units" (:units counts)]]]
               ($ :button {:key (name k)
                           :class (when (= kind k) "on")
                           :on-click #(on-kind k)}
                  ;; `counts` is not in the catalog DTO, so `n` is normally
                  ;; nil — without this the label carried a trailing space.
                  (if n (str label " " n) label)))))
       (case kind
         :attributes
         ($ vlist {:items items
                   :key-fn :attribute
                   :selected (when (= :attribute (:kind selected)) (:id selected))
                   :on-select #(on-select {:kind :attribute :id (:attribute %) :data %})
                   :on-context (when on-context
                                 #(on-context (assoc % :kind :attributes
                                                     :id (:attribute (:item %))
                                                     :entity (:entity (:item %)))))
                   :empty-note "No attribute matches."
                   ;; At CIS scale every row starts `cis.<domain>.<table>/`, so the
                   ;; part that identifies the attribute is the part that gets cut.
                   ;; The namespace is shown dimmed and truncates first; the name
                   ;; always survives.
                   :render-row (fn [a]
                                 ($ :<>
                                    ($ :span.dot {:style {:background (theme/namespace-color
                                                                       namespaces (:entity a))}})
                                    ($ :span.ns (str (short-ns (:entity a)) "/"))
                                    ($ :span.leaf (last (str/split (:attribute a) #"/")))
                                    ;; A join says where it goes; a scalar
                                    ;; says how many resolvers read it. Both
                                    ;; are the one fact worth a row's tail.
                                    (if-let [t (:target a)]
                                      ($ :span.meta (str "→ " (short-ns t)))
                                      ($ :span.meta (str (count (:consumers a)) "→")))))})

         :sources
         ($ vlist {:items items
                   :key-fn :id
                   :selected (when (= :source (:kind selected)) (:id selected))
                   :on-select #(on-select {:kind :source :id (:id %) :data %})
                   :empty-note "No source matches."
                   :render-row (fn [s]
                                 ($ :<>
                                    ($ :span.name (:id s))
                                    ($ :span.meta (str (:type s) " · "
                                                       (count (:used-by s)) "←"))))})

         :links
         ($ vlist {:items items
                   :key-fn (fn [l] (str (:from l) "→" (:to l)))
                   :selected (when (= :link (:kind selected)) (:id selected))
                   :on-select #(on-select {:kind :link :id (:id %) :data %})
                   :empty-note "No link matches."
                   :render-row (fn [l]
                                 ($ :<>
                                    ($ :span.leaf (last (str/split (:from l) #"/")))
                                    ($ :span.via " → ")
                                    ($ :span.leaf (last (str/split (:to l) #"/")))))})

         :metrics
         ($ vlist {:items items
                   :key-fn :id
                   :selected (when (= :metric (:kind selected)) (:id selected))
                   :on-select #(on-select {:kind :metric :id (:id %) :data %})
                   :empty-note "No metric matches."
                   :render-row (fn [m]
                                 ($ :<>
                                    ($ :span.name (:id m))
                                    ($ :span.meta (or (:entity m) ""))))})

         :mutations
         ($ vlist {:items items
                   :key-fn :id
                   :selected (when (= :mutation (:kind selected)) (:id selected))
                   :on-select #(on-select {:kind :mutation :id (:id %) :data %})
                   :empty-note "No mutation matches."
                   :render-row (fn [m]
                                 ($ :<>
                                    ($ :span.name (:id m))
                                    ($ :span.meta (or (:result_type m) ""))))})

         :units
         ($ vlist {:items items
                   :key-fn :id
                   :selected (when (= :unit (:kind selected)) (:id selected))
                   :on-select #(on-select {:kind :unit :id (:id %) :data %})
                   :empty-note "No unit matches."
                   :render-row (fn [u]
                                 ($ :<>
                                    ($ :span.name (:id u))
                                    ($ :span.meta (or (:symbol u) (:label u) ""))
                                    ;; A unit nothing in this graph uses is
                                    ;; still in it — declared and unused is a
                                    ;; state, not an absence.
                                    (when-not (:used? u) ($ :span.tag "unused"))))})

         :terms
         ($ vlist {:items items
                   :key-fn :id
                   :selected (when (= :term (:kind selected)) (:id selected))
                   :on-select #(on-select {:kind :term :id (:id %) :data %})
                   :empty-note "No term matches."
                   :render-row (fn [t]
                                 ($ :<>
                                    ($ :span.name (:id t))
                                    ($ :span.meta (or (:label t) ""))))})

         :resolvers
         ($ vlist {:items items
                   :key-fn :id
                   :selected (when (= :resolver (:kind selected)) (:id selected))
                   :on-select #(on-select {:kind :resolver :id (:id %) :data %})
                   :on-context (when on-context
                                 #(on-context (assoc % :kind :resolvers
                                                     :id (:id (:item %)))))
                   :empty-note "No resolver matches."
                   :render-row (fn [r]
                                 ($ :<>
                                    ($ :span.name (:id r))
                                    ($ :span.meta (str (:source-type r)
                                                       (when (:batch? r) " ·batch")
                                                       " " (:output-count r) "→"))))})

         ($ vlist {:items items
                   :key-fn :entity
                   :selected (when (= :entity (:kind selected)) (:id selected))
                   :on-select #(on-select {:kind :entity :id (:entity %) :data %})
                   :empty-note "No entity matches."
                   :render-row (fn [e]
                                 ($ :<>
                                    ($ :span.dot {:style {:background (theme/namespace-color
                                                                       namespaces (:entity e))}})
                                    ($ :span.name (:entity e))
                                    ($ :span.meta (str (:attribute-count e) " attrs"))))})))))

;; ---------------------------------------------------------------------------
;; Dossier
;; ---------------------------------------------------------------------------

(def ^:private chip-threshold
  "Above this many entries a ledger stops being a list and becomes chips. A
   resolver with forty outputs is normal; forty stacked rows are a wall."
  10)

(def ^:private chip-preview 24)

(defn- shared-prefix
  "The namespace every entry shares, if they share one. Lifting `customer/` into
   the heading turns forty rows of noise into forty short words."
  [items]
  (let [nss (map #(when (string? %) (first (str/split % #"/"))) items)]
    (when (and (seq nss) (every? some? nss) (apply = nss) (> (count items) 1))
      (first nss))))

(defui ledger [{:keys [title lead? items empty-note render]}]
  (let [[expanded set-expanded!] (uix/use-state false)
        n       (count items)
        chips?  (> n chip-threshold)
        prefix  (when chips? (shared-prefix items))
        shown   (if (or expanded (<= n chip-preview)) items (take chip-preview items))]
    ($ :div {:class (str "ledger" (when lead? " lead"))}
       ($ :h3 title
          (when (seq items) ($ :span.count (str "  " n)))
          (when prefix ($ :span.prefix (str prefix "/"))))
       (cond
         (empty? items) ($ :p.none (or empty-note "none"))

         chips?
         ($ :<>
            ($ :ul {:class (str "chips" (when expanded " expanded"))}
               (for [[i item] (map-indexed vector shown)]
                 ($ :li {:key i}
                    (render (cond-> item
                              (and prefix (string? item)) (subs (inc (count prefix))))
                            item))))
            (when (> n (count shown))
              ($ :button.more {:on-click #(set-expanded! true)}
                 (str "+ " (- n (count shown)) " more"))))

         :else
         ($ :ul (for [[i item] (map-indexed vector items)]
                  ($ :li {:key i} (render item item))))))))

;; ---------------------------------------------------------------------------
;; Small inputs the wizards still draw
;;
;; The **entry field form is gone** (owner ruling 2026-09-05: creating and
;; editing any pool object is EDN-only — "forms are unreliable"). What is left
;; here is what the probe-driven wizards use: they are workflows over a live
;; source, not editors of a pool entry.
;; ---------------------------------------------------------------------------

;; Both halves of the keyword round trip live in `form-logic`; a second copy
;; here is how the two would eventually disagree about what a modeller typed.
(def ^:private kw-text fl/kw-text)
(def ^:private text-kw fl/text-kw)

(defn- text-list
  "Comma/newline separated text → vector of trimmed strings."
  [s]
  (->> (str/split (or s "") #"[,\n]")
       (map str/trim) (remove str/blank?) vec))

(defui field-row [{:keys [label hint children]}]
  ($ :div.field-row
     ($ :label.field-label label
        (when hint ($ :span.field-hint hint)))
     children))

(defui draft-input
  "A text input whose value is **parsed on blur, not on every keystroke**.

   The obvious spelling of a comma-separated field — parse each keystroke and
   render the parsed value back — cannot type a second item at all: the parser
   drops the empty tail, so the comma is swallowed the instant it is pressed
   and the caret lands back on the first word. A controlled input must not
   normalise a value someone is still in the middle of writing.

   The draft is held here and published on blur; the parsed value flows back in
   only while the field is not being edited, so a re-render from elsewhere
   cannot overwrite what is half-typed."
  [{:keys [value->text text->value value on-change aria-label placeholder list-id
           spell-check?]}]
  (let [[draft set-draft!] (uix/use-state nil)]
    ($ :input (cond-> {:value (or draft (value->text value))
                       :aria-label aria-label
                       :on-change #(set-draft! (.. % -target -value))
                       :on-blur (fn [e]
                                  (let [parsed (text->value (.. e -target -value))]
                                    (set-draft! nil)
                                    (on-change parsed)))}
                placeholder    (assoc :placeholder placeholder)
                list-id        (assoc :list list-id)
                (some? spell-check?) (assoc :spell-check spell-check?)))))

(defn- findings-report
  "The plain-text form of a rejection: what a person pastes into a ticket or a
   chat. Title, request reference, then every finding with its location and its
   expected/got — everything the box on screen shows, and nothing it hides."
  [error title]
  (str/join
   "\n"
   (concat
    [title]
    (when-let [id (:request-id error)] [(str "request: " id)])
    (when (and (:message error) (empty? (:findings error)))
      [(str (:message error) (when (:code error) (str " (" (:code error) ")")))])
    (for [f (:findings error)]
      (str "- " (:code f)
           (when-let [path (:path f)] (str " " (pr-str path)))
           " — " (:message f)
           (when (contains? f :expected) (str "\n    expected: " (pr-str (:expected f))))
           (when (contains? f :got) (str "\n    got: " (pr-str (:got f)))))))))

(defui findings-view
  "Structured rejections, finding-v1 shaped: code, path, message, expected/got.
   An `:expected_revision` conflict arrives here as one more finding, not as a
   special case.  A transport-level error without findings falls back to its
   code + message.

   `compact?` is for a result that already has a headline of its own (the
   connection check says «Validation failed» itself): it drops the generic
   title and next-step sentence and keeps only the findings.  Both forms carry
   «Copy details», because a rejection is the thing someone needs to hand to
   another person to debug."
  [{:keys [error compact?]}]
  (let [findings (:findings error)
        {:keys [title next]} (guidance/error-guidance error)
        [copied set-copied!] (uix/use-state nil)
        copy! (fn []
                (if-let [clipboard (.-clipboard js/navigator)]
                  (-> (.writeText clipboard (findings-report error title))
                      (.then #(set-copied! :ok))
                      (.catch #(set-copied! :failed)))
                  (set-copied! :failed))
                (js/setTimeout #(set-copied! nil) 1500))]
    ($ :div.error-guidance {:role "alert" :class (when compact? "error-guidance--compact")}
      (when-not compact? ($ :strong title))
      (when-not compact? ($ :p next))
      (when-let [request-id (:request-id error)]
        ($ :p.error-reference "Request reference: " ($ :code request-id)))
      (if (seq findings)
      ($ :ul.findings
         (for [[i f] (map-indexed vector findings)]
           ($ :li.finding {:key i :class (name (or (:severity f) :error))}
              ($ :div.finding-head
                 ($ :span.code (str (:code f)))
                 (when-let [p (:path f)] ($ :span.mono (pr-str p))))
              ($ :div.finding-message (:message f))
              (when (contains? f :expected)
                ($ :div.mono (str "expected: " (pr-str (:expected f)))))
              (when (contains? f :got)
                ($ :div.mono (str "got: " (pr-str (:got f))))))))
      ($ :p.hint.bad (str (:message error)
                          (when (:code error) (str " (" (:code error) ")")))))
      ($ :button.btn.tiny.eg-copy {:type "button" :on-click copy!
                                   :title "Copy this error, with its request reference, as plain text"}
         (case copied :ok "Copied" :failed "Copy failed" "Copy details")))))

(defui preflight-warning
  "What a cut would refuse **because of this action**, at the moment of saving.

   Not a block. D2.2 is \"free to write, strict to seal\": the pool accepts this,
   and the Builder turning it into a refusal would be changing that ruling
   rather than serving it. So it is a soft confirmation — say what will happen
   at seal time, and let the author decide."
  [{:keys [report on-save on-cancel saving?]}]
  (let [introduced (:introduced-findings report)
        existing   (:existing-findings report)]
    ($ :div.preflight
       ($ :p.hint.bad
          (str "This can be saved, but the graph will not cut a version: "
               (count introduced) " problem"
               (when (not= 1 (count introduced)) "s")
               " introduced by this change."))
       ($ findings-view {:error {:findings introduced}})
       (when (seq existing)
         ($ :details.preflight-existing
            ($ :summary (str (count existing) " problem"
                             (when (not= 1 (count existing)) "s")
                             " were already there — not this change's doing"))
            ($ findings-view {:error {:findings existing}})))
       ($ :div.asset-editor-actions
          ($ :button.btn.primary {:disabled saving? :on-click on-save}
             (if saving? "Saving…" "Save anyway"))
          ($ :button.btn {:on-click on-cancel} "Back to editing")))))

(defn- missing-joins
  "The join attributes a resolver's `:output` needs and this catalog lacks.
   The rule itself lives in `form-logic` (pinned by tests); this only supplies
   what the catalog already declares — since D2.29 that is the attribute list
   itself, filtered by `:target`, rather than a second registry to keep in
   step with it."
  [catalog output]
  (fl/missing-joins
   (into #{} (comp (filter :target) (map (comp text-kw :attribute)))
         (:attributes catalog))
   output))

(defn- declared-attributes
  "Every attribute id this catalog declares, join and scalar alike — the set a
   resolver's output is checked against."
  [catalog]
  (into #{} (map (comp text-kw :attribute)) (:attributes catalog)))

(defn- missing-attributes
  "The scalar attributes a resolver's `:output` names and this catalog lacks,
   as the EDN block the declare-attributes gate opens with. The rule lives in
   `form-logic` (pinned by tests); this only supplies the catalog.

   `confirmed` are the rows an earlier pass through the gate already answered
   for. They count as declared even though the catalog has not been re-read:
   without that, editing the output after a confirmation and pressing Save
   again would ask for a type the author has already given."
  [catalog confirmed entry]
  (fl/missing-attributes-template
   (into (declared-attributes catalog) (map :attribute) (or confirmed []))
   entry))

(defui missing-attributes-gate
  "**Declare the output's leaves before writing the resolver** (owner ruling
   2026-09-05), as one EDN block.

   Every leaf of an `:output` must be a registered attribute, and until this
   gate existed the editor found that out from the server as
   `:dialect.invariant/unregistered-reference` — a refusal that names the
   attribute and offers no way to make it exist, so the modeller left the
   editor, created attributes one by one, and came back.

   A block, not a table (same ruling: every pool object is authored as EDN —
   \"forms are unreliable\"). It opens pre-filled, one entry per missing leaf,
   with `:type` read off a **static** source's own `:data` and left `nil` where
   nothing can be inferred; sql and api are not probed, because nothing on this
   screen dials a source. The entries travel to the pool verbatim, so a
   `:label`, a `:format` or a whole join entry (`:target`, D2.29) can be typed
   in here and is written as typed. What is refused inline is an entry with
   neither `:type` (D2.14) nor `:target` — the one thing the pool is certain to
   refuse, said before anything is sent."
  [{:keys [text message saving? on-text on-confirm on-cancel]}]
  ($ :div.preflight
     ($ :p.hint
        "Some attributes this output names are not declared yet. Give each one a
         type and they will be created before the resolver; a join needs
         :target instead (D2.29). A type is pre-filled from a static source's
         own data — sql and api sources are not probed from here.")
     ($ editor/edn-editor {:text text :on-change on-text
                           :aria-label "attribute declarations" :line-numbers? true
                           :height "200px"})
     (when message ($ :p.hint.bad message))
     ($ :div.asset-editor-actions
        ($ :button.btn.primary {:disabled saving? :on-click on-confirm}
           (if saving? "Saving…" "Declare and continue"))
        ($ :button.btn {:on-click on-cancel} "Back to editing"))))


(def ^:private source-editor-key-rank
  "How a source entry reads in the EDIT view: what it is, how to reach it, who
   it is. The canonical form is dictionary order (D2.12) and stays so on every
   wire and on disk; this is only the order a person edits in, where
   `:password` sorting above `:username` puts the answer before the question.
   Parsing the text back does not care about order, so nothing downstream sees
   a second layout."
  {:type 0 :label 1 :description 2 :jdbc_url 3 :base_url 3 :username 4 :password 5})

(defn- for-editing
  "The entry as the editor shows it. Sources get the reading order above,
   ties and the rest in dictionary order; every other type is the canonical
   map as it came."
  [type entry]
  (if (and (= :source type) (map? entry))
    (into (sorted-map-by (fn [a b]
                           (compare [(get source-editor-key-rank a 99) (str a)]
                                    [(get source-editor-key-rank b 99) (str b)])))
          entry)
    entry))

(def ^:private curated-seed-note
  "The comment line a resolver seed carries above its `:curated? true`.

   A line of EDN comment rather than a hint beside the box: the box **is** the
   entry now (owner ruling 2026-09-05), so the explanation has to live where
   the key does or it is one more thing that scrolls away. The reader ignores
   it, and deleting the key deletes the note with it."
  ";; named query (no :input) — you sign for its convergence; remove when :input has an anchor")

;; Per type, what the dialect says it may carry — fetched once and kept for the
;; session. The key face is derived from the closed schemas server-side (owner
;; ruling 2026-09-07, doc 84 §8.4.25) and changes only with a new build, so
;; re-asking on every open would be a round trip per edit for a constant.
;; (`defonce` takes no docstring in ClojureScript.)
(defonce ^:private key-face-cache (atom {}))

(defn- seed-text
  "The text the box opens with for one entry: the entry as canonical EDN, with
   the type's note lines above it (and the D2.13 note when this resolver entry
   is an unanchored list).

   **Both paths, not just create.** The box *is* the entry now (owner ruling
   2026-09-05, EDN-only), so what a type may carry can only be said inside the
   text — and an entry that arrives from the MCP write face carries almost no
   keys (`add_thing` writes a namespace as literal `{}`, an attribute as
   `{:type …}` per D2.14). Editing one used to open on a near-empty box with
   nothing on screen naming `:kind`, `:label`, `:description`. The notes are
   EDN comments: `editor/parse-edn` drops them, so no byte of them, and no
   template default, reaches the pool — an absent key stays absent (D2.12
   canonical form)."
  [type entry key-face]
  (let [edn (editor/format-edn (pr-str (for-editing type entry)))
        notes (cond-> (fl/key-face-notes type key-face)
                (and (= :resolver type) (fl/curated-required? entry))
                (conj curated-seed-note))]
    (if (seq notes)
      (str (str/join "\n" notes) "\n" edn)
      edn)))

(defui source-connection-check
  "Check the current EDN without saving. Key by text to discard stale results."
  [{:keys [text id revision disabled?]}]
  (let [[busy? set-busy!] (uix/use-state false)
        [result set-result!] (uix/use-state nil)
        [error set-error!] (uix/use-state nil)]
    ($ :<>
       ($ :button.btn
          {:disabled (or disabled? busy?)
           :on-click
           (fn []
             (set-result! nil)
             (set-error! nil)
             (let [{:keys [value error]} (editor/parse-edn (or text ""))]
               (cond
                 error (set-error! {:message error})
                 (not (map? value)) (set-error! {:message "Enter a source EDN map."})
                 :else
                 (do
                   (set-busy! true)
                   (api/declare-source!
                    {:id (or id (:id value)) :entry (if id value (dissoc value :id))
                     :expected-revision revision :apply? false}
                    {:on-success (fn [r] (set-busy! false) (set-result! r))
                     :on-error (fn [e] (set-busy! false) (set-error! e))})))))}
          (if busy? "Validating…" "Validate connection"))
       ;; The result is its own block: this component sits inside a flex row of
       ;; buttons (`.asset-editor-actions`), and as a bare sibling it became a
       ;; tall box between Validate and Cancel. `.check-result` takes the full
       ;; last line of that row instead.
       (when error
         ($ :div.check-result ($ findings-view {:error error})))
       (when result
         ($ :div.check-result {:role "status" :class (if (:connected? result) "ok" "bad")}
            ($ :p.check-head
               (if (:connected? result)
                 "✓ Connection validated — nothing was saved."
                 "✕ Validation failed — nothing was saved."))
            (when (seq (:findings result))
              ($ findings-view {:error {:findings (:findings result)} :compact? true})))))))

(defui asset-editor
  "Author or edit one pool object as its canonical EDN entry.

   **EDN only** (owner ruling 2026-09-05: creating and editing any pool object
   in Studio is EDN-only — \"forms are unreliable\"). Create seeds the box from
   `:template`; edit fills it with the object's entry exactly as the pool
   returned it, pretty-printed for reading and canonical on the way back. Both
   paths open through `seed-text`, so the type's note lines are on screen while
   editing too — they are EDN comments, and nothing of them is saved. There
   is no second surface, so there is no question of which one is being saved.

   Editing fetches the object with its revision; the write carries that
   revision back as `:expected_revision`, so a concurrent edit is rejected by
   the pool rather than silently overwritten.  Creating asserts revision 0 —
   'this object should not exist yet' is the same kind of claim, said with the
   same key.  Every rejection — dialect validation, revision conflict,
   authorization — renders through `findings-view` below the text, and the
   pool guarantees a rejected write changed nothing.

   **Creating a resolver may create other objects first** (same ruling). The
   `:output` names attributes, and neither kind of them may be undeclared when
   the resolver is written: the leaves need `:type` (D2.14), the branch keys
   need `:target` (D2.29). So a create walks a chain — leaves, then joins, then
   the resolver — and the leaves are asked for in an EDN block before anything
   is sent, because the alternative is
   `:dialect.invariant/unregistered-reference`, a refusal that names an
   attribute and offers no way to make it exist. In the same spirit an
   unanchored `\"list\"` resolver is refused **here** without `:curated? true`
   (D2.13) rather than at the cut: the seed carries the key and a comment
   saying what it means, and deleting it is how an author declines to sign."
  [{:keys [type id mode template id-placeholder id-override schema catalog graph
           on-close on-saved]}]
  (let [create?                  (= mode :create)
        ;; **A new object should join the graph you made it in.** Authoring a
        ;; resolver while looking at one graph and having it land only in the
        ;; pool is a surprise every time: the object exists, the graph does not
        ;; have it, and nothing says so. Default on, and visible — a modeller
        ;; who genuinely wants a pool-only object can say so.
        [join-graph? set-join-graph!] (uix/use-state true)
        ;; Join attributes the entry's :output needs and the catalog lacks. A
        ;; resolver is refused without them, and until now the refusal was a
        ;; dead end: nothing in this Builder could declare one.
        [make-joins set-make-joins!] (uix/use-state true)
        ;; `entry` is the text, parsed — never a second editable copy. It is
        ;; what the side-effect notices below read; the text is the document.
        [entry set-entry!]       (uix/use-state (when create? template))
        ;; What this type may carry. nil until the fetch lands (or for good, if
        ;; the route is unreachable) — `key-face-notes` then shows the short
        ;; notes rather than nothing.
        [key-face set-key-face!] (uix/use-state (get @key-face-cache (name type)))
        [text set-text!]         (uix/use-state
                                  (when create? (seed-text type template key-face)))
        [new-id set-new-id!]     (uix/use-state "")
        [revision set-revision!] (uix/use-state nil)
        [error set-error!]       (uix/use-state nil)
        [saving? set-saving!]    (uix/use-state false)
        ;; A pending action held between "Save" and the author's decision on
        ;; what preflight found. nil means nothing is waiting.
        [pending set-pending!]   (uix/use-state nil)
        ;; The declare-attributes gate, held between "Save" and the author's
        ;; answer: `{:action … :text "…" :message …}`. nil means nothing is
        ;; waiting, and `attrs` below is what a confirmed gate left behind.
        [attr-gate set-attr-gate!] (uix/use-state nil)
        [attrs set-attrs!]       (uix/use-state nil)
        ;; The entry as fetched, never edited. `:update` is a **patch** and for
        ;; merge types an absent key means "keep" — so "this key was here and
        ;; the author removed it" can only be said by diffing against this.
        [original set-original!] (uix/use-state nil)
        ;; Maximized is a **view** state and deliberately session-only: the box
        ;; is the same document either way, so nothing about it is worth
        ;; persisting, and a box that reopens full-screen days later would be a
        ;; surprise nobody asked for. The text lives in `text` above, untouched
        ;; by the toggle, so maximizing can never lose an edit.
        [maximized? set-maximized!] (uix/use-state false)
        ;; Maximized must fill the **window**, and `position: fixed` cannot
        ;; promise that from where the box normally sits: the workspace drawer
        ;; and the dossier sheet have ancestors carrying transform/filter/
        ;; contain, and any of those makes `fixed` resolve against that
        ;; ancestor instead of the viewport — the overlay then grew inside the
        ;; sheet. So the block always renders through a portal into a container
        ;; this component owns, and the toggle moves that container between the
        ;; page and <body>. The container's identity never changes, so React
        ;; never unmounts the subtree: the CodeMirror instance, and with it the
        ;; cursor and the undo history, survives the move.
        portal-node (uix/use-ref nil)
        anchor      (uix/use-ref nil)
        _ (when (nil? @portal-node)
            (let [node (js/document.createElement "div")]
              (set! (.-className node) "asset-editor-portal")
              (reset! portal-node node)))]
    ;; Cmd/Ctrl+Shift+M maximizes, Escape restores. A mode entered from a small
    ;; button and only left by finding that same button again is a trap — the
    ;; query panes above take the same escape hatch.
    (uix/use-effect
     (fn []
       (let [on-key (fn [^js e]
                      (cond
                        (and (or (.-metaKey e) (.-ctrlKey e))
                             (.-shiftKey e)
                             (= "m" (str/lower-case (or (.-key e) ""))))
                        (do (.preventDefault e) (set-maximized! not))
                        (and maximized? (= "Escape" (.-key e)))
                        ;; Stop here: an Escape meant for the overlay must not
                        ;; also close the dossier underneath it.
                        (do (.preventDefault e) (.stopPropagation e) (set-maximized! false))))]
         (.addEventListener js/window "keydown" on-key)
         #(.removeEventListener js/window "keydown" on-key)))
     [maximized?])
    ;; Move the portal container, before paint so the overlay never shows in
    ;; the wrong place for a frame. Focus goes back to the entry afterwards:
    ;; the toggle is reached from the button or the shortcut, and either way
    ;; typing should carry on in the document, not on whatever the click left
    ;; focused.
    (uix/use-layout-effect
     (fn []
       (when-let [^js node @portal-node]
         (if maximized?
           (.appendChild js/document.body node)
           (when-let [^js a @anchor] (.appendChild a node)))
         (when-let [^js content (.querySelector node ".cm-content")]
           (.focus content)))
       js/undefined)
     [maximized?])
    ;; A box closed while maximized would otherwise leave its container on
    ;; <body> for good.
    (uix/use-effect
     (fn [] #(some-> ^js @portal-node .remove))
     [])
    ;; The key face, once per type per session. A failure is silent on purpose:
    ;; the box already holds the entry and the short notes, and a banner about
    ;; a note line would obscure the refusals that are about the author's work.
    (uix/use-effect
     (fn []
       (when-not (contains? @key-face-cache (name type))
         (api/key-face! type
                        {:on-success (fn [face]
                                       (swap! key-face-cache assoc (name type) face)
                                       (set-key-face! face))
                         :on-error (fn [_] nil)}))
       js/undefined)
     [type])
    ;; When it lands, re-seed — but only a box the author has not touched, or
    ;; the notes would eat an edit in progress.
    (uix/use-effect
     (fn []
       (when key-face
         (let [source (if create? template original)]
           (when (and source (= text (seed-text type source nil)))
             (set-text! (seed-text type source key-face)))))
       js/undefined)
     [key-face create? type template original text])
    (uix/use-effect
     (fn []
       (when-not create?
         (api/asset-object!
          type id
          {:on-success (fn [{:keys [object editable?]}]
                         (set-revision! (:pool/revision object))
                         (set-original! (:pool/entry object))
                         (set-entry! (:pool/entry object))
                         ;; Same seed text as create: one source for the notes.
                         (set-text! (seed-text type (:pool/entry object)
                                               (get @key-face-cache (name type))))
                         (when-not editable?
                           (set-error! {:code :workspace-editor-required
                                        :message "An owner or editor of this workspace is required to save changes."})))
           :on-error set-error!}))
       js/undefined)
     [create? type id])
    (let [;; Some types are not addressed by a name someone types: a link's
          ;; identity **is** its endpoint pair (D2.3), so the caller supplies
          ;; it and there is no id box to fill in.
          used-id (cond id-override id-override
                        create? (str/trim new-id)
                        :else id)
          ;; The text is the document; `entry` only follows it, so the side
          ;; effects below (missing joins, the D2.13 seed) can read the entry
          ;; without a second editable copy that could disagree with it.
          edit-text! (fn [t]
                       (set-text! t)
                       (let [{:keys [value]} (editor/parse-edn t)]
                         (when (map? value) (set-entry! value))))
          real-graph? (and graph (not (workspace-scope? graph)))
          ;; Put the new object — and anything created alongside it — into the
          ;; graph being worked in. Best effort AFTER the write: the object
          ;; exists either way, and failing to file it must not read as failing
          ;; to create it.
          file-in-graph! (fn [pairs done]
                           (if-not (and real-graph? join-graph? (seq pairs))
                             (done)
                             (let [left (atom (count pairs))]
                               (doseq [[k ids] pairs]
                                 (api/set-graph-members!
                                  {:graph graph :kind (name k) :ids ids :in? true}
                                  {:on-success (fn [_] (when (zero? (swap! left dec)) (done)))
                                   :on-error (fn [e]
                                               (js/console.error "could not add to graph:" (pr-str e))
                                               (when (zero? (swap! left dec)) (done)))})))))
          commit! (fn [action new-attrs]
                    (set-saving! true)
                    (set-error! nil)
                    (let [needed (when (and create? (= :resolver type) make-joins)
                                   (filterv :target (missing-joins catalog (:output (:entry action)))))
                          ;; **Declare the joins first.** A resolver whose join
                          ;; root is not a registered join attribute is
                          ;; refused, so the order is not a preference — it is
                          ;; the only order that works.
                          write-resolver!
                          (fn []
                            (api/write-asset!
                             action
                             ;; on-saved owns what follows a success — closing,
                             ;; reselecting — so a create can land on the new
                             ;; object without on-close wiping the selection.
                             {:on-success
                              (fn [result]
                                ;; Source editors stay mounted after Save. The
                                ;; next write must use the committed revision
                                ;; and diff removals against this saved entry.
                                (set-revision! (:revision result))
                                (set-original! (:value (editor/parse-edn text)))
                                (file-in-graph!
                                 (let [;; Joins and leaves file into ONE
                                       ;; `:attributes` pair: since D2.29 both
                                       ;; kinds are ordinary entries of the one
                                       ;; registry, and two pairs of the same
                                       ;; kind would be two calls racing over
                                       ;; the same group. A new leaf must join
                                       ;; the recipe too, or the cut refuses
                                       ;; the resolver with `:scope/mixed-output`.
                                       attr-ids (into (mapv #(pr-str (:join %)) needed)
                                                      (mapv #(pr-str (:attribute %)) new-attrs))]
                                   (cond-> [[(keyword (str (name type) "s")) [(pr-str (:id action))]]]
                                     (seq attr-ids) (conj [:attributes attr-ids])))
                                 (fn []
                                   (set-saving! false)
                                   (set-pending! nil)
                                   (on-saved (assoc result :id (:id action))))))
                              :on-error (fn [e]
                                          (set-saving! false)
                                          (set-pending! nil)
                                          (set-error! e))}))
                          write-joins!
                          (fn []
                            (if (empty? needed)
                              (write-resolver!)
                              (let [left (atom (count needed))
                                    failed (atom nil)]
                                (doseq [{:keys [join target]} needed]
                                  (api/write-asset!
                                   ;; A join attribute is an `:attributes` entry
                                   ;; with `:target` and no `:type` (D2.29); the
                                   ;; id is the qualified keyword the output
                                   ;; already names.
                                   {:action :create :type :attribute
                                    :id join
                                    :entry {:target target :cardinality :one-to-many}
                                    :expected_revision 0}
                                   {:on-success (fn [_] (when (zero? (swap! left dec))
                                                          (if @failed
                                                            (do (set-saving! false) (set-error! @failed))
                                                            (write-resolver!))))
                                    :on-error (fn [e]
                                                (reset! failed e)
                                                (when (zero? (swap! left dec))
                                                  (set-saving! false)
                                                  (set-error! e)))})))))
                          ;; **Leaves, then joins, then the resolver.** Each
                          ;; step is a reference the next one needs, so the
                          ;; order is not a preference. One at a time and
                          ;; stopping on the first failure: a half-declared
                          ;; output would leave the author guessing which of
                          ;; the writes went through.
                          write-attrs!
                          (fn write-attrs! [remaining]
                            (if (empty? remaining)
                              (write-joins!)
                              (let [row (first remaining)]
                                (api/write-asset!
                                 {:action :create :type :attribute
                                  :id (:attribute row)
                                  ;; Written as the author typed it: the gate
                                  ;; reads the block, it does not rewrite it.
                                  :entry (:entry row)
                                  :expected_revision 0}
                                 {:on-success (fn [_] (write-attrs! (rest remaining)))
                                  :on-error (fn [e]
                                              (set-saving! false)
                                              (set-pending! nil)
                                              (set-error! e))}))))]
                      (write-attrs! (vec new-attrs))))
          ;; **Preflight cannot see what does not exist yet.** It builds a
          ;; candidate pool from THIS action alone, so a resolver whose leaves
          ;; or join roots are still to be declared is refused there for
          ;; `:dialect.invariant/unregistered-reference` — a refusal about the
          ;; objects the very next step creates, rendered as "the pool refuses
          ;; this entry". So the advice is asked for only when nothing is
          ;; being created alongside; otherwise the chain in `commit!` runs and
          ;; the pool's own gate on the resolver write is the authority.
          proceed! (fn [action new-attrs]
                     (set-saving! true)
                     (set-error! nil)
                     (let [pre-writes? (or (seq new-attrs)
                                           (and create? (= :resolver type) make-joins
                                                (seq (filterv :target
                                                              (missing-joins catalog (:output (:entry action)))))))]
                       ;; Preflight runs at the submit boundary — not on every
                       ;; keystroke, which would flash red at a form that is
                       ;; merely unfinished, and not as a separate button
                       ;; nobody would remember to press.
                       ;;
                       ;; It needs a graph: a cut is per-recipe, so in the
                       ;; workspace scope there is nothing to project and the
                       ;; save simply goes through unchecked. Saying that
                       ;; plainly beats inventing an answer.
                       (if (or pre-writes? (str/blank? (str graph)) (= "*" graph))
                         (commit! action new-attrs)
                         (api/preflight!
                          {:graph graph :action action}
                          {:on-success (fn [report]
                                         (set-saving! false)
                                         (cond
                                           ;; **池自己就会拒**：preflight 在这种
                                           ;; 情形下回的是 `:findings`（没有候选
                                           ;; 可比，见 version/preflight），而不是
                                           ;; `:introduced-findings`。原来只看后者,
                                           ;; 于是一个必然失败的写入被渲染成
                                           ;; 「可以保存，只是切不出版本」，作者
                                           ;; 点下 Save anyway，然后撞上一堵墙。
                                           (seq (:findings report))
                                           (set-error! {:code :write-refused
                                                        :message "The pool refuses this entry; it cannot be saved as written."
                                                        :findings (:findings report)})

                                           (seq (:introduced-findings report))
                                           (set-pending! {:action action :report report})

                                           :else (commit! action new-attrs)))
                           ;; Preflight is advice. If it cannot answer, that
                           ;; must not stop the author from saving.
                           :on-error (fn [_] (commit! action new-attrs))}))))
          save! (fn []
                  (let [{:keys [value error]} (editor/parse-edn (or text ""))
                        ;; The id box holds text; the pool addresses each type
                        ;; by its own id shape (`:id-kind` in the schema). Read
                        ;; it here, once, for every door this form has.
                        typed (when (and create? (not id-override))
                                (fl/typed-id (get-in schema [:types type :id-kind]) used-id))]
                    (cond
                      (and create? (not id-override) (str/blank? used-id))
                      (set-error! {:code :id-required
                                   :message "A new object needs an id before it can be created."})

                      (:error typed)
                      (set-error! {:code :id-shape :message (:error typed)})

                      error
                      (set-error! {:code :edn-unreadable :message error})

                      (not (map? value))
                      (set-error! {:code :edn-shape
                                   :message "An entry is an EDN map of dialect keys."})

                      :else
                      (let [;; Deletion completion (merge patches swallow an
                            ;; absent key) — the rule and its rationale live in
                            ;; form-logic/patch-entry, pinned by tests.
                            id* (if typed (:id typed) used-id)
                            entry* (fl/patch-entry
                                    {:create? create?
                                     :update-kind (get-in schema [:types type :update])
                                     :original original
                                     :value (fl/complete-link-entry type id* value)})
                            action {:action (if create? :create :update)
                                    :type type :id id*
                                    :entry entry*
                                    :expected_revision (if create? 0 revision)}
                            ;; Asked before anything is sent, because it is a
                            ;; local question: the leaves are declared or they
                            ;; are not, and a round trip would only answer it
                            ;; as a refusal with nothing to act on.
                            gap (when (and create? (= :resolver type))
                                  (not-empty (missing-attributes catalog attrs entry*)))]
                        (cond
                          ;; **D2.13, said here rather than at the cut.** An
                          ;; unanchored `"list"` without `:curated? true` is
                          ;; refused by `cut` with
                          ;; `:cut.resolver/unsigned-named-query` — a refusal
                          ;; the author meets long after writing the resolver,
                          ;; about a key nothing had mentioned. The seed
                          ;; carries the key; deleting it is a choice, and this
                          ;; says what that choice costs while it can still be
                          ;; taken back.
                          (and (= :resolver type)
                               (fl/curated-required? entry*)
                               (not (contains? entry* :curated?)))
                          (set-error!
                           {:code :resolver-unsigned
                            :message (str "A \"list\" resolver with no anchor in :input is a named "
                                          "query: add :curated? true to sign for its convergence, "
                                          "or anchor :input (D2.13).")})

                          gap (set-attr-gate! {:action action
                                               :text (editor/format-edn (pr-str gap))})
                          :else (proceed! action attrs))))))]
      ($ :<>
       ;; Where the block sits when it is not maximized. `display: contents`,
       ;; so it adds no box of its own and the page lays out as before.
       ($ :div.asset-editor-anchor {:ref anchor})
       (uix.dom/create-portal
        ($ :<>
         (when maximized?
           ($ :div.asset-editor-backdrop
              {:aria-hidden true
               ;; Same exit as Escape and Restore, no third meaning.
               :on-click #(set-maximized! false)}))
         ($ :div {:class (str "asset-editor" (when maximized? " maximized"))}
         (when (and create? (not id-override))
           ($ :input.asset-id
              {:value new-id
               :placeholder (or id-placeholder "id")
               :spell-check false
               :aria-label "New object id"
               :on-change #(set-new-id! (.. % -target -value))}))
         (cond
           attr-gate
           ($ missing-attributes-gate
              {:text (:text attr-gate)
               :message (:message attr-gate)
               :saving? saving?
               :on-text #(set-attr-gate! (assoc attr-gate :text % :message nil))
               :on-confirm
               (fn []
                 (let [{:keys [value error]} (editor/parse-edn (or (:text attr-gate) ""))
                       parsed (when-not error (fl/parse-attribute-declarations value))]
                   (cond
                     error (set-attr-gate! (assoc attr-gate :message error))
                     (:error parsed) (set-attr-gate! (assoc attr-gate :message (:error parsed)))
                     :else (let [rows (into (vec attrs) (:rows parsed))]
                             (set-attrs! rows)
                             (set-attr-gate! nil)
                             (proceed! (:action attr-gate) rows)))))
               :on-cancel #(set-attr-gate! nil)})

           pending
           ($ preflight-warning
              {:report (:report pending) :saving? saving?
               :on-save #(commit! (:action pending) attrs)
               :on-cancel #(set-pending! nil)})

           (nil? text) (when-not error ($ :p.hint "Loading entry…"))

           :else
           ;; The same CodeMirror the query panes use: the box IS the entry
           ;; (owner ruling 2026-09-05, EDN-only), so it gets EDN highlighting,
           ;; bracket matching and a line gutter rather than a plain textarea
           ;; that renders the note lines and the entry as one grey block.
           ($ editor/edn-editor
              {:text text
               :on-change edit-text!
               :aria-label "entry EDN"
               :line-numbers? true
               :class "asset-entry"
               ;; Grow with the document up to 70vh, then scroll — what `rows`
               ;; plus the CSS cap did. Maximized the overlay sizes it instead.
               :height (if maximized? "100%" "auto")
               :max-height (when-not maximized? "70vh")
               ;; A toggle changes the geometry above; `refresh` makes the
               ;; editor re-measure after it rather than keep the old layout.
               :refresh maximized?
               ;; No theme prop reaches this component (twenty call sites), and
               ;; the palette key is authoritative during render by design
               ;; (theme/set-current!): reading it here re-runs the highlight
               ;; reconfigure on the re-render a theme switch already causes.
               :theme @theme/current
               ;; Completion over the model's own attributes — a resolver's
               ;; :output is written here now, and nobody types
               ;; `:customer/region-code` correctly from memory.
               :attributes (mapv (fn [a] {:attribute (:attribute a)
                                          :detail (when-let [t (:target a)] (str "→ " t " · join"))})
                                 (:attributes catalog))}))
         (when error ($ findings-view {:error error}))
         ;; **Say what will happen, before it happens.** Both of these act on
         ;; the modeller's behalf, and doing that silently is the same failure
         ;; as not doing it: either way the result is not what they expected.
         (when (and create? (not pending) (not attr-gate))
           (let [needed (when (= :resolver type)
                          (missing-joins catalog (:output (or entry {}))))]
             ($ :div.create-side-effects
                (when (seq needed)
                  ($ :<>
                     ($ :label.check
                        ($ :input {:type "checkbox" :checked make-joins
                                   :aria-label "declare the missing joins"
                                   :on-change #(set-make-joins! (.. % -target -checked))})
                        (str "declare " (count needed)
                             (if (= 1 (count needed)) " join" " joins")
                             " this output needs"))
                     ($ :ul.needed-navs
                        (for [{:keys [join target]} needed]
                          ($ :li {:key (str join)}
                             ($ :code.mono (str join))
                             ($ :span.via (if target (str " → " target)
                                              ;; Nothing to infer from: the
                                              ;; members span more than one
                                              ;; entity, or none. Asked for
                                              ;; rather than guessed.
                                              " → declare it yourself first")))))))
                (when real-graph?
                  ($ :label.check
                     ($ :input {:type "checkbox" :checked join-graph?
                                :aria-label "add to the current graph"
                                :on-change #(set-join-graph! (.. % -target -checked))})
                     (str "add to " graph))))))
         (when-not (or pending attr-gate)
          ($ :div.asset-editor-actions
            ($ :button.btn.primary
               {:disabled (or saving? (nil? text)
                              (and (not create?) (nil? revision)))
                :on-click save!}
               (if saving? "Saving…" (if create? "Create" "Save")))
            (when (= :source type)
              ($ source-connection-check {:key (str text revision new-id)
                                          :text text :id (or id-override (if create? new-id id))
                                          :revision revision :disabled? saving?}))
            ($ :button.btn {:on-click on-close} "Cancel")
            ($ :button.btn
               {:on-click #(set-maximized! not)
                :title (if maximized?
                         "Restore the editor (Esc)"
                         "Fill the window (Cmd/Ctrl+Shift+M)")
                :aria-label (if maximized? "Restore editor size" "Maximize editor")}
               (if maximized? "Restore" "Maximize"))
            (if create?
              ($ :span.via (if id-override (pr-str id-override) "new object"))
              (when revision ($ :span.via (str "revision " revision))))))))
        @portal-node)))))

(defui resolver-create-panel
  "Author a new resolver into the pool through the same guarded write path the
   in-place editor uses (`:action :create`, revision 0)."
  [{:keys [catalog schema graph from to on-select on-model-change on-close]}]
  (let [sources (->> (:resolvers catalog) (keep :source-ref) distinct vec)
        source-ref (first sources)
        ;; The seeded `:source` block belongs to the connector the chosen
        ;; source-ref declares, so the type is read off the source register
        ;; rather than assumed: `sources` above is a list of ids in use, and an
        ;; id says nothing about which contract governs the block below it.
        source-type (:type (first (filter #(= source-ref (:id %)) (:sources catalog))))]
    ($ :div.dossier
       ($ :h2 (if (and from to) "New resolver — from a drawn hop" "New resolver"))
       (when (and from to)
         ($ :p.sub
            ($ :span.mono from) ($ :span.via " → ") ($ :span.mono to)))
       ($ :p.hint "The entry is dialect-v1 resolver shape; the pool validates it structurally on create.")
       (when (seq sources)
         ($ :p.synonyms
            ($ :span.k "sources in use ")
            (for [[i s] (map-indexed vector sources)]
              ($ :span.tag {:key i} s))))
       ($ asset-editor {:type :resolver :mode :create
                        :template (fl/resolver-template source-ref source-type from to)
                        :id-placeholder "resolver id, e.g. demo_customer_orders"
                        :schema schema :catalog catalog :graph graph
                        :on-close on-close
                        :on-saved (fn [{:keys [id]}]
                                    (on-model-change)
                                    (on-select {:kind :resolver :id id}))}))))

(defui derived-create-panel
  "A rule, not a query: a value computed from attributes the entity already
   has, with no database behind it.

   EDN-only like every other create door (owner ruling 2026-09-05): the seed
   already carries the three positions derived-v1 will not forgive — no
   `:source-ref`, `:result_type \"single\"`, and an `:expr` — so what is left
   to type is the inputs, the one output and the expression. Inputs bind inside
   the expression by their LOCAL name (`:cis.master.customer/entry_id` is
   `entry_id`); that is said in the seed's comment, where the key is."
  [{:keys [catalog schema graph on-select on-model-change on-close]}]
  ($ :div.dossier
     ($ :h2 "New derived resolver")
     ($ :p.hint
        (str "A derived resolver computes one attribute from attributes the entity already "
             "carries — no source, no query. Each :input binds inside :expr by its LOCAL name "
             "(:cis.master.customer/entry_id is entry_id); :output names the one attribute the "
             "rule produces. The expression runs in a closed allowlist sandbox with a hard time "
             "budget (derived-v1) and is checked when the graph is compiled, not on save."))
     ($ asset-editor {:type :resolver :mode :create
                      :template (fl/derived-template [] nil)
                      :id-placeholder "resolver id, e.g. derived_customer_audit_info"
                      :schema schema :catalog catalog :graph graph
                      :on-close on-close
                      :on-saved (fn [{:keys [id]}]
                                  (on-model-change)
                                  (on-select {:kind :resolver :id id}))})))

(defui facts
  "The two facts an output row is judged on, set inline and dimmed: the type,
   访问档位随 owner 裁定 2026-08-27 整套退役，所以这里只剩类型。"
  [{:keys [attr catalog]}]
  (let [d (get (:attribute-index catalog) attr)]
    ($ :span.facts
       ;; A join attribute has no `:type` (D2.29) — where it goes is the fact
       ;; that stands in its place, and a row with neither reads as an
       ;; attribute nobody finished.
       (if-let [t (:target d)]
         ($ :span.fact-type (str "→ " t))
         (when-let [ty (:type d)] ($ :span.fact-type (name ty)))))))

(defui collection-members
  "What a collection contains, **derived from the resolvers that produce it**.

   Membership is not declared on the attribute — `:target` only says the
   attribute is a join root and where it goes, and the member list lives in each producer's
   `:output`. So this is read-only here: changing it means editing that
   resolver, and offering a second place to edit it would be a second way to
   say the same thing.

   Producers are listed separately. If they agree the list is shown once; if
   they disagree that is a **shape conflict**, shown as one rather than
   silently unioned into a member list no resolver actually declares."
  [{:keys [attribute catalog on-select]}]
  (let [shapes (filterv #(= attribute (:collection %)) (:collection-shapes catalog))
        variants (distinct (map :members shapes))
        consistent? (= 1 (count variants))
        ;; The same drawing the resolver dossier uses for its output: the
        ;; collection sits above its members, members indented under it. A
        ;; collection is one thing; it should look like one thing on every
        ;; page it appears, not a tree on the resolver and a flat list here.
        ;; The root repeats the page's own name on purpose — the picture is
        ;; the unit of recognition, and half of it is not it.
        tree (fn [members]
               ($ :ul.tree-list
                  ($ :li.tree-item
                     ($ :div.tree-head
                        ($ :span.mono attribute)
                        ($ facts {:attr attribute :catalog catalog}))
                     ($ :ul.tree-members
                        (for [[j m] (map-indexed vector members)]
                          ($ :li {:key j}
                             ($ :button.link
                                {:on-click #(on-select {:kind :attribute :id m})} m)
                             ($ facts {:attr m :catalog catalog})))))))]
    ($ :div.ledger.tree-ledger
       ($ :h3 "Members"
          (when consistent?
            ($ :span.count (str "  " (count (first variants))))))
       (cond
         (empty? shapes)
         ($ :p.none "no resolver declares this collection's members yet")

         consistent?
         ($ :<>
            (tree (first variants))
            ($ :p.hint (str "declared by " (str/join ", " (map :producer shapes)))))

         :else
         ($ :<>
            ($ :p.hint.bad
               "Shape conflict: producers of this collection declare different members.")
            (for [{:keys [producer members]} shapes]
              ($ :div.shape-variant {:key producer}
                 ($ :p.hint producer)
                 (tree members))))))))

(defui removal-list [{:keys [title items tone]}]
  (when (seq items)
    ($ :<>
       ($ :p {:class (if (= :bad tone) "hint bad" "hint")} title)
       ($ :ul.removal-list
          (for [[t id] (take 40 items)]
            ($ :li {:key (pr-str [t id])}
               ($ :span.tag (name t))
               ($ :span.mono (str id))))
          (when (> (count items) 40)
            ($ :li ($ :span.hint (str "… and " (- (count items) 40) " more"))))))))

(defui remove-object
  "Remove this object, after saying what that reaches.

   Removing reaches past the thing named in two directions, and they are not
   symmetric: an attribute anything still names is **refused** with the list,
   because rewriting someone's resolver behind their back is not part of
   deleting an attribute; a resolver takes with it the attributes nothing else
   produces, because those are drift that stops the graph being cut. Neither is
   a thing to discover afterwards, so the plan is fetched and shown first —
   \"are you sure?\" is not a question anyone can answer."
  [{:keys [request label on-removed]}]
  (let [[plan set-plan!]   (uix/use-state nil)
        [busy? set-busy!]  (uix/use-state false)
        [error set-error!] (uix/use-state nil)
        blocked? (seq (:blocked-by plan))
        going    (or (:objects plan)
                     (concat [(:target plan)] (:cascade plan)))]
    ($ :div.remove-object
       (cond
         (nil? plan)
         ($ :button.btn.danger
            {:disabled busy?
             :on-click (fn []
                         (set-busy! true) (set-error! nil)
                         (api/removal-plan!
                          request
                          {:on-success (fn [p] (set-busy! false) (set-plan! p))
                           :on-error (fn [e] (set-busy! false) (set-error! e))}))}
            (if busy? "checking…" (or label "remove")))

         blocked?
         ($ :<>
            ($ removal-list {:tone :bad
                             :title "Still named by these — nothing was removed:"
                             :items (:blocked-by plan)})
            ($ :p.hint "Remove or repoint them first; this will not edit them for you.")
            ($ :div.asset-editor-actions
               ($ :button.btn {:on-click #(set-plan! nil)} "close")))

         ;; The pool does not hold these. Said here rather than at the
         ;; execute step: a plan with an unknown selection has no actions, and
         ;; an empty "Removing:" list with a live button is not a question
         ;; anyone can answer.
         (seq (:unknown plan))
         ($ :<>
            ($ removal-list {:tone :bad
                             :title "The pool holds no such object — nothing was removed:"
                             :items (:unknown plan)})
            ($ :div.asset-editor-actions
               ($ :button.btn {:on-click #(set-plan! nil)} "close")))

         :else
         ($ :<>
            ($ removal-list {:title "Removing:" :items going :tone :bad})
            ($ removal-list {:title (str "Produced by nothing else, so they go too:")
                             :items (:cascade plan)})
            ($ removal-list {:tone :bad
                             :title "Not in this namespace, but reaching into it:"
                             :items (:reaching-in plan)})
            ($ removal-list {:title "Left behind — still referenced, so not taken:"
                             :items (:orphaned plan)})
            ($ :div.asset-editor-actions
               ($ :button.btn.danger
                  {:disabled busy?
                   :on-click (fn []
                               (set-busy! true) (set-error! nil)
                               (api/remove-objects!
                                request
                                ;; **`:removed? false` is a refusal, not a
                                ;; success.** The route answers 200 with the
                                ;; findings that say why nothing went (an
                                ;; identity the pool does not hold, most of
                                ;; them); treating that as done closed the
                                ;; dialog and left the object in the list.
                                {:on-success (fn [r] (set-busy! false)
                                               (if (false? (:removed? r))
                                                 (set-error! r)
                                                 (do (set-plan! nil)
                                                     (when on-removed (on-removed)))))
                                 :on-error (fn [e] (set-busy! false) (set-error! e))}))}
                  (if busy? "removing…" "remove them"))
               ($ :button.btn {:on-click #(set-plan! nil)} "cancel"))))
       (when error ($ findings-view {:error error})))))

(defn- n-of [n one many] (str n " " (if (= 1 n) one many)))

(defn source-removal-verdict
  "The one sentence on top of the removal panel (owner 2026-10-04): what stops
   the removal, or what goes with it. Pure, so the wording is tested."
  [{:keys [source removable? used-by also-removed findings]}]
  (let [{:keys [objects drafts serving]} used-by
        feeders (count (filter #(#{"resolver" "mutation"} (:type %)) also-removed))]
    (if removable?
      (if (pos? feeders)
        (str "Remove " source " and the " (n-of feeders "resolver" "resolvers")
             " only it feeds? No Graph uses them.")
        (str "Remove " source "? Nothing uses it."))
      (let [why (cond-> []
                  (seq drafts)  (conj (str (n-of (count drafts) "Graph draft" "Graph drafts")
                                           (if (= 1 (count drafts)) " still binds it" " still bind it")))
                  (seq serving) (conj (str (n-of (count serving) "serving version" "serving versions")
                                           (if (= 1 (count serving)) " runs on it" " run on it")))
                  (seq objects) (conj (str (n-of (count objects) "object" "objects")
                                           " a Graph still needs "
                                           (if (= 1 (count objects)) "reads" "read") " it")))
            why (if (seq why) why [(or (:message (first findings)) "it is still in use")])]
        (str "Can't remove " source ": " (str/join "; " why) ".")))))

(defui removal-group
  "One titled list in the removal panel: five rows, then «and N more»."
  [{:keys [title items tone]}]
  (let [[open? set-open!] (uix/use-state false)
        shown (if open? items (take 5 items))]
    (when (seq items)
      ($ :section {:class (str "removal-group" (when (= :bad tone) " bad"))}
         ($ :h4 title ($ :span.count (count items)))
         ($ :ul.removal-list
            (for [{:keys [key tag text note on-click]} shown]
              ($ :li {:key key}
                 (when tag ($ :span.tag tag))
                 (if on-click
                   ($ :button.linkish.mono {:type "button" :on-click on-click} text)
                   ($ :span.mono text))
                 (when note ($ :span.dim note))))
            (when (and (not open?) (> (count items) 5))
              ($ :li ($ :button.linkish {:type "button" :on-click #(set-open! true)}
                        (str "and " (- (count items) 5) " more")))))))))

(defn- object-item [{:keys [type id reason graphs]}]
  {:key (str type id) :tag (if (= "mutation" type) "operation" type) :text id
   :note (when reason (str reason (when (seq graphs) (str " · " (str/join ", " graphs)))))})

(defn- graph-title [{:keys [graph state]}]
  (str graph (when (= "archived" state) " (archived)")))

(defn source-removal-graph-lines
  "One line per blocking graph on top of the panel (owner 2026-10-04): an
   archived graph still names objects, which reads as a ghost unless said."
  [{:keys [blocking-graphs]}]
  (mapv (fn [{:keys [graph state workspace can-open? can-retire?] :as g}]
          (cond
            ;; Owner 2026-10-04: demo/cis lived in a workspace nobody could
            ;; enter; say so instead of pointing at a graph that cannot open.
            (and (contains? g :can-open?) (not can-open?))
            (str graph " is in workspace " workspace " — you are not a member. "
                 (if can-retire?
                   "As a Studio administrator you can delete that graph here."
                   "Ask its workspace owner or a Studio administrator to remove it."))
            (= "archived" state)
            (str "Delete or restore archived graph " graph " first.")
            :else
            (str "Remove these objects from graph " graph " first.")))
        blocking-graphs))

(defn source-removal-blocker-groups
  "Blocking objects grouped by the graph that names them — one group per graph,
   biggest first — then the ones no graph accounts for. Pure, so it is tested."
  [{:keys [blocking-graphs used-by]}]
  (let [objects (:objects used-by)]
    (conj (mapv (fn [{:keys [graph] :as g}]
                  {:graph g
                   :title (str (graph-title g) " — " (n-of (:objects g) "object" "objects"))
                   :objects (filterv #(some #{graph} (:graphs %)) objects)})
                blocking-graphs)
          {:title "Still needed" :objects (filterv #(empty? (:graphs %)) objects)})))

(defui source-removal-panel
  "Remove a declared Source (owner 2026-10-04). Shown in place of the source
   editor: a verdict sentence, then who blocks and what goes with it. The
   resolvers / operations only this Source feeds and no Graph names go in the
   same reviewed plan; anything a Graph still needs blocks."
  [{:keys [source on-close on-removed on-open-graph]}]
  (let [[plan set-plan!]   (uix/use-state nil)
        [busy? set-busy!]  (uix/use-state true)
        [error set-error!] (uix/use-state nil)
        [tick set-tick!]   (uix/use-state 0)
        reload! #(set-tick! inc)]
    (uix/use-effect
     (fn []
       (api/source-removal-plan!
        source {:on-success (fn [p] (set-busy! false) (set-plan! p))
                :on-error (fn [e] (set-busy! false) (set-error! e))})
       js/undefined)
     [source tick])
    (let [{:keys [drafts serving]} (:used-by plan)
          also (:also-removed plan)
          of (fn [types] (mapv object-item (filter #(types (:type %)) also)))]
      ($ :div.source-removal
         ($ :div.pane-head
            ($ :span (str "Remove " source))
            ($ :span.spacer))
         ($ :div.source-removal-body
            (cond
              (and (nil? plan) (nil? error)) ($ :p.hint "checking who uses this Source…")
              plan
              ($ :<>
                 ($ :p {:class (str "removal-verdict" (when-not (:removable? plan) " bad"))}
                    (source-removal-verdict plan))
                 ($ removal-group
                    {:tone :bad :title "Graph drafts that bind it"
                     :items (mapv (fn [g] {:key g :tag "draft" :text g
                                           :on-click (when on-open-graph #(on-open-graph g))})
                                  drafts)})
                 ($ removal-group
                    {:tone :bad :title "Serving versions"
                     :items (mapv (fn [{:keys [graph version]}]
                                    {:key (str graph version) :tag "serving"
                                     :text (str graph " v" version)})
                                  serving)})
                 (for [line (source-removal-graph-lines plan)]
                   ($ :p.hint {:key line} line))
                 (for [{:keys [graph title objects]} (source-removal-blocker-groups plan)]
                   ($ :<> {:key title}
                      (when (and graph on-open-graph (:can-open? graph true))
                        ($ :button.linkish.mono
                           {:type "button" :on-click #(on-open-graph (:graph graph))}
                           (str "open " (graph-title graph))))
                      (when (and graph (:can-retire? graph) (not (:can-open? graph true)))
                        ($ :button.btn.danger
                           {:type "button" :disabled busy?
                            :on-click
                            (fn []
                              (let [qn (:graph graph)]
                                (when (js/confirm (str "Archive and permanently delete graph " qn
                                                       " in workspace " (:workspace graph) "?"))
                                  (set-busy! true) (set-error! nil)
                                  (api/admin-retire-graph!
                                   qn {:on-success (fn [_] (set-busy! false) (reload!))
                                       :on-error (fn [e] (set-busy! false) (set-error! e))}))))}
                           (str "delete graph " (:graph graph))))
                      ($ removal-group {:tone :bad :title title
                                        :items (mapv #(cond-> (object-item %) graph (dissoc :note))
                                                     objects)})))
                 (when (:removable? plan)
                   ($ :<>
                      ($ removal-group {:title "Also removed: resolvers / operations"
                                        :items (of #{"resolver" "mutation"})})
                      ($ removal-group {:title "Also removed: attributes only they produce"
                                        :items (of #{"attribute"})})
                      ($ removal-group {:title "Also removed: links"
                                        :items (of #{"link"})})
                      ($ removal-group
                         {:title "Also removed: release bindings (those versions can no longer be activated)"
                          :items (mapv (fn [{:keys [graph version]}]
                                         (let [t (str graph (when version (str " v" version)))]
                                           {:key t :tag "binding" :text t}))
                                       (:bindings plan))})
                      ($ removal-group
                         {:title "Also removed: stored values only this Source referenced"
                          :items (mapv (fn [v] {:key v :tag "variable" :text v})
                                       (:credentials plan))})))))
            (when error ($ findings-view {:error error}))
            ($ :div.asset-editor-actions
               (when (:removable? plan)
                 ($ :button.btn.danger
                    {:disabled busy?
                     :on-click (fn []
                                 (set-busy! true) (set-error! nil)
                                 (api/remove-source!
                                  source
                                  {:on-success (fn [_] (set-busy! false)
                                                 (when on-removed (on-removed)))
                                   :on-error (fn [e] (set-busy! false) (set-error! e))}))}
                    (if busy? "removing…" "Remove")))
               ($ :button.btn {:on-click on-close}
                  (if (:removable? plan) "Cancel" "Close"))))))))

(def ^:private disposal-kinds
  "The pool's object types, in the order this screen offers them: what a
   modeller reaches for first, and what the closure drags along anyway, last.

   **Sources included.** They were left out on the theory that offering one
   invites removing what every resolver still needs — but the pool already
   refuses exactly that (`:pool.reference/inbound`, naming the resolvers and
   mutations whose `:source-ref` points at it), and a guard that already holds
   does not want a second, weaker copy in the UI. The cost of the omission was
   real: a connection nothing references any more could never be cleared."
  [["namespace" "entities"] ["attribute" "attributes"] ["resolver" "resolvers"]
   ["link" "links"] ["mutation" "mutations"] ["metric" "metrics"]
   ["source" "sources"] ["term" "terms"] ["unit" "units"]])

(defui asset-detail
  "One pool object's definition, beside the list it was picked from — and, for
   an editor, the place it is changed.

   Read straight from `/api/studio/assets/object` rather than from the row: the
   list carries a label and an id, and «what is this thing actually» is the
   question a person opens a row to ask (8.4.15). Editing is the same
   `asset-editor` every dossier opens — the object's own entry as EDN (owner,
   2026-09-05: the Assets tab is where the workspace's objects are MANAGED, not
   a removal queue with a preview)."
  [{:keys [type id schema can-edit? on-close on-changed on-removed]}]
  (let [[object set-object!] (uix/use-state nil)
        [error set-error!]   (uix/use-state nil)
        [editing? set-editing!] (uix/use-state false)
        ;; The same key face the editor seeds from, so the read view shows the
        ;; type's note lines too — read and edit are then the same document,
        ;; byte for byte. Cached per type for the session; a failure is silent,
        ;; and the entry is simply shown without notes.
        [key-face set-key-face!] (uix/use-state (get @key-face-cache (name type)))
        ;; The list prints every id (a keyword, a string and a link's `[from to]`
        ;; pair share one field), and `asset-object!` prints it again on the way
        ;; out — so the printed form is read back here rather than sent twice.
        real-id (reader/read-string id)
        read! (uix/use-callback
               (fn []
                 (api/asset-object! type (reader/read-string id)
                                    {:on-success (fn [d] (set-error! nil) (set-object! d))
                                     :on-error   (fn [e] (set-object! nil) (set-error! e))}))
               [type id])]
    (uix/use-effect
     (fn [] (set-object! nil) (set-error! nil) (set-editing! false) (read!) js/undefined)
     [read!])
    (uix/use-effect
     (fn []
       (if (contains? @key-face-cache (name type))
         (set-key-face! (get @key-face-cache (name type)))
         (api/key-face! (keyword type)
                        {:on-success (fn [face]
                                       (swap! key-face-cache assoc (name type) face)
                                       (set-key-face! face))
                         :on-error (fn [_] nil)}))
       js/undefined)
     [type])
    ($ :aside.ws-drawer {:aria-label (str "definition of " id)}
       ($ :div.ws-drawer-head
          ($ :span.tag (name type))
          ($ :span.mono.ws-drawer-id (str id))
          ($ :span.spacer)
          (when (and can-edit? (not editing?) object)
            ($ :button.btn {:type "button" :on-click #(set-editing! true)} "edit"))
          ($ :button.btn {:type "button" :on-click on-close} "close"))
       ($ :div.ws-drawer-body
          (cond
            error       ($ findings-view {:error error})
            (nil? object) ($ :p.hint "Reading the object…")
            editing?    ($ asset-editor {:type (keyword type) :id real-id
                                         :schema schema
                                         :on-close #(set-editing! false)
                                         :on-saved (fn [_]
                                                     (set-editing! false)
                                                     (read!)
                                                     (when on-changed (on-changed)))})
            :else
            ($ :<>
               ;; The entry, and only the entry — the same text «edit» opens
               ;; on, so reading and editing show one document. The envelope
               ;; (`:pool/type`, revision, workspace, `:editable?`) is the
               ;; header's business, not the entry's.
               ;; The read-only CodeMirror, not a `<pre>`: same highlighting,
               ;; gutter and text as «edit» opens on, so the two views of one
               ;; entry cannot drift apart. Selection and copy still work; the
               ;; box takes no caret and offers no completion.
               ($ editor/edn-editor
                  {:key (str "read-" type "/" id)
                   :text (seed-text (keyword type)
                                    (get-in object [:object :pool/entry])
                                    key-face)
                   :read-only? true
                   :line-numbers? true
                   :class "asset-entry"
                   :aria-label (str "entry EDN of " id)
                   :height "auto"
                   :max-height "70vh"
                   :theme @theme/current})
               ;; **One object at a time, from where it is being read** (owner,
               ;; 2026-09-05). The plan is fetched and shown first: an object
               ;; anything still names is refused with the list, and a resolver
               ;; takes the attributes nothing else produces — neither is a
               ;; thing to discover afterwards.
               (when can-edit?
                 ($ :div.ws-drawer-remove
                    ($ :p.hint "Removing takes it out of the pool for every graph that names it. To leave one graph only, untick it in that graph's recipe.")
                    ($ remove-object
                       {:key (str "rm-" type "/" id)
                        ;; `id` is the printed form the list carries, so it
                        ;; is sent as-is: read here and read again by the route
                        ;; is how a source id became the symbol `demo-db`, an
                        ;; identity the pool does not hold, and the removal
                        ;; reported itself done having written nothing.
                        :request (fl/removal-request
                                  [(fl/printed-object-ref type id)])
                        :label "remove from the pool…"
                        :on-removed (fn [] (when on-removed (on-removed)))})))))))))

(defn- attribute-namespace
  "The entity an attribute row belongs to: the namespace of its printed id.
   Ids arrive printed (`\":cis.users/name\"`), so this is string work on
   purpose — reading ten thousand of them back as keywords to ask one question
   is the wrong trade."
  [id]
  (let [s (if (str/starts-with? id ":") (subs id 1) id)
        i (str/last-index-of s "/")]
    (when i (subs s 0 i))))

(defn- index-objects
  "The pool index, prepared ONCE for searching: a lowercase search key per row,
   and the entity an attribute belongs to. Everything a keystroke needs is
   read from here — nothing is lowercased or grouped per keystroke."
  [objects]
  (mapv (fn [r]
          (cond-> (assoc r :key (str/lower-case (str (:label r) " " (:id r))))
            (= "attribute" (:type r)) (assoc :ns (attribute-namespace (:id r)))))
        objects))

(defn- asset-singular
  "How a pool type is named on a button: `entity` for a namespace (that is the
   word the rest of the app uses), the type's own name otherwise."
  [kind]
  (if (= "namespace" kind) "entity" kind))

(def ^:private asset-template
  "The create box's seed per type — `fl/asset-template`, one definition."
  fl/asset-template)

(defn- asset-id-placeholder [kind]
  (case kind
    "namespace" "entity id, e.g. cis.sales.mso"
    "attribute" "attribute id, e.g. cis.sales.mso/total_order"
    "resolver"  "resolver id, e.g. cis_sales_mso_by_no"
    "link"      "link id, e.g. [:cis.a/x :cis.b/y]"
    "source"    "source id, e.g. sales-db"
    "term"      "term id, e.g. cis.money/unit_price"
    "unit"      "unit id, e.g. USD"
    "metric"    "metric id, e.g. sales_by_customer_month"
    "mutation"  "mutation id, e.g. approve_order"
    "id"))

(defui workspace-maintenance
  "Every object in the POOL, by type — read one, or clear several out (8.4.15).

   **No graph here, and none acquired.** This screen reads the pool directly:
   its whole reason to exist is the objects no recipe selects, which reading
   through a graph would hide. It also means opening it cannot disturb whichever
   graph the Graph Editor has open.

   **Built for ten thousand objects** (owner, 2026-09-05). Attributes are the
   bulk of any pool, so three things bound what is on screen at once:

   - one type at a time, on the rail — there is no «everything» view, because
     that is the one view that necessarily lays the whole pool out;
   - attributes are read **by entity**: a column of namespaces, each with its
     count, and the list beside it holds one entity's attributes — which is
     how a modeller thinks of them and keeps a render to hundreds, not
     thousands;
   - the rows are windowed (`vlist`), so a list of any length costs a
     screenful of DOM.

   The index is fetched once and searched in memory: ten thousand rows filter
   in milliseconds, and it is the DOM, never the data, that was ever slow. One
   search box serves every type — while a query is active the rail carries each
   type's MATCH count, and the entity column each entity's, so «where did that
   name land» is answered before a tab is opened.

   **Creating is pool-only and lives here too** (owner, 2026-09-05): «+ <type>»
   on the tools row opens the same create form the Graph Editor uses, with the
   workspace-scope catalog behind its pickers and no graph to join — for the
   resolver that belongs to no graph yet.

   **Removal lives in the drawer, one object at a time** (owner, 2026-09-05).
   This page is where the workspace's objects are read and changed; taking one
   out is done from where it is being read, behind the pool's own plan
   (`remove-object`). There is no batch mode and no tick on a row — a list
   that grows ticks reads as a deletion queue, which is not what this is.
   Unticking in the Graph Editor edits one graph's list and leaves the object
   where it lives (D3.19); removal here is for every graph at once, and the
   drawer says so. There is deliberately no «nothing names it» filter: that
   judgement is OQ-ORPHAN-SWEEP and is not ruled."
  [{:keys [workspace can-edit? schema on-model-change]}]
  (let [[data set-data!]     (uix/use-state nil)
        [error set-error!]   (uix/use-state nil)
        ;; What this browser had open here last time, for THIS workspace:
        ;; a reload comes back to the same type, entity and object rather
        ;; than to the rail's first entry (owner, 2026-09-05).
        was (ui-recall-in [:assets workspace])
        ;; The rail's position. Entities first: it is the shortest list and
        ;; the one the attributes view is organised by.
        [kind set-kind!]     (uix/use-state (or (:kind was) "namespace"))
        ;; The entity whose attributes are listed, when `kind` is attribute.
        [entity set-entity!] (uix/use-state (:entity was))
        ;; What is typed, and what is searched: the second follows the first
        ;; after a short pause, so a fast typist pays for one filter, not one
        ;; per keystroke.
        [q set-q!]           (uix/use-state "")
        [needle set-needle!] (uix/use-state "")
        [detail set-detail!] (uix/use-state (:detail was))
        ;; **Creating here is creating in the POOL** (owner, 2026-09-05). An
        ;; object need not belong to any graph — the pool is its home (D3.19)
        ;; and a resolver declared before any graph names it is a legitimate
        ;; thing. The form still gets its pickers: the catalog is read at
        ;; WORKSPACE scope (`*`), which is every object this workspace may see
        ;; and no graph at all, so the «add to graph» tick never appears.
        [creating? set-creating!] (uix/use-state false)
        [wcat set-wcat!]     (uix/use-state nil)
        _ (uix/use-effect
           (fn []
             (ui-remember! :assets (assoc (or (ui-recall-in [:assets]) {})
                                          workspace {:kind kind :entity entity :detail detail}))
             js/undefined)
           [workspace kind entity detail])
        load! (uix/use-callback
               (fn [] (api/workspace-objects!
                       {:on-success (fn [d] (set-error! nil) (set-data! d))
                        :on-error   set-error!}))
               [])
        _ (uix/use-effect (fn [] (load!) js/undefined) [load!])
        ;; The workspace-scope catalog, for the create form's pickers only —
        ;; read when a form is first opened, not on every visit, and re-read
        ;; on the next open after a write.
        _ (uix/use-effect
           (fn []
             (when (and creating? (nil? wcat))
               (api/index! "" workspace-scope
                           {:on-success set-wcat!
                            :on-error #(js/console.error (pr-str %))}))
             js/undefined)
           [creating? wcat])
        _ (uix/use-effect
           (fn []
             (let [t (js/setTimeout #(set-needle! (str/lower-case (str/trim q))) 120)]
               #(js/clearTimeout t)))
           [q])
        rows    (uix/use-memo #(index-objects (:objects data)) [data])
        ;; The query's answer across EVERY type, computed once per needle: the
        ;; rail counts, the entity counts and the list all read this.
        matched (uix/use-memo
                 (fn [] (if (str/blank? needle)
                          rows
                          (filterv #(str/includes? (:key %) needle) rows)))
                 [rows needle])
        counts  (uix/use-memo #(frequencies (map :type matched)) [matched])
        ;; Attributes by entity, from the matched set — so with a query on,
        ;; the column shows only the entities that answered, with how many.
        by-ns   (uix/use-memo
                 (fn [] (->> matched
                             (filter #(= "attribute" (:type %)))
                             (group-by :ns)
                             (map (fn [[ns* rs]] [ns* (count rs)]))
                             (sort-by first)
                             vec))
                 [matched])
        ;; A stale entity — its attributes gone, or none matching — falls
        ;; back to the first one that has any, so the list is never blank for
        ;; a reason the column does not show.
        entity* (if (some #(= entity (first %)) by-ns) entity (ffirst by-ns))
        shown   (uix/use-memo
                 (fn [] (let [of-kind (filterv #(= kind (:type %)) matched)]
                          (if (= "attribute" kind)
                            (filterv #(= entity* (:ns %)) of-kind)
                            of-kind)))
                 [matched kind entity*])
        kind-label (some (fn [[k l]] (when (= k kind) l)) disposal-kinds)
        row-key (fn [r] (str (:type r) "/" (:id r)))]
    ($ :div.pane.pane-assets
       ($ :div.pane-head
          ($ :span "Objects in the pool")
          ($ :span.spacer)
          ($ :span.count (str (count rows) " objects"
                              (when-let [w (:workspace data)] (str " · " w)))))
       (cond
         error ($ :div.pane-body.pad ($ findings-view {:error error}))

         (nil? data) ($ :div.pane-body.pad ($ :p.hint "Reading the pool…"))

         :else
         ($ :<>
            ($ :div.ws-split
               ($ :nav.ws-kinds {:role "tablist" :aria-label "object types"}
                  (for [[k label] disposal-kinds
                        :let [n (get counts k 0)]]
                    ($ :button {:key k :type "button" :role "tab"
                                :aria-selected (= kind k)
                                :class (when (= kind k) "on")
                                ;; A type with nothing in it — or nothing
                                ;; matching — stays on the rail, greyed:
                                ;; «no term matches» is an answer, and a rail
                                ;; that changes length is one you re-read.
                                :disabled (zero? n)
                                ;; A tab is a change of subject: whatever was
                                ;; open on the right — a definition, a half-
                                ;; filled create form — belongs to the type
                                ;; being left, so it closes and the new type
                                ;; is simply listed (owner, 2026-09-05).
                                :on-click (fn []
                                            (set-detail! nil)
                                            (set-creating! false)
                                            (set-kind! k))}
                       ($ :span label)
                       ($ :span.ws-kind-count n))))
               ($ :div.ws-kind-pane
                  ($ :div.disposal-tools
                     ($ :input.disposal-filter
                        {:value q :spell-check false
                         :placeholder "search every type…"
                         :aria-label "search every object in the pool"
                         :on-change #(set-q! (.. % -target -value))})
                     (when (and can-edit? (not creating?))
                       ($ :button.btn {:type "button"
                                       :title (str "Declare a new " (asset-singular kind) " in the pool")
                                       :on-click (fn [] (set-detail! nil) (set-creating! true))}
                          (str "+ " (asset-singular kind)))))
                  ($ :div.ws-results-split
                     ;; The entity column: only for attributes, and read from
                     ;; the attributes themselves rather than from the
                     ;; namespace objects, so an attribute whose namespace was
                     ;; never registered is still reachable here.
                     (when (= "attribute" kind)
                       ($ :nav.ws-entities {:aria-label "entities"}
                          (if (empty? by-ns)
                            ($ :p.hint.pad "No attribute matches.")
                            (for [[ns* n] by-ns]
                              ($ :button {:key ns* :type "button"
                                          ;; The full name, for the one that
                                          ;; still does not fit.
                                          :title ns*
                                          :class (when (= ns* entity*) "on")
                                          :aria-pressed (= ns* entity*)
                                          :on-click (fn []
                                                      (set-detail! nil)
                                                      (set-creating! false)
                                                      (set-entity! ns*))}
                                 ($ :span.mono ns*)
                                 ($ :span.ws-kind-count n))))))
                     ($ vlist {:items shown
                               :key-fn row-key
                               :selected (when detail (str (:type detail) "/" (:id detail)))
                               ;; The row opens the definition (8.4.15) — and
                               ;; closes a create form, for the same reason a
                               ;; tab does: picking something is leaving the
                               ;; thing you were writing.
                               :on-select (fn [r]
                                            (set-creating! false)
                                            (set-detail! (select-keys r [:type :id])))
                               :empty-note (if (str/blank? needle)
                                             (str "No " kind-label " in the pool.")
                                             (str "No " kind-label " matches that search."))
                               :render-row
                               (fn [r]
                                 ($ :<>
                                    ;; The entity column already names the
                                    ;; namespace; repeating it on every row
                                    ;; would leave the part that differs
                                    ;; hidden behind an ellipsis.
                                    ($ :span.name
                                       (if (and (:ns r) (= "attribute" kind))
                                         (subs (str (:label r)) (+ 2 (count (:ns r))))
                                         (str (:label r))))
                                    (when-let [n (:note r)]
                                      (when-not (str/blank? n) ($ :span.note n)))))})
                     (when creating?
                       ($ :aside.ws-drawer {:aria-label (str "new " (asset-singular kind))}
                          ($ :div.ws-drawer-head
                             ($ :span.tag kind)
                             ($ :span.mono.ws-drawer-id (str "new " (asset-singular kind)))
                             ($ :span.spacer)
                             ($ :button.btn {:type "button" :on-click #(set-creating! false)} "close"))
                          ($ :div.ws-drawer-body
                             (if (nil? wcat)
                               ($ :p.hint "Reading the workspace catalog…")
                               ($ asset-editor {:key (str "new-" kind)
                                                :type (keyword kind) :mode :create
                                                :template (asset-template kind)
                                                :id-placeholder (asset-id-placeholder kind)
                                                :schema schema :catalog wcat
                                                ;; Workspace scope, so this is a
                                                ;; pool write and nothing else.
                                                :graph workspace-scope
                                                :on-close #(set-creating! false)
                                                :on-saved (fn [{:keys [id]}]
                                                            (set-creating! false)
                                                            (set-wcat! nil)
                                                            (load!) (on-model-change)
                                                            (set-detail! {:type kind :id (pr-str id)}))})))))
                     (when (and detail (not creating?))
                       ($ asset-detail {:key (str (:type detail) "/" (:id detail))
                                        :type (:type detail) :id (:id detail)
                                        :schema schema :can-edit? can-edit?
                                        :on-changed (fn [] (load!) (on-model-change))
                                        :on-removed (fn [] (set-detail! nil) (load!) (on-model-change))
                                        :on-close #(set-detail! nil)}))))))))))

(defui attribute-dossier [{:keys [attr catalog index on-select on-plan can-edit?
                                  on-model-change schema graph]}]
  (let [id         (:attribute attr)
        producers  (:producers attr)
        consumers  (:consumers attr)
        ;; The unfiltered index: `:resolvers` is narrowed by the search, so
        ;; naming this attribute's producers from it goes blank exactly when a
        ;; filter is active.
        by-id      (:resolver-index catalog)
        ;; Every hop out of this attribute: the same fact as "who takes it as
        ;; input", answered in attributes instead of resolvers.
        hops       (filterv #(= id (:from %)) (:edges index))
        namespaces (map :entity (:attributes catalog))
        [editing? set-editing!] (uix/use-state false)]
    ($ :div.dossier
       ($ :h2 id)
       ($ :p.sub
          ($ :span.tag.hue {:style {:color (theme/namespace-color namespaces (:entity attr))}}
             (:entity attr))
          ;; What the chart says this attribute *is*, before what the model does
          ;; with it. A modeller arrives knowing the business name, not the id.
          (when (:identity? attr) ($ :span.tag "identity"))
          ;; `:format` is one of the keys a bound term carries (D2.26), so an
          ;; attribute that binds one has none of its own.
          (when-let [f (or (:format attr)
                           (:format (get (:term-index catalog) (:term attr))))]
            ($ :span.tag (name f)))
          (when (:deprecated? attr) ($ :span.tag.warn "deprecated"))
          ;; `:listed?` defaults to true and only `false` is written (D2.28),
          ;; so its presence here always means "deliberately not listed".
          (when (false? (:listed? attr)) ($ :span.tag.warn "not listed"))
          ;; A join attribute says where it goes instead of what type it is
          ;; (D2.29). A badge, not a second kind of edge on the blueprint:
          ;; containment would need its own edge semantics, and that is not
          ;; worth a second legend yet.
          (when-let [target (:target attr)]
            ($ :<>
               ($ :span.tag.collection-tag (str "→ " target))
               (when-let [c (:cardinality attr)] ($ :span.tag (name c)))
               (when-let [shape (first (filter #(= id (:collection %))
                                               (:collection-shapes catalog)))]
                 (let [n (count (:members shape))]
                   ($ :span.tag (str n " member" (when (not= 1 n) "s")))))))
          (when-let [t (:type attr)] ($ :span.tag (name t)))
          (when-let [u (:unit attr)] ($ :span.tag u))
          (when (empty? producers) ($ :span.tag.warn "entry point"))
          ($ :button.btn.primary {:on-click #(on-plan id)} "query this")
          (when (and can-edit? (not editing?))
            ($ :button.btn {:on-click #(set-editing! true)} "edit")))
       (when editing?
         ($ :<>
            ($ asset-editor {:type :attribute :id (keyword id)
                             :schema schema :catalog catalog :graph graph
                             :on-close #(set-editing! false)
                             :on-saved (fn [_] (set-editing! false) (on-model-change))})
            ($ remove-object {:request (fl/object-ref :attribute (keyword id))
                              :label "remove this attribute"
                              :on-removed (fn [] (set-editing! false)
                                            (on-select nil) (on-model-change))})))
       ;; Where the meaning lives depends on whether a term is bound: a bound
       ;; attribute may not carry label, description or synonyms itself
       ;; (D2.26 refuses both), so reading them off the attribute would show
       ;; nothing at all — which is exactly how this dossier used to look for
       ;; every term-bound attribute.
       ;; Resolved against the unfiltered index, not the rail's list: the
       ;; search narrows what is listed, not what a reference means.
       (let [bound (when (:term attr) (get (:term-index catalog) (:term attr)))
             meaning (or bound attr)]
         ($ :<>
            (when (:term attr)
              ($ :p.sub
                 ($ :span.k "term ")
                 ($ :button.link {:on-click #(on-select {:kind :term :id (:term attr)})}
                    (:term attr))
                 (when-let [c (:term-context attr)] ($ :span.tag c))))
            (when-let [label (:label meaning)] ($ :p.lead label))
            (when-let [d (:description meaning)] ($ :p.hint d))
            (when-let [synonyms (seq (:synonyms meaning))]
              ($ :p.synonyms
                 ($ :span.k "also called ")
                 (for [[i s] (map-indexed vector synonyms)]
                   ($ :span.tag {:key i} s))))
            (when (and (:term attr) (nil? bound))
              ($ :p.hint.bad
                 "This attribute binds a term that is not in this graph, so its meaning cannot be shown here."))))
       (when-let [r (:replaced-by attr)]
         ($ :p.hint
            ($ :span.k "replaced by ")
            ($ :button.link {:on-click #(on-select {:kind :attribute :id (kw-text r)})}
               (kw-text r))))
       (when (:target attr)
         ($ :<>
            ;; The one thing a join is FOR, said in the syntax it is written
            ;; in: it had its own dossier saying this until D2.29 folded the
            ;; object type away, and losing the sentence with the page would
            ;; have been a loss of information, not of duplication.
            ($ :p.lead "Queried as " ($ :code.mono (str "{:" id " [...]}")))
            ($ :div.ledgers
               ($ ledger {:title "Reaches" :lead? true :items [(:target attr)]
                          :render (fn [_ t] ($ :button.link
                                               {:on-click #(on-select {:kind :entity :id t})} t))})
               ($ collection-members {:attribute id :catalog catalog :on-select on-select}))))
       (when-let [values (seq (:values (or (when (:term attr)
                                            (get (:term-index catalog) (:term attr)))
                                          attr)))]
         ($ :div.ledgers
            ($ ledger {:title "Values" :lead? true
                       :items (mapv (fn [[code d]] [code d]) (sort-by (comp str key) values))
                       :render (fn [_ [code d]]
                                 ($ :<>
                                    ($ :span.mono (str code))
                                    ($ :span.via (or (:label d) (:description d)))))})))
       ($ :div.ledgers
          ;; Tables rather than ledgers: choosing between producers is a
          ;; comparison, and a comparison made one click at a time is not one.
          ($ graph/relations-table
             {:title "Produced by" :rows producers :resolver-index by-id
              :empty-note "the caller supplies this"
              :on-select (fn [rid r] (on-select {:kind :resolver :id rid :data r}))})
          ($ graph/relations-table
             {:title "Input to" :rows consumers :resolver-index by-id
              :empty-note "no resolver takes this as input"
              :on-select (fn [rid r] (on-select {:kind :resolver :id rid :data r}))})
          ($ ledger {:title "Leads to" :items hops
                     :empty-note "nothing is reachable from this"
                     :render (fn [_ hop]
                               ($ :<>
                                  ($ :button.link
                                     {:on-click #(on-select {:kind :attribute :id (:to hop)})}
                                     (:to hop))
                                  ($ :span.via (str/join ", " (:resolvers hop)))))})))))

(defui resolver-dossier [{:keys [resolver on-select on-plan can-edit?
                                 on-model-change schema catalog graph]}]
  (let [{:keys [id description connection source-type batch? input output output-tree
                join eql-params non-executable]} resolver
        [editing? set-editing!] (uix/use-state false)]
    ($ :div.dossier
       ($ :h2 id)
       ($ :p.sub
          ($ :span.tag source-type)
          ($ :span.tag connection)
          (when batch? ($ :span.tag "batched"))
          (when non-executable ($ :span.tag.warn "not runnable here"))
          (when (and (not non-executable) (or join (seq output)))
            ($ :button.btn.primary
               {:on-click #(on-plan (if join (:key join) (first output)))}
               "query this"))
          (when (and can-edit? (not editing?))
            ($ :button.btn {:on-click #(set-editing! true)} "edit")))
       (when editing?
         ($ :<>
            ($ asset-editor {:type :resolver :id id
                             :schema schema :catalog catalog :graph graph
                             :on-close #(set-editing! false)
                             :on-saved (fn [_] (set-editing! false) (on-model-change))})
            ($ remove-object {:request {:type "resolver" :id (pr-str id)}
                              :label "remove this resolver"
                              :on-removed (fn [] (set-editing! false)
                                            (on-select nil) (on-model-change))})))
       ;; Production runs this resolver and this process does not. Saying so
       ;; where the resolver is read is the whole point of loading it (ADR-030).
       (when non-executable ($ :p.hint.bad (:message non-executable)))
       (when description ($ :p.hint description))
       ($ :div.ledgers
          ($ graph/attributes-table
             {:title "Input" :attrs input
              :attribute-index (:attribute-index catalog)
              :empty-note "global — needs no input"
              :on-select (fn [a] (on-select {:kind :attribute :id a}))})
          ($ ledger {:title "EQL params" :items eql-params
                     :empty-note "none declared"
                     ;; Param names arrive as keywords. React cannot render a
                     ;; keyword object — handing it one blanks the whole
                     ;; workbench, not just this ledger — so name it here,
                     ;; where the shape is known.
                     :render (fn [label _] ($ :span.mono (name label)))})
          ;; The output as written, same shape the editor shows: a collection
          ;; carries its members, it does not sit beside them. The old view
          ;; flattened children up into the list and then repeated the join in
          ;; a separate block, so the same three attributes appeared twice and
          ;; neither place said which was the root.
          ($ :div.ledger.tree-ledger
             ($ :h3 "Output"
                (when (seq output) ($ :span.count (str "  " (count output)))))
             (if (empty? output-tree)
               ($ :p.none "nothing declared")
               ($ :ul.tree-list
                  (for [[i n] (map-indexed vector output-tree)]
                    ;; Collection over its members, the shape the editor uses:
                    ;; side by side, a qualified member name has nowhere to go
                    ;; but off the edge of the pane.
                    ($ :li.tree-item {:key i}
                       ($ :div.tree-head
                          ($ :button.link
                             {:on-click #(on-select {:kind :attribute :id (:attribute n)})}
                             (:attribute n))
                          ;; Type inline rather than one click away:
                          ;; reviewing what a resolver hands back should not
                          ;; take a request per row. The tree shape is kept —
                          ;; nesting a table inside it only repeated a header
                          ;; per collection.
                          ($ facts {:attr (:attribute n) :catalog catalog}))
                       ;; No collection badge: the indented members below are
                       ;; the statement, and a tag restating the picture is
                       ;; noise (owner ruling 2026-08-26).
                       (when (:members n)
                         ($ :ul.tree-members
                            (for [[j m] (map-indexed vector (:members n))]
                              ($ :li {:key j}
                                 ($ :button.link
                                    {:on-click #(on-select {:kind :attribute :id m})}
                                    m)
                                 ($ facts {:attr m :catalog catalog}))))))))))))))

(defui entity-dossier
  "A namespace, and everything registered under it.

   It was the one object type with a dossier and no way to edit it, so `:kind`
   (D2.7), the label and the synonyms could be read here and only written
   through another tool. The editor is the same one every other dossier opens.

   The paths out get no control here on purpose: since D2.29 a path is an
   ordinary attribute of this namespace, with the attribute dossier and editor
   every other attribute has — a second place to write one would be a second
   semantics."
  [{:keys [entity catalog on-select can-edit? on-model-change schema graph]}]
  (let [[editing? set-editing!] (uix/use-state false)
        name'      (:entity entity)
        attrs      (filterv #(= name' (:entity %)) (:attributes catalog))
        ;; D2.29: the paths out of this entity are its own join attributes —
        ;; the ones carrying `:target` — not a separate registry.
        joins      (filterv :target attrs)
        namespaces (map :entity (:attributes catalog))]
    ($ :div.dossier
       ($ :h2 name')
       ($ :p.sub
          ($ :span.tag.hue {:style {:color (theme/namespace-color namespaces name')}}
             (str (:attribute-count entity) " attributes"))
          (when (and can-edit? (not editing?))
            ($ :button.btn {:on-click #(set-editing! true)} "edit")))
       (when editing?
         ($ :<>
            ($ asset-editor {:type :namespace :id name'
                             :schema schema :catalog catalog :graph graph
                             :on-close #(set-editing! false)
                             :on-saved (fn [_] (set-editing! false) (on-model-change))})
            ;; **The pool's bulk delete, which had no way in.** The server has
            ;; planned a whole namespace since `pool/namespace-removal` existed
            ;; — ordered so the pool's inbound-reference refusal cannot fire,
            ;; and naming what reaches in from outside — but nothing called it:
            ;; the only remove controls were one attribute, one resolver, one
            ;; link. Taking an entity apart meant clicking it apart.
            ;;
            ;; It matches on segment boundaries, so naming a parent takes
            ;; everything under it (`cis.master` takes `cis.master.customer`)
            ;; while `cis.dv` leaves `cis.dvd` alone.
            ;;
            ;; This deletes from the POOL. Unticking an entity in the Graph
            ;; Editor edits one recipe and leaves the objects where they live;
            ;; this is the other thing, and there is no undo but a backup.
            ($ remove-object {:request {:namespace name'}
                              :label "remove this entity and everything under it"
                              :on-removed (fn [] (set-editing! false)
                                            (on-select nil) (on-model-change))})))
       (when-let [label (:label entity)] ($ :p.lead label))
       ($ :div.ledgers
          ($ ledger {:title "Identity keys" :lead? true :items (:identity-keys entity)
                     ;; Empty means the chart declares no `:identity?` here, not
                     ;; that Studio failed to work one out. It is a gap to fill.
                     :empty-note "no attribute here is marked :identity?"
                     :render (fn [label a] ($ :button.link {:on-click #(on-select {:kind :attribute :id a})} label))})
          ;; **The paths out were invisible.** They are the keys a query writes
          ;; as `{:ns/name [...]}`, and nothing in this Builder showed them — a
          ;; modeller could not see what paths an entity had, let alone that
          ;; one was missing. Kept as its own ledger after D2.29 even though
          ;; joins now also appear under "Attributes": what an entity reaches
          ;; is a different question from what it holds, and answering it by
          ;; scanning a list of two hundred attributes is not answering it.
          ($ ledger {:title "Paths out" :items joins
                     :empty-note "no join leaves this entity — a list resolver needs one to hang its rows from"
                     :render (fn [_ j]
                               ($ :button.link
                                  {:on-click #(on-select {:kind :attribute
                                                          :id (:attribute j)
                                                          :data j})}
                                  (str (:attribute j) " \u2192 " (:target j))))})
          ($ ledger {:title "Attributes" :items (mapv :attribute attrs)
                     :render (fn [label a] ($ :button.link {:on-click #(on-select {:kind :attribute :id a})} label))})))))

(defui combination-dossier
  "An input combination has no entry in the catalog — it is not an attribute but
   a set of them, and what it is worth saying about it is what it opens."
  [{:keys [id index on-select]}]
  (let [members (graph/combination-members id)
        hops    (filterv #(= id (:from %)) (:edges index))]
    ($ :div.dossier
       ($ :h2 "Input combination")
       ($ :p.sub "Every one of these has to be known before the resolvers below can run.")
       ($ ledger {:title "Held together" :items members
                  :render (fn [label a]
                            ($ :button.link {:on-click #(on-select {:kind :attribute :id a})} label))})
       ($ ledger {:title "Opens" :items (mapv :to hops)
                  :render (fn [label a]
                            ($ :button.link {:on-click #(on-select {:kind :attribute :id a})} label))}))))

(defui term-dossier
  "A glossary entry, and every attribute currently bound to it.

   The bindings are derived from the attributes, not stored on the term: a
   term does not know who uses it, and keeping a second list here would be one
   more thing to drift."
  [{:keys [term catalog on-select can-edit? on-model-change schema graph]}]
  (let [[editing? set-editing!] (uix/use-state false)
        id    (:id term)
        ;; Derived server-side and unfiltered: asking the search-narrowed
        ;; attribute list who binds this term answers "nobody" whenever a
        ;; filter is active, which is most of the time.
        bound (get (:term-bindings catalog) id [])]
    ($ :div.dossier
       ($ :h2 id)
       ($ :p.sub
          ($ :span.tag "term")
          (when-let [f (:format term)] ($ :span.tag (name f)))
          (when (:deprecated? term) ($ :span.tag.warn "deprecated"))
          (when (and can-edit? (not editing?))
            ($ :button.btn {:on-click #(set-editing! true)} "edit")))
       (when editing?
         ($ asset-editor {:type :term :id id
                          :schema schema :catalog catalog :graph graph
                          :on-close #(set-editing! false)
                          :on-saved (fn [_] (set-editing! false) (on-model-change))}))
       (when-let [label (:label term)] ($ :p.lead label))
       (when-let [d (:description term)] ($ :p.hint d))
       (when-let [synonyms (seq (:synonyms term))]
         ($ :p.synonyms
            ($ :span.k "also called ")
            (for [[i s] (map-indexed vector synonyms)]
              ($ :span.tag {:key i} s))))
       ($ :div.ledgers
          ($ ledger {:title "Bound attributes" :lead? true
                     :items bound
                     :empty-note "no attribute binds this term yet"
                     :render (fn [label a]
                               ($ :button.link
                                  {:on-click #(on-select {:kind :attribute :id a})} label))})))))

(def ^:private registry-faces
  "What the three registries say about themselves on a dossier and a create
   panel. One component serves all three (below): a metric, a mutation and a
   unit are each an id plus an entry the form already knows how to draw, and
   three copies of «heading, tags, edit, label, description» would drift."
  {:metric   {:tag "metric" :new "New metric"
              :placeholder "metric id, e.g. sales_by_customer_month"
              :hint (str "A metric is a measure over one entity: an expression, the entity it "
                         "is taken on, the dimensions it can be cut by and the grain it is "
                         "reported at. It is computed at query time, never stored.")
              :tags (fn [m] [(some-> (:entity m) str) (some-> (:grain m) name)])}
   :mutation {:tag "mutation" :new "New mutation"
              :placeholder "mutation id, e.g. approve_order"
              :hint (str "A mutation writes to a source system: its parameters, the statement "
                         "or call it runs through a source, and what it returns. Publishing "
                         "one is what makes it callable; declaring it here only names it.")
              :tags (fn [m] [(:result_type m) (:source-ref m)])}
   :unit     {:tag "unit" :new "New unit"
              :placeholder "unit id, e.g. USD or days"
              :hint (str "A unit is a name an attribute or a term can carry — a currency, a "
                         "duration, a count. It has a label and a description and nothing "
                         "else; what it means is what attributes bound to it mean.")
              :tags (fn [_] [])}})

(defui registry-dossier
  "A metric, a mutation or a unit: heading, its tags, edit in place, and the
   words it carries. Which of the three is `type`."
  [{:keys [type item can-edit? on-model-change schema catalog graph]}]
  (let [[editing? set-editing!] (uix/use-state false)
        face (get registry-faces type)
        id   (:id item)]
    ($ :div.dossier
       ($ :h2 id)
       ($ :p.sub
          ($ :span.tag (:tag face))
          (for [[i t] (map-indexed vector (remove str/blank? ((:tags face) item)))]
            ($ :span.tag {:key i} t))
          (when (and can-edit? (not editing?))
            ($ :button.btn {:on-click #(set-editing! true)} "edit")))
       (when editing?
         ($ asset-editor {:type type :id id
                          :schema schema :catalog catalog :graph graph
                          :on-close #(set-editing! false)
                          :on-saved (fn [_] (set-editing! false) (on-model-change))}))
       (when-let [label (:label item)] ($ :p.lead label))
       (when-let [d (:description item)] ($ :p.hint d))
       (when-let [synonyms (seq (:synonyms item))]
         ($ :p.synonyms
            ($ :span.k "also called ")
            (for [[i s] (map-indexed vector synonyms)]
              ($ :span.tag {:key i} s)))))))

(defui registry-create-panel
  "Author a metric, a mutation or a unit. The same `asset-editor` in create
   mode every other panel uses; the id is a plain string for all three."
  [{:keys [type catalog schema graph on-select on-model-change on-close]}]
  (let [face (get registry-faces type)]
    ($ :div.dossier
       ($ :h2 (:new face))
       ($ :p.hint (:hint face))
       ($ asset-editor {:type type :mode :create
                        :template {:label "" :description ""}
                        :id-placeholder (:placeholder face)
                        :schema schema :catalog catalog :graph graph
                        :on-close on-close
                        :on-saved (fn [{:keys [id]}]
                                    (on-model-change)
                                    (on-select {:kind type :id id}))}))))

(defui term-create-panel
  "Author a glossary entry. The id is a qualified name — `cis.money/unit_price`
   — because a term is a registry entry, and D2.26 makes attributes reference
   it by that id."
  [{:keys [catalog schema graph on-select on-model-change on-close]}]
  ($ :div.dossier
     ($ :h2 "New term")
     ($ :p.hint "A term carries the meaning an attribute then borrows: label, description, format, synonyms and values. Attributes that bind it keep only type, identity and unit.")
     ($ asset-editor {:type :term :mode :create
                      :template {:label "" :description ""}
                      :id-placeholder "term id, e.g. cis.money/unit_price"
                      :schema schema :catalog catalog :graph graph
                      :on-close on-close
                      :on-saved (fn [{:keys [id]}]
                                  (on-model-change)
                                  (on-select {:kind :term :id id}))})))

(defui source-dossier
  "A connector this graph's resolvers read through.

   A source is in a graph because a resolver there references it
   (scope-projection-v1 §5) — it does not arrive because a namespace prefix
   reached it. So the resolvers using it are the interesting fact, and they
   are listed rather than counted.

   Connector key **values** are not shown here. dialect-v1 says credential
   keys live among them, and the rule that they may only hold `${VAR}` is
   currently a docstring rather than an enforced invariant — so one of these
   may hold a literal secret. Which keys are set is safe to say; what they are
   set to belongs in the editor, where someone is deliberately looking."
  [{:keys [source catalog on-select can-edit? on-model-change schema graph]}]
  (let [[editing? set-editing!] (uix/use-state false)
        by-id (:resolver-index catalog)]
    ($ :div.dossier
       ($ :h2 (:id source))
       ($ :p.sub
          (when-let [ty (:type source)] ($ :span.tag ty))
          (when (seq (:credential-keys source))
            ($ :span.tag.warn (str (count (:credential-keys source)) " credential key"
                                   (when (not= 1 (count (:credential-keys source))) "s"))))
          ;; The source knows its own schema; offering to read it here is the
          ;; shortest path from "a connector is declared" to "there is a model".
          ;; Only when it does know: `:describable?` is the server's answer for
          ;; `spi/supports-describe?`, and a source type with no
          ;; `describe-schema` (api, for one) can only answer this button with
          ;; a refusal. The hint below replaces it rather than leaving a dead
          ;; control on the page.
          ;; 8.4.22 裁定十六 ③: an api source has a wizard of its own (one real
          ;; call, then pick rows and fields), so the door opens for it too —
          ;; the generate panel routes an api type to that wizard.
          ;; doc 106 §106.16: the FIRST sentence, and the same one for every
          ;; connector — «there is a thing called X». It comes before the bulk
          ;; generator because it is what a modeller says first, and because
          ;; until now which screen said it depended on the source's kind.
          (when can-edit?
            ($ :button.btn.primary
               {:title "Say «there is a thing called X», from a table, an endpoint or a hand-written list"
                :on-click #(on-select {:kind :thing-create :id (:id source) :data source})}
               "有一种东西… · add a thing"))
          (when (and can-edit? (not editing?))
            ($ :button.btn {:on-click #(set-editing! true)} "edit")))
       ;; Stated as a property of this connector kind, not as a failure: an
       ;; HTTP upstream has no introspectable relational structure to read, so
       ;; there is nothing here that a working button would have found.
       (when (and (false? (:describable? source)) (not= "api" (:type source)))
         ($ :p.hint
            (str "A " (or (:type source) "source") " source exposes no introspectable schema, "
                 "so there is nothing to read a default model from — model it by hand: "
                 "declare the attributes, then a resolver that reads through this source.")))
       (when editing?
         ($ :<>
            ;; The form can only offer :type, :label and :description. A
            ;; source's remaining keys are declared by its connector contract
            ;; (D2.15), and the pool cannot enumerate them — so they live in
            ;; the entry untouched and are edited through the EDN view. Saying
            ;; that beats letting the form look as though they were gone.
            ($ :p.hint "Edit connection keys in the EDN view. Manage variable names and values in Workspace → Variables, and use ${VARIABLE_NAME} references here.")
            ($ asset-editor {:type :source :id (:id source)
                             :schema schema :catalog catalog :graph graph
                             :on-close #(set-editing! false)
                             :on-saved (fn [_] (set-editing! false) (on-model-change))})))
       (when-let [l (:label source)] ($ :p.lead l))
       (when-let [d (:description source)] ($ :p.hint d))
       ($ :div.ledgers
          ($ ledger {:title "Connector keys" :lead? true :items (:keys-set source)
                     :empty-note "only :type is declared"
                     :render (fn [label k]
                               ($ :<>
                                  ($ :span.mono label)
                                  (when (some #{k} (:credential-keys source))
                                    ($ :span.via " credential — value not shown"))))})
          ($ ledger {:title "Read by" :items (mapv :id (:used-by source))
                     :empty-note "no resolver in this graph reads it"
                     :render (fn [label rid]
                               ($ :button.link
                                  {:on-click #(on-select {:kind :resolver :id rid
                                                          :data (by-id rid)})}
                                  label))})))))

(defui source-create-panel
  "Declare a connector. `:type` names a registered connector contract, and the
   keys it then accepts are that contract's (D2.15) — the pool checks them, so
   an unknown key is refused rather than dropped."
  [{:keys [catalog schema graph on-select on-model-change on-close]}]
  ($ :div.dossier
     ($ :h2 "New source")
     ($ :p.hint "A source is where a resolver's data actually comes from. Create credentials in Workspace → Variables, then use ${VARIABLE_NAME} references in this Source.")
     ;; The seed stays sql: it is what most sources here are, and changing a
     ;; default that every existing path relies on would be a change nobody
     ;; asked for. What was missing is that `:type` is a *choice* — the
     ;; Sources page's connect card draws the keys for sql and api, and this
     ;; panel is the way to declare any other registered connector by hand.
     ;; The list is the server's answer, so it names what this instance can
     ;; actually compile rather than what a browser once believed.
     (when-let [types (seq (:source-types schema))]
       ($ :p.hint (str ":type seeds as \"sql\" and may be any connector this instance registers — "
                       (str/join ", " types)
                       ". The Sources page's connect card draws the connection keys for sql and api.")))
     ($ asset-editor {:type :source :mode :create
                      :template {:type "sql" :label ""}
                      :id-placeholder "source id, e.g. cis-demo-db"
                      :schema schema :catalog catalog :graph graph
                      :on-close on-close
                      :on-saved (fn [{:keys [id]}]
                                  (on-model-change)
                                  (on-select {:kind :source :id id}))})))

(defui statements-view
  "The statements this run actually sent to the sources.

   Shown on a refusal and on an empty result too, not only on a good one: the
   question \"why did I get nothing back\" is answered by the statement, and
   withholding it exactly when it is asked would be the wrong way round.

   Values ride beside the statement rather than inside it â that is how the
   connector sends them (`[sql v1 v2 â¦]`, never interpolated), and showing the
   two apart is the honest picture of what the database was handed."
  [{:keys [statements]}]
  (when (seq statements)
    ($ :details.statements
       ($ :summary (str (count statements)
                        (if (= 1 (count statements)) " statement" " statements")
                        " sent"))
       (for [[i st] (map-indexed vector statements)]
         ($ :div.statement {:key i}
            ($ :div.statement-head
               ($ :span.mono (:resolver-id st))
               ($ :span.via (str " · " (:source-type st)))
               ($ :span.spacer)
               (when (:rows st) ($ :span.count (str (:rows st) " rows")))
               ($ :span.count (str (:elapsed-ms st) " ms")))
            ($ :pre.out (:sql st))
            (when (seq (:values st))
              ($ :p.hint (str "values: " (pr-str (:values st))))))))))

(defui source-declared-structure
  "What an api-shaped source's resolvers DECLARE, when there is no schema to read.

   `describe-schema` is refused for these types and that is correct — an HTTP
   upstream has no introspectable relational structure. What this shows is a
   different fact: the resolvers already pointed at this source each wrote down
   an endpoint, a row path and an attribute→path mapping. That is the structure
   a modeller needs, and the panel used to count those resolvers and then say
   there was nothing here.

   It SHOWS and does not propose. Nothing is generated from a declaration: a
   `:field_mapping` is something a person wrote, not something an engine
   reported, and offering to build a «default model» out of it would blur the
   line the SQL path keeps clean."
  [{:keys [declared]}]
  (let [resolvers (:resolvers declared)]
    (if (empty? resolvers)
      ($ :p.hint
         "Nothing reads this source yet, so there is nothing declared to show. "
         "Model it by hand — declare the attributes, then a resolver that reads through it.")
      ($ :div.declared-structure
         ($ :p.hint
            "No schema to read, so this is what the "
            ($ :b (str (count resolvers)))
            (str " resolver" (when (not= 1 (count resolvers)) "s")
                 " reading it declare. Declared, not introspected — nothing here is proposed."))
         (for [r resolvers]
           ($ :div.declared-op {:key (:id r)}
              ($ :div.declared-op-head
                 ($ :span.mono.declared-op-id (:id r))
                 (when (:batch? r) ($ :span.tag "batch")))
              ($ :dl.declared-op-facts
                 (for [[label v] [["method" (:method r)]
                                  ["endpoint" (:endpoint r)]
                                  ["rows at" (:data_path r)]
                                  ["result" (:result_type r)]]
                       :when (seq (str v))]
                   ($ :<> {:key label}
                      ($ :dt label)
                      ($ :dd.mono v)))
                 (when-let [b (:batch r)]
                   ($ :<>
                      ($ :dt "batch key")
                      ($ :dd.mono (str (:parent-key b) " → " (:child-fk b)
                                       (when (:chunk-size b)
                                         (str "  (chunk " (:chunk-size b) ")")))))))
              (if (seq (:field-mapping r))
                ($ :table.declared-mapping
                   ($ :thead ($ :tr ($ :th "attribute") ($ :th "path in the response")))
                   ($ :tbody
                      (for [[attr path] (:field-mapping r)]
                        ($ :tr {:key attr}
                           ($ :td.mono attr)
                           ($ :td.mono path)))))
                ($ :p.hint "This resolver declares no field mapping."))
              (when (seq (:other-keys-set r))
                ;; Named, never shown: these can carry ${VAR} references and
                ;; templated values, and this panel must not become the one
                ;; place a credential reference is rendered.
                ($ :p.hint.declared-op-rest
                   (str "Also set, not shown here: "
                        (str/join ", " (:other-keys-set r)) ".")))))))))

(defn- json-path-get
  "One `$.a.b` path against a parsed response. The same syntax the server
   walks — this side only needs it to SHOW what the chosen path selects."
  [data path]
  (reduce (fn [acc seg]
            (cond (nil? acc) (reduced nil)
                  (map? acc) (or (get acc (keyword seg)) (get acc seg))
                  :else (reduced nil)))
          data
          (remove str/blank? (str/split (str/replace (str path) #"^\$\.?" "") #"\."))))

(defn- array-paths
  "Every path in a response whose value is an array of objects — the candidates
   for `:data_path` (8.4.22 裁定十六 ③ ②). Listed rather than guessed at: the
   server pre-selects one and the modeller may pick another."
  ([node] (array-paths node "$" 0))
  ([node path depth]
   (cond
     (and (sequential? node) (map? (first node))) [path]
     (> depth 6) []
     (map? node) (vec (mapcat (fn [[k v]] (array-paths v (str path "." (name k)) (inc depth)))
                              (sort-by (comp str key) node)))
     :else [])))

(defn- sampled-fields
  "The sampled records → the rows the field table shows: every value at the top
   level, and every scalar one level inside a nested object, each with the
   JSONPath that reaches it.

   Merged across the WHOLE sample (补裁 2026-09-04): the keys are the union and
   each row says in how many of the sampled records the field was present, out
   of however many records THIS probe read — a `note` seen in 30 of 500 is an
   optional field, which a sample of one could not have shown.

   Nested values are OFFERED and not taken: a person ticks the ones that are
   attributes of this entity, and each ticked one travels to `add_resolver` as
   `{name, path}` (8.4.22 裁定十六 ②)."
  [records]
  (let [records (vec records)
        total   (count records)
        rows-of (fn [record]
                  (mapcat (fn [[k v]]
                            (if (map? v)
                              (for [[k2 v2] (sort-by (comp str key) v)
                                    :when (not (or (map? v2) (sequential? v2)))]
                                {:path (str "$." (name k) "." (name k2))
                                 :local (name k2) :nested? true})
                              [{:path (str "$." (name k)) :local (name k) :nested? false}]))
                          (sort-by (comp str key) record)))
        seen    (reduce (fn [acc row] (update acc (:path row) (fnil inc 0)))
                        {}
                        (mapcat rows-of records))]
    (->> (mapcat rows-of records)
         (reduce (fn [acc row]
                   (if (some #(= (:path row) (:path %)) acc) acc (conj acc row)))
                 [])
         (mapv #(assoc % :seen (get seen (:path %) 0) :of total)))))

(defn- sampled-records
  "The records a probe reply holds at `p`: a truncated reply IS the vector of
   sampled records (already located by the connector), a whole-body reply is
   walked to `p` (8.4.23 裁定十七 + 补裁)."
  [reply p]
  (let [sample (:sample reply)
        located (cond
                  (:truncated? reply) sample
                  (or (nil? p) (= "$" p)) sample
                  :else (json-path-get sample p))]
    (cond
      (sequential? located) (vec located)
      (map? located) [located]
      :else [])))

(defui api-probe-step
  "Step 1 — which endpoint, and one real call to it."
  [{:keys [source-id path on-path method on-method busy? reply on-probe]}]
  ($ :div.wizard-step
     ($ :h3 "1 · Call it once")
     ($ :p.hint
        "An HTTP upstream exposes no schema to read, so its fields are learned from ONE real "
        "call. The probe reads the first 1000 records or up to its byte ceiling (8 MiB), "
        "whichever comes first, and refuses rather than truncating when not one complete record "
        "fits. What is recorded about the call is its path, its parameter names and its counts — "
        "never the body.")
     ($ field-row
        {:label "path" :hint (str "relative to " source-id "'s base URL")
         :children ($ :input {:value (or path "") :aria-label "api path"
                              :spell-check false :placeholder "/users"
                              :on-change #(on-path (.. % -target -value))})})
     ($ field-row
        {:label "method" :hint "GET"
         :children ($ :input {:value (or method "GET") :aria-label "http method"
                              :spell-check false
                              :on-change #(on-method (.. % -target -value))})})
     ($ :div.asset-editor-actions
        ($ :button.btn.primary
           {:disabled (or busy? (str/blank? (str/trim (or path ""))))
            :on-click on-probe}
           (if busy? "calling…" (if reply "try again" "试一次 · try it once"))))
     (when reply
       ($ :p.sub
          ($ :span.tag (str "HTTP " (:status reply)))
          ;; What was READ, always known; the size of the whole answer only
          ;; when the probe read all of it (8.4.23 裁定十七).
          ($ :span.tag (str (or (:bytes reply) (:bytes-read reply)) " bytes"
                            (when (:truncated? reply) " read")))
          (when (:records reply)
            ($ :span.tag (str (:records reply) " records")))))))

(defui api-records-step
  "Step 2 — where the records are in the tree."
  [{:keys [reply data-path on-data-path]}]
  (let [candidates (distinct (concat (array-paths (:sample reply))
                                     (when (:data-path reply) [(:data-path reply)])))]
    ($ :div.wizard-step
       ($ :h3 "2 · The records")
       ($ :p.hint
          "Which array in the response holds one record each. The first array of objects is "
          "pre-selected; it becomes the resolver's :data_path, and the query path reads its "
          "rows from exactly there.")
       ;; 8.4.23 裁定十七: the probe stops at the first record, so what is shown
       ;; below is ONE record and not the whole answer — said out loud, because
       ;; a tree read as the whole response would be read as the whole schema.
       (when (:truncated? reply)
         ($ :p.hint.warn
            (str "Only the first " (or (:records reply) 0) " record(s) were read: this endpoint "
                 "answers with more than a probe reads, so the probe stopped there and closed "
                 "the connection. The first one is shown below; the fields come from all "
                 (or (:records reply) 0) ".")))
       (if (empty? candidates)
         ($ :p.hint.bad "No array of objects in this response — a single-record endpoint is modelled by hand.")
         ($ :div.api-record-paths
            (for [c candidates]
              ($ :label.api-record-path {:key c}
                 ($ :input {:type "radio" :name "api-data-path"
                            :checked (= c data-path)
                            :on-change #(on-data-path c)})
                 ($ :span.mono c)
                 (when (= c (:data-path reply)) ($ :span.tag "suggested"))))))
       ($ :details.api-response-tree
          ($ :summary (if (:truncated? reply) "first record" "response"))
          ;; A sampled reply is a VECTOR of records; showing all thousand would
          ;; bury the shape a person is here to read (补裁 2026-09-04).
          ($ editor/edn-view {:text (pr-str (if (:truncated? reply)
                                 (first (:sample reply))
                                 (:sample reply)))})))))

(defui api-fields-step
  "Step 3 — the record, expanded into fields."
  [{:keys [fields on-toggle]}]
  ($ :div.wizard-step
     ($ :h3 "3 · The fields")
     ($ :p.hint
        "One row per value across the sampled records, nested values included. «Seen in» says "
        "in how many of them the field was present — anything short of all of them is an "
        "OPTIONAL field. Every type here is INFERRED from that sample, so all of them are "
        "marked pending: the plan lists them for a person to accept, and nothing is asserted "
        "about a value the sample did not carry.")
     (if (empty? fields)
       ($ :p.hint.bad "The chosen path holds no record to read fields from.")
       ($ :table.api-fields
          ($ :thead ($ :tr ($ :th "include") ($ :th "JSONPath") ($ :th "attribute")
                        ($ :th "seen in") ($ :th "type")))
          ($ :tbody
             (for [{:keys [path local nested? include? seen of]} fields]
               ($ :tr {:key path}
                  ($ :td ($ :input {:type "checkbox" :checked (boolean include?)
                                    :aria-label (str "include " path)
                                    :on-change #(on-toggle path)}))
                  ($ :td ($ :span.mono path) (when nested? ($ :span.tag "nested")))
                  ($ :td ($ :span.mono local))
                  ($ :td ($ :span.mono (str seen "/" of))
                     (when (and of (pos? of) (< seen of)) ($ :span.tag "optional")))
                  ($ :td ($ :span.tag.warn "pending"))))))) ))

(defui api-review-step
  "Step 4 — plan, then write, through the same planner the MCP endpoint uses."
  [{:keys [relation on-relation namespace* on-namespace plan applied busy? planning?
           can-plan? on-plan on-apply]}]
  ($ :div.wizard-step
     ($ :h3 "4 · Plan and write")
     ($ :p.hint
        "The resolver, the attributes and the recipe change are generated by add_resolver — the "
        "same tool an agent calls — so what is written here is what an agent would have written.")
     ($ field-row
        {:label "relation" :hint "what to call these records"
         :children ($ :input {:value (or relation "") :aria-label "relation name"
                              :spell-check false :placeholder "users"
                              :on-change #(on-relation (.. % -target -value))})})
     ($ field-row
        {:label "namespace" :hint "left empty, derived from the graph's own"
         :children ($ :input {:value (or namespace* "") :aria-label "namespace"
                              :spell-check false :placeholder "cis.users"
                              :on-change #(on-namespace (.. % -target -value))})})
     ($ :div.asset-editor-actions
        ($ :button.btn {:disabled (or planning? (not can-plan?)) :on-click on-plan}
           (if planning? "planning…" "plan"))
        ($ :button.btn.primary
           {:disabled (or busy? (not can-plan?) (nil? plan)) :on-click on-apply}
           (if busy? "writing…" "add to the graph")))
     (when-let [shown (or applied plan)]
       ($ :<>
          (when (seq (:fill shown))
            ($ :<>
               ($ :p.hint "Positions waiting for a person:")
               ($ :ul.api-fill
                  (for [f (:fill shown)]
                    ($ :li.mono {:key (pr-str f)} (str/join " · " (map str f)))))))
          (when (seq (:actions shown))
            ($ :details.api-plan
               ($ :summary (str (count (:actions shown)) " action"
                                (when (not= 1 (count (:actions shown))) "s")))
               ($ editor/edn-view {:text (pr-str (:actions shown))})))
          (when (:written? applied)
            ($ :p.hint (str "written: " (get-in applied [:created :resolver]))))
          (when (seq (:findings shown))
            ($ findings-view {:error {:findings (:findings shown)}}))))))

(defui api-source-panel
  "Probe one API endpoint and author one resolver from the returned evidence.
   The probe describes one response, so this panel ends at one resolver rather
   than inferring a wider source model."
  [{:keys [source-id graph on-model-change]}]
  (let [[path set-path!]           (uix/use-state "")
        [method set-method!]       (uix/use-state "GET")
        [reply set-reply!]         (uix/use-state nil)
        [data-path set-data-path!] (uix/use-state nil)
        [fields set-fields!]       (uix/use-state [])
        [relation set-relation!]   (uix/use-state "")
        [namespace* set-namespace!] (uix/use-state "")
        [plan set-plan!]           (uix/use-state nil)
        [applied set-applied!]     (uix/use-state nil)
        [probing? set-probing!]    (uix/use-state false)
        [planning? set-planning!]  (uix/use-state false)
        [writing? set-writing!]    (uix/use-state false)
        [error set-error!]         (uix/use-state nil)
        ;; The fields the table shows for a given path, everything ticked: the
        ;; common case is «all of it», and untick is cheaper than tick.
        fields-for (fn [reply p]
                     (mapv #(assoc % :include? true)
                           (sampled-fields (sampled-records reply p))))
        outputs (fn []
                  (vec (for [{:keys [path local include? nested?]} fields
                             :when include?]
                         (if nested? {:name local :path path} local))))
        request (fn [more]
                  (merge {:source source-id :graph graph :path path :method method
                          :relation (str/trim relation)
                          :data_path data-path
                          :outputs (outputs)}
                         (when-not (str/blank? (str/trim namespace*))
                           {:namespace (str/trim namespace*)})
                         more))]
    ($ :<>
       ($ :p.hint
          "This source's type exposes no introspectable schema — an HTTP upstream has none. "
          "Its fields are read from one real call instead, and the resolver is built from what "
          "answered.")
       (when error ($ findings-view {:error error}))
       ($ api-probe-step
          {:source-id source-id :path path :on-path set-path!
           :method method :on-method set-method!
           :busy? probing? :reply reply
           :on-probe (fn []
                       (set-probing! true) (set-error! nil)
                       (set-plan! nil) (set-applied! nil)
                       (api/api-probe!
                        {:source source-id :path path :method method}
                        {:on-success (fn [r]
                                       (set-probing! false)
                                       (set-reply! r)
                                       (set-data-path! (:data-path r))
                                       (set-fields! (fields-for r (:data-path r)))
                                       (when (str/blank? relation)
                                         (set-relation! (last (remove str/blank?
                                                                      (str/split (str path) #"/"))))))
                         :on-error (fn [e] (set-probing! false) (set-error! e))}))})
       (when reply
         ($ :<>
            ($ api-records-step
               {:reply reply :data-path data-path
                :on-data-path (fn [p]
                                (set-data-path! p)
                                (set-fields! (fields-for reply p))
                                (set-plan! nil) (set-applied! nil))})
            ($ api-fields-step
               {:fields fields
                :on-toggle (fn [p]
                             (set-plan! nil) (set-applied! nil)
                             (set-fields! (fn [fs]
                                            (mapv #(cond-> % (= p (:path %))
                                                           (update :include? not))
                                                  fs))))})
            ($ api-review-step
               {:relation relation :on-relation set-relation!
                :namespace* namespace* :on-namespace set-namespace!
                :plan plan :applied applied
                :busy? writing? :planning? planning?
                :can-plan? (and graph (seq (str/trim (or relation "")))
                                (some :include? fields))
                :on-plan (fn []
                           (set-planning! true) (set-error! nil) (set-applied! nil)
                           (api/api-resolver!
                            (request nil)
                            {:on-success (fn [r] (set-planning! false) (set-plan! r))
                             :on-error (fn [e] (set-planning! false) (set-error! e))}))
                :on-apply (fn []
                            (set-writing! true) (set-error! nil)
                            (api/api-resolver!
                             (request {:apply true})
                             {:on-success (fn [r]
                                            (set-writing! false)
                                            (set-applied! r)
                                            (when (:written? r) (on-model-change)))
                              :on-error (fn [e] (set-writing! false) (set-error! e))}))})))
       (when-not graph
         ($ :p.hint.bad "Pick a graph first: a resolver is added to one.")))))

(defn- placeholder-name
  "`{+nifi.flow.group/id}` → `nifi.flow.group/id`. The key a `sample` map is read
   by, both here and in `intent-declarations`' `path-sample` — one spelling, or
   the value a person typed fills nothing and the probe refuses for the wrong
   reason."
  [ph]
  (str/replace (str ph) #"[{}+?]" ""))

(defn- use-need-view
  "The graph read back in the four words (`inspect_graph view=things`).

   The criterion is `dialect/need-view`'s, computed on the SERVER and shared
   with the data plane's `list_entities` (doc 106 §106.5). Nothing here decides
   what a thing is — a second criterion in the browser is exactly how the same
   graph becomes five things in the Builder and six entities over MCP."
  [graph]
  (let [[state set-state!] (uix/use-state nil)
        [nonce set-nonce!] (uix/use-state 0)
        reload! (uix/use-callback (fn [] (set-nonce! inc)) [])]
    (uix/use-effect
     (fn []
       (when (and graph (not (workspace-scope? graph)))
         (api/intent! "inspect_graph" {:graph graph :view :things}
                      {:on-success set-state!
                       :on-error (fn [e] (set-state! {:things [] :fragments [] :concepts []
                                                      :findings (or (:findings e) [e])}))}))
       js/undefined)
     [graph nonce])
    [state reload!]))

;; ---------------------------------------------------------------------------
;; «有一种东西叫 X» — the Thing wizard (need-grammar-v1 A1 + A2, doc 106 §106.16)
;; ---------------------------------------------------------------------------

(defn- plan-summary-lines
  "A plan's `:summary` as lines. The PLANNER writes it (doc 106 §106.4, «三行人话»),
   so this only has to decide what a missing one looks like — nothing, rather
   than a sentence invented here."
  [plan]
  (let [s (:summary plan)]
    (cond (sequential? s) (vec s)
          (string? s) (vec (remove str/blank? (str/split-lines s)))
          :else [])))

(defui plan-confirm
  "What a sentence is about to write, before it writes it.

   The `:summary` lines come from the planner — they are the sentence read back
   in the words a person used — and `:fill` is what it could not settle from the
   evidence and will not guess (need-grammar-v1 §2 不变量 1). Both are shown
   BEFORE apply because that is the whole difference between a plan and a write."
  [{:keys [plan applied busy? on-apply apply-label]}]
  (let [shown (or applied plan)
        lines (plan-summary-lines shown)
        blocked? (boolean (some #(= :error (:severity %)) (:findings shown)))]
    (when shown
      ($ :div.thing-confirm
         (if (seq lines)
           ($ :ul.thing-summary
              (for [[i l] (map-indexed vector lines)]
                ($ :li {:key i} l)))
           ;; No summary and no findings is not a quiet success: the planner
           ;; writes one for every sentence it can plan.
           (when-not blocked?
             ($ :p.hint "This plan carries no summary — read the actions below before applying.")))
         (when (seq (:fill shown))
           ($ :<>
              ($ :p.hint "Positions the evidence did not settle — a person fills these:")
              ($ :ul.api-fill
                 (for [f (:fill shown)]
                   ($ :li.mono {:key (pr-str f)} (str/join " · " (map str f)))))))
         (when (seq (:actions shown))
           ($ :details.api-plan
              ($ :summary (str (count (:actions shown)) " action"
                               (when (not= 1 (count (:actions shown))) "s")
                               (when-let [d (:recipe-delta shown)]
                                 (str " · recipe delta " (pr-str (into {} (map (fn [[k v]] [k (count v)]) d)))))))
              ($ editor/edn-view {:text (pr-str (:actions shown))})))
         (when (seq (:findings shown))
           ($ findings-view {:error {:findings (:findings shown)}}))
         (when-not applied
           ($ :div.asset-editor-actions
              ($ :button.btn.primary
                 {:disabled (or busy? blocked? (nil? plan)) :on-click on-apply}
                 (if busy? "writing…" (or apply-label "写进图 · add it to the graph")))))
         (when applied
           ($ :p.hint (str "written · " (count (:mutations applied)) " pool mutation"
                           (when (not= 1 (count (:mutations applied))) "s"))))))))

(defui sample-map-editor
  "One real value per `{…}` in a templated path or base URL.

   The probe calls the template ONCE with these and stores the template, never
   the values (need-grammar-v1 §3.2). A templated `:base_url` puts its own
   attribute in this list too (§3.5, «sample 在这里是「哪一台」»)."
  [{:keys [placeholders bound sample on-change]}]
  ;; Keyed by the placeholder's NAME, not by its braces: `path-sample` reads the
  ;; map by `id` / `nifi.instance/host`, and `{id}` matched nothing — the probe
  ;; then refused with «give one real value of EVERY key it names» while the
  ;; person had just typed one. Found by the §106.16 acceptance run.
  (let [slots (vec (distinct (concat (map #(str/replace (str %) #"[{}+?]" "") placeholders)
                                     bound)))]
    (when (seq slots)
      ($ :div.thing-sample
         ($ :p.hint
            "One real value for each placeholder, used for ONE call so the fields can be read. "
            "The value is never stored — the model keeps the template.")
         (for [k slots]
           ($ field-row
              {:key k :label k
               :hint (if (some #{k} bound) "which instance (base URL)" "a real key value")
               :children ($ :input {:value (str (get sample k ""))
                                    :aria-label (str "sample value for " k)
                                    :spell-check false
                                    :on-change #(on-change (assoc sample k (.. % -target -value)))})}))))))

(defui rows-grid
  "A hand-written list (need-grammar-v1 §3.1 / A1's third ⟨源⟩).

   Edited as EDN text rather than a spreadsheet: `rows` ships INSIDE the model
   and is part of its version, so what is typed is exactly what is stored, and a
   grid that normalises on the way through would make those two different."
  [{:keys [text on-change parsed error]}]
  ($ :div.thing-rows
     ($ :p.hint
        "The records themselves, as a vector of maps — an environment roster, a code table, "
        "a handful of instances. They ship with the model and are part of its version, so "
        "there is no source to read and no table or endpoint to name.")
     ($ editor/edn-editor {:text (or text "") :on-change on-change
                           :aria-label "hand-written rows" :line-numbers? true
                           :height "200px"})
     (cond
       error ($ :p.hint.bad (str "unreadable: " error))
       (seq parsed) ($ :p.hint (str (count parsed) " record"
                                    (when (not= 1 (count parsed)) "s")
                                    " · fields: "
                                    (str/join ", " (sort (distinct (mapcat (comp (partial map name) keys)
                                                                          parsed))))))
       :else nil)))

(defui identity-picker
  "«用 k 认它» — and «用 a 和 b 一起认它» (need-grammar-v1 §3.6).

   A MULTI-select, because a compound key is a set of fields that recognise one
   record TOGETHER; one ticked box is the ordinary case and reads back the same.
   Carried attributes — a templated `:base_url`'s `{host}` — are offered
   alongside, which is item 18: the key may include an attribute the connection
   brings into the row rather than one the source's records carry."
  [{:keys [fields carried identity* on-toggle]}]
  ($ :div.wizard-step
     ($ :h3 "2 · What recognises one of them")
     ($ :p.hint
        "Tick the field that tells one of these apart. Tick SEVERAL when it takes them "
        "together — a Kubernetes pod is «which namespace, which name». Tick none and you "
        "get «all of them», which answers without being told which one.")
     ($ :div.thing-identity
        (for [f fields]
          ($ :label.wizard-check {:key f}
             ($ :input {:type "checkbox" :checked (boolean (some #{f} identity*))
                        :aria-label (str "identity " f)
                        :on-change #(on-toggle f)})
             ($ :span.mono f))))
     (when (seq carried)
       ($ :<>
          ($ :p.hint
             "This source picks its instance per query, so these are in every row it answers "
             "even though no record carries them. Tick one to make it part of the key.")
          ($ :div.thing-identity
             (for [f carried]
               ($ :label.wizard-check {:key f}
                  ($ :input {:type "checkbox" :checked (boolean (some #{f} identity*))
                             :aria-label (str "identity " f)
                             :on-change #(on-toggle f)})
                  ($ :span.mono f)
                  ($ :span.tag "from the connection"))))))
     (when (empty? identity*)
       ($ :p.hint "Nothing ticked — the plan will say «all of them» and no by-key lookup."))))

(defui attributes-picker
  "«X 有属性 a、b…» — which fields to take, with the type the evidence gave.

   Untick is cheaper than tick, so everything the source typed starts ticked;
   an api's types are all INFERRED from one sample and say so (D2.14: no type is
   asserted about a value the evidence did not carry)."
  [{:keys [fields inferred? on-toggle]}]
  ($ :div.wizard-step
     ($ :h3 "3 · Its attributes")
     (if (empty? fields)
       ($ :p.hint.bad "No field to read yet — name where they come from first.")
       ($ :table.api-fields
          ($ :thead ($ :tr ($ :th "include") ($ :th "field") ($ :th "attribute") ($ :th "type")))
          ($ :tbody
             (for [{:keys [path local include? nested? type seen of]} fields]
               ($ :tr {:key path}
                  ($ :td ($ :input {:type "checkbox" :checked (boolean include?)
                                    :aria-label (str "include " path)
                                    :on-change #(on-toggle path)}))
                  ($ :td ($ :span.mono path) (when nested? ($ :span.tag "nested")))
                  ($ :td ($ :span.mono local))
                  ($ :td (if inferred?
                           ($ :span.tag.warn "inferred")
                           ($ :span.tag (str (or type "—"))))
                     (when (and of (pos? of) seen (< seen of)) ($ :span.tag "optional"))))))))))

(defui thing-wizard
  "**«有一种东西叫 X»** — one flow for every kind of source (doc 106 §106.16).

   This replaces the sql/api split for the sentence A1 says. The two old panels
   were symmetric but separate, and a modeller had to know which connector a
   source was before knowing which screen said «there is a thing called X».
   A source's KIND decides only where the evidence comes from — a table's
   declared columns, one real call, or the list typed here — and never what the
   sentence is.

   **Nothing about the model is decided in this namespace.** Where the fields
   are registered, which resolver shape a templated path implies, whether a
   collection endpoint gets a by-key lookup, the `:batch`, the `:field_mapping`,
   the recipe delta: all of it is `add_thing`'s, reached through
   `/api/studio/intent` — the defence §106.16 names against a second planner (D1.8).
   What is here is a form and the evidence it prefills from."
  [{:keys [source graph on-model-change on-close]}]
  (let [source-id (:id source)
        api? (= "api" (:type source))
        ;; §106.14-11: the attributes this source binds by picking an instance.
        ;; The SERVER's answer (the connector's own `connection-bound-attributes`),
        ;; not a placeholder scan done here.
        carried (vec (:bound-attributes source))
        [name* set-name!]        (uix/use-state "")
        [origin set-origin!]     (uix/use-state (if api? :endpoint :table))
        [from set-from!]         (uix/use-state "")
        [namespace* set-namespace!] (uix/use-state "")
        [sample set-sample!]     (uix/use-state {})
        [rows-text set-rows-text!] (uix/use-state "")
        [relations set-relations!] (uix/use-state nil)
        [fields set-fields!]     (uix/use-state [])
        [identity* set-identity!] (uix/use-state [])
        [reply set-reply!]       (uix/use-state nil)
        [data-path set-data-path!] (uix/use-state nil)
        [plan set-plan!]         (uix/use-state nil)
        [applied set-applied!]   (uix/use-state nil)
        [busy set-busy!]         (uix/use-state nil)
        [error set-error!]       (uix/use-state nil)
        forget! (fn [] (set-plan! nil) (set-applied! nil))
        ;; The `{…}` this path names. Read off the text the modeller typed
        ;; because it IS the text the modeller typed — the placeholder set is a
        ;; property of the string, not a judgement about the model.
        placeholders (vec (map first (re-seq #"\{\+?[^{}]*\}" (str from))))
        rows-parsed (try (let [v (reader/read-string (str/trim (or rows-text "")))]
                           (when (and (sequential? v) (every? map? v)) (vec v)))
                         (catch :default _ nil))
        rows-error (when (and (= origin :rows) (seq (str/trim (or rows-text "")))
                              (nil? rows-parsed))
                     "a hand-written list is a vector of maps")
        set-fields-from (fn [fs] (set-fields! (mapv #(assoc % :include? true) fs)))
        ;; Every field name the modeller may tick as the key: the evidence's,
        ;; plus whatever `rows` carries.
        field-names (mapv :local fields)
        args (fn [more]
               (merge
                (cond-> {:graph graph :name (str/trim name*)}
                  (seq identity*) (assoc :identity (vec identity*))
                  (seq (str/trim (or namespace* ""))) (assoc :namespace (str/trim namespace*))
                  (not= origin :rows) (assoc :source source-id :from (str/trim from))
                  (= origin :rows) (assoc :rows (or rows-parsed []))
                  (and (not= origin :rows) (seq sample)) (assoc :sample sample)
                  (and api? (seq (str data-path))) (assoc :data_path data-path)
                  (seq fields) (assoc :attributes
                                      (vec (for [{:keys [local path nested? include?]} fields
                                                 :when include?]
                                             (if nested? {:name local :path path} local)))))
                more))
        run! (fn [more k]
               (set-busy! k) (set-error! nil)
               (api/intent! "add_thing" (args more)
                            {:on-success (fn [r]
                                           (set-busy! nil)
                                           (if more (set-applied! r) (set-plan! r))
                                           (when (and more (seq (:mutations r)))
                                             (on-model-change)))
                             :on-error (fn [e] (set-busy! nil) (set-error! e))}))]
    ;; The tables this source declares, read once for the table branch. An api
    ;; source has none — its evidence is one real call, below.
    (uix/use-effect
     (fn []
       (when (and (= origin :table) (nil? relations) (not (false? (:describable? source))))
         (api/source-schema!
          {:source source-id :keys? false}
          {:on-success (fn [r] (set-relations! (vec (:relations r))))
           :on-error (fn [e] (set-relations! []) (set-error! e))}))
       js/undefined)
     [origin relations source-id source])
    ($ :div.dossier.thing-wizard
       ($ :h2 "有一种东西叫 … · There is a thing called …")
       ($ :p.hint
          "One sentence: what this kind of thing is called, what recognises one of them, and "
          "where they come from. Everything else — where its fields are registered, the "
          "by-key lookup, the recipe change — is generated by add_thing, the same tool an "
          "agent calls, so what this writes is what an agent would have written.")
       (when error ($ findings-view {:error error}))

       ($ :div.wizard-step
          ($ :h3 "1 · Its name, and where they come from")
          ($ field-row
             {:label "name" :hint "«processor», «customer» — a name with dots is the exact place to register it"
              :children ($ :input {:value name* :aria-label "thing name" :spell-check false
                                   :placeholder "processor"
                                   :on-change #(do (forget!) (set-name! (.. % -target -value)))})})
          ($ :div.thing-origins
             (for [[k label hint]
                   [[:table "a table" "a relation this database declares"]
                    [:endpoint "an endpoint" "a path this API answers"]
                    [:rows "a hand-written list" "records typed here, shipped with the model"]]]
               ($ :label.wizard-check {:key (str k)}
                  ($ :input {:type "radio" :name "thing-origin" :checked (= origin k)
                             :aria-label label
                             :on-change #(do (forget!) (set-origin! k) (set-fields! [])
                                             (set-identity! []) (set-reply! nil))})
                  ($ :span label) ($ :span.field-hint hint))))

          (case origin
            :table
            ($ :<>
               ($ field-row
                  {:label "table" :hint "the relation these records live in"
                   :children ($ :input {:value from :aria-label "table name" :spell-check false
                                        :list "thing-relations" :placeholder "customers"
                                        :on-change #(do (forget!) (set-from! (.. % -target -value)))})})
               ($ :datalist {:id "thing-relations"}
                  (for [r (or relations [])]
                    ($ :option {:key (:name r) :value (:name r)})))
               ($ :div.asset-editor-actions
                  ($ :button.btn
                     {:disabled (or (= :reading busy) (str/blank? (str/trim from)))
                      :on-click
                      (fn []
                        (set-busy! :reading) (set-error! nil) (forget!)
                        (api/source-schema!
                         {:source source-id :names [(str/trim from)] :keys? true}
                         {:on-success
                          (fn [r]
                            (set-busy! nil)
                            (let [rel (first (:relations r))]
                              (set-relations! (vec (:relations r)))
                              (set-fields-from
                               (mapv (fn [c] {:path (:name c) :local (:name c)
                                              :type (some-> (:class c) name)})
                                     (:columns rel)))
                              ;; The table's declared primary key is what
                              ;; recognises one of them — offered, not applied:
                              ;; a compound key is ticked here and a modeller
                              ;; may say something else.
                              (when (seq (:primary-key rel))
                                (set-identity! (vec (:primary-key rel))))))
                          :on-error (fn [e] (set-busy! nil) (set-error! e))}))}
                     (if (= :reading busy) "reading…" "read its columns"))))

            :endpoint
            ($ :<>
               ($ field-row
                  {:label "endpoint"
                   :hint (str "relative to " (or source-id "the source") "'s base URL; "
                              "write /processors/{id} for one at a time, /processors for all of them")
                   :children ($ :input {:value from :aria-label "endpoint path" :spell-check false
                                        :placeholder "/processors/{id}"
                                        :on-change #(do (forget!) (set-from! (.. % -target -value)))})})
               ($ sample-map-editor
                  {:placeholders placeholders :bound carried :sample sample
                   :on-change #(do (forget!) (set-sample! %))})
               ($ :div.asset-editor-actions
                  ($ :button.btn
                     {:disabled (or (= :probing busy) (str/blank? (str/trim from)))
                      :on-click
                      (fn []
                        (set-busy! :probing) (set-error! nil) (forget!)
                        ;; The probe is called with the SUBSTITUTED path — the
                        ;; same substitution `add_thing` makes for its own one
                        ;; call (need-grammar-v1 §3.2) — because a template
                        ;; cannot be called as it stands.
                        (let [called (reduce (fn [p ph]
                                               (str/replace p ph (str (get sample (placeholder-name ph) ""))))
                                             (str/trim from) placeholders)]
                          (api/api-probe!
                           {:source source-id :path called :method "GET"}
                           {:on-success (fn [r]
                                          (set-busy! nil)
                                          (set-reply! r)
                                          (set-data-path! (:data-path r))
                                          (set-fields-from
                                           (sampled-fields (sampled-records r (:data-path r)))))
                            :on-error (fn [e] (set-busy! nil) (set-error! e))})))}
                     (if (= :probing busy) "calling…" "试一次 · call it once")))
               (when reply
                 ($ :p.sub
                    ($ :span.tag (str "HTTP " (:status reply)))
                    (when (:records reply) ($ :span.tag (str (:records reply) " records")))
                    (when (:data-path reply) ($ :span.tag.mono (:data-path reply))))))

            :rows
            ($ rows-grid
               {:text rows-text :parsed rows-parsed :error rows-error
                :on-change (fn [t]
                             (forget!) (set-rows-text! t)
                             (let [v (try (reader/read-string (str/trim (or t "")))
                                          (catch :default _ nil))]
                               (when (and (sequential? v) (every? map? v))
                                 (set-fields-from
                                  (mapv (fn [k] {:path (name k) :local (name k)})
                                        (distinct (mapcat keys v)))))))})))

       ($ identity-picker
          {:fields field-names :carried carried :identity* identity*
           :on-toggle (fn [f]
                        (forget!)
                        (set-identity! (fn [ids]
                                         (if (some #{f} ids)
                                           (vec (remove #{f} ids))
                                           (conj (vec ids) f)))))})

       ($ attributes-picker
          {:fields fields :inferred? (= origin :endpoint)
           :on-toggle (fn [p]
                        (forget!)
                        (set-fields! (fn [fs]
                                       (mapv #(cond-> % (= p (:path %)) (update :include? not))
                                             fs))))})

       ($ :div.wizard-step
          ($ :h3 "4 · Read it back, then write it")
          ($ field-row
             {:label "namespace" :hint "advanced — left empty, derived from the name"
              :children ($ :input {:value namespace* :aria-label "namespace" :spell-check false
                                   :placeholder "nifi.flow.processor"
                                   :on-change #(do (forget!) (set-namespace! (.. % -target -value)))})})
          ($ :div.asset-editor-actions
             ($ :button.btn
                {:disabled (or (= :planning busy)
                               (str/blank? (str/trim name*))
                               (not graph)
                               (if (= origin :rows)
                                 (empty? rows-parsed)
                                 (str/blank? (str/trim from))))
                 :on-click #(run! nil :planning)}
                (if (= :planning busy) "planning…" "plan"))
             ($ :button.btn {:on-click on-close} "close"))
          ($ plan-confirm
             {:plan plan :applied applied :busy? (= :applying busy)
              :on-apply #(run! {:apply true} :applying)}))
       (when-not graph
         ($ :p.hint.bad "Pick a graph first: a thing is added to one.")))))

;; ---------------------------------------------------------------------------
;; «每个 B 有很多 A» — the Relation dialog (need-grammar-v1 B1 / B2 / B4)
;; ---------------------------------------------------------------------------

(def ^:private relation-choices
  "The three sentences a person says about two things (need-grammar-v1 B).

   `parent` and `child` mean the SAME in all three — the parent is the «one»
   end, the child the «many» end — and the caller does not swap them per kind
   (doc 106 §106.12 item 8.5). What changes is where the new key lands, which
   is `:says` below and which the plan's own `:summary` repeats.

   B1 and B2 are ONE plan: «每个 A 属于一个 B» is the other way of reading
   «每个 B 有很多 A», normalised to a single canonical form (不变量 2). They are
   two rows here because they are two things a person says, not two shapes."
  [{:id :has-many :kind :many
    :label "每个 B 有很多 A · every parent has many children"
    :says "a key on the PARENT that reaches its children, and — when `via` is known — the child's key back"}
   {:id :belongs-to :kind :many
    :label "每个 A 属于一个 B · every child belongs to one parent"
    :says "the same plan read the other way: it is one canonical form, so this also gives the parent its collection"}
   {:id :has-one :kind :one
    :label "每个 A 有一个 B · every child has one parent (a reference)"
    :says "only the child's key back; no reverse «the parent has many» is invented — an instance has a root group, but a group does not have many instances"}])

(defui filters-editor
  "`filters` — the narrowing a QUERY may pass (need-grammar-v1 §4).

   Not one of the twelve sentences and deliberately not given one: it is carried
   through to the new resolver's `:eql-params` verbatim, and the only judgement
   the tool makes is qualifying `attribute` to this thing's namespace. So this
   editor carries it verbatim too — three boxes per parameter, no vocabulary of
   its own.

   Not to be confused with `params`, which is this ONE call's query string,
   frozen into the model."
  [{:keys [filters on-change]}]
  ($ :details.thing-filters
     ($ :summary (str "filters · query-time narrowing"
                      (when (seq filters) (str " (" (count filters) ")"))))
     ($ :p.hint
        "Parameters a QUERY may pass to narrow this collection — they land in the resolver's "
        ":eql-params unchanged. Not the same as params, which freezes this one call's query "
        "string into the model.")
     (for [[i {:keys [param attribute op description]}] (map-indexed vector filters)]
       ($ :div.thing-filter {:key i}
          ($ :input {:value (or param "") :aria-label "parameter name" :placeholder "state"
                     :spell-check false
                     :on-change #(on-change (assoc-in (vec filters) [i :param] (.. % -target -value)))})
          ($ :input {:value (or attribute "") :aria-label "attribute" :placeholder "state"
                     :spell-check false
                     :on-change #(on-change (assoc-in (vec filters) [i :attribute] (.. % -target -value)))})
          ($ :input {:value (or op "") :aria-label "operator" :placeholder "="
                     :spell-check false
                     :on-change #(on-change (assoc-in (vec filters) [i :op] (.. % -target -value)))})
          ($ :input {:value (or description "") :aria-label "description"
                     :placeholder "only the ones in this state"
                     :on-change #(on-change (assoc-in (vec filters) [i :description] (.. % -target -value)))})
          ($ :button.btn.tiny {:on-click #(on-change (vec (concat (subvec (vec filters) 0 i)
                                                                  (subvec (vec filters) (inc i)))))}
             "remove")))
     ($ :button.btn.tiny
        {:on-click #(on-change (conj (vec filters) {:param "" :attribute "" :op "=" :description ""}))}
        "add a filter")))

(defui relation-dialog
  "**«每个 B 有很多 A»** — one dialog for B1, B2 and B4 (doc 106 §106.16).

   Every shape decision is `add_relation`'s: whether the sql branch gets a
   `:batch`, whether the api branch templates the parent's key into the child
   endpoint, whether a `$…` `from` is an array inside the parent's own record
   (§5.2), which resolver gains the `{A/b [B/k]}` branch that produces the
   «belongs to» key (§5.1). None of it is decided here — this dialog collects
   the sentence and shows the plan.

   The `via` box is a multi-select over the child's fields for the same reason
   `identity` is: when the parent takes several fields together to recognise
   one, `via` names one child field per part, paired position by position
   (§3.6)."
  [{:keys [graph sources initial-parent on-model-change on-close]}]
  (let [[need _reload-need!]   (use-need-view graph)
        things (vec (:things need))
        [choice set-choice!]   (uix/use-state :has-many)
        [parent set-parent!]   (uix/use-state (or initial-parent ""))
        [child set-child!]     (uix/use-state "")
        [via set-via!]         (uix/use-state [])
        [from set-from!]       (uix/use-state "")
        [name* set-name!]      (uix/use-state "")
        [source* set-source!]  (uix/use-state "")
        [sample set-sample!]   (uix/use-state {})
        [attrs set-attrs!]     (uix/use-state "")
        [filters set-filters!] (uix/use-state [])
        [plan set-plan!]       (uix/use-state nil)
        [applied set-applied!] (uix/use-state nil)
        [busy set-busy!]       (uix/use-state nil)
        [error set-error!]     (uix/use-state nil)
        forget! (fn [] (set-plan! nil) (set-applied! nil))
        chosen (first (filter #(= choice (:id %)) relation-choices))
        one? (= :one (:kind chosen))
        ;; The child's own fields, as the graph reads back — `via` names one of
        ;; them. Read from `inspect_graph things`, so what the dialog offers is
        ;; what the graph actually holds and not a list assembled here.
        child-thing (first (filter #(= child (:name %)) things))
        parent-thing (first (filter #(= parent (:name %)) things))
        child-fields (vec (:attributes child-thing))
        ;; A templated `:base_url` names its attribute in every row this source
        ;; answers, so the probe needs a value for it here as much as in the
        ;; Thing wizard (§3.5).
        carried (vec (:bound-attributes (first (filter #(= source* (:id %)) sources))))
        in-record? (str/starts-with? (str/trim from) "$")
        placeholders (vec (map first (re-seq #"\{\+?[^{}]*\}" (str from))))
        args (fn [more]
               (merge
                (cond-> {:graph graph :parent (str/trim parent) :child (str/trim child)
                         :kind (:kind chosen)}
                  (seq via) (assoc :via (vec via))
                  (seq (str/trim from)) (assoc :from (str/trim from))
                  (seq (str/trim name*)) (assoc :name (str/trim name*))
                  (seq (str/trim source*)) (assoc :source (str/trim source*))
                  ;; A scalar for the in-record branch (one real value of the
                  ;; PARENT's key), a map when several placeholders or a
                  ;; templated base URL each need one — the two spellings
                  ;; `sample` has in every sentence that reads an api path
                  ;; (need-grammar-v1 §3.2 / §3.5).
                  (and in-record? (seq (str (get sample "parent key" ""))))
                  (assoc :sample (get sample "parent key"))
                  (and (not in-record?) (seq sample)) (assoc :sample sample)
                  (seq (text-list attrs)) (assoc :attributes (text-list attrs))
                  (seq (remove #(str/blank? (str (:param %))) filters))
                  (assoc :filters
                         (into {} (for [{:keys [param attribute op description]} filters
                                        :when (not (str/blank? (str param)))]
                                    [param (cond-> {}
                                             (seq (str attribute)) (assoc :attribute attribute)
                                             (seq (str op)) (assoc :op op)
                                             (seq (str description)) (assoc :description description))]))))
                more))
        run! (fn [more k]
               (set-busy! k) (set-error! nil)
               (api/intent! "add_relation" (args more)
                            {:on-success (fn [r]
                                           (set-busy! nil)
                                           (if more (set-applied! r) (set-plan! r))
                                           (when (and more (seq (:mutations r)))
                                             (on-model-change)))
                             :on-error (fn [e] (set-busy! nil) (set-error! e))}))]
    ($ :div.dossier.relation-dialog
       ($ :h2 "两个东西怎么相关 · How two things relate")
       ($ :p.hint
          "Name the parent (the «one» end) and the child (the «many» end) — the same way round "
          "in all three sentences — then say which one you mean. What gets written is "
          "add_relation's, the same tool an agent calls.")
       (when error ($ findings-view {:error error}))

       ($ :div.wizard-step
          ($ :h3 "1 · The two things")
          ($ field-row
             {:label "parent" :hint "the «one» end — «process group»"
              :children ($ :input {:value parent :aria-label "parent thing" :spell-check false
                                   :list "relation-things" :placeholder "process_group"
                                   :on-change #(do (forget!) (set-parent! (.. % -target -value)))})})
          ($ field-row
             {:label "child" :hint "the «many» end — «processor»"
              :children ($ :input {:value child :aria-label "child thing" :spell-check false
                                   :list "relation-things" :placeholder "processor"
                                   :on-change #(do (forget!) (set-child! (.. % -target -value)))})})
          ($ :datalist {:id "relation-things"}
             (for [t things] ($ :option {:key (:name t) :value (:name t)})))
          (when (and (seq parent) (nil? parent-thing))
            ($ :p.hint "No thing of that name is read back from this graph yet — the plan will say so."))
          ($ :div.relation-kinds
             (for [{:keys [id label says]} relation-choices]
               ($ :label.wizard-check {:key (str id)}
                  ($ :input {:type "radio" :name "relation-kind" :checked (= choice id)
                             :aria-label label
                             :on-change #(do (forget!) (set-choice! id))})
                  ($ :span label)
                  (when (= choice id) ($ :span.field-hint says))))))

       ($ :div.wizard-step
          ($ :h3 "2 · How the children are reached")
          ($ field-row
             {:label "via" :hint "the child's own field pointing at the parent — required over a database source"
              :children
              ($ :div.thing-identity
                 (if (seq child-fields)
                   (for [f child-fields]
                     ($ :label.wizard-check {:key f}
                        ($ :input {:type "checkbox" :checked (boolean (some #{f} via))
                                   :aria-label (str "via " f)
                                   :on-change #(do (forget!)
                                                   (set-via! (fn [vs]
                                                               (if (some #{f} vs)
                                                                 (vec (remove #{f} vs))
                                                                 (conj (vec vs) f)))))})
                        ($ :span.mono f)))
                   ($ draft-input
                      {:value via :value->text #(str/join ", " %) :text->value text-list
                       :aria-label "via fields" :spell-check? false
                       :placeholder "parentGroupId"
                       :on-change #(do (forget!) (set-via! %))})))})
          (when (and (seq via) parent-thing (seq (:identity parent-thing))
                     (not= (count via) (count (:identity parent-thing))))
            ($ :p.hint.warn
               (str "This parent is recognised by " (count (:identity parent-thing))
                    " field(s) — " (str/join ", " (:identity parent-thing))
                    " — so `via` takes one child field per part, in that order. "
                    "The planner refuses a half key rather than pointing at nothing.")))
          (when-not one?
            ($ :<>
               ($ field-row
                  {:label "from"
                   :hint "an endpoint under the parent (/process-groups/{id}/processors), a table name, or $.processors[*] for an array inside the parent's own record"
                   :children ($ :input {:value from :aria-label "children come from"
                                        :spell-check false :placeholder "/process-groups/{id}/processors"
                                        :on-change #(do (forget!) (set-from! (.. % -target -value)))})})
               (when in-record?
                 ($ :p.hint
                    "An array inside the parent's own record: no endpoint of its own is called. "
                    "Give one real value of the PARENT's key below and that record is read once "
                    "to learn the child's fields, so the child needs no add_thing first. "
                    "`via` is refused here — the rows already came out of the parent."))
               ($ sample-map-editor
                  {:placeholders (if in-record? ["parent key"] placeholders)
                   :bound (if in-record? [] carried) :sample sample
                   :on-change #(do (forget!) (set-sample! %))})
               ($ field-row
                  {:label "attributes"
                   :hint "which of the child's fields the collection carries — empty leaves the default"
                   :children ($ :input {:value attrs :aria-label "child attributes"
                                        :spell-check false :placeholder "id, name, state"
                                        :on-change #(do (forget!)
                                                        (set-attrs! (.. % -target -value)))})})))
          ($ field-row
             {:label "source" :hint "omit when the graph names exactly one"
              :children ($ :input {:value source* :aria-label "source id" :spell-check false
                                   :list "relation-sources"
                                   :on-change #(do (forget!) (set-source! (.. % -target -value)))})})
          ($ :datalist {:id "relation-sources"}
             (for [s sources] ($ :option {:key (:id s) :value (:id s)})))
          ($ field-row
             {:label "name" :hint "what to call the new key — defaults to the child's (many) or the parent's (one)"
              :children ($ :input {:value name* :aria-label "relation name" :spell-check false
                                   :on-change #(do (forget!) (set-name! (.. % -target -value)))})})
          ($ filters-editor {:filters filters :on-change #(do (forget!) (set-filters! %))}))

       ($ :div.wizard-step
          ($ :h3 "3 · Read it back, then write it")
          ($ :div.asset-editor-actions
             ($ :button.btn
                {:disabled (or (= :planning busy) (not graph)
                               (str/blank? (str/trim parent)) (str/blank? (str/trim child)))
                 :on-click #(run! nil :planning)}
                (if (= :planning busy) "planning…" "plan"))
             ($ :button.btn {:on-click on-close} "close"))
          ($ plan-confirm
             {:plan plan :applied applied :busy? (= :applying busy)
              :apply-label "写进图 · add the relation"
              :on-apply #(run! {:apply true} :applying)}))
       (when-not graph
         ($ :p.hint.bad "Pick a graph first: a relation is added to one.")))))

;; ---------------------------------------------------------------------------
;; «它还来自另一个来源» — a second source on a thing (need-grammar-v1 A2, §3.3)
;; ---------------------------------------------------------------------------

(defui second-source-panel
  "**A thing's second source** — «X 有属性 a、b，来自 ⟨另一个源⟩» (A2).

   A thing is produced by SEVERAL resolvers; that is Pathom's core ability and
   the grammar has to be able to say it. What comes back is a second by-key
   resolver on the same identity whose `:output` holds only the NEW attributes —
   the key has one producer (§3.3) — and, when this source templates its base
   URL, the key need not appear in the record at all (§3.5).

   The column → attribute mapping with types is what §106.16 asks for here:
   the evidence names the field, the modeller names the attribute."
  [{:keys [graph thing sources on-model-change on-close]}]
  (let [[source* set-source!] (uix/use-state (or (:id (first sources)) ""))
        [from set-from!]      (uix/use-state "")
        [sample set-sample!]  (uix/use-state {})
        [fields set-fields!]  (uix/use-state [])
        [reply set-reply!]    (uix/use-state nil)
        [plan set-plan!]      (uix/use-state nil)
        [applied set-applied!] (uix/use-state nil)
        [busy set-busy!]      (uix/use-state nil)
        [error set-error!]    (uix/use-state nil)
        forget! (fn [] (set-plan! nil) (set-applied! nil))
        picked (first (filter #(= source* (:id %)) sources))
        api? (= "api" (:type picked))
        carried (vec (:bound-attributes picked))
        placeholders (vec (map first (re-seq #"\{\+?[^{}]*\}" (str from))))
        chosen (filterv :include? fields)
        ;; ONE `add_attribute` per field, and that is the sentence: «X 有属性 a,
        ;; 来自 ⟨源⟩» names one field. Saying it again with the SAME `from`
        ;; joins the resolver the first one made rather than starting a third
        ;; (need-grammar-v1 §3.4「一个来源一条 resolver」), so the calls that
        ;; follow land on one lookup. `column` carries the mapping when the
        ;; attribute is not called what the field is (§106.16 items 7.2 / 10.3).
        ;; No `:source`: `add_attribute`'s key set is closed and does not carry
        ;; one — which source `from` names is decided from the graph's own
        ;; members, the same way `add_thing` decides it. The picker above is
        ;; for the EVIDENCE (which upstream to probe or describe), and sending
        ;; it as an argument is a refusal, not a hint.
        args (fn [{:keys [local path nested?]} more]
               (merge (cond-> {:graph graph
                               :thing (:name thing)
                               :from (str/trim from)
                               :name local}
                        (or nested? (not= local path)) (assoc :column path))
                      (when (seq sample) {:sample sample})
                      more))
        ;; The calls run in order and stop at the first refusal: a half-applied
        ;; second source with no word about which field stopped it is exactly
        ;; the silent degradation D2.9 forbids. Every plan's `:summary`,
        ;; `:actions` and `:fill` are concatenated, so what the confirm box
        ;; shows is all of them.
        run! (fn [more k]
               (set-busy! k) (set-error! nil)
               (let [step (fn step [remaining acc]
                            (if-let [f (first remaining)]
                              (api/intent!
                               "add_attribute" (args f more)
                               {:on-success
                                (fn [r]
                                  (let [acc' (-> acc
                                                 (update :summary into (plan-summary-lines r))
                                                 (update :actions into (:actions r))
                                                 (update :fill into (:fill r))
                                                 (update :mutations into (:mutations r))
                                                 (update :findings into (:findings r)))]
                                    (if (some #(= :error (:severity %)) (:findings r))
                                      (do (set-busy! nil)
                                          (if more (set-applied! acc') (set-plan! acc')))
                                      (step (rest remaining) acc'))))
                                :on-error (fn [e] (set-busy! nil) (set-error! e))})
                              (do (set-busy! nil)
                                  (if more (set-applied! acc) (set-plan! acc))
                                  (when (and more (seq (:mutations acc))) (on-model-change)))))]
                 (step chosen {:summary [] :actions [] :fill [] :mutations [] :findings []})))]
    ($ :div.dossier.second-source
       ($ :h2 (str "它还来自另一个来源 · " (:name thing) " also comes from …"))
       ($ :p.hint
          "A second place this same thing's fields are read from. The key is not produced "
          "again — it already has one producer — and the new resolver is looked up by the "
          "very identity this thing already has.")
       (when error ($ findings-view {:error error}))
       ($ :div.wizard-step
          ($ :h3 "1 · Where the extra fields are")
          ($ field-row
             {:label "source" :hint "may be a different one from the thing's first"
              :children ($ :select {:value source* :aria-label "source id"
                                    :on-change #(do (forget!) (set-source! (.. % -target -value)))}
                           (for [s sources]
                             ($ :option {:key (:id s) :value (:id s)} (:id s))))})
          ($ field-row
             {:label (if api? "endpoint" "table")
              :hint (if api? "/processors/{id}/state — the key templated in" "the relation these columns live in")
              :children ($ :input {:value from :aria-label "second source path" :spell-check false
                                   :on-change #(do (forget!) (set-from! (.. % -target -value)))})})
          ($ sample-map-editor
             {:placeholders placeholders :bound carried :sample sample
              :on-change #(do (forget!) (set-sample! %))})
          ($ :div.asset-editor-actions
             ($ :button.btn
                {:disabled (or (= :reading busy) (str/blank? (str/trim from)))
                 :on-click
                 (fn []
                   (set-busy! :reading) (set-error! nil) (forget!)
                   (if api?
                     (let [called (reduce (fn [p ph] (str/replace p ph (str (get sample (placeholder-name ph) ""))))
                                          (str/trim from) placeholders)]
                       (api/api-probe!
                        {:source source* :path called :method "GET"}
                        {:on-success (fn [r]
                                       (set-busy! nil) (set-reply! r)
                                       (set-fields! (mapv #(assoc % :include? true)
                                                          (sampled-fields
                                                           (sampled-records r (:data-path r))))))
                         :on-error (fn [e] (set-busy! nil) (set-error! e))}))
                     (api/source-schema!
                      {:source source* :names [(str/trim from)] :keys? true}
                      {:on-success (fn [r]
                                     (set-busy! nil)
                                     (set-fields!
                                      (mapv (fn [c] {:path (:name c) :local (:name c)
                                                     :type (some-> (:class c) name)
                                                     :include? true})
                                            (:columns (first (:relations r))))))
                       :on-error (fn [e] (set-busy! nil) (set-error! e))})))}
                (if (= :reading busy) "reading…" (if api? "试一次 · call it once" "read its columns"))))
          (when reply
            ($ :p.sub ($ :span.tag (str "HTTP " (:status reply))))))

       ($ :div.wizard-step
          ($ :h3 "2 · Which fields, and what to call them")
          ($ :p.hint
             (str "The key is not among them: " (str/join ", " (:identity thing))
                  " already has a producer, and a second one would make this graph "
                  "un-cuttable. Rename a field by editing the attribute column."))
          (if (empty? fields)
            ($ :p.hint.bad "Nothing read yet.")
            ($ :table.api-fields
               ($ :thead ($ :tr ($ :th "include") ($ :th "field") ($ :th "attribute") ($ :th "type")))
               ($ :tbody
                  (for [{:keys [path local include? type nested?]} fields]
                    ($ :tr {:key path}
                       ($ :td ($ :input {:type "checkbox" :checked (boolean include?)
                                         :aria-label (str "include " path)
                                         :on-change #(do (forget!)
                                                         (set-fields!
                                                          (fn [fs] (mapv (fn [f] (cond-> f (= path (:path f))
                                                                                        (update :include? not)))
                                                                         fs))))}))
                       ($ :td ($ :span.mono path) (when nested? ($ :span.tag "nested")))
                       ($ :td ($ :input {:value local :aria-label (str "attribute for " path)
                                         :spell-check false
                                         :on-change #(let [v (.. % -target -value)]
                                                       (forget!)
                                                       (set-fields!
                                                        (fn [fs] (mapv (fn [f] (cond-> f (= path (:path f))
                                                                                      (assoc :local v)))
                                                                       fs))))}))
                       ($ :td (if api? ($ :span.tag.warn "inferred") ($ :span.tag (str (or type "—")))))))))))

       ($ :div.wizard-step
          ($ :h3 "3 · Read it back, then write it")
          ($ :div.asset-editor-actions
             ($ :button.btn
                {:disabled (or (= :planning busy) (not graph) (empty? chosen)
                               (str/blank? (str/trim from)))
                 :on-click #(run! nil :planning)}
                (if (= :planning busy) "planning…" "plan"))
             ($ :button.btn {:on-click on-close} "close"))
          ($ plan-confirm
             {:plan plan :applied applied :busy? (= :applying busy)
              :apply-label "写进图 · add these attributes"
              :on-apply #(run! {:apply true} :applying)})))))

;; ---------------------------------------------------------------------------
;; «有个概念叫 T» / «X 的数字口径 m» — concepts and metrics (C1–C4, A4)
;; ---------------------------------------------------------------------------

(defui concept-form
  "**«有个概念叫 T»** — C1 through C4 in one form (need-grammar-v1 C).

   The values table is C3 and the per-attribute `contexts` map is C4, which is
   required when one namespace binds the same concept more than once. Binding
   is never generated from a name (D2.26): C2 is the modeller ticking, and this
   form only carries the tick."
  [{:keys [graph need on-model-change on-close]}]
  (let [[concept set-concept!]  (uix/use-state "")
        [label* set-label!]     (uix/use-state "")
        [definition set-def!]   (uix/use-state "")
        [synonyms set-syn!]     (uix/use-state "")
        [attrs set-attrs!]      (uix/use-state "")
        [values set-values!]    (uix/use-state [])
        [contexts set-contexts!] (uix/use-state [])
        [plan set-plan!]        (uix/use-state nil)
        [applied set-applied!]  (uix/use-state nil)
        [busy set-busy!]        (uix/use-state nil)
        [error set-error!]      (uix/use-state nil)
        forget! (fn [] (set-plan! nil) (set-applied! nil))
        every-attribute (vec (sort (mapcat (fn [t] (map #(str (:name t) "/" %) (:attributes t)))
                                           (:things need))))
        args (fn [more]
               (merge
                (cond-> {:graph graph :concept (str/trim concept)
                         :attributes (text-list attrs)}
                  ;; `definition` is the concept's WORDS as one map — label,
                  ;; description, synonyms — not three parameters. Required the
                  ;; first time the concept is named; the same words again are
                  ;; idempotent and different ones are refused (C1).
                  (or (seq (str/trim definition)) (seq (text-list synonyms)))
                  (assoc :definition (cond-> {}
                                       (seq (str/trim definition))
                                       (assoc :description (str/trim definition))
                                       (seq (str/trim label*)) (assoc :label (str/trim label*))
                                       (seq (text-list synonyms))
                                       (assoc :synonyms (text-list synonyms))))
                  (seq (remove #(str/blank? (str (:code %))) values))
                  ;; `{"D": {"label": "dev"}}` — the shape the contract states;
                  ;; a bare string would be a second spelling of the same fact.
                  (assoc :values (into {} (for [{:keys [code label]} values
                                                :when (not (str/blank? (str code)))]
                                            [code {:label label}])))
                  (seq (remove #(str/blank? (str (:attribute %))) contexts))
                  (assoc :contexts (into {} (for [{:keys [attribute context]} contexts
                                                  :when (not (str/blank? (str attribute)))]
                                              [attribute context]))))
                more))
        run! (fn [more k]
               (set-busy! k) (set-error! nil)
               (api/intent! "define_concept" (args more)
                            {:on-success (fn [r]
                                           (set-busy! nil)
                                           (if more (set-applied! r) (set-plan! r))
                                           (when (and more (seq (:mutations r))) (on-model-change)))
                             :on-error (fn [e] (set-busy! nil) (set-error! e))}))]
    ($ :div.dossier.concept-form
       ($ :h2 "有个概念叫 … · There is a concept called …")
       ($ :p.hint
          "A meaning several attributes share — «客户编号» in a hundred and thirty-one tables. "
          "Binding an attribute takes the local label, description, synonyms, format and values "
          "OFF it: a meaning has one source of truth (D2.26).")
       (when error ($ findings-view {:error error}))
       ($ :div.wizard-step
          ($ :h3 "1 · The concept")
          ($ field-row {:label "concept" :hint "«customer_no», or 域/T"
                        :children ($ :input {:value concept :aria-label "concept id" :spell-check false
                                             :on-change #(do (forget!) (set-concept! (.. % -target -value)))})})
          ($ field-row {:label "label" :hint "what a person calls it"
                        :children ($ :input {:value label* :aria-label "concept label"
                                             :on-change #(do (forget!) (set-label! (.. % -target -value)))})})
          ($ field-row {:label "description" :hint "said once, in words — repeating it verbatim is idempotent"
                        :children ($ :input {:value definition :aria-label "definition"
                                             :on-change #(do (forget!) (set-def! (.. % -target -value)))})})
          ($ field-row {:label "synonyms" :hint "comma separated"
                        :children ($ :input {:value synonyms :aria-label "synonyms"
                                             :on-change #(do (forget!) (set-syn! (.. % -target -value)))})}))
       ($ :div.wizard-step
          ($ :h3 "2 · Which attributes mean it (C2)")
          ($ field-row {:label "attributes" :hint "comma separated, qualified — never generated from a name"
                        :children ($ :input {:value attrs :aria-label "bound attributes" :spell-check false
                                             :list "concept-attributes"
                                             :on-change #(do (forget!) (set-attrs! (.. % -target -value)))})})
          ($ :datalist {:id "concept-attributes"}
             (for [a every-attribute] ($ :option {:key a :value a})))
          ($ :p.hint "Where one namespace binds this concept twice, each of those attributes needs a context below (C4).")
          (for [[i {:keys [attribute context]}] (map-indexed vector contexts)]
            ($ :div.thing-filter {:key i}
               ($ :input {:value (or attribute "") :aria-label "context attribute" :spell-check false
                          :placeholder "shop.order/buyer_no"
                          :on-change #(do (forget!)
                                          (set-contexts! (assoc-in (vec contexts) [i :attribute]
                                                                   (.. % -target -value))))})
               ($ :input {:value (or context "") :aria-label "context" :placeholder "购买方"
                          :on-change #(do (forget!)
                                          (set-contexts! (assoc-in (vec contexts) [i :context]
                                                                   (.. % -target -value))))})
               ($ :button.btn.tiny
                  {:on-click #(set-contexts! (vec (concat (subvec (vec contexts) 0 i)
                                                          (subvec (vec contexts) (inc i)))))}
                  "remove")))
          ($ :button.btn.tiny {:on-click #(set-contexts! (conj (vec contexts) {:attribute "" :context ""}))}
             "add a context"))
       ($ :div.wizard-step
          ($ :h3 "3 · Its values (C3)")
          ($ :p.hint "The codes this concept takes. A bound attribute's type must agree with the key's (D2.27).")
          (for [[i {:keys [code label]}] (map-indexed vector values)]
            ($ :div.thing-filter {:key i}
               ($ :input {:value (or code "") :aria-label "code" :placeholder "A" :spell-check false
                          :on-change #(do (forget!)
                                          (set-values! (assoc-in (vec values) [i :code] (.. % -target -value))))})
               ($ :input {:value (or label "") :aria-label "label" :placeholder "在售"
                          :on-change #(do (forget!)
                                          (set-values! (assoc-in (vec values) [i :label] (.. % -target -value))))})
               ($ :button.btn.tiny
                  {:on-click #(set-values! (vec (concat (subvec (vec values) 0 i)
                                                        (subvec (vec values) (inc i)))))}
                  "remove")))
          ($ :button.btn.tiny {:on-click #(set-values! (conj (vec values) {:code "" :label ""}))}
             "add a value"))
       ($ :div.wizard-step
          ($ :h3 "4 · Read it back, then write it")
          ($ :div.asset-editor-actions
             ($ :button.btn
                {:disabled (or (= :planning busy) (not graph) (str/blank? (str/trim concept))
                               ;; `attributes` is required: a concept nothing
                               ;; means is not one of the sentences (C2).
                               (empty? (text-list attrs)))
                 :on-click #(run! nil :planning)}
                (if (= :planning busy) "planning…" "plan"))
             ($ :button.btn {:on-click on-close} "close"))
          ($ plan-confirm {:plan plan :applied applied :busy? (= :applying busy)
                           :apply-label "写进图 · define it"
                           :on-apply #(run! {:apply true} :applying)})))))

(defui metric-form
  "**«X 的数字口径 m 是 ⟨表达式⟩»** — A4 (D2.21).

   `:contract` is written by the tool and never asked for here: which execution
   contract a metric compiles against is a platform fact, not something a person
   says (need-grammar-v1 §3, A4)."
  [{:keys [graph need on-model-change on-close]}]
  (let [[thing set-thing!]   (uix/use-state "")
        [name* set-name!]    (uix/use-state "")
        [expression set-expr!] (uix/use-state "")
        [dimensions set-dims!] (uix/use-state "")
        [label set-label!]   (uix/use-state "")
        [description set-desc!] (uix/use-state "")
        [plan set-plan!]     (uix/use-state nil)
        [applied set-applied!] (uix/use-state nil)
        [busy set-busy!]     (uix/use-state nil)
        [error set-error!]   (uix/use-state nil)
        forget! (fn [] (set-plan! nil) (set-applied! nil))
        picked (first (filter #(= thing (:name %)) (:things need)))
        args (fn [more]
               (merge
                (cond-> {:graph graph :thing (str/trim thing) :name (str/trim name*)
                         :expression (str/trim expression)}
                  (seq (text-list dimensions)) (assoc :dimensions (text-list dimensions))
                  (seq (str/trim label)) (assoc :label (str/trim label))
                  (seq (str/trim description)) (assoc :description (str/trim description)))
                more))
        run! (fn [more k]
               (set-busy! k) (set-error! nil)
               (api/intent! "add_metric" (args more)
                            {:on-success (fn [r]
                                           (set-busy! nil)
                                           (if more (set-applied! r) (set-plan! r))
                                           (when (and more (seq (:mutations r))) (on-model-change)))
                             :on-error (fn [e] (set-busy! nil) (set-error! e))}))]
    ($ :div.dossier.metric-form
       ($ :h2 "数字口径 · A metric on a thing")
       ($ :p.hint
          "A number with a definition — «open orders» is COUNT(*) WHERE state = 'open', on order. "
          "The definition is kept; how it is executed is not asked for here.")
       (when error ($ findings-view {:error error}))
       ($ :div.wizard-step
          ($ :h3 "1 · Whose number")
          ($ field-row {:label "thing" :hint "the thing this counts"
                        :children ($ :input {:value thing :aria-label "metric thing" :spell-check false
                                             :list "metric-things"
                                             :on-change #(do (forget!) (set-thing! (.. % -target -value)))})})
          ($ :datalist {:id "metric-things"}
             (for [t (:things need)] ($ :option {:key (:name t) :value (:name t)})))
          ($ field-row {:label "name" :hint "«open_orders»"
                        :children ($ :input {:value name* :aria-label "metric name" :spell-check false
                                             :on-change #(do (forget!) (set-name! (.. % -target -value)))})})
          ($ field-row {:label "expression" :hint "the definition itself"
                        :children ($ :input {:value expression :aria-label "expression" :spell-check false
                                             :placeholder "count(*) filter (where state = 'open')"
                                             :on-change #(do (forget!) (set-expr! (.. % -target -value)))})})
          ($ field-row {:label "dimensions" :hint "comma separated — registered attributes of this thing"
                        :children ($ :input {:value dimensions :aria-label "dimensions" :spell-check false
                                             :list "metric-dimensions"
                                             :on-change #(do (forget!) (set-dims! (.. % -target -value)))})})
          ($ :datalist {:id "metric-dimensions"}
             (for [a (:attributes picked)] ($ :option {:key a :value a})))
          ($ field-row {:label "label"
                        :children ($ :input {:value label :aria-label "metric label"
                                             :on-change #(do (forget!) (set-label! (.. % -target -value)))})})
          ($ field-row {:label "description"
                        :children ($ :input {:value description :aria-label "metric description"
                                             :on-change #(do (forget!) (set-desc! (.. % -target -value)))})}))
       ($ :div.wizard-step
          ($ :h3 "2 · Read it back, then write it")
          ($ :div.asset-editor-actions
             ($ :button.btn
                {:disabled (or (= :planning busy) (not graph)
                               (str/blank? (str/trim thing)) (str/blank? (str/trim name*))
                               (str/blank? (str/trim expression)))
                 :on-click #(run! nil :planning)}
                (if (= :planning busy) "planning…" "plan"))
             ($ :button.btn {:on-click on-close} "close"))
          ($ plan-confirm {:plan plan :applied applied :busy? (= :applying busy)
                           :apply-label "写进图 · add the metric"
                           :on-apply #(run! {:apply true} :applying)})))))

;; ---------------------------------------------------------------------------
;; The `things` view — the graph read back in the four words (§106.5, §106.16)
;; ---------------------------------------------------------------------------

(defui things-view
  "Browse a large Graph without rendering every Entity dossier at once."
  [{:keys [need graph semantic? semantic-revision on-open-form on-open-definition initial-section]}]
  (let [[section set-section!] (uix/use-state (or initial-section :entities))
        [search set-search!] (uix/use-state "")
        [selected-id set-selected-id!] (uix/use-state nil)
        [limit set-limit!] (uix/use-state 50)
        needle (str/lower-case (str/trim search))
        matches? (fn [x] (or (str/blank? needle)
                             (str/includes? (str/lower-case (pr-str x)) needle)))
        things (->> (:things need) (filter matches?) (sort-by :name) vec)
        selected (or (some #(when (= selected-id (:name %)) %) things) (first things))
        fragments (vec (:fragments need))
        series (group-by :parent (filter #(= :series (:kind %)) fragments))
        loose (filterv matches? (remove #(= :series (:kind %)) fragments))]
    (if (nil? need)
      ($ :p.hint "Loading Graph…")
      ($ :div.need-view
         ($ :div.need-toolbar
            ($ :div.need-sections {:aria-label "Graph content"}
               (for [[k label n] [[:entities "Entities" (count (:things need))]
                                  [:concepts "Concepts" (count (:concepts need))]
                                  [:findings "Validation" (count (:findings need))]]]
                 ($ :button {:key (name k) :type "button" :aria-pressed (= section k)
                             :class (when (= section k) "on")
                             :on-click #(do (set-section! k) (set-limit! 50))}
                    label ($ :span.need-count n))))
            ($ :input.need-search {:type "search" :aria-label "Search Graph content"
                                   :placeholder "Search by name, attribute, relationship, or description"
                                   :value search
                                   :on-change #(do (set-search! (.. % -target -value))
                                                   (set-limit! 50))}))
         ($ :p.need-intro
            (if semantic?
              (str "Showing saved Graph Definition revision " semantic-revision
                   ". Select an Entity to inspect it; edit the authoritative EDN in Definition.")
              "Showing the legacy Graph projection. Select an Entity to inspect its identity, attributes, relationships, and source actions."))
         (case section
           :entities
           ($ :div.entity-browser
              ($ :div.entity-list {:role "listbox" :aria-label "Entities"}
                 ($ :div.entity-list-head ($ :strong "Entities")
                    ($ :span.count (str (count things) " matches")))
                 (if (empty? things)
                   ($ :p.hint "No Entities match this search.")
                   ($ :<>
                      (for [t (take limit things)]
                        ($ :button.entity-list-row
                           {:key (:name t) :type "button" :role "option"
                            :aria-selected (= (:name selected) (:name t))
                            :class (when (= (:name selected) (:name t)) "on")
                            :on-click #(set-selected-id! (:name t))}
                           ($ :span.mono (:name t))
                           ($ :span.need-meta
                              (str (count (:attributes t)) " attributes · "
                                   (count (:relations t)) " relationships"))))
                      (when (> (count things) limit)
                        ($ :button.btn.entity-more {:type "button" :on-click #(set-limit! (+ limit 50))}
                           (str "Show 50 more · " (- (count things) limit) " remaining"))))))
              ($ :div.entity-detail
                 (if-not selected
                   ($ :p.hint "Select an Entity.")
                   ($ :<>
                      ($ :div.entity-detail-head
                         ($ :div
                            ($ :h3.mono (:name selected))
                            (when (seq (:label selected)) ($ :p.need-meta (:label selected))))
                         (when (:list? selected) ($ :span.tag "Collection"))
                         (when-not (:identity-declared? selected) ($ :span.tag.warn "Identity inferred")))
                      (when (seq (:description selected))
                        ($ :p.hint (:description selected)))
                      ($ :h4 "Identity")
                      ($ :p.mono (if (seq (:identity selected))
                                   (str/join " + " (:identity selected)) "Not declared"))
                      ($ :h4 (str "Attributes · " (count (:attributes selected))))
                      ($ :ul.entity-attribute-list
                         (for [attribute (:attributes selected)]
                           (let [definition (get (:attribute-definitions selected) attribute)]
                             ($ :li {:key attribute}
                                ($ :span.mono attribute)
                                (when (seq (:attribute/label definition))
                                  ($ :span.need-meta (:attribute/label definition)))
                                (when (seq (:attribute/description definition))
                                  ($ :span.via (:attribute/description definition)))))))
                      ($ :h4 (str "Relationships · " (count (:relations selected))))
                      (if (empty? (:relations selected))
                        ($ :p.hint "No Relationships are declared for this Entity.")
                        ($ :div.entity-relations
                           (for [relationship (:relations selected)]
                             ($ :div.need-relation {:key (:name relationship)}
                                ($ :span.mono (:name relationship))
                                ($ :span.tag (if (= :one (:kind relationship)) "Has one" "Has many"))
                                ($ :button.linkish {:type "button" :title "Open the related Entity"
                                                   :on-click #(set-selected-id! (:target relationship))}
                                   (str (:target relationship)))
                                (for [item (get series (:name relationship))]
                                  ($ :div.need-thing {:key (:name item)}
                                     ($ :span.tag "Series") ($ :span.mono (:name item))))))))
                      ($ :div.need-repair
                         (if semantic?
                           ($ :button.btn.tiny {:on-click on-open-definition} "Edit Definition")
                           ($ :<>
                              ($ :button.btn.tiny {:on-click #(on-open-form {:form :second-source :thing selected})}
                                 "Add another Source")
                              ($ :button.btn.tiny {:on-click #(on-open-form {:form :relation :parent (:name selected)})}
                                 "Add Relationship")))))))
              (when (seq loose)
                ($ :div.entity-fragments
                   ($ :h4 (str "Unmodelled fragments · " (count loose)))
                   (for [fragment (take 20 loose)]
                     ($ :div.need-thing {:key (:name fragment)}
                        ($ :span.mono (:name fragment))
                        ($ :span.tag.warn (name (or (:kind fragment) :fragment)))
                        ($ :span.via (:why fragment)))))))
           :concepts
           ($ :div.need-column.single
              ($ :h4 (str "Concepts · " (count (filter matches? (:concepts need)))))
              (if-let [concepts (seq (filter matches? (:concepts need)))]
                (for [concept concepts]
                  ($ :div.need-thing {:key (str (:concept concept))}
                     ($ :span.mono (str (:concept concept)))
                     ($ :span.via (str (get-in concept [:definition :label]) " · "
                                       (count (:attributes concept)) " bound attributes"))))
                ($ :p.hint "No Concepts match this search."))
              ($ :div.need-repair
                 (if semantic?
                   ($ :button.btn.tiny {:on-click on-open-definition} "Edit Definition")
                   ($ :<>
                      ($ :button.btn.tiny {:on-click #(on-open-form {:form :concept})} "Add Concept")
                      ($ :button.btn.tiny {:on-click #(on-open-form {:form :metric})} "Add Metric")))))
           :findings
           ($ :div.need-column.single
              ($ :h4 (str "Validation results · " (count (filter matches? (:findings need)))))
              (if-let [findings (seq (filter matches? (:findings need)))]
                (for [[i finding] (map-indexed vector findings)]
                  ($ :div.need-thing {:key i}
                     ($ :span.code (str (:code finding)))
                     ($ :span.via (str (or (:message finding) "")))
                     (when-let [repair (and (not semantic?) (:repair finding))]
                       ($ :div.need-repair
                          ($ :button.btn.tiny
                             {:on-click #(on-open-form {:form :repair :repair repair :finding finding})}
                             (str "Repair with " (or (:tool repair) "recommended action")))
                          ($ :span.mono (pr-str (dissoc repair :tool)))))))
                ($ :p.hint "No validation issues match this search.")))
           nil)))))

(defui add-pool-objects-panel
  "**《Add pool objects to this graph》** (doc 106 §106.12 item 13.4).

   A recipe is a frozen object list (D3.19), so objects the pool grew after a
   graph was made never join it by themselves. The wizard files what it writes,
   but until this panel a modeller who created an empty graph had no GUI path
   at all to put already-generated objects into it — MCP had
   `change_graph_members`, the browser had nothing.

   Thin on purpose. It reads the recipe tick-list
   (`/api/studio/graphs/candidates`, which already answers 《what does the pool
   hold that this recipe does not》), narrows it by a namespace prefix, and
   posts the ids to `change_graph_members` over `/api/studio/intent` — the SAME
   planner the MCP door dispatches (§106.16). Nothing here decides what a
   member is or which group it belongs in.

   Links are not offered: `change_graph_members` names objects the way a person
   says them, and a link identity is a pair of attributes with no such name.
   They ride into a recipe with the run that creates them, or through the
   recipe editor."
  [{:keys [graph on-model-change on-close]}]
  (let [[rows set-rows!] (uix/use-state nil)
        [prefix set-prefix!] (uix/use-state "")
        [plan set-plan!] (uix/use-state nil)
        [applied set-applied!] (uix/use-state nil)
        [busy set-busy!] (uix/use-state nil)
        [error set-error!] (uix/use-state nil)]
    (uix/use-effect
     (fn []
       (api/recipe-candidates!
        graph
        {:on-success
         (fn [r]
           (set-rows!
            (vec (for [kind [:namespaces :attributes :resolvers :mutations
                             :metrics :terms :units]
                       row (get (:groups r) kind)
                       :when (not (:in? row))]
                   ;; The id travels printed (":cis.a/id", "a_by_id");
                   ;; `change_graph_members` reads a name, so the quotes a
                   ;; printed string carries come off.
                   {:kind kind
                    :id (let [id (:id row)]
                          (if (str/starts-with? id "\"")
                            (subs id 1 (dec (count id)))
                            id))}))))
         :on-error (fn [e] (set-error! e) (set-rows! []))})
       js/undefined)
     [graph])
    (let [picked (when (seq (str/trim prefix))
                   (filterv #(str/starts-with? (str/replace (:id %) #"^:" "")
                                               (str/trim prefix))
                            rows))
          run! (fn [more k]
                 (set-busy! k) (set-error! nil)
                 (api/intent!
                  "change_graph_members"
                  (merge {:graph graph :add (mapv :id picked)} more)
                  {:on-success (fn [r]
                                 (set-busy! nil)
                                 (if more
                                   (do (set-applied! r)
                                       (when (and on-model-change
                                                  (not (some #(= :error (:severity %))
                                                             (:findings r))))
                                         (on-model-change)))
                                   (set-plan! r)))
                   :on-error (fn [e] (set-busy! nil) (set-error! e))}))]
      ($ :div.dossier
         ($ :h2 "把池里对象加进这张图 · Add pool objects to this graph")
         ($ :p.hint
            (str "Objects this workspace holds that this graph's recipe does not name. "
                 "Nothing is created and nothing is deleted — this edits the list (D3.19)."))
         (cond
           (nil? rows) ($ :p.hint "reading the pool …")
           (empty? rows) ($ :p.hint "this graph's recipe already names everything in the pool")
           :else
           ($ :<>
              ($ field-row
                 {:label "namespace prefix"
                  :hint (str (count rows) " object" (when (not= 1 (count rows)) "s")
                             " not in this recipe")
                  :children ($ :input {:value prefix :spell-check false
                                       :aria-label "namespace prefix"
                                       :placeholder "cis.demo"
                                       :on-change (fn [e]
                                                    (set-plan! nil) (set-applied! nil)
                                                    (set-prefix! (.. e -target -value)))})})
              (if (empty? picked)
                ($ :p.hint "type a prefix to pick the objects under it")
                ($ :<>
                   ($ :p.hint (str (count picked) " object"
                                   (when (not= 1 (count picked)) "s") " match"))
                   ($ :ul.selector-picks
                      (for [o (take 50 picked)]
                        ($ :li {:key (str (name (:kind o)) "/" (:id o))}
                           ($ :span.mono (:id o))
                           ($ :span.tag (name (:kind o))))))
                   (when (> (count picked) 50)
                     ($ :p.hint (str "… and " (- (count picked) 50) " more")))))))
         ($ :div.asset-editor-actions
            ($ :button.btn
               {:disabled (or (some? busy) (empty? picked))
                :on-click #(run! nil :planning)}
               (if (= :planning busy) "planning…" "plan"))
            ($ :button.btn {:on-click on-close} "close"))
         ($ plan-confirm {:plan plan :applied applied :busy? (= :applying busy)
                          :apply-label "写进菜谱 · apply"
                          :on-apply #(run! {:apply true} :applying)})
         (when error ($ findings-view {:error error}))))))

(defui repair-runner
  "One finding's `:repair`, planned and applied through the tool it names.

   The arguments are the SERVER's — `impl.repair` builds them next to the check
   that produced the finding (8.4.21 裁定十五 ①「一个形状，一个产生处」) — so
   this shows them, plans, and applies. It edits nothing: a repair a person
   wants to change is a sentence they say themselves in the form above."
  [{:keys [graph repair on-model-change on-close]}]
  (let [[plan set-plan!] (uix/use-state nil)
        [applied set-applied!] (uix/use-state nil)
        [busy set-busy!] (uix/use-state nil)
        [error set-error!] (uix/use-state nil)
        ;; `{:tool … :args {…} :then [{:tool … :args {…}} …]}` — the shape
        ;; `impl.repair` states. `:then` is part of the repair (a relation moved
        ;; off the wrong namespace has to have the old one removed), so it is
        ;; run in order and stops at the first refusal rather than leaving half
        ;; a repair with nothing said about it.
        steps (into [{:tool (:tool repair) :args (:args repair)}] (:then repair))
        run! (fn [more k]
               (set-busy! k) (set-error! nil)
               (let [step (fn step [remaining acc]
                            (if-let [{:keys [tool args]} (first remaining)]
                              (api/intent!
                               (str tool) (merge {:graph graph} args more)
                               {:on-success
                                (fn [r]
                                  (let [acc' (-> acc
                                                 (update :summary into (plan-summary-lines r))
                                                 (update :actions into (:actions r))
                                                 (update :fill into (:fill r))
                                                 (update :mutations into (:mutations r))
                                                 (update :findings into (:findings r)))]
                                    (if (some #(= :error (:severity %)) (:findings r))
                                      (do (set-busy! nil)
                                          (if more (set-applied! acc') (set-plan! acc')))
                                      (step (rest remaining) acc'))))
                                :on-error (fn [e] (set-busy! nil) (set-error! e))})
                              (do (set-busy! nil)
                                  (if more (set-applied! acc) (set-plan! acc))
                                  (when (and more (seq (:mutations acc))) (on-model-change)))))]
                 (step steps {:summary [] :actions [] :fill [] :mutations [] :findings []})))]
    ($ :<>
       ($ :p.sub (for [{:keys [tool]} steps] ($ :span.tag {:key (str tool)} (str tool))))
       ($ editor/edn-view {:text (pr-str steps)})
       (when error ($ findings-view {:error error}))
       ($ :div.asset-editor-actions
          ($ :button.btn {:disabled (or (= :planning busy) (str/blank? (str (:tool repair))))
                          :on-click #(run! nil :planning)}
             (if (= :planning busy) "planning…" "plan"))
          ($ :button.btn {:on-click on-close} "close"))
       ($ plan-confirm {:plan plan :applied applied :busy? (= :applying busy)
                        :apply-label "写进图 · apply the repair"
                        :on-apply #(run! {:apply true} :applying)}))))

(defui graph-things-tab
  "**The `things` view on the graph page** (doc 106 §106.16), with the forms the
   four words open.

   The columns are `need-view`'s answer, whole. The buttons under them are the
   twelve sentences: a thing offers «另一个来源» and «有很多 …», the concept
   column offers C1 and A4, and a finding's `:repair` — which the SERVER
   generates (`impl.repair`, 8.4.21 裁定十五 ①) — opens the form it names,
   pre-filled from it, so «this reads back as a fragment» and «here is how to
   fix it» are one click apart rather than two screens."
  [{:keys [graph can-edit? on-model-change on-open-definition]}]
  (let [[need reload-need!] (use-need-view graph)
        [semantic-state set-semantic-state!] (uix/use-state {:loading? true})
        [sources set-sources!] (uix/use-state [])
        [form set-form!] (uix/use-state nil)
        form-ref (uix/use-ref nil)
        changed! (fn [] (reload-need!) (when on-model-change (on-model-change)))]
    (uix/use-effect
     (fn []
       (when-let [node (and form @form-ref)] (.focus node))
       js/undefined)
     [form])
    (uix/use-effect
     (fn []
       (set-semantic-state! {:loading? true})
       (api/intent! "semantic_draft" {:graph graph :action :read}
                    {:on-success
                     (fn [response]
                       (if (pos? (or (:revision response) 0))
                         (set-semantic-state!
                          {:loading? false
                           :revision (:revision response)
                           :need (semantic-ui/document->need (:document response))})
                         (set-semantic-state! {:loading? false})))
                     :on-error #(set-semantic-state! {:loading? false :error %})})
       js/undefined)
     [graph])
    (uix/use-effect
     (fn []
       (api/sources! {:on-success #(set-sources! (vec (:sources %)))
                      :on-error (fn [_] (set-sources! []))})
       js/undefined)
     [])
    (let [semantic? (some? (:need semantic-state))
          displayed-need (or (:need semantic-state) need)]
    ($ :<>
       (when-not form
        ($ :<>
       ($ things-view
          {:need (when-not (:loading? semantic-state) displayed-need)
           :graph graph :semantic? semantic? :semantic-revision (:revision semantic-state)
           :on-open-definition on-open-definition
           :on-open-form (fn [f] (when can-edit? (set-form! f)))})
       (when (:error semantic-state)
         ($ :p.hint.bad "Definition discovery failed; showing the legacy projection."))))
       (when (and form can-edit? (not semantic?))
         ($ :div.need-form {:ref form-ref :tab-index -1}
            ($ :button.btn {:on-click #(set-form! nil)} "← Back to Entities & Relationships")
            (case (:form form)
              :second-source
              ($ second-source-panel {:key (str "2nd-" (:name (:thing form)))
                                      :graph graph :thing (:thing form) :sources sources
                                      :on-model-change changed!
                                      :on-close #(set-form! nil)})
              :relation
              ($ relation-dialog {:key (str "rel-" (:parent form))
                                  :graph graph :sources sources
                                  :initial-parent (:parent form)
                                  :on-model-change changed!
                                  :on-close #(set-form! nil)})
              :concept
              ($ concept-form {:graph graph :need displayed-need :on-model-change changed!
                               :on-close #(set-form! nil)})
              :metric
              ($ metric-form {:graph graph :need displayed-need :on-model-change changed!
                              :on-close #(set-form! nil)})
              :add-pool-objects
              ($ add-pool-objects-panel {:graph graph
                                         :on-model-change changed!
                                         :on-close #(set-form! nil)})
              :repair
              ;; A repair names a TOOL and its arguments. Rather than a second
              ;; table mapping tools to forms, the two the `things` view can
              ;; raise are drawn straight: `describe_objects` for «declare
              ;; :identity? here» and `add_relation` for a relation hanging on
              ;; the wrong namespace. Anything else is shown as what it is —
              ;; the arguments an agent would send — because inventing a form
              ;; for a repair this view has never produced would be a guess.
              ($ :div.dossier
                 ($ :h2 "修 · Repair")
                 ($ :p.hint (:message (:finding form)))
                 ($ repair-runner {:graph graph :repair (:repair form)
                                   :on-model-change changed!
                                   :on-close #(set-form! nil)}))
              nil)))))))


(defui link-dossier
  "One declared join between two attributes.

   A link says these two columns mean the same thing; it is not a resolver and
   does not fetch anything. `:bi` is the default and is never written, so a
   link showing `bi` here is showing the default, not a choice someone made."
  [{:keys [link catalog on-select can-edit? on-model-change schema graph]}]
  (let [[editing? set-editing!] (uix/use-state false)]
    ($ :div.dossier
       ($ :h2 "Link")
       ($ :p.sub
          ($ :span.tag (name (or (:direction link) :bi)))
          (when-let [c (:cardinality link)] ($ :span.tag (name c)))
          (when (and can-edit? (not editing?))
            ($ :button.btn {:on-click #(set-editing! true)} "edit")))
       (when editing?
         ;; The pool addresses a link by a pair of attribute **keywords**
         ;; (D2.3); the catalog carries its endpoints as display strings. Sent
         ;; as strings the lookup misses and the editor reports the link as
         ;; missing — which it is not.
         ($ :<>
            ($ asset-editor {:type :link
                             :id [(text-kw (:from link)) (text-kw (:to link))]
                             :schema schema :catalog catalog :graph graph
                             :on-close #(set-editing! false)
                             :on-saved (fn [_] (set-editing! false) (on-model-change))})
            ($ remove-object {:request (fl/object-ref :link [(text-kw (:from link))
                                                             (text-kw (:to link))])
                              :label "remove this link"
                              :on-removed (fn [] (set-editing! false)
                                            (on-select nil) (on-model-change))})))
       ($ :dl.spec
          ($ :dt "from")
          ($ :dd ($ :button.link {:on-click #(on-select {:kind :attribute :id (:from link)})}
                    (:from link)))
          ($ :dt "to")
          ($ :dd ($ :button.link {:on-click #(on-select {:kind :attribute :id (:to link)})}
                    (:to link))))
       (when-let [d (:description link)] ($ :p.hint d))
       (when-let [c (:condition link)] ($ :p.hint ($ :span.mono c))))))

(defui link-create-panel
  "Declare a join between two attributes, as EDN (owner ruling 2026-09-05).

   The two endpoint pickers are gone with every other field form. The id **is**
   the endpoint pair (D2.3), so the id box takes the pair — `fl/typed-id` reads
   it as EDN and says so here when the shape is wrong — and the entry names the
   same two attributes, because a link entry carries `:from` and `:to` in its
   own right. The seed shows both, so what has to agree is on screen together
   rather than split across a picker and a hidden template."
  [{:keys [catalog schema graph on-select on-model-change on-close]}]
  ($ :div.dossier
     ($ :h2 "New link")
     ($ :p.hint "A link declares that two attributes mean the same value, so a query can cross between them. It fetches nothing — that is a resolver's job. The id is the endpoint pair, and :from/:to in the entry name the same two attributes.")
     ($ asset-editor {:type :link :mode :create
                      :template {:from :cis.a/x :to :cis.b/y :description ""}
                      :id-placeholder "link id, e.g. [:cis.a/x :cis.b/y]"
                      :schema schema :catalog catalog :graph graph
                      :on-close on-close
                      :on-saved (fn [{:keys [id]}]
                                  (on-model-change)
                                  (on-select {:kind :link :id id}))})))

(defui entity-create-panel
  "Declare an entity from the Graph Editor's entities rail (owner, 2026-09-05).

   Deliberately **not a second form**: it is the `asset-editor` in create mode
   on `:namespace`, seeded exactly as the Workspace page seeds it, so the two
   doors cannot come to ask different questions. The difference is the graph:
   here there is one, so the editor's «add to graph» tick appears and the new
   entity lands in the recipe on save instead of needing a second visit."
  [{:keys [catalog schema graph on-select on-model-change on-close]}]
  ($ :div.dossier
     ($ :h2 "New entity")
     ($ :p.hint "An entity is a namespace: the thing attributes hang off and paths start from. Tick «add to graph» to put it in this graph's recipe as you save.")
     ($ asset-editor {:type :namespace :mode :create
                      :template (asset-template "namespace")
                      :id-placeholder (asset-id-placeholder "namespace")
                      :schema schema :catalog catalog :graph graph
                      :on-close on-close
                      :on-saved (fn [{:keys [id]}]
                                  (on-model-change)
                                  (on-select {:kind :entity :id id}))})))

(defui attribute-create-panel
  "Declare a scalar attribute from the Graph Editor's attributes rail.

   Same reasoning as `entity-create-panel`: the `asset-editor` in create mode
   on `:attribute`, seeded as the Workspace page seeds it, with the graph in
   context so the «add to graph» tick puts it in the recipe on save. Joins have
   their own panel because they need two entities picked, not a type."
  [{:keys [catalog schema graph on-select on-model-change on-close]}]
  ($ :div.dossier
     ($ :h2 "New attribute")
     ($ :p.hint "A scalar attribute of an entity: id is entity/name, e.g. nifi.instance/host. Tick «add to graph» to put it in this graph's recipe as you save. For a path to another entity use «+ join» on the entities tab.")
     ($ asset-editor {:type :attribute :mode :create
                      :template (asset-template "attribute")
                      :id-placeholder (asset-id-placeholder "attribute")
                      :schema schema :catalog catalog :graph graph
                      :on-close on-close
                      :on-saved (fn [{:keys [id]}]
                                  (on-model-change)
                                  (on-select {:kind :attribute :id id}))})))

(defui join-create-panel
  "Declare a join: the named path from one entity to another, as EDN (owner
   ruling 2026-09-05).

   **It had no way in at all.** A list resolver's join root MUST be a
   registered object, and until this panel existed a modeller who wrote the
   resolver got it refused for something they had no way to declare. The pool
   always accepted it; only the door was missing.

   Since D2.29 the object it writes is an **attribute** — one carrying
   `:target` and no `:type` — not a navigation of its own type. What kept this
   a panel of its own is the seed, not a form: the entry opens as a join's
   shape rather than a scalar's, so the two keys that differ are the ones on
   screen. The id is the qualified keyword the query writes, because the path
   `ssos` on `cis.default.customers` is read as `:cis.default.customers/ssos`."
  [{:keys [catalog schema graph on-select on-model-change on-close]}]
  ($ :div.dossier
     ($ :h2 "New join")
     ($ :p.hint "A join is the named path a query follows from one entity to another — the key you write as {:ns/name [...]}. It is an attribute whose :target names where it goes, and it carries no :type (D2.29). Declare it before the resolver whose output hangs from it.")
     ($ asset-editor {:type :attribute :mode :create
                      ;; No `:type`: a join attribute and a scalar one are
                      ;; mutually exclusive, and an entry carrying both is
                      ;; refused by the reader (D2.29).
                      :template {:target "" :cardinality :one-to-many}
                      :id-placeholder "join id, e.g. cis.default.customers/ssos"
                      :schema schema :catalog catalog :graph graph
                      :on-close on-close
                      :on-saved (fn [{:keys [id]}]
                                  (on-model-change)
                                  (on-select {:kind :attribute :id (kw-text id)}))})))


(defui dossier [{:keys [selected catalog index on-select on-plan can-edit?
                        on-model-change schema graph]}]
  (case (:kind selected)
    :attribute (if (graph/combination? (:id selected))
                 ($ combination-dossier {:id (:id selected) :index index :on-select on-select})
                 ($ attribute-dossier
                  ;; The catalog copy first: after an in-place edit the catalog
                  ;; is refetched, while `:data` still holds the row captured at
                  ;; selection time.
                  {:attr (or (first (filter #(= (:id selected) (:attribute %)) (:attributes catalog)))
                             (:data selected)
                             {:attribute (:id selected) :entity (entity-of (:id selected))})
                   :catalog catalog :index index :on-select on-select :on-plan on-plan
                   :can-edit? can-edit? :on-model-change on-model-change :schema schema :graph graph}))
    :resolver  ($ resolver-dossier {:resolver (or (first (filter #(= (:id selected) (:id %))
                                                                 (:resolvers catalog)))
                                                  (:data selected))
                                    :on-select on-select :on-plan on-plan
                                    :can-edit? can-edit? :on-model-change on-model-change
                                    :schema schema :catalog catalog :graph graph})
    :source
    ($ source-dossier {:source (or (first (filter #(= (:id selected) (:id %)) (:sources catalog)))
                                   (:data selected))
                       :catalog catalog :on-select on-select
                       :can-edit? can-edit? :on-model-change on-model-change
                       :schema schema :graph graph})
    :thing-create
    ($ thing-wizard {:key (str "thing-" (:id selected))
                     :source (or (first (filter #(= (:id selected) (:id %)) (:sources catalog)))
                                 (:data selected))
                     :graph graph
                     :on-model-change on-model-change
                     :on-close #(on-select {:kind :source :id (:id selected)})})
    :source-create
    ($ source-create-panel {:catalog catalog :schema schema :graph graph
                            :on-select on-select :on-model-change on-model-change
                            :on-close #(on-select nil)})
    :link
    ($ link-dossier {:link (or (first (filter #(= (:id selected) (:id %)) (:links catalog)))
                               (:data selected))
                     :catalog catalog :on-select on-select
                     :can-edit? can-edit? :on-model-change on-model-change
                     :schema schema :graph graph})
    :link-create
    ($ link-create-panel {:catalog catalog :schema schema :graph graph
                          :on-select on-select :on-model-change on-model-change
                          :on-close #(on-select nil)})
    :join-create
    ($ join-create-panel {:catalog catalog :schema schema :graph graph
                          :on-select on-select :on-model-change on-model-change
                          :on-close #(on-select nil)})
    :entity-create
    ($ entity-create-panel {:catalog catalog :schema schema :graph graph
                            :on-select on-select :on-model-change on-model-change
                            :on-close #(on-select nil)})
    :attribute-create
    ($ attribute-create-panel {:catalog catalog :schema schema :graph graph
                               :on-select on-select :on-model-change on-model-change
                               :on-close #(on-select nil)})
    :term
    ($ term-dossier {:term (or (first (filter #(= (:id selected) (:id %)) (:terms catalog)))
                               (:data selected))
                     :catalog catalog :on-select on-select
                     :can-edit? can-edit? :on-model-change on-model-change
                     :schema schema :graph graph})
    :term-create
    ($ term-create-panel {:catalog catalog :schema schema :graph graph
                          :on-select on-select :on-model-change on-model-change
                          :on-close #(on-select nil)})
    (:metric :mutation :unit)
    (let [kind (:kind selected)
          rows (get catalog ({:metric :metrics :mutation :mutations :unit :units} kind))]
      ($ registry-dossier {:type kind
                           ;; The catalog copy first, as every dossier does:
                           ;; an in-place edit refetches it while `:data` still
                           ;; holds the row captured at selection time.
                           :item (or (first (filter #(= (:id selected) (:id %)) rows))
                                     (:data selected)
                                     {:id (:id selected)})
                           :can-edit? can-edit? :on-model-change on-model-change
                           :schema schema :catalog catalog :graph graph}))
    (:metric-create :mutation-create :unit-create)
    ($ registry-create-panel {:type ({:metric-create :metric :mutation-create :mutation
                                      :unit-create :unit} (:kind selected))
                              :catalog catalog :schema schema :graph graph
                              :on-select on-select :on-model-change on-model-change
                              :on-close #(on-select nil)})
    :resolver-create
    ($ resolver-create-panel {:catalog catalog :schema schema :graph graph
                              :from (:from selected) :to (:to selected)
                              :on-select on-select
                              :on-model-change on-model-change
                              :on-close #(on-select nil)})
    :derived-create
    ($ derived-create-panel {:catalog catalog :schema schema :graph graph
                             :on-select on-select
                             :on-model-change on-model-change
                             :on-close #(on-select nil)})
    :entity    ($ entity-dossier
                  ;; The catalog copy first, for the reason the attribute case
                  ;; gives: an in-place edit refetches the catalog, while
                  ;; `:data` still holds the row captured at selection time —
                  ;; so a saved label would keep showing the old one. The
                  ;; last fallback is what a "reaches" link hands over: an id
                  ;; and nothing else.
                  {:entity (or (first (filter #(= (:id selected) (:entity %)) (:entities catalog)))
                               (:data selected)
                               {:entity (:id selected)})
                   :catalog catalog :on-select on-select
                   :can-edit? can-edit? :on-model-change on-model-change
                   :schema schema :graph graph})
    ($ :div.dossier
       ($ :p.hint "Pick an attribute on the left to see which resolvers take it as input."))))

;; ---------------------------------------------------------------------------
;; Graph Editor page — review and edit one graph
;; ---------------------------------------------------------------------------

(def pane-boundary
  "A render failure in one pane must not take the workbench with it.

   `demo_all_vpos` did exactly that: its `:eql-params` arrived as keywords, a
   keyword is an object React cannot render, and the resulting throw unmounted
   everything up to the auth gate — a blank page for a bad field in one
   ledger. The field is fixed, but the fragility was the real defect: the pane
   that failed is the pane that should show it."
  (uix/create-error-boundary
   {:derive-error-state (fn [error] {:error error})
    :did-catch (fn [error info] (js/console.error error info))}
   (fn [[state _] {:keys [children]}]
     (if-let [error (:error state)]
       ($ :div.pane-error
          ($ :p.hint.bad "This pane could not be rendered.")
          ($ :p.hint (str (.-message error)))
          ($ :p.hint "The rest of the workbench is unaffected; the details are in the console."))
       children))))

(def member-kinds
  "The eight groups of a recipe, in the order a reader wants them: what the
   graph is about first, what it can do second, what it borrows last.
   `:navigations` was the ninth until D2.29 made a join an attribute."
  [[:namespaces "entities"] [:attributes "attributes"] [:resolvers "resolvers"]
   [:links "links"] [:mutations "mutations"]
   [:metrics "metrics"] [:terms "terms"] [:units "units"]])

(defui recipe-editor
  "**What this graph holds** — the recipe as a tick-list (D3.19).

   Every object in the workspace's pool is a row; the ticked ones are the
   recipe. Two things this screen exists to make true:

   - **Membership is a set, so it is shown as one.** No prefix to interpret,
     no \"nearly in\" — the box is on or it is off.
   - **What is *not* in the graph is counted, per kind.** A recipe does not
     follow the pool (that is the cost D3.19 accepts), so the divergence has to
     be somewhere a modeller can see and act on it. A number they can click is
     that somewhere; a silent gap is what the old prefix rule gave instead."
  [{:keys [graph on-change on-close]}]
  (let [[data set-data!] (uix/use-state nil)
        [busy set-busy!] (uix/use-state nil)
        ;; `:entities` is a view over the same pool, not a second recipe shape:
        ;; ticking a group posts exactly the member ids the object tabs post.
        ;; It is the default because it is the unit a modeller decides in —
        ;; nobody picks eighteen attributes, they pick "SSO".
        [kind set-kind!] (uix/use-state :entities)
        [q set-q!] (uix/use-state "")
        [only-out? set-only-out!] (uix/use-state false)
        load! (uix/use-callback
               (fn [] (api/recipe-candidates!
                       graph {:on-success set-data!
                              :on-error #(js/console.error (pr-str %))}))
               [graph])]
    (uix/use-effect (fn [] (load!) js/undefined) [load!])
    (let [rows   (get-in data [:groups kind] [])
          needle (str/lower-case (str/trim q))
          entities (filterv (fn [e] (and (or (not only-out?) (not= :in (:state e)))
                                         (or (str/blank? needle)
                                             (str/includes? (str/lower-case (:entity e)) needle))))
                            (:entities data))
          shown  (filterv (fn [r] (and (or (not only-out?) (not (:in? r)))
                                       (or (str/blank? needle)
                                           (str/includes? (str/lower-case (:id r)) needle))))
                          rows)
          done!  (fn [] (set-busy! nil) (load!) (when on-change (on-change)))
          failed (fn [e] (set-busy! nil)
                   ;; A failed edit must not look like a slow one: the row is
                   ;; released and the error is named, rather than the panel
                   ;; sitting there implying it worked.
                   (js/console.error "recipe edit refused:" (pr-str e))
                   (js/alert (str "This recipe edit was refused: "
                                  (or (:message (:error e)) (pr-str e)))))
          toggle (fn [r on?]
                   (set-busy! (:id r))
                   (api/set-graph-members!
                    {:graph graph :kind (name kind) :ids [(:id r)] :in? on?}
                    {:on-success (fn [_] (done!)) :on-error failed}))
          ;; One edit for everything the filter is showing. The recipe is a
          ;; value either way; what this spares is twenty round trips and the
          ;; half-formed recipe between them.
          toggle-all (fn [on?]
                       (set-busy! ::all)
                       (api/set-graph-members!
                        {:graph graph :kind (name kind) :ids (mapv :id shown) :in? on?}
                        {:on-success (fn [_] (done!)) :on-error failed}))
          ;; An entity is several kinds at once — and they go in ONE request.
          ;; They used to go in three, concurrently, and the server rewrites the
          ;; whole recipe from what it read: three writers reading the same
          ;; value meant the last one silently discarded the other two, so a
          ;; ticked entity came back half ticked and an unticked one came back
          ;; unchanged. One gesture is one edit.
          toggle-entity (fn [e on?]
                          (set-busy! (:entity e))
                          (api/set-graph-members!
                           {:graph graph
                            :edits (vec (for [k [:namespaces :attributes :resolvers
                                                 :links :metrics :mutations]
                                              :let [ids (get (:members e) k)]
                                              :when (seq ids)]
                                          {:kind (name k) :ids ids :in? on?}))}
                           {:on-success (fn [_] (done!)) :on-error failed}))]
      ($ :section.pane.pane-recipe
         ($ :div.pane-head
            ($ :span "Recipe · " graph)
            ($ :span.spacer)
            ($ :button.btn {:on-click on-close} "Close"))
         ($ :div.pane-body
            ($ :div.seg.kinds
               ($ :button {:class (when (= kind :entities) "on")
                           :on-click #(set-kind! :entities)}
                  (let [n (count (remove #(= "in" (name (or (:state %) :out)))
                                         (:entities data)))]
                    (if (pos? n) (str "by entity · " n " out") "by entity")))
               (for [[k label] member-kinds
                     :let [n (get-in data [:missing k])]]
                 ($ :button {:key (name k)
                             :class (when (= kind k) "on")
                             :on-click #(set-kind! k)}
                    ;; The badge is what is **out**, not what is in: "12 not
                    ;; here" is the number a modeller acts on.
                    (if (and n (pos? n)) (str label " · " n " out") label))))
            ($ :div.wizard-filter
               ($ :input.search {:value q :placeholder "filter" :spell-check false
                                 :aria-label "filter recipe candidates"
                                 :on-change #(set-q! (.. % -target -value))})
               ($ :label.check
                  ($ :input {:type "checkbox" :checked only-out?
                             :aria-label "show only what is not in this graph"
                             :on-change #(set-only-out! (.. % -target -checked))})
                  " not in this graph")
               ($ :span.count (str (count shown) " of " (count rows)))
               (when (and (not= kind :entities) (seq shown))
                 ($ :<>
                    ($ :button.btn {:disabled (some? busy)
                                    :on-click #(toggle-all true)}
                       (str "add these " (count shown)))
                    ($ :button.btn {:disabled (some? busy)
                                    :on-click #(toggle-all false)}
                       "remove these"))))
            (if (= kind :entities)
              (if (empty? entities)
                ($ :p.none "nothing matches")
                ($ :ul.recipe-rows.by-entity
                   (for [e entities]
                     ($ :li {:key (:entity e) :class (name (:state e))}
                        ($ :label
                           ($ :input {:type "checkbox"
                                      :checked (= :in (:state e))
                                      ;; A partial entity reads as neither on
                                      ;; nor off, because it is neither — and
                                      ;; that is the state worth seeing.
                                      :ref (fn [el] (when el
                                                      (set! (.-indeterminate el)
                                                            (= :partial (:state e)))))
                                      :disabled (= busy (:entity e))
                                      ;; Partial reads as "remove" because that is
                                      ;; what clicking it now does.
                                      :aria-label (str (if (= :out (:state e)) "add " "remove ")
                                                       (:entity e))
                                      ;; **Partial means take it OUT.** `indeterminate`
                                      ;; is a paint job — the browser clears it on
                                      ;; click and reports `checked true`, so a
                                      ;; half-in entity used to need two clicks to
                                      ;; leave and the first one silently ADDED the
                                      ;; missing half. Ticking what is partly there
                                      ;; is the rarer intent than clearing it, and
                                      ;; an accidental add is the more expensive
                                      ;; mistake: it is the one that changes what
                                      ;; the graph serves.
                                      :on-change #(toggle-entity
                                                   e (if (= :partial (:state e))
                                                       false
                                                       (.. % -target -checked)))})
                           ($ :code.mono (:entity e))
                           ($ :span.via (str (:have e) "/" (:count e)
                                             (when-not (= (:label e) (:entity e))
                                               (str " · " (:label e))))))))))
              (if (empty? shown)
                ($ :p.none "nothing matches")
                ($ :ul.recipe-rows
                   (for [r shown]
                     ($ :li {:key (:id r) :class (when (:in? r) "on")}
                        ($ :label
                           ($ :input {:type "checkbox"
                                      :checked (boolean (:in? r))
                                      :disabled (= busy (:id r))
                                      :aria-label (str (if (:in? r) "remove " "add ") (:id r))
                                      :on-change #(toggle r (.. % -target -checked))})
                           ($ :code.mono (:id r)))))))))))))

(defui index-page
  [{:keys [catalog query on-query kind on-kind selected on-select index
           graph-name view on-view hovered on-hover on-plan theme
           can-edit? on-model-change schema]}]
  (let [[recipe-open? set-recipe-open!] (uix/use-state false)
        ;; The right-click menu needs to know whether the object is already in
        ;; this graph, so the membership answer is fetched once per graph and
        ;; refreshed after an edit — the same `recipe-candidates` the Recipe
        ;; panel reads, so the two can never disagree about what is in.
        [membership set-membership!] (uix/use-state nil)
        [ctx set-ctx!] (uix/use-state nil)
        [rail-w set-rail-w!]   (uix/use-state 22)
        ;; Not `dossier`: that is the component this page renders, and shadowing
        ;; it handed React the number 26 to render.
        [dossier-w set-dossier-w!] (uix/use-state 26)
        ;; Connect mode re-reads as a class and a drag branch, never as a
        ;; canvas rebuild — see attribute-graph's `connecting?`.
        [connect? set-connect!] (uix/use-state false)
        load-membership! (uix/use-callback
                          (fn []
                            (when (and graph-name (not (workspace-scope? graph-name)))
                              (api/recipe-candidates!
                               graph-name {:on-success set-membership!
                                           :on-error (fn [_] (set-membership! nil))})))
                          [graph-name])
        open-menu!
        (uix/use-callback
         (fn [{:keys [item x y kind id entity]}]
           (let [ids  (set (map :id (filter :in? (get-in membership [:groups kind] []))))
                 erow (first (filter #(= entity (:entity %)) (:entities membership)))]
             (set-ctx! {:x x :y y :kind kind :id id
                        :label (str id)
                        :in? (contains? ids (pr-str id))
                        :entity entity
                        :entity-row erow
                        :entity-in? (= "in" (some-> (:state erow) name))
                        :item item})))
         [membership])]
    (uix/use-effect (fn [] (load-membership!) js/undefined) [load-membership!])
  ($ :main.page-index
     ;; The recipe panel takes the whole page when open: editing what a graph
     ;; holds is a different act from browsing what it holds, and doing it in a
     ;; corner of the Blueprint would make the two look like one.
     (when recipe-open?
       ($ recipe-editor
          {:graph graph-name
           ;; **Editing the recipe changes the model everything else is reading.**
           ;; Every other write on this page announces itself through
           ;; `on-model-change`; this one did not, so ticking an entity in and
           ;; closing the panel left the rail, the blueprint and the query
           ;; editor holding the catalog from before the edit — the object was
           ;; genuinely in the graph and genuinely unfindable, until a page
           ;; reload. Refreshing membership too keeps the right-click menu
           ;; honest about what is in.
           :on-change (fn [] (load-membership!)
                        (when on-model-change (on-model-change)))
           :on-close #(set-recipe-open! false)}))
     (when ctx
       ($ member-menu {:at ctx
                       :on-close #(set-ctx! nil)
                       :on-apply
                       (fn [{:keys [scope in?]}]
                         (let [ids (if (= :entity scope)
                                     (:members (:entity-row ctx))
                                     {(:kind ctx) [(pr-str (:id ctx))]})
                               ks  (vec (remove #(empty? (get ids %)) (keys ids)))
                               left (atom (count ks))]
                           (doseq [k ks]
                             (api/set-graph-members!
                              {:graph graph-name :kind (name k)
                               :ids (get ids k) :in? in?}
                              {:on-success (fn [_] (when (zero? (swap! left dec))
                                                     (load-membership!)
                                                     (when on-model-change (on-model-change))))
                               :on-error #(js/console.error (pr-str %))}))))}))
     ($ :section.pane.pane-rail {:style {:flex (str "0 0 " rail-w "%")}}
        ($ rail {:catalog catalog :query query :on-query on-query :kind kind :on-kind on-kind
                 :selected selected :on-select on-select
                 :can-edit? can-edit?
                 :on-context (when (and can-edit? membership) open-menu!)
                 :on-create-resolver #(on-select {:kind :resolver-create})
                 :on-create-term #(on-select {:kind :term-create})
                 :on-create-metric #(on-select {:kind :metric-create})
                 :on-create-mutation #(on-select {:kind :mutation-create})
                 :on-create-unit #(on-select {:kind :unit-create})
                 :on-create-link #(on-select {:kind :link-create})
                 :on-create-join #(on-select {:kind :join-create})
                 :on-create-entity #(on-select {:kind :entity-create})
                 :on-create-attribute #(on-select {:kind :attribute-create})
                 :on-create-derived #(on-select {:kind :derived-create})
                 :on-create-source #(on-select {:kind :source-create})}))
     ($ gutter {:dir :x :pct rail-w :on-pct set-rail-w!})
     ($ :<>
        ($ :section.pane.pane-graph
           ($ :div.pane-head
              ($ :span "Blueprint · " graph-name)
              (when can-edit?
                ($ :button.btn {:on-click #(set-recipe-open! true)
                                :title "Choose what this graph holds"}
                   "Recipe"))
              ($ :span.count (str (:shown (:counts index)) " of " (:attributes (:counts index))
                                  " attributes · " (:edges (:counts index)) " hops"
                                  (when hovered (str " · via " hovered))))
              ($ :span.spacer)
              (when (and can-edit? (= view :graph))
                ($ :button.btn {:class (when connect? "on")
                                :title "Draw a hop between two attributes to propose a resolver"
                                :on-click #(set-connect! (not connect?))}
                   (if connect? "connecting…" "connect")))
              ($ :div.seg
                 ($ :button {:class (when (= view :graph) "on") :on-click #(on-view :graph)} "graph")
                 ($ :button {:class (when (= view :table) "on") :on-click #(on-view :table)} "table")))
           (cond
             ;; A graph whose recipe selects nothing draws an empty canvas, and
             ;; an empty canvas says nothing about what to do next — a modeller
             ;; arriving here for the first time has no way to learn that a
             ;; database can fill it (owner, 2026-09-03). `catalog` and not
             ;; `index`: nil means «still reading», and a loading pane must not
             ;; announce itself as empty.
             (and catalog (empty? (:attributes catalog)))
             ($ :div.pane-body
                ($ :div.empty-state
                   ($ :div.empty-state-icon "\u25CD")
                   ($ :div.empty-state-title "Nothing in this graph yet")
                   ($ :div.empty-state-hint
                      "A Graph holds the attributes, resolvers and links you define. Add them here, or import a Semantic Contract from Graph Definition.")))

             (= view :table)
             ($ :div.pane-body ($ graph/index-table
                                  {:graph index :catalog catalog :kind kind
                                   :on-select (fn [r]
                                                (on-select
                                                 (case kind
                                                   :resolvers {:kind :resolver :id (:id r) :data r}
                                                   :terms     {:kind :term :id (:id r) :data r}
                                                   :links     {:kind :link :id (:id r) :data r}
                                                   :sources   {:kind :source :id (:id r) :data r}
                                                   {:kind :attribute :id (:attribute r) :data r})))}))

             :else
             ($ attribute-graph/attribute-graph
                {:graph index
                 ;; The selection is marked in the full picture rather than
                 ;; becoming the picture — and the viewport travels to it. A
                 ;; resolver locates by what it consumes, which is where it sits.
                 ;; An entity is a namespace, not a node — it locates by its
                 ;; busiest attribute, which is where that entity sits in the
                 ;; picture and what its neighbours hang off.
                 :focus (case (:kind selected)
                          :attribute (:id selected)
                          :resolver  (graph/combination-id (:input (:data selected)))
                          :entity    (->> (:nodes index)
                                          (filter #(= (:id selected) (:entity %)))
                                          (sort-by (juxt (comp - :reach) (comp - :weight)))
                                          first
                                          :attribute)
                          nil)
                 ;; The canvas cannot read CSS variables, so a palette change is a
                 ;; rebuild — hence the theme travels with the data.
                 :theme theme
                 :on-hover-edge on-hover
                 :connect? connect?
                 ;; A drawn hop is a proposal, not a write: it opens the create
                 ;; panel seeded with the two ends, and the pool still gets the
                 ;; final say when Create is pressed.
                 :on-connect (fn [from to]
                               (set-connect! false)
                               (on-select {:kind :resolver-create :from from :to to}))
                 :on-select (fn [attr] (on-select {:kind :attribute :id attr}))})))

        ($ gutter {:dir :x :pct (- 100 dossier-w) :on-pct #(set-dossier-w! (- 100 %))})

        ($ :section.pane.pane-dossier {:style {:flex (str "0 0 " dossier-w "%")}}
           ($ :div.pane-head
              ($ :span (case (:kind selected)
                         :attribute "Attribute"
                         :resolver  "Resolver"
                         :resolver-create "New resolver"
                         :derived-create "New derived resolver"
                         :term      "Term"
                         :term-create "New term"
                         :metric    "Metric"
                         :metric-create "New metric"
                         :mutation  "Mutation"
                         :mutation-create "New mutation"
                         :unit      "Unit"
                         :unit-create "New unit"
                         :link      "Link"
                         :link-create "New link"
                         :join-create "New join"
                         :entity-create "New entity"
                         :attribute-create "New attribute"
                         :source    "Source"
                         :source-create "New source"
                         :thing-create "There is a thing called…"
                         :entity    "Entity"
                         "Detail"))
              ($ :span.spacer)
              (when selected
                ($ :button.btn {:on-click #(on-select nil)} "clear")))
           ($ :div.pane-body
           ($ pane-boundary
              ($ dossier {:selected selected :catalog catalog :index index
                          :on-select on-select :on-plan on-plan
                          :can-edit? can-edit? :on-model-change on-model-change
                          :schema schema :graph graph-name}))))))))

;; ---------------------------------------------------------------------------
;; Workspace graphs page
;; ---------------------------------------------------------------------------

(defn- parse-selector-list [text]
  (->> (str/split (or text "") #"[,\n]")
       (map str/trim)
       (remove str/blank?)
       vec))

;; ---------------------------------------------------------------------------
;; Self-test tokens against the embedded runtime
;; ---------------------------------------------------------------------------

(def issued-secret access/issued-secret)

(declare ledger-filter-toggle)

(def ^:private recent-version-count 5)

(defui token-panel
  "Issue, list and revoke this graph's self-test tokens.

   These are the Server's tokens in every respect but authority: same format,
   same verification, same grant rules, because there is only one runtime
   (D1.13). What is local is where they are valid — a token issued here fails
   on any other instance (S5.8), which is what keeps development authorisation
   and production authorisation from ever being the same ledger."
  [{:keys [graph data-port data-scheme on-refresh archived?]}]
  (let [[tokens set-tokens!]   (uix/use-state nil)
        [secret set-secret!]   (uix/use-state nil)
        [error set-error!]     (uix/use-state nil)
        [busy? set-busy!]      (uix/use-state false)
        [vouching? set-vouching!] (uix/use-state false)
        reload! (uix/use-callback
                 (fn []
                   (api/self-test-tokens!
                    {:on-success set-tokens! :on-error set-error!}))
                 [])
        ;; The ledger records one graph per token under `:graph-id`; `:grants`
        ;; retired with the multi-grant shape. Reading the old key matched
        ;; nothing, so this panel reported "no token has been issued" over a
        ;; ledger holding live ones.
        mine (filterv #(= graph (:graph-id %)) (:tokens tokens))
        [show-all? set-show-all!] (uix/use-state false)
        live? #(= :active (access/token-status %))
        shown (if show-all? mine (filterv live? mine))]
    (uix/use-effect (fn [] (reload!) js/undefined) [reload! graph])
    ($ :section.token-panel
       ;; Title, what a token grants and the one action share a row; the
       ;; endpoint sentence under it is reference. No tier to pick — access
       ;; tiers retired (owner ruling 2026-08-27): a token grants **this graph**,
       ;; and a graph holds exactly what its recipe lists. Narrower access is a
       ;; narrower graph (D3.19).
       ($ :div.runtime-head
          ($ :h3 "Access tokens")
          ($ :span.field-hint "read access to everything this graph holds")
          ($ :span.spacer)
          ($ :button.btn.primary
             {:disabled (or busy? archived?)
              :on-click (fn []
                          (set-busy! true) (set-error! nil)
                          (api/issue-self-test-token!
                           (cond-> {:graph graph} vouching? (assoc :asserts-user? true))
                           {:on-success (fn [r]
                                          (set-busy! false)
                                          (set-vouching! false)
                                          (set-secret! (:secret r))
                                          (reload!)
                                          (when on-refresh (on-refresh)))
                            :on-error (fn [e] (set-busy! false) (set-error! e))}))}
             (if busy? "Issuing…" "Create token")))
       ;; D1.9b: only the Workbench server's read-only token may name the user it reads for.
       ($ :label.field-hint
          ($ :input {:type "checkbox" :checked vouching? :disabled busy?
                     :on-change #(set-vouching! (.. % -target -checked))})
          " Workbench service token: may name the signed-in user it reads for (leave off for Agents)")
       ($ :p.hint
          "Works only on this instance — "
          ($ :code (str (or data-scheme "http") "://" (.-hostname js/location) ":" (or data-port 9632) "/eql"))
          " with " ($ :code "Authorization: Bearer …") ".")
       (when secret ($ issued-secret {:secret secret :on-dismiss #(set-secret! nil)}))
       (when error ($ findings-view {:error error}))
       (cond
         (nil? tokens) ($ :p.hint "Reading the ledger…")
         (empty? mine) ($ :p.hint "No token has been issued for this graph.")
         :else
         ($ :<>
         (if (empty? shown)
           ($ :p.hint "No active token for this graph.")
         ($ access/token-list
            {:tokens shown
             :on-revoke (fn [token-id done]
                          (api/revoke-self-test-token! token-id
                           {:on-success (fn [_] (done) (reload!))
                            :on-error (fn [e] (done) (set-error! e))}))}))
         ($ ledger-filter-toggle {:all mine :show-all? show-all? :pred live?
                                  :on-toggle set-show-all!}))))))

(defn- token-active?
  "A workspace token is live unless the ledger says otherwise (`:status`)."
  [t]
  (not (#{:revoked :expired} (:status t))))

(defn- graph-token-active?
  "`:expired?` is the SERVER's answer (common/token `expired?`), never a
   comparison made here."
  [t]
  (and (= :active (:status t)) (not (:expired? t))))

(defn- expiry-text
  "MM/DD/YYYY plus a relative hint. The hint is display only; whether a key
   has run out is the ledger's verdict, passed in as `expired?`."
  [iso expired?]
  (let [d (js/Date. iso)]
    (if (js/isNaN (.getTime d))
      (str iso)
      (let [pad  #(.padStart (str %) 2 "0")
            date (str (pad (inc (.getMonth d))) "/" (pad (.getDate d)) "/" (.getFullYear d))
            days (js/Math.ceil (/ (- (.getTime d) (js/Date.now)) 86400000))]
        (str date " · "
             (cond expired?    "expired"
                   (<= days 0) "today"
                   (= 1 days)  "in 1 day"
                   :else       (str "in " days " days")))))))

(defn- token-status-tag [status]
  (case status
    :revoked ($ :span.tag "Revoked")
    :expired ($ :span.tag "Expired")
    ($ :span.tag.tag-live "Active")))

(defui ledger-filter-toggle
  "Active keys by default; retired ones are history, one click away."
  [{:keys [all show-all? on-toggle pred] :or {pred token-active?}}]
  (let [n (count (remove pred all))]
    (when (pos? n)
      ($ :button.linkish.token-filter
         {:on-click #(on-toggle (not show-all?))}
         (if show-all? "Hide revoked/expired" (str "Show revoked/expired (" n ")"))))))

(defui workspace-token-panel
  "Issue, list and revoke **workspace tokens** — the key an AI client presents
   to this Builder's MCP endpoint (8.4 P1b).

   Lives on the workspace team page, apart from the per-graph self-test
   panel — a workspace token is bound to (workspace, scope) and involves no
   graph. Worded to say which
   door each key opens. The two are not interchangeable: a self-test token
   reads one graph's data on the data plane; this one edits the model of a
   whole workspace. Presenting either at the other's door is refused, but a
   panel that let them look alike would still be inviting the mistake.

   The secret is shown **once**, by the same component the self-test panel
   uses — there is one place in this UI that knows how to display a secret,
   and one is the right number."
  [{:keys [can-edit?]}]
  (let [[data set-data!]     (uix/use-state nil)
        [secret set-secret!] (uix/use-state nil)
        [error set-error!]   (uix/use-state nil)
        [busy? set-busy!]    (uix/use-state false)
        [writable? set-writable!] (uix/use-state false)
        ;; No preselected lifetime: the issuer picks one every time. A default
        ;; here would be a policy decision made by whoever wrote this line,
        ;; invisible to the person actually signing the key.
        [days set-days!] (uix/use-state nil)
        ;; 8.4.9 ③：一个 OMA Agent 一把 token，name 记下是哪个。池的审计只记
        ;; `token-id`，多个 agent 共用一把时，「哪个 agent 改的」就没有答案。
        ;; 这里不做校验也不去查 OMA 的 agent 表——那会把 8.5 维持取消的同步
        ;; 重新引进来。name 是给人查的线索，池仍然只信 transport 注入的身份。
        [label set-label!] (uix/use-state "")
        ;; The form is closed until asked for: issuing is the rare act on this
        ;; page, reading the ledger the common one (owner 2026-10-04).
        [issuing? set-issuing!] (uix/use-state false)
        [show-all? set-show-all!] (uix/use-state false)
        ;; A SECOND field rather than a wider first one (8.4.11): one box asked
        ;; to be both the audit clue and the purpose ended up worded «used by»
        ;; — a person's name slot for a key that usually belongs to no person.
        [description set-description!] (uix/use-state "")
        lifetimes (or (seq (:lifetimes data)) [1 7 30 90])
        ;; Served by the endpoint, never a constant here: a bound the browser
        ;; keeps its own copy of is a bound that disagrees with the one that
        ;; actually refuses.
        limits (:limits data)
        reload! (uix/use-callback
                 (fn [] (api/workspace-tokens! {:on-success set-data! :on-error set-error!}))
                 [])]
    (uix/use-effect (fn [] (reload!) js/undefined) [reload!])
    ($ :section.token-panel
       ($ :div.token-head
          ($ :h3 "Workspace tokens · Builder MCP")
          (when data ($ :span.token-count (str (count (filter token-active? (:tokens data))) " active")))
          ($ :span.spacer)
          (when (and can-edit? (not issuing?))
            ($ :button.btn.primary {:on-click #(set-issuing! true)} "Issue workspace token")))
       ($ :p.hint
          "For an AI client that edits this workspace's model over MCP. Point it at "
          ;; The UI is served by the Builder listener itself, so its own origin
          ;; IS the MCP endpoint's origin — no configured port to drift from.
          ($ :code (str (.-origin js/location) "/mcp"))
          " with " ($ :code "Authorization: Bearer …") ". "
          ($ :strong "Not a self-test token")
          " — that one reads a graph's data on the data plane and is not accepted here.")
       (when secret ($ issued-secret {:secret secret :on-dismiss #(set-secret! nil)}))
       (when error ($ findings-view {:error error}))
       (when (and can-edit? issuing?)
         ($ :div.token-issue
            ($ :label.field-check
               ($ :input {:type "checkbox" :checked writable?
                          :on-change #(set-writable! (.. % -target -checked))})
               " may write the model")
            ($ :span.field-hint
               (if writable?
                 "the write tools appear for this key and every write it makes is recorded
                  against it"
                 "read-only: the write tools are not listed for this key at all"))
            ($ :label.field
               "expires after "
               ($ :select
                  {:value (or (some-> days str) "")
                   :on-change #(let [v (.. % -target -value)]
                                 (set-days! (when-not (= "" v) (js/parseInt v 10))))}
                  ($ :option {:value ""} "choose…")
                  (for [d lifetimes]
                    ($ :option {:key d :value (str d)}
                       (if (= 1 d) "1 day" (str d " days"))))))
            ($ :span.field-hint
               "the key stops working on its own at that point — expiry and revocation
                are separate, and the list below says which one ended a key")
            ($ :label.field
               "name "
               ($ :input {:type "text" :value label :required true
                          :max-length (:label limits)
                          :placeholder "the client or agent that holds this key"
                          :on-change #(set-label! (.. % -target -value))}))
            ($ :span.field-hint
               "one key per client, and name it: the audit records the token-id, so a key
                two agents share cannot answer which of them made a change")
            ($ :label.field.field-wide
               "description "
               ($ :textarea {:value description :rows 2
                             :max-length (:description limits)
                             :placeholder "what this key is for"
                             :on-change #(set-description! (.. % -target -value))}))
            ($ :span.field-hint
               "optional, and about the KEY rather than a person — most of these belong to
                a client, not to anybody. Do not paste a secret here: this ledger is a file
                on disk.")
            ($ :button.btn.primary
               ;; The name is required at the door too; disabling here only
               ;; spares a round trip, it is not where the rule lives.
               {:disabled (or busy? (nil? days) (str/blank? label))
                :on-click (fn []
                            (set-busy! true) (set-error! nil)
                            (api/issue-workspace-token!
                             (cond-> {:scope (if writable? [:read :write] [:read])
                                      :expires-in-days days
                                      :label (str/trim label)}
                               (seq (str/trim description))
                               (assoc :description (str/trim description)))
                             {:on-success (fn [r]
                                            (set-busy! false)
                                            (set-secret! (:token r))
                                            (set-label! "")
                                            (set-description! "")
                                            (set-issuing! false)
                                            (reload!))
                              :on-error (fn [e] (set-busy! false) (set-error! e))}))}
               (if busy? "Issuing…" "Issue workspace token"))
            ($ :button.btn {:disabled busy? :on-click #(set-issuing! false)} "Cancel")))
       (cond
         (nil? data) ($ :p.hint "Reading the ledger…")
         (empty? (:tokens data)) ($ :p.hint "No workspace token has been issued.")
         :else
         (let [all  (:tokens data)
               rows (if show-all? all (filterv token-active? all))]
           ($ :<>
              ($ :table.token-table
                 ($ :thead
                    ($ :tr
                       ($ :th "Name") ($ :th "ID") ($ :th "Scope")
                       ($ :th "Expires") ($ :th "Status") ($ :th.act "")))
                 ($ :tbody
                    (for [t rows]
                      ($ :tr {:key (:token-id t)}
                         ;; The name leads: it is the only thing in this row a
                         ;; person wrote, and the only thing that says WHICH
                         ;; client holds the key — the token-id answers «which
                         ;; key», never «whose» (8.4.9 ③). A record from before
                         ;; names were required shows a dash, so «never named»
                         ;; reads as a fact rather than a gap. The description
                         ;; rides in the tooltip so every row keeps one height.
                         ($ :td.token-name {:title (:description t)}
                            (if (seq (:label t)) (:label t) "—"))
                         ($ :td.token-id {:title (:token-id t)} (:token-id t))
                         ($ :td (if (contains? (set (:scope t)) :write) "read + write" "read"))
                         ($ :td {:title (:expires-at t)}
                            (expiry-text (:expires-at t) (= :expired (:status t))))
                         ;; Three states, not «revoked or not»: a key that ran
                         ;; out and a key somebody retired are different facts
                         ;; about how this workspace is run.
                         ($ :td (token-status-tag (:status t)))
                         ($ :td.act
                            (when (and can-edit? (nil? (:revoked-at t)))
                              ($ :button.btn
                                 {:title "Revoke this token on this instance"
                                  :on-click #(api/revoke-workspace-token!
                                              (:token-id t)
                                              {:on-success (fn [_] (reload!))
                                               :on-error set-error!})}
                                 "Revoke")))))))
              (when (empty? rows) ($ :p.hint "No active workspace token."))
              ($ ledger-filter-toggle {:all all :show-all? show-all? :on-toggle set-show-all!})))))))

(defui graph-token-ledger
  "Every data-plane key this workspace's graphs hold — a READ, and deliberately
   only a read (8.4.11 ①).

   Issuing stays where it was: on the graph itself. The one risk a screen like
   this can add is a single place asking «which kind of key do you want», which
   would put two authorities that must never be interchangeable into one
   dropdown — and D3.12 split the doors precisely so that a leaked query key is
   not a write authority. Listing cannot make that mistake, and without a list
   «how many keys are out, and is one of them stale?» had no answer anywhere in
   Studio: they were visible only one graph at a time, behind a disclosure
   triangle on a row somebody had to already be looking at.

   Revoking is here for the same reason it is not a risk: it names one key that
   already exists, so there is nothing to choose between."
  [{:keys [on-open]}]
  (let [[data set-data!]   (uix/use-state nil)
        [error set-error!] (uix/use-state nil)
        [show-all? set-show-all!] (uix/use-state false)
        reload! (uix/use-callback
                 (fn [] (api/self-test-tokens! {:on-success set-data! :on-error set-error!}))
                 [])
        ;; `:expired?` is answered by the SERVER, with `common/token`'s own
        ;; `expired?` — the same function the door uses. This browser once
        ;; compared the instants itself, which was a second implementation of
        ;; «has this run out» sitting where nobody would look for one.
        ;; Grouped by graph; inside a graph the server's order stands, newest first
        ;; (sort-by is stable). Oldest-first put the live key at the bottom (owner 2026-10-10).
        rows (vec (sort-by :graph-id (:tokens data)))
        ;; Visibility belongs to the workspace, editing does not: a member sees
        ;; these keys and may not retire them. The server says which graphs
        ;; this caller may act on, and refuses regardless — this only keeps a
        ;; button that could never work off the screen.
        editable (set (:editable data))]
    (uix/use-effect (fn [] (reload!) js/undefined) [reload!])
    ($ :section.token-panel
       ($ :div.token-head
          ($ :h3 "Graph tokens")
          (when data ($ :span.token-count (str (count (filter graph-token-active? rows)) " active"))))
       ($ :p.hint
          "One key, one graph, read-only, presented at the data plane. "
          ($ :strong "Not a workspace key")
          " — that one edits the model and is not accepted there. Keys are issued "
          "on the graph itself (Graphs › a graph › local runtime); this is where they "
          "are counted and retired.")
       (when error ($ findings-view {:error error}))
       (cond
         (nil? data) ($ :p.hint "Reading the ledger…")
         (empty? rows)
         ($ :p.hint "No graph key has been issued in this workspace.")
         :else
         (let [shown (if show-all? rows (filterv graph-token-active? rows))]
           ($ :<>
              ($ :table.token-table
                 ($ :thead
                    ($ :tr
                       ($ :th "Graph") ($ :th "ID") ($ :th "Scope")
                       ($ :th "Expires") ($ :th "Status") ($ :th.act "")))
                 ($ :tbody
                    (for [t shown]
                      ($ :tr {:key (:token-id t)}
                         ;; The graph leads, because it IS the authorisation: a
                         ;; graph key holds exactly what its graph holds.
                         ($ :td.token-name
                            ($ :button.linkish.token-name
                               {:title (str "Open " (:graph-id t) " in Graphs")
                                :on-click #(on-open (:graph-id t))}
                               (:graph-id t)))
                         ($ :td.token-id {:title (:token-id t)} (:token-id t))
                         ($ :td "read")
                         ($ :td {:title (:expires-at t)}
                            (if (:expires-at t) (expiry-text (:expires-at t) (:expired? t)) "—"))
                         ($ :td (token-status-tag
                                 (cond (= :revoked (:status t)) :revoked
                                       (:expired? t)            :expired
                                       :else                    :active)))
                         ($ :td.act
                            (when (and (= :active (:status t))
                                       (contains? editable (:graph-id t)))
                              ($ :button.btn
                                 {:title "Revoke this key on this instance"
                                  :on-click #(api/revoke-self-test-token!
                                              (:token-id t)
                                              {:on-success (fn [_] (reload!))
                                               :on-error set-error!})}
                                 "Revoke")))))))
              (when (empty? shown) ($ :p.hint "No active graph token."))
              ($ ledger-filter-toggle {:all rows :show-all? show-all? :pred graph-token-active?
                                       :on-toggle set-show-all!})))))))

(defui workspace-tokens-page
  "The workspace's two key ledgers — one page, and never one form (8.4.11 ①).

   They are here together because «how many keys are out» is a question about
   the workspace, not about a graph; they stay apart within it because the two
   authorities must not be interchangeable (D3.12). What keeps that true is not
   a border: it is that only ONE of them can be issued from this screen. The
   other's issue panel is on the graph it belongs to, so no screen in Studio
   ever asks a person to pick which kind of key to mint."
  [{:keys [workspace role on-open-graph]}]
  ($ :main.workspace-tokens-page
     ($ :header.page-header
        ($ :div.page-header-row ($ :h2 "Tokens"))
        ($ :p.page-desc
           (str "Every key that opens something in " workspace ". ")
           "A workspace key edits the model over the Builder's MCP endpoint; a graph key
            reads one graph's data on the data plane. They are separate ledgers and
            presenting either at the other's door is refused — the prefix (\"oaw_\" or
            \"oat_\") says which one you are holding."))
     ($ :div.token-ledgers
        ($ workspace-token-panel {:can-edit? (contains? #{:owner :editor} role)})
        ($ graph-token-ledger {:on-open on-open-graph}))))

(defn- cut-by-label
  "`:version/cut-by` is a **printed principal map** — the version registry keeps
   the whole `{:issuer … :subject …}` so an audit can tell two issuers with the
   same subject apart. A row wants the half a person recognises; the full value
   stays in the tooltip, because shortening it in the only place it is shown
   would make the registry look like it recorded less than it did."
  [s]
  (or (second (re-find #":subject\s+\"([^\"]+)\"" (str s))) (str s)))

(defn- short-subject
  "A subject as a row shows it: a UUID (a local account's subject) is cut to
   its first block — enough to tell two apart at a glance, with the whole value
   in the tooltip; anything else (an email, a name) is shown as it is."
  [subject]
  (if (re-matches #"[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}" (str subject))
    (str (subs subject 0 8) "…")
    subject))

(defn- bundle-href
  "The download URL for one cut version's delivery bundle (D4.13).

   A plain same-origin URL rather than anything assembled in JavaScript: the
   session cookie carries the request, the server names the file in
   `Content-Disposition`, and a blob built here would only be something the
   browser has to undo."
  [graph version]
  (str "/api/studio/graphs/export?graph=" (js/encodeURIComponent graph)
       "&version=" version))

(defn- reclaim-blocked-reason
  "Why a version cannot be removed now (R3.5), as one sentence; nil when it can."
  [{:keys [reclaimable? blockers]}]
  (when-not reclaimable?
    (let [{:keys [serving pinned-tokens referenced-by uninspectable]} blockers]
      (cond
        (nil? blockers) "Reclaim status unavailable — refresh"
        serving "Cannot remove: this version is serving"
        (seq pinned-tokens) (str "Cannot remove: pinned by active key"
                                 (when (next pinned-tokens) "s") " "
                                 (str/join ", " pinned-tokens))
        (seq referenced-by) (str "Cannot remove: referenced by "
                                 (str/join ", " (map #(str (:graph %) " v" (:version %)) referenced-by)))
        uninspectable (str "Cannot remove: " uninspectable)
        :else "Cannot remove this version now"))))

(defui graph-runtime
  "Publishing this graph and the versions that produced.

   This was a `<details>` labelled «local runtime · publish and self-test
   tokens», shut by default — so the page said nothing about whether the graph
   was serving anything, and publish was a button that left no trace: press it,
   get a version number in a sentence, and the sentence was gone on the next
   render. Meanwhile `serving_status` and `rollback_graph` had been reachable by
   an **agent** over MCP since 8.4 P3, with no way for a person to reach either.
   The asymmetry was the bug; this panel is not new capability, it is the same
   service functions given a door on this side.

   Two things in the order they get asked about: what is live, and what could be
   made live. **«Who may read it» moved out** — the key ledger is its own tab in
   the console now, because it is a different job with a different reader, and
   stacking it under the version list meant neither had room.

   Publishing stays a round trip rather than a shortcut — the recipe is cut onto
   the Builder's own shelf and the bytes are then **sent into** the runtime's,
   through the same six steps a remote Server gets. That is what makes «what I
   self-tested is what production will do» true rather than hopeful."
  [{:keys [graph graph-id data-port data-scheme can-edit? on-publish known-serving archived? blocked?]}]
  (let [[busy? set-busy!]      (uix/use-state false)
        [checking-publication? set-checking-publication!] (uix/use-state false)
        [activating set-activating!] (uix/use-state nil)
        [all-versions? set-all-versions!] (uix/use-state false)
        [error set-error!]     (uix/use-state nil)
        [data set-data!]       (uix/use-state nil)
        ;; A SECOND request, deliberately: the cut check projects and
        ;; health-checks (most of a cut minus the seal), and folding it into the
        ;; version read would make listing versions pay for it.
        [check set-check!]     (uix/use-state nil)
        ;; What the last publish did, kept until dismissed. Publishing used to
        ;; leave a sentence that the next render threw away; what a person wants
        ;; in that moment is the artifact it just produced, and hunting for its
        ;; row in the list below is a step nobody should have to take.
        [published set-published!] (uix/use-state nil)
        reload! (uix/use-callback
                 (fn []
                   ;; Both reads sit behind the same guard publishing does, so
                   ;; for a viewer they can only refuse. Asking anyway painted a
                   ;; panel full of failures over a graph that is perfectly
                   ;; fine — «you may not» told as «something is broken». The
                   ;; callback still exists for every caller; it just has
                   ;; nothing to fetch.
                   (when can-edit?
                     (api/graph-versions! graph {:on-success set-data!
                                                 :on-error set-error!})
                     (set-check! nil)
                     (api/graph-cut-check! graph {:on-success set-check!
                                                  ;; A failed check must not read
                                                  ;; as a failed panel: the
                                                  ;; versions beside it are still
                                                  ;; true.
                                                  :on-error #(set-check! {:error %})})))
                 [graph can-edit?])
        ;; The row already knows what is serving — it is a column now — so a
        ;; viewer, who may not read the shelf, still gets a true answer here
        ;; instead of «nothing serving», which would be a lie told by an
        ;; absent permission.
        serving  (if data (:serving data) known-serving)
        ;; What the runtime actually holds, which is not the same as what was
        ;; cut: a version can be on the Builder's shelf and never have been sent
        ;; anywhere. Rolling back to one of those is a load, not just a pointer
        ;; move, and the runtime says so — this only makes the difference
        ;; visible before the button is pressed.
        on-shelf (set (:on-shelf data))
        readiness-tone (cond
                         (or archived? blocked? (not (:would-cut? check))) "bad"
                         (= :changed (get-in check [:release-status :state])) "warn"
                         :else "ok")]
    (uix/use-effect
      (fn []
        (reload!)
        (.addEventListener js/window "focus" reload!)
        (fn [] (.removeEventListener js/window "focus" reload!)))
      [reload!])
    ($ :section.graph-runtime
       ;; One row: the verdict with the facts that qualify it, then the two
       ;; actions. The serving version is in the graph's title badge already;
       ;; this row used to repeat it as a second badge, the status line a third
       ;; time, and the publish-result box a fourth.
       ($ :div.runtime-head
          (if (and can-edit? check (not (:error check)))
         (let [status (:release-status check)]
           ($ :div.publish-status {:aria-label "Publication readiness" :role "status"
                                   :class (str "publish-status--" readiness-tone)
                                   :title (str "Readiness applies to the saved model and configured Sources; Publish checks again before creating the release."
                                               (when (:saved-at check) (str " Last saved " (:saved-at check) ".")))}
              ($ :strong.ps-state
                 (cond archived? "Archived — restore before publishing"
                       blocked? "Saving the release name…"
                       (not (:would-cut? check)) "Not ready to publish"
                       (= :current (:state status)) "Published — no draft changes"
                       (= :changed (:state status)) "Unpublished changes — ready to publish"
                       (and (= :semantic-contract (:execution-target check))
                            (not (contains? #{:current :changed :unpublished} (:state status))))
                       "Validation passed — publication status unavailable"
                       :else "Ready to publish"))
              (when-let [revision (:config-revision check)]
                ($ :span.ps-meta (str "draft rev " revision
                                      (when (:model-label check) (str " · " (:model-label check))))))
              (when (:serving-version status)
                ($ :span.ps-meta (str "serving v" (:serving-version status)
                                      (when (:serving-draft-revision status)
                                        (str " from rev " (:serving-draft-revision status))))))
              (when (and (= :semantic-contract (:execution-target check)) (nil? status))
                ($ :span.ps-meta "published revision unavailable — Refresh"))))
            (if serving
              ($ :span.badge.badge--ok (str "serving v" serving))
              ($ :span.badge.badge--neutral "nothing serving")))
          ($ :span.spacer)
          (when can-edit?
            ($ :button.btn {:disabled busy? :on-click reload!} "Refresh status"))
          (when can-edit?
            ($ :button.btn.btn--primary
               {:disabled (or busy? (some? activating) archived? blocked? (not (:would-cut? check)) (:error check)
                               (= :current (get-in check [:release-status :state])))
                :title (if (= :semantic-contract (:execution-target check))
                         "Publish the saved model as a new version"
                         "Publish the saved model as a new version")
                :on-click (fn []
                            (set-busy! true) (set-error! nil) (set-published! nil)
                            (set-checking-publication! false)
                            (graph-actions/publish-graph!
                             graph (:config-revision check)
                             {:on-success (fn [r]
                                            (set-busy! false)
                                            (set-checking-publication! false)
                                            (set-published! r)
                                            (reload!)
                                            (when on-publish (on-publish r)))
                              :on-checking #(set-checking-publication! true)
                              :on-error (fn [e]
                                          (set-busy! false)
                                          (set-checking-publication! false)
                                          (set-error! e)
                                          (reload!))}))}
               (cond checking-publication? "Checking publication…"
                     busy? "publishing…"
                     (= :current (get-in check [:release-status :state])) "Already published"
                     :else "Publish to this instance"))))

       ($ mcp-connections/graph-connection {:graph-id graph-id :data-port data-port :data-scheme data-scheme
                                           :serving serving :archived? archived?})
       (if-not can-edit?
         ($ :p.hint
            "Publishing, version history and delivery bundles need editor on this graph.")
         ($ :<>
       (when archived? ($ :p.hint "Restore this Graph in Settings before publishing."))
       ;; «Would this cut» BEFORE the button that cuts. D2.2 is «free to write,
       ;; strict to seal», so the seal is the first place a modeller learns the
       ;; graph is not sealable — and until this read existed the only way to
       ;; ask was to press publish and read the refusal.
       (cond
         (nil? check) ($ :p.hint "Checking the saved model…")

         (:error check)
         ($ :p.hint.bad "Could not check the model. Version history is still available.")

         (:would-cut? check) nil

         :else
         ($ :div.cut-check-bad
            ($ :p.hint.bad
               (str (count (:health-findings check))
                    (if (= 1 (count (:health-findings check)))
                      " check refuses this graph — publishing would be rejected:"
                      " checks refuse this graph — publishing would be rejected:")))
            ($ :ul.finding-list
               (for [[i f] (map-indexed vector (:health-findings check))]
                 ($ :li {:key i}
                    ($ :code.mono (str (:code f)))
                    ($ :span " " (:message f))
                    (when (seq (:path f))
                      ($ :span.dim (str " · " (str/join " / " (map str (:path f)))))))))))
       (when error ($ findings-view {:error error}))
       (cond
         (nil? data) ($ :p.hint "Loading versions…")

         (empty? (:versions data))
         ($ :p.hint "No published versions yet.")

         :else
         ;; Every publish adds a row, so the list grows without bound. The
         ;; newest few and the serving one answer the everyday questions; the
         ;; rest is history, one click away.
         (let [versions (:versions data)
               shown    (if all-versions?
                          versions
                          (let [recent (set (map :version/number (take recent-version-count versions)))]
                            (filterv #(or (recent (:version/number %)) (= serving (:version/number %))) versions)))
               hidden   (- (count versions) (count shown))]
         ($ :<>
         ($ :ul.version-list
            (for [v shown]
              (let [n (:version/number v)]
                ;; The version a publish just produced is marked in the list
                ;; itself — where its Download already is — rather than in a
                ;; box above that repeated the row and had to be dismissed.
                ($ :li {:key n :class (str (when (= n serving) "serving")
                                           (when (= n (:version published)) " just"))}
                   ($ :span.v-num (str "v" n))
                   ($ :span.v-when (or (some-> (:version/cut-at v) (subs 0 16)) "—"))
                   ($ :span.v-who {:title (str (:version/cut-by v))}
                      (short-subject (cut-by-label (:version/cut-by v))))
                   (cond
                     (= n serving)          ($ :span.badge.badge--ok "serving")
                     (:version/reclaimed-at v)
                     ($ :span.tag {:title (str "Removed " (subs (str (:version/reclaimed-at v)) 0 16)
                                               " · its artifact no longer exists on this instance")}
                        "Removed")
                     :else ($ :span.tag "Previous version"))
                   (when (= n (:version published))
                     ($ :span.tag.tag--just
                        {:title (cond (:reconciled? published) "Verified serving · the saved draft is published"
                                      (:unchanged? published) "Already published · no new version was cut"
                                      :else "Created by the publish you just ran")}
                        (if (:unchanged? published) "already published" "just published")))
                   ;; A plain link, not a fetch: the session cookie rides along
                   ;; on a same-origin navigation, and a download assembled in
                   ;; JavaScript would only add a blob for the browser to undo.
                   (when-not (:version/reclaimed-at v)
                     ($ :a.btn.version-export
                        {:href (bundle-href graph n)
                         :title (str "Download v" n " as a delivery bundle (zip)")}
                        "Download"))
                   ;; **«activate», not «roll back».** In the runtime the two
                   ;; are one function (`pointer-move`): the only differences are
                   ;; which key the target comes from and which op the audit
                   ;; records. And the service accepts any cut version that is
                   ;; not already serving — including one NEWER than it, which
                   ;; is the ordinary state after a rollback. Labelling those
                   ;; «roll back» would be telling the reader they are going
                   ;; backwards while they go forwards; the panel already says
                   ;; which version is serving, so the direction is visible
                   ;; without the button claiming it.
                   ;;
                   ;; The target is still named explicitly and never «the
                   ;; previous one» — the evidence presented is THAT version's
                   ;; own plan (R3.6), which is why every row carries its own
                   ;; button rather than the panel carrying one «undo».
                   (when (and can-edit? (not= n serving) (not (:version/reclaimed-at v)))
                     ($ :button.btn
                        {:disabled (or archived? busy? (some? activating))
                         :title (str "Move the serving pointer to v" n
                                     " — its own deployment plan is what is re-presented")
                         :on-click (fn []
                                     (set-activating! n) (set-error! nil)
                                     (api/rollback-graph!
                                      {:graph graph :to-version n}
                                      {:on-success (fn [_]
                                                     (set-activating! nil) (reload!)
                                                     (when on-publish (on-publish nil)))
                                       :on-error (fn [e] (set-activating! nil) (set-error! e))}))}
                        (if (= activating n) "Switching…" "Use this version")))
                   ;; R3.5: enabled only when nothing on this instance still
                   ;; needs the version; otherwise the reason is the tooltip.
                   (when (and can-edit? (not (:version/reclaimed-at v)))
                     (let [reason (reclaim-blocked-reason (:version/reclaim v))]
                       ($ :button.btn.btn--danger
                          {:disabled (or archived? busy? (some? activating) (some? reason))
                           :title (or reason (str "Remove v" n "'s artifact from this instance"))
                           :on-click (fn []
                                       (when (js/confirm
                                              (str "Remove v" n " from this instance?\n\n"
                                                   "Its artifact files are deleted and cannot be restored here. "
                                                   "The version number is never reused; its record, audit and "
                                                   "deployment history stay. You will no longer be able to use "
                                                   "or download v" n "."))
                                         (set-activating! n) (set-error! nil)
                                         (api/reclaim-graph-version!
                                          {:graph graph :version n}
                                          {:on-success (fn [_] (set-activating! nil) (reload!))
                                           :on-error (fn [e] (set-activating! nil) (set-error! e) (reload!))})))}
                          "Remove")))))))
         (when (some #(get-in % [:version/reclaim :reclaimable?]) (drop 1 versions))
           ($ :button.linkish.token-filter
              {:disabled (or archived? busy? (some? activating))
               :title "Remove every version nothing on this instance still needs, keeping the newest one"
               :on-click (fn []
                           (when (js/confirm
                                  (str "Remove all unused versions of " graph " except the newest?\n\n"
                                       "Versions that are serving, pinned by an active key, or referenced "
                                       "by another graph are kept. Removed artifacts cannot be restored here."))
                             (set-activating! :all) (set-error! nil)
                             (api/reclaim-unused-graph-versions!
                              {:graph graph :keep 1}
                              {:on-success (fn [_] (set-activating! nil) (reload!))
                               :on-error (fn [e] (set-activating! nil) (set-error! e) (reload!))})))}
              "Remove unused versions (keep newest)"))
         (when (or all-versions? (pos? hidden))
           ($ :button.linkish.token-filter
              {:on-click #(set-all-versions! (not all-versions?))}
              (if all-versions? "Show recent versions only" (str "Show all " (count versions) " versions")))))))
       )))))

(defui recipe-form
  "Create and edit share a shape because they are the same act: naming a scope.
   The leading mark in the gutter is the only thing that differs."
  [{:keys [mark title name-value on-name selectors-value on-selectors
           submit-label on-submit on-cancel]}]
  ($ :form.plate.plate-form {:on-submit on-submit}
     ($ :div.plate-gutter ($ :span.plate-mark mark))
     ($ :div.plate-body
        ($ :p.plate-eyebrow title)
        ($ :div.recipe-fields
           (when on-name
             ($ :input.field-name {:value name-value :required true
                                   :placeholder "graph name"
                                   :aria-label "Graph name"
                                   :on-change #(on-name (.. % -target -value))}))
           (when on-selectors
             ($ :input.field-selectors {:value selectors-value :required true
                                        :placeholder "cis.sales.*, cis.master.customer"
                                        :aria-label "Selectors"
                                        :on-change #(on-selectors (.. % -target -value))}))
           ($ :button.btn.primary {:type "submit"} submit-label)
           (when on-cancel
             ($ :button.btn {:type "button" :on-click on-cancel} "Cancel"))))))


;; ---------------------------------------------------------------------------
;; Sources — the standalone "database → model" line
;; ---------------------------------------------------------------------------

(defui source-connect-form
  "Create a source directly from EDN; validation never registers it."
  [{:keys [on-connected on-cancel]}]
  (let [[text set-text!] (uix/use-state
                          "{:id \"sales-db\"\n :type \"sql\"\n :jdbc_url \"jdbc:postgresql://localhost:5432/sales\"\n :username \"${SALES_DB_USER}\"\n :password \"${SALES_DB_PASSWORD}\"}")
        [busy? set-busy!] (uix/use-state false)
        [error set-error!] (uix/use-state nil)
        connect! (fn []
                   (let [{:keys [value error]} (editor/parse-edn text)]
                     (cond
                       error (set-error! {:message error})
                       (not (map? value)) (set-error! {:message "Enter a source EDN map."})
                       :else
                       (do
                         (set-busy! true)
                         (set-error! nil)
                         (api/declare-source!
                          {:id (:id value) :entry (dissoc value :id) :apply? true}
                          {:on-success (fn [r]
                                         (set-busy! false)
                                         (if (:written? r)
                                           (on-connected (:source r) nil)
                                           (set-error! {:findings (:findings r)})))
                           :on-error (fn [e] (set-busy! false) (set-error! e))})))))]
    ($ :div.connect-card
       ($ :h3 "Connect a source")
       ($ :p.hint "Enter the source id and connection settings as EDN. Connect validates the entry and connection before registering it. Use ${VARIABLE_NAME} for credentials from Workspace → Variables.")
       ($ editor/edn-editor {:text text :on-change (fn [v] (set-text! v) (set-error! nil))
                            :aria-label "source EDN" :line-numbers? true
                            :height "auto" :max-height "70vh" :theme @theme/current})
       ($ :div.asset-editor-actions
          ($ :button.btn.primary {:disabled busy? :on-click connect!}
             (if busy? "Connecting…" "Connect"))
          ($ source-connection-check {:key text :text text :disabled? busy?})
          (when on-cancel ($ :button.btn {:on-click on-cancel} "Cancel")))
       (when error ($ findings-view {:error error})))))

(defui sources-page
  "The register of this workspace's sources: what is declared, its entry, and
   taking one out.

   **One thing per side** (owner, 2026-09-05). The list says which connections
   exist; the pane beside it is the selected one's ENTRY, in EDN, editable and
   saveable. Model creation starts from a Semantic Contract import or the
   explicit manual authoring tools.

   **Why EDN and not a form.** A source's connection keys are its connector
   contract's (D2.15) and the pool cannot enumerate them: the form can draw
   `:type`, `:label` and `:description`, and every api-v1 connection key but
   two has no box — a key with no box looks exactly like a key that is not
   needed, which is how `:ssl_insecure?` went missing for an afternoon. The
   editor therefore opens in EDN for a source (8.4.24 裁定十八); there is no form toggle.

   **Removing is the pool's judgement, not this page's.** A source anything
   still reads is refused with the list of what reads it
   (`:pool.reference/inbound`), and the plan is fetched and shown before a byte
   is written — so «why can I not remove this» is answered by the same
   machinery that would have refused the write."
  [{:keys [workspace can-edit? on-model-change on-open-graph graph schema]}]
  (let [[sources set-sources!] (uix/use-state nil)
        ;; The selected source id, or `:connect` while the card is open. A
        ;; reload comes back to the source that was open (owner, 2026-09-05);
        ;; the connect card is not remembered — a half-typed card is not a
        ;; place, and coming back into it would be a surprise.
        [picked set-picked!]   (uix/use-state (ui-recall-in [:sources workspace]))
        _ (uix/use-effect
           (fn []
             (ui-remember! :sources (assoc (or (ui-recall-in [:sources]) {})
                                           workspace (when (string? picked) picked)))
             js/undefined)
           [workspace picked])
        ;; Carried past the connect card so the ask does not vanish with the
        ;; form that made it.
        [note set-note!]       (uix/use-state nil)
        ;; The source whose row «remove» was pressed: its pane opens with the
        ;; removal plan already fetched (owner 2026-10-04).
        [removing set-removing!] (uix/use-state nil)
        reload! (uix/use-callback
                 (fn []
                   (api/sources! {:on-success #(set-sources! (vec (:sources %)))
                                  :on-error #(js/console.error (pr-str %))}))
                 [])
        current (when (string? picked)
                  (first (filter #(= picked (:id %)) sources)))]
    (uix/use-effect (fn [] (reload!) js/undefined) [reload!])
    ($ :div.sources-page
       (when (seq note)
         ($ :p.hint.sources-note
            (str "Connected. Credentials are held on this Studio only — in production a Server "
                 "administrator configures " (str/join ", " note) ".")))
       ($ :div.sources-body
          ($ :div.sources-register
             ($ :div.pane-head
                ($ :span "Sources")
                ($ :span.spacer)
                (when can-edit?
                  ($ :button.btn.primary {:on-click #(set-picked! :connect)} "+ connect")))
             (cond
               (nil? sources) ($ :p.empty-note "reading the register…")
               (empty? sources) ($ :p.empty-note "No source is declared yet. Connecting a database is how one arrives.")
               :else
               ($ :ul.source-plates
                  (for [src sources]
                    ($ :li.source-plate
                       {:key (:id src)
                        :class (when (= picked (:id src)) "on")}
                       ;; The whole plate selects it. There is one thing to do
                       ;; with a source here — read its entry, and change it —
                       ;; so a separate «edit» beside the face was a second
                       ;; control for the same gesture.
                       ($ :button.plate-face
                          {:type "button"
                           :aria-pressed (= picked (:id src))
                           :on-click #(set-picked! (:id src))}
                          ($ :span.plate-id (source-summary/label src))
                          (when (not= (:id src) (source-summary/label src))
                            ($ :span.plate-sub.mono (:id src)))
                          ($ :span.plate-meta
                             ($ :span.tag (:type src))
                             (when (seq (:credential-keys src))
                               ($ :span.tag.warn (str (count (:credential-keys src)) " credential")))
                             (when-let [w (:workspace src)] ($ :span.tag w)))
                          ($ :span.plate-sub (source-summary/usage-text src)))
                       (when can-edit?
                         ($ :button.btn.tiny.plate-remove
                            {:type "button" :title (str "Remove " (:id src) "…")
                             :on-click #(do (set-picked! (:id src)) (set-removing! (:id src)))}
                            "remove")))))))
          ($ :div.sources-stage
             (cond
               (= picked :connect)
               ($ source-connect-form
                  ;; Fresh each time the card is opened: a form still holding the
                  ;; last database's URL is one connection string away from
                  ;; registering the wrong thing under a new name.
                  {:key "connect"
                   :on-cancel #(set-picked! nil)
                   ;; Connecting lands on the new source's EDN entry.
                   :on-connected (fn [id vars]
                                   (reload!)
                                   (when (seq vars) (set-note! vars))
                                   (set-picked! id))})

               ;; Owner 2026-10-04: the removal panel takes the editor's
               ;; place until Close / Cancel, rather than stacking under it.
               (and current (= removing (:id current)))
               ($ source-removal-panel
                  {:key (str "rm-" (:id current))
                   :source (:id current)
                   :on-open-graph on-open-graph
                   :on-close #(set-removing! nil)
                   :on-removed (fn []
                                 (set-removing! nil)
                                 (set-picked! nil)
                                 (reload!)
                                 (when on-model-change (on-model-change)))})

               current
               ($ :div.source-view {:key (:id current)}
                  ($ :div.pane-head
                     ($ :span (source-summary/label current))
                     (when (not= (:id current) (source-summary/label current))
                       ($ :span.mono.dim (:id current)))
                     ($ :span.tag (:type current))
                     ($ :span.spacer)
                     ($ :span.count (source-summary/usage-text current))
                     (when can-edit?
                       ($ :button.btn.tiny {:type "button" :on-click #(set-removing! (:id current))}
                          "remove…")))
                  ($ :div.source-view-body
                     (if can-edit?
                       ($ asset-editor {:type :source :id (:id current)
                                        :schema schema :graph graph
                                        :on-close #(set-picked! nil)
                                        :on-saved (fn [_]
                                                    (reload!)
                                                    (when on-model-change (on-model-change)))})
                       ($ :p.hint "Editing a source needs editor on this workspace."))))

               :else
               ($ :div.sources-empty
                  ($ :p.hint "A source is a database or an HTTP api this workspace may read. Pick one to read and edit what it declares."))))))))

(defui graph-row
  "One graph as a register row.

   A row, not a plate: the questions asked in this view are comparative — which
   is newest, which holds the most, which nobody reads — and comparison wants
   columns.

   **Picking a row opens that graph in the console.** It used to expand a panel
   below the whole table while the checkbox beside it selected for a merge:
   one row, two selection models, nothing to tell them apart. The checkbox stays
   because merging really is an act on a set; the row now has exactly one
   meaning."
  [{:keys [graph workspace owner? picked? on-pick on-open-graph]}]
  (let [{:graph/keys [qualified-name held created-at state serving cut-versions
                      last-cut-at live-keys grants]} graph
        holds (mapv #(or (get held %) 0) [:namespaces :attributes :resolvers :links])]
    ($ :tr {:class (str (name state) (when picked? " picked"))
            :on-click (fn [e]
                        (when-not (.closest (.-target e) ".pick")
                          (on-open-graph qualified-name)))}
       ($ :td.pick
          (when owner?
            ($ :input {:type "checkbox" :checked picked?
                       :aria-label (str "select " qualified-name " for merging")
                       :on-change #(on-pick (.. % -target -checked))})))
       ($ :td
          ($ :div.g-name
             ($ :span.linkish qualified-name)
             ;; A recipe that selects no resolver holds nothing anybody can
             ;; query, and looks identical to a working graph in every other
             ;; column — four zeroes read as «small», not as «empty».
             (when (zero? (nth holds 2))
               ($ :span.badge.badge--warn
                  {:title "This recipe selects no resolver — nothing can be queried from it"}
                  "empty")))
          ($ :div.g-sub (graph-label qualified-name workspace)))
       ($ :td ($ :span {:class (str "badge badge--"
                                    (if (= state :archived) "neutral" "ok"))}
                 (name state)))
       ($ :td.g-serving
          (if serving
            ($ :span.badge.badge--ok (str "v" serving))
            ($ :span.dim "—"))
          (when (pos? (or cut-versions 0))
            ($ :div.g-sub (str cut-versions " cut"))))
       ;; **One cell, not four.** The four counts existed to answer one question
       ;; — is this graph empty or working — and three of the columns were the
       ;; cost of answering it. The breakdown is still here, in the order the
       ;; header names, for the reader who wants it.
       ($ :td.num.g-holds
          {:title (str (nth holds 0) " entities · " (nth holds 1) " attributes · "
                       (nth holds 2) " resolvers · " (nth holds 3) " links")}
          (if (zero? (reduce + holds))
            ($ :span.dim "—")
            (str/join " · " holds)))
       ($ :td.g-consumers
          (if (pos? (+ (or live-keys 0) (or grants 0)))
            ($ :<>
               (when (pos? (or live-keys 0))
                 ($ :span.tag {:title "active data-plane keys for this graph"}
                    (str live-keys " key" (when (not= 1 live-keys) "s"))))
               (when (pos? (or grants 0))
                 ($ :span.tag {:title "explicit per-graph grants"}
                    (str grants " grant" (when (not= 1 grants) "s")))))
            ($ :span.dim "—")))
       ($ :td.dim {:title (str "created " (or (some-> created-at (subs 0 10)) "—"))}
          (or (some-> last-cut-at (subs 0 10)) "—")))))

(defui sortable-th
  "A column header that sorts. Clicking the active column flips direction —
   there is no third state, because «unsorted» is not a thing a reader ever asks
   a table to go back to."
  [{:keys [col label title num? sort-state on-sort]}]
  (let [[active dir] sort-state
        on? (= col active)]
    ($ (if num? :th.num :th)
       {:title title :aria-sort (when on? (if (= :asc dir) "ascending" "descending"))}
       ($ :button.th-sort
          {:type "button"
           :class (when on? "on")
           :on-click #(on-sort [col (if (and on? (= :asc dir)) :desc :asc)])}
          label
          ($ :span.th-arrow (if (and on? (= :desc dir)) "\u25BE" "\u25B4"))))))

(defui graph-rail-item
  "One graph in the console rail: a status dot, the name, and one line saying
   what state it is in.

   The caption is not decoration — it is the whole of what direction C proposed
   as a banner, moved to where the eye already goes."
  [{:keys [graph selected? on-select]}]
  (let [{:keys [tone caption]} (table/graph-status graph)
        qn (:graph/qualified-name graph)]
    ($ :button.rail-item
       {:type "button"
        :class (str (when selected? "on") " tone-" (name tone))
        :aria-current (when selected? "true")
        :on-click #(on-select qn)}
       ($ :span.rail-top
          ($ :span {:class (str "rail-dot tone-" (name tone))
                    :aria-hidden true})
          ($ :span.rail-name {:title qn} (table/display-name graph)))
       ($ :span.rail-caption caption))))

(defui graph-rename [{:keys [graph on-rename on-cancel]}]
  (let [[value set-value!] (uix/use-state (table/display-name graph))
        [busy? set-busy!] (uix/use-state false)
        [error set-error!] (uix/use-state nil)]
    ($ :section.graph-settings
       ($ :h3 "Rename Graph")
       ($ :p.hint "Choose the name shown in Studio. The graph UUID, MCP URL, access tokens and published versions stay the same.")
       ($ :form
          {:on-submit (fn [event]
                        (.preventDefault event)
                        (when (and (not busy?) (not (str/blank? value)))
                          (set-busy! true) (set-error! nil)
                          (on-rename {:graph (:graph/qualified-name graph) :name (str/trim value)}
                                     (fn [] (set-busy! false) (on-cancel))
                                     (fn [err] (set-busy! false) (set-error! err)))))}
          ($ :label "Graph name"
             ($ :input {:aria-label "Graph name" :value value :max-length 128
                        :disabled busy? :auto-focus true
                        :on-change #(set-value! (.. % -target -value))}))
          (when error ($ findings-view {:error error}))
          ($ :div.asset-editor-actions
             ($ :button.btn.primary {:type "submit" :disabled (or busy? (str/blank? value)
                                                                              (= (str/trim value) (table/display-name graph)))}
                (if busy? "Saving…" "Save name"))
             ($ :button.btn {:type "button" :disabled busy? :on-click on-cancel} "Cancel"))))))

(defui graph-settings
  "Metadata and lifecycle controls for one Graph. Contract 1.4 assets have one
   owner, so prefix-based membership editing is deliberately absent."
  [{:keys [graph landing? mode on-dirty-change on-set-landing lifecycle-busy? on-set-package-name on-duplicate on-delete on-archive on-restore]}]
  (let [{:graph/keys [qualified-name cut-versions can-edit? can-manage? state serving live-keys]} graph
        [package-name set-package-name!] (uix/use-state (or (:graph/package-name graph) ""))
        [saved-package set-saved-package!] (uix/use-state (or (:graph/package-name graph) ""))
        [saving? set-saving!] (uix/use-state false)
        ;; The name in force, from the backend's one rule (`package-name-for`):
        ;; the graph's own, or the default. nil only when no name can be made.
        release-name (:graph/release-name graph)
        [editing-package? set-editing-package!] (uix/use-state (nil? release-name))
        [landing-busy? set-landing-busy!] (uix/use-state false)
        [preview-attempt set-preview-attempt!] (uix/use-state 0)
        [saved? set-saved!] (uix/use-state false)
        [confirm-delete? set-confirm-delete!] (uix/use-state false)
        [delete-name set-delete-name!] (uix/use-state "")
        [deleting? set-deleting!] (uix/use-state false)
        dirty? (not= (str/trim package-name) saved-package)
        [copy-name set-copy-name!] (uix/use-state "")
        [copy-namespace set-copy-namespace!] (uix/use-state "")
        [namespace-edited? set-namespace-edited!] (uix/use-state false)
        [copying? set-copying!] (uix/use-state false)
        [copy-plan set-copy-plan!] (uix/use-state nil)
        [previewing? set-previewing!] (uix/use-state false)
        section-ref (uix/use-ref nil)
        leave-target (uix/use-ref nil)
        allow-leave (uix/use-ref false)
        [leaving? set-leaving!] (uix/use-state false)
        [error-section set-error-section!] (uix/use-state nil)
        [copy-result set-copy-result!] (uix/use-state nil)
        copy-valid? (and (seq (str/trim copy-name))
                         (not (str/includes? copy-name "/"))
                         (re-matches #"[A-Za-z][A-Za-z0-9_.-]*" (str/trim copy-namespace)))
        [error set-error!] (uix/use-state nil)
        busy? (or saving? copying? deleting? lifecycle-busy? landing-busy?)
        unsaved? (or dirty? (not (str/blank? copy-name)) (not (str/blank? copy-namespace)))
        cut (or cut-versions 0)
        archived? (= :archived state)
        live-keys (or live-keys 0)]
    (uix/use-effect (fn [] (when on-dirty-change (on-dirty-change (or dirty? saving?)))) [dirty? saving? on-dirty-change])
    (uix/use-effect
     (fn []
       (let [leaving (fn [event]
                       (when (or unsaved? busy?)
                         (.preventDefault event)
                         (set! (.-returnValue event) "")))
             navigate (fn [event]
                        (when (and (or unsaved? busy?)
                                   (not @allow-leave)
                                   (not (.contains @section-ref (.-target event)))
                                   (.closest (.-target event) "button, a, select, [role=tab]")
                                   (or busy?
                                       (.closest (.-target event) "a, select, .shell-navigation button, .rail-item, .pane-tab, .view-switch button, [data-settings-navigation]")))
                          (.preventDefault event)
                          (.stopPropagation event)
                          (when-not busy?
                            (reset! leave-target (.closest (.-target event) "button, a, select, [role=tab]"))
                            (set-leaving! true))))
             navigate-key (fn [event]
                            (when (and (= "SELECT" (.-tagName (.-target event)))
                                       (contains? #{"ArrowDown" "ArrowUp" "Home" "End" "Enter" " "} (.-key event)))
                              (navigate event)))]
         (.addEventListener js/window "beforeunload" leaving)
         (.addEventListener js/document "click" navigate true)
         (.addEventListener js/document "keydown" navigate-key true)
         (fn []
           (.removeEventListener js/window "beforeunload" leaving)
           (.removeEventListener js/document "click" navigate true)
           (.removeEventListener js/document "keydown" navigate-key true))))
     [unsaved? busy?])
    (uix/use-effect
     (fn []
       (set-previewing! false)
       (set-copy-plan! nil)
       (when (and (= mode :duplicate) copy-valid?)
         (let [active? (atom true)
               timer (js/setTimeout
                      (fn []
                        (set-previewing! true)
                        (set-error-section! :duplicate)
                        (set-error! nil)
                        (api/duplicate-graph-plan!
                         {:graph qualified-name :name (str/trim copy-name) :namespace (str/trim copy-namespace)}
                         {:on-success #(when @active? (set-previewing! false) (set-copy-plan! %))
                          :on-error #(when @active? (set-previewing! false) (set-error! %))})) 400)]
           (fn [] (reset! active? false) (js/clearTimeout timer)))))
     [mode qualified-name copy-name copy-namespace copy-valid? preview-attempt])
    ($ :section.graph-settings {:ref section-ref}
       (when leaving?
         ($ :div.settings-leave-confirmation {:role "alertdialog" :aria-label "Unsaved Graph Settings"}
            ($ :p "Discard unsaved Graph Settings changes and leave?")
            ($ :div.setting-actions
               ($ :button.btn {:auto-focus true :on-click #(do (set-leaving! false) (reset! leave-target nil))} "Keep editing")
               ($ :button.btn.btn--danger
                  {:on-click #(let [target @leave-target]
                                (set-leaving! false)
                                (set-package-name! saved-package)
                                (set-copy-name! "") (set-copy-namespace! "")
                                (set-copy-plan! nil) (set-namespace-edited! false)
                                (reset! allow-leave true)
                                (.click target)
                                (reset! allow-leave false))}
                  "Discard and leave"))))
       (when busy? ($ :p.hint {:role "status"} "Operation in progress. Please wait before leaving Settings."))
       (when-not (or can-edit? can-manage?)
         ($ :p.hint "Graph Settings require Graph editor or Workspace owner access."))
       (when (and (= mode :publish) can-edit?)
         ($ :div.release-name
            ($ :span.rn-label "Release name")
            (if-not editing-package?
              ($ :<>
                 ($ :code.rn-value (cond (seq saved-package) saved-package
                                         release-name release-name
                                         :else "—"))
                 (when (and (str/blank? saved-package) release-name)
                   ($ :span.tag {:title "Nobody named this release, so the default is used. Edit to publish under another name."} "default"))
                 ($ :button.btn.tiny {:type "button" :disabled busy? :on-click #(set-editing-package! true)
                                      :title "Used by future published versions; existing versions keep their name"}
                    "Edit")
                 (when saving? ($ :span.hint {:role "status"} "saving…")))
              ($ :form.rn-form
                 {:on-submit (fn [event]
                               (.preventDefault event)
                               (when (and dirty? (not busy?))
                                 (let [typed (str/trim package-name)
                                       ;; Emptied clears it: the default applies again.
                                       value (when (seq typed) typed)]
                                   (set-error-section! :package)
                                   (set-error! nil)
                                   (set-saving! true)
                                   (set-saved! false)
                                   (on-set-package-name
                                    {:graph qualified-name :package-name value}
                                    (fn [err] (set-saving! false) (set-error! err))
                                    (fn []
                                      (set-saving! false)
                                      (set-saved-package! (or value ""))
                                      (set-package-name! (or value ""))
                                      (set-saved! true)
                                      (set-editing-package! false))))))}
                 ($ :input {:value package-name :spell-check false :disabled busy?
                            :placeholder (str (or release-name "open-atlas/orders") " (empty = default)")
                            :aria-label (str "Release name for " qualified-name)
                            :on-change #(do (set-package-name! (.. % -target -value))
                                            (set-saved! false))})
                 ($ :button.btn {:type "submit" :class (when dirty? "btn--primary")
                                 :disabled (or (not dirty?) busy?)}
                    (if saving? "Saving…" "Save"))
                 (when release-name
                   ($ :button.btn {:type "button" :disabled busy?
                                   :on-click #(do (set-package-name! saved-package) (set-editing-package! false))}
                      "Cancel"))))
            (when (and error (= error-section :package)) ($ findings-view {:error error}))))
       (when (and (= mode :duplicate) can-manage?)
         ($ :div.setting
            ($ :h3 "Create a copy")
            (when (and error (= error-section :duplicate)) ($ findings-view {:error error}))
            (when (and error (= error-section :duplicate))
              ($ :button.btn {:disabled (or busy? previewing?) :on-click #(set-preview-attempt! inc)} "Retry preview"))
            ($ :p.hint "Copies the model. Connections, access tokens, and running state must be configured separately.")
            ($ :details
               ($ :summary "Copy details")
            ($ :dl.duplicate-scope
               ($ :div ($ :dt "Copies")
                  ($ :dd "Contract 1.4 draft assets into the new namespace. Legacy Graphs copy their recipe references."))
               ($ :div ($ :dt "Keeps references")
                  ($ :dd "External Graph dependencies, external Terms, and logical Source Slots."))
               ($ :div ($ :dt "Configure separately")
                  ($ :dd "Physical Source configuration and credentials, releases, tokens, and serving are not copied. Contract binding definitions are copied."))))
            ($ :form.duplicate-graph-form
               {:on-submit #(.preventDefault %)}
               ($ field-row {:label "Graph name" :hint "Name inside this Workspace; must not contain /"
                             :children ($ :input {:value copy-name :required true :spell-check false
                                                  :aria-label "Duplicate Graph name" :disabled busy?
                                                  :pattern "[^/]+" :placeholder "orders-sandbox"
                                                  :on-change #(let [value (.. % -target -value)]
                                                                (set-copy-name! value)
                                                                (set-copy-result! nil)
                                                                (set-copy-plan! nil)
                                                                (when-not namespace-edited?
                                                                  (set-copy-namespace! (fl/duplicate-namespace value))))})})
               ($ :details ($ :summary "Advanced: asset namespace")
               ($ field-row {:label "Asset namespace" :hint "Starts with a letter; then letters, numbers, underscores, dots, or hyphens. Generated until you edit it."
                             :children ($ :input {:value copy-namespace :required true :spell-check false
                                                  :aria-label "Duplicate asset namespace" :disabled busy?
                                                  :pattern "[A-Za-z][A-Za-z0-9_.\\-]*"
                                                  :placeholder "orders-sandbox"
                                                  :on-change #(do (set-copy-plan! nil)
                                                                  (set-namespace-edited! true)
                                                                  (set-copy-result! nil)
                                                                  (set-copy-namespace! (.. % -target -value)))})})
))
            (when copy-plan
              ($ :div.duplicate-plan
                 ($ :h4 "This copy will contain")
                 ($ :p ($ :code (:target copy-plan)) " · source revision " (:source-revision copy-plan))
                 ($ :ul (for [[kind n] (sort-by (comp name key) (:counts copy-plan))]
                          ($ :li {:key (name kind)} (str n " " (name kind)
                                                       (when (= :legacy (:mode copy-plan)) " recipe references")))))
                 ($ :p "External Graph dependencies: " (count (:dependencies copy-plan)))
                 ($ :p "External Terms kept as references: " (count (:external-terms copy-plan)))
                 (when (seq (:dependencies copy-plan))
                   ($ :ul (for [dependency (:dependencies copy-plan)]
                            ($ :li {:key (pr-str dependency)} ($ :code (pr-str dependency))))))
                 (when (seq (:external-terms copy-plan))
                   ($ :ul (for [term (:external-terms copy-plan)]
                            ($ :li {:key term} ($ :code term)))))
                 ($ :p "Source Slots to configure: " (if (seq (:source-slots copy-plan))
                                                       (str/join ", " (:source-slots copy-plan)) "None"))
                 ($ :button.btn.btn--primary
                    {:disabled busy?
                     :on-click #(when-not busy?
                                  (set-copying! true)
                                  (set-error-section! :duplicate)
                                  (set-error! nil)
                                  (on-duplicate {:graph qualified-name :name (str/trim copy-name)
                                                 :namespace (str/trim copy-namespace)
                                                 :plan-token (:plan-token copy-plan)}
                                                (fn [err] (set-copying! false) (set-copy-plan! nil) (set-error! err))
                                                (fn [result]
                                                  (set-copying! false)
                                                  (set-copy-plan! nil)
                                                  (set-copy-result! (get-in result [:graph :graph/qualified-name]))
                                                  (set-copy-name! "") (set-copy-namespace! "")
                                                  (set-namespace-edited! false))))}
                    (if copying? "Duplicating…" "Create copy"))))
            ($ :p.hint {:role "status" :aria-live "polite"}
               (cond copying? "Creating copy…"
                     previewing? "Checking the copy…"
                     copy-result (str "Created Graph " copy-result ". Select it from the Graph list to continue.")
                     :else nil))))
       (when (and (= mode :delete) can-manage?)
         ($ :div.setting.lifecycle
            (when archived?
            ($ :div.setting-delete
               ($ :h4 "Permanent deletion")
               (when (and error (= error-section :delete)) ($ findings-view {:error error}))
               ($ :p.hint (if archived?
                            "Permanently delete this Graph. This cannot be undone."
                            "Archive this Graph using the button above before deleting it."))
               (if (and archived? confirm-delete?)
                 ($ :form.delete-confirmation
                    {:aria-label "Confirm permanent Graph deletion"
                     :on-submit (fn [event]
                                  (.preventDefault event)
                                  (when (and archived? (= delete-name qualified-name) (not busy?))
                                    (set-error-section! :delete)
                                    (set-deleting! true)
                                    (set-error! nil)
                                    (on-delete {:graph qualified-name}
                                               (fn [err] (set-deleting! false) (set-error! err))
                                               (fn [] (set-deleting! false) (set-confirm-delete! false)))))}
                    ($ :p "Deleting " ($ :code qualified-name) " will:")
                    ($ :ul
                       ($ :li "Delete its Semantic draft assets and edit history.")
                       ($ :li (str "Delete " cut " cut-version records and their local artifact copies."))
                       (when (some? serving) ($ :li (str "Stop serving version " serving ".")))
                       (when (pos? live-keys) ($ :li (str "Revoke " live-keys " active data-plane keys."))))
                    ($ :p.hint "Deletion is blocked if another retained Graph artifact references this Graph. Audit/deployment evidence, shared legacy assets, external Graphs, and Source instances are retained.")
                    ($ :label {:html-for "delete-graph-name"}
                       "Type " ($ :code qualified-name) " to confirm. This cannot be undone.")
                    ($ :input {:id "delete-graph-name" :value delete-name :required true
                               :auto-complete "off" :spell-check false :disabled deleting?
                               :on-change #(set-delete-name! (.. % -target -value))})
                    ($ :div.setting-actions
                       ($ :button.btn {:type "button" :disabled deleting?
                                       :on-click #(do (set-confirm-delete! false) (set-delete-name! ""))}
                          "Cancel")
                       ($ :button.btn.btn--danger {:type "submit"
                                                  :disabled (or busy? (not= delete-name qualified-name))}
                          (if deleting? "Deleting…" "Permanently delete Graph"))))
                 ($ :button.btn.btn--danger
                    {:disabled (or busy? (not archived?))
                     :on-click #(do (set-delete-name! "") (set-confirm-delete! true))}
                    "Delete Graph…")))))))))

(defui graph-model [{:keys [graph can-edit? theme explorer? initial-selection on-edit]}]
  (let [[view set-view!] (uix/use-state :explore)
        [dirty? set-dirty!] (uix/use-state false)
        switch! #(do (set-dirty! false) (set-view! %))]
    ($ :section.graph-model
       (when-not explorer? ($ :div.model-view-switch
          ($ :button.pane-tab {:class (when (= view :explore) "on") :on-click #(switch! :explore)} "Objects")
          ($ :button.pane-tab {:class (when (= view :edn) "on") :on-click #(switch! :edn)} "Import/Export")
          ($ :button.pane-tab {:class (when (= view :guide) "on") :on-click #(switch! :guide)} "Usage guide")))
       ($ navigation/guarded-content {:dirty? dirty?}
          (if (= view :edn)
            ($ semantic-editor {:graph graph :can-edit? can-edit? :on-dirty-change set-dirty!})
            ($ contract-editor/graph-editor
               {:key view :graph graph :can-edit? can-edit? :theme theme :explorer? explorer? :guide? (= view :guide) :initial-selection initial-selection
                :on-definition (if explorer? on-edit #(switch! :edn))
                :on-dirty-change set-dirty!}
               ($ graph-things-tab {:graph graph :can-edit? can-edit? :on-open-definition #(switch! :edn)})))))))

(defui graph-connections [{:keys [graph can-edit? schema]}]
  (let [[creating? set-creating!] (uix/use-state false)
        [created set-created!] (uix/use-state nil)
        [epoch set-epoch!] (uix/use-state 0)
        finish! (fn [id] (set-created! id) (set-creating! false) (set-epoch! inc))]
    (if creating?
      ($ source-connect-form {:schema schema :on-cancel #(set-creating! false)
                              :on-connected (fn [id _] (finish! id)) :on-registered finish!})
      ($ semantic-ui/graph-sources {:key epoch :graph graph :can-edit? can-edit? :initial-source created
                                   :on-create-connection #(set-creating! true)}))))

(defui graphs-page
  "Work on the selected Graph. New Graph opens an empty-create / merge chooser;
   the active content tab stays selected when moving between existing Graphs."
  [{:keys [workspace graphs loaded? owner? theme schema on-open focus-graph on-focus-graph
           on-create on-update on-rename on-set-package-name on-archive on-restore on-publish
           on-duplicate on-merge on-delete data-port data-scheme
           landing-graph on-set-landing]}]
  (let [focus-graph (when (and focus-graph (str/starts-with? focus-graph (str workspace "/"))) focus-graph)
        ;; Seeded, not synced: `focus-graph` is a jump from another page (the
        ;; Tokens page's graph-key rows land here), read once at mount. Keeping
        ;; it in step with a prop afterwards would make clicking «Graphs» in the
        ;; nav re-open whatever was last jumped to.
        [selected set-selected!] (uix/use-state #(or focus-graph (ui-recall-in [:graph-selection workspace])))
        [tab set-tab!] (uix/use-state #(let [remembered (ui-recall-in [:graph-tab])]
                                       (if (#{:semantic :explorer :sources :release} remembered) remembered :sources)))
        [model-selection set-model-selection!] (uix/use-state nil)
        [release-dirty? set-release-dirty!] (uix/use-state false)
        [rail-collapsed? set-rail-collapsed!] (uix/use-state #(boolean (ui-recall-in [:graph-rail-collapsed?])))
        [picked set-picked!] (uix/use-state #{})
        [new-name set-new-name!] (uix/use-state "")
        [creating? set-creating!] (uix/use-state false)
        [create-busy? set-create-busy!] (uix/use-state false)
        [create-error set-create-error!] (uix/use-state nil)
        [q set-q!] (uix/use-state "")
        [sort-state set-sort-state!] (uix/use-state [:name :asc])
        ;; Closed by default: the retired list is looked up on purpose (to
        ;; restore something), never scanned. It opens by itself when the graph
        ;; in the pane is one of them, so the rail never contradicts the pane.
        [archived-open? set-archived-open!] (uix/use-state false)
        ;; A refusal from a header act belongs under the header, not in the
        ;; browser console where every one of these used to end up.
        [pane-error set-pane-error!] (uix/use-state nil)
        [lifecycle-busy? set-lifecycle-busy!] (uix/use-state false)
        lifecycle-lock (uix/use-ref false)
        lifecycle! (fn [action graph on-error]
                     (when-not @lifecycle-lock
                       (reset! lifecycle-lock true)
                       (set-lifecycle-busy! true)
                       (let [done #(do (reset! lifecycle-lock false) (set-lifecycle-busy! false))]
                         (action graph (fn [err] (done) (on-error err)) done))))
        needle (str/lower-case (str/trim q))
        matching (filterv #(table/matches-filter? needle %) graphs)
        {rail :live rail-archived :archived} (table/rail-split matching)
        ;; The register sorts by its own column; the rail sorts by attention.
        ;; Two views of one list, each ordered by the question it answers.
        register (vec (sort (let [[col dir] sort-state
                                  v #(table/graph-sort-value col %)]
                              (if (= :desc dir)
                                #(compare (v %2) (v %1))
                                #(compare (v %1) (v %2))))
                            (remove #(= :archived (:graph/state %)) matching)))
        current (or (some #(when (= selected (:graph/qualified-name %)) %) graphs)
                    (first rail)
                    (first rail-archived))
        pick! (fn [qn]
                (set-selected! qn)
                (set-model-selection! nil)
                ;; The tab describes the current task, independent of the Graph.
                ;; Copy is a transient action rather than one of the four tabs.
                (when (#{:duplicate :delete :rename} tab)
                  (let [remembered (ui-recall-in [:graph-tab])]
                    (set-tab! (if (#{:semantic :explorer :sources :release} remembered) remembered :sources))))
                (set-creating! false))
        active   (count (filter #(= :active (:graph/state %)) graphs))
        archived (- (count graphs) active)]
    (uix/use-effect
     (fn []
       (when selected
         (when on-focus-graph (on-focus-graph selected))
         (ui-remember! :graph-selection (assoc (or (ui-recall-in [:graph-selection]) {}) workspace selected))
         (when (#{:semantic :explorer :sources :release} tab)
           (ui-remember! :graph-tab tab))))
     [selected tab workspace on-focus-graph])
    ;; Keep the initially displayed Graph selected when a copy changes rail order.
    (uix/use-effect
     (fn []
       (when (and current (nil? selected))
         (set-selected! (:graph/qualified-name current))))
     [current selected])
    ($ :main.page-graphs
       ($ :header.page-header
          ($ :div.page-header-row
             ($ :h2 "Graphs")
             (when loaded?
               ($ :span.page-counts
                  (str active " active" (when (pos? archived) (str " · " archived " archived")))))
             ($ :span.spacer)
             (when owner?
               ($ :button.btn.btn--primary {:disabled create-busy? :on-click #(do (set-creating! true) (set-picked! #{}) (set-new-name! "") (set-create-error! nil))}
                  "New Graph"))))

       (cond
         ;; Until the workspace's list arrives `graphs` is the empty seed, and
         ;; «No graphs yet» there reads as lost data.
         (and (empty? graphs) (not loaded?))
         ($ :p.hint "Loading graphs…")

         (and (empty? graphs) (not creating?))
         ($ :div.empty-state
            ($ :div.empty-state-icon "\u25CD")
            ($ :div.empty-state-title "No graphs yet")
            ($ :div.empty-state-hint
               "A graph is how a model reaches consumers. Create one, then pick
                its model in the Model tab.")
            ($ :button.btn.btn--primary.empty-state-action
               {:type "button" :on-click #(set-creating! true)}
               "New Graph"))

         creating?
         ($ :section.new-graph-panel
            ($ :h3 "New Graph")
            ($ :p.hint "Create an empty Graph, or select two or more Graphs to merge into a new one.")
            ($ :label "Graph name"
               ($ :input {:aria-label "New Graph name" :value new-name :disabled create-busy?
                          :placeholder "my-graph" :on-change #(set-new-name! (.. % -target -value))}))
            (when create-error ($ findings-view {:error create-error}))
            ($ :div.actions
               ($ :button.btn.btn--primary
                  {:disabled (or create-busy? (str/blank? new-name) (str/includes? new-name "/") (= 1 (count picked)))
                   :on-click #(let [done (fn [result]
                                          (set-create-busy! false) (set-creating! false) (set-picked! #{})
                                          (when-let [qn (get-in result [:graph :graph/qualified-name])]
                                            (set-selected! qn) (set-tab! :sources)))
                                    failed (fn [e] (set-create-busy! false) (set-create-error! e))]
                                (set-create-busy! true) (set-create-error! nil)
                                (if (seq picked)
                                  (on-merge {:graphs (vec (sort picked)) :name (str/trim new-name)} done failed)
                                  (on-create {:name (str/trim new-name)} done failed)))}
                  (cond create-busy? "Creating…" (seq picked) (str "Merge " (count picked) " into new Graph") :else "Create empty Graph"))
               ($ :button.btn {:disabled create-busy? :on-click #(set-creating! false)} "Cancel")
               (when (seq picked) ($ :button.btn {:disabled create-busy? :on-click #(set-picked! #{})} "Clear selection")))
            (when (= 1 (count picked)) ($ :p.hint "Select at least one more Graph to merge."))
            ($ :input.g-filter {:type "search" :value q :aria-label "Filter Graphs to merge"
                                :placeholder "Filter Graphs…" :on-change #(set-q! (.. % -target -value))})
            ($ :div.table-wrapper
               ($ :table.g-table
                  ($ :thead
                     ($ :tr
                        ($ :th.pick "")
                        ($ sortable-th {:col :name :label "graph"
                                        :sort-state sort-state :on-sort set-sort-state!})
                        ($ sortable-th {:col :state :label "state"
                                        :sort-state sort-state :on-sort set-sort-state!})
                        ($ sortable-th {:col :serving :label "serving"
                                        :title "which version this instance is serving"
                                        :sort-state sort-state :on-sort set-sort-state!})
))

                  ($ :tbody
                     (for [g register]
                       ($ :tr {:key (:graph/id g)}
                          ($ :td ($ :input {:type "checkbox" :aria-label (str "Select " (table/display-name g))
                                            :disabled (or create-busy? (= :archived (:graph/state g)))
                                            :checked (contains? picked (:graph/qualified-name g))
                                            :on-change #(let [qn (:graph/qualified-name g) on? (.. % -target -checked)]
                                                          (set-picked! (fn [ids] ((if on? conj disj) ids qn))))}))
                          ($ :td {:title (:graph/qualified-name g)} (table/display-name g))
                          ($ :td (name (:graph/state g)))
                          ($ :td (or (:graph/serving g) "—"))))))))

         :else
         ($ :div.graph-console {:class (when rail-collapsed? "is-rail-collapsed")}
            ($ :aside.graph-rail {:aria-label "Graph list"}
               ($ :div.graph-rail-toolbar
                  (when-not rail-collapsed? ($ :span "Graphs"))
                  ($ :button.btn
                     {:type "button" :aria-label (if rail-collapsed? "Expand graph list" "Collapse graph list")
                      :title (if rail-collapsed? "Expand graph list" "Collapse graph list")
                      :aria-expanded (not rail-collapsed?)
                      :on-click #(let [collapsed? (not rail-collapsed?)]
                                   (set-rail-collapsed! collapsed?)
                                   (ui-remember! :graph-rail-collapsed? collapsed?))}
                     (if rail-collapsed? "»" "«")))
               ($ :input.g-filter
                  {:type "search" :value q
                   :placeholder "Filter graphs\u2026"
                   :aria-label "Filter graphs by name"
                   :on-change #(set-q! (.. % -target -value))})
               (let [selected-qn (:graph/qualified-name current)
                     item (fn [g]
                            ($ graph-rail-item
                               {:key (:graph/id g)
                                :graph g
                                :selected? (= selected-qn (:graph/qualified-name g))
                                :on-select pick!}))
                     ;; The pane and the rail must agree: landing on an archived
                     ;; graph (a jump from Tokens, or restoring one) opens the
                     ;; section that holds it.
                     open? (or archived-open?
                               (some #(= selected-qn (:graph/qualified-name %))
                                     rail-archived))]
                 ($ :<>
                    (cond
                      (seq rail)
                      ($ :div.rail-list (map item rail))

                      (seq rail-archived)
                      ($ :p.hint "Nothing active here \u2014 only archived graphs.")

                      :else
                      ($ :p.hint "No graph matches that."))

                    ;; Retired graphs are their own list. They are not choices —
                    ;; the only act they answer to is «restore» — so they sit
                    ;; under the live ones behind one click instead of sorting
                    ;; in among them by name.
                    (when (seq rail-archived)
                      ($ :div.rail-archived
                         ($ :button.rail-section
                            {:type "button"
                             :aria-expanded open?
                             :title (if (and open? (not archived-open?))
                                      "Open because the graph you are in is archived"
                                      "Graphs that have been retired")
                             :on-click #(set-archived-open! (not open?))}
                            ($ :span.rail-section-caret {:aria-hidden true}
                               (if open? "\u25BE" "\u25B8"))
                            ($ :span.rail-section-label "Archived")
                            ($ :span.rail-section-count (count rail-archived)))
                         (when open?
                           ($ :div.rail-list.is-archived (map item rail-archived))))))))

            (when current
              (let [qn (:graph/qualified-name current)
                    landing? (= landing-graph qn)]
                ($ :section.graph-pane
                   ($ :div.pane-title
                      ($ :h3.pane-graph {:title qn} (table/display-name current))
                      (if-let [v (:graph/serving current)]
                        ($ :span.badge.badge--ok (str "v" v " serving"))
                        ($ :span.badge.badge--neutral "nothing serving"))
                      ($ :span.spacer)
                      ($ :button.btn {:data-settings-navigation true :on-click #(on-open qn :lab)} "Query")
                      ($ :details.graph-actions
                         ($ :summary.btn {:aria-label "Graph actions"} "⋯")
                         (when (:graph/can-manage? current)
                           ($ :button.btn.pane-tab
                              {:on-click #(do (set-tab! :rename) (set! (.-open (.closest (.-currentTarget %) "details")) false))}
                              "Rename Graph"))
                         (when (:graph/can-manage? current)
                           ($ :button.btn.pane-tab {:on-click #(do (set-tab! :duplicate) (set! (.-open (.closest (.-currentTarget %) "details")) false))} "Create a copy"))
                         (when (:graph/can-manage? current)
                           ($ :<>
                              ($ :button.btn.pane-tab
                                 {:disabled lifecycle-busy?
                                  :title "Archiving hides this Graph from pickers and retains its contents. It can be restored."
                                  :on-click #(do (set-pane-error! nil)
                                                 (lifecycle! (if (= :archived (:graph/state current)) on-restore on-archive) current set-pane-error!))}
                                 (if (= :archived (:graph/state current)) "Restore Graph" "Archive Graph"))
                              (when (= :archived (:graph/state current))
                                ($ :button.btn.pane-tab {:disabled lifecycle-busy?
                                                        :on-click #(do (set-tab! :delete) (set! (.-open (.closest (.-currentTarget %) "details")) false))}
                                   "Delete Graph…"))))
                         ($ :button.btn {:on-click #(when (some? js/navigator.clipboard)
                                                    (.writeText js/navigator.clipboard qn))} "Copy full name")))

                   (when pane-error ($ findings-view {:error pane-error}))

                   ($ :div.pane-tabs {:role "tablist" :aria-label "Graph"}
                      ;; The tabs run in the order the work does (owner,
                      ;; 2026-10-01): connections, then the model bound to
                      ;; them, then the read-back, then publish. It used to lead
                      ;; with Model (doc 106 §106.16: «is this graph the
                      ;; sentences I said»), which sent a new graph's author to
                      ;; a page that cannot be finished until Sources exist.
                      (for [[k label] [[:sources "Sources"] [:semantic "Model"] [:explorer "Explorer"] [:release "Publish"]]]
                        ($ :button.pane-tab
                           {:key (name k) :type "button" :role "tab"
                            :class (when (= tab k) "on")
                            :aria-selected (= tab k)
                            :on-click #(set-tab! k)}
                           label)))

                   ($ :div.pane-body
                      (when (contains? #{:semantic :explorer :sources :release} tab)
                        ($ guidance/graph-guide
                           {:tab tab :archived? (= :archived (:graph/state current))
                            :can-edit? (:graph/can-edit? current) :serving (:graph/serving current)
                            :on-navigate set-tab!}))
                      (case tab
                        :rename
                        ($ graph-rename {:key (:graph/id current) :graph current :on-rename on-rename
                                         :on-cancel #(set-tab! :semantic)})
                         :semantic
                        ($ graph-model {:key (str qn model-selection) :graph qn :theme theme
                                        :initial-selection model-selection :can-edit? (:graph/can-edit? current)})
                        :delete
                        ($ :<>
                           ($ :button.pane-tab {:on-click #(set-tab! :semantic)} "← Back to model")
                           ($ graph-settings {:key (str qn "-delete") :graph current :mode :delete
                                              :on-delete (fn [request on-error done]
                                                           (on-delete request on-error
                                                                      (fn []
                                                                        (done)
                                                                        (pick! nil))))}))
                        :explorer
                        ($ graph-model {:key (str qn "-explorer") :graph qn :theme theme :explorer? true
                                        :can-edit? (:graph/can-edit? current)
                                        :on-edit #(do (set-model-selection! %) (set-tab! :semantic))})
                        :sources ($ graph-connections {:key qn :graph qn :schema schema :can-edit? (:graph/can-edit? current)})
                        :release
                        ($ :<>
                           ($ graph-settings {:key (str qn "-release-name") :mode :publish :graph current
                                              :on-dirty-change set-release-dirty!
                                              :on-set-package-name on-set-package-name})
                           ($ graph-runtime {:key qn :graph qn :graph-id (:graph/id current) :data-port data-port :data-scheme data-scheme :blocked? release-dirty? :archived? (= :archived (:graph/state current))
                                             :can-edit? (:graph/can-edit? current) :known-serving (:graph/serving current)
                                             :on-publish on-publish})
                           ($ token-panel {:key (str qn "-tokens") :graph qn :data-port data-port :data-scheme data-scheme
                                           :archived? (= :archived (:graph/state current))}))
                        :duplicate
                        ($ :<>
                           ($ :button.pane-tab {:on-click #(set-tab! :semantic)} "← Back to model")
                           ($ graph-settings {:key (str qn "-copy") :mode :duplicate :graph current :on-duplicate on-duplicate}))
))))))))))

(defui feedback-banner
  "Shared inline status renderer for short action feedback."
  [{:keys [kind message id focus-ref error copy-text]}]
  (let [copy-target (or copy-text
                        (and (map? error)
                             (str (pr-str error))))
        [copied? set-copied!] (uix/use-state false)
        [failed? set-failed!] (uix/use-state false)
        clear-copy-hint #(js/setTimeout (fn []
                                         (set-copied! false)
                                         (set-failed! false))
                                       1200)
        copy-label (cond
                    copied? "copied"
                    failed? "copy failed"
                    :else "copy details")]
    (when message
      ($ :div.team-action-feedback
         {:id id
          :ref focus-ref
          :role (if (= kind :error) "alert" "status")
          :tab-index -1
          :aria-live (if (= kind :error) "assertive" "polite")
          :aria-atomic true
          :class (if (= kind :error) "team-action-error" "team-action-success")}
         ($ :p (str message))
         (when (and copy-target (= kind :error))
           ($ :button.btn
              {:type "button"
               :on-click #(if-let [clipboard (.-clipboard js/navigator)]
                           (-> (.writeText clipboard copy-target)
                               (.then (fn []
                                        (set-copied! true)
                                        (set-failed! false)
                                        (clear-copy-hint)))
                               (.catch (fn [_]
                                         (set-failed! true)
                                         (set-copied! false)
                                         (clear-copy-hint))))
                           (do
                             (set-failed! true)
                             (set-copied! false)
                             (clear-copy-hint)))}
              copy-label))))))

(defui workspace-team-page
  "Membership for the selected workspace. Account creation has its own
   first-level administrator page and is deliberately absent here."
  [{:keys [workspace workspace-data principal studio-users on-refresh-context]}]
  (let [members (or (:workspace/members workspace-data) [])
        role    (or (:workspace/role workspace-data) :member)
        owner?  (= :owner role)
        member-principals (set (map :principal members))
        candidates (remove #(or (:disabled? %)
                                (contains? member-principals (:principal %)))
                           studio-users)
        [invite-email set-invite-email!] (uix/use-state "")
        [invite-role set-invite-role!] (uix/use-state :member)
        [member-error set-member-error!] (uix/use-state nil)
        [invite-error set-invite-error!] (uix/use-state nil)]
    ($ :main.workspace-team-page
       ($ :header.workspace-plate
          ($ :div.plate-identity
             ($ :span.eyebrow "Studio workspace · registry")
             ($ :h2 workspace)
             ($ :p "Members collaborate on this Studio model and its graphs. This membership never grants Server or Workbench access."))
          ($ :span.workspace-role
             ($ :em "your role")
             (name role)))
       ($ :div.team-layout.team-layout-single
          ($ :div.team-main
       (when owner?
         ($ :section.team-note
            ($ :h3 "Add a team member")
            ($ :p "Choose an existing Studio user and the access they need.")
            ($ :form.workspace-member-form
               {:on-submit
                (fn [event]
                  (.preventDefault event)
                  (set-invite-error! nil)
                  (api/add-workspace-member!
                   workspace invite-email invite-role
                   {:on-success (fn [_]
                                  (set-invite-email! "")
                                  (set-invite-role! :member)
                                  (on-refresh-context))
                    :on-error set-invite-error!}))}
               ($ :select {:aria-label "Studio user to add" :value invite-email :required true
                            :on-change #(set-invite-email! (.. % -target -value))}
                  ($ :option {:value ""} "Select a Studio user")
                  (for [candidate candidates]
                    ($ :option {:key (:id candidate) :value (:email candidate)}
                       (str (:name candidate) " · " (:email candidate)))))
               ($ :select {:aria-label "Workspace access role" :value (name invite-role)
                            :on-change #(set-invite-role! (keyword (.. % -target -value)))}
                  ($ :option {:value "member"} "Member · view")
                  ($ :option {:value "editor"} "Editor · edit model & graphs"))
               ($ :button.btn.primary {:type "submit" :disabled (empty? candidates)} "Add member"))
            (when invite-error
              ($ feedback-banner
                 {:kind :error
                  :message (normalize-api-error invite-error)
                  :error invite-error
                  :id "workspace-member-invite-error"}))))
       ($ :section.team-members
          ($ :div.team-section-head
             ($ :h3 "Members")
             ($ :span.graphs-count (str (count members) " member"
                                        (when (not= 1 (count members)) "s"))))
          ($ :div.team-member-list
             (for [[idx {member-principal :principal :keys [role attributes]}]
                   (map-indexed vector members)]
               ($ :article.team-member
                  {:key (str (:issuer member-principal) "/" (:subject member-principal))
                   :style {:--row-index idx}
                   :class (when (= principal member-principal) "current")}
                  ($ :span.member-index (str (when (< idx 9) "0") (inc idx)))
                  ($ :div.member-avatar
                     (str/upper-case (subs (or (:name attributes) (:subject member-principal)) 0 1)))
                  ($ :div.member-identity
                     ($ :h4 (or (:name attributes) (:subject member-principal)))
                     ($ :p (or (:email attributes) (:issuer member-principal))))
                  ;; The owner edits roles and withdraws access in place. An
                  ;; owner's own role is not editable here: promoting a second
                  ;; owner is the deliberate act that makes stepping down
                  ;; possible, and the module refuses to remove the last one
                  ;; regardless of what this form offers.
                  (if (and owner? (not= :owner role))
                    ($ :select.member-role
                       {:value (name role)
                        :aria-label (str "Role for " (or (:email attributes)
                                                         (:subject member-principal)))
                        :on-change
                        #(let [next (keyword (.. % -target -value))]
                           (api/add-workspace-member!
                            workspace (:email attributes) next
                            {:on-success (fn [_] (on-refresh-context))
                             :on-error set-member-error!}))}
                       (for [r ["member" "editor"]]
                         ($ :option {:key r :value r} r)))
                    ($ :span.member-role (name role)))
                  (when (= principal member-principal)
                    ($ :span.member-current "you"))
                  (when (and owner? (not= principal member-principal))
                    ($ :button.btn.member-remove
                       {:title "Withdraw this member's access to the workspace"
                        :on-click
                        #(api/remove-workspace-member!
                          workspace (:email attributes)
                          {:on-success (fn [_] (on-refresh-context))
                           :on-error set-member-error!})}
                       "remove"))))
             (when member-error ($ findings-view {:error member-error})))))))))

(defui studio-user-admin-page
  "First-level account creation and Studio user directory, visible only to a
   Studio system administrator."
  [{:keys [studio-users studio-users-loaded? studio-users-load-error current-user on-refresh-users]}]
  (let [[new-name set-new-name!] (uix/use-state "")
        [new-email set-new-email!] (uix/use-state "")
        [new-password set-new-password!] (uix/use-state "")
        [new-sso? set-new-sso!] (uix/use-state false)
        [new-role set-new-role!] (uix/use-state :member)
        [editing-user set-editing-user!] (uix/use-state nil)
        [user-error set-user-error!] (uix/use-state nil)
        [create-attempted? set-create-attempted!] (uix/use-state false)
        [reset-user set-reset-user!] (uix/use-state nil)
        [reset-password set-reset-password!] (uix/use-state "")
        [directory-error set-directory-error!] (uix/use-state nil)
        [reset-attempted? set-reset-attempted!] (uix/use-state false)
        [action-success set-action-success!] (uix/use-state nil)
        [action-state set-action-state!] (uix/use-state nil)
        [sso-provider set-sso-provider!] (uix/use-state nil)
        [reset-user-id set-reset-user-id!] (uix/use-state nil)
        trim-name (str/trim new-name)
        trim-email (str/trim new-email)
        trim-password (str/trim new-password)
        is-email? (fl/valid-user-email? trim-email)
        clear-action-feedback! (fn []
                               (set-directory-error! nil)
                               (set-action-success! nil))
        action-busy? (fn [kind user-id]
                       (and (= kind (:type action-state))
                            (or (nil? user-id) (= user-id (:user-id action-state)))))
        new-name-valid? (seq trim-name)
        new-email-valid? (seq trim-email)
        new-password-valid? (or new-sso? (and (seq trim-password) (>= (count trim-password) 12)))
        create-ready? (and new-name-valid? new-email-valid? is-email? new-password-valid?)
        create-errors (cond-> []
                       (and (or create-attempted? (seq trim-name))
                            (not new-name-valid?))
                       (conj "Display name is required.")
                       (and (or create-attempted? (seq trim-email))
                            (not new-email-valid?))
                       (conj "A valid email address is required.")
                       (and (or create-attempted? (seq trim-password))
                            (not new-sso?) (not (seq trim-password)))
                       (conj "Temporary password is required for local accounts.")
                       (and (or create-attempted? (seq trim-password))
                            (not new-sso?) (seq trim-password) (< (count trim-password) 12))
                       (conj "Temporary password must be at least 12 characters.")
                       (and (or create-attempted? (seq trim-email))
                            (not is-email?))
                       (conj "Enter a complete email address, for example jerry.duan@tdsynnex.com."))
        reset-password-valid? (>= (count (str/trim reset-password)) 12)
        reset-password-errors (cond-> []
                                (and (or reset-attempted? (seq reset-password))
                                     (not reset-password-valid?))
                                (conj "Temporary password must be at least 12 characters."))
        creating? (= :create (:type action-state))
        admin-feedback-ref (uix/use-ref nil)]
    (uix/use-effect
     (fn []
       (on-refresh-users)
       (api/auth-state! {:on-success #(set-sso-provider! (:oidc %))
                         :on-error (fn [_] (set-sso-provider! nil))})
       js/undefined)
     [on-refresh-users])
    (uix/use-effect
     (fn []
       (when (or user-error studio-users-load-error directory-error action-success)
         (when-let [node @admin-feedback-ref]
           (.focus node)))
       js/undefined)
     [user-error studio-users-load-error directory-error action-success])
    ($ :main.workspace-team-page
       ($ :header.workspace-plate
          ($ :div.plate-identity
             ($ :span.eyebrow "Studio administration")
             ($ :h2 "User Management")
             ($ :p "Create Studio accounts and review the Studio user directory.")))
       ($ :div.team-layout.team-layout-single
          ($ :div.team-main
             ($ :section.team-note.studio-user-admin
                ($ :h3 "Create a Studio user")
                ($ :p "Creates a local Studio account and that user's own empty workspace. It does not create a Server administrator or issue a Server token.")
                ($ :form.studio-user-form
                   {:on-submit
                    (fn [event]
                      (.preventDefault event)
                      (set-user-error! nil)
                      (set-create-attempted! true)
                      (clear-action-feedback!)
                      (if (and create-ready? (not creating?))
                        (do
                          (set-action-state! {:type :create})
                          (api/create-studio-user!
                           (if new-sso?
                             {:name trim-name :email trim-email :sso true :role new-role}
                             {:name trim-name :email trim-email :password trim-password :role new-role})
                           {:on-success (fn [_]
                                          (set-action-state! nil)
                                          (set-action-success! "Studio user created.")
                                          (set-new-name! "")
                                          (set-new-email! "")
                                          (set-new-password! "")
                                          (set-new-sso! false)
                                          (set-new-role! :member)
                                          (set-create-attempted! false)
                                          (on-refresh-users))
                            :on-error (fn [failure]
                                        (set-action-state! nil)
                                        (set-user-error! failure))}))
                        (set-user-error! {:message "Please complete all required fields."})))}
                   ($ :input {:value new-name :required true :placeholder "Display name"
                              :auto-complete "off"
                              :aria-describedby "studio-user-create-hints"
                              :aria-invalid (boolean (and (or create-attempted? (seq trim-name))
                                                         (not new-name-valid?)))
                              :on-change #(set-new-name! (.. % -target -value))})
                   ($ :input {:type "email" :value new-email :required true :placeholder "Email"
                              :auto-complete "off"
                              :aria-describedby "studio-user-create-hints"
                              :aria-invalid (boolean (and (or create-attempted? (seq trim-email))
                                                         (or (not new-email-valid?)
                                                             (not is-email?))))
                              :on-change #(set-new-email! (.. % -target -value))})
                   ($ :label "Role"
                      ($ :select {:aria-label "New user role" :value (name new-role) :disabled creating?
                                  :on-change #(set-new-role! (keyword (.. % -target -value)))}
                         ($ :option {:value "member"} "Member") ($ :option {:value "admin"} "Admin")))
                   (when sso-provider
                     ($ :label.studio-user-sso
                        ($ :input {:type "checkbox" :checked new-sso?
                                   :on-change #(set-new-sso! (.. % -target -checked))})
                        ($ :span (str "SSO account · signs in with "
                                      (or (:name sso-provider) "Single Sign-On")))))
                    (when-not new-sso?
                     ($ :input {:type "password" :value new-password :required true :min-length 12
                                :placeholder "Temporary password · 12+ characters"
                                :aria-label "Temporary password, at least 12 characters"
                                :auto-complete "new-password"
                                :aria-describedby "studio-user-create-hints"
                                :aria-invalid (boolean (and (or create-attempted? (seq trim-password))
                                                           (not new-password-valid?)))
                                :on-change #(set-new-password! (.. % -target -value))}))
                   ($ :div#studio-user-create-hints.action-hints
                     (for [error create-errors]
                       ($ :p.hint error)))
                   ($ :button.btn.primary
                     {:type "submit"
                      :disabled (or creating? (not create-ready?))
                      :aria-busy creating?
                      :aria-describedby "studio-user-create-hints"}
                     (if creating? "Creating…" "Create Studio user")))
                (when user-error
                  ($ feedback-banner
                     {:kind :error
                      :message (normalize-api-error user-error)
                      :error user-error
                      :id "studio-user-create-error"
                      :focus-ref admin-feedback-ref})))
             ($ :section.team-members
                ($ :div.team-section-head
                   ($ :h3 "Studio users")
                   ($ :span.graphs-count (count studio-users)))
                (cond
                  studio-users-load-error
                  ($ feedback-banner
                     {:kind :error
                      :message (normalize-api-error studio-users-load-error)
                      :error studio-users-load-error
                      :id "studio-user-load-error"
                      :focus-ref admin-feedback-ref})
                  (not studio-users-loaded?)
                  ($ :p.team-empty "Loading Studio users…")
                  (empty? studio-users)
                  ($ :p.team-empty "No Studio users found.")
                  :else
                  ($ :div.team-member-list.studio-user-list
                     (for [studio-user studio-users]
                       (let [user-id (:id studio-user)
                             resetting? (action-busy? :reset user-id)
                             removing? (action-busy? :remove user-id)]
                       ($ :article.team-member {:key user-id}
                          ($ :div.member-avatar
                             (str/upper-case (subs (or (:name studio-user) (:email studio-user)) 0 1)))
                          ($ :div.member-identity
                             ($ :h4 (:name studio-user))
                             ($ :p (:email studio-user)))
                          ($ :div.studio-user-status
                             ($ :span.member-role (name (:system-role studio-user)))
                             ;; Linked or pending, never the issuer or subject.
                             (when (:sso-linked? studio-user)
                               ($ :span.member-current "SSO"))
                             (when (and (:sso-email studio-user) (not (:sso-linked? studio-user)))
                               ($ :span.member-current {:title "Links on the first SSO sign-in that asserts this address"}
                                  (str "SSO pending · " (:sso-email studio-user))))
                             (when (:disabled? studio-user)
                               ($ :span.member-current "removed")))
                          ($ :div.studio-user-actions
                             (when (:disabled? studio-user)
                               ($ :button.btn.member-remove
                                  {:type "button" :disabled (some? action-state)
                                   :title "Restore sign-in with the existing role and workspace access"
                                   :on-click #(do
                                                (clear-action-feedback!)
                                                (set-action-state! {:type :restore :user-id user-id})
                                                (api/restore-studio-user! user-id
                                                  {:on-success (fn [_]
                                                                 (set-action-state! nil)
                                                                 (set-action-success! (str "Restored " (:email studio-user) "."))
                                                                 (on-refresh-users))
                                                   :on-error (fn [failure]
                                                               (set-action-state! nil)
                                                               (set-directory-error! failure))}))}
                                  (if (action-busy? :restore user-id) "Restoring…" "Restore")))
                             (when-not (:disabled? studio-user)
                               ($ :button.btn.user-edit {:type "button" :disabled (some? action-state)
                                               :on-click #(do (clear-action-feedback!)
                                                              (set-editing-user! {:user-id user-id :name (:name studio-user)
                                                                                 :email (:email studio-user) :role (:system-role studio-user)}))}
                                  "Edit"))
                             ;; Doc 101 §8.16.9 item 3: link an existing account
                             ;; by pre-registering the address its identity
                             ;; provider will assert. A linked one is refused
                             ;; server-side, so the button is not offered.
                             (when-not (or (:disabled? studio-user) (:sso-linked? studio-user))
                               ($ :button.btn.user-sso
                                  {:type "button" :disabled (some? action-state)
                                   :title "Pre-register for single sign-on; empty clears it"
                                   :on-click
                                   #(when-let [email (js/prompt (str "SSO email for " (:email studio-user)
                                                                     " (the address the identity provider asserts; empty clears it)")
                                                                (or (:sso-email studio-user) ""))]
                                      (clear-action-feedback!)
                                      (set-action-state! {:type :sso :user-id user-id})
                                      (api/set-studio-user-sso-email!
                                       user-id (str/trim email)
                                       {:on-success (fn [_] (set-action-state! nil)
                                                      (set-action-success! "SSO email updated.")
                                                      (on-refresh-users))
                                        :on-error (fn [failure] (set-action-state! nil)
                                                    (set-directory-error! failure))}))}
                                  "SSO email"))
                             (when (and (not (:disabled? studio-user))
                                        (:has-password? studio-user))
                                   ($ :button.btn.user-reset {:type "button"
                                                  :disabled (action-busy? :reset user-id)
                                                  :aria-busy (action-busy? :reset user-id)
                                                  :on-click #(do (set-directory-error! nil)
                                                                 (set-reset-password! "")
                                                                 (set-reset-user-id! user-id)
                                                                 (set-reset-attempted! false)
                                                                 (set-action-success! nil)
                                                                 (set-reset-user! studio-user))}
                                   "Reset password"))
                             (when (and (not (:disabled? studio-user))
                                        (not= (:id current-user) (:id studio-user)))
                               ($ :button.btn.member-remove
                                  {:type "button"
                                   :disabled (action-busy? :remove user-id)
                                   :aria-busy (action-busy? :remove user-id)
                                   :title "Disable sign-in while preserving identity, ownership, and attribution"
                                   :on-click
                                   #(when (js/confirm
                                           (str "Remove Studio access for " (:email studio-user)
                                                "? Their identity and historical attribution will be preserved."))
                                      (clear-action-feedback!)
                                      (set-action-state! {:type :remove :user-id user-id})
                                      (api/remove-studio-user!
                                       user-id
                                       {:on-success (fn [_] (set-action-state! nil)
                                                     (set-action-success! (str "Removed " (:email studio-user) "."))
                                                     (on-refresh-users))
                                        :on-error (fn [failure]
                                                    (set-action-state! nil)
                                                    (set-directory-error! failure))}))}
                                  (if (action-busy? :remove user-id)
                                    "Removing…"
                                    "Remove")))))))))
                (when editing-user
                  ($ :form.studio-user-edit-form
                     {:on-submit (fn [e]
                                   (.preventDefault e)
                                   (when (and (not action-state) (fl/valid-user-email? (:email editing-user))
                                              (not (str/blank? (:name editing-user))))
                                     (set-action-state! {:type :edit}) (clear-action-feedback!)
                                     (api/update-studio-user! editing-user
                                       {:on-success (fn [_] (set-action-state! nil) (set-editing-user! nil)
                                                           (set-action-success! "User updated.") (on-refresh-users))
                                        :on-error (fn [error] (set-action-state! nil) (set-directory-error! error))})))}
                     ($ :h3 "Edit user")
                     ($ :label "Display name" ($ :input {:required true :aria-label "Edit display name" :value (:name editing-user)
                                                         :on-change #(set-editing-user! (assoc editing-user :name (.. % -target -value)))}))
                     ($ :label "Email" ($ :input {:required true :type "email" :aria-label "Edit email" :value (:email editing-user)
                                                  :on-change #(set-editing-user! (assoc editing-user :email (.. % -target -value)))}))
                     ($ :label "Role" ($ :select {:aria-label "Edit user role" :value (name (:role editing-user))
                                                  :on-change #(set-editing-user! (assoc editing-user :role (keyword (.. % -target -value))))}
                                         ($ :option {:value "member"} "Member") ($ :option {:value "admin"} "Admin")))
                     ($ :div.actions
                        ($ :button.btn.btn--primary {:type "submit" :disabled (or (some? action-state) (str/blank? (:name editing-user)) (not (fl/valid-user-email? (:email editing-user))))} "Save")
                        ($ :button.btn {:type "button" :disabled (some? action-state) :on-click #(set-editing-user! nil)} "Cancel"))))
                (when reset-user
                  ($ :form.studio-user-form.studio-password-reset-form
                     {:on-submit
                        (fn [event]
                        (.preventDefault event)
                        (set-reset-attempted! true)
                        (let [password (str/trim reset-password)]
                          (clear-action-feedback!)
                          (if (< (count password) 12)
                            (set-directory-error! {:message "New temporary password must be at least 12 characters."})
                            (do
                              (set-directory-error! nil)
                              (set-reset-user-id! (:id reset-user))
                              (set-action-state! {:type :reset :user-id (:id reset-user)})
                              (api/reset-studio-user-password!
                               (:id reset-user) password
                               {:on-success (fn [_]
                                              (set-action-state! nil)
                                              (set-reset-user-id! nil)
                                              (set-reset-attempted! false)
                                              (set-action-success! (str "Password reset for " (:email reset-user) "."))
                                              (set-reset-user! nil)
                                              (set-reset-password! "")
                                              (on-refresh-users))
                                :on-error (fn [failure]
                                            (set-action-state! nil)
                                            (set-reset-user-id! nil)
                                            (set-directory-error! failure))}))))
                        )}
                     ($ :span (str "Reset password for " (:email reset-user)))
                     ($ :input {:type "password" :value reset-password :required true
                                :min-length 12 :placeholder "New temporary password (12+)"
                                :auto-complete "new-password"
                                :on-change #(set-reset-password! (.. % -target -value))
                                :aria-describedby "studio-user-reset-hints"
                                :aria-invalid (boolean (and (or reset-attempted? (seq reset-password))
                                                            (not reset-password-valid?)))})
                     ($ :div#studio-user-reset-hints.action-hints
                        (for [error reset-password-errors]
                          ($ :p.hint error)))
                     ($ :button.btn.primary {:type "submit"
                                            :disabled (or (action-busy? :reset (:id reset-user))
                                                          (not reset-password-valid?))
                                            :aria-busy (action-busy? :reset (:id reset-user))
                                            :aria-describedby "studio-user-reset-hints"}
                        (if (action-busy? :reset (:id reset-user))
                          "Resetting…"
                          "Reset password"))
                     ($ :button.btn {:type "button"
                                     :disabled (action-busy? :reset (:id reset-user))
                                     :aria-busy (action-busy? :reset (:id reset-user))
                                     :on-click #(do (set-reset-user! nil)
                                                    (set-reset-password! "")
                                                    (set-reset-user-id! nil)
                                                    (set-reset-attempted! false)
                                                    (clear-action-feedback!))}
                        "Cancel")))
                (when directory-error
                  ($ feedback-banner {:kind :error
                                     :message (normalize-api-error directory-error)
                                     :error directory-error
                                     :id "studio-user-directory-error"
                                     :focus-ref admin-feedback-ref}))
                (when action-success
                  ($ feedback-banner {:kind :success
                                     :message action-success
                                     :id "studio-user-directory-success"
                                     :focus-ref admin-feedback-ref}))))))))


;; ---------------------------------------------------------------------------
;; Observability (8.10.6)
;;
;; 8.10 built the plumbing — structured logs, an audit trail with a durable
;; subject index, request ids across every entrance — and studio had no door to
;; any of it. These three pages are that door.
;;
;; What a caller may SEE is decided on the server (a workspace owner sees their
;; own workspace's events, an admin sees all). These pages render what they are
;; handed and never filter: a filter written twice is a filter that disagrees
;; with itself, and the copy that leaks is the one nobody tested.
;; ---------------------------------------------------------------------------

(defn- outcome-class [outcome]
  (case outcome
    :ok     "obs-outcome ok"
    :denied "obs-outcome denied"
    :error  "obs-outcome error"
    "obs-outcome"))

(defn- event-label [e]
  (some-> (:event e) str (str/replace #"^:" "")))

(defn- actor-label [e]
  (let [a (:actor e)
        who (or (get-in a [:on-behalf-of :subject])
                (get-in a [:delegated-by :subject]))]
    (or who
        (when (:token-id e) (str "token " (subs (str (:token-id e)) 0 (min 12 (count (str (:token-id e)))))))
        (some-> (get-in a [:service :instance-id]) (->> (str "instance ")))
        "—")))

(defui workspace-page
  "Workspace maintenance: membership and shared connections on one page."
  [{:keys [workspace workspace-data can-edit? session studio-users
           graph schema on-model-change on-refresh-context on-open-graph]}]
  (let [[tab set-tab!] (uix/use-state :team)
        ;; The switcher shows the new workspace at once, but the server serves
        ;; it only after `select-workspace!` answers — and every tab below
        ;; reads the server's current workspace. Keying them on the picker
        ;; remounted them early: they fetched the OLD workspace (or failed)
        ;; and never fetched again. Keyed on the confirmed one, they reload
        ;; exactly when the switch has landed.
        ws-key (or (:current-workspace session) workspace)]
  ($ :main.ws-page
     ($ :header.ws-header
        ($ :span.eyebrow "Workspace")
        ($ :div.ws-heading-row
           ($ :div ($ :h2 workspace)
              ($ :p.page-desc "People and connections, managed in one place."))
           ($ :span.ws-access-badge (str "Your role · " (name (or (:workspace/role workspace-data) :member))))))
     ($ :nav.ws-section-switch {:aria-label "Workspace sections"}
        ;; Tokens came back 2026-10-04: the 2026-09-15 rework dropped its entry, which left the Builder MCP key
        ;; (workspace token) with no place to be issued; the page itself had stayed.
        (for [[id label] [[:team "Team"] [:sources "Sources"] [:variables "Variables"] [:tokens "Tokens"]]]
           ($ :button {:key (name id) :type "button"
                       :aria-pressed (= tab id) :class (when (= tab id) "on")
                       :on-click #(set-tab! id)} label)))
     ($ :div.ws-sections
        (case tab
        :variables ($ variables/variables-page {:key ws-key :can-edit? can-edit? :on-model-change on-model-change})
        :tokens ($ workspace-tokens-page {:key (str "tokens-" ws-key) :workspace workspace
                                          :role (or (:workspace/role workspace-data) :member)})
        :team
        ($ :section.ws-section {:aria-labelledby "workspace-team-title"}
           ($ :header.ws-section-heading
              ($ :h3 {:id "workspace-team-title"} "Team")
              ($ :span.ws-section-count (str (count (:workspace/members workspace-data)) " members")))
           ($ workspace-team-page
              {:key (str "team-" ws-key)
               :workspace workspace :workspace-data workspace-data
               :principal (:principal session) :studio-users studio-users
               :on-refresh-context on-refresh-context}))
        ($ :section.ws-section.ws-sources {:aria-labelledby "workspace-sources-title"}
           ($ :header.ws-section-heading
              ($ :h3 {:id "workspace-sources-title"} "Sources")
              ($ :button.btn {:on-click #(set-tab! :variables)} "Manage variables"))
           ($ sources-page
              {:key (str "sources-" ws-key)
               :workspace workspace :can-edit? can-edit?
               :graph graph :schema schema :on-model-change on-model-change
               :on-open-graph on-open-graph})))))))

(defn- obs-field [label value]
  ($ :div.obs-field ($ :dt label) ($ :dd (or value "—"))))

(defn- obs-chips [label items]
  (when (seq items)
    ($ :div.obs-chips
       ($ :h5 (str label " (" (count items) ")"))
       ($ :div.obs-chip-row
          (for [i items] ($ :span.obs-tag {:key (str i)} (str i)))))))

(defui obs-resolver-timeline
  "每-resolver 一行：调用序号、名字、开始偏移、耗时，条的位置=何时开始、
   长度=跑了多久。**按执行顺序自上而下**，所以并行的 resolver 条会重叠——
   这是 open-atlas 的 activity 页早就证明有用的读法。"
  [{:keys [trace]}]
  (let [{:keys [resolvers span-ms slowest resolver-count]} trace
        span (max (or span-ms 0) 1)]
    ($ :div.obs-timeline
       ($ :h5 (str "Resolvers (" (or resolver-count 0) ")"
                   (when span-ms (str " · " span-ms "ms wall"))))
       (for [[i r] (map-indexed vector resolvers)]
         (let [start (or (:start r) 0)
               ms (or (:ms r) 0)]
           ($ :div.obs-track-row {:key (str i "-" (:resolver r))}
              ($ :div.obs-track-head
                 ($ :span.obs-track-name
                    {:class (when (= (:resolver r) slowest) "slowest")}
                    (str (inc i) ". " (:resolver r)))
                 ($ :span.obs-track-ms
                    {:class (when-not (:ok? r) "failed")}
                    (str (when (pos? start) (str "+" start "ms · ")) ms "ms")))
              ($ :div.obs-track
                 ($ :i {:class (when-not (:ok? r) "failed")
                        :style {:left (str (min 98.5 (* 100.0 (/ start span))) "%"
                                           )
                                :width (str (max 1.5 (* 100.0 (/ (max ms 1) span))) "%")}}))))))))

(defui obs-detail
  "选中一条事件的全部可看信息。**没有的就说没有**——一个永远空着的区块会让人
   以为「没记录」等于「没发生」。"
  [{:keys [event]}]
  (if-not event
    ($ :div.obs-detail.empty
       ($ :p "选一行看它的详情。")
       ($ :p.obs-note "详情里只有名称、计数、时延与状态——值一律不在这里,"
          "也不在盘上（audit-event-v1 §5）。"))
    (let [a (:actor event)
          trace (:resolver-trace event)]
      ($ :div.obs-detail
         ($ :header
            ($ :span.obs-event (event-label event))
            ($ :span {:class (outcome-class (:outcome event))}
               (or (some-> (:outcome event) name) "—"))
            (when-let [rid (:request-id event)] ($ :span.obs-rid rid)))
         ($ :dl.obs-fields
            (obs-field "When" (or (:at event) (:ts event)))
            (obs-field "Entry" (some-> (:entry event) name))
            (obs-field "Latency" (when-let [ms (:latency-ms event)] (str ms "ms")))
            (obs-field "Instance" (:instance-id event))
            (obs-field "For" (or (get-in a [:on-behalf-of :subject]) "—"))
            (obs-field "Issued by" (get-in a [:on-behalf-of :issuer]))
            ;; 代理调用有三方，:actor 只有两格——第三格在这里露出来,
            ;; 否则「谁按的按钮」在委托场景里没有答案。
            (obs-field "Run by" (get-in a [:delegated-by :subject]))
            (obs-field "Asserted" (some-> (get-in a [:asserted-by]) name))
            (obs-field "Workspace" (:workspace event))
            (obs-field "Graph" (when-let [g (:graph event)]
                                 (str g (when-let [v (:graph-version event)] (str " @v" v)))))
            (obs-field "Token" (:token-id event))
            (obs-field "Member" (:member event))
            (obs-field "Role" (when-let [r (:role event)]
                                (str (name r)
                                     (when-let [p (:previous-role event)]
                                       (str "  ← " (name p))))))
            (obs-field "Op" (some-> (:op event) name))
            (obs-field "Reason" (some-> (:reason event) str (str/replace #"^:" ""))))
         (when-let [err (:error event)] ($ :p.obs-error (str err)))
         (when (seq (:queries event))
           ($ query-details/query-details {:queries (:queries event)}))
         (obs-chips "Entities" (:entities event))
         (obs-chips "Asked for" (get-in event [:eql-shape :query-keys]))
         (obs-chips "Filtered attributes" (:attrs-filtered event))
         ;; 语句层面的步骤：谁发的、发给什么源、多久、几行。**没有语句原文,
         ;; 也没有绑定值**——那两样按契约 §5 根本没写进审计。
         ;; 平台键在先，`:app` 是为了读得懂 8.10 P6 早期写下的那一批
         ;; （那时它还住在应用扩展里）——旧记录不该因为字段搬家就看不见了。
         (when-let [st (or (:statements event) (get-in event [:app :statements]))]
           ($ :div.obs-statements
              ($ :h5 (str "Statements (" (:count st) ")"
                          (when (:rows st) (str " · " (:rows st) " rows"))))
              ($ :div.obs-stmt-rows
                 (for [[i r] (map-indexed vector (:by-resolver st))]
                   ($ :div.obs-stmt {:key i}
                      ($ :span.obs-stmt-name (or (:resolver-id r) "—"))
                      ($ :span.obs-stmt-src (or (:source-type r) "—"))
                      ($ :span.obs-stmt-num (str (or (:rows r) 0) " rows"))
                      ($ :span.obs-stmt-num (str (or (:elapsed-ms r) 0) "ms")))))
              ($ :p.obs-note "语句原文与绑定值不在这里，也不在盘上——"
                 "审计只记名称、计数与时延（audit-event-v1 §5）。")))
         (when (seq (:resolvers trace)) ($ obs-resolver-timeline {:trace trace}))
         ;; 说清楚是**哪一种**。列两种可能等于让读的人自己去猜,
         ;; 而这两条信息（门、是否从盘上读回）面板都拿得到。
         (when (and (= :access/query (:event event)) (not (seq (:resolvers trace))))
           ($ :p.obs-note
              (cond
                (:obs/restored event)
                "这一条是从盘上的流水读回来的（重启之前记下的）。这次运行本来
                 就没有产生 resolver 时间线——落盘的是完整记录，不是删节版。"

                (contains? #{:data-eql :data-mcp} (:entry event))
                "这一条来自数据面。每-resolver 的耗时与语句回执只在 Builder 的
                 编排环境里产生，数据面根本不绑那条通道——不是关掉的日志，是不存在。"

                :else
                "这一条没有 resolver 时间线：这次运行没有产生 run-stats。")))))))

(defui obs-activity-page
  "谁做了什么。默认是内存环里最近的一批；点一个主体就改读**持久索引**——
   那份扛得住轮转与重启，正是并账时补上的能力。"
  []
  (let [[state set-state!] (uix/use-state {:events [] :scope nil :loading? true})
        [subject set-subject!] (uix/use-state nil)   ; {:kind :graph :id "ws/g"}
        [selected set-selected!] (uix/use-state 0)
        [error set-error!] (uix/use-state nil)
        load! (uix/use-callback
               (fn [subj]
                 (set-state! #(assoc % :loading? true))
                 (let [handlers {:on-success (fn [d] (set-error! nil)
                                               (set-state! (assoc d :loading? false)))
                                 :on-error (fn [e] (set-error! e)
                                             (set-state! #(assoc % :loading? false)))}]
                   (if subj
                     (api/obs-activity! (:kind subj) (:id subj) handlers)
                     (api/obs-activity! handlers))))
               [])]
    (uix/use-effect (fn [] (set-selected! 0) (load! subject) js/undefined) [load! subject])
    ($ :main.obs-page
       ($ :header.obs-head
          ($ :div
             ($ :span.eyebrow "Observability · activity")
             ($ :h2 "Who did what")
             ($ :p "成员与角色变更、graph 装载与切换、token 签发与吊销、"
                "以及每一次查询——" ($ :strong "拒绝与成功一样记") "。")
             ;; 「我看得到什么」是读这张表的前提，所以它跟着标题走,
             ;; 而不是横在表和标题之间当一条通告。
             (when-let [scope (:scope state)]
               ($ :p.obs-range
                  ($ :span.obs-range-label "范围")
                  (if (:admin? scope)
                    ($ :span "整台 instance")
                    ($ :<>
                       ($ :span (str/join "、" (:workspaces scope)))
                       ($ :span.obs-range-note
                          "· 无法归属到 workspace 的事件（认证失败、未解析出图的"
                          "被拒读取）仅管理员可见"))))))
          ($ :div.obs-actions
             ($ :button.btn {:type "button" :on-click #(load! subject)} "Refresh")))
       (when subject
         ($ :div.obs-subject-bar
            ($ :span.obs-chip (str (name (:kind subject)) " · " (:id subject)))
            ($ :span.obs-note "读的是持久索引——轮转与重启都带不走它")
            ($ :button.btn {:type "button" :on-click #(set-subject! nil)} "回到最近事件")))
       (when error ($ findings-view {:error error}))
       (cond
         (:loading? state) ($ :p.obs-empty "读取中…")
         (empty? (:events state))
         ($ :p.obs-empty (if subject "这个主体名下还没有事件。" "还没有事件被记录。"))
         :else
         ($ :div.obs-master-detail
          ($ :div.obs-table-wrap
            ($ :table.obs-table
               ($ :thead ($ :tr ($ :th "When") ($ :th "Event") ($ :th "Actor")
                             ($ :th "Subject") ($ :th "Entry") ($ :th "Outcome")))
               ($ :tbody
                  (for [[i e] (map-indexed vector (:events state))]
                    ($ :tr {:key (str (:request-id e) "-" i)
                            :class (when (= i (or selected 0)) "selected")
                            :on-click #(set-selected! i)}
                       ($ :td.obs-when ($ :span (or (:at e) (:ts e) "—"))
                          (when (:request-id e)
                            ($ :span.obs-rid (:request-id e))))
                       ($ :td ($ :span.obs-event (event-label e)))
                       ($ :td (actor-label e))
                       ($ :td
                          (cond
                            (:workspace e)
                            ($ :button.obs-link {:type "button"} (:workspace e))
                            (:graph e)
                            ($ :button.obs-link
                               {:type "button"
                                :on-click #(set-subject! {:kind :graph :id (:graph e)})}
                               (str (:graph e)
                                    (when-let [v (:graph-version e)] (str " @v" v))))
                            (:token-id e)
                            ($ :button.obs-link
                               {:type "button"
                                :on-click #(set-subject! {:kind :token :id (:token-id e)})}
                               (str "token " (:token-id e)))
                            (seq (:entities e))
                            ($ :span.obs-muted (str/join ", " (:entities e)))
                            :else ($ :span.obs-muted "—")))
                       ($ :td ($ :span.obs-entry (some-> (:entry e) name)))
                       ($ :td ($ :span {:class (outcome-class (:outcome e))}
                                 (or (some-> (:outcome e) name) "—"))
                          (when-let [r (:reason e)]
                            ($ :span.obs-reason (str/replace (str r) #"^:" "")))))))))
          ($ obs-detail {:event (get (vec (:events state)) (or selected 0))})))
       ($ :p.obs-foot
          ($ :strong "都在盘上") "：流水（完整、会轮转）与按主体的持久索引（不随轮转丢失）"
          "都写在这台 instance 的 obs 目录下。这张表显示的是最近的一批——重启后"
          "由流水尾部回填，所以它不会因为进程重启而变空。"))))

(defui obs-logs-page
  "进程日志。与审计流水刻意分开：读者不同、保留期不同、泄漏风险也不同。"
  []
  (let [[state set-state!] (uix/use-state {:lines [] :loading? true})
        [level set-level!] (uix/use-state "")
        [q set-q!] (uix/use-state "")
        [error set-error!] (uix/use-state nil)
        load! (uix/use-callback
               (fn [level* q*]
                 (set-state! #(assoc % :loading? true))
                 (api/obs-logs! {:level level* :q q*
                                 :on-success (fn [d] (set-error! nil)
                                               (set-state! (assoc d :loading? false)))
                                 :on-error (fn [e] (set-error! e)
                                             (set-state! #(assoc % :loading? false)))}))
               [])]
    (uix/use-effect (fn [] (load! level q) js/undefined) [load! level q])
    ($ :main.obs-page
       ($ :header.obs-head
          ($ :div
             ($ :span.eyebrow "Observability · logs")
             ($ :h2 "What the process said")
             ($ :p "结构化日志（" ($ :code "app.jsonl") "）：模块与库的每一行都在这里。"
                ($ :strong "仅管理员") "——它是整个进程的日志，带不上 workspace 归属,"
                "过滤不了就不该给别人看。"))
          ($ :div.obs-actions
             ($ :select {:value level :aria-label "Level"
                         :on-change #(set-level! (.. % -target -value))}
                ($ :option {:value ""} "所有级别")
                (for [l ["error" "warn" "info" "debug"]]
                  ($ :option {:key l :value l} l)))
             ($ :input {:value q :placeholder "过滤…" :aria-label "Filter"
                        :on-change #(set-q! (.. % -target -value))})
             ($ :button.btn {:type "button" :on-click #(load! level q)} "Refresh")))
       (when error ($ findings-view {:error error}))
       (cond
         (:loading? state) ($ :p.obs-empty "读取中…")
         (false? (:present? state))
         ($ :p.obs-empty (str "还没有日志文件：" (:path state)
                              "。它在第一条日志写下时创建。"))
         (empty? (:lines state)) ($ :p.obs-empty "没有匹配的行。")
         :else
         ($ :div.obs-log
            (for [[i l] (map-indexed vector (reverse (:lines state)))]
              ($ :div.obs-log-line {:key i :class (str "lvl-" (or (:level l) "info"))}
                 ($ :span.obs-log-ts (:ts l))
                 ($ :span.obs-log-lvl (or (:level l) "info"))
                 ($ :span.obs-log-msg (or (:msg l) (pr-str (dissoc l :ts :level))))
                 (when (:request-id l)
                   ($ :span.obs-rid (:request-id l))))))))))

(defui obs-metrics-page
  "进程内计数。**重启即归零，这是设计**——进程本身就是这份数据的来源。"
  []
  (let [[state set-state!] (uix/use-state {:loading? true})
        [error set-error!] (uix/use-state nil)
        load! (uix/use-callback
               (fn []
                 (set-state! #(assoc % :loading? true))
                 (api/obs-metrics! {:on-success (fn [d] (set-error! nil)
                                                  (set-state! (assoc d :loading? false)))
                                    :on-error (fn [e] (set-error! e)
                                                (set-state! #(assoc % :loading? false)))}))
               [])]
    (uix/use-effect (fn [] (load!) js/undefined) [load!])
    ($ :main.obs-page
       ($ :header.obs-head
          ($ :div
             ($ :span.eyebrow "Observability · metrics")
             ($ :h2 "How much, how fast")
             ($ :p "进程内计数与时延。" ($ :strong "重启归零是设计") "：进程就是这份数据的来源,"
                "要长期序列请把它接到外部采集器。"))
          ($ :div.obs-actions
             ($ :button.btn {:type "button" :on-click #(load!)} "Refresh")))
       (when error ($ findings-view {:error error}))
       (if (:loading? state)
         ($ :p.obs-empty "读取中…")
         ($ :<>
            ($ :div.obs-cards
               ($ :article.obs-card
                  ($ :h4 "Uptime")
                  ($ :strong (str (Math/round (/ (or (:uptime-ms state) 0) 60000.0)) " min")))
               (for [[k v] (:rejections state)]
                 ($ :article.obs-card {:key (str k)}
                    ($ :h4 (str/replace (str (name k)) #"-" " "))
                    ($ :strong (str v)))))
            (if (empty? (:counters state))
              ($ :p.obs-empty "还没有请求被计数。")
              ($ :div.obs-table-wrap
                 ($ :table.obs-table
                    ($ :thead ($ :tr ($ :th "Source") ($ :th "Queries")
                                  ($ :th "Mutations") ($ :th "Errors")))
                    ($ :tbody
                       (for [[source counts] (:counters state)]
                         ($ :tr {:key (str source)}
                            ($ :td (str (name source)))
                            ($ :td (str (or (:queries counts) 0)))
                            ($ :td (str (or (:mutations counts) 0)))
                            ($ :td (str (or (:errors counts) 0)))))))))
            (when-let [url (:prometheus-url state)]
              ($ :p.obs-foot
                 "Prometheus 抓取点：" ($ :code url)
                 "——免认证，和 /health 并排挂在" ($ :strong "数据面") "监听器上（默认只在"
                 " loopback）。吐出的只有聚合量：uptime、按 source/op 的计数、"
                 "时延分位、拒绝计数；没有用户、没有实体名、没有值。")))))))

;; ---------------------------------------------------------------------------
;; Platform shell
;; ---------------------------------------------------------------------------

(defn- wordmark-icon
  "The Open Atlas mark, the same as Workbench's, so both products open the same way."
  []
  ($ :svg.wordmark-icon {:width 26 :height 26 :view-box "0 0 24 24" :aria-hidden "true"}
     ($ :rect {:x 1 :y 1 :width 22 :height 22 :rx 6 :fill "currentColor" :opacity ".14"})
     ($ :rect {:x 5.5 :y 5.5 :width 5.5 :height 5.5 :rx 1.6 :fill "currentColor"})
     ($ :rect {:x 13 :y 5.5 :width 5.5 :height 5.5 :rx 1.6 :fill "currentColor" :opacity ".55"})
     ($ :rect {:x 5.5 :y 13 :width 5.5 :height 5.5 :rx 1.6 :fill "currentColor" :opacity ".55"})
     ($ :rect {:x 13 :y 13 :width 5.5 :height 5.5 :rx 2.75 :fill "currentColor"})))

(defn- shell-icon [kind]
  (let [props {:viewBox "0 0 24 24" :width 17 :height 17 :fill "none"
               :stroke "currentColor" :strokeWidth 1.8
               :strokeLinecap "round" :strokeLinejoin "round"
               :aria-hidden true}]
    (case kind
      :graphs
      ($ :svg props
         ($ :circle {:cx 6 :cy 7 :r 2.2})
         ($ :circle {:cx 18 :cy 7 :r 2.2})
         ($ :circle {:cx 12 :cy 18 :r 2.2})
         ($ :path {:d "M8 8.2l3 7.5M16 8.2l-3 7.5M8.2 7h7.6"}))

      :explorer
      ($ :svg props
         ($ :circle {:cx 10.5 :cy 10.5 :r 6.5})
         ($ :path {:d "M15.5 15.5L21 21M8 10.5h5M10.5 8v5"}))

      :query
      ($ :svg props
         ($ :path {:d "M8 5L3 12l5 7M16 5l5 7-5 7M14 3l-4 18"}))

      ;; A pulse, a stack of lines, a bar chart: what each page actually shows.
      :activity
      ($ :svg props
         ($ :path {:d "M3 12h3.5l2-6 3.5 12 2.5-8 1.5 2H21"}))

      :logs
      ($ :svg props
         ($ :path {:d "M4 6h16M4 10h16M4 14h10M4 18h7"}))

      :metrics
      ($ :svg props
         ($ :path {:d "M4 20V10M10 20V4M16 20v-7M22 20H2"}))

      :team
      ($ :svg props
         ($ :circle {:cx 9 :cy 8 :r 3})
         ($ :path {:d "M3.5 20c0-3 2.5-5 5.5-5s5.5 2 5.5 5M16 5.5a3 3 0 010 5M20.5 20c0-2.3-1.4-3.9-3.5-4.5"}))

      :profile
      ($ :svg props
         ($ :circle {:cx 12 :cy 8 :r 4})
         ($ :path {:d "M4.5 21c0-4.2 3.3-7 7.5-7s7.5 2.8 7.5 7"}))

      :source
      ($ :svg props
         ($ :ellipse {:cx 12 :cy 6 :rx 7 :ry 2.6})
         ($ :path {:d "M5 6v12c0 1.5 3.1 2.7 7 2.7s7-1.2 7-2.7V6M5 12c0 1.5 3.1 2.7 7 2.7s7-1.2 7-2.7"}))

      ;; A table on the left, a graph node on the right, an arrow between them:
      ;; the whole of what this page does (owner, 2026-09-03). Deliberately not
      ;; `:source` — that glyph is the register's, and this entry is the
      ;; modelling flow, not the connection admin it used to hide behind.
      :build
      ($ :svg props
         ($ :rect {:x 2.5 :y 5 :width 8 :height 14 :rx 1.4})
         ($ :path {:d "M2.5 9h8M6.5 9v10"})
         ($ :path {:d "M12.5 12h4"})
         ($ :path {:d "M15 10l2 2-2 2"})
         ($ :circle {:cx 19.5 :cy 7 :r 2})
         ($ :circle {:cx 19.5 :cy 17 :r 2})
         ($ :path {:d "M19.5 9v6"}))

      :key
      ($ :svg props
         ($ :circle {:cx 8 :cy 12 :r 3.4})
         ($ :path {:d "M11.4 12H21M18 12v3M15 12v2.2"}))

      ;; A container with its lid off: the workspace is what holds everything
      ;; else. Distinct from `:explorer` on purpose (8.4.15) — the Graph Editor
      ;; owns that glyph and two rail entries wearing one icon read as two
      ;; views of the same page.
      :workspace
      ($ :svg props
         ($ :path {:d "M3 8.5L12 4l9 4.5M3 8.5V17l9 4.5 9-4.5V8.5M3 8.5l9 4.5 9-4.5M12 13v8.5"}))

      :collapse
      ($ :svg props
         ($ :path {:d "M14 6l-6 6 6 6"}))

      ($ :svg props ($ :circle {:cx 12 :cy 12 :r 8})))))

(defui shell-nav-button [{:keys [page active-page label icon on-select]}]
  ($ :button.shell-nav-item
     {:class (when (= page active-page) "selected")
      :type "button"
      :title label
      :on-click #(on-select page)}
     ($ :span.shell-nav-icon (shell-icon icon))
     ($ :span.shell-nav-label label)))

(defui change-password-card
  "Change the local password, as a card on My Profile (owner, 2026-10-01: a
   password is part of who you are signed in as, not a destination of its own).
   Rendered only for accounts that have a local secret — a pure SSO account
   would be offered a form that can never succeed."
  []
  (let [[current-password set-current-password!] (uix/use-state "")
        [new-password set-new-password!] (uix/use-state "")
        [confirm-password set-confirm-password!] (uix/use-state "")
        [error set-error!] (uix/use-state nil)
        [change-success set-change-success!] (uix/use-state nil)
        [busy? set-busy!] (uix/use-state false)
        change-feedback-ref (uix/use-ref nil)
        current-password-trim (str/trim current-password)
        new-password-trim (str/trim new-password)
        confirm-password-trim (str/trim confirm-password)
        current-valid? (seq current-password-trim)
        new-valid? (and (seq new-password-trim) (>= (count new-password-trim) 12))
        confirm-valid? (seq confirm-password-trim)
        match? (= new-password-trim confirm-password-trim)
        different-from-current? (not= new-password-trim current-password-trim)
        change-password-errors (cond-> []
                                 (and (seq current-password) (not current-valid?))
                                 (conj "Current password is required.")
                                 (and (seq new-password) (not new-valid?))
                                 (conj "New password must be at least 12 characters.")
                                 (and (seq confirm-password) (not confirm-valid?))
                                 (conj "Password confirmation is required.")
                                 (and (seq new-password) (seq confirm-password) (not match?))
                                 (conj "The new passwords do not match.")
                                 (and (seq new-password) (seq current-password) (not different-from-current?))
                                 (conj "New password must be different from current password."))
        disable-submit? (or busy? (not current-valid?) (not new-valid?) (not confirm-valid?)
                             (not match?) (not different-from-current?))]
    (uix/use-effect
     (fn []
       (when (or error change-success)
         (when-let [node @change-feedback-ref]
           (.focus node)))
       js/undefined)
     [error change-success])
    ($ :article.profile-card.change-password-card
             ($ :h3 "Password")
             ($ :p.profile-help "Changing it signs out every active Studio session.")
             ($ :form.change-password-form
                {:on-submit
                 (fn [event]
                   (.preventDefault event)
                   (set-error! nil)
                   (set-change-success! nil)
                   (when (not disable-submit?)
                     (set-busy! true)
                     (api/change-password!
                      {:current-password current-password
                       :new-password new-password}
                      {:on-success (fn [_]
                                    (set-busy! false)
                                    (set-change-success! "Password changed. Signing out.")
                                    (js/setTimeout #(.reload js/location) 650))
                       :on-error (fn [failure]
                                   (set-busy! false)
                                   (set-error! failure))})))}
                ($ :label.change-password-field
                   ($ :span "Current password")
                   ($ :input {:type "password" :value current-password :required true
                              :placeholder "Enter current password" :auto-complete "current-password"
                              :aria-label "Current password"
                              :aria-invalid (boolean (and (seq current-password) (not current-valid?)))
                              :aria-describedby "change-password-hints"
                              :on-change #(set-current-password! (.. % -target -value))}))
                ($ :label.change-password-field
                   ($ :span "New password")
                   ($ :input {:type "password" :value new-password :required true :min-length 12
                              :placeholder "At least 12 characters" :auto-complete "new-password"
                              :aria-label "New password"
                              :aria-invalid (boolean (and (seq new-password) (not new-valid?)))
                              :aria-describedby "change-password-hints"
                              :on-change #(set-new-password! (.. % -target -value))}))
                ($ :label.change-password-field
                   ($ :span "Confirm new password")
                   ($ :input {:type "password" :value confirm-password :required true :min-length 12
                              :placeholder "Enter the new password again" :auto-complete "new-password"
                              :aria-label "Confirm new password"
                              :aria-invalid (boolean (and (seq confirm-password) (not match?)))
                              :aria-describedby "change-password-hints"
                              :on-change #(set-confirm-password! (.. % -target -value))}))
                ($ :div.change-password-actions
                   ($ :div#change-password-hints.action-hints
                     (for [password-error change-password-errors]
                       ($ :p.hint password-error)))
                   ($ :button.btn.primary {:type "submit" :disabled disable-submit?
                                          :aria-busy busy?
                                          :aria-describedby "change-password-hints"}
                      (if busy? "Changing…" "Change password"))))
             (when error
               ($ feedback-banner
                  {:kind :error
                   :message (normalize-api-error error)
                   :error error
                   :id "change-password-error"
                   :focus-ref change-feedback-ref}))
             (when change-success
               ($ feedback-banner
                  {:kind :success
                   :message change-success
                   :id "change-password-success"
                   :focus-ref change-feedback-ref})))))

(defui profile-page [{:keys [session ui-theme on-theme]}]
  ;; Owner, 2026-10-04: one plain column — who you are, your workspaces, theme, and password on demand.
  (let [{:keys [principal workspaces user]} session
        ;; A sign-out that fails must SAY so. Dropping the error left the
        ;; button looking broken while the session was still live — the same
        ;; silence that made a failed removal read as a successful one.
        [sign-out-error set-sign-out-error!] (uix/use-state nil)
        [changing? set-changing!] (uix/use-state false)
        ;; A session from the identity provider carries its issuer; a Studio account carries open-atlas-local.
        sso? (not= "open-atlas-local" (:issuer principal))]
    ($ :main.profile-page.profile-simple
       ($ :article.profile-card.identity-card
          ($ :div.profile-avatar
             (if-let [label (or (:name user) (:email user))]
               (str/upper-case (subs label 0 1))
               "?"))
          ($ :div.profile-primary
             ($ :h3 (or (:name user) (:email user)))
             ($ :p (str (:email user) " · " (if sso? "company sign-in" "Studio account"))))
          ($ :button.btn
             {:on-click #(do (set-sign-out-error! nil)
                             (api/logout!
                              {:on-success (fn [_] (.reload js/location))
                               :on-error set-sign-out-error!}))}
             "Sign out")
          (when sign-out-error
            ($ :<>
               ($ :p.hint.bad "You are still signed in — the sign-out was refused.")
               ($ findings-view {:error sign-out-error}))))
       ($ :article.profile-card
          ($ :h3 "Workspaces")
          ($ :div.profile-workspaces
             (for [{workspace-name :workspace/name
                    workspace-role :workspace/role} workspaces]
               ($ :div.profile-workspace {:key workspace-name}
                  ($ :span workspace-name)
                  ($ :span.member-role (name workspace-role))))))
       ($ :article.profile-card.profile-row
          ($ :h3 "Theme")
          ($ :div.profile-theme
             ($ :button.btn {:class (when (= ui-theme :light) "on")
                             :on-click #(on-theme :light)}
                "Light")
             ($ :button.btn {:class (when (= ui-theme :dark) "on")
                             :on-click #(on-theme :dark)}
                "Dark")))
       (when (:has-password? user)
         (if changing?
           ($ :<>
              ($ change-password-card)
              ($ :button.btn-text.profile-cancel {:on-click #(set-changing! false)} "Cancel"))
           ($ :article.profile-card.profile-row
              ($ :h3 "Password")
              ($ :button.btn {:on-click #(set-changing! true)} "Change password…")))))))

;; ---------------------------------------------------------------------------
;; Query page
;; ---------------------------------------------------------------------------

(defui zoom-btn
  "Maximise / restore, on the pane's own header.

   A workbench pane is small because it shares the window with four others, and
   the moment you actually need one — reading a 13 KB result, following a plan
   four levels deep — the others are in the way. Zoom is per-pane and exclusive:
   one pane fills the page, everything else steps out, and the same button brings
   it back. The layout percentages are untouched underneath, so restoring returns
   to the arrangement you had rather than to a default."
  [{:keys [on? on-toggle]}]
  ($ :button.zoom {:title (if on? "restore" "maximise")
                   :aria-label (if on? "Restore pane" "Maximise pane")
                   :aria-pressed (boolean on?)
                   :on-click on-toggle}
     ($ :span.zoom-glyph (if on? "\u2921" "\u2922"))))

(defui plan-pane
  "The plan, every level of it.

   A query with joins does not have one plan — Pathom plans and runs each nested
   entity separately — so the levels are grafted into one graph at the step that
   produced each join, and the dashed edge carries how many rows ran through it.
   Tabs were the first attempt and they were wrong: they made you go and look for
   what happened next, when what happened next is the shape of the query."
  [{:keys [plan view on-view focus on-focus theme height zoomed? on-zoom]}]
  ($ :section.pane {:style (when height {:flex (str "0 0 " height "%")})}
     ($ :div.pane-head
        ($ :span "Plan")
        (when plan
          ($ :span.count (str (count (:steps plan)) " steps"
                              (when-let [n (seq (:nested plan))]
                                (str " · " (inc (count n)) " levels"))
                              (when (seq (:unreachable plan))
                                (str " · unreachable: " (str/join ", " (:unreachable plan)))))))
        ($ :span.spacer)
        ($ graph/plan-legend {:ran? (boolean (some :status (:steps plan))) :theme theme})
        ($ :div.seg
           ($ :button {:class (when (= view :graph) "on") :on-click #(on-view :graph)} "graph")
           ($ :button {:class (when (= view :table) "on") :on-click #(on-view :table)} "table"))
        (when on-zoom ($ zoom-btn {:on? zoomed? :on-toggle on-zoom})))
     (if (= view :table)
       ($ :div.pane-body ($ graph/plan-table {:steps (:steps plan)}))
       ($ graph/plan-graph {:graph (:graph plan) :focus focus :on-select on-focus
                            :theme theme}))))

(defui history-panel
  "One History panel, two sources, told apart by tab (owner 2026-10-10):

   - **mine** — what this Studio's users ran here, on the DRAFT (Pathom Viz's
     History: server-held, survives a reload; ADR-012-A §4);
   - **consumers · failed / all**, **pinned** — what agents, Workbench and
     applications sent to the PUBLISHED data plane (proposal
     studio-saved-queries §4). Never result data. Pin keeps one for 7 days; a
     capture rule pins matching failures for up to a day.

   Clicking a row loads it into the editor either way."
  [{:keys [entries on-pick on-delete graph on-pick-recent on-error width]}]
  (let [[tab set-tab!] (uix/use-state :mine)
        [data set-data!] (uix/use-state nil)
        [subject set-subject!] (uix/use-state "")
        reload! (uix/use-callback
                 (fn [] (when graph
                          (api/recent-queries! graph {:on-success set-data! :on-error on-error})))
                 [graph on-error])
        act! (fn [body] (api/recent-query-action! (assoc body :graph graph)
                                                  {:on-success (fn [_] (reload!)) :on-error on-error}))
        pinned (set (map :id (:pinned data)))
        consumer? (not= :mine tab)
        rows (get data tab)]
    (uix/use-effect
     (fn [] (reload!)
       (let [id (js/setInterval reload! 5000)] #(js/clearInterval id)))
     [reload!])
    ($ :section.pane.pane-history {:style {:flex (str "0 0 " width "%")}}
       ($ :div.pane-head.history-head
          ($ :span "History")
          ($ :span.history-tabs
             (for [[k label n] [[:mine "mine" (count entries)]
                                [:failed "failed" (count (:failed data))]
                                [:all "consumers" (count (:all data))]
                                [:pinned "pinned" (count (:pinned data))]]]
               ($ :button.btn {:key (name k) :class (when (= k tab) "on")
                               :title (case k
                                        :mine "Queries run here, on the draft"
                                        :failed "Refused requests from agents, Workbench and apps"
                                        :all "Every request from agents, Workbench and apps"
                                        :pinned "Requests kept for 7 days")
                               :on-click #(set-tab! k)}
                  (str label " " n)))))
       ($ :div.pane-body
          (cond
            (not consumer?)
            (if (empty? entries)
              ($ :p.empty-note "Nothing run yet.")
              (for [[i {:keys [query entity] :as entry}] (map-indexed vector entries)]
                ($ :div.history-item {:key (or (:id entry) i) :on-click #(on-pick entry)}
                   ($ :button.x {:title "forget"
                                 :on-click (fn [e] (.stopPropagation e) (on-delete entry))} "\u00d7")
                   ($ editor/edn-view {:text (str/trim query)})
                   (when (seq (str/trim (or entity "")))
                     ($ editor/edn-view {:text (str/trim entity)})))))

            (nil? graph) ($ :p.empty-note "Pick a graph: consumer requests are kept per graph.")
            (empty? rows) ($ :p.empty-note
                             (case tab
                               :failed "No refused requests from consumers since this Studio started."
                               :pinned "Nothing pinned. Star a request to keep it for 7 days."
                               "No requests from consumers since this Studio started."))
            :else
            (for [{:keys [id at outcome entry tool saved-query on-behalf-of token-id latency-ms eql findings truncated]}
                  rows]
              ($ :div.history-item.recent-item {:key id :on-click #(on-pick-recent eql)
                                                :title "Load into the editor"}
                 ($ :div.recent-meta
                    ($ :span {:class (str "recent-outcome " (if (= "ok" outcome) "ok" "bad"))} outcome)
                    ($ :span.mono (subs (str at) 0 (min 19 (count (str at)))))
                    ($ :span (or tool entry))
                    (when saved-query ($ :span.mono (str "saved:" (:name saved-query))))
                    ($ :span.mono (or on-behalf-of (some-> token-id (subs 0 (min 8 (count token-id))))))
                    ($ :span (str latency-ms " ms"))
                    ($ :button.x {:title (if (pinned id) "unpin" "pin for 7 days")
                                  :on-click (fn [e] (.stopPropagation e)
                                              (act! {:action (if (pinned id) :unpin :pin) :id id}))}
                       (if (pinned id) "\u2605" "\u2606")))
                 ($ editor/edn-view {:text eql})
                 (when truncated ($ :p.hint "EQL cut at 8 KB."))
                 (when-let [f (first findings)]
                   ($ :p.hint.bad (str (subs (str (:code f)) 1) " — " (:message f))))))))
       (when (and consumer? graph)
         ($ :div.recent-rules
            (for [{:keys [id match expires-at]} (:rules data)]
              ($ :div.hint {:key id}
                 (str "Capturing " (if (:failures-only? match) "failures" "all requests")
                      (when (:subject match) (str " for " (:subject match)))
                      " until " (subs (str expires-at) 0 16))
                 ($ :button.x {:title "stop" :on-click #(act! {:action :remove-rule :id id})} "\u00d7")))
            ($ :div.recent-rule-form
               ($ :input {:placeholder "user email (optional)" :value subject
                          :on-change #(set-subject! (.. % -target -value))})
               ($ :button.btn {:title "Pin every refused request (for this user, if given) for the next 24 hours"
                               :on-click #(act! (cond-> {:action :add-rule :hours 24 :failures-only? true}
                                                  (seq subject) (assoc :subject subject)))}
                  "capture failures 24h")))))))

(defn- normalize-default
  "The comparable shape of a default query: an entity nobody typed is ABSENT.

   `editor/parse-request` always yields an `:entity` key, nil when the entity
   editor is empty, while a stored default that never had one has no such key.
   Compared raw, those two are unequal and the toolbar would offer to save a
   default that is already saved."
  [dq]
  (when (and dq (seq (:query dq)))
    (cond-> {:query (:query dq)}
      (seq (:entity dq)) (assoc :entity (:entity dq)))))

(defui lab-page
  "The Lab, laid out as Pathom Viz's query editor: a toolbar, then History |
   Request over Entity | Result across the top, then the plan graph and the trace
   underneath. Any one of the four working panes can be zoomed to fill the page."
  [{:keys [graph query-text on-query-text on-semantic-draft attributes
           on-explain on-run busy? lenient? on-lenient parse-error entity-error
           outcome plan focus on-focus view on-view theme scope-note run-target-note
           history show-history? on-show-history on-pick-history on-delete-history
           recent-graph on-pick-recent on-recent-error
           on-set-default default-saved? can-set-default?]}]
  (let [[top set-top!]       (uix/use-state 65)
        [hist set-hist!]     (uix/use-state 20)
        [req set-req!]       (uix/use-state 55)
        [diagnostics? set-diagnostics!] (uix/use-state false)
        [menu? set-menu!] (uix/use-state false)
        [diagnostic-tab set-diagnostic-tab!] (uix/use-state :plan)
        [zoom set-zoom!]     (uix/use-state nil)
        zoomed?  (fn [k] (= zoom k))
        toggle   (fn [k] #(set-zoom! (if (= zoom k) nil k)))
        ;; Escape leaves a zoomed pane. A mode you can enter with one click and
        ;; only leave by finding the same small button again is a trap.
        _ (uix/use-effect
           (fn []
             (let [on-key (fn [^js e] (when (= "Escape" (.-key e)) (set-zoom! nil)))]
               (.addEventListener js/window "keydown" on-key)
               #(.removeEventListener js/window "keydown" on-key)))
           [])

        eql-pane
        ($ :section.pane {:key "eql" :style {:flex "1 1 auto"}}
           ($ :div {:class (str "pane-head" (when parse-error " bad"))}
              ($ :span "Request")
              ($ :span.count {:title "The shape execute_eql takes: what is known about the entity, then what to read."}
                 "{:entity … :query …}")
              ($ :span.spacer)
              (when parse-error ($ :span.count.bad parse-error))
              ($ zoom-btn {:on? (zoomed? :eql) :on-toggle (toggle :eql)}))
           ($ editor/edn-editor {:text query-text :on-change on-query-text
                                 :on-run on-run :on-explain on-explain
                                 :attributes attributes :theme theme}))

        result-pane
        ($ :section.pane.pane-result {:key "result"}
           ($ :div.pane-head
              ($ :span "Result")
              (when (= :result (:kind outcome))
                ($ :span.count (str (:elapsed-ms (:meta (:value outcome))) " ms round trip")))
              ($ :span.spacer)
              (when (:total-ms plan) ($ :span.count (str (:total-ms plan) " ms in Pathom")))
              ($ zoom-btn {:on? (zoomed? :result) :on-toggle (toggle :result)}))
           ($ :div.pane-body
              (case (:kind outcome)
                :error  ($ :<> ($ :p.hint.bad (name (or (:code (:value outcome)) :failed)))
                           ($ :pre.out (error-text (:value outcome))))
                :result ($ :<>
                           ($ editor/edn-view {:text (with-out-str (pprint/pprint (:result (:value outcome))))})
                           (when (seq (:issues (:value outcome)))
                             ($ editor/edn-view {:text (with-out-str (pprint/pprint {:partial-errors (:issues (:value outcome))}))}))
                           (when (seq (:source-diagnostics (:value outcome)))
                             ($ editor/edn-view {:text (with-out-str (pprint/pprint {:source-diagnostics (:source-diagnostics (:value outcome))}))}))
                           ($ statements-view {:statements (:statements (:value outcome))}))
                ($ :p.empty-note "Run a query to see its result."))))

        trace-pane
        ($ :section.pane {:key "trace"}
           ($ :div.pane-head
              ($ :span "Trace")
              (when-let [n (:calls (:counts plan))]
                ($ :span.count (str n " calls in " (:graphs (:counts plan)) " graphs")))
              ($ :span.spacer)
              (when (:timeline-truncated? plan) ($ :span.count "first 300 shown"))
              ($ zoom-btn {:on? (zoomed? :trace) :on-toggle (toggle :trace)}))
           ($ :div.pane-body ($ trace/timeline {:plan plan})))

        the-plan-pane
        ($ plan-pane {:key "plan" :plan plan :view view :on-view on-view
                      :height nil
                      :zoomed? (zoomed? :plan) :on-zoom (toggle :plan)
                      :focus focus :on-focus on-focus :theme theme})]

    (uix/use-effect (fn [] (when plan (set-diagnostics! true)) js/undefined) [plan])
    ($ :main.page-lab
       ($ :div.lab-toolbar
          (when (seq graph) ($ contract-query-tools {:graph graph :on-draft on-semantic-draft
                                                     :on-request-text on-query-text}))
          (when run-target-note
            ($ :span.lab-target {:title run-target-note} run-target-note))
          ($ :label.check
             ($ :input {:type "checkbox" :checked lenient?
                        :on-change #(on-lenient (.. % -target -checked))})
             "lenient mode")
          ;; Toolbar (owner 2026-10-10): panel toggles on the left as one
          ;; segmented control, the two acts on the query on the right with
          ;; `run query` the only filled button, occasional acts in a menu.
          ($ :div.lab-views {:role "group" :aria-label "Panels"}
             ($ :button.btn {:class (when show-history? "on")
                             :aria-pressed (boolean show-history?)
                             :title "Your runs here, and what agents and apps sent to the published graph"
                             :on-click #(on-show-history (not show-history?))} "History")
             ($ :button.btn {:aria-expanded diagnostics?
                             :aria-controls "query-diagnostics"
                             :aria-pressed (boolean diagnostics?)
                             :class (when diagnostics? "on")
                             :on-click #(set-diagnostics! (not diagnostics?))} "Diagnostics"))
          ($ :div.lab-actions
          ($ :button.btn {:on-click on-explain
                          :disabled (query-tools/query-action-disabled?
                                     {:busy? busy? :handler on-explain :scope-block scope-note
                                      :parse-error parse-error :entity-error entity-error})}
             "explain")
          ($ :button.btn.primary {:on-click on-run
                                  :title (or scope-note run-target-note "")
                                  :disabled (query-tools/query-action-disabled?
                                             {:busy? busy? :handler on-run :scope-block scope-note
                                              :parse-error parse-error :entity-error entity-error})}
             "run query")
          ($ :div.lab-menu
             ($ :button.btn {:aria-haspopup "menu" :aria-expanded menu? :title "More"
                             :on-click #(set-menu! (not menu?))} "\u22ef")
             (when menu?
               ($ :div.lab-menu-list {:role "menu" :on-mouse-leave #(set-menu! false)}
                  ($ :button {:role "menuitem"
                              :on-click (fn [] (set-menu! false)
                                          (on-query-text (editor/format-request query-text)))}
                     "Format request")
                  ;; Saving what is in the editors as this graph's opening
                  ;; query: an act on the graph, not another way to execute.
                  ;; Refused for the same reasons a run is.
                  (when can-set-default?
                    ($ :button {:role "menuitem"
                                :title "Open this graph with what is in the editors"
                                :disabled (or busy? default-saved? (some? parse-error) (some? entity-error))
                                :on-click (fn [] (set-menu! false) (on-set-default))}
                       (if default-saved? "Saved as default" "Set as default"))))))))
       (when scope-note ($ :p.hint.bad.lab-scope-note scope-note))

       (if zoom
         ($ :div.lab-solo
            (case zoom
              :eql eql-pane :result result-pane
              :plan the-plan-pane :trace trace-pane nil))

         ($ :<>
            ($ :div.lab-row {:style (if diagnostics? {:height (str top "%")} {:flex "1 1 auto"})}
               (when show-history?
                 ($ :<>
                    ($ history-panel {:entries history :on-pick on-pick-history
                                      :on-delete on-delete-history
                                      :graph recent-graph :on-pick-recent on-pick-recent
                                      :on-error on-recent-error :width (max hist 28)})
                    ($ gutter {:dir :x :pct hist :on-pct set-hist!})))

               ($ :div.lab-request {:style {:flex (str "0 0 " req "%")}}
                  eql-pane)

               ($ gutter {:dir :x :pct req :on-pct set-req!})
               result-pane)

            (when diagnostics?
              ($ :<>
                 ($ gutter {:dir :y :pct top :on-pct set-top!})
                 ($ :div.lab-diagnostics {:id "query-diagnostics"}
                    ($ :div.diagnostic-tabs {:role "tablist" :aria-label "Query diagnostics"}
                       (for [[k label] [[:plan "Plan"] [:trace "Trace"]]]
                         ($ :button.pane-tab {:key k :role "tab" :id (str "diagnostic-" (name k))
                                             :aria-selected (= diagnostic-tab k)
                                             :aria-controls "diagnostic-content"
                                             :class (when (= diagnostic-tab k) "on")
                                             :on-click #(set-diagnostic-tab! k)} label))
                       ($ :span.spacer)
                       ($ :button.btn {:aria-label "Close diagnostics" :on-click #(set-diagnostics! false)} "Close"))
                    ($ :div.lab-bottom {:id "diagnostic-content" :role "tabpanel"
                                        :aria-labelledby (str "diagnostic-" (name diagnostic-tab))}
                       (if (= diagnostic-tab :plan) the-plan-pane trace-pane))))))))))

;; ---------------------------------------------------------------------------
;; Footer
;; ---------------------------------------------------------------------------

(defn- short-rev
  "The revision's own hash, without the `sha256:` tag CSS already implies by
   position — `subs` alone throws on a short/nil string, which a status still
   loading or a server that has never synced both are."
  [rev]
  (when (and rev (>= (count rev) 19)) (subs rev 7 19)))

(defui status-bar [{:keys [status model-status syncing? on-sync]}]
  ($ :footer.status
     ($ :span {:class (str "live" (when-not (:ready? status) " down"))}
        (if (:ready? status) "● ready" "● not ready"))
     ($ :span (if (contains? status :environment-count)
                (str (:environment-count status) " graph environments cached · "
                     (:environment-cache-ttl-seconds status) " s idle TTL")
                (str (:resolver-count status) " resolvers · " (:connection-count status) " connections")))
     (when-let [rejection (:last-rejection status)]
       ($ :span.rejected
          (str "reload rejected — "
               (str/join "; " (map (fn [e] (str (:file e) " " (name (:code e)) ": " (:message e)))
                                   (:errors rejection))))))
     ($ :span.spacer)
     ;; The model/server revision gap (ADR-034): what is authored may be ahead
     ;; of what the Pathom server is answering queries from, and that is the
     ;; one fact this footer exists to never hide.
     ($ :span.mono {:title "model revision"} (str "model " (or (short-rev (:revision model-status)) "…")))
     ($ :span.mono {:class (when (and model-status (not (:in-sync? model-status))) "ahead")
                    :title "server revision — what Explain/Run answer from"}
        (str "server " (if-let [rev (:server-revision model-status)] (short-rev rev) "none synced")))
     (when (and model-status (not (:in-sync? model-status)))
       ($ :span.warn "model ahead of server"))
     ($ :button.btn {:on-click on-sync :disabled (or syncing? (nil? model-status))}
        (if syncing? "syncing…" "sync"))))

(defui sync-banner
  "Persistent, not manually dismissed: it shows whatever the live `:last-sync`
   poll currently says, so it clears itself the moment a later sync succeeds
   — the same state-driven rule model_status's :last-sync is designed around
   (PRD tool #1), never a click the user has to remember to make."
  [{:keys [last-sync]}]
  (when (= :failed (:status last-sync))
    ($ :div.sync-banner
       ($ :strong "sync failed")
       ($ :span.mono (str " — attempted " (some-> (:attempted-revision last-sync) short-rev)))
       ($ :ul
          (for [[i {:keys [path code message]}] (map-indexed vector (take 3 (:errors last-sync)))]
            ($ :li {:key i}
               (when (seq path) ($ :span.mono (str (str/join " " (map str path)) " — ")))
               ($ :span.code (str (name code) ": "))
               message)))
       (when (> (count (:errors last-sync)) 3)
         ($ :span.more (str "+ " (- (count (:errors last-sync)) 3) " more"))))))

;; ---------------------------------------------------------------------------
;; Root
;; ---------------------------------------------------------------------------

(defn- as-edn-text
  "A stored query or entity as editor text: the server returns either the EDN
   value or the text it was saved as, and the request editor takes text."
  [v]
  (if (string? v) v (pr-str v)))

(defn query-for
  "The query text for one attribute. A join key is only useful with its children,
   so querying `customer/orders` writes the nested form rather than a bare key —
   which is also the shape a user would have had to write by hand."
  [attr catalog]
  (if-let [join (->> (:resolvers catalog)
                     (keep :join)
                     (filter #(= attr (:key %)))
                     first)]
    (str "[{:" attr "\n  [" (str/join "\n   " (map #(str ":" %) (:children join))) "]}]")
    (str "[:" attr "]")))

(defn seed-entity
  "A starting entity for querying one attribute.

   **The anchor comes from the resolver that produces it, not from whether the
   attribute is a collection.** `:target` says the attribute is a join root
   (D2.29); it says nothing about whether that root can be reached without an
   entity. `film/actors` is a collection *and* anchored — its resolver takes
   `film/film_id` — so seeding `{}` for it produced a query that could never
   run, and the reason was invisible: the entity box simply looked finished.

   An unanchored collection (`:input []`, the curated full set) still seeds
   `{}`, because for that one an entity would be the wrong thing to supply."
  [attr catalog]
  (let [producer (->> (:resolvers catalog)
                      (filter (fn [r]
                                (or (= attr (get-in r [:join :key]))
                                    (some #(= attr %) (:output r)))))
                      (sort-by #(count (:input %)))
                      last)
        inputs (:input producer)]
    (cond
      ;; Anchored: seed the anchor the resolver actually asks for, so the
      ;; placeholder names the key that has to be filled in.
      (seq inputs)
      (str "{" (str/join "\n " (map #(str ":" % " \"?\"") inputs)) "}")

      ;; Produced by something unanchored — a curated collection, or an
      ;; attribute of an entity whose own identity is the way in.
      (:target (first (filter #(= attr (:attribute %)) (:attributes catalog))))
      "{}"

      :else
      (let [name' (entity-of attr)]
        (if-let [id-key (->> (:entities catalog)
                             (filter #(= name' (:entity %)))
                             first :identity-keys first)]
          (str "{:" id-key " \"?\"}")
          "{}")))))

(defui workbench []
  ;; Graphs first (owner ruling 2026-08-26, replacing the earlier query-first
  ;; default): a session starts by picking which graph to work on, and the
  ;; Graphs page is where that catalog lives.
  (let [;; A reload comes back to the page it left (owner, 2026-09-05);
        ;; only a browser with nothing remembered starts on Graphs.
        [page set-page!]         (uix/use-state #(let [remembered (ui-recall-in [:page])]
                                                   (if (= remembered :build) :graphs (or remembered :graphs))))
        ;; Whether this render restored a page rather than defaulting: the
        ;; landing rule below must not move a person off a page they were on.
        [restored-page?]         (uix/use-state #(let [remembered (ui-recall-in [:page])]
                                                   (and (some? remembered) (not= remembered :build))))
        ;; Which face of the Workspace page is showing (8.4.15). Kept across
        ;; a workspace switch on purpose: the tab is what the person is
        ;; doing, the workspace is what they are doing it to.

        ;; Which graph the Graphs page should open expanded when it next
        ;; mounts. Set by a jump from elsewhere (the Tokens page's graph-key
        ;; rows), and only read at that mount — arriving at Graphs by clicking
        ;; Graphs must not re-open whatever was last jumped to.
        [graph-focus set-graph-focus!] (uix/use-state nil)
        ;; The workspace's sources, read once per workspace, for the landing
        ;; rule below (owner, 2026-09-03). nil until the reply lands: «not read
        ;; yet» and «none declared» are different answers and the rule may only
        ;; act on the second. The Build page reads the register again for
        ;; itself — it has to stay fresh across a connect, which this does not.
        [ws-sources set-ws-sources!] (uix/use-state nil)
        ;; The workspace whose first arrival has already been ruled on. First
        ;; arrival only: a person who navigated somewhere must never be moved
        ;; off it by a later refetch.
        [landed set-landed!] (uix/use-state nil)
        ;; Whether the graph list has answered at all. `graphs` starts empty,
        ;; so «no graphs» and «not loaded» would otherwise be one value.
        [graphs-loaded? set-graphs-loaded!] (uix/use-state false)
        [nav-collapsed? set-nav-collapsed!] (uix/use-state false)
        [graphs set-graphs!]     (uix/use-state [])
        [session set-session!]   (uix/use-state nil)
        [studio-users set-studio-users!] (uix/use-state [])
        [studio-users-loaded? set-studio-users-loaded!] (uix/use-state false)
        [studio-users-load-error set-studio-users-load-error!] (uix/use-state nil)
        [workspace set-workspace!] (uix/use-state "")
        [root set-root!]         (uix/use-state "")
        [catalog set-catalog!]   (uix/use-state nil)
        [query set-query!]       (uix/use-state "")
        [kind set-kind!]         (uix/use-state :attributes)
        [selected set-selected!] (uix/use-state nil)
        [index set-index!]       (uix/use-state nil)
        [index-view set-index-view!] (uix/use-state :graph)
        [hovered set-hovered!]   (uix/use-state nil)
        ;; Bumped after every accepted model write.  The catalog and blueprint
        ;; effects depend on it, so an in-place edit refetches both without a
        ;; page reload — the same read path the initial render used.
        [model-epoch set-model-epoch!] (uix/use-state 0)
        ;; The form's field list, vocabularies and defaults come from the
        ;; server's descriptor — fetched once, since the dialect contract does
        ;; not change under a running Studio.
        [schema set-schema!]     (uix/use-state nil)
        ;; Empty, and filled from the SELECTED GRAPH's own default below (owner
        ;; ruling 2026-09-01). This used to be a string literal naming
        ;; `cis.master.*` — namespaces the shipped demo does not have, so a new
        ;; instance's very first Run failed. A default query belongs to the
        ;; graph it queries; a graph with none opens empty rather than opening
        ;; with somebody else's model.
        [query-text set-query-text!]   (uix/use-state "")
        [semantic-draft set-semantic-draft!] (uix/use-state nil)
        [outcome set-outcome!]   (uix/use-state nil)
        [plan set-plan!]         (uix/use-state nil)
        [focus set-focus!]       (uix/use-state nil)
        [plan-view set-plan-view!] (uix/use-state :graph)
        [status set-status!]     (uix/use-state nil)
        ;; The model/server sync gap (ADR-034): a second, independent poll —
        ;; `/api/model/status` answers a different question than
        ;; `/api/config/status` does, and the footer plus the sync banner are
        ;; the GUI's whole verification loop (plan D4).
        [model-status set-model-status!] (uix/use-state nil)
        [syncing? set-syncing!]  (uix/use-state false)
        [busy? set-busy!]        (uix/use-state false)
        [lenient? set-lenient!]  (uix/use-state false)
        ;; Pathom Viz keeps its history in local storage. This one is the
        ;; server's: the operator's own workspace holds it (model.history), so it
        ;; survives a restart, while the browser still stores nothing of a
        ;; request — which is what ADR-012-A section 4 asks for.
        [history set-history!]   (uix/use-state [])
        [show-history? set-show-history!] (uix/use-state false)
        [ui-theme set-theme!]    (uix/use-state (theme/stored))
        ;; Incremented before every workspace or graph switch.  Responses that
        ;; belonged to the old context are ignored when they arrive late.
        context-token            (uix/use-ref 0)

        ;; One editor, one document: `{:entity … :query …}` (a bare vector is
        ;; still read as a query with no entity).
        parsed     (editor/parse-request query-text "")
        ;; Workspace choices are authorization data, not an inference from
        ;; graph rows.  The Platform Server returns only workspaces this
        ;; principal may enter, including the server-restored last workspace.
        workspaces (mapv :workspace/name (:workspaces session))
        workspace-data (first (filter #(= workspace (:workspace/name %))
                                      (:workspaces session)))
        workspace-graphs (->> graphs
                              (filter #(= workspace (:graph/workspace %)))
                              vec)
        ;; The picker sets the scope you model in, and an archived graph is not
        ;; something to model in — it cannot be published. So it is off the list
        ;; — but it stays listed while it IS the current scope, because a select
        ;; whose value is not among its options shows the wrong graph's name.
        picker-graphs (let [live (filterv #(not (table/archived? %)) workspace-graphs)]
                        (if (and (seq root)
                                 (not (workspace-scope? root))
                                 (not-any? #(= root (:graph/qualified-name %)) live))
                          (into live
                                (filter #(= root (:graph/qualified-name %)))
                                workspace-graphs)
                          live))
        selected-graph (some #(when (= root (:graph/qualified-name %)) %) workspace-graphs)
        ;; The graph's own opening query (owner ruling 2026-09-01). Absent is
        ;; ABSENT, not empty: `set-default-query!` retracts rather than writing
        ;; a blank, so `nil` here really does mean "this graph has none".
        graph-default-query (:graph/default-query selected-graph)
        ;; Load the selected graph's default into the editors when the graph --
        ;; or its default -- actually changes.
        ;;
        ;; The dependency is `pr-str` of the value, not the value: `graphs` is
        ;; refetched on a timer and every refetch produces a fresh map, so
        ;; depending on identity would re-fire on each poll and overwrite what
        ;; somebody is in the middle of typing. Compared by value it fires when
        ;; the default arrives, when the graph is switched, and at no other
        ;; time. Same discipline as the key-absorbing effect in the source
        ;; wizard: the mutable half must not be a dependency.
        ;; The value itself rides in a ref so the effect body reads no changing
        ;; binding — the same shape the source wizard's key-absorbing effect
        ;; uses, and what keeps the dependency list honest instead of suppressed.
        default-query-ref (uix/use-ref nil)
        _ (set! (.-current default-query-ref) graph-default-query)
        _ (uix/use-effect
           (fn []
             (when (seq root)
               (let [dq (.-current default-query-ref)]
                 ;; A graph with no default clears the editors rather than
                 ;; leaving the previous graph's query sitting there against a
                 ;; model that has no such attributes.
                 (set-query-text! (editor/request-text (some-> (:query dq) as-edn-text)
                                                       (some-> (:entity dq) as-edn-text)))))
             js/undefined)
           [root (pr-str graph-default-query)])
        graph-history (filterv #(= root (:graph %)) history)
        graph-context (cond-> {:graph root}
                        (:graph/id selected-graph) (assoc :graph-id (:graph/id selected-graph)))
        ;; Joins complete too — they are in this one list since D2.29, and a
        ;; join root the editor cannot offer is one nobody writes a sub-query
        ;; for (demo-ch's user_posts, 2026-08-31). Their detail names the
        ;; target, so the reader knows a sub-query of THAT entity's attributes
        ;; is what comes next.
        attributes (query-tools/completion-attributes semantic-draft catalog)
        ;; The plan and the trace below it describe one request. Whenever the
        ;; answer changes they are replaced together with the step selection,
        ;; which points into the plan being replaced.
        show-plan! (uix/use-callback
                    (fn [p] (set-plan! p) (set-focus! nil)) [])
        ;; The one way in to the Workspace page, so every caller lands on the
        ;; tab it meant rather than on whichever one was last open (8.4.15).
        open-workspace! (uix/use-callback
                         (fn [] (set-page! :workspace)) [])
        reset-graph-context! (uix/use-callback
                              (fn []
                                (set! (.-current context-token)
                                      (inc (.-current context-token)))
                                (set-catalog! nil)
                                (set-index! nil)
                                (set-selected! nil)
                                (set-hovered! nil)
                                (set-outcome! nil)
                                (set-semantic-draft! nil)
                                (show-plan! nil)
                                (set-history! [])
                                (set-show-history! false)
                                (set-busy! false))
                              [show-plan!])
        error!     (uix/use-callback
                    (fn [e]
                      (set-busy! false)
                      ;; A failure has no plan and no trace. Leaving the previous
                      ;; request's there would present them as this one's — the
                      ;; graph would show a run that succeeded next to the error
                      ;; that replaced it.
                      (show-plan! nil)
                      (set-outcome! {:kind :error :value e}))
                    [show-plan!])
        reload-history! (uix/use-callback
                         (fn []
                           (let [token (.-current context-token)]
                             (api/history! {:on-success (fn [value]
                                                          (when (= token (.-current context-token))
                                                            (set-history! (vec (:history value)))))
                                            :on-error (fn [_])})))
                         [])
        reload-graphs! (uix/use-callback
                        (fn []
                          (let [token (.-current context-token)]
                            (api/graphs! {:on-success (fn [value]
                                                        (when (= token (.-current context-token))
                                                          (set-graphs-loaded! true)
                                                          (set-graphs! (vec (:graphs value)))))
                                          :on-error #(js/console.error (pr-str %))})))
                        [])
        reload-context! (uix/use-callback
                         (fn []
                           (api/studio-context!
                            {:on-success (fn [context]
                                           (set-session! context)
                                           (when-let [current (:current-workspace context)]
                                             (set-workspace! current)))
                             :on-error #(js/console.error (pr-str %))}))
                         [])
        reload-studio-users! (uix/use-callback
                              (fn []
                                (set-studio-users-loaded! false)
                                (set-studio-users-load-error! nil)
                                (api/studio-users!
                                 {:on-success #(do
                                                (set-studio-users-loaded! true)
                                                (set-studio-users! (vec (:users %)))
                                                (set-studio-users-load-error! nil))
                                  :on-error (fn [failure]
                                              (set-studio-users-loaded! true)
                                              (set-studio-users-load-error! failure)
                                              (set-studio-users! []))}))
                              [])
        explain!   (uix/use-callback
                    (fn [request]
                      (api/explain! (merge request graph-context)
                                    {:on-success (fn [v] (set-busy! false) (show-plan! v))
                                     :on-error error!}))
                    [error! show-plan! graph-context])
        run!       (uix/use-callback
                    (fn [request]
                      (set-busy! true)
                      ;; The server chooses the saved Contract or legacy draft
                      ;; and supplies the same diagnostics/history for both.
                      (api/execute! (merge request graph-context)
                                    {:on-success (fn [v]
                                                   (set-busy! false)
                                                   (show-plan! (:plan v))
                                                   (set-outcome! {:kind :result :value v})
                                                   (reload-history!))
                                     :on-error (fn [e] (error! e) (reload-history!))}))
                    [error! show-plan! reload-history! graph-context])
        sync!      (uix/use-callback
                    (fn []
                      (set-syncing! true)
                      ;; sync! then re-poll status immediately, rather than
                      ;; waiting for the next 3 s tick — the button exists so a
                      ;; modeller sees the gap close right away.
                      (api/sync! {:on-success (fn [_]
                                                (set-syncing! false)
                                                (api/model-status! {:on-success set-model-status!
                                                                    :on-error (fn [_])}))
                                  :on-error (fn [_] (set-syncing! false))}))
                    [])]

    ;; Set during render, not in an effect: the editors and the canvases read the
    ;; palette while they render, and a child's effect runs before its parent's.
    (theme/set-current! ui-theme)

    ;; The document attribute and the stored preference can wait for the effect.
    (uix/use-effect (fn [] (theme/apply! ui-theme) js/undefined) [ui-theme])

    ;; The history is the server's, so it is fetched rather than remembered: a
    ;; reload — or a restart — must show what was actually run, not an empty
    ;; panel that looks like nothing ever happened.
    (uix/use-effect (fn [] (reload-history!) js/undefined) [reload-history!])

    (uix/use-effect
     (fn []
       (api/schema! {:on-success set-schema! :on-error #(js/console.error (pr-str %))})
       js/undefined)
     [])

    ;; Identity and workspace context are Studio-owned platform state. The
    ;; browser receives the principal attached to its HttpOnly local session.
    (uix/use-effect
     (fn [] (reload-context!) js/undefined)
     [reload-context!])

    ;; A workspace owner needs the directory to add an existing member; a
    ;; system administrator also needs it for the first-level user page.
    ;; Ordinary members receive neither.
    (uix/use-effect
     (fn []
       (if (or (= :owner (:workspace/role workspace-data))
               (= :admin (get-in session [:user :system-role])))
         (reload-studio-users!)
         (do
           (set-studio-users! [])
           (set-studio-users-loaded! true)
           (set-studio-users-load-error! nil)))
       js/undefined)
     [workspace workspace-data session reload-studio-users!])

    ;; Graphs are separately server-filtered by the authenticated principal.
    ;; A user selects a graph, not an ad-hoc namespace filter, so every model
    ;; request is bound to a registered ownership boundary.
    (uix/use-effect
     (fn [] (reload-graphs!) js/undefined)
     [reload-graphs!])

    ;; The register, for the landing rule only.
    (uix/use-effect
     (fn []
       (api/sources! {:on-success #(set-ws-sources! (vec (:sources %)))
                      ;; A refusal is «unknown», not «none»: an empty vector
                      ;; here would let the rule conclude the workspace has no
                      ;; source from a failure to ask.
                      :on-error (fn [_])})
       js/undefined)
     [workspace])

    ;; Where a workspace opens (owner, 2026-09-03). A workspace with sources
    ;; and no graph has exactly one sensible first move — read a database into
    ;; a model — and the Graphs page it used to land on is an empty list with
    ;; no way to learn that. Anything else lands on the default.
    ;;
    ;; Both facts must have ARRIVED before this rules: `graphs` starts empty
    ;; and `ws-sources` starts nil, and ruling on either default would send
    ;; every workspace here for one render. And it rules ONCE per workspace —
    ;; a later refetch must not yank somebody off the page they navigated to.
    (uix/use-effect
     (fn []
       (when (and (seq workspace) graphs-loaded? (some? ws-sources)
                  (not= landed workspace))
         (set-landed! workspace)
         nil)
       js/undefined)
     [workspace graphs-loaded? ws-sources workspace-graphs landed restored-page?])

    (uix/use-effect (fn [] (ui-remember! :page page) js/undefined) [page])

    ;; The server restores the last accessible workspace.  This fallback only
    ;; deals with a registry changing underneath an already-open Studio tab.
    (uix/use-effect
     (fn []
       (when (seq workspaces)
         (when-not (some #{workspace} workspaces)
           (set-workspace! (first workspaces)))
         (cond
           ;; The workspace scope is a deliberate choice, not a stale graph
           ;; name — this fallback exists for a registry changing under an
           ;; open tab, and must not undo it.
           (workspace-scope? root) nil

           graphs-loaded?
           (let [next-root (graph-actions/active-root
                            workspace-graphs root (:workspace/landing-graph workspace-data))]
             (when (not= (or root "") next-root)
               (reset-graph-context!)
               (set-root! next-root)
               (reload-history!)))))
       js/undefined)
     ;; `workspace-data` joins the list because the landing graph is read from
     ;; it (D3.21). Re-firing on its identity is harmless here: every branch
     ;; below is idempotent -- it sets `root` to the same value it already
     ;; holds -- unlike the Lab's default-query effect, which would overwrite
     ;; what somebody is typing and therefore compares by value instead.
     [workspace-graphs workspaces workspace-data graphs graphs-loaded? workspace root
      reset-graph-context! reload-history!])

    ;; The catalog follows both graph ownership and the text filter.
    (uix/use-effect
     (fn []
       (if (seq root)
         (let [token (.-current context-token)]
           (api/index! query root {:on-success (fn [value]
                                                 (when (= token (.-current context-token))
                                                   (set-catalog! value)))
                                  :on-error #(js/console.error (pr-str %))}))
         (set-catalog! nil))
       js/undefined)
     [query root model-epoch])

    ;; The blueprint is the whole model, filtered only by the search box. A
    ;; selection highlights its node rather than narrowing the picture: narrowing
    ;; was a second way to scope the graph, and the filter already does that.
    (uix/use-effect
     (fn []
       (if (seq root)
         (let [token (.-current context-token)]
           (api/index-graph! {:root root :q query}
                             {:on-success (fn [value]
                                            (when (= token (.-current context-token))
                                              (set-index! value)))
                              :on-error #(js/console.error (pr-str %))}))
         (set-index! nil))
       js/undefined)
     [query root model-epoch])

    (uix/use-effect
     (fn []
       (let [tick #(api/status! {:on-success set-status! :on-error (fn [_])})]
         (tick)
         (let [id (js/setInterval tick 3000)]
           #(js/clearInterval id))))
     [])

    (uix/use-effect
     (fn []
       (let [tick #(api/model-status! {:on-success set-model-status! :on-error (fn [_])})]
         (tick)
         (let [id (js/setInterval tick 3000)]
           #(js/clearInterval id))))
     [])

    ($ :div {:class (str "app platform-shell" (when nav-collapsed? " nav-collapsed"))}
       ($ :aside.platform-sidebar
          ($ :div.shell-brand
             (wordmark-icon)
             ;; Name and version top left, as in Workbench (owner 2026-10-10); the
             ;; commit and build time on hover. The footer no longer repeats it.
             (let [build (:build session)]
               ($ :div.brand-copy
                  ($ :strong "Open Atlas Studio")
                  ($ :span.brand-version
                     {:title (if build
                               (str "Studio " (:version build) " · commit " (some-> (:commit build) (subs 0 8))
                                    " · built " (:built-at build))
                               "development checkout")}
                     (or (:version build) "dev"))))
             ($ :button.collapse-button
                {:type "button"
                 :title (if nav-collapsed? "Expand navigation" "Collapse navigation")
                 :aria-label (if nav-collapsed? "Expand navigation" "Collapse navigation")
                 :on-click #(set-nav-collapsed! (not nav-collapsed?))}
                (shell-icon :collapse)))
          ($ :nav.shell-navigation
             ($ :div.shell-nav-section
                ($ :h3 "Model")
                ($ shell-nav-button {:page :graphs :active-page page :label "Graphs"
                                     :icon :graphs :on-select set-page!})
                ($ shell-nav-button {:page :lab :active-page page :label "Query Studio"
                                     :icon :query :on-select set-page!})
                ;; Sources, Team and Tokens live in here as tabs and the Access
                ;; section went with them (8.4.15); the Workspace and Test
                ;; sections were folded into Model too (owner, 2026-09-03) —
                ;; one section for everything that is modelling work. Its own
                ;; icon, not `:explorer`: that one is the Graph Editor's, and
                ;; two entries sharing a glyph read as two views of one thing.
                ;; `open-workspace!` and not `set-page!`: one entry point, so
                ;; every caller lands on a named tab. It sets no graph scope —
                ;; setting one would move the graph the Graph Editor is on.
                ($ shell-nav-button {:page :workspace :active-page page :label "Workspace"
                                     :icon :workspace
                                     :on-select #(open-workspace!)}))
             ($ :div.shell-nav-section
                ($ :h3 "Observability")
                ($ shell-nav-button {:page :activity :active-page page :label "Activity"
                                     :icon :activity :on-select set-page!})
                ($ shell-nav-button {:page :logs :active-page page :label "Logs"
                                     :icon :logs :on-select set-page!})
                ($ shell-nav-button {:page :metrics :active-page page :label "Metrics"
                                     :icon :metrics :on-select set-page!}))
             ;; Changing the password lives on My Profile (the account button
             ;; below), so this section is only the administrator's.
             (when (= :admin (get-in session [:user :system-role]))
               ($ :div.shell-nav-section
                  ($ :h3 "Account")
                  ($ shell-nav-button {:page :user-admin :active-page page
                                       :label "User Management" :icon :team
                                       :on-select set-page!}))))
          ($ :div.shell-account
             ($ :button.account-button
                {:type "button"
                 :class (when (= page :profile) "selected")
                 :on-click #(set-page! :profile)}
                ($ :span.account-avatar
                   (if-let [label (or (get-in session [:user :name])
                                      (get-in session [:user :email]))]
                     (str/upper-case (subs label 0 1))
                     "?"))
                ($ :span.account-copy
                   ($ :strong (or (get-in session [:user :name])
                                  (get-in session [:user :email])
                                  "Loading identity"))
                   ($ :span (str (or (some-> workspace-data :workspace/role name) "member")
                                 " · " workspace)))
                ($ :span.account-chevron "›"))))

       ($ :section.studio-content
          ($ :header.context-bar
             ($ :div.context-heading
                ($ :span.context-eyebrow "Studio")
                ($ :strong (case page
                             :activity "Activity"
                             :logs "Logs"
                             :metrics "Metrics"
                             :graphs "Graphs"
                             :index "Graph editor"
                             :workspace "Workspace"
                             :profile "My Profile"
                             :user-admin "User Management"
                             :change-password "My Profile"
                             "Query Studio"))
                ($ :span.context-graph (if selected-graph (table/display-name selected-graph) (graph-label root workspace))))
             ($ :div.model-context
                ($ :label.graph-picker
                   ($ :span "Workspace")
                   ($ :select {:aria-label "Workspace"
                               :value workspace
                               :on-change (fn [event]
                                            (let [next (.. event -target -value)]
                                              (reset-graph-context!)
                                              (set-workspace! next)
                                              (set-root! "")
                                              (set-graphs! [])
                                              ;; Both facts the landing rule
                                              ;; reads belong to a workspace,
                                              ;; so both go back to «not read
                                              ;; yet» when the workspace does.
                                              (set-graphs-loaded! false)
                                              (set-ws-sources! nil)
                                              (api/select-workspace!
                                               next
                                               {:on-success (fn [context]
                                                              (set-session! context)
                                                              (set-workspace! (:current-workspace context))
                                                              ;; Graph rows are server-scoped to the selected
                                                              ;; workspace. Refresh rather than retaining a
                                                              ;; previously accessible workspace's catalog.
                                                              (let [token (.-current context-token)]
                                                                (api/graphs! {:on-success (fn [value]
                                                                                            (when (= token (.-current context-token))
                                                                                              (set-graphs-loaded! true)
                                                                                              (set-graphs! (vec (:graphs value)))))
                                                                             :on-error #(js/console.error (pr-str %))}))
                                                              (reload-history!))
                                                :on-error (fn [error]
                                                            (js/console.error (pr-str error)))})))}
                      (for [name workspaces]
                        ($ :option {:key name :value name} name))))
                ($ :label.graph-picker
                   ($ :span "Graph")
                   ($ :select {:aria-label "Graph"
                               :value root
                               :on-change #(let [next (.. % -target -value)]
                                             (reset-graph-context!)
                                             (set-root! next)
                                             (reload-history!))}
                      ($ :option {:value "" :disabled true} "Select a graph")
                      ;; The workspace scope used to sit here, as an option
                      ;; among graphs. It is not one: it is every object the
                      ;; workspace owns, recipe or no recipe, and reading it as
                      ;; "a graph called Workspace-demo" is what made deleting
                      ;; from the POOL look like editing a graph. It has its own
                      ;; page now (the Workspace entry in Model).
                      (for [{:graph/keys [qualified-name state name display-name]} picker-graphs]
                        ($ :option {:key qualified-name :value qualified-name}
                           (str (or display-name name (last (str/split qualified-name #"/")))
                                (when (= state :archived) " · archived")))))))
             ($ :a.context-help {:href "/help" :target "_blank" :rel "noopener"
                                 :title "Open Help in a new tab"} "Help ↗")
             ($ :button.theme-toggle
                {:type "button"
                 :title (str "Use " (name (if (= ui-theme :dark) :light :dark)) " theme")
                 :aria-label (str "Use " (name (if (= ui-theme :dark) :light :dark)) " theme")
                 :on-click #(set-theme! (if (= ui-theme :dark) :light :dark))}
                (if (= ui-theme :dark) "☀" "◐")))
          ($ sync-banner {:last-sync (:last-sync model-status)})
          (case page
            :profile
            ($ profile-page {:session session :workspace-data workspace-data
                             :ui-theme ui-theme :on-theme set-theme!})

            :user-admin
            (if (= :admin (get-in session [:user :system-role]))
              ($ studio-user-admin-page {:studio-users studio-users
                                         :studio-users-loaded? studio-users-loaded?
                                         :studio-users-load-error studio-users-load-error
                                         :current-user (:user session)
                                         :on-refresh-users reload-studio-users!})
              ($ profile-page {:session session :workspace-data workspace-data
                               :ui-theme ui-theme :on-theme set-theme!}))

            ;; Retired as a page; a remembered one opens the profile, where
            ;; the form now is.
            :change-password
            ($ profile-page {:session session :workspace-data workspace-data
                             :ui-theme ui-theme :on-theme set-theme!})

            :activity ($ obs-activity-page)
            :logs     ($ obs-logs-page)
            :metrics  ($ obs-metrics-page)

            :graphs
            ($ graphs-page
               {:key workspace :workspace workspace :theme ui-theme :schema schema
                :graphs workspace-graphs
                :loaded? graphs-loaded?
                :focus-graph graph-focus
                :on-focus-graph set-graph-focus!
                :owner? (= :owner (:workspace/role workspace-data))
                :on-open (fn [qualified-name next-page]
                           (reset-graph-context!)
                           (set-root! qualified-name)
                           (reload-history!)
                           (set-page! next-page))
                 :on-create (fn [graph done on-error]
                             (api/create-graph! graph
                                                {:on-success (fn [r] (if (seq (:findings r)) (on-error r) (do (reload-graphs!) (done r))))
                                                 :on-error on-error}))
                ;; Every one of these used to end at `js/console.error`, so a
                ;; refusal — «a graph with cut versions is refused», «owner
                ;; required» — reached a place nobody looks and the button
                ;; simply did nothing. The caller now says where to put it.
                :on-duplicate (fn [req on-error on-success]
                                (api/apply-duplicate-graph!
                                 req {:on-success (fn [result] (on-success result) (reload-graphs!))
                                      :on-error on-error}))
                :on-merge (fn [req done on-error]
                            (api/merge-graphs!
                             req {:on-success (fn [r] (if (seq (:findings r)) (on-error r) (do (reload-graphs!) (done r))))
                                  :on-error on-error}))
                :on-delete (fn [req on-error done]
                             (graph-actions/delete-graph!
                              req {:set-graphs! set-graphs!
                                   :clear-context! #(when (= root (:graph req))
                                                      (reset-graph-context!)
                                                      (set-root! nil))
                                   :on-success done
                                   :refresh! reload-graphs!
                                   :on-error on-error}))
                ;; **No `reset-graph-context!` here.** It bumps `context-token`,
                ;; and `reload-graphs!` discards any reply whose token no longer
                ;; matches — so resetting the context and reloading in the same
                ;; breath raced, and the list simply did not refresh after an
                ;; edit. Editing a recipe is also no reason to switch the app to
                ;; that graph, which is what `set-root!` was doing.
                ;; `on-error` is the caller's, so a refused prefix edit lands
                ;; next to the box that earned it. It used to end at the console
                ;; — and "Remove by prefix" failing silently would take the
                ;; modeller's word for what the recipe now holds.
                :on-update (fn [recipe done on-error]
                             (api/update-graph-recipe!
                              recipe
                              {:on-success (fn [_] (reload-graphs!) (done))
                               :on-error (or on-error #(js/console.error (pr-str %)))}))
                :on-rename (fn [req done on-error]
                             (api/rename-graph!
                              req {:on-success (fn [_] (reload-graphs!) (reload-context!) (done))
                                   :on-error on-error}))
                ;; doc 104 §8.17.7 D. `on-error` is the caller's, so a malformed
                ;; release name lands next to the box that earned it.
                :on-set-package-name (fn [req on-error on-success]
                                       (api/set-package-name!
                                        req {:on-success (fn [_] (on-success) (reload-graphs!))
                                             :on-error on-error}))
                :on-archive (fn [graph on-error on-success]
                              (graph-actions/set-graph-state!
                               {:graph (:graph/qualified-name graph) :graph-id (:graph/id graph)}
                               :archived
                               {:set-graphs! set-graphs! :refresh! reload-graphs!
                                :on-success on-success :on-error on-error}))
                :data-port (get-in session [:data-plane :port])
                :data-scheme (get-in session [:data-plane :scheme])
                ;; Resolved by the server (D3.21), so a pointer at a deleted or
                ;; archived graph arrives as nil rather than as a row that never
                ;; lights up.
                :landing-graph (:workspace/landing-graph workspace-data)
                :on-set-landing (fn [qn on-error on-success]
                                  (api/set-landing-graph!
                                   {:graph qn}
                                   {:on-success (fn [_] (reload-context!) (on-success))
                                    :on-error on-error}))
                ;; Publishing and rolling back both land here. The graph LIST
                ;; has to be re-read, not just the model: `serving` and
                ;; `published` are columns now, and they are stale the moment
                ;; the pointer moves.
                :on-publish (fn [_] (set-model-epoch! inc) (reload-graphs!))
                :on-restore (fn [graph on-error on-success]
                              (graph-actions/set-graph-state!
                               {:graph (:graph/qualified-name graph) :graph-id (:graph/id graph)}
                               :active
                               {:set-graphs! set-graphs! :refresh! reload-graphs!
                                :on-success on-success :on-error on-error}))})

            :workspace
            ;; Shared workspace membership and connections.
            ($ workspace-page
               {:workspace workspace
                :workspace-data workspace-data
                :role (or (:workspace/role workspace-data) :member)
                :can-edit? (contains? #{:owner :editor} (:workspace/role workspace-data))
                :session session
                :studio-users studio-users
                :graph root
                :schema schema
                :model-epoch model-epoch
                :on-model-change (fn [] (set-model-epoch! inc))
                :on-refresh-context reload-context!
                :on-open-graph (fn [qualified-name]
                                 (reset-graph-context!)
                                 (set-root! qualified-name)
                                 (reload-history!)
                                 (set-graph-focus! qualified-name)
                                 (set-page! :graphs))})

            :index
            ($ contract-editor/graph-editor
               {:key (str root "-" model-epoch "-" (:workspace/role workspace-data))
                :graph root :theme ui-theme
                :can-edit? (contains? #{:owner :editor} (:workspace/role workspace-data))
                :on-definition #(do (set-graph-focus! root) (set-page! :graphs))}
            ($ index-page
               {:catalog catalog :query query :on-query set-query!
                :kind kind :on-kind set-kind!
                :selected selected :on-select set-selected!
                :index index
                :graph-name root
                :view index-view :on-view set-index-view!
                :hovered hovered :on-hover set-hovered!
                :theme ui-theme
                ;; Workspace owner/editor may write model assets; the server
                ;; enforces the same rule on every write regardless.
                :can-edit? (contains? #{:owner :editor} (:workspace/role workspace-data))
                :on-model-change (fn [] (set-model-epoch! inc))
                :schema schema
                :on-plan (fn [attr]
                           (let [text (editor/request-text (query-for attr catalog)
                                                           (seed-entity attr catalog))
                                 parsed' (editor/parse-request text "")]
                             (set-query-text! text)
                             (set-outcome! nil)
                             (set-page! :lab)
                             (when (:value parsed') (explain! (:value parsed')))))}))

            ($ lab-page
               {:graph root :query-text query-text :on-query-text set-query-text!
                :on-semantic-draft set-semantic-draft!
                :attributes attributes
                :busy? busy? :lenient? lenient? :on-lenient set-lenient!
                :parse-error (:query-error parsed)
                :entity-error (:entity-error parsed)
                :history graph-history :show-history? show-history?
                :on-show-history set-show-history!
                :recent-graph (when-not (workspace-scope? root) root)
                ;; A recorded request is the EQL as it was sent: an EQL vector,
                ;; or the {:entity :query} map. The editor wants the map.
                :on-pick-recent (fn [eql]
                                  (let [t (str/trim (str eql))]
                                    (set-query-text! (editor/format-request
                                                      (if (str/starts-with? t "{")
                                                        t
                                                        (str "{:entity {} :query " t "}"))))))
                :on-recent-error error!
                :on-pick-history (fn [{:keys [query entity]}]
                                   (set-query-text! (editor/request-text (some-> query as-edn-text)
                                                                         (some-> entity as-edn-text))))
                ;; A row that could not be forgotten stays in the list, so the
                ;; failure has to be said out loud — otherwise the list simply
                ;; looks like it ignored the click. `error!` is the panel this
                ;; page already reports failures in.
                :on-delete-history (fn [entry]
                                     (api/forget-history! (:id entry)
                                                          {:on-success (fn [_] (reload-history!))
                                                           :on-error error!}))
                :outcome outcome :plan plan :focus focus :on-focus set-focus!
                :view plan-view :on-view set-plan-view!
                :theme ui-theme
                ;; Explain is a Builder-side plan; a consumer run has no plan
                ;; to show, so the button stays wired to the authoring path.
                :on-explain (when-not semantic-draft
                              (fn []
                                (when-let [request (:value parsed)]
                                  (set-busy! true)
                                  (set-outcome! nil)
                                  (explain! request))))
                :scope-note (when (workspace-scope? root)
                              "Running needs a graph: a query is answered by one recipe's model, and the workspace scope is not one. Pick a graph above.")
                :run-target-note (when semantic-draft
                                   (str "Target: Draft rev " (:revision semantic-draft)))
                :on-run (fn []
                          (when-let [request (:value parsed)]
                            (run! (assoc request :lenient? lenient?))))
                ;; Only where the recipe itself could be edited: choosing what
                ;; a graph opens with is modelling, and the workspace scope is
                ;; not a graph to set anything on.
                :can-set-default? (boolean (and selected-graph
                                                (:graph/can-edit? selected-graph)
                                                (not (workspace-scope? root))))
                ;; An empty entity is ABSENT, not `:entity nil` — otherwise the
                ;; button reads "set as default" forever against a stored value
                ;; that differs only by a key nobody typed.
                :default-saved? (= (normalize-default (:value parsed))
                                   (normalize-default graph-default-query))
                :on-set-default
                (fn []
                  (when-let [request (:value parsed)]
                    (api/set-default-query!
                     {:graph root :default-query (normalize-default request)}
                     {:on-success (fn [_] (reload-graphs!))
                      :on-error (fn [e] (set-status! {:error e}))})))}))
          ($ status-bar {:status status :model-status model-status :syncing? syncing? :on-sync sync!})))))

(defui auth-gate []
  (let [[state set-state!] (uix/use-state nil)
        [email set-email!] (uix/use-state "")
        [name set-name!] (uix/use-state "")
        [password set-password!] (uix/use-state "")
        [error set-error!] (uix/use-state nil)
        [busy? set-busy!] (uix/use-state false)
        setup? (:needs-setup? state)
        refresh! (uix/use-callback
                  (fn [] (api/auth-state! {:on-success set-state!
                                           :on-error (fn [e] (set-error! e))}))
                  [])]
    (uix/use-effect (fn [] (refresh!) js/undefined) [refresh!])
    (cond
      (nil? state)
      ($ :main.auth-shell
         ($ :div.auth-loading "Opening Studio…"))

      (:authenticated? state)
      ($ workbench)

      :else
      ($ :main.auth-shell
         ($ :section.auth-card
            ($ :div.auth-brand
               (wordmark-icon)
               ($ :div
                  ($ :strong "Open Atlas Studio")))
            ($ :div.auth-heading
               ($ :span.context-eyebrow (if setup? "First run" "Local account"))
               ($ :h1 (if setup? "Create the founding administrator" "Welcome back"))
               ($ :p (if setup?
                       "This one-time step creates the local admin and grants owner access to the demo workspace. Registration closes immediately afterwards."
                       (if (:oidc state)
                         "Sign in with your Studio account, or continue with your company sign-in below."
                         "Sign in with your Studio account."))))
            ($ :form.auth-form
               {:on-submit
                (fn [event]
                  (.preventDefault event)
                  (set-busy! true)
                  (set-error! nil)
                  (let [done (fn [_] (set-busy! false) (refresh!))
                        failed (fn [e] (set-busy! false) (set-error! e))]
                    (if setup?
                      (api/register! {:email email :name name :password password :workspace "demo"}
                                     {:on-success done :on-error failed})
                      (api/login! {:email email :password password}
                                  {:on-success done :on-error failed}))))}
               (when setup?
                 ($ :label
                    ($ :span "Display name")
                    ($ :input {:value name :required true :auto-complete "name"
                               :on-change #(set-name! (.. % -target -value))})))
               ($ :label
                  ($ :span "Email")
                  ($ :input {:type "email" :value email :required true
                             :auto-complete "username"
                             :on-change #(set-email! (.. % -target -value))}))
               ($ :label
                  ($ :span "Password")
                  ($ :input {:type "password" :value password :required true
                             :min-length (if setup? 12 1)
                             :auto-complete (if setup? "new-password" "current-password")
                             :on-change #(set-password! (.. % -target -value))}))
               (when error
                 ($ :div.auth-error (or (:message error) "Authentication failed.")))
               ($ :button.btn.primary.auth-submit
                  {:type "submit" :disabled busy?}
                  (cond busy? "Please wait…" setup? "Create admin" :else "Sign in")))
            ;; Enterprise SSO (doc 8.16 §8.16.5). A plain link, not a fetch:
            ;; the begin route answers 302 to the identity provider, and the
            ;; browser must follow it as a navigation for the callback to come
            ;; back with a cookie this document can use.
            (when-let [provider (:oidc state)]
              (when-not setup?
                ($ :div.auth-sso
                   ($ :div.auth-divider ($ :span "or"))
                   ($ :a.btn.auth-sso-link
                      {:href (str "/api/auth/oidc?return_to="
                                  (js/encodeURIComponent "/"))}
                      (str "Continue with " (or (:name provider) "Single Sign-On"))))))
            ($ :p.auth-footnote
               (if setup?
                 "For headless deployment, use dev/bootstrap-admin.sh or bin/bootstrap-admin.sh instead."
                 "Accounts are local to this Studio instance.")))))))

(defonce root (atom nil))

(defn init []
  (when-not @root
    (reset! root (uix.dom/create-root (js/document.getElementById "app"))))
  (uix.dom/render-root ($ auth-gate) @root))
