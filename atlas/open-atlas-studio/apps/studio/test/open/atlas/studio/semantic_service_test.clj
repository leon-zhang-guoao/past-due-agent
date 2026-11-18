(ns open.atlas.studio.semantic-service-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [open.atlas.common.semantic-artifact :as artifact]
            [open.atlas.common.semantic-contract :as contract]
            [open.atlas.runtime.env :as env]
            [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.tools :as tools]
            [open.atlas.studio.semantic-service :as semantic]))

(deftest source-compatibility-understands-structured-checker-results
  (doseq [[result expected] [[true true] [{:findings []} true]
                           [{:findings '()} true] [false false] [nil false]
                           [{} false] [{:findings nil} false]
                           [{:findings {}} false]
                           [{:findings [{:code :api/invalid :severity :error :path []}]} false]]]
    (with-redefs [semantic/checkers {"api" (constantly result)}]
      (is (= expected (#'semantic/binding-compatible? "api" {})))))
  (with-redefs [semantic/checkers {"api" (fn [_] (throw (ex-info "bad checker" {})))}]
    (is (false? (#'semantic/binding-compatible? "api" {})))))

(deftest a-new-graph-reads-as-a-complete-v2-contract
  (let [document (semantic/empty-document "w" "graph-id" "New graph")]
    (is (= {:contract/family "atlas-semantic" :contract/version "2.0.0"}
           (:contract document)))
    (is (= [] (get-in document [:graph :graph/terms])))
    (is (= {:entities {} :attributes {} :terms {} :units {}
            :aliases [] :equivalences [] :references []}
           (:vocabulary document)))
    (is (= {:entities {} :resolvers {} :operations {} :terms {}}
           (:ownership document)))
    (is (= #{:semantic/core-v1 :graph/single-owner-v1
             :deployment/source-slot-v1 :artifact/embedded-closure-v1}
           (get-in document [:publication :required-capabilities])))
    (is (empty? (:findings (contract/validate-document document semantic/checkers))))))

(deftest guide-contract-import-reopen-and-query
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "guide-owner"}
        caller {:workspace "guide" :principal principal}
        url (str "jdbc:sqlite:file:" (random-uuid) "?mode=memory&cache=shared")
        service (semantic/service conn caller (fn [_ source] source))
        ctx {:scope #{:read :write} :semantic-service service
             :provenance {:entrypoint :mcp :actor principal}}
        registry (tools/registry #{:read :write})
        call #(tools/call registry ctx "semantic_draft" (assoc % :graph "guide/orders"))]
    (try
      (is (empty? (:findings (graph/register-workspace!
                              conn {:name "guide" :members [{:principal principal :role :owner}]}))))
      (is (empty? (:findings (graph/register-graph!
                              conn {:workspace "guide" :name "orders"
                                    :created-at "2026-09-09T00:00:00Z" :members {}}))))
      (with-open [db (java.sql.DriverManager/getConnection url)]
        (jdbc/execute! db ["CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL, from_acct_no TEXT, status TEXT NOT NULL, total INTEGER NOT NULL)"])
        (jdbc/execute! db ["INSERT INTO orders VALUES (1001, 77, 'V-001', 'PAID', 125)"])
        (jdbc/execute! db ["CREATE TABLE part (sku_No TEXT PRIMARY KEY, description TEXT)"])
        (jdbc/execute! db ["INSERT INTO part VALUES ('SKU-1', 'Demo part')"])
        (is (empty? (:findings
                     (pool/execute-action conn {:action :create :type :source :id "orders-db"
                                                :workspace "guide" :expected_revision 0
                                                :entry {:type "sql" :label "Orders DB" :jdbc_url url}}))))
        (let [graph-id (get-in (edn/read-string (:text (call {:action :read}))) [:graph :graph/id])
              sample (-> (slurp "../../docs/guides/examples/orders-semantic-contract-2.0.edn")
                         (str/replace "REPLACE-WITH-WORKSPACE-ID" "guide")
                         (str/replace "REPLACE-WITH-GRAPH-ID" graph-id))
              imported (call {:action :import :expected_revision 0 :text sample
                              :source_additions ["orders-db"]})
              reopened (call {:action :read})
              reopened-document (edn/read-string (:text reopened))
              examples (get-in reopened-document [:graph :graph/query-examples])]
          (is (true? (:imported? imported)) (pr-str imported))
          (is (= 1 (:revision imported)))
          (is (= (edn/read-string (:text imported)) reopened-document)
              "the imported revision is persisted")
          (is (= "2.0.0" (get-in reopened-document [:contract :contract/version])))
          (is (= 3 (count examples)))
          (is (= "common/vend_no"
                 (get-in reopened-document [:vocabulary :attributes :demo.order/from_acct_no :attribute/term])))
          (is (= "common/sku_no"
                 (get-in reopened-document [:vocabulary :attributes :demo.part/sku_no :attribute/term])))
          (is (= "common/sku_no"
                 (get-in reopened-document [:vocabulary :attributes :demo.order_detail/sku_no :attribute/term])))
          (doseq [{:example/keys [id eql entity]} examples]
            (let [answer (call {:action :query :expected_revision 1
                                :entity entity :query (edn/read-string eql)})]
              (is (= :ok (get-in answer [:response :engine/status]))
                  (str id " failed: " (pr-str answer)))
              (is (seq (get-in answer [:response :engine/data])) id)))
          (let [part (call {:action :query :expected_revision 1
                            :entity {:demo.part/sku_no "SKU-1"}
                            :query [:demo.part/description]})]
            (is (= "Demo part" (get-in part [:response :engine/data :demo.part/description]))
                "the normalized semantic sku_no maps to the physical sku_No column"))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest published-content-edn-is-refused-with-a-visible-format-answer
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "owner"}
        svc (semantic/service conn {:workspace "w" :principal principal} (fn [_ source] source))
        provenance {:entrypoint :ui :actor principal}]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (graph/register-graph! conn {:workspace "w" :name "g" :created-at "2026-09-11T00:00:00Z" :members {}})
      (pool/execute-action conn {:action :create :type :source :id "workspace-db" :workspace "w"
                                 :expected_revision 0
                                 :entry {:type "sql" :label "Workspace DB"
                                         :jdbc_url "jdbc:sqlite::memory:"}})
      (let [content (pr-str {:artifact/format-version 1
                             :objects {:attributes {:sample.item/id {:type :integer :identity? true}}}
                             :references []})
            result (svc :import {:graph "w/g" :expected_revision 0 :text content} provenance)
            reopened (svc :read {:graph "w/g"} provenance)]
        (is (= :semantic.import/published-content-not-contract
               (get-in result [:findings 0 :code])))
        (is (re-find #"legacy published content.edn"
                     (get-in result [:findings 0 :message])))
        (is (zero? (:revision reopened)) "a refused legacy artifact does not modify the Graph draft"))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest a-historical-contract-version-is-refused-as-retired-on-save-and-import
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "owner"}
        svc (semantic/service conn {:workspace "w" :principal principal} (fn [_ source] source))
        provenance {:entrypoint :ui :actor principal}]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (graph/register-graph! conn {:workspace "w" :name "g"
                                   :created-at "2026-09-11T00:00:00Z" :members {}})
      (let [current (:document (svc :read {:graph "w/g"} provenance))
            old (-> current
                    (assoc-in [:contract :contract/version] "1.3.0")
                    (update :graph dissoc :graph/terms)
                    (update :ownership dissoc :terms)
                    (update-in [:publication :required-capabilities]
                               disj :graph/single-owner-v1 :deployment/source-slot-v1
                               :artifact/embedded-closure-v1))
            save (svc :save {:graph "w/g" :expected_revision 0 :text (pr-str old)} provenance)
            import (svc :prepare-import {:graph "w/g" :text (pr-str old)} provenance)
            reopened (svc :read {:graph "w/g"} provenance)]
        ;; Task book «contract-upgrade-and-retirement» PR1 (owner 2026-09-19):
        ;; the 1.x readers are retired, so both doors answer with the
        ;; retirement finding and point at the skill that rebuilds the graph.
        (is (= :contract/version-retired (get-in save [:findings 0 :code])))
        (is (= :contract/version-retired (get-in import [:findings 0 :code])))
        (is (re-find #"open-atlas-graph-builder" (get-in import [:findings 0 :message])))
        (is (zero? (:revision reopened)))
        (is (= "2.0.0" (get-in reopened [:document :contract :contract/version]))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest ai-contract-example-imports-and-persists-non-empty-graph-content
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "owner"}
        svc (semantic/service conn {:workspace "w" :principal principal} (fn [_ source] source))
        provenance {:entrypoint :ui :actor principal}]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (graph/register-graph! conn {:workspace "w" :name "g" :created-at "2026-09-11T00:00:00Z" :members {}})
      (pool/execute-action conn {:action :create :type :source :id "workspace-db" :workspace "w"
                                 :expected_revision 0
                                 :entry {:type "sql" :label "Workspace DB"
                                         :jdbc_url "jdbc:sqlite::memory:"}})
      (let [graph-id (get-in (svc :read {:graph "w/g"} provenance) [:document :graph :graph/id])
            text (-> (slurp "../../docs/guides/examples/ai-semantic-contract-example-2.0.edn")
                     (str/replace "REPLACE-WITH-WORKSPACE-ID" "w")
                     (str/replace "REPLACE-WITH-GRAPH-ID" graph-id))
            imported (svc :import {:graph "w/g" :expected_revision 0 :text text
                                   :source_additions ["workspace-db"]} provenance)
            reopened (:document (svc :read {:graph "w/g"} provenance))]
        (is (true? (:imported? imported)) (pr-str imported))
        (is (= 1 (:revision imported)))
        (is (= 2 (count (get-in reopened [:vocabulary :entities]))))
        (is (= 6 (count (get-in reopened [:vocabulary :attributes]))))
        (is (= 2 (count (get-in reopened [:execution :resolvers]))))
        (is (= 2 (count (get-in reopened [:execution :bindings]))))
        (is (= 1 (count (get-in reopened [:vocabulary :aliases])))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest graph-sources-are-explicit-and-bound-before-import
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "owner"}
        svc (semantic/service conn {:workspace "w" :principal principal} (fn [_ source] source))
        provenance {:entrypoint :ui :actor principal}]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (graph/register-graph! conn {:workspace "w" :name "g" :created-at "2026-09-11T00:00:00Z" :members {}})
      (doseq [id ["alpha-db" "beta-db"]]
        (pool/execute-action conn {:action :create :type :source :id id :workspace "w"
                                   :expected_revision 0
                                   :entry {:type "sql" :label (str "Label " id)
                                           :jdbc_url "jdbc:sqlite::memory:"}}))
      (let [raw (slurp "../../docs/guides/examples/ai-semantic-contract-example-2.0.edn")
            empty-plan (svc :prepare-import {:graph "w/g" :text raw} provenance)
            added (svc :update-graph-sources {:graph "w/g" :expected_revision 0
                                               :source_ids ["alpha-db"]} provenance)
            view (svc :graph-sources {:graph "w/g"} provenance)
            graph-plan (svc :prepare-import {:graph "w/g" :text raw} provenance)
            graph-external (svc :prepare-import {:graph "w/g" :text raw
                                                  :source_mappings {"user-by-id" "beta-db"}}
                                         provenance)
            explicitly-added (svc :prepare-import {:graph "w/g" :text raw
                                                     :source_additions ["beta-db"]
                                                     :source_mappings {"user-by-id" "beta-db"}}
                                            provenance)
            imported (svc :import {:graph "w/g" :expected_revision 1 :text raw} provenance)
            blocked-removal (svc :update-graph-sources {:graph "w/g" :expected_revision 2
                                                         :source_ids []} provenance)]
        (is (false? (:source-mapping-ready? empty-plan)))
        (is (= :semantic.import/source-selection-required
               (get-in empty-plan [:source-findings 0 :code])))
        (is (true? (:updated? added)) (pr-str added))
        (is (= ["alpha-db"] (mapv :id (:graph-sources view))))
        (is (= ["beta-db"] (mapv :id (:workspace-sources view))))
        (is (every? #(= ["alpha-db"] (mapv :id (:options %)))
                    (:resolver-sources graph-plan)))
        (is (= :semantic.import/source-not-added
               (get-in graph-external [:source-findings 0 :code])))
        (is (true? (:source-mapping-ready? explicitly-added)) (pr-str explicitly-added))
        (is (= "beta-db" (get-in explicitly-added [:source-mappings "user-by-id"])))
        (is (true? (:imported? imported)) (pr-str imported))
        (is (= :semantic.graph-source/source-in-use
               (get-in blocked-removal [:findings 0 :code]))
            "a Graph Source cannot be removed while a Resolver Binding uses it"))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest portable-ai-example-plans-target-confirmation-then-imports-new-graph
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "owner"}
        svc (semantic/service conn {:workspace "w" :principal principal} (fn [_ source] source))
        registry (tools/registry #{:read :write})
        ctx {:scope #{:read :write}
             :semantic-service svc
             :provenance {:entrypoint :ui :actor principal}}
        provenance {:entrypoint :ui :actor principal}
        raw-example (slurp "../../docs/guides/examples/ai-semantic-contract-example-2.0.edn")]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (graph/register-graph! conn {:workspace "w" :name "brand-new"
                                   :created-at "2026-09-11T00:00:00Z" :members {}})
      (pool/execute-action conn {:action :create :type :source :id "workspace-db" :workspace "w"
                                 :expected_revision 0
                                 :entry {:type "sql" :label "Workspace DB"
                                         :jdbc_url "jdbc:sqlite::memory:"}})
      (let [before (svc :read {:graph "w/brand-new"} provenance)
            target-id (get-in before [:document :graph :graph/id])
            ;; Exercise the declared tool boundary used by the browser. Calling
            ;; the service directly would miss a stale action enum.
            planned (tools/call registry ctx "semantic_draft"
                                {:graph "w/brand-new" :action :prepare-import
                                 :text raw-example})
            still-empty (svc :read {:graph "w/brand-new"} provenance)
            imported (svc :import {:graph "w/brand-new" :expected_revision 0
                                   :text raw-example
                                   :source_additions ["workspace-db"]
                                   :source_mappings {"user-by-id" "workspace-db"
                                                     "posts-for-user" "workspace-db"}} provenance)
            reopened (svc :read {:graph "w/brand-new"} provenance)]
        (is (empty? (:findings planned)) (pr-str planned))
        (is (true? (:proposal? planned)))
        (is (= {:workspace "REPLACE-WITH-WORKSPACE-ID"
                :graph "REPLACE-WITH-GRAPH-ID"}
               (:source-context planned)))
        (is (= {:workspace "w" :graph target-id} (:target-context planned)))
        (is (zero? (:revision still-empty)) "planning and target preview never save")
        (is (true? (:imported? imported)) (pr-str imported))
        (is (= 1 (:revision reopened)))
        (is (= target-id (get-in reopened [:document :graph :graph/id])))
        (is (= "w" (get-in reopened [:document :workspace :workspace/id])))
        (is (= 2 (count (get-in reopened [:document :vocabulary :entities]))))
        (is (= 6 (count (get-in reopened [:document :vocabulary :attributes]))))
        (is (= 2 (count (get-in reopened [:document :execution :resolvers]))))
        (is (= 2 (count (get-in reopened [:document :execution :bindings])))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest import-source-review-ignores-file-sources-and-maps-each-resolver
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "owner"}
        resolved (atom [])
        svc (semantic/service conn {:workspace "w" :principal principal}
                              (fn [id source] (swap! resolved conj id) source))
        provenance {:entrypoint :ui :actor principal}]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (graph/register-graph! conn {:workspace "w" :name "g" :created-at "2026-09-11T00:00:00Z" :members {}})
      (doseq [id ["alpha-db" "beta-db"]]
        (is (empty? (:findings
                     (pool/execute-action
                      conn {:action :create :type :source :id id :workspace "w"
                            :expected_revision 0
                            :entry {:type "sql" :label (str "Label " id)
                                    :jdbc_url "jdbc:sqlite::memory:"}})))))
      (let [graph-id (get-in (svc :read {:graph "w/g"} provenance) [:document :graph :graph/id])
            base (-> (walk/postwalk-replace
                      {"REPLACE-WITH-WORKSPACE-ID" "w"
                       "REPLACE-WITH-GRAPH-ID" graph-id}
                      (edn/read-string
                       (slurp "../../docs/guides/examples/ai-semantic-contract-example-2.0.edn")))
                     (assoc-in [:execution :sources]
                               {"beta-db" {:source/kind "sql" :source/effects #{:read}}})
                     (update-in [:execution :bindings]
                                #(update-vals % (fn [binding]
                                                  (assoc binding :binding/source "beta-db")))))
            saved (svc :save {:graph "w/g" :expected_revision 0 :text (pr-str base)} provenance)
            external (-> base
                         (assoc-in [:execution :sources]
                                   {"external-db" {:source/kind "sql" :source/effects #{:read}
                                                   ;; Deliberately invalid Contract content: import must discard it.
                                                   :jdbc_url "jdbc:must-not-be-imported"}})
                         (update-in [:execution :bindings]
                                    #(update-vals % (fn [binding]
                                                      (assoc binding :binding/source "external-db")))))
            text (pr-str external)
            proposed (svc :prepare-import {:graph "w/g" :text text} provenance)
            mappings {"user-by-id" "alpha-db" "posts-for-user" "beta-db"}
            imported (svc :import {:graph "w/g" :expected_revision 1 :text text
                                   :source_additions ["alpha-db"]
                                   :source_mappings mappings} provenance)
            document (:document imported)
            bad (svc :import {:graph "w/g" :expected_revision 2 :text text
                              :source_mappings {"user-by-id" "missing-db"
                                                "posts-for-user" "beta-db"}} provenance)
            reopened (svc :read {:graph "w/g"} provenance)
            assessment (semantic/publishability (pool/db conn) "w" graph-id
                                                 (fn [id source]
                                                   (swap! resolved conj id) source))]
        (is (= 1 (:revision saved)))
        (is (true? (:proposal? proposed)))
        (is (true? (:source-mapping-ready? proposed)) (pr-str proposed))
        (is (= {"user-by-id" "beta-db" "posts-for-user" "beta-db"}
               (:source-mappings proposed))
            "the target Graph's existing Source wins; the file's Source is ignored")
        (is (every? #(= ["beta-db"] (mapv :id (:options %)))
                    (:resolver-sources proposed)))
        (is (true? (:imported? imported)) (pr-str imported))
        (is (= #{"alpha-db" "beta-db"} (set (keys (get-in document [:execution :sources])))))
        (is (nil? (get-in document [:execution :sources "external-db"])))
        (is (every? #(= #{:source/kind :source/effects} (set (keys %)))
                    (vals (get-in document [:execution :sources])))
            "file connection configuration is never persisted")
        (is (= "alpha-db" (get-in document [:execution :bindings "user-by-id-sql" :binding/source])))
        (is (= "beta-db" (get-in document [:execution :bindings "posts-for-user-sql" :binding/source])))
        (is (= :semantic.import/source-incompatible (get-in bad [:findings 0 :code])))
        (is (= 2 (:revision reopened)) "a rejected remap does not overwrite the imported draft")
        (is (:would-cut? assessment) (pr-str assessment))
        (is (= #{"alpha-db" "beta-db"} (set @resolved))
            "publication resolves the reviewed target Sources"))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest import-source-review-rebuilds-missing-sources-and-keeps-static-local
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "owner"}
        svc (semantic/service conn {:workspace "w" :principal principal} (fn [_ source] source))
        provenance {:entrypoint :ui :actor principal}]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (graph/register-graph! conn {:workspace "w" :name "g" :created-at "2026-09-11T00:00:00Z" :members {}})
      (pool/execute-action conn {:action :create :type :source :id "workspace-db" :workspace "w"
                                 :expected_revision 0
                                 :entry {:type "sql" :label "Workspace DB"
                                         :jdbc_url "jdbc:sqlite::memory:"}})
      (let [graph-id (get-in (svc :read {:graph "w/g"} provenance) [:document :graph :graph/id])
            document (-> (walk/postwalk-replace
                          {"REPLACE-WITH-WORKSPACE-ID" "w"
                           "REPLACE-WITH-GRAPH-ID" graph-id}
                          (edn/read-string
                           (slurp "../../docs/guides/examples/ai-semantic-contract-example-2.0.edn")))
                         (assoc-in [:execution :sources] {})
                         (assoc-in [:execution :bindings "user-by-id-sql" :binding/implementation]
                                   {:data {:example.user/id 1 :example.user/name "Ada"}
                                    :match-on :example.user/id})
                         (assoc-in [:execution :bindings "user-by-id-sql" :binding/input-map]
                                   {:example.user/id "example.user/id"})
                         (assoc-in [:execution :bindings "user-by-id-sql" :binding/output-map]
                                   {:example.user/name "example.user/name"}))
            proposed (svc :prepare-import {:graph "w/g" :text (pr-str document)} provenance)
            reviewed (svc :prepare-import {:graph "w/g" :text (pr-str document)
                                           :source_additions ["workspace-db"]} provenance)
            by-id (into {} (map (juxt :resolver-id identity) (:resolver-sources reviewed)))]
        (is (false? (:source-mapping-ready? proposed)) (pr-str proposed))
        (is (= :semantic.import/source-selection-required
               (get-in proposed [:source-findings 0 :code])))
        (is (nil? (get-in proposed [:source-mappings "posts-for-user"]))
            "a Workspace Source is never selected until it is explicitly added")
        (is (true? (:source-mapping-ready? reviewed)) (pr-str reviewed))
        (is (= :not-required (get-in by-id ["user-by-id" :status])))
        (is (= "static" (get-in by-id ["user-by-id" :local-kind])))
        (is (= "workspace-db" (get-in by-id ["posts-for-user" :selected-source])))
        (is (= #{"static" "workspace-db"}
               (set (keys (get-in reviewed [:document :execution :sources]))))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest save-draft-validates-before-write-and-keeps-the-last-good-text
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "owner"}
        svc (semantic/service conn {:workspace "w" :principal principal} (fn [_ source] source))
        provenance {:entrypoint :ui :actor principal}]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (graph/register-graph! conn {:workspace "w" :name "g" :created-at "2026-09-11T00:00:00Z" :members {}})
      (let [initial (svc :read {:graph "w/g"} provenance)
            saved (svc :save {:graph "w/g" :expected_revision 0 :text (:text initial)} provenance)
            invalid (svc :save {:graph "w/g" :expected_revision 1 :text "{:contract"} provenance)
            reopened (svc :read {:graph "w/g"} provenance)
            stale (svc :save {:graph "w/g" :expected_revision 0 :text (:text initial)} provenance)]
        (is (= 1 (:revision saved)))
        (is (= :contract/unreadable (get-in invalid [:findings 0 :code])))
        (is (= 1 (:revision reopened)))
        (is (= (:document saved) (:document reopened)) "invalid text never replaces the saved draft")
        (is (= :semantic.draft/revision-conflict (get-in stale [:findings 0 :code]))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest adopt-document-rebinds-only-assets-owned-by-the-source-graph
  (let [source (-> (edn/read-string
                    (slurp "../../docs/guides/examples/ai-semantic-contract-example-2.0.edn"))
                   (assoc-in [:ownership :terms "shared/external"] "external-graph"))
        planned (semantic/rebind-document-context source "target-workspace" "target-graph")]
    (is (= "target-graph"
           (get-in planned [:document :ownership :entities "example.user"])))
    (is (= "external-graph"
           (get-in planned [:document :ownership :terms "shared/external"]))
        "external ownership is not captured by the import target")))

(deftest contract-14-duplicate-rewrites-only-owned-assets
  (let [package (edn/read-string
                 (slurp "../../docs/guides/examples/orders-semantic-graph-2.0.edn"))
        original (get-in package [:graphs 0 :document])
        copied (semantic/duplicate-document original "new-graph-id" "sandbox.orders"
                                            semantic/checkers)]
    (is (empty? (:findings copied)) (pr-str copied))
    (is (= "new-graph-id" (get-in copied [:document :graph :graph/id])))
    (is (every? #(str/starts-with? % "sandbox.orders.")
                (keys (get-in copied [:document :vocabulary :entities]))))
    (is (every? #(str/starts-with? (namespace %) "sandbox.orders.")
                (keys (get-in copied [:document :vocabulary :attributes]))))
    (is (= (get-in original [:execution :sources])
           (get-in copied [:document :execution :sources]))
        "logical Source Slots are retained without deployment credentials")
    (is (= "1" (get-in copied [:document :publication :release/version])))
    (is (= original (get-in package [:graphs 0 :document]))
        "the source Contract is unchanged")))

(deftest saved-semantic-sql-through-shared-tool-service
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "owner"}
        caller {:workspace "w" :principal principal}
        url (str "jdbc:sqlite:file:" (random-uuid) "?mode=memory&cache=shared")
        svc (semantic/service conn caller (fn [_ source] source))
        ctx {:scope #{:read :write} :semantic-service svc :provenance {:entrypoint :ui :actor principal}}
        reg (tools/registry #{:read :write})
        call #(tools/call reg ctx "semantic_draft" (assoc % :graph "w/g"))]
    (try
      (is (empty? (:findings (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]}))))
      (is (empty? (:findings (graph/register-graph! conn {:workspace "w" :name "g" :created-at "2026-09-08T00:00:00Z" :members {}}))))
      (with-open [db (java.sql.DriverManager/getConnection url)]
        (jdbc/execute! db ["CREATE TABLE cases (id INTEGER PRIMARY KEY, title TEXT)"])
        (jdbc/execute! db ["INSERT INTO cases VALUES (1, 'Saved contract')"])
        (is (empty? (:findings (pool/execute-action conn {:action :create :type :source :id "db" :workspace "w"
                                                        :expected_revision 0 :entry {:type "sql" :label "DB" :jdbc_url url}}))))
        (let [proposal (call {:action :prepare-lookup
                             :lookup {:entity-id "support.case" :label "Case" :identity-name "id" :identity-type :integer
                                      :value-name "title" :value-type :string :value-nullable? false
                                      :source-id "db" :resolver-id "lookup" :sql-text "SELECT title FROM cases WHERE id = :id"}})
              id :support.case/id
              broken :support.case/broken
              graph-id (get-in (:document proposal) [:graph :graph/id])
              document (-> (:document proposal)
                           (assoc-in [:vocabulary :attributes broken]
                                     {:attribute/label "Broken" :attribute/type :string
                                      :attribute/nullable? true})
                           (update-in [:vocabulary :entities "support.case" :entity/attributes] conj broken)
                           (assoc-in [:execution :resolvers "broken"]
                                     {:label "Broken lookup" :effect :read :input [id]
                                      :output [broken] :result/cardinality :one})
                           (assoc-in [:execution :bindings "broken"]
                                     {:binding/for [:resolver "broken"] :binding/source "db"
                                      :binding/effect :read
                                      :binding/implementation {:query "SELECT missing_column FROM cases WHERE id = :id"}
                                      :binding/input-map {id "id"}
                                      :binding/output-map {broken "missing_column"}})
                           (assoc-in [:execution :selection [:resolver "broken"]] "broken")
                           (assoc-in [:ownership :resolvers "broken"] graph-id)
                           (update-in [:graph :graph/resolvers] conj "broken"))
              saved (call {:action :import :expected_revision 0 :text (pr-str document)
                           :source_additions ["db"]})]
          (is (= 1 (:revision saved)) (pr-str saved))
          (is (true? (:imported? saved)) "import is a real persisted draft action")
          (is (= :semantic.studio/publish-not-enabled
                 (get-in (call {:action :cut-artifact :expected_revision 1}) [:findings 0 :code])))
          (let [cuts (atom [])
                publisher (semantic/service conn caller (fn [_ source] source)
                            {:cut-artifact! (fn [request] (swap! cuts conj request) {:version 1 :findings []})})]
            (is (= :semantic.draft/revision-conflict
                   (get-in (publisher :cut-artifact {:graph "w/g" :expected_revision 0} nil) [:findings 0 :code])))
            (is (empty? @cuts))
            (is (= 1 (:version (publisher :cut-artifact {:graph "w/g" :expected_revision 1} nil))))
            (is (= #{"db"} (set (keys (:entries (first @cuts)))))))
          (is (= (:document saved) (:document (call {:action :read}))))
          (is (= :semantic.draft/revision-conflict (get-in (call {:action :save :expected_revision 0 :text (pr-str document)}) [:findings 0 :code])))
          (let [answer (call {:action :query :expected_revision 1 :entity {:support.case/id 1} :query [:support.case/title]})]
            (is (= "Saved contract" (get-in answer [:response :engine/data :support.case/title])) (pr-str answer)))
          (let [gui-request {:graph "w/g" :action :query :expected_revision 1
                             :entity {:support.case/id 1} :query [:support.case/title]}
                answer (tools/call reg ctx "semantic_draft" gui-request)
                explicit-strict (tools/call reg ctx "semantic_draft" (assoc gui-request :lenient? false))]
            (is (= "Saved contract" (get-in answer [:response :engine/data :support.case/title]))
                "the exact closed request assembled by the GUI reaches the real semantic query")
            (is (= "Saved contract" (get-in explicit-strict [:response :engine/data :support.case/title]))
                "explicit strict mode is a declared Semantic query parameter"))
          (let [mixed {:action :query :expected_revision 1 :entity {:support.case/id 1}
                       :query [:support.case/title :support.case/broken]}
                strict (call (assoc mixed :lenient? false))
                lenient (call (assoc mixed :lenient? true))]
            (is (= :rejected (get-in strict [:response :engine/status]))
                "strict mode preserves the all-or-error execution behavior")
            (is (= :ok (get-in lenient [:response :engine/status])) (pr-str lenient))
            (is (= "Saved contract" (get-in lenient [:response :engine/data :support.case/title])))
            (is (not (contains? (get-in lenient [:response :engine/data]) :support.case/broken))
                "lenient mode retains the successful sibling and omits the failed one")
            (is (= [:support.case/broken]
                   (get-in lenient [:response :engine/issues 0 :path]))
                "the omitted sibling has a safe, attribute-addressed error"))
          (let [encoded (artifact/encode-candidate (:document saved) {} semantic/checkers)
                candidate (env/for-semantic-artifact (:files encoded) (:artifact-digest encoded)
                                                    {:adapters semantic/adapters :connections {"db" {:jdbc_url url}}})]
            (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
            (is (= "Saved contract" (get-in (env/query-semantic-candidate candidate {:query [:support.case/title] :entity {:support.case/id 1}})
                                           [:response :engine/data :support.case/title])))
            (is (seq (:findings (env/for-semantic-artifact (:files encoded) "sha256:wrong" {:adapters semantic/adapters}))))
            (is (seq (:findings (env/query-semantic-candidate candidate {:graph/id "other" :query [:support.case/title]})))))
          (is (seq (:findings (call {:action :query :expected_revision 1 :entity {:support.case/id "1"} :query [:support.case/title]}))))
          (is (seq (:findings (call {:action :query :expected_revision 1 :query '[(evil {})]}))))
          (is (seq (:findings (tools/call reg (assoc ctx :semantic-service (semantic/service conn (assoc caller :workspace "other") (fn [_ source] source)))
                                        "semantic_draft" {:graph "w/g" :action :read}))))
          (is (seq (:findings (tools/call (tools/registry #{:read}) ctx "semantic_draft" {:graph "w/g" :action :read}))))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest upgrade-and-migration-are-retired-actions-and-old-drafts-cannot-be-saved
  ;; Task book «contract-upgrade-and-retirement» PR1 (owner 2026-09-19): the
  ;; migration namespace is deleted and the platform reads 2.0 only. An old
  ;; document cannot even be seeded as a draft; the upgrade path is the
  ;; open-atlas-graph-builder skill, outside Studio.
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "owner"}
        svc (semantic/service conn {:workspace "w" :principal principal} (fn [_ source] source))
        provenance {:entrypoint :ui :actor principal}]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (graph/register-graph! conn {:workspace "w" :name "g" :created-at "2026-09-08T00:00:00Z" :members {}})
      (let [initial (:document (svc :read {:graph "w/g"} provenance))
            graph-id (get-in initial [:graph :graph/id])
            old (-> initial
                    (assoc-in [:contract :contract/version] "1.0.0")
                    (update :graph dissoc :graph/terms)
                    (update :vocabulary dissoc :references)
                    (update :ownership dissoc :terms)
                    (assoc-in [:publication :required-capabilities] #{:semantic/core-v1}))
            seeded (pool/save-semantic-draft! conn "w" graph-id 0 old semantic/checkers provenance)
            saved (svc :save {:graph "w/g" :expected_revision 0 :text (pr-str old)} provenance)]
        (is (= :contract/version-retired (get-in seeded [:findings 0 :code])) (pr-str seeded))
        (is (= :contract/version-retired (get-in saved [:findings 0 :code])) (pr-str saved))
        (is (re-find #"open-atlas-graph-builder" (get-in saved [:findings 0 :message])))
        (doseq [action [:prepare-upgrade :prepare-migration :prepare-migration-document :migration-source-evidence]]
          (is (= :semantic.studio/unknown-action
                 (get-in (svc action {:graph "w/g"} provenance) [:findings 0 :code]))
              (name action)))
        (is (zero? (:revision (svc :read {:graph "w/g"} provenance)))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest duplicate-rewrites-owned-attributes-inside-query-example-text
  (let [doc (edn/read-string (slurp "../../docs/guides/examples/orders-semantic-contract-2.0.edn"))
        result (semantic/duplicate-document doc "copy-id" "sandbox.orders" semantic/checkers)
        examples (get-in result [:document :graph :graph/query-examples])]
    (is (empty? (:findings result)))
    (is (= [:sandbox.orders.order/customer_id :sandbox.orders.order/from_acct_no
            :sandbox.orders.order/status :sandbox.orders.order/total]
           (edn/read-string (:example/eql (first examples)))))
    (is (= (get-in doc [:execution :bindings "order-by-id-sql" :binding/implementation])
           (get-in result [:document :execution :bindings "order-by-id-sql" :binding/implementation])))))

(deftest graph-connection-replacement-updates-bindings-in-one-revision
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "owner"}
        service (semantic/service conn {:workspace "w" :principal principal} (fn [_ s] s))
        provenance {:entrypoint :ui :actor principal}]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (let [g (:graph (graph/register-graph! conn {:workspace "w" :name "g" :members {} :created-at "2026-09-15T00:00:00Z"}))
            doc (:document (semantic/rebind-document-context
                             (edn/read-string (slurp "../../docs/guides/examples/orders-semantic-contract-2.0.edn")) "w" (:graph/id g)))]
        (doseq [id ["orders-db" "replacement"]]
          (pool/execute-action conn {:action :create :type :source :id id :workspace "w" :expected_revision 0
                                     :entry {:type "sql" :jdbc_url "jdbc:sqlite::memory:"}}))
        (pool/save-semantic-draft! conn "w" (:graph/id g) 0 doc semantic/checkers provenance)
        (let [request {:graph "w/g" :expected_revision 1 :source_ids ["replacement"]
                       :source_replacements {"orders-db" "replacement"}}
              result (service :update-graph-sources request provenance)
              updated (:document result)]
          (is (empty? (:findings result)) (pr-str result))
          (is (= 2 (:revision result)))
          (is (= #{"replacement"} (set (keys (get-in updated [:execution :sources])))))
          (is (= #{"replacement"} (set (map :binding/source (vals (get-in updated [:execution :bindings]))))))
          (is (= (map :binding/implementation (vals (get-in doc [:execution :bindings])))
                 (map :binding/implementation (vals (get-in updated [:execution :bindings])))))
          (is (= :semantic.draft/revision-conflict
                 (get-in (service :update-graph-sources request provenance) [:findings 0 :code])))
          (is (= :semantic.graph-source/incompatible-connection
                 (get-in (service :update-graph-sources (assoc request :expected_revision 2
                                                              :source_replacements {"replacement" "missing"}) provenance)
                         [:findings 0 :code])))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest merge-model-drafts-preserves-both-source-models
  (let [document (clojure.edn/read-string (slurp "../../docs/guides/examples/orders-semantic-contract-2.0.edn"))
        result (open.atlas.studio.semantic-service/merge-documents [document document] "merged-id" "Merged" "merged.test")
        merged (:document result)]
    (is (empty? (:findings result)) (pr-str (:findings result)))
    (is (= 8 (count (get-in merged [:vocabulary :entities]))))
    (is (= 4 (count (get-in merged [:execution :resolvers]))))
    (is (= 4 (count (get-in merged [:execution :bindings]))))
    (is (= 6 (count (get-in merged [:graph :graph/query-examples]))))
    (is (= #{"merged-id"} (set (vals (get-in merged [:ownership :entities])))))))

(deftest merge-model-refuses-conflicting-source-declarations
  (let [document (clojure.edn/read-string (slurp "../../docs/guides/examples/orders-semantic-contract-2.0.edn"))
        changed (assoc-in document [:execution :sources "orders-db" :source/effects] #{:read :write})
        result (open.atlas.studio.semantic-service/merge-documents [document changed] "merged-id" "Merged" "merged.test")]
    (is (seq (:findings result)))
    (is (nil? (:document result)))))

(deftest the-same-model-imports-into-independent-graphs-and-packages-are-refused
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "model-owner"}
        svc (semantic/service conn {:workspace "w" :principal principal} (fn [_ source] source))
        provenance {:entrypoint :ui :actor principal}
        call (fn [graph action args] (svc action (assoc args :graph (str "w/" graph)) provenance))
        text (slurp "../../docs/guides/examples/ai-semantic-contract-example-2.0.edn")]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (doseq [name ["first" "second"]]
        (graph/register-graph! conn {:workspace "w" :name name :created-at "2026-09-27T00:00:00Z" :members {}})
        (let [imported (call name :import {:text text :expected_revision 0})]
          (is (:imported? imported) (pr-str imported))
          (is (= 1 (:revision imported)))))
      (let [first-draft (call "first" :read {})
            second-draft (call "second" :read {})]
        (is (= (semantic/document-asset-ids (:document first-draft))
               (semantic/document-asset-ids (:document second-draft))))
        (is (seq (semantic/document-asset-ids (:document first-draft))))
        (is (not= (get-in first-draft [:document :graph :graph/id])
                  (get-in second-draft [:document :graph :graph/id])))
        (doseq [[name source] [["first" "alpha"] ["second" "beta"]]]
          (pool/execute-action conn {:action :create :type :source :id source :workspace "w" :expected_revision 0
                                     :entry {:type "sql" :jdbc_url "jdbc:sqlite::memory:"}})
          (is (:updated? (call name :configure-resolver-sources
                              {:expected_revision 1 :default_sources {"sql" source}}))))
        (let [before-first (call "first" :read {})
              before-second (call "second" :read {})
              package-text (slurp "../../docs/guides/examples/orders-semantic-graph-2.0.edn")]
          (is (= #{"alpha"} (set (map :binding/source (vals (get-in before-first [:document :execution :bindings]))))))
          (is (= #{"beta"} (set (map :binding/source (vals (get-in before-second [:document :execution :bindings]))))))
          (doseq [action [:prepare-import :import]]
            (let [refused (call "first" action {:text package-text :expected_revision 2})]
              (is (= :semantic.import/package-not-supported (get-in refused [:findings 0 :code])))
              (is (re-find #"single-Graph" (get-in refused [:findings 0 :message])))
              (is (= before-first (call "first" :read {})))
              (is (= before-second (call "second" :read {})))))
          (let [edited (assoc-in (:document before-first) [:graph :graph/label] "First only")
                saved (call "first" :save {:text (pr-str edited) :expected_revision 2})]
            (is (empty? (:findings saved)) (pr-str saved))
            (is (= 3 (:revision saved)))
            (is (= "First only" (get-in (call "first" :read {}) [:document :graph :graph/label])))
            (is (= before-second (call "second" :read {})) "editing one copy cannot change the other draft"))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest missing-sources-import-as-atomic-drafts-then-bind-and-release
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (graph/install! (pool/open-pool! cfg))
        principal {:issuer "test" :subject "import-owner"}
        svc (semantic/service conn {:workspace "w" :principal principal} (fn [_ source] source))
        provenance {:entrypoint :ui :actor principal}
        call #(svc %1 (assoc %2 :graph "w/g") provenance)]
    (try
      (graph/register-workspace! conn {:name "w" :members [{:principal principal :role :owner}]})
      (graph/register-graph! conn {:workspace "w" :name "g" :created-at "2026-09-23T00:00:00Z" :members {}})
      (let [raw (edn/read-string (slurp "../../docs/guides/examples/ai-semantic-contract-example-2.0.edn"))
            ;; Keep an inactive alternative too: a complete import never drops it.
            raw (assoc-in raw [:execution :bindings "alternative-user"]
                          (get-in raw [:execution :bindings "user-by-id-sql"]))
            imported (call :import {:text (pr-str raw) :expected_revision 0})
            id (get-in imported [:document :graph :graph/id])]
        (is (:imported? imported) (pr-str imported))
        (is (= 1 (:revision imported)))
        (is (= 3 (count (get-in imported [:document :execution :bindings]))))
        (is (seq (:source-findings imported)))
        (is (not (:imported? (call :import {:text (pr-str raw) :expected_revision 0}))))
        (is (false? (:would-cut? (semantic/publishability (pool/db conn) "w" id (fn [_ x] x)))))
        (is (= 3 (count (:resolver-sources (call :resolver-sources {})))))
        (doseq [text ["{:broken" (pr-str (assoc-in raw [:vocabulary :entities "example.user" :entity/identity] [:not/declared]))]]
          (is (seq (:findings (call :import {:text text :expected_revision 1}))))
          (is (= (:document imported) (:document (call :read {}))))
          (is (= 1 (:revision (call :read {})))))
        (doseq [[source type] [["alpha" "sql"] ["beta" "sql"] ["http" "api"]]]
          (pool/execute-action conn {:action :create :type :source :id source :workspace "w" :expected_revision 0
                                    :entry (if (= type "sql") {:type type :jdbc_url "jdbc:sqlite::memory:"}
                                               {:type type :base_url "https://example.com"})}))
        (is (seq (:findings (call :configure-resolver-sources {:expected_revision 1 :binding_sources {"user-by-id-sql" "http"}}))))
        (is (= 1 (:revision (call :read {}))))
        (is (:updated? (call :configure-resolver-sources {:expected_revision 1 :binding_sources {"user-by-id-sql" "beta"}})))
        (let [updated (call :configure-resolver-sources {:expected_revision 2 :default_sources {"sql" "alpha"}})
              document (:document (call :read {}))]
          (is (:updated? updated) (pr-str updated))
          ;; A per-kind default resets EVERY binding of the kind, including the
          ;; one an earlier explicit choice had pointed at «beta».
          (is (= "alpha" (get-in document [:execution :bindings "user-by-id-sql" :binding/source])))
          (is (= "alpha" (get-in document [:execution :bindings "posts-for-user-sql" :binding/source])))
          (is (= "alpha" (get-in document [:execution :bindings "alternative-user" :binding/source])))
          (is (every? #(= :ready (:status %)) (:resolver-sources updated)))
          (is (:would-cut? (semantic/publishability (pool/db conn) "w" id (fn [_ x] x))))
          (is (seq (:findings (call :configure-resolver-sources {:expected_revision 2 :binding_sources {"user-by-id-sql" "alpha"}}))))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest rebinding-preserves-existing-target-source-policy
  (let [policy {:max_retries 2 :retry_on #{:timeout}}
        document {:execution {:sources {"old" {:source/kind "sql" :source/effects #{:read}}
                                        "target" {:source/kind "sql" :source/effects #{:write} :source/policy policy}}
                              :bindings {"a" {:binding/source "target" :binding/effect :write}
                                         "b" {:binding/source "old" :binding/effect :read}}}}]
    (with-redefs-fn {#'semantic/workspace-source-options (fn [& _] [{:id "target" :type "sql"}])
                    #'semantic/binding-compatible? (constantly true)
                    #'contract/validate-document (fn [d _] {:document d :findings []})}
      (fn []
        (let [updated (:document (#'semantic/configure-resolver-sources nil "w" {:document document} {"b" "target"} {}))]
          (is (= policy (get-in updated [:execution :sources "target" :source/policy])))
          (is (= #{:read :write} (get-in updated [:execution :sources "target" :source/effects])))
          (is (= "target" (get-in updated [:execution :bindings "b" :binding/source])))
          (is (= "target" (get-in updated [:execution :bindings "a" :binding/source]))))))))
