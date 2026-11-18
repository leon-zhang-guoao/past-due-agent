(ns open.atlas.studio.draft-parity-test
  "**A draft is the cut, run early** (8.4.18 裁定十一 ③, 裁定十二 施工记录).

   The ruling's claim is that `execute_eql` on the Builder and `execute_eql`
   on the data plane answer the same question with the same code: the draft is
   the scope projection — an unsealed cut — compiled by the same builder
   (`runtime/env/for-model`, 裁定十二 ①) a published version is compiled by.
   Every other test in this repository checks one half of that. This one runs
   BOTH halves against one graph and compares the bytes.

   It needs a source that really opens, which is why it was left unfinished in
   the first round: the MCP fixtures declare `${VAR}` placeholders nothing
   resolves. So this suite builds a small SQLite database in a temp directory
   — the engine the demo databases use and the one driver that travels inside
   the jar (`apps/studio/deps.edn`) — and points the model at it. It is a test
   fixture and lives with the tests; `fixtures/demo` is the demo model's home
   and is not the place for a throwaway two-row table.

   The two answers are byte-identical **modulo three keys**, each of which is
   the Builder deliberately adding something (`mount-data-plane`):

   - `:draft true` — a draft answer is not what a consumer sees;
   - `:graph` — the parameter the data plane takes from the token instead;
   - `:statements` — 裁定十二 ③, the statements the run sent.

   And the refusals must match too, which is the half that is easy to lose: a
   Builder that refuses in its own words teaches an agent a vocabulary the
   data plane does not speak."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.mcp-transport :as wire]
            [open.atlas.server.mcp :as mcp]
            [open.atlas.server.mcp.impl.results :as results]
            [open.atlas.studio.app :as app]
            [open.atlas.studio.auth :as auth]
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
(def ^:dynamic *db* nil)
(def ^:private pepper "test-workspace-pepper")
(def ^:private actor-key-id "studio-builder-actor")
(def ^:private actor-secret "studio-actor-secret")

(def ^:private studio-state #'open.atlas.studio.builder-server/studio-state)
(def ^:private issue-self-test-token! #'open.atlas.studio.builder-server/issue-self-test-token!)

(defn- tmp [prefix] (str (System/getProperty "java.io.tmpdir") "/" prefix (random-uuid)))
(defn- rm-r [path]
  (doseq [f (reverse (file-seq (java.io.File. ^String path)))] (.delete ^java.io.File f)))
(defn- check! [r] (assert (empty? (:findings r)) (pr-str (:findings r))) r)

(defn- build-db!
  "Two rows in one table. Small on purpose: what is under test is that two
   doors give one answer, not what SQLite can do."
  [path]
  (with-open [conn (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" path))
              stmt (.createStatement conn)]
    (.execute stmt "CREATE TABLE a (id INTEGER PRIMARY KEY, name TEXT)")
    (.execute stmt "INSERT INTO a (id, name) VALUES (1, 'first'), (2, 'second')")))

(defn- model [db]
  {:attributes {:cis.a/id    {:type :integer :format :id :identity? true :label "A"}
                :cis.a/name  {:type :string :format :text :label "Name"}
                :cis.a/all_a {:label "All A" :target "cis.a"}}
   :namespaces {"cis.a" {:kind :master :label "A"}}
   :sources    {"db" {:type "sql" :label "DB" :jdbc_url (str "jdbc:sqlite:" db)}}
   :resolvers  [{:id "a_by_id" :input [:cis.a/id] :output [:cis.a/name]
                 :result_type "single"
                 :source {:type "sql"
                          :query "SELECT id, name FROM a WHERE id = :id"}
                 :source-ref "db"}
                ;; `:curated? true` — a list resolver with no anchor is a named
                ;; query, and D2.13 wants the author to sign for it before a cut.
                {:id "all_a" :input [] :curated? true
                 :output [{:cis.a/all_a [:cis.a/id :cis.a/name]}]
                 :result_type "list"
                 :limit {:default 50 :max 500}
                 :order {:allowed [:cis.a/id] :default [:cis.a/id :asc]}
                 :source {:type "sql" :query "SELECT id, name FROM a"}
                 :source-ref "db"}]})

(defn- with-studio [f]
  (let [runtime-root (tmp "parity-rt-")
        artifact-root (tmp "parity-art-")
        home (tmp "parity-home-")
        db-dir (tmp "parity-db-")
        db (str db-dir "/a.db")
        _ (.mkdirs (java.io.File. ^String db-dir))
        _ (build-db! db)
        started (app/start! {:pool* {:backend :mem :id (str (gensym "parity"))}
                             :instance-id "studio-embedded"
                             :runtime-root runtime-root
                             :artifact-root artifact-root
                             :token-pepper "studio-pepper"
                             :actor-key (fn [k] (when (= actor-key-id k) actor-secret))})
        conn (:conn started)]
    (assert (empty? (:findings started)) (pr-str (:findings started)))
    (try
      (identity/install! conn)
      (let [owner (:user (identity/bootstrap-admin!
                          conn {:email "owner@example.com" :name "Owner"
                                :password "a-safe-password" :workspace "demo"}))
            m (model db)]
        (check! (pool/import-pool! conn {:pool/version 1
                                         :pool/objects (mapv #(assoc % :pool/revision 1 :pool/workspace "demo")
                                                             (pool/dialect->objects m))}))
        (let [pooled (pool/as-dialect (pool/db conn))]
          (check! (graph/register-graph!
                   conn {:workspace "demo" :name "app"
                         :created-at "2026-08-28T00:00:00Z"
                         :members {:namespaces (set (keys (:namespaces pooled)))
                                   :attributes (set (keys (:attributes pooled)))
                                   :resolvers (set (map :id (:resolvers pooled)))}})))
        (reset! (var-get studio-state)
                (assoc started :secrets {:actor-key-id actor-key-id
                                         :actor-secret actor-secret}))
        (binding [*state* started *home* home *owner* owner *db* db]
          (f)))
      (finally
        (reset! (var-get studio-state) nil)
        (app/stop! started)
        (rm-r runtime-root) (rm-r artifact-root) (rm-r home) (rm-r db-dir)))))

(use-fixtures :each with-studio)

;; --- the two doors ---------------------------------------------------------

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
    :now (constantly "2026-08-28T00:00:00Z")}))

(defn- post! [handler port token body]
  (json/parse-string
   (:body (handler {:request-method :post :uri "/mcp"
                    :headers {"host" (str "127.0.0.1:" port)
                              "authorization" (str "Bearer " token)}
                    :body (java.io.ByteArrayInputStream.
                           (.getBytes (json/generate-string body) "UTF-8"))}))
   true))

(defn- call [handler port token tool args]
  (post! handler port token {:jsonrpc "2.0" :id 1 :method "tools/call"
                             :params {:name tool :arguments args}}))

(defn- text-of [res] (get-in res [:result :content 0 :text]))

(defn- workspace-token! []
  (:token (wt/issue! *home* pepper {:workspace "demo" :scope #{:read :write}
                                    :issued-by (:principal *owner*)
                                    :issued-at "2026-08-28T00:00:00Z"
                                    :expires-at "2026-09-28T00:00:00Z"
                                    :label "draft-parity-test"})))

(defn- draft!
  "`execute_eql` on the Builder (9631), against the DRAFT of demo/app."
  [tool args]
  (call (wire/routes (builder-deps)) 9631 (workspace-token!) tool (assoc args :graph "demo/app")))

(defn- published!
  "`execute_eql` on the data plane (9632), against the version this graph
   token pins. The shell is the app's own — `(:runtime state)` is the embedded
   instance, so this is `instance/answer` and nothing else."
  [token tool args]
  (call (mcp/routes {:instance (:runtime *state*)
                     :now (constantly "2026-08-28T00:00:00Z")
                     :sse-writer identity})
        9632 token tool args))

(defn- graph-token! []
  (binding [auth/*request-user* (assoc (:user *owner* *owner*) :default-workspace "demo")]
    ;; `:secret` is the bearer; the ledger keeps only a keyed digest of it.
    (:secret (issue-self-test-token! {:graph "demo/app"}))))

(defn- publish! [t]
  (let [res (call (wire/routes (builder-deps)) 9631 t "publish_graph"
                  {:graph "demo/app" :apply true})
        _ (assert (nil? (get-in res [:result :isError])) (str (text-of res)))
        r (json/parse-string (text-of res) true)]
    (assert (= 1 (:serving r)) (pr-str r))
    r))

;; --- the properties --------------------------------------------------------

(def ^:private query
  "One collection query and one entity-anchored query in the same call: the
   two shapes a resolver can answer, so a difference in either shows up here."
  (str "{:entity {} :query [{:cis.a/all_a [:cis.a/id :cis.a/name]}]}"))

(deftest the-draft-and-the-published-version-answer-the-same-bytes
  (let [t (workspace-token!)]
    (publish! t)
    (let [gt (graph-token!)
          d (json/parse-string (text-of (draft! "execute_eql" {:query query})) true)
          p (json/parse-string (text-of (published! gt "execute_eql" {:query query})) true)]
      (testing "the rows are there at all — a parity test over two empty
                results would pass while proving nothing"
        (is (= [{:cis.a/id 1 :cis.a/name "first"}
                {:cis.a/id 2 :cis.a/name "second"}]
               (get-in d [:result (keyword "cis.a/all_a")]))
            (pr-str d)))
      (testing "and the draft's answer is the published one, key for key"
        (is (= (:result p) (:result d))))
      (testing "the three keys the Builder adds are the only difference"
        (is (= #{:draft :graph :statements}
               (into #{} (remove (set (keys p))) (keys d))))
        (is (true? (:draft d)))
        (is (seq (:statements d))
            "裁定十二 ③: the statements the run sent ride with the rows")
        (is (empty? (remove (set (keys d)) (keys p)))
            "and the data plane's own keys all survive the mount")))))

(deftest a-refusal-is-the-same-refusal-on-both-doors
  "The half that is easy to lose. An agent learns the vocabulary of refusals
   from whichever door it happens to be on, so two vocabularies is two tools."
  (let [t (workspace-token!)]
    (publish! t)
    (let [gt (graph-token!)
          bad "{:entity {} :query [:cis.a/no_such_attribute]}"
          d (draft! "execute_eql" {:query bad})
          p (published! gt "execute_eql" {:query bad})
          ;; A refusal's payload is EDN, not JSON — findings keep their
          ;; keywords and their `:got` values all the way to the caller.
          fs #(let [v (edn/read-string (text-of %))] (:findings (or (:error v) v)))]
      (is (true? (get-in d [:result :isError])) (pr-str d))
      (is (true? (get-in p [:result :isError])) (pr-str p))
      (is (= (mapv #(dissoc % :env/version) (fs p)) (fs d))
          "same code, same path, same message, same `:attribute` — not merely
           both refused. `:env/version` is the one key a draft cannot carry:
           it has no version until it is cut."))))

(deftest retained-results-work-through-both-authenticated-mcp-doors
  (let [store (results/create-store)
        t (workspace-token!)]
    (binding [results/*store* store]
      (try
        (publish! t)
        (let [gt (graph-token!)
              builder (wire/routes (builder-deps))
              draft-call (fn [tool args] (call builder 9631 t tool (assoc args :graph "demo/app")))
              data-call (fn [tool args] (published! gt tool args))]
          (doseq [invoke [draft-call data-call]]
            (let [response (invoke "execute_eql" {:query query :retain_result true})
                  r (json/parse-string (text-of response) true)
                  handle (:result_handle r)]
              (is (string? handle) (pr-str response))
              (let [analysis (invoke "analyze_result" {:result_handle handle
                                                       :sql "SELECT count(*) AS n FROM data"})]
                (is (not (get-in analysis [:result :isError])) (pr-str analysis))
                (is (= [["2"]] (:rows (json/parse-string (text-of analysis) true)))))
              (when (= invoke data-call)
                (binding [*state* (assoc-in *state* [:runtime :token :graph-enabled?] (constantly false))]
                  (is (true? (get-in (invoke "analyze_result"
                                            {:result_handle handle :sql "SELECT count(*) FROM data"})
                                    [:result :isError]))))))))
        (finally (results/close! store))))))
