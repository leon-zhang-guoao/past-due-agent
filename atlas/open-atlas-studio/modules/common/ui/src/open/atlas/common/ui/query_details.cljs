(ns open.atlas.common.ui.query-details
  (:require [uix.core :refer [$ defui]]))

(defui query-details [{:keys [queries]}]
  ($ :div.query-details
     (for [[i query] (map-indexed vector queries)]
       ($ :section.query-detail {:key i}
          ($ :dl
             (for [[label value] [["Target" (:execution-target query)]
                                  ["Token ID" (:token-id query)]
                                  ["Operator" (get-in query [:principal :subject])]
                                  ["Source IP" (:remote-addr query)]
                                  ["Client" (:user-agent query)] ["Path" (:path query)]
                                  ["MCP tool" (:tool query)] ["Reason" (:reason query)]]
                   :when (some? value)]
               ($ :<> {:key label} ($ :dt label) ($ :dd (str value)))))
          ($ :h4 "EQL")
          ($ :pre {:style {:white-space "pre-wrap" :overflow-wrap "anywhere"}}
             (:eql query))))))
