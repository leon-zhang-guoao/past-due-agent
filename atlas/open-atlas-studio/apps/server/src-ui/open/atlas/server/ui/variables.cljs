(ns open.atlas.server.ui.variables
  (:require [clojure.string :as str]
            [open.atlas.server.ui.api :as api]
            [open.atlas.common.ui.clipboard :as clipboard]
            [uix.core :as uix :refer [defui $]]))

(defn variable-rows [{:keys [variables unreferenced]}]
  (let [referenced (set (map :name variables))]
    (vec (sort-by :name
           (concat variables
                   (for [name unreferenced :when (not (contains? referenced name))]
                     {:name name :stored? true :present? true :used-by []}))))))

(defn valid-name? [name]
  (boolean (and (string? name) (re-matches #"[A-Za-z_][A-Za-z0-9_]*" name))))

(defui copy-reference [{:keys [name]}]
  (let [[status set-status!] (uix/use-state :idle)]
    (uix/use-effect
     (fn []
       (when (= status :copied)
         (let [timer (js/setTimeout #(set-status! :idle) 2500)]
           #(js/clearTimeout timer)))) [status])
    ($ :<>
       ($ :button.btn {:type "button" :aria-label (str "Copy reference for " name)
                       :on-click #(do (set-status! :copying)
                                      (-> (clipboard/copy-text! (str "${" name "}"))
                                          (.then (fn [_] (set-status! :copied)))
                                          (.catch (fn [_] (set-status! :failed)))))}
          ($ :span {:aria-live "polite"} (case status :copied "Copied ✓" :copying "Copying…" "Copy reference")))
       (when (= status :failed)
         ($ :span {:role "alert"} "Copy failed. Select the reference and copy it manually.")))))

(defui variables-page [{:keys [revision on-model-change]}]
  (let [[data set-data!] (uix/use-state nil)
        [form set-form!] (uix/use-state nil)
        [removing set-removing!] (uix/use-state nil)
        [busy? set-busy!] (uix/use-state false)
        [error set-error!] (uix/use-state nil)
        [notice set-notice!] (uix/use-state nil)
        [epoch set-epoch!] (uix/use-state 0)
        [query set-query!] (uix/use-state "")
        refresh! #(set-epoch! inc)]
    (uix/use-effect
      (fn []
        (api/request! {:method "GET" :path "/api/console/variables" :on-ok #(do (set-data! %) (set-error! nil)) :on-error set-error!})
        js/undefined)
      [epoch revision])
    ($ :div
       ($ :section.variables-page
          ($ :header.ws-section-heading
             ($ :h1 "Variables")
             ($ :<>
               ($ :button.btn.primary {:disabled (some? form)
                                       :on-click #(do (set-error! nil) (set-notice! nil)
                                                      (set-form! {:name "" :value ""}))}
                  "+ Add variable")))
          ($ :p.hint "Create a variable here, then use ${VARIABLE_NAME} in any Source field, including NiFi and TLS settings. You can add variables before any Source references them.")
          ($ :p.hint "Values are shared by this Server and are never displayed. Source usage below is for registered Sources. Updating a shared value affects every Source using it.")
          (when error ($ :p.hint.bad {:role "alert"} (or (:message error) "Unable to save variable.")))
          (when notice ($ :p.hint {:role "status"} notice))
          (when removing
            ($ :div.variable-form {:role "alertdialog" :aria-label "Remove variable"}
               ($ :p (str "Remove variable " removing "? This permanently removes its stored value. Referenced variables cannot be removed."))
               ($ :button {:disabled busy? :on-click #(do (set-busy! true) (set-error! nil)
                 (api/request! {:method "POST" :path "/api/console/variables/remove" :body {:name removing}
                                :on-ok (fn [_] (set-busy! false) (set-removing! nil) (refresh!)
                                         (when on-model-change (on-model-change)) (set-notice! "Variable removed."))
                                :on-error (fn [e] (set-busy! false) (set-removing! nil) (set-error! e))}))} "Confirm remove")
               ($ :button {:disabled busy? :on-click #(set-removing! nil)} "Cancel remove")))
          (when form
            ($ :form.variable-form
               {:on-submit
                (fn [event]
                  (.preventDefault event)
                  (set-busy! true) (set-error! nil)
                  (api/request!
                    {:method "POST" :path "/api/console/variables" :body form :on-ok (fn [result]
                                   (set-busy! false) (set-form! nil) (refresh!)
                                   (when on-model-change (on-model-change))
                                   (set-notice! (if (:renamed? result)
                                                  "Variable renamed. Source and saved release references updated; the old name has been removed."
                                                  "Variable saved. Use its reference in Source.")))
                     :on-error (fn [e] (set-busy! false) (set-error! e))}))}
               ($ :h4 (if (:previous-name form) "Edit variable" "Add variable"))
               ($ :label.source-field "Variable name"
                  ($ :input {:aria-label "Variable name" :auto-focus true :required true
                             :value (:name form) :spell-check false :disabled busy?
                             :pattern "[A-Za-z_][A-Za-z0-9_]*"
                             :placeholder "NIFI_CLIENT_STORE_PASS"
                             :on-change (fn [e] (let [v (.. e -target -value)]
                                                  (set-form! #(assoc % :name v))))}))
               ($ :label.source-field "Value"
                  ($ :input {:aria-label "Variable value" :type "password" :auto-complete "new-password"
                             :value (:value form) :disabled busy?
                             :required (nil? (:previous-name form))
                             :placeholder (if (:previous-name form) "Leave blank to keep current value" "Enter value")
                             :on-change (fn [e] (let [v (.. e -target -value)]
                                                  (set-form! #(assoc % :value v))))}))
               (when (:previous-name form)
                 ($ :p.hint "Changing the name renames this variable and updates Source and saved release references. The old name is removed."))
               (when (valid-name? (:name form))
                 ($ :p.hint "Source reference: " ($ :code (str "${" (:name form) "}"))))
               ($ :div.asset-editor-actions
                  ($ :button.btn.primary {:type "submit" :disabled (or busy? (not (valid-name? (:name form))))}
                     (if busy? "Saving…" "Save variable"))
                  ($ :button.btn {:type "button" :disabled busy? :on-click #(set-form! nil)} "Cancel"))))
          ($ :input {:type "search" :aria-label "Search variables" :placeholder "Search variable or Source…" :value query :on-change #(set-query! (.. % -target -value))})
          (cond
            (nil? data) ($ :p.hint "Loading variables…")
            (empty? (variable-rows data)) ($ :p.hint "No variables yet. Click Add variable to create one.")
            :else
            ($ :div.variables-table-wrap
               ($ :table.table
                  ($ :thead ($ :tr ($ :th "Variable / Source reference") ($ :th "Value status")
                                  ($ :th "Sources") ($ :th "Actions")))
                  ($ :tbody
                     (for [{:keys [name stored? present? used-by]} (variable-rows data)
                           :when (str/includes? (str/lower-case (str name " " (str/join " " used-by))) (str/lower-case query))]
                       ($ :tr {:key name}
                          ($ :td ($ :code (str "${" name "}")))
                          ($ :td (cond stored? "Stored" present? "Environment" :else "Not set"))
                          ($ :td (if (seq used-by) (str/join ", " used-by) "Not referenced here"))
                          ($ :td
                             ($ copy-reference {:name name})
                             ($ :<>
                               ($ :button.btn {:type "button" :disabled (some? form)
                                               :on-click #(do (set-error! nil) (set-notice! nil)
                                                              (set-form! {:previous-name name :name name :value ""}))}
                                  "Edit")
                             ($ :button.btn {:type "button" :disabled (or busy? (some? form) (some? removing))
                                             :on-click #(do (set-error! nil) (set-notice! nil) (set-removing! name))} "Remove")))))))))))))
