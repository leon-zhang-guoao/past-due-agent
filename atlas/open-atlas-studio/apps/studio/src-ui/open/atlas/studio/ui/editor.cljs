(ns open.atlas.studio.ui.editor
  "The EDN editors: CodeMirror 6, Clojure highlighting, and completion over the
   model's own attributes.

   Completion is the difference between usable and not at this scale — nobody
   types `:customer/region-code` correctly from memory out of three thousand
   attributes. The catalog the Explorer already loaded is the completion source.

   An unreadable document is the normal state while typing, so it is reported
   under the editor and the actions are disabled — never thrown."
  (:require ["@codemirror/autocomplete" :refer [autocompletion closeBrackets closeBracketsKeymap
                                                completionKeymap]]
            ["@codemirror/commands" :refer [defaultKeymap history historyKeymap indentWithTab]]
            ["@codemirror/language" :refer [bracketMatching HighlightStyle StreamLanguage
                                            syntaxHighlighting]]
            ["@codemirror/legacy-modes/mode/clojure" :refer [clojure]]
            ["@codemirror/state" :refer [Compartment EditorState]]
            ["@codemirror/view" :refer [EditorView keymap lineNumbers]]
            ["@lezer/highlight" :refer [tags]]
            [cljs.pprint :as pprint]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [open.atlas.studio.ui.theme :as theme]
            [uix.core :as uix :refer [defui $]]))

;; ---------------------------------------------------------------------------
;; Reading what the user wrote
;; ---------------------------------------------------------------------------

(defn parse-edn
  "Read one EDN form. Returns {:value ...} or {:error \"...\"}."
  [text]
  (if (str/blank? text)
    {:value nil}
    (try
      {:value (reader/read-string text)}
      (catch :default e
        {:error (str "Unreadable EDN: " (.-message e))}))))

(defn- parse-request-map
  "The EQL box held a map: a whole `execute_eql` request, `{:entity {...} :query [...]}`
   — the shape the data plane and the MCP verb take, so a request copied from a
   ticket, a doc or an agent's trace runs here as it stands.

   Only `:query` (required, a vector) and `:entity` (optional, a map) belong in
   it. Anything else is refused by name rather than dropped: a `:params` that
   silently did nothing would make the answer look like it had been asked for.
   When the map has no `:entity`, the Entity Data box is used as before; when it
   does, the box is ignored and the pane says so."
  [m entity-text]
  (let [extra (seq (remove #{:query :entity} (keys m)))]
    (cond
      extra
      {:error (str "A request map takes only :query and :entity; unsupported: "
                   (str/join ", " (map pr-str extra)))
       :query-error "Request map: only :query and :entity"}

      (not (vector? (:query m)))
      {:error "In a request map, :query must be an EQL vector, e.g. {:query [:customer/name]}"
       :query-error "Request :query must be an EQL vector"}

      (and (contains? m :entity) (some? (:entity m)) (not (map? (:entity m))))
      {:error "In a request map, :entity must be a map, e.g. {:entity {:customer/id \"C-1\"}}"
       :query-error "Request :entity must be a map"}

      (contains? m :entity)
      {:value {:query (:query m) :entity (:entity m)} :combined :entity}

      :else
      (let [e (parse-edn entity-text)]
        (cond
          (:error e)
          {:error (:error e) :entity-error (:error e)}

          (and (:value e) (not (map? (:value e))))
          {:error "Entity must be a map, e.g. {:customer/id \"C-1\"}"
           :entity-error "Entity data must be a map"}

          :else {:value {:query (:query m) :entity (:value e)} :combined :query})))))

(defn parse-request
  "Compose what was typed into one Pathom request.

   Two ways in. The EQL box holds a query VECTOR and the Entity box holds what is
   already known — the way a request is thought about, and neither editor can
   break the other's syntax. Or the EQL box holds a whole request MAP
   (`{:entity … :query …}`, see `parse-request-map`) and the Entity box is not
   needed. `:combined` says which keys the map carried (`:query` or `:entity`),
   absent for the two-box form."
  [query-text entity-text]
  (let [q (parse-edn query-text)
        e (parse-edn entity-text)]
    ;; Each document reports its own error, so the message sits on the document
    ;; that has the problem — Pathom Viz marks the offending title bar, and a
    ;; single combined message cannot say which of the two is at fault.
    (cond
      (:error q)
      {:error (:error q) :query-error (:error q)}

      (map? (:value q))
      (parse-request-map (:value q) entity-text)

      (not (vector? (:value q)))
      {:error "Query must be an EQL vector, e.g. [:customer/name] — or a request map {:query [...] :entity {...}}"
       :query-error "Query must be an EQL vector or a request map"}

      (:error e)
      {:error (:error e) :entity-error (:error e)}

      (and (:value e) (not (map? (:value e))))
      {:error "Entity must be a map, e.g. {:customer/id \"C-1\"}"
       :entity-error "Entity data must be a map"}

      :else {:value {:query (:value q) :entity (:value e)}})))

(defn format-edn
  "Pretty-print a readable document; leave an unreadable one alone."
  ([text] (format-edn text 72))
  ([text right-margin]
   (let [{:keys [value error]} (parse-edn text)]
     (if error
       text
       (binding [pprint/*print-right-margin* right-margin]
         (str/trim-newline (with-out-str (pprint/pprint value))))))))

(defn request-text
  "One request document, `{:entity … :query …}`, from its two halves as text.

   Query Studio has ONE editor and it holds the shape `execute_eql` takes, so
   whatever fills it — a graph's default query, a history entry, a Contract
   example, «query this attribute» — arrives as that shape. The entity comes
   first because it is read first: what is known, then what is asked. Each half
   is formatted on its own and indented under its key; an unreadable half is
   kept verbatim (and reported by the parser) rather than dropped."
  [query-text entity-text]
  (let [half (fn [text blank]
               (let [t (str/trim (or text ""))]
                 (-> (if (seq t) (format-edn t 60) blank)
                     (str/replace "\n" "\n         "))))]
    (str "{:entity " (half entity-text "{}") "\n"
         " :query  " (half query-text "[]") "}")))

(defn format-request
  "«format» for the request editor: a well-formed request map is laid out as
   `request-text` lays it out (keys aligned, entity first); anything else —
   a bare vector, a map with other keys, unreadable text — goes through the
   plain formatter, so nothing is reshaped behind the parser's back."
  [text]
  (let [{:keys [value error]} (parse-edn text)]
    (if (and (not error) (map? value) (contains? value :query)
             (every? #{:query :entity} (keys value)))
      (request-text (pr-str (:query value)) (if (contains? value :entity) (pr-str (:entity value)) ""))
      (format-edn text))))

;; ---------------------------------------------------------------------------
;; Look
;; ---------------------------------------------------------------------------

(defn- highlighting
  "Per-theme syntax colours. The editor is the largest coloured surface on either
   ground, so it gets a scheme built for that ground rather than one re-used."
  []
  (syntaxHighlighting
   (.define HighlightStyle
            (clj->js [{:tag (.-keyword tags)      :color (theme/syntax :keyword)}
                      {:tag (.-atom tags)         :color (theme/syntax :atom)}
                      {:tag (.-string tags)       :color (theme/syntax :string)}
                      {:tag (.-number tags)       :color (theme/syntax :number)}
                      {:tag (.-comment tags)      :color (theme/syntax :comment) :fontStyle "italic"}
                      {:tag (.-bracket tags)      :color (theme/syntax :bracket)}
                      {:tag (.-variableName tags) :color (theme/syntax :name)}]))))

(defonce ^:private highlight-slot (Compartment.))
(defonce ^:private theme-slot (Compartment.))
(defonce ^:private wrap-slot (Compartment.))

(defn- editor-theme
  "The look, and the box's own geometry.

   `height` / `max-height` are per-instance because the two surfaces differ:
   a query pane owns a flex slot and fills it, while the asset editor is the
   entry itself (owner ruling 2026-09-05, EDN-only) and must grow with the
   document up to a cap, then scroll — the height the textarea got from `rows`."
  ([] (editor-theme nil))
  ([{:keys [height max-height]}]
   (.theme EditorView
          (clj->js {"&"              (cond-> {:height (or height "100%") :fontSize "12.5px"}
                                       max-height (assoc :maxHeight max-height))
                    ".cm-content"    {:padding "10px 0" :caretColor (theme/ink :accent)}
                    ".cm-line"       {:padding "0 12px"}
                    ".cm-scroller"   {:overflow "auto" :lineHeight "1.6"}
                    ".cm-gutters"    {:paddingRight "6px"}})
          #js {:dark (= :dark @theme/current)})))

;; ---------------------------------------------------------------------------
;; Completion
;; ---------------------------------------------------------------------------

(defn- completion-source
  "Complete `:namespace/name` from the model's own attributes."
  [completions]
  (fn [^js ctx]
    (let [before (.matchBefore ctx #":[a-zA-Z0-9_.*+!?<>=/-]*")]
      (when (and before (or (not= (.-from before) (.-to before)) (.-explicit ctx)))
        #js {:from    (.-from before)
             :options (clj->js (map (fn [{:keys [attribute detail]}]
                                      {:label (str ":" attribute)
                                       :type  "property"
                                       :detail detail})
                                    @completions))
             :validFor #"^:[a-zA-Z0-9_.*+!?<>=/-]*$"}))))

(defn- extensions
  "Built once, from refs holding the current handlers and completions. They are
   inline values in the parent and change identity on every render; closing over
   them directly would rebuild the editor on every keystroke, dropping the cursor
   and the undo history."
  [handlers completions {:keys [line-numbers? aria-label read-only? line-wrap?] :as opts}]
  (clj->js
   (cond-> [(bracketMatching)
            (.define StreamLanguage clojure)
            (.of highlight-slot (highlighting))
            (.of theme-slot (editor-theme opts))
            (.of wrap-slot (if line-wrap? (.-lineWrapping EditorView) #js []))]
     ;; A read-only box is the SAME document, shown: same highlighting, same
     ;; gutter, same theme, so reading and editing an entry look alike (owner
     ;; ruling 2026-09-05, EDN-only — the entry is the document either way).
     ;; `EditorState.readOnly` refuses transactions and `EditorView.editable
     ;; false` takes the surface off the caret and out of the tab order, while
     ;; selection and copy keep working. Nothing that only serves typing is
     ;; built: no completion, no bracket closing, no history, and no change
     ;; listener — there is no change to report.
     read-only?
     (into [(.of (.-readOnly EditorState) true)
            (.of (.-editable EditorView) false)])

     (not read-only?)
     (into [(history)
            (closeBrackets)
            (autocompletion #js {:override #js [(completion-source completions)]
                                 :activateOnTyping true
                                 :icons false})
            ;; `indentWithTab` goes last so it cannot shadow the completion
            ;; keymap's Tab (accept the option). It takes Tab away from focus
            ;; traversal, so the documented escape is Escape then Tab.
            (.of keymap (clj->js (concat [{:key "Mod-Enter"       :run (fn [_] ((:on-run @handlers)) true)}
                                          {:key "Mod-Shift-Enter" :run (fn [_] ((:on-explain @handlers)) true)}]
                                         closeBracketsKeymap completionKeymap
                                         defaultKeymap historyKeymap
                                         [indentWithTab])))
            (.. EditorView -updateListener
                (of (fn [^js update]
                      (when (.-docChanged update)
                        ((:on-change @handlers) (.. update -state -doc toString))))))])
     ;; The asset editor IS the entry (owner ruling 2026-09-05, EDN-only), and a
     ;; dialect refusal names a line — without a gutter the reader counts by hand.
     line-numbers? (conj (lineNumbers))
     ;; The textarea carried an `aria-label`; CodeMirror's editable surface is
     ;; the `.cm-content` div, so the label has to be put on it.
     aria-label (conj (.. EditorView -contentAttributes
                          (of #js {:aria-label aria-label}))))))

(defui edn-editor
  "A controlled editor: the text lives in the parent's state, and external edits
   are pushed in without disturbing the cursor when the text already matches.

   `line-numbers?`, `aria-label`, `height` / `max-height`, `class` and `refresh`
   are the asset editor's needs — a pool object is authored as EDN and nothing
   else (owner ruling 2026-09-05), so that box is a document and wants a
   document's affordances. The query panes pass none of them and are unchanged.

   `read-only?` shows an entry without offering to change it — same
   highlighting, gutter and theme, no completion, no `on-change`. Like the
   other structural props it is mount-time configuration."
  [{:keys [text on-change on-run on-explain attributes theme
           line-numbers? aria-label height max-height refresh class read-only? line-wrap?]}]
  (let [host        (uix/use-ref nil)
        view        (uix/use-ref nil)
        initial     (uix/use-ref text)
        handlers    (uix/use-ref nil)
        completions (uix/use-ref nil)
        ;; Mount-time configuration, held like `initial`: line numbers and the
        ;; label are structural, and rebuilding the editor to change one would
        ;; drop the cursor and the undo history.
        built-with  (uix/use-ref {:line-numbers? line-numbers?
                                  :aria-label aria-label
                                  :height height
                                  :max-height max-height
                                  :line-wrap? line-wrap?
                                  :read-only? read-only?})]
    (reset! handlers {:on-change (or on-change (fn [_]))
                      :on-run (or on-run (fn []))
                      :on-explain (or on-explain (fn []))})
    (reset! completions attributes)

    (uix/use-effect
     (fn []
       (let [v (EditorView. (clj->js {:state  (.create EditorState
                                                       (clj->js {:doc (or @initial "")
                                                                 :extensions (extensions handlers completions
                                                                                         @built-with)}))
                                      :parent @host}))]
         (reset! view v)
         #(do (.destroy v) (reset! view nil))))
     ;; Created once. Everything that changes flows through the refs above.
     [])

    (uix/use-effect
     (fn []
       (when-let [v @view]
         (let [current (.. v -state -doc toString)]
           (when (not= current (or text ""))
             (.dispatch v (clj->js {:changes {:from 0 :to (count current) :insert (or text "")}}))))))
     [text])

    ;; A palette change swaps two compartments rather than rebuilding the editor:
    ;; the cursor, the selection and the undo history all survive it. The same
    ;; swap carries a geometry change (the asset editor maximizing), and the
    ;; measure after it is what makes CodeMirror re-lay-out inside the overlay
    ;; instead of keeping the height it was given in the page.
    (uix/use-effect
     (fn []
       (when-let [^js v @view]
         (.dispatch v (clj->js {:effects [(.reconfigure highlight-slot (highlighting))
                                          (.reconfigure theme-slot (editor-theme {:height height
                                                                                  :max-height max-height}))
                                          (.reconfigure wrap-slot
                                                        (if line-wrap?
                                                          (.-lineWrapping EditorView)
                                                          #js []))]}))
         (.requestMeasure v))
       js/undefined)
     [theme height max-height refresh line-wrap?])

    ($ :div {:class (str "editor"
                         (when read-only? " editor-read-only")
                         (when class (str " " class)))
             :ref host})))


(defui edn-view
  "Read-only EDN uses the same syntax, gutter and selection as editing."
  [{:keys [text value aria-label theme max-height]}]
  ($ edn-editor {:text (if (some? text) text (with-out-str (pprint/pprint value)))
                 :read-only? true :line-numbers? true
                 :aria-label (or aria-label "EDN document")
                 :theme theme :max-height (or max-height "480px")}))
