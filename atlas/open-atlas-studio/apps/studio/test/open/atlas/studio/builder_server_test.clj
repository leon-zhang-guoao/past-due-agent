(ns open.atlas.studio.builder-server-test
  "The Studio server's authenticated authoring, source, graph, and audit boundaries."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.runtime.obs :as obs]
            [open.atlas.runtime.graph :as rgraph]
            [open.atlas.runtime.store :as rstore]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.sql :as sql]
            [open.atlas.common.connector.sql.impl.exec :as sql-exec]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.studio.builder-server :as bs]
            [open.atlas.runtime.artifact-cleanup :as cleanup]
            [open.atlas.studio.semantic-service :as semantic]
            [open.atlas.studio.credentials :as credentials]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.auth :as auth]
            [open.atlas.studio.http :as http]
            [open.atlas.runtime.token :as rtoken]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.pool :as pool]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

;; Private by design — the handlers are the app's own surface, not an API. The
;; alternative to reaching for the vars is making them public purely to be
;; tested, which would widen the ns for no caller.
(def ^:private describe-source-schema! #'bs/describe-source-schema!)
(def ^:private studio-state            #'bs/studio-state)
(def ^:private add-member!             #'bs/add-studio-workspace-member!)
(def ^:private remove-member!          #'bs/remove-studio-workspace-member!)
(def ^:private set-members!            #'bs/update-graph-members!)
(def ^:private update-recipe!          #'bs/update-graph-recipe!)
(def ^:private intent-tool!            #'bs/intent-tool!)
(def ^:private visible-events          #'bs/visible-events)
(def ^:private event-workspace         #'bs/event-workspace)
(def ^:private rekeywordize            #'bs/rekeywordize)
(def ^:private statements-summary      #'bs/statements-summary)
(def ^:private eql-shape-of            #'bs/eql-shape-of)
(def ^:private key-face-data           #'bs/key-face-data)
(def ^:private run-request!            #'bs/run-request!)
(def ^:private duplicate-graph!        #'bs/duplicate-graph!)
(def ^:private create-graph!           #'bs/create-graph!)
(def ^:private create-studio-user!     #'bs/create-studio-user!)
(def ^:private reset-user-password!    #'bs/reset-studio-user-password!)
(def ^:private remove-studio-user!     #'bs/remove-studio-user!)
(def ^:private prepare-graph-deletion! #'bs/prepare-runtime-graph-deletion!)

(declare accept! as ^:dynamic *conn* ^:dynamic *owner*)

(deftest archived-graph-runtime-cleanup-stops-serving-and-revokes-only-live-keys
  (let [before @@studio-state
        stopped (atom nil)
        revoked (atom [])
        owner {:issuer "test" :subject "owner"}]
    (try
      (swap! @studio-state assoc :runtime {:graph ::graph-store :token ::token-store})
      (with-redefs [rgraph/serving-version (fn [store graph]
                                             (is (= ::graph-store store))
                                             (is (= "demo/orders" graph))
                                             4)
                    rgraph/stop-serving! (fn [store graph request]
                                           (reset! stopped [store graph request])
                                           {:graph graph :from 4 :serving nil :findings []})
                    rtoken/all-records (fn [store]
                                         (is (= ::token-store store))
                                         [{:token-id "live" :graph-id "graph-id" :version 4 :status :active
                                           :expires-at "2999-01-01T00:00:00Z"}
                                          {:token-id "other" :graph-id "other-id" :version 1 :status :active
                                           :expires-at "2999-01-01T00:00:00Z"}])
                    rtoken/revoke-token! (fn [store request]
                                           (swap! revoked conj [store request])
                                           {:token-id (:token-id request) :status :revoked})]
        (is (= {:stopped-version 4 :tokens-revoked 1 :artifact-copies-deleted 0}
               (prepare-graph-deletion! {:graph/qualified-name "demo/orders" :graph/id "graph-id"}
                                        {:principal owner}))))
      (is (= [::graph-store "demo/orders"
              {:expected-current 4 :stopped-at (get-in @stopped [2 :stopped-at]) :stopped-by owner}]
             @stopped))
      (is (= ["live"] (mapv #(get-in % [1 :token-id]) @revoked)))
      (finally (reset! @studio-state before)))))

(deftest new-empty-graph-needs-only-a-name
  (let [sources-before (count (filter #(= :source (:pool/type %))
                                      (pool/objects (pool/db *conn*))))
        result (as *owner* #(create-graph! {:name "empty-contract"}))
        created (:graph result)]
    (is (= "demo/empty-contract" (:graph/qualified-name created)))
    (is (every? empty? (vals (:graph/members created))))
    (is (nil? (pool/semantic-draft (pool/db *conn*) "demo" (:graph/id created))))
    (is (= sources-before (count (filter #(= :source (:pool/type %))
                                         (pool/objects (pool/db *conn*)))))
        "creating an empty Graph does not invent a Source")))

(deftest duplicate-contract-graph-creates-new-owned-namespace
  (let [source (accept! (graph/register-graph! *conn* {:workspace "demo" :name "semantic-source"
                                                       :members {} :created-at "2026-09-09T00:00:00Z"}))
        graph-id (get-in source [:graph :graph/id])
        package (edn/read-string (slurp "../../docs/guides/examples/orders-semantic-graph-2.0.edn"))
        original (-> (get-in package [:graphs 0 :document])
                     (assoc :workspace {:workspace/id "demo"})
                     (assoc-in [:graph :graph/id] graph-id)
                     (update :ownership (fn [ownership]
                                          (into {} (map (fn [[kind owners]]
                                                          [kind (into {} (map (fn [[asset _]] [asset graph-id])) owners)]))
                                                ownership))))]
    (is (= 1 (:revision (pool/save-semantic-draft! *conn* "demo" graph-id 0 original
                                                   semantic/checkers
                                                   {:entrypoint :ui :actor (:principal *owner*)}))))
    (let [source-before (:document (pool/semantic-draft (pool/db *conn*) "demo" graph-id))
          result (as *owner* #(duplicate-graph! {:graph "demo/semantic-source"
                                                 :name "semantic-copy"
                                                 :namespace "sandbox.orders"}))
          target (graph/graph (pool/db *conn*) "demo/semantic-copy")
          copied (pool/semantic-draft (pool/db *conn*) "demo" (:graph/id target))]
      (is (true? (:semantic-copied? result)) (pr-str result))
      (is (= 1 (:revision copied)))
      (is (every? empty? (vals (:graph/members target)))
          "a semantic copy does not retain the source Graph's legacy recipe")
      (is (every? #(str/starts-with? % "sandbox.orders.")
                  (keys (get-in copied [:document :vocabulary :entities]))))
      (is (= (get-in original [:execution :sources])
             (get-in copied [:document :execution :sources]))
          "logical Source configuration is copied without deployment credentials")
      (is (= source-before (:document (pool/semantic-draft (pool/db *conn*) "demo" graph-id)))
          "the source Graph remains unchanged"))))

(def ^:dynamic *jdbc-url*
  "A fresh in-memory database per test.

   Not a constant: the connector caches a connection pool per connection
   fingerprint, and a pool holding a connection open is exactly what keeps a
   `mode=memory&cache=shared` database alive after this fixture drops its own
   handle. Reusing one URL therefore leaks the previous test's tables into the
   next one — as `CREATE TABLE vendors` failing the second time."
  nil)

(def ^:private schema
  ["CREATE TABLE vendors (vend_no INTEGER PRIMARY KEY, vend_name TEXT NOT NULL)"
   "CREATE TABLE parts (sku_no INTEGER PRIMARY KEY, part_no TEXT NOT NULL,
                        vend_no INTEGER REFERENCES vendors(vend_no))"
   ;; A composite-PK child table: what `by-key-resolver` writes for it is the
   ;; row-value batch (owner 2026-09-07, doc 106 §106.14 item 14).
   "CREATE TABLE part_notes (sku_no INTEGER, seq INTEGER, body TEXT,
                             PRIMARY KEY (sku_no, seq),
                             FOREIGN KEY (sku_no) REFERENCES parts(sku_no))"
   "CREATE VIEW cheap_parts AS SELECT sku_no, part_no FROM parts"])

(def ^:dynamic *conn* nil)
(def ^:dynamic *owner* nil)   ; owner of workspace "demo"
(def ^:dynamic *editor* nil)  ; editor of workspace "demo"
(def ^:dynamic *outsider* nil); owner of their own workspace only

(defn- accept!
  "A setup step that reports findings instead of throwing is a setup step that
   can fail silently. Every one of them goes through here."
  [result]
  (assert (empty? (:findings result)) (pr-str (:findings result)))
  result)

(defn- with-studio [f]
  (let [url  (str "jdbc:sqlite:file:atlas_bs_" (str/replace (str (random-uuid)) "-" "")
                  "?mode=memory&cache=shared")
        keep-alive (java.sql.DriverManager/getConnection url)
        cfg  (pool/config {:backend :mem :id (str (random-uuid))})
        conn (-> (pool/open-pool! cfg) graph/install! identity/install!)]
    (try
      (doseq [s schema] (jdbc/execute! keep-alive [s]))
      (let [owner    (:user (identity/bootstrap-admin!
                             conn {:email "owner@example.com" :name "Owner"
                                   :password "a-safe-password" :workspace "demo"}))
            editor   (:user (identity/create-local-user!
                             conn {:email "editor@example.com" :name "Editor"
                                   :password "a-safe-password"}))
            outsider (:user (identity/create-local-user!
                             conn {:email "outsider@example.com" :name "Outsider"
                                   :password "a-safe-password"}))]
        ;; Asserted, not fired and forgotten: this call refuses a malformed
        ;; member with findings rather than throwing, so an ignored result
        ;; leaves the editor a non-member and every gate below then passes or
        ;; fails for the wrong reason.
        (accept! (graph/add-workspace-member!
                  conn "demo" {:principal (:principal editor) :role :editor}))
        (accept! (pool/execute-action conn {:workspace "demo" :action :create :type :source :id "demo-db"
                                            :entry {:type "sql" :jdbc_url url}
                                            :expected_revision 0}))
        (reset! @studio-state {:conn conn})
        (binding [*conn* conn *owner* owner *editor* editor
                  *outsider* outsider *jdbc-url* url]
          (f)))
      (finally
        (sql/close-all-pools!)
        (reset! @studio-state nil)
        (pool/close-pool! conn)
        (pool/delete-pool! cfg)
        (.close keep-alive)))))

(use-fixtures :each with-studio)

(defn- as
  "Run `f` as this user, the way the HTTP layer binds a request."
  [user f]
  (binding [auth/*request-user* (assoc user :default-workspace
                                     (if (= (:email user) "outsider@example.com")
                                       "outsider@example.com" "demo"))]
    (f)))

(defn- caught [f]
  (try (f) (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- browse [user]
  (as user #(describe-source-schema! {:source "demo-db" :prefix "cis.demo" :keys? false})))

(defn- objects-of [type*]
  (filter #(= type* (:pool/type %)) (pool/objects (pool/db *conn*))))

;; ---------------------------------------------------------------------------
;; Who may read a source's structure
;; ---------------------------------------------------------------------------

(deftest an-editor-of-the-current-workspace-may-read-the-schema
  (let [reply (browse *editor*)]
    (is (= #{"vendors" "parts" "part_notes" "cheap_parts"}
           (set (map :name (:relations reply)))))))

(deftest the-editors-key-face-is-answered-per-type-and-derived-from-the-dialect
  (doseq [t ["attribute" "namespace" "resolver" "link" "metric" "mutation"
             "source" "term" "unit"]]
    (let [face (key-face-data {"type" t})]
      (is (= t (:type face)))
      (is (seq (:summary face)) t)
      (is (seq (:keys face)) t)
      (testing (str t ": every key carries a meaning and a literal example")
        (doseq [{:keys [key meaning example]} (:keys face)]
          (is (seq meaning) (str t " " key))
          (is (str/starts-with? example (pr-str key)) (str t " " key))))))
  (testing "the vocabulary comes from the schema, so it cannot drift from it"
    (let [kind (first (filter #(= :kind (:key %))
                              (:keys (key-face-data {"type" "namespace"}))))]
      (is (= #{:master :transaction :reference :event :bridge} (set (:enum kind))))))
  (testing "an unknown type is refused by name, not answered with an empty face"
    (is (= :asset-type-unknown (:code (caught #(key-face-data {"type" "navigation"})))))
    (is (= :asset-type-unknown (:code (caught #(key-face-data {})))))))

(deftest browsing-skips-the-per-relation-key-round-trips-and-says-so
  ;; The whole point of the browse mode at three hundred tables. It must be
  ;; visible in the reply, or a caller cannot tell an unread key from a
  ;; relation that declares none.
  (let [reply (browse *editor*)]
    (is (false? (:keys-read? reply)))
    (is (every? #(= [] (:primary-key %)) (:relations reply))))
  (testing "and asking without the flag reads them"
    (let [reply (as *editor* #(describe-source-schema!
                               {:source "demo-db" :prefix "cis.demo"
                                :names ["vendors"]}))]
      (is (true? (:keys-read? reply)))
      (is (= ["vend_no"] (:primary-key (first (:relations reply))))))))

(deftest a-source-is-not-readable-from-another-workspace
  "D3.22 closed the window this test used to assert was open.

   A source's owner used to be derived from the resolvers referencing it, so a
   freshly registered one had no owner and had to be readable by any editor in
   the Studio — otherwise it could never be used. With ownership stored, the
   write that registered `demo-db` stamped it `demo`, so there is no unowned
   window and the outsider is refused. Reading a schema spends that source's
   credentials."
  (let [e (caught #(browse *outsider*))]
    (is (= :asset-outside-current-workspace (:code e)))
    (is (= "demo" (:asset-workspace e)) "and it names who does own it")))

(deftest someone-with-no-workspace-they-can-edit-is-refused
  (binding [auth/*request-user* (assoc *outsider* :default-workspace "demo")]
    ;; Asking for "demo" does not grant it: the current workspace is resolved
    ;; from what this principal can actually reach, never from the request.
    (is (not= "demo" (:current-workspace (#'bs/studio-context))))))

(deftest an-unknown-source-is-refused-by-name
  (is (= :source-unknown
         (:code (caught #(as *editor* (fn [] (describe-source-schema!
                                              {:source "nope" :prefix "cis.demo"}))))))))

(deftest a-source-another-workspace-registered-is-refused
  "The stamp is what decides (D3.22): a source the outsider's workspace
   registered is not readable from `demo`, whatever its name looks like and
   whether or not anything references it yet."
  (accept! (pool/execute-action
            *conn* {:workspace "outsider@example.com" :action :create :type :source :id "theirs-db"
                    :entry {:type "sql" :jdbc_url "${THEIRS_URL}"} :expected_revision 0}))
  (let [e (caught #(as *editor* (fn [] (describe-source-schema!
                                        {:source "theirs-db" :prefix "cis.demo"}))))]
    (is (= :asset-outside-current-workspace (:code e)))
    (is (= "outsider@example.com" (:asset-workspace e)))))

(deftest a-source-nobody-has-referenced-yet-is-readable
  ;; Its owner is the workspace that registered it (D3.22) — not derived from
  ;; resolvers — so a source no resolver names yet is readable by its own
  ;; workspace, which is exactly the case this feature exists for.
  (is (seq (:relations (browse *editor*)))))

;; ---------------------------------------------------------------------------
;; Where the model may be written
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Filing a run into a graph somebody is already looking at
;; ---------------------------------------------------------------------------

(defn- graph-named!
  "A registered graph with an empty recipe, to file a run into."
  [name*]
  (:graph/qualified-name
   (:graph (accept! (graph/register-graph!
                     *conn* {:workspace "demo" :name name*
                             :members {} :created-at "2026-08-28T00:00:00Z"})))))

(deftest query-studio-prefers-a-saved-semantic-contract-at-the-http-boundary
  "The browser learns whether a graph has a Contract asynchronously. That UI
   state must never choose the execution catalog: `/api/pathom` makes the
   authoritative decision from the saved draft on every request."
  (let [qn (graph-named! "semantic-http")
        graph-id (:graph/id (graph/graph (pool/db *conn*) qn))
        calls (atom [])
        legacy-called? (atom false)]
    (as *editor*
        #(with-redefs [pool/semantic-draft
                       (fn [_ workspace requested-graph-id]
                         (is (= "demo" workspace))
                         (is (= graph-id requested-graph-id))
                         {:revision 2 :document {:contract "1.3.0"}})
                       semantic/service
                       (fn [& _]
                         (fn [action args provenance]
                           (swap! calls conj [action args provenance])
                           ;; The service's REAL answer shape: the engine
                           ;; response under `:response`, as
                           ;; semantic_service_test pins it. A bare engine map
                           ;; here once hid that this reader looked one level
                           ;; too high and showed `nil` for every Contract run.
                           {:response {:engine/status :ok
                                       :engine/data {:nifi.provenance.event/id 69070}
                                       :engine/stats {}}
                            :findings []
                            :revision 2}))
                       bs/run-draft!
                       (fn [& _]
                         (reset! legacy-called? true)
                         (throw (ex-info "legacy projection must not run" {})))]
           (let [request {:graph qn
                          :query [:nifi.provenance.event/id]
                          :entity {}}
                 reply (run-request! request)]
             (is (= {:nifi.provenance.event/id 69070} (:result reply)))
             (is (= :semantic-contract (get-in reply [:meta :execution-target])))
             (is (= 2 (get-in reply [:meta :config-revision])))
             (is (false? @legacy-called?))
             (is (= [[:query {:graph qn
                              :expected_revision 2
                              :query [:nifi.provenance.event/id]
                              :entity {}
                              :lenient? false}
                      nil]]
                    @calls)))))))

;; ---------------------------------------------------------------------------
;; Editing a recipe: several kinds are one edit, and prefixes cut both ways
;; ---------------------------------------------------------------------------

(defn- members-of [qn] (:graph/members (graph/graph (pool/db *conn*) qn)))

;; ---------------------------------------------------------------------------
;; What only an owner may sign
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Preview writes nothing
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; The path a modeller actually walks: register a source, then build from it
;; ---------------------------------------------------------------------------

(def ^:private write-workspace-asset! #'bs/write-workspace-asset!)
(def ^:private catalog-data           #'bs/catalog-data)

(deftest a-source-can-be-registered-through-the-builders-write-path
  ;; The chicken and egg this guards: a source's owner is derived from the
  ;; resolvers referencing it, so a new source is owned by nobody — and a rule
  ;; of "you may only write what your workspace owns" then makes registering
  ;; one impossible, while no resolver can reference a source that cannot be
  ;; created. Without this, the whole feature is unreachable: no source, no
  ;; dossier, no "build default model".
  (let [result (as *editor*
                   #(write-workspace-asset!
                     {:workspace "demo" :action :create :type :source :id "new-db"
                      :entry {:type "sql" :jdbc_url "${NEW_DB_URL}"}
                      :expected_revision 0}))]
    (is (= 1 (:revision result)))
    (is (some #(= "new-db" (:pool/id %)) (objects-of :source)))))

(deftest a-source-owned-by-another-workspace-may-not-be-edited-here
  "New is fair game; owned elsewhere is not (D3.22). The object another
   workspace created carries its stamp, and this workspace's editor cannot
   write over it."
  (accept! (pool/execute-action
            *conn* {:workspace "outsider@example.com" :action :create :type :source :id "new-db"
                    :entry {:type "sql" :jdbc_url "${NEW_DB_URL}"} :expected_revision 0}))
  (let [e (caught #(as *editor*
                      (fn [] (write-workspace-asset!
                              {:action :update :type :source :id "new-db"
                               :entry {:type "sql" :label "mine now"}
                               :expected_revision 1}))))]
    (is (= :asset-outside-current-workspace (:code e)))
    (is (= "outsider@example.com" (:asset-workspace e)))))

(deftest an-entity-under-a-name-no-claim-covers-is-created-and-stamped
  "The bug this whole ruling came from (measured 2026-09-05): «+ entity» on any
   name outside `cis.*` was refused with `:asset-outside-current-workspace`,
   because the write boundary asked the claim table D3.17 had retired. A new
   object is now simply new — allowed, and stamped with the workspace that
   created it."
  (as *editor* #(write-workspace-asset!
                 {:action :create :type :namespace :id "abc.order"
                  :entry {:kind :master :label "Order"} :expected_revision 0}))
  (let [o (pool/object (pool/db *conn*) :namespace "abc.order")]
    (is (some? o) "the entity exists")
    (is (= "demo" (:pool/workspace o)) "and it belongs to the workspace that made it")))

(deftest a-save-preview-is-stamped-like-the-save-itself
  "Measured 2026-09-05 right after the stamping landed: every «Save» in the
   editor was refused with `:pool.workspace/mismatch … got nil`. The editor
   previews an edit through `/api/studio/preflight` before writing it, and
   that preview handed the browser's action to the pool unstamped — the write
   path stamped, the preview path did not. Same decision path, same stamp."
  (as *editor* #(write-workspace-asset!
                 {:action :create :type :namespace :id "abc.order"
                  :entry {:kind :master :label "Order"} :expected_revision 0}))
  (let [model (pool/as-dialect (pool/db *conn*))]
    (accept! (graph/register-graph!
              *conn* {:workspace "demo" :name "pf"
                      :members (graph/expand-selectors model ["abc.*"])})))
  ;; `preflight-asset!` needs the embedded runtime; the stamping lives one
  ;; call below it, in the service both doors share, so that is what is tested.
  (let [preflight (requiring-resolve 'open.atlas.studio.publish-service/preflight)
        r (as *editor* #(preflight {:conn *conn* :caller (#'bs/session-caller)}
                                   {:graph "demo/pf"
                                    :action {:action :update :type :namespace :id "abc.order"
                                             :entry {:kind :master :label "Orders"}
                                             :expected_revision 1}}))
        codes (set (map :code (:findings r)))]
    (is (not (contains? codes :pool.workspace/mismatch)) (pr-str r))
    (is (not (contains? codes :pool.action/malformed)) (pr-str r))))

(deftest another-workspace-can-neither-see-nor-write-what-this-one-stamped
  "D3.17's closed world, enforced by the stored column: the outsider's editor
   does not get `abc.order` in its model and cannot write over it."
  (as *editor* #(write-workspace-asset!
                 {:action :create :type :namespace :id "abc.order"
                  :entry {:kind :master :label "Order"} :expected_revision 0}))
  (is (nil? (get-in (as *outsider* #(#'bs/read-model)) [:namespaces "abc.order"]))
      "not in the other workspace's model")
  (let [e (caught #(as *outsider*
                      (fn [] (#'bs/write-pool-asset!
                              {:action :update :type :namespace :id "abc.order"
                               :entry {:label "mine now"} :expected_revision 1}
                              {:workspace "outsider@example.com" :provenance nil}))))]
    (is (= :asset-outside-current-workspace (:code e)))
    (is (= "demo" (:asset-workspace e)))))

(deftest a-freshly-registered-source-schema-can-be-read-immediately
  ;; Registering a connection makes its schema available to explicit reads.
  (as *editor* #(write-workspace-asset!
                 {:workspace "demo" :action :create :type :source :id "new-db"
                  :entry {:type "sql" :jdbc_url *jdbc-url*} :expected_revision 0}))
  (let [reply (as *editor* #(describe-source-schema!
                             {:source "new-db" :prefix "cis.demo" :keys? false}))]
    (is (= #{"vendors" "parts" "part_notes" "cheap_parts"} (set (map :name (:relations reply)))))
    (is (not (str/includes? (pr-str reply) *jdbc-url*))
        "the structure comes back; the connection string does not")))

;; ---------------------------------------------------------------------------
;; Connect: one step from a JDBC URL to a list of tables
;; ---------------------------------------------------------------------------

(def ^:private connect-source! #'bs/connect-source!)

(deftest connecting-registers-the-source-and-lists-its-tables
  (let [r (as *editor* #(connect-source! {:id "sales-db" :type "sql"
                                          :jdbc_url *jdbc-url*
                                          :label "Sales" :prefix "cis.demo"}))]
    (is (true? (:connected? r)))
    (is (= :ok (:status (:health r))))
    (is (= #{"vendors" "parts" "part_notes" "cheap_parts"} (set (map :name (:relations r)))))
    (is (some #(= "sales-db" (:pool/id %)) (objects-of :source))
        "and the source is registered for later explicit use")))

(deftest a-connection-that-does-not-answer-registers-nothing
  ;; A source that is registered and cannot be reached is worse than no source:
  ;; it sits in the pool looking finished, and the failure resurfaces later as a
  ;; resolver that does not run.
  (let [r (as *editor* #(connect-source! {:id "broken-db" :type "sql"
                                          :jdbc_url "jdbc:sqlite:/nonexistent/dir/x.db"
                                          :prefix "cis.demo"}))]
    (is (false? (:connected? r)))
    (is (= [:connector.source/unreachable] (map :code (:findings r))))
    (is (empty? (filter #(= "broken-db" (:pool/id %)) (objects-of :source))))))

(deftest a-url-carrying-credentials-is-kept-off-the-model-not-refused
  ;; The modeller pastes a full connection string; that is the ordinary case,
  ;; and refusing it is what made this Builder unusable by the people it is
  ;; for. The secret goes to this instance's store and the model records the
  ;; variable a Server administrator will configure.
  (let [r (as *editor* #(connect-source! {:id "leaky-db" :type "sql"
                                          :jdbc_url "jdbc:postgresql://u:sekret@h/db"
                                          :prefix "cis.demo"
                                          :register-unverified? true}))
        entry (:pool/entry (pool/object (pool/db *conn*) :source "leaky-db"))]
    (is (= "${LEAKY_DB_URL}" (:jdbc_url entry))
        "what the pool holds is the name, never the value")
    (is (not (str/includes? (pr-str (pool/as-dialect (pool/db *conn*))) "sekret"))
        "and so the export, the content hash and the artifact cannot carry it")
    (is (not (str/includes? (pr-str r) "sekret")))))

(def ^:private credentials-data #'bs/credentials-data)
(def ^:private put-credentials! #'bs/put-credentials!)

(deftest variable-management-is-independent-of-source-discovery
  (let [home (str (java.nio.file.Files/createTempDirectory
                   "manual-variables" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-redefs-fn {#'bs/studio-home (constantly home)}
      (fn []
        (let [saved (as *editor* #(#'bs/save-variable! {:name "MANUAL_PASSWORD" :value "first-value"}))
              listing (as *editor* #(credentials-data {}))]
          (is (:saved? saved))
          (is (some #{"MANUAL_PASSWORD"} (:unreferenced listing)))
          (is (not (str/includes? (pr-str [saved listing]) "first-value"))))
        (as *editor* #(#'bs/save-variable!
                       {:name "MANUAL_PASSWORD" :previous-name "MANUAL_PASSWORD" :value "second-value"}))
        (let [object (pool/object (pool/db *conn*) :source "demo-db")]
          (as *editor* #(write-workspace-asset!
                         {:action :update :type :source :id "demo-db"
                          :expected_revision (:pool/revision object)
                          :entry (assoc (:pool/entry object) :password "${MANUAL_PASSWORD}")})))
        (accept! (pool/execute-action *conn*
                   {:action :create :workspace "outsider@example.com" :type :source :id "other-workspace-db"
                    :entry {:type "sql" :jdbc_url *jdbc-url* :password "${MANUAL_PASSWORD}"}
                    :expected_revision 0}))
        (let [renamed (as *editor* #(#'bs/save-variable!
                                     {:name "RENAMED_PASSWORD" :previous-name "MANUAL_PASSWORD" :value ""}))]
          (is (= ["demo-db"] (:updated-sources renamed)))
          (is (= "${RENAMED_PASSWORD}"
                 (get-in (pool/object (pool/db *conn*) :source "demo-db") [:pool/entry :password])))
          (is (= "${MANUAL_PASSWORD}"
                 (get-in (pool/object (pool/db *conn*) :source "other-workspace-db") [:pool/entry :password])))
          (is (= "second-value" ((credentials/lookup-fn home) "RENAMED_PASSWORD")))
          (is (= "second-value" ((credentials/lookup-fn home) "MANUAL_PASSWORD")))
          (is (not (str/includes? (pool/export-pool (pool/db *conn*)) "second-value"))))))))

(deftest the-credentials-page-lists-sql-and-api-variables-in-one-table
  ;; doc 106 §106.14-12 item 4: one table over every `${VAR}` a registered
  ;; source references, whatever the connector, with present/missing — and an
  ;; api source's NESTED credential key can now be filled in, which is the
  ;; whole point of keying the store by variable name.
  (as *editor* #(connect-source! {:id "pw-db" :type "sql"
                                  :jdbc_url *jdbc-url*
                                  :username "reader" :password "hunter2"
                                  :prefix "cis.demo"}))
  ;; Registered through the pool rather than the card: api-v1's write-side
  ;; validation resolves `${VAR}` through the PROCESS environment of its own
  ;; accord, so an api source whose token lives only in this store cannot be
  ;; declared through `connect-source!` yet. What is under test here is the
  ;; credentials table.
  (accept! (pool/execute-actions
            *conn* [{:workspace "demo" :action :create :type :source :id "tok-api"
                     :entry {:type "api" :base_url "https://api.example.com"
                             :auth {:type "api_key" :value "${TOK_API_TOKEN}"
                                    :header_name "X-Api-Key"}}
                     :expected_revision 0}]
            {:entrypoint :ui :actor *editor* :at "2026-09-07T00:00:00Z"}))

  (let [by-name #(as *editor* (fn [] (into {} (for [r (:variables (credentials-data {}))] [(:name r) r]))))]
    (is (true? (:present? (get (by-name) "PW_DB_PASSWORD"))) "typed on the sql card")
    ;; Cleared first: this Studio home outlives one test, and «not filled in
    ;; yet» is the state under test.
    (as *editor* #(put-credentials! {:values {"TOK_API_TOKEN" ""}}))
    (is (= {:name "TOK_API_TOKEN" :present? false :stored? false :in-env? false
            :used-by ["tok-api"] :paths [":auth.:value"]}
           (get (by-name) "TOK_API_TOKEN"))
        "declared by an api source and not yet filled in")
    (as *editor* #(put-credentials! {:values {"TOK_API_TOKEN" "jwt-value"}}))
    (is (true? (:stored? (get (by-name) "TOK_API_TOKEN")))
        "and typing it needs no restart — the store is re-read on every call")
    (is (not (str/includes? (pr-str (as *editor* #(credentials-data {}))) "jwt-value"))
        "names, never values")))

(deftest a-credential-free-url-stays-in-the-model
  ;; sql-v1 allows one, and moving it into a local store would cost the model
  ;; the record of which database it was built against. The connector decides
  ;; which values are credentials; this app does not hold a second opinion.
  (as *editor* #(connect-source! {:id "plain-db" :type "sql"
                                  :jdbc_url *jdbc-url*
                                  :prefix "cis.demo"}))
  (is (= *jdbc-url* (:jdbc_url (:pool/entry (pool/object (pool/db *conn*) :source "plain-db"))))))

(deftest a-typed-password-never-reaches-the-pool
  (as *editor* #(connect-source! {:id "pw-db" :type "sql"
                                  :jdbc_url *jdbc-url*
                                  :username "reader" :password "hunter2"
                                  :prefix "cis.demo"}))
  (let [entry (:pool/entry (pool/object (pool/db *conn*) :source "pw-db"))]
    (is (= "${PW_DB_USER}" (:username entry)))
    (is (= "${PW_DB_PASSWORD}" (:password entry)))
    (is (not (str/includes? (pr-str (pool/export-pool (pool/db *conn*))) "hunter2"))
        "the export is the thing that travels; it must not be able to carry this")))

;; ---------------------------------------------------------------------------
;; The Sources page's register
;; ---------------------------------------------------------------------------

(def ^:private sources-data #'bs/sources-data)

(deftest the-sources-register-lists-without-a-graph-selected
  ;; The page exists so a database can be connected before any graph knows
  ;; about it — a graph-context requirement here would rebury the feature.
  (let [{:keys [sources]} (as *editor* #(sources-data {}))
        entry (first (filter #(= "demo-db" (:id %)) sources))]
    (is (some? entry))
    (is (= "sql" (:type entry)))
    (is (= ["jdbc_url"] (:credential-keys entry))
        "which keys are set is said")
    (is (not (str/includes? (pr-str sources) *jdbc-url*))
        "what they are set to never leaves the server")))

(deftest the-source-register-includes-contract-bindings-within-the-workspace
  (let [base (edn/read-string (slurp "../../modules/common/dialect/fixtures/semantic-v2.edn"))
        provenance {:entrypoint :ui :actor (:principal *owner*)}]
    ;; Two Graphs may reuse resolver IDs. Alternative bindings in one Graph
    ;; must not inflate its usage; a different workspace must not contribute.
    (doseq [[workspace id] [["demo" "first"] ["demo" "second"]
                           ["outsider@example.com" "foreign"]]]
      (let [document (-> (:document (semantic/rebind-document-context base workspace id))
                         (assoc-in [:execution :sources] {"demo-db" {:source/kind "sql" :source/effects #{:read}}})
                         (assoc-in [:execution :bindings "lookup-sql" :binding/source] "demo-db"))
            document (assoc-in document [:execution :bindings "alternative"]
                               (get-in document [:execution :bindings "lookup-sql"]))]
        (accept! (pool/save-semantic-draft! *conn* workspace id 0 document
                                           semantic/checkers provenance))))
    (let [source (first (filter #(= "demo-db" (:id %))
                                (:sources (as *editor* #(sources-data {})))))]
      (is (= {:resolvers 2 :operations 0 :graphs 2} (:draft-usage source)))
      (is (empty? (:used-by source)) "Contract usage is independent of legacy pool resolvers")
      (is (not (str/includes? (pr-str source) "foreign")))
      (is (not (str/includes? (pr-str source) *jdbc-url*))))))

;; ---------------------------------------------------------------------------
;; Which sources "build a default model" can actually be offered for
;; ---------------------------------------------------------------------------

(defn- register-api-source! []
  (as *editor* #(write-workspace-asset!
                 {:workspace "demo" :action :create :type :source :id "vendor-api"
                  :entry {:type "api" :base_url "https://vendor.example.invalid"}
                  :expected_revision 0})))

(defn- describable-by-id [sources]
  (into {} (map (juxt :id :describable?)) sources))

(deftest the-register-says-whether-a-source-can-be-introspected-at-all
  ;; `spi/supports-describe?` is a fact only this process holds: which
  ;; connectors are on the classpath decides it, and the browser cannot work it
  ;; out from `:type`. Sent so the Builder can withhold a button whose only
  ;; possible answer would be a refusal.
  (register-api-source!)
  (is (contains? (spi/registered-types) "api")
      "the api connector is loaded here — so the false below means 'implemented
       and declines to describe', which is the interesting case, not 'absent'")
  (let [d (describable-by-id (:sources (as *editor* #(sources-data {}))))]
    (is (true? (get d "demo-db"))
        "sql implements describe-schema, so the wizard can be offered")
    (is (false? (get d "vendor-api"))
        "an HTTP upstream exposes no introspectable structure — api-v1 declines
         describe-schema on purpose, and that is a property of the connector,
         not a failure of this source")))

(deftest the-catalog-carries-the-same-answer-the-dossier-reads
  ;; The dossier's button reads the catalog, the Sources page reads the
  ;; register. Two screens answering the same question differently is how one
  ;; of them ends up offering the dead button.
  (register-api-source!)
  (accept! (pool/execute-actions
            *conn* [{:workspace "demo" :action :create :type :namespace :id "a.thing" :entry {}
                     :expected_revision 0}
                    {:workspace "demo" :action :create :type :namespace :id "b.row" :entry {}
                     :expected_revision 0}
                    {:workspace "demo" :action :create :type :attribute :id :a.thing/id
                     :entry {:type :string :identity? true} :expected_revision 0}
                    {:workspace "demo" :action :create :type :attribute :id :a.thing/name
                     :entry {:type :string} :expected_revision 0}
                    {:workspace "demo" :action :create :type :attribute :id :b.row/id
                     :entry {:type :integer :identity? true} :expected_revision 0}
                    {:workspace "demo" :action :create :type :attribute :id :b.row/label
                     :entry {:type :string} :expected_revision 0}
                    {:workspace "demo" :action :create :type :resolver :id "thing_by_id"
                     :entry {:id "thing_by_id" :input [:a.thing/id]
                             :output [:a.thing/name] :result_type "single"
                             :source {:type "api" :method "GET"
                                      :endpoint "/things/{a.thing/id}"
                                      :field_mapping {:a.thing/name "$.name"}}
                             :source-ref "vendor-api"}
                     :expected_revision 0}
                    {:workspace "demo" :action :create :type :resolver :id "row_by_id"
                     :entry {:id "row_by_id" :input [:b.row/id]
                             :output [:b.row/label] :result_type "single"
                             :source {:type "sql"
                                      :query "SELECT vend_name FROM vendors WHERE vend_no = :id"}
                             :source-ref "demo-db"}
                     :expected_revision 0}]))
  (let [model (pool/as-dialect (pool/db *conn*))]
    ;; The sources are not selected: a source id is a bare string and no
    ;; selector reaches one (scope-projection-v1 §1). They arrive because the
    ;; resolvers above reference them — the projection's own closure (§2.4–§2.8).
    (accept! (graph/register-graph!
              *conn* {:workspace "demo" :name "gs"
                      :members (graph/expand-selectors model ["a.*" "b.*"])})))
  (let [d (describable-by-id (:sources (as *editor* #(catalog-data nil "demo/gs"))))]
    (is (true? (get d "demo-db")))
    (is (false? (get d "vendor-api")))))

(deftest the-workspace-scope-shows-unreferenced-sources
  ;; A source id is a bare string with no namespace segment
  ;; (`source-owner`), and a source nothing references is `used` nowhere —
  ;; so without a rule of its own the workspace scope would hide it
  ;; everywhere but the Sources register. That is exactly the "invisible
  ;; with nobody able to fix it" the scope exists to prevent
  ;; (`workspace-model`), so the workspace scope shows the whole register.
  (accept! (pool/execute-action
            *conn* {:workspace "demo" :action :create :type :source :id "fresh-db"
                    :entry {:type "sql" :jdbc_url "jdbc:sqlite::memory:"}
                    :expected_revision 0}))
  (let [sources (:sources (as *editor* #(catalog-data nil "*")))
        fresh   (first (filter #(= "fresh-db" (:id %)) sources))]
    (is (some? fresh)
        "an unreferenced source is visible in the workspace scope")
    (is (true? (:declared? fresh))
        "and :declared? still tells the modeller nothing reads it yet")
    (is (= [] (:used-by fresh)))))

(def ^:private asset-object-data #'bs/asset-object-data)

(deftest an-unowned-source-can-be-opened-for-editing
  ;; The write path rules "unowned is fair game" for sources
  ;; (`writable-here?`); the editor's fetch must agree, or the form refuses
  ;; an edit the pool would accept.
  (accept! (pool/execute-action
            *conn* {:workspace "demo" :action :create :type :source :id "fresh-db"
                    :entry {:type "sql" :jdbc_url "jdbc:sqlite::memory:"}
                    :expected_revision 0}))
  (let [r (as *editor* #(asset-object-data {"type" "source"
                                            "id" (pr-str "fresh-db")}))]
    (is (some? (:object r)) "the unowned source's entry is fetchable")
    (is (true? (:editable? r)))))

;; ---------------------------------------------------------------------------
;; Removal
;; ---------------------------------------------------------------------------

(def ^:private removal-plan   #'bs/removal-plan)
(def ^:private remove-objects! #'bs/remove-objects!)

(deftest removing-nothing-is-refused-rather-than-called-a-success
  (let [r (as *editor* #(remove-objects! {:namespace "nothing.here"}))]
    (is (false? (:removed? r)))
    (is (seq (filter #(= :error (:severity %)) (:findings r))))))

;; ---------------------------------------------------------------------------
;; The catalog is scoped by the graph — terms included
;; ---------------------------------------------------------------------------

(deftest terms-are-selected-by-the-recipe-not-derived-from-attributes
  ;; A term is an independently defined object: it must exist before anything
  ;; can bind it, and `query_model` reports a term nothing binds as a gap to go
  ;; and fix. Deriving the list from the attributes that bind one made both
  ;; impossible — the term could never be created here, and the zero-binding
  ;; gap named an object no screen could open.
  ;; **对象先建，菜谱后写**：菜谱是对象清单（D3.19），前缀在写菜谱的那一刻
  ;; 展开一次。先登记再建对象，展开出来的是一张空清单——那不是 bug，是这条
  ;; 裁定认下的代价，编辑器用「有 N 个对象不在这张图里」把它变成看得见的待办。
  (accept! (pool/execute-actions
            *conn* [{:workspace "demo" :action :create :type :namespace :id "a.thing" :entry {}
                     :expected_revision 0}
                    {:workspace "demo" :action :create :type :namespace :id "b.thing" :entry {}
                     :expected_revision 0}
                    {:workspace "demo" :action :create :type :term :id "gl/bound"
                     :entry {:label "Bound"} :expected_revision 0}
                    {:workspace "demo" :action :create :type :term :id "gl/unbound"
                     :entry {:label "Bound by nobody"} :expected_revision 0}
                    {:workspace "demo" :action :create :type :attribute :id :a.thing/x
                     :entry {:type :string :term "gl/bound"} :expected_revision 0}]))
  (let [model (pool/as-dialect (pool/db *conn*))]
    (accept! (graph/register-graph! *conn* {:workspace "demo" :name "ga"
                                            :members (graph/expand-selectors
                                                      model ["a.*" "gl/*"])}))
    (accept! (graph/register-graph! *conn* {:workspace "demo" :name "gb"
                                            :members (graph/expand-selectors
                                                      model ["b.*"])})))
  (let [ga (as *editor* #(catalog-data nil "demo/ga"))
        gb (as *editor* #(catalog-data nil "demo/gb"))]
    (is (= [["gl/bound" true] ["gl/unbound" false]]
           (mapv (juxt :id :bound?) (:terms ga)))
        "the glossary domain is named by this recipe, so both are in it —
         and each says whether an attribute here binds it")
    (is (= [] (:terms gb))
        "a graph that names no glossary domain contains no terms")
    (testing "and the registry a reference resolves against stays whole"
      ;; Listing what belongs and resolving a reference are different
      ;; questions: a dossier resolving a bound term against the filtered list
      ;; would report it missing whenever a filter was active.
      (is (= 2 (count (:term-index gb))))
      (is (contains? (:term-index gb) "gl/bound")))))

(deftest a-wrong-method-is-refused-in-one-readable-shape
  "`post!` collapsed 22 hand-written method guards that had already drifted
   into two different 405 bodies — EDN for eighteen routes, `text/plain` for
   four.  The client parses EDN, so on those four the refusal for a wrong
   method was itself unreadable.  Pinned here because the drift happened
   silently: nothing failed, the wrong answer was just unparseable."
  (let [replies (atom [])]
    (with-redefs [http/reply-edn! (fn [_ status body & _] (swap! replies conj [status body]))
                  http/request-edn (fn [_] {})]
      (#'bs/post! nil "GET" (fn [_] :never-called))
      (#'bs/post! nil "DELETE" 201 (fn [_] :never-called)))
    (is (= [[405 {:error {:code :method-not-allowed}}]
            [405 {:error {:code :method-not-allowed}}]]
           @replies)
        "every non-POST is refused with the same EDN body, whatever status the
         route would have replied on success")))

(deftest the-form-descriptor-says-which-dialect-update-speaks
  "编辑器给的是整份条目，`:update` 收的是补丁；merge 类型下缺席键**存活**,
   所以编辑器必须把「删掉的键」翻译成显式 nil。它做这个翻译的依据是描述符上的
   `:update`——这条测试钉住那根线：线断了，翻译静默消失，「勾回缺省」又会变成
   保存成功、revision 上涨、值纹丝不动。"
  (let [types (:types (#'bs/schema-data))]
    (is (= :merge (get-in types [:attribute :update])))
    (is (= :replace (get-in types [:resolver :update])))))

(deftest the-token-panel-lists-a-graphs-live-tokens
  "**A governance view that under-reports is worse than none.**

   This panel filtered the ledger by `:grants`, a key the ledger normalises
   away and explicitly never stores (`normalize-issue-request`: 'never
   persists nor returns a `:grants` field'). The filter therefore matched
   nothing and the panel said 'no token has been issued for this graph' over
   live, unrevoked ones — which invites issuing another to replace one that
   was never gone, and leaves the real one unrevoked and unseen.

   Same shape as a dangling CSS variable: reading a retired key yields nil,
   nil filters everything, and nothing anywhere says so."
  (as *owner*
      (fn []
        (let [g "demo/tok"]
          (accept! (graph/register-graph!
                    *conn* {:workspace "demo" :name "tok" :members {}
                            :created-at "2026-08-28T00:00:00Z"}))
          (with-redefs [bs/runtime-parts (constantly {:token ::store})
                        rtoken/all-records
                        (constantly [{:token-id "t1" :graph-id g :version :latest}
                                     {:token-id "t2" :graph-id "demo/other" :version :latest}])]
            (let [ids (set (map :token-id (:tokens (#'bs/self-test-tokens-data))))]
              (is (contains? ids "t1")
                  "a live record for a visible graph must be listed")
              (is (not (contains? ids "t2"))
                  "and one for a graph outside this workspace must not")))))))

(deftest every-css-variable-a-stylesheet-uses-is-declared
  "**A dangling CSS variable fails silently.** `var(--nope)` resolves to
   nothing and the declaration is simply dropped — no console error, no visual
   cue that anything was meant to be there.

   This test exists because that is exactly how it bit: the Recipe panel said
   `background: var(--surface)`, but this theme declares `--panel`. The panel
   was therefore TRANSPARENT, and the rail and blueprint read straight through
   it. It looked like a stacking-context bug and was a spelling mistake. The
   sweep that followed found thirteen more across two stylesheets — borders
   that never drew, error text that was not red.

   Only the no-fallback form is checked: `var(--x, y)` has a ground by
   construction and is a legitimate way to make a value optional."
  (let [css (into {} (for [f ["platform.css" "studio.css"]]
                       [f (slurp (io/resource (str "public/" f)))]))
        all (str/join "\n" (vals css))
        declared (set (map second (re-seq #"(--[a-z0-9-]+)\\s*:" all)))
        dangling (fn [text]
                   (into (sorted-set)
                         (comp (map second) (remove declared))
                         (re-seq #"var\\((--[a-z0-9-]+)\\s*\\)" text)))]
    (doseq [[f text] css]
      (is (empty? (dangling text))
          (str f " uses variables no stylesheet declares — they resolve to "
               "nothing and the declaration is dropped: " (pr-str (dangling text)))))))

(deftest a-whole-entity-joins-a-recipe-in-one-gesture
  "**The unit a modeller acts in is an entity, not an attribute.**

   A recipe is an object list (D3.19) and that is right for the artifact, but
   adding an entity the pool grew later means a namespace plus its attributes
   plus its resolvers. One toggle at a time that was twenty round trips across
   three tabs — and the recipe was half-formed after each one. So the member
   edit takes a LIST, and the candidates answer offers the same pool grouped by
   entity: `:members` on a group is exactly the ids the object tabs would post.

   Pinned because the grouping is a VIEW: if it ever starts computing a
   different membership than the tick-list does, a modeller ticks 'SSO' and
   gets something other than what the SSO rows say."
  (accept! (pool/execute-actions
            *conn* [{:workspace "demo" :action :create :type :namespace :id "e.sso" :entry {:label "SSO"}
                     :expected_revision 0}
                    {:workspace "demo" :action :create :type :attribute :id :e.sso/order_no
                     :entry {:type :integer :identity? true} :expected_revision 0}
                    {:workspace "demo" :action :create :type :attribute :id :e.sso/total
                     :entry {:type :decimal} :expected_revision 0}]))
  (let [g (:graph (accept! (graph/register-graph!
                            *conn* {:workspace "demo" :name "ge"
                                    :members {} :created-at "2026-08-28T00:00:00Z"})))
        qn (:graph/qualified-name g)]
    (as *owner*
        (fn []
          (let [before (#'bs/recipe-candidates {"graph" qn})
                sso (first (filter #(= "e.sso" (:entity %)) (:entities before)))]
            (is (some? sso) "the pool is offered grouped by entity")
            (is (= :out (:state sso)) "nothing of it is in this recipe yet")
            (is (= 0 (:have sso)))

            (testing "one gesture per kind, each carrying a LIST"
              (doseq [[k ids] (:members sso) :when (seq ids)]
                (#'bs/update-graph-members! {:graph qn :kind (name k) :ids ids :in? true})))

            (let [after (#'bs/recipe-candidates {"graph" qn})
                  sso' (first (filter #(= "e.sso" (:entity %)) (:entities after)))]
              (is (= :in (:state sso')) "the whole entity is now in")
              (is (= (:count sso') (:have sso')))

              (testing "and the grouping agrees with the tick-list — it is a
                        view over the same membership, not a second answer"
                (is (every? :in? (filter #(str/starts-with? (:id %) ":e.sso/")
                                         (get-in after [:groups :attributes]))))
                (is (some #(and (= "\"e.sso\"" (:id %)) (:in? %))
                          (get-in after [:groups :namespaces]))))

              (testing "partial is its own state — the one that looks present
                        and answers nothing"
                (#'bs/update-graph-members! {:graph qn :kind "attributes"
                                             :ids [(pr-str :e.sso/total)] :in? false})
                (let [p (first (filter #(= "e.sso" (:entity %))
                                       (:entities (#'bs/recipe-candidates {"graph" qn}))))]
                  (is (= :partial (:state p)))))))))))

(deftest a-recipe-is-an-object-list-not-a-prefix-rule
  "D3.19 在 app 这一侧的样子：菜谱存的是**展开后的对象清单**，前缀只是写入时
   的一次便利。后来进池的对象**不会自动入伙**——这是那条裁定认下的代价,
   钉在这里，免得哪天有人「修好」它又变回静默跟随。"
  (accept! (pool/execute-actions
            *conn* [{:workspace "demo" :action :create :type :namespace :id "d.one" :entry {}
                     :expected_revision 0}
                    {:workspace "demo" :action :create :type :attribute :id :d.one/a
                     :entry {:type :string} :expected_revision 0}]))
  (let [g (:graph (accept! (graph/register-graph!
                            *conn* {:workspace "demo" :name "gd"
                                    :members (graph/expand-selectors
                                              (pool/as-dialect (pool/db *conn*)) ["d.*"])
                                    :created-at "2026-08-27T00:00:00Z"})))]
    (is (= #{"d.one"} (:namespaces (:graph/members g))))
    (is (= #{:d.one/a} (:attributes (:graph/members g)))
        "存下来的是对象，不是 `d.*`")
    (is (= "2026-08-27T00:00:00Z" (:graph/created-at g)))

    (testing "后来加的属性不在清单里"
      (accept! (pool/execute-action
                *conn* {:workspace "demo" :action :create :type :attribute :id :d.one/b
                        :entry {:type :string} :expected_revision 0}))
      (let [g' (graph/graph (pool/db *conn*) "demo/gd")]
        (is (= #{:d.one/a} (:attributes (:graph/members g')))
            "菜谱不跟着池走——编辑器要把这件事显示成待办，而不是替人决定")))))

;; ---------------------------------------------------------------------------
;; Connect: the same card, a connector that is not sql
;; ---------------------------------------------------------------------------

(def ^:private studio-home #'bs/studio-home)

(defn- recording-validate
  "`spi/validate-source` with every config it was asked about kept.

   What this path hands the validator is the interesting part: a connection on
   its own is not a shape any contract can judge, so connect merges a probe
   operation block in — and a `SELECT 1` sent to api-v1 would be refused for a
   key the modeller never typed."
  [seen]
  (let [real spi/validate-source]
    (fn [config] (swap! seen conj config) (real config))))

(deftest an-api-connection-is-judged-as-api-and-never-with-select-1
  (let [seen (atom [])
        r (with-redefs [spi/validate-source (recording-validate seen)
                        ;; No HTTP from a test. What the health check *says* is
                        ;; the interesting part below; that it can dial is the
                        ;; connector's own suite.
                        spi/health-check (fn [_ _] {:status :ok})]
            (as *editor* #(connect-source! {:id "orders-api" :type "api"
                                            :base_url "https://api.example.com"
                                            ;; `${HOME}` rather than a name
                                            ;; invented here: api-v1 requires a
                                            ;; credential reference to *resolve*
                                            ;; at write time, so a variable this
                                            ;; JVM does not have is refused
                                            ;; before any of this is reached.
                                            :auth {:type "api_key" :value "${HOME}"}
                                            :health_endpoint "/v1/ping"
                                            :label "Orders" :prefix "cis.demo"})))
        entry (:pool/entry (pool/object (pool/db *conn*) :source "orders-api"))]
    (is (true? (:connected? r)))
    (is (seq @seen))
    (is (every? #(= {:method "GET" :endpoint "/"}
                    (select-keys % [:method :endpoint]))
                @seen)
        "the shell that makes the entry judgeable is api-v1's, not sql's")
    (is (not-any? :query @seen))
    (is (= {:type "api" :label "Orders"
            :base_url "https://api.example.com"
            :auth {:type "api_key" :value "${HOME}"}
            :health_endpoint "/v1/ping"}
           entry)
        "and the connection keys reach the pool exactly as declared")
    (is (false? (:describable? r))
        "api brings no describe-schema, so the reply says so instead of erroring")
    (is (nil? (:relations r)))))

(deftest an-api-connection-that-does-not-answer-points-at-base-url
  ;; A finding is only actionable if it names the box the modeller filled in.
  ;; `:jdbc_url` on an api source names a key that is not on the form.
  (let [r (with-redefs [spi/health-check (fn [_ _] {:status :error
                                                    :error/category :connection})]
            (as *editor* #(connect-source! {:id "down-api" :type "api"
                                            :base_url "https://api.example.com"
                                            :prefix "cis.demo"})))]
    (is (false? (:connected? r)))
    (is (= [:connector.source/unreachable] (map :code (:findings r))))
    (is (= [[:source :base_url]] (map :path (:findings r))))
    (is (empty? (filter #(= "down-api" (:pool/id %)) (objects-of :source)))))
  (testing "and an unresolved variable names it too"
    (let [r (with-redefs [spi/health-check (fn [_ _] {:status :error
                                                      :missing-vars ["ORDERS_API_KEY"]})]
              (as *editor* #(connect-source! {:id "unset-api" :type "api"
                                              :base_url "https://api.example.com"
                                              :prefix "cis.demo"})))]
      (is (= [:connector.source/missing-key] (map :code (:findings r))))
      (is (= [[:source :base_url]] (map :path (:findings r)))))))

(deftest an-api-connection-puts-nothing-in-this-machines-credential-store
  ;; Not an accident of which keys happen to be sent: api-v1's credential keys
  ;; take one `${VAR}` and a literal is refused on write, so there is no value
  ;; to keep — and the nested `[:auth :value]` is not a key this store can
  ;; name in the first place (owner ruling 2026-08-28, api-v1 `:auth`).
  (with-redefs [spi/health-check (fn [_ _] {:status :ok})]
    (as *editor* #(connect-source! {:id "kept-api" :type "api"
                                    :base_url "https://api.example.com"
                                    :auth {:type "api_key" :value "${HOME}"}
                                    ;; sql's keys, sent by a form that had not
                                    ;; cleared them. They are neither stored
                                    ;; nor written.
                                    :username "reader" :password "hunter2"
                                    :prefix "cis.demo"})))
  (let [entry (:pool/entry (pool/object (pool/db *conn*) :source "kept-api"))]
    (is (empty? (credentials/holds (studio-home) "kept-api")))
    (is (= #{:type :base_url :auth} (set (keys entry))))
    (is (not (str/includes? (pr-str entry) "hunter2")))))


;; ---------------------------------------------------------------------------
;; 8.4.9 ①：Builder 监听口绑在哪
;; ---------------------------------------------------------------------------

(deftest the-data-listener-is-independently-configurable
  (is (= {:host "127.0.0.1" :port 9632} (bs/data-plane-binding {})))
  (is (= {:host "0.0.0.0" :port 19632}
         (bs/data-plane-binding {"OPEN_ATLAS_STUDIO_DATA_HOST" "0.0.0.0"
                                 "OPEN_ATLAS_STUDIO_DATA_PORT" "19632"})))
  (is (= {:host "192.0.2.10" :port 9632}
         (bs/data-plane-binding {"OPEN_ATLAS_STUDIO_DATA_HOST" " 192.0.2.10 "})))
  (is (= {:host "127.0.0.1" :port 9632}
         (bs/data-plane-binding {"OPEN_ATLAS_STUDIO_BUILDER_HOST" "0.0.0.0"}))))

(deftest the-data-listener-speaks-https-with-a-keystore
  (testing "the Builder's keystore covers the data plane when it has none of its own"
    (is (= {:host "127.0.0.1" :port 9632 :tls {:keystore "/b.p12" :password "pw"}}
           (bs/data-plane-binding {"OPEN_ATLAS_STUDIO_BUILDER_TLS_KEYSTORE" "/b.p12"
                                   "OPEN_ATLAS_STUDIO_BUILDER_TLS_PASSWORD" "pw"}))))
  (testing "its own keystore wins"
    (is (= {:keystore "/d.p12" :password "dpw"}
           (:tls (bs/data-plane-binding {"OPEN_ATLAS_STUDIO_BUILDER_TLS_KEYSTORE" "/b.p12"
                                         "OPEN_ATLAS_STUDIO_BUILDER_TLS_PASSWORD" "pw"
                                         "OPEN_ATLAS_STUDIO_DATA_TLS_KEYSTORE" "/d.p12"
                                         "OPEN_ATLAS_STUDIO_DATA_TLS_PASSWORD" "dpw"})))))
  (testing "a keystore without its password is refused"
    (is (= [:data.listener/tls-password-missing]
           (map :code (:findings (bs/data-plane-binding
                                  {"OPEN_ATLAS_STUDIO_DATA_TLS_KEYSTORE" "/d.p12"})))))))

(deftest the-builder-listener-stays-on-loopback-unless-told-otherwise
  (testing "没有配置 = 只有这台机器。OMA 够得着 Builder 是有人配出来的，
            不是升级带出来的"
    (is (= {:host "127.0.0.1" :port 9631} (bs/builder-binding {:port 9631})))
    (is (= {:host "localhost" :port 9631} (bs/builder-binding {:host "localhost" :port 9631}))
        "localhost 也是回环——它是人会打的那个字，不是一次暴露")))

(deftest a-reachable-builder-may-speak-plain-http
  (testing "owner 裁定 2026-08-29-b（8.4.9 ①修订）：任何绑定都可以走明文，
            原「非回环必须 TLS」的拒绝取消。后果记录在裁定处：明文的
            workspace token 对路径上的任何东西可抄可重放"
    (is (= {:host "0.0.0.0" :port 9631}
           (bs/builder-binding {:host "0.0.0.0" :port 9631}))
        "无 keystore 的对外绑定成立，不再是拒绝")))

(deftest a-keystore-without-a-password-is-refused-rather-than-attempted
  (testing "「TLS 没起来」不许是读日志才发现的事"
    (is (= [:builder.listener/tls-password-missing]
           (map :code (:findings (bs/builder-binding
                                  {:host "studio.example.com" :port 443
                                   :keystore "/k.p12"})))))))

(deftest a-configured-keystore-turns-the-builder-into-https
  (is (= {:host "studio.example.com" :port 443
          :tls {:keystore "/k.p12" :password "pw"}}
         (bs/builder-binding {:host "studio.example.com" :port 443
                              :keystore "/k.p12" :password "pw"}))))

;; ---------------------------------------------------------------------------
;; Membership leaves a trail (8.10 P6)
;;
;; Before this, promoting, demoting or withdrawing someone's access to a
;; workspace was the one privileged act that recorded nothing anywhere.
;; ---------------------------------------------------------------------------

(defn- with-audit
  "Run `f` with the audit trail anchored in a temp dir; → the events recorded."
  [f]
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "studio-audit-" (into-array java.nio.file.attribute.FileAttribute [])))]
    (try
      (obs/audit-configure! {:recent 50 :dir dir})
      (obs/audit-clear!)
      (f)
      {:events (vec (reverse (obs/audit-recent)))   ; recent is newest-first
       :dir dir}
      (finally (obs/audit-configure! {:recent 50 :dir nil})
               (obs/audit-clear!)))))

(deftest adding-a-member-is-recorded-with-who-did-it
  (let [{:keys [events]} (with-audit
                           #(as *owner* (fn [] (add-member! {:workspace "demo"
                                                             :email "outsider@example.com"
                                                             :role :member}))))
        e (first (filter #(= :workspace/member-add (:event %)) events))]
    (is (some? e) "the add produced an audit event")
    (is (= :ok (:outcome e)))
    (is (= "demo" (:workspace e)))
    (is (= "outsider@example.com" (:member e)))
    (is (= :member (:role e)))
    (is (nil? (:previous-role e)) "an add has no previous role")
    (testing "the two-part principal chain (audit-event-v1 §4)"
      (is (= (:principal *owner*) (get-in e [:actor :on-behalf-of])))
      (is (some? (get-in e [:actor :service]))  "the service half is never empty")
      (is (= :verified (get-in e [:actor :asserted-by]))))))

(deftest changing-a-role-is-not-recorded-as-an-add
  (let [{:keys [events]} (with-audit
                           #(as *owner* (fn [] (add-member! {:workspace "demo"
                                                             :email "editor@example.com"
                                                             :role :member}))))
        e (first (filter #(= :workspace/member-role-change (:event %)) events))]
    (is (some? e) "an existing member's role change is its own event")
    (is (= :editor (:previous-role e)) "what they were is knowable afterwards")
    (is (= :member (:role e)))))

(deftest withdrawing-access-is-recorded
  (let [{:keys [events]} (with-audit
                           #(as *owner* (fn [] (remove-member! {:workspace "demo"
                                                                :email "editor@example.com"}))))
        e (first (filter #(= :workspace/member-remove (:event %)) events))]
    (is (some? e))
    (is (= :ok (:outcome e)))
    (is (= "editor@example.com" (:member e)))))

(deftest a-refused-change-is-recorded-too
  ;; S5.3: the refusal trail is the data source for «who is knocking on which
  ;; door». A trail of successes only cannot answer that.
  (let [{:keys [events]} (with-audit
                           #(caught (fn [] (as *editor*
                                               (fn [] (add-member! {:workspace "demo"
                                                                    :email "outsider@example.com"
                                                                    :role :member}))))))
        e (first (filter #(= :workspace/member-add (:event %)) events))]
    (is (some? e) "the refusal is audited, not swallowed")
    (is (= :denied (:outcome e)))
    (is (= :workspace-owner-required (:reason e)) "machine-readable, and no values")
    (is (= (:principal *editor*) (get-in e [:actor :on-behalf-of])))))

(deftest the-trail-can-be-read-back-by-who-did-it
  (let [{:keys [dir]} (with-audit
                        #(as *owner* (fn [] (add-member! {:workspace "demo"
                                                          :email "outsider@example.com"
                                                          :role :member}))))
        filed (obs/audit-for-principal dir (:principal *owner*))]
    (is (= 1 (count filed))
        "the subject index answers «what did this person do», and survives rotation")
    (is (= :workspace/member-add (:event (first filed))))))

;; ---------------------------------------------------------------------------
;; The doors leave a trail too (audit-event-v1 §3 :auth/failed, :token/*)
;;
;; S5.3's refusal trail was complete for membership and empty for the two
;; things an attacker actually knocks on: the login form and the workspace-key
;; ledger. What follows pins the shapes, and pins hardest on what must NOT be
;; in them.
;; ---------------------------------------------------------------------------

(def ^:private audit-auth-failure! #'bs/audit-auth-failure!)
(def ^:private issue-workspace-token!  #'bs/issue-workspace-token!)
(def ^:private revoke-workspace-token! #'bs/revoke-workspace-token!)

(defn- login!
  "One `handle-login!` against the real identity store, with the transport
   stubbed the way `a-wrong-method-is-refused-in-one-readable-shape` stubs it.
   → the replies it wrote."
  [email password]
  (let [replies (atom [])]
    (with-redefs [http/request-edn (fn [_] {:email email :password password})
                  http/reply-edn! (fn [_ status body & _] (swap! replies conj [status body]))]
      (auth/handle-login! *conn* nil (partial audit-auth-failure! :builder-http)))
    @replies))

(deftest a-failed-login-is-recorded-at-the-builder-http-door
  (let [{:keys [events]} (with-audit #(login! "owner@example.com" "not-the-password"))
        failures (filter #(= :auth/failed (:event %)) events)
        e (first failures)]
    (is (= 1 (count failures)) "exactly one event per attempt (audit-event-v1 §8)")
    (is (= :builder-http (:entry e)) "which door was knocked on")
    (is (= :denied (:outcome e)))
    (is (= :auth/invalid-credentials (:reason e)))
    (is (some? (get-in e [:actor :service])) "the service half is never empty")
    (is (nil? (get-in e [:actor :on-behalf-of]))
        "a credential that failed establishes nobody — an attempted name is not a principal")
    (testing "no credential material anywhere in the event (audit-event-v1 §5)"
      (let [printed (pr-str e)]
        (is (not (str/includes? printed "not-the-password")))
        (is (not (str/includes? printed "owner@example.com")))))))

(deftest a-failed-login-does-not-say-which-half-was-wrong
  ;; `authenticate-password` answers nil to both, and the trail must not undo
  ;; that: an audit reader less privileged than the identity store would
  ;; otherwise learn from it which addresses exist.
  (let [wrong-password (first (filter #(= :auth/failed (:event %))
                                      (:events (with-audit #(login! "owner@example.com" "nope")))))
        no-such-user   (first (filter #(= :auth/failed (:event %))
                                      (:events (with-audit #(login! "ghost@example.com" "nope")))))
        stable #(dissoc % :at :request-id)]
    (is (= (stable wrong-password) (stable no-such-user))
        "the two refusals are one event, byte for byte")))

(deftest a-failed-login-still-answers-401
  (is (= [401] (map first (login! "owner@example.com" "not-the-password")))
      "auditing changed no response"))

(deftest a-successful-login-adds-no-event
  (let [{:keys [events]} (with-audit #(login! "owner@example.com" "a-safe-password"))]
    (is (= [200] (map first (login! "owner@example.com" "a-safe-password"))))
    (is (empty? (filter #(= :auth/failed (:event %)) events))
        "a success is not a refusal")
    (is (empty? events) "and this change added no event to the success path")))

(deftest only-a-system-admin-can-manage-studio-users
  (testing "a workspace editor cannot create, reset, or remove another account"
    (doseq [attempt [#(#'bs/restore-studio-user! {:user-id (:id *outsider*)})
                     #(#'bs/update-studio-user! {:user-id (:id *outsider*) :name "Changed" :email "changed@example.com" :role :admin})
                     #(create-studio-user! {:email "new@example.com" :name "New"
                                            :password "a-safe-password"})
                     #(reset-user-password! {:user-id (:id *outsider*)
                                             :new-password "replacement-password"})
                     #(remove-studio-user! {:user-id (:id *outsider*)})
                     ;; Doc 101 §8.16.9 item 3: linking is an administrator's act.
                     #(#'bs/set-studio-user-sso-email! {:user-id (:id *outsider*)
                                                        :email "outsider@corp.example.com"})]]
      (is (= :studio-admin-required
             (:code (ex-data (try (as *editor* attempt) nil
                                  (catch clojure.lang.ExceptionInfo e e))))))))
  (testing "the system administrator can create a user"
    (is (= :ok (:status
                (as *owner*
                    #(create-studio-user! {:email "new@example.com" :name "New"
                                           :password "a-safe-password"})))))))

(defn- with-token-home
  "Run `f` with the workspace-token ledger in a temp home and a test pepper."
  [f]
  (let [home (str (System/getProperty "java.io.tmpdir") "/oa-wt-audit-" (random-uuid))
        before (:secrets @@studio-state)]
    (try
      (swap! @studio-state assoc :secrets {:workspace-token-pepper "test-pepper"})
      (with-redefs-fn {#'bs/studio-home (constantly home)} f)
      (finally
        (swap! @studio-state assoc :secrets before)
        (doseq [f* (reverse (file-seq (io/file home)))] (.delete ^java.io.File f*))))))

(deftest issuing-a-workspace-token-is-recorded-like-the-data-plane-ledger
  (let [issued (atom nil)
        {:keys [events]}
        (with-audit
          #(with-token-home
             (fn [] (as *owner*
                       (fn [] (reset! issued
                                      (issue-workspace-token! {:scope [:read :write]
                                                               :label "an agent's key"
                                                               :expires-in-days 30})))))))
        e (first (filter #(= :token/issue (:event %)) events))]
    (is (some? e) "minting a key that writes the model is not a silent act")
    (is (= :ok (:outcome e)))
    (is (= :builder-http (:entry e)))
    (is (= (:token-id (:record @issued)) (:token-id e)) "and it is findable by that id")
    (is (= (:principal *owner*) (get-in e [:actor :on-behalf-of]))
        "the operator who asked, not the key itself")
    (is (= {:workspace "demo" :scope [:read :write]
            :expires-at (:expires-at (:record @issued))}
           (:authorization e))
        "the grant summary — what it opens and until when (§2.2)")
    (testing "no secret and no digest (S5.2 / audit-event-v1 §5)"
      (let [printed (pr-str e)]
        (is (not (str/includes? printed (:token @issued))))
        (is (not (str/includes? printed "digest")))))))

(deftest revoking-a-workspace-token-is-recorded
  (let [issued (atom nil)
        {:keys [events]}
        (with-audit
          #(with-token-home
             (fn [] (as *owner*
                       (fn []
                         (reset! issued (issue-workspace-token! {:scope [:read]
                                                                 :label "a short-lived key"
                                                                 :expires-in-days 1}))
                         (revoke-workspace-token! {:token-id (:token-id (:record @issued))}))))))
        e (first (filter #(= :token/revoke (:event %)) events))]
    (is (some? e) "withdrawing access is as auditable as granting it")
    (is (= :ok (:outcome e)))
    (is (= (:token-id (:record @issued)) (:token-id e)))
    (is (= (:principal *owner*) (get-in e [:actor :on-behalf-of])))
    (is (= [:read] (:scope (:authorization e)))
        "what the withdrawn key opened is still readable afterwards")
    (is (not (str/includes? (pr-str e) (:token @issued))))))

(deftest a-token-event-can-be-read-back-by-its-id
  (let [issued (atom nil)
        {:keys [dir]}
        (with-audit
          #(with-token-home
             (fn [] (as *owner*
                       (fn [] (reset! issued (issue-workspace-token! {:scope [:read]
                                                                      :label "k"
                                                                      :expires-in-days 1})))))))]
    (is (= [:token/issue]
           (map :event (obs/audit-for-subject dir :token (:token-id (:record @issued)))))
        "audit-event-v1 §6: «what happened to THIS key» is an index question")))

;; ---------------------------------------------------------------------------
;; 谁看得到审计（8.10.6 的可见性裁定）
;;
;; 这是安全属性不是 UI 选项，所以过滤在服务端，并且**在这里被盯着**。
;; ---------------------------------------------------------------------------

(deftest an-events-workspace-comes-from-whatever-names-it
  (testing "限定名的首段就是 workspace（D3.10）"
    (is (= "demo" (event-workspace {:workspace "demo"})))
    (is (= "demo" (event-workspace {:graph "demo/orders"})))
    (is (= "demo" (event-workspace {:authorization {:graph-id "demo/orders"}})))
    (is (nil? (event-workspace {:event :access/query :outcome :denied}))
        "认证失败没有归属——这正是它只给 admin 看的原因")))

(deftest an-owner-sees-their-own-workspace-and-nothing-else
  (let [scope {:admin? false :workspaces #{"demo"}}
        events [{:workspace "demo" :event :workspace/member-add}
                {:workspace "other" :event :workspace/member-add}
                {:graph "demo/orders" :event :access/query}
                {:graph "other/x" :event :access/query}]]
    (is (= [{:workspace "demo" :event :workspace/member-add}
            {:graph "demo/orders" :event :access/query}]
           (visible-events scope events)))))

(deftest an-unattributable-event-is-admin-only
  ;; 一次被拒的读没解析出 graph，也就没有 workspace。把它给 owner 看，
  ;; 等于把别的租户「谁在敲门」的情报给了他——泄漏的是这个，不是功能。
  (let [refusal {:event :access/query :outcome :denied :reason :token.authz/expired}]
    (is (= [] (visible-events {:admin? false :workspaces #{"demo"}} [refusal])))
    (is (= [refusal] (visible-events {:admin? true} [refusal])))))

(deftest events-read-back-from-the-stream-keep-their-vocabulary
  ;; 流水是 JSON：重启后回填的那一批，:outcome 是 "ok" 而不是 :ok。不还原的话
  ;; 页面上它们会静静地少掉状态徽章——数据没少，看起来却像少了。
  (let [from-disk {:event "access/query" :outcome "denied" :reason "token.authz/expired"
                   :entry "data-eql" :member "alice@example.com"}
        back (rekeywordize from-disk)]
    (is (= :access/query (:event back)))
    (is (= :denied (:outcome back)))
    (is (= :token.authz/expired (:reason back)))
    (is (= :data-eql (:entry back)))
    (testing "只还原词表字段——把人的邮箱变成 keyword 是另一种错"
      (is (= "alice@example.com" (:member back)))))
  (testing "内存环里本来就是 keyword 的，原样不动"
    (is (= :ok (:outcome (rekeywordize {:outcome :ok}))))))

(deftest a-statement-receipt-loses-its-text-before-it-reaches-the-audit
  ;; 回执里有 :sql 与 :values——语句原文与绑定值。§5 说它们不进审计,
  ;; 而这条纪律靠白名单执行：回执哪天多一个字段，白名单什么都不会多带。
  (let [receipt {:sql "SELECT * FROM film WHERE film_id IN (?)"
                 :values [133]
                 :elapsed-ms 12 :rows 7
                 :resolver-id "pg_dvd3_film_film_actor" :source-type "sql"
                 :some-future-field "whatever it turns out to be"}
        summary (statements-summary [receipt])
        one (first (:by-resolver summary))]
    (is (= 1 (:count summary)))
    (is (= 7 (:rows summary)) "行数是计数，可以记")
    (is (= {:resolver-id "pg_dvd3_film_film_actor" :source-type "sql"
            :elapsed-ms 12 :rows 7}
           one)
        "只有名称、类型、时延、计数——一个字段都不多")
    (is (nil? (:sql one)))
    (is (nil? (:values one)))
    (is (nil? (:some-future-field one)) "白名单：新字段默认不进"))
  (testing "没发过语句就没有这一节，而不是一个空壳"
    (is (nil? (statements-summary [])))))

(deftest the-asked-for-keys-are-names-with-no-values
  (let [shape (eql-shape-of {:entity {:pg.film/film_id 133}
                             :query [:pg.film/title
                                     {:pg.film/actors [:pg.actor/name]}]})]
    (is (= [:pg.film/film_id] (:entity-keys shape)))
    (is (= [:pg.actor/name :pg.film/actors :pg.film/title] (:query-keys shape)))
    (testing "实体的键在，值（133）不在"
      (is (not (str/includes? (pr-str shape) "133"))))))

;; ---------------------------------------------------------------------------
;; Model changes reach the unified ledger too (E3, `:pool/mutation`)
;;
;; Two ledgers with no key in common could not answer "which change put this
;; field in the graph, and who approved it". What follows pins the join key —
;; `:mutation-id` — and pins hardest on the two things that would make the
;; answer wrong: an event per CALL rather than per object, and an entrance
;; that goes unrecorded.
;; ---------------------------------------------------------------------------

(def ^:private audit-pool-mutations! #'bs/audit-pool-mutations!)

(defn- pool-events [events]
  (vec (filter #(= :pool/mutation (:event %)) events)))

(deftest a-ui-write-is-recorded-in-the-unified-ledger
  (let [{:keys [events]}
        (with-audit #(as *editor*
                         (fn [] (write-workspace-asset!
                                 {:workspace "demo" :action :create :type :source :id "new-db"
                                  :entry {:type "sql" :jdbc_url "${NEW_DB_URL}"}
                                  :expected_revision 0}))))
        [e & more] (pool-events events)]
    (is (some? e) "the write produced an audit event")
    (is (empty? more) "exactly one — one action, one event (§8)")
    (is (= :ok (:outcome e)))
    (is (= :builder-http (:entry e)) "the UI is the Builder's HTTP door (D3.12)")
    (is (= :ui (:entrypoint e)) "and the pool ledger's own word for it rides along")
    (is (= [:source "new-db"] (:object e)))
    (is (= :create (:action e)))
    (is (= 0 (:revision-before e)) "0 is «did not exist», the same word :create uses")
    (is (= 1 (:revision-after e)))
    (testing "the two-part principal chain (§4)"
      (is (= (:principal *editor*) (get-in e [:actor :on-behalf-of])))
      (is (some? (get-in e [:actor :service])))
      (is (= :verified (get-in e [:actor :asserted-by]))))
    (testing "the join key names a row in the pool ledger — E3's whole point"
      (is (string? (:mutation-id e)))
      (is (= [:source "new-db"]
             (:mutation/object (first (filter #(= (:mutation-id e) (:mutation/id %))
                                              (pool/mutations (pool/db *conn*))))))))))

(deftest one-call-touching-three-objects-produces-three-events
  ;; §8 «one action, one event». A batch is several changes, and reporting it
  ;; as one would make «how many objects did this person change» answer wrong.
  (let [prov {:entrypoint :ui :actor (:principal *editor*)}
        {:keys [events]}
        (with-audit
          #(audit-pool-mutations!
            prov
            (pool/execute-actions
             *conn*
             [{:workspace "demo" :action :create :type :namespace :id "e3.batch"
               :entry {:label "批"} :expected_revision 0}
              {:workspace "demo" :action :create :type :attribute :id :e3.batch/a
               :entry {:type :string} :expected_revision 0}
              {:workspace "demo" :action :create :type :attribute :id :e3.batch/b
               :entry {:type :string} :expected_revision 0}]
             prov)))
        es (pool-events events)]
    (is (= 3 (count es)))
    (is (= [[:namespace "e3.batch"] [:attribute :e3.batch/a] [:attribute :e3.batch/b]]
           (map :object es))
        "in the order the actions applied")
    (is (= 3 (count (distinct (map :mutation-id es))))
        "three rows in the pool ledger, three keys pointing at them")))

(deftest a-refused-write-is-recorded-as-denied
  (let [{:keys [events]}
        (with-audit
          #(caught (fn [] (as *editor*
                              (fn [] (write-workspace-asset!
                                      {:workspace "demo" :action :update :type :source :id "no-such-db"
                                       :entry {:type "sql"} :expected_revision 3}))))))
        [e] (pool-events events)]
    (is (some? e) "a refusal is audited, not swallowed (S5.3)")
    (is (= :denied (:outcome e)))
    (is (= :pool.action/unknown-entity (:reason e)) "machine-readable, no values")
    (is (= [:source "no-such-db"] (:object e)) "what was attempted is knowable")
    (is (nil? (:mutation-id e)) "nothing was written, so there is no row to point at")))

(deftest the-event-carries-no-part-of-the-entry
  ;; §5, the same discipline finding-v1 §5 states: a source entry holds a
  ;; :jdbc_url, and an attribute entry holds a label. Names and numbers only.
  (let [{:keys [events]}
        (with-audit #(as *editor*
                         (fn [] (write-workspace-asset!
                                 {:workspace "demo" :action :create :type :source :id "secret-db"
                                  :entry {:type "sql"
                                          :jdbc_url "jdbc:postgresql://host/db?password=hunter2"
                                          :label "机密连接"}
                                  :expected_revision 0}))))
        s (pr-str (pool-events events))]
    (is (str/includes? s "secret-db") "the name is recorded")
    (is (not (str/includes? s "hunter2")))
    (is (not (str/includes? s "jdbc:")))
    (is (not (str/includes? s "机密连接")))))

(deftest the-agent-door-is-recorded-as-its-own-door
  ;; D3.12 keeps the two doors apart; an MCP write filed as :builder-http
  ;; would give «which door did this come through» a false answer, which is
  ;; the reason S5.3 audits the entrance at all.
  (let [{:keys [events]}
        (with-audit
          #(audit-pool-mutations!
            {:entrypoint :mcp :token-id "wt-e3" :actor {:issuer "studio" :subject "agent"}}
            (pool/execute-action *conn*
                                 {:workspace "demo" :action :create :type :namespace :id "e3.mcp"
                                  :entry {:label "agent"} :expected_revision 0}
                                 {:entrypoint :mcp :token-id "wt-e3"})))
        [e] (pool-events events)]
    (is (= :builder-mcp (:entry e)))
    (is (= :mcp (:entrypoint e)))
    (is (= {:issuer "studio" :subject "agent"} (get-in e [:actor :on-behalf-of])))))

(deftest an-internal-write-borrows-no-door
  ;; A write with no provenance (bootstrap, migration) is `:unattributed` in
  ;; the pool ledger; in this one it is `:internal`. Filing it as a door
  ;; somebody knocked on would put a caller in the record where there was none.
  (let [{:keys [events]}
        (with-audit #(audit-pool-mutations!
                      nil
                      (pool/execute-action *conn*
                                           {:workspace "demo" :action :create :type :namespace
                                            :id "e3.internal" :entry {} :expected_revision 0})))
        [e] (pool-events events)]
    (is (= :internal (:entry e)))
    (is (= :unattributed (:entrypoint e)))
    (is (nil? (get-in e [:actor :on-behalf-of])) "no person to name")
    (is (some? (get-in e [:actor :service])) "the service half is never empty (§4)")))

(deftest the-same-sentence-over-the-route-twice-writes-one-resolver
  "0.2 acceptance defect 13 (doc 84 §8.4.25). `add_thing`, applied twice with
   byte-identical arguments over `/api/studio/intent`, used to leave a SECOND
   by-key resolver producing the same attributes — after which the graph could
   not be cut at all (`:cut.structure/multiple-producers` on every one of
   them). The second call must write nothing and say so."
  (let [qn   (graph-named! "twice")
        args {:graph qn :name "vendors" :from "vendors" :source "demo-db"
              :namespace "cis.twice" :identity "vend_no" :apply true}
        one  (as *editor* #(intent-tool! {:tool "add_thing" :args args}))
        pool-after (fn [] (set (map (juxt :pool/type :pool/id) (pool/objects (pool/db *conn*)))))
        before (pool-after)
        two  (as *editor* #(intent-tool! {:tool "add_thing" :args args}))]
    (is (true? (:written? one)) (pr-str (:findings one)))
    (is (empty? (filter #(= :error (:severity %)) (:findings two))) (pr-str (:findings two)))
    (is (= [] (:actions two)) "the repeat plans no pool action")
    (is (= ["already said — nothing to write"] (:summary two)))
    (is (= before (pool-after)) "and the pool is byte-identical to before it")
    (is (= 1 (count (filter #(clojure.string/starts-with? (str (:pool/id %)) "cis_twice")
                            (filter #(= :resolver (:pool/type %))
                                    (pool/objects (pool/db *conn*))))))
        "one sentence, one resolver")))

(deftest duplicate-preview-is-read-only-and-rejects-stale-and-reused-plans
  (let [source (accept! (graph/register-graph! *conn* {:workspace "demo" :name "plan-source" :members {} :created-at "2026-09-15T00:00:00Z"}))
        id (get-in source [:graph :graph/id])
        doc (semantic/empty-document "demo" id "Plan source")
        provenance {:entrypoint :ui :actor (:principal *owner*)}
        req {:graph "demo/plan-source" :name "plan-copy" :namespace "sandbox.plan"}
        plan! #(as *owner* (fn [] (#'bs/duplicate-graph-plan req)))
        apply! #(as *owner* (fn [] (#'bs/apply-duplicate-graph! (assoc req :plan-token %))))]
    (is (= 1 (:revision (pool/save-semantic-draft! *conn* "demo" id 0 doc semantic/checkers provenance))))
    (let [plan (plan!)]
      (is (empty? (:findings plan)))
      (is (= 1 (:source-revision plan)))
      (is (= 0 (get-in plan [:counts :entities])))
      (is (nil? (graph/graph (pool/db *conn*) "demo/plan-copy")))
      (is (= 2 (:revision (pool/save-semantic-draft! *conn* "demo" id 1 doc semantic/checkers provenance))))
      (is (= :semantic.duplicate/stale-plan (get-in (apply! (:plan-token plan)) [:findings 0 :code])))
      (is (nil? (graph/graph (pool/db *conn*) "demo/plan-copy")))
      (let [fresh (plan!) result (apply! (:plan-token fresh))]
        (is (true? (:semantic-copied? result)))
        (is (= :semantic.duplicate/name-conflict (get-in (apply! (:plan-token fresh)) [:findings 0 :code])))))))

(deftest duplicate-plan-binds-the-destination-and-enforces-owner-access
  (accept! (graph/register-graph! *conn* {:workspace "demo" :name "plan-source" :members {} :created-at "2026-09-15T00:00:00Z"}))
  (let [req {:graph "demo/plan-source" :name "plan-copy" :namespace "sandbox"}
        plan (as *owner* #(#'bs/duplicate-graph-plan req))]
    (is (seq (:plan-token plan)))
    (is (= :legacy (:mode plan)))
    (is (= :semantic.duplicate/stale-plan
           (get-in (as *owner* #(#'bs/apply-duplicate-graph! (assoc req :name "other-copy" :plan-token (:plan-token plan)))) [:findings 0 :code])))
    (is (nil? (graph/graph (pool/db *conn*) "demo/other-copy")))
    (is (thrown? clojure.lang.ExceptionInfo (as *editor* #(#'bs/duplicate-graph-plan req))))
    (is (= :semantic.duplicate/plan-required
           (get-in (as *owner* #(#'bs/apply-duplicate-graph! req)) [:findings 0 :code])))))

(deftest duplicate-plan-counts-assets-and-rechecks-namespace-conflicts
  (let [source (accept! (graph/register-graph! *conn* {:workspace "demo" :name "plan-source" :members {} :created-at "2026-09-15T00:00:00Z"}))
        id (get-in source [:graph :graph/id])
        doc (:document (semantic/rebind-document-context
                        (edn/read-string (slurp "../../docs/guides/examples/orders-semantic-contract-2.0.edn")) "demo" id))
        req {:graph "demo/plan-source" :name "planned" :namespace "plan.copy"}]
    (accept! (pool/save-semantic-draft! *conn* "demo" id 0 doc semantic/checkers {:entrypoint :ui :actor (:principal *owner*)}))
    (let [plan (as *owner* #(#'bs/duplicate-graph-plan req))]
      (is (= {:entities 4 :attributes 12 :resolvers 2 :operations 0 :terms 2 :bindings 2} (:counts plan)))
      (is (= ["orders-db"] (:source-slots plan)))
      (is (empty? (:findings plan)))
      ;; Another copy claims the namespace after review but before apply.
      (is (true? (:semantic-copied? (as *owner* #(duplicate-graph! (assoc req :name "competitor"))))))
      (is (= :semantic.duplicate/namespace-conflict
             (get-in (as *owner* #(#'bs/apply-duplicate-graph! (assoc req :plan-token (:plan-token plan)))) [:findings 0 :code])))
      (is (nil? (graph/graph (pool/db *conn*) "demo/planned"))))))

(deftest listing-drafts-does-not-read-nonportable-runtime-paths
  (let [calls (atom [])
        serving (ns-resolve 'open.atlas.studio.builder-server 'graph-serving-version)]
    (with-redefs [rgraph/serving-version (fn [_ qn] (swap! calls conj qn) 7)]
      (doseq [qn ["demo/订单" "demo/my graph" "中文/app" "demo/../app" nil]]
        (is (nil? (serving :runtime qn))))
      (is (empty? @calls))
      (is (nil? (serving nil "demo/app")))
      (is (= 7 (serving :runtime "user@example.com/app")))
      (is (= ["user@example.com/app"] @calls)))))

(defn- graph-route [user method path body]
  (let [result (atom nil)
        exchange (proxy [com.sun.net.httpserver.HttpExchange] []
                   (getRequestURI [] (java.net.URI/create path))
                   (getRequestMethod [] method))]
    (with-redefs [http/request-edn (fn [_] body)
                  http/reply! (fn [_ status text & _]
                                (reset! result {:status status :body (edn/read-string text)}))]
      (as user #(#'bs/handle-routes! exchange)))
    @result))

(deftest create-archive-list-restore-retains-owner-access
  ;; Nonportable names previously broke the entire list whenever a runtime
  ;; was attached, including immediately after archiving an empty draft.
  (swap! @studio-state assoc :runtime {:graph ::runtime})
  (doseq [name ["订单" "my graph"]]
    (let [created (graph-route *owner* "POST" "/api/studio/graphs/create" {:name name})
          qn (get-in created [:body :graph :graph/qualified-name])
          request {:graph qn}]
      (is (= 201 (:status created)))
      (is (= 200 (:status (graph-route *owner* "POST" "/api/studio/graphs/archive" request))))
      (let [listed (graph-route *owner* "GET" "/api/studio/graphs?include-archived=true" nil)
            row (some #(when (= qn (:graph/qualified-name %)) %) (get-in listed [:body :graphs]))]
        (is (= 200 (:status listed)))
        (is (= :archived (:graph/state row)))
        (is (true? (:graph/can-manage? row)))
        (is (true? (:graph/can-edit? row)))
        (is (nil? (:graph/serving row))))
      (is (= 403 (:status (graph-route *editor* "POST" "/api/studio/graphs/restore" request)))
          "Archive must not bypass the existing owner boundary")
      (is (= 200 (:status (graph-route *owner* "POST" "/api/studio/graphs/restore" request))))
      (is (= :active (:graph/state (graph/graph (pool/db *conn*) qn)))))))

(deftest one-persisted-archived-draft-cannot-hide-all-graphs
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "studio-list-recovery-"
                       (into-array java.nio.file.attribute.FileAttribute [])))
        instance (:instance (rstore/open! {:instance-id "list-recovery" :root (str root)}))]
    (try
      (swap! @studio-state assoc :runtime {:graph (rgraph/open! instance)})
      (doseq [name ["healthy" "归档测试"]]
        (is (= 201 (:status (graph-route *owner* "POST" "/api/studio/graphs/create" {:name name})))))
      (is (= 200 (:status (graph-route *owner* "POST" "/api/studio/graphs/archive"
                                       {:graph "demo/归档测试"}))))
      (let [saved (graph/graph (pool/db *conn*) "demo/归档测试")
            list-all #(graph-route *owner* "GET" "/api/studio/graphs?include-archived=true" nil)]
        ;; The old lookup fails repeatedly on the SAME stored row. Reloading
        ;; the page cannot remove an archived registration from the database.
        (with-redefs-fn {#'bs/graph-serving-version rgraph/serving-version}
          (fn []
            (dotimes [_ 3]
              (let [reply (list-all)]
                (is (= 400 (:status reply)))
                (is (= "illegal path segment" (get-in reply [:body :error :message])))
                (is (nil? (get-in reply [:body :graphs])))))
            (is (= 200 (:status (graph-route *owner* "GET" "/api/studio/graphs" nil)))
                "The healthy active graph is intact; the archived row poisons the inclusive list")))
        ;; The fix reads existing data without rename, restore, deletion or migration.
        (dotimes [_ 3]
          (let [reply (list-all)]
            (is (= 200 (:status reply)))
            (is (= #{"demo/healthy" "demo/归档测试"}
                   (set (map :graph/qualified-name (get-in reply [:body :graphs])))))))
        (is (= saved (graph/graph (pool/db *conn*) "demo/归档测试"))))
      (finally
        (rstore/close! instance)
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest permanent-deletion-removes-artifacts-and-retains-registration-on-io-failure
  (doseq [fail? [true false]]
    (let [root (.toFile (java.nio.file.Files/createTempDirectory
                        "delete-artifacts-" (make-array java.nio.file.attribute.FileAttribute 0)))
          dir (io/file root "demo/to-delete/1")]
      (try
        (when-not (graph/graph (pool/db *conn*) "demo/to-delete")
          (as *owner* #(create-graph! {:name "to-delete"})))
        (as *owner* #(#'bs/archive-request! {:graph "demo/to-delete"} :archived))
        (.mkdirs dir)
        (spit (io/file dir "manifest.edn") (pr-str {:provenance {:workspace "demo" :graph "to-delete" :version 1}}))
        (spit (io/file dir "content.edn") "{:references []}")
        (spit (io/file dir "report.edn") "{}")
        (swap! @studio-state assoc :artifact-root (str root))
        (if fail?
          (with-redefs [cleanup/delete-path! (fn [_] (throw (java.io.IOException. "injected")))]
            (is (= :graph-artifact-cleanup-failed
                   (:code (caught #(as *owner* (fn [] (#'bs/delete-graph! {:graph "demo/to-delete"})))))))
            (is (some? (graph/graph (pool/db *conn*) "demo/to-delete"))))
          (do
            (is (= 1 (get-in (as *owner* #(#'bs/delete-graph! {:graph "demo/to-delete"}))
                             [:runtime-cleanup :artifact-copies-deleted])))
            (is (nil? (graph/graph (pool/db *conn*) "demo/to-delete")))
            (is (not (.exists dir)))))
        (finally
          (swap! @studio-state dissoc :artifact-root)
          (doseq [file (reverse (file-seq root))] (.delete file)))))))

(deftest draft-query-diagnostics-carry-identity-and-query
  (let [events (atom [])]
    (with-redefs [obs/log-event! (fn [event data] (swap! events conj [event data]))]
      (binding [obs/*query-source* {:token-id "workspace-token-id" :remote-addr "127.0.0.1"
                                    :tool "execute_eql"}]
        (#'bs/audit-query-run! {:request {:entity {} :query [:example/name]}
                               :graph "workspace/graph" :principal {:subject "operator"}
                               :entry :builder-mcp :outcome :ok :latency-ms 12})))
    (let [[event data] (first @events)]
      (is (= :query-executed event))
      (is (= :draft (:execution-target data)))
      (is (= "workspace-token-id" (:token-id data)))
      (is (= "operator" (get-in data [:principal :subject])))
      (is (= {:entity {} :query [:example/name]} (edn/read-string (:eql data)))))))

(deftest query-builder-sql-diagnostics-reach-response-but-not-history
  (let [qn (graph-named! "sql-diagnostics")
        history (atom [])]
    (as *editor*
        #(with-redefs [pool/semantic-draft (fn [& _] {:revision 1})
                       bs/record-history! (fn [& args] (swap! history conj args))
                       semantic/service
                       (fn [& _]
                         (fn [& _]
                           (try
                             (sql-exec/query!
                              (jdbc/get-datasource {:dbtype "sqlite" :dbname ":memory:"})
                              ["SELECT missing_column"] 5
                              {:resolver-id "broken" :source-type "sql" :source-ref "flowx-pg"})
                             (catch Exception _
                               {:engine/status :rejected :code :engine.execution/failed
                                :message "source execution failed" :error/category :source}))))]
           (let [failure (try (run-request! {:graph qn :query [:test/value]})
                              (catch Exception e (#'bs/api-error->edn e)))
                 sources (get-in failure [:diagnostics :sources])]
             (is (= "broken" (:resolver-id (first sources))))
             (is (= "flowx-pg" (:source-ref (first sources))))
             (is (str/includes? (pr-str sources) "missing_column"))
             (is (= 1 (count @history)))
             (is (not (str/includes? (pr-str @history) "missing_column")))
             (is (not (str/includes? (pr-str @history) ":sources"))))))))

;; ---------------------------------------------------------------------------
;; Removing a declared Source / a credential variable (owner 2026-10-04)
;; ---------------------------------------------------------------------------

(defn- temp-home []
  (str (java.nio.file.Files/createTempDirectory
        "source-removal" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- add-source! [id entry]
  (accept! (pool/execute-action *conn* {:workspace "demo" :action :create :type :source :id id
                                        :entry entry :expected_revision 0})))

(defn- add-feeder-model!
  "b.row namespace; `row_by_id` reads feed-db and alone produces :b.row/label;
   :b.row/id is produced by `ids` on other-db."
  []
  (add-source! "feed-db" {:type "sql" :jdbc_url *jdbc-url*})
  (add-source! "other-db" {:type "sql" :jdbc_url *jdbc-url*})
  (accept! (pool/execute-actions
            *conn* [{:workspace "demo" :action :create :type :namespace :id "b.row" :entry {}
                     :expected_revision 0}
                    {:workspace "demo" :action :create :type :attribute :id :b.row/id
                     :entry {:type :integer :identity? true} :expected_revision 0}
                    {:workspace "demo" :action :create :type :attribute :id :b.row/label
                     :entry {:type :string} :expected_revision 0}
                    {:workspace "demo" :action :create :type :resolver :id "ids"
                     :entry {:id "ids" :input [] :output [:b.row/id] :result_type "list" :limit {:default 10 :max 100}
                             :source {:type "sql" :query "SELECT 1 AS id"}
                             :source-ref "other-db"}
                     :expected_revision 0}
                    {:workspace "demo" :action :create :type :resolver :id "row_by_id"
                     :entry {:id "row_by_id" :input [:b.row/id]
                             :output [:b.row/label] :result_type "single"
                             :source {:type "sql" :query "SELECT 1 WHERE 1 = :id"}
                             :source-ref "feed-db"}
                     :expected_revision 0}])))

(deftest feeders-no-graph-names-are-removed-with-their-source
  (with-redefs-fn {#'bs/studio-home (constantly (temp-home))}
    (fn []
      (add-feeder-model!)
      (let [plan (as *editor* #(#'bs/source-removal-plan-request {:source "feed-db"}))]
        (is (true? (:removable? plan)) (pr-str plan))
        (is (= #{{:type "resolver" :id (pr-str "row_by_id")}
                 {:type "attribute" :id (pr-str :b.row/label)}}
               (set (:also-removed plan)))
            "only-this-source & unnamed -> removable, with the attribute only it produces")
        (is (= [] (get-in plan [:used-by :objects]))))
      (let [{:keys [events]}
            (with-audit
              #(let [r (as *editor* (fn [] (#'bs/remove-source! {:source "feed-db"})))]
                 (is (true? (:removed? r)))
                 (is (= {:resolvers 1 :operations 0 :attributes 1 :links 0 :credentials 0}
                        (:removed-counts r)))))]
        (is (= #{[:source "feed-db"] [:resolver "row_by_id"] [:attribute :b.row/label]}
               (set (keep #(when (and (= :pool/mutation (:event %)) (= :ok (:outcome %)))
                             (:object %))
                          events)))
            "one unit, audited"))
      (let [db (pool/db *conn*)]
        (is (nil? (pool/object db :source "feed-db")))
        (is (nil? (pool/object db :resolver "row_by_id")))
        (is (nil? (pool/object db :attribute :b.row/label)))
        (is (some? (pool/object db :attribute :b.row/id)) "another source still produces it")
        (is (some? (pool/object db :resolver "ids")))))))

(deftest a-feeder-a-graph-names-blocks-the-source
  (with-redefs-fn {#'bs/studio-home (constantly (temp-home))}
    (fn []
      (add-feeder-model!)
      (accept! (graph/register-graph! *conn* {:workspace "demo" :name "keeps"
                                              :members {:resolvers #{"row_by_id"}}}))
      (let [plan (as *editor* #(#'bs/source-removal-plan-request {:source "feed-db"}))]
        (is (false? (:removable? plan)))
        (is (= [{:type "resolver" :id (pr-str "row_by_id")
                 :reason "named by a graph" :graphs ["demo/keeps"]}]
               (get-in plan [:used-by :objects])))
        (is (= [:source/in-use] (map :code (:findings plan))))
        (is (= [{:graph "demo/keeps" :state "active" :workspace "demo" :can-open? true
                 :can-retire? false :objects 1}]
               (:blocking-graphs plan)))
        (is (= #{:source :type :removable? :used-by :blocking-graphs :also-removed :bindings
                 :credentials :findings}
               (set (keys plan)))
            "internal keys stay on the server"))
      (let [refused (caught #(as *editor* (fn [] (#'bs/remove-source! {:source "feed-db"}))))]
        (is (= :source/in-use (:code refused)))
        (is (some? (pool/object (pool/db *conn*) :resolver "row_by_id")) "nothing was removed")
        (is (some? (pool/object (pool/db *conn*) :source "feed-db")))))))

(deftest an-archived-graph-blocks-and-says-so
  ;; Owner 2026-10-04: an archived recipe still names objects (deliberate);
  ;; the plan names its state so the person knows to delete or restore it.
  (with-redefs-fn {#'bs/studio-home (constantly (temp-home))}
    (fn []
      (add-feeder-model!)
      (accept! (graph/register-graph! *conn* {:workspace "demo" :name "old"
                                              :members {:resolvers #{"row_by_id"}}}))
      (graph/archive-graph! *conn* "demo/old")
      (let [plan (as *editor* #(#'bs/source-removal-plan-request {:source "feed-db"}))]
        (is (false? (:removable? plan)))
        (is (= [{:graph "demo/old" :state "archived" :workspace "demo" :can-open? true
                 :can-retire? false :objects 1}]
               (:blocking-graphs plan))))
      ;; Regression (owner 2026-10-04): once the graph is deleted, its recipe
      ;; names nothing and the same objects no longer block.
      (as *owner* #(#'bs/delete-graph! {:graph "demo/old"}))
      (is (nil? (graph/graph (pool/db *conn*) "demo/old")))
      (let [plan (as *editor* #(#'bs/source-removal-plan-request {:source "feed-db"}))]
        (is (true? (:removable? plan)))
        (is (= [] (:blocking-graphs plan)))))))

(deftest a-graph-in-an-unreachable-workspace-names-it-and-an-admin-retires-it
  ;; Owner 2026-10-04: demo/cis sat in a workspace whose only owner was the
  ;; retired `studio/local-builder` principal. The plan says whose the graph is
  ;; and that the requester cannot open it; a Studio administrator archives and
  ;; deletes it from the panel, confirmed by name and audited.
  (with-redefs-fn {#'bs/studio-home (constantly (temp-home))}
    (fn []
      (add-feeder-model!)
      (accept! (graph/register-workspace!
                *conn* {:name "legacy"
                        :members [{:principal {:issuer "studio" :subject "local-builder"}
                                   :role :owner}]}))
      (accept! (graph/register-graph! *conn* {:workspace "legacy" :name "cis"
                                              :members {:resolvers #{"row_by_id"}}}))
      (let [plan (as *editor* #(#'bs/source-removal-plan-request {:source "feed-db"}))]
        (is (false? (:removable? plan)))
        (is (= [{:graph "legacy/cis" :state "active" :workspace "legacy" :can-open? false
                 :can-retire? false :objects 1}]
               (:blocking-graphs plan))))
      (is (true? (-> (as *owner* #(#'bs/source-removal-plan-request {:source "feed-db"}))
                     :blocking-graphs first :can-retire?))
          "the founding admin may retire it")
      (is (= :studio-admin-required
             (:code (caught #(as *editor* (fn [] (#'bs/admin-retire-graph!
                                                  {:graph "legacy/cis" :confirm "legacy/cis"}))))))
          "a non-admin is refused")
      (is (= :graph/confirm-mismatch
             (:code (caught #(as *owner* (fn [] (#'bs/admin-retire-graph!
                                                 {:graph "legacy/cis" :confirm "cis"})))))))
      (is (some? (graph/graph (pool/db *conn*) "legacy/cis")) "refusals removed nothing")
      (is (= {:graph "legacy/cis" :workspace "legacy"}
             (as *owner* #(#'bs/admin-retire-graph! {:graph "legacy/cis" :confirm "legacy/cis"}))))
      (is (nil? (graph/graph (pool/db *conn*) "legacy/cis")))
      (let [plan (as *editor* #(#'bs/source-removal-plan-request {:source "feed-db"}))]
        (is (true? (:removable? plan)))
        (is (= [] (:blocking-graphs plan)))))))

(deftest a-cascaded-attribute-a-graph-names-blocks-the-source
  (with-redefs-fn {#'bs/studio-home (constantly (temp-home))}
    (fn []
      (add-feeder-model!)
      (accept! (graph/register-graph! *conn* {:workspace "demo" :name "attr"
                                              :members {:attributes #{:b.row/label}}}))
      (let [plan (as *editor* #(#'bs/source-removal-plan-request {:source "feed-db"}))]
        (is (false? (:removable? plan)))
        (is (= [(pr-str :b.row/label)] (map :id (get-in plan [:used-by :objects]))))))))

(deftest a-draft-binding-the-source-still-refuses
  (with-redefs-fn {#'bs/studio-home (constantly (temp-home))}
    (fn []
      (add-feeder-model!)
      (with-redefs [pool/semantic-drafts
                    (fn [_] [{:workspace "demo" :graph "g-x"
                              :document {:execution {:bindings {:b {:binding/source "feed-db"}}}}}])]
        (let [plan (as *editor* #(#'bs/source-removal-plan-request {:source "feed-db"}))
              refused (caught #(as *editor* (fn [] (#'bs/remove-source! {:source "feed-db"}))))]
          (is (false? (:removable? plan)))
          (is (= ["g-x"] (get-in plan [:used-by :drafts])))
          (is (= :source/in-use (:code refused)))))
      (is (some? (pool/object (pool/db *conn*) :resolver "row_by_id"))))))

(deftest an-unused-source-is-removed-with-its-bindings-and-own-credentials
  (let [home (temp-home)]
    (with-redefs-fn {#'bs/studio-home (constantly home)}
      (fn []
        (add-source! "lens" {:type "sql" :jdbc_url *jdbc-url*
                             :username "${LENS_USER}" :password "${SHARED_PW}"})
        (add-source! "keeper" {:type "sql" :jdbc_url *jdbc-url* :password "${SHARED_PW}"})
        (credentials/put-vars! home {"LENS_USER" "lens-secret-user" "SHARED_PW" "shared-secret"})
        (spit (io/file home "source-bindings.edn")
              (pr-str {["demo" "g-1" "1" "lens"] {:type "sql"}
                       ["demo" "g-1" "1" "keeper"] {:type "sql"}}))
        (let [plan (as *editor* #(#'bs/source-removal-plan-request {:source "lens"}))]
          (is (true? (:removable? plan)) (pr-str plan))
          (is (= ["LENS_USER"] (:credentials plan))
              "a value another source still references is not this source's to drop")
          (is (= [{:graph "g-1" :version "1"}] (:bindings plan))))
        (let [{:keys [events]}
              (with-audit
                #(let [r (as *editor* (fn [] (#'bs/remove-source! {:source "lens"})))]
                   (is (true? (:removed? r)))
                   (is (= 1 (:bindings-removed r)))
                   (is (not (str/includes? (pr-str r) "secret")))))]
          (is (some #(and (= :pool/mutation (:event %)) (= :ok (:outcome %))
                          (= [:source "lens"] (:object %)))
                    events)
              "audited like any other pool removal"))
        (is (nil? (pool/object (pool/db *conn*) :source "lens")))
        (is (= #{["demo" "g-1" "1" "keeper"]}
               (set (keys (edn/read-string (slurp (io/file home "source-bindings.edn")))))))
        (is (= ["SHARED_PW"] (credentials/held-variables home)))))))

(deftest a-source-a-serving-version-runs-on-is-refused
  (let [home (temp-home)
        before @@studio-state]
    (with-redefs-fn {#'bs/studio-home (constantly home)
                     #'rgraph/serving-version (fn [_ qn] (when (= "demo/live" qn) 2))}
      (fn []
        (add-source! "lens" {:type "sql" :jdbc_url *jdbc-url*})
        (let [g (accept! (graph/register-graph! *conn* {:workspace "demo" :name "live" :members {}}))
              gid (get-in g [:graph :graph/id])]
          (swap! @studio-state assoc :runtime {:graph ::store})
          (try
            (spit (io/file home "source-bindings.edn")
                  (pr-str {["demo" gid "2" "lens"] {:type "sql"}}))
            (let [plan (as *editor* #(#'bs/source-removal-plan-request {:source "lens"}))]
              (is (false? (:removable? plan)))
              (is (= [{:graph "demo/live" :version "2"}] (get-in plan [:used-by :serving]))))
            (finally (reset! @studio-state before))))))))

(deftest removing-a-source-needs-a-workspace-editor
  (with-redefs-fn {#'bs/studio-home (constantly (temp-home))}
    (fn []
      (add-source! "lens" {:type "sql" :jdbc_url *jdbc-url*})
      (let [r (caught #(as *outsider* (fn [] (#'bs/remove-source! {:source "lens"}))))]
        (is (keyword? (:code r)))
        (is (not= :source/in-use (:code r))))
      (is (some? (pool/object (pool/db *conn*) :source "lens"))))))

(deftest a-credential-variable-is-removed-only-when-nothing-references-it
  (let [home (temp-home)]
    (with-redefs-fn {#'bs/studio-home (constantly home)}
      (fn []
        (add-source! "lens" {:type "sql" :jdbc_url *jdbc-url* :password "${LENS_PW}"})
        (credentials/put-vars! home {"LENS_PW" "pw-value-1" "ORPHAN_PW" "pw-value-2"})
        (let [plan (as *editor* #(#'bs/variable-removal-plan {:variable "LENS_PW"}))
              refused (caught #(as *editor* (fn [] (#'bs/remove-variable! {:variable "LENS_PW"}))))]
          (is (= ["lens"] (:used-by plan)))
          (is (false? (:removable? plan)))
          (is (= :credential/in-use (:code refused)))
          (is (not (str/includes? (pr-str [plan refused]) "pw-value"))))
        (let [r (as *editor* #(#'bs/remove-variable! {:variable "ORPHAN_PW"}))]
          (is (true? (:removed? r)))
          (is (not (str/includes? (pr-str r) "pw-value"))))
        (is (= ["LENS_PW"] (credentials/held-variables home)))
        (is (= :credential/not-held
               (:code (caught #(as *editor* (fn [] (#'bs/remove-variable! {:variable "NEVER_SET"})))))))
        (is (keyword? (:code (caught #(as *outsider* (fn [] (#'bs/remove-variable! {:variable "LENS_PW"})))))))
        (is (= ["LENS_PW"] (credentials/held-variables home)))))))

;; ---------------------------------------------------------------------------
;; Admin service token (doc 112 §112.1)
;; ---------------------------------------------------------------------------

(defn- sha256-hex [^String s]
  (apply str (map #(format "%02x" %)
                  (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           (.getBytes s "UTF-8")))))

(def ^:private token-a (apply str (repeat 64 "a")))
(def ^:private token-b (apply str (repeat 64 "b")))

(defn- request-with
  "Drive the whole request boundary (not just the route table) with headers."
  [method path headers body]
  (let [result (atom nil)
        hs (com.sun.net.httpserver.Headers.)
        _ (doseq [[k v] headers] (.add hs k v))
        exchange (proxy [com.sun.net.httpserver.HttpExchange] []
                   (getRequestURI [] (java.net.URI/create path))
                   (getRequestMethod [] method)
                   (getRequestHeaders [] hs)
                   (getResponseHeaders [] (com.sun.net.httpserver.Headers.)))
        audits (atom [])]
    (with-redefs [http/request-edn (fn [_] body)
                  http/reply! (fn [_ status text & _]
                                (reset! result {:status status :body (edn/read-string text)}))
                  obs/audit-record! (fn [e] (swap! audits conj e))]
      (#'bs/handle-request-inner! exchange))
    (assoc @result :audits @audits)))

(defn- with-admin-token [digests f]
  (swap! @studio-state assoc :admin-token {:digests digests :user "owner@example.com"})
  (try (f) (finally (swap! @studio-state dissoc :admin-token))))

(defn- bearer [t] {"Authorization" (str "Bearer " t)})

(deftest admin-token-config-is-refused-when-half-written-or-malformed
  (is (nil? (auth/admin-token-config {})))
  (is (= {:digests [(sha256-hex token-a)] :user "owner@example.com"}
         (auth/admin-token-config {"OPEN_ATLAS_STUDIO_ADMIN_TOKEN_SHA256" (sha256-hex token-a)
                                   "OPEN_ATLAS_STUDIO_ADMIN_TOKEN_USER" "owner@example.com"})))
  (doseq [env [{"OPEN_ATLAS_STUDIO_ADMIN_TOKEN_SHA256" (sha256-hex token-a)}
               {"OPEN_ATLAS_STUDIO_ADMIN_TOKEN_USER" "owner@example.com"}
               {"OPEN_ATLAS_STUDIO_ADMIN_TOKEN_SHA256" "NOT-HEX"
                "OPEN_ATLAS_STUDIO_ADMIN_TOKEN_USER" "owner@example.com"}
               {"OPEN_ATLAS_STUDIO_ADMIN_TOKEN_SHA256" (str/upper-case (sha256-hex token-a))
                "OPEN_ATLAS_STUDIO_ADMIN_TOKEN_USER" "owner@example.com"}]]
    (is (= :auth/admin-token-config (get-in (auth/admin-token-config env) [:findings 0 :code])))))

(deftest admin-token-start-refuses-unknown-account
  (is (= :auth/admin-token-user-missing
         (get-in (caught #(bs/admin-token-from-env!
                           {"OPEN_ATLAS_STUDIO_ADMIN_TOKEN_SHA256" (sha256-hex token-a)
                            "OPEN_ATLAS_STUDIO_ADMIN_TOKEN_USER" "nobody@example.com"}
                           *conn*))
                 [:findings 0 :code])))
  (is (some? (bs/admin-token-from-env!
              {"OPEN_ATLAS_STUDIO_ADMIN_TOKEN_SHA256" (sha256-hex token-a)
               "OPEN_ATLAS_STUDIO_ADMIN_TOKEN_USER" "owner@example.com"}
              *conn*))))

(deftest admin-token-reads-and-mutates-as-the-configured-account
  (with-admin-token [(sha256-hex token-a)]
    (fn []
      (let [listed (request-with "GET" "/api/studio/graphs" (bearer token-a) nil)]
        (is (= 200 (:status listed)))
        (is (vector? (get-in listed [:body :graphs]))))
      (let [created (request-with "POST" "/api/studio/graphs/create" (bearer token-a)
                                  {:name "via-admin-token"})
            audit (some #(when (= :auth/admin-token (:event %)) %) (:audits created))]
        (is (= 201 (:status created)))
        (is (= "service:admin-token" (get-in audit [:actor :service :id])))
        (is (= (subs (sha256-hex token-a) 0 8)
               (get-in audit [:actor :service :token-digest-prefix])))
        (is (= (:principal *owner*) (get-in audit [:actor :on-behalf-of])))
        (is (not (str/includes? (pr-str created) token-a)) "never echoed")))))

(deftest admin-token-wrong-bearer-is-401
  (with-admin-token [(sha256-hex token-a)]
    (fn []
      (let [r (request-with "GET" "/api/studio/graphs" (bearer "wrong") nil)]
        (is (= 401 (:status r)))
        (is (= :auth/admin-token-invalid (get-in r [:body :error :code])))))))

(deftest admin-token-off-when-unset
  (let [r (request-with "GET" "/api/studio/graphs" (bearer token-a) nil)]
    (is (= 401 (:status r)))
    (is (= :authentication-required (get-in r [:body :error :code])))))

(deftest admin-token-does-not-reach-account-routes
  (with-admin-token [(sha256-hex token-a)]
    (fn []
      (doseq [[method path] [["POST" "/api/auth/change-password"]
                             ["GET" "/api/studio/users"]
                             ["POST" "/api/studio/users/reset-password"]
                             ["POST" "/api/studio/workspaces/members"]
                             ["POST" "/api/studio/workspace-tokens/issue"]
                             ["POST" "/api/studio/admin/graphs/retire"]]]
        (is (= 403 (:status (request-with method path (bearer token-a) {}))) path)))))

(deftest workspace-token-bearer-path-is-unchanged
  (with-admin-token [(sha256-hex token-a)]
    (fn []
      ;; An `oaw_v1.` key is never compared as an admin token: without a
      ;; session it is refused exactly as before.
      (let [r (request-with "GET" "/api/studio/graphs" (bearer "oaw_v1.not-a-real-key") nil)]
        (is (= 401 (:status r)))
        (is (= :authentication-required (get-in r [:body :error :code])))))))

(deftest admin-token-rotation-accepts-either-digest
  (with-admin-token [(sha256-hex token-a) (sha256-hex token-b)]
    (fn []
      (is (= 200 (:status (request-with "GET" "/api/studio/graphs" (bearer token-a) nil))))
      (is (= 200 (:status (request-with "GET" "/api/studio/graphs" (bearer token-b) nil))))
      (is (= 401 (:status (request-with "GET" "/api/studio/graphs"
                                        (bearer (apply str (repeat 64 "c"))) nil)))))))

;; ---------------------------------------------------------------------------
;; The graph list with a workspace token (owner 2026-10-11)
;; ---------------------------------------------------------------------------

(deftest the-graph-list-accepts-a-workspace-token-and-carries-each-graphs-purpose
  (let [home (str (System/getProperty "java.io.tmpdir") "/wt-list-" (random-uuid))
        pepper "list-pepper"
        source (accept! (graph/register-graph! *conn* {:workspace "demo" :name "guided"
                                                       :members {} :created-at "2026-10-11T00:00:00Z"}))
        graph-id (get-in source [:graph :graph/id])
        package (edn/read-string (slurp "../../docs/guides/examples/orders-semantic-graph-2.0.edn"))
        doc (-> (get-in package [:graphs 0 :document])
                (assoc :workspace {:workspace/id "demo"})
                (assoc-in [:graph :graph/id] graph-id)
                (assoc-in [:graph :graph/usage-guide] {:guide/purpose "按订单编号查询订单事实"})
                (update :ownership (fn [ownership]
                                     (into {} (map (fn [[kind owners]]
                                                     [kind (into {} (map (fn [[asset _]] [asset graph-id])) owners)]))
                                           ownership))))
        _ (accept! (pool/save-semantic-draft! *conn* "demo" graph-id 0 doc semantic/checkers
                                              {:entrypoint :ui :actor (:principal *owner*)}))
        issue (fn [scope]
                (:token ((requiring-resolve 'open.atlas.studio.workspace-token/issue!)
                         home pepper {:workspace "demo" :scope scope :issued-by (:principal *owner*)
                                      :issued-at "2026-10-11T00:00:00Z" :expires-at "2099-01-01T00:00:00Z"
                                      :label "list test"})))
        raw (fn [path headers]
              (let [result (atom nil)
                    hs (com.sun.net.httpserver.Headers.)
                    _ (doseq [[k v] headers] (.add hs k v))
                    exchange (proxy [com.sun.net.httpserver.HttpExchange] []
                               (getRequestURI [] (java.net.URI/create path))
                               (getRequestMethod [] "GET")
                               (getRequestHeaders [] hs)
                               (getResponseHeaders [] (com.sun.net.httpserver.Headers.)))]
                (with-redefs [http/reply! (fn [_ status text & [ctype]]
                                            (reset! result {:status status :body text :type ctype}))]
                  (#'bs/handle-request-inner! exchange))
                @result))]
    (swap! @studio-state assoc-in [:secrets :workspace-token-pepper] pepper)
    (with-redefs-fn {#'bs/studio-home (constantly home)}
      (fn []
        (let [t (issue #{:read})]
          (testing "EDN by default; the purpose only when asked for"
            (let [plain (edn/read-string (:body (raw "/api/studio/graphs" (bearer t))))
                  guided (edn/read-string (:body (raw "/api/studio/graphs?with-guide=true" (bearer t))))
                  row (fn [r] (some #(when (= "demo/guided" (:graph/qualified-name %)) %) (:graphs r)))]
              (is (= "demo" (:workspace plain)))
              (is (= graph-id (:graph/id (row plain))))
              (is (nil? (:graph/usage-guide (row plain))))
              (is (= "Orders example" (:graph/label (row guided))))
              (is (= "按订单编号查询订单事实" (get-in (row guided) [:graph/usage-guide :guide/purpose])))))
          (testing "JSON when the client asks"
            (let [r (raw "/api/studio/graphs?with-guide=true" (assoc (bearer t) "Accept" "application/json"))]
              (is (= "application/json" (:type r)))
              (is (str/includes? (:body r) "按订单编号查询订单事实"))))
          (testing "a bad key falls through to the session check and is refused"
            (is (= 401 (:status (raw "/api/studio/graphs" (bearer "oaw_v1.nope")))))))))))
