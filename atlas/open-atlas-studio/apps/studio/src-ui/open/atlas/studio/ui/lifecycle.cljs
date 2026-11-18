(ns open.atlas.studio.ui.lifecycle
  (:require ["cytoscape" :as cytoscape]
            ["cytoscape-dagre" :as dagre]
            [clojure.string :as str]
            [open.atlas.studio.ui.api :as api]
            [uix.core :as uix :refer [defui $]]))

(defonce registered (do (cytoscape/use dagre) true))
(def event-fields
  [:nifi.provenance.event/source_id :nifi.provenance.event/node_id :nifi.provenance.event/id
   :nifi.provenance.event/flowfile_uuid :nifi.provenance.event/type :nifi.provenance.event/time
   :nifi.provenance.event/component_id :nifi.provenance.event/component_name
   :nifi.provenance.event/details :nifi.provenance.event/relationship])
(def lineage-fields
  [:nifi.lineage/id :nifi.lineage/completeness
   {:nifi.lineage/nodes [:nifi.lineage.node/id :nifi.lineage.node/type :nifi.lineage.node/flowfile_uuid
                        :nifi.lineage.node/event_type :nifi.lineage.node/millis]}
   {:nifi.lineage/links [:nifi.lineage.link/id :nifi.lineage.link/source_id :nifi.lineage.link/target_id]}])

(defn graph-elements [nodes links]
  (let [ids (set (map :nifi.lineage.node/id nodes))]
    (into (mapv (fn [n] {:data {:id (str "node/" (:nifi.lineage.node/id n)) :originalId (:nifi.lineage.node/id n)
                               :label (str (or (:nifi.lineage.node/event_type n) (:nifi.lineage.node/type n))
                                           "\n" (:nifi.lineage.node/id n))}}) nodes)
          (for [l links :when (and (ids (:nifi.lineage.link/source_id l)) (ids (:nifi.lineage.link/target_id l)))]
            {:data {:id (str "edge/" (:nifi.lineage.link/id l))
                    :source (str "node/" (:nifi.lineage.link/source_id l)) :target (str "node/" (:nifi.lineage.link/target_id l))}}))))

(defui lineage-view [{:keys [data]}]
  (let [container (uix/use-ref nil)
        [selected set-selected!] (uix/use-state nil)
        nodes (:nifi.lineage/nodes data) links (:nifi.lineage/links data)
        elements (graph-elements nodes links)]
    (uix/use-effect
     (fn []
       (set-selected! nil)
       (when (and @container (seq (:nifi.lineage/nodes data)))
         (let [nodes (:nifi.lineage/nodes data)
               elements (graph-elements nodes (:nifi.lineage/links data))
               cy (cytoscape (clj->js {:container @container :elements elements
                                      :layout {:name "dagre" :rankDir "LR"}
                                      :style [{:selector "node" :style {:label "data(label)" :font-size 11 :text-wrap "wrap"
                                                                       :background-color "#a96518" :color "#333" :text-valign "bottom"}}
                                              {:selector "edge" :style {:width 2 :line-color "#8a8271" :target-arrow-color "#8a8271"
                                                                       :target-arrow-shape "triangle" :curve-style "bezier"}}]}))]
           (.on cy "tap" "node" (fn [e] (let [id (.data (.-target e) "originalId")]
                                          (set-selected! (first (filter #(= id (:nifi.lineage.node/id %)) nodes))))))
           (fn [] (.destroy cy))))) [data])
    ($ :section
       ($ :h4 "FlowFile lineage")
       ($ :p.hint (str (count nodes) " nodes · " (count links) " directed links · Evidence may be incomplete"))
       (if (seq nodes)
         ($ :div {:ref container :style {:height "360px" :width "100%" :border "1px solid #c8bfa9"}})
         ($ :p "No retained lineage nodes returned."))
       (when (< (- (count elements) (count nodes)) (count links))
         ($ :p {:role "status"} "Some link endpoints are absent from the returned evidence; those links remain in the list below."))
       (when selected
         ($ :dl
            (for [[k v] selected]
              ($ :div {:key (name k)} ($ :dt (name k)) ($ :dd (str v))))))
       ($ :details ($ :summary "Directed links")
          (for [l links]
            ($ :p {:key (:nifi.lineage.link/id l)}
               (str (:nifi.lineage.link/source_id l) " → " (:nifi.lineage.link/target_id l))))))))

(defui lifecycle [{:keys [graph revision]}]
  (let [[fields set-fields!] (uix/use-state [])
        [field set-field!] (uix/use-state "FlowFileUUID")
        [value set-value!] (uix/use-state "")
        [busy set-busy!] (uix/use-state false)
        [error set-error!] (uix/use-state nil)
        [events set-events!] (uix/use-state nil)
        [detail set-detail!] (uix/use-state nil)
        [lineage set-lineage!] (uix/use-state nil)
        run! (uix/use-callback
              (fn [entity query accept!]
                (set-busy! true) (set-error! nil)
                (api/intent! "semantic_draft" {:graph graph :action :query :expected_revision revision :entity entity :query query}
                  {:on-success (fn [r]
                                 (set-busy! false)
                                 (if (and (empty? (:findings r)) (= :ok (get-in r [:response :engine/status])))
                                   (accept! (get-in r [:response :engine/data]))
                                   (set-error! (str "Query failed: " (or (get-in r [:response :code])
                                                                         (get-in r [:findings 0 :code]) "source unavailable")))))
                   :on-error (fn [_] (set-busy! false) (set-error! "Query unavailable. Reload the saved draft or sign in again."))})) [graph revision])]
    (uix/use-effect
     (fn [] (run! {} [{:nifi.provenance.search_field/all [:nifi.provenance.search_field/id]}]
                     #(set-fields! (mapv :nifi.provenance.search_field/id (:nifi.provenance.search_field/all %)))) js/undefined)
     [run!])
    ($ :section.lifecycle
       ($ :h3 "FlowFile lifecycle")
       ($ :p.hint "Search retained NiFi evidence. Results use source order, are limited to 100 events and may be incomplete.")
       ($ :div.actions
          ($ :label "Search field "
             ($ :select {:value field :disabled busy :on-change #(set-field! (.. % -target -value))}
                (for [f fields] ($ :option {:key f :value f} f))))
          ($ :input {:aria-label "Search value" :value value :placeholder "UUID or filename"
                     :on-change #(set-value! (.. % -target -value))})
          ($ :button.btn {:disabled (or busy (str/blank? value) (not (some #{field} fields)))
                          :on-click #(do (set-events! nil) (set-detail! nil) (set-lineage! nil)
                                         (run! {:nifi.provenance.search/field field :nifi.provenance.search/value value}
                                               [{(list :nifi.provenance.search/events {:limit 100}) event-fields}]
                                               (fn [data] (set-events! (:nifi.provenance.search/events data)))))}
             (if busy "Loading…" "Search")))
       (when error ($ :p {:role "alert"} error))
       (when (some? events)
         ($ :div {:style {:overflow-x "auto"}}
            ($ :p {:role "status"} (str (count events) " events returned"))
            ($ :table
               ($ :thead ($ :tr (for [s ["Time" "Event" "Component" "FlowFile" "Inspect"]] ($ :th {:key s} s))))
               ($ :tbody
                  (for [e events]
                    ($ :tr {:key (str (:nifi.provenance.event/node_id e) ":" (:nifi.provenance.event/id e))}
                       ($ :td (str (:nifi.provenance.event/time e)))
                       ($ :td (:nifi.provenance.event/type e))
                       ($ :td (or (:nifi.provenance.event/component_name e) (:nifi.provenance.event/component_id e)))
                       ($ :td (:nifi.provenance.event/flowfile_uuid e))
                       ($ :td
                          ($ :button.btn {:disabled busy
                                          :on-click #(run! (select-keys e [:nifi.provenance.event/id :nifi.provenance.event/node_id])
                                                           event-fields set-detail!)} "Details")
                          ($ :button.btn {:disabled (or busy (nil? (:nifi.provenance.event/flowfile_uuid e)))
                                          :on-click #(do (set-lineage! nil)
                                                         (run! {:nifi.flowfile/uuid (:nifi.provenance.event/flowfile_uuid e)}
                                                               lineage-fields set-lineage!))} "Lineage"))))))))
       (when detail
         ($ :section ($ :h4 "Event details")
            ($ :dl (for [k event-fields]
                     ($ :div {:key (name k)} ($ :dt (name k)) ($ :dd {:style {:overflow-wrap "anywhere"}} (str (or (get detail k) "Unknown"))))))))
       (when lineage ($ lineage-view {:data lineage}))))
)
