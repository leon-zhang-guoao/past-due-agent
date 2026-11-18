(ns open.atlas.common.ui.access
  (:require [uix.core :as uix :refer [$ defui]]
            [open.atlas.common.ui.clipboard :as clipboard]))

(defui issued-secret
  "The one and only time this value exists.

   It is shown until dismissed and never fetched again — the ledger keeps a
   keyed digest, not the value (S5.2/S5.9). Saying so plainly is part of the
   design: a reader who assumes they can come back for it later will lose it."
  [{:keys [secret on-dismiss]}]
  (let [[copied? set-copied!] (uix/use-state false)
        [failed? set-failed!] (uix/use-state false)
        field (uix/use-ref nil)]
    (uix/use-effect
     (fn [] (set-copied! false) (set-failed! false) js/undefined)
     [secret])
    ($ :div.token-secret
       ($ :p.token-secret-head "Copy this now — it is shown once and never again.")
       ($ :textarea.token-secret-value
          {:ref field :value secret :read-only true :rows 3
           :aria-label "Issued token" :spell-check false
           :style {:width "100%" :font-family "monospace" :resize "vertical"}
           :on-focus #(.select (.-currentTarget %))
           :on-click #(.select (.-currentTarget %))})
       (when failed?
         ($ :p.hint {:role "alert"}
            "Automatic copy is unavailable. The token is selected; press Ctrl+C (Mac: Cmd+C), or right-click and choose Copy."))
       ($ :div.asset-editor-actions
          ($ :button.btn.primary
             {:type "button"
              :on-click (fn []
                          (set-copied! false)
                          (set-failed! false)
                          (-> (clipboard/copy-text! secret)
                              (.then (fn [_] (set-copied! true)))
                              (.catch (fn [_]
                                        (set-failed! true)
                                        (when-let [input (.-current field)]
                                          (.focus input)
                                          (.select input))))))}
             (if copied? "copied" "copy"))
          ($ :button.btn {:type "button" :on-click on-dismiss} "dismiss")))))


(defn token-status [record]
  (if (and (= :active (:status record)) (:expires-at record)
           (<= (js/Date.parse (:expires-at record)) (.now js/Date)))
    :expired (:status record)))

(defui token-list [{:keys [tokens on-revoke]}]
  (let [[busy set-busy!] (uix/use-state nil)]
    ($ :table.table.token-list
       ($ :thead ($ :tr ($ :th "Token") ($ :th "Graph") ($ :th "Status") ($ :th "Expires") ($ :th "")))
       ($ :tbody
          (for [t tokens :let [status (token-status t)]]
            ($ :tr {:key (:token-id t)}
               ($ :td ($ :code (:token-id t)))
               ($ :td (:graph-id t))
               ($ :td (name (or status :unknown)))
               ($ :td (or (:expires-at t) "Never"))
               ($ :td (when (= :active status)
                        ($ :button.btn {:disabled (some? busy)
                                        :on-click #(do (set-busy! (:token-id t))
                                                       (on-revoke (:token-id t) (fn [] (set-busy! nil))))}
                           (if (= busy (:token-id t)) "Revoking…" "Revoke"))))))))))
