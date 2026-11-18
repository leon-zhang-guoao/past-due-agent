(ns open.atlas.studio.ui.navigation
  (:require [uix.core :as uix :refer [defui $]]))

(defui guarded-content
  "Keep unsaved model edits when navigation is cancelled."
  [{:keys [dirty? children]}]
  (let [content (uix/use-ref nil)
        target (uix/use-ref nil)
        allowed? (uix/use-ref false)
        [leaving? set-leaving!] (uix/use-state false)]
    (uix/use-effect
     (fn []
       (let [unload (fn [e] (when dirty? (.preventDefault e) (set! (.-returnValue e) "")))
             navigate (fn [e]
                        (when (and dirty? (not @allowed?)
                                   (not (.contains @content (.-target e))))
                          (when-let [node (.closest (.-target e) "button,a,select,[role=tab]")]
                            (.preventDefault e) (.stopPropagation e)
                            (reset! target node) (set-leaving! true))))]
         (.addEventListener js/window "beforeunload" unload)
         (.addEventListener js/document "click" navigate true)
         (fn [] (.removeEventListener js/window "beforeunload" unload)
           (.removeEventListener js/document "click" navigate true))))
     [dirty?])
    ($ :section.guarded-model
       ($ :div {:ref content}
          (when leaving?
            ($ :div.settings-leave-confirmation {:role "alertdialog" :aria-label "Unsaved model changes"}
               ($ :p "Discard unsaved model changes and leave?")
               ($ :button.btn {:auto-focus true :on-click #(set-leaving! false)} "Keep editing")
               ($ :button.btn.btn--danger
                  {:on-click #(do (set-leaving! false) (reset! allowed? true)
                                  (.click @target) (reset! allowed? false))} "Discard and leave")))
          children))))
