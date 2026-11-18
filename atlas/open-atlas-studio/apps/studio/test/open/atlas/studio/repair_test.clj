(ns open.atlas.studio.repair-test
  "**8.4.21 裁定十五, end to end** (owner 2026-09-04).

   The ruling's evidence: after 裁定十三 the agent found the root cause of
   «user 9's posts do not come back» in one go, and still explained it to a
   person in engine vocabulary and still stopped to ask what the collection
   should be called. So the exit conditions are about what a person and an
   agent READ, and this suite runs them over a real database through the MCP
   door:

   1. an anchorless list with a link beside it — `inspect_graph`'s `:compile`,
      `publish_graph`'s check and `execute_eql`'s refusal carry the SAME
      `:repair`;
   2. that `:repair` sent as it stands, plan then apply, and then the query
      the session could not run, runs;
   3. the three refusals' FIRST SENTENCE carries no engine vocabulary;
   4. `query_model {query :namespace/unreachable}` reports the entity before
      anybody asks, and an entity with no link carries the `add_link` template
      rather than an `add_resolver` nobody could have inferred.

   The fixture is `child_collection_test`'s, for the same reason: what is
   under test needs a source that really opens, and `fixtures/demo` is the
   demo model's home rather than a place for two throwaway tables."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
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

(defn- build-db! [path]
  (with-open [conn (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" path))
              stmt (.createStatement conn)]
    (.execute stmt "CREATE TABLE users (Id INTEGER PRIMARY KEY, DisplayName TEXT)")
    (.execute stmt "CREATE TABLE posts (Id INTEGER PRIMARY KEY, Title TEXT, OwnerUserId INTEGER)")
    (.execute stmt "INSERT INTO users (Id, DisplayName) VALUES (8, 'eight'), (9, 'nine')")
    (.execute stmt (str "INSERT INTO posts (Id, Title, OwnerUserId) VALUES "
                        "(1, 'first post', 9), (2, 'second post', 9), (3, 'other', 8)"))))

(defn- model
  "Yesterday's state, exactly: the parent is modelled, the child is modelled as
   an ANCHORLESS list (the shape a modeller was left holding before 裁定十三),
   and a LINK between them already exists — which is what makes the repair
   inferable."
  [db]
  {:attributes {:cis.users/Id {:type :integer :format :id :identity? true :label "Id"}
                :cis.users/DisplayName {:type :string :format :text :label "Name"}
                :cis.posts/Id {:type :integer :format :id :identity? true :label "Id"}
                :cis.posts/Title {:type :string :format :text :label "Title"}
                :cis.posts/OwnerUserId {:type :integer :format :id :label "Owner"}}
   :namespaces {"cis.users" {:kind :master :label "Users"}
                "cis.posts" {:kind :master :label "Posts"}}
   ;; `:direction` is left out: `:bi` is the dialect default and dialect-v1 §4
   ;; refuses a written default.
   :links [{:from :cis.users/Id :to :cis.posts/OwnerUserId :cardinality :one-to-many}]
   :sources    {"db" {:type "sql" :label "DB" :jdbc_url (str "jdbc:sqlite:" db)}}
   :resolvers  [{:id "cis_users_by_Id" :input [:cis.users/Id]
                 :output [:cis.users/Id :cis.users/DisplayName]
                 :result_type "single"
                 :source {:type "sql"
                          :query "SELECT Id, DisplayName FROM users WHERE Id IN (:Ids)"
                          :batch {:parent-key :cis.users/Id :child-fk "Id"
                                  :placeholder "Ids"}}
                 :source-ref "db"}
                ;; A by-key lookup so the child's attributes ARE in the
                ;; catalog: without it the refusal is «unknown attribute» and
                ;; the ruling's case (declared, and no path reaches it) never
                ;; arises.
                {:id "cis_posts_by_Id" :input [:cis.posts/Id]
                 :output [:cis.posts/Id :cis.posts/Title :cis.posts/OwnerUserId]
                 :result_type "single"
                 :source {:type "sql"
                          :query "SELECT Id, Title, OwnerUserId FROM posts WHERE Id IN (:Ids)"
                          :batch {:parent-key :cis.posts/Id :child-fk "Id"
                                  :placeholder "Ids"}}
                 :source-ref "db"}
                {:id "cis_posts_flat_list" :input [] :curated? true
                 :output [:cis.posts/Id :cis.posts/Title :cis.posts/OwnerUserId]
                 :result_type "list" :limit {:default 50 :max 500}
                 :source {:type "sql" :query "SELECT Id, Title, OwnerUserId FROM posts"}
                 :source-ref "db"}]})

(defn- with-studio [f]
  (let [runtime-root (tmp "rp-rt-") artifact-root (tmp "rp-art-")
        home (tmp "rp-home-") db-dir (tmp "rp-db-")
        db (str db-dir "/a.db")
        _ (.mkdirs (java.io.File. ^String db-dir))
        _ (build-db! db)
        started (app/start! {:pool* {:backend :mem :id (str (gensym "repair"))}
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
                                           (pool/dialect->objects (model db)))}))
        (check! (graph/register-graph!
                 conn {:workspace "demo" :name "app"
                       :created-at "2026-09-04T00:00:00Z"
                       :members {:namespaces #{"cis.users" "cis.posts"}
                                 :attributes #{:cis.users/Id :cis.users/DisplayName
                                               :cis.posts/Id :cis.posts/Title
                                               :cis.posts/OwnerUserId}
                                 :resolvers #{"cis_users_by_Id" "cis_posts_by_Id" "cis_posts_flat_list"}}}))
        (reset! (var-get studio-state) started)
        (binding [*state* started *home* home *owner* owner]
          (f)))
      (finally
        (reset! (var-get studio-state) nil)
        (app/stop! started)
        (rm-r runtime-root) (rm-r artifact-root) (rm-r home) (rm-r db-dir)))))

(use-fixtures :each with-studio)

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
                                    :label "repair-test"})))

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

(defn- ok! [res]
  (is (nil? (get-in res [:result :isError])) (pr-str (get-in res [:result :content 0 :text])))
  (json/parse-string (get-in res [:result :content 0 :text]) true))

(defn- findings [res]
  (let [v (edn/read-string (get-in res [:result :content 0 :text]))]
    (:findings (or (:error v) v))))

(defn- first-sentence [m] (first (str/split (str m) #"(?<=\.)\s")))

(def ^:private engine-words
  "The vocabulary the ruling banished from a first sentence: it is the model's
   mechanism, and a person asking for a user's posts never has to learn it."
  ["join root" "projection" "D2."])

(defn- plain? [message]
  (let [s (str/lower-case (first-sentence message))]
    (every? #(not (str/includes? s (str/lower-case %))) engine-words)))

;; --- 1. the same repair in all three places --------------------------------

(def ^:private expected-repair
  {:tool "add_relation"
   :args {:graph "demo/app" :parent "cis.users" :child "cis.posts"
          :kind "many" :via "OwnerUserId" :name "posts"}
   :then [{:tool "remove_objects" :args {:objects ["cis_posts_flat_list"] :graph "demo/app"}}]})

(defn- as-repair [m]
  (-> m
      (update :args #(into {} (for [[k v] %] [k v])))
      (update :then (fn [ts] (mapv #(update % :args (fn [a] (into {} a))) ts)))))

(deftest the-defect-carries-the-same-repair-everywhere
  (testing "inspect_graph's :compile section"
    (let [r (ok! (call! "inspect_graph" {:graph "demo/app"}))
          f (:finding (first (get-in r [:compile :resolvers])))]
      (is (= "engine.compile/list-without-anchor" (:code f)) (pr-str (:compile r)))
      (is (= expected-repair (as-repair (:repair f))) (pr-str f))
      (is (plain? (:message f)) (first-sentence (:message f)))))

  (testing "publish_graph's check"
    (let [r (ok! (call! "publish_graph" {:graph "demo/app"}))
          f (first (filter #(= "engine.compile/list-without-anchor" (:code %))
                           (:health-findings r)))]
      (is (some? f) (pr-str (:health-findings r)))
      (is (= expected-repair (as-repair (:repair f))))))

  (testing "and the execute_eql refusal a person actually meets — the child's
            attributes are declared, and no path reaches them"
    (let [res (call! "execute_eql"
                     {:graph "demo/app"
                      :query "{:entity {:cis.users/Id 9} :query [:cis.posts/Title]}"})
          f (first (findings res))]
      (is (= :engine.request/unreachable (:code f)) (pr-str f))
      (is (plain? (:message f)) (first-sentence (:message f)))
      (is (= "add_relation" (:tool (:repair f))) (pr-str f))
      (is (= "cis.users" (get-in f [:repair :args :parent])))
      (is (= "OwnerUserId" (get-in f [:repair :args :via]))))))

;; --- 2. sending it as it stands fixes the model ----------------------------

(deftest sending-the-repair-as-it-stands-makes-the-query-run
  (let [args (:args expected-repair)
        plan (ok! (call! "add_relation" args))
        done (ok! (call! "add_relation" (assoc args :apply true)))]
    (is (= (pr-str (:actions plan)) (pr-str (:actions done)))
        "plan then apply, the discipline the repair does not touch")
    (is (true? (:written? done)) (pr-str (:findings done)))
    (let [r (ok! (call! "execute_eql"
                        {:graph "demo/app"
                         :query (str "{:entity {:cis.users/Id 9} "
                                     ":query [{:cis.users/posts [:cis.posts/Title]}]}")}))]
      (is (= [{:cis.posts/Title "first post"} {:cis.posts/Title "second post"}]
             (get-in r [:result :cis.users/posts]))
          (pr-str r)))
    (testing "and the :then step removes the resolver that compiled to nothing"
      (let [t (first (:then expected-repair))]
        (ok! (call! (:tool t) (:args t)))
        (ok! (call! (:tool t) (assoc (:args t) :apply true))))
      (is (zero? (get-in (ok! (call! "inspect_graph" {:graph "demo/app"}))
                         [:compile :uncompilable]))))))

;; --- 3. query_model finds it before anybody asks ---------------------------

(deftest query-model-reports-unreachable-entities-with-their-repairs
  ;; With the anchorless list removed, `cis.posts` has no root collection and
  ;; nothing points at it — the state the ruling wants found BEFORE a person
  ;; asks. `cis.users` is unreachable too, and for the opposite reason: the
  ;; link's identity end is on IT, so no link makes it a child of anything.
  (ok! (call! "remove_objects" {:graph "demo/app" :objects ["cis_posts_flat_list"]}))
  (ok! (call! "remove_objects" {:graph "demo/app" :objects ["cis_posts_flat_list"]
                                :apply true}))
  (let [gaps (:gaps (ok! (call! "query_model" {:query "namespace/unreachable"})))
        by-ns (into {} (for [g gaps] [(second (:target g)) g]))]
    (is (= #{"cis.posts" "cis.users"} (set (keys by-ns))) (pr-str gaps))
    (testing "the one a link reaches carries the child collection, prefilled"
      (let [r (:repair (by-ns "cis.posts"))]
        (is (= "add_relation" (:tool r)) (pr-str r))
        (is (= "cis.users" (get-in r [:args :parent])))
        (is (= "OwnerUserId" (get-in r [:args :via])))))
    (testing "the one no link reaches carries the template, not a guess"
      (let [r (:repair (by-ns "cis.users"))]
        (is (= "add_link" (:tool r)) (pr-str r))
        (is (= "" (get-in r [:args :from])))
        (is (= "" (get-in r [:args :to])))
        (is (string? (:note r)) "and it says a person must name the two ends")))))
