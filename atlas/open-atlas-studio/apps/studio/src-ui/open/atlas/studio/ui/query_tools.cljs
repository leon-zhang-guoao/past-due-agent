(ns open.atlas.studio.ui.query-tools
  (:require [clojure.string :as str]
            [open.atlas.studio.ui.api :as api]
            [open.atlas.studio.ui.editor :as editor]
            [uix.core :as uix :refer [defui $]]))

(defn contract-capabilities [document]
  (or (get-in document [:publication :required-capabilities]) #{}))

(defn completion-attributes
  "Use the same saved Contract as execution. Legacy catalogs are only a
   fallback for graphs without a Contract; never merge another model's fields."
  [draft catalog]
  (if draft
    (->> (get-in draft [:document :vocabulary :attributes])
         (sort-by (comp str key))
         (mapv (fn [[attribute definition]]
                 {:attribute (subs (str attribute) 1)
                  :detail (or (:attribute/description definition)
                              (:attribute/label definition)
                              (some-> (:attribute/type definition) name))})))
    (mapv (fn [a]
            {:attribute (:attribute a)
             :detail (if-let [target (:target a)]
                       (str "→ " target " · join")
                       (str (count (:producers a)) " producers"))})
          (:attributes catalog))))

(defn supports-lifecycle? [document]
  (contains? (contract-capabilities document) :connector/nifi))

(defn query-examples [document]
  (vec (get-in document [:graph :graph/query-examples])))

(defn example-editor-text
  "Format both editor documents before loading a Contract example. Invalid
   example EDN is retained verbatim and reported so a broken example can be
   repaired instead of disappearing or being silently changed."
  [example]
  (let [query-text (str (or (:example/eql example) ""))
        entity-text (pr-str (or (:example/entity example) {}))
        query-read (editor/parse-edn query-text)
        entity-read (editor/parse-edn entity-text)
        errors (remove nil? [(:error query-read) (:error entity-read)])]
    (let [query-text (if (:error query-read) query-text (editor/format-edn query-text 40))
          entity-text (if (:error entity-read) entity-text (editor/format-edn entity-text 40))]
      {:query-text query-text
       :entity-text entity-text
       ;; What Query Studio's one editor receives: the example as a request.
       :request-text (editor/request-text query-text entity-text)
       :error (when (seq errors) (str/join " · " errors))})))

(defn query-action-disabled?
  "Only an execution blocker disables a Query Studio action. Informational
   text such as the selected Contract revision is deliberately absent from
   this decision."
  [{:keys [busy? handler scope-block parse-error entity-error]}]
  (boolean (or busy? (nil? handler) scope-block parse-error entity-error)))

(defn semantic-query-args
  "Build the closed `semantic_draft` query request. UI-only and legacy keys
   must not cross this boundary, even when their value is false or nil."
  [graph revision request]
  {:graph graph
   :action :query
   :expected_revision revision
   :query (:query request)
   :entity (or (:entity request) {})
   :lenient? (boolean (:lenient? request))})

(defui contract-query-tools [{:keys [graph on-draft on-request-text]}]
  (let [[state set-state!] (uix/use-state {:loading? true})
        [example-error set-example-error!] (uix/use-state nil)]
    (uix/use-effect
     (fn []
       (if (seq graph)
         (api/intent! "semantic_draft" {:graph graph :action :read}
                      {:on-success (fn [response]
                                     (set-state! {:loading? false :response response})
                                     (when on-draft
                                       (on-draft (when (pos? (or (:revision response) 0))
                                                   (select-keys response [:revision :document])))))
                       :on-error (fn [error]
                                   (set-state! {:loading? false :error error})
                                   (when on-draft (on-draft nil)))})
         (do (set-state! {:loading? false})
             (when on-draft (on-draft nil))))
       js/undefined)
     [graph on-draft])
    (let [response (:response state)
          document (:document response)
          revision (:revision response)
          examples (when document (query-examples document))
          apply-example! (fn [id]
                           (when-let [example (some #(when (= (str (:example/id %)) id) %) examples)]
                             (let [{:keys [request-text error]} (example-editor-text example)]
                               (set-example-error! error)
                               (on-request-text request-text))))]
      ;; One inline group in the toolbar, not a strip of its own: the contract
      ;; and draft it runs against, and the examples it ships. What used to be
      ;; two paragraphs under a header (the capability list, and «Run uses this
      ;; saved draft…») is the tooltip — read when you want it, not every time.
      ($ :div.contract-inline
         (cond
           (:loading? state) ($ :span.ci-state "Contract…")
           (:error state) ($ :span.ci-state.bad {:title "Contract discovery is unavailable; serving EQL remains available."}
                             "Contract unavailable")
           (nil? document) ($ :span.ci-state {:title "This Graph has no saved Semantic Contract draft. Serving EQL remains available."}
                              "No saved draft")
           :else
           ($ :<>
              ($ :span.ci-label
                 {:title (str "Run uses this saved Contract draft (revision " revision "). "
                              "EQL parameters belong in the query; Entity Data contains only known entity identity/data.\n"
                              "Capabilities: " (str/join " " (map str (sort (contract-capabilities document)))))}
                 (str "Contract " (get-in document [:contract :contract/version]) " · draft " revision))
              (when (seq examples)
                ($ :select.ci-examples {:aria-label "Query examples" :value ""
                                        :title "Fill the editors with an example from this Contract"
                                        :on-change #(apply-example! (.. % -target -value))}
                   ($ :option {:value ""} "Examples…")
                   (for [example examples]
                     ($ :option {:key (str (:example/id example)) :value (str (:example/id example))
                                 :title (:example/description example)}
                        (:example/title example)))))
              (when example-error
                ($ :span.ci-state.bad {:role "alert"} example-error))))))))
