(ns open.atlas.studio.ui.platform-app
  "A thin Platform shell around the Studio graph renderer migrated unchanged
   from open-atlas-studio. The renderer remains the authoritative graph UI."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [open.atlas.studio.ui.attribute-graph :as attribute-graph]
            [uix.core :as uix :refer [defui $]]
            [uix.dom :as uix.dom]))

(defn- fetch-edn! [path on-success on-error]
  (-> (js/fetch path {:headers {"Accept" "application/edn"}})
      (.then (fn [response] (.text response)))
      (.then (fn [text] (on-success (reader/read-string text))))
      (.catch (fn [error] (on-error (.-message error))))))

(defui builder []
  (let [[graph set-graph!] (uix/use-state nil)
        [graphs set-graphs!] (uix/use-state [])
        [root set-root!] (uix/use-state "demo/sales")
        [selected set-selected!] (uix/use-state nil)
        [query set-query!] (uix/use-state "")
        [error set-error!] (uix/use-state nil)]
    (uix/use-effect
     (fn []
       (fetch-edn! "/api/studio/graphs"
                   #(set-graphs! (:graphs %))
                   set-error!)
       js/undefined)
     [])
    (uix/use-effect
     (fn []
       (set-graph! nil)
       (set-selected! nil)
       (fetch-edn! (str "/api/pathom/index-graph?root=" (js/encodeURIComponent root)
                         (when (seq query)
                           (str "&q=" (js/encodeURIComponent query))))
                   set-graph!
                   set-error!)
       js/undefined)
     [root query])
    ($ :main.builder
       ($ :header
          ($ :div ($ :span.eyebrow "OPEN ATLAS / STUDIO") ($ :h1 "Builder"))
          ($ :div.live "● latest dialect · Pathom Viz graph"))
       ($ :section.summary
          ($ :label.graph-picker "Graph"
             ($ :select {:value root
                         :aria-label "Select Builder graph"
                         :on-change #(set-root! (.. % -target -value))}
                (for [entry graphs]
                  ($ :option {:key (:graph/qualified-name entry)
                              :value (:graph/qualified-name entry)}
                     (:graph/qualified-name entry)))))
          ($ :input {:type "search" :value query
                     :placeholder "Filter attributes or namespace…"
                     :aria-label "Filter Builder graph"
                     :on-change #(set-query! (.. % -target -value))})
          (when graph
            ($ :div.graph-scope
               ($ :span (str (get-in graph [:counts :shown]) " of "
                             (get-in graph [:counts :attributes]) " attributes · "
                             (get-in graph [:counts :edges]) " resolver hops"))
               ($ :small (str "Own: " (pr-str (get-in graph [:definition :graph/selectors]))
                              (when-let [references (seq (get-in graph [:definition :graph/references]))]
                                (str " · Uses: "
                                     (str/join ", "
                                                          (map #(str (:graph %) "@" (:version %)) references)))))))))
       (cond
         error ($ :p.error error)
         (nil? graph) ($ :p.loading "Loading Builder graph…")
         :else
         ($ :section.canvas
            ($ attribute-graph/attribute-graph
               {:graph graph :focus selected
                :on-select set-selected!
                :on-hover-edge (fn [_])
                :theme :light})
            ($ :aside
               ($ :span.eyebrow "ATTRIBUTE")
               (if selected
                 ($ :<>
                    ($ :h2 selected)
                    ($ :p "Selected in the graph. Drag, zoom, or choose another attribute to explore resolver capability paths."))
                 ($ :p "Select an attribute node. The graph is the migrated Studio Pathom Viz renderer, not a second graph implementation."))))))))

(defonce root (atom nil))

(defn init []
  (when-not @root
    (reset! root (uix.dom/create-root (js/document.getElementById "app"))))
  (uix.dom/render-root ($ builder) @root))
