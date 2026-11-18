(ns open.atlas.studio.child-collection-test
  "**8.4.19 裁定十三, end to end** (owner 2026-09-04).

   The ruling's evidence was a modelling session that did everything right and
   still could not ask a user for its posts: `add_resolver` knew two shapes —
   the unanchored set and the by-key lookup — and «a parent's children», the
   commonest navigation D2.29 leaves you with, was neither. The resolver that
   got hand-written instead was a `list` with a flat `:output`, which the
   engine drops with a warning: present in the draft, absent from every query.

   So this suite runs the exit conditions over a real database, through the
   MCP door, the way the session did:

   1. `add_resolver {relation \"posts\" parent \"cis.users/Id\" foreign_key
      \"OwnerUserId\"}`, one plan and one apply, and then `execute_eql`
      `{:entity {cis.users/Id 9} :query [{cis.users/posts [cis.posts/Title]}]}`
      returns rows;
   2. `parent` with `input` is refused, and an unknown `foreign_key` is
      refused with the columns the table does carry;
   3. a graph holding an anchorless list is REPORTED by `inspect_graph`'s
      `:compile` section and REFUSED by `publish_graph`'s check.

   The fixture is `draft_parity_test`'s: a small SQLite database in a temp
   directory, because what is under test needs a source that really opens.
   `fixtures/demo` is the demo model's home and not the place for two
   throwaway tables."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.mcp-transport :as wire]
            [open.atlas.server.mcp :as mcp]
            [open.atlas.studio.app :as app]
            [open.atlas.studio.builder-mcp :as builder-mcp]
            [open.atlas.studio.builder-server]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.tools :as tools]
            [open.atlas.studio.workspace-token :as wt]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

(def ^:dynamic *state* nil)
(def ^:dynamic *home* nil)
(def ^:dynamic *owner* nil)
(def ^:private pepper "test-workspace-pepper")

(def ^:private studio-state #'open.atlas.studio.builder-server/studio-state)

(defn- tmp [prefix] (str (System/getProperty "java.io.tmpdir") "/" prefix (random-uuid)))
(defn- rm-r [path]
  (doseq [f (reverse (file-seq (java.io.File. ^String path)))] (.delete ^java.io.File f)))
(defn- check! [r] (assert (empty? (:findings r)) (pr-str (:findings r))) r)

(defn- build-db!
  "Two tables, a parent and its children. User 9 has two posts and user 8 has
   one, so «the batch returned somebody else's rows» would show."
  [path]
  (with-open [conn (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" path))
              stmt (.createStatement conn)]
    (.execute stmt "CREATE TABLE users (Id INTEGER PRIMARY KEY, DisplayName TEXT)")
    (.execute stmt "CREATE TABLE posts (Id INTEGER PRIMARY KEY, Title TEXT, OwnerUserId INTEGER)")
    (.execute stmt "INSERT INTO users (Id, DisplayName) VALUES (8, 'eight'), (9, 'nine')")
    (.execute stmt (str "INSERT INTO posts (Id, Title, OwnerUserId) VALUES "
                        "(1, 'first post', 9), (2, 'second post', 9), (3, 'other', 8)"))))

(defn- model
  "The state the modelling session starts from: the PARENT is modelled and the
   child table is not. That is the whole setting of the ruling — the child is
   what `add_resolver` is asked to add."
  [db]
  {:attributes {:cis.users/Id {:type :integer :format :id :identity? true :label "Id"}
                :cis.users/DisplayName {:type :string :format :text :label "Name"}}
   :namespaces {"cis.users" {:kind :master :label "Users"}}
   :sources    {"db" {:type "sql" :label "DB" :jdbc_url (str "jdbc:sqlite:" db)}}
   :resolvers  [{:id "cis_users_by_Id" :input [:cis.users/Id]
                 :output [:cis.users/Id :cis.users/DisplayName]
                 :result_type "single"
                 :source {:type "sql"
                          :query "SELECT Id, DisplayName FROM users WHERE Id IN (:Ids)"
                          :batch {:parent-key :cis.users/Id :child-fk "Id"
                                  :placeholder "Ids"}}
                 :source-ref "db"}]})

(def ^:private broken
  "A graph whose only resolver the engine cannot compile: `:result_type
   \"list\"` with a flat `:output`. This is the shape the ruling's session was
   left holding, written here by hand because the tools now refuse to
   generate it — which is exactly what makes the report and the publish check
   worth having (裁定十三 ③: no auto-migration, so this state exists)."
  {:attributes {:cis.bad/Id {:type :integer :format :id :identity? true :label "Id"}
                :cis.bad/Title {:type :string :format :text :label "Title"}}
   :namespaces {"cis.bad" {:kind :master :label "Bad"}}
   :resolvers [{:id "cis_bad_flat_list" :input [] :curated? true
                :output [:cis.bad/Id :cis.bad/Title]
                :result_type "list" :limit {:default 50 :max 500}
                :source {:type "sql" :query "SELECT Id, Title FROM posts"}
                :source-ref "db"}]})

(defn- with-studio [f]
  (let [runtime-root (tmp "cc-rt-") artifact-root (tmp "cc-art-")
        home (tmp "cc-home-") db-dir (tmp "cc-db-")
        db (str db-dir "/a.db")
        _ (.mkdirs (java.io.File. ^String db-dir))
        _ (build-db! db)
        started (app/start! {:pool* {:backend :mem :id (str (gensym "childcoll"))}
                             :instance-id "studio-embedded"
                             :runtime-root runtime-root
                             :artifact-root artifact-root
                             :token-pepper "studio-pepper"
                             :actor-key (constantly nil)})
        conn (:conn started)]
    (assert (empty? (:findings started)) (pr-str (:findings started)))
    (try
      (identity/install! conn)
      (let [owner (:user (identity/bootstrap-admin!
                          conn {:email "owner@example.com" :name "Owner"
                                :password "a-safe-password" :workspace "demo"}))]
        (check! (pool/import-pool!
                 conn {:pool/version 1
                       :pool/objects (mapv #(assoc % :pool/revision 1 :pool/workspace "demo")
                                           (concat (pool/dialect->objects (model db))
                                                   (pool/dialect->objects broken)))}))
        (check! (graph/register-graph!
                 conn {:workspace "demo" :name "app"
                       :created-at "2026-09-04T00:00:00Z"
                       :members {:namespaces #{"cis.users"}
                                 :attributes #{:cis.users/Id :cis.users/DisplayName}
                                 :resolvers #{"cis_users_by_Id"}}}))
        (check! (graph/register-graph!
                 conn {:workspace "demo" :name "bad"
                       :created-at "2026-09-04T00:00:00Z"
                       :members {:namespaces #{"cis.bad"}
                                 :attributes #{:cis.bad/Id :cis.bad/Title}
                                 :resolvers #{"cis_bad_flat_list"}}}))
        (reset! (var-get studio-state) started)
        (binding [*state* started *home* home *owner* owner]
          (f)))
      (finally
        (reset! (var-get studio-state) nil)
        (app/stop! started)
        (rm-r runtime-root) (rm-r artifact-root) (rm-r home) (rm-r db-dir)))))

(use-fixtures :each with-studio)

;; --- the door --------------------------------------------------------------

(defn- builder-deps []
  (builder-mcp/deps
   {:home *home* :pepper pepper :conn (:conn *state*)
    :graph-service (var-get #'open.atlas.studio.builder-server/graph-service)
    :publish-service (var-get #'open.atlas.studio.builder-server/publish-service)
    :draft-service (var-get #'open.atlas.studio.builder-server/draft-service)
    :draft-runtime (var-get #'open.atlas.studio.builder-server/draft-runtime)
    :declarations (tools/declarations mcp/mountable-declarations)
    :registry #(tools/registry % mcp/mountable-declarations)
    :tools-list tools/tools-list :call tools/call
    :now (constantly "2026-09-04T00:00:00Z")}))

(defn- token! []
  (:token (wt/issue! *home* pepper {:workspace "demo" :scope #{:read :write}
                                    :issued-by (:principal *owner*)
                                    :issued-at "2026-09-04T00:00:00Z"
                                    :expires-at "2026-10-04T00:00:00Z"
                                    :label "child-collection-test"})))

(defn- call! [tool args]
  (json/parse-string
   (:body ((wire/routes (builder-deps))
           {:request-method :post :uri "/mcp"
            :headers {"host" "127.0.0.1:9631" "authorization" (str "Bearer " (token!))}
            :body (java.io.ByteArrayInputStream.
                   (.getBytes (json/generate-string
                               {:jsonrpc "2.0" :id 1 :method "tools/call"
                                :params {:name tool :arguments args}})
                              "UTF-8"))}))
   true))

(defn- ok!
  "**JSON, not EDN**: the transport prints a refusal with `pr-str` so the
   finding survives whole, and JSON-encodes a success because that is what an
   MCP client reads (`builder_mcp_test` says the same in more words)."
  [res]
  (is (nil? (get-in res [:result :isError])) (pr-str (get-in res [:result :content 0 :text])))
  (json/parse-string (get-in res [:result :content 0 :text]) true))

(defn- findings [res]
  (let [v (edn/read-string (get-in res [:result :content 0 :text]))]
    (:findings (or (:error v) v))))
(defn- codes [res] (set (map :code (findings res))))

;; --- 1. the exit condition -------------------------------------------------

(deftest a-parent-reaches-its-children-after-one-add-resolver
  (let [args {:graph "demo/app" :parent "cis.users" :child "cis.posts"
              :via "OwnerUserId" :from "posts" :name "posts"}
        plan (ok! (call! "add_relation" args))
        done (ok! (call! "add_relation" (assoc args :apply true)))]
    (testing "plan and apply are one planner (8.4.14), even for the new shape"
      (is (= (pr-str (:actions plan)) (pr-str (:actions done)))))
    (is (true? (:written? done)) (pr-str (:findings done)))
    (is (= "cis.users/posts"
           (some #(when (and (= "attribute" (:type %))
                             (= "posts" (name (keyword (:id %)))))
                    (:id %))
                 (:actions done)))
        "the join attribute is on the PARENT (D2.29)")

    (testing "and the query the session could not run, runs"
      (let [r (ok! (call! "execute_eql"
                          {:graph "demo/app"
                           :query (str "{:entity {:cis.users/Id 9} "
                                       ":query [{:cis.users/posts [:cis.posts/Title]}]}")}))]
        (is (= [{:cis.posts/Title "first post"} {:cis.posts/Title "second post"}]
               (get-in r [:result :cis.users/posts]))
            (pr-str r))))

    (testing "the resolver batches — one statement for the parent's children (D2.4)"
      (let [entry (:entry (some #(when (= "resolver" (:type %)) %) (:actions done)))]
        (is (= {:parent-key "cis.users/Id" :child-fk "OwnerUserId"
                :placeholder "OwnerUserIds"}
               (get-in entry [:source :batch])))))

    (testing "the way BACK rides along, produced by the child's own rows
              (doc 106 §106.14 item 5, owner 2026-09-06)"
      (let [entry (:entry (some #(when (= "resolver" (:type %)) %) (:actions done)))
            rows  (get (first (:output entry)) (keyword "cis.users/posts"))]
        (is (= {(keyword "cis.posts/users") ["cis.users/Id"]} (last rows))
            "D2.16 + D2.29: one more branch beside the child's own fields")
        (is (= "OwnerUserId" (get-in entry [:source :field_mapping (keyword "cis.users/Id")]))
            "the parent's key is read from the foreign-key column")
        (is (some #(and (= "attribute" (:type %)) (= "cis.posts/users" (:id %)))
                  (:actions done))
            "and the join attribute itself is created on the CHILD"))
      (testing "so a query walks from the child into the parent in one read"
        (let [r (ok! (call! "execute_eql"
                            {:graph "demo/app"
                             :query (str "{:entity {:cis.users/Id 8} "
                                         ":query [{:cis.users/posts "
                                         "[:cis.posts/Title "
                                         "{:cis.posts/users [:cis.users/DisplayName]}]}]}")}))]
          (is (= [{:cis.posts/Title "other"
                   :cis.posts/users {:cis.users/DisplayName "eight"}}]
                 (get-in r [:result :cis.users/posts]))
              (pr-str r)))))

    (testing "and the graph still cuts: the plan wrote the recipe too"
      (is (true? (:would-cut? (ok! (call! "publish_graph" {:graph "demo/app"}))))
          (pr-str (ok! (call! "publish_graph" {:graph "demo/app"})))))))

;; --- 2. the two refusals ---------------------------------------------------

(deftest has-many-over-sql-without-via-is-refused
  "need-grammar-v1 B1-sql: «has many» over a database source is read by
   matching the child's own field against the parent's key, and there is no
   such statement without that field."
  (let [res (call! "add_relation" {:parent "cis.users" :child "cis.posts"
                                   :from "posts" :graph "demo/app"})]
    (is (contains? (codes res) :tools.intent/unknown-column) (pr-str (findings res)))))

(deftest an-unknown-foreign-key-is-refused-with-the-columns-that-exist
  (let [res (call! "add_relation" {:parent "cis.users" :child "cis.posts" :via "UserId" :from "posts" :graph "demo/app"})
        f (first (filter #(= :tools.intent/unknown-column (:code %)) (findings res)))]
    (is (some? f) (pr-str (findings res)))
    (is (= [:via] (:path f)))
    (is (contains? (set (:expected f)) "OwnerUserId")
        "the candidate it meant is in the list")))

;; --- 3. an uncompilable resolver is neither hidden nor publishable ----------

(deftest an-anchorless-list-is-reported-and-refused
  (testing "inspect_graph's summary counts it and carries the engine's finding"
    (let [r (ok! (call! "inspect_graph" {:graph "demo/bad"}))]
      (is (= 1 (get-in r [:projected :resolvers])) "the projection has it")
      (is (= 1 (get-in r [:compile :uncompilable]))
          (str "and the summary says it does not compile: " (pr-str (:compile r))))
      (is (= "cis_bad_flat_list" (:resolver (first (get-in r [:compile :resolvers])))))
      (is (= "engine.compile/list-without-anchor"
             (:code (:finding (first (get-in r [:compile :resolvers]))))))))

  (testing "publish_graph's check refuses it as an ERROR — a resolver that
            cannot compile is not a smaller graph"
    (let [r (ok! (call! "publish_graph" {:graph "demo/bad"}))]
      (is (false? (:would-cut? r)))
      (is (contains? (set (map :code (:health-findings r)))
                     "engine.compile/list-without-anchor")
          (pr-str (:health-findings r)))))

  (testing "and apply does not publish it — the check runs on every path, so
            the refusal IS the answer and nothing was cut"
    (let [res (call! "publish_graph" {:graph "demo/bad" :apply true})]
      (is (true? (get-in res [:result :isError])))
      (is (contains? (set (map :code (findings res)))
                     :engine.compile/list-without-anchor)
          (pr-str (findings res))))))
