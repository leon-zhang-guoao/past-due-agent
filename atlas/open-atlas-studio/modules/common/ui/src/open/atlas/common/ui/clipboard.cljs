(ns open.atlas.common.ui.clipboard)

(defn- legacy-copy! [text document]
  (let [field (.createElement document "textarea")
        previous (.-activeElement document)]
    (try
      (set! (.-value field) text)
      (set! (.-readOnly field) true)
      (set! (.. field -style -cssText) "position:fixed;left:-9999px;top:0;opacity:0;")
      (.appendChild (.-body document) field)
      (.focus field)
      (.select field)
      (.setSelectionRange field 0 (count text))
      (when-not (.execCommand document "copy")
        (throw (js/Error. "Automatic copy is unavailable")))
      true
      (finally
        (set! (.-value field) "")
        (when (.-parentNode field) (.removeChild (.-parentNode field) field))
        (when (and previous (fn? (.-focus previous)))
          (try (.focus previous) (catch :default _ nil)))))))

(defn copy-text!
  "HTTPS clipboard API with an HTTP-compatible fallback. Never logs or stores text."
  ([text] (copy-text! text js/navigator js/document))
  ([text navigator document]
   (let [fallback (fn []
                    (try (js/Promise.resolve (legacy-copy! text document))
                         (catch :default _
                           (js/Promise.reject (js/Error. "Automatic copy is unavailable")))))
         clipboard (.-clipboard navigator)]
     (if (and clipboard (fn? (.-writeText clipboard)))
       (try
         (-> (js/Promise.resolve (.writeText clipboard text))
             (.catch (fn [_] (fallback))))
         (catch :default _ (fallback)))
       ;; Stay inside the click event when navigator.clipboard is absent.
       (fallback)))))
