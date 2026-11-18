(ns open.atlas.studio.tools.impl.semantic-declarations
  (:require [open.atlas.common.dialect :as dialect]
            [open.atlas.common.semantic-protocol :as protocol]))

(def draft-tool
  {:name "semantic_draft"
   :description "Read, validate, import, save or query a Semantic Contract draft. graph-sources lists the Sources explicitly selected for this Graph; update-graph-sources replaces that selection with registered Workspace Source IDs and refuses to remove a Source still used by a Binding. resolver-sources reads saved binding configuration; configure-resolver-sources updates it. Missing deployment Sources do not block a structurally valid draft import, but block publication. Import and save take complete contract EDN text and expected_revision (0 for creation), use the same validation and workspace authorization, and never publish or activate. prepare-import ignores Source identity/configuration from the file and limits Resolver choices to the target Graph's Sources; source_additions must explicitly name any Workspace Sources being added during import. Query runs the saved revision, never unsaved text. Credentials do not belong in a contract. This does not alter legacy models. cut-artifact is available only when the host supplies a separate publishing capability; it never activates serving."
   :capability :write
   :params [{:name "binding_sources" :description "For configure-resolver-sources: Binding ID to registered compatible Source ID; saved to the existing draft." :type :map :required? false}
            {:name "default_sources" :description "For configure-resolver-sources: source kind to registered Source ID; sets EVERY binding of that kind to it, replacing existing choices (derived and static bindings are untouched). An explicit binding_sources entry for the same binding wins." :type :map :required? false}
            {:name "source_replacements" :description "For update-graph-sources: explicitly remap each old Source ID to a registered connection of the same kind, updating its bindings in the same draft save." :type :map :required? false}
            {:name "lookup" :description "Explicit SQL lookup: entity-id, label, identity-name/type, value-name/type, value-nullable?, source-id, resolver-id and sql-text. prepare-lookup returns an unsaved proposal." :type :map :required? false}
            {:name "graph" :description "Qualified Studio graph name." :type :string :required? true}
            {:name "action" :description "Draft action: read, graph-sources, update-graph-sources, validate, prepare-import, import, save, query or prepare-lookup. prepare-import validates and previews rebinding a portable Contract to the selected Graph without saving; import persists the confirmed complete Contract as a draft only. Only Semantic Contract 2.0 is readable: a legacy or 1.x document is refused with contract/version-retired and is rebuilt with the open-atlas-graph-builder skill, not upgraded here." :type :keyword :required? true :enum [:resolver-sources :configure-resolver-sources :execute :cut-artifact :read :graph-sources :update-graph-sources :validate :prepare-import :import :save :query :prepare-lookup]}
            {:name "mutation" :description "Declared operation ID; execute is GUI-only." :type :string :required? false}
            {:name "params" :description "Operation input attributes." :type :map :required? false}
            {:name "text" :description "Complete Semantic Contract document as EDN text." :type :string :required? false}
            {:name "source_mappings" :description "For import review, a map from Resolver ID to an explicitly selected Graph Source ID. Imported Source declarations are ignored and rebuilt from these reviewed mappings." :type :map :required? false}
            {:name "source_additions" :description "Workspace Source IDs explicitly added to the Graph during this import review. A Resolver cannot bind directly to another Workspace Source without this explicit addition." :type :vector :items :string :required? false}
            {:name "source_ids" :description "For update-graph-sources, the complete ordered list of registered Workspace Source IDs selected for this Graph." :type :vector :items :string :required? false}
            {:name "expected_revision" :description "Saved revision expected by this save or query." :type :integer :required? false}
            {:name "query" :description "Read-only EQL vector." :type :vector :required? false}
            {:name "entity" :description "Input attributes for the saved draft query." :type :map :required? false}
            {:name "lenient?" :description "When true, return successful sibling attributes when another resolver fails; false keeps strict Pathom behavior." :type :boolean :required? false}]
   :handler (fn [ctx args]
              (if-let [service (:semantic-service ctx)]
                (try
                  (let [result (protocol/wire-value (service (:action args) (dissoc args :action) (:provenance ctx)))]
                    ;; EDN has vector/keyword map keys that JSON cannot preserve.
                    ;; Keep the complete document as canonical text on the MCP wire.
                    (if (and (= :mcp (get-in ctx [:provenance :entrypoint])) (:document result))
                      (-> result (assoc :text (dialect/canonical-str (:document result))) (dissoc :document))
                      result))
                  (catch clojure.lang.ExceptionInfo _
                    {:findings [{:code :semantic.studio/not-permitted :severity :error :path []}]}))
                {:findings [{:code :semantic.studio/unavailable :severity :error :path []}]}))})

(def graph-import-tool
  {:name "import_semantic_graph"
   :description "Plan or apply import of a complete Semantic Contract 1.4 Graph package. The package embeds the root and exact transitive child snapshots. It never imports Source instances or credentials and never publishes or activates. Call first without apply, review targets and required Source Slots, then repeat with targets and plan_id plus apply=true."
   :capability :write
   :params [{:name "text" :description "Complete atlas-semantic-graph EDN package text." :type :string :required? true}
            {:name "digest" :description "Optional transported sha256 digest; Studio computes it for a local file when omitted." :type :string :required? false}
            {:name "root_graph" :description "Existing qualified Studio Graph that receives the portable root Graph." :type :string :required? true}
            {:name "targets" :description "Reviewed map from portable Graph ID to qualified Studio Graph target; required for apply." :type :map :required? false}
            {:name "plan_id" :description "Plan fingerprint returned by the immediately preceding plan." :type :string :required? false}
            {:name "apply" :description "False plans without writes; true applies the reviewed unchanged plan as drafts." :type :boolean :required? false}]
   :handler (fn [ctx args]
              (if-let [service (:semantic-package-service ctx)]
                (try
                  (protocol/wire-value
                   (service (if (:apply args) :apply :plan)
                            {:text (:text args) :digest (:digest args)
                             :root-graph (:root_graph args) :targets (:targets args)
                             :plan-id (:plan_id args)}
                            (:provenance ctx)))
                  (catch clojure.lang.ExceptionInfo _
                    {:findings [{:code :semantic.package/not-permitted :severity :error :path []}]}))
                {:findings [{:code :semantic.package/unavailable :severity :error :path []}]}))})

;; 8.4.27: task-oriented entry points share the existing draft service.
(def get-graph-tool
  {:name "get_graph"
   :description "Get the complete editable Semantic Contract EDN and draft revision for a Studio Graph. Requires graph editor access. Edit the returned text, then call upload_graph with this revision. This reads the draft, not the active release."
   :capability :read
   :params [{:name "graph" :description "Qualified Studio Graph name." :type :string :required? true}]
   :handler (fn [ctx args]
              ((:handler draft-tool) ctx (assoc (select-keys args [:graph]) :action :read)))})

(def upload-graph-tool
  {:name "upload_graph"
   :description "Upload a complete edited Semantic Contract EDN to an existing Studio Graph. Use get_graph first and preserve its workspace and graph IDs and Source bindings. Validates and atomically saves the draft; expected_revision must match (0 for the first save). A conflict or invalid document leaves the draft unchanged. Does not publish. On success call publish_graph with graph and apply=true to release and activate, then test through the data-plane MCP using the existing Graph token. For portable packages or cross-Graph imports use import_semantic_graph instead."
   :capability :write
   :params [{:name "graph" :description "Qualified target Studio Graph name." :type :string :required? true}
            {:name "text" :description "Complete edited Semantic Contract EDN text, not a file path or a released artifact." :type :string :required? true}
            {:name "expected_revision" :description "Draft revision returned by get_graph; 0 for the first save." :type :integer :required? true}]
   :handler (fn [ctx args]
              ((:handler draft-tool) ctx
               (assoc (select-keys args [:graph :text :expected_revision]) :action :save)))})
