(ns open.atlas.common.ui.source-bindings
  "A Source-slot assignment view, independent of authoring or release storage."
  (:require [uix.core :refer [$ defui]]))

(defui resolver-bindings [{:keys [sources selection disabled? on-change on-open-source]}]
  ($ :div.table-scroll
     ($ :table.table.resolver-source-table
        ($ :thead ($ :tr ($ :th "Resolver / operation") ($ :th "Source slot") ($ :th "Connection") ($ :th "State")))
        ($ :tbody
           (for [source sources
                 use (if (seq (:uses source)) (:uses source) [{:target-id "No resolver references"}])
                 :let [slot (:slot-id source) connection (or (get selection slot) (:source-id source) (:selected-source source))
                       required? (not= :not-required (:binding-status source))]]
             ($ :tr {:key (str slot "/" (:binding-id use))}
                ($ :td (:target-id use)
                   (when (:target-kind use) ($ :span.row-group (str (name (:target-kind use))
                                                                  (when-not (:selected? use) " · alternate binding")))))
                ($ :td slot ($ :span.row-group (:kind source)))
                ($ :td
                   (cond
                     (not required?) "No external connection"
                     on-change ($ :select {:aria-label (str "Source for " (or (:binding-id use) slot))
                                            :value (or connection "") :disabled disabled?
                                            :on-change #(on-change slot (.. % -target -value))}
                                  ($ :option {:value ""} (if (= :bound (:binding-status source)) "Saved release binding" "Choose Source…"))
                                  (for [option (:options source)] ($ :option {:key (:id option) :value (:id option)}
                                                                     (str (:id option) (when (seq (:label option)) (str " · " (:label option)))))))
                     (and connection (:registered? source) on-open-source)
                     ($ :button.graph-link {:on-click #(on-open-source connection)} connection)
                     :else (or connection "Release configuration")))
                ($ :td (case (:binding-status source) :bound "Bound" :missing "Needs assignment" :embedded "Embedded" "Not required"))))))))
