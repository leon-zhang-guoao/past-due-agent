(ns open.atlas.studio.publish-mcp-test
  "**The publish loop over MCP** (8.4 P3).

   Against a REAL embedded runtime, not a stand-in: what is being claimed is
   that an agent's publish goes through the very offer/stage/load/activate a
   remote Server receives, and a fake would let that claim be true only of the
   fake. `studio/app` assembles the runtime here exactly as `-main` does.

   Four properties, each an exit condition of P3:

   1. the loop closes — cut, publish, and the serving pointer moves;
   2. a second publisher racing the first is **refused by the CAS guard**,
      because with an agent in the loop two publishers is no longer a rare
      human coincidence;
   3. rollback re-presents the target version's own plan, and a version that
      was never cut is refused by name;
   4. preflight writes nothing.

   Plus one that is not an MCP property but belongs to the same service and the
   same fixture: `versions`, the Builder-only read behind the graph detail
   panel's version list. It is here because it needs a real shelf to be true
   about, and because it must agree with `inspect_graph`'s summary — the two
   answer the same question in two shapes, and the shape is exactly where the
   last bug in this area lived (`:version/number` read as `:version` gave a
   list of nils).

   `serving_status`, `preflight_graph` and `rollback_graph` are gone (8.4.16
   裁定九): the pointer sits beside the recipe and the projection in
   `inspect_graph`'s summary view, and the check/apply/to_version discipline
   all lives in `publish_graph` now."
  (:require [clojure.data :as data]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.semantic-artifact :as semantic-artifact]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [open.atlas.common.mcp-transport :as wire]
            [open.atlas.runtime.env :as runtime-env]
            [open.atlas.runtime.graph :as rgraph]
            [open.atlas.runtime.token :as runtime-token]
            [open.atlas.runtime.source-bindings :as source-bindings]
            [open.atlas.studio.app :as app]
            [open.atlas.studio.auth :as auth]
            [open.atlas.studio.builder-mcp :as builder-mcp]
            [open.atlas.studio.builder-server]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.recent-queries :as recent]
            [open.atlas.studio.semantic-service :as semantic]
            [open.atlas.studio.semantic-package-service :as semantic-package]
            [open.atlas.server.data :as data-plane]
            [open.atlas.server.mcp :as mcp]
            [open.atlas.studio.tools :as tools]
            [open.atlas.studio.version :as version]
            [open.atlas.studio.workspace-token :as wt]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

(def ^:dynamic *state* nil)
(def ^:dynamic *home* nil)
(def ^:dynamic *owner* nil)
(def ^:private pepper "test-workspace-pepper")
(def ^:private actor-key-id "studio-builder-actor")
(def ^:private actor-secret "studio-actor-secret")
(def ^:private publish-http-handler #'open.atlas.studio.builder-server/publish-graph!)

(def ^:private model
  "The smallest model that can actually be cut and served: one entity, one
   identity attribute, one resolver that produces it."
  {:attributes {:cis.a/id   {:type :integer :format :id :identity? true :label "A"}
                :cis.a/name {:type :string :format :text :label "Name"}}
   :namespaces {"cis.a" {:kind :master :label "A"}}
   :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
   :resolvers  [{:id "a_by_id" :input [:cis.a/id] :output [:cis.a/id :cis.a/name]
                 :result_type "single" :source {:type "sql" :query "SELECT 1"}
                 :source-ref "db"}]})

(defn- tmp [prefix] (str (System/getProperty "java.io.tmpdir") "/" prefix (random-uuid)))

(defn- rm-r [path]
  (doseq [f (reverse (file-seq (java.io.File. ^String path)))] (.delete ^java.io.File f)))

(defn- check! [r] (assert (empty? (:findings r)) (pr-str (:findings r))) r)

(defn- with-studio [f]
  (let [runtime-root (tmp "pubmcp-rt-")
        artifact-root (tmp "pubmcp-art-")
        home (tmp "pubmcp-home-")
        binding-path (str (io/file home "source-bindings.edn"))
        started (app/start! {:pool* {:backend :mem :id (str (gensym "pubmcp"))}
                             :instance-id "studio-embedded"
                             :runtime-root runtime-root
                             :artifact-root artifact-root
                             :token-pepper "studio-pepper"
                             :actor-key (fn [k] (when (= actor-key-id k) actor-secret))
                             :resolve-source (source-bindings/file-resolver binding-path
                                                                          (fn [_ entry] entry))})
        conn (:conn started)]
    (assert (empty? (:findings started)) (pr-str (:findings started)))
    (try
      (identity/install! conn)
      (let [owner (:user (identity/bootstrap-admin!
                          conn {:email "owner@example.com" :name "Owner"
                                :password "a-safe-password" :workspace "demo"}))]
        (check! (pool/import-pool! conn {:pool/version 1
                                         :pool/objects (mapv #(assoc % :pool/revision 1 :pool/workspace "demo")
                                                             (pool/dialect->objects model))}))
        (let [m (pool/as-dialect (pool/db conn))]
          (check! (graph/register-graph!
                   conn {:workspace "demo" :name "app"
                         :created-at "2026-08-28T00:00:00Z"
                         :members {:namespaces (set (keys (:namespaces m)))
                                   :attributes (set (keys (:attributes m)))
                                   :resolvers (set (map :id (:resolvers m)))}})))
        ;; The Builder's services read the server state atom, exactly as every
        ;; HTTP handler does. `:secrets` is what signs the deploy envelopes.
        (reset! (var-get #'open.atlas.studio.builder-server/studio-state)
                (assoc started :secrets {:actor-key-id actor-key-id
                                         :actor-secret actor-secret}
                               :source-bindings-path binding-path
                               :resolve-source (fn [_ entry] entry)))
        (binding [*state* started *home* home *owner* (:principal owner)]
          (f)))
      (finally
        (reset! (var-get #'open.atlas.studio.builder-server/studio-state) nil)
        (app/stop! started)
        (rm-r runtime-root) (rm-r artifact-root) (rm-r home)))))

(use-fixtures :each with-studio)

(defn- deps []
  (builder-mcp/deps
   {:home *home* :pepper pepper :conn (:conn *state*)
    :graph-service (var-get #'open.atlas.studio.builder-server/graph-service)
    :publish-service (var-get #'open.atlas.studio.builder-server/publish-service)
    :semantic-service (fn [caller] (semantic/service (:conn *state*) caller (fn [_ entry] entry)))
    :draft-service (var-get #'open.atlas.studio.builder-server/draft-service)
    :declarations (tools/declarations mcp/mountable-declarations)
    :registry #(tools/registry % mcp/mountable-declarations) :tools-list tools/tools-list :call tools/call
    :now (constantly "2026-08-28T00:00:00Z")}))

(defn- token! [scope]
  (:token (wt/issue! *home* pepper {:workspace "demo" :scope scope
                                    :issued-by *owner*
                                    :issued-at "2026-08-28T00:00:00Z"
                                    :expires-at "2026-09-28T00:00:00Z"
                                    :label "publish-mcp-test"})))

(defn- call! [token tool args]
  (json/parse-string
   (:body ((wire/routes (deps))
           {:request-method :post :uri "/mcp"
            :headers {"host" "127.0.0.1:9631" "authorization" (str "Bearer " token)}
            :body (java.io.ByteArrayInputStream.
                   (.getBytes (json/generate-string
                               {:jsonrpc "2.0" :id 1 :method "tools/call"
                                :params {:name tool :arguments args}})
                              "UTF-8"))}))
   true))

(defn- ok! [res]
  (is (nil? (get-in res [:result :isError])) (pr-str res))
  (json/parse-string (get-in res [:result :content 0 :text]) true))

(defn- refusal [res]
  (is (true? (get-in res [:result :isError])) (pr-str res))
  (let [v (clojure.edn/read-string (get-in res [:result :content 0 :text]))]
    (:findings (or (:error v) v))))

(defn- serving [] (rgraph/serving-version (:graph (:runtime *state*)) "demo/app"))

(defn- serving-status!
  "`serving_status`'s old shape ({:serving :cut :on-shelf}), now the `:serving`
   block of `inspect_graph`'s summary (8.4.16 裁定九)."
  [t graph]
  (:serving (ok! (call! t "inspect_graph" {:graph graph}))))

;; ---------------------------------------------------------------------------

(deftest the-loop-closes
  "Cut → publish → the pointer moves. The steps come back by name because an
   agent that only hears «ok» cannot tell a publish that activated from one
   that merely staged."
  (let [t (token! #{:read :write})]
    (testing "before publishing, nothing is served and nothing is cut"
      (let [s (serving-status! t "demo/app")]
        (is (nil? (:serving s)))
        (is (= [] (:cut s)))
        (is (= [] (:on-shelf s)))))
    (testing "publish runs the whole command sequence"
      (let [r (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))]
        (is (= 1 (:version r)))
        (is (= [:owner :plan :offer :stage :load :activate] (mapv keyword (:steps r)))
            "the same six-step boundary a remote Server receives — owner check
             and closure plan included, because those are where a publish is
             refused for reasons the last four steps cannot see")
        (is (= 1 (:serving r)))))
    (testing "and the runtime itself agrees — not just the reply"
      (is (= 1 (serving)))
      (let [s (serving-status! t "demo/app")]
        (is (= 1 (:serving s)))
        (is (= [1] (:cut s)))
        (is (= [1] (:on-shelf s)))))))

(deftest a-second-publish-carries-the-pointer-it-actually-found
  "The CAS guard's real job. Publishing twice in a row must WORK — the pointer
   is read immediately before activate, not assumed — and this is the exact
   bug the hardcoded nil once caused: every publish after the first was
   refused, and the guard was right, because the lie was ours."
  (let [t (token! #{:read :write})]
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (let [r (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))]
      (is (= 2 (:version r)))
      (is (= 2 (:serving r))))
    (is (= 2 (serving)))))

(deftest a-publisher-that-lost-the-race-is-refused
  "Two publishers is no longer a rare human coincidence once an agent is in the
   loop: a person and an agent work the same graph at once. The activate step's
   compare-and-swap is what makes the loser lose loudly instead of silently
   overwriting."
  (let [t (token! #{:read :write})
        svc ((var-get #'open.atlas.studio.builder-server/publish-service)
             {:workspace "demo" :principal *owner*})]
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (testing "a publish planned against a stale pointer is refused by the guard"
      ;; Publish through the app directly with the pointer as it was BEFORE the
      ;; first publish — which is precisely what a second publisher holding an
      ;; older read would present.
      (let [at (str (.truncatedTo (java.time.Instant/now)
                                  java.time.temporal.ChronoUnit/SECONDS))
            out (app/publish! @(var-get #'open.atlas.studio.builder-server/studio-state)
                              {:graph "demo/app" :version 1
                               :actor *owner*
                               :actor-key-id actor-key-id :actor-secret actor-secret
                               :deployment-id "studio-stale"
                               :request-id-fn (fn [s] (str "stale-" (pr-str s)))
                               ;; The wall clock, not the fixture's fixed
                               ;; instant: the control envelope carries an
                               ;; expiry the runtime checks, so a frozen `now`
                               ;; is refused for being expired long before the
                               ;; CAS guard is ever consulted.
                               :now at
                               :expires-at (str (.plusSeconds (java.time.Instant/parse at) 300))
                               :expected-current nil})]
        (is (seq (:findings out)) "a stale expectation must not silently win")
        (is (some #(str/includes? (str (:code %)) "stale-expectation") (:findings out))
            (pr-str (mapv :code (:findings out))))))
    (testing "and the pointer did not move"
      (is (= 1 (serving))))
    (is (some? svc))))

(deftest rollback-points-back-and-refuses-what-was-never-cut
  (let [t (token! #{:read :write})]
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (is (= 2 (serving)))
    (testing "back to version 1"
      (let [r (ok! (call! t "publish_graph" {:graph "demo/app" :to_version 1 :apply true}))]
        (is (= 1 (:to-version r)))
        (is (= 1 (:serving r))))
      (is (= 1 (serving))))
    (testing "a version that was never cut is refused BY NAME, listing what was"
      (let [fs (refusal (call! t "publish_graph"
                               {:graph "demo/app" :to_version 99 :apply true}))]
        (is (= [:tools.publish/refused] (mapv :code fs)))
        (is (= :version-unknown (get-in (first fs) [:got :code])))
        (is (= [1 2] (get-in (first fs) [:got :cut]))
            "and says which versions exist, so the next call can be right")))
    (testing "rolling back to what is already served is refused rather than
              being a no-op that looks like success"
      (let [fs (refusal (call! t "publish_graph"
                               {:graph "demo/app" :to_version 1 :apply true}))]
        (is (= :version-already-serving (get-in (first fs) [:got :code])))))))

(deftest an-empty-recipe-is-refused-at-the-cut
  "doc 106 §106.12 13.3: a graph whose recipe names nothing passed every check
   and published a version carrying nothing. `:cut.structure/empty-recipe` is
   an error, so the preflight — the same one the GUI's Release tab reads — says
   so and names the two doors that fill a recipe."
  (let [t (token! #{:read :write})]
    (graph/register-graph! (:conn *state*)
                           {:workspace "demo" :name "hollow"
                            :created-at "2026-08-28T00:00:00Z"
                            :members {}})
    (let [fs (refusal (call! t "publish_graph" {:graph "demo/hollow" :apply true}))
          codes (set (map :code (concat fs (:findings (:got (first fs))))))]
      (is (contains? codes :cut.structure/empty-recipe) (pr-str fs)))))

(def ^:private versions-service
  "The Builder's own read. No MCP tool: an agent has `serving_status`, and a
   second name over the same reads would be two words for one fact."
  (fn [caller args]
    ((:versions ((var-get #'open.atlas.studio.builder-server/publish-service) caller))
     args)))

(deftest versions-agrees-with-serving-status-and-carries-the-records
  "The list a person rolls back from. Numbers alone cannot answer «which of
   these is the one from before this morning», so this read carries the records
   — and it must not disagree with `serving_status` about what was cut, which is
   why both derive from `version/versions` rather than each counting for
   itself."
  (let [t (token! #{:read :write})
        caller {:workspace "demo" :principal *owner*}]
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (let [status (serving-status! t "demo/app")
          v (versions-service caller {:graph "demo/app"})]
      (is (= [1 2] (:cut status)))
      (testing "the same versions, newest first"
        (is (= [2 1] (mapv :version/number (:versions v)))))
      (testing "and the same pointer and shelf as the agent's read"
        (is (= (:serving status) (:serving v)))
        (is (= (:on-shelf status) (:on-shelf v))))
      (testing "each record says when it was cut and by whom — the half a
                number cannot carry"
        (is (every? #(and (string? (:version/cut-at %))
                          (seq (:version/cut-at %)))
                    (:versions v)))
        (is (every? #(re-find #":subject" (str (:version/cut-by %))) (:versions v)))))
    (testing "and it refuses at the same bar publishing does"
      (is (= :graph-unknown
             (:code (try (versions-service {:workspace "demo" :principal *owner*}
                                           {:graph "demo/nope"})
                         nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))))))))

(def ^:private cut-check-service
  (fn [caller args]
    ((:cut-check ((var-get #'open.atlas.studio.builder-server/publish-service) caller))
     args)))

(declare orders-db!)

(deftest imported-semantic-package-can-be-configured-then-activated
  (let [conn (:conn *state*)
        definition (graph/graph (pool/db conn) "demo/app")
        graph-id (:graph/id definition)
        caller {:workspace "demo" :principal *owner*}
        request {:text (slurp "../../docs/guides/examples/orders-semantic-graph-2.0.edn")
                 :root-graph "demo/app"}]
    (check! (graph/update-recipe! conn "demo/app" {:members {}}))
    (let [planned (semantic-package/plan conn caller request semantic/checkers)
          imported (semantic-package/apply! conn caller
                     (assoc request :targets (:targets planned) :plan-id (:plan-id planned))
                     semantic/checkers {:entrypoint :ui :actor *owner*})]
      (is (empty? (:findings planned)) (pr-str planned))
      (is (true? (:imported? imported)) (pr-str imported))
      (is (false? (:would-cut? (cut-check-service caller {:graph "demo/app"})))
          "Import is allowed before the required physical Source exists"))
    (check! (pool/execute-action conn {:action :create :type :source :id "orders-db"
                                       :workspace "demo" :expected_revision 0
                                       :entry {:type "sql" :label "Orders DB"
                                               :jdbc_url (orders-db! (str (io/file *home* "upload-orders.db")) "PAID")}}))
    (is (= 1 (:revision (pool/semantic-draft (pool/db conn) "demo" graph-id))))
    (let [check (cut-check-service caller {:graph "demo/app"})]
      (is (true? (:would-cut? check)) (pr-str check))
      (is (= :semantic-contract (:execution-target check)))
      (is (= 1 (:config-revision check)))
      (is (= :unpublished (get-in check [:release-status :state])))
      (is (empty? (:health-findings check))))
    (let [token (token! #{:read :write})
          read-back (ok! (call! token "get_graph" {:graph "demo/app"}))
          text (pr-str (assoc-in (edn/read-string (:text read-back)) [:graph :graph/label] "AI edited orders"))
          revision (:revision read-back)
          saved (ok! (call! token "upload_graph"
                           {:graph "demo/app" :text text :expected_revision revision}))]
      (is (string? text))
      (is (= 1 revision))
      (is (= 2 (:revision saved)))
      (is (nil? (serving)) "Upload does not activate a release")
      (is (seq (refusal (call! token "upload_graph"
                              {:graph "demo/app" :text text :expected_revision revision}))))
      (is (seq (refusal (call! token "upload_graph"
                              {:graph "demo/app" :text "invalid" :expected_revision 2}))))
      (is (seq (refusal (call! token "upload_graph" {:graph "demo/app" :text text}))))
      (is (seq (refusal (call! (token! #{:read}) "upload_graph"
                              {:graph "demo/app" :text text :expected_revision 2}))))
      (is (= 2 (:revision (ok! (call! token "get_graph" {:graph "demo/app"})))))
      (let [difference (take 2 (data/diff (assoc-in (edn/read-string text) [:publication :source/revision] 2)
                                         (clojure.edn/read-string (:text (ok! (call! token "get_graph" {:graph "demo/app"}))))))]
        (is (= [nil nil] difference))))
    (let [r (ok! (call! (token! #{:read :write}) "publish_graph"
                       {:graph "demo/app" :apply true}))
          content (slurp (io/file (:artifact-root *state*) "demo" "app" "1" "content.edn"))]
      (is (= 1 (:version r)))
      (is (= 1 (:serving r)))
      (let [check (cut-check-service caller {:graph "demo/app"})]
        (is (= :current (get-in check [:release-status :state])))
        (is (= 2 (get-in check [:release-status :serving-draft-revision]))))
      (is (semantic-artifact/format-two? {"content.edn" content})
          "the published bytes are Contract Artifact format 2, not the empty legacy recipe")
      (let [draft (pool/semantic-draft (pool/db conn) "demo" graph-id)
            saved (pool/save-semantic-draft! conn "demo" graph-id (:revision draft)
                    (assoc-in (:document draft) [:graph :graph/label] "New import")
                    semantic/checkers {:entrypoint :ui :actor *owner* :at "2026-09-24T00:00:00Z"})
            check (cut-check-service caller {:graph "demo/app"})]
        (is (empty? (:findings saved)))
        (is (= :changed (get-in check [:release-status :state])))
        (is (= 3 (:config-revision check)))
        (is (= 2 (get-in check [:release-status :serving-draft-revision])))
        (is (= "2026-09-24T00:00:00Z" (:saved-at check))))
      (let [now "2026-08-28T00:00:00Z"
            issued (runtime-token/issue! (get-in *state* [:runtime :token])
                                        {:graph-id "demo/app" :version :latest :actor "test"
                                         :request-id (str (random-uuid)) :now now :expires-at nil})
            handler (:handler (first (filter #(= "execute_eql" (:name %)) mcp/mountable-declarations)))
            query #(handler {:parts (:runtime *state*) :credential (:secret issued) :now now}
                            {:query "{:entity {:demo.order/id 1001} :query [:demo.order/status]}"})
            before (query)]
        (is (empty? (:findings issued)))
        (let [service ((var-get #'open.atlas.studio.builder-server/graph-service) caller)
              renamed ((:rename-graph! service) {:graph "demo/app" :name "Order Insights"})]
          (is (= "Order Insights" (get-in renamed [:graph :graph/display-name])))
          (is (= graph-id (get-in renamed [:graph :graph/id])))
          (is (= "demo/app" (get-in renamed [:graph :graph/qualified-name])))
          (is (= content (slurp (io/file (:artifact-root *state*) "demo" "app" "1" "content.edn")))))
        (let [routes (mcp/routes {:instance (:runtime *state*) :now (constantly now)})
              request (fn [uri method params]
                        (routes {:request-method :post :uri uri
                                 :headers {"authorization" (str "Bearer " (:secret issued))}
                                 :body (java.io.ByteArrayInputStream.
                                        (.getBytes (json/generate-string
                                                    {:jsonrpc "2.0" :id 1 :method method :params params})
                                                   "UTF-8"))}))
              args {:name "execute_eql"
                    :arguments {:query "{:entity {:demo.order/id 1001} :query [:demo.order/status]}"}}]
          (is (= 200 (:status (request (str "/mcp/" graph-id) "initialize" {}))))
          (is (= 403 (:status (request "/mcp/00000000-0000-4000-8000-000000000000" "tools/call" args))))
          (let [response (request (str "/mcp/" graph-id) "tools/call" args)
                result (json/parse-string (:body response) true)]
            (is (= 200 (:status response)))
            (is (not (true? (get-in result [:result :isError]))))
            (is (str/includes? (:body response) "PAID"))))
        (is (= 1 (:env/version before)))
        (is (= "PAID" (get-in before [:result :demo.order/status])))
        (let [builder-token (token! #{:read :write})
              draft (ok! (call! builder-token "get_graph" {:graph "demo/app"}))
              edited (assoc-in (edn/read-string (:text draft))
                               [:execution :bindings "order-by-id-sql" :binding/implementation :query]
                               "SELECT customer_id, from_acct_no, 'UPDATED' AS status, total FROM orders WHERE id = :id")
              saved (ok! (call! builder-token "upload_graph"
                               {:graph "demo/app" :text (pr-str edited) :expected_revision (:revision draft)}))
              published (ok! (call! builder-token "publish_graph"
                                   {:graph "demo/app" :apply true}))
              after (query)]
          (is (= 4 (:revision saved)))
          (is (= 2 (:version published) (:serving published)))
          (is (= 2 (:env/version after)) "The same token queries the newly active release")
          (is (= "UPDATED" (get-in after [:result :demo.order/status]))))))))

(defn- orders-db! [path status]
  (io/make-parents (io/file path))
  (let [url (str "jdbc:sqlite:" path)]
    (with-open [db (java.sql.DriverManager/getConnection url)]
      (jdbc/execute! db ["CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL, from_acct_no TEXT, status TEXT NOT NULL, total INTEGER NOT NULL)"])
      (jdbc/execute! db ["INSERT INTO orders VALUES (1001, 77, 'V-001', ?, 125)" status])
      (jdbc/execute! db ["CREATE TABLE part (sku_No TEXT PRIMARY KEY, description TEXT)"])
      (jdbc/execute! db ["INSERT INTO part VALUES ('SKU-1', 'Part')"]))
    url))

(deftest changing-a-source-publishes-a-new-release-without-overwriting-the-old-binding
  (let [conn (:conn *state*)
        definition (graph/graph (pool/db conn) "demo/app")
        graph-id (:graph/id definition)
        caller {:workspace "demo" :principal *owner*}
        old-url (orders-db! (str (io/file *home* "old-orders.db")) "OLD")
        new-url (orders-db! (str (io/file *home* "new-orders.db")) "NEW")
        request {:text (slurp "../../docs/guides/examples/orders-semantic-graph-2.0.edn")
                 :root-graph "demo/app"}
        query-version
        (fn [version]
          (runtime-env/query (get-in *state* [:runtime :env])
                             {:graph-id "demo/app"
                              :authorization {:version version}
                              :request {:entity {:demo.order/id 1001}
                                        :query [:demo.order/status]}}))]
    (check! (graph/update-recipe! conn "demo/app" {:members {}}))
    (let [planned (semantic-package/plan conn caller request semantic/checkers)]
      (check! (semantic-package/apply! conn caller
                                       (assoc request :targets (:targets planned)
                                              :plan-id (:plan-id planned))
                                       semantic/checkers {:entrypoint :ui :actor *owner*})))
    (check! (pool/execute-action conn {:action :create :type :source :id "orders-db"
                                       :workspace "demo" :expected_revision 0
                                       :entry {:type "sql" :label "Orders DB" :jdbc_url old-url}}))
    (let [v1 (binding [auth/*request-user* {:principal *owner* :default-workspace "demo"}]
               (publish-http-handler {:graph "demo/app"}))]
      (is (= 1 (:version v1)))
      (let [answer (query-version 1)]
        (is (= "OLD" (get-in answer [:engine/data :demo.order/status])) (pr-str answer))))
    (testing "retries and concurrent clicks reuse the serving release"
      (let [publish #(binding [auth/*request-user* {:principal *owner* :default-workspace "demo"}]
                       (publish-http-handler {:graph "demo/app"}))
            attempts (mapv (fn [_] (future (publish))) (range 3))]
        (doseq [attempt attempts]
          (let [r @attempt]
            (is (= 1 (:version r) (:serving r)))
            (is (true? (:unchanged? r)))))
        (is (= [1] (mapv :version/number (:versions (versions-service caller {:graph "demo/app"})))))))
    (testing "saving identical content changes its revision but does not need another release"
      (let [draft (pool/semantic-draft (pool/db conn) "demo" graph-id)]
        (check! (pool/save-semantic-draft! conn "demo" graph-id (:revision draft)
                                         (:document draft) semantic/checkers {:entrypoint :ui :actor *owner*})))
      (is (= :current (get-in (cut-check-service caller {:graph "demo/app"}) [:release-status :state])))
      (let [r (binding [auth/*request-user* {:principal *owner* :default-workspace "demo"}]
                (publish-http-handler {:graph "demo/app"}))]
        (is (= 1 (:version r)))
        (is (true? (:unchanged? r)))))
    (let [before (pool/object (pool/db conn) :source "orders-db")
          saved (pool/execute-action conn {:action :update :type :source :id "orders-db"
                                           :workspace "demo"
                                           :expected_revision (:pool/revision before)
                                           :entry {:type "sql" :label "Orders DB" :jdbc_url new-url}})
          reopened (pool/object (pool/db conn) :source "orders-db")]
      (check! saved)
      (is (= new-url (get-in reopened [:pool/entry :jdbc_url])))
      (is (= (inc (:pool/revision before)) (:pool/revision reopened))))
    (is (= :changed (get-in (cut-check-service caller {:graph "demo/app"}) [:release-status :state]))
        "Source-only changes must still be publishable")
    (let [v2 (binding [auth/*request-user* {:principal *owner* :default-workspace "demo"}]
               (publish-http-handler {:graph "demo/app"}))]
      (is (= 2 (:version v2)))
      (is (= 2 (:serving v2))))
    (let [current (query-version 2)
          previous (query-version 1)]
      (is (= "NEW" (get-in current [:engine/data :demo.order/status]))
          (str "the current release executes through the newly saved Source: " (pr-str current)))
      (is (= "OLD" (get-in previous [:engine/data :demo.order/status]))
          (str "the immutable old release still resolves its own Source for rollback: " (pr-str previous))))
    (is (= 2 (rgraph/serving-version (:graph (:runtime *state*)) "demo/app")))))

(deftest cut-check-answers-before-the-press-and-behind-the-same-guard
  "«Free to write, strict to seal» (D2.2) puts the seal at the far side of the
   publish button, so until this read existed the only way to ask «is this graph
   publishable» was to press publish and read the refusal — a write attempted as
   a question."
  (let [caller {:workspace "demo" :principal *owner*}
        r (cut-check-service caller {:graph "demo/app"})]
    (is (true? (:would-cut? r)))
    (is (empty? (:health-findings r)))
    (testing "and the answer survives being asked twice — it writes nothing"
      (is (= r (cut-check-service caller {:graph "demo/app"}))))
    (testing "a graph outside this workspace refuses exactly as publishing would"
      (is (= :graph-unknown
             (:code (try (cut-check-service caller {:graph "demo/nope"})
                         nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))))))))

(def ^:private export-service
  (fn [caller args]
    ((:export ((var-get #'open.atlas.studio.builder-server/publish-service) caller))
     args)))

(deftest export-hands-over-the-shelf-bytes-verbatim-and-checked
  "A bundle is loaded on a machine this one cannot see (D4.13), so «the shelf
   changed under us» is exactly the thing its receiver has no way to notice.
   The digest is recomputed from the bytes being handed over and compared with
   the one recorded at cut time."
  (let [t (token! #{:read :write})
        caller {:workspace "demo" :principal *owner*}]
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (let [b (export-service caller {:graph "demo/app" :version 1})]
      (is (= "demo/app" (:graph b)))
      (is (= 1 (:version b)))
      (testing "all three files, and only those"
        (is (= (set artifact/artifact-files) (set (keys (:files b))))))
      (testing "the digest it reports is the one recomputed from what it hands over"
        (is (= (:artifact-digest b) (artifact/artifact-digest (:files b)))))
      (testing "and it is the digest the version registry recorded at the cut"
        (is (= (:artifact-digest b)
               (:version/artifact-digest
                (first (filter #(= 1 (:version/number %))
                               (version/versions (pool/db (:conn *state*)) "demo/app")))))))
      (testing "a version parsed from a query string works the same — the route
                hands strings through"
        (is (= b (export-service caller {:graph "demo/app" :version "1"}))))))
  (testing "a version that was never cut is refused by name, listing what was"
    (let [caller {:workspace "demo" :principal *owner*}
          d (try (export-service caller {:graph "demo/app" :version 99}) nil
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :version-unknown (:code d)))
      (is (= [1] (:cut d)))))
  (testing "and a graph outside this workspace refuses as publishing would"
    (let [caller {:workspace "demo" :principal *owner*}]
      (is (= :graph-unknown
             (:code (try (export-service caller {:graph "demo/nope" :version 1}) nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))))))))

(deftest export-refuses-when-the-shelf-no-longer-matches-the-recorded-digest
  "The one failure a receiver cannot detect for themselves, caught on this side
   while there is still somebody to tell."
  (let [t (token! #{:read :write})
        caller {:workspace "demo" :principal *owner*}]
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (let [f (io/file (:artifact-root *state*) "demo" "app" "1" "report.edn")]
      (spit f (str (slurp f) "\n;; tampered\n")))
    (let [d (try (export-service caller {:graph "demo/app" :version 1}) nil
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :artifact-digest-mismatch (:code d)))
      (is (not= (:expected d) (:got d))))))

(def ^:private bundle-bytes #'open.atlas.studio.builder-server/bundle-bytes)
(def ^:private bundle-filename #'open.atlas.studio.builder-server/bundle-filename)

(defn- entries-of [^bytes zip]
  (with-open [in (java.util.zip.ZipInputStream. (java.io.ByteArrayInputStream. zip))]
    (loop [acc []]
      (if-let [e (.getNextEntry in)]
        (let [out (java.io.ByteArrayOutputStream.)]
          (io/copy in out)
          (recur (conj acc [(.getName e) (String. (.toByteArray out) "UTF-8")])))
        acc))))

(deftest a-bundle-is-laid-out-like-the-shelf
  "D4.13's whole content: the zip is a subtree of the artifact root, so unzipping
   it onto one just works. Pinned on THIS side because the Server's unpacker is
   pinned on the other, and the two are an agreement between two applications —
   nobody would notice them drifting until a delivery failed."
  (let [z (bundle-bytes {:workspace "demo" :name "app" :version 3
                         :files {"content.edn" "C" "manifest.edn" "M" "report.edn" "R"}})
        entries (entries-of z)]
    (is (= ["demo/app/3/content.edn" "demo/app/3/manifest.edn" "demo/app/3/report.edn"]
           (mapv first entries)))
    (is (= ["C" "M" "R"] (mapv second entries)))
    (testing "fixed order, like every other byte-stable output here"
      (is (= (mapv first entries)
             (mapv #(str "demo/app/3/" %) artifact/artifact-files))))))

(deftest exporting-the-same-version-twice-gives-the-same-bytes
  "A bundle is a thing people checksum and diff; «the same version gave me a
   different file» is a question nobody should have to answer. The entry
   timestamps are fixed for exactly this."
  (let [args {:workspace "demo" :name "app" :version 1
              :files {"content.edn" "C" "manifest.edn" "M" "report.edn" "R"}}]
    (is (java.util.Arrays/equals ^bytes (bundle-bytes args) ^bytes (bundle-bytes args)))))

(deftest a-bundle-filename-survives-a-downloads-folder
  "The qualified name's slash cannot go in a filename, and a downloads folder is
   flat — two workspaces' `app` graphs must not both arrive as `app-v3.zip`."
  (is (= "demo-app-v3.zip" (bundle-filename {:workspace "demo" :name "app" :version 3})))
  (is (= "other-app-v3.zip" (bundle-filename {:workspace "other" :name "app" :version 3})))
  (testing "and anything a filesystem would argue about is flattened, not passed through"
    (is (= "a_b-c_d-v2.zip" (bundle-filename {:workspace "a/b" :name "c d" :version 2})))))

(deftest going-forward-again-after-a-rollback-is-accepted-by-the-runtime
  "The path the direction ruling (D3.16 ⑤) newly sends as `:activate` rather than
   `:rollback`. It has to satisfy the activate-mode checks the runtime applies to
   ANY pointer move: the CAS, the plan rooted at the target, and — activate mode
   specifically — a receipt whose digest matches that plan. All three hold for a
   version that was published earlier and then rolled away from, but nothing
   proved it until this move stopped being labelled a rollback."
  (let [t (token! #{:read :write})]
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (is (= 2 (serving)))
    (testing "back to v1"
      (ok! (call! t "publish_graph" {:graph "demo/app" :to_version 1 :apply true}))
      (is (= 1 (serving))))
    (testing "and forward to v2 again — the same tool, the other direction"
      (let [r (ok! (call! t "publish_graph" {:graph "demo/app" :to_version 2 :apply true}))]
        (is (= 2 (:to-version r)))
        (is (= 2 (:serving r))))
      (is (= 2 (serving))))
    (testing "and back down once more, so this is not a one-way door"
      (ok! (call! t "publish_graph" {:graph "demo/app" :to_version 1 :apply true}))
      (is (= 1 (serving))))))

(deftest preflight-writes-nothing
  "«Free to write, strict to seal» (D2.2). `publish_graph` WITHOUT `apply` is
   how an agent finds out whether a write would cost the graph its next
   version BEFORE spending it (8.4.16 裁定九 folds `preflight_graph` in as the
   default) — so it must not itself be a write."
  (let [t (token! #{:read})
        before (pool/export-pool (pool/db (:conn *state*)))
        ;; The action rides as an EDN STRING — the wire convention (8.4 P1b).
        ;; A nested JSON object would arrive with string values, and `:type
        ;; "attribute"` is not `:attribute`: the pool would refuse an
        ;; unaddressable object. Coercion is per declared parameter, so a map
        ;; parameter is coerced as a whole or not at all.
        r (ok! (call! t "publish_graph"
                      {:graph "demo/app"
                       :action (pr-str {:workspace "demo" :action :update :type :attribute :id :cis.a/name
                                        :entry {:type :string :label "改名"}
                                        :expected_revision 1})}))]
    (is (contains? r :would-cut?))
    (is (= before (pool/export-pool (pool/db (:conn *state*))))
        "the pool is byte-identical afterwards")
    (is (nil? (serving)) "and nothing was published")))

(deftest publishing-needs-the-write-scope
  (let [reader (token! #{:read})
        listed (fn [tok]
                 (set (map :name (get-in (json/parse-string
                                          (:body ((wire/routes (deps))
                                                  {:request-method :post :uri "/mcp"
                                                   :headers {"host" "h"
                                                             "authorization" (str "Bearer " tok)}
                                                   :body (java.io.ByteArrayInputStream.
                                                          (.getBytes (json/generate-string
                                                                      {:jsonrpc "2.0" :id 1
                                                                       :method "tools/list"})
                                                                     "UTF-8"))}))
                                          true)
                                         [:result :tools]))))]
    (testing "a read key sees inspect_graph (the pointer) and publish_graph's
              check half (no apply), which is the whole point of folding
              preflight in as the default rather than a third tool"
      (let [l (listed reader)]
        (is (contains? l "inspect_graph"))
        (is (contains? l "publish_graph"))))
    (testing "the check half runs for a read key — it writes nothing"
      (ok! (call! reader "publish_graph" {:graph "demo/app"})))
    (testing "and apply says «read-only», not «unknown» — the tool exists,
              only the write half is gated (裁定 ②'s rule applies at apply,
              not at the whole tool, per publish_declarations' capability note)"
      (let [fs (refusal (call! reader "publish_graph" {:graph "demo/app" :apply true}))]
        (is (= [:tools.publish/refused] (mapv :code fs)))))))

(deftest no-token-secret-rides-along-with-a-publish
  "Ruling ③, checked at the loop's last step: publishing signs deploy envelopes
   with this instance's actor secret, and none of it may reach the wire."
  (let [t (token! #{:read :write})
        responses [(call! t "publish_graph" {:graph "demo/app" :apply true})
                   (call! t "inspect_graph" {:graph "demo/app"})
                   (call! t "publish_graph" {:graph "demo/app" :to_version 99 :apply true})]]
    (doseq [r responses]
      (let [s (pr-str r)]
        (is (not (str/includes? s actor-secret)))
        (is (not (str/includes? s t)))
        (is (not (str/includes? s "studio-pepper")))))))

(deftest unbound-import-cannot-publish
  (let [caller {:workspace "demo" :principal *owner*}
        svc (semantic/service (:conn *state*) caller (fn [_ source] source))
        imported (svc :import {:graph "demo/app" :expected_revision 0
                               :text (slurp "../../docs/guides/examples/ai-semantic-contract-example-2.0.edn")}
                      {:entrypoint :ui :actor *owner*})]
    (is (:imported? imported) (pr-str imported))
    (is (some #(str/starts-with? (:binding/source %) "unbound:")
              (vals (get-in imported [:document :execution :bindings]))))
    (is (seq (refusal (call! (token! #{:read :write}) "publish_graph" {:graph "demo/app" :apply true}))))
    (is (nil? (serving)))
    (is (empty? (:versions (versions-service caller {:graph "demo/app"}))))))


;; ---------------------------------------------------------------------------
;; Saved queries, end to end (proposal studio-saved-queries, owner 2026-10-10)
;; ---------------------------------------------------------------------------

(defn- orders-graph!
  "The orders example as a semantic graph over a real SQLite source — the
   setup `imported-semantic-package-can-be-configured-then-activated` uses."
  []
  (let [conn (:conn *state*)
        caller {:workspace "demo" :principal *owner*}
        request {:text (slurp "../../docs/guides/examples/orders-semantic-graph-2.0.edn")
                 :root-graph "demo/app"}]
    (check! (graph/update-recipe! conn "demo/app" {:members {}}))
    (let [planned (semantic-package/plan conn caller request semantic/checkers)]
      (semantic-package/apply! conn caller
                               (assoc request :targets (:targets planned) :plan-id (:plan-id planned))
                               semantic/checkers {:entrypoint :ui :actor *owner*}))
    (check! (pool/execute-action conn {:action :create :type :source :id "orders-db"
                                       :workspace "demo" :expected_revision 0
                                       :entry {:type "sql" :label "Orders DB"
                                               :jdbc_url (orders-db! (str (io/file *home* "sq-orders.db")) "PAID")}}))))

(defn- data-plane-shell
  "The data plane exactly as `-main` assembles it, saved queries included."
  [now]
  {:instance (:runtime *state*) :now (constantly now)
   :saved-queries (fn [graph-uuid version]
                    (graph/pinned-saved-queries (pool/db (:conn *state*)) graph-uuid version))})

(deftest an-agent-saves-a-query-and-a-consumer-runs-it-by-name
  (orders-graph!)
  (let [t (token! #{:read :write})
        caller {:workspace "demo" :principal *owner*}
        graph-id (:graph/id (graph/graph (pool/db (:conn *state*)) "demo/app"))
        now "2026-10-10T00:00:00Z"
        ;; Issued after v1 exists: a graph token is for a published graph.
        _ (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
        issued (runtime-token/issue! (get-in *state* [:runtime :token])
                                     {:graph-id "demo/app" :version :latest :actor "test"
                                      :request-id (str (random-uuid)) :now now :expires-at nil})
        shell (data-plane-shell now)
        routes (mcp/routes shell)
        consumer (fn [tool args]
                   (let [r (json/parse-string
                            (:body (routes {:request-method :post :uri (str "/mcp/" graph-id)
                                            :headers {"authorization" (str "Bearer " (:secret issued))}
                                            :body (java.io.ByteArrayInputStream.
                                                   (.getBytes (json/generate-string
                                                               {:jsonrpc "2.0" :id 1 :method "tools/call"
                                                                :params {:name tool :arguments args}})
                                                              "UTF-8"))}))
                            true)]
                     (if (get-in r [:result :isError])
                       {:refused (get-in r [:result :content 0 :text])}
                       (json/parse-string (get-in r [:result :content 0 :text]) true))))
        order-status {:graph "demo/app" :name "order_status"
                      :description "One order's status, by order id."
                      :params [{:name "id" :type "integer" :required true :example 1001
                                :description "The order id."}]
                      :eql "{:entity {:demo.order/id ?id} :query [:demo.order/status]}"}]
    (testing "v1 is published before any saved query exists"
      (is (= 1 (serving)))
      (is (= [] (:saved-queries (consumer "list_saved_queries" {})))
          "a version published without saved queries has none — nothing to migrate"))

    (testing "the agent saves one; the save RUNS it against the draft first"
      (let [r (ok! (call! t "save_saved_query" order-status))]
        (is (= true (get-in r [:verification :ran])))
        (is (= false (:published r))))
      (is (= ["order_status"] (map :name (:saved-queries (ok! (call! t "list_saved_queries" {:graph "demo/app"})))))))

    (testing "a query naming an attribute the model does not have is refused, with the attribute"
      (let [fs (refusal (call! t "save_saved_query"
                               (assoc order-status :name "bad"
                                      :eql "{:entity {:demo.order/id ?id} :query [:demo.order/nope]}")))]
        (is (= ["saved-query/stale"] (map #(subs (str (:code %)) 1) fs)))))

    (testing "a read-only key cannot save"
      (is (refusal (call! (token! #{:read}) "save_saved_query" (assoc order-status :name "ro")))))

    (testing "saving changes nothing the consumer sees until it is published"
      (is (= [] (:saved-queries (consumer "list_saved_queries" {})))))

    (testing "a change to saved queries alone is a change worth a version (ruling #1)"
      (is (= :changed (get-in (cut-check-service caller {:graph "demo/app"}) [:release-status :state])))
      (let [r (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))]
        (is (= 2 (:version r)))
        (let [doc #(-> (slurp (io/file (:artifact-root *state*) "demo" "app" (str %) "content.edn"))
                       edn/read-string
                       (update-in [:document :publication] dissoc :source/revision :release/version))]
          (is (= (doc 1) (doc 2))
              "the model is the same but for its release number: saved queries never enter content.edn")))
      (is (= :current (get-in (cut-check-service caller {:graph "demo/app"}) [:release-status :state]))))

    (testing "the consumer lists it and runs it by name"
      (let [listed (consumer "list_saved_queries" {})]
        (is (= ["order_status"] (map :name (:saved-queries listed))))
        (is (= ["id"] (get-in listed [:saved-queries 0 :params :required]))))
      (let [r (consumer "run_saved_query" {:name "order_status" :params {:id 1001}})]
        (is (= "PAID" (get-in r [:result :demo.order/status])) (pr-str r))
        (is (= 2 (:env/version r))))
      (is (str/includes? (:refused (consumer "run_saved_query" {:name "order_status" :params {:id "x"}}))
                         "param-type")))

    (testing "/eql runs it too"
      (let [handler (data-plane/routes (assoc shell :saved-query (mcp/saved-query-request shell)))
            r (handler {:request-method :post :uri "/eql"
                        :headers {"authorization" (str "Bearer " (:secret issued))}
                        :body (java.io.ByteArrayInputStream.
                               (.getBytes "{:saved-query \"order_status\" :params {\"id\" 1001}}" "UTF-8"))})]
        (is (= 200 (:status r)) (:body r))
        (is (= "PAID" (get-in (edn/read-string (:body r)) [:result :demo.order/status])))))

    (testing "rollback serves the set that version was published with"
      (ok! (call! t "publish_graph" {:graph "demo/app" :to_version 1 :apply true}))
      (is (= [] (:saved-queries (consumer "list_saved_queries" {}))))
      (ok! (call! t "publish_graph" {:graph "demo/app" :to_version 2 :apply true}))
      (is (= ["order_status"] (map :name (:saved-queries (consumer "list_saved_queries" {}))))))

    (testing "a saved query the model no longer matches blocks the publish, by name"
      (graph/set-saved-queries! (:conn *state*) "demo/app"
                                [{:name "gone" :description "d" :params []
                                  :eql [:demo.order/removed_attribute]}])
      (let [fs (refusal (call! t "publish_graph" {:graph "demo/app" :apply true}))]
        (is (some #(= "saved-query/stale" (subs (str (:code %)) 1)) fs) (pr-str fs)))
      (is (= 2 (serving)) "nothing was published"))

    (testing "delete removes it from the draft only"
      (graph/set-saved-queries! (:conn *state*) "demo/app"
                                (graph/pinned-saved-queries (pool/db (:conn *state*)) graph-id 2))
      (ok! (call! t "delete_saved_query" {:graph "demo/app" :name "order_status"}))
      (is (= [] (:saved-queries (ok! (call! t "list_saved_queries" {:graph "demo/app"})))))
      (is (= ["order_status"] (map :name (:saved-queries (consumer "list_saved_queries" {}))))
          "the serving version keeps what it was published with"))))


(deftest consumers-requests-are-recorded-for-debugging
  (recent/reset-state!)
  (orders-graph!)
  (let [t (token! #{:read :write})
        caller {:workspace "demo" :principal *owner*}
        graph-id (:graph/id (graph/graph (pool/db (:conn *state*)) "demo/app"))
        now "2026-10-10T00:00:00Z"
        _ (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
        issued (runtime-token/issue! (get-in *state* [:runtime :token])
                                     {:graph-id "demo/app" :version :latest :actor "test"
                                      :request-id (str (random-uuid)) :now now :expires-at nil})
        shell (assoc-in (data-plane-shell now) [:instance :on-query]
                        (recent/recorder (constantly (:conn *state*)) 50))
        routes (mcp/routes shell)
        consumer (fn [tool args]
                   (json/parse-string
                    (:body (routes {:request-method :post :uri (str "/mcp/" graph-id)
                                    :headers {"authorization" (str "Bearer " (:secret issued))}
                                    :body (java.io.ByteArrayInputStream.
                                           (.getBytes (json/generate-string
                                                       {:jsonrpc "2.0" :id 1 :method "tools/call"
                                                        :params {:name tool :arguments args}})
                                                      "UTF-8"))}))
                    true))
        svc ((var-get #'open.atlas.studio.builder-server/graph-service) caller)]
    (consumer "execute_eql" {:query "{:entity {:demo.order/id 1001} :query [:demo.order/status]}"})
    (let [refused (consumer "execute_eql" {:query "{:entity {:demo.order/id 1001} :query [:demo.order/nope]}"})
          text (get-in refused [:result :content 0 :text])
          rid (second (re-find #":studio/recent-id \"([^\"]+)\"" text))]
      (testing "a refusal tells the consumer which recorded request it was"
        (is (true? (get-in refused [:result :isError])))
        (is (string? rid) text))
      (testing "the graph's readers see all and failed, newest first, with the EQL and never rows"
        (let [r ((:recent-queries svc) {:graph "demo/app"})]
          (is (= 2 (count (:all r))))
          (is (= ["denied" "ok"] (map :outcome (:all r))))
          (is (= [rid] (map :id (:failed r))))
          (is (str/includes? (:eql (first (:failed r))) "demo.order/nope"))
          (is (every? #(not (contains? % :result)) (:all r)))))
      (testing "an agent reads it through the Builder MCP, by layer or by id"
        (let [r (ok! (call! t "list_recent_queries" {:graph "demo/app"}))]
          (is (= "failed" (:layer r)))
          (is (= [rid] (map :id (:entries r)))))
        (is (= rid (get-in (ok! (call! t "list_recent_queries" {:graph "demo/app" :id rid})) [:entry :id]))))
      (testing "pinning keeps it beyond the ring"
        ((:pin-recent-query! svc) {:graph "demo/app" :id rid})
        (recent/reset-state!)
        (is (= [rid] (map :id (:pinned ((:recent-queries svc) {:graph "demo/app"})))))
        (is (= rid (:id ((:recent-query svc) {:graph "demo/app" :id rid}))) "a debug link still opens it")
        ((:unpin-recent-query! svc) {:graph "demo/app" :id rid})
        (is (= [] (:pinned ((:recent-queries svc) {:graph "demo/app"}))))))
    (testing "a capture rule pins what matches, as it happens"
      ((:add-capture-rule! svc) {:graph "demo/app" :hours 2 :failures-only? true})
      (consumer "execute_eql" {:query "{:entity {:demo.order/id 1001} :query [:demo.order/status]}"})
      (consumer "execute_eql" {:query "{:entity {:demo.order/id 1001} :query [:demo.order/still_nope]}"})
      (let [r ((:recent-queries svc) {:graph "demo/app"})]
        (is (= 1 (count (:pinned r))))
        (is (str/includes? (:eql (first (:pinned r))) "still_nope"))
        (is (= 1 (count (:rules r)))))
      (is (thrown? clojure.lang.ExceptionInfo
                   ((:add-capture-rule! svc) {:graph "demo/app" :hours 48}))
          "a rule lasts at most a day"))
    (recent/reset-state!)))

;; ---------------------------------------------------------------------------
;; Reclaiming a single version (R3.5)
;; ---------------------------------------------------------------------------

(defn- psvc-call [op caller args]
  ((op ((var-get #'open.atlas.studio.builder-server/publish-service) caller)) args))

(defn- refused [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- version-record [n]
  (first (filter #(= n (:version/number %))
                 (version/versions (pool/db (:conn *state*)) "demo/app"))))

(deftest a-version-nothing-needs-is-reclaimed-and-its-record-stays
  (let [t (token! #{:read :write})
        caller {:workspace "demo" :principal *owner*}
        store (:graph (:runtime *state*))
        graph-id (:graph/id (graph/graph (pool/db (:conn *state*)) "demo/app"))
        shelf (fn [n] (io/file (:artifact-root *state*) "demo" "app" (str n)))]
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (graph/pin-saved-queries! (:conn *state*) graph-id 1 [{:name "q"}])
    (is (= 2 (serving)))
    (testing "the version list says which may go and why the other may not"
      (let [vs (:versions (psvc-call :versions caller {:graph "demo/app"}))
            by-n (into {} (map (juxt :version/number :version/reclaim)) vs)]
        (is (true? (:reclaimable? (by-n 1))))
        (is (= {:serving 2} (:blockers (by-n 2))))))
    (testing "the serving version is refused by name"
      (let [d (refused #(psvc-call :reclaim-version! caller {:graph "demo/app" :version 2}))]
        (is (= :version-in-use (:code d)))
        (is (= 2 (:serving d)))))
    (testing "v1 is reclaimed: bytes gone on both shelves, record kept and marked"
      (is (.isDirectory (shelf 1)))
      (is (= {:graph "demo/app" :reclaimed [1]}
             (psvc-call :reclaim-version! caller {:graph "demo/app" :version "1"})))
      (is (not (.exists (shelf 1))))
      (is (.isDirectory (shelf 2)) "only the one version")
      (is (not (rgraph/loaded? store "demo/app" 1)))
      (is (rgraph/loaded? store "demo/app" 2))
      (is (= [2] (vec (sort (keep #(when (= "demo/app" (:graph %)) (:version %))
                                  (rgraph/shelf-versions store))))))
      (is (string? (:version/reclaimed-at (version-record 1))))
      (is (= [1 2] (mapv :version/number (version/versions (pool/db (:conn *state*)) "demo/app")))))
    (testing "its pinned saved queries are dropped"
      (is (nil? (graph/pinned-saved-queries (pool/db (:conn *state*)) graph-id 1))))
    (testing "rollback and export to it are refused clearly"
      (is (= :version-reclaimed
             (:code (refused #(psvc-call :rollback! caller {:graph "demo/app" :to-version 1})))))
      (is (= :version-reclaimed
             (:code (refused #(psvc-call :export caller {:graph "demo/app" :version 1})))))
      (is (= 2 (serving))))
    (testing "reclaiming it again is refused, and the next cut does not reuse the number"
      (is (= :version-reclaimed
             (:code (refused #(psvc-call :reclaim-version! caller {:graph "demo/app" :version 1})))))
      (is (= 3 (:version (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))))))
    (testing "the runtime still serves after the reclaim"
      (is (= 3 (serving))))))

(deftest a-version-pinned-by-an-active-token-is-kept-until-the-token-is-not
  (let [t (token! #{:read :write})
        caller {:workspace "demo" :principal *owner*}
        tokens (get-in *state* [:runtime :token])]
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (let [now (str (java.time.Instant/now))
          issued (runtime-token/issue! tokens {:graph-id "demo/app" :version 1 :actor "test"
                                               :request-id (str (random-uuid)) :now now
                                               :expires-at nil})
          token-id (get-in issued [:record :token-id])]
      (is (some? token-id) (pr-str issued))
      (let [d (refused #(psvc-call :reclaim-version! caller {:graph "demo/app" :version 1}))]
        (is (= :version-in-use (:code d)))
        (is (= [token-id] (:pinned-tokens d))))
      (testing "the sweep skips it with the same reason"
        (let [r (psvc-call :reclaim-unused! caller {:graph "demo/app" :keep 1})]
          (is (= [] (:reclaimed r)))
          (is (= [{:version 1 :blockers {:pinned-tokens [token-id]}}] (:skipped r)))))
      (runtime-token/revoke-token! tokens {:token-id token-id :actor "test"
                                           :request-id (str (random-uuid)) :now now})
      (testing "a revoked token pins nothing"
        (is (= {:graph "demo/app" :keep 1 :reclaimed [1] :skipped []}
               (psvc-call :reclaim-unused! caller {:graph "demo/app" :keep 1})))))))

(deftest a-version-another-artifact-references-is-kept
  (let [t (token! #{:read :write})
        caller {:workspace "demo" :principal *owner*}]
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    ;; A retained artifact of another graph that pins demo/app v1.
    (let [dir (io/file (:artifact-root *state*) "demo" "other" "1")]
      (.mkdirs dir)
      (spit (io/file dir "manifest.edn")
            (pr-str {:artifact/format-version 1
                     :provenance {:workspace "demo" :graph "other" :version 1}}))
      (spit (io/file dir "content.edn")
            (pr-str {:references [{:workspace "demo" :graph "app" :version 1}]})))
    (let [d (refused #(psvc-call :reclaim-version! caller {:graph "demo/app" :version 1}))]
      (is (= :version-in-use (:code d)))
      (is (= [{:graph "demo/other" :version 1}] (:referenced-by d))))
    (testing "a viewer-less caller from another workspace is refused like publish"
      (is (= :graph-outside-current-workspace
             (:code (refused #(psvc-call :reclaim-version! {:workspace "elsewhere" :principal *owner*}
                                         {:graph "demo/app" :version 1}))))))))

(deftest an-agent-reclaims-through-mcp-checking-first
  (let [t (token! #{:read :write})]
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
    (testing "without apply it writes nothing and says what may go and why not"
      (let [r (ok! (call! t "reclaim_version" {:graph "demo/app"}))]
        (is (= [{:version 2 :reclaimable false :blockers {:serving 2}}
                {:version 1 :reclaimable true}]
               (:versions r)))
        (is (.isDirectory (io/file (:artifact-root *state*) "demo" "app" "1")))))
    (testing "the serving version is refused by name"
      (let [fs (refusal (call! t "reclaim_version" {:graph "demo/app" :version 2 :apply true}))]
        (is (= :version-in-use (get-in (first fs) [:got :code])))
        (is (= 2 (get-in (first fs) [:got :serving])))))
    (testing "apply with version reclaims it"
      (is (= [1] (:reclaimed (ok! (call! t "reclaim_version" {:graph "demo/app" :version 1 :apply true})))))
      (is (= {:version 1 :reclaimable false}
             (dissoc (last (:versions (ok! (call! t "reclaim_version" {:graph "demo/app"})))) :reclaimed_at))))
    (testing "a read-only key does not see the tool"
      (is (true? (get-in (call! (token! #{:read}) "reclaim_version" {:graph "demo/app"})
                         [:result :isError]))))))


(deftest an-agent-publishes-and-issues-graph-tokens-with-its-workspace-token
  "Owner ruling 2026-10-11: an agent's workspace token may issue every kind of
   graph token, Workbench service tokens included — through the token panel's
   own functions, under the same editor bar."
  (orders-graph!)
  (let [t (token! #{:read :write})
        graph-id (:graph/id (graph/graph (pool/db (:conn *state*)) "demo/app"))
        now "2026-10-11T00:00:00Z"
        _ (ok! (call! t "publish_graph" {:graph "demo/app" :apply true}))
        consumer (fn [secret]
                   (json/parse-string
                    (:body ((mcp/routes (data-plane-shell now))
                            {:request-method :post :uri (str "/mcp/" graph-id)
                             :headers {"authorization" (str "Bearer " secret)}
                             :body (java.io.ByteArrayInputStream.
                                    (.getBytes (json/generate-string
                                                {:jsonrpc "2.0" :id 1 :method "tools/call"
                                                 :params {:name "execute_eql"
                                                          :arguments {:query "{:entity {:demo.order/id 1001} :query [:demo.order/status]}"}}})
                                               "UTF-8"))}))
                    true))]
    (testing "an agent token: the secret comes back once and reads the published graph"
      (let [r (ok! (call! t "issue_graph_token" {:graph "demo/app" :expires_at "2027-01-01T00:00:00Z"}))
            secret (:secret r)]
        (is (string? secret))
        (is (str/includes? (get-in (consumer secret) [:result :content 0 :text]) "PAID"))
        (testing "listing shows it, never the secret"
          (let [listed (ok! (call! t "list_graph_tokens" {:graph "demo/app"}))]
            (is (some #(= (:token-id r) (:token-id %)) (:tokens listed)))
            (is (not (str/includes? (pr-str listed) secret)))))
        (testing "revoking cuts the consumer off"
          (ok! (call! t "revoke_graph_token" {:graph_token_id (:token-id r)}))
          (is (not= 200 (:status ((mcp/routes (data-plane-shell now))
                                  {:request-method :post :uri (str "/mcp/" graph-id)
                                   :headers {"authorization" (str "Bearer " secret)}
                                   :body (java.io.ByteArrayInputStream.
                                          (.getBytes (json/generate-string
                                                      {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})
                                                     "UTF-8"))})))))))
    (testing "a Workbench service token may name the user it reads for"
      (let [r (ok! (call! t "issue_graph_token" {:graph "demo/app" :workbench true}))
            rec (some #(when (= (:token-id r) (:token-id %)) %)
                      (:tokens (ok! (call! t "list_graph_tokens" {:graph "demo/app"}))))]
        (is (string? (:secret r)))
        (is (true? (:asserts-user? rec)) (pr-str rec))))
    (testing "a read-only key cannot issue or revoke"
      (is (refusal (call! (token! #{:read}) "issue_graph_token" {:graph "demo/app"}))))))

(defn- credentials-flow []
  (check! (pool/execute-action (:conn *state*)
                               {:action :create :type :source :id "vault-db"
                                :workspace "demo" :expected_revision 0
                                :entry {:type "sql" :label "Vault DB" :jdbc_url "${VAULT_DB_URL}"}}))
  (let [t (token! #{:read :write})
        ro (token! #{:read})
        row (fn [token n] (some #(when (= n (:name %)) %)
                                (:variables (ok! (call! token "list_credentials" {})))))]
    (testing "the missing variable is listed with its user, not held"
      (is (= {:name "VAULT_DB_URL" :present? false :used-by ["vault-db"]}
             (select-keys (row t "VAULT_DB_URL") [:name :present? :used-by]))))
    (testing "save_credential sets it; no tool reads the value back"
      (ok! (call! t "save_credential" {:name "VAULT_DB_URL" :value "jdbc:sqlite:/tmp/s3cr3t.db"}))
      (is (true? (:present? (row t "VAULT_DB_URL"))))
      (is (not (str/includes? (pr-str (call! t "list_credentials" {})) "s3cr3t"))))
    (testing "removal is refused while a source references it"
      (is (= [:credential/in-use] (mapv :code (refusal (call! t "remove_credential" {:name "VAULT_DB_URL"})))))
      (is (= [:credential/in-use] (mapv :code (refusal (call! t "remove_credential" {:name "VAULT_DB_URL" :apply true}))))))
    (testing "an unreferenced value is planned, then removed"
      (ok! (call! t "save_credential" {:name "SPARE_TOKEN" :value "x"}))
      (is (true? (:removable? (ok! (call! ro "remove_credential" {:name "SPARE_TOKEN"})))))
      (is (refusal (call! ro "remove_credential" {:name "SPARE_TOKEN" :apply true})))
      (ok! (call! t "remove_credential" {:name "SPARE_TOKEN" :apply true}))
      (is (not (some #{"SPARE_TOKEN"} (:unreferenced (ok! (call! t "list_credentials" {})))))))
    (testing "a read-only key cannot write a value; a blank value is refused, not a clear"
      (is (refusal (call! ro "save_credential" {:name "OTHER" :value "x"})))
      (is (refusal (call! t "save_credential" {:name "OTHER" :value " "}))))))

(deftest an-agent-manages-credentials-with-its-workspace-token
  "Owner ruling 2026-10-11: credentials over the Builder MCP — the credentials
   page's own functions, a value written and never read back."
  (with-redefs-fn {#'open.atlas.studio.builder-server/studio-home (constantly *home*)}
    #(credentials-flow)))

(deftest an-agent-lists-and-removes-sources-with-its-workspace-token
  "Owner ruling 2026-10-11: the Sources page's register and removal over the
   Builder MCP — the page's own handlers, run as the key's caller."
  (orders-graph!)
  (check! (pool/execute-action (:conn *state*)
                               {:action :create :type :source :id "spare-db"
                                :workspace "demo" :expected_revision 0
                                :entry {:type "sql" :label "Spare" :jdbc_url "${SPARE_URL}"}}))
  (let [t (token! #{:read :write})
        ro (token! #{:read})
        ids #(set (map :id (:sources (ok! (call! % "list_sources" {})))))]
    (testing "the register lists both, never a value"
      (is (every? (ids t) ["orders-db" "spare-db"]))
      (is (= (ids t) (ids ro))))
    (testing "a source a graph still needs is refused, with the reason"
      (is (seq (refusal (call! t "remove_source" {:source "orders-db" :apply true}))))
      (is (contains? (ids t) "orders-db")))
    (testing "an unused one plans, a read-only key may not apply, a write key removes it"
      (is (true? (:removable? (ok! (call! ro "remove_source" {:source "spare-db"})))))
      (is (refusal (call! ro "remove_source" {:source "spare-db" :apply true})))
      (is (true? (:removed? (ok! (call! t "remove_source" {:source "spare-db" :apply true})))))
      (is (not (contains? (ids t) "spare-db"))))
    (testing "probing needs a write key"
      (is (refusal (call! ro "probe_api_source" {:source "orders-db" :path "/x"}))))))

(deftest an-agent-carries-a-graph-through-its-lifecycle-with-its-workspace-token
  "Owner ruling 2026-10-11 (reversing 裁定九 for these): the Graphs page's
   lifecycle handlers over the Builder MCP, run as the key's caller."
  (let [t (token! #{:read :write})
        ro (token! #{:read})
        names #(set (map :graph/qualified-name (:graphs (ok! (call! t "list_graphs" {})))))]
    (testing "check_graph_cut reads, writes nothing"
      (is (map? (ok! (call! ro "check_graph_cut" {:graph "demo/app"})))))
    (testing "duplicate plans on a read key, creates on a write key"
      (is (map? (ok! (call! ro "duplicate_graph" {:graph "demo/app" :name "copy"}))))
      (is (refusal (call! ro "duplicate_graph" {:graph "demo/app" :name "copy" :apply true})))
      (ok! (call! t "duplicate_graph" {:graph "demo/app" :name "copy" :apply true}))
      (is (contains? (names) "demo/copy")))
    (testing "rename keeps the graph, changes its label"
      (ok! (call! t "rename_graph" {:graph "demo/copy" :name "Copy of app"}))
      (is (contains? (names) "demo/copy")))
    (testing "settings"
      (ok! (call! t "set_default_query" {:graph "demo/copy" :default_query "{:entity {:demo.order/id 1001} :query [:demo.order/status]}"}))
      (ok! (call! t "set_package_name" {:graph "demo/copy" :package_name "demo/copy-pkg"}))
      (ok! (call! t "set_workspace_default_graph" {:graph "demo/app"})))
    (testing "merge makes a new graph"
      (ok! (call! t "merge_graphs" {:graphs ["demo/app" "demo/copy"] :name "merged"}))
      (is (contains? (names) "demo/merged")))
    (testing "delete needs archive first and the name repeated"
      (is (refusal (call! t "delete_graph" {:graph "demo/merged" :confirm "demo/merged"})))
      (ok! (call! t "archive_graph" {:graph "demo/merged"}))
      (ok! (call! t "restore_graph" {:graph "demo/merged"}))
      (ok! (call! t "archive_graph" {:graph "demo/merged"}))
      (is (refusal (call! t "delete_graph" {:graph "demo/merged" :confirm "merged"})))
      (ok! (call! t "delete_graph" {:graph "demo/merged" :confirm "demo/merged"})))
    (testing "a read key cannot write any of them"
      (doseq [[tool args] [["rename_graph" {:graph "demo/copy" :name "x"}]
                           ["archive_graph" {:graph "demo/copy"}]
                           ["rollback_graph" {:graph "demo/app" :to_version "1"}]]]
        (is (refusal (call! ro tool args)) tool)))))

(defn- access-and-tokens-flow []
  (let [conn (:conn *state*)
        member (:user (identity/create-local-user!
                       conn {:email "member@example.com" :name "Member" :password "a-safe-password"}))
        _ (check! (graph/add-workspace-member! conn "demo" {:principal (:principal member) :role :member}))
        t (token! #{:read :write})
        ro (token! #{:read})
        emails #(set (map :email (:access (ok! (call! ro "list_graph_access" {:graph "demo/app"})))))]
    (testing "grant, list, revoke"
      (is (= #{} (emails)))
      (ok! (call! t "grant_graph_access" {:graph "demo/app" :email "member@example.com"}))
      (is (= #{"member@example.com"} (emails)))
      (is (refusal (call! ro "revoke_graph_access" {:graph "demo/app" :email "member@example.com"})))
      (ok! (call! t "revoke_graph_access" {:graph "demo/app" :email "member@example.com"}))
      (is (= #{} (emails))))
    (testing "an outsider cannot be granted"
      (is (refusal (call! t "grant_graph_access" {:graph "demo/app" :email "nobody@example.com"}))))
    (testing "workspace tokens: listed without secrets, revoked, and the revoked key stops working"
      (let [listed (:tokens (ok! (call! t "list_workspace_tokens" {})))
            ro-id (:token-id (some #(when (= #{"read"} (set (map name (:scope %)))) %) listed))]
        (is (<= 2 (count listed)))
        (is (not (str/includes? (pr-str listed) ro)))
        (is (refusal (call! ro "revoke_workspace_token" {:workspace_token_id ro-id})))
        (ok! (call! t "revoke_workspace_token" {:workspace_token_id ro-id}))
        (is (not= 200 (:status ((wire/routes (deps))
                                {:request-method :post :uri "/mcp"
                                 :headers {"host" "127.0.0.1:9631" "authorization" (str "Bearer " ro)}
                                 :body (java.io.ByteArrayInputStream.
                                        (.getBytes (json/generate-string
                                                    {:jsonrpc "2.0" :id 1 :method "tools/list"}) "UTF-8"))}))))))
    (testing "no tool issues a workspace token"
      (is (not-any? #(re-find #"issue_workspace" %)
                    (map :name (get-in (json/parse-string
                                        (:body ((wire/routes (deps))
                                                {:request-method :post :uri "/mcp"
                                                 :headers {"host" "127.0.0.1:9631" "authorization" (str "Bearer " t)}
                                                 :body (java.io.ByteArrayInputStream.
                                                        (.getBytes (json/generate-string
                                                                    {:jsonrpc "2.0" :id 1 :method "tools/list"}) "UTF-8"))}))
                                        true)
                                       [:result :tools])))))))

(deftest an-agent-manages-graph-access-and-workspace-tokens
  "Owner ruling 2026-10-11: graph grants and workspace-token list/revoke over
   the Builder MCP. Issuing a workspace token stays a person's act."
  (with-redefs-fn {#'open.atlas.studio.builder-server/studio-home (constantly *home*)}
    #(access-and-tokens-flow)))

(deftest an-agent-lists-its-workspace-objects
  "Owner ruling 2026-10-11: the Workspace screen's object list over the
   Builder MCP — this workspace's pool objects, never a source's config."
  (orders-graph!)
  (let [r (ok! (call! (token! #{:read :write}) "list_workspace_objects" {}))]
    (is (= "demo" (:workspace r)))
    (is (seq (:objects r)))
    (is (not (str/includes? (pr-str r) "jdbc:sqlite")))))
