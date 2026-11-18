(ns open.atlas.common.ui.mcp-connections
  (:require [open.atlas.common.ui.clipboard :as clipboard]
            [uix.core :refer [defui $ use-state]]))

(defui endpoint-row [{:keys [title url description credential]}]
  (let [[status set-status!] (use-state nil)]
    ($ :section.mcp-endpoint
       ($ :div.mcp-endpoint-heading ($ :h3 title) ($ :span.mcp-credential credential))
       ($ :p description)
       (if url
         ($ :div.mcp-url-row
            ($ :input {:read-only true :value url :aria-label (str title " URL")
                       :on-focus #(.select (.-target %))})
            ($ :button.btn {:type "button"
                            :on-click #(-> (clipboard/copy-text! url)
                                           (.then (fn [_] (set-status! "URL copied")))
                                           (.catch (fn [_] (set-status! "Select the URL and copy it manually."))))}
               "Copy URL"))
         ($ :p.hint "This release has no verified Graph UUID. Import a Studio release containing its Graph UUID."))
       ($ :p.mcp-copy-status {:role "status" :aria-live "polite"} (or status "")))))
