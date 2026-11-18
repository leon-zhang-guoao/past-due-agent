(ns open.atlas.studio.ui.variables
  (:require [clojure.string :as str]
            [open.atlas.studio.ui.api :as api]
            [open.atlas.studio.ui.clipboard :as clipboard]
            [open.atlas.studio.ui.navigation :as navigation]
            [uix.core :as uix :refer [defui $]]))

(defn variable-rows [{:keys [variables unreferenced]}]
  (let [referenced (set (map :name variables))]
    (vec (sort-by :name
           (concat variables
                   (for [name unreferenced :when (not (contains? referenced name))]
                     {:name name :stored? true :present? true :used-by []}))))))

(defn valid-name? [name]
  (boolean (and (string? name) (re-matches #"[A-Za-z_][A-Za-z0-9_]*" name))))

(defn row-status
  "Where a variable's value comes from: stored in this instance, present only in
   its environment, or nowhere (a Source references it and nothing answers)."
  [{:keys [stored? present?]}]
  (cond stored? :stored present? :environment :else :not-set))

(def ^:private status-label {:stored "Stored" :environment "Environment" :not-set "Not set"})

(defn query-terms
  "The words of a search. `${NAME}` pasted from a Source field is searched as
   NAME — the braces are syntax, not part of any variable's name."
  [query]
  (->> (str/split (str/lower-case (str/replace (or query "") #"[${}]" " ")) #"\s+")
       (remove str/blank?)
       vec))

(defn filter-variable-rows
  "Rows matching every word of `query` — in the variable's name, in a Source that
   uses it, or in its status — and the `mode` filter: `:all`, `:unused` (no
   Source in this workspace references it) or `:not-set` (no value anywhere).
   A hundred variables is ordinary here, so finding one has to be a keystroke."
  [rows query mode]
  (let [terms (query-terms query)]
    (filterv (fn [row]
               (and (case mode
                      :unused (empty? (:used-by row))
                      :not-set (= :not-set (row-status row))
                      true)
                    (let [hay (str/lower-case (str (:name row) " " (str/join " " (:used-by row))
                                                   " " (status-label (row-status row))))]
                      (every? #(str/includes? hay %) terms))))
             rows)))

(defn sort-variable-rows
  "Sort by `:name`, `:status` or `:sources` (the first Source that uses it; the
   unreferenced sort last). Ties fall back to the name, so the order is stable."
  [rows sort-key dir]
  (let [k (case sort-key
            :status (fn [r] [(name (row-status r)) (:name r)])
            :sources (fn [r] [(if (seq (:used-by r)) (first (sort (:used-by r))) "\uffff") (:name r)])
            (fn [r] [(:name r)]))
        sorted (sort-by k rows)]
    (vec (if (= :desc dir) (reverse sorted) sorted))))

(defn- highlight
  "`text` with every occurrence of a search word wrapped in <mark>."
  [text terms]
  (if (or (empty? terms) (str/blank? text))
    text
    (let [escape #(.replace % (js/RegExp. "[.*+?^${}()|[\\]\\\\]" "g") "\\$&")
          re (js/RegExp. (str "(" (str/join "|" (map escape terms)) ")") "ig")]
      ($ :<>
         (map-indexed (fn [i part]
                        (if (odd? i)
                          ($ :mark {:key i} part)
                          ($ :<> {:key i} part)))
                      (.split (str text) re))))))

(defui variables-page [{:keys [can-edit? on-model-change]}]
  (let [[data set-data!] (uix/use-state nil)
        [form set-form!] (uix/use-state nil)
        [busy? set-busy!] (uix/use-state false)
        [error set-error!] (uix/use-state nil)
        [notice set-notice!] (uix/use-state nil)
        ;; owner 2026-10-04: the removal plan for one variable, fetched before
        ;; anything is deleted. Confirm is offered only when nothing uses it.
        [rm-plan set-rm-plan!] (uix/use-state nil)
        [epoch set-epoch!] (uix/use-state 0)
        [query set-query!] (uix/use-state "")
        [mode set-mode!] (uix/use-state :all)
        [sort-state set-sort!] (uix/use-state {:key :name :dir :asc})
        search-ref (uix/use-ref nil)
        refresh! #(set-epoch! inc)
        all-rows (when data (variable-rows data))
        terms (query-terms query)
        shown (when all-rows
                (sort-variable-rows (filter-variable-rows all-rows query mode)
                                    (:key sort-state) (:dir sort-state)))
        unused-n (count (filter #(empty? (:used-by %)) all-rows))
        unset-n (count (filter #(= :not-set (row-status %)) all-rows))
        sort-by! (fn [k] (set-sort! (fn [{cur :key dir :dir}]
                                      {:key k :dir (if (and (= cur k) (= dir :asc)) :desc :asc)})))]
    ;; «/» jumps to the search box from anywhere on the page, as on any list
    ;; long enough to need one — unless a field is already taking the keys.
    (uix/use-effect
      (fn []
        (let [on-key (fn [^js e]
                       (when (and (= "/" (.-key e))
                                  (not (.closest (.-target e) "input, textarea, select, [contenteditable]"))
                                  (.-current search-ref))
                         (.preventDefault e)
                         (.focus (.-current search-ref))))]
          (.addEventListener js/window "keydown" on-key)
          #(.removeEventListener js/window "keydown" on-key)))
      [])
    (uix/use-effect
      (fn []
        (api/variables! {:on-success set-data! :on-error set-error!})
        js/undefined)
      [epoch])
    ($ navigation/guarded-content {:dirty? (some? form)}
       ($ :section.variables-page
          ($ :header.ws-section-heading
             ($ :h3 "Variables")
             (when can-edit?
               ($ :button.btn.primary {:disabled (some? form)
                                       :on-click #(do (set-error! nil) (set-notice! nil)
                                                      (set-form! {:name "" :value ""}))}
                  "+ Add variable")))
          ($ :p.hint "Use ${NAME} in any Source field, including NiFi and TLS settings; a variable can exist before any Source references it. Values are shared by this Studio instance and never displayed — updating one affects every Source using it. Source usage shown is for this workspace.")
          (when error ($ :p.hint.bad {:role "alert"} (or (:message error) "Unable to save variable.")))
          (when notice ($ :p.hint {:role "status"} notice))
          (when rm-plan
            ($ :div.variable-form {:role "dialog" :aria-label "Remove variable"}
               ($ :h4 "Remove " ($ :code (str "${" (:variable rm-plan) "}")))
               (cond
                 (seq (:used-by rm-plan))
                 ($ :p.hint.bad (str "Still referenced by " (str/join ", " (:used-by rm-plan))
                                     ". Remove the reference first; nothing was removed."))
                 (not (:held? rm-plan))
                 ($ :p.hint.bad "No value is stored in this Studio (it may come from the environment); nothing to remove.")
                 :else
                 ($ :p.hint "No Source references this variable. Its stored value will be deleted."))
               ($ :div.asset-editor-actions
                  (when (:removable? rm-plan)
                    ($ :button.btn.danger
                       {:type "button" :disabled busy?
                        :on-click (fn []
                                    (set-busy! true) (set-error! nil)
                                    (api/remove-variable! (:variable rm-plan)
                                      {:on-success (fn [r]
                                                     (set-busy! false) (set-rm-plan! nil) (refresh!)
                                                     (set-notice! (str "Removed ${" (:variable r) "}.")))
                                       :on-error (fn [e] (set-busy! false) (set-error! e))}))}
                       (if busy? "Removing…" "Remove variable")))
                  ($ :button.btn {:type "button" :disabled busy? :on-click #(set-rm-plan! nil)}
                     (if (:removable? rm-plan) "Cancel" "Close")))))
          (when form
            ($ :form.variable-form
               {:on-submit
                (fn [event]
                  (.preventDefault event)
                  (set-busy! true) (set-error! nil)
                  (api/save-variable! form
                    {:on-success (fn [result]
                                   (set-busy! false) (set-form! nil) (refresh!)
                                   (when on-model-change (on-model-change))
                                   (set-notice! (if (:renamed? result)
                                                  "Renamed. References in this workspace were updated. The old variable is retained for other consumers."
                                                  "Variable saved. Use its reference in Source.")))
                     :on-error (fn [e] (set-busy! false) (set-error! e))}))}
               ($ :h4 (if (:previous-name form) "Edit variable" "Add variable"))
               ($ :label "Variable name"
                  ($ :input {:aria-label "Variable name" :auto-focus true :required true
                             :value (:name form) :spell-check false :disabled busy?
                             :pattern "[A-Za-z_][A-Za-z0-9_]*"
                             :placeholder "NIFI_CLIENT_STORE_PASS"
                             :on-change (fn [e] (let [v (.. e -target -value)]
                                                  (set-form! #(assoc % :name v))))}))
               ($ :label "Value"
                  ($ :input {:aria-label "Variable value" :type "password" :auto-complete "new-password"
                             :value (:value form) :disabled busy?
                             :required (nil? (:previous-name form))
                             :placeholder (if (:previous-name form) "Leave blank to keep current value" "Enter value")
                             :on-change (fn [e] (let [v (.. e -target -value)]
                                                  (set-form! #(assoc % :value v))))}))
               (when (:previous-name form)
                 ($ :p.hint "Changing the name updates this workspace's Source references and copies the value. The old variable remains available to other consumers."))
               (when (valid-name? (:name form))
                 ($ :p.hint "Source reference: " ($ :code (str "${" (:name form) "}"))))
               ($ :div.asset-editor-actions
                  ($ :button.btn.primary {:type "submit" :disabled (or busy? (not (valid-name? (:name form))))}
                     (if busy? "Saving…" "Save variable"))
                  ($ :button.btn {:type "button" :disabled busy? :on-click #(set-form! nil)} "Cancel"))))
          (cond
            (nil? data) ($ :p.hint "Loading variables…")
            (empty? all-rows) ($ :p.hint "No variables yet. Click Add variable to create one.")
            :else
            ($ :<>
               ;; Finding one of a hundred has to be a keystroke: search across
               ;; the name AND the Sources that use it, two quick filters for the
               ;; two housekeeping questions (what is unused, what has no value),
               ;; and the count so «no match» and «filtered out» are told apart.
               ($ :div.variables-toolbar
                  ($ :div.vt-search
                     ($ :input {:ref search-ref :type "search" :value query
                                :placeholder "Search variable or Source…   ( / )"
                                :aria-label "Search variables"
                                :auto-complete "off" :spell-check false
                                :on-change #(set-query! (.. % -target -value))
                                :on-key-down (fn [^js e]
                                               (when (= "Escape" (.-key e))
                                                 (if (seq query) (set-query! "") (.blur (.-target e)))))}))
                  ($ :div.seg {:role "group" :aria-label "Filter variables"}
                     ($ :button {:type "button" :class (when (= :all mode) "on") :on-click #(set-mode! :all)}
                        "All")
                     ($ :button {:type "button" :class (when (= :unused mode) "on")
                                 :title "No Source in this workspace references it"
                                 :on-click #(set-mode! :unused)}
                        (str "Unused " unused-n))
                     ($ :button {:type "button" :class (when (= :not-set mode) "on")
                                 :title "Referenced by a Source, but no value is stored or set in the environment"
                                 :on-click #(set-mode! :not-set)}
                        (str "Not set " unset-n)))
                  ($ :span.spacer)
                  ($ :span.vt-count {:role "status" :aria-live "polite"}
                     (if (= (count shown) (count all-rows))
                       (str (count all-rows) " variables")
                       (str (count shown) " of " (count all-rows)))))
               (if (empty? shown)
                 ($ :p.vt-empty
                    "No variable matches"
                    (when (seq (str/trim query)) ($ :strong (str " “" (str/trim query) "”")))
                    (when (not= :all mode) " in this filter")
                    ". "
                    ($ :button.btn.tiny {:type "button" :on-click #(do (set-query! "") (set-mode! :all))} "Clear"))
                 ($ :div.variables-table-wrap
                    ($ :table.variables-table
                       ($ :thead
                          ($ :tr
                             (for [[k label] [[:name "Variable"] [:status "Value"] [:sources "Used by (this workspace)"]]]
                               ($ :th {:key (name k)
                                       :aria-sort (if (= k (:key sort-state))
                                                    (if (= :asc (:dir sort-state)) "ascending" "descending")
                                                    "none")}
                                  ($ :button.th-sort {:type "button" :on-click #(sort-by! k)
                                                      :title (str "Sort by " (str/lower-case label))}
                                     label
                                     ($ :span.th-arrow (cond (not= k (:key sort-state)) ""
                                                             (= :asc (:dir sort-state)) "▲"
                                                             :else "▼")))))
                             ($ :th "")))
                       ($ :tbody
                          (for [{:keys [name used-by] :as row} shown
                                :let [status (row-status row)]]
                            ($ :tr {:key name}
                               ($ :td ($ :code.var-name ($ :span.var-brace "${") (highlight name terms) ($ :span.var-brace "}")))
                               ($ :td ($ :span {:class (str "var-status var-status--" (clojure.core/name status))}
                                         (status-label status)))
                               ($ :td (if (seq used-by)
                                        (for [src used-by] ($ :span.var-src {:key src} (highlight src terms)))
                                        ($ :span.var-unused "not referenced")))
                               ($ :td.var-actions
                                  ($ :button.btn.tiny
                                     {:type "button" :title (str "Copy ${" name "}")
                                      :on-click #(-> (clipboard/copy-text! (str "${" name "}"))
                                                     (.then (fn [_] (set-notice! (str "Copied ${" name "}"))))
                                                     (.catch (fn [_] (set-error! {:message "Unable to copy. Select the reference to copy it manually."}))))}
                                     "Copy")
                                  (when can-edit?
                                    ($ :button.btn.tiny {:type "button" :disabled (some? form)
                                                         :on-click #(do (set-error! nil) (set-notice! nil)
                                                                        (set-form! {:previous-name name :name name :value ""}))}
                                       "Edit"))
                                  (when (and can-edit? (:stored? row))
                                    ($ :button.btn.tiny
                                       {:type "button" :disabled (or busy? (some? form))
                                        :on-click #(do (set-error! nil) (set-notice! nil)
                                                       (api/variable-removal-plan! name
                                                         {:on-success set-rm-plan!
                                                          :on-error set-error!}))}
                                       "Remove")))))))))))))))
