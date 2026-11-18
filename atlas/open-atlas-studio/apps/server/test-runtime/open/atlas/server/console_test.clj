(ns open.atlas.server.console-test
  "8.12 P3's exit conditions.

   The centrepiece is `an-imported-graph-is-served-through-the-data-plane`: it
   signs in, imports an artifact, issues a token, and reads through `/eql` with
   it — the chain 8.12 P1 could only prove up to the connector, because until
   this phase there was no way for the Server to put a graph on its own shelf."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.semantic-artifact :as semantic-artifact]
            [open.atlas.runtime.source-bindings :as source-bindings]
            [open.atlas.runtime.graph :as graph]
            [open.atlas.server.console.sources :as sources]
            [open.atlas.server.main :as main]
            ;; Required for effect, exactly as the composition root requires
            ;; them: a connector registers itself into the engine SPI by
            ;; `defmethod`, so off the classpath it does not fail loudly — the
            ;; sql resolvers are excluded and the example's own attributes come
            ;; back `:engine.catalog/unknown-attribute`. Without these lines
            ;; this namespace passed only when run alongside `main-test`, which
            ;; loads them through `main` — a test that reads the data plane
            ;; must not depend on another test having been loaded first.
            [open.atlas.common.connector.api]
            [open.atlas.common.connector.derived]
            [open.atlas.common.connector.sql]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.oidc :as oidc]
            [open.atlas.common.oidc.fake-idp :as fake]
            [open.atlas.runtime.instance :as instance]
            [open.atlas.runtime.artifact-cleanup :as cleanup]
            [open.atlas.runtime.token :as token]
            [open.atlas.server.console :as console]
            [open.atlas.server.console.example :as example]
            [open.atlas.server.data :as data]
            [open.atlas.server.identity :as identity]))

;; --- a running instance, a signed-in operator --------------------------------

(def ^:private password "a-long-enough-password")

(defn- temp-root []
  (str (java.nio.file.Files/createTempDirectory
        "server-console-" (into-array java.nio.file.attribute.FileAttribute []))))

(defn- sealed-files
  "A real artifact: encode → seal, the two pure functions B3 and B5 share."
  []
  (let [encoded (artifact/encode-content {:namespaces {"demo" {:kind :master}}} [])]
    (:files
     (artifact/seal-graph
      (:content-edn encoded) (:content-hash encoded)
      {:workspace "test" :graph "orders" :version 1
       :cut-at "2026-08-31T00:00:00Z" :cut-by "console-test"}
      {:findings [] :carried-only-keys [] :row-security-declared []}
      {:previous-version nil :added-attributes [] :removed-attributes []
       :modified-objects []}
      {:attribute-count 0}))))

(defn- with-console
  "Start one instance + its console handler, run `f`, always stop.
   `extra-deps` is merged into the console's dependency map — doc 8.16's
   `:oidc` handle arrives that way."
  ([f] (with-console {} f))
  ([extra-deps f]
  (let [root (temp-root)
        runtime (instance/start!
                 {:instance-id "console-test" :root root :token-pepper "pepper"
                  :resolve-source (source-bindings/file-resolver (str (io/file root "source-bindings.edn")) (fn [_ x] x))
                  :actor-key #(when (= "actor-key" %) "actor-secret")
                  :service-allows? (fn [principal _]
                                     (= principal {:issuer "service-key" :subject "console"}))})]
    (assert (empty? (:findings runtime)) (pr-str (:findings runtime)))
    (instance/recover! runtime)
    (identity/sign-out-all!)
    (try
      (let [db (identity/open! (:instance runtime))
            deps (merge {:runtime runtime :identity-db db :instance-id "console-test"
                         :service-key-id "console" :actor-key-id "actor-key"
                         :actor-secret "actor-secret" :home root :data-port 3009}
                        extra-deps)]
        (f {:deps deps :handler (console/routes deps) :runtime runtime :root root}))
      (finally (instance/stop! runtime))))))

(defn- request
  ([handler method uri] (request handler method uri nil nil))
  ([handler method uri body] (request handler method uri body nil))
  ([handler method uri body cookie]
   (handler (cond-> {:request-method method :uri uri}
              body   (assoc :body (io/input-stream (.getBytes (pr-str body) "UTF-8")))
              cookie (assoc :headers {"cookie" (str console/session-cookie-name "=" cookie)})))))

(defn- edn-body [response] (edn/read-string (:body response)))

(defn- signed-in
  "→ the session cookie value for a freshly bootstrapped operator."
  [{:keys [deps handler]}]
  (identity/create-operator! (:identity-db deps) {:username "admin" :password password})
  (let [r (request handler :post "/api/console/auth/sign-in"
                   {:username "admin" :password password})]
    (assert (= 200 (:status r)) (pr-str r))
    (second (re-find #"open_atlas_server_session=([^;]+)"
                     (get-in r [:headers "set-cookie"])))))

;; --- exit condition: nothing is reachable without a session ------------------

(def ^:private every-route
  [[:get  "/api/console/graphs"]
   [:post "/api/console/graphs/import"]
   [:post "/api/console/graphs/import/plan"]
   [:get  "/api/console/packages"]
   [:post "/api/console/packages/install"]
   [:post "/api/console/graphs/enabled"]
   [:post "/api/console/graphs/archive"]
   [:post "/api/console/graphs/restore"]
   [:post "/api/console/graphs/delete"]
   [:post "/api/console/graphs/publish"]
   [:get "/api/console/graphs/page"]
   [:get "/api/console/graphs/detail"]
   [:get "/api/console/graphs/versions"]
   [:get "/api/console/graphs/sources"]
   [:get "/api/console/graphs/review"]
   [:get "/api/console/tokens/page"]
   [:post "/api/console/groups/save"]
   [:post "/api/console/groups/delete"]
   [:post "/api/console/groups/assign"]
   [:post "/api/console/groups/sources"]
   [:get  "/api/console/tokens"]
   [:post "/api/console/tokens/issue"]
   [:post "/api/console/tokens/revoke"]
   [:get  "/api/console/obs/activity"]
   [:get  "/api/console/obs/logs"]
   [:get  "/api/console/obs/metrics"]])

(deftest no-route-answers-without-a-session
  (with-console
    (fn [{:keys [handler] :as ctx}]
      (signed-in ctx)                       ; an operator exists; still no cookie
      (doseq [[method uri] every-route]
        (let [r (request handler method uri {})]
          (is (= 401 (:status r)) (str uri " answered " (:status r) " unauthenticated"))
          (is (= :error (:status (edn-body r))))))
      (testing "an unknown path is 401 too, so the routes cannot be mapped"
        (is (= 401 (:status (request handler :get "/api/console/does-not-exist")))))
      (testing "a stale cookie is not a session"
        (is (= 401 (:status (request handler :get "/api/console/graphs" nil "not-a-token"))))))))

(deftest signing-out-ends-the-session-it-names
  (with-console
    (fn [{:keys [handler] :as ctx}]
      (let [cookie (signed-in ctx)]
        (is (= 200 (:status (request handler :get "/api/console/graphs" nil cookie))))
        (request handler :post "/api/console/auth/sign-out" nil cookie)
        (is (= 401 (:status (request handler :get "/api/console/graphs" nil cookie))))))))

(deftest the-sign-in-form-can-tell-a-fresh-instance-from-a-wrong-password
  (with-console
    (fn [{:keys [handler]}]
      (let [state (edn-body (request handler :get "/api/console/auth/state"))]
        (is (false? (:authenticated? state)))
        (is (false? (:bootstrapped? state))
            "otherwise a fresh instance says 'wrong password' forever")))))

;; --- exit condition: the whole chain, end to end -----------------------------

(deftest an-imported-graph-is-served-through-the-data-plane
  (with-console
    (fn [{:keys [handler runtime] :as ctx}]
      (let [cookie (signed-in ctx)
            import* (edn-body (request handler :post "/api/console/graphs/import"
                                       {:files (sealed-files)} cookie))]
        (is (= :ok (:status import*)) (pr-str import*))
        (is (= "test/orders" (:graph-id import*)))
        (is (= 1 (:version import*)))

        (testing "the shelf, the serving pointer and enablement are all visible"
          (let [{:keys [graphs]} (edn-body (request handler :get "/api/console/graphs"
                                                    nil cookie))
                g (first graphs)]
            (is (= 1 (count graphs)))
            (is (= "test/orders" (:graph-id g)))
            (is (= 1 (:serving-version g)))
            (is (true? (:enabled? g)))))

        (let [issued (edn-body (request handler :post "/api/console/tokens/issue"
                                        {:graph-id "test/orders"} cookie))
              secret (:token issued)]
          (is (= :ok (:status issued)))
          (is (string? secret))

          (testing "the secret is returned once and is NOT in the ledger (S5.9)"
            (let [listed (edn-body (request handler :get "/api/console/tokens" nil cookie))
                  record (first (:tokens listed))]
              (is (= (:token-id issued) (:token-id record)))
              (is (not (str/includes? (pr-str listed) secret))
                  "the ledger keeps a keyed digest; a secret readable twice is not a secret")
              (is (not (str/includes? (pr-str (token/all-records (:token runtime))) secret)))))

          (testing "and that token reads through /eql — the chain P1 could not close"
            (let [eql (data/routes {:instance runtime :now (constantly "2026-08-31T00:00:00Z")})
                  r   (eql {:request-method :post :uri "/eql"
                            :headers {"authorization" (str "Bearer " secret)}
                            :body (io/input-stream
                                   (.getBytes (pr-str {:request {:entity {} :query []}})
                                              "UTF-8"))})]
              (is (= 200 (:status r))
                  (str "the data plane refused a token this console just issued: "
                       (:body r)))))

          (testing "D1.9b: only a token issued as a Workbench service token may name a user"
            (let [eql (data/routes {:instance runtime :now (constantly "2026-08-31T00:00:00Z")})
                  read-as (fn [bearer]
                            (eql {:request-method :post :uri "/eql"
                                  :headers {"authorization" (str "Bearer " bearer)}
                                  :body (io/input-stream
                                         (.getBytes (pr-str {:request {:entity {} :query []}
                                                             :on-behalf-of {:issuer "workbench" :subject "alex@example.com"}})
                                                    "UTF-8"))}))
                  vouching (edn-body (request handler :post "/api/console/tokens/issue"
                                              {:graph-id "test/orders" :asserts-user? true} cookie))]
              (is (= 403 (:status (read-as secret))) "the plain token just issued cannot vouch")
              (is (true? (get-in vouching [:record :asserts-user?])))
              (is (= 200 (:status (read-as (:token vouching)))))
              (is (= :error (:status (edn-body (request handler :post "/api/console/tokens/issue"
                                                         {:graph-id "test/orders" :asserts-user? "yes"} cookie))))
                  "anything but true/false is refused, not read as a yes")))

          (testing "revoking it is visible in the ledger"
            (let [r (edn-body (request handler :post "/api/console/tokens/revoke"
                                       {:token-id (:token-id issued)} cookie))]
              (is (= :ok (:status r)))
              (is (= :revoked (:status (:record r)))
                  "the response carries the ledger's record, read back after the write")
              (is (= :revoked
                     (:status (token/record (:token runtime) (:token-id issued))))))))))))

(def ^:private absent-var
  "A name no environment sets. The plan asks the process environment whether a
   name is set, and a test may not set one — so the fixture is a name that is
   not set rather than a stubbed environment."
  "OPEN_ATLAS_TEST_ABSENT_VAR_XYZ")

(defn- packaged-files
  "A sealed artifact declaring a `:package` block (doc 104 §8.17.7 A)."
  [package]
  (let [encoded (artifact/encode-content {:namespaces {"demo" {:kind :master}}} [])]
    (:files
     (artifact/seal-graph
      (:content-edn encoded) (:content-hash encoded)
      {:workspace "test" :graph "packaged" :version 1
       :cut-at "2026-09-06T00:00:00Z" :cut-by "console-test"}
      {:findings [] :carried-only-keys [] :row-security-declared []}
      {:previous-version nil :added-attributes [] :removed-attributes []
       :modified-objects []}
      {:attribute-count 0}
      package))))

(deftest a-package-missing-a-variable-is-planned-and-refused
  ;; doc 104 §8.17.7 C: the refusal happens BEFORE the shelf is touched. Until
  ;; this landed the import answered :ok and the first query died with
  ;; :engine.execution/failed, which nobody could trace back to the install.
  (with-console
    (fn [{:keys [handler] :as ctx}]
      (let [cookie (signed-in ctx)
            files (packaged-files {:name "open-atlas/packaged"
                                   :requires {:vars [absent-var]}
                                   :compat {:dialect dialect/format-version
                                            :connectors {}}})
            plan (edn-body (request handler :post "/api/console/graphs/import/plan"
                                    {:files files} cookie))]
        (is (= :ok (:status plan)))
        (is (= "open-atlas/packaged" (:package plan)))
        (is (false? (:would-import? plan)))
        (is (= [{:name absent-var :present? false}] (get-in plan [:requires :vars])))
        (is (= [:credential/missing] (mapv :code (:findings plan))))

        (let [r (edn-body (request handler :post "/api/console/graphs/import"
                                   {:files files} cookie))]
          (is (= :error (:status r)))
          (is (= [:credential/missing] (mapv :code (:findings r))))
          (is (false? (get-in r [:plan :would-import?]))))

        (testing "nothing reached the shelf"
          (is (empty? (:graphs (edn-body (request handler :get "/api/console/graphs"
                                                  nil cookie))))))))))

(deftest an-undeclared-bundles-variables-are-read-from-its-source-entries
  ;; doc 106 §106.14-12 / OQ-IMPORT-VARS: a bundle cut before the `:package`
  ;; block still says which variables it needs, because the plan reads the
  ;; source entries themselves. Before this, «nothing could be checked» let a
  ;; half-dead graph onto the shelf and the failure surfaced at the first query.
  (with-console
    (fn [{:keys [handler] :as ctx}]
      (let [cookie (signed-in ctx)
            encoded (artifact/encode-content
                     {:namespaces {"demo" {:kind :master}}
                      :sources {"nifi" {:type "api"
                                        :base_url "https://nifi.example/nifi-api"
                                        :auth {:type "api_key"
                                               :value (str "${" absent-var "}")}}}}
                     [])
            files (:files
                   (artifact/seal-graph
                    (:content-edn encoded) (:content-hash encoded)
                    {:workspace "test" :graph "unpackaged" :version 1
                     :cut-at "2026-09-07T00:00:00Z" :cut-by "console-test"}
                    {:findings [] :carried-only-keys [] :row-security-declared []}
                    {:previous-version nil :added-attributes [] :removed-attributes []
                     :modified-objects []}
                    {:attribute-count 0}))
            plan (edn-body (request handler :post "/api/console/graphs/import/plan"
                                    {:files files} cookie))]
        (is (= [{:name absent-var :present? false}] (get-in plan [:requires :vars]))
            (pr-str plan))
        (is (false? (:would-import? plan)))
        (is (= #{:package/undeclared :credential/missing}
               (set (map :code (:findings plan)))))
        (testing "apply refuses, and nothing reached the shelf"
          (let [r (edn-body (request handler :post "/api/console/graphs/import"
                                     {:files files} cookie))]
            (is (= :error (:status r)))
            (is (contains? (set (map :code (:findings r))) :credential/missing)))
          (is (empty? (:graphs (edn-body (request handler :get "/api/console/graphs"
                                                  nil cookie))))))))))

(deftest a-bundle-cut-before-packages-existed-installs-with-a-warning
  ;; H item 3 (owner ruling 2026-09-06) — this is what the three bundles in
  ;; fixtures/bundles do today.
  (with-console
    (fn [{:keys [handler] :as ctx}]
      (let [cookie (signed-in ctx)
            r (edn-body (request handler :post "/api/console/graphs/import"
                                 {:files (sealed-files)} cookie))]
        (is (= :ok (:status r)))
        (is (true? (get-in r [:plan :would-import?])))
        (is (= [:package/undeclared] (mapv :code (get-in r [:plan :findings]))))))))

(deftest the-package-directory-lists-and-installs
  ;; doc 104 §8.17.7 E. The directory is named by the assembly root here; a
  ;; downloaded Server finds `bundles/` beside its jar.
  (let [dir (io/file (str (java.nio.file.Files/createTempDirectory
                           "console-packages-"
                           (into-array java.nio.file.attribute.FileAttribute []))))
        zip (io/file dir "orders.zip")]
    (with-open [out (java.util.zip.ZipOutputStream. (io/output-stream zip))]
      (doseq [[nm text] (sealed-files)]
        (.putNextEntry out (java.util.zip.ZipEntry. (str "test/orders/1/" nm)))
        (.write out (.getBytes ^String text "UTF-8"))
        (.closeEntry out)))
    (spit (io/file dir "index.edn")
          (pr-str [{:package nil :file "orders.zip" :graph-id "test/orders"
                    :version 1 :summary "orders, for the console test"}]))
    (with-console
      {:bundles-dir (.getPath dir)}
      (fn [{:keys [handler] :as ctx}]
        (let [cookie (signed-in ctx)
              listing (edn-body (request handler :get "/api/console/packages" nil cookie))
              row (first (:packages listing))]
          (is (= :ok (:status listing)))
          (is (= 1 (count (:packages listing))))
          (is (= "orders, for the console test" (:summary row)))
          (is (true? (:would-import? row)))
          (is (nil? (:installed-version row)))

          (testing "an install is the ordinary import, and the graph is then served"
            (let [r (edn-body (request handler :post "/api/console/packages/install"
                                       {:file "orders.zip"} cookie))]
              (is (= :ok (:status r)) (pr-str r))
              (is (= "test/orders" (:graph-id r))))
            (let [row (first (:packages (edn-body (request handler :get "/api/console/packages"
                                                           nil cookie))))]
              (is (= 1 (:installed-version row)))))

          (testing "a file name outside the index is not a package"
            (let [r (edn-body (request handler :post "/api/console/packages/install"
                                       {:file "../index.edn"} cookie))]
              (is (= :error (:status r)))
              (is (= [:package/unknown] (mapv :code (:findings r)))))))))))

(deftest an-illegal-import-is-refused-with-a-structured-finding
  (with-console
    (fn [{:keys [handler] :as ctx}]
      (let [cookie (signed-in ctx)]
        (testing "not the three files"
          (let [r (edn-body (request handler :post "/api/console/graphs/import"
                                     {:files {"content.edn" "{}"}} cookie))]
            (is (= :error (:status r)))))
        (testing "a bundle that is not a zip is refused BY NAME (D4.13)"
          (let [r (edn-body (request handler :post "/api/console/graphs/import"
                                     {:bundle "bm90LWEtemlw"} cookie))]
            (is (= :error (:status r)))
            (is (= [:server.bundle/invalid] (mapv :code (:findings r))))))
        (testing "a token for a graph this instance never loaded"
          (let [r (edn-body (request handler :post "/api/console/tokens/issue"
                                     {:graph-id "nobody/here"} cookie))]
            (is (= :error (:status r)))
            (is (seq (:findings r)))
            (is (every? #(str/starts-with? (namespace (:code %)) "token") (:findings r))
                (pr-str (:findings r)))))))))

(deftest a-body-that-is-not-an-edn-map-is-refused-rather-than-guessed
  (with-console
    (fn [{:keys [handler] :as ctx}]
      (let [cookie (signed-in ctx)
            r (handler {:request-method :post :uri "/api/console/tokens/issue"
                        :headers {"cookie" (str console/session-cookie-name "=" cookie)}
                        :body (io/input-stream (.getBytes "not edn at all [" "UTF-8"))})]
        (is (= 400 (:status r)))))))

;; --- the page itself ---------------------------------------------------------

(deftest the-console-page-is-served-but-the-api-behind-it-is-not
  (with-console
    (fn [{:keys [handler]}]
      (testing "the sign-in screen loads without a session, because it has to"
        (doseq [[uri fragment] [["/" "<div id=\"app\">"]
                                ["/console.css" "--accent"]]]
          (let [r (request handler :get uri)]
            (is (= 200 (:status r)) uri)
            (is (str/includes? (:body r) fragment) uri))))
      (testing "the compiled script, or a refusal that says the build has not run"
        ;; `resources-console/js/` is build output and is not committed, so
        ;; this asserts BOTH shapes rather than requiring a node toolchain to
        ;; run the Clojure tests. What it pins is the thing worth pinning: a
        ;; missing script is never a blank page with a silent 404.
        (let [r (request handler :get "/js/main.js")]
          (if (= 200 (:status r))
            (is (str/includes? (:body r) "open"))
            (is (and (= 500 (:status r))
                     (str/includes? (:body r) "console:release"))))))
      (testing "and serving it changed nothing about the API being closed"
        (is (= 401 (:status (request handler :get "/api/console/graphs"))))))))

;; --- first run (8.14) --------------------------------------------------------

(deftest the-first-operator-is-registered-in-the-browser-and-only-the-first
  (testing "an instance with no operator lets one be created; then it does not"
    (with-console
      (fn [{:keys [handler]}]
        (is (false? (:bootstrapped? (edn-body (request handler :get "/api/console/auth/state")))))
        (let [r (request handler :post "/api/console/auth/register"
                         {:username "owner" :password password})]
          (is (= 201 (:status r)))
          (is (= "owner" (get-in (edn-body r) [:operator :username])))
          (testing "and the response signs them in — no second password prompt"
            (let [cookie (second (re-find #"open_atlas_server_session=([^;]+)"
                                          (get-in r [:headers "set-cookie"])))]
              (is (= 200 (:status (request handler :get "/api/console/graphs" nil cookie)))))))
        (testing "the door closes behind the first one"
          (let [r (request handler :post "/api/console/auth/register"
                           {:username "intruder" :password password})]
            (is (= 409 (:status r)))
            (is (= [:identity.account/exists] (mapv :code (:findings (edn-body r)))))))))))

(deftest a-refused-registration-says-which-field-and-does-not-open-the-instance
  (with-console
    (fn [{:keys [handler] :as ctx}]
      (let [r (request handler :post "/api/console/auth/register"
                       {:username "owner" :password "short"})]
        (is (= 400 (:status r)))
        (is (= [:identity.account/invalid] (mapv :code (:findings (edn-body r))))))
      (testing "and nothing was created, so the first operator is still to come"
        (is (false? (:bootstrapped? (edn-body (request handler :get "/api/console/auth/state")))))
        (signed-in ctx)))))

(deftest the-bundled-example-imports-through-the-ordinary-path
  (testing "one button, four signed commands, and then a real row through /eql"
    (with-console
      (fn [{:keys [handler runtime] :as ctx}]
        (let [cookie (signed-in ctx)]
          (is (true? (:example-available? (edn-body (request handler :get "/api/console/auth/state"))))
              "the fixture is the fallback when the packaged copy is absent")
          (let [r (edn-body (request handler :post "/api/console/graphs/example" {} cookie))]
            (is (= :ok (:status r)) (pr-str r))
            (is (= "demo/dvd" (:graph-id r))))

          (testing "the example's database was materialised under THIS instance"
            (is (.isFile (example/database-file (:instance runtime)))))

          (let [secret (:token (edn-body (request handler :post "/api/console/tokens/issue"
                                                  {:graph-id "demo/dvd"} cookie)))
                eql (data/routes {:instance runtime :now (constantly "2026-09-01T00:00:00Z")})
                answer (eql {:request-method :post :uri "/eql"
                             :headers {"authorization" (str "Bearer " secret)}
                             :body (io/input-stream
                                    (.getBytes (pr-str {:request {:entity {:dvd.catalog.film/film_id 7}
                                                                  :query [:dvd.catalog.film/title]}})
                                               "UTF-8"))})]
            (is (= 200 (:status answer)) (str answer))
            (is (str/includes? (:body answer) "Crimson Lantern")
                "a real row out of the example's own database")))))))

;; --- operators (2026-09-04) --------------------------------------------------

(deftest a-signed-in-operator-creates-the-accounts-that-follow-the-first
  (with-console
    (fn [{:keys [handler] :as ctx}]
      (let [cookie (signed-in ctx)
            created (request handler :post "/api/console/operators"
                             {:username "second" :password password
                              :display-name "The Second"} cookie)]
        (is (= 201 (:status created)) (pr-str created))
        (is (= "second" (get-in (edn-body created) [:operator :username])))

        (testing "and that account can sign in"
          (let [r (request handler :post "/api/console/auth/sign-in"
                           {:username "second" :password password})]
            (is (= 200 (:status r)) (pr-str r))))

        (testing "the list shows both, marks the caller, and carries no secret"
          (let [listed (edn-body (request handler :get "/api/console/operators" nil cookie))
                by-name (into {} (map (juxt :username identity) (:operators listed)))]
            (is (= :ok (:status listed)))
            (is (= #{"admin" "second"} (set (keys by-name))))
            (is (true? (:self? (get by-name "admin"))))
            (is (nil? (:self? (get by-name "second"))))
            (is (not (str/includes? (pr-str listed) password))
                "a password must never travel back out of the store")
            (is (not (str/includes? (pr-str listed) "pbkdf2-sha256$"))
                "nor the hash of one")))))))

(def ^:private operator-routes
  [[:get  "/api/console/operators"]
   [:post "/api/console/operators"]
   [:post "/api/console/operators/password"]
   [:post "/api/console/operators/sso-email"]
   [:post "/api/console/operators/disabled"]])

(deftest the-operator-routes-are-closed-without-a-session
  (with-console
    (fn [{:keys [handler] :as ctx}]
      (signed-in ctx)
      (doseq [[method uri] operator-routes]
        (let [r (request handler method uri {})]
          (is (= 401 (:status r)) (str uri " answered " (:status r) " unauthenticated")))))))

(deftest disabling-your-own-account-is-refused-and-another-one-is-not
  (with-console
    (fn [{:keys [handler] :as ctx}]
      (let [cookie (signed-in ctx)]
        (request handler :post "/api/console/operators"
                 {:username "second" :password password} cookie)

        (testing "your own account, refused by name — otherwise the last operator locks the instance"
          (let [r (request handler :post "/api/console/operators/disabled"
                           {:username "admin" :disabled? true} cookie)]
            (is (= 400 (:status r)))
            (is (= [:identity.account/self-disable] (mapv :code (:findings (edn-body r)))))))

        (testing "another account is disabled, and cannot sign in afterwards"
          (let [r (request handler :post "/api/console/operators/disabled"
                           {:username "second" :disabled? true} cookie)]
            (is (= 200 (:status r)) (pr-str r))
            (is (true? (get-in (edn-body r) [:operator :disabled?]))))
          (is (= 401 (:status (request handler :post "/api/console/auth/sign-in"
                                       {:username "second" :password password})))))

        (testing "a reset password signs them in — once they are enabled again"
          (request handler :post "/api/console/operators/disabled"
                   {:username "second" :disabled? false} cookie)
          (let [r (request handler :post "/api/console/operators/password"
                           {:username "second" :password "another-long-password"} cookie)]
            (is (= 200 (:status r)) (pr-str r)))
          (is (= 401 (:status (request handler :post "/api/console/auth/sign-in"
                                       {:username "second" :password password})))
              "the old password stops working")
          (is (= 200 (:status (request handler :post "/api/console/auth/sign-in"
                                       {:username "second"
                                        :password "another-long-password"})))))))))

;; --- single sign-on (doc 8.16, §8.16.7's acceptance for this app) ------------
;;
;; The IdP is `open.atlas.common.oidc.fake-idp`, the one the protocol module's
;; own tests drive, on this app's classpath through :test-prod's :extra-paths.
;; A second fake would be a second set of assumptions about what an IdP does,
;; and this round trip is worth exactly what its IdP is.

(def ^:private client-secret "console-client-secret-not-in-any-log")
(def ^:private client-id "console-client")
(def ^:private sso-kid "console-test-key")
(def ^:private authorization-code "the-console-authorization-code")

(defn- sso-env [base extra]
  (merge {"OIDC_DISCOVERY_URL" (str base "/.well-known/openid-configuration")
          "OIDC_CLIENT_ID"     client-id
          "OIDC_CLIENT_SECRET" client-secret
          "OIDC_REDIRECT_URI"  "http://127.0.0.1:3009/api/console/auth/oidc/callback"
          "OIDC_PROVIDER_NAME" "Corp SSO"}
         extra))

(defn- with-sso-console
  "A console whose deps carry a live handle pointed at a fake IdP."
  ([f] (with-sso-console {} f))
  ([{:keys [env-extra]} f]
   (let [kp  (fake/rsa-keypair)
         ctl (atom {:jwks [(fake/rsa-jwk kp sso-kid)]})
         idp (fake/start! ctl)]
     (try
       (let [handle (oidc/open (:config (oidc/config (sso-env (:base-url idp)
                                                              (or env-extra {})))))]
         (try
           (with-console {:oidc handle}
             (fn [ctx] (f (assoc ctx :kp kp :ctl ctl :handle handle
                                 :issuer (:issuer @ctl)))))
           (finally (oidc/close! handle))))
       (finally (fake/stop! idp))))))

(defn- get-query
  "A GET with a query string — the two SSO routes are the only console routes
   that read one from the browser."
  ([handler uri query] (get-query handler uri query nil))
  ([handler uri query cookie]
   (handler (cond-> {:request-method :get :uri uri}
              query  (assoc :query-string query)
              cookie (assoc :headers {"cookie" (if (map? cookie)
                                                   (str/join "; " (map (fn [[k v]] (str k "=" v)) cookie))
                                                   (str console/session-cookie-name "=" cookie))})))))

(defn- query-of [url]
  (into {} (for [pair (str/split (.getQuery (java.net.URI/create url)) #"&")
                 :let [[k v] (str/split pair #"=" 2)]]
             [k (java.net.URLDecoder/decode (or v "") "UTF-8")])))

(defn- id-token-claims [issuer nonce overrides]
  (merge {"iss" issuer "sub" "subject-1" "aud" client-id
          "exp" (+ (quot (System/currentTimeMillis) 1000) 300)
          "nonce" nonce "email" "Person@Example.COM" "name" "A Person"}
         overrides))

(defn- round-trip
  "begin → mint an id_token the fake IdP will hand back → callback.
   → `{:begin :state :id-token :callback}`."
  ([ctx] (round-trip ctx {}))
  ([{:keys [handler kp ctl issuer]} {:keys [return-to claims]}]
   (let [begin (get-query handler "/api/console/auth/oidc"
                          (when return-to (str "return_to=" return-to)))
         q     (query-of (get-in begin [:headers "location"]))
         token (fake/sign-jwt kp sso-kid "RS256"
                              (id-token-claims issuer (get q "nonce") claims))]
     (swap! ctl assoc :token-status 200
            :token-body {"id_token" token "access_token" "console-access-token"})
     {:begin begin
      :state (get q "state")
      :id-token token
      :callback (get-query handler "/api/console/auth/oidc/callback"
                           (str "state=" (get q "state") "&code=" authorization-code)
                           {"open_atlas_server_oidc_state"
                            (second (re-find #"open_atlas_server_oidc_state=([^;]+)"
                                             (get-in begin [:headers "set-cookie"])))})})))

(defn- cookie-of [response]
  (second (re-find #"open_atlas_server_session=([^;]+)"
                   (str (get-in response [:headers "set-cookie"])))))

(defn- codes-of [response] (mapv :code (:findings (edn-body response))))

(deftest the-sign-in-screen-is-told-whether-this-server-has-a-provider
  (testing "the button is drawn from auth-state and from nothing else"
    (with-console
      (fn [{:keys [handler]}]
        (is (nil? (:oidc (edn-body (request handler :get "/api/console/auth/state"))))
            "no OIDC_* configured: no button, and no route that would work")))
    (with-sso-console
      (fn [{:keys [handler]}]
        (is (= {:id "oidc" :name "Corp SSO"}
               (:oidc (edn-body (request handler :get "/api/console/auth/state")))))))))

(deftest the-sso-routes-answer-404-when-single-sign-on-is-not-configured
  (with-console
    (fn [{:keys [handler]}]
      (doseq [uri ["/api/console/auth/oidc" "/api/console/auth/oidc/callback"]]
        (let [r (get-query handler uri nil)]
          (is (= 404 (:status r)) uri)
          (is (= [:auth/oidc-disabled] (codes-of r)) uri))))))

(deftest a-pre-registered-operator-signs-in-through-the-identity-provider
  (testing "§8.16.7: button → 302 → callback → cookie → auth-state knows them"
    (with-sso-console
      (fn [{:keys [handler deps] :as ctx}]
        (identity/create-operator! (:identity-db deps)
                                   {:username "sso-operator"
                                    :sso-email "person@example.com"})
        (let [{:keys [begin callback state]} (round-trip ctx {:return-to "/tokens"})]
          (testing "the authorization request carries PKCE S256 and a state"
            (is (= 302 (:status begin)))
            (let [q (query-of (get-in begin [:headers "location"]))]
              (is (= "S256" (get q "code_challenge_method")))
              (is (seq (get q "code_challenge")))
              (is (= "code" (get q "response_type")))
              (is (= client-id (get q "client_id")))
              (is (= state (get q "state")))))

          (testing "the callback seats a session and sends the browser on"
            (is (= 302 (:status callback)) (pr-str callback))
            (is (= "/tokens" (get-in callback [:headers "location"])))
            (let [cookie (cookie-of callback)
                  state* (edn-body (request handler :get "/api/console/auth/state"
                                            nil cookie))]
              (is (some? cookie))
              (is (true? (:authenticated? state*)))
              (is (= "sso-operator" (get-in state* [:operator :username])))
              (testing "and that session is an ordinary console session"
                (is (= 200 (:status (request handler :get "/api/console/graphs"
                                             nil cookie)))))))

          (testing "the link is now on the account, matched by principal from here on"
            (let [op (identity/operator-by-principal (:identity-db deps)
                                                     (:issuer ctx) "subject-1")]
              (is (= "sso-operator" (:username op)))
              (is (true? (:sso-linked? op)))))

          (testing "a replayed state is refused — the transaction is one-time"
            (let [replay (get-query handler "/api/console/auth/oidc/callback"
                                    (str "state=" state "&code=" authorization-code)
                                    {"open_atlas_server_oidc_state" state})]
              (is (= 401 (:status replay)))
              (is (= [:auth/expired-oidc-transaction] (codes-of replay))))))))))

(deftest a-callback-is-bound-to-the-browser-that-started-login
  (with-sso-console
    (fn [{:keys [handler deps]}]
      (let [begin (get-query handler "/api/console/auth/oidc" nil)
            state (get (query-of (get-in begin [:headers "location"])) "state")]
        (is (str/includes? (get-in begin [:headers "set-cookie"]) "HttpOnly; SameSite=Lax"))
        (doseq [cookie [nil {"open_atlas_server_oidc_state" "another-browser"}]]
          (let [response (get-query handler "/api/console/auth/oidc/callback"
                                     (str "state=" state "&code=code") cookie)]
            (is (= 401 (:status response)))
            (is (= [:auth/invalid-oidc-response] (codes-of response)))
            (is (contains? @(get-in deps [:oidc :transactions]) state))))))))

(deftest an-address-this-console-does-not-know-is-not-an-account
  (testing "§8.16.4 rule 4.5: the console never creates an account on sign-in"
    (with-sso-console
      (fn [ctx]
        (let [{:keys [callback]} (round-trip ctx)]
          (is (= 403 (:status callback)))
          (is (= [:auth/identity-not-provisioned] (codes-of callback)))
          (is (nil? (cookie-of callback))))))))

(deftest an-account-with-a-password-is-not-taken-over-by-an-assertion
  (testing "§8.16.4 rule 4.4: without an sso_email no assertion reaches a password operator"
    (with-sso-console
      (fn [{:keys [deps] :as ctx}]
        ;; The console matches by email only on `sso_email`, which only an
        ;; operator sets; a username that looks like the address is not one.
        (identity/create-operator! (:identity-db deps)
                                   {:username "person@example.com" :password password})
        (let [{:keys [callback]} (round-trip ctx)]
          (is (= 403 (:status callback)))
          (is (= [:auth/identity-not-provisioned] (codes-of callback)))
          (is (nil? (cookie-of callback))))))))

;; Doc 101 §8.16.4 rule 4.4's remedy and §8.16.9 item 3: an operator links an
;; EXISTING password account by setting its `sso_email`. Mirrors Workbench's
;; sso-preregistration tests so the three apps are held to one behaviour.

(defn- set-sso-email [handler cookie username email]
  (request handler :post "/api/console/operators/sso-email"
           {:username username :sso-email email} cookie))

(deftest an-existing-password-operator-is-pre-registered-then-linked-once
  (with-sso-console
    (fn [{:keys [deps handler] :as ctx}]
      (let [cookie (signed-in ctx)
            db     (:identity-db deps)]
        (identity/create-operator! db {:username "jie" :password password})
        (testing "set: normalised, shown as pending, password still there"
          (let [r (set-sso-email handler cookie "jie" " Person@Example.COM ")]
            (is (= 200 (:status r)) (pr-str r))
            (is (= "person@example.com" (get-in (edn-body r) [:operator :sso-email])))
            (is (false? (get-in (edn-body r) [:operator :sso-linked?])))
            (is (true? (get-in (edn-body r) [:operator :has-password?])))))
        (testing "the next SSO sign-in binds (issuer, subject) and consumes the pre-registration"
          (is (= 302 (:status (:callback (round-trip ctx)))))
          (let [op (identity/operator-by-principal db (:issuer ctx) "subject-1")]
            (is (= "jie" (:username op)))
            (is (nil? (:sso-email op))))
          (is (nil? (identity/operator-by-sso-email db "person@example.com"))))
        (testing "the password keeps working (hybrid)"
          (is (empty? (:findings (identity/authenticate db {:username "jie" :password password})))))
        (testing "a second subject asserting the same email is not bound"
          (let [{:keys [callback]} (round-trip ctx {:claims {"sub" "someone-else"}})]
            (is (= 403 (:status callback)))
            (is (nil? (cookie-of callback)))))
        (testing "a linked operator cannot be pre-registered again"
          (let [r (set-sso-email handler cookie "jie" "other@example.com")]
            (is (= 400 (:status r)))
            (is (= [:auth/identity-already-linked] (codes-of r)))))
        (testing "an email change at the IdP does not move the link (rule 4.1 only)"
          (let [{:keys [callback]} (round-trip ctx {:claims {"email" "renamed@example.com"}})]
            (is (= 302 (:status callback)))))))))

(deftest setting-an-sso-email-is-refused-with-the-shared-codes
  (with-console
    (fn [{:keys [deps handler] :as ctx}]
      (let [cookie (signed-in ctx)
            db     (:identity-db deps)]
        (identity/create-operator! db {:username "jie" :password password})
        (identity/create-operator! db {:username "other" :sso-email "taken@example.com"})
        (is (= [:auth/invalid-email]
               (codes-of (set-sso-email handler cookie "jie" "not-an-address"))))
        (is (= [:auth/email-in-use]
               (codes-of (set-sso-email handler cookie "jie" "TAKEN@example.com"))))
        (testing "clearing is allowed where a password remains"
          (is (= 200 (:status (set-sso-email handler cookie "jie" "jie@example.com"))))
          (let [r (set-sso-email handler cookie "jie" "")]
            (is (= 200 (:status r)))
            (is (nil? (get-in (edn-body r) [:operator :sso-email])))))
        (testing "and refused where it would leave no way in"
          (is (= [:identity.account/credentials-required]
                 (codes-of (set-sso-email handler cookie "other" nil)))))
        (testing "no address in the response's refusals"
          (is (not (str/includes? (:body (set-sso-email handler cookie "jie" "TAKEN@example.com"))
                                  "taken@"))))))))

(deftest only-a-signed-in-operator-may-pre-register
  (with-console
    (fn [{:keys [deps handler]}]
      (identity/create-operator! (:identity-db deps) {:username "jie" :password password})
      (let [r (set-sso-email handler nil "jie" "person@example.com")]
        (is (= 401 (:status r)))
        (is (nil? (:sso-email (first (identity/operators (:identity-db deps))))))))))

(deftest a-domain-outside-the-allow-list-never-reaches-the-account-lookup
  (with-sso-console {:env-extra {"OIDC_ALLOWED_EMAIL_DOMAINS" "corp.test"}}
    (fn [{:keys [deps] :as ctx}]
      (identity/create-operator! (:identity-db deps)
                                 {:username "sso-operator" :sso-email "person@example.com"})
      (let [{:keys [callback]} (round-trip ctx)]
        (is (= 403 (:status callback)))
        (is (= [:auth/oidc-domain-not-allowed] (codes-of callback)))))))

(deftest a-disabled-linked-account-gets-the-password-refusal
  (testing "§8.16.4 rule 4.1: 'switched off' is not something a login confirms"
    (with-sso-console
      (fn [{:keys [deps] :as ctx}]
        (identity/create-operator! (:identity-db deps)
                                   {:username "sso-operator" :sso-email "person@example.com"})
        (is (= 302 (:status (:callback (round-trip ctx)))))
        (identity/set-disabled! (:identity-db deps) "sso-operator" true)
        (let [{:keys [callback]} (round-trip ctx)]
          (is (= 401 (:status callback)))
          (is (= [:auth/invalid-credentials] (codes-of callback))))))))

(deftest an-id-token-that-does-not-verify-is-one-refusal
  (with-sso-console
    (fn [{:keys [handler] :as ctx}]
      (testing "a callback with no state or code"
        (let [r (get-query handler "/api/console/auth/oidc/callback" "error=access_denied")]
          (is (= 401 (:status r)))
          (is (= [:auth/invalid-oidc-response] (codes-of r)))))
      (testing "an audience that is not this client"
        (let [{:keys [callback]} (round-trip ctx {:claims {"aud" "some-other-client"}})]
          (is (= 401 (:status callback)))
          (is (= [:auth/invalid-oidc-response] (codes-of callback)))))
      (testing "an id_token with no subject"
        (let [{:keys [callback]} (round-trip ctx {:claims {"sub" nil}})]
          (is (= 401 (:status callback)))
          (is (= [:auth/invalid-oidc-claims] (codes-of callback))))))))

(deftest a-return-to-that-is-not-a-path-of-this-console-is-dropped
  (testing "otherwise the sign-in route is an open redirector"
    (with-sso-console
      (fn [{:keys [deps] :as ctx}]
        (identity/create-operator! (:identity-db deps)
                                   {:username "sso-operator" :sso-email "person@example.com"})
        (let [{:keys [callback]} (round-trip ctx {:return-to "//evil.example/x"})]
          (is (= "/" (get-in callback [:headers "location"]))))))))

(deftest an-operator-is-pre-registered-for-sso-from-the-console
  (with-sso-console
    (fn [{:keys [handler] :as ctx}]
      (let [cookie (signed-in ctx)
            created (request handler :post "/api/console/operators"
                             {:username "sso-operator" :sso-email "Person@Example.COM"}
                             cookie)]
        (is (= 201 (:status created)) (pr-str (edn-body created)))
        (is (false? (get-in (edn-body created) [:operator :has-password?])))
        (testing "the listing says which accounts are linked and which are waiting"
          (let [by-name (into {} (map (juxt :username identity))
                              (:operators (edn-body (request handler :get
                                                             "/api/console/operators"
                                                             nil cookie))))]
            (is (= "person@example.com" (:sso-email (get by-name "sso-operator"))))
            (is (false? (:sso-linked? (get by-name "sso-operator"))))
            (is (nil? (:sso-email (get by-name "admin"))))))
        (testing "and neither a hash nor a password is anywhere in that answer"
          (is (not (str/includes? (pr-str (edn-body created)) "pbkdf2-sha256$"))))
        (testing "an account with neither credential is refused, not created"
          (let [r (request handler :post "/api/console/operators" {:username "nothing"} cookie)]
            (is (= 400 (:status r)))
            (is (= [:identity.account/credentials-required] (codes-of r)))))
        (testing "and after the round trip the same listing says linked"
          (round-trip ctx)
          (let [by-name (into {} (map (juxt :username identity))
                              (:operators (edn-body (request handler :get
                                                             "/api/console/operators"
                                                             nil cookie))))]
            (is (true? (:sso-linked? (get by-name "sso-operator"))))))))))

(deftest no-secret-of-the-round-trip-reaches-a-response-or-the-log
  (testing "§8.16.7, the sweep 8.12 P2 established for passwords"
    (with-sso-console
      (fn [{:keys [handler deps] :as ctx}]
        (identity/create-operator! (:identity-db deps)
                                   {:username "sso-operator" :sso-email "person@example.com"})
        (let [ok       (round-trip ctx)
              cookie   (cookie-of (:callback ok))
              refused  (round-trip ctx {:claims {"aud" "wrong"}})
              disabled (do (identity/set-disabled! (:identity-db deps) "sso-operator" true)
                           (round-trip ctx))
              texts    (mapv pr-str [(:begin ok) (:callback ok)
                                     (:callback refused) (:callback disabled)
                                     (edn-body (request handler :get
                                                        "/api/console/auth/state" nil cookie))
                                     (edn-body (request handler :get
                                                        "/api/console/obs/activity" nil cookie))])]
          (doseq [text texts]
            (is (not (str/includes? text client-secret))
                "the client secret reached a value a caller may log")
            (is (not (str/includes? text authorization-code))
                "the authorization code reached a value a caller may log")
            (is (not (str/includes? text (:id-token ok)))
                "the id_token reached a value a caller may log")
            (is (not (str/includes? text "console-access-token"))
                "the access token reached a value a caller may log")))))))

(deftest studio-semantic-release-uses-production-sources-and-shared-tokens
  (with-console
    (fn [{:keys [deps handler runtime root] :as ctx}]
      (let [cookie (signed-in ctx)
            doc (-> (edn/read-string (slurp "../../modules/common/dialect/fixtures/semantic-v2.edn"))
                    (assoc-in [:publication :release/version] "1")
                    (assoc-in [:execution :bindings "lookup-sql" :binding/implementation :query]
                              "SELECT 'production' AS title WHERE :id = 1"))
            artifact (semantic-artifact/encode-candidate
                       doc {} {"sql" #((:validate (open.atlas.common.connector.spi/semantic-adapter "sql")) %)}
                       {:workspace "support-dev" :graph "support" :version 1})
            payload {:files (:files artifact)}
            save! (fn [id config revision]
                    (edn-body (request handler :post "/api/console/sources"
                               {:id id :config config :expected-revision revision} cookie)))
            sql {:type "sql" :label "Production" :jdbc_url "jdbc:sqlite::memory:"}]
        (is (empty? (:findings artifact)) (pr-str artifact))
        (is (false? (:would-import? (open.atlas.server.console.ops/plan-import payload))))
        (is (= 401 (:status (request handler :get "/api/console/sources"))))
        (is (= 401 (:status (request handler :post "/api/console/sources" {:id "x"}))))
        (is (= :ok (:status (save! "prod" sql 0))))
        (is (= :error (:status (save! "stale" sql 0))))
        (is (= :error (:status (save! "secret" (assoc sql :password "do-not-store") 1))))
        (is (not (str/includes? (slurp (io/file root "sources.edn")) "do-not-store")))
        (let [blocked (edn-body (request handler :post "/api/console/graphs/import/plan" payload cookie))]
          (is (false? (:would-import? blocked)))
          (is (= "support-dev/support" (:graph-id blocked)))
          (is (= "db" (get-in blocked [:sources 0 :slot-id])))
          (is (not (.exists (io/file root "source-bindings.edn")))))
        (let [bound (assoc payload :source-bindings {"db" "prod"})
              plan (edn-body (request handler :post "/api/console/graphs/import/plan" bound cookie))
              published (edn-body (request handler :post "/api/console/graphs/import" bound cookie))]
          (is (:would-import? plan) (pr-str plan))
          (let [stale (edn-body (request handler :post "/api/console/graphs/import"
                                        (assoc bound :expected-source-revision 0) cookie))]
            (is (= :source.registry/revision-mismatch (get-in stale [:findings 0 :code]))))
          (is (= :ok (:status published)) (pr-str published))
          (is (= sql (get (edn/read-string (slurp (io/file root "source-bindings.edn")))
                         ["support-dev" "support" "1" "db"])))
          (let [issued (edn-body (request handler :post "/api/console/tokens/issue"
                                         {:graph-id "support-dev/support"} cookie))
                secret (:token issued)
                unified (main/routes {:instance-id "console-test" :service-keys {"console" "service"}}
                                     {:instance runtime :now (constantly "2026-09-26T00:00:00Z")} deps)
                query! (fn [] (unified {:request-method :post :uri "/eql"
                                         :headers {"authorization" (str "Bearer " secret)}
                                         :body (io/input-stream (.getBytes
                                                 (pr-str {:request {:entity {:support.case/id 1}
                                                                    :query [:support.case/title]}}) "UTF-8"))}))
                result (query!)]
            (is (= :ok (:status issued)) (pr-str issued))
            (is (= 200 (:status result)) (:body result))
            (is (str/includes? (:body result) "production") (:body result))
            (let [mcp (unified {:request-method :post :uri "/mcp"
                                :headers {"host" "127.0.0.1:3009" "authorization" (str "Bearer " secret)}
                                :body (io/input-stream (.getBytes
                                       "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}" "UTF-8"))})]
              (is (= 404 (:status mcp)) (:body mcp))
              (is (str/includes? (:body mcp) "/mcp/<graph-uuid>")))
            (is (= 401 (:status (unified {:request-method :post :uri "/eql"
                                          :headers {"cookie" (str console/session-cookie-name "=" cookie)}}))))
            (doseq [uri ["/control" "/api/console/sources" "/api/console/tokens/issue"]]
              (is (= 401 (:status (unified {:request-method :post :uri uri
                                            :headers {"authorization" (str "Bearer " secret)}}))) uri))
            (is (= :ok (:status (save! "prod" (assoc sql :label "Changed for next release") 1))))
            (is (= sql (get (edn/read-string (slurp (io/file root "source-bindings.edn")))
                           ["support-dev" "support" "1" "db"])))
            (let [refused (edn-body (request handler :post "/api/console/graphs/import" bound cookie))]
              (is (= :error (:status refused)))
              (is (= :semantic.runtime/source-binding-conflict (get-in refused [:findings 0 :code]))))
            (is (= 200 (:status (query!))))
            (request handler :post "/api/console/tokens/revoke" {:token-id (:token-id issued)} cookie)
            (is (= 401 (:status (query!))))))))))

(deftest production-credential-variables-use-studios-store-and-resolution
  (with-console
    (fn [{:keys [handler root] :as ctx}]
      (let [cookie (signed-in ctx)
            saved (edn-body (request handler :post "/api/console/sources/variable"
                                    {:name "PROD_PASSWORD" :value "write-only-test-value"} cookie))
            entry {:type "sql" :jdbc_url "jdbc:sqlite::memory:" :password "${PROD_PASSWORD}"}]
        (is (= :ok (:status saved)))
        (is (not (str/includes? (pr-str saved) "write-only-test-value")))
        (is (= :ok (:status (edn-body (request handler :post "/api/console/sources"
                                             {:id "db" :config entry :expected-revision 0} cookie)))))
        (let [listed (edn-body (request handler :get "/api/console/sources" nil cookie))]
          (is (= ["PROD_PASSWORD"] (:variables listed)))
          (is (true? (get-in listed [:sources 0 :variables 0 :stored?])))
          (is (not (str/includes? (pr-str listed) "write-only-test-value"))))
        (is (= "write-only-test-value"
               (:password (open.atlas.runtime.credentials/resolve-config root "db" entry (constantly "stale-env-value")))))
        (request handler :post "/api/console/sources/variable"
                 {:name "PROD_PASSWORD" :previous-name "PROD_PASSWORD" :value ""} cookie)
        (is (= "write-only-test-value"
               (:password (open.atlas.runtime.credentials/resolve-config root "db" entry))))
        (is (= 401 (:status (request handler :post "/api/console/sources/variable"
                                    {:name "BAD" :value "unauthorized"}))))))))

(deftest source-registration-verifies-by-default-with-an-explicit-unverified-option
  (with-console
    (fn [{:keys [deps root]}]
      (let [input {:id "missing-credential" :expected-revision 0
                   :config {:type "sql" :jdbc_url "${ATLAS_ABSENT_TEST_JDBC_URL}"}}]
        (is (= :error (:status (sources/save-source! deps input))))
        (is (not (.exists (io/file root "sources.edn"))))
        (let [saved (sources/save-source! deps (assoc input :verify? false))]
          (is (= :ok (:status saved)))
          (is (false? (:connected? saved))))))))

(deftest production-graph-lifecycle-matches-studio
  (with-console
    (fn [{:keys [handler runtime root] :as ctx}]
      (let [cookie (signed-in ctx)
            post (fn [path body] (edn-body (request handler :post path body cookie)))
            graphs #(-> (request handler :get "/api/console/graphs" nil cookie) edn-body :graphs)
            imported (post "/api/console/graphs/import" {:files (sealed-files) :activate? false})
            qn "test/orders"]
        (is (= :ok (:status imported)))
        (is (= [:offer :stage :load] (mapv :step (:steps imported))))
        (is (nil? (:serving-version (first (graphs)))))
        (is (= :error (:status (post "/api/console/tokens/issue" {:graph-id qn}))))
        (is (= :error (:status (post "/api/console/graphs/delete" {:graph-id qn :confirm-name qn}))))
        (is (= :ok (:status (post "/api/console/graphs/publish" {:graph-id qn :version 1 :expected-current nil}))))
        (let [issued (post "/api/console/tokens/issue" {:graph-id qn :expires-at "2099-01-01T00:00:00Z"})
              secret (:token issued)
              token-id (:token-id issued)]
          (is (= :ok (:status issued)))
          (is (= secret (:secret issued)))
          (is (= "2099-01-01T00:00:00Z" (get-in issued [:record :expires-at])))
          (is (= :ok (:status (post "/api/console/graphs/archive" {:graph-id qn}))))
          (is (= :archived (:state (first (graphs)))))
          ;; Archive preserves deployed service and token records, as in Studio.
          (is (= 1 (:serving-version (first (graphs)))))
          (is (= :active (:status (token/record (:token runtime) token-id))))
          (is (= :error (:status (post "/api/console/graphs/publish" {:graph-id qn :version 1 :expected-current 1}))))
          (is (= :error (:status (post "/api/console/tokens/issue" {:graph-id qn}))))
          (is (= :error (:status (post "/api/console/graphs/import" {:files (sealed-files)}))))
          (is (= :ok (:status (post "/api/console/graphs/restore" {:graph-id qn}))))
          (is (= :active (:state (first (graphs)))))
          (is (= :ok (:status (post "/api/console/graphs/archive" {:graph-id qn}))))
          (is (= :error (:status (post "/api/console/graphs/delete" {:graph-id qn :confirm-name "wrong"}))))
          (is (= :active (:status (token/record (:token runtime) token-id))))
          (let [deleted (post "/api/console/graphs/delete" {:graph-id qn :confirm-name qn})]
            (is (= :ok (:status deleted)) (pr-str deleted))
            (is (= 1 (get-in deleted [:runtime-cleanup :tokens-revoked])))
            (is (empty? (graphs)))
            (is (= :revoked (:status (token/record (:token runtime) token-id))))
            (is (seq (:findings (token/verify (:token runtime) secret {:now "2026-09-26T00:00:00Z"}))))
            (is (empty? (:findings (instance/recover! runtime))))))))))

(deftest deletion-is-blocked-by-references-and-retryable-after-io-failure
  (with-console
    (fn [{:keys [handler runtime root] :as ctx}]
      (let [cookie (signed-in ctx)
            post #(edn-body (request handler :post %1 %2 cookie))
            qn "test/orders"
            _ (is (= :ok (:status (post "/api/console/graphs/import" {:files (sealed-files)}))))
            issued (post "/api/console/tokens/issue" {:graph-id qn})
            other (io/file root "artifacts/test/dependent/1")]
        (post "/api/console/graphs/archive" {:graph-id qn})
        (.mkdirs other)
        (spit (io/file other "manifest.edn") (pr-str {:provenance {:workspace "test" :graph "dependent" :version 1}}))
        (spit (io/file other "content.edn") "{:references [{:workspace \"test\" :graph \"orders\" :version 1}]}")
        (let [refused (post "/api/console/graphs/delete" {:graph-id qn :confirm-name qn})]
          (is (= :graph-artifact-in-use (get-in refused [:findings 0 :code])))
          (is (= :active (:status (token/record (:token runtime) (:token-id issued))))))
        (doseq [file (reverse (file-seq other))] (.delete file))
        (with-redefs [cleanup/delete-path! (fn [_] (throw (java.io.IOException. "injected")))]
          (let [failed (post "/api/console/graphs/delete" {:graph-id qn :confirm-name qn})]
            (is (= :graph-artifact-cleanup-failed (get-in failed [:findings 0 :code])))))
        (let [rows (:graphs (edn-body (request handler :get "/api/console/graphs" nil cookie)))]
          (is (= :archived (:state (first rows))))
          (is (nil? (:serving-version (first rows)))))
        (is (= :revoked (:status (token/record (:token runtime) (:token-id issued)))))
        (is (= :ok (:status (post "/api/console/graphs/delete" {:graph-id qn :confirm-name qn}))))))))

(deftest instance-groups-organize-without-changing-graph-authority
  (with-console
    (fn [{:keys [handler runtime] :as ctx}]
      (let [cookie (signed-in ctx)
            post #(edn-body (request handler :post %1 %2 cookie))
            get-page (fn [uri query] (edn-body (handler {:request-method :get :uri uri :query-string query
                                                       :headers {"cookie" (str console/session-cookie-name "=" cookie)}})))
            group (:group (post "/api/console/groups/save" {:name "Operations"}))
            id (:id group)]
        (is (string? id))
        (is (= :error (:status (post "/api/console/groups/save" {:name "operations"}))))
        (is (= :error (:status (post "/api/console/graphs/import" {:files (sealed-files) :expected-graph-id "test/wrong"}))))
        (is (= :error (:status (post "/api/console/graphs/import" {:files (sealed-files) :group-id "missing"}))))
        (is (= :ok (:status (post "/api/console/graphs/import" {:files (sealed-files) :group-id id :activate? false}))))
        (let [listed (get-page "/api/console/graphs/page" (str "group=" id))
              review (get-page "/api/console/graphs/review" "graph-id=test%2Forders&version=1")]
          (is (= 1 (:total listed)))
          (is (= id (:group-id (first (:items listed)))))
          (is (true? (:would-publish? review)))
          (is (nil? (:expected-current review)))
          (is (= 1 (get-in review [:target :version]))))
        (is (= :ok (:status (post "/api/console/graphs/publish" {:graph-id "test/orders" :version 1 :expected-current nil}))))
        (let [issued (post "/api/console/tokens/issue" {:graph-id "test/orders"})]
          (is (= :ok (:status (post "/api/console/groups/save" {:id id :name "Business Operations"}))))
          (is (= :error (:status (post "/api/console/groups/assign" {:group-id nil :graph-ids ["test/orders" "test/missing"]}))))
          (is (= 1 (:total (get-page "/api/console/graphs/page" (str "group=" id)))))
          (is (= :ok (:status (post "/api/console/groups/delete" {:id id}))))
          (is (= 1 (:total (get-page "/api/console/graphs/page" "group=ungrouped"))))
          (is (empty? (:findings (token/verify (:token runtime) (:token issued) {:now "2026-09-27T00:00:00Z"})))))
        (is (= :error (:status (get-page "/api/console/graphs/detail" "graph-id=..%2Fescape"))))))))

(deftest sources-and-tokens-follow-instance-group-organization
  (with-console
    (fn [{:keys [handler runtime] :as ctx}]
      (let [cookie (signed-in ctx)
            post #(edn-body (request handler :post %1 %2 cookie))
            get-page (fn [uri query] (edn-body (handler {:request-method :get :uri uri :query-string query
                                                       :headers {"cookie" (str console/session-cookie-name "=" cookie)}})))
            a (get-in (post "/api/console/groups/save" {:name "A"}) [:group :id])
            b (get-in (post "/api/console/groups/save" {:name "B"}) [:group :id])
            source {:id "prod" :config {:type "sql" :jdbc_url "jdbc:sqlite::memory:"} :expected-revision 0 :group-id a}]
        (is (= :error (:status (post "/api/console/sources" (assoc source :group-id "missing")))))
        (is (empty? (:sources (get-page "/api/console/sources" ""))))
        (is (= :ok (:status (post "/api/console/sources" source))))
        (is (= a (:group-id (first (:sources (get-page "/api/console/sources" ""))))))
        (is (= :error (:status (post "/api/console/groups/sources" {:source-ids ["prod" "missing"] :group-id b}))))
        (is (= a (:group-id (first (:sources (get-page "/api/console/sources" ""))))))
        (is (= :ok (:status (post "/api/console/groups/sources" {:source-ids ["prod"] :group-id b}))))
        (is (= 1 (:revision (get-page "/api/console/sources" ""))))
        (is (= b (:group-id (first (:sources (get-page "/api/console/sources" ""))))))
        (is (= :ok (:status (post "/api/console/graphs/import" {:files (sealed-files) :group-id a}))))
        (let [issued (post "/api/console/tokens/issue" {:graph-id "test/orders"})]
          (is (= 1 (:total (get-page "/api/console/tokens/page" (str "group=" a)))))
          (is (= 0 (:total (get-page "/api/console/tokens/page" (str "group=" b)))))
          (is (= :ok (:status (post "/api/console/groups/assign" {:graph-ids ["test/orders"] :group-id b}))))
          (is (= 0 (:total (get-page "/api/console/tokens/page" (str "group=" a)))))
          (is (= 1 (:total (get-page "/api/console/tokens/page" (str "group=" b)))))
          (is (= :ok (:status (post "/api/console/groups/delete" {:id b}))))
          (is (nil? (:group-id (first (:sources (get-page "/api/console/sources" ""))))))
          (is (= 1 (:total (get-page "/api/console/tokens/page" "group=ungrouped"))))
          (is (empty? (:findings (token/verify (:token runtime) (:token issued) {:now "2026-09-27T00:00:00Z"})))))))))


(deftest graph-source-inspection-is-versioned-and-does-not-expose-connections
  (with-console
    (fn [{:keys [handler root] :as ctx}]
      (let [cookie (signed-in ctx)
            post #(edn-body (request handler :post %1 %2 cookie))
            inspect (fn [query] (edn-body (handler {:request-method :get :uri "/api/console/graphs/sources"
                                                   :query-string query :headers {"cookie" (str console/session-cookie-name "=" cookie)}})))
            query "graph-id=support-dev%2Fsupport"
            doc (edn/read-string (slurp "../../modules/common/dialect/fixtures/semantic-v2.edn"))
            files (fn [v] (:files (semantic-artifact/encode-candidate
                                    (assoc-in doc [:publication :release/version] (str v)) {}
                                    {"sql" #((:validate (open.atlas.common.connector.spi/semantic-adapter "sql")) %)}
                                    {:workspace "support-dev" :graph "support" :version v})))
            sql {:type "sql" :label "Original production" :jdbc_url "jdbc:sqlite::memory:" :password "${SOURCE_INSPECT_SECRET}"}
            changed (assoc sql :label "Next release connection")
            save! (fn [id config revision] (post "/api/console/sources" {:id id :config config :expected-revision revision}))]
        (is (= :ok (:status (post "/api/console/sources/variable" {:name "SOURCE_INSPECT_SECRET" :value "not-in-graph-response"}))))
        (is (= :ok (:status (save! "prod" sql 0))))
        (is (= :ok (:status (post "/api/console/graphs/import" {:files (files 1) :activate? false :source-bindings {"db" "prod"}}))))
        (let [r (inspect query) row (first (:sources r))]
          (is (= 1 (:version r)) (pr-str r))
          (is (= "prod" (:source-id row)))
          (is (= :recorded (:origin row)))
          (is (= :bound (:binding-status row)))
          (is (= :current (:registry-status row)))
          (is (= ["resolver / lookup"] (:used-by row)))
          (is (= [{:name "SOURCE_INSPECT_SECRET" :present? true}] (:variables row)))
          (is (not (str/includes? (pr-str r) "jdbc:")))
          (is (not (str/includes? (pr-str r) "not-in-graph-response"))))
        (is (= :ok (:status (save! "prod" changed 1))))
        (is (= :changed (get-in (inspect query) [:sources 0 :registry-status])))
        (is (= :ok (:status (post "/api/console/graphs/import" {:files (files 2) :activate? false :source-bindings {"db" "prod"}}))))
        (is (= 2 (:version (inspect query))))
        (is (= :current (get-in (inspect query) [:sources 0 :registry-status])))
        (is (= :changed (get-in (inspect (str query "&version=1")) [:sources 0 :registry-status])))
        (is (= :ok (:status (post "/api/console/graphs/publish" {:graph-id "support-dev/support" :version 1 :expected-current nil}))))
        (is (= 1 (:version (inspect query))) "Default inspection follows the published version.")
        (is (= :error (:status (inspect (str query "&version=999")))))
        (is (= :error (:status (inspect "graph-id=..%2Fescape&version=1"))))
        (is (= :ok (:status (save! "alias" changed 2))))
        (is (= "prod" (get-in (inspect (str query "&version=2")) [:sources 0 :source-id])) "Equal configurations do not erase recorded identity.")
        (.delete (io/file root "source-binding-origins.edn"))
        (is (= :ambiguous (get-in (inspect (str query "&version=2")) [:sources 0 :registry-status])))
        (is (nil? (get-in (inspect (str query "&version=2")) [:sources 0 :source-id])))
        (is (= :untracked (get-in (inspect query) [:sources 0 :registry-status])))
        (is (= :ok (:status (save! "alias" sql 3))))
        (is (= :configuration-match (get-in (inspect (str query "&version=2")) [:sources 0 :origin])))
        (with-redefs [source-bindings/file-resolver (fn [& _] (fn [& _] (throw (ex-info "unavailable" {}))))]
          (is (= :missing (get-in (inspect query) [:sources 0 :binding-status]))))))))

(deftest shared-server-sources-support-many-graphs-and-all-resolver-assignments
  (with-console
    (fn [{:keys [handler runtime] :as ctx}]
      (let [cookie (signed-in ctx)
            post #(edn-body (request handler :post %1 %2 cookie))
            base (edn/read-string (slurp "../../modules/common/dialect/fixtures/semantic-v2.edn"))
            doc (-> base
                    (assoc-in [:publication :release/version] "1")
                    (update-in [:graph :graph/resolvers] conj "lookup-other")
                    (assoc-in [:execution :resolvers "lookup-other"] (get-in base [:execution :resolvers "lookup"]))
                    (assoc-in [:execution :resolvers "lookup-other" :output] [:support.case/other])
                    (assoc-in [:vocabulary :attributes :support.case/other] {:attribute/label "Other" :attribute/type :string :attribute/nullable? false})
                    (update-in [:vocabulary :entities "support.case" :entity/attributes] conj :support.case/other)
                    (assoc-in [:execution :sources "secondary"] {:source/kind "sql" :source/effects #{:read}})
                    (assoc-in [:ownership :resolvers "lookup-other"] "support")
                    (assoc-in [:execution :bindings "other-sql"] (assoc (get-in base [:execution :bindings "lookup-sql"])
                                                                      :binding/for [:resolver "lookup-other"] :binding/source "secondary" :binding/output-map {:support.case/other "title"}))
                    (assoc-in [:execution :selection [:resolver "lookup-other"]] "other-sql"))
            files (fn [workspace] (let [result (semantic-artifact/encode-candidate
                                           (assoc-in doc [:workspace :workspace/id] workspace) {}
                                           {"sql" #((:validate (open.atlas.common.connector.spi/semantic-adapter "sql")) %)}
                                           {:workspace workspace :graph "support" :version 1})] (is (:files result) (pr-str result)) (:files result)))
            input {:files (files "support-dev") :activate? false}
            mapping {"db" "shared" "secondary" "reporting"}]
        (is (= :ok (:status (post "/api/console/sources" {:id "shared" :config {:type "sql" :jdbc_url "jdbc:sqlite::memory:"} :expected-revision 0}))))
        (is (= :ok (:status (post "/api/console/sources" {:id "reporting" :config {:type "sql" :jdbc_url "jdbc:sqlite::memory:" :label "Reporting"} :expected-revision 1}))))
        (let [plan (post "/api/console/graphs/import/plan" input)]
          (is (= #{"lookup" "lookup-other"} (set (map :target-id (mapcat :uses (:sources plan))))))
          (is (false? (:would-import? plan))))
        (is (= :error (:status (post "/api/console/graphs/import" (assoc input :source-bindings {"db" "shared"})))))
        (is (nil? (graph/serving-version (:graph runtime) "support-dev/support")))
        (is (= :ok (:status (post "/api/console/graphs/import" (assoc input :source-bindings mapping)))))
        (is (= :ok (:status (post "/api/console/graphs/import" {:files (files "secondary-dev") :activate? false :source-bindings mapping}))))
        (is (= 2 (count (:sources (edn-body (request handler :get "/api/console/sources" nil cookie))))) "Import reuses registered connections; it does not clone them per Graph.")
        (doseq [workspace ["support-dev" "secondary-dev"]]
          (let [view (sources/graph-sources (:deps ctx) {"graph-id" (str workspace "/support")})]
            (is (= #{{:slot-id "db" :source-id "shared"} {:slot-id "secondary" :source-id "reporting"}}
                   (set (map #(select-keys % [:slot-id :source-id]) (:sources view)))) (pr-str view))))))))

(deftest independent-variable-register-and-renaming
  (with-console
    (fn [{:keys [handler home] :as ctx}]
      (let [cookie (signed-in ctx)
            post #(edn-body (request handler :post %1 %2 cookie))
            get-vars #(edn-body (request handler :get "/api/console/variables" nil cookie))]
        (is (= :ok (:status (post "/api/console/variables" {:name "OLD" :value "synthetic-private-value"}))))
        (is (= [{:name "OLD" :stored? true :present? true :used-by []}] (:variables (get-vars))))
        (is (= :ok (:status (post "/api/console/sources" {:id "shared" :config {:type "sql" :jdbc_url "jdbc:sqlite::memory:" :password "${OLD}"} :expected-revision 0}))))
        (is (= ["shared"] (:used-by (first (:variables (get-vars))))))
        (is (not (str/includes? (pr-str (get-vars)) "synthetic-private-value")))
        (is (:renamed? (post "/api/console/variables" {:name "NEW" :previous-name "OLD" :value ""})))
        (let [registry (edn-body (request handler :get "/api/console/sources" nil cookie))]
          (is (= "${NEW}" (get-in registry [:sources 0 :config :password])))
          (is (= 2 (:revision registry))))
        (is (= #{"NEW"} (set (map :name (:variables (get-vars))))))
        (is (= :error (:status (post "/api/console/variables/remove" {:name "NEW"}))))
        (is (= :ok (:status (post "/api/console/variables" {:name "UNUSED" :value "temporary"}))))
        (is (= :ok (:status (post "/api/console/variables/remove" {:name "UNUSED"}))))
        (is (= #{"NEW"} (set (map :name (:variables (get-vars))))))
        (is (= :error (:status (post "/api/console/variables" {:name "bad-name" :value "x"}))))))))

(deftest variable-rename-updates-saved-binding-references
  (with-console
    (fn [{:keys [root deps]}]
      (let [scope ["workspace" "graph" "1" "db"]
            config {:type "sql" :jdbc_url "jdbc:sqlite::memory:" :password "${BEFORE}"}
            file (io/file root "source-bindings.edn")]
        (is (= :ok (:status (sources/save-variable! deps {:name "BEFORE" :value "private-test-value"}))))
        (source-bindings/install-missing! (str file) {scope config})
        (is (= :error (:status (sources/remove-variable! deps {:name "BEFORE"}))))
        (is (= :ok (:status (sources/save-variable! deps {:name "AFTER" :previous-name "BEFORE" :value ""}))))
        (is (= "${AFTER}" (get-in (edn/read-string (slurp file)) [scope :password])))
        (is (= ["AFTER"] (mapv :name (:variables (sources/list-variables deps)))))
        (is (= :error (:status (sources/remove-variable! deps {:name "AFTER"}))))
        (is (= :ok (:status (sources/save-variable! deps {:name "OTHER" :value "other-value"}))))
        (is (= :error (:status (sources/save-variable! deps {:name "OTHER" :previous-name "AFTER" :value ""}))))
        (is (= "${AFTER}" (get-in (edn/read-string (slurp file)) [scope :password])))
        (is (not (str/includes? (pr-str (sources/list-variables deps)) "private-test-value")))))))

(deftest source-archive-restore-delete-lifecycle
  (with-console
    (fn [{:keys [handler deps] :as ctx}]
      (let [cookie (signed-in ctx)
            post #(edn-body (request handler :post %1 %2 cookie))
            change #(post "/api/console/sources/lifecycle" {:id "shared" :action %1 :expected-revision %2})
            doc (assoc-in (edn/read-string (slurp "../../modules/common/dialect/fixtures/semantic-v2.edn")) [:publication :release/version] "1")
            files (:files (semantic-artifact/encode-candidate doc {}
                           {"sql" #((:validate (open.atlas.common.connector.spi/semantic-adapter "sql")) %)}
                           {:workspace "support-dev" :graph "support" :version 1}))]
        (is (= :ok (:status (post "/api/console/sources" {:id "shared" :config {:type "sql" :jdbc_url "jdbc:sqlite::memory:"} :expected-revision 0}))))
        (is (= :ok (:status (change :archive 1))))
        (is (empty? (get-in (sources/prepare deps doc {}) [:sources 0 :options])))
        (is (seq (:findings (sources/prepare deps doc {"db" "shared"})) ))
        (is (= :error (:status (change :restore 1))) "Stale changes are refused.")
        (is (= :ok (:status (change :restore 2))))
        (is (= :ok (:status (post "/api/console/graphs/import" {:files files :source-bindings {"db" "shared"} :activate? false}))))
        (is (= :error (:status (change :delete 3))) "Retained releases protect their Source registration.")
        (is (= :ok (:status (change :archive 3))))
        (is (empty? (:findings (sources/prepare deps doc {}))) "Saved release bindings still resolve after archiving.")
        (is (= :ok (:status (post "/api/console/sources" {:id "unused" :config {:type "sql" :jdbc_url "jdbc:sqlite::memory:"} :expected-revision 4}))))
        (is (= :ok (:status (post "/api/console/sources/lifecycle" {:id "unused" :action :delete :expected-revision 5}))))
        (is (= ["shared"] (mapv :id (:sources (sources/list-sources deps)))))))))

(deftest activity-query-details-correlate-and-whitelist
  (let [home (.toFile (java.nio.file.Files/createTempDirectory
                       "activity-details-" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file home "logs" "app.jsonl")]
    (io/make-parents file)
    (try
      (spit file (str "{\"event\":\"query-executed\",\"request-id\":\"wanted\",\"eql\":\"[:a/name]\",\"token-id\":\"t1\",\"remote-addr\":\"127.0.0.1\",\"authorization\":\"never expose\"}\n"
                      "malformed partial line\n"
                      "{\"event\":\"query-executed\",\"request-id\":\"other\",\"eql\":\"[:b/name]\"}\n"))
      (is (= {"wanted" [{:eql "[:a/name]" :token-id "t1" :remote-addr "127.0.0.1"}]}
             (#'console/query-details (str home) [{:request-id "wanted"}])))
      (is (= {} (#'console/query-details (str home) [{:request-id "missing"}])) )
      (finally (.delete file) (.delete (.getParentFile file)) (.delete home)))))
