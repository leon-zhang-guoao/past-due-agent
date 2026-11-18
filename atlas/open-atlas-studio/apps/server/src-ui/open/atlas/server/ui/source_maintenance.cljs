(ns open.atlas.server.ui.source-maintenance
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [open.atlas.server.ui.api :as api]
            [open.atlas.server.ui.components :refer [card notice read-state]]
            [open.atlas.common.ui.pagination :refer [pager]]
            [uix.core :as uix :refer [$ defui]]))

(defui source-actions [{:keys [id archived? revision disabled? on-changed children]}]
  (let [[busy? set-busy!] (uix/use-state false)
        [confirm? set-confirm!] (uix/use-state false)
        [error set-error!] (uix/use-state nil)
        [message set-message!] (uix/use-state nil)
        act! (fn [action]
               (set-busy! true) (set-error! nil) (set-message! nil)
               (api/request! {:method "POST" :path "/api/console/sources/lifecycle"
                              :body {:id id :action action :expected-revision revision}
                              :on-ok (fn [_] (set-busy! false) (set-confirm! false)
                                       (set-message! (if (= action :archive) "Archived" "Restored")) (on-changed))
                              :on-error (fn [e] (set-busy! false) (set-confirm! false) (set-error! e))}))]
    ($ :div.source-row-actions
       children
       ($ :button {:disabled (or busy? disabled?) :on-click #(act! (if archived? :restore :archive))}
          (if archived? "Restore" "Archive"))
       ($ :button {:disabled (or busy? disabled?) :on-click #(do (set-confirm! true) (set-error! nil))} "Delete")
       (when confirm?
         ($ :div {:role "alertdialog" :aria-label (str "Delete Source " id)}
            ($ :p (str "Delete Source " id "? This removes its registered configuration. Variables are kept. Referenced Sources cannot be deleted."))
            ($ :button {:disabled busy? :on-click #(act! :delete)} "Confirm delete")
            ($ :button {:disabled busy? :on-click #(set-confirm! false)} "Cancel delete")))
       (when busy? ($ :span {:role "status"} "Working…"))
       (when message ($ :span {:role "status"} message))
       ($ notice {:error error}))))

(defui sources-page [{:keys [revision on-changed navigation on-back on-variables]}]
  (let [[group set-group!] (uix/use-state "all")
        handled-navigation (uix/use-ref nil)
        [read-version set-read-version!] (uix/use-state 0)
        response (api/use-read "/api/console/sources" (str revision "/" read-version))
        view (:data response) groups (:groups view)
        [source-group set-source-group!] (uix/use-state (if (= group "all") "ungrouped" group))
        [editing? set-editing!] (uix/use-state false)
        [status set-status!] (uix/use-state "all")
        [q set-q!] (uix/use-state "") [page set-page!] (uix/use-state 1) [size set-size!] (uix/use-state 20)
        [picked set-picked!] (uix/use-state #{}) [destination set-destination!] (uix/use-state "ungrouped")
        [id set-id!] (uix/use-state "")
        [existing? set-existing!] (uix/use-state false)
        [kind set-kind!] (uix/use-state "sql")
        [extra set-extra!] (uix/use-state "{:jdbc_url \"\"}")
        [busy? set-busy!] (uix/use-state false)
        [unverified? set-unverified!] (uix/use-state false)
        [error set-error!] (uix/use-state nil)
        [message set-message!] (uix/use-state nil)
        [test-result set-test-result!] (uix/use-state nil)
        reload (uix/use-callback (fn [] (set-read-version! inc)) [])
        rows (vec (filter #(and (case status "active" (not (:archived? %)) "archived" (:archived? %) true)
                                (case group "all" true "ungrouped" (nil? (:group-id %)) (= group (:group-id %)))
                                (str/includes? (str/lower-case (str (:id %) " " (get-in % [:config :label]))) (str/lower-case q))) (:sources view)))
        pages (max 1 (js/Math.ceil (/ (count rows) size))) current-page (min page pages)
        shown (subvec rows (* (dec current-page) size) (min (count rows) (* current-page size)))
        disabled? (or busy? (:loading? response))
        move! (fn [] (set-busy! true) (set-error! nil)
                (api/request! {:method "POST" :path "/api/console/groups/sources"
                               :body {:source-ids (vec picked) :group-id (when-not (= destination "ungrouped") destination)}
                               :on-ok (fn [_] (set-busy! false) (set-picked! #{}) (reload) (on-changed))
                               :on-error (fn [e] (set-busy! false) (set-error! e))}))
        submit (fn [test?]
                 (set-test-result! (when test? {:state :testing}))
                 (try
                   (let [options (reader/read-string extra)]
                     (when-not (map? options) (throw (js/Error. "Source definition must be an EDN map.")))
                     (let [config (assoc options :type kind)]
                       (set-busy! true) (set-error! nil) (set-message! nil)
                       (api/request!
                        {:method "POST" :path (if test? "/api/console/sources/test" "/api/console/sources")
                         :body {:id id :config config :expected-revision (:revision view) :verify? (not unverified?)
                                :group-id (when-not (= source-group "ungrouped") source-group)}
                         :on-ok (fn [r] (set-busy! false)
                                  (if test?
                                    (set-test-result! {:state :success})
                                    (do (reload) (on-changed)
                                        (set-message! (str (if (:connected? r) "Source saved." "Source saved, connection unverified.") " Select it when publishing a new release; existing releases keep their bindings.")))))
                         :on-error (fn [e] (set-busy! false)
                                     (if test? (set-test-result! {:state :failed :error e}) (set-error! e)))})))
                   (catch :default e
                     (if test? (set-test-result! {:state :failed :error {:message (.-message e)}})
                         (set-error! {:message (.-message e)})))))]
    (uix/use-effect (fn [] (set-test-result! nil) js/undefined) [id kind extra editing?])
    (uix/use-effect
     (fn []
       (when (and view (:source-id navigation) (not= navigation @handled-navigation))
         (reset! handled-navigation navigation)
         (if-let [source (some #(when (= (:source-id navigation) (:id %)) %) (:sources view))]
           (let [config (:config source)]
             (set-group! (or (:group-id source) "ungrouped")) (set-editing! true)
             (set-existing! true) (set-id! (:id source)) (set-kind! (:type config))
             (set-source-group! (or (:group-id source) "ungrouped"))
             (set-extra! (pr-str (dissoc config :type)))
             (set-unverified! false) (set-error! nil) (set-message! nil))
           (set-error! {:message "This Source is no longer registered. The release keeps its saved binding."})))
       js/undefined)
     [view navigation])
    ($ :div.source-maintenance
       ($ :div.catalog-heading
          ($ :div ($ :h1 "Sources") ($ :p.muted "Maintain production connections independently of Graphs."))
          (when (:graph-id navigation) ($ :button {:on-click on-back} (str "Back to " (:graph-id navigation)))))
       ($ :button {:on-click on-variables} "Manage variables")
       ($ :div.group-layout {:class (when editing? "source-editor-layout")}
          ($ :aside.group-rail {:aria-label "Source groups" :hidden editing?}
             ($ :div.group-rail-heading ($ :h2 "Groups"))
             (for [[key title n] (concat [["all" "All Sources" (count (:sources view))]
                                          ["ungrouped" "Ungrouped" (:ungrouped-count view 0)]]
                                         (map (juxt :id :name :source-count) groups))]
               ($ :button.group-item {:key key :class (when (= key group) "active") :aria-pressed (= key group)
                                     :disabled busy? :on-click #(do (set-group! key) (set-page! 1) (set-picked! #{})
                                                                 (set-editing! false) (set-error! nil) (set-message! nil))}
                  ($ :span title) ($ :span.group-count n)))
             ($ :p.muted "Source groups organize connections. Graphs can explicitly bind compatible Sources from any Group."))
          ($ :div.page-stack

       ($ :div {:hidden editing?}
       ($ card {:title (or (:name (some #(when (= group (:id %)) %) groups)) (if (= group "ungrouped") "Ungrouped Sources" "All Sources")) :hint "Connection register"}
          ($ :p "Save connections here, then select them when importing a release. Changing a Group does not change saved release bindings.")
          ($ :div.catalog-tools
             ($ :input {:type "search" :aria-label "Search Sources" :placeholder "Search Source name or ID…" :value q
                         :on-change #(do (set-q! (.. % -target -value)) (set-page! 1))})
             ($ :select {:aria-label "Source status" :value status :on-change #(do (set-status! (.. % -target -value)) (set-page! 1) (set-picked! #{}))}
                ($ :option {:value "all"} "All statuses") ($ :option {:value "active"} "Active") ($ :option {:value "archived"} "Archived"))
             ($ :button {:disabled disabled? :on-click #(do (set-editing! true) (set-existing! false) (set-id! "") (set-kind! "sql")
                    (set-extra! "{:jdbc_url \"\"}") (set-unverified! false) (set-error! nil) (set-message! nil)
                    (set-source-group! (if (= group "all") "ungrouped" group)))} "New Source")
             ($ :button.quiet {:disabled disabled? :on-click reload} "Refresh Sources"))
          ($ read-state {:response response :retry reload})
          (when (seq picked)
            ($ :div.selection-toolbar
               ($ :strong (str (count picked) " selected"))
               ($ :select {:aria-label "Destination Source Group" :value destination :disabled disabled?
                            :on-change #(set-destination! (.. % -target -value))}
                  ($ :option {:value "ungrouped"} "Ungrouped")
                  (for [g groups] ($ :option {:key (:id g) :value (:id g)} (:name g))))
               ($ :button {:disabled disabled? :on-click move!} "Move Sources")
               ($ :button {:disabled disabled? :on-click #(set-picked! #{})} "Clear selection")))
          (when view
            ($ :div.table-scroll
               ($ :table.table.source-table
                  ($ :thead ($ :tr ($ :th "") ($ :th "Source") ($ :th "Type") ($ :th "Group") ($ :th "")))
                  ($ :tbody
                     (for [{:keys [id config group-id archived?]} shown]
                       ($ :tr {:key id}
                          ($ :td ($ :input {:type "checkbox" :aria-label (str "Select Source " id) :disabled disabled? :checked (contains? picked id)
                                           :on-change #(set-picked! ((if (.. % -target -checked) conj disj) picked id))}))
                          ($ :td ($ :strong (or (:label config) id)) ($ :span.row-group id) (when archived? ($ :span.row-group "Archived")))
                          ($ :td (:type config))
                          ($ :td (or (:name (some #(when (= group-id (:id %)) %) groups)) "Ungrouped"))
                          ($ :td ($ source-actions {:id id :archived? archived? :revision (:revision view) :disabled? disabled?
                                                    :on-changed #(do (set-editing! false) (reload) (on-changed))}
                             ($ :button {:disabled (or disabled? archived?) :aria-label (str "Edit Source " id)
                                    :on-click #(do (set-editing! true) (set-existing! true) (set-id! id) (set-kind! (:type config))
                                                  (set-source-group! (or group-id "ungrouped"))
                                                  (set-unverified! false)
                                                                                       (set-extra! (pr-str (dissoc config :type)))
                                                  (set-error! nil) (set-message! nil))} "Edit")))))))))
          (when (and view (empty? rows)) ($ :p.empty "No Sources in this selection. Create one or move an existing Source into this Group."))
          ($ pager {:data {:page current-page :size size :total (count rows) :pages pages} :busy? disabled? :label "Sources"
                    :on-page set-page! :on-size #(do (set-size! %) (set-page! 1))})
          ($ notice {:error error})
          (when message ($ :p {:role "status"} message))))
       ($ :div {:hidden (not editing?)}
       ($ :button {:disabled busy? :on-click #(do (set-editing! false) (set-error! nil) (set-message! nil))} "← Back to Sources")
       ($ card {:title (if existing? (str "Edit Source · " id) "New Source")}
          ($ :label.source-field "Source Group"
             ($ :select {:aria-label "Source Group" :value source-group :disabled disabled?
                          :on-change #(set-source-group! (.. % -target -value))}
                ($ :option {:value "ungrouped"} "Ungrouped")
                (for [g groups] ($ :option {:key (:id g) :value (:id g)} (:name g)))))
          ($ :label.source-field "Source ID" ($ :input {:value id :disabled (or busy? existing?) :on-change #(set-id! (.. % -target -value))}))
          ($ :label.source-field "Type" ($ :select {:value kind :disabled busy? :on-change #(set-kind! (.. % -target -value))}
                               (for [k ["sql" "api" "nifi"]] ($ :option {:key k :value k} k))))
          ($ :label.source-field "Source definition (EDN)"
             ($ :textarea {:aria-label "Source definition (EDN)" :value extra :rows 12 :disabled busy?
                           :on-change #(set-extra! (.. % -target -value))}))
          ($ :p.muted "Use environment references for secrets, for example {:username \"app\" :password \"${DB_PASSWORD}\"}. Maintain variable values on the separate Variables page.")
          ($ :label.source-field "Save without verifying connection"
             ($ :input {:type "checkbox" :checked unverified? :disabled busy?
                         :on-change #(set-unverified! (.. % -target -checked))}))
          ($ :button {:disabled (or busy? (nil? view) (str/blank? id)) :on-click #(submit false)} "Save Source")
          ($ :button.quiet {:disabled busy? :on-click #(do (set-editing! false) (set-error! nil) (set-message! nil))} "Back to Sources")
          ($ notice {:error error})
          (when message ($ :p {:role "status"} message))
          ($ :div.source-connection-test
             ($ :div.source-connection-test-actions
                ($ :button.quiet {:disabled busy? :on-click #(submit true)}
                   (if (= :testing (:state test-result)) "Testing…" "Test connection"))
                ($ :span {:role "status" :aria-live "polite"}
                   (case (:state test-result) :testing "Connecting…" :success "Connected ✓" :failed "Connection test failed" nil)))
             (when (:error test-result)
               ($ :div {:role "alert"} ($ notice {:error (:error test-result)}))))
          ))
)))))
