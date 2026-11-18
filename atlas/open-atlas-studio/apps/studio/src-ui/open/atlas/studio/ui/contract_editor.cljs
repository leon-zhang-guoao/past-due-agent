(ns open.atlas.studio.ui.contract-editor
  (:require [cljs.pprint :as pprint]
            [clojure.string :as str]
            [open.atlas.studio.ui.api :as api]
            [open.atlas.studio.ui.editor :as editor]
            [open.atlas.studio.ui.attribute-graph :as attribute-graph]
            [open.atlas.studio.ui.graph-scope :as graph-scope]
            [open.atlas.studio.ui.contract-blueprint :as blueprint]
            [uix.core :as uix :refer [defui $]]))

(defui mutation-form [{:keys [graph revision operation document theme]}]
  (let [input (get-in operation [:definition :input])
        [text set-text!] (uix/use-state
                          (with-out-str
                            (pprint/pprint
                              (into {} (for [a input]
                                         [a (cond
                                              (= a :trdc.command/request_id) (str (random-uuid))
                                              (= a :trdc.command/object_id) (str (random-uuid))
                                              (= a :trdc.command/payload) "{}"
                                              (= :integer (get-in document [:vocabulary :attributes a :attribute/type])) 0
                                              :else "")])))))
        [busy? set-busy!] (uix/use-state false)
        [outcome set-outcome!] (uix/use-state nil)
        parsed (editor/parse-edn text)
        valid? (and (nil? (:error parsed)) (map? (:value parsed)))
        run! (fn []
               (set-busy! true)
               (set-outcome! nil)
               (api/intent! "semantic_draft"
                            {:graph graph :action :execute :expected_revision revision
                             :mutation (:id operation) :params (:value parsed)}
                            {:on-success #(do (set-busy! false) (set-outcome! %))
                             :on-error #(do (set-busy! false)
                                            (set-outcome! {:operation/status :unknown
                                                           :message "Check the receipt before retrying with the same request ID."
                                                           :error %}))}))]
    ($ :section
       ($ :h4 "Execute mutation")
       ($ :p.hint (str "Writes to " (get-in operation [:binding :binding/source])
                       " using saved draft " revision "."))
       ($ :label {:html-for "mutation-parameters"} "Mutation parameters (EDN)")
       ($ editor/edn-editor {:text text :aria-label "Mutation parameters (EDN)"
                             :line-numbers? true :theme theme :height "240px"
                             :on-change #(do (set-text! %) (set-outcome! nil))})
       (when-not valid? ($ :p.hint.bad "Enter an EDN map of the declared input attributes."))
       ($ :button.btn {:type "button" :disabled (or busy? (not valid?)) :on-click run!}
          (if busy? "Writing…" "Execute mutation"))
       (when outcome
         ($ :<>
            ($ :h4 (if (= :ok (:engine/status outcome)) "Write completed" "No confirmed write result"))
            ($ editor/edn-view {:value outcome :theme theme :aria-label "Mutation result (EDN)"}))))))

(defn replace-selected
  "Patch one selected asset back into the authoritative Contract. Stable map
   keys come from the projection; user-authored EDN can change the definition,
   never silently rename or replace another asset."
  [document kind selected definition binding]
  (let [asset-id (or (:contract-id selected) (:id selected))
        with-definition
        (case kind
          :entities (assoc-in document [:vocabulary :entities asset-id] definition)
          :attributes (assoc-in document [:vocabulary :attributes asset-id] definition)
          :resolvers (assoc-in document [:execution :resolvers asset-id] definition)
          :mutations (assoc-in document [:execution :operations asset-id] definition)
          :terms (assoc-in document [:vocabulary :terms asset-id] definition)
          :aliases (assoc-in document [:vocabulary :aliases (:contract-index selected)] definition)
          :equivalences (assoc-in document [:vocabulary :equivalences (:contract-index selected)] definition)
          document)]
    (if-let [binding-id (:binding-id selected)]
      (assoc-in with-definition [:execution :bindings binding-id] binding)
      with-definition)))

(defui selected-definition-editor
  [{:keys [graph document revision kind selected theme on-saved on-dirty-change]}]
  (let [definition-seed (with-out-str (pprint/pprint (:definition selected)))
        binding-seed (when (:binding-id selected)
                       (with-out-str (pprint/pprint (:binding selected))))
        [definition-text set-definition-text!] (uix/use-state definition-seed)
        [binding-text set-binding-text!] (uix/use-state binding-seed)
        [saved-definition set-saved-definition!] (uix/use-state definition-seed)
        [saved-binding set-saved-binding!] (uix/use-state binding-seed)
        [busy? set-busy!] (uix/use-state false)
        [result set-result!] (uix/use-state nil)
        definition-read (editor/parse-edn definition-text)
        binding-read (when (:binding-id selected) (editor/parse-edn binding-text))
        valid? (and (map? (:value definition-read))
                    (or (nil? (:binding-id selected)) (map? (:value binding-read))))
        dirty? (or (not= definition-text saved-definition)
                   (not= binding-text saved-binding))
        proposed (when valid?
                   (replace-selected document kind selected
                                     (:value definition-read) (:value binding-read)))
        request! (fn [action]
                   (set-busy! true)
                   (set-result! nil)
                   (api/intent!
                    "semantic_draft"
                    {:graph graph :action action :expected_revision revision
                     :text (with-out-str (pprint/pprint proposed))}
                    {:on-success
                     (fn [response]
                       (set-busy! false)
                       (set-result! response)
                       (when (and (= action :save) (empty? (:findings response)))
                         (set-saved-definition! definition-text)
                         (set-saved-binding! binding-text)
                         (on-saved response)))
                     :on-error #(do (set-busy! false) (set-result! %))}))]
    (uix/use-effect
     (fn [] (on-dirty-change dirty?) js/undefined)
     [dirty? on-dirty-change])
    ($ :div.contract-object-editor
       ($ :div.contract-object-editor-actions
          ($ :span.mono (str (:id selected)))
          ($ :span.spacer)
          ($ :button.btn {:type "button" :disabled (or busy? (not valid?) (not dirty?))
                          :on-click #(request! :save)}
             (if busy? "Saving…" "Save"))
          ($ :button.btn {:type "button" :disabled (or busy? (not dirty?))
                          :on-click #(do (set-definition-text! saved-definition)
                                         (set-binding-text! saved-binding)
                                         (set-result! nil))}
             "Cancel"))
       (when-not valid?
         ($ :p.hint.bad "Definition and binding must each be an EDN map."))
       ($ :label {:html-for "selected-definition"} "Definition EDN")
       ($ editor/edn-editor {:text definition-text :on-change set-definition-text!
                             :theme theme :line-numbers? true
                             :aria-label "Definition (EDN)" :height "280px"})
       (when (:binding-id selected)
         ($ :<>
            ($ :label {:html-for "selected-binding"} "Selected binding EDN")
            ($ editor/edn-editor {:text binding-text :on-change set-binding-text!
                                  :theme theme :line-numbers? true
                                  :aria-label "Selected binding (EDN)" :height "260px"})))
       (when result
         ($ :div.contract-object-result {:role "status" :aria-live "polite"}
            (if-let [findings (seq (:findings result))]
              ($ :ul.findings
                 (for [[index finding] (map-indexed vector findings)]
                   ($ :li.finding {:key index}
                      ($ :div.finding-head
                         ($ :span.code (str (:code finding)))
                         ($ :span.mono (pr-str (:path finding))))
                      ($ :div (or (:message finding) "The Contract did not pass validation."))))
              ($ :p.hint.ok (if (:revision result)
                              (str "Saved Contract revision " (:revision result) ".")
                              "Definition is valid.")))))))))

(defui relationships-panel [{:keys [model focus seeds theme on-select on-hover-edge hovered]}]
  (let [[maximized? set-maximized!] (uix/use-state false)
        [scope set-scope!] (uix/use-state :neighbors)
        [edge-kind set-edge-kind!] (uix/use-state :all)
        [fit-request set-fit-request!] (uix/use-state 0)
        shown (uix/use-memo
               #(graph-scope/filter-edges
                  (if (= scope :all) (:graph model)
                    (graph-scope/neighborhood (:graph model) (or (seq seeds) [focus]))) edge-kind)
               [model seeds focus scope edge-kind])
        dialog (uix/use-ref nil)
        trigger (uix/use-ref nil)
        was-maximized (uix/use-ref false)
        content ($ :<>
                   ($ :div.pane-head
                      ($ :span {:id (when maximized? "relationships-dialog-title")} "Relationships")
                      ($ :span.count (str (count (:nodes shown)) " attributes · "
                                          (count (:edges shown)) " hops"))
                      ($ :span.spacer)
                      ($ :button.btn {:ref (when-not maximized? trigger) :type "button"
                                      :aria-label (if maximized? "Close maximized relationships" "Maximize relationships")
                                      :on-click #(set-maximized! (not maximized?))}
                         (if maximized? "Close · Esc" "Maximize")))
                   ($ :div.relationships-tools
                      ($ :div.seg {:role "group" :aria-label "Relationship scope"}
                         (for [[k label] [[:neighbors "Selection + neighbors"] [:all "Full model"]]]
                           ($ :button {:key k :type "button" :class (when (= scope k) "on")
                                       :aria-pressed (= scope k) :on-click #(set-scope! k)} label)))
                      ($ :select {:aria-label "Relationship type" :value (name edge-kind)
                                  :on-change #(set-edge-kind! (keyword (.. % -target -value)))}
                         ($ :option {:value "all"} "All hops")
                         ($ :option {:value "direct"} "Resolver hops")
                         ($ :option {:value "nested"} "Nested outputs"))
                      ($ :button.btn {:type "button" :on-click #(set-fit-request! inc)} "Fit view"))
                   ($ :div.relationships-legend
                      ($ :span ($ :i.line) "Resolver hop")
                      ($ :span ($ :i.line.nested) "Nested output")
                      ($ :span "Node color = namespace · hover for names"))
                   (when hovered ($ :p.hint hovered))
                   ($ attribute-graph/attribute-graph
                      {:graph shown :focus focus :theme theme :fit-request fit-request
                       :on-select on-select :on-hover-edge on-hover-edge}))]
    (uix/use-layout-effect
     (fn []
       (when maximized? (.showModal @dialog))
       (when (and @was-maximized (not maximized?) @trigger) (.focus @trigger))
       (reset! was-maximized maximized?)
       js/undefined)
     [maximized?])
    ($ :<>
       (when-not maximized? ($ :section.pane.pane-graph.relationships-panel content))
       (when maximized?
         ($ :dialog.relationships-dialog
            {:ref dialog :aria-labelledby "relationships-dialog-title"
             :on-cancel #(do (.preventDefault %) (set-maximized! false))
             :on-close #(set-maximized! false)} content)))))

(defui contract-view [{:keys [graph document revision theme on-definition on-saved on-dirty-change explorer? initial-selection]}]
  (let [model (uix/use-memo #(assoc (blueprint/document->blueprint document)
                                             :terms (mapv (fn [[id definition]] {:id id :contract-id id :definition definition :label (:term/label definition)})
                                                          (get-in document [:vocabulary :terms]))) [document])
        [show-map? set-show-map!] (uix/use-state (boolean explorer?))
        [kind set-kind!] (uix/use-state (or (:kind initial-selection) :entities))
        [search set-search!] (uix/use-state "")
        [picked set-picked!] (uix/use-state (:id initial-selection))
        [hovered set-hovered!] (uix/use-state nil)
        [dirty? set-dirty!] (uix/use-state false)
        choose! (fn [f]
                  (when (or (not dirty?)
                            (js/confirm "Discard the unsaved changes for this selected Contract object?"))
                    (set-dirty! false)
                    (f)))
        needle (str/lower-case (str/trim search))
        rows (filterv #(or (str/blank? needle)
                          (str/includes? (str/lower-case (pr-str %)) needle)) (get model kind))
        selected (or (some #(when (= picked (or (:contract-index %) (:id %))) %) rows) (first rows))
        focus (case kind
                :attributes (:id selected)
                :entities (some-> selected :definition :entity/identity first blueprint/asset-name)
                :resolvers (some-> selected :input first blueprint/asset-name)
                nil)]
    (uix/use-effect (fn [] (when on-dirty-change (on-dirty-change dirty?)) js/undefined) [dirty? on-dirty-change])
    ($ :main.page-index {:class (when explorer? "graph-explorer")}
       ($ :section.pane.pane-rail {:style {:flex "0 0 25%"}}
          ($ :div.pane-head ($ :span "Model")
             (when explorer? ($ :button.btn {:on-click #(set-show-map! (not show-map?))} (if show-map? "Hide map" "Relationships"))))
          ($ :div.pane-body
             ($ :input.need-search {:type "search" :aria-label "Filter model"
                                    :placeholder "Search model"
                                    :disabled dirty?
                                    :value search :on-change #(set-search! (.. % -target -value))})
             ($ :div.need-sections
                (for [[k title] [[:entities "Entities"] [:attributes "Attributes"] [:resolvers "Resolvers"] [:mutations "Operations"] [:terms "Terms"] [:aliases "Aliases"] [:equivalences "Equivalences"]]]
                  ($ :button {:key (name k) :type "button" :aria-pressed (= kind k)
                              :disabled dirty?
                              :on-click #(choose! (fn [] (set-kind! k) (set-picked! nil)))}
                     (str title " " (count (get model k))))))
             (if (empty? rows)
               ($ :p.hint "No matches in this saved Contract.")
               (for [row rows]
                 ($ :button.entity-list-row {:key (or (:contract-index row) (:id row)) :type "button"
                                            :aria-pressed (= (or (:contract-index selected) (:id selected)) (or (:contract-index row) (:id row)))
                                            :class (when (= (or (:contract-index selected) (:id selected)) (or (:contract-index row) (:id row))) "on")
                                            :on-click #(choose! (fn [] (set-picked! (or (:contract-index row) (:id row)))))}
                    ($ :span.mono (:id row))
                    (when-let [label (or (:label row) (get-in row [:definition :label]))]
                      ($ :span.need-meta label)))))))
       (when show-map?
         ($ relationships-panel
            {:model model :focus focus
             :seeds (when (= kind :entities)
                      (mapv blueprint/asset-name (concat (get-in selected [:definition :entity/identity])
                                                        (get-in selected [:definition :entity/attributes]))))
             :theme theme :hovered hovered
             :on-select #(choose! (fn [] (set-kind! :attributes) (set-search! "") (set-picked! %)))
             :on-hover-edge set-hovered!}))
       ($ :section.pane.pane-dossier {:style {:flex "0 0 30%"}}
          ($ :div.pane-head ($ :span "Detail"))
          ($ :div.pane-body
             (when selected
               ($ :<>
                  (when (and (not explorer?) (= kind :mutations))
                    ($ mutation-form {:key (:id selected) :graph graph :revision revision
                                      :operation selected :document document :theme theme}))
                  ($ :div.model-object-summary
                     (when-let [description (or (get-in selected [:definition :entity/description])
                                               (get-in selected [:definition :attribute/description])
                                               (get-in selected [:definition :term/description])
                                               (get-in selected [:definition :alias/description])
                                               (get-in selected [:definition :equivalence/description])
                                               (get-in selected [:definition :description]))]
                       ($ :p description))
                     (when-let [identity (get-in selected [:definition :entity/identity])]
                       ($ :p "Identity: " (str/join ", " (map blueprint/asset-name identity))))
                     (when-let [attributes (get-in selected [:definition :entity/attributes])]
                       ($ :ul (for [attribute attributes] ($ :li {:key (str attribute)} ($ :code (blueprint/asset-name attribute)))))))
                  (if explorer?
                    ($ :button.btn {:on-click #(when on-definition (on-definition {:kind kind :id (or (:contract-index selected) (:id selected))}))} "Edit in Model")
                  ($ :details {:key (str "edit-" kind "-" (or (:contract-index selected) (:id selected))) :open (boolean initial-selection)}
                     ($ :summary "Edit definition")
                  ($ selected-definition-editor
                     {:key (str (name kind) ":" (or (:contract-index selected) (:id selected)))
                      :graph graph :document document :revision revision
                      :kind kind :selected selected :theme theme
                      :on-saved on-saved :on-dirty-change set-dirty!}))))))))))

(def guide-fields
  [[:guide/purpose "Purpose"] [:guide/asset-scope "Scope"]
   [:guide/applicability "When to use"] [:guide/limitations "Limitations (one per line)"]
   [:guide/composition "Using with other Graphs"]])

(def guide-help
  {:guide/purpose ["What does this Graph help someone understand or do?"
                   "Answer questions about orders, customers, and fulfillment performance."]
   :guide/asset-scope ["Describe the data and business concepts covered."
                       "Includes orders, order items, customers, and shipment status. Excludes billing and refunds."]
   :guide/applicability ["Name the questions or workflows this Graph is suited to."
                         "Use for daily order reporting and investigating delayed shipments."]
   :guide/limitations ["List known gaps or constraints, one per line."
                       "Data refreshes once a day.\nHistorical records are available from January 2024."]
   :guide/composition ["Explain how to connect this Graph to another, if relevant."
                       "Join with the Customer Graph using customer ID. Use the Finance Graph for payment details."]})

(defn guide-form-value [guide key]
  (if (= key :guide/limitations) (str/join "\n" (get guide key)) (or (get guide key) "")))

(defn update-guide-field [guide key text]
  (let [value (if (= key :guide/limitations)
                (vec (remove str/blank? (map str/trim (str/split-lines text))))
                (str/trim text))]
    (if (empty? value) (dissoc guide key) (assoc guide key value))))

(defui usage-guide-form [{:keys [graph document revision on-saved on-dirty-change]}]
  (let [original (or (get-in document [:graph :graph/usage-guide]) {})
        [guide set-guide!] (uix/use-state original)
        [saved set-saved!] (uix/use-state original)
        [busy? set-busy!] (uix/use-state false)
        [error set-error!] (uix/use-state nil)
        [saved? set-saved?] (uix/use-state false)
        dirty? (not= guide saved)]
    (uix/use-effect (fn [] (on-dirty-change dirty?) js/undefined) [dirty? on-dirty-change])
    ($ :section.usage-guide-form
       ($ :header.guide-header
          ($ :div ($ :h3 "Help others use this Graph")
             ($ :p "Describe what it covers, when to use it, and what to keep in mind."))
          ($ :span.guide-optional "All fields optional"))
       ($ :div.guide-guideline
          ($ :strong "Writing guidelines")
          ($ :p "Keep each answer to one or two plain-language sentences. Be specific about coverage and constraints. Skip anything that does not apply.")
          ($ :span "The examples below are illustrative; they are not saved as part of your model."))
       ($ :div.guide-fields
          (for [[key label] guide-fields
                :let [[hint example] (get guide-help key)
                      field-id (str "usage-guide-" (name key))]]
            ($ :div.guide-field {:key (name key)}
               ($ :label {:html-for field-id} label)
               ($ :p.guide-field-hint {:id (str field-id "-hint")} hint)
               ($ :textarea {:id field-id :rows 3 :aria-label label :disabled busy?
                             :aria-describedby (str field-id "-hint " field-id "-example")
                             :placeholder "Write a short note…"
                             :value (guide-form-value guide key)
                             :on-change #(do (set-saved? false)
                                             (let [text (.. % -target -value)]
                                               (set-guide! (fn [current]
                                                             (assoc current key (if (= key :guide/limitations)
                                                                                  (str/split text #"\n" -1) text))))))})
               ($ :div.guide-example {:id (str field-id "-example")}
                  ($ :span "Example") ($ :p example)))))
       (when-let [examples (seq (get-in document [:graph :graph/query-examples]))]
         ($ :fieldset ($ :legend "Related examples (optional)")
            (for [example examples :let [id (:example/id example)]]
              ($ :label {:key id}
                 ($ :input {:type "checkbox" :disabled busy?
                            :checked (boolean (some #{id} (:guide/examples guide)))
                            :on-change #(set-guide! (update guide :guide/examples
                                                     (fn [ids] (if (.. % -target -checked)
                                                                 (conj (vec ids) id) (vec (remove #{id} ids))))))})
                 (or (:example/label example) id)))))
       ($ :div.actions.guide-actions
          ($ :span.guide-save-status {:role "status"} (if dirty? "Unsaved changes" ""))
          ($ :button.btn.primary {:disabled (or busy? (not dirty?))
                          :on-click #(let [clean (reduce (fn [g [key _]] (update-guide-field g key (guide-form-value guide key))) guide guide-fields)
                                           next-document (if (empty? clean)
                                                           (update document :graph dissoc :graph/usage-guide)
                                                           (assoc-in document [:graph :graph/usage-guide] clean))]
                                       (set-busy! true) (set-error! nil)
                                       (api/intent! "semantic_draft"
                                         {:graph graph :action :save :expected_revision revision
                                          :text (with-out-str (pprint/pprint next-document))}
                                         {:on-success (fn [r]
                                                        (set-busy! false)
                                                        (if (seq (:findings r)) (set-error! r)
                                                          (do (set-guide! clean) (set-saved! clean) (set-saved? true) (on-saved r))))
                                          :on-error (fn [e] (set-busy! false) (set-error! e))}))}
             (if busy? "Saving…" "Save"))
          ($ :button.btn {:disabled (or busy? (not dirty?)) :on-click #(do (set-guide! saved) (set-error! nil))} "Cancel"))
       (when saved? ($ :p.hint.ok {:role "status"} "Saved."))
       (when error ($ :p.hint.bad {:role "alert"} (or (:message error) (some-> error :findings first :message) "Could not save. Your changes are still here."))))))

(defui graph-editor
  "Saved Contracts and legacy recipes have different stores. Discover the
   authoritative draft before selecting a view; never turn a refusal into an
   empty legacy graph. Key this component by workspace/graph/model revision."
  [{:keys [graph theme can-edit? on-definition on-dirty-change explorer? initial-selection guide? children]}]
  (let [[state set-state!] (uix/use-state {:loading? true})]
    (uix/use-effect
     (fn []
       (let [active? (atom true)]
         (if (and can-edit? (seq graph) (not= "*" graph))
           (api/intent! "semantic_draft" {:graph graph :action :read}
                      {:on-success #(when @active? (set-state! {:response %}))
                       :on-error #(when @active? (set-state! {:error %}))})
           (set-state! {}))
         #(reset! active? false)))
     [graph can-edit?])
    (let [{:keys [document revision findings]} (:response state)]
      (cond
        (:loading? state) ($ :main ($ :p.hint "Loading saved Graph Definition…"))
        (or (:error state) (seq findings))
        ($ :main ($ :p.hint.bad "Graph Definition could not be read. Check access or reload to retry."))
        (and guide? document)
        ($ usage-guide-form {:graph graph :document document :revision revision
                             :on-dirty-change on-dirty-change :on-saved #(set-state! {:response %})})
        (and document (pos? (or revision 0)))
        ($ contract-view {:graph graph :document document :revision revision :theme theme
                          :on-definition on-definition :on-dirty-change on-dirty-change
                          :explorer? explorer? :initial-selection initial-selection
                          :on-saved #(set-state! {:response %})})
        :else children))))
