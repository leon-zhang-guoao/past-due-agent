(ns open.atlas.server.ui.graph-browser
  (:require [clojure.string :as str]
            [open.atlas.server.ui.api :as api]
            [open.atlas.server.ui.components :refer [card notice findings-list read-state]]
            [open.atlas.common.ui.access :as access]
            [open.atlas.common.ui.mcp-connections :as mcp]
            [open.atlas.common.ui.source-bindings :as source-bindings]
            [open.atlas.common.ui.pagination :refer [pager]]
            [uix.core :as uix :refer [$ defui]]))

(defn- url [path params]
  (str path "?" (str/join "&" (for [[k v] params :when (some? v)]
                               (str (name k) "=" (js/encodeURIComponent (str v)))))))

(defn- use-debounced [value]
  (let [[settled set-settled!] (uix/use-state value)]
    (uix/use-effect (fn [] (let [timer (js/setTimeout #(set-settled! value) 250)]
                            #(js/clearTimeout timer))) [value])
    settled))

(defn- version-label [version] (if version (str "v" version) "Not published"))
(defn- status-label [graph]
  (cond (= :archived (:state graph)) "Archived"
        (:enabled? graph) "Serving"
        (:serving-version graph) "Paused"
        :else "Unpublished"))

(defui token-browser [{:keys [graph-id revision reload heading]}]
  (let [[q set-q!] (uix/use-state "") [state set-state!] (uix/use-state "all")
        [page set-page!] (uix/use-state 1) [size set-size!] (uix/use-state 20)
        [error set-error!] (uix/use-state nil)
        needle (use-debounced q)
        response (api/use-read (url "/api/console/tokens/page" {:graph-id graph-id :q needle :state state :page page :size size}) revision)
        data (:data response) busy? (or (:loading? response) (not= q needle))]
    ($ :section.catalog-panel
       ($ :h2 (or heading "Access tokens"))
       ($ :p.muted "Tokens work only on this instance and follow the Graph's published version.")
       ($ :div.catalog-tools
          ($ :input {:type "search" :aria-label "Search tokens" :placeholder "Search token ID or Graph…"
                      :value q :on-change #(do (set-q! (.. % -target -value)) (set-page! 1))})
          ($ :select {:aria-label "Token status" :value state
                       :on-change #(do (set-state! (.. % -target -value)) (set-page! 1))}
             ($ :option {:value "all"} "All tokens") ($ :option {:value "active"} "Active")
             ($ :option {:value "revoked"} "Revoked") ($ :option {:value "expired"} "Expired")))
       ($ notice {:error error}) ($ read-state {:response response :retry reload})
       (when (and data (not busy?))
         (if (empty? (:items data)) ($ :p.empty "No tokens match these filters.")
           ($ :div.table-scroll
              ($ access/token-list {:tokens (:items data)
                 :on-revoke (fn [id done]
                              (set-error! nil)
                              (api/request! {:method "POST" :path "/api/console/tokens/revoke" :body {:token-id id}
                                             :on-ok (fn [_] (done) (reload))
                                             :on-error (fn [e] (done) (set-error! e))}))}))))
       ($ pager {:data data :busy? busy? :label "Tokens" :on-page set-page!
                 :on-size #(do (set-size! %) (set-page! 1))}))))

(defui version-browser [{:keys [graph-id revision reload on-review disabled?]}]
  (let [[page set-page!] (uix/use-state 1) [size set-size!] (uix/use-state 20)
        [q set-q!] (uix/use-state "") needle (use-debounced q)
        response (api/use-read (url "/api/console/graphs/versions" {:graph-id graph-id :page page :size size :q needle}) revision)
        data (:data response) busy? (or (:loading? response) (not= q needle))]
    ($ :section.catalog-panel
       ($ :h2 "Version history")
       ($ :p.muted "Newest first. Review a version before publishing it; existing tokens follow the serving pointer.")
       ($ :div.catalog-tools
          ($ :input {:type "search" :aria-label "Search versions" :placeholder "Find version number…" :value q
                      :on-change #(do (set-q! (.. % -target -value)) (set-page! 1))}))
       ($ read-state {:response response :retry reload})
       (when (and data (not busy?))
         (if (empty? (:items data)) ($ :p.empty "No versions match this search.")
           ($ :div.table-scroll
              ($ :table.table.version-table
                 ($ :thead ($ :tr ($ :th "Version") ($ :th "State") ($ :th "Created") ($ :th "Created by") ($ :th "")))
                 ($ :tbody
                    (for [v (:items data)]
                      ($ :tr {:key (:version v)}
                         ($ :td ($ :code (version-label (:version v))))
                         ($ :td ($ :span {:class (if (:serving? v) "badge on" "badge")}
                                    (if (:serving? v) "Serving" "On shelf")))
                         ($ :td (or (:cut-at v) "—")) ($ :td (or (:cut-by v) "—"))
                         ($ :td.act ($ :button {:disabled disabled? :on-click #(on-review (:version v))}
                                       "Review")))))))))
       ($ pager {:data data :busy? busy? :label "Versions" :on-page set-page!
                 :on-size #(do (set-size! %) (set-page! 1))}))))

(defui publication-review [{:keys [graph-id version revision reload busy? on-cancel on-publish]}]
  (let [response (api/use-read (url "/api/console/graphs/review" {:graph-id graph-id :version version}) revision)
        r (:data response) section (uix/use-ref nil)]
    (uix/use-effect (fn [] (when @section (.scrollIntoView @section #js {:block "start"})) js/undefined) [])
    ($ :section.publication-review {:ref section :aria-label "Publication review"}
       ($ :div.catalog-heading ($ :h2 "Review publication")
          ($ :button {:disabled busy? :on-click on-cancel} "Cancel review"))
       ($ :p ($ :code graph-id))
       ($ read-state {:response response :retry reload})
       (when r
         ($ :<>
            ($ :div.release-comparison
               ($ :div ($ :span.muted "Currently published") ($ :strong (version-label (:expected-current r))))
               ($ :span.release-arrow "→")
               ($ :div ($ :span.muted "Target version") ($ :strong (version-label (:version r)))))
            ($ :dl.facts
               ($ :dt "Created") ($ :dd (or (get-in r [:target :cut-at]) "—"))
               ($ :dt "Created by") ($ :dd (or (get-in r [:target :cut-by]) "—"))
               ($ :dt "Content hash") ($ :dd.code.break-word (or (get-in r [:target :content-hash]) "—")))
            ($ findings-list {:findings (get-in r [:plan :findings])})
            ($ :p.muted "Publishing switches the serving version for this Graph. Existing tokens retain their access.")
            ($ :div.catalog-actions
               ($ :button.primary {:disabled (or busy? (not (:would-publish? r))) :on-click #(on-publish r)}
                  (if busy? "Publishing…" (str "Publish " (version-label version))))
               ($ :button {:disabled busy? :on-click reload} "Refresh readiness")))))))

(defui graph-source-view [{:keys [graph-id revision reload on-open-source]}]
  (let [[version set-version!] (uix/use-state nil) [entered set-entered!] (uix/use-state "")
        [q set-q!] (uix/use-state "") [page set-page!] (uix/use-state 1) [size set-size!] (uix/use-state 20)
        response (api/use-read (url "/api/console/graphs/sources" {:graph-id graph-id :version version}) revision)
        data (:data response)
        rows (vec (filter #(str/includes? (str/lower-case (str (:slot-id %) " " (:source-id %) " " (:label %) " " (str/join " " (:used-by %))))
                                         (str/lower-case q)) (:sources data)))
        pages (max 1 (js/Math.ceil (/ (count rows) size))) current-page (min page pages)
        shown (subvec rows (* (dec current-page) size) (min (count rows) (* current-page size)))
        choose! (fn [v] (set-version! v) (set-entered! (str v)) (set-page! 1))]
    ($ :section.catalog-panel
       ($ :div.catalog-heading
          ($ :div ($ :h2 "Graph Sources")
             ($ :p.muted "Connections and resolver usage for this release. Maintain connections in the independent Sources register."))
          ($ :button {:on-click #(on-open-source nil graph-id)} "Open Source maintenance"))
       ($ :p.muted "Release bindings are saved snapshots. Editing a registered connection does not change this version; import a new version to use a different configuration.")
       ($ :form.catalog-tools {:on-submit (fn [e] (.preventDefault e) (choose! entered))}
          ($ :button {:type "button" :disabled (or (:loading? response) (nil? (:serving-version data)))
                      :on-click #(choose! (:serving-version data))} "Published version")
          ($ :button {:type "button" :disabled (or (:loading? response) (nil? (:latest-version data)))
                      :on-click #(choose! (:latest-version data))} "Latest imported")
          ($ :input {:type "number" :min 1 :required true :aria-label "Source binding version" :placeholder "Version number"
                      :value entered :on-change #(set-entered! (.. % -target -value))})
          ($ :button {:type "submit" :disabled (:loading? response)} "Load version"))
       ($ read-state {:response response :retry reload})
       (when data
         ($ :<>
            ($ :p ($ :strong (str "Sources in v" (:version data)))
               (when (= (:version data) (:serving-version data)) ($ :span.badge.on "Published")))
            ($ :div.catalog-tools
               ($ :input {:type "search" :aria-label "Search Graph Sources" :placeholder "Search Source, slot or resolver…" :value q
                           :on-change #(do (set-q! (.. % -target -value)) (set-page! 1))}))
            ($ :details.resolver-assignments {:open true}
               ($ :summary "Resolver connection assignments")
               ($ source-bindings/resolver-bindings {:sources shown :on-open-source #(on-open-source % graph-id)}))
            (if (empty? rows) ($ :p.empty "No Sources match this release or search.")
              ($ :div.table-scroll
                 ($ :table.table.graph-source-table
                    ($ :thead ($ :tr ($ :th "Source slot") ($ :th "Production connection") ($ :th "Binding") ($ :th "Used by")))
                    ($ :tbody
                       (for [source shown]
                         ($ :tr {:key (:slot-id source)}
                            ($ :td ($ :strong (:label source)) ($ :span.row-group (str (:slot-id source) " · " (:kind source))))
                            ($ :td
                               (if (:registered? source)
                                 ($ :button.graph-link {:on-click #(on-open-source (:source-id source) graph-id)} (:source-id source))
                                 ($ :span (or (:source-id source) "Release configuration")))
                               (when (:source-label source) ($ :span.row-group (:source-label source)))
                               ($ :span.row-group
                                  (case (:registry-status source)
                                    :changed "Registered configuration changed; this release keeps its snapshot."
                                    :unavailable "No longer registered; saved binding retained."
                                    :ambiguous "Several registered connections match; original selection is unknown."
                                    :untracked "No recorded registry connection."
                                    :current (if (= :configuration-match (:origin source)) "Matched by configuration; original selection was not recorded." "Registered connection")
                                    "No external connection required")))
                            ($ :td (case (:binding-status source) :bound "Bound" :missing "Missing binding" :embedded "Embedded in release" "Not required")
                               (for [{:keys [name present?]} (:variables source) :when (not present?)]
                                 ($ :span.row-group {:key name} (str "Missing variable: " name))))
                            ($ :td (if (seq (:used-by source))
                                     ($ :details ($ :summary (str (count (:used-by source)) " resolvers / operations"))
                                        ($ :ul (for [use (:used-by source)] ($ :li {:key use} use))))
                                     "—"))))))))
            ($ pager {:data {:page current-page :size size :total (count rows) :pages pages} :label "Graph Sources"
                      :on-page set-page! :on-size #(do (set-size! %) (set-page! 1))}))))))

(defui issued-token-panel [{:keys [issued on-dismiss]}]
  (let [panel (uix/use-ref nil)]
    (uix/use-effect
     (fn []
       (when-let [element @panel]
         (.scrollIntoView element #js {:block "center"})
         (.focus element #js {:preventScroll true}))
       js/undefined)
     [(:token-id issued)])
    ($ :section.catalog-panel.secret {:ref panel :tab-index -1 :aria-label "New access token"}
       ($ :h2 "Token created")
       ($ :p "Copy the full token below into your client's Bearer token field. Do not use the Token ID.")
       ($ access/issued-secret {:secret (:token issued) :on-dismiss on-dismiss})
       ($ :p.muted "Token ID: " ($ :code (:token-id issued)))
       (when (get-in issued [:record :asserts-user?])
         ($ :p.notice "This token may name the user it reads for. Give it only to the Workbench server.")))))

(defui graph-detail [{:keys [graph-id revision reload on-back on-issued issued on-dismiss-issued groups render-import on-open-source]}]
  (let [response (api/use-read (url "/api/console/graphs/detail" {:graph-id graph-id}) revision)
        graph (:graph (or (:data response) (:previous-data response)))
        [tab set-tab!] (uix/use-state :overview) [review set-review!] (uix/use-state nil)
        [writing? set-busy!] (uix/use-state false) lock (uix/use-ref false)
        [error set-error!] (uix/use-state nil) [confirm set-confirm!] (uix/use-state "")
        [importing? set-importing!] (uix/use-state false) [move-target set-move-target!] (uix/use-state nil)
        [vouching? set-vouching!] (uix/use-state false)
        busy? (or writing? (:loading? response))
        archived? (= :archived (:state graph))
        write! (fn [path body success]
                 (when (and (not @lock) (not (:loading? response)))
                   (reset! lock true) (set-busy! true) (set-error! nil)
                   (api/request! {:method "POST" :path path :body (assoc body :graph-id graph-id)
                                  :on-ok (fn [r] (reset! lock false) (set-busy! false) (set-review! nil) (success r) (reload))
                                  :on-error (fn [e] (reset! lock false) (set-busy! false) (set-review! nil) (set-error! e))})))
        action! (fn [name body success] (write! (str "/api/console/graphs/" name) body success))]
    ($ :div.graph-detail
       ($ :button.back-link {:disabled busy? :on-click on-back} "← Back to Graphs")
       ($ :div.catalog-heading
          ($ :div
             ($ :p.muted (str "Group · " (or (:name (some #(when (= (:group-id graph) (:id %)) %) groups)) "Ungrouped")))
             ($ :h1 graph-id) (when graph ($ :span.badge (status-label graph))))
          (when graph
            ($ :div.catalog-actions
               ($ :button {:disabled (or busy? archived?) :on-click #(set-importing! (not importing?))}
                  (if importing? "Close import" "Import new version"))
               ($ :button.primary {:disabled (or busy? archived? (nil? (:latest-version graph)))
                                   :on-click #(do (set-importing! false) (set-review! (:latest-version graph)))} "Review latest release"))))
       (when writing? ($ :p.read-state {:role "status"} "Applying change…"))
       ($ notice {:error error})
       ($ read-state {:response response :retry reload})
       (when graph
         ($ :<>
            ($ :div.graph-summary
               ($ :div ($ :span "Published") ($ :strong (version-label (:serving-version graph))))
               ($ :div ($ :span "Latest imported") ($ :strong (version-label (:latest-version graph))))
               ($ :div ($ :span "Versions") ($ :strong (:version-count graph)))
               ($ :div ($ :span "Active tokens") ($ :strong (str (:active-token-count graph) " / " (:token-count graph)))))
            (when importing?
              (render-import {:expected-graph-id graph-id
                              :on-imported (fn [_] (set-importing! false) (reload))}))
            (when review
              ($ publication-review {:key review :graph-id graph-id :version review :revision revision :reload reload
                                     :busy? busy? :on-cancel #(set-review! nil)
                                     :on-publish #(action! "publish" {:version (:version %) :expected-current (:expected-current %)} (fn [_] nil))}))
            ($ :nav.detail-tabs {:aria-label "Graph sections"}
               (for [[id label] [[:overview "Overview"] [:versions "Versions"] [:sources "Sources"] [:access "Access"] [:manage "Manage"]]]
                 ($ :button {:key id :class (when (= id tab) "active") :aria-pressed (= id tab) :disabled busy?
                             :on-click #(set-tab! id)} label)))
            (case tab
              :overview
              ($ :section.catalog-panel
                 ($ :h2 "Release overview")
                 ($ :p (if (= (:latest-version graph) (:serving-version graph))
                          "The latest imported version is published."
                          "A version is available to review and publish."))
                 ($ :dl.facts
                    ($ :dt "Latest version created") ($ :dd (or (get-in response [:data :latest :cut-at]) "—"))
                    ($ :dt "Created by") ($ :dd (or (get-in response [:data :latest :cut-by]) "—"))
                    ($ :dt "EQL endpoint") ($ :dd ($ :code (str (.-origin js/location) "/eql")))
                    ($ :dt "MCP endpoint") ($ :dd ($ :code (if (:graph-uuid graph) (str (.-origin js/location) "/mcp/" (:graph-uuid graph)) "Graph UUID unavailable"))))
                 (when archived? ($ :p.muted "This Graph is archived. Restore it in Manage before publishing or creating tokens.")))
              :versions ($ version-browser {:graph-id graph-id :revision revision :reload reload
                                             :on-review set-review! :disabled? (or busy? archived?)})
              :sources ($ graph-source-view {:graph-id graph-id :revision revision :reload reload :on-open-source on-open-source})
              :access ($ :<>
                         ($ :section.catalog-panel {:aria-label "MCP connections"}
                            ($ :h2 "MCP connections")
                            ($ :p "Graph UUID: " ($ :code (or (:graph-uuid graph) "Unavailable in this release")))
                            ($ mcp/endpoint-row {:title "Server MCP" :url (when (:graph-uuid graph) (str (.-origin js/location) "/mcp/" (:graph-uuid graph)))
                                                 :credential "Graph token"
                                                 :description "HTTP transport. Use an access token for this Graph."})
                            ($ mcp/endpoint-row {:title "Docker MCP" :url (when (:graph-uuid graph) (let [u (js/URL. (.-origin js/location))]
                                                                                         (set! (.-hostname u) "host.docker.internal")
                                                                                         (str (.-origin u) "/mcp/" (:graph-uuid graph))))
                                                 :credential "Same graph token"
                                                 :description "For a Docker client connecting to the Server on this host. For a remote Server, use the Server URL instead. The host port must be reachable from the container."})
                            ($ :p "Authentication header: " ($ :code "Authorization: Bearer <GRAPH_TOKEN>"))
                            ($ :p.muted "The URL identifies this Graph. Use a matching Graph token in place of <GRAPH_TOKEN>.")
                            (when-not (:enabled? graph) ($ :p.notice "Publish this Graph and enable serving before connecting or creating an access token.")))
                         ($ :label.token-vouching
                            ($ :input {:type "checkbox" :checked vouching? :disabled busy?
                                       :on-change #(set-vouching! (.. % -target -checked))})
                            " Workbench service token: may name the signed-in user it reads for")
                         ($ :p.muted "Only for the Workbench server's read-only token. Agent and other tokens must leave this off; a token without it cannot name a user.")
                         ($ :div.catalog-actions
                            ($ :button.primary {:disabled (or busy? archived? (not (:enabled? graph)))
                                                :on-click #(write! "/api/console/tokens/issue"
                                                                   (if vouching? {:asserts-user? true} {})
                                                                   (fn [r] (set-vouching! false) (on-issued r)))}
                               (if busy? "Creating…" "Create access token")))
                         (when (= graph-id (:graph-id issued))
                           ($ issued-token-panel {:issued issued :on-dismiss on-dismiss-issued}))
                         ($ token-browser {:graph-id graph-id :revision revision :reload reload}))
              :manage
              ($ :section.catalog-panel
                 ($ :h2 "Graph management")
                 ($ :div.management-row
                    ($ :div ($ :h3 "Group") ($ :p.muted "Organization only; access and published addresses stay the same."))
                    ($ :div.catalog-actions
                       ($ :select {:aria-label "Move Graph to Group" :disabled busy?
                                    :value (or move-target (:group-id graph) "ungrouped")
                                    :on-change #(set-move-target! (.. % -target -value))}
                          ($ :option {:value "ungrouped"} "Ungrouped")
                          (for [g groups] ($ :option {:key (:id g) :value (:id g)} (:name g))))
                       ($ :button {:disabled busy?
                                   :on-click #(write! "/api/console/groups/assign"
                                              {:graph-ids [graph-id] :group-id (let [id (or move-target (:group-id graph))] (when-not (= id "ungrouped") id))}
                                              (fn [_] (set-move-target! nil)))} "Move Graph")))
                 ($ :div.management-row
                    ($ :div ($ :h3 "Serving") ($ :p.muted "Pausing blocks queries immediately without revoking tokens."))
                    ($ :button {:disabled (or busy? (nil? (:serving-version graph)))
                                :on-click #(action! "enabled" {:enabled? (not (:enabled? graph))} (fn [_] nil))}
                       (if (:enabled? graph) "Pause serving" "Resume serving")))
                 ($ :div.management-row
                    ($ :div ($ :h3 (if archived? "Restore Graph" "Archive Graph"))
                       ($ :p.muted "Archiving preserves existing service, versions and tokens. Restore before publishing or issuing new tokens."))
                    ($ :button {:disabled busy? :on-click #(action! (if archived? "restore" "archive") {} (fn [_] (set-confirm! "")))}
                       (if archived? "Restore Graph" "Archive Graph")))
                 ($ :div.delete-zone
                    ($ :h3 "Permanent deletion")
                    ($ :p "Stops serving, revokes active tokens and deletes local versions. References from other Graphs block deletion. Sources and audit records remain.")
                    (if-not archived? ($ :p.muted "Archive this Graph before deleting it.")
                      ($ :<>
                         ($ :label "Type " ($ :code graph-id) " to confirm. This cannot be undone."
                            ($ :input {:aria-label "Confirm Graph deletion" :auto-complete "off" :value confirm :disabled busy?
                                        :on-change #(set-confirm! (.. % -target -value))}))
                         ($ :button.danger {:disabled (or busy? (not= confirm graph-id))
                                            :on-click #(action! "delete" {:confirm-name confirm} (fn [_] (on-back)))} "Delete permanently")))))))))))

(defui graphs-page [{:keys [revision reload on-issued issued on-dismiss-issued on-counts render-import navigation on-open-source]}]
  (let [[q set-q!] (uix/use-state "") [state set-state!] (uix/use-state "all")
        [sort set-sort!] (uix/use-state "name") [direction set-direction!] (uix/use-state "asc")
        [page set-page!] (uix/use-state 1) [size set-size!] (uix/use-state 20)
        [group set-group!] (uix/use-state "all") [picked set-picked!] (uix/use-state #{})
        [destination set-destination!] (uix/use-state "ungrouped")
        [editing set-editing!] (uix/use-state nil) [group-name set-group-name!] (uix/use-state "")
        [saving? set-saving!] (uix/use-state false) [error set-error!] (uix/use-state nil)
        [selected set-selected!] (uix/use-state nil) [importing? set-importing!] (uix/use-state false)
        needle (use-debounced q)
        response (api/use-read (url "/api/console/graphs/page" {:q needle :state state :group group :sort sort :direction direction :page page :size size}) revision)
        data (:data response) group-data (or data (:previous-data response))
        groups (:groups group-data) current-group (some #(when (= group (:id %)) %) groups)
        busy? (or (:loading? response) (not= q needle))
        choose-group! (fn [id] (set-group! id) (set-page! 1) (set-picked! #{}) (set-editing! nil) (set-error! nil))
        group-write! (fn [action body success]
                       (set-saving! true) (set-error! nil)
                       (api/request! {:method "POST" :path (str "/api/console/groups/" action) :body body
                                      :on-ok (fn [r] (set-saving! false) (success r) (reload))
                                      :on-error (fn [e] (set-saving! false) (set-error! e))}))]
    (uix/use-effect
     (fn []
       (when navigation (set-selected! (:graph-id navigation)))
       js/undefined)
     [navigation])
    (uix/use-effect (fn [] (when-let [counts (:counts data)] (on-counts counts)) js/undefined) [data on-counts])
    ($ :div.graph-browser
       (if selected
         ($ graph-detail {:key selected :graph-id selected :revision revision :reload reload
                          :groups groups :render-import render-import :on-open-source on-open-source
                          :on-issued on-issued :issued issued :on-dismiss-issued on-dismiss-issued :on-back #(set-selected! nil)})
         ($ :<>
            ($ :div.catalog-heading
               ($ :div ($ :h1 "Groups & Graphs") ($ :p.muted "Choose a Group, then manage its Graphs, releases and access."))
               ($ :div.catalog-actions ($ :button {:on-click reload} "Refresh")
                  ($ :button.primary {:on-click #(set-importing! (not importing?))} (if importing? "Close import" "Import Graph"))))
            (when importing?
              ($ :div
                 ($ :p.muted (str "Import into " (or (:name current-group) "Ungrouped") "."))
                 (render-import {:group-id (:id current-group)
                                 :on-imported (fn [r] (set-importing! false) (set-selected! (:graph-id r)) (reload))})))
            ($ :div.group-layout
               ($ :aside.group-rail {:aria-label "Graph groups"}
                  ($ :div.group-rail-heading ($ :h2 "Groups")
                     ($ :button.quiet {:aria-label "Create Group" :on-click #(do (set-editing! {}) (set-group-name! "") (set-error! nil))} "+"))
                  (for [[id label count] (concat [["all" "All Graphs" (get-in group-data [:counts :graphs] 0)]
                                                  ["ungrouped" "Ungrouped" (:ungrouped-count group-data 0)]]
                                                 (map (juxt :id :name :graph-count) groups))]
                    ($ :button.group-item {:key id :class (when (= group id) "active") :aria-pressed (= group id)
                                          :disabled saving? :on-click #(choose-group! id)}
                       ($ :span label) ($ :span.group-count count)))
                  ($ :p.muted "Groups organize Graphs and Sources. Tokens follow their Graph. Grouping does not grant access."))
               ($ :div.group-content
                  ($ :div.group-title
                     ($ :h2 (or (:name current-group) (if (= group "ungrouped") "Ungrouped" "All Graphs")))
                     (when current-group
                       ($ :button.quiet {:on-click #(do (set-editing! current-group) (set-group-name! (:name current-group)) (set-error! nil))} "Manage Group")))
                  ($ notice {:error error})
                  (when editing
                    ($ :form.group-editor {:on-submit (fn [e] (.preventDefault e)
                                                       (group-write! "save" {:id (:id editing) :name group-name}
                                                        (fn [r] (choose-group! (get-in r [:group :id])))))}
                       ($ :h3 (if (:id editing) "Manage Group" "Create Group"))
                       ($ :label "Group name" ($ :input {:aria-label "Group name" :value group-name :max-length 80 :required true :disabled saving?
                                                         :on-change #(set-group-name! (.. % -target -value))}))
                       ($ :div.catalog-actions
                          ($ :button.primary {:type "submit" :disabled (or saving? (str/blank? group-name))} "Save Group")
                          ($ :button {:type "button" :disabled saving? :on-click #(set-editing! nil)} "Cancel")
                          (when (:id editing)
                            ($ :button.danger {:type "button" :disabled saving?
                                               :on-click #(group-write! "delete" {:id (:id editing)} (fn [_] (choose-group! "ungrouped")))} "Remove Group")))
                       (when (:id editing) ($ :p.muted "Removing this Group moves its Graphs and Sources to Ungrouped. Versions, tokens and saved bindings remain."))))
                  ($ :section.card.graph-register
                     ($ :div.catalog-tools
                        ($ :input {:type "search" :aria-label "Search Graphs" :placeholder "Search Graph name…"
                                    :value q :on-change #(do (set-q! (.. % -target -value)) (set-page! 1))})
                        ($ :select {:aria-label "Graph status" :value state :on-change #(do (set-state! (.. % -target -value)) (set-page! 1))}
                           (for [[value label] [["all" "All statuses"] ["active" "Active"] ["serving" "Serving"] ["unpublished" "Unpublished"] ["paused" "Paused"] ["archived" "Archived"]]]
                             ($ :option {:key value :value value} label)))
                        ($ :select {:aria-label "Sort Graphs" :value sort :on-change #(do (set-sort! (.. % -target -value)) (set-page! 1))}
                           ($ :option {:value "name"} "Name") ($ :option {:value "tokens"} "Active tokens") ($ :option {:value "versions"} "Version count"))
                        ($ :button {:aria-label "Reverse Graph order" :on-click #(do (set-direction! (if (= direction "asc") "desc" "asc")) (set-page! 1))}
                           (if (= direction "asc") "↑" "↓")))
                     (when (seq picked)
                       ($ :div.selection-toolbar
                          ($ :strong (str (count picked) " selected"))
                          ($ :select {:aria-label "Destination Group" :value destination :disabled saving?
                                       :on-change #(set-destination! (.. % -target -value))}
                             ($ :option {:value "ungrouped"} "Ungrouped")
                             (for [g groups] ($ :option {:key (:id g) :value (:id g)} (:name g))))
                          ($ :button {:disabled saving? :on-click #(group-write! "assign" {:graph-ids (vec picked) :group-id (when-not (= destination "ungrouped") destination)}
                                                                               (fn [_] (set-picked! #{})))} "Move selected")
                          ($ :button.quiet {:disabled saving? :on-click #(set-picked! #{})} "Clear selection")))
                     ($ read-state {:response response :retry reload})
                     (when (and data (not busy?))
                       (if (empty? (:items data))
                         ($ :div.empty.catalog-empty ($ :h2 "No matching Graphs")
                            ($ :p "Adjust the search or status filter, or import a Studio release into this Group."))
                         ($ :div.table-scroll
                            ($ :table.table.graph-table
                               ($ :thead ($ :tr
                                  ($ :th ($ :input {:type "checkbox" :aria-label "Select this page" :disabled saving?
                                                    :checked (every? #(contains? picked (:graph-id %)) (:items data))
                                                    :on-change #(if (.. % -target -checked)
                                                                  (set-picked! (into picked (map :graph-id (:items data))))
                                                                  (set-picked! (apply disj picked (map :graph-id (:items data)))))}))
                                  ($ :th "Graph") ($ :th "State") ($ :th "Published") ($ :th "Latest") ($ :th "Versions") ($ :th "Tokens")))
                               ($ :tbody
                                  (for [g (:items data)]
                                    ($ :tr {:key (:graph-id g)}
                                       ($ :td ($ :input {:type "checkbox" :aria-label (str "Select " (:graph-id g)) :disabled saving?
                                                         :checked (contains? picked (:graph-id g))
                                                         :on-change #(set-picked! ((if (.. % -target -checked) conj disj) picked (:graph-id g)))}))
                                       ($ :td ($ :button.graph-link {:on-click #(do (set-selected! (:graph-id g)))} (:graph-id g))
                                          (when (= group "all") ($ :span.row-group (or (:name (some (fn [x] (when (= (:id x) (:group-id g)) x)) groups)) "Ungrouped"))))
                                       ($ :td ($ :span {:class (if (:enabled? g) "badge on" "badge")} (status-label g)))
                                       ($ :td (version-label (:serving-version g)))
                                       ($ :td (version-label (:latest-version g)))
                                       ($ :td (:version-count g))
                                       ($ :td {:title "Active / all tokens"} (str (:active-token-count g) " / " (:token-count g))))))))))
                     ($ pager {:data data :busy? busy? :label "Graphs" :on-page set-page!
                               :on-size #(do (set-size! %) (set-page! 1))})))))))))
