(ns open.atlas.studio.ui.semantic
  (:require [clojure.string :as str]
            [open.atlas.studio.ui.api :as api]
            [open.atlas.studio.ui.guidance :as guidance]
            [open.atlas.studio.ui.vlist :as vlist]
            [uix.core :as uix :refer [defui $]]))

(defn- asset-name [value]
  (if (keyword? value)
    (subs (str value) 1)
    (str value)))

(defn- attribute-entity [attribute]
  (some-> attribute namespace (str/replace "/" ".")))

(defn- download-contract! [graph text]
  (let [safe-name (str/replace (or graph "graph") #"[^A-Za-z0-9_.-]" "_")
        blob (js/Blob. #js [text] #js {:type "application/edn;charset=utf-8"})
        url (.createObjectURL js/URL blob)
        anchor (.createElement js/document "a")]
    (set! (.-href anchor) url)
    (set! (.-download anchor) (str safe-name "-atlas-semantic-contract.edn"))
    (.click anchor)
    (.revokeObjectURL js/URL url)))

(defn document->need
  "Project a saved Semantic Contract into the compact Entities browser shape.
   This is deliberately a read projection: Definition remains the one editor
   and Query Studio executes the same saved document revision."
  [document]
  (let [entities (get-in document [:vocabulary :entities] {})
        attributes (get-in document [:vocabulary :attributes] {})
        references (get-in document [:vocabulary :references] [])
        graph-entities (or (seq (get-in document [:graph :graph/entities]))
                           (keys entities))
        relation-for
        (fn [entity-id attribute]
          (let [definition (get attributes attribute)]
            (when-let [target (:attribute/target definition)]
              {:name (asset-name attribute)
               :kind (if (= :many (:attribute/cardinality definition)) :many :one)
               :target (asset-name target)})))
        conditional-relations
        (group-by (fn [{:reference/keys [from]}] (attribute-entity from)) references)
        things
        (mapv
         (fn [entity-id]
           (let [entity (get entities entity-id)
                 members (:entity/attributes entity)
                 member-names (mapv asset-name members)
                 definitions (into {}
                                   (map (fn [attribute]
                                          [(asset-name attribute) (get attributes attribute)]))
                                   members)]
             {:name (asset-name entity-id)
              :label (:entity/label entity)
              :description (:entity/description entity)
              :identity (mapv asset-name (:entity/identity entity))
              :identity-declared? (seq (:entity/identity entity))
              :attributes member-names
              :attribute-definitions definitions
              :relations
              (vec
               (concat
                (keep #(relation-for entity-id %) members)
                (for [{:reference/keys [from to direction description]}
                      (get conditional-relations (asset-name entity-id))]
                  {:name (asset-name from)
                   :kind :one
                   :target (attribute-entity to)
                   :direction direction
                   :description description})))}))
         graph-entities)
        terms (get-in document [:vocabulary :terms] {})]
    {:things things
     :fragments []
     :concepts (mapv (fn [[term definition]]
                       {:concept (asset-name term)
                        :definition definition
                        :attributes (->> attributes
                                         (keep (fn [[attribute value]]
                                                 (when (= (asset-name term)
                                                          (asset-name (:attribute/term value)))
                                                   (asset-name attribute))))
                                         vec)})
                     terms)
     :findings []}))

(defn filter-source-rows [rows search unbound-only?]
  (let [q (str/lower-case (str/trim search))]
    (filterv (fn [row]
               (and (or (not unbound-only?) (not (contains? #{:ready :not-required} (:status row))))
                    (or (empty? q)
                        (str/includes? (str/lower-case (str (:label row) " " (:resolver-id row) " " (:operation-id row))) q))))
             rows)))

(defn load-source-draft!
  "Loading errors may arrive as HTTP-success findings. Never treat such a
   response as an empty Source list or leave the page silently disabled."
  [graph {:keys [on-draft on-view on-loading on-error]}]
  (let [failed (fn [stage r]
                 (on-loading false)
                 (on-error (assoc r :context :load
                                   :message (str "Could not load " stage ". "
                                                 (or (:message r) "Use Refresh Sources to retry; check that the Studio frontend and backend are the same version.")))))]
    (on-loading true)
    (api/intent! "semantic_draft" {:graph graph :action :read}
      {:on-error #(failed "the saved draft" %)
       :on-success
       (fn [r]
         (if (or (seq (:findings r)) (not (map? (:document r))))
           (failed "the saved draft" r)
           (do (on-draft r)
               (api/intent! "semantic_draft" {:graph graph :action :resolver-sources}
                 {:on-error #(failed "resolver Sources" %)
                  :on-success
                  (fn [v]
                    (if (or (seq (:findings v))
                            (not (vector? (:resolver-sources v)))
                            (not (vector? (:operation-sources v))))
                      (failed "resolver Sources" v)
                      (do (on-view v) (on-loading false))))}))))})))

(defui semantic-editor [{:keys [graph can-edit? on-dirty-change]}]
  (let [[draft set-draft!] (uix/use-state nil)
        [view set-view!] (uix/use-state nil)
        [busy set-busy!] (uix/use-state false)
        [progress set-progress!] (uix/use-state nil)
        [loading? set-loading!] (uix/use-state true)
        [report set-report!] (uix/use-state nil)
        [search set-search!] (uix/use-state "")
        [unbound-only? set-unbound-only!] (uix/use-state false)
        ;; The Source picked for each kind but not yet applied: «set all» is a
        ;; two-step act (choose, then apply) because it overwrites bindings.
        [kind-choice set-kind-choice!] (uix/use-state {})
        load! (uix/use-callback
               (fn []
                 (set-view! nil)
                 (set-report! (fn [r] (when (not= :load (:context r)) r)))
                 (load-source-draft! graph {:on-draft set-draft! :on-view set-view!
                                           :on-loading set-loading! :on-error set-report!})) [graph])
        finish! (uix/use-callback
                 (fn [r]
                   (set-busy! false) (set-progress! nil) (set-report! r)
                   (when (or (:imported? r) (:updated? r)) (load!))) [load!])
        fail! (fn [r] (set-busy! false) (set-progress! nil) (set-report! r))
        configure! (fn [args]
                     (set-busy! true) (set-progress! "Saving Source configuration…")
                     (api/intent! "semantic_draft"
                       (merge {:graph graph :action :configure-resolver-sources
                               :expected_revision (:revision view)} args)
                       {:on-success finish! :on-error fail!}))
        import-file!
        (fn [event]
          (let [input (.-target event) file (aget (.-files input) 0)]
            (when file
              (set! (.-value input) "")
              (set-busy! true) (set-report! nil) (set-progress! "Reading file…")
              (let [file-reader (js/FileReader.)]
                (set! (.-onerror file-reader) #(fail! {:message "The selected file could not be read. No draft was changed."}))
                (set! (.-onload file-reader)
                      (fn [e]
                        (let [content (.. e -target -result)]
                          (set-progress! "Validating and importing draft…")
                          (api/intent! "semantic_draft"
                            {:graph graph :action :import :text content :expected_revision (:revision draft)}
                            {:on-success #(finish! (assoc % :import-file (.-name file))) :on-error fail!}))))
                (.readAsText file-reader file)))))
        rows (vec (concat (:resolver-sources view) (:operation-sources view)))
        summary (guidance/source-summary rows)
        filtered (filter-source-rows rows search unbound-only?)
        kind-counts (frequencies (keep #(when (:requires-source? %) (:required-kind %)) rows))
        kinds (sort (keys kind-counts))]
    (uix/use-effect (fn [] (when on-dirty-change (on-dirty-change false)) js/undefined) [on-dirty-change])
    (uix/use-effect (fn [] (when can-edit? (load!)) js/undefined) [load! can-edit?])
    ($ :section.semantic-definition
       (if-not can-edit?
         ($ :p "An owner or editor can import this model and configure Sources.")
         ($ :div.semantic-definition-body
            ;; One row: the three acts, and what the draft is. The sentence that
            ;; used to follow («Importing updates this draft…») is the tooltip.
            ($ :div.semantic-definition-import-strip
               ($ :label.btn {:class (when busy "disabled")}
                  "Import model (.edn)"
                  ($ :input {:type "file" :accept ".edn,application/edn,text/plain"
                             :disabled (or busy loading? (nil? draft)) :style {:display "none"} :on-change import-file!}))
               ($ :button.btn {:disabled (or busy (not (pos? (or (:revision draft) 0))))
                               :on-click #(download-contract! graph (:text draft))} "Download")
               ($ :button.btn {:disabled busy :on-click load! :title "Re-read the draft and the Workspace's Sources"} "Refresh")
               (when (and draft (not loading?))
                 ($ :span.draft-line
                    {:role "status"
                     :title (str "Saved " (or (:saved-at draft) "—") ". Importing updates this draft; Publish checks and releases the saved revision.")}
                    (str "rev " (:revision draft) " · "
                         (count (get-in draft [:document :graph :graph/entities])) " entities · "
                         (count (get-in draft [:document :graph :graph/resolvers])) " resolvers · "
                         (count (get-in draft [:document :graph :graph/operations])) " operations"))))
            (when loading? ($ :p.hint {:role "status" :aria-live "polite"} "Loading saved draft and resolver Sources…"))
            (when progress ($ :p.hint {:role "status" :aria-live "polite"} progress))
            (when report
              ($ :div.semantic-definition-result {:role (if (or (:message report) (seq (:findings report))) "alert" "status")}
                 (cond
                   (seq (:findings report))
                   ($ :div ($ :strong (if (= :load (:context report)) "Could not load saved draft or Sources" "No changes saved"))
                      ($ :ul (for [[i f] (map-indexed vector (:findings report))]
                               ($ :li {:key i} (str (:code f) " " (pr-str (:path f)) " — " (:message f))))))
                   (:message report) ($ :p (:message report))
                   (:imported? report) ($ :p (str "Imported" (when (:import-file report) (str " " (:import-file report))) " · revision " (:revision draft) ". Bind any unbound resolvers below, then Publish."))
                   (:updated? report) ($ :p "Saved."))))

            ;; Bindings: title, how many are done, and the filters, on one line.
            ($ :div.bindings-head
               ($ :h3 "Resolver Sources")
               (when (and view (not loading?))
                 ($ :span {:class (str "bindings-state " (if (pos? (:needs-attention summary)) "bad" "ok")) :role "status" :aria-live "polite"}
                    (cond
                      (zero? (:total summary)) "no bindings"
                      (pos? (:needs-attention summary)) (str (:needs-attention summary) " of " (:total summary) " need a Source")
                      :else (str "all " (:total summary) " bound"))))
               ($ :span.spacer)
               ($ :input {:type "search" :value search :placeholder "Search resolver"
                          :aria-label "Search resolver Sources" :on-change #(set-search! (.. % -target -value))})
               ($ :label.check ($ :input {:type "checkbox" :checked unbound-only?
                                          :on-change #(set-unbound-only! (.. % -target -checked))}) "Needs attention")
               ($ :span.hint (str (count filtered) "/" (count rows))))

            ;; One row per kind: pick a Source, apply it to EVERY binding of the
            ;; kind. It replaces what each pointed at — that is the point of it.
            (when (seq kinds)
              ($ :div.kind-sets
                 (for [kind kinds
                       :let [n (get kind-counts kind)
                             choice (get kind-choice kind "")]]
                   ($ :div.kind-set {:key kind}
                      ($ :span.kind-name (str "All " n " " kind))
                      ($ :select {:value choice :disabled busy :aria-label (str "Source for all " kind " resolvers")
                                  :on-change #(set-kind-choice! (assoc kind-choice kind (.. % -target -value)))}
                         ($ :option {:value ""} "Choose Source…")
                         (for [{:keys [id label type]} (:source-options view) :when (= type kind)]
                           ($ :option {:key id :value id} (or label id))))
                      ($ :button.btn {:disabled (or busy (str/blank? choice))
                                      :title (str "Point all " n " " kind " resolvers at this Source, replacing their current one")
                                      :on-click #(do (set-kind-choice! (dissoc kind-choice kind))
                                                     (configure! {:default_sources {kind choice}}))}
                         "Set all")))))

            ($ :div.semantic-resolver-sources
               ($ vlist/vlist
                  {:key (str search "/" unbound-only?)
                   :items filtered :key-fn :binding-id :row-size 40
                   :viewport-style {:height "100%" :overflow-y "auto"}
                   :empty-note (cond loading? "Loading resolver Sources…"
                                     (nil? view) "Source list unavailable. See the error above and use Refresh."
                                     (empty? rows) "This draft has no resolver or operation bindings. Import a model to begin."
                                     :else "No bindings match the current filters.")
                   :render-row
                   (fn [{:keys [resolver-id operation-id label binding-id required-kind requires-source? selected-source status workspace-options]}]
                     (let [id (or resolver-id operation-id)]
                       ($ :div.src-row
                          ($ :div.src-name
                             ($ :strong {:title id} (or label id))
                             (when (not= label id) ($ :span.mono {:title id} id)))
                          ($ :span.src-kind (or required-kind "?"))
                          (if-not requires-source?
                            ($ :span.src-none "no Source needed")
                            ($ :select {:aria-label (str "Source for " id)
                                        :disabled busy
                                        :value (if (or (nil? selected-source) (str/starts-with? selected-source "unbound:")) "" selected-source)
                                        :on-change #(when (seq (.. % -target -value))
                                                      (configure! {:binding_sources {binding-id (.. % -target -value)}}))}
                               ($ :option {:value ""} "Unbound — choose Source")
                               (when (and selected-source (not (str/starts-with? selected-source "unbound:"))
                                          (not (some #(= selected-source (:id %)) workspace-options)))
                                 ($ :option {:value selected-source :disabled true} (str selected-source " (unavailable)")))
                               (for [{:keys [id label type]} workspace-options]
                                 ($ :option {:key id :value id} (str (or label id) " · " type)))))
                          ($ :span {:class (str "src-status " (if (contains? #{:ready :not-required} status) "ok" "bad"))}
                             (case status :ready "bound" :not-required "—" :source-incompatible "not bound" :no-compatible-source "no Source of this kind" "not bound")))))})))))))

(defui graph-sources [{:keys [graph can-edit? on-create-connection initial-source]}]
  (let [[view set-view!] (uix/use-state nil)
        [mapping set-mapping!] (uix/use-state {})
        [undo-sources set-undo-sources!] (uix/use-state nil)
        [chosen set-chosen!] (uix/use-state (or initial-source ""))
        [busy set-busy!] (uix/use-state false)
        [error set-error!] (uix/use-state nil)
        load! (uix/use-callback
               (fn []
                 (set-busy! true)
                 (api/intent! "semantic_draft" {:graph graph :action :graph-sources}
                              {:on-success #(do (set-busy! false) (set-error! nil) (set-view! %))
                               :on-error #(do (set-busy! false) (set-error! %))}))
               [graph])
        save! (uix/use-callback
               (fn [ids replacements]
                 (set-busy! true)
                 (set-error! nil)
                 (api/intent! "semantic_draft"
                              {:graph graph :action :update-graph-sources
                               :source_ids (vec ids) :source_replacements replacements
                               :expected_revision (:revision view)}
                              {:on-success (fn [r]
                                             (if (seq (:findings r))
                                               (do (set-busy! false) (set-error! r))
                                               (do (set-chosen! "") (set-mapping! {}) (load!))))
                               :on-error #(do (set-busy! false) (set-error! %))}))
               [graph view load!])
        graph-sources (vec (:graph-sources view))
        current-ids (mapv :id graph-sources)
        workspace-sources (vec (:workspace-sources view))]
    (uix/use-effect (fn [] (when can-edit? (load!)) js/undefined) [can-edit? load!])
    ;; The connections this graph reads, as a table: who, what kind, how many
    ;; resolvers lean on it, and the two things you can do to it. Adding sits in
    ;; the header, where the eye already is, instead of under an empty list.
    ($ :section.semantic-definition.graph-sources
       ($ :div.gs-head
          ($ :h3 "Connections")
          ($ :span.gs-count (str (count graph-sources) " used by this graph"))
          ($ :span.spacer)
          (when (and can-edit? (not (and busy (nil? view))))
            ($ :div.gs-add
               ($ :select {:value chosen :aria-label "Add a Workspace connection"
                           :disabled (or busy (empty? workspace-sources))
                           :on-change #(set-chosen! (.. % -target -value))}
                  ($ :option {:value ""}
                     (if (seq workspace-sources) "Add connection…" "No other Workspace connections"))
                  (for [{:keys [id label type]} workspace-sources]
                    ($ :option {:key id :value id}
                       (str (or label id) " · " type))))
               ($ :button.btn.btn--primary
                  {:type "button" :disabled (or busy (str/blank? chosen))
                   :on-click #(do (set-undo-sources! nil) (save! (conj current-ids chosen) {}))}
                  "Add")
               (when on-create-connection
                 ($ :button.btn {:type "button" :on-click on-create-connection
                                 :title "Register a new connection in the Workspace"} "New…")))))
       (cond
         (not can-edit?)
         ($ :p.hint "An owner or editor can maintain this Graph's connections.")

         (and busy (nil? view))
         ($ :p.hint "Loading connections…")

         :else
         ($ :div.semantic-definition-body
            (when error
              ($ :div.semantic-definition-result {:role "alert"}
                 ($ :p.hint.bad (or (:message error) "Connections could not be updated."))
                 (when (seq (:findings error))
                   ($ :ul.findings
                      (for [[index finding] (map-indexed vector (:findings error))]
                        ($ :li.finding {:key index}
                           ($ :div.finding-head ($ :span.code (str (:code finding))))
                           ($ :div (:message finding))))))))

            (if (seq graph-sources)
              ($ :div.gs-table {:role "table" :aria-label "Connections used by this graph"}
                 (for [{:keys [id label type effects used-by registered?]} graph-sources]
                   ($ :div.gs-row {:key id :role "row"}
                      ($ :div.gs-name
                         ($ :strong (or label id))
                         ($ :span.mono id)
                         (when-not registered? ($ :span.gs-warn "no longer registered")))
                      ($ :span.gs-kind (str type (when (seq effects) (str " · " (str/join "/" (map name (sort effects)))))))
                      (if (seq used-by)
                        ($ :details.source-usage
                           ($ :summary (str (count used-by) " resolver" (when (not= 1 (count used-by)) "s")))
                           ($ :ul (for [target used-by] ($ :li.mono {:key target} target))))
                        ($ :span.gs-unused "unused"))
                      ($ :div.gs-actions
                         ($ :select {:aria-label (str "Replace " id " with")
                                     :title "Switch every resolver using this connection to another one of the same kind"
                                     :value (get mapping id id) :disabled busy
                                     :on-change #(set-mapping! (assoc mapping id (.. % -target -value)))}
                            (when-not registered? ($ :option {:value id} "Replace with…"))
                            (for [option (:available-sources view) :when (= type (:type option))]
                              ($ :option {:key (:id option) :value (:id option)} (or (:label option) (:id option)))))
                         (when (and (get mapping id) (not= id (get mapping id)))
                           ($ :button.btn {:disabled busy
                                           :on-click #(do (set-undo-sources! nil)
                                                          (save! (mapv (fn [source] (if (= source id) (get mapping id) source)) current-ids)
                                                                 {id (get mapping id)}))} "Replace"))
                         (when-not (seq used-by)
                           ($ :button.btn {:disabled busy
                                           :on-click #(do (set-undo-sources! current-ids)
                                                          (save! (remove #{id} current-ids) {}))} "Remove"))))))
              ($ :p.gs-empty
                 "No connections yet. Add one from the Workspace above, or create a new one."))
            (when undo-sources
              ($ :button.btn {:disabled busy :on-click #(do (save! undo-sources {}) (set-undo-sources! nil))} "Undo removal"))
            ($ :p.hint.gs-foot
               "Resolvers are bound to these on Model → Import/Export. Credentials stay in Workspace → Sources."))))))
