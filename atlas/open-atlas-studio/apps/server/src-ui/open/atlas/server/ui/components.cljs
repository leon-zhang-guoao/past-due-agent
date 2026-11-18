(ns open.atlas.server.ui.components
  (:require [uix.core :refer [$ defui]]))

(defui findings-list [{:keys [findings]}]
  (when (seq findings)
    ($ :ul.findings
       (for [[i f] (map-indexed vector findings)]
         ($ :li {:key i}
            ($ :code.code (str (:code f)))
            ($ :span.finding-message (:message f))
            (when (seq (:path f))
              ($ :span.finding-path (pr-str (:path f)))))))))

(defui notice [{:keys [error]}]
  (when error
    ($ :div.notice
       ($ :div.notice-message (:message error))
       ($ findings-list {:findings (:findings error)}))))

(defui card
  "A titled surface. `hint` is the sentence that says what the card is for and
   `right` its actions; both are optional."
  [{:keys [title hint right children]}]
  ($ :div.card
     (when (or title hint right)
       ($ :header
          (when title ($ :h2 title))
          (when hint ($ :span.hint hint))
          (when right ($ :div.right right))))
     ($ :div.body children)))


(defui read-state [{:keys [response retry]}]
  ($ :<>
     (when (:loading? response) ($ :p.read-state {:role "status"} "Loading…"))
     (when (:error response)
       ($ :div.read-state ($ notice {:error (:error response)})
          ($ :button {:on-click retry} "Retry")))))
