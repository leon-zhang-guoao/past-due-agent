(ns open.atlas.studio.self-test-token-test
  "Who may issue and who may retire a **graph key** — the data-plane credential
   the Builder mints against its embedded runtime.

   These two handlers had different bars: issuing has always required editor on
   the graph, revoking checked nothing at all and took only a `token-id`. That
   was survivable while the only revoke button lived inside a disclosure panel
   on a graph row somebody had to already be looking at; the workspace-wide key
   list (8.4.11 ①) is what made it worth closing, and worth pinning here so it
   does not quietly reopen.

   Against a REAL embedded runtime: the ledger is the runtime's, so a stand-in
   would be testing a stand-in's idea of who may revoke."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.studio.app :as app]
            [open.atlas.studio.auth :as auth]
            [open.atlas.studio.builder-server]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.pool :as pool]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

;; Private by design — these are the app's own handlers, not an API.
(def ^:private issue-self-test-token!  #'open.atlas.studio.builder-server/issue-self-test-token!)
(def ^:private revoke-self-test-token! #'open.atlas.studio.builder-server/revoke-self-test-token!)
(def ^:private self-test-tokens-data   #'open.atlas.studio.builder-server/self-test-tokens-data)
;; A graph key names a serving version, so the graph has to be published before
;; one can exist at all — the fixture publishes through the real handler rather
;; than reaching into the runtime, so what is tested sits behind the same door.
(def ^:private publish-graph!          #'open.atlas.studio.builder-server/publish-graph!)
(def ^:private studio-state            #'open.atlas.studio.builder-server/studio-state)

(def ^:dynamic *owner* nil)
(def ^:dynamic *member* nil)
(def ^:private actor-key-id "studio-builder-actor")
(def ^:private actor-secret "studio-actor-secret")

(def ^:private model
  {:attributes {:cis.a/id {:type :integer :format :id :identity? true :label "A"}}
   :namespaces {"cis.a" {:kind :master :label "A"}}
   :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
   :resolvers  [{:id "a_by_id" :input [:cis.a/id] :output [:cis.a/id]
                 :result_type "single" :source {:type "sql" :query "SELECT 1"}
                 :source-ref "db"}]})

(defn- tmp [prefix] (str (System/getProperty "java.io.tmpdir") "/" prefix (random-uuid)))
(defn- rm-r [path]
  (doseq [f (reverse (file-seq (java.io.File. ^String path)))] (.delete ^java.io.File f)))
(defn- check! [r] (assert (empty? (:findings r)) (pr-str (:findings r))) r)

(defn- with-studio [f]
  (let [runtime-root (tmp "stt-rt-") artifact-root (tmp "stt-art-")
        started (app/start! {:pool* {:backend :mem :id (str (gensym "stt"))}
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
            member (:user (identity/create-local-user!
                           conn {:email "member@example.com" :name "Member"
                                 :password "a-safe-password"}))]
        ;; A plain member: the workspace makes the graph VISIBLE to them, and
        ;; that is exactly the gap this suite is about — seeing a key is not
        ;; being allowed to retire it.
        (check! (graph/add-workspace-member!
                 conn "demo" {:principal (:principal member) :role :member}))
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
        ;; `:secrets` is what signs the deploy envelopes the publish goes out in.
        (reset! (var-get studio-state)
                (assoc started :secrets {:actor-key-id actor-key-id
                                         :actor-secret actor-secret}))
        (binding [*owner* owner *member* member] (f)))
      (finally
        (reset! (var-get studio-state) nil)
        (app/stop! started)
        (rm-r runtime-root) (rm-r artifact-root)))))

(use-fixtures :each with-studio)

(defn- as [user f]
  (binding [auth/*request-user* (assoc user :default-workspace "demo")] (f)))

(defn- code-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))

(defn- published-key!
  "Publish the graph and issue a key against the version now serving. Both as
   the owner: this suite is about who may RETIRE a key, not who may mint one."
  []
  (as *owner* #(publish-graph! {:graph "demo/app"}))
  (:token-id (as *owner* #(issue-self-test-token! {:graph "demo/app"}))))

(deftest a-member-sees-the-key-and-may-not-retire-it
  "Visibility belongs to the workspace and editing does not. Before the guard,
   a `token-id` was the whole authorisation: anyone signed in could retire any
   key on this instance."
  (let [token-id (published-key!)]
    (is (some? token-id))
    (testing "the member's read shows it — that part is deliberate"
      (let [data (as *member* self-test-tokens-data)]
        (is (contains? (set (map :token-id (:tokens data))) token-id))
        (testing "but the read says which graphs they may act on, and this is not one"
          (is (= [] (:editable data))))))
    (testing "and revoking is refused"
      (is (= :token-unknown (code-of #(as *member* (fn [] (revoke-self-test-token!
                                                           {:token-id token-id}))))))
      (testing "the key still works: a refused revoke revoked nothing"
        (is (= :active (:status (first (filter #(= token-id (:token-id %))
                                               (:tokens (as *owner* self-test-tokens-data)))))))))
    (testing "the owner may, and the ledger says so before they try"
      (is (= ["demo/app"] (:editable (as *owner* self-test-tokens-data))))
      (as *owner* #(revoke-self-test-token! {:token-id token-id}))
      (is (= :revoked (:status (first (filter #(= token-id (:token-id %))
                                              (:tokens (as *owner* self-test-tokens-data))))))))))

(deftest an-unknown-key-and-a-forbidden-one-refuse-identically
  "Whether a key exists on this instance is not something a caller without
   rights on it should be able to learn by trying — the same reason D3.12's two
   doors give one answer to a key brought to the wrong one."
  (let [token-id (published-key!)]
    (is (= (code-of #(as *member* (fn [] (revoke-self-test-token!
                                          {:token-id token-id}))))
           (code-of #(as *member* (fn [] (revoke-self-test-token!
                                          {:token-id "no-such-token"}))))
           (code-of #(as *owner* (fn [] (revoke-self-test-token!
                                         {:token-id "no-such-token"}))))))))
